# Repository-Audit – 2026-10-01

## Auftrag und Methode

Zuerst Bestandsaufnahme, anschließend ausschließlich Dokumentation und sichere
Repository-Hygiene. Keine Produktlogik, Regel, Authentifizierung, Function,
Dependency, Version oder Build-Konfiguration geändert. Keine APK/AAB, Signierung,
Geräteinstallation, neuen Tags/GitHub-Releases oder Deployments.

Geprüft: alle versionierten Pfade, aktuelle zusätzliche Source-/Testdateien,
Module/Source Sets/Ressourcen, Gradle, lokale Release- und Testnachweise,
Markdown-Dateien, TODO/FIXME-Suche, Git-Historie/Tags, Remote-Refs/GitHub-Releases/
Actions und Google Play Console **nur lesend**. Private Datenbanken/Fotos/
Anmeldedaten wurden nicht zu Audit-Fixtures gemacht oder veröffentlicht.

## Struktur und Hygiene

| Bereich | Einordnung |
| --- | --- |
| `app/src/main/` | Android-Produktcode, Manifest, Compose-Oberfläche, Ressourcen |
| `app/src/test/`, `app/src/androidTest/` | JVM- bzw. Instrumentationtests |
| `app/src/release/` | Lokale noch unversionierte Release-Keep-Regeln |
| `app/schemas/` | Versionierte Room-Schemas 1–12; 13 lokal zusätzlich |
| `gradle/`, Root-Gradledateien | Wrapper, Versionskatalog, Daemon-JVM-Anforderung |
| `functions/`, `firebase-tests/`, Rules | Knowledge-Aggregation und Policytests; produktive Regeln nicht geändert |
| `scripts/diagnostics/` | Katalog-/Preis-Snapshot- und Berichtswerkzeuge |
| `scripts/release/`, `scripts/scanner/` | Teils versionierte, teils lokale Release-/Diagnosehelfer |
| `docs/` | Aktuelle Leitdokumente plus datierte historische Berichte |
| `.gradle/`, `.kotlin/`, `.android*`, `build/`, `app/build/` | Lokale Tool-/Buildcaches; keine Quellen |
| `releases/`, `artifacts/` | Ignorierte Release-/Testbelege; aufbewahrt, nicht neu gebaut |
| `.codex-remote-attachments/`, `marketing/`, `output/`, `tmp/`, `.tmp-adb/` | Nutzeranhänge, Medien, Werk-/Geräteausgaben; nicht in Produktcommit übernehmen |
| `androidx/` | Lokale Hilfs-/Extraktionsstruktur, kein Gradle-Modul |

App-Ressourcen umfassen DomiDex-Launcher, Icons, Bindercover-/Holo-Atlanten,
deutsche Strings und Backup-Regeln. Keine eigenen C/C++-/JNI-Quellen oder
Native-Buildmodule gefunden. Weitere TCG-Enums bzw. historische Grading-Tabellen
belegen keine vollständigen produktiven Features.

Eine breite Bereinigung mit Löschungen wäre hier riskant: Nutzerbilder,
Signing-/Releasebelege und unversionierte Entwicklungsdateien bleiben erhalten.
`output/` wird lediglich als lokaler Medienexport ignoriert. Es wurden keine
Nutzerartefakte gelöscht. Keine neue Lizenz oder Open-Source-Freigabe erfunden.

## Abgleich: Dokumentation → Befund → Korrektur

| Frühere Aussage | Tatsächlicher Befund | Dokumentationsentscheidung |
| --- | --- | --- |
| Architektur: account-free, Netzwerkimporte erst später | Firebase/Google-Login, Cardmarket-Import und kontogetrennter Sync vorhanden | Aktive Architektur neu beschrieben; Gastmodus von Kontosync getrennt |
| Scanner: Auto-Capture standardmäßig an | `autoCaptureEnabled=false` | Standard aus, bewusstes Einschalten |
| Scanner: Latin/Japanisch/Chinesisch aktiv | Latin-Recognizer in Runtime; Enumwerte allein genügen nicht | Keine CJK-Erkennungszusage |
| Confidence/Queue erst künftig | Drei Entscheidungszustände, begrenzte Queue und Bestätigung vorhanden | Implementierten Ablauf dokumentiert |
| Fotos werden nie übertragen / ältere automatische Berichte | Drei getrennte Pfade: manueller ScanLab-Upload, lokaler Editor, Katalogbildbeitrag | Keine pauschale Offline-/Anonymitäts-/EXIF-Zusage |
| Knowledge „anonymisiert“ im alten GitHub-Release | Nutzerpfad und Hashbeiträge pseudonym, nicht anonym | Privacy-Grenze klargestellt; historischen Release nicht umgeschrieben |
| Grading pro Kartendetail / baldiger Web-Zustandscheck | Kein aktiver eigener Grading-Analyzer; alte Models/UI-Hinweise vorhanden | Nur Legacy-Gerüst; eigene Gradingidee langfristig, keine nächste Releasezusage |
| Preisquelle nur englische visuelle API / ausschließlich Trend | Aktuell TCGdex DE/EN und getrennte Cardmarket-Identität; lokale explizite Alternative nur ohne Trend | Quellen und Releasestatus getrennt erklärt |
| R8 aus / Signierung noch nicht eingerichtet | v17-Historie; v26-Mapping in AAB und Play; lokale R8-Konfiguration | Historische Berichte markieren, aktuelle Technik separat |
| Alle Native-Symbole verfügbar | Nur libc++ exakt verifiziert; sechs Herstellerdateien offen | Teilabdeckung statt Warnungsfreiheit |
| Firestore/Functions/Google noch inaktiv | Clients/Trigger vorhanden; aktueller Deploystatus nicht daraus ableitbar | Codebestand und Betrieb trennen |
| App Check nicht installiert | Transitive Bestandteile, kein integrierter Provider/Enforcement-Nachweis | Offenes Hardening statt absoluter Installationsaussage |
| Sharing am 22.09. noch gated off | Spätere lokale Sharing-/Readerpfade vorhanden | Historischen Gate-Bericht nicht als aktuellen Zustand verwenden |
| GitHub „Latest“ = aktuelles Release | Badge rc7; jüngster Prerelease Build17; Play26 | Datum, Tag, App-Version und Track getrennt rekonstruieren |

