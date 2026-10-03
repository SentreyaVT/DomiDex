"""Build only a fresh disposable Room history debug APK pair; never installs."""
import sys
sys.dont_write_bytecode = True
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
from datetime import datetime, timezone
from uuid import uuid4
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUTPUT = ROOT / 'artifacts/release27-engineering-20261003/history-proof'
JAVA = Path('C:/Program Files/Android/Android Studio/jbr')
# Reuse unchanged reviewed preservation code, not its build or main entry point.
shared = runpy.run_path(str(ROOT / 'scripts/verification/workmanager-proof/build.py'))
digest = shared['digest']
def snapshot():
    result = shared['snapshot']()
    for file in sorted(HERE.rglob('*')):
        if file.is_symlink():
            raise ValueError('No linked diagnostic inputs')
        if file.is_file():
            result['inputs'][file.relative_to(ROOT).as_posix()] = {'bytes': file.stat().st_size, 'sha256': digest(file)}
    return result
def main():
    if sys.argv[1:] not in (['--build'], ['--build-private-details']):
        raise ValueError('Use exactly --build or --build-private-details; no runtime or release actions')
    private_details = sys.argv[1:] == ['--build-private-details']
    build_mode = 'private-details' if private_details else 'history'
    source_class = 'PrivateBinderFirstPositiveInstrumentedTest' if private_details else 'Release27RoomScaleInstrumentedTest'
    if (ROOT / 'keystore.properties').exists() or any(key.startswith('DOMIDEX_UPLOAD_') for key in os.environ):
        raise ValueError('No release credentials allowed')
    source = ROOT / ('app/src/androidTest/java/com/dominik/cardvault/' + source_class + '.kt')
    if not source.is_file() or len(list((HERE / 'androidTest').rglob('*.kt'))) != 1:
        raise ValueError('One complete ordinary test source plus exactly one diagnostic runner required')
    run_id = uuid4().hex
    directory = OUTPUT / run_id
    directory.mkdir(parents=True, exist_ok=False)
    before = snapshot()
    package = 'com.dominik.cardvault.historyproof.r' + run_id
    injection = directory / 'injected-source'
    injection.mkdir()
    copied = injection / source.name
    shutil.copyfile(source, copied)
    source_hash = digest(copied)
    if source_hash != before['inputs'][source.relative_to(ROOT).as_posix()]['sha256']:
        raise ValueError('Copied full history test is not byte-identical')
    record = {'runId': run_id, 'package': package, 'startedAt': datetime.now(timezone.utc).isoformat(),
              'buildMode': build_mode, 'injectedSourceClass': source_class,
              'injectedSourceSha256': source_hash, **before}
    (directory / 'before.json').write_text(json.dumps(record, indent=2), encoding='utf-8')
    env = {key: value for key, value in os.environ.items() if key.upper() in {
        'SYSTEMROOT', 'WINDIR', 'SYSTEMDRIVE', 'TEMP', 'TMP', 'USERPROFILE', 'LOCALAPPDATA', 'APPDATA',
        'PROCESSOR_ARCHITECTURE', 'NUMBER_OF_PROCESSORS'}}
    env['JAVA_HOME'] = str(JAVA)
    env['PATH'] = str(JAVA / 'bin') + os.pathsep + str(Path(env.get('SystemRoot', 'C:/Windows')) / 'System32')
    command = [str(ROOT / 'gradlew.bat'), '--offline', '--no-daemon', '--no-configuration-cache', '--no-build-cache',
               '--console=plain', '--init-script', str(HERE / 'proof.init.gradle'), '-PhistoryProofRun=' + run_id,
               '-PdomidexAppCheck=false', ':app:assembleDebug', ':app:assembleDebugAndroidTest']
    print('History diagnostic build: ' + str(directory), flush=True)
    code = -1
    process = None
    failure_type = None
    cleanup_error = None
    timed_out = False
    with (directory / 'build.log').open('x', encoding='utf-8') as log:
        try:
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=log,
                                       stderr=subprocess.STDOUT, shell=False, creationflags=subprocess.CREATE_NO_WINDOW)
            code = process.wait(timeout=900)
        except BaseException as error:
            failure_type = type(error).__name__
            timed_out = isinstance(error, subprocess.TimeoutExpired)
        finally:
            if process is not None and process.poll() is None:
                # Only the still-live exact process we launched and its descendant tree.
                # Never enumerate or kill unrelated Java/Gradle/Android Studio processes.
                taskkill = Path(env.get('SystemRoot', 'C:/Windows')) / 'System32/taskkill.exe'
                try:
                    stopped = subprocess.run([str(taskkill), '/PID', str(process.pid), '/T', '/F'],
                        env=env, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                        timeout=30, shell=False, creationflags=subprocess.CREATE_NO_WINDOW)
                    process.wait(timeout=30)
                    if stopped.returncode != 0:
                        cleanup_error = 'OwnedProcessTreeStopFailed'
                except BaseException as error:
                    cleanup_error = type(error).__name__
    try:
        after = snapshot()
        preserved = before == after and digest(copied) == source_hash
    except Exception as error:
        after = {'inventoryError': type(error).__name__}
        preserved = False
    classes = directory / 'app-build/intermediates/built_in_kotlinc/debugAndroidTest/compileDebugAndroidTestKotlin/classes'
    class_names = sorted(file.relative_to(classes).as_posix() for file in classes.rglob('*.class'))
    prefixes = ('com/dominik/cardvault/HistoryProofRunner', 'com/dominik/cardvault/' + source_class)
    proof_classes = bool(class_names) and all(any(name == prefix + '.class' or name.startswith(prefix + '$')
        for prefix in prefixes) for name in class_names) and all(prefix + '.class' in class_names for prefix in prefixes)
    artifacts = {}
    for relative in ('outputs/apk/debug/app-debug.apk', 'outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
        file = directory / 'app-build' / relative
        if file.is_file():
            artifacts[relative] = {'bytes': file.stat().st_size, 'sha256': digest(file)}
    outcome = {'runId': run_id, 'package': package, 'finishedAt': datetime.now(timezone.utc).isoformat(),
               'exitCode': code, 'inputsAndOldOutputsUnchanged': preserved,
               'buildFailureType': failure_type, 'timedOut': timed_out, 'ownedProcessCleanupError': cleanup_error,
               'proofClassesVerified': proof_classes, 'compiledClasses': class_names,
               'buildMode': build_mode, 'injectedSourceClass': source_class,
               'injectedSourceSha256': source_hash, 'artifacts': artifacts}
    (directory / 'after.json').write_text(json.dumps(after, indent=2), encoding='utf-8')
    (directory / 'build-result.json').write_text(json.dumps(outcome, indent=2), encoding='utf-8')
    print(json.dumps(outcome), flush=True)
    if code or failure_type or cleanup_error or not preserved or not proof_classes or len(artifacts) != 2:
        raise SystemExit(1)
if __name__ == '__main__':
    main()
