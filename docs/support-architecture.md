# CardVault Support-Architektur

## Aktueller Stand

Die App enthält eine bewusst deaktivierte E-Mail-Kontaktstelle und fachliche Verträge für eine spätere In-App-Support-Inbox. Die lokale Firebase-Konfigurationsdatei wurde für `com.dominik.cardvault` geprüft und bleibt von Git ausgeschlossen. Google-Services-Plugin, Firebase Authentication und Credential Manager sind eingebunden. Der Google-Login wird ausschließlich durch den sichtbaren Nutzer-Button gestartet und bestätigt zunächst nur die Identität; er vergibt keine Teamrechte. Es sind weder eine Betreiberadresse noch eine Google-UID oder Cloud-Geheimnisse im Quellcode eingebaut. Firestore und Push sind nicht aktiviert; es findet durch CardVault keine Support-Hintergrundübertragung statt.

## Produktionsanforderung

Eine echte Inbox braucht einen externen, vom Betreiber kontrollierten Backend-Dienst mit:

- Google-OIDC-Anmeldung für Nutzer und Betreiber;
- serverseitig gepflegten Rollen `USER`, `SUPPORT_AGENT`, `ADMIN`;
- Zugriffskontrolle pro Supportfall und unveränderlichem Nachrichtenverlauf;
- Push-Token-Registrierung nur nach Einwilligung sowie serverseitigem Versand;
- Aufbewahrungs-, Lösch- und Datenschutzregeln;
- Rate-Limits, Audit-Protokoll und verständlichen Offline-/Fehlerzuständen.

Der Android-Client darf eine Adminrolle niemals aus einer E-Mail-Adresse oder lokalen Einstellung ableiten. Das Backend validiert das Google-ID-Token und liefert eine signierte/authoritative Rolle. Die konkrete Betreiber-E-Mail und Google-UID werden erst nach ausdrücklicher Nennung serverseitig zugeordnet.

## Rollenbasierte Oberfläche

- Abgemeldete und normale Nutzer erhalten ausschließlich die normale CardVault- und eigene Supportansicht. Betreiber-/Teamnavigation, globale Inbox und Fallverwaltung werden nicht dargestellt.
- `SUPPORT_AGENT` darf nach serverseitig bestätigter Rolle Inbox und Fallverwaltung sehen, aber keine administrative Konfiguration.
- `ADMIN` darf zusätzliche Verwaltungsbereiche sehen. Welche einzelne Aktion erlaubt ist, entscheidet trotzdem immer das Backend anhand des aktuellen Tokens.
- Die UI-Fähigkeitsmatrix ist ausschließlich UX. Ausgeblendete Schaltflächen sind keine Sicherheitsgrenze. Direkte Requests, manipulierte Clients und abgelaufene Rollen werden durch Firebase-Regeln bzw. Backend-Autorisierung abgewiesen.
- Es gibt keinen lokalen Adminschalter und keine Ableitung aus E-Mail, Anzeigename, Build-Variante oder Gerätezustand.

## Kleinster nächster Einrichtungsschritt

Der Google-Anbieter, der Debug-SHA-Fingerprint und der Web-Client wurden für den aktuellen lokalen Test konfiguriert. Eine spätere Release-Signatur benötigt ihren eigenen Fingerprint. Die Admin-Zuordnung erfolgt erst später serverseitig anhand einer ausdrücklich genannten Betreiberidentität; nie anhand einer lokalen E-Mail-Abfrage. Firestore-Regeln, Backend-Rollen und Push erfordern eine gesonderte Datenschutz-, Kosten- und Betriebsfreigabe. Geheimnisse gehören nicht in die App. Bis dahin bleibt die Inbox sichtbar als nicht konfiguriert und es wird keine Produktionsfunktion vorgetäuscht.

## Datenschutz

Sammlung, Fotos und Diagnoseinformationen werden nie automatisch an den Support gesendet. Eine spätere Diagnosebeilage ist standardmäßig aus und muss pro Anfrage sichtbar bestätigt werden. Der normale E-Mail-Weg verwendet ausschließlich einen Android-`ACTION_SENDTO`-Entwurf.
