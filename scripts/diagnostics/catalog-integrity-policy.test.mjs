import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import quarantine from './cardmarket-source-quarantine.json' with { type: 'json' };
import { compareAuditSummaries, evaluatePrinting } from './catalog-integrity-policy.mjs';
import { safeSourceId } from './download-catalog-snapshot.mjs';

const fixture = (override = {}) => ({
  runtime: { setId: 'sv08.5', sourceCardId: 'sv08.5-137', sourceLocalId: '137', collectorNumber: '137',
    sourcePrintingId: 'special', finish: 'Holo', variant: 'Standard', hasGermanName: true,
    image: 'https://assets.tcgdex.net/de/sv/sv08.5/137/high.webp',
    canonicalBinderPrinting: true, actualProductIds: [805531], actualPrices: [1.06], priceField: 'trend', detailStatus: 'DETAIL_OK' },
  source: { id: 'sv08.5-137', localId: '137', set: { id: 'sv08.5', cardCount: { official: 131 } },
    variants_detailed: [{ variantId: 'special', type: 'Holo', size: 'Standard', thirdParty: { cardmarket: 805531 } }] },
  provenance: { url: 'https://api.tcgdex.net/v2/de/cards/sv08.5-137' },
  products: new Map([[805531, { idProduct: 805531, name: 'Friends in Paldea', idExpansion: 5998 }]]),
  prices: new Map([[805531, { idProduct: 805531, trend: 1.06, 'trend-holo': 0 }]]),
  slots: [{ sourceCardId: 'sv08.5-137', slotIndex: '137', slotId: 'stable', finish: 'Holo', variant: 'Standard' }],
  image: { status: 'AVAILABLE', contentType: 'image/webp' }, ...override,
});

test('above-main card keeps exact set, variant and product with no denominator clamp', () => {
  const result = evaluatePrinting(fixture());
  assert.equal(result.priceEur, 1.06);
  assert.equal(result.status, 'VERIFIED_SOURCE_CHAIN');
  assert.equal(result.scannerCatalogIdentity, undefined);
  assert.equal(result.sourceCardId, 'sv08.5-137');
});

test('reviewed contradictions are unavailable even when a stale runtime reports a price', () => {
  for (const { sourceCardId, rejectedProductId } of quarantine) {
    const data = fixture();
    data.runtime.sourceCardId = data.source.id = sourceCardId;
    data.source.variants_detailed[0].thirdParty.cardmarket = rejectedProductId;
    data.runtime.actualProductIds = [rejectedProductId];
    data.products.set(rejectedProductId, { idProduct: rejectedProductId, name: 'Contradictory source product' });
    data.prices.set(rejectedProductId, { trend: 1.38 });
    const result = evaluatePrinting(data);
    assert.equal(result.cardmarketProductId, null);
    assert.equal(result.rejectedSourceProductId, rejectedProductId);
    assert.equal(result.priceStatus, 'NOT_AVAILABLE');
    assert.equal(result.priceEur, null);
    assert.equal(result.mappingEvidence, 'REVIEWED_SOURCE_CONTRADICTION');
    assert.ok(result.missingReasons.includes('SOURCE_PRODUCT_ID_QUARANTINED'));
  }
});

