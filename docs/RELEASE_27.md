# Release 27 engineering ledger

This is a work-in-progress evidence ledger, not a release approval. Updated 2026-10-01.

## Scope and safety boundaries

Harden the existing Android client and separate Web/backend without replacing working
offline-first Room, outbox, scanner, authentication or pricing behavior. Work proceeds
in gates; findings and red tests are retained, not relabelled as successful tests.
No production deployment, Firebase configuration change, Play upload, secret rotation,
billing change or release signing has been performed in this engineering run.
Only normal local debug builds are permitted for isolated emulator validation now.
The physical Xiaomi is not used. Real-device validation remains NOT TESTED.

The five user mission documents define the scope. Their gate numbering overlaps at
14–15: database migration, release engineering and final RC validation are tracked
separately below so none is omitted.

## Gate 0: frozen current baseline

| Item | Android | Web/backend |
|---|---|---|
| Workspace | `CardVault` | sibling `DomiDex-Web` |
| Starting branch | `codex/ui-v3` | `main` |
| Starting HEAD | `096aa356b5efc513483fd081bc2256683a5196d9` | `59d11be5a359f8eac8e32e7b37b3c98dd5a976cf` |
| Preserved current baseline | `53b4e40d669012f69e6d1920d916475e0107b8fb` | `6caa46a011889a416041224a67d89b75db7016da` |
| Engineering branch | `codex/release-27-hardening` | `codex/release-27-hardening` |
| Existing version | 27 / `1.0-closed-alpha.6` | package `1.0.0-pre-beta` |

Both starting working trees contained extensive pre-existing work. The checkpoint
commits preserve that work; they do not claim to reproduce the source of release 26.
Original branches were not rewritten; engineering branches have not been pushed.
The Android application ID is `com.dominik.cardvault`.

### Immutable historical release 26

Existing directory: `releases/google-play-v26-closed-alpha-2026-09-23/`.

| File | SHA-256 |
|---|---|
| `DomiDex-1.0-closed-alpha.5-v26-SIGNED.aab` | `079608E27BCB5D440252ED727E238B162B449F3DBA4B1C28986D4A4570D9B14B` |
| `mapping.txt` | `E6A081B27940982450CB40CBF4BE09D393AA3A79BF9F8B6931AF806B5D5C204E` |
| `native-debug-symbols-PARTIAL/native-debug-symbols.zip` | `04DCB9585023EC99E89858965A5758BCC3BD12B1EA0652C8FF75CCE17EAB8C28` |

Historical AAB size: 52,352,301 bytes. These artifacts stay immutable. No new release
26 artifact or symbol archive will be manufactured. Partial symbols are not complete
manufacturer symbol coverage. Release comparisons must distinguish this artifact
from the reproducible current-source checkpoint above.

### Toolchain and schema

- Gradle 9.5.0, AGP 9.3.1, Kotlin 2.2.10, KSP 2.3.10.
- Android Studio JBR (JDK 25), Kotlin/Java target 11; min SDK 26, compile/target 37.
- Room schema 13; explicit migrations including 12→13 price-selection sidecar.
- Existing conservative R8 configuration stays intact. Release arm64 packaging and
  upload signing configuration are not modified as part of baseline collection.
- Web uses Vite/vinext, React, Firebase and a separate backend; it is not copied into
  the Android repository. Node requirement is >=22.13.

### Baseline measurements and limitations

| Check | Result | Evidence / caveat |
|---|---|---|
| Android debug + androidTest APK build | PASS | combined Gradle run below |
| Android release Kotlin compilation | PASS | compilation only, not an R8/release APK/AAB validation |
| Android debug/release lint | PASS | existing lifecycle deprecation warnings remain |
| Combined Gradle invocation | PASS, 247.55 s | 104 tasks; 17 executed, 87 up-to-date |
| Android JVM unit tests | PASS, 629 / 630, 41.40 s | one pre-existing optional live Cardmarket test skipped; fresh rerun |
| Android local instrumentation | PASS, 83 tests, 32.574 s | fresh isolated API 36 AVD, network disabled; Room/outbox/prices/scanner |
| Emulator camera rotations | PASS, 1 test, 10.956 s | actual emulator JPEGs at four display rotations; no recreation, portrait preserved |
| Scanner OCR baseline | PASS harness, 60 measured samples, 571.596 s total | median 13,690.748 ms / P95 16,118.944 ms; limited flat-image catalogue, not real-camera accuracy |
| Room scale reads | PASS, 1k / 5k / 20k entries | exact totals/IDs/owner isolation, unchanged per-column hashes; first subscription plus three warm repeats |
| Room 12→13 scale migrations | PASS, nine fresh synthetic databases | three each at 1k/5k/20k; every prior field/ID/SQLite sequence preserved; both scale tests total 220.416 s |
| Web primary suites | PASS, 611 tests / 48 files, 13.53 s | external network blocked, `envDir:false` |
| Web additional suites | PASS, 76 tests / 7 files, 1.73 s | same isolation |
| Web Node tests | PASS, 7 tests, 0.19 s | knowledge/deletion compatibility |
| Web typecheck | baseline FAIL → fixed PASS | TS2352 in price-alternative-picker test; correct runtime type narrowing |
| Web lint | baseline FAIL → fixed PASS | 26 test-only findings; no suppressed rules or weakened assertions |
| Web legal prebuild check | PASS | local route check |
| Android existing Firebase rules suites | PASS, 18 tests, 5.89 s | 11 Firestore + 7 Storage; localhost demo project |
| Expanded security contract suite | PASS, 89/89, 33.338 s | 18 existing Android + 62 new + 9 historical Web; 24 new red cases fixed |
| Web isolated production-mode build | PASS, 20.413 s | 294-source-file temp copy; original config; demo env, network blocked; NOT deployable |
| Live cost/Cloud Run/App Check | NOT TESTED | no external production actions |

