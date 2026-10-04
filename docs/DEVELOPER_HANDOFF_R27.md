# R27 – Einstieg für Entwickler

Stand: 04.10.2026, Europe/Berlin. Diese Übergabe trennt den veröffentlichten
Test-Build, den aktuellen Quellcode und noch offene Engineering-Nachweise.
**Geschlossene Alpha ist nicht gleich vollständig bestandene Release-Gates.**

## Richtigen Quellstand auschecken

Android und Web liegen in zwei privaten Repositories. Für die R27-Weiterarbeit
jeweils ausdrücklich den Engineeringbranch verwenden, nicht einen alten Default-Checkout:

```sh
git clone --branch codex/release-27-hardening https://github.com/nandedomi/DomiDex.git
git clone --branch codex/release-27-hardening https://github.com/nandedomi/DomiDex-Web.git
```

Die Standardbranches enthalten einen sichtbaren Übergabeeinstieg. Das ist kein
stiller Merge des gesamten R27-Produktcodes. Ein Update von `master`/`main` ist
getrennt zu reviewen; die Android-Historien sind divergiert. Kein Force-Push.

Vor dieser Dokumentationskorrektur remote bestätigt: Android `06190b2`, Web
`77b37a1`. Neuere reine Dokumentationscommits ändern das vorhandene AAB nicht.
Private Konfigurationen, Schlüssel, persönliche Daten und lokale Belegordner
werden nicht automatisch mitgeklont. Zugriff darauf kontrolliert beim Betreiber
anfordern, niemals Credentials in Issues oder Chats einfügen.

## Was ist tatsächlich veröffentlicht?

| Bestandteil | Verifizierter Stand |
| --- | --- |
| Android-Paket | `com.dominik.cardvault`, Code **27**, Name **1.0-closed-alpha.6** |
| Google Play | Interner Test und geschlossene Alpha aktiv; Alpha am 04.10.2026 um 21:54 bestätigt, kein öffentlicher Produktionsrollout |
| Unverändertes AAB | `DomiDex-v27.aab`, 52.705.225 Bytes; SHA-256 `2c3fe33bb84c47fda4525297a8134859f44fd727dabf268bade401d36f276cc7` |
| R8-Mapping | Vorhanden, dem exakten AAB zugeordnet; SHA-256 `8900194e5eaa4042f46e4043eba099062d30d60f7974d533cee7868649448c8e` |
| Native-Symbole | Nur **1 von 7** Libraries exakt abgedeckt (`libc++_shared.so`); ZIP-SHA-256 `cbef5ac45ae8c7c870d40cc8ea77fa75da8b9bfe1c8d7a556be65f776e30e687` |
| Web/Backend | Hosting `334fdfd6961039a1`, Cloud-Run-Revision `domidex-web-r27-20261004d`, veröffentlicht 04.10.2026 um 21:47:30 |
| Sync-Entscheidung | **Full-Sync bleibt aktiv, Delta bleibt deaktiviert.** |

