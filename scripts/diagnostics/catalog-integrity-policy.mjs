/** Pure reporting policy: never invents a product, printing, language or price. */
import quarantinedLinks from './cardmarket-source-quarantine.json' with { type: 'json' };

export function sourcePrintingMetadata(printing) {
  return {
    sourcePrintingId: printing?.variantId ?? null,
    type: printing?.type ?? null,
    size: printing?.size ?? null,
    subtype: printing?.subtype ?? null,
    foil: printing?.foil ?? null,
    stamps: Array.isArray(printing?.stamp) ? [...printing.stamp] : printing?.stamp ? [printing.stamp] : [],
  };
}

// Independent reconstruction of the Android parser's physical descriptor. A
// local disambiguated ID is not source evidence, and raw IDs alone can collide.
function sourcePrintingIdentity(printing) {
  const type = typeof printing?.type === 'string' ? printing.type : '';
  const finish = /reverse/i.test(type) ? 'Reverse Holo' : /holo/i.test(type) ? 'Holo'
    : /^normal$/i.test(type) ? 'Normal' : type.trim() ? type : 'Normal';
  // Android preserves a nonblank size exactly, while trimming metadata markers.
  const parts = [typeof printing?.size === 'string' && printing.size.trim() ? printing.size : finish];
  const append = (label, value) => {
    if (typeof value === 'string' && value.trim()) parts.push(`${label}: ${value.trim()}`);
  };
  append('Untertyp', printing?.subtype);
  const stamps = new Map();
  for (const value of Array.isArray(printing?.stamp) ? printing.stamp : [printing?.stamp]) {
    if (typeof value !== 'string' || !value.trim()) continue;
    const stamp = value.trim();
    if (!stamps.has(stamp.toLowerCase())) stamps.set(stamp.toLowerCase(), stamp);
  }
  append('Stempel', [...stamps].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([, value]) => value).join(' + '));
  append('Folie', printing?.foil);
  return { finish, descriptor: parts.join(' · ') };
}

const positiveId = (value) => Number.isSafeInteger(value) && value > 0 ? value : null;

