# DomiDex – Cardmarket-Import und Preisgrenzen

Prüfstand: 1. Oktober 2026. Quellenbasis ist der vorhandene Code; lokale Release-27-Erweiterungen werden ausdrücklich von veröffentlichten Releases getrennt.

## Ergänzung R27: widersprüchliche Preiszeilen abweisen (2. Oktober 2026)

Die lokale Vorprüfung lehnt jetzt eine vollständige Preisdatei ab, wenn dieselbe
`idProduct` darin unterschiedliche geparste Werte besitzt. Alle neun Preisfelder,
Kategorie und auch fehlende gegenüber vorhandenen Werten werden verglichen, bevor
der Produktkatalog verknüpft wird. Auch weiter auseinanderliegende Widersprüche
werden erkannt. Identische Wiederholungen behalten Reihenfolge und Datensatzanzahl;
eine spätere unabhängige Datei darf denselben Quellzeitpunkt weiterhin korrigieren.

Ohne diese Prüfung gewinnt bei widersprüchlichen Zeilen zuletzt Room REPLACE.
Der tatsächliche Parser-/Datenbanktest hat vor dem Fix das Überschreiben des
vorhandenen Katalogs und der Preise reproduziert: drei bestehende Fälle bestanden,
der neue Integritätstest scheiterte. Mit dem Fix bestehen unverändert **4/4** Fälle,
einschließlich späterer zulässiger Korrektur und Rollback nach einer späten
SQLite-Ausnahme. Die gezielten JVM-Fälle reproduzierten zunächst35PASS/1FAIL;
die vollständige anschließende JVM-Suite besteht mit **917PASS/1 bestehendem
optionalen Live-SKIP**, ohne Fehler. Debug-/Test-APK, Release-Kotlin und beide
Lint-Prüfungen bestehen; bestehende Warnungen bleiben sichtbar.

Garantiert wird die Ablehnung vor dem Speichern der eingehenden Katalog-/Preiswerte,
nicht eine unveränderte komplette Produktions-Statuszeile: der bestehende äußere
Importer schreibt zunächst RUNNING und bei Fehlschlag ERROR mit neuem Versuchstermin
und Fehlertext, unter Beibehaltung der früheren Erfolgs-/Quell-/Anzahlangaben. Diese
HTTP-/Statushülle ist nicht Gegenstand des neuen Room-Tests.

Die zusätzliche lokale Prüfung benötigt erwartbar O(n) Zeit und O(u) temporären
Map-Speicher für eindeutige IDs. Sie kopiert keine Preisobjekte und persistiert
keinen zusätzlichen Cache; genaue zusätzliche Laufzeit/Spitzenbelegung wurde nicht
gemessen. Netzwerk, Schema, Historie und Full-Sync wurden nicht verändert.

Belege im ignorierten Engineering-Verzeichnis
`artifacts/release27-engineering-20261002/pricing-duplicate-review/`:
`red-011429-1f823aa9/`, `room-124815-f2ca6444/`,
`green-124911-83029c8c/` und `room-125324-14b2f629/`.
Der geprüfte eingefrorene Download enthielt keine doppelten IDs; diese Härtung
erklärt daher **nicht** dessen offene Preis-/Mapping-Lücken und erzeugt keine
zusätzlich belegten Preise. Noch nicht veröffentlicht; Release26 bleibt unverändert.

## Tatsächlich verwendete Quellen

Der Android-Import `app/src/main/java/com/dominik/cardvault/data/sync/CardmarketSync.kt` verwendet die offiziellen öffentlichen Pokémon-Downloads:

