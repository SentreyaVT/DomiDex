# Release 27 synchronization contract

Status: Gate 4 design and measured regression work, not a deployed delta protocol.
The current application remains Room-first. Auth, entitlements, owner isolation,
optimistic revisions, tombstones and the existing private-sharing readiness gate
must remain authoritative. No production data migration is authorized here.

## Confirmed collection reread before removing checkpoints (2026-10-01)

Follow-up to the session guard below: a missing document or null decoder result
previously removed the CLEAN-entry/PENDING-outbox reread checkpoint without a
usable server result. The actual worker now requires existence, non-cache and
non-pending metadata, and successful decoding at its common reread edge. Only
then can the session-bound Room transaction apply it and conditionally delete the
captured mutation. `finishReread` requires a non-null decoded entry. Both resumed
and just-ACKed paths retain their existing Retry handling.

Strict newer-revision apply, valid tombstones, same-revision completion, M2
protection and all owner/session guards remain. Do not require matching remote
lastMutationId: a newer valid server update is legitimate. No schema, scheduler,
Rules or release configuration changed. Permanently invalid server data still
needs repair; its retained checkpoint can delay later work for that owner.

Three new real-SDK cases first prove actual server/decoder state, then seed an
explicit interrupted-reread checkpoint in isolated Room (not a claimed real prior
ACK). Missing and malformed cases reproduced `UNCONFIRMED_REREAD_DISCARDED_CHECKPOINT`;
valid control and both genuine-commit/session cases passed. Final **5/5 SDK PASS**
includes second fresh passes, five independent complete Admin readbacks, exact six
synthetic Google accounts/claims, unchanged prior owners and unchanged fixture
timestamps. Missing stays absent; malformed is missing only the required name.

Sibling Web evidence:
- RED: `outputs/collection-sync-session-local/2026-10-01T16-08-16-317Z-2c0111a4b9854e4b8b70a9713f5f1c94/`.
- GREEN: `outputs/collection-sync-session-local/2026-10-01T16-14-27-332Z-2bb445eca6ca4938ad10e6abaa8e2e33/`.
- Both runs retain input hashes, free final ports, empty reverse maps and idle
  instrumentation. RED remains failure despite valid independent readbacks.
- Seven pure host-validator cases and focused lint also pass.

Root installed-file verification and **11/11 actual Room regressions** pass:
`artifacts/release27-engineering-20261001/collection-session-room-161337-013e8ad7/`.
App APK SHA-256 `a2d4f35e2ed0882d6f2bbc4dcb7e3a761f6f7c7832b42f6097673eb22170a699`;
test APK `155424e7a49d534fb45b4288744110137f16f6fc4200e5034e2d283fe034f9df`.
Debug/test builds, release Kotlin, both lints and JVM tests pass (3m 13s;
865 PASS / one existing optional live-source SKIP / no failures, 127 suites).
Evidence: `collection-reread-green-regression.log` and `collection-reread-green-unit/`
in the same Android artifact parent. No release AAB/signing/deployment/Xiaomi.
Pending-write/tombstone guards are source-reviewed here, not new SDK overlay or
tombstone scenarios. Full release and live-scale gates remain open.

Final fixed offline API-36 regression: **371/371 PASS**, no skips/failures,
281.081 s; exact same APK/source hashes, inputs unchanged, offline/no reverse.
Evidence: `artifacts/android-regression/20261001T161521Z-75af8b11/manifest.json`.

## Collection-worker session and final-commit protection (2026-10-01)

The inspected worker compared only the current UID after suspensions. A real local
Android Firebase SDK proof held its actual transaction Commit, observed A→B→A
through IdToken callbacks, then released that Commit. An independent named control
SDK confirmed the complete server document before the assertion: the old worker
had incorrectly marked Room CLEAN/revision 1 and removed the original outbox.
The unmodified healthy control passed. This is a reproduced failure of the inspected
v27 source path, not an attributed failure of the immutable signed Release-26 AAB.

`runCollectionSync` is the actual worker pass with explicit dependencies, not a
test implementation. Production still supplies the same default Auth/Firestore,
lazy Room database and scheduler. Each active pass now uses the existing observed
token/account-generation lease. Initially signed-out/different-owner jobs remain
no-ops; a session invalidated during the run retries without accepting its old
completion. Entitlement and SDK success **and failure** paths are checked before
terminal error classification. Already-sent remote commits can still succeed;
their retained mutation ID makes the next valid pass idempotent.

`CollectionSyncSessionCommits` wraps the existing ACK, failure and normalization
helpers in a final session-checked Room transaction. The missing-projection discard
and both reread paths use the same boundary. Remote application and conditional
outbox deletion commit together. M1/M2 rebasing, terminal decisions, strict newer-
revision application, listener policy, the 200-mutation budget, sharing priority,
outbox schema, Rules and scheduler policy are preserved. The same existing Room
application is shared by the SDK decoder and worker; no copied apply algorithm.

A second genuine Room RED exposed cancellation during the **last** lease predicate:
10 cases passed but a cancelled caller still committed reread payload and deletion.
The helper now checks the captured caller Job before/after that final predicate,
inside Room's transaction boundary. All **11/11 real Room cases now pass**, 0.700 s,
including post-write A→B→A rollback, actual Job cancellation, fresh retry, M2
successors, terminal states and foreign-owner snapshots. These tests observe the
actual uncommitted SQL writes, not a guessed number of guard calls.

Evidence (local ignored artifacts):

- SDK RED: sibling Web `outputs/collection-sync-session-local/2026-10-01T15-38-16-631Z-a13618ea118c412681b31406f33eb4c1/`.
  Healthy PASS; stale case fails specifically `STALE_SESSION_ACKED_COMMITTED_MUTATION`.
  Independent Admin readbacks are valid even though the overall run correctly fails.
- Room cancellation RED: `artifacts/release27-engineering-20261001/collection-session-room-154823-e427e074/`.
- Room GREEN: `artifacts/release27-engineering-20261001/collection-session-room-155215-8b753c30/`.
  The manifest verifies the exact installed APK hashes and unchanged source inputs.
- SDK GREEN: sibling Web `outputs/collection-sync-session-local/2026-10-01T15-52-48-330Z-442c460bda5b42d5b54b8c346ddf5ba8/`.
  **2/2 actual SDK cases pass**, including fresh retry with unchanged full server
  payload/revision/timestamp. Two independent final Admin readbacks verify exact
  accounts/claims/paths/payloads and unchanged foreign owner. Ports, reverse maps
  and instrumentation are clean afterward; inputs unchanged.
- Final debug/test APKs, release Kotlin and both lints pass in 3m;
  JVM **865 PASS / 1 pre-existing optional live-source SKIP / 0 FAIL**, 127 suites.
  `collection-session-finalguard-debug-regression.log` in the Android artifact folder.
- Sibling Web `tests/collection-sync-session-local/`: 6/6 pure fail-closed validator
  tests and focused lint pass. They are distinct from the actual SDK evidence.

APK SHA-256: app `854a069cb065e43bc44172e968c2d5e3fa2c4f80e143c0f3063dab987b56ed9a`;
test `e72d5c8e1604dae37a762bdc6ddb4d6353a07db3821c2955edf4e3f78b37c123`.
The fixed **371/371** final regression also passes with no skips/failures in
289.801 s on those exact APKs: `artifacts/android-regression/20261001T155328Z-1cf61eb3/manifest.json`.
Source/APK inputs remain unchanged and the emulator stays offline without reverse mappings.

