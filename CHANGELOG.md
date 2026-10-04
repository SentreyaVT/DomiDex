# Änderungsübersicht

Diese Übersicht unterscheidet Dokumentation, lokale Entwicklung und tatsächlich
belegte Releases. Sie ersetzt weder Git-Historie noch Google Play Console.

## 27 – `1.0-closed-alpha.6` – Testrelease vom 04.10.2026

- Unverändertes signiertes AAB im internen und geschlossenen Alpha-Test verfügbar;
  Alpha-live am 04.10. um 21:54 Europe/Berlin bestätigt. Kein Produktions-Track.
- Android-Engineeringstand auf `codex/release-27-hardening` gesichert und remote
  bestätigt (`06190b2` vor dieser Dokumentationsfortschreibung); Standardbranch
  `master` behält ältere Produktquellen. Die README-Portale wurden per Docs-only-
  PR aktualisiert (Android `b2e41ee`, Web `eb75501`), ohne R27-Code-Merge.
  Build-Quellcommit des AAB: `a3c7aa7`.
- Sammlungspreise/Portfolio, begrenzter Preisverlauf und Bildcache, Scanner-
  Sessions, bekannte Zugriffssperren und Import-/Speicherfehlerbehandlung gezielt
  gehärtet. Full-Sync bleibt aktiv, Delta bleibt aus. Kein vollständiger
  Historien-Restore oder universeller Hardware-/Lastnachweis wird versprochen.
- 1.063 JVM-Fälle: 1.062 PASS, ein unveränderter optionaler Live-Skip;
  Android-17-Emulator 371/371 und isolierte Datenbankfälle 46/46 PASS.
  39 Release-Pipelineprüfungen PASS; Release-Lint ohne Fehler, mit Warnungen/Hinweisen.
- Echtes eingebettetes R8-Mapping und passende partielle Native-Symbole in Play
  zugeordnet. Abdeckung weiterhin **1/7**, sechs Herstelleroriginale fehlen.
- Separates Web/Backend (`77b37a1`) unter Hosting `334fdfd6961039a1` und Revision
  `domidex-web-r27-20261004d` veröffentlicht; Regeln bytegleich zurückgelesen.
  Neu am 04.10. um 20:32 UTC: React-Fehler 185 beim Scrollen der live betriebenen
  Web-Sammlung, noch nicht behoben. Der frühere Deployment-PASS wird nicht umgedeutet.
- [GitHub-Binärarchiv R27](https://github.com/nandedomi/DomiDex/releases/tag/archive/v27-1.0-closed-alpha.6)
  am 04.10. um 22:47:21 Europe/Berlin als Vorabversion veröffentlicht, kein Entwurf;
  Zielcommit `a3c7aa7`. Sechs vorhandene Assets hochgeladen, sämtliche Hashes
  abgeglichen. Kein Neubau, erneutes Signieren oder weiterer Play-Upload.
  Kein bytegenaues Reproduzierbarkeitsversprechen aus dem Source-ZIP ableiten.

Dies ist **kein Gesamt-Gate-PASS**. Ursprünglicher Lock-only-Listener-RED,
Hardware-/Mehrgeräte-/Betriebsgrenzen, Datenschutzrestpunkte, unvollständige Preise
und zwei noch ungeklärte Binderplätze bleiben im [Ledger](docs/RELEASE_27.md)
dokumentiert. Einstieg: [Entwicklerübergabe](docs/DEVELOPER_HANDOFF_R27.md).

## 2026-10-01 – Repository-/Dokumentationsaudit

Der folgende Audit war vom anschließend erteilten GitHub-Aufräumauftrag getrennt.

### Anschließend: GitHub-Darstellung und vorhandenes Releasearchiv

- About-Beschreibung, Website und passende Repository-Themen ergänzt.
- 14 historische interne Releases als Archiv/Vorabversion gekennzeichnet;
  ursprüngliche Inhalte, Tags und Downloads erhalten, kaputte Zeilenumbrüche korrigiert.
- Original-Release 26 mit AAB, Mapping, partieller Symbol-ZIP und Prüfsummen als
  GitHub-Binärarchiv hinterlegt. Hochgeladene Dateihashes verifiziert.
- Der neue Archiv-Tag bildet keinen nachträglich erfundenen v26-Quellstand ab.
- Keine App-/Buildänderung, kein Neubau, keine Signierung, kein Play-Upload.

### Ursprüngliche Auditänderungen

- README, zentraler Projektstatus, Architektur, Scanner-/Cloud-/Preisgrenzen,
  Versionshistorie, Tests, Security und Roadmap am tatsächlichen Stand ausgerichtet.
- GitHub-, lokalen Commit-, Working-Tree- und signierten Play-Stand getrennt.
- Version 26 in geschlossenem Alpha- und internem Test lesend bestätigt;
  R8-Mapping und partielle Native-Symbole zugeordnet.
- Historische Berichte datiert erhalten; alte Aussagen nicht als aktuelle
  Freigaben oder Tester-/Geschäftsmodellversprechen weitergetragen.
- **Keine App-, Regel-, Dependency-, Build- oder Versionsänderung; keine APK/AAB,
  Signierung, neue GitHub-Veröffentlichung oder Google-Play-Aktion.**

## Historischer R27-Zwischenstand – 01.–02.10.2026, vor Signierung

**Anschließender Engineeringlauf:** Der Ausgangsstand wurde lokal gesichert;
gezielte Sicherheits-, Sync-, Preis-, Scanner- und Backup-Korrekturen wurden auf
`codex/release-27-hardening` geprüft. Damals gab es noch kein neues signiertes AAB
und keine Veröffentlichung. Der abgeschlossene spätere Testrelease steht oben;
seine verbleibenden Grenzen stehen im [Release-27-Ledger](docs/RELEASE_27.md).

Gezielter lokaler Performance-Fix: Große behaltene Preisverläufe werden ohne
Datenverlust indexgerecht gelesen und außerhalb des UI-Threads chronologisch
geordnet. Im unveränderten Jahresdaten-Test sinken die warmen Portfolio-/Binder-
Ladezeiten von rund 16,1 auf 2,6–2,7 Sekunden. Das ist ein Debug-Emulator-Nachweis,
kein versprochenes Handy- oder Netzwerktiming. Full-Sync bleibt erhalten;
Delta-Sync ist ausdrücklich nicht Teil von R27. Zusätzliche echte lokale
SDK-/Knowledge-Prüfungen und ihre verbleibenden Grenzen stehen im Ledger.

Bereits vor dem ursprünglichen Audit vorhanden, damals teils unversioniert: Scanner-Lifecycle-/OCR-
Vorbereitungsoptimierungen, überprüfte Knowledge-Hinweise, zusätzliche genaue
Katalog-/Preiszuordnungen, explizite Alternativen bei fehlendem Trendpreis mit
Sync-Sidecar, Sharing-/Darstellungsarbeiten und zugehörige Tests.

Die damalige Bestandsaufnahme war **keine Implementierungs- oder Freigabeerklärung**.
Die spätere Prüfung des gesicherten Quellstands ist im Ledger dokumentiert.
Die lokale Versionskonfiguration 27 wurde bei dieser frühen Prüfung nicht
verändert; damals war noch kein fertiges signiertes v27-AAB nachgewiesen.

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
