"""Pure synthetic contract tests only. No process, Gradle, ADB, network or device calls."""
import sys
sys.dont_write_bytecode = True
import copy
from pathlib import Path
import unittest
from unittest.mock import patch
import contracts as c

RUN = 'a' * 32
PROFILE_SHA = 'b' * 64
PACKAGE = 'com.dominik.cardvault.maintenanceproof.r' + RUN
PAIR = [PACKAGE, PACKAGE + '.test']
HASHES = {PAIR[0]: 'c' * 64, PAIR[1]: 'd' * 64}

def profile():
    return {'schema': 1, 'serial': c.SERIAL, 'avd': c.AVD, 'model': c.MODEL, 'fingerprint': c.FINGERPRINT,
            'api': 26, 'abi': 'x86_64', 'imageRevision': 16, 'sqliteVersion': '3.18.2',
            'bootId': '11111111-2222-3333-4444-555555555555',
            'avdIniSha256': '1' * 64, 'avdConfigSha256': '2' * 64,
            'imagePackageSha256': '3' * 64, 'imageSourcePropertiesSha256': '4' * 64,
            'ordinaryPackages': {'com.dominik.cardvault': '5' * 64, 'com.dominik.cardvault.test': '6' * 64}}

def log(identities=c.TESTS):
    lines = []
    for identity in identities:
        cls, method = identity.split('#')
        for code in (1, 0):
            lines += ['INSTRUMENTATION_STATUS: class=' + cls, 'INSTRUMENTATION_STATUS: test=' + method,
                      'INSTRUMENTATION_STATUS: numtests=' + str(len(identities)), 'INSTRUMENTATION_STATUS_CODE: ' + str(code)]
    return '\n'.join(lines + ['OK (%d tests)' % len(identities), 'INSTRUMENTATION_CODE: -1', ''])

def manifest(test=False):
    value = 'E: manifest\n  E: application\n    A: android:name="android.app.Application"\n'
    if test:
        value += '  E: instrumentation\n    A: android:name="' + c.RUNNER + '"\n    A: android:targetPackage="' + PACKAGE + '"\n'
    return value

