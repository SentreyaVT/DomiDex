import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, test } from 'node:test';
import { assertFails, assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { collection, deleteDoc, doc, getDocFromServer, getDocs, runTransaction, serverTimestamp, setDoc, writeBatch } from 'firebase/firestore';
import { deleteObject, getDownloadURL, getMetadata, listAll, ref, updateMetadata, uploadBytes } from 'firebase/storage';
import { clearLocalStorage } from './emulator-fixtures.mjs';

// Synthetic identities and fixtures only. This suite must never contact a real project.
const projectId = 'demo-domidex-firestore';
const timestamp = 1_788_220_800_000;
const entryId = 'entry-A';
const binderId = 'binder-A';
const placementId = 'placement-A';
const fingerprint = 'a'.repeat(64);
const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xd9]);
const imageMetadata = { contentType: 'image/jpeg' };
let environment;

function entry(ownerUid = 'A', overrides = {}) {
  return {
    syncId: entryId, ownerUid, revision: 1, lastMutationId: 'entry-mutation-1',
    updatedAt: serverTimestamp(), updatedAtClient: timestamp, deleted: false, deletedAtClient: null,
    name: 'Synthetic card', setId: 'sv-test', setName: 'Synthetic set', collectorNumber: '001/100',
    language: 'GERMAN', finish: 'Normal', variant: 'Standard', rarity: 'UNKNOWN', quantity: 1,
    condition: 'Nicht geprüft', notes: '', acquisitionPriceMinor: null, acquisitionCurrency: null,
    acquisitionDateEpochDay: null, externalProductSource: 'CARDMARKET_PUBLIC_DOWNLOAD', externalProductId: 733596,
    imageUrl: null, effectOverride: null, sourceSetId: 'sv-test', sourceCardId: 'sv-test-001',
    binderId: 'set-test', binderSetKey: 'test', binderName: 'Synthetic set', binderHeroAsset: null,
    ...overrides,
  };
}

function binder(ownerUid = 'A', overrides = {}) {
  return {
    binderId, ownerUid, name: 'Synthetic private binder', description: '', coverStyle: 'DOMIDEX',
    minimumPageCount: 1, createdAtClient: timestamp, updatedAtClient: timestamp, lastOpenedAtClient: null,
    deleted: false, revision: 1, lastMutationId: 'binder-mutation-1', updatedAt: serverTimestamp(), ...overrides,
  };
}

function placement(ownerUid = 'A', overrides = {}) {
  return {
    placementId, ownerUid, binderId, entrySyncId: entryId, slotPosition: 0, holoEffectId: null, holoIntensity: 0,
    createdAtClient: timestamp, updatedAtClient: timestamp, deleted: false,
    revision: 1, lastMutationId: 'placement-mutation-1', updatedAt: serverTimestamp(), ...overrides,
  };
}

function layout(ownerUid = 'A', overrides = {}) {
  return {
    entrySyncId: entryId, ownerUid, visibleInSetBinder: true, updatedAtClient: timestamp,
    revision: 1, lastMutationId: 'layout-mutation-1', updatedAt: serverTimestamp(), ...overrides,
  };
}

function selection(ownerUid = 'A', overrides = {}) {
  return {
    schemaVersion: 1, ownerUid, entrySyncId: entryId, revision: 1, lastMutationId: 'selection-mutation-1',
    updatedAt: serverTimestamp(), updatedAtClient: timestamp, cleared: false,
    sourceSetId: 'sv-test', sourceCardId: 'sv-test-001', collectorNumber: '001/100', language: 'GERMAN',
    finish: 'Normal', variant: 'Standard', productId: 733596, metric: 'normal_avg_sell', amountMinor: 123,
    currency: 'EUR', sourceAt: timestamp - 1000, selectedAt: timestamp, ...overrides,
  };
}

function clearedSelection(ownerUid = 'A', revision = 2) {
  return selection(ownerUid, {
    revision, lastMutationId: `selection-clear-${revision}`, cleared: true,
    sourceSetId: null, sourceCardId: null, collectorNumber: null, language: null, finish: null, variant: null,
    productId: null, metric: null, amountMinor: null, currency: null, sourceAt: null, selectedAt: null,
  });
}

function evidence(overrides = {}) {
  return {
    fingerprint, confirmedCardId: 'source:sv-test:sv-test-001|normal|standard', sourceSetId: 'sv-test',
    cardNumber: '001/100', setCode: 'TEST', ocrTokens: ['synthetic', '001100'], visualFingerprint: fingerprint,
    candidateIds: ['source:sv-test:sv-test-001|normal|standard'], originalConfidence: 80, corrected: true,
    scannerVersion: 'rules-fixture', knowledgeVersion: 1, observedAtClient: timestamp, updatedAt: serverTimestamp(),
    ...overrides,
  };
}

