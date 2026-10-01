# DomiDex v27 – Scanner-Entwicklungsprüfung

Entwicklungsstand, kein Release. Versionscode 26, Versionsname, Release-Signing,
R8 und Release-Pipeline bleiben unverändert. Ausschließlich normale Debug-APKs
für den Emulator wurden vom Nutzer freigegeben. Kein physisches Gerät verwenden.

**Zwischenstand vom 24.09.2026, noch kein Abschluss:** Der vollständige
90er-OCR-Vergleich, der erweiterte 34er-Vergleich, 21 Geometriefälle,
29 Kern-Instrumentation-Tests sowie 21 bestandene UI-Tests liegen vor.
Eine gezielte Wiederholung der unoptimierten Baseline relativiert den zunächst
beobachteten Treffergewinn; der erweiterte Vergleich zeigt keinen Median-Zeitgewinn.
Die gepaarten 45-s-Preview-Soaks und frühen Compose-basierten Galerie-/
Warteschlangenmessungen liegen ebenfalls vor: stabile Prüfzustände, aber noch
keine identifizierten Karten in diesen frühen UI-Läufen. Ein testweises
Verkleinern der OCR-Bilder wurde wegen Fehlablesungen
verworfen. Eine isolierte Pause der Live-Konturarbeit brachte ebenfalls keinen
belegten Nutzen und wurde vollständig zurückgenommen. Ein zusätzlicher
Galerielauf nach 20 s Warm-up scheiterte am globalen Analyse-Timeout.
Ein erneuter Kern-Regressionslauf deckte zwei konkrete Close-/Cleanup-Races auf.
Größenunabhängige Sitzungsiteration sowie serialisierter Abschluss bis zum
Einreihen des Cleanups beheben die reproduzierten Ursachen. Der jüngste
Kernlauf besteht mit **29 Tests**, einschließlich 512 Stressiterationen und
einem deterministischen Test der vorzeitigen Bitmapfreigabe. Der erste
Echtzeit-UI-Kontrolltest scheiterte an der Ergebnisbeobachtung. Mit korrigierter
rein testseitiger Accessibility-Beobachtung liegen jetzt sechs abwechselnde
Echtzeitläufe vor: optimiert 3/3, Baseline 2/3 mit sichtbarer korrekter
Araqua-Identität und weiterhin manueller Varianten-/Kandidatenentscheidung; ein
Baseline-Lauf scheitert am Analyse-Timeout. Nur die erfolgreichen Endzeiten
zeigen einen kleinen Median-Unterschied von rund 2,51 %, keine allgemein
belastbare Genauigkeits- oder Geschwindigkeitssteigerung.
Der zusätzliche Echtzeitlauf mit 20 s Warm-up besteht ebenfalls mit
verifizierter Araqua-Identität und Variantenmehrdeutigkeit. Die abschließenden
UI-Regressionen bestehen mit 21 Erfolgen und einem opt-in Skip, die abschließende
Offline-Regression mit 13 Erfolgen. Der Debug-/Unit-Build besteht mit
582 ausgeführten Erfolgen und einem Skip. Offen bleiben belastbare Belege
höherer allgemeiner Trefferquote und generell schnellerer fertiger Ergebnisse,
nicht die oben abgeschlossenen Testläufe. Es gibt keine Scanner-Fertigmeldung.
Alle unten genannten Leistungswerte bleiben ein Zwischenstand. Das Nutzerziel umfasst
**schneller ein fertiges Ergebnis und eine höhere Trefferquote** gegenüber dem
vorherigen Stand, nicht bloß eine schnellere Aufnahme oder eine frühere Anzeige.
Qualitäts- und Annahmeschwellen dürfen dafür nicht gelockert werden.

## Tatsächlicher Ablauf

CameraX Y-Kanal → gedrosselte Live-Kontur/Qualität → manueller oder optionaler
automatischer Capture → begrenzte Warteschlange (ein Analyse-Worker) →
JPEG/EXIF-Decodierung → vollständige Kartenkontur → Perspektivkorrektur →
Qualitätsprüfung → zwei geometrisch passende Orientierungen (beim manuellen
Ganzbild-Fallback vier) → Orientierungs-OCR → konservative Orientierungswahl →
vollständige ROI-OCR mit bestehendem exaktem Sitzungscache → Nummer/Set/Name →
Katalogreduktion → Varianten → Bildvergleich → Hybrid-Ranking/Knowledge →
ACCEPTED / AMBIGUOUS / REJECTED → Warteschlangen-Ergebnis.

Der manuelle Fallback bleibt ausdrücklich prüfpflichtig. Keine Aufnahme wird
aufgrund dieses Entwicklungsruns automatisch an ScanLab gesendet.

## Messmethodik und Grenzen

- Medium_Phone, Android 36, x86_64, 4 virtuelle CPU-Kerne, 2 GiB AVD-RAM.
- Simulierte Emulator-Rückkamera; kein Webcam-/Xiaomi-Zugriff.
- Dieselben Debug-Einstellungen und vorgesehenen Fixture-/Transformationsfälle
  vor/nach dem Patch. Die historische Baseline enthält noch keine
  `inputSha256`-Felder; bei allen 90 OCR-Paaren ist die Zuordnung nach
  Fixture/Fall/Wiederholung geprüft, die Bytegleichheit jedoch nicht durch
  beidseitig gespeicherte Eingabehashes unabhängig belegt. Das ist eine
  Messgrenze, keine implizit erfolgreiche Hashprüfung.
- Sechs öffentliche TCGdex-Katalogbilder; Herkunft und SHA-256 stehen im
  generierten `artifacts/scanner-v27/public-fixtures/manifest.json`.
- Normale Karten, eine normal/reverse verfügbare Karte, Full Art, besondere
  Illustration oberhalb des Hauptsets und eine englische Promo. Flache Bilder
  beweisen **keine physische Holo-/Reverse-Erkennung**.
- Reproduzierbare synthetische Beleuchtungs-, Lage- und Qualitätsvarianten;
  private Nutzerfotos werden weder benötigt noch archiviert.
- Live-Kontur und Normalisierung: Warmups, wiederholte Messungen,
  stabile Qualitäts-/Eckenwerte und Bild-Hashes.
- OCR: echte lokale ML-Kit-Verarbeitung mit unveränderten Zeitlimits. Die
  Katalogentscheidung im Benchmark nutzt einen ausdrücklich begrenzten lokalen
  Fixture-Katalog und die echten Ranking-/Bildvergleichs-/Entscheidungsfunktionen.
  Sie ist **kein Nachweis der Erfolgsrate im gesamten Live-Katalog**.
- Historisches `numberCorrect` vergleicht nur den normalisierten
  Collector-Zähler beziehungsweise `localId`, **nicht den gedruckten Nenner**.
  Die Werte 50/90, 54/90 beziehungsweise 13/18 sind deshalb keine Quote
  vollständig korrekt gelesener `Nummer/Gesamtzahl`-Paare. ACCEPTED-/REJECTED-
  Entscheidungen sind davon getrennte Kennzahlen. Historische Reportfelder
  werden nicht nachträglich zu einer strengeren Metrik umgedeutet; eine
  zusätzliche Prüfung des vollständigen Zahlenpaares muss explizit ausgewiesen
  werden.
- Die OCR-Gesamtzeit endet beim Analyzer-Ergebnis, einschließlich dessen
  Schließen. Die anschließende lokale Kandidatenbewertung hat eine eigene
  Messung. Kameraauslösung, Warteschlangenwartezeit, echter Katalog-/Netzzugriff
  und die endgültige UI-Auslieferung sind nicht Bestandteil dieser OCR-Zeit.
  Die frühen Compose-basierten UI-Tests erreichen nur manuelle Prüfzustände
  ohne identifizierte Karte. Der spätere Echtzeit-Harness v2 belegt dagegen
  sichtbare richtige Araqua-Identität und AMBIGUOUS-Endzustand; seine separat
  gemessenen Endzeiten sind unten ausgewiesen. OCR-Latenz allein darf nicht
  als vollständige erfolgreiche Scan-Latenz verkauft werden.
