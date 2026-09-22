/** Public, read-only inputs for the catalog integrity audit. No credentials or account data. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

export function safeSourceId(id) {
  return typeof id === 'string' && /^[a-z0-9._!%-]+$/i.test(id)
    && !id.includes('..') && !/%(?![a-f0-9]{2})/i.test(id);
}

export async function downloadCatalogSnapshot(root) {
  if (!root) throw new Error('Provide an external snapshot directory, never a product asset directory.');
  root = path.resolve(root);
  const api = 'https://api.tcgdex.net/v2';
  const started = new Date().toISOString();
  const manifest = new Map();
  const pending = new Map();
  let nextStart = 0, pauseUntil = 0, requests = 0, completed = 0;
  const failures = [];
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
  await fs.mkdir(root, { recursive: true });
  const manifestFile = path.join(root, 'source-manifest.json');
  try { for (const row of JSON.parse(await fs.readFile(manifestFile, 'utf8'))) manifest.set(row.file, row); } catch {}
  async function request(url, relative, attempts = 2) {
    if (pending.has(url)) return pending.get(url);
    const work = (async () => {
      const file = path.join(root, relative);
      const cached = manifest.get(relative);
      if (cached?.url === url && cached.status === 200) {
        try {
          const bytes = await fs.readFile(file);
          if (crypto.createHash('sha256').update(bytes).digest('hex') === cached.sha256) return JSON.parse(bytes);
        } catch {}
      }
      await fs.mkdir(path.dirname(file), { recursive: true });
      for (let attempt = 0; attempt < attempts; attempt++) {
        const start = Math.max(Date.now(), nextStart, pauseUntil);
        nextStart = start + 70;
        await sleep(Math.max(0, start - Date.now()));
        requests++;
        const readAt = new Date().toISOString();
        try {
          const response = await fetch(url, { signal: AbortSignal.timeout(15000),
            headers: { 'User-Agent': 'DomiDex-public-catalog-integrity-audit/2.0' } });
          if (!response.ok) {
            manifest.set(relative, { file: relative, url, readAt, status: response.status });
            if (response.status === 429) {
              const retry = Number(response.headers.get('retry-after'));
              pauseUntil = Date.now() + (retry > 0 ? Math.min(retry * 1000, 60000) : 30000);
            }
            if ((response.status === 429 || response.status >= 500) && attempt + 1 < attempts) continue;
            throw new Error(`HTTP_${response.status}`);
          }
          const bytes = Buffer.from(await response.arrayBuffer());
          const data = JSON.parse(bytes);
          await fs.writeFile(file, bytes);
          manifest.set(relative, { file: relative, url, readAt, status: response.status,
            bytes: bytes.length, sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
            etag: response.headers.get('etag'), lastModified: response.headers.get('last-modified') });
          return data;
        } catch (error) {
          if (attempt + 1 < attempts && !/^HTTP_4/.test(error.message)) continue;
          if (!manifest.get(relative) || manifest.get(relative).status === 200) {
            manifest.set(relative, { file: relative, url, readAt, status: 'ERROR', error: error.message });
          }
          throw error;
        }
      }
    })();
    pending.set(url, work);
    try { return await work; } finally { pending.delete(url); }
  }
  const save = async (stage, extra = {}) => {
    await fs.writeFile(manifestFile, JSON.stringify([...manifest.values()], null, 2));
    const summary = { started, updated: new Date().toISOString(), stage, completed, requests, failures, ...extra };
    await fs.writeFile(path.join(root, 'download-summary.json'), JSON.stringify(summary, null, 2));
    console.log(JSON.stringify({ ...summary, failures: failures.length }));
  };
  const [sets, german, english, pocket] = await Promise.all([
    request(`${api}/de/sets`, 'sets-de.json'),
    request(`${api}/de/cards`, 'cards-de.json'),
    request(`${api}/en/cards`, 'cards-en.json'),
    request(`${api}/de/series/tcgp`, 'series-tcgp.json'),
    request(`${api}/en/sets`, 'sets-en.json'),
    request('https://downloads.s3.cardmarket.com/productCatalog/productList/products_singles_6.json', 'products.json'),
    request('https://downloads.s3.cardmarket.com/productCatalog/priceGuide/price_guide_6.json', 'prices.json'),
  ]);
  if (![sets, german, english].every(Array.isArray) || !Array.isArray(pocket.sets)) throw new Error('Invalid catalog index');
  const pocketIds = new Set(pocket.sets.map(set => set.id));
  const physical = sets.filter(set => !pocketIds.has(set.id));
  const allSetIds = sets.map(set => set.id).sort((a, b) => b.length - a.length);
  const physicalIds = new Set(physical.map(set => set.id));
  const germanIds = new Set(german.map(card => card.id));
  const cards = [...new Map([...english, ...german].map(card => [card.id, card])).values()]
    .filter(card => physicalIds.has(allSetIds.find(id => card.id.startsWith(`${id}-`))));
  const queue = physical.flatMap(set => ['de', 'en'].map(language => ({ kind: 'set', id: set.id, language })))
    .concat(cards.map(card => ({ kind: 'card', id: card.id, language: germanIds.has(card.id) ? 'de' : 'en' })));
  let cursor = 0;
  const timer = setInterval(() => console.log(JSON.stringify({ stage: 'download', completed,
    total: queue.length, requests, failures: failures.length })), 30000);
  try {
    await save('indexes', { listedSets: sets.length, physicalSets: physical.length, cards: cards.length });
    await Promise.all(Array.from({ length: 8 }, async () => {
      while (cursor < queue.length) {
        const row = queue[cursor++];
        try {
          if (!safeSourceId(row.id)) throw new Error('UNSAFE_ID');
          const plural = row.kind === 'set' ? 'sets' : 'cards';
          const relative = row.kind === 'set' ? `sets/${row.language}/${row.id}.json` : `cards/${row.id}.json`;
          let detail;
          try { detail = await request(`${api}/${row.language}/${plural}/${encodeURIComponent(row.id)}`, relative); }
          catch (error) {
            if (row.kind !== 'card' || row.language !== 'de') throw error;
            // Store the actual language in provenance; never label English fallback as German.
            detail = await request(`${api}/en/cards/${encodeURIComponent(row.id)}`, relative);
          }
          if (detail.id !== row.id) throw new Error('SOURCE_ID_MISMATCH');
        } catch (error) { failures.push({ ...row, error: error.message }); }
        completed++;
        if (completed % 500 === 0) await save('download', { total: queue.length });
      }
    }));
  } finally { clearInterval(timer); }
  await save('complete', { listedSets: sets.length, physicalSets: physical.length, excludedPocketSets: pocketIds.size, cards: cards.length });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await downloadCatalogSnapshot(process.argv[2]);
}
