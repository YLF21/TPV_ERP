import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const sources = Promise.all([
  readFile(new URL("../src/App.tsx", import.meta.url), "utf8"),
  readFile(new URL("../src/lib/api.ts", import.meta.url), "utf8"),
  readFile(new URL("../src/lib/types.ts", import.meta.url), "utf8")
]);

test("V50 outbox recovery consumes the paginated payload without rendering message payloads", async () => {
  const [app, api, types] = await sources;
  assert.match(types, /type OutboxFailurePage = \{\s*items: OutboxFailure\[\];\s*nextCursor: string \| null;/s);
  assert.match(api, /\/api\/v1\/admin\/outbox\/failures\?\$\{params\.toString\(\)\}/);
  assert.match(api, /params\.set\("cursor", options\.cursor\)/);
  assert.match(api, /\/api\/v1\/admin\/outbox\/\$\{resource\}\/\$\{encodeURIComponent\(id\)\}\/\$\{action\}/);
  assert.match(app, /permissions\.has\("MANAGE_OPERATIONS"\)/);
  assert.match(app, /failure\.error \|\| t\("notAvailable"\)/);
  assert.doesNotMatch(types.slice(types.indexOf("export type OutboxFailure"), types.indexOf("export type AdminSession")), /payload|token/i);
});

test("billing mutations and fiscal verification are isolated by company context", async () => {
  const [app] = await sources;
  assert.match(app, /billingContextId\.current \+= 1/);
  assert.match(app, /setBusy\(null\); setReconciliationBusy\(false\); setFiscalBusy\(false\)/);
  assert.match(app, /operationContext === billingContextId\.current && isCurrentSelection/);
  assert.match(app, /settleWithConcurrency\(companyInvoices, .*?, 4\)/);
  assert.match(app, /fiscalStatesLoadError && <RetryError/);
});
