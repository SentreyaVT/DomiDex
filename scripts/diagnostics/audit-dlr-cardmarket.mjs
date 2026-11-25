#!/usr/bin/env node
/**
 * Read-only DLR (me06) evidence report. It never writes project or Firebase data.
 * Run: node scripts/diagnostics/audit-dlr-cardmarket.mjs > dlr-report.json
 */
const SET_ID = 'me06';
const EXPANSION_ID = 6704;
const VERIFIED = new Map(`001:918810,002:918811,004:918813,005:918814,006:918815,007:918816,008:918817,009:918818,010:918819,013:918823,014:918824,015:918825,016:918826,017:918827,020:918834,022:918837,024:918839,027:918842,028:918843,030:918846,032:918848,034:918850,035:918852,037:918854,038:918855,039:918856,040:918857,041:918858,042:918859,043:918863,044:918864,045:918865,046:918867,049:918871,050:918872,051:918873,052:918875,054:918877,055:918878,056:918879,057:918880,058:918881,060:918883,061:918884,062:918885,064:918888,065:918889,067:918892,070:918896,072:918900,073:918901,074:918902,075:918903,076:918904,077:918905,079:918907,081:918909,085:918913,086:918914,087:918915,093:918921,129:918968`.split(',').map((entry) => {
  const [number, id] = entry.split(':'); return [number, Number(id)];
}));
const FINGERPRINT_VERIFIED = new Set(['010', '017', '028', '035', '039', '050', '052', '054', '055', '057', '075', '079', '085']);
const UNMATCHED = new Set(['090', '094', '095', '098', '099', '101', '126', '132', '133', '134']);
const productUrl = 'https://downloads.s3.cardmarket.com/productCatalog/productList/products_singles_6.json';
const guideUrl = 'https://downloads.s3.cardmarket.com/productCatalog/priceGuide/price_guide_6.json';
const normalized = (value) => String(value ?? '')
  .normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '');

function mapping(number) {
  const productId = VERIFIED.get(number) ?? null;
  if (productId !== null) return {
    status: 'VERIFIED', productId, finish: 'Normal', variant: 'Standard',
    provenance: FINGERPRINT_VERIFIED.has(number)
      ? 'Cardmarket expansion + unique English name/complete attack fingerprint'
      : 'TCGdex declared product + Cardmarket expansion/name/attack fingerprint',
    verifiedAt: '2026-11-23T00:00:00.000Z',
  };
  if (UNMATCHED.has(number)) return {
    status: 'UNMATCHED', productId: null, finish: 'Normal', variant: 'Standard',
    provenance: 'No Cardmarket root reference or unique Cardmarket fingerprint', verifiedAt: null,
  };
  return {
    status: 'NEEDS_REVIEW', productId: null, finish: 'Normal', variant: 'Standard',
    provenance: 'No print-specific Cardmarket evidence yet', verifiedAt: null,
  };
}
function status(number) {
  return mapping(number).status;
}
async function json(url) {
  const response = await fetch(url, { headers: { Accept: 'application/json', 'User-Agent': 'DomiDex-catalog-audit/29' } });
  if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
  return response.json();
}
async function pooled(values, limit, work) {
  const output = []; let index = 0;
  await Promise.all(Array.from({ length: limit }, async () => {
    while (index < values.length) { const value = values[index++]; output.push(await work(value)); }
  }));
  return output;
}

