# DomiDex – Architektur und Datenfluss

Prüfstand: 1. Oktober 2026. Dieses Dokument beschreibt den gelesenen Android-Quellstand, nicht automatisch den Inhalt eines veröffentlichten AAB. Die ursprüngliche Zuordnung steht im [Projektstatus](PROJECT_STATUS.md), anschließende lokale Korrekturen und Testresultate im [Release-27-Ledger](RELEASE_27.md).

## Standgrenzen

Aktuelle Übergabe vom 04.10.2026: [R27-Entwickler-Einstieg](DEVELOPER_HANDOFF_R27.md).
R27 ist im geschlossenen Test verfügbar; die folgenden Auditstände sind historische
Quellcodebeobachtungen, keine unverändert aktuellen Release-/Betriebsaussagen.
Full-Sync bleibt die R27-Lösung, Delta deaktiviert. Die spätere ausdrücklich
autorisierte zwölfmonatige Preisverlaufs-Aufbewahrung ist im Ledger dokumentiert.

- Ausgangs-HEAD des früheren Dokumentationsaudits war `096aa35` vom 22. September 2026.
- Die damals teils unversionierten Weiterentwicklungen für Release 27 sind inzwischen im lokalen Engineeringcheckpoint `53b4e40d` gesichert. Weitere gezielte Änderungen werden auf `codex/release-27-hardening` geprüft; keine Veröffentlichungsfreigabe.
- Die lokale Room-Konfiguration verwendet Schema **13**; der damalige Audit-HEAD `096aa35` enthielt Schema **12**. Die Preisalternativen-Tabelle gehört zur lokalen Erweiterung; im anschließenden Engineeringlauf wurde das Schema nicht erhöht.
- Der offiziell genutzte Release 26 und der lokale Quellstand sind nicht gleichzusetzen. Ein erfolgreicher lokaler Test beweist nicht rückwirkend den Inhalt oder Zustand eines älteren Releases.
- Die Web-App und ihre serverseitigen HTTP-Endpunkte werden im separaten Projekt **DomiDex-Web** gepflegt. Dieses Android-Repository ist nicht die vollständige Website oder Backend-Implementierung.

## Android-Kern

DomiDex ist eine Kotlin-/Jetpack-Compose-App mit Room als lokaler Datenbasis für die Oberfläche. Firebase Authentication, Firestore, Storage und WorkManager sind eingebunden; „account-free“ und „Netzwerkimporte erst geplant“ beschreiben den aktuellen Code nicht mehr.

Der wesentliche Datenfluss ist:

1. Katalog-/Preisdaten werden unabhängig vom Kartenbesitz importiert oder abgefragt.
2. Ein bestätigter Karteneintrag wird mit seiner fachlichen Identität in Room gespeichert.
3. Bei berechtigtem Google-Konto werden ownergebundene Änderungen über dauerhafte Outboxes synchronisiert.
4. Remote-Änderungen werden unter Revisions- und Konfliktschutz in die lokale Darstellung übernommen.

Die Tabellen und expliziten Migrationen stehen in `app/src/main/java/com/dominik/cardvault/data/local/Entities.kt` und `CardVaultDatabase.kt`. Es gibt keinen konfigurierten destruktiven Migrations-Fallback.

## Identität und Besitz

`catalog_cards` beschreibt Referenzkarten. `collection_entries` enthält tatsächlichen Besitz einschließlich Menge, Zustand, Notizen, Anschaffungsdaten und lokaler Bildverweise. Ein Katalogimport erzeugt keine besessenen Karten.

Set/Karten-ID, Sammlernummer, Sprache, Finish/Variante und verifizierte Quellen-/Produktidentität dürfen nicht auf einen bloßen Kartennamen reduziert werden. Karten oberhalb der gedruckten Main-Set-Gesamtzahl sowie eigenständig nummerierte RGB-Karten sind keine pauschal ungültigen Set-Mitglieder.

