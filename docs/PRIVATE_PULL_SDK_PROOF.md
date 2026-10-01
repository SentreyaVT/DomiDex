# Lokaler Android-SDK-Nachweis: bestätigte Query und ausstehende Schreiboperation

Stand: 01.10.2026. **Drei tatsächliche Android-SDK-Fälle bestanden**, einschließlich
unabhängiger lokaler Serverkontrolle und Cleanup. Keine Reader-Aktivierung, kein
Produktionsbefund, kein vollständiger Worker-E2E- und kein Performancebeleg.

## Enger Zweck

Der bestehende private Android-Sync schreibt mit `runTransaction` (Commit-RPC).
Eine optimistische `DocumentReference.set()`-Operation verwendet dagegen die
lokale Mutation Queue und den Write-Stream. Die drei opt-in Instrumentierungstests
prüfen beide Pfade getrennt mit dem tatsächlichen Firestore-Android-SDK 26.5.0.
Die erwartete Auslassung im zweiten/dritten Test wäre ein Gegenbeispiel zur
Vollständigkeit einer **vorgeschlagenen** Delta-Query, kein nachgewiesener Fehler
des aktuellen bounded-full-pull Workers. Room, dessen Cursor und Outbox bleiben
unberührt.

Neue Dateien:

- `app/src/androidTest/java/com/dominik/cardvault/data/sync/PrivatePullDemoGrpcGate.kt`
- `app/src/androidTest/java/com/dominik/cardvault/data/sync/PrivatePullSdkCompletenessInstrumentedTest.kt`

## Fester Fixturevertrag für den isolierten lokalen Runner

Der Runner darf ausschließlich die bereits isolierte Demo-Projekt-ID
`demo-domidex-privacy` und die bestehenden lokalen Emulatoren verwenden:

- Firestore: `127.0.0.1:8187`, Datenbank `(default)`.
- Auth: `127.0.0.1:9097`.
- Unveränderte aktuelle Android-`firestore.rules`, keine Test-Allow-all-Regeln.
- Ausschließlich synthetische Benutzer `sdk-proof-commit`, `sdk-proof-write-ack`
  und `sdk-proof-write-reject`; E-Mail jeweils `<UID>@example.invalid`.
- Passwort ausschließlich für diese lokalen Fixtures:
  `Demo-only-private-pull-proof-2026` (kein echtes Zugangsdatenmaterial).
- Custom Claims je Benutzer: `{ "appAccess": true }`.
- Für diese Benutzer keine `accountDeletionLocks`-Dokumente. `accountAccess`
  kann gemäß bestehendem Claim-Fallback fehlen; falls gesetzt, darf es weder
  `disabled: true` noch `appAccess: false` enthalten.

Pro Benutzer zwei **vor dem Instrumentierungslauf lokal Admin-seedete** Dokumente
`users/<UID>/privateBinders/a` und `users/<UID>/privateBinders/b`:

| Feld | Wert |
| --- | --- |
| `binderId` | `a` beziehungsweise `b` |
| `ownerUid` | exakte jeweilige UID |
| `name` | `SDK proof A` beziehungsweise `SDK proof B` |
| `description` | `null` |
| `coverStyle` | `default` |
| `minimumPageCount` | Integer `1` |
| `createdAtClient`, `updatedAtClient` | jeweils Integer `1` |
| `lastOpenedAtClient` | `null` |
| `deleted` | `false` |
| `revision` | Integer `1` |
| `lastMutationId` | `sdk-proof-seed` |
| `updatedAt` für `a` | Firestore Timestamp `(seconds=1700000000, nanoseconds=1000)` |
| `updatedAt` für `b` | Firestore Timestamp `(seconds=1700000001, nanoseconds=2000)` |

Die alten Zeitwerte sind bewusst künstliche historische Testdaten, keine
aktuelle Serverzeit und kein Beweis historischer Rules-Konformität. Alle echten
SDK-Updates setzen anschließend `FieldValue.serverTimestamp()` und unveränderte
Rules entscheiden über Revision `2` (ALLOW) oder die stale Revision `1` (DENY).
`a`/`b`, die vollständigen Felder und `sdk-proof-mutation` passen zu den vorhandenen
Binder-ID-/Mutations-ID- und Payload-Regeln. Jeder Test hat seine eigene UID;
Wiederholung setzt die Fixtures im isolierten Demo-Emulator vollständig zurück.

