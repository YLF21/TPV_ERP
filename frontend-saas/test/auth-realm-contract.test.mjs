import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { readSources } from "../test-support/source-helpers.mjs";

const sources = Promise.all([
  readSources("app/App.tsx"),
  readSources("lib/api.ts", "lib/tenant-api.ts"),
  readFile(new URL("../src/lib/types.ts", import.meta.url), "utf8")
]);

test("internal portal uses the admin login and retains tenant contracts only for the future application", async () => {
  const [app, api, types] = await sources;
  assert.match(types, /mode: "admin" \| "tenant"/);
  assert.match(api, /publicPost<LoginResponse>\("\/api\/v1\/auth\/admin\/login", credentials\)/);
  assert.match(app, /mode: authenticated\.mode/);
  // Navigation normalization may run inside the admin guard before refreshing.
  assert.match(app, /if \(credentials\?\.mode === "admin"\) \{\s*(?:if \(window\.location\.hash[^\n]*\) \{\s*window\.history\.replaceState\([^\n]*\);\s*\}\s*)?void refresh\(credentials\)/);
  assert.doesNotMatch(app, /TenantWorkspace|MastersView|OperationsView/);
  const refresh = app.slice(app.indexOf("async function refresh"), app.indexOf("async function login"));
  assert.doesNotMatch(refresh, /error\.status === 403/);
});

test("initial login and password reauthentication reject other realms before admitting credentials", async () => {
  const [app] = await sources;
  const guard = app.slice(app.indexOf("function acceptInternalSession"), app.indexOf("async function login"));
  assert.match(guard, /if \(next\.mode === "admin"\) return true/);
  assert.match(guard, /api\.logout\(next\)/);
  assert.match(guard, /pendingPasswordChangeRef\.current = null/);
  assert.match(guard, /setCredentials\(null\)/);
  assert.match(guard, /setPendingPasswordChange\(null\)/);
  assert.match(guard, /i18n\.t\("internalAccessOnly"\)/);
  assert.match(guard, /return false/);
  const login = app.slice(app.indexOf("async function login"), app.indexOf("async function completeRequiredPasswordChange"));
  const reauthentication = app.slice(app.indexOf("async function completeRequiredPasswordChange"), app.indexOf("async function requestRecovery"));
  for (const branch of [login, reauthentication]) {
    const guardAt = branch.indexOf("if (!acceptInternalSession(next)) return;");
    assert.ok(guardAt >= 0);
    assert.ok(guardAt < branch.indexOf("credentialsRef.current = next"));
  }
  assert.ok(login.indexOf("if (!acceptInternalSession(next)) return;") < login.indexOf("authenticated.passwordChangeRequired"));
});

test("internal login context is localized and fiscal monitoring is separate from global policy", async () => {
  const [app] = await sources;
  const auth = await readSources("features/auth/AuthScreens.tsx");
  assert.match(auth, /t\("centralAdministration"\)/);
  assert.match(auth, /t\("internalPortal"\)/);
  assert.match(app, /activeView === "fiscal" && <FiscalStatusView/);
  assert.match(app, /activeView === "fiscal-policy" && <VerifactuPolicySection/);
  assert.match(app, /canManage=\{permissions\.has\("MANAGE_FISCAL_POLICY"\)\}/);
  assert.match(app, /group === "system" && item\.phase && item\.phase !== items\[index - 1\]\?\.phase/);
  assert.match(app, /l\(activeNavigationItem\.phase \?\? activeNavigationItem\.group\)/);
  assert.match(app, /className="module-help">\{l\(activeNavigationItem\.description\)\}/);
});

test("leaving mandatory password change revokes its pending bearer token", async () => {
  const [app] = await sources;
  assert.match(app, /credentialsRef\.current \?\? pendingPasswordChangeRef\.current\?\.credentials/);
  assert.match(app, /api\.logout\(activeCredentials\)/);
});

test("tenant optional data is settled independently and CSV failures are explicit", async () => {
  const [, api] = await sources;
  const portal = await readSources("lib/tenant-api.ts");
  const workspace = await readSources("features/tenant/TenantWorkspace.tsx");
  const sharedApi = await readSources("lib/api.ts");
  assert.match(portal, /export\s+async\s+function\s+loadTenantPortal\s*\(/);
  assert.match(workspace, /loadTenantPortal\s*\(\s*scopedCredentials\s*,\s*company\s*\)/);
  assert.doesNotMatch(sharedApi, /\btenantPortal\s*\(/);
  assert.match(portal, /Promise\.allSettled\s*\(/);
  assert.match(portal, /loadErrors\s*:/);
  for (const [privilege, resources] of [
    ["READ_COMPANY", ["tenantLicenses"]],
    ["READ_BILLING", ["tenantInvoices"]],
    ["SUPPORT", ["tenantTickets"]],
    ["READ_MASTERS", ["tenantErpCustomers", "tenantErpProducts", "tenantErpSuppliers", "tenantErpWarehouses"]]
  ]) {
    for (const resource of resources) {
      assert.match(portal, new RegExp(`can\\("${privilege}"\\)\\s*\\?\\s*api\\.${resource}\\(credentials\\)`));
    }
  }
  assert.match(api, /respuesta CSV vacia/);
  assert.match(api, /respuesta vacia al importar CSV/);
  assert.match(api, /resultado de importacion CSV invalido/);
  assert.doesNotMatch(api, /processed:\s*0,\s*inserted:\s*0,\s*updated:\s*0/);
});
