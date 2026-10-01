# Play-Assets und öffentliche Website – 04.09.2026

> Historischer Bericht / Entwurf des im Titel genannten Stands; keine aktuelle Release- oder Produktfreigabe. Maßgeblich sind [Projektstatus vom 01.10.2026](PROJECT_STATUS.md) und [Versionshistorie](RELEASE_HISTORY.md). Aussagen zu R8, ScanLab/Knowledge, Grading, Cloud, Test-Tracks und Geschäftsmodell dürfen nicht ungeprüft übernommen werden. Alte lokale Artefaktverweise können außerhalb der Arbeitsstation fehlen.

Stand: 04.09.2026, ca. 03:26 Uhr Europe/Berlin. Read-only-Prüfung; ausschließlich dieser Bericht wurde angelegt. Keine Bilder erzeugt, konvertiert oder hochgeladen; kein Build, ADB, Login, Kontoeingriff oder Deployment.

## Ergebnis und Einordnung des internen Tests

Der Android-Launcher ist im Quellstand korrekt auf die vorhandenen DomiDex-Ressourcen verdrahtet. Ein vollständig vorbereitetes **Play-Store-Assetpaket wurde im Prüfumfang nicht gefunden**: separates 512×512-PNG, 1024×500-Featuregrafik und unverändert geeignete aktuelle Screenshots fehlen.

