# Einstellungen / Konto / Datenschutz – 3. September 2026

> Historischer Bericht / Entwurf des im Titel genannten Stands; keine aktuelle Release- oder Produktfreigabe. Maßgeblich sind [Projektstatus vom 01.10.2026](PROJECT_STATUS.md) und [Versionshistorie](RELEASE_HISTORY.md). Aussagen zu R8, ScanLab/Knowledge, Grading, Cloud, Test-Tracks und Geschäftsmodell dürfen nicht ungeprüft übernommen werden. Alte lokale Artefaktverweise können außerhalb der Arbeitsstation fehlen.

## Umfang und Ergebnis

Nur Einstellungen, Kontodarstellung, Kontakt/Löschanfrage und die vorhandene Scanner-Einwilligung wurden in diesem Lauf geändert. Bereits vorhandene Änderungen im Arbeitsverzeichnis wurden erhalten. Keine Änderung an Scanner-Erkennung, Binder-, Preis-, Room-, Sammlungs- oder Firebase-Sync-Implementierung; keine Webänderung, kein Deployment, kein Commit/Push. Keine realen Nutzerdaten gelöscht, keine Testberichte hochgeladen, keine E-Mail versendet.

Einstellungen enthalten jetzt „Konto & Datenschutz“, die gemeinsame Scanner-Einwilligung, Support, tatsächliche installierte Version/Build, Pre-Beta/privat/Non-Profit und einen gesonderten Bereich „Konto löschen“. Design und bestehende Navigation wurden beibehalten.

## Konto und Synchronisierung

- Tatsächlicher Google-Profilname und E-Mail, soweit vorhanden; keine prominente UID und keine Tokens.
- Der bestehende Firebase-Login/-Logout bleibt zuständig. Die Oberfläche beobachtet Kontoänderungen und entfernt alte Personendaten beim Logout.
- Asynchrone Rückmeldungen zu Kontoaktionen sind an UID und Sitzung gebunden. Alte Kontorückmeldungen dürfen nach einem Wechsel nicht erscheinen.
- Interne Einstellungen verlangen zusätzlich zum Debug-Build einen bestätigten Admin-Claim; ein Kontowechsel schließt sie.
- Die Darstellung unterscheidet Gastbetrieb, angemeldetes Firebase-Konto, lokale Synchronisationsfehler und Konflikte. Der Cardmarket-Katalogstatus wird nicht länger als erfolgreiche Kontosynchronisierung dargestellt. Keine Behauptung vollständiger Cloud-Synchronisierung allein wegen fehlender Fehlermeldungen.

## Datenschutz und Scanner-Einwilligung

Öffentliche, per HTTP 200 geprüfte Adresse: https://domidex.web.app/datenschutz

Die Seite existiert, bezeichnet ihren Rechtstext aber selbst als noch unvollständig. Die App weist darauf hin. Dies ist keine abschließende rechtliche Freigabe.

Scanner und Einstellungen verwenden unverändert `scanner_report_consent` / `automatic_v1_<uid>`. Gemeinsamer Store mit Listenern statt zweiter Einstellung; bestehende Entscheidungen bleiben bestehen. Die Einwilligung gilt pro Konto auf diesem Gerät. Aktivieren verlangt Tester-/Adminberechtigung und die ausdrückliche Bestätigung im bestehenden Einwilligungsdialog. Ausschalten bleibt auch bei fehlender Verbindung oder entfallener Berechtigung möglich.

Der Text beschreibt den realen Datenfluss: unklare/fehlgeschlagene manuell ausgelöste Fotos, Kartenmerkmale und technische Analyse; keine automatische Übertragung von Galerie- oder Auto-Capture-Bildern. Bild-Metadaten werden vor dem Upload entfernt. Berichte sind dem angemeldeten Konto zugeordnet und deshalb nicht anonym. Dominik prüft sie im Admin-Scanner-Lab. Ein Widerruf verhindert neue automatische Uploads, löscht aber weder bereits gesendete Berichte noch garantiert er den Abbruch laufender Übertragungen. Ein separat bestätigter manueller Upload bleibt unabhängig davon möglich.

## Löschung und Support

Kontakt: Dominik.muxifeld@gmail.com

Eine vollständige serverseitige Kontolöschfunktion wurde in den vorhandenen relevanten Android-/Webpfaden nicht gefunden. Es wird deshalb **keine** isolierte Firebase-Auth-Löschung mit zurückbleibenden Nutzerdaten ausgeführt.

