# DomiDex – Support, Datenschutzkontakt und Kontolöschung

Prüfstand: 1. Oktober 2026.

## Tatsächlich vorhandene Kontaktwege

Die normale Android-Supportstelle ist **konfiguriert**, nicht deaktiviert. `ui/AppSupportConfig.kt` enthält den bestätigten öffentlichen Kontakt und die öffentliche Datenschutzerklärungs-URL. `ui/SettingsPrivacy.kt` öffnet einen Android-`ACTION_SENDTO`-Entwurf.

Der Nutzer prüft und sendet die E-Mail selbst. Das Öffnen der Mail-App sendet noch keine Nachricht und fügt keine vollständige Sammlung oder Diagnosedateien automatisch hinzu.

Die lokale Firebase-/Google-Anmeldung sowie Collection-/Binder-Synchronisation sind hiervon unabhängig vorhanden. Die frühere Support-Dokumentation mit „Firestore nicht aktiviert“ ist deshalb überholt.

Kotlin-Quellen befinden sich unter `app/src/main/java/com/dominik/cardvault/`.

## Drei unterschiedliche Konfigurationen

| Bereich | Nachgewiesener Stand |
| --- | --- |
| `AppSupportConfig` | öffentlicher Support-/Datenschutzkontakt und Community-Verweis konfiguriert |
| `ProjectSupportConfig` | Ziel leer; kein dadurch aktivierter Finanzierungs-/Unterstützungskanal |
| `OwnerSupportConfig` | `isSecureBackendConfigured = false`; kein konfigurierter privilegierter Fernsupport |

Das leere zweite bzw. deaktivierte dritte Objekt bedeutet nicht, dass die normale Support-E-Mail nicht funktioniert.

Der Community-Verweis im gelesenen Android-Code weicht vom später bestätigten dauerhaften Discord-Link ab. Das ist ein gesonderter Inhaltsabgleich für einen künftigen Änderungsauftrag; dieser Dokumentationslauf verändert die App-Konfiguration nicht.

## Kontolöschung

Der Android-Dialog erklärt die Konsequenzen, verlangt eine Bestätigung zur Vorbereitung und öffnet anschließend eine **E-Mail-Löschanfrage**. Er löscht weder das Firebase-Konto noch lokale Daten selbst.

Im separaten Web-Projekt DomiDex-Web existiert außerdem `/api/account/delete` mit serverseitigen Prüfungen und Löschablauf. „Kontolöschung ist nirgendwo implementiert“ wäre falsch; ebenso falsch wäre die Behauptung, der Android-Maildialog löse bereits diesen Ablauf automatisch aus.

Eine vollständige Löschung betrifft je nach vorhandenen Daten Auth-Konto, Sammlung/Binder, zugeordnete Dateien und Berichte. Allgemeine Referenz-/Scannerdaten sind nicht pauschal Eigentum eines einzelnen Kontos. Umfang, Fristen und tatsächlicher Vollzug müssen am Backend und an den Datenschutzinformationen nachvollziehbar geprüft werden.

## Vorhandene Verträge, keine fertige Inbox

`support/SupportContracts.kt` definiert `SupportBackend`, Fall-/Nachrichtenmodelle, Rollen `USER`, `SUPPORT_AGENT`, `ADMIN` und Push-Token-Methoden. Das ist ein vorbereiteter Vertrag, kein Nachweis einer aktiven In-App-Inbox, eines Fall-Backends oder versandter Push-Nachrichten.

Die Firestore-Regel `supportRequests` erlaubt nur eng begrenzte requestergebundene Metadaten. Sie ist keine vollständige Supportkonversation und kein Zugang zu Kamera, Bildschirmübertragung, Rohlogs oder Fernsteuerung.

Für einen künftig ausdrücklich beauftragten Dienst wären serverseitige Rollenprüfung, Zugriff pro Fall, Aufbewahrung/Löschung, Rate-Limits, Audit und verständliches Offlineverhalten gesondert erforderlich. Hierdurch wird dieser Dienst weder geplant priorisiert noch eingerichtet.

## Datenschutzgrenzen

- Support-E-Mail, Sammlungssync, ScanLab-Fotoupload und Community-Knowledge-Opt-in bleiben getrennte Zwecke.
- Eine Testerrolle oder Testervereinbarung aktiviert keinen Fotoanhang.
- Authentifizierung bestätigt Identität; eine Team-/Adminberechtigung kommt ausschließlich vom autorisierten Backend.
- Diagnose- oder Bildbeilagen benötigen einen konkreten, sichtbaren und begrenzten Übermittlungsschritt.
- Die öffentliche Datenschutzerklärung liegt außerhalb dieses Android-Repositories. Der vorhandene ältere „Online-Text noch nicht vollständig“-Hinweis in der Android-UI ist kein aktuelles rechtliches Gutachten und muss separat gegen die tatsächliche Website abgeglichen werden.

Dieses Dokument ändert weder öffentliche Rechtstexte noch Betreiberinformationen, Berechtigungen oder bestehende Testerzusagen.
