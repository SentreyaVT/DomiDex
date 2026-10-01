# Release 27: Android / Web backend handoff

Updated 2026-10-01. This is a development handoff, not deployment authorization.

The Web application and backend remain in the separate sibling repository
`DomiDex-Web`. Its detailed handoff is `docs/WEBAPP_RELEASE_27_HANDOFF.md` there.
Do not copy the Web application into this Android workspace.

## Reproducible baseline

- Android: `53b4e40d669012f69e6d1920d916475e0107b8fb`.
- Web: `6caa46a011889a416041224a67d89b75db7016da`.
- Both working branches: `codex/release-27-hardening`.
- These preserve the current source, not an unverified reconstruction of release 26.
- Full gate status and measurements: [RELEASE_27.md](RELEASE_27.md).

## Ownership

Android owns CameraX/OCR/preprocessing, immediate UI state, Room, owner-scoped local
collection/binders, persistent outboxes, offline caches and Android API clients.
The Web backend owns privileged operations, public share authorization, exact
external catalog/price processing, distributed API limits, aggregation and account
deletion. Neither UI replaces server authorization. Monitoring is cross-cutting,
low-cardinality technical evidence, not a second store of user collections.

An existing drift needs explicit resolution in Gate 9: `functions/index.js` here
and `DomiDex-Web/backend/scan-knowledge/index.js` deploy the same function name and
`default` codebase. The latter has an additional current-evidence/deletion-lock
guard. No indiscriminate `firebase deploy` from either workspace is authorized.

## Shared invariants (already implemented unless explicitly marked pending)

### Authentication and account state

Firebase UID is obtained from authenticated server-verified identity, never from a
request body. Claims are checked together with current `accountAccess`, disabled
state and `accountDeletionLocks`. Removing an access check to save a roundtrip is
not acceptable. Android local/offline data remains usable under existing product
policy; cloud authorization and data ownership remain separate.

### Collection and price selection

Collection identity includes set/card ID, collector number, language, finish,
variant and, when verified, Cardmarket product ID. Never resolve price by name alone.
`users/{uid}/collectionEntries/{syncId}` uses revisions, mutation IDs and tombstones.
Hard deletion by a client is denied; stale writes cannot revive final tombstones.

The additive `collectionPriceSelections/{entrySyncId}` sidecar preserves old-client
compatibility. Schema 1 stores revision/mutation/server timestamp, owner/entry key,
`cleared`, exact identity, `metric`, `amountMinor`, `currency`, `sourceAt`, `selectedAt`.
No new personal information is needed. Clear is a versioned record with null price
fields, not a physical delete. A valid source trend has priority over a confirmed
alternative; alternative is not relabelled as trend. A transport failure is not
evidence that a trend does not exist. Rules must validate the parent after atomic
changes, not merely the old pre-transaction state.

### Sharing

Canonical domain is `https://domidex.web.app`, page `/share/{token}`, existing API
`/api/shared-binders/{token}`. The backend creates a random 256-bit secret and stores
only its SHA-256. Explicit owner confirmation and price/total/display-name options
remain; private notes and owner identifiers never enter the public DTO. Both
Android and Web use the same backend, with a final revocation/access recheck after
slow work. Existing links and the Android app-link route must remain compatible.

Pending Gate 6: current implementation loads up to 5,000 placements before selecting
the nine visible slots and performs additional catalog work when totals are shown.
It does not yet have true server-side page-only reads. Its price resolver also
differs from normal collection enrichment; unify only with exact identity and
visibility consent preserved, not by publishing arbitrary client-entered prices.

### Scanner knowledge and reports

Community votes are supporting evidence, not automatic truth. Private evidence is
separate from optional photographic reports. Photo uploads stay explicit/manual;
neither App Check nor metrics enables them. Gate 8/9 must address stale hints and
hint-only automatic acceptance, and keep variants/uncertainty explicit.

## Contracts being prepared, not yet claimed implemented

- Gate 2: distributed server-side budgets for costly authenticated operations;
  UID only after authorization, atomic shared store, distinct revocation/deletion
  policy, sanitized 429 with integer `Retry-After`, no automatic unsafe POST retry.
  Anonymous IP-based limits require a verified proxy trust boundary first.
- Gate 3: App Check Play Integrity in release, debug provider only in development,
  monitor-first integration. No immediate enforcement or replacement of Auth/Rules.
- Gate 5: versioned exact price/catalog endpoint, conditional delivery, last-valid
  local fallback; no fabricated prices. Define schema before both clients change.
- Gate 11: operation/status/duration/cache counters, no token, email, UID label,
  share URL, raw scan or collection payload in telemetry. Cost assumptions must be
  labelled as simulations; no billing or scaling changes without authorization.

## Cross-platform acceptance

Use isolated synthetic Firebase test identities for allowed/denied operations,
two independent clients for concurrent price choices and old-client writes,
offline/reconnect and tombstone/conflict/restart tests. Test Android and Web error
handling, share revoke, disabled/deleting accounts, exact price variants and
optional reports. A mock test or clean build alone is not a live integration test.

Security rules copies must remain byte-identical; separate implementations must
agree on the above wire contracts. Record test counts without duplication.
Physical-device and Google Sign-In/Play Integrity validation remain explicit manual
items unless actually performed. Deployments, Play upload, budget activation and
credential rotation are separate manual actions, never inferred from this handoff.
