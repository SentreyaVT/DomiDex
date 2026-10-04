# Release 27 engineering ledger

This is a work-in-progress evidence ledger, not a release approval. Updated 2026-10-04.

### Authorized Web hotfix and actual Legacy G9 result — 2026-10-04 23:48 Europe/Berlin

The separately authorized Collection fix is now **LIVE**, not merely local.
Hosting `5719d197695f2e1c`, released23:42:33 Europe/Berlin, pins
`domidex-web-r27-20261004e`; immutable image digest
`sha256:eda5084e7d99600b282c6e3580c000aeec7017f5321792d381d094bda7015f8b`.
Cloud Build `aaa33c4d-1a3f-4db1-9631-2777c7c3c45a` succeeded from frozen Web
commit`39742dd9a3aba5eacf7126d4c7248c295cc0a661`. All309 production files were
compared with the prior deployed stage: **only windowed-collection-grid.tsx changed**.
Untracked market-data and new test harnesses were excluded. Actual-image client
and server archives passed bounded safe extraction and Cloud Build manifest hashes.
Candidate and live GET checks both passed45/45, including exact client assets,
root/app separation, global CSS, security headers and anonymous API rejection.
Runtime configuration, IAM and default Cloud Run percentage traffic stayed equal;
Hosting uses the verified new revision pin. No Rules, Functions or access-grant
deployment, Android release build/signing, Play action or physical-device change.

Authenticated browser readback shows351 copies/348 distinct saved entries. The
previously failing End jump, Home/repeated End and width changes were exercised.
At1280x720, final entry347 was focused and visible with23 mounted tiles; at390x844,
10 mounted tiles, the same visible target. Both settled geometries had zero tile
overlaps; no captured browser warnings/errors. Empty-search recovery also worked.
These are bounded real-account navigation checks, not universal correctness or
price coverage: existing unpriced entries remain explicitly visible. No collection
record was written. Evidence: ignored `artifacts/r27-grid-hotfix-20261004/`,
including `browser-live.json` and three actual screenshots. Receipt SHA256:
`f40839086b7693bed4e7eeb69ad092f50254abf0fd49b2077a2dd7b1adac7fee`.
Search and temporary viewport overrides were reset after the checks.
The earlier1306/1306 source regression and historical REDs remain separately dated.

The new, independently reviewed **Legacy G9 SDK singleton is RED**. Evidence in
Web`outputs/collection-sync-session-legacy-local/2026-10-04T21-45-30-197Z-7b40a84aa021497b858f9cca5a55d201/`:
one JUnit failure,32.057s, unchanged30s observation from Android Ready (30034ms
diagnostic elapsed). A synthetic legacy STARTED job/lock was atomically created;
actual Auth-disable and revoke were each acknowledged once while the test ran.
Host timings UTC: create21:45:50.166–.172, disable.442–.447, revoke.807–.811.
The window does **not** restart at Auth-revoke; no exact cross-clock offset was
recorded. No access-fence document or Knowledge data/updateTime was changed.
The genuine prior SERVER-positive Session remained PENDING/accessConfirmed=true,
same UID, only initial Auth/token events, both Session listeners present. The
actual consumer remained valid with its positive hint and zero listener closes.
No refresh, new control read, re-listen or synthetic denial was injected.

All14 entity tables plus sqlite_sequence, eight fixture files, preferences,
directories, local pending mutations and owner/foreign receipts stayed exact.
After the failed assertion, the host removed only its eight known documents in
one acknowledged CAS batch and its two synthetic Auth accounts; final inventories
were empty. Ports, reverse mappings and instrumentation were clear, offline AVD
identity preserved, all199 pinned inputs independently rehashed unchanged.
The manifest correctly retains exit1/serverVerified=false; cleanup is not PASS.
The original18-case registry and17/18 lock-only RED are unchanged. Pure host
validators passed 112/112; after semantically equivalent sort typing, all 112
cases passed again (10.379s, zero skips), with scoped lint. Those are not
substitute SDK evidence.

Root built only Debug app/test APKs (final build43s) and installed them solely on
owned offline`emulator-5556 / DomiDex_R27_Regression_API36`. Installed hashes:
app`64170e3ccffb821dabb886826fcaf20b7372e4cac2e7b952a6540630c160c9b9`,
test`ef62043a2aeafc7e68e9ebeab93d3ff2d78c150452ec1ec79acfbbc1089bd8b7`.
The immutable signed27 AAB remains`2c3fe33bb84c47fda4525297a8134859f44fd727dabf268bade401d36f276cc7`.

Diagnosis: missing accountAccess permits the existing claims fallback; without
an actual listener-denial/Auth event, the Consumer receives no invalidation signal.
Fresh deletion requests already create the disabled fence; legacy retries do not
reconstruct an absent fence. This proves a bounded stale active-consumer case,
not cross-user data access or successful new unauthorized requests. A narrowly
job/UID/lock/lease-bound, idempotent legacy repair needs separate authorization
and its own real SDK proof; no blind migration or production backfill was performed.
Full-Sync remains; Delta stays off. Other binder, hardware/multidevice, operational
privacy/load/cost, coverage and original-vendor-symbol limitations remain open.
**This is not all-gates completion.**

### Local Web crash fix and operator evidence — 2026-10-04 23:16 Europe/Berlin

The actual Web Collection React185 failure now has a deterministic RED regression:
varying visible tile maxima oscillated the row stride/window synchronously.
Original run1299PASS/1FAIL is preserved under the Web repository's
`outputs/release-local/2026-10-04T20-55-25-131Z-9108.{json,log}`.
The narrow Web fix retains a high-water height per grid geometry, waits for the
new inline width before resize measurement, observes every mounted tile and
measures the keyboard target window/neighbours before scrolling. Repeated keyboard
requests also get a commit. No change to card identity, prices, sync or data access.
The known tradeoff is retained whitespace after shorter text at unchanged geometry.

