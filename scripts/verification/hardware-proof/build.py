"""Build only the isolated physical-hardware debug APK pair. Never installs or runs tests."""
import sys
sys.dont_write_bytecode = True
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import runpy
import shutil
import subprocess
import time
from datetime import datetime, timezone
from uuid import uuid4

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUTPUT = ROOT / "artifacts/release27-engineering-20261003/hardware-proof"
JAVA = Path("C:/Program Files/Android/Android Studio/jbr")
SDK = Path("C:/Users/Dominik/AppData/Local/Android/Sdk")
SHARED = ROOT / "scripts/verification/workmanager-proof/build.py"
SHARED_SHA256 = "1c7df731df078d3404eb5cfa4430d7c8aeeb0c5ae8e86301c07c415f4b41452b"
CERTIFICATE_SHA256 = "61e8314b147e44653b8e0d169fe6caf22760c36ee47795cd8471b434166ba854"
APK_PATHS = ("outputs/apk/debug/app-debug.apk", "outputs/apk/androidTest/debug/app-debug-androidTest.apk")
SOURCES = json.loads(r'''[
  {
    "source": "app/src/androidTest/java/com/dominik/cardvault/PrivateBinderFirstPositiveInstrumentedTest.kt",
    "className": "com.dominik.cardvault.PrivateBinderFirstPositiveInstrumentedTest",
    "sha256": "434131fdc11e92ef5349c50d67e2e53d4acfc552c1bb1473a81d716dcde8a2ea",
    "methods": [
      "privateDetailsRetainCompleteCardsWithoutMaterializingWholeOwnerHistory",
      "sustainedPrivateDetailsTrackPriceLinkCatalogOwnerPlacementAndSelectionChanges",
      "safePortfolioRetainsCompleteModelWithoutWholeHistoryTransport",
      "sustainedPortfolioRetainsCompleteModelsAcrossBoundsLinksOwnersAndInvalidPrices"
    ]
  },
  {
    "source": "app/src/androidTest/java/com/dominik/cardvault/OwnedHistoryFlowInvalidationInstrumentedTest.kt",
    "className": "com.dominik.cardvault.OwnedHistoryFlowInvalidationInstrumentedTest",
    "sha256": "dbc105fc12fe2a2751cc1f0bd9804e069ebe9ba5cfc9afd1115b0186d904abdf",
    "methods": [
      "activeOwnedHistoryTracksPriceChangesTombstonesAndOwnerIsolation"
    ]
  },
  {
    "source": "app/src/androidTest/java/com/dominik/cardvault/BinderDataLoadingPerformanceInstrumentedTest.kt",
    "className": "com.dominik.cardvault.BinderDataLoadingPerformanceInstrumentedTest",
    "sha256": "12fb9b12dd1a244a78edef09a4e7e7d3f4b6bb9c43a76c46d231d41ee2aaf0e0",
    "methods": [
      "summariesDoNotLoad1920HistoricalRowsAndKeepIdenticalValuations",
      "lightweightSummariesRefreshOnClearTrendArrivalAndOwnerSwitch",
      "annualHistoryKeepsEveryValueAndOwnerBoundaryWithSameDateCorrections",
      "portfolioSelectsOnlyTheFourHistoryFieldsItConsumes"
    ]
  },
  {
    "source": "app/src/androidTest/java/com/dominik/cardvault/data/sync/PrivateSyncTransactionInstrumentedTest.kt",
    "className": "com.dominik.cardvault.data.sync.PrivateSyncTransactionInstrumentedTest",
    "sha256": "235430df69f218034469bf0f8620f4da924067ccf7e2c9f260609a288865aa8c",
    "methods": [
      "validSessionCommitsAndReturnsResult",
      "alreadyStaleSessionDoesNotEnterCallback",
      "accountChangeDuringWritesRollsPayloadAndCursorBack",
      "accountRoundTripStillRollsBack",
      "closedSessionDuringWriteRollsBack",
      "cancellationAfterWritesPreservesPriorPayloadCursorAndOutbox",
      "completedNestedRoomTransactionRollsBackWithStaleOuterSession",
      "completedSdkTaskReturnsOnlyForCurrentLease",
      "sdkTaskCompletionAfterAccountRoundTripCannotApplyResult",
      "cancelledSdkAwaitDoesNotApplyALaterRemoteCompletion",
      "missingCachedOrPendingDependencyDoesNotEnterRoomApply",
      "confirmedDependencyCanApplyUnderCurrentSession"
    ]
  },
  {
    "source": "app/src/androidTest/java/com/dominik/cardvault/ui/CameraQualityTest.kt",
    "className": "com.dominik.cardvault.ui.CameraQualityTest",
    "sha256": "25341b7840b7e55e119e1aa9a484dc6d14ae7810db5b5c219097ee76bb573c0c",
    "methods": [
      "detectsCardShapedQuadrilateralAndRejectsBlankFrame"
    ]
  },
  {
    "source": "app/src/androidTest/java/com/dominik/cardvault/ui/CardImageNormalizerInstrumentedTest.kt",
    "className": "com.dominik.cardvault.ui.CardImageNormalizerInstrumentedTest",
    "sha256": "8dc8bec588408c39e92ec5e87e305bf19bcc4008b0fb4c5cc46d34965809f977",
    "methods": [
      "appliesAllEightExifTransformsToPixels",
      "normalizesPlausibleCardFrameOnDevice",
      "rejectsImageWithoutPlausibleCardFrameOnDevice",
      "rejectsTexturedFabricWithoutPhysicalCard",
      "holoHighlightDoesNotInvalidateAnOtherwisePlausibleSpecialCard",
      "landscapeCardProducesOnlyAspectCorrectCanonicalCandidates",
      "completesSmallInterruptionsInLowContrastOuterBorder"
    ]
  },
  {
    "source": "app/src/androidTest/java/com/dominik/cardvault/ui/ScannerSessionIsolationInstrumentedTest.kt",
    "className": "com.dominik.cardvault.ui.ScannerSessionIsolationInstrumentedTest",
    "sha256": "12ff9decc2f90439e03940a5951bf68eb08e613f1ed69259a2d36d19668f49dc",
    "methods": [
      "cancellationCleanupSubmissionCannotRaceShutdownWhilePreprocessingOwnsBitmap",
      "repeatedAnalyzerCloseRacingEmptyCaptureCleanupFinishesExactlyOnce",
      "cancelDuringLeasedNativeOcrReleasesInputsAndAllowsAnotherIdentity",
      "sameAnalyzerReadsNewIdentityFromSecondSameSizedCapture"
    ]
  }
]''')
SELECTORS = [item["className"] for item in SOURCES]
EXPECTED_TESTS = [item["className"] + "#" + method for item in SOURCES for method in item["methods"]]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def ordinary(path, root=ROOT):
    require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(root.resolve()),
            "Exact ordinary file required: " + str(path))
    return path


