"""Pure contracts for the isolated, fixed Redmi 33-case proof. No device access."""
import hashlib
import importlib.util
from pathlib import Path
import re

CERT_SHA256 = "61e8314b147e44653b8e0d169fe6caf22760c36ee47795cd8471b434166ba854"
RUNNER = "com.dominik.cardvault.HardwareProofRunner"
CLASSES = (
    ("com.dominik.cardvault.PrivateBinderFirstPositiveInstrumentedTest", (
        "privateDetailsRetainCompleteCardsWithoutMaterializingWholeOwnerHistory",
        "sustainedPrivateDetailsTrackPriceLinkCatalogOwnerPlacementAndSelectionChanges",
        "safePortfolioRetainsCompleteModelWithoutWholeHistoryTransport",
        "sustainedPortfolioRetainsCompleteModelsAcrossBoundsLinksOwnersAndInvalidPrices")),
    ("com.dominik.cardvault.OwnedHistoryFlowInvalidationInstrumentedTest", (
        "activeOwnedHistoryTracksPriceChangesTombstonesAndOwnerIsolation",)),
    ("com.dominik.cardvault.BinderDataLoadingPerformanceInstrumentedTest", (
        "summariesDoNotLoad1920HistoricalRowsAndKeepIdenticalValuations",
        "lightweightSummariesRefreshOnClearTrendArrivalAndOwnerSwitch",
        "annualHistoryKeepsEveryValueAndOwnerBoundaryWithSameDateCorrections",
        "portfolioSelectsOnlyTheFourHistoryFieldsItConsumes")),
    ("com.dominik.cardvault.data.sync.PrivateSyncTransactionInstrumentedTest", (
        "validSessionCommitsAndReturnsResult", "alreadyStaleSessionDoesNotEnterCallback",
        "accountChangeDuringWritesRollsPayloadAndCursorBack", "accountRoundTripStillRollsBack",
        "closedSessionDuringWriteRollsBack", "cancellationAfterWritesPreservesPriorPayloadCursorAndOutbox",
        "completedNestedRoomTransactionRollsBackWithStaleOuterSession",
        "completedSdkTaskReturnsOnlyForCurrentLease",
        "sdkTaskCompletionAfterAccountRoundTripCannotApplyResult",
        "cancelledSdkAwaitDoesNotApplyALaterRemoteCompletion",
        "missingCachedOrPendingDependencyDoesNotEnterRoomApply",
        "confirmedDependencyCanApplyUnderCurrentSession")),
    ("com.dominik.cardvault.ui.CameraQualityTest", (
        "detectsCardShapedQuadrilateralAndRejectsBlankFrame",)),
    ("com.dominik.cardvault.ui.CardImageNormalizerInstrumentedTest", (
        "appliesAllEightExifTransformsToPixels", "normalizesPlausibleCardFrameOnDevice",
        "rejectsImageWithoutPlausibleCardFrameOnDevice", "rejectsTexturedFabricWithoutPhysicalCard",
        "holoHighlightDoesNotInvalidateAnOtherwisePlausibleSpecialCard",
        "landscapeCardProducesOnlyAspectCorrectCanonicalCandidates",
        "completesSmallInterruptionsInLowContrastOuterBorder")),
    ("com.dominik.cardvault.ui.ScannerSessionIsolationInstrumentedTest", (
        "cancellationCleanupSubmissionCannotRaceShutdownWhilePreprocessingOwnsBitmap",
        "repeatedAnalyzerCloseRacingEmptyCaptureCleanupFinishesExactlyOnce",
        "cancelDuringLeasedNativeOcrReleasesInputsAndAllowsAnotherIdentity",
        "sameAnalyzerReadsNewIdentityFromSecondSameSizedCapture")),
)
SELECTORS = [name for name, _ in CLASSES]
EXPECTED = [name + "#" + method for name, methods in CLASSES for method in methods]
EXPECTED_TESTS = EXPECTED
REGISTRARS = {
    "com.google.firebase.components:com.google.mlkit.vision.text.internal.TextRegistrar",
    "com.google.firebase.components:com.google.mlkit.vision.common.internal.VisionCommonRegistrar",
    "com.google.firebase.components:com.google.mlkit.common.internal.CommonComponentRegistrar",
}