Baseline Gradle command:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:compileReleaseKotlin --offline --no-daemon --console=plain
```

Fresh unit rerun: `:app:testDebugUnitTest --rerun --offline` (107 suites).
The existing skipped test is `CardmarketLiveSmokeTest.officialDownloadsRemainParseableWhenExplicitlyEnabled`,
which is deliberately opt-in for external downloads, not disabled by this run.
Baseline debug APK SHA-256: `0317026C31C95549ED70687E89B3298476D2660E454BCC9AB989E98FAB41A158`;
baseline instrumentation APK: `E6387921EB5D1797A00CA70009330D5F506C512C0B3E56F2245EEE00DB363585`.
The new `ScannerKnowledgeSafetyRegressionTest` was run separately after that baseline:
9 tests, **4 FAIL / 5 PASS**, 27 s Gradle invocation. The four failures reproduce
hint-only promotion and unknown/ancient hint freshness; no product fix is claimed yet.
Synthetic geometry benchmark: PASS, 21 cases × 12 measured repetitions, 20.567 s;
input pixel/luma hashes are recorded. This is not physical-camera recognition accuracy.
Release pipeline: 39 Python tests PASS; Android's existing Functions aggregator: 3
Node tests PASS. Those tests do not sign or publish a build.

The OCR fixture results are 26 ACCEPTED, 10 AMBIGUOUS, 24 REJECTED; collector number
correct in 36/60 samples and zero card-ID false accepts against the six-fixture
catalogue. This does **not** prove zero printing/language errors: the old harness
does not yet score these identities, and rejects are not recognition successes.
The low recall remains visible and must not be hidden by the harness PASS result.

Local Room warm medians (three samples, milliseconds; emulator, not UI/network):

| Entries | Collection | Set summaries | Private summaries | Portfolio | Private contents |
|---|---:|---:|---:|---:|---:|
| 1,000 | 17.93 | 48.98 | 48.93 | 58.48 | 57.81 |
| 5,000 | 92.39 | 141.88 | 148.23 | 205.46 | 273.92 |
| 20,000 | 836.64 | 1,051.16 | 1,023.20 | 1,857.39 | 1,805.12 |

The fixture has 5% tombstones and synthetic second-owner sentinels. Room outbox
limit-200 queries remained below 6 ms in these samples. Larger reads expose real
scaling cost, not an invented performance SLA. No scanner timing improvement or
remote sync reduction is claimed from these local measurements.
Migration+schema-validation medians were 51.23 / 48.70 / 44.29 ms at the three
sizes. The full test also seeds and hashes the data outside these timing windows;
this must not be advertised as end-to-end upgrade duration. Each new sidecar is
empty as expected; old binder/outbox/tombstone fields and foreign keys remain valid.

Read-only Git history pattern scan: 964 Android and 701 Web text blobs, no matches
for the high-confidence private-key/GitHub/OAuth/Slack token patterns. Binary blobs
and other possible secret formats are not proven clean by this scan. The original
source inventory found no credentials; intentionally synthetic rejection fixtures
are distinguished from actual credential material. No secret value is logged.

Local most-recent tag by creation sort: `v1.0.0-beta.1` (historical tag, not release
27). GitHub API confirms latest published archive `archive/v26-1.0-closed-alpha.5`
at 2026-10-01T00:42:52Z, marked prerelease, not a Play release action. Historical
Play documentation records version 26 in Internal/Closed tracks; this run has not
modified or newly verified the Play Console.
Local evidence directories are ignored, not published as source:

- Android: `artifacts/release27-engineering-20261001/`
- Web: `outputs/release27-engineering-20261001/baseline/`

### Baseline rules consistency

Android and Web copies are byte-identical, as inspected locally (not asserted live):

- Firestore SHA-256: `C69476213CD5475E388899EE2A9286F8BD3086C389D6098793E7FA36D01B9E48`
- Storage SHA-256: `795F343C173229E338F0459351F2E76D1F8FA51152D609C53B63AD2ADA32953F`

Existing tests do not cover the whole disabled/deletion-lock/price-selection matrix.
An 18-test pass alone is not Gate 1 approval.

## Gate ledger

| Gate | Status | Required next evidence |
|---|---|---|
| 0 Baseline | PASS (local reproducible scope) | frozen current source, fresh JVM/instrumentation/scanner timings and isolated Web build; live limits explicit |
| 1 Firebase security | PASS (local rules) | see RELEASE_27_SECURITY.md; full Android outbox multi-device test remains Gate 4 |
| 2 API security | PASS (authenticated write scope) | 773 non-emulator + 30 emulator tests; public IP/token limits remain Gate 6, source-configured limits and deployment limits explicit |
| 3 App Check | PASS (local disabled-rollout preparation) | 15 new JVM + 2 startup emulator tests; 52 backend tests; real enabled attestation/latency and worker ordering remain manual, no live enforcement |
| 4 Collection/binder sync | IN PROGRESS | ACK/session/remote-apply and explicit conflict protection tested; real local two-SDK matrix 25/25; local delta-to-business Room adapter 32/32 and full sync-package 141/141; full Android/Web convergence, active delta integration and multi-client scale remain; see SYNC.md |
| 5 Pricing/catalog | IN PROGRESS | bounded atomic Android import, Web snapshot/identity fixes, seven verified mappings and incremental price resolution tested; full coverage and centralized delivery remain |
| 6 Shared binder scale | IN PROGRESS | in-flight deletion/ownership, bounded public cache and exact persisted-price/supplement parity tested; O(N) placement reads, total fan-out and trusted-ingress public IP/token limits remain |
| 7 Camera stability | PARTIAL (emulator regression PASS) | 25 focused lifecycle/OCR-session/cache/status/virtual-camera cases pass; four display rotations preserve portrait and Activity identity; physical camera, permission-revocation and background-capture matrix remain untested |
| 8 Recognition | IN PROGRESS (limited replay PASS) | additive identity oracle 12/12; 60 observations with matching input hashes and unchanged historical outputs, 42/42 geometry outputs unchanged; no demonstrated overall speed/recall gain or physical-printing accuracy |
| 9 Community knowledge | IN PROGRESS (local safety/aggregation tests PASS) | confidence/freshness/lifecycle faults fixed; 48 focused JVM + 11 Android cases; counter/key hardening and 61 synthetic concurrency tests pass; actual trigger contention/live evidence remain |
| 10 Privacy/lifecycle | IN PROGRESS (local owner/session fixes PASS) | feedback backup owner/session and Web deletion-consent races corrected; 47 focused Android + 8 JVM and 27 real local Firebase tests pass; provider retention/live deletion and complete workflow remain unverified |
| 11 Observability/cost | PARTIAL (local audit/model) | safe-log inventory and hypothetical 1k/5k/10k/50k resource model, 6 tests; actual metrics, alerts, prices and external retention remain unverified |
| 12 Load/scale | PARTIAL (local simulation) | 10/40/80 sharing concurrency and 1k/5k/20k Room baselines; O(N) reads and provider fan-out remain, no production load |
| 13 Full regression | IN PROGRESS | latest root 824 JVM PASS/1 opt-in SKIP, 1,104 Web, 339 distinct emulator tests (327 regression + 12 manual-analysis), release/debug lint and isolated Web build pass; prior SwiftShader host crashes retained; physical/live contracts untested |
| 14 Database migration | BASELINE PASS | current 12→13 on 1k/5k/20k with all old columns preserved; rerun if later schema changes |
| 15 Release engineering | BLOCKED by earlier gates | no release signing/build before critical gate evidence |
| RC final validation | NOT READY | complete technical report and final explicit recommendation |

## Findings to reproduce, not assumed fixed

- Baseline had no common multi-instance request limiter. Gate 2 now uses shared
  Firestore transactions for report uploads/share creation/role grants, with
  monitor-first configuration and explicit later enforcement. Rights revocation and
  account deletion remain available without consuming these write quotas. Public
  IP/token limits and their trusted ingress boundary remain Gate 6 work.
- Sharing fetches external card data directly and does not use the same enrichment
  or price-selection path as normal collection pricing. Validate exact identity and
  public-price consent before unifying any path.
- Existing scanner public fixture benchmark has six flat images; it is not a full
  camera/variant benchmark. Card-ID-only correctness misses printing errors.
- Shared knowledge may survive a missing remote document and lacks a clear TTL;
  community score boosts may promote close HIGH candidates to ACCEPTED. Reproduce
  with tests before changing decision policy; do not conceal the red tests.
- Private placement/layout parent checks on create vs update require lifecycle tests
  before deciding whether changes are necessary for cleanup and offline compatibility.

## Manual validation matrix (initial state)

| Area | Status | Reason |
|---|---|---|
| Xiaomi / physical camera / low light / sleeves / reflections | NOT TESTED | emulator-only engineering phase |
| Live Google Sign-In / Play Integrity | NOT TESTED | no live account/device validation in this run |
| Production disabled-account / deletion workflow | NOT TESTED | test synthetic emulator accounts instead |
| Cloud Run scaling and actual billing | NOT TESTED | prepare local measurements and manual verification |
| Google Play upload / symbols accepted for 27 | NOT TESTED | no upload authorized/performed by this run |

## Work rules and rollback anchors

Isolated changes get tests and logical commits after review. Keep the two frozen
baseline commits as rollback anchors; never reset or discard unrelated user work.
No Room downgrade or destructive migration is presumed safe. No production state
has changed, so deployment rollback instructions will be written before any eventual
human-authorized rollout. `docs/WEBAPP_RELEASE_27_HANDOFF.md` will document shared
contracts and separate workspace responsibilities as those gates are implemented.

Final delivery must contain all 29 requested report sections and an evidence-based
READY FOR INTERNAL VALIDATION / READY FOR CLOSED TEST / NOT READY classification.
This ledger does not declare the mission complete.

## Latest local checkpoint: account boundaries and regression (2026-10-01)

Android `058ddbf` isolates local scan-feedback metadata by the captured owner and
observed account-session generation. Legacy records without an owner remain
unassigned rather than silently being exported or assigned to the current user.
Explicit import binds records to the captured target session; foreign/legacy ID
collisions fail closed. Export/import callbacks check the session around IO and
repository awaits. Same-UID refresh or offline sync state alone does not invalidate
that local session. Room schema, cloud permissions and manual upload consent are
unchanged. File-copy cancellation cannot retract bytes already written to an
external destination; the existing pre-editor scanner queue is not newly modeled
as owner-specific storage. These limits remain documented, not hidden.

Focused verification passes 8 JVM session tests and 47 offline instrumentation
tests, including feedback owner, backup restore/journal and privacy UI cases.
Earlier RED cases reproduce cross-owner export/collision, late callbacks and the
initial same-UID self-invalidation problem. The separate `18bf60f` cloud-conflict
resolution fix revalidates captured ownership around both remote and local phases;
it does not reinterpret temporary lack of cloud access as permission to drop local
pending data. No destructive migration or new remote-wins default was introduced.

Web `801591a` and `e6d9e29` bind account-deletion consent and async callbacks to the
observed auth session. A UID A→B→A transition invalidates old consent even if React
has not rendered B. Checkbox revocation, unmount and stale completions cannot
initiate a replacement account's request or sign out that replacement session.
A request already sent to the server is not retroactively cancelled by switching
accounts; server token UID remains authoritative. The final 19 cases against the
original component produce 15 failures / 4 passes; the corrected component passes
19/19. A focused real-component/status/routes/auth-provider selection passes 50/50.

Root's final combined Android Gradle checkpoint passes in **3m 4s**: debug app/test
APK builds, debug unit tests, debug/release lint and release Kotlin compilation.
There are **819 JVM tests: 818 PASS, 1 existing opt-in SKIP, 0 failures/errors** in
120 classes. The skip remains the deliberate external Cardmarket smoke test.
Release Kotlin/lint are not R8 execution, signing or an AAB verification.
Evidence: `artifacts/release27-engineering-20261001/gate10-root-regression.log`.

The independent durable Web regression passes **1,050/1,050 in 66 files**, 33.73 s,
with external Node/JSDOM network blocked and environment-file loading disabled.
Existing React-act/Canvas harness warnings remain. Evidence:
Web `outputs/release-local/2026-10-01T03-46-31-932Z-16420.{json,log}`.
The offline release-pipeline verifier rerun passes **39/39 Python tests**, 0.318 s;
the release-26 AAB, mapping and partial-symbol archive hashes above remain identical.
No production key, release artifact, deployment, push or Xiaomi was used.

### Expanded emulator run: retained infrastructure failure

The offline API-36 run selected 287 existing tests across data, backup, price,
sharing, auth/UI and binder visuals. After 54 successful cases it stopped during
`PrivateBinderVisualSelectorsInstrumentedTest#holoSelectorKeepsInteractivePreviewPinnedWhenSelectingLowerEffects`.
The Windows Application log records `qemu-system-x86_64-headless.exe` exception
`0xc0000005` at 05:53:57 local time. An isolated repeat on the same AVD/APKs and
SwiftShader configuration reproduced a host-process crash at 05:58:37. No Android
assertion result or final instrumentation summary was returned. Therefore this is
**INCOMPLETE / infrastructure failure**, never a 287-test PASS and not evidence of
a proven DomiDex Java/Kotlin crash. A graphics/driver cause is not yet established.

