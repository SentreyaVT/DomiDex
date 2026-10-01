# DomiDex

DomiDex hilft Sammlern, physische Pokémon-TCG-Karten zu erfassen, in digitalen
Set- und privaten Bindern zu organisieren und verfügbare Marktwerte zu überblicken.
Der Schwerpunkt liegt auf deutschsprachigen Sammlungen. Andere TCGs sind keine
nachgewiesen unterstützten Produktkataloge. Eine integrierte Tauschplattform und
ein eigenes Grading-System gehören nicht zum aktuellen Funktionsumfang.

## Projektstand: geschlossene Alpha

**Technische Bestandsaufnahme: 1. Oktober 2026.** Dieses Dokumentationsupdate ist
kein App-Release. Es enthält keine neuen App-Funktionen, APKs oder AABs.

| Stand | Nachweis |
| --- | --- |
| Google Play | **26 / `1.0-closed-alpha.5`**, am 01.10.2026 lesend bestätigt: aktiver geschlossener Alpha-Test **und** aktiver interner Test, jeweils vollständiger Roll-out |
| Lokale Entwicklung | **27 / `1.0-closed-alpha.6`**, bereits vor diesem Audit konfiguriert; kein abgeschlossener signierter v27-Build belegt |
| Lokaler Quellcode-Commit vor Audit | `096aa35` auf `codex/ui-v3`, Konfiguration darin noch Build 21; zahlreiche zusätzliche unveröffentlichte Änderungen |
| GitHub-Quellcode vor Audit | Standardbranch `master`: Build 16 (`9bbe7bc`); `codex/ui-v3`: Build 19 (`77b76cf`) |

**Wichtig für einen neuen Checkout:** Der Dokumentationsstand beschreibt auch den
geprüften lokalen Entwicklungsstand, nicht nur den älteren Code auf GitHub.
Dieser reine Dokumentations-Push übernimmt absichtlich keine unveröffentlichten
Code-Commits. Ein Clone des Standardbranches ist **nicht** der Quellnachweis für
die signierte Play-Version 26 oder die lokale Entwicklung 27. Einzelheiten und
offene Reproduzierbarkeit: [Projektstatus](docs/PROJECT_STATUS.md).

## Releases und Downloads

