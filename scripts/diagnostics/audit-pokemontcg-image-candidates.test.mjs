import test from 'node:test';
import assert from 'node:assert/strict';
import { collectCandidates, sourceNumber } from './audit-pokemontcg-image-candidates.mjs';

function fixture() {
  const ids = ['ecard2', 'ecard3', 'smp'];
  const sets = ids.map(id => ({ id, name: id, cardCount: { official: 100, total: 120 } }));
  const upstreamSets = ids.map(id => ({ id, name: id, printedTotal: 100, total: 120 }));
  const source = { sourceCardId: 'ecard2-H01', setId: 'ecard2', localId: 'H01',
    nameDe: 'Ampharos', nameEn: 'Ampharos', identityStatus: 'EXACT_SOURCE_ID', identityIssues: [],
    imageState: 'BOTH_LANGUAGES_MISSING_OR_INVALID' };
  const card = (id, number = '1', name = 'Ampharos') => ({ id: `${id}-${number}`, number, name,
    images: { small: `https://images.pokemontcg.io/${id}/${number}.png`,
      large: `https://images.pokemontcg.io/${id}/${number}_hires.png` } });
  return { inventory: { data: { cards: [source] } }, sets, upstreamSets,
    cards: { ecard2: [card('ecard2', 'H1')], ecard3: [card('ecard3')], smp: [card('smp')] } };
}
const run = f => collectCandidates(f.inventory, f.sets, f.upstreamSets, f.cards);

test('exact set plus narrow H zero padding matches and retains verbatim published URLs', () => {
  const f = fixture(), r = run(f);
  assert.equal(r.candidates.length, 1);
  assert.equal(r.candidates[0].sourceCardId, 'ecard2-H01');
  assert.equal(r.candidates[0].upstreamId, 'ecard2-H1');
  assert.equal(r.candidates[0].large, f.cards.ecard2[0].images.large);
  assert.equal(r.candidates[0].availability, 'NOT_HTTP_CHECKED');
  assert.equal(r.candidates[0].language, 'en');
  assert.equal(sourceNumber('smp', 'H01'), 'H01');
  assert.equal(sourceNumber('ecard2', '103a'), '103a');
});
test('same English name alone never substitutes another number', () => {
  const f = fixture();
  f.inventory.data.cards[0].localId = 'H02';
  f.inventory.data.cards[0].sourceCardId = 'ecard2-H02';
  assert.deepEqual(run(f).rejected[0].reasons, ['NO_EXACT_SET_NUMBER']);
});
test('same exact number with a different English name is rejected', () => {
  const f = fixture(); f.cards.ecard2[0].name = 'Arcanine';
  assert.deepEqual(run(f).rejected[0].reasons, ['ENGLISH_NAME_MISMATCH']);
});
test('source identity conflict cannot pass even with an exact upstream name', () => {
  const f = fixture(); f.inventory.data.cards[0].sourceCardId = 'ecard3-H01';
  assert.deepEqual(run(f).rejected[0].reasons, ['SOURCE_IDENTITY_UNRESOLVED']);
});
test('English GX suffix punctuation is corroboration after exact identity', () => {
  const f = fixture(); f.inventory.data.cards[0].nameEn = 'Naganadel GX';
  f.cards.ecard2[0].name = 'Naganadel-GX';
  assert.equal(run(f).candidates[0].nameComparison, 'TYPOGRAPHIC_SUFFIX_OR_CASE_ONLY');
});
test('upstream ID-number conflicts and duplicate numbers fail closed', () => {
  const f = fixture(); f.cards.ecard2[0].id = 'ecard3-H1';
  assert.throws(() => run(f), /ID\/number conflict/);
  const g = fixture(); g.cards.ecard2.push({ ...g.cards.ecard2[0], id: 'ecard2-other' });
  assert.throws(() => run(g), /Duplicate ecard2 numbers/);
});
test('wrong set name or printed total cannot be accepted as set equivalence', () => {
  const f = fixture(); f.upstreamSets[0].name = 'Other';
  assert.throws(() => run(f), /Set English name mismatch/);
  const g = fixture(); g.upstreamSets[0].printedTotal = 101;
  assert.throws(() => run(g), /Printed set total mismatch/);
});
test('different variant totals are explicit and do not broaden the exact-number join', () => {
  const f = fixture(); f.upstreamSets[0].total = 124;
  const r = run(f);
  assert.equal(r.setEvidence[0].totalCountsEqual, false);
  assert.equal(r.candidates.length, 1);
});
test('missing images and unapproved origins are rejected without inferred URLs', () => {
  const f = fixture(); delete f.cards.ecard2[0].images;
  assert.deepEqual(run(f).rejected[0].reasons, ['INVALID_PUBLISHED_IMAGE_URL']);
  const g = fixture(); g.cards.ecard2[0].images.large = 'https://example.org/wrong.png';
  assert.deepEqual(run(g).rejected[0].reasons, ['INVALID_PUBLISHED_IMAGE_URL']);
});
test('a/b collector variants are never collapsed into a base collector number', () => {
  const f = fixture();
  Object.assign(f.inventory.data.cards[0], { sourceCardId: 'ecard2-103a', localId: '103a' });
  Object.assign(f.cards.ecard2[0], { id: 'ecard2-103', number: '103' });
  assert.deepEqual(run(f).rejected[0].reasons, ['NO_EXACT_SET_NUMBER']);
});
test('existing image records are outside this missing-only candidate inventory', () => {
  const f = fixture(); f.inventory.data.cards[0].imageState = 'SAME_ID_ENGLISH_FALLBACK_PRESENT';
  assert.equal(run(f).candidates.length, 0);
  assert.equal(run(f).rejected.length, 0);
});
