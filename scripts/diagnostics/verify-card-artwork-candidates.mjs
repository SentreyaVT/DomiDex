/** Fetch only previously identity-reviewed candidates, bounded and without credentials. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const sha = value => crypto.createHash('sha256').update(value).digest('hex');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const allowed = new Set(['assets.tcgdex.net', 'assets.pokemon.com', 'www.serebii.net', 'images.pokemontcg.io']);
export function selectCandidates(cards) {
  const selected = [], seen = new Set();
  for (const row of cards) {
    const id = row.sourceCardId, set = row.setId, local = row.sourceLocalId;
    if (!set || !local || id !== `${set}-${local}` || seen.has(id)) throw Error('Invalid/duplicate candidate identity');
    seen.add(id);
    const choices = row.candidates.filter(c => ['de', 'en'].includes(c.language));
    const candidate = choices.find(c => c.language === 'de') ?? choices.find(c => c.language === 'en');
    if (!candidate) continue;
    for (const url of [candidate.lowUrl, candidate.highUrl]) {
      const parsed = new URL(url);
      if (parsed.protocol !== 'https:' || !allowed.has(parsed.hostname) || parsed.username || parsed.password ||
          parsed.port || parsed.search || parsed.hash || !/\.(webp|png|jpe?g)$/i.test(parsed.pathname)) throw Error('Invalid candidate URL');
    }
    selected.push({ sourceCardId: id, setId: set, sourceLocalId: local, candidate });
  }
  return selected;
}

export async function verify(inputFile, expectedHash) {
  inputFile = path.resolve(inputFile);
  const parent = 'C:\\Users\\Dominik\\DomiDex-audits';
  if (!inputFile.startsWith(parent + path.sep) || !/^[a-f0-9]{64}$/.test(expectedHash)) throw Error('Retained audit source required');
  const raw = await fs.readFile(inputFile);
  if (sha(raw) !== expectedHash) throw Error('Candidate source hash mismatch');
  const cards = selectCandidates(JSON.parse(raw).cards);
  const urls = new Map();
  for (const card of cards) for (const quality of ['low', 'high']) {
    const url = card.candidate[`${quality}Url`];
    const entry = urls.get(url) ?? { url, references: [] };
    entry.references.push({ sourceCardId: card.sourceCardId, language: card.candidate.language, quality });
    urls.set(url, entry);
  }
  const output = path.join(parent, `card-artwork-verified-20261003-${crypto.randomUUID()}`);
  await fs.mkdir(output);
  await fs.mkdir(path.join(output, 'images'));
  const plan = { inputFile, sourceSha256: expectedHash, cards, urls: [...urls.values()] };
  await fs.writeFile(path.join(output, 'plan.json'), JSON.stringify(plan, null, 2), { flag: 'wx' });
  const results = [], startedAt = new Date().toISOString();
  console.log(JSON.stringify({ output, candidateCards: cards.length, urls: urls.size }));
  let previousStart = 0;
  for (const [index, item] of [...urls.values()].entries()) {
    await pause(Math.max(0, 1000 - (Date.now() - previousStart)));
    previousStart = Date.now();
    const row = { url: item.url, checkedAt: new Date().toISOString(), status: 'UNKNOWN' };
    try {
      const response = await fetch(item.url, { redirect: 'error', signal: AbortSignal.timeout(15000),
        headers: { 'User-Agent': 'DomiDex-public-card-image-audit/1.0' } });
      row.httpStatus = response.status;
      row.contentType = response.headers.get('content-type');
      if ([403, 429].includes(response.status)) {
        row.status = 'ACCESS_BOUNDARY_STOP'; row.retryAfter = response.headers.get('retry-after');
        await response.body?.cancel();
      } else if (response.status === 404 || response.status === 410) {
        row.status = 'NOT_AVAILABLE'; await response.body?.cancel();
      } else if (response.status === 200 && /^image\//i.test(row.contentType ?? '')) {
        const parts = []; let bytes = 0;
        for await (const part of response.body) {
          bytes += part.length;
          if (bytes > 4 * 1024 * 1024) throw Error('IMAGE_BYTE_LIMIT');
          parts.push(part);
        }
        const image = Buffer.concat(parts);
        row.sha256 = sha(image); row.bytes = bytes;
        row.file = `images/${String(index).padStart(4, '0')}${path.extname(new URL(item.url).pathname)}`;
        await fs.writeFile(path.join(output, row.file), image, { flag: 'wx' });
        row.status = 'FETCHED_IMAGE_NOT_DECODED';
      } else { await response.body?.cancel(); }
    } catch (error) { row.error = error.message; }
    results.push(row);
    await fs.appendFile(path.join(output, 'results.jsonl'), JSON.stringify(row) + '\n');
    if (results.length % 30 === 0) console.log(JSON.stringify({ checked: results.length, total: urls.size }));
    if (row.status === 'ACCESS_BOUNDARY_STOP') break;
  }
  if (sha(await fs.readFile(inputFile)) !== expectedHash) throw Error('Candidate source changed');
  const summary = { startedAt, finishedAt: new Date().toISOString(), sourceSha256: expectedHash,
    plannedCards: cards.length, plannedUrls: urls.size, checkedUrls: results.length,
    statuses: Object.fromEntries([...new Set(results.map(r => r.status))].map(s => [s, results.filter(r => r.status === s).length])),
    limitations: ['Image bytes and MIME fetched; decoding and visual/card identity checks are separate.', 'No fabricated German artwork or cross-set/name-only fallback.'] };
  await fs.writeFile(path.join(output, 'summary.json'), JSON.stringify(summary, null, 2), { flag: 'wx' });
  console.log(JSON.stringify({ output, ...summary }));
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  verify(process.argv[2], process.argv[3]).catch(error => { console.error(error.message); process.exitCode = 1; });
}
