# DomiDex – technischer Projektstatus

Stand: **2026-10-04**, Europe/Berlin. Ausgangsbefunde des ursprünglichen
Dokumentationsaudits: lokaler Checkout, Git-Historie, GitHub und Google Play
Console lesend. Die anschließenden lokalen Änderungen und Teststände sind im
[Release-27-Ledger](RELEASE_27.md) maßgeblich dokumentiert; kein Gesamt-Gate-Pass
und keine produktive Veröffentlichung. Der Engineeringbranch heißt
`codex/release-27-hardening`; sein Ausgangscheckpoint ist `53b4e40d`.

**Fortschreibung 02.10.2026:** Full-Sync bleibt für R27 aktiv, Delta bleibt
deaktiviert. Der nachgewiesene große History-Engpass wurde eng ohne Schema- oder
Aufbewahrungsänderung verbessert. Dieselbe aktuelle Debug-APK ist ausdrücklich
freigegeben auf dem Redmi installiert und besteht lokal371 Regressionen,18
fokussierte Room-Fälle,14 echte lokale SDK-Fälle und den unveränderten
58-Messpunkte-Jahreshistorientest. R8-only/DEX ist geprüft, kein neuer Release
signiert oder veröffentlicht. Drei Benutzer-Scans belegen vorhandene richtige
Vorschläge, nicht eindeutige automatische Treffer. Hardware-/Mehrgeräte-/Scheduler-,
Live-/Datenschutz-Betriebs- und Release-Nachweise sind weiterhin offen. Gate15
bleibt gesperrt; genaue Artefakte und Grenzen im aktuellen Release-27-Ledger.

## Unterschiedliche Projektstände

**Aktueller Stand 04.10.2026, nach 21:54:** Die geschlossene Alpha ist inzwischen
auch tatsächlich live: Die Play Console zeigt „Aktiv“, „Für ausgewählte Tester
verfügbar“, Release „27 · Geschlossene Alpha · Preise & Stabilität“ und
„Veröffentlicht am 4. Okt. 21:54“. Die Veröffentlichungsübersicht bestätigt das
veröffentlichte Update. Diese Nachprüfung war ausschließlich lesend; dabei wurde
keine weitere Veröffentlichung ausgelöst. Der frühere Prüfstatus unten ist
historisch. Das signierte AAB und Versionscode 27 sind unverändert.

Die Web-Regression wurde um 22:07 erneut ausgeführt: **1.299/1.299 bestanden**,
79 Dateien, keine Fehler oder übersprungenen Tests. Typecheck bestanden. Das ist
keine vollständige technische Gesamtfreigabe: Der Lock-only-30s-Nachweis bleibt
rot, ebenso bleiben die dokumentierten Hardware-/Mehrgeräte-/Betriebsgrenzen,
offenen Datenschutzabläufe und sechs fehlenden Herstellersymbole bestehen.
Die Anzeige 338/348 zählt bepreiste Exemplare, nicht verschiedene Karten-IDs.
Zwei bereits vor dem Deployment vorhandene nicht auflösbare Binder-Plätze sind
noch nicht ursächlich geklärt; sie belegen nicht automatisch fehlende Bilddateien.

**Git-Sicherung:** Android-R27-Quellstand `6ea94e2` war direkt auf GitHub bestätigt;
die neueren Release-Nachweise werden mit diesem Dokumentationscheckpoint ergänzt.
Web einschließlich der letzten geprüften Produktfixes und Testhelfer ist jetzt
ebenfalls gepusht und remote bestätigt: `77b37a1`, Repository `DomiDex-Web`.
Beide verwenden `codex/release-27-hardening`; Hauptbranches bleiben unverändert.
Ungeprüftes `backend/market-data` und vorhandene untracked Hardware-Helfer bleiben
lokal. Ein Branch-Push ist kein neuer GitHub-Release und keine technische Gesamtfreigabe.