- 90 OCR-Messungen sind sechs Bilder × fünf Varianten × drei Wiederholungen,
  nicht 90 unabhängige reale Karten. Geometriefälle haben je zwei Warmups und
  zwölf Messungen. p95 bei kleinen Fallgruppen liegt nahe/am Maximalwert und
  ist kein belastbares Produktions-Perzentil.
- Prozess-CPU kann durch mehrere Threads größer als die verstrichene Zeit sein.
  PSS-/Heap-Snapshots sind keine vollständigen Spitzenverbrauchsprofile.
- Temporäre OCR-Bitmapbytes messen vorbereitete Bilder, nicht gleichzeitig
  gehaltenen Speicher. OpenCV-Konvertierungen wurden im Code nachvollzogen,
  nicht durch einen vollständigen nativen Allocation-Tracer gemessen.
- Die bisherigen UI-Stufen sind keine echten Messgrenzen: `PREPROCESSING`
  umfasst bereits OCR. Neue, standardmäßig ausgeschaltete Messbeobachtung trennt
  Normalisierung, OCR-Vorbereitung, OCR-Wartezeit, Parsing und Deskriptoren.
- Geometrie-Fingerprints umfassen Eingaben, Konturen, Qualitätswerte und die
  Pixel-Hashes der ersten Ausgabe pro Fall. Wiederholungen prüfen strukturelle
  Ausgabegleichheit, nicht erneut jeden Pixel-Hash. Die scharfen Textur-Fälle
  müssen tatsächlich normalisierte Bitmaps und beide Analyseorientierungen
  liefern; abgewiesene Leer-/Weichbilder allein wären kein Warp-Nachweis.
- Der Preview-Soak misst UI-/Compositor-`Window.FrameMetrics`, nicht direkt die
  Kamera-Surface-FPS. Verpasste FrameMetrics-Meldungen sind nicht mit verworfenen
  Kamerabildern gleichzusetzen. CPU-, PSS-, Java-/Native-Heap- und Threadproben
  enthalten auch den Messaufwand; Fünf-Sekunden-Snapshots sind kein Peak- oder
  Langzeit-Leak-Nachweis. Es gibt keine erfundene Emulator-FPS-Freigabegrenze.

## Befunde und bewusst erhaltene Grenzen

1. CLAHE und die dazugehörige Glättung wurden auch dann berechnet, wenn schon
   die unveränderte primäre Kontur genügte.
2. Qualitätsprüfung: 8.690 Stichproben verursachten 17.380 einzelne `Mat.get`
   JNI-Aufrufe und kleine Arrays. Zeilenweises Lesen kann dieselben Werte in
   derselben Reihenfolge mit 220 Aufrufen und zwei Zeilenpuffern auswerten.
3. OCR-Ergebnisse wurden bereits innerhalb einer Aufnahme exakt gecacht.
   Die später verworfenen OCR-Bilder wurden jedoch vorher trotzdem erzeugt.
4. Die gründliche OCR ist der Hauptengpass. Bereits die Baseline kann im
   Emulator das 5-s-Orientierungs- und 9-s-Voll-OCR-Zeitfenster ausschöpfen.
5. Keine frühe automatische Annahme nur anhand erster Nummer-/Set-Treffer:
   weitere ROIs/Bildvergleich können Widersprüche entdecken.
6. Kein unsicherer Cross-Frame-OCR-Cache: Ein Kartenwechsel darf niemals die
   Identität der vorherigen Karte übernehmen. Bestehende Fingerprints,
   Duplikatschutz und Knowledge-Regeln bleiben bestehen.
7. CameraX nutzte bereits KEEP_ONLY_LATEST, Y-only-Auswertung, wiederverwendete
   Puffer, ca. 140-ms-Drosselung und einen einzelnen Analyse-Thread. Diese
   Architektur wurde nicht ersetzt.
8. Beobachteter Altzustand: Der automatische UI-Aufruf verwendet noch
   `quality.level == GREEN`, obwohl `captureEligible` berechnet wird. Dieser
   Performance-Patch verändert diese Capture-Policy nicht. Manueller Auslöser
   bleibt unabhängig. Dies ist kein neu eingeführter Zustand.
9. Keine neue Gamma-/Schärfungskaskade: Der bestehende adaptive Kontrastpfad
   bleibt für schwierige Geometrie erhalten; Qualitätsschwellen bleiben gleich.

## Fast-vs-Deep: tatsächlich umgesetzt, bewusst nicht umgesetzt

Der schnelle Pfad bedeutet hier **weniger unnötige Vorarbeit bei identischer
fachlicher Prüfung**: CLAHE/Glättung erst beim bereits vorhandenen
Geometrie-Fallback, Qualitätsdaten zeilenweise statt pixelweise aus OpenCV lesen
und Pixelvorbereitung für bereits exakt gelesene OCR-Eingaben überspringen.
Der Cache gilt ausschließlich für dieselbe Aufnahme und berücksichtigt ROI,
Vorverarbeitung, Orientierung und Bildmaße. Auch leere gecachte Lesungen,
Evidenzplätze, Reihenfolge und Konsensgewicht bleiben erhalten. Noch benötigte
Ableitungen erhalten unveränderte Ausgangspixel; native OCR-Eingaben bleiben
bis zur tatsächlichen Fertigstellung geleast, auch nach einem Abbruch.

Es wurde **kein zweiter Erkennungspfad**, keine kürzere ROI-Liste und keine
vorschnelle ACCEPTED-Entscheidung eingeführt. Der bestehende gründliche Pfad
bleibt für alle nötigen Belege aktiv. 5-s-Orientierungs- und 9-s-Voll-OCR-Budget,
Varianten-/Kartenidentität, Mehrdeutigkeitsbehandlung und manuelle Prüfung
bleiben unverändert. Eine erste Nummer allein wäre zwar früher verfügbar,
könnte aber Set-/Namens- oder Artwork-Widersprüche verdecken; das ist kein
vertretbarer Geschwindigkeitsgewinn.

Der Baseline-Engpass bleibt reale ML-Kit-Arbeit auf dem Emulator. Weniger
Vorbereitungsaufwand kann innerhalb unveränderter Budgets mehr verwertbare
Lesungen ermöglichen. Die vier zunächst besseren Entscheidungen sind jedoch
**kein belastbarer kausaler Genauigkeitsgewinn**: Die unoptimierte APK erkannte
dieselben beiden Problemfälle im separaten Stabilitätslauf bereits sechsmal
korrekt. ML-Kit-Scheduling, Erwärmung und Host-Auslastung wirken ebenfalls auf
Zeitbudgets. Auch der erweiterte Vergleich hat identische Entscheidungen und
keinen Median-Zeitgewinn. Ein zusätzlicher Gewinn bei dunklen, kontrastarmen
oder unscharfen Fällen ist nicht belegt. Unsichere Bilder bleiben abgelehnt.

Eine kleinere OCR-Analyseauflösung wurde ausschließlich über Testparameter
isoliert untersucht und wegen Fehlablesungen **nicht übernommen**. Die normale
Analysebreite bleibt 1.200 Pixel. Weniger Pixel können kleine Setcodes und
Nummern verschlechtern; ein Zeitgewinn alleine rechtfertigt keine Übernahme.

