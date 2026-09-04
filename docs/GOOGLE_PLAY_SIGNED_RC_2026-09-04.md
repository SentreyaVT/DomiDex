# DomiDex – signierter Google-Play-Pre-Beta-Release-Candidate

Stand: 04.09.2026. Dieser Bericht ersetzt die **Build-/Signing- und Live-Website-Befunde** der älteren, unsignierten Übergabe vom selben Datum. Die alte UNSIGNED-Datei bleibt als historisches Artefakt erhalten und darf nicht hochgeladen werden.

## A. Release-Status

**READY FOR GOOGLE PLAY INTERNAL TEST** – technisch signiertes, lokal validiertes Upload-Bundle. Keine Play-Console-Aktion ausgeführt und keine Annahme durch Google behauptet. Paketbindung und noch unbenutzter `versionCode=17` müssen beim ersten manuellen Upload bestätigt werden. Ein vollständiges öffentliches Store-Listing ist nicht Teil dieser technischen Freigabe.

## B. App

| Eigenschaft | Geprüfter Wert |
| --- | --- |
| App-Name | DomiDex |
| Package / applicationId | `com.dominik.cardvault` |
| versionName / versionCode | `1.0-pre-beta` / `17` |
| minSdk / targetSdk / compileSdk | `26` / `37` / `37` |
| Orientierung | Portrait, unverändert |
| Release-ABI | ausschließlich `arm64-v8a`, unverändert |
| debuggable / testOnly | false; Attribute im Release-Manifest nicht gesetzt |
| Minifizierung | deaktiviert, unverändert |

Das lokal installierte Android-37.0-SDK meldet `REL` und Preview-SDK 0. Keine SDK- oder Versionsanpassung vorgenommen.

## C. Signierung und sichere Aufbewahrung

Mit ausdrücklicher Betreiberfreigabe neu erzeugter **Upload-Schlüssel**, kein Debug-Schlüssel:

- Keystore: `C:/Users/Dominik/.domidex-signing/domidex-upload.p12`.
- Alias: `domidex-upload`; PKCS12; RSA 3072; Zertifikat SHA384withRSA.
- Zertifikat: `CN=DomiDex Upload,C=AT`, gültig 04.09.2026 bis 20.01.2054.
- SHA-1: `24:D8:48:20:16:4D:1F:FB:C8:30:19:1B:6D:65:3C:10:6E:0F:EB:7E`.
- SHA-256: `83:7D:12:C0:24:DF:ED:39:04:E1:3C:EB:24:1B:9B:99:58:4A:D5:4A:E8:AA:9D:4F:58:08:4B:BC:76:B7:0E:A2`.
- Öffentliches Zertifikat: `C:/Users/Dominik/.domidex-signing/domidex-upload-cert.pem`.
- Zufälliges Passwort nur Windows-DPAPI-geschützt in `upload-credential.clixml`, außerhalb des Repositorys. Verzeichnis ohne geerbte Zugriffe, ausschließlich aktueller Windows-Nutzer und SYSTEM. Keine Schlüssel oder Passwörter im Commit, Chat oder Bundle.
- Build erhält Signierwerte kurzzeitig über Umgebungsvariablen; kein langlebiger Gradle-Daemon und kein Konfigurationscache für signierte Builds.

**Manuelles Backup erforderlich:** Keystore **und wiederherstellbares Passwort** getrennt geschützt sichern. Die DPAPI-Datei allein ist kein portables Backup: Sie ist an Windows-Benutzer und Rechner gebunden. Keine externe Sicherung wurde vorgenommen. `Initialize-UploadKey.ps1` nicht erneut ausführen; vorhandene Schlüssel niemals ersetzen.