**Ergänzung 04.10.2026, 21:50:** R27 ist im INTERNAL-Test aktiv und im geschlossenen
Alpha-Test zur Google-Überprüfung eingereicht, nicht bereits Alpha-live. Verwaltete
Veröffentlichung bleibt aktiv. Das exakte signierte AAB wurde nicht geändert.
Der konkrete Web-Buildpfadfehler ist behoben: RootLayout/Startseite und Unter-App
werden jetzt nachweislich getrennt gebündelt. 1.299 lokale Tests und45/45 jeweilige
Candidate-/Live-Prüfungen bestanden; tatsächliche Browserkontrolle bestätigt Design,
angemeldete348Karten, verbundene Synchronisierung, private Binder und10Freigaben.
Web/Backend sind unter Hosting334fdfd6961039a1/tagr27-20261004d veröffentlicht,
Firebase-Regeln sind bytegleich verifiziert. Keine Testerdaten/Rechte/Geräte verändert.
Historische Einschränkungen und fehlende sechs Herstellersymbole bleiben im Ledger.

**Ergänzung 04.10.2026, 21:29:** Das unveränderte signierte R27-AAB ist nach
ausdrücklicher Nutzerfreigabe im INTERNAL-Test aktiv. Auf die zusätzliche direkte
Nutzeranfrage wurde der bestehende R27-Entwurf im geschlossenen Alpha-Test als
einzige Änderung zur Google-Prüfung eingereicht. Alpha ist noch nicht als live
bestätigt; Vorabprüfungen laufen und verwaltete Veröffentlichung bleibt aktiviert.
Testerlisten, Geräte, Signing und Produktionskanal blieben unverändert.
Die überprüften Firebase-Regeln sind veröffentlicht und bytegleich zurückgelesen.
Der neue Web-Kandidat zeigte trotz bestandener HTTP-Checks einen echten Rootlayout-/
CSS-Fehler und wurde sofort auf die bisher funktionierende Hosting-Version
zurückgesetzt. Eine erste CSS-Brücke wurde durch den Produktionscheck widerlegt
und entfernt. Web-Deployment bleibt offen, keine pauschale Gesamt-Gate-Freigabe.
Nachweise und verbleibende Grenzen stehen im aktuellen Release-27-Ledger.

**Ergänzung 04.10.2026, 20:15:** Der lokale signierte R27-Kandidat ist gebaut und
verifiziert: `releases/v27/DomiDex-v27.aab`, Versionscode27,
`1.0-closed-alpha.6`, bestehendes Upload-Zertifikat, eingebettetes passendes
R8-Mapping. 39 Pipelineprüfungen und die erneute JVM-/Release-Lint-Prüfung sind
bestanden (Lint ohne Fehler, 170 Warnungen). Native ZIP deckt exakt libc++ ab;
sechs Herstellersymbole fehlen weiterhin. Keine vollständige Rolloutfreigabe:
Web-/Backend-Voraussetzungen sowie Hardware-/Betriebsgrenzen bleiben im Ledger
ausdrücklich dokumentiert. Kein Google-Play-Upload, Deployment oder Gerätetest.
R26 unverändert; der separate vorhandene Hardware-Harness bleibt unangetastet.

**Ergänzung 04.10.2026, 20:00:** Der aktuelle vollständige JVM-/Lint-Lauf und die
anschließende Android17-Gesamtregression sind bestanden: 1.062 JVM-PASS, ein
unveränderter optionaler Live-Skip; 371/371 Androidfälle ohne Skip/Fehler.
Debug-Lint hat keine Fehler, aber 144 Warnungen. Die 46 Datenbank-Fehlerfälle
und der tatsächliche lokale Löschworkflow sind separat ebenfalls bestanden.
Jetzt folgt die gezielte Integration in den Engineeringbranch und der vom Nutzer
erlaubte lokale signierte Kandidat. Das ist keine pauschale Gate- oder
Produktionsfreigabe: Hardware-/Betriebsgrenzen und die fehlenden Herstellersymbole
bleiben ausgewiesen. Keine Play-Veröffentlichung und kein Eingriff am Redmi.

**Ergänzung 04.10.2026, 19:48:** Alle46 isolierten Datenbankfälle bestanden,
einschließlich echtem Speicherfehler beim ERROR-Schreiben, erhaltenem ursprünglichem
Importfehler, allen15 geschützten Tabellen und erfolgreichem Wiederöffnen.
Testpakete sauber entfernt; reale Geräte unberührt. Beide reinen Harnessfehler
(Wort im Dateipfad/Windows-Pfadlänge) bleiben als Fehlbelege dokumentiert und sind
eng korrigiert. Die Live-Web-Zuordnung zur Serverrevision vom25.09. ist nun lesend
bestätigt, ohne Deployment. Vollständige JVM/Lint- und aktuelle Android17-Prüfung
folgen; noch keine signierte AAB oder Gesamtfreigabe.

