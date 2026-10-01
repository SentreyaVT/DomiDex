# Änderungsübersicht

Diese Übersicht unterscheidet Dokumentation, lokale Entwicklung und tatsächlich
belegte Releases. Sie ersetzt weder Git-Historie noch Google Play Console.

## 2026-10-01 – Repository-/Dokumentationsaudit

- README, zentraler Projektstatus, Architektur, Scanner-/Cloud-/Preisgrenzen,
  Versionshistorie, Tests, Security und Roadmap am tatsächlichen Stand ausgerichtet.
- GitHub-, lokalen Commit-, Working-Tree- und signierten Play-Stand getrennt.
- Version 26 in geschlossenem Alpha- und internem Test lesend bestätigt;
  R8-Mapping und partielle Native-Symbole zugeordnet.
- Historische Berichte datiert erhalten; alte Aussagen nicht als aktuelle
  Freigaben oder Tester-/Geschäftsmodellversprechen weitergetragen.
- **Keine App-, Regel-, Dependency-, Build- oder Versionsänderung; keine APK/AAB,
  Signierung, neue GitHub-Veröffentlichung oder Google-Play-Aktion.**

## Unreleased – lokaler Entwicklungsstand für 27

Bereits vor diesem Audit vorhanden, teils unversioniert: Scanner-Lifecycle-/OCR-
Vorbereitungsoptimierungen, überprüfte Knowledge-Hinweise, zusätzliche genaue
Katalog-/Preiszuordnungen, explizite Alternativen bei fehlendem Trendpreis mit
Sync-Sidecar, Sharing-/Darstellungsarbeiten und zugehörige Tests.

Das ist **keine Implementierungs- oder Freigabeerklärung dieses Doku-Commits**.
Die Änderungen müssen separat als vollständiger Quellstand geprüft werden.
Die lokale Versionskonfiguration 27 wurde nicht verändert; kein fertiges
signiertes v27-AAB nachgewiesen.

## 26 – `1.0-closed-alpha.5`

Signiertes AAB vom 23.09.2026; aktiver Alpha-/Internal-Stand am 01.10.2026.
R8-Zuordnungsdatei vorhanden. Native-Symbolarchiv partiell: libc++ exakt
verifiziert, sechs Herstellerbibliotheken ohne verfügbare passende Originalsymbole.
Eine vollständige Zuordnung aller enthaltenen Funktionsänderungen zu einem
sauberen Git-Commit ist nicht vorhanden.

## Frühere Stände

Build 17: Pre-Beta; 18: Binder-/Preisidentität; 19: private Binder/Holo-/Scanner-
Sessions; 20: Konto-Dialog; 21–25: weitere lokale Update-Artefakte.
Die Tag-Namen und Play-Versionscodes sind nicht durchgehend dasselbe Schema.
[Vollständiger rekonstruierter Verlauf und Beleggrenzen](docs/RELEASE_HISTORY.md)
