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
  assert.match(app, /api\.changeOwnPassword\(pending\.credentials/);
  assert.match(app, /api\.login\(\{ username: pending\.credentials\.username, password: newPassword \}\)/);
  assert.match(app, /const requestId = \+\+authRequestId\.current/);
  assert.match(app, /if \(!isCurrentAuthRequest\(requestId, authRequestId\.current\)\) return;/);
  assert.match(app, /disabled=\{loading\} onClick=\{onCancel\}/);
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
  assert.doesNotMatch(app, /login-recovery-actions/);
});

test("new passwords enforce the four-character backend minimum in both flows", async () => {
  const app = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");
  assert.ok((app.match(/newPassword\.length < 4/g) ?? []).length >= 2);
  assert.match(app, /minLength=\{4\}/);
});

test("admin passwords can be changed from the admin users screen", async () => {
  const [app, api] = await Promise.all([
    readFile(new URL("../src/App.tsx", import.meta.url), "utf8"),
    readFile(new URL("../src/lib/api.ts", import.meta.url), "utf8")
  ]);

  assert.match(app, /const \[adminPasswordByUser, setAdminPasswordByUser\]/);
  assert.match(app, /api\.changePassword\(credentials, user, nextPassword\)/);
  assert.match(app, /user\.active[\s\S]*changeAdminPassword\(user\.username\)/);
  assert.match(app, /setBusy\(`admin-password-\$\{user\}`\)/);
  assert.match(api, /\/api\/v1\/admin\/users\/\$\{encodeURIComponent\(username\)\}\/password/);
});

test("inactive admin users require a new password before activation", async () => {
  const [app, api] = await Promise.all([
    readFile(new URL("../src/App.tsx", import.meta.url), "utf8"),
    readFile(new URL("../src/lib/api.ts", import.meta.url), "utf8")
  ]);

  assert.match(app, /api\.activateUser\(credentials, user, nextPassword\)/);
  assert.match(app, /nextPassword\.length < 4/);
  assert.match(app, /minLength=\{4\}/);
  assert.match(app, /user\.active \? t\("changePassword"\) : t\("activate"\)/);
  assert.match(app, /user\.active[\s\S]*changeAdminPassword\(user\.username\)[\s\S]*activateAdminUser\(user\.username\)/);
  assert.match(api, /\/api\/v1\/admin\/users\/\$\{encodeURIComponent\(username\)\}\/activation/);
  assert.match(api, /method: "PUT"/);
});
