# Versionshistorie und Google-Play-Status

Stand **01.10.2026**. Rekonstruktion aus Git, tatsächlichen lokalen Artefakten,
historischen Prüfberichten und einer aktuellen **nur lesenden** Play-Console-Prüfung.
Der ursprüngliche Audit erzeugte keine Version, Tags, GitHub-Releases oder Play-Uploads.
Im anschließend separat freigegebenen GitHub-Aufräumauftrag wurde ausschließlich
das bestehende Release-26-AAB als Binärarchiv hinterlegt; siehe unten. Kein neuer
App-Build, keine neue Signierung und keine Änderung in Google Play.

## Aktuell belegbarer Stand

- **Projektphase: geschlossene Alpha.** Das ist nicht automatisch der Name eines Play-Tracks.
- **Google Play: Version 26 / `1.0-closed-alpha.5` aktiv in beiden Test-Tracks.**
  Geschlossener Test – Alpha: verfügbar/vollständiger Roll-out, zuletzt 23.09.2026 04:43.
  Interner Test: verfügbar/vollständiger Roll-out, zuletzt 24.09.2026 20:28.
  Zeitangaben wie in der Console angezeigt; keine daraus abgeleitete weitere Zeitzone.
- Versionen 25 (`1.0-pre-beta.5`), 24, 23 und 22 wurden als inaktiv angezeigt.
  Kein Version-27-Bundle war in der geprüften Ansicht sichtbar.
- Version 26 hat in Downloads eine `mapping.txt` (104 MB) und ein
  `native-debug-symbols.zip` (512 KB) zugeordnet. Native Symbolabdeckung bleibt **1/7**
  lokal verifiziert; das Warnungsende wurde nicht behauptet.
- Letztes vorhandenes signiertes lokales AAB: **26 / `1.0-closed-alpha.5`**.
- Lokale Gradle-Konfiguration: bereits **27 / `1.0-closed-alpha.6`**;
  Vorbereitung abgebrochen, kein abgeschlossener signierter Release 27.

Die vorher widersprüchlichen Trackangaben werden nicht rückwirkend umgeschrieben:
Die aktuelle Console bestätigt **beide** mit 26. Keine schnell veraltenden Testerzahlen.

## Git und veröffentlichter Quellstand

| Referenz am Auditbeginn | Tatsächliche Bedeutung |
|---|---|
| GitHub `master`, `9bbe7bc` | Standardbranch; Code 16 / `1.0-beta` |
| Remote-Tag `v1.0.0-pre-beta`, `1be7adc` | Jüngster belegter Tag nach Commitdatum; App `1.0-pre-beta` / 17; im ursprünglichen lokalen Tagbestand noch nicht vorhanden |
| Tag `v1.0.0-beta.1`, `9bbe7bc` | Vorgänger; Tagname ist nicht identisch mit Android-versionName `1.0-beta` |
| GitHub `codex/ui-v3`, `77b76cf` | Committed Build 19 / `1.0-pre-beta` |
| Lokaler `codex/ui-v3`, `096aa35` | Vier Produktcommits vor dem Remote-Branch; committed Konfiguration 21 |
| Lokaler Working Tree | Zahlreiche zusätzliche Änderungen/untracked Dateien, darunter Konfiguration 27 |

Beim Auditbeginn war der jüngste GitHub-Release `v1.0.0-pre-beta` vom
**01.09.2026**; das „Latest“-Label zeigte damals auf `v1.0.5-rc7`.
Die anschließende Bereinigung kennzeichnet alle historischen internen Builds als
Archiv/Vorabversion. Label, Tag, Android-Versionscode und Play-Status dürfen nicht
gleichgesetzt werden. Die Dokumentationsaktualisierung übernimmt keine vier
lokalen Produktcommits und stellt Release 26 nicht als aus Git exakt reproduzierbar dar.

### GitHub-Binärarchiv 26 – Nachtrag vom 01.10.2026