**Ergänzung 04.10.2026, nach19:15:** Der echte lokale Lösch-/Berechtigungsworkflow
ist mit frischen Debug-APKs bestanden:1/1 Androidfall,2,257s, tatsächlicher
Dienstaufruf und Firebase-Emulatoren, unveränderte private Daten sowie vollständig
geprüfte Testbereinigung. Kein Nachweis für produktive Endlöschung oder ältere
Löschaufträge. Beim folgenden46er-Datenbanklauf trat ein enger Prüfhelferfehler
auf: `error` im eigenen Dateipfad wurde trotz erfolgreicher Installation als
Fehler gewertet. Isoliertes Paket bereinigt, Parserkorrektur mit19 Kontrollen
abgesichert; neuer Lauf folgt. Keine Änderung am Redmi, Signing oder Produktion.

**Ergänzung 04.10.2026, 19:10:** Drei echte Regressionen im Import-Fehlerpfad
sind reproduziert und gezielt behoben: dieselben elf JVM-Fälle bestehen jetzt,
einschließlich Abbruch und Freigabe der bestehenden Importsperre. Kein Eingriff
in Full-Sync, Preisquellen, Schema oder Import-Reihenfolge. Der tatsächliche
native Fehlerstatus-Schreibtest und die anschließende Gesamtregression stehen
noch aus. Neue Debug-APKs für den Lösch-/Berechtigungsnachweis werden gebaut;
kein Release signiert oder veröffentlicht.

**Ergänzung 04.10.2026, 19:00:** Der zusätzliche reale SQLite-Vollspeichertest
ist nun bestanden:45/45, keine Skips/Fehler,109,877s. Ein passiver Testbeobachter
belegt den echten nativen Speicherfehler trotz äußerem Room-Rollbackfehler;
Bestandsdaten, Wiederöffnen und Fortsetzung nach testinternem Limit-Anheben sind
geprüft. Die früheren Fehlversuche bleiben dokumentiert. Isolierte Testpakete sauber
entfernt; normales App-Paket und Redmi unverändert.92 reine Hostprüfungen für den
neuen Löschworkflow bestehen, dessen Android-/Dienstelauf noch offen ist. Ein
separater Fehler beim Speichern des Import-Fehlerstatus wird reproduzierbar geprüft.
Kein finaler Release-PASS; Gate15 bleibt gesperrt. Ältere Ergänzungen unten sind
datierte Zwischenstände und ersetzen nicht diesen bzw. den aktuellen Ledgerstand.

**Ergänzung 04.10.2026, 18:41:** Derselbe aktuelle Android17-Debugstand besteht
auch den vorher festgelegten zweiten vollständigen Lauf:371/371, keine Skips oder
Fehler,335,961s, unveränderte Quellen/APKs und bestandene Abschlusskontrollen.
Damit zwei Gesamt-PASSes; der historische sporadische OCR-Fehler bleibt dennoch
unaufgeklärt dokumentiert. Keine Aussage zu Echtgeräte-Geschwindigkeit oder
finaler Freigabe. Speicher-/Rechteentzugsnachweise bleiben als nächste lokale
Schritte offen; Redmi, Signing und Produktion unverändert.

**Ergänzung 04.10.2026, 18:21:** Der zusätzliche echte SQLite-Vollspeicher-Fall
scheitert an einer nicht passenden Ausnahme; alle bisherigen44 Speichertests
bleiben grün. Native Logs zeigen zuerst Speicherfehler13, danach einen Fehler
beim Rollback. Eine reine Testdiagnose wird geprüft; weder App-Fix noch
Datenintegritäts-Pass werden daraus vorweggenommen. Isolierte Testpakete sauber
entfernt, Redmi und Produktion unverändert. Gate15 bleibt gesperrt.

**Ergänzung 04.10.2026, 18:03:** Der isolierte Android8-Nachweis für echte
OS-Wiederaufnahme desselben gespeicherten Wartungs-Folgeauftrags ist bestanden,
einschließlich unveränderter privater Daten und korrekter Akku-Wiederherstellung.
Das ist kein Echtgeräte-/OEM-Nachweis. Der erste Paket-Cleanup entfernte die
Testpakete, scheiterte aber an einer anschließenden ADB-Abfrage; seine separate
Nachprüfung ist nun bestanden. SQLite-Speicherfehler und weitere Release-Grenzen
bleiben offen.