Emulator version is 37.1.11 / build 15917651; AVD
`DomiDex_R27_Baseline_API36`, API 36, x86_64, 4 GiB, `-no-snapshot -gpu swiftshader`.
The `swangle` launch alternative still reports SwiftShader GLES in this installed
runtime and also did not complete the isolated test. A separate host-GPU diagnostic
was then performed; changing test rendering is not an app-code fix or permission to omit
the assertion. No AVD wipe or Xiaomi action was performed. Original run and recovery
logs remain under the engineering artifact directory as `gate13-*.log`.

The **same failing selector** passes unchanged with `-gpu host` in 6.005 s; startup
confirms the AMD Radeon host-GLES path rather than the prior SwiftShader path.
This supports a renderer-dependent emulator problem, not a proven particular
driver defect. After installing the rebuilt test-only APK, the full original 287
tests plus 12 new scanner identity-oracle tests pass **299/299 in 218.819 s**.
No assertions were removed, relaxed or skipped. Evidence:
`gate13-selector-host.log`, `gate13-root-expanded-host-instrumentation.log`.
Renderer changes make these UI timings unsuitable as an app-performance A/B claim.

An additional **25/25** scanner tests pass in 39.587 s: cancellation/watchdog,
cleanup races (including 512 close/finish iterations), actual bundled native OCR
followed by another synthetic identity, orientation/cache bitmap consistency,
status UI, auto-mode opt-in and camera JPEG/EXIF at four display rotations without
Activity recreation. AVD back-camera is `emulated`, front-camera `none`; no laptop
webcam or physical phone is captured. Evidence: `gate7-root-scanner-lifecycle-camera.log`.
These tests do not establish physical-card accuracy, permission-revocation behavior,
or background/foreground camera stability. A separate **3/3** MainActivity input
selection passes in 22.559 s: cancelled injected gallery result, synthetic blank
image import/manual review, and virtual shutter → queue/save under the existing
2/4-second bounds. The OS picker itself is not automated and no user photo is read.
The app remains signed out/offline and no collection item or upload is created.
Evidence: `gate7-root-scanner-input.log`.

### Manual-analysis generation correction (local checkpoint `e5c396d`)

The production `ScannerScreen` manual-analysis effect previously caught cancellation
as an ordinary resolver failure and launched optional artwork in the whole-screen
scope. Late A results and `finally` writes could then overwrite a newer B state or
queue review. The extracted actual effect now keys the complete manual context and
checks both live UI fields and its active effect job before publishing. Artwork is
a structured child of that effect. Ordinary resolver I/O failure still exposes
manual review; recognition thresholds, images, transport, auto-add and uploads are
unchanged. ACCEPTED is still applied immediately, not held for optional artwork.
Its existing `analysis=null` intentionally ends only that old optional preview.

The new current null/queue-review generation resets only the two progress flags;
the current invalid-frame branch also clears old artwork progress. An obsolete
generation cannot clear a newer generation's progress. This is a UI ownership fix,
not a claim of physical recognition accuracy or immediate network cancellation.

