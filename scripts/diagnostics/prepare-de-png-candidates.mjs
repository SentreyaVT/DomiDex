/** Offline, source-bound WebP-404 -> same-base PNG candidate evidence only. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { selectCandidates } from './verify-card-artwork-candidates.mjs';

const PARENT = 'C:/Users/Dominik/DomiDex-audits';
const ROOT = `${PARENT}/card-images-20261003-6d7c9ea1-cd9b-47b4-9ac3-2754d4d39b93`;
const ASSETS = `${PARENT}/card-images-assets-20261003-e651d3bc235c4ed297d860c5d203006b`;
const SUBSETS = `${PARENT}/card-images-candidates-20261003-82cf11f339184b06aa389a31c68f4da6/candidates.json`;
const SV_CHECK = `${PARENT}/card-artwork-verified-20261003-22fa1f12-fac1-4ad7-9f0e-6e15d42f5341`;
const PLAN = `${ROOT}/head-run-136ae221-4a0c-4a6a-b592-f1713f238437/plan.json`;
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const encode = value => Buffer.from(`${JSON.stringify(value, null, 2)}\n`);
const check = (ok, message) => { if (!ok) throw new Error(message); };
const retained = [];
async function load(file, expectedHash = null) {
  const bytes = await fs.readFile(file);
  check(!expectedHash || sha(bytes) === expectedHash, `Source hash mismatch: ${file}`);
  return { bytes, data: JSON.parse(bytes.toString('utf8')), receipt: { file, bytes: bytes.length, sha256: sha(bytes) } };
}
async function completedJournal(file, retainedName) {
  const bytes = await fs.readFile(file), end = bytes.lastIndexOf(10) + 1;
  const complete = bytes.subarray(0, end);
  check(complete.length > 0, `No completed records: ${file}`);
  const rows = complete.toString('utf8').trimEnd().split('\n').map(row => JSON.parse(row));
  const receipt = { file, observedAt: new Date().toISOString(), observedBytes: bytes.length,
    completeBytes: complete.length, ignoredIncompleteTailBytes: bytes.length - complete.length,
    completeRows: rows.length, sha256: sha(complete), retainedFile: retainedName };
  retained.push([retainedName, complete]);
  return { rows, receipt };
}
function pngChoice(highWebp, evidence) {
  const url = new URL(highWebp);
  check(url.protocol === 'https:' && url.hostname === 'assets.tcgdex.net'
    && url.pathname.startsWith('/de/') && url.pathname.endsWith('/high.webp')
    && !url.port && !url.search && !url.hash && !url.username && !url.password, 'Not an exact German WebP source');
  const base = highWebp.slice(0, -'/high.webp'.length);
  return { language: 'de', lowUrl: `${base}/low.png`, highUrl: `${base}/high.png`,
    kind: 'SAME_DECLARED_BASE_PNG_AFTER_WEBP_404',
    evidence: { ...evidence, originalHighWebp: highWebp,
      formatDocumentation: 'https://tcgdex.dev/assets',
      formatDocumentationBasis: 'Root-reviewed public documentation: low/high sizes and webp/png/jpg formats.',
      networkStatus: 'NOT_CHECKED', decoded: false } };
}

async function main() {
  const manifest = await load(`${ROOT}/source-manifest.json`);
  const inventory = await load(`${ROOT}/inventory.json`, manifest.data.inventorySha256);
  check(inventory.data.sourceFingerprint === manifest.data.sourceFingerprint, 'Inventory fingerprint conflict');
  for (const source of manifest.data.sources) {
    check(['sets-de.json', 'sets-en.json', 'cards-de.json', 'cards-en.json', 'series-tcgp.json'].includes(source.file), 'Unexpected source');
    const raw = await load(`${ROOT}/${source.file}`, source.sha256);
    check(raw.bytes.length === source.bytes, 'Source length changed');
  }
  check(sha(encode(manifest.data.sources.map(x => [x.file, x.sha256]))) === manifest.data.sourceFingerprint, 'Invalid source fingerprint');
  const plan = await load(PLAN), p = plan.data;
  check(p.sourceFingerprint === manifest.data.sourceFingerprint && p.quality === 'high' && p.sources === 'chosen', 'Unexpected HEAD plan scope');
  check(sha(encode({ quality: p.quality, sources: p.sources, plan: p.plan })) === p.planFingerprint, 'Invalid plan fingerprint');
  const planned = new Set(p.plan.map(x => x.url));
  const journal = await completedJournal(`${ROOT}/head-high-chosen.jsonl`, 'head-high-chosen-complete-prefix.jsonl');
  const latest = new Map();
  for (const row of journal.rows) {
    check(row.sourceFingerprint === p.sourceFingerprint && row.planFingerprint === p.planFingerprint
      && planned.has(row.url), 'Journal/plan identity conflict');
    latest.set(row.url, row);
  }
  const cards = [], eligible = [];
  for (const row of latest.values()) {
    if (row.status !== 'NOT_AVAILABLE' || row.httpStatus !== 404) continue;
    for (const card of inventory.data.data.cards.filter(c => c.identityStatus === 'EXACT_SOURCE_ID'
      && !c.identityIssues.length && c.deImage.high === row.url && row.url.endsWith('/high.webp'))) {
      eligible.push({ sourceCardId: card.sourceCardId, setId: card.setId, sourceLocalId: card.localId,
        candidates: [pngChoice(row.url, { basis: 'FROZEN_DE_INVENTORY_URL_AND_COMPLETE_HEAD_404_RECORD',
          inventorySha256: inventory.receipt.sha256, sourceFingerprint: p.sourceFingerprint,
          planFingerprint: p.planFingerprint, journalPrefixSha256: journal.receipt.sha256, originalCheck: row })] });
    }
  }
  cards.push(...eligible.slice(0, 9));
  const subsets = await load(SUBSETS, '5a77fffceee9272bb4ff70127a426908119f3bd72b66717f37fc0cfce5ec9e0c');
  const assetProvenance = await load(`${ASSETS}/provenance.json`);
  const assets = await load(`${ASSETS}/datas.json`, '2a91112e10f238b8aca429499b83f8cd72b9a6c7bdd125a2006743129eb7b9e8');
  check(assetProvenance.data.sha256 === assets.receipt.sha256 && assetProvenance.data.bytes === assets.bytes.length, 'Asset receipt conflict');
  const subsetCard = subsets.data.cards.find(c => c.sourceCardId === 'swsh4.5sv-SV110');
  const subsetCandidate = subsetCard.candidates.find(c => c.language === 'de');
  check(subsetCandidate.highUrl === 'https://assets.tcgdex.net/de/swsh/swsh4.5/SV110/high.webp'
    && subsetCandidate.manifestMarker === assets.data.de.swsh['swsh4.5'].SV110, 'SV110 manifest identity conflict');
  check(inventory.data.data.cards.some(c => c.sourceCardId === subsetCard.sourceCardId
    && c.identityStatus === 'EXACT_SOURCE_ID' && !c.identityIssues.length && c.localId === subsetCard.sourceLocalId), 'SV110 inventory identity conflict');
  const svPlan = await load(`${SV_CHECK}/plan.json`);
  check(svPlan.data.sourceSha256 === subsets.receipt.sha256
    && JSON.stringify(svPlan.data.cards.find(c => c.sourceCardId === subsetCard.sourceCardId)?.candidate)
      === JSON.stringify(subsetCandidate), 'SV110 verifier plan conflict');
  const svJournal = await completedJournal(`${SV_CHECK}/results.jsonl`, 'subset-results-complete-prefix.jsonl');
  const svLast = svJournal.rows.filter(row => row.url === subsetCandidate.highUrl).at(-1);
  check(svLast?.status === 'NOT_AVAILABLE' && svLast.httpStatus === 404, 'No actual completed SV110 WebP404');
  cards.push({ sourceCardId: subsetCard.sourceCardId, setId: subsetCard.setId, sourceLocalId: subsetCard.sourceLocalId,
    candidates: [pngChoice(subsetCandidate.highUrl, { basis: 'PREVIOUSLY_REVIEWED_EXACT_SUBSET_PARENT_AND_COMPLETE_GET_404_RECORD',
      originalCheck: svLast, subsetSourceSha256: subsets.receipt.sha256, assetManifestSha256: assets.receipt.sha256,
      manifestPath: subsetCandidate.manifestPath, manifestMarker: subsetCandidate.manifestMarker,
      subsetIdentityEvidence: subsetCandidate.evidence, journalPrefixSha256: svJournal.receipt.sha256 })] });
  check(cards.length <= 10 && selectCandidates(cards).length === cards.length, 'Candidate count/identity rejected by root verifier');
  const report = { schemaVersion: 1, generatedAt: new Date().toISOString(), sourceSha256: journal.receipt.sha256,
    scope: 'At most nine currently observed exact German inventory WebP404 cards, plus separately verified SV110 parent.',
    limitations: ['PNG candidates only; no network or decoding performed here.',
      'Live journals are copied as complete-line prefixes, not asserted to be final or atomic snapshots.',
      'Only file format changes; no new source identity, collector-number path or card mapping.'],
    provenance: { inventory: inventory.receipt, manifest: manifest.receipt, plan: plan.receipt,
      journal: journal.receipt, subsetSource: subsets.receipt, assetManifest: assets.receipt,
      assetProvenance: assetProvenance.receipt, subsetPlan: svPlan.receipt, subsetJournal: svJournal.receipt },
    eligibleInventory404Cards: eligible.length, deferredInventory404Cards: Math.max(0, eligible.length - 9), cards };
  const output = `${PARENT}/card-images-png-candidates-20261003-${crypto.randomUUID()}`;
  await fs.mkdir(output, { recursive: false });
  for (const [name, bytes] of retained) await fs.writeFile(path.join(output, name), bytes, { flag: 'wx' });
  const payload = encode(report);
  await fs.writeFile(path.join(output, 'candidates.json'), payload, { flag: 'wx' });
  console.log(JSON.stringify({ output: path.join(output, 'candidates.json'), sha256: sha(payload),
    cards: cards.length, ids: cards.map(c => c.sourceCardId), completeHeadRows: journal.receipt.completeRows }, null, 2));
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