Das vorhandene signierte **Release 26** ist zusätzlich als
[GitHub-Binärarchiv](https://github.com/nandedomi/DomiDex/releases/tag/archive/v26-1.0-closed-alpha.5)
hinterlegt: Original-AAB, passendes R8-Mapping, partielle Native-Symbole und
SHA-256-Prüfsummen. Kein neuer Build und kein Play-Upload. Ein AAB ist nicht
direkt installierbar. Der Archiv-Tag ist **kein rekonstruierter v26-Quellstand**;
die automatisch erzeugten Source-code-Downloads sind nicht dessen Buildquellen.
Historische Testreleases bleiben als Archiv/Vorabversion erhalten.

## Was im geprüften Entwicklungsstand vorhanden ist

- **Karten erfassen:** Kamera, Galerie, Set-/Nummernsuche, OCR- und Bildvergleich,
  Kandidatenprüfung, manuelle Korrektur und ausdrückliches Hinzufügen.
- **Sammlung und Set-Binder:** vorhandene/fehlende Plätze, Mengen, Normal/Holo/
  Reverse und weitere Druckvarianten, Fortschritt und gezielte Set-Zuordnung.
  Nummern oberhalb der gedruckten Main-Set-Gesamtzahl bleiben gültig.
- **Private Binder:** freie Anordnung, zusätzliche Seiten, Cover, individuell
  gewählte Holo-Effekte und Freigabelinks. Private Platzierungen referenzieren
  Sammlungseinträge; sie erzeugen nicht automatisch neue besessene Karten.
- **Preise und Portfolio:** öffentliche Cardmarket-Produkt-/Preisdownloads,
  exakte Druckzuordnung, EUR-Trendwerte und Preishistorie. Fehlende Belege oder
  Preise werden nicht durch gleichnamige Karten ersetzt.
- **Lokale Daten und Cloud:** Room, kontogetrennte Daten, Google/Firebase-Anmeldung,
  berechtigungsabhängiger Sync, Offline-Outbox, Konfliktzustände und expliziter
  Backup-Export/-Import.
- **Scanner-Feedback:** lokaler Editor, separat freiwilliges bildfreies
  Community-Knowledge und manuell bestätigter ScanLab-Fotoversand.

Die neue Auswahl einer geprüften Preisalternative **nur bei fehlendem Trendpreis**,
ihr Sync-Sidecar und weitere Scanner-/Sharing-Änderungen liegen teilweise nur lokal
vor. Daraus folgt keine Freigabe oder Veröffentlichung von Version 27.

## Scanner in Kürze

CameraX liefert Aufnahme und Qualitätsinformationen; OpenCV bereitet die Karte
auf, der gebündelte Latin-ML-Kit-Recognizer liest Text lokal. Set-/Nummer-/Sprach-
und Bildmerkmale werden mit dem Quellkatalog abgeglichen. Die Zustände
`ACCEPTED`, `AMBIGUOUS` und `REJECTED` steuern die Prüfung; auch ein akzeptierter
Treffer wird nicht still in einen Binder eingefügt.

Auto-Capture ist standardmäßig **aus**. Grün bedeutet Aufnahmequalität, nicht
garantierte Identifikation. OCR ist lokal, Katalog-/Bild-/Knowledge-Abfragen können
Netzwerk benötigen. Die Erkennung älterer Karten, Promos, Reflexionen und schwerer
Lichtbedingungen bleibt ein Test- und Optimierungsziel.

[Scanner, Editor und Datenschutzgrenzen](docs/scanner-architecture.md)

## Sammlung, Preise und Teilen

Kartenidentität ist mehr als der Name: Quellkarte, Set, Sammlernummer, Druck,
Finish und gegebenenfalls exakte Cardmarket-Produkt-ID müssen zusammenpassen.
Cardmarket-Trendwerte sind Orientierungswerte, keine garantierten Verkaufspreise
und keine ausschließlich deutschen Zustands-/Sprachpreise. Vollständige
Preisabdeckung ist **nicht** erreicht.

Die Portfolio-Historie bewertet den heutigen Bestand mit historischen Preisen;
sie ist keine vollständige Kauf-/Verkaufshistorie oder Renditeberechnung.
Eine eigenständige Favoritenliste wurde nicht gefunden; vorhanden ist unter
anderem die lokal ausgewählte Dashboard-Fokuskarte.

Binder-Freigaben sind widerrufbare **Zugangslinks**: Wer einen gültigen Link besitzt,
kann die freigegebene Ansicht lesen. Das ist keine individuelle Empfängerrollen-
Freigabe. Der Sharing-/ScanLab-Webdienst liegt im separaten DomiDex-Web-Projekt.

[Architektur](docs/ARCHITECTURE.md) · [Preise](docs/cardmarket-import.md) ·
[Cloud und Sicherheit](FIREBASE_SECURITY.md)

## Technik und Entwicklung

Ein Android-Modul `:app`, Kotlin/Compose, Room, WorkManager, CameraX, ML Kit,
OpenCV, Coil und Firebase. Im lokalen Audit: minSdk 26, compile/targetSdk 37,
Gradle 9.5.0, AGP 9.3.1, Gradle-Daemon-JDK 25, Java-Bytecode-Ziel 11.
Deklarierte und tatsächlich aufgelöste Library-Versionen können sich unterscheiden.

1. Zuerst Branch und [Build-/Release-Dokumentation](docs/BUILD_AND_RELEASE.md)
   prüfen; die aktuelle Entwicklung ist nicht vollständig auf GitHub.
2. Passendes Android SDK/JDK und Android Studio verwenden; `local.properties`
   und Firebase-Clientkonfiguration separat lokal bereitstellen.
3. Keine Upload-Schlüssel, Service-Account-Dateien oder Passwörter einchecken.
   Eine ignorierte `app/google-services.json` gehört nicht zu einem frischen Clone.
4. Für reine JVM-Prüfung: `./gradlew :app:testDebugUnitTest` bzw.
   `.\gradlew.bat :app:testDebugUnitTest`. Das erzeugt keine APK/AAB.

Ein Release-Build, Signierung, Deployment oder Google-Play-Upload ist ein separater
freizugebender Vorgang. Keine automatisierte Release-Pipeline ist auf GitHub aktiv.

## Test- und Sicherheitsstand

Am 01.10.2026 wurde der lokale JVM-Testlauf erneut ausgeführt: **630 Tests,
629 bestanden, 1 optionaler Live-Test übersprungen, keine Fehler**. Ergänzende
Offline-Tests und historische Emulatornachweise stehen in [TESTING](docs/TESTING.md).
Es gab in diesem Audit keine Geräteinstallation und keinen neuen Kamera-Endtest.

R8-Mapping und eine **partielle** Native-Symbol-ZIP sind Version 26 in Play
zugeordnet. Nur `libc++_shared.so` ist exakt symbolisch abgedeckt; sechs
Drittanbieterbibliotheken bleiben offen. App Check ist trotz transitiver
Bestandteile nicht als initialisierter/enforced App-Schutz nachgewiesen.
Weitere Grenzen: [Security](docs/security-entitlements.md).

## Nächste Schritte und Produktgrenzen

[Roadmap](docs/ROADMAP.md) · [Versionshistorie](docs/RELEASE_HISTORY.md) ·
[Änderungsübersicht](CHANGELOG.md) · [Dokumentationsindex](docs/README.md)

Prioritäten sind nachvollziehbare Quell-/Release-Stände, Scannerregressionen,
belegte Preiszuordnungen, Sync-/Offline-Tests und gezieltes Security-Hardening.
Ein eigenes DomiDex-Grading ist nicht implementiert, nicht aktiv in Entwicklung
und nicht für den nächsten Release vorgesehen. Verbliebene historische UI-Texte
sind kein Liefertermin.

Das langfristige Monetarisierungsmodell wird zur nachhaltigen Deckung laufender
Infrastruktur- und Dienstkosten neu bewertet. Es ist **kein neues Modell**
beschlossen. Das ist weder eine Abo-/Werbeankündigung noch eine Änderung früherer
Zusagen an Tester.

Website: [domidex.web.app](https://domidex.web.app/).
Community/Testerkommunikation: [DomiDex Discord](https://discord.gg/fc9XgwtVTE).
Testerzugang folgt dem bestehenden Freigabeprozess; ein Discord- oder Firebase-
Zugang ersetzt nicht die gesonderte Google-Play-Testberechtigung.
