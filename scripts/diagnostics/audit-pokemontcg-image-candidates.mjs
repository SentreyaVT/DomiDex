/** Narrow public-source candidate evidence. No image HTTP checks, installs, or app writes. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const TARGETS = ['ecard2', 'ecard3', 'smp'];
const ROOT_PARENT = 'C:/Users/Dominik/DomiDex-audits';
const SOURCE_LIMIT = 4 * 1024 * 1024;
const sha256 = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const assert = (value, message) => { if (!value) throw new Error(message); };
const json = value => `${JSON.stringify(value, null, 2)}\n`;

// Only the documented, observed E-Card Holo zero-padding discrepancy is normalized.
export function sourceNumber(setId, localId) {
  return /^ecard[23]$/.test(setId) && /^H\d{2}$/.test(localId)
    ? `H${Number(localId.slice(1))}` : localId;
}

export function englishNameKey(value) {
  return typeof value === 'string' ? value.normalize('NFKC').trim()
    .replace(/[’‘]/g, "'").replace(/\s*-\s*(GX|EX)\b/g, ' $1')
    .replace(/\s+/g, ' ').toLowerCase() : null;
}

function publishedImage(value) {
  if (typeof value !== 'string') return false;
  try {
    const u = new URL(value);
    return u.href === value && u.protocol === 'https:' && u.hostname === 'images.pokemontcg.io'
      && !u.username && !u.password && !u.port && !u.search && !u.hash
      && /\.(?:png|jpe?g|webp)$/i.test(u.pathname);
  } catch { return false; }
}

function unique(rows, field, label) {
  assert(Array.isArray(rows) && rows.length > 0, `Empty/invalid ${label}`);
  const map = new Map();
  for (const row of rows) {
    assert(typeof row?.[field] === 'string' && row[field].length > 0, `Invalid ${label}.${field}`);
    assert(!map.has(row[field]), `Duplicate ${label}.${field}: ${row[field]}`);
    map.set(row[field], row);
  }
  return map;
}

/** Matching is set+collector-number FIRST; English names only confirm that identity. */
export function collectCandidates(inventory, tcgdexSetsEn, upstreamSets, upstreamCards) {
  const sourceSets = unique(tcgdexSetsEn, 'id', 'tcgdex sets');
  const publishedSets = unique(upstreamSets, 'id', 'published sets');
  const candidates = [], rejected = [], setEvidence = [];
  for (const setId of TARGETS) {
    const sourceSet = sourceSets.get(setId), publishedSet = publishedSets.get(setId);
    assert(sourceSet && publishedSet, `Missing exact set ID ${setId}`);
    assert(sourceSet.name === publishedSet.name, `Set English name mismatch: ${setId}`);
    assert(Number.isInteger(sourceSet.cardCount?.official)
      && sourceSet.cardCount.official === publishedSet.printedTotal, `Printed set total mismatch: ${setId}`);
    setEvidence.push({ setId, tcgdexNameEn: sourceSet.name, upstreamNameEn: publishedSet.name,
      tcgdexPrintedTotal: sourceSet.cardCount.official, upstreamPrintedTotal: publishedSet.printedTotal,
      tcgdexTotal: sourceSet.cardCount.total, upstreamTotal: publishedSet.total,
      totalCountsEqual: sourceSet.cardCount.total === publishedSet.total,
      limitation: 'Matching printed total is corroboration, not proof of identical variant enumeration.' });
    const rows = upstreamCards[setId];
    unique(rows, 'id', `${setId} cards`);
    const byNumber = unique(rows, 'number', `${setId} numbers`);
    for (const row of rows) assert(row.id === `${setId}-${row.number}`, `Upstream ID/number conflict ${row.id}`);
    for (const source of inventory.data.cards.filter(row => row.setId === setId
      && row.imageState === 'BOTH_LANGUAGES_MISSING_OR_INVALID')) {
      const reason = [];
      if (source.identityStatus !== 'EXACT_SOURCE_ID' || source.identityIssues?.length
        || source.sourceCardId !== `${setId}-${source.localId}`) reason.push('SOURCE_IDENTITY_UNRESOLVED');
      const number = sourceNumber(setId, source.localId);
      const card = byNumber.get(number);
      if (!card) reason.push('NO_EXACT_SET_NUMBER');
      else {
        if (!source.nameEn || englishNameKey(source.nameEn) !== englishNameKey(card.name)) reason.push('ENGLISH_NAME_MISMATCH');
        if (!publishedImage(card.images?.small) || !publishedImage(card.images?.large)) reason.push('INVALID_PUBLISHED_IMAGE_URL');
      }
      if (reason.length) {
        rejected.push({ sourceCardId: source.sourceCardId, setId, localId: source.localId,
          lookupNumber: number, sourceNameEn: source.nameEn, upstreamId: card?.id ?? null,
          upstreamNameEn: card?.name ?? null, reasons: reason });
        continue;
      }
      candidates.push({ sourceCardId: source.sourceCardId, sourceSetId: setId, sourceLocalId: source.localId,
        sourceNameDe: source.nameDe, sourceNameEn: source.nameEn, upstreamId: card.id,
        upstreamSetId: setId, upstreamNumber: card.number, upstreamNameEn: card.name,
        numberComparison: source.localId === card.number ? 'EXACT' : 'ECARD_H_ZERO_PADDING_ONLY',
        nameComparison: source.nameEn === card.name ? 'EXACT' : 'TYPOGRAPHIC_SUFFIX_OR_CASE_ONLY',
        language: 'en', small: card.images.small, large: card.images.large,
        availability: 'NOT_HTTP_CHECKED', evidence: 'PUBLISHED_JSON_EXACT_SET_NUMBER_AND_ENGLISH_NAME',
        upstreamSourceFile: `cards-en-${setId}.json` });
    }
  }
  return { setEvidence, candidates, rejected, counts: { candidates: candidates.length, rejected: rejected.length,
    bySet: Object.fromEntries(TARGETS.map(set => [set, { candidates: candidates.filter(x => x.sourceSetId === set).length,
      rejected: rejected.filter(x => x.setId === set).length }])) } };
}

