# DomiDex Release 29 – Phase 5 validation report

**Date:** 25 November 2026  
**Status:** partial local validation only. This is not release approval, a deployment record, or evidence of scanner performance on a physical device.

## Scope and safety boundary

- Android branch inspected: `codex/release-29-hardening` at `f86fea1`.
- Web branch inspected: `codex/release-29-hardening` at `40f3d5c`.
- No Firebase, Functions, Hosting, Firestore production data, Google Play, tester, Git push, or release action was performed.
- Full sync remains unchanged. No delta-sync behaviour was enabled.
- The 79 unresolved DLR printings were left unpriced and without invented Cardmarket identities.

## Phase-5 implementation reviewed

The opt-in **Live** scanner path is deliberately separate from automatic photo capture.

- `LivePreviewFrameGate` admits only stable, complete green frames, rate-limits captures, prevents concurrent preview extraction, and suppresses a held card until removal/new-card evidence is observed.
- `FrameQualityAnalyzer` keeps CameraX analysis on `STRATEGY_KEEP_ONLY_LATEST`, saves only an admitted cropped/downscaled cache sample, releases the gate after saving, and closes every `ImageProxy` in `finally`.
- `LiveCameraScanner` disables automatic photo capture when Live is enabled (and vice versa); Live is disabled/paused while queue capacity or capture state disallows background intake.
- `CardVaultApp` submits a preview sample only through the existing bounded scan queue with `ScanCaptureOrigin.LIVE_PREVIEW`. It has no direct collection, binder, pricing, or feedback write path.
- `LIVE_PREVIEW` is background intake in `ScanQueuePolicy`; the existing maximum in-flight/backlog and rejection/deletion path therefore applies.

This is an early candidate-preparation path, not a release-ready continuous-recognition feature. OCR, candidate selection, finish/variant handling and collector confirmation remain in the established scanner queue.

## Executed local checks

| Check | Result |
| --- | --- |
| Standalone Kotlin/JUnit compilation and execution of `LivePreviewRecognition.kt` plus `LivePreviewFrameGateTest.kt` | **8 / 8 passed** |
| Android Gradle targeted unit-test/compile task | Blocked before tests: `app/google-services.json` is absent; the Android Firebase source references the generated `default_web_client_id`. No placeholder configuration was created. |
| Current read-only DLR/Cardmarket audit | **141 / 141** cards/images; **62** verified mappings/trend prices; **69** review and **10** unmatched; zero duplicate verified IDs. |
| Web `npm ci` and `npm --prefix backend/scan-knowledge ci` | Installed from existing lock files. The nested Functions dependency is required by the existing trigger regression. |
| Web `npm test` | **52 files / 779 tests passed** |
| Web `npm run typecheck` | Passed |
| Web focused Oxlint for changed Binder components | Passed |
| Web production build | Passed; existing chunk-size advisory remains non-blocking. |
| Full Web Oxlint | Not green: 209 existing findings in `backend/market-data/`, which was intentionally not modified. |

The Web unit harness still emits its known JSDOM `HTMLCanvasElement.getContext()` notices; these did not fail the suite.

## Changes made during this validation

The Binder views now use a stable selected-binder ID in effects rather than an optional property expression. This removes stale-dependency findings without changing persisted keys or binder ordering:

- `../DomiDex-Web/components/private-binder-gallery.tsx`
- `../DomiDex-Web/components/web-app.tsx`

Focused Binder regression: **23 / 23 tests passed** across `private-binder-gallery`, `private-binder-view`, and `web-collection-layouts`.

## Not tested / release blockers

1. A real, non-versioned Android `app/google-services.json` (or an approved non-production test configuration) is needed before the Android Gradle compile, unit suite, lint, APK, or emulator checks can run on this workstation. It must be supplied through the authorised Firebase setup; it was not fabricated.
2. No physical-device scanner test was performed: interruption/background-resume, rotation, multiple cards, rapid movement, low light/glare, cache cleanup after lifecycle end, and sustained queue-overflow behaviour remain open hardware validation items.
3. No two-device/offline/reboot/WorkManager full-sync matrix was executed in this validation pass.
4. The 79 DLR printings still require an authorised print-specific source or individually documented evidence. Product order, names, images, or prices remain insufficient proof.
5. `backend/market-data/` retains its pre-existing full-lint findings and is outside this Phase-5 change scope.

No release readiness is claimed until the Android configuration-dependent checks and the listed emulator/hardware matrices have real evidence.