Test-first evidence was retained under `artifacts/release27-engineering-20261001/`:
- `manual-analysis-red-instrumentation.log`: **7 FAIL / 2 PASS** before guards.
- `manual-analysis-live-context-red-instrumentation.log`: one additional **FAIL**
  when the queue context changes synchronously before Compose can restart an effect.
- `manual-analysis-green10-instrumentation.log`: **10/10 PASS**, 28.681 s.
- `manual-analysis-invalid-frame-red-instrumentation.log`: **1 FAIL / 11 PASS**;
  only the newly demonstrated invalid-frame progress flag remains stale. The
  original assertions remain, with genuine recompose-tick and observed A→B→A cases.
- `manual-analysis-final-green12-instrumentation.log`: **12/12 PASS**, 29.403 s,
  after exactly one further effect callback clears that flag; tests unchanged.
  RED source snapshots are retained under `manual-analysis/RED-source-*`.

The tests use the actual orchestration with controlled synthetic callbacks and
2×2 bitmap presence, not live OCR/network/camera or a full manual-correction UI E2E.
They establish observed-generation and synchronous-current-context safety, not an
unobservable A→B→A transition entirely inside one callback.

Final offline Gradle check passes in **3m44s**, 104 tasks (22 executed / 82 up-to-date):
debug APK, instrumentation APK, **824 JVM PASS / 1 existing opt-in SKIP / 0 FAIL**
(825 tests / 120 classes), debug/release lint and Release Kotlin compilation.
Log: `manual-analysis-final-regression-build.log`. No R8 release artifact or signing
was performed. Tested debug APK SHA-256:
`6B8D5B8D0C67B561EBB5BB69952C111A769C2FCF104FBA1B4241DC9C64564DB0`;
test APK `B29EB1859B1381ADFDC124129D1C464C3D383A0D89FE354A0A7CF461098A3BE7`.
The separate broader run on these same installed APKs passes **327/327 tests** in
**274.389 s**: Room/migrations/outbox, owner isolation, selected and variant prices,
private/shared binders, backup/account boundaries, camera lifecycle and fast input.
Together with the separate 12 manual-analysis cases this is **339 distinct emulator
tests**, not a complete physical-device or cloud integration test. Evidence:
`manual-analysis-final-regression327-instrumentation.log`. These results cover the
`e5c396d` source checkpoint, not subsequent transport or incremental-sync work.
The emulator was restarted with the already proven host-GPU configuration for UI
regression, offline and without snapshots; it still uses only an emulated camera.
No physical Xiaomi operation, provider request, production change or push occurred.

### Catalog transport cancellation and combined local checkpoint

Real JVM loopback tests reproduced two old blocking catalog requests that remained
alive after cancellation (stalled response headers and a partial JSON string).
A structured URLConnection wrapper fixed only the headers case; its body reader
still failed the original two-second observation gate. The final implementation
uses the already resolved OkHttp 4.12.0, with no dependency update. Cancellation
closes the Call on IO and joins actual reader/response cleanup before releasing
cache/single-flight ownership. Lazy TLS/client initialization stays off UI creation.
Existing sourceOverride constructors, request headers, timeouts and DE/EN behavior
are preserved. Details and explicit platform/TLS limits: `docs/scanner-architecture.md`.

Root focused regression: **22/22 PASS** in five classes (12 new transport/ownership/
contract cases plus 10 existing cache cases). The unchanged real-loopback failure
gates now pass; this is not a general scanner speed or physical-device accuracy claim.
Full offline Gradle regression passes in **3m48s**, 104 tasks (19 executed / 85 up-to-date):
debug/test APKs, **836 JVM PASS / 1 existing opt-in SKIP / 0 FAIL / 0 ERROR**
(837 cases, 123 classes), debug/release lint and Release Kotlin compilation.
Evidence: `catalog-transport-final-regression-build.log` and archived full XML under
`artifacts/release27-engineering-20261001/catalog-transport/full-unit-results/`.

The same installed APKs pass **371/371** offline API-36 emulator tests in **318.095 s**,
including the earlier 327-case selection, all 12 manual-analysis cases and 32 actual
delta-to-Room adapter cases. No overlap is counted twice. Evidence:
`catalog-transport-final-regression371-instrumentation.log`. Debug APK SHA-256:
`EDDB661FC830CA4B8188D2EB787BA0169B5A120A3EF897DB712329D67898A8A2`;
instrumentation APK `3B3DE487F96710451B7E3FC19016D392084BC45F34260144C3DD1DB0B3571796`.
This checkpoint excludes the subsequent reference-artwork HTTP lifetime investigation.
Only emulator-5554 was installed. Xiaomi, production, release signing and historical
release-26 artifacts remain untouched; the entire release is not declared ready.

### Gate 8: unchanged-input replay with additive identity observations

The debug/test APK checkpoint `b8cb4ef` was replayed on the original API-36 AVD
with `-gpu swiftshader`, 4 GiB, no snapshot, airplane mode on and Wi-Fi off. The
same six cached public fixtures, five transforms and two repeats produce **60/60**
observations in **583.094 s**. No corpus download or physical device was involved.
The fixture manifest SHA-256 is
`833af2352737baf2141afd42d70e1ef62faf31038128f3033fa758b3d6de2ed4`.

The strict comparator confirms 60 matching keys, 60 matching input-image hashes
and **zero changes in the nine historical recognition output fields**. Results
remain 26 ACCEPTED / 10 AMBIGUOUS / 24 REJECTED, 36 correct local/full numbers,
zero analyzer failures and zero accepted wrong card IDs in the finite catalogue.
The new identity oracle observes all 26 accepted catalogue identities consistently;
two incorrect OCR numbers are rejected, not counted as recognition successes.
Printed-set-code truth and physical printing accuracy remain NOT TESTED. Language
is catalogue metadata, not a verified photographed-language classifier. The old
report has no corresponding identity fields or manifest hash, so those new fields
are not retroactively claimed as two-sided baseline observations.

| Limited debug observation | Gate-0 baseline | Current checkpoint |
|---|---:|---:|
| Overall median analysis | 13,690.748 ms | 14,130.560 ms |
| P95 (nearest rank) | 16,118.944 ms | 15,850.321 ms |
| Worst analysis | 16,622.423 ms | 16,480.944 ms |
| Median process CPU (all threads) | 47,491.5 ms | 47,549.5 ms |

This is **not an overall speed improvement**: the median is about 3.2% higher,
while P95 is about 1.7% lower. One sequential emulator pair cannot establish a
causal device regression or gain. No concurrent heavy Gradle/Web build was run
during these measurements; light source review and focused small tests continued.
No OCR threshold, analysis pixel size or recognition policy was weakened to pass.

The separate generated geometry replay passes in **21.995 s**: 21 cases × 12
measured repetitions, matching pixel/luma inputs and **42/42 identical detector/
normalizer output hashes**. Original reused-detector observations are preserved
where recorded; the comparator does not invent missing baseline data.
Evidence under `artifacts/release27-engineering-20261001/`:
`gate8-identity-ocr-instrumentation.log`, `gate8-identity-current.json`,
`gate8-current-geometry-instrumentation.log`, `gate8-current-geometry.json`,
`gate8-current-comparison.json`. These results precede the separate manual-analysis
session regression investigation and must not be attributed to its later fix.

