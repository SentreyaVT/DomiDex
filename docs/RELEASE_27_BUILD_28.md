# DomiDex Release 27 – Build 28 release receipt

**Status:** signed candidate for Internal and Closed testing. This receipt records
the local release evidence only. It does not authorize Production or a Firebase,
Hosting, Rules or Functions deployment.

## Identity

- Engineering branch: `codex/release-27-hardening`
- Source commit: `32a75c2ce9829cc49756d6de89def98315b75adb`
- Package: `com.dominik.cardvault`
- Version name: `1.0-closed-alpha.7`
- Version code: `28` (higher than the already-used Play code 27)
- AAB: `releases/v28/DomiDex-v28.aab`
- Size: `36,493,936` bytes
- SHA-256: `c01812f5cf48c22d1598a0838c467c6635d6d0351088fbb244994f7097e7f3ff`

## Build and package verification

- Existing authorized upload signing was used. The exact AAB contains 279 signed
  payload entries and its certificate fingerprint was verified by the release
  archive verifier; no signing material is recorded here.
- `bundletool validate` passed for the exact AAB.
- The manifest verifies the package, version code and version name above.
- Declared app permissions are camera, Internet and network-state access.
  Runtime dependencies also declare normal WorkManager/Google service permissions;
  no new dangerous permission was introduced by this release.
- R8 shrinking is enabled. The matching `mapping.txt` is archived with SHA-256
  `497e7b37c29e52c9b64fc141098b6d3c00bdf38dbc3990789c26450233898795`.

## Verification performed

- Android `lintRelease`: **PASS**, no errors.
- Android JVM suite: **1,070 tests**, zero failures. One explicit optional test
  remained skipped by design.
- Android API 36 emulator: **35 selected binder, price, search, scanner-fixture
  and share-link instrumentation tests**, zero failures.
- Exact AAB payload smoke test: an APK set generated from the release AAB was
  installed on the API 36 emulator, cold-started successfully in 1.299 s and
  produced no `FATAL EXCEPTION`/`AndroidRuntime` entry. The temporary test APK
  set was debug-signed only by bundletool for local installation; the uploaded
  AAB remains upload-signed.
- Fresh catalogue snapshot: 157 sets and 21,457 scoped cards downloaded to
  completion after transient provider 503 retries.

## Price-catalogue audit

- The all-source diagnostic is complete. It intentionally includes Pokémon
  Pocket and other non-Cardmarket source sets, so it is not a valid assertion
  that every entry has a Cardmarket price.
- The physical-only diagnostic visited 19,759 cards / 28,485 actual binder slots.
  20,047 slots have an exact declared Cardmarket product match. 8,416 historic
  printings lack a declared upstream product ID, and one declared product is not
  present in the downloaded snapshot. R27 keeps these entries explicitly
  unverified or offers the existing exact-price selection path; it never invents
  a market price or treats missing price data as zero.

## Native symbols

- `native-debug-symbols.zip` belongs to this exact AAB and is archived beside it.
- Seven arm64 native libraries are present. `libc++_shared.so` has been verified
  against its official NDK symbol candidate. Six third-party stripped vendor
  binaries currently have no approved matching symbols:
  graphics-path, datastore shared counter, CameraX image/surface utilities,
  ML Kit OCR pipeline and OpenCV.
- Upload the matching ZIP after the AAB in Play Console. Google Play may retain a
  non-functional diagnostic warning for those six vendor libraries; no dependency
  was replaced merely to suppress it.

## Known remaining limits

- A hardware-specific scanner lifecycle proof still requires the designated
  physical Redmi evidence device. It was not substituted with an emulator.
- The large 13 GiB storage stress procedure remains a separate, documented
  non-release proof and was not run on a normal emulator.
- Full Sync remains the R27 data path. Delta Sync is deliberately inactive.
- Play track receipt and tester availability are appended only after Play Console
  validation; no Production release is permitted.
