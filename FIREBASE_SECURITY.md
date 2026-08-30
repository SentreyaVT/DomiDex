# Firebase security foundation (not deployed)

These local artifacts are default-deny only. They do not create Firebase services, a Storage bucket, Cloud Functions, billing, an upload endpoint, or a live backend. The Android app performs no upload.

## Community image submissions

- An authenticated user may create one constrained metadata document in `PENDING_UPLOAD`, then upload one private JPEG/PNG (maximum 8 MiB) to its matching path.
- Clients cannot approve, publish, overwrite, delete, list submissions, or make an image public. They may only request withdrawal from a pending state.
- A Cloud Function/Admin SDK service must re-check ownership/state, validate bytes and dimensions, strip/reject sensitive metadata, run malware/safety checks and rate limits, move objects into private review, moderate publication, and perform retention/deletion on withdrawal. Admin SDK bypasses rules, so these checks remain mandatory.

## Owner support

`supportRequests` permits only small requester metadata. There is no rule path for remote control, camera/screen streaming, raw diagnostics, or client-side privileged access. A future owner operation must run server-side and verify both a Firebase custom claim (for example `ownerSupport == true`) and a server-managed allowlist. Claims are issued/revoked only by authorized backend code; no email or role is hard-coded in the app.

A future user-initiated support package must require confirmation and be limited to app version, curated non-sensitive logs, and an explicitly selected screenshot.

## Before activation

1. Create/configure private Firebase services, implement the Cloud Functions workflow, published privacy/contribution terms, moderation, quotas, retention/deletion and withdrawal.
2. Implement server-side owner authorization and a secure support endpoint.
3. Test the rules with Firebase Emulator Suite: cross-user access, status-forging, overwrite/delete, oversized/non-image, public read and withdrawal.

After those owner-run prerequisites and selecting the correct project, the one deploy command is:

```sh
firebase deploy --only firestore:rules,storage
```

This change does not run it.
