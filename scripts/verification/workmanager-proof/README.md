# R27: isolated actual WorkManager retry proof

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

This is **not** OS JobScheduler, physical reconnect, process death, expedited quota,
simultaneous devices, large-budget automatic continuation or production evidence.
Environment/Node/gRPC guards are layered test controls, not a general OS firewall.

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
