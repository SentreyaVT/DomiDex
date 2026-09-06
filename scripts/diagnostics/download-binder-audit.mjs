// Read-only public-source snapshot for the exhaustive Binder price audit.
// No app, account, Firestore, Room, signing or release changes.
import fs from 'node:fs/promises';
import path from 'node:path';

const root = path.resolve(process.argv[2]);
const api = 'https://api.tcgdex.net/v2';
await fs.mkdir(path.join(root, 'cards'), { recursive: true });
let requests = 0, cacheHits = 0, nextStart = 0, pauseUntil = 0;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function request(url, file, attempts = 2) {
  try { return JSON.parse(await fs.readFile(file, 'utf8')); } catch {}
  for (let attempt = 0; attempt < attempts; attempt++) {
    const start = Math.max(Date.now(), nextStart, pauseUntil);
    nextStart = start + 70; // Max ~14 requests/sec, additionally bounded by eight workers.
    await sleep(Math.max(0, start - Date.now()));
    requests++;
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(20000),
        headers: { 'User-Agent': 'DomiDex-readonly-binder-coverage-audit/1.0' } });
      if (response.status === 429) {
        const retry = Number(response.headers.get('retry-after'));
        pauseUntil = Date.now() + (Number.isFinite(retry) && retry > 0 ? retry * 1000 : 30000);
        if (attempt + 1 < attempts) continue;
      }
      if (!response.ok) {
        const error = new Error(`HTTP_${response.status}`);
        if (response.status >= 500 && attempt + 1 < attempts) { await sleep(1000); continue; }
        throw error;
      }
      const body = await response.text();
      const data = JSON.parse(body);
      await fs.writeFile(file, body);
      return data;
    } catch (error) {
      if (attempt + 1 < attempts && !/^HTTP_4/.test(error.message)) { await sleep(1000); continue; }
      throw error;
    }
  }
}
const started = new Date().toISOString();
const [sets, german, english] = await Promise.all([
  request(`${api}/de/sets`, path.join(root, 'sets-de.json')),
  request(`${api}/de/cards`, path.join(root, 'cards-de.json')),
  request(`${api}/en/cards`, path.join(root, 'cards-en.json')),
]);
if (![sets, german, english].every(Array.isArray)) throw new Error('Invalid source index');
const prefixes = sets.map(s => s.id).sort((a, b) => b.length - a.length);
const unique = new Map([...english, ...german].map(c => [c.id, c]));
const cards = [...unique.values()].filter(c => prefixes.some(s => c.id.startsWith(`${s}-`)));
console.log(JSON.stringify({ stage: 'indexes', sets: sets.length, germanCards: german.length, scopedCards: cards.length }));
await Promise.all([
  request(`${api}/de/series/tcgp`, path.join(root, 'series-tcgp.json')),
  request('https://downloads.s3.cardmarket.com/productCatalog/productList/products_singles_6.json', path.join(root, 'products.json')),
  request('https://downloads.s3.cardmarket.com/productCatalog/priceGuide/price_guide_6.json', path.join(root, 'prices.json')),
]);
console.log(JSON.stringify({ stage: 'cardmarket_snapshot_ready' }));
let index = 0, completed = 0, ok = 0;
const failures = [];
const startedMs = Date.now();
async function saveSummary(final = false) {
  const summary = { started, updated: new Date().toISOString(), final, sets: sets.length,
    cards: cards.length, completed, ok, failures, requests, cacheHits, elapsedSeconds: Math.round((Date.now() - startedMs) / 1000) };
  await fs.writeFile(path.join(root, 'download-summary.json'), JSON.stringify(summary, null, 2));
  return summary;
}
const progress = setInterval(() => console.log(JSON.stringify({ stage:'cards', completed, total:cards.length,
  ok, failed:failures.length, requests, elapsedSeconds:Math.round((Date.now()-startedMs)/1000) })), 30000);
try {
  await Promise.all(Array.from({length: 8}, async () => {
    while (index < cards.length) {
      const card = cards[index++];
      // The source also publishes Unown ! and the already URL-escaped ? (exu-%3F).
      // Keep those real IDs intact, while still rejecting path separators.
      if (!/^[a-z0-9._!%-]+$/i.test(card.id) || /%(?![a-f0-9]{2})/i.test(card.id)) { failures.push({id:card.id,error:'UNSAFE_ID'}); completed++; continue; }
      const file = path.join(root, 'cards', `${card.id}.json`);
      try {
        try { await fs.access(file); cacheHits++; } catch {}
        const encodedId = encodeURIComponent(card.id);
        // Production uses an identity-equal English detail only when the German
        // detail is unavailable. Store that same safe fallback under the card id.
        let detail;
        try { detail = await request(`${api}/de/cards/${encodedId}`, file); }
        catch { detail = await request(`${api}/en/cards/${encodedId}`, file); }
        if (detail.id !== card.id) throw new Error('SOURCE_ID_MISMATCH');
        ok++;
      } catch (error) { failures.push({ id: card.id, error: error.message }); }
      completed++;
      if (completed % 250 === 0) await saveSummary();
    }
  }));
} finally { clearInterval(progress); }
const summary = await saveSummary(true);
console.log(JSON.stringify({ ...summary, failures: summary.failures.length }));
