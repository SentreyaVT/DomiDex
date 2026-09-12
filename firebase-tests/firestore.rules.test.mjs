import { readFileSync } from "node:fs";
import { after, before, beforeEach, test } from "node:test";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, deleteDoc, doc, getDoc, getDocs, serverTimestamp, setDoc } from "firebase/firestore";

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

function evidence(fingerprint) {
  return {
    fingerprint, confirmedCardId: "source:sv1:sv1-001|normal|standard",
    sourceSetId: "sv1", cardNumber: "001/198", setCode: "SV1",
    ocrTokens: ["pikachu", "001198"], visualFingerprint: "a".repeat(64),
    candidateIds: ["source:sv1:sv1-001|normal|standard"], originalConfidence: 84,
    corrected: false, scannerVersion: "scanner-v2.1", knowledgeVersion: 1,
    observedAtClient: 1_788_220_800_000, updatedAt: serverTimestamp(),
  };
}

function privateBinder(ownerUid, binderId, deleted = false, revision = 1) {
  return {
    binderId, ownerUid, name: "Tauschbinder", description: "Privat sortiert",
    coverStyle: "DOMIDEX", minimumPageCount: 1, createdAtClient: 1_788_220_800_000,
    updatedAtClient: 1_788_220_800_000, lastOpenedAtClient: null, deleted,
    revision, lastMutationId: `private-binder-mutation-${revision}`, updatedAt: serverTimestamp(),
  };
}

function privatePlacement(ownerUid, placementId, binderId, entrySyncId, deleted = false, revision = 1) {
  return {
    placementId, ownerUid, binderId, entrySyncId, slotPosition: 9,
    holoEffectId: null, holoIntensity: 0,
    createdAtClient: 1_788_220_800_000, updatedAtClient: 1_788_220_800_000,
    deleted, revision, lastMutationId: `private-placement-mutation-${revision}`, updatedAt: serverTimestamp(),
  };
}

