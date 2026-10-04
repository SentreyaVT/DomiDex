"""Root-only fixed5558 proof. No boot/AVD creation, daemon management or network changes."""
import sys
sys.dont_write_bytecode = True
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import socket
import time
from uuid import uuid4
import xml.etree.ElementTree as ET
import contracts as c
from process import execute, environment

SDK = Path('C:/Users/Dominik/AppData/Local/Android/Sdk')
JAVA = Path('C:/Program Files/Android/Android Studio/jbr/bin/java.exe')
AVD_ROOT = Path('C:/Users/Dominik/.android/avd')
PROFILE = c.OUTPUT / 'reviewed-profile.json'

def save(path, value):
    path.write_text(json.dumps(value, indent=2), encoding='utf-8')

def daemon():
    # Read the existing local server protocol before each ADB client call.
    # Never run start-server/kill-server or select/discover another serial.
    with socket.create_connection(('127.0.0.1', 5037), timeout=2) as connection:
        connection.sendall(b'000chost:version')
        raw = b''
        while len(raw) < 12:
            part = connection.recv(12 - len(raw))
            c.require(bool(part), 'Incomplete existing daemon reply')
            raw += part
        c.require(raw == b'OKAY00040029', 'Existing protocol41 ADB daemon required')

class Runner:
    def __init__(self, directory, action, profile_hash):
        self.run, self.app = c.identity(directory)
        self.directory, self.action = Path(directory).resolve(), action
        c.require(re.fullmatch('[a-f0-9]{64}', profile_hash) and c.digest(PROFILE) == profile_hash,
                  'Explicit fresh Root-reviewed profile SHA required')
        self.profile = c.validate_profile(c.read_json(PROFILE, 8192)); self.profile_hash = profile_hash
        self.before = c.read_json(self.directory / 'before.json')
        self.build = c.read_json(self.directory / 'build-result.json')
        self.packages = [self.app, self.app + '.test']
        self.apks = [self.directory / 'app-build' / relative for relative in c.APK_PATHS]
        self.hashes = {p: self.build['artifacts'][relative]['sha256'] for p, relative in zip(self.packages, c.APK_PATHS)}
        self.owner_file = self.directory / 'device-owner.json'
        self.owner = c.read_json(self.owner_file, 8192) if self.owner_file.exists() else None
        if self.owner is not None:
            c.validate_owner(self.owner, self.run, self.profile_hash, self.packages, self.hashes)
        self.invocation = uuid4().hex
        self.out = self.directory / ('runtime-' + action + '-' + self.invocation); self.out.mkdir(exist_ok=False)
        self.adb = SDK / 'platform-tools/adb.exe'
        self.deadline = time.monotonic() + c.RUN_SECONDS
        self.total_bytes = 0
        self.receipt = {'schema': 1, 'runId': self.run, 'invocation': self.invocation, 'action': action,
                        'profileSha256': self.profile_hash, 'serial': c.SERIAL, 'avd': c.AVD,
                        'startedAt': datetime.now(timezone.utc).isoformat(), 'status': 'RUNNING', 'commands': [],
                        'scope': 'ISOLATED_ROOM_MAINTENANCE_NOT_OS_KILL_POWERLOSS_OR_13GIB_PROOF'}

    def save(self):
        save(self.out / 'manifest.json', self.receipt)

    def command(self, args, timeout=20, limit=4 * 1024 * 1024, accepted=(0,), name=None):
        left = self.deadline - time.monotonic()
        c.require(left > 0 and self.total_bytes + limit <= 64 * 1024 * 1024, 'Action/log budget exhausted')
        log = self.out / (name or 'command-%04d.log' % len(self.receipt['commands']))
        raw, result = execute(args, c.ROOT, environment(), min(timeout, left), limit, log=log)
        self.total_bytes += len(raw)
        result.update({'arguments': [str(v) for v in args], 'log': log.name,
                       'sha256': hashlib.sha256(raw).hexdigest()})
        self.receipt['commands'].append(result); self.save()
        c.require(result['failure'] is None and result['exitCode'] in accepted, 'Bounded local command failed')
        return raw

    def device(self, *args, **kwargs):
        daemon()
        return self.command([self.adb, '-s', c.SERIAL, *args], **kwargs)

    def shell(self, *args, **kwargs):
        return self.device('shell', '-T', *args, **kwargs).decode('utf-8', errors='strict').strip()

    def identity(self):
        c.require(self.device('get-state').decode().strip() == 'device', 'Owned API26 target must already be booted')
        c.require(self.device('features').decode().splitlines() == ['cmd', 'stat_v2', 'shell_v2'], 'Exact API26 shell-v2 features required')
        raw = self.device('emu', 'avd', 'name').decode()
        c.require(raw.replace('\r', '').splitlines() == [c.AVD, 'OK'], 'Exact read-only AVD console identity required')
        expected = {'ro.boot.qemu.avd_name': '', 'ro.kernel.qemu': '1', 'ro.build.version.sdk': '26',
                    'ro.product.cpu.abi': 'x86_64', 'ro.product.model': c.MODEL,
                    'ro.build.fingerprint': c.FINGERPRINT, 'sys.boot_completed': '1'}
        c.require({p: self.shell('getprop', p) for p in expected} == expected, 'Target property mismatch')
        c.require(self.shell('cat', '/proc/sys/kernel/random/boot_id') == self.profile['bootId'], 'New boot needs reviewed profile')

    def offline(self):
        c.require(self.shell('settings', 'get', 'global', 'airplane_mode_on') == '1' and
                  self.shell('settings', 'get', 'global', 'wifi_on') == '0', 'Root must prepare offline target')
        c.require(re.search(r'(?m)^\s*Active default network: none\s*$', self.shell('dumpsys', 'connectivity')),
                  'Active/unknown default network')
        c.require(not self.device('reverse', '--list').strip(), 'Reverse mapping exists; never remove it')

    def no_active(self):
        c.validator().validate_idle_instrumentation(self.shell('dumpsys', 'activity', 'processes'))

    def idle(self):
        self.no_active()
        for package in self.packages:
            raw = self.device('shell', '-T', 'pidof', package, accepted=(0, 1))
            c.require(raw == b'' and self.receipt['commands'][-1]['exitCode'] == 1, 'Own package PID still exists')

    def installed(self, package):
        path = self.shell('pm', 'path', package, accepted=(0, 1))
        if not path:
            return None
        c.require(re.fullmatch(r'package:/data/app/[A-Za-z0-9_./=+~-]+/base\.apk', path), 'One installed APK required')
        value = self.shell('sha256sum', path[8:])
        c.require(re.fullmatch(r'[a-f0-9]{64}\s+' + re.escape(path[8:]), value), 'Exact installed APK hash required')
        return value[:64]

    def ordinary_unchanged(self):
        c.require({p: self.installed(p) for p in self.profile['ordinaryPackages']} == self.profile['ordinaryPackages'],
                  'Ordinary app/test inventory changed; never modify it')

    def owned(self):
        self.identity()
        c.validate_owner(self.owner, self.run, self.profile_hash, self.packages, self.hashes)
        for package, hashed in self.owner['installed'].items():
            observed = self.installed(package)
            uncertain_removed = self.action == 'cleanup' and self.owner.get('uninstallIntent') == package and observed is None
            c.require(observed == hashed or uncertain_removed, 'Owned package bytes changed')

    def local_freeze(self):
        c.require(c.digest(PROFILE) == self.profile_hash and c.snapshot() == self.before['snapshot'], 'Inputs/profile/protected outputs changed')
        c.require(self.build['status'] == 'DEBUG_PAIR_BUILT_NOT_DEVICE_PROOF' and
                  self.build['sourcesAndProtectedOutputsUnchanged'] is True and self.build['runId'] == self.run and
                  self.build['package'] == self.app and self.build['expectedTests'] == self.before['expectedTests'] == c.TESTS,
                  'Unverified build')
        c.require(set(self.build['artifacts']) == set(c.APK_PATHS) and set(self.build['injectedSources']) == set(c.SOURCES), 'Wrong build inventory')
        for source in c.SOURCES:
            c.require(c.digest(self.directory / 'injected-source' / Path(source).name) == self.build['injectedSources'][source]
                      == self.before['snapshot']['worktree'][source]['sha256'], 'Injected source changed')
        c.require(c.digest(self.directory / 'injected-assets/maintenance-selection.txt') == self.build['selectionSha256']
                  == self.before['selectionSha256'], 'Packaged selector input changed')
        for apk, package, relative in zip(self.apks, self.packages, c.APK_PATHS):
            c.no_links(apk)
            c.require(c.digest(apk) == self.hashes[package] and apk.stat().st_size == self.build['artifacts'][relative]['bytes'], 'APK changed')

    def preflight(self):
        self.local_freeze()
        image = SDK / 'system-images/android-26/google_apis/x86_64'
        for path, key in ((AVD_ROOT / (c.AVD + '.ini'), 'avdIniSha256'),
                          (AVD_ROOT / (c.AVD + '.avd/config.ini'), 'avdConfigSha256'),
                          (image / 'package.xml', 'imagePackageSha256'), (image / 'source.properties', 'imageSourcePropertiesSha256')):
            c.no_links(path); c.require(c.digest(path) == self.profile[key], 'AVD/image metadata changed')
        xml = ET.parse(image / 'package.xml').getroot()
        local = [e for e in xml.iter() if e.tag.split('}')[-1] == 'localPackage']
        c.require(len(local) == 1 and local[0].attrib.get('path') == 'system-images;android-26;google_apis;x86_64', 'Wrong image')
        revisions = [e for e in local[0] if e.tag.split('}')[-1] == 'revision']
        c.require(len(revisions) == 1 and [e.text for e in revisions[0] if e.tag.split('}')[-1] == 'major'] == ['16'], 'Wrong image revision')
        self.identity(); self.offline(); self.ordinary_unchanged()
        if self.action == 'cleanup': self.no_active()
        else: self.idle()
        # CLI observation only, not a claim about a connection used by Room tests.
        c.require(self.shell('/system/xbin/sqlite3', '-version') ==
                  '3.18.2 2017-07-21 07:56:09 8201f4e1c566f7223c71c07e6b703d1352801f1b2daa0fd00895a18e1944cb4d', 'SQLite CLI changed')
        for apk, package in zip(self.apks, self.packages):
            badging = self.command([SDK / 'build-tools/36.0.0/aapt2.exe', 'dump', 'badging', apk]).decode()
            signature = self.command([JAVA, '-jar', SDK / 'build-tools/36.0.0/lib/apksigner.jar', 'verify', '--verbose', '--print-certs', apk]).decode()
            c.require(c.validator().validate_debug_apk(badging, signature, package) == c.CERT, 'Exact standard debug cert required')
            c.require(re.findall(r"(?m)^minSdkVersion:'([0-9]+)'\r?$", badging) == ['26'], 'Exact minimum API26 required')
            tree = self.command([SDK / 'build-tools/36.0.0/aapt2.exe', 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml']).decode()
            c.validate_manifest(tree, self.app, test=package.endswith('.test'))
        if self.owner is not None:
            self.owned()

    def install(self):
        c.require(self.owner is None and all(self.installed(p) is None for p in self.packages), 'Never replace preexisting packages')
        c.require(self.shell('pm', 'list', 'packages', 'com.dominik.cardvault.maintenanceproof') == '', 'Another maintenance fixture exists')
        self.owner = {'schema': 1, 'runId': self.run, 'profileSha256': self.profile_hash, 'installed': {}}
        save(self.owner_file, self.owner)
        for package, apk in zip(self.packages, self.apks):
            self.owner['installIntent'] = package; save(self.owner_file, self.owner)
            text = self.device('install', '-t', '--no-streaming', apk, timeout=120).decode().strip()
            c.validate_install(text)
            c.require(self.installed(package) == self.hashes[package], 'Installed APK mismatch')
            self.owner['installed'][package] = self.hashes[package]
            self.owner.pop('installIntent'); save(self.owner_file, self.owner)

    def run_tests(self):
        self.owned()
        c.require(self.owner['installed'] == self.hashes and 'installIntent' not in self.owner and 'uninstallIntent' not in self.owner
                  and not self.owner.get('cleaned'), 'Incomplete installed pair')
        raw = self.device('shell', '-T', 'am', 'instrument', '-w', '-r', '-e', 'class', ','.join(c.TESTS),
                          '-e', 'maintenanceProofRun', self.run, '-e', 'maintenanceInvocation', self.invocation,
                          self.app + '.test/' + c.RUNNER, timeout=c.RUN_SECONDS, limit=c.LOG_BYTES, name='instrumentation.log')
        self.receipt['instrumentation'] = c.validate_log(raw.decode('utf-8', errors='strict'))
        self.receipt['instrumentationSha256'] = hashlib.sha256(raw).hexdigest()
        self.save()  # Preserve genuine test results before the strict post-idle guard.
        self.finish_owned_after_terminal()

    def finish_owned_after_terminal(self):
        c.require(self.receipt.get('instrumentation') ==
                  {'passed': 44, 'skipped': 0, 'failed': 0, 'identities': c.TESTS},
                  'Complete exact terminal tests required before owned post-test stop')
        self.receipt['terminalOwnedProcessStop'] = []
        for package in reversed(self.packages):
            # Root-authorized postterminal lifecycle cleanup, not an OS kill test.
            # Recheck source/profile/APK ownership and global no-active state at
            # each stop. No assertions, parser status or earlier FAIL is changed.
            self.local_freeze(); self.owned(); self.no_active()
            self.shell('am', 'force-stop', package)
            self.receipt['terminalOwnedProcessStop'].append(package); self.save()
        self.idle()

    def cleanup(self):
        self.owned(); self.no_active()
        if 'uninstallIntent' in self.owner:
            pending = self.owner['uninstallIntent']
            observed = self.installed(pending)
            c.require(observed is None or observed == self.hashes[pending], 'Uncertain uninstall has foreign bytes')
            if observed is None: self.owner['installed'].pop(pending)
            self.owner.pop('uninstallIntent'); save(self.owner_file, self.owner)
        if 'installIntent' in self.owner:
            pending = self.owner['installIntent']; observed = self.installed(pending)
            c.require(observed is None or observed == self.hashes[pending], 'Uncertain install has foreign bytes')
            if observed is not None: self.owner['installed'][pending] = observed
            self.owner.pop('installIntent'); save(self.owner_file, self.owner)
        # Explicit cleanup only, never used to turn a failed run into PASS. Requires
        # no active instrumentation globally and exact unique installed APK bytes.
        for package in reversed(self.packages):
            if package not in self.owner['installed']: continue
            self.owned(); self.no_active()
            self.shell('am', 'force-stop', package)
        self.idle()
        for package in reversed(self.packages):
            if package not in self.owner['installed']: continue
            self.owned()
            self.owner['uninstallIntent'] = package; save(self.owner_file, self.owner)
            c.require(self.device('uninstall', package, timeout=120).decode().strip() == 'Success', 'Owned uninstall failed')
            c.require(self.installed(package) is None, 'Owned package still installed')
            self.owner['installed'].pop(package); self.owner.pop('uninstallIntent'); save(self.owner_file, self.owner)
        self.owner['cleaned'] = True; save(self.owner_file, self.owner)

    def execute(self):
        self.preflight()
        if self.action == 'install': self.install()
        elif self.action == 'run': self.run_tests()
        elif self.action == 'cleanup': self.cleanup()
        else: c.require(self.action == 'check', 'Unknown action')
        self.identity(); self.offline(); self.ordinary_unchanged(); self.idle()
        if self.owner is not None: self.owned()
        self.receipt['status'] = 'PASS_ACTION_ONLY_NOT_RELEASE_GATE'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--build-dir', required=True)
    parser.add_argument('--profile-sha256', required=True)
    parser.add_argument('--action', required=True, choices=['check', 'install', 'run', 'cleanup'])
    args = parser.parse_args()
    runner = Runner(args.build_dir, args.action, args.profile_sha256)
    lock = runner.directory / 'runtime.lock'
    with lock.open('x', encoding='ascii') as stream: stream.write(runner.invocation)
    try:
        runner.execute()
    except BaseException as error:
        runner.receipt['status'] = 'FAIL'; runner.receipt['failure'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        try:
            runner.local_freeze()
            runner.receipt['sourcesAndProtectedOutputsUnchanged'] = True
            # Passive checks also run after a failed JUnit result. A timed-out
            # action cannot exceed its deadline to pretend these checks passed.
            runner.identity(); runner.offline(); runner.ordinary_unchanged(); runner.idle()
            runner.receipt['finalOfflineOrdinaryPackagesAndIdleVerified'] = True
        except BaseException as error:
            runner.receipt['status'] = 'FAIL'; runner.receipt['finalGuardFailure'] = str(error)
            raise
        finally:
            runner.receipt['finishedAt'] = datetime.now(timezone.utc).isoformat(); runner.save()
            if lock.read_text(encoding='ascii') == runner.invocation: lock.unlink()
            print(str(runner.out / 'manifest.json'), flush=True)

if __name__ == '__main__':
    main()
