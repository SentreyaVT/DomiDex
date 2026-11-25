# DomiDex Release 29 – Phase 6 report

**Date:** 25 November 2026  
**Status:** local remediation and validation preparation; not release approval.

## Safety boundary

No Firebase/Functions/Hosting deployment, production Firebase access or data change, Google Play action, tester change, Git push, release or DLR mapping change was performed. Full sync remains the only enabled synchronization design.

## Android configuration and current automated validation

The local project has only `debug` and `release` build types and no product flavour that omits Google Services. The Google Services Gradle plugin is applied to the app, and `FirebaseGoogleAuthGateway` requires the generated `R.string.default_web_client_id` resource.

The authorised local `app/google-services.json` is now present, remains Git-ignored, matches `com.dominik.cardvault`, and contains the generated Web OAuth client resource required by `FirebaseGoogleAuthGateway`. Its contents, identifiers and credentials are not recorded here.

With that local-only configuration, the following current checkout checks completed without a production Firebase action:

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | **1,088 tests:** 0 failures, 0 errors, 2 documented opt-in skips |
| `:app:lintDebug` | Passed; no error-level SARIF finding. The report retains 170 existing non-error diagnostics. |
| `:app:assembleDebug` | Passed; debug APK created locally. |
| Phase-5 focused `LivePreviewFrameGateTest` | Passed as part of the current Gradle path; 8 cases also passed in the earlier standalone check. |

The two skips are intentional: a live Cardmarket-download smoke test is opt-in, and an all-binder price audit requires an absent dated public snapshot. They are not counted as successful hardware/emulator evidence.

An Android Virtual Device definition is present but no emulator/instrumentation run was started: the available physical devices and authorised non-production test data are still pending, and broad instrumentation may reach Firebase SDK paths. No substitute configuration or production contact was used.

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
- Android unit, debug lint and debug APK results above are current. No scanner hardware, Android emulator, two-device, offline/reboot or WorkManager matrix result is claimed.

## Release status and blockers

Release 29 is **not release-ready**.

1. **P0:** 79 DLR printings lack authorised, print-specific Cardmarket evidence.
2. **P1:** The Phase-6 physical matrix and the two-device/full-sync/WorkManager evidence are unexecuted; authorised non-production test data and physical devices are still needed.
3. **P1:** 205 test-only market-data lint findings remain. They do not indicate a current runtime test failure, but a full-lint release gate cannot pass until that separate test-harness cleanup is completed.

No deployment, release or DLR promotion is authorised by this report.
