"""Pure synthetic parser tests. No APK inspection, build, subprocess or device access."""
import sys
sys.dont_write_bytecode = True
import importlib.util
from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("hardware_contract_test_target", HERE / "contracts.py")
c = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)
shared = c.shared_contracts()
PACKAGE = c.package_for("a" * 32)


def log():
    blocks = []
    for identity in c.EXPECTED:
        name, method = identity.split("#")
        for code in (1, 0):
            blocks.append(f"INSTRUMENTATION_STATUS: class={name}\nINSTRUMENTATION_STATUS: test={method}\n"
                          f"INSTRUMENTATION_STATUS: numtests=33\nINSTRUMENTATION_STATUS_CODE: {code}\n")
    return "".join(blocks) + "INSTRUMENTATION_RESULT: stream=\nTime: 1.0\n\nOK (33 tests)\n\nINSTRUMENTATION_CODE: -1\n"


def manifest(test=False):
    own = PACKAGE + ".test" if test else PACKAGE
    text = (f'N: android=http://schemas.android.com/apk/res/android\n  E: manifest (line=1)\n'
            f'    A: package="{own}" (Raw: "{own}")\n'
            '    E: application (line=2)\n'
            '      A: android:name(0x01010003)="android.app.Application"\n'
            '      A: android:allowBackup(0x01010280)=(type 0x12)0x0\n')
    if test:
        return text + ('    E: instrumentation (line=3)\n'
            f'      A: android:name="{c.RUNNER}"\n      A: android:targetPackage="{PACKAGE}"\n')
    text += ('      E: provider (line=3)\n'
             '        A: android:name="com.google.mlkit.common.internal.MlKitInitProvider"\n'
             f'        A: android:authorities="{PACKAGE}.mlkitinitprovider"\n'
             '        A: android:exported=(type 0x12)0x0\n'
             '        A: android:initOrder=(type 0x10)0x63\n'
             '      E: service (line=4)\n'
             '        A: android:name="com.google.mlkit.common.internal.MlKitComponentDiscoveryService"\n'
             '        A: android:exported=(type 0x12)0x0\n'
             '        A: android:directBootAware=(type 0x12)0xffffffff\n')
    for registrar in sorted(c.REGISTRARS):
        text += ('        E: meta-data (line=5)\n'
                 f'          A: android:name="{registrar}"\n'
                 '          A: android:value="com.google.firebase.components.ComponentRegistrar"\n')
    return text