**Ergänzung 04.10.2026, 17:48:** Die neue, zeitdiagnostisch ergänzte Debug-APK
besteht auf frisch gestartetem Android17 alle371 unveränderten Regressionen,
ohne Fehler oder Skips. Quellen-/Artefakt- und Diagnoseprüfungen bestanden.
Der frühere sporadische Scanner-Folgetestfehler ist damit nicht dauerhaft als
behoben erklärt; es wurde kein Produkt-Lifecycle-Fix vorgenommen. Der eigene
Hintergrundtest hat34 reine Steuerungsprüfungen bestanden; sein tatsächlicher
Android-Nachweis und der SQLite-Full-Nachweis stehen noch aus. Details/Hashes im
Ledger. Gate15 weiterhin gesperrt, keine Veröffentlichung und Redmi unberührt.

**Ergänzung 04.10.2026, 16:45:** Die neue begrenzte Scanner-Zeitdiagnose besteht
die vollständige lokale JVM-Prüfung:1.051 PASS aus1.052 Fällen; genau der bereits
vorhandene optionale Live-Download-Test bleibt als Skip ausgewiesen. Debug-Lint
ohne Fehler; Quell- und geschützte Release-Ausgaben unverändert. Die erste
OS-Hintergrundtest-Vorbereitung bestand auf Android8, der Gesamtnachweis scheiterte
aber an der Singleton-Auswertung. Diese wird mit unveränderten strikten
Identitäts-/Ergebnisprüfungen korrigiert und der ganze Ablauf wiederholt. Kein
OS-Fortsetzungs-Pass und keine Scanner-Freigabe daraus abgeleitet. Details und
unveränderte Fehlbelege im Ledger; keine Signierung/Veröffentlichung.

**Aktuellster Laufzeitstand 04.10.2026, 16:18:** Die vollständige aktuelle
Android-17-Regression besteht370 von371 Fällen; der OCR-Folgetest nach Abbruch
scheitert weiterhin im Gesamtlauf. Der frühere fokussierte8/8-Pass reicht damit
nicht zur Freigabe. Ein zusätzlicher echter lokaler Firebase-SDK-Test auf Android16
bestätigt dagegen den beobachteten Rechteentzug bis zur Invalidierung der
Scanner-Hinweise (1/1,2.163s, geprüfte Bereinigung). Der frühere Lock-only-
Zustellungsfehler bleibt getrennt offen. Aktuelle Belege stehen oben im Ledger;
keine Signierung, kein Push oder Deployment, Redmi unverändert.

**Gezielter Zugriffsfix 04.10.2026:** Nach einem tatsächlich gemeldeten
Zugriffsverbot konnte der Scanner noch zwischengespeicherte Community-Hinweise
verwenden. Sechs unveränderte Regressionen reproduzieren das Problem; nach dem
engen Fix bestehen alle elf Prüfungen. Offline-Hinweise ohne Rechteentzug bleiben
erhalten. Die Android-Oberflächenprüfung und der echte SDK-Gesamtnachweis stehen
noch aus; die frühere Verzögerung beim serverseitigen Rechteentzug ist damit nicht
als gelöst erklärt. Keine Produktionsänderung oder Releasefreigabe.

Der anschließende unabhängige Review fand zusätzlich einen schnellen Wechsel
A → B → A, bei dem eine alte Aufnahme wieder Hinweise liefern konnte. Auch dieser
Fall ist mit unverändertem Test reproduziert und eng behoben: jetzt zwölf von zwölf
gezielten Fällen bestanden. Der vollständige JVM-/Lint-Lauf und Android-Prüfungen
werden gegen diesen neueren Stand erneut ausgeführt.

Der erneute vollständige JVM-Lauf besteht inzwischen 1.038 Tests, bei einem
unverändert ausgelassenen optionalen Live-Test. Lint enthält keine Fehler,
aber144 Warnungen und4 Hinweise. Das Auswertungsskript erkannte die Kategorie
„Hint“ nicht und bleibt deshalb mit seinem ursprünglichen FAIL-Beleg erhalten;
die getrennte Nachprüfung aller unveränderten Berichte und Schutzinventare ist
inzwischen bestanden. Die aktuelle
Debug-App samt Testpaket ist erfolgreich neu gebaut. Android-Laufzeittests und
Releasefreigabe sind damit ausdrücklich noch nicht abgeschlossen.