## Isolation und Ausführung: ausschließlich nach Root-Review

Keine Downloads, produktiven Login-Aktionen, produktiven Credentials oder Cloudaufrufe.
Der hostseitige Runner muss die vorhandene Demo-/Loopback-Isolation verwenden:
frischer Konfigurationskontext, bereinigte Credential-/Proxy-Umgebung, geprüfte
lokale CLI/JARs, Node-Netzguard vor SDK-Import, feste localhost-Endpunkte und
Time-outs. Ein lokal privilegierter Fixture-Seed ist kein produktiver Adminzugriff.

Nur `emulator-5554`, niemals ein per USB angeschlossenes Smartphone. Vor dem Lauf:

1. Portbelegung prüfen; bei fremdem Prozess abbrechen, nichts nach Port/PID-Namen
   beenden. Nur vom Runner gestartete Emulatorprozesse später aufräumen.
2. `airplane_mode_on=1`, `wifi_on=0` und **kein aktives Android-Standardnetz**
   beibehalten. Die Tests prüfen diese Bedingungen vor Client-Erzeugung.
3. Bestehende `adb reverse`-Zuordnungen prüfen. Nur wenn die beiden betreffenden
   Ports frei sind, ausschließlich auf `emulator-5554` `tcp:8187 -> tcp:8187` und
   `tcp:9097 -> tcp:9097` anlegen. Keine vorhandene Zuordnung überschreiben.
4. Nach vorherigem Quellen-/Sicherheitsreview Debug-Test-APKs lokal erstellen.
   Nur die Klasse `com.dominik.cardvault.data.sync.PrivatePullSdkCompletenessInstrumentedTest`
   ausführen, Runner `com.dominik.cardvault.test/androidx.test.runner.AndroidJUnitRunner`,
   mit Argument `domidexPrivatePullSdkProof=demo-loopback-only`.
5. Im Runner-`finally` genau seine eigenen beiden Reverse-Zuordnungen entfernen,
   seine Demo-Emulatorprozesse beenden und Port-/Mapping-Nachkontrolle sichern.

**Praktisch bestätigt:** Auth-HTTP, tatsächliche Android-gRPC-Queries sowie
Commit-/Write-Antworten funktionieren im dedizierten Offline-AVD über die beiden
`adb reverse`-Verbindungen. Das ist mehr als ein Socket-Connect; Internet wurde
nicht aktiviert und SDK-Antworten wurden nicht durch Mocks ersetzt. Die enge
HTTP-Ausnahme für `127.0.0.1` liegt ausschließlich im Debug-Quellset. Der Test
prüft, dass globales HTTP und die Hosts `example.invalid` / `10.0.2.2` gesperrt
bleiben. Das erzeugte Release-Manifest enthält keine Network-Security-Config
und kein `usesCleartextTraffic=true` (targetSdk 37).

Ohne Opt-in-Argument werden diese drei Tests absichtlich übersprungen. Ein solcher
Standard-Instrumentierungslauf ist **kein PASS dieses SDK-Nachweises**.

## Kontrollierter Transport und Cleanup

Zwei voneinander unabhängige benannte FirebaseApps je Test, eigene Auth-/Firestore-
Instanzen, MemoryCache und explizite Emulator-Konfiguration; kein Zugriff auf die
DefaultApp, keine Änderung ihres Auth-Zustands oder ihrer Firestore-Einstellungen.
Der unveränderliche globale SDK-Testhook wird ausschließlich für die geprüfte
Version 26.5.0 und seine erwartete Feldsignatur per Reflection installiert. Ein
bereits gesetzter Hook führt zum Abbruch statt Überschreiben.

Da dieser SDK-Hook kein `DatabaseInfo` übergibt, baut er ausschließlich einen
Loopback-Kanal. Zusätzlich prüft der Interceptor dessen genaue Authority. Die
Weitergabe des echten `ClientCall.start` wird bis zur ersten typisierten Nachricht
zurückgehalten: Nur bekannte Commit-/Write-/Listen-/BatchGet-RPCs mit exakt
`projects/demo-domidex-privacy/databases/(default)` werden gestartet. Unbekannte
Methoden/fremde Datenbanken werden lokal abgewiesen, **bevor** CallCredentials
und Header an den echten Call weitergereicht werden. Eine lokale negative
Transportprobe belegt dafür null Delegate-Starts und null gesendete Nachrichten.
Tokens/Headers werden nicht geloggt.

