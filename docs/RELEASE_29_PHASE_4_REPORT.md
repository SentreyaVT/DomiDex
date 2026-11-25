# DomiDex Release 29 – Phase 4 Report

**Date:** 23 November 2026  
**Scope:** local architecture review, local regression checks and reproducible
test preparation only. No Firebase deployment, production-data access/change,
Hosting deployment, Google Play action, Git push, tester change or publication
was performed.

## 1. Baseline and boundaries

- Android branch: `codex/release-29-hardening`, local base `78c1348`.
- The existing Phase-2/Phase-3 working-tree changes remain uncommitted and were
  not reset or overwritten.
- User-owned directories remain excluded: `scripts/verification/hardware-proof/`
  and `../DomiDex-Web/backend/market-data/`.
- Release 29 deliberately retains the established **full synchronization**
  design. No delta-sync architecture was enabled.

## 2. Actual scanner components

| Component | Present implementation | Current capability / boundary |
| --- | --- | --- |
| Camera lifecycle | `PortraitScannerCamera` binds Preview, ImageCapture and ImageAnalysis to the visible lifecycle, clears the analyzer and unbinds all use cases on teardown. | Correctly frees the current scanner session; this is a suitable basis for an additive live path. |
| Preview backpressure | CameraX `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`. | Prevents preview-frame queues from growing behind a slow analyzer. |
| Live frame quality | `FrameQualityAnalyzer` in `CameraScanner.kt`: card boundary, four corners, coverage, sharpness, exposure, glare and movement/stability. | Lightweight preview gating (about seven checks/second), not OCR or final card identity. |
| Auto capture | Disabled initially; opt-in `AutoCaptureGate` requires two eligible frames and re-arms only after missing/new-card evidence. | Can automatically take one still image; it does **not** silently add a card. |
| Capture / image pipeline | One latency-optimized full-resolution still, then bounded OpenCV normalization/crop and ML Kit text processing through `CardScanPipeline` and `CardTextAnalyzer`. | Existing reliable path for set/collector-number evidence; it is photo-based, not continuous preview recognition. |
| Candidate identity | `CardRecognitionRanker`, hybrid fusion and `ScannerDecisionPolicy`. Candidate threshold is 65; automatic acceptance requires score at least 88 and a clear margin. | Contradictions reject; close results stay ambiguous. Collector confirmation remains required before collection/binder mutation. |
| ScanKnowledge | Owner-scoped cache plus exact-document observed community hints. | Identity hints are guarded by authenticated entitlement and revoked on access loss; they are not a replacement for card evidence. |
| ScanEvidence | Explicit-consent durable local outbox, uploaded by `ScanKnowledgeSyncWorker` with a connected-network constraint. | Feedback/evidence is separate from scanning and is not automatically uploaded merely by scanning. |

### Current live-scanner status

The app is an **automatic-capture scanner**, not a finished continuous
recognition scanner. A stable card in the preview can trigger an opt-in photo;
the final OCR, catalogue lookup and candidate decision occur only after that
photo is captured and processed. This distinction is intentional and must not
be represented as already-complete continuous recognition.

## 3. Safe continuous live-scanner evolution

The existing architecture is suitable for an additive implementation. The
manual capture and current auto-capture route must remain available throughout.
No Phase-4 source change introduces a feature flag or replaces the scanner.

| Stage | Planned work | Required guardrails / admission evidence |
| --- | --- | --- |
| 0 – shadow measurement | Add a non-user-visible coordinator that records bounded preview timing, quality state, dropped-frame count and thermal/battery signals. | No candidate is shown or persisted; verify no capture/analysis regression first. |
| 1 – frame selection | From only stable, complete card frames, crop the card rectangle and downscale to a small canonical preview. Limit recognition attempts (for example 1–2 Hz) and cancel stale work. | `KEEP_ONLY_LATEST`, one bounded worker, lifecycle cancellation, no full-resolution preview OCR. |
| 2 – low-cost clues | Read small set/collector-number regions only, normalize them and query a bounded local catalogue candidate set. Cache results by card-frame fingerprint. | No final identity from artwork/name alone; unreadable or contradictory evidence stays unresolved. |
| 3 – temporal fusion | Require the same plausible candidate across several bounded samples, debounce the same physical card and show an overlay candidate. | User must confirm before any collection/binder write; suppress repeated presentation of the same card. |
| 4 – adaptive quality | Use a device performance governor: lower recognition rate/resolution on sustained latency, thermal pressure or low battery; restore only after a cool/stable window. | Never keep the camera or a worker alive after lifecycle stop; preserve manual capture as fallback. |
| 5 – field rollout | Enable only behind a remote/local feature gate after physical-card and device evidence meets explicit thresholds. | Per-device telemetry must be consented and aggregated; a poor device/card class remains on existing photo capture. |

