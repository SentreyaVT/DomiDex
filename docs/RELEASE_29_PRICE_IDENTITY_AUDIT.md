# DomiDex Release 29 – Preisidentitäts-Audit

**Datum:** 26. November 2026  
**Status:** vollständige Bestandsaufnahme der lokalen Preisidentitätsdaten; keine neue Preisfreigabe.

## Sicherheits- und Beweisgrenze

Dieses Audit trennt Kartenkatalog, Preisverfügbarkeit und drucksichere Produktidentität. Eine Cardmarket-Produktkennung darf nur dann verwendet werden, wenn Set, Kartennummer, Name, gedruckte Variante/Finish und Produktidentität belastbar zusammenpassen. Produktreihenfolge, ähnlicher Name, Bild, Rarität oder Preis allein wurden nicht als Beweis akzeptiert.

Es wurden weder Preise geraten noch Cardmarket-IDs, Android-/Web-Mappings, Firebase-Daten oder externe Konten verändert. Der öffentliche Cardmarket-Produkt-/Price-Guide-Download dient hier nur als technisch lesbare Quelle. Ob und unter welchen Bedingungen Preise dauerhaft extern angezeigt werden dürfen, ist eine separate rechtliche/vertragliche Abhängigkeit und wurde nicht als durch dieses Audit geklärt bewertet.

## Lokaler Katalogbestand

| Bereich | Kartensätze | Karten | Identitätsfelder | Preisidentität |
| --- | ---: | ---: | --- | --- |
| Web public/catalog/sets JSON | 92 | 14.713 | Jede Karte hat ID, lokale Nummer und Namen; 13.250 tragen eine fachliche variants-Liste, 1.463 keine | 0 Felder für idProduct, pricing oder cardmarket |
| davon physische Sets | 85 | 13.615 | Katalog-/Darstellungsdaten | Keine lokale drucksichere Cardmarket-Zuordnung im statischen JSON |
| davon Pokémon Pocket | 7 | 1.098 | Katalog-/Darstellungsdaten | Bewusst keine Cardmarket-Preisbrücke; physische Produkte dürfen nicht abgeleitet werden |
| DLR me06 (dynamische Detailquelle) | 1 | 141 | 141/141 Karten und Bilder, je normal/Standard | 62 verifiziert, 79 bewusst ohne Preisidentität |

Die Werte in variants der statischen Web-Dateien sind Spiel-/Karteneigenschaften (z. B. Energie oder Fähigkeit), keine verlässliche Angabe einer druckbaren Preisvariante. Daher sind die 92 statischen Dateien eine vollständige lokale Kataloginventur, aber kein zulässiges Produkt-Mapping-Inventar. Für dynamisch geladene physische Karten bleibt eine Quelle mit tatsächlicher gedruckter Identität erforderlich.

## DLR-/Delta-Herrschaft-Audit

Der ausschließlich lesende Nachweis wurde erneut mit folgendem bestehenden Skript erzeugt:

~~~powershell
node scripts/diagnostics/audit-dlr-cardmarket.mjs |
  Set-Content -Encoding utf8 "$env:TEMP\domidex-r29-price-audit-dlr-20261126.json"
~~~

Quellenstand des erzeugten Berichts: TCGdex DE/EN me06, Cardmarket-Expansion 6704, öffentlicher Produktkatalog (25.11.2026 12:38 +01:00) und öffentlicher Price Guide (25.11.2026 02:42 +01:00).

| Prüfung | Ergebnis |
| --- | ---: |
| Erwartete / vorhandene DLR-Karten | 141 / 141 |
| Karten mit Bild | 141 |
| Verifizierte Normal/Standard-Produktidentitäten | 62 |
| Verifizierte Trendpreise | 62 |
| Verifizierte IDs doppelt | 0 |
| Prüfen | 69 |
| Nicht zuordenbar | 10 |
| Neue eindeutig sichere Kandidaten | 0 |
| Mehrdeutige exakte Fingerprint-Kandidaten | 47 |
| Ohne exakten Fingerprint-Kandidaten | 32 |

Alle 141 DLR-Datensätze tragen im vorliegenden Detailfeed normal/Standard. Das bestätigt nicht, dass Reverse, Holo, Stamped, Promo oder Sonderdrucke mit der normalen Karte gleichgesetzt werden dürfen: Für diese nicht gelieferten oder nicht drucksicher belegten Varianten bleibt der Status „ohne verifizierte Preiszuordnung“.

### 47 mehrdeutige Normal/Standard-Drucke

