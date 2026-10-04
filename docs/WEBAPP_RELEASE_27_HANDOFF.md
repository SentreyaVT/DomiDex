# Release 27: Android / Web backend handoff

Updated 2026-10-04. This handoff does not itself authorize a deployment.

## Current deployment checkpoint

Follow-up hotfix, explicitly authorized on2026-10-04: Hosting
`5719d197695f2e1c` now pins`domidex-web-r27-20261004e`, released23:42:33
Europe/Berlin. Frozen Web source`39742dd` differs from the prior309-file production
stage only in`components/windowed-collection-grid.tsx`. Candidate/live45/45 and
authenticated desktop/mobile End/Home checks passed. Runtime/IAM/default percentage
traffic unchanged; no Rules/Functions deployment or physical-device change.
The earlier deployment below is historical. The new Legacy G9 SDK test is genuinely
RED at30s despite acknowledged lock/Auth revocation; all local data and exact
fixture cleanup verified. See the newest ledger checkpoint; no all-gates approval.

The user's separate explicit deployment authorization was executed on 2026-10-04.
Reviewed Web/backend source is live as Hosting version `334fdfd6961039a1`, pinned
to tag/revision `domidex-web-r27-20261004d` (published21:47:30 Europe/Berlin).
Firestore/Storage Rules were independently read back byte-identical. Candidate and
live checks each passed45/45, followed by actual visual/authenticated read-only
checks. Fresh Web regression passed1,299/1,299 in79 files. The root-layout build-path
collision and missing root security headers were reproduced and narrowly fixed.
Unreviewed `backend/market-data` was excluded. Functions, IAM, access grants and
device installations were not changed by this deployment. Historical "not deployed"
phrases below describe earlier checkpoints, not the current Web deployment state.

Android27 is also confirmed available in Closed Alpha, published21:54, with the
same verified signed AAB. This is not blanket completion of the engineering gates:
the lock-only SDK timeout, six unavailable vendor symbols, hardware/multidevice,
privacy lifecycle, production-load and other scoped limitations remain in
[the engineering ledger](RELEASE_27.md). Full-Sync remains; Delta is not enabled.

The Web application and backend remain in the separate sibling repository
`DomiDex-Web`. Its detailed handoff is `docs/WEBAPP_RELEASE_27_HANDOFF.md` there.
Do not copy the Web application into this Android workspace.

R27 owner decision (2026-10-02): retain the secured full synchronization paths.
The inactive delta foundation is a future optimization, not an R27 rollout gate.
Do not activate its reader, add cursors/migrations or replace the architecture.
Android/Web identity, conflict, offline and owner-isolation checks still apply;
no deployment or release signing is authorized by this scope decision.

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

Gate 9 now designates `DomiDex-Web/backend/scan-knowledge` as the canonical
Functions source. The historical Android copy uses the same function name and
`default` codebase but lacks current-evidence/deletion-lock protection. Its checked-in
Functions predeploy hook now refuses deployment; details and limits are below.
No indiscriminate `firebase deploy` from either workspace is authorized.

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

Gate 6 now locally reuses the existing exact supplement/anniversary enrichment and
honors a valid persisted alternative only when the exact printing has no trend and
the owner enabled prices or totals. Private selections are read in bounded batches
for needed entries only, never cached with public card metadata. The optional
`price.basis: "selection"` is backward-compatible with Android; Web labels the
chosen metric separately. Public amounts respect the existing Android wire limit;
unrepresentable complete totals are omitted, never clamped or assigned fake counts.
These changes are locally tested, not deployed.

Still pending: the handler loads up to 5,000 placements before selecting nine
visible slots and resolves all needed entries when totals are shown. There are no
true server-side page-only reads or verified anonymous ingress/IP limits yet.
The public `raw`/`prices` cache modes intentionally do not share unresolved results;
switching mode can require an additional first provider fetch, not a private-data
cache hit. Detailed scope and read costs are in the Web handoff.

### Scanner knowledge and reports

Community votes are supporting evidence, not automatic truth. Private evidence is
separate from optional photographic reports. Photo uploads stay explicit/manual;
neither App Check nor metrics enables them. The current Android knowledge path
rejects expired/unknown-time hints and scopes observations to an auth generation.
Ranking may reorder existing contradiction-free candidates only while both the
before/after decisions remain ambiguous; it cannot manufacture acceptance.
Physical printing/language accuracy and live aggregation still require validation.

