import test from 'node:test';
import assert from 'node:assert/strict';
import { accessBoundaryReason, inspectOfficialPages, parseOfficialCard } from './discover-official-sm75-artwork.mjs';
const card = { setId: 'sm7.5', sourceCardId: 'sm7.5-70', localId: '70', nameDe: 'Amalia' };
const html = '<meta name="pkm-id" content="70/70" /><meta name="pkm-subcategory" content="Majestät Der Drachen" />' +
  '<meta property="og:image" content="https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/SM75/SM75_DE_70.png" /><h1>Amalia</h1>';
test('exact official set, collector, German name and image reference', () => {
  assert.equal(parseOfficialCard(html, card).sourceCardId, 'sm7.5-70');
});
test('same name cannot cross expansion, number, language or image identity', () => {
  for (const invalid of [html.replace('70/70', '69/70'), html.replace('Majestät Der Drachen', 'Anderes Set'),
    html.replace('SM75_DE_70', 'SM75_DE_69'), html.replace('cms2-de-de', 'cms2-en-us'),
    html.replace('<h1>Amalia', '<h1>Wela'), html.replace('assets.pokemon.com', 'example.org')]) {
    assert.throws(() => parseOfficialCard(invalid, card));
  }
  assert.throws(() => parseOfficialCard(html, { ...card, setId: 'sm75' }));
  assert.throws(() => parseOfficialCard(html, { ...card, sourceCardId: 'sm7.5-69' }));
});

test('HTTP-200 Incapsula and challenge markers cannot establish card identity', () => {
  for (const challenge of [
    '<iframe src="/_Incapsula_Resource?synthetic"></iframe>',
    '<h1>Request unsuccessful</h1>',
    '<span>Incident ID: synthetic-only</span>',
    '<span>incident&nbsp;id: synthetic-only</span>',
    '<title>Just a moment</title>',
    '<h1 class="heading">Access Denied</h1>',
  ]) {
    assert.ok(accessBoundaryReason(challenge));
    assert.throws(() => parseOfficialCard(`${html}${challenge}`, card), /ACCESS_BOUNDARY/);
  }
  assert.equal(accessBoundaryReason(html), null);
  assert.equal(accessBoundaryReason('<p>A card with an unsuccessful attack.</p>'), null);
});

test('first HTTP-200 iframe boundary stops before any second request or retry', async () => {
  const requested = [], saved = [];
  let pauses = 0;
  const results = await inspectOfficialPages([card, { ...card, sourceCardId: 'sm7.5-71', localId: '71' }], {
    fetchPage: async url => { requested.push(url); return new Response('<iframe src="/_Incapsula_Resource?synthetic"></iframe>', { status: 200 }); },
    savePage: async (id, bytes) => { saved.push({ id, bytes: bytes.length }); },
    pause: async () => { pauses++; },
  });
  assert.equal(requested.length, 1);
  assert.equal(saved.length, 1);
  assert.equal(pauses, 0);
  assert.equal(results.length, 1);
  assert.equal(results[0].status, 'ACCESS_BOUNDARY_STOP');
  assert.equal(results[0].accessBoundary, 'INCAPSULA_RESOURCE');
  assert.equal(results[0].candidate, undefined);
  assert.ok(!JSON.stringify(results).includes('synthetic'));
});

test('HTTP-403 and HTTP-429 remain immediate access boundaries', async () => {
  for (const status of [403, 429]) {
    let requests = 0;
    const results = await inspectOfficialPages([card, card], {
      fetchPage: async () => { requests++; return new Response('unread challenge', { status }); },
      savePage: async () => assert.fail('HTTP boundary body must not be saved'),
      pause: async () => assert.fail('No retry delay after an access boundary'),
    });
    assert.equal(requests, 1);
    assert.equal(results[0].status, 'ACCESS_BOUNDARY_STOP');
  }
});

test('evidence write failure never resumes requests after a recognized challenge', async () => {
  let requests = 0;
  const results = await inspectOfficialPages([card, card], {
    fetchPage: async () => { requests++; return new Response('Request unsuccessful', { status: 200 }); },
    savePage: async () => { throw Error('synthetic disk failure'); },
    pause: async () => assert.fail('No subsequent wait or request'),
  });
  assert.equal(requests, 1);
  assert.equal(results[0].status, 'ACCESS_BOUNDARY_STOP');
  assert.equal(results[0].evidenceError, 'PAGE_EVIDENCE_WRITE_FAILED');
});

test('ordinary identity errors stay unresolved, never verified or mistaken for challenge evidence', async () => {
  const results = await inspectOfficialPages([card], {
    fetchPage: async () => new Response('<h1>Other page</h1>', { status: 200 }),
    savePage: async () => {}, pause: async () => {},
  });
  assert.equal(results[0].status, 'UNRESOLVED');
  assert.equal(results[0].error, 'PAGE_IDENTITY_MISMATCH');
  assert.equal(results[0].candidate, undefined);
});