Das gesonderte CPU-Experiment, Live-Konturarbeit während einer laufenden Analyse
zu pausieren, wurde **nicht übernommen**: kein belastbarer Zeit-/Treffergewinn
und ein im Review entdecktes automatisches Dublettenrisiko nach Wiederaufnahme.
Die experimentellen Änderungen in `CameraScanner.kt` und `CardVaultApp.kt`
sowie die beiden ausschließlich dafür angelegten Tests wurden vollständig
zurückgenommen. Die drei ursprünglichen Produktionsoptimierungen bleiben;
Aufnahme-/Entscheidungsregeln wurden nicht für diesen Versuch gelockert.

## Scannerdateien des bisher vermessenen Patches

Die folgende Liste grenzt die Änderungen dieses Runs von schon vorhandenen,
unabhängigen Working-Tree-Änderungen ab. Pfade sind relativ zur Repositorywurzel.

| Datei | Zweck |
| --- | --- |
| `app/src/main/java/com/dominik/cardvault/ui/CameraScanner.kt` | Live-CLAHE/Glättung nur im unveränderten Fallback. |
| `app/src/main/java/com/dominik/cardvault/ui/CardScanPipeline.kt` | Dasselbe für Standbilder; identische Qualitätssamples über Zeilenpuffer. |
| `app/src/main/java/com/dominik/cardvault/ui/CardTextAnalyzer.kt` | Exakte Cachetreffer vor Bitmapvorbereitung nutzen; standardmäßig inaktive Messbeobachtung; größenunabhängige Sitzungsiteration und serialisierter Abschluss bis zur Cleanup-Einreihung. |
| `app/src/androidTest/java/com/dominik/cardvault/ScannerFastInputInstrumentedTest.kt` | Aktuelle lokale Einstiegsschritte, manueller Capture-/Galerie-Handoff und Messung eines öffentlichen Bilds durch die echte Warteschlange. |
| `app/src/androidTest/java/com/dominik/cardvault/ScannerRealtimeGalleryInstrumentedTest.kt` | Echtzeit-Galerietest ohne Compose-Testuhr mit frischer Accessibility-Beobachtung; prüft sichtbare Kartenidentität, echte Endzustände und Zeit bis zum Ergebnis. |
| `app/src/androidTest/java/com/dominik/cardvault/ScannerSoakInstrumentedTest.kt` | Emulator-Preview-Soak mit FrameMetrics, CPU, Speicher, Threads und Auto-aus-Assertions. |
| `app/src/androidTest/java/com/dominik/cardvault/ui/CameraQualityTest.kt` | Detektor im Test zuverlässig schließen. |
| `app/src/androidTest/java/com/dominik/cardvault/ui/ScannerGeometryBenchmarkInstrumentedTest.kt` | 21 deterministische Fälle, vollständige Geometrie-/Qualitätsfingerprints, Bild-Hashes und Wiederverwendungssequenz. |
| `app/src/androidTest/java/com/dominik/cardvault/ui/ScannerOcrBenchmarkInstrumentedTest.kt` | Echte lokale OCR öffentlicher Fixtures, begrenzter Kandidatenvergleich, Phasen-/Ressourcenmessung. |
| `app/src/androidTest/java/com/dominik/cardvault/ui/ScannerOcrCachePreparationInstrumentedTest.kt` | Pixel-/Evidenzgleichheit bei vollständigen und partiellen Cachetreffern; Orientierung, Maße, Ressourcen. |
| `app/src/androidTest/java/com/dominik/cardvault/ui/ScannerSessionIsolationInstrumentedTest.kt` | Reale lokale OCR über aufeinanderfolgende Identitäten, Abbruch während nativer Leases, 512 Close-/Cleanup-Stressiterationen und deterministisches Cleanup-/Shutdown-Rennen. |
| `scripts/scanner/Prepare-PublicFixtures.ps1` | Öffentliche Referenzbilder mit Herkunft und SHA-256 vorbereiten. |
| `scripts/scanner/Compare-Benchmarks.ps1` | Vollständigkeit, Duplikate, Fallmengen und verfügbare Hashes prüfen; Zeiten, Entscheidungen und Messgrenzen vergleichen. |
| `docs/scanner-v27-performance-20260924.md` | Dieser Entwicklungs-/Messbericht. |

Damit für den aktuellen Scannerstand: drei Produktionsdateien, acht Testdateien,
zwei Messskripte und dieser
Bericht. Keine Release-, Signing-, R8-, Firebase-, Datenmodell- oder
fachfremden UI-Änderungen gehören zu diesem Scanner-Patch.
Die Leistungsgrundmatrizen stammen von vor den zusätzlichen Close-Race-Fixes;
die jüngeren Kern-Regressionen sind separat ausgewiesen. Die neue Echtzeit-
Testdatei ist korrigiert; ihre erfolgreichen und fehlgeschlagenen Läufe sind
unten einzeln und getrennt von den OCR-Leistungsgrundmatrizen dokumentiert.
Die verworfene Busy-Pause-Untersuchung ist unten dokumentiert, gehört aber
nicht zum verbleibenden Patch. `ScannerLiveAnalysisPauseTest.kt` und
`ScannerLiveAnalysisPauseInstrumentedTest.kt` sind nicht mehr vorhanden.

### Verifizierte Artefaktidentitäten

Die folgenden SHA-256-Werte wurden nach dem letzten Debug-Build erneut aus
den vorhandenen Dateien gelesen. Sie unterscheiden die aktuelle App, den
Test-Harness, die unveränderte Vergleichs-App und das unberührte Release 26.

| Vorhandene Datei | SHA-256 |
| --- | --- |
| `artifacts/scanner-v27/verified-debug-20260924/app-debug.apk` | `D69072A4FA858E05D4E4E3146AB15AF66CD2C6DC784F7192D44F5C9B17627BAF` |
| `artifacts/scanner-v27/verified-debug-20260924/app-debug-androidTest.apk` | `C7F7AEA1C9806D1BE6856067227C06C509615BC61D6D34764E0B23317269227B` |
| `artifacts/scanner-v27/baseline/app-debug.apk` | `B17360EB1C5B76D655A819970AD47FE429FBC6C02CF96CC12DAFE1C64166AD4B` |
| `releases/google-play-v26-closed-alpha-2026-09-23/DomiDex-1.0-closed-alpha.5-v26-SIGNED.aab` | `079608E27BCB5D440252ED727E238B162B449F3DBA4B1C28986D4A4570D9B14B` |

Die archivierten Debug-App-/Test-APKs stimmen auch mit den zuletzt geprüften
Dateien unter `app/build/outputs/apk/debug/` beziehungsweise
`app/build/outputs/apk/androidTest/debug/` überein. Das sind ausschließlich
Emulator-Testartefakte, keine Release-APKs. Die Release-AAB wurde weder neu
gebaut noch verändert. Die Hashprüfung ist keine Veröffentlichung und kein
Nachweis einer zusätzlichen Release-Freigabe.

## Reproduktion

Öffentliche Fixtures mit `scripts/scanner/Prepare-PublicFixtures.ps1` erzeugen.
Nur `:app:assembleDebug`, `:app:assembleDebugAndroidTest` und gezielte
`:app:testDebugUnitTest`-Klassen ausführen. Debug-APKs auf **emulator-5554**
installieren; Fixtures in `cache/scanner-public-fixtures` des Debug-Pakets kopieren.

Instrumentation-Klassen:

- `ScannerGeometryBenchmarkInstrumentedTest`, Argumente
  `scannerBenchmark=true`, `benchmarkLabel=baseline|optimized`,
  `benchmarkRepeat=12`.
- `ScannerOcrBenchmarkInstrumentedTest`, Argumente
  `scannerBenchmark=true`, `scannerBenchmarkLabel=baseline|optimized`,
  `scannerFixtureLimit=6`, `scannerRepeats=3`,
  `scannerCases=clean,dim,low_contrast,rotated90,mild_blur`.
