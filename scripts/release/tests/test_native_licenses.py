import hashlib
import json
import re
import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import native_licenses as licenses


class NativeLicenseTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.coordinate = "example.native:engine:1.2.3"
        self.aar = self.root / "engine.aar"
        self.output = self.root / "archive"

    def aar_with(self, entries):
        with zipfile.ZipFile(self.aar, "w") as archive:
            for name, data in entries:
                archive.writestr(name, data)
        return {"coordinate": self.coordinate, "aar_path": str(self.aar)}

    def registry(self, document=b"Original vendor notice\n", unresolved=None):
        return {
            "schema_version": 1,
            "providers": {self.coordinate: {
                "external_evidence": ["vendor/NOTICE.txt"],
                "unresolved_checks": unresolved or [],
                "expected_aar_licenses": ["LICENSE.txt"],
            }},
            "external_evidence": [{
                "cache_path": "vendor/NOTICE.txt", "seed_path": "old/NOTICE.txt",
                "url": "https://example.invalid/pinned/NOTICE.txt", "kind": "license",
                "sha256": hashlib.sha256(document).hexdigest(),
            }],
        }

    def test_only_original_license_entries_copied_not_secrets(self):
        dependency = self.aar_with([
            ("LICENSE.txt", b"License"),
            ("third_party_licenses.json", b"{}"),
            ("third_party_licenses.txt", b"Third-party text"),
            ("NOTICE", b"Notice"),
            ("tokens.json", b"private-token"),
            ("upload.p12", b"not-a-real-key"),
            ("classes.jar", b"code"),
        ])
        report = licenses.archive_licenses(self.output, [dependency])
        self.assertEqual(len(report["files"]), 4)
        self.assertFalse(report["complete"])
        self.assertTrue(all("private-token" not in p.read_text() for p in (self.output / "licenses").rglob("*") if p.is_file()))
        self.assertTrue(any("exact dependency version" in item for item in report["warnings"]))

    def test_valid_offline_evidence_records_hashes_and_unresolved_checks(self):
        dep = self.aar_with([("LICENSE.txt", b"License")])
        cache = self.root / "evidence"
        (cache / "vendor").mkdir(parents=True)
        (cache / "vendor/NOTICE.txt").write_bytes(b"Original vendor notice\n")
        with patch.object(licenses, "load_registry", return_value=self.registry(unresolved=["Exact bundled library notice missing"])), patch.object(licenses.urllib.request, "urlopen", side_effect=AssertionError("No network permitted")):
            report = licenses.archive_licenses(self.output, [dep], cache)
        self.assertEqual(len(report["files"]), 2)
        self.assertFalse(report["complete"])
        self.assertIn("Exact bundled library notice missing", report["dependencies"][0]["warnings"])
        for entry in report["files"]:
            self.assertEqual(hashlib.sha256((self.output / entry["path"]).read_bytes()).hexdigest(), entry["sha256"])

    def test_registry_licenses_never_reused_for_other_version(self):
        dep = self.aar_with([("LICENSE.txt", b"License")])
        dep["coordinate"] = "example.native:engine:1.2.4"
        with patch.object(licenses, "load_registry", return_value=self.registry()):
            report = licenses.archive_licenses(self.output, [dep], self.root)
        self.assertFalse(report["dependencies"][0]["registry_known"])
        self.assertEqual(len(report["files"]), 1)

    def test_corrupt_pinned_evidence_is_rejected(self):
        dep = self.aar_with([("LICENSE.txt", b"License")])
        cache = self.root / "evidence"
        (cache / "vendor").mkdir(parents=True)
        (cache / "vendor/NOTICE.txt").write_bytes(b"wrong version")
        with patch.object(licenses, "load_registry", return_value=self.registry()):
            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                licenses.archive_licenses(self.output, [dep], cache)

    def test_expected_aar_checksum_is_checked(self):
        dep = self.aar_with([("LICENSE.txt", b"License")])
        dep["aar_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "AAR hash changed"):
            licenses.archive_licenses(self.output, [dep])

    def test_unsafe_zip_paths_rejected(self):
        for name in ["../LICENSE.txt", "/LICENSE.txt", "C:/LICENSE.txt", "dir\\LICENSE.txt"]:
            with self.subTest(name=name):
                # Backslash members are not interpreted as safe license basenames.
                if "\\" in name:
                    with self.assertRaises(ValueError):
                        licenses._relative(name)
                    continue
                dep = self.aar_with([(name, b"License")])
                with self.assertRaisesRegex(ValueError, "Unsafe"):
                    licenses.archive_licenses(self.output, [dep])

    def test_duplicate_zip_license_rejected(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            dep = self.aar_with([("LICENSE.txt", b"same"), ("LICENSE.txt", b"same")])
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            licenses.archive_licenses(self.output, [dep])

    def test_symbolic_link_zip_license_rejected(self):
        info = zipfile.ZipInfo("LICENSE.txt")
        info.create_system = 3
        info.external_attr = 0o120777 << 16
        dep = self.aar_with([(info, b"../../secret")])
        with self.assertRaisesRegex(ValueError, "symbolic-link"):
            licenses.archive_licenses(self.output, [dep])

    def test_private_key_in_license_file_rejected(self):
        dep = self.aar_with([("LICENSE.txt", b"-----BEGIN RSA PRIVATE KEY-----\nsecret")])
        with self.assertRaisesRegex(ValueError, "Private-key"):
            licenses.archive_licenses(self.output, [dep])

    def test_original_pom_must_match_coordinate(self):
        dep = self.aar_with([("LICENSE.txt", b"License")])
        pom = self.root / "engine.pom"
        pom.write_text("<project><groupId>example.native</groupId><artifactId>engine</artifactId><version>9.9</version></project>")
        dep["pom_path"] = str(pom)
        with self.assertRaisesRegex(ValueError, "POM does not match"):
            licenses.archive_licenses(self.output, [dep])

    def test_valid_namespaced_pom_archived_byte_exact(self):
        dep = self.aar_with([("LICENSE.txt", b"License")])
        pom = self.root / "engine.pom"
        pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>example.native</groupId><artifactId>engine</artifactId><version>1.2.3</version></project>')
        dep["pom_path"] = str(pom)
        report = licenses.archive_licenses(self.output, [dep])
        entry = next(row for row in report["files"] if row["path"].endswith("original.pom"))
        self.assertEqual((self.output / entry["path"]).read_bytes(), pom.read_bytes())

    def test_pom_entities_are_not_accepted(self):
        self.assertFalse(licenses._pom_matches(b'<!DOCTYPE project [<!ENTITY x "sensitive">]><project/>', self.coordinate))

    def test_no_overwrite_and_same_evidence_is_idempotent(self):
        licenses._put(self.output, "LICENSE.txt", b"original")
        licenses._put(self.output, "LICENSE.txt", b"original")
        with self.assertRaisesRegex(ValueError, "overwrite"):
            licenses._put(self.output, "LICENSE.txt", b"changed")
        self.assertEqual((self.output / "LICENSE.txt").read_bytes(), b"original")

    def test_prepare_offline_import_and_missing_are_explicit(self):
        cache, seed = self.root / "cache", self.root / "seed"
        (seed / "old").mkdir(parents=True)
        (seed / "old/NOTICE.txt").write_bytes(b"Original vendor notice\n")
        with patch.object(licenses, "load_registry", return_value=self.registry()), patch.object(licenses.urllib.request, "urlopen", side_effect=AssertionError("No network permitted")):
            missing = licenses.prepare_license_evidence(cache)
            self.assertEqual(missing["missing"], ["vendor/NOTICE.txt"])
            result = licenses.prepare_license_evidence(cache, seed_root=seed)
            self.assertEqual(result["available"], ["vendor/NOTICE.txt"])
            self.assertEqual(result["downloaded"], [])

    def test_prepare_checksum_mismatch_never_overwrites_cache(self):
        cache = self.root / "cache"
        (cache / "vendor").mkdir(parents=True)
        (cache / "vendor/NOTICE.txt").write_bytes(b"wrong")
        with patch.object(licenses, "load_registry", return_value=self.registry()):
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                licenses.prepare_license_evidence(cache)
        self.assertEqual((cache / "vendor/NOTICE.txt").read_bytes(), b"wrong")

    def test_current_registry_exact_versions_and_unresolved_components(self):
        registry = licenses.load_registry()
        self.assertEqual(len(registry["providers"]), 5)
        self.assertEqual(sum(len(p["libraries"]) for p in registry["providers"].values()), 7)
        self.assertIn("KleidiCV", json.dumps(registry))
        self.assertIn("libyuv", json.dumps(registry))
        self.assertTrue(all(re.fullmatch(r"[0-9a-f]{64}", row["sha256"]) for row in registry["external_evidence"]))


if __name__ == "__main__":
    unittest.main()
