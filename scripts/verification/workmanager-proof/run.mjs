import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, writeFileSync, appendFileSync, copyFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { ROOT, WEB, SERIAL, AVD, PROJECT, RUNNER, runIdentity, validateApk, validateInstrumentation,
  sourceSnapshot, proofScenario, validateFullBudgetDeviceProof } from './contract.mjs';
const { PORTS, requireFile, readJson, validateMetadata, verifyCache, sha256, assertPortsFree,
  isolatedEnvironment, assertTestEnvironment, firebaseConfiguration, runChild } = await import(
    pathToFileURL(path.join(WEB, 'tests/private-operations-local/safety.mjs')));
const { assertNoInstrumentation } = await import(pathToFileURL(path.join(WEB, 'tests/android-web-price-local/contract.mjs')));
assert.equal(process.platform, 'win32');
assert.ok(process.argv.length === 3 || process.argv.length === 4,
  'Pass the successful isolated build directory and optionally full-budget-continuation');
if (process.argv.length === 4) assert.equal(process.argv[3], 'full-budget-continuation');
const scenario = proofScenario(process.argv[3] ?? 'retry');
const directory = path.resolve(process.argv[2]);
const { runId, app } = runIdentity(directory);
const build = readJson(path.join(directory, 'build-result.json'));
assert.equal(build.exitCode, 0); assert.equal(build.inputsAndOldOutputsUnchanged, true);
assert.equal(build.proofClassesVerified, true);
assert.equal(build.runId, runId); assert.equal(build.package, app);
const before = readJson(path.join(directory, 'before.json'));
const here = path.dirname(fileURLToPath(import.meta.url));
const java = requireFile('C:/Program Files/Android/Android Studio/jbr/bin/java.exe');
const sdk = 'C:/Users/Dominik/AppData/Local/Android/Sdk';
const adb = requireFile(path.join(sdk, 'platform-tools/adb.exe'));
const aapt = requireFile(path.join(sdk, 'build-tools/36.0.0/aapt2.exe'));
const signer = requireFile(path.join(sdk, 'build-tools/36.0.0/lib/apksigner.jar'));
const cli = requireFile('C:/Users/Dominik/AppData/Roaming/npm/node_modules/firebase-tools/lib/bin/firebase.js');
const cliRoot = path.resolve(path.dirname(cli), '../..');
const cliPackage = readJson(path.join(cliRoot, 'package.json'));
const metadata = readJson(path.join(cliRoot, 'lib/emulator/downloadableEmulatorInfo.json'));
const jars = validateMetadata(cliPackage.version, metadata);
const cache = 'C:/Users/Dominik/.cache/firebase/emulators';
await verifyCache(cache, jars);
await assertPortsFree();
assert.deepEqual(await sourceSnapshot(), { inputs: before.inputs, preserved: before.preserved }, 'Complete inventory must match');
const injectedGate = path.join(directory, 'injected-source/PrivatePullDemoGrpcGate.kt');
assert.equal(await sha256(injectedGate), before.injectedGateSha256);
assert.equal(build.injectedGateSha256, before.injectedGateSha256);
const harnessInputs = Object.fromEntries(await Promise.all([
  'tests/private-operations-local/safety.mjs', 'tests/private-operations-local/network-preload.cjs',
  'tests/android-web-price-local/contract.mjs', 'firestore.rules', 'storage.rules', 'package.json', 'package-lock.json',
].map(async relative => [relative, await sha256(requireFile(path.join(WEB, relative)))])));
for (const file of ['firestore.rules', 'storage.rules']) {
  assert.equal(await sha256(path.join(ROOT, file)), harnessInputs[file], 'Identical rules required');
}
const runtime = path.join(directory, scenario.runtime); mkdirSync(runtime); // Never overwrite a previous run.
for (const name of ['temp', 'home', 'appdata', 'localappdata', 'xdg', 'cloudsdk']) mkdirSync(path.join(runtime, name));
const env = isolatedEnvironment(process.env, { directory: runtime, root: WEB, java, cache,
  preload: path.join(WEB, 'tests/private-operations-local/network-preload.cjs') });