- `ScannerSoakInstrumentedTest`, Argumente `scannerSoak=true`,
  `scannerSoakLabel=baseline|optimized`, optional `scannerSoakMillis=45000`.
  Nur Emulator; Kameraerlaubnis wird dort vor dem Einstieg erteilt. Der Test
  entfernt den Window-Listener und beendet dessen HandlerThread im Cleanup.

Geometrie-JSON liegt im App-`files`-Verzeichnis, OCR-JSON im App-`cache`.
Lokale Kopien nach `artifacts/scanner-v27` übernehmen.
`scripts/scanner/Compare-Benchmarks.ps1` vergleicht beide vollständigen Läufe.
Benchmarkartefakte und heruntergeladene Bilder bleiben im bereits ignorierten
`artifacts`-Verzeichnis; keine privaten Rohscans im Repository.

## Vorläufiges Ergebnis des vollständigen 90er-Vergleichs

Quelle: `artifacts/scanner-v27/comparison.json`. Beide OCR-Reports sind explizit
vollständig (`expectedSamples=90`), die Stichprobenschlüssel und Geometriefälle
stimmen überein; keine Duplikate oder fehlenden Fall/Wiederholungs-Kombinationen
wurden akzeptiert. Die aus dem pausierten Lauf zusammengeführte Baseline wurde
nicht allein aufgrund vorhandener Zeilen als vollständig angesehen.

| Messwert | Baseline | Optimiert |
| --- | ---: | ---: |
| Analyzerzeit, alle 90 Fälle: Median / p95 / schlechtester Wert | 14,223 / 16,700 / 17,741 s | 13,353 / 14,532 / 15,040 s |
| Analyzerzeit, 57 Fälle mit OCR-Aufrufen: Median / p95 / schlechtester Wert | 15,696 / 16,788 / 17,741 s | 13,894 / 14,783 / 15,040 s |
| Prozess-CPU je Fall: Median / p95 / schlechtester Wert | 49,760 / 58,524 / 62,783 s | 46,537 / 51,083 / 52,595 s |
| Korrekter Collector-Zähler / `localId` (`numberCorrect`, Nenner nicht geprüft) | 50/90 | 54/90 |
| ACCEPTED / AMBIGUOUS / REJECTED im Fixture-Katalog | 35 / 15 / 40 | 39 / 15 / 36 |
| Falsch akzeptiert / technische Fehler | 0 / 0 | 0 / 0 |
| Vorbereitete temporäre OCR-Bitmaps, Median | 70 | 68 |
| Kumulierte vorbereitete OCR-Bitmapbytes, Median | 100.672.092 | 97.946.556 |
| PSS nach einem Fall: Median / größter beobachteter Wert | 240.653,5 / 270.795 KiB | 243.482,5 / 262.872 KiB |

Die Gesamtzeit enthält auch frühe Qualitätsablehnungen. Deshalb steht zusätzlich
die Teilmenge mit wirklicher OCR dabei. Die CPU-Zeiten summieren Prozess-Threads,
nicht Wartezeit für den Nutzer. PSS ist nicht durchgehend niedriger; aus diesen
Snapshots folgt weder ein pauschaler RAM-Gewinn noch ein Leak-Ausschluss.

| Fall, jeweils 18 Messungen | Analyzer-Median vorher → nachher | ACCEPTED / AMBIGUOUS / REJECTED vorher → nachher |
| --- | ---: | --- |
| `clean` | 16,132 → 14,127 s | 13 / 3 / 2 → 15 / 3 / 0 |
| `dim` | 0,347 → 0,141 s | 3 / 3 / 12 → 3 / 3 / 12 |
| `low_contrast` | 0,180 → 0,140 s | 3 / 3 / 12 → 3 / 3 / 12 |
| `rotated90` | 16,190 → 14,001 s | 13 / 3 / 2 → 15 / 3 / 0 |
| `mild_blur` | 6,446 → 6,293 s | 3 / 3 / 12 → 3 / 3 / 12 |

Die vier verbesserten Ergebnisse betreffen ausschließlich `base1-58`: zwei
`clean`- und zwei `rotated90`-Wiederholungen wechselten von
`orientation_evidence_insufficient`/REJECTED zu korrektem `058/102`/ACCEPTED.
Im Vergleich wurde keine entgegengesetzte Entscheidungsverschlechterung
beobachtet. Der nachfolgende Stabilitätslauf zeigt jedoch, dass bereits die
unoptimierte APK diese Fälle erfolgreich erkennen kann. Die vier zusätzlichen
Treffer dieses einzelnen Durchlaufs dürfen deshalb **weder als belastbare
kausale Verbesserung noch als allgemein höhere Echtkarten-Trefferquote**
dargestellt werden.

Alle **42 Geometrie-Stufenergebnisse** (21 Fälle × Detektor/Normalisierung)
sind vor/nach dem Patch gleich, inklusive verfügbarer normalisierter
Ausgabebild-Hashes. `sharp_texture` und `sharp_texture_90` liefern in beiden
Ständen echte normalisierte Bilder statt einer bloßen Qualitätsablehnung.
Die Detektorfolge `good → dark → low_contrast → blank → good` stimmt jeweils
mit frischen Instanzen und zwischen beiden Ständen überein. Die wiederverwendeten
Fallback-Puffer haben in diesen Fällen keine alten Konturen übernommen.

## Erweiterter Vergleich und Baseline-Stabilität

Quelle: `artifacts/scanner-v27/comparison-extended.json`. Beide Reports sind
vollständig, alle 34 Stichprobenschlüssel stimmen überein und hier sind auch
**34/34 Eingabe-SHA-256-Paare identisch**. Die 17 Fallarten sind `room_light`,
`shadow`, `overexposed`, `warm`, `cold`, `low_resolution`, `noise`, `motion_blur`,
`small`, `off_center`, `tilted`, `perspective`, `reflection`, `rotated180`,
`rotated270`, `manual_whole` und `blank`, jeweils zwei Messungen.

| Messwert, 34 Messungen | Baseline | Optimiert |
| --- | ---: | ---: |
| Analyzerzeit: Median / p95 / schlechtester Wert | 13,285 / 14,761 / 14,839 s | 13,474 / 14,633 / 14,809 s |
| ACCEPTED / AMBIGUOUS / REJECTED | 26 / 0 / 8 | 26 / 0 / 8 |
| Falsch akzeptiert / technische Fehler | 0 / 0 | 0 / 0 |

Keine verglichene Recognition-Ausgabe änderte sich. Der Median ist optimiert
rund 0,189 s beziehungsweise 1,4 % höher, während p95 und schlechtester Wert
geringfügig niedriger liegen. **Dieser Datensatz belegt keinen
Median-Geschwindigkeitsgewinn und keine höhere Trefferquote.** Die vollständige
Grundmatrix und der erweiterte Vergleich dürfen nicht selektiv nur anhand
günstiger Werte zusammengefasst werden.

`artifacts/scanner-v27/baseline-stability.json` wiederholt mit der
**unoptimierten** APK ausschließlich `base1-58`, `clean` und `rotated90`, je
dreimal. Der Report ist vollständig: **6/6 ACCEPTED**, jeweils korrekte Nummer
`058/102`, keine falsche Annahme und kein technischer Fehler. Damit ist der
anfängliche Unterschied von vier Entscheidungen nicht stabil reproduziert;
ein vom Patch verursachter Trefferquotenzuwachs ist bislang **nicht bewiesen**.
Die geforderte Kombination aus schnellerem fertig sichtbarem Ergebnis und
belastbar höherer Trefferquote bleibt ein offenes Erfolgskriterium.

