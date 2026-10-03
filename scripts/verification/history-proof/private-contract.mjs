import assert from 'node:assert/strict';

// Additive private-details contract; the history phase/edges contract is unchanged.
export const PRIVATE_CLASS = 'com.dominik.cardvault.PrivateBinderFirstPositiveInstrumentedTest';
const MODES = Object.freeze({
  control: Object.freeze({ name: 'control', method: 'sustainedPrivateDetailsTrackPriceLinkCatalogOwnerPlacementAndSelectionChanges' }),
  query: Object.freeze({ name: 'query', method: 'privateDetailsRetainCompleteCardsWithoutMaterializingWholeOwnerHistory' }),
});
export function privateModeFor(name) {
  assert.equal(typeof name, 'string');
  assert.ok(Object.hasOwn(MODES, name), 'Only the two reviewed complete private-details methods');
  return MODES[name];
}
const EXPECTED_ASSERTION = /^java\.lang\.AssertionError: Private details need first-positive bases, not all retained owner-history rows expected:<0> but was:<([1-9][0-9]*)>$/;
export function validatePrivateInstrumentation(output, name, expected) {
  const mode = privateModeFor(name);
  assert.ok(['PASS', 'EXPECTED_FAILURE'].includes(expected));
  if (expected === 'EXPECTED_FAILURE') assert.equal(name, 'query', 'Control can never be an expected failure');
  assert.equal(typeof output, 'string');
  assert.doesNotMatch(output, /INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|shortMsg=|AssumptionViolated|MultipleFailure|Suppressed:|Caused by:/);
  const records = []; const endings = []; const resultKeys = []; let fields = {};
  for (const line of output.split(/\r?\n/)) {
    if (line.startsWith('INSTRUMENTATION_STATUS: ')) {
      const value = line.slice('INSTRUMENTATION_STATUS: '.length); const split = value.indexOf('=');
      assert.ok(split > 0); const key = value.slice(0, split);
      assert.ok(['class', 'current', 'id', 'numtests', 'stream', 'test', 'stack'].includes(key), 'Unexpected instrumentation status field');
      assert.equal(Object.hasOwn(fields, key), false, 'Duplicate instrumentation field');
      fields[key] = value.slice(split + 1);
    } else if (line.startsWith('INSTRUMENTATION_STATUS_CODE:')) {
      const match = /^INSTRUMENTATION_STATUS_CODE: (0|1|-2)$/.exec(line); assert.ok(match, 'No skipped, errored or unknown test status');
      assert.equal(fields.class, PRIVATE_CLASS); assert.equal(fields.test, mode.method);
      assert.equal(fields.id, 'AndroidJUnitRunner');
      assert.equal(fields.numtests, '1'); assert.equal(fields.current, '1');
      records.push({ code: Number(match[1]), fields }); fields = {};
    } else if (line.startsWith('INSTRUMENTATION_RESULT: ')) {
      const value = line.slice('INSTRUMENTATION_RESULT: '.length); const split = value.indexOf('=');
      assert.ok(split > 0); const key = value.slice(0, split);
      assert.equal(key, 'stream', 'No hidden instrumentation result error');
      assert.equal(records.length, 2); assert.deepEqual(fields, {}); assert.equal(endings.length, 0);
      resultKeys.push(key);
    } else if (line.startsWith('INSTRUMENTATION_CODE:')) endings.push(line);
    else if (line.startsWith('INSTRUMENTATION_')) assert.fail('Unknown instrumentation protocol field');
  }
  assert.deepEqual(fields, {});
  assert.deepEqual(resultKeys, ['stream']);
  assert.deepEqual(endings, ['INSTRUMENTATION_CODE: -1']);
  const lines = output.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  assert.equal(lines.at(-1), 'INSTRUMENTATION_CODE: -1');
  const throwableHeaders = lines.map(line => line.replace(/^INSTRUMENTATION_(?:STATUS|RESULT): (?:stack|stream)=/, ''))
    .filter(line => /^(?:[\w$]+\.)*(?:[\w$]*(?:Error|Exception)|Throwable)(?::|$)/.test(line));
  assert.deepEqual(records.map(record => record.code), expected === 'PASS' ? [1, 0] : [1, -2]);
  assert.equal(Object.hasOwn(records[0].fields, 'stack'), false);
  if (expected === 'PASS') {
    assert.deepEqual(throwableHeaders, [], 'No hidden exception in a passing result');
    assert.equal(Object.hasOwn(records[1].fields, 'stack'), false);
    assert.doesNotMatch(output, /FAILURES!!!|There (?:was|were) \d+ failures?:|Tests run:/);
    assert.equal((output.match(/^OK \(1 test\)\r?$/gm) ?? []).length, 1);
    return { status: 'PASS', tests: 1, failed: 0, skipped: 0, method: mode.method };
  }
  const match = EXPECTED_ASSERTION.exec(records[1].fields.stack ?? '');
  assert.ok(match, 'Only the final whole-owner-history query-count assertion may be RED');
  assert.ok(throwableHeaders.length >= 2, 'Status and final summary must retain the same assertion');
  for (const header of throwableHeaders) assert.equal(header, records[1].fields.stack,
    'No unrelated exception or conflicting summary may satisfy expected RED');
  const historyQueries = Number(match[1]);
  assert.ok(Number.isSafeInteger(historyQueries) && historyQueries > 0);
  assert.equal((output.match(/^FAILURES!!!\r?$/gm) ?? []).length, 1);
  assert.equal((output.match(/^There was 1 failure:\r?$/gm) ?? []).length, 1);
  assert.equal((output.match(/^Tests run: 1,  Failures: 1\r?$/gm) ?? []).length, 1);
  assert.doesNotMatch(output, /^OK \(/m);
  const expectedHeader = '1) ' + mode.method + '(' + PRIVATE_CLASS + ')';
  assert.deepEqual(lines.filter(line => /^\d+\) /.test(line)), [expectedHeader]);
  assert.deepEqual(lines.filter(line => /^There (?:was|were) /.test(line)), ['There was 1 failure:']);
  assert.deepEqual(lines.filter(line => /^Tests run:/.test(line)), ['Tests run: 1,  Failures: 1']);
  assert.equal(lines[lines.indexOf(expectedHeader) + 1], records[1].fields.stack,
    'Final JUnit summary must repeat the exact accepted assertion');
  return { status: 'EXPECTED_FAILURE', tests: 1, failed: 1, skipped: 0,
    method: mode.method, assertion: 'Private details need first-positive bases, not all retained owner-history rows',
    observedWholeOwnerHistoryQueries: historyQueries,
    meaning: 'Old-product regression reproduced after preceding complete-model and positive-control assertions; NOT product PASS' };
}
