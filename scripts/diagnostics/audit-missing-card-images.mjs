/** Public image evidence only. No credentials, user data, card details, or image bodies. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

export const SOURCES = Object.freeze([
  ['sets-de.json', 'https://api.tcgdex.net/v2/de/sets'],
  ['sets-en.json', 'https://api.tcgdex.net/v2/en/sets'],
  ['cards-de.json', 'https://api.tcgdex.net/v2/de/cards'],
  ['cards-en.json', 'https://api.tcgdex.net/v2/en/cards'],
  ['series-tcgp.json', 'https://api.tcgdex.net/v2/de/series/tcgp'],
]);
const ALLOWED_PARENT = 'C:/Users/Dominik/DomiDex-audits';
const INDEX_LIMIT = 12 * 1024 * 1024;
const IMAGE_HOSTS = new Set(['assets.tcgdex.net', 'assets.pokemon.com', 'www.pokemon.com']);
const HASH = /^[a-f0-9]{64}$/;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const jsonBytes = value => Buffer.from(`${JSON.stringify(value, null, 2)}\n`);
const requireThat = (ok, message) => { if (!ok) throw new Error(message); };
const validId = value => typeof value === 'string' && /^[a-z0-9._!%-]+$/i.test(value) &&
  !value.includes('..') && !/%(?![a-f0-9]{2})/i.test(value);

/** Qualify only a provider-declared base. Never derive artwork from a card ID/name. */
export function imageCandidate(value) {
  if (value == null || (typeof value === 'string' && !value.trim())) return { status: 'MISSING_URL' };
  if (typeof value !== 'string') return { status: 'INVALID_URL', reason: 'NON_STRING' };
  try {
    const url = new URL(value.trim());
    if (url.protocol !== 'https:' || !IMAGE_HOSTS.has(url.hostname) || url.username || url.password || url.port || url.hash) {
      return { status: 'INVALID_URL', reason: 'UNAPPROVED_ORIGIN_OR_CREDENTIALS' };
    }
    url.pathname = url.pathname.replace(/\/+$/, '');
    const tail = url.pathname.slice(url.pathname.lastIndexOf('/') + 1);
    if (!tail) return { status: 'INVALID_URL', reason: 'EMPTY_PATH' };
    if (/\.(?:webp|png|jpe?g|gif)$/i.test(tail)) {
      return { status: 'DECLARED_URL', declared: value, low: url.href, high: url.href };
    }
    if (tail.includes('.') || url.search) return { status: 'INVALID_URL', reason: 'UNRECOGNIZED_FILE_OR_BASE_QUERY' };
    const base = url.href;
    return { status: 'DECLARED_BASE', declared: value, low: `${base}/low.webp`, high: `${base}/high.webp` };
  } catch { return { status: 'INVALID_URL', reason: 'MALFORMED_URL' }; }
}