Die folgenden Cardmarket-IDs stammen aus dem öffentlichen Expansionsexport, sind aber jeweils mehrere passende Fingerprint-Kandidaten und deshalb keine Zuordnung:

~~~text
003: 918812, 918940       012: 918821, 918941       018: 918832, 918943
021: 918836, 918944       029: 918845, 918959       036: 918853, 918947
048: 918869, 918948       066: 918891, 918950       068: 918893, 918952
069: 918895, 918962       078: 918906, 918953       080: 918908, 918954
082: 918910, 918955       083: 918911, 918956       084: 918912, 918982, 918985
088: 918916, 918963       089: 918917, 918964, 918983 091: 918919, 918966
092: 918920, 918967       096: 918926, 918927       097: 918926, 918927
102: 918934, 918969       103: 918935, 918970, 918984 104: 918812, 918940
105: 918821, 918941       106: 918832, 918943       107: 918836, 918944
109: 918853, 918947       110: 918869, 918948       112: 918891, 918950
113: 918893, 918952       114: 918906, 918953       115: 918908, 918954
116: 918910, 918955       117: 918911, 918956       120: 918845, 918959
123: 918895, 918962       124: 918916, 918963       125: 918917, 918964, 918983
127: 918919, 918966       128: 918920, 918967       130: 918934, 918969
131: 918935, 918970, 918984 138: 918912, 918982, 918985 139: 918917, 918964, 918983
140: 918935, 918970, 918984 141: 918912, 918982, 918985
~~~

### 32 Normal/Standard-Drucke ohne exakten Fingerprint

~~~text
011, 019, 023, 025, 026, 031, 033, 047, 053, 059, 063, 071,
090, 094, 095, 098, 099, 100, 101, 108, 111, 118, 119, 121,
122, 126, 132, 133, 134, 135, 136, 137
~~~

Für 090, 094, 095, 098, 099, 101, 126, 132, 133 und 134 fehlt zusätzlich eine verwertbare TCGdex-Cardmarket-Root-Referenz. Sie bleiben als UNMATCHED sichtbar. Die übrigen 69 offenen Drucke bleiben NEEDS_REVIEW. Kartenname, Bild und vorhandener Cardmarket-Preis belegen weder Kartennummer noch Finish und wurden nicht als Ersatz verwendet.

## Android-/Web-Parität

Die beiden lokalen Mapping-Dateien wurden direkt verglichen:

- Android: DeltaHerrschaftCardmarketMapping.kt
- Web: lib/dlr-cardmarket-mapping.ts

Beide enthalten exakt 62 identische collectorNumber: productId-Paare und dieselben zehn UNMATCHED-Kollektornummern; es gibt keinen nur auf einer Seite vorhandenen verifizierten Datensatz. Jede verifizierte Zuordnung ist auf Normal / Standard begrenzt. Die 79 offenen DLR-Drucke erhalten in beiden Clients keine fallback-basierte Preiszuordnung.

## Ausgeführte Prüfungen

| Prüfung | Klassifikation | Ergebnis |
| --- | --- | --- |
| DLR-Auditskript gegen öffentliche Katalog-/Preisdownloads | AUTOMATED / EXTERN LESEND | PASS — 141/141, 62 verifiziert, 79 offen, keine neue sichere Zuordnung |
| Android DeltaHerrschaftCardmarketMappingTest | AUTOMATED | PASS — 2/2 |
| Web tests/delta-herrschaft.test.ts | AUTOMATED | PASS — 5/5 |
| Lokaler Android-/Web-Paarvergleich | AUTOMATED | PASS — 62/62 identisch, keine einseitige Zuordnung |

## Unveränderte Blocker

1. Die 79 DLR-Drucke brauchen einen rechtmäßig nutzbaren, druckspezifischen Nachweis (Kartennummer und Finish/Variante) oder eine einzeln dokumentierte, reproduzierbare Produktseite mit denselben Merkmalen.
2. Eine Preisangabe in Cardmarket ist kein Identitätsnachweis. Die rechtliche Freigabe für dauerhafte externe Preisanzeige ist separat zu prüfen.
3. Für die übrigen lokalen statischen Katalogkarten existiert kein lokales druckspezifisches Cardmarket-Mapping. Das ist bewusst keine Behauptung, dass keine Preise existieren.
4. Pocket-, Promo-, Holo-, Reverse-, Stamped- und Sonderdruckvarianten dürfen ohne konkrete Quellen nicht mit Normaldrucken zusammengeführt werden.

Release 29 bleibt nicht releasebereit. Dieses Audit erweitert den Nachweis, ohne die bestehenden Preis-Sicherheitsgrenzen zu lockern.