Der Hook schützt nicht schon vorher existierende DefaultApp-Kanäle; deshalb ist
der unverändert offline gehaltene, dedizierte Instrumentierungsprozess zwingend.
Während des Hooks neu erzeugte fremde Calls werden lokal abgewiesen, nicht an das
Demo-Projekt weitergeleitet. Das ist ein Testhook, keine produktionsfähige
Netzwerk-Firewall oder allgemeine SDK-Isolation.

Ein exakt identifizierter Commit beziehungsweise Write wird mit einer Latch
zurückgehalten, ohne Firestore-Workerthreads zu blockieren. Die unabhängige
Kontroll-App liest währenddessen den echten Serverzustand. Keine Schlafraces:
Loopback-Connect maximal 3 Sekunden, Latch 15 Sekunden, Tasks jeweils 20 Sekunden.
Bei Fehlern werden gehaltene Mutationen **nicht** freigegeben. Das JUnit-`After`
führt Cleanup auch nach teilweisem Setup aus: jede bereits erfasste Firestore-
Instanz terminieren, nur benannte Test-Authinstanzen abmelden, Test-Apps löschen,
Hook wiederherstellen und die vor `delete()` gesicherten Appnamen nachprüfen.
Ein Cleanup-Fehler wird nicht als PASS verschluckt. Der äußere Runner muss zusätzlich
eine Gesamtlaufzeit begrenzen und seinen Prozess-/Reverse-Cleanup sicherstellen.

## Erwartete getrennte Belege

1. **Tatsächlicher `runTransaction`/Commit:** Vor ACK bleibt die begrenzte
   Serverquery `[a,b]` sowohl im Subject als auch im Control sichtbar, ohne lokale
   Mutation. Nach echtem ACK liegt `a.updatedAt` über dem alten oberen Rand und
   der inklusive Folgelauf ergibt `[b,a]`.
2. **Absichtlich optimistisches `set`/Write:** Die lokale Punktansicht hat eine
   ausstehende ServerTimestamp-Transformation. Control sieht `[a,b]`; die
   begrenzte Subject-Serverquery kann `[b]` und dennoch `fromCache=false` sowie
   `hasPendingWrites=false` liefern. Nach erfolgreichem ACK ergibt der Folgelauf
   `[b,a]`.
3. **Optimistisches `set` mit tatsächlicher Rules-Ablehnung:** Vor Freigabe dasselbe
   Loch, zusätzlich eine leere bestätigte Query bis zum ersten Timestamp. Nach
   `PERMISSION_DENIED` kehrt `a` mit seinem alten Timestamp zurück; ein alleiniger
   inklusiver Folgelauf ab `b` bleibt `[b]`, während der alte Bereich `[a,b]` enthält.

Alle drei oben beschriebenen Szenarien sind im finalen Lauf tatsächlich bestätigt.
Der Commit-Fall prüft die Punktansicht mit `Source.SERVER`: vorhanden, bestätigt,
ohne Pending-Writes, ursprünglicher Timestamp. Er setzt keine Cache-Aufbewahrung
nach dem Abmelden einer Query voraus. Beide begrenzten Queries bleiben zusätzlich
geprüft. Auch dieser PASS aktiviert keinen Delta-Reader und beweist weder
`Source.SERVER` als vertrauenswürdige Zeitquelle noch vollständige Reparatur von
alten Hard-Deletes oder fehlenden Zeitstempeln.

## Ausgeführte Nachweise und erhaltene Aufbaufehler

Versionierter Host-Runner im Schwesterrepository:
`DomiDex-Web/tests/private-pull-sdk-local/README.md`.
Der Runner installiert keine APKs; Root installierte die exakten Debug-Dateien
separat ausschließlich auf `emulator-5554` / `DomiDex_R27_Baseline_API36`.

- Finaler Debug-/AndroidTest-Build und Lint: **PASS, 1m 19s**, 82 Tasks, davon
  7 ausgeführt und 75 aktuell. Die vorherige kombinierte Debug-/Lint-/Release-
  Manifest-Prüfung bestand ebenfalls. Keine Release-APK, kein AAB oder R8-Lauf.
