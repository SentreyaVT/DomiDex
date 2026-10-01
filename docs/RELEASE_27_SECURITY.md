# Release 27 Gate 1 — Firebase rules evidence

2026-10-01. Local emulator evidence only; **not deployed**.

## Result

**PASS: 89/89 tests, zero skips/failures, 33.338 seconds.** This includes 18
pre-existing Android rules cases, 62 new security cases and 9 historical Web
sharing-rule regressions. The historical Web cases test their pinned historical
fixture, not a separately asserted live deployment. New cases use the current
candidate rules. Prior to the fix 24 new assertions failed (65/89 passed).

Test identities, cards, images and accounts are synthetic. Both emulators run on
localhost with a `demo-` project and a network guard; no production accounts or
objects were changed. Two independent same-UID Firebase SDK instances exchange a
price choice, reject stale revisions and verify that a legacy collection-only
write does not erase the sidecar. This is **not** yet two Android devices performing
the full Room/outbox sync; that remains Gate 4.

## Minimal changes

- Price-selection identity is checked against `getAfter`/`existsAfter`, so a batch
  or transaction must match the committed parent, not the pre-write card identity.
- Active private placements validate live post-commit binder and collection-entry
  parents on update as well as create. Deleted placements remain cleanable.
- Active collection layouts validate the post-commit parent. Hiding an existing
  layout remains allowed after parent deletion for cleanup.
- The two create-only contribution-image prefixes also require `resource == null`.
  Normal Android retry checks existing metadata before uploading; no override or
  new entitlement is introduced.

All original ownership, app access, disabled-account, deletion-lock, revision,
tombstone, field/type/length validation and default-deny boundaries remain.
Both Android and Web copies are byte-identical:

| Rule file | SHA-256 |
|---|---|
| `firestore.rules` | `53B815173F59727368F85C1E1861108C0C246FE262F5FF6FBAA848000F930ACE` |
| `storage.rules` | `0C02A80E22BB667FE6EA586A7F0BC694B15A30F54607EA15A9944754F58624DC` |

## Evidence coverage

Allow and deny cases cover owners/foreign users/anonymous users, app access
revocation, disabled accounts and deletion locks across collection/binder/placement/
layout/price/evidence/image operations. Also covered: exact price schema and parent
identity, unknown paths, hard-delete/revival rejection, atomic parent changes,
global knowledge client write/list denial and direct scanner-image metadata/token
denial including client admin. Client-admin claims do not override private ownership.

The create-only overwrite case was reproduced with different bytes in the local
Storage emulator. Its upload operation is classified as CREATE even for an existing
object; explicit absence makes the intended rule robust independently of that
classification. This is **not a demonstrated live Storage exploit**.

The test SDK's `clearStorage()` left nested fixtures behind. The test-only recursive
cleanup helper validates localhost and a `demo-` project before deleting synthetic
objects. No product rule was relaxed to solve this harness problem.

## Limits and follow-up

- Rules validate price identity/shape, not whether a client-provided alternative
  amount is a currently published Cardmarket value. Public views must not trust it
  as an independently verified market quote.
- Legacy parent-only deletion/identity changes remain possible. Existing sidecars
  are not automatically deleted; readers must continue exact matching and tombstone
  filtering. No destructive cleanup/migration was introduced.
- Physical client sync, live IAM, App Check, privileged backend APIs and actual
  deployed rules are separate gates. No security completeness claim for those yet.
- Gate 4 must verify replay/cleanup after parent deletion under the stricter rules,
  so an orphaned queued edit does not retry forever.

Reproduction: start isolated Firestore/Storage emulators for `demo-domidex-firestore`,
set emulator hosts to localhost, run `npm test` inside `firebase-tests`. The separate
historical Web suite is `DomiDex-Web/tests/sharing-live-rules.test.mjs`.
Machine-readable result and before/after logs are in ignored
`artifacts/release27-engineering-20261001/security-gate1/`.

Deployment is a later explicit manual action from a verified commit pair. Reverting
these local changes is not a recommended production rollback, because it restores
the demonstrated parent-validation gaps. Prefer compatible client recovery and
forward fixes while retaining owner isolation and terminal tombstones.
