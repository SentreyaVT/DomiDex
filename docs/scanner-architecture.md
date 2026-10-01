# Scanner-Architektur

Stand: **01.10.2026**, technische Bestandsaufnahme des lokalen Arbeitsverzeichnisses.
Dieses Dokument beschreibt keine neue Veröffentlichung und keine zugesicherte Erkennungsquote.
Der lokale Entwicklungsstand (`versionCode 27`, `1.0-closed-alpha.6`) enthält uncommittete Änderungen.
Er ist weder mit dem GitHub-Default-Branch `master` (Konfiguration 16), dem GitHub-Entwicklungsbranch
`codex/ui-v3` (Konfiguration 19), dem lokalen HEAD `096aa35` (Konfiguration 21)
noch mit dem archivierten signierten Release 26 gleichzusetzen.
Versions- und Testnachweise sind in `docs/PROJECT_STATUS.md` und `docs/TESTING.md` getrennt dokumentiert.

## Datenfluss

```text
CameraX-Vorschau / manueller Auslöser / optionaler Auto-Auslöser / Galerie
  → lokale Bilddatei und begrenzte Warteschlange
  → JPEG-/EXIF-Decodierung, Kartenkontur, Perspektive und Qualitätsprüfung
  → geometrisch passende Orientierungen, lokale Latin-OCR, Nummer/Set/Name
  → Quellkatalog-Kandidaten und mögliche Druckvarianten
  → visueller Vergleich, Hybridbewertung, begrenzte Knowledge-Hinweise
  → ACCEPTED / AMBIGUOUS / REJECTED
  → Auswahl, Ziel-Binder und ausdrückliches Hinzufügen durch den Nutzer
```

Die fotografierte Karte wird für die normale OCR/Geometrie nicht an einen Erkennungsserver gesendet.
Katalogabfragen, Referenzbilder und Knowledge-Zugriffe können dennoch Netzwerkverkehr verursachen.
Die unten beschriebenen freiwilligen Foto-/Feedbackwege sind davon getrennt.

## Kamera, Aufnahme und Warteschlange

- `app/src/main/java/com/dominik/cardvault/ui/PortraitScannerCamera.kt` bindet Preview,
  ImageCapture und ImageAnalysis lebenszyklusgebunden an die Portrait-Vorschau.
  Aufnahme: `MINIMIZE_LATENCY`; Liveanalyse: `KEEP_ONLY_LATEST`; Fokus und Zoom sind bedienbar.
- `app/src/main/java/com/dominik/cardvault/ui/CameraScanner.kt` analysiert den Y-Kanal etwa alle
  140 ms: Belichtung, überstrahlte Pixel, Schärfeproxy, Bewegung und Kartengeometrie.
  Grün bedeutet aufnahmebereit, **nicht erkannt**; die sichtbare Bereitschaftsanzeige ist kein Modellscore.
- Auto-Aufnahme ist standardmäßig **aus**. Die aktivierte Sperre nutzt zwei bereite Analyseschritte
  und eine Wiederfreigabe nach bestätigtem Übergang/Entfernen beziehungsweise geändertem Fingerprint.
  Der aktuelle Aufruf verwendet den grünen Qualitätszustand; `captureEligible` ist zusätzlich berechnet.
- Aktuell wird pro Auslösung ein Bild aufgenommen. Vorhandene Mehrbild-Zusammenführung begründet
  keine Behauptung, jede Aufnahme nutze einen Burst oder könne Holo physisch sicher erkennen.
- `app/src/main/java/com/dominik/cardvault/ui/CardVaultApp.kt` verwendet für Galerieaufnahmen
  Androids Fotoauswahl und kopiert das ausgewählte Bild für die Verarbeitung in den App-Cache.
- `app/src/main/java/com/dominik/cardvault/ui/ScanProcessingQueue.kt` begrenzt schwere Analyse
  auf einen Worker, automatische laufende Arbeit auf vier und die Warteschlange auf sechs Einträge.
  Ausstehende manuelle Prüfungen werden nicht automatisch zugunsten neuer Aufnahmen verworfen.
  Aufnahme-IDs ordnen Ergebnisse dem richtigen Bild zu; Sitzungsschutz verhindert unbemerkte Dubletten.

