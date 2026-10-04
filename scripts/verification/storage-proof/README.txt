ISOLATED STORAGE PROOF -- NOT EXECUTED BY ITS AUTHOR

Scope
This separate opt-in harness builds a unique standard-debug app/test pair, with
plain android.app.Application and no app components, INTERNET or CAMERA. It
does not launch the normal application, open a user's database, create/boot an
AVD, change networking, sign release artifacts or contact a cloud service.
The only target is emulator-5562 / DomiDex_R27_Storage13GiB_API36_20261004,
API36 x86_64, dedicated 48G /data. A phone or another emulator is never selected.
Existing verification scripts and their limits are not changed.

Review/setup prerequisites (root operator only)
1. Finish independent review and run the synthetic pure tests below. Freeze
   all app sources and protected outputs until every desired phase completes.
2. Root's separate AVD must already be booted and offline: airplane1, wifi0,
   no active default network/reverse mapping/instrumentation/ordinary app.
3. Record fresh read-only evidence in
   artifacts/release27-engineering-20261004/storage-proof/reviewed-profile.json:
   schema:1; serial:"emulator-5562";
   avd:"DomiDex_R27_Storage13GiB_API36_20261004";
   bootId: exact /proc/sys/kernel/random/boot_id;
   fingerprint: ro.build.fingerprint; model: ro.product.model;
   avdIniSha256: SHA256 of C:/Users/Dominik/.android/avd/<avd>.ini;
   avdConfigSha256: SHA256 of C:/Users/Dominik/.android/avd/<avd>.avd/config.ini;
   imagePackageSha256: SHA256 of
   C:/Users/Dominik/AppData/Local/Android/Sdk/system-images/android-36/google_apis_playstore/x86_64/package.xml.
   Do not fill placeholders or infer hashes. A changed boot/profile fails closed.
   No profile is created or network property changed by this harness.
4. No release credentials may be present. Only exact debug assemble tasks are
   allowed. The unchanged shared snapshot protects app/src, schemas/config,
   ordinary debug outputs, AAB/mapping and the complete archived release26.
   This harness adds its own source files to that snapshot.

Commands to be run only by the root operator, from the repository directory
(use the approved Python executable; all scripts are standard-library only):
  python -B scripts/verification/storage-proof/test_contracts.py
  python -B scripts/verification/storage-proof/build.py --build
The build prints a fresh .../storage-proof/<32hex> directory; retain its exact
path as BUILD below. It copies all four test source files byte-for-byte and
never replaces ordinary APKs. It does not install or run anything.
  python -B scripts/verification/storage-proof/run.py --build-dir BUILD --action check
  python -B scripts/verification/storage-proof/run.py --build-dir BUILD --action install
  python -B scripts/verification/storage-proof/run.py --build-dir BUILD --phase contracts
Stop and review this result before running any large fixture:
  python -B scripts/verification/storage-proof/run.py --build-dir BUILD --phase pilot
  python -B scripts/verification/storage-proof/run.py --build-dir BUILD --phase seed
  python -B scripts/verification/storage-proof/run.py --build-dir BUILD --phase verify
  python -B scripts/verification/storage-proof/run.py --build-dir BUILD --phase prune
  python -B scripts/verification/storage-proof/run.py --build-dir BUILD --phase resume
  python -B scripts/verification/storage-proof/run.py --build-dir BUILD --phase reclaim
Explicit cleanup, only after evidence is reviewed and the fixture is unwanted:
  python -B scripts/verification/storage-proof/run.py --build-dir BUILD --action cleanup
Cleanup uninstalls only this run's freshly created, exact-hash proof packages.
It verifies the persisted ownership receipt before stopping/uninstalling. It
never clears/uninstalls/stops the ordinary app. Uninstallation removes the
disposable synthetic databases; host logs and reports remain. There is no
automatic uninstall on failure. A stale runtime.lock requires manual review;
the harness never steals another process's lock or selects another target.
After terminal successful instrumentation, the unchanged strict global
no-active-instrumentation check runs BEFORE any owned stop. Only then is the
exact installed-hash pair stopped; global no-active and exact pidof absence
are both required afterward and at every run preflight. Explicit cleanup alone
permits its cached own processes before the APK-verified stop. Cached processes are
not confused with active instrumentation. No existing idle validator is changed.