function graph(ownerUid = 'A') {
  return [
    [`users/${ownerUid}/collectionEntries/${entryId}`, entry(ownerUid)],
    [`users/${ownerUid}/privateBinders/${binderId}`, binder(ownerUid)],
    [`users/${ownerUid}/privateBinderPlacements/${placementId}`, placement(ownerUid)],
    [`users/${ownerUid}/collectionLayouts/${entryId}`, layout(ownerUid)],
    [`users/${ownerUid}/collectionPriceSelections/${entryId}`, selection(ownerUid)],
    [`users/${ownerUid}/scanEvidence/${fingerprint}`, evidence()],
  ];
}

function nextRevision(value) {
  return {
    ...value, ...(value.revision ? { revision: value.revision + 1, lastMutationId: 'next-mutation' } : {}),
    updatedAt: serverTimestamp(),
  };
}

async function seedGraph(ownerUid = 'A') {
  await environment.withSecurityRulesDisabled(async context => {
    for (const [path, value] of graph(ownerUid)) await setDoc(doc(context.firestore(), path), value);
  });
}

async function setAccountState(value, locked = false) {
  await environment.withSecurityRulesDisabled(async context => {
    if (value !== null) await setDoc(doc(context.firestore(), 'accountAccess/A'), value);
    if (locked) await setDoc(doc(context.firestore(), 'accountDeletionLocks/A'), { jobId: 'synthetic-test-lock' });
  });
}

async function seedImage(path) {
  await environment.withSecurityRulesDisabled(async context => {
    await uploadBytes(ref(context.storage(), path), jpeg, imageMetadata);
  });
}

async function commitAtomically(db, mode, writes) {
  if (mode === 'transaction') {
    return runTransaction(db, async transaction => {
      // Read all documents first, using actual SDK transaction semantics.
      for (const [path] of writes) await transaction.get(doc(db, path));
      for (const [path, value] of writes) transaction.set(doc(db, path), value);
    });
  }
  const batch = writeBatch(db);
  for (const [path, value] of writes) batch.set(doc(db, path), value);
  return batch.commit();
}

before(async () => {
  for (const key of ['FIRESTORE_EMULATOR_HOST', 'FIREBASE_STORAGE_EMULATOR_HOST']) {
    assert.match(process.env[key] || '', /^(127\.0\.0\.1|localhost):\d+$/, `${key} must target localhost`);
  }
  assert.equal(process.env.GCLOUD_PROJECT, projectId, 'An explicit isolated demo project is required');
  environment = await initializeTestEnvironment({
    projectId,
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
    storage: { rules: readFileSync(new URL('../storage.rules', import.meta.url), 'utf8') },
  });
});
beforeEach(async () => { await clearLocalStorage(environment); await environment.clearFirestore(); });
after(async () => { await environment?.cleanup(); });

for (const ownerUid of ['A', 'B']) {
  test(`allow: ${ownerUid} creates, reads, lists and updates its complete private graph`, async () => {
    const db = environment.authenticatedContext(ownerUid, { appAccess: true }).firestore();
    for (const [path, value] of graph(ownerUid)) {
      await assertSucceeds(setDoc(doc(db, path), value));
      const snapshot = await assertSucceeds(getDocFromServer(doc(db, path)));
      assert.equal(snapshot.exists(), true, path);
      if (!path.includes('/scanEvidence/')) {
        await assertSucceeds(getDocs(collection(db, path.slice(0, path.lastIndexOf('/')))));
      }
      await assertSucceeds(setDoc(doc(db, path), nextRevision(value)));
    }
    const storage = environment.authenticatedContext(ownerUid, { appAccess: true }).storage();
    const image = ref(storage, `users/${ownerUid}/existing/nested/image.jpg`);
    await assertSucceeds(uploadBytes(image, jpeg, imageMetadata));
    await assertSucceeds(getMetadata(image));
    await assertSucceeds(getDownloadURL(image));
    await assertSucceeds(listAll(ref(storage, `users/${ownerUid}/existing/nested`)));
    await assertSucceeds(uploadBytes(image, jpeg, imageMetadata));
    await assertSucceeds(deleteObject(image));
  });
}

test('allow: admin owner without appAccess retains its intentional app entitlement', async () => {
  const db = environment.authenticatedContext('A', { admin: true }).firestore();
  for (const [path, value] of graph()) await assertSucceeds(setDoc(doc(db, path), value));
  await assertSucceeds(getDocFromServer(doc(db, `users/A/collectionPriceSelections/${entryId}`)));
  const storage = environment.authenticatedContext('A', { admin: true }).storage();
  await assertSucceeds(uploadBytes(ref(storage, 'users/A/image.jpg'), jpeg, imageMetadata));
});

