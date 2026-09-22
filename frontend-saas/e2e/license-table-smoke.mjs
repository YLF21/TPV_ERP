import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
const { chromium } = await import("playwright").catch(() => import("../../frontend/node_modules/playwright/index.mjs"));
// Real browser, synthetic API fixtures only. No customer data or running backend is used.
const root = fileURLToPath(new URL("..", import.meta.url));
const base = "http://127.0.0.1:5188/";
const id = suffix => `10000000-0000-4000-8000-${String(suffix).padStart(12, "0")}`;
const company = id(1), newCompany = id(2), store = id(3), newStore = id(4);
const permissions = ["VIEW_ADMIN_DATA", "ADD_COMPANY", "RENEW_LICENSE", "BLOCK_LICENSE", "UNBLOCK_LICENSE", "REGENERATE_PAIRING_CODE", "REVOKE_INSTALLATION"];
let server, browser;
const errors = [];
async function waitFor(check) {
  for (let index = 0; index < 100; index++) { const value = await check(); if (value) return value; await new Promise(resolve => setTimeout(resolve, 50)); }
  throw new Error("Timed out waiting for expected browser/API state");
}
async function nav(page, name) { await page.locator(".top-nav-list").getByRole("button", { name, exact: true }).click(); }
async function setup(username = "LICENSE_DEMO", allowed = permissions, count = 3) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  const page = await context.newPage(); page.on("pageerror", error => errors.push(error.message));
  const calls = [];
  const rows = [1, 2, 3].map((number) => ({ id: id(10 + number), reference: `LIC-DEMO-${number}`, companyId: company, companyName: "Empresa sintética", taxId: "B00000000",
    status: "VALIDA", validUntil: `209${number}-01-01T00:00:17.321Z`, maxWindows: number, maxPda: number - 1, activeInstallations: number,
    lastValidatedAt: "2026-09-20T09:00:00Z", lastSyncAt: "2026-09-20T10:00:00Z", stores: [{ id: store, code: "001", name: "Tienda sintética", internalCode: "3500001", active: true }],
    billingScope: "COMPANY", companyBillingStatus: "PENDIENTE", companyDebt: [{ currency: "EUR", outstanding: "29.00", overdue: "0.00" }, { currency: "USD", outstanding: "5.00", overdue: "0.00" }] }));
  if (count === 82) rows.splice(0, rows.length, ...Array.from({ length: count }, (_, index) => ({
    id: id(100 + index), reference: `LIC-SCROLL-${String(index + 1).padStart(3, "0")}`,
    companyId: index < 41 ? company : newCompany, companyName: index < 41 ? "Empresa sintética" : "Empresa sintética B", taxId: index < 41 ? "B00000000" : "B00000001",
    status: "VALIDA", validUntil: new Date(Date.UTC(2099, 0, index + 1, 0, 0, 17, 321)).toISOString(), maxWindows: 1 + index % 4, maxPda: index % 3,
    activeInstallations: index % 2, lastValidatedAt: "2026-09-20T09:00:00Z", lastSyncAt: "2026-09-20T10:00:00Z",
    stores: [{ id: id(200 + index), code: String(index + 1).padStart(3, "0"), name: `Tienda ${index < 2 ? "pareja" : "sintética"} ${index + 1}`, internalCode: String(3500001 + index), active: true }],
    billingScope: "COMPANY", companyBillingStatus: index < 41 ? "PENDIENTE" : "PAGADO", companyDebt: index < 41 ? [{ currency: "EUR", outstanding: "29.00", overdue: "0.00" }] : [],
  })));
  const installations = count === 3 ? [{ installationId: id(500), installationReference: "INST-LICENSE-DEMO", companyId: company, storeId: store,
    licenseReference: "LIC-DEMO-1", linkedAt: "2026-09-20T08:00:00Z", lastValidatedAt: "2026-09-20T09:00:00Z", lastSyncAt: "2026-09-20T10:00:00Z",
    appVersion: "synthetic", operatingSystem: "Test", terminalName: "Terminal sintético", lastIp: null, active: true,
    revokedAt: null, revokedBy: null, revocationReason: null, version: 1 }] : [];
  let delay = 0;
  let nextError = false;
  let heldNext = null;
  let detailError = false;
  let renewalError = false;
  let heldDashboard = null;
  const activationCodes = [];
  await context.route("**/api/**", async route => {
    const request = route.request(), url = new URL(request.url()), path = decodeURIComponent(url.pathname), method = request.method();
    const body = request.postData() ? JSON.parse(request.postData()) : null;
    calls.push({ path, method, query: Object.fromEntries(url.searchParams), body });
    const json = value => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/v1/auth/admin/login") return json({ username, accessToken: "synthetic-token", mode: "admin", expiresAt: "2099-01-01T00:00:00Z", passwordChangeRequired: false });
    if (path === "/api/v1/admin/me") return json({ username, permissions: allowed });
    if (path === "/api/v1/admin/licenses") {
      if (heldDashboard) {
        const held = heldDashboard; heldDashboard = null; held.started(); await held.released;
        if (held.fail) return route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Synthetic global refresh failure" }) });
      }
      return json(rows.map(row => ({ ...row, licenseReference: row.reference, taxpayerType: "SOCIEDAD", taxRegime: "IGIC", commercialProfile: "MINORISTA" })));
    }
    if (path === "/api/v1/admin/installations") return json(installations);
    if (path === `/api/v1/admin/installations/${id(500)}/revoke` && method === "POST") {
      assert.ok(body.reason.length >= 5);
      installations[0] = { ...installations[0], active: false, revokedAt: "2026-09-21T12:00:00Z", revokedBy: username, revocationReason: body.reason, version: 2 };
      return json({ ...installations[0], lastSyncAt: null });
    }
    if (path === "/api/v1/admin/companies") return json([{ companyId: company, companyName: "Empresa sintética", taxId: "B00000000" }, { companyId: newCompany, companyName: count === 82 ? "Empresa sintética B" : "Empresa sin licencia", taxId: "B00000001" }]);
    if (path === "/api/v1/admin/license-workspace/activation-codes" && method === "GET") return json({ items: activationCodes, page: 0, size: 25, total: activationCodes.length, totalPages: activationCodes.length ? 1 : 0, serverNow: new Date().toISOString() });
    if (path === "/api/v1/admin/license-workspace" && method === "POST") {
      assert.deepEqual(Object.keys(body), ["storeId"]);
      const serverNow = new Date().toISOString();
      const created = { id: id(20), pairingCodeId: id(21), companyId: newCompany, reference: "LIC-GENERATED", storeId: body.storeId, pairingCode: "SYNTHETIC-ACTIVATION", pairingExpiresAt: new Date(Date.parse(serverNow) + 30 * 60_000).toISOString(), serverNow };
      activationCodes.splice(0, activationCodes.length, { ...created, id: created.pairingCodeId, licenseId: created.id, companyName: "Empresa sin licencia", storeName: "Tienda sin licencia", internalCode: "3500002", storeCode: "002" });
      return json(created);
    }
    if (path.startsWith("/api/v1/admin/license-workspace/") && method === "GET") {
      if (detailError) return route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Synthetic license detail failure" }) });
      const row = rows.find(row => row.id === path.split("/").at(-1)); assert.ok(row); return json(row);
    }
    if (path === "/api/v1/admin/license-workspace") {
      if (delay) await new Promise(resolve => setTimeout(resolve, delay));
      const params = Object.fromEntries(url.searchParams);
      const key = params.sortBy, direction = params.sortDirection === "DESC" ? -1 : 1;
      const q = (params.q || "").toLocaleLowerCase("es-ES");
      const filtered = rows.filter(row => (!params.companyId || row.companyId === params.companyId)
        && (!params.status || row.status === params.status)
        && (!params.hasConnections || String(row.activeInstallations > 0) === params.hasConnections)
        && (!params.billingStatus || row.companyBillingStatus === params.billingStatus)
        && (!params.expiresBefore || row.validUntil < params.expiresBefore)
        && (!q || [row.reference, row.companyName, row.taxId, ...row.stores.flatMap(store => [store.name, store.code, store.internalCode])].some(value => value.toLocaleLowerCase("es-ES").includes(q))));
      const sortValue = row => key === "storeCode" ? row.stores[0]?.internalCode ?? "" : key === "billingStatus" ? row.companyBillingStatus : row[key] ?? "";
      const sorted = [...filtered].sort((a, b) => (String(sortValue(a)).localeCompare(String(sortValue(b)), "es-ES", { numeric: true }) || a.id.localeCompare(b.id)) * direction);
      const pageNumber = Number(params.page || 0), size = Number(params.size || 25);
      assert.equal(size, 25);
      const result = structuredClone({ items: sorted.slice(pageNumber * size, (pageNumber + 1) * size), page: pageNumber, size, total: sorted.length, totalPages: Math.ceil(sorted.length / size) });
      if (pageNumber === 1 && nextError) {
        nextError = false;
        return route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Synthetic license next-page failure" }) });
      }
      if (pageNumber === 1 && heldNext) {
        const held = heldNext; heldNext = null; held.started(); await held.released; await json(result); held.finished(); return;
      }
      return json(result);
    }
    if (path.match(/\/licenses\/[^/]+\/renew$/)) {
      if (renewalError) { renewalError = false; return route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Synthetic renewal failure" }) }); }
      const row = rows.find(row => row.reference === path.split("/").at(-2)); Object.assign(row, body); return json(row);
    }
    if (path.match(/\/licenses\/[^/]+\/(block|unblock)$/) && method === "POST") {
      const row = rows.find(row => row.reference === path.split("/").at(-2));
      row.status = path.endsWith("/unblock") ? "VALIDA" : "BLOQUEADA_MANUAL"; return json(row);
    }
    if (path === "/api/v1/admin/stores") {
      const selected = { id: newStore, companyId: newCompany, companyName: "Empresa sin licencia", internalCode: "3500002", code: "002", name: "Tienda sin licencia", active: true,
        taxRegime: "IGIC", taxRegimeLocked: false, servicePrice: "29.00", billingPeriod: "MONTHLY", maxWindows: 2, maxPda: 1, validUntil: "2099-01-01T00:00:00Z" };
      const items = !url.searchParams.get("companyId") || url.searchParams.get("companyId") === newCompany ? [selected] : [];
      return json({ items, page: 0, size: 25, total: items.length, totalPages: items.length ? 1 : 0 });
    }
    if (path === "/api/v1/admin/sync/sales-summary") return json({ documentCount: 0, total: "0.00" });
    if (path === "/api/v1/admin/reports/advanced") return json({ companies: 2, invoices: 0, invoicedTotal: 0, paidTotal: 0, salesDocuments: 0, salesTotal: 0, inventoryMovements: 0, integrations: 0, activeIntegrations: 0 });
    if (method !== "GET") throw new Error(`Unexpected mutation: ${method} ${path}`);
    return json([]);
  });
  await page.goto(base); await page.locator('input[autocomplete="username"]').fill(username);
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click(); await page.locator(".top-nav-list").waitFor();
  await nav(page, "Licencias activas"); await page.locator(".saas-data-table tbody tr").first().waitFor();
  return { page, context, calls, rows, setDelay: value => { delay = value; }, failDetail: value => { detailError = value; },
    failNextRenewal: () => { renewalError = true; }, holdGlobalRefresh: (fail = false) => {
      const held = { fail };
      held.requested = new Promise(resolve => { held.started = resolve; });
      held.released = new Promise(resolve => { held.release = resolve; });
      heldDashboard = held; return held;
    }, failNextPage: () => { nextError = true; }, holdNextPage: () => {
    const held = {};
    held.requested = new Promise(resolve => { held.started = resolve; });
    held.released = new Promise(resolve => { held.release = resolve; });
    held.done = new Promise(resolve => { held.finished = resolve; });
    heldNext = held; return held;
  } };
}
try {
  server = spawn(process.execPath, [fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url)), "--host", "127.0.0.1", "--port", "5188", "--strictPort", "--configLoader", "runner"], { cwd: root, stdio: "ignore", windowsHide: true });
  await waitFor(async () => { if (server.exitCode !== null) throw new Error("Vite failed to start"); try { return (await fetch(base)).ok; } catch { return false; } });
  browser = await chromium.launch({ headless: true });
  const { page, context, calls, rows: licenseRows, setDelay, failDetail, failNextRenewal, holdGlobalRefresh } = await setup();
  const table = page.locator(".saas-data-table"), headers = () => table.locator("thead th").evaluateAll(elements => elements.map(element => element.dataset.columnKey));
  assert.equal(await table.getByRole("button", { name: "Detalle", exact: true }).count(), 0);
  await page.getByRole("button", { name: "Ordenar por Referencia", exact: true }).click();
  await waitFor(() => calls.findLast(call => call.query.sortBy === "reference" && call.query.sortDirection === "ASC"));
  await page.getByRole("button", { name: "Ordenar por Referencia", exact: true }).click();
  await waitFor(() => calls.findLast(call => call.query.sortBy === "reference" && call.query.sortDirection === "DESC"));
  await waitFor(async () => (await table.locator("tbody tr").first().innerText()).includes("LIC-DEMO-3"));
  assert.equal(await table.locator('th[data-column-key="reference"]').getAttribute("aria-sort"), "descending");
  assert.equal(await table.locator('th[data-column-key="debt"] .saas-layout-sort').count(), 0, "Different currencies have no invented total sort");
  console.log("License table E2E: server sorting and multi-currency debt guard passed.");

  const status = table.locator('th[data-column-key="status"]'); await status.focus(); await page.keyboard.press("Control+ArrowLeft");
  assert.equal((await headers())[0], "status");
  await page.getByRole("button", { name: "Opciones de columna Estado", exact: true }).click();
  await page.getByRole("menuitemcheckbox", { name: "Días restantes", exact: true }).click();
  assert.equal((await headers()).includes("days"), false);
  await page.keyboard.press("Escape");
  const originalWidth = await status.evaluate(element => element.getBoundingClientRect().width);
  await status.getByRole("button", { name: "Cambiar ancho de Estado", exact: true }).focus(); await page.keyboard.press("ArrowRight");
  assert.ok(await status.evaluate(element => element.getBoundingClientRect().width) > originalWidth);
  await table.locator('th[data-column-key="reference"]').dragTo(status);
  assert.equal((await headers())[0], "reference");
  const savedHeaders = await headers();
  await page.reload();
  await page.locator('input[autocomplete="username"]').fill("LICENSE_DEMO");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click(); await page.locator(".top-nav-list").waitFor();
  await nav(page, "Licencias activas"); await page.locator(".saas-data-table tbody tr").first().waitFor();
  assert.deepEqual(await headers(), savedHeaders, "Column visibility and order survive reload");
  const persisted = await page.evaluate(() => Object.entries(localStorage).filter(([key]) => key.includes(":table:licenses:")));
  assert.equal(persisted.length, 1); assert.match(persisted[0][0], /license_demo/); assert.equal(persisted[0][1].includes("synthetic-token"), false);
  console.log("License table E2E: keyboard move, drag reorder, hide, resize and per-user persistence passed.");

  const globalRefresh = holdGlobalRefresh();
  await page.getByRole("button", { name: "Actualizar", exact: true }).click(); await globalRefresh.requested;
  licenseRows[0].maxWindows = 6; // Simulate a change after the list was loaded.
  failDetail(true);
  await table.locator("tbody tr").first().dblclick();
  const modal = page.locator("dialog.saas-license-dialog"); await modal.waitFor({ state: "visible" });
  assert.equal(await modal.getByRole("button", { name: "Cerrar", exact: true }).evaluate(element => document.activeElement === element), true);
  await modal.locator(".retry-error").waitFor();
  assert.equal(await modal.locator("form").count(), 0, "Failed license detail must not create an editable form using stale list data");
  failDetail(false); await modal.getByRole("button", { name: "Reintentar", exact: true }).click();
  await modal.getByLabel("Terminales Windows", { exact: true }).waitFor();
  assert.equal(await modal.getByLabel("Terminales Windows", { exact: true }).inputValue(), "6", "License details must come from a fresh GET, not the earlier table row");
  assert.ok(calls.some(call => call.method === "GET" && call.path === `/api/v1/admin/license-workspace/${licenseRows[0].id}`));
  await modal.getByLabel("Terminales Windows", { exact: true }).fill("4");
  await modal.getByLabel("Terminales PDA", { exact: true }).fill("2");
  const detailPath = `/api/v1/admin/license-workspace/${licenseRows[0].id}`;
  const detailReads = calls.filter(call => call.path === detailPath && call.method === "GET").length;
  const refreshedList = page.waitForResponse(response => new URL(response.url()).pathname === "/api/v1/admin/license-workspace");
  globalRefresh.release(); await refreshedList;
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.equal(await modal.getByLabel("Terminales Windows", { exact: true }).inputValue(), "4", "A delayed global refresh must preserve the open license draft");
  assert.equal(await modal.getByLabel("Terminales PDA", { exact: true }).inputValue(), "2");
  assert.equal(calls.filter(call => call.path === detailPath && call.method === "GET").length, detailReads, "Directory refresh must not reload the independently opened license profile");
  failNextRenewal(); await page.keyboard.press("Enter");
  await modal.getByRole("alert").waitFor();
  assert.equal(await modal.isVisible(), true, "A failed renewal POST must keep the modal open");
  assert.equal(await modal.getByLabel("Terminales Windows", { exact: true }).inputValue(), "4");
  assert.equal(licenseRows[0].maxWindows, 6, "Failed renewal must leave the persisted fixture unchanged");
  assert.equal(await modal.getByRole("button", { name: "Guardar", exact: true }).isEnabled(), true);
  const renewalsBeforeRetry = calls.filter(call => call.path.endsWith("/renew")).length;
  await modal.getByLabel("Terminales PDA", { exact: true }).focus();
  await page.keyboard.press("Enter");
  const saved = await waitFor(() => calls.filter(call => call.path.endsWith("/renew")).length > renewalsBeforeRetry && calls.findLast(call => call.path.endsWith("/renew")));
  assert.equal(saved.method, "POST");
  assert.equal(saved.body.maxWindows, 4); assert.equal(saved.body.maxPda, 2); assert.ok(saved.body.validUntil.endsWith("Z"));
  assert.equal(saved.body.validUntil, "2091-01-01T00:00:17.321Z", "Changing only quotas must preserve seconds and milliseconds of the existing expiry");
  await waitFor(async () => !(await modal.getByRole("button", { name: "Guardar", exact: true }).isDisabled()));
  page.once("dialog", dialog => dialog.accept());
  await modal.getByRole("button", { name: "Bloquear", exact: true }).click();
  await modal.getByRole("button", { name: "Desbloquear", exact: true }).waitFor();
  await waitFor(async () => !(await table.locator("tbody tr").allTextContents()).some(text => text.includes("LIC-DEMO-1")));
  assert.equal(await modal.isVisible(), true, "Blocking a license must retain its independently loaded modal even when it leaves the active filter");
  page.once("dialog", dialog => dialog.accept());
  await modal.getByRole("button", { name: "Desbloquear", exact: true }).click();
  await modal.getByRole("button", { name: "Bloquear", exact: true }).waitFor();
  await waitFor(async () => (await table.locator("tbody tr").allTextContents()).some(text => text.includes("LIC-DEMO-1")));
  const installationRow = modal.getByRole("row").filter({ hasText: "INST-LICENSE-DEMO" });
  const lastSyncBeforeRevocation = await installationRow.locator("td").nth(5).innerText();
  assert.ok(lastSyncBeforeRevocation.includes("2026"), "The fixture must show a received synchronization before revocation");
  const revokeRefresh = holdGlobalRefresh(true);
  page.once("dialog", dialog => dialog.accept("Revocación sintética de prueba"));
  await installationRow.getByRole("button", { name: /^Revocar/ }).click();
  await revokeRefresh.requested;
  await installationRow.getByText("Revocación sintética de prueba", { exact: true }).waitFor();
  assert.equal(await installationRow.getByRole("button", { name: /^Revocar/ }).count(), 0,
    "The canonical revoked installation must appear immediately while the dashboard refresh is still pending");
  const failedRefresh = page.waitForResponse(response => new URL(response.url()).pathname === "/api/v1/admin/licenses" && response.status() === 503);
  revokeRefresh.release(); await failedRefresh;
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.equal(await installationRow.getByRole("button", { name: /^Revocar/ }).count(), 0,
    "A failed dashboard refresh must not restore the old active installation in the modal");
  assert.equal(await installationRow.getByText("Revocación sintética de prueba", { exact: true }).isVisible(), true);
  assert.equal(await installationRow.locator("td").nth(5).innerText(), lastSyncBeforeRevocation,
    "A null lastSyncAt in the revocation response must preserve the known synchronization even when the dashboard refresh fails");
  console.log("License table E2E: canonical installation revocation remains visible during a pending and failed global refresh.");
  await modal.getByLabel("Caducidad", { exact: true }).click(); await page.locator(".date-time-popover").waitFor();
  await page.keyboard.press("Escape"); await page.locator(".date-time-popover").waitFor({ state: "hidden" });
  assert.equal(await modal.isVisible(), true, "First Escape closes date picker only");
  await page.keyboard.press("Escape"); await modal.waitFor({ state: "hidden" });
  const firstRow = table.locator("tbody tr").first(); await firstRow.focus(); await page.keyboard.press("Enter"); await modal.waitFor({ state: "visible" });
  await page.keyboard.press("Escape"); await modal.waitFor({ state: "hidden" });
  assert.equal(await firstRow.evaluate(element => document.activeElement === element), true, "Closing the modal returns keyboard focus to its row");
  console.log("License table E2E: fresh-detail retry, draft preserved on global refresh, failed-POST recovery, block outside the active filter, double-click/Enter modal, date-picker Escape and focus restoration passed.");

  const scroll = page.locator(".saas-data-table-scroll"); await scroll.evaluate(element => { element.scrollLeft = 500; });
  setDelay(350); const previousScroll = await scroll.evaluate(element => element.scrollLeft);
  await page.getByLabel("Buscar empresa, tienda o referencia", { exact: true }).fill("3500001");
  await page.locator('.saas-license-table-region[aria-busy="true"]').waitFor();
  assert.equal(await scroll.evaluate(element => element.scrollLeft), previousScroll);
  assert.equal(await table.locator("tbody tr").count(), 0, "Changing filters must remove stale rows while the new query loads");
  assert.equal(await modal.count(), 0, "Loading a new filter must not retain or reopen a stale modal");
  await page.locator('.saas-license-table-region[aria-busy="false"]').waitFor();
  assert.equal(await scroll.evaluate(element => element.scrollLeft), previousScroll);
  setDelay(0);
  await nav(page, "Crear licencia");
  const creation = page.locator("main");
  assert.equal(await creation.getByLabel("Referencia", { exact: true }).count(), 0);
  assert.equal(await creation.getByLabel("Caducidad", { exact: true }).count(), 0);
  assert.equal(await creation.locator('input[type="number"]').count(), 0);
  await creation.getByRole("combobox", { name: "Empresa", exact: true }).fill("sin licencia");
  await page.getByRole("option", { name: /Empresa sin licencia/ }).click();
  await page.getByRole("table", { name: "Tiendas de la empresa", exact: true }).getByRole("button", { name: "Generar código de activación", exact: true }).click();
  assert.deepEqual((await waitFor(() => calls.findLast(call => call.method === "POST" && call.path.endsWith("license-workspace")))).body, { storeId: newStore });
  await page.getByText("SYNTHETIC-ACTIVATION", { exact: true }).first().waitFor();
  console.log("License table E2E: stable loading table, stale-row guard and company-scoped store activation without a previous license passed.");

  await nav(page, "Licencias activas");
  await page.getByRole("button", { name: "Opciones de columna Referencia", exact: true }).click(); await page.getByRole("menuitem", { name: "Restablecer columnas", exact: true }).click();
  await table.locator("tbody tr").first().waitFor();
  const output = fileURLToPath(new URL("../../output/playwright/", import.meta.url)); await mkdir(output, { recursive: true });
  await page.screenshot({ path: `${output}/saas-license-table.png`, fullPage: true });
  await table.locator("tbody tr").first().dblclick(); await modal.waitFor({ state: "visible" });
  await page.screenshot({ path: `${output}/saas-license-configuration.png`, fullPage: true });
  await context.close();

  const viewer = await setup("LICENSE_VIEWER", ["VIEW_ADMIN_DATA"]);
  assert.equal(await viewer.page.locator('th[data-column-key="days"]').count(), 1, "Another account starts with its own columns");
  await viewer.page.locator(".saas-data-table tbody tr").first().dblclick();
  await viewer.page.locator("dialog.saas-license-dialog").waitFor({ state: "visible" });
  assert.equal(await viewer.page.locator("dialog.saas-license-dialog form").count(), 0);
  assert.equal(await viewer.page.locator("dialog.saas-license-dialog").getByRole("button", { name: /Bloquear|Generar|Guardar/ }).count(), 0);
  await viewer.context.close();
  await verifyContinuousDirectory();
  assert.deepEqual(errors, []);
  console.log("License table E2E: readonly permissions, isolated preferences and screenshots passed. All scenarios passed.");
} finally { if (browser) await browser.close(); if (server) server.kill(); }

