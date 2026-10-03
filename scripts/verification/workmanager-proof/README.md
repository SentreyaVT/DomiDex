# R27: isolated actual WorkManager retry and FULL-continuation proofs

Opt-in local diagnostic, never part of a normal Android Studio or release build.
It packages the unchanged product worker/scheduler into a fresh UUID-scoped debug
application and tests WorkManager 2.11.2 with real Auth/Firestore emulator SDKs.
Full synchronization remains enabled; no Delta, production or release action.

## Fixed prerequisites and commands

Windows, the reviewed sibling `DomiDex-Web` checkout, Android Studio JBR, SDK
36.0.0 tools, existing offline `DomiDex_R27_Baseline_API36` at `emulator-5554`,
normal Android debug certificate, installed Firebase CLI15.28.2 and verified
cached emulator jars. No release credentials or `keystore.properties` may exist.
The initial build needs the official test-only `work-testing:2.11.2` dependency;
the init script does not alter product dependencies or Gradle files.

```powershell
node --test scripts/verification/workmanager-proof/contract.test.mjs
python -I -B scripts/verification/workmanager-proof/build.py --build
node scripts/verification/workmanager-proof/run.mjs "<exact successful build directory printed above>"
node scripts/verification/workmanager-proof/run.mjs "<same unchanged build directory>" full-budget-continuation
```

Every build produces a fresh directory under the fixed dated engineering root.
Only `assembleDebug` and `assembleDebugAndroidTest` are permitted. Output folders
are isolated; ordinary APK/AAB/mapping outputs and the complete v26 release archive
are inventoried before/after. AGP9's separate Kotlin source set is explicitly set;
compiled-class checks require the runner, proof and unchanged copied gRPC guard,
and reject an accidentally compiled ordinary test suite.

The runner requires the same full source/output inventory, exact APK identities,
single pinned debug certificate, isolated manifests and unchanged rules. An
existing runtime folder is never overwritten. A new source change requires a
fresh build. Only the two run-scoped packages are installed; the ordinary installed
app's APK hash must remain unchanged. Only owned reverses9097/8187 and synthetic
demo data are used. Inputs/ports/reverses are checked after shutdown. Own test
packages/data are removed after the run, never the ordinary app or a phone.

The default command still selects the original retry scenario. The sole optional
mode is `full-budget-continuation`, with its own non-overwriting
`runtime-full-budget-continuation` directory and `wm-full-proof` receipt. Each
command installs and removes only its own isolated package pair. The same build
may run both scenarios only while its complete input/output inventory still
matches. The historical date in the fixed build directory is not the run date;
each fresh receipt records its actual UTC start/finish.

## What is exercised

- Real `PrivateBinderSyncScheduler.schedule` and unchanged
  `PrivateBinderSyncWorker`, not a direct helper/doWork call or fabricated Result.
- `TestScheduler` retains system clock/default scheduler. TestDriver only controls
  CONNECTED; the offline AVD never receives an external network.
- Held constraint: no worker invocation, Room or remote mutation.
- Genuine SDK transport failure: actual retry with unchanged default exponential
  30-second backoff, same work identity and preserved complete local state.
- Restored local SDK transport: genuine Remote ACK and FULL metadata, one clean
  owner binder. Other owner's pending binder/outbox and all unrelated tables stay.
- Second actual APPEND_OR_REPLACE enqueue: dependency chain is present and remote
  document/revision/server timestamp plus local state remain identical.
- Independent Admin readback inventories the full synthetic remote tree and checks
  exact payload and unchanged account claims. START/PASS/total and cleanup must
  all agree; skip, assumption, crash, unexpected identity or incomplete result fails.

The retry scenario is **not** OS JobScheduler, physical reconnect, process death,
expedited quota, simultaneous devices, large-budget automatic continuation or production evidence.
Environment/Node/gRPC guards are layered test controls, not a general OS firewall.

## Additional FULL-budget scenario

Admin seeds exactly1001 immutable synthetic owner binders. An existing pending
owner binder must be truly acknowledged, giving1002 server binders; a different
owner's pending binder/outbox must remain untouched. The test explicitly enqueues
only two independent cycle starts. The unchanged production worker must itself
append both continuation requests through the actual production scheduler.
TestDriver only releases CONNECTED for the already observed real WorkSpecs, after
their exact prerequisite and intermediate Room/cursor states are checked.

