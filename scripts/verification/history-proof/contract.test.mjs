import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { readFileSync } from 'node:fs';
import { ROOT, OUTPUT, CLASS, identity, modeFor, validateInstrumentation, validateReport } from './contract.mjs';
const id = 'a'.repeat(32);
const schema = JSON.parse(readFileSync(path.join(ROOT, 'app/schemas/com.dominik.cardvault.data.local.CardVaultDatabase/13.json'), 'utf8'));
function tableHashes() {
  const rows = { raw_price_snapshots: 366095, catalog_cards: 1003, collection_entries: 1003 };
  const result = Object.fromEntries(schema.database.entities.map(table => [table.tableName, {
    rows: rows[table.tableName] ?? 0,
    columnsSha256: Object.fromEntries(table.fields.map(field => [field.columnName, 'a'.repeat(64)])),
  }]));
  result.sqlite_sequence = { rows: 3, columnsSha256: { name: 'b'.repeat(64), seq: 'c'.repeat(64) } };
  return result;
}
const operations = ['postFix.realDao.productOrder', 'postFix.sameStableSort.Default', 'postFix.groupBy.Default',
  'postFix.productionSweep.Default', 'postFix.counterfactual.firstPositiveCursor', 'postFix.concurrentPortfolioAndPrivate.first'];
const states = ['baseline', 'all-null-zero-negative', 'reverse-first-positive-on-later-date',
  'out-of-order-earlier-normal', 'same-date-correction-removes-normal-base', 'independent-normal-reverse-dates',
  'wrong-source-cannot-supply-base', 'duplicate-linked-product-distinct-finish', 'tombstone-original-with-linked-sibling',
  'tombstone-last-owner-reference', 'entry-only-owner-reassignment', 'owned-product-without-history', 'explicit-quarantined-identity'];