env.DOMIDEX_WM_PROOF_DIRECTORY = directory;
env.DOMIDEX_WM_PROOF_ADB = adb;
env.DOMIDEX_WM_PROOF_SCENARIO = scenario.name;
assertTestEnvironment(env);
function command(executable, args, accepted = [0], timeout = 20_000) {
  const result = spawnSync(executable, args, { cwd: ROOT, env, windowsHide: true, shell: false,
    encoding: 'utf8', timeout, maxBuffer: 8 * 1024 * 1024 });
  assert.equal(result.error, undefined, 'Local command could not complete');
  assert.ok(accepted.includes(result.status), `Local command failed: ${path.basename(executable)} ${args[0]}`);
  return result.stdout.trim();
}
const device = (args, accepted, timeout) => command(adb, ['-s', SERIAL, ...args], accepted, timeout);
function offline() {
  assert.equal(device(['get-state']), 'device');
  assert.equal(device(['shell', 'getprop', 'ro.boot.qemu.avd_name']), AVD);
  assert.equal(device(['shell', 'getprop', 'ro.kernel.qemu']), '1');
  assert.equal(device(['shell', 'getprop', 'ro.build.version.sdk']), '36');
  assert.equal(device(['shell', 'settings', 'get', 'global', 'airplane_mode_on']), '1');
  assert.equal(device(['shell', 'settings', 'get', 'global', 'wifi_on']), '0');
  assert.match(device(['shell', 'dumpsys', 'connectivity']), /Active default network: none/);
  assertNoInstrumentation(device(['shell', 'dumpsys', 'activity', 'processes']));
}
offline(); assert.equal(device(['reverse', '--list']), '');
const ordinaryPackage = 'com.dominik.cardvault';
function installedHash(pkg) {
  const apk = device(['shell', 'pm', 'path', pkg]);
  assert.match(apk, /^package:\/data\/app\/[^\r\n]+\/base\.apk$/);
  return device(['shell', 'sha256sum', apk.slice(8)]).split(/\s+/)[0];
}
const ordinaryBefore = installedHash(ordinaryPackage);
const packages = [app, app + '.test'];
const paths = ['outputs/apk/debug/app-debug.apk', 'outputs/apk/androidTest/debug/app-debug-androidTest.apk'];
for (let index = 0; index < 2; index++) {
  assert.equal(device(['shell', 'pm', 'path', packages[index]], [0, 1]), '', 'Run-scoped package already present');
  const file = requireFile(path.join(directory, 'app-build', paths[index]));
  assert.equal(await sha256(file), build.artifacts[paths[index]].sha256);
  validateApk(command(aapt, ['dump', 'badging', file]),
    command(java, ['-jar', signer, 'verify', '--verbose', '--print-certs', file]), packages[index]);
  const xml = command(aapt, ['dump', 'xmltree', file, '--file', 'AndroidManifest.xml']);
  if (index === 0) {
    assert.doesNotMatch(xml, /FirebaseInitProvider|WorkManagerInitializer|MainActivity|SharedBinderActivity/);
    assert.match(xml, /debug_emulator_network_security|networkSecurityConfig/);
  } else {
    assert.ok(xml.includes(RUNNER)); assert.ok(xml.includes(app));
  }
}
for (const file of ['firestore.rules', 'storage.rules']) copyFileSync(path.join(WEB, file), path.join(runtime, file));
copyFileSync(path.join(here, 'execute.mjs'), path.join(runtime, 'execute.mjs'));
const config = firebaseConfiguration(); delete config.emulators.storage;
writeFileSync(path.join(runtime, 'firebase.json'), JSON.stringify(config, null, 2), { flag: 'wx' });
const receipt = { project: PROJECT, runId, package: app, serial: SERIAL, avd: AVD, scenario: scenario.name,
  startedAt: new Date().toISOString(), build, jars, harnessInputs, installed: [], ownedReverse: [],
  ordinaryBefore, ordinaryAfter: null, result: null, serverVerified: false, cleanupPackages: [], errors: [] };