The current Web source also builds in a fresh isolated temp copy in **22.867 s**
(298 copied files, synthetic demo configuration, Node network guard, no deployment).
The separate legal-source consistency check passes. Evidence: Web baseline-harness
`build-gate13-current.log` and `build-gate13-current-result.json`. The runner's
`originalConfigUnmodified` flag is a declared harness property, not a complete
before/after hash verification; an attempted full root-dist hash listing was
truncated and is deliberately not treated as an integrity proof.

### Knowledge aggregate and real local privacy services

Web `4cf0c52` validates every aggregate count as a nonnegative safe integer, uses
own-key access and prevents inherited-object keys from corrupting counts. The
installed Firestore serializer drops a literal `__proto__` map key; that input is
explicitly rejected instead of pretending a null-prototype map alone preserves it.
Malformed old counts fail closed rather than being guessed or silently reset.
Root rerun of the actual-handler/optimistic-conflict harness passes **61/61**,
including 10/40/80 synthetic writers. This is not production Firestore contention,
and the documented retry/contention limits remain. See Web
`docs/RELEASE_27_KNOWLEDGE_CONCURRENCY.md`.

The existing private-operations suite was independently run against real **local**
Auth/Firestore/Storage emulators: **27/27**, 41.76 s Vitest / 76.94 s runner. It uses
only `demo-domidex-privacy`, synthetic accounts and loopback services. Seven share,
twelve image/access and eight deletion cases pass. Provider retention/soft-delete
behavior and clock progression are fixtures, not evidence of real cloud purging
or a full Android/Web account-deletion user journey. All six reserved ports were
free after shutdown. Evidence: Web
`outputs/release27-engineering-20261001/security-gate10-deletion/run-20261001-054852-250c84e7/`.
The ignored original runner is machine-specific. Its independently reviewed
replacement is now versioned in Web `tests/private-operations-local/` (`af61aaa`).
Root reran **13/13 safety tests** and **27/27 real local service tests**, 26.35 s
Vitest / 40.89 s runner. Evidence:
Web `outputs/private-operations-local/2026-10-01T04-02-26-501Z-4a2dea50/manifest.json`.
It verifies already installed CLI 15.28.2 and JAR versions/sizes/SHA-256, creates an
allowlisted child environment and fresh credential/config directories, binds only
loopback, refuses occupied ports, and never downloads or kills foreign processes.
All six ports are free after shutdown. The manifest's input-hash check covers the
explicitly listed rules/tests/harness, not every transitive backend product module.
The new Node guard also handles already-normalized Socket arguments; historical
guarded runs must not be advertised as having identical network-isolation coverage.
Neither guard is an OS/Java firewall. Deprecation/metadata-lookup warnings remain
visible in the logs and are not disguised as a warning-free run.

### Gate 6 cross-platform price parity checkpoint

The Web share path now uses the existing exact supplement/anniversary enrichment
and an explicitly saved alternative metric only if a valid trend is absent. It
preserves price/total visibility consent, owner/entry/printing/product identity and
the final authorization/revocation check. Sidecars are request-local, read only
for needed entries in batches of at most 100, never put in the public card cache.
The two public `raw`/`prices` modes share one bounded cache budget but intentionally
need separate first fetches. This is correctness work, not a claim of fewer reads.

Android commit `b7d6f7e` adds six test-only wire regressions: optional selection
basis, exact and exceeded amount ceilings, omitted overflowing totals and invalid
total/count combinations. **16/16 SharedBinderClientTest cases pass** in 0.585 s;
the existing production Android parser is unchanged. This synthetic contract check
is not an end-to-end Android/Web account test. Evidence:
`manual-analysis-red-build.log` and the corresponding Gradle XML.

The independent root Web regression passes **1,104/1,104 tests in 68 files**,
24.72 s; `tests/release-local/selection` includes both new parity files. Evidence:
Web `outputs/release-local/2026-10-01T04-22-32-835Z-21860.{json,log}`.
Focused parity coverage is 103/103 (overlapping, not additive), and the public
cache harness passes 69/69 including 20 cache cases. Fresh real local service
tests pass **27/27**, with **13/13 harness safety tests** and all six ports free
after shutdown. Sources listed by the manifest remain unchanged. Evidence:
Web `outputs/private-operations-local/2026-10-01T04-28-36-922Z-a113a180/manifest.json`
and `outputs/public-catalog-cache/2026-10-01T04-29-26-723Z-10596.log`.

The repeated concurrency harness passes **12/12**, retaining the earlier **10 PASS /
1 FAIL** from an outdated mixed-mode expectation. The deliberate mode partition
needs 18 cold fetches for two different modes, versus nine for two owners using
the same mode; both have zero additional warm fetches. Private reads and Auth/DTO
checks remain in both cycles. Ten other scenarios are unchanged. Evidence: Web
`outputs/sharing-concurrency/2026-10-01T04-31-30-334Z-2832.{log,metrics.json}` and
its versioned README. Those counts are synthetic, not cloud billing observations.

Normal Web lint and TypeScript checks pass after nine test-harness-only findings
were corrected without suppressions. Cost-model 6/6, sharing-scale 9/9 and clean
standalone-package import 1/1 also pass. Logs: Web baseline
`gate6-harness-{cost,scale,package,lint,typecheck}-final.log`.

An isolated Web build passes in **14.944 s**, using 299 copied source files,
synthetic demo configuration and the existing Node network guard. Legal-source
checks pass. A compact before/after SHA-256 verifies the root `dist` tree and nine
listed build/deployment metadata files unchanged: 3,267 files / 70,962,371 bytes,
digest `fbc3b489d56287d0dd84b2a4bedcbf0b17113621f5d3e74872fcc76e95fdf95f`.
This excludes `node_modules` and private env files, and is not a deployment check.
Evidence: Web baseline `build-gate6-final.log`, `build-gate6-final-result.json`
and `build-gate6-final-integrity.json`. Runtime timings with concurrent local work
are not performance comparisons. No production settings, release signing or device
installations outside the isolated emulator were performed.

## Gate 2 local validation

Web/backend adds three UID+policy budgets, current Bearer revalidation for role
changes, exact owned quota deletion, in-transaction deletion fencing, bounded JSON
streams for six Admin/Lab actions, a scoped OpenAPI contract and a clean package-
import smoke test. No rules were weakened and no production configuration changed.

Final isolated suite: 690 primary/new tests + 76 extra + 7 Node = 773 unique tests.
Separately 30 Auth/Firestore/Storage emulator tests pass, including two independent
Firestore clients competing for the same quota. Typecheck/lint pass. Clean isolated
Web build: 295 source files, 37.125 s, not a deployable production artifact.
An initial CPU-contended run had two existing 1-second UI wait failures (688/690);
the unchanged full serial rerun passed 690/690 in 16.61 s. No assertion or timeout
was loosened. Public ingress, Cloud Run behavior, live TTL and old sharing-overlay
packer deployment remain unverified; the old packer is explicitly not approved.

## Gate 3 local validation

Android/AppCheck default is off, backend default is off with monitor-only opt-in;
no Firebase/Play Console or production setting was changed. Provider registration,
live Play/debug attestation and enabled SDK performance still need a separate
activation decision. See `docs/RELEASE_27_APP_CHECK.md` for the exact contract.

