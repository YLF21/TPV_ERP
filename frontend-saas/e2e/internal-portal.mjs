import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import { mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
let playwright;
try { playwright = require("playwright"); }
catch { playwright = createRequire(new URL("../../frontend/package.json", import.meta.url))("playwright"); }

const baseUrl = "http://127.0.0.1:5187/";
const root = fileURLToPath(new URL("..", import.meta.url));
const vite = fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url));
const adminLogin = "/api/v1/auth/admin/login";
const passwordChange = "/api/v1/auth/password/change";
const logout = "/api/v1/auth/logout";
const initialPassword = "synthetic-initial-password";
const nextPassword = "synthetic-next-password";
const errors = [];
const screenshotDirectory = fileURLToPath(new URL("../../output/playwright/", import.meta.url));
const permissions = ["VIEW_ADMIN_DATA", "ADD_COMPANY", "MANAGE_TENANT_USERS", "MANAGE_BILLING", "MANAGE_OPERATIONS", "VIEW_REPORTS", "MANAGE_FISCAL_POLICY", "MANAGE_OPERATIONAL_INCIDENTS"];
let server;
let browser;

function session(mode, passwordChangeRequired = false, accessToken = `${mode}-synthetic-token`) {
  return { username: "INTERNAL_E2E", accessToken, mode, passwordChangeRequired, expiresAt: "2099-01-01T00:00:00Z" };
}

async function start() {
  server = spawn(process.execPath, [vite, "--host", "127.0.0.1", "--port", "5187", "--strictPort", "--configLoader", "runner"], { cwd: root, stdio: "ignore", windowsHide: true });
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error("Vite internal portal E2E did not start");
    try { if ((await fetch(baseUrl)).ok) return; } catch { /* bounded startup polling */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("Vite internal portal E2E startup timed out");
}

async function setup(loginResponses, { adminPermissions = permissions } = {}) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on("pageerror", error => errors.push(error.message));
  const calls = [];
  const unexpected = [];
  let loginCount = 0;
  await page.addInitScript(() => {
    window.__internalPortalViews = { tenant: false, passwordGate: false };
    const observe = () => {
      window.__internalPortalViews.tenant ||= Boolean(document.querySelector(".tenant-shell"));
      window.__internalPortalViews.passwordGate ||= Boolean(document.querySelector("#password-change-title"));
    };
    new MutationObserver(observe).observe(document, { childList: true, subtree: true });
  });
  await page.route("**/api/v1/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const call = { path, method: request.method(), body: request.postData() ? JSON.parse(request.postData()) : null, authorization: request.headers().authorization };
    calls.push(call);
    const json = body => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (path === adminLogin && call.method === "POST") {
      const response = loginResponses[loginCount++];
      if (!response) throw new Error("Unexpected extra login");
      return json(response);
    }
    if ([passwordChange, logout].includes(path) && call.method === "POST") return route.fulfill({ status: 204 });
    if (call.method === "GET") {
      if (path === "/api/v1/admin/me") return json({ username: "INTERNAL_E2E", permissions: adminPermissions });
      if (path === "/api/v1/admin/sync/sales-summary") return json({ documentCount: 0, total: "0.00" });
      if (path === "/api/v1/admin/reports/advanced") return json({ companies: 0, invoices: 0, invoicedTotal: "0.00", paidTotal: "0.00", salesDocuments: 0, salesTotal: "0.00", inventoryMovements: 0, integrations: 0, activeIntegrations: 0 });
      if (path === "/api/v1/admin/billing-summary") return json({ totalCompanies: 0, paidCompanies: 0, pendingCompanies: 0, overdueCompanies: 0, renewalsNext30Days: 0, monthlyRecurringRevenue: "0.00", companies: [] });
      if (path === "/api/v1/admin/verifactu-activation-policies") return json([{ taxpayerType: "SOCIEDAD", activationDate: "2099-01-01", version: 1, activeLicenses: 0, linkedInstallations: 0, updatedBy: "SYNTHETIC", updatedAt: "2026-09-20T00:00:00Z", reason: "Synthetic E2E policy" }]);
      if (["licenses", "installations", "users", "audit", "sync/events", "fiscal-status", "fiscal-status/companies", "companies"].some(resource => path === `/api/v1/admin/${resource}`)) return json([]);
    }
    unexpected.push(`${call.method} ${path}`);
    return route.fulfill({ status: 404, contentType: "application/problem+json", body: JSON.stringify({ detail: `Unexpected E2E request: ${path}` }) });
  });
  await page.goto(baseUrl, { waitUntil: "domcontentloaded" });
  return { page, calls, unexpected };
}

