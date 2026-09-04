# DomiDex – Karten-Datenperformance, 03.09.2026

## Messaufbau

Android-Studio-Emulator `Medium_Phone`, Android 36, x86_64, 2 GB RAM,
Software-Grafik SwiftShader; **keine Smartphone-Messwerte**.
Verglichen wird die vor diesem Performance-Pass gebaute APK mit dem aktuellen
Arbeitsstand, nicht ein früherer Git-Release. Vorher vorhandene Änderungen bleiben erhalten.

Der ursprüngliche Emulator hing in EGL/`eglSwapBuffers` mit ANR. Diese ersten
Startwerte wurden verworfen. Der Emulator wurde ohne Löschen seiner Daten neu gestartet.
BEFORE_STABLE und AFTER-Messungen nutzen denselben echten, lokal importierten
Cardmarket-Katalog sowie TCGdex-Netzwerkdaten. Kein Preis wurde für die Messungen erfunden.

Für UI-Tests wurde im zunächst leeren lokalen Emulator-Bestand über Set-Auswahl
eine Owei-Karte aus „Prismatische Entwicklungen“ hinzugefügt (angezeigter realer
Trendpreis beim Test: 0,03 €). Das Smartphone wurde nicht verändert.

## Nachgewiesene Bremsen und Änderungen

- Jede neue Kataloginstanz lud dieselben Set-/Karten-/Variantendaten erneut.
  Jetzt teilen sich Scanner, Auswahl und Binder einen begrenzten Metadaten-Cache.
- Set + Sammlernummer verwendete wiederholte Listenfilter. Jetzt gibt es je
  geladenem Set eine Map auf der vorhandenen normalisierten Nummernidentität.
  Mehrdeutige Nummern bleiben Listen von Kandidaten, nicht überschreibbare Einzelwerte.
- Bei PRE erforderte die sichere Cardmarket-Expansionsbrücke 145 Namensabfragen.
  Preis-JOINs laufen erst für die begrenzte Trefferliste. Unveränderte Filter,
  Sortierung und Preise werden gegen die bisherige SQL getestet.
- Ein zusätzlicher deckender Namensindex erlaubt den Filterlauf auf schmalen
  Produkt-ID-/Namensdaten. Room-Migration 9→10 führt ausschließlich `CREATE INDEX`
  aus. Der Migrationstest bestätigt unveränderte Tabellen und sämtliche gespeicherten Felder.
- Wiederholte Abfragen werden zusammengefasst/gecacht. Die gemessenen 7.733
  PRE-Ergebniszeilen passen in das Limit von 8.192 Zeilen und 256 Suchschlüsseln.
  Ein zunächst kleineres 4.096-Zeilen-Limit verdrängte sich selbst und wurde verworfen.
- Existiert für den exakt identifizierten Kartennamen kein kompatibles
  Preisprodukt, entfällt die nutzlose Suche nach allen übrigen Set-Namen.
  Das Ergebnis bleibt derselbe unbepreiste Kandidat; keine gelockerte Erkennung.
- Binder-Anzahl verwendet COUNT statt Laden/Sortieren sämtlicher Referenzen;
  Seiten werden linear aufgeteilt; Portfolio-Transformationen laufen im Hintergrund.
- Namenssuche wartet 250 ms auf Eingabepause, bricht alte Eingabejobs ab und
  behält das bisherige Limit von 20 Treffern. Preisverlaufs-Flows werden je
  Karten-/Druckidentität wiederverwendet.

## Startvorbereitung, Cache und Korrektheit

Kritisch ist nur die vorhandene lokale Initialisierung. Der bestehende
Ladezustand zeigt „DomiDex wird vorbereitet…“ ohne künstlichen Prozentfortschritt
oder zusätzliche Pflicht-Wartezeit. Im Hintergrund wird ausschließlich der
kompakte Setindex geladen. Netzwerkfehler verhindern keine lokale Bedienung.

Metadaten: 1 Setindex, höchstens 24 Setlisten, 512 Detaildatensätze, jeweils
15 Minuten Gültigkeit; manuelle Binder-Aktualisierung leert diese Caches.
Gleichzeitige Abrufe desselben Schlüssels teilen die Arbeit; abgebrochene/fehlgeschlagene
Ladevorgänge werden nicht als erfolgreiche Leerantwort gespeichert.

