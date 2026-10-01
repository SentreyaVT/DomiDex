# DomiDex – Firebase-Sicherheitsgrenzen und Synchronisation

Prüfstand: 1. Oktober 2026. Diese Bestandsaufnahme beschreibt vorhandenen lokalen Code. Die frühere pauschale Kennzeichnung „nicht deployed“ ist überholt: Firebase-Funktionen werden von DomiDex verwendet. Welche konkrete Regel-/Backend-Version live aktiv ist, muss jedoch unabhängig vom lokalen Git-Zustand belegt werden.

Der Dokumentationslauf verändert keine Regeln, Cloud-Daten, Konten, IAM-Rechte, Deployments oder App-Builds.

## Komponenten und Autorität

Android verwendet Firebase Authentication, Firestore und Storage. Room liefert die lokale/offline Datenbasis der Oberfläche. Die Funktionen unter `functions/` aggregieren Community-Scannerwissen. Weitere produktive HTTP-Abläufe, insbesondere ScanLab, Testerrechte, Freigaben und Kontolöschung, liegen im separaten **DomiDex-Web**-Projekt.

Der kanonische Collection-Owner ist eine mit Google verknüpfte Firebase-UID. `admin`, `appAccess` und `pretester` werden als serverseitige Claims gelesen; ein Client stellt sie nicht aus. `accountAccess` und `accountDeletionLocks` schränken gültige Claims zusätzlich ein.

## Regelpfade

| Pfad | Wesentliche Grenze im lokalen Regelstand |
| --- | --- |
| Firestore `users/{uid}/collectionEntries/{syncId}` | Owner/Entitlement, erlaubtes Schema, Mutation/Revisionsprüfung; Löschungen als Tombstone |
| private Binder, Platzierungen und Layouts unter `users/{uid}` | ownergebundene, validierte Dokumente mit Konflikt-/Revisionsschutz |
| `users/{uid}/collectionPriceSelections/{entrySyncId}` | lokale Release-27-Erweiterung; exakte Parent-/Karten-/Produkt-/Variantenidentität, Kennzahl und Wert validiert |
| `users/{uid}/scanEvidence/{fingerprint}` und `scanKnowledge/{fingerprint}` | persönliche Evidenz bzw. eng begrenzte Einzelabfrage; globale Aggregat-Schreibzugriffe nur serverseitig |
| `scannerReports` | keine direkten Client-Uploads/-Löschungen; lokaler direkter Review-/Lesezugriff administrativ begrenzt |
| `supportRequests` | kleine requestergebundene Metadaten, keine vollständige Inbox/Fernsteuerung |
| `siteContent/public` | öffentliche Produkttexte lesbar, Schreibzugriff administrativ und schema-begrenzt |
| Storage `users/{uid}/...` | private Bilder für passenden Owner mit App-Zugriff |
| `catalog-contributions/{uid}/{sha256}/card.{ext}` | ownergebundener, create-once Bildbeitrag, Owner kann lesen/zurückziehen |
| `community-submissions/{uid}/{submissionId}/front.jpg` | Upload erfordert vorhandenen passenden Firestore-Metadatensatz |
| `scanner-report-images/{reportId}/{fileName}` | alle direkten Client-Reads/-Writes verweigert, auch für Admin; Bildzugriff über authentifizierte Serverroute |
| übrige Pfade | Default-Deny |

Maßgeblich sind `firestore.rules` und `storage.rules`, nicht eine UI-Rollenanzeige. Eine separate Web-Scanner-Lab-Berechtigung ist am Web-Backend zu beurteilen; sie macht einen Nutzer nicht automatisch zum allgemeinen Firebase-Admin.

## Sammlungssync und Offlineverhalten

Die Implementierung unter `app/src/main/java/com/dominik/cardvault/data/sync/` verwendet dauerhafte ownergebundene Outboxes. Fachliche lokale Änderung und Mutation werden gemeinsam in Room gespeichert.

- Stabile Quellenidentitäten, Mutation-IDs und erwartete Revisionen verhindern blindes Überschreiben.
- Pending-/Konfliktänderungen werden nicht durch eingehende Snapshots stillschweigend verworfen.
- Tombstones verhindern eine einfache Wiederherstellung gelöschter Daten durch ältere Offline-Stände.
- Authwechsel wechselt den Owner-Namespace und Listener; Gastdaten bleiben isoliert.
- Lokale/Cloud-Konfliktauflösung und Wiederholung korrigierter terminaler Fehler sind explizite Aktionen.
- Collection-ACKs werden mit einem frischen Serverstand abgeglichen.
- Gastübernahme ist ausdrücklich bestätigt und serverseitig verifiziert; sie ist keine automatische Folge einer Anmeldung.
- Vollständig manuelle, nicht verknüpfte Karten können bei unabhängiger Anlage auf zwei Offline-Geräten getrennte Einträge bleiben.

Collection besitzt einen Live-Listener. Private Binder verwenden auch begrenzte Abrufe und periodische Arbeit im 15-Minuten-Raster. „Alles ohne Delay“ ist damit keine belegte Eigenschaft.

Die lokale Preisalternativen-Erweiterung nutzt separate `collectionPriceSelections` und Room-Schema 13; HEAD enthält Schema 12. Sie ist von einer neuen Veröffentlichung zu unterscheiden. Vorhandene Firebase-Regeltests decken nicht automatisch alle neuen Sidecar-Grenzfälle ab.

## Drei getrennte Beitrags-/Bildpfade

### Manuelle ScanLab-Berichte

`data/feedback/FirebaseScannerReportUploader.kt` sendet nach ausdrücklicher Bestätigung an die Web-API. Es gibt in diesem Pfad keinen automatischen Hintergrund-Retry. Auth und Kontoidentität werden geprüft.

