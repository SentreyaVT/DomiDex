# Release 27 engineering ledger

This is a work-in-progress evidence ledger, not a release approval. Updated 2026-10-04.

### Release authorization and new storage prerequisite (2026-10-04)

The user authorized creating the signed R27 AAB and pushing the engineering
branch; Google Play upload remains explicitly excluded. Before any signing or
build started, a new user-authorized requirement arrived from the parallel
architecture audit: bounded local storage and a safe large-installation migration
must PASS before Gate15. The originating human instruction was read back and
verified. This newer prerequisite takes precedence over the earlier permission
to create an artifact. **No R27 AAB has been built or signed in this follow-up.**

The user subsequently chose **price history limited to 12 months**, instead of
retaining the complete history in a new cloud archive. This authorizes shortening
price history only, not deleting collections, private binders, layouts, photos,
price preferences, guest data or unacknowledged synchronization work. FULL sync
remains the R27 strategy; Delta remains disabled.

Read-only verification confirms the October1 retention finding: every distinct
Cardmarket guide timestamp appends global product history, with no runtime
retention. The previous portfolio projection fixes read costs, not stored rows.
The current public Web endpoints serve current metadata/prices, not a persistent
historical-price restoration contract. A 12-month cutoff alone therefore neither
proves a fixed byte budget nor makes all Room rows reproducible from Firebase.

Safety constraints for the forthcoming implementation:

- Do not apply a global Room file/page cap or evict user tables. Gast/local-only
  state, PENDING/ERROR/CONFLICT mutations, tombstones and both outboxes may be the
  only current copy. Fresh cloud collection rows do not restore personal photo
  bytes or grading analyses; FULL completion is not an atomic server snapshot.
- Price pruning must preserve the latest source-qualified price, an exact
  pre-window state for portfolio carry-forward (including null/zero), and the
  independently defined Normal/Reverse percentage bases. Removing old rows or
  filtering delta events before computing the initial value can corrupt charts.
- An image cache is distinct from personal photos, scanner evidence and pending
  uploads. Only reconstructible cache files may be evicted.
- The 13-GB migration proof is not yet executed. Resumption, low free space,
  WAL/temporary growth, logical integrity and actual physical reclamation must
  be tested; a row DELETE alone is not proof that disk space was returned.

The storage gate and the previous critical gate failures remain OPEN. In
particular API37.1 still records 368/371 and the SDK lock-only case 17/18. API26
public2/edge13/count3/multi-window proofs already PASS; its annual aggregate13
run remains incomplete at the diagnostic-plan cap. The real TestScheduler retry
and bounded FULL continuation already PASS, but are not physical multi-device or
real OS scheduling evidence. No old failed artifact is relabelled as passing.

GitHub source backup and an AAB release are separate operations. A push of
`codex/release-27-hardening` is permitted without labelling it release-ready or
replacing `master`; no GitHub stable release or Play upload follows implicitly.


### German visual name search and targeted Redmi update (2026-10-03)

The user reported that Add card > Pokemon search returned English Cardmarket
product records instead of cards with German names and images. That UI branch
called the price-product catalogue; it now queries the German visual catalogue,
keeps exact card/set/local-number identity and opens the chosen card directly.
Results show image, German name, set and number, without price-product metadata.
Pagination, debouncing, stale-query cancellation, explicit error/retry and a
bounded 24-page/15-minute memory cache are covered. The physical-card filter uses
the provider's Pocket-series membership, not a guessed ID/number boundary.
Missing German artwork can use an existing image of the exact same card ID and
local number; that fallback never replaces the German name or supplies prices.
The selected card still uses the unchanged exact-printing Cardmarket resolver.
Scanner, Full-Sync, schemas, rules and production configuration were not changed.

Frozen debug build `green-185220-47fa643b` completed in 3m57s: **938 JVM cases,
937 PASS, 1 existing opt-in SKIP, 0 FAIL/ERROR**, including 12 new search cases.
Debug and test APK assembly, release Kotlin compilation and debug/release lint
passed. Release-26 AAB, mapping and symbol artifacts retained their exact hashes.
No new release AAB or release signing was performed.

The offline owned API36 emulator ran four new real Compose/Room search cases and
the four byte-unchanged BinderPricePreview cases: **8/8 PASS, 0 skips, 34.696s**.
Tests verify rendered image pixels, German names, direct selection, pagination,
secret numbers, stale results/retry and no namesake-price borrowing. Fixtures are
synthetic; this is not an exhaustive live-provider or all-card image guarantee.
`runtime-green8-496c75585aff4294a205498c02867802` preserves the raw evidence.
The runner's overall exit remains **1**, because its strict post-run idle guard
found cached test PID8454 (`cached=true`, process state18/CRE). Source/APK hashes,
installed hashes and offline/no-reverse checks passed. No test was weakened, no
result rewritten and no Android process was stopped to disguise this guard result.

At the user's request the ordinary app was updated in place on Redmi e30bd3ce,
model24115RA8EG, with APK SHA256
`21f117a80b791008abb2dddfd1a82b500395223b95c6e370e002fb935a6d18c7`.
Its normal debug certificate is unchanged (`61e8314b...`); installed-file readback
matches exactly. Version remains27/1.0-closed-alpha.6. Original first-install time
2026-10-01 07:55:34 is retained; last update2026-10-03 21:00:40. No uninstall or
data clear occurred. Launch returned Status ok/COLD, TotalTime2202ms/WaitTime2231ms;
PID8412 and foreground MainActivity were verified, with no AndroidRuntime error
in the scoped post-launch log. Network settings remain airplane0/Wi-Fi1/mobile1.
This is an installation/start check, not a live search or scanner hardware proof.
Evidence is under ignored `artifacts/release27-engineering-20261003/german-name-search-review/`.
The complete R27 gates were not rerun or declared closed; Gate15 remains locked.

### User-requested Redmi debug update and manual-test launch (2026-10-03)

The user explicitly requested installation and launch on the connected Redmi.
The existing ordinary APK `6f8cee6b...` was first pulled to the local ignored
`artifacts/release27-engineering-20261003/redmi-debug-install-20261003T1835/`
directory. Both it and current debug APK `2c9ae510...` verify with the same normal
Android debug certificate `61e8314b...`. In-place `-r -t` installation returned
Success; the installed file was independently hashed as exactly `2c9ae510...`.
Version remains27/1.0-closed-alpha.6; original first-install time remains
2026-10-01 07:55:34 and update time is2026-10-03 20:34:31. Existing45,236,224-byte
collection database and424,392-byte WAL retained their pre-launch sizes/timestamps.
No uninstall, app-data clear, private-data export or network-setting change occurred.

Launching `.MainActivity` returned Status ok, COLD, TotalTime1451ms/WaitTime1481ms;
PID4391 and the foreground MainActivity were subsequently verified. This is a
single launch observation, not scanner/performance regression proof or verification
of the user's signed-in state. The user can now test the ordinary app. The planned
separate33-case hardware container has not been installed or executed; automated
device tests must not interrupt this manual session. `observed-install.json`
records the scoped observation. No new build, release signing, Play upload,
backend/configuration mutation or release approval occurred. Gate15 stays locked.

### API26 actual compatibility results and newest-stable Android17 follow-up (2026-10-03)

The isolated API26 run now has actual, distinct results, all with unchanged
product/test APKs `2c9ae510...` / `54a1ac35...` and successful offline/idle/input
postchecks. Under `artifacts/release27-engineering-20261003/api26-compatibility-review/`:

- `runtime-api26-public2-756e24fdeb4e473cb0dfed46bb26c1d9`: PASS2/2,1.945s.
- `runtime-api26-edge13-4e3d1d08fb3b453ba4a5412687428023`: PASS1/1,12.109s,
  all13 original counterfactual states. The earlier `edge13-3e164b1c...` remains
  overall FAIL despite JUnit1/1: API26's `head` lacks `-c`. The separate export
  fix uses `cat` only after exact remote byte-count/SHA256 checks, with the same
  host byte cap and repeated metadata verification; no Android assertion changed.
- `runtime-api26-count3-4014b9f036744844985e0245c54071c8`: PASS1/1,61.605s,
  complete 20000-safe -> 20001-legacy-fallback -> 20000-safe transitions.
- `runtime-api26-windows1-2cda29c72bc149b19f14f8e39ae8c70c`: PASS1/1,69.067s,
  full structured proof, all three phases with100001 typed tuples, both independent
  writer commits before a real cursor refill, all15-table hash restoration and
  exact owned-fixture cleanup. This is actual API26/SQLite3.18.2 evidence, not
  physical-device, release/minified or performance-equivalence proof.
- `runtime-api26-aggregate13-9b999a5c63aa40c0b44883cac06c7609`: **FAIL remains**,
  101.706s. The real Android test hits `postFixExplain`'s64-row diagnostic cap on
  the aggregate SQL plan, not a reported model mismatch. The13 preceding samples
  and owner/model comparisons completed, but final hash/FK/sync assertions were
  not reached. A subsequent read-only, size/digest-verified export preserves the
  original FAIL report in `failed-aggregate-report-diagnostic/`: legacy plan5rows,
  aggregate plan incomplete, owned UUID database closed and files absent. This
  does not qualify as complete annual proof; no cap/assertion was relaxed.