Der getrennte Android-8-Neustartnachweis ist jetzt ebenfalls bestanden: zwei
echte Prozesse setzen dieselbe gespeicherte Bereinigung ohne neuen Import fort.
Geschützte Daten und Fotos bleiben identisch, nur veraltete Preiszeilen entfallen.
Beide Prüfungen und die anschließende Entfernung der eigenen Testpakete bestehen.
Dies prüft die Wiederaufnahme beim App-Start, nicht die automatische Zustellung
durch Androids Betriebssystem-Scheduler.

Die fokussierte Android-17-Prüfung besteht sechs von acht Fällen: Kamera in allen
Display-Drehungen, drei Compose-Zugriffsfälle und zwei Abbruchkontrollen. Zwei
echte OCR-Identitätstests scheitern weiterhin. Die Messung zeigt, dass innerhalb
der unveränderten fünf Sekunden nur Namensproben fertig werden und die
Kartennummern-Proben noch gar nicht starten. Eine eng begrenzte Priorisierung
wird geprüft; kein bestandener Scanner-Gesamtnachweis wird daraus vorweggenommen.
Der vollständige fehlgeschlagene Beleg bleibt erhalten, einschließlich eines
nach Testende verbliebenen App-Prozesses. Details und Prüfsummen im Ledger.

Die gezielte OCR-Reihenfolgeänderung besteht inzwischen acht zusätzliche
JVM-Fälle und die vollständige Regression: 1.046 bestanden, ein bestehender
optionaler Live-Test ausgelassen, keine Fehler. Lint bleibt bei144 Warnungen
und4 Hinweisen ohne Fehler. Das neue Debug-Paar ist gebaut und wird separat
mit den unveränderten echten OCR-Prüfungen auf Android17 verglichen. Daraus
folgt noch keine allgemeine Geschwindigkeits-, Geräte- oder Releasefreigabe.

**Fortschreibung 04.10.2026:** Der Nutzer hat Signierung und GitHub-Push
grundsätzlich freigegeben, aber vor dem Start zusätzlich eine nachzuweisende
Speicherbegrenzung als Voraussetzung für das AAB ergänzt. Preisverläufe dürfen
nach ausdrücklicher Auswahl auf zwölf Monate begrenzt werden. Persönliche Daten,
Gastdaten und ausstehende Synchronisierung dürfen dabei nicht verloren gehen.
Die bisherige History-Optimierung begrenzt die gespeicherten Zeilen noch nicht;
ein Cloud-Vertrag für alte Marktpreisstände ist nicht vorhanden. Gate15 bleibt
bis zur Speicherabnahme und den übrigen kritischen Nachweisen gesperrt. Ein
Engineering-Push ist keine Releasefreigabe. Google Play bleibt unberührt.

