import assert from 'node:assert/strict';
import path from 'node:path';
import { lstatSync, readdirSync } from 'node:fs';
import { createReadStream } from 'node:fs';
import { createHash } from 'node:crypto';
export const ROOT = 'C:/Users/Dominik/AndroidStudioProjects/CardVault';
export const WEB = 'C:/Users/Dominik/AndroidStudioProjects/DomiDex-Web';
export const SERIAL = 'emulator-5554';
export const AVD = 'DomiDex_R27_Baseline_API36';
export const PROJECT = 'demo-domidex-privacy';
export const OWNER = 'sdk-proof-workmanager-private';
export const OTHER = 'sdk-proof-workmanager-other';
export const CLASS = 'com.dominik.cardvault.data.sync.PrivateBinderWorkManagerInstrumentedTest';
export const METHOD = 'actualPrivateWorkerRetriesAndAcknowledgesWithoutBypassingScheduler';
export const FULL_METHOD = 'actualPrivateWorkerAutomaticallyContinuesFullBudgetWithoutManualEnqueue';
const SCENARIOS = Object.freeze({
  retry: Object.freeze({ name: 'retry', method: METHOD, runtime: 'runtime',
    receiptPrefix: 'wm-proof-', instrumentationTimeoutMs: 200_000 }),
  'full-budget-continuation': Object.freeze({ name: 'full-budget-continuation', method: FULL_METHOD,
    runtime: 'runtime-full-budget-continuation', receiptPrefix: 'wm-full-proof-', instrumentationTimeoutMs: 300_000 }),
});
export function proofScenario(name = 'retry') {
  assert.equal(typeof name, 'string');
  assert.ok(Object.hasOwn(SCENARIOS, name), 'Only fixed reviewed WorkManager scenarios are allowed');
  return SCENARIOS[name];
}
export const ROOM_TABLES = Object.freeze(['catalog_cards', 'binders', 'collection_entries', 'collection_sync_outbox',
  'private_binders', 'private_binder_placements', 'collection_layout', 'collection_price_selections',
  'private_binder_sync_outbox', 'raw_price_snapshots', 'graded_market_records', 'grading_analyses',
  'app_metadata', 'catalog_sync_state']);