106 selected Android Auth/sharing/sync/AppCheck JVM tests pass (15 new), and 31
offline-emulator tests pass (2 new startup tests, 18 collection ACK/failure cases,
11 existing sharing/work tests). Debug/test APK builds, release Kotlin and fresh
release manifest, debug/release lint pass in 4m 53s. No release signing or AAB build.
The unmodified signed-v26 hash was rechecked. Web commit `d58c917` adds 52 new
AppCheck tests within 825 unique tests, typecheck/lint, package-import and isolated
Web-build success. No real attestation is inferred from these local test doubles.

Remaining scanner-knowledge baseline regressions are still recorded for Gate 9;
these selected passing suites are not a claim that every gate or full suite is green.

## Gate 4 local validation (in progress)

Latest local checkpoint `30669e2` adds an **unwired** adapter between the prepared
delta protocol/store and the existing four-type business apply function. It keeps
payloads, generated outbox mutations, tombstone receipts, deferred markers and the
checkpoint in one Room transaction. The new 32 cases pass in 1.836 s; the entire
`data.sync` instrumentation package passes **141/141 in 5.348 s**, including those
32 cases. Debug/test APK build passes in 38 s. Exact logs, hashes, rollback/reopen
coverage and outstanding SDK/fence/fairness requirements are in `docs/SYNC.md`.
This is not a Firestore worker activation, a network-read reduction or a physical
multi-device result. The following paragraphs retain earlier checkpoint evidence.

Committed ACK/failure changes preserve successor mutations and terminal decisions;
late responses cannot recreate a pending row without an outbox or overwrite a newer
local mutation. The private delta protocol and Room metadata transaction adapter
are now prepared, **not yet connected to the production pull worker**. Existing
bounded full pulls remain active. Schema 13, remote paths and security rules did
not change. The prepared auth-session guard is not proof of live Firebase testing.

Debug + instrumentation APK build, release Kotlin compilation and 116 selected
sync/sharing JVM tests pass. The offline API-36 emulator's final combined run passes
57 tests in 15.123 s: 19 actual Room checkpoint/defer/rollback/CAS cases, 7 indexed
placement-position cases (including scale), 18 collection ACK cases, and 13 existing
sharing/work/startup cases. These are helper/Room tests, not worker/Firebase E2E.

The active placement improvement first checks the existing owner/binder/slot index
with `EXISTS`; an unoccupied incoming slot no longer materializes every placement.
Occupied slots retain the existing first-free policy and still read all active rows.
Synthetic three-sample medians, milliseconds, for **one local lookup**:

| Existing placements | Previous free-slot strategy | Indexed free-slot lookup | Collision fallback |
|---|---:|---:|---:|
| 1,000 | 11.285 | 2.385 | 14.922 |
| 5,000 | 28.472 | 2.579 | 38.793 |
| 20,000 | 118.817 | 2.063 | 131.272 |

The baseline matches the prior materialization plus `any` check, not an extra hash
allocation for an already-free slot. Placement field hashes and foreign keys are
unchanged, and `EXPLAIN QUERY PLAN` confirms the existing slot index. The collision
path deliberately includes both the index probe and old fallback; no claim of a
collision-speedup, network-read saving, whole-sync speedup or UI latency SLA follows.

Evidence: `gate4-pull-foundation-build.log`,
`gate4-pull-foundation-instrumentation-final.log`, and
`gate4-slot-measurement-final.log` under the ignored Android evidence directory.
Only PID 21379 / 02:10 UTC lines in the measurement log are the final comparison;
the earlier PID 21257 baseline was superseded. Remaining delta integration risks
and acceptance tests are listed explicitly in `docs/SYNC.md`.

### Active session and remote-apply follow-up

The prepared auth lease is now connected to the existing private worker, without
enabling the delta protocol. A shared remote-apply path protects pending mutations,
terminal parents and confirmed deletion revisions across full pulls and push-conflict
responses. Collection dependency reads reject cached or pending-write overlays.
See `docs/SYNC.md` for the exact boundaries and retained legacy fallbacks.

Final local run: 78/78 offline-emulator tests in 3.054 s; 154/154 selected JVM tests;
debug/test APK builds and release Kotlin compilation pass (43 s combined build).
The original test command contained two incorrect test-class names; this was a
runner configuration failure, followed by the corrected passing run. No live
account switch, Firestore E2E, network-saving or full Gate 4 completion is claimed.

### Bounded full-pull follow-up

The existing full scan now shares one 1,000-unit logical read budget across query
results, empty queries and dependent document reads. Repeated missing parents are
looked up once per invocation. An unread parent at budget exhaustion does not
advance the current document's bookmark; already-attempted missing parents do not
cause an immediate orphan loop. A process-local owner-specific non-waiting gate
prevents two full pulls from racing the same bookmark. Busy/capacity preserves the
request with WorkManager retry, while pushes and priority sharing stay outside.

The production loop is directly tested through a synthetic port. Forty-four focused
tests pass, including two complete cycles at each of 1k/5k/20k rows, exact bookmark
identities, <=200 rows/query, <=1,000 logical units/run, interruptions, cancellation,
missing parents and phase boundaries. Two tests of an obsolete unused cursor helper
were replaced, not counted as worker coverage. The first focused run had a corrected
JUnit void-signature error; no executed pre-fix performance/functional RED is claimed.

Final combined check: 189/189 selected JVM tests, 78/78 offline-emulator regressions
(3.025 s), debug/test APK builds and release Kotlin compilation (42 s) pass. Evidence:
`gate4-full-pull-final-build.log`, `gate4-full-pull-final-instrumentation.log` and
`scanner-baseline/full-pull-green/`. This proves local control flow and Room helpers,
not multi-device Firebase convergence, billed-read counts or end-to-end sync latency.

## Gate 5 Android import safety (partial, local validation)

The existing atomic Cardmarket import now serializes overlapping UI/worker imports,
owns both temporary files before parallel downloads, and cleans both after failure
or cancellation. Each decoded stream is bounded to 128 MiB; this is a technical
safety limit, not a claim about the provider's published maximum. Truncated or
oversized downloads, an empty product/guide join, or a missing/invalid guide source
timestamp cannot replace the last good Room snapshot. A valid source timestamp is
never substituted with the device's current time. Source URLs, gzip behavior,
timeouts, Room schema, release configuration and provider rights are unchanged.

Thirty new helper tests and three existing parser tests pass. They exercise stream
limits (including expanded gzip), cleanup, cancellation, serialization and import
preflight; they are not a live provider download or an actual Room import E2E test.
The combined build also passes 154 selected sync/sharing JVM tests, debug/test APK
builds and release Kotlin compilation. Evidence: `gate5-cardmarket-import-*` and
`gate4-confirmed-dependency-build.log` in the ignored engineering directory.

Full all-set price coverage, shared dataset versions and a rights-approved central
delivery strategy remain open Gate 5 work. No production import was triggered.

### Web price reuse and sharing consistency follow-up

Web commit `b3a5d43` preserves unchanged collection price results only within the
original public-card cache deadline and current owner/listener/retry generation.
An 18-card quantity edit now leaves resolver calls at 18 rather than 36. A 601-card
edit no longer triggers 601 additional resolver/loader/fetch calls through the
600-card FIFO cache. These are local invocation counts, not wire-byte or billed-read
measurements. Fifty new tests cover every price input, true TTL, pending reuse,
account/regrant/old-callback/unmount boundaries; cache size/TTL and price policy stay
unchanged. Root rerun passes 962/962 Web tests in 60 files plus typecheck and lint.

