# DomiDex Release 29 – working status

**Status:** development only; not a release approval and not a deployment record.

## Scope and safety

- Android branch: `codex/release-29-hardening`
- Web branch: `codex/release-29-hardening`
- No Firebase data, Firestore rules, Functions, Hosting, Google Play test track or
  production system was changed as part of this work.
- Existing unversioned work remains excluded: `scripts/verification/hardware-proof/`
  in Android and `backend/market-data/` in the Web repository.

## DLR/Cardmarket evidence (23 November 2026)

The reproducible, read-only command is:

```powershell
node scripts/diagnostics/audit-dlr-cardmarket.mjs |
  Set-Content -Encoding utf8 "$env:TEMP\domidex-dlr-cardmarket-phase2.json"
```

It downloads the current TCGdex German `me06` set and the public Cardmarket
product/price catalogues. The report covers all 141 DLR slots.

| Measure | Result |
| --- | ---: |
| Present cards | 141 / 141 |
| Cards with image | 141 / 141 |
| Variant-safe verified Cardmarket products | 62 |
| Verified products with a trend price | 62 |
| Needs review | 69 |
| Unmatched | 10 |
| Duplicate verified product IDs | 0 |

The generated JSON is the card-by-card Phase-2 evidence record. Every one of
the 79 unresolved printings contains its German card identity, English
name/attack fingerprint where available, every matching product candidate in
Cardmarket expansion `6704`, and an explicit non-acceptance reason. It is
therefore possible to review each decision without turning product order into a
false mapping source.

| Open-evidence result | Count |
| --- | ---: |
| Exactly one additional safe fingerprint candidate | 0 |
| More than one exact fingerprint candidate | 47 |
| No exact fingerprint candidate | 32 |

The public Cardmarket catalogue offers all 141 products in the expansion but
does not contain printed collector number or finish fields. Its ordinal product
position is retained in the report as *rejected evidence only* and is never
used for price linkage.

The 62 verified entries consist of:

1. Entries whose declared TCGdex Cardmarket root also agrees with the expected
   Cardmarket expansion and identity evidence.
2. Thirteen entries without a declared TCGdex root for which exactly one product
   in Cardmarket expansion `6704` has the same English card name and complete
   attack-name fingerprint. These use the explicit provenance label
   `Cardmarket expansion + unique English name/complete attack fingerprint`.

The remaining 69 review entries and 10 unmatched entries intentionally have no
fallback product ID. Product order, German display names, a similar card image,
or a price alone are not sufficient proof. This keeps an incorrect market price
out of Android, the Web app, and shared binder values.

## Android / Web parity

- The same 62 DLR mappings exist in
  `app/src/main/java/com/dominik/cardvault/data/catalog/DeltaHerrschaftCardmarketMapping.kt`
  and `../DomiDex-Web/lib/dlr-cardmarket-mapping.ts`.
- Both map the normal/standard printing only. Reverse, stamped and other special
  printings remain unpriced unless they have their own exact source evidence.
- The Web layer now accepts an independently reviewed mapping when the upstream
  root is absent, but rejects it if a present upstream root contradicts it.
- New collection entries also carry an optional client creation timestamp so the
  Binder can truthfully sort by “last added”. The matching Android and Web
  `firestore.rules` files allow that field while accepting legacy entries without
  it. A legacy entry may add a positive creation timestamp exactly once; once
  present it is immutable, preventing a later client from rewriting the
  cross-device “last added” order. **The rules must be deployed before, or
  atomically with, any client that writes this field**; otherwise a strict
  deployed `hasOnly` rule could reject a new collection write.

## Live versus source Web app

Read-only checks on 23 November 2026 show that `domidex.web.app` still serves
the legacy DLR display name `Delta-Herrschaft` for `/api/sets/me06` and does not
expose a Cardmarket product for `/api/cards/me06-055`. The local source contains
the correction (`Mega-Entwicklung – Delta-Herrschaft` and the reviewed Nidoran
mapping), but it has not been deployed. Deployment remains an explicit separate
approval.

## Checks executed on the current working tree

- Android targeted catalog and DLR mapping tests: passed.
- Android complete unit suite with the complete catalogue snapshot: **1,080 / 1,080
  passed**, including `AllBinderPriceAuditTest`.
- Android debug lint and debug APK build: zero lint errors; 165 pre-existing
  warnings and 4 hints remain and were not suppressed.
- The final DLR audit command completed against current public sources: 141/141
  cards and images present, 62 verified product/trend-price pairs, zero duplicate
  verified IDs.
- Web DLR tests and the complete Web suite: **52 files / 779 tests passed**.
- Web typecheck and production build: passed. The build retains the existing
  non-blocking 500 kB chunk-size advisory.
- Firestore emulator security suite after the timestamp hardening: **13 / 13
  passed**. It includes owner isolation, claim revocation, schema rejection,
  immutable identifiers, tombstones, private shares, scanner/admin boundaries,
  owner-reassignment/schema-pollution rejection, and positive/invalid/rewritten
  creation timestamps.

The above evidence is local only. It does not constitute an approval to deploy
rules, host the Web app, upload an AAB, or publish a release.

## Phase 6 local follow-up (25 November 2026)

An authorised, intentionally untracked local `app/google-services.json` is now
available for `com.dominik.cardvault`; no Firebase identity or placeholder was
fabricated. Current Gradle evidence is **1,088 tests with 0 failures/errors and
2 documented opt-in skips**, debug lint passed with no error-level SARIF finding,
and a debug APK assembled locally. The required physical
scanner/two-device/offline/WorkManager cases are prepared in
`RELEASE_29_PHASE_6_HARDWARE_MATRIX.md` and are all explicitly not executed.