Scope: named Google-linked demo clients, unchanged local Rules, isolated in-memory
Room, fixed offline API-36 emulator. No production accounts, Xiaomi, release
signing, AAB or deployment. This does not prove WorkManager scheduling, all network
races, multi-device scale or lower production latency. At this earlier checkpoint,
missing/null-decoded rereads still followed the old conditional-delete policy;
the separately reproduced and tested correction is documented above.

## Actual Android/Web stale-price conflict proof (2026-10-01)

Two separate fresh, fixed-demo SDK runs now verify the existing conflict policy
through actual repository/worker passes and the Web subscription/save functions.
The new `AndroidWebPriceConflictInstrumentedTest` and sibling Web
`tests/android-web-price-conflict-local/` do not change product behavior or the
older ACK/pull/clear proof. Each run retains its own marked Room database across
three Android invocations, using only the exact isolated API-36 emulator.

Android first acknowledges 532 minor units at revision 1. Web saves 450 at revision
2. Android, still based on revision 1, selects 500: two worker passes preserve the
entire local selection and original outbox mutation/base as CONFLICT, while all
server fields stay identical to Web revision 2. The real queue aggregation reports
ERROR, not ACTIVE. Ordinary full pull cannot discard that held local choice.

Explicit **cloud** resolution adopts confirmed revision 2/450 locally without a
server write. Explicit **local** resolution rebases onto revision 2, uses a fresh
mutation, then the actual worker acknowledges revision 3/500. Original sourceAt
1000 and selectedAt 3000 survive that rebase. In both runs Web subsequently reads
the exact result through its real subscription without another write. No trend
is manufactured from this explicit alternative, and the collection parent stays
unchanged locally and remotely.

Both runs pass all three Android and two Web phases, with five independent full
Admin readbacks each (sidecar, unchanged parent, exact sole Google-linked owner,
claims, no access override, no extra sidecar). Real verified entitlement and
`PrivatePullAuthSession` guards protect the Android resolver; no always-true
guard or bypass is injected. Cleanup removes only the verified own fixture and
is not counted as price-test success. Source/APK hashes stay unchanged, ports
are free, reverse mappings empty, and no instrumentation remains afterward.

Web evidence directories under `outputs/android-web-price-conflict-local/`:

- `2026-10-01T14-28-51-954Z-cloud-46dbb61bb54245eaac5f5a7744799fb2/`
- `2026-10-01T14-30-05-496Z-local-d938e2045fde4aae8fdc7542e01d12a2/`

Seven pure validator cases, full Web typecheck and focused lint also pass.
Android debug/test build and lint pass in 1m32s; JVM 856 PASS / one existing
live-source opt-in SKIP. Exact installation and **371/371** regression, 270.701s:
`artifacts/android-regression/20261001T142307Z-3a9a405f/manifest.json`.
Debug SHA-256 `CBD7CDB28A26939ED70A7F115A15949344C21D52508D01013ABEF7ECD7221E39`;
test SHA-256 `BF5E7C20C373E36CAC7039AB34703EC794CFEB3D86855831BC90FB60BAA36C98`.

This is not the Settings UI/WorkManager lifecycle, all concurrent-resolution
races, multi-device scale, a production-latency comparison or Gate-4 completion.
No Xiaomi, production service, rule/dependency change, release signing or AAB.

## Proven baseline and first isolated fixes

Two collection workers (ordinary and sharing-priority) can capture the same outbox
mutation. The original inline ACK transaction failed five of seven deterministic
Room regressions: late ACKs could manufacture PENDING without an outbox, lower a
confirmed revision, or reset a newer conflict/error decision. The extracted ACK
transaction preserves newer states and revisions and keeps the durable CLEAN +
outbox server-reread checkpoint. It never deletes an outbox row before reread.

The original failure path conditionally marked an outbox mutation but then marked
the collection entry unconditionally. A failure for old mutation M1 could therefore
mark a newer M2 entry. Both failure statuses now share a current-mutation guard.
Tests call the production helpers and real Room DAOs, not copied implementations.

An existing terminal entry with a pending outbox is not automatically reopened.
Only its matching outbox is made terminal and visible in the existing Settings UI;
entry payload, identity, revision and mutation ID stay unchanged. Explicit error
retry is tested through the real repository. A conflict with no confirmed remote
revision cannot invent a local-wins base revision. The existing cloud-wins action
requires authoritative reads; it is not claimed as end-to-end verified locally.

Normalizing such a terminal row does not fail the WorkManager APPEND chain: the
worker skips that row and can process independent healthy mutations. The terminal
outbox still blocks the existing Room-based sharing-readiness check. A successful
worker result is not substituted for confirmed sharing readiness.

Final local validation on the isolated, offline API 36 emulator:

- 18 production-helper/Room regressions pass (original ACK baseline: 5 failures
  out of 7 tests). No copied ACK implementation is used in the final tests.
- Combined ACK, sharing readiness/work, collection-work and App Check startup
  instrumentation: 31/31 pass in 1.203 s after the final chain correction.
- 74 selected sync/sharing JVM tests pass; debug + androidTest APK builds and
  release Kotlin compilation pass in the final 20 s Gradle invocation.
- Evidence: `artifacts/release27-engineering-20261001/gate4-ack-validation-build.log`
  and `gate4-ack-instrumentation-final.log` (local ignored artifacts).

These are deterministic local Room/worker-contract tests, not two physical devices
or a live Firebase end-to-end run. No Xiaomi, production data or release signing
was used. The wider Gate 4 delta/scale work below is still in progress.

## Held price conflicts remain visible in cloud status (2026-10-01)

A real Room regression reproduced `ACTIVE` with a confirmed collection snapshot
and an unresolved private price-selection conflict. The pending private queue
intentionally excludes `PRICE_SELECTION` rows whose error matches
`price-selection-conflict:*`, but the status calculation only inspected ordinary
collection errors/conflicts. It could therefore falsely advertise no pending work.

The actual `refreshCloudState` now calls a shared owner-scoped queue reader which
also performs an existence-only query using exactly that held-conflict predicate.
The existing error priority makes the unresolved choice visible. No price, source
date, payload, revision or outbox is changed by reading status. The existing
two-outbox invalidation observer was already correct and remains unchanged.
No schema, sync policy, Firebase rule or network request is added. SQLite may scan
that owner's indexed outbox range; this is not a constant-time latency claim.

Root RED: **4 PASS / 1 FAIL**, 0.273 s, exact assertion `ERROR` expected but `ACTIVE`.
Root GREEN: **9/9 actual Room tests PASS**, 0.461 s, zero skips/failures. Tests use
the real production conflict helpers: keep-local becomes PENDING with a new
mutation and unchanged market source, confirmed keep-cloud becomes CLEAN/empty
outbox, and ACTIVE still requires a confirmed server snapshot. Foreign owners,
other record types, malformed held markers, old decisions and unconfirmed remote
snapshots retain the correct prior policy. Two source reviews found no blocker.