Der Upload-Key ist mit Play App Signing kompatibel, aber nicht automatisch das spätere Zertifikat der von Play installierten App. [Android: Signierung](https://developer.android.com/studio/publish/app-signing)

## D. Finales Bundle

Finaler Zielpfad: `C:/Users/Dominik/AndroidStudioProjects/CardVault/releases/google-play-internal-2026-09-04/DomiDex-1.0-pre-beta-v17-SIGNED.aab`.

Exakte endgültige Größe, SHA-256, Quell-Commit und Zertifikats-/Bundleprüfung stehen neben der Datei in `FINAL-ARTIFACT.md` und im Unterverzeichnis `validation`. Diese generierten Artefakte sind bewusst Git-ignoriert. Das nach dem Quell-Commit gebaute AAB wird nochmals validiert; die erfolgreiche Vorabprüfung ist ebenfalls nachvollziehbar erhalten.

Geprüft: Bundletool 1.18.3 `validate`, Manifest, Konfiguration; JAR-Signatur gegen das neue Upload-Zertifikat. Keine Test-Instrumentation/Test-Activity oder eigenen Testfixtures im Produktbundle. Alle sieben ARM64-ELF-Bibliotheken haben 16.384-Byte-LOAD-Alignment, passende Offsets und RELRO; Bundle-Konfiguration `PAGE_ALIGNMENT_16K`. Das ist eine statische Prüfung, kein Laufzeittest auf einem 16-KB-Gerät.

Signaturwarnungen transparent getrennt von Fehlern: selbstsignierte/unvertraute Zertifikatskette, fehlender Zeitstempel und POSIX-Attribute. Gradle legt `META-INF/MANIFEST.MF` am ZIP-Ende ab; deshalb erkennt `JarInputStream` die Signaturen nicht wie `JarFile`. `jarsigner` über `JarFile` bestätigt **jar verified**, Bundletool validiert erfolgreich. Keine ZIP-Umordnung und keine Signaturwarnung durch Produktänderungen versteckt.

Dateien mit Test-/Debug-Namen wurden auf ihre Herkunft geprüft: `DebugProbesKt.bin` stammt bytegleich aus Kotlin Coroutines, `datastore_test_trace.proto` bytegleich aus Firebase Firestore und `test_level_drawable.xml` aus AppCompat. Das sind unveränderte Runtime-Abhängigkeitsressourcen, nicht DomiDex-Testfixtures; nichts daraus entfernt.

## E. Tests / Build

- `clean` ausgeführt, danach echter signierter Release-Build mit `bundleRelease`, Release-Lint und Debug-/Test-APK.
- Unit: **328 ausgeführt, 327 bestanden, 1 übersprungen, 0 Fehler**. Ausschließlich der vorhandene opt-in-Netzwerktest `CardmarketLiveSmokeTest.officialDownloadsRemainParseableWhenExplicitlyEnabled` wurde übersprungen; keine Behauptung eines aktuellen flächendeckenden Live-Preisabrufs.
- Ein veralteter Unit-Test erwartete Reverse-Preise für eine Normal+Holo-Fixture. Nur Erwartung/Kommentar an den bereits physisch freigegebenen Preisfix angeglichen; keinerlei Preis-Produktcode in diesem Release-Run verändert. Anschließend alle aktivierten Unit-Tests bestanden.
- **30/30 gezielte Instrumented-/UI-Tests bestanden**, Medium_Phone API 36, `emulator-5554`. Isolierte Testdaten/in-memory Room; kein Schreiben in persönliche Sammlungen oder Firebase-Produktion.
- Release-Lint: **0 Fehler, 148 Warnungen, 2 Hinweise**. Nicht blockierende vorhandene Warnungen nicht zum Anlass für Umbauten genommen.
- Signierte Release-Erstellung, Bundle- und Signaturvalidierung erfolgreich.
- Zielgerichteter Secret-Scan: 221 Repository-Textdateien und 687 Bundle-Einträge, einschließlich exaktem Vergleich mit dem tatsächlich erzeugten Upload-Passwort; **keine Treffer**. Geprüft wurden hochzuverlässige Token-/Privatschlüsselmuster und lokale Geheimnisdateinamen. Kein Sicherheitszertifikat und keine vollständige Sicherheitsanalyse.
- Keine app-eigenen localhost-/Emulator-Endpunkte im geprüften `app/src/main` gefunden. Öffentliche Firebase-Clientkonfiguration ist kein Service-Account-Schlüssel.

Reproduzierbarer signierter Build unter diesem Windows-Nutzer:

```powershell
./scripts/release/Build-SignedBundle.ps1
```

Unit-Berichte: `app/build/test-results/testDebugUnitTest/`; Instrumentation: `app/build/outputs/androidTest-results/connected/debug/`; Lint: `app/build/reports/lint-results-release.html`.

## F. Gezielte Regressionen

| Bereich | Erfolgreicher Nachweis |
| --- | --- |
| Missing → Add → Owned | `BinderSlotOwnershipInstrumentedTest` und `BinderPricePreviewInstrumentedTest`; derselbe offizielle Slot, keine zusätzlichen Duplikat-Slots |
| Menge / Entfernen | Mengensteigerung, getrennte Owned-Zeilen und Rückkehr zum Placeholder bei unveränderter Slotidentität |
| Normal / Holo / Reverse | getrennte Varianten; kein Ausleihen des Normal-/Holo-Preises bei fehlendem Reverse-Preis |
| Binder-Aktualisieren | vier Refresh-Durchläufe während Seitenanimation und schrumpfender Referenzliste, danach weiterblättern, kein Crash oder Variantenverlust |
| Oberhalb Main-Set | `AboveMainSetPriceTest`, `AboveMainSetPriceFlowInstrumentedTest`, `SourceCardmarketLookupInstrumentedTest`; exakte Produkt-ID durch Add, Sammlung, Gesamtsumme und Historie |
| Konkrete Kartendaten | `me03` u. a. 089/088, 090/088, 100/088, 117/088, 120/088, 124/088; Main-Set-Gegenproben; `sv08.5-137` Freunde aus Paldea → Cardmarket 805531, nicht gleichnamige Main-Set-Karte |
| Vorschau / Zielbinder | schattierte Full-Art-Karte, Preisnachladen, offizieller Platz 137 vor 138, erneutes Öffnen; Set/Printing bleiben erhalten |
| Scanner / Portrait | realer Activity-Einstieg, 0/90/180/270-Grad-Displayrotation ohne neue Activity; Kamera-JPEG-Portrait im isolierten Kameratest |
| ScanLab | Button → Vorschau → ausdrückliches Hochladen; Abbrechen sendet kein Foto; manuelle Set-/Nummerauswahl bleibt verfügbar |
| Auto-Capture | opt-in pro Sitzung, keine Wiederherstellung in neue Sitzung; unverändert |

Login wurde als erreichbarer/aktivierter Google-Anmeldeeinstieg geprüft, **nicht** als neue echte Google-Anmeldung. Die Emulator-Regressionssuite verwendet den Debug-Build desselben Produktquellstands; das Release-AAB wurde separat statisch/signaturtechnisch geprüft. Die reale Xiaomi-Funktionsprüfung stammt aus der ausdrücklichen Betreiberfreigabe vor diesem Run. Xiaomi nicht ersetzt/deinstalliert und keine Nutzerdaten gelöscht.

## G. Firebase / Google Sign-In

Lokale `app/google-services.json` ist vorhanden, ignoriert und passt zu `com.dominik.cardvault`; Android-App-ID `1:361496003351:android:55c2da4ff527b4cadf6d52`. Web-OAuth-Client vorhanden. Lokaler Android-OAuth-Eintrag passt zum bisherigen Debug-SHA-1:

- Debug SHA-1: `F7:C1:F5:BB:CA:2C:F4:44:DC:A4:3D:13:66:E6:DD:83:8F:69:20:35`.
- Debug SHA-256: `61:E8:31:4B:14:7E:44:65:3B:8E:0D:16:9F:E6:CA:F2:27:60:C3:6E:E4:77:95:CD:84:71:B4:34:16:6B:A8:54`.
- Upload-Fingerprints: Abschnitt C.

Nach manueller Play-App-Signing-Einrichtung: **tatsächliches App-Signing-Zertifikat von Play**, SHA-1 und SHA-256, in Firebase → Projekteinstellungen → Android-App `com.dominik.cardvault` → Zertifikat-Fingerprints prüfen/ergänzen. Der Upload-Key ersetzt dieses Zertifikat nicht. Für eine direkt mit Upload-Key signierte lokale APK wären zusätzlich deren Fingerprints relevant. Geänderte Clientkonfiguration bei Bedarf erneut herunterladen. Keine Firebase-Änderung oder Live-Freigabe der neuen Signatur in diesem Run. [Firebase: Google-Anmeldung](https://firebase.google.com/docs/auth/android/google-signin)

Danach kurzer Play-Installations-/Anmelde-Smoke-Test durch Betreiber. Eine bestehende Debug-App nicht wegen eines Zertifikatskonflikts blind deinstallieren: zuerst persönliche Daten/Export/Sync sichern und verifizieren.

## H. Website / Legal – nur gelesen

Live am 04.09.2026 mit HTTP 200 und erwarteten Seitentiteln erreichbar:

- https://domidex.web.app/
- https://domidex.web.app/datenschutz
- https://domidex.web.app/impressum
- https://domidex.web.app/kontakt
- https://domidex.web.app/konto-loeschen
- https://domidex.web.app/pre-beta

Die Kontolöschseite bietet einen E-Mail-Anfrageweg. Kein Löschantrag gesendet; keine vollständige Live-Löschung als getestet behauptet. Alte Hinweise in der UNSIGNED-Übergabe auf fehlende Kontolöschseiten sind überholt. Manuell bleiben die rechtliche Vollständigkeit, tatsächlichen Datenverarbeitungen/Aufbewahrungsfristen, Bearbeitung von Löschanfragen und Nutzungsrechte an Karten-/Markenmotiven zu prüfen. Keine Websiteänderung und keine rechtliche Zertifizierung.

## I. Play-Assets / vorhandene Store-Texte

- **512×512 Store-Icon: nein.** Vorhanden ist das echte 1280×1280-Launcher-JPEG; kein neuer Export erzeugt.
- **1024×500 Feature Graphic: nein.** Kein erfundenes Artwork erstellt.
- **Aktuelle, für das Listing freigegebene Screenshots: nein.** Ältere echte Aufnahmen vorhanden, teils überlange Formate, veralteter Scanner oder Ladezustände; nicht als aktueller Release-Nachweis ausgegeben.
- Offene Assets: passender Icon-Export, Featuregrafik, aktuelle echte formatgerechte Screens ohne private Daten. Kein Fake-Zustand erzeugt.
- Bestehende deutsche Store-Texte in `docs/GOOGLE_PLAY_STORE_TEXT_DE_2026-09-04.md` wiederverwenden; vorhandene Asset-Inventur und technischer Data-Safety-Bericht bleiben Referenzen, historische Live-Befunde sind mit diesem Bericht abzugleichen.

**Google Play: Paid/Free Auswahl erfolgt manuell durch Betreiber.** Keine Preisentscheidung oder dauerhafte Gratiszusage vorgenommen.

## J. Google Play – nächster Schritt

App kann jetzt manuell in der Google Play Console für Internal Testing eingerichtet werden.

## K. Git / Herkunft

Branch: `codex/ui-v3`. Ausgangs-Commit: `19f2b8b`. Zu Beginn waren die zuletzt physisch bestätigten Preis-/Produkt-ID-/Binder-Zielslot-Fixes samt Tests noch uncommitted; sie wurden unverändert erhalten und werden zusammen mit der Release-Vorbereitung gesichert. Neue Änderungen dieses Runs beschränken sich auf Signierkonfiguration, zwei Signierhilfsskripte, `.gitignore`, die genannte Unit-Test-Erwartung und Übergabedokumentation.

Nach erfolgreicher erster Signierung/Validierung lokaler Commit; anschließend Bundle aus dem eingefrorenen Commit und erneute Artefaktprüfung. Abschließender Commit, SHA und Working-Tree-Nachweis in der generierten `FINAL-ARTIFACT.md`. Kein Push, kein Tag, kein Release-Upload, kein Discord-Post, keine Google-Play-/Firebase-/Website-Veröffentlichung.

## L. Freeze / spätere Punkte

**Keine weiteren funktionalen Änderungen vorgenommen.**

DOMIDEX PRE-BETA GOOGLE PLAY RELEASE CANDIDATE. Nicht blockierende Lint-/Signaturhinweise, öffentliche Store-Assets und echter 16-KB-Geräte-Smoke-Test bleiben dokumentierte spätere Punkte; keine weiteren App-Arbeiten beginnen.
