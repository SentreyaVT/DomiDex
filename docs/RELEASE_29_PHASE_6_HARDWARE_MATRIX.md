# DomiDex Release 29 – Phase 6 hardware validation matrix

**Status:** prepared only on 25 November 2026. Every physical row is **NOT EXECUTED** until a named tester records the actual device, card, environment and outcome. No row is proof of a release gate by itself.

## Evidence rules

- Use only a non-production test account/project or a locally isolated test data set. Do not alter production collections, binders, prices, Firebase data or tester roles.
- Record device model, Android version, camera module/lens, app commit, build variant, lighting measurement/description, network state and each observed result.
- Save only approved local diagnostics/screenshots; do not attach account tokens, Firebase configuration files, card purchase data or private images to Git.
- A card may be added only after the existing candidate/collector confirmation flow. Treat an automatic collection/binder mutation, an unconfirmed price/variant, or a crash/data loss as a failed case.
- Re-run a failed case three times after a fix. A pass on one device never proves another device family.

## Already automated or simulated evidence

| Area | Evidence | Result | Physical proof? |
| --- | --- | --- | --- |
| Live-frame admission | Standalone Kotlin/JUnit `LivePreviewFrameGateTest` | 8 / 8 passed | No |
| Invalid/incomplete frame, held card, removal/new card, sequence replay, in-flight gating | Same gate unit tests | Covered in simulation | No |
| Bounded queue hand-off | Source review of `LIVE_PREVIEW` through `ScanQueuePolicy` | Review only | No |
| Android CameraX lifecycle/crop/rotation/cache files | Current source review only | Not device-validated | No |
| Full Android Gradle unit suite, debug lint and debug APK | 1,088 tests with 0 failures/errors (2 documented opt-in skips); lint and assemble passed | Automated local evidence only | No |

## Required physical matrix

Use Device A and Device B from different camera/SoC families. Repeat every scanner row on both devices where indicated.

| ID | Area / setup | Device(s) | Procedure and expected result | Result |
| --- | --- | --- | --- | --- |
| SCN-01 | Normal non-foil card, clean background, 300–800 lux | A + B | Enable Live; present one card steadily, remove it, then present again. At most one bounded queue sample per presentation; candidate still requires review; no duplicate collection/binder entry. | NOT EXECUTED |
| SCN-02 | Normal card, rapid movement / partial frame | A + B | Pan, tilt and cover corners during preview. No sample until complete stable green frame; scanner remains responsive and manual capture remains usable. | NOT EXECUTED |
| SCN-03 | Normal card, two cards in frame | A + B | Alternate overlap and side-by-side cards. No silent identity/collection decision; ambiguous/incomplete geometry must stay gated or enter normal review only. | NOT EXECUTED |
| SCN-04 | Holo card, direct reflection and shallow tilt | A + B | Test at 0°, 15°, 30°, 45° under point and diffuse light. Glare must not manufacture finish/variant or price; poor frames are rejected or require normal review. | NOT EXECUTED |
| SCN-05 | Reverse-Holo card, moving reflection | A + B | Repeat SCN-04 while moving light/card. Confirm no repeated queue flood and no incorrect Normal/Holo classification is auto-applied. | NOT EXECUTED |
| SCN-06 | Low light (<100 lux), indoor diffuse (300–800 lux), bright/daylight (>1,000 lux) | A + B | Record lux or an explicit measured-equivalent setup. Validate guidance, gating, manual fallback and absence of crash/thermal runaway. | NOT EXECUTED |
| SCN-07 | Orientation, crop and lifecycle | A + B | Rotate device before/during preview, background then foreground the app, deny/regrant camera permission where supported, and leave scanner. Preview/analyzer stop cleanly; no stale overlay/cache sample is processed after exit. | NOT EXECUTED |
| SCN-08 | Cache cleanup and queue pressure | A + B | Create enough valid presentations to hit the documented background limit, then leave scanner/app. Reopen and inspect approved local diagnostics/cache only. Rejected/evicted items are deleted; pending review is never silently discarded. | NOT EXECUTED |
| NET-01 | Offline scan | A + B | Airplane mode before scanner entry. Scanner/queue can create local review work without network dependency; no retry storm, false online success or data loss. | NOT EXECUTED |
| NET-02 | Connection drop during queued scan/sync | A + B | Begin a local scan, drop Wi-Fi/mobile data during processing and while ordinary full sync is pending. Local state remains visible and no unauthorised remote action is claimed. | NOT EXECUTED |
| NET-03 | Reconnect and recovery | A + B | Restore connection after NET-02. Observe only authorised test data: durable work resumes according to existing full-sync/outbox policy without duplicate records. | NOT EXECUTED |
| SYN-01 | Two-device full-sync conflict | A + B | Use two distinct Android devices and an authorised non-production account/data set. Create edits/offline changes on both, reconnect in both orders, and verify documented full-sync conflict handling. Do not enable delta sync. | NOT EXECUTED |
| SYN-02 | Restart and WorkManager recovery | A + B | Queue approved local test work, force-stop/reboot, restore network, then observe existing WorkManager constraints/scheduling. Record actual execution timing; do not claim expedited work ran unless observed. | NOT EXECUTED |

## Completion record template

```text
Case ID:
Date/time and tester:
App commit / build variant:
Device model / Android / camera lens:
Card type and non-sensitive identifier:
Lighting / angle / network state:
Steps actually performed:
Observed queue, candidate and persistence outcome:
Diagnostics retained locally (path/checksum, if approved):
Result: PASS / FAIL / BLOCKED
Follow-up issue:
```

## Exit criteria

Phase-6 physical validation is complete only when every required case has reproducible evidence on both devices, failures have linked fixes/regressions, no unconfirmed collection/binder change occurred, and the configuration-dependent Android automated suite also has current results. It does not remove the separate DLR pricing-evidence blocker.