for (const [actorUid, targetUid] of [['A', 'B'], ['B', 'A']]) {
  for (const claims of [{ appAccess: true }, { admin: true }]) {
    test(`deny: ${actorUid} cannot read/write/list/delete ${targetUid} private data with ${Object.keys(claims)[0]}`, async () => {
      await seedGraph(targetUid);
      const context = environment.authenticatedContext(actorUid, claims);
      const db = context.firestore();
      for (const [path, value] of graph(targetUid)) {
        await assertFails(getDocFromServer(doc(db, path)));
        await assertFails(getDocs(collection(db, path.slice(0, path.lastIndexOf('/')))));
        await assertFails(setDoc(doc(db, path), nextRevision(value)));
        await assertFails(deleteDoc(doc(db, path)));
      }
      const imagePath = `users/${targetUid}/private/image.jpg`;
      await seedImage(imagePath);
      const image = ref(context.storage(), imagePath);
      await assertFails(getMetadata(image));
      await assertFails(getDownloadURL(image));
      await assertFails(listAll(ref(context.storage(), `users/${targetUid}/private`)));
      await assertFails(uploadBytes(image, jpeg, imageMetadata));
      await assertFails(deleteObject(image));
    });
  }
}

const blockedAccounts = [
  { name: 'anonymous', claims: null, access: null },
  { name: 'no appAccess', claims: {}, access: null },
  { name: 'pretester alone', claims: { pretester: true }, access: null },
  { name: 'wrong-type appAccess', claims: { appAccess: 'true' }, access: null },
  { name: 'revoked appAccess with old token', claims: { appAccess: true }, access: { appAccess: false } },
  { name: 'disabled owner with old token', claims: { appAccess: true }, access: { disabled: true } },
  { name: 'disabled admin', claims: { admin: true, appAccess: true }, access: { disabled: true } },
  { name: 'owner under deletion lock', claims: { appAccess: true }, access: null, locked: true },
  { name: 'admin under deletion lock', claims: { admin: true, appAccess: true }, access: null, locked: true },
];

for (const scenario of blockedAccounts) {
  test(`deny: ${scenario.name} cannot access any private Firestore family or private Storage image`, async () => {
    await seedGraph();
    await seedImage('users/A/private/image.jpg');
    await setAccountState(scenario.access, scenario.locked);
    const context = scenario.claims === null ? environment.unauthenticatedContext() : environment.authenticatedContext('A', scenario.claims);
    const db = context.firestore();
    for (const [path, value] of graph()) {
      await assertFails(getDocFromServer(doc(db, path)));
      await assertFails(getDocs(collection(db, path.slice(0, path.lastIndexOf('/')))));
      await assertFails(setDoc(doc(db, path), nextRevision(value)));
      await assertFails(deleteDoc(doc(db, path)));
    }
    await assertFails(setDoc(doc(db, 'users/A/collectionEntries/new-entry'), entry('A', { syncId: 'new-entry' })));
    const image = ref(context.storage(), 'users/A/private/image.jpg');
    await assertFails(getMetadata(image));
    await assertFails(getDownloadURL(image));
    await assertFails(listAll(ref(context.storage(), 'users/A/private')));
    await assertFails(uploadBytes(image, jpeg, imageMetadata));
    await assertFails(deleteObject(image));
    await assertFails(uploadBytes(ref(context.storage(), 'users/A/new-image.jpg'), jpeg, imageMetadata));
  });
}

test('allow/deny: blocked owner can get its own account status but cannot elevate, list or read another status', async () => {
  await setAccountState({ disabled: true, appAccess: false }, true);
  const db = environment.authenticatedContext('A', { appAccess: true }).firestore();
  await assertSucceeds(getDocFromServer(doc(db, 'accountAccess/A')));
  await assertFails(getDocFromServer(doc(db, 'accountAccess/B')));
  await assertFails(getDocs(collection(db, 'accountAccess')));
  await assertFails(setDoc(doc(db, 'accountAccess/A'), { disabled: false, appAccess: true, admin: true }));
  await assertFails(deleteDoc(doc(db, 'accountAccess/A')));
  await assertFails(deleteDoc(doc(db, 'accountDeletionLocks/A')));
});

