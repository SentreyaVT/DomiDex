"""Synthetic parser/receipt tests only: no device, process, network, build or fixture."""
import sys
sys.dont_write_bytecode = True
import unittest
from unittest.mock import patch
import contracts as c

RUN, INVOCATION = 'a' * 32, 'b' * 32


def log(ids):
    parts = []
    for identity in ids:
        name, method = identity.split('#')
        for code in (1, 0):
            parts += [f'INSTRUMENTATION_STATUS: class={name}', f'INSTRUMENTATION_STATUS: test={method}',
                      f'INSTRUMENTATION_STATUS: numtests={len(ids)}', f'INSTRUMENTATION_STATUS_CODE: {code}']
    parts += [f'OK ({len(ids)} {"test" if len(ids) == 1 else "tests"})', 'INSTRUMENTATION_CODE: -1']
    return '\n'.join(parts) + '\n'


def report(phase):
    used = 13 * c.GIB
    value = dict(schema=1, buildRun=RUN, invocation=INVOCATION, phase=phase, status='PASS', api=36,
                 scope='SYNTHETIC_OWNED_ROOM_PHASE_NOT_RELEASE_GATE', closed=True, nonPriceTablesUnchanged=True,
                 collectionHistoryOnly=True, retainedRows=52198,
                 generatedRows=70000000, seedUsedBytes=used, target13GiBReached=True,
                 integrity='ok', foreignKeyErrors=0, retentionComplete=True, retainedRowsMatch=True,
                 retainedHashMatch=True, restartMode='FRESH_PASS_RESCAN_NOT_SERIALIZED_CURSOR',
                 beforeReclaimMainBytes=used + 4096, reclaimed=True,
                 database=dict(mainBytes=used, walBytes=0, pageSize=4096, pageCount=used // 4096,
                               freePages=0, usedBytes=used, dataAvailableBytes=30 * c.GIB))
    if phase == 'pilot':
        value.update(generatedRows=500000, target13GiBReached=False, seedUsedBytes=100000000)
    if phase == 'prune':
        value.update(batches=32, deletedRowsThisPhase=98, retentionComplete=False)
    return value