- [Produktkatalog](https://downloads.s3.cardmarket.com/productCatalog/productList/products_singles_6.json)
- [Preisguide](https://downloads.s3.cardmarket.com/productCatalog/priceGuide/price_guide_6.json)

Der Import benötigt keinen persönlichen Cardmarket-API-Schlüssel und durchsucht keine Verkaufsangebote. Einzelne Produktseiten wurden für dokumentierte, statisch hinterlegte Identitätskorrekturen manuell geprüft; das ist kein allgemeiner Laufzeit-Scraper.

Die visuelle Set-/Kartenquelle im aktuellen `PokemonVisualCatalog.kt` ist **TCGdex, Deutsch und Englisch**. Die frühere Beschreibung einer ausschließlich englischen Pokémon-TCG-API-GitHub-Datenquelle ist überholt.

## Identität vor Preis

Externe Cardmarket-Identität: `CARDMARKET_PUBLIC_DOWNLOAD` und `idProduct`; reine importierte Produktdatensätze verwenden IDs der Form `cm-pokemon-{idProduct}`.

Die fachliche Kartenidentität umfasst Set/Karten-ID, Sammlernummer, Sprache und Druckausführung. Ein ähnlicher Name, eine gleiche Root-ID oder eine Position in einer Liste beweist keine identische Karte. Der öffentliche Produktdownload enthält keine ausreichend strukturierten Collector-/Finish-/Variantenfelder für eine universelle Ableitung.

Vorhandene konkrete Produktlinks kommen aus geprüften Quellenzuordnungen bzw. einer ausdrücklichen Auswahl. Quarantäneregeln sperren bekannte falsche Identitätspaare. Eine positive Preistabelle allein legitimiert keinen falschen Produktlink.

Die gedruckte Main-Set-Gesamtzahl ist keine Obergrenze gültiger Secret-/SAR-Kartennummern. RGB-Karten besitzen ihre eigene gedruckte Identität, beispielsweise `R/RGB`, nicht einen erfundenen Main-Set-Zähler.

## Trend und lokale Preisalternativen-Erweiterung

Ein echter positiver, endlicher Cardmarket-Trendwert mit passender Identität ist die bevorzugte Bewertungsquelle. Mengenwerte werden daraus berechnet. Fehlende Werte werden nicht mit erfundenen Nullen oder einem Preis einer anderen Karte/Variante gefüllt.

Der lokale Release-27-Stand ergänzt `model/CardPriceSelection.kt`, `data/PriceSelectionMapping.kt` und `data/sync/PriceSelectionSyncContract.kt`:

- Nur ohne geeigneten positiven Trendwert kann ein tatsächlich vorhandener Vergleichswert derselben Preisquelle angeboten werden.
- Es gibt keine Vorauswahl und keine freie Betragseingabe; die Auswahl muss bestätigt werden.
- Normale und Reverse-Werte werden entsprechend der Druckausführung getrennt.
- Set-/Karten-/Produkt-ID, Sammlernummer, Variante, Finish, Sprache, Quellzeitpunkt und gewählte Kennzahl sind Bestandteil der Validierung.
- Bei wieder vorhandenem echtem Trend hat dieser Vorrang.
- Die Auswahl wird separat unter `users/{uid}/collectionPriceSelections/{entrySyncId}` synchronisiert und nicht als offizielle Trendhistorie gespeichert.

Diese Implementierung liegt im lokalen Arbeitsverzeichnis. Ihr Vorhandensein ist kein Beleg, dass sie bereits in Release 26 oder in jeder veröffentlichten Web-Version enthalten ist. Der UI-Text in `CardVaultApp.kt`, der weiterhin ausschließlich Trendpreise verspricht, ist ein bekannter fachlicher Textabgleich für einen späteren funktionalen Lauf; dieser Dokumentationsauftrag ändert ihn nicht.

Cardmarket-Vergleichswerte sind keine Garantie eines konkreten Verkaufswerts einer individuellen Karte. Sprache, Zustand, tatsächliches Angebot und abgeschlossener Verkauf können vom aggregierten Guide abweichen.

## Import, Cache und Fehlerverhalten

Produkt- und Preisdownload werden gemeinsam verarbeitet und transaktional in Room übernommen. Ein fehlgeschlagener Import löscht keine Sammlung oder bisherigen gültigen Snapshots. Die bestehenden Migrationen entfernen ausdrücklich künstliche frühere Demo-Preise.

WorkManager plant eine netzgebundene Aktualisierung im 24-Stunden-Raster. Beim Start wird ein veralteter Import berücksichtigt; manuelle Aktualisierungen werden gegen parallele Läufe koalesziert. Der Preisguide ist kein Echtzeitfeed.

Visuelle Metadaten besitzen getrennte begrenzte Caches; sie speichern weder Eigentumsrechte noch als Nebenwirkung neue Preise. Android und Web besitzen außerdem unterschiedliche Preis-/Detailcaches. Sofort identische Preise auf allen Geräten sind damit nicht garantiert.

## Nachgewiesene Abdeckung statt Vollständigkeitsbehauptung

Der lokale, noch unversionierte Bericht `docs/release27-price-sync-validation-20260925.md`
beschreibt folgende Momentaufnahme; die GitHub-lesbare Zusammenfassung steht auch
in [PROJECT_STATUS](PROJECT_STATUS.md):

| Größe im damaligen Snapshot | Ergebnis |
| --- | ---: |
| physische deutsche Sets | 144 |
| Karten einschließlich lokaler Ergänzungen | 19.463 |
| untersuchte Druckplätze | 27.131 |
| unabhängig bepreiste Druckplätze nach Korrektur | 17.867 |
| Slots ohne ausreichend belegten Quellenverweis | 9.227 |
| Slot ohne Mapping | 1 |

Diese Zahlen sind ein **datierter Datenaudit**, keine heute neu ausgeführte Abfrage und keine globale Garantie. Eine Null in der strengen Verifikationstabelle bedeutet nicht automatisch, dass jede UI-Karte dieses Sets preislos ist.

8.024 Karten ohne verwertbare druckspezifische ID, zahlreiche Root-ID-Kollisionen und generierte Einzelausführungen verhindern einen seriösen universellen Namen-/Root-ID-Fallback. Ein vorhandenes Verkaufsangebot garantiert außerdem keinen positiven Trendwert im verwendeten Download. Im geprüften Snapshot fehlte dieser unter anderem beim blauen RGB-Mew.

## 30-Jahre-Ergänzungen: zeitlicher Verlauf

Die historischen Nachweise bleiben erhalten; spätere Dokumente ergänzen frühere Grenzen:

Die vier Detailberichte liegen bisher nur lokal unter `docs/`:

1. `catalog-30th-supplement-20260922.md`: drei RGB-Mew und MEP 094–110, Bild-/Sprachfallbacks sowie zu diesem Zeitpunkt ungeklärte Produktzuordnungen.
2. `catalog-30th-cardmarket-prices-20260922.md`: begrenzter, ausdrücklich belegter erster Satz Produktlinks; keine universelle Namensheuristik.
3. `catalog-30th-sar-prices-20260923.md`: zusätzliche nummernseitig verifizierte Produktlinks und damalige echte Preisguide-Lücken.
4. `catalog-30th-classic-cardmarket-20260925.md`: sieben restliche Classic-Zuordnungen; damit im beschriebenen Registry-Stand 30 Classic-, 158 Main-Set- und drei RGB-Identitäten.

Vollständige Identitätslinks für diese Gruppen sind nicht gleichbedeutend mit vollständigen Preisen für jede Druckausführung. Datierte Testzahlen und damalige Nullwerte dürfen nicht stillschweigend als heutige Live-Messung umgedeutet werden.

## Tests und Zuständigkeit

Normale Parser-, Identitäts-, Portfolio- und Auswahltests arbeiten mit lokalen Fixtures. Vorhandene Live-Smoke-Tests sind gesondert opt-in; `cardvault.liveSmoke=true` ist keine Voraussetzung eines sicheren Offline-Tests.

Web-Darstellung, Web-Caches und deren Backend-Vertrag liegen in DomiDex-Web. Gezielte Android-Tests ersetzen weder Web-Tests noch einen realen Zwei-Geräte-Sync-Test. In diesem Dokumentationslauf werden keine Produktlinks, Preise, Firebase-Regeln oder Releases verändert.
