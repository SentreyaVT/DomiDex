import { readFileSync } from "node:fs";
import { after, before, beforeEach, test } from "node:test";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { deleteDoc, doc, getDoc, serverTimestamp, setDoc } from "firebase/firestore";

let environment;
const id = "11111111-1111-4111-8111-111111111111";

function entry(ownerUid, revision = 1, deleted = false) {
  return {
    syncId: id, ownerUid, revision, lastMutationId: `mutation-${revision}`,
    updatedAt: serverTimestamp(), updatedAtClient: 1_788_220_800_000,
    deleted, deletedAtClient: deleted ? 1_788_220_800_000 : null,
    name: "Mew", setId: "sv-test", setName: "Testset", collectorNumber: "001/100",
    language: "GERMAN", finish: "Normal", variant: "Standard", rarity: "UNKNOWN",
    quantity: 1, condition: "Nicht geprüft", notes: "", acquisitionPriceMinor: null,
    acquisitionCurrency: null, acquisitionDateEpochDay: null,
    externalProductSource: null, externalProductId: null, imageUrl: null,
    effectOverride: null, sourceSetId: null, sourceCardId: null,
    binderId: "set-test", binderSetKey: "test",
    binderName: "Testset", binderHeroAsset: null,
  };
}

before(async () => {
  environment = await initializeTestEnvironment({
    projectId: "demo-domidex-firestore",
    firestore: { rules: readFileSync(new URL("../firestore.rules", import.meta.url), "utf8") },
  });
});
beforeEach(async () => environment.clearFirestore());
after(async () => environment.cleanup());

test("collection entries require authentication and matching UID", async () => {
  const anonymous = environment.unauthenticatedContext().firestore();
  await assertFails(setDoc(doc(anonymous, `users/A/collectionEntries/${id}`), entry("A")));
  const userA = environment.authenticatedContext("A").firestore();
  await assertSucceeds(setDoc(doc(userA, `users/A/collectionEntries/${id}`), entry("A")));
  const userB = environment.authenticatedContext("B").firestore();
  await assertFails(getDoc(doc(userB, `users/A/collectionEntries/${id}`)));
  await assertFails(setDoc(doc(userB, `users/A/collectionEntries/${id}`), entry("B")));
});

test("revision must advance exactly once", async () => {
  const userA = environment.authenticatedContext("A").firestore();
  const reference = doc(userA, `users/A/collectionEntries/${id}`);
  await assertSucceeds(setDoc(reference, entry("A")));
  await assertFails(setDoc(reference, entry("A", 3)));
  await assertSucceeds(setDoc(reference, entry("A", 2)));
});

test("tombstones cannot be physically deleted or resurrected", async () => {
  const userA = environment.authenticatedContext("A").firestore();
  const reference = doc(userA, `users/A/collectionEntries/${id}`);
  await assertSucceeds(setDoc(reference, entry("A", 1, true)));
  await assertFails(deleteDoc(reference));
  await assertFails(setDoc(reference, entry("A", 2, false)));
});

test("source identity is optional but must be a valid complete pair", async () => {
  const userA = environment.authenticatedContext("A").firestore();
  const reference = doc(userA, `users/A/collectionEntries/${id}`);
  await assertSucceeds(setDoc(reference, {
    ...entry("A"), sourceSetId: "sv08", sourceCardId: "sv08-001-reverseholofoil",
  }));

  const anotherId = "22222222-2222-4222-8222-222222222222";
  const partial = { ...entry("A"), syncId: anotherId, sourceSetId: "sv08", sourceCardId: null };
  await assertFails(setDoc(doc(userA, `users/A/collectionEntries/${anotherId}`), partial));

  const malformedId = "33333333-3333-4333-8333-333333333333";
  const malformed = { ...entry("A"), syncId: malformedId, sourceSetId: "SV 08", sourceCardId: "001" };
  await assertFails(setDoc(doc(userA, `users/A/collectionEntries/${malformedId}`), malformed));
});