The user separately approved the newest stable Android17 emulator. Google's
[stable-release overview](https://developer.android.com/topic/generic-system-image/releases)
identifies Android17QPR1 as stable and QPR2 as Beta;
[API mapping](https://developer.android.com/media/platform/xhe-aac-encoding)
identifies QPR1 as API37.1. Therefore the installed37.2-beta3 image is not used as
the newest-stable proof. Only `system-images;android-37.1;google_apis_ps16k;x86_64`
revision9 was additionally installed (76.438s), no new license acceptance, all
protected Release26/tool/existing-AVD hashes unchanged. Setup receipt:
`api37-compatibility-review/image-install/result.json`. New AVD
`DomiDex_R27_Compatibility_API37_1` uses port5560, emulated rear camera (no webcam),
2GiB RAM and no snapshots. Only the newly created, idle5558 API26 emulator was
shut down after its tests to free RAM; existing5554/5556 and physical phones were
not changed. Android17 runtime proof is pending; targetSdk37 is not that proof.

The actual new image reports stable `REL`, preview0, SDK37, `sdk_full=37.1`
and an empty `sdk_minor` (not an invented minor-property value), fingerprint
`google/sdk_gphone16k_x86_64/emu64xa16k:17/CP31.260623.012/16064790:userdebug/dev-keys`.
Actual `getconf PAGE_SIZE` is16384; this is x86_64 emulator evidence, not physical
ARM64. The old Espresso3.5.1 dependency was unsuitable for the new target37
MessageQueue according to [Android's compatibility guidance](https://developer.android.com/about/versions/17/changes/messagequeue).
Only `espressoCore` in the test version catalog changed to3.7.0. Actual generated
AndroidTest dependency XML `9d085a72...` resolves core/idling3.7.0, Test core/runner1.7.0,
monitor1.8.0 and unchanged ext-junit1.1.5. No MessageQueue compatibility override
was disabled, and no runtime/release dependency was changed.
Build `api37-compatibility-review/green-180347-0553f724` passes3m53s,104 tasks,
20 executed84 UP-TO-DATE, both lints/release Kotlin; JVM is UP-TO-DATE, not a fresh
925-case claim. Product APK remains byte-identical `2c9ae510...`; only the test APK
changes to `9354bed5...`. All frozen sources/Release26 files are unchanged during
that build. First installation on only5560 passes exact certificate/installed-hash,
zero-account/offline/idle/no-reverse checks (`installed-debug.json`).
The separate Full371 runner has23 pure checks passing0.054s and independent peer
review. A caught selection-hash transcription typo was corrected against the
unchanged actual selection file, not by altering any test identity or assertion.
Final runner `72c36e11...`, observed profile `0682cb6f...`, boot
`1e1b10f1-1923-4dce-b45b-c1259a95999e`; live read-only preflight is CHECKED with all
postguards and frozen inputs intact. The actual unchanged371-case runtime
`runtime-api37-full371-8716343fa8164b57b65697517032f79d/` completed in280.558s:
**368/371 PASS,3 FAIL,0 skips; overall FAIL remains**. The three failures are:

- `PortraitScannerCameraInstrumentedTest#originalCameraJpegsRemainPortraitForEveryDisplayRotation`:
  the fresh emulator lacked the CAMERA runtime grant. The missing permission was
  subsequently granted only on owned5560 and verified; a complete rerun is pending.
- `ScannerSessionIsolationInstrumentedTest#cancelDuringLeasedNativeOcrReleasesInputsAndAllowsAnotherIdentity`:
  the second capture returned no number instead of098/197 after the earlier lease,
  cancellation and bitmap-cleanup assertions had passed.
- `ScannerSessionIsolationInstrumentedTest#sameAnalyzerReadsNewIdentityFromSecondSameSizedCapture`:
  already the first capture returned no number instead of094/084; name OCR returned
  Pikachu ex. This run does not establish a stale second-session cache defect.

Actual scanner logs report orientation score20 and
`orientation_evidence_insufficient` for both OCR failures. The conservative manual
fallback deliberately clears the number in that state. Timeout, font/image and
ML-Kit causes are not yet distinguished. No OCR deadline or identity assertion
was weakened. The original runner also failed its strict post-idle check because
cached test PID3608 remained; frozen inputs and offline state were intact. A bounded
read-only diagnostic preserves these observations in
`failed-full371-diagnostics.json`. Only the newly created idle5560 emulator was
then stopped to release host memory; no ordinary app was force-stopped or cleared.
Its two-core720x1600 profile differs from the four-core1080x2400 API36 regression
emulator, so these runs are not a controlled performance comparison.

The user subsequently supplied physical Redmi `24115RA8EG`, Android16/API36,
ARM64/4096-byte pages, serial `e30bd3ce`, and authorized necessary local test
installation/cleanup. Its ordinary debug R27 app currently hashes `6f8cee6b...`
and contains existing collection data. Initial inspection changed nothing. A
separate disposable offline-permission test package is being prepared for a small
hardware-compatible selection; emulator-only suites will not have their guards
bypassed. This is not yet a physical-device test result.

Local scanner recreation source checkpoint is `8b53083`. All results above are
local debug evidence. Full-Sync remains R27, Delta disabled; Gate15 remains locked.
No signing, new AAB, Git push, production modification or publication occurred.

### New Android-8 test environment and scanner recreation checkpoint (2026-10-03)

The user explicitly authorized downloading the official API26 image and creating
a separate local debug-test emulator. Only `system-images;android-26;google_apis;x86_64`
revision16 was installed (41.172s); no new license was accepted. The exact existing
Release26 files, SDK tools and existing AVD configurations retain their recorded
hashes. New `DomiDex_R27_Compatibility_API26` uses only `emulator-5558`, Android8/API26,
model `Android SDK built for x86_64`, SQLite3.18.2, 1536MiB RAM, two cores and no
camera. It is offline with zero accounts and no reverse mappings. Only the newly
created emulator needed a root-only airplane-mode broadcast/Wi-Fi/data setup;
the normal shell broadcast was denied and was not falsely treated as successful.
No ADB-server restart, existing-emulator configuration change or phone operation
occurred. Setup receipts live in `artifacts/release27-engineering-20261003/`
`api26-compatibility-review/`. First installation of the normal debug app `2c9ae510...`
and test APK `54a1ac35...` passed exact installed-hash, debug-certificate, source,
offline/idle and protected-file checks. This setup alone is NOT API26 app proof.
The new API26 runner/report contracts remain separate from the fixed API36 ones.
SQLite3.18.2 uses the documented old EQP positional format, not the newer node tree;
no original report or existing API36 validator is rewritten to manufacture a pass.

The additive real MainActivity recreation test runs through the production scanner
screen: old Activity destroyed, old PreviewView detached/IDLE, new Activity/View
distinct and STREAMING, restored scanner route, no implicit capture/upload and
protected preferences/files unchanged. Before launch it checks only the existing
schema13 database through a read-only framework handle and a throw-only corruption
handler, with no pending backup journal/presentation restore. No Room migration or
default corruption repair/deletion is invoked. Twelve pure parser tests pass0.079s.
Build `green-172125-fce12b2c` passes1m21s,104 tasks8 executed96 UP-TO-DATE, with both
lints and release Kotlin; the product APK remains exactly `2c9ae510...`.
The actual `g7-recreation-review/runtime-recreation1-`
`2cb25ec9f7784edeab3645d9399f764f/` has **1/1 JUnit PASS,13.581s**, zero skips,
all Android preconditions and frozen-input checks, but **overall FAIL remains**:
strict post-idle detected cached test PID7865. A subsequent independently guarded
background-test-only `am kill` verified exact PID/start/UID/APK and closed camera,
activities/services before acting; `assisted-cached-test-7865.json` records exit0.
This is assisted cleanup, not natural process-exit proof and not a rewritten PASS.
No app force-stop/data clear occurred. G7 still lacks physical/process-death and
other lifecycle coverage; Gate15 is not unlocked by this finite recreation test.

The prior rebuilt test APK `443db33e...` also passed the complete unchanged
**371/371** selection, zero skips/failures,305.723s:
`artifacts/android-regression/20261003T170036Z-60880268/`. Its cached test PID4575
required separately recorded guarded assistance (`g7-mainactivity-review/`
`assisted-cached-test-4575.json`). No natural-exit claim is made for that run either.
Local-only Git checkpoints now preserve the portfolio fix (`3da0e5c`), executor
test correction/evidence (`af472a2`), and Web SDK harness (`22012b9` in DomiDex-Web).
No push, signing, AAB build, deployment, Play upload or production change occurred.
Full-Sync remains R27; Delta-Sync remains disabled. Critical gates remain open.

### Test-only callback correction and broad scale rerun (2026-10-03)

The separately isolated six-case gRPC executor regression proves the exact defect
before its fix: `g9-grpc-executor-review/runtime-executor6-red-`
`2203864e649441e7930cf6bc98edeaeb/` has precisely five intended assertion failures
and one unchanged delegate-control PASS (0.172s). It remains RED/exit1, not a gate
success. After the reviewed test-only correction preserves the original
`CallOptions.executor`, `runtime-executor6-green-aa6f07147eb249d9a5302fc160ca7943/`
passes6/6 (0.165s), zero skips, with exact source/APK, offline/idle and no-reverse
guards. The eight pure parser checks also pass; synthetic logs are not SDK proof.
The product APK stays `2c9ae510...`; build `green-164540-5ecb81ec` passes1m17s,
both lints/release Kotlin, with JVM task UP-TO-DATE. Only test APK `7cc57023...`
was installed on owned5556. No production code, Rules, deadline, assertion or
existing18-case contract was relaxed. The actual18-case rerun now completes
**17/18 PASS, overall RED**,230.550s: Web `outputs/collection-sync-session-local/`
`2026-10-03T16-51-59-914Z-ae6def1ed997488c87e64ba673141a76/`. The formerly crashing
cache/sign-out case passes1.884s; no process crash remains in this run. Only the
unchanged lock-only listener case fails31.923s. The lock-plus-data-change case
passes2.737s and final1001-binder case18.436s. All18 complete Admin preflights and
readbacks, nineteen unchanged accounts, both exact scoped CAS restoration
protocols and all frozen-input/port/reverse/instrumentation postchecks complete;
no readback errors. `serverVerified=false` and overall exit1 remain correct,
because the mandatory original-listener denial was not observed in30s.
Official [Firebase documentation](https://firebase.google.com/docs/firestore/pricing#cloud_firestore_security_rules)
describes listener Rules reevaluation after dependent-document updates but gives
no30s guarantee. The installed CLI15.28.2/Firestore1.22.0 metadata matches the
[official pinned distribution](https://raw.githubusercontent.com/firebase/firebase-tools/v15.28.2/src/emulator/downloadableEmulatorInfo.json).
No exact documented Firestore-emulator1.22.0 exception was found. This is an unresolved local boundary,
not an established production defect or confirmed emulator-internal bug.

The unchanged1k/5k/20k Room-scale selection also passes1/1, all87 samples,
75.468s: `regression-isolation-review/runtime-scale87-`
`7a63e207ae9b4c0c8c966e5bf2ec3d20/`. Integrity/cleanup and frozen source/APK,
offline/idle postguards pass. Portfolio warm medians61.2812/191.4158/1352.0678ms;
private detail53.7067/191.1109/1137.5155ms. This is the original two-date scale
fixture, not annual-history, physical-device, p95 or production-load evidence.
The separate100000-timestamp multi-window test now passes1/1,82.971s with a
full structured PASS, not merely JUnit success: `g12-portfolio-aggregate-review/`
`runtime-windows1-e482248c7fe1492bab83617435f2184a/`. All three phases consume
100001 exact typed tuples across five actual CursorWindows. In BOTH correction
phases, the independent writer's real commit precedes the next real cursor refill;
every old-generation tuple remains coherent, then the new safe/unsafe state is
verified (including public legacy fallback). Both writers terminate; all15-table
hashes are restored and only the owned UUID fixture files are removed. Nine actual
queries/cursors, canonical SQL/four bindings and all source/APK/offline/idle guards
pass. The13 pure report tests distinguish missing overlap as PARTIAL/exit1.
This is API36/SQLite3.44.3 evidence, not an API26 or performance result.
Local inventory confirms API26 has no installed system image; API25 cannot replace
minAPI26. Full-Sync stays active and Gate15 locked.

### Current portfolio boundary evidence and remaining SDK failures (2026-10-03)

The app `2c9ae510...` / test `6a5325d5...` pair passes the fixed **371/371**
Android regression selection, zero skips/failures, 298.627s:
`artifacts/android-regression/20261003T160550Z-e2ee2e55/`. A subsequent strict
idle check detected only cached test PID24720. Exact process/start/UID/APK and
closed-resource checks preceded assisted background-test cleanup; receipt
`g7-mainactivity-review/assisted-cached-test-24720.json` preserves that distinction.
No app data was cleared; this is not a natural-process-exit claim.

Three additional strict owned5556 wrappers now pass, with the same APKs,
unchanged sources, offline/no-reverse and idle postchecks. Under
`artifacts/release27-engineering-20261003/g12-portfolio-aggregate-review/`:
- `runtime-aggregate13-2e93ed695f1c47508bef67b3b8bf7317`: 1/1 PASS,150.139s,
  all13 samples; four complete actual portfolio models, separate legacy/compact
  chart parity, all15-table hashes, real SQL/bindings and complete41-node aggregate
  plan (SQLite3.44.3).346750 legacy rows versus366 compact rows. Public warm median
  1234.4848ms; sequential diagnostics3614.0961/1432.0562ms, not a new matched SLA.
- `runtime-edge13-69c034943af840fba91e6c269881adcf`: 1/1 PASS,17.810s,
  all13 original mutation states and all3 owners; full cards/placements/history
  and all15-table read-preservation checks.
- `runtime-count3-eafc6745fa6d48e7b29d3b5989ceb633`: 1/1 PASS,69.372s,
  exact20000 safe ->20001 fallback ->20000 safe after tombstone; all3 state/field
  checks and only the test-owned UUID database cleanup. No latency claim here.
The pure report validator passes10 methods including83 negative cases. These
synthetic validator records are not device proof. API26 and real multi-window
concurrent-commit coverage are still open; the additive test draft is not executed.

Actual SDK run `DomiDex-Web/outputs/collection-sync-session-local/`
`2026-10-03T16-15-12-189Z-ba946e2f37624fa09d756a0bb2318660/` is **16/18, RED**.
The new lock-plus-valid-seed-change case passes2.686s, including original-target
SDK denial and local preservation; its ten-event owned CAS restore completes.
The original lock-only case still fails31.791s without original-listener denial.
Separately, the previously passing cache/sign-out case crashes during test cleanup:
an early, not-yet-started test gRPC call invokes its close callback from the wrong
thread. Stack identifies test-only `PrivatePullDemoGrpcGate.finishLocally`; do not
label it a production-app crash or waive it. A separate deterministic regression
and minimal original-executor correction are being reviewed, not yet applied.
All18 preflights/readbacks and both narrow restore barriers retain45 snapshots,
nineteen unchanged accounts and final16collections/1029references/1019documents.
No readback errors; input, port, reverse and instrumentation postchecks pass.
No whole-run SDK PASS or lock-only permission timing guarantee is claimed.

R8-only revalidation passes3m40s: `r8-portfolio-review/verification.json`, source
manifest `619da0bb...`, R8 9.3.16/minAPI26, all32 permitted tasks and five DEX
headers/checksums verified. New portfolio classes appear in mapping `950c5647...`.
Sources, schemas, harness and every pre-existing build output/Release26 file match
before/after verification. No APK/AAB was packaged; this diagnostic mapping is
not an upload artifact and does not prove minified runtime/native-symbol coverage.
Later additive test-only build `green-162944-1ceae92a` passes2m6s; product APK2c9ae510
is unchanged, test APKd96ef203 contains the new six-case executor regression and
multi-window proof. Only that test APK was installed on owned5556; strict source,
certificate, installed hashes and offline/idle checks pass. Those new tests have
not yet executed. Generated bytecode separately confirms the test observer's
read-only transaction calls delegate directly to the real database implementation.
Full-Sync remains unchanged, Delta disabled. Gate15 stays locked; no upload/push/deploy.

### Guarded portfolio history: genuine RED/GREEN and first matched annual comparison (2026-10-03)

The unchanged-product baseline is now established before the product optimization.
`regression-isolation-review/runtime-portfolio2-ddf99827bc08457bb72b80fbd88ab89f/`
has one sustained full-model PASS and exactly one intended final transport failure:
the safe portfolio still materializes the complete owner history (expected0/actual1).
Every preceding model, chart, typed15-table hash and no-sync-write assertion passes.
The earlier two baseline failures remain retained, not rewritten: their unexpected
Unicode case revealed an oracle assumption, not a product regression. Android's
literal `lower('reverſe') LIKE '%reverse%'` is characterized independently of any
fixture/model/query output; the oracle retains separate Kotlin history semantics.
No comparison, timeout or edge case was dropped to obtain the intended RED.

The narrow read-only product change aggregates bounded weighted historical price
changes in one transactional SQL snapshot. Unsupported/unsafe states use the
unchanged chronological history fallback. All original timestamps remain, including
zero/invalid-price events; no history retention, schema, index, network or sync change.
This is chart arithmetic, **not Delta-Sync**. Full-Sync remains active and unchanged.
Generated Debug/Release Room implementations are byte-equal SHA256 `7ab09b7e...`:
transaction flag true, exactly collection/catalog/raw-price invalidations, four
owner/source bindings, integer/nullable-long mappings and full cursor iteration
inside the transaction. API26 and concurrent multi-CursorWindow proof remain open.

Guarded build `pricing-duplicate-review/green-155334-feb219e2/` passes4m7s:
debug/test APKs, release Kotlin, both lints, **925 executed JVM PASS, one existing
opt-in SKIP, zero errors/failures**. App `2c9ae510...`, test `8d348968...` are installed
only on owned offline5556, with exact before/after file hashes and debug certificate.
Release26 AAB/mapping/symbols and existing bundle/mapping outputs remain byte-exact.
No release packaging/signing, device action or deployment.

Post-fix portfolio2 passes2/2 in1.531s; focused29 passes29/29 in11.415s, zero skips.
Both strict wrappers pass frozen inputs, unchanged APKs, idle and offline/no-reverse
checks. Evidence suffixes `ace37f58c16941a8b4e6a30f0419ab05` and
`5e45f012216e46be92858e6e00ced374` under `regression-isolation-review/`.
The unchanged annual58 method passes100.926s, two complete15-table hash sets and
own-file cleanup: `history-scale-review/room-scale-annual-20261003T160040Z-`
`7d14136de6a349629b8286c3548c97c3/`; wrapper exit0 and all postguards pass.
Same owned API36 AVD, fixture, selector, sampling order and three warm repeats as
baseline `20261003T130504Z-2757ccb4d7dc4442a3ccd35baa6d5b82`:
365-day public portfolio median **2950.9101 -> 1154.0951ms** (about60.9% shorter).
The fixture retains366095 raw rows/346750 eligible observations/950 active cards.
Private detail45.3884 ->53.7927ms is also reported; no private-detail speedup claim.
These are sequential local debug observations, not a randomized hardware comparison,
p95/SLA, UI rendering, peak memory, production performance or whole-G12 closure.

An additive18th SDK scenario (lock plus valid seed change) and its exact19-account
host protocol are now implemented, runtime still pending. The original lock-only
RED remains mandatory.76 host/stop/safety tests pass twice, zero skips; retained
Web TAP `outputs/collection-sync-session-local/g9-host18-20261003-1605.tap`.
Focused oxlint passes. This is host orchestration evidence, not SDK success.
Subsequent test-only build `green-160338-dbc0d8cb` passes with unchanged app2c9ae510
and test6a5325d5; its JVM task is UP-TO-DATE, not another execution claim.
The full371 regression and new aggregate/edge/count runtime proofs remain pending
at this checkpoint. **Gate15 remains locked; no AAB, signing, push or publication.**

### Existing-listener denial isolated; actual MainActivity lifecycle proof (2026-10-03)

The current diagnostic SDK run remains **16/17 PASS, overall RED**, not a waived
gate: Web `outputs/collection-sync-session-local/`
`2026-10-03T15-26-22-772Z-96672a3985a449928db77db74f57fb57/`.
The original knowledge listener receives no SDK denial within the unchanged30s
window. A separate real SERVER read of a different existing knowledge document,
governed by the same Rules, returns **PERMISSION_DENIED** under the lock. Before
and after this control, the audited original target6 has one ADD, CURRENT/ready,
zero denial and no audit error; both owners and Auth epoch1 remain unchanged.
The test intentionally still fails31.636s with the original timeout cause. This
isolates fresh authorization versus existing-listener reevaluation in this local
setup; it does not prove the emulator-internal cause or production behavior.
The product's local-preservation success tail was not reached, so is not claimed.
All38 complete Admin snapshots/eighteen accounts, exact one-lock create/delete
barrier, original update-time CAS, restored full tree, frozen172 inputs, owned5556
offline identity, empty reverses/instrumentation and free ports pass independently.
Final1001-binder case passes17.338s. No Rules/product/claims change was made.

Current-binary ordinary Android selection passes **371/371**, zero skips/failures,
288.151s: `artifacts/android-regression/20261003T150751Z-abca265f/`.
This receipt belongs to app5158a655/test3892dbe1, before the additive tests below.
Guarded build `green-151551-5c7e2df3` passes1m35s (104tasks,9executed/95up-to-date),
debug/test APKs, release Kotlin and both lints; JVM is retained UP-TO-DATE evidence,
not a newly executed917-case claim. App5158a655 unchanged, test8fd0f5b5. No AAB.

New genuine MainActivity navigation/PreviewView test passes twice:13.144s and
12.801s. It observes actual STREAMING -> Activity CREATED -> IDLE held for1s
(including every observer event) -> RESUMED -> STREAMING, same activity/view,
manual shutter UI, no capture/import/queue/upload and unchanged protected capture,
knowledge/evidence/consent files. Normal startup housekeeping is not whole-DB
immutability. It is not HOME/OS-death, fresh JPEG, permission-revoke or OEM proof.
The second wrapper parses **1/1 PASS**, but remains exit1 because Android retains
a cached test process after return. The first also exposed a singleton-summary
parser gap. A narrow new-suite-only adapter now requires exactly `OK (1 test)`
and retains the original strict identity/status/count checks;12 pure adversarial
tests pass. Shared371 validator and pinned annual/scale runners remain unchanged.
No original failed log/manifest was rewritten or relabelled as full-wrapper PASS.
Runtime directories in `regression-isolation-review/`:
`runtime-maincamera1-5ac73ff1faec4e99b6d5a4f6c7ea8b6b/`,
`runtime-maincamera1-6edc6d27564a4ee78bc65f6ccbf29d7a/`.
Before the next runs, fresh strict process identity/cached state/absent app and
activities/services/closed camera checks allowed only background `am kill` of
owned test PID19262, later22315. Receipts under `g7-mainactivity-review/` retain
the reads. This assisted preparation is not natural-exit evidence; no force-stop,
app clear, physical device or ordinary user AVD was used.

Full-Sync remains unchanged, Delta disabled; portfolio candidate is still under
matched baseline/fix verification. Gate15 stays locked, no signing/upload/push.

### Real revocation RED, fresh Web regression and camera resource diagnostic (2026-10-03)

The added real knowledge-permission listener proof remains **RED:16/17**. After
the actual product listener publishes a positive SERVER record, the host reads its
exact run-owned readiness, independently checks all accounts/documents, creates
one deletion lock, joins Android and deletes only the unchanged owned lock using
its original update-time precondition. That complete barrier/restore receipt passes.
The original live target does not deliver SDK PERMISSION_DENIED in30seconds; the
new Android case times out at31.616s. It is not passed by cleanup, sign-out, forced
relisten, fake transport errors or longer waits. Rules do reference the deletion
lock. Emulator reevaluation versus actual app behavior remains under investigation.
No product, Rules, claim, signing or sync architecture change was made for this case.

Evidence: Web `outputs/collection-sync-session-local/`
`2026-10-03T14-59-03-323Z-e748b8810d2d4b25bc5ed0453cb5f2ee/` (exit1).
All17 complete preflights/readbacks and eighteen nominal accounts are retained;
final16collections/1028references/1018documents, earlier owners, frozen inputs,
offline AVDid, no reverse/active instrumentation and free ports remain verified.
The failed case alone does not prove its Android teardown. Earlier transport-only
RED `14-45-20-685Z-6a9a01eb63104d4c81eb22e018f4e9f3` created no lock: Windows adb
changed LF to CRLF. The corrected byte-exact Base64 transport preserves strict
marker validation and remote shell-v2 exit status.63 host/stop/safety tests pass,
0skips; TAP is retained under the same output root. Fixture ownership now rejects
adoption/cleanup of a pre-existing run directory/preferences even after failed setup.

Current guarded build `green-142853-ee040f8f` passes1m38s,104tasks/8executed/
96up-to-date, debug/test APKs, release Kotlin and both lints. JVM was UP-TO-DATE,
not newly executed: retained917PASS/one existing opt-in SKIP. Product APK remains
`5158a6556bfea004ff81f6599b18f70674ba74bec73718da65de6420a1fe7ab3`;
new test APK `3892dbe1d9d92306834f7f0b0366aecb8e508a4f2e901bbcd07e47e842758a74`.
Only owned5556's debug test package was updated; hashes independently rechecked.

Fresh existing Web selection passes1295/1295 in27.93s (78files, no failure/skip),
`outputs/release-local/2026-10-03T14-37-52-355Z-20188.json`. Fresh real local
private-operations35/35 passes44.170s, unchanged inputs/free ports/exit0:
`outputs/private-operations-local/2026-10-03T15-03-06-745Z-eeec2e26/`.
Existing React/JSDOM and SDK/CLI deprecation/blocked-metadata warnings are retained.
These are local tests, not Eventarc, provider purge, production traffic or consent proof.

Unchanged virtual-camera tests again pass2/2 in16.729s, while their strict wrapper
remains exit1 for an existing test process. Before/after passive observations prove
same boot/APKs, absent app process, no active camera clients, device1 closed, no
app/test activities or test services, and restored rotation settings. Only cached
test PID15697/UID10218/start652430 remains (cch/CRE,frozen), whereas camera history
names app PID15581. This is resource poststate, not proof of the exact unbind instant.
Root subsequently reverified identity/resources and used only `am kill --user0`
on that owned background test package; the unchanged strict preflight then passes.
No force-stop, user app/device, test assertion or old failed manifest was changed.
An initial cleanup guard rejected the historical appended camera dump as if current;
it performed no kill. The corrected guard separates the explicitly labelled history.
Receipts: `artifacts/release27-engineering-20261003/g7-camera-passive-review/`
`observation-245444c51db040b3aabfd769aec4dc1a/`; camera wrapper
`regression-isolation-review/runtime-camera2-3401797d9f614d91a06dba12684eb628/`.

Full-Sync stays active; Delta deferred. Remaining portfolio, real-device, live and
release gates are not closed. Gate15 remains locked. No release build/signing,
Play upload, production/deployment change, Git push or phone action.

### Current-binary sixteen-case SDK and malformed knowledge proof (2026-10-03)

The additive real-listener negative case passes without a product change. Admin
creates one complete fresh synthetic aggregate whose only schema violation is
knowledgeVersion string `"1"`. An independent control SDK confirms the exact
SERVER payload; the actual adapter rejects it while its owner stays authenticated.
Only that fingerprint's base/.bak/.new cache files disappear, before any subsequent
cache get could delete them. Both owners' nonempty pending evidence and upload
receipts, another cache, directory inventory and isolated consent remain byte-exact.
The existing Room snapshot covers eight tables as strings, not all15 typed tables.
The full independent Admin tree/accounts remain unchanged. No upload is triggered.

Root current-standard build passes3m30s:104 tasks,10 executed/94 up-to-date,
debug/test APKs, release Kotlin and both lints. JVM task was UP-TO-DATE; retained
reports contain917 PASS/1 existing opt-in SKIP/0 failures/errors, not a new execution
of those917 cases. Product APK is unchanged; test APK is newly compiled:

- app: `5158a6556bfea004ff81f6599b18f70674ba74bec73718da65de6420a1fe7ab3`
- test: `b5f919749f06a380a16cedaadb896faf8692f7558e662e1622bfd011cad74b22`

Both are installed/independently hash-verified only on owned5556. Current371-case
regression passes371/371,0 skips/failures,315.878s; sources/APKs unchanged. The
complete real SDK suite passes16/16,162.627s total harness, malformed case1.550s,
large FULL case18.941s. All16 complete Admin preflights/readbacks,17 nominal accounts,
16 collections/1027 references/1017 existing documents, unchanged earlier owners,
fixture teardown, exact AVD/offline identity, free local ports, empty reverses and
inactive instrumentation pass. Inputs are frozen throughout; installed hashes and
historical signed-v26 SHA256 are independently rechecked afterward.32 pure host
validators and focused lint also pass. CLI deprecation/blocked metadata-lookup
warnings remain; the Node guard is not OS/Java/native no-egress proof.

Evidence: Android `artifacts/release27-engineering-20261002/pricing-duplicate-review/`
`green-135502-bd1c88b6/`; `artifacts/android-regression/20261003T135933Z-35fd1743/`;
installed receipt `artifacts/release27-engineering-20261003/g9-current-sdk-review/`.
Web `outputs/collection-sync-session-local/`
`2026-10-03T14-07-04-463Z-f38f2ef6eda446699f9b9ba8104ce096/`.
An initial read-only installed-path check rejected package-name dots in its overly
strict regex; it performed no install/runtime and was corrected before the receipts.

Actual permission revocation/transport-failure listener paths remain separate
unproved cases, as do Eventarc, production load/consent, physical lifecycle and
multi-device delivery. Full-Sync stays active, Delta deferred. Gate15 remains locked.
No release signing/AAB, production write, deployment, push, Play or phone action.

### Private Binder detail: integrated first-positive read, RED/GREEN and regression (2026-10-03)

The previously measured private-detail bottleneck is now fixed in three product
files. An additive read-only DAO projection returns each safely linked owned
product's independently earliest positive Normal and Reverse trend. The existing
complete card mapper consumes these bases instead of materializing the owner's
entire history for a private Binder. It preserves prices, saved alternatives,
historical percentage changes and all card/placement fields. Full portfolio/chart
semantics, schema, indexes, retention, import, networking and FULL-sync are unchanged.
Generated Debug **and Release** Room implementations observe collection_entries,
catalog_cards and raw_price_snapshots; no cache or manually invalidated state added.

Genuine isolated pre-fix RED `ec26839e9d6d4ec8816ab52ba427f947`: the sustained
observer control passes; the complete-value/query test fails only its final named
assertion (one whole-owner history query instead of zero). Post-fix GREEN
`63775f4bf55b4406a6d8c825c1968ffd` passes both tests, using identical complete test
source SHA256 `2df185fee88ffbe5ce78f3476485fcb4be2f431c0d46468bf0e54148995576f3`.
The full portfolio is a positive query-counter control. Independent expected full
models and all15 typed persisted-table hashes are checked. A single sustained
subscription covers corrections, earlier dates, absent/returning trends, saved
choices, catalog-only relinking, product/source changes, visual fields, placements,
tombstones and A->B->A. Eventual correct states are proved, not atomic emissions.
Both disposable builds preserve ordinary APK/output/v26 state and clean only their
owned packages. Later strict parser hardening has12/12 pure contract tests; the
retained real logs contain no hidden inline exception.

Fresh standard debug/test build, all JVM tests, release Kotlin and both lints pass
in5m42s: **917 JVM PASS /1 existing opt-in live SKIP /0 failures/errors**. New owned
offline API36 AVD `DomiDex_R27_Regression_API36`/5556 uses existing image, host GPU,
4GiB RAM and emulated back camera, never baseline userdata or a physical device.
The original371-case selection passes **371/371,0 skips/failures,305.809s**. Separate
complete Room/price/private-detail selection passes **29/29,10.739s**. Runner profile
contracts pass30/30; the existing baseline profile and exact371 identities remain.

Exact current standard APK hashes for these runs:

- app: `5158a6556bfea004ff81f6599b18f70674ba74bec73718da65de6420a1fe7ab3`
- test: `52fcb3cfd0adf40bce334e1276a4c955de75c59f89b3d14e5dd52cb71b2a368d`

Unchanged annual-history benchmark on this pair passes1/1 in112.837s, all58 samples,
15-table integrity and complete values/cleanup. Three-warm median private-detail
read: **45.3884ms at365 dates**,54.2321ms at2 dates. Full portfolio remains
**2950.9101ms at365 dates**,60.0822ms at2 dates. This is synthetic Room timing on a
new AVD, not phone/UI/network/cold-start/p95 evidence or a matched historical A/B
ratio. The full-history portfolio cost remains open; no general latency SLA claimed.

Evidence: `artifacts/release27-engineering-20261003/history-proof/` RED/GREEN IDs;
`artifacts/release27-engineering-20261002/pricing-duplicate-review/green-125023-9487ca58/`;
`artifacts/android-regression/20261003T125728Z-4d25d3c7/`;
`artifacts/release27-engineering-20261003/regression-isolation-review/`
`runtime-focused29-4bde8ec479b24464b056f1db7620aa97/` and
`runtime-annual58-2757ccb4d7dc4442a3ccd35baa6d5b82/`.
Historical output roots contain fresh2026-10-03 timestamps, not overwritten runs.
Owned test-package cleanup after371 was explicit and hash-verified; it is not OS
recovery proof. On resuming, both emulators were absent; their termination cause
was not observed. Root restarted only the same verified owned5556 config,
verified unchanged installed hashes,
no accounts and offline/no-reverse state, and stopped its own boot-started app
process before the next diagnostic. Baseline5554 was not restarted or altered.
The unchanged1k/5k/20k scale test then passes1/1, all87 samples and integrity
checks. Private-detail three-warm medians:50.9906/169.8877/1092.7974ms respectively;
these are local flow resubscriptions with two history dates, not a cold UI/network
SLA. Evidence: same wrapper directory, `runtime-scale87-8d56556b7ddc46eb835b853289b8ed18/`.

Separate virtual-camera run `runtime-camera2-c482524f02da4766a6168193db2486b7/`
contains **2/2 functional PASS,16.995s**, no skips: all four display rotations and
one actual CameraX CREATED->RESUMED cycle through the production wrapper, without
manual rebind. Both preview/analyzer stop for continuous quiet windows, resume with
fresh frames and produce one portrait JPEG. Test restores rotation, unbinds and
removes its own captures. It is not MainActivity, OS death, permission revocation,
OEM/physical camera or scanner-recognition evidence.

The camera **wrapper remains exit1**, because its final idle guard found cached
test-package PID4093 (cch/CRE), despite terminal instrumentation success. Root
independently checked exact owned AVD/test APK, absent active instrumentation and
closed current camera client, stopped only that owned test package, then reran
read-only preflight successfully. No assertion was weakened and the original
failed overall manifest is retained. This follow-up does not retroactively turn it
into a wholly clean harness run.

Fresh isolated offline R8 passes4m14s:32 exact task paths/28 executed/2 up-to-date
actionable tasks, five DEX files with verified headers/checksums, no APK/AAB or
signing. Source manifest `42567fa07b9c3be07d63950ba395e3e46983c4b9944a4c23879b532ec5513ab2`,
schemas, existing outputs and complete v26 archive match before/after. Existing
conservative no-shrink/no-optimize and targeted keep rules remain untouched;
R8 mapping is for this isolated compiler output, **not for Play upload** without
a corresponding final artifact. This is compilation/integrity, not R8 runtime,
packaged release, hardware or native-symbol completeness proof. Evidence:
`artifacts/release27-engineering-20261003/r8-private-binder-review/verification.json`.
Independent review found no correctness blocker; private detail still processes
the owner's current-card/base records, not only the displayed Binder's membership.
**Gate15 remains locked.**
No release-key signing, AAB, phone action, production write, deployment, push or Play.

### Current-history phase and first-positive counterfactual proof (2026-10-03)

New isolated, offline Room diagnostic run `934ff50d449d416993598a6e51c9200c`
passes **2/2, no failures/skips**: annual-history phases173.081s; thirteen edge
states18.429s. Debug-only build passes2m24s/72executed tasks. Four pure host
validator groups pass; independent source/fixture/harness review found no blocker.
No product source changed. Default app/SDK initializers and network permission
are excluded only from the disposable diagnostic package, not the ordinary app.

Unchanged annual365 fixture contains1003 products,950 active owner-A products,
366095 retained raw observations,346750 eligible observations. All25 samples,
all365 independent chart values/labels, full card/placement models,950 independent
normal/reverse bases and all15 typed persisted-table hashes pass. The separate
edge fixture proves13 states across ownerA, ownerB and empty owner: null/zero/
negative values, independent finish dates, earlier inserts, genuine same-date
REPLACE corrections, wrong source, linked siblings, tombstones, owner reassignment,
missing history and explicit quarantined identity. Every read phase preserves its
complete before-state; deliberate edge-fixture mutations are not product writes.

Three-warm medians on this exact isolated build/environment:

| Measured operation | Median ms |
|---|---:|
| Actual product-major Room history read | 2986.99 |
| Same stable chronological sort on Default | 223.74 |
| Default grouping | 191.75 |
| Existing chronological portfolio sweep | 98.71 |
| Test-only first-positive cursor | 9.19 |
| Concurrent actual portfolio + private-detail first subscriptions | 6351.76 |

These separately timed sequential phases are **not additive attribution**. The
candidate transports950 base records instead of346750 history records, but the
report does not claim that SQLite visited only950 rows. Both actual plans use the
existing product/source/date index. Normal and Reverse have independent earliest
positive lookups; the actual owner/link/quarantine predicate is preserved exactly.
This justifies investigating the narrow private-detail projection, not claiming
that the integrated Binder screen is already faster. The full portfolio remains
an open large-history cost. No rows, schema, retention or FULL-sync strategy change.

Sample0 follows unmeasured baseline reads and is not a cold/first DAO read.
The full-history comparison lists are scoped to a completed sequential helper;
concurrent reads retain only compact expected cards/chart. No forced GC/heap
increase. Current emulator has host GPU/4GiB RAM/192MiB growth limit; this is not a
physical-device or historical cross-environment A/B result. Four one-shot pairs
do not prove sustained invalidation, UI responsiveness or a latency SLA.

Evidence: `artifacts/release27-engineering-20261003/history-proof/`
`934ff50d449d416993598a6e51c9200c/{runtime,emulator-context.json}`.
Exact debug APK755ea554b70cd417a4c73e385cc408ae1ab3900d593cb43f0426b11cbb7b8dbd;
test APK9a50a29e2012d21a5d5f9b6f95613d688d2cf28f3b55cea7bf91ff35139383ec.
Inputs, ordinary output tree and complete v26 archive are unchanged; both owned
packages/databases are removed. Ordinary installed APK7f003…b105 is unchanged.
Reproduction/limits: `scripts/verification/history-proof/README.md`.
**G12 remains partial, Gate15 locked; no release-key signing, AAB, phone action,
production write, deployment, push or Play action.**

### Automatic large FULL continuation through actual WorkManager (2026-10-03)

The unchanged production worker now proves both budget continuations through
actual WorkManager2.11.2 TestScheduler, not directly invoked helper passes.
Root isolated run `580b60a79e984c4abb522894b885b1e0` passes **1/1,30.058s**:
1001 immutable seeded binders plus one genuinely ACKed pending binder; four
distinct WorkSpecs/attempts `[0,0,0,0]`; two explicit cycle starts, two worker-created
tails, exact linear prerequisites. First partial state has997 paging rows,
cursor`paging-0996`/phase`rr1|1|14`; both completed cycles have1001 paging rows plus
the ACKed binder, phase`rr1|1|0`/empty cursors. Second cycle is fully idempotent.
All14 domain Room tables, foreign pending binder/outbox, complete remote payloads
and seed server timestamps are checked. Independent Admin inventory1001→1002 and
nominal account claims pass. Held constraints and one-second terminal windows
exclude extra work only within those stated bounded observations.

The same isolated APKs rerun the original retry proof **1/1,36.865s**,2 WorkSpecs,
attempts`[0,1,0]`; actual retry27ms after unchanged30s eligibility. Both manifests
verify frozen inputs/protected outputs, exact owned cleanup and the unchanged
ordinary owner-installed emulator APK7f003…b105. It is not the tested isolated
APK and inherits no test claim. Build2m5s/72tasks passes;7/7 pure validators and
independent Android/host reviews pass. Existing warning scopes remain explicit.
Evidence: `artifacts/release27-engineering-20261002/workmanager-proof/`
`580b60a79e984c4abb522894b885b1e0/{runtime,runtime-full-budget-continuation}/`.
Build/receipts use fresh2026-10-03 timestamps despite the historical output root.
Full APK hashes and reproducible commands: `scripts/verification/workmanager-proof/README.md`.

Only test harnesses changed. This binder-heavy controlled TestScheduler proof
does not close OS delivery/process death/physical reconnect, mixed-group arbitrary
scale, simultaneous physical devices or production behavior. FULL remains active,
Delta stays deferred. **Gate4 remains partial; Gate15 locked. No signing with
release keys, AAB, production write, deployment, push or Play action.**

### Resume checkpoint: complete current-binary regression (2026-10-03)

Read back the completed pre-pause run, without reclassifying an unfinished run:
`artifacts/android-regression/20261002T132647Z-78f25325/manifest.json` records
**371/371 PASS, zero skips/failures**, exit0, frozen inputs/APKs and offline,
empty-reverse cleanup. It finished2026-10-02T13:31:20Z. Product APK is09cdad…,
test APK1c175… (full hashes in the listener checkpoint below), matching the15/15
SDK proof. No new run, physical-device, packaged-release or live claim follows
from this readback. Automatic large-budget WorkManager continuation is the next
local proof; Gate15 stays locked. No signing, deployment, Play upload or push.

Resume inventory: all source files recorded by that regression still match. The
ordinary local `app/build/outputs/apk/debug/` directory is now absent; its removal
was not observed or attributed. The retained test APK still hashes1c175…, but the
installed ordinary emulator APK now hashes
`7f00308b45958b15eb5ffcf2a95c88b4f13e2c0c922d2dda411184fe8626b105`, not09cdad….
The owner subsequently confirmed opening Android Studio caused this replacement;
previous regression receipts apply only to
their recorded09cdad… APK, not this different installation. The signed v26 AAB remains
079608…b14b. Do not describe the missing local APK as a preserved present file;
subsequent isolated proofs snapshot the actual current output tree and never
replace the ordinary installed application.

### Actual scanner-knowledge listener and sign-out proof (2026-10-02)

The complete guarded local Auth/Firestore suite now passes **15/15**, with15 full
independent Admin preflights/readbacks and16 unchanged nominal Google accounts.
Whole harness139.382s; new knowledge case1.294s, unchanged final1001-binder case
14.710s. Inputs, ports, reverses and instrumentation cleanup all pass. No product
or Rules change was necessary: this extends tests/validators only.

The unchanged `FirebaseScanKnowledge.observeRelevant` must replace a deliberately
different fresh local record with the complete real server record. For a second
fingerprint, a confirmed absent document must produce null AND remove its exact
AtomicFile cache before any read could delete it. An unrelated cache remains
byte-identical. The still-open positive observation must publish null after actual
subject Auth sign-out; callback-time UID rejects earlier authenticated failure-null.
Independent control Auth remains signed in. Fresh positive cache, Room snapshot,
unique consent preferences and empty outbox/receipt directories remain unchanged.
The test owns a marked UUID directory and unique preference file; verified exact
cleanup never touches the default app cache/consent. Full Admin tree/timestamps
are identical before/after this read-only case.

Evidence in sibling Web: `outputs/collection-sync-session-local/`
`2026-10-02T13-17-53-019Z-705ccc68641040b7a510cdc6d836b152/`. Root independently
verified installed app SHA256 `09cdad1dfafd62f37f482cf2d63a7c287009b074f0dd8ba2402a531e1a864633`
and new test APK `1c17553d33e41edd2a5c0e05b4ef0eefbf3cbfc22858fb3e2d25ecf9099a94fe`.
Android evidence: `artifacts/release27-engineering-20261002/knowledge-listener-review/`
`green-131159-3ce18963/` and `installed-apks.json`. Debug/test build and both lints
pass1m17s; product APK unchanged; the JVM task reuses its recorded917PASS/1optional
SKIP, not a fresh execution. The26 pure Web contract tests and focused lint pass.
Independent draft review found no blocker. An operator-aborted old-test-APK run
`d4d5f88b...` is retained as NOT PASS in `aborted-harness.json`; owned processes,
reverses and exact APKs were independently checked before the successful restart.

Fresh isolated R8/DEX verification passes3m13s:32 allowlisted task paths,
28executed/two up-to-date actionable tasks, five DEX with valid headers/SHA1/Adler.
Conservative rules are preserved, no APK/AAB generated or signing configured.
Inputs/schemas/ordinary APKs/AAB/mapping and complete v26 archive remain unchanged.
Evidence: `artifacts/release27-engineering-20261002/r8-only-price-integrity/verification.json`,
source manifest `3f7e9810f975236d3e989310c6f770f00752dfbd26aa9230484d8381d34cb9e9`.
Diagnostic mapping `1e539e524ea1a91a7728bd3362298ac6bc1f89345d569e3aee316dec9c2712e3`
is NOT for upload or association with v26. Existing Kotlin lifecycle deprecations
remain; this is not packaged-runtime/native-symbol/Play validation.
Fresh isolated Web unit regression also passes **1295/1295**,78files,23.19s:
`outputs/release-local/2026-10-02T13-24-39-770Z-2132.{json,log}`. Existing React-act
and Canvas limitations remain, no real browser/live performance claim.

This proves the three narrow adapter paths, not nonempty evidence/receipt
preservation, malformed data, revoked permissions, transport failure, A→B→A,
post-close callbacks, process death, Eventarc, real-user consent or scanner quality.
No physical device, production project, signing, AAB, deployment or push was used.
**Full-Sync remains active; Delta deferred; Gate15 locked.**

### Conflicting price-guide rows: reproduced and rejected before persistence (2026-10-02)

One narrow importer preflight now rejects differing parsed values for the same
Cardmarket product within one guide. All nine prices, nulls and category are
compared before catalog joining. Equal duplicates preserve order/counts; later
independent files can still correct the same source timestamp. Schema, network,
identity mapping, history and FULL sync are unchanged. The temporary map adds
expected O(n) traversal/O(unique IDs) references; added peak memory/time is not
yet quantified and no performance improvement is claimed.

The original code fails the new integrity test using the real parser and Room:
catalog rename/new product and conflicting prices overwrite the prior good rows.
Focused JVM RED is35PASS/1intentionalFAIL; Room RED3PASS/1intentionalFAIL. After the
fix the complete JVM suite is **917PASS, 1 existing optional live SKIP, 0 failures**
(134classes), and Room is **4/4PASS**, 0.821s. Separate HTTP/outer RUNNING→ERROR
status handling is not exercised by that Room test; it must not be described as
preserving the entire production status row. Existing rollback/correction tests
are unchanged. Independent review found no blocker.

Debug/test APKs, release Kotlin and both lints pass in3m37s; lint retains148debug/
151release warnings, no errors. Inputs and the complete protected v26 archive
remain unchanged during the build. New standard debug APK SHA256:
`09cdad1dfafd62f37f482cf2d63a7c287009b074f0dd8ba2402a531e1a864633`;
test APK `08dab2f72777522dce5f799accfcd8b23f2e38ea74409bee75b0b257d0618741`.
Only the offline emulator received them; **the Redmi was not changed**.
Evidence: `artifacts/release27-engineering-20261002/pricing-duplicate-review/`
`red-011429-1f823aa9/`, `room-124815-f2ca6444/`, `green-124911-83029c8c/`,
`room-125324-14b2f629/`. Exact semantics/limits: `docs/cardmarket-import.md`.

The first unchanged371-case rerun, `artifacts/android-regression/`
`20261002T125359Z-f666efba/`, is **INCOMPLETE, NOT PASS**: Windows Event1000 records
qemu-headless0xc0000005 at14:54:31CEST, unknown fault module, during the same Holo
preview selector previously documented as renderer-sensitive. The host process
crash is confirmed; a specific graphics-driver cause or Android code failure is
not. Recovery uses the earlier proven host-GPU/4GiB configuration, no snapshot,
no data wipe or persistent AVD change. Receipt: `emulator-recovery.json` alongside
the price evidence. The unchanged full matrix subsequently passes **371/371**, no
skips/errors, 260.948s, with frozen inputs/APKs and offline/no-reverse afterward:
`artifacts/android-regression/20261002T130324Z-51cfd6db/manifest.json`. All24 pure
runner contracts also pass. The4 importer Room cases are separate, not hidden
inside the371 selection. Different renderer/RAM excludes a performance A/B claim.

The frozen official download had no duplicate IDs: this is preventive integrity
hardening, **not** closure of its outstanding Cardmarket coverage gaps. Earlier
WorkManager/R8 proofs retain their own recorded source/binary identities.
**Gate15 remains locked; no signing, AAB, deployment, push or Play action.**

### Actual local WorkManager retry and shared-view work reduction (2026-10-02)

The unchanged production private-binder scheduler/worker has now run through real
WorkManager2.11.2 with the official TestScheduler, real clock and local Auth/Firestore
SDKs. Root result: **1/1 PASS, 36.744s**, exactly two WorkSpecs and factory attempts
`[0,1,0]`. Held CONNECTED constraint invokes nothing; genuine SDK network failure
causes retry without any local/remote mutation. The second attempt starts24ms after
its unchanged30s eligibility (30,052ms between factory invocations), receives the
actual remote ACK and persists FULL metadata. A second actual APPEND_OR_REPLACE
enqueue leaves the exact document/revision/server timestamp unchanged.

All14 domain Room tables and the foreign owner's pending binder/outbox are checked.
Independent Admin readback verifies the complete synthetic server tree and unchanged
account claims. Own databases, two UUID-scoped test packages and local reverses are
cleaned up. Full source/output inventories and ordinary installed APK
`6f8cee6b...e19835` remain unchanged. No product-source or Gradle/dependency/signing
change was needed; only the explicit opt-in test init adds official work-testing.
The Redmi was not used. Evidence under
`artifacts/release27-engineering-20261002/workmanager-proof/`
`5bfca253658a4c7f844cb245428c73b0/runtime/` includes manifest/device/server proof and
the raw instrumentation log. Durable runner and scope: `scripts/verification/workmanager-proof/`.

Earlier harness failures are retained: compiler-JAR task guard, AGP9 separate Kotlin
sources, debug-certificate DN order, and raw/canonical Android path comparison.
The successful receipt confirms `/data/user/0` and `/data/data` aliases for the same
owned database. No assertions about worker behavior were weakened. The pure runner
contract suite passes3/3, including malformed/extra signer and skipped-test negatives.

This proves controlled TestScheduler integration, **not** OS JobScheduler, real
connectivity change, process death, large-budget automatic continuation, expedited
quota or simultaneous physical-device behavior. Those lifecycle limits remain open.
Full-Sync stays active; Delta remains deferred. **Gate15 stays locked.**

Separately, Web commit `4d9e1c9` reuses the DTO's exact printing/trend instead of
resolving it twice when trend is missing. No reads, auth, DTO fields or pricing rules
changed. The real forwarding-spy fixture reproduces128→64 resolver calls for64
complete DTOs. Full RED1294PASS/1intentionalFAIL becomes **1295/1295 PASS** (78files,
23.53s), with seven complete parity cases. Typecheck and focused lint pass. Evidence:
Web `outputs/release-local/2026-10-02T00-46-18-162Z-16120.*` and
`2026-10-02T00-49-35-054Z-18684.*`. Existing act/Canvas warnings are retained.
This is not a measured live speedup or an O(N)/fan-out solution. Nothing deployed.

### Clarified Redmi follow-up: three correct Nidorina suggestions (2026-10-02)

The installed APK digest was read-only rechecked and remains `6f8cee6b…e19835`.
Six newer observations were present. The owner explicitly clarified that their
report concerns the **last three cards currently in the queue**, all correctly
displayed: Nidorina MEP101. Root initially attributed this ground truth to the
first three earlier sequential observations; that attribution was incorrect and
is retracted. The earlier three cards' ground truth is not established.

The intended triple independently logs `MEP101`/`mep` three times, two candidates
each and score pairs84:84. Active processing **4688/4384/4190ms**; approximate
capture-to-terminal **4691/8298/11761ms**, because these three were captured in a
burst. Correct suggested cards plus an `AMBIGUOUS` final state is not a failed
card lookup; nor does it prove uniquely accepted physical printing. The catalog
has two Nidorina printings and the resolver explicitly retains unpriced candidates.
No Cardmarket-filter defect is demonstrated; no product fix was made.

Earlier sequential observations independently show5108/4000/4350ms processing,
no material queue wait and no candidates, but must not be labelled Nidorina failures.
Several new sharpness scores exceed0.12 while processing remains around4s;
low sharpness is not a general explanation. `usedGeometryFallback` describes a
contour path, not by itself the soft-card review reason. The local post-normalization
block includes preparation/OCR/parsing/descriptors and still dominates; precise
subphase evidence is required before a quality-neutral optimization.

Minimal receipt: `artifacts/release27-engineering-20261002/history-performance-fix/`
`redmi-scans-followup-20261002.json`. No photos/raw OCR persisted, no app/device/
production changes. Previous local regression/R8 results remain valid; this
limited user observation does not close the full hardware or release gate.

### Latest exact-binary checkpoint and owner-operated Redmi scans (2026-10-02)

Current standard-debug app SHA256:
`6f8cee6b9a81e0e444dce2c78a6f434b87439232ad58e612c075264087e19835`;
test APK: `ac7f2e8daed63577b86d0cd52756f211a613f48ed770d431bc97d75585d5e2fd`.
The same app APK is installed on the explicitly authorized Redmi. No uninstall,
data clear, release key, new AAB, Play operation or deployment occurred.

- **371/371** fresh fixed Android regressions, no skipped/failed cases, 298.267 s
  instrumentation; frozen sources/APKs and offline/no-reverse afterward:
  `artifacts/android-regression/20261002T000810Z-c3faeca9/manifest.json`.
- **18/18** focused Room/Flow/projection/ACK cases and installed-file digest check:
  `artifacts/release27-engineering-20261002/history-product-room-235453-ecea92cc/`.
- **1/1 unchanged annual-history benchmark**, 58 samples, all 15-table hashes,
  full valuations, owner checks and exact owned-file cleanup; 120.738 s test time.
  New-binary warm medians at 365 dates: portfolio **3058.43 ms**, private contents
  **2675.52 ms**, collection19.33, set summaries34.75, private summaries41.18.
  At two dates: portfolio48.45/private49.61 ms. Evidence: dated engineering dir,
  `history-scale-review/room-scale-annual-20261002T000532Z-fea265ab0c354861af1b03a8b0348b1f/`.
  Earlier paired before/after numbers below retain their original binary identities;
  the new result is not substituted silently. Roughly 2.7–3.1 s remains at large
  retained history. No UI, physical-device, cold-disk, p95 or network SLA claim.
- **14/14 actual local SDK cases**, complete independent Admin preflight/readback
  after each case, all 15 nominal accounts and prior owners unchanged; frozen
  inputs, free ports/empty reverse/idle afterward. New 1001-binder case18.507s,
  whole harness236.007s. Web evidence:
  `outputs/collection-sync-session-local/2026-10-01T23-56-21-042Z-6e75c4ca78764c4e8c77bccf03c3a004/`.
  Four explicit worker passes prove bounded FULL continuation, genuine Room reopen
  and idempotence across two cycles. This is not automatic WorkManager delivery,
  OS process death, simultaneous physical devices or arbitrary-scale coverage.
- **Isolated R8/DEX verification PASS**, build3m45s, 32 allowed task paths,
  28 executed/two up-to-date actionable tasks, five valid DEX, no APK/AAB/signing.
  Inputs/schemas/existing outputs and the complete v26 archive remain byte-identical.
  Evidence: `artifacts/release27-engineering-20261002/r8-only-history-order/verification.json`;
  source manifest `c0e37763689276bacb2357823a62466b4f5cc714737ebfb1f081d8a0e3ef459f`.
  Mapping `dab26ad7a0afd5c2487615fcc7df3afa855cf61de37dcc5f9d99f146466a597b`
  is diagnostic only, NOT for upload or association with release26. Existing
  conservative R8 rules remain unchanged; no native-symbol/Play acceptance claim.

Owner-operated Redmi manual scans were read afterward without retaining photos,
OCR, card identities or raw scan IDs. Three captures arrived only 0.77/0.85 s
apart. Active processing: **4524 / 4984 / 3932 ms**; approximate queue-to-terminal:
**4531 / 8755 / 11842 ms**. Timer starts after acquiring the single processing
slot. Outcomes: one `MANUAL_REVIEW`, two `AMBIGUOUS`; the owner confirms the right
card was among suggestions. This is candidate recall, NOT three automatic matches.
The latter two top-score pairs are 84:84 and 83:83; no margin threshold is weakened.

The post-normalization analysis window is 3465/3911/3382 ms, including ROI setup,
OCR/orientation/parsing and descriptors, not isolated ML Kit time. Descriptor
8/4/3ms is already inside that window. Geometry674/354/312ms and perspective79/56/42ms
are smaller. All captures are one frame/manual with fallback geometry; sharpness
scores 0.02839/0.10846/0.06041 fall below the existing0.12 limit. With preserved card
boundaries this is consistent with the intended soft-card manual-review path,
not proof of a new regression or grounds to reduce quality checks. No image review
or exact release26 comparator was performed. Further controlled sequential scans
and the wider hardware matrix remain open. Minimal evidence:
`artifacts/release27-engineering-20261002/history-performance-fix/redmi-scans-20261002.json`.

Full sync remains active; Delta is deferred. WorkManager/OS lifecycle, real
multi-device conflicts, full live-price coverage, controlled live load/monitoring,
complete deletion/retention operations and final packaged-release checks remain
open. **Gate15 is locked; overall R27 is NOT READY.**

### Previous follow-up: annual-history query fix and bounded proofs (2026-10-02)

The actual counterfactual below justified one narrow product change in
`CardVaultDao`: transport the same four owner/source-filtered history fields in
existing product/date index order, then restore chronological order using a
stable sort on `Dispatchers.Default`. No dates are discarded. Filters, pricing,
schema, indexes, dependencies, FULL sync and Room invalidation tables are unchanged.
Independent review includes both generated Debug/Release Room implementations.

The **UNCHANGED annual business benchmark passes 1/1**, 58 exact samples, 15-table
hash/integrity checks, all 365 valuation points, complete card equality, owner
isolation and exact owned-file cleanup. Three warm resubscription medians in ms:

| Flow | Before, 365 dates | After, 365 dates | After, 2 dates |
|---|---:|---:|---:|
| Collection | 20.28 | 19.21 | 17.52 |
| Set binder summaries | 35.87 | 32.58 | 48.03 |
| Private binder summaries | 37.21 | 36.41 | 52.27 |
| Portfolio | 16,101.36 | 2,713.24 | 51.75 |
| Private binder contents | 16,108.68 | 2,615.00 | 54.39 |

Evidence: `artifacts/release27-engineering-20261002/history-scale-review/`
`room-scale-annual-20261001T233749Z-3b7e80a0397543c6aab11606f45d27a7/manifest.json`.
This is a fixed debug-emulator fixture with 346,750 eligible history rows, NOT
physical-device/UI/network latency, cold-disk, peak memory, p95 or a global SLA.
Approximately 2.6–2.7 s remains a large-history limitation; no broad claim that
Gate12 or the complete R27 mission is finished. Summary timing variation is not
claimed as a product optimization. All full-sync/retention semantics remain intact.

Product-fix build, actual fresh JVM execution (**914 PASS / one existing optional
live-price SKIP**), release Kotlin and both lints pass in 3m32s. **18/18** focused
actual Room/Flow/projection/ACK cases and **371/371** fixed Android regression pass
on product APK `1440ca33…d3eb96`, test APK `7d45119d…15ed25`; no failed/skipped Android
cases. Frozen-source receipts: `history-product-room-233542-7dbe21ee/manifest.json`
and `artifacts/android-regression/20261001T234051Z-e8124b6b/manifest.json`.
Web broad regression: **1287/1287**, 78 files, 28.52 s,
`outputs/release-local/2026-10-01T23-31-18-741Z-16540.{json,log}`. Two additive cases
characterize the existing 5000-raw-placement budget including tombstones; no
limit increase, deletion policy, legacy filtering or share behavior change.
Existing React act/Canvas and dependency-deprecation warnings remain visible.

The two new actual local Admin-SDK knowledge cases pass together with all previous
33 private-operation cases: **35/35**, harness 53.567 s, new cases 5.860/3.696 s.
Canonical aggregation persists three concurrent contributor votes correctly; exact redelivery
is a full stored-graph/server-timestamp no-op. A genuinely stale evidence version
and deletion lock block writes, and removing only the synthetic lock allows the
same valid version to apply. Evidence: Web `outputs/private-operations-local/`
`2026-10-01T23-47-21-660Z-52ae5b8f/manifest.json`, exit0, frozen inputs/free ports.
This manually invokes the genuine callback with genuine server snapshots; it does
NOT prove Eventarc delivery, retries actually occurred, production contention or
real-user consent. No production change or altered transaction attempt limit.

At the owner's subsequent explicit request the fresh standard-debug APK was
installed **only as DomiDex** on the Redmi, in place, without uninstall/data clear.
Installed/local SHA256 both
`6f8cee6b9a81e0e444dce2c78a6f434b87439232ad58e612c075264087e19835`, code27,
`1.0-closed-alpha.6`. The previously installed code27 APK differed. A local APK
path disappeared during this session; its cause is not inferred. A fresh 19s
debug rebuild plus **18/18** focused cases verifies the replacement identity;
broader tests on this new binary are tracked separately, not silently inherited.
Receipt: `history-performance-fix/redmi-install-20261001T235622Z.json` under the
dated engineering directory. Installation success is not a hardware test PASS.

Read-only Cloud configuration was inspected, not changed: default Firestore
TTL/backup-schedule/backup list queries return empty arrays; PITR and deletion
protection are disabled, with a 3600s version-retention window. Initial Logging
bucket page shows `_Default` 30 days and locked `_Required` 400 days, not a proven
complete log inventory. Storage soft-delete window is 604800s; it is not automatic
expiry of live images. Two read Cloud Run revisions have 1 CPU/512Mi, concurrency40,
timeout60s and maxScale2. Service default traffic and latest-ready differ; Hosting
pinTag routing is **not** established by those values. No live load, alert,
deletion, restore or legal-compliance conclusion follows. Minimal receipts are in
`artifacts/release27-engineering-20261002/lifecycle-gates-review/`.

Full sync remains active; Delta remains unwired/deferred. Gate15 stays locked.
No release package, upload key use, Play upload, deployment or production writes.

### Scope decision: retain full synchronization for R27 (2026-10-02)

The owner explicitly chose the currently secured full-sync implementation for
R27. The existing Collection listener and fair, bounded private FULL pull remain
active; the delta reader/Room adapter stay unwired. Delta activation, its query
completeness proofs and any larger read-path redesign are deferred to a future
optimization, not remaining R27 release requirements. No code, schema, cursor,
Rules, index, setting or dependency changes are needed for this decision.

Correctness, data integrity and existing regressions take priority. The remaining
full-sync lifecycle/offline/multi-client tests and all non-delta mission gates
remain applicable. Historical delta experiments below are retained as evidence,
not current rollout instructions. Overall NOT READY is not lifted solely by
this scope adjustment. Release-signing/production restrictions remain; subsequent
physical-device work uses only the separately authorized scope described above.

### In-progress follow-up: isolate annual-history cost (2026-10-02)

Full sync remains unchanged and Gate15 remains locked. A separate opt-in real-Room
diagnostic on the same product APK (`64d40b75…822d3`) captures the actual generated
SQL and its exact source/owner bindings, then compares DAO emission, an equivalent
raw cursor, grouping and the actual chronological valuation sweep. **1/1 PASS**,
nine exact timing samples, 346,750 eligible rows, full four-field projections,
all 365 independent values/month labels, 15-table hashes and owned-file cleanup.

Two diagnostic observations each (ms, NOT three-warm medians or a device SLA):
DAO **15,840.59 / 15,514.48**; identical raw cursor **15,582.37 / 16,558.33**;
groupBy **184.22 / 132.48**; actual sweep **162.61 / 107.52**. Raw first movement
is 831.95/783.54 ms and remaining materialization 14,747.48/15,773.24 ms. These
phases include SQLite/cursor work; they are not isolated disk/SQL/ORM attribution.
Actual EXPLAIN uses the existing product/source/date index and a temporary B-tree
for the global date ordering. CursorWindow refill/repeated-sort causation is a
hypothesis, not a counted or directly proved event. No driver/schema/retention
change is justified by this measurement alone.

Evidence: `artifacts/release27-engineering-20261002/history-performance-fix/`
`probe-20261001T230841Z-ba2962cf84294c47be485c682084157d/manifest.json`, exit0,
exact installed identities, frozen inputs and offline/no-reverse/idle verified.
The separate test-only ordering counterfactual subsequently **passes 1/1** with
five exact samples: original raw materialization **15,214.81/15,395.98 ms** versus
product-ordered materialization INCLUDING stable Kotlin time-sort
**3,462.76/3,621.53 ms**. Both complete 346,750-row projections match, including
the observed original sequence in this fixture; both actual history sweeps match.
The existing unique product/source/date index is verified and only the original
EXPLAIN has a temporary sort B-tree. All 15 table hashes and owned-file cleanup
pass. Evidence: same directory,
`order-20261001T232039Z-5122789d0e0a4c02ad24d03464ebd85b/manifest.json`.
Two synthetic positive/55 negative/three invalid-JSON Host controls pass. This
supports a narrow query-order change, not a universal speedup or a finished gate.
Both SQL-specific diagnostic methods intentionally describe the PRE-FIX product;
after a product change, retain these historical preconditions and rerun the
unchanged annual business benchmark plus continuous-Flow regression instead.

New ongoing-owner-history Flow control first passes against UNCHANGED product
sources, together with the existing eleven Room ACK/rollback controls: **12/12**,
`history-flow-room-231656-cf342add/manifest.json`. It exercises early/late updates,
same-date corrections, null/zero/negative values, wrong source, tombstone-only and
entry-owner-only invalidation under two continuous subscriptions. It does not
substitute re-subscription or sorting inside the test for actual ordered emissions.
Rebuilt test-only additions pass debug/test APKs, release Kotlin and both lints in
37/38 s; the unchanged 914-PASS/one-optional-SKIP JVM task is reused, not rerun.

Fresh independent local backend/Rules checks on unchanged Web product sources:
**33/33 actual backend/Web-client SDK cases**, Web
`outputs/private-operations-local/2026-10-01T22-59-18-269Z-9aecaaf3/manifest.json`,
and **84/84 Rules cases**, Web
`outputs/rules-local/2026-10-01T23-02-19-857Z-f05e79a9/manifest.json`. Both exit0,
frozen inputs and all ports free afterward. These local synthetic-account proofs
do not establish production retention, hardware, real OS scheduling or live load.

### Actual FULL two-client conflict proof (2026-10-02)

**13/13 actual local SDK cases PASS**, complete harness **100.475 s**; new
two-client case **6.726 s**. A and B use separate marked file-backed Room databases
and named SDK clients. A really creates revision1, B receives it via FULL; both
hold different pending base1 edits. A commits revision2 and B's stale edit, even
with a newer client timestamp, must yield REMOTE_WINS. The actual worker consumes
B's original outbox mutation and converges every binder field. An independent
SERVER witness checks the exact winning payload/timestamp before and after B
and fresh A/B passes; no revision3 rewrite occurs. All fourteen business tables,
FULL metadata, prior owners, and final owned-file/SDK cleanup pass. No production
code, sync policy, Rules or lifecycle implementation changes were necessary.

Web evidence: `outputs/collection-sync-session-local/`
`2026-10-01T23-24-15-174Z-448b17e0ba034f5796a0336a0290f8f0/manifest.json`.
Thirteen full Admin preflights/readbacks and fourteen nominal accounts pass;
inventory remains within unchanged 16-collection/32-reference bounds. Exit0,
all ports free, empty reverse mappings, idle instrumentation and frozen inputs.
18/18 pure validators and focused oxlint pass. An initial attempt to invoke
ESLint found no such installed module; the actual project linter is oxlint.
Fresh installation + 12 Room/Flow cases: `history-flow-room-231944-f97408e3/`.
App remains `64d40b75…822d3`; test APK is
`7d45119d587f8d97f86c896e03662e608aecba3a83a8c32b8fd2d91cf915ed25`.
This is sequential SDK-client convergence in one process, not simultaneous
physical devices, automatic WorkManager delivery, OS reconnect or process death.

### Previous local checkpoint: bounded annual-history scale (2026-10-02)

The separate opt-in method `localRoomAnnualHistoryAtBoundedSyntheticScale`
compares two fresh file-backed Room fixtures on the SAME unchanged product APK:
1,000 owner-A products plus three foreign sentinels, 50 A tombstones, and 2 versus
365 daily source dates. **1/1 actual case PASS, 58 exact samples, 240.700 s**.
All 365 valuation points, Normal/Reverse prices, current timestamps, complete
private-card equality, owner switches and all 15 table/per-column hashes pass.
There are 2,006/366,095 raw rows and 1,900/346,750 eligible owner-history rows.
Both owned UUID databases and their SQLite sidecars are closed/deleted and their
absence asserted in Android finally; the Host verifies both explicit receipts.
This is not an independent whole-device inventory or an importer performance test.

Three warm resubscription medians (ms; first-after-seed measured separately):

| Actual local flow | 2 daily dates | 365 daily dates |
|---|---:|---:|
| Collection | 18.55 | 20.28 |
| Set binder summaries | 49.84 | 35.87 |
| Private binder summaries | 53.79 | 37.21 |
| Portfolio | 57.60 | 16,101.36 |
| Private binder contents | 52.59 | 16,108.68 |

**Open performance finding, not release approval:** large retained history makes
the latter two flows slow in this controlled debug-emulator fixture. Both load
all owned history projections and group them; `includePortfolioHistory=false`
only skips chart construction, not that shared cost. The similar durations do
not isolate SQL, cursor materialization, grouping or GC; no query-plan/peak-memory
or physical-device claim is made. Main SQLite files are 2,207,744/74,616,832 bytes
in the final sample. Summary variation is not an optimization/speedup claim.
Keep all dates and price semantics; a separately proved first-positive-history
projection for binder details is only a possible follow-up, not implemented.
No schema, retention, data, sync, Rules or other product code changed.

Evidence: `artifacts/release27-engineering-20261002/history-scale-review/`
`room-scale-annual-20261001T224027Z-bc230904f2494440a736f2fe6340cb7e/`.
Manifest exit0, 58 validated samples, exact installed hashes, offline/no reverse,
idle and frozen inputs all pass. Build/debug APKs/release Kotlin/both lints PASS
in 37 s (104 tasks, eight executed); unchanged JVM result reused, old Migration-
Helper warnings remain. Separate pure Host controls: one positive, 23 negative
reports and three malformed JSON cases PASS. Fresh installation and 11/11 Room
controls: `collection-session-room-224000-a6fab4ed/manifest.json` in the same dated
engineering directory. Product APK remains `64d40b75180873a339413460b5af1455a32c32ac24096577777d3a725ec822d3`;
test APK `9426a66ee1cdd70906527804facc9f4b13ab2c7a8d687c7756fdf598efd5f078`,
source snapshot `518ee15529cc477440690d95fa749c75a4bf216bc394dd78441843818e2db918`.
Earlier 371/12 SDK cases below retain their exact pre-annual test APK identity;
the annual addition changes no existing test body or product APK. No signing,
release build, production or Xiaomi action. Gate12 and overall R27 remain open.

### Previous local checkpoint: actual offline FULL-worker recovery (2026-10-02)

Test-only extension: a valid pending private binder/outbox in a uniquely marked
Room file survives two real worker retries with its SDK transport disabled,
including file close/reopen between them. All fourteen business tables remain
exact; the worker does not open Room before successful SERVER authorization.
An independent nominal SDK client observes no premature remote write. After
re-enable, the real transaction, ACK and current four-group FULL sweep complete;
a second online invocation preserves every local/remote field and timestamp.
This does not claim OS reconnect delivery, WorkManager retry, process death or
page/budget-scale completion. No product/schema/Rules/architecture change.

**12/12 actual SDK cases PASS** in 91.365 s complete harness time; new case 4.094 s.
Twelve independent complete Admin preflights/readbacks and thirteen nominal
Google accounts pass, with no extra documents/claims or changed prior owners.
16/16 pure validators and focused lint pass. Fresh installed-identity proof plus
11/11 Room rollback/ACK controls pass. Debug/test build, release Kotlin and both
lints PASS in 2m1s (104 tasks, ten executed); unchanged JVM task is UP-TO-DATE,
reusing the recorded 914 PASS / one optional SKIP, not a fresh JVM execution.

App remains byte-identical `64d40b75180873a339413460b5af1455a32c32ac24096577777d3a725ec822d3`;
new test APK `ad0bb28f57bc5778cab306b4dfc98a579a1ceb36a5f50d7996c549d39ce1f5e7`,
source snapshot `261bda2e3022f6bd7b1bdcfe640b07c41e520afc06772a3e16b10a45716063c2`.
Inputs frozen, ports free, reverse mappings empty and instrumentation idle at exit.
Evidence: Android `artifacts/release27-engineering-20261002/offline-full-test-build.log`
and `collection-session-room-222722-9c8f340d/manifest.json`; Web
`outputs/collection-sync-session-local/2026-10-01T22-27-50-753Z-8fc2d585e11e4d0881894a53d65a83f2/`.
Fresh broad Web regression: **1285/1285 in 78 files**, 34.68 s,
`outputs/release-local/2026-10-01T22-30-01-188Z-19892.{json,log}`; existing
act/Canvas harness warnings remain. Fresh fixed Android regression on these exact
APKs: **371/371 PASS**, zero skips/failures, 316.390 s instrumentation,
`artifacts/android-regression/20261001T222953Z-34f463c1/manifest.json`; frozen inputs
and final offline/no-reverse/idle checks pass. No live cloud, signing, release build or Xiaomi.
Overall NOT READY; this closes this narrow offline proof, not all remaining gates.

### Previous local checkpoint: current parser source passes isolated R8

Source commit `2dce825` now passes a fresh offline R8-only run after the parser
pattern reuse. The reviewed guard allows exactly 32 task paths ending at
`minifyReleaseWithR8`, requires absent release credentials/configuration and
isolates all new build output. Dry run PASS; actual **BUILD SUCCESSFUL in 3m36s**,
30 actionable tasks (28 executed, two up-to-date), no build/configuration cache.
Existing lifecycle deprecations remain; no missing-class or R8 warning appears.
No release/build/dependency setting or conservative R8 rule was changed.

All five DEX headers, file lengths, SHA-1 and Adler32 checks pass. R8 9.3.16,
minAPI26; mapping **104,555,109 bytes**, SHA256
`1a29ff1f5b8258ccca254616c11feaa9cd4b6723714438558ccf1f399b8286fa`,
map ID `2b1d9746619e76b65cbfdcd34f06fb9aa59c9da3b3312c35f8596e883b3788f7`.
This diagnostic mapping is NOT for v26 or any other existing upload; a later
authorized final artifact needs its own matching mapping and package validation.

Before/after all **441 input files**, **13 schemas** and **20 existing output/
release files** (574,422,629 bytes) match exactly. The v26 signed AAB, its mapping
and partial native ZIP also individually match their original immutable hashes.
The isolated output contains **zero APK/AAB files**. Evidence:
`artifacts/release27-engineering-20261001/r8-only-parser-regex/` includes the exact
guard, dry-run/build logs, snapshots, DEX verification and preservation report.

This is current-source R8/DEX validation, not a packaged runtime, native-symbol,
retracing or Play acceptance test. No release signing, deployment, production or
Xiaomi operation. Overall **NOT READY**; remaining mission gates still apply.

### Previous local checkpoint: immutable parser-pattern reuse, exact old-output parity

Six existing constant Regex patterns are now private immutable values instead of
being constructed at seven per-line/per-token sites. Literal patterns, flags,
contains/replace/split/matches operations and input order remain identical. No
Matcher, result, registry response or capture is cached. Reversing only those
declarations and uses in memory reproduces the complete original file SHA256
`2efb2b0cec6ad50e93cb6e3ec9bdc676f27c1292eb42f2c4281959e9ba7df0a8`;
new file SHA256 `48718d226c03fcfcadaada6bba4b302e75d0fe46fb65a7d2650e56deec34f356`.
No callback, pixel, threshold, timeout, SDK, synchronization or permission changed.

The actual old parser was captured BEFORE the product edit, then independently
passed its frozen control before the hoist (test commit `991e99b`). The deliberate
initial missing-golden failure is a capture step, not a product RED. All **960**
ordered synthetic inputs and full results retain the same **1,198,843-byte** framed
snapshot and SHA256 `b39195cd95eaff9566b0b7c866831b0a3fd5e97273b92509d61bfa695bda5a4f`.
Null/empty, map/list element boundaries, float bits and all remaining default fields
are checked. A bounded four-thread control also compares every full result directly
against its sequential counterpart. No second parser implementation or mutable
registry reset is used. This is finite behavioral parity, not OCR accuracy.

Root verification: **914 JVM PASS / one existing optional live-test SKIP**,
134 classes, zero failures/errors. Full debug/test build, release Kotlin and both
lints **PASS in 3m32s** (104 tasks, 24 executed); lint reports 167 debug/170 release
warnings and two hints each, not warning-free. The unchanged actual analyzer
characterization passes **3/3 in 12.304 s**; fixed emulator regression passes
**371/371 in 304.598 s**, no skips/failures. Exact sources/APKs are frozen, installed
identity matches, and final offline/no-reverse/idle checks all verify.

Evidence: engineering `parser-regex-{baseline-capture,baseline-control,after-parity}.xml`,
`parser-regex-full-build.log`, `verify-parser-regex-diff.mjs`, and archived full JVM
results `parser-regex-jvm-results.zip` (SHA256
`e12d7415c85209ca604dd0fa90fe90120b274171d37fee8b568fde63d5ed0fb2`).
Emulator manifests: engineering `scanner-batch-205955-c5b8eb80/manifest.json` and
`artifacts/android-regression/20261001T210045Z-8fdd4467/manifest.json`.
App SHA256 `64d40b75180873a339413460b5af1455a32c32ac24096577777d3a725ec822d3`;
test `1b2c720bdf29c6621befbe8f6f30708af7fbee3c1e481dbb936044d0f56f5336`;
source snapshot `779e3fae28689d57d4b11823aec97fcfc9975e94409db01ff6a2c76ae6ed77d4`.

Proven benefit: repeated pattern construction is removed. No measured end-to-end
speed, allocation-byte count or accuracy gain is claimed. The earlier isolated R8
run predates this hoist; the current-source repetition is recorded above. No release
signing/AAB, production, deployment, Play or Xiaomi action. Overall **NOT READY**.

### Previous local checkpoint: actual analyzer batch transition characterization

Three new test-only cases exercise the real analyzer, parser, preprocessing,
Handler deadlines and bitmap ownership with controlled per-session GMS Tasks.
**3/3 PASS**, no skips/failures, **12.235 s** instrumentation: healthy completion;
the original five-second probe deadline with two old and two new submitted tasks;
and close during that overlap with bitmap leases retained until real task completion.
Late counter-identity readings neither change the cache nor contaminate the final
identity, and only one terminal callback is delivered. Actual ART delegate injection,
ordered cleanup and all input-resource releases are verified, not presumed.

This characterizes four unfinished submitted tasks, NOT four native threads or a
measured slowdown. No native OCR, camera or real photo is used; no concurrency,
timeout, recognition or product behavior changed. The scanner architecture now
correctly documents the existing per-batch rather than per-capture two-task limit.
Changing that limit could reduce available recognition evidence under deadlines;
no speculative scheduling fix is included.

The first test compile failed because a raw Java constructor required an explicit
`Text.TextBlock` list type; only the test was corrected. Rebuild: **PASS 2m32s**,
debug/test APK, release Kotlin and both lints; unchanged **912 JVM PASS / one existing
optional SKIP** reused by Gradle. Sources/APKs remain frozen during instrumentation;
offline/no reverse/idle cleanup verifies. Evidence:
`artifacts/release27-engineering-20261001/scanner-batch-204516-52f5b60c/manifest.json`
and `scanner-batch-build{,-corrected}.log` in the engineering directory.
Source SHA256 `801e2cc655e5b8ec71f86ca8aef51fae31c4019643f928f0752aaf23abf56b33`;
test APK `1b2c720bdf29c6621befbe8f6f30708af7fbee3c1e481dbb936044d0f56f5336`.
App APK stays exactly `58613d94075ea30791e4996c791ed5aedd9f960f97326aea66d0d3f5ddbdf958`,
already covered by the earlier 371-case regression. No release signing, AAB,
production, deployment, Play or Xiaomi operation. Overall **NOT READY**.

### Previous local checkpoint: current-source isolated R8 verification

The same reviewed R8-only guard now verifies source commit `e9b2627`, including
the reconnect fix. Its exact 32-task graph ends at `minifyReleaseWithR8`; no
signing input/configuration, package/bundle/install task or release artifact is
allowed. Offline, no build/configuration cache, fresh isolated output directory:
**BUILD SUCCESSFUL in 3m44s**, 30 actionable tasks (28 executed, two up-to-date
profile/generated-rule merge tasks). Existing lifecycle deprecations remain;
there is no new R8 missing-class warning. Conservative R8 rules stay unchanged.

R8 9.3.16/minAPI26 emits a fresh **104,554,056-byte mapping** and five DEX files;
all lengths, headers, SHA-1 and Adler32 checks verify. Mapping SHA256
`836ba0ac499ec294fbacbbdd91ad8772394473d42739a540ab889c5773908b5b`;
map ID `bc25bf597b0fab6119bbdaf2bb0f695d922e83db91aded640d9f04f379918375`.
Before/after **439 inputs**, all **13 schemas**, and **20 previous build/release
files** (574,422,286 bytes) match exactly. The immutable v26 AAB still matches its
Gate-0 hash. Evidence: `artifacts/release27-engineering-20261001/r8-only-20261001T2030/`
with guarded init, logs, snapshots and `verification.json`.

This is current-source R8/DEX integrity, not packaged runtime, retracing, native
symbols or Play acceptance. The diagnostic mapping must NOT be uploaded for v26
or any other package. A future authorized final build needs its matching mapping.
No APK/AAB signing/packaging, product/build configuration, deployment or Xiaomi
change. Overall release **NOT READY**; remaining gates are unchanged.

### Previous local checkpoint: real deferred point read across Room reopen

A test-only addition joins real named SDK SERVER point reads and the existing
unwired private-pull Room adapter. Admin seeds a valid parent/binder/placement;
only local Collection delivery is delayed. The actual adapter records its typed
dependency marker, closes/reopens a uniquely owned SQLite file, reserves that
same marker and retains it after another blocked recheck. The existing SDK decoder
and Room apply then deliver the real parent. A fresh unchanged placement read
applies exact slot/holo/identity/revision and atomically removes the marker.
Checkpoint revision advances; scope, epoch and cursor phase remain unchanged.
No dummy parent, remote write, outbox ACK or active delta rollout is introduced.

**11/11 actual SDK cases PASS** (87.918 s complete harness; new case 3.213 s),
including all previous ten, with eleven complete Admin preflights/readbacks and
twelve exact nominal Google accounts. All three new seed payloads/timestamps and
previous owners remain unchanged. Exact marked test DB/sidecars are removed only
after close and read-only ownership verification. Sources/APKs and cleanup checks
all pass. This proves file close/reopen, not process death, general query/time
provenance, active worker scheduling or end-to-end multi-device convergence.

Evidence: Web `outputs/collection-sync-session-local/2026-10-01T20-25-48-822Z-ccab0ba13ece438187f1e58ac19444e3/`;
Android `artifacts/release27-engineering-20261001/collection-session-room-202521-6b8fb4ba/manifest.json`.
Debug/test build, release Kotlin, JVM selection and both lints PASS (1m30; unchanged
912 PASS/1 optional SKIP JVM results reused by Gradle), 11 Room controls PASS,
14 host validators/focused lint PASS. App APK remains exactly
`58613d94075ea30791e4996c791ed5aedd9f960f97326aea66d0d3f5ddbdf958`,
already covered by the prior 371-case regression; it was not rerun for this
test-only change. New test APK
`230d51dff73bd2e52d308c0e98b3f03ccf8f19dcbba29690c649b2bc5fc2dd46`;
source snapshot `10553f87faccf3ace1d974a9e5f4b23678c4913788e245e6baa2995b3fc5a485`.
Before installation the earlier completed/cached own test process was identified
by exact APK hash and idle instrumentation, then stopped; no unrelated process or
user data was touched. No product/rules/release/signing/deployment/Xiaomi change.
Overall **NOT READY**, including the wider Gate-4 matrix.

### Previous local checkpoint: bounded event-driven session recheck

An unconfirmed LOCAL_ONLY session now requests the existing real SERVER
entitlement verification when its current Android default network becomes both
INTERNET-capable and VALIDATED. The callback never grants access itself. Repeated
capabilities and already-active/denied/signed-out sessions do not trigger new
checks; a usable edge during an in-flight check permits at most one follow-up
after a transient failure. Generation, owner, close and listener/scheduling
boundaries remain guarded. Registrations are owned per session and closed outside
the entitlement lock. No polling, new permission, schema, Rules, outbox, worker
policy, dependency or release setting changed. The earlier negative SDK control
retains a no-op connectivity source; it still characterizes the original case.

Root verification: **912 JVM PASS / 1 existing optional SKIP**, 133 classes;
debug/test builds, release Kotlin and both lints PASS (**3m 48s**). Thirteen new
pure cases cover the bounded retry gate and default-network event policy.
**11/11 Room controls**, **10/10 actual SDK cases**, and the unchanged fixed
**371/371 emulator regression** (297.779 s, no skips/failures) all PASS with the
same installed APKs. The new actual SDK case recovers using a synthetic usable
connectivity signal, without requestSync; actual identity, SERVER entitlement,
Room and listeners remain real. Healthy duplicate bursts, retained callbacks
after close and another start cannot reactivate it. This is not proof of Android
OS network delivery, automatic WorkManager execution, physical-device latency or
process restart. Twelve host validators and focused host lint pass separately.

Evidence: `artifacts/release27-engineering-20261001/session-reconnect-fix-build.log`;
`artifacts/release27-engineering-20261001/collection-session-room-200651-0aa74074/manifest.json`;
`artifacts/android-regression/20261001T200922Z-fd7834dc/manifest.json`;
Web `outputs/collection-sync-session-local/2026-10-01T20-07-42-715Z-a417bd8b48954918bb8cf634c6c906ea/`.
All ten complete independent Admin preflights/readbacks, eleven nominal accounts,
unchanged prior/diagnostic owners, cleanup and frozen inputs verify. Final emulator
state is offline with no reverse mappings. APK SHA256:
app `58613d94075ea30791e4996c791ed5aedd9f960f97326aea66d0d3f5ddbdf958`,
test `ea5bc02b4a59ba64045d41376b3362014360c634729d3df4d56315ed45c1cac7`;
source snapshot `19a2bec0ba86e910ac86bd1e3574a31ae0fc9b732313e39470e62e2b699ec124`.
Release-26 AAB/mapping/partial-symbol hashes remain unchanged. No release signing,
APK/AAB packaging, deployment, Play or Xiaomi action. The earlier isolated R8
proof predates this code; it is not a current artifact proof. Overall **NOT READY**.

### Previous local checkpoint: actual offline-start session characterization

A mechanical per-session scheduling adapter preserves all six existing production
effects and makes the actual session safe to exercise with named demo SDK clients.
It does not replace authentication, owner checks, server verification, listeners,
Room or Rules. No connectivity fix is included in this checkpoint.

All **9 actual local SDK cases PASS**, including the original seven worker cases.
A fresh session started with its named Firestore transport disabled stays
unconfirmed/local after transport is re-enabled and real SERVER reads succeed.
No Auth/generation change or activation occurs in the bounded two-second window;
the same session's explicit `requestSync()` then genuinely verifies and activates.
This reproduces a missing session recheck in that controlled case, not a permanent
block, Android OS connectivity delivery, process cold boot or a latency SLA.
The healthy-session control activates without changing the pending Room fixture.

Independent Admin preflight/readback checks cover all nine methods, ten nominal
Google accounts and the complete server tree. Both diagnostic owners stay empty;
all eight observed Room tables and exact pending entry/outbox remain unchanged.
The owned local servers stop, ports/reverse mappings are empty, instrumentation is
idle and frozen inputs stay unchanged. Twelve pure host validators pass separately.
Evidence: Web `outputs/collection-sync-session-local/2026-10-01T19-53-27-370Z-c302ec6569884feb9b8e423dcb94cf5b/`.

Root also verifies **899 JVM PASS / 1 existing optional SKIP**, 131 classes, debug
and test builds, release Kotlin and both lints (**4m 17s**), plus **11/11** actual
Room session controls. Installed APK identity and cleanup are recorded in
`artifacts/release27-engineering-20261001/collection-session-room-195240-3b974eef/manifest.json`:
app `259b5319722a1e3d9a949f1e21e74f8ce90666953866c3c0644593329d5acd2b`,
test `3028b9ac412af64e76f9aa25d792d504334f8946cb8b43d5c0ce85c469f8548e`.
Only the offline API-36 emulator was installed. No production, Xiaomi, signing,
release APK/AAB or deployment. Automatic recovery remains the next scoped fix;
the wider release remains **NOT READY**.

### Previous local checkpoint: isolated R8 execution without release packaging

On source commit `345bf63`, the existing release R8 configuration was executed
offline in a new isolated app build directory. The reviewed init guard permits
only the exact 32-task dependency graph ending at `minifyReleaseWithR8`, rejects
signing inputs/configuration and verifies the output directory. No credentials,
upload helper, assembly/bundle/installation task, release APK or AAB were used.
Build and configuration caches were disabled; **30 actionable tasks executed,
BUILD SUCCESSFUL in 3m 45s**. The two existing Compose lifecycle deprecation
warnings remain; no new R8 missing-class error or warning was reported.

R8 **9.3.16**, minimum API 26, generated a fresh **104,526,819-byte mapping** and
five DEX files. All five DEX header lengths, SHA-1 signatures and Adler32 checksums
verify. The merged configuration preserves `-dontshrink`, `-dontoptimize`, the
OpenCV `CvException` keep rule and the three existing optional OkHttp2 warnings.
The usage report remains empty; MainActivity and CvException names are retained.
This validates actual conservative R8 execution, not installable packaging,
device behavior, end-to-end retracing, native symbols or Google Play acceptance.

Before/after integrity matches for **433 input files**, all **13 schema files**,
and **20 prior build/release-26 files** (574,373,840 bytes). Even the older mapping
and AAB under normal `app/build/outputs` were left intact. Verification is saved
under `artifacts/release27-engineering-20261001/r8-only-20261001T1933/`:
`r8-only.init.gradle`, dry-run/build logs, `before.json`, `after.json` and
`verification.json`. Mapping SHA256:
`ad1affe17b4c1dba7569ed72bf16965238300369df5f6f7ab72835890008203b`.
**Do not upload this diagnostic mapping against Release 26 or another artifact.**
A future authorized release build must supply its own exactly matching mapping.
No product/build settings, release signing, deployment, Play or Xiaomi changes.
Overall release status remains **NOT READY** for the other documented gates.

### Previous local checkpoint: equivalent chronological portfolio history

The historical valuation now groups original, individually quarantine-checked
holdings by product and normal/reverse finish, then processes chronological price
events once. Invalid/null/zero prices still replace earlier values with zero;
same-time last-event semantics, all original timestamps, UTC labels, quantities
and wrapping Long arithmetic are unchanged. Current chosen alternatives are not
rewritten into historical trends. DAO queries, schema, sync and price policy stay
unchanged. Expected history arithmetic is linear in rows plus price events; the
surrounding card projection still retains its existing per-product history groups.

Eight new JVM cases pass, including 400 deterministic small histories against an
independent per-original-row BigInteger oracle. The complete debug/test build,
release Kotlin compile and both lints PASS in 3m 29s: **899 JVM PASS, one existing
optional audit SKIP, zero failures**, 131 classes. This is not an R8/signed build.
The unchanged fixed emulator selection passes **371/371**, no skips/failures,
283.714 s. It includes real Room annual 365-date corrections and owner boundaries.
Two additional full-field Room projection controls also pass. Sources/APKs remain
unchanged throughout the verified offline runs.

The same 1k/5k/20k two-date scale fixture passes all 87 measured samples, all
15-table/per-column hashes match the prior run and query counts are unchanged.
Three warm repeats per size do **not** establish a wall-time speedup: portfolio
medians are 53.84 -> 49.62 ms (1k), 161.75 -> 165.60 ms (5k), and
1172.48 -> 1222.46 ms (20k). Unchanged private-contents work also varies
1164.75 -> 1230.79 ms at 20k. These are descriptive emulator observations, not
proof that the whole app became faster, a causal regression verdict, a year-long
benchmark, network latency, UI responsiveness, or physical-device performance.

Evidence: `artifacts/release27-engineering-20261001/history-sweep-{build.log,final-unit/,final-regression.log,scale-comparison.json}`;
`artifacts/android-regression/20261001T190735Z-f7c6f309/manifest.json`;
`artifacts/release27-engineering-20261001/card-projection-review/projection-control-20261001T191334Z-f6e9afb4ffc048bc88f120fddaac76f9/manifest.json`;
`artifacts/release27-engineering-20261001/room-scale-current-20261001T191348Z-6caf9828293c4545bb599db7e120f89b/manifest.json`.
App SHA256 `b590e53c722e16c4dae56d5ef8c8565291497991038d2e3c367e457efedd0c6c`;
test APK `a7bb873e80a044fe5b608ede46a1091fcee48987b637575ee603caf207866df9`;
source snapshot `6863da22889cddb91bac12b871cfb5ed70d8e55e82c465a2edef646ea74d8e42`.
The cached, completed own test process was verified and stopped before the extra
controls; no foreign process or app data was removed. Release 26 AAB/mapping/partial
symbols retain their Gate-0 hashes. No release signing, deployment or Xiaomi action.
Overall **NOT READY**; remaining external and full-mission gates are not closed.

The next Web-only checkpoint also verifies the actual unchanged `addExactCard`
through real local Google/Firestore clients: exact create/increment and rejection
of a different positive product at the same UUID with empty/stale caller state.
The existing suite passes **33/33**, all old 31 cases retained, with complete
independent Admin state, exact error and owned cleanup checks. No Web product or
Rules change was required. Pure starter checks 17/17, typecheck/lint and a fresh
1285/1285 Web regression pass. Evidence in the separate Web repository:
`outputs/private-operations-local/2026-10-01T19-19-46-837Z-635450bc/` and
`outputs/release-local/2026-10-01T19-21-37-030Z-13684.{json,log}`.
This is not browser UI or automatic Android WorkManager convergence evidence.

### Previous local checkpoint: genuine pending overlay and late-parent recovery

Two test-only additions extend the existing collection-session SDK harness to
**7/7 PASS**, seven full independent Admin preflights and seven readbacks, eight
nominal Google/appAccess-only accounts. Real pending Source.SERVER data decodes
successfully but cannot discard the resumed outbox checkpoint; after its actual
ACK the worker converges exactly and remains idempotent. An unchanged orphan
placement is recovered by the real private worker after an independent nominal
client creates its parent. No product or Rules change was necessary.

The orphan is deliberately Admin-seeded legacy/resilience state in a fresh demo
database, not a normal Rules-allowed client create race. Both actual worker passes
are invoked by the test; WorkManager scheduling, UI latency and delta activation
remain unproven. Details: `docs/SYNC.md` and the Web harness README.

Web evidence: `outputs/collection-sync-session-local/2026-10-01T18-53-04-230Z-52fcb2bc3e124af5be3ea075fe3cd920/`.
15.317 s summed Android test time; 51.187 s complete local harness. Input hashes,
free ports, empty reverse mappings and idle instrumentation all verified.
Ten separate pure validator cases and focused host lint pass. Debug/test build
passes in 33s; 11 additional existing Room session-commit cases also pass.
Installed APK identity proof:
`artifacts/release27-engineering-20261001/collection-session-room-185032-bbcdb47a/manifest.json`.
App remains `b85d6a5bb0a90fbef91ecadb88c913b2a4e9282027311a8907f91b8111931cf4`;
new test APK `a7bb873e80a044fe5b608ede46a1091fcee48987b637575ee603caf207866df9`.
No signing, release artifact, production change, deployment or Xiaomi use.
Overall **NOT READY**; these scoped proofs do not close the wider Gate 4.

### Previous local checkpoint: equivalent collection projection, measured conservatively

The Room collection mapper constructs each card once instead of constructing and
copying it. Portfolio history no longer re-sorts the already chronologically
ordered DAO projection; grouping/distinct preserve that order. Queries, price
selection/source checks, owner boundaries, schemas and sync behavior are unchanged.

Two new real in-memory Room tests check every projected card field, alternate-price
to real-trend transitions, saved selection retention, wrong-source rejection,
out-of-order insertion/history valuation and owner A -> B -> A. Both pass before
and after (0.860 s / 0.701 s), using an independent full-field expected model.
These are value-parity tests, not speed assertions. Evidence:
`artifacts/release27-engineering-20261001/card-projection-review/projection-control-*`.

Identical 1k/5k/20k synthetic fixtures produce 87 verified samples in each run,
with all 15 table/column hashes unchanged and identical query counts. Three warm
samples per size are descriptive, not a causal benchmark or SLA. For example,
5k portfolio median is 168.69 -> 161.75 ms, but 20k is 1170.45 -> 1172.48 ms;
20k collection is 833.19 -> 860.13 ms. **No material large-binder wall-time speedup
is established.** The justified improvement is removal of redundant work, not a
promised end-to-end latency gain. Full comparison and manifest references:
`artifacts/release27-engineering-20261001/card-projection-scale-comparison.json`.

Full debug/test APK build, release Kotlin and debug/release lint PASS (3m 25s);
891 JVM PASS, one optional dated-audit SKIP, zero failures, 130 classes. The fixed
offline emulator regression separately passes **371/371**, zero skips/failures,
with source/APK hashes unchanged and no network/reverse mappings. Evidence:
`artifacts/android-regression/20261001T183659Z-47b91cac/manifest.json`,
`artifacts/release27-engineering-20261001/card-projection-final-regression.log`
and `card-projection-final-unit/`. App SHA256:
`b85d6a5bb0a90fbef91ecadb88c913b2a4e9282027311a8907f91b8111931cf4`;
test APK `54e6f101694c29e71d80e2cf9562be3b7cf4b2d8f28354b6b210e87e97895e78`.

Signed v26 AAB SHA256 remains
`079608e27bcb5d440252ed727e238b162b449f3dba4b1c28986d4a4570d9b14b`.
No signing, release bundle, deployment, Firebase production or Xiaomi action.
Overall release remains **NOT READY**; next evidence concerns actual SDK sync
edge cases, not activation of the unfinished delta rollout.

### Previous local checkpoint: duplicate source declarations must agree

The detail parser now compares all positive raw thirdParty/pricing product
declarations within the exact source-ID/finish/full-descriptor group. Conflicts
block that printing, its cached metadata and maintained-registry fallback. A
single unambiguous thirdParty declaration can fill an otherwise missing duplicate
link; pricing-only evidence can contradict but cannot establish a link. Other
finishes, stamps and descriptors are unchanged; no stored-data migration.

Six real-parser JVM methods cover both source orders, two/three products, healthy
siblings, registry/cache, agreeing duplicates, one missing declaration and split
provider declarations. The prior first-wins positive test now correctly uses
agreeing products; the negative behavior has dedicated failing-before tests.
Full current result: **891 PASS, one optional dated-audit SKIP, zero failures**,
892 methods in 130 classes. Debug/test APKs, release Kotlin compile and debug/release
lint pass in 3m 8s. This is not an R8/shrunk/signed release build. Evidence:
`artifacts/release27-engineering-20261001/cross-row-printing-final-regression.log`
and `cross-row-printing-final-unit/`; targeted RED evidence is retained separately.
The six-method split-declaration addition reproduced one failure before the fix.

Independent reporting policy applies the same raw-evidence disagreement rule
without importing pricing-only links. **26/26** policy/report tests pass; the new
split-declaration test failed before that correction. Snapshot replay separately
ran the real parser/linker/binder enumeration: **one PASS, no skip**, 21.37 s test,
2m 39s offline Gradle run. All 19,738 manifest hashes and 19,748 physical files
still match before/after (aggregate
`9af1f37909e9c5331599655cf26befd8b97ccf6e8aaf674a623d8d24ca07b32b`).

Across **31,615** output printing rows, **31,611 are deep-field identical**.
Exactly three ambiguous links are removed: `sv10-096` Holo/Standard,
`sv03-125` and `sv10.5b-034` Holo/player-rewards-program. Exactly one independently
declared link is recovered: `hgss2-82` Normal/ross-cawthorn -> **868187**. That
product's trend is absent, so it remains unpriced rather than borrowing a value.
All 144 physical sets, 19,463 cards and 27,349 canonical binder slots are retained;
17,867 strictly verified priced slots remain unchanged. Both broader before/after
reports use the same stricter current policy: **20,252 available / 11,363 unavailable**,
zero technical price errors. No live/image/physical-camera coverage is claimed.
Fresh runtime/before-report/report outputs:
`C:/Users/Dominik/AppData/Local/Temp/domidex-v27-price-replay-20261001-1819/`.
Full evidence prefix: `artifacts/release27-engineering-20261001/cross-row-*`.

Web counterpart is locally committed as `e40c158`: 18 source-product regressions,
eight actual add-transaction callback tests with a mechanical SDK double, existing
1,285-unit/103-cache/31-local-SDK suites and isolated build PASS. These eight
transaction-double tests are not claimed as additional real Firebase E2E coverage.
No production write, Rules change, signing, release artifact or Xiaomi action.
The unchanged signed v26 AAB hash was rechecked. The fixed offline emulator
regression passes **371/371**, 341.799 s, exact inputs unchanged, no network/reverse:
`artifacts/android-regression/20261001T181956Z-14e48a44/manifest.json`.
APK SHA256 `f937f0d042f6a2d200ea1a93dff2958c7958b6c2a534cbe0faec4be31c027ed5`;
test APK `489ddb2e66cb276609975aa52b71d0711645cd449796ff977f295731fe2b0a9d`.
Overall release remains **NOT READY**.

### Previous local checkpoint: reused source IDs retain distinct physical printings

Android no longer deduplicates printings by raw ID alone. The existing first
reference ID stays unchanged; additional different finish/descriptor records
receive collision-checked local reference IDs. All actual source IDs are reserved
first, and the raw `sourceVariantId` remains available separately for diagnostics.
Existing private entries, placements, schema and sync identities are not migrated.
The prior per-record identity-conflict guard now follows the full raw-ID/finish/
descriptor identity, so an invalid printing cannot block a healthy sibling.

Ten focused JVM methods cover both source orders, cache/registry reuse, descriptor
differences, source-ID collisions and no sibling/root price borrowing. Initial
combined RED: 12 PASS / seven targeted FAIL; final combined identity cases **20/20**.
Full debug/test APKs, release Kotlin and both lints pass in **3m 40s**;
**885 JVM PASS / one existing optional SKIP**, 129 classes, no failures/errors.
Fixed offline emulator matrix: **371/371 PASS**, 297.546 s, source/APKs unchanged
through invocation and offline/no-reverse checks passed. Only a subsequently
removed stale draft comment differs from the tested JVM source; product code did
not change. Evidence: `artifacts/release27-engineering-20261001/generated-printing-*`
and `artifacts/android-regression/20261001T175101Z-f3ad78a3/manifest.json`.
App APK SHA-256: `191722879590f9388aedbe8d797c16a4b8ef7386f8e91ddd0120978a58494161`;
test APK: `489ddb2e66cb276609975aa52b71d0711645cd449796ff977f295731fe2b0a9d`.

The same frozen 25 September snapshot was verified before/after: 19,738 manifest
records match, 19,748 actual files keep aggregate
`9af1f37909e9c5331599655cf26befd8b97ccf6e8aaf674a623d8d24ca07b32b`.
The actual audit method passes without skip in 14.704 s (Gradle 1m 58s).
It restores **218 previously discarded printings**: 164 in `swsh1`, 54 in
`swsh3.5`. All 31,397 previous printing records retain identical fields after
excluding the new diagnostic runtime-ID field. There are now 31,615 source/fallback
printings and 27,349 canonical slots across 144 physical index sets / 19,463 cards.
Exact runtime links stay 17,903; strictly verified priced slots stay 17,867.
The 218 restored forms have no exact source product links: **metadata coverage
gain, not invented price coverage**. No new network/image check or UI-wide claim.

Diagnostic matching now uses raw ID + actual finish/descriptor, with unique
declared-ID agreement and fail-closed conflicting/missing metadata. Its independent
policy/report suite is **24/24**, including two newly reproduced reviewer cases.
Both before/after reports were regenerated with that identical stricter policy:
20,252 available prices are unchanged, unavailable rows increase by exactly 218.
Do not compare these figures with the previous looser 20,257-report total as an
app regression. Fresh outputs: `C:/Users/Dominik/AppData/Local/Temp/domidex-v27-price-replay-20261001-1753/`.

Newly exposed follow-up: three source rows describe the same raw-ID/finish/descriptor
with contradictory products (`sv10.5b-034`, `sv10-096`, `sv03-125`). The diagnostic
flags these; Android's pre-existing cross-row first-wins behavior is still under
targeted review. This differs from the earlier within-one-record contradiction
guard. The snapshot still contains no within-one-record positive ID contradiction.

Web counterpart is independently tested in commit `87ef22e`: complete raw
subtype/stamp/foil descriptions, stable ordinary/legacy identity, private-field
exclusion, **1,259 unit + 103 catalog + 31 isolated SDK tests PASS**, isolated build
successful. No deployment. No Xiaomi, signing/AAB, R8, Rules or production change.
Overall release remains **NOT READY**.

### Previous local checkpoint: contradictory printing identities fail closed

The actual Android detail parser now compares a printing's positive
`thirdParty.cardmarket` with its positive `pricing.cardmarket.idProduct`. When
both exist and disagree, that printing remains selectable but neither product
can supply its price. The conflict also survives metadata-cache reuse and cannot
be resurrected by the maintained 30th-anniversary registry. Unrelated printings
remain usable. Root-level IDs/prices are never inherited; pricing-only acceptance,
invalid optional-ID handling and ordinary reviewed missing-link bridges retain
their prior policy. Existing saved collection identities are not migrated.

Ten JVM methods exercise the real parser, registry and printing linker, including
both conflict directions, cache/name reuse, valid Reverse siblings in either
source order, exact decimal/exponent IDs and malformed optional metadata. Initial
six-method RED: four controls PASS, two precise unsafe-link failures. Final
**10/10 PASS**. Full debug/test builds, release Kotlin and both lints pass in
3m 5s; **875 JVM PASS / one pre-existing optional live-source SKIP**. Fixed offline
API-36 matrix: **371/371 PASS**, 291.079 s, no skips/failures, unchanged source/APK
hashes and offline/no-reverse checks. Evidence:
`artifacts/android-regression/20261001T172210Z-2e2f523c/manifest.json` and
`artifacts/release27-engineering-20261001/printing-identity-*`.
Tested app APK SHA-256:
`dfae12e4b09cb98e7de979ae34b14d5362e98f79c8d55d61ce7556dfaa7d36f1`;
test APK: `489ddb2e66cb276609975aa52b71d0711645cd449796ff977f295731fe2b0a9d`.

The complete dated **25 September** public snapshot was also replayed offline
through today's production parser/linker/binder code on 1 October. All 19,738
manifest hashes match actual files; all 19,748 snapshot files retain the same
before/after aggregate `9af1f37909e9c5331599655cf26befd8b97ccf6e8aaf674a623d8d24ca07b32b`.
The executed audit method passes without a skip (11.164 s); Gradle completes in
2m 4s. Both CSVs and every production-printing JSONL row are byte-identical to the
25 September final audit: **144 physical index sets, 19,463 cards, 27,131 canonical
printing slots, 17,903 exact mappings, 17,867 strictly verified priced slots,
9,227 unverified links and one empty mapping**. Of the other 36 exact mappings,
29 lack their matching trend field and seven have prices but lack independent
upstream detail. Do not describe 9,227 unverified links as proven UI price failures.

Snapshot: `C:/Users/Dominik/AppData/Local/Temp/domidex-v27-price-audit-20260925/`;
new outputs: `C:/Users/Dominik/AppData/Local/Temp/domidex-v27-price-replay-20261001-1729/`
(`runtime/` and `report/`); evidence `price-snapshot-*` in the engineering artifact
directory above. The broader report additionally retains special printings
outside canonical slots; its counts are not interchangeable with binder counts.
No new network/image check was run. A successful audit means complete iteration,
not complete price coverage or present-day provider availability. The snapshot
contains zero positive printing-ID contradictions and zero pricing-only IDs, so
this conflict fix has **no demonstrated historical coverage gain**. It is a
defensive identity correction, not a claim that missing prices are all fixed.

No Xiaomi, release signing/AAB, version, schema, R8, Rules or production change.
Overall release remains **NOT READY**.

### Previous local checkpoint: binder metadata without unused price work

`observeBinders` now returns only the five fields its sole repository consumer
uses. Removed are two unused price aggregates and their linked-product/latest-price
LEFT JOINs. Counts, owner/tombstone filtering, names, grouping and sorting remain
identical. Price values still come from `currentCardsFor`, including its independent
raw-price and price-selection observations. The projection is not a Room entity;
schema, migrations, indices, sync, price identities and cloud contracts are unchanged.

Two new real-Room tests keep one live repository subscription through quantity,
name, ordering, move, deletion and A→B→A changes, and through trend/alternative/
clear/raw-price-only changes. They check Reverse Holo isolation and unchanged
foreign/tombstoned entries. The executed SQL is inspected only after the first
test's functional controls. Before the fix: one control PASS and one precise
`BINDER_METADATA_UNUSED_PRICE_AGGREGATES` failure. After: **2/2 PASS**.
Evidence under `artifacts/release27-engineering-20261001/`:
`binder-metadata-room-163449-612bbb77/` (RED),
`binder-metadata-room-163953-c0d94222/` (GREEN).

Actual before/after scale runs use the same synthetic in-app Room fixture,
1k/5k/20k entries, one warmup and three warm repetitions. Each run passes 87
measurements and all before/after full-column table-integrity assertions.

| Entries | Set-binder first emission before (median ms) | After (median ms) | Process CPU before/after (median ms) |
|---|---:|---:|---:|
| 1,000 | 41.31 | 45.36 | 69 / 71 |
| 5,000 | 137.01 | 133.94 | 200 / 189 |
| 20,000 | 967.91 | 970.42 | 1,209 / 1,154 |

**No material end-to-end loading-speed improvement is demonstrated.** The 1k
wall-time median is higher, the 5k median slightly lower and 20k essentially
unchanged. CPU samples at 5k/20k are lower, but three consecutive repetitions
are not a statistical/production guarantee. This is time to the first repository
emission on a fresh subscription in a warm process/SQLite environment, including
mapping/combine work, not isolated SQL, UI, cold-start, scanner or network latency.
The fixture has 5% tombstones, three foreign sentinels, two price snapshots per
card and no saved alternative-price rows; the two live tests cover those separately.
SELECT count remains three; fewer joins do not mean fewer Firestore reads.

Scale evidence: `room-scale-current-20261001T162603Z-3307e465f17146bdac153b2fc6ba5717/`
and `room-scale-current-20261001T164022Z-6c5da26bfa9e4462996936cb28e86f13/`.
The pre-fix debug artifacts were restored after a separately observed Android
Studio build completed; source was unchanged and both installed hashes were
verified before the measurement. No binary reproducibility claim is made.

Final combined working-tree verification: **371/371** offline API-36 regressions,
284.224 s, no skips/failures (`artifacts/android-regression/20261001T164219Z-11fc31b3/manifest.json`).
JVM **865 PASS / one pre-existing optional live-source SKIP**, no failures/errors.
Debug/test APKs, release Kotlin compilation and both lints pass in 3m 51s;
`binder-metadata-green-regression.log`, preserved XML in `binder-metadata-green-unit/`.
Tested app APK SHA-256: `8c2198ac5eeae63bfa9f18b8bb57f5c1f41d7223660227ffeb3fb4e419ee8dec`;
test APK: `489ddb2e66cb276609975aa52b71d0711645cd449796ff977f295731fe2b0a9d`.
The combined tree includes the separately scoped four-error-log minimization.
No Xiaomi action, release signing/AAB, production change or deployment. Overall
release remains **NOT READY**; the wider documented gates are not closed by this fix.

### Narrow error-log privacy hardening

Four `CardVaultApp.kt` error sites (master-set index, master-set variants, exact
product link and manual-card persistence) now emit their existing fixed message
and exception class only. They no longer pass a Throwable to Android logging.
Tags/levels, cancellation handling, user messages, retries and return paths remain
unchanged. This prevents those sites from inadvertently forwarding provider,
parser or database exception messages/causes/stacktraces into logs; no actual
production secret disclosure was established. It is not a whole-app log-retention
or observability proof. The combined build, lint, JVM, two live Room tests and
371-case emulator checkpoint immediately above include these exact four changes.

### Previous local checkpoint: confirmed rereads and bounded shared-view refresh

The separately reproduced missing/malformed collection reread no longer removes
its durable checkpoint. Both actual worker reread paths require an existing,
non-cache/non-pending, successfully decoded server document. Normal revision,
tombstone, M2 and session semantics remain. Local SDK RED had three controls PASS
and two exact checkpoint-loss failures; GREEN is **5/5 actual SDK cases**, five
independent full Admin readbacks, unchanged accounts/payloads and clean final
ports/reverse/instrumentation. Eleven real Room cases also pass. Exact evidence
and installed APK identities are in `docs/SYNC.md`; Web harness commit `4e0b74e`.

Final Android regression: **371/371**, no skips/failures, 281.081 s on the fixed
offline API-36 emulator. Evidence:
`artifacts/android-regression/20261001T161521Z-75af8b11/manifest.json`.
JVM **865 PASS / one pre-existing optional live-source SKIP**; debug/test APKs,
release Kotlin compilation and both lints pass (3m 13s). No release signing/AAB.

Web commit `6782c59` fixes automatic-refresh starvation: unfinished fetch/JSON is
not superseded by the 15-second tick, and a per-request 30-second local deadline
clears old content and permits retry. Explicit retry/visibility and context
cancellation remain latest-wins. RED: 1,204 PASS / four targeted failures; GREEN
**1,208/1,208**, typecheck/lint PASS, isolated build PASS (13.181 s), root dist and
config unchanged. This is not a server-abort/cost guarantee or instant revocation.
No deployment, production Firebase, Xiaomi or signed Release-26 changes. Invalid
permanent server data still needs repair. Overall release remains **NOT READY**.

### Previous local checkpoint: collection-worker session and cancellation guards

A real local SDK RED proved that observed A→B→A let the old collection worker
accept its delayed transaction ACK and delete its outbox. Its healthy control
passed. The actual worker now uses one generation-bound session lease, guards
both successful and failed network completions, and commits every local ACK,
failure, normalization, discard and reread under a final Room session fence.
An additional actual Room RED (10 PASS / 1 FAIL) exposed cancellation at the
final predicate; checking the captured caller Job there closes that boundary too.

Final results on the fixed offline API-36 AVD: **371/371 existing regressions**,
**11/11 new real Room cases**, **2/2 actual Firebase SDK cases** with independent
full server readbacks. JVM: **865 PASS / 1 existing optional live-source SKIP**.
Debug/test builds, release Kotlin and debug/release lint pass. The sibling Web
test launcher has six passing validator cases and lint; commit `525a865`.
Exact RED/GREEN evidence, hashes and semantics are in `docs/SYNC.md`.

The existing M1/M2, conflict, revision, listener, entitlement, outbox, schema,
Rules, sharing-priority and scheduling policies remain. No production service,
Xiaomi, release signing/AAB or deployment changed; signed Release 26 remains
byte-identical. These are local worker/Room proofs, not WorkManager/physical-device
scale or a production speed claim. The missing/null-decoded reread follow-up was
subsequently corrected as documented above. Overall release remains **NOT READY**.

### Previous local checkpoint: FULL-pull fairness and viewer cancellation

The existing four-group FULL scan now rotates after each bounded page instead
of draining the first group indefinitely. Rotation and EOF evidence persist in
the existing owner-local metadata keys; EOF cursor clearing is one guarded Room
transaction. No Room/cloud schema, query order, Rules, auth entitlement, outbox,
delta protocol or sharing priority has changed. Partial group cursors survive a
completion milestone. This milestone means each group reached EOF at least once,
not a simultaneous server snapshot. See `docs/SYNC.md` for exact read-cost traces:
the first 1,000-row milestone costs 1,601 logical reads, not the old 1,004 bound.
Do not infer a universal cost saving or live latency improvement.

The original growing-first-group RED remains preserved. Focused JVM tests pass
46/46; full JVM tests pass 865 with one pre-existing optional live-source skip.
Debug/test APKs, release Kotlin compilation and debug/release lint pass; no release
APK/AAB was built or signed. Independent source reviews and actual execution now
agree: fixed **371/371 emulator tests** plus **5/5 actual-Room metadata tests**
pass without skips. Baseline: `artifacts/android-regression/20261001T151140Z-a0aa117d/manifest.json`.
App APK SHA-256: `2ac471a4a693702ae8bf74d905c6c207e2f9116f9055953be302b3dc6deb77ef`;
test APK: `f81c2d4a97dbabb2e5ad63fd45a71d59945067a9143ebb4f70d0ca7a95cc5909`.
The actual Android/Web price roundtrip and both cloud/local conflict decisions
also pass on this installed build, with independent server readbacks and complete
owned-fixture/input/port cleanup checks. Exact evidence paths are in `docs/SYNC.md`.
They are not proof of real-cloud latency, full WorkManager scheduling or large-scale
SDK convergence. Xiaomi and production remained untouched.

Sibling Web commit `b8d3d95` binds manual shared-binder retries to the existing
page/share cancellation signal. The reproduced baseline was 1,199 PASS / 2 FAIL;
the final full suite is 1,201/1,201 PASS with typecheck, lint and isolated build
PASS (19.304 s; original dist/config unchanged). Polling cadence and permissions
are unchanged; overlapping slow automatic refreshes remain documented as open.
No server-work cancellation or reduced Firestore billing is claimed. Nothing deployed.

### Previous local checkpoint: public catalog execution bound

The sibling Web cache now limits actual public catalog loader promises, including
uncached overflow: 32 active / 512 FIFO queued per instance. Original deadlines,
dedup/cache budgets, price identities and private authorization remain unchanged.
Active cancellation cannot release a permit until its underlying loader settles.
Overload remains uncached unavailability, never a fabricated value. The historical
480 simultaneous synthetic provider calls are now bounded to 32 in the same 80-reader
fixture; this is not a socket, heap or cross-replica limit.

Root compared 16/32/64 configurations. Default 32 is a conservative compromise,
not a proven production optimum or unconditional speedup. Full measurements,
including longer burst waits than unbounded execution and outstanding O(N) private
reads, are retained in Web `tests/sharing-concurrency/README.md`.
Final checks: 103/103 cache/body/package cases, 12/12 actual-handler concurrency
cases (10/40/80 readers, three repetitions), 1,199/1,199 full Web unit cases,
typecheck/lint and isolated production-mode build (13.058 s). Root dist and nine
build/deploy metadata files remain byte-identical. No deployment, Firebase change,
Android behavior change, release signing or Xiaomi action in this block.

Gate 4 fairness is now independently reproduced: four 1,000-read FULL-pull runs
visit only phase 0 when new binder IDs continually extend that group. Three existing
later rows remain untouched. New JVM baseline: two finite controls PASS, one growing
fixture FAIL. Evidence: `artifacts/release27-engineering-20261001/full-pull-fairness-red.log`.
This adversarial control-flow finding is not a measured real-user failure frequency.
The source correction and bounded device/SDK follow-up verification are described
above. Overall release remains **NOT READY**.

## Scope and safety boundaries

Harden the existing Android client and separate Web/backend without replacing working
offline-first Room, outbox, scanner, authentication or pricing behavior. Work proceeds
in gates; findings and red tests are retained, not relabelled as successful tests.
No production deployment, Firebase configuration change, Play upload, secret rotation,
billing change or release signing has been performed in this engineering run.
Only normal local debug builds are permitted for isolated emulator validation now.
On 2026-10-01 the user separately installed a version-27 debug APK on the Xiaomi
and performed scans himself. Root used read-only package/hash and allowlisted
timing checks only; no installation, camera launch or setting change on that phone.
The user confirmed two cards were recognized correctly and felt fast. This limited
manual observation is not the complete real-device matrix or a controlled v26/v27
performance comparison; those remain NOT TESTED.

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

### Limited user-operated Xiaomi observation (2026-10-01)

The user's installed version-27 / `1.0-closed-alpha.6` debug APK has SHA-256
`1636F9C06A5B1A121E72168F3F4D4E3DBD32A38C751ADA01231ED12628AF3518`, identical
to the then-existing IDE debug artifact. The user clarified that he installed a
debug APK himself and subsequently opened Android Studio. This is not the APK
used for the earlier isolated emulator SDK proof, nor an exact release-26 source
baseline. No release/production artifact or phone setting was changed by this check.

The user reports that both scanned cards were recognized correctly and felt fast.
Keep this qualitative result distinct from the redacted diagnostic sample: the
latest three completed worker durations in that sample were 3,741 / 3,525 / 1,725 ms,
including OCR 2,912 / 2,728 / 1,243 ms; terminal states were AMBIGUOUS / AMBIGUOUS /
MANUAL_REVIEW. They are not independently assigned to the user's two test cards or
claimed to be two automatic ACCEPTED decisions. Earlier queued captures showed
additional waiting; worker duration excludes queue wait. These observations do not
establish a controlled speed/recall improvement over release 26 or emulator replays.

Read-only current-process timing extraction retained only allowlisted numeric/status
fields. No raw OCR text, card images, account data or raw device logs were saved.
The bounded local evidence is `artifacts/release27-engineering-20261001/
xiaomi-v27-timing-batch-readonly-20261001.json`. A full physical lifecycle, account,
offline and multi-device regression matrix is still outstanding.

## Gate ledger

### 2026-10-01: fair local retry selection and repeatable emulator checkpoint

The still-unwired delta Room adapter now has owner-isolated, durable round-robin
reservation of up to 50 deferred markers, fixed per-group ID horizons and atomic
schedule-only updates. No active worker, cloud schema, outbox policy or payload is
changed. Six new JVM and 18 actual Room tests pass (Room 0.992 s), including restart,
concurrent reservations, corrupt state, cancellation and account-generation guards.
Two independent source reviews found no remaining blocker in that narrow scope.
Reservation is neither ACK nor an exclusive lock; later reads/apply still need trust
and exact-marker CAS. Details and preserved first test-source compilation failure:
`docs/SYNC.md`. It does not complete Gate 4 or prove lower network latency.

Fresh debug/test build and lint pass in 1m 9s after correcting that test-only typo.
The complete JVM output contains 854 cases in 125 suites: **853 PASS, 1 existing
live-source opt-in SKIP, 0 failures/errors**. Retained XML evidence:
`artifacts/release27-engineering-20261001/deferred-schedule/full-unit-results/`.

The versioned `scripts/verification/android-regression/` runner passes **24 pure
contract tests**, then its read-only preflight and actual **371/371 PASS**, 272.227 s,
0 skips/failures. Evidence `artifacts/android-regression/20261001T061326Z-65675803/`:
manifest Exit 0, input hashes unchanged during the run, offline/no-reverse afterward,
and exact installation receipts. Debug SHA-256
`845B6F6F0DCA89D3F0D3A0551467EA7E6B9C08D205571009829669B094C73FBE`;
test SHA-256 `9E0893E222BE95B9EDDCA9729032D4C5B35576E0D1B60F30B6D9331C93867491`.
Both were installed only on the named isolated emulator, not the Xiaomi.

The runner does not build, sign releases or contact production. Two selector/dump
issues and a cleanup-ownership issue were caught and corrected before its first run.
Actual API-36 arguments were opaque in the process dump, so token-based emergency
package cleanup is not available in that observation; it fails closed rather than
stopping an unproven process. README states this limitation. Source hashes do not
independently prove reproducibility of an arbitrary old APK. Release-26 AAB, mapping
and partial-symbol archive were rehashed and remain identical to the immutable table.

Next source finding to reproduce separately: the active ID-based full pull can
keep following newly appended lexicographic tail IDs before visiting later groups.
No fixed-horizon change or delta activation is claimed in this checkpoint.

Subsequent check: that starvation was reproduced, but the proposed descending-ID
horizon failed the actual SDK/emulator query test (three existing cases passed,
the new case failed). Root preserved the rejected source and evidence and removed
only the uncommitted horizon experiment. The prior ASC path and all earlier fixes
remain. No index/schema/rules/deployment change was attempted. See `docs/SYNC.md`;
the failed attempt and its synthetic passes do not close Gate 4.

### 2026-10-01: accurate cloud status for held price conflicts

The real Room status aggregation falsely reported ACTIVE for an unresolved private
price-selection conflict: that row is intentionally absent from the pending queue,
but was also missing from the error check. The actual session now uses a shared
queue reader with an owner-isolated EXISTS over the exact existing held-conflict
predicate. No price mutation, conflict resolution, schema or network change.

Root RED: four passed / one failed (`ERROR` expected, `ACTIVE` actual). Final GREEN:
nine focused Room tests pass in 0.461 s, including real explicit local/cloud
resolution and unchanged owner/type/marker boundaries. Build, debug lint and
853 JVM cases pass; one existing opt-in skip remains. The fixed 371-case emulator
regression passes in 256.329 s, with unchanged inputs and exact APK installation.
Existing actual local SDK overlay/transaction cases pass 3/3 in 2.813 s with
independent server verification and complete cleanup. Paths, hashes and limitations
are in `docs/SYNC.md`. This does not close the full Android/Web convergence gate.

### 2026-10-01: actual Android / Web price-choice integration proof

The actual worker pass is shared without replacing its authentication, server
entitlements, session/ACK/conflict guards or scheduling decisions. Two independent
local runs pass all five phases: real Android repository/push/ACK, real Web
subscription/save, Android pull, Web clear, Android pull. Five full Admin readbacks
per run confirm the sidecar and unchanged parent. Same marked Room file across
Android phases; isolated Google-linked synthetic owner, unchanged Rules, no default
Firebase repointing. Separate verified owned-fixture cleanup is not counted as a
price success. Detailed evidence, exact APK hashes and boundaries are in SYNC.md.

Debug/test build, debug lint and 853 JVM tests pass (one existing opt-in skip).
Exact-installed emulator regression: 371/371 in 258.802s; existing SDK proof 3/3 in
3.803s. Six harness-validator tests, Web typecheck and focused lint pass. A separate
full Web regression found two shared-view empty-dialog timing failures (1195/1197
pass). A deterministic commit-phase regression reproduced the closing delay;
Web `e2662f2` fixes only that layout-effect timing. A later owner-form test failure
was an early click while the separate shares GET still disabled creation; its
readiness/invalid-email assertions are now independently proved. No owner-form
product behavior changed. Both UI files pass 22/22; full Web runs pass 1199/1199
twice. Original failures and the RED/GREEN evidence remain in the Web handoff.
The narrow roundtrip does not close scheduling/UI/scale/full-convergence coverage.

Web `e787553` also bounds each decoded DE/EN catalog-card response to 1 MiB and
disposes rejected/aborted bodies without awaiting a hung cancellation. Fourteen
new default-loader cases first produced 4 PASS / 10 FAIL, then all pass; the
complete public-cache selection is 83/83. Exact price/image fallback, private
authorization, guide downloads and existing cache/deadline/concurrency policies
remain. This is not a global heap/transport/concurrency limit. Typecheck, focused
lint and an isolated production-mode build pass (13.973s, 303 source files, demo
environment, root dist/config integrity unchanged). No deployment was performed.

| Gate | Status | Required next evidence |
|---|---|---|
| 0 Baseline | PASS (local reproducible scope) | frozen current source, fresh JVM/instrumentation/scanner timings and isolated Web build; live limits explicit |
| 1 Firebase security | PASS (local rules) | see RELEASE_27_SECURITY.md; full Android outbox multi-device test remains Gate 4 |
| 2 API security | PASS (authenticated write scope) | 773 non-emulator + 30 emulator tests; public IP/token limits remain Gate 6, source-configured limits and deployment limits explicit |
| 3 App Check | PASS (local disabled-rollout preparation) | 15 new JVM + 2 startup emulator tests; 52 backend tests; real enabled attestation/latency and worker ordering remain manual, no live enforcement |
| 4 Collection/binder sync | IN PROGRESS, FULL retained | Current collection/binder SDK cases pass, including durable offline retry, separate-client stale-base convergence and1001-binder FULL/Room-reopen/idempotence (latest18.436s). Whole SDK run remains17/18 RED for the separate Gate9 lock-only case. Actual WorkManager retry1/1 and automatic large-budget continuation1/1 retain isolated-package scopes; exact4WorkSpecs/two cycles/complete local-remote integrity.18/18 focused Room controls remain dated. UI/OS/process delivery, simultaneous physical devices and broader mixed scale remain open. Delta is deferred; see SYNC.md |
| 5 Pricing/catalog | IN PROGRESS | bounded Android import with4/4 real parser/Room persistence/rollback/conflicting-row cases, Web snapshot/identity fixes, seven verified mappings and incremental pricing tested. Conflicting duplicates were not found in the frozen provider download; this hardening does not close full coverage or centralized delivery |
| 6 Shared binder scale | IN PROGRESS | in-flight deletion/ownership, bounded public cache and exact persisted-price/supplement parity tested; per-card body cap/cancellation14/14, cache/package83/83. Missing-trend DTO resolves once instead of twice, full parity verified. O(N) placement reads, total fan-out and trusted-ingress public IP/token limits remain |
| 7 Camera stability | PARTIAL (emulator functional evidence) | Prior virtual rotation/CameraX fresh-JPEG tests retain their scopes. Actual MainActivity PreviewView STREAMING->CREATED/IDLE held1s->RESUMED/STREAMING passes twice13.144/12.801s without capture/queue/upload. New actual recreation/old-view retirement/new-view STREAMING test has1/1 JUnitPASS13.581s with protected-state checks. Overall wrapper failures for cached test-process idle are retained; separately verified owned cleanup is not retroactive whole-run PASS. Physical camera, HOME/OS-death, permission revocation and broader background matrix remain open |
| 8 Recognition | IN PROGRESS (limited replay PASS) | Historical replays retain their scope. Latest owner-confirmed queue triple: Nidorina MEP101 read correctly3times, correct suggestions, two candidates each,4.2–4.7s active plus burst queue delay. Earlier sequential no-candidate observations have unknown ground truth and are not Nidorina failures. No automatic-printing, speedup vs26 or full hardware claim; quality checks unchanged |
| 9 Community knowledge | IN PROGRESS, current expanded SDK run RED | Current17/18 after test-only executor fix: cache/sign-out passes without cleanup crash; lock-plus-valid-data-change gives genuine original-listener denial/null with preservation; lock-only still lacks denial in30s. Executor has genuine5FAIL/1PASS to6PASS regression.45 complete Admin snapshots/CAS restoration pass independently. Earlier16/16/61 synthetic/35 private-operation proofs retain their dated scopes. No fresh data leak or confirmed emulator cause inferred; cached identity-free hints are not forcibly erased by revocation. Eventarc/retry/load and live consent remain open |
| 10 Privacy/lifecycle | IN PROGRESS (local owner/session fixes PASS) | Owner/session and deletion-consent fixes retain prior tests;35 local private-operation cases pass. Read-only metadata partly confirms provider configuration including seven-day Storage soft delete, not live-object TTL. Complete deletion/purge/operator workflow and retention decisions remain open |
| 11 Observability/cost | PARTIAL | Safe-log inventory and hypothetical model retained. Two actual Run revision resource configurations and partial retention metadata read-only verified; Hosting pinTag target, live metrics/alerts/billing and complete inventories remain unverified |
| 12 Load/scale | PARTIAL; bounded portfolio history now compact | Genuine RED/GREEN and current2/2 sustained complete-model +29/29 focused Room proofs. Matched annual58 comparison: portfolio2950.91->1154.10ms for365dates, private45.39->53.79ms (no private improvement claimed). New13-sample full-model/SQL/15-table proof passes; all13 edge states x3owners and20000/20001/20000 fallback boundary pass. Original87-sample1k/5k/20k rerunPASS75.468s. ActualAPI36 multi-window testPASS82.971s, both concurrent commits before refill, all3x100001tuples coherent and15hashrestore. Unsafe states keep full history; no retention/schema/sync change. API26, O(N) sharing/fan-out and production load remain open |
| 13 Full regression | IN PROGRESS, API37.1 RED | API36 app2c9ae510/test443db33e passes371/371, zero skips/failures,305.723s. New stableAPI37.1/16KiB run with unchanged app2c9ae510/test9354bed5 is368/371,3FAIL,0skip,280.558s: one fresh-device CAMERA grant plus two native OCR identity failures; strict cached-test idle also fails. Permission corrected only on owned5560, rerun pending; OCR diagnosis not resolved. Test-only Espresso3.7.0 supports target37 MessageQueue without compatibility bypass. Current SDK17/18 RED remains separate.925 executed JVM PASS/one existing opt-in skip retains its dated scope; later JVM UP-TO-DATE is not fresh proof. Both lints/releaseKotlin pass; no signing/minified runtime claim.1295Web/35private-operation retain dated scopes. Physical/live/final release contracts remain open |
| 14 Database migration | BASELINE PASS | current 12→13 on 1k/5k/20k with all old columns preserved; rerun if later schema changes |
| 15 Release engineering | BLOCKED by earlier gates | no release signing/build before critical gate evidence |
| RC final validation | NOT READY | complete technical report and final explicit recommendation |

## Initial findings (historical; later checkpoints document resolved items)

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

### Reference-artwork callback lifetime: verified local checkpoint

The actual reference-image download helper now retains its existing semaphore slot
until the registered callback has finished body reading and response cleanup,
including cancellation. Factory/enqueue/cancel work runs on IO; cancellation before
factory publication must not enqueue a new request. Callback and enqueue failures
cannot leave the caller waiting for a callback that will never arrive. The existing
OkHttp client, headers, byte limit, cache, descriptor logic and recognition thresholds
remain unchanged. This fixes resource ownership, not a demonstrated overall OCR speedup.

The behavior-preserving extraction first reproduced **4 PASS / 2 FAIL**; the original
six assertions remain intact. Eleven focused lifetime cases and 22 existing artwork/
catalog cases now pass (**33/33**, eight classes). Controlled callback threads and
body/cleanup gates prove lifetime and permit ordering, not physical-camera quality
or unrestricted network cancellation. Exact scope: `docs/scanner-architecture.md`.

Root full offline regression: **848 JVM cases in 124 classes: 847 PASS / 1 existing
opt-in SKIP / 0 FAIL / 0 ERROR**. Debug/test APKs, debug/release lint and release Kotlin
compile pass in **3m40s**, 104 tasks (20 executed / 84 up-to-date). Full XML archived
under `artifacts/release27-engineering-20261001/artwork-http/full-unit-results/`;
log `artwork-http-final-regression-build.log`.

These exact APKs pass **371/371** API-36 emulator tests in **305.139 s**; the same
combined selection as the catalog checkpoint above, with no cases counted twice.
Log: `artwork-http-final-regression371-instrumentation.log`. Debug SHA-256:
`EFC43123536FE34FDE5C244A808B9A846EC6A0F494F178D1682B7E6369666FF3`;
test APK `3B3DE487F96710451B7E3FC19016D392084BC45F34260144C3DD1DB0B3571796`.
Only emulator-5554, airplane mode 1 / Wi-Fi 0. The signed-v26 AAB, mapping and partial
symbol ZIP hashes were rechecked and match the immutable table above. No Xiaomi,
release signing, AAB, production change, deployment or push. This checkpoint does
not cover subsequent SDK overlay tests or activate the prepared delta reader.

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
`data.sync` instrumentation package has **140 PASS / 1 opt-in SKIP / 0 FAIL in
5.348 s**, including those 32 passed cases. Individual status recount corrects the
earlier 141/141 claim: the runner's OK count includes the skipped placement scale
case. Debug/test APK build passes in 38 s. Exact logs, hashes, rollback/reopen
coverage and outstanding SDK/fence/fairness requirements are in `docs/SYNC.md`.
This is not a Firestore worker activation, a network-read reduction or a physical
multi-device result. The following paragraphs retain earlier checkpoint evidence.

Actual Android Firestore 26.5.0 overlay proof now passes **3/3 in 2.962 s**, with
independent host-side verification of all six synthetic documents and complete
port/reverse/input cleanup. The current transaction primitive keeps the old bounded
range in this fixture; synthetic optimistic writes demonstrate why a server response
with no reported pending writes does not by itself prove bounded-query completeness.
After rejection, an inclusive timestamp cursor alone misses the restored older
document. No delta reader was activated. The normal offline sync-package regression
on the same APKs reports **140 PASS / 4 opt-in SKIP / 0 FAIL in 5.590 s**; the new
three SDK tests run only in the separately verified local-demo harness. Debug/test
build and lint pass; the loopback HTTP exception is debug-only, absent from the
merged release manifest. `docs/PRIVATE_PULL_SDK_PROOF.md` records all setup failures,
precise evidence/limitations and the final isolated run
`2026-10-01T05-46-24-017Z-354ac507` in the sibling Web repository. No current-worker,
production-Firebase, release-signing, R8, dependency-runtime or Xiaomi change.

Separate Web checkpoint `bb4bffe` now prepares an authenticated exact server-time
anchor with a dedicated 60/minute owner quota and exact account-deletion cleanup.
Existing authorization shape/read count and write-limit policy are unchanged.
Root confirms **1,197/1,197 Web unit tests**, **30/30 actual local Firebase SDK cases**
(including the exact missing-lock readTime and two-SDK 59+2 quota race), lint,
typecheck and isolated build PASS. Android has not been wired to the new endpoint;
no deployment occurred. `4bb7886` additionally projects six needed shared-placement
fields without changing global collision layout or document-read counts.
See the sibling Web `docs/WEBAPP_RELEASE_27_HANDOFF.md` for exact evidence/limits.
A server-time anchor alone does not prove query completeness in the presence of
local pending-write overlays, nor validate historical/admin timestamp provenance.
Those adapter/fair-recheck/repair gates remain open before delta-worker activation.

Additional local security proof: Android `1c3eb27` and Web `c960f20` add four real
client-SDK Rules deny cases and a versioned isolated cross-repository runner. Owner,
foreign app user, client admin and anonymous cannot create, get/list/query, overwrite,
update or delete the current hashed `privateReadRateLimits` counter. Server checks
confirm unchanged fields; production Rules themselves are unchanged. Root passes
**84/84** current Android Rules cases, no skips (35.604 s), **17/17** runner-safety
cases, plus the unchanged **30/30** backend-SDK default (33.487 s). Both demo manifests
confirm unchanged inputs and free ports before/after; Web lint/typecheck pass.
Invocation, tool integrity, credential isolation and non-OS-sandbox limits are in
the sibling Web `tests/rules-local/README.md`. No live rules or real account test.

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

### Frozen coverage triage after the October identity fixes

Read-only follow-up of the existing 25 September snapshot and the 18:19 October-1
replay found no further reproducible general parser/resolver correction justified
by the available exact-printing evidence. This is not a fresh provider download or
a universal no-defects claim. The strict report has 20,252 available and 11,363
unavailable printings: 11,251 missing/conflicting/quarantined exact identities,
111 missing/zero requested trends, and one product absent from the provider catalog.
Seven strict-audit-unavailable printings already have positive runtime values;
those provenance gaps are not seven newly reproduced app failures.

All 158 numbered `30th` cards, including 129–158 above main total 128, are priced
in this replay. Examples `30th-151` -> product 908353 -> EUR 83.02 and `30th-152`
-> 908354 -> EUR 132.26 are dated evidence, not live-price claims. RGB supplements
lack upstream detailed-printing evidence in this snapshot; B has no trend but has
supported average/low alternatives. Of the 111 missing/zero-trend printings, 83
already have a supported same-finish alternative; 28 do not. The report measures
trend coverage, not all user-confirmed alternative/UI paths.

Root-ID inheritance, name matching, normal-for-reverse substitution and guessing
replacement products are not justified fixes. The next coverage step requires
explicit collector/printing-level provider evidence; no additional fallback or
product change was made for this triage. Full bounded breakdown and examples:
`artifacts/release27-engineering-20261001/price-triage-followup.md`.

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

### Actual Room import persistence proof (2026-10-01)

The real importer now calls the verbatim extracted internal `persistCardmarketImport`
transaction. Product/price mappings, 1,000-row chunks, source timestamps and SUCCESS
write are unchanged. No injectable failure hook, Room schema, dependency, scheduling
or network change. The existing outer RUNNING/ERROR handling remains outside this
transaction, as before; this proof does not execute a real provider download.

Three new `CardmarketImportRoomInstrumentedTest` cases pass against a fresh private
in-memory Room database and actual DAO/repository APIs, **3/3 in 0.882s**:

- Missing normal/reverse trend stays null; all seven normal/reverse alternatives,
  exact product, EUR and original guide time survive import. No invented printing.
- Correcting the same source timestamp replaces values without another history
  row; null fields are genuinely cleared and an unrelated product remains intact.
- A test-only TEMP AFTER INSERT trigger aborts only after 1,001 incoming product/
  price rows and the SUCCESS status are observable inside the real transaction.
  Every column of products, raw prices and sync state matches the prior snapshot
  afterward. The identical import succeeds after removing the isolated trigger.

This adds a real atomic-persistence proof to the earlier parser/stream helper tests,
not an all-cards coverage, full importer catch/worker, network or production test.
Two independent source reviews found no blocker in the narrow extraction/test.

Root debug/test build and debug lint pass in 3m17s; all JVM results: **853 PASS,
one existing live-download opt-in SKIP, zero failures/errors**, 125 suites.
The exact-installed offline API-36 regression passes **371/371 in 309.666s**;
its manifest confirms unchanged APK/source inputs and offline/no reverse afterward.
The three additional Room cases use those same installed APKs with separate strict
identity/count validation and unchanged input hashes. No Xiaomi was accessed.

Evidence under `artifacts/release27-engineering-20261001/`:
`cardmarket-room-proof-build.log`, copied `cardmarket-room-proof-jvm-results/`,
and `cardmarket-room-proof/{instrumentation.log,manifest.json}`. Installation receipt:
`artifacts/android-regression/20261001T135408Z-cbd27130/manifest.json`.
Debug APK SHA-256: `CBD7CDB28A26939ED70A7F115A15949344C21D52508D01013ABEF7ECD7221E39`.
Test APK SHA-256: `04A27622924C110387B8BEE60E45529FB9105AC29B3A1EDF75AD4D81B116321A`.
The separate release-source compilation/lint invocation was interrupted on the
user's pause; that partial log is not a successful release-validation result.
After the explicit resume, the same compile/lint-only tasks pass in **1m50s**:
`cardmarket-room-proof-release-compile-resumed.log`. Release Kotlin was already
up-to-date; release lint completed. This is not R8 execution or an AAB validation.
No release APK/AAB, signing, upload or deployment was performed.

### Non-finite and overflowing price regression (2026-10-01)

Three additional actual-parser JVM cases pass for both normal and reverse trend:
quoted `NaN`, quoted `Infinity`, and an exact cent value of `Long.MAX_VALUE + 1`.
Each first proves the same valid JSON shape accepts a decimal string, then puts
the invalid value after a valid record and requires the entire parse to fail,
not return its partially parsed prefix. The existing BigDecimal/exact-Long policy
already rejects these values; no parser or product behavior was changed.
Evidence: `cardmarket-invalid-prices-jvm.log` under the ignored engineering
directory. The subsequent full JVM run has **856 PASS / 1 existing opt-in SKIP**
across 126 suites; debug/test build and debug lint pass in 1m32s, with copied XML
in `price-conflict-sdk-jvm-results/`. This is not a live provider coverage claim.

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

### German physical-set artwork audit and repair — 2026-10-03

Scope: Android catalogue/search/selected-card and display-only saved-card artwork.
No database migration, saved-card rewrite, price mapping, sync architecture, cloud
deployment, release signing, Play upload or R26 AAB change. Full-Sync remains R27;
Delta stays disabled and Gate 15 is not opened by this image checkpoint.

The frozen public TCGdex inventory covers **144 physical German-index sets and
19,477 exact source card IDs** (11 Pocket sets excluded). There are 843 identities
with neither DE nor EN image metadata, not necessarily 843 blank app cards:
47 already have app-maintained artwork. Another 4,872 use declared EN fallbacks.
The full chosen high-image HEAD/MIME pass checked 18,634 distinct URLs. Its first
pass found 9 HTTP404s and 131 transient 503/timeouts; only those 131 were retried.
Final: **18,625 reachable, 9 unavailable, zero unknown/unchecked**. This is not
pixel validation of all 18,634 files, all thumbnails or every printed language.

The added immutable, exact-identity registry has **843 entries: 592 German and
251 English**. Candidate files were downloaded, SHA-bound and actually decoded;
DE takes precedence, no foreign-name-only or main-set-total mapping is used.
312 gallery images resolve to their manifest-proven parent CDN paths. 234 existing
English fallbacks can now use verified German artwork. Six German format gaps
use real PNGs, not broken WebPs; an older EX English format gap is also repaired.
All nine unavailable inventory high-image identities have verified replacements.
Three legacy XY promos (XY39/XY46/XY68) only have verified lower-resolution files.
Only exact known failed/replaced URLs are overridden; newly corrected URLs,
private photos, different source IDs and non-German saved-card languages remain
untouched. The catalogue displays an English replacement notice. Its thumbnail
loader retries a failed small image once with the same card's large image and
resets on identity/URL changes. No image binaries are bundled into the APK.

Of the 843 missing-both metadata identities, 600 now have registry coverage,
including 47 pre-existing curated entries: **553 newly covered** (312 DE/241 EN).
**243 remain unresolved in 12 sets**. Across all 5,715 missing-DE metadata entries,
587 have verified DE replacements (41 previously curated); 5,128 still lack a
verified German-language image in these checked sources. An English image does
not become German artwork or establish equivalence of stamped/finish variants.
The maintained 20-card supplement has 17 MEP identities inside today's inventory
and three older `30th-rgb-*` identities outside it. New upstream `30th-R/G/B` image
aliases were verified separately; duplicate canonical/source representations are
an existing catalogue-identity follow-up, not silently migrated by this repair.

Remaining exclusions include rejected ambiguous/variant joins, missing DE/EN
manifest candidates and one verified two-quality404 (HGSS18). French-only images
were not substituted. Official SM75 page automation encountered an HTTP200
Incapsula iframe: those responses are retained as access-boundary failures, never
artwork proof. The boundary detector was corrected and no bypass was attempted.

Evidence: ignored `artifacts/release27-engineering-20261003/german-image-source-review/`
and `C:/Users/Dominik/DomiDex-audits/`. Inventory SHA256
`83187e0e6d9e547c477532f4c1e88ab95835de10ba592b20bb111b03b72cd950`;
final registry evidence file SHA256
`cba2653bd244fc4d83643147cd54eb7a29a20b785422dd70d6e52c756f747279`;
canonical tuple SHA256
`dc729c34dbe7590c65d8fd1234c76cdd2200656a9160562d9c1466140c7039a6`.
The production table is independently pinned against these retained receipts.
Initial RED proved the missing saved-gallery image; the first full regression also
exposed a saved gallery URL omitted from the unavailable policy. That policy was
fixed from exact negative receipts, not by weakening its assertion. Earlier EN
expectations were updated to the newly proven DE PNG and assert no EN request.
Final build/runtime/Redmi receipts follow below; this audit is not R27 release
readiness or an assertion that every German printed artwork has been located.

Final artwork verification receipts:

- `green-201428-b8e4ec03`: debug/test APKs, release Kotlin compilation, debug/release
  lint PASS in 3m24s. JVM **969 cases, 968 passed, zero failures/errors, one existing
  opt-in Cardmarket-live test skipped**. Inputs and protected releases unchanged.
- `runtime-artwork12-0e0f72bbb4cf45fb8fb82220b48e3549`: **12/12 assertions PASS**,
  zero skips, 63.683s on offline owned emulator-5556. Includes four real decoded
  pixel tests, four German search tests and four byte-unchanged binder-price tests.
  APK/source/installed hashes remained identical, offline/no-reverse guard passed.
  **The harness still exits 1:** its strict post-idle guard rejects Android's
  retained test process. Read-only inspection found PID9011 cached CRE/frozen;
  the guard was not weakened, the process was not stopped to manufacture PASS,
  and the complete harness is not recorded as green.
- 46 diagnostic/source-identity/access-boundary tests independently PASS.
- `coverage.json` SHA256
  `d1ecd928ffdea8f1f8f116312c3b7c457cf9af7ff6cadb3cc0e656793a084d76`
  binds 23 sources and explicitly lists all unresolved identities in bwp, cel25,
  cel25cc, ecard2, exu, hgssp, mee, mep, sve, svp, swshp and xya.
- `redmi-install-201842-a3409766`: authorized normal-debug update on exact Redmi
  e30bd3ce (24115RA8EG), install-r-t success and installed APK hash read back:
  `36a45a9622d8ddd53acfadcd161134314fcfc5e270919be9e72e2b3726a67345`.
  Previous debug APK retained locally for rollback. No uninstall/data clear;
  MainActivity cold launch succeeded (1814ms, not a scanner benchmark).
  This is installation/start evidence, not proof of every image on the phone.

R26 signed AAB remains SHA256
`079608e27bcb5d440252ed727e238b162b449f3dba4b1c28986d4a4570d9b14b`.
No AAB, release/upload signing, Google Play, cloud deployment, Git commit or push
was performed by this artwork run. Remaining R27 gates are not waived.

### Missing-source artwork completion — 2026-10-04 (local only)

The separately frozen all-set recheck contains 402 genuinely image-less entries
(243 physical cards and 159 Pocket cards), not the larger count of cards whose
only available image is English. Its SHA256 is
`38ba78fe18fbd06bbf0b1eeb7808e90cc7e145e957a2d77027c6e540a6cc28d7`.
This follow-up adds **396 exact-identity image mappings: 187 DE and 209 EN**.
That is 237 physical cards (187 DE/50 EN) plus all 159 Pocket entries (EN).
No claim is made that these English fallbacks are German printed artwork.

New coverage: B1a 1, B2a 131, P-A 27, bwp 2, cel25 1, cel25cc 25,
ecard2 4, exu 28, mee 16, mep 95, sve 24, svp 33, swshp 3 and xya 6.
All **553 selected image URLs** have retained downloaded files, verified hashes,
successful full decoding and portrait-card dimensions. Sources are published
TCGdex/PokemonTCG manifests, Limitless and Serebii set/detail pages, with explicit
card identity and special-print evidence. No name-only or guessed URL joining.
Eleven Serebii thumbnails were landscape artwork crops, not complete cards; they
were rejected and the verified full image is used for both display sizes.

Six identities remain unresolved, deliberately not supplied with a similar card:

- `ecard2-50b`, `ecard2-74b`, `ecard2-95b`, `ecard2-103b`: retained scans visibly
  identify the a-print and corresponding e-Reader microcode, not the b-print.
- `hgssp-HGSS18`: no verified reachable exact front from the checked sources;
  the earlier image URLs returned 404 and Worlds stamp variants are ambiguous.
- `svp-500`: the candidate is Chinese, not a DE/EN fallback, and no safe published
  numeric crosswalk establishes this source identity.

These are limitations of the checked sources, not proof that no scan exists
elsewhere. Encountered access boundaries were retained and not bypassed.

`AdditionalPokemonCardArtwork.kt` is a separate additive table. The previous
843-entry table remains byte-identical (SHA256
`07c1cc16c19274454ded838b61d0fbc58b95f0acdedd8f3962fb151b04325dd7`),
and its existing 843-count/provenance regression remains unchanged. New entries
have no replaceable/unavailable URL policies: existing nonblank URLs and private
photos win. Exact DE/EN notices are display-only. Prices, collection records,
card names, HP/artist metadata, print variants and synchronization are not changed.
The nonnumeric Museum identity, literal Unown punctuation, Classic source IDs
and individual energy editions remain separate. Source metadata discrepancies
(Pocket B2a 088/105/130, MEP035 and SVP190/192) have explicit image-only review
decisions; contradictory metadata was not silently rewritten.

Reproducible local evidence is retained under ignored
`artifacts/release27-engineering-20261004/{pocket-missing-artwork,modern-missing-artwork,legacy-missing-artwork,missing-artwork-integration}/`.
Final `validated-additions.json` SHA256:
`2f948d38c8ba3720413e4f8fec6d7f3f8f3460f879ab001747868069a654aa00`.
Independently pinned additive tuple SHA256:
`b5f3cf2f1b7f2af5776f128268ae6f51b1ebdd3f5efbc67c89a1c624094f0534`.
The 210 exact English URLs have ordinal canonical SHA256
`3a702f271087fd5186db080c34e16747882db941e439180dc8cbd4f5c9216f5a`.

Initial `red-221113-c7a2cffe` demonstrated two actual failing image regressions
(MEP001 and SVE024 returned empty URLs); it was not a compilation failure.
The new tests also exercise all additions, null/partial images, wrong identities,
private photos, German manual search, the public Pocket set-loading path,
cached calls, no additional per-card metadata requests and exact language hints.
Final `green-223509-5b40b352`: full offline debug JVM suite and `lintDebug`
PASS, build exit 0 in 2m50s. **981 cases: 980 passed, zero failures/errors,
one existing opt-in Cardmarket live test skipped** (not weakened or newly skipped).
Lint reports no errors, 167 warnings and four hints; this is not a warning-free
claim. All input-source and protected-release hashes remained unchanged during
the build. No APK/AAB or release/signing task was run and no device was touched.
An independent offline review matched all 396 tuples and 210 EN URLs to the
frozen receipts, rechecked 513 evidence files and 553 image files, and confirmed
the exact 396+6 partition of the original 402 identities with no old-table overlap.
These are source/image and JVM/lint proofs, not a new Redmi/emulator rendering
test. No performance or full-release-readiness claim follows from them.
R26 remains protected; no Google Play, Firebase, deployment, commit or push.
Full-Sync remains the R27 solution and Gate 15 remains subject to its existing
critical prerequisites. This follow-up does not waive any remaining R27 gate.

User-authorized Xiaomi installation, 2026-10-04: the follow-up request explicitly
allowed installing this image-completion test state. `apk-224206-e1391a49` ran
only offline `assembleDebug`, succeeded in 34s, and bound all source/protected
hashes to the preceding 981-case JVM/lint proof. Normal debug APK SHA256:
`0395ace32c520317351793837848832612531df8d30e43b7a40165e7d7861781`.
Package/version checked: `com.dominik.cardvault`, 27 / `1.0-closed-alpha.6`,
debuggable, existing standard Android debug certificate, min26/target37.
The first USB streamed transfer failed without changing the installed package;
the exact device reconnected and a guarded retry succeeded.
`redmi-install-224449-77729fad` records replacement on authorized e30bd3ce /
24115RA8EG: previous APK backed up, both signatures verified, `install -r -t`
Success, installed APK hash read back and matched, UID10411 unchanged, explicit
MainActivity launch `Status: ok` and process present. No uninstall/data clear,
no release/upload signing or AAB and all protected release hashes unchanged.
The APK rollback copy is not a backup or checksum proof of the user's database.
This is installation/start evidence, not a claim of every image rendered on-device.