const badSelections = [
  ['foreign owner', { ownerUid: 'B' }], ['wrong entry', { entrySyncId: 'another-entry' }],
  ['unknown field', { admin: true }], ['wrong schema', { schemaVersion: 2 }],
  ['wrong set', { sourceSetId: 'other-set' }], ['wrong card', { sourceCardId: 'sv-test-002' }],
  ['wrong number', { collectorNumber: '002/100' }], ['wrong language', { language: 'ENGLISH' }],
  ['wrong finish', { finish: 'Holo' }], ['wrong variant', { variant: 'First Edition' }],
  ['wrong product', { productId: 733597 }], ['product string', { productId: '733596' }],
  ['reverse metric for normal', { metric: 'reverse_holo_low' }], ['unknown metric', { metric: 'user_price' }],
  ['wrong currency', { currency: 'USD' }], ['zero amount', { amountMinor: 0 }],
  ['negative amount', { amountMinor: -1 }], ['fractional amount', { amountMinor: 1.5 }],
  ['string amount', { amountMinor: '123' }], ['unsafe amount', { amountMinor: 9_007_199_254_740_992 }],
  ['source time zero', { sourceAt: 0 }], ['source time string', { sourceAt: '1' }],
  ['selected time unsafe', { selectedAt: 9_007_199_254_740_992 }], ['client time zero', { updatedAtClient: 0 }],
  ['client time unsafe', { updatedAtClient: 9_007_199_254_740_992 }],
  ['revision skip', { revision: 3 }], ['revision fractional', { revision: 1.5 }],
  ['wrong server timestamp', { updatedAt: new Date(0) }], ['empty mutation', { lastMutationId: '' }],
  ['oversized mutation', { lastMutationId: 'x'.repeat(129) }], ['mutation path separator', { lastMutationId: '../x' }],
  ['oversized set', { sourceSetId: 'x'.repeat(121) }], ['oversized card', { sourceCardId: 'x'.repeat(121) }],
  ['oversized collector number', { collectorNumber: 'x'.repeat(81) }], ['oversized language', { language: 'A'.repeat(31) }],
  ['oversized finish', { finish: 'x'.repeat(81) }], ['oversized variant', { variant: 'x'.repeat(81) }],
  ['cleared with retained payload', { cleared: true }], ['cleared string', { cleared: 'false' }],
];

test('deny: price-selection field types, identity, metrics, limits and exact schema reject manipulated payloads', async () => {
  const db = environment.authenticatedContext('A', { appAccess: true }).firestore();
  await assertSucceeds(setDoc(doc(db, `users/A/collectionEntries/${entryId}`), entry()));
  const target = doc(db, `users/A/collectionPriceSelections/${entryId}`);
  await assertSucceeds(setDoc(target, selection()));
  for (const [name, change] of badSelections) {
    await assertFails(setDoc(target, selection('A', { revision: 2, lastMutationId: 'selection-2', ...change })))
      .catch(error => { error.message = `${name}: ${error.message}`; throw error; });
  }
  for (const key of Object.keys(selection())) {
    const incomplete = selection('A', { revision: 2, lastMutationId: 'selection-2' });
    delete incomplete[key];
    await assertFails(setDoc(target, incomplete)).catch(error => { error.message = `missing ${key}: ${error.message}`; throw error; });
  }
  assert.equal((await getDocFromServer(target)).data().revision, 1);
});

test('allow/deny: normal and reverse metrics stay printing-specific; clearing is revisioned and reversible while entry is alive', async () => {
  const db = environment.authenticatedContext('A', { appAccess: true }).firestore();
  const entryRef = doc(db, `users/A/collectionEntries/${entryId}`);
  const target = doc(db, `users/A/collectionPriceSelections/${entryId}`);
  await assertSucceeds(setDoc(entryRef, entry('A', { finish: 'Reverse Holo' })));
  await assertFails(setDoc(target, selection('A', { finish: 'Reverse Holo', metric: 'normal_avg_sell' })));
  await assertSucceeds(setDoc(target, selection('A', { finish: 'Reverse Holo', metric: 'reverse_holo_avg_sell' })));
  await assertSucceeds(setDoc(target, clearedSelection()));
  await assertSucceeds(setDoc(target, selection('A', { revision: 3, lastMutationId: 'selection-3', finish: 'Reverse Holo', metric: 'reverse_holo_low' })));
  await assertFails(deleteDoc(target));
});

test('deny: active or cleared price selection cannot be created without an existing own parent', async () => {
  const db = environment.authenticatedContext('A', { appAccess: true }).firestore();
  const target = doc(db, `users/A/collectionPriceSelections/${entryId}`);
  await assertFails(setDoc(target, selection()));
  await assertFails(setDoc(target, clearedSelection('A', 1)));
  await environment.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(), `users/B/collectionEntries/${entryId}`), entry('B'));
  });
  await assertFails(setDoc(target, selection()));
});

