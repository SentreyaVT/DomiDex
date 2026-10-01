# Grand Update — integration gate (2026-09-22)

> Historischer Bericht / Entwurf des im Titel genannten Stands; keine aktuelle Release- oder Produktfreigabe. Maßgeblich sind [Projektstatus vom 01.10.2026](PROJECT_STATUS.md) und [Versionshistorie](RELEASE_HISTORY.md). Aussagen zu R8, ScanLab/Knowledge, Grading, Cloud, Test-Tracks und Geschäftsmodell dürfen nicht ungeprüft übernommen werden. Alte lokale Artefaktverweise können außerhalb der Arbeitsstation fehlen.

## NOT READY for release

This is a locally verified engineering checkpoint, not a published release. The existing
working-tree version 21 / 1.0-pre-beta.1 and Room schema 12 are retained. No next Play
version code was guessed and no final signed AAB was produced. No production deployment,
Play Console action, tester-list change or announcement is authorized by this checkpoint.

## Deployment compatibility

- Android and DomiDex-Web now share the same Firestore/Storage rule contract.
- New clients verify the current user's server-owned `accountAccess/{uid}` status. A
  missing document preserves valid claims; an unreadable document fails closed. Existing
  production rules must not be assumed to expose this new self-read path.
- Before distributing this Android candidate, the operator must separately approve and
  verify the coordinated rules/backend release, then run allowed/denied/revoked-role,
  authenticated Web, two-device sync and reconnect checks. Do not weaken the new checks
  to get around an undeployed server contract.
- Technical ScanKnowledge contribution is a separate per-account opt-in, initially off.
  Manual ScanLab photo submission is unchanged and never enabled by backup import.
- Web sharing is intentionally gated off. Its owner UI, Android integration, Holo/cover
  parity and large-binder verification are incomplete. The self-account-deletion route
  retains the existing readiness gate and operator-driven retention/continuation process.

## Backup V2

Format: `domidex.collection.backup`, version 2. Restore is a merge of missing stable
identities, not a destructive replacement. Importing again never increments an existing
entry's quantity. A changed backup containing the same sync identity does not overwrite
local edits. Identity conflicts abort rather than silently substitute another printing.

The snapshot includes owner collection/catalog identities, quantities and metadata,
set-binder membership/layout, private binders and placements, cover/effect/intensity/page
settings, grading data, portable local photos, feedback files and allowlisted presentation
preferences. Price caches, credentials, access grants and consent are not restored.

Import validates before writing. SQLite changes commit together. A durable file journal
records new files before creation and a marker commits with SQLite. Startup rolls back
only newly imported uncommitted files, or retains files after a committed database import.
Recovery and imports use the same mutex. Post-commit scheduling failure does not delete
committed images. Settings replay is idempotent and finishes before opening the collection.
A failed recovery shows a blocking retry screen; it never clears data or silently bypasses
recovery. Account switching before commit rolls back the entire graph.

Safety limits: 28 MiB input archive, 64 MiB expanded total, 14 MiB per entry, 650 entries,
compression ratio 200; additional JSON/row/photo limits apply. Manipulated paths, duplicate
entries, symlink escapes, unsupported future formats and missing declared images fail
explicitly. These are intentional limits, not a promise to export arbitrarily large photo
libraries. Exports are ordinary private ZIP files: users must protect their backup location.

## Scanner and data changes

- The current capture/geometry/orientation and ACCEPTED/AMBIGUOUS/REJECTED policy remain.
- Exactly-once OCR completion, bounded concurrency, OCR and queue deadlines, cancellation
  and bitmap/executor lifecycle cleanup address stalls without accepting weaker matches.
- Artwork candidate checks reuse descriptors and deduplicate/bound image requests.
- Price mapping does not infer products from names, product order or a majority expansion.
  Maintained or source-declared exact printing IDs remain usable.
- Four verified me04 reverse slots (013/029/051/068) repair the canonical total to 198.
- Five verified same-card image fallbacks and set-logo fallback selection are corrected.
- Snapshot audit: 144 listed physical German sets, 19,443 card identities, 31,376 source
  printing rows. 20,105 rows have a positive exact-source price. Missing upstream evidence
  is reported, never invented. Upstream printing rows are not independent proof of every
  real German finish. Cardmarket's public guide is not a German-language-only sale price.

## Regression evidence and remaining acceptance

The operator handoff outside the repository records exact final test totals, artifact
hashes, commits, audit snapshots and device limitations. Source tests cover owner isolation,
backup atomicity/idempotence, path/ZIP abuse, scanner lifecycle, binder prices/identities,
private placements, migrations, entitlement overrides and consent separation.

Still required before release: final Xiaomi camera/queue/thermal behavior and full user
journeys, two-device authenticated sync/reconnect/conflicts, final hosted Google sign-in
and ScanLab verification, independent review of high-value/cross-product audit flags,
next-unused Play version verification and signed AAB validation. No emulator or synthetic
fixture result substitutes for those physical/production checks.

The original dirty source work was backed up before changes and retained. Local marketing
captures and `vlc-help.txt` remain on disk, now explicitly ignored to keep private generated
material out of source commits. No app data was cleared or uninstalled on the Xiaomi.