class Contracts(unittest.TestCase):
    def test_exact_44_matrix_and_copied_sources(self):
        self.assertEqual([len(v) for v in c.CLASSES.values()], [15, 3, 6, 15, 2, 3])
        self.assertEqual(len(c.TESTS), 44)
        self.assertEqual(len(set(c.TESTS)), 44)
        c.check_source_selection()
        self.assertIn('com.dominik.cardvault.data.PriceHistoryRetentionInstrumentedTest#emptyAndSignedPrimaryKeysExtremeCutoffsAndInvalidInputsFailSafely', c.TESTS)
        self.assertIn('com.dominik.cardvault.data.PriceHistoryMaintenanceInstrumentedTest#cancellationAtDeleteOrCursorSqlRollsBackAllChanges', c.TESTS)
        self.assertEqual(c.TESTS[38], 'com.dominik.cardvault.data.PriceHistoryMaintenanceInstrumentedTest#regressedVerifiedClockPausesByteExactlyAndFreshTicketResumesOriginalCursor')
        self.assertEqual(c.TESTS[39], 'com.dominik.cardvault.data.PriceHistoryStorageReclaimInstrumentedTest#realRowsDurableResumeAndOfflineVacuumReleaseOwnedFileWithoutChangingRetainedData')
        self.assertEqual(c.TESTS[40], 'com.dominik.cardvault.data.PriceHistoryStorageReclaimInstrumentedTest#platformDefaultRetentionReleasesRealSpaceWithoutChangingRetainedData')
        self.assertEqual(c.TESTS[41:], ['com.dominik.cardvault.data.local.PriceHistoryMaintenanceWorkManagerInstrumentedTest#' + method for method in (
            'failedPrerequisiteReallyPoisonsAlreadyBlockedSuccessor',
            'actualPausedWorkerAllowsBlockedFreshImportSuccessor',
            'actualRetryExhaustedWorkerAllowsBlockedFreshImportSuccessor')])

    def test_exact_complete_status_groups(self):
        result = c.validate_log(log())
        self.assertEqual((result['passed'], result['failed'], result['skipped']), (44, 0, 0))
        self.assertEqual(result['identities'], c.TESTS)

    def test_isolated_sources_are_byte_exact_and_outside_normal_source_sets(self):
        expected = {
            'PriceHistoryStorageReclaimInstrumentedTest.kt': '654a7b5d5dcc3cbce55e086f6819b8d0c66580078121d42f4583b9fb03318460',
            'PriceHistoryMaintenanceWorkManagerInstrumentedTest.kt': 'ff994edcad8a3a39ca6cbc6292db6ae32da3b96fbaaf3e9ea5491f9b5caa4b52',
        }
        self.assertEqual(len(c.SOURCES), 6)
        self.assertEqual(len(set(c.SOURCES)), 6)
        self.assertEqual(len({Path(source).name for source in c.SOURCES}), 6)
        self.assertEqual(set(c.ISOLATED_SOURCES.values()), {source for source in c.SOURCES if '/testSources/' in source})
        for cls, source in c.ISOLATED_SOURCES.items():
            path = c.ROOT / source
            self.assertEqual(c.digest(path), expected[path.name])
            self.assertFalse((c.ROOT / ('app/src/androidTest/java/' + cls.replace('.', '/') + '.kt')).exists())
            self.assertNotIn('/androidTest/', source)

    def test_duplicate_source_or_basename_cannot_pass_selection(self):
        for sources in (c.SOURCES[:-1], c.SOURCES[:-1] + (c.SOURCES[0],),
                        c.SOURCES[:-1] + ('different/' + Path(c.SOURCES[0]).name,)):
            with patch.object(c, 'SOURCES', sources), self.assertRaises(ValueError):
                c.check_source_selection()

    def test_failure_skip_ignore_crash_and_exception_reject(self):
        for code in ('-1', '-2', '-3', '-4'):
            with self.subTest(code=code), self.assertRaises(ValueError):
                c.validate_log(log().replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: ' + code, 1))
        for text in ('Process crashed', 'INSTRUMENTATION_FAILED', 'FAILURES!!!', 'shortMsg=oops',
                     'AssumptionViolatedException', 'java.lang.IllegalStateException: failed'):
            with self.subTest(text=text), self.assertRaises(ValueError): c.validate_log(log() + text)

    def test_missing_duplicate_foreign_or_wrong_numtests_reject(self):
        variants = [log(c.TESTS[:-1]), log(c.TESTS + [c.TESTS[0]]), log().replace(c.TESTS[0].split('#')[1], 'foreign', 2),
                    log().replace('numtests=44', 'numtests=24', 1), log().replace('OK (44 tests)', 'OK (24 tests)'),
                    log().replace('numtests=44', 'numtests=40', 1), log().replace('OK (44 tests)', 'OK (40 tests)'),
                    log().replace('numtests=44', 'numtests=41', 1), log().replace('OK (44 tests)', 'OK (41 tests)'),
                    log().replace('numtests=44', 'numtests=43', 1), log().replace('OK (44 tests)', 'OK (43 tests)'),
                    log(c.TESTS[:41]), log() + 'OK (44 tests)\n', log().replace('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: 0')]
        for value in variants:
            with self.assertRaises(ValueError): c.validate_log(value)

    def test_missing_or_late_start_reject(self):
        groups = log().splitlines()
        with self.assertRaises(ValueError): c.validate_log('\n'.join(groups[4:]))
        with self.assertRaises(ValueError): c.validate_log(log().replace('INSTRUMENTATION_STATUS_CODE: 1', 'INSTRUMENTATION_STATUS_CODE: 0', 1))

    def test_profile_requires_exact_api26_and_fresh_explicit_fields(self):
        self.assertEqual(c.validate_profile(profile()), profile())
        for key, value in [('serial', 'emulator-5562'), ('avd', 'DomiDex_R27_Regression_API36'), ('api', 36),
                           ('abi', 'x86'), ('imageRevision', 15), ('schema', True), ('model', 'guessed'),
                           ('bootId', ''), ('avdIniSha256', 'X' * 64), ('sqliteVersion', '3.46.0')]:
            data = profile(); data[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): c.validate_profile(data)
        for key in c.PROFILE_KEYS:
            data = profile(); data.pop(key)
            with self.assertRaises(ValueError): c.validate_profile(data)
        data = profile(); data['autoDiscover'] = True
        with self.assertRaises(ValueError): c.validate_profile(data)

    def test_ordinary_package_inventory_never_contains_unowned_cleanup_targets(self):
        data = profile(); data['ordinaryPackages']['com.example.foreign'] = '1' * 64
        with self.assertRaises(ValueError): c.validate_profile(data)
        data = profile(); data['ordinaryPackages']['com.dominik.cardvault'] = None
        self.assertEqual(c.validate_profile(data), data)

    def test_exact_worktree_output_uuid_only(self):
        self.assertEqual(c.identity(c.OUTPUT / RUN), (RUN, PACKAGE))
        for value in (c.MAIN / 'artifacts' / RUN, c.OUTPUT / '..' / RUN, c.OUTPUT / 'latest', c.OUTPUT / ('A' * 32)):
            with self.assertRaises(ValueError): c.identity(value)

    def test_manifest_plain_app_no_network_components_or_wrong_runner(self):
        c.validate_manifest(manifest(), PACKAGE)
        c.validate_manifest(manifest(True), PACKAGE, test=True)
        for extra in ('E: provider', 'E: service', 'E: activity', 'E: activity-alias', 'E: receiver',
                      'android:sharedUserId', 'android.permission.INTERNET', 'android.permission.CAMERA'):
            with self.subTest(extra=extra), self.assertRaises(ValueError): c.validate_manifest(manifest() + extra, PACKAGE)
        for value in (manifest(True).replace(c.RUNNER, 'androidx.test.runner.AndroidJUnitRunner'),
                      manifest(True).replace(PACKAGE, 'com.dominik.cardvault'), manifest(True) + ' E: instrumentation\n',
                      manifest(True).replace('android.app.Application', 'com.dominik.cardvault.DomiDexApplication')):
            with self.assertRaises(ValueError): c.validate_manifest(value, PACKAGE, test=True)

    def test_installer_requires_one_terminal_success(self):
        for text in ('Success', 'Performing Push Install\nSuccess'):
            c.validate_install(text)
        for text in ('', 'Failure [x]', 'Success\nSuccess', 'Success\nerror', 'error\nSuccess', 'Success\nextra'):
            with self.assertRaises(ValueError): c.validate_install(text)

    def test_owner_receipt_cannot_authorize_foreign_pair_or_hash(self):
        valid = {'schema': 1, 'runId': RUN, 'profileSha256': PROFILE_SHA, 'installed': dict(HASHES)}
        c.validate_owner(valid, RUN, PROFILE_SHA, PAIR, HASHES)
        for key, value in [('runId', 'f' * 32), ('profileSha256', 'e' * 64), ('installed', {'com.dominik.cardvault': 'c' * 64}),
                           ('installed', {PAIR[0]: 'e' * 64}), ('installIntent', 'com.dominik.cardvault'),
                           ('uninstallIntent', 'com.example.other'), ('cleaned', True)]:
            data = copy.deepcopy(valid); data[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): c.validate_owner(data, RUN, PROFILE_SHA, PAIR, HASHES)

    def test_uncertain_install_and_uninstall_receipts_remain_exact_owned(self):
        pending = {'schema': 1, 'runId': RUN, 'profileSha256': PROFILE_SHA, 'installed': {}, 'installIntent': PAIR[0]}
        c.validate_owner(pending, RUN, PROFILE_SHA, PAIR, HASHES)
        pending['installed'] = dict(HASHES)
        with self.assertRaises(ValueError): c.validate_owner(pending, RUN, PROFILE_SHA, PAIR, HASHES)
        pending.pop('installIntent'); pending['uninstallIntent'] = PAIR[1]
        c.validate_owner(pending, RUN, PROFILE_SHA, PAIR, HASHES)

    def test_runner_import_never_executes_a_process_or_network(self):
        import importlib.util
        with patch('subprocess.Popen', side_effect=AssertionError('no processes')), patch('socket.create_connection', side_effect=AssertionError('no network')):
            spec = importlib.util.spec_from_file_location('maintenance_runner_pure', c.HERE / 'run.py')
            module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        self.assertEqual(module.PROFILE.parent, c.OUTPUT)
        self.assertEqual(c.BUILD_SECONDS, 1200); self.assertEqual(c.RUN_SECONDS, 1200)

    def test_postterminal_stop_requires_exact_success_and_rechecks_every_owned_target(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('maintenance_runner_postterminal', c.HERE / 'run.py')
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        runner = object.__new__(module.Runner)
        runner.packages = PAIR
        calls = []
        runner.local_freeze = lambda: calls.append('freeze')
        runner.owned = lambda: calls.append('owned')
        runner.no_active = lambda: calls.append('no-active')
        runner.idle = lambda: calls.append('idle')
        runner.save = lambda: None
        runner.shell = lambda *args: calls.append(args)
        for result in (None, {'passed': 43, 'skipped': 1, 'failed': 0, 'identities': c.TESTS},
                       {'passed': 44, 'skipped': 0, 'failed': 0, 'identities': c.TESTS[:-1]},
                       {'passed': 43, 'skipped': 0, 'failed': 0, 'identities': c.TESTS},
                       {'passed': 41, 'skipped': 0, 'failed': 0, 'identities': c.TESTS[:41]}):
            runner.receipt = {'instrumentation': result}
            with self.assertRaises(ValueError): runner.finish_owned_after_terminal()
            self.assertEqual(calls, [])
        result = {'passed': 44, 'skipped': 0, 'failed': 0, 'identities': c.TESTS}
        runner.receipt = {'instrumentation': copy.deepcopy(result)}
        runner.finish_owned_after_terminal()
        self.assertEqual(calls, ['freeze', 'owned', 'no-active', ('am', 'force-stop', PAIR[1]),
                                 'freeze', 'owned', 'no-active', ('am', 'force-stop', PAIR[0]), 'idle'])
        self.assertEqual(runner.receipt['instrumentation'], result)
        self.assertEqual(runner.receipt['terminalOwnedProcessStop'], list(reversed(PAIR)))

if __name__ == '__main__':
    unittest.main()