## Preview-Soak: stabil, aber kein FPS-/CPU-Gewinn belegt

Quellen: `artifacts/scanner-v27/baseline-soak.json` und
`artifacts/scanner-v27/optimized-soak.json`. Beide Reports sind vollständig,
die Scanner-/Auto-aus-Assertions bestanden, der Window-Listener wurde entfernt
und sein HandlerThread sauber beendet.

| Messwert | Baseline | Optimiert |
| --- | ---: | ---: |
| Beobachtete Dauer | 45,036 s | 45,035 s |
| Prozess-CPU während des Intervalls | 12,210 s | 13,967 s |
| Erfasste Window-Frames / verpasste Listener-Meldungen | 2 / 0 | 2 / 0 |
| PSS erste → letzte Intervallprobe | 229.834 → 244.475 KiB | 229.770 → 244.045 KiB |
| Java-Heap erste → letzte Intervallprobe | 9.215.488 → 13.612.400 Bytes | 9.494.656 → 14.338.928 Bytes |
| Native-Heap erste → letzte Intervallprobe | 31.242.656 → 35.383.488 Bytes | 31.163.040 → 35.531.744 Bytes |
| Prozessthreads erste → letzte Intervallprobe | 79 → 113 | 77 → 111 |

Nur zwei Window-Frame-Meldungen je Lauf sind **keine belastbare
Preview-FPS-/Jank-Messung**. Beide Meldungen überschritten jeweils die vom
System gemeldete Deadline; daraus folgt weder die Bildrate der separat
gerenderten Kameravorschau noch eine Aussage über sämtliche Preview-Frames.
Die gemessene Prozess-CPU ist optimiert in diesem Lauf höher, nicht niedriger.
Aus diesen einzelnen 45-s-Läufen darf kein CPU-Gewinn behauptet werden.
Anlaufende Kamera-/Systemthreads und Speicheranstieg sind sichtbar; der kurze
Lauf beweist weder einen dauerhaften Leak noch dessen Abwesenheit. Nach Stoppen
des Mess-Threads sinkt die Threadzahl jeweils um eins (113 → 112; 111 → 110).

## Echter Galerie-Handoff bis zum sichtbaren Prüfzustand

Quellen: `artifacts/scanner-v27/baseline-gallery-e2e.json`,
`artifacts/scanner-v27/optimized-gallery-e2e.json` und
`artifacts/scanner-v27/optimized-gallery-diagnostics.log`.
Das öffentliche Araqua-Bild `swsh12-047` wurde über den vorhandenen Galerie-
Import in die echte App-Warteschlange übergeben. Eingang und importierte Datei
haben in beiden Läufen dieselben gespeicherten SHA-256-Werte.

| UI-Messwert | Baseline | Optimiert |
| --- | ---: | ---: |
| Warteschlange sichtbar | 364 ms | 362 ms |
| Abschließender Zustand sichtbar | 8.433 ms | 8.413 ms |
| Erster Kartenkandidat sichtbar | keiner | keiner |
| Abschließender Zustand | Manuelle Prüfung nötig | Manuelle Prüfung nötig |

Beide Reports sind vollständig und belegen einen begrenzten, funktionierenden
Handoff, **keinen Erkennungserfolg und keinen relevanten Endergebnis-Zeitgewinn**.
Die UI-Zeiten sind Polling-Obergrenzen eines Einzelfalls, keine Kameraauslösung
oder Zeit bis zu einem korrekt identifizierten Treffer. Keine Sammlungsmutation
und kein ScanLab-Upload wurden angefordert.

Der optimierte Diagnoselog erklärt den Prüfzustand: `cardBoundaryFound=true`,
Geometriescore `0.9690516`, kein Geometrie-Fallback; anschließend liefert die
Orientierungs-OCR nur Scores `0:20, 180:0` und
`orientation_evidence_insufficient`. Die gedruckte Nummer bleibt leer. Der
Abbruch liegt somit in der Orientierungsevidenz **vor** der Kandidatensuche,
nicht in einem nachgewiesenen Katalog-/Netzwerkfehler. Die allgemeine UI-Meldung
„Kartenfläche nicht sicher erkannt“ darf hier nicht als Beleg für eine fehlende
Kontur missverstanden werden. Der Log nennt 8.215 ms Verarbeitung, davon
8.210 ms in der UI-Sammelstufe PREPROCESSING, die bereits OCR einschließt.

Weitere Araqua-Wiederholungen ohne Busy-Pause endeten nach 8.617 und 8.130 ms
ebenfalls in manueller Prüfung (`optimized-gallery-e2e-r2.json` und `-r3.json`).
Ein anderes Bild zeigt keinen gegenteiligen Erfolg:
`optimized-gallery-dratini.json`, Dratini `sm1-94`, Warteschlange nach 345 ms,
manuelle Prüfung nach **8.119 ms**, kein sichtbarer Kandidat. Der Report ist
vollständig; Eingang und importierte Datei stimmen im SHA-256 überein, ohne
Sammlungsmutation oder Upload. Das ist eine zweite fehlende End-to-End-
Identifikation, kein behobenes Erkennungsproblem.

### Zusätzlicher Lauf nach 20 Sekunden Warm-up: fehlgeschlagen

`optimized-settled-gallery.json` verwendet wieder Araqua und dieselben
Eingabe-/Importhashes mit `scannerWarmupMillis=20000`. Nach 573 ms ist die
Warteschlange sichtbar, nach **18.397 ms** jedoch **„Analyse fehlgeschlagen“**
mit `TimeoutException`; kein Kandidat. Der Test bricht mit einer Assertion ab,
der Report trägt korrekt `complete=false` und wird nicht in einen vollständigen
Erfolgsvergleich hineingezählt.

Der zugehörige `optimized-settled-gallery-diagnostics.log` zeigt diesmal
`orientation_selected` mit Scores `0:73, 180:0`. Die Orientierung gelingt also,
anschließend läuft die Gesamtanalyse in den unveränderten 18-s-Watchdog
(`totalProcessingMs=18019`). Das unterscheidet sich von den früheren
Orientierungsablehnungen. Ein Warm-up allein behebt die End-to-End-Erkennung
damit nicht. Ursache und abschließende Regression müssen weiter geprüft werden;
dieser Stand ist **nicht fertig/freigegeben**.

Die Thread-Stichproben in `profiled-gallery-threads.log` umfassen App-Startup
und einen Teil der Analyse. Sie zeigen unter anderem JIT-, Main-/UI- und
Render-Last, sind aber kein zeitlich sauber abgegrenzter Methoden-/Scheduling-
Trace. Daraus lässt sich nicht exakt ableiten, welcher Executor oder Thread den
Timeout verursacht. Eine mögliche Konkurrenz mit dem ML-Kit-Standardexecutor
ist derzeit eine **Untersuchungshypothese, keine nachgewiesene Root Cause**.

### Echtzeit-Kontrolllauf: App-Ergebnis vorhanden, UI-Test fehlgeschlagen

`optimized-realtime-gallery.json` und `.log` prüfen den Galerie-Handoff mit
`ActivityScenario`/`UiAutomation` und Android-Echtzeit, ohne Compose-Testuhr
oder virtuelle Zeitschritte. Nur die Picker-Auswahl wird abgefangen; Import,
OCR und vorhandener Katalog bleiben echt. Die Warteschlange erscheint nach
358 ms, der Test sieht jedoch nur den alten Accessibility-Verarbeitungszustand
und endet nach **41.069 ms** mit Test-Timeout (`complete=false`,
`recognitionOutcome=not_observed`). Der gesamte Instrumentationlauf dauert
48,511 s und zählt **einen fehlgeschlagenen Test**.

