"""Explicit isolated debug build only. Never creates an AVD, installs, signs release or runs tests."""
import sys
sys.dont_write_bytecode = True
import json
import os
from pathlib import Path
import shutil
import subprocess
from datetime import datetime, timezone
from uuid import uuid4
import contracts as c

def main():
    c.require(sys.argv[1:] == ['--build'], 'Exactly --build required')
    c.require(not (c.ROOT / 'keystore.properties').exists()
              and not any(key.startswith('DOMIDEX_UPLOAD_') for key in os.environ), 'No release credentials')
    run = uuid4().hex
    directory = c.OUTPUT / run
    directory.mkdir(parents=True, exist_ok=False)
    before = c.snapshot()
    injection = directory / 'injected-source'
    injection.mkdir()
    injected = {}
    for relative in c.SOURCES:
        source = c.ROOT / relative
        copied = injection / source.name
        shutil.copyfile(source, copied)
        source_hash = c.digest(copied)
        c.require(source_hash == before['inputs'][relative]['sha256'], 'Source copy mismatch')
        injected[relative] = source_hash
    package = c.identity(directory)[1]
    record = {'runId': run, 'package': package, 'injectedSources': injected,
              'startedAt': datetime.now(timezone.utc).isoformat(), **before}
    (directory / 'before.json').write_text(json.dumps(record, indent=2), encoding='utf-8')
    java = Path('C:/Program Files/Android/Android Studio/jbr')
    env = {key: value for key, value in os.environ.items() if key.upper() in {
        'SYSTEMROOT', 'WINDIR', 'SYSTEMDRIVE', 'TEMP', 'TMP', 'USERPROFILE', 'LOCALAPPDATA', 'APPDATA'}}
    env['JAVA_HOME'] = str(java)
    env['PATH'] = str(java / 'bin') + os.pathsep + 'C:/Windows/System32'
    command = [str(c.ROOT / 'gradlew.bat'), '--offline', '--no-daemon', '--no-configuration-cache',
               '--no-build-cache', '--console=plain', '--init-script', str(c.HERE / 'proof.init.gradle'),
               '-PstorageProofRun=' + run, '-PdomidexAppCheck=false', ':app:assembleDebug', ':app:assembleDebugAndroidTest']
    code, failure, cleanup = -1, None, None
    with (directory / 'build.log').open('x', encoding='utf-8') as log:
        process = subprocess.Popen(command, cwd=c.ROOT, env=env, stdin=subprocess.DEVNULL, stdout=log,
                                   stderr=subprocess.STDOUT, shell=False, creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            code = process.wait(timeout=1200)
        except BaseException as error:
            failure = type(error).__name__
        finally:
            if process.poll() is None:
                try:
                    stopped = subprocess.run(['C:/Windows/System32/taskkill.exe', '/PID', str(process.pid), '/T', '/F'],
                        env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                        timeout=30, shell=False, creationflags=subprocess.CREATE_NO_WINDOW)
                    process.wait(timeout=30)
                    c.require(stopped.returncode == 0, 'Owned build process stop failed')
                except BaseException as error:
                    cleanup = type(error).__name__
    after = c.snapshot()
    classes = directory / 'app-build/intermediates/built_in_kotlinc/debugAndroidTest/compileDebugAndroidTestKotlin/classes'
    names = sorted(file.relative_to(classes).as_posix() for file in classes.rglob('*.class'))
    prefixes = ('com/dominik/cardvault/StorageProofRunner', 'com/dominik/cardvault/PriceHistoryStorageScaleInstrumentedTest',
                'com/dominik/cardvault/data/PriceHistoryRetentionInstrumentedTest',
                'com/dominik/cardvault/PriceHistoryWindowRepositoryInstrumentedTest',
                'com/dominik/cardvault/data/sync/CardmarketImportRoomInstrumentedTest')
    verified = bool(names) and all(any(name == p + '.class' or name.startswith(p + '$') for p in prefixes) for name in names)
    verified = verified and all(p + '.class' in names for p in prefixes)
    artifacts = {relative: {'sha256': c.digest(file), 'bytes': file.stat().st_size}
                 for relative in c.APK_PATHS if (file := directory / 'app-build' / relative).is_file()}
    result = {'runId': run, 'package': package, 'exitCode': code, 'failure': failure, 'cleanupError': cleanup,
              'inputsAndOldOutputsUnchanged': before == after and all(c.digest(injection / Path(p).name) == h for p, h in injected.items()),
              'injectedSources': injected, 'proofClassesVerified': verified,
              'compiledClasses': names, 'artifacts': artifacts}
    (directory / 'after.json').write_text(json.dumps(after, indent=2), encoding='utf-8')
    (directory / 'build-result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({'directory': str(directory), **result}), flush=True)
    c.require(code == 0 and failure is None and cleanup is None and verified
              and result['inputsAndOldOutputsUnchanged'] and len(artifacts) == 2, 'Isolated build failed')

if __name__ == '__main__':
    main()
