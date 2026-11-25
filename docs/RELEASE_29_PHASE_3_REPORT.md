# DomiDex Release 29 – Phase 3 Report

**Date:** 23 November 2026  
**Scope:** local investigation, verification and test preparation only. This is
not a deployment, Play release, Firebase data access, or release approval.

## 1. Baseline verified

- Android branch: `codex/release-29-hardening` at local base `78c1348`.
- Web branch: `codex/release-29-hardening` at local base `d4d687e`.
- Existing user-owned untracked directories remain excluded:
  `scripts/verification/hardware-proof/` and `../DomiDex-Web/backend/market-data/`.
- No Firebase deployment, production-data read/write, Hosting deployment, Google
  Play action, Git push, or user/tester-data change was performed.

## 2. Cardmarket data-source assessment

### Current DLR result

| Result | Count |
| --- | ---: |
| DLR cards / images present | 141 / 141 |
| Variant-safe mapped Cardmarket products | 62 |
| Verified mappings with a trend price | 62 |
| Needs review | 69 |
| Unmatched | 10 |
| Further Phase-3 mappings added | 0 |

The 79 unresolved printings remain unchanged. The local audit continues to show
47 ambiguous fingerprints and 32 without an exact fingerprint. Product order,
similar images, name-only matches and price presence remain rejected evidence.

### Official/authorized sources evaluated

| Source | Availability / access | Useful identity data | Constraints / assessment |
| --- | --- | --- | --- |
| Cardmarket API 2.0 – product, expansion singles and product search | OAuth access; Cardmarket states that it is currently not accepting new API-access applications. Documentation also says API access is restricted to professional sellers and manual approval. | `idProduct`, expansion, localized name, collector number where available, rarity; detailed product responses include price guides. | Best technical source if Cardmarket approves the intended DomiDex use in writing. The product model does not expose a separate canonical finish field, so variant/finish still needs explicit reconciliation evidence. |
| Approved third-party Cardmarket app | Commercial-app approval plus user OAuth authorisation. | May access the approved user's authorised resources. | Suitable only after Cardmarket approval and a privacy/contract review. Never embed or share account credentials in Android or Web clients. |
| Cardmarket own-stock export | API 2.0 export of the account's own inventory; once per hour/game, download valid for 24 hours. | The seller's own article/product records. | Not a complete Pokémon product catalogue; cannot establish mappings for every DLR card. |
| Deprecated Product List / Price Guide file endpoints | Documented as deprecated. Price-guide file access is restricted to widget, approved third-party, or qualifying professional/powerseller dedicated apps. | Bulk product/price information, subject to entitlement. | Not an acceptable long-term integration without a current explicit entitlement and data-use approval. |
| Existing public catalogue evidence used in Phase 2 | Locally reproducible read-only evidence. | Expansion/product candidates and prices. | Insufficient for the remaining 79 because its records lack the required print-specific collector-number/finish proof. It must not be promoted to an authoritative mapping feed. |

### Contract and legal/operational constraints

- Cardmarket's current help page says it is not accepting new API access
  applications.
- Its terms say API use is for the user's own content; displaying cards and
  prices on an external site requires prior written agreement. This needs written
  confirmation for DomiDex before product/price data is used as a live catalogue
  feed.
- Credentials are account authority and must stay server-side. They must not be
  committed, shipped in the AAB, stored in Firebase client configuration, or
  shared with general third-party software.
- The 2026 API base domain is `apiv2.cardmarket.com`; no old API domain should be
  added to new code.

### Decision

**No new Cardmarket integration is approved or implemented in this phase.** The
P0 remains until one of the following is available:

1. Cardmarket written approval and an authorised API/app entitlement that permits
   DomiDex's intended product/price presentation, plus a product response export
   containing sufficient number/variant evidence; or
2. an authorised, documented, print-specific data feed that maps each DLR
   printing to one Cardmarket product ID.

## 3. Two-client and offline synchronization

### What is already locally evidenced