export function evaluatePrinting({ runtime, source, provenance, products, prices, slots = [], image, highPriceEur = 1000 }) {
  const reasons = new Set();
  const flags = new Set();
  const matchingPrintings = (Array.isArray(source?.variants_detailed) ? source.variants_detailed : []).filter(row => {
    if (row?.variantId !== runtime.sourcePrintingId) return false;
    const identity = sourcePrintingIdentity(row);
    return identity.finish === runtime.finish && identity.descriptor === runtime.variant;
  });
  const print = matchingPrintings[0];
  const declaredIds = new Set(matchingPrintings.map(row => positiveId(row.thirdParty?.cardmarket)).filter(id => id !== null));
  // Pricing-only metadata can contradict a link, but never establishes one.
  const evidenceIds = new Set([...declaredIds, ...matchingPrintings
    .map(row => positiveId(row.pricing?.cardmarket?.idProduct)).filter(id => id !== null)]);
  const conflicting = evidenceIds.size > 1;
  if (conflicting) reasons.add('SOURCE_PRINTING_IDENTITY_CONFLICT');
  const sourceId = conflicting ? null : declaredIds.values().next().value ?? null;
  const declaredProductId = conflicting || !print ? null : sourceId ?? positiveId(runtime.localVerifiedProductId);
  const quarantined = quarantinedLinks.some(row => row.sourceCardId === runtime.sourceCardId.toLowerCase() && row.rejectedProductId === declaredProductId);
  const productId = quarantined ? null : declaredProductId;
  if (quarantined) reasons.add('SOURCE_PRODUCT_ID_QUARANTINED');
  const product = productId === null ? null : products.get(productId);
  const priceRow = productId === null ? null : prices.get(productId);
  const field = runtime.finish.toLowerCase().includes('reverse') ? 'trend-holo' : 'trend';
  const rawPrice = priceRow?.[field];
  const price = Number.isFinite(rawPrice) && rawPrice > 0 ? Math.round(rawPrice * 100) / 100 : null;
  const mappedIds = [...new Set(runtime.actualProductIds)];
  const language = provenance?.url?.includes('/de/') ? 'de' : provenance?.url?.includes('/en/') ? 'en' : 'unknown';
  if (!source) reasons.add('CARD_DETAIL_MISSING');
  else {
    if (source.id !== runtime.sourceCardId) reasons.add('SOURCE_CARD_ID_MISMATCH');
    if (source.set?.id !== runtime.setId) reasons.add('SOURCE_SET_ID_MISMATCH');
    if (source.localId !== runtime.sourceLocalId) reasons.add('SOURCE_LOCAL_ID_MISMATCH');
    if (!print) reasons.add('SOURCE_PRINTING_METADATA_MISSING');
    if (print?.variantId === 'generated') reasons.add('SOURCE_PRINTING_IS_GENERATED_PLACEHOLDER');
  }
  for (const issue of runtime.issues ?? []) {
    // Classic subsets retain printed historic numbers, independently of source localId.
    if (issue === 'DETAIL_NUMBER_MISMATCH' && source?.localId === runtime.sourceLocalId) continue;
    if (issue.startsWith('MANUAL_') || issue.startsWith('DETAIL_')) reasons.add(issue);
  }
  if (!runtime.hasGermanName) reasons.add('GERMAN_CARD_NAME_MISSING');
  if (language !== 'de') flags.add('GERMAN_DETAIL_NOT_VERIFIED');
  if (!runtime.image) reasons.add('IMAGE_URL_MISSING');
  else if (!image) flags.add('IMAGE_REACHABILITY_NOT_CHECKED');
  else if (image.status !== 'AVAILABLE') reasons.add(image.status === 'ERROR' ? 'IMAGE_REQUEST_ERROR' : 'IMAGE_NOT_AVAILABLE');
  if (productId === null) reasons.add('EXACT_PRODUCT_MAPPING_MISSING');
  else if (!product) reasons.add('PRODUCT_NOT_IN_CARDMARKET_CATALOG');
  else if (!priceRow) reasons.add('PRICE_GUIDE_RECORD_MISSING');
  else if (price === null) reasons.add(rawPrice === 0 ? 'PRICE_FIELD_ZERO' : 'PRICE_FIELD_MISSING_OR_INVALID');
  if (mappedIds.length > 1) reasons.add('RUNTIME_MAPPING_AMBIGUOUS');
  if (mappedIds.length && !productId) reasons.add('RUNTIME_MAPPING_NOT_SOURCE_VERIFIED');
  if (productId && mappedIds.some(id => id !== productId)) reasons.add('RUNTIME_PRODUCT_ID_MISMATCH');
  if (productId && product && mappedIds.length === 0) reasons.add('RUNTIME_EXACT_MAPPING_MISSING');
  if (runtime.priceField !== field) reasons.add('RUNTIME_PRICE_FIELD_MISMATCH');
  if (mappedIds.length === 1 && mappedIds[0] === productId && runtime.actualPrices[0] !== price) reasons.add('RUNTIME_PRICE_VALUE_MISMATCH');
  const matchedSlots = slots.filter(slot => slot.sourceCardId?.toLowerCase() === runtime.sourceCardId.toLowerCase()
    && slot.finish === runtime.finish && slot.variant === runtime.variant);
  if (runtime.canonicalBinderPrinting && matchedSlots.length === 0) reasons.add('CANONICAL_BINDER_SLOT_MISSING');
  if (runtime.canonicalBinderPrinting && matchedSlots.length > 1) reasons.add('DUPLICATE_CANONICAL_BINDER_SLOT');
  if (!runtime.canonicalBinderPrinting) flags.add('SPECIAL_PRINT_NOT_SEPARATE_CANONICAL_BINDER_SLOT');
  if (price !== null && price >= highPriceEur) flags.add('HIGH_PRICE_REQUIRES_PRODUCT_REVIEW');
  if (productId !== null) flags.add('CARDMARKET_PRICE_IS_LANGUAGE_AGGREGATE');
  const technicalError = runtime.detailStatus === 'DETAIL_PARSE_ERROR' || (!source && provenance?.status === 'ERROR');
  const unsafe = [...reasons].some(reason => /MISMATCH|AMBIGUOUS|NOT_SOURCE_VERIFIED/.test(reason));
  const runtimeResolvesExactProduct = mappedIds.length === 1 && mappedIds[0] === productId;
  const priceStatus = technicalError ? 'ERROR' : product && price !== null && runtimeResolvesExactProduct && !unsafe ? 'AVAILABLE' : 'NOT_AVAILABLE';
  return {
    ...runtime,
    language: { name: runtime.hasGermanName ? 'de' : 'source-fallback', detail: language,
      image: runtime.image?.includes('/de/') || runtime.image?.includes('_DE_') ? 'de'
        : runtime.image?.includes('/en/') ? 'en' : 'unknown',
      germanPrintingSourceDeclared: language === 'de' && !!print && print.variantId !== 'generated',
      physicalGermanPrintingIndependentlyVerified: false, priceGuideLanguage: 'all-languages-aggregate' },
    printMetadata: sourcePrintingMetadata(print),
    imageVerification: image ?? { status: runtime.image ? 'NOT_CHECKED' : 'NOT_AVAILABLE' },
    binderSlot: matchedSlots.map(slot => ({ index: Number(slot.slotIndex), id: slot.slotId })),
    binderPlacementPolicy: runtime.canonicalBinderPrinting ? 'CANONICAL_SLOT' : 'ADDITIONAL_SOURCE_PRINT',
    cardmarketProductId: productId,
    rejectedSourceProductId: quarantined ? declaredProductId : null,
    cardmarketProductName: product?.name ?? null,
    cardmarketExpansionId: product?.idExpansion ?? null,
    mappingEvidence: quarantined ? 'REVIEWED_SOURCE_CONTRADICTION' : sourceId ? 'SOURCE_DECLARED_PRODUCT_ID' : productId ? 'EXISTING_LOCAL_VERIFIED_REGISTRY' : 'NONE',
    priceField: field,
    priceEur: priceStatus === 'AVAILABLE' ? price : null,
    sourcePriceEur: price,
    priceStatus,
    status: technicalError ? 'ERROR' : reasons.size ? 'GAP' : 'VERIFIED_SOURCE_CHAIN',
    missingReasons: [...reasons], auditFlags: [...flags],
    portfolioContribution: 'ONLY_OWNED_QUANTITY_NOT_BINDER_REFERENCES',
    sourceUpdatedAt: source?.updated ?? null,
    detailReadAt: provenance?.readAt ?? null,
  };
}

export function compareAuditSummaries(before, after) {
  if (before.snapshotFingerprint !== after.snapshotFingerprint) throw new Error('Before/after must use the same frozen source snapshot');
  if (before.imageEvidenceFingerprint !== after.imageEvidenceFingerprint) throw new Error('Before/after must use the same image-check evidence');
  const keys = new Set([...Object.keys(before.missingReasons), ...Object.keys(after.missingReasons)]);
  return { snapshotFingerprint: after.snapshotFingerprint,
    before: before.counts, after: after.counts,
    missingReasons: Object.fromEntries([...keys].sort().map(key => [key, {
      before: before.missingReasons[key] ?? 0, after: after.missingReasons[key] ?? 0,
      delta: (after.missingReasons[key] ?? 0) - (before.missingReasons[key] ?? 0),
    }])) };
}
