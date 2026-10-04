"""Root-only explicit isolated debug build; never installs, starts devices or signs release."""
import sys
sys.dont_write_bytecode = True
import json
import os
from pathlib import Path
import shutil
from datetime import datetime, timezone
from uuid import uuid4
import contracts as c
from process import execute, environment

def save(path, value):
    path.write_text(json.dumps(value, indent=2), encoding='utf-8')

def main():
    c.require(sys.argv[1:] == ['--build'], 'Exactly --build required')
    c.require(not (c.ROOT / 'keystore.properties').exists() and
              not any(key.startswith('DOMIDEX_UPLOAD_') for key in os.environ), 'No release signing credentials')
    c.require(Path(__file__).resolve() == c.HERE / 'build.py', 'Wrong worktree script')
    c.check_source_selection()
    run = uuid4().hex
    directory = c.OUTPUT / run
    c.no_links(directory)
    directory.mkdir(parents=True, exist_ok=False)
    package = c.identity(directory)[1]
    before = c.snapshot()
    injection = directory / 'injected-source'; injection.mkdir()
    assets = directory / 'injected-assets'; assets.mkdir()
    injected = {}
    for relative in c.SOURCES:
        copied = injection / Path(relative).name
        shutil.copyfile(c.ROOT / relative, copied)
        injected[relative] = c.digest(copied)
        c.require(injected[relative] == before['worktree'][relative]['sha256'], 'Byte-identical test copy required')
    selection = assets / 'maintenance-selection.txt'
    selection.write_bytes(('\n'.join(c.TESTS) + '\n').encode('ascii'))
    record = {'schema': 1, 'runId': run, 'package': package, 'expectedTests': c.TESTS,
              'selectionSha256': c.digest(selection), 'injectedSources': injected,
              'startedAt': datetime.now(timezone.utc).isoformat(), 'snapshot': before}
    save(directory / 'before.json', record)
    java = Path('C:/Program Files/Android/Android Studio/jbr')
    command = [c.ROOT / 'gradlew.bat', '--offline', '--no-daemon', '--no-configuration-cache', '--no-build-cache',
               '--project-cache-dir', directory / 'project-cache', '--console=plain',
               '--init-script', c.HERE / 'proof.init.gradle', '-PmaintenanceProofRun=' + run,
               '-PdomidexAppCheck=false', ':app:assembleDebug', ':app:assembleDebugAndroidTest']
    result = {'schema': 1, 'runId': run, 'package': package, 'status': 'FAIL', 'expectedTests': c.TESTS,
              'injectedSources': injected, 'selectionSha256': c.digest(selection)}
    try:
        _, result['command'] = execute(command, c.ROOT, environment(java), c.BUILD_SECONDS, c.LOG_BYTES,
                                       log=directory / 'build.log', build_tree=True)
        c.require(result['command']['exitCode'] == 0 and result['command']['failure'] is None, 'Isolated debug build failed')
        classes = directory / 'app-build/intermediates/built_in_kotlinc/debugAndroidTest/compileDebugAndroidTestKotlin/classes'
        names = sorted(file.relative_to(classes).as_posix() for file in classes.rglob('*.class'))
        prefixes = [value.replace('.', '/') for value in [c.RUNNER, *c.CLASSES]]
        c.require(names and all(any(name == p + '.class' or name.startswith(p + '$') for p in prefixes) for name in names)
                  and all(p + '.class' in names for p in prefixes), 'Only exact runner and six test classes')
        result['compiledClasses'] = names
        result['artifacts'] = {relative: {'bytes': (directory / 'app-build' / relative).stat().st_size,
                                        'sha256': c.digest(directory / 'app-build' / relative)} for relative in c.APK_PATHS}
        c.require(all(c.digest(injection / Path(p).name) == h for p, h in injected.items()) and
                  c.digest(selection) == record['selectionSha256'], 'Injected inputs changed')
        result['status'] = 'DEBUG_PAIR_BUILT_NOT_DEVICE_PROOF'
    except BaseException as error:
        result['failure'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        try:
            after = c.snapshot(); save(directory / 'after.json', after)
            result['sourcesAndProtectedOutputsUnchanged'] = before == after
            c.require(before == after, 'Sources/protected standard outputs changed')
        except BaseException as error:
            result['status'] = 'FAIL'; result['finalGuardFailure'] = str(error)
            raise
        finally:
            result['finishedAt'] = datetime.now(timezone.utc).isoformat()
            save(directory / 'build-result.json', result)
            print(str(directory / 'build-result.json'), flush=True)

if __name__ == '__main__':
    main()
