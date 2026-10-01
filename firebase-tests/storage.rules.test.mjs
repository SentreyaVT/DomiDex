import { readFileSync } from "node:fs";
import { after, before, beforeEach, test } from "node:test";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { ref, uploadBytes } from "firebase/storage";
import { clearLocalStorage } from "./emulator-fixtures.mjs";

let environment;
const hash = "a".repeat(64);
const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xd9]);
const contributionMetadata = {
  contentType: "image/jpeg",
  customMetadata: { ownerUid: "A", reviewStatus: "PENDING_REVIEW", contentSha256: hash, appVersion: "test" },
};

before(async () => {
  environment = await initializeTestEnvironment({
    projectId: process.env.GCLOUD_PROJECT || "demo-domidex-storage",
    firestore: { rules: readFileSync(new URL("../firestore.rules", import.meta.url), "utf8") },
    storage: { rules: readFileSync(new URL("../storage.rules", import.meta.url), "utf8") },
  });
});
beforeEach(async () => { await clearLocalStorage(environment); await environment.clearFirestore(); });
after(async () => environment.cleanup());

test("A: unauthenticated upload is denied", async () => {
  const storage = environment.unauthenticatedContext().storage();
  await assertFails(uploadBytes(ref(storage, "users/A/image.jpg"), jpeg, { contentType: "image/jpeg" }));
});

test("B: user A can write an image below users/A", async () => {
  const storage = environment.authenticatedContext("A", { appAccess: true }).storage();
  await assertSucceeds(uploadBytes(ref(storage, "users/A/scans/image.jpg"), jpeg, { contentType: "image/jpeg" }));
});

test("C: user A cannot write below users/B", async () => {
  const storage = environment.authenticatedContext("A", { appAccess: true }).storage();
  await assertFails(uploadBytes(ref(storage, "users/B/image.jpg"), jpeg, { contentType: "image/jpeg" }));
});

test("D: user A can create a private catalogue contribution below A", async () => {
  const storage = environment.authenticatedContext("A", { appAccess: true }).storage();
  await assertSucceeds(uploadBytes(ref(storage, `catalog-contributions/A/${hash}/card.jpg`), jpeg, contributionMetadata));
});

test("E: arbitrary non-image MIME type is denied", async () => {
  const storage = environment.authenticatedContext("A", { appAccess: true }).storage();
  await assertFails(uploadBytes(ref(storage, "users/A/document.pdf"), jpeg, { contentType: "application/pdf" }));
});

test("F: image above 10 MiB is denied", async () => {
  const storage = environment.authenticatedContext("A", { appAccess: true }).storage();
  const oversized = new Uint8Array(10 * 1024 * 1024 + 1);
  await assertFails(uploadBytes(ref(storage, "users/A/large.jpg"), oversized, { contentType: "image/jpeg" }));
});

test("cross-user contribution reads and writes are denied", async () => {
  const storage = environment.authenticatedContext("B", { appAccess: true }).storage();
  await assertFails(uploadBytes(ref(storage, `catalog-contributions/A/${hash}/card.jpg`), jpeg, {
    ...contributionMetadata,
    customMetadata: { ...contributionMetadata.customMetadata, ownerUid: "B" },
  }));
});