Davon zu trennen ist `optimized-realtime-diagnostics.log`: Die App liest
Araqua **047/195**, findet zwei Kandidaten `Araqua:SIT/047` und erreicht nach
**19.607 ms** den Zustand **AMBIGUOUS**, nicht FAILED. OCR dauert dabei
16.464 ms; der Bildvergleich 2.308 ms. Beide Kandidaten haben denselben
Endscore 92 und keinen ausreichenden Abstand. Die echte Sicherheitslogik
behält deshalb die Varianten-/Kandidatenmehrdeutigkeit bei; „Sicher erkannt“
im UI-Text macht daraus keine automatische ACCEPTED-Entscheidung.

Dieser historische Fehlversuch bleibt fehlgeschlagen. Ein App-Diagnoselog ist
kein Ersatz für seinen fehlgeschlagenen UI-Test: Weder seine sichtbare Endzeit
noch ein Geschwindigkeitsgewinn oder eine verbesserte Erfolgsquote ist dadurch
verifiziert. Unterschiede zur Compose-Testuhr sind ein zu prüfender Einfluss,
keine gesicherte alleinige Ursache der bisherigen OCR-Unterschiede.

### Korrigierter Echtzeit-Harness v2: sechs abwechselnde Läufe

Die Korrektur betrifft ausschließlich den Test: Beim Wechsel der zuvor
deaktivierten Warteschlangenkarte in den bedienbaren Endzustand kann Compose
Accessibility-Knoten anders zusammenführen. Der Harness liest diese Struktur
erneut und leert auf API 33+ den Accessibility-Cache, statt den alten
Verarbeitungszustand weiter zu beobachten. App-OCR, Kandidatenbewertung und
Annahmeschwellen wurden dafür nicht verändert.

Alle sechs Eingabe- und importierten Datei-Hashes stimmen überein:
`4bcdf68936c0844d9c9152c7f27e1ef28e722b38a856258732a8f4fb67d709d6`.
Alle verwenden Araqua `swsh12-047`, ohne zusätzlichen Scanner-Warm-up.
Die fünf erfolgreichen Tests verifizieren sichtbar **Araqua / SIT / 047**;
der fachliche Endzustand bleibt **AMBIGUOUS**, mit manueller Varianten-/
Kandidatenentscheidung statt automatischem ACCEPTED. Der UI-Test verifiziert
nicht die konkrete Variantenmenge. Der sechste Test schlägt tatsächlich fehl.

| Folge / Paar | Stand und Report in `artifacts/scanner-v27/` | Erster Kandidat sichtbar | Endzustand sichtbar | Ergebnis |
| --- | --- | ---: | ---: | --- |
| 1 / 1 | Optimiert: `optimized-realtime-v2-gallery.json` | 18.554 ms | 19.998 ms | PASS, Identität verifiziert, AMBIGUOUS |
| 2 / 1 | Baseline: `baseline-realtime-r1.json` | 19.436 ms | 20.774 ms | PASS, Identität verifiziert, AMBIGUOUS |
| 3 / 2 | Optimiert: `optimized-realtime-r2.json` | 18.322 ms | 19.994 ms | PASS, Identität verifiziert, AMBIGUOUS |
| 4 / 2 | Baseline: `baseline-realtime-r2.json` | 18.765 ms | 20.251 ms | PASS, Identität verifiziert, AMBIGUOUS |
| 5 / 3 | Optimiert: `optimized-realtime-r3.json` | 18.382 ms | 20.193 ms | PASS, Identität verifiziert, AMBIGUOUS |
| 6 / 3 | Baseline: `baseline-realtime-r3.json` | keiner | 18.289 ms | FAIL, Analyse-Timeout, `complete=false` |

`baseline-realtime-r3-diagnostics.log` bestätigt einen echten Pipeline-Timeout
nach **18.014 ms**, nachdem die Orientierung mit Score `0:73` gewählt wurde.
Die kürzere sichtbare Fehlerzeit ist kein schnelleres Erkennungsergebnis und
wird nicht in die Erfolgszeit-Mediane eingerechnet. Der fehlgeschlagene Lauf
bleibt in der obigen vollständigen Tabelle enthalten.

Nur unter den **erfolgreichen** Läufen liegt die mediane sichtbare Endzeit bei
Baseline **20.512,5 ms (n=2)** und optimiert **19.998 ms (n=3)**, also
514,5 ms beziehungsweise rund **2,51 %** niedriger. Diese unterschiedlich
großen, auf Erfolg bedingten Stichproben sind kein Gesamtleistungsnachweis.
Im vollständig erfolgreichen Paar 1 beträgt der Unterschied 776 ms
(rund 3,74 %), in Paar 2 257 ms (rund 1,27 %) zugunsten des optimierten Stands.
Paar 3 hat wegen des Baseline-Fehlers **keinen erfolgreichen Zeitvergleich**.
Aus so wenigen Läufen werden weder belastbare p95-/Worst-Case-Aussagen noch
eine allgemeine Verbesserung der Trefferquote abgeleitet.

Der Echtzeit-Handoff bis zu einem **korrekt identifizierten, aber weiterhin
manuell zu bestätigenden** Ergebnis ist damit für diesen einen Araqua-Fall
nachgewiesen. Das ist enger als universell zuverlässige oder automatisch
akzeptierte Erkennung. Die Zeiten bleiben Polling-Obergrenzen im Emulator;
keine Sammlung wurde verändert und kein Upload ausgelöst. Der Gesamtstand
bleibt ohne Fertig-/Freigabeaussage.

### Echtzeit-Wiederholung mit 20 Sekunden Warm-up: bestanden

`optimized-realtime-warm20.json` ist vollständig und verwendet denselben
Araqua-Eingabe-/Importhash wie die sechs Läufe oben. Nach dem ausdrücklich
erfassten **20-s-Warm-up** erscheint der erste Kandidat nach **19.084 ms**,
der Endzustand nach **21.343 ms**. Araqua / SIT / 047 ist sichtbar verifiziert;
der Endzustand bleibt **AMBIGUOUS** und erfordert eine manuelle Varianten-/
Kandidatenentscheidung. Die konkrete Variantenmenge ist keine Assertion
dieses UI-Tests. `optimized-realtime-warm20.log` meldet **1 Test bestanden
in 50,410 s**, einschließlich Warm-up und Testeinrichtung.

`optimized-realtime-warm20-diagnostics.log` nennt **21.037 ms**
Pipeline-Gesamtverarbeitung, davon **17.781 ms PREPROCESSING**, das die
OCR-Arbeit bereits einschließt. Die Stage-Bezeichnung ist daher keine
isolierte Messung ausschließlich geometrischer Vorverarbeitung.

Dieser erfolgreiche Echtzeitversuch ersetzt oder entkräftet nicht den oben
dokumentierten fehlgeschlagenen Compose-basierten Warm-up-Lauf
(`optimized-settled-gallery.json`, Timeout nach 18.397 ms). Die Tests verwenden
unterschiedliche Harnesses; deren Einfluss ist damit nicht als alleinige
Fehlerursache bewiesen. Der einzelne spätere Erfolg ist weder ein gepaarter
Warm-up-Geschwindigkeitsvergleich noch eine allgemeine Genauigkeitssteigerung.

## Verworfener Busy-Pause-Versuch

Die Live-Vorschau blieb gebunden; nur CPU-intensive Konturarbeit sollte während
PREPROCESSING/OCR warten. Drei reale Galerie-/Warteschlangenläufe je Stand:

| Araqua, sichtbarer Endzustand | Ohne Busy-Pause | Mit Busy-Pause |
| --- | ---: | ---: |
| Lauf 1 | 8.413 ms | 8.345 ms |
| Lauf 2 | 8.617 ms | 8.560 ms |
| Lauf 3 | 8.130 ms | 8.637 ms |
| Median | 8.413 ms | 8.560 ms |
| Ergebnis in allen drei Läufen | Manuelle Prüfung | Manuelle Prüfung |

