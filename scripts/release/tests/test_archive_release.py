"""Small offline tests. No Gradle build, real key access or historical release writes."""
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from datetime import datetime, timezone, timedelta
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import archive_release as archive
import official_symbols
from test_native_elf import elf


class ArchiveTest(unittest.TestCase):
    def test_production_binding_requires_fresh_exact_metadata(self):
        now = datetime.now(timezone.utc)
        info = {'aab_sha256': 'bundle', 'mapping_sha256': 'mapping', 'version_code': 27, 'version_name': 'alpha'}
        metadata = {'outputs': {'aab': {'sha256': 'bundle'}, 'mapping': {'sha256': 'mapping'}},
                    'release': {'version_code': 27, 'version_name': 'alpha', 'minify_enabled': True},
                    'captured_at_utc': now.isoformat()}
        with patch.object(archive, 'command', return_value='commit'):
            archive.verify_build_binding(metadata, info, (now - timedelta(seconds=1)).isoformat(), 'commit')
            with self.assertRaisesRegex(ValueError, 'Stale/future'):
                archive.verify_build_binding(metadata, info, (now + timedelta(seconds=30)).isoformat(), 'commit')
            with self.assertRaisesRegex(ValueError, 'Git commit changed'):
                archive.verify_build_binding(metadata, info, now.isoformat(), 'different')
            metadata['outputs']['aab']['sha256'] = 'stale'
            with self.assertRaisesRegex(ValueError, 'does not belong'):
                archive.verify_build_binding(metadata, info, now.isoformat(), 'commit')

    def test_historical_release_and_arbitrary_destination_rejected(self):
        with self.assertRaisesRegex(ValueError, 'immutable'):
            archive.check_destination(archive.ROOT / 'releases/v26', 26, False)
        with self.assertRaises(ValueError):
            archive.check_destination(archive.ROOT / 'releases/new-v27', 27, False)
        with self.assertRaises(ValueError):
            archive.check_destination(archive.ROOT / 'releases/v26', 26, True)
        self.assertEqual(archive.check_destination(archive.ROOT / 'releases/v999997', 999997, False),
                         (archive.ROOT / 'releases/v999997').resolve())

    def test_existing_archive_not_overwritten(self):
        base = archive.ROOT / 'artifacts/release-pipeline-tests'
        base.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as temp:
            with self.assertRaisesRegex(ValueError, 'already exists'):
                archive.check_destination(Path(temp), 26, True)

    def test_zip_paths_and_duplicates_rejected(self):
        for name in ('../escape.so', '/absolute', 'C:/secret', 'foo\\bar'):
            with self.subTest(name=name):
                data = io.BytesIO()
                with zipfile.ZipFile(data, 'w') as z:
                    z.writestr(name.replace('\\', '/'), b'data')
                # Windows ZipInfo normalizes backslashes when *writing*. Model
                # an externally received ZIP without letting it repair our fixture.
                if '\\' in name:
                    data = io.BytesIO(data.getvalue().replace(name.replace('\\', '/').encode(), name.encode()))
                with zipfile.ZipFile(data) as z, self.assertRaises(ValueError):
                    archive.zip_entries(z)

    def test_zip_duplicate_rejected(self):
        import warnings
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as z, warnings.catch_warnings():
            warnings.simplefilter('ignore')
            z.writestr('same', b'a')
            z.writestr('same', b'b')
        with zipfile.ZipFile(data) as z, self.assertRaisesRegex(ValueError, 'Duplicate'):
            archive.zip_entries(z)

    def test_exact_aar_origin_required(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'source.aar'
            binary = elf()
            with zipfile.ZipFile(path, 'w') as z:
                z.writestr('jni/arm64-v8a/libtest.so', binary)
            metadata = {'artifacts': [{'path': str(path), 'coordinate': 'vendor:module:1', 'sha256': archive.file_digest(path)}]}
            item = {'entry': 'base/lib/arm64-v8a/libtest.so', 'filename': 'libtest.so', 'abi': 'arm64-v8a',
                    'data': binary, 'sha256': archive.digest(binary)}
            archive.resolve_origins([item], metadata)
            self.assertEqual(item['origin']['coordinate'], 'vendor:module:1')
            item['data'] = elf(build_id=b'wrong')
            item['sha256'] = archive.digest(item['data'])
            with self.assertRaisesRegex(ValueError, 'not unique/proven'):
                archive.resolve_origins([item], metadata)

    def test_stale_dependency_hash_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'source.aar'
            path.write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'changed since'):
                archive.resolve_origins([], {'artifacts': [{'path': str(path), 'coordinate': 'vendor:module:1', 'sha256': 'bad'}]})

    def test_false_candidate_excluded_from_symbol_package(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp) / 'arm64-v8a'
            folder.mkdir()
            (folder / 'libtest.so').write_bytes(elf(symbols=True, build_id=b'wrong'))
            binary = elf()
            item = {'entry': 'base/lib/arm64-v8a/libtest.so', 'abi': 'arm64-v8a', 'filename': 'libtest.so',
                    'data': binary, 'elf': archive.inspect_elf(binary), 'sha256': archive.digest(binary),
                    'origin': {'data': binary, 'coordinate': 'vendor:module:1', 'member': 'jni/arm64-v8a/libtest.so'}}
            accepted, attempts, _ = archive.select_symbols([item], [Path(temp)], [], Path(temp), False, {})
            self.assertEqual(accepted, {})
            self.assertTrue(all(not row['accepted'] for row in attempts))
            (folder / 'libtest.so').write_bytes(elf(symbols=True))
            accepted, _, shipped = archive.select_symbols([item], [Path(temp)], [], Path(temp), False, {})
            self.assertEqual(list(accepted), ['arm64-v8a/libtest.so'])
            zpath = Path(temp) / 'symbols.zip'
            with zipfile.ZipFile(zpath, 'w') as z:
                z.writestr('arm64-v8a/libtest.so', accepted['arm64-v8a/libtest.so']['data'])
            archive.verify_symbol_zip(zpath, shipped)

    def test_empty_and_mismatched_final_symbol_zip_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'symbols.zip'
            with zipfile.ZipFile(path, 'w'):
                pass
            with self.assertRaisesRegex(ValueError, 'Empty'):
                archive.verify_symbol_zip(path, {})
            with zipfile.ZipFile(path, 'w') as z:
                z.writestr('arm64-v8a/libtest.so', elf(symbols=True, code=b'bad!'))
            with self.assertRaisesRegex(ValueError, 'mismatch'):
                archive.verify_symbol_zip(path, {'arm64-v8a/libtest.so': {'data': elf()}})

    def test_invalid_embedded_symbols_block_release(self):
        binary = elf()
        item = {'entry': 'base/lib/arm64-v8a/libtest.so', 'abi': 'arm64-v8a', 'filename': 'libtest.so',
                'data': binary, 'elf': archive.inspect_elf(binary), 'sha256': archive.digest(binary),
                'origin': {'data': binary, 'coordinate': 'vendor:module:1', 'member': 'jni/arm64-v8a/libtest.so'}}
        with self.assertRaisesRegex(ValueError, 'Unverifiable symbols already embedded'):
            archive.select_symbols([item], [], [], Path('.'), False,
                {'BUNDLE-METADATA/com.android.tools.build.debugsymbols/arm64-v8a/libtest.so.sym': elf(symbols=True, build_id=b'wrong')})

    def test_wrong_mapping_is_rejected_before_java(self):
        with tempfile.TemporaryDirectory() as temp:
            aab, mapping = Path(temp) / 'fake.aab', Path(temp) / 'mapping.txt'
            with zipfile.ZipFile(aab, 'w') as z:
                z.writestr(archive.MAPPING, b'correct')
            mapping.write_bytes(b'wrong')
            with self.assertRaisesRegex(ValueError, 'mapping does not match'), patch.object(archive, 'command') as command:
                archive.inspect_bundle(aab, Path('java'), Path('bundletool'), mapping)
            command.assert_not_called()

    def test_symbol_cache_is_hash_pinned(self):
        with tempfile.TemporaryDirectory() as temp:
            cache = Path(temp)
            with self.assertRaises(FileNotFoundError):
                official_symbols.original_libcxx(cache, False)
            wrong = cache / 'ndk-r27c/arm64-v8a/libc++_shared.so'
            wrong.parent.mkdir(parents=True)
            wrong.write_bytes(b'fake symbols')
            with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                official_symbols.original_libcxx(cache, False)
            self.assertEqual(wrong.read_bytes(), b'fake symbols')


if __name__ == '__main__':
    unittest.main()
