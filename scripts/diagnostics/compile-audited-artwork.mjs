/** Offline: emit an apply_patch document from exact-identity, decoded image receipts. */
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const args = process.argv.slice(2);
const inventoryFile = args.shift();
const receiptFiles = args;
if (!inventoryFile || receiptFiles.length < 4) throw Error('Inventory and decoded receipts required');
const inventoryRaw = await fs.readFile(inventoryFile);
const inventory = JSON.parse(inventoryRaw).data;
const originals = new Map(inventory.cards.map(c => [c.sourceCardId, c]));
const receipts = [], images = new Map(), candidates = [];
for (const file of receiptFiles) {
  const raw = await fs.readFile(file), report = JSON.parse(raw);
  const plan = JSON.parse(await fs.readFile(path.join(path.dirname(file), 'plan.json'), 'utf8'));
  if (report.sourceSha256 !== plan.sourceSha256) throw Error('Plan hash mismatch');
  const sourceRaw = await fs.readFile(plan.inputFile);
  if (sha(sourceRaw) !== plan.sourceSha256) throw Error('Source changed');
  receipts.push({ file, sha256: sha(raw), sourceSha256: report.sourceSha256 });
  for (const image of report.images) {
    if (!image.decoded) continue;
    const bytes = await fs.readFile(path.join(path.dirname(file), image.file));
    if (sha(bytes) !== image.sha256 || bytes.length !== image.bytes) throw Error('Image bytes changed');
    images.set(image.url, image);
  }
  // Retry reports may only verify one quality. They provide bytes, never new identity mappings.
  for (const card of plan.cards) if (card.candidate.kind !== 'SINGLE_QUALITY_RETRY') candidates.push(card);
}
const selected = new Map();
for (const card of candidates) {
  const original = originals.get(card.sourceCardId);
  if (!original || original.setId !== card.setId || original.localId !== card.sourceLocalId ||
      card.sourceCardId !== `${card.setId}-${card.sourceLocalId}`) throw Error('Unproved identity: ' + card.sourceCardId);
  const c = card.candidate;
  if (!['de', 'en'].includes(c.language)) throw Error('Unsupported artwork language');
  const low = images.get(c.lowUrl), high = images.get(c.highUrl);
  if (!low && !high) continue;
  const entry = { set: card.setId, id: card.sourceCardId, local: card.sourceLocalId,
    language: c.language, small: low ? c.lowUrl : c.highUrl, large: high ? c.highUrl : c.lowUrl,
    lowResolutionOnly: !high, kind: c.kind, unavailable: [], replaceable: [] };
  const score = (c.language === 'de' ? 100 : 0) + (low && high ? 10 : 0);
  if (!selected.has(entry.id) || score > selected.get(entry.id).score) selected.set(entry.id, { ...entry, score });
}
// A URL is unavailable only if an actual retained request proved 404/410.
const unavailable = new Set();
for (const file of receiptFiles) {
  const report = JSON.parse(await fs.readFile(file, 'utf8'));
  for (const image of report.images) if (image.status === 'NOT_AVAILABLE' && [404, 410].includes(image.httpStatus)) unavailable.add(image.url);
  for (const card of report.cards) {
    const check = card.candidate.evidence?.originalCheck;
    if (check?.status === 'NOT_AVAILABLE' && [404, 410].includes(check.httpStatus) &&
        check.url === card.candidate.evidence.originalHighWebp) unavailable.add(check.url);
  }
}
// The five exact URLs were already confirmed and handled by the September 22 source audit.
const datedBroken = new Set(['bw2-9', 'swsh12-047', 'sm1-94', 'swsh10-162', 'sv02-269']);
for (const entry of selected.values()) {
  const original = originals.get(entry.id);
  // A missing API URL can still have a previously used gallery-parent URL.
  // Bind each failed candidate URL to its own exact identity, not merely a host.
  for (const card of candidates.filter(card => card.sourceCardId === entry.id)) {
    for (const url of [card.candidate.lowUrl, card.candidate.highUrl]) {
      if (unavailable.has(url)) entry.unavailable.push(url);
    }
  }
  for (const image of [original.deImage, original.enImage]) for (const key of ['low', 'high']) {
    const url = image?.[key];
    if (url && (unavailable.has(url) || datedBroken.has(entry.id) && image === original.deImage)) entry.unavailable.push(url);
  }
  if (entry.language === 'de') for (const key of ['low', 'high']) {
    if (original.enImage?.[key]) entry.replaceable.push(original.enImage[key]);
  }
  // New source RGB identities have exact broken German endpoints and a reviewed image-only alias.
  if (['30th-R', '30th-G', '30th-B'].includes(entry.id)) {
    for (const key of ['low', 'high']) if (original.deImage?.[key] && unavailable.has(original.deImage[key])) entry.unavailable.push(original.deImage[key]);
  }
  entry.unavailable = [...new Set(entry.unavailable)];
  entry.replaceable = [...new Set(entry.replaceable)];
  delete entry.score;
}
const entries = [...selected.values()].sort((a, b) => a.id.localeCompare(b.id, 'en'));
const chunks = [];
for (let index = 0; index < entries.length; index += 35) chunks.push(entries.slice(index, index + 35));
const source = `package com.dominik.cardvault.data.catalog

// Exact source identities audited 2026-10-03. No network, name matching or price changes.
// Inventory SHA256: ${sha(inventoryRaw)}
// Decoded-image receipt hashes:
${receipts.map(r => '// ' + r.sha256).join('\n')}
// English references remain English; three legacy promos currently have thumbnail-only artwork.
internal fun auditedPokemonCardArtworkOverrides(): List<PokemonCardArtworkOverride> = buildList {
${chunks.map((_, i) => `    addAll(auditedArtworkChunk${i}())`).join('\n')}
}

private fun parseAuditedArtworkRows(rows: String): List<PokemonCardArtworkOverride> =
    rows.trimIndent().lineSequence().filter(String::isNotBlank).map { row ->
        val fields = row.split('|')
        require(fields.size == 7)
        PokemonCardArtworkOverride(
            sourceSetId = fields[0], sourceCardId = fields[1], sourceLocalId = fields[2],
            replacement = PokemonCardArtwork(fields[3], fields[4]),
            unavailableUrls = fields[5].split(',').filter(String::isNotBlank).toSet(),
            replaceableUrls = fields[6].split(',').filter(String::isNotBlank).toSet(),
        )
    }.toList()

${chunks.map((chunk, i) => `private fun auditedArtworkChunk${i}() = parseAuditedArtworkRows("""
${chunk.map(e => '    ' + [e.set, e.id, e.local, e.small, e.large, e.unavailable.join(','), e.replaceable.join(',')].join('|')).join('\n')}
""")`).join('\n\n')}
`;
const target = 'app/src/main/java/com/dominik/cardvault/data/catalog/AuditedPokemonCardArtwork.kt';
const patch = `*** Begin Patch\n*** Add File: ${target}\n${source.split('\n').map(l => '+' + l).join('\n')}\n*** End Patch`;
const summary = { total: entries.length, german: entries.filter(e => e.language === 'de').length,
  english: entries.filter(e => e.language === 'en').length, lowResolutionOnly: entries.filter(e => e.lowResolutionOnly).map(e => e.id),
  unavailableUrls: entries.reduce((n, e) => n + e.unavailable.length, 0), receipts, entries };
process.stdout.write(JSON.stringify({ patch, summary }));
