import test from 'node:test';
import assert from 'node:assert/strict';
import { selectCandidates } from './verify-card-artwork-candidates.mjs';
const choice = language => ({ language, lowUrl: `https://assets.tcgdex.net/${language}/swsh/swsh9/TG01/low.webp`, highUrl: `https://assets.tcgdex.net/${language}/swsh/swsh9/TG01/high.webp` });
const row = { sourceCardId: 'swsh9tg-TG01', setId: 'swsh9tg', sourceLocalId: 'TG01', candidates: [choice('en'), choice('de')] };
test('prefer exact declared German candidate and leave foreign-only unresolved', () => {
  assert.equal(selectCandidates([row])[0].candidate.language, 'de');
  assert.deepEqual(selectCandidates([{ ...row, candidates: [choice('fr')] }]), []);
});
test('reject duplicate/contradictory identity and unapproved URLs before networking', () => {
  assert.throws(() => selectCandidates([row, row]));
  assert.throws(() => selectCandidates([{ ...row, sourceLocalId: 'TG02' }]));
  for (const highUrl of ['http://assets.tcgdex.net/x.webp', 'https://bad.example/x.webp', 'https://assets.tcgdex.net/x.webp?token=x']) {
    assert.throws(() => selectCandidates([{ ...row, candidates: [{ ...choice('de'), highUrl }] }]));
  }
});
