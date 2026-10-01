# Native-Debug-Symbol-Pipeline – DomiDex v27+

Stand: 24.09.2026. **Release 26 bleibt unverändert.** Kein Dependency-Fork,
kein NDK-Install, keine Änderung von Signing, R8, Packaging oder App-Verhalten.
Die Implementierung ist mit einem getrennten Dry-Run verifiziert, nicht mit einem
neuen Produktionsbuild. Versionscode 26 wurde nicht erhöht.

## Erneut ermittelter Buildstand

| Bestandteil | Tatsächlicher Stand |
|---|---|
| AGP / Gradle | 9.3.1 / 9.5.0 |
| Build-JDK | Android-Studio-JBR 25.0.2 |
| NDK | Keiner installiert; kein expliziter `ndkVersion`/`ndkPath` |
| Effektiver NDK-DSL-Wert | AGP-Default 28.2.13676358, **kein Beleg einer verwendeten Toolchain** |
| Native Eigenmodule | Keine; alle sieben Libraries stammen aus fünf Maven-Dependencies |
| Release-ABI | arm64-v8a; tatsächlich im AAB geprüft |
| Native Symbolstufe | Nicht explizit gesetzt; AGP 9.3.1 verwendet für Release SYMBOL_TABLE |
| R8 | Aktiv; bestehende Keep-Regeln bleiben unverändert, Resource-Shrinking aus |
| Aktuelle Version | 26 / 1.0-closed-alpha.5 |

