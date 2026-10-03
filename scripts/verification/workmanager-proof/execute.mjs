// Copied to the owned emulator output directory. No .env, ADC files or production target.
import assert from 'node:assert/strict';
import { writeFileSync, appendFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
const ROOT = 'C:/Users/Dominik/AndroidStudioProjects/CardVault';
const { WEB, SERIAL, PROJECT, OWNER, OTHER, CLASS, RUNNER, accounts, runIdentity, validateInstrumentation,
  proofScenario, referenceBudget, fullBudgetSeeds, validateFullBudgetSeeds, validateFullBudgetServer } =
  await import(pathToFileURL(path.join(ROOT, 'scripts/verification/workmanager-proof/contract.mjs')));
const { assertTestEnvironment, runChild } = await import(pathToFileURL(path.join(WEB, 'tests/private-operations-local/safety.mjs')));
const { normalize, assertNoInstrumentation } = await import(pathToFileURL(path.join(WEB, 'tests/android-web-price-local/contract.mjs')));
const directory = process.env.DOMIDEX_WM_PROOF_DIRECTORY;
assert.equal(typeof directory, 'string');
const { runId, app: packageName } = runIdentity(directory);
const scenario = proofScenario(process.env.DOMIDEX_WM_PROOF_SCENARIO);
const runtime = path.join(directory, scenario.runtime); assert.equal(process.cwd(), runtime);
assert.equal(process.env.DOMIDEX_PRIVATE_TEST_ROOT, WEB);
assert.equal(process.env.DOMIDEX_PRIVATE_TEST_RUN, runtime);
const adb = process.env.DOMIDEX_WM_PROOF_ADB;
assert.equal(adb, 'C:\\Users\\Dominik\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe');
const env = { ...process.env }; delete env.FIREBASE_CONFIG; delete process.env.FIREBASE_CONFIG;
assertTestEnvironment(env);
const require = createRequire(path.join(WEB, 'package.json'));
const { initializeApp, applicationDefault, deleteApp } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const app = initializeApp({ projectId: PROJECT, credential: applicationDefault() }, 'wm-proof-admin-' + runId);
const db = getFirestore(app); const auth = getAuth(app);
const proof = { project: PROJECT, runId, scenario: scenario.name, verified: false, before: null, after: null,
  accountsUnchanged: false, errors: [] };
const save = () => writeFileSync(path.join(runtime, 'server-proof.json'), JSON.stringify(proof, null, 2));
async function authRows() {
  const all = await auth.listUsers(3); assert.equal(all.pageToken, undefined);
  return all.users.map(user => ({ uid: user.uid, email: user.email, emailVerified: user.emailVerified,
    disabled: user.disabled, customClaims: user.customClaims,
    providerData: user.providerData.map(({ providerId, uid, email }) => ({ providerId, uid, email }))
  })).sort((left, right) => left.uid.localeCompare(right.uid));
}
async function databaseRows() {
  const rows = []; let groups = 0; const countReference = referenceBudget(scenario.name);
  async function visit(collection, depth) {
    assert.ok(depth <= 3 && ++groups <= 12, 'Unexpected emulator collection tree');
    for (const reference of await collection.listDocuments()) {
      countReference(reference.path);
      const document = await reference.get();
      if (document.exists) rows.push({ path: reference.path, data: normalize(document.data()) });
      for (const child of await reference.listCollections()) await visit(child, depth + 1);
    }
  }
  for (const collection of await db.listCollections()) await visit(collection, 0);
  return rows.sort((a, b) => a.path.localeCompare(b.path));
}
async function noInstrumentation() {
  let output = '';
  const result = await runChild(adb, ['-s', SERIAL, 'shell', 'dumpsys', 'activity', 'processes'], {
    cwd: runtime, env, timeoutMs: 15_000, onOutput: chunk => { output += String(chunk); } });
  assert.equal(result.code, 0); assertNoInstrumentation(output);
}
try {
  await noInstrumentation();
  assert.deepEqual(await authRows(), []);
  assert.deepEqual(await databaseRows(), []);
  for (const account of accounts) {
    const { customClaims, providerData, ...identity } = account;
    await auth.createUser(identity);
    await auth.updateUser(identity.uid, { providerToLink: providerData[0] });
    await auth.setCustomUserClaims(identity.uid, customClaims);
  }
  assert.deepEqual(await authRows(), accounts);
  assert.deepEqual(await databaseRows(), []);
  if (scenario.name === 'full-budget-continuation') {
    const seeds = fullBudgetSeeds(runId);
    proof.seedStartedAt = new Date().toISOString();
    // Only this synthetic demo owner and the 1001 exact reviewed paging IDs are seeded.
    // Each transform is produced by the emulator server, never a fabricated Timestamp.
    for (let start = 0; start < seeds.length; start += 400) {
      const batch = db.batch();
      for (const row of seeds.slice(start, start + 400)) batch.create(db.doc(row.path), {
        ...row.data, updatedAt: FieldValue.serverTimestamp(),
      });
      await batch.commit();
    }
    proof.seedFinishedAt = new Date().toISOString();
    proof.before = await databaseRows(); validateFullBudgetSeeds(proof.before, runId);
    const earliest = Date.parse(proof.seedStartedAt) - 2000;
    const latest = Date.parse(proof.seedFinishedAt) + 2000;
    for (const row of proof.before) {
      const stamp = row.data.updatedAt;
      const milliseconds = stamp.seconds * 1000 + Math.floor(stamp.nanoseconds / 1e6);
      assert.ok(milliseconds >= earliest && milliseconds <= latest, 'Seed must retain a fresh server timestamp');
    }
  } else { proof.before = await databaseRows(); assert.deepEqual(proof.before, []); }
  save();
  let output = '';
  const result = await runChild(adb, ['-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-r',
    '-e', 'class', `${CLASS}#${scenario.method}`, '-e', 'wmProofMode', 'demo-loopback-only', '-e', 'wmProofRun', runId,
    `${packageName}.test/${RUNNER}`], {
    cwd: runtime, env, timeoutMs: scenario.instrumentationTimeoutMs, onOutput: chunk => {
      output += String(chunk); appendFileSync(path.join(runtime, 'instrumentation.log'), chunk); process.stdout.write(chunk);
    } });
  proof.androidResult = result;
  // Always independently retain the actual remote state even if the instrumentation fails.
  proof.after = await databaseRows(); save();
  assert.equal(result.code, 0); proof.android = validateInstrumentation(output, scenario.name);
  if (scenario.name === 'full-budget-continuation') validateFullBudgetServer(proof.before, proof.after, runId);
  else {
  assert.equal(proof.after.length, 1);
  const row = proof.after[0]; assert.equal(row.path, `users/${OWNER}/privateBinders/wm-proof-binder`);
  const stamp = row.data.updatedAt;
  assert.deepEqual(Object.keys(stamp).sort(), ['nanoseconds', 'seconds']);
  assert.ok(Number.isSafeInteger(stamp.seconds) && stamp.seconds > 1700000000);
  assert.ok(Number.isSafeInteger(stamp.nanoseconds) && stamp.nanoseconds >= 0 && stamp.nanoseconds < 1e9);
  assert.deepEqual(row.data, { binderId: 'wm-proof-binder', ownerUid: OWNER, name: 'Synthetic WorkManager binder',
    description: null, coverStyle: 'DOMIDEX', minimumPageCount: 1, createdAtClient: 1700000000000,
    updatedAtClient: 1700000000000, lastOpenedAtClient: null, deleted: false, revision: 1,
    lastMutationId: `wm-proof-${runId}-binder`, updatedAt: stamp });
  }
  assert.deepEqual(await authRows(), accounts); proof.accountsUnchanged = true;
  await noInstrumentation(); proof.verified = true;
} catch (error) {
  proof.errors.push(error.message); process.exitCode = 1;
  try { proof.after = await databaseRows(); } catch (readError) { proof.errors.push(readError.message); }
} finally {
  try { await db.terminate(); } catch (error) { proof.errors.push(error.message); process.exitCode = 1; }
  try { await deleteApp(app); } catch (error) { proof.errors.push(error.message); process.exitCode = 1; }
  if (proof.errors.length) proof.verified = false;
  save(); console.log(`WorkManager independent server proof: verified=${proof.verified}`);
}