Ein berechtigtes, mit Google verbundenes Firebase-Konto bildet den kanonischen Owner. Gastdaten liegen separat unter `__domidex_guest__`. Ihre Übernahme erfordert einen ausdrücklichen, serverseitig verifizierten Schritt. Ein GitHub-Zugang für optionale Update-Funktionen ersetzt diesen Owner nicht.

## Binder, Sammlung und Portfolio

- Referenz-/Set-Binder nutzen Katalogidentitäten und die vorhandenen Karten.
- Private Binder besitzen eigene Metadaten, Cover, Seiten und geordnete Platzierungen mit Holo-Auswahl.
- Eine private Platzierung referenziert einen vorhandenen Sammlungseintrag; Verschieben bedeutet nicht, dass neuer Besitz entsteht. Der Repository-Pfad behandelt auch belegte Zielplätze.
- Summen verwenden den effektiven Einzelwert mal Menge und unterscheiden bepreiste von unbepreisten Einträgen.
- Die historische Kurve berechnet frühere Preissnapshots mit den **gegenwärtigen Mengen**. Sie ist keine vollständige Transaktions-, Rendite- oder historische Vermögensbuchhaltung.
- Die manuell ausgewählte Startseitenkarte ist eine lokale Präsentationseinstellung (`dashboard_focus`). Eine eigenständige synchronisierte Favoritenliste wurde nicht gefunden.

Zentrale Implementierung: `data/RoomCollectionRepository.kt`, `model/CardModels.kt` und `ui/CardVaultApp.kt` unter dem Kotlin-Paket `com/dominik/cardvault`.

## Preise und Katalog

Der aktuelle visuelle Pokémon-Katalog verwendet TCGdex für deutsche und englische Metadaten. Der offizielle öffentliche Cardmarket-Produkt-/Preisdownload ist eine getrennte Quelle. Verifizierte ergänzende Produktzuordnungen schließen bestimmte bekannte Lücken, nicht beliebige Variantenlücken.

Echte positive Trendwerte bleiben vorrangig. Im lokalen Release-27-Stand existiert zusätzlich eine ausdrücklich bestätigte, identitätsgebundene Vergleichspreis-Auswahl, wenn kein geeigneter Trendwert vorhanden ist. Sie wird nicht als Cardmarket-Trendhistorie ausgegeben. Details und bekannte Abdeckungslücken: [Cardmarket-Import](cardmarket-import.md).

## Offline- und Synchronisationsgrenzen

Room bleibt für lokale Ansichten verfügbar. Collection-Outboxes speichern Mutationen zusammen mit der fachlichen Änderung; stabile IDs, Mutation-IDs, erwartete Revisionen und Tombstones schützen vor blindem Überschreiben bzw. Wiederauferstehen gelöschter Einträge.

`CollectionFirebaseSync.kt` verarbeitet einen ownergebundenen Firestore-Listener. `PrivateBinderFirebaseSync.kt` nutzt zusätzlich begrenzte Abrufe, ereignisbezogene Arbeit und periodische Arbeit im 15-Minuten-Raster. Das ist **keine garantierte verzögerungsfreie Zwei-Geräte-Replikation**.

Konflikte und terminale Payload-/Regelfehler sind getrennte Zustände. Lokale/Cloud-Auflösung erfolgt explizit. Vollständig manuelle, nicht verknüpfte Karten, die unabhängig auf zwei Offline-Geräten angelegt werden, können getrennte Einträge bleiben.

Die neuen Preis-Sidecars hängen an bestätigten Parent-Einträgen und werden über eine eigene Datenstruktur synchronisiert; sie erweitern nicht ungeprüft den bestehenden Collection-Entry-Vertrag.

## Private Binder teilen

Der Android-Client verwendet den Web-Endpunkt `https://domidex.web.app/api/binder-shares` für Erstellen, Auflisten und Widerrufen. Freigaben benötigen das Besitzerkonto und eine ausdrückliche Bestätigung. Preise, Gesamtwert und Anzeigename sind getrennte Optionen, standardmäßig ausgeschaltet.

