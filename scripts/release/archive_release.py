"""DomiDex v27+ immutable release archive. Python 3.11+, JDK, bundletool; no pip.

This tool never signs, rebuilds, modifies or uploads its inputs. Production archives
are append-only. Historical bundles are usable only in the isolated dry-run root.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone, timedelta
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from native_elf import inspect_elf, verify_symbol_candidate
from native_licenses import archive_licenses, prepare_license_evidence
from official_symbols import BUILD_ID, original_libcxx

ROOT = Path(__file__).resolve().parents[2]
TOOLS = Path(__file__).resolve().parent
MAPPING = 'BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map'
CERT = '837D12C024DFED3904E13CEB241B9B99584AD54AE8AA9D4F58084BBC76B70EA2'
ABIS = {'arm64-v8a': (64, 183), 'armeabi-v7a': (32, 40),
        'x86': (32, 3), 'x86_64': (64, 62)}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def command(args):
    result = subprocess.run([str(arg) for arg in args], cwd=ROOT, capture_output=True,
                            encoding='utf-8', errors='replace', timeout=240)
    if result.returncode:
        # Do not expose environment, credentials or unbounded command output.
        raise ValueError(f'{Path(str(args[0])).name} verification failed (exit {result.returncode})')
    return result.stdout.strip()


def json_write(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def zip_entries(archive):
    names = archive.namelist()
    require(len(names) == len(set(names)), 'Duplicate ZIP entries')
    for name in names:
        parts = PurePosixPath(name).parts
        info = archive.getinfo(name)
        require(info.orig_filename == name, 'Non-canonical ZIP path')
        require(name and not name.startswith('/') and '\\' not in name and ':' not in name
                and '..' not in parts and '.' not in name.split('/'), 'Unsafe ZIP path')
        require(not info.flag_bits & 1, 'Encrypted ZIP entry')
        require((info.external_attr >> 16) & 0o170000 != 0o120000, 'ZIP symlink rejected')
    require(archive.testzip() is None, 'ZIP integrity check failed')
    return names


def check_destination(output, code, dry_run):
    output = Path(output).resolve()
    if dry_run:
        base = (ROOT / 'artifacts/release-pipeline-tests').resolve()
        require(output.is_relative_to(base) and output != base,
                'Dry-run output must be a new child of artifacts/release-pipeline-tests')
    else:
        require(code >= 27, 'Release 26 and earlier are immutable; production requires versionCode >= 27')
        require(output == (ROOT / 'releases' / f'v{code}').resolve(),
                'Production archive must be releases/v<actual-versionCode>')
    require(not output.exists(), 'Archive already exists; refusing to overwrite')
    return output


def inspect_bundle(aab, java, bundletool, mapping=None):
    aab_hash = file_digest(aab)
    with zipfile.ZipFile(aab) as bundle:
        names = zip_entries(bundle)
        require(MAPPING in names, 'Release R8 mapping missing in AAB')
        embedded_mapping = bundle.read(MAPPING)
        require(embedded_mapping, 'R8 mapping is empty')
        if mapping:
            require(file_digest(mapping) == digest(embedded_mapping),
                    'Sidecar mapping does not match this exact AAB')
        natives = []
        for name in names:
            match = re.fullmatch(r'([^/]+)/lib/([^/]+)/([^/]+\.so)', name)
            if not match:
                continue
            module, abi, filename = match.groups()
            require(abi in ABIS, f'Unsupported ABI: {abi}')
            data = bundle.read(name)
            elf = inspect_elf(data)
            require((elf['elf_class'], elf['machine']) == ABIS[abi], 'ELF/ABI mismatch')
            natives.append({'entry': name, 'module': module, 'abi': abi, 'filename': filename,
                            'sha256': digest(data), 'bytes': len(data), 'elf': elf, 'data': data})
        embedded_symbols = {name: bundle.read(name) for name in names
                            if name.startswith('BUNDLE-METADATA/com.android.tools.build.debugsymbols/')
                            and not bundle.getinfo(name).is_dir()}
    # bundletool checks AAB structure/schema; JarFile verifies every actual payload byte.
    command([java, '-jar', bundletool, 'validate', f'--bundle={aab}'])
    manifest_xml = command([java, '-jar', bundletool, 'dump', 'manifest', f'--bundle={aab}', '--module=base'])
    manifest = ET.fromstring(manifest_xml)
    ns = '{http://schemas.android.com/apk/res/android}'
    package = manifest.get('package')
    code = int(manifest.attrib[ns + 'versionCode'])
    name = manifest.attrib[ns + 'versionName']
    require(package == 'com.dominik.cardvault', 'Not a DomiDex application bundle')
    application = manifest.find('application')
    require(application is not None and application.get(ns + 'debuggable', 'false') == 'false',
            'Debuggable AAB is not a release')
    signature = json.loads(command([java, TOOLS / 'VerifyBundle.java', aab, CERT]))
    require(file_digest(aab) == aab_hash, 'AAB changed during verification')
    return {'aab_sha256': aab_hash, 'package': package, 'version_code': code,
            'version_name': name, 'signature': signature, 'mapping_sha256': digest(embedded_mapping)}, \
        embedded_mapping, natives, embedded_symbols


def resolve_origins(natives, metadata):
    """Resolve the actual AAR bytes, not a filename guess or Gradle cache version guess."""
    origins = {item['entry']: [] for item in natives}
    seen_artifacts = set()
    for artifact in metadata['artifacts']:
        identity = (artifact['coordinate'], artifact['path'], artifact['sha256'])
        if identity in seen_artifacts:
            continue
        seen_artifacts.add(identity)
        path = Path(artifact['path'])
        if path.suffix.lower() != '.aar':
            continue
        require(file_digest(path) == artifact['sha256'], 'Resolved dependency changed since metadata capture')
        with zipfile.ZipFile(path) as aar:
            entries = set(aar.namelist())
            for item in natives:
                member = f"jni/{item['abi']}/{item['filename']}"
                if member not in entries:
                    continue
                data = aar.read(member)
                same = digest(data) == item['sha256']
                proof = None if same else verify_symbol_candidate(item['data'], data)
                if same or proof['accepted']:
                    origins[item['entry']].append({
                        'coordinate': artifact['coordinate'], 'aar_path': str(path),
                        'aar_sha256': artifact['sha256'], 'member': member,
                        'source_sha256': digest(data), 'match': 'byte-identical' if same else 'runtime-ELF-identical',
                        'data': data})
    for item in natives:
        matches = origins[item['entry']]
        require(len(matches) == 1, f"Native origin not unique/proven: {item['entry']} ({len(matches)} matches)")
        item['origin'] = matches[0]


def candidate_files(symbol_dirs, symbol_zips):
    """Explicit candidate locations only. Never recursively copy arbitrary build trees."""
    for directory in symbol_dirs:
        require(directory.is_dir(), f'Symbol directory missing: {directory.name}')
        for path in sorted(directory.rglob('*')):
            if path.is_file() and not path.is_symlink() and re.search(r'\.so(?:\.(?:dbg|sym|debug))?$', path.name):
                abi = next((part for part in reversed(path.parts[:-1]) if part in ABIS), None)
                if abi:
                    yield abi, re.sub(r'\.(?:dbg|sym|debug)$', '', path.name), path.read_bytes(), {
                        'source': 'explicit-candidate-directory', 'relative_path': path.relative_to(directory).as_posix()}
    for path in symbol_zips:
        require(path.is_file(), f'Symbol ZIP missing: {path.name}')
        with zipfile.ZipFile(path) as archive:
            for name in zip_entries(archive):
                match = re.fullmatch(r'([^/]+)/([^/]+\.so)(?:\.(?:dbg|sym|debug))?', name)
                if match and match[1] in ABIS:
                    yield match[1], match[2], archive.read(name), {'source': 'candidate-zip', 'zip_sha256': file_digest(path), 'member': name}


def select_symbols(natives, symbol_dirs, symbol_zips, cache, download, embedded):
    candidates = list(candidate_files(symbol_dirs, symbol_zips))
    attempts, accepted = [], {}
    for item in natives:
        candidates.append((item['abi'], item['filename'], item['origin']['data'], {
            'source': item['origin']['coordinate'], 'member': item['origin']['member']}))
        if item['filename'] == 'libc++_shared.so' and item['abi'] == 'arm64-v8a' and item['elf']['build_id'] == BUILD_ID:
            try:
                data, provenance = original_libcxx(cache, download)
                candidates.append((item['abi'], item['filename'], data, provenance))
            except (OSError, ValueError) as error:
                attempts.append({'entry': item['entry'], 'accepted': False, 'reason': str(error)})
    for name, data in embedded.items():
        match = re.fullmatch(r'BUNDLE-METADATA/com.android.tools.build.debugsymbols/([^/]+)/([^/]+\.so)(?:\.(?:dbg|sym|debug))?', name)
        require(match is not None and match[1] in ABIS, 'Unrecognized embedded native symbol metadata')
        candidates.append((match[1], match[2], data, {'source': 'embedded-AAB', 'member': name}))
    by_key = {}
    for item in natives:
        key = f"{item['abi']}/{item['filename']}"
        require(key not in by_key or by_key[key]['sha256'] == item['sha256'],
                'Different module binaries collide in Play symbol ZIP')
        by_key[key] = item
    for abi, filename, data, provenance in candidates:
        key = f'{abi}/{filename}'
        if key not in by_key:
            attempts.append({'entry': key, 'accepted': False, 'reason': 'Not shipped in this AAB', 'source': provenance})
            continue
        verdict = verify_symbol_candidate(by_key[key]['data'], data)
        attempts.append({'entry': key, 'sha256': digest(data), 'source': provenance, **verdict})
        if provenance['source'] == 'embedded-AAB':
            require(verdict['accepted'], 'Unverifiable symbols already embedded in AAB; rebuild with proven originals required')
        if verdict['accepted']:
            old = accepted.get(key)
            if not old or (old['verification']['level'] != 'FULL' and verdict['level'] == 'FULL'):
                accepted[key] = {'data': data, 'source': provenance, 'verification': verdict}
    return accepted, attempts, by_key


def verify_symbol_zip(path, shipped):
    with zipfile.ZipFile(path) as archive:
        names = zip_entries(archive)
        require(names, 'Empty symbols ZIP must not be uploaded')
        require(sum(info.file_size for info in archive.infolist()) <= 1_600_000_000, 'Symbol ZIP exceeds Play limit')
        for name in names:
            require(name in shipped, 'Unexpected Play symbol ZIP entry')
            require(verify_symbol_candidate(shipped[name]['data'], archive.read(name))['accepted'],
                    'Final ZIP symbol/AAB mismatch')


def verify_build_binding(metadata, info, started_at, git_commit):
    require(metadata['outputs']['aab']['sha256'] == info['aab_sha256'], 'Metadata does not belong to this AAB')
    require(metadata['outputs']['mapping']['sha256'] == info['mapping_sha256'], 'Metadata mapping mismatch')
    require(metadata['release']['version_code'] == info['version_code'] and
            metadata['release']['version_name'] == info['version_name'], 'Metadata version mismatch')
    require(metadata['release']['minify_enabled'], 'Unexpected R8 configuration')
    require(started_at and git_commit, 'Production archive requires build start and Git commit from release helper')
    start = datetime.fromisoformat(started_at.replace('Z', '+00:00'))
    captured = datetime.fromisoformat(metadata['captured_at_utc'].replace('Z', '+00:00'))
    require(start.tzinfo is not None and captured.tzinfo is not None, 'Build timestamps must include timezone')
    require(start <= captured <= datetime.now(timezone.utc) + timedelta(seconds=5),
            'Stale/future metadata: export must run after this build started')
    require(command(['git', 'rev-parse', 'HEAD']) == git_commit, 'Git commit changed during build')


def archive(args):
    metadata = json.loads(args.metadata.read_text(encoding='utf-8-sig'))
    require(metadata['schema_version'] == 1, 'Unsupported metadata schema')
    info, mapping, natives, embedded = inspect_bundle(args.aab, args.java, args.bundletool, args.mapping)
    output = check_destination(args.output or ROOT / 'releases' / f"v{info['version_code']}", info['version_code'], args.dry_run)
    if not args.dry_run:
        verify_build_binding(metadata, info, args.build_started_at, args.build_git_commit)
    resolve_origins(natives, metadata)
    accepted, attempts, shipped = select_symbols(natives, args.symbol_dir, args.symbol_zip,
                                                 args.symbol_cache, args.allow_download, embedded)
    evidence_preparation = None
    try:
        evidence_preparation = prepare_license_evidence(args.license_evidence, allow_download=args.allow_download)
    except OSError:
        # Network failure is not permission to substitute evidence. Archive only
        # available hash-verified documents and let the license manifest list gaps.
        evidence_preparation = {'warning': 'Public evidence download unavailable; only existing verified cache used.'}
    output.parent.mkdir(parents=True, exist_ok=True)
    # Failed verification never publishes a final directory. The private temporary
    # directory contains only explicit diagnostic/public files, never credentials.
    with tempfile.TemporaryDirectory(prefix='.archive-staging-', dir=output.parent) as temp:
        stage = Path(temp) / 'archive'
        stage.mkdir()
        shutil.copyfile(args.aab, stage / f"DomiDex-v{info['version_code']}.aab")
        (stage / 'mapping.txt').write_bytes(mapping)
        inventory = []
        for item in natives:
            target = stage / 'native-libraries' / item['entry']
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(item['data'])
            row = {key: value for key, value in item.items() if key not in ('data', 'origin')}
            row['origin'] = {key: value for key, value in item['origin'].items() if key not in ('data', 'aar_path')}
            row['symbol_status'] = 'verified' if f"{item['abi']}/{item['filename']}" in accepted else 'unavailable'
            inventory.append(row)
        if accepted:
            with zipfile.ZipFile(stage / 'native-debug-symbols.zip', 'w', zipfile.ZIP_DEFLATED) as archive_file:
                for name, item in sorted(accepted.items()):
                    entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                    entry.compress_type = zipfile.ZIP_DEFLATED
                    archive_file.writestr(entry, item['data'])
            verify_symbol_zip(stage / 'native-debug-symbols.zip', shipped)
        json_write(stage / 'native-inventory.json', inventory)
        json_write(stage / 'symbol-verification.json', attempts)
        (stage / 'build-ids.txt').write_text('\n'.join(
            f"{i['entry']} | {i['origin']['coordinate']} | Build-ID={i['elf']['build_id'] or 'MISSING'} | SHA256={i['sha256']}"
            for i in inventory) + '\n', encoding='utf-8')
        (stage / 'dependency-versions.txt').write_text('\n'.join(sorted(set(metadata['dependency_versions']))) + '\n', encoding='utf-8')
        deps = {i['origin']['coordinate']: {k: v for k, v in i['origin'].items() if k != 'data'} for i in natives}
        licenses = archive_licenses(stage, list(deps.values()), args.license_evidence)
        environment = {
            'mode': 'DRY-RUN: not a new build/release; current tools are not historical build provenance' if args.dry_run else 'release-build',
            'archive_time_utc': datetime.now(timezone.utc).isoformat(),
            'build_started_at_utc': args.build_started_at,
            'build_git_commit': args.build_git_commit,
            'metadata_captured_at_utc': metadata['captured_at_utc'],
            'source_commit_at_archive': command(['git', 'rev-parse', 'HEAD']),
            'source_tags_at_archive': command(['git', 'tag', '--points-at', 'HEAD']).splitlines(),
            'source_dirty_at_archive': bool(command(['git', 'status', '--porcelain'])),
            'dirty_source_notice': 'Uncommitted sources are NOT archived; commit before a reproducible production release.',
            'tools': {k: v for k, v in metadata['tools'].items() if k not in ('java_home',)},
            'release_config': {k: v for k, v in metadata['release'].items() if k not in ('ndk_path',)},
            'ndk_configured_path': bool(metadata['release'].get('ndk_path')),
            'java_verifier': command([args.java, '--version']).splitlines(),
            **info}
        json_write(stage / 'build-environment.txt', environment)
        summary = {
            **info, 'dry_run': args.dry_run,
            'checks': ['AAB ZIP/CRC/path uniqueness', 'bundletool validate', 'every payload JAR signature + pinned upload certificate',
                       'manifest package/version/release', 'R8 mapping extracted from exact AAB', 'resolved AAR origin hashes',
                       'ELF ABI/Build-ID inventory', 'symbol Build-ID + runtime bytes + symbol addresses', 'final Play symbol ZIP reverified'],
            'native_libraries': len(natives), 'abis': sorted({i['abi'] for i in natives}),
            'symbols_verified': len(accepted), 'symbols_unavailable': sorted(set(shipped) - set(accepted)),
            'native_warning_may_remain': len(accepted) < len(shipped),
            'licenses': licenses,
            'license_evidence_preparation': evidence_preparation,
            'published_to_google_play': False,
            'next_step': ('DRY-RUN ONLY: nothing to upload.' if args.dry_run else
                          'Review gaps; upload exact AAB and its matching native-debug-symbols.zip to this version. R8 mapping is embedded.' if accepted else
                          'No verified native symbol ZIP exists. Review gaps and upload only this AAB; native warning remains. R8 mapping is embedded.')}
        json_write(stage / 'release-verification.txt', summary)
        require(file_digest(args.aab) == info['aab_sha256'] and
                file_digest(stage / f"DomiDex-v{info['version_code']}.aab") == info['aab_sha256'], 'AAB source/copy changed')
        require(file_digest(stage / 'mapping.txt') == info['mapping_sha256'], 'Archived R8 mapping mismatch')
        hashes = {p.relative_to(stage).as_posix(): file_digest(p) for p in sorted(stage.rglob('*')) if p.is_file()}
        json_write(stage / 'artifact-sha256.json', hashes)
        require(not output.exists(), 'Archive created concurrently; refusing overwrite')
        stage.rename(output)
    print(json.dumps({'archive': str(output), 'version_code': info['version_code'],
                      'verified_symbols': len(accepted), 'native_libraries': len(natives), 'dry_run': args.dry_run}, indent=2))
    return output


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--aab', type=Path, required=True)
    p.add_argument('--metadata', type=Path, required=True)
    p.add_argument('--mapping', type=Path)
    p.add_argument('--output', type=Path)
    p.add_argument('--java', type=Path, required=True)
    p.add_argument('--bundletool', type=Path, required=True)
    p.add_argument('--dry-run', action='store_true')
    p.add_argument('--build-started-at')
    p.add_argument('--build-git-commit')
    p.add_argument('--symbol-dir', type=Path, action='append', default=[])
    p.add_argument('--symbol-zip', type=Path, action='append', default=[])
    p.add_argument('--symbol-cache', type=Path, default=ROOT / 'artifacts/release-symbol-cache')
    p.add_argument('--license-evidence', type=Path, default=ROOT / 'artifacts/release-license-cache')
    p.add_argument('--allow-download', action='store_true', help='Allow only pinned official symbol downloads')
    return p


if __name__ == '__main__':
    try:
        archive(parser().parse_args())
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f'Release archive NOT published: {error}', file=sys.stderr)
        sys.exit(1)
