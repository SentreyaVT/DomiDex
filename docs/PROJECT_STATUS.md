# DomiDex – technischer Projektstatus

Stand: **2026-10-01**, Europe/Berlin. Ausgangsbefunde des ursprünglichen
Dokumentationsaudits: lokaler Checkout, Git-Historie, GitHub und Google Play
Console lesend. Die anschließenden lokalen Änderungen und Teststände sind im
[Release-27-Ledger](RELEASE_27.md) maßgeblich dokumentiert; kein Gesamt-Gate-Pass
und keine produktive Veröffentlichung. Der Engineeringbranch heißt
`codex/release-27-hardening`; sein Ausgangscheckpoint ist `53b4e40d`.

## Unterschiedliche Projektstände

| Ebene | Befund vor diesem Dokumentations-Commit |
| --- | --- |
| GitHub Standardbranch | `master`, `9bbe7bc`, App `1.0-beta`, code 16 |
| GitHub Entwicklungsbranch | `codex/ui-v3`, `77b76cf`, App `1.0-pre-beta`, code 19 |
| Lokaler Commit | `codex/ui-v3`, `096aa35`, App `1.0-pre-beta.1`, code 21; vier Code-Commits vor Remote |
| Lokaler Working Tree | code **27**, `1.0-closed-alpha.6`; zahlreiche schon vorher geänderte und unversionierte Quellen/Tests |
| Google Play / signiertes Archiv | code **26**, `1.0-closed-alpha.5`; AAB vom 23.09.2026, unverändert |

Der damalige Doku-Push übernahm keine der vier noch lokalen Code-Commits und keine
unveröffentlichten Produktdateien. Der spätere Engineeringcheckpoint sichert den
lokalen Entwicklungsstand, nicht rückwirkend die Release-26-Quellen;
insbesondere lässt sich das v26-AAB keinem vollständigen sauberen Commit zuordnen.

## Version, Track und Release

- Package/Namespace: `com.dominik.cardvault`; ein Modul `:app`, keine Flavors.
- Lokales SDK: min 26, compile/target 37; Release nur `arm64-v8a`.
- Phase: **geschlossene Alpha**, kein belegter öffentlicher Produktionsrelease.
- **Play live am 01.10.2026:** Version 26 aktiv im „Geschlossener Test – Alpha“
  (verfügbar, vollständiger Roll-out, angezeigte letzte Änderung 23.09.2026)
  sowie im „Interner Test“ (verfügbar, vollständiger Roll-out, 24.09.2026).
- Die angezeigten Bundles 22–25 sind inaktiv. Kein Bundle 27 in der aktuellen
  Releaseübersicht. Keine Testerzahlen als Produkt- oder Freigabegarantie.
- Beim Auditbeginn jüngster GitHub-Release: `v1.0.0-pre-beta`, 01.09.2026,
  APK Build 17; Tag auf `1be7adc`. Der „Latest“-Badge zeigte damals auf rc7.