Das [R27-Binärarchiv](https://github.com/nandedomi/DomiDex/releases/tag/archive/v27-1.0-closed-alpha.6)
enthält die bestehenden Release-Dateien, nicht einen neuen Build. Ein AAB ist
nicht direkt auf einem Gerät installierbar. GitHubs automatische „Source code“-ZIP
ist **kein Nachweis eines bytegenau reproduzierbaren Release-Builds**.

Die Build-Provenienz nennt `a3c7aa7ddef8987faa285bebf1bd8779313baae4` und
`source_dirty_at_archive: true`. Der zugehörige Erfolgsbeleg präzisiert das:
638 Quellhashes vorher/nachher identisch, keine getrackten Änderungen; zehn
untracked Hardware-Proof-Dateien waren ausdrücklich außerhalb des normalen Builds
zugelassen. Das Dirty-Flag beweist hier also keinen unbekannten geänderten Appcode.
Git allein reicht trotzdem nicht zur vollständigen Reproduktion: lokale
Firebase-/SDK-Konfiguration, Toolchain und Signing sind weitere Build-Eingaben.
Den späteren Engineering-HEAD nicht mit dem Buildcommit gleichsetzen. Der
Hashnachweis identifiziert eindeutig das tatsächlich ausgelieferte AAB.

## Entwickeln und prüfen

- Android: [Build/Release](BUILD_AND_RELEASE.md), [Tests](TESTING.md),
  [Architektur](ARCHITECTURE.md), [Sync](SYNC.md), [Scanner](scanner-architecture.md).
- Web: [README und Setup](https://github.com/nandedomi/DomiDex-Web/tree/codex/release-27-hardening),
  [aktueller Web-Handoff](https://github.com/nandedomi/DomiDex-Web/blob/codex/release-27-hardening/docs/WEBAPP_RELEASE_27_HANDOFF.md).
- APIs, Auth, Regeln und Sidecars: [R27-Security](RELEASE_27_SECURITY.md),
  [Firebase-Grenzen](../FIREBASE_SECURITY.md),
  [Web-Vertrag](https://github.com/nandedomi/DomiDex-Web/blob/codex/release-27-hardening/docs/FIREBASE_COLLECTION_CONTRACT.md).
- Preise: [Cardmarket-Import](cardmarket-import.md). Exakte Druckidentität erhalten;
  fehlende Preise nicht als Null oder als Preis einer gleichnamigen Karte ersetzen.

Android verwendet Gradle 9.5.0 / AGP 9.3.1, das geprüfte Release JDK 25.0.2,
minSdk 26 / targetSdk 37 und arm64-v8a. Firebase-Clientkonfiguration sowie Android
SDK-Pfade lokal bereitstellen. Für JVM-Tests: `./gradlew :app:testDebugUnitTest`
(Windows: `.\gradlew.bat :app:testDebugUnitTest`). Nicht ungefragt signieren.

Web verwendet die eingecheckte Lockdatei; nach Prüfung der lokalen Umgebung
`npm ci`, `npm run typecheck` und `npm run test:release-local` verwenden.
Der isolierte Release-Runner liest keine privaten `.env`-Werte und ersetzt weder
einen echten SDK-/Geräte-Endtest noch einen Produktionslasttest. Die speziellen
Emulator-Runner und ihre README-Dateien beachten; keine Testdaten gegen Produktion.

## Offene Aufgaben – nicht als erledigt übernehmen

1. **Web-Sammlung, neu nachgewiesener Fehler:** Am 04.10.2026 um 22:32 führte der
   Sprung ans Listenende live zu React #185 (Maximum update depth). Produktionsstack
   verweist auf `WindowedCollectionGrid`: die Maximalhöhe nur sichtbarer Karten
   kann das virtuelle Fenster und damit erneut die gemessene Höhe ändern.
   Regression und Fix stehen noch aus. Die bisherigen 1.299 bestandenen Webtests
   deckten diesen variierenden Geometriefall nicht ab.
2. **Private Binder:** Zwei nicht auflösbare Platzierungen wurden schon vor dem
   Web-Deployment beobachtet. Exakte Owner-/Entry-Verknüpfung prüfen; nicht durch
   Löschen, falsche Karten oder angenommene Bild-URLs kaschieren.
3. **Berechtigungsentzug:** Der ursprüngliche Lock-only-Listener-Nachweis bleibt
   17/18 RED, mit unverändertem 30s-Ziel. Der gesondert bestandene frische
   Account-Deletion-/Session-Consumer-Fall ersetzt ihn nicht. Legacy-Jobs gesondert prüfen.
4. **Weitere Nachweise:** OS-/WorkManager-Hintergrundzustellung, physische
   Mehrgeräte-/Kameramatrix, operative Datenschutz-/Löschprozesse und belastbare
   Live-/Last-/Kostenmessung nicht pauschal als vollständig abgeschlossen behandeln.
5. **Katalog/Preise/Symbole:** Vollständige Anbieterpreis- und Bildabdeckung ist
   nicht erreicht. Sechs fehlende Hersteller-Native-Symbole bleiben separat offen;
   eine warnungsfreie Play-Oberfläche ist keine vollständige Symbolabdeckung.

Priorität: Web-Crash reproduzierbar absichern, gezielt beheben und bestehende
Regressionen erneut ausführen. Keine zusätzliche Sync-Architekturänderung.
Details, frühere Fehlversuche und Nachweisgrenzen bleiben im
[Engineering-Ledger](RELEASE_27.md) erhalten; neuere Checkpoints haben Vorrang
vor ausdrücklich historischen Zwischenständen. Der Ledger enthält Verweise auf
private lokale Belege: diese sind nicht automatisch GitHub-Anhänge.

## Änderungs- und Betriebsgrenzen

- Keine neuen Releases, produktiven Deployments, Nutzerrechte oder Migrationen
  allein durch Clone, Dokumentations-Push oder Archivierung auslösen.
- App Check bleibt standardmäßig `off`, Write-Limiter `monitor`, sofern nicht
  separat anhand einer freigegebenen Live-Konfiguration anders nachgewiesen.
- Die autorisierte Preisverlaufs-Aufbewahrung ist auf zwölf Monate für Karten
  lokaler Sammlungen begrenzt; nicht gesammelte Karten behalten den letzten Preis.
  Das ist nicht als vollständig reversible Historienbereinigung zu bezeichnen.
- Noch lokale, nicht freigegebene Teständerungen und experimentelles
  `backend/market-data` gehören nicht zum verifizierten Release-/Deploymentnachweis.
- Keine Signing-Schlüssel, Tokens, Benutzerbilder oder privaten Logs ins Repository.

Diese Übergabe verbessert die Nachvollziehbarkeit. Sie erklärt keinen offenen
Gate-Nachweis zum PASS und ersetzt keinen notwendigen Hotfix.