Der neue Dialog erklärt die Folgen, verlangt eine Checkbox und öffnet ausschließlich einen selbst zu prüfenden und zu versendenden E-Mail-Entwurf. Auch dann wurde noch nichts gelöscht. Ohne nutzbare Mail-App wird die öffentliche Kontaktadresse mit einer ehrlichen Fehlermeldung angezeigt. Keine privaten Kontofelder, Anhänge, Tokens oder Sammlungsdaten werden automatisch in die Anfrage geschrieben.

Bei der späteren vollständigen Bearbeitung zu berücksichtigen: Firebase-Auth-Konto, kontogebundene Sammlung einschließlich persönlicher Binder-/Portfoliozustände, lokale kontogebundene Daten und Einstellungen/Einwilligungen sowie zuordenbare Scanner-Berichte und ihre Bilder. Globale Referenzdaten, Scannerprofile und das gemeinsame Manifest dürfen nicht pauschal entfernt werden. Bearbeitung und Identitätsprüfung durch den Betreiber sowie das sichere Verfahren für Cloud- und lokale Daten müssen noch verbindlich geklärt werden.

Es wurde kein klarer öffentlicher Kontolösch-/Löschanfrageweg auf der Website gefunden. `/kontakt` war erreichbar, enthält aber keinen fertigen solchen Ablauf. Keine erfundene Lösch-URL angelegt. Für den Play-Release ist der öffentliche Anfrageweg zusätzlich zum App-Einstieg zu ergänzen; eine manuelle Bearbeitung kann dabei verwendet werden, muss aber tatsächlich funktionieren. Referenz: https://support.google.com/googleplay/android-developer/answer/13327111?hl=de

## Tests

### Lokale Tests – 51/51 bestanden

| Klasse | Tests |
| --- | ---: |
| GoogleAccountStateTest | 5 |
| FirebaseEntitlementsTest | 4 |
| CanonicalCollectionOwnerTest | 2 |
| ScannerReportFlowTest | 14 |
| ScannerReportContractTest | 15 |
| CollectionSyncPolicyTest | 11 |

### Emulator – 13 unterschiedliche Tests bestanden

Android-36-Emulator `emulator-5554`, bestehender Gastbestand erhalten, kein persönliches Testkonto angemeldet.

| Klasse | Tests | Geprüft |
| --- | ---: | --- |
| GoogleAccountCardInstrumentedTest | 2 | Tatsächliche UI mit eingespeisten Kontozuständen, Abmelden/erneut Anmelden als UI-Callbacks, kein alter Name/E-Mail, gesperrter laufender Login |
| ScannerConsentSettingsInstrumentedTest | 3 | Vorhandener Preference-Schlüssel, zwei aktive Leser, Persistenz/Wiederöffnen, Kontotrennung, bestätigtes Aktivieren, Offline-Widerruf, Abbruch |
| SettingsPrivacyInstrumentedTest | 6 | Öffentliche Ziele, Mailentwurf ohne private Daten, Checkbox, Abbrechen ohne Aktion, ehrliche Rückmeldung, fehlende/gesperrte Mail-App |
| SettingsNavigationInstrumentedTest | 1 | Echte App: Settings, Konto/Gast-Syncstatus, Consent, Datenschutzadresse, installierte Version/Build, gesperrte Löschanfrage, Abbrechen, Adminbereich verborgen, Binder-Navigation |
| CardDataUiPerformanceInstrumentedTest | 1 | Bestehenden Binder zweimal öffnen, Seite blättern, vorhandene Karte laden, Scanner-Navigation |

Im ersten Navigations-Testlauf wurde die bestehende Großschreibung von Abschnittstiteln fälschlich case-sensitiv geprüft; nur die Testsuche wurde berichtigt, der erneute Lauf bestand. Die übrigen zwölf Tests bestanden bereits im gemeinsamen Lauf.

Zusätzlich manuell im Emulator: reale Google-Credential-Auswahl geöffnet, ohne Kontoanmeldung abgebrochen, Meldung „Anmeldung wurde abgebrochen“ und erneut verfügbarer Login geprüft. Datenschutzknopf öffnet Chrome mit dem öffentlichen Ziel. Screenshots des fertig geladenen Settings- und Konto-Dialogs visuell geprüft: bestehendes Dark/Purple-Design, scrollbar, keine offensichtliche horizontale Überlagerung. Neue Checkbox-/Switch-Zeilen sind mindestens 56 dp hoch, neue Aktionsflächen mindestens 48 dp; Zustände werden nicht allein über Farbe vermittelt.

`assembleDebug`, `assembleDebugAndroidTest` und `lintDebug` erfolgreich. Lint: 0 Fehler, 144 Warnungen und 2 Hinweise im gesamten aktuellen Projekt; darunter KTX-Stilhinweise in neuen Dateien. Keine Warnungen pauschal unterdrückt. Ein zunächst falscher Test-Import wurde korrigiert; finaler Compile/Build und Lint sind erfolgreich.