const PAGING_IDS = Object.freeze(Array.from({ length: 1001 }, (_, index) => `paging-${String(index).padStart(4, '0')}`));
const PAGING_PATHS = new Set(PAGING_IDS.map(id => `users/${OWNER}/privateBinders/${id}`));
export function referenceBudget(mode = 'retry') {
  const scenario = proofScenario(mode); const seen = new Set(); let ordinary = 0; let paging = 0;
  return referencePath => {
    assert.equal(typeof referencePath, 'string');
    assert.equal(seen.has(referencePath), false, 'Duplicate emulator reference'); seen.add(referencePath);
    if (scenario.name === 'full-budget-continuation' && PAGING_PATHS.has(referencePath)) {
      assert.ok(++paging <= 1001, 'Unexpected paging reference count');
    } else assert.ok(++ordinary <= 16, 'Unexpected emulator reference count');
    return { ordinary, paging };
  };
}
export function fullBudgetSeeds(runId) {
  assert.match(runId, /^[a-f0-9]{32}$/);
  return PAGING_IDS.map(id => ({ path: `users/${OWNER}/privateBinders/${id}`, data: {
    binderId: id, ownerUid: OWNER, name: `Synthetic paging binder ${id}`, description: null,
    coverStyle: 'DOMIDEX', minimumPageCount: 1, createdAtClient: 1700000000000,
    updatedAtClient: 1700000000000, lastOpenedAtClient: null, deleted: false, revision: 1,
    lastMutationId: `full-wm-${runId}-${id}`,
  } }));
}
function validateStamp(stamp) {
  assert.deepEqual(Object.keys(stamp).sort(), ['nanoseconds', 'seconds']);
  assert.ok(Number.isSafeInteger(stamp.seconds) && stamp.seconds > 1700000000);
  assert.ok(Number.isSafeInteger(stamp.nanoseconds) && stamp.nanoseconds >= 0 && stamp.nanoseconds < 1e9);
}
export function validateFullBudgetSeeds(rows, runId) {
  assert.ok(Array.isArray(rows)); const seeds = fullBudgetSeeds(runId);
  assert.equal(rows.length, seeds.length);
  const byPath = new Map(rows.map(row => [row.path, row])); assert.equal(byPath.size, rows.length);
  for (const seed of seeds) {
    const row = byPath.get(seed.path); assert.ok(row, 'Missing exact paging seed');
    validateStamp(row.data.updatedAt);
    assert.deepEqual(row, { path: seed.path, data: { ...seed.data, updatedAt: row.data.updatedAt } });
  }
}
export function validateFullBudgetServer(before, after, runId) {
  validateFullBudgetSeeds(before, runId); assert.ok(Array.isArray(after));
  assert.equal(after.length, 1002);
  const binderPath = `users/${OWNER}/privateBinders/wm-proof-binder`;
  const binder = after.filter(row => row.path === binderPath); assert.equal(binder.length, 1);
  validateStamp(binder[0].data.updatedAt);
  assert.deepEqual(binder[0], { path: binderPath, data: {
    binderId: 'wm-proof-binder', ownerUid: OWNER, name: 'Synthetic WorkManager binder', description: null,
    coverStyle: 'DOMIDEX', minimumPageCount: 1, createdAtClient: 1700000000000,
    updatedAtClient: 1700000000000, lastOpenedAtClient: null, deleted: false, revision: 1,
    lastMutationId: `wm-proof-${runId}-binder`, updatedAt: binder[0].data.updatedAt,
  } });
  // Includes exact server timestamps; no normalization that discards unknown fields or writes.
  assert.deepEqual(after.filter(row => row.path !== binderPath), before);
}
export function validateFullBudgetDeviceProof(proof, runId, packageName) {
  assert.match(runId, /^[a-f0-9]{32}$/);
  assert.equal(packageName, `com.dominik.cardvault.wmproof.r${runId}`);
  assert.equal(proof.status, 'PASS'); assert.equal(proof.kind, 'actualPrivateWorkManagerFullContinuation');
  assert.equal(proof.runId, runId); assert.equal(proof.packageName, packageName);
  assert.equal(Object.hasOwn(proof, 'failureType'), false); assert.equal(Object.hasOwn(proof, 'cleanupFailureType'), false);
  for (const key of ['bodyPassed', 'cleanupPassed', 'automaticContinuation', 'secondCycleIdempotent',
    'unchangedForeignOwner', 'boundedNoSpin', 'unchangedPagingSeeds', 'retainedOwnerPendingAcknowledged',
    'workManagerClosed', 'ownedRoomClosedAndFilesAbsent', 'offlineAfter']) assert.equal(proof[key], true, key);
  assert.equal(proof.exactWorkSpecCount, 4); assert.equal(proof.externalEnqueueCount, 2);
  assert.equal(proof.initialPagingCount, 1001); assert.equal(proof.finalServerCount, 1002);
  assert.equal(proof.heldObservationMillis, 750); assert.equal(proof.terminalObservationMillis, 1000);
  assert.deepEqual(proof.roomTables, ROOM_TABLES);
  assert.ok(Array.isArray(proof.attempts)); assert.equal(proof.attempts.length, 4);
  const ids = proof.attempts.map(attempt => attempt.id);
  for (const id of ids) assert.match(id, /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/);
  assert.equal(new Set(ids).size, 4); assert.deepEqual(proof.attempts.map(row => row.attempt), [0, 0, 0, 0]);
  for (let index = 0; index < proof.attempts.length; index++) {
    const row = proof.attempts[index];
    assert.deepEqual(Object.keys(row).sort(), ['attempt', 'elapsedMillis', 'epochMillis', 'id']);
    assert.ok(Number.isSafeInteger(row.epochMillis) && row.epochMillis > 1700000000000);
    assert.ok(Number.isSafeInteger(row.elapsedMillis) && row.elapsedMillis > 0);
    if (index) assert.ok(row.elapsedMillis > proof.attempts[index - 1].elapsedMillis);
  }
  assert.deepEqual(proof.cycleInitialIds, [ids[0], ids[2]]);
  assert.deepEqual(proof.continuationIds, [ids[1], ids[3]]);
  assert.deepEqual(proof.prerequisites, ids.map((id, index) => ({ id, prerequisiteIds: index ? [ids[index - 1]] : [] })));
  assert.deepEqual(proof.checkpoints, [
    { cycle: 1, pagingCount: 997, phase: 'rr1|1|14', cursor: 'paging-0996' },
    { cycle: 2, pagingCount: 1001, phase: 'rr1|1|14', cursor: 'paging-0996' },
  ]);
  assert.equal(proof.finalPhase, 'rr1|1|0'); assert.deepEqual(proof.finalCursors, ['', '', '', '']);
}
export const RUNNER = 'com.dominik.cardvault.data.sync.WorkManagerProofRunner';
export const DEBUG_CERT = '61e8314b147e44653b8e0d169fe6caf22760c36ee47795cd8471b434166ba854';
export function runIdentity(directory) {
  const runId = path.basename(directory);
  assert.match(runId, /^[a-f0-9]{32}$/);
  assert.equal(path.resolve(directory), path.resolve(ROOT, 'artifacts/release27-engineering-20261002/workmanager-proof', runId));
  return { runId, app: `com.dominik.cardvault.wmproof.r${runId}` };
}
export const accounts = [OTHER, OWNER].sort().map(uid => ({ uid, email: `${uid}@example.invalid`,
  emailVerified: true, disabled: false, customClaims: { appAccess: true },
  providerData: [{ providerId: 'google.com', uid: `${uid}-google`, email: `${uid}@example.invalid` }] }));
