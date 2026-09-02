import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const sources = Promise.all([
  readFile(new URL("../src/App.tsx", import.meta.url), "utf8"),
  readFile(new URL("../src/lib/api.ts", import.meta.url), "utf8"),
  readFile(new URL("../src/lib/types.ts", import.meta.url), "utf8")
]);

test("login preserves the backend realm and tenant refresh never probes admin first", async () => {
  const [app, , types] = await sources;
  assert.match(types, /mode: "admin" \| "tenant"/);
  assert.match(app, /mode: authenticated\.mode/);
  assert.match(app, /if \(activeCredentials\.mode === "tenant"\)/);
  const refresh = app.slice(app.indexOf("async function refresh"), app.indexOf("async function login"));
  assert.doesNotMatch(refresh, /error\.status === 403/);
});

test("leaving mandatory password change revokes its pending bearer token", async () => {
  const [app] = await sources;
  assert.match(app, /credentialsRef\.current \?\? pendingPasswordChangeRef\.current\?\.credentials/);
  assert.match(app, /api\.logout\(activeCredentials\)/);
});

test("tenant optional data is settled independently and CSV failures are explicit", async () => {
  const [, api] = await sources;
  assert.match(api, /async tenantPortal/);
  assert.match(api, /Promise\.allSettled/);
  assert.match(api, /loadErrors/);
  assert.match(api, /respuesta CSV vacia/);
  assert.match(api, /respuesta vacia al importar CSV/);
  assert.match(api, /resultado de importacion CSV invalido/);
  assert.doesNotMatch(api, /processed: 0, inserted: 0, updated: 0/);
});