## Normalisierung und OCR

`app/src/main/java/com/dominik/cardvault/ui/CardScanPipeline.kt` berücksichtigt EXIF-Rotation und
Spiegelung, begrenzt die Decodierung, sucht eine plausible vierseitige Kartenkontur und korrigiert
deren Perspektive mit OpenCV. Qualität und Bildgeometrie bleiben von Kartenidentität getrennt.
Die Erkennungsebene ist 1200 × 1680 px, die Anzeigeebene 900 × 1260 px; der Decode-Rand ist auf 2600 px begrenzt.
Normale Konturen liefern zwei geometrisch passende Orientierungen. Ein manueller Ganzbild-Fallback
kann vier prüfen, darf aber keine automatische sichere Identität vortäuschen.
ROI-Profile decken moderne, ältere, Full-Art- und unterschiedlich positionierte untere Druckzeilen ab.

`app/src/main/java/com/dominik/cardvault/ui/CardTextAnalyzer.kt` verwendet im aktiven Pfad den
gebündelten **Latin-Recognizer von ML Kit lokal**. Script-Enumwerte für Japanisch/Chinesisch sind
kein Nachweis eines aktiven CJK-Erkennungspfads oder einer sicher bestimmten Kartensprache.
Original-, Kontrast-, Schwellenwert- und gegebenenfalls invertierte Ausschnitte liefern OCR-Evidenz.
Die Parser behandeln Setcodes, Promo-Präfixe und Sammlernummern getrennt von KP-/Angriffswerten.

Der exakte OCR-Cache gilt ausschließlich innerhalb einer Aufnahme: ROI, Vorverarbeitung, Orientierung
und Bildmaße gehören zum Schlüssel. Ergebnisse werden nicht zwischen verschiedenen Karten übernommen.
Budgets: 5 s Orientierungs-OCR, 9 s vollständige OCR, 18 s Analyzer, 40 s äußerer Worker;
diese Obergrenzen sind **keine üblichen Laufzeiten oder Geschwindigkeitsgarantie**.
Bis zu zwei OCR-Aufrufe laufen innerhalb des einzelnen Workers parallel.
`app/src/main/java/com/dominik/cardvault/ui/ScanTaskResources.kt` verwaltet Ressourcen und native
Bild-Leases; Abbruch darf von ML Kit noch verwendete Bitmaps nicht vorzeitig freigeben.

## Identität, Varianten und Bestätigung

`app/src/main/java/com/dominik/cardvault/ui/ScanProcessingQueue.kt` reduziert anhand des
TCGdex-basierten Quellkatalogs, Setkennung und Nummer. Eine ausdrücklich gewählte Set-ID bleibt bindend.
Ein gedruckter Nenner grenzt Kandidaten ein; eine Nummer oberhalb des Hauptsets ist nicht allein ungültig.
Quelle: `app/src/main/java/com/dominik/cardvault/data/catalog/PokemonVisualCatalog.kt`.
Bis zu acht plausible Druckvarianten bleiben nach der ersten Bewertung; bis zu vier erhalten Bildvergleiche.
Eine fehlende geprüfte Cardmarket-Produkt-ID macht einen erkannten Kandidaten unbepreist, nicht ungültig.
Ein Preis oder derselbe Kartenname ist kein Ersatz für die richtige Karten-/Druckidentität.

`app/src/main/java/com/dominik/cardvault/ui/CardArtworkVerifier.kt` vergleicht mehrere Kartenregionen
über visuelle Deskriptoren, maskierte Hashes und Ähnlichkeitswerte. Das ist kein nachtrainiertes Universalmodell.
Referenzbilder werden bei Bedarf geladen: zwei parallele Downloads, 3 s Aufrufbudget, 7 s Batchbudget,
96 Referenzdeskriptoren im Prozess-LRU. Nicht verfügbare Referenzen bleiben `UNAVAILABLE`.
`app/src/main/java/com/dominik/cardvault/ui/HybridRecognition.kt` kombiniert Nummer, Set, Namen,
Sprache und Bildbelege. Harte Widersprüche dürfen nicht durch andere gute Signale überstimmt werden.

`app/src/main/java/com/dominik/cardvault/ui/ScannerDecisionPolicy.kt` definiert tatsächlich:

- `ACCEPTED`: ausreichend gestützte Identität mit Kandidatenabstand, noch keine automatische Speicherung.
- `AMBIGUOUS`: mehrere plausible Ergebnisse oder zusätzliche Prüfung erforderlich.
- `REJECTED`: unzureichende beziehungsweise widersprüchliche Evidenz; manuelle Fortsetzung bleibt möglich.

Das endgültige Ergebnis verwendet die Hybridentscheidung. Manuelle Ganzbild-/Identitäts-Fallbacks bleiben prüfpflichtig.
Der Hinzufügen-Dialog bestätigt Kandidat und Ziel-Binder; identische Sitzungsfunde erfordern bei Wiederholung
„Trotzdem hinzufügen“. Physische Holo-/Reverse-Eindeutigkeit ist durch ein flaches Referenzbild nicht bewiesen.

## Drei getrennte Foto-/Feedbackwege

| Weg | Auslösung, Speicherung und Grenze |
| --- | --- |
| ScanLab-Fehlscan | Pro Aufnahme ausdrücklich „Hochladen“ bestätigen; aktuell nur manuell ausgelöste Fotos. Geschützter, kontozugeordneter Web-Upload, nicht anonym. |
| Lokaler Scan-Editor | Bereiche verschieben/vergrößern, positive/negative Markierungen und Texte korrigieren, ausdrücklich lokal speichern. Originalbild und JSON unter `filesDir/scan_feedback`; im ausdrücklich erzeugten ZIP-Backup enthalten. |
| Optionales Katalogfoto | Separater Bestätigungsdialog und Firebase-Storage-Pfad `catalog-contributions/{uid}/{sha256}/card.*`; nicht mit ScanLab oder bildfreiem Knowledge gleichsetzen. |

`app/src/main/java/com/dominik/cardvault/ui/ScannerReportFlow.kt` deaktiviert automatische
Fehlscan-Uploads ausdrücklich, auch bei vorhandenen alten Einwilligungswerten. Ein gespeicherter Opt-in
allein startet keinen Fotoversand. Unklare Übertragungsausgänge dürfen keine blinden Doppeluploads auslösen.
`app/src/main/java/com/dominik/cardvault/data/feedback/FirebaseScannerReportUploader.kt` sendet
einen authentifizierten One-shot-POST ohne automatischen Retry-Worker. Für **diesen** Weg werden Fotos
decodiert und neu als JPEG kodiert: EXIF/GPS entfernt, maximal 2048 px Bildrand und 10 MiB Upload.
Berichte enthalten ausgewählte OCR-/Kartenmerkmale und technische Analyseinformationen, keine beliebigen Gerätelogs.
Ein bereits begonnener Upload kann nach Schließen seiner Vorschau weiterlaufen; das löscht keinen Bericht.

`app/src/main/java/com/dominik/cardvault/data/feedback/ScanFeedbackStore.kt` speichert dagegen
lokale Originaldateien. Auch `app/src/main/java/com/dominik/cardvault/data/storage/DomiDexStorageGateway.kt`
kopiert beim optionalen Katalogbeitrag Originalbytes. **Für diese beiden Wege ist keine generelle
EXIF-Entfernung belegt.** Die ScanLab-Datenschutzbeschreibung darf nicht pauschal auf sie übertragen werden.

## Bildfreies Community-Knowledge

`app/src/main/java/com/dominik/cardvault/data/knowledge/ScanKnowledge.kt` beschreibt Evidenz aus
bestätigter Identität, Nummer/Set, kanonisierten OCR-Tokens, gehashtem visuellem Fingerprint, Kandidaten,
Konfidenz/Korrektur, Version und Zeit. Der SHA-256-Schlüssel berücksichtigt diese Analysemerkmale;
er ist kein Foto und keine Garantie, dass verschiedene Aufnahmen denselben Schlüssel ergeben.
`app/src/main/java/com/dominik/cardvault/data/knowledge/ScanKnowledgeConsent.kt` verlangt eine separate konten-/gerätebezogene
Einwilligung mit Version/Zeitpunkt, standardmäßig aus. Widerruf leert die lokale wartende Outbox,
verspricht aber nicht die Löschung bereits übertragener Daten.