class ContractsTest(unittest.TestCase):
    def reject_log(self, text, phase='contracts'):
        with self.assertRaises(ValueError):
            c.validate_log(text, phase)

    def test_contract_matrix_exact_and_crlf(self):
        self.assertEqual(len(set(c.CONTRACT_TESTS)), len(c.CONTRACT_TESTS))
        for newline in ('\n', '\r\n'):
            result = c.validate_log(log(c.CONTRACT_TESTS).replace('\n', newline), 'contracts')
            self.assertEqual(len(c.CONTRACT_TESTS), result['passed'])
            self.assertEqual(0, result['skipped'])

    def test_missing_duplicate_wrong_identity_and_wrong_count(self):
        for ids in (c.CONTRACT_TESTS[:-1], c.CONTRACT_TESTS + c.CONTRACT_TESTS[:1],
                    c.CONTRACT_TESTS[:-1] + ['com.dominik.cardvault.Wrong#case']):
            self.reject_log(log(ids))
        self.reject_log(log(c.CONTRACT_TESTS).replace('numtests=', 'numtests=0'))

    def test_failure_skip_crash_and_incomplete(self):
        source = log(c.CONTRACT_TESTS)
        for change in ('INSTRUMENTATION_STATUS_CODE: -2', 'INSTRUMENTATION_STATUS_CODE: -3',
                       'INSTRUMENTATION_STATUS_CODE: -4'):
            self.reject_log(source.replace('INSTRUMENTATION_STATUS_CODE: 0', change, 1))
        for suffix in ('Process crashed', 'INSTRUMENTATION_FAILED', 'java.lang.IllegalStateException: hidden'):
            self.reject_log(source + suffix)
        self.reject_log(source.replace('INSTRUMENTATION_CODE: -1', ''))
        self.reject_log(source + 'INSTRUMENTATION_CODE: -1\n')

    def test_all_six_singletons_and_no_plural_laundering(self):
        for phase, (method, _) in c.PHASES.items():
            if phase == 'contracts':
                continue
            source = log([c.CLASS + '#' + method])
            self.assertEqual(1, c.validate_log(source, phase)['passed'])
            self.reject_log(source.replace('OK (1 test)', 'OK (1 tests)'), phase)
            self.reject_log(source.replace(method, 'wrongMethod'), phase)

    def test_phase_receipts_not_release_pass(self):
        for phase in c.PHASES:
            if phase != 'contracts':
                result = c.validate_report(report(phase), RUN, INVOCATION, phase)
                self.assertEqual('PHASE_PASS_ONLY', result['status'])
                self.assertTrue(result['notReleaseGate'])

    def test_contracts_cannot_manufacture_scale_report(self):
        with self.assertRaises(ValueError):
            c.validate_report(report('contracts'), RUN, INVOCATION, 'contracts')

    def test_bad_identity_status_scope_close_and_preservation(self):
        for key, value in [('buildRun', 'c' * 32), ('invocation', 'c' * 32), ('status', 'PARTIAL'),
                           ('scope', 'RELEASE_PASS'), ('api', 37), ('closed', False),
                           ('nonPriceTablesUnchanged', False), ('collectionHistoryOnly', False), ('failure', 'hidden')]:
            changed = report('verify'); changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                c.validate_report(changed, RUN, INVOCATION, 'verify')

    def test_real_live_pages_not_size_or_boolean(self):
        for change in (dict(usedBytes=1), dict(mainBytes=1), dict(pageCount=True), dict(freePages=1),
                       dict(pageSize=1), dict(walBytes=-1)):
            changed = report('seed'); changed['database'].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                c.validate_report(changed, RUN, INVOCATION, 'seed')

    def test_pilot_seed_and_prune_boundaries(self):
        for phase, change in [('pilot', dict(target13GiBReached=True)), ('pilot', dict(generatedRows=499999)),
                              ('seed', dict(seedUsedBytes=13 * c.GIB - 1)), ('seed', dict(generatedRows=70000001)),
                              ('prune', dict(deletedRowsThisPhase=0)), ('prune', dict(retentionComplete=True))]:
            changed = report(phase); changed.update(change)
            with self.subTest(phase=phase, change=change), self.assertRaises(ValueError):
                c.validate_report(changed, RUN, INVOCATION, phase)

    def test_resume_and_reclaim_require_complete_evidence(self):
        for phase, key, value in [('resume', 'restartMode', 'TOKEN'), ('resume', 'retainedHashMatch', False),
                                  ('resume', 'retainedRowsMatch', False), ('resume', 'integrity', 'error'),
                                  ('resume', 'retainedRows', 18350002),
                                  ('reclaim', 'beforeReclaimMainBytes', 13 * c.GIB), ('reclaim', 'reclaimed', False)]:
            changed = report(phase); changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                c.validate_report(changed, RUN, INVOCATION, phase)
        changed = report('reclaim'); changed['database']['walBytes'] = 1
        with self.assertRaises(ValueError):
            c.validate_report(changed, RUN, INVOCATION, 'reclaim')

    def test_manifest_plain_application_and_exact_runner(self):
        app = 'E: manifest\n  E: application\n    A: android:name="android.app.Application"\n'
        test = app + f'  E: instrumentation\n    A: android:name="{c.RUNNER}"\n    A: android:targetPackage="isolated"\n'
        c.validate_manifest(app, 'isolated'); c.validate_manifest(test, 'isolated', test=True)
        for suffix in ('E: activity', 'E: service', 'E: provider', 'E: receiver', 'E: activity-alias',
                       'android.permission.INTERNET', 'android.permission.CAMERA', 'android:sharedUserId'):
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                c.validate_manifest(app + suffix, 'isolated')
        for invalid in (test.replace('isolated', 'com.dominik.cardvault'), test.replace(c.RUNNER, 'Unknown'),
                        test + 'E: instrumentation\n', test.replace('android.app.Application', 'com.dominik.App')):
            with self.assertRaises(ValueError):
                c.validate_manifest(invalid, 'isolated', test=True)

    def test_exact_build_path_and_source_methods_match_both_allowlists(self):
        self.assertEqual(24, len(c.CONTRACT_TESTS))
        self.assertEqual(RUN, c.identity(c.OUTPUT / RUN)[0])
        for path in (c.ROOT / RUN, c.OUTPUT / ('a' * 31), c.OUTPUT / 'not-a-run'):
            with self.assertRaises(ValueError):
                c.identity(path)
        runner = (c.HERE / 'androidTest/com/dominik/cardvault/StorageProofRunner.kt').read_text(encoding='utf-8')
        for name, methods in c.CONTRACT_CLASSES.items():
            source = c.ROOT / 'app/src/androidTest/java' / (name.replace('.', '/') + '.kt')
            import re
            actual = re.findall(r'@Test\s+fun\s+(\w+)\s*\(', source.read_text(encoding='utf-8'))
            self.assertEqual(set(methods), set(actual))
            self.assertEqual(len(methods), len(actual))
            for method in methods:
                self.assertEqual(1, runner.count('"' + method + '"'))

    def test_contract_failure_is_not_suppressed_by_report_finally(self):
        import run as adapter
        runner = object.__new__(adapter.Runner)
        runner.action = 'contracts'; runner.packages = ['isolated', 'isolated.test']
        runner.owner = dict(installed=runner.packages, passedPhases={})
        runner.run = RUN; runner.app = 'isolated'; runner.invocation = INVOCATION
        runner.adb = 'never-executed'; runner.out = c.OUTPUT / RUN
        runner.receipt = {}; runner.owned = lambda: None; runner.save = lambda: None
        def fail(*args, **kwargs):
            raise ValueError('Synthetic command failure')
        runner.command = fail
        runner.finish_contracts = lambda: self.fail('Failure became contract PASS')
        with self.assertRaisesRegex(ValueError, 'Synthetic command failure'):
            runner.phase()

    def test_install_progress_keeps_exact_terminal_success(self):
        c.validate_install_output('Success')
        c.validate_install_output('Performing Push Install\r\napp.apk: 1 file pushed.\r\nSuccess')
        for invalid in ('', 'NotSuccess', 'Success\nSuccess', 'Success\nunfinished',
                        'Failure [INSTALL_FAILED]\nSuccess', 'error: transport\nSuccess'):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                c.validate_install_output(invalid)

    def test_idle_requires_exact_pid_absence_after_global_check(self):
        import run as adapter
        for code, output in ((1, b''), (0, b''), (0, b'123\n'), (1, b'error\n')):
            runner = object.__new__(adapter.Runner); order = []
            runner.packages = ['isolated', 'isolated.test']; runner.adb = 'never-executed'
            runner.receipt = {'commands': []}
            runner.no_active_instrumentation = lambda: order.append('global')
            def command(args, **kwargs):
                order.append(args[-1]); runner.receipt['commands'].append({'exitCode': code}); return output
            runner.command = command
            if (code, output) == (1, b''):
                runner.idle(); self.assertEqual(['global', *runner.packages], order)
            else:
                with self.assertRaises(ValueError):
                    runner.idle()
                self.assertEqual(['global', 'isolated'], order)

    def test_terminal_cleanup_order_never_ignores_active_instrumentation(self):
        import run as adapter
        for active in (False, True):
            runner = object.__new__(adapter.Runner); order = []
            runner.out = c.OUTPUT / RUN; runner.invocation = INVOCATION
            runner.owner_file = runner.out / 'never-written.json'; runner.owner = {'passedPhases': {}}
            runner.receipt = {}
            def no_active():
                order.append('global-before')
                if active:
                    raise ValueError('Synthetic active instrumentation')
            runner.no_active_instrumentation = no_active
            runner.stop_owned = lambda: order.append('owned-stop')
            runner.idle = lambda: order.append('global-and-pid-after')
            with patch.object(adapter.c, 'digest', return_value='a' * 64), patch.object(adapter, 'write_json'):
                if active:
                    with self.assertRaises(ValueError):
                        runner.finish_contracts()
                    self.assertEqual(['global-before'], order)
                    self.assertEqual({}, runner.owner['passedPhases'])
                else:
                    runner.finish_contracts()
                    self.assertEqual(['global-before', 'owned-stop', 'global-and-pid-after'], order)
                    self.assertEqual('CONTRACT_TESTS_PASS_NOT_STORAGE_GATE', runner.receipt['validation']['status'])


if __name__ == '__main__':
    unittest.main()
