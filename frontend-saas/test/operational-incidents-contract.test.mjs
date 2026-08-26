import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const appSourceUrl = new URL("../src/App.tsx", import.meta.url);
const apiSourceUrl = new URL("../src/lib/api.ts", import.meta.url);
const typesSourceUrl = new URL("../src/lib/types.ts", import.meta.url);

test("SyncView consumes real operational incidents without demo fallbacks", async () => {
  const source = await readFile(appSourceUrl, "utf8");

  assert.match(source, /\["incidents", canViewIncidents \? `\$\{t\("incidents"\)\} \(\$\{incidents\.length\}\)` : t\("incidents"\)\]/);
  assert.match(source, /permissions\.has\("MANAGE_OPERATIONAL_INCIDENTS"\)/);
  assert.match(source, /permissions\.has\("VIEW_ADMIN_DATA"\)/);
  assert.match(source, /const incidentsPromise = canViewIncidents/);
  assert.match(source, /incident\.cancellable && canManage/);
  assert.doesNotMatch(source, /sampleSyncEvents|sampleStock|demoProfiles|DEMO_COMPANY_ID|demoHint/);
});

test("incident cancellation is idempotent, version-aware and validates the reason", async () => {
  const [appSource, apiSource] = await Promise.all([
    readFile(appSourceUrl, "utf8"),
    readFile(apiSourceUrl, "utf8")
  ]);

  assert.match(appSource, /commandId: crypto\.randomUUID\(\)/);
  assert.match(appSource, /expectedStatus: cancelTarget\.incident\.status/);
  assert.match(appSource, /reason\.length < 5/);
  assert.match(appSource, /error instanceof ApiError && error\.status === 409/);
  assert.match(apiSource, /\/api\/v1\/admin\/operational-incidents/);
  assert.match(apiSource, /member-category-bootstraps\/\$\{encodeURIComponent\(bootstrapId\)\}\/cancel/);
});

test("synchronization exposes projection state and the central projection counters", async () => {
  const [appSource, apiSource, typesSource] = await Promise.all([
    readFile(appSourceUrl, "utf8"),
    readFile(apiSourceUrl, "utf8"),
    readFile(typesSourceUrl, "utf8")
  ]);

  assert.match(apiSource, /\/api\/v1\/admin\/sync\/projection-status/);
  assert.match(appSource, /event\.projectionStatus/);
  assert.match(appSource, /event\.projectionError/);
  assert.match(appSource, /projectionStatus\?\.received/);
  assert.match(appSource, /projectionStatus\?\.error/);
  assert.match(typesSource, /oldestReceivedAt: string \| null/);
});

test("operational incident copy is present in Spanish, English and Chinese", async () => {
  const source = await readFile(appSourceUrl, "utf8");
  const occurrences = source.match(/operationalIncidentsSubtitle:/g) ?? [];

  assert.equal(occurrences.length, 3);
  assert.match(source, /Incidencias operativas/);
  assert.match(source, /Operational incidents/);
  assert.match(source, /运行事件异常/);
});

test("operational dashboards never manufacture health, billing, technical or permission data", async () => {
  const source = await readFile(appSourceUrl, "utf8");

  assert.doesNotMatch(source, /fallbackHealth|fallbackBilling|fallbackTechnicalStatus/);
  assert.doesNotMatch(source, /fallbackSession|fallbackPermissions/);
  assert.match(source, /new Set\(session\?\.permissions \?\? \[\]\)/);
});

test("fiscal inventory renders installations that have not reported yet", async () => {
  const [appSource, typesSource] = await Promise.all([
    readFile(appSourceUrl, "utf8"),
    readFile(typesSourceUrl, "utf8")
  ]);

  assert.match(appSource, /if \(mode === "MIXED"\) return t\("fiscalMixed"\)/);
  assert.match(appSource, /row\.reportedAt \? formatDate\(row\.reportedAt\) : "-"/);
  assert.match(typesSource, /reportedAt: string \| null/);
});