The Android integration suite contains a real local Firebase Auth/Firestore
emulator setup using two independently named Firebase applications, Auth/Firestore
instances and Room databases for the same synthetic account. It is not a mock and
does not use the default Firebase app or a production project.

| Scenario | Local status | Evidence / limitation |
| --- | --- | --- |
| Add/acknowledge a collection mutation | Covered by local SDK worker tests | Real local emulator path; not a current two-physical-device test. |
| Same user on two isolated clients | Partly covered | `twoClientPrivateBinderStaleEditConvergesWithoutRemoteRewrite` has two independent SDK clients and Room files. |
| Stale simultaneous private-binder edit | Covered locally | Revision conflict makes the stale client import the remote winner; it must not write an invented revision 3. |
| Duplicate suppression / idempotent rerun | Covered locally | Full cycles preserve the remote payload and do not create an extra mutation. |
| Offline binder edit, Room close/reopen, reconnect | Covered locally | `offlinePrivateMutationSurvivesRoomReopenAndCompletesFullPull` preserves the outbox through a file-backed Room reopen and acknowledges only after local network restoration. |
| Large binder history / bounded FULL continuation | Covered locally | 1,001 synthetic binders are pulled across bounded continuation passes without changing existing remote rows. |
| Actual two separate Android devices | Not tested in Phase 3 | Requires two controlled device/AVD installations using the same isolated emulator and the current source build. |
| Actual device reboot / OS WorkManager delivery | Not tested in Phase 3 | Existing proofs cover Room close/reopen and worker behaviour, not device reboot scheduling. |

### Important qualification

The historical broad local session harness is currently **17/18, RED**. Its
remaining failing case concerns a lock-only listener/permission-denial
characterisation in the local emulator. It must not be called a complete green
multi-client gate. No production conclusion is derived from it.

The Release-29 design remains conservative **FULL synchronization**. Delta sync
is not enabled.

### Reproducible physical-device matrix (still to run)

Run only against local Auth/Firestore emulators, two fresh debug installations,
one synthetic account and fresh owned Room databases:

1. Device A creates a normal and a duplicate card; Device B receives exactly the
   expected records and no extra row after a second full sync.
2. Device A creates/moves a private-binder placement; Device B receives the
   same binder, placement, holo settings and revision.
3. Both devices edit the same binder from the same revision; verify the explicit
   conflict outcome and that the stale device cannot overwrite the winner.
4. Disable the local emulator path for Device A, add/edit a card and binder,
   close the app, then reboot/reopen the device. Verify the durable outbox before
   reconnecting.
5. Restore the local path and verify one acknowledgement, no data loss, no
   duplicate mutation and idempotent next sync on both devices.
6. Repeat with a temporarily unreachable emulator and then a valid recovery;
   capture worker/outbox state and remote revisions after every step.

## 4. Scanner hardware-quality plan

### Current live-scanner capability

- **Present:** CameraX preview, real-time lightweight frame-quality/card-boundary
  analysis, rear-camera preference, torch control, manual capture and opt-in
  automatic capture.
- **Automatic capture:** disabled at session start; when enabled it requires a
  stable eligible rectangular card frame over consecutive analysis frames and a
  changed-card fingerprint before rearming. The preview analysis is deliberately
  lightweight (about seven quality checks per second).
- **After capture:** one full-resolution still is sent to the bounded local
  OpenCV/ML Kit processing queue for normalization, OCR and candidate ranking.
- **Not present:** persistent low-resolution OCR or final card recognition from
  every preview frame. A held card can trigger an automatic *photo*, but the
  final identity is not shown before that photo is processed.

This is therefore an **automatic-capture scanner**, not yet a fully live
continuous-recognition scanner.

### Hardware matrix to execute on representative physical cameras