Das ist **nicht pauschal ein Blocker für den ersten Internal-Test-Upload**: Google erlaubt interne Tests vor Abschluss des App-Setups. Ausschließlich intern getestete Apps müssen laut Google das Data-safety-Formular noch nicht ausfüllen. Die konkrete Freigabe hängt weiterhin vom Play-Console-Zustand und einem gültigen Bundle ab; beides wurde hier nicht geprüft. [Google: Interne Tests](https://support.google.com/googleplay/android-developer/answer/9845334?hl=en), [Google: Data safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)

Die öffentliche Website antwortet erfolgreich, ihre drei verlinkten Informationsseiten sind aber inhaltlich unvollständig. Öffentlich auffindbare Links zu Kontolöschung und Beta-Nutzungsbedingungen wurden nicht gefunden. Das sind offene Veröffentlichungs-/Datenschutzprüfpunkte, keine rechtliche Freigabe oder automatische Aussage über eine technische Internal-Track-Sperre.

## Prüfumfang

- Android-Ressourcen sowie Bilddateien im Repository-Stamm, `artifacts`, `releases`, `.codex-screenshot-review-2` und gezielt nach Assetnamen; keine Build-/Gradle-Verzeichnisse als Assetquellen verwendet.
- Downloads nur oberste Ebene, nach DomiDex/CardVault/Screenshot/Store-/Feature-Namen gefiltert. Zwei relevante vorhandene Bilder untersucht; keine rekursive private Suche, keine Schlüssel-/Zugangsdaten geöffnet.
- Bildmaße/Format aus den Originaldateien gelesen; sieben ausgewählte Bilder zusätzlich visuell geprüft. SHA-256 bestätigt identische Launcher-JPGs und das unten genannte Screenshot-Duplikat.
- Website über direkte HTTPS-GETs und tatsächlich vorhandene HTML-Links geprüft. Kein angemeldeter oder interaktiver Web-App-Ablauf getestet.

## Launcher, Store-Icon und Featuregrafik

| Vorhandene Quelle | Tatsächliche Maße/Format | Bewertung |
| --- | --- | --- |
| domidex_launcher.jpg (`app/src/main/res/mipmap-nodpi/domidex_launcher.jpg`, lokaler Quell-/Artefaktverweis) | 1280×1280, JPEG, RGB24, 90.236 Bytes | Aktuelles rundes D-Markenmotiv auf dunklem Quadrat. Als vorhandene Ausgangsdatei geeignet; **kein unverändert hochladbares Play-Icon**. |
| domidex_launcher_round.jpg (`app/src/main/res/mipmap-nodpi/domidex_launcher_round.jpg`, lokaler Quell-/Artefaktverweis), domidex_discord_logo.jpg (`app/src/main/res/mipmap-nodpi/domidex_discord_logo.jpg`, lokaler Quell-/Artefaktverweis) | Je 1280×1280, JPEG, RGB24; identischer Dateiinhalt wie oben | Keine zusätzlichen Store-Varianten. |
| Adaptive Icon (`app/src/main/res/mipmap-anydpi-v26/domidex_launcher.xml`, lokaler Quell-/Artefaktverweis), entsprechende Round-XML | Android-XML, keine Rastermaße | Foreground verweist auf das DomiDex-Motiv mit 72dp zentrierter Darstellung; Hintergrund `#080913`. Manifest nutzt `@mipmap/domidex_launcher` und `_round`. Kein monochromer Layer in dieser aktiven Variante; hier kein Release-Blocker daraus abgeleitet. |
| Alte `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher*.webp` | 48² / 72² / 96² / 144² / 192², WebP | Alte Ressourcen, nicht die im Manifest benannte DomiDex-Iconfamilie. Kein Store-Icon. |
| [Downloads: Codex-Bild domidex website](<C:/Users/Dominik/Downloads/Codex-Bild domidex website>) | 1536×1024, PNG ohne Dateiendung, RGB24, 1.913.939 Bytes | Visuell eine Web-Designübersicht/Referenz, **kein echter Android-Screenshot** und keine 1024×500-Featuregrafik. Nicht als dargestellter App-Istzustand verwenden. |
| [Öffentliches OpenGraph-Bild](https://domidex.web.app/og.png) | HTTP 200, `image/png`, 1.337.573 Bytes; HTML deklariert 1200×630 | Website-Asset, nicht das Play-Featureformat. Rastermaße hier nur aus Website-Metadaten, nicht separat dekodiert; keine Datei heruntergeladen. |

Die aktive Verdrahtung steht im Manifest (`app/src/main/AndroidManifest.xml`, lokaler Quell-/Artefaktverweis) und im Foreground-Layer (`app/src/main/res/drawable/domidex_launcher_foreground.xml`, lokaler Quell-/Artefaktverweis). Das vorhandene Binder-Cover (`app/src/main/res/drawable-nodpi/binder_eevee_prismatic_cover.png`, lokaler Quell-/Artefaktverweis) ist 1219×1290 PNG/RGBA und ebenfalls keine Featuregrafik.

Für ein veröffentlichtes Store-Listing nennt Google: Icon 512×512 als 32-Bit-PNG, maximal 1024 KB; Featuregrafik 1024×500 als JPEG oder 24-Bit-PNG ohne Alpha. Ein passender fertig exportierter Satz wurde nicht gefunden. Nutzungsrechte und finale Motivfreigabe sind vor Veröffentlichung separat zu bestätigen. [Google: Vorschau-Assets](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en)

## Vorhandene echte Screenshots

Viele frühere Geräte-/Emulatoraufnahmen sind vorhanden, überwiegend vom 30./31.08.2026; ein Gerätebild ist vom 01.09.2026. Die Release-Archive enthalten ausdrücklich ältere RC-Stände. Sie ersetzen keinen Nachweis des nun einzureichenden Builds.

| Konkrete Datei/Gruppe | Maße/Format | Inhalt und Eignung |
| --- | --- | --- |
| domidex-ui-v3-home-final.png (`domidex-ui-v3-home-final.png`, lokaler Quell-/Artefaktverweis) | 1080×2400, PNG/RGBA32 | Echte Übersicht mit leerer Sammlung; visuell geprüft. Älterer UI-Stand. |
| domidex-ui-v3-binder.png (`domidex-ui-v3-binder.png`, lokaler Quell-/Artefaktverweis) | 1080×2400, PNG/RGBA32 | Echte Binder-Übersicht ohne Binder; visuell geprüft. |
| domidex-ui-v3-scanner.png (`domidex-ui-v3-scanner.png`, lokaler Quell-/Artefaktverweis) | 1080×2400, PNG/RGBA32 | Zeigt Berechtigungszustand mit altem „Testbild“-Button und pauschaler Aussage zum Verbleib von Aufnahmen. **Nicht als aktueller Scanner-Funktionsnachweis übernehmen.** |
| domidex-prebeta-device.png (`artifacts/domidex-prebeta-device.png`, lokaler Quell-/Artefaktverweis) | 1280×2772, PNG/RGBA32, 1.111.910 Bytes | Echte Geräteübersicht mit Sammlungs-/Preisdaten; visuell geprüft. Datum 01.09.2026. Veröffentlichung dieser Daten und Kartenabbildungen bestätigen. |
| Bekannter Downloads-Screenshot (`Downloads/_storage_emulated_0_Pictures_Screenshots_Screenshot_2026-08-31-21-40-16-674_com.dominik.cardvault.jpg`, historischer lokaler Nachweis) | 1280×2772, JPEG/RGB24, 794.267 Bytes | Echte Sammlungsansicht; visuell geprüft. Identisches Exemplar unter `.codex-screenshot-review-2/Screenshot_2026-08-31-21-40-16-674_com.dominik.cardvault.jpg`. |
| `releases/rc-1.0.4/{01_home,06_binder,08_prices}.png` | Je 1080×2400, PNG/RGBA32 | Drei ältere Release-QA-Screenshots. |
| `releases/rc-1.0.5/screenshots/*.png` | Sieben Dateien, je 1080×2400, PNG/RGBA32 | Alte Home-/Binder-/Suche-/Preisaufnahmen. |
| Weitere `artifacts/screenshots/*.png`, `artifacts/app-stand/*.png`, Repository-Stamm | Überwiegend 1080×2400 PNG/RGBA32; einzelne 1280×2772 | QA-Material, darunter leere/ladende Ansichten und Debug-/Berechtigungszustände. Keine pauschale Store-Freigabe. |

Technische Listing-Lücke: 1080×2400 hat ein Seitenverhältnis von 2,22:1; 1280×2772 von 2,17:1. Beide überschreiten Googles maximale Relation 2:1. Die PNG-Dateien besitzen außerdem einen Alpha-Kanal. Google verlangt für Listing-Screenshots JPEG oder 24-Bit-PNG ohne Alpha, Seitenlängen 320–3840 Pixel, mindestens zwei Screenshots. Deshalb diese Originale nicht unverändert als fertiges Listingpaket behandeln. Keine Zuschnitte oder Konvertierungen wurden vorgenommen. [Google: Screenshot-Anforderungen](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en)

## Öffentliche Website: tatsächliche Links und Inhalte

| Ziel | Ergebnis | Inhaltsrelevanz / offene Punkte |
| --- | --- | --- |
| [Startseite](https://domidex.web.app/) | HTTPS 200, DomiDex-Titel und deutscher Inhalt | Beschreibt Android-Scanner, Sammlung, Binder, Portfolio und Web-App. Öffentliche Navigation verlinkt `/updates`, `/roadmap`, `/faq`, `/app`; Footer verlinkt genau die drei folgenden Informationsseiten. Google-Play-Testzugang wird als in Vorbereitung beschrieben. |
| [Datenschutz](https://domidex.web.app/datenschutz) | HTTPS 200, direkt verlinkt | Kurze Web-Angaben zu Firebase Authentication/Firestore, fehlenden Tracking-Cookies und Hosting-Protokollen. Kündigt vollständige Erklärung erst vor Produktstart an. Android-Kamera/OCR, freiwillige ScanLab-Fotoübermittlung, Aufbewahrung/Löschung und konkreter Ansprechpartner sind dort nicht vollständig beschrieben. **Nicht als vollständig abgehakt markieren.** |
| [Kontakt](https://domidex.web.app/kontakt) | HTTPS 200, direkt verlinkt | Verweist nur auf den Discord-Link der Startseite; keine eigene E-Mail/Formularangabe. Angekündigte geschäftliche Kontaktadresse noch nicht enthalten. |
| [Impressum](https://domidex.web.app/impressum) | HTTPS 200, direkt verlinkt | Pre-Beta-Platzhalter; vollständige Anbieter-/ladungsfähige Kontaktdaten erst für später angekündigt. Keine rechtliche Vollständigkeitsbestätigung. |
| Kontolöschung | Kein entsprechender öffentlicher Link gefunden | Geprüfter Linkgraph: Startseite, drei Footer-Seiten, FAQ, Updates, Roadmap; keine öffentlich verlinkte Löschanleitung oder Löschanfrage gefunden. Keine URL geraten und kein Löschvorgang ausgelöst. Hinter Anmeldung möglicherweise vorhandene Funktionen wurden nicht geprüft. |
| Beta-Nutzungsbedingungen | Kein entsprechender öffentlicher Link gefunden | Pre-Beta-Hinweise existieren, aber keine tatsächlich verlinkte eigene Bedingungen-/Nutzungsseite im geprüften Linkgraph. Inhaltliche Entscheidung/Freigabe durch Betreiber offen; nicht pauschal als allgemeine Play-Pflicht behauptet. |
| [FAQ](https://domidex.web.app/faq), [Updates](https://domidex.web.app/updates), [Roadmap](https://domidex.web.app/roadmap) | Je HTTPS 200 | Zusätzliche öffentlich verlinkte Seiten; keine dort entdeckten Kontolöschungs-/Bedingungslinks. |
| [Web-App](https://domidex.web.app/app) | HTTPS 200 | Initiales HTML zeigt nur Vorbereitung/Ladezustand. Kein Login und keine Prüfung privater Kontofunktionen. |

Abgleich ohne externe Kontaktaufnahme: AppSupportConfig.kt (`app/src/main/java/com/dominik/cardvault/ui/AppSupportConfig.kt`, lokaler Quell-/Artefaktverweis) enthält bereits `DomiDex · Dominik` und die als öffentliche Supportadresse konfigurierte `Dominik.muxifeld@gmail.com`; diese ist auf `/kontakt` nicht aufgeführt. Die dort konfigurierte Discord-Einladung unterscheidet sich außerdem vom öffentlichen Website-Link. Zuständigkeit und gewünschte einheitliche Ziele bestätigen; weder E-Mail noch Einladung wurden ausgelöst.

## Offene Entscheidungen / prüfen

1. **Listing-Assets offen:** vorhandenes Originalmotiv für einen korrekten Store-Icon-Export freigeben; echte Featuregrafik bereitstellen; aktuelle, formatgeeignete Screenshots des Release-Builds auswählen/aufnehmen. Keine Bildherkunft oder Freigabe unterstellen.
2. **Datenschutzinhalt offen:** vollständige, zum tatsächlichen Android-/Web-/ScanLab-Verhalten passende Angaben und bestätigte Support-/Datenschutzkontaktdaten ergänzen lassen. HTTP 200 allein bestätigt keine Eignung. [Google: User Data](https://support.google.com/googleplay/android-developer/answer/10144311)
3. **Öffentlicher Löschweg offen:** konkrete funktionale URL und klaren Antragspfad bestätigen. Google verlangt bei Kontoerstellung einen innerhalb und außerhalb der App auffindbaren Löschweg; ein bloßer App-Login oder allgemeiner Kontaktplatzhalter ist hier kein Nachweis. [Google: Kontolöschung](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en-EN)
4. **Betreiberangaben/Beta-Regeln offen:** benötigte öffentliche Anbieterinformationen und gewünschte Beta-Nutzungsinformationen vom Betreiber festlegen bzw. fachlich prüfen lassen. Keine rechtliche Garantie durch diesen Bericht.
5. **Internal-Test-Freigabe gesondert:** Signing/Bundle, Console-Erklärungen, Testerliste und Veröffentlichungsstatus liegen außerhalb dieses read-only Asset-/Website-Checks.
