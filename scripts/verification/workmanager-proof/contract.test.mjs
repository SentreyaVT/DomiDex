import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ROOT, CLASS, METHOD, DEBUG_CERT, runIdentity, validateApk, validateInstrumentation } from './contract.mjs';
const id = 'a'.repeat(32);
const base = `${ROOT}/artifacts/release27-engineering-20261002/workmanager-proof/`;
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