| Group | Variants | Measurements / acceptance evidence |
| --- | --- | --- |
| Card type | modern normal, Holo, Reverse Holo, older border, promo, full art | Capture eligibility, candidate rank, correct set/number, manual-fallback rate. |
| Lighting | diffuse indoor, bright side light, low light, direct glare | Guidance state, glare rejection, false auto-captures, torch usefulness. |
| Geometry | near/far, parallel, 5–15° tilt, portrait/landscape orientation | Four-corner stability, crop correctness, set-number readability. |
| Scene complexity | one card, partial card, multiple cards, textured background | No auto-capture for ambiguous/multiple-card scenes; no wrong-card acceptance. |
| Motion | stable hand, focus hunting, slow replacement with another card | Auto-capture only once per card; rearm only after removal/new-card evidence. |
| Device class | Redmi/Xiaomi, current Pixel-class emulator only for UI, a second physical Android model | Camera selection, focus/torch availability, capture-to-result time, crash/ANR evidence. |

For every physical run, record device/model/API, card identity, lighting,
distance, auto/manual mode, capture latency, analysis latency, top candidates,
manual correction and whether any image leaves the device. Do not upload a scan
unless the tester explicitly uses the existing ScanLab consent flow.

## 5. Regression results – Phase 3

| Check | Result |
| --- | --- |
| Android complete unit suite | **1,080 / 1,080 passed** |
| Android lint | **0 errors**; 165 existing warnings and 4 hints retained |
| Android debug APK | Built successfully |
| Web complete suite | **52 files / 779 tests passed** |
| Web typecheck | Passed |
| Web production build | Passed; existing non-blocking 500 kB chunk advisory retained |
| Firestore Emulator rules suite | **13 / 13 passed** |
| DLR catalogue audit | 141/141 cards and images; 62 verified price mappings; 0 duplicate verified IDs |

## 6. Release-readiness classification

### Successfully completed

- Phase-2 Firestore hardening remains locally tested.
- Full Android and Web regression repetitions are green.
- DLR audit is reproducible and continues to reject unsafe mappings.
- Scanner capability and hardware acceptance plan are documented.

### Technically prepared

- Isolated local Firebase emulator path for two independent Android SDK clients.
- Offline outbox, full-pull continuation, stale-binder conflict and idempotency
  test scaffolding.
- Physical two-device test matrix and scanner field matrix.

### Not yet tested

- The exact current R29 source on two separately installed Android devices.
- Device reboot plus real OS WorkManager delivery/reconnect.
- Physical-camera scanner matrix across lighting, foil, legacy and multi-card
  conditions.

### Externally dependent

- Cardmarket written API/data-use approval and an authorised source with enough
  print-specific information for the remaining 79 DLR cards.

### Release-blocking

1. **P0:** 79 DLR printings are not variant-safely mapped.
2. **P0:** Local Web source has not been deployed or rechecked live (deployment is
   outside this phase).
3. **P1 gate:** two-physical-device and full hardware scanner evidence are not
   complete.
4. **P1 gate:** the broad local session harness's 17/18 status must be understood
   or corrected before it can serve as a complete sync-release gate.
5. **Release ordering:** reviewed Firestore rules must be deployed before clients
   that write `createdAtClient`; this report does not authorise such a deployment.

## 7. Recommended next step

Obtain written Cardmarket approval for DomiDex's intended use and an authorised
product feed/API entitlement. In parallel, create two isolated debug
installations against the local Firebase emulator and execute the six-step
physical-device matrix above. Only after those two gates are resolved should a
new Release Candidate be considered.

## Sources consulted

- Cardmarket Help – API status: <https://help.cardmarket.com/de/cardmarket-api>
- Cardmarket API authentication/access: <https://api.cardmarket.com/ws/documentation/API%3AAuth_Overview>
- Cardmarket API 2.0 product entity: <https://api.cardmarket.com/ws/documentation/API_2.0%3AEntities%3AProduct>
- Cardmarket API 2.0 marketplace information: <https://api.cardmarket.com/ws/documentation/API_2.0%3AMarket_Place_Information>
- Cardmarket terms, API use: <https://help.cardmarket.com/en/general-terms-and-conditions>