export function validateInstrumentation(output, mode = 'retry') {
  const scenario = proofScenario(mode);
  assert.doesNotMatch(output, /FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|AssumptionViolated|INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)/);
  assert.equal((output.match(/^OK \(1 test\)\r?$/gm) ?? []).length, 1);
  let fields = {}; const statuses = []; const endings = [];
  for (const line of output.split(/\r?\n/)) {
    if (line.startsWith('INSTRUMENTATION_STATUS: ')) {
      const payload = line.slice('INSTRUMENTATION_STATUS: '.length); const equals = payload.indexOf('=');
      assert.ok(equals > 0); const key = payload.slice(0, equals);
      assert.equal(Object.hasOwn(fields, key), false, 'Duplicate status field');
      fields[key] = payload.slice(equals + 1);
    } else if (line.startsWith('INSTRUMENTATION_STATUS_CODE:')) {
      const code = /^INSTRUMENTATION_STATUS_CODE: (0|1)$/.exec(line); assert.ok(code);
      assert.equal(fields.class, CLASS); assert.equal(fields.test, scenario.method);
      assert.equal(fields.numtests, '1'); assert.equal(fields.current, '1');
      statuses.push(Number(code[1])); fields = {};
    } else if (line.startsWith('INSTRUMENTATION_CODE:')) endings.push(line);
  }
  assert.deepEqual(fields, {}); assert.deepEqual(statuses, [1, 0]);
  assert.deepEqual(endings, ['INSTRUMENTATION_CODE: -1']);
  return { tests: 1, failed: 0, skipped: 0 };
}
export async function sourceSnapshot() {
  const inputs = ['app/src', 'app/schemas', 'app/build.gradle.kts', 'build.gradle.kts',
    'settings.gradle.kts', 'gradle.properties', 'gradle', 'gradlew', 'gradlew.bat',
    'app/google-services.json', 'local.properties', 'scripts/verification/workmanager-proof'];
  const preserved = ['app/build/outputs/apk', 'app/build/outputs/bundle', 'app/build/outputs/mapping',
    'releases/google-play-v26-closed-alpha-2026-09-23'];
  async function collect(roots) {
    const values = {};
    async function visit(relative) {
      const file = path.join(ROOT, relative); const stat = lstatSync(file);
      assert.equal(stat.isSymbolicLink(), false);
      if (stat.isDirectory()) for (const name of readdirSync(file).sort()) await visit(`${relative}/${name}`);
      else {
        assert.ok(stat.isFile()); const hash = createHash('sha256');
        for await (const bytes of createReadStream(file)) hash.update(bytes);
        values[relative] = { bytes: stat.size, sha256: hash.digest('hex') };
      }
    }
    for (const root of roots) await visit(root);
    return values;
  }
  return { inputs: await collect(inputs), preserved: await collect(preserved) };
}
export function validateApk(badging, signature, packageName) {
  assert.match(badging, new RegExp(`package: name='${packageName.replaceAll('.', '\\.')}' `));
  if (packageName.endsWith('.test')) assert.match(badging, /versionCode='' versionName=''/);
  else assert.match(badging, /versionCode='27' versionName='1\.0-closed-alpha\.6'/);
  assert.match(badging, /application-debuggable/);
  const names = [...signature.matchAll(/^Signer #1 certificate DN: ([^\r\n]*)\r?$/gm)];
  assert.equal(names.length, 1, 'Exactly one debug signer DN is required');
  // apksigner may print X.500 RDNs in either order. Accept the same three exact
  // fields, never additional/duplicate fields or a release identity.
  assert.deepEqual(names[0][1].split(',').map(field => field.trim()).sort(),
    ['C=US', 'CN=Android Debug', 'O=Android']);
  const fingerprints = [...signature.matchAll(/^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})\r?$/gm)];
  assert.equal(fingerprints.length, 1, 'Exactly one signer fingerprint is required');
  assert.equal(fingerprints[0][1].toLowerCase(), DEBUG_CERT);
  assert.doesNotMatch(signature, /Signer #(?!1\b)\d+/);
}
