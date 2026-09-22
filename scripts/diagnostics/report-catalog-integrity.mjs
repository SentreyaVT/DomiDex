/** Joins real Android production-parser output with immutable public-source evidence. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { evaluatePrinting, compareAuditSummaries } from './catalog-integrity-policy.mjs';

const [snapshotArg, runtimeArg, outputArg, beforeArg] = process.argv.slice(2);
if (!snapshotArg || !runtimeArg || !outputArg) throw new Error('Usage: snapshot-dir production-audit-dir report-dir [before-summary.json]');
const snapshot = path.resolve(snapshotArg), runtimeDir = path.resolve(runtimeArg), output = path.resolve(outputArg);
await fs.mkdir(output, { recursive: true });
const read = async (root, file) => JSON.parse(await fs.readFile(path.join(root, file), 'utf8'));
const optional = async (root, file) => { try { return await read(root, file); } catch { return null; } };
const jsonl = text => text.trim().split('\n').filter(Boolean).map(line => JSON.parse(line));
function csv(text) {
  const lines = text.trim().split(/\r?\n/);
  const split = line => [...line.matchAll(/"((?:[^"]|"")*)"(?:,|$)/g)].map(match => match[1].replaceAll('""', '"'));
  const headers = split(lines.shift());
  return lines.map(line => Object.fromEntries(split(line).map((value, i) => [headers[i], value])));
}
const [manifest, listed, pocket, productsFile, pricesFile, production] = await Promise.all([
  read(snapshot, 'source-manifest.json'), read(snapshot, 'sets-de.json'), read(snapshot, 'series-tcgp.json'),
  read(snapshot, 'products.json'), read(snapshot, 'prices.json'), read(runtimeDir, 'binder-price-audit-report.json'),
]);
const excluded = new Set(pocket.sets.map(set => set.id));
const englishListed = await optional(snapshot, 'sets-en.json') ?? [];
const sourceByFile = new Map(manifest.map(row => [row.file, row]));
const products = new Map(productsFile.products.map(row => [row.idProduct, row]));
const prices = new Map(pricesFile.priceGuides.map(row => [row.idProduct, row]));
const runtime = jsonl(await fs.readFile(path.join(runtimeDir, 'production-printings.jsonl'), 'utf8'));
const slots = csv(await fs.readFile(path.join(runtimeDir, 'binder-price-audit-slots.csv'), 'utf8'));
const slotsByCard = new Map();
for (const slot of slots) {
  const key = slot.sourceCardId.toLowerCase();
  slotsByCard.set(key, [...(slotsByCard.get(key) ?? []), slot]);
}
let imageRows = [];
try { imageRows = jsonl(await fs.readFile(path.join(snapshot, 'image-checks.jsonl'), 'utf8')); } catch {}
const images = new Map(imageRows.map(row => [row.url, row]));
const detailCache = new Map();
const counts = { listedSets: listed.length, physicalSets: listed.filter(set => !excluded.has(set.id)).length,
  excludedDigitalSets: excluded.size, cards: new Set(runtime.map(row => row.sourceCardId)).size,
  printings: runtime.length, canonicalBinderPrintings: runtime.filter(row => row.canonicalBinderPrinting).length,
  actualBinderSlots: slots.length, exactSourceMappings: 0, existingLocalVerifiedMappings: 0,
  availablePrices: 0, unavailablePrices: 0, priceErrors: 0, sourceChainVerified: 0, highValueFlags: 0,
  reachableImagePrintings: 0, failedImagePrintings: 0, uncheckedImagePrintings: 0,
  unsafeMappedBinderSlots: slots.filter(slot => slot.actualProductIds && !slot.declaredProductIds).length,
};
const missingReasons = {}, auditFlags = {}, sets = [], highValue = [], productUsers = new Map();
const increment = (target, key) => { target[key] = (target[key] ?? 0) + 1; };
const report = await fs.open(path.join(output, 'card-variant-coverage.jsonl'), 'w');
const perSet = new Map();
for (const row of runtime) {
  if (excluded.has(row.setId)) throw new Error(`Digital set leaked into physical report: ${row.setId}`);
  if (!detailCache.has(row.sourceCardId)) detailCache.set(row.sourceCardId, await optional(snapshot, `cards/${row.sourceCardId}.json`));
  const source = detailCache.get(row.sourceCardId);
  const result = evaluatePrinting({ runtime: row, source, provenance: sourceByFile.get(`cards/${row.sourceCardId}.json`),
    products, prices, slots: slotsByCard.get(row.sourceCardId.toLowerCase()), image: images.get(row.image) });
  if (result.mappingEvidence === 'SOURCE_DECLARED_PRODUCT_ID') counts.exactSourceMappings++;
  if (result.mappingEvidence === 'EXISTING_LOCAL_VERIFIED_REGISTRY') counts.existingLocalVerifiedMappings++;
  if (result.priceStatus === 'AVAILABLE') counts.availablePrices++;
  else if (result.priceStatus === 'ERROR') counts.priceErrors++;
  else counts.unavailablePrices++;
  if (result.status === 'VERIFIED_SOURCE_CHAIN') counts.sourceChainVerified++;
  if (result.imageVerification.status === 'AVAILABLE') counts.reachableImagePrintings++;
  else if (result.imageVerification.status === 'NOT_CHECKED') counts.uncheckedImagePrintings++;
  else counts.failedImagePrintings++;
  result.missingReasons.forEach(reason => increment(missingReasons, reason));
  result.auditFlags.forEach(reason => increment(auditFlags, reason));
  if (result.auditFlags.includes('HIGH_PRICE_REQUIRES_PRODUCT_REVIEW')) {
    counts.highValueFlags++;
    highValue.push({ setId: row.setId, sourceCardId: row.sourceCardId, name: row.displayName,
      finish: row.finish, variant: row.variant, productId: result.cardmarketProductId,
      productName: result.cardmarketProductName, priceField: result.priceField, price: result.sourcePriceEur,
      mappingEvidence: result.mappingEvidence, sourceMetadata: result.printMetadata, reviewStatus: 'REQUIRES_OPERATOR_OR_INDEPENDENT_PRINT_VERIFICATION' });
  }
  if (result.cardmarketProductId) {
    const key = `${result.cardmarketProductId}|${result.priceField}`;
    productUsers.set(key, [...(productUsers.get(key) ?? []), { sourceCardId: row.sourceCardId, variant: row.variant, setId: row.setId }]);
  }
  perSet.set(row.setId, [...(perSet.get(row.setId) ?? []), result]);
  await report.appendFile(`${JSON.stringify(result)}\n`);
}
await report.close();
for (const listedSet of listed.filter(set => !excluded.has(set.id))) {
  const de = await optional(snapshot, `sets/de/${listedSet.id}.json`);
  const en = await optional(snapshot, `sets/en/${listedSet.id}.json`);
  const sourceCards = new Map([...(en?.cards ?? []), ...(de?.cards ?? [])].map(card => [card.id, card]));
  const uniqueRows = [...new Map((perSet.get(listedSet.id) ?? []).map(row => [row.sourceCardId, row])).values()];
  const total = de?.cardCount?.total ?? listedSet.cardCount?.total;
  const printed = de?.cardCount?.official ?? listedSet.cardCount?.official;
  const issues = [];
  if (!de) issues.push('GERMAN_SET_DETAIL_MISSING');
  if (!en) issues.push('ENGLISH_SET_DETAIL_MISSING');
  if (de && de.cardCount.total !== listedSet.cardCount?.total) issues.push('INDEX_DETAIL_TOTAL_DIFFER');
  if (sourceCards.size !== total) issues.push('DECLARED_TOTAL_DIFFERS_FROM_SOURCE_INVENTORY');
  if (uniqueRows.length !== sourceCards.size) issues.push('RUNTIME_AND_SET_INVENTORY_DIFFER');
  if (uniqueRows.some(row => row.language.name !== 'de')) issues.push('GERMAN_LOCALIZATION_INCOMPLETE');
  const values = perSet.get(listedSet.id) ?? [];
  const productionSet = production.sets.find(set => set.setId === listedSet.id);
  const englishListedSet = englishListed.find(set => set.id === listedSet.id);
  const officialLogo = ['30th', '30th-c'].includes(listedSet.id)
    ? 'https://www.pokemon.com/static-assets/content-assets/cms2-de-de/img/trading-card-game/series/me_series/30th/30th_logo_169_de.png' : null;
  const logo = listedSet.logo || listedSet.symbol || officialLogo || englishListedSet?.logo || englishListedSet?.symbol;
  const oldLogoUrl = logo ? /\.[a-z]+$/i.test(logo.substring(logo.lastIndexOf('/') + 1)) ? logo : `${logo}.png` : null;
  const logoUrl = productionSet?.logoUrl ?? oldLogoUrl;
  sets.push({ setId: listedSet.id, nameDe: listedSet.name, series: de?.serie ?? null,
    setCode: de?.abbreviation?.official ?? null, releaseDate: de?.releaseDate ?? null,
    printedTotal: printed, declaredMainSetTotal: printed, declaredCompleteCardCount: total,
    enumeratedSourceCardCount: sourceCards.size, germanSetCardCount: de?.cards?.length ?? null,
    unenumeratedDeclaredCardCount: Math.max(0, (total ?? 0) - sourceCards.size),
    auditedCardCount: uniqueRows.length,
    aboveMainSetCards: uniqueRows.filter(row => /^\d+$/.test(row.collectorNumber) && Number(row.collectorNumber) > printed).map(row => row.sourceCardId),
    specialNumberCards: uniqueRows.filter(row => !/^\d+$/.test(row.collectorNumber)).map(row => ({ sourceCardId: row.sourceCardId, collectorNumber: row.collectorNumber })),
    sourcePrintings: values.length, canonicalBinderPrintings: values.filter(row => row.canonicalBinderPrinting).length,
    actualBinderSlots: productionSet?.actualBinderSlots ?? null, emptyLayoutCells: productionSet?.emptyLayoutCells ?? null,
    pricedPrintings: values.filter(row => row.priceStatus === 'AVAILABLE').length,
    logo: { sourceUrl: logoUrl, check: images.get(logoUrl) ?? null,
      evidenceOrigin: Object.hasOwn(productionSet ?? {}, 'logoUrl') ? 'PRODUCTION_PARSER'
        : 'RECONSTRUCTED_FROM_FROZEN_PRE_FIX_LOGO_POLICY' },
    completeness: issues.length ? 'SOURCE_GAPS' : 'SOURCE_ENUMERATION_CONSISTENT',
    independentlyVerifiedPhysicalTotal: null,
    independentChecklistStatus: 'NOT_VERIFIED_AGAINST_COMPLETE_OFFICIAL_GERMAN_CHECKLIST',
    issues,
  });
}
counts.setLogosAvailable = sets.filter(set => set.logo.check?.status === 'AVAILABLE').length;
counts.setLogosMissing = sets.filter(set => !set.logo.sourceUrl).length;
counts.setLogosUnavailable = sets.filter(set => set.logo.check?.status === 'NOT_AVAILABLE').length;
counts.setLogoErrors = sets.filter(set => set.logo.check?.status === 'ERROR').length;
const collisions = [...productUsers].filter(([, rows]) => new Set(rows.map(row => row.sourceCardId)).size > 1)
  .map(([productAndField, rows]) => ({ productAndField, rows, status: 'REVIEW_REQUIRED_NOT_AUTOMATIC_ERROR' }));
const knownExpansions = new Set([...perSet.values()].flat().map(row => row.cardmarketExpansionId).filter(Boolean));
const knownProductIds = new Set([...perSet.values()].flat().map(row => row.cardmarketProductId).filter(Boolean));
const unmappedHighPrices = productsFile.products.filter(product => knownExpansions.has(product.idExpansion) && !knownProductIds.has(product.idProduct))
  .flatMap(product => ['trend', 'trend-holo'].flatMap(field => {
    const price = prices.get(product.idProduct)?.[field];
    return Number.isFinite(price) && price >= 1000 ? [{ productId: product.idProduct,
      name: product.name, expansionId: product.idExpansion, priceField: field, priceEur: price,
      sourceCardId: null, status: 'NO_VERIFIED_SOURCE_IDENTITY_DO_NOT_ASSIGN_BY_NAME' }] : [];
  }));
const snapshotFingerprint = crypto.createHash('sha256').update(JSON.stringify(manifest
  .filter(row => row.sha256).map(row => [row.file, row.sha256]).sort((a, b) => a[0].localeCompare(b[0])))).digest('hex');
const summary = { schemaVersion: 1, generatedAt: new Date().toISOString(), snapshot, runtimeDir,
  imageEvidenceFingerprint: crypto.createHash('sha256').update(JSON.stringify([...images].sort(([a], [b]) => a.localeCompare(b)))).digest('hex'),
  snapshotFingerprint, allPricesGuaranteed: false, counts, missingReasons, auditFlags,
  sourcePriceCreatedAt: pricesFile.createdAt, sourceProductsCreatedAt: productsFile.createdAt,
  distinctHighPriceProducts: new Set(highValue.map(row => `${row.productId}|${row.priceField}`)).size,
  crossCardProductReuse: collisions.length,
  unassignedHighPriceProductsInKnownExpansions: unmappedHighPrices.length,
  limitations: [
    'All physical sets listed in the German registry are included; this does not prove all possible German releases are listed upstream.',
    'Source-declared totals are checked against enumeration; no numeric total alone proves full physical checklist completeness.',
    'Existing local product registry is identified separately from upstream-declared links; no name or price heuristics are used by this audit.',
    'Cardmarket public price guide aggregates languages/condition; exact German-language sale value is not present in these files.',
    'Image HEAD validates URL reachability and MIME, not correctness of printed artwork.',
    'Audit evaluates source/parser/mapping/binder policies, not every live UI state or a real camera.',
    'Special source printings retained outside canonical master slots are explicitly identified, not silently collapsed.',
    'Portfolio audit asserts ownership policy only; no private collection was copied or modified.',
  ],
};
await Promise.all([
  fs.writeFile(path.join(output, 'summary.json'), JSON.stringify(summary, null, 2)),
  fs.writeFile(path.join(output, 'sets.json'), JSON.stringify(sets, null, 2)),
  fs.writeFile(path.join(output, 'high-price-review.json'), JSON.stringify(highValue, null, 2)),
  fs.writeFile(path.join(output, 'cross-card-product-review.json'), JSON.stringify(collisions, null, 2)),
  fs.writeFile(path.join(output, 'unmapped-high-price-products.json'), JSON.stringify(unmappedHighPrices, null, 2)),
]);
if (beforeArg) {
  const before = JSON.parse(await fs.readFile(beforeArg, 'utf8'));
  await fs.writeFile(path.join(output, 'before-after.json'), JSON.stringify(compareAuditSummaries(before, summary), null, 2));
}
console.log(JSON.stringify(summary));
