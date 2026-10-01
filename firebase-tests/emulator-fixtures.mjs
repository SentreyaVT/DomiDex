import assert from 'node:assert/strict';
import { deleteObject, listAll, ref } from 'firebase/storage';

// rules-unit-testing 5.0.2 clearStorage() only deletes root items, not prefixes.
// Recursively clear synthetic nested objects, strictly on the local demo emulator.
export async function clearLocalStorage(environment) {
  assert.match(process.env.GCLOUD_PROJECT || '', /^demo-/);
  assert.match(process.env.FIREBASE_STORAGE_EMULATOR_HOST || '', /^(127\.0\.0\.1|localhost):\d+$/);
  await environment.withSecurityRulesDisabled(async context => {
    async function clearPrefix(prefix) {
      const result = await listAll(prefix);
      await Promise.all(result.prefixes.map(clearPrefix));
      await Promise.all(result.items.map(item => deleteObject(item)));
    }
    await clearPrefix(ref(context.storage(), ''));
  });
}
