/** Read-only, exact numbered official German Dragon Majesty pages. No CAPTCHA retries. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const norm = value => value.replace(/&amp;/g, '&').replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(+n))
  .replace(/&#x([\da-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
  .normalize('NFC').replace(/\s+/g, ' ').trim().toLocaleLowerCase('de');

/** Labels only: never return the challenge HTML, incident identifier, or IP address. */
export function accessBoundaryReason(html) {
  if (/_Incapsula_Resource/i.test(html)) return 'INCAPSULA_RESOURCE';
  if (/request\s+unsuccessful/i.test(html)) return 'REQUEST_UNSUCCESSFUL';
  if (/incident(?:\s|&nbsp;|&#160;)+id\b/i.test(html)) return 'INCIDENT_ID';
  if (/<(?:h1|title)\b[^>]*>\s*(?:Pardon Our Interruption|Access Denied|Just a moment)/i.test(html)) return 'ACCESS_CHALLENGE_TITLE';
  return null;
}

export function parseOfficialCard(html, card) {
  if (card.setId !== 'sm7.5' || !/^\d{1,2}$/.test(card.localId) ||
      card.sourceCardId !== `sm7.5-${card.localId}` || +card.localId < 1 || +card.localId > 78) throw Error('SOURCE_ID');
  const boundary = accessBoundaryReason(html);
  if (boundary) throw Error(`ACCESS_BOUNDARY:${boundary}`);
  const meta = key => html.match(new RegExp(`<meta[^>]+(?:name|property)="${key}"[^>]+content="([^"]+)"`, 'i'))?.[1];
  const title = html.match(/<h1>\s*([^<]+)\s*<\/h1>/i)?.[1];
  const collector = meta('pkm-id'), set = meta('pkm-subcategory'), image = meta('og:image');
  if (collector !== `${+card.localId}/70` || norm(set ?? '') !== norm('Majestät der Drachen') ||
      !title || norm(title) !== norm(card.nameDe ?? '')) throw Error('PAGE_IDENTITY_MISMATCH');
  const url = new URL(image);
  if (url.protocol !== 'https:' || url.hostname !== 'assets.pokemon.com' || url.search || url.hash ||
      url.pathname !== `/static-assets/content-assets/cms2-de-de/img/cards/web/SM75/SM75_DE_${+card.localId}.png`) throw Error('IMAGE_IDENTITY_MISMATCH');
  return { sourceSetId: card.setId, sourceCardId: card.sourceCardId, sourceLocalId: card.localId,
    image, language: 'de', pageName: title, collector, setName: set,
    evidence: 'Official German page names exact expansion, collector number and artwork; German name agrees' };
}

/** Dependency injection permits a finite stop test without any real network request. */
export async function inspectOfficialPages(cards, { fetchPage, savePage, pause }) {
  const results = [];
  for (const card of cards) {
    const page = `https://www.pokemon.com/de/pokemon-sammelkartenspiel/pokemon-karten/series/sm75/${+card.localId}/`;
    const record = { sourceCardId: card.sourceCardId, page, checkedAt: new Date().toISOString() };
    try {
      const response = await fetchPage(page);
      record.httpStatus = response.status;
      if ([403, 429].includes(response.status)) { await response.body?.cancel(); record.status = 'ACCESS_BOUNDARY_STOP'; results.push(record); break; }
      if (response.status !== 200) { await response.body?.cancel(); throw Error(`HTTP_${response.status}`); }
      const chunks = []; let size = 0;
      for await (const chunk of response.body) { size += chunk.length; if (size > 1024 * 1024) throw Error('BYTE_LIMIT'); chunks.push(chunk); }
      const bytes = Buffer.concat(chunks), html = bytes.toString('utf8');
      record.pageSha256 = sha(bytes);
      const boundary = accessBoundaryReason(html);
      if (boundary) {
        record.status = 'ACCESS_BOUNDARY_STOP'; record.accessBoundary = boundary;
        // A disk failure must not turn a recognized boundary into permission to
        // continue requesting later pages. Preserve the stop even without raw evidence.
        try { await savePage(card.sourceCardId, bytes); } catch { record.evidenceError = 'PAGE_EVIDENCE_WRITE_FAILED'; }
        results.push(record); break;
      }
      await savePage(card.sourceCardId, bytes);
      record.candidate = parseOfficialCard(html, card);
      record.status = 'IDENTITY_VERIFIED_IMAGE_NOT_PROBED';
    } catch (error) { record.status = 'UNRESOLVED'; record.error = error.message; }
    results.push(record);
    await pause();
  }
  return results;
}

/** Offline correction of the already captured attempt, not another discovery run. */
export async function reclassifySavedPages(directory) {
  directory = path.resolve(directory);
  const originalPath = path.join(directory, 'candidates.json');
  const originalBytes = await fs.readFile(originalPath);
  const original = JSON.parse(originalBytes);
  if (original.expected !== 78 || original.results?.length !== 78) throw Error('Unexpected saved SM75 report');
  const ids = new Set();
  const results = [];
  for (const record of original.results) {
    if (!/^sm7\.5-(?:[1-9]|[1-6]\d|7[0-8])$/.test(record.sourceCardId) || ids.has(record.sourceCardId)) throw Error('Invalid saved source identity');
    ids.add(record.sourceCardId);
    const file = `${record.sourceCardId}.html`;
    const filePath = path.join(directory, file);
    const stat = await fs.lstat(filePath);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 1024 * 1024) throw Error('Unsafe saved page');
    const bytes = await fs.readFile(filePath);
    const digest = sha(bytes);
    if (digest !== record.pageSha256) throw Error('Saved page hash mismatch');
    const boundary = accessBoundaryReason(bytes.toString('utf8'));
    results.push({ sourceCardId: record.sourceCardId, file, bytes: bytes.length, sha256: digest,
      httpStatus: record.httpStatus, originalStatus: record.status,
      status: boundary ? 'ACCESS_BOUNDARY' : 'UNCLASSIFIED_SAVED_PAGE', accessBoundary: boundary });
  }
  const firstBoundary = results.findIndex(row => row.status === 'ACCESS_BOUNDARY');
  const report = { createdAt: new Date().toISOString(), mode: 'OFFLINE_RECLASSIFICATION_NO_REQUESTS',
    originalReport: 'candidates.json', originalReportSha256: sha(originalBytes),
    inventorySha256: original.inventorySha256, expected: original.expected, inspected: results.length,
    accessBoundaryCount: results.filter(row => row.status === 'ACCESS_BOUNDARY').length,
    firstBoundaryRequestIndex: firstBoundary < 0 ? null : firstBoundary + 1,
    originalRequestsAfterFirstBoundary: firstBoundary < 0 ? 0 : results.length - firstBoundary - 1,
    verifiedCardPages: 0, results,
    limitations: ['Retrospective classification only; the original attempt did not stop at the first challenge.',
      'No requests, retries, client changes, image probes, or access-boundary bypass.',
      'Challenge pages do not establish card or image identity. Original report and HTML remain unchanged.'] };
  if (sha(await fs.readFile(originalPath)) !== report.originalReportSha256) throw Error('Original report changed');
  const output = path.join(directory, 'access-boundary-correction.json');
  await fs.writeFile(output, `${JSON.stringify(report, null, 2)}\n`, { flag: 'wx' });
  return { output, inspected: report.inspected, accessBoundaryCount: report.accessBoundaryCount,
    originalReportSha256: report.originalReportSha256, originalRequestsAfterFirstBoundary: report.originalRequestsAfterFirstBoundary };
}

async function run(root) {
  root = path.resolve(root);
  if (path.dirname(root).toLowerCase() !== 'c:\\users\\dominik\\domidex-audits' ||
      !/^card-images-20261003-[a-f\d-]{36}$/.test(path.basename(root))) throw Error('Audit directory only');
  const raw = await fs.readFile(path.join(root, 'inventory.json'));
  const manifest = JSON.parse(await fs.readFile(path.join(root, 'source-manifest.json'), 'utf8'));
  if (sha(raw) !== manifest.inventorySha256) throw Error('Inventory hash mismatch');
  const cards = JSON.parse(raw).data.cards.filter(c => c.setId === 'sm7.5' && c.identityStatus === 'EXACT_SOURCE_ID');
  if (cards.length !== 78 || new Set(cards.map(c => c.localId)).size !== 78) throw Error('Unexpected SM75 inventory');
  const output = path.join(root, `official-sm75-${crypto.randomUUID()}`);
  await fs.mkdir(output);
  const results = await inspectOfficialPages(cards, {
    fetchPage: page => fetch(page, { redirect: 'error', signal: AbortSignal.timeout(15000),
      headers: { 'User-Agent': 'DomiDex-public-card-image-audit/1.0' } }),
    savePage: (id, bytes) => fs.writeFile(path.join(output, `${id}.html`), bytes, { flag: 'wx' }),
    pause: () => new Promise(resolve => setTimeout(resolve, 500)),
  });
  const report = { createdAt: new Date().toISOString(), inventorySha256: sha(raw), expected: cards.length, results,
    limitations: ['Page identity and image reference only, not an image HTTP/decode result.', 'Stop without bypass/retry on access boundary.'] };
  await fs.writeFile(path.join(output, 'candidates.json'), JSON.stringify(report, null, 2), { flag: 'wx' });
  console.log(JSON.stringify({ output, expected: cards.length, checked: results.length,
    verified: results.filter(r => r.candidate).length, stopped: results.some(r => r.status === 'ACCESS_BOUNDARY_STOP') }));
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  (process.argv[2] === '--reclassify'
    ? reclassifySavedPages(process.argv[3]).then(result => console.log(JSON.stringify(result)))
    : run(process.argv[2])).catch(error => { console.error(error.message); process.exitCode = 1; });
}
