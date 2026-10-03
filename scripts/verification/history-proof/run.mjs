import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, writeFileSync, appendFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { ROOT, SERIAL, AVD, CLASS, RUNNER, identity, modeFor, snapshot, sha256, validateApk,
  validateInstrumentation, validateReport } from './contract.mjs';
import { PRIVATE_CLASS, privateModeFor, validatePrivateInstrumentation } from './private-contract.mjs';
assert.equal(process.platform, 'win32');
assert.ok(process.argv.length === 3 || (process.argv.length === 4 &&
  ['--private-red', '--private-green'].includes(process.argv[3])), 'Build directory plus optional explicit --private-red/--private-green');
const privateMode = process.argv[3]?.slice(2) ?? null;
const sourceClass = privateMode ? 'PrivateBinderFirstPositiveInstrumentedTest' : 'Release27RoomScaleInstrumentedTest';
const directory = path.resolve(process.argv[2]); const { id, app } = identity(directory);
const json = file => JSON.parse(readFileSync(file, 'utf8'));
const before = json(path.join(directory, 'before.json')); const build = json(path.join(directory, 'build-result.json'));
assert.equal(build.exitCode, 0); assert.equal(build.inputsAndOldOutputsUnchanged, true);
assert.equal(build.proofClassesVerified, true); assert.equal(build.runId, id); assert.equal(build.package, app);
assert.equal(before.runId, id); assert.equal(before.package, app);
assert.equal(build.buildMode ?? 'history', privateMode ? 'private-details' : 'history');
assert.equal(before.buildMode ?? 'history', build.buildMode ?? 'history');
assert.equal(build.injectedSourceClass ?? 'Release27RoomScaleInstrumentedTest', sourceClass);
assert.equal(before.injectedSourceClass ?? 'Release27RoomScaleInstrumentedTest', sourceClass);
assert.equal(build.buildFailureType, null); assert.equal(build.ownedProcessCleanupError, null); assert.equal(build.timedOut, false);
assert.deepEqual(await snapshot(), { inputs: before.inputs, preserved: before.preserved });
const safetyFile = 'C:/Users/Dominik/AndroidStudioProjects/DomiDex-Web/tests/private-operations-local/safety.mjs';
const safetySha256 = await sha256(safetyFile);
const { runChild } = await import(pathToFileURL(safetyFile));
const copied = path.join(directory, 'injected-source', sourceClass + '.kt');
const sourceRelative = 'app/src/androidTest/java/com/dominik/cardvault/' + sourceClass + '.kt';
assert.equal(before.inputs[sourceRelative].sha256, before.injectedSourceSha256);
assert.equal(await sha256(copied), before.injectedSourceSha256);
assert.equal(build.injectedSourceSha256, before.injectedSourceSha256);
const runtime = path.join(directory, privateMode ? 'runtime-' + privateMode : 'runtime');
mkdirSync(runtime); // Fresh-only, never replace a receipt.
const java = 'C:/Program Files/Android/Android Studio/jbr/bin/java.exe';
const sdk = 'C:/Users/Dominik/AppData/Local/Android/Sdk';
const adb = path.join(sdk, 'platform-tools/adb.exe');
const aapt = path.join(sdk, 'build-tools/36.0.0/aapt2.exe');
const signer = path.join(sdk, 'build-tools/36.0.0/lib/apksigner.jar');
const env = Object.fromEntries(Object.entries(process.env).filter(([key]) =>
  ['SYSTEMROOT', 'WINDIR', 'SYSTEMDRIVE', 'TEMP', 'TMP', 'USERPROFILE', 'LOCALAPPDATA', 'APPDATA',
    'PROCESSOR_ARCHITECTURE', 'NUMBER_OF_PROCESSORS'].includes(key.toUpperCase())));
