/** Offline format adapter only. It never contacts any URL or mutates the original report. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { selectCandidates } from './verify-card-artwork-candidates.mjs';

export const SOURCE_SHA256 = 'cf916f4326d37f0cce6ff761c6d8fb77aafd84e7c034c0f1bc78e282b2811cf0';
const sha = value => crypto.createHash('sha256').update(value).digest('hex');
const check = (ok, message) => { if (!ok) throw new Error(message); };

export function adaptCandidateBytes(bytes) {
  check(sha(bytes) === SOURCE_SHA256, 'Final 242-candidate report hash mismatch');
  const report = JSON.parse(bytes.toString('utf8'));
  check(report.candidates.length === 242 && report.counts.candidates === 242, 'Final candidate count mismatch');
  const cards = report.candidates.map(row => {
    check(row.language === 'en' && row.availability === 'NOT_HTTP_CHECKED', 'Unexpected evidence classification');
    return { sourceCardId: row.sourceCardId, setId: row.sourceSetId, sourceLocalId: row.sourceLocalId,
      candidates: [{ language: 'en', lowUrl: row.small, highUrl: row.large,
        kind: 'PUBLISHED_JSON_ENGLISH_EXACT_IDENTITY_FALLBACK',
        evidence: { classification: row.evidence, upstreamSetId: row.upstreamSetId,
          upstreamId: row.upstreamId, upstreamNumber: row.upstreamNumber,
          sourceNameEn: row.sourceNameEn, upstreamNameEn: row.upstreamNameEn,
          numberComparison: row.numberComparison, nameComparison: row.nameComparison,
          sourceFile: row.upstreamSourceFile, availability: row.availability } }] };
  });
  check(selectCandidates(cards).length === 242, 'Verifier selector did not retain all exact unique identities');
  return { schemaVersion: 1, sourceSha256: SOURCE_SHA256,
    provenance: { upstreamCommit: report.upstreamCommit, sourceFingerprint: report.sourceFingerprint,
      inventorySha256: report.inventorySha256, inventoryManifestSha256: report.inventoryManifestSha256,
      priorCandidateReportSha256: report.priorCandidateReportSha256, sources: report.sources,
      setEvidence: report.setEvidence, limitations: report.limitations }, cards };
}

export async function main(argv) {
  check(argv.length === 1, 'Usage: node adapt-pokemontcg-image-candidates.mjs <final-candidates.json>');
  const input = path.resolve(argv[0]);
  check(input.startsWith(path.resolve('C:/Users/Dominik/DomiDex-audits') + path.sep)
    && path.basename(input) === 'candidates.json', 'Unexpected source path');
  const bytes = await fs.readFile(input), result = adaptCandidateBytes(bytes);
  for (const receipt of result.provenance.sources) {
    check(/^(?:commit|sets-en|cards-en-[a-z0-9]+)\.json$/.test(receipt.file), 'Unexpected source receipt path');
    const raw = await fs.readFile(path.join(path.dirname(input), receipt.file));
    check(sha(raw) === receipt.sha256 && raw.length === receipt.bytes, `Source receipt mismatch: ${receipt.file}`);
  }
  const output = path.join(path.dirname(input), 'adapted-candidates.json');
  const payload = Buffer.from(`${JSON.stringify(result, null, 2)}\n`);
  await fs.writeFile(output, payload, { flag: 'wx' });
  check(sha(await fs.readFile(input)) === SOURCE_SHA256, 'Original report changed during adaptation');
  console.log(JSON.stringify({ output, sha256: sha(payload), cards: result.cards.length }, null, 2));
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
