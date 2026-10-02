"""Build a run-scoped debug-only WorkManager proof without replacing ordinary APKs."""
import sys
sys.dont_write_bytecode = True
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from datetime import datetime, timezone
from uuid import uuid4

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUTPUT = ROOT / 'artifacts/release27-engineering-20261002/workmanager-proof'
JAVA = Path('C:/Program Files/Android/Android Studio/jbr')

def digest(file):
    value = hashlib.sha256()
    with file.open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(data)
    return value.hexdigest()

def snapshot():
    inputs = ['app/src', 'app/schemas', 'app/build.gradle.kts', 'build.gradle.kts',
              'settings.gradle.kts', 'gradle.properties', 'gradle', 'gradlew', 'gradlew.bat',
              'app/google-services.json', 'local.properties', 'scripts/verification/workmanager-proof']
    preserved = ['app/build/outputs/apk', 'app/build/outputs/bundle', 'app/build/outputs/mapping',
                 'releases/google-play-v26-closed-alpha-2026-09-23']
    def collect(roots):
        files = []
        for relative in roots:
            item = ROOT / relative
            if not item.exists():
                raise ValueError('Required snapshot input missing: ' + relative)
            files.extend(item.rglob('*') if item.is_dir() else [item])
        result = {}
        for item in sorted(set(files)):
            if item.is_symlink():
                raise ValueError('Unexpected link')
            if item.is_file():
                result[item.relative_to(ROOT).as_posix()] = {'bytes': item.stat().st_size, 'sha256': digest(item)}
        return result
    return {'inputs': collect(inputs), 'preserved': collect(preserved)}

def main():
    if sys.argv[1:] != ['--build']:
        raise ValueError('Use exactly --build. This script never installs or runs anything.')
    if (ROOT / 'keystore.properties').exists() or any(key.startswith('DOMIDEX_UPLOAD_') for key in os.environ):
        raise ValueError('No release credentials allowed')
    for name in ('WorkManagerProofRunner.kt', 'PrivateBinderWorkManagerInstrumentedTest.kt'):
        if len(list((HERE / 'androidTest').rglob(name))) != 1:
            raise ValueError('Reviewed test source missing: ' + name)
    run_id = uuid4().hex
    directory = OUTPUT / run_id
    directory.mkdir(parents=True, exist_ok=False)
    before = snapshot()
    record = {'runId': run_id, 'package': 'com.dominik.cardvault.wmproof.r' + run_id,
              'startedAt': datetime.now(timezone.utc).isoformat(), **before}
    injection = directory / 'injected-source'
    injection.mkdir()
    # Compile the existing reviewed local-only transport gate byte-for-byte.
    original_gate = ROOT / 'app/src/androidTest/java/com/dominik/cardvault/data/sync/PrivatePullDemoGrpcGate.kt'
    shutil.copyfile(original_gate, injection / original_gate.name)
    injected = injection / original_gate.name
    gate_hash = digest(injected)
    if gate_hash != before['inputs'][original_gate.relative_to(ROOT).as_posix()]['sha256']:
        raise ValueError('Copied transport gate differs from original')
    record['injectedGateSha256'] = gate_hash
    (directory / 'before.json').write_text(json.dumps(record, indent=2), encoding='utf-8')
    env = {key: value for key, value in os.environ.items() if key.upper() in {
        'SYSTEMROOT', 'WINDIR', 'SYSTEMDRIVE', 'TEMP', 'TMP', 'USERPROFILE', 'LOCALAPPDATA', 'APPDATA',
        'PROCESSOR_ARCHITECTURE', 'NUMBER_OF_PROCESSORS'}}
    env['JAVA_HOME'] = str(JAVA)
    env['PATH'] = str(JAVA / 'bin') + os.pathsep + str(Path(env.get('SystemRoot', 'C:/Windows')) / 'System32')
    command = [str(ROOT / 'gradlew.bat'), '--no-daemon', '--no-configuration-cache', '--no-build-cache',
               '--console=plain', '--init-script', str(HERE / 'proof.init.gradle'), '-PwmProofRun=' + run_id,
               '-PdomidexAppCheck=false', ':app:assembleDebug', ':app:assembleDebugAndroidTest']
    print('WorkManager proof build: ' + str(directory), flush=True)
    with (directory / 'build.log').open('x', encoding='utf-8') as log:
        result = subprocess.run(command, cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=log,
                                stderr=subprocess.STDOUT, timeout=900, shell=False,
                                creationflags=subprocess.CREATE_NO_WINDOW)
    after = snapshot()
    preserved = before == after and digest(injected) == gate_hash
    classes = directory / 'app-build/intermediates/built_in_kotlinc/debugAndroidTest/compileDebugAndroidTestKotlin/classes/com/dominik/cardvault'
    proof_classes = all((classes / 'data/sync' / name).is_file() for name in (
        'WorkManagerProofRunner.class', 'PrivateBinderWorkManagerInstrumentedTest.class', 'PrivatePullDemoGrpcGate.class'))
    proof_classes = proof_classes and not (classes / 'CardVaultDatabaseTest.class').exists()
    artifacts = {}
    for relative in ('outputs/apk/debug/app-debug.apk', 'outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
        file = directory / 'app-build' / relative
        if file.is_file():
            artifacts[relative] = {'bytes': file.stat().st_size, 'sha256': digest(file)}
    outcome = {'runId': run_id, 'package': record['package'], 'finishedAt': datetime.now(timezone.utc).isoformat(),
               'exitCode': result.returncode, 'inputsAndOldOutputsUnchanged': preserved,
               'proofClassesVerified': proof_classes, 'injectedGateSha256': gate_hash, 'artifacts': artifacts}
    (directory / 'after.json').write_text(json.dumps(after, indent=2), encoding='utf-8')
    (directory / 'build-result.json').write_text(json.dumps(outcome, indent=2), encoding='utf-8')
    print(json.dumps(outcome), flush=True)
    if result.returncode or not preserved or not proof_classes or len(artifacts) != 2:
        raise SystemExit(1)

if __name__ == '__main__':
    main()
