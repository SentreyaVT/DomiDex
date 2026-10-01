# Roadmap – nächste Prüfziele, keine Release-Zusage

Stand: 2026-10-02. Ausgangspunkt ist der ursprüngliche Audit in
[PROJECT_STATUS](PROJECT_STATUS.md). Die folgenden Prüfziele sind keine Liste
vollständig unerledigter Implementierungen: anschließende lokale Teilkorrekturen,
Tests und offene Gates stehen im [Release-27-Ledger](RELEASE_27.md).

## Vor dem nächsten Release

1. **Quellstand nachvollziehbar sichern:** lokale Entwicklung, GitHub und
   Release-Provenienz getrennt prüfen; der lokale Ausgangsstand ist inzwischen
   als Engineeringcheckpoint gesichert. Weitere Änderungen gezielt reviewen;
   kein blindes Pushen des kompletten Working Trees und kein rekonstruierter v26-Quellstand.
2. **Scanner messen:** gleicher verifizierter Bildkorpus und gleiche Geräte,
   warme/kalte Pfade, OCR-/Netzwerk-/Queue-Zeit, Speicher/Temperatur, Abbruch und
   Kontowechsel. Ältere Karten, Promos und schwieriges Licht gezielt einbeziehen.
   Weniger Latenz darf nicht durch schwächere Identitätsprüfung erkauft werden.
3. **Preisabdeckung verbessern:** fehlende druckspezifische Produktbelege und
   tatsächlich fehlende Preisfelder getrennt bearbeiten. Keine Namens-, Nachbar-
   oder Main-Set-Fallbackpreise erfinden. Snapshot-Audit über alle gelisteten
   physischen deutschen Sets reproduzierbar halten.
4. **Lokale Preisalternative prüfen:** nur ohne echten Trend, genaue Identität,
   aktuelle Quelle, verständliche Anzeige; Android/Web-Sidecar-Regeln und
   ältere Clients, Offline-Konflikte und erneuten echten Trend gemeinsam testen.
5. **Sync/Binder:** messbare Erstlade-/Folgeladezeiten; zwei berechtigte Geräte,
   Offline/Reconnect, Revocation, Konflikte, Löschen und Teilen end-to-end.
   Für R27 bleibt auf ausdrücklichen Nutzerwunsch der abgesicherte vollständige
   Abgleich aktiv. Kein Delta-Rollout und kein unnötiger Lesepfad-Umbau.
   Der aktuelle lokale Offline-/Datei-Reopen-Nachweis ist bestanden. Die getrennte
   Jahresverlaufs-Messung zeigt bei 346.750 relevanten Preiszeilen etwa 16,1 s für
   Portfolio/Binderdetails, während die Übersichten schnell bleiben. Diesen lokalen
   Engpass gezielt aufteilen und erst mit vollständigem Preis-/Owner-Paritätsnachweis
   optimieren; keine Historie kürzen, kein Sync-Umbau und keine Geräte-SLA ableiten.
6. **Release-Gate:** unveränderte R8-Sicherheitsgrenzen, Mapping zum exakten AAB,
   nachgewiesene Native-Symbol-Abdeckung, ehrlicher Hersteller-Blocker; finaler
   Geräte- und Google-Play-Kompatibilitätstest nach separatem Go.

Version 27 ist dabei ein Entwicklungsziel, nicht das Ergebnis dieses Audits.
Keine neue Version, kein Tag, kein Build, keine Signierung und kein Deployment
werden durch diese Roadmap freigegeben.

## Hardening und Wartbarkeit

- **Delta als spätere Optimierung:** vorhandene inaktive Grundlage erhalten;
  erst nach belastbarem Nachweis vollständiger Änderungen einschließlich
  SDK-Overlays, Cursorgrenzen, Altbestand und Wiederherstellung aktivieren.
  Die offenen Delta-Nachweise sind keine R27-Anforderung mehr; Sicherheits- und
  Regressionstests des weiterhin aktiven vollständigen Sync bleiben erforderlich.
- Die lokal implementierten, standardmäßig deaktivierten App-Check-Provider erst
  nach Projektregistrierungs-/Play-Integrity-Prüfung aktivieren; Enforcement separat
  freigeben und bestehende UID-/Serverrechte nicht dadurch ersetzen.
- Retention, Widerruf und Löschung von Evidence, Cache und Aggregationsbeiträgen
  nachweisbar abstimmen. Katalogbild-Metadaten getrennt vom ScanLab behandeln.
- Dependency-Verifikation/Locks sowie CI für reproduzierbare Offline- und
  Emulatorprüfungen entwerfen. Signier-Secrets nie in einer ungeprüften CI anlegen.
- Historische UI-Ankündigungen und widersprüchliche Preis-/Gradingtexte in einem
  ausdrücklich freigegebenen späteren Produktlauf korrigieren.
- Lizenz-/Marken-/Datenrechte und tatsächliche Betriebsprozesse vom Betreiber
  prüfen lassen; technische Tests ersetzen diese Prüfung nicht.

## Langfristige Ideen – nicht in aktiver Entwicklung

Weitere TCGs sind mögliche Erweiterungen, aber nicht aktuell unterstützte Systeme.
Ein eigenes DomiDex-Grading ist lediglich eine frühe Zukunftsidee: **nicht
implementiert, nicht aktive Entwicklung, nicht für den nächsten Release**, ohne
Zeitplan, Skala oder Geschäftsmodell. Keine integrierte P2P-Tauschplattform als
bestehendes Kernfeature darstellen.

## Wirtschaftlicher Rahmen

Das langfristige Monetarisierungsmodell befindet sich in strategischer Prüfung,
damit laufende Infrastruktur-, Daten- und Dienstkosten nachhaltig gedeckt werden
können. Kein Modell wurde festgelegt. Keine Aussage dieser Roadmap ändert
frühere Testerzusagen oder kündigt Abos, Werbung, Credits oder neue Preise an.