Die Bildvorbereitung dekodiert und kodiert ein begrenztes JPEG neu; dadurch werden ursprüngliche EXIF-/GPS-Metadaten nicht übernommen. Im Foto **sichtbare** Namen, Personen oder private Inhalte werden dadurch nicht entfernt. Ein historischer Preference-Schlüssel mit `automatic_v1` ändert den manuellen Uploadablauf nicht.

### Gemeinsames Scannerwissen

Die Einwilligung ist separat, versioniert und standardmäßig ausgeschaltet. Übertragen werden technische OCR-/Bildmerkmals-Hinweise, Kandidaten und bestätigte Korrekturen, keine Fotos. Die ownergebundene Queue prüft die Zustimmung vor dem Upload erneut.

`functions/index.js` enthält `aggregateScanKnowledge`; `functions/knowledge-aggregate.js` berechnet Zustände aus Beiträgen. Mindestens drei unabhängige Bestätigungen und ein Abstand von zwei Stimmen sind für den beschriebenen stabilen Zustand nötig. Das ist Evidenzaggregation, kein nachgewiesenes automatisches ML-Modelltraining.

Ausschalten verhindert neue Beiträge, entfernt aber nicht automatisch schon übertragene Evidenz. Der gelesene Android-Cache besitzt zudem keinen allgemeinen TTL-Widerruf: Ein fehlendes Remote-Dokument entfernt vorhandene lokale Hinweise nicht zwingend. Sofortige globale Rücknahme ist nicht zugesichert.

### Optionale Katalogbilder

`data/storage/DomiDexStorageGateway.kt` prüft Bildtyp, Dateigröße, Header/Dimensionen und SHA-256 und verlangt einen expliziten Beitragsschritt. Der gelesene Pfad lädt jedoch Originaldateibytes hoch; hier ist keine gleiche EXIF-Entfernung wie beim ScanLab-JPEG implementiert.

Storage-Regeln erlauben JPEG/PNG/WebP und maximal 10 MiB. Deklarierter MIME-Typ und Clientprüfung ersetzen keine serverseitige Inhaltsprüfung. Ein privater Beitrag wird nicht automatisch ein freigegebenes öffentliches Katalogbild.

Serverseitige Moderation, echte Bytevalidierung, Metadatenbehandlung, Quoten sowie Aufbewahrung/Löschung müssen für diesen Pfad gesondert nachgewiesen werden. Admin SDK umgeht Clientregeln.

## Freigabelinks, Backup und Löschung

Private Binderlinks werden im Web-Backend erzeugt und widerrufen. Der gelesene Vertrag ist tokenbasiert öffentlich lesbar, nicht auf bereits freigeschaltete Empfängerkonten beschränkt. Das Backend prüft den Freigabestatus und den weiter gültigen Besitzerzugriff. Linktokens sind vertraulich.

Das Android-ZIP-Backup ist nicht verschlüsselt und kann personenbezogene Besitzdaten enthalten. Es exportiert keine Auth-/Claim-Berechtigung oder Einwilligung. Android-Systembackup ist deaktiviert; das ist nicht dasselbe wie Verschlüsselung eines manuell exportierten Backups.

Der Android-Kontolöschdialog bereitet eine E-Mail vor, löscht selbst nichts. Der gesonderte Web-Löschendpunkt und sein serverseitiger Ablauf sind außerhalb dieses Repositories implementiert. Weder ein E-Mail-Entwurf noch ein Rules-Test beweist eine abgeschlossene Löschung.

## App Check und offene Betriebsnachweise

`firebase-appcheck:19.4.0` und `firebase-appcheck-interop:17.1.0` sind im lokal ausgewerteten Abhängigkeitsbaum transitiv vorhanden. Ein aktiver Android-App-Check-Provider/Initialisierung wurde nicht gefunden. Firebase-Console-Enforcement ist damit **nicht nachgewiesen**. Eine Provider-Einrichtung oder Enforcement-Umschaltung gehört nicht zu diesem Audit.

Weitere separat zu prüfende Punkte:

- tatsächlich deployte Regel-/Functions-/Web-Versionen;
- serverseitige Ratenbegrenzung, Quoten und Überwachung pro Endpoint;
- Reihenfolge/Konfliktbehandlung von clientzeitgestützter Knowledge-Evidenz;
- Widerruf lokal gecachter Knowledge-Daten;
- echte Metadaten-/Inhaltsprüfung optionaler Katalogbild-Beiträge;
- Wiederherstellungs-/Löschtests über alle betroffenen Dienste.

Kein unbedingtes „manipulationssicher“, „vollständig anonym“ oder „rechtlich zertifiziert“ ableiten.

## Lokale Prüfung ohne Produktionszugriff

Reine Aggregatlogik kann ohne Firebase-Verbindung ausgeführt werden:

```powershell
node --test functions/knowledge-aggregate.test.js
```

`firebase-tests/firestore.rules.test.mjs` und `storage.rules.test.mjs` benötigen lokale Firestore-/Storage-Emulatoren und einen ausdrücklich gewählten `demo-...`-Projektnamen. Ihre Setup-Hooks leeren Testdaten. Sie dürfen daher nicht als unkontrollierter Test gegen ein echtes Projekt gestartet werden.

Eine sichere Testhülle muss lokale Emulator-Hosts/Ports, isolierte Konfiguration, serielle Ausführung und passende Test-Dependencies festlegen. Die aktuelle Audit-Testdokumentation hält ausgeführte Befehle und Ergebnisse fest. Historische grüne Logs sind keine heute neu ausgeführten Tests.

Dieses Dokument enthält bewusst keinen Deploy-Befehl. Eine Veröffentlichung von Regeln oder Functions erfordert einen gesonderten, freigegebenen Änderungsauftrag.