const [set, productsDocument, guideDocument] = await Promise.all([
  json(`https://api.tcgdex.net/v2/de/sets/${SET_ID}`), json(productUrl), json(guideUrl),
]);
const expansionProducts = productsDocument.products.filter((product) => product.idExpansion === EXPANSION_ID);
const productById = new Map(expansionProducts.map((product) => [product.idProduct, product]));
const priceById = new Map(guideDocument.priceGuides.map((price) => [price.idProduct, price]));
const cards = await pooled(set.cards, 8, async (summary) => json(`https://api.tcgdex.net/v2/de/cards/${summary.id}`));
const englishById = new Map(await pooled(set.cards, 8, async (summary) => {
  const englishId = summary.id;
  return [englishId, await json(`https://api.tcgdex.net/v2/en/cards/${englishId}`)];
}));
const rows = cards.sort((left, right) => Number(left.localId) - Number(right.localId)).map((card) => {
  const number = String(card.localId).padStart(3, '0');
  const mappingRecord = mapping(number);
  const mappingStatus = mappingRecord.status;
  const productId = mappingRecord.productId;
  const product = productId === null ? null : productById.get(productId) ?? null;
  const guide = productId === null ? null : priceById.get(productId) ?? null;
  const trend = guide?.trend ?? null;
  const english = englishById.get(card.id);
  const englishName = normalized(english?.name);
  const englishAttackNames = (english?.attacks ?? []).map((attack) => normalized(attack.name)).filter(Boolean);
  const fingerprintCandidates = expansionProducts.filter((candidate) => {
    const productName = normalized(candidate.name);
    return productName.startsWith(englishName) && englishAttackNames.every((attack) => productName.includes(attack));
  });
  const ordinalProduct = expansionProducts[Number(number) - 1] ?? null;
  const candidateEvidence = {
    englishName: english?.name ?? null,
    englishAttackNames: english?.attacks?.map((attack) => attack.name) ?? [],
    exactFingerprintProductIds: fingerprintCandidates.map((candidate) => candidate.idProduct),
    exactFingerprintProductNames: fingerprintCandidates.map((candidate) => candidate.name),
    // This confirms the catalog's observed 141-row order for later manual review,
    // but it is explicitly not accepted as a price mapping: product exports do
    // not publish collector number or finish as fields.
    ordinalExpansionCandidate: ordinalProduct && {
      productId: ordinalProduct.idProduct,
      productName: ordinalProduct.name,
      acceptedForPricing: false,
      reason: 'Expansion-position evidence only; Cardmarket export has no printed number/finish field',
    },
  };
  return {
    sourceCardId: card.id, setId: card.set?.id, setName: card.set?.name, collectorNumber: number,
    name: card.name, rarity: card.rarity ?? null, variants: card.variants_detailed ?? [],
    mapping: mappingRecord, candidateEvidence,
    imageAvailable: Boolean(card.image), mappingStatus, productId,
    productInExpectedExpansion: product?.idExpansion === EXPANSION_ID,
    productName: product?.name ?? null, priceTrendEur: typeof trend === 'number' && trend > 0 ? trend : null,
    priceUpdatedAt: card.pricing?.cardmarket?.updated ?? null,
    androidVisible: Boolean(card.image), webVisible: Boolean(card.image), binderVisible: Boolean(card.image),
    androidPriceEligible: mappingStatus === 'VERIFIED', webPriceEligible: mappingStatus === 'VERIFIED',
    sharedBinderPriceEligible: mappingStatus === 'VERIFIED',
  };
});
const count = (predicate) => rows.filter(predicate).length;
const report = {
  schemaVersion: 1,
  generatedAt: new Date().toISOString(),
  sources: { tcgdexSet: SET_ID, cardmarketExpansion: EXPANSION_ID, productCatalogueCreatedAt: productsDocument.createdAt, priceGuideCreatedAt: guideDocument.createdAt },
  summary: {
    expectedCards: 141, presentCards: rows.length, imagesPresent: count((row) => row.imageAvailable),
    verifiedMappings: count((row) => row.mappingStatus === 'VERIFIED'),
    needsReview: count((row) => row.mappingStatus === 'NEEDS_REVIEW'),
    unmatched: count((row) => row.mappingStatus === 'UNMATCHED'), rejected: count((row) => row.mappingStatus === 'REJECTED'),
    verifiedProductsAvailable: count((row) => row.mappingStatus === 'VERIFIED' && row.productInExpectedExpansion),
    verifiedTrendPricesAvailable: count((row) => row.mappingStatus === 'VERIFIED' && row.priceTrendEur !== null),
    verifiedPricesMissing: count((row) => row.mappingStatus === 'VERIFIED' && row.priceTrendEur === null),
    duplicateVerifiedProductIds: rows.filter((row) => row.productId !== null).length - new Set(rows.map((row) => row.productId).filter(Boolean)).size,
    androidInvisible: count((row) => !row.androidVisible), webInvisible: count((row) => !row.webVisible), binderInvisible: count((row) => !row.binderVisible),
    openCardsWithExactlyOneAdditionalFingerprintCandidate: count((row) => row.mappingStatus !== 'VERIFIED' && row.candidateEvidence.exactFingerprintProductIds.length === 1),
    openCardsWithAmbiguousFingerprintCandidates: count((row) => row.mappingStatus !== 'VERIFIED' && row.candidateEvidence.exactFingerprintProductIds.length > 1),
    openCardsWithoutExactFingerprintCandidate: count((row) => row.mappingStatus !== 'VERIFIED' && row.candidateEvidence.exactFingerprintProductIds.length === 0),
  },
  cards: rows,
};
console.log(JSON.stringify(report, null, 2));
