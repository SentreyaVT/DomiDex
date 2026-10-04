# DomiDex – Entwicklerzugang und Release 27

**Stand: 04.10.2026 · geschlossene Alpha.** Dieses private Repository dient der
Entwicklung und nachvollziehbaren Übergabe, nicht nur der Produktpräsentation.

## Hier mit R27 beginnen

**Der aktuelle R27-Quellcode liegt auf
[`codex/release-27-hardening`](https://github.com/nandedomi/DomiDex/tree/codex/release-27-hardening).**
Der Code auf `master` ist älter. Diese Startseite ist ein aktualisierter Wegweiser,
kein ungeprüfter Merge der divergierten Quellcodehistorien.

```sh
git clone --branch codex/release-27-hardening https://github.com/nandedomi/DomiDex.git
git clone --branch codex/release-27-hardening https://github.com/nandedomi/DomiDex-Web.git
```

- [R27-Entwicklerübergabe: Setup, Architektur, Tests und offene Aufgaben](https://github.com/nandedomi/DomiDex/blob/codex/release-27-hardening/docs/DEVELOPER_HANDOFF_R27.md)
- [Build- und Release-Anleitung](https://github.com/nandedomi/DomiDex/blob/codex/release-27-hardening/docs/BUILD_AND_RELEASE.md)
- [Changelog](https://github.com/nandedomi/DomiDex/blob/codex/release-27-hardening/CHANGELOG.md)
- [Architektur](https://github.com/nandedomi/DomiDex/blob/codex/release-27-hardening/docs/ARCHITECTURE.md) · [Sync](https://github.com/nandedomi/DomiDex/blob/codex/release-27-hardening/docs/SYNC.md) · [Scanner](https://github.com/nandedomi/DomiDex/blob/codex/release-27-hardening/docs/scanner-architecture.md)
- [Engineering-Ledger / Testergebnisse und Grenzen](https://github.com/nandedomi/DomiDex/blob/codex/release-27-hardening/docs/RELEASE_27.md)
- [Web-Repository](https://github.com/nandedomi/DomiDex-Web/tree/codex/release-27-hardening)

## Release-Dateien

[**Release27: signiertes AAB, R8-Mapping, partielle Native-Symbole und Prüfsummen**](https://github.com/nandedomi/DomiDex/releases/tag/archive/v27-1.0-closed-alpha.6)

Code27 / `1.0-closed-alpha.6`, Paket `com.dominik.cardvault`. Der bestehende Build
ist im internen Test und in der geschlossenen Alpha aktiv; kein öffentlicher
Produktionsrollout. GitHub archiviert die bestehenden Dateien, erzeugt keinen neuen
Build und veröffentlicht nichts zusätzlich auf Google Play. Ein AAB ist keine APK.

Der Archiv-Tag allein liefert keine vollständig reproduzierbare Buildumgebung;
lokale Konfiguration, Toolchain und Signing bleiben weitere Eingaben. Native-Symbole sind **1/7**,
nicht vollständig. Einzelheiten stehen in den Release-Hinweisen.
Das [Release26-Archiv](https://github.com/nandedomi/DomiDex/releases/tag/archive/v26-1.0-closed-alpha.5)
und ältere Testreleases bleiben unverändert erhalten.

## Wichtige Arbeitsgrenzen

Full-Sync bleibt aktiv, Delta deaktiviert. Offene Gates sind kein PASS durch
Veröffentlichung: insbesondere ein neu nachgewiesener Web-Collection-Scroll-Crash (React185),
zwei Binder-Verknüpfungen sowie Entzugs-/Hardware-/Betriebsnachweise.
Der Developer-Handoff beschreibt die nächsten Arbeitsschritte.

Lokale SDK-/Firebase-Konfiguration separat beim Betreiber beziehen. Keine
Signing-Schlüssel, Service-Accounts, Tokens oder persönlichen Daten einchecken.
Keine produktiven Deployments oder Signierung allein durch Checkout/Pull auslösen.
Ältere Dokumente auf `master` sind historisch; für aktuelle Aussagen die oben
verlinkten R27-Dokumente verwenden.

[Website](https://domidex.web.app/) · [Discord](https://discord.gg/fc9XgwtVTE)
