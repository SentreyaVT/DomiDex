import assert from 'node:assert/strict';
import path from 'node:path';
import { lstatSync, readdirSync, readFileSync } from 'node:fs';
import { createReadStream } from 'node:fs';
import { createHash } from 'node:crypto';
import { ROOT, SERIAL, AVD, validateApk, sourceSnapshot as sharedSnapshot } from '../workmanager-proof/contract.mjs';
export { ROOT, SERIAL, AVD, validateApk };
export const CLASS = 'com.dominik.cardvault.Release27RoomScaleInstrumentedTest';
export const RUNNER = 'com.dominik.cardvault.HistoryProofRunner';
export const OUTPUT = path.join(ROOT, 'artifacts/release27-engineering-20261003/history-proof');
const MODES = Object.freeze({
  phase: Object.freeze({ name: 'phase', method: 'localRoomPostFixHistoryPhasesAndFirstPositiveCounterfactual',
    kind: 'localRoomPostFixHistoryPhases', database: 'postfix-history-365', days: 365 }),
  edges: Object.freeze({ name: 'edges', method: 'localRoomFirstPositiveCounterfactualEdgeParity',
    kind: 'localRoomFirstPositiveEdgeParity', database: 'first-positive-edges', days: 2 }),
});
export function modeFor(name) {
  assert.equal(typeof name, 'string'); assert.ok(Object.hasOwn(MODES, name), 'Exact history method required');
  return MODES[name];
}
export function identity(directory) {
  const id = path.basename(directory); assert.match(id, /^[a-f0-9]{32}$/);
  assert.equal(path.resolve(directory), path.resolve(OUTPUT, id));
  return { id, app: `com.dominik.cardvault.historyproof.r${id}` };
}
export async function sha256(file) {
  const hash = createHash('sha256');
  for await (const data of createReadStream(file)) hash.update(data);
  return hash.digest('hex');
}
export async function snapshot() {
  const result = await sharedSnapshot();
  async function visit(relative) {
    const file = path.join(ROOT, relative); const stat = lstatSync(file);
    assert.equal(stat.isSymbolicLink(), false);
    if (stat.isDirectory()) for (const child of readdirSync(file).sort()) await visit(`${relative}/${child}`);
    else { assert.ok(stat.isFile()); result.inputs[relative] = { bytes: stat.size, sha256: await sha256(file) }; }
  }
  await visit('scripts/verification/history-proof');
  return result;
}
export function validateInstrumentation(output, modeName) {
  const mode = modeFor(modeName);
  assert.doesNotMatch(output, /FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|AssumptionViolated|INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)/);
  assert.equal((output.match(/^OK \(1 test\)\r?$/gm) ?? []).length, 1);
  let fields = {}; const statuses = []; const endings = [];
  for (const line of output.split(/\r?\n/)) {
    if (line.startsWith('INSTRUMENTATION_STATUS: ')) {
      const value = line.slice('INSTRUMENTATION_STATUS: '.length); const split = value.indexOf('=');
      assert.ok(split > 0); const key = value.slice(0, split);
      assert.equal(Object.hasOwn(fields, key), false); fields[key] = value.slice(split + 1);
    } else if (line.startsWith('INSTRUMENTATION_STATUS_CODE:')) {
      const match = /^INSTRUMENTATION_STATUS_CODE: (0|1)$/.exec(line); assert.ok(match);
      assert.equal(fields.class, CLASS); assert.equal(fields.test, mode.method);
      assert.equal(fields.numtests, '1'); assert.equal(fields.current, '1');
      statuses.push(Number(match[1])); fields = {};
    } else if (line.startsWith('INSTRUMENTATION_CODE:')) endings.push(line);
  }
  assert.deepEqual(fields, {}); assert.deepEqual(statuses, [1, 0]);
  assert.deepEqual(endings, ['INSTRUMENTATION_CODE: -1']);
  return { tests: 1, failed: 0, skipped: 0 };
}
const schema = JSON.parse(readFileSync(path.join(ROOT, 'app/schemas/com.dominik.cardvault.data.local.CardVaultDatabase/13.json'), 'utf8'));
assert.equal(schema.database.version, 13); assert.equal(schema.database.entities.length, 14);
const COLUMNS = Object.fromEntries(schema.database.entities.map(table => [table.tableName, table.fields.map(field => field.columnName).sort()]));
COLUMNS.sqlite_sequence = ['name', 'seq'];
const integer = value => assert.ok(Number.isSafeInteger(value) && value >= 0, 'Non-negative integer required');
const milliseconds = value => assert.ok(typeof value === 'number' && Number.isFinite(value) && value >= 0, 'Finite non-negative measured duration required');
function keys(object, expected) { assert.deepEqual(Object.keys(object).sort(), [...expected].sort()); }
function hashes(value) {
  keys(value, Object.keys(COLUMNS));
  for (const [table, columns] of Object.entries(COLUMNS)) {
    const item = value[table]; keys(item, ['rows', 'columnsSha256']); integer(item.rows);
    keys(item.columnsSha256, columns);
    for (const digest of Object.values(item.columnsSha256)) assert.match(digest, /^[a-f0-9]{64}$/);
  }
}
const OPERATIONS = Object.freeze(['postFix.realDao.productOrder', 'postFix.sameStableSort.Default',
  'postFix.groupBy.Default', 'postFix.productionSweep.Default', 'postFix.counterfactual.firstPositiveCursor',
  'postFix.concurrentPortfolioAndPrivate.first']);