def shared_contracts():
    path = Path(__file__).resolve().parents[1] / "android-regression/validate.py"
    if hashlib.sha256(path.read_bytes()).hexdigest() != "4cf12d32ddfc56dc00d7f481e66baab33c0ddee5f196316ad3739129aae0cc1a":
        raise ValueError("Reviewed regression contract changed")
    spec = importlib.util.spec_from_file_location("hardware_shared_contracts", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_debug_apk(badging, signature, package):
    certificate = shared_contracts().validate_debug_apk(badging, signature, package)
    if certificate != CERT_SHA256:
        raise ValueError("Exact reviewed Android Debug certificate required")
    return certificate


def package_for(run_id):
    if not isinstance(run_id, str) or not re.fullmatch(r"[a-f0-9]{32}", run_id):
        raise ValueError("Expected a fresh canonical 32-hex build identity")
    return "com.dominik.cardvault.hardwareproof.r" + run_id


def manifest_nodes(tree):
    """Decode only documented aapt2 xmltree element/attribute output; fail closed."""
    roots, stack = [], []
    for line in tree.splitlines():
        element = re.fullmatch(r"(\s*)E: ([\w-]+)(?: .*)?", line)
        if element:
            depth = len(element[1])
            while stack and stack[-1][0] >= depth:
                stack.pop()
            node = {"tag": element[2], "attrs": {}, "children": []}
            (stack[-1][1]["children"] if stack else roots).append(node)
            stack.append((depth, node))
        elif line.lstrip().startswith("A: "):
            attribute = re.fullmatch(r'\s*A: ([\w:-]+)(?:\(0x[\da-fA-F]+\))?=(.*)', line)
            if not stack or not attribute or attribute[1] in stack[-1][1]["attrs"]:
                raise ValueError("Malformed or duplicate manifest attribute")
            raw = attribute[2]
            quoted = re.fullmatch(r'"([^"\r\n]*)"(?: \(Raw: "[^"\r\n]*"\))?', raw)
            typed = re.fullmatch(r"\(type 0x([\da-fA-F]+)\)0x([\da-fA-F]+)", raw)
            value = quoted[1] if quoted else (int(typed[2], 16) if typed else raw)
            stack[-1][1]["attrs"][attribute[1]] = value
    if len(roots) != 1 or roots[0]["tag"] != "manifest":
        raise ValueError("Expected one manifest root")
    return roots[0]


def validate_manifest(tree, package, test=False, target=None):
    root = manifest_nodes(tree)
    if any(key in root["attrs"] for key in ("android:sharedUserId", "android:sharedUserLabel")):
        raise ValueError("Fresh proof must never join a shared UID")
    if root["attrs"].get("package") != package:
        raise ValueError("APK manifest package mismatch")
    def walk(node):
        yield node
        for child in node["children"]:
            yield from walk(child)
    nodes = list(walk(root))
    for node in nodes:
        if node["tag"].startswith("uses-permission") and node["attrs"].get("android:name") in {
                "android.permission.INTERNET", "android.permission.CAMERA"}:
            raise ValueError("Isolated proof must have neither INTERNET nor CAMERA")
    apps = [node for node in root["children"] if node["tag"] == "application"]
    if len(apps) != 1 or apps[0]["attrs"].get("android:name") != "android.app.Application":
        raise ValueError("Only plain Application may start")
    app = apps[0]
    if app["attrs"].get("android:allowBackup") != 0:
        raise ValueError("Proof backup must be disabled")
    instruments = [node for node in nodes if node["tag"] == "instrumentation"]
    if test:
        if app["children"] or len(instruments) != 1:
            raise ValueError("Test APK must be component-free with one instrumentation")
        attrs = instruments[0]["attrs"]
        if attrs.get("android:name") != RUNNER or attrs.get("android:targetPackage") != target:
            raise ValueError("Wrong instrumentation runner or target")
    else:
        if instruments or sorted(node["tag"] for node in app["children"]) != ["provider", "service"]:
            raise ValueError("Only the two exact bundled ML Kit components are allowed")
        provider = next(node for node in app["children"] if node["tag"] == "provider")
        service = next(node for node in app["children"] if node["tag"] == "service")
        for key, expected in {"android:name": "com.google.mlkit.common.internal.MlKitInitProvider",
                              "android:authorities": package + ".mlkitinitprovider",
                              "android:exported": 0, "android:initOrder": 99}.items():
            if provider["attrs"].get(key) != expected:
                raise ValueError("Unexpected ML Kit provider contract")
        if provider["children"]:
            raise ValueError("Unexpected provider children")
        for key, expected in {"android:name": "com.google.mlkit.common.internal.MlKitComponentDiscoveryService",
                              "android:exported": 0, "android:directBootAware": 0xffffffff}.items():
            if service["attrs"].get(key) != expected:
                raise ValueError("Unexpected ML Kit service contract")
        metadata = service["children"]
        if (len(metadata) != 3 or {node["attrs"].get("android:name") for node in metadata} != REGISTRARS
                or any(node["tag"] != "meta-data" or node["children"] or
                       node["attrs"].get("android:value") != "com.google.firebase.components.ComponentRegistrar"
                       for node in metadata)):
            raise ValueError("Unexpected ML Kit discovery registrars")
    # Components must be direct application children, never hidden in another tree.
    component_tags = {"activity", "activity-alias", "receiver", "provider", "service"}
    if sum(node["tag"] in component_tags for node in nodes) != (0 if test else 2):
        raise ValueError("Unexpected application component")
    return True


def validate_log(text, shared_validator):
    if re.search(r"(?i)shortMsg=|INSTRUMENTATION_ABORTED|FATAL EXCEPTION|Error:|\bcrash(?:ed)?\b", text):
        raise ValueError("Failure/crash output cannot be a hardware proof")
    return shared_validator(text, EXPECTED)
