# Release 27 App Check contract and staged validation

Status: local integration implemented; rollout disabled, not live protection. No Firebase/Play Console
setting has been changed. Auth, roles, Rules and deletion locks stay authoritative.

## Cross-platform contract

- Optional custom HTTP header: `X-Firebase-AppCheck`, separate from Authorization.
  Android and backend use the same 4,096-character maximum and three-part base64url
  JWT syntax guard. Syntax is not proof of validity; only backend verification is.
- Android keeps the existing Firebase BoM version; release gets only Play Integrity,
  debug gets only the Debug Provider. No debug secret in source, APK assets or logs
  collected for this engineering report.
- Install the appropriate provider in Application startup before activities.
  Existing FirebaseInitProvider and WorkManagerInitializer can run earlier; resumed
  background workers require a separate enabled-build ordering check. Do not
  restructure working initialization speculatively. Startup never awaits attestation.
- Initial rollout switch: strict Gradle property `domidexAppCheck=true|false`,
  default `false` until project registration/distribution validation. This is not
  an Auth bypass: it retains the pre-existing authentication/security boundary.
- For the three private custom Android HTTP clients (share management, manual
  report submission, reviewed scan knowledge), attach only an already available,
  unexpired in-memory token to the exact HTTPS DomiDex API origin. Never force a
  token refresh or wait for attestation on the scanner/share request path.
- No token in public share links, TCGdex/Cardmarket requests or analytics. DomiDex's
  additional custom HTTP cache is RAM-only; the Firebase SDK itself persists tokens
  in its private preferences. Existing disabled backup/extraction rules remain.
  Network errors must not make scans require an online service.
- Backend `DOMIDEX_APP_CHECK_MODE=off|monitor`, default `off` during preparation.
  There is deliberately no enforcement mode in this first integration.
- Monitor uses an explicit comma-separated `DOMIDEX_APP_CHECK_ALLOWED_APP_IDS`
  allowlist of Firebase app IDs, not package names, UIDs or OAuth client IDs.
- Authenticate/authorize first. Missing, invalid, wrong-app or unavailable App Check
  never overrides Auth denial and does not change an otherwise legitimate response.
- At most one bounded verification per original request, no token consumption or
  replay-enforcement RPC. Only fixed route/reason labels; no UID, token, full IP,
  share secret, body, email or private card data. Public catalog/share reads remain
  public under their own capability/rate-limit rules.

The implementation and local evidence below cover the prepared, default-off rollout.
Enabled real-device attestation and SDK latency are separate manual activation gates.

## Manual activation sequence (not performed)

1. Verify the Firebase project linked to Play Integrity and register the Android
   Firebase app with the **Play app-signing** SHA-256. An upload-key fingerprint is
   not a substitute for the installed Play build's certificate.
2. Decide and validate both Play-installed and intentionally sideloaded test builds.
   Play recognition/licensing verdicts differ; do not accidentally exclude either.
3. Use a separately registered debug token only for development. Enter it directly
   in the Firebase Console; never paste it into a report, repository or public chat.
   The official Debug Provider deliberately prints its local debug secret in Logcat.
   Do not collect its tag into test reports, and exclude this provider from release
   dependencies rather than relying on an `if (DEBUG)` branch alone.
4. Build the explicitly enabled test configuration. Observe actual Firebase request
   metrics and custom-backend monitor events. Keep Firestore, Storage, Auth and
   Functions enforcement unchanged/off during compatibility validation.
5. Test Internal/Closed Play installation, debug emulator, offline recovery and
   temporary provider failure. Measure cold-start and first-request overhead of the
   SDK separately from custom HTTP, which uses only cached tokens.
6. Enforce only in a separately reviewed rollout after older supported clients and
   the Web app are accounted for. No claim that a local fake verifier proves real
   device attestation.

## Evidence and limits

- Android: **15/15** new cache/startup-policy JVM tests; **106/106** in the combined
  Auth/sharing/sync/App Check selection, no skips. Missing/expired/near-expiry tokens,
  4,096-character boundary, CRLF/invalid syntax, exact URL/method restriction,
  backward/forward clock changes and off-mode zero provider calls are covered.
- Isolated offline API-36 emulator: **2/2** new startup/request-identity tests;
  **31/31** combined with ACK and existing sharing/work-request regressions in
  1.703 s. No real Firebase user, provider secret or attestation was used.
- Debug and release Kotlin compile. Fresh debug/release merged manifests name
  `DomiDexApplication`; final generated flags are both `false`. Explicit `true`
  produces `true` for both variants; invalid values fail configuration as intended.
- Combined default-off debug APK/test APK, release Kotlin/manifest, 106 JVM tests
  and debug/release lint completed successfully in **4m 53s** (106 Gradle tasks).
  Only ordinary emulator debug artifacts were signed; no release signing or AAB.
- Actual runtime dependency reports and compiled provider bytecode confirm Debug
  Provider **19.4.0 only in debug**, Play Integrity Provider **19.4.0 only in release**.
  BoM remains **34.17.0**. Necessary transitive difference: the release provider
  resolves `com.google.android.play:integrity` **1.4.0** instead of the existing
  Auth/ReCaptcha **1.3.0**; debug stays at 1.3.0. Live sign-in regression remains a
  manual check, not evidence inferred from compilation. No final R8 artifact yet.
- Backend Web commit `d58c917`: **52/52** new contract/runtime/forwarding/services
  tests, independently repeated. **825** distinct non-emulator tests in the full
  Web selection; typecheck/lint/package import and isolated 296-file build pass.
  Existing Gate-2 emulator results are not falsely counted as rerun for Gate 3.
- Backend observes only already-authorized requests, once per original Request,
  with missing/invalid/wrong-app/timeout/failure tests and fixed non-PII labels.
  The 250-ms timer is a response-wait budget, not cancellation of an SDK key fetch
  or a strict wall-clock guarantee. Five local hung-verifier samples measured
  median 263.69 ms, maximum 264.15 ms; JS scheduling can delay the timer further.
  Off/missing synthetic p95 overhead was 0.023/0.067 ms (25 samples each), not a
  live network benchmark. Default-off adds no attestation wait.

Evidence: `artifacts/release27-engineering-20261001/gate3-*.log` and the separate
Web `docs/WEBAPP_RELEASE_27_HANDOFF.md` / ignored baseline output directory.
No unfiltered Logcat collection or App Check debug-secret output was performed.
Existing release-26 signed AAB rechecked SHA-256:
`079608E27BCB5D440252ED727E238B162B449F3DBA4B1C28986D4A4570D9B14B` (unchanged).

Real Play attestation, enabled-build SDK cold-start/first-request latency and
resumed-worker initialization ordering remain **NOT TESTED** until the activation
sequence above. This is not permission to enable enforcement or deploy these changes.

Primary references checked 2026-10-01:

- [Android Play Integrity provider](https://firebase.google.com/docs/app-check/android/play-integrity-provider)
- [Android debug provider](https://firebase.google.com/docs/app-check/android/debug-provider)
- [Custom backend verification](https://firebase.google.com/docs/app-check/custom-resource-backend)
- [AppCheckListener for custom backends](https://firebase.google.com/docs/reference/android/com/google/firebase/appcheck/FirebaseAppCheck)
