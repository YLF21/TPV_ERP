import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("passwordChangeRequired gates dashboard loading and uses the dedicated endpoint", async () => {
  const [app, api] = await Promise.all([
    readFile(new URL("../src/App.tsx", import.meta.url), "utf8"),
    readFile(new URL("../src/lib/api.ts", import.meta.url), "utf8")
  ]);

  assert.match(app, /authenticated\.passwordChangeRequired/);
  assert.match(app, /if \(pendingPasswordChange\)/);
  assert.match(app, /api\.changeOwnPassword\(pendingPasswordChange\.credentials/);
  assert.match(app, /api\.login\(\{ username: pendingPasswordChange\.credentials\.username, password: newPassword \}\)/);
  assert.match(api, /\/api\/v1\/auth\/password\/change/);
});

test("recovery remains generic and never reads or renders a token from its request response", async () => {
  const [app, api] = await Promise.all([
    readFile(new URL("../src/App.tsx", import.meta.url), "utf8"),
    readFile(new URL("../src/lib/api.ts", import.meta.url), "utf8")
  ]);

  assert.match(api, /requestPasswordRecovery\(username: string\)/);
  assert.match(api, /publicPost<void>\("\/api\/v1\/auth\/password\/recovery\/request"/);
  assert.match(api, /publicPost<void>\("\/api\/v1\/auth\/password\/recovery\/confirm"/);
  assert.match(app, /recoveryGeneric/);
  assert.doesNotMatch(app, /response\.(?:token|recoveryToken)/);
});

test("new passwords enforce the backend minimum length in both flows", async () => {
  const app = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");
  assert.ok((app.match(/newPassword\.length < 12/g) ?? []).length >= 2);
  assert.match(app, /minLength=\{12\}/);
});
