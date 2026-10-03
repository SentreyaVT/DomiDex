/** Second, separately preserved JSON-only candidate pass. No HTTP image checks. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { englishNameKey } from './audit-pokemontcg-image-candidates.mjs';

const PRIOR_DIRECTORY = 'candidate-pokemontcg-f9fe218e-edd4-4b93-9a94-95b8b2b65011';
const PRIOR_HASH = '4b1ffe217bc1a643ffa93dd8b166d7b6f674a9ecc0a23b1b11b04f8eb8befc9f';
const COMMIT = '39a26a144c8b6ef6c2fb17b2c29d0bb7121e3a11';
const HASH = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const JSON_BYTES = value => Buffer.from(`${JSON.stringify(value, null, 2)}\n`);
const check = (ok, message) => { if (!ok) throw new Error(message); };
export const EXTRA_SETS = Object.freeze([
  ['bwp', 'bwp'], ['hgssp', 'hsp'], ['xyp', 'xyp'], ['swshp', 'swshp'],
  ['svp', 'svp'], ['pl2', 'pl2'], ['sm7.5', 'sm75'], ['cel25cc', 'cel25c'],
]);

function index(rows, field, label) {
  check(Array.isArray(rows) && rows.length > 0, `Invalid ${label}`);
  const result = new Map();
  for (const row of rows) {
    check(typeof row?.[field] === 'string' && row[field].length > 0, `Invalid ${label}.${field}`);
    check(!result.has(row[field]), `Duplicate ${label}.${field}: ${row[field]}`);
    result.set(row[field], row);
  }
  return result;
}
function imageUrl(value) {
  try {
    const u = new URL(value);
    return typeof value === 'string' && u.href === value && u.protocol === 'https:'
      && u.hostname === 'images.pokemontcg.io' && !u.username && !u.password && !u.port
      && !u.hash && !u.search && /\.(?:png|jpe?g|webp)$/i.test(u.pathname);
  } catch { return false; }
}

export function collectExtraCandidates(inventory, sourceSets, publishedSets, cardSources) {
  const tcgdex = index(sourceSets, 'id', 'tcgdex sets'), published = index(publishedSets, 'id', 'published sets');
  const candidates = [], rejected = [], setEvidence = [];
  for (const [sourceSetId, upstreamSetId] of EXTRA_SETS) {
    const sourceSet = tcgdex.get(sourceSetId), upstreamSet = published.get(upstreamSetId);
    check(sourceSet && upstreamSet, `Missing set ${sourceSetId}/${upstreamSetId}`);
    const setReasons = [];
    // The Classic Collection colon is presentation punctuation only. Its numbers are NOT normalized.
    const nameMatches = sourceSet.name === upstreamSet.name || (sourceSetId === 'cel25cc'
      && sourceSet.name === 'Celebrations Classic Collection'
      && upstreamSet.name === 'Celebrations: Classic Collection');
    if (!nameMatches) setReasons.push('SET_ENGLISH_NAME_NOT_EXACT');
    if (!Number.isInteger(sourceSet.cardCount?.official)
      || sourceSet.cardCount.official !== upstreamSet.printedTotal) setReasons.push('SET_PRINTED_TOTAL_MISMATCH');
    setEvidence.push({ sourceSetId, upstreamSetId, sourceNameEn: sourceSet.name, upstreamNameEn: upstreamSet.name,
      sourcePrintedTotal: sourceSet.cardCount.official, upstreamPrintedTotal: upstreamSet.printedTotal,
      sourceTotal: sourceSet.cardCount.total, upstreamTotal: upstreamSet.total,
      totalCountsEqual: sourceSet.cardCount.total === upstreamSet.total,
      nameComparison: sourceSet.name === upstreamSet.name ? 'EXACT' : nameMatches ? 'EXPLICIT_COLON_ONLY' : 'UNRESOLVED',
      identityStatus: setReasons.length ? 'UNRESOLVED' : 'SET_NAME_AND_PRINTED_TOTAL_CONFIRMED', reasons: setReasons });
    const cards = cardSources[upstreamSetId];
    index(cards, 'id', `${upstreamSetId} cards`);
    const byNumber = new Map();
    for (const card of cards) {
      check(typeof card.number === 'string' && card.number.length > 0, `Invalid ${upstreamSetId} number`);
      byNumber.set(card.number, [...(byNumber.get(card.number) ?? []), card]);
    }
    const idNumberExceptions = cards.filter(card => card.id !== `${upstreamSetId}-${card.number}`);
    if (sourceSetId !== 'cel25cc') check(idNumberExceptions.length === 0,
      `Upstream ID/number conflict ${idNumberExceptions[0]?.id}`);
    setEvidence.at(-1).upstreamIdNumberExceptions = idNumberExceptions.map(card => ({ id: card.id, number: card.number }));
    for (const source of inventory.data.cards.filter(row => row.setId === sourceSetId
      && row.imageState === 'BOTH_LANGUAGES_MISSING_OR_INVALID')) {
      const reasons = [...setReasons];
      if (source.identityStatus !== 'EXACT_SOURCE_ID' || source.identityIssues?.length
        || source.sourceCardId !== `${sourceSetId}-${source.localId}`) reasons.push('SOURCE_IDENTITY_UNRESOLVED');
      // No CC ordinal-to-historical-number or zero-padding guess, and no name-based lookup.
      const matching = byNumber.get(source.localId) ?? [];
      const card = matching.length === 1 ? matching[0] : null;
      if (matching.length > 1) reasons.push('AMBIGUOUS_UPSTREAM_NUMBER');
      else if (!card) reasons.push('NO_EXACT_SET_NUMBER');
      else {
        if (card.id !== `${upstreamSetId}-${card.number}`) reasons.push('UPSTREAM_ID_NUMBER_CONFLICT');
        if (!source.nameEn || englishNameKey(source.nameEn) !== englishNameKey(card.name)) reasons.push('ENGLISH_NAME_MISMATCH');
        if (!imageUrl(card.images?.small) || !imageUrl(card.images?.large)) reasons.push('INVALID_PUBLISHED_IMAGE_URL');
      }
      if (reasons.length) {
        rejected.push({ sourceCardId: source.sourceCardId, setId: sourceSetId, localId: source.localId,
          lookupNumber: source.localId, upstreamSetId, sourceNameEn: source.nameEn,
          upstreamId: card?.id ?? null, upstreamNameEn: card?.name ?? null, reasons });
        continue;
      }
      candidates.push({ sourceCardId: source.sourceCardId, sourceSetId, sourceLocalId: source.localId,
        sourceNameDe: source.nameDe, sourceNameEn: source.nameEn, upstreamId: card.id, upstreamSetId,
        upstreamNumber: card.number, upstreamNameEn: card.name, numberComparison: 'EXACT',
        nameComparison: source.nameEn === card.name ? 'EXACT' : 'TYPOGRAPHIC_SUFFIX_OR_CASE_ONLY',
        language: 'en', small: card.images.small, large: card.images.large, availability: 'NOT_HTTP_CHECKED',
        evidence: 'PUBLISHED_JSON_EXACT_SET_NUMBER_AND_ENGLISH_NAME',
        upstreamSourceFile: `cards-en-${upstreamSetId}.json` });
    }
  }
  return { candidates, rejected, setEvidence,
    counts: { candidates: candidates.length, rejected: rejected.length,
      bySet: Object.fromEntries(EXTRA_SETS.map(([set]) => [set, {
        candidates: candidates.filter(x => x.sourceSetId === set).length,
        rejected: rejected.filter(x => x.setId === set).length }])) } };
}

async function boundedGet(url, file, out) {
  const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(20_000),
    headers: { 'User-Agent': 'DomiDex-public-image-candidate-audit/1.0', Accept: 'application/json' } });
  check(response.ok, `HTTP ${response.status}: ${url}`);
  const chunks = [], reader = response.body.getReader();
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.length;
    if (total > 4 * 1024 * 1024) { await reader.cancel(); throw new Error(`Source over 4MiB: ${file}`); }
    chunks.push(Buffer.from(value));
  }
  const bytes = Buffer.concat(chunks);
  await fs.writeFile(path.join(out, file), bytes, { flag: 'wx' });
  const receipt = { file, url, status: response.status, checkedAt: new Date().toISOString(),
    bytes: bytes.length, sha256: HASH(bytes), etag: response.headers.get('etag') };
  await fs.writeFile(path.join(out, `${file}.receipt.json`), JSON_BYTES(receipt), { flag: 'wx' });
  return { receipt, data: JSON.parse(bytes.toString('utf8')) };
}

export async function main(argv) {
  check((argv.length === 2 || (argv.length === 4 && argv[2] === '--replay-snapshot'))
    && argv[0] === '--inventory-root', 'Usage: node audit-pokemontcg-extra-image-candidates.mjs --inventory-root <existing-root> [--replay-snapshot <existing-subdirectory>]');
  const root = path.resolve(argv[1]);
  check(path.dirname(root).toLowerCase() === path.resolve('C:/Users/Dominik/DomiDex-audits').toLowerCase()
    && /^card-images-20261003-[0-9a-f-]{36}$/.test(path.basename(root)), 'Unexpected inventory root');
  const priorRoot = path.join(root, PRIOR_DIRECTORY), priorBytes = await fs.readFile(path.join(priorRoot, 'candidates.json'));
  check(HASH(priorBytes) === PRIOR_HASH, 'Prior candidate report changed');
  const prior = JSON.parse(priorBytes);
  check(prior.upstreamCommit === COMMIT && prior.candidates.length === 131, 'Prior scope/commit conflict');
  const manifestBytes = await fs.readFile(path.join(root, 'source-manifest.json'));
  check(HASH(manifestBytes) === prior.inventoryManifestSha256, 'Original inventory manifest changed');
  const manifest = JSON.parse(manifestBytes), inventoryBytes = await fs.readFile(path.join(root, 'inventory.json'));
  check(HASH(inventoryBytes) === prior.inventorySha256, 'Original inventory changed');
  const inventory = JSON.parse(inventoryBytes), tcgdexSources = {};
  check(inventory.sourceFingerprint === prior.sourceFingerprint, 'Inventory source fingerprint changed');
  for (const source of manifest.sources) {
    check(['sets-de.json', 'sets-en.json', 'cards-de.json', 'cards-en.json', 'series-tcgp.json'].includes(source.file), 'Unexpected original source');
    const bytes = await fs.readFile(path.join(root, source.file));
    check(HASH(bytes) === source.sha256 && bytes.length === source.bytes, `Original source changed: ${source.file}`);
    tcgdexSources[source.file] = JSON.parse(bytes);
  }
  check(Object.keys(tcgdexSources).length === 5, 'Missing original source');
  const replay = argv.length === 4;
  const directory = replay ? argv[3] : `candidate-pokemontcg-extra-${crypto.randomUUID()}`;
  check(/^candidate-pokemontcg-extra-[0-9a-f-]{36}$/.test(directory), 'Unexpected replay directory');
  const out = path.join(root, directory);
  if (!replay) {
    await fs.mkdir(out, { recursive: false });
    await fs.writeFile(path.join(out, 'prior-candidates.json'), priorBytes, { flag: 'wx' });
  } else check(HASH(await fs.readFile(path.join(out, 'prior-candidates.json'))) === PRIOR_HASH, 'Replay prior changed');
  const copied = [];
  for (const source of prior.sources) {
    check(/^(?:commit|sets-en|cards-en-(?:ecard2|ecard3|smp))\.json$/.test(source.file), 'Unexpected prior source');
    const bytes = await fs.readFile(path.join(priorRoot, source.file));
    check(HASH(bytes) === source.sha256 && bytes.length === source.bytes, `Prior source changed: ${source.file}`);
    if (!replay) await fs.writeFile(path.join(out, source.file), bytes, { flag: 'wx' });
    else check(HASH(await fs.readFile(path.join(out, source.file))) === source.sha256, `Replay source changed: ${source.file}`);
    copied.push(source);
  }
  check(copied.length === 5, 'Missing prior source receipt');
  const publishedSets = JSON.parse(await fs.readFile(path.join(out, 'sets-en.json'), 'utf8'));
  const cards = {}, fetched = [];
  for (const [, upstreamSetId] of EXTRA_SETS) {
    const url = `https://raw.githubusercontent.com/PokemonTCG/pokemon-tcg-data/${COMMIT}/cards/en/${upstreamSetId}.json`;
    const file = `cards-en-${upstreamSetId}.json`;
    let result;
    if (replay) {
      const receipt = JSON.parse(await fs.readFile(path.join(out, `${file}.receipt.json`), 'utf8'));
      const bytes = await fs.readFile(path.join(out, file));
      check(receipt.url === url && receipt.file === file && receipt.status === 200
        && bytes.length === receipt.bytes && HASH(bytes) === receipt.sha256, `Replay receipt conflict: ${file}`);
      result = { receipt, data: JSON.parse(bytes.toString('utf8')) };
    } else result = await boundedGet(url, file, out);
    cards[upstreamSetId] = result.data; fetched.push(result.receipt);
    if (!replay) await new Promise(resolve => setTimeout(resolve, 150));
  }
  const extra = collectExtraCandidates(inventory, tcgdexSources['sets-en.json'], publishedSets, cards);
  const combined = [...prior.candidates, ...extra.candidates];
  check(new Set(combined.map(x => x.sourceCardId)).size === combined.length, 'Combined candidate duplicate');
  const report = { schemaVersion: 1, createdAt: new Date().toISOString(), upstreamCommit: COMMIT,
    sourceFingerprint: prior.sourceFingerprint, inventorySha256: prior.inventorySha256,
    inventoryManifestSha256: prior.inventoryManifestSha256, priorCandidateReportSha256: PRIOR_HASH,
    sources: [...copied, ...fetched], scope: 'Prior131 plus eight explicitly reviewed sets; only missing-both-language records.',
    limitations: [...prior.limitations, 'SVP set mismatch remains unresolved; no CC ordinal-to-historical-number inference.',
      'Different total variant counts are recorded; printed-total equality is only one identity corroborator.'],
    setEvidence: [...prior.setEvidence, ...extra.setEvidence], candidates: combined,
    rejected: [...prior.rejected, ...extra.rejected],
    counts: { candidates: combined.length, priorCandidates: prior.candidates.length,
      newCandidates: extra.candidates.length, rejected: prior.rejected.length + extra.rejected.length,
      bySet: { ...prior.counts.bySet, ...extra.counts.bySet } } };
  await fs.writeFile(path.join(out, 'candidates.json'), JSON_BYTES(report), { flag: 'wx' });
  console.log(JSON.stringify({ output: out, ...report.counts }, null, 2));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