### Foil / Holo limitation

Preview glare detection can reject unsuitable frames, but a single live preview
frame cannot reliably distinguish Holo from Reverse-Holo across lighting,
angle, print runs and cameras. In the live overlay, finish must therefore be
shown as unknown or provisional unless the captured-photo pipeline plus
catalogue evidence supports a choice. The user must retain the final variant
selection. This is a quality requirement, not a missing UI label.

## 4. Local synchronization architecture and evidence

| Area | Existing protection | Evidence / limitation |
| --- | --- | --- |
| Collection | Owner-scoped Firestore namespace, authenticated entitlement checks, Room as UI source, durable outbox, optimistic revisions and conflict state. | Listener does not overwrite pending/conflicted local data. |
| Private binder | Durable private-binder outbox, bounded full pull and continuation scheduling. | `PrivateBinderSyncWorker` has connected-network constraints, user-initiated expedited fallback on Android 12+, plus 15-minute periodic work. |
| Price selection | Sidecar selection waits until its parent collection mutation is resolved. | Prevents a selection document from racing ahead of an unacknowledged collection entry. |
| Sharing | Separate owner-scoped continuation scheduler. | Must be covered by the two-client field matrix before release approval. |
| Scan knowledge/evidence | Separate consented path, not collection synchronization. | No scan image/evidence leaves the client through ordinary collection/binder sync. |

### What has already been tested locally

`CollectionSyncSessionSdkInstrumentedTest` contains real local Firebase
Auth/Firestore-emulator tests with two independently named SDK clients and two
file-backed Room databases for the same synthetic user. It does not use the
production Firebase project or a default production app. Covered cases include:

- stale private-binder edit convergence without inventing a later remote
  revision;
- offline private mutation surviving Room close/reopen, then acknowledging only
  after local network restoration;
- idempotent rerun / duplicate suppression; and
- a bounded full pull of 1,001 synthetic binders with explicit continuation.

This is strong local integration evidence, but it is **not** proof from two
physical Android devices or proof of operating-system WorkManager delivery after
a device reboot. The broad historical multi-client harness remains **17/18 RED**
because of an unresolved lock-only listener/permission-denial characterization.
It is not counted as a green release gate.

## 5. Reproducible two-Android-client matrix (not yet executed)

Use two fresh debug installs, two separate Room files, one synthetic test user
and only local Auth/Firestore emulators. Never point these steps at production.

| # | Scenario | Acceptance evidence |
| ---: | --- | --- |
| 1 | Sign in on A and B | Same synthetic owner; no other owner data visible. |
| 2 | Add normal card and duplicate on A | B receives exactly intended entries; repeated full sync adds no row. |
| 3 | Create/move private-binder placement on A | B receives binder, placement, cover/holo and revision unchanged. |
| 4 | Same binder edited from same revision on A and B | Explicit conflict outcome; stale client cannot overwrite winner or create invented revision. |
| 5 | A offline: add card and edit binder | Durable outboxes remain after force-stop / Room reopen. |
| 6 | Re-enable local network | One acknowledgement per mutation, no data loss, duplicate or extra outbox row. |
| 7 | App fully close/reopen on both | UI rebuilds from local Room before and after pull. |
| 8 | Reboot one test device/AVD | Android reschedules eligible work; no lost outbox after recovery. |
| 9 | Unreachable emulator then recovery | Worker retries without hot-loop; full sync converges after endpoint returns. |
| 10 | Price selection while parent entry pending | Sidecar stays deferred until parent acknowledgement. |
| 11 | Shared binder change plus ordinary collection change | Both schedulers complete independently without cross-owner writes. |
| 12 | Repeat successful scenario | Idempotent state and stable remote revisions. |

## 6. WorkManager / offline test plan

- Exercise the worker only through local network constraints; never replace
  endpoint configuration or credentials in production builds.
- Use airplane/network isolation for the test emulator, then restore only its
  local emulator route.
- Capture outbox count, mutation IDs, remote revision and WorkManager state
  before offline, after force-stop/reopen, after reboot and after recovery.
- Verify `Result.retry()` on transient local transport failure, no retry for
  revoked/changed owner, and only one continuation for bounded pulls.
- Treat an emulator reboot as useful but insufficient: at least two physical
  models still need the same test before a release claim.

## 7. Physical scanner matrix (not yet executed)