/** All counts are source-enumeration counts, not independent physical checklist certification. */
export function buildInventory(input) {
  const issues = [];
  const addIssue = (kind, source, id, extra = {}) => issues.push({ kind, source, id, ...extra });
  function indexed(rows, source, cards = false) {
    requireThat(Array.isArray(rows) && rows.length > 0 && rows.length <= 100_000, `Invalid index envelope: ${source}`);
    const grouped = new Map();
    for (const row of rows) {
      if (!row || !validId(row.id) || (cards && !validId(row.localId))) {
        addIssue('INVALID_SOURCE_ID', source, row?.id ?? null, { localId: row?.localId ?? null });
        continue;
      }
      grouped.set(row.id, [...(grouped.get(row.id) ?? []), row]);
    }
    const accepted = new Map(), ambiguous = new Set();
    for (const [id, records] of grouped) {
      if (records.length !== 1) {
        ambiguous.add(id);
        addIssue('DUPLICATE_SOURCE_ID', source, id, { records: records.length });
      } else accepted.set(id, records[0]);
    }
    return { accepted, ambiguous, ids: new Set(grouped.keys()), rawCount: rows.length };
  }
  const deSets = indexed(input['sets-de.json'], 'sets-de');
  const enSets = indexed(input['sets-en.json'], 'sets-en');
  const de = indexed(input['cards-de.json'], 'cards-de', true);
  const en = indexed(input['cards-en.json'], 'cards-en', true);
  const pocket = input['series-tcgp.json'];
  requireThat(pocket?.id === 'tcgp', 'Missing exact Pocket series identity');
  const pocketSets = indexed(pocket.sets, 'series-tcgp');
  requireThat(!pocketSets.ambiguous.size && pocketSets.accepted.size === pocket.sets.length, 'Invalid Pocket membership index');
  const excluded = new Set(pocketSets.accepted.keys());
  const physicalSets = [...deSets.accepted.values()].filter(set => !excluded.has(set.id));
  requireThat(physicalSets.length > 0, 'No physical German-index sets');
  const allKnownSets = [...new Set([...deSets.ids, ...enSets.ids])].sort((a, b) => b.length - a.length || a.localeCompare(b));
  const cards = [], excludedCards = [], outsideGermanIndex = [], unknownCards = [];
  for (const id of [...new Set([...de.ids, ...en.ids])].sort()) {
    const german = de.accepted.get(id), english = en.accepted.get(id);
    const setId = allKnownSets.find(set => id.startsWith(`${set}-`));
    const disputed = de.ambiguous.has(id) || en.ambiguous.has(id);
    if (!setId) { unknownCards.push(id); addIssue('UNKNOWN_SET_ID', 'cards-union', id); continue; }
    if (excluded.has(setId)) { excludedCards.push(id); continue; }
    if (!deSets.ids.has(setId)) { outsideGermanIndex.push(id); continue; }
    const identityIssues = [];
    if (deSets.ambiguous.has(setId)) identityIssues.push('AMBIGUOUS_SET_ID');
    if (disputed) identityIssues.push('AMBIGUOUS_CARD_ID');
    for (const [language, row] of [['de', german], ['en', english]]) {
      if (row && id !== `${setId}-${row.localId}`) identityIssues.push(`${language.toUpperCase()}_LOCAL_ID_MISMATCH`);
    }
    if (german && english && german.localId !== english.localId) identityIssues.push('CROSS_LANGUAGE_LOCAL_ID_CONFLICT');
    for (const issue of identityIssues) addIssue(issue, 'cards-union', id, { setId });
    const deImage = imageCandidate(german?.image), enImage = imageCandidate(english?.image);
    for (const [language, candidate] of [['de', deImage], ['en', enImage]]) {
      if (candidate.status === 'INVALID_URL') addIssue('INVALID_IMAGE_URL', language, id, { reason: candidate.reason });
    }
    const usable = candidate => Boolean(candidate.low && candidate.high);
    const selectedLanguage = identityIssues.length ? null : usable(deImage) ? 'de' : usable(enImage) ? 'en' : null;
    cards.push({ sourceCardId: id, setId, localId: german?.localId ?? english?.localId ?? null,
      nameDe: german?.name ?? null, nameEn: english?.name ?? null, hasGermanRecord: Boolean(german),
      identityStatus: identityIssues.length ? 'UNRESOLVED' : 'EXACT_SOURCE_ID', identityIssues,
      deImage, enImage, selectedLanguage,
      imageState: identityIssues.length ? 'IDENTITY_UNRESOLVED' : selectedLanguage === 'de' ? 'GERMAN_URL_PRESENT'
        : selectedLanguage === 'en' ? 'SAME_ID_ENGLISH_FALLBACK_PRESENT' : 'BOTH_LANGUAGES_MISSING_OR_INVALID',
    });
  }
  const setRows = physicalSets.map(set => {
    const members = cards.filter(card => card.setId === set.id);
    return { setId: set.id, nameDe: set.name ?? null, declaredOfficial: set.cardCount?.official ?? null,
      declaredTotal: set.cardCount?.total ?? null, enumeratedUnion: members.length,
      germanRecords: members.filter(card => card.hasGermanRecord).length,
      missingGermanImage: members.filter(card => !card.deImage.high).length,
      missingBothImages: members.filter(card => !card.deImage.high && !card.enImage.high).length,
      unresolvedIdentities: members.filter(card => card.identityStatus !== 'EXACT_SOURCE_ID').length,
      declaredTotalMatchesEnumeration: Number.isInteger(set.cardCount?.total) ? set.cardCount.total === members.length : null };
  }).sort((a, b) => a.setId.localeCompare(b.setId));
  return { schemaVersion: 1, scope: 'German set index minus exact tcgp membership; union of DE/EN card IDs',
    counts: { listedGermanSets: deSets.rawCount, acceptedGermanSets: deSets.accepted.size, physicalSets: setRows.length,
      pocketMembershipSets: excluded.size, excludedPocketCardIds: excludedCards.length,
      unionCardIdsInGermanPhysicalSets: cards.length, outsideGermanSetIndexCardIds: outsideGermanIndex.length,
      unknownSetCardIds: unknownCards.length, germanRecords: cards.filter(card => card.hasGermanRecord).length,
      missingGermanImage: cards.filter(card => !card.deImage.high).length,
      missingBothImages: cards.filter(card => !card.deImage.high && !card.enImage.high).length,
      sameIdEnglishFallbackCandidates: cards.filter(card => card.selectedLanguage === 'en').length,
      unresolvedIdentities: cards.filter(card => card.identityStatus !== 'EXACT_SOURCE_ID').length,
      issues: issues.length },
    sets: setRows, cards, issues, unknownCards, outsideGermanIndex,
    limitations: ['URLs have not been fetched by inventory mode.',
      'English artwork is labelled as an English fallback; German printed artwork is not implied.',
      'Declared totals are not an independently verified physical checklist; above-main and special numbers are retained.',
      'Only the five public indices are fetched; no per-card detail storm, prices, user collection or app database.',
      'App-maintained supplements and existing saved-binder images are not part of this raw index inventory.'] };
}

