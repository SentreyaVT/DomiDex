# CardVault Support-Architektur

## Aktueller Stand

Die App enthält eine bewusst deaktivierte E-Mail-Kontaktstelle und fachliche Verträge für eine spätere In-App-Support-Inbox. Es sind weder eine Betreiberadresse noch eine Google-UID, API-Schlüssel oder Cloud-Zugangsdaten eingebaut. Es findet keine Hintergrundübertragung statt.

## Produktionsanforderung

Eine echte Inbox braucht einen externen, vom Betreiber kontrollierten Backend-Dienst mit:

- Google-OIDC-Anmeldung für Nutzer und Betreiber;
- serverseitig gepflegten Rollen `USER`, `SUPPORT_AGENT`, `ADMIN`;
- Zugriffskontrolle pro Supportfall und unveränderlichem Nachrichtenverlauf;
- Push-Token-Registrierung nur nach Einwilligung sowie serverseitigem Versand;
- Aufbewahrungs-, Lösch- und Datenschutzregeln;
- Rate-Limits, Audit-Protokoll und verständlichen Offline-/Fehlerzuständen.

Der Android-Client darf eine Adminrolle niemals aus einer E-Mail-Adresse oder lokalen Einstellung ableiten. Das Backend validiert das Google-ID-Token und liefert eine signierte/authoritative Rolle. Die konkrete Betreiber-E-Mail und Google-UID werden erst nach ausdrücklicher Nennung serverseitig zugeordnet.

## Kleinster nächster Einrichtungsschritt

Der Betreiber muss sich für ein Cloud-/Backend-Projekt entscheiden und dessen Datenschutz-, Kosten- und Betriebsmodell freigeben. Erst danach werden OAuth-Client-ID, Android-Paketname und SHA-256-Fingerprint sicher konfiguriert. Geheimnisse gehören nicht in die App. Push erfordert zusätzlich ein Push-Projekt und eine transparente Einwilligung. Bis dahin bleibt die Inbox sichtbar als nicht konfiguriert und es wird keine Produktionsfunktion vorgetäuscht.

## Datenschutz

Sammlung, Fotos und Diagnoseinformationen werden nie automatisch an den Support gesendet. Eine spätere Diagnosebeilage ist standardmäßig aus und muss pro Anfrage sichtbar bestätigt werden. Der normale E-Mail-Weg verwendet ausschließlich einen Android-`ACTION_SENDTO`-Entwurf.
