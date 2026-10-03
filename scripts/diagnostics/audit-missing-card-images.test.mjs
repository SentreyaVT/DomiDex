import test from 'node:test';
import assert from 'node:assert/strict';
import { buildInventory, imageCandidate, makeHeadPlan, classifyHead, reusableChecks, createHeadStartGate, SOURCES } from './audit-missing-card-images.mjs';

const image = 'https://assets.tcgdex.net/de/sv/example/001';
const card = (id, localId, url = image) => ({ id, localId, name: 'Deutsche Karte', image: url });
function inputs() {
  return {
    'sets-de.json': [{ id: 'example', name: 'Deutsch', cardCount: { official: 1, total: 2 } }, { id: 'A1', name: 'Pocket' }],
    'sets-en.json': [{ id: 'example', name: 'English' }, { id: 'A1', name: 'Pocket' }, { id: 'english-only', name: 'English only' }],
    'cards-de.json': [card('example-001', '001'), card('example-162', '162', ''), card('A1-001', '001')],
    'cards-en.json': [card('example-001', '001'), card('example-162', '162', 'https://assets.tcgdex.net/en/sv/example/162'), card('english-only-1', '1')],
    'series-tcgp.json': { id: 'tcgp', sets: [{ id: 'A1', name: 'Pocket' }] },
  };
}

test('only five exact public indices are requested; no detail or price endpoint', () => {
  assert.equal(SOURCES.length, 5);
  assert.deepEqual(SOURCES.map(([file]) => file), ['sets-de.json', 'sets-en.json', 'cards-de.json', 'cards-en.json', 'series-tcgp.json']);
  assert(SOURCES.every(([, url]) => url.startsWith('https://api.tcgdex.net/v2/')));
});

test('inventory counts exact physical union separately from Pocket and English-only sets', () => {
  const result = buildInventory(inputs());
  assert.equal(result.counts.physicalSets, 1);
  assert.equal(result.counts.unionCardIdsInGermanPhysicalSets, 2);
  assert.equal(result.counts.excludedPocketCardIds, 1);
  assert.equal(result.counts.outsideGermanSetIndexCardIds, 1);
  assert.equal(result.counts.missingGermanImage, 1);
  assert.equal(result.counts.missingBothImages, 0);
  assert.equal(result.counts.sameIdEnglishFallbackCandidates, 1);
  assert.equal(result.cards.find(row => row.sourceCardId === 'example-162').nameDe, 'Deutsche Karte');
  assert.equal(result.cards.find(row => row.sourceCardId === 'example-162').localId, '162');
  assert.equal(result.sets[0].declaredOfficial, 1); // Above-main 162 is retained, never range-filtered.
});

test('both missing is not a failed HTTP check and declared count does not invent cards', () => {
  const value = inputs();
  value['cards-en.json'][1].image = null;
  value['sets-de.json'][0].cardCount.total = 999;
  const result = buildInventory(value);
  assert.equal(result.counts.missingBothImages, 1);
  assert.equal(result.sets[0].enumeratedUnion, 2);
  assert.equal(result.sets[0].declaredTotalMatchesEnumeration, false);
  assert.equal(result.cards[1].imageState, 'BOTH_LANGUAGES_MISSING_OR_INVALID');
  assert(!JSON.stringify(result).includes('REACHABLE_HEAD'));
});

test('same-named different source ID never fills another image', () => {
  const value = inputs();
  value['cards-en.json'][1] = card('example-163', '163');
  const result = buildInventory(value);
  const original = result.cards.find(row => row.sourceCardId === 'example-162');
  assert.equal(original.selectedLanguage, null);
  assert.equal(original.enImage.status, 'MISSING_URL');
  assert.equal(result.counts.unionCardIdsInGermanPhysicalSets, 3);
});

test('longest known prefix cannot fall back to a shorter set on local-ID contradiction', () => {
  const value = inputs();
  value['sets-de.json'].push({ id: 'example-sub', name: 'Subset' });
  value['cards-de.json'].push(card('example-sub-1', 'sub-1'));
  const result = buildInventory(value);
  const invalid = result.cards.find(row => row.sourceCardId === 'example-sub-1');
  assert.equal(invalid.setId, 'example-sub');
  assert.equal(invalid.identityStatus, 'UNRESOLVED');
  assert.equal(invalid.selectedLanguage, null);
  assert(result.issues.some(row => row.kind === 'DE_LOCAL_ID_MISMATCH'));
});

test('cross-language local-ID conflict blocks candidates and remains explicit', () => {
  const value = inputs();
  value['cards-en.json'][0].localId = '002';
  const result = buildInventory(value);
  assert.equal(result.cards[0].identityStatus, 'UNRESOLVED');
  assert(result.cards[0].identityIssues.includes('CROSS_LANGUAGE_LOCAL_ID_CONFLICT'));
  assert(makeHeadPlan(result).every(row => row.references.every(ref => ref.sourceCardId !== 'example-001')));
});

test('duplicate identity fails closed even when one locale has a healthy sibling', () => {
  const value = inputs();
  value['cards-de.json'].push(card('example-001', '001', 'https://assets.tcgdex.net/de/conflicting'));
  const result = buildInventory(value);
  assert.equal(result.cards[0].identityStatus, 'UNRESOLVED');
  assert(result.issues.some(row => row.kind === 'DUPLICATE_SOURCE_ID'));
});

test('unknown and malformed identities are counted, never silently mapped from a name', () => {
  const value = inputs();
  value['cards-de.json'].push(card('unknown-1', '1'), card('../unsafe', '1'), card('example-2', ''));
  const result = buildInventory(value);
  assert.deepEqual(result.unknownCards, ['unknown-1']);
  assert.equal(result.issues.filter(row => row.kind === 'INVALID_SOURCE_ID').length, 2);
});