const STATES = Object.freeze(['baseline', 'all-null-zero-negative', 'reverse-first-positive-on-later-date',
  'out-of-order-earlier-normal', 'same-date-correction-removes-normal-base', 'independent-normal-reverse-dates',
  'wrong-source-cannot-supply-base', 'duplicate-linked-product-distinct-finish', 'tombstone-original-with-linked-sibling',
  'tombstone-last-owner-reference', 'entry-only-owner-reassignment', 'owned-product-without-history', 'explicit-quarantined-identity']);
function plan(value) {
  assert.ok(Array.isArray(value) && value.length > 0 && value.length <= 64);
  for (const row of value) {
    keys(row, ['id', 'parent', 'aux', 'detail']);
    for (const field of ['id', 'parent', 'aux']) integer(row[field]);
    assert.equal(typeof row.detail, 'string'); assert.ok(row.detail.length > 0);
  }
}
export function validateReport(report, modeName, runId) {
  const mode = modeFor(modeName); assert.match(runId, /^[a-f0-9]{32}$/);
  assert.equal(report.kind, mode.kind); assert.equal(report.label, `${mode.name}-${runId}`);
  assert.equal(report.status, 'PASS'); assert.equal(Object.hasOwn(report, 'failureType'), false);
  assert.equal(report.fixtureVersion, 2); assert.equal(report.api, 36);
  assert.deepEqual(report.sizes, [1000]); assert.equal(report.repeats, 3);
  assert.deepEqual(report.historyDays, [mode.days]);
  assert.ok(Number.isSafeInteger(report.wallClockRecordedAt) && report.wallClockRecordedAt > 1700000000000);
  assert.equal(typeof report.emulatorModel, 'string'); assert.ok(report.emulatorModel.length > 0);
  for (const key of ['scope', 'fixture', 'readSampling', 'memoryScope', 'queryCounterScope', 'passCriteria']) {
    assert.equal(typeof report[key], 'string'); assert.ok(report[key].length > 0);
  }
  assert.ok(Array.isArray(report.cleanup)); assert.equal(report.cleanup.length, 1);
  const cleanup = report.cleanup[0]; keys(cleanup, ['databaseName', 'closed', 'filesAbsent']);
  assert.equal(cleanup.closed, true); assert.equal(cleanup.filesAbsent, true);
  assert.match(cleanup.databaseName, new RegExp(`^release27-room-scale-${mode.database}-[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}\\.db$`));
  if (mode.name === 'phase') {
    assert.equal(report.readSampling, 'Sample 0 is the first measured repetition after unmeasured baseline reads, NOT first DAO subscription or cold disk; 1..3 are subsequent warm repetitions');
    assert.equal(report.memoryScope, 'Whole-process snapshots; full baseline retained only inside completed sequential helper; concurrent subscriptions retain compact expected portfolio only; no peak, no cold-disk, no forced GC or heap increase');
    assert.equal(report.measurementOrder, 'Seed, all four sequential five-phase repetitions, then four concurrent subscription repetitions; no full-history fixture lists retained across these scopes');
    assert.equal(report.integrity.length, 2);
    keys(report.integrity[0], ['historyDays', 'before']); assert.equal(report.integrity[0].historyDays, 365);
    hashes(report.integrity[0].before);
    assert.equal(report.integrity[0].before.raw_price_snapshots.rows, 366095);
    assert.equal(report.integrity[0].before.catalog_cards.rows, 1003);
    assert.equal(report.integrity[0].before.collection_entries.rows, 1003);
    assert.deepEqual(report.integrity[1], { afterReadHashMatch: true });
    assert.equal(report.samples.length, 25);
    const expected = ['seed:-1', ...[0, 1, 2, 3].flatMap(sample => OPERATIONS.map(operation => `${operation}:${sample}`))].sort();
    assert.deepEqual(report.samples.map(row => `${row.operation}:${row.sample}`).sort(), expected);
    assert.deepEqual(report.samples.map(row => `${row.operation}:${row.sample}`), ['seed:-1',
      ...[0, 1, 2, 3].flatMap(sample => OPERATIONS.slice(0, 5).map(operation => `${operation}:${sample}`)),
      ...[0, 1, 2, 3].map(sample => `${OPERATIONS[5]}:${sample}`)]);
    assert.equal(report.samples[0].operation, 'seed'); assert.equal(report.samples[0].sample, -1);
    for (const row of report.samples) {
      assert.equal(row.size, 1000); assert.equal(row.historyDays, 365);
      milliseconds(row.elapsedMs); integer(row.processCpuMs);
      integer(row.roomSqlCallbacks); integer(row.roomSelectCallbacks);
      assert.ok(row.roomSelectCallbacks <= row.roomSqlCallbacks);
      for (const kind of ['memoryBefore', 'memoryAfter']) {
        keys(row[kind], ['totalPssKiB', 'dalvikPssKiB', 'nativePssKiB', 'javaUsedBytes', 'nativeHeapAllocatedBytes']);
        Object.values(row[kind]).forEach(integer);
      }
      keys(row.databaseBytes, ['main', 'wal', 'shm']); Object.values(row.databaseBytes).forEach(integer);
    }
    assert.deepEqual(report.transferCounts, [0, 1, 2, 3].map(sample => ({ sample,
      productionHistoryRows: 346750, candidateBaseRows: 950 })));
    assert.equal(report.concurrentReadings.length, 4);
    report.concurrentReadings.forEach((row, index) => {
      keys(row, ['sample', 'portfolioMs', 'privateContentsMs']); assert.equal(row.sample, index);
      milliseconds(row.portfolioMs); milliseconds(row.privateContentsMs);
    });
    assert.deepEqual(report.capturedHistoryBindings, ['CARDMARKET_PUBLIC_DOWNLOAD', 'fixture-owner-a']);
    assert.deepEqual(report.candidateBindings, ['fixture-owner-a', 'CARDMARKET_PUBLIC_DOWNLOAD', 'CARDMARKET_PUBLIC_DOWNLOAD']);
    for (const key of ['capturedHistorySql', 'candidateSql']) {
      assert.equal(typeof report[key], 'string'); assert.ok(report[key].length > 100 && report[key].length < 50000);
    }
    assert.equal((report.capturedHistorySql.match(/\?/g) ?? []).length, 2);
    assert.equal((report.candidateSql.match(/\?/g) ?? []).length, 3);
    plan(report.historyPlan); plan(report.candidatePlan);
  } else {
    assert.deepEqual(report.samples, []); assert.deepEqual(report.integrity, []);
    assert.deepEqual(report.states.map(state => state.label), STATES);
    for (const state of report.states) {
      keys(state, ['label', 'before', 'afterReadHashMatch', 'owners']); hashes(state.before);
      assert.equal(state.afterReadHashMatch, true);
      assert.deepEqual(state.owners.map(owner => owner.owner), ['fixture-owner-a', 'fixture-owner-b', 'fixture-owner-with-no-data']);
      for (const owner of state.owners) {
        keys(owner, ['owner', 'historyRows', 'bases', 'fullCardsEqual', 'privatePlacementsEqual']);
        integer(owner.historyRows); integer(owner.bases);
        assert.equal(owner.fullCardsEqual, true); assert.equal(owner.privatePlacementsEqual, true);
      }
      assert.equal(state.owners[2].historyRows, 0); assert.equal(state.owners[2].bases, 0);
    }
  }
  return { mode: mode.name, status: 'PASS', samples: report.samples.length,
    states: report.states?.length ?? 0, tableHashes: 15, cleanupVerified: true };
}