| Dimension | Samples / conditions | Automated coverage | Physical-device requirement |
| --- | --- | --- | --- |
| Card stock | normal, Holo, Reverse-Holo, older border, promo, full art | Fixture/ranking safety and rejection logic | Yes: real foil/reflection and focus behaviour. |
| Lighting | diffuse indoor, side light, low light, direct glare | Quality-policy tests | Yes: actual sensor exposure, glare and torch. |
| Geometry | near/far, parallel, 5–15° tilt, portrait/landscape | Crop/geometry fixtures | Yes: autofocus and motion behaviour. |
| Scene | one card, partial card, multiple cards, patterned background | Rejection/decision tests | Yes: real boundary detector safety. |
| Motion | stable hand, focus hunt, swap to next card | `AutoCaptureGate` tests | Yes: timing and false-repeat rate. |
| Devices | Redmi/Xiaomi plus a second current Android model | Emulator for UI/lifecycle only | Yes: camera hardware, thermal/battery and performance. |

Record model/API, card identity, light setup, distance, mode, capture latency,
analysis latency, candidates, correction/fallback and whether the user opted
into ScanLab. No physical run may upload scan material without that existing
explicit consent.

## 8. DLR / Cardmarket status

No mapping or price decision was changed in Phase 4. The repeatable local
audit remains:

| Measure | Result |
| --- | ---: |
| DLR cards / images present | 141 / 141 |
| Variant-safe Cardmarket mappings | 62 |
| Needs review | 69 |
| Unmatched | 10 |
| Open cards with ambiguous exact fingerprint candidates | 47 |
| Open cards without exact fingerprint candidate | 32 |
| Duplicate verified product IDs | 0 |
| Android / Web / binder invisible verified entries | 0 / 0 / 0 |

The remaining 79 printings stay unpriced and explicitly non-verified. No
product ID, finish or price was guessed. Phase-3's Cardmarket restriction
remains: an authorised print-specific source and written approval for the
intended data use are externally required before live catalogue completion.

## 9. Regression results – Phase 4

| Check | Actual result |
| --- | --- |
| Scanner safety subset | **58 / 58 passed** (`AutoCaptureGate`, ranker, decision, hybrid fusion, pipeline state). |
| Android full unit suite | **1,080 / 1,080 passed** with the required local audit snapshot explicitly supplied. |
| Android lint | **0 errors**, 165 existing warnings retained. |
| Web full suite | **52 files / 779 tests passed**. |
| Web typecheck | Passed. |
| Web production build | Passed; existing non-blocking 500 kB chunk advisory retained. |
| Firestore emulator rules | **13 / 13 passed**. |
| DLR audit | See section 8; counts unchanged. |

An initial all-unit invocation without `DOMIDEX_AUDIT_DIR` reported the missing
optional dated input `sets-de.json` in `AllBinderPriceAuditTest`. That was a
local audit-fixture path issue, not a source-code defect. The test was rerun
using its already-created, read-only local snapshot and all 1,080 tests passed.
The test was not weakened or suppressed.

## 10. Classification and next steps

### Successfully tested

- Existing scanner safety gates, ranking/decision safeguards and automatic
  capture re-arm behaviour.
- Full Android unit suite, lint, Web suite/typecheck/build, Firestore rules and
  DLR audit, all locally.
- Isolated Firebase SDK/Room integration scenarios described in section 4.

### Technically prepared

- Additive, staged live-recognition architecture.
- Reproducible two-client, offline, WorkManager and physical-card matrices.
- Existing full-sync, outbox and continuation infrastructure.

### Implemented but not hardware tested

- Current CameraX preview, quality gating and opt-in automatic capture.
- Local emulator SDK/Room sync path; it is not an Android-device delivery proof.

### Externally dependent

- Cardmarket written approval / authorised print-specific product data for the
  79 unresolved printings.
- Two controlled physical Android devices and representative real cards/
  lighting for field validation.

### Still open / release-blocking

1. **P0:** 79 DLR printings lack variant-safe Cardmarket mapping.
2. **P1:** Real two-device, reboot and OS WorkManager delivery proof is absent;
   historical broad harness is 17/18 RED.
3. **P1:** Full physical scanner matrix, including foil/reflection and weak
   device classes, is absent.
4. **Feature gate:** Continuous live recognition is planned only; it is not a
   release-ready R29 feature and must not replace the reliable capture route.

### Recommended next development step

Execute the two-client matrix on isolated local emulators, then on two physical
test devices, before any Firebase/Play action. Build the live scanner in Stage
0 shadow mode first and admit each later stage only after the physical matrix
has measured false-accept, duplicate, latency, thermal and battery behaviour.
