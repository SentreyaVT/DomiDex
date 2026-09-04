# DomiDex – Google Play Internal Test: Übergabe

Stand: 04.09.2026. Nur Vorbereitung, keine Veröffentlichung. Keine App-Funktion wurde in diesem Vorbereitungslauf geändert. Der zuvor erarbeitete Stand einschließlich Portrait und manuell bestätigtem ScanLab-Upload wurde unverändert gebaut.

## A. PRE-BETA BUILD

**NOT READY für den AAB-Upload.** Release-Build, Paketprüfung und die unten angegebenen Tests sind erfolgreich. Das erzeugte Bundle ist jedoch **unsigniert**, weil die lokale Release-Signierung nicht eingerichtet ist. Kein neuer Schlüssel erzeugt, kein Debug-Schlüssel als Release-Schlüssel verwendet.

Offene Übergabepunkte:

1. Upload-Schlüssel mit Betreiber klären: vorhandenen Schlüssel verwenden oder ausdrücklich dokumentiert neu anlegen; sichere getrennte Sicherung und lokale Signierkonfiguration. Danach erneut signiert bauen und prüfen. Das vorliegende `UNSIGNED.aab` nicht hochladen.
2. Play App Signing in der Console einrichten; Google-Anmeldung anschließend mit dem tatsächlichen Play-App-Signaturzertifikat verifizieren.
3. Store-Assets und rechtliche Betreiberangaben vervollständigen, siehe D/E. Diese Vorbereitung ist noch kein vollständiges öffentliches Store-Release.
4. Preisstrategie ausdrücklich entscheiden, siehe J. Es wurde weder eine kostenlose noch eine kostenpflichtige App angelegt.