- Nachfolgend separat freigegeben und am 01.10.2026 ergänzt:
  [Release-26-Binärarchiv](https://github.com/nandedomi/DomiDex/releases/tag/archive/v26-1.0-closed-alpha.5),
  unverändertes AAB, Mapping, partielle Symbole und Prüfsummen. Der Archiv-Tag
  zeigt auf den Dokumentationscommit `53e7d14`, **nicht** auf rekonstruierte
  Buildquellen. Die vier Asset-Digests wurden nach dem Upload abgeglichen.
- Historische interne Builds sind als Archiv/Vorabversion gekennzeichnet;
  kein Alpha-/RC-Build wird für einen „Latest“-Badge zum stabilen Release erklärt.
- Der abgebrochene lokale v27-Signierlauf vom 25.09.2026 hat kein fertiges
  Archiv `releases/v27` erzeugt. Das vorhandene `app-release.aab` ist weiterhin v26.

## R8 und Native Debug Symbols

R8 ist im v26-Artefakt vorhanden; Play zeigt `mapping.txt` (104 MB) zugeordnet.
Aktuelle lokale Releasekonfiguration: Minify an, Code-Shrinking/Optimierung per
Keep-Regeln aus, Ressourcen-Shrinking aus. Diese Konfiguration war im damaligen
Audit-HEAD `096aa35` noch nicht vollständig enthalten und ist inzwischen im lokalen
Engineeringcheckpoint gesichert. Nicht auf den älteren GitHub-Checkout übertragen.

Play zeigt außerdem `native-debug-symbols.zip` (512 KB) bei **Version 26**.
Das lokal erneut verifizierte Archiv deckt **1 von 7** nativen Bibliotheken ab:
`libc++_shared.so`. Die sechs übrigen Herstellerbibliotheken liefern keine passenden
vollständigen Originalsymbole im vorliegenden Bestand. Keine künstlichen Symbole
und keine Behauptung „alle Warnungen weg“. [Details und Hashes](BUILD_AND_RELEASE.md)

## Funktionsstatus und bekannte Grenzen

| Bereich | Implementiert im geprüften lokalen Stand | Einschränkung / nächste Prüfung |
| --- | --- | --- |
| Scanner | CameraX, Latin-OCR, OpenCV, Bildvergleich, begrenzte Queue, drei Entscheidungszustände, manuelle Bestätigung/Korrektur | Ältere Karten/Promos, Glanz, Kaltstart/Netzwerk und echte Gerätelatenz weiter messen; keine generelle Geschwindigkeits-/Trefferquote bewiesen |
| Sammlung / Binder | Room, Varianten/Mengen, Set-Slots, private Seiten/Platzierungen/Cover/Holos, Backup V2 | Kein funktionales Nachtesten am Gerät in diesem Audit; manche Erweiterungen nur lokal |
| Teilen | Owner-Sync und Linkverwaltung, lokaler Android-Linkleser; externer Webdienst | Linkbesitz erlaubt Lesen; Backend-/App-Link-/Zwei-Geräte-Endtest nicht durch diese Dokumentation ersetzt |
| Preise | Exakte Cardmarket-ID, Druckfeld, Trend, Cache; neue explizite Alternative im lokalen Stand | Fehlende Quellbelege/Trendwerte bleiben; neue Alternative/Sync nicht als veröffentlicht behaupten |
| Portfolio | Mengen × verfügbare Werte, Historie heutiger Mengen | Keine Rendite-/Transaktionshistorie; lokaler UI-Text zu ausschließlich Trend noch widersprüchlich |
| Favoriten | Lokale Dashboard-Fokuskarte | Keine separate vollständige Favoritenfunktion nachgewiesen |
| Grading | Historische Models/Tabellen und nicht aktiv erreichbare Oberfläche | Kein eigenes Analyse-/Grading-System; UI-Web-Ankündigung ist kein bestätigtes nächstes Feature |

Preis-Snapshot vom **25.09.2026**, keine heutige Live-Vollprüfung: 144 physische
deutsche Index-Sets, 19.463 Karten einschließlich 20 lokaler Ergänzungen,
27.131 kanonische Binder-Druckplätze. **17.867** unabhängig bepreiste Plätze;
9.227 mit unzureichender Quellenverifikation, 1 ohne Mapping. Diese Kategorien
sind nicht identisch mit allen fehlenden Preisfeldern und nicht einfach
gegenseitig ergänzende Summanden. Vollständige Abdeckung ist nicht belegt.

## Cloud und Datenschutz

- Google/Firebase Authentication ist implementiert; Google-verknüpfte UID
  bestimmt den Sammlungs-Owner. Claim-/serverseitige Freigabe ist getrennt vom Login.
- Firestore: ownergebundene Daten, Revisions-/Mutationserkennung, Tombstones und
  Konfliktbehandlung; Room-Daten bleiben lokal lesbar.
- Collection verwendet Live-Listener plus Outbox; private Binder verwenden
  periodische/gezielte Läufe. **Kein garantiert verzögerungsfreier Sync**.
- Functions enthält einen Scan-Knowledge-Aggregationstrigger. Implementierung
  vorhanden, aktueller Deploy-/Quota-/Retention-Zustand in diesem Audit nicht geprüft.
- App Check: inzwischen lokal implementierte Debug-/Release-Provider und
  begrenzte REST-Tokenweitergabe, standardmäßig ausgeschaltet. Console-Enforcement
  nicht nachgewiesen; [Vertrag und offene Aktivierung](RELEASE_27_APP_CHECK.md).
- ScanLab-Fotos bleiben manuell pro Aufnahme bestätigt; bildfreies Knowledge hat
  separaten Opt-in. Katalogbildbeiträge sind ein dritter Pfad mit Originalbytes.
- ZIP-Backups sind **nicht verschlüsselt**. Androids Kontolöschdialog öffnet eine
  Anfrage-Mail; die separate Web-Löschfunktion ist kein Android-Client-Delete.

[Architektur](ARCHITECTURE.md) · [Firebase](../FIREBASE_SECURITY.md) ·
[Security / offene Prüfungen](security-entitlements.md)

## Tests, CI und offene Freigaben

Baseline des ursprünglichen Audits am 01.10.2026: JVM **629 bestanden / 1 optional übersprungen**, Node
**18/18**, native ELF-/Lizenzverifier **27/27**, lokale Firebase-Regeltests
**18/18**. Details und historische Emulatorläufe: [TESTING](TESTING.md).
Spätere lokale Tests und ihre jeweiligen Grenzen: [Release-27-Ledger](RELEASE_27.md).

Keine GitHub Actions konfiguriert; API meldet 0 Workflow-Runs. Aktuelle
Gradle-Dependency-Locks und Gradle-Verification-Metadaten fehlen; npm-Lockfiles
sind vorhanden. Ein Testpass ist weder eine
Produktionsfreigabe noch ein Beweis für alle Karten, alle Geräte oder alle Nutzer.

Offen bleiben insbesondere vollständige Quellcode-Provenienz, Regressionen mit
repräsentativem Bildkorpus/echten Geräten, Zwei-Geräte-/Revocation-Tests, fehlende
Herstellersymbole, Aufbewahrung/Löschung, Rechte an Assets/Daten und abgestimmte
Datenschutzhinweise. Der ursprüngliche Dokumentationsaudit erledigte diese Aufgaben
nicht durch Codeänderungen; spätere Teilkorrekturen und verbleibende Lücken stehen
im Ledger und sind kein pauschaler Abschluss dieser Prüfziele.

## Roadmap und Finanzierung

Version 27 bleibt ein **zukünftiger technischer Meilenstein**; lokale Konfiguration
27 bedeutet nicht veröffentlichte Version 27. [Roadmap](ROADMAP.md) enthält
Prüfziele, keine Terminzusagen.

Monetarisierung wird strategisch neu bewertet, ohne beschlossenes neues Modell.
Historische Aussagen zu Werbung/Testkosten bleiben als datierte Aussagen erhalten.
Es gibt durch diesen Audit keine neuen Kosten, Abos, Credits oder geänderte
Testerbedingungen. [Dokumentationsabgleich](REPOSITORY_AUDIT_2026-10-01.md)