test('deny: price selection requires a Cardmarket parent and cannot reactivate after parent tombstone', async () => {
  const db = environment.authenticatedContext('A', { appAccess: true }).firestore();
  const entryRef = doc(db, `users/A/collectionEntries/${entryId}`);
  const target = doc(db, `users/A/collectionPriceSelections/${entryId}`);
  await assertSucceeds(setDoc(entryRef, entry('A', { externalProductSource: null, externalProductId: null })));
  await assertFails(setDoc(target, selection()));
  await assertSucceeds(setDoc(entryRef, entry('A', { revision: 2, lastMutationId: 'entry-2' })));
  await assertSucceeds(setDoc(target, selection()));
  await assertSucceeds(setDoc(entryRef, entry('A', { revision: 3, lastMutationId: 'entry-3', deleted: true, deletedAtClient: timestamp })));
  await assertFails(setDoc(target, selection('A', { revision: 2, lastMutationId: 'selection-2' })));
  await assertSucceeds(setDoc(target, clearedSelection()));
  await assertFails(setDoc(target, selection('A', { revision: 3, lastMutationId: 'selection-3' })));
  await assertFails(setDoc(entryRef, entry('A', { revision: 4, lastMutationId: 'entry-4' })));
});

test('allow/deny: two independent same-UID clients synchronize price choices without legacy collection-only writes clearing them', async () => {
  const first = environment.authenticatedContext('A', { appAccess: true }).firestore();
  const second = environment.authenticatedContext('A', { appAccess: true }).firestore();
  assert.notEqual(first, second, 'The test requires independent SDK instances');
  const entryPath = `users/A/collectionEntries/${entryId}`;
  const pricePath = `users/A/collectionPriceSelections/${entryId}`;
  await assertSucceeds(setDoc(doc(first, entryPath), entry()));
  await assertSucceeds(setDoc(doc(first, pricePath), selection()));
  assert.equal((await getDocFromServer(doc(second, pricePath))).data().amountMinor, 123);
  await assertSucceeds(setDoc(doc(second, pricePath), selection('A', { revision: 2, lastMutationId: 'client-two-2', metric: 'normal_low', amountMinor: 90 })));
  const secondChoice = (await getDocFromServer(doc(first, pricePath))).data();
  assert.equal(secondChoice.revision, 2);
  assert.equal(secondChoice.metric, 'normal_low');
  // A legacy client knows only the unchanged collection-entry wire schema.
  await assertSucceeds(setDoc(doc(first, entryPath), entry('A', { revision: 2, lastMutationId: 'legacy-collection-2', quantity: 2 })));
  assert.equal((await getDocFromServer(doc(second, entryPath))).data().quantity, 2);
  assert.deepEqual((await getDocFromServer(doc(second, pricePath))).data(), secondChoice);
  await assertFails(setDoc(doc(first, pricePath), selection('A', { revision: 2, lastMutationId: 'stale-client-one-2' })));
  await assertSucceeds(setDoc(doc(second, pricePath), clearedSelection('A', 3)));
  assert.equal((await getDocFromServer(doc(first, pricePath))).data().cleared, true);
  await assertFails(setDoc(doc(first, pricePath), selection('A', { revision: 2, lastMutationId: 'stale-before-clear-2' })));
});

for (const [family, factory, key] of [
  ['collectionEntries', entry, entryId], ['privateBinders', binder, binderId], ['privateBinderPlacements', placement, placementId],
]) {
  test(`deny: ${family} tombstone remains readable for sync but cannot be deleted or revived`, async () => {
    await seedGraph();
    const db = environment.authenticatedContext('A', { appAccess: true }).firestore();
    const target = doc(db, `users/A/${family}/${key}`);
    const tombstone = factory('A', { revision: 2, lastMutationId: 'tombstone-2', deleted: true, ...(family === 'collectionEntries' ? { deletedAtClient: timestamp } : {}) });
    await assertSucceeds(setDoc(target, tombstone));
    assert.equal((await getDocFromServer(target)).data().deleted, true);
    await assertFails(deleteDoc(target));
    await assertFails(setDoc(target, factory('A', { revision: 3, lastMutationId: 'revive-3' })));
  });
}

for (const parentFamily of ['collectionEntries', 'privateBinders']) {
  test(`lifecycle deny: active placement cannot change after ${parentFamily} parent is tombstoned`, async () => {
    await seedGraph();
    const db = environment.authenticatedContext('A', { appAccess: true }).firestore();
    const factory = parentFamily === 'collectionEntries' ? entry : binder;
    const key = parentFamily === 'collectionEntries' ? entryId : binderId;
    await assertSucceeds(setDoc(doc(db, `users/A/${parentFamily}/${key}`), factory('A', {
      revision: 2, lastMutationId: 'parent-deleted-2', deleted: true, ...(parentFamily === 'collectionEntries' ? { deletedAtClient: timestamp } : {}),
    })));
    await assertFails(setDoc(doc(db, `users/A/privateBinderPlacements/${placementId}`), placement('A', {
      revision: 2, lastMutationId: 'active-orphan-2', slotPosition: 8,
    })));
  });
}

