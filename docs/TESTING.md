# Tests und Nachweisgrenzen

Aktuelle Engineering-Fortschritte nach dem untenstehenden Dokumentationsaudit stehen
im [Release-27-Gateledger](RELEASE_27.md). Die datierten Baseline-Zahlen hier bleiben
historische Nachweise; sie sind nicht die neueste Testzahl oder eine Releasefreigabe.

Stand: 2026-10-01. Die folgenden aktuellen Tests beziehen sich auf den **lokalen
Working Tree**. Sie zertifizieren weder den älteren GitHub-Checkout noch das
signierte v26-AAB oder einen neuen Release.

## Neu ausgeführt in diesem Dokumentationsaudit

| Prüfung | Ergebnis | Grenze |
| --- | --- | --- |
| `:app:testDebugUnitTest --rerun --offline --no-daemon` | 107 Suites, **630 Tests: 629 bestanden, 1 übersprungen**, 0 Fehler | Optionaler Cardmarket-Live-Smoke-Test aus; keine Kamera/Cloud-E2E |
| `node --test functions/knowledge-aggregate.test.js` | **3/3** | Reine Aggregationslogik, kein Functions-Deployment |
| `node --test scripts/diagnostics/catalog-integrity-policy.test.mjs` | **14/14** | Regeln für ehrliche Katalogberichte, kein neuer Live-Preisdownload |
| `node --test scripts/diagnostics/catalog-integrity-report.test.mjs` | **1/1** | Synthetischer vollständiger Bericht, nicht alle echten Karten |
| `python -m unittest discover -s scripts/release/tests -p test_native_elf.py -v` | **11/11** | Lokaler noch unversionierter Verifier; kein APK/AAB |
| `python -m unittest discover -s scripts/release/tests -p test_native_licenses.py -v` | **16/16** | Offline-Lizenz-/Archivvalidierung; keine Lizenzfreigabe |
| `:app:dependencies --configuration releaseRuntimeClasspath --offline --no-daemon` | **Erfolgreich**, effektive Versionen erneut bestätigt | Nur Dependencybericht, kein Release-Build/Download |
| Firestore-/Storage-Regeltests in lokalen Emulatoren | **18/18** (11 Firestore, 7 Storage), 0 Fehler | Bestehende Policies; keine vollständige Abdeckung des neuen Preis-Sidecars |

Der erste Gradle-Aufruf war `UP-TO-DATE`; anschließend wurde die Testaufgabe mit
`--rerun` tatsächlich erneut ausgeführt. JUnit-XML: 01.10.2026,
00:14:32–00:14:51 UTC, `app/build/test-results/testDebugUnitTest/`.
Es lief **kein** `assemble*`, `bundleRelease`, Signierhelfer oder Installationsbefehl.
Die Archive-Testklasse, die eine synthetische `fake.aab` schreibt, wurde in diesem
Auftrag bewusst nicht erneut ausgeführt.

Die Firebase-Regeltests liefen ausschließlich im isolierten Projekt
`demo-domidex-firestore` mit bereits vorhandenen lokalen Emulatoren und identischen
Testkopien der Regeln. Nichtlokale Node-Netzverbindungen waren blockiert. Der erste
Anlauf endete vor Testbeginn am CLI-Pfadschutz; mit Regelkopien im Testverzeichnis
bestand der erneute Lauf alle 18 Tests. Keine Produktionszugriffe oder Änderung
veröffentlichter Regeln. Ergebnis: lokales, Git-ignoriertes
`artifacts/repository-audit-20261001/firebase-emulator/test-output-rerun.log`.
Alle Testemulatoren wurden anschließend beendet.

## Vorhandene Tests

Im auditierten Arbeitsverzeichnis: 107 JVM-Kotlin-Testdateien und 73 Instrumentation-
Dateien; bei HEAD sind davon 91 beziehungsweise 51 versioniert. Dateianzahl ist
nicht Testfallanzahl und nicht die Zahl tatsächlich ausgeführter Geräteprüfungen.