The first partial pass must contain997 paging rows plus the acknowledged binder;
the remaining rows must arrive through the continuation. All14 domain tables,
exact server payloads/timestamps and owner boundaries are checked. A second
complete cycle must be idempotent. The host independently inventories every
synthetic document and checks the nominal account claims. Only the exact1001
paging paths get an additional reference allowance; the ordinary16-reference and
12-collection/depth3 bounds remain. Four unique WorkSpecs, four zero-retry attempts,
exact linear prerequisites, START/PASS, receipt and owned cleanup must all agree.

This is a binder-heavy controlled TestScheduler proof, with the other groups
empty. The no-extra-work observation is explicitly bounded to one second after
each completed cycle. It does not establish OS delivery, real reconnect, process
death, arbitrary mixed-group scale, simultaneous devices or production behavior.
## Root result 2026-10-03

Fresh run `580b60a79e984c4abb522894b885b1e0`: debug-only isolated build passes in
2m5s,72 executed tasks. Product-source inputs and existing ordinary outputs/full
v26 archive remain unchanged. Proof APK SHA256
`510ad5dd5f640134eab3100f735154365452d3d6f5bb98099cb4badbcf427134`;
test APK `bdafc98cd649303c7d64ad293b2f9f057cf906976d4eeb40c31bba5e5b2cb622`.

- Original retry: **1/1 PASS,36.865s**; actual attempts `[0,1,0]`, retry27ms after
  its unchanged30-second eligibility,2 WorkSpecs. Receipt in `runtime/`.
- FULL continuation: **1/1 PASS,30.058s**;4 distinct WorkSpecs, attempts `[0,0,0,0]`,
  exact dependency chain, both automatic tails and idempotent second cycle.
  Independent Admin readback1001→1002 documents, unchanged seed timestamps and
  account claims. Receipt in `runtime-full-budget-continuation/`.
- Both device/server/host manifests pass, exact owned-package/database/reverse
  cleanup and frozen-input checks included. The ordinary emulator APK remains
  `7f00308b45958b15eb5ffcf2a95c88b4f13e2c0c922d2dda411184fe8626b105` throughout.
  This owner-installed APK is not itself exercised by these isolated proof apps.
- Pure validator suite **7/7 PASS**; four additive tests include negative fixtures
  for scenario, exact-reference budget, payload/timestamp/owner and WorkSpec proof.
  Independent Android/host source reviews found no blocking issue.

The fixed directory retains the historical `20261002` root, but these new receipt
timestamps explicitly identify2026-10-03. Existing lifecycle/test-factory and CLI
deprecation warnings are retained. No phone, production, release build/signing,
deployment, push or Google Play action occurred. The limits above still apply;
Gate4 is partial and Gate15 remains locked.

## Root result 2026-10-02

Run `5bfca253658a4c7f844cb245428c73b0`: **1/1 PASS, 36.744 s**. Factory attempts
`[0,1,0]` across exactly two WorkSpecs; retry began24ms after eligibility and
30,052ms after the first factory invocation. All14 Room tables, independent server
readback, owned cleanup, complete inventories and ordinary installed APK unchanged
checks pass. Runtime evidence: `manifest.json`, `device-proof.json`,
`server-proof.json`, `instrumentation.log` inside that run's `runtime/` directory.
No source, signing, production or original-app modification was needed for this proof.

Earlier harness failures are retained, not counted as product failures or PASS:
over-broad compiler-JAR task guard (`7aa603...`), AGP9 Kotlin source-set mismatch
(`364ff...`), debug-certificate DN print order (`e0f828...`, before install), and
raw versus canonical Android database path (`b59c...`, before worker invocation).
The successful receipt confirms `/data/user/0` and `/data/data` refer to the exact
same run-owned database. Fingerprint/ownership checks were retained, not bypassed.
Pure validator suite: **3/3 PASS**, including adversarial negative fixtures.

Gate4 lifecycle work and Gate15 remain open/locked respectively.