async function verifyContinuousDirectory() {
  const fixture = await setup("LICENSE_SCROLL_DEMO", permissions, 82);
  const { page, context, calls, rows: records } = fixture;
  const output = fileURLToPath(new URL("../../output/playwright/", import.meta.url));
  const region = page.locator(".saas-license-table-region");
  const section = page.locator("section.content-section").filter({ has: region });
  const table = region.locator(".saas-data-table");
  const scroll = region.locator(".saas-data-table-scroll");
  const tableRows = table.locator("tbody tr");
  const toolbar = section.getByRole("search", { name: "Licencias activas", exact: true });
  const search = toolbar.getByLabel("Buscar empresa, tienda o referencia", { exact: true });
  const companyFilter = toolbar.getByRole("combobox", { name: "Empresa", exact: true });
  const statusFilter = toolbar.getByLabel(/^Estado/);
  const connections = toolbar.getByLabel(/^Con instalaciones activas/);
  const billing = toolbar.getByLabel(/^Facturación de la empresa/);
  const applied = toolbar.getByRole("group", { name: "Filtros aplicados", exact: true });
  const chip = label => applied.getByRole("button", { name: `Quitar filtro ${label}`, exact: true });
  const reads = () => calls.filter(call => call.path === "/api/v1/admin/license-workspace" && call.method === "GET");
  const references = () => tableRows.locator('td[data-column-key="reference"]').allTextContents();
  const waitRows = count => waitFor(async () => await tableRows.count() === count);
  const settle = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  const bottom = () => scroll.evaluate(element => { element.scrollTop = element.scrollHeight; });
  const chooseCompany = async (query, companyId, keyboard = false) => {
    const expected = records.find(row => row.companyId === companyId);
    const before = reads().length;
    await companyFilter.fill(query); await settle();
    assert.equal(reads().length, before, "Typing in the company picker must not discard or replace the applied filter");
    const option = toolbar.getByRole("option", { name: new RegExp(`${expected.companyName}.*${expected.taxId}`) });
    await option.waitFor();
    if (keyboard) {
      const id = await option.getAttribute("id");
      for (let index = 0; await companyFilter.getAttribute("aria-activedescendant") !== id && index < await toolbar.getByRole("option").count(); index++) await companyFilter.press("ArrowDown");
      assert.equal(await companyFilter.getAttribute("aria-activedescendant"), id);
      await companyFilter.press("Enter");
    } else await option.click();
    assert.equal(await companyFilter.inputValue(), `${expected.companyName} · ${expected.taxId}`);
  };
  const loadAll = async count => {
    for (let attempt = 0; await tableRows.count() < count && attempt < 5; attempt++) {
      const previous = await tableRows.count(); await bottom();
      await waitFor(async () => await tableRows.count() > previous);
    }
    assert.equal(await tableRows.count(), count);
  };
  const filter = async (action, count, params) => {
    const first = reads().length; await action();
    await waitFor(() => reads().length > first); await waitRows(count); await settle();
    assert.equal(reads()[first].query.page, "0", "Every changed license filter must restart page zero");
    for (const [key, value] of Object.entries(params)) assert.equal(reads()[first].query[key] ?? "", value);
  };
  try {
    await waitRows(25); await settle();
    assert.equal(await connections.count(), 0, "Advanced license filters must start collapsed");
    assert.equal(await billing.count(), 0);
    assert.equal(await toolbar.getByLabel("Caduca antes de", { exact: true }).count(), 0);
    assert.equal(await applied.getByRole("button", { name: /^Quitar filtro / }).count(), 1);
    assert.match(await chip("Estado").locator("..").innerText(), /Licencias activas/);
    assert.equal(await section.getByRole("button", { name: "Columnas", exact: true }).count(), 0);
    assert.ok(reads().every(call => call.query.page === "0"), "Entering the license directory must not eagerly fetch its complete history");
    assert.equal(await section.locator(".pagination-controls").count(), 0, "The license directory must use continuous scrolling without page controls");
    const initial = await references(); const firstPageCalls = reads().length;
    fixture.failNextPage(); await bottom();
    await section.locator(".retry-error").waitFor(); await settle();
    assert.deepEqual(await references(), initial, "Failure on a following page must preserve the visible licenses");
    assert.deepEqual(reads().slice(firstPageCalls).map(call => call.query.page), ["1"], "Scrolling must not automatically retry a failed request");
    await section.getByRole("button", { name: "Reintentar", exact: true }).click(); await waitRows(50);
    assert.deepEqual(reads().slice(firstPageCalls).map(call => call.query.page), ["1", "1"]);
    assert.deepEqual((await references()).slice(0, 25), initial);

    for (const viewport of [{ width: 1280, height: 900 }, { width: 1600, height: 1000 }, { width: 1366, height: 768 }]) {
      await page.setViewportSize(viewport); await settle(); await loadAll(82);
      assert.equal(new Set(await references()).size, 82, "Incremental pages must not duplicate licenses");
      await scroll.evaluate(element => { element.scrollTop = 0; element.scrollLeft = 0; }); await settle();
      const before = await licenseGeometry(page); assertLicenseGeometry(before, viewport);
      const heading = table.locator("thead th").first(); const headingBefore = await heading.boundingBox();
      const toolbarBefore = await toolbar.boundingBox();
      await bottom(); await settle();
      const after = await licenseGeometry(page); assertLicenseGeometry(after, viewport);
      const headingAfter = await heading.boundingBox(); const last = await tableRows.last().boundingBox();
      assert.ok(Math.abs(headingAfter.y - headingBefore.y) <= 1, "License headings must remain fixed while the rows scroll");
      assert.ok(last.y >= after.scroll.top && last.y + last.height <= after.scroll.bottom + 1, "The final license must be fully visible inside the table viewport");
      assert.deepEqual(await toolbar.boundingBox(), toolbarBefore, "License filters must remain in place during table scrolling");
      assert.ok(toolbarBefore.y >= 0 && toolbarBefore.y + toolbarBefore.height <= viewport.height, "License filters must remain visible");
      await page.mouse.move(after.scroll.left + 80, after.scroll.bottom - 40); await page.mouse.wheel(0, 1200); await settle();
      assertLicenseGeometry(await licenseGeometry(page), viewport);
      await page.screenshot({ path: `${output}/saas-licenses-scroll-${viewport.width}x${viewport.height}.png`, animations: "disabled" });
      await filter(() => search.fill("pareja"), 2, { q: "pareja" });
      const few = await licenseGeometry(page); assertLicenseGeometry(few, viewport);
      assert.ok(Math.abs(few.scroll.bottom - before.scroll.bottom) <= 2, "The two-row license table must retain its available height");
      await page.screenshot({ path: `${output}/saas-licenses-few-${viewport.width}x${viewport.height}.png`, animations: "disabled" });
      await filter(() => search.fill("NO-LICENSE-FOUND"), 0, { q: "NO-LICENSE-FOUND" });
      await section.getByText("SIN DATOS", { exact: true }).waitFor();
      assertLicenseGeometry(await licenseGeometry(page), viewport, true);
      await page.screenshot({ path: `${output}/saas-licenses-empty-${viewport.width}x${viewport.height}.png`, animations: "disabled" });
      await filter(() => search.fill(""), 25, { q: "" });
    }

    await table.getByRole("button", { name: "Ordenar por Referencia", exact: true }).click(); await waitRows(25); await settle();
    assert.equal(reads().at(-1).query.sortDirection, "ASC");
    const sortStart = reads().length;
    await table.getByRole("button", { name: "Ordenar por Referencia", exact: true }).click(); await waitRows(25); await settle();
    assert.equal(reads()[sortStart].query.page, "0");
    assert.equal((await references())[0], "LIC-SCROLL-082", "Server sorting must include licenses outside the previously loaded page");
    await loadAll(82);
    assert.deepEqual(await references(), records.map(row => row.reference).reverse());
    assert.ok(reads().slice(sortStart).every(call => call.query.sortBy === "reference" && call.query.sortDirection === "DESC"));

    await filter(() => search.fill("pareja"), 2, { q: "pareja" });
    await filter(() => search.fill(""), 25, { q: "" });
    const held = fixture.holdNextPage(); await bottom(); await held.requested;
    await filter(() => search.fill("pareja"), 2, { q: "pareja" });
    const latest = await references(); held.release(); await held.done; await settle();
    assert.deepEqual(await references(), latest, "A delayed page from an old filter must not replace or append to current license results");
    await filter(() => search.fill(""), 25, { q: "" });
    assert.equal(await companyFilter.evaluate(element => element.required), false);
    await filter(() => chooseCompany("B00000000", company, true), 25, { companyId: company }); await loadAll(41);
    const selectedCompanyLabel = await companyFilter.inputValue(); const beforeCancel = reads().length;
    for (const cancellation of ["Escape", "Tab", "outside"]) {
      await companyFilter.fill("sintetica b");
      await toolbar.getByRole("option", { name: /Empresa sintética B.*B00000001/ }).waitFor();
      assert.match(await chip("Empresa").locator("..").innerText(), /Empresa sintética/);
      if (cancellation === "outside") await search.click(); else await companyFilter.press(cancellation);
      await settle();
      assert.equal(await companyFilter.inputValue(), selectedCompanyLabel, `${cancellation} must restore the applied company label`);
      assert.equal(await companyFilter.getAttribute("aria-expanded"), "false");
      assert.equal(reads().length, beforeCancel);
      assert.equal(await tableRows.count(), 41, "Cancelling company lookup must retain loaded directory pages");
    }
    await toolbar.getByRole("button", { name: "+ Más filtros", exact: true }).click();
    await filter(() => connections.selectOption("false"), 21, { companyId: company, status: "VALIDA", hasConnections: "false" });
    await filter(() => billing.selectOption("PAGADO"), 0, { companyId: company, hasConnections: "false", billingStatus: "PAGADO" });
    await filter(() => billing.selectOption("PENDIENTE"), 21, { companyId: company, hasConnections: "false", billingStatus: "PENDIENTE" });
    await filter(() => search.fill("pareja"), 1, { companyId: company, status: "VALIDA", hasConnections: "false", billingStatus: "PENDIENTE", q: "pareja" });
    assert.equal(await applied.getByRole("button", { name: /^Quitar filtro / }).count(), 5);
    assert.match(await chip("Empresa").locator("..").innerText(), /Empresa sintética/);
    const beforeDisclosure = reads().length;
    await toolbar.getByRole("button", { name: "− Menos filtros", exact: true }).click(); await settle();
    assert.equal(await connections.count(), 0);
    assert.equal(await chip("Instalaciones").isVisible(), true);
    assert.equal(await chip("Facturación de la empresa").isVisible(), true);
    assert.equal(reads().length, beforeDisclosure, "Collapsing advanced filters must not clear their server query");
    for (const viewport of [{ width: 1280, height: 900 }, { width: 1600, height: 1000 }]) {
      await page.setViewportSize(viewport); await settle(); assertLicenseGeometry(await licenseGeometry(page), viewport);
      await page.screenshot({ path: `${output}/saas-licenses-filters-${viewport.width}.png`, animations: "disabled" });
      await companyFilter.click(); await toolbar.getByRole("listbox", { name: "Empresa", exact: true }).waitFor();
      await page.screenshot({ path: `${output}/saas-licenses-company-picker-${viewport.width}.png`, animations: "disabled" });
      await companyFilter.press("Escape");
    }
    await page.setViewportSize({ width: 1366, height: 768 }); await settle();
    await filter(() => chip("Buscar empresa, tienda o referencia").click(), 21, { companyId: company, status: "VALIDA", hasConnections: "false", billingStatus: "PENDIENTE", q: "" });
    await toolbar.getByRole("button", { name: "+ Más filtros", exact: true }).click();
    assert.equal(await connections.inputValue(), "false"); assert.equal(await billing.inputValue(), "PENDIENTE");
    await filter(() => connections.selectOption("true"), 20, { hasConnections: "true" });
    await filter(() => chip("Instalaciones").click(), 25, { companyId: company, status: "VALIDA", hasConnections: "", billingStatus: "PENDIENTE" });
    await filter(() => chip("Facturación de la empresa").click(), 25, { companyId: company, billingStatus: "" });
    await filter(() => chooseCompany("sintetica b", newCompany), 25, { companyId: newCompany });
    assert.ok((await tableRows.locator('td[data-column-key="company"]').allTextContents()).every(value => value.includes("Empresa sintética B")));
    await filter(() => chip("Empresa").click(), 25, { companyId: "" });
    records.slice(-2).forEach(row => { row.status = "BLOQUEADA_MANUAL"; });
    await filter(() => statusFilter.selectOption("BLOQUEADA_MANUAL"), 2, { status: "BLOQUEADA_MANUAL" });
    await filter(() => chip("Estado").click(), 25, { status: "" });
    assert.equal(await applied.count(), 0, "Removing the state chip must select all statuses rather than restoring VALIDA");
    await filter(() => statusFilter.selectOption("VALIDA"), 25, { status: "VALIDA" });
    const expiryStart = reads().length;
    await toolbar.getByLabel("Caduca antes de", { exact: true }).click();
    const calendar = page.getByRole("dialog", { name: "Caduca antes de", exact: true });
    await calendar.getByRole("button", { name: "Hoy", exact: true }).click();
    await calendar.getByRole("button", { name: "Cerrar calendario", exact: true }).click();
    await waitRows(0); await waitFor(() => reads().length > expiryStart);
    assert.equal(reads()[expiryStart].query.page, "0"); assert.ok(reads()[expiryStart].query.expiresBefore.endsWith("Z"));
    await filter(() => chip("Caduca antes de").click(), 25, { expiresBefore: "", status: "VALIDA" });
    await toolbar.getByLabel("Caduca antes de", { exact: true }).click();
    await calendar.getByRole("button", { name: "Hoy", exact: true }).click();
    await calendar.getByRole("button", { name: "Cerrar calendario", exact: true }).click(); await waitRows(0);
    await filter(() => chooseCompany("B00000000", company), 0, { companyId: company });
    await filter(() => connections.selectOption("false"), 0, { hasConnections: "false" });
    await filter(() => billing.selectOption("PENDIENTE"), 0, { billingStatus: "PENDIENTE" });
    await filter(() => search.fill("pareja"), 0, { q: "pareja" });
    assert.equal(await applied.getByRole("button", { name: /^Quitar filtro / }).count(), 6);
    for (const viewport of [{ width: 1366, height: 768 }, { width: 1280, height: 720 }]) {
      await page.setViewportSize(viewport); await settle();
      assertLicenseGeometry(await licenseGeometry(page), viewport, true);
      const bounds = await toolbar.boundingBox();
      assert.ok(bounds.y >= 0 && bounds.y + bounds.height <= viewport.height, "All six filter chips and expanded controls must fit in a short viewport");
      await page.screenshot({ path: `${output}/saas-licenses-filters-expanded-${viewport.width}x${viewport.height}.png`, animations: "disabled" });
    }
    await filter(() => applied.getByRole("button", { name: "Limpiar todos", exact: true }).click(), 25,
      { q: "", companyId: "", status: "", hasConnections: "", billingStatus: "", expiresBefore: "" });
    assert.equal(await applied.count(), 0);
    for (const control of [search, companyFilter, statusFilter, connections, billing, toolbar.getByLabel("Caduca antes de", { exact: true })]) assert.equal(await control.inputValue(), "");
    await loadAll(82);
    assert.ok((await references()).includes("LIC-SCROLL-082"), "Clear all must include the license with a blocked status");
    await filter(() => chooseCompany("sintetica b", newCompany, true), 25, { companyId: newCompany, status: "" });
    await filter(async () => {
      await companyFilter.click(); await toolbar.getByRole("listbox", { name: "Empresa", exact: true }).getByRole("option", { name: "Todas", exact: true }).click();
    }, 25, { companyId: "", status: "" });
    assert.equal(await applied.count(), 0, "Todas must remove the company filter without restoring default VALIDA");
    const summary = "License directory E2E passed: optional name/NIF company picker and keyboard selection, Escape/Tab/outside cancellation preserving applied company, Todas/chips/clear-all including default VALIDA, server-side AND filtering, advanced disclosure preservation, paginated API/lazy loading/error/retry, 82/2/0 rows at three viewports, fixed controls and sticky headers, global sorting, stale-response isolation and 1280/1600 captures.";
    assert.deepEqual(errors, []);
    console.log(summary);
    await writeFile(`${output}/license-table-scroll.log`, `Command: node e2e/license-table-smoke.mjs\nExit code: 0\nSynthetic API fixtures only; no real backend data.\n${summary}\n`);
  } finally { await context.close(); }
}