def snapshot():
    # Import only reviewed definitions. The original builder's __main__ never runs.
    require(digest(ordinary(SHARED)) == SHARED_SHA256, "Preservation helper changed")
    shared = runpy.run_path(str(SHARED))
    result = shared["snapshot"]()
    for path in sorted(HERE.rglob("*")):
        require(not path.is_symlink(), "Linked hardware proof input")
        if path.is_file() and "__pycache__" not in path.parts:
            result["inputs"][path.relative_to(ROOT).as_posix()] = {
                "bytes": path.stat().st_size, "sha256": digest(ordinary(path))}
    return result


def contracts():
    path = ordinary(HERE / "contracts.py")
    spec = importlib.util.spec_from_file_location("hardware_build_contracts", path)
    require(spec is not None and spec.loader is not None, "Missing pure contract loader")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def inspect_command(command, env):
    # Only local read-only APK inspectors, never ADB/Gradle discovery or an arbitrary command.
    result = subprocess.run([str(part) for part in command], cwd=ROOT, env=env,
        stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        timeout=30, shell=False, creationflags=subprocess.CREATE_NO_WINDOW)
    require(result.returncode == 0 and len(result.stdout) <= 4 * 1024 * 1024,
            "APK inspector failed or exceeded output budget")
    return result.stdout.decode("utf-8")