`app/src/main/java/com/dominik/cardvault/data/knowledge/ScanKnowledgeStore.kt` hält einen Cache mit höchstens 512 Einträgen
und kontogetrennte Outboxen mit jeweils höchstens 128 Einträgen unter `filesDir/scan_knowledge` (SHA-256-Kontonamensräume).
`app/src/main/java/com/dominik/cardvault/data/knowledge/FirebaseScanKnowledge.kt` überträgt Evidenz nach Konto-/Berechtigungsprüfung mit WorkManager
bei Verbindung nach `users/{uid}/scanEvidence/{fingerprint}`. Diese privaten Daten sind kontozuordenbar.
`functions/index.js` aggregiert transaktional, mit einem Beitrag pro gehashter UID und Fingerprint.
`functions/knowledge-aggregate.js` setzt `STABLE` ab drei übereinstimmenden Beiträgen und Abstand zwei;
bei mindestens drei konkurrierenden Beiträgen und Abstand höchstens eins gilt `DISPUTED`, sonst `CANDIDATE`.
`firestore.rules` erlaubt Clients nur passende Einzelzugriffe; globale Aggregate schreibt der vertrauenswürdige Server.
`app/src/main/java/com/dominik/cardvault/ui/ScannerKnowledgeEvidence.kt` verwendet Knowledge nur
für begrenzte Bewertung vorhandener widerspruchsfreier Kandidaten, nicht als neue Identität oder Modelltraining.
Der vorhandene Aggregationstrigger ignoriert gelöschte Evidenz; eine automatische Rücknahme früherer
Aggregatstimmen bei Löschung ist dadurch nicht nachgewiesen und bleibt ein Datenschutz-/Hardening-Prüfpunkt.

## Lokaler Entwicklungsstand, Offline-Grenzen und Nachweise

Uncommittete Optimierungen betreffen verzögerte Kontrastvorbereitung, identische zeilenweise Qualitätssamples,
OCR-Bitmap-Wiederverwendung, Sitzungs-Cleanup und Schutz vor gewöhnlichen Wörtern als schwachen Setcodes.
Die lokale Ergänzung `app/src/main/java/com/dominik/cardvault/data/knowledge/FirebaseReviewedScanKnowledge.kt`
liest ScanLab-Prüfhinweise parallel mit 1,5-s-Budget und ohne persistenten Cache.
`app/src/main/java/com/dominik/cardvault/ui/ReviewedScanKnowledgeRanker.kt` darf einen passenden
vorhandenen Kandidaten begrenzt umsortieren, aber weder Widersprüche entfernen noch `ACCEPTED` herstellen.
Diese Ergänzung ist kein Nachweis, dass Release 26 sie enthält oder dass sie serverseitig live verfügbar ist.

Lokale OCR funktioniert ohne Netzdienst; vollständige Katalogauflösung ist damit nicht garantiert offline.
`app/src/main/java/com/dominik/cardvault/data/catalog/CatalogMemoryCache.kt` hält Metadaten nur im
Prozess für 15 Minuten (eine Setliste, 24 Set-Kartenlisten, 512 Details). Kalte/abgelaufene Abfragen benötigen
die Quelle; Referenzdeskriptoren sind ebenfalls kein vollständiger persistenter Offlinekatalog.
Private Fotos, lokale Editor-Daten, bildfreie Outbox und Sammlungssynchronisierung sind getrennte Datenwege.

`docs/scanner-v27-performance-20260924.md` enthält datierte Emulator-Messungen und deren Grenzen.
90 Fixture-Beobachtungen sind keine 90 unabhängigen physischen Karten; der erweiterte Vergleich zeigt
keinen allgemeinen Median-Zeitgewinn. Erfolgreiche Regressionen belegen keine flächendeckende Trefferquote.
UI-Stufennamen sind keine exakten Messgrenzen: `PREPROCESSING` enthält bereits OCR-Arbeit.
Offene Nachweise betreffen reale Geräte, ältere/Promo-Karten, Licht/Hüllen, Varianten und kalte Katalogzugriffe.
Scannerqualität ist **kein automatisches Zustandsgrading**; ein eigenes DomiDex-Grading ist keine aktive Scannerfunktion.