**Ergänzende Speicherfreigabe:** Zwölf Monate Preisverlauf dürfen auf Karten
aller lokalen Sammlungen beschränkt werden, einschließlich Gastdaten, anderer
Konten und noch nicht synchronisierter Einträge. Für nicht gesammelte Karten
bleibt der letzte vollständige Preisstand; ältere Verläufe dürfen unwiederbringlich
entfallen. Die Bereinigung ist noch nicht in der normalen App aktiv. Der künstliche
13GiB-Fülllauf wurde auf Nutzerwunsch bewusst beendet, ohne Ergebnis; weder
Produktfehler noch PASS. Er wird nicht wiederholt. Kleinere reproduzierbare
Prüfungen müssen Begrenzung, tatsächliche Speicherfreigabe, Datenerhalt und
Fortsetzung nachweisen. Der gesicherte Engineeringbranch steht
remote auf `f76feaf`; die weitere dauerhafte Fortschrittssicherung wird getrennt
im Worktree `codex/r27-storage-maintenance` geprüft. Dort sind 38/38 Android-8-
Datenbanktests sowie 1.012 JVM-Fälle erfolgreich (ein bestehender optionaler
Test bleibt ausgelassen). Ein zusätzlich gefundener rückwärtsgehender Zeitfall
ist inzwischen mit einem echten Datenvergleich reproduziert (38 PASS/1 FAIL).
Der kleine Pausen-Fix besteht anschließend dieselben 39 Tests vollständig.
Der ergänzte Android-8-Lauf besteht jetzt 41/41 Fälle ohne Auslassungen. In einer
isolierten Datenbank mit 100.002 echten Preiszeilen schrumpft der tatsächliche
Room-Standardmodus von 18.059.264 auf 753.664 Bytes; geschützte Tabellen und Fotos
bleiben identisch. Ein separater NONE-Fall prüft die manuelle Komprimierung nur
der eigenen Testdatei. Keine pauschale Produkt-VACUUM-Funktion ist aktiviert.
Die lokale Hintergrundanbindung besteht 14 neue JVM-Fälle; der Gesamtlauf besteht
1.026 Fälle bei einem bestehenden optionalen SKIP und erfolgreicher Lint-Prüfung.
Der anschließende isolierte Android-8-Lauf besteht **44/44 Fälle ohne Auslassungen**
in 111,277s, einschließlich drei echter WorkManager-Warteschlangenfälle. Der
negative Kontrollfall bestätigt, dass ein fehlgeschlagener Vorgänger seinen
bereits blockierten Nachfolger ebenfalls scheitern lässt. Die echten pausierten
bzw. nach zwei Wiederholungen ausgeschöpften Wartungsjobs lassen dagegen einen
frisch angehängten Nachfolger vollständig fertig werden. Die drei unabhängigen
Reportdateien und der anschließende Eigentümer-Cleanup sind geprüft; die gewöhnlichen
Apps sowie Quellen und Release-26-Dateien bleiben unverändert. Dies belegt die
kontrollierte WorkManager-Engine, nicht Betriebssystemzustellung oder Fortsetzung
nach echtem Prozessneustart. Die genauen Belege stehen im Release-27-Ledger.
Auch der zuvor an einer zu engen EXPLAIN-Diagnosegrenze gescheiterte Android-8-
Jahrestest besteht jetzt vollständig: ein Test, 13 Messpunkte, 365 Diagrammpunkte,
vollständige Modelle und 15 unveränderte Tabelleninventare; der echte SQLite-Plan
enthält 113 Zeilen. Frühere Fehlbelege bleiben erhalten. API37-Kamera/OCR,
Neustart-/Low-Disk- und weitere kritische Release-Nachweise bleiben offen.
Gate15 bleibt gesperrt; diese Einzelnachweise sind keine vollständige Speicher-
oder Releaseabnahme, keine Signierung und keine Veröffentlichung.

| Ebene | Befund vor diesem Dokumentations-Commit |
| --- | --- |
| GitHub Standardbranch | `master`, `9bbe7bc`, App `1.0-beta`, code 16 |
| GitHub Entwicklungsbranch | `codex/ui-v3`, `77b76cf`, App `1.0-pre-beta`, code 19 |
| Lokaler Commit | `codex/ui-v3`, `096aa35`, App `1.0-pre-beta.1`, code 21; vier Code-Commits vor Remote |
| Lokaler Working Tree | code **27**, `1.0-closed-alpha.6`; zahlreiche schon vorher geänderte und unversionierte Quellen/Tests |
| Google Play / signiertes Archiv | code **26**, `1.0-closed-alpha.5`; AAB vom 23.09.2026, unverändert |

Der damalige Doku-Push übernahm keine der vier noch lokalen Code-Commits und keine
unveröffentlichten Produktdateien. Der spätere Engineeringcheckpoint sichert den
lokalen Entwicklungsstand, nicht rückwirkend die Release-26-Quellen;
insbesondere lässt sich das v26-AAB keinem vollständigen sauberen Commit zuordnen.

## Version, Track und Release

- Package/Namespace: `com.dominik.cardvault`; ein Modul `:app`, keine Flavors.
- Lokales SDK: min 26, compile/target 37; Release nur `arm64-v8a`.
- Phase: **geschlossene Alpha**, kein belegter öffentlicher Produktionsrelease.
- **Play live am 01.10.2026:** Version 26 aktiv im „Geschlossener Test – Alpha“
  (verfügbar, vollständiger Roll-out, angezeigte letzte Änderung 23.09.2026)
  sowie im „Interner Test“ (verfügbar, vollständiger Roll-out, 24.09.2026).
- Die angezeigten Bundles 22–25 sind inaktiv. Kein Bundle 27 in der aktuellen
  Releaseübersicht. Keine Testerzahlen als Produkt- oder Freigabegarantie.
- Beim Auditbeginn jüngster GitHub-Release: `v1.0.0-pre-beta`, 01.09.2026,
  APK Build 17; Tag auf `1be7adc`. Der „Latest“-Badge zeigte damals auf rc7.