Preis-Suchergebnisse sind Rohdaten, nicht die abschließend ausgewählte Variante.
Sie werden bei Änderungen an `catalog_cards` oder `raw_price_snapshots` sowie
bei neuer erfolgreicher Importrevision verworfen. Nutzerbestand/Mengen/Zustände
werden nicht in diesem Cache gehalten. Normal/Holo/Reverse und Karten-IDs bleiben getrennt.

Bewusst kein Vorladen sämtlicher Karten, Bitmaps oder Firebase-Sammlungen.
Vorhandenes Lazy-Image-Loading bleibt erhalten; keine neue Offline- oder Sync-Architektur.

## Messergebnisse

Messphasen `BEFORE_STABLE` / `AFTER_FINAL` und `BEFORE_UI` /
`AFTER_FINAL_UI_VERIFIED`, Logcat-Tag `DomiDexPerf`. Zeiten in Millisekunden,
außer anders angegeben. Netzwerkzeiten sind Einzelmessungen, keine zugesicherte
Dienstgeschwindigkeit. UI-Zeiten enthalten Animation und Abfrageintervall des
Testtreibers; sie sind keine isolierten Frame-Benchmarks.

| Ablauf | Vorher | Nachher |
|---|---:|---:|
| Cold Start bis erste Activity-Anzeige, Median aus 3 Systemmessungen | 4.322 | 4.085 |
| Activity-Start bis echter nutzbarer Home-Inhalt (UI-Test) | 5.539 | 4.688 |
| Laufende App aus Hintergrund zurückholen, System-Wartezeit, Einzelmessung | 33 | 186 |
| Setindex, erster Netzwerkabruf | 1.538 | 1.101 |
| Set + Nummer, erster Set-/Detail-Netzwerkabruf | 184 | 762 |
| Set + Nummer, warm, Median aus 5 Aufrufen | 3,44 | 2,00 |
| Set + Nummer aus neuer Ansicht/Kataloginstanz | 298 | 1,39 |
| Manuelle PRE-Korrektur inklusive erster Preis-Expansionsprüfung | 6.623 | 6.144 |
| Dieselbe manuelle Korrektur wiederholt | 6.263 | 160 |
| Namenssuche „Pikachu“, erster Aufruf, 20 Treffer | 48,96 | 32,97 |
| Wiederholte Namenssuche, zwei Aufrufe | 44,21 / 53,51 | 1,10 / 0,79 |
| Binder-Metadaten einschließlich erster 9 Druckdetails | 731 | 612 |
| Dasselbe aus neuer Binder-Kataloginstanz | 700 | 0,33 |
| Erste 9 Druckdetails wiederholt | 456 | 0,20 |
| Binder-Referenzanzahl, erster Aufruf | 18,49 | 1,04 |
| Binder öffnen, erster / zweiter UI-Aufruf | 1.827 / 1.337 | 1.761 / 1.170 |
| Erste Kartenseite bis sichtbarer Karteninhalt, erster / zweiter Binderaufruf | 11.733 / 25.081 | 12.217 / 3.261 |
| 100 wiederholte Nummern-Lookups zusammen | 304 | 138 |
| Prozess-PSS vor → nach 100 Lookups, KiB | 170.233 → 171.745 | 185.732 → 189.368 |

**Keine pauschale Beschleunigung aller Erstzugriffe:** Der erste Netzwerkabruf
und die erste vollständige Preis-Expansionsprüfung sind weiterhin spürbar;
die erste Binder-Kartenseite wurde nicht schneller. Der klare Gewinn liegt bei
wiederholter Korrektur, Ansichtswechseln und gemeinsam genutzten Metadaten.
Die Erstabgleich-Latenz ist noch nicht auf dem gewünschten Sofort-Niveau.

PSS ist eine Momentaufnahme des gesamten Debug-Prozesses einschließlich Laufzeit,
nicht ausschließlich Cache-Speicher. Der Nachherwert liegt rund 17 MiB höher.
Kein Bitmap-Preload; harte Cachegrenzen werden getestet. Ein Langzeit-Leaktest
oder ein Performanceversprechen für alle Sets wurde nicht daraus abgeleitet.