Abgedeckt werden unter anderem Parser/Identität, Varianten, Preise/Portfolio,
Binder-Slots und Refresh, Sammlung-/Owner-Isolation, Konflikte/Outbox, Room-
Migrationen, Backups/Recovery, Consent, Scanner-Queue/OCR-Lifecycle/Orientierung,
Bildeditor, Google-Konto-UI, Navigation, Portrait und Sharing.

- `app/src/test/`: JVM-Prüfungen und optionaler Live-Smoke-Test.
- `app/src/androidTest/`: Android-/Compose-/Room-/Kamera- und Gerätetests.
- `firebase-tests/`: Firestore-/Storage-Policytests.
- `functions/knowledge-aggregate.test.js`: serverseitige Vote-Entscheidung.
- `scripts/diagnostics/`: Snapshot-/Quarantäne-/Berichtssicherheit.
- Lokales `scripts/release/tests/`: Symbol-/Artefakt-/Lizenzverifikation;
  noch kein vollständiger auf GitHub verfügbarer Release-Workflow.
- Lokales `scripts/scanner/`: Fixture-/Benchmarkvergleiche, nicht automatisch
  standardisierte reale Gerätebenchmarks.

## Historische Belege – nicht heute wiederholt

| Datum / lokaler Nachweis | Ergebnis |
| --- | --- |
| 25.09.2026, `artifacts/release27-development/emulator-final-core.log` | 54 bestandene Tests |
| 25.09.2026, `…/emulator-final-navigation-sharing.log` | 33 bestandene Tests |
| 24.09.2026, `artifacts/scanner-v27/final-serialized-core.log` | 29 bestandene Tests |
| 24.09.2026, `…/final-offline-regression.log` | 13 bestandene Tests |
| Release-Lint vom 23.09.2026 | 0 Fehler, 169 Warnungen, 2 Hinweise |
| Debug-Lint vom 22.09.2026 | 0 Fehler, 167 Warnungen, 2 Hinweise |

Diese lokalen Artefakte sind absichtlich Git-ignoriert. Ältere fehlerhafte Läufe
wurden nicht als Erfolg gezählt. Historischer Debug-/Emulatortest ist kein neuer
Play-signierter End-to-End-Test. Lint wurde im Dokumentationsaudit nicht erneut
als aktuelle App-Freigabe ausgegeben.

Scanner-Benchmarks zeigen gemischte Ergebnisse: ein verifizierter Vergleich mit
34 Beobachtungen hatte 26 akzeptierte Treffer auf beiden Seiten und eine leicht
langsamere Medianzeit nach Änderung; ein anderer 90er-Vergleich hatte keine
beidseitig verifizierten Input-Hashes. Daraus folgt **keine** allgemeine Aussage
„schneller und genauer als Release 26“.

## Reproduzierbar prüfen, ohne ein App-Paket zu bauen

Voraussetzungen: passendes SDK/JDK, lokale Firebase-Clientkonfiguration und bereits
aufgelöste Dependencies für `--offline`. Zuerst [Buildstand](BUILD_AND_RELEASE.md)
lesen; nicht auf einem alten Clone die Verfügbarkeit lokaler Tests annehmen.

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun --offline --no-daemon
node --test functions/knowledge-aggregate.test.js
node --test scripts/diagnostics/catalog-integrity-policy.test.mjs scripts/diagnostics/catalog-integrity-report.test.mjs
git diff --check
```

Kein allgemeines `gradlew build`: das würde hier unnötig Paketerstellung anstoßen.
Instrumentation erfordert passende vorhandene Test-APKs oder einen gesondert
autorisierten Build-/Installationslauf; im Audit wurden keine erzeugt.

## CI und nächste Test-Gates

Keine `.github/workflows` und keine anderen CI-Pipelinekonfigurationen gefunden;
GitHub meldet am Auditdatum **0 Actions-Runs**. Diese Aufgabe aktiviert keine CI.
Künftige Gates: reproduzierbare Quellstände, ganze lokale Unit-Suite,
Sidecar-Regelgrenzen, erlaubte/verweigerte/widerrufene Konten, zwei echte Geräte,
Offline-/Konflikt-/Löschpfade, schwierige Scannerkarten und exakte AAB-/Mapping-/
Symbolbindung. Tests mit Fixtures sind nie ein Nachweis lückenloser Live-Preise.
