# DomiDex Release 29 – Phase 7 report

**Date:** 26 November 2026  
**Status:** virtual-device readiness and release-prerequisite review only; not release approval.

## Safety boundary

No Firebase, Functions, Hosting, Firestore production-data, Google Play Console, tester, release, Git push, deployment or camera action was performed. No physical camera or webcam was enabled. Full sync remains the only enabled synchronization design.

## Evidence basis

This phase continued from Android commit `fad7d4b` on `codex/release-29-hardening`. Phase-6's current Android Gradle evidence (1,088 tests, debug lint and debug APK) and its Web evidence were retained; they were not needlessly repeated. The Phase-6 report remains the authoritative record for those runs.

## Virtual Android environment

Android Studio is installed locally (AI-262.10968.63.2622.16975389). The existing `Medium_Phone_API_37.0` AVD was cold-booted successfully. `adb` reported `emulator-5554` as `device`, `sys.boot_completed=1`, Android `17`, and model `sdk_gphone64_x86_64`.

No app, instrumentation, Firebase, scanner or camera test was started on that AVD. At the time of the check, `adb devices` listed only the emulator; no physical device was connected. A bootable emulator proves only virtual-device availability. It is not evidence for the camera pipeline, real OCR, physical lighting/finish behaviour, offline sync, reboot/WorkManager behaviour, or two-device convergence.

Camera validation is prepared by the Phase-6 hardware matrix but must not activate a webcam or device camera without Dominik's explicit approval. The matrix remains unexecuted.

## DLR/Cardmarket review

No mapping or price data was changed. The latest read-only audit evidence remains 141/141 cards with images, 62 variant-safe verified mappings with trend price, 69 entries requiring review and 10 unmatched entries. Of the 79 open printings, 47 have more than one exact fingerprint candidate and 32 have none; there is no additional unique safe candidate. The current public catalogue lacks collector-number and finish fields, so product order, name, image or price remains insufficient evidence. A print-specific authorised source, Cardmarket API entitlement, or documented individual evidence is still required.

## Web market-data lint position

No Web source was changed in this phase. Phase 6 already corrected the four unambiguous runtime-source findings and verified `174 / 174` isolated `backend/market-data` tests. The remaining 205 full-lint findings are the pre-existing test-harness backlog only: 168 `no-floating-promises`, 26 `unbound-method`, and 11 other style/maintainability findings. They were not created by Release 29, suppressed, or mass-rewritten. They still prevent a green full Web lint gate until separately remediated.

## Read-only Play/release prerequisite review

The current Android project configuration has `debug` and `release` build types, no flavours, package `com.dominik.cardvault`, and release signing only through a protected ignored configuration or short-lived environment variables. Phase 6 established that the authorised, ignored `app/google-services.json` is available for the debug validation path; it does not supply release-signing authority.

`RELEASE_27_BUILD_28.md` records a historical R27 upload/test-track receipt. It is not evidence of an R29 bundle, a current Play review state, or permission to use Play Console. Before any future R29 Play action, the following evidence remains necessary:

1. Resolve the P0 DLR evidence gap for all 79 unpriced printings, or explicitly narrow an approved release scope without inventing mappings.
2. Execute and record the physical scanner and two-device/full-sync/offline/reboot/WorkManager matrix with authorised non-production data.
3. Produce a separately authorised release-signed AAB using existing protected signing material; retain the exact input commit, AAB hash, R8 mapping and matching native-debug-symbol archive, and validate the exact bundle.
4. Complete a release-device compatibility check, including the applicable Firebase/App Check activation decisions. The documented App Check rollout is default-off and no enforcement or console action was made here.
5. Deploy/recheck reviewed Firestore rules only under separate approval before clients writing the creation timestamp are exposed. No such deployment was performed.

## Release status

Release 29 is **not release-ready**. The virtual Android environment is ready for non-production tests, but physical devices and authorised non-production data remain unavailable. The DLR P0 evidence gap, unexecuted physical/two-device matrix, and Web test-only lint backlog remain open. No historical Play record changes these conclusions.
