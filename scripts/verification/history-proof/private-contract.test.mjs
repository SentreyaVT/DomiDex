import test from 'node:test';
import assert from 'node:assert/strict';
import { PRIVATE_CLASS, privateModeFor, validatePrivateInstrumentation } from './private-contract.mjs';

function output(name, failure = null) {
  const method = privateModeFor(name).method;
  const record = (code, stack) => [
    'INSTRUMENTATION_STATUS: class=' + PRIVATE_CLASS,
    'INSTRUMENTATION_STATUS: current=1',
    'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner',
    'INSTRUMENTATION_STATUS: numtests=1',
    ...(stack ? ['INSTRUMENTATION_STATUS: stack=' + stack, '    at synthetic.fixture(Test.kt:1)'] : []),
    'INSTRUMENTATION_STATUS: stream=',
    'INSTRUMENTATION_STATUS: test=' + method,
    'INSTRUMENTATION_STATUS_CODE: ' + code,
  ].join('\n');
  return record(1) + '\n' + record(failure ? -2 : 0, failure) +
    '\nINSTRUMENTATION_RESULT: stream=\nTime: 0.1\n' + (failure
      ? 'There was 1 failure:\n1) ' + method + '(' + PRIVATE_CLASS + ')\n' + failure + '\nFAILURES!!!\nTests run: 1,  Failures: 1\n'
      : 'OK (1 test)\n') + 'INSTRUMENTATION_CODE: -1\n';
}
const exact = 'java.lang.AssertionError: Private details need first-positive bases, not all retained owner-history rows expected:<0> but was:<2>';

test('both exact complete private-detail methods accept genuine one-test PASS', () => {
  for (const name of ['control', 'query']) assert.equal(validatePrivateInstrumentation(output(name), name, 'PASS').failed, 0);
});
test('old query regression remains a failed test with an explicitly expected RED receipt', () => {
  const result = validatePrivateInstrumentation(output('query', exact), 'query', 'EXPECTED_FAILURE');
  assert.equal(result.status, 'EXPECTED_FAILURE'); assert.equal(result.failed, 1);
  assert.equal(result.observedWholeOwnerHistoryQueries, 2);
});
test('unrelated assertion, nonpositive or unsafe query count cannot satisfy expected RED', () => {
  for (const message of ['java.lang.AssertionError: Complete model differs',
    exact.replace('<2>', '<0>'), exact.replace('<2>', '<9007199254740992>')]) {
    assert.throws(() => validatePrivateInstrumentation(output('query', message), 'query', 'EXPECTED_FAILURE'));
  }
});
test('control failure and an unexpected query PASS cannot be relabelled expected RED', () => {
  assert.throws(() => validatePrivateInstrumentation(output('control', exact), 'control', 'EXPECTED_FAILURE'));
  assert.throws(() => validatePrivateInstrumentation(output('query'), 'query', 'EXPECTED_FAILURE'));
  assert.throws(() => validatePrivateInstrumentation(output('query', exact), 'query', 'PASS'));
});
test('skips, wrong method, duplicate status and count mismatch reject', () => {
  const pass = output('control');
  for (const invalid of [pass.replace('STATUS_CODE: 0', 'STATUS_CODE: -3'),
    pass.replace('numtests=1', 'numtests=2'), pass.replace('current=1', 'current=2'),
    pass.replace('test=' + privateModeFor('control').method, 'test=other'),
    pass + pass, pass.replace('STATUS: stream=', 'STATUS: current=1\nINSTRUMENTATION_STATUS: stream=')]) {
    assert.throws(() => validatePrivateInstrumentation(invalid, 'control', 'PASS'));
  }
});
test('expected RED rejects missing failure summary and hidden teardown/multiple failures', () => {
  const red = output('query', exact);
  for (const invalid of [red.replace('Tests run: 1,  Failures: 1', 'Tests run: 2,  Failures: 1'),
    red.replace('There was 1 failure:', 'There were 2 failures:'),
    red + '\nSuppressed: java.lang.IllegalStateException: cleanup failed',
    red + '\nCaused by: java.lang.IllegalStateException',
    red.replace('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: 0')]) {
    assert.throws(() => validatePrivateInstrumentation(invalid, 'query', 'EXPECTED_FAILURE'));
  }
});

test('hidden result errors and contradictory final failure summaries reject expected RED', () => {
  const red = output('query', exact);
  for (const invalid of [
    red.replace('INSTRUMENTATION_RESULT: stream=', 'INSTRUMENTATION_RESULT: Error=java.lang.IllegalStateException: cleanup failed\nINSTRUMENTATION_RESULT: stream='),
    red.replace('INSTRUMENTATION_RESULT: stream=', 'INSTRUMENTATION_RESULT: stream=\nINSTRUMENTATION_RESULT: stream='),
    red.replace(exact + '\nFAILURES!!!', 'java.lang.AssertionError: unrelated failure\nFAILURES!!!'),
    red.replace('FAILURES!!!', '2) unrelated(other.Test)\nFAILURES!!!'),
    red.replace('FAILURES!!!', 'There were 2 failures:\nFAILURES!!!'),
    red.replace('FAILURES!!!', 'Tests run: 2,  Failures: 2\nFAILURES!!!'),
    red.replace('FAILURES!!!', 'java.lang.IllegalStateException: hidden failure\nFAILURES!!!'),
    red + 'java.lang.IllegalStateException: late failure\n',
  ]) assert.throws(() => validatePrivateInstrumentation(invalid, 'query', 'EXPECTED_FAILURE'));
});

test('inline stream exceptions cannot hide behind instrumentation protocol prefixes', () => {
  for (const prefix of ['INSTRUMENTATION_STATUS: stream=', 'INSTRUMENTATION_RESULT: stream=']) {
    for (const [name, expected, value] of [['control', 'PASS', output('control')],
      ['query', 'EXPECTED_FAILURE', output('query', exact)]]) {
      assert.throws(() => validatePrivateInstrumentation(
        value.replace(prefix, prefix + 'java.lang.IllegalStateException: hidden failure'), name, expected));
    }
  }
});