Der lokale Weiterentwicklungsstand enthält priorisierte Vorbereitung der betroffenen Binder-/Kartendaten sowie einen eigenen Android-Leser für `/share/{token}`. Ein Timeout, Offline-Zustand oder unbekanntes Ergebnis eines POSTs darf nicht als erfolgreiche Freigabe dargestellt werden.

Der gelesene Web-Vertrag ist eine **Linkfreigabe**: Wer den geheimen Link besitzt, kann den freigegebenen Binder lesen; eine zusätzliche Empfängerrollenprüfung ist nicht Teil dieses Vertrags. Widerruf und Gültigkeit des Besitzers werden serverseitig geprüft. Private Linktokens gehören nicht in Logs oder öffentliche Dokumentation.

## Scanner, Bilder und gemeinsames Wissen

Scanner-Erkennung, lokale Analyse und Scanner-Lab-Uploads sind unterschiedliche Abläufe. Dieses Dokument verspricht keine gemessene Beschleunigung oder Erkennungsquote; dafür sind reproduzierbare Messungen gegen einen definierten Vergleichsstand nötig.

- Fehlscan-Fotos werden über den bestehenden **manuellen** ScanLab-Prozess übermittelt, nicht durch allgemeine Testerzustimmung automatisch.
- Gemeinsames Scannerwissen hat eine separate, standardmäßig ausgeschaltete versionierte Einwilligung und überträgt technische Hinweise/Korrekturen, keine Fotos.
- Kanonische Functions-Deployquelle ist `backend/scan-knowledge` im separaten Web-Repository. Androids `functions/knowledge-aggregate.js` bleibt historische Referenz; ein lokaler Functions-Predeploy-Guard verhindert das versehentliche Überschreiben aus diesem Repository. Die Aggregation ist keine automatische Neutrainierung eines ML-Modells. Ownership und Nachweise stehen im [Release-27-Ledger](RELEASE_27.md).
- Ein optionaler Katalogbild-Beitrag ist nochmals ein eigener Uploadpfad. Seine Datenschutzmerkmale sind nicht mit denen eines ScanLab-JPEGs gleichzusetzen.

Siehe [Firebase-Sicherheitsgrenzen](../FIREBASE_SECURITY.md).

## Backup und Wiederherstellung

Das benutzergesteuerte Backup ist eine ZIP-/JSON-Datei mit lokalen Sammlungs-/Binderinformationen und gegebenenfalls Fotos. Das aktuelle Format V2 unterstützt auch die lokale Preis-Sidecar-Erweiterung. Es ist **nicht anwendungsseitig verschlüsselt** und kann UID, Notizen und Kaufdaten enthalten.

Auth-Tokens, Rollen/Entitlements und Einwilligungen werden nicht als wiederherstellbare Berechtigungen exportiert. Importgrenzen, Pfad-/CRC-Prüfung, Datenbanktransaktionen und ein Wiederherstellungsjournal schützen den technischen Import. Sie ersetzen kein sicheres externes Aufbewahrungskonzept. Android-Systembackup ist in der Manifest-Konfiguration deaktiviert.

## Keine daraus abzuleitenden Produktversprechen

`graded_market_records` und `grading_analyses` sind vorhandene Daten-/Vertragsstrukturen. Daraus folgt keine aktive professionelle Grading-Analyse, PSA-Bewertung oder aktuelle Grading-Roadmap-Priorität.

Mehrere TCGs in einem Datenfeld bedeuten nicht, dass mehrere Spiele vollständig unterstützt werden. Ein geprüftes Build-/Regeltestresultat ist auch keine Lizenz-, Datenschutz- oder Sicherheitszertifizierung. Monetarisierung und bestehende Testerzusagen sind gesonderte Produktentscheidungen; dieses Dokument führt kein neues Kauf-, Abo- oder Pro-Modell ein.