test('lifecycle allow: placement tombstone cleanup works after its parent is deleted', async () => {
  await seedGraph();
  const db = environment.authenticatedContext('A', { appAccess: true }).firestore();
  await assertSucceeds(setDoc(doc(db, `users/A/collectionEntries/${entryId}`), entry('A', {
    revision: 2, lastMutationId: 'parent-deleted-2', deleted: true, deletedAtClient: timestamp,
  })));
  await assertSucceeds(setDoc(doc(db, `users/A/privateBinderPlacements/${placementId}`), placement('A', {
    revision: 2, lastMutationId: 'cleanup-2', deleted: true,
  })));
});

test('lifecycle deny: deleted collection entry cannot receive a newly active layout update', async () => {
  await seedGraph();
  const db = environment.authenticatedContext('A', { appAccess: true }).firestore();
  await assertSucceeds(setDoc(doc(db, `users/A/collectionEntries/${entryId}`), entry('A', {
    revision: 2, lastMutationId: 'parent-deleted-2', deleted: true, deletedAtClient: timestamp,
  })));
  await assertFails(setDoc(doc(db, `users/A/collectionLayouts/${entryId}`), layout('A', {
    revision: 2, lastMutationId: 'orphan-layout-2', visibleInSetBinder: true,
  })));
});

for (const mode of ['batch', 'transaction']) {
  for (const parentFamily of ['collectionEntries', 'privateBinders']) {
    for (const operation of ['create', 'update']) {
      test(`atomic deny: ${mode} ${parentFamily} tombstone plus active placement ${operation}`, async () => {
        await seedGraph();
        const db = environment.authenticatedContext('A', { appAccess: true }).firestore();
        const factory = parentFamily === 'collectionEntries' ? entry : binder;
        const key = parentFamily === 'collectionEntries' ? entryId : binderId;
        const targetId = operation === 'create' ? 'new-placement' : placementId;
        await assertFails(commitAtomically(db, mode, [
          [`users/A/${parentFamily}/${key}`, factory('A', {
            revision: 2, lastMutationId: 'atomic-parent-delete', deleted: true,
            ...(parentFamily === 'collectionEntries' ? { deletedAtClient: timestamp } : {}),
          })],
          [`users/A/privateBinderPlacements/${targetId}`, placement('A', {
            placementId: targetId, revision: operation === 'create' ? 1 : 2,
            lastMutationId: 'atomic-active-placement', slotPosition: 7,
          })],
        ]));
        assert.equal((await getDocFromServer(doc(db, `users/A/${parentFamily}/${key}`))).data().deleted, false);
      });
    }
  }

  for (const family of ['collectionPriceSelections', 'collectionLayouts']) {
    test(`atomic deny: ${mode} entry tombstone plus active ${family}`, async () => {
      await seedGraph();
      const db = environment.authenticatedContext('A', { appAccess: true }).firestore();
      const factory = family === 'collectionLayouts' ? layout : selection;
      await assertFails(commitAtomically(db, mode, [
        [`users/A/collectionEntries/${entryId}`, entry('A', { revision: 2, lastMutationId: 'atomic-delete', deleted: true, deletedAtClient: timestamp })],
        [`users/A/${family}/${entryId}`, factory('A', { revision: 2, lastMutationId: 'atomic-active' })],
      ]));
      assert.equal((await getDocFromServer(doc(db, `users/A/collectionEntries/${entryId}`))).data().deleted, false);
    });
  }

  for (const matching of [false, true]) {
    test(`atomic ${matching ? 'allow' : 'deny'}: ${mode} new parent identity with ${matching ? 'matching' : 'old'} price selection`, async () => {
      await seedGraph();
      const db = environment.authenticatedContext('A', { appAccess: true }).firestore();
      const identity = { sourceCardId: 'sv-test-002', collectorNumber: '002/100' };
      const result = commitAtomically(db, mode, [
        [`users/A/collectionEntries/${entryId}`, entry('A', { ...identity, externalProductId: 733597, revision: 2, lastMutationId: 'atomic-new-identity' })],
        [`users/A/collectionPriceSelections/${entryId}`, selection('A', {
          ...(matching ? { ...identity, productId: 733597 } : {}), revision: 2, lastMutationId: 'atomic-selection-identity',
        })],
      ]);
      await (matching ? assertSucceeds(result) : assertFails(result));
      const parent = (await getDocFromServer(doc(db, `users/A/collectionEntries/${entryId}`))).data();
      const price = (await getDocFromServer(doc(db, `users/A/collectionPriceSelections/${entryId}`))).data();
      assert.equal(parent.sourceCardId, price.sourceCardId);
      assert.equal(parent.externalProductId, price.productId);
    });
  }

  test(`atomic allow: ${mode} parent deletion and cleanup of placement, layout and price selection`, async () => {
    await seedGraph();
    const db = environment.authenticatedContext('A', { appAccess: true }).firestore();
    await assertSucceeds(commitAtomically(db, mode, [
      [`users/A/collectionEntries/${entryId}`, entry('A', { revision: 2, lastMutationId: 'cleanup-parent', deleted: true, deletedAtClient: timestamp })],
      [`users/A/privateBinderPlacements/${placementId}`, placement('A', { revision: 2, lastMutationId: 'cleanup-placement', deleted: true })],
      [`users/A/collectionLayouts/${entryId}`, layout('A', { revision: 2, lastMutationId: 'cleanup-layout', visibleInSetBinder: false })],
      [`users/A/collectionPriceSelections/${entryId}`, clearedSelection()],
    ]));
    assert.equal((await getDocFromServer(doc(db, `users/A/collectionPriceSelections/${entryId}`))).data().cleared, true);
  });

  test(`atomic allow: ${mode} creates a consistent new parent graph and price choice together`, async () => {
    const db = environment.authenticatedContext('A', { appAccess: true }).firestore();
    await assertSucceeds(commitAtomically(db, mode, graph().filter(([path]) => !path.includes('/scanEvidence/'))));
    assert.equal((await getDocFromServer(doc(db, `users/A/collectionPriceSelections/${entryId}`))).data().amountMinor, 123);
  });
}

