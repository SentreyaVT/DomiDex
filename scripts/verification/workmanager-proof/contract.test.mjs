import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ROOT, CLASS, METHOD, DEBUG_CERT, runIdentity, validateApk, validateInstrumentation } from './contract.mjs';
import { OWNER, OTHER, FULL_METHOD, ROOM_TABLES, proofScenario, referenceBudget, fullBudgetSeeds,
  validateFullBudgetSeeds, validateFullBudgetServer, validateFullBudgetDeviceProof } from './contract.mjs';
const id = 'a'.repeat(32);
const base = `${ROOT}/artifacts/release27-engineering-20261002/workmanager-proof/`;
const fullMode = 'full-budget-continuation';
const fullPackage = `com.dominik.cardvault.wmproof.r${id}`;
function fullDeviceFixture() {
  const ids = [1, 2, 3, 4].map(value => `00000000-0000-4000-8000-${String(value).padStart(12, '0')}`);
  return {
    status: 'PASS', kind: 'actualPrivateWorkManagerFullContinuation', runId: id, packageName: fullPackage,
    bodyPassed: true, cleanupPassed: true, automaticContinuation: true, secondCycleIdempotent: true,
    unchangedForeignOwner: true, boundedNoSpin: true, unchangedPagingSeeds: true,
    retainedOwnerPendingAcknowledged: true, workManagerClosed: true, ownedRoomClosedAndFilesAbsent: true,
    offlineAfter: true, exactWorkSpecCount: 4, externalEnqueueCount: 2,
    initialPagingCount: 1001, finalServerCount: 1002, heldObservationMillis: 750, terminalObservationMillis: 1000,
    roomTables: [...ROOM_TABLES], attempts: ids.map((workId, index) => ({ id: workId, attempt: 0,
      elapsedMillis: 1000 + 2000 * index, epochMillis: 1800000000000 + 2000 * index })),
    cycleInitialIds: [ids[0], ids[2]], continuationIds: [ids[1], ids[3]],
    prerequisites: ids.map((workId, index) => ({ id: workId, prerequisiteIds: index ? [ids[index - 1]] : [] })),
    checkpoints: [
      { cycle: 1, pagingCount: 997, phase: 'rr1|1|14', cursor: 'paging-0996' },
      { cycle: 2, pagingCount: 1001, phase: 'rr1|1|14', cursor: 'paging-0996' },
    ], finalPhase: 'rr1|1|0', finalCursors: ['', '', '', ''],
  };
}
function fullServerFixture() {
  const before = fullBudgetSeeds(id).map(row => ({ path: row.path, data: {
    ...row.data, updatedAt: { seconds: 1800000000, nanoseconds: 123000000 },
  } })).sort((a, b) => a.path.localeCompare(b.path));
  const after = [...structuredClone(before), { path: `users/${OWNER}/privateBinders/wm-proof-binder`, data: {
    binderId: 'wm-proof-binder', ownerUid: OWNER, name: 'Synthetic WorkManager binder', description: null,
    coverStyle: 'DOMIDEX', minimumPageCount: 1, createdAtClient: 1700000000000,
    updatedAtClient: 1700000000000, lastOpenedAtClient: null, deleted: false, revision: 1,
    lastMutationId: `wm-proof-${id}-binder`, updatedAt: { seconds: 1800000001, nanoseconds: 456000000 },
  } }].sort((a, b) => a.path.localeCompare(b.path));
  return { before, after };
}
function instrumentationFor(method) {
  const group = code => `INSTRUMENTATION_STATUS: class=${CLASS}\nINSTRUMENTATION_STATUS: test=${method}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS_CODE: ${code}`;
  return `${group(1)}\n${group(0)}\nOK (1 test)\nINSTRUMENTATION_CODE: -1`;
}
test('fixed scenario registry preserves original default and isolates the full-budget receipt and method', () => {
  assert.deepEqual(proofScenario(), { name: 'retry', method: METHOD, runtime: 'runtime',
    receiptPrefix: 'wm-proof-', instrumentationTimeoutMs: 200000 });
  assert.deepEqual(proofScenario(fullMode), { name: fullMode, method: FULL_METHOD,
    runtime: 'runtime-full-budget-continuation', receiptPrefix: 'wm-full-proof-', instrumentationTimeoutMs: 300000 });
  assert.ok(Object.isFrozen(proofScenario(fullMode)));
  for (const mode of ['', 'full', 'runtime', '../full-budget-continuation', 'constructor', '__proto__',
    'FULL-BUDGET-CONTINUATION', fullMode + ' ', null, 1, {}]) assert.throws(() => proofScenario(mode));
  assert.equal(validateInstrumentation(instrumentationFor(METHOD)).tests, 1);
  assert.equal(validateInstrumentation(instrumentationFor(FULL_METHOD), fullMode).tests, 1);
  assert.throws(() => validateInstrumentation(instrumentationFor(FULL_METHOD)));
  assert.throws(() => validateInstrumentation(instrumentationFor(METHOD), fullMode));
  assert.throws(() => validateInstrumentation(instrumentationFor(FULL_METHOD), 'not-reviewed'));
  for (const failure of ['FAILURES!!!', 'AssumptionViolated', 'Process crashed', 'INSTRUMENTATION_STATUS_CODE: -3']) {
    assert.throws(() => validateInstrumentation(instrumentationFor(FULL_METHOD) + '\n' + failure, fullMode));
  }
});
test('full-budget allowance admits only 1001 exact seed references while ordinary limit remains 16', () => {
  const count = referenceBudget(fullMode);
  const seeds = fullBudgetSeeds(id);
  assert.equal(seeds.length, 1001);
  assert.equal(seeds[0].path, `users/${OWNER}/privateBinders/paging-0000`);
  assert.equal(seeds.at(-1).path, `users/${OWNER}/privateBinders/paging-1000`);
  seeds.forEach(row => count(row.path));
  let last;
  for (let index = 0; index < 16; index++) last = count(`sentinel/s${index}`);
  assert.deepEqual(last, { ordinary: 16, paging: 1001 });
  assert.throws(() => count('sentinel/overflow'));
  assert.throws(() => count(seeds[0].path), /Duplicate/);
  const defaultCount = referenceBudget();
  seeds.slice(0, 16).forEach(row => defaultCount(row.path));
  assert.throws(() => defaultCount(seeds[16].path));
  for (const invalid of [`users/${OTHER}/privateBinders/paging-0000`, `users/${OWNER}/privateBinders/paging-1001`,
    `users/${OWNER}/privateBinders/paging-001`, `users/${OWNER}/privateBinders/paging-00000`,
    `users/${OWNER}/privateBinders/paging-0000/extra/x`, `users/${OWNER}/privateBinders//paging-0000`,
    `users/${OWNER}/privateBinders/paging-0000 `, `users/${OWNER}/privateBinders/PAGING-0000`]) {
    const restricted = referenceBudget(fullMode);
    for (let index = 0; index < 16; index++) restricted(`sentinel/s${index}`);
    assert.throws(() => restricted(invalid), 'Unexpected path must consume ordinary, not paging, allowance');
  }
  assert.throws(() => fullBudgetSeeds('not-a-run'));
  assert.throws(() => referenceBudget('anything-else'));
});
test('full-budget server validation rejects missing, duplicate, foreign, altered, or additional documents and timestamps', () => {
  const valid = fullServerFixture();
  validateFullBudgetSeeds(valid.before, id); validateFullBudgetServer(valid.before, valid.after, id);
  const mutations = [
    rows => rows.before.pop(),
    rows => rows.after.pop(),
    rows => rows.before.push(structuredClone(rows.before[0])),
    rows => { rows.before[1] = structuredClone(rows.before[0]); },
    rows => { rows.after[1] = structuredClone(rows.after[0]); },
    rows => { rows.before[0].path = rows.before[0].path.replace(OWNER, OTHER); },
    rows => { rows.before[0].data.ownerUid = OTHER; },
    rows => { rows.before[0].data.revision = 2; },
    rows => { rows.before[0].data.lastMutationId = 'unrelated'; },
    rows => { rows.before[0].data.unexpected = true; },
    rows => { rows.before[0].data.updatedAt.nanoseconds = 1e9; },
    rows => { rows.before[0].data.updatedAt.seconds = 1700000000; },
    rows => { rows.before[0].data.updatedAt.extra = 'hidden'; },
    rows => { rows.after[0].data.updatedAt.nanoseconds++; },
    rows => { rows.after[0].data.name = 'unexpected write'; },
    rows => { rows.after.at(-1).data.lastMutationId = 'wrong-ACK'; },
    rows => { rows.after.at(-1).data.revision = 0; },
    rows => { rows.after.at(-1).data.updatedAt = { seconds: 1800000001, nanoseconds: -1 }; },
    rows => { rows.after.at(-1).path = `users/${OTHER}/privateBinders/wm-proof-binder`; },
    rows => rows.after.push({ path: `accountAccess/${OWNER}`, data: { appAccess: true } }),
  ];
  for (const mutate of mutations) {
    const candidate = structuredClone(valid); mutate(candidate);
    assert.throws(() => validateFullBudgetServer(candidate.before, candidate.after, id));
  }
  assert.throws(() => validateFullBudgetServer(valid.before, valid.after, 'b'.repeat(32)));
});
test('full-budget device receipt requires four true WorkManager invocations, exact dependencies and bounded checkpoints', () => {
  const valid = fullDeviceFixture(); validateFullBudgetDeviceProof(valid, id, fullPackage);
  for (const key of ['bodyPassed', 'cleanupPassed', 'automaticContinuation', 'secondCycleIdempotent',
    'unchangedForeignOwner', 'boundedNoSpin', 'unchangedPagingSeeds', 'retainedOwnerPendingAcknowledged',
    'workManagerClosed', 'ownedRoomClosedAndFilesAbsent', 'offlineAfter']) {
    for (const value of [false, undefined, 'true']) {
      const candidate = structuredClone(valid); candidate[key] = value;
      assert.throws(() => validateFullBudgetDeviceProof(candidate, id, fullPackage), key);
    }
  }
  const mutations = [
    proof => { proof.status = 'RUNNING'; },
    proof => { proof.kind = 'actualPrivateWorkManagerRetry'; },
    proof => { proof.runId = 'b'.repeat(32); },
    proof => { proof.packageName = 'com.dominik.cardvault'; },
    proof => { proof.failureType = 'HiddenFailure'; },
    proof => { proof.cleanupFailureType = 'HiddenFailure'; },
    proof => { proof.exactWorkSpecCount = 5; },
    proof => { proof.externalEnqueueCount = 4; },
    proof => { proof.initialPagingCount = 1000; },
    proof => { proof.finalServerCount = 1001; },
    proof => { proof.heldObservationMillis = 0; },
    proof => { proof.terminalObservationMillis = 0; },
    proof => proof.roomTables.pop(),
    proof => proof.attempts.pop(),
    proof => proof.attempts.push(structuredClone(proof.attempts[0])),
    proof => { proof.attempts[1].attempt = 1; },
    proof => { proof.attempts[1].id = proof.attempts[0].id; },
    proof => { proof.attempts[1].id = 'not-a-work-UUID'; },
    proof => { proof.attempts[1].elapsedMillis = proof.attempts[0].elapsedMillis; },
    proof => { proof.attempts[1].epochMillis = 0; },
    proof => { proof.attempts[1].unexpected = true; },
    proof => { proof.cycleInitialIds = proof.continuationIds; },
    proof => { proof.continuationIds = proof.cycleInitialIds; },
    proof => { proof.prerequisites[1].prerequisiteIds = []; },
    proof => { proof.prerequisites[2].prerequisiteIds = [proof.attempts[0].id]; },
    proof => proof.prerequisites[3].prerequisiteIds.push(proof.attempts[0].id),
    proof => { proof.checkpoints[0].pagingCount = 998; },
    proof => { proof.checkpoints[1].pagingCount = 997; },
    proof => { proof.checkpoints[0].phase = 'rr1|1|0'; },
    proof => { proof.checkpoints[0].cursor = 'paging-0997'; },
    proof => { proof.finalPhase = 'rr1|1|14'; },
    proof => { proof.finalCursors[0] = 'paging-0996'; },
  ];
  for (const mutate of mutations) {
    const candidate = structuredClone(valid); mutate(candidate);
    assert.throws(() => validateFullBudgetDeviceProof(candidate, id, fullPackage));
  }
  assert.throws(() => validateFullBudgetDeviceProof(valid, id, 'com.dominik.cardvault'));
});
test('run identity accepts only its fixed UUID output location and never the ordinary app', () => {
  assert.equal(runIdentity(base + id).app, 'com.dominik.cardvault.wmproof.r' + id);
  for (const directory of [base + '../' + id, base + 'release', 'C:/tmp/' + id, base + 'A'.repeat(32)]) {
    assert.throws(() => runIdentity(directory));
  }
});
test('instrumentation rejects failures and skipped/assumed tests, not merely shell success', () => {
  const group = code => `INSTRUMENTATION_STATUS: class=${CLASS}\nINSTRUMENTATION_STATUS: test=${METHOD}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS_CODE: ${code}`;
  const good = `${group(1)}\n${group(0)}\nOK (1 test)\nINSTRUMENTATION_CODE: -1`;
  assert.equal(validateInstrumentation(good).tests, 1);
  for (const extra of ['FAILURES!!!', 'INSTRUMENTATION_STATUS_CODE: -3', 'Process crashed', 'AssumptionViolated']) {
    assert.throws(() => validateInstrumentation(good + '\n' + extra));
  }
  assert.throws(() => validateInstrumentation(good.replace('OK (1 test)', 'OK (0 tests)')));
  assert.throws(() => validateInstrumentation(good.replace(group(1) + '\n', '')));
  assert.throws(() => validateInstrumentation(group(1) + '\n' + good));
  assert.throws(() => validateInstrumentation(good.replace(CLASS, 'another.Test')));
  assert.throws(() => validateInstrumentation(good.replace('numtests=1', 'numtests=2')));
  assert.throws(() => validateInstrumentation(good.replace('current=1', 'current=2')));
  assert.throws(() => validateInstrumentation(good + '\nINSTRUMENTATION_CODE: -1'));
});
test('APK identity and normal debug certificate are mandatory', () => {
  const pkg = runIdentity(base + id).app;
  const badging = `package: name='${pkg}' versionCode='27' versionName='1.0-closed-alpha.6'\napplication-debuggable`;
  const signature = `Signer #1 certificate DN: CN=Android Debug, O=Android, C=US\nSigner #1 certificate SHA-256 digest: ${DEBUG_CERT}`;
  for (const ordered of [signature, signature.replace('CN=Android Debug, O=Android, C=US', 'C=US, O=Android, CN=Android Debug')]) {
    validateApk(badging, ordered, pkg);
    validateApk(badging.replace(pkg, pkg + '.test').replace("versionCode='27' versionName='1.0-closed-alpha.6'", "versionCode='' versionName=''"), ordered, pkg + '.test');
    validateApk(badging, ordered.replaceAll('\n', '\r\n').replace(DEBUG_CERT, DEBUG_CERT.toUpperCase()), pkg);
  }
  assert.throws(() => validateApk(badging.replace(pkg, 'com.dominik.cardvault'), signature, pkg));
  assert.throws(() => validateApk(badging.replace("versionCode='27'", "versionCode='26'"), signature, pkg));
  assert.throws(() => validateApk(badging, signature.replace(DEBUG_CERT, '0'.repeat(64)), pkg));
  for (const dn of ['CN=DomiDex Release, O=Android, C=US', 'CN=Android Debug, O=Android, C=US, OU=Release',
    'CN=Android Debug, O=Android, C=US, CN=Android Debug', 'CN=Android Debug, C=US',
    'CN=Android Debug, O=Android, C=DE', 'CN=Android Debug, O=Android, C=US,']) {
    assert.throws(() => validateApk(badging, signature.replace('CN=Android Debug, O=Android, C=US', dn), pkg));
  }
  for (const extra of ['Signer #2 certificate DN: CN=Android Debug, O=Android, C=US',
    'Signer #3 certificate DN: CN=Android Debug, O=Android, C=US',
    'Signer #1 certificate DN: C=US, O=Android, CN=Android Debug',
    `Signer #1 certificate SHA-256 digest: ${DEBUG_CERT}`]) {
    assert.throws(() => validateApk(badging, signature + '\n' + extra, pkg));
  }
  assert.throws(() => validateApk(badging, signature.replace(DEBUG_CERT, DEBUG_CERT + '0'), pkg));
});
