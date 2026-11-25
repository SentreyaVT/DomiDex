# DomiDex Release 29 – Phase 8 emulator report

**Date:** 26 November 2026  
**Status:** offline Android-17 emulator validation only; not release approval.

## Safety boundary and environment

No Firebase, Functions, Hosting, Firestore production-data, Google Play, deployment, Git push, camera, webcam or physical device was used. The installed debug APK was the existing Phase-6 artifact; no new build was required.

The existing `Medium_Phone_API_37.0` AVD was used as `emulator-5554`: Android 17, 1080 × 2400, 4,005,308 KiB RAM total and 19% of the user-data partition used at test start. The application was not previously installed.

Before installation, the emulator was isolated using the observable state `airplane_mode_on=1`, `wifi_on=0` and `mobile_data=0`. Android 17 rejects the legacy shell broadcast for airplane-mode change, but the three settings remained in the stated offline values. This phase intentionally did not restore connectivity because the available Firebase client configuration is not independently proven to point to a non-production project. No Google sign-in was started.

## Commands and start results

- `adb install -r app/build/outputs/apk/debug/app-debug.apk`: **EMULATOR — PASS**.
- `adb shell am force-stop com.dominik.cardvault` followed by `adb shell am start -W -n com.dominik.cardvault/.MainActivity`: **EMULATOR — PASS**. First cold start: **3,560 ms**; later force-stop/restart cold start: **3,677 ms**.
- Background/Home followed by the same start command: **EMULATOR — PASS**. The existing task resumed hot in **119 ms** and retained the selected Settings screen.
- `adb logcat -d -b crash` and a scan for `FATAL EXCEPTION`, `ANR in` and `Fatal signal`: **EMULATOR — PASS**. No matching crash or ANR record appeared during this run.

The view hierarchy was present and accessible after every tested navigation step; no blank or stuck rendering state was observed.

## Tested functions

| Function | Classification | Result |
| --- | --- | --- |
| Welcome screen and local-only entry | EMULATOR | PASS — selected **Vorerst lokal nutzen**; no account or cloud action. |
| Overview / empty portfolio | EMULATOR | PASS — zero cards/entries, unavailable-price state and empty Binder state rendered. |
| Binder navigation and empty Set-Binder | EMULATOR | PASS — empty-state guidance rendered. |
| Card-search entry, set selection and filter | EMULATOR | PASS — search entry opened; typing `Pikachu` updated the filter. Offline catalogue-unavailable and no-result states rendered safely. |
| Collection navigation | EMULATOR | PASS — zero-card/zero-entry empty state rendered. |
| Settings navigation and Android back navigation | EMULATOR | PASS — settings loaded; Back returned to the application overview without a crash. |
| Background/resume and force-stop restart | EMULATOR | PASS — see start results above. |
| Offline operation | SIMULATED / EMULATOR | PASS for local navigation and offline/empty catalogue behaviour. Network reconnect was not tested. |
| WorkManager observation | EMULATOR | OBSERVED — a `CardmarketSyncWorker` SystemJob was scheduled with network unsatisfied while offline; no worker execution was triggered or asserted. |
| Camera permission | EMULATOR | PASS — app-op `CAMERA: ignore`; package permission `android.permission.CAMERA: granted=false`. Scanner tab/camera was not opened. |

No card, binder, price, variant or account data was created. The only persisted emulator-local state is the local-use onboarding selection created in a newly installed debug app; it can be removed by uninstalling this test APK if a clean AVD state is required.

## Not tested or blocked

- **BLOCKED:** online/reconnection, cloud sync, authentication, Firebase calls and remote catalogue loading. The local Firebase configuration is not proven non-production, so connectivity stayed disabled.
- **NOT TESTED:** scanner camera pipeline, preview/live capture, OCR, images, Holo/Reverse-Holo/normal variants, light/reflection and any webcam/physical camera path. No permission was granted or requested.
- **NOT TESTED:** card detail with populated catalogue, price mapping, duplicates, ordering, Master-Set and variant persistence, because the offline fresh installation had no authorised local test catalogue or non-production cards.
- **NOT TESTED:** physical device, two-device, reboot and real WorkManager recovery evidence. The Phase-6 hardware matrix remains authoritative for those cases.

## Release status

This run proves a narrow offline Android-17 emulator smoke path. It does not replace the physical scanner/two-device matrix, non-production sync evidence, DLR evidence work, Web full-lint cleanup or a separately authorised release build. Release 29 remains **not release-ready**.
