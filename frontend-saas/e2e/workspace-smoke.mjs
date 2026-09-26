import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
const playwright = await import("playwright").catch(() => import("../../frontend/node_modules/playwright/index.mjs"));

// Browser behaviour only: all API responses below are synthetic contract fixtures.
const root = fileURLToPath(new URL("..", import.meta.url));
const base = "http://127.0.0.1:5186/";
const companyA = "10000000-0000-4000-8000-000000000001";
const companyB = "10000000-0000-4000-8000-000000000002";
const companyWithoutLicense = "10000000-0000-4000-8000-000000000003";
const storeA = "20000000-0000-4000-8000-000000000001";
const storeB = "20000000-0000-4000-8000-000000000002";
const storeWithoutLicense = "20000000-0000-4000-8000-000000000004";
const installation = "30000000-0000-4000-8000-000000000001";
const address = { linea1: "Calle Sintética 1", ciudad: "Las Palmas", codigoPostal: "35001", provincia: "Las Palmas", pais: "ES" };
const licenses = [companyA, companyB].map((companyId, index) => ({ licenseReference: `LIC-DEMO-${index + 1}`, companyId,
  companyName: `Empresa de prueba ${index + 1}`, taxId: `B0000000${index}`, taxpayerType: "SOCIEDAD", taxRegime: "IVA",
  commercialProfile: "MINORISTA", status: "VALIDA", validUntil: "2099-01-01T00:00:00Z", maxWindows: 1, maxPda: 0 }));
const allPermissions = ["VIEW_ADMIN_DATA", "ADD_COMPANY", "EDIT_COMPANY_DATA", "MANAGE_TENANT_USERS", "MANAGE_OPERATIONS", "RENEW_LICENSE", "BLOCK_LICENSE", "UNBLOCK_LICENSE", "REGENERATE_PAIRING_CODE"];
const companies = [...licenses.map(l => ({ companyId: l.companyId, companyName: l.companyName, taxId: l.taxId, taxpayerType: l.taxpayerType,
  commercialProfile: l.commercialProfile, companyAddress: address, createdAt: "2026-01-01T00:00:00Z" })),
  { companyId: companyWithoutLicense, companyName: "Empresa sin licencia", taxId: "B00000002", taxpayerType: "SOCIEDAD", commercialProfile: "MINORISTA", companyAddress: address, createdAt: "2026-01-01T00:00:00Z" }];
const errors = [];
let server;
let browser;

async function start() {
  const bin = fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url));
  server = spawn(process.execPath, [bin, "--host", "127.0.0.1", "--port", "5186", "--strictPort", "--configLoader", "runner"], { cwd: root, stdio: "ignore", windowsHide: true });
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error("Vite workspace test could not start on port 5186");
    try { if ((await fetch(base)).ok) return; } catch { /* bounded startup polling */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("Vite workspace test startup timed out");
}

