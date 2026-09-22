// Read-only HEAD checks; stores metadata only, never card images.
import fs from 'node:fs/promises';
import path from 'node:path';

const root = path.resolve(process.argv[2]);
const read = async file => JSON.parse(await fs.readFile(path.join(root, file), 'utf8'));
const [sets, englishSets, de, en, pocket] = await Promise.all(['sets-de.json', 'sets-en.json', 'cards-de.json', 'cards-en.json', 'series-tcgp.json'].map(read));
const excluded = new Set(pocket.sets.map(set => set.id));
const prefixes = sets.map(set => set.id).sort((a, b) => b.length - a.length);
const cards = new Map([...en, ...de].map(card => [card.id, card]));
const fallback = new Map(en.map(card => [card.id, card]));
const imageUrl = base => !base ? null : /\.[a-z0-9]+(?:\?|$)/i.test(base.substring(base.lastIndexOf('/') + 1)) ? base : `${base.replace(/\/$/, '')}/high.webp`;
const images = new Map();
for (const card of cards.values()) {
  const setId = prefixes.find(id => card.id.startsWith(`${id}-`));
  if (!setId || excluded.has(setId)) continue;
  const url = imageUrl(card.image || fallback.get(card.id)?.image);
  if (url) images.set(url, { url, sourceCardIds: [...(images.get(url)?.sourceCardIds ?? []), card.id] });
}
for (const set of sets.filter(set => !excluded.has(set.id))) {
  const englishSet = englishSets.find(row => row.id === set.id);
  const base = set.logo || englishSet?.logo || set.symbol || englishSet?.symbol
    || (['30th', '30th-c'].includes(set.id)
      ? 'https://www.pokemon.com/static-assets/content-assets/cms2-de-de/img/trading-card-game/series/me_series/30th/30th_logo_169_de.png' : null);
  if (base) {
    const url = /\.[a-z]+$/i.test(base.substring(base.lastIndexOf('/') + 1)) ? base : `${base}.png`;
    images.set(url, { url, sourceSetIds: [set.id] });
  }
}
// Optional production output adds actual official/detail-image fallbacks to the same cache.
if (process.argv[3]) {
  for (const line of (await fs.readFile(path.resolve(process.argv[3]), 'utf8')).trim().split('\n')) {
    const row = JSON.parse(line);
    if (row.image) images.set(row.image, { url: row.image,
      sourceCardIds: [...new Set([...(images.get(row.image)?.sourceCardIds ?? []), row.sourceCardId])] });
  }
}
const output = path.join(root, 'image-checks.jsonl');
const existing = new Map();
try { for (const line of (await fs.readFile(output, 'utf8')).trim().split('\n')) { const row = JSON.parse(line); existing.set(row.url, row); } } catch {}
const file = await fs.open(output, 'a');
for (const url of existing.keys()) if (!images.has(url)) existing.delete(url);
// Retry technical failures on a subsequent run, without redownloading successful checks.
for (const [url, row] of existing) if (row.status === 'ERROR') existing.delete(url);
const queue = [...images.values()].filter(row => !existing.has(row.url));
let next = 0, cursor = 0, completed = existing.size;
let available = [...existing.values()].filter(row => row.status === 'AVAILABLE').length;
let errors = [...existing.values()].filter(row => row.status === 'ERROR').length;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const progress = setInterval(() => console.log(JSON.stringify({ stage: 'images', completed, total: images.size, available, errors })), 30000);
try {
  await Promise.all(Array.from({ length: 8 }, async () => {
    while (cursor < queue.length) {
      const row = queue[cursor++];
      const begin = Math.max(Date.now(), next); next = begin + 70;
      await sleep(Math.max(0, begin - Date.now()));
      const result = { ...row, checkedAt: new Date().toISOString() };
      try {
        const parsed = new URL(row.url);
        if (parsed.protocol !== 'https:' || !['assets.tcgdex.net', 'assets.pokemon.com', 'www.pokemon.com'].includes(parsed.hostname)) {
          throw new Error('UNEXPECTED_IMAGE_ORIGIN');
        }
        const response = await fetch(row.url, { method: 'HEAD', signal: AbortSignal.timeout(10000),
          headers: { 'User-Agent': 'DomiDex-public-catalog-image-audit/1.0' } });
        result.httpStatus = response.status;
        result.contentType = response.headers.get('content-type');
        result.contentLength = response.headers.get('content-length');
        result.status = response.ok && result.contentType?.startsWith('image/') ? 'AVAILABLE'
          : response.status === 404 || response.status === 410 ? 'NOT_AVAILABLE' : 'ERROR';
      } catch (error) { result.status = 'ERROR'; result.error = error.message; }
      if (result.status === 'AVAILABLE') available++;
      if (result.status === 'ERROR') errors++;
      await file.appendFile(`${JSON.stringify(result)}\n`);
      existing.set(result.url, result);
      completed++;
    }
  }));
} finally { clearInterval(progress); await file.close(); }
await fs.writeFile(path.join(root, 'image-summary.json'), JSON.stringify({ finishedAt: new Date().toISOString(),
  inspectedUrls: images.size, completed, available, errors, notAvailable: completed - available - errors,
  validation: 'HTTP HEAD and image MIME only; artwork correctness/pixel identity not independently verified' }, null, 2));
console.log(JSON.stringify({ completed, available, errors }));