env.PATH = path.join(process.env.SystemRoot ?? 'C:/Windows', 'System32');
function command(executable, args, accepted = [0], timeout = 20000) {
  const result = spawnSync(executable, args, { cwd: ROOT, env, windowsHide: true, shell: false,
    encoding: 'utf8', timeout, maxBuffer: 16 * 1024 * 1024 });
  assert.equal(result.error, undefined, 'Bounded local command failed');
  assert.ok(accepted.includes(result.status), `Local command failed: ${path.basename(executable)} ${args[0]}`);
  return result.stdout.trim();
}
const device = (args, accepted, timeout) => command(adb, ['-s', SERIAL, ...args], accepted, timeout);
function noInstrumentation() {
  const output = device(['shell', 'dumpsys', 'activity', 'processes']);
  assert.ok(output.startsWith('ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)'));
  assert.ok(output.includes('All known processes:') && !/instrumentation/i.test(output));
}
function offline() {
  assert.equal(device(['get-state']), 'device');
  assert.equal(device(['shell', 'getprop', 'ro.boot.qemu.avd_name']), AVD);
  assert.equal(device(['shell', 'getprop', 'ro.kernel.qemu']), '1');
  assert.equal(device(['shell', 'getprop', 'ro.build.version.sdk']), '36');
  assert.equal(device(['shell', 'settings', 'get', 'global', 'airplane_mode_on']), '1');
  assert.equal(device(['shell', 'settings', 'get', 'global', 'wifi_on']), '0');
  assert.match(device(['shell', 'dumpsys', 'connectivity']), /Active default network: none/);
  assert.equal(device(['reverse', '--list']), ''); noInstrumentation();
}
function installedHash(pkg) {
  const result = device(['shell', 'pm', 'path', pkg]);
  assert.match(result, /^package:\/data\/app\/[^\r\n]+\/base\.apk$/);
  const digest = device(['shell', 'sha256sum', result.slice(8)]).split(/\s+/)[0];
  assert.match(digest, /^[a-f0-9]{64}$/); return digest;
}
offline();
const ordinary = 'com.dominik.cardvault'; const ordinaryBefore = installedHash(ordinary);
const packages = [app, `${app}.test`];
const paths = ['outputs/apk/debug/app-debug.apk', 'outputs/apk/androidTest/debug/app-debug-androidTest.apk'];
for (let index = 0; index < 2; index++) {
  assert.equal(device(['shell', 'pm', 'path', packages[index]], [0, 1]), '', 'Run package must not exist');
  const file = path.join(directory, 'app-build', paths[index]);
  assert.equal(await sha256(file), build.artifacts[paths[index]].sha256);
  const badging = command(aapt, ['dump', 'badging', file]);
  validateApk(badging, command(java, ['-jar', signer, 'verify', '--verbose', '--print-certs', file]), packages[index]);
  const xml = command(aapt, ['dump', 'xmltree', file, '--file', 'AndroidManifest.xml']);
  if (index === 0) {
    assert.doesNotMatch(badging, /uses-permission: name='android\.permission\.INTERNET'/);
    assert.doesNotMatch(xml, /FirebaseInitProvider|WorkManagerInitializer|E: (?:activity|activity-alias|provider|service|receiver)(?:\s|$)/m);
    assert.ok(xml.includes('com.dominik.cardvault.DomiDexApplication'));
  } else { assert.ok(xml.includes(RUNNER)); assert.ok(xml.includes(app)); }
}
const receipt = { runId: id, package: app, serial: SERIAL, avd: AVD, startedAt: new Date().toISOString(),
  mode: privateMode ?? 'history', build, runtimeInputs: { safetyFile, safetySha256 },
  installed: [], cleanupPackages: [], ordinaryBefore,
  ordinaryAfter: null, methods: [], errors: [] };
