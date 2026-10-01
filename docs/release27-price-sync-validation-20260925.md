# Release 27 – Preisabdeckung, Synchronisation und Regression

Stand: 25.09.2026. Entwicklung auf dem bestehenden Arbeitsstand; kein Google-Play-Upload. Release 26 bleibt unverändert.

## Vollständiger Kataloglauf

Der eingefrorene öffentliche Datenbestand umfasst alle **144 physischen Sets des deutschen Setindex**, 19.443 heruntergeladene Karten und 20 lokale Ergänzungen: **19.463 Karten / 27.131 kanonische Druckplätze**. Elf Pocket-Sets sind absichtlich ausgeschlossen. Alle 19.731 Downloadaufgaben abgeschlossen, keine verbleibenden Netzwerkfehler. Es handelt sich um eine vollständige Daten-/Resolverprüfung, nicht um 27.131 manuelle Bildschirmtests.

Gleicher Snapshot vor/nach den Korrekturen: 17.860 → **17.867 unabhängig bepreiste Druckplätze**; exakte deklarierte Verweise 17.896 → **17.903**. Sieben bestätigte Classic-Zuordnungen kamen hinzu. 9.227 Slots bleiben ohne ausreichend belegten Quellenverweis, ein Slot ohne Mapping. 20 fehlende Upstream-Details sind die bekannten lokalen RGB-/Promo-Ergänzungen und keine fehlgeschlagenen Downloads.

Der offizielle Preisdownload enthält auch tatsächlich fehlende Trendwerte (beispielsweise blaues RGB-Mew). Ein existierendes Verkaufsangebot garantiert keinen Trendwert im verwendeten Download. Fehlende Werte werden nicht erfunden, aus anderen Varianten übernommen oder über Kartennamen geraten.

### Grenzen der Identitätsprüfung

Von 8.024 Karten ohne verwertbare druckspezifische ID haben 7.379 zwar eine Root-ID; bereits darin gibt es 471 Produkt-ID-Kollisionen über 989 unterschiedliche Karten. 5.748 Details liefern nur einen generierten Druck. Der Cardmarket-Produktdownload besitzt keine Collector-/Finish-/Variantenfelder. Ein allgemeiner Root-ID-/Namens-/Reihenfolge-Fallback wäre daher unsicher. Beispiele: zehn verschiedene ex11-Karten teilen Root-ID 276798; sechs xy8-Karten teilen 286307. Auch Stempel-/Jumbo-/Goldprodukte dürfen nicht für normale Drucke herangezogen werden.

## Umsetzung

- Preisauflösung vorhandener Karten nicht mehr auf das Jubiläumsset beschränkt; exakte Set-/Karten-/Nummern-/Druckidentität bleibt erforderlich.
- Karten oberhalb der gedruckten Hauptsetgröße bleiben zulässig. Das historische ME04-Masterformat wird nur über seine gepflegte exakte Identität akzeptiert.
- Eindeutige Karte auch ohne Trendpreis hinzufügbar, dann ausdrücklich unbepreist.
- Nur ohne echten positiven Trendpreis: vorhandene Cardmarket-Vergleichswerte auswählbar, ohne Vorauswahl und mit ausdrücklicher Bestätigung. Keine freie Preiseingabe und keine Speicherung als Trendhistorie.
- Normal/Holo und Reverse verwenden ihre jeweils passenden Preisfelder. Wechsel der Kartenausgabe darf keinen vorherigen Preis übernehmen.
- Ein später verfügbarer echter Trendpreis hat immer Vorrang. Vergleichswerte sind in Sammlung, Binder und Portfolio gekennzeichnet.
- Zustimmung/gewählter Wert in separaten ownergeschützten Dokumenten `users/{uid}/collectionPriceSelections/{entrySyncId}`. Bestehende v26-Kartendokumente bleiben unverändert; alte Clients können die neue Auswahl nicht durch Vollschreiben entfernen.
- Room-Schema 13 additiv, Backup-Kompatibilität und Lösch-/Eigentümerwechsel geprüft.

## Gemessene technische Verbesserungen

