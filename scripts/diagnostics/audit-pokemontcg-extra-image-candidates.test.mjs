import test from 'node:test';
import assert from 'node:assert/strict';
import { collectExtraCandidates, EXTRA_SETS } from './audit-pokemontcg-extra-image-candidates.mjs';
import { adaptCandidateBytes } from './adapt-pokemontcg-image-candidates.mjs';

function fixture() {
  const sets = EXTRA_SETS.map(([sourceId]) => ({ id: sourceId, name: sourceId, cardCount: { official: 100, total: 100 } }));
  const published = EXTRA_SETS.map(([sourceId, upstreamId]) => ({ id: upstreamId, name: sourceId, printedTotal: 100, total: 100 }));
  const cards = Object.fromEntries(EXTRA_SETS.map(([, upstreamId]) => [upstreamId, [{ id: `${upstreamId}-1`, number: '1',
    name: 'Example', images: { small: `https://images.pokemontcg.io/${upstreamId}/1.png`,
      large: `https://images.pokemontcg.io/${upstreamId}/1_hires.png` } }]]));
  const row = { sourceCardId: 'sm7.5-1', setId: 'sm7.5', localId: '1', nameDe: 'Beispiel', nameEn: 'Example',
    identityStatus: 'EXACT_SOURCE_ID', identityIssues: [], imageState: 'BOTH_LANGUAGES_MISSING_OR_INVALID' };
  return { sets, published, cards, inventory: { data: { cards: [row] } } };
}
const run = f => collectExtraCandidates(f.inventory, f.sets, f.published, f.cards);

test('explicit set alias needs exact name/printed total/number and preserves both identities', () => {
  const f = fixture(), r = run(f);
  assert.equal(r.candidates.length, 1);
  assert.equal(r.candidates[0].sourceCardId, 'sm7.5-1');
  assert.equal(r.candidates[0].upstreamId, 'sm75-1');
  assert.equal(r.candidates[0].large, f.cards.sm75[0].images.large);
  assert.equal(r.candidates[0].availability, 'NOT_HTTP_CHECKED');
});
test('same set alias with wrong name or printed total is unresolved', () => {
  const f = fixture(); f.published.find(x => x.id === 'sm75').name = 'Different set';
  assert.deepEqual(run(f).rejected[0].reasons, ['SET_ENGLISH_NAME_NOT_EXACT']);
  const g = fixture(); g.published.find(x => x.id === 'sm75').printedTotal = 99;
  assert.deepEqual(run(g).rejected[0].reasons, ['SET_PRINTED_TOTAL_MISMATCH']);
});
test('same English name on a different number never creates a candidate', () => {
  const f = fixture();
  Object.assign(f.inventory.data.cards[0], { sourceCardId: 'sm7.5-2', localId: '2' });
  assert.deepEqual(run(f).rejected[0].reasons, ['NO_EXACT_SET_NUMBER']);
});
test('wrong English name at the exact number is rejected', () => {
  const f = fixture(); f.cards.sm75[0].name = 'Other';
  assert.deepEqual(run(f).rejected[0].reasons, ['ENGLISH_NAME_MISMATCH']);
});
test('CC ordinal is never inferred from a matching name and a historic number', () => {
  const f = fixture();
  Object.assign(f.inventory.data.cards[0], { sourceCardId: 'cel25cc-CC001', setId: 'cel25cc', localId: 'CC001' });
  f.sets.find(x => x.id === 'cel25cc').name = 'Celebrations Classic Collection';
  f.published.find(x => x.id === 'cel25c').name = 'Celebrations: Classic Collection';
  Object.assign(f.cards.cel25c[0], { id: 'cel25c-2_A', number: '2' });
  const r = run(f);
  assert.equal(r.candidates.length, 0);
  assert.deepEqual(r.rejected[0].reasons, ['NO_EXACT_SET_NUMBER']);
  assert.deepEqual(r.setEvidence.at(-1).upstreamIdNumberExceptions, [{ id: 'cel25c-2_A', number: '2' }]);
});
test('duplicate historic Classic Collection numbers cannot select the first row', () => {
  const f = fixture();
  Object.assign(f.inventory.data.cards[0], { sourceCardId: 'cel25cc-15', setId: 'cel25cc', localId: '15' });
  f.cards.cel25c = [1, 2].map(n => ({ ...f.cards.cel25c[0], id: `cel25c-15_A${n}`, number: '15' }));
  assert.deepEqual(run(f).rejected[0].reasons, ['AMBIGUOUS_UPSTREAM_NUMBER']);
});
test('individual Classic Collection id/number conflict still rejects even with matching number', () => {
  const f = fixture();
  Object.assign(f.inventory.data.cards[0], { sourceCardId: 'cel25cc-2', setId: 'cel25cc', localId: '2' });
  Object.assign(f.cards.cel25c[0], { id: 'cel25c-2_A', number: '2' });
  assert.deepEqual(run(f).rejected[0].reasons, ['UPSTREAM_ID_NUMBER_CONFLICT']);
});
test('source-ID conflict and unapproved image origin cannot pass', () => {
  const f = fixture(); f.inventory.data.cards[0].sourceCardId = 'sm75-1';
  assert.deepEqual(run(f).rejected[0].reasons, ['SOURCE_IDENTITY_UNRESOLVED']);
  const g = fixture(); g.cards.sm75[0].images.large = 'https://example.org/image.png';
  assert.deepEqual(run(g).rejected[0].reasons, ['INVALID_PUBLISHED_IMAGE_URL']);
});
test('adapter refuses any unpinned report even if it claims the correct count', () => {
  assert.throws(() => adaptCandidateBytes(Buffer.from(JSON.stringify({ counts: { candidates: 242 }, candidates: [] }))),
    /hash mismatch/);
});