For the separately experimental Web `backend/market-data/` area, Phase 6 fixed
four unambiguous runtime-source lint findings and its isolated Node suite passed
174/174. The remaining 205 full-lint findings are test-only and were neither
suppressed nor mass-rewritten. They remain a release-gate follow-up; the prior
Web application test/typecheck/build evidence is unaffected.

## Phase 7 virtual readiness and prerequisite review (26 November 2026)

Phase 7 retained the Phase-6 Gradle and Web evidence rather than rerunning it. Android Studio is installed locally and the existing `Medium_Phone_API_37.0` AVD cold-booted successfully; `adb` reported a fully booted Android 17 `sdk_gphone64_x86_64` emulator. No app, instrumentation, Firebase, scanner or camera test was run on it, and no physical device was connected. This proves only that a virtual non-production test target is available. Physical scanner, two-device, offline/recovery and reboot/WorkManager evidence remains unexecuted in the Phase-6 matrix. No webcam or camera was enabled; explicit approval is required before camera activation.

The 79 DLR entries remain unchanged (69 review, 10 unmatched): the existing audit has 47 ambiguous exact-fingerprint cases, 32 cases without an exact candidate, and no additional unique safe candidate. The Cardmarket source still lacks the collector-number/finish evidence required to promote them. Likewise, the Phase-6 four runtime market-data lint corrections remain the only related code change; the remaining 205 full-lint findings are the pre-existing test-harness backlog.

The historical R27 Play receipt was reviewed read-only and does not establish an R29 bundle or current Play status. R29 still needs authorised release signing and exact-bundle evidence, physical/non-production validation, resolution or approved handling of the DLR P0 gap, and separately approved Firestore-rule release ordering before any future Play action.


## Phase 8 Android-17 offline emulator run (26 November 2026)

The existing debug APK was installed on the isolated `Medium_Phone_API_37.0` Android-17 emulator. The test kept airplane mode on with Wi-Fi and mobile data off because the local Firebase configuration is not independently proven non-production. Local-only onboarding, overview, Binder, card-search/filter offline/empty states, collection, settings, Back navigation, background/resume and force-stop restart all rendered without a crash or ANR. Cold starts measured 3,560 ms and 3,677 ms; hot resume measured 119 ms. The pending `CardmarketSyncWorker` remained network-blocked and was not run.

No account, remote data, card, binder or price data was created. Camera permission stayed denied and the scanner/camera was not opened. Online/reconnection, Firebase/auth/sync, populated-card/variant/price flows, physical scanner work, two-device and reboot/WorkManager recovery remain untested or blocked; see `RELEASE_29_PHASE_8_REPORT.md`.


## Preisidentitäts-Audit (26. November 2026)

Das neue RELEASE_29_PRICE_IDENTITY_AUDIT.md inventarisiert 92 lokale Web-Katalogdateien mit 14.713 Karten (85 physische Sets / 13.615 Karten sowie 7 Pocket-Sets / 1.098 Karten). Diese JSON-Dateien enthalten keine Cardmarket-Produktkennungen und ihre fachlichen Variantenfelder sind kein Finish-Beweis. Der erneut ausgeführte, nur lesende DLR-Audit bestätigt 141/141 Karten/Bilder, 62 verifizierte Normal/Standard-Identitäten und Trendpreise sowie weiterhin 79 unbepreiste Drucke: 47 haben mehrere Fingerprint-Kandidaten, 32 keinen. Kein neuer Kandidat war eindeutig genug für eine Zuordnung. Android und Web enthalten dieselben 62 bestätigten Paare und zehn UNMATCHED-Nummern; die gezielten Mapping-Tests bestanden (Android 2/2, Web 5/5).

## Current blockers before Release 29

1. **P0:** 79 DLR printings do not yet have an unambiguous Cardmarket identity
   (69 review + 10 unmatched). A trusted card-number/finish-aware supplier
   export, Cardmarket API entitlement, or individually verified product evidence
   is required; guessing is prohibited.
2. **P0:** the live Web deployment is behind the local verified source and must
   not be described as aligned until a separately approved deployment has been
   made and rechecked.
3. **P1:** the scanner currently performs live quality/card-boundary gating but
   not persistent low-resolution OCR/candidate recognition from the preview
   stream. The requested live candidate panel needs a separate performance and
   hardware validation pass.
4. **P1:** the full two-device/offline/conflict sync matrix still needs current
   evidence. The conservative full-sync path remains the Release 29 baseline.
5. **P1 release ordering:** deploy the reviewed Firestore rules before exposing
   the timestamp-writing Android/Web clients. This is a release-ordering
   requirement, not authorization to deploy.

## Phase 9 blocker remediation evidence (26 November 2026)

`RELEASE_29_PHASE_9_BLOCKER_EVIDENCE.md` records the current source-backed
closure path for each remaining class of blocker. The Web test harness backlog
is no longer a blocker: its former 205 test-only diagnostics were corrected
without lint suppressions or production-path changes. The resulting local checks
passed: market-data Node tests 174/174, complete Web suite 52 files/779 tests,
full lint with zero diagnostics, typecheck and production build.

The DLR pricing blocker cannot truthfully be marked closed from the public
export: the official Pokémon checklist confirms printed identities but not
Cardmarket product identities; Cardmarket's documented Product API is capable
of supplying the needed number/rarity/image/product evidence but requires an
approved professional-seller API application. Public page access encountered a
Cloudflare verification challenge and was not bypassed. The only safe closure is
authorised API access or manual, reproducibly recorded product-page evidence for
each of the 79 prints. No price or product ID was guessed.

Physical scanner/two-device/online/recovery proof likewise needs actual
designated devices, non-production data/environment and explicit camera
permission. The Android-17 offline emulator evidence does not replace it.

