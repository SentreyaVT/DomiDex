# Isolated local history diagnostics

Opt-in emulator-only diagnostics. No ordinary app replacement, network, Firebase
runtime, phone, release key, AAB, deployment or publishing. FULL sync and Delta
configuration are unchanged. This harness does not close Release27 Gate12/15.

## Run

From the Android repository on the configured Windows host:

```powershell
node --test scripts/verification/history-proof/contract.test.mjs
python scripts/verification/history-proof/build.py --build
node scripts/verification/history-proof/run.mjs artifacts/release27-engineering-20261003/history-proof/<fresh-build-uuid>
```

The runner requires the existing offline API36 `DomiDex_R27_Baseline_API36`
emulator at `emulator-5554`, no running instrumentation or reverse ports, and the
ordinary application installed. It does not boot, wipe or reconfigure an AVD.
Run only with the normal Android debug key already pinned by the shared validator.
No release/upload credential may be supplied. Build uses Gradle offline mode.

The build copies the complete existing Room-scale test byte-for-byte and compiles
only it plus the restricted runner. Its fresh UUID application/test IDs cannot
replace ordinary `com.dominik.cardvault`. Test-only packaging removes network and
camera permissions and components/initializers; ordinary manifests are untouched.
Only the two explicit new method selectors can run. No skips or alternate test
discovery are accepted. The existing expensive opt-in tests and371-case suite
are neither changed nor implicitly run.

Build and runtime freeze source inputs, schema, ordinary APK/AAB/mapping outputs
and the complete protected release26 archive. Runtime verifies debug certificates,
manifest restrictions, exact installed APK hashes and the unchanged ordinary app.
Every report validates its UUID, method,25 sample identities or13 edge states,
three owners, all15 persisted-table columns and owned database cleanup. Failure
reports are retained before PASS validation. Only exact pinned owned packages are
stopped/uninstalled in cleanup. The reused child controller handles interruption,
bounded output and a10-minute hung-test guard, not a performance SLA.

## Scope and interpretation

### Private-detail RED / GREEN

An explicit separate mode copies the complete
`PrivateBinderFirstPositiveInstrumentedTest.kt` instead of the Room-scale class.
It retains the same disposable package, offline/debug-only/immutable-release
guards, and does not use the history JSON report contract:

```powershell
node --test scripts/verification/history-proof/contract.test.mjs scripts/verification/history-proof/private-contract.test.mjs
python scripts/verification/history-proof/build.py --build-private-details
node scripts/verification/history-proof/run.mjs <fresh-build-directory> --private-red
# After a justified product fix: NEW build directory, same complete test source.
node scripts/verification/history-proof/run.mjs <new-build-directory> --private-green
```

The sustained complete-model/invalidation control must genuinely pass first.
RED accepts only the final named whole-history query-count assertion with a
positive count, exact single-test failure and matching JUnit summary. It remains
one **failed product test**, never a PASS. GREEN requires both genuine passes.
Raw logs, APK/test-source hashes, parser results and cleanup are retained. Queued
intermediate Flow states may occur; the test proves eventual full expected states,
not atomic emissions or one emission per mutation. No performance inference is
made from these tiny fixtures.

Actual 2026-10-03 RED `ec26839e9d6d4ec8816ab52ba427f947`:
control PASS0.984s; query expected FAIL0.489s, actual history queries1.
GREEN `63775f4bf55b4406a6d8c825c1968ffd`: control PASS1.385s, query PASS0.490s.
Both use complete test SHA256
`2df185fee88ffbe5ce78f3476485fcb4be2f431c0d46468bf0e54148995576f3`.
Both manifests confirm unchanged inputs/protected outputs, removed owned packages,
and unchanged ordinary installed APK7f003…b105. Generated Debug Room observes
collection_entries, catalog_cards and raw_price_snapshots for the new projection.
Final release compilation, broader regression and large-history timings are
separate requirements, recorded for the subsequent standard debug pair in
`docs/RELEASE_27.md` (371/371 plus29/29; annual58 samples; isolated R8 only).
Later parser hardening rejects inline stream exceptions;
the retained real RED/GREEN logs contain no such exceptions.

### History diagnostic

Annual365 fixture:1003 products,950 active owner-A products,366095 raw prices,
346750 eligible history observations. Full expected values, chart and placements
remain unchanged. The raw candidate inherits the actual generated membership
predicate and independently selects the earliest positive Normal/Reverse prices.
It is not a production DAO, Room-invalidation or integrated performance proof.

All four sequential five-phase repetitions finish before four concurrent actual
portfolio/private one-shot subscriptions. Full comparison lists do not escape
the sequential helper. Sample0 is first measured **after baseline reads**, not
cold disk. Memory reports are whole-process snapshots, not peak/leak evidence;
no forced GC or heap override. Sequential phase times are not additive. Transfer
counts do not count internal SQLite row visits. Parallel first emissions do not
prove sustained updates, atomic snapshots, real UI or physical-device behavior.

The second fixture deliberately mutates only its fresh UUID database.13 states
cover missing/nonpositive data, independent finish dates, earlier insertions,
same-date REPLACE, wrong source, duplicate links, tombstones, owner changes,
missing history and quarantine. Read-only hash parity applies to each state's
own before-state, not to the pre-mutation fixture. Saved alternatives and new
repository integration require the separate complete-model/invalidation tests.

## Verified checkpoint, 2026-10-03

Run `934ff50d449d416993598a6e51c9200c`: build2m24s,72tasks executed;4/4 pure host
contracts; phase1/1 in173.081s and edges1/1 in18.429s; no failures/skips. Runtime
12:06:33.315–12:09:58.522UTC, exit0, exact cleanup and preservation verified.

- App SHA256: `755ea554b70cd417a4c73e385cc408ae1ab3900d593cb43f0426b11cbb7b8dbd`
- Test SHA256: `9a50a29e2012d21a5d5f9b6f95613d688d2cf28f3b55cea7bf91ff35139383ec`
- Injected source SHA256: `a59551a92032efc52ee6211c269353208e7459308faf632b326eb6a71cb258c2`
- Ordinary installed app before/after: `7f00308b45958b15eb5ffcf2a95c88b4f13e2c0c922d2dda411184fe8626b105`

Three-warm medians: actual history2986.99ms, stable sort223.74ms, grouping191.75ms,
production sweep98.71ms, raw candidate9.19ms, concurrent pair6351.76ms. This is
evidence for a private-detail experiment, not an already implemented speedup.
Native-strip/lifecycle-deprecation warnings and the test-only removeAll alias
warning remain; this debug proof does not resolve manufacturer native symbols.
