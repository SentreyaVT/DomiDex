# Isolated Redmi local hardware proof

Status: authored, not built or run. Root alone builds/installs/executes after review.
Do not run while the user is manually testing the ordinary application.
This is an additive standalone harness; ordinary build files, application/test
sources and emulator guards are not changed. No release approval is implied.

## Fixed scope

The helper copies these complete existing sources byte-for-byte, with fixed source
SHA256 and method inventories in `build.py` and independently fixed host identities
in `contracts.py`:

| Class (under com.dominik.cardvault) | Methods |
| --- | ---: |
| PrivateBinderFirstPositiveInstrumentedTest | 4 |
| OwnedHistoryFlowInvalidationInstrumentedTest | 1 |
| BinderDataLoadingPerformanceInstrumentedTest | 4 |
| data.sync.PrivateSyncTransactionInstrumentedTest | 12 |
| ui.CameraQualityTest | 1 |
| ui.CardImageNormalizerInstrumentedTest | 7 |
| ui.ScannerSessionIsolationInstrumentedTest | 4 |

Exactly 33 methods, seven complete classes. No assertion, fixture, timeout, OCR
policy, emulator guard or source body is rewritten. This is NOT the prior focused
29-case matrix or the Full371 selection. The two OCR failures on API37 remain RED;
this package retains the original OCR class, not its separate diagnostic patch.

The Room tests use in-memory databases and synthetic owners; pixel tests use
generated bitmaps/bytes. Real bundled ML Kit and OpenCV execute without camera or
user photographs. No actual scan quality, thermal/camera lifecycle, physical
two-device sync, backend, full-app startup, release/minified or 16-KB proof follows
from this finite functional check. Root observed this Redmi as API36/Android16,
arm64-v8a, 4096-byte pages; do not relabel it as the API37 x86_64/16-KB result.

## Separate application and legitimate ML Kit bootstrap

Each build uses `com.dominik.cardvault.hardwareproof.r<32hex-build-UUID>` and its
`.test` package. Sources retain their Kotlin namespace, which is not the installed
application ID. The generated package cannot replace `com.dominik.cardvault`.
Build output is only `artifacts/release27-engineering-20261003/hardware-proof/`
`<UUID>/app-build/`; normal APK/AAB/mapping and the protected Release26 archive are
snapshotted before/after, as are source/schema/harness inputs.

The proof manifest replaces the application with `android.app.Application` and
removes INTERNET/CAMERA. Its only provider is the original
`com.google.mlkit.common.internal.MlKitInitProvider`, with run-specific authority,
nonexported and initOrder99. Its only service is the original nonexported
`MlKitComponentDiscoveryService`, carrying exactly TextRegistrar,
VisionCommonRegistrar and CommonComponentRegistrar. Their `com.google.firebase.components:`
metadata names belong to ML Kit dependency discovery, not FirebaseApp startup.
The provider legitimately initializes bundled ML Kit; the runner checks the real
initialized context. No FirebaseInitProvider, WorkManagerInitializer, product
Application, Activity, receiver or other service/provider is retained. The test
APK has a plain Application and no component tree. APK manifests, debug signing
and package identity must pass the independent host contract after packaging.

ML Kit/Room may create their own local bookkeeping in the fresh proof UID. This is
not a claim of zero local writes. No ordinary app DB/preferences/photos/auth or
ScanLab/knowledge opt-ins are used. No internet permission is available even if
the device's network state changes. This is package isolation, not an OS sandbox
or a proof against every possible SDK/platform defect.

## Build only

From the fixed Windows repository with the existing reviewed SDK/JBR and no release
credentials, Root may run after review:

```powershell
python -I -B scripts/verification/hardware-proof/build.py --build
```

This command invokes only offline `:app:assembleDebug` and
`:app:assembleDebugAndroidTest`, with fresh output roots, normal pinned debug
certificate and disabled Google Services resource generation for the synthetic ID.
No install, ADB, emulator, network change, release build, signing-key import,
publishing or test execution occurs. A missing offline dependency is a failure,
not download authorization. The 900-second build/64-MiB log guard may stop only
its exact still-live owned host build process tree, never unrelated Gradle/IDE
processes or an Android process. No cleanup deletes existing outputs.

Before/after/build-result/apk-inspection JSON files preserve the input inventory,
copied source hashes, 33 expected IDs, compiled class allowlist, APK bytes/digests,
authentic build exit and separate proof status. Only `status=PASS`,
`proofExitCode=0`, all preservation/manifest/class checks and both APKs complete
build preparation. This is not runtime PASS or reproducible release attribution.

## Runtime boundary

The separately reviewed `run.py` owns host preflight/runtime and must target only
Root-verified Redmi serial `e30bd3ce` / model `24115RA8EG` / device `amethyst`.
The custom instrumentation accepts only the seven class selectors in fixed order,
`hardwareProofMode=isolated-redmi33`, its exact `hardwareProofRun` build UUID, and a
separate `domidexRegressionRunId`32hex token. It rejects alternate discovery,
sharding/log-only/coverage and other arguments. It checks debug package, plain
Application, device and denied network/camera permissions before
allowing the unchanged tests. The phone may stay online for ordinary app use;
no airplane/Wi-Fi/default-network change or offline prerequisite is imposed.
No phone serial is inferred from a connected-device
list. Installation, runtime and owned-package cleanup remain distinct explicitly
authorized Root actions; never clear/uninstall/reset the ordinary app for this
proof, and never bypass a busy/user-foreground guard.