test('maintained quarantine metadata stays represented in the Android exact guard', () => {
  const android = readFileSync(new URL('../../app/src/main/java/com/dominik/cardvault/data/catalog/CardmarketIdentityGuard.kt', import.meta.url), 'utf8');
  for (const { sourceCardId, rejectedProductId, evidence } of quarantine) {
    assert.ok(android.includes(`${rejectedProductId}L -> "${sourceCardId}"`));
    assert.ok(evidence.length >= 3);
  }
});
test('normal and reverse use independent fields and do not borrow price', () => {
  const data = fixture();
  data.runtime = { ...data.runtime, finish: 'Reverse Holo', actualPrices: [null], priceField: 'trend-holo' };
  const result = evaluatePrinting(data);
  assert.equal(result.priceStatus, 'NOT_AVAILABLE');
  assert.equal(result.priceEur, null);
  assert.ok(result.missingReasons.includes('PRICE_FIELD_ZERO'));
});
test('same name and an existing runtime result never prove an undeclared product', () => {
  const data = fixture(); delete data.source.variants_detailed[0].thirdParty;
  const result = evaluatePrinting(data);
  assert.equal(result.cardmarketProductId, null);
  assert.equal(result.priceStatus, 'NOT_AVAILABLE');
  assert.ok(result.missingReasons.includes('RUNTIME_MAPPING_NOT_SOURCE_VERIFIED'));
});
test('wrong product and runtime ambiguous product block verified price', () => {
  const data = fixture(); data.runtime.actualProductIds = [805531, 999];
  const result = evaluatePrinting(data);
  assert.equal(result.priceStatus, 'NOT_AVAILABLE');
  assert.ok(result.missingReasons.includes('RUNTIME_MAPPING_AMBIGUOUS'));
});
test('source price present but runtime mapping absent remains a real application gap', () => {
  const data = fixture(); data.runtime.actualProductIds = []; data.runtime.actualPrices = [];
  const result = evaluatePrinting(data);
  assert.equal(result.sourcePriceEur, 1.06);
  assert.equal(result.priceStatus, 'NOT_AVAILABLE');
  assert.ok(result.missingReasons.includes('RUNTIME_EXACT_MAPPING_MISSING'));
});

test('binder lowercase stable IDs match uppercase upstream collector prefixes', () => {
  const data = fixture();
  data.runtime.sourceCardId = data.source.id = 'sv08.5-TG01';
  data.slots[0].sourceCardId = 'sv08.5-tg01';
  const result = evaluatePrinting(data);
  assert.equal(result.binderSlot.length, 1);
  assert.ok(!result.missingReasons.includes('CANONICAL_BINDER_SLOT_MISSING'));
});
test('generated upstream printing does not count as fully verified physical metadata', () => {
  const data = fixture(); data.source.variants_detailed[0].variantId = 'generated'; data.runtime.sourcePrintingId = 'generated';
  const result = evaluatePrinting(data);
  assert.equal(result.status, 'GAP');
  assert.ok(result.missingReasons.includes('SOURCE_PRINTING_IS_GENERATED_PLACEHOLDER'));
});
test('preserves stamps, foil, subtype and genuine high values for review', () => {
  const data = fixture();
  Object.assign(data.source.variants_detailed[0], { foil: 'Cosmos', stamp: ['Set Logo', 'Promo'], subtype: 'Stamped' });
  data.prices.set(805531, { trend: 4500 }); data.runtime.actualPrices = [4500];
  const result = evaluatePrinting(data);
  assert.equal(result.priceEur, 4500);
  assert.deepEqual(result.printMetadata.stamps, ['Set Logo', 'Promo']);
  assert.ok(result.auditFlags.includes('HIGH_PRICE_REQUIRES_PRODUCT_REVIEW'));
});
test('price guide is not falsely claimed to verify a German-only price', () => {
  const result = evaluatePrinting(fixture());
  assert.equal(result.language.priceGuideLanguage, 'all-languages-aggregate');
});
test('technical detail error and missing product are different states', () => {
  const data = fixture({ source: null, provenance: { status: 'ERROR' } });
  const result = evaluatePrinting(data);
  assert.equal(result.priceStatus, 'ERROR');
});
test('source ids cannot become filesystem traversal but keep real Unown ids', () => {
  for (const id of ['exu-%3F', 'exu-!', '30th-c-003']) assert.equal(safeSourceId(id), true);
  for (const id of ['../secrets', '..', 'a/b', 'a\\b', 'C:\\a', 'x%GG']) assert.equal(safeSourceId(id), false);
});
test('before/after comparison rejects source changes hidden as code improvements', () => {
  assert.throws(() => compareAuditSummaries({ snapshotFingerprint: 'a' }, { snapshotFingerprint: 'b' }));
  const delta = compareAuditSummaries({ snapshotFingerprint: 'a', counts: {}, missingReasons: { IMAGE: 2 } },
    { snapshotFingerprint: 'a', counts: {}, missingReasons: {} });
  assert.equal(delta.missingReasons.IMAGE.delta, -2);
});
