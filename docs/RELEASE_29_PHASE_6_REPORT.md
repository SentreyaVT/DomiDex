# DomiDex Release 29 – Phase 6 report

**Date:** 25 November 2026  
**Status:** local remediation and validation preparation; not release approval.

## Safety boundary

No Firebase/Functions/Hosting deployment, production Firebase access or data change, Google Play action, tester change, Git push, release or DLR mapping change was performed. Full sync remains the only enabled synchronization design.

## Android configuration blocker

The local project has only `debug` and `release` build types and no product flavour that omits Google Services. The Google Services Gradle plugin is applied to the app, and `FirebaseGoogleAuthGateway` requires the generated `R.string.default_web_client_id` resource.

No `google-services.json` was found in the current DomiDex workspace or the inspected local Android-project roots. The file is correctly ignored by Git. As a result, normal Gradle Android compilation stops at `:app:processDebugGoogleServices`; skipping that task then stops at the missing generated resource. A placeholder, reconstructed project identity, API key or OAuth client was not created.

Needed user-provided local input:

1. An authorised Firebase `google-services.json` for the Android application ID `com.dominik.cardvault`, stored only at `E:\DomiDex\CardVault\app\google-services.json`.
2. The file must be generated from the intended authorised Firebase project and include the registered Android client/OAuth configuration that yields `default_web_client_id`.
3. Alternatively, a separately authorised non-production Firebase project/configuration explicitly approved for this workstation. It must not be committed, copied to a release artifact by hand, or used to change production data.

Without one of these inputs, only Firebase-independent source/unit validation is safe. The Phase-5 standalone `LivePreviewFrameGateTest` result (8 / 8) remains the current Android executable evidence; the Gradle unit suite, lint, APK and emulator checks remain unexecuted for this checkout.

## Hardware validation preparation

`RELEASE_29_PHASE_6_HARDWARE_MATRIX.md` now lists the required physical scanner, lighting, Holo/Reverse-Holo, offline/recovery, two-device and reboot/WorkManager cases. All physical rows are explicitly **NOT EXECUTED**. It separates existing automated gate coverage from source review and physical evidence.

## Web market-data lint assessment

The prior full Oxlint result had 209 errors, all under the historically experimental and non-deployed `backend/market-data/` directory:

| Class | Count before remediation | Assessment |
| --- | ---: | --- |
| `no-floating-promises` in test files | 168 | Test-harness observability/waiting issue; no runtime source finding. |
| `unbound-method` in test files | 26 | Test assertion style; no runtime source finding. |
| Other test-only style rules | 11 | Maintainability issue, not a production execution path. |
| Runtime-source rules | 4 | Deterministic validation/style findings in `contract.mjs` and `ingestion.mjs`. |

The four runtime findings were corrected without altering accepted wire semantics: explicit ASCII-control scanning replaced equivalent control-character regexes, and intentionally discarded fields are named as ignored. Targeted Oxlint for both runtime files passes. The remaining **205** findings are test-only and deliberately not mass-rewritten in this phase; no lint suppression or broad ignore rule was added.

`node --test backend/market-data/*.test.mjs` passed **174 / 174** after the change. These are local module tests only. The directory remains unintegrated with a production deployment and was not published.

## Current verified status

- Web Phase-5 evidence remains current: 52 test files / 779 tests, TypeScript check and production build passed; focused Binder lint passed. The complete Web lint is not green because of the remaining 205 test-only market-data findings.
- The latest read-only DLR audit remains 141/141 cards/images, 62 variant-safe verified mappings/trend prices, 69 review and 10 unmatched. The 79 unresolved printings remain deliberately unpriced.
- No scanner hardware, Android emulator, full Android Gradle, two-device, offline/reboot or WorkManager matrix result is claimed.

## Release status and blockers

Release 29 is **not release-ready**.

1. **P0:** 79 DLR printings lack authorised, print-specific Cardmarket evidence.
2. **P0:** Android configuration-dependent checks require an authorised local `google-services.json` or explicitly approved non-production equivalent.
3. **P1:** The Phase-6 physical matrix and the two-device/full-sync/WorkManager evidence are unexecuted.
4. **P1:** 205 test-only market-data lint findings remain. They do not indicate a current runtime test failure, but a full-lint release gate cannot pass until that separate test-harness cleanup is completed.

No deployment, release or DLR promotion is authorised by this report.
