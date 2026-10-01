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
| 2 API security | IN PROGRESS | distributed write limits and local Auth tested; stream bounds/review in progress; public IP/token limits belong to Gate 6 |
| 3 App Check | NOT TESTED | actual integration and monitor-first behavior, no blind enforcement |
| 4 Collection/binder sync | NOT TESTED | initial/delta/outbox/conflict/offline/restart/multi-client scale |
| 5 Pricing/catalog | NOT TESTED | exact-identity centralized fetch/cache/fallback/retention evidence |
| 6 Shared binder scale | NOT TESTED | 5k placements, pagination, final revoke check, caching |
| 7 Camera stability | NOT TESTED | synthetic/emulator regression; real hardware matrix separate |
| 8 Recognition | NOT TESTED | multisignal, variant-aware false accepts, reproducible benchmark |
| 9 Community knowledge | NOT TESTED | independent evidence, conflict, freshness and no hint-only acceptance |
| 10 Privacy/lifecycle | NOT TESTED | export/deletion/locks/storage/retention inventory |
| 11 Observability/cost | NOT TESTED | low-cardinality safe metrics; simulation labelled, alerts prepared |
| 12 Load/scale | NOT TESTED | 10/40/80 concurrency; 1k/5k/20k collections; no production load |
| 13 Full regression | NOT TESTED | existing features and cross-platform contracts |
| 14 Database migration | BASELINE PASS | current 12→13 on 1k/5k/20k with all old columns preserved; rerun if later schema changes |
| 15 Release engineering | BLOCKED by earlier gates | no release signing/build before critical gate evidence |
| RC final validation | NOT READY | complete technical report and final explicit recommendation |

## Findings to reproduce, not assumed fixed

- Backend has no common multi-instance request rate-limit implementation in inspected
  source. Existing size/concurrency limits are not request rate limits.
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