The final local debug/test build, lint and JVM run pass in 2m49s: **853 PASS,
one existing live-source opt-in SKIP, no failures/errors**, 125 suites. The fixed
emulator regression passes **371/371**, 256.329 s, no skips/failures. Manifest
`artifacts/android-regression/20261001T130420Z-908a5872/manifest.json` confirms exact
APK installation, unchanged source/APK inputs, and offline/no-reverse afterward.
The separate SDK overlay/transaction proof passes **3/3**, 2.813 s, with independent
server readback and all cleanup/input flags true; Web evidence directory
`outputs/private-pull-sdk-local/2026-10-01T13-09-31-515Z-e0cdc853/`.
That SDK suite is not a complete production Worker/Android-Web roundtrip.

Debug APK SHA-256: `617B1DF9D8D14DDE4EB9058521DAE772D76B6297024009FFBC22EB67FB71E195`.
Test APK SHA-256: `73A70017BB72802842E25089C69670EAC8354E36FACD4511F6690DF667ABC04A`.
Focused RED/GREEN, installation receipts, strict nine-case result validation and
copied JVM XML are retained as `cloud-queue-status-*` / `cloud-queue-status/`
under `artifacts/release27-engineering-20261001/`. Only the isolated emulator was
installed. No Xiaomi, release signing, AAB, Play, production or deployment action.

## Actual Android repository / worker pass and Web price roundtrip (2026-10-01)

`PrivateBinderSyncWorker` now invokes the same extracted `runPrivateBinderSync`
pass used by the isolated integration proof. Its per-invocation Auth/Firestore,
lazy Room provider and continuation callbacks are explicit; Google ownership,
server entitlement checks, session generation, transaction/payload/ACK/conflict
logic, current ID-ASC pull, limits and scheduling decisions remain inside the
actual product path. No global override, bypass, schema or Rule change was added.
This does not exercise WorkManager's constraints, input delivery or scheduling.

The opt-in `AndroidWebPriceRoundTripInstrumentedTest` uses a fresh, marked Room
file across three separate Android invocations. With real named Auth/Firestore
SDKs and unchanged Rules on the fixed local demo project, the exact sequence is:

1. Real Android repository selects a synthetic 532-minor-EUR alternative; actual
   worker push/ACK produces revision 1 and clears only its current outbox.
2. Actual Web subscription observes it; actual Web save selects 450 minor EUR,
   revision 2. Android reopens the same Room file and pulls that precise choice.
3. Actual Web clear writes revision 3 with all twelve nullable fields null;
   Android pulls that clear without rewriting it or fabricating a trend/history.

The source date, set/card/above-main collector identity, variant, currency and
synthetic product ID remain exact. Parent collection documents remain unchanged.
Five separate Admin server readbacks verify complete fields, not just counts.
The real Web subscription is compared with separately confirmed server snapshots;
its callback does not expose snapshot metadata. Only named SDK initialization is
substituted on Web; no RPC, transaction, codec, Auth or product function is mocked.

Root ran the five-phase proof twice in fresh emulator lifetimes: both **3 Android
+ 2 Web phases PASS**, no skipped cases, plus separately verified owned Room
cleanup. Evidence in the Web repository:

- `outputs/android-web-price-local/2026-10-01T13-29-15-824Z-8fbfb9a811e14451a6c771905fdf8a10/`
- `outputs/android-web-price-local/2026-10-01T13-32-46-840Z-e6d81587f03b4f59bde4e14393d41c58/`

Both manifests confirm exit 0, independent server verification, unchanged inputs,
ports released, no remaining reverse mappings/instrumentation and owned fixture
cleanup. The synthetic Google-linked owner has only appAccess, never admin. The
API-36 emulator is offline; no default client is repointed or user account used.
The six negative/positive host validator cases, Web typecheck and focused lint
pass. Initial lint-only harness findings were fixed before the first SDK run.

Root debug/test APK build and debug lint pass (2m25s); JVM compilation/tests pass
(57s): **853 PASS / 1 existing opt-in SKIP / 0 errors/failures**. The fixed emulator
matrix passes **371/371 in 258.802s**; exact installation and unchanged hashes are
recorded in `artifacts/android-regression/20261001T132345Z-a78547af/manifest.json`.
Debug APK SHA-256: `54BE5295D58BE8435BBCC805E96E76BD8498F4CBE4CED1AE1C50DB2AC2F1EA3C`.
Test APK SHA-256: `ACFC335FAAF87C80EBA3756250A01AB229BC006F33C8284276B4A700BACCDB5D`.
The unchanged three-case SDK overlay proof also passes, 3.803s, with complete
server/input/cleanup checks: Web `outputs/private-pull-sdk-local/
2026-10-01T13-30-16-009Z-0af6d225/`.

The broader Web unit rerun is **1195 PASS / 2 FAIL**, not a green release result:
two existing shared-view refresh assertions observed an empty dialog before its
passive closing effect. Evidence: Web `outputs/release-local/
2026-10-01T13-30-26-015Z-13648.{json,log}`. This separate finding did not invalidate
the distinct five-phase price proof. Follow-up Web commit `e2662f2` closes the
invalid native dialog in the layout commit; a deterministic Profiler test was RED
before the change and GREEN afterward. A later owner-form test failure exposed an
early click before the separate shares GET completed, not an observed failed POST.
That test now proves loading/ready states and the email restriction independently.
Both UI files pass 22/22; the complete Web selection then passes 1199/1199 twice.
The original failures remain in the evidence history; precise paths and limits
are in the Web handoff. No production/UI permission policy was relaxed.

This remains one synthetic printing, not real-card price coverage, initial parent
upload, all conflicts/retries, UI/WorkManager E2E, live latency, delta integration
or Gate-4 completion. No production/deployment, Xiaomi, release build or signing.

## Rejected ID-horizon experiment (2026-10-01; not active)

A deterministic regression reproduced a fairness limit in the then-active
sequential runner: continuously appended higher IDs could keep the first group
open across worker runs, delaying an existing later-group placement. The original RED is retained locally
as `full-pull-horizon-red.{log,xml}`. This is a synthetic counterexample, not a
production-data incident or a claim that an ordinary finite binder cannot sync.

An attempted fixed ID horizon used `documentId DESC, limit(1)` before each group.
Although its isolated runner/codec/Room tests passed, the actual Android Firestore
26.5.0 SDK against the fixed local Firestore 1.22.0 emulator rejected this exact
query with `FAILED_PRECONDITION: Firestore does not support descending key scans`.
The four-case SDK run had **3 PASS / 1 FAIL** and no successful post-run independent
server proof. Cleanup, free ports, empty reverse mappings and unchanged input
hashes were verified. Evidence in the sibling Web repository:
`outputs/private-pull-sdk-local/2026-10-01T12-47-08-248Z-d9bb1ef9/`.

Root therefore removed **only this uncommitted experiment** from the active
Firebase adapter, full-pull runner, tests and SDK harness. The prior ascending-ID
`limit/startAfter` path, per-owner cursor/phase metadata, bounded read budget,
existing session guards and sharing priority remain. No Room migration, cloud
schema/index/rules change, release signing, deployment or phone action occurred.
The new tuple store, query helper and their tests are not compiled or wired.
Their source snapshots and the four-case harness are preserved under the ignored
Android engineering directory `rejected-full-pull-horizon-source/{android,web}/`;
the older timestamp-delta/deferred foundations below remain unchanged.

