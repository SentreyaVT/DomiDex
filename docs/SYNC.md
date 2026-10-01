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

## Private binder delta protocol — foundation in progress, not active yet

`PrivateBinderPullProtocol.kt` now defines pure phases, timestamp tuples, bounded
page transitions and strict local metadata encoding. `PrivatePullRoomStore.kt`
implements exact-raw CAS plus atomic payload/marker/checkpoint commit. A fresh
local epoch prevents a repaired/reset checkpoint from recreating an older CAS
token. `PrivatePullAuthSession.kt` prepares a per-run token/account-event lease.
These components are **not yet connected to the running Firebase worker**. Their
tests must pass before integration; no delta/network speedup is claimed yet.

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
Blocked documents are revisited after the next full cycle; no permanent-orphan
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

`PrivateFullPullRunner` is the worker's actual four-phase control flow; its adapter
retains Firestore SDK reads, snapshot metadata and session-guarded Room writes.
It accounts for the whole fetched page before applying rows, at most 200/query and
1,000 logical read units/run. Empty queries count one; point reads are reserved
before requesting a parent, including missing documents. The per-run parent set
is bounded by that budget and is discarded before the next invocation.

If a never-attempted dependency cannot fit, the bookmark remains before its child.
Already-attempted missing or locally blocked parents allow the FULL scan to advance
and be revisited next cycle. Completing phase four resets once, including recovery
from a persisted phase-four checkpoint; it never starts another full cycle in the
same invocation. A single shared owner gate covers phase read through final write.
It retains at most eight active owners and releases synchronously in `finally`.
Concurrent pulls retry with existing WorkManager backoff; pushes and sharing are
outside the gate. This is single-process exclusion, not a distributed lock.

Thirty-seven new runner/gate/budget cases replace two obsolete cursor-only tests;
the selected sync/sharing total is now 189 JVM tests. Synthetic 1k/5k/20k matrices
exercise two finite full cycles and exact ID/phase bookmark sequences. The final
debug/test/release-Kotlin build and 78 emulator regressions pass. A metadata port
and fake provider are not Firebase E2E, and logical read units are not an invoice
prediction (SDK retries and Firestore pricing are not modeled). Delta/network
watermark integration, actual cross-device scale and remote latency remain open.

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