export function makeHeadPlan(inventory, quality = 'both', sources = 'chosen') {
  requireThat(['both', 'low', 'high'].includes(quality) && ['chosen', 'all'].includes(sources), 'Invalid HEAD plan');
  const urls = new Map();
  for (const card of inventory.cards) {
    if (card.identityStatus !== 'EXACT_SOURCE_ID') continue;
    const languages = sources === 'all' ? ['de', 'en'] : card.selectedLanguage ? [card.selectedLanguage] : [];
    for (const language of languages) for (const size of quality === 'both' ? ['low', 'high'] : [quality]) {
      const url = card[`${language}Image`][size];
      if (!url) continue;
      const row = urls.get(url) ?? { url, references: [] };
      row.references.push({ sourceCardId: card.sourceCardId, setId: card.setId, language, quality: size });
      urls.set(url, row);
    }
  }
  return [...urls.values()].sort((a, b) => a.url.localeCompare(b.url));
}

export function classifyHead(status, contentType) {
  if (status === 404 || status === 410) return 'NOT_AVAILABLE';
  if (status >= 200 && status < 300 && /^image\//i.test(contentType ?? '')) return 'REACHABLE_HEAD';
  return 'UNKNOWN';
}

/** Space actual request starts, including after delayed timers, not nominal reservations. */
export function createHeadStartGate({ now = Date.now, sleep = pause } = {}) {
  let previous = Promise.resolve(), lastStart = null;
  return async (permitted, start) => {
    const ahead = previous;
    let release;
    previous = new Promise(resolve => { release = resolve; });
    await ahead;
    try {
      if (!permitted()) return null;
      while (lastStart !== null && now() - lastStart < 125) {
        await sleep(125 - (now() - lastStart));
        if (!permitted()) return null;
      }
      if (!permitted()) return null;
      lastStart = now();
      // Call fetch synchronously here, then release the gate without waiting on IO.
      // Returning a wrapper prevents async promise assimilation from serializing IO.
      return { pending: start() };
    } finally { release(); }
  };
}

function checkedRoot(value) {
  requireThat(typeof value === 'string' && path.isAbsolute(value), 'Absolute audit directory required');
  const root = path.resolve(value);
  requireThat(path.dirname(root).toLowerCase() === path.resolve(ALLOWED_PARENT).toLowerCase() &&
    /^card-images-20261003-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(path.basename(root)),
  'Only the newly owned dated UUID audit directory is permitted');
  return root;
}

async function boundedBytes(response, limit) {
  requireThat(response.body, 'Missing response body');
  const chunks = []; let length = 0;
  for await (const chunk of response.body) {
    length += chunk.length;
    requireThat(length <= limit, 'INDEX_BYTE_LIMIT');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

export async function createInventory(rootArg) {
  const root = checkedRoot(rootArg);
  await fs.mkdir(path.dirname(root), { recursive: true });
  await fs.mkdir(root); // EEXIST is fatal: never adopt/overwrite a previous snapshot.
  const manifest = { schemaVersion: 1, auditId: path.basename(root), startedAt: new Date().toISOString(), stage: 'FETCHING', sources: [] };
  const saveManifest = () => fs.writeFile(path.join(root, 'source-manifest.json'), jsonBytes(manifest));
  await saveManifest();
  const input = {};
  try {
    for (const [file, url] of SOURCES) {
      if (manifest.sources.length) await pause(150);
      const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(20_000),
        headers: { 'User-Agent': 'DomiDex-public-card-image-audit/1.0', 'Cache-Control': 'no-cache' } });
      if (response.status !== 200) {
        manifest.failure = { file, url, status: response.status, retryAfter: response.headers.get('retry-after') };
        await response.body?.cancel();
        throw new Error(`INDEX_HTTP_${response.status}`);
      }
      const bytes = await boundedBytes(response, INDEX_LIMIT);
      input[file] = JSON.parse(bytes.toString('utf8'));
      await fs.writeFile(path.join(root, file), bytes, { flag: 'wx' });
      manifest.sources.push({ file, url, checkedAt: new Date().toISOString(), status: 200, bytes: bytes.length,
        sha256: hash(bytes), etag: response.headers.get('etag'), lastModified: response.headers.get('last-modified') });
      await saveManifest();
    }
    const sourceFingerprint = hash(jsonBytes(manifest.sources.map(row => [row.file, row.sha256])));
    const report = { sourceFingerprint, createdAt: new Date().toISOString(), data: buildInventory(input) };
    const bytes = jsonBytes(report);
    await fs.writeFile(path.join(root, 'inventory.json'), bytes, { flag: 'wx' });
    manifest.inventorySha256 = hash(bytes);
    manifest.sourceFingerprint = sourceFingerprint;
    manifest.stage = 'INVENTORY_COMPLETE';
    manifest.finishedAt = new Date().toISOString();
    await saveManifest();
    console.log(JSON.stringify({ root, stage: manifest.stage, sourceFingerprint, counts: report.data.counts }));
    return report;
  } catch (error) {
    manifest.stage = 'FAILED'; manifest.error = error.message; await saveManifest(); throw error;
  }
}

async function loadSnapshot(rootArg) {
  const root = checkedRoot(rootArg);
  const manifest = JSON.parse(await fs.readFile(path.join(root, 'source-manifest.json'), 'utf8'));
  requireThat(manifest.schemaVersion === 1 && manifest.auditId === path.basename(root) &&
    manifest.stage === 'INVENTORY_COMPLETE' && manifest.sources?.length === SOURCES.length, 'Incomplete/foreign snapshot');
  const input = {};
  for (const [index, [file, url]] of SOURCES.entries()) {
    const row = manifest.sources[index];
    requireThat(row.file === file && row.url === url && row.status === 200 && HASH.test(row.sha256), 'Invalid source receipt');
    const bytes = await fs.readFile(path.join(root, file));
    requireThat(bytes.length === row.bytes && bytes.length <= INDEX_LIMIT && hash(bytes) === row.sha256, `Source changed: ${file}`);
    input[file] = JSON.parse(bytes.toString('utf8'));
  }
  const fingerprint = hash(jsonBytes(manifest.sources.map(row => [row.file, row.sha256])));
  requireThat(fingerprint === manifest.sourceFingerprint, 'Source fingerprint mismatch');
  const bytes = await fs.readFile(path.join(root, 'inventory.json'));
  requireThat(hash(bytes) === manifest.inventorySha256, 'Inventory changed');
  const inventory = JSON.parse(bytes.toString('utf8'));
  requireThat(inventory.sourceFingerprint === fingerprint &&
    JSON.stringify(inventory.data) === JSON.stringify(buildInventory(input)), 'Inventory is not the exact frozen source projection');
  return { root, fingerprint, inventory: inventory.data };
}

export function reusableChecks(rows, urls, sourceFingerprint, planFingerprint, now = Date.now()) {
  const latest = new Map();
  for (const row of rows) {
    requireThat(row.sourceFingerprint === sourceFingerprint && row.planFingerprint === planFingerprint && urls.has(row.url),
      'Foreign checkpoint source/plan/URL');
    requireThat(['REACHABLE_HEAD', 'NOT_AVAILABLE', 'UNKNOWN'].includes(row.status) &&
      Number.isFinite(Date.parse(row.checkedAt)) && Date.parse(row.checkedAt) <= now + 60_000, 'Invalid checkpoint result');
    requireThat(row.status === classifyHead(row.httpStatus, row.contentType), 'Checkpoint classification mismatch');
    latest.set(row.url, row);
  }
  return new Map([...latest].filter(([, row]) => row.status !== 'UNKNOWN' && now - Date.parse(row.checkedAt) <= 24 * 60 * 60 * 1000));
}

export async function verifyImages(rootArg, { quality = 'both', sources = 'chosen', resume = false, limit = 50000 } = {}) {
  requireThat(Number.isInteger(limit) && limit > 0 && limit <= 100000, 'Invalid explicit HEAD request limit');
  const { root, fingerprint, inventory } = await loadSnapshot(rootArg);
  const plan = makeHeadPlan(inventory, quality, sources);
  const planFingerprint = hash(jsonBytes({ quality, sources, plan }));
  const journalFile = path.join(root, `head-${quality}-${sources}.jsonl`);
  let previous = [];
  try {
    const stat = await fs.stat(journalFile);
    requireThat(resume && stat.isFile() && stat.size <= 64 * 1024 * 1024, 'Existing checkpoint requires explicit --resume');
    const text = await fs.readFile(journalFile, 'utf8');
    requireThat(!text || text.endsWith('\n'), 'Incomplete checkpoint tail; preserve and inspect, never silently discard');
    previous = text.split('\n').filter(Boolean).map(line => JSON.parse(line));
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  const urls = new Set(plan.map(row => row.url));
  const results = reusableChecks(previous, urls, fingerprint, planFingerprint);
  const pending = plan.filter(row => !results.has(row.url));
  const retryAt = Math.max(0, ...previous.map(row => row.retryNotBefore ?? 0));
  requireThat(Date.now() >= retryAt, `Provider backoff active until ${new Date(retryAt).toISOString()}`);
  const runId = crypto.randomUUID();
  const output = path.join(root, `head-run-${runId}`);
  await fs.mkdir(output);
  await fs.writeFile(path.join(output, 'plan.json'), jsonBytes({ sourceFingerprint: fingerprint, planFingerprint, quality, sources, plan }), { flag: 'wx' });
  const journal = await fs.open(journalFile, resume ? 'a' : 'ax');
  let cursor = 0, started = 0, stopped = false;
  const startGate = createHeadStartGate();
  let writeTail = Promise.resolve();
  const startedAt = new Date().toISOString();
  const save = row => { writeTail = writeTail.then(() => journal.write(`${JSON.stringify(row)}\n`)); return writeTail; };
  const progress = setInterval(() => console.log(JSON.stringify({ stage: 'HEAD', runId,
    plannedUrls: plan.length, checkedUrls: results.size, requestsStarted: started,
    remainingUnchecked: plan.length - results.size, stoppedOnRateLimit: stopped })), 30000);
  try {
    await Promise.all(Array.from({ length: 4 }, async () => {
      while (!stopped && cursor < pending.length && started < limit) {
        const job = pending[cursor++];
        const row = { url: job.url, sourceFingerprint: fingerprint, planFingerprint, runId,
          status: 'UNKNOWN' };
        try {
          const ticket = await startGate(() => !stopped && started < limit, () => {
            started++;
            row.checkedAt = new Date().toISOString();
            return fetch(job.url, { method: 'HEAD', redirect: 'error', signal: AbortSignal.timeout(10000),
              headers: { 'User-Agent': 'DomiDex-public-card-image-audit/1.0' } });
          });
          if (ticket === null) break;
          const response = await ticket.pending;
          row.httpStatus = response.status;
          row.contentType = response.headers.get('content-type');
          row.contentLength = response.headers.get('content-length');
          row.status = classifyHead(row.httpStatus, row.contentType);
          if (response.status === 429) {
            stopped = true;
            const retry = response.headers.get('retry-after');
            const seconds = Number(retry);
            const requested = retry && Number.isFinite(seconds) ? Date.now() + seconds * 1000 : Date.parse(retry ?? '');
            row.retryNotBefore = Math.max(Date.now() + 30000, Number.isFinite(requested) ? requested : 0);
            row.retryAfter = retry;
          }
          await response.body?.cancel();
        } catch (error) { row.error = error.name === 'TimeoutError' ? 'TIMEOUT' : error.message; }
        await save(row);
        results.set(row.url, row);
      }
    }));
  } finally { clearInterval(progress); await writeTail; await journal.close(); }
  const summary = { schemaVersion: 1, runId, sourceFingerprint: fingerprint, planFingerprint, startedAt,
    finishedAt: new Date().toISOString(), quality, sources, uniquePlannedUrls: plan.length, requestsStarted: started,
    reachableHead: [...results.values()].filter(row => row.status === 'REACHABLE_HEAD').length,
    notAvailable: [...results.values()].filter(row => row.status === 'NOT_AVAILABLE').length,
    unknown: [...results.values()].filter(row => row.status === 'UNKNOWN').length,
    unchecked: plan.length - results.size, stoppedOnRateLimit: stopped,
    inventoryIdentityIssues: inventory.counts.unresolvedIdentities, inventoryMissingBothImages: inventory.counts.missingBothImages,
    validation: 'HEAD and MIME only; not decoded artwork, German printed text, UI display, or saved-binder reconciliation' };
  summary.complete = !stopped && summary.unknown === 0 && summary.unchecked === 0;
  await fs.writeFile(path.join(output, 'summary.json'), jsonBytes(summary), { flag: 'wx' });
  console.log(JSON.stringify({ output, ...summary }));
  return summary;
}

async function main(args) {
  const mode = args.shift();
  const options = {};
  while (args.length) {
    const key = args.shift();
    requireThat(['--root', '--quality', '--sources', '--limit', '--resume'].includes(key) && !(key in options), 'Unknown/duplicate option');
    options[key] = key === '--resume' ? true : args.shift();
    requireThat(options[key] !== undefined, `Missing value: ${key}`);
  }
  if (mode === 'inventory') {
    requireThat(Object.keys(options).every(key => key === '--root'), 'Inventory accepts only --root');
    await createInventory(options['--root']);
  } else if (mode === 'head') {
    const result = await verifyImages(options['--root'], { quality: options['--quality'] ?? 'both', sources: options['--sources'] ?? 'chosen',
      limit: options['--limit'] === undefined ? 50000 : Number(options['--limit']), resume: options['--resume'] === true });
    if (!result.complete) process.exitCode = 2;
  } else throw new Error('Use inventory --root <fresh UUID directory>, or head --root <snapshot> [--quality both|low|high] [--sources chosen|all] [--limit N] [--resume]');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