- Tatsächliche Instrumentierung: **3/3 PASS, 2,962 Sekunden**, keine Skips.
- Danach unabhängige Admin-SDK-Prüfung: erfolgreiche A-Updates exakt Revision 2,
  Name/Mutations-ID/ServerTimestamp wie erwartet; alle übrigen A-Felder
  unverändert. Alle B-Dokumente unverändert; abgelehntes A komplett unverändert.
- Manifest bestätigt `serverVerified`, unveränderte Eingabedateien, wieder freie
  Ports und leere Reverse-Zuordnungen, Exit 0.
- Web-Auswertungsvalidator: **2/2 PASS**; Web-Lint und Typecheck bestanden.
- Zusätzlicher Offline-Lauf des gesamten `data.sync`-Pakets ohne Opt-in:
  **140 PASS / 4 ausdrücklich SKIP / 0 FAIL**, 5,590 Sekunden. Die vier Skips
  sind die drei hier separat ausgeführten SDK-Fälle und der bestehende opt-in
  Placement-Lasttest. Die Runner-Zeile `OK (144 tests)` zählt Skips mit und ist
  daher nicht als 144 bestandene Fälle zu lesen. Nachweis:
  `private-pull-sdk-sync-regression.log` im selben Android-Artefaktordner.

Finale Nachweise: Web
`outputs/private-pull-sdk-local/2026-10-01T05-46-24-017Z-354ac507/`
(`manifest.json`, `instrumentation.log`, `server-proof.json`). Android
`artifacts/release27-engineering-20261001/private-pull-sdk-proof-build5.log`
und `private-pull-sdk-proof-install4.log`. Installierte SHA-256:

- Debug-App: `94E845D1DCCDDAF6465608AC7D1C274FF65CE3A0D88B56DE131FAF222BEAA752`
- AndroidTest: `1EA5844D925EAF4594B76949B29F69A5ABAA50CF2BACE949B0D4638DCF2A043C`

Frühere Fehlversuche bleiben getrennt dokumentiert, nicht als Produkt-RED:

1. Testkompilierung benötigte explizite Sichtbarkeit bereits vorhandener gRPC-/
   Protobuf-Klassen. Drei exakte `androidTestCompileOnly`-Abhängigkeiten ohne
   Transitivität ergänzen keine App-Runtime-Abhängigkeit oder neue Version.
2. Lauf `2026-10-01T05-35-54-096Z-36baf158`: drei Setupfehler, zuerst blockiertes
   Loopback-HTTP, danach wiederverwendeter synthetischer API-Key einer gelöschten
   benannten App. Debug-Loopback-Ausnahme und pro App neuer Demo-Key korrigieren
   ausschließlich den Testaufbau.
3. Lauf `2026-10-01T05-42-26-504Z-c108d5ec`: drei Setupfehler am Fixture-Timestamp.
   Firestore speichert Feldwerte mikrosekundengenau; nanos 1/2 sind ungeeignet.
   nanos 1000/2000 werden nun vor Android-Start durch Admin-Readback exakt geprüft.
   Lokaler Primärbeleg: Web `node_modules/@google-cloud/firestore/build/protos/
   google/firestore/v1/document.proto`, Feld `timestamp_value`.
4. Lauf `2026-10-01T05-44-20-568Z-1fdcd71e`: zwei Szenarien bestanden; Commit-Test
   scheiterte an unnötig vorausgesetzter Cache-Aufbewahrung. Der SDK-MemoryCache
   nutzt standardmäßig Eager-GC. Die bestätigte Server-Punktansicht ersetzt diese
   Annahme, ohne die Query-/Overlay-Assertions zu entfernen oder GC umzubauen.

Alle drei vorherigen tatsächlichen SDK-Läufe bestätigten sauberes Port-/Reverse-
Cleanup und unveränderte Eingaben. Sie sind kein erfolgreicher Gesamtnachweis.
Der finale Umfang bleibt sechs synthetische Dokumente, drei Demo-Owner und die
angegebenen Android-SDK-Primitiven; nicht der vollständige Sync-Worker, Room,
eine reale Cloud oder ein physischer Gerätevergleich.