async function login(page, password = initialPassword) {
  await page.locator('input[autocomplete="username"]').fill("INTERNAL_E2E");
  await page.locator('input[autocomplete="current-password"]').fill(password);
  await page.locator('form button[type="submit"]').click();
}

function assertNoTenantRequests(calls) {
  assert.equal(calls.some(call => call.path.startsWith("/api/v1/tenant/")), false, "Internal portal must not request tenant data");
  assert.equal(calls.some(call => call.path === "/api/v1/auth/login"), false, "Internal portal must use the admin realm endpoint");
}

async function changeRequiredPassword(page) {
  const fields = page.locator('.login-panel input[autocomplete="new-password"]');
  await fields.nth(0).fill(nextPassword);
  await fields.nth(1).fill(nextPassword);
  await page.locator('.login-panel button[type="submit"]').click();
}

try {
  await start();
  browser = await playwright.chromium.launch({ headless: true });

  for (const required of [false, true]) {
    const { page, calls, unexpected } = await setup([session("tenant", required)]);
    await login(page);
    await page.locator('.login-panel [role="alert"]').waitFor();
    assert.equal(await page.locator('input[autocomplete="current-password"]').isVisible(), true);
    assert.equal(await page.locator(".app-shell, .tenant-shell, #password-change-title").count(), 0);
    assert.deepEqual(await page.evaluate(() => window.__internalPortalViews), { tenant: false, passwordGate: false });
    assert.equal(calls.some(call => call.path.startsWith("/api/v1/admin/")), false, "Rejected tenant realm must not fetch admin data");
    assert.equal(calls.some(call => call.path === passwordChange), false);
    assertNoTenantRequests(calls);
    assert.deepEqual(unexpected, []);
    await page.close();
  }
  console.log("Internal portal E2E: tenant responses rejected before data loading or mandatory-password UI.");

  const mandatory = await setup([session("admin", true, "pending-admin-token"), session("admin", false, "renewed-admin-token")]);
  await login(mandatory.page);
  await mandatory.page.locator("#password-change-title").waitFor();
  assert.equal(mandatory.calls.some(call => call.path.startsWith("/api/v1/admin/")), false);
  const fields = mandatory.page.locator('.login-panel input[autocomplete="new-password"]');
  await fields.nth(0).fill(nextPassword);
  await fields.nth(1).fill("different-password");
  await mandatory.page.locator('.login-panel button[type="submit"]').click();
  await mandatory.page.locator('[role="alert"]').waitFor();
  assert.equal(mandatory.calls.some(call => call.path === passwordChange), false);
  await changeRequiredPassword(mandatory.page);
  await mandatory.page.locator(".saas-dashboard").waitFor();
  const changed = mandatory.calls.find(call => call.path === passwordChange);
  assert.deepEqual(changed.body, { currentPassword: initialPassword, newPassword: nextPassword });
  assert.equal(changed.authorization, "Bearer pending-admin-token");
  assert.deepEqual(mandatory.calls.filter(call => call.path === adminLogin).map(call => call.body.password), [initialPassword, nextPassword]);
  assert.equal(mandatory.calls.filter(call => call.path.startsWith("/api/v1/admin/")).every(call => call.authorization === "Bearer renewed-admin-token"), true);
  assertNoTenantRequests(mandatory.calls);
  assert.deepEqual(mandatory.unexpected, []);
  await mandatory.page.locator(".session-logout").click();
  await mandatory.page.locator('input[autocomplete="current-password"]').waitFor();
  await mandatory.page.close();
  console.log("Internal portal E2E: mandatory admin password validation, reauthentication and logout passed.");

  const wrongReauthentication = await setup([session("admin", true), session("tenant", true)]);
  await login(wrongReauthentication.page);
  await wrongReauthentication.page.locator("#password-change-title").waitFor();
  await changeRequiredPassword(wrongReauthentication.page);
  await wrongReauthentication.page.locator('[role="alert"]').waitFor();
  assert.equal(await wrongReauthentication.page.locator(".app-shell, .tenant-shell").count(), 0);
  assert.equal(await wrongReauthentication.page.evaluate(() => window.__internalPortalViews.tenant), false);
  assert.equal(wrongReauthentication.calls.some(call => call.path.startsWith("/api/v1/admin/")), false);
  assertNoTenantRequests(wrongReauthentication.calls);
  assert.deepEqual(wrongReauthentication.unexpected, []);
  await wrongReauthentication.page.close();
  console.log("Internal portal E2E: tenant reauthentication after a password change cannot open either workspace.");

  const internal = await setup([session("admin")]);
  await mkdir(screenshotDirectory, { recursive: true });
  await internal.page.setViewportSize({ width: 1280, height: 900 });
  await internal.page.screenshot({ path: `${screenshotDirectory}/saas-internal-login.png`, animations: "disabled" });
  await login(internal.page);
  await internal.page.locator(".saas-dashboard").waitFor();
  await assertNavigation(internal.page, internal.calls);
  await captureNavigation(internal.page);
  const account = internal.page.locator(".account-password");
  await account.locator("summary").click();
  await account.locator('input[autocomplete="current-password"]').fill(initialPassword);
  const ownFields = account.locator('input[autocomplete="new-password"]');
  await ownFields.nth(0).fill(nextPassword);
  await ownFields.nth(1).fill(nextPassword);
  await account.getByRole("button").click();
  await internal.page.locator('.login-panel input[autocomplete="current-password"]').waitFor();
  assert.equal(await internal.page.locator(".app-shell, .tenant-shell").count(), 0);
  assert.deepEqual(internal.calls.find(call => call.path === passwordChange).body, { currentPassword: initialPassword, newPassword: nextPassword });
  assertNoTenantRequests(internal.calls);
  assert.deepEqual(internal.unexpected, []);
  await internal.page.close();
  assert.deepEqual(errors, []);
  console.log("Internal portal E2E: internal navigation and voluntary password change passed.");
} finally {
  if (browser) await browser.close();
  if (server) server.kill();
}

