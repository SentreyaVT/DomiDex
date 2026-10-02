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
export function validateInstrumentation(output) {
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
      assert.equal(fields.class, CLASS); assert.equal(fields.test, METHOD);
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
