# Roadmap – nächste Prüfziele, keine Release-Zusage

Stand: 2026-10-01. Ausgangspunkt ist [PROJECT_STATUS](PROJECT_STATUS.md).
Dieser Dokumentationslauf implementiert keinen Punkt dieser Liste.

## Vor dem nächsten Release

1. **Quellstand nachvollziehbar sichern:** lokale Entwicklung, GitHub und
   Release-Provenienz getrennt prüfen; bisher fehlende Produktdateien später
   gezielt reviewen/übernehmen. Kein blindes Pushen des kompletten Working Trees.
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
6. **Release-Gate:** unveränderte R8-Sicherheitsgrenzen, Mapping zum exakten AAB,
   nachgewiesene Native-Symbol-Abdeckung, ehrlicher Hersteller-Blocker; finaler
   Geräte- und Google-Play-Kompatibilitätstest nach separatem Go.

Version 27 ist dabei ein Entwicklungsziel, nicht das Ergebnis dieses Audits.
Keine neue Version, kein Tag, kein Build, keine Signierung und kein Deployment
werden durch diese Roadmap freigegeben.

## Hardening und Wartbarkeit

- App-Check-Provider/Play-Integrity-Eignung und schrittweise Enforcement-Strategie
  separat prüfen; bestehende UID-/Serverrechte nicht dadurch ersetzen.
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