for (const actor of [null, { appAccess: true }, { admin: true }]) {
  const role = actor === null ? 'anonymous' : Object.keys(actor)[0];
  test(`deny: unknown Firestore/Storage roots and unknown Firestore user subcollections stay closed to ${role}`, async () => {
    const context = actor === null ? environment.unauthenticatedContext() : environment.authenticatedContext('A', actor);
    const db = context.firestore();
    for (const path of ['notDefined/example', 'users/A/notDefined/example', 'users/B/notDefined/example']) {
      await environment.withSecurityRulesDisabled(async admin => { await setDoc(doc(admin.firestore(), path), { test: true }); });
      await assertFails(getDocFromServer(doc(db, path)));
      await assertFails(getDocs(collection(db, path.slice(0, path.lastIndexOf('/')))));
      await assertFails(setDoc(doc(db, path), { test: false }));
      await assertFails(deleteDoc(doc(db, path)));
    }
    const path = 'not-defined/image.jpg';
    await seedImage(path);
    const image = ref(context.storage(), path);
    await assertFails(getMetadata(image));
    await assertFails(getDownloadURL(image));
    await assertFails(listAll(ref(context.storage(), 'not-defined')));
    await assertFails(uploadBytes(image, jpeg, imageMetadata));
    await assertFails(deleteObject(image));
  });
}

test('allow/deny: global knowledge is individually readable by app users but never client-publishable or listable', async () => {
  const path = `scanKnowledge/${fingerprint}`;
  await environment.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(), path), { fingerprint, status: 'STABLE', cardId: 'synthetic-card' });
  });
  for (const claims of [{ appAccess: true }, { admin: true }]) {
    const db = environment.authenticatedContext('A', claims).firestore();
    await assertSucceeds(getDocFromServer(doc(db, path)));
    await assertFails(getDocs(collection(db, 'scanKnowledge')));
    await assertFails(setDoc(doc(db, path), { fingerprint, status: 'STABLE', cardId: 'forged' }));
    await assertFails(setDoc(doc(db, `scanKnowledge/${'b'.repeat(64)}`), { status: 'STABLE' }));
    await assertFails(deleteDoc(doc(db, path)));
  }
  for (const state of [{ disabled: true }, { appAccess: false }]) {
    await setAccountState(state);
    const db = environment.authenticatedContext('A', { appAccess: true }).firestore();
    await assertFails(getDocFromServer(doc(db, path)));
  }
  await setAccountState({ appAccess: true }, true);
  await assertFails(getDocFromServer(doc(environment.authenticatedContext('A', { appAccess: true }).firestore(), path)));
  await assertFails(getDocFromServer(doc(environment.unauthenticatedContext().firestore(), path)));
});

