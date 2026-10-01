# Dokumentationsindex

Stand: 2026-10-01. **Für den laufenden Engineeringstand zuerst das Release-27-Ledger
lesen; der Projektstatus dokumentiert die Ausgangsbefunde.** Ältere Release-Berichte
sind keine aktuellen Freigaben. Lokaler Entwicklungscode und GitHub-Quellstand
sind unterschiedlich; die Texte kennzeichnen diese Grenze.

| Frage | Dokument |
| --- | --- |
| Was ist DomiDex? | [Haupt-README](../README.md) |
| Laufende Release-27-Arbeit, Nachweise und offene Gates | [Release-27-Ledger](RELEASE_27.md) |
| Ausgangsstand und Release-/Git-Zuordnung | [PROJECT_STATUS](PROJECT_STATUS.md) |
| Aufbau, Daten und Featuregrenzen | [Architektur](ARCHITECTURE.md) |
| Scanner, Editor, Knowledge, Offline | [Scanner](scanner-architecture.md) |
| Identität, Preise und Portfolio | [Preisarchitektur](cardmarket-import.md) |
| Cloud-Datenflüsse und Sicherheit | [Firebase](../FIREBASE_SECURITY.md), [Security](security-entitlements.md) |
| Support und Konto | [Support](support-architecture.md) |
| Toolchain, Dependencies, R8 und Symbole | [Build/Release](BUILD_AND_RELEASE.md) |
| Versionen, Tags, Play und GitHub | [Release-Historie](RELEASE_HISTORY.md) |
| Frische Tests vs. historische Belege | [Tests](TESTING.md) |
| Nächste Ziele / ausdrücklich nicht zugesagt | [Roadmap](ROADMAP.md) |
| Widersprüche / Hygiene / Prüfauftrag | [Audit](REPOSITORY_AUDIT_2026-10-01.md) |
| Zusammenfassung der Änderungen | [Changelog](../CHANGELOG.md) |

## Historische Berichte

Die datierten Dateien zu `GOOGLE_PLAY_*`, `PLAY_*`,
`SETTINGS_ACCOUNT_PRIVACY_2026-09-03.md`,
`CARD_DATA_PERFORMANCE_2026-09-03.md` und
`GRAND_UPDATE_RELEASE_GATE_2026-09-22.md` bleiben Nachweise ihres damaligen Laufs.
Sie sind keine aktuelle Anleitung für Signierung, Deployment, Datenschutz,
Preisstrategie oder Google-Play-Trackstatus. Ältere lokale Artefaktlinks sind
gegebenenfalls nur auf der ursprünglichen Arbeitsstation zugänglich.

Die beim ursprünglichen Audit noch unversionierten
Berichte `catalog-30th-*.md`, `scanner-v27-performance-20260924.md`,
`release27-price-sync-validation-20260925.md` und
`native-symbol-release-pipeline.md` sind inzwischen im lokalen Engineeringcheckpoint
gesichert. Ihre damaligen Ergebnisse bleiben historische Nachweise, keine aktuelle
Releasefreigabe. Die relevanten Befunde sind in den aktuellen Seiten zusammengefasst.

Die Web-App ist ein separates Repository. Diese Android-Dokumentation ist keine
Behauptung über seinen jeweils aktuellen Deployment-/Rechts-/Frontendstand.
