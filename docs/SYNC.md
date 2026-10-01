# Release 27 synchronization contract

Status: Gate 4 design and measured regression work, not a deployed delta protocol.
The current application remains Room-first. Auth, entitlements, owner isolation,
optimistic revisions, tombstones and the existing private-sharing readiness gate
must remain authoritative. No production data migration is authorized here.

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

## Private binder delta protocol — planned, not implemented yet

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