test('Pocket registry must be exact, nonempty, and duplicate-free', () => {
  for (const pocket of [{ id: 'wrong', sets: [{ id: 'A1' }] }, { id: 'tcgp', sets: [] },
    { id: 'tcgp', sets: [{ id: 'A1' }, { id: 'A1' }] }]) {
    const value = inputs(); value['series-tcgp.json'] = pocket;
    assert.throws(() => buildInventory(value));
  }
});

test('provider base receives only documented low/high qualifiers; qualified files retain query', () => {
  assert.deepEqual(imageCandidate(image), { status: 'DECLARED_BASE', declared: image, low: `${image}/low.webp`, high: `${image}/high.webp` });
  const qualified = `${image}/high.webp?version=1`;
  assert.equal(imageCandidate(qualified).low, qualified);
  assert.equal(imageCandidate(`${image}/`).high, `${image}/high.webp`);
});

test('blank and unsafe URLs are distinguished and never fetched', () => {
  assert.equal(imageCandidate(' ').status, 'MISSING_URL');
  for (const bad of ['http://assets.tcgdex.net/de/a', 'https://evil.test/a', 'https://user@assets.tcgdex.net/a',
    'https://assets.tcgdex.net:8443/a', 'file:///tmp/a.png', 'https://assets.tcgdex.net/a#fragment', { url: image }]) {
    assert.equal(imageCandidate(bad).status, 'INVALID_URL');
  }
});

test('plan deduplicates URLs but retains every card/quality provenance reference', () => {
  const result = buildInventory(inputs());
  const plan = makeHeadPlan(result);
  assert.equal(plan.length, 4);
  assert.equal(makeHeadPlan(result, 'high').length, 2);
  const qualified = inputs();
  for (const key of ['cards-de.json', 'cards-en.json']) qualified[key][0].image = `${image}/shared.png`;
  const shared = makeHeadPlan(buildInventory(qualified), 'both', 'all').find(row => row.url.endsWith('shared.png'));
  assert.equal(shared.references.length, 4);
  assert.throws(() => makeHeadPlan(result, 'thumbnail'));
});

test('404/410 are unavailable; timeout, rate limit, nonimage and server failure remain unknown', () => {
  assert.equal(classifyHead(200, 'image/webp'), 'REACHABLE_HEAD');
  for (const code of [404, 410]) assert.equal(classifyHead(code, 'text/html'), 'NOT_AVAILABLE');
  for (const [code, mime] of [[429, 'image/webp'], [503, 'text/html'], [200, 'text/html'], [undefined, undefined]]) {
    assert.equal(classifyHead(code, mime), 'UNKNOWN');
  }
});

test('resume reuses only matching fresh definitive evidence; never UNKNOWN or stale', () => {
  const now = Date.parse('2026-10-03T12:00:00Z');
  const row = { url: image, sourceFingerprint: 'a', planFingerprint: 'b', checkedAt: new Date(now).toISOString(),
    status: 'REACHABLE_HEAD', httpStatus: 200, contentType: 'image/webp' };
  const urls = new Set([image]);
  assert.equal(reusableChecks([row], urls, 'a', 'b', now).size, 1);
  assert.equal(reusableChecks([{ ...row, checkedAt: '2026-10-01T12:00:00Z' }], urls, 'a', 'b', now).size, 0);
  assert.equal(reusableChecks([{ ...row, status: 'UNKNOWN', httpStatus: 429 }], urls, 'a', 'b', now).size, 0);
  for (const changed of [{ sourceFingerprint: 'x' }, { planFingerprint: 'x' }, { url: `${image}/foreign` },
    { status: 'NOT_AVAILABLE' }, { checkedAt: 'not-a-date' }]) {
    assert.throws(() => reusableChecks([{ ...row, ...changed }], urls, 'a', 'b', now));
  }
});

test('global gate spaces actual starts after delayed timers rather than catching up in a burst', async () => {
  let clock = 0;
  const starts = [];
  const gate = createHeadStartGate({ now: () => clock, sleep: async millis => { clock += millis + 500; } });
  await Promise.all(Array.from({ length: 12 }, () => gate(() => true, () => { starts.push(clock); })));
  assert.equal(starts.length, 12);
  assert(starts.slice(1).every((start, i) => start - starts[i] >= 125));
  for (const begin of starts) assert(starts.filter(at => at >= begin && at < begin + 1000).length <= 8);
});

test('global gate handles early timers and rechecks a 429 stop immediately before starting', async () => {
  let clock = 0, stopped = false;
  const starts = [];
  const gate = createHeadStartGate({ now: () => clock, sleep: async millis => { clock += Math.min(50, millis); } });
  await Promise.all(Array.from({ length: 4 }, () => gate(() => !stopped, () => {
    starts.push(clock);
    if (starts.length === 2) stopped = true;
  })));
  assert.deepEqual(starts, [0, 125]);
});

test('global gate releases before request completion but never exceeds the permitted start budget', async () => {
  let clock = 0, started = 0;
  const neverCompletes = new Promise(() => {});
  const gate = createHeadStartGate({ now: () => clock, sleep: async millis => { clock += millis; } });
  const tickets = await Promise.all(Array.from({ length: 4 }, () => gate(() => started < 3, () => {
    started++; return neverCompletes;
  })));
  assert.equal(started, 3);
  assert.equal(tickets.filter(Boolean).length, 3);
  assert(tickets.filter(Boolean).every(ticket => ticket.pending === neverCompletes));
});