Interne Tests dürfen laut Google vor vollständigem App-Setup beginnen; ein fehlendes vollständiges Listing ist deshalb nicht automatisch eine technische Internal-Track-Sperre. Ausschließlich interne Apps sind vom Data-Safety-Formular ausgenommen. Diese Erleichterungen sind keine Datenschutzfreigabe. [Play: interne Tests](https://support.google.com/googleplay/android-developer/answer/9845334?hl=en), [Data Safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)

## B. APP

| Feld | Tatsächlicher Wert |
| --- | --- |
| Anzeigename | DomiDex |
| applicationId / Package / Namespace | `com.dominik.cardvault` |
| versionName | `1.0-pre-beta` |
| versionCode | `17` |
| minSdk | `26` |
| targetSdk | `37` |
| compileSdk | `37` |
| Release-ABI | ausschließlich `arm64-v8a` |
| Sprache für Play | Deutsch, `de-DE` |
| Typ / Teststatus | App / Internal Testing |
| Kategorie – Empfehlung, keine Einstellung vorgenommen | Produktivität, da Sammlungsorganisation im Mittelpunkt steht |
| Orientation | fest **Portrait**, ausdrücklich vom Betreiber bestätigt |
| debuggable | `false` im Release; `BuildConfig.DEBUG=false` |
| R8 / Minifizierung | deaktiviert (`optimization.enable=false`); unverändert |

Konfiguration: [app/build.gradle.kts](C:/Users/Dominik/AndroidStudioProjects/CardVault/app/build.gradle.kts:31). Portrait steht in [AndroidManifest.xml](C:/Users/Dominik/AndroidStudioProjects/CardVault/app/src/main/AndroidManifest.xml:22). Kein Landscape-Lock und keine neue Rotation-/EXIF-Logik eingeführt. Der Phone-AVD hält bei 0/90/180/270 Grad dieselbe Activity in Portrait. Android kann auf großen Displays feste Ausrichtungen übergehen; die bestehende Lint-Warnung dazu ist kein getesteter Tablet-Nachweis und wurde nicht durch einen Umbau umgangen.

Der manuelle ScanLab-Dialog benötigt weiterhin die einzelne Freigabe. Alte Dauereinwilligung startet keinen automatischen Foto-Upload; auch keine Zwei-Fehlscan-Pflicht aktiviert. Separat vorhandene Scan-Evidence ohne Foto bleibt unverändert und wird in F transparent aufgeführt.

## C. AAB

- Dateiname: **DomiDex-1.0-pre-beta-v17-UNSIGNED.aab**
- Vollständiger Pfad: [Bundle](C:/Users/Dominik/AndroidStudioProjects/CardVault/releases/google-play-internal-2026-09-04/DomiDex-1.0-pre-beta-v17-UNSIGNED.aab)
- Größe: **35.743.776 Bytes**, rund 35,74 MB / 34,09 MiB. Das ist Bundle-Dateigröße, nicht Play-Downloadgröße.
- SHA-256: `8F9EDE624AE260C8544AF63A94125ECF2AD547240EA12CFA9558B6C81FE923CF`
- Signing: **UNSIGNED**, `jarsigner -verify` meldet nicht signiert; keine Signaturdateien im Bundle.
- Play App Signing: reguläres AAB-Format grundsätzlich geeignet; noch **nicht eingerichtet oder durch Play angenommen**. Der fehlende Upload-Schlüssel ist ein harter Upload-Blocker.
- Unverändertes Gradle-Original: [app-release.aab](C:/Users/Dominik/AndroidStudioProjects/CardVault/app/build/outputs/bundle/release/app-release.aab); Kopie hat denselben Hash.

`keystore.properties` fehlt. [keystore.properties.example](C:/Users/Dominik/AndroidStudioProjects/CardVault/keystore.properties.example) enthält ausschließlich Platzhalter. Schlüssel, Passwörter und echte lokale Konfiguration gehören weder in Git noch in Chat/Store-Listing. Die vorhandene Gradle-Konfiguration fällt ohne Release-Schlüssel bewusst nicht auf eine Debug-Signatur zurück.

Nach Freigabe: Schlüsselbestand prüfen, Upload-Key einrichten und sichern, signiert neu bauen, Signatur/Zertifikat und neue SHA-256 dokumentieren. Play App Signing signiert die installierte App mit dem App-Signing-Key; dieser ist vom Upload-Key zu unterscheiden. Das Play-Zertifikat muss für Google-/Firebase-Anmeldung passen. Google dokumentiert dafür insbesondere den SHA-1-Fingerprint; SHA-256 des Zertifikats ebenfalls dokumentieren und für verwendete Firebase-/Integritätsdienste prüfen. Keine Firebase-Einstellung wurde jetzt geändert. [Android: App-Signierung](https://developer.android.com/studio/publish/app-signing), [Firebase: Google-Anmeldung](https://firebase.google.com/docs/auth/android/google-signin)

Release-Paketprüfung:

- `bundletool validate` erfolgreich, finale Manifestwerte entsprechen B.
- Keine Test-Instrumentation oder Test-Activity im AAB.
- 7 native ARM64-Bibliotheken: ELF-LOAD-Alignment jeweils mindestens 16.384 Bytes, RELRO vorhanden; Bundle `PAGE_ALIGNMENT_16K`; Release-APK besteht `zipalign -c -P 16 4`.
- **Kein** Laufzeittest auf einem echten 16-KB-Gerät; AVD verwendet 4.096 Bytes. Statische Eignung ist keine pauschale Gerätefreigabe. [Android: Seitengrößen](https://developer.android.com/guide/practices/page-sizes)
- Zielgerichtete Secret-/Paketprüfung über Repository-Textdateien und Bundle: keine privaten Schlüssel, Service-Account-Dateien, erkannten Tokens oder lokalen Geheimnisdateien gefunden. Kein pauschales Sicherheitszertifikat.
- Keine app-eigenen Emulator-/localhost-Endpunkte im geprüften Produktivquellpfad. Keine enthaltene Demo-Sammlungsdatenbank gefunden; Test-Fixtures liegen im Testbereich.
- Lokale `google-services.json` vorhanden und Git-ignoriert; Package stimmt überein, Web-OAuth-Client konfiguriert. Öffentliche Firebase-Clientkonfiguration im Ressourcenpaket ist erwartbar und kein privater Admin-Schlüssel. Live-Login mit späterer Play-Signatur noch offen.

## D. GOOGLE PLAY ASSETS

Vorhanden: aktives DomiDex-Launcher-Motiv als 1280×1280-JPEG, adaptive und runde Icon-Konfiguration, echte ältere Geräte-/Emulator-Screenshots. Keine Fake-Bilder erzeugt.

Noch kein fertiges Listingpaket:

- Store-Icon: 512×512, 32-Bit-PNG, höchstens 1024 KB exportieren; vorhandenes Original ist verwendbare Grundlage, aber noch kein passendes Exportformat.
- Mindestens zwei aktuelle echte Screenshots auswählen/aufnehmen: JPEG oder RGB-PNG ohne Alpha; Seiten 320–3840 Pixel, längste Seite höchstens doppelt so lang wie kürzeste. Vorhandene 1080×2400-/1280×2772-Aufnahmen überschreiten diese Relation; einige zeigen ältere Scanner-UI oder Ladezustände.
- Featuregrafik: 1024×500 JPEG oder RGB-PNG ohne Alpha fehlt. Keine generierte Web-Designreferenz als Android-Screenshot verwenden.
- Rechte an Marken-/Kartenmotiven und personenbezogenen Bildinhalten vor Verwendung bestätigen.

Exakte Dateien, Maße und Sichtprüfung: [Asset-/Website-Bericht](C:/Users/Dominik/AndroidStudioProjects/CardVault/docs/PLAY_ASSETS_AND_WEBSITE_2026-09-04.md). Formate nach [Google: Vorschau-Assets](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en). Ein vollständiges Listing ist von der anfänglichen Internal-Test-Einrichtung zu unterscheiden.

Fertige deutsche Textvorschläge für Kurzbeschreibung, vollständige Beschreibung, Pre-Beta-/Tester-Hinweise und Versionshinweise: [Store-Texte](C:/Users/Dominik/AndroidStudioProjects/CardVault/docs/GOOGLE_PLAY_STORE_TEXT_DE_2026-09-04.md). Nur aktuell vorhandene Funktionen, kein KI-/Grading- oder Vollständigkeitsversprechen. Keine dauerhafte Kostenfreiheit zugesagt.

## E. WEBSITE / LEGAL

| Punkt | Live-Befund / offener Schritt |
| --- | --- |
| Website | [domidex.web.app](https://domidex.web.app/) antwortet HTTP 200. |
| Datenschutz | [Öffentliche Datenschutzseite](https://domidex.web.app/datenschutz), HTTP 200, **inhaltlich unvollständiger Pre-Beta-Text**. Android-/SDK-/ScanLab-Daten und Löschfristen ergänzen. |
| Kontakt | [Kontaktseite](https://domidex.web.app/kontakt), HTTP 200, bisher Discord-Verweis. Gewünschte Supportadresse `Dominik.muxifeld@gmail.com` steht im App-Code, nicht auf dieser Seite. |
| Impressum | [Impressum](https://domidex.web.app/impressum), HTTP 200, unvollständiger Betreiber-Platzhalter. |
| Kontolöschung | Kein entsprechender öffentlicher Link in geprüfter Navigation/FAQ/Updates/Roadmap gefunden. Keine URL erfunden. App bietet nur E-Mail-Anfrage; tatsächliche Bearbeitung/Hard-Delete nicht nachgewiesen. |
| Pre-Beta/Nutzung | Öffentliche Pre-Beta-Hinweise vorhanden; keine separate verlinkte Beta-Nutzungsseite gefunden. Inhalt vom Betreiber festzulegen, nicht pauschal eine vorgeschriebene Play-Seite. |

**Offene GOOGLE-PLAY-/VERÖFFENTLICHUNGS-BLOCKER der vollständigen Vorbereitung:** vollständige Datenschutzinformation und bestätigter Kontolöschweg fehlen; Betreiber-/Kontaktangaben sind unvollständig. Was die Console für ausschließlich Internal Testing unmittelbar erzwingt, wurde nicht geprüft. Keine juristische Freigabe. Insbesondere bedeutet HTTP 200 nicht, dass der Inhalt vollständig oder geeignet ist. [Play: Nutzerdaten](https://support.google.com/googleplay/android-developer/answer/10144311), [Kontolöschung](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en-EN)

Keine Website-, Firebase-Regel-, Auth-, Hosting- oder Produktionsänderung ausgeführt.

## F. DATA SAFETY

Vollständige technische Matrix mit Quellen, Zweck, Optionalität, Empfängern, TLS und Löschgrenzen: [Data-Safety-Bericht](C:/Users/Dominik/AndroidStudioProjects/CardVault/docs/PLAY_DATA_SAFETY_TECHNICAL_2026-09-04.md). Nicht ungeprüft als verbindliche Formularantwort abschicken.

| Datengruppe | Erhebung / Zweck / Freiwilligkeit | Löschung und Weitergabe |
| --- | --- | --- |
| E-Mail, Anzeigename, UID | Bei optionaler Google-Anmeldung: Konto, Zugriffsschutz, Firebase Auth. | Abmelden ist keine Kontolöschung; bislang E-Mail-Antrag. Google/Firebase als Empfänger; Dienstleister-Ausnahme zu bestätigen. |
| Sammlung, Binder, Varianten, Notizen; optionale Kaufpreise/-daten | Bei freigeschaltetem Konto Sync mit Firestore; Portfolio wird daraus abgeleitet. Gast lokal, Gastübernahme separat bestätigt. | Cloud-Einträge werden beim Löschen als Tombstones behalten; keine vollständige Cloud-Bereinigung nachgewiesen. |
| ScanLab-Fotos, OCR, Konfidenz, Fehlergrund | Nur Admin/Pretester, manuell gewähltes Foto plus Einzelbestätigung; HTTPS-API und Firebase Storage/Firestore zur Fehleranalyse. | Kein Nutzer-Hard-Delete/keine bestätigte Backend-Frist. Scannerbericht entfernt EXIF, sichtbare private Bildinhalte bleiben möglich. |
| Scan-Evidence ohne Rohfoto | Separater bestehender automatischer Pfad nach bestätigtem Scan-Hinzufügen bei Kontoberechtigung; Kandidaten, Fingerprints, OCR, Konfidenz und Karten-ID. | Nicht anonym nennen; UID-bezogen, keine Client-Löschung. Nicht mit manuellem ScanLab-Fotoversand verwechseln. |
| Katalogbildbeiträge | Separat freiwillig, bestätigte Kameraaufnahme an Storage. | Aktueller Beitrag zurückziehbar; Originalbytes können EXIF behalten. Kein allgemeiner Kontolöschmechanismus. |
| Installationskennungen, SDK-/Leistungsmetadaten | Firebase Remote Config/Installations, ML Kit, Play-Updates; teils ab Start, kein globaler Telemetrie-Opt-out gefunden. | SDK-/Betreiberfristen zu bestätigen. Kein Firebase Analytics-, Crashlytics- oder Ads-SDK gefunden; trotzdem nicht „keine Diagnostik“ behaupten. |
| IP/Requestmetadaten, möglicher Ländercode | Auth, Remote Config und externe Katalog-/Bild-/Preisabrufe. Keine GPS-API/Standortberechtigung gefunden. | Anbieter-/Ländercode-Verarbeitung klären. Keine pauschale Nichtweitergabe-Garantie für externe Hosts. |
| Lokale Bilder/Export | Kamera/OCR lokal; Photo Picker oder Dokumentdialog; expliziter Export kann Bilder/OCR enthalten. | Lokale Löschung löscht keine Cloud- oder unabhängig exportierten Kopien. |

Eigene Konto-/Sync-/Reportpfade nutzen Firebase bzw. HTTPS. TLS ist aus Code/SDK-Dokumentation belegt, nicht mit vollständigem Netzwerk-Mitschnitt validiert. Angaben „mit Dritten geteilt: nein“ sind für Dienstleister-Ausnahmen erst nach Bestätigung der tatsächlichen Verträge/Verwendung zulässig. Unbekannte Empfänger-/Aufbewahrungsdetails bleiben unbekannt; keine Ja/Nein-Antwort erfunden. Ausschließlich interner Test: derzeit keine Pflicht zum Data-Safety-Formular; spätere Tracks neu bewerten. [Play-Definitionen](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)

## G. PERMISSIONS

Exakte acht `uses-permission` des finalen Release-Bundles:

| Berechtigung | Zweck / Zeitpunkt / optional / Console |
| --- | --- |
| `android.permission.CAMERA` | Kartenaufnahme, Laufzeitdialog beim Kamerazugriff. Optional; manuelle Suche/Galerie bleiben möglich. Kamera-/Fotoverarbeitung korrekt erklären. |
| `android.permission.INTERNET` | Firebase, Katalog, Bilder, Preise. Normale Installationsberechtigung, kein eigener Dialog; erforderlich für Onlinefunktionen. |
| `android.permission.ACCESS_NETWORK_STATE` | Netzwerkstatus/Hintergrundarbeit. Normal, kein Dialog; nicht separat abschaltbar. |
| `android.permission.WAKE_LOCK` | WorkManager für geplante Arbeit. Normal, kein Dialog; transitiv. |
| `android.permission.RECEIVE_BOOT_COMPLETED` | WorkManager stellt Arbeit nach Neustart wieder her. Normal, kein Dialog; transitiv. |
| `android.permission.FOREGROUND_SERVICE` | WorkManager-Manifestdeklaration. Kein Laufzeitdialog; kein Nachweis, dass ein Dauer-Service gestartet wird. Etwaige Console-Frage anhand tatsächlicher Nutzung beantworten. |
| `com.google.android.providers.gsf.permission.READ_GSERVICES` | Transitiver reCAPTCHA/Auth-Import. Kein eigener App-Laufzeitdialog; kein Nachweis für aktiv genutzte Telefonanmeldung. |
| `com.dominik.cardvault.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX-Core-Signature-Berechtigung zum Schutz interner Receiver; kein Laufzeitdialog. |

**Nicht vorhanden:** `READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `POST_NOTIFICATIONS`, Mikrofon, Kontakte, Telefon, Standort und Werbe-ID-Berechtigung. System-Photo-Picker statt breitem Galeriezugriff. Keine Berechtigung entfernt. Zusätzliche Console-Erklärungen lassen sich abschließend erst nach signiertem Upload anhand der erkannten SDKs und Nutzung prüfen; keine Erklärung vorweggenommen.

## H. TESTS

### Durchgeführt

Clean-Neubau: `:app:bundleRelease`, `:app:assembleRelease`, gefiltertes `:app:testDebugUnitTest`, `:app:lintRelease`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**, 2 min 58 s, 141 Tasks, davon 139 ausgeführt. Ein erster Aufruf enthielt das hier nicht vorhandene `testReleaseUnitTest` und scheiterte bereits bei der Task-Auswahl; danach mit den tatsächlich verfügbaren gezielten JVM-Tests erfolgreich neu ausgeführt. Keine erfundenen Release-JVM-Testergebnisse.

**152 JVM-Tests, 0 Fehler / 0 Fehlschläge:**

| Testklasse | Anzahl |
| --- | ---: |
| FirebaseEntitlementsTest | 4 |
| BinderIdentityTest | 7 |
| CatalogMemoryCacheTest | 5 |
| PokemonCatalogImagesTest | 7 |
| PokemonVisualCatalogCacheTest | 5 |
| ScannerReportContractTest | 15 |
| CollectionSyncPolicyTest | 11 |
| CardRecognitionRankerTest | 9 |
| HybridCandidateFusionTest | 19 |
| ManualBinderCatalogSelectionTest | 6 |
| ManualBinderPlacementTest | 16 |
| ManualScanIdentityTest | 12 |
| PromoBorderNumberParserTest | 16 |
| ScannerReportFlowTest | 14 |
| ScanPipelineStateTest | 6 |

**17 Instrumentation-Tests auf Phone-AVD, API 36, x86_64: OK, 56,071 s.** Frisch gebaute Debug- und Test-APK installiert; keine Produktionsdaten als Test-Fixtures verändert.

| Testklasse | Anzahl / Abdeckung |
| --- | --- |
| PortraitLockInstrumentedTest | 2: alle vier Drehungen, dieselbe Activity, Übersicht/Portfolio/Binder/Sammlung/Scanner-Einstieg/Settings/Google-Login-Einstieg |
| ManualSetLookupInstrumentedTest | 2: manuelle Set-/Nummer-Suche |
| ScannerReportDialogInstrumentedTest | 6: manuelle Einzelbestätigung, kein unbemerkter Versand |
| BinderRefreshInstrumentedTest | 1: vier Reload-/Blätterzyklen mit schrumpfender Seitenliste während Animation; Seitenindex begrenzt, Normal/Reverse und Besitzobjekte erhalten |
| ScannerConsentSettingsInstrumentedTest | 3: Consent-/Rollenverhalten |
| GradingWebNoticeInstrumentedTest | 2: Web-Hinweis statt aktiver Zustandsprüfung |
| CatalogSearchIndexMigrationInstrumentedTest | 1: Migration 9→10 erhält bestehende Felder |

Binder-Identität, manuelle Nummernpositionen und Schutz bestehender Karten sind durch die gezielten Unit-/UI-Tests abgedeckt; kein neues Binder-/Room-/Varianten-Refactoring vorgenommen.

Lint: **0 Errors/Fatals, 146 Warnungen und 2 Hinweise**. Hauptgruppen: ungenutzte Ressourcen, KTX-/Dependency-/Icon-/EXIF-Hinweise. Zusätzlich CredentialManager-Exception-Hinweis und Hinweis zur festen Orientation auf großen Geräten. Nicht als „warnungsfrei“ deklarieren und in diesem Vorbereitungslauf keine fachfremde Bereinigung starten. [Lint-Bericht](C:/Users/Dominik/AndroidStudioProjects/CardVault/app/build/reports/lint-results-release.html)

Paket/Manifest/Secret-/ELF-/ZIP-Prüfungen siehe C. Testberichte: [JVM-HTML](C:/Users/Dominik/AndroidStudioProjects/CardVault/app/build/reports/tests/testDebugUnitTest/index.html).

### Grenzen

- Instrumentation lief gegen **Debug**, nicht gegen eine Play-signierte Release-Installation. Ein Release-Compile und AAB-Validator ersetzen diesen Laufzeittest nicht.
- Login-Einstieg geprüft, keine neue echte Google-Anmeldung/Produktions-Synchronisierung ausgeführt.
- Keine neue reale Kartenserie, kein vollständiger Alle-Karten-/Alle-Preise-Beweis, kein 16-KB-Laufzeittest.
- Keine Play-Console-Annahme, Zertifikatsprüfung durch Google oder Pre-Launch-Report vorhanden.

## I. PHYSICAL DEVICE

Das Xiaomi war per WLAN-Debugging vorhanden; frühere physische Nutzer-Scans sind nicht als erneuter finaler Release-Test zu werten. In diesem Lauf keine signierte Release-Version auf dem Xiaomi installiert und keine neue vollständige physische Testfreigabe erteilt. Die Geräteoberfläche ließ automatisierte Eingaben nicht zu; keine Geräteschutz-Einstellung umgangen.

Nach Signierung/Internal-Test-Installation noch prüfen: Installation, Google-Anmeldung mit Play-Zertifikat, berechtigter Sync, Scanner mit echten Karten/Portrait, manuelle ScanLab-Freigabe, Binder-Blättern/Aktualisieren und Hinzufügen, App-Neustart sowie später das echte Play-Update.

**Nicht zur Behebung eines Signaturkonflikts blind deinstallieren.** Die bisherige Debug-Installation und spätere Play-App können unterschiedliche Zertifikate haben; ein direktes Update kann deshalb scheitern. Zuerst lokale Sammlung exportieren und vorhandenen Sync/Import tatsächlich verifizieren. Deinstallation kann lokale Gastdaten entfernen. Jetzt keine Deinstallation/Datenlöschung ausgeführt.

## J. GOOGLE PLAY

Empfohlen: **Internal Testing** für bis zu 100 ausgewählte Google-Konten, Installation über den erst nach Einrichtung/Freigabe verfügbaren offiziellen Opt-in-Link, kein APK-Versand nötig. Play-Teilnahme vergibt **keine** Firebase-Rolle: Tester installiert → Google/Firebase-Anmeldung → bestehende DomiDex-Administration verwaltet `pretester` bzw. `appAccess`; spezielle Foto-Berichte benötigen die passende Rolle. Keine automatische Rollenintegration behauptet oder angelegt. [Interne Tests](https://support.google.com/googleplay/android-developer/answer/9845334?hl=en)

**Vor dem finalen App-Anlegen bewusst vom Betreiber entscheiden: kostenlos oder kostenpflichtig.** Nach kostenloser Verfügbarkeit kann dieselbe Play-App nicht auf kostenpflichtigen Download umgestellt werden. Interne Tester können auch eine kostenpflichtige App kostenlos installieren. Damit zwingt die kostenlose Pre-Beta nicht zur dauerhaften Gratis-Preisentscheidung. Hier keine Auswahl, kein Händlerkonto und kein Betrag gesetzt. [Play-Preise](https://support.google.com/googleplay/android-developer/answer/6334373?hl=en), [Testregeln](https://support.google.com/googleplay/android-developer/answer/9845334?hl=en)

### Nummerierte Checkliste für den Betreiber

1. **App erstellen** in deinem vorhandenen Play-Console-Konto – erst nach bewusster Entscheidung zu Punkt 5, kein neues Konto durch diese Vorbereitung.
2. **App-Name:** DomiDex.
3. **Standardsprache:** Deutsch – de-DE.
4. **App/Spiel:** App. Kategorie-Empfehlung: Produktivität, vom Betreiber bestätigen.
5. **Kostenlos/kostenpflichtig:** bewusst festlegen, nicht automatisch „kostenlos“ wegen Pre-Beta wählen; Erläuterung oben beachten.
6. **Package Name:** `com.dominik.cardvault`, wird durch das AAB festgelegt. Bestehende Paketbindung prüfen, nicht umbenennen; Verfügbarkeit in deinem Play-Konto hier nicht nachgewiesen.
7. **App Access:** Gastbereiche und gesperrte Kontofunktionen wahrheitsgemäß beschreiben. Für Prüfung geschützter Funktionen einen tatsächlich nutzbaren, minimal berechtigten Prüferzugang mit Anleitung bereitstellen; keine privaten Admin-Zugangsdaten eintragen.
8. **Ads:** nach aktuellem Code keine Werbung / kein Ads-SDK. Betreiber bestätigt die Erklärung; externe Links sind keine eingebauten Werbe-SDKs.
9. **Content Rating:** Fragebogen anhand tatsächlicher Inhalte ausfüllen; keine Altersfreigabe raten.
10. **Target Audience:** Zielgruppe selbst festlegen. Pokémon-Bezug allein ersetzt keine Zielgruppen-/Kinderprüfung; keine Altersgruppe automatisch ausgewählt.
11. **Data Safety:** Bericht F bereithalten. Für ausschließlich internen Track nach aktueller Ausnahme behandeln; für weitere Tracks vollständig und mit Betreiberangaben beantworten.
12. **Privacy Policy:** tatsächliche URL `https://domidex.web.app/datenschutz`, aber vor Abhaken unvollständigen Text für Android/SDKs/Fotos/Löschung vervollständigen.
13. **Account Deletion:** öffentlichen funktionierenden Anfrageweg und Bearbeitungsprozess festlegen; keine inexistente URL eintragen. In-App-E-Mail-Anfrage ist keine automatische Datenlöschung.
14. **Store Listing:** deutsche Texte aus dem beigefügten Vorschlag prüfen. Support `Dominik.muxifeld@gmail.com`, Website `https://domidex.web.app`; Rechte/Betreiberangaben bestätigen.
15. **App Icon:** echtes DomiDex-Motiv in passendem 512×512-PNG exportieren/prüfen.
16. **Screenshots:** mindestens zwei echte aktuelle, formatgerechte App-Aufnahmen ohne private Angaben/Debug-Anzeigen vorbereiten; keine alten Lade-/Fehlerscreens verwenden.
17. **Feature Graphic:** 1024×500 passend erstellen, sobald für Listing erforderlich; bisher nicht vorhanden.
18. **Internal Test:** ausschließlich internen Track öffnen; Play App Signing einrichten, Upload-Key sicher konfigurieren. Vor Ausgabe an Tester Play-App-Signing-SHA für Google-Anmeldung prüfen/registrieren. Kein Production-/Open-Track.
19. **Tester-Liste:** freigegebene Google-Konten oder Gruppe, bis zu 100. Firebase-Pretester separat über bestehende Administration verwalten; keine erfundenen Adressen/Zugänge.
20. **AAB Upload:** **erst nach Signierung** neu gebautes und validiertes Bundle verwenden. `versionCode=17` muss noch unbenutzt sein; Console-Befund prüfen. Das aktuelle `UNSIGNED.aab` ist NICHT die hochzuladende Enddatei.
21. **Release Notes:** geprüfte deutschen Versionshinweise übernehmen; interner Name z. B. `DomiDex 1.0-pre-beta (17)`.
22. **Review:** Signatur, Manifest, SDK-/Gerätekompatibilität, erforderliche Erklärungen und Console-Fehler prüfen. Keine Warnungen als automatisch freigegeben darstellen.
23. **Internal Test veröffentlichen:** ausschließlich durch dich nach Freigabe; kein öffentlicher Rollout. Etwaige Console-Prüfung/Freigabe abwarten.
24. **Opt-in-Link kopieren:** tatsächlichen von Play bereitgestellten Link verwenden; kein Testlink wurde jetzt erfunden oder erstellt.
25. **Tester informieren:** Pre-Beta-Hinweis, richtige Google-Adresse, Opt-in → Play-Installation, Firebase-Rolle getrennt, Backup/Signaturhinweis für alte Debug-Installation und Feedbackweg mitteilen. Nichts automatisch auf Discord gepostet.

Die vollständige Checkliste bereitet auch später benötigte Store-Angaben vor; nicht jeder Punkt ist zwingend vor der ersten ausschließlich internen Installation erforderlich. Maßgeblich sind Track und tatsächliche Console-Prüfung, ohne daraus eine Rechts-/Policy-Garantie abzuleiten.

## K. GIT

- Branch bleibt `codex/ui-v3`; Ausgangscommit `1be7adc`.
- Der bereits vorhandene App-Änderungsstand wurde getestet und zusammen mit diesen Vorbereitungstexten für einen lokalen Übergabecommit geprüft. Dieser Lauf ergänzt Dokumentation, keine neuen Produktivcodeänderungen.
- Der Commit, der diesen Bericht hinzufügt, ist die Git-Referenz der Übergabe; vollständiger Hash und abschließender Working-Tree-Status stehen in der Übergabeantwort. Nicht mit einer Play-Release-Freigabe gleichsetzen.
- Kein Push, kein Force-Push, kein zusätzlicher GitHub-Release, keine Discord-Nachricht, kein Play-/Firebase-Deployment.
- Builds/Release-Kopie und lokale Konfigurationen bleiben ignoriert. Beispiel-Signierdatei enthält nur Platzhalter. Keine echten Schlüssel/Passwörter eingecheckt.

Danach Stopp; nächste Schritte nur mit ausdrücklicher Freigabe.
