# Dokumentationsindex

Stand: **2026-10-04**. **Für neue Entwickler zuerst die R27-Übergabe lesen.** Der
Engineeringbranch `codex/release-27-hardening` ist auf GitHub gesichert; der
Standardbranch `master` hat jetzt einen aktualisierten README-Einstieg, aber
weiterhin ältere Produktquellen: Der Docs-only-PR ist kein R27-Code-Merge.
Android 27 ist im internen
und geschlossenen Alpha-Test verfügbar, das zugehörige Web/Backend ist deployed.
Diese Veröffentlichung bedeutet nicht, dass alle technischen Gates bestanden sind.
Aktuelle Nachweise und bekannte Fehler stehen oben im Ledger; datierte ältere
Abschnitte bleiben historische Belege. Das
[R27-GitHub-Binärarchiv](https://github.com/nandedomi/DomiDex/releases/tag/archive/v27-1.0-closed-alpha.6)
ist seit 04.10. um 22:47:21 Europe/Berlin als Vorabversion veröffentlicht;
sechs vorhandene Assets sind hashverifiziert, kein Neubau.

| Frage | Dokument |
| --- | --- |
| Was ist DomiDex? | [Haupt-README](../README.md) |
| Welcher Branch, wie lokal entwickeln und was bleibt offen? | [Entwicklerübergabe R27](DEVELOPER_HANDOFF_R27.md) |
| Laufende Release-27-Arbeit, Nachweise und offene Gates | [Release-27-Ledger](RELEASE_27.md) |
| Aktueller Status, Ausgangsbefunde und Release-/Git-Zuordnung | [PROJECT_STATUS](PROJECT_STATUS.md) |
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
gesichert und über den Engineeringbranch verfügbar. Ihre damaligen Ergebnisse
bleiben historische Nachweise, keine aktuelle Gesamtfreigabe. Die relevanten
Befunde sind in den aktuellen Seiten zusammengefasst.

Die Web-App ist ein separates Repository. Diese Android-Dokumentation ist keine
Behauptung über seinen jeweils aktuellen Deployment-/Rechts-/Frontendstand.