function collectionLayout(ownerUid, entrySyncId, visibleInSetBinder = false, revision = 1) {
  return {
    entrySyncId, ownerUid, visibleInSetBinder, updatedAtClient: 1_788_220_800_000,
    revision, lastMutationId: `collection-layout-mutation-${revision}`, updatedAt: serverTimestamp(),
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

test("private binders are owner-scoped, shape constrained and use tombstones", async () => {
  const binderId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const userA = environment.authenticatedContext("A").firestore();
  const userB = environment.authenticatedContext("B").firestore();
  const reference = doc(userA, `users/A/privateBinders/${binderId}`);
  await assertSucceeds(setDoc(reference, privateBinder("A", binderId)));
  await assertFails(getDoc(doc(userB, `users/A/privateBinders/${binderId}`)));
  await assertFails(getDocs(collection(userB, "users/A/privateBinders")));
  await assertFails(setDoc(doc(userB, `users/A/privateBinders/${binderId}`), privateBinder("B", binderId)));
  await assertFails(setDoc(reference, { ...privateBinder("A", binderId), admin: true }));
  await assertFails(setDoc(reference, { ...privateBinder("A", binderId), minimumPageCount: 0 }));
  await assertSucceeds(setDoc(reference, privateBinder("A", binderId, true, 2)));
  await assertFails(setDoc(reference, privateBinder("A", binderId, false, 3)));
  await assertFails(deleteDoc(reference));
});

test("private placements require the owner's live binder and exact collection entry", async () => {
  const binderId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  const placementId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
  const userA = environment.authenticatedContext("A").firestore();
  await assertSucceeds(setDoc(doc(userA, `users/A/privateBinders/${binderId}`), privateBinder("A", binderId)));
  await assertSucceeds(setDoc(doc(userA, `users/A/collectionEntries/${id}`), entry("A")));
  const legacyPlacementId = "ffffffff-ffff-4fff-8fff-ffffffffffff";
  const legacyPlacement = privatePlacement("A", legacyPlacementId, binderId, id);
  delete legacyPlacement.holoEffectId;
  delete legacyPlacement.holoIntensity;
  await assertSucceeds(setDoc(
    doc(userA, `users/A/privateBinderPlacements/${legacyPlacementId}`),
    legacyPlacement,
  ));
  const reference = doc(userA, `users/A/privateBinderPlacements/${placementId}`);
  await assertSucceeds(setDoc(reference, privatePlacement("A", placementId, binderId, id)));
  await assertSucceeds(setDoc(reference, {
    ...privatePlacement("A", placementId, binderId, id, false, 2),
    holoEffectId: "reverse-holo", holoIntensity: 0.65,
  }));
  await assertFails(setDoc(reference, {
    ...privatePlacement("A", placementId, binderId, id, false, 3), slotPosition: -1,
  }));
  await assertFails(setDoc(reference, {
    ...privatePlacement("A", placementId, binderId, id, false, 3),
    holoEffectId: "not a stable id", holoIntensity: 0.5,
  }));
  await assertFails(setDoc(reference, {
    ...privatePlacement("A", placementId, binderId, id, false, 3),
    holoEffectId: null, holoIntensity: 0.5,
  }));

  const missingPlacementId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
  await assertFails(setDoc(
    doc(userA, `users/A/privateBinderPlacements/${missingPlacementId}`),
    privatePlacement("A", missingPlacementId, binderId, "missing-entry"),
  ));
  await assertSucceeds(setDoc(reference, privatePlacement("A", placementId, binderId, id, true, 3)));
  await assertFails(setDoc(reference, privatePlacement("A", placementId, binderId, id, false, 4)));

  const offlineDeleteId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
  await assertSucceeds(setDoc(
    doc(userA, `users/A/privateBinderPlacements/${offlineDeleteId}`),
    privatePlacement("A", offlineDeleteId, "missing-binder", "missing-entry", true),
  ));
});

test("set-binder visibility remains owner-scoped and references a real owned entry", async () => {
  const userA = environment.authenticatedContext("A").firestore();
  const userB = environment.authenticatedContext("B").firestore();
  const reference = doc(userA, `users/A/collectionLayouts/${id}`);
  await assertFails(setDoc(reference, collectionLayout("A", id)));
  await assertSucceeds(setDoc(doc(userA, `users/A/collectionEntries/${id}`), entry("A")));
  await assertSucceeds(setDoc(reference, collectionLayout("A", id)));
  await assertFails(getDoc(doc(userB, `users/A/collectionLayouts/${id}`)));
  await assertFails(setDoc(reference, { ...collectionLayout("A", id), visibleInSetBinder: "false" }));
  await assertSucceeds(setDoc(reference, collectionLayout("A", id, true, 2)));
  await assertFails(deleteDoc(reference));
});

test("scan evidence is private, idempotent and shape constrained", async () => {
  const fingerprint = "a".repeat(64);
  const userA = environment.authenticatedContext("A").firestore();
  const userB = environment.authenticatedContext("B").firestore();
  const reference = doc(userA, `users/A/scanEvidence/${fingerprint}`);
  await assertSucceeds(setDoc(reference, evidence(fingerprint)));
  await assertSucceeds(setDoc(reference, { ...evidence(fingerprint), originalConfidence: 90 }));
  await assertFails(getDoc(doc(userB, `users/A/scanEvidence/${fingerprint}`)));
  await assertFails(setDoc(doc(userA, `users/A/scanEvidence/${"b".repeat(64)}`), evidence(fingerprint)));
  await assertFails(setDoc(reference, { ...evidence(fingerprint), status: "STABLE" }));
});

test("clients can read but never publish shared stable knowledge", async () => {
  const fingerprint = "a".repeat(64);
  await environment.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(), `scanKnowledge/${fingerprint}`), {
      fingerprint, cardId: "source:sv1:sv1-001|normal|standard", status: "STABLE",
    });
  });
  const userA = environment.authenticatedContext("A").firestore();
  await assertSucceeds(getDoc(doc(userA, `scanKnowledge/${fingerprint}`)));
  await assertFails(setDoc(doc(userA, `scanKnowledge/${fingerprint}`), {
    fingerprint, cardId: "forged", status: "STABLE",
  }));
  const anonymous = environment.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(anonymous, `scanKnowledge/${fingerprint}`)));
});