Contracts
Exact24 methods: all15 PriceHistoryRetentionInstrumentedTest tests plus all3
PriceHistoryWindowRepositoryInstrumentedTest tests and all6 existing/additive
CardmarketImportRoomInstrumentedTest tests, including collection-only
policy, all-owner/guest/outbox protection, rollback and cancellation. Their
complete source files are copied, not edited. Both Python and Android runner
carry the same explicit method allowlist. Zero skips, exact starts/results,
terminal instrumentation code and final OK(24 tests) are required. Status is
CONTRACT_TESTS_PASS_NOT_STORAGE_GATE. No scale reporter/13GiB claim is used.
No pilot is permitted without the retained successful contract log/guards.

Scale phases and meaning
pilot: separate 500,000-row database, 50,000 real catalog products and100 real
price sentinels, actual Room schema13/FKs/indexes. NOT a13GiB proof.
seed: separate persistent scale database; bounded5,000-row INSERT...SELECT
transactions,50,000 products, checkpoint every50,000 rows, maximum100M rows.
Stop only at a complete50,000-row day after at least13GiB of live SQLite pages
and actual main-file bytes. No padding, arbitrary BLOB inflation, sparse-file
resize, mock cursor or Kotlin object per price row. Full historical metrics,
NULL/zero/negative prices and realistic indexed fields are actual tuples.
verify: full SQLite integrity_check, foreign_key_check, full row count,
unchanged non-price tables and retained-row byte/type hash oracle.
prune: exactly32 real production batches of<=1024 examined PKs. The98 early
obsolete sentinels must really be deleted; the large pass must remain partial.
resume: after the prior process is stopped, open the same persisted database,
create a NEW production retention instance and begin a fresh idempotent pass.
This is safe restart by rescan, not an O(1) durable cursor or power-loss test.
Scale uses the explicitly approved collectionHistoryOnly=true policy. The old
false/default-policy contracts remain unchanged. The independent retained
oracle is the known newest-first PK set: day0 for50,000 bulk products, plus
days1..366 for exactly six locally referenced products (all owners, including
three tombstones), plus exact old-only and other-source sentinels =52,198 rows.
No ownership predicate from production SQL is reused for this oracle. Original timestamps
and every stored field are hashed, not just counts. User-like tables contain
all-owner/guest active/tombstone/unsynced/outbox fixtures and stay byte-exact.
reclaim: explicitly opt-in VACUUM of only this fixture after complete pruning.
Require free space >=2*current-main +2GiB, final main file strictly smaller,
freelist0, WAL0, integrity and retained hashes. It does not VACUUM app databases.

Limits and honest remaining proof
Build20min; contracts20min; pilot/prune30min; seed8h; verify/resume/reclaim4h.
Each instrumentation log has a16MiB ceiling; overflow/timeout/crash/failure is
RED with retained raw logs. Android phase deadlines are slightly shorter and
report errors. On timeout only exact owned package processes may be stopped.
An already committed failed/interrupted prune can be inspected; a fresh run
must not fabricate a completed prune marker. Initialization interrupted before
its final state marker fails closed and may need explicit cleanup/new build.

No timing-based PASS. The old measured366k-row fixture extrapolated to roughly
68.5M rows/~4h generation at13GiB; that is ONLY an estimate for a different
generator. This pilot must calibrate the actual SQL generator and free-space
budget before seed authorization. Host has ample free disk, but emulator/data
space is independently constrained. Every phase reports actual page counts,
main/WAL sizes and available/data bytes. Seed checks2GiB reserve per batch;
sampling is not an exact transient peak-space proof. Low-disk/power-loss kill,
concurrent writer and actual API26/API37 large-fixture coverage remain OPEN.
PHASE_PASS_ONLY is never an automatic complete storage/release gate. No13GiB
claim is valid until real seed+verify+prune+resume+reclaim evidence is reviewed.