Quellen: `optimized-gallery-e2e*.json` und
`busy-pause-gallery-e2e-r1.json` bis `-r3.json`. Alle sechs Reports sind
vollständig, aber keiner zeigt eine erfolgreiche Identifikation. Der Median
mit Pause liegt 147 ms höher; ein nützlicher Zeit-/Treffervorteil ist nicht
belegt.

Unabhängiges Review fand zudem ein konkretes Risiko: Nach Zurücksetzen der
Qualitätshistorie entstehen zunächst Nicht-GREEN-Aufwärmframes, obwohl dieselbe
Karte noch vorhanden ist. Drei solche Frames könnten im bisherigen Auto-Gate
als Entfernung zählen und dieselbe Karte erneut scharfstellen/aufnehmen. Die
neuen Hilfstests hatten diese reale Aufwärmsequenz nicht abgedeckt. Dies ist
ein **Review-Befund, kein auf einem realen Gerät reproduzierter Fehler**.
Der Versuch wurde komplett zurückgenommen, einschließlich seiner beiden
Testdateien; kein zusätzlicher Capture-/UI-Policy-Patch bleibt deshalb zurück.

## Verworfenes 1.000-Pixel-Experiment

`artifacts/scanner-v27/experiment-width1000.json` ist ein vollständiger
**testlokaler** Lauf: sechs Fixtures × `clean`, `dim`, `rotated90`, je einmal,
18 Messungen. Die Analyseebene wurde testweise verkleinert, nicht die
Produktionskonfiguration. Das Experiment ist ausdrücklich nicht pixelgleich;
der gespeicherte Eingabehash beschreibt nur das ursprüngliche PNG, nicht die
verkleinerten OCR-Ebenen.

Ergebnis: 9 ACCEPTED, 3 AMBIGUOUS, 6 REJECTED, keine falsche automatische
Annahme; Analyzer-Median 13,349 s, schlechtester Wert 15,642 s. Entscheidend
sind die beobachteten Fehler, nicht eine isolierte Zeitangabe:

- Enigmara `sv02-269`, `clean`: OCR liest **269/019 statt 269/193**. Die
  bestehende Entscheidung bleibt sicher REJECTED. Das alte Feld
  `numberCorrect=true` bezeichnet hier nur den richtigen Zähler `269`; es
  beweist gerade keinen richtigen Nenner. Auch der aggregierte Wert 13/18
  darf nur als Collector-Zähler-/localId-Trefferzahl bezeichnet werden.
- Pikachu `base1-58`, `clean`: erneut `orientation_evidence_insufficient`,
  keine gelesene Nummer, REJECTED.

**Nicht übernehmen. Die unveränderte Analysebreite von 1.200 Pixel bleibt.**
Es wurde kein Produktionsparser und keine Annahmeschwelle angepasst, um diese
Fehler zu verdecken.

## Beim Abschlusscheck gefundene Close-/Cleanup-Races

`final-regression-core.log` endete mit **26 bestandenen und einem
fehlgeschlagenen Test**. Der Stacktrace zeigt eine `NoSuchElementException`
in `ConcurrentHashMap.KeyIterator.next()` über Kotlin `Collection.toList()`
innerhalb `CardTextAnalyzer.close()`; betroffen war der bestehende manuelle
Ganzbild-OCR-Test. Die Ursache ist konkret: Die nebenläufige Sitzungsmenge
meldet zunächst Größe eins, ihr letzter Eintrag wird durch Cleanup entfernt,
bevor der `toList()`-Sonderpfad `iterator.next()` aufruft.

Minimaler Fix in derselben Scannerdatei: statt größenabhängigem
`sessions.toList().forEach(...)` direkt über den nebenläufigen Iterator
iterieren (`for (session in sessions) session.close()`). Das verändert keine
OCR-Evidenz oder Entscheidungsgrenzen. Ein neuer Test fährt **512 begrenzte
Close-/Cleanup-Rennen** und prüft Abschluss und Ressourcenfreigabe.
`final-close-race-core.log` besteht danach mit **28/28 Tests in 24,887 s**.

Die weitere Race zwischen Schließen und Einreihen der Cleanup-Arbeit wurde
inzwischen **deterministisch nachgewiesen und gezielt behoben**.
`cleanup-submission-before-fix.log` scheitert vor dem Fix nach 0,075 s:
„Cleanup must not recycle an owned bitmap while preprocessing still uses it“.
Der Test hält die Vorverarbeitung mit einem tatsächlich registrierten Bitmap
an und steuert das Einreihen des Cleanups gegenüber `analyzer.close()`.

Ursache: Ein Abschluss hatte seinen atomaren Finished-Zustand bereits gesetzt,
sein Cleanup aber noch nicht in den Worker eingereiht. Ein zweiter Close-Aufruf
konnte deshalb den Executor schließen; die abgewiesene Cleanup-Einreihung lief
dann direkt auf dem aufrufenden Thread und recycelte ein noch von der
Vorverarbeitung genutztes, nicht an ML Kit geleastes Bitmap.

Minimaler Fix: `AnalysisSession.finish(...)` ist jetzt sitzungsspezifisch
`@Synchronized`, einschließlich des tatsächlichen `completion.complete(...)`-
Aufrufs und seiner Cleanup-Einreihung. Ein konkurrierendes `close()` wartet
damit auf diesen Abschluss, bevor es den Worker herunterfährt. Die laufende
Vorverarbeitung selbst wird nicht unter dieses Monitor-Lock verschoben.
Cleanup bleibt seriell hinter der Vorverarbeitung; aktive native OCR-Leases
behalten ihre bestehende Freigaberegel.

`final-serialized-core.log` besteht danach mit **29/29 Tests in 23,495 s**,
einschließlich des deterministischen Gegenfalls und der 512 Stressiterationen.
Damit sind die beiden konkret nachgewiesenen Races abgedeckt; daraus folgt
kein pauschaler Beweis sämtlicher denkbarer Lifecycle-Interleavings und keine
Erkennungs-/Geschwindigkeitsfreigabe.

## Teststatus und noch offene Nachweise

- `debug-build-and-unit-tests.log`: Debug-App, Instrumentation-APK und die
  ausgewählten Debug-Unit-Tests erfolgreich gebaut/ausgeführt. Kein Releasebuild.
- `debug-test-build-final.log`: aktualisierte Instrumentation-APK erfolgreich
  gebaut. Build-Erfolg bedeutet noch nicht, dass jede neue Messung gelaufen ist.
- `debug-final-build.log`: nach Rücknahme des Busy-Pause-Versuchs Debug-App,
  Instrumentation-APK und Debug-Unit-Tests erfolgreich, **37 s**. Die zugehörigen
  XML-Ergebnisse enthalten **583 Testfälle, 0 Fehler/Failures, 1 übersprungen**
  (also nicht 583 ausgeführte Erfolge). Keine AAB-/Release-/Signing-Aktion.
- `debug-close-race-build.log`: nach dem größenunabhängigen Close-Iterationsfix
  Debug-Build und Unit-Tests erfolgreich, **29 s**; weiterhin **583 Testfälle,
  0 Fehler/Failures, 1 übersprungen**. Kein Releasebuild.
- Jüngster Stand `debug-serialized-cleanup-build.log`: nach Serialisierung der
  Cleanup-Einreihung erneut **Build erfolgreich in 26 s**, **583 Unit-Testfälle,
  582 bestanden, 0 Fehler/Failures, 1 übersprungen**. Weiterhin nur Debug-Artefakte.
