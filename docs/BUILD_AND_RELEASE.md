# Build, Signierung und native Diagnose

Technische Bestandsaufnahme vom **01.10.2026**. Kein neuer App-Build, keine
Signierung und kein Play-Upload in diesem Dokumentationsauftrag.
Die [Versionshistorie](RELEASE_HISTORY.md) trennt lokale Quellen, Git und Play.

## Unterschiedliche Quell-/Release-Stände

| Ebene | Nachgewiesener Stand |
|---|---|
| GitHub-Standardbranch `master` | `9bbe7bc`, App-Code 16 / `1.0-beta` |
| GitHub-Branch `codex/ui-v3` vor Dokumentationsabgleich | `77b76cf`, Code 19 / `1.0-pre-beta` |
| Lokaler Produkt-HEAD `096aa35` | Code 21 / `1.0-pre-beta.1` in der committed Konfiguration |
| Lokaler, bereits vorher veränderter Arbeitsstand | Code **27** / **`1.0-closed-alpha.6`**, nicht als signiertes Release abgeschlossen |
| Letztes geprüftes signiertes AAB / aktuell in Play | Code **26** / **`1.0-closed-alpha.5`** |

Die Konfiguration beschreibt den **lokalen Arbeitsstand**, nicht automatisch einen
GitHub-Checkout. Dieser Auftrag übernimmt keinen Produktcode und erhöht keine Version.

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
Quellprovenienz von Release 26. Keine Dependency wurde aktualisiert oder neu heruntergeladen.

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

Im lokalen Arbeitsstand und im geprüften Release 26 ist R8 aktiv. Die echte
Zuordnung liegt im AAB unter
`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`.
`-dontshrink`, `-dontoptimize` und deaktiviertes Resource Shrinking erhalten den
konservativen ersten Rollout. Gezielte JNI-Regel für `OpenCV CvException` und
drei begrenzte `dontwarn`-Regeln für optionale alte gRPC/OkHttp-2-Typen bestehen.
Nicht als vollständige Größen-/Performanceoptimierung beschreiben.

Die Play-Detailansicht von Version 26 zeigte am 01.10.2026: **0 % Optimierung,
0 % Shrinking, 96 % Obfuscation** sowie eine vorhandene `mapping.txt` (Anzeige 104 MB).
Die lokale `app/src/release/keepRules/release.keep` ist noch nicht versioniert;
der committed Produktstand hat diese R8-Lösung nicht vollständig übernommen.

## Native Symbole: genau ein verifiziertes Original von sieben

Für alle sieben ARM64-Dateien wurden die Original-AAR-Bytes erneut mit dem
Release-26-AAB verglichen: **7/7 identisch**. Bereits die Anbieterdateien sind
gestrippt, ohne `.symtab` und DWARF. DomiDex hat diese Informationen nicht entfernt.

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

### Identität der unveränderten Release-26-Artefakte

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

Die Erweiterung, `archive_release.py`, ELF-/Lizenzprüfer, Registry, Init-Skript und
Tests sind teilweise noch uncommitted/untracked. Ein Docs-only-Push überträgt sie
nicht. Die vorhandene lokale Pipeline-Dokumentation vom 24.09.2026 ist ein
Entwicklungsnachweis, keine Zusage eines fertigen GitHub-Releaseworkflows.
Neue Release-Freigabe erfordert später getrennte Prüfung des vollständigen
Quellstands, unbenutzten Versionscodes, Tests und zugehörigen Artefakte.
