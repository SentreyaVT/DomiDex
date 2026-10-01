"""Pure, fail-closed contracts for the fixed offline Android regression."""
import json
import re
from collections import Counter

COUNT = 371
PARTIAL_CLASSES = {"com.dominik.cardvault.ScannerFastInputInstrumentedTest": 3,
                   "com.dominik.cardvault.ui.PrivateBinderVisualSelectorsInstrumentedTest": 9}
ID = re.compile(r"com\.dominik\.cardvault\.[A-Za-z][A-Za-z0-9_.]*#[A-Za-z][A-Za-z0-9_]*\Z")


def selection_from_text(text):
    data = json.loads(text)
    if not isinstance(data, dict) or set(data) != {"schema", "source_log", "source_log_sha256", "expected_count", "selectors", "expected_ids"}:
        raise ValueError("Unexpected selection fields")
    ids = data["expected_ids"]
    selectors = data["selectors"]
    if data["schema"] != 1 or data["expected_count"] != COUNT:
        raise ValueError("Selection version/count requires review")
    if not isinstance(ids, list) or len(ids) != COUNT or any(not isinstance(v, str) or not ID.fullmatch(v) for v in ids):
        raise ValueError("Expected exactly 371 explicit method identities")
    if len(set(ids)) != COUNT or not isinstance(data["source_log_sha256"], str) or not re.fullmatch(r"[a-f0-9]{64}", data["source_log_sha256"]):
        raise ValueError("Duplicate identity or invalid provenance hash")
    if data["source_log"] != "artifacts/release27-engineering-20261001/artwork-http-final-regression371-instrumentation.log":
        raise ValueError("Unexpected historical provenance; never read as a runtime dependency")
    classes = list(dict.fromkeys(value.split("#")[0] for value in ids))
    expected_selectors = [entry for cls in classes for entry in
                          ([v for v in ids if v.startswith(cls + "#")] if cls in PARTIAL_CLASSES else [cls])]
    if any(sum(v.startswith(cls + "#") for v in ids) != count for cls, count in PARTIAL_CLASSES.items()):
        raise ValueError("Partial class selection must retain exactly its reviewed methods")
    if len(classes) != 51 or len(expected_selectors) != 61 or selectors != expected_selectors:
        raise ValueError("Only 49 complete classes and 9 + 3 reviewed single methods are allowed")
    if len(",".join(selectors)) > 6000:
        raise ValueError("Selector argument exceeds the reviewed command-line budget")
    return data


def validate_instrumentation(text, expected):
    if len(expected) != len(set(expected)) or not expected:
        raise ValueError("Ambiguous expected identities")
    if any(token in text for token in ("INSTRUMENTATION_FAILED", "FAILURES!!!", "Process crashed")):
        raise ValueError("Instrumentation failed")
    wanted = set(expected)
    started, passed = Counter(), Counter()
    fields = {}
    codes = []
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if not separator:
                raise ValueError("Malformed status field")
            if key in {"class", "test", "numtests"}:
                if key in fields:
                    raise ValueError("Duplicate status field")
                fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            match = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
            if not match or match.group(1) not in {"0", "1"}:
                raise ValueError("Failure, assumption skip, ignore or malformed result")
            identity = fields.get("class", "") + "#" + fields.get("test", "")
            if codes or identity not in wanted or fields.get("numtests") != str(len(expected)):
                raise ValueError("Unexpected identity or declared test count")
            if match.group(1) == "1":
                started[identity] += 1
                if started[identity] != 1 or passed[identity]:
                    raise ValueError("Duplicate or late test start")
            else:
                passed[identity] += 1
                if started[identity] != 1 or passed[identity] != 1:
                    raise ValueError("Missing start or duplicate test result")
            fields = {}
        elif line.startswith("INSTRUMENTATION_CODE:"):
            codes.append(line)
    if fields or codes != ["INSTRUMENTATION_CODE: -1"]:
        raise ValueError("Incomplete or ambiguous instrumentation termination")
    if set(passed) != wanted or set(started) != wanted or sum(passed.values()) != len(expected):
        raise ValueError("Incomplete expected matrix")
    totals = re.findall(r"(?m)^OK \((\d+) tests\)\r?$", text)
    if totals != [str(len(expected))]:
        raise ValueError("Missing or ambiguous final test count")
    return {"passed": len(expected), "skipped": 0, "failed": 0, "identities": list(expected)}


def validate_debug_apk(badging, signature, package):
    """AAPT2/apksigner output only; never opens a private signing key."""
    package_match = re.search(r"(?m)^package: name='([^']+)'", badging)
    if not package_match or package_match.group(1) != package:
        raise ValueError("Unexpected APK package")
    if not re.search(r"(?m)^application-debuggable\s*$", badging):
        raise ValueError("Only debuggable APKs may be installed")
    signers = re.findall(r"(?m)^Signer #\d+ certificate DN: (.+)$", signature)
    digests = re.findall(r"(?m)^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$", signature)
    if len(signers) != 1 or len(digests) != 1 or set(signers[0].strip().split(", ")) != {"CN=Android Debug", "O=Android", "C=US"}:
        raise ValueError("A single standard Android Debug certificate is required")
    return digests[0].lower()


def validate_test_manifest(tree):
    """Inspect AAPT2's decoded APK manifest, not an unrelated build-side XML."""
    blocks = []
    current = None
    indent = 0
    for line in tree.splitlines():
        element = re.match(r"^(\s*)E: (\S+)", line)
        if element:
            depth = len(element.group(1))
            if current is not None and depth <= indent:
                blocks.append(current)
                current = None
            if element.group(2) == "instrumentation":
                current, indent = [], depth
        elif current is not None:
            current.append(line)
    if current is not None:
        blocks.append(current)
    if len(blocks) != 1:
        raise ValueError("Exactly one test instrumentation declaration is required")
    block = "\n".join(blocks[0])
    for attribute, expected in (("name", "androidx.test.runner.AndroidJUnitRunner"),
                                ("targetPackage", "com.dominik.cardvault")):
        values = re.findall(r'android:' + attribute + r'(?:\(0x[0-9a-fA-F]+\))?="([^"]+)"', block)
        if values != [expected]:
            raise ValueError("Unexpected instrumentation name/target in APK")


def validate_idle_instrumentation(text):
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if (not lines or lines[0] != "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)"
            or "All known processes:" not in lines or re.search(r"instrumentation", text, re.IGNORECASE)):
        raise ValueError("Unknown or already active instrumentation; nothing will be stopped")


def cleanup_allowed(started, identity_valid, activity_dump, run_token):
    if not started or not identity_valid or not activity_dump.startswith("ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)"):
        return False
    active = re.findall(r"ActiveInstrumentation\{[^\n]*", activity_dump)
    # Empty/same-component dumps do not establish ownership. Only one currently
    # active invocation with our exact non-user-supplied token permits cleanup.
    tokens = re.findall(r"domidexRegressionRunId=([a-f0-9]{32})(?=[,}\]\s])", activity_dump)
    return (len(active) == 1 and "com.dominik.cardvault.test/androidx.test.runner.AndroidJUnitRunner" in active[0]
            and isinstance(run_token, str) and re.fullmatch(r"[a-f0-9]{32}", run_token) is not None
            and tokens == [run_token])