- `regression-core.log`: **27 Tests bestanden**, 22,897 s. Enthalten sind
  Lifecycle/Timeout/Abbruch, lokale ML-Kit-Lesung und manueller Fallback,
  Normalisierung/EXIF/Geometrie, Holo-Reflexionsrobustheit, Knowledge-Consent/
  Eigentümertrennung, OCR-Cache-Evidenzgleichheit und echte native
  Session-/Abbruch-Isolation.
- Nachfolgend `final-regression-core.log`: 26 bestanden, 1 Close-Race-Fehler.
  Nach dessen gezieltem Fix `final-close-race-core.log`: **28 bestanden**,
  24,887 s, einschließlich 512 Close-/Cleanup-Stressiterationen. Die frühere
  fehlerhafte Wiederholung bleibt dokumentiert und wird nicht übergangen.
- Danach gezielter Vorher-Nachweis `cleanup-submission-before-fix.log`:
  ein deterministisch fehlgeschlagener Test wegen zu früher Bitmapfreigabe.
  Nach dem zweiten Minimalfix ist der jüngste Kernlauf
  `final-serialized-core.log` **29/29 bestanden in 23,495 s**. Diese jüngere
  Zahl ersetzt 28 als aktuellen Kern-Teststand, nicht als neue Benchmarkquote.
- `regression-ui.log`: **21 Tests bestanden, ein opt-in Test übersprungen**,
  88,232 s für den Lauf. Der Runner meldet 22 eingeplante Tests; die
  `AssumptionViolatedException` bei
  `publicGalleryFixtureReportsRealQueueCandidateAndTerminalLatency` ist kein
  ausgeführter Erfolgstest. Enthalten sind Portrait-Lock/Kamera, manuelle
  Aufnahme/Galerie-Handoff, Auto-Capture-Sitzung, Capture-Status und getrennte
  Consent-/Report-UI. Der opt-in Galerie-Latenztest wurde inzwischen separat
  vor/nach dem Patch ausgeführt; sein erfolgreicher Testabschluss bedeutet
  Handoff/Prüfzustand, ausdrücklich keinen erkannten Kartenkandidaten.
- Abschließende UI-Regression `final-serialized-ui.log`: **21 Tests bestanden,
  ein opt-in Test übersprungen**, **90,833 s**. Der Runner meldet `OK (22 tests)`;
  der Skip zählt weiterhin nicht als ausgeführter Erfolg. Damit ist die
  relevante UI-Regression nach beiden Close-/Cleanup-Fixes abgeschlossen.
- `offline-confirmed-regression.log`: **9 Tests bestanden**, 21,105 s.
  `offline-confirmed-network-state.log` bestätigt bereits vor dem Start
  `Active default network: none`. Die temporären Emulator-Netzeinstellungen
  wurden anschließend auf den Ausgangsstand zurückgesetzt
  (`airplane_mode_on=0`, `wifi_on=1`, `mobile_data=1`). Der Offline-Lauf prüft
  lokale Scanner-Regressionsfälle, keinen erfolgreichen Online-Katalogabruf.
- Abschließende Offline-Regression `final-offline-regression.log`:
  **13/13 Tests bestanden in 22,181 s**. `final-offline-network-state.log`
  belegt vor Testbeginn erneut `Active default network: none`. Nach dem Lauf
  wurde die vorübergehende Emulator-Netzkonfiguration wiederhergestellt und
  nach asynchroner Aktivierung nochmals überprüft: Flugmodus 0, WLAN 1,
  mobile Daten 1, aktives Standardnetz 107. Kein physisches Gerät oder
  Nutzerkonto wurde dafür verwendet; kein Online-Katalogerfolg wird behauptet.
- Geometrie-, 90er- und 34er-OCR-Vergleiche sind vollständig. Der erste Lauf
  zeigt niedrigere Zeiten und vier zusätzliche Treffer; der erweiterte Lauf
  zeigt gleiche Entscheidungen ohne Median-Gewinn. Der unoptimierte
  Stabilitätslauf widerlegt eine belastbare Zuschreibung der vier Treffer zum
  Patch. Qualitäts-/Annahmeschwellen wurden nicht gelockert.
- Preview-Soak und frühe Compose-basierte opt-in Galerie-/Warteschlangenmessungen
  sind vollständig dokumentiert: stabile Prüfzustände, aber kein belegter
  FPS-/CPU-Gewinn oder identifizierter End-to-End-Treffer in diesen frühen
  UI-Läufen. Das betrifft nicht die späteren erfolgreichen Echtzeitmessungen.
  Ebenfalls davon getrennt bleibt der zusätzliche Compose-basierte
  20-s-Warm-up-Lauf: Dieser scheiterte nach 18,397 s am Timeout und bleibt
  ausdrücklich als fehlgeschlagener Test dokumentiert. Auflösungs- und
  Busy-Pause-Experiment wurden verworfen.
- `optimized-realtime-gallery.log`: **ein fehlgeschlagener UI-Test** wegen
  nicht aktualisierter Accessibility-Ergebnisbeobachtung; App-Diagnose dagegen
  Araqua 047/195, zwei Kandidaten, AMBIGUOUS nach 19.607 ms. Kein bestandener
  UI- oder Geschwindigkeitsnachweis für diesen historischen Versuch.
- Korrigierter Echtzeit-Harness v2: sechs abwechselnde Läufe mit identischen
  Eingabehashes, **optimiert 3/3 PASS, Baseline 2/3 PASS und ein echter
  Analyse-Timeout/FAIL**. Bei jedem PASS sichtbar richtige Identität Araqua /
  SIT / 047, fachlich AMBIGUOUS mit manueller Varianten-/Kandidatenentscheidung,
  ohne Assertion der konkreten Variantenmenge. Erfolgszeit-Median 20.512,5 ms
  (Baseline n=2) gegenüber 19.998 ms (optimiert n=3); keine allgemeine
  Geschwindigkeits- oder Trefferquotenbehauptung aus diesen kleinen und nach
  Erfolg ausgewählten Stichproben. Alle sechs Läufe einschließlich Fehler
  sind oben getrennt dokumentiert.
- `optimized-realtime-warm20.log`: **1 Test bestanden in 50,410 s** inklusive
  20-s-Warm-up, erste sichtbare Araqua-Identität 19.084 ms, sichtbarer
  AMBIGUOUS-Endzustand 21.343 ms. Der frühere Compose-Warm-up-Timeout bleibt
  separat dokumentiert und wird dadurch nicht nachträglich als Erfolg gezählt.
- **Inhaltlich noch nicht belegt:** robust höhere Trefferquote über Karten und
  Aufnahmebedingungen hinweg; allgemeiner Gewinn bei fertig sichtbaren,
  richtigen End-to-End-Ergebnissen; Ursache der schwankenden Timeout-/
  Orientierungsergebnisse zwischen Wiederholungen und Test-Harnesses. Die
  abgeschlossenen Kern-, UI-, Offline- und Warm-up-Tests stehen nicht mehr aus.
  Die beiden reproduzierten Close-/Cleanup-Races sind mit den obigen
  Gegen-/Regressionstests behoben. Das kombinierte Nutzerziel darf dennoch
  nicht als vollständig erreicht ausgegeben werden.
- **Nur am echten Gerät prüfbar:** Autofokus/Belichtung, physische Holo-/Reverse-
  Reflexionen, Handbewegung, reale Kamera-/JPEG-Latenz, Gerätesensorik und
  längeres thermisches Verhalten. Den abschließenden Smartphone-Test führt
  der Nutzer selbst durch; dafür wurde hier kein physisches Gerät verwendet.
  Dieser Bericht fordert jetzt keinen zusätzlichen Hardwaretest an.

Bis diese offenen Prüfungen abgeschlossen sind, ist dieser Bericht ausdrücklich
kein Freigabe- oder Releasebericht.