async function captureNavigation(page) {
  const sidebar = page.locator(".top-nav-list");
  const buttons = sidebar.getByRole("button");
  for (const width of [1600, 1280]) {
    await page.setViewportSize({ width, height: 900 });
    await buttons.first().focus();
    for (let index = 1; index < await buttons.count(); index++) await page.keyboard.press("Tab");
    const last = buttons.last();
    assert.equal(await last.evaluate(element => element === document.activeElement), true, "All menu items must remain reachable by keyboard");
    assert.equal(await last.evaluate(element => {
      const rect = element.getBoundingClientRect();
      return rect.x >= 0 && rect.y >= 0 && rect.right <= innerWidth && rect.bottom <= innerHeight
        && element.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2));
    }), true, `Last menu item must be visible and clickable after scrolling at ${width}px`);
    const scroll = await sidebar.evaluate(element => ({ top: element.scrollTop, height: element.scrollHeight, viewport: element.clientHeight }));
    if (scroll.height > scroll.viewport) assert.ok(scroll.top > 0, "Keyboard navigation must scroll overflowing menu items into view");
    await page.keyboard.press("Enter");
    await page.waitForFunction(() => location.hash === "#/audit");
    await sidebar.getByRole("button", { name: "Estado fiscal", exact: true }).click();
    await page.waitForFunction(() => location.hash === "#/fiscal");
    await page.locator("main .module-help").waitFor();
    await sidebar.locator(".nav-group").filter({ has: page.locator(".nav-group-title").filter({ hasText: /^Supervisión$/ }) }).evaluate(group => {
      const menu = group.parentElement;
      menu.scrollTop += group.getBoundingClientRect().top - menu.getBoundingClientRect().top;
    });
    assert.equal(await sidebar.locator(".nav-phase-title").last().evaluate(element => {
      const bounds = element.getBoundingClientRect();
      const menu = element.closest(".top-nav-list").getBoundingClientRect();
      return bounds.top >= menu.top && bounds.bottom <= menu.bottom;
    }), true, "The supervision phase overview must fit within the scrolled menu");
    await page.mouse.move(width - 40, 150);
    await page.screenshot({ path: `${screenshotDirectory}/saas-internal-navigation-${width}.png`, animations: "disabled" });
  }
  console.log("Internal portal E2E: menu keyboard scrolling at 1600/1280px and synthetic login/navigation screenshots passed.");
}