## Implemented local contracts versus remaining work

- Gate 2, implemented and locally tested: distributed server-side budgets for costly authenticated operations;
  UID only after authorization, atomic shared store, distinct revocation/deletion
  policy, sanitized 429 with integer `Retry-After`, no automatic unsafe POST retry.
  Anonymous IP-based limits require a verified proxy trust boundary first.
- Gate 3, implemented but default-off: App Check Play Integrity in release, debug
  provider only in development, monitor-first backend integration. Live attestation
  and worker startup ordering remain unverified. No enforcement or replacement of
  Auth/Rules; see `RELEASE_27_APP_CHECK.md`.
- Gate 5, still pending: versioned exact price/catalog endpoint, conditional delivery, last-valid
  local fallback; no fabricated prices. Define schema before both clients change.
- Gate 11, audit/model prepared, live monitoring still unverified:
  operation/status/duration/cache counters, no token, email, UID label,
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

## Gate 9: eindeutiger Functions-Deploy-Eigentümer (2026-10-01)

Canonical Functions source: `DomiDex-Web/backend/scan-knowledge`

Der Web-Quellpfad ist für die gemeinsame Codebase `default` und den bestehenden
regionalen Export `aggregateScanKnowledge` maßgeblich. Die Android-Kopie unter
`functions/` bleibt unveränderte historische Referenz: Sie besitzt nicht den
transaktionalen `isCurrentEvidence`-/Kontolöschsperren-Guard des Web-Backends und
könnte bei einem versehentlichen Deployment dessen Schutz überschreiben.

Deshalb verweist ausschließlich `functions.predeploy` in dieser `firebase.json`
auf `scripts/deny-android-functions-deploy.cjs`. Dieser lokale Node-Guard beendet
sich immer mit Status 1, ohne SDK, Zugangsdaten, Netzwerk, Flags oder Env-Ausnahme.
Exports, Codebase, Signing sowie Firestore-/Storage-Konfiguration bleiben gleich.
Ein Android-Deploy mit Functions bricht ab; gezielte Firestore-/Storage-Vorgänge
bekommen keinen neuen Hook. Das ist ein Schutz der eingecheckten Konfiguration,
keine IAM-Sperre gegen bewusst andere Konfigurationen oder Cloud-Werkzeuge.

`node --test functions/deploy-ownership.test.js` prüft den Konfigurationsvertrag,
den Abbruch auch außerhalb des Projektverzeichnisses sowie wirkungslose
Overrideversuche. Der Test benötigt weder globale Firebase-CLI noch Web-Checkout.
Der separate Web-Test `tests/scan-knowledge-trigger.test.mjs` importiert den echten
Export mit SDK-Doubles; beide fachlichen Helper bleiben unverändert eingebunden.
Ein isolierter lokaler Nachweis des installierten CLI-15.28.2-Lifecycle-Runners
prüft zusätzlich Windows-`$PROJECT_DIR`-Expansion und Zielauswahl. Dabei werden
Cloud-/Auth-Imports ersetzt, nur der blockierende Guard über `cross-env` gestartet,
kein Firebase-CLI-Einstiegspunkt und kein Deploy ausgeführt. Dieser rechnerabhängige
Harness liegt ausschließlich unter den ignorierten Gate-9-Artefakten.

Dies legt den zuvor offenen lokalen Deploy-Eigentümer fest. Eine Aussage zum
aktuell produktiv laufenden Trigger, eine Übernahme der alten Android-Implementierung
oder eine Deploymentfreigabe ist damit ausdrücklich nicht verbunden.

Lokale Nachweise: 4/4 Guard-/Vertragstests zunächst rot, anschließend 4/4 grün;
mit den drei unveränderten Aggregationstests 7/7. Die zwei zusätzlichen lokalen
CLI-Harnesstests sind ebenfalls grün (nicht Bestandteil der sieben Repositorytests).
Belege: `artifacts/release27-engineering-20261001/gate9-ownership-red.log`,
`gate9-ownership-green.log` und `gate9-android-functions-green.log`.

Der vorhandene Functions-Testentry `npm --prefix functions test` (`node --test`)
entdeckt den neuen Guardtest automatisch; keine zusätzliche Dependency nötig.
Eine CI-Pipeline existiert derzeit nicht und wurde hier nicht neu angelegt.