Independent review identified repeated-End and taller-destination-neighbour cases.
The latter was genuinely reproduced in a minified browser fixture (target outside
viewport), then corrected rather than dismissed. Final isolated Web regression:
**1306/1306 in79 files**,23.13s, zero failures/skips;13 collection cases, typecheck
and scoped lint PASS. Web report`outputs/release-local/2026-10-04T21-13-27-372Z-23092`.
Browser fixture uses actual grid/CSS with synthetic351/10000 entries, desktop1280×720
and mobile390×844: settled End target visible, no overlapping tile rectangles,
bounded DOM, filters and exact-ID selection verified. Source/procedure/limits:
[Web fixture](https://github.com/nandedomi/DomiDex-Web/blob/codex/release-27-hardening/tests/collection-grid-browser/README.md).
This is not logged-in Firebase/live acceptance, a physical device or a full Web
production build. Existing unit act/Canvas warnings remain recorded.

The previously pending three operator privacy cases now pass in the isolated real
Auth/Firestore/Storage demo: **39/39**,0skip,exit0,unchanged inputs,ports free before
and after. Web manifest`outputs/private-operations-local/2026-10-04T20-55-49-732Z-1c8432ed/manifest.json`;
separate launcher safety17/17. Historical stored timestamps are explicit fixtures,
not65minutes actually elapsed or an old-token revocation proof. A new named SDK
client is not an OS process restart; service calls are not HTTP route coverage;
emulator Storage metadata is not provider-level purge evidence.

**Local code/test and engineering-branch backup only; NOT DEPLOYED.** The immutable
Android27 AAB, active Hosting revision, Rules, Google Play, signing and physical
devices are untouched. Full-Sync stays; Delta remains disabled. Original17/18
lock-only SDK RED, two private-binder references, physical multi-device/WorkManager,
operational privacy/load/cost evidence and unavailable vendor symbols remain open.
Here WorkManager means missing physical/OEM sync lifecycle coverage, not the
already-passed real retry/1001-binder continuation or maintenance OS-wake/new-process
same-child proof. Those earlier narrowly scoped successes remain valid.
Do not read older "fix pending" paragraphs as a claim about this newer local fix.

### Developer handoff and newly observed Web failure — 2026-10-04 after 22:32 Europe/Berlin

The [developer entry point](DEVELOPER_HANDOFF_R27.md) distinguishes the immutable
published test-track AAB from the evolving engineering source and incomplete gates.
GitHub default-branch documentation and the existing R27 binary archive are now
published separately; no new build, signature, Play change or production deployment.

GitHub readback: `archive/v27-1.0-closed-alpha.6` published20:47:21Z, draft=false,
prerelease=true, tag exactly at build-provenance commit`a3c7aa7`. All six uploaded
asset digests/sizes match local files: unchanged AAB, mapping, partial native ZIP,
checksums, release notes, and original NDK NOTICE. The61-entry local archive
manifest was freshly verified without mismatch; embedded AAB mapping matches too.
The source receipt qualifies the dirty flag:638 source hashes unchanged, no tracked
changes;10 explicitly allowed untracked hardware-proof files are outside the normal
build. Local configuration/toolchain/signing are still additional reproducibility inputs.

Developer portals were reviewed and merged via documentation-only PR1 in each
repository: Android master`b2e41ee22ecf24e6834b93b3f39bcf9a622b597c`, Web
main`eb75501376921b1f1c95ad4a1678d07388ec0ce8`. Each changes README.md only. They link
directly to the R27 engineering branches and current documents, state that default
source code remains older, and expose the archive and open issues. No blind code
merge, workflow, force-push, secret upload or new claim of all-gates PASS.

A read-only live Collection navigation to the end triggered React error185 at
20:32:11Z. The captured production stack maps to WindowedCollectionGrid's synchronous
layout measurement/setMetrics. Measuring the maximum of only the visible cards can
change row stride/window start, then change that maximum again. Existing constant-
geometry tests did not cover this case. No fix or new PASS is claimed yet. This
new finding limits the prior1299-test and happy-path browser evidence; preserve both.
No user account, binder, collection entry or production data was changed by that check.

### Current test-track status and source-backup verification — 2026-10-04 after 21:54 Europe/Berlin

The read-only Play Console follow-up now confirms **Closed Test - Alpha / Active /
available to selected testers**, release `27 · Geschlossene Alpha · Preise & Stabilität`,
one version code, published **04 October at 21:54**. Publishing overview confirms
the published app update with no pending changes. No publish control was activated
during that follow-up. This supersedes the pending-review status in the historical
21:50 checkpoint below; the immutable AAB, signature and version remain unchanged.

The source-backup follow-up rechecked GitHub directly: Android remote engineering
branch matched local `6ea94e258c6ff8edffabbc0960ba6095b8917c99`; the final deployment
documentation and three reviewed Web production changes were not yet committed.
The Web remote is named `github`, not `origin`, and its `main` is not the current
engineering branch. Git backup completion must not be confused with deployment,
a default-branch replacement, a new GitHub Release, or all-gates approval.

Web backup is now pushed and independently confirmed at
`77b37a145dbd5aacdc018124a2251637ce63f991` on GitHub
`nandedomi/DomiDex-Web`, branch `codex/release-27-hardening`. The remote `main`
remains `d169c9e1ecf6d728a58d2fa55ead59adcf7a1716`. Separate reviewed commits are
`f2c7704` (Docker/root headers), `2cf0bc9` (deletion access fence), `a6dc6b5`
(isolated proof harnesses), and `77b37a1` (current handoff). No GitHub Release,
tag, PR or default-branch merge was created. GitHub reports zero repository
webhooks/workflows; Cloud Build trigger listings for global/europe-west1 are empty.
Targeted high-confidence secret patterns found no matches in the72 pre-helper
outgoing commit trees or final staged proof helpers; this is not a comprehensive
secret-detection guarantee. Private environment/key/release files and experimental
`backend/market-data` were not included. Android source already matched remote
`6ea94e2`; this documentation checkpoint adds the latest deployment/readback facts
to that same engineering branch. Existing untracked hardware-proof work is excluded.

Fresh isolated Web regression: **1,299/1,299 tests in 79 files**, no failures/skips,
24.45s, report `DomiDex-Web/outputs/release-local/2026-10-04T20-07-35-687Z-21596.{json,log}`.
Typecheck passed. Existing React-act/JSDOM-canvas diagnostic warnings remain.
The separate host-helper selection initially passed 102/102. Broader scoped lint
found unawaited node:test registrations and missing explicit sort comparators in
new proof helpers; these are not a new product/SDK failure or a reason to waive lint.
The ordinary whole-workspace lint also still includes unrelated experimental
`backend/market-data`, excluded from the deployed source and source-backup commits.

The scoped lint findings are now corrected only in the new proof helpers:
33 explicit top-level awaits and six explicit UTF-16 comparators, preserving
assertions, ordering and deadlines. Final scoped lint and typecheck pass;
92 SDK host controls plus10 selection cases pass **102/102**,10.035s, no skips.
No fresh SDK/device run was claimed from these host-only edits; the original
lock-only RED and historical exact-input receipts remain unchanged.

Important evidence limits: live `338/348` counts priced **copies**, not unique
card identities. Private-binder positions6/7 already displayed unavailable before
this deployment; the placeholder represents missing/conflicting collection-entry
placement identity, not a demonstrated image404. Their precise cause remains open.
The six unresolved artwork identities and six missing native vendor symbols are
separate issues. Full-Sync stays enabled, Delta disabled. No new Android build,
signing, device installation, deployment or Play mutation is part of this follow-up.

### Authorized test-track and Web deployment — 2026-10-04 21:50 Europe/Berlin

**Current outcome:** immutable Android27 is INTERNAL-live and submitted to the
closed Alpha review (Google approval/publish still pending). Reviewed Web/backend
and Rules are now live; replacement Web version`334fdfd6961039a1` was published at
21:47:30 and pinned to`domidex-web-r27-20261004d`. No production-track Android
release, tester-list change, new Android build/signature or device install.
This deployment does not turn the historically scoped/unfinished engineering
gates into blanket PASS. Full-Sync remains R27; Delta stays disabled.

The human-authorized follow-up in the DomiDex architecture chat explicitly requested
the existing signed27 AAB in the intended test track plus its reviewed Web/backend.
The root agent read the actual human turn before production writes. This is not a
new Android build, a production-track rollout or a claim that all earlier gates passed.

Google Play accepted the immutable AAB hash documented below, automatically recognized
its embedded R8 mapping, and accepted the existing exact partial native-symbol ZIP.
The INTERNAL track `4701419545030527814` now reports **Active / available to internal
testers**, release `27 · Geschlossene Alpha · Preise & Stabilität`, published
04 October at21:04, code27/name`1.0-closed-alpha.6`. At that checkpoint the separate
Closed Alpha track had not been changed. Tester lists, signing, Android dependencies
and devices were not changed. The review
showed no warnings and zero newly unsupported devices; that does NOT change the
actual one-of-seven native-symbol coverage. Six unavailable vendor originals remain.
Proof: `artifacts/release27-engineering-20261004/deployment-web-r27/play-r27-internal-live.jpg`.

The user subsequently explicitly requested R27 in the closed Alpha. The existing
R27 draft in track`4698265943442511200`, release`9`, already referenced the same
code27 AAB, embedded mapping and attached symbols. It was reviewed, saved with100%
Alpha rollout and submitted as the sole pending change. Publishing overview now
lists **Closed Test - Alpha / 27 / changes being reviewed**, with preliminary checks
still running (UI estimate up to14minutes). This is NOT an Alpha-live confirmation.
Managed publishing remains enabled; after Google approval the publication step is
still required. No production-track release or tester-list change was made.
Proof: `artifacts/release27-engineering-20261004/deployment-web-r27/play-r27-alpha-submitted.jpg`.
At21:35 the preliminary checks had finished; the UI explicitly said the changes
are now being reviewed. Proof: `play-r27-alpha-in-review.jpg` in the same directory.

The Web candidate uses an isolated allowlisted source stage; unreviewed untracked
`backend/market-data` and private environment files are excluded. A first candidate
failed because installed vinext's `/:path*` header matcher excludes `/`. An explicit
root rule fixes that, with a real matcher regression (1295PASS/1FAIL before,
1296/1296 after). Candidate b passed32 HTTP/header/asset/auth checks, but the final
visual check revealed missing global CSS: server-layout CSS was absent from emitted
client assets. Hosting was promptly restored to original version`c71089a842d591bc`.
The earlier32-check PASS is therefore not a visual or overall deployment PASS.
The stronger smoke reproduces the CSS failure and is retained as
`candidate-b-style-regression-red.json`; no failed receipt was overwritten.

The reviewed Firestore and Storage Rules were deployed and read back byte-identical
(receipt `rules-after-ccaed9dc9d0243728001da6eb205c187/rules-after.json`). Functions,
IAM, account grants, server limits and public access settings were not changed.
The candidate runtime stable-configuration hash matches the baseline. Initial
price-listener interruption cleared after explicit retry; another full reload then
loaded348cards/345variants/10sets and connected without retry. Private binders and
ten existing shares loaded read-only. Two unavailable slots on private-binder page1
were independently present on the restored old version too, not introduced by R27.

The attempted null-client `GlobalStyles` bridge passed local typecheck/scoped lint
and1296/1296 tests but FAILED the actual candidate-c production smoke: global CSS
was still absent and rendered HTML lacked the root html/body wrapper. The attempt
was therefore reverted; candidate c was never published to Hosting. Its failed
receipt remains `candidate-c-smoke.json`. Last full test before reverting the
ineffective bridge: Web `outputs/release-local/2026-10-04T19-08-20-061Z-19952.{json,log}`.
The actual immutable candidate-c server bundle was extracted read-only in Cloud
Build`571ac264-5223-42e5-8402-8c4a0ea92dab` and downloaded as
`server-c-audit.tar.gz` for a concrete root-layout diagnosis. The old functioning
Hosting version remains live; the reviewed R27 Web/backend is not yet live.
The actual server bundle proves root `/` and `/app` load the SAME page chunk and
both root/nested layout loaders resolve to AuthProvider alone. Discovery retained
both loaders, so the failure is import aliasing, not missing source files. Vinext
emits absolute `/app/app/...` imports without `/@fs/`; Vite8 passes root/asSrc to
its native resolver. The actual `/app` build root permits those imports to be
shadowed by existing `app/app/...` files. The targeted Docker correction changes
only the dependencies/builder WORKDIR to `/srv/domidex` and its two COPY sources;
runtime remains `/app`, same dependencies/settings. Real filesystem regression:
1FAIL/2PASS before,3PASS after, including retained counterfactual collision control.
Full suite afterward:79files/1299PASS,0FAIL/0SKIP,21.91s,
Web`outputs/release-local/2026-10-04T19-34-24-604Z-18520.{json,log}`.
Typecheck passed. A new-test-only unnecessary regex escape was removed after lint
reported it; focused lint and the unchanged3assertions then passed again.
Candidate d build`a5cceaa9-ea8f-4fef-9b72-d648ff477d4d` is running; actual server,
HTML/root CSS and visual checks remain necessary before Hosting publication.

**Completed d validation (21:50):** Cloud Build succeeded21:43:07; exact image
digest`4ac5de98703c567cef2ec07fdad1c77f60a1654849965a6dc25eff61cf9af4e8`.
The exported server proves distinct root/app pages and distinct RootLayout versus
AuthProvider wrapper; actual RootLayout renders html/lang=de/body and binds global
CSS. The exported client contains158 files; all311 original stage inputs remain
unchanged. The extraction reviewer's first CSS parser missed an adjacent block;
this diagnostic-only failure and its read-only corrected validation are retained
in`stage-d1a6bcea285348ac8231d9ce5b9617e4/client-extraction.json`. No product/test
assertion was relaxed. A separate selection registration test first failed22vs21;
the count and explicit new-file assertion were updated, then10/10 passed.

Both tagged-candidate and live GET-only checks pass45/45: root document wrappers,
distinct landing/app pages, exact built JS/CSS bytes, global CSS first, security
headers and401 denial on three private APIs. Receipts:`candidate-d-smoke.json`
and`live-d-smoke.json`. Actual browser screenshots/computed styles confirm the
landing and guest-app design before publication, then the authenticated live page:
Geist,body margin0,expected responsive layout,348cards/345variants/10sets,
600.28EUR,338/348priced,connected sync without retry. Private binders,three pages
and ten existing shares load read-only. Existing unavailable slots6/7 remain as
observed on the old version; no collection/binder/price-choice/share write was made.
Proof images:`candidate-d-home.jpg`,`candidate-d-app.jpg`,`live-d-app.jpg`.

Runtime baseline hash remains`e078d24140bf14ed015045aceb8789ee7619b8870f132d3ec821916684e59be8`
(`run-after-d.json`); no changed identity/env/limits. The fixed6-GET metadata read
`live-metadata-minimal-review/read-a697c17c41564c9fb4d2896ff11a2e69/result.json`
in the retained storage worktree confirms Hosting334fdfd6961039a1→tagr27-20261004d
→revisiondomidex-web-r27-20261004d with equal start/end reads; SHA256
`9722e767f0c2556376473e6673628477840c8bde54c323f284fa363d7b72522b`.
Default Run traffic remains on its existing revision; Hosting uses the exact tag.
Production Cloud Functions, IAM/AppCheck enforcement and scaling were not changed.
The exact R27 AAB hash was rechecked after the Alpha submission and is unchanged.
Whole-workspace lint includes excluded experimental market-data failures; the exact
deployable source scope passed under the unchanged repository ignore policy
(185source files,61regularly ignored,124eligible). No claim of whole-workspace green.

### Signed local R27 candidate — 2026-10-04 20:15 Europe/Berlin

**Local artifact verification PASS; not a blanket rollout approval.** The reviewed
36-file worktree delta was committed as `cffaeea8635ae3da590dfeca038904eeeff26a8e`
and integrated byte-equivalently through Git as `56eb4a47e58fe6b3c8428aa9d1faf3606e073e76`.
Build source commit: `a3c7aa7ddef8987faa285bebf1bd8779313baae4`.
No dependency, schema, Rules, version or signing configuration was altered by
this integration. Separate existing hardware-proof scaffolding remains untracked
and unchanged; it is not an input to the ordinary app build. The archive's
`source_dirty_at_archive=true` records precisely those ten untracked scaffold
files, not uncommitted compiled code. The before/after source snapshot is equal.

Existing `Build-SignedBundle.ps1` completed in 368.015s through the bounded local
runner. All 39 release-pipeline checks PASS; Gradle BUILD SUCCESSFUL, 134 tasks
(87 executed/47 up-to-date), full JVM 1,063 cases/1,062 PASS/one unchanged opt-in
live skip/zero failures or errors. Release lint: zero errors, 170 warnings and
four hints. Release lint and debug lint have different variant scopes; this is
not a warning-free claim. No tests were disabled or relaxed.

The original two launcher failures are retained. First, missing PATHEXT prevented
PowerShell from waiting for Python; second, the isolated Windows batch invocation
failed before Gradle started. A paired native-command probe reproduced both
conditions. Only the ignored orchestration runner gained normal PATHEXT,
native-backslash COMSPEC and OS=Windows_NT. The release helper and application
were unchanged. Credentials were cleaned by the existing helper's finally block;
no key/password content was printed or archived.

Immutable candidate archive: `releases/v27/`:

- `DomiDex-v27.aab`: 52,705,225 bytes, SHA256
  `2c3fe33bb84c47fda4525297a8134859f44fd727dabf268bade401d36f276cc7`.
- Actual manifest: `com.dominik.cardvault`, code27, `1.0-closed-alpha.6`,
  min26/target37, non-debuggable, arm64-v8a only.
- All 688 payload entries verify against the existing pinned upload certificate
  `837D12C024DFED3904E13CEB241B9B99584AD54AE8AA9D4F58084BBC76B70EA2`;
  bundletool structure validation PASS.
- Embedded R8 mapping equals `mapping.txt`, SHA256
  `8900194e5eaa4042f46e4043eba099062d30d60f7974d533cee7868649448c8e`.
  Existing conservative R8 configuration retained. MainActivity and the new
  WorkManager worker names remain preserved in the actual mapping.
- `native-debug-symbols.zip`, SHA256
  `cbef5ac45ae8c7c870d40cc8ea77fa75da8b9bfe1c8d7a556be65f776e30e687`:
  **partial coverage, one of seven libraries**, exact original libc++ verified
  against this AAB's Build-ID, allocated/load bytes and symbol addresses.
  Six vendor originals remain unavailable: graphics.path, datastore_shared_counter,
  image_processing_util_jni, mlkit_google_ocr_pipeline, opencv_java4 and
  surface_util_jni. The native-symbol Play warning may therefore remain.
- Native license evidence retains three explicit provider limitations for
  CameraX/libyuv, future ML Kit symbol-delivery rights and OpenCV/KleidiCV.
  These are unresolved provenance checks, not an invented legal clearance.

Bounded build receipt:
`artifacts/release27-engineering-20261004/final-candidate/signed-92f582e7070745d898d29686a080cdf9/`.
Result SHA256 `9dd9d7d6a8ccfe13f8b796e6b7469a66b8e8b2b01fcc887de1b06d6cf960f97e`;
archive verification `838493175b65c33c166d6f2aedb999b979771947efbc656a8ec3a7d474ceec98`.
All tracked inputs, protected R26 files and the earlier tested worktree APK pair
remained unchanged. Normal MAIN debug outputs were intentionally rebuilt by
the existing release pipeline; no device was installed or launched.

**Before a corresponding production rollout promise:** cross-device alternative
prices need a compatible Web client and verified deployed
`collectionPriceSelections` Rules (current live Rules are unverified, not proven
absent). The corrected public-share alternative-price path and fresh-job account
deletion access-fence are explicitly local Web/backend fixes, not deployed by
this Android build. Android cannot activate those server changes. The documented
physical-device, operational-privacy, trusted-ingress/load and alerts/billing
limits still apply. No new requirement to enable Delta or App Check is implied.
The candidate does not establish a new physical ARM64/minified runtime test.

No Google Play upload, production deployment, GitHub stable release or tag was
created. Engineering-branch push is a separate authorized source backup; it does
not upload the ignored AAB/symbol archive. Its outcome requires a separate exact
remote HEAD readback and is not inferred from this successful local build.


### Final local regression checkpoint — 2026-10-04 20:00 Europe/Berlin

The post-import-fix full JVM/debug-lint run passed: 152 suites, 1,063 cases,
**1,062 passed, zero failures/errors, one unchanged opt-in Cardmarket live skip**.
Debug lint: zero errors, 144 warnings and four hints; not warning-free.
Receipt in the storage worktree:
`artifacts/release27-engineering-20261004/storage-prerequisite/import-error-status-review/full-jvm-lint-3e8bbaac9f0744e9acaf377ef5812392`,
267.719s; result SHA256
`50d573c06c376fce3cf9d3e684aa97d64e443486738a8ebdff0a1c95f841e3bf`.
Protected sources, ordinary APKs, release outputs and R26 remained unchanged.

Fresh API37.1/16KiB full regression on the exact current debug pair passed
**371/371, zero failures/skips, JUnit 310.127s**. Receipt:
`artifacts/release27-engineering-20261004/storage-prerequisite/import-error-status-review/api37-followup/runtime-api37-full371-42a75432ac494088846e9c29486bbf9b`.
Manifest SHA256 `5ce1e4445801fcb4d7af17ee6c9a6ea011fb3023a6cc0442f1918e8598e153c3`;
JUnit log `f4a2ad815ce48ca3fe6f024564dd101c7b9495745f6cbc99d53f85373a7dbdd9`;
diagnostics `eb8bcd5318610ab96be46b62773505a335611a9e8e637437f4636d60a67ae6f9`;
terminal receipt `7067b5cefdf4b93fe95a38ee3be4d9d561c30ca5fb2b02d75f64271765fb6c15`.
All source/APK/profile/offline and both scanner-diagnostic checks passed.
The terminal contract explicitly permits an observed cached test process only
after complete JUnit/diagnostic success and no active instrumentation/camera;
it is not a native-unload or background-delivery assertion. A PowerShell display
step could not parse the driver's mixed text/JSON stdout; the saved actual
driver manifest, JUnit and diagnostic receipts independently establish PASS.
No test was rerun or reclassified because of that presentation error.

Exact pair: app `64170e3ccffb821dabb886826fcaf20b7372e4cac2e7b952a6540630c160c9b9`,
test `5da0d817c2fed4c4fc0ac02119410cdcf9b3b129e14e02f1dc1999c29bad1e46`.
Earlier intermittent OCR failures remain historical unresolved evidence, not
retroactively fixed by these passes. The separate lock-only SDK expectation
also remains RED; the actual deletion workflow has its own newer PASS above.

The user-authorized next step is integration of the reviewed storage-worktree
sources and a **local signed candidate**, followed by archive verification and
engineering-branch backup. This is not a blanket PASS of Gates4–14 or permission
to publish: physical multi-device/camera evidence, operational privacy completion,
public-ingress/load controls and live alerts/billing still have their documented
boundaries. No new speculative architecture work is added. Full-Sync stays;
Delta stays off. R26 remains immutable. Google Play upload, stable-release
publication and production deployment are not part of this candidate build.


### Native ERROR-write46 PASS and live metadata binding 2026-10-04 19:48 Europe/Berlin

New isolated output `artifacts/r27-native46-20261004/40fe15a815e040de964b5df6cd7fd22d`
built127.719s; check/install passed. Actual runtime
`runtime-run-f5286e3a2a954ed6ab3a736e608bad51`:46/46 PASS, no skips/failures,
117.094s, all source/output/device/offline/ordinary-package/terminal guards green.
Manifest SHA256 `118be1b1f1d9ff3ac8fbaf7cf6dcf8ca5a178b1e16492c964eaa7dad187876a6`;
JUnit log `ce2d2a1ae41888aab2bf32f7b86e98cbdb96d6205e71653fc82aee7e0a19fc13`.
Guarded cleanup `4b8fa51eceaa462bb1960cd3b01cf5c5` passed; only UUID-owned
test packages removed. Existing ordinary app and Redmi untouched.

Original44 and unchanged catalogSQLite45 pass. New46 genuinely observes a separate
native SQLiteFullException from the real ERROR-status INSERT, after a real FK
primary failure. The fixed import Result retains the exact primary and secondary
failure, releases its existing guard and preserves all15 typed tables, schema,
sequence, photos, ticket and cursor across reopening. Lifting only the owned page
limit permits that same ERROR writer to persist correctly; second reopen succeeds.
Fixture telemetry:65pages at4096bytes,820 setup status rows,4.950s new-case scope.
This is SQLite page exhaustion, not physical ENOSPC/WAL/power loss/network/crash proof.

Both earlier host-only failures are preserved. After parser correction, second
install43477... failed because Windows adb could not stat the existing260-character
test-APK path. Cleanup02d0... passed. Only the isolated output root was shortened;
20 pure controls bind identical test selection/lifecycle and both APK paths<240.
No product modification arose from either harness correction.

Read-only live Hosting-to-Run collector completed six bounded GETs, consistent
at both observations. Actual Hosting versionc71089a842d591bc, tag and revision
sharing-layout-repair-20260925. Actual Run ingressALL, max2instances, concurrency40,
60s timeout,1CPU/512Mi,cpuIdle/startupCpuBoost true. Omitted values are not invented.
WT `live-metadata-minimal-review/read-a6bb92af18194654ab61870bc53d6995/result.json`
SHA256 `27d82d53c1215db6d39f76b3bf662d791fee99164829ebcf93d1117f74ce7e72`.
Initial readf7f... remains UNVERIFIED for unsupported static legal prefix; exact
locally documented five legal routes plus final Run are now admitted,21 pure
controls PASS. No arbitrary routing, redirect, secret/log exposure or mutation.
This closes the observed binding only, not IP provenance, load, alerts or billing.
Full JVM/lint and current-pair API37 regression follow. Gate15 is not approved.

### Actual local deletion workflow PASS and remaining checks 2026-10-04

Fresh normal debug pair `normal-debug-8e07874c4864438596309ffa873dbbb1` built
in157.641s; app SHA256 `64170e3ccffb821dabb886826fcaf20b7372e4cac2e7b952a6540630c160c9b9`,
test `5da0d817c2fed4c4fc0ac02119410cdcf9b3b129e14e02f1dc1999c29bad1e46`.
Both are standard-debug-signed, not release artifacts. Sources and protected outputs
unchanged. Installed hashes were independently checked on owned API36/5556 only.

The real local deletion-request/Android SDK workflow passed1/1 in2.257s at19:15,
using Auth/Firestore/Storage emulators and actual deletionRequest handler202.
Existing Session reaches NO_ACCESS, the consumer closes/denies later capture,
and all15 private tables/photos/consent remain unchanged. Actual same-clock retry
retains the pending65-minute fence. Server proof, exact fixture-only cleanup,
port/reverse/instrumentation cleanup and protected-input guards all passed.
Receipt in WEB `outputs/collection-sync-session-deletion-local/2026-10-04T17-14-48-739Z-6900c5e290e2441c8032c84d13042e68`:
manifest SHA256 `b641d43f70d0770ea7972418ceea02a8e5c3b81d70097393842b551d091c98cf`;
server proof `a03e92233971496420e9e80de63f06ea14b5c14073abd9fe37acacb63af25533`;
Android log `bdbcc2573fb8ee493b81f2372cdfea4083e74500a4d05319a4fe39af8f034c5f`.
No natural65-minute expiry, final provider purge, live Google/AppCheck, isolated
listener causality or legacy-job repair is claimed. Historical lock-only30s RED remains.
Only identified5556 was shut down after completed cleanup; Redmi untouched.

The additive native ERROR-write46 suite built131.140s under `c0edd05b1900456fa5af47d25d840437`.
Preflight passed. Installation actually returned exit0/Success, but the historical
generic parser rejected `error` inside its exact local artifact directory name.
Original `runtime-install-bc152381ea7142da878d3807f7979c6b` remains FAIL, not rewritten.
Guarded cleanup `runtime-cleanup-60c1ca0d9b15451595f3e555903dc236` passed, removing
only the identified isolated app. No46-case runtime result exists from that attempt.
Private adapter now validates the exact three-line push receipt, bound APK path,
byte size and positive decimal rate/duration; the complete install lifecycle is
source-compared with the original except that parser call.19 pure controls pass,
including reproduction of the original path false-positive and explicit negatives.
The six old helper files, normal app code and historical receipts are unchanged.
Fresh46 build/runtime and full JVM/lint remain next; no speculative new test block.
No release signing, push, deployment or Google Play operation in this continuation.

### Import error-reporting regression and targeted fix 2026-10-04 19:10 Europe/Berlin

The old error-status callback can throw out of Result.onFailure, replacing the
original failed import; a cancelled Job can also expose a non-cancellation cleanup
failure. A behavior-preserving extraction retained the exact operation, actual Room
ERROR writer and import guard. New11 focused JVM cases genuinely reproduced three
predeclared assertion failures with eight controls passing; zero skips/errors.
RED `jvm-red-731738a473a9481a9d2f4c1a37de7e27`,109.718s; result SHA256
`152510a6c51f2c31c5556c769e4d6fbe3b0664ccde43b10bedd91b0394e557c7`,
raw XML `91ff0c9ce21c4b303bd9c69c994bf8bdaa64bc697e6bea4fbaf17ac52a8ee847`.

The narrow fix catches ordinary reporting failure, checks coroutine cancellation,
and attaches a different reporting Throwable as suppressed to the original failed
Result. Direct cancellation still propagates; successful imports do not report an
error. Existing provider routes, atomic persistence, maintenance scheduling, schema,
mutexes, Sync and retry policy are unchanged. No actual process-crash claim.
Same frozen11 cases now PASS, zero skips/errors,113.735s:
`jvm-green-9da65bff21f64331ad7c085a7439445b`; result SHA256
`24c5d7ed2683e0e7ee6868c4d9348c90e13b630599adbba87c7a7ba1ad71ef99`,
raw XML `0086c2b22dbb8a21f96380d62a065522dfa682e66e46fa7746897db2ac56579e`.
All protected source/output inventories remained unchanged within each run;
11 pure validator negatives also passed. Receipts live under maintenance WT
`artifacts/release27-engineering-20261004/storage-prerequisite/import-error-status-review`.

Synthetic callback exceptions establish this Result/cancellation contract, NOT a
native ERROR-write or complete network-import/crash proof. An additive real Room
page-limit ERROR-writer test and full subsequent regressions remain pending.
Normal debug/test build is now running for the new deletion-workflow SDK proof.
No release signing, push, production or physical-device change; Gate15 still locked.

### SQLite-full observer proof and fresh deletion preparation 2026-10-04 19:00 Europe/Berlin

The independently reviewed fixture-only native exception observer now establishes
the actual SQLiteFullException at the unchanged real generated catalog INSERT
execute() boundary. It rethrows the identical object; the outer known Room rollback
mask is accepted only alongside that exact per-attempt native witness. No product
SQL, transaction, deadline, original44 test or preservation oracle was changed.
Both previous45th-test failures remain retained, not overwritten or called PASS.

Isolated build `40116ed93c4b49a5ab8ff76a45d2b8b7` passed124.969s; check/install passed.
Runtime `runtime-run-1b9b2d2d601d4a6d943be1a86fd341e6` passed45/45, zero skips/failures,
109.877s. All source/output, identity, offline, ordinary-package and terminal guards
passed. All15 tables, private fixtures, schema, sequence, photos, ticket and cursor
are preserved through the actual failed import and reopen; lifting only the owned
page cap allows the same prepared import to succeed, followed by another reopen.
This is bounded SQLite max_page_count exhaustion, NOT physical ENOSPC, WAL,
power-loss,13GiB or the outer import ERROR-status-write proof.

Receipts below the maintenance worktree's
`artifacts/release27-engineering-20261004/storage-prerequisite/sqlite-full-proof/runs/40116ed93c4b49a5ab8ff76a45d2b8b7`:
runtime manifest SHA256 `96a1ae6ee7185551ae8897dd1ddb039162e94ea0ae0e6c4eaabfdbd8888d5ee9`;
JUnit log `2960f704e2a7ea7d980e0b24b6485a0845148de070c8e431304a457dc92b98c4`.
Owned-package cleanup `57d6412efcda43a1b200d551e0d6c271` passed; manifest
`7f51b431b7843e935b397e7ff86af09fc2a225c9ec815612d4696efdbb376bc0`.
Only identified emulator5558 was shut down afterwards, without ordinary data clear.

The fresh actual deletion-workflow proof packet is locally applied but not yet built
or run on Android. Its complete host pure suite passed92/92, zero skips/failures,
18.461s; saved output `g9-deletion-workflow-review/pure-host-92-output.log` SHA256
`36a1563b7b9a16e6c0f30d45f72397ee742674b8bd1f3639636f8b913b7eb1f5`.
This is not a Firebase/Android runtime or final provider-deletion proof.

A distinct static importer defect is under RED-test preparation: the existing
Result.onFailure status writer can itself throw and escape the import Result.
Its effect differs for immediate launch and WorkManager; no process crash has been
measured. Proposed changes remain artifact-only pending cancellation review and
genuine regression. Full-Sync retained; no release signing, push or deployment.
Gate15 remains locked by the separate critical evidence boundaries.

### Second fixed same-binary API37 full regression 2026-10-04 18:41 Europe/Berlin

The sole predeclared repeat `16a32691058043a7bd18b7e7665e879c` passed371/371,
zero failures/skips,335.961s, on the fresh observed boot. Both diagnostic parsers
passed with no errors; source/APK/environment checks remained exact. Same app
`58bbfa38...39427` and test `2e7be736...aceca` as the earlier394.237s PASS;
no new code, timeout, fixture or lifecycle change between these completed suites.
No claim of speed improvement follows from whole-suite duration variance.

Receipt in the maintenance worktree under
`artifacts/release27-engineering-20261004/compatibility-remaining-review/ocr-call-timing-review/runtime-api37-full371-16a32691058043a7bd18b7e7665e879c`.
SHA256 manifest `9085112aa711f0c94e723596a37a20f4cf9774ce4abdcefa292cc51490620be0`;
JUnit `79b944801e5e82ee7211e1ea0bc7503cfeac2d5eb170fff44ec55c2602fe3e91`;
diagnostics `8c01b9795faf68273153c5458a41a2feb6065203bc3a987958d9f12684c1aca0`;
terminal `905564330e0a237ad0190180b269b4d4d29bda4aaeb11f229cb5b3a19d8f6e23`;
raw OCR `9743afdb5824d26f0f6774e451c58457aa50c645bf082868d982d2db38c1efab`.
No active instrumentation/main-app PID/camera clients at terminal; cached test
PID3883/UID10230 state18 remains explicitly recorded. Not a native-unload or
no-background-work proof. Root shut down only the identified emulator after
successful terminal checks, without data clearing. The historical370/371 OCR
failure remains retained and unexplained, not relabelled fixed by these two PASSes.
SQLite-full and real fresh deletion-workflow tests remain pending; Gate15 locked.

### SQLite-full actual negative result 2026-10-04 18:21 Europe/Berlin

Update18:36: the predeclared second same-binary API37 run is in progress under
token `16a32691058043a7bd18b7e7665e879c`, boot `e24cef40-a38e-46ac-96c6-96ad8791e3ab`,
profile SHA256 `c680abd4d4286acd407d37cd94899a9c3b2054f7350e8e224681f8b43f501b3b`.
Preflight initially rejected an existing cached-empty app PID2873/UID10229;
Root independently verified installed APK hashes, absent instrumentation/camera
clients, and stopped only that pre-test emulator app, without data clearing.
Next preflight `f6be0ed217ff4e6cb84d4cb828599b3d` passed with all input guards.
These were preparation-only checks, not repeated JUnit until green. Exact371
identities/61 selectors and diagnostic contracts remain unchanged. No result yet.

Update18:28: diagnostic build `3e478acfdef74a2491d90e75cbac11d2` passed140.28s,
check/install passed. Runtime `runtime-run-1c7bfda1d4014a3b82d1b7460300a88d`
again passed original44 and failed the new45th (113.505s). Attached actual cause:
`android.database.sqlite.SQLiteException: cannot rollback - no transaction is active (code 1)`
from SQLiteSession.endTransactionUnchecked439 / RoomDatabase.withTransaction2048.
This confirms outer rollback masking, not an import data-preservation PASS.
Cleanup `runtime-cleanup-02dca64fe39e4301b49871d9dbc6c662` passed.
An independent review proposes observing/rethrowing the actual SQLiteFullException
at the unchanged real compiled INSERT execute() boundary in the fixture only;
all15 typed-table/reopen/same-import oracles remain mandatory. No product change
is justified merely to satisfy this test. Original failures remain retained.

The isolated45-case build `4dabe0ba7fe54a4fbe85d5e961b58e6f` succeeded136.844s;
fresh check/install passed. Actual runtime
`runtime-run-0cb2e44ac8bf4959998afc0f29c91084` failed45th case after114.135s:
all original44 passed, but `realImportSqliteFullRollsBackAllRowsAndResumesAfterOwnedLimitLift`
did not observe the required actual SQLiteFullException in the caught cause chain.
No rollback/reopen/resumption PASS follows from this. Cleanup
`runtime-cleanup-95651365cf5541bf99c4aaf5eb39c8a7` passed; ordinary packages,
Redmi and production were untouched. Original negative receipt remains retained.

A subsequent read-only SQLiteLog query shows native error13 during the real
catalog INSERT followed2ms later by error1 during ROLLBACK (no active transaction),
at16:12:18 device time, PID10313/thread10345. This suggests cleanup may mask
the original exception, but does not by itself establish the required operation
cause or complete data-integrity proof. A diagnostic-only isolated fixture change
now attaches the original caught throwable to the same rejecting assertion.
No product source, selection45,90s bound or data oracle changed. Source SHA256
`5ececdd591c2797a2fb91ad2ef60fe8cacd7739f8e0a05326694fb184204de08`;
all12 pure adapter tests passed0.080s. Fresh build/runtime pending.
The one predeclared same-binary API37 repeat remains pending after cleanup.
Gate15 remains locked; no release signing, push or deployment.

### Actual OS wake and same-child completion 2026-10-04 18:03 Europe/Berlin

Isolated debug build7 `97f18412c2d7447f93a648aca3ecd14c` passed in160.125s,
with protected sources/ordinary outputs unchanged. Fresh check/install passed.
Runtime `runtime-run-eb7dc56b77a44ab4856fc421a796f872` then passed the complete
bounded OS-triggered fresh-process/same-persisted-child proof on API26/5558:
parent `819d39a7-6195-4b39-83b6-ee92768931e4`, worker PID7506; genuinely
constraint-stopped/requeued child `cb522acb-8455-489d-94af-0c21c4130697` resumed
under OS-started PID8153, without replacing its UUID or dependency.
Actual system_server PID1711/UID1000 process-start events plus matching
SystemJobService-before-worker delivery were retained for both wakes. Parent
128 transactions/129→2 and child2→1 completed with exact protected table/photo
oracle, cleared maintenance state and two successful singleton assertions.
Measured device gates1413ms/1195ms stayed inside the unchanged5s bound; unchanged
30s worker/120s proof bounds applied. Normal GreedyScheduler remains enabled:
this is not an exclusive-SystemJob dispatch claim or an OEM/physical/reboot test.

Manifest SHA256 `7c40afbd09d75a0ca7f7ee6d2d3d9944cf0de64a224e6a6719b693a2c9eff9a4`;
original final verification `974fd960666588f7d65402fbcd2791ee5146cc405c1cc5936448b634f09b846f`.
Battery restored exactly and all final source/device checks passed. The first
explicit cleanup removed both owned packages but its subsequent SDK-property
read returned ADB exit255; `runtime-cleanup-d1fe89d0880c450bbd6b4ab747146811`
remains FAIL. The separate cleanup verification
`runtime-cleanup-43d14285c0f9484eaa95de63e2e122d0` passed against its
already-cleaned ownership receipt with all final guards; no runtime PASS was
rewritten or enlarged. Only owned synthetic packages were removed; ordinary
app data and all host proof receipts remain.
Prior failed proof attempts remain retained. SQLite-full and the predeclared
second same-binary API37 run remain next; no release/Gate15 approval follows yet.

### Fresh API37 timing-enabled full regression 2026-10-04 17:48 Europe/Berlin

Update17:56: OS build6 `37239f59a4e94bf18ccd805c9c1ed1a8` succeeded176.234s.
Check/install passed on freshly observed API26 boot
`3033979d-db02-460f-820e-841235bdfd85`, profile SHA256
`e7efe0eaeacd9c225e3b3408b17c366c768f508286904f8b531e7afd59fadf2a`.
Actual runtime `d34c79aff1d34b598f9d657b73571367` remains FAIL before OS wake:
the baseline contains real user0/UID1037 WebView preload-helper process events
with two empty hosting fields. Original baseline SHA256
`fe5fe976b09e8e403e15d90a6bd59193e5b2d43d04e62fe928cb895922d3bb48`.
Battery restored exactly, final guards passed, owned package cleanup
`a6e2902898da4833acb371e13715b53b` passed. Two additive pure cases first
reproduced the error (10 pass/1 error), then passed11/11 after the parser
admitted only those two observed helper names with user0/UID1037/both fields
empty. Exact owned service-launch validation is unchanged; no product code
or test deadline changed. No runtime PASS is inferred from this parser fix.

The fresh normal debug pair (app SHA256
`58bbfa38c2db6bcaa895111fa526a1640f3419bbf085aa3ef909c05136039427`,
test `2e7be736b83c4ede598043d621a4c21234ba61b50465f022f687574c091aceca`)
built in208.000s and was separately verified against the existing Android Debug
certificate. Only owned API37/5560 received this pair. Its fresh observed boot
`be83254d-300c-434b-8321-3d3091f00178`, image/AVD metadata, dependencies and APKs
are bound by profile SHA256
`47735e09d45609ef63f6341f547db2fa2a36593df282bba9fad0613e222c238e`.

The original371 identities/61 selectors then passed **371/371, zero failures,
zero skips**. Timing and original probe parsers both passed, with unchanged
source/protected-output inventories. Receipt directory in the maintenance worktree:
`artifacts/release27-engineering-20261004/compatibility-remaining-review/ocr-call-timing-review/runtime-api37-full371-0ddf0d0f06d74983a7efdab75bbe95f2`.
Manifest SHA256 `ec30a05a40554393ddf7f5eb75fbe0b805f983c90c1419365d564fedc809a3da`;
instrumentation `05f8ecbf3316dda228fdbad509eb5bfda3377a06acc752ac62cabd238732a898`;
diagnostics `a7bde27d484d1580557b77a17a37967cae8a3f2ed5663d9ee1f4194b94b35f74`.
The explicit terminal contract observed no active instrumentation, no main-app
PID and no current camera client, while separately recording any cached test
process. It does NOT claim all processes absent, native unload completion or
absence of background work. Historical failed receipts remain unchanged.

The formerly failing cancellation-followed-by-new-identity test passed in this
run. Its first B number task completed1854.526ms after process() returned and
reached serial processing0.525ms later. These are task-callback/dispatch intervals,
not isolated native CPU measurements. This one green timing run does NOT prove a
fix for the previous intermittent370/371 failure: only bounded diagnostics were
added, deadlines/fixtures/assertions were not relaxed, and no SDK-lifecycle fix
was made. A/B trace clocks have separate origins and must not be subtracted.

The durable OS-launch witness correction was independently reviewed; its9 new
pure cases and25 existing host cases pass. Actual OS/SQLite-full proofs remain
pending. API37 was stopped only after terminal completion, without clearing data,
then the next isolated OS proof build was started. Full-Sync remains, Delta off;
Gate15 remains locked. No signing, push, deployment, Play upload or Redmi action.

### Continued local verification 2026-10-04 17:06 Europe/Berlin

Update17:29: Fifth OS build `39dbb3d9c0e64197b6e846377af64de9` succeeded
in228.953s. One preflight-only attempt `db99761117b94aa1bf47db6b1b4c9f5e`
failed a20s pidof transport bound before any test/battery mutation; final
guards passed and its receipt remains FAIL. The same byte-identical pair's
first actual attempt `d565110d7e4a441b9895f655830cca4c` reached parent128
batches/129→2 and true constraint-cancelled ENQUEUED child
`a1a09cb8-854c-4085-9427-c410224950c9`, preserving its dependency across
normal termination of PID14461. The OS restarted PID14994, same child UUID,
attempt1. This full attempt still remains **FAIL**: its mandatory live service
binding witness was no longer present and its unchanged5s gate expired.
Retained command0277 has actual child onStartJob before Worker creation/gate,
but `dumpsys activity services` shows `(nothing)` after the normal service
callback had already returned. Root separately observed system-server PID1714
`am_proc_start` with exact PID14994/UID10087/owned package, reason `service`,
component `androidx.work.impl.background.systemjob.SystemJobService`. This is
useful diagnosis, not a retroactive PASS or completed child-body assertion.
Battery restored exactly, final guards passed; cleanup
`9e689e9f09104662ac71f52d2758fb20` removed only owned synthetic packages.
API26 was then stopped, without resetting its data, before normal debug build
and the next API37 scanner measurement. Redmi remains untouched.

Update17:18: Fourth build `b0e5d98afb4d4b678803357729a79ccd` passed
in166.360s. Actual parent OS wake/PID11645, SystemJobService-before-Worker
ordering and bounded1212ms gate passed. Parent completed128 batches/129→2
rows with exact protected state and created child
`7f87f11d-3803-4160-ac61-899d5937e92e`. The complete runtime remains **FAIL**:
the artificial bare `awaitCancellation()` hold allowed a weak future
completer to be garbage collected (`FutureGarbageCollectedException`). Child
became FAILED, not ENQUEUED; the unchanged assertion correctly rejected it.
The cancellation marker alone is not an OS-cancellation proof. Evidence:
`runtime-run-9f6e28af641243b0861f2c4df63b0a37`, plus root's exact
`observations-b0e5d98a.txt`. Battery restoration and final guards passed;
receipt-owned cleanup `2e7bdd4da201466b9c111263a7f534de` passed.
Only the fixture now retains its artificial hold in a process-local
`CompletableDeferred` that is never completed, preserving genuine Worker
cancellation and every original state/deadline assertion. Independent static
review found no blocker; the next fresh runtime is still required. No product
fix or complete scheduler clearance is claimed from this fixture change.

The third OS proof debug pair `ae405270cd01459996e17a038700ec0f` built
successfully in145.563s, with frozen sources/protected outputs unchanged.
Its actual preflight and owned installation passed. Runtime
`runtime-run-bb33b9b9614f4441a683da72867260e9` remains **FAIL**: Android8's
SQLite3.18.2 shell does not implement the `-readonly` command-line switch.
Original command0077 retains the exact error. The host therefore stopped
before making the claimed OS-continuation assertions. Root's separate
read-only observation confirmed `file:<exact owned DB>?mode=ro` can read
that actual WorkDB; an absent URI DB fails without creating a file.
This is a proof-host compatibility issue, not evidence of production loss.
Battery restoration and final guards passed. Explicit cleanup
`runtime-cleanup-c1c2a018f4e942988ff815f6fb7c8e90` passed, removing only the
receipt-owned synthetic package pair. All original failed receipts remain.

The independent scanner lifecycle review found a candidate boundary, not
a proven cause: a session-specific MLKit client shares the SDK's underlying
option-keyed resource, and public `close()` returns before its queued unpin
necessarily finishes. Existing cancellation tests prove app session/task
lease cleanup, not SDK unload completion. Fresh task/dispatch timing is
required before any product change; no timeout extension, private SDK API,
test relaxation or artificial waiting was introduced.

### Resumed local verification 2026-10-04 16:42 Europe/Berlin

The first isolated OS-scheduler build (`storage-prerequisite/os-scheduler-proof/
runs/4c707189e014406094b2057a43a2aec1`) failed compilation because its fixture's
`assertFinalData` called suspend DAO methods from a non-suspend function. Only
that fixture declaration was changed to `suspend`; both callers already run in
coroutine contexts. No production scheduling behavior or assertion changed.
The fresh debug-only pair `b95e1e3f71d142f28dbaf81fa70a8420` built successfully
in119.969s, with both source/protected-output snapshots unchanged. Its actual
API26 preflight and receipt-owned installation passed; ordinary app/test APKs
were preserved. The separate reviewed profile binds boot
`7c069d2f-fc00-4cf9-beb0-288670d66e5a` and SHA256
`0777aae7070decfd34caae0d2fc73a1f6eb23f2311344a68226bd5c75b6c4888`.

The first preparation singleton passed on-device (1/1,1.864s), but the existing
generic instrumentation parser only recognizes plural `OK (N tests)`, not the
actual singleton `OK (1 test)`. The original complete run remains **FAIL**:
`runtime-run-aaeb827d1e4446c48ed5b8e8bb4e71d0/manifest.json`, SHA256
`2ca849985916311fc786df52e8069bd968a27f3773fdba55e5578c38462c6093`.
Original phase log SHA256
`ba86c80507c918b6ae90f40816e09b3576e0b2b7c709668bef7e22c4b31d13f6`.
No OS process-restart/continuation PASS is claimed. Battery simulation was
restored exactly; final source/device/ordinary-package guards passed. Explicit
cleanup removed only this receipt-owned synthetic package pair, preserving all
host reports and ordinary app data (`runtime-cleanup-a80453aa6e8c4031b28115b8c3929e7e`,
manifestSHA256 `73a6ad37f2a29b56c3007e79d134346e357911c65ad360df34d0efe4168ec731`).

The bounded debug-plus-explicit-observer OCR timing diagnostics are now compiled
in that isolated build, but not yet runtime-verified. They do not change OCR
deadlines, fixtures, thresholds or native-call count. A fresh complete JVM/lint
run for151 suites/1052 cases completed: **1051 PASS, zero failures/errors, one
unchanged named optional CardmarketLiveSmokeTest skip**,233.453s. Debug lint:
zero errors,144 existing warnings and4 hints. Both source/protected-output
snapshots remained unchanged. Receipt:
`compatibility-remaining-review/ocr-call-timing-review/full-jvm-lint-5a53e416195d4329982369f49c5c6f9e/result.json`,
SHA256 `2003ca6b0d1093a164a564bff466086d05c3102f9034179960652a162aeb0d82`.
The seven singleton grammar and four wake-command host tests also pass, without
changing the generic pinned instrumentation validator. The previous
API37 full370/371 failure remains open. Full-Sync/Delta-off, Gate15 lock, no
release signing/push/production deployment/Play upload and untouched Redmi remain.

The next OS attempt (`54c47ade9b9c40e7b405bee8cabb6d94`) built in140.281s and
its preparation singleton passed1/1 in1.983s. Its strict host report readback
then failed because API26 cannot exec the shell builtin `test` through run-as:
the retained command0067 stderr is `run-as: exec failed for test: Permission denied`.
The owned fixture report demonstrably existed. Root's read-only checks confirmed
the exact `/system/bin/sh -c 'test -f <owned path>'` form returns0 for that file
and1 for an absent fixture path; direct toybox test is unavailable. This is a
host-fixture compatibility correction, not a production data-loss finding.
The original full run remains FAIL, SHA256
`788eb1f62ed96f3d378a02f1c5a1132ebabf7249fc6fbe22fa56d84455a57293`.
Battery restored exactly and final guards passed; explicit owned cleanup passed,
SHA256 `a95574c8c3bb98b0bb9ef8eb24c92b7b0b285ba994c8471417a46ca329524edc`.
The new helper accepts only strict ASCII owned run/name components, empty output
and integer0/1 exits. All18 proof-local host tests pass (7 existence,7 singleton,
4 wake-publication); the next fresh complete run is pending. No original
JUnit condition, real worker deadline, scheduler or product code was changed.

The separate bounded OCR timing parser also passes20 pure tests. It requires
four exact run-token/PID-bound snapshots with contiguous entries, no overflow
or clock errors. Missing task completion is explicitly unknown, not a pending,
failure, success or native-unload assertion. This is parser proof only; it does
not supersede the Android17 full370/371 RED.

### Current runtime checkpoint 2026-10-04 16:18 Europe/Berlin

The current normal debug pair (app933850685d66/testa0286d8e1280) ran the
unchanged full371 registry on API37.1/16KiB: **370 PASS, 1 FAIL, zero skips**,
305.630s. The unchanged cancellation-followed-by-new-native-OCR test still
returns no number instead of098/197. Its resource/cancellation assertions pass;
the subsequent two same-analyzer identities both pass. The earlier focused8
PASS is therefore not a complete scanner clearance. The failed capture starts
two prioritized native calls but has no completed session readout before the
5.053s probe deadline; it safely reports insufficient orientation evidence.
This differs from the earlier unstarted-number priority bug. SDK completion,
callback dispatch and deadline timing are not yet separately measured; no
deadline, fixture, recognition assertion or acceptance threshold was relaxed.

Original retained worktree proof:
`compatibility-remaining-review/api37-full371-current/runtime-api37-full371-927795ea2e204f54aa83b776959c02db/`.
Manifest SHA256 `ffac1456808b34bcf756a37a203194df3839c386f1fe4dd5e635c5427dd3a129`;
raw instrumentation `313dc6c7c022a0e688322723ac8c4bec06e026dd784303aafb97acdc53f11992`.
Both source/output inventories remained unchanged. Wrapper FAIL and null
complete-diagnostics verdict remain preserved, not rewritten as a pass.

The additive actual SDK singleton **passes** on the same APK pair in owned
API36/5556, boot980e87a1-5412-4299-a452-77c3b06d3512:2.163s, one test, no skip.
Actual SERVER-backed shared knowledge is ready before the atomic own access
disable/deletion-lock write. Observed Session NO_ACCESS invalidates the shared
consumer, closes its observation and prevents reuse without polling, Auth
changes or new knowledge writes. It does not isolate which denied Session
listener reports first, and does not supersede the original lock-only30s RED.
Web proof `outputs/collection-sync-session-consumer-local/2026-10-04T14-16-42-516Z-694ee79d04564a8caa5c63cb889252f5/manifest.json`,
SHA256 `c123d1f0cfcef76b69cd1e910a2b34209e135389a51441e2b70e8f474b0e3c3a`:
exit0, atomic CAS cleanup, complete unchanged synthetic tree/accounts, ports
and reverse mappings released, no active instrumentation, frozen inputs PASS.
Both installed APK byte hashes and normal debug certificate61e8314b147e were
independently checked. WT Rules were mechanically normalized CRLF→LF after
semantic byte comparison; they now exactly equal Web Rules, with no rule change.

API37 was stopped only after matching owned AVD/boot, no active instrumentation
and no camera clients. API36 was booted without wipe/snapshot. Redmi unchanged.
No signing, production deployment, Play upload or Git push; Gate15 stays locked.

### Deletion access-fence lifetime: local RED/GREEN (2026-10-04 16:22)

The existing account deletion path removed `accountAccess/<uid>` in its initial
personal-data sweep, before its existing65-minute post-revocation expiry fence.
The target's disabled payload could therefore be short-lived. This is a lifecycle
defect, not proof that every remote listener coalesces those writes. A narrowly
scoped backend fix keeps this exact owned inventory item unfinished until the
existing post-expiry sweep. The start transaction replaces old role/lease fields
with only `{disabled:true, updatedAt:now}`. Normal personal-data cleanup continues;
the final sweep removes the access item and accounts for it in the receipt.
No new worker, timer, Rules, expiry policy or job schema is introduced.

Real local Auth/Firestore/Storage regression in the Web checkout retains all35
existing test identities and adds one boundary/retry/foreign-protection case.
Added assertions first reproduce **33 PASS / 3 FAIL / zero skips** against the
unchanged backend (`outputs/private-operations-local/2026-10-04T14-18-04-023Z-e7e8563c/`,
manifestSHA256 `671708d18a9aa82c2f085c7858f5b163e343ccbcfdd0f43d7613ac26d5f1db18`).
With only the narrow backend patch, identical tests pass **36/36**,42.349s test
time (`2026-10-04T14-20-11-689Z-a6a5cb93/`, manifestSHA256
`8d54c3dd7c75d7aa3839e7a7f6fd9b9e88948f3c05eef123aa42f1e61b2a90a5`).
Both retained manifests have unchanged source hashes and released local ports;
actual Web add/increment and rejected-collision readbacks remain verified.
Backend sourceSHA256 `07938f9340b2e253d156f337bc2ec78715cbd193d8d38ebd446ca531676ad050`.
The17 host-safety tests also pass; none is a substitute for Android delivery.

Scope is **fresh deletion jobs and their retries**, with an injected clock, not
a65-minute wall-clock proof. Previously pending jobs whose old access document
was already removed are not silently repaired/migrated. Concurrent role admin
can still append metadata while disabled remains true; permanent minimality
under such concurrent writes is not claimed. REVIEW_REQUIRED/failed jobs can
retain this fence until operator continuation. Original lock-only SDK RED and
production retention/operation proofs remain separate. Not deployed.

The subsequent complete isolated Web unit regression also passes:78 files,
1,295 cases, zero failures/skips/todos,24.580s. Original report/log:
`outputs/release-local/2026-10-04T14-22-04-802Z-6996.{json,log}`. Network and
ambient credentials remain excluded by the unchanged release-local runner.
Existing JSDOM canvas-not-implemented and React act warnings are retained in the
raw log; this is not a rendered browser QA or a production deployment proof.

### Observed scanner-access denial: actual RED/GREEN (2026-10-04)

The scanner's community-knowledge consumer could retain and reuse cached hints
after the existing session had reported `NO_ACCESS` for that same owner. The
shared production consumer is now generation/owner scoped: observed denial
invalidates the hint and rejects subsequent cache reads and late callbacks for
that owner during the consumer lifetime. External listener cleanup occurs outside
the state lock. Foreign-owner denial and ordinary offline/error/checking states
do not revoke legitimate offline knowledge; reviewed/pretester knowledge is
unchanged. There is no new network request, polling, permission grant or data
deletion. Coarse `ACTIVE`/access-confirmed state alone does not undo denial.

Worktree evidence under `artifacts/release27-engineering-20261004/g9-no-access-review/`:

- `jvm-red-8b11258d0d3c4c319aa34a9e8763a47d/result.json`: real
  **6 assertion failures / 5 passes / zero skips or errors**, unchanged 11 methods;
  raw XML SHA256 `bff2b1f586c9346413d61c7e6fbece00bbceb5675a0be92208b35b71f312d9a9`.
- `jvm-green-f15c9cf2051f4eea83b077b3f4f59836/result.json`: same methods
  **11/11 PASS**, 110.391s build/test command; raw XML SHA256
  `63b36d477f44c2dca907ed4c0716e78cdda837a2dc7450ea107eb7e309bdf7b9`.
- Both runs preserved complete protected source/output snapshots. The earlier
  `jvm-red-8d95842daccf42188de51da17ba2ca9f` was a pre-test init/filter failure,
  not a reproduced product failure, and remains retained as such.

This closes the focused JVM regression only. Compose integration, broad current
regression and genuine SDK integration remain pending. It does **not** change or
close the prior real-SDK lock-only notification latency failure. Gate15 stays
locked; no release signing, production deployment or Play upload occurred.

An independent review then found a real consumer ABA regression: A's capture
ignored an observation's null callback while B was current; returning to A before
the conflated cloud-state collector observed B could revive the old hint. The
additive twelfth test deliberately delivers B/null and returns to A without a
cloud-state-B call. `jvm-aba-red-574355ca05034289a0b7d54d0daac3f8` reproduces
exactly this stale-value assertion (11 controls PASS, 1 FAIL, zero skips/errors),
raw XML SHA256 `3564c80823b596297a46db1261d149b8a6c54553c7dc98209212f5024e7aa3b5`.
The narrow fix permanently retires an inactive capture when its callback arrives,
closing its listener outside the consumer lock. A genuinely new capture may still
use normal offline knowledge; an account transition is not an entitlement denial.
The identical 12 methods then pass in
`jvm-aba-green-c643ae28e7264114a0780c0f93f911a7` (109.796s command, 12/12, no skip),
raw XML SHA256 `1038d4c741212d06aaaa58197079faf50de7b0cd74faf030a0a7bf742507707a`.
Both source/protected-output checks pass. Seven additional pure receipt-parser
tests pass; they are not Android evidence. The previously built standard debug
pair `normal-debug-5a5b798ccd0e4eb68e4fe425f725d18d` passed its build in150.297s
but predates this ABA fix and was **not installed or accepted as the final pair**.

The fresh full JVM/lint invocation `full-jvm-lint-cad78ce0024c427a91507a8e473edb51`
completed Gradle successfully in237.766s: **149 suites, 1,038 PASS, zero failures
or errors, one unchanged explicit opt-in Cardmarket live-test SKIP**. The original
wrapper receipt is retained as **FAIL**: its lint parser did not recognize the
actual report's `Hint` severity. The raw lint report contains144 warnings and4
hints, no Error/Fatal, SHA256
`71933c23e9dd99990fc30dd67db30294e1d1d3640e96694c9e6a90b575c93553`.
This is a receipt-parser compatibility issue, not an assertion failure or a
suppressed lint error. Independent read-only receipt revalidation now passes:
`revalidate-full-jvm-lint.py` SHA256
`6adb24b62b0285de71e093889d33b401af3522e0abb10beda03fba733e911955`
verified the unchanged original result/log/lint, all149 XML reports, fresh ordered
task markers and both byte-identical source/output inventories. Its20 negative
and positive pure checks pass (0.074s); Error/Fatal and unknown severities still
fail closed. Original wrapper/result remain unchanged FAIL, separate verdict is
`OFFLINE_REVALIDATED_JVM_LINT_PASS_WITH_EXISTING_OPT_IN_SKIP`; no new Gradle run.
All protected inputs/outputs remained identical. The new consumer files have
zero lint findings; the flagged CardVaultApp source expressions and four
autoboxing hints already exist in HEADf76feaf. A complete old/new lint comparison
is unavailable because the earlier1027-case run did not archive its raw lint XML.

The actual post-ABA normal debug pair is now built in
`normal-debug-70beca95920f424591725ed4df579142` (155.907s, protected guards PASS):
app SHA256 `7c89b08327bf95ed1933cd9a648e2328342d28232e2feeb33df7e74e37da6ebd`,
test SHA256 `74168e2326db56a83532ccb6cf826cde320f3fe08ce1083379372b7add95e040`.
This pair was subsequently installed and verified on the owned API37.1/16KiB
emulator; its focused runtime result is recorded below, not a release approval.

### API37 focused runtime: real OCR starvation reproduced (2026-10-04)

`compatibility-remaining-review/api37-focus8/runtime-focus8-d8b1b53eaf79415e8139597c2222a09e/`
ran the eight explicit methods on the exact post-ABA debug pair: **6 PASS, 2
assertion failures, zero skips**, 28.379s. The real CameraX all-rotations case,
three actual Compose-consumer cases and two cancellation controls pass. Both
unchanged real-ML-Kit identity assertions fail: expected098/197 or094/084,
actual collectorNumber=null. Neither fixtures nor deadlines were relaxed.
Manifest SHA256 `6a75f2ddf74fc26f158050050d3e3976ac053711169a10d7efc3bdc42810fe9e`;
JUnit log `b636a26216964b6eefca8d1dd423c69f9b60afe7f815bf0e39fa82dcf9da85ca`;
raw OCR log `d87a990d53ecc2b42b26ec66ed623c5f5d0ce6d61e576305ec8e8d894f06a1bd`.

Actual probe wall times are5.011s and5.013s. Only4/3 native OCR calls start;
only2/1 name ROIs complete. The modern number ROI is sixth in the existing
launch order and therefore is never attempted in either capture. Name-only
score20 is safely rejected, clearing identity instead of inventing a match.
This establishes scheduling starvation under the measured deadline, not why
native OCR completion itself is slow. The second same-session capture is not
reached after the first assertion fails. The bounded next candidate is launch
priority only, with original pixel/slot/cache identities and time limits kept.
It remains unverified until actual regressions are rerun.

All source/APK/profile guards pass. The wrapper remains FAIL also because an
app PID persists after instrumentation; raw camera/process dumps are retained.
No automatic force-stop was used to turn that postcondition green. The earlier
368/371 API37 result and the independent SDK lock-only RED remain unresolved.
No phone action, production change, signing or Play upload occurred.

The scheduling-only candidate is now implemented in the separate worktree:
original modern NUMBER groups for each orientation, then original NAME/SET,
then the unchanged stable remainder. Group objects, original result indices,
cache keys, pixels, evidence thresholds, timeouts and concurrency remain intact;
the later full OCR batch returns its original list/order unchanged. Eight added
JVM cases cover orientation fairness, all-slot preservation, out-of-order result
mapping, duplicate keys/blank cache hits, timeout cleanup suffix and conservative
acceptance of unknown sets. No instrumented assertion was changed.

`ocr-priority-review/full-jvm-lint-eac146754b154d2aac82e311cfe0b5b5/`
passes the complete fresh JVM/lint run in225.985s: **150 suites, 1,046 PASS,
zero failures/errors, the one unchanged optional live SKIP**. Lint has144
warnings and4 hints, no errors; both source/output inventories match. The old
Hint-parser failure and its receipts are untouched; this fresh wrapper explicitly
recognizes the observed Hint severity while still rejecting Error/Fatal.
New normal debug pair `normal-debug-5325b88b750a45cdb5aaeb1385a6053b` builds in
153.407s with protected guards passing: app SHA256
`933850685d669070fb44ce8f019b486a5b60999618bbbd2b10c25b42ba172845`, test
`a0286d8e1280f3429e10ab572d97b7a731f94642fc0755fbff3021b9c5819455`.
Both retain the standard debug certificate; runtime acceptance is still pending.

The subsequent exact focus8 runtime
`runtime-focus8-30ae980f4a9e467e835af75781b8e7ea` has **8/8 actual JUnit PASS,
zero skips/failures**, 67.52s. The unchanged native OCR assertions now read
OBF098/197, PBL094/084 and OBF098/197 in the three required captures, each
orientation score98 and empty quality reason. All three full diagnostic records
validate, and source/APK/environment inventories remain identical. These captures
take16.280/16.377/16.209s on this emulator; they demonstrate correct identity
instead of the prior early manual fallback, **not a demonstrated latency win**.
Manifest SHA256 `0488c35eb2ed6f2d9d00b7b9504a0677ddd736ede3ebfac2cc97f67e354b3f57`,
raw JUnit `d93480154a74b413cc9328910b51607243119c2e8a6479f6f33f78b5ff51b274`,
OCR log `d77f525f40e63fa7424bbbe70f5dea58b2aa491841f1e79642987b09b5f9cfc1`.

The wrapper is deliberately retained **FAIL**, solely its strict final empty-PID
guard: AndroidX leaves a cached Recents helper in the test package. Independent
fresh inspection finds no app process or live app/test activity, both service
lists empty, camera clients empty/device closed, and the rotation history names
AndroidX InstrumentationActivityInvoker$EmptyActivity. The separate explicitly
owned test-helper cleanup at16:02:44+02:00 verifies boot049356a3-21a2-40d8-ac15-136a2ce30a54,
test APK hash above, UID10230, PID4337/startTicks28521 before stopping only
com.dominik.cardvault.test. Both app/test PIDs are then absent. No data clear,
phone action or retroactive manifest PASS. The first cleanup attempt refused an
unrecognized dump-header indentation without stopping anything. Full current
API37 regression remains a separate requirement.

### User-directed storage scope correction and API26 baseline (2026-10-04)

**Actual process-restart recovery, completed04.10:** The separate isolated
`storage-prerequisite/restart-host/runs/c432caa5f1e44d789909d69a62d45b9a/`
build and `runtime-run-c592df5d014c491aa8aa9d84f41c178f` both pass. Run manifest
SHA256 `891eeeefd91f5e9818d150ba1eba253f8865b9e24d915904bd40eeaf0ff0e1ca`.
The first real worker performs128 bounded transactions, leaves two of129 genuine
price rows and an actual dependent ENQUEUED continuation. After verified closure
and stopping only that owned test app, process26733 is replaced by process27237.
The fresh process uses the production startup-recovery helper without a new import:
it resumes the original cursor, removes only the final obsolete row, retains the
complete latest row, and clears the finished ticket/cursor. Both singleton tests
PASS with no skip. Raw prepare/resume report hashes are respectively
`16f585221c741e407ff62133df7451e18553b4f4f174d369d3dc50fb17ee62c0` and
`8e26043034b0b25a0e70381f77a65873d44c3dce8a5fb1ae757d615bdcb6e53a`.
Three owners including guest, a tombstone, pending outboxes, typed table contents
and the photo remain unchanged. UID/APKs/boot and closed DB/sidecar/photo/prepare
bytes match across the stop. Both source/output trees, R26, ordinary emulator apps
and final offline/idle checks pass. Explicit owned cleanup
`runtime-cleanup-ab5fcb9331b548ca8349871dd1bfc085` passes (manifest SHA256
`53ca8fd0263e7fca25d82299471fe80c9a9d8c42eb4ee1c9e73d2143c4544e21`).
This proves **startup fallback after a real process change**, not OS JobScheduler
delivery, persisted WorkDB-child survival, reboot or interruption during SQL.
The test-only WorkManager queue is deliberately cancelled/closed before stopping;
that boundary is explicit and cannot be represented as an OS scheduling PASS.

The user explicitly discontinued the artificial 13GiB fill run via the parallel
`Domidex-Aufbau und Skalierung` task and subsequently resumed this task. The
original human instructions and the deliberate package removal were read back.
The run was stopped intentionally and its isolated emulator packages removed;
it is **WITHDRAWN / NO RESULT**, not a product failure and not PASS. Do not restart
it. Its interrupted receipt still says RUNNING because the host process was
terminated; that stale field does not mean work continues. Earlier references
below to a running large seed or a mandatory 13GiB prerequisite are superseded.

The product requirement remains bounded image/price caches, collection/outbox
protection, correct existing Full-Sync, cleanup and real reclaimed storage, and
safe interruption/restart/resume. Smaller deterministic fixtures and existing
contract/pilot evidence must prove those properties. Removing the artificial
size target does not turn incomplete runtime wiring or missing proofs green.
The 500,000-row pilot and earlier completed tests remain valid for their stated
scope. No phone, real collection or Cloud data was involved in that fill run.

The separate maintenance worktree passed its first real Android-8 matrix:
`artifacts/release27-engineering-20261004/maintenance-proof/b5f1392ad33143fb823a9944f43d28db/runtime-run-a382df2cfce74607bc1c78e4001ec41b/manifest.json`
(under the managed worktree), SHA256
`a8b1fed3df07178f1d731c76ee8c530b5b024e19499964776d7e9ed8db1ea120`:
**38/38 PASS, zero skipped/failed**, instrumentation 6.403s. Ordinary emulator
app/test APK hashes, both source inventories and R26/standard outputs stayed
unchanged. Its exact UUID test pair was then explicitly removed with a passing
cleanup receipt `runtime-cleanup-31dfbbe9cf7d41c889471feb402e5c33`; this was on
API26/5558, distinct from the discontinued API36/5562 large-fill package.

Independent review then found a narrower missing case: a later legitimate
provider/device clock reference can clamp backwards, making the active pass's
older cutoff too aggressive for the newly conservative collection window.
The unchanged 38 methods plus the new exact-data regression were executed in
`maintenance-proof/1f0fa75b513c410ca132e32792e3fc72/runtime-run-7b0706fdb7a4462497f3335d8ce422ba/manifest.json`:
**38 PASS / 1 FAIL, no skips**, 5.762s. The failure is the actual all-row equality
assertion after the backwards reference, not compilation or instrumentation.
Source/protected-output inventories and final device guards remained unchanged.
The owned pair was removed by passing cleanup
`runtime-cleanup-c54cef51280840eca08842115e58a8f9`; ordinary apps were untouched.
A seven-line guard now pauses without changing rows, cursor or tickets when the
active cutoff is more aggressive than the newest verified reference permits.
Its fresh identical 39-method GREEN run then passed **39/39, zero skips/failures**:
`maintenance-proof/76e065d085764bf28bd68870b280df11/runtime-run-d6b0634755f1472eb6769fd4f0e8f959/manifest.json`,
SHA256 `a18e81272acbd9ea2b1abbb7cc36afb0455221c11c7054cf59703c75e8b062f9`.
Both source/protected inventories and ordinary-app/offline/idle checks passed.
Its UUID pair was removed by passing cleanup
`runtime-cleanup-2a6b8f914dd54161b728bb749cf3b0ab`. The test proves exact row and
metadata preservation on the backwards clock, followed by resumption of the
original durable cursor when verified time is sufficient again.
The successful 38-case baseline is not represented as covering this new case.
Worker scheduling, actual file reclamation and other critical release proofs
remain open. No R27 signing, AAB, production deployment or Play upload yet.

An additive 40th test is now prepared for 100,002 real price observations,
98 bounded batches, exact independently derived 2,298 retained rows, 14 protected
table multiset hashes, two photo-file hashes, actual cursor resumption and
physical file/allocation reduction. It operates only on its private disposable
UUID Room database. The 120s bound is cooperative around native SQL, not a native
statement hard timeout. Its closed-fixture VACUUM is not automatic production
compaction approval. Its first run is **39 PASS / 1 FAIL**, not a reclaim PASS:
`maintenance-proof/c86a738772194bffae27a36bc3142ff5/runtime-run-bc1de15ffddd41838fe614374db2ef88/manifest.json`.
After the exact row/cursor/protected-data/integrity checks, it failed the unverified
`freelist_count > 0` expectation (13.301s total). The fixture had never selected
or checked auto-vacuum mode. This is an unproven test precondition, not evidence
of data loss. Guards passed; owned cleanup passed in
`runtime-cleanup-29b70f104b074a7cafed9dab53abf621`.

The completed 41-case follow-up preserves the prior 39 tests and separates two
fixtures: explicit NONE configured before tables, retaining all original
freelist/VACUUM assertions; and untouched actual Room default. FULL must itself
reduce file length AND allocated blocks after DELETE/checkpoint. NONE/INCREMENTAL
must pass the strict pre-compaction checks and owned-file VACUUM. Mode and exact
byte counts are recorded, not inferred from an unrelated in-memory CLI database.

Fresh API26 run `maintenance-proof/e4126c9743f94e97818762638c3694d1/runtime-run-b242fd8669564f0eb7c7f3dbedc9678f/manifest.json`
passes **41/41, zero skips/failures**, 20.959s; manifest SHA256
`d8ee9ae7febc303f5d0ee27f7498d1cfc796c0ab067a364302e2729822372379`.
The adjacent `reclaim-observations.json`, SHA256
`75caf6906f44313bfeb4ea0ac2ae86897e57259055bbfd85b6492cb15e3ee088`,
retains the two exact-package/UUID tagged logcat observations. Both fixtures
generated 100,002 rows, examined 100,002 in 98 batches, deleted 97,704, and retained
the same independently derived 2,298-row typed hash. Fourteen protected table
multiset hashes, two photo hashes, integrity, foreign keys and cursor reopening pass.
Actual Room default was **auto_vacuum=1/FULL**: after deletion/checkpoint both file
length and allocated bytes fell **18,059,264 -> 753,664**, without extra VACUUM.
Explicit NONE retained 18,034,688 bytes/4,220 free pages after logical pruning;
offline VACUUM of that exact owned test file reduced both byte measures to 659,456.
This is small-fixture evidence, not 13GiB, every-platform/legacy database or
production compaction evidence. No additional product VACUUM path is activated.
Both source/protected-output inventories and final offline/ordinary-app/idle
checks passed. The exact owned pair was removed with passing cleanup
`runtime-cleanup-08ed4b15e16e4c4896a799b52c504b94`; no ordinary app data changed.

The worktree now includes local Worker wiring after committed import and Ready
startup: confirmed APPEND_OR_REPLACE continuation, 128 bounded batches / 30s
checked between transactions, battery/storage constraints and finite retry.
Invalid-clock/retry-exhausted tickets stay pending. Their WorkManager SUCCESS
means transport completion with `maintenanceComplete=false`, not cleanup PASS;
it prevents poisoning fresh already-appended successors. Fourteen new JVM tests
and actual WorkManager evidence remain separate obligations. The whole JVM/lint
rerun `storage-prerequisite/maintenance-jvm-36ca4cffac534331be29f54e45f24968/manifest.json`
now passes **148 suites / 1,027 cases: 1,026 PASS, the same one optional SKIP,
zero errors/failures**, including all fourteen new coordination cases. Build and
lint succeeded in 2m49s; frozen inputs and standard/R26 outputs stayed unchanged.
Actual Android queue execution is not implied by JVM success. Full-Sync is unchanged.
No ordinary app installation or product cleanup has occurred.

The subsequent real isolated API26 matrix now passes **44/44, zero skipped/failed**,
instrumentation 111.277s. Its worktree-relative receipt is
`artifacts/release27-engineering-20261004/maintenance-proof/9a1543d7cdab4ea3987b94136f8e5011/runtime-run-2a584624ecef429fb11f54d39af134e7/manifest.json`,
SHA256 `1c0f8ca2e0affa7cce9a69102f2a2ec1041406c781487dd8a5ba5b45bea80eea`.
All prior41 identities remain, plus three actual WorkManager2.11.2 queue cases.
The deliberate failure control observes an already BLOCKED successor and then
FAILED parent/child. The real INVALID_TICKET worker terminates as
PAUSED_INVALID_TICKET with incomplete maintenance, permitting the fresh blocked
successor to reach COMPLETE. The real retry-exhausted case records attempts
`[0,1,2,0]`: two genuine retries, RETRY_EXHAUSTED predecessor, then COMPLETE
successor. Both paused/exhausted parents are WorkManager SUCCEEDED, not a claim
that their maintenance completed. All three reports record body/cleanup PASS.

The read-only supplemental receipt in the same build directory,
`runtime-check-af475825ad5b40d49119207bcf2ca866/manifest.json`, SHA256
`1302e4a02aca623d4e4644350c81c3127fc1a51b00372f6c8cbf018616366d55`,
retains exact package/run/invocation-bound `failure_control.json`, `invalid.json`
and `exhausted.json` with byte hashes. Its final guards pass. Runtime source and
protected-output inventories, ordinary app/test APKs, offline and final idle
guards pass; the explicitly owned post-terminal stops are recorded, not natural
process-exit evidence. The UUID pair was subsequently removed by passing
`runtime-cleanup-87d5e2a918b140608e5c9bb1c6a48f29/manifest.json`, SHA256
`b7dd0fb9bbe163d784cf39fb2de83dddbee0bd18115b034b9e5807c837460148`.
This proves the controlled time-based WorkManager test engine with real Worker
and database behavior, not OS scheduling, power-loss durability, process restart
or low-disk behavior. Older RED receipts above remain unchanged. Gate15 remains
blocked; no normal user database cleanup, release signing, AAB or Play action.

The subsequent source-set isolation check moves the Reclaim and WorkManager
queue classes byte-identically out of ordinary `app/src/androidTest` and into
`scripts/verification/maintenance-proof/testSources`. Only two explicit copied
source paths change; the six classes, 44 identities and all assertions remain.
This avoids leaking proof-only WorkManager test dependencies/owned-package
preconditions into the ordinary test build. Sixteen host contracts pass.
Fresh isolated build `b61be36874ec4eb7b494133d25feb6e4` then passes the same
**44/44, zero skipped/failed**, 110.422s, runtime
`043f69601972424e93f2f7ddf8ae4088`, manifest SHA256
`6e9710ac39affc9e75221e7fa52c7bb971c718647a30538fc4b11e4fe7bfe1a4`.
Supplemental read `b793823c0e884af48ef252d069c8fc46` retains all three exact
queue reports, manifest SHA256
`7b29bba8b2876e603ebb4cf96b0239d59b0ef972b0b33b63713b22c5764c00db`.
Owned cleanup `4bd9099059f548c08807f3b459928229` passes, SHA256
`8889e457749a34bda23ab30d5efce72adb2d5fdcd4e36f7c5aa87475827147b1`.
Both final protection guards pass; ordinary apps, production and R26 are untouched.
This additional isolated success is not itself a normal instrumentation-build
or process-restart proof. The subsequent ordinary compile-only check, with no
proof init or work-testing dependency, passes in 126.313s:
`compatibility-remaining-review/compile-standard-6c468d7e13bb4172a8bdae6631a2aba9/manifest.json`,
SHA256 `db8d3eaacd67626a079c9c93e0060042e67b1e86adbdf9309c3ee98aeabaacea`.
Debug Kotlin, ordinary Android-test Kotlin and the Android-test dependency model
all compile. Both complete source and ordinary APK/AAB/mapping/R26 inventories
stay byte-identical. This is not on-device execution or release verification.
It includes additive OCR terminal-cache diagnostics and the G9 consumer
extraction/regression baseline; no denial-fix PASS is inferred from compilation.
The first focused JVM RED starter failed before tests because its init-script
task admission did not account for the CLI test selector. Receipt
`g9-no-access-review/jvm-red-8d95842daccf42188de51da17ba2ca9f/result.json`
remains FAIL with all protection guards passing, not a reproduced product RED.
The intended assertions and existing SDK failures remain unchanged.

Physical compaction remains unactivated. SQLite's [VACUUM contract](https://www.sqlite.org/lang_vacuum.html)
describes journal/WAL protection and up to twice the original file size in extra
free disk, not a hard execution deadline. Existing NONE databases cannot gain
incremental reclamation merely by a pragma: [auto_vacuum](https://www.sqlite.org/pragma.html#pragma_auto_vacuum).
Actual default-mode evidence is checked before adding unnecessary machinery.

### API26 annual diagnostic completion (2026-10-04)

Only the full-plan diagnostic transport in `postFixExplain` is widened from
64 rows to a bounded 1,024 rows / 256KiB total / 4KiB UTF-8 per detail. SQLite3.18.2
expands the same query beyond64 rows; no SQL, model comparison, integrity, timing
assertion or retention behavior changes. The old failed annual receipt is retained.
Eight new adapter contracts and23 unchanged full-report/negative contracts pass.
The first separate singleton build3bb576dcff1f44c1b409645ee298cc38 compiled and
installed, but runtimee01a96ffce9545ab878f36d9eddcd85e failed BEFORE instrumentation:
API26 `run-as` could not exec a standalone `test` command. Strict nonempty-output
rejection prevented a false absence/PASS; no application test result exists.
The exact owned pair was removed with passing cleanupe523186d83fb4b13aed18ac066899e48.
Root verified the shell-builtin positive-directory/negative-file controls and
corrected only the allowlisted absence probe. A fresh build/run is required;
the earlier run is not relabelled and ordinary apps/R26 remain unchanged.

Fresh isolated build314c277849584947aba15ef8eea02693 now passes its exact singleton,
**1/1, zero skipped/failed, 124.915s**, in runtime44816510b8d2416289d7ccfb1b88e6c6.
Manifest SHA256 `797d9dcc164a0d1752ce66975b8ecf7c220507771e3e2aa97d0dfa1a66c4cedb`;
adjacent untouched raw report SHA256
`cd60ad61104e70cdf84d3cac28c66b30784b68f7219a539fc516f16a98b599a4`.
The complete SQLite3.18.2 aggregate plan has113 rows (legacy5), explaining the
old diagnostic64-row abort. All13 samples,8 transport records,365 chart points,
complete owner models,15 unchanged typed table inventories, FK and zero requested
sync pass. The wrapper additionally verifies actual absence of each exact owned
database/main/WAL/SHM/journal file, not just the Android report's cleanup assertion.
Warm median public portfolio2092.1225ms; paired diagnostic legacy DAO+sweep4316.6743ms
versus aggregate DAO+map2030.0594ms. Different paths/order: do not call this a matched
whole-app/phone speedup. Ordinary APKs, both source inventories, standard/R26 files,
offline/no-reverse and final idle guards pass. No release-gate or OS-restart claim.
Owned cleanup439ee1e0d95f48f4abf915a3bac20b7f then passed all final guards.
The full worktree-relative location is
`artifacts/release27-engineering-20261004/compatibility-remaining-review/annual-adapter/runs/314c277849584947aba15ef8eea02693/`;
the cleanup manifest SHA256 is
`dcec8ea464e9b426b23235e69e40619e47b590404a772d5e1e59cea45bb2f0c6`.
This closes the specific API26 full-plan diagnostic failure, not the remaining
API37 camera/OCR failures or the complete storage/release gate.

### Durable-maintenance checkpoint (2026-10-04, continuation, historical)

Engineering checkpoint `f76feaf8a2a451416db968ad1991295d525abc29` was pushed
non-forcibly to `codex/release-27-hardening` and read back from the remote. This
is the retention foundation and proof harness, not a signed release. The main
source tree and ordinary/R26 artifacts remain frozen for the still-running
actual 13GiB seed. No scale PASS is claimed. The owned idle API36 regression
emulator was stopped after identity/instrumentation checks to release RAM;
neither its userdata nor the connected phone was changed.

A separate managed worktree, `codex/r27-storage-maintenance`, now adds an atomic
import ticket and durable maintenance cursor. They do not schedule deletion yet.
Cursor advancement and bounded deletion share one Room transaction; a new import
cannot erase the active pass or have its pending ticket cleared by an older pass.
Fresh ticket authority must match committed verified-clock metadata. The fixed
cutoff is never inferred from maintenance-time wall clock.

Root's offline whole-JVM/lint receipt:
`artifacts/release27-engineering-20261004/storage-prerequisite/maintenance-jvm-9a0556422fbf41129d51144683f98141/manifest.json`
SHA256 `cd8a1c377ad85afbb54e21fc2aff4cf1fdad1afa8a6839ad6200e48e6eb68989`.
It records **147 suites / 1,013 cases: 1,012 PASS, the same one opt-in SKIP,
zero failures/errors**, with all source/protected inventories unchanged. The
fourteen new file-Room tests are separate pending Android evidence; they are
not included in that JVM count. The new fixed API26 harness's fourteen pure
negative/parser tests pass. Its 38-case runtime matrix remains pending.

The later root Web-module receipt
`storage-prerequisite/market-local-tests-b743beebffcd43d2aec89ad3bf91edd4/manifest.json`
records **174/174 PASS, zero skipped**, Node22.23.3, fifteen sources unchanged;
test-log SHA256 `1adcbecc42f0e8d1be25e357eb53eb88bbfea8237f7610b3e47aa2451d0f63b0`.
This adds a mandatory admitted-publisher wrapper and bounded CAS reservation
tests to the earlier 131-case checkpoint. UNKNOWN/orphan reservations remain
charged. The four-generation cap is fail-closed but NOT a continuous-production
lifecycle or physical cloud billing cap. No Web commit, deployment, public route,
Cloud write, signing, AAB or Google Play action is claimed for this checkpoint.

### Storage checkpoint and parallel backend evidence (2026-10-04)

The actual 13GiB fixture is currently being generated on the dedicated offline
API36 emulator; it is **not yet a passed scale proof**. Its prerequisite pilot
`storage-proof/af59f51e91cc4ed8941bd8e2065c48a6/runtime-pilot-6bdfbea356084bc599bd867948ccdac8`
passed with 500,000 generated rows, 113,410,048 live SQLite/main-file bytes,
zero final WAL and unchanged non-price tables. Generation took 15,892ms; this
small pilot is not a representative 13GiB runtime or a production migration.
The large seed uses the same frozen source/APK pair and is slower per row than
the pilot. Its final integrity, bounded prune, restart and physical reclamation
phases are still pending. No user database has been cleaned.

The separately checked-out Web repository now contains only unwired market-data
foundations. Root verification at
`artifacts/release27-engineering-20261004/storage-prerequisite/market-local-tests-fc7cefd0124a453bb2c12a63de7360c9/manifest.json`
records **131/131 PASS, zero skipped**, Node22.23.3, and identical before/after
hashes of the eleven selected sources. Seven tests exercise the installed
Storage7.22.0 SDK against controlled loopback servers: actual CRC mismatch,
generation-pinned read, multipart MD5/preconditions, HTTP412 and finite HTTP
timeouts. No Cloud/ADC/DELETE occurs in these controlled cases. Their servers
never redirect; the test interceptor is not a general network-egress firewall.
The other twenty-one newly added HTTP-download cases prove fixed feed selection,
body/header ceilings, no redirects/retries, and actual socket closure on
deadline/idle/cancellation before fixture cleanup. Reviewer findings about
coercible feed selectors and missing socket-close evidence were fixed and rerun.

A separate **read-only** real-provider run at
`storage-prerequisite/market-provider-read-4b1c5b9243494d1ca390e007508bacf4`
successfully downloaded and normalized the two fixed official public feeds:
74,620 products and 79,688 price-guide records, 74,620 exact matched product IDs,
5,068 explicitly accounted other-category rows, 374 pages. Original source
times are catalog `2026-10-03T11:44:53+0200` and prices
`2026-10-04T02:40:55+0200`; generation is
`eff5d9f1da4be49866c433bff60a3fd54c0d3a70b0625fedaff61ff533d689b6`.
The source-byte receipts and hashes are retained. Total 12,210ms includes download
and validation under parallel emulator load, NOT an application performance
benchmark. A matching guide row is not proof of a non-null trend or complete
set/card mapping. No snapshot was uploaded or exposed to clients.

Remaining storage/backend blockers are explicit: durable atomic Android
maintenance progress and enqueue-race proof, safe actual-file reclamation,
cross-version/low-disk/concurrency checks, wired Cloud budget/admission and
continuous lifecycle, public read admission, deploy/package/runtime proof.
The preliminary admission prototype never releases UNKNOWN/orphan reservations;
it is not a continuous-production cache. Full-Sync and existing signing/R8/
packaging remain unchanged. The next maintenance implementation is being
prepared in a separate managed worktree so the in-progress scale sources do
not change. Engineering checkpoints are not release approvals or deployments.

### Collection-only history authority and isolated verification (2026-10-04)

The user explicitly approved retaining twelve-month history only for cards in
any local collection, including guest, other-account and not-yet-synchronized
entries. For other cards, only the latest complete source observation is kept;
discarded history is not recoverable and starts accumulating again when the card
is collected later. This does not authorize deleting collection entries, photos,
preferences, outboxes or pending work. Tombstones are protected conservatively.

The new retention primitive is still **unwired**: no ordinary application or
user database has been pruned. Its opt-in collection policy reevaluates direct
card IDs, exact external-product links and stored price selections inside every
bounded transaction. The default non-collection-only contract is also retained
and tested. Full-Sync remains unchanged and Delta remains disabled.

The read-only twelve-month display window is now connected locally to the
repository and UI. A separate trusted-clock foundation records a plausible Date
header from the successful fixed HTTPS price-guide request in the same Room
transaction as quotes and SUCCESS state. A missing clock does not reject valid
quotes or advance the previous cutoff. This is not yet runtime maintenance.

The first whole-JVM/lint attempt for this new work,
`storage-prerequisite/jvm-235228-50c20274`, failed compiling a new test because
`kotlinx.coroutines.test` is not an existing dependency. No test pass is claimed
for that attempt; all source/protected hashes stayed unchanged. The test was
rewritten using existing coroutine primitives and deterministic channel steps,
not a new dependency or real-hour sleeps. A fresh complete rerun is required.

That fresh rerun is now recorded at
`artifacts/release27-engineering-20261004/storage-prerequisite/jvm-000619-ee9057a9/manifest.json`,
SHA256 `273e26e032e3348d75c8afc24406a1654aa31a064b115bc26ff6c1a3ed5ab820`.
It finished successfully in 2m53s with all source and protected artifacts
unchanged: 146 suites / 1,007 cases, **1,006 PASS, 1 existing opt-in live SKIP,
0 FAIL/ERROR**. Debug lint remains 0 errors, 167 warnings and 4 hints. This
validates the eight added JVM cases; the 24 real Room cases are still a separate
pending emulator proof, not included in this JVM count.

A new dedicated offline API36 x86_64 AVD, `emulator-5562`, has a 48G data
partition for the storage proof. Existing emulators and the connected phone
were not changed. The reviewed profile is in
`artifacts/release27-engineering-20261004/storage-proof/reviewed-profile.json`.
The debug-only harness uses a new UUID package, no app components, INTERNET or
CAMERA, exact copied test classes and pinned source/artifact hashes. Its first
stage requires all 24 retention, repository and real-import contract tests.
The later 500,000-row pilot and actual 13GiB fixture are separate proof stages,
not a claimed complete gate. Final seed, restart, byte-exact preservation,
integrity and physical-reclamation evidence remain pending.

The engineering branch has been pushed and remote-read back at
`3d4f91f3ccc89955c402b2f4e2cbfd1cc5ef0c14`; the current uncommitted retention and
backend work is not part of that push. No R27 signing, AAB, Play action or
production deployment has occurred during this verification tranche.

The first isolated debug pair `storage-proof/5d582bcf2e424c55b12ce5132bb14f15`
built successfully without changing ordinary APKs/AABs/mapping or archived R26.
Its 24-case contract stage is **FAIL**, not a storage pass: a newly added test
ended with `Log.i` and inferred `Int`, so JUnit rejected the retention class
(`initializationError`, expected void). The other nine repository/import cases
passed; the fifteen retention cases were not executed. The exact owned pair was
then explicitly uninstalled, preserving host evidence and leaving the ordinary
app/phone untouched. The correction is only explicit `: Unit` plus six explicit
`arrayOf<Any?>` type arguments; independent byte comparison confirms no SQL,
assertions, fixture values or timeouts were changed. A new full proof is needed.

After that narrow correction, the whole JVM/lint rerun
`storage-prerequisite/jvm-001724-d7f5de3c` again passed with 146 suites / 1,007
cases: 1,006 PASS, the same one opt-in SKIP, zero failures/errors. Build time was
4m03s. Manifest SHA256
`5c563057846a32cde00606e237840ff8079686742b36307d03264e502cc282d4`
records all source/protected inputs unchanged. The new isolated pair and its
24-case stage must still be verified separately.

The corrected isolated pair `storage-proof/af59f51e91cc4ed8941bd8e2065c48a6`
now passes **24/24 real Android Room contracts**, zero skips/failures. Evidence:
`runtime-contracts-c691bfda382446408f2ebb18dc1f5d64/manifest.json`, SHA256
`d814d14af86d404a8af693a7e509eee9c4fec98ae6eaf410d8c5f3d1d2f9735e`.
The actual instrumentation summary is `OK (24 tests)` / 2.976s. Source,
ordinary outputs and R26 remain byte-identical; global instrumentation and both
owned process-absence guards pass after explicitly stopping the exact completed
test pair. This covers 15 retention, 3 windowed repository and 6 importer cases.
It is not a 13GiB, power-loss, low-disk or production-worker pass. The previous
failed run remains recorded unchanged.

The new public-market modules in the separate Web repository are not deployed.
Root verified 78 contract/ingestion/immutable-store tests with both Node24 and
portable official Node22.23.3. The Node22 binary is local-only under
`storage-prerequisite/node22-d4a84ff7ec944b1c856a833fe2500265/node.exe`, SHA256
`9c9245166b4a8e182e0b797da9c20136117ff24368eaff1fec8343a123c8db0e`, matching
nodejs.org's release checksums with Valid OpenJS Authenticode. No system runtime
or default PATH was changed. The same pinned real feeds normalize to 74,620
products / 374 pages / generation
`0b19503449f614fd76acefc86dbc25e6ee44c5863f94c73f48679300030b1795` on Node22.
This proves this input snapshot, not all physical-card identities or non-null
trend coverage. Linux packaging, actual cloud storage, HTTP boundaries, bounded
generation lifecycle and production verification remain separate open work.

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


### Storage foundation, verified source backup and deployment scope (2026-10-04)

The authorized engineering branch was pushed successfully and remote-read back
as `339ac430dabc33164af5005912028189fd746730`. It preserves the previous hardening
history and separately records artwork/search (`9acf47b`), Espresso compatibility
(`9700103`), audit tools (`d469c4f`) and status (`339ac43`). `master` and the old UI
branch were not replaced. No GitHub Release, tag or Play upload was created.
The new artwork audit helpers passed all46 Node cases, no skips/failures. A scoped
signature/path scan covered all95 previously outgoing Git text snapshots and the
then49 changed/untracked files: no real credential finding in those categories;
one incomplete private-key header is an intentional negative test. This is not a
guarantee against arbitrary obfuscation or an audit of binary/archive contents.
Local hardware-proof scaffolding and all ignored data/build artifacts stay local.

Two small storage foundations are now locally verified:

- Coil3.3 uses the same existing `coil3_disk_cache` directory and one shared lazy
  DiskCache with an explicit64MiB LRU target. No RAM policy, photo, scanner upload,
  Outbox, Firebase cache, permissions or database data is changed. Coil already had
  a default bounded LRU; it was not shown to be the cause of13GB growth. Open edits,
  snapshots/journal overhead and a large pre-existing journal before the next trim
  can exceed64MiB temporarily. This is not an instantaneous filesystem hard cap.
- A pure UTC twelve-calendar-month policy and optional window parameters in both
  portfolio builders preserve all pre-window state updates and emit only real
  in-window observations. The existing default remains unchanged. These helpers
  are **not yet wired into the repository, price importer or maintenance**, and
  therefore have not deleted or shortened any stored history.

Final offline JVM/debug-lint proof for these parts:
`artifacts/release27-engineering-20261004/storage-prerequisite/`
`jvm-231641-5fc5860f/manifest.json`, SHA256
`cb0e3cec8c3542c8bc5326afbce48f00108b5215b1d3b732f25d178cd638a71d`.
All source/protected inputs remain unchanged across that run.144 suites contain
999 cases: **998 PASS, 1 existing opt-in live SKIP, 0 FAIL/ERROR**. This adds four
real Coil journal/file tests and fourteen calendar/window tests; original test
bodies/assertions are retained. Debug lint has0 errors,167 warnings,4 hints.
The preceding proof `jvm-231251-d15dc4e5` had the same successful case count; a
nullable-parent warning in the new test was then made explicit and the full
suite rerun. Neither run builds an APK/AAB, signs a release or touches a device.

The user also explicitly permits the necessary new Firebase/Web backend pieces
to be deployed **after successful local tests**. This is not a claim they exist,
passed, or were deployed; no production write has occurred in this follow-up.
Google Play and automatic personal-photo uploads remain excluded. The next
implementation is a versioned public-market snapshot contract, bounded local
price maintenance and isolated large-data verification; no change to FULL sync.

Existing AVDs have10G data partitions, insufficient for the13GB test and safe
compaction overhead. A separate larger test environment is required; no existing
AVD or phone is resized/cleared. The existing366095-row seed took76.044s and its
database was74616832 bytes: a simple linear projection is roughly4h to seed13GiB
with that old row helper, **an estimate only**, not a measurement at that scale.
Generator calibration, actual low-space/restart/reclamation proofs and the
central cache/restore implementation remain OPEN. Gate15 remains blocked.

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
| 5 Pricing/catalog | IN PROGRESS | Bounded parser/Room import and verified mappings retain scope. API26 isolated46/46 proves catalog INSERT FULL plus real ERROR-status FULL, exact15-table preservation/reopen/recovery and retained failed Result. Three original JVM assertions reproduced, narrowly fixed,11/11 focused GREEN; subsequent full JVM1062PASS/one existing live skip and Android371/371 now passed. No complete provider price-coverage or central-cache production claim. Live338/348 counts priced copies; exact missing entry identities remain to classify. |
| 6 Shared binder scale | IN PROGRESS | in-flight deletion/ownership, bounded public cache and exact persisted-price/supplement parity tested; per-card body cap/cancellation14/14, cache/package83/83. Missing-trend DTO resolves once instead of twice, full parity verified. O(N) placement reads, total fan-out and trusted-ingress public IP/token limits remain |
| 7 Camera stability | PARTIAL (emulator functional evidence) | Prior virtual rotation/CameraX fresh-JPEG tests retain their scopes. Actual MainActivity PreviewView STREAMING->CREATED/IDLE held1s->RESUMED/STREAMING passes twice13.144/12.801s without capture/queue/upload. New actual recreation/old-view retirement/new-view STREAMING test has1/1 JUnitPASS13.581s with protected-state checks. Overall wrapper failures for cached test-process idle are retained; separately verified owned cleanup is not retroactive whole-run PASS. Physical camera, HOME/OS-death, permission revocation and broader background matrix remain open |
| 8 Recognition | IN PROGRESS (limited replay PASS) | Historical replays retain their scope. Latest owner-confirmed queue triple: Nidorina MEP101 read correctly3times, correct suggestions, two candidates each,4.2–4.7s active plus burst queue delay. Earlier sequential no-candidate observations have unknown ground truth and are not Nidorina failures. No automatic-printing, speedup vs26 or full hardware claim; quality checks unchanged |
| 9 Community knowledge | IN PROGRESS; separate lock-only RED retained | Original18-case SDK suite remains17/18 because lock-only lacks listener denial within unchanged30s. Independent Session NO_ACCESS-to-consumer proof passes1/1. Fresh actual deletion-request/Auth/Firestore/Storage/Android workflow now passes1/1,2.257s, plus92 host controls and complete fixture/input cleanup. No listener SLA, legacy-job repair, final purge, Eventarc/load or live-consent claim. |
| 10 Privacy/lifecycle | IN PROGRESS (fresh local deletion fence PASS; reviewed service deployed) | Real local deletion-service suite36/36 and latest Web1299/1299 pass; fresh jobs atomically retain minimal disabled accountAccess through controlled65-minute session-expiry wait. Existing jobs whose access document was previously removed are not migrated; stalled/review-required jobs need operator continuation. Complete provider purge/expiry/retention workflow remains open. Seven-day Storage soft delete is not live-object TTL. Reviewed service is in the explicitly authorized04October Web deployment; no live deletion was tested. |
| 11 Observability/cost | PARTIAL | Safe-log inventory and hypothetical model retained. Current Hosting334fdfd6961039a1 is read-only bound to r27-20261004d tag/revision with consistent metadata reads; priorc71089a842d591bc remains a historical rollback anchor. Actual limits unchanged:2instances/40concurrency/60s/1CPU/512Mi. This does not prove IP provenance or load. Live metrics/alerts/billing and complete retention inventories remain unverified. |
| 12 Load/scale | PARTIAL; bounded history and local maintenance verified | Prior matched58/87-sample and complete-model/SQL/private-data proofs remain dated; portfolio improvement is not private-binder speedup. API26 complete113-row annual plan now passes. User-approved12-month collection-only retention preserves every local owner/pending/tombstone and unowned latest price. Actual isolated44+SQLiteFull45, file reclamation, two-process recovery and OS-triggered same-child continuation pass. No physical ENOSPC/WAL/power-loss/OEM proof;13GiB test explicitly withdrawn, not a blocker to restart. O(N) sharing/fan-out and production load remain open. |
| 13 Full regression | Current local regression and signed artifact verification PASS in stated scopes | Latest app64170e3c/test5da0d817 passes371/371, zero skips/failures,310.127s with source/diagnostic checks. Full JVM1062PASS/one unchanged opt-in skip; debug lint0errors/144warnings, release lint0errors/170warnings. Native46 and actual deletion workflow separately PASS. Historical368/371 and370/371 remain; intermittent OCR cause unresolved. Latest Web1299/private36 retain scopes. Gate15 records signed static verification; final physical/live/minified runtime evidence is not inferred from debug or artifact PASS. |
| 14 Database migration | BASELINE PASS | current 12→13 on 1k/5k/20k with all old columns preserved; rerun if later schema changes |
| 15 Release engineering | Verified signed candidate; explicitly authorized test deployment | Immutable releases/v27: actual code27/name, pinned upload signature,688 signed payloads, bundletool validation, embedded R8 mapping and partial1/7 native-symbol coverage verified. INTERNAL active; same27 now confirmed Closed Alpha-live, published04October21:54. Reviewed Rules deployed/read back; Web334fdfd6961039a1/tagr27-20261004d is live after45/45 candidate/live checks and actual visual/authenticated read-only checks. Earlier hardware/live/privacy/load boundaries remain; no production-track or blanket all-gates approval. See dated deployment checkpoints above. |
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