async function assertNavigation(page, calls) {
  const sidebar = page.locator(".top-nav-list");
  const ownCompany = sidebar.locator(".nav-group").filter({ has: page.locator(".nav-group-title").filter({ hasText: /^Mi empresa$/ }) });
  const clients = sidebar.locator(".nav-group").filter({ has: page.locator(".nav-group-title").filter({ hasText: /^Clientes$/ }) });
  const supervision = sidebar.locator(".nav-group").filter({ has: page.locator(".nav-group-title").filter({ hasText: /^Supervisión$/ }) });
  const technical = sidebar.locator(".nav-group").filter({ has: page.locator(".nav-group-title").filter({ hasText: /^Configuración técnica$/ }) });
  assert.equal(await ownCompany.getByRole("button", { name: /^Facturaci[oó]n$/ }).count(), 1);
  assert.equal(await clients.getByRole("button", { name: /^(Maestros|Operaciones|Facturaci[oó]n)$/ }).count(), 0);
  assert.equal(await sidebar.getByRole("button", { name: /^(Maestros|Operaciones)$/ }).count(), 0);
  assert.deepEqual(await supervision.locator(".nav-phase-title").allTextContents(), ["1. Revisar estado", "2. Detectar fallos", "3. Diagnosticar", "4. Recuperar entregas", "5. Atender al cliente"]);
  assert.deepEqual(await supervision.evaluate(element => {
    const phases = [];
    for (const child of element.children) {
      if (child.classList.contains("nav-phase-title")) phases.push({ title: child.textContent.trim(), modules: [] });
      if (child.tagName === "BUTTON") phases.at(-1).modules.push(child.textContent.trim());
    }
    return phases;
  }), [
    { title: "1. Revisar estado", modules: ["Estado de clientes"] },
    { title: "2. Detectar fallos", modules: ["Fallos de tiendas"] },
    { title: "3. Diagnosticar", modules: ["Sincronizacion", "Estado fiscal"] },
    { title: "4. Recuperar entregas", modules: ["Recuperación de entregas"] },
    { title: "5. Atender al cliente", modules: ["Soporte"] },
  ]);
  await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === "/api/v1/admin/billing-summary"),
    ownCompany.getByRole("button", { name: /^Facturaci[oó]n$/ }).click(),
  ]);
  await page.waitForFunction(() => location.hash === "#/billing");
  assert.equal(await page.locator(".topbar .eyebrow").textContent(), "Mi empresa");
  assert.ok(await page.locator(".module-help").isVisible());
  await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === "/api/v1/admin/fiscal-status/companies"),
    supervision.getByRole("button", { name: "Estado fiscal", exact: true }).click(),
  ]);
  await page.waitForFunction(() => location.hash === "#/fiscal");
  assert.equal(await page.locator(".verifactu-policy-section").count(), 0, "Fiscal supervision must remain read only and separate from policy configuration");
  assert.equal(calls.some(call => call.path === "/api/v1/admin/verifactu-activation-policies"), false);
  assert.equal(await page.locator("main").getByRole("button", { name: "Actualizar politica", exact: true }).count(), 0);
  await technical.getByRole("button", { name: "Activacion global de VeriFactu", exact: true }).click();
  await page.waitForFunction(() => location.hash === "#/fiscal-policy");
  await page.locator(".verifactu-policy-section").getByRole("button", { name: "Actualizar politica", exact: true }).waitFor();
  assert.ok(calls.some(call => call.path === "/api/v1/admin/verifactu-activation-policies"));
  for (const removed of ["masters", "operations"]) {
    await page.evaluate(view => { location.hash = `#/${view}`; }, removed);
    await page.locator(".topbar").getByRole("heading", { name: "Resumen", exact: true }).waitFor();
    assert.equal(await page.locator("main .verifactu-policy-section").count(), 0);
    assert.equal(calls.some(call => call.path.includes("/erp/")), false, "Retired ERP routes must not load operational customer data");
  }
}