def main():
    require(sys.platform == "win32" and sys.version_info >= (3, 11) and
            sys.flags.isolated and sys.flags.dont_write_bytecode, "Windows Python3.11+ -I -B required")
    require(HERE == Path("C:/Users/Dominik/AndroidStudioProjects/CardVault/scripts/verification/hardware-proof"),
            "Fixed repository helper required")
    require(sys.argv[1:] == ["--build"], "Use exactly --build; no runtime/install/release actions")
    require(not (ROOT / "keystore.properties").exists() and
            not any(key.startswith("DOMIDEX_UPLOAD_") for key in os.environ), "No release credentials")
    require(len(EXPECTED_TESTS) == 33 and len(set(EXPECTED_TESTS)) == 33 and len(SELECTORS) == 7,
            "Only the reviewed33 methods/seven complete classes")
    runner = HERE / "androidTest/com/dominik/cardvault/HardwareProofRunner.kt"
    require(list((HERE / "androidTest").rglob("*.kt")) == [runner], "Only the diagnostic runner may be injected")
    for item in SOURCES:
        source = ordinary(ROOT / item["source"])
        require(digest(source) == item["sha256"], "Reviewed original test changed: " + item["source"])
        methods = re.findall(r"@Test\s+fun\s+([A-Za-z][A-Za-z0-9_]*)\s*\(", source.read_text(encoding="utf-8"))
        require(methods == item["methods"], "Test identities changed")
    contract = contracts()
    require(list(contract.EXPECTED_TESTS) == EXPECTED_TESTS and list(contract.SELECTORS) == SELECTORS,
            "Independent host selection differs")
    run_id = uuid4().hex
    package = "com.dominik.cardvault.hardwareproof.r" + run_id
    directory = OUTPUT / run_id
    directory.mkdir(parents=True, exist_ok=False)
    before = snapshot()
    injection = directory / "injected-source"
    injection.mkdir()
    injected = []
    for item in SOURCES:
        relative = Path(item["source"]).relative_to("app/src/androidTest/java")
        copied = injection / relative
        copied.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / item["source"], copied)
        require(digest(copied) == item["sha256"] ==
                before["inputs"][item["source"]]["sha256"], "Source copy is not byte-identical")
        injected.append({**item, "relativeCopied": copied.relative_to(directory).as_posix()})
    record = {"runId": run_id, "package": package, "testPackage": package + ".test",
        "startedAt": datetime.now(timezone.utc).isoformat(), "injectedSources": injected,
        "expectedTests": EXPECTED_TESTS, "selectors": SELECTORS, **before}
    with (directory / "before.json").open("x", encoding="utf-8") as stream:
        json.dump(record, stream, indent=2)
    allowed = {"SYSTEMROOT", "WINDIR", "SYSTEMDRIVE", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA",
               "PROCESSOR_ARCHITECTURE", "NUMBER_OF_PROCESSORS"}
    env = {key: value for key, value in os.environ.items() if key.upper() in allowed}
    env["JAVA_HOME"] = str(JAVA)
    env["PATH"] = str(JAVA / "bin") + os.pathsep + "C:/Windows/System32"
    command = [str(ROOT / "gradlew.bat"), "--offline", "--no-daemon", "--no-configuration-cache", "--no-build-cache",
        "--console=plain", "--init-script", str(HERE / "proof.init.gradle"), "-PhardwareProofRun=" + run_id,
        "-PdomidexAppCheck=false", ":app:assembleDebug", ":app:assembleDebugAndroidTest"]
    code, process, failure_type, cleanup_error, timed_out = -1, None, None, None, False
    print("Isolated hardware build only: " + str(directory), flush=True)
    with (directory / "build.log").open("xb") as log:
        try:
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=log,
                stderr=subprocess.STDOUT, shell=False, creationflags=subprocess.CREATE_NO_WINDOW)
            deadline = time.monotonic() + 900
            while process.poll() is None:
                if time.monotonic() >= deadline:
                    timed_out = True
                    raise TimeoutError("Own build exceeded900s")
                require((directory / "build.log").stat().st_size <= 64 * 1024 * 1024, "Own build log exceeded64MiB")
                time.sleep(.1)
            code = process.returncode
            require((directory / "build.log").stat().st_size <= 64 * 1024 * 1024, "Build log exceeded64MiB")
        except BaseException as error:
            failure_type = type(error).__name__
        finally:
            if process is not None and process.poll() is None:
                # Exact still-live owned build tree only, same boundary as history-proof.
                try:
                    stopped = subprocess.run(["C:/Windows/System32/taskkill.exe", "/PID", str(process.pid), "/T", "/F"],
                        env=env, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                        timeout=30, shell=False, creationflags=subprocess.CREATE_NO_WINDOW)
                    process.wait(timeout=30)
                    if stopped.returncode != 0:
                        cleanup_error = "OwnedProcessTreeStopFailed"
                except BaseException as error:
                    cleanup_error = type(error).__name__
    classes = directory / "app-build/intermediates/built_in_kotlinc/debugAndroidTest/compileDebugAndroidTestKotlin/classes"
    class_names = sorted(path.relative_to(classes).as_posix() for path in classes.rglob("*.class"))
    prefixes = ["com/dominik/cardvault/HardwareProofRunner"] + [name.replace(".", "/") for name in SELECTORS]
    proof_classes = bool(class_names) and all(any(name == prefix + ".class" or name.startswith(prefix + "$")
        for prefix in prefixes) for name in class_names) and all(prefix + ".class" in class_names for prefix in prefixes)
    artifacts, inspector_receipts = {}, {}
    manifest_verified, certificate = False, None
    try:
        for relative in APK_PATHS:
            apk = ordinary(directory / "app-build" / relative, directory)
            artifacts[relative] = {"bytes": apk.stat().st_size, "sha256": digest(apk)}
        if code == 0 and failure_type is None and cleanup_error is None:
            aapt = ordinary(SDK / "build-tools/36.0.0/aapt2.exe", SDK)
            signer = ordinary(SDK / "build-tools/36.0.0/lib/apksigner.jar", SDK)
            java = ordinary(JAVA / "bin/java.exe", JAVA)
            for index, relative in enumerate(APK_PATHS):
                apk = directory / "app-build" / relative
                tree = inspect_command([aapt, "dump", "xmltree", apk, "--file", "AndroidManifest.xml"], env)
                badging = inspect_command([aapt, "dump", "badging", apk], env)
                signature = inspect_command([java, "-jar", signer, "verify", "--verbose", "--print-certs", apk], env)
                own_package = package if index == 0 else package + ".test"
                certificate = contract.validate_debug_apk(badging, signature, own_package)
                require(certificate == CERTIFICATE_SHA256, "Exact reviewed standard debug certificate required")
                contract.validate_manifest(tree, own_package, test=index == 1, target=package)
                inspector_receipts[relative] = {"manifest": tree, "badging": badging, "signature": signature}
            manifest_verified = True
    except Exception as error:
        failure_type = failure_type or ("ArtifactVerification:" + type(error).__name__ + ":" + str(error))
    try:
        after = snapshot()
        preserved = before == after and all(digest(ordinary(directory / item["relativeCopied"], directory)) ==
                    item["sha256"] for item in injected)
    except Exception as error:
        after, preserved = {"inventoryError": type(error).__name__}, False
    outcome = {"runId": run_id, "package": package, "testPackage": package + ".test",
        "finishedAt": datetime.now(timezone.utc).isoformat(), "exitCode": code,
        "inputsAndOldOutputsUnchanged": preserved, "buildFailureType": failure_type, "timedOut": timed_out,
        "ownedProcessCleanupError": cleanup_error, "proofClassesVerified": proof_classes,
        "compiledClasses": class_names, "injectedSources": injected, "expectedTests": EXPECTED_TESTS,
        "selectors": SELECTORS, "artifacts": artifacts, "packagedManifestVerified": manifest_verified,
        "debugCertificateSha256": certificate}
    passed = code == 0 and not failure_type and not cleanup_error and preserved and proof_classes and manifest_verified
    outcome["status"] = "PASS" if passed else "FAIL"
    outcome["proofExitCode"] = 0 if passed else 1
    for name, content in (("after.json", after), ("apk-inspection.json", inspector_receipts), ("build-result.json", outcome)):
        with (directory / name).open("x", encoding="utf-8") as stream:
            json.dump(content, stream, indent=2)
    print(json.dumps(outcome), flush=True)
    return outcome["proofExitCode"]


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (Exception, KeyboardInterrupt) as failure:
        print(type(failure).__name__ + ": " + str(failure), file=sys.stderr)
        raise SystemExit(1)