Web commit `75bf30e` rechecks the owned binder after catalog resolution, in addition
to the existing final share/owner checks. Four reproduced races now return 404 when
the binder is deleted, removed or has mismatched ownership/identity. This costs an
additional document read and is not a performance optimization. Root's overlapping
sharing selection passes 87/87 tests in nine files and 4/4 separate archived-deletion
compatibility tests. No production read, deployment, schema, private sidecar exposure
or change to share options. See the Web handoff for detailed evidence and remaining
pagination/price-parity constraints.

### Price-history retention audit (read-only, 2026-10-01)

`raw_price_snapshots` is a global local catalog cache, unique by product/source/source
time. Reimporting the same source time uses REPLACE: it does not add rows, but still
writes and can invalidate Room observers. New source times add rows for every joined
catalog product, not just owned cards. There is no general runtime retention policy.
The 24-hour schedule, startup staleness check and manual refresh do not guarantee
exactly one successful import or new source timestamp per day.

Portfolio history is owner/identity filtered, but currently materializes all 16 raw
fields for all retained source dates of those products. Binder summaries already
avoid this history; private binder contents still load it for per-card change percent.
MAX history and the earliest positive normal/reverse trend are current UI contracts.
A blind 365-day cutoff would change them and is therefore **not implemented**.

One-year **simulation only**, assuming a constant catalog, one new source timestamp
per day, 365 successful days and no deletion:

| Joined products | Retained rows | REPLACE attempts with four imports of each daily snapshot |
|---|---:|---:|
| 1,000 | 365,000 | 1,460,000 |
| 10,000 | 3,650,000 | 14,600,000 |
| 50,000 | 18,250,000 | 73,000,000 |

Formula: products × distinct source timestamps; repeated imports of the same daily
timestamp increase writes, not rows. Four distinct source timestamps daily would
also quadruple rows. An owner with 200/2,000 distinct products could materialize
73,000/730,000 history objects in this scenario. Duplicate owned copies do not
duplicate product history. These counts do not establish MB, elapsed time or actual
production use. Existing 1k/5k/20k scale fixtures have only two source dates; they
are not a year-long history benchmark.

The smallest follow-up is now implemented: a four-field owner-safe trend projection,
preserving all dates, values, MAX and identity semantics. It selects card ID, source
time, normal trend and reverse trend instead of all 16 raw fields. Source/owner/
tombstone/quarantine filters remain in the same SQL; no schema, retention, import,
network or price policy changes. Row growth and download size are not reduced.

The real Room baseline first passed the full 365-date value test and failed the
new projection contract (3 PASS / 1 FAIL). After the change, 24/24 targeted emulator
tests pass, including 17,520 owned history rows, null/zero/negative prices, reverse
prices, same-date corrections, an unowned extra date, private binder detail equality
and owner A -> B -> A. Debug/test APKs, release Kotlin and all JVM tests pass (794
PASS / 1 existing opt-in SKIP). Independent review found no new blocker.

One illustrative emulator sample changed from 705.55 ms to 189.94 ms for the annual
portfolio flow; this is **not** a repeat-sampled benchmark or a device performance
guarantee. The verified deterministic improvement is 16 -> 4 selected columns per
history row. Evidence: `gate5-history-projection-{red,green}-*` in the ignored
engineering folder. Retention/compaction remains separate with explicit semantics.

## Gate 9 client knowledge safety (local validation)

Community knowledge no longer modifies confidence, candidate scores or evidence
objects. It may reorder already plausible, contradiction-free candidates only when
both the original and resulting decision are AMBIGUOUS. Independent ACCEPTED and
REJECTED results remain untouched. Freshness is based on a valid supported version,
exact fingerprint and actual source timestamp less than seven days old, not time of
cache access. Missing, future, disputed or incompatible hints are ignored.

Only confirmed server snapshots may replace/remove the exact local hint. Confirmed
missing or malformed data deletes its AtomicFile base/backup/new files, not consent,
evidence outbox, upload receipts or collection data. Cached/pending snapshots are not
deletion proof. Account-generation events invalidate running hints and late callbacks.
Transport errors retain a fresh offline hint while stopping network/scope resources;
only its auth watcher remains until normal scanner-finally cleanup. Permission/auth
errors fail closed instead. This explicitly avoids weakening offline operation to
simplify cleanup. Camera, ML Kit, thresholds, ReviewedScanKnowledge and upload paths
are unchanged.

The initial 15 ranking/freshness regression cases reproduced 10 failures. Final focused
JVM selection: 48/48 PASS. Independent review found the first adapter error cleanup
would immediately erase the intended offline fallback: two additional tests reproduced
that and now pass, including auth changes after transport termination. Three SDK-error
constructor tests initially failed under plain android.jar stubs (TextUtils/SparseArray);
the unchanged assertions were moved to real Android instrumentation, not disabled.

Final root run: **795 JVM tests, 794 PASS / 1 SKIP / 0 FAIL / 0 ERROR**, across 117
classes. The skip is the existing explicitly opt-in `CardmarketLiveSmokeTest`, not a
newly suppressed regression. Debug/test APK builds, release Kotlin compilation and
debug/release lint pass in 3m 40s. Lint reports zero errors, 169/170 warnings respectively
and two hints each; this is not a warning-free claim. The final offline API-36 emulator
run passes **89/89 tests in 5.274 s**, including all 11 knowledge store/SDK-policy tests
and 78 existing sync/sharing/startup regressions. Only emulator-5554 was used, with
airplane mode on and Wi-Fi off, no user account or production data.

Evidence: `gate9-root-regression-build.log`, copied XML in `gate9-root-jvm/`, and
`gate9-root-instrumentation.log` under the ignored engineering directory. Focused
RED/GREEN and the SDK-harness failure are retained separately. No real Firebase
listener/auth E2E, physical-device benchmark or scanner accuracy/speed improvement is
inferred from these tests. No release signing, AAB, deployment or Xiaomi action.
The historical signed-v26 AAB SHA-256 remains the immutable value listed above.

## Gate 4 two-client price-selection matrix (local, 2026-10-01)

The real local Firestore SDK/rules matrix now includes 21 price-selection cases,
alongside four existing two-client sync cases. Root independently reran **25/25
PASS** in 17.41 s of Vitest / 25.8 s total. Both clients use synthetic claims and
`demo-domidex-sync`; no user account or production service is used. Android, Web
and copied harness rules retain SHA-256
`53B815173F59727368F85C1E1861108C0C246FE262F5FF6FBAA848000F930ACE`.

The matrix covers all seven normal/reverse metrics and source fields, legacy
quantity edits, clear/change races, queued stale writes, revoked access, disabled
accounts, deletion locks, owner isolation, parent identity/tombstones, and confirmed
snapshots. Expected PERMISSION_DENIED messages are successful negative assertions,
not ignored errors. In a same-revision transaction race, a rules rejection may occur
before an SDK precondition retry; the caller must not assume automatic convergence.

`disableNetwork()` alone did not interrupt direct transaction RPCs in the installed
Node SDK. A separate loopback TCP proxy therefore destroys its own connections and
rejects new ones. The transaction fails, the second client confirms unchanged server
state, reconnect does not replay it, and a fresh explicit transaction succeeds.
The test accepts CANCELLED/UNAVAILABLE, not a false acknowledgement. Earlier failed
test assumptions and their logs are retained. This is real local transport/rules
coverage, **not** an Android Worker/Room or Web UI end-to-end convergence claim.