class Contracts(unittest.TestCase):
    def rejects_log(self, value):
        with self.assertRaises(ValueError):
            c.validate_log(value, shared.validate_instrumentation)

    def rejects_manifest(self, value, test=False):
        with self.assertRaises(ValueError):
            c.validate_manifest(value, PACKAGE + ".test" if test else PACKAGE, test=test, target=PACKAGE)

    def test_fixed_matrix(self):
        self.assertEqual([len(methods) for _, methods in c.CLASSES], [4, 1, 4, 12, 1, 7, 4])
        self.assertEqual(len(c.SELECTORS), 7)
        self.assertEqual(len(set(c.EXPECTED)), 33)

    def test_complete_success_lf_crlf(self):
        for value in (log(), log().replace("\n", "\r\n")):
            self.assertEqual(c.validate_log(value, shared.validate_instrumentation)["passed"], 33)

    def test_missing_identity(self):
        self.rejects_log(log().replace(c.EXPECTED[0].split("#")[1], "unknownMethod"))

    def test_wrong_declared_count(self):
        self.rejects_log(log().replace("numtests=33", "numtests=32", 1))

    def test_duplicate_identity(self):
        self.rejects_log(log().replace(c.EXPECTED[1].split("#")[1], c.EXPECTED[0].split("#")[1]))

    def test_skip_and_failure(self):
        for code in ("-1", "-2", "-3", "-4"):
            self.rejects_log(log().replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: " + code, 1))

    def test_missing_start(self):
        self.rejects_log(log().replace("INSTRUMENTATION_STATUS_CODE: 1", "INSTRUMENTATION_STATUS_CODE: 0", 1))

    def test_final_count_and_termination(self):
        for old, new in (("OK (33 tests)", "OK (32 tests)"),
                         ("OK (33 tests)", "OK (33 tests)\nOK (33 tests)"),
                         ("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
                         ("INSTRUMENTATION_CODE: -1", "")):
            self.rejects_log(log().replace(old, new))

    def test_crash_not_masked_by_success(self):
        for failure in ("INSTRUMENTATION_FAILED", "FAILURES!!!", "Process crashed", "shortMsg=Process died",
                        "FATAL EXCEPTION", "INSTRUMENTATION_ABORTED", "Error: runner failed"):
            self.rejects_log(log() + failure)

    def test_manifests_exact_positive(self):
        self.assertTrue(c.validate_manifest(manifest(), PACKAGE))
        self.assertTrue(c.validate_manifest(manifest(True), PACKAGE + ".test", test=True, target=PACKAGE))

    def test_forbidden_permissions_all_tags(self):
        for tag in ("uses-permission", "uses-permission-sdk-23"):
            for permission in ("INTERNET", "CAMERA"):
                suffix = f'    E: {tag}\n      A: android:name="android.permission.{permission}"\n'
                self.rejects_manifest(manifest() + suffix)
                self.rejects_manifest(manifest(True) + suffix, test=True)

    def test_shared_uid_forbidden(self):
        for key in ("sharedUserId", "sharedUserLabel"):
            self.rejects_manifest(manifest().replace("    E: application", f'    A: android:{key}="anything"\n    E: application'))

    def test_product_application_or_components_rejected(self):
        self.rejects_manifest(manifest().replace("android.app.Application", "com.dominik.cardvault.DomiDexApplication"))
        for tag in ("provider", "service", "activity", "activity-alias", "receiver"):
            self.rejects_manifest(manifest() + f'      E: {tag}\n        A: android:name="unreviewed.Component"\n')
            self.rejects_manifest(manifest(True).replace("    E: instrumentation", f'      E: {tag}\n    E: instrumentation'), test=True)

    def test_provider_service_guards(self):
        for old, new in ((".mlkitinitprovider", ".wrong"), ("(type 0x10)0x63", "(type 0x10)0x62"),
                         ("android:exported=(type 0x12)0x0", "android:exported=(type 0x12)0xffffffff"),
                         ("android:directBootAware=(type 0x12)0xffffffff", "android:directBootAware=(type 0x12)0x0"),
                         ("TextRegistrar", "FakeRegistrar")):
            self.rejects_manifest(manifest().replace(old, new))

    def test_test_runner_and_target(self):
        self.rejects_manifest(manifest(True).replace(c.RUNNER, "androidx.test.runner.AndroidJUnitRunner"), test=True)
        self.rejects_manifest(manifest(True).replace('android:targetPackage="' + PACKAGE, 'android:targetPackage="com.dominik.cardvault'), test=True)

    def test_ambiguous_manifest(self):
        self.rejects_manifest(manifest() + manifest())
        self.rejects_manifest(manifest().replace('A: android:initOrder=', 'A: android:exported='))

    def test_package_id_scope(self):
        for value in ("", "a" * 31, "A" * 32, "../" + "a" * 32, "a" * 33, None):
            with self.assertRaises(ValueError):
                c.package_for(value)

    def test_exact_certificate(self):
        badge = f"package: name='{PACKAGE}' versionCode='27'\napplication-debuggable\n"
        signature = ("Signer #1 certificate DN: C=US, O=Android, CN=Android Debug\n"
                     "Signer #1 certificate SHA-256 digest: " + c.CERT_SHA256 + "\n")
        self.assertEqual(c.validate_debug_apk(badge, signature, PACKAGE), c.CERT_SHA256)
        for altered in (signature.replace(c.CERT_SHA256, "0" * 64), signature + signature):
            with self.assertRaises(ValueError):
                c.validate_debug_apk(badge, altered, PACKAGE)


if __name__ == "__main__":
    unittest.main()
