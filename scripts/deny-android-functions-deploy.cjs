"use strict";

// This historical Functions copy must never replace the canonical Web backend.
// Deliberately no arguments, environment overrides, credentials or SDK imports.
process.stderr.write(
  "ANDROID_FUNCTIONS_DEPLOY_DISABLED\n" +
  "Canonical Functions source: DomiDex-Web/backend/scan-knowledge.\n" +
  "The Android functions directory is historical reference only. Nothing was deployed by this guard.\n" +
  "Review and deploy Functions exclusively from the Web repository with separate authorization.\n" +
  "Firestore/storage-only operations are not blocked by this functions predeploy guard.\n",
);
process.exitCode = 1;