Nicht als bestanden behauptet: Anmeldung/Abmeldung mit einem echten Google-Konto und anschließender Live-Zwei-Geräte-Sync; TalkBack-End-to-End; physische Kamera-/Smartphoneprüfung.

## Dateien dieses Laufs

- `app/src/main/java/com/dominik/cardvault/ui/CardVaultApp.kt`
- `app/src/main/java/com/dominik/cardvault/ui/AppSupportConfig.kt`
- `app/src/main/java/com/dominik/cardvault/ui/SettingsPrivacy.kt`
- `app/src/main/java/com/dominik/cardvault/ui/ScannerReportFlow.kt` (nur Einwilligungsbereich)
- `app/src/main/java/com/dominik/cardvault/data/feedback/ScannerReportConsentStore.kt`
- `app/src/main/java/com/dominik/cardvault/auth/FirebaseGoogleAuthGateway.kt`
- `app/src/test/java/com/dominik/cardvault/auth/GoogleAccountStateTest.kt`
- `app/src/test/java/com/dominik/cardvault/ui/ScannerReportFlowTest.kt`
- `app/src/androidTest/java/com/dominik/cardvault/ui/GoogleAccountCardInstrumentedTest.kt`
- `app/src/androidTest/java/com/dominik/cardvault/ui/ScannerConsentSettingsInstrumentedTest.kt`
- `app/src/androidTest/java/com/dominik/cardvault/ui/SettingsPrivacyInstrumentedTest.kt`
- `app/src/androidTest/java/com/dominik/cardvault/ui/SettingsNavigationInstrumentedTest.kt`
- Dieser Bericht.

## Release-Bewertung

**Noch nicht READY FOR GOOGLE PLAY INTERNAL TEST.** Konkrete verbleibende Punkte:

1. Öffentliche Datenschutzerklärung für den tatsächlichen Android-/Firebase-/Scanner-Datenfluss vervollständigen.
2. Öffentlichen Löschanfrageweg und eine verlässlich ausführbare vollständige Daten-/Kontolöschbearbeitung bereitstellen. Die App eröffnet bislang nur den ehrlichen E-Mail-Kontaktweg.
3. Echten Google-Login/-Logout samt Kontotrennung und Live-Sync mit autorisierten Testkonten abschließend prüfen.

Physisches Android-Gerät getestet: **NEIN**. Der Betreiber übernimmt diesen Test später. Keine Arbeiten auf dem verbundenen Telefon ausgeführt.

## Nachtrag: physischer Xiaomi-Test auf ausdrücklichen Nutzerwunsch

Der obige Stand beschreibt den ursprünglichen Emulator-Durchlauf. Anschließend wurde der verbundene Xiaomi (Modell 2510ERA8BG, Android 16 / API 36, WLAN-Debugging) tatsächlich verwendet:

- Aktuelle vorhandene Debug-APK als Update mit Datenerhalt installiert, ohne Deinstallation oder App-Daten-Reset.
- App startet und zeigt die ursprüngliche Oberfläche mit Navigation auf dem physischen Gerät.
- 12/12 gezielt ausgewählte Gerätetests bestanden: GoogleAccountCardInstrumentedTest (2), SettingsPrivacyInstrumentedTest (6), ScannerReportDialogInstrumentedTest (4), Laufzeit 17,26 Sekunden.
- Diese Sicherheitstests verwenden simulierte Kontozustände, Mailaufrufe und Uploadtransporte. Kein echtes Konto wurde abgemeldet, keine Mail gesendet, keine Karte verändert, keine Datei hochgeladen und keine gespeicherte Einwilligung umgeschaltet. Gast-exklusive Navigationstests und Tests, die Consent-Schlüssel verändern, wurden auf diesem Gerät bewusst nicht ausgeführt.
- Die weitere Fernbedienung der echten Oberfläche ist blockiert: Android weist `input tap` mit `SecurityException` / fehlender `INJECT_EVENTS`-Berechtigung zurück. Keine Umgehung vorgenommen. Weitere manuelle UI-, echter Login-/Logout- und Live-Sync-Tests auf dem Xiaomi sind noch nicht abgeschlossen.

**Physisches Gerät jetzt teilweise getestet: JA; vollständiger interaktiver Durchlauf: noch offen.** Für die weitere Fernbedienung muss der Betreiber die zusätzliche Xiaomi-Option „USB-Debugging (Sicherheitseinstellungen)“ prüfen/aktivieren. Die vorher genannten öffentlichen Datenschutz-/Löschprozess-Blocker bleiben bestehen. In diesem Nachtrag wurden keine App-Quelltexte oder Produktionsdienste geändert.
