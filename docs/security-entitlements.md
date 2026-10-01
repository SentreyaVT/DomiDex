# DomiDex – Sicherheits- und Entitlement-Architektur

Prüfstand: 1. Oktober 2026. Die folgenden Grenzen beschreiben gelesenen Code. Sie sind keine pauschale Aussage über jede historische Veröffentlichung oder die aktuellen Einstellungen aller Cloud-Dienste.

## Aktive Identität statt Zukunftsentwurf

Google-Anmeldung, Firebase Authentication, Firestore-/Storage-Integration und Cloud-Function-Quellcode sind vorhanden. Die frühere Aussage, diese Dienste seien insgesamt „noch nicht aktiv“, ist überholt. Das allein bestätigt nicht, welche lokale Regel-/Function-Version aktuell deployed ist.

`FirebaseGoogleAuthGateway.kt` verwendet Credential Manager und Firebase Auth. Ein Konto mit Google-Verknüpfung wird als kanonischer Sammlungs-Owner verwendet. Kontoabbruch/-wechsel, Sign-out und Gastdaten sind getrennte Zustände; eine lokal eingegebene E-Mail-Adresse erteilt keine Rechte.

## Autorität und Zugriff

- Android wertet serverseitig ausgestellte boolesche Claims wie `admin`, `appAccess` und `pretester` aus; es stellt diese Claims nicht selbst aus.
- `accountAccess` und `accountDeletionLocks` können einen vorhandenen Zugriff zusätzlich sperren. Ein permissives lokales Flag ersetzt weder gültige Claims noch die serverseitige Prüfung.
- Firestore-/Storage-Regeln beginnen mit Default-Deny und erlauben bestimmte ownergebundene oder administrativ eingeschränkte Operationen.
- Berechtigungen müssen an jedem Backend-/Regelzugriff geprüft werden. Eine ausgeblendete Schaltfläche ist lediglich Darstellung.
- Offline lesbare lokale Daten bedeuten keine offline erteilte Berechtigung für geschützte Cloud-Operationen.

Belege: `auth/FirebaseEntitlements.kt`, `auth/FirebaseAppAccess.kt`, `auth/CanonicalCollectionOwner.kt`, `firestore.rules`, `storage.rules`; Kotlin-Pfade unter `app/src/main/java/com/dominik/cardvault/`.

## Datensynchronisation ist nicht Support-Einwilligung

Bei einem berechtigten Owner werden Sammlung und zugehörige Binder-Mutationen über ihre vorgesehenen Outboxes synchronisiert. „Sammlung wird nie automatisch hochgeladen“ wäre daher falsch.

Davon unabhängig bleiben:

- ausdrücklicher manueller ScanLab-Fotoupload;
- separate standardmäßig ausgeschaltete Community-Knowledge-Einwilligung;
- ausdrücklich bestätigte optionale Katalogbild-Beiträge;
- E-Mail-Support, den der Nutzer selbst prüft und absendet.

Keine dieser Zustimmungen darf aus einer anderen abgeleitet werden. Insbesondere ist die Testerfreigabe keine automatische Fotoveröffentlichung.

## Private Daten, Freigaben und Backups

Owner-Namespaces, optimistische Revisionen und Tombstones schützen die private Replikation. Eine private Binderfreigabe erzeugt dagegen absichtlich einen lesbaren Link: Der Besitz des Tokens ermöglicht den Abruf, nicht eine zusätzliche Empfängerrolle. Erstellen und Widerrufen sind Besitzeroperationen. Freigabeoptionen für Preise, Summe und Anzeigename sind getrennt und standardmäßig aus.

Das lokale ZIP-Backup ist nicht verschlüsselt. Es kann private Sammlungseinträge, UID, Notizen, Kaufdaten und Fotos enthalten, aber keine als Berechtigung wiederherzustellenden Rollen, Tokens oder Einwilligungen. Exportdateien müssen entsprechend vertraulich behandelt werden.

## App Check, Integrity und Build-Schutz

Die lokale Dependency-Auswertung enthält `firebase-appcheck:19.4.0` und `firebase-appcheck-interop:17.1.0` transitiv. Es wurde jedoch kein aktiver App-Check-Provider und keine Initialisierung im Android-Anwendungscode gefunden. Daraus folgt **kein nachgewiesenes App-Check-/Play-Integrity-Enforcement**. Transitive Bestandteile sind keine aktivierte Attestierung.

Eine spätere App-Check-Einführung müsste Anbieter-/Console-Konfiguration, Debug-/Release-Verhalten, erlaubte Clients und Ausfallverhalten separat prüfen. Dieses Dokument aktiviert nichts und verändert keine bestehenden Firebase-Zugriffe.

R8/Obfuskation und Signierung sind Build-/Integritätsbausteine, keine Autorisierungsgrenze. Vorhandene funktionierende R8-/Signing-Einstellungen werden durch diesen Audit weder zurückgebaut noch geändert. Der Upload von Debug-Symbolen beweist keine vollständige Verfügbarkeit herstellerseitig entfernter Symbole.

## Nicht als aktive Produkte ausgeben

`support/SupportContracts.kt` enthält Rollen-/Inbox-Verträge für einen möglichen Supportdienst. Daraus folgt keine eingerichtete Support-Agent-Inbox oder Push-Infrastruktur.

Im geprüften aktiven Zugangspfad wurde kein umfassendes Play-Billing-/Kaufprüfungs-/PRO-Entitlement-Produkt belegt. Frühere Architekturentwürfe mit `PRO`, Storekaufprüfung oder Pro-Offline-Caches sind keine Beschreibung eines aktivierten Angebots.

Monetarisierung wird gesondert entschieden. Dieses Dokument führt weder Abo noch Einmalkauf, Paywall oder neue Testerbedingungen ein und hebt keine bestehenden Testerzusagen auf.

## Offene Prüf- und Härtungspunkte

- Aktuell deployte Regeln/Backend-Versionen müssen getrennt vom lokalen Arbeitsverzeichnis nachgewiesen werden.
- Admin SDK umgeht Clientregeln: Web-/Function-Handler brauchen eigene Autorisierung und Eingabeprüfung.
- ScanLab-JPEG-Neukodierung entfernt Metadaten, aber keine im Bild sichtbaren privaten Inhalte. Der optionale Katalogbild-Beitrag überträgt aktuell Originaldateibytes; eine allgemeine Metadatenfreiheit darf nicht zugesagt werden.
- Ein fehlendes Community-Knowledge-Remote-Dokument entfernt nach gelesenem Clientpfad einen zuvor gespeicherten Cacheeintrag nicht zwingend. Sofortiger globaler Widerruf ist nicht belegt.
- Rate-Limits, serverseitige Quoten, Monitoring und Attestierung sind pro Endpunkt zu prüfen, nicht aus vorhandenen Rules abzuleiten.
- Ein begrenzter Quelltext-/Dateinamen-Secretscan ist keine vollständige Prüfung der Git-Historie oder sämtlicher lokaler Dateien.

Konkrete Pfade, Regeltestgrenzen und Uploadunterschiede: [FIREBASE_SECURITY.md](../FIREBASE_SECURITY.md).
