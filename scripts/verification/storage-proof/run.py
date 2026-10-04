"""Explicit fixed-owned AVD phases; no AVD creation/boot, network changes, phone or ordinary app writes."""
import sys
sys.dont_write_bytecode = True
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import threading
import time
from uuid import uuid4
import contracts as c

SDK = Path('C:/Users/Dominik/AppData/Local/Android/Sdk')
JAVA = Path('C:/Program Files/Android/Android Studio/jbr/bin/java.exe')
PROFILE = c.OUTPUT / 'reviewed-profile.json'

def read_json(path):
    c.require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 16 * 1024 * 1024, 'Exact bounded JSON required')
    return json.loads(path.read_text(encoding='utf-8'))

def write_json(path, value):
    path.write_text(json.dumps(value, indent=2), encoding='utf-8')

def daemon():
    with socket.create_connection(('127.0.0.1', 5037), timeout=2) as connection:
        connection.sendall(b'000chost:version')
        result = b''
        while len(result) < 12:
            part = connection.recv(12 - len(result))
            c.require(bool(part), 'Incomplete existing ADB daemon reply')
            result += part
        c.require(result == b'OKAY00040029', 'Existing protocol41 daemon required; never start/replace one')

class Runner:
    def __init__(self, directory, action):
        self.run, self.app = c.identity(directory)
        self.directory, self.action = Path(directory).resolve(), action
        self.invocation = uuid4().hex
        self.out = self.directory / ('runtime-' + action + '-' + self.invocation)
        self.out.mkdir(exist_ok=False)
        self.before = read_json(self.directory / 'before.json')
        self.build = read_json(self.directory / 'build-result.json')
        self.profile = read_json(PROFILE)
        self.profile_hash = c.digest(PROFILE)
        self.owner_file = self.directory / 'device-owner.json'
        self.owner = read_json(self.owner_file) if self.owner_file.exists() else None
        self.packages = [self.app, self.app + '.test']
        self.apks = [self.directory / 'app-build' / relative for relative in c.APK_PATHS]
        self.env = {key: value for key, value in os.environ.items() if key.upper() in {'SYSTEMROOT', 'WINDIR', 'SYSTEMDRIVE', 'TEMP', 'TMP'}}
        self.env['PATH'] = 'C:/Windows/System32'
        self.adb = SDK / 'platform-tools/adb.exe'
        self.contract = c.load_pinned('scripts/verification/android-regression/validate.py')
        self.receipt = {'runId': self.run, 'invocation': self.invocation, 'action': action,
                        'serial': c.SERIAL, 'avd': c.AVD, 'profileSha256': self.profile_hash,
                        'startedAt': datetime.now(timezone.utc).isoformat(), 'status': 'RUNNING', 'commands': []}
        self.instrumentation_started = False

    def save(self):
        write_json(self.out / 'manifest.json', self.receipt)

    def command(self, args, timeout=20, limit=4 * 1024 * 1024, accepted=(0,), raw_log=None):
        process = subprocess.Popen([str(v) for v in args], cwd=c.ROOT, env=self.env, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, shell=False, creationflags=subprocess.CREATE_NO_WINDOW)
        chunks, errors, overflow = [], [], threading.Event()
        def collect():
            total = 0
            try:
                with (raw_log.open('xb') if raw_log else open(os.devnull, 'wb')) as log:
                    while block := process.stdout.read(65536):
                        total += len(block)
                        if total > limit:
                            overflow.set(); break
                        chunks.append(block); log.write(block); log.flush()
            except BaseException as error:
                errors.append(type(error).__name__)
        reader = threading.Thread(target=collect, daemon=True)
        reader.start()
        start = time.monotonic()
        try:
            while process.poll() is None:
                c.require(not overflow.is_set() and not errors, 'Bounded output failed')
                c.require(time.monotonic() - start < timeout, 'Explicit phase deadline exceeded')
                time.sleep(.2)
        finally:
            if process.poll() is None:
                process.kill()  # Only the exact local adb/java child we created, never a daemon/tree scan.
            process.wait(timeout=10); reader.join(timeout=10)
            self.receipt['commands'].append({'executable': str(args[0]), 'arguments': [str(v) for v in args[1:]],
                'exitCode': process.returncode, 'timeoutSeconds': timeout, 'elapsedSeconds': time.monotonic() - start,
                'outputBytes': sum(map(len, chunks)), 'overflow': overflow.is_set(), 'readerErrors': errors})
            self.save()
        c.require(not reader.is_alive() and not errors and not overflow.is_set(), 'Incomplete bounded command')
        c.require(process.returncode in accepted, 'Local command failed')
        return b''.join(chunks)

    def device(self, *args, **kwargs):
        return self.command([self.adb, '-s', c.SERIAL, *args], **kwargs).decode('utf-8', errors='strict').strip()

    def installed(self, package):
        value = self.device('shell', '-T', 'pm', 'path', package, accepted=(0, 1))
        if not value:
            return None
        c.require(re.fullmatch(r'package:/data/app/[^\r\n]+/base\.apk', value) is not None, 'Exact single installed base APK required')
        output = self.device('shell', '-T', 'toybox', 'sha256sum', value[8:])
        c.require(re.fullmatch(r'[a-f0-9]{64}\s+' + re.escape(value[8:]), output) is not None, 'Installed hash receipt malformed')
        return output[:64]

    def owned(self):
        daemon()
        c.require(self.device('shell', '-T', 'getprop', 'ro.boot.qemu.avd_name') == c.AVD
                  and self.device('shell', '-T', 'getprop', 'ro.kernel.qemu') == '1'
                  and self.device('shell', '-T', 'cat', '/proc/sys/kernel/random/boot_id') == self.profile['bootId'],
                  'Owned target identity changed')
        c.require(self.owner is not None and self.owner['runId'] == self.run and self.owner['profileSha256'] == self.profile_hash,
                  'No matching exclusive package ownership receipt')
        for package in self.owner['installed']:
            index = self.packages.index(package)
            c.require(self.installed(package) == self.build['artifacts'][c.APK_PATHS[index]]['sha256'], 'Owned package changed')

    def no_active_instrumentation(self):
        self.contract.validate_idle_instrumentation(self.device('shell', '-T', 'dumpsys', 'activity', 'processes'))

    def idle(self):
        self.no_active_instrumentation()
        for package in self.packages:
            output = self.command([self.adb, '-s', c.SERIAL, 'shell', '-T', 'pidof', package], accepted=(0, 1))
            c.require(output == b'' and self.receipt['commands'][-1]['exitCode'] == 1,
                      'Exact own package process absence required')

    def preflight(self):
        daemon()
        c.require(self.profile['schema'] == 1 and self.profile['serial'] == c.SERIAL and self.profile['avd'] == c.AVD, 'Wrong fixed profile')
        for file, key in ((Path('C:/Users/Dominik/.android/avd') / (c.AVD + '.ini'), 'avdIniSha256'),
                          (Path('C:/Users/Dominik/.android/avd') / (c.AVD + '.avd/config.ini'), 'avdConfigSha256'),
                          (SDK / 'system-images/android-36/google_apis_playstore/x86_64/package.xml', 'imagePackageSha256')):
            c.require(not file.is_symlink() and c.digest(file) == self.profile[key], 'Pinned AVD/image changed')
        config = (Path('C:/Users/Dominik/.android/avd') / (c.AVD + '.avd/config.ini')).read_text(encoding='utf-8')
        c.require(re.findall(r'(?m)^disk.dataPartition.size=(.+)$', config) == ['48G'], 'Fresh 48G profile required')
        c.require(self.device('get-state') == 'device', 'Owned emulator unavailable')
        for prop, expected in {'ro.kernel.qemu': '1', 'ro.build.version.sdk': '36', 'ro.product.cpu.abi': 'x86_64',
                               'ro.boot.qemu.avd_name': c.AVD, 'sys.boot_completed': '1',
                               'ro.build.fingerprint': self.profile['fingerprint'], 'ro.product.model': self.profile['model']}.items():
            c.require(self.device('shell', '-T', 'getprop', prop) == expected, 'Fixed owned AVD identity mismatch')
        c.require(self.device('shell', '-T', 'cat', '/proc/sys/kernel/random/boot_id') == self.profile['bootId'], 'New boot requires explicit reviewed profile')
        c.require('shell_v2' in self.device('features').splitlines(), 'Reliable shell exit status required')
        c.require(self.device('shell', '-T', 'settings', 'get', 'global', 'airplane_mode_on') == '1'
                  and self.device('shell', '-T', 'settings', 'get', 'global', 'wifi_on') == '0', 'Already offline AVD required')
        c.require('Active default network: none' in self.device('shell', '-T', 'dumpsys', 'connectivity'), 'No default network')
        c.require(self.device('reverse', '--list') == '', 'No reverse mappings')
        if self.action == 'cleanup':
            self.no_active_instrumentation()  # Explicit own cleanup may encounter its cached process.
        else:
            self.idle()
        c.require(self.installed('com.dominik.cardvault') is None, 'Dedicated empty AVD must not contain ordinary app')
        c.require(self.build['runId'] == self.run and self.build['package'] == self.app and self.build['exitCode'] == 0
                  and self.build['failure'] is None and self.build['cleanupError'] is None
                  and self.build['inputsAndOldOutputsUnchanged'] is True and self.build['proofClassesVerified'] is True, 'Build not verified')
        c.require(c.snapshot() == {key: self.before[key] for key in ('inputs', 'preserved')}, 'Source/protected outputs changed')
        c.require(set(self.build['injectedSources']) == set(c.SOURCES), 'Exact four copied source files required')
        for source in c.SOURCES:
            c.require(c.digest(self.directory / 'injected-source' / Path(source).name)
                      == self.build['injectedSources'][source] == self.before['inputs'][source]['sha256'], 'Copied source changed')
        for index, apk in enumerate(self.apks):
            c.require(c.digest(apk) == self.build['artifacts'][c.APK_PATHS[index]]['sha256'], 'APK changed')
            badging = self.command([SDK / 'build-tools/36.0.0/aapt2.exe', 'dump', 'badging', apk]).decode()
            signature = self.command([JAVA, '-jar', SDK / 'build-tools/36.0.0/lib/apksigner.jar', 'verify', '--verbose', '--print-certs', apk]).decode()
            c.require(self.contract.validate_debug_apk(badging, signature, self.packages[index]) == c.CERT, 'Exact standard debug certificate required')
            tree = self.command([SDK / 'build-tools/36.0.0/aapt2.exe', 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml']).decode()
            c.validate_manifest(tree, self.app, test=index == 1)
        if self.owner:
            self.owned()

    def phase(self):
        self.owned(); c.require(self.owner['installed'] == self.packages, 'Exact installed pair required')
        c.require('installIntent' not in self.owner and not self.owner.get('cleaned'), 'No unfinished installation')
        required = {'pilot': 'contracts', 'seed': 'pilot', 'verify': 'seed', 'prune': 'verify', 'resume': 'prune', 'reclaim': 'resume'}
        if self.action in required:
            prior = required[self.action]
            evidence = self.owner['passedPhases'][prior]
            token = evidence['invocation']
            c.require(re.fullmatch('[a-f0-9]{32}', token) is not None, 'Exact previous invocation required')
            previous = self.directory / ('runtime-' + prior + '-' + token)
            manifest = read_json(previous / 'manifest.json')
            c.require(manifest['status'] == 'PASS_ACTION_ONLY_NOT_RELEASE_GATE'
                      and manifest['inputsAndOldOutputsUnchanged'] is True
                      and manifest['profileSha256'] == self.profile_hash
                      and manifest['runId'] == self.run and manifest['invocation'] == token
                      and manifest['action'] == prior, 'Prior terminal guards not proven')
            c.validate_log((previous / 'instrumentation.log').read_text(encoding='utf-8'), prior)
            if prior == 'contracts':
                c.require(c.digest(previous / 'instrumentation.log') == evidence['logSha256'], 'Prior contract log changed')
            else:
                c.require(c.digest(previous / 'android-report.json') == evidence['reportSha256'], 'Prior report changed')
                c.validate_report(read_json(previous / 'android-report.json'), self.run, token, prior)
        remote = 'files/storage-' + self.invocation + '.json'
        if self.action != 'contracts':
            probe = self.command([self.adb, '-s', c.SERIAL, 'shell', '-T', 'run-as', self.app, 'toybox', 'test', '-e', remote], accepted=(0, 1))
            c.require(probe == b'' and self.receipt['commands'][-1]['exitCode'] == 1, 'Report must be genuinely absent')
        selection = ','.join(c.CONTRACT_TESTS) if self.action == 'contracts' else c.CLASS + '#' + c.PHASES[self.action][0]
        args = ['shell', '-T', 'am', 'instrument', '-w', '-r', '-e', 'class', selection,
                '-e', 'storageProofRun', self.run, '-e', 'storageInvocation', self.invocation,
                '-e', 'storagePhase', self.action, self.app + '.test/' + c.RUNNER]
        try:
            self.instrumentation_started = True
            output = self.command([self.adb, '-s', c.SERIAL, *args], timeout=c.PHASES[self.action][1],
                                  limit=16 * 1024 * 1024, raw_log=self.out / 'instrumentation.log')
            self.receipt['instrumentation'] = c.validate_log(output.decode('utf-8'), self.action)
        finally:
            # Retain an Android failure report before deciding PASS. No shared/unfiltered logcat.
            try:
                if self.action == 'contracts':
                    self.receipt['reportScope'] = 'No scale report for exact contract tests'
                    # Keep only the real instrumentation receipt; never manufacture a 13GiB report.
                else:
                    encoded = self.device('shell', '-T', 'run-as', self.app, 'toybox', 'base64', '-w0', remote, limit=3 * 1024 * 1024)
                    raw = base64.b64decode(encoded, validate=True)
                    c.require(len(raw) <= 2 * 1024 * 1024, 'Bounded report required')
                    receipt_hash = self.device('shell', '-T', 'run-as', self.app, 'toybox', 'sha256sum', remote).split()[0]
                    c.require(hashlib.sha256(raw).hexdigest() == receipt_hash, 'Report bytes changed during retrieval')
                    (self.out / 'android-report.json').write_bytes(raw)
                    self.receipt['reportSha256'] = receipt_hash
            except Exception as error:
                self.receipt['reportReadFailure'] = type(error).__name__ + ': ' + str(error)
            self.save()
        if self.action == 'contracts':
            self.finish_contracts()
            return
        report = read_json(self.out / 'android-report.json')
        self.receipt['validation'] = c.validate_report(report, self.run, self.invocation, self.action)
        self.no_active_instrumentation()
        # New package only: terminal test is proven first, then stop cached process for a real reopen.
        self.stop_owned()
        self.idle()
        self.owner['passedPhases'][self.action] = {'invocation': self.invocation, 'reportSha256': self.receipt['reportSha256']}
        write_json(self.owner_file, self.owner)

    def finish_contracts(self):
        self.no_active_instrumentation(); self.stop_owned(); self.idle()
        self.receipt['validation'] = {'status': 'CONTRACT_TESTS_PASS_NOT_STORAGE_GATE', 'tests': len(c.CONTRACT_TESTS)}
        self.owner['passedPhases']['contracts'] = {'invocation': self.invocation,
            'logSha256': c.digest(self.out / 'instrumentation.log')}
        write_json(self.owner_file, self.owner)

    def stop_owned(self):
        self.owned()
        for package in reversed(self.owner['installed']):
            self.device('shell', '-T', 'am', 'force-stop', package)

    def execute(self):
        self.preflight()
        if self.action == 'install':
            c.require(self.owner is None and all(self.installed(p) is None for p in self.packages), 'Never overwrite preexisting package')
            c.require(self.device('shell', '-T', 'pm', 'list', 'packages', 'com.dominik.cardvault.storageproof') == '', 'No other storage fixture on this AVD')
            self.owner = {'runId': self.run, 'profileSha256': self.profile_hash, 'installed': [], 'passedPhases': {}}
            write_json(self.owner_file, self.owner)
            for index, package in enumerate(self.packages):
                # Persist intent before installing, so even an uncertain outcome remains recoverable.
                self.owner['installIntent'] = package; write_json(self.owner_file, self.owner)
                output = self.device('install', '-t', '--no-streaming', self.apks[index], timeout=120)
                self.receipt.setdefault('installOutput', {})[package] = output; self.save()
                c.validate_install_output(output)
                c.require(self.installed(package) == self.build['artifacts'][c.APK_PATHS[index]]['sha256'], 'Installed bytes mismatch')
                self.owner['installed'].append(package); self.owner.pop('installIntent'); write_json(self.owner_file, self.owner)
        elif self.action == 'cleanup':
            if self.owner and 'installIntent' in self.owner:
                # Recover only our persisted, previously absent exact installation target.
                self.owned()
                pending = self.owner['installIntent']
                c.require(pending in self.packages and pending not in self.owner['installed'], 'Invalid install intent')
                index = self.packages.index(pending)
                installed_hash = self.installed(pending)
                if installed_hash is not None:
                    c.require(installed_hash == self.build['artifacts'][c.APK_PATHS[index]]['sha256'], 'Uncertain install bytes changed')
                    self.owner['installed'].append(pending)
                self.owner.pop('installIntent'); write_json(self.owner_file, self.owner)
            self.owned(); self.no_active_instrumentation(); self.stop_owned(); self.idle()
            for package in list(reversed(self.owner['installed'])):
                c.require(self.device('uninstall', package, timeout=120) == 'Success', 'Owned uninstall failed')
                c.require(self.installed(package) is None, 'Owned package still present')
                self.owner['installed'].remove(package); write_json(self.owner_file, self.owner)
            self.owner['cleaned'] = True; write_json(self.owner_file, self.owner)
        elif self.action in c.PHASES:
            self.phase()
        else:
            c.require(self.action == 'check', 'Unknown action')
        self.preflight()
        self.receipt['status'] = 'PASS_ACTION_ONLY_NOT_RELEASE_GATE'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--build-dir', required=True)
    parser.add_argument('--action', '--phase', dest='action', required=True, choices=['check', 'install', *c.PHASES, 'cleanup'])
    args = parser.parse_args()
    runner = Runner(args.build_dir, args.action)
    lock = runner.directory / 'runtime.lock'
    with lock.open('x', encoding='ascii') as stream:
        stream.write(runner.invocation)
    try:
        runner.execute()
    except BaseException as error:
        runner.receipt['status'] = 'FAIL'
        runner.receipt['failure'] = type(error).__name__ + ': ' + str(error)
        if runner.instrumentation_started and runner.owner:
            try: runner.stop_owned()  # Own exact pinned pair only; fixture persists for inspection.
            except Exception as stop_error: runner.receipt['ownedStopFailure'] = str(stop_error)
        raise
    finally:
        try:
            runner.receipt['inputsAndOldOutputsUnchanged'] = c.snapshot() == {key: runner.before[key] for key in ('inputs', 'preserved')}
            c.require(runner.receipt['inputsAndOldOutputsUnchanged'], 'Preservation failure')
            c.require(c.digest(PROFILE) == runner.profile_hash, 'Reviewed profile changed')
        except BaseException as error:
            runner.receipt['status'] = 'FAIL'
            runner.receipt['finalGuardFailure'] = type(error).__name__ + ': ' + str(error)
            raise
        finally:
            runner.receipt['finishedAt'] = datetime.now(timezone.utc).isoformat(); runner.save()
            if lock.read_text(encoding='ascii') == runner.invocation:
                lock.unlink()
        print(str(runner.out / 'manifest.json'), flush=True)

if __name__ == '__main__':
    main()
