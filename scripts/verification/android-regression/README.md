# Feste lokale Android-Regressionsmatrix (Release 27 WIP)

Dieser Runner reproduziert die **Auswahl** des erfolgreichen historischen
`artwork-http-final-regression371-instrumentation.log`: 371 konkrete
`class#method`-Identitäten. Er ist kein Release-, Build- oder Deploymentwerkzeug.
Der neue Runner selbst muss separat lokal verifiziert werden; der alte erfolgreiche
Lauf ist kein Ausführungsnachweis für dieses Skript oder einen späteren APK-Stand.

Erste Root-Verifikation am 01.10.2026: **24/24 reine Runner-Vertragstests**, lesender
Preflight PASS und **371/371 tatsächliche Emulatorfälle PASS**, keine Skips/Fehler,
272,227 Sekunden Instrumentierung. Manifest: `artifacts/android-regression/
20261001T061326Z-65675803/manifest.json`, Exit 0, unveränderte APK-/Quellinputs,
Offlinezustand und leere Reverse-Liste danach bestätigt. Dieser datierte Nachweis
gilt für die dort gehashten Dateien, nicht automatisch für spätere Änderungen.

## Voraussetzungen und Aufruf

Nur Windows, bereits installiertes Python 3.11+, Android SDK Build Tools **36.0.0**,
Platform Tools mit ADB-Protokoll 41 sowie Android Studio JBR. Keine Downloads.
Ein vorhandener lokaler ADB-Server muss auf `127.0.0.1:5037` bereits laufen.
Der Runner startet/beendet diesen Server und den Emulator nicht.

Der separat gestartete **exklusiv freigehaltene** Emulator muss genau
`emulator-5554`, AVD `DomiDex_R27_Baseline_API36`, API 36 sein, Flugmodus an,
WLAN aus, kein aktives Standardnetz und keine ADB-Reverse-Verbindungen.
Keine andere Instrumentierung und kein vorhandener DomiDex-/Testprozess dürfen
laufen. Vorhandene Prozesse werden beim Preflight **nicht** beendet.
Während eines Laufs keine parallelen Installationen, Instrumentierungen,
Source-/APK-Änderungen, AVD-Neustarts oder IDE-Aktionen auf diesem Emulator.
Diese organisatorische Exklusivität ist keine vom Betriebssystem erzwungene Sperre.

Im Repository-Stamm:

```powershell
python -I -B scripts/verification/android-regression/test_runner.py
python -I -B scripts/verification/android-regression/run.py --check
python -I -B scripts/verification/android-regression/run.py --run
```

`-I -B` ist zwingend: isolierter Pythonstart ohne `PYTHONPATH`/Benutzer-Site-Hooks,
keine Bytecode-Dateien. Die Kindprozessumgebung übernimmt keine ADB-Endpunkte,
Geräteauswahl, Javaoptionen, Python-Startupvariablen, Proxy-/Cloud-/Signingvariablen.
Das ist eine begrenzte Umgebungsisolation, **keine OS-/Netzwerksandbox**.
Werkzeugpfade dürfen mit `--sdk-root <SDK-Verzeichnis>` und
`--java-home <JBR-Verzeichnis>` explizit auf bereits geprüfte Installationen zeigen.
Es gibt keine Parameter für Geräte, APKs, Tasks, Selektoren oder freie Befehle.

`--check` prüft ausschließlich vorhandene Dateien, APK-Metadaten/Signaturen und
lesbare Emulator-Voraussetzungen. Keine Installation, Instrumentierung, Ausgabe-
verzeichnisse, Force-Stops, Netzwerkumschaltung oder Geräteänderung. Es liest den
öffentlichen Zertifikatsanteil der APKs, nie einen privaten Signierschlüssel.

`--run` installiert ausschließlich:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

Beide müssen debuggable sein, die erwarteten Paketnamen tragen und mit demselben
einzelnen normalen Android-Debugzertifikat signiert sein. Der Test-APK-Manifestbaum
muss exakt den vorgesehenen Runner und das App-Ziel enthalten. Der feste Aufruf
`install -r -t` erlaubt das Test-only-Attribut normaler Debug-Testartefakte; es gibt
keinen Wiederholungs-/IDE-/Release-Fallback und keine Deinstallation. Insbesondere
werden `intermediates/apk/debug` und ein angeschlossenes Xiaomi niemals verwendet.
Fehlen Standard-APKs, endet der Runner vor jeder Installation.

## Build und JVM-Tests bleiben bewusst getrennt

Nur der freigegebene Build-Verantwortliche erstellt bei Bedarf vorher die normalen
Debug-/Test-APKs. Ein Beispiel für die getrennte Offline-Prüfung im bereits korrekt
eingerichteten lokalen Buildumfeld ist:

```powershell
.\gradlew.bat --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:compileReleaseKotlin :app:lintDebug :app:lintRelease
```

Das ist **kein vom Runner gestarteter Befehl** und keine Freigabe für zusätzliche
Builds/Schlüssel. Lokale Gradle-/Signingbedingungen müssen zuvor separat geprüft
werden. Keine `bundleRelease`-/`assembleRelease`-/Publishing-Tasks aufrufen.
`compileReleaseKotlin` kompiliert Quellcode, erzeugt aber kein signiertes Release.
Kein `connectedDebugAndroidTest`, das unbeabsichtigt weitere Geräte auswählen könnte.