async function fetchSource(url, file, out, receipts) {
  const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(20_000),
    headers: { 'User-Agent': 'DomiDex-public-image-candidate-audit/1.0', Accept: 'application/json' } });
  assert(response.ok, `HTTP ${response.status} for ${url}`);
  const reader = response.body.getReader(), chunks = [];
  let length = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    length += value.length;
    if (length > SOURCE_LIMIT) { await reader.cancel(); throw new Error(`Source over budget: ${file}`); }
    chunks.push(Buffer.from(value));
  }
  const bytes = Buffer.concat(chunks);
  await fs.writeFile(path.join(out, file), bytes, { flag: 'wx' });
  receipts.push({ file, url, checkedAt: new Date().toISOString(), status: response.status,
    bytes: bytes.length, sha256: sha256(bytes), etag: response.headers.get('etag') });
  await fs.writeFile(path.join(out, `source-receipt-${receipts.length}.json`), json(receipts.at(-1)), { flag: 'wx' });
  return JSON.parse(bytes.toString('utf8'));
}

export async function main(argv) {
  assert(argv.length === 2 && argv[0] === '--inventory-root', 'Usage: node audit-pokemontcg-image-candidates.mjs --inventory-root <existing-root>');
  const root = path.resolve(argv[1]);
  assert(path.dirname(root).toLowerCase() === path.resolve(ROOT_PARENT).toLowerCase()
    && /^card-images-20261003-[0-9a-f-]{36}$/.test(path.basename(root)), 'Unexpected inventory root');
  const manifestBytes = await fs.readFile(path.join(root, 'source-manifest.json'));
  const manifest = JSON.parse(manifestBytes);
  const inventoryBytes = await fs.readFile(path.join(root, 'inventory.json'));
  assert(sha256(inventoryBytes) === manifest.inventorySha256, 'Inventory bytes changed');
  const inventory = JSON.parse(inventoryBytes), source = {};
  assert(inventory.sourceFingerprint === manifest.sourceFingerprint, 'Inventory fingerprint conflict');
  for (const receipt of manifest.sources) {
    assert(['sets-de.json', 'sets-en.json', 'cards-de.json', 'cards-en.json', 'series-tcgp.json'].includes(receipt.file), 'Unexpected source file');
    const bytes = await fs.readFile(path.join(root, receipt.file));
    assert(bytes.length === receipt.bytes && sha256(bytes) === receipt.sha256, `Source changed: ${receipt.file}`);
    source[receipt.file] = JSON.parse(bytes);
  }
  assert(Object.keys(source).length === 5, 'Missing frozen source');
  const out = path.join(root, `candidate-pokemontcg-${crypto.randomUUID()}`);
  await fs.mkdir(out, { recursive: false });
  const receipts = [];
  const commit = await fetchSource('https://api.github.com/repos/PokemonTCG/pokemon-tcg-data/commits/master', 'commit.json', out, receipts);
  assert(/^[0-9a-f]{40}$/.test(commit.sha), 'Invalid upstream commit SHA');
  const base = `https://raw.githubusercontent.com/PokemonTCG/pokemon-tcg-data/${commit.sha}`;
  const upstreamSets = await fetchSource(`${base}/sets/en.json`, 'sets-en.json', out, receipts);
  const cards = {};
  for (const setId of TARGETS) {
    await new Promise(resolve => setTimeout(resolve, 150));
    cards[setId] = await fetchSource(`${base}/cards/en/${setId}.json`, `cards-en-${setId}.json`, out, receipts);
  }
  const result = collectCandidates(inventory, source['sets-en.json'], upstreamSets, cards);
  const report = { schemaVersion: 1, createdAt: new Date().toISOString(), upstreamCommit: commit.sha,
    sourceFingerprint: manifest.sourceFingerprint, inventorySha256: manifest.inventorySha256,
    inventoryManifestSha256: sha256(manifestBytes), sources: receipts,
    scope: 'Only missing-both-language physical German-index ecard2, ecard3, smp records; no image fetches.',
    limitations: ['PokemonTCG is the publishing community project, not a Pokemon-company source.',
      'English image candidates only; no German artwork claim.', 'No image HTTP availability or image-content verification.',
      'No new app/runtime image dependency or card/price identity mapping is installed.'], ...result };
  await fs.writeFile(path.join(out, 'candidates.json'), json(report), { flag: 'wx' });
  console.log(json({ output: out, commit: commit.sha, ...result.counts }));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
