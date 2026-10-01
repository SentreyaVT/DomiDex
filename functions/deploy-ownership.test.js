"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const childProcess = require("node:child_process");

const root = path.resolve(__dirname, "..");
const guard = path.join(root, "scripts/deny-android-functions-deploy.cjs");
const config = JSON.parse(fs.readFileSync(path.join(root, "firebase.json"), "utf8"));
const expectedCommand = 'node "$PROJECT_DIR/scripts/deny-android-functions-deploy.cjs"';
const canonical = "DomiDex-Web/backend/scan-knowledge";

function childEnvironment() {
  const env = { PATH: path.dirname(process.execPath), NODE_ENV: "test" };
  for (const key of ["SystemRoot", "WINDIR", "COMSPEC", "PATHEXT"]) {
    if (process.env[key]) env[key] = process.env[key];
  }
  return env;
}

test("only the Android functions target has a mandatory non-bypassable predeploy guard", () => {
  assert.deepEqual(config.firestore, { rules: "firestore.rules" });
  assert.deepEqual(config.storage, { rules: "storage.rules" });
  assert.equal(config.functions.length, 1);
  assert.deepEqual(config.functions[0], {
    source: "functions", codebase: "default", ignore: ["node_modules", ".git", "firebase-debug.log"],
    predeploy: [expectedCommand],
  });
});

for (const cwd of [root, os.tmpdir()]) {
  test(`guard rejects deployment without reading credentials, including cwd ${cwd === root ? "project" : "outside project"}`, () => {
    const result = childProcess.spawnSync(process.execPath, [guard, "--force", "--allow"], {
      cwd, env: { ...childEnvironment(), DOMIDEX_ALLOW_FUNCTIONS_DEPLOY: "true" },
      encoding: "utf8", windowsHide: true, timeout: 5_000,
    });
    assert.ifError(result.error);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /ANDROID_FUNCTIONS_DEPLOY_DISABLED/);
    assert.ok(result.stderr.includes(canonical));
    assert.equal(result.stdout, "");
  });
}

test("local ownership contract retains the historical export without requiring a sibling checkout", () => {
  assert.match(fs.readFileSync(path.join(root, "functions/index.js"), "utf8"), /exports\.aggregateScanKnowledge\s*=\s*onDocumentWritten/);
  assert.ok(fs.readFileSync(path.join(root, "docs/WEBAPP_RELEASE_27_HANDOFF.md"), "utf8")
    .includes(`Canonical Functions source: \`${canonical}\``));
});