Vor dem ersten Runnerlauf wurden zwei reine Harness-Reviewbefunde korrigiert:
API 36 unterstützt `dumpsys activity instrumentation` hier nicht; der Preflight
verwendet daher den tatsächlich lesbar bestätigten `dumpsys activity processes`-
Header und verweigert unbekannte/aktive Instrumentierungszustände. Außerdem hätte
die anfängliche vollständige VisualSelectors-Klasse 13 nicht ausgewählte Tests
hinzugenommen; sie wurde auf die belegten 9 Einzelmethoden begrenzt. Das sind keine
behaupteten Android-Produkt-REDs, sondern vor Ausführung korrigierte Runnerfehler.

## Auswahl, Nachweis und Grenzen

`selection.json` enthält unverändert die 371 historisch bestandenen Methoden mit
SHA-256 des damaligen Logs. Das ignorierte historische Log wird zur Laufzeit nicht
benötigt. Die kompakte Invocation verwendet **49 vollständige Klassen und 12
Einzelmethoden**: 9 aus `PrivateBinderVisualSelectorsInstrumentedTest`, 3 aus
`ScannerFastInputInstrumentedTest`. Diese beiden Klassen werden ausdrücklich nicht
vollständig ausgeführt (22 Visual-Tests bzw. zusätzlicher Opt-in-Gallery-Benchmark).
Damit 61 Selektoren innerhalb des überprüften Argumentbudgets; keine Batches und
keine Testlistendatei auf dem Gerät.

Ein PASS erfordert jede erwartete Identität genau einmal mit Start und PASS,
vollständigen Abschluss, korrekte Gesamtsumme, **0 Skip/Ignore/Fehler/Duplikate/
unerwartete Tests**. Neue Methoden in einer vollständig gewählten Klasse führen
zum bewussten Fehler; die Auswahl wird nicht automatisch erweitert und Zahlen
werden nicht an ein zufälliges Ergebnis angepasst. Eine Änderung braucht Review.

Unter dem ignorierten `artifacts/android-regression/<UTC>-<ID>/` entstehen nur bei
`--run` Installationsbeleg, Instrumentierungslog und Manifest mit APK-Hashes vor/
nach dem Lauf, Quell-/Selektionshashes und genauen Ergebnissen. Ein geänderter Input
oder unbestätigter Abschluss ist kein PASS. Quellhashes belegen den lokalen Stand,
**nicht**, dass ein schon vorhandenes APK reproduzierbar genau daraus gebaut wurde;
dafür ist der separate Buildnachweis erforderlich. Produkt- und Releaseartefakte
werden nicht geschrieben oder ersetzt.

Der feste Timeout beträgt 20 Minuten, das Instrumentierungslog ist auf 16 MiB
begrenzt. Abbruch/Timeout beendet nur den eigenen ADB-Kindprozess. Ein Paket-Stop
nach einem begonnenen fehlgeschlagenen Testlauf ist zusätzlich nur bei erneut
bestätigtem AVD und sichtbarer, exakt tokengebundener aktiver eigener Invocation
zulässig. Leerer/unklarer Dump oder andere Invocation: **kein Paket-Stop**, fehlende
Bereinigung wird im Manifest angegeben und muss vom Verantwortlichen geprüft
werden. **Im ersten tatsächlichen API-36-Lauf wurde der Token nicht sichtbar:**
Der Dump enthielt `mArguments=Bundle[mParcelledData.dataSize=9124]` statt Klartext
und dieselbe aktive Instrumentierung an zwei Stellen. Damit erlaubt der aktuelle
Ownership-Check ausdrücklich keinen Paket-Stop. Die positive Cleanup-Unitfixture
ist kein Live-Nachweis; bei einem solchen Abbruch muss der Verantwortliche den
AVD-Zustand prüfen. Erfolgreiche Läufe stoppen keine Appprozesse automatisch.

Die Matrix ist eine feste lokale Funktionsregression. Sie ist keine komplette
Scanner-Qualitäts-/Latenzmessung, kein Firebase-/Produktions-/Xiaomi-E2E-Test, keine
Releasefreigabe und aktiviert weder den vorbereiteten Delta-Pull noch andere
ungeprüfte Produktionspfade. Renderer-/Host-AVD-Abstürze werden als fehlgeschlagener
Lauf dokumentiert, nicht ungeprüft als Android-Produktfehler umgedeutet.

## Separately owned regression emulator

The optional exact flag `--owned-regression-avd` selects only
`DomiDex_R27_Regression_API36` / `emulator-5556`. Omitting it preserves the original
baseline profile and its behavior. **Do not use the baseline `--run` when its
ordinary user installation must remain unchanged.** There is no arbitrary-device
or selector override. Both profiles retain the same exact371 identities, debug
certificate, package, offline, input-freeze and cleanup-ownership checks.

The runner never provisions or starts an AVD. Before using the additional profile,
independently record ownership of its freshly initialized data directory, exact
system image, launch arguments and camera configuration; do not copy baseline
userdata/accounts/snapshots. Prepare it offline before any test and avoid host
webcam/microphone passthrough. A different AVD is a functional-regression target,
not an interchangeable performance baseline.

```powershell
python -I -B scripts/verification/android-regression/test_runner.py
python -I -B scripts/verification/android-regression/run.py --check --owned-regression-avd
python -I -B scripts/verification/android-regression/run.py --run --owned-regression-avd
```