`FULL` würde heute keine entfernten Informationen zurückbringen. Erst für künftig
bewusst eingeführten, mit Debug-Informationen gebauten eigenen nativen Code wäre
FULL sinnvoll: Funktionsnamen plus Datei-/Zeileninformationen. Dazu müssten
unstripped Binary, Symbole und Produktionsbinary aus **demselben Linklauf**
gesichert werden. Keine nachträgliche Rekonstruktion.
[Offizielle Android-Anleitung](https://developer.android.com/build/include-native-symbols).

## Nutzung für zukünftige Releases

Der bestehende unterstützte Release-Einstieg `scripts/release/Build-SignedBundle.ps1`
führt jetzt nach erfolgreichem Build automatisch Prüfung und Archivierung aus.
Er verweigert Versionscode ≤26 sowie schon existierende `releases/v<code>`-Ordner
**vor** Zugriff auf Signing-Zugangsdaten. Der nächste unbenutzte Versionscode muss
vom Verantwortlichen bewusst gewählt werden; der Helfer erhöht ihn nie selbst.

Voraussetzungen: Python 3.11+, JDK 17+ mit Source-Launcher (aktuell JBR 25), vorhandenes bundletool,
bestehende geschützte Signing-Einrichtung. Keine zusätzlichen Python-Pakete.

```powershell
./scripts/release/Build-SignedBundle.ps1 `
  -PythonExecutable 'C:/Users/Dominik/AppData/Local/Programs/Python/Python312/python.exe'
```

Optional: `-JavaDirectory`, `-BundletoolPath`, `-OfflineSymbols`. Standardmäßig
werden fehlende, SHA256-fixierte öffentliche Originalsymbole/Lizenzbelege von
offiziellen Quellen nachgeladen. Keine vollständigen SDK-Downloads, keine
NDK-Installation, keine Secrets im Archiv. Offline wird ein fehlender Beleg
ausgewiesen; unverifizierte Symbole gelangen niemals ins Upload-Paket.

**Android-Studio-/direkte `bundleRelease`-Aufrufe außerhalb dieses Helfers lösen
die Archivierung nicht aus.** Für veröffentlichungsfähige Releases den Helfer
verwenden. Der Metadatenexport ist absichtlich nur per Init-Skript zugeschaltet;
normale App-Builds und Android-Konfiguration bleiben unberührt.

Nach dem Build werden die Signing-Umgebungsvariablen zuerst entfernt. Erst danach
startet das Archivprogramm. Es kopiert ausschließlich die AAB, das eingebettete
Mapping, native Dateien, freigegebene Lizenztexte und ausgewählte Metadaten.
Kein Kopieren von Keystores, `.env`, `google-services.json`, vollständiger
Umgebung oder unkontrollierten Build-Verzeichnissen.

## Automatische Kontrollen / Archiv

```text
releases/v<actual-versionCode>/
  DomiDex-v<actual-versionCode>.aab
  mapping.txt
  native-debug-symbols.zip        # nur bei mindestens einem verifizierten Treffer
  native-libraries/base/lib/<ABI>/*.so
  native-inventory.json
  build-ids.txt
  dependency-versions.txt
  build-environment.txt
  symbol-verification.json
  release-verification.txt
  artifact-sha256.json
  licenses/manifest.json + Originaltexte
```

1. ZIP-Integrität, Pfade und doppelte Einträge; zusätzlich `bundletool validate`.
2. Kryptografische JAR-Signatur **jeder Nutzdatei**, gleiche Signierer und bekannter
   Upload-Zertifikat-Fingerprint; auch unsignierte META-INF-Nutzdateien abweisen.
3. Manifest: Package, versionCode, versionName, nicht debuggable.
4. Mapping direkt aus der AAB; angegebene Sidecar-Datei und Build-Metadaten müssen
   denselben SHA256 liefern. Kein Vermischen alter/newer Mapping-Dateien.
5. Aufgelöste, SHA256-geprüfte Original-AARs bestimmen die Native-Herkunft durch
   identische Bytes oder streng nachgewiesene Runtime-ELF-Identität. Unbekannte
   oder mehrdeutige Herkunft blockiert das Archiv.
6. Pro Library ABI, Dateiname, Maven-Koordinate/Version, AAR-/Binary-Hash, Build-ID.
7. Symbole: reale `.symtab`, gleiche Build-ID, ELF-/ABI-/Load-Segment-Identität,
   alle geladenen Bytes und dynamischen Symbole, Funktions-/Objektadressen und
   Größen. Eine falsche Build-ID oder gleicher Name alleine reicht nicht.
8. ZIP ausschließlich `<ABI>/<library>.so`; nach Schreiben gegen die AAB erneut
   geprüft. Stale oder ungeeignete Kandidaten ausgeschlossen und dokumentiert.
9. Buildstart ≤ frischer Metadatenexport ≤ Archivzeit; identischer Git-Commit.
   Dirty-Status wird dokumentiert; **uncommittete Quellen werden nicht archiviert**.
10. Ausschließlich neue Archivverzeichnisse, temporärer Aufbau vor finaler
    Freigabe; AAB-/Mapping-Hashes zum Abschluss nochmals geprüft.

Fehler in AAB, Signatur, Mapping oder Herkunft stoppen die Veröffentlichung des
Archivs. Fehlende Symbole/Lizenzbelege werden als Lücken dokumentiert. Ein bereits
in der AAB eingebettetes, nicht verifizierbares Symbolpaket blockiert das Archiv,
da ein bloßes Weglassen aus der Sidecar-ZIP den AAB-Inhalt nicht korrigieren würde.

## Abdeckung und Herstellerstrategie

| Library | Herkunft | Klasse / heutige Abdeckung |
|---|---|---|
| libc++_shared.so | OpenCV 4.13.0, Original aus NDK r27c | A: Original vorhanden; nur bei erneut vollständig passender Binary aufnehmen |
| libopencv_java4.so | org.opencv:opencv:4.13.0 | C: Offizielles SDK ebenfalls gestripped; keine passenden final gelinkten Symbole |
| libmlkit_google_ocr_pipeline.so | com.google.mlkit:text-recognition-bundled-common:17.0.0 | C: proprietäre Engine, keine passende öffentliche Symbollieferung |
| libandroidx.graphics.path.so | androidx.graphics:graphics-path:1.0.1 | C: Hersteller-AAR gestripped |
| libdatastore_shared_counter.so | androidx.datastore:datastore-core-android:1.1.7 | C: Hersteller-AAR gestripped |
| libimage_processing_util_jni.so | androidx.camera:camera-core:1.6.1 | C: Hersteller-AAR gestripped, libyuv-Eingaben nicht vollständig fixiert |
| libsurface_util_jni.so | androidx.camera:camera-core:1.6.1 | C: Hersteller-AAR gestripped |

A bedeutet offiziell erhältlicher Originalkandidat, **nicht** immer gültig für
jede zukünftige Version. B wäre ein gesondert begründeter, vollständig kontrollierter
Source-Build; aktuell ist kein solcher Ersatz gerechtfertigt oder umgesetzt.
Quellcodeverfügbarkeit alleine macht keinen heute ausgelieferten Build reproduzierbar.
CameraX, AndroidX, ML Kit und OpenCV werden nicht ersetzt.

Der normale Lauf prüft unstripped Original-AARs, AGP-Merged-Natives, vorhandene
AGP-Symbol-ZIP/AAB-Metadaten sowie die SHA256-fixierte offizielle NDK-libc++-Quelle.
Zusätzliche Herstelleroriginale können nach Herkunfts-/Lizenzprüfung per
`--symbol-dir` oder `--symbol-zip` geprüft werden. Vertraute Originaldateien
bereitstellen, niemals künstlich beschriftete/rekompilierte Ersatzsymbole.

**Bewusst konservativ:** Debug-only-ELFs ohne geladene Codebytes werden nicht nur
wegen passender Build-ID akzeptiert. Für künftige FULL-Pipelines zuerst den
unstripped Originalbuild sichern; reine `.dbg`-Zuordnung benötigt eine gesonderte
gleiches-Linkergebnis-Provenienzprüfung. Unbekannte ELF-Sonderformate werden
abgewiesen statt als vollständige Abdeckung ausgegeben.

## Lizenzen

`native-providers.json` enthält exakt versionierte Quellen, Prüfsummen, A/B/C-
Bewertung und offene Prüfpunkte. Archiviert werden Original-POMs, tatsächlich
enthaltene AAR-Lizenzen/NOTICES, ML-Kit-Drittanbieterlisten, AndroidX-Lizenztexte,
OpenCV-4.13.0-SDK-Lizenzpaket und NDK-r27c-Toolchain-Notice. Jeder Beleg erhält
Herkunft und SHA256. Andere Dependency-Versionen erben keine alten Lizenzbelege.

Das OpenCV-SDK-Paket enthält auch Hinweise für andere SDK-Module/ABIs; dessen
Archivierung ist **kein Beweis, dass jedes dort genannte Modul in arm64 steckt**.
Nicht pauschal alles als Apache-2.0 oder GPL einordnen.

Offen bleiben insbesondere exakter CameraX-libyuv-Quellstand/Copyrighttext,
vollständiger KleidiCV-0.7.0-Lizenznachweis und bei künftig angebotenen ML-Kit-
Symbolen deren Nutzungs-/Weitergaberechte. Das Manifest weist dies aus; kein
rechtlicher Freibrief oder vorgetäuschte vollständige Lizenzfreigabe.

## Verifikation ohne neue Version

Der echte Release-26-Build ist ein **nur gelesener Testdatensatz**. Testarchive
sind ausschließlich unter `artifacts/release-pipeline-tests/<neuer-name>` erlaubt.
Sie sind keine neuen Releasekandidaten. Metadaten markieren aktuelle Tools und
aktuellen Checkout ausdrücklich nicht als historische Build-26-Provenienz.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat --no-daemon --no-configuration-cache `
  -I scripts/release/release-metadata.init.gradle :app:exportReleaseArchiveMetadata
python -B -m unittest discover -s scripts/release/tests -p 'test_*.py'
python -B scripts/release/archive_release.py --dry-run `
  --aab releases/google-play-v26-closed-alpha-2026-09-23/DomiDex-1.0-closed-alpha.5-v26-SIGNED.aab `
  --mapping releases/google-play-v26-closed-alpha-2026-09-23/mapping.txt `
  --metadata app/build/outputs/release-archive/metadata.json `
  --java 'C:/Program Files/Android/Android Studio/jbr/bin/java.exe' `
  --bundletool artifacts/release-tools/bundletool-all-1.18.3.jar `
  --output artifacts/release-pipeline-tests/unique-dry-run-name --allow-download
```

## Manuell in Google Play / Restgrenzen

Vor echtem Release uncommittete Änderungen prüfen und einen nachvollziehbaren
Quellstand festhalten; Version bewusst wählen. Nach erfolgreichem Helferlauf
`release-verification.txt` einschließlich Lücken lesen. Exakt die archivierte AAB
hochladen. R8-Mapping ist bereits enthalten. Falls vorhanden, die zu genau dieser
Version erzeugte `native-debug-symbols.zip` bei den nativen Debug-Symbolen der
Version hinzufügen. Keine alte ZIP allein aufgrund einer ähnlichen Versionsnummer
wiederverwenden. Es wurde nichts in Play hochgeladen; die Live-Annahme der ZIP
kann erst dort geprüft werden. Bei Teilabdeckung darf die Warnung bleiben.

Keine vollständige Reproduzierbarkeit ungecommiteter Quellen oder proprietärer
Herstellerbuilds behaupten. Bei AGP-/Dependency-/Zertifikatwechsel den betroffenen
Registry-/Verifikationspunkt bewusst aktualisieren. Neue eigene native Module
müssen eine gleichwertige Herkunftsprüfung erhalten, bevor sie freigegeben werden.

## Dateien und durchgeführte Abnahme

Geändert:

- `scripts/release/Build-SignedBundle.ps1`: v27+-Schutz, Tests, Metadatenexport,
  automatische Archivierung nach Entfernung der Signing-Umgebungsvariablen.

Neu:

- `scripts/release/archive_release.py`: Archivierung und Verifikationsablauf.
- `scripts/release/native_elf.py`: strenge ELF-/Symbol-Zuordnung.
- `scripts/release/official_symbols.py`: fixierte Original-NDK-Quelle und Cache.
- `scripts/release/native_licenses.py`: sichere versionsgenaue Lizenzarchivierung.
- `scripts/release/native-providers.json`: Quellen, Prüfsummen und offene Punkte.
- `scripts/release/VerifyBundle.java`: Signaturprüfung aller AAB-Nutzdateien.
- `scripts/release/release-metadata.init.gradle`: opt-in Build-Metadatenexport.
- `scripts/release/tests/test_archive_release.py`
- `scripts/release/tests/test_native_elf.py`
- `scripts/release/tests/test_native_licenses.py`
- `scripts/release/.gitignore`: erzeugte Python-Caches ausschließen.
- `docs/native-symbol-release-pipeline.md`: diese Anleitung/Abnahme.

Abnahme am 24.09.2026:

- **39 Offline-Tests erfolgreich**, einschließlich falscher Build-ID, verändertem
  Code, fehlender Symboltabelle, stale Mapping/Metadaten, Pfad-/ZIP-/Lizenzschutz.
- Echter AAB-Signaturtest: **688 signierte Nutzdateien** bestanden. Vier negative
  Tests: manipulierte Nutzdatei, unsignierte Ergänzung, unsignierte META-INF-
  Nutzdatei, falscher Zertifikat-Fingerprint – sämtlich zurückgewiesen.
- Gradle-Metadatenexport erfolgreich (zuletzt 13 s); kein neuer Release-Build.
- Vollständiger Dry-Run mit Download des fixierten libc++-Originals und zweiter
  vollständiger **Offline-Dry-Run** erfolgreich.
- Finales Testarchiv: `artifacts/release-pipeline-tests/v26-fixture-final-20260924/`.
  Sieben Native-Binaries, genau ein verifiziertes Symbol (`libc++_shared.so`),
  44 Original-Lizenz-/POM-Dokumente; **61 Artefaktprüfsummen erneut bestätigt**.
- PowerShell-Syntaxprüfung und v26-Abbruch vor Build/Signing-Zugriff bestanden.
- Alle neun zuvor gesicherten Schutz-Hashes unverändert: Release-26-AAB,
  vorhandenes Symbolpaket, Gradle-/Dependency-/R8-Konfiguration.
- Keine App-Datei verändert, keine Version erhöht, kein Commit/Push/Play-Upload.
