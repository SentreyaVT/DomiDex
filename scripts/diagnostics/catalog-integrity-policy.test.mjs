import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import quarantine from './cardmarket-source-quarantine.json' with { type: 'json' };
import { compareAuditSummaries, evaluatePrinting } from './catalog-integrity-policy.mjs';
import { safeSourceId } from './download-catalog-snapshot.mjs';

const fixture = (override = {}) => ({
  runtime: { setId: 'sv08.5', sourceCardId: 'sv08.5-137', sourceLocalId: '137', collectorNumber: '137',
    sourcePrintingId: 'special', runtimePrintingId: 'special', finish: 'Holo', variant: 'Standard', hasGermanName: true,
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
  // A healthy reverse fixture must actually describe a reverse source printing, not a Holo row.
  data.source.variants_detailed[0].type = 'Reverse Holo';
  data.runtime = { ...data.runtime, finish: 'Reverse Holo', actualPrices: [null], priceField: 'trend-holo' };
  data.slots[0].finish = 'Reverse Holo';
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
  // Android includes the physical metadata and sorted stamps in the runtime descriptor.
  data.runtime.variant = data.slots[0].variant = 'Standard · Untertyp: Stamped · Stempel: Promo + Set Logo · Folie: Cosmos';
  data.prices.set(805531, { trend: 4500 }); data.runtime.actualPrices = [4500];
  const result = evaluatePrinting(data);
  assert.equal(result.priceEur, 4500);
  assert.equal(result.status, 'VERIFIED_SOURCE_CHAIN');
  assert.deepEqual(result.printMetadata.stamps, ['Set Logo', 'Promo']);
  assert.ok(result.auditFlags.includes('HIGH_PRICE_REQUIRES_PRODUCT_REVIEW'));
});

test('same raw printing ID resolves Normal and Reverse independently of source row order', () => {
  const rows = [
    { variantId: 'generated', type: 'Normal', size: 'Standard', thirdParty: { cardmarket: 805531 } },
    { variantId: 'generated', type: 'Reverse Holo', size: 'Standard', thirdParty: { cardmarket: 900001 } },
  ];
  const cases = [
    { finish: 'Normal', productId: 805531, price: 1.06, field: 'trend', runtimeId: 'local-normal' },
    { finish: 'Reverse Holo', productId: 900001, price: 7.25, field: 'trend-holo', runtimeId: 'local-reverse' },
  ];
  for (const orderedRows of [rows, [...rows].reverse()]) for (const expected of cases) {
    const data = fixture();
    data.source.variants_detailed = orderedRows;
    Object.assign(data.runtime, { sourcePrintingId: 'generated', runtimePrintingId: expected.runtimeId,
      finish: expected.finish, canonicalBinderPrinting: false, actualProductIds: [expected.productId],
      actualPrices: [expected.price], priceField: expected.field });
    data.products.set(900001, { idProduct: 900001, name: 'Separate reverse product' });
    data.prices.set(900001, { trend: 123.45, 'trend-holo': 7.25 });
    const result = evaluatePrinting(data);
    assert.equal(result.sourcePrintingId, 'generated');
    assert.equal(result.runtimePrintingId, expected.runtimeId);
    assert.equal(result.printMetadata.type, expected.finish);
    assert.equal(result.printMetadata.size, 'Standard');
    assert.equal(result.cardmarketProductId, expected.productId);
    assert.equal(result.sourcePriceEur, expected.price);
    assert.equal(result.priceEur, expected.price);
    assert.equal(result.priceStatus, 'AVAILABLE');
    // Separating the runtime ID must not erase the upstream generated-placeholder caveat.
    assert.ok(result.missingReasons.includes('SOURCE_PRINTING_IS_GENERATED_PLACEHOLDER'));
  }
});

test('same raw ID and finish still distinguish subtype, stamp and foil descriptors', () => {
  const rows = [
    { variantId: 'shared', type: 'Holo', size: 'Standard', thirdParty: { cardmarket: 805531 } },
    { variantId: 'shared', type: 'Holo', size: 'Standard', subtype: 'Stamped', thirdParty: { cardmarket: 900002 } },
    { variantId: 'shared', type: 'Holo', size: 'Standard', stamp: 'Promo', thirdParty: { cardmarket: 900003 } },
    { variantId: 'shared', type: 'Holo', size: 'Standard', foil: 'Cosmos', thirdParty: { cardmarket: 900004 } },
  ];
  const cases = [
    { variant: 'Standard', productId: 805531, price: 1.06, subtype: null, stamps: [], foil: null },
    { variant: 'Standard · Untertyp: Stamped', productId: 900002, price: 2.02, subtype: 'Stamped', stamps: [], foil: null },
    { variant: 'Standard · Stempel: Promo', productId: 900003, price: 3.03, subtype: null, stamps: ['Promo'], foil: null },
    { variant: 'Standard · Folie: Cosmos', productId: 900004, price: 4.04, subtype: null, stamps: [], foil: 'Cosmos' },
  ];
  for (const orderedRows of [rows, [...rows].reverse()]) for (const expected of cases) {
    const data = fixture();
    data.source.variants_detailed = orderedRows;
    Object.assign(data.runtime, { sourcePrintingId: 'shared', runtimePrintingId: `local-${expected.productId}`,
      variant: expected.variant, canonicalBinderPrinting: false,
      actualProductIds: [expected.productId], actualPrices: [expected.price] });
    for (const item of cases) {
      data.products.set(item.productId, { idProduct: item.productId, name: 'Synthetic printing' });
      data.prices.set(item.productId, { trend: item.price });
    }
    const result = evaluatePrinting(data);
    assert.equal(result.status, 'VERIFIED_SOURCE_CHAIN');
    assert.equal(result.cardmarketProductId, expected.productId);
    assert.equal(result.priceEur, expected.price);
    assert.deepEqual(result.printMetadata, { sourcePrintingId: 'shared', type: 'Holo', size: 'Standard',
      subtype: expected.subtype, foil: expected.foil, stamps: expected.stamps });
  }
});

test('descriptor matching follows Android size preservation, type normalization and unique sorted stamps', () => {
  const cases = [
    { row: { variantId: 'special', type: '  reverse HOLO  ', size: '  Standard  ', subtype: ' Special ',
      stamp: [' Set Logo ', 'promo', 'PROMO', '', '  alpha '], foil: ' Cosmos ', thirdParty: { cardmarket: 805531 } },
    finish: 'Reverse Holo', variant: '  Standard   · Untertyp: Special · Stempel: alpha + promo + Set Logo · Folie: Cosmos',
    field: 'trend-holo', price: 6.66 },
    { row: { variantId: 'special', type: '  hOLO  ', size: '   ', thirdParty: { cardmarket: 805531 } },
      finish: 'Holo', variant: 'Holo', field: 'trend', price: 1.06 },
  ];
  for (const expected of cases) {
    const data = fixture();
    data.source.variants_detailed = [expected.row];
    Object.assign(data.runtime, { finish: expected.finish, variant: expected.variant, canonicalBinderPrinting: false,
      actualPrices: [expected.price], priceField: expected.field });
    data.prices.set(805531, { trend: 1.06, 'trend-holo': 6.66 });
    const result = evaluatePrinting(data);
    assert.equal(result.status, 'VERIFIED_SOURCE_CHAIN');
    assert.equal(result.cardmarketProductId, 805531);
    assert.equal(result.priceEur, expected.price);
  }
});

test('unknown descriptor cannot inherit product metadata or price from a matching raw ID', () => {
  const data = fixture();
  data.runtime.variant = 'Standard · Folie: Not source-declared';
  data.runtime.canonicalBinderPrinting = false;
  const result = evaluatePrinting(data);
  assert.equal(result.status, 'GAP');
  assert.equal(result.cardmarketProductId, null);
  assert.equal(result.sourcePriceEur, null);
  assert.equal(result.priceEur, null);
  assert.equal(result.priceStatus, 'NOT_AVAILABLE');
  assert.equal(result.printMetadata.sourcePrintingId, null);
  assert.ok(result.missingReasons.includes('SOURCE_PRINTING_METADATA_MISSING'));
});

test('matching finish and descriptor do not replace a missing raw source printing ID', () => {
  const data = fixture();
  data.runtime.sourcePrintingId = 'not-source-declared';
  // The locally unique runtime ID is not source provenance.
  data.runtime.runtimePrintingId = 'special';
  const result = evaluatePrinting(data);
  assert.equal(result.cardmarketProductId, null);
  assert.equal(result.sourcePriceEur, null);
  assert.equal(result.priceStatus, 'NOT_AVAILABLE');
  assert.ok(result.missingReasons.includes('SOURCE_PRINTING_METADATA_MISSING'));
});

test('registry metadata cannot give an unknown source descriptor a verified price', () => {
  const data = fixture();
  data.runtime.variant = 'Standard · Folie: Not source-declared';
  data.runtime.canonicalBinderPrinting = false;
  data.runtime.localVerifiedProductId = 805531;
  const result = evaluatePrinting(data);
  assert.equal(result.cardmarketProductId, null);
  assert.equal(result.priceStatus, 'NOT_AVAILABLE');
  assert.equal(result.priceEur, null);
  assert.ok(result.missingReasons.includes('SOURCE_PRINTING_METADATA_MISSING'));
});

test('one unambiguous source declaration survives row order without inventing runtime resolution', () => {
  const rows = [
    { variantId: 'special', type: 'Holo', size: 'Standard' },
    { variantId: 'special', type: 'Holo', size: 'Standard', thirdParty: { cardmarket: 805531 } },
  ];
  for (const orderedRows of [rows, [...rows].reverse()]) for (const mapped of [true, false]) {
    const data = fixture();
    data.source.variants_detailed = orderedRows;
    if (!mapped) { data.runtime.actualProductIds = []; data.runtime.actualPrices = []; }
    const result = evaluatePrinting(data);
    assert.equal(result.cardmarketProductId, 805531);
    assert.equal(result.sourcePriceEur, 1.06);
    assert.equal(result.mappingEvidence, 'SOURCE_DECLARED_PRODUCT_ID');
    assert.equal(result.priceStatus, mapped ? 'AVAILABLE' : 'NOT_AVAILABLE');
    assert.equal(result.priceEur, mapped ? 1.06 : null);
    assert.equal(result.missingReasons.includes('RUNTIME_EXACT_MAPPING_MISSING'), !mapped);
  }
});

test('printing-level provider conflict blocks only its exact printing, including registry fallback', () => {
  const rows = [
    { variantId: 'generated', type: 'Holo', size: 'Standard', thirdParty: { cardmarket: 805531 },
      pricing: { cardmarket: { idProduct: 999999 } } },
    { variantId: 'generated', type: 'Normal', size: 'Standard', thirdParty: { cardmarket: 900001 } },
  ];
  for (const orderedRows of [rows, [...rows].reverse()]) {
    const data = fixture();
    data.source.variants_detailed = orderedRows;
    Object.assign(data.runtime, { sourcePrintingId: 'generated', runtimePrintingId: 'local-holo',
      canonicalBinderPrinting: false, localVerifiedProductId: 805531 });
    data.products.set(900001, { idProduct: 900001, name: 'Healthy normal printing' });
    data.prices.set(900001, { trend: 8.5 });
    const conflict = evaluatePrinting(data);
    assert.equal(conflict.status, 'GAP');
    assert.equal(conflict.cardmarketProductId, null);
    assert.equal(conflict.sourcePriceEur, null);
    assert.equal(conflict.priceStatus, 'NOT_AVAILABLE');
    assert.equal(conflict.priceEur, null);

    Object.assign(data.runtime, { runtimePrintingId: 'local-normal', finish: 'Normal',
      actualProductIds: [900001], actualPrices: [8.5] });
    delete data.runtime.localVerifiedProductId;
    const healthy = evaluatePrinting(data);
    assert.equal(healthy.cardmarketProductId, 900001);
    assert.equal(healthy.priceStatus, 'AVAILABLE');
    assert.equal(healthy.priceEur, 8.5);
    assert.equal(healthy.printMetadata.type, 'Normal');
  }
});

test('contradictory products on indistinguishable source descriptors cannot use first-row wins', () => {
  const rows = [
    { variantId: 'special', type: 'Holo', size: 'Standard', thirdParty: { cardmarket: 805531 } },
    { variantId: 'special', type: 'Holo', size: 'Standard', thirdParty: { cardmarket: 900001 } },
  ];
  for (const orderedRows of [rows, [...rows].reverse()]) {
    const data = fixture();
    data.source.variants_detailed = orderedRows;
    data.products.set(900001, { idProduct: 900001, name: 'Contradictory duplicate' });
    data.prices.set(900001, { trend: 8.5 });
    const result = evaluatePrinting(data);
    assert.equal(result.status, 'GAP');
    assert.equal(result.cardmarketProductId, null);
    assert.equal(result.sourcePriceEur, null);
    assert.equal(result.priceStatus, 'NOT_AVAILABLE');
    assert.equal(result.priceEur, null);
  }
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
