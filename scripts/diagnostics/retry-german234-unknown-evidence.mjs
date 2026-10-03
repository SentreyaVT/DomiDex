/** Prepare only the five retained HTTP503 URLs; merge by ORIGINAL quality URLs, never transport aliases. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { selectCandidates } from './verify-card-artwork-candidates.mjs';
const PARENT = 'C:/Users/Dominik/DomiDex-audits';
const ORIGINAL = `${PARENT}/card-artwork-verified-20261003-c342e71c-8f20-4e2f-9944-a2a9bf76bd4f`;
const DECODED_HASH = '8540bc62e52891f515ad12994b62b8c6ad010ddac3cd070ec3652bf98d07aaf5';
const sha = b => crypto.createHash('sha256').update(b).digest('hex');
const encode = x => Buffer.from(`${JSON.stringify(x, null, 2)}\n`);
const check = (ok, message) => { if (!ok) throw new Error(message); };
async function read(file, hash = null) {
  const bytes = await fs.readFile(file);
  check(!hash || sha(bytes) === hash, `Hash changed: ${file}`);
  return { data: JSON.parse(bytes.toString('utf8')), sha256: sha(bytes), file };
}
function ownedDirectory(value, prefix) {
  const out = path.resolve(value);
  check(path.dirname(out).toLowerCase() === path.resolve(PARENT).toLowerCase()
    && path.basename(out).startsWith(prefix), 'Unexpected evidence directory');
  return out;
}
async function main(argv) {
  const original = await read(`${ORIGINAL}/decoded.json`, DECODED_HASH);
  const plan = await read(`${ORIGINAL}/plan.json`, '30092c45c3a38c77156d4cfc5b172dc6a1f6178c182bee6620e6c1d35abea34e');
  const source = await read(plan.data.inputFile, plan.data.sourceSha256);
  check(original.data.plannedCards === 234 && original.data.plannedUrls === 468
    && original.data.decodedUrls === 463 && original.data.sourceSha256 === source.sha256, 'Unexpected original scope');
  const unknown = original.data.images.filter(x => x.status === 'UNKNOWN');
  check(unknown.length === 5 && unknown.every(x => x.httpStatus === 503 && !x.decoded), 'Unexpected retry scope/status');
  const allowedUrls = new Set(unknown.map(x => x.url));
  if (argv[0] === 'prepare' && argv.length === 1) {
    const cards = [];
    for (const card of plan.data.cards) {
      const c = card.candidate, low = allowedUrls.has(c.lowUrl), high = allowedUrls.has(c.highUrl);
      if (!low && !high) continue;
      cards.push({ sourceCardId: card.sourceCardId, setId: card.setId, sourceLocalId: card.sourceLocalId,
        candidates: [{ ...c, lowUrl: low ? c.lowUrl : c.highUrl, highUrl: high ? c.highUrl : c.lowUrl,
          kind: low && high ? 'UNKNOWN_ONLY_BOTH_QUALITIES_RETRY' : 'SINGLE_QUALITY_RETRY_TRANSPORT_ALIAS',
          evidence: { originalCandidate: c, originalDecodedFile: original.file, originalDecodedSha256: DECODED_HASH,
            retriedQualities: [low && 'low', high && 'high'].filter(Boolean),
            limitation: 'A single-quality transport alias is not evidence that both original qualities decoded.' } }] });
    }
    const selected = selectCandidates(cards), requested = new Set(selected.flatMap(x => [x.candidate.lowUrl, x.candidate.highUrl]));
    check(cards.length === 3 && requested.size === 5 && [...requested].every(x => allowedUrls.has(x)), 'Retry expands beyond five unknown URLs');
    const out = `${PARENT}/card-images-german234-retry-20261003-${crypto.randomUUID()}`;
    await fs.mkdir(out, { recursive: false });
    const payload = encode({ schemaVersion: 1, sourceSha256: DECODED_HASH,
      provenance: { original, originalPlanSha256: plan.sha256, originalCandidateSourceSha256: source.sha256 },
      scope: 'Exactly five prior HTTP503 URLs only, no successful image re-fetch.', cards });
    // Only compact source receipts, not duplicated 234-card report contents.
    const parsed = JSON.parse(payload); delete parsed.provenance.original.data;
    const compact = encode(parsed), file = path.join(out, 'candidates.json');
    await fs.writeFile(file, compact, { flag: 'wx' });
    console.log(JSON.stringify({ input: file, sha256: sha(compact), urls: [...requested] }, null, 2));
  } else if (argv[0] === 'merge' && argv.length === 2) {
    const retryRoot = ownedDirectory(argv[1], 'card-artwork-verified-20261003-');
    const retry = await read(path.join(retryRoot, 'decoded.json'));
    const retryPlan = await read(path.join(retryRoot, 'plan.json'));
    const retrySource = await read(retryPlan.data.inputFile, retryPlan.data.sourceSha256);
    check(retry.data.sourceSha256 === retrySource.sha256
      && retrySource.data.sourceSha256 === DECODED_HASH, 'Retry provenance conflict');
    check(retryPlan.data.urls.length === 5 && retryPlan.data.urls.every(x => allowedUrls.has(x.url)), 'Retry plan expanded scope');
    check(retry.data.images.every(x => allowedUrls.has(x.url))
      && new Set(retry.data.images.map(x => x.url)).size === retry.data.images.length, 'Unexpected/duplicate retry image');
    const byUrl = new Map(original.data.images.map(x => [x.url, { ...x, evidenceDirectory: ORIGINAL }]));
    for (const image of retry.data.images) byUrl.set(image.url, { ...image, evidenceDirectory: retryRoot });
    const cards = plan.data.cards.map(card => {
      const low = byUrl.get(card.candidate.lowUrl), high = byUrl.get(card.candidate.highUrl);
      const smallDecoded = low?.decoded === true, largeDecoded = high?.decoded === true;
      return { ...card, smallDecoded, largeDecoded, usable: smallDecoded && largeDecoded,
        qualityRestriction: smallDecoded && largeDecoded ? 'NONE' : smallDecoded ? 'SMALL_ONLY' : largeDecoded ? 'LARGE_ONLY' : 'NO_VERIFIED_IMAGE',
        lowEvidenceDirectory: low?.evidenceDirectory, highEvidenceDirectory: high?.evidenceDirectory };
    });
    const report = { sourceSha256: source.sha256, originalDecodedSha256: DECODED_HASH,
      retryDecodedSha256: retry.sha256, retryPlanSha256: retryPlan.sha256, retrySourceSha256: retrySource.sha256,
      plannedUrls: 468, decodedUrls: [...byUrl.values()].filter(x => x.decoded).length,
      plannedCards: 234, usableCards: cards.filter(x => x.usable).length,
      qualityRestrictedCards: cards.filter(x => !x.usable).map(x => ({ sourceCardId: x.sourceCardId, restriction: x.qualityRestriction })),
      cards, images: [...byUrl.values()], limitations: ['Merged strictly by original low/high URLs, not retry transport aliases.',
        'No manual visual identity review of all images is implied; retained original identity evidence remains unchanged.'] };
    const file = path.join(retryRoot, 'combined-german234-decoded.json'), payload = encode(report);
    await fs.writeFile(file, payload, { flag: 'wx' });
    console.log(JSON.stringify({ file, sha256: sha(payload), decodedUrls: report.decodedUrls,
      usableCards: report.usableCards, qualityRestrictedCards: report.qualityRestrictedCards }, null, 2));
  } else throw new Error('Usage: prepare | merge <retry-evidence-directory>');
}
main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