[DomiDex 26 · Closed Alpha · signiertes AAB (Archiv)](https://github.com/nandedomi/DomiDex/releases/tag/archive/v26-1.0-closed-alpha.5)
enthält die unveränderten Originale: AAB, passendes Mapping und partielle
Native-Symbol-ZIP sowie `SHA256SUMS.txt`. Alle vier GitHub-Asset-Digests stimmen
mit den hochgeladenen Dateien überein. Das AAB bleibt ein Play-Bundle, keine APK.

`archive/v26-1.0-closed-alpha.5` ist ein Archiv-Tag auf Dokumentationscommit
`53e7d142a8349e36ed7ca85fc7ea40315fffee14`. Die automatischen GitHub-Source-code-
Downloads sind **nicht** der exakt zugehörige v26-Quellstand. Der Eintrag ist eine
Vorabversion, keine neue stabile Veröffentlichung. Bestehende 14 Releases,
ihre Tags, Downloads und Veröffentlichungsdaten bleiben erhalten; Titel,
historische Hinweise und fehlerhafte Zeilenumbrüche wurden aufgeräumt.
Die AAB-Archive 18–25 wurden dabei nicht zusätzlich hochgeladen; 27 bleibt unfertig.

## Rekonstruierte Entwicklung

| Versionscode | Android-versionName | Beleg / Einordnung |
|---:|---|---|
| 1–5 | `1.0`, `1.0.1` bis `1.0.4` | Git-Verlauf 16.–30.08.; lokale Grundlage, Marke, Updateeinstieg, Scanner/Preise |
| 6–12 | `1.0.5-rc1` bis `1.0.5-rc7` | Git/Tags 30.–31.08.; Feedback, Binderpreise, Scan-Verarbeitung |
| 13–15 | `1.0.6-rc1` bis `1.0.6-rc3` | Git/Tags 31.08.; Scanner-Engine, Gerätestabilität, Katalogabdeckung |
| 16 | `1.0-beta` | `9bbe7bc`, 31.08.; Tag `v1.0.0-beta.1` |
| 17 | `1.0-pre-beta` | `1be7adc`, 01.09.; signiertes Internal-Test-Bundle vom 04.09. vorhanden |
| 18 | `1.0-pre-beta` | `920decd`, 06.09.; Binder-/Produktidentität und Preiszuordnung |
| 19 | `1.0-pre-beta` | `77b76cf`, 12.09.; private Binder, Holo-UI, Analysesessions |
| 20 | `1.0-pre-beta` | `30a179c`, 13.09.; Kontonavigation/An- und Abmeldung |
| 21 | `1.0-pre-beta.1` | Mini-Set-AAB vom 21.09.; Konfiguration später in `b28c16c` committed |
| 22 | `1.0-pre-beta.2` | Signiertes lokales AAB 22.09.; aktueller Design-/Integrationsstand |
| 23 | `1.0-pre-beta.3` | Signiertes lokales AAB 22.09.; Holo-Materialien, RGB-Mew, nummerierte Jubiläumspromos |
| 24 | `1.0-pre-beta.4` | Signiertes lokales AAB 22.09.; zusätzliche belegte Jubiläums-Produktzuordnungen |
| 25 | `1.0-pre-beta.5` | Signiertes lokales AAB 23.09.; SAR-/Preiszuordnung und priorisierte Sync-Arbeit |
| 25, verworfener Ersatz | `1.0-closed-alpha.5` | Separater R8-Vorbereitungsbuild 23.09.; wegen bereits verwendetem Code ausdrücklich überholt |
| 26 | `1.0-closed-alpha.5` | Signiertes AAB 23.09.; konservatives R8, danach passende libc++-Originalsymbole ergänzt |
| 27, unvollendet | `1.0-closed-alpha.6` | Lokale Vorbereitung 25.09.; nicht abgeschlossen/signiert/hochgeladen |

Datei- oder Commitdatum ist kein Beweis des damaligen Play-Roll-outs. Lokale
Release-Unterlagen belegen die Vorbereitung; aktueller Play-Status steht separat oben.
Die zwei unterschiedlichen lokalen Code-25-AABs niemals als austauschbar behandeln.

## Artefakte und Nachweisgrenzen

Die lokalen Archive liegen unter `releases/` und sind bewusst Git-ignoriert:
`google-play-internal-2026-09-04` bis `google-play-internal-2026-09-21`, danach
`google-play-v22-2026-09-22` bis `google-play-v26-closed-alpha-2026-09-23`.
Sie sind keine Git-Quelldateien. Release 26 ist inzwischen zusätzlich im oben
genannten GitHub-Binärarchiv verfügbar; die übrigen lokalen AABs nicht pauschal.

Version-26-Hashes und native Zuordnung stehen in [Build und Release](BUILD_AND_RELEASE.md).
Die aktuelle Build-Ausgabe `app/build/outputs/bundle/release/app-release.aab` ist
weiterhin bytegleich zum archivierten v26-AAB. Das vorhandene v27-Log unter
`artifacts/release27-development/signed-release-build.log` endet vor erfolgreichem
Bundle-/Archivabschluss; es gibt kein fertiges `releases/v27`-Archiv. Keine
Rückschlüsse aus bloßem `validateSigningRelease UP-TO-DATE` auf ein signiertes v27 ziehen.

## Historische Unterlagen richtig lesen

Die datierten Play-Dokumente zu Build 17 und die Release Notes 18–20 bleiben
historische Belege. Aussagen wie „Pre-Beta“, „R8 deaktiviert“, „Signierung fehlt“
oder „öffentliche Kontolöschseite fehlt“ gelten nicht ungeprüft heute.
Der Grand-Update-Gatebericht vom 22.09. beschreibt einen damaligen Zwischenstand,
nicht die jetzige Vollständigkeit von Sharing, Datenbank oder Berechtigungen.

Frühere Texte beschreiben das damalige Projekt als werbefrei und den internen Test
als kostenlos, ausdrücklich ohne dauerhafte Gratiszusage. Die langfristige
Monetarisierung wird angesichts laufender Kosten neu bewertet; kein neues Modell,
Preis, Abo, Werbe- oder Testerzahlungsversprechen wird hier festgelegt.

## Nächster Entwicklungszyklus: kein Release durch diese Aufgabe

Release 27 bleibt separat abzunehmen; lokale Arbeiten sind keine Veröffentlichung
oder allgemeine Freigabe. Scanner/Performance, Preise und Sync benötigen gezielte
Regressionen sowie reale Kamera-/Thermal-/Lichttests nach Emulatorprüfungen.
R8 und passende Originalsymbole bleiben Prüfpunkte, ohne für die sechs fehlenden
Herstellersymbole eine Lösung vorzutäuschen.