Cold-Start-Rohwerte: vorher 4.322 / 4.322 / 4.474 ms; nachher
3.850 / 4.085 / 4.650 ms. Gemessen mit `am start -S -W`.
Der Warm-Resume-Einzelwert (`am start -W`) ist keine belastbare Aussage über
eine Beschleunigung: keine Prozessinitialisierung, kein UI-Fertigsignal, deutliche
Emulatorstreuung. Er wird ausdrücklich nicht als Verbesserung dargestellt.

## Testergebnis

- 77 JVM-Tests erfolgreich: Caches, Stornierung, Invalidierung, identische
  manuelle Kandidaten/Preise/Entscheidungen, Binder-Slots/-Identitäten,
  Druckvarianten, Portfolio, Owner- und Sync-Regeln.
- 21 Emulator-Datenbanktests erfolgreich: 42 Vergleichskombinationen der alten
  und neuen Such-SQL, echte Tabelleninvalidierung, Preiswechsel, additive Migration
  9→10, vorhandene Migrationen und Owner-/Outbox-/Persistenzregressionen.
- 12 Emulator-Scannerregressionen erfolgreich: lokales OCR mit statischen
  Testbildern, Kontur/EXIF/Normalisierung/Abweisung, exakte PRE- und SVP-173-Suche.
- Echter Katalog-Performance-Test erfolgreich, keine ausgedachten Treffer/Preise.
- UI-Test erfolgreich: echte Startoberfläche, zweimal Binder öffnen und erste
  Kartenseite anzeigen, Scanner öffnen. Der neue Test wurde nach einem Timeout
  am vorhandenen Erststart-Kamerahinweis so korrigiert, dass er diesen explizit
  bestätigt. Keine entsprechende Produktionsänderung und kein Foto-Upload.
- Debug- und AndroidTest-Build erfolgreich. Lint: 0 Fehler, 131 Warnungen, 1 Hinweis;
  kein pauschales „warnungsfrei“. `git diff --check` für Änderungen sauber.

## Grenzen

- Ein bisher ungeöffnetes Set benötigt weiterhin seinen ersten Netzwerkabruf.
- Für Sets ohne hinterlegte verifizierte Cardmarket-Brücke bleibt der erstmalige
  Evidenzvergleich erforderlich. Es werden keine Expansions-IDs geraten.
- Physischer Smartphone-Test: **PENDING**, durch Betreiber.
- Ein neuer Live-Zwei-Geräte-Firebase-Test ist nicht Bestandteil der Messung;
  lokale Sync-/Owner-/Persistenzregressionen werden separat getestet.
- Kein Commit, Push, Release oder Deployment in diesem Run angefordert.

## Geänderte Dateien dieses Performance-Passes

Produktiv (unter `app/src/main/java/com/dominik/cardvault/`):

- `MainActivity.kt`
- `data/RoomCollectionRepository.kt`
- `data/CatalogQueryCache.kt` (neu)
- `data/catalog/PokemonVisualCatalog.kt`
- `data/catalog/CatalogMemoryCache.kt` (neu)
- `data/local/CardVaultDao.kt`
- `data/local/CardVaultDatabase.kt`
- `data/local/Entities.kt`
- `ui/CardVaultApp.kt`
- `ui/ScanProcessingQueue.kt` (nur nachgelagerte Preiskatalogabfrage)

Außerdem das generierte Schema
`app/schemas/com.dominik.cardvault.data.local.CardVaultDatabase/10.json`.
Keine Android-Manifest-, Kamera-/OCR-, Firebase-, Cloud-, Signing- oder Releaseänderungen
in diesem Pass. Bereits vorliegende Änderungen in solchen Dateien gehören nicht zu diesem Pass.

Neue Tests:

- `CatalogMemoryCacheTest`, `PokemonVisualCatalogCacheTest`, `CatalogQueryCacheTest`
- `ManualCandidateLookupPerformanceTest`
- `CatalogQueryCacheInstrumentedTest`, `CatalogSearchIndexMigrationInstrumentedTest`
- `CardDataPerformanceInstrumentedTest`, `CardDataUiPerformanceInstrumentedTest`

Git-Basis: Branch `codex/ui-v3`, HEAD `1be7adc`.
Arbeitsverzeichnis enthält diesen Pass und bereits vorher vorhandene Änderungen;
es wurde nicht pauschal bereinigt oder zurückgesetzt.
