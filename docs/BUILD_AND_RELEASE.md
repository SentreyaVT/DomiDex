# Build, Signierung und native Diagnose

Stand **04.10.2026**. Das vorhandene R27-AAB ist signiert und im internen sowie
geschlossenen Alpha-Test verfügbar. Diese Dokumentationsfortschreibung baut,
signiert oder veröffentlicht nichts erneut. Die [Entwicklerübergabe](DEVELOPER_HANDOFF_R27.md)
und [Versionshistorie](RELEASE_HISTORY.md) trennen Branch, Buildquellen, Binärarchiv
und Play-Status. Offene Gates bleiben im [Ledger](RELEASE_27.md) maßgeblich.

## Unterschiedliche Quell-/Release-Stände

| Ebene | Nachgewiesener Stand |
|---|---|
| Aktueller Engineeringbranch | `codex/release-27-hardening`, remote bestätigter Sicherungscheckpoint `06190b2` vor dieser Docs-Fortschreibung |
| Build-Quellcommit des R27-AAB | `a3c7aa7ddef8987faa285bebf1bd8779313baae4`; Eingabesnapshot vor/nach Build gleich |
| Signiertes Bundle / Play-Testtracks | Code **27** / **`1.0-closed-alpha.6`**, INTERNAL und Closed Alpha live; Alpha am 04.10. um 21:54 Europe/Berlin bestätigt |
| GitHub-Standardbranch `master` | `b2e41ee`, aktualisierter README-Einstieg per Docs-only-PR; enthält den aktuellen R27-Produktstand nicht |
| GitHub-Binärarchiv R27 | [archive/v27-1.0-closed-alpha.6](https://github.com/nandedomi/DomiDex/releases/tag/archive/v27-1.0-closed-alpha.6), veröffentlicht 04.10. um 22:47:21 Europe/Berlin; Vorabversion, kein Entwurf; Zielcommit `a3c7aa7` |

Der unabhängige Quellenabgleich bestätigt **638 Quell-Dateihashes** exakt gegen
`a3c7aa7`, bei sauberen versionierten Quellen. `source_dirty_at_archive=true`
bezieht sich auf zehn ausdrücklich inventarisierte untracked `hardware-proof`-
Dateien, die nicht in den gewöhnlichen Build eingehen; das ist kein Beleg für
ungeprüfte Änderungen am App-Code. Private lokale Konfiguration, Toolchain und
Signing bleiben außerhalb einer vollständigen Git-Reproduzierbarkeitsgarantie.

### Historischer Ausgangsstand am 01.10.2026

Die folgenden Befunde erklären die damalige Diskrepanz und werden nicht als
aktueller Signierungs- oder Veröffentlichungsstatus fortgeführt:

| Ebene beim ursprünglichen Audit | Damaliger Stand |
|---|---|
| GitHub-Standardbranch `master` | `9bbe7bc`, App-Code 16 / `1.0-beta` |
| GitHub-Branch `codex/ui-v3` vor Dokumentationsabgleich | `77b76cf`, Code 19 / `1.0-pre-beta` |
| Lokaler Produkt-HEAD `096aa35` | Code 21 / `1.0-pre-beta.1` in der committed Konfiguration |
| Lokaler, bereits vorher veränderter Arbeitsstand | Code **27** / **`1.0-closed-alpha.6`**, nicht als signiertes Release abgeschlossen |
| Damals letztes geprüftes signiertes AAB / Play | Code **26** / **`1.0-closed-alpha.5`** |

Der R27-Engineeringbranch ist inzwischen gesichert. Ein Clone von `master` oder ein
automatisches Source-ZIP eines Archiv-Tags ersetzt weder den Build-Eingabesnapshot
noch den Nachweis eines bytegenau reproduzierbaren AAB. Version und Originaldateien
werden durch diese Dokumentation nicht verändert.

## Lokaler Entwicklereinstieg

Den Branch ausdrücklich auswählen und lokale Voraussetzungen bereitstellen:

```powershell
git clone --branch codex/release-27-hardening https://github.com/nandedomi/DomiDex.git
cd DomiDex
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

Vor dem Gradle-Aufruf müssen passendes JDK/Android SDK, `local.properties` und die
berechtigt bereitgestellte `app/google-services.json` vorhanden sein. Keine
Signing-Zugangsdaten sind für diesen JVM-/Debug-Lint-Aufruf erforderlich. Unter
POSIX wird derselbe Taskaufruf mit `./gradlew` gestartet. Er installiert nichts und
erzeugt kein AAB. Testberichte liegen unter `app/build/test-results/testDebugUnitTest/`,
Debug-Lint unter `app/build/reports/lint-results-debug.xml`; vorhandene Berichte
allein beweisen keinen frischen Testlauf.

Ein gewöhnlicher Debug-Build verwendet `.\gradlew.bat :app:assembleDebug` und
erzeugt eine APK, installiert sie aber nicht. Für Instrumentation gelten die
einzeln dokumentierten Emulator-/Hardwareprofile und Schutzregeln; keine pauschale
Freigabe für Tests auf einem angemeldeten Benutzergerät. Vollständiger Ablauf und
aktuelle Grenzen: [Entwicklerübergabe](DEVELOPER_HANDOFF_R27.md).

## Android-Konfiguration

- Ein Android-Modul `:app`; Projektname `DomiDex`.
- Namespace und Application ID: `com.dominik.cardvault`.
- `minSdk=26`, `compileSdk=37`, `targetSdk=37`.
- Source Sets: `main`, `test`, `androidTest`, `release`; keine Product Flavors.
- Build Types: `debug` und `release`; Release auf `arm64-v8a` begrenzt,
  Debug unterstützt die Emulator-ABIs der Dependencies.
- Gradle **9.5.0**, AGP **9.3.1**, Compose-Kotlin-Plugin **2.2.10**, KSP **2.3.10**.
- Gradle-Daemon-Toolchain **Java 25**; Java-Quell-/Bytecodeziel **11**.
  Der vorhandene Buildnachweis verwendete Android-Studio-JBR **25.0.2**.
- Compose und BuildConfig aktiv; Room-Schemaexport unter `app/schemas`;
  Instrumentation-Runner und AndroidX Test Orchestrator konfiguriert.
- Kein eigenes C/C++-/ASM-Modul, kein CMake-/ndk-build-Aufruf, kein expliziter
  `ndkVersion`-/`ndkPath`-Eintrag. Alle sieben Release-Natives kommen aus AARs.

Quellen: `settings.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, Wrapper-/Daemon-Konfiguration.
`app/google-services.json` ist lokale, ignorierte Clientkonfiguration und muss
für einen berechtigten Entwickler separat bereitgestellt werden.

## Libraries: deklariert ist nicht immer aufgelöst

Aufgelöste Werte wurden am **01.10.2026** mit Gradles Offline-Dependencybericht
für `releaseRuntimeClasspath` erneut bestätigt. Zusätzlich liegt der Metadatenexport
vom 24.09.2026 unter `app/build/outputs/release-archive/metadata.json` vor.
Beides beschreibt den jeweiligen Checkout, nicht eine vollständige historische
Quellprovenienz von Release 26. Diese historische Tabelle ist keine neue vollständige
Auflösung aller R27-Testabhängigkeiten; beispielsweise wurde Espresso für den
Android-17-Nachweis inzwischen auf 3.7.0 aktualisiert. Maßgeblich sind die gepinnten
Eingaben des jeweiligen Laufs und die aktuelle Versionsdatei.

| Bereich | Deklaration / belegte Auflösung |
|---|---|
| AndroidX Core KTX | 1.10.1 angefordert → 1.16.0 aufgelöst |
| Lifecycle Runtime | 2.6.1 angefordert → 2.9.4 aufgelöst |
| Activity Compose | 1.8.0 angefordert → 1.8.2 aufgelöst |
| Compose | BOM 2026.02.01; UI/Runtime/Foundation 1.10.4, Material3 1.4.0 |
| CameraX | 1.6.1; Viewfinder Core transitiv 1.5.1 |
| Room / WorkManager | 2.8.4 / 2.11.2 |
| DataStore / Graphics Path | transitiv 1.1.7 / 1.0.1 |
| Firebase | BOM 34.17.0; Auth 24.2.0, Firestore 26.5.0, Storage 22.0.1, Remote Config 23.1.0 |
| ML Kit | Text Recognition 16.0.1; Bundled Common 17.0.0 |
| OpenCV | 4.13.0 |
| Coil / Moshi / OkHttp | 3.3.0 / 1.15.2 / 4.12.0 |
| Credentials / Google ID / Play Updates | 1.3.0 / 1.1.1 / 2.1.0 |
| WindowManager | 1.5.0 |
| Coroutines / Serialization Core | 1.10.2 / 1.8.1 |
| Functions | Node 22; Lockfile: firebase-admin 14.3.0, firebase-functions 7.3.2 |
| Firebase-Regeltests | Lockfile: Firebase 12.18.0, rules-unit-testing 5.0.2 |

Transitives Firebase App Check 19.4.0 ist kein Nachweis einer eingerichteten
Attestation oder serverseitigen Erzwingung. Der Gradle-Wrapper hat eine
ZIP-Prüfsumme; Gradle Dependency Locking/Verification ist nicht eingerichtet.

## Signierung: vorhandenen Schlüssel schützen

Gradle liest entweder die ignorierte `keystore.properties` oder vier kurzlebige
Signing-Umgebungsvariablen. Unvollständige Konfiguration, fehlender Keystore oder
eine als `debug.keystore` benannte Release-Datei werden zurückgewiesen. Ohne
Release-Zugangsdaten gibt es keinen automatischen Rückfall auf den Debugschlüssel.

Der vorhandene Windows-Helfer verwendet den außerhalb des Repositories liegenden
Upload-Keystore und eine Windows-DPAPI-geschützte Credential-Datei. Passwörter,
private Schlüssel und echte Signing-Dateien gehören niemals in Git oder Berichte.
Eine DPAPI-Datei allein ist kein portables Backup: Keystore und wiederherstellbares
Passwort müssen separat geschützt gesichert werden. Keine Schlüssel neu erzeugen.
Upload-Zertifikat und Google-Play-App-Signing-Zertifikat sind unterschiedliche Rollen.

## R8: behoben, aber bewusst konservativ

In Release 27 und im früher geprüften Release 26 ist R8 aktiv. Die echte
Zuordnung liegt im AAB unter
`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`.
`-dontshrink`, `-dontoptimize` und deaktiviertes Resource Shrinking erhalten den
konservativen ersten Rollout. Gezielte JNI-Regel für `OpenCV CvException` und
drei begrenzte `dontwarn`-Regeln für optionale alte gRPC/OkHttp-2-Typen bestehen.
Nicht als vollständige Größen-/Performanceoptimierung beschreiben.

Die Play-Detailansicht von Version 26 zeigte am 01.10.2026: **0 % Optimierung,
0 % Shrinking, 96 % Obfuscation** sowie eine vorhandene `mapping.txt` (Anzeige 104 MB).
Das war eine damalige Console-Anzeige, keine neu gemessene R27-Prozentzahl.
`app/src/release/keepRules/release.keep` ist inzwischen versioniert. Das echte
R27-Mapping wurde eingebettet, im Archiv abgeglichen und in Play erkannt.

## Native Symbole: genau ein verifiziertes Original von sieben

Beim historischen Release-26-Audit wurden alle sieben ARM64-Dateien mit den
Original-AAR-Bytes verglichen: **7/7 identisch**. Bereits die Anbieterdateien waren
gestrippt, ohne `.symtab` und DWARF. Für R27 wurde die partielle libc++-Zuordnung
erneut gegen das tatsächlich signierte Bundle geprüft; die sechs fehlenden
Herstelleroriginale bleiben offen. Play-Annahme der ZIP bedeutet keine Vollabdeckung.

| Datei | Herkunft | Passende zusätzliche Symbole |
|---|---|---|
| `libc++_shared.so` | OpenCV 4.13.0; Original-Laufzeit aus NDK r27c | **Ja**, echte Symboltabelle, kein DWARF |
| `libopencv_java4.so` | `org.opencv:opencv:4.13.0` | Nicht verfügbar/verifiziert |
| `libmlkit_google_ocr_pipeline.so` | `com.google.mlkit:text-recognition-bundled-common:17.0.0` | Nicht verfügbar/verifiziert |
| `libandroidx.graphics.path.so` | `androidx.graphics:graphics-path:1.0.1` | Nicht verfügbar/verifiziert |
| `libdatastore_shared_counter.so` | `androidx.datastore:datastore-core-android:1.1.7` | Nicht verfügbar/verifiziert |
| `libimage_processing_util_jni.so` | `androidx.camera:camera-core:1.6.1` | Nicht verfügbar/verifiziert |
| `libsurface_util_jni.so` | `androidx.camera:camera-core:1.6.1` | Nicht verfügbar/verifiziert |

Das separate ZIP enthält ausschließlich `arm64-v8a/libc++_shared.so`. Build-ID
`b04675a35ad96f8a9dcaa073e3bd31d4536f00ad`, geladene ELF-Bereiche, Laufzeitbytes und
Symboladressen wurden erneut geprüft. Abdeckung: **SYMBOL_TABLE, nicht FULL**.
Der bestehende AGP-9.3.1-Default ist bereits SYMBOL_TABLE; FULL rekonstruiert keine
entfernten Herstellerdaten. Fehlende öffentliche Originale sind kein Beweis,
dass der Hersteller intern keine Symbole besitzt. Ein beliebiger Source-Rebuild
oder bloß gleicher Dateiname ist kein zulässiger Ersatz für dasselbe Linkergebnis.

### Identität der unveränderten Release-27-Artefakte

Archivbasis: `releases/v27/` (lokal, Git-ignoriert). Dieselben vorhandenen Bytes
sind im [GitHub-Binärarchiv R27](https://github.com/nandedomi/DomiDex/releases/tag/archive/v27-1.0-closed-alpha.6)
veröffentlicht; alle sechs hochgeladenen Assets wurden per Hash abgeglichen:

| Artefakt | SHA-256 |
|---|---|
| `DomiDex-v27.aab` | `2c3fe33bb84c47fda4525297a8134859f44fd727dabf268bade401d36f276cc7` |
| `mapping.txt` | `8900194e5eaa4042f46e4043eba099062d30d60f7974d533cee7868649448c8e` |
| `native-debug-symbols.zip` | `cbef5ac45ae8c7c870d40cc8ea77fa75da8b9bfe1c8d7a556be65f776e30e687` |

Zusätzlich enthält das Archiv Prüfsummen, Release Notes und Toolchain-Hinweise.
Es wurde kein neuer Build erstellt und keine Datei erneut signiert.

AAB: 52.705.225 Bytes, nicht debuggable, `arm64-v8a`, minSdk26/targetSdk37.
Bestehendes Upload-Zertifikat, Bundle-Struktur und eingebettetes Mapping wurden
verifiziert; die partielle ZIP wurde diesem AAB in Play zugeordnet. Die Pipeline
bestand 39 Prüfungen. JVM: 1.062 PASS und ein unveränderter optionaler Live-Skip;
Release-Lint: keine Fehler, 170 Warnungen und vier Hinweise. Details, Herkunft und
Eingabesnapshots stehen im [R27-Ledger](RELEASE_27.md). Das ist kein Nachweis eines
neuen physischen ARM64-/minifizierten End-to-End-Laufs oder aller Release-Gates.

### Historische Identität der unveränderten Release-26-Artefakte

Archivbasis: `releases/google-play-v26-closed-alpha-2026-09-23/` (lokal, Git-ignoriert).

| Artefakt | SHA-256 |
|---|---|
| `DomiDex-1.0-closed-alpha.5-v26-SIGNED.aab` | `079608e27bcb5d440252ed727e238b162b449f3dba4b1c28986d4a4570d9b14b` |
| Eingebettete/gesicherte `mapping.txt` | `e6a081b27940982450cb40cbf4be09d393aa3a79bf9f8b6931af806b5d5c204e` |
| `native-debug-symbols-PARTIAL/native-debug-symbols.zip` | `04dcb9585023ec99e89858965a5758bcc3bd12b1ea0652c8ff75cce17eab8c28` |

AAB-Größe: 52.352.301 Bytes. AAB-/ZIP-Integrität und libc++-Zuordnung wurden am
01.10.2026 nur lesend nachgeprüft. Play zeigt Version 26 ein natives Symbol-ZIP
(Anzeige 512 KB) zugeordnet. Ohne erneuten Download ist dessen Byteidentität nicht
frisch nachgewiesen. **Das beweist weder vollständige Abdeckung noch das Verschwinden
der Native-Warnung.** Die sechs fehlenden Herstellerdateien bleiben Diagnosegrenzen.
Play erkennt für dieses AAB 16-KB-Unterstützung; das ersetzt keinen realen Gerätetest.

## Lokale Pipeline und verbleibende Übergabegrenzen

Der lokal erweiterte `scripts/release/Build-SignedBundle.ps1` schützt alte Codes und
vorhandene Archive, startet Tests, erstellt Release-/Debug-Artefakte und prüft danach
Signatur, Mapping, Herkunft, ELF-Identität und Lizenzen. Es veröffentlicht nicht in Play.
**Nicht als Dokumentationstest starten:** Der Helfer baut und signiert ausdrücklich.

`Build-SignedBundle.ps1`, `archive_release.py`, ELF-/Lizenzprüfer, Registry,
Init-Skript und zugehörige Tests sind inzwischen im Engineeringbranch versioniert.
Der erfolgreiche R27-Lauf ist im Ledger dokumentiert. Die Pipeline wird nicht
automatisch durch einen GitHub-Push ausgeführt; ein Binärarchiv ist eine getrennte
Veröffentlichung vorhandener Dateien. Vorhandene untracked `hardware-proof`-
Helfer gehören nicht zum gewöhnlichen R27-Build und bleiben davon getrennt.

Ein weiterer Release-Build braucht eine eigene Prüfung und Freigabe; vorhandene
Archive und bereits verwendete Versionscodes dürfen nicht überschrieben werden.
Das aktuelle Testrelease ist keine Behauptung, dass Hardware-/Mehrgeräte-,
Betriebs-/Datenschutz- und alle Known-Issue-Gates geschlossen sind. Insbesondere
der am 04.10. um 20:32 UTC beobachtete Web-React-Fehler 185 beim Sammlungsscrollen
ist noch offen; ein neues Android-Bundle würde diesen Webfehler nicht beheben.