async function licenseGeometry(page) {
  return page.evaluate(() => {
    const read = element => {
      const rect = element.getBoundingClientRect(); const css = getComputedStyle(element);
      return { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom,
        innerLeft: rect.left + parseFloat(css.borderLeftWidth) + parseFloat(css.paddingLeft),
        innerRight: rect.right - parseFloat(css.borderRightWidth) - parseFloat(css.paddingRight),
        innerBottom: rect.bottom - parseFloat(css.borderBottomWidth) - parseFloat(css.paddingBottom),
        scrollHeight: element.scrollHeight, clientHeight: element.clientHeight, scrollWidth: element.scrollWidth, clientWidth: element.clientWidth,
        scrollTop: element.scrollTop, scrollLeft: element.scrollLeft };
    };
    const region = document.querySelector(".saas-license-table-region");
    return { main: read(document.querySelector("main")), section: read(region.closest("section.content-section")), region: read(region),
      scroll: read(region.querySelector(".saas-data-table-scroll")), document: read(document.scrollingElement) };
  });
}
function assertLicenseGeometry(geometry, viewport, empty = false) {
  const size = `${viewport.width}x${viewport.height}`;
  for (const key of ["main", "document"]) {
    const element = geometry[key];
    assert.ok(element.scrollHeight <= element.clientHeight + 1 && element.scrollWidth <= element.clientWidth + 1,
      `${key} must not overflow the license list at ${size}: ${JSON.stringify(element)}`);
    assert.ok(element.scrollTop <= 1 && element.scrollLeft <= 1, `${key} must not move with license rows at ${size}`);
  }
  assert.ok(Math.abs(geometry.scroll.left - geometry.section.innerLeft) <= 2 && Math.abs(geometry.scroll.right - geometry.section.innerRight) <= 2,
    `License table must fill available width at ${size}: ${JSON.stringify(geometry)}`);
  assert.ok(Math.abs(geometry.section.bottom - geometry.main.innerBottom) <= 2 && Math.abs(geometry.region.bottom - geometry.section.innerBottom) <= 2,
    `License region must reach the available lower edge at ${size}: ${JSON.stringify(geometry)}`);
  if (!empty) assert.ok(Math.abs(geometry.scroll.bottom - geometry.region.bottom) <= 2, `License scroll container must fill its region at ${size}: ${JSON.stringify(geometry)}`);
  assert.ok(geometry.scroll.clientHeight > 100, `License table must retain usable height at ${size}`);
}
