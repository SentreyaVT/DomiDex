# DomiDex

DomiDex hilft Sammlern, physische Pokémon-TCG-Karten zu erfassen, in digitalen
Set- und privaten Bindern zu organisieren und verfügbare Marktwerte zu überblicken.
Der Schwerpunkt liegt auf deutschsprachigen Sammlungen. Andere TCGs sind keine
nachgewiesen unterstützten Produktkataloge. Eine integrierte Tauschplattform und
ein eigenes Grading-System gehören nicht zum aktuellen Funktionsumfang.

## Projektstand: geschlossene Alpha

**Stand: 4. Oktober 2026.** Version **27 / `1.0-closed-alpha.6`** ist signiert und
im internen sowie geschlossenen Alpha-Test von Google Play verfügbar; Alpha-live
wurde am 04.10. um **21:54 Europe/Berlin** bestätigt. Das ist keine Veröffentlichung
im Produktions-Track und kein pauschales PASS aller technischen Gates.

| Stand | Nachweis |
| --- | --- |
| Android-Quellen | `codex/release-27-hardening`; remote bestätigter Sicherungscheckpoint `06190b2` |
| Signiertes Android-Bundle | Unverändertes `releases/v27/DomiDex-v27.aab`; Build-Quellcommit `a3c7aa7`, Hash und Provenienz in [Build/Release](docs/BUILD_AND_RELEASE.md) |
| Web/Backend | Separates Repository `DomiDex-Web`, Engineeringcheckpoint `77b37a1`; Hosting `334fdfd6961039a1`, Revision `domidex-web-r27-20261004d` live |
| Standardbranches | README-Einstiege per Docs-only-PR aktualisiert: Android `master` (`b2e41ee`), Web `main` (`eb75501`). Der R27-Produktcode wurde nicht dorthin gemergt. |

**Entwicklereinstieg:** Den Engineeringbranch ausdrücklich auswählen; ein normaler
Clone des Standardbranches zeigt weiterhin ältere Produktquellen; sein README
verweist jetzt auf den richtigen R27-Einstieg. Zuerst die
[Entwicklerübergabe R27](docs/DEVELOPER_HANDOFF_R27.md), dann
[Build/Release](docs/BUILD_AND_RELEASE.md) und das
[Release-27-Ledger](docs/RELEASE_27.md) lesen. Das Ledger trennt aktuelle Nachweise
von historischen Fehlversuchen und weiterhin offenen Gates.

## Releases und Downloads

Das bestehende R27-Bundle ist in Play als Testrelease und seit **04.10.2026,
22:47:21 Europe/Berlin** im
[GitHub-Binärarchiv R27](https://github.com/nandedomi/DomiDex/releases/tag/archive/v27-1.0-closed-alpha.6)
verfügbar. Das Archiv ist eine **Vorabversion, kein Entwurf** und verweist auf
Build-Quellcommit `a3c7aa7`. Die sechs Assets (AAB, Mapping, partielle Symbol-ZIP,
Prüfsummen, Release Notes und Toolchain-Hinweise) wurden mit passenden Hashes
hochgeladen; kein Neubau oder erneutes Signieren. Ein automatisches GitHub-Source-ZIP
beweist trotzdem keine bytegenaue Reproduzierbarkeit des AAB; private Konfiguration,
Toolchain und Signing gehören nicht vollständig zum Git-Checkout.

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

Die Auswahl einer geprüften Preisalternative **nur bei fehlendem Trendpreis** und
ihr Sync-Sidecar gehören zum R27-Stand. Full-Sync bleibt aktiv, Delta-Sync bleibt
deaktiviert. Vollständige Preis-, Hardware- oder Betriebsabdeckung wird dadurch
nicht behauptet.

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

Für einen neuen Checkout des gesicherten R27-Quellstands:

```powershell
git clone --branch codex/release-27-hardening https://github.com/nandedomi/DomiDex.git
cd DomiDex
```

1. Branch, konkreten Commit und [Entwicklerübergabe](docs/DEVELOPER_HANDOFF_R27.md)
   prüfen; `master` ist nicht der aktuelle R27-Quellstand.
2. Passendes Android SDK/JDK und Android Studio verwenden; `local.properties`
   und Firebase-Clientkonfiguration separat lokal bereitstellen.
3. Keine Upload-Schlüssel, Service-Account-Dateien oder Passwörter einchecken.
   Eine ignorierte `app/google-services.json` gehört nicht zu einem frischen Clone.
4. Für JVM-Tests und Debug-Lint: `./gradlew :app:testDebugUnitTest :app:lintDebug`
   bzw. `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug`. Das erzeugt keine APK/AAB.
   Geräte-/Emulatorprüfungen brauchen die jeweils dokumentierten isolierten
   Profile; sie sind kein automatischer Bestandteil dieses Befehls.

Ein Release-Build, Signierung, Deployment oder Google-Play-Upload ist ein separater
freizugebender Vorgang. Keine automatisierte Release-Pipeline ist auf GitHub aktiv.

## Test- und Sicherheitsstand

Aktueller R27-Nachweis: **1.063 JVM-Fälle, 1.062 PASS, ein unveränderter optionaler
Live-Skip**, keine Fehler; **371/371** in der dokumentierten Android-17-
Emulatorregression und **46/46** isolierte Datenbank-Fehlerfälle. Die signierte
Pipeline bestand 39 Prüfungen; Release-Lint hatte keine Fehler, aber 170 Warnungen
und vier Hinweise. Exakte Eingaben, Geräteprofile und Geltungsgrenzen stehen im
[Release-27-Ledger](docs/RELEASE_27.md), ältere Nachweise in [TESTING](docs/TESTING.md).

R8-Mapping und eine **partielle** Native-Symbol-ZIP sind Version 27 in Play
zugeordnet. Nur `libc++_shared.so` ist exakt symbolisch abgedeckt; sechs
Drittanbieterbibliotheken bleiben offen. App Check ist inzwischen im lokalen
Entwicklungsstand implementiert, aber standardmäßig **ausgeschaltet**; produktives
Enforcement ist nicht nachgewiesen. [Vertrag und Aktivierungsgrenzen](docs/RELEASE_27_APP_CHECK.md).
Weitere Grenzen: [Security](docs/security-entitlements.md).

**Bekannte offene Punkte:** Der ursprüngliche Lock-only-Listenernachweis bleibt
rot; separate erfolgreiche NO_ACCESS-/Löschworkflow-Tests ersetzen ihn nicht.
Hardware-/Mehrgeräte-, Last-/Betriebs- und Datenschutznachweise sind nicht pauschal
abgeschlossen. Zwei private Binderplätze sind noch nicht ursächlich geklärt.
Am 04.10.2026 um **20:32 UTC** trat in der live betriebenen Web-Sammlung beim
Scrollen zusätzlich **React-Fehler 185** auf; zum Stand dieser Dokumentation ist
er nicht behoben. Frühere Web-Test- und Deployment-PASS zählen nicht als Beleg
gegen diesen neu beobachteten Fehler.

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