- Binderübersichten lesen keine vollständige Preisgeschichte mehr; Gruppierung erfolgt einmal statt pro Binder. Isolierte Messung: 48 Karten, 12 Sets, 1.920 Historienzeilen; Übersicht 0 Historienabfragen statt 1 beim Portfolio. Beide Übersichtsdatenflüsse gemeinsam 32,1 ms im Test. Dies ist kein allgemeiner prozentualer Geschwindigkeitsnachweis.
- Bereits während des Pulls bestätigte übergeordnete Karteneinträge verursachen keinen unnötigen erneuten Sync-Backoff für die Preisauswahl. Konflikt-/Fehlerbehandlung bleibt erhalten.
- OCR-Orientierungsvorbereitung: gleiche 14 Eingaben mit pixelgleichen Ergebnissen, 18 → 15 Bitmap-Erzeugungen je Orientierung. Keine Verringerung der Auflösung, Evidenz oder Erkennungsschwellen.
- Ein tatsächlicher Fehlhinweis durch das deutsche Wort „UND“ wurde im schwachen Setcode-/Einzelzahlpfad behoben. Echte gebundene UND-Kartenangaben bleiben erhalten.
- Eine generell schnellere oder genauere End-to-End-Erkennung ist damit **nicht** bewiesen. Die bisherigen Emulator-Messgrenzen stehen in `docs/scanner-v27-performance-20260924.md`.

## Prüf- und Bereitstellungsstand

Abschließende Build-/Test-/Deployment-Ergebnisse werden nach Ausführung hier ergänzt. Nur Emulator; Xiaomi und Google Play bleiben unberührt. Keine Commit-/Push-Aktion.

R8 bleibt aktiv. Für sechs gestrippte Herstellerbibliotheken fehlen passende Originalsymbole weiterhin. Nur nachweislich zur neuen AAB passende Symbole dürfen archiviert werden; eine vollständig warnungsfreie Native-Abdeckung wird nicht behauptet.

## Abdeckung pro Set

„Belegt bepreist“ ist die strenge unabhängige Snapshotprüfung. Lokale gepflegte Ergänzungen ohne Upstream-Detail werden darin nicht als unabhängig verifiziert gezählt, auch wenn ihr reales Preisprodukt bekannt ist. Die Differenz ist daher nicht automatisch die Zahl fehlender UI-Preise.