- Nachfolgend separat freigegeben und am 01.10.2026 ergänzt:
  [Release-26-Binärarchiv](https://github.com/nandedomi/DomiDex/releases/tag/archive/v26-1.0-closed-alpha.5),
  unverändertes AAB, Mapping, partielle Symbole und Prüfsummen. Der Archiv-Tag
  zeigt auf den Dokumentationscommit `53e7d14`, **nicht** auf rekonstruierte
  Buildquellen. Die vier Asset-Digests wurden nach dem Upload abgeglichen.
- Historische interne Builds sind als Archiv/Vorabversion gekennzeichnet;
  kein Alpha-/RC-Build wird für einen „Latest“-Badge zum stabilen Release erklärt.
- Der abgebrochene lokale v27-Signierlauf vom 25.09.2026 hat kein fertiges
  Archiv `releases/v27` erzeugt. Das vorhandene `app-release.aab` ist weiterhin v26.

## R8 und Native Debug Symbols

R8 ist im v26-Artefakt vorhanden; Play zeigt `mapping.txt` (104 MB) zugeordnet.
Aktuelle lokale Releasekonfiguration: Minify an, Code-Shrinking/Optimierung per
Keep-Regeln aus, Ressourcen-Shrinking aus. Diese Konfiguration war im damaligen
Audit-HEAD `096aa35` noch nicht vollständig enthalten und ist inzwischen im lokalen
Engineeringcheckpoint gesichert. Nicht auf den älteren GitHub-Checkout übertragen.

Play zeigt außerdem `native-debug-symbols.zip` (512 KB) bei **Version 26**.
Das lokal erneut verifizierte Archiv deckt **1 von 7** nativen Bibliotheken ab:
`libc++_shared.so`. Die sechs übrigen Herstellerbibliotheken liefern keine passenden
vollständigen Originalsymbole im vorliegenden Bestand. Keine künstlichen Symbole
und keine Behauptung „alle Warnungen weg“. [Details und Hashes](BUILD_AND_RELEASE.md)

## Funktionsstatus und bekannte Grenzen

| Bereich | Implementiert im geprüften lokalen Stand | Einschränkung / nächste Prüfung |
| --- | --- | --- |
| Scanner | CameraX, Latin-OCR, OpenCV, Bildvergleich, begrenzte Queue, drei Entscheidungszustände, manuelle Bestätigung/Korrektur | Ältere Karten/Promos, Glanz, Kaltstart/Netzwerk und echte Gerätelatenz weiter messen; keine generelle Geschwindigkeits-/Trefferquote bewiesen |
| Sammlung / Binder | Room, Varianten/Mengen, Set-Slots, private Seiten/Platzierungen/Cover/Holos, Backup V2 | Kein funktionales Nachtesten am Gerät in diesem Audit; manche Erweiterungen nur lokal |
| Teilen | Owner-Sync und Linkverwaltung, lokaler Android-Linkleser; externer Webdienst | Linkbesitz erlaubt Lesen; Backend-/App-Link-/Zwei-Geräte-Endtest nicht durch diese Dokumentation ersetzt |
| Preise | Exakte Cardmarket-ID, Druckfeld, Trend, Cache; neue explizite Alternative im lokalen Stand | Fehlende Quellbelege/Trendwerte bleiben; neue Alternative/Sync nicht als veröffentlicht behaupten |
| Portfolio | Mengen × verfügbare Werte, Historie heutiger Mengen | Keine Rendite-/Transaktionshistorie; lokaler UI-Text zu ausschließlich Trend noch widersprüchlich |
| Favoriten | Lokale Dashboard-Fokuskarte | Keine separate vollständige Favoritenfunktion nachgewiesen |
| Grading | Historische Models/Tabellen und nicht aktiv erreichbare Oberfläche | Kein eigenes Analyse-/Grading-System; UI-Web-Ankündigung ist kein bestätigtes nächstes Feature |

Preis-Snapshot vom **25.09.2026**, lokal am **01.10.2026** nach den
Druckidentitätskorrekturen erneut ausgewertet; keine heutige Live-Vollprüfung:
144 physische deutsche Index-Sets, 19.463 Karten einschließlich 20 lokaler
Ergänzungen und **27.349 kanonische Binder-Druckplätze**. Davon sind 17.903 exakt
einem deklarierten Produkt zugeordnet: **17.867** unabhängig verifiziert bepreist,
29 ohne passenden Trendwert für die Druckform und sieben mit Preis, aber ohne
vollständige unabhängige Quelldetails. Weitere 9.445 Plätze sind nicht ausreichend
quellenverifiziert; bei einem fehlt der deklarierte Produktdatensatz im Snapshot.
Fehlende Quellenverifikation ist nicht gleichbedeutend mit fehlendem Marktpreis.
Vollständige Abdeckung ist nicht belegt; Datenstand und getrennte Nenner bleiben
im [Preis-Replay-Nachweis](RELEASE_27.md) dokumentiert.

## Cloud und Datenschutz

- Google/Firebase Authentication ist implementiert; Google-verknüpfte UID
  bestimmt den Sammlungs-Owner. Claim-/serverseitige Freigabe ist getrennt vom Login.
- Firestore: ownergebundene Daten, Revisions-/Mutationserkennung, Tombstones und
  Konfliktbehandlung; Room-Daten bleiben lokal lesbar.
- Collection verwendet Live-Listener plus Outbox; private Binder verwenden
  periodische/gezielte Läufe. **Kein garantiert verzögerungsfreier Sync**.
- Functions enthält einen Scan-Knowledge-Aggregationstrigger. Implementierung
  vorhanden, aktueller Deploy-/Quota-/Retention-Zustand in diesem Audit nicht geprüft.
- App Check: inzwischen lokal implementierte Debug-/Release-Provider und
  begrenzte REST-Tokenweitergabe, standardmäßig ausgeschaltet. Console-Enforcement
  nicht nachgewiesen; [Vertrag und offene Aktivierung](RELEASE_27_APP_CHECK.md).
- ScanLab-Fotos bleiben manuell pro Aufnahme bestätigt; bildfreies Knowledge hat
  separaten Opt-in. Katalogbildbeiträge sind ein dritter Pfad mit Originalbytes.
- ZIP-Backups sind **nicht verschlüsselt**. Androids Kontolöschdialog öffnet eine
  Anfrage-Mail; die separate Web-Löschfunktion ist kein Android-Client-Delete.

[Architektur](ARCHITECTURE.md) · [Firebase](../FIREBASE_SECURITY.md) ·
[Security / offene Prüfungen](security-entitlements.md)

## Tests, CI und offene Freigaben

Baseline des ursprünglichen Audits am 01.10.2026: JVM **629 bestanden / 1 optional übersprungen**, Node
**18/18**, native ELF-/Lizenzverifier **27/27**, lokale Firebase-Regeltests
**18/18**. Details und historische Emulatorläufe: [TESTING](TESTING.md).
Spätere lokale Tests und ihre jeweiligen Grenzen: [Release-27-Ledger](RELEASE_27.md).

Keine GitHub Actions konfiguriert; API meldet 0 Workflow-Runs. Aktuelle
Gradle-Dependency-Locks und Gradle-Verification-Metadaten fehlen; npm-Lockfiles
sind vorhanden. Ein Testpass ist weder eine
Produktionsfreigabe noch ein Beweis für alle Karten, alle Geräte oder alle Nutzer.

Offen bleiben insbesondere vollständige Quellcode-Provenienz, Regressionen mit
repräsentativem Bildkorpus/echten Geräten, Zwei-Geräte-/Revocation-Tests, fehlende
Herstellersymbole, Aufbewahrung/Löschung, Rechte an Assets/Daten und abgestimmte
Datenschutzhinweise. Der ursprüngliche Dokumentationsaudit erledigte diese Aufgaben
nicht durch Codeänderungen; spätere Teilkorrekturen und verbleibende Lücken stehen
im Ledger und sind kein pauschaler Abschluss dieser Prüfziele.

## Roadmap und Finanzierung

Version 27 bleibt ein **zukünftiger technischer Meilenstein**; lokale Konfiguration
27 bedeutet nicht veröffentlichte Version 27. [Roadmap](ROADMAP.md) enthält
Prüfziele, keine Terminzusagen.

Monetarisierung wird strategisch neu bewertet, ohne beschlossenes neues Modell.
Historische Aussagen zu Werbung/Testkosten bleiben als datierte Aussagen erhalten.
Es gibt durch diesen Audit keine neuen Kosten, Abos, Credits oder geänderte
Testerbedingungen. [Dokumentationsabgleich](REPOSITORY_AUDIT_2026-10-01.md)
