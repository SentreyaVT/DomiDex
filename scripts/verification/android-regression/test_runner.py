"""Pure/mocked safety tests. No ADB, emulator, JVM, network or APK installation."""
import sys
sys.dont_write_bytecode = True
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))

import contextlib
import copy
import io
import json
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import Mock, patch

import run
from validate import (cleanup_allowed, selection_from_text, validate_debug_apk,
                      validate_idle_instrumentation, validate_instrumentation,
                      validate_test_manifest)

SELECTION = json.loads((Path(__file__).parent / "selection.json").read_text(encoding="utf-8"))
IDS = ["com.dominik.cardvault.ExampleTest#first", "com.dominik.cardvault.ExampleTest#second"]


def result(ids=IDS):
    lines = []
    for identity in ids:
        cls, name = identity.split("#")
        for code in (1, 0):
            lines.extend(("INSTRUMENTATION_STATUS: class=" + cls,
                          "INSTRUMENTATION_STATUS: test=" + name,
                          "INSTRUMENTATION_STATUS: numtests=" + str(len(ids)),
                          "INSTRUMENTATION_STATUS_CODE: " + str(code)))
    lines.extend((f"OK ({len(ids)} tests)", "INSTRUMENTATION_CODE: -1"))
    return "\n".join(lines)


class SelectionTests(unittest.TestCase):
    def test_reviewed_371_identities_and_compact_selection(self):
        data = selection_from_text(json.dumps(SELECTION))
        self.assertEqual(371, len(data["expected_ids"]))
        self.assertEqual(61, len(data["selectors"]))
        self.assertNotIn("com.dominik.cardvault.ScannerFastInputInstrumentedTest", data["selectors"])
        self.assertNotIn("com.dominik.cardvault.ui.PrivateBinderVisualSelectorsInstrumentedTest", data["selectors"])
        self.assertLess(len(",".join(data["selectors"])), 6000)

    def test_exact_matrix_validator_accepts_every_reviewed_identity_once(self):
        self.assertEqual(371, validate_instrumentation(result(SELECTION["expected_ids"]), SELECTION["expected_ids"])["passed"])

    def test_selection_changes_require_review(self):
        for mutation in (lambda d: d.update(arbitrary="value"),
                         lambda d: d.update(expected_count=370),
                         lambda d: d["expected_ids"].__setitem__(1, d["expected_ids"][0]),
                         lambda d: d["expected_ids"].__setitem__(0, "x; arbitrary-command"),
                         lambda d: d["selectors"].append("com.foreign.Test"),
                         lambda d: d.update(source_log="other.log"),
                         lambda d: d.update(source_log_sha256=None)):
            data = copy.deepcopy(SELECTION)
            mutation(data)
            with self.subTest(data=data.get("expected_count")), self.assertRaises(ValueError):
                selection_from_text(json.dumps(data))

    def test_non_object_selection_rejected(self):
        with self.assertRaises(ValueError):
            selection_from_text("[]")


class ResultTests(unittest.TestCase):
    def test_pass_crlf_and_exact_ids(self):
        self.assertEqual({"passed": 2, "failed": 0, "skipped": 0, "identities": IDS},
                         validate_instrumentation(result().replace("\n", "\r\n"), IDS))

    def test_error_ignore_assumption_never_count_as_success(self):
        for code in (-1, -2, -3, -4, 2):
            with self.subTest(code=code), self.assertRaises(ValueError):
                validate_instrumentation(result().replace("STATUS_CODE: 0", f"STATUS_CODE: {code}", 1), IDS)

    def test_missing_case_rejected_even_with_forged_ok_count(self):
        with self.assertRaises(ValueError):
            validate_instrumentation(result(IDS[:1]).replace("1 tests", "2 tests"), IDS)

    def test_duplicate_case_rejected(self):
        with self.assertRaises(ValueError):
            validate_instrumentation(result([IDS[0], IDS[0]]), IDS)

    def test_unknown_case_rejected(self):
        with self.assertRaises(ValueError):
            validate_instrumentation(result().replace("test=second", "test=unexpected"), IDS)

    def test_pass_without_start_rejected(self):
        lines = result().splitlines()
        with self.assertRaises(ValueError):
            validate_instrumentation("\n".join(lines[4:]), IDS)

    def test_termination_is_not_optional_or_repeatable(self):
        for text in (result().replace("INSTRUMENTATION_CODE: -1", ""),
                     result() + "\nINSTRUMENTATION_CODE: -1",
                     result() + "\nINSTRUMENTATION_STATUS: class=unfinished",
                     result() + "\nFAILURES!!!"):
            with self.subTest(text=text[-70:]), self.assertRaises(ValueError):
                validate_instrumentation(text, IDS)

    def test_forged_total_and_duplicate_status_field_rejected(self):
        for text in (result().replace("numtests=2", "numtests=3", 1),
                     result().replace("OK (2 tests)", "OK (1 tests)"),
                     result().replace("test=first", "test=first\nINSTRUMENTATION_STATUS: test=first", 1)):
            with self.assertRaises(ValueError):
                validate_instrumentation(text, IDS)