async function setup(permissions = allPermissions) {
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  page.on("pageerror", error => errors.push(error.message));
  const calls = [];
  let companiesFail = false;
  const tenantUsers = [];
  const activationCodes = [];
  let stores = [storeA, storeB].map((id, i) => ({ id, companyId: i ? companyB : companyA, companyName: licenses[i].companyName,
    code: "001", internalCode: `350000${i + 1}`, name: `Tienda de prueba ${i + 1}`, active: true,
    storeAddress: address, timeZoneId: "Atlantic/Canary", installations: 1, activeInstallations: 1,
    taxRegime: "IGIC", commercialProfile: "MINORISTA", taxRegimeLocked: true, servicePrice: "29.00", billingPeriod: "MONTHLY", maxWindows: 1, maxPda: 0, validUntil: "2099-01-01T00:00:00Z",
    lastSyncAt: "2026-09-20T10:00:00Z", createdAt: "2026-01-01T00:00:00Z" }));
  stores.push({ ...stores[0], id: storeWithoutLicense, companyId: companyWithoutLicense, companyName: "Empresa sin licencia",
    internalCode: "3500004", name: "Tienda sin licencia", taxRegimeLocked: false, installations: 0, activeInstallations: 0, lastSyncAt: null });
  let memberships = [{ companyId: companyA, companyName: licenses[0].companyName, roleName: "VIEWER", companyPrivileges: [],
    stores: [{ storeId: storeA, code: "001", name: stores[0].name, internalCode: "3500001", active: true }] }];
  const failure = { id: "LOCAL_SYNC:40000000-0000-4000-8000-000000000001", source: "LOCAL_SYNC", sourceId: "50000000-0000-4000-8000-000000000001",
    companyId: companyA, companyName: licenses[0].companyName, storeId: storeA, storeName: stores[0].name, internalCode: "3500001",
    installationId: installation, installationReference: "INST-DEMO-1", status: "OPEN", severity: "WARNING", code: "SYNC_DELIVERY_FAILED",
    detail: "Synthetic store delivery failure", firstSeenAt: "2026-09-19T10:00:00Z", lastSeenAt: "2026-09-20T10:00:00Z", occurrences: 3, central: false, storeActive: true };
  await page.route("**/api/**", async route => {
    const request = route.request(); const url = new URL(request.url()); const path = decodeURIComponent(url.pathname); const method = request.method();
    const body = request.postData() ? JSON.parse(request.postData()) : null;
    calls.push({ path, method, query: Object.fromEntries(url.searchParams), body });
    const json = value => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/v1/auth/admin/login") return json({ username: "WORKSPACE_DEMO", accessToken: "synthetic-admin-token", mode: "admin", expiresAt: "2099-01-01T00:00:00Z", passwordChangeRequired: false });
    if (path === "/api/v1/admin/me") return json({ username: "WORKSPACE_DEMO", permissions });
    if (path === "/api/v1/admin/licenses") return json(licenses);
    if (path === "/api/v1/admin/companies") return companiesFail
      ? route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Synthetic companies unavailable" }) }) : json(companies);
    if (path === "/api/v1/admin/sync/sales-summary") return json({ documentCount: 0, total: "0.00" });
    if (path === "/api/v1/admin/reports/advanced") return json({ companies: 2, invoices: 0, invoicedTotal: "0.00", paidTotal: "0.00", salesDocuments: 0, salesTotal: "0.00", inventoryMovements: 0, integrations: 0, activeIntegrations: 0 });
    if (path === "/api/v1/admin/stores" && method === "GET") {
      const q = (url.searchParams.get("q") ?? "").toLowerCase();
      const company = url.searchParams.get("companyId"); const active = url.searchParams.get("active");
      const filtered = stores.filter(s => (!company || s.companyId === company) && (!active || String(s.active) === active)
        && (!q || [s.name, s.internalCode, s.code, s.companyName].some(value => value.toLowerCase().includes(q))));
      const sortBy = url.searchParams.get("sortBy") || "internalCode";
      const direction = url.searchParams.get("sortDirection") === "DESC" ? -1 : 1;
      filtered.sort((a, b) => (String(a[sortBy] ?? "").localeCompare(String(b[sortBy] ?? ""), "es-ES", { numeric: true }) || a.id.localeCompare(b.id)) * direction);
      return json({ items: filtered, page: 0, size: 25, total: filtered.length, totalPages: 1 });
    }
    if (path.match(/\/companies\/[^/]+\/stores$/) && method === "POST") {
      const next = { ...stores[0], ...body, companyId: path.split("/")[5], id: "20000000-0000-4000-8000-000000000003", internalCode: "3500003", active: true };
      stores.push(next); return json(next);
    }
    if (path.startsWith("/api/v1/admin/stores/") && method === "PUT") {
      const id = path.split("/")[5]; stores = stores.map(s => s.id === id ? { ...s, ...body } : s); return json(stores.find(s => s.id === id));
    }
    if (path.startsWith("/api/v1/admin/stores/") && method === "GET") return json(stores.find(s => s.id === path.split("/")[5]));
    if (path === "/api/v1/admin/license-workspace/activation-codes" && method === "GET") return json({ items: activationCodes, page: 0, size: 25, total: activationCodes.length, totalPages: activationCodes.length ? 1 : 0, serverNow: new Date().toISOString() });
    if (path === "/api/v1/admin/license-workspace" && method === "POST") {
      const selected = stores.find(s => s.id === body.storeId);
      const serverNow = new Date().toISOString();
      const created = { id: "60000000-0000-4000-8000-000000000003", pairingCodeId: "70000000-0000-4000-8000-000000000003", reference: "LIC-GENERATED", companyId: selected.companyId, storeId: selected.id, pairingCode: "TPV-SYNTHETIC-DEMO", pairingExpiresAt: new Date(Date.parse(serverNow) + 30 * 60_000).toISOString(), serverNow };
      activationCodes.splice(0, activationCodes.length, { ...created, id: created.pairingCodeId, licenseId: created.id, companyName: selected.companyName, storeName: selected.name, internalCode: selected.internalCode, storeCode: selected.code });
      return json(created);
    }
    if (path.startsWith("/api/v1/admin/license-workspace") && method === "GET") {
      const items = licenses.map((l, i) => ({ id: `60000000-0000-4000-8000-00000000000${i + 1}`, reference: l.licenseReference,
        companyId: l.companyId, companyName: l.companyName, taxId: l.taxId, status: l.status, validUntil: l.validUntil,
        maxWindows: 1, maxPda: 0, activeInstallations: 1, lastValidatedAt: "2026-09-20T09:00:00Z", lastSyncAt: "2026-09-20T10:00:00Z",
        stores: stores.filter(s => s.companyId === l.companyId), billingScope: "COMPANY", companyBillingStatus: "PAGADO", companyDebt: [] }));
      return json(path === "/api/v1/admin/license-workspace" ? { items, page: 0, size: 25, total: 2, totalPages: 1 } : items.find(item => item.id === path.split("/").at(-1)));
    }
    if (path.startsWith("/api/v1/admin/tenant-users/client-demo/access")) {
      if (method === "PUT") {
        const id = path.split("/").at(-1); memberships = memberships.filter(m => m.companyId !== id);
        memberships.push({ companyId: id, companyName: companies.find(company => company.companyId === id).companyName, roleName: body.roleName,
          companyPrivileges: body.companyPrivileges, stores: body.storeIds.map(id => { const s = stores.find(s => s.id === id); return { storeId: id, code: s.code, name: s.name, internalCode: s.internalCode, active: s.active }; }) });
      }
      return json({ username: "client-demo", companies: memberships });
    }
    if (path.match(/\/companies\/[^/]+\/tenant-users$/)) {
      const selectedCompany = path.split("/")[5];
      if (method === "POST") {
        const created = { companyId: selectedCompany, username: body.username, roleName: body.roleName, active: true, createdAt: "2026-09-20T10:00:00Z" };
        tenantUsers.push(created); return json(created);
      }
      return json(tenantUsers.filter(user => user.companyId === selectedCompany));
    }
    if (path === "/api/v1/admin/supervision/failures") return json({ items: [failure], nextCursor: null, hasMore: false, size: 1 });
    if (path === `/api/v1/admin/supervision/failures/${failure.id}`) return json(failure);
    if (method !== "GET") throw new Error(`Unexpected workspace mutation: ${method} ${path}`);
    return json([]);
  });
  await page.goto(base, { waitUntil: "domcontentloaded" });
  await page.locator('input[autocomplete="username"]').fill("WORKSPACE_DEMO");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password-only");
  await page.locator('form button[type="submit"]').click();
  await page.locator(".saas-dashboard").waitFor();
  return { page, calls, failCompanies: value => { companiesFail = value; } };
}

