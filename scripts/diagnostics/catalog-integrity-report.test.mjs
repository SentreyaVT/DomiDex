import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

test('complete report keeps missing source and exact above-main printing visible', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'domidex-catalog-report-test-'));
  const snapshot = path.join(directory, 'snapshot'), runtime = path.join(directory, 'runtime'), output = path.join(directory, 'report');
  await Promise.all([fs.mkdir(path.join(snapshot, 'cards'), { recursive: true }), fs.mkdir(path.join(snapshot, 'sets/de'), { recursive: true }),
    fs.mkdir(path.join(snapshot, 'sets/en'), { recursive: true }), fs.mkdir(runtime)]);
  const write = (root, file, value) => fs.writeFile(path.join(root, file), JSON.stringify(value));
  const sets = [{ id: 'test', name: 'Prüfset', cardCount: { official: 1, total: 2 } },
    { id: 'pocket', name: 'Pocket', cardCount: { official: 1, total: 1 } }];
  const runtimeRows = [1, 2].map(number => ({ setId: 'test', sourceCardId: `test-${number}`, sourceLocalId: `${number}`,
    collectorNumber: `${number}`, sourcePrintingId: 'holo', finish: 'Holo', variant: 'Standard', displayName: `Karte ${number}`,
    hasGermanName: true, image: `https://example.invalid/${number}.webp`, canonicalBinderPrinting: true,
    actualProductIds: number === 2 ? [22] : [], actualPrices: number === 2 ? [6.7] : [], priceField: 'trend', detailStatus: 'DETAIL_OK' }));
  await Promise.all([
    write(snapshot, 'sets-de.json', sets), write(snapshot, 'series-tcgp.json', { sets: [sets[1]] }),
    write(snapshot, 'source-manifest.json', [{ file: 'cards/test-2.json', sha256: 'frozen-example', url: 'https://api.tcgdex.net/v2/de/cards/test-2' }]),
    write(snapshot, 'products.json', { createdAt: '2026-09-22', products: [{ idProduct: 22, name: 'Card 2', idExpansion: 3 }] }),
    write(snapshot, 'prices.json', { createdAt: '2026-09-22', priceGuides: [{ idProduct: 22, trend: 6.7 }] }),
    write(snapshot, 'cards/test-2.json', { id: 'test-2', localId: '2', set: { id: 'test' },
      variants_detailed: [{ variantId: 'holo', type: 'Holo', size: 'Standard', thirdParty: { cardmarket: 22 } }] }),
    write(snapshot, 'sets/de/test.json', { ...sets[0], cards: runtimeRows.map(row => ({ id: row.sourceCardId, localId: row.sourceLocalId })) }),
    write(snapshot, 'sets/en/test.json', { ...sets[0], cards: runtimeRows.map(row => ({ id: row.sourceCardId, localId: row.sourceLocalId })) }),
    write(runtime, 'binder-price-audit-report.json', { sets: [{ setId: 'test', actualBinderSlots: 2, emptyLayoutCells: 0 }] }),
    fs.writeFile(path.join(runtime, 'production-printings.jsonl'), runtimeRows.map(row => JSON.stringify(row)).join('\n')),
    fs.writeFile(path.join(runtime, 'binder-price-audit-slots.csv'),
      '"sourceCardId","slotIndex","slotId","finish","variant"\n"test-1","1","slot1","Holo","Standard"\n"test-2","2","slot2","Holo","Standard"\n'),
    fs.writeFile(path.join(snapshot, 'image-checks.jsonl'), runtimeRows.map(row => JSON.stringify({ url: row.image, status: 'AVAILABLE' })).join('\n')),
  ]);
  const result = spawnSync(process.execPath, [fileURLToPath(new URL('./report-catalog-integrity.mjs', import.meta.url)), snapshot, runtime, output], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  const summary = JSON.parse(await fs.readFile(path.join(output, 'summary.json'), 'utf8'));
  const reportSets = JSON.parse(await fs.readFile(path.join(output, 'sets.json'), 'utf8'));
  const records = (await fs.readFile(path.join(output, 'card-variant-coverage.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
  assert.equal(summary.counts.physicalSets, 1);
  assert.equal(summary.counts.availablePrices, 1);
  assert.equal(summary.counts.unavailablePrices, 1);
  assert.equal(summary.missingReasons.CARD_DETAIL_MISSING, 1);
  assert.deepEqual(reportSets[0].aboveMainSetCards, ['test-2']);
  assert.equal(records[1].priceEur, 6.7);
  assert.equal(records[1].binderSlot[0].index, 2);
  assert.equal(reportSets[0].independentlyVerifiedPhysicalTotal, null);
  // Only delete this test's explicitly created, validated temporary directory.
  if (path.dirname(directory) === path.resolve(os.tmpdir()) && path.basename(directory).startsWith('domidex-catalog-report-test-')) {
    await fs.rm(directory, { recursive: true });
  }
});