The isolated runner uses cached tools, temporary empty credential directories and
a Node loopback-only network guard. That guard is not an OS/Java firewall. All five
reserved local ports were unoccupied before and closed after the run. Evidence:
`artifacts/release27-engineering-20261001/security-gate4-two-client/run-20261001-051139-b2f2af98/`.
The newly identified Android silent remote-wins policy and Web partial-add retry
are separate product fixes under validation, not closed by this matrix.

The durable Web `test:sync-emulator` command now includes both suites in an explicit
serial configuration with environment-file loading disabled and 30-second test
timeouts. The transport fixture derives the upstream from the validated local
emulator address and allocates its own ephemeral loopback port; it does not require
the engineering harness's machine-specific port. Root rerun after this portability
change: **25/25 PASS**, 22.96 s / 30.34 s total, evidence sibling directory
`run-20261001-051340-2a0bd236`. No emulator configuration or product rules changed.

## Gate 6 bounded public catalog cache (local, 2026-10-01)

Shared-binder resolution now reuses public catalog metadata for at most 60 seconds
from insertion, with no renewal on access. The process-local cache is bounded to
8,192 entries / 32 MiB serialized bytes / 128 KiB per entry. It contains no UID,
token, binder, collection entry, private DTO or authorization result. Exact public
card IDs and the fixed language/resolver version form keys. Every consumer receives
a fresh copy. Declared identity contradictions and malformed prices are preserved
uncached rather than normalized into apparently valid data.

Up to 64 in-flight catalog jobs are deduplicated; overflow performs uncached work,
so this is **not** a global concurrency limit. One consumer's cancellation cannot
cancel another; the final cancellation or 12-second job deadline aborts its provider
request. Late completion cannot populate a replacement job's cache. Errors/missing
cards are not cached. Existing private checks, including final revocation/deletion
checks, still run for every response. Price source timestamps do not change.

Root independently reran **64/64** tests in six files (3.58 s), including cache
boundaries, copies, abort races, real handler entry points and packaging. In a local
one-entry fixture, cold/warm catalog fetches are 1/0; private logical reads remain
9/9. This is not a Cloud Run, provider-latency or billing measurement. The cache does
not fix O(N) placement reads, private-price parity, provider response-byte limits,
or the missing anonymous public-read limiter. Evidence: Web
`outputs/public-catalog-cache/2026-10-01T03-09-19-895Z-21304.log`.

The separate Web `docs/RELEASE_27_PUBLIC_READ_LIMITS.md` records the unresolved
trusted-ingress boundary, guessed-token read cost, GET/HEAD behavior and polling
amplification. `VINEXT_TRUST_PROXY` in the inspected version verifies protocol/host,
not client-IP provenance. No speculative header-based limiter or new quota storage
was added; external ingress/bypass/logging evidence is needed before enforcement.

No production deployment, signing, AAB, Xiaomi action or live account test was
performed for either follow-up.

### Price-choice preservation and final local regression checkpoint

The Android PRICE_SELECTION remote-wins case now preserves its local payload,
source date, base revision and exact outbox mutation as an explicit conflict.
The existing Settings local/cloud decision handles it; ordinary pull cannot discard
the pending choice. Rebase and cloud replacement are transactional and validate
owner/auth generation, parent state, exact printing and source fields. A confirmed
remote clear may acknowledge a deleted parent without resurrecting it. Other private
record kinds retain their existing policy; Room schema and rules are unchanged.
See `docs/SYNC.md` for the exact limits and callback integration.

Web search and missing-binder-card add flows distinguish a successful card write
from a failed optional price-choice write. Retry now writes only the original price
choice, not the acknowledged card quantity. Context/account changes require a fresh
selection and cannot receive the old action's completion. Its pending state is
UI-local, not a new persistent offline outbox. The real-component RED reproduced
double-add behavior before the fix.

Independent root checkpoint: **801 Android JVM tests: 800 PASS / 1 existing opt-in
SKIP / 0 FAIL / 0 ERROR**, 118 classes. Debug/test APKs, release Kotlin, debug/release
lint pass; combined Gradle run 3m 16s. The combined offline API-36 emulator selection
passes **151/151 in 19.886 s**, covering the new price conflicts, price-history
projection, above-main-set prices, Room/ACK/pull, sharing and knowledge regressions.
Only emulator-5554 was installed, airplane mode 1 / Wi-Fi 0; Xiaomi untouched.
Evidence: `gate4-price-conflict-root-{regression,instrumentation}.log`.

Independent root Web checkpoint: **1,000/1,000 in 62 files**, 32.52 s, no external
network or environment-file loading. React-act/Canvas harness warnings remain;
this does not establish visual rendering, real authentication or live convergence.
No release signing, R8 execution, AAB or deployment was performed. The older
Collection cloud-resolution path has a separate account-generation review follow-up;
the above results do not declare that follow-up or the entire release complete.

### Shared-view refresh and local concurrency checkpoint

The Web large-card dialog now retains only card identity and derives display fields
from the newest permitted binder DTO. A successful refresh that hides prices or
removes/replaces a card no longer leaves the old private content in the open dialog.
The existing 15-second/visibility refresh contract remains; no instant-push claim.
Root's extended regression passes **1,013/1,013 in 63 files**, 22.05 s, including
13 new viewer cases with recorded RED evidence. Existing harness warnings remain.

The real sharing handler/cache was additionally exercised with 10/40/80 concurrent
synthetic readers, cold/warm cycles and three repetitions: **11/11 PASS**, 12.34 s.
The 55 measured batches contain 2,350 requests and 5,028,810 logical document reads.
Firestore/Auth/provider are doubles, not production load or billing measurements.
At 80 separate-page readers, 480 simultaneous fake provider calls expose that the
64-job deduplication bound is not a global upstream limiter. O(N) placement reads
remain. These are documented open scaling limits, not hidden by PASS assertions.
See Web `tests/sharing-concurrency/README.md` and local raw metrics under
`outputs/sharing-concurrency/2026-10-01T03-23-31-232Z-20888.*` for scope and numbers.

The independent Android review found a further pre-lease account-switch race in
price-conflict resolution; it is under correction together with the older two-phase
Collection cloud-resolution path. No whole-release readiness claim is made.

### Reproducible local Web regression and cost inventory

Web `npm run test:release-local` now reproduces the extended unit selection without
depending on ignored engineering reports. It derives only whitelisted unit paths
from the existing explicit package groups, adds four named suites, excludes emulator
tests, strips inherited credentials, disables environment-file loading and guards
Node/JSDOM network paths. It installs/builds/publishes nothing. The guards are not
an OS sandbox. Twelve launcher-selection/transport tests and focused lint pass;
the durable command independently passes **1,013/1,013**, 22.53 s. One earlier
Windows-preload-path failure occurred before test discovery and was fixed, not
counted as product RED. Details: Web `tests/release-local/README.md`.

Web `docs/RELEASE_27_OBSERVABILITY_COST_AUDIT.md` maps actual local log/status paths,
unknown production configuration and remaining privacy/retention risks. The pure
`tests/cost-model` calculation has **6/6** independently rerun tests and explicit
hypothetical Light/Average/Heavy inputs for 1k/5k/10k/50k users. It separates logical
operations/new-upload bytes from billed reads/current storage, and leaves currency,
compute and unknown production values null. No budget, monitoring backend or
production telemetry was activated; the inventory is not Gate-11 completion.