`ASC + limitToLast(1)` is not an independent workaround: the exact installed SDK
source, `com/google/firebase/firestore/core/Query.java:494–535`, reverses query
ordering and bounds for LIMIT_TO_LAST. This is a limitation of the tested query
under the tested setup, **not a blanket claim that production Firestore can never
support a descending name index**. Google's
[Firestore index definition](https://github.com/GoogleCloudPlatform/magic-modules/blob/main/mmv1/products/firestore/Index.yaml)
includes such an index; neither its presence for DomiDex nor emulator support was
established. No production index was inspected or created for this experiment.
Additional payload ordering/filtering would introduce new completeness/index
assumptions and was not substituted blindly.

The rejected approach also added one sequential query to each small nonempty
group and deferred newly appended tail IDs until a later cycle. Its synthetic
passes were not evidence of faster sync. Historical experiment-only results:
27 runner cases, six codec cases, 11 Room cases, a full JVM run of 867 PASS /
one existing opt-in SKIP and an existing 371-case emulator selection all passed
before the real SDK rejection. **These are not final restored-build results.**
The later active round-robin change is described below; it does not revive this
rejected horizon query. Low-latency Android/Web convergence and wider Gate-4
acceptance remain open; do not hide the rejected SDK evidence.

## Private binder delta protocol — foundation in progress, not active yet

`PrivateBinderPullProtocol.kt` now defines pure phases, timestamp tuples, bounded
page transitions and strict local metadata encoding. `PrivatePullRoomStore.kt`
implements exact-raw CAS plus atomic payload/marker/checkpoint commit. A fresh
local epoch prevents a repaired/reset checkpoint from recreating an older CAS
token. `PrivatePullAuthSession.kt` provides the per-run token/account-event lease
already used by the active worker. The delta protocol/store and the local adapter
described below are **not yet connected to the running Firebase worker**; no
delta/network speedup is claimed yet.

The existing placement apply path separately uses an indexed existence query for
a requested free slot, fetching all active placements only for a real collision.
Its first-free conflict policy is unchanged. This is a local Room optimization,
not an alteration to Firestore requests or automatic conflict decisions.

### Scope and compatibility

Four existing collections: `privateBinders`, `privateBinderPlacements`,
`collectionLayouts`, `collectionPriceSelections`. Existing writers use server
`updatedAt` timestamps and Rules require `request.time`. No new cloud schema or
server write is needed. Public sharing and priority uploads stay separate.
The existing full-pull implementation is the fallback/repair path, not deleted.

Use versioned per-owner/per-group state in `app_metadata`. Existing Room schema
can store checkpoints and bounded, separate recheck markers without a destructive
migration. Old metadata remains untouched during rollout. Checkpoints are local
sync state, not portable exported collection contents.

### Bootstrap and bounded delta

1. Before a document-ID full scan, record a confirmed server-derived upper tuple.
   Keep all documents in the bootstrap, including legacy missing `updatedAt`.
2. On completion, start delta inclusively from the pre-bootstrap timestamp. If no
   timestamp was available, perform an unbounded-lower initial timestamp pass.
3. Delta orders by `updatedAt ASC, documentId ASC`, 200 rows/page, at most 1,000
   remote rows per worker run. Capture a fixed upper tuple per pass with `endAt`.
4. Resume an interrupted pass with its exact tuple via `startAfter`. A completed
   pass starts the next pass **inclusively at the boundary timestamp**, replaying
   ties idempotently, not strictly after the last ID.
5. Persist seconds and nanoseconds exactly; never use client time or milliseconds
   for the remote cursor. Tombstones/cleared values remain in the query.

Only confirmed timestamps from snapshots without pending writes may advance the
watermark. `Source.SERVER` does not itself exclude local pending write overlays.
An empty query must not synthesize a timestamp from the device clock.

### Atomic application and deferred documents

Within a single Room transaction, check captured owner/session, current outbox and
expected checkpoint state, then apply the document **or** persist its owner/type/ID
recheck marker, then advance the cursor. Never checkpoint a pending local mutation
or unresolved parent without a durable recheck marker. Missing or still-blocked
parents are not proven permanently invalid by repeating the same dependency fetch.

Rechecks fetch the newest document at the allowed, exact owner-scoped address.
Remove the marker atomically only after application or an explicitly completed
result, not merely because an outbox ACK arrived. Bound each pass; deferred-only
work must not cause immediate endless APPEND scheduling. Independent rows should
still progress. Network errors never count as successful application.

### Concurrency and account changes

Serialize per-owner pulls and protect durable state against stale writers. Current
one-shot and periodic workers may overlap. A→B→A must invalidate an already-running
session, not merely compare the final UID. Recheck the captured session before and
after awaits and inside each applying transaction; preserve owner-A work for its
next valid session. Network work remains outside Room transactions.

### Limits and manual checks

- Standard ascending `updatedAt` indexing ordinarily includes matching document-ID
  order; production index exemptions are NOT TESTED. Missing-index failures cannot
  advance a cursor. No Console/index deployment is performed by this local work.
- `orderBy(updatedAt)` excludes missing-field documents. Keep a bounded explicit
  full repair and an infrequent reconciliation policy for administrative legacy
  writes. No automatic cloud backfill is implied.
- Administrative hard deletion is not a tombstone and is not detected by delta.
  The old full scan did not reliably remove such local rows either; no new hard-
  delete guarantee is claimed.

### Required acceptance tests

Run 1k/5k/20k synthetic rows, >200 identical timestamps, nanosecond distinctions,
changes behind active cursors, pending A followed by B, parents arriving later,
terminal parents, tombstones, interrupted transactions, concurrent workers,
A→B→A, malformed/missing timestamps, pending-write overlays, offline/denied/index
errors and corrupted local checkpoints. Compare reads and unchanged-pass costs
against the measured full-scan baseline, with no live customer data or load.

Collection pagination/listening requires its own reviewed continuation protocol;
this private-binder contract does not silently replace the existing collection
listener. Rollout flags must not bypass any security checks.

### Integration checks still required

- The common remote-apply seam now implements explicit deferred outcomes, pending
  membership protection and confirmed tombstone receipts (evidence below). The
  future delta adapter must still persist recheck markers for deferred documents.
- The active full-pull budget and cycle stop are now covered by the runner tests
  below; a future delta adapter must preserve those properties independently.
- Timestamp trust remains an adapter obligation. The Android public snapshot API
  has no server read-time field; a representable timestamp alone is not proof of a
  valid historical writer. Malformed legacy values must not poison progress.
- The metadata port isolates metadata scope, not arbitrary caller DAO writes. The
  adapter must validate document owner, parents and outbox in the same transaction.

Reference semantics: [Firestore query cursors](https://firebase.google.com/docs/firestore/query-data/query-cursors)
and [Android DocumentSnapshot](https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/DocumentSnapshot.html).
Pending-write metadata and full seconds/nanoseconds are required; neither estimated
local server timestamps nor client-clock values establish a confirmed watermark.

### Local foundation evidence (2026-10-01)

The pure protocol's 42 JVM cases pass within 116 selected sync/sharing unit tests.
Nineteen real Room tests cover metadata CAS, recovery epochs, marker capacity,
owner/group isolation and payload+cursor rollback. Seven local placement tests
cover indexed free positions, unchanged collision policy, tombstones and synthetic
1k/5k/20k scale. Combined with existing ACK/work/sharing/startup tests, the final
offline emulator run passes 57 tests in 15.123 seconds. Debug/test builds and release
Kotlin compilation pass; no signed release build was performed.

At that foundation checkpoint only the indexed free-position fast path was active.
The delta state machine and Room store are still not enabled; the auth lease and
common remote-apply protection are now active as described below.
No Firestore delta-read savings or cross-device timing claim is made from these
local tests. See `RELEASE_27.md` for the measured local lookup and its limitations.

### Active full-pull and push-response protection (2026-10-01)

Every private-worker run now holds an observed Firebase token/account generation
lease. Task completion and Room commit boundaries reject stale leases, including
an observed A-to-B-to-A transition. Cancellation or a late account change rolls
back local writes. This cannot cancel an already-sent remote transaction; its
durable, idempotent outbox is retained for the next valid session. The existing
entitlement checks and security rules remain separate and authoritative.

All four private-record apply paths, including conflict replies and parent reads,
use a common Room helper. It defers own outstanding mutations and other pending
placement memberships, distinguishes missing/terminal parents, and retains confirmed
unknown binder/placement deletion revisions in owner-scoped metadata. An old live
snapshot cannot resurrect a record below that receipt. Invalid/corrupt evidence
does not discard the current outbox. Receipts have no TTL and are not permissions.
Critical identity/revision/position/price fields are validated, but legacy optional
description/cover/page/creation-time fallbacks remain: this is not a strict validator
for every field. A dependency returned from SERVER is additionally checked for
cache/pending-write metadata before it can create a collection parent in Room.

The current ID bookmark still pages a FULL scan, not an incremental high-watermark.
Blocked documents are revisited in a later sweep of their group; no permanent-orphan
inference or hot continuation solely for a missing parent is made. This must not
be reused as the delta adapter's deferred-document policy. Remote apply and the
current bookmark are separate transactions; crash replay must remain idempotent.

Final isolated emulator evidence: 78 tests pass in 3.054 s, including 16 common-
apply Room tests and 12 transaction/session/SDK-Task tests, plus existing ACK,
checkpoint, sharing/work and startup tests. The snapshot-metadata cases exercise
the actual guard with all Boolean combinations and Room callbacks, not live
Firestore. The cancellation test uses synthetic metadata, not a real outbox table.
There is no earlier executed RED baseline for the 16 new common-apply cases.
An initial runner invocation had two wrong fully-qualified class names; the corrected
78-case run passed. Evidence: `gate4-confirmed-dependency-instrumentation.log` and
`gate4-confirmed-dependency-build.log`. The latter passes debug/test builds, release
Kotlin compilation and 154 selected JVM tests. No release signing or production call.

### Active bounded full-scan runner

`runPrivateFullPull` in `PrivateFullPullRunner.kt` is the actual worker control
flow. It now rotates pages between four groups in their existing order:
`privateBinders`, `privateBinderPlacements`, `collectionLayouts`, and
`collectionPriceSelections`. Firestore queries remain `documentId ASC` with
`limit/startAfter` and `Source.SERVER`; there is no horizon query, timestamp
delta, extra cloud index or altered payload/authorization policy.

Each group keeps its own durable exclusive ID cursor. After a page, the next
group becomes eligible, including when the current page is full. A short or empty
page establishes that group's EOF and clears only its cursor. That group is not
queried again during the same invocation after EOF, but is eligible in a later
invocation even if its persistent EOF bit is already set. Thus an indefinitely
growing group cannot monopolize every run, and new rows in earlier EOF groups
remain serviceable while another group is still progressing.

The per-owner `PrivateFullPullSchedule(nextGroup, seenEofMask)` records rotation
and which groups have reached EOF since the previous completion. Mask `15`
produces one `COMPLETE` milestone; `resetCycle` clears only the mask and retains
the next group and all partial cursors. A previously finished group may already
have started its next sweep by then. **This is not a simultaneous server snapshot,
not a statement that every cursor is empty, and not a promise that all concurrent
changes have arrived.** A persisted complete mask resets without another remote
read; the runner returns rather than starting another milestone in that invocation.

`PrivateFullPullMetadataStore` in `PrivateBinderFirebaseSync.kt` is wired into the
production adapter, not the separate unintegrated delta foundation. It reuses
the existing owner-derived `private-sync-pull-phase-*` and per-group
`private-sync-pull-cursor-*-<group>` keys in `app_metadata`, with no Room migration.
The schedule codec writes exactly `rr1|<nextGroup 0..3>|<mask 0..15>`. A missing
value starts at `(0, 0)`. Exact legacy values `0` through `3` decode to that next
group and the preceding groups' EOF bits; legacy `4` decodes to `(0, 15)`.
Reading alone does not rewrite legacy state; the next advance/reset persists the
versioned value. Other values fail closed instead of inventing progress or
clearing cursors.

Schedule/cursor reads and writes use the actual `withPrivateSyncSession` Room
transaction helper and the captured `PrivatePullAuthSession` generation guard.
`advanceGroup` decodes first, then atomically writes rotation/EOF evidence and,
only at EOF, the current group's empty cursor. Its final guard rejects an
observed account-generation change before commit, including A-to-B-to-A.
Reset validates a complete mask and changes only schedule evidence. Remote
payload apply and its document bookmark remain separate transactions, so crash
replay must still be idempotent; this change does not make network reads atomic.

The whole fetched page is charged before applying rows: at most 200/query and
1,000 logical read units/run. Empty queries count one; point reads are reserved
before requesting a parent, including missing documents. The per-run parent set
is bounded by that budget and discarded before the next invocation. If a
never-attempted dependency cannot fit, the bookmark stays before its child and
rotation advances without claiming EOF. Already-attempted missing or locally
blocked parents allow ordinary FULL-scan progression and later group-sweep replay.
A shared owner gate still spans initial schedule read through final write, retains
at most eight active owners and releases synchronously in `finally`. Existing
WorkManager backoff, separate pushes and sharing priority are unchanged. This is
single-process exclusion, not a distributed lock.

Fair revisits have a measurable logical-read cost. In the fixed, evenly distributed
synthetic matrix (200/query, 1,000/run, no dependency reads), the confirmed JVM
traces are:

| Total fixture rows | First COMPLETE milestone | Next COMPLETE: additional reads | Combined reads |
| --- | ---: | ---: | ---: |
| 1,000 | 1,601 | 400 | 2,001 |
| 5,000 | 5,601 | 4,400 | 10,001 |
| 20,000 | 20,004 | 20,004 | 40,008 |

The first 1,000-row milestone exceeds the old sequential `N..N+4` ceiling of
1,004: it includes 600 real repeated row reads plus the final empty EOF read.
Those repeats already advance subsequent group sweeps, whose partial cursors
survive completion. The tests check exact ascending identity sequences within
each group sweep and at least two completed sweeps per group across the two
milestones, not a globally ordered snapshot or a universal two-pass cost bound.
No production latency improvement, billed-read reduction or unchanged-run saving
is inferred from these synthetic counts; SDK retries and billing are not modeled.

The sequential fairness RED is preserved as `full-pull-fairness-red.{log,xml}`:
two finite controls passed and only the growing-first-group case failed. With
rotation, the focused JVM selection is **46/46 PASS**; the full JVM run is
**865 PASS / one existing live-source opt-in SKIP**, as confirmed by Root.
Evidence is retained under `artifacts/release27-engineering-20261001/` in
`full-pull-fairness-focused-results/` and `full-pull-fairness-all-jvm-results/`.
Tests cover budgets 1/200/201/1,000, new rows after an earlier EOF, durable EOF
evidence across tiny budgets, strict legacy decoding, bounded dependency handling
and the scale/cost traces above. The new actual-Room metadata tests now pass
**5/5 with no skips**: owner isolation, corruption preservation, rollback after
both writes, A-to-B-to-A final-guard rollback, and real database close/reopen.
The fixed emulator regression passes **371/371, no skips** at
`artifacts/android-regression/20261001T151140Z-a0aa117d/manifest.json`;
the separate Room proof is `full-pull-fairness-room-manifest.json` under the
engineering artifact directory. Its installed APK hashes match that baseline.

The actual SDK Android/Web price roundtrip and both explicit conflict decisions
were rerun on this same installed build. All three pass, including independent
Admin server readbacks, unchanged input hashes, owned fixture cleanup, empty
reverse mappings and free local ports afterward. Web evidence directories:

- `outputs/android-web-price-local/2026-10-01T15-16-59-907Z-2ab1c907358e409ab70823c7e79d0523/`
- `outputs/android-web-price-conflict-local/2026-10-01T15-18-04-290Z-cloud-32eea1be3e5447e78ba31c025d189f49/`
- `outputs/android-web-price-conflict-local/2026-10-01T15-18-56-995Z-local-992029626ad54c928c7d1f13c3f4076b/`

These small actual-SDK fixtures are distinct from the synthetic JVM scale matrix.
They do not establish 20,000-row SDK convergence, WorkManager scheduling or
production performance. Cross-device scale, remote latency, delta
integration and overall Gate-4 acceptance remain open.

### Preiswahl-Konflikte: explizite Entscheidung statt stiller Cloud-Übernahme (2026-10-01)

Der `PRICE_SELECTION`-Push verwendet bei abweichender Remote-Revision jetzt eine
eigene Konfliktentscheidung. Die exakt aktuelle Outbox-Mutation bleibt bestehen;
`lastError = price-selection-conflict:<revision>` und `syncState = CONFLICT`
kennzeichnen den wartenden Datensatz. Der gewählte Preis, sein Quelldatum und die
bisherige lokale Basisrevision werden dabei nicht verändert. Für Binder,
Platzierungen, Layouts und erfolgreiche ACKs gelten die bisherigen Policies.

Nur solche markierten Preiszeilen werden aus der normalen Pending-Auswahl
ausgeschlossen. Sie können andere Synchronisationsarbeit nicht dauerhaft
blockieren und bleiben durch ihre Outbox vor gewöhnlichem Pull-Apply geschützt.
Der vorhandene Konfliktzähler in den Einstellungen umfasst Collection- und
Preiswahl-Konflikte. Es gibt keine neue Room-Spalte, Migration oder Firestore-Regel.

- **Lokale Fassung senden:** prüft den exakt aktuellen Konflikt, einen stabilen
  bestätigten Collection-Parent und die vollständige Kartenidentität. Eine neue
  Mutation wird ausdrücklich gegen die bekannte Remote-Revision vorbereitet.
  Quelldatum, Betrag und gewählte Metrik bleiben unverändert. Ein späterer
  Serverkonflikt erfordert erneut eine Entscheidung; es gibt kein blindes Rebase.
- **Cloud-Fassung übernehmen:** liest das Dokument erneut mit `Source.SERVER`.
  Nur vorhandene, nicht aus dem Cache stammende und nicht lokal schwebende,
  vollständig validierte Daten dürfen übernommen werden. Parent-/Owner-/Mutation-
  und Revisionsprüfungen liegen mit Payload-Übernahme und Outbox-Löschung in einer
  gemeinsamen Room-Transaktion. Gleiche Revision bedeutet nur bei dieser expliziten
  Entscheidung Zustimmung, niemals im normalen Pull. Physisch fehlende, ungültige,
  ältere oder noch blockierte Daten lassen den Konflikt unverändert.
- Ein gültiger Cloud-Clear darf bei einem bestätigten gelöschten Parent übernommen
  werden; eine aktive Auswahl oder lokale Entscheidung darf ihn nicht wiederbeleben.
  Auch lokale Clears müssen tatsächlich zwölf leere Wertfelder enthalten.
- Die neuen Preisaktionen verwenden eine Owner-/Auth-Generation-Lease. Die danach
  fortgesetzte bestehende Collection-Entscheidung erhält über `MainActivity` den
  bestehenden Entitlement-Generationsschutz; ein Konto-Rundwechsel A → B → A oder
  eine ungültige Sitzung darf keine alte Fortsetzung in Room bestätigen.

Nachweis: Der zuvor ausgeführte Worker-Policy-RED-Lauf hatte **1 Fehler bei 5 Tests**
(Preis-Remote-Wins wählte noch `APPLY_REMOTE`). Final sind **146 ausgewählte JVM-Tests**
und **98 echte Room-/Regressionsfälle im isolierten Emulator** grün, darunter
**19 neue Konflikt-Tests**. Debug-/Test-APKs und Release-Kotlin-Kompilierung sind
geprüft; kein Release-Build, Signing oder Deployment. Die Room-Tests sind kein
Firebase-Worker- oder Geräte-zu-Geräte-E2E-Nachweis.

Separat wurden mit realem JavaScript-SDK und lokalen unveränderten Firestore-Regeln
**25 Zwei-Client-Tests** bestanden (21 Preiswahl, 4 bestehende Sync-Fälle). Die
ursprünglichen RED-Ergebnisse bleiben erhalten: `permission-denied` bei konkurrierender
Revision wird nicht zwingend automatisch vom SDK wiederholt; `disableNetwork()`
blockiert im installierten Node-SDK nicht sämtliche direkten Transaction-RPCs.
Ein eigener Loopback-Proxy prüft deshalb zusätzlich echten Transportabbruch,
unveränderten bestätigten Serverstand, Reconnect ohne Replay und bewussten Retry.
Das ist eine SDK-/Rules-Matrix, keine Ausführung der Android-Worker/Web-UI-Pipeline.

Lokale Protokolle und isolierter Runner:
`artifacts/release27-engineering-20261001/security-gate4-two-client/`.
Der Node-Loopback-Guard ist keine OS-Firewall und instrumentiert nicht den Java-
Emulator; verwendet wurden ausschließlich Demo-Projekt, synthetische Owner,
leere Credential-Konfiguration und der bereits gecachte Emulator. Alle eigenen
Firebase-/Proxy-Ports waren nach dem Lauf wieder frei.

### Collection-Cloud-Entscheidung: Generationsschutz beider Phasen (2026-10-01)

Die vorhandene zweiphasige Cloud-Auflösung bleibt erhalten: Zuerst wird die lokale
Darstellung für neuere Listener-Fassungen geöffnet, während die Konflikt-Outbox
bestehen bleibt. Erst nach dem zweiten Server-Read wird die genau passende Mutation
quittiert. Eine bereits neuere lokale Listener-Revision wird weiterhin nicht
durch einen älteren zweiten Read zurückgesetzt. Auch eine ausdrücklich gewählte
Cloud-Löschung bleibt eine Löschung.

Die zwei unverändert fachlichen Room-Phasen sind in `CollectionCloudConflict.kt`
als tatsächlich vom Coordinator verwendete, getrennt testbare Funktionen extrahiert.
Beide prüfen jetzt dieselbe ursprüngliche Aktionsgeneration vor und nach ihren
Schreibzugriffen innerhalb der Transaktion. Die Server-Reads prüfen sie vor und
nach dem Warten; Coroutine-Abbruch und Sitzungswechsel werden nicht als bloßer
Offline-Fehler verschluckt. Gewöhnliche Netzwerkfehler lassen den Konflikt offen.

Der ursprüngliche Owner-/Generations-Guard wird ausdrücklich **vor `withContext`**
erfasst: Bereits das Warten auf den IO-Dispatcher kann suspendieren. Das gilt auch
für Preisentscheidungen vor ihrer DAO-Abfrage und vor Einrichtung der zusätzlichen
Auth-Lease. Ein Konto-Rundwechsel während Dispatch- oder DAO-Wartezeit kann einer
alten Entscheidung damit keine neue Sitzung geben. Lokale DAO-Ausnahmen werden
weiterhin weitergegeben und nicht als leere Konfliktliste ausgegeben.

Ein berechtigter erster Commit wird bei Offline-/Abbruch zwischen den Reads nicht
nachträglich zurückgerollt: Seine `CLEAN`-Darstellung mit weiterhin vorhandener
`CONFLICT`-Outbox ist ein bereits bestehender, bewusst fortsetzbarer Zwischenzustand.
Pauschales Rücksetzen könnte inzwischen eingetroffene neuere Listener-Daten zerstören.
Die zweite Phase darf bei ungültiger Generation nicht bestätigen.

Ausgeführter RED-Nachweis nach unveränderter Extraktion: **4/6 JVM-Lesetests** und
**5/14 echte Room-Tests** fehlgeschlagen. Final nach den Guards und den zusätzlichen
DAO-/Dispatcher-Randfällen: **156 ausgewählte JVM-Tests** sowie **112 Emulator-
Regressionen** grün, darunter **10 neue Lese-/Guardtests** und **14 neue Room-Fälle**.
Der Dispatcher-Test hält die Queue deterministisch an, ohne Schlafzeiten. Die
Tests prüfen reale Room-Seams und den tatsächlichen suspend-Leseguard mit lokalen
Testlieferanten; sie ersetzen keinen Firebase-/Geräte-E2E-Test.

Finale Protokolle im obigen Artefaktordner:
`collection-cloud-dispatch-final-build.log` und
`collection-cloud-dispatch-final-instrumentation.log` (6,263 Sekunden, 112/112).
Debug-/Test-APKs und Release-Kotlin wurden offline gebaut; kein Release-APK/AAB,
keine Release-Signierung, Migration, Regeländerung oder Veröffentlichung.

### Lokaler Delta-Remote-Apply-Adapter — noch nicht im Worker aktiviert (2026-10-01)

`PrivatePullRemoteRoomAdapter.kt` verbindet das vorhandene reine Pull-Protokoll und
den `PrivatePullRoomStore` mit der tatsächlich genutzten Vier-Typen-Anwendung
`applyPrivateRemoteRecord`. Store und DAO werden zwingend aus derselben Room-Datenbank
erzeugt. Die Dokumentliste und die flachen Payload-Maps werden vor der ersten
Suspension kopiert; dies ist keine allgemeine Tiefenkopie beliebiger Objekte.

Page-/Recheck-Commits wenden Geschäftsdaten, gegebenenfalls erzeugte Placement-
Outbox-Mutationen und Tombstone-Receipts innerhalb derselben Transaktion wie Marker
und Checkpoint an. `Completed` kann neue lokale Outbox-Arbeit enthalten und ist kein
ACK. Bestehende Outbox-/ERROR-/CONFLICT-Blockaden werden als `LOCAL_MUTATION` markiert;
fehlende Binder-/Collection-Eltern erhalten typisierte Marker mit exakter Parent-ID.
Eine spätere ACK allein entfernt keinen Marker. Ein bestätigter Recheck verschiebt
den Remote-Cursor nicht. Bestehende Kollisions-, Tombstone- und Preiswahlregeln bleiben
unverändert; der Adapter führt keine neue Konfliktentscheidung aus.

Unbestätigte oder Pending-Envelopes werden ohne Daten-/Cursoränderung zurückgewiesen. Nur ein
ausdrücklich ungültiger Payload bzw. unveränderlicher Identitätswiderspruch wird als
abgeschlossen behandelt, nicht ein fehlender Parent oder Netzwerkfehler. Fehlende
Point-Read-Dokumente dürfen nicht als leere Payload-Map übergeben werden. Der Adapter
hat keine Firebase-, Netzwerk-, Scheduler- oder ACK-Aufgabe und erteilt keine
Berechtigung. Die Timestamp-Vertrauensprüfung, begrenzte faire Recheck-Steuerung,
Full-Repair-Strategie und tatsächliche Worker-Verdrahtung bleiben eigene offene Schritte.

Der aktive Worker bleibt beim begrenzten Full-Pull; Sharing-Priorität, Rules, DAO,
Room-Schema und Cloud-Payloads werden durch diesen Schritt nicht geändert.

Neu angelegt sind **32 echte Room-Integrationstests** in
`PrivatePullRemoteRoomAdapterInstrumentedTest`: vier Datentypen, ausstehende Mutationen,
Ownerisolation, Parent-Nachlieferung, Clear/Preisidentität, Receipts, Markergrenze,
Session-/Checkpoint-/Marker-CAS, mutable Eingabemaps sowie Datei-Datenbank-Wiederöffnung.
Die Rollback-Fälle lesen innerhalb der tatsächlichen Transaktion die bereits
geschriebenen Geschäftsdaten, generierte Outbox, Receipt und Marker; danach werden
Ausnahme, Cancellation oder A→B→A geprüft, einschließlich des letzten Guards nach
Checkpoint-Schreiben. Dieselbe zurückgerollte Seite kann anschließend erneut angewandt
werden. Tests wurden vor dem neuen Adapter geschrieben; es gibt keinen behaupteten
historischen RED-Nachweis gegen einen zuvor vorhandenen Adapter.

Ausgeführt auf dem isolierten Offline-API-36-Emulator: **32/32 Tests bestanden in
1,836 Sekunden**. Debug-App und Instrumentierungs-APK wurden erfolgreich gebaut
(38 Sekunden; 73 Tasks, davon 8 ausgeführt und 65 aktuell). Die Zeitangaben sind
Laufprotokolle, keine Performance-SLA. Nachweise im lokalen Ordner
`artifacts/release27-engineering-20261001/`:
`private-pull-remote-room-build.log` und
`private-pull-remote-room-instrumentation.log`.

Zusätzlich bestand auf denselben APKs das vollständige Instrumentierungspaket
`com.dominik.cardvault.data.sync`: **140 PASS / 1 opt-in SKIP / 0 FAIL in 5,348
Sekunden**. Nachzählung der einzelnen Instrumentierungsstatus korrigiert die
frühere Angabe „141/141“: Der Runner zählt den übersprungenen Placement-Lasttest
in `OK (141 tests)` mit. Die 140 PASS enthalten die neuen 32 Fälle; die Zählungen
werden nicht addiert. Nachweis:
`private-pull-remote-room-sync-regression.log` im selben lokalen Artefaktordner.
SHA-256 der APKs: App
`B73F8783302651C7EA435B303F434E63C7418902DB27D550D97B1B2F5BBD3C69`,
Instrumentierung
`3B3DE487F96710451B7E3FC19016D392084BC45F34260144C3DD1DB0B3571796`.

Dies ist das lokale Sync-Instrumentierungspaket, nicht die gesamte Android-Testsuite.
Der Testumfang ist Room-/Protokollintegration, kein Firebase-Worker-/Geräte-E2E- oder
Leistungsnachweis. Kein aktiver Worker wurde umgestellt; keine Release-Signierung,
Veröffentlichung oder Nutzung des Xiaomi erfolgte dafür.

### Tatsächlicher Android-SDK-Overlay-Nachweis (2026-10-01)

Die drei opt-in Fälle in `PrivatePullSdkCompletenessInstrumentedTest` wurden mit
Firestore Android 26.5.0, realem lokalen Auth-/Firestore-Emulator und unveränderten
Rules ausgeführt: **3/3 PASS, 2,962 Sekunden**, anschließend unabhängiger Admin-
Readback aller sechs synthetischen Dokumente. Transportfreigabe und tatsächliche
ACK-/Rules-Antworten werden geprüft, nicht simuliert. Siehe
`docs/PRIVATE_PULL_SDK_PROOF.md` für Ablauf, Hashes und erhaltene Aufbaufehler.

Der derzeitige private `runTransaction`/Commit-Pfad behält im getesteten Fall die
vollständige begrenzte Query während eines noch unbestätigten Commits. Eine
absichtlich andere optimistische `set`/Write-Operation kann dagegen ein Dokument
aus einer nach Timestamp begrenzten Query verdrängen, obwohl die Serverantwort
und alle zurückgegebenen Dokumente `hasPendingWrites=false` melden. Nach einer
tatsächlichen Rules-Ablehnung kehrt das Dokument mit altem Timestamp zurück;
allein ein inklusiver Folgecursor ab dem neueren Dokument repariert dieses Loch
nicht. Eine leere bestätigte Query ist ebenso wenig ein Vollständigkeitsbeweis.

Das ist kein nachgewiesener Fehler des aktiven bounded-full-pull Workers und kein
Grund, seinen stabilen Schreibpfad zu ersetzen. Für eine spätere Delta-Aktivierung
bleiben Overlay-Ausschluss, faire Wiederprüfung und vollständige Reparatur nötig.
Die drei SDK-Fälle ersetzen weder Room-Integration noch den Nachweis vollständiger
Geräte-zu-Geräte-Konvergenz, historischer Zeitstempel oder Hard-Delete-Reparatur.

### Dauerhafte lokale Recheck-Auswahl — noch nicht aktiviert (2026-10-01)

`reserveNextDeferredBatch` ergänzt den lokalen Room-Adapter um eine Auswahl von
höchstens 50 vorhandenen Deferred-Markern. Limit `0` liefert `NoBudget`, ohne
Session-Guard, Datenbankzugriff oder Schreiboperation. Pro Aufruf werden höchstens
die vier bekannten Gruppen besucht; genau eine nichtleere Batch wird ausgegeben.
Die Gruppen wechseln im Round-robin. Jede Gruppe behält während eines Durchlaufs
eine feste Dokument-ID-Obergrenze, damit neue lexikografisch größere Marker den
nächsten Umlauf nicht endlos verzögern. Ein zusätzlicher indexierter `LIMIT 1`-
Metadatenabruf ermittelt diese Grenze, ohne alle Marker zu laden.

Nur `private-sync-v2:<base64-owner>:deferred-schedule` wird verändert. Die kurze
Room-Transaktion prüft die erfasste Ownergeneration vor/nach Leseoperationen und
nach dem letzten Write. Ein Wechsel innerhalb dieser Grenze rollt die Reservierung
zurück. Ein erst nach erfolgreichem Commit beobachteter Wechsel unterdrückt die
Ausgabe der Batch, behauptet aber keinen nachträglichen Rollback. Die ausgegebene
Markerliste ist kopiert und durch `Collections.unmodifiableList` geschützt.

Eine Reservierung ist weder ACK noch Remote-Apply: Outbox, Nutzdaten, Marker und
Anwendungscheckpoint bleiben unverändert. Nach Absturz oder ausgefallenem Request
werden die Marker spätestens in einem folgenden endlichen Sweep erneut ausgewählt,
sofern die Ausführung fortgesetzt wird und die Metadaten gültig sind. Die spätere
Anwendung braucht weiterhin einen frischen vertrauenswürdigen Read und den
bestehenden exakten Marker-CAS. Eine fehlende Serverantwort wird nicht zum Tombstone.
Es gibt hier keinen Netzwerkaufruf und kein Signal für sofortige endlose APPENDs.

Beschädigte Scheduling-Werte dürfen ausschließlich diese eigene Auswahlposition
zurücksetzen (sichere Wiederholung). Beschädigte oder falsch zugeordnete Marker
bleiben dagegen erhalten und verursachen einen expliziten Fehler mit Rollback;
deren Reparatur ist nicht Teil dieses Bausteins. Für korrupte Marker wird keine
Fortschrittsgarantie behauptet. Der bestehende stateless `deferredPage`-Abruf
bleibt kompatibel. Ein Schedule-Key wird nicht über `UserBackupStore` exportiert
oder importiert; dessen feste Business-Table-Liste schließt `app_metadata` aus.
Ein neuer pauschaler lokaler Account-Cleanup wurde nicht eingeführt.

Testquellen zuerst ergänzt: sechs JVM-Vertragsfälle und 18 echte Room-Fälle für
Reihenfolge, feste Grenzen, Ownertrennung, Unveränderlichkeit, ACK-Abgrenzung,
Neustart, konkurrierende Reservierungen und Cancellation/Generations-Rollback.
Root-Ausführung auf dem isolierten API-36-AVD: **6/6 JVM und 18/18 Room PASS**;
Room 0,992 Sekunden, keine Skips. Der volle JVM-Lauf hat 853 PASS / 1 bestehenden
opt-in SKIP / 0 FAIL. Debug-/AndroidTest-Build und Lint bestanden im zweiten
Buildlauf (1m 9s). Der erste Buildlauf scheiterte an einem fehlenden Anführungszeichen
in einer neuen Testquelle; dies ist ein korrigierter Testaufbaufehler, kein historischer
Produkt-RED. Artefakte unter `artifacts/release27-engineering-20261001/`:
`deferred-schedule-regression-build.log`, `deferred-schedule-regression-build2.log`,
`deferred-schedule-install.log`, `deferred-schedule-instrumentation.log`.
Zwei unabhängige Quellenreviews fanden im engen Scope keinen konkreten Blocker.
Reservierungen sind keine exklusiven Locks: nach einem Sweep-Wrap können parallele
Verbraucher denselben Marker erhalten; der frische Read und exakte CAS bleiben nötig.
Dies ist neue, noch unverdrahtete lokale
Funktionalität, kein historischer Fehlernachweis gegen den aktiven Full-Pull und
keine Delta-/Hard-Delete-/Geräte-E2E-Freigabe.