class ApkTests(unittest.TestCase):
    def setUp(self):
        self.badging = "package: name='com.dominik.cardvault' versionCode='27'\napplication-debuggable"
        self.signature = "Signer #1 certificate DN: C=US, O=Android, CN=Android Debug\nSigner #1 certificate SHA-256 digest: " + "a" * 64

    def test_debug_identity_and_certificate(self):
        self.assertEqual("a" * 64, validate_debug_apk(self.badging, self.signature, run.APP))

    def test_other_package_release_or_multi_signer_rejected(self):
        for badging, signature in ((self.badging, self.signature.replace("Android Debug", "Upload")),
                                    (self.badging.replace("application-debuggable", ""), self.signature),
                                    (self.badging.replace(run.APP, "com.other.app"), self.signature),
                                    (self.badging, self.signature + "\n" + self.signature)):
            with self.assertRaises(ValueError):
                validate_debug_apk(badging, signature, run.APP)

    def test_exact_instrumentation_element_not_other_attribute(self):
        tree = ('E: manifest (line=1)\n  E: instrumentation (line=5)\n'
                '    A: android:name(0x01010003)="androidx.test.runner.AndroidJUnitRunner" (Raw: "same")\n'
                '    A: android:targetPackage(0x01010021)="com.dominik.cardvault"\n'
                '  E: application (line=9)\n')
        validate_test_manifest(tree)
        for invalid in (tree.replace("targetPackage", "label"), tree.replace(run.APP, "com.other.app"), tree + tree,
                        tree.replace("E: instrumentation", "E: application")):
            with self.assertRaises(ValueError):
                validate_test_manifest(invalid)