## Offene Quelltext-/Produktwidersprüche – bewusst nicht repariert

- `strings.xml` enthält weiterhin eine baldige Web-Zustandsprüfung; eigene
  Gradingfunktion ist nicht aktiv. Diese UI-Korrektur braucht einen separaten Auftrag.
- `CardVaultApp.kt` enthält bei der lokalen neuen Preisalternative noch eine
  Trend-only-Portfoliobeschreibung. Kein fachlicher Code-/Textfix in diesem Audit.
- `AppSupportConfig.kt` verwendet noch einen anderen Discord-Code als den vom
  Betreiber bestätigten dauerhaften Link. Dokumentation verwendet
  `https://discord.gg/fc9XgwtVTE`; den App-Link später separat abstimmen.
- Die TODO-/FIXME-Suche im geprüften App-/Functions-/Skriptquellcode lieferte keine
  entsprechend markierten offenen Stellen. Die oben beschriebenen Einschränkungen
  existieren dennoch; fehlende TODO-Kommentare sind kein Qualitätsbeweis.

## Sicherheit und Beleggrenzen

Gezielte Suche in versionierten Texten nach hochzuverlässigen Private-Key-/Token-
Mustern: keine Treffer. Unter versionierten Dateinamen keine echten Keystores,
Service-Account-Dateien, APK/AAB oder `google-services.json`; nur die
Signierkonfigurationsvorlage. Keine Schlüsselwerte ausgegeben oder verändert.
Das ist **kein** vollständiger History-/Dependency-/Penetrationstest.

Offene Themen sind konkret dokumentiert: App Check, Vendor-Symbole, Quellprovenienz,
Rücknahme/Löschung von Knowledge, Katalogbild-Metadaten, unverschlüsselte Backups,
Server-Retention/Quoten, Lizenz-/Marken-/Datenrechte und End-to-End-Freigaben.
Die Auditdateien stellen keine rechtliche oder allgemeine Sicherheitsfreigabe dar.

## Monetarisierung und frühere Zusagen

In der geprüften Android-Dokumentation und aktuellen Quelle wurde kein festes
9,99-Euro-Modell, beschlossenes Abo oder lebenslanges Gratisversprechen für Tester
gefunden. Das sagt nichts über frühere Zusagen außerhalb dieses Repositories.

**Erhaltene historische Aussagen, vom Betreiber bei weiterer Planung beachten:**

- Store-Textentwurf vom 04.09.2026: damals privat entwickelt und werbefrei.
- Derselbe Entwurf: interner Gratis-Test ist keine Zusage dauerhaft kostenloser
  späterer Nutzung.
- Internal-Test-Übergabe: datierter Hinweis auf kostenlosen Zugang interner Tester
  zu einer kostenpflichtigen App; keine Preisfestlegung.
- Signed-RC-Bericht: Paid/Free-Entscheidung beim Betreiber, keine dauerhafte
  Gratiszusage.

Keine solche Aussage wurde gelöscht oder als Zusage für ein neues Modell
umformuliert. Die heutige strategische Neubewertung ist weder neue Zahlungspflicht
noch Werbung-/Abo-/Credits-Beschluss.

## Git-Abgrenzung

Vor Audit: 57 geänderte versionierte Dateien, leerer Index und zahlreiche
unversionierte Dateien; HEAD `096aa35`, vier Code-Commits vor `origin/codex/ui-v3`.
Sie bleiben unangetastet. Für den Doku-Push wird ein sauberer Arbeitsbaum auf
`origin/master` verwendet; nur die überprüften Dokumentationsdateien und die
enge Ignore-Ergänzung werden übernommen. **Kein** `git add .`, kein Force-Push,
kein Reset, keine Tags/Release-Uploads.

Prüfung vor Abschluss: Markdown-Ziele, `git diff --check`, Dateiliste des Commits,
keine App-/Regel-/Buildänderung im Push und identischer Nicht-Dokumentations-
Dateibestand im ursprünglichen Arbeitsverzeichnis. Testergebnisse:
[TESTING](TESTING.md); zentrale Fakten: [PROJECT_STATUS](PROJECT_STATUS.md).