const receiptPath = path.join(runtime, 'manifest.json');
const save = () => writeFileSync(receiptPath, JSON.stringify(receipt, null, 2));
save();
console.log(`Isolated WorkManager proof: ${runtime}`);
try {
  for (let index = 0; index < 2; index++) {
    const file = path.join(directory, 'app-build', paths[index]);
    assert.match(device(['install', '-t', '--no-streaming', file], [0], 90_000), /Success/);
    receipt.installed.push(packages[index]); save();
    assert.equal(installedHash(packages[index]), build.artifacts[paths[index]].sha256);
  }
  for (const port of [PORTS.firestore, PORTS.auth]) {
    device(['reverse', '--no-rebind', `tcp:${port}`, `tcp:${port}`]); receipt.ownedReverse.push(port); save();
  }
  receipt.result = await runChild(process.execPath, [cli, 'emulators:exec', '--only', 'auth,firestore',
    '--project', PROJECT, '--config', path.join(runtime, 'firebase.json'), 'node execute.mjs'], {
    cwd: runtime, env, timeoutMs: 360_000, onOutput: chunk => {
      appendFileSync(path.join(runtime, 'emulator.log'), chunk); process.stdout.write(chunk);
    } });
  const deviceProof = JSON.parse(device(['shell', 'run-as', app, 'cat', `cache/${scenario.receiptPrefix}${runId}.json`]));
  writeFileSync(path.join(runtime, 'device-proof.json'), JSON.stringify(deviceProof, null, 2), { flag: 'wx' });
  assert.equal(receipt.result.code, 0);
  assert.equal(deviceProof.status, 'PASS'); assert.equal(deviceProof.runId, runId);
  assert.equal(deviceProof.packageName, app);
  if (scenario.name === 'full-budget-continuation') validateFullBudgetDeviceProof(deviceProof, runId, app);
  else {
  for (const key of ['bodyPassed', 'cleanupPassed', 'constraintsBlockedWithoutInvocation', 'retryObserved',
    'backoffUsedRealClock', 'secondEnqueueIdempotent', 'unchangedForeignOwner', 'workManagerClosed',
    'ownedRoomClosedAndFilesAbsent', 'offlineAfter']) assert.equal(deviceProof[key], true, key);
  assert.equal(deviceProof.exactWorkSpecCount, 2);
  assert.deepEqual(deviceProof.attempts.map(attempt => attempt.attempt), [0, 1, 0]);
  assert.equal(deviceProof.attempts[0].id, deviceProof.attempts[1].id);
  assert.notEqual(deviceProof.attempts[0].id, deviceProof.attempts[2].id);
  assert.equal(deviceProof.retryEligibleEpochMillis - deviceProof.retryLastEnqueueEpochMillis, 30_000);
  assert.ok(deviceProof.secondAttemptEpochMillis >= deviceProof.retryEligibleEpochMillis);
  }
  receipt.android = validateInstrumentation(readFileSync(path.join(runtime, 'instrumentation.log'), 'utf8'), scenario.name);
  const server = readJson(path.join(runtime, 'server-proof.json'));
  assert.equal(server.verified, true); assert.equal(server.runId, runId);
  assert.equal(server.scenario, scenario.name);
  receipt.serverVerified = true;
} catch (error) { receipt.errors.push(error.message); }
finally {
  try {
    offline();
    for (const pkg of [...receipt.installed].reverse()) {
      assert.ok(packages.includes(pkg) && pkg.startsWith('com.dominik.cardvault.wmproof.r' + runId));
      const index = packages.indexOf(pkg);
      assert.equal(installedHash(pkg), build.artifacts[paths[index]].sha256, 'Changed package not removed');
      assert.equal(device(['uninstall', pkg]), 'Success'); // Only this run's disposable synthetic data.
      receipt.cleanupPackages.push(pkg);
    }
    receipt.ordinaryAfter = installedHash(ordinaryPackage);
    assert.equal(receipt.ordinaryAfter, ordinaryBefore);
  } catch (error) { receipt.errors.push('package cleanup: ' + error.message); }
  for (const port of [...receipt.ownedReverse].reverse()) {
    try {
      const rows = device(['reverse', '--list']).split(/\r?\n/).map(row => row.trim().split(/\s+/))
        .filter(row => row.length === 3 && row[1] === `tcp:${port}`);
      assert.equal(rows.length, 1); assert.equal(rows[0][2], `tcp:${port}`);
      device(['reverse', '--remove', `tcp:${port}`]);
    } catch (error) { receipt.errors.push('reverse cleanup: ' + error.message); }
  }
  try {
    assert.equal(device(['reverse', '--list']), ''); await assertPortsFree();
    assert.deepEqual(await sourceSnapshot(), { inputs: before.inputs, preserved: before.preserved });
    assert.equal(await sha256(injectedGate), before.injectedGateSha256);
    for (const [relative, expected] of Object.entries(harnessInputs)) assert.equal(await sha256(path.join(WEB, relative)), expected);
    receipt.inputsUnchanged = true;
  } catch (error) { receipt.errors.push('final checks: ' + error.message); }
  receipt.finishedAt = new Date().toISOString();
  receipt.exitCode = receipt.errors.length || !receipt.serverVerified ? 1 : 0;
  save(); console.log(`WorkManager manifest: ${receiptPath}`);
  process.exitCode = receipt.exitCode;
}