class RunnerSafetyTests(unittest.TestCase):
    def runner(self):
        obj = run.Runner.__new__(run.Runner)
        obj.args = SimpleNamespace(check=False)
        obj.adb = Path("C:/reviewed-sdk/platform-tools/adb.exe")
        obj.apks = [Path("app.apk"), Path("test.apk")]
        obj.selection = SELECTION
        obj.env = {}
        obj.started = False
        obj.run_token = "a" * 32
        return obj

    def test_flags_no_implicit_run_arbitrary_serial_or_abbreviation(self):
        for arguments in ([], ["--run", "--check"], ["--run", "--serial", "physical"], ["--ru"], ["--run", "--task", "bundleRelease"]):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                run.options(arguments)
        self.assertTrue(run.options(["--check"]).check)

    def test_environment_drops_inherited_endpoints_and_startup_credentials(self):
        unsafe = {key: "must-not-leak" for key in ("ADB_SERVER_SOCKET", "ADB_SERVER_PORT", "ANDROID_ADB_SERVER_PORT", "ANDROID_SERIAL",
                    "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "PYTHONPATH", "PYTHONSTARTUP", "PYTHONHOME", "HTTP_PROXY",
                    "GOOGLE_APPLICATION_CREDENTIALS", "DOMIDEX_UPLOAD_STORE_PASSWORD", "PATH", "HOME", "APPDATA")}
        env = run.clean_environment({**unsafe, "SystemRoot": r"C:\Windows", "TEMP": r"C:\Temp"})
        self.assertFalse(set(unsafe).difference({"PATH"}).intersection(env))
        self.assertNotIn("must-not-leak", env.values())
        self.assertEqual(r"C:\Windows", env["SystemRoot"])

    def test_fixed_command_and_standard_apks_only(self):
        command = self.runner().instrumentation_command()
        self.assertEqual(["-s", "emulator-5554", "shell", "am", "instrument", "-w", "-r", "-e", "class"], command[1:10])
        self.assertEqual(run.INSTRUMENTATION, command[-1])
        self.assertNotIn("testFile", command)
        self.assertTrue(all("/outputs/apk/" in value and "intermediates" not in value for value in run.APK_PATHS))

    def test_check_does_not_install_start_stop_or_create_output(self):
        runner = self.runner()
        runner.args.check = True
        runner.preflight = Mock(return_value={"offline": True})
        runner.device = Mock(side_effect=AssertionError("No mutating device call"))
        with patch.object(run, "file_hash", return_value="hash"), patch.object(run, "source_hashes", return_value={}), \
                patch.object(Path, "mkdir", side_effect=AssertionError("No output directory")), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, runner.run())
        runner.device.assert_not_called()

    def test_preflight_failure_does_not_cleanup_foreign_process(self):
        runner = self.runner()
        runner.preflight = Mock(side_effect=ValueError("busy"))
        runner.failure_cleanup = Mock(side_effect=AssertionError("Forbidden cleanup"))
        with patch.object(run, "file_hash", return_value="hash"), patch.object(run, "source_hashes", return_value={}), self.assertRaises(ValueError):
            runner.run()
        runner.failure_cleanup.assert_not_called()

    def test_idle_refuses_existing_instrumentation_and_unknown_dump(self):
        idle = "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n  All known processes:\n"
        validate_idle_instrumentation(idle)
        for text in ("", "Bad activity command: instrumentation", idle + "ActiveInstrumentation{abc foreign/Runner}",
                     idle + "instrumentation: unknown format", idle.replace("All known processes:", "unknown")):
            with self.assertRaises(ValueError):
                validate_idle_instrumentation(text)

    def test_cleanup_only_own_started_run_and_same_avd(self):
        empty = "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n  All known processes:\n"
        token = "a" * 32
        own = empty + "ActiveInstrumentation{abc " + run.INSTRUMENTATION + "}\n mArguments=Bundle[{domidexRegressionRunId=" + token + "}]"
        foreign = empty + "ActiveInstrumentation{abc foreign/Runner}"
        self.assertFalse(cleanup_allowed(False, True, own, token))
        self.assertFalse(cleanup_allowed(True, False, own, token))
        self.assertFalse(cleanup_allowed(True, True, foreign, token))
        self.assertFalse(cleanup_allowed(True, True, "unknown", token))
        self.assertTrue(cleanup_allowed(True, True, own, token))
        self.assertFalse(cleanup_allowed(True, True, empty, token))
        self.assertFalse(cleanup_allowed(True, True, own.replace(token, "b" * 32), token))
        self.assertFalse(cleanup_allowed(True, True, own.split("\n mArguments")[0], token))
        runner = self.runner()
        runner.identity = Mock()
        runner.device = Mock(return_value=own)
        self.assertEqual("not-started-no-stop", runner.failure_cleanup())
        runner.device.assert_not_called()
        runner.started = True
        self.assertEqual("own-run-packages-stopped", runner.failure_cleanup())
        self.assertEqual([unittest.mock.call("shell", "dumpsys", "activity", "processes"),
                          unittest.mock.call("shell", "am", "force-stop", run.APP),
                          unittest.mock.call("shell", "am", "force-stop", run.TEST)], runner.device.call_args_list)

    def test_foreign_run_appearing_during_failure_never_force_stopped(self):
        runner = self.runner()
        runner.started = True
        runner.identity = Mock()
        runner.device = Mock(return_value="ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\nActiveInstrumentation{x foreign/Runner}")
        with self.assertRaises(ValueError):
            runner.failure_cleanup()
        self.assertEqual(1, runner.device.call_count)

    def test_timeout_kills_and_waits_only_exact_owned_adb_child(self):
        runner = self.runner()
        process = Mock()
        process.poll.return_value = None
        with tempfile.TemporaryDirectory() as directory, patch.object(run.subprocess, "Popen", return_value=process) as spawn, \
                patch.object(run.subprocess, "CREATE_NO_WINDOW", 0, create=True), patch.object(run.time, "monotonic", side_effect=[0, run.TIMEOUT + 1]):
            with self.assertRaises(TimeoutError):
                runner.instrument(Path(directory) / "instrumentation.log")
        process.kill.assert_called_once_with()
        process.wait.assert_called_once_with(timeout=10)
        self.assertTrue(runner.started)
        self.assertIs(False, spawn.call_args.kwargs["shell"])


if __name__ == "__main__":
    unittest.main()