const receiptPath = path.join(runtime, 'manifest.json');
const save = () => writeFileSync(receiptPath, JSON.stringify(receipt, null, 2));
save(); console.log(`Isolated history diagnostics: ${runtime}`);
async function instrument(args, log) {
  let output = '';
  // Existing reviewed controller bounds output to 16 MiB, catches log-handler failures,
  // handles SIGINT/SIGTERM, and stops only its own live child tree. No Firebase methods run.
  const result = await runChild(adb, ['-s', SERIAL, ...args], { cwd: ROOT, env, timeoutMs: 600000,
    onOutput: bytes => {
      const value = bytes.toString('utf8'); appendFileSync(log, value); output += value; process.stdout.write(value);
    } });
  return { result, output };
}
try {
  for (let index = 0; index < 2; index++) {
    assert.match(device(['install', '-t', '--no-streaming', path.join(directory, 'app-build', paths[index])], [0], 90000), /Success/);
    receipt.installed.push(packages[index]); save();
    assert.equal(installedHash(packages[index]), build.artifacts[paths[index]].sha256);
  }
  if (privateMode) {
    // Independent complete-model/invalidation control runs first and must actually PASS.
    // The old-product query regression remains an explicitly failed test, never a PASS.
    for (const name of ['control', 'query']) {
      const mode = privateModeFor(name); offline();
      const args = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', PRIVATE_CLASS + '#' + mode.method,
        '-e', 'historyProofRun', id, '-e', 'historyProofMode', 'isolated-private-details-only',
        app + '.test/' + RUNNER];
      const { output, result } = await instrument(args, path.join(runtime, name + '-instrumentation.log'));
      const expected = name === 'query' && privateMode === 'private-red' ? 'EXPECTED_FAILURE' : 'PASS';
      const method = { name, result, expected, reportRetained: false, evidence: 'Exact instrumented JUnit status and retained raw log; no history JSON report' };
      receipt.methods.push(method); save();
      assert.equal(result.code, 0); assert.equal(result.stopped, null); assert.equal(result.error, null);
      method.validated = validatePrivateInstrumentation(output, name, expected);
      method.android = { tests: method.validated.tests, failed: method.validated.failed, skipped: method.validated.skipped };
      noInstrumentation(); save();
    }
    assert.deepEqual(receipt.methods.map(method => method.name), ['control', 'query']);
  } else {
  for (const name of ['phase', 'edges']) {
    const mode = modeFor(name); offline();
    const label = `${name}-${id}`;
    const remote = `cache/release27-room-scale-${label}-${mode.kind}.json`;
    const args = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', `${CLASS}#${mode.method}`,
      '-e', 'historyProofRun', id, '-e', 'historyProofMode', 'isolated-room-only',
      '-e', 'release27RoomScale', 'true', '-e', 'release27RoomSizes', '1000', '-e', 'release27RoomRepeats', '3',
      '-e', 'release27RoomHistoryPostFix', 'phase-first-positive', '-e', 'release27RoomLabel', label,
      `${app}.test/${RUNNER}`];
    const { output, result } = await instrument(args, path.join(runtime, `${name}-instrumentation.log`));
    const method = { name, result, reportRetained: false }; receipt.methods.push(method); save();
    let report;
    // Retain failure/cleanup evidence BEFORE requiring a successful instrumentation receipt.
    try {
      const raw = device(['shell', 'run-as', app, 'cat', remote]);
      writeFileSync(path.join(runtime, `${name}-report.json`), raw, { flag: 'wx' });
      method.reportRetained = true; report = JSON.parse(raw);
    } catch (error) { method.reportReadError = error.message; }
    save();
    assert.equal(result.code, 0); assert.equal(result.stopped, null); assert.equal(result.error, null);
    method.android = validateInstrumentation(output, name); noInstrumentation();
    assert.equal(method.reportRetained, true); assert.equal(method.reportReadError, undefined);
    method.validated = validateReport(report, name, id); save();
  }
  assert.deepEqual(receipt.methods.map(method => method.name), ['phase', 'edges']);
  }
} catch (error) {
  receipt.errors.push(error.message);
  // If the exact isolated process still exists, capture only that PID's bounded log.
  // Never save unfiltered logcat (which could contain ordinary user app data).
  try {
    const pid = device(['shell', 'pidof', app], [0, 1]);
    if (pid) {
      assert.match(pid, /^[1-9][0-9]*$/);
      const processRows = device(['shell', 'ps', '-A', '-o', 'PID,NAME']).split(/\r?\n/)
        .map(row => row.trim().split(/\s+/)).filter(row => row[0] === pid);
      assert.deepEqual(processRows, [[pid, app]]);
      writeFileSync(path.join(runtime, 'failure-own-process-logcat.txt'),
        device(['logcat', '-d', `--pid=${pid}`, '-t', '2000']), { flag: 'wx' });
      receipt.failureLogPid = pid;
    } else receipt.failureLogUnavailable = 'Owned test process already exited; unfiltered logs not read';
  } catch (diagnosticError) { receipt.failureLogError = diagnosticError.message; }
}
finally {
  // Stop/remove only this run's exact pinned disposable pair, including after a host timeout.
  for (const pkg of [...receipt.installed].reverse()) {
    try {
      const index = packages.indexOf(pkg); assert.ok(index >= 0 && pkg.startsWith(`com.dominik.cardvault.historyproof.r${id}`));
      assert.equal(installedHash(pkg), build.artifacts[paths[index]].sha256, 'Changed package must not be removed');
      device(['shell', 'am', 'force-stop', pkg]);
      assert.equal(device(['uninstall', pkg]), 'Success'); receipt.cleanupPackages.push(pkg);
    } catch (error) { receipt.errors.push('Owned package cleanup: ' + error.message); }
  }
  try {
    offline(); receipt.ordinaryAfter = installedHash(ordinary); assert.equal(receipt.ordinaryAfter, ordinaryBefore);
    assert.deepEqual(await snapshot(), { inputs: before.inputs, preserved: before.preserved });
    assert.equal(await sha256(copied), before.injectedSourceSha256); receipt.inputsUnchanged = true;
    assert.equal(await sha256(safetyFile), safetySha256);
  } catch (error) { receipt.errors.push('Final preservation checks: ' + error.message); }
  receipt.finishedAt = new Date().toISOString();
  receipt.exitCode = receipt.errors.length || receipt.methods.length !== 2 ||
    receipt.methods.some(method => method.validated?.status !==
      (privateMode === 'private-red' && method.name === 'query' ? 'EXPECTED_FAILURE' : 'PASS')) ||
    receipt.cleanupPackages.length !== 2 ? 1 : 0;
  if (privateMode) {
    receipt.verdict = receipt.exitCode ? 'INCOMPLETE_OR_UNEXPECTED_RESULT' :
      privateMode === 'private-red' ? 'EXPECTED_RED_CONFIRMED_NOT_PRODUCT_PASS' : 'PRIVATE_DETAILS_GREEN';
    receipt.validatedProductTests = {
      executed: receipt.methods.reduce((sum, method) => sum + (method.validated?.tests ?? 0), 0),
      failed: receipt.methods.reduce((sum, method) => sum + (method.validated?.failed ?? 0), 0),
      skipped: receipt.methods.reduce((sum, method) => sum + (method.validated?.skipped ?? 0), 0),
    };
  }
  save(); console.log(`History manifest: ${receiptPath}`); process.exitCode = receipt.exitCode;
}