function fixture(name) {
  const mode = modeFor(name);
  const report = { kind: mode.kind, label: `${name}-${id}`, status: 'PASS', fixtureVersion: 2, api: 36,
    sizes: [1000], repeats: 3, historyDays: [mode.days], wallClockRecordedAt: 1800000000000,
    emulatorModel: 'sdk_gphone', scope: 'synthetic fixture', fixture: 'fixture only', readSampling: 'fixture sampling',
    memoryScope: 'fixture memory', queryCounterScope: 'fixture counter', passCriteria: 'fixture parity',
    cleanup: [{ databaseName: `release27-room-scale-${mode.database}-00000000-0000-4000-8000-000000000001.db`,
      closed: true, filesAbsent: true }], samples: [], integrity: [] };
  if (name === 'phase') {
    report.readSampling = 'Sample 0 is the first measured repetition after unmeasured baseline reads, NOT first DAO subscription or cold disk; 1..3 are subsequent warm repetitions';
    report.memoryScope = 'Whole-process snapshots; full baseline retained only inside completed sequential helper; concurrent subscriptions retain compact expected portfolio only; no peak, no cold-disk, no forced GC or heap increase';
    report.measurementOrder = 'Seed, all four sequential five-phase repetitions, then four concurrent subscription repetitions; no full-history fixture lists retained across these scopes';
    const sample = (operation, index) => ({ operation, sample: index, size: 1000, historyDays: 365,
      elapsedMs: 1.25, processCpuMs: 2, roomSqlCallbacks: 3, roomSelectCallbacks: 1,
      memoryBefore: { totalPssKiB: 1, dalvikPssKiB: 1, nativePssKiB: 1, javaUsedBytes: 1, nativeHeapAllocatedBytes: 1 },
      memoryAfter: { totalPssKiB: 2, dalvikPssKiB: 2, nativePssKiB: 2, javaUsedBytes: 2, nativeHeapAllocatedBytes: 2 },
      databaseBytes: { main: 1024, wal: 0, shm: 0 } });
    report.samples = [sample('seed', -1), ...[0, 1, 2, 3].flatMap(index => operations.slice(0, 5).map(operation => sample(operation, index))),
      ...[0, 1, 2, 3].map(index => sample(operations[5], index))];
    report.integrity = [{ historyDays: 365, before: tableHashes() }, { afterReadHashMatch: true }];
    report.transferCounts = [0, 1, 2, 3].map(sample => ({ sample, productionHistoryRows: 346750, candidateBaseRows: 950 }));
    report.concurrentReadings = [0, 1, 2, 3].map(sample => ({ sample, portfolioMs: 1.5, privateContentsMs: 2.5 }));
    report.capturedHistoryBindings = ['CARDMARKET_PUBLIC_DOWNLOAD', 'fixture-owner-a'];
    report.candidateBindings = ['fixture-owner-a', 'CARDMARKET_PUBLIC_DOWNLOAD', 'CARDMARKET_PUBLIC_DOWNLOAD'];
    report.capturedHistorySql = 'SELECT ? ? ' + 'x'.repeat(120);
    report.candidateSql = 'SELECT ? ? ? ' + 'y'.repeat(120);
    report.historyPlan = [{ id: 1, parent: 0, aux: 0, detail: 'synthetic plan validation fixture' }];
    report.candidatePlan = structuredClone(report.historyPlan);
  } else report.states = states.map(label => ({ label, before: tableHashes(), afterReadHashMatch: true,
    owners: ['fixture-owner-a', 'fixture-owner-b', 'fixture-owner-with-no-data'].map(owner => ({ owner,
      historyRows: owner.endsWith('no-data') ? 0 : 6, bases: owner.endsWith('no-data') ? 0 : 3,
      fullCardsEqual: true, privatePlacementsEqual: true })) }));
  return report;
}
function instrumentation(name) {
  const method = modeFor(name).method;
  const status = code => `INSTRUMENTATION_STATUS: class=${CLASS}\nINSTRUMENTATION_STATUS: test=${method}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS_CODE: ${code}`;
  return `${status(1)}\n${status(0)}\nOK (1 test)\nINSTRUMENTATION_CODE: -1`;
}
test('only fixed fresh history locations and two explicit methods are accepted', () => {
  assert.equal(identity(path.join(OUTPUT, id)).app, `com.dominik.cardvault.historyproof.r${id}`);
  for (const value of [path.join(ROOT, id), path.join(OUTPUT, 'A'.repeat(32)), path.join(OUTPUT, 'release')]) {
    assert.throws(() => identity(value));
  }
  for (const mode of ['', null, 'retry', 'constructor', '../phase', 'phase ', 1]) assert.throws(() => modeFor(mode));
  assert.ok(Object.isFrozen(modeFor('phase')));
});
test('instrumentation must genuinely start and finish the exact one method with no skip', () => {
  for (const name of ['phase', 'edges']) {
    const good = instrumentation(name); assert.equal(validateInstrumentation(good, name).tests, 1);
    for (const suffix of ['FAILURES!!!', 'AssumptionViolated', 'Process crashed', 'INSTRUMENTATION_STATUS_CODE: -3', 'INSTRUMENTATION_CODE: -1']) {
      assert.throws(() => validateInstrumentation(good + '\n' + suffix, name));
    }
    for (const bad of [good.replace('OK (1 test)', 'OK (0 tests)'), good.replace(CLASS, 'unknown.Test'),
      good.replace('numtests=1', 'numtests=2'), good.replace('current=1', 'current=2'), good.replace('INSTRUMENTATION_STATUS_CODE: 1', 'INSTRUMENTATION_STATUS_CODE: 0')]) {
      assert.throws(() => validateInstrumentation(bad, name));
    }
  }
  assert.throws(() => validateInstrumentation(instrumentation('phase'), 'edges'));
});
test('phase report rejects lost samples, false integrity, changed measurement order or cleanup', () => {
  const good = fixture('phase'); assert.equal(validateReport(good, 'phase', id).samples, 25);
  const changes = [r => { r.status = 'FAIL'; }, r => { r.failureType = 'Hidden'; }, r => { r.label = 'old-run'; },
    r => { r.kind = modeFor('edges').kind; }, r => { r.sizes = [5000]; }, r => { r.repeats = 2; },
    r => { r.historyDays = [2]; }, r => r.samples.pop(), r => { r.samples[2] = structuredClone(r.samples[1]); },
    r => { [r.samples[2], r.samples[3]] = [r.samples[3], r.samples[2]]; },
    r => { r.samples[1].elapsedMs = NaN; }, r => { r.samples[1].processCpuMs = -1; },
    r => { r.samples[1].roomSqlCallbacks = 0; }, r => { r.samples[1].memoryAfter.javaUsedBytes = -1; },
    r => { r.samples[1].databaseBytes.main = Infinity; }, r => { r.transferCounts[2].candidateBaseRows = 949; },
    r => r.concurrentReadings.pop(), r => { r.capturedHistoryBindings[1] = 'foreign-owner'; },
    r => { r.historyPlan = []; }, r => { r.candidateSql += '?'; },
    r => { r.integrity[1].afterReadHashMatch = false; }, r => { delete r.integrity[0].before.sqlite_sequence; },
    r => { delete r.integrity[0].before.catalog_cards.columnsSha256.id; },
    r => { r.integrity[0].before.catalog_cards.columnsSha256.id = 'invalid-hash'; },
    r => { r.integrity[0].before.raw_price_snapshots.rows = 346750; },
    r => { r.cleanup[0].closed = false; }, r => { r.cleanup[0].filesAbsent = false; },
    r => { r.cleanup[0].databaseName = 'cardvault.db'; }, r => { r.readSampling = 'cold start'; },
    r => { r.measurementOrder = 'unknown'; }, r => { r.memoryScope = 'peak memory'; }];
  for (const change of changes) { const bad = structuredClone(good); change(bad); assert.throws(() => validateReport(bad, 'phase', id)); }
});
test('edge report requires all thirteen exact states, three owners, full field hashes and parity', () => {
  const good = fixture('edges'); assert.equal(validateReport(good, 'edges', id).states, 13);
  const changes = [r => r.states.pop(), r => { r.states[1].label = 'baseline'; }, r => { r.states[0].afterReadHashMatch = false; },
    r => r.states[0].owners.pop(), r => { r.states[0].owners[1].owner = 'fixture-owner-a'; },
    r => { r.states[0].owners[0].fullCardsEqual = false; }, r => { r.states[0].owners[1].privatePlacementsEqual = false; },
    r => { r.states[0].owners[2].historyRows = 1; }, r => { r.states[0].owners[0].bases = -1; },
    r => { delete r.states[0].before.catalog_cards; }, r => { r.states[1].before.raw_price_snapshots.columnsSha256.cardId = 'bad'; },
    r => r.samples.push({ operation: 'unexpected' }), r => r.cleanup.pop()];
  for (const change of changes) { const bad = structuredClone(good); change(bad); assert.throws(() => validateReport(bad, 'edges', id)); }
});