async function nav(page, name) { await page.locator(".top-nav-list").getByRole("button", { name, exact: true }).click(); }
async function assertLogoutInsideViewport(page) {
  for (const width of [1600, 1280]) {
    await page.setViewportSize({ width, height: 1000 });
    const logout = page.locator(".system-session .session-logout");
    await logout.waitFor({ state: "visible" });
    const bounds = await logout.boundingBox();
    assert.ok(bounds && bounds.width > 0 && bounds.height > 0, `Logout must have visible bounds at ${width}px`);
    assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= width, `Logout must fit horizontally at ${width}px: ${JSON.stringify(bounds)}`);
    assert.ok(bounds.y >= 0 && bounds.y + bounds.height <= 1000, `Logout must fit vertically at ${width}px`);
    assert.equal(await logout.evaluate(element => {
      const rect = element.getBoundingClientRect();
      return element.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2));
    }), true, `Logout must remain clickable at ${width}px`);
  }
  await page.setViewportSize({ width: 1600, height: 1000 });
}
async function latestCall(calls, predicate) {
  for (let attempt = 0; attempt < 200; attempt++) {
    const result = calls.findLast(predicate); if (result) return result;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error("Expected API request was not received");
}

try {
  await start(); browser = await playwright.chromium.launch({ headless: true });
  const { page, calls, failCompanies } = await setup();
  await assertLogoutInsideViewport(page);
  console.log("Workspace E2E: logout fits and remains clickable at 1600px and 1280px.");
  await nav(page, "Tiendas");
  await page.getByRole("cell", { name: "3500001", exact: true }).waitFor();
  const firstStore = page.getByRole("row").filter({ has: page.getByRole("cell", { name: "3500001", exact: true }) });
  const storeDialog = page.locator("dialog.saas-store-dialog");
  await firstStore.dblclick();
  await page.getByRole("form", { name: "Editar" }).getByLabel("Nombre", { exact: true }).fill("Tienda editada");
  await page.getByRole("button", { name: "Guardar", exact: true }).click();
  assert.equal((await latestCall(calls, c => c.method === "PUT" && c.path.endsWith(storeA))).body.name, "Tienda editada");
  await storeDialog.waitFor({ state: "hidden" });
  await firstStore.dblclick();
  await page.getByRole("form", { name: "Editar" }).getByLabel("Nombre", { exact: true }).fill("Unsaved activity regression");
  const savesBeforeActivity = calls.filter(c => c.method === "PUT" && c.path.endsWith(storeA)).length;
  await storeDialog.getByRole("button", { name: /Desactivar/i }).click();
  assert.equal((await latestCall(calls, c => c.path.endsWith(`${storeA}/activity`))).body.active, false);
  await page.getByRole("form", { name: "Editar" }).waitFor({ state: "hidden" });
  assert.equal(calls.filter(c => c.method === "PUT" && c.path.endsWith(storeA)).length, savesBeforeActivity,
    "Changing activity must close the stale editor without saving its old active state");
  await firstStore.dblclick();
  await storeDialog.getByRole("button", { name: /^Activar$/i }).click();
  await storeDialog.waitFor({ state: "hidden" });
  await page.getByRole("button", { name: "Crear", exact: true }).click();
  const createStore = page.getByRole("form", { name: "Crear", exact: true });
  await createStore.getByLabel("Empresa", { exact: true }).selectOption(companyA);
  await createStore.getByLabel("Código local (3 cifras)", { exact: true }).fill("003");
  await createStore.getByLabel("Nombre", { exact: true }).fill("Nueva tienda sintética");
  await createStore.getByLabel("Impuestos", { exact: true }).selectOption("IGIC");
  await createStore.getByLabel("Perfil comercial", { exact: true }).selectOption("MINORISTA");
  await createStore.getByLabel("Precio SaaS (EUR)", { exact: true }).fill("29.00");
  await createStore.getByLabel("Periodicidad", { exact: true }).selectOption("MONTHLY");
  await createStore.getByLabel("Valida hasta", { exact: true }).click();
  await page.locator(".date-time-popover").getByRole("button", { name: /Hoy|Today/ }).click();
  await page.locator(".date-time-popover .primary-button").click();
  for (const [label, value] of [["Direccion", address.linea1], ["Ciudad", address.ciudad], ["Codigo postal", address.codigoPostal], ["Pais", address.pais]]) {
    await createStore.getByLabel(label, { exact: true }).fill(value);
  }
  await createStore.getByLabel("Provincia", { exact: true }).selectOption(address.provincia);
  await createStore.getByRole("button", { name: "Guardar", exact: true }).click();
  await page.getByRole("cell", { name: "3500003", exact: true }).waitFor();
  console.log("Workspace E2E: seven-digit codes, store create/edit/activity and stale-editor closure passed.");

  await nav(page, "Licencias activas");
  await page.getByRole("row").filter({ hasText: "LIC-DEMO-1" }).dblclick();
  const licenseDetail = page.getByRole("heading", { name: "LIC-DEMO-1", level: 3, exact: true });
  await licenseDetail.waitFor({ state: "visible" });
  await page.keyboard.press("Escape");
  await page.getByLabel("Buscar empresa, tienda o referencia", { exact: true }).fill("3500001");
  await latestCall(calls, c => c.path.endsWith("license-workspace") && c.query.q === "3500001");
  await licenseDetail.waitFor({ state: "hidden" });
  await page.getByRole("row").filter({ hasText: "LIC-DEMO-1" }).waitFor({ state: "visible" });
  assert.equal(await licenseDetail.count(), 0, "A fresh filtered response must not reopen the previous license detail");
  await page.getByLabel(/^Estado/).selectOption("CADUCADA");
  await latestCall(calls, c => c.path.endsWith("license-workspace") && c.query.status === "CADUCADA");
  await page.getByRole("search", { name: "Licencias activas", exact: true }).getByRole("button", { name: "+ Más filtros", exact: true }).click();
  await page.getByLabel("Caduca antes de", { exact: true }).click();
  await page.locator(".date-time-popover").getByRole("button", { name: /Hoy|Today/ }).click();
  await page.locator(".date-time-popover .primary-button").click();
  assert.ok((await latestCall(calls, c => c.path.endsWith("license-workspace") && c.query.expiresBefore)).query.expiresBefore.endsWith("Z"));
  await nav(page, "Crear licencia");
  await page.getByRole("combobox", { name: "Empresa", exact: true }).fill("Empresa de prueba 1");
  await page.getByRole("option", { name: /Empresa de prueba 1/ }).click();
  assert.equal(await page.getByLabel("Referencia", { exact: true }).count(), 0);
  await page.getByRole("table", { name: "Tiendas de la empresa", exact: true }).getByRole("row").filter({ has: page.getByRole("cell", { name: "3500001", exact: true }) }).getByRole("button", { name: "Generar código de activación", exact: true }).click();
  const created = await latestCall(calls, c => c.path.endsWith("license-workspace") && c.method === "POST");
  assert.deepEqual(created.body, { storeId: storeA });
  await page.getByText("TPV-SYNTHETIC-DEMO", { exact: true }).first().waitFor();
  console.log("Workspace E2E: remote license filters clear the previous detail; expiry and separate creation passed.");

  assert.equal(licenses.some(license => license.companyId === companyWithoutLicense), false);
  failCompanies(true);
  await nav(page, "Usuarios admin");
  const tenantSection = page.locator("section.content-section").filter({ has: page.getByRole("heading", { name: "Usuarios cliente", exact: true }) });
  await tenantSection.locator(".retry-error").waitFor();
  assert.equal(await tenantSection.getByLabel("Empresa", { exact: true }).isDisabled(), true);
  assert.equal(await tenantSection.getByRole("button", { name: "Crear usuario cliente", exact: true }).isDisabled(), true);
  failCompanies(false); await tenantSection.getByRole("button", { name: "Reintentar", exact: true }).click();
  await tenantSection.getByLabel("Empresa", { exact: true }).selectOption(companyWithoutLicense);
  const createTenant = page.getByRole("form", { name: "Crear usuario cliente", exact: true });
  await createTenant.getByLabel("Usuario", { exact: true }).fill("unlicensed-company-user");
  await createTenant.getByLabel("Password", { exact: true }).fill("synthetic-password-only");
  await createTenant.getByRole("button", { name: "Crear usuario cliente", exact: true }).click();
  const createdTenant = await latestCall(calls, c => c.method === "POST" && c.path.endsWith(`${companyWithoutLicense}/tenant-users`));
  assert.equal(createdTenant.body.username, "unlicensed-company-user");
  await tenantSection.getByRole("cell", { name: "unlicensed-company-user", exact: true }).waitFor();
  console.log("Workspace E2E: company-list error/retry and customer account creation without a license passed.");

  failCompanies(true);
  await nav(page, "Accesos a empresas y tiendas");
  await page.getByLabel("Usuario cliente", { exact: true }).fill("client-demo");
  await page.getByRole("button", { name: "Detalle", exact: true }).click();
  await page.locator(".retry-error").waitFor();
  assert.equal(await page.getByLabel("Empresa", { exact: true }).isDisabled(), true);
  failCompanies(false); await page.getByRole("button", { name: "Reintentar", exact: true }).click();
  await page.getByLabel(/^Empresa/).selectOption(companyB);
  await page.getByLabel(/^Rol/).selectOption("MANAGER");
  await page.getByLabel("Gestionar maestros", { exact: true }).check();
  assert.equal(await page.getByLabel("Consultar maestros", { exact: true }).isChecked(), true);
  await page.getByRole("checkbox", { name: /Tienda de prueba 2$/ }).check();
  await page.getByRole("button", { name: "Guardar", exact: true }).click();
  const grant = await latestCall(calls, c => c.method === "PUT" && c.path.includes("/access/companies/"));
  assert.equal(grant.path.endsWith(companyB), true); assert.deepEqual(grant.body.storeIds, [storeB]);
  assert.deepEqual([...grant.body.companyPrivileges].sort(), ["READ_MASTERS", "WRITE_MASTERS"]);
  await page.getByLabel("Empresa", { exact: true }).selectOption(companyWithoutLicense);
  await page.getByLabel("Consultar empresa y licencias", { exact: true }).check();
  await page.getByRole("checkbox", { name: /Tienda sin licencia$/ }).check();
  await page.getByRole("button", { name: "Guardar", exact: true }).click();
  const unlicensedGrant = await latestCall(calls, c => c.method === "PUT" && c.path.endsWith(`/access/companies/${companyWithoutLicense}`));
  assert.deepEqual(unlicensedGrant.body.storeIds, [storeWithoutLicense]);
  assert.deepEqual(unlicensedGrant.body.companyPrivileges, ["READ_COMPANY"]);
  const accessEditor = page.locator("section.content-section").filter({ has: page.getByRole("heading", { name: "Accesos a empresas y tiendas", level: 2, exact: true }) });
  assert.equal(await accessEditor.locator('input[type="password"]').count(), 0);
  assert.equal(await page.getByText("TPV-SYNTHETIC-DEMO", { exact: true }).count(), 0);
  console.log("Workspace E2E: explicit grants without a license, company-list retry, privilege dependency and no installation secret passed.");

  await nav(page, "Fallos de tiendas");
  await page.getByRole("cell", { name: "3500001", exact: false }).waitFor();
  await latestCall(calls, c => c.path.endsWith("supervision/failures") && c.query.activeStoresOnly === "true");
  await page.getByLabel("Buscar nombre, código o referencia", { exact: true }).fill("3500001");
  await page.getByLabel(/^Origen/).selectOption("LOCAL_SYNC");
  await page.getByLabel(/^Estado/).selectOption("OPEN");
  await page.getByRole("button", { name: "Aplicar filtros", exact: true }).click();
  await latestCall(calls, c => c.path.endsWith("supervision/failures") && c.query.q === "3500001" && c.query.source === "LOCAL_SYNC" && c.query.status === "OPEN");
  await page.getByRole("button", { name: "Detalle", exact: true }).click();
  await latestCall(calls, c => c.path.includes("supervision/failures/LOCAL_SYNC:"));
  await page.locator("details.failure-technical summary").click();
  await page.getByText("50000000-0000-4000-8000-000000000001", { exact: true }).waitFor();
  const screenshotDirectory = fileURLToPath(new URL("../../output/playwright/", import.meta.url));
  await mkdir(screenshotDirectory, { recursive: true });
  await page.screenshot({ path: `${screenshotDirectory}/saas-workspace.png`, fullPage: true });
  console.log("Workspace E2E: received-failure server filters, encoded detail and synthetic screenshot passed.");
  await page.close();

  const viewer = await setup(["VIEW_ADMIN_DATA"]);
  assert.equal(await viewer.page.locator(".top-nav-list").getByRole("button", { name: "Crear licencia", exact: true }).count(), 0);
  assert.equal(await viewer.page.locator(".top-nav-list").getByRole("button", { name: "Accesos a empresas y tiendas", exact: true }).count(), 0);
  await nav(viewer.page, "Tiendas");
  await viewer.page.getByRole("cell", { name: "3500001", exact: true }).waitFor();
  assert.equal(await viewer.page.locator("main").getByRole("button", { name: /^(Crear|Editar|Desactivar|Activar)$/i }).count(), 0);
  await viewer.page.locator(`tr[data-row-id="${storeA}"]`).dblclick();
  const viewerStoreDialog = viewer.page.locator("dialog.saas-store-dialog");
  await viewerStoreDialog.getByLabel("Nombre", { exact: true }).waitFor();
  assert.equal(await viewerStoreDialog.getByLabel("Nombre", { exact: true }).isDisabled(), true);
  assert.equal(await viewerStoreDialog.getByRole("button", { name: /^(Guardar|Desactivar|Activar)$/ }).count(), 0);
  await viewer.page.keyboard.press("Escape");
  await viewerStoreDialog.waitFor({ state: "hidden" });
  await viewer.page.evaluate(() => { location.hash = "#/access"; });
  assert.equal(await viewer.page.getByLabel("Usuario cliente", { exact: true }).count(), 0);
  await viewer.page.close();
  assert.deepEqual(errors, []);
  console.log("Workspace E2E: viewer mutation guards passed. All scenarios passed.");
} finally {
  if (browser) await browser.close();
  if (server) server.kill();
}