| Set-ID | Set | Karten | Druckplätze | Belegt bepreist |
|---|---|---:|---:|---:|
| 30th | 30 Jahre | 161 | 161 | 158 |
| 30th-c | 30 Jahre: Klassische Sammlung | 30 | 30 | 30 |
| me05 | Dunkelnacht | 120 | 194 | 194 |
| me04 | Wachsendes Chaos | 122 | 198 | 198 |
| me03 | Optimale Ordnung | 124 | 203 | 202 |
| me02.5 | Erhabene Helden | 295 | 473 | 473 |
| me02 | Fatale Flammen | 130 | 214 | 214 |
| me01 | Mega-Entwicklung | 188 | 310 | 308 |
| mee | Mega-Entwicklung Energie | 8 | 16 | 16 |
| mep | MEP Black Star Promos | 106 | 106 | 88 |
| sv10.5b | Schwarze Blitze | 172 | 252 | 252 |
| sv10.5w | Weiße Flammen | 173 | 253 | 253 |
| sv10 | Ewige Rivalen | 244 | 409 | 409 |
| sv09 | Reisegefährten | 190 | 333 | 329 |
| sv08.5 | Prismatische Entwicklungen | 180 | 280 | 280 |
| sv08 | Stürmische Funken | 252 | 417 | 410 |
| sv07 | Stellarkrone | 175 | 300 | 300 |
| sv06.5 | Nebel der Sagen | 99 | 154 | 149 |
| sv06 | Maskerade im Zwielicht | 226 | 373 | 373 |
| sv05 | Gewalten der Zeit | 218 | 359 | 357 |
| sv04.5 | Paldeas Schicksale | 245 | 326 | 326 |
| sv04 | Paradoxrift | 266 | 428 | 428 |
| sv03.5 | 151 | 207 | 360 | 360 |
| sv03 | Obsidian Flammen | 230 | 406 | 405 |
| sv02 | Entwicklungen in Paldea | 279 | 455 | 455 |
| sv01 | Karmesin & Purpur | 258 | 444 | 0 |
| sve | Karmesin & Purpur Energie | 24 | 48 | 31 |
| svp | SVP Black Star Promos | 226 | 227 | 214 |
| swsh12.5 | Zenit der Könige | 160 | 272 | 272 |
| swsh12.5gg | Zenit der Könige Galar-Galerie | 70 | 70 | 70 |
| swsh12 | Silberne Sturmwinde | 215 | 357 | 357 |
| swsh12tg | Silberne Sturmwinde Trainer-Galerie | 30 | 30 | 30 |
| swsh11 | Verlorener Ursprung | 217 | 366 | 365 |
| swsh11tg | Verlorener Ursprung Trainer-Galerie | 30 | 30 | 30 |
| swsh10.5 | Pokémon GO | 88 | 145 | 145 |
| swsh10 | Astralglanz | 216 | 344 | 344 |
| swsh10tg | Astralglanz Trainer-Galerie | 30 | 30 | 28 |
| swsh9tg | Strahlende Sterne Trainer-Galerie | 30 | 30 | 30 |
| swsh9 | Strahlende Sterne | 186 | 310 | 310 |
| swsh8 | Fusionsangriff | 284 | 501 | 501 |
| swsh7 | Drachenwandel | 237 | 369 | 369 |
| swsh6 | Schaurige Herrschaft | 233 | 369 | 369 |
| swsh5 | Kampfstile | 183 | 306 | 306 |
| swsh4.5 | Glänzendes Schicksal | 73 | 119 | 119 |
| swsh4.5sv | Glänzendes Schicksal Glitzer-Tresor | 122 | 122 | 122 |
| swsh4 | Farbenschock | 203 | 345 | 344 |
| swsh3.5 | Weg des Champs | 80 | 80 | 0 |
| swsh3 | Flammende Finsternis | 201 | 356 | 356 |
| swsh2 | Clash der Rebellen | 209 | 360 | 360 |
| swsh1 | Schwert & Schild | 216 | 216 | 0 |
| swshp | SCSC Promokarten | 307 | 309 | 307 |
| sm115 | Verborgenes Schicksal | 69 | 69 | 0 |
| sm12 | Welten im Wandel | 271 | 271 | 0 |
| sm11 | Bund der Gleichgesinnten | 258 | 260 | 4 |
| sm10 | Kräfte im Einklang | 234 | 235 | 2 |
| sm9 | Teams Sind Trumpf | 196 | 197 | 2 |
| sm8 | Echo des Donners | 236 | 236 | 0 |
| sm7.5 | Majestät Der Drachen | 78 | 78 | 0 |
| sm7 | Sturm Am Firmament | 183 | 183 | 0 |
| sm6 | Grauen Der Lichtfinsternis | 146 | 147 | 2 |
| sm5 | Ultra-Prisma | 173 | 173 | 0 |
| sm4 | Aufziehen der Sturmröte | 125 | 125 | 0 |
| sm3.5 | Schimmernde Legenden | 78 | 78 | 0 |
| sm3 | Nacht in Flammen | 169 | 285 | 0 |
| sm2 | Stunde der Wächter | 169 | 169 | 0 |
| sm1 | Sonne & Mond | 172 | 172 | 0 |
| smp | SM Promokarten | 248 | 248 | 248 |
| sma | Verborgenes Schicksal Glitzer-Tresor | 94 | 94 | 0 |
| xy12 | Evolution | 113 | 113 | 0 |
| xy11 | Dampfkessel | 116 | 116 | 0 |
| xy10 | Schicksalsschmiede | 129 | 129 | 0 |
| xy9 | TURBOfieber | 126 | 126 | 0 |
| xy8 | TURBOstart | 165 | 165 | 0 |
| xy7 | Ewiger Anfang | 101 | 101 | 0 |
| xy6 | Drachenleuchten | 112 | 112 | 0 |
| xy5 | Protoshock | 164 | 164 | 0 |
| xy4 | Phantomkräfte | 124 | 124 | 0 |
| xy3 | Fliegende Fäuste | 114 | 114 | 0 |
| xy2 | Flammenmeer | 110 | 110 | 0 |
| xy1 | XY | 146 | 146 | 0 |
| xyp | XY Promokarten | 216 | 216 | 0 |
| xy0 | Willkommen in Kalos | 39 | 39 | 0 |
| xya | Gelbes-A-Alternativkarten | 6 | 6 | 0 |
| bw10 | Plasma-Blaster | 105 | 108 | 6 |
| bw9 | Plasma-Frost | 122 | 122 | 0 |
| bw8 | Plasma-Sturm | 138 | 138 | 0 |
| bw7 | Überschrittene Schwellen | 153 | 153 | 0 |
| bw6 | Hoheit der Drachen | 128 | 128 | 0 |
| bw5 | Erforscher der Finsternis | 111 | 111 | 0 |
| bw4 | Kommende Schicksale | 103 | 103 | 0 |
| bw3 | Königliche Siege | 102 | 104 | 4 |
| bw2 | Aufstreben der Mächtigen | 98 | 98 | 0 |
| bw1 | Schwarz & Weiß | 115 | 115 | 0 |
| bwp | SW Promokarten | 101 | 101 | 0 |
| hgss4 | Triumph | 103 | 196 | 185 |
| hgss3 | Unerschrocken | 91 | 172 | 166 |
| hgss2 | Entfesselt | 96 | 180 | 177 |
| hgss1 | HeartGold SoulSilver | 124 | 232 | 223 |
| col1 | Ruf der Legenden | 106 | 193 | 192 |
| hgssp | Promo HGSS | 25 | 25 | 25 |
| dp7 | Sturmtief | 106 | 201 | 0 |
| dp6 | Erwachte Legenden | 146 | 284 | 0 |
| dp5 | Majestätischer Morgen | 100 | 196 | 0 |
| dp4 | Epische Begegnungen | 106 | 208 | 0 |
| pl4 | Arceus | 111 | 204 | 189 |
| dp3 | Rätselhafte Wunder | 132 | 263 | 0 |
| pl3 | Ultimative Sieger | 153 | 293 | 293 |
| dp2 | Geheimnisvolle Schätze | 124 | 244 | 0 |
| pl2 | Aufstieg der Rivalen | 120 | 223 | 221 |
| dp1 | Diamant & Perl | 130 | 249 | 0 |
| pl1 | Platin | 133 | 254 | 254 |
| dpp | Promo DP | 56 | 58 | 0 |
| ex16 | EX Power Keepers | 108 | 113 | 0 |
| ex15 | EX Dragon Frontiers | 101 | 101 | 0 |
| ex14 | EX Crystal Guardians | 100 | 104 | 0 |
| ex13 | EX Holon Phantoms | 111 | 113 | 0 |
| ex12 | EX Legend Maker | 93 | 93 | 0 |
| ex11 | EX Delta Species | 114 | 116 | 0 |
| ex10 | EX Verborgene Mächte | 117 | 217 | 210 |
| ex9 | EX Smaragd | 107 | 196 | 192 |
| ex8 | EX Deoxys | 108 | 204 | 199 |
| ex6 | EX Feuerrot & Blattgrün | 116 | 218 | 218 |
| ex4 | EX Team Magma vs Team Aqua | 97 | 185 | 183 |
| ex3 | EX Drache | 100 | 188 | 186 |
| ex2 | EX Sandsturm | 100 | 193 | 193 |
| ex1 | EX Rubin & Saphir | 109 | 210 | 205 |
| exu | EX Verborgene Mächte Icognito | 28 | 28 | 28 |
| cel25cc | Celebrations Klassische Kollektion | 25 | 25 | 25 |
| cel25 | Celebrations | 25 | 25 | 25 |
| base5 | Team Rocket | 83 | 83 | 83 |
| neo4 | Neo Destiny | 113 | 113 | 112 |
| base3 | Fossil | 62 | 62 | 62 |
| neo3 | Neo Revelation | 66 | 66 | 66 |
| ecard3 | Skyridge | 182 | 326 | 322 |
| base2 | Dschungel | 64 | 64 | 63 |
| neo2 | Neo Entdeckung | 75 | 75 | 75 |
| ecard2 | Aquapolis | 186 | 338 | 337 |
| base1 | Grundset | 102 | 102 | 102 |
| neo1 | Neo Genesis | 111 | 111 | 111 |
| ecard1 | Expedition | 165 | 330 | 330 |
| dv1 | Drachengruft | 21 | 21 | 0 |
| g1 | Generationen | 117 | 117 | 0 |
| det1 | Meisterdetektiv Pikachu | 18 | 18 | 0 |
| basep | Wizards Black Star Promos | 53 | 54 | 1 |

### Lokale Nachweise

Snapshot: `C:/Users/Dominik/AppData/Local/Temp/domidex-v27-price-audit-20260925` mit `before-runtime` und `after-runtime`; detaillierte Karten-/Slot-CSV sowie JSON-Bericht. Ausgangs- und Endvergleich verwenden denselben eingefrorenen Datenbestand. Keine privaten Sammlungen ausgelesen oder verändert.