for (const claims of [null, { appAccess: true }, { pretester: true }, { admin: true }]) {
  const role = claims === null ? 'anonymous' : Object.keys(claims)[0];
  test(`deny: direct scanner-report images remain inaccessible to ${role}, including metadata/download tokens`, async () => {
    const path = 'scanner-report-images/synthetic-report/capture.jpg';
    await seedImage(path);
    const context = claims === null ? environment.unauthenticatedContext() : environment.authenticatedContext('A', claims);
    const image = ref(context.storage(), path);
    await assertFails(getMetadata(image));
    await assertFails(getDownloadURL(image));
    await assertFails(listAll(ref(context.storage(), 'scanner-report-images/synthetic-report')));
    await assertFails(uploadBytes(image, jpeg, imageMetadata));
    await assertFails(deleteObject(image));
  });
}

test('allow/deny: catalog image contributions retain private read and owner withdrawal', async () => {
  const owner = environment.authenticatedContext('A', { appAccess: true }).storage();
  const other = environment.authenticatedContext('B', { appAccess: true }).storage();
  const path = `catalog-contributions/A/${fingerprint}/card.jpg`;
  const metadata = { ...imageMetadata, customMetadata: { ownerUid: 'A', reviewStatus: 'PENDING_REVIEW', contentSha256: fingerprint } };
  await assertSucceeds(uploadBytes(ref(owner, path), jpeg, metadata));
  await assertSucceeds(getMetadata(ref(owner, path)));
  await assertFails(updateMetadata(ref(owner, path), { customMetadata: { reviewStatus: 'APPROVED' } }));
  await assertFails(getMetadata(ref(other, path)));
  await assertFails(getDownloadURL(ref(other, path)));
  await assertFails(deleteObject(ref(other, path)));
  await assertSucceeds(deleteObject(ref(owner, path)));
});

for (const changedBytes of [false, true]) {
  test(`deny: catalog create-once rejects ${changedBytes ? 'different-byte' : 'same-byte'} repeated upload`, async () => {
    const owner = environment.authenticatedContext('A', { appAccess: true }).storage();
    const target = ref(owner, `catalog-contributions/A/${fingerprint}/card.jpg`);
    const metadata = { ...imageMetadata, customMetadata: { ownerUid: 'A', reviewStatus: 'PENDING_REVIEW', contentSha256: fingerprint } };
    await assertSucceeds(uploadBytes(target, jpeg, metadata));
    await assertFails(uploadBytes(target, changedBytes ? new Uint8Array([0xff, 0xd8, 0x01, 0x02, 0xff, 0xd9]) : jpeg, metadata));
    assert.equal((await getMetadata(target)).size, jpeg.length);
  });
}

test('allow/deny: community image create is tied to pending owner metadata; mutation and foreign access stay blocked', async () => {
  const owner = environment.authenticatedContext('A', { appAccess: true });
  const foreign = environment.authenticatedContext('B', { appAccess: true });
  const path = 'community-submissions/A/synthetic-submission/front.jpg';
  const target = ref(owner.storage(), path);
  await assertFails(uploadBytes(target, jpeg, imageMetadata));
  await assertSucceeds(setDoc(doc(owner.firestore(), 'communitySubmissions/synthetic-submission'), {
    submitterUid: 'A', status: 'PENDING_UPLOAD', createdAt: serverTimestamp(), appVersion: 'test', imagePath: path, cardHint: 'Synthetic',
  }));
  await assertFails(uploadBytes(ref(foreign.storage(), path), jpeg, imageMetadata));
  await assertSucceeds(uploadBytes(target, jpeg, imageMetadata));
  await assertSucceeds(getMetadata(target));
  await assertFails(getMetadata(ref(foreign.storage(), path)));
  await assertFails(updateMetadata(target, { customMetadata: { reviewStatus: 'APPROVED' } }));
  await assertFails(deleteObject(target));
});

test('deny: community create-once rejects different bytes while metadata remains pending', async () => {
  const owner = environment.authenticatedContext('A', { appAccess: true });
  const path = 'community-submissions/A/synthetic-submission/front.jpg';
  await assertSucceeds(setDoc(doc(owner.firestore(), 'communitySubmissions/synthetic-submission'), {
    submitterUid: 'A', status: 'PENDING_UPLOAD', createdAt: serverTimestamp(), appVersion: 'test', imagePath: path, cardHint: 'Synthetic',
  }));
  const target = ref(owner.storage(), path);
  await assertSucceeds(uploadBytes(target, jpeg, imageMetadata));
  await assertFails(uploadBytes(target, new Uint8Array([0xff, 0xd8, 0x01, 0x02, 0xff, 0xd9]), imageMetadata));
  assert.equal((await getMetadata(target)).size, jpeg.length);
});
