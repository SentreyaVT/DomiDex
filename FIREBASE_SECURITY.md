# Firebase security and collection sync (not deployed)

The Android app uses the existing Firebase Authentication session for private image contributions and a UID-scoped collection replica. Room remains the local/offline source read by the UI. The rules remain default-deny and have not been deployed by Codex.

## Paths

- `users/{uid}/...`: private JPEG, PNG or WebP files; only the matching Firebase UID may read/write/delete.
- `catalog-contributions/{uid}/{sha256}/card.{ext}`: private, create-once intake. The matching UID can read or withdraw it; it is never an official/public catalogue image automatically.
- `community-submissions/{uid}/{submissionId}/front.jpg`: the existing Firestore-metadata-backed contract remains intact.
- Firestore `users/{uid}/collectionEntries/{syncId}`: private collection snapshots. Only the matching UID may read/write; physical deletes are denied and use tombstones.

## Collection data safety

- Legacy rows migrate into the isolated `__domidex_guest__` namespace and are never uploaded or assigned to an account automatically.
- A separate explicit repository operation can claim guest rows after user confirmation. Source-/Cardmarket-identified rows use the same stable owner-scoped identity as normal adds (with a safe successor for occupied/tombstoned generations); unlinked rows receive fresh UUIDs. Every claimed row and outbox snapshot is committed transactionally.
- Only a Firebase user linked to `google.com` is a canonical collection owner. A GitHub-only Firebase session stays in the guest namespace; GitHub may only be linked to an already signed-in Google user for private update access and can never switch collection ownership.
- Local writes and a coalesced outbox share one Room transaction. Firestore writes use stable document IDs, mutation IDs and optimistic revisions.
- Pending/conflicting local changes are never overwritten by a remote snapshot. Deletes remain tombstones so an offline device cannot resurrect them.
- Authentication changes immediately switch the Room query namespace and the remote listener. Signing out reveals only guest data, not the previous account's collection.
- Sync work uses a separate durable WorkManager chain per owner. A wake-up queued while work is already running is appended rather than lost, and every mutation re-checks the captured canonical UID.
- Revision conflicts and terminal rule/payload errors have separate owner-scoped counters. Neither retries automatically: local-wins, fresh-server Cloud-wins, and corrected-error retry are explicit repository actions. Cloud-wins retains the guarded conflict outbox, opens the entry for listener updates, and performs a second server read before clearing that guard; it therefore cannot roll back a newer listener revision. A Cloud read/auth failure leaves the guarded conflict available for an explicit retry. Remote-delete and local-delete conflicts remain blocked from local-wins instead of silently resurrecting or deleting data.
- After every successful write ACK the durable outbox remains until a fresh server read completes. This catches a newer remote revision that arrived while the local row was still pending and also resumes safely after process death.
- Local inputs are checked against the same field/quantity limits as the rules. Oversized values are rejected with a validation error rather than silently truncated; existing legacy rows remain untouched.

Known beta boundary: the app does not yet block all edits behind an initial Firestore-hydration screen. Stable source identities turn concurrent recognised-card adds into an explicit revision conflict, but fully manual/unlinked cards created simultaneously on two offline devices can still become separate entries. They are never auto-merged or silently discarded.

## Community image submissions

- Uploads accept JPEG/PNG/WebP metadata and at most 10 MiB. The Android client additionally checks header, decoded dimensions and SHA-256 before upload.
- Deterministic SHA-256 paths and a metadata existence check prevent duplicate uploads. There is no automatic retry loop.
- Clients cannot approve, publish, overwrite, delete, list submissions, or make an image public. They may only request withdrawal from a pending state.
- A Cloud Function/Admin SDK service must re-check ownership/state, validate bytes and dimensions, strip/reject sensitive metadata, run malware/safety checks and rate limits, move objects into private review, moderate publication, and perform retention/deletion on withdrawal. Admin SDK bypasses rules, so these checks remain mandatory.

## Owner support

`supportRequests` permits only small requester metadata. There is no rule path for remote control, camera/screen streaming, raw diagnostics, or client-side privileged access. A future owner operation must run server-side and verify both a Firebase custom claim (for example `ownerSupport == true`) and a server-managed allowlist. Claims are issued/revoked only by authorized backend code; no email or role is hard-coded in the app.

A future user-initiated support package must require confirmation and be limited to app version, curated non-sensitive logs, and an explicitly selected screenshot.

## App Check

App Check is not currently installed. Enable Play Integrity for the Android app later, add the App Check SDK, test debug/development tokens, and only then enable Storage enforcement in Firebase Console. UID-based Storage Rules remain mandatory and work independently of App Check.

## Before activation

1. Publish privacy/contribution terms and define moderation, quotas and retention/deletion for private contributions.
2. Implement server-side owner authorization and a secure support endpoint.
3. Test the rules with Firebase Emulator Suite: cross-user access, status-forging, overwrite/delete, oversized/non-image, public read and withdrawal.

After those owner-run prerequisites, completing Firestore/Storage emulator tests and selecting the correct project, the deploy command is:

```sh
firebase deploy --only firestore:rules,storage
```

This change does not run it.
