# CardVault Sicherheits- und Entitlement-Architektur

Diese Leitlinie bereitet spätere kostenpflichtige Funktionen vor. Sie aktiviert weder ein Pro-Angebot noch Käufe. Kein Clientschutz kann Manipulation absolut unmöglich machen; Ziel sind mehrere überprüfbare Schutzschichten und ein sicherer Fehlerzustand.

## Autorität und Rollen

- Die Android-App ist niemals Autorität für `ADMIN`, `SUPPORT_AGENT` oder `PRO`.
- Google-Anmeldung liefert eine Identität. Ein kontrolliertes Backend validiert das ID-Token und ermittelt Rollen und Entitlements serverseitig.
- Betreiber- und Supportrollen werden anhand serverseitig gepflegter Identitäten vergeben, nicht anhand einer lokal verglichenen E-Mail-Adresse.
- Rollen steuern eine getrennte Oberfläche: normale Nutzer sehen keine Teamfunktionen; Support-Agenten sehen Inbox/Fälle und Admins gegebenenfalls Verwaltung. Diese Unsichtbarkeit ist nur UX und ersetzt nie die serverseitige Prüfung jeder Aktion.
- Bei fehlender oder abgelaufener Bestätigung bleiben geschützte Funktionen geschlossen; lokale Sammlungsdaten bleiben weiterhin lesbar und sicher.

## Datenzugriff

- Firebase-Sicherheitsregeln gewähren standardmäßig keinen Zugriff und öffnen nur die kleinste benötigte Operation für den authentifizierten Eigentümer eines Datensatzes.
- Nutzer dürfen nur ihre eigenen Supportfälle lesen und Nachrichten hinzufügen. Zustandswechsel und Adminübersichten erfordern serverseitig bestätigte Rollen.
- Diagnosen, Fotos und Sammlung werden nie automatisch hochgeladen. Jede optionale Beilage braucht eine sichtbare, konkrete Einwilligung.
- Support- und Adminaktionen erhalten Rate-Limits, Audit-Ereignisse und nachvollziehbare Fehlermeldungen ohne sensible Interna.

## Käufe und Pro-Entitlements

- Der Client darf einen Kauf starten und den Status anzeigen, aber kein Pro-Recht selbst aus einem lokalen Flag ableiten.
- Ein Backend prüft Kauf-Token beim offiziellen Store, ordnet sie einer authentifizierten Identität zu und speichert den autoritativen Entitlement-Status.
- Rückerstattung, Widerruf, Ablauf und erneute Prüfung werden serverseitig berücksichtigt. Offline-Caches sind kurzlebig, signiert bzw. an den Nutzer gebunden und niemals alleinige Autorität.
- Play Integrity kann als zusätzliches Risikosignal für sensible Operationen dienen. Es ersetzt weder Kaufprüfung, Authentifizierung noch serverseitige Autorisierung und garantiert keine Manipulationsfreiheit.

## App- und Betriebsschutz

- Produktionsversionen werden mit einem geschützten Release-Schlüssel signiert; Schlüsselmaterial und Backend-Secrets liegen nie im Repository oder APK.
- Release-Builds verwenden R8-Minify/Obfuskation nach getesteten Keep-Regeln. Das erschwert Analyse, ist aber keine Sicherheitsgrenze.
- Netzwerkzugriffe verwenden TLS; Backend und Regeln validieren alle Eingaben erneut. Fehler verbergen Tokens und interne Details.
- Rollen-, Regel- und Entitlement-Änderungen werden vor Veröffentlichung in einer getrennten Testumgebung geprüft. Budgetwarnungen, Quoten, Missbrauchsalarme und Wiederherstellungsverfahren gehören zum Betrieb.

## Noch nicht aktiv

Google-Login, Firestore, Cloud Functions, Push, Play Integrity und Play-Billing sind in CardVault derzeit nicht produktiv aktiviert. Vor jeder Aktivierung sind Anbieter-, Datenschutz-, Kosten-, Regel- und Testentscheidungen ausdrücklich zu treffen.
