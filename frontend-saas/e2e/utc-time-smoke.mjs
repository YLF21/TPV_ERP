import assert from "node:assert/strict";
import { emptySummary, summaryPath } from "./summary-fixture.mjs";
import { spawn } from "node:child_process";
import { mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const playwright = await import("playwright").catch(() => import("../../frontend/node_modules/playwright/index.mjs"));
const root = fileURLToPath(new URL("..", import.meta.url));
const vite = fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url));
const baseUrl = "http://127.0.0.1:5203/";
const fixedInstant = "2026-07-01T00:34:56.000Z";
const screenshotDirectory = fileURLToPath(new URL("../../output/playwright/", import.meta.url));
const originalExpiry = "2026-07-15T12:34:17.123Z";
const selectedExpiry = "2026-07-16T07:08:00.000Z";
const address = { linea1: "Calle de Prueba 1", ciudad: "Las Palmas", codigoPostal: "35001", provincia: "Las Palmas", pais: "ES" };
const company = { companyId: "utc-company", companyName: "Sociedad sintética", taxId: "B00000001" };
const store = {
  id: "utc-store", companyId: company.companyId, companyName: company.companyName,
  code: "001", internalCode: "3500001", name: "Tienda sintética", active: true,
  storeAddress: address, timeZoneId: "Atlantic/Canary", installations: 0, activeInstallations: 0,
  lastSyncAt: fixedInstant, createdAt: fixedInstant, taxRegime: "IGIC", commercialProfile: "MINORISTA",
  taxRegimeLocked: false, servicePrice: "29.00", billingPeriod: "MONTHLY",
  maxWindows: 1, maxPda: 0, validUntil: originalExpiry,
};
const server = spawn(process.execPath, [vite, "--host", "127.0.0.1", "--port", "5203", "--strictPort", "--configLoader", "runner"],
  { cwd: root, stdio: "ignore", windowsHide: true });
let browser;

async function start() {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error("UTC smoke: Vite exited before startup");
    try { if ((await fetch(baseUrl)).ok) return; } catch { /* bounded startup retry */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("UTC smoke: Vite startup timed out");
}

async function runZone(zone) {
  const context = await browser.newContext({ timezoneId: zone, viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const pageErrors = [];
  const writes = [];
  page.on("pageerror", error => pageErrors.push(error.message));
  let currentStore = structuredClone(store);
  await page.clock.install({ time: new Date(fixedInstant) });
  await page.clock.pauseAt(new Date(fixedInstant));
  await page.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    const body = request.postData() ? JSON.parse(request.postData()) : null;
    const json = value => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/v1/auth/admin/login") return json({ username: "UTC_E2E", accessToken: "synthetic-token", mode: "admin", passwordChangeRequired: false, expiresAt: "2099-01-01T00:00:00Z" });
    if (path === summaryPath + "/options") return json({ companies: [], stores: [] });
    if (path === summaryPath) return json(emptySummary);
    if (path === "/api/v1/admin/me") return json({ username: "UTC_E2E", permissions: ["VIEW_ADMIN_DATA", "EDIT_COMPANY_DATA", "RENEW_LICENSE"] });
    if (path === "/api/v1/admin/companies") return json([company]);
    if (path === "/api/v1/admin/licenses") return json([]);
    if (path === "/api/v1/admin/audit") return json([{ id: "utc-audit", username: "UTC_E2E", action: "UTC_TEST", targetType: "STORE", targetId: currentStore.id, createdAt: fixedInstant }]);
    if (path === "/api/v1/admin/sync/sales-summary") return json({ documentCount: 0, total: "0.00" });
    if (path === "/api/v1/admin/reports/advanced") return json({ companies: 1, invoices: 0, invoicedTotal: "0.00", paidTotal: "0.00", salesDocuments: 0, salesTotal: "0.00", inventoryMovements: 0, integrations: 0, activeIntegrations: 0 });
    if (path === "/api/v1/admin/stores" && method === "GET") return json({ items: [currentStore], page: 0, size: 25, total: 1, totalPages: 1 });
    if (path === "/api/v1/admin/stores/utc-store" && method === "GET") return json(currentStore);
    if (path === "/api/v1/admin/stores/utc-store" && method === "PUT") {
      writes.push(body);
      currentStore = { ...currentStore, ...body };
      return json(currentStore);
    }
    if (method !== "GET") throw new Error(`Unexpected UTC smoke mutation: ${method} ${path}`);
    return json([]);
  });

  try {
    await page.goto(baseUrl);
    const loginClock = page.locator(".saas-login-topbar .saas-login-clock");
    await loginClock.waitFor();
    assert.equal(await loginClock.getAttribute("datetime"), fixedInstant);
    const clockText = await loginClock.innerText();
    assert.match(clockText, /00:34:56.*UTC/);
    if (zone === "Europe/Madrid") {
      await page.setViewportSize({ width: 320, height: 800 });
      assert.equal(await loginClock.evaluate(element => element.scrollWidth <= element.clientWidth), true);
      await page.screenshot({ path: screenshotDirectory + "/saas-utc-login-mobile.png" });
      await page.setViewportSize({ width: 1440, height: 1000 });
    }

    await page.locator('input[autocomplete="username"]').fill("UTC_E2E");
    await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
    await page.locator('form button[type="submit"]').click();
    const appClock = page.locator(".app-system-bar .saas-login-clock");
    await appClock.waitFor();
    assert.equal(await appClock.getAttribute("datetime"), fixedInstant);
    assert.equal(await appClock.innerText(), clockText, `${zone}: login and app clocks must show the same UTC instant`);
    assert.equal(await appClock.evaluate(element => element.scrollWidth <= element.clientWidth), true);
    const isYesterday = await page.evaluate(async () => {
      const { isToday } = await import("/src/shared/lib.tsx");
      return isToday("2026-06-30T23:00:00Z");
    });
    assert.equal(isYesterday, false, `${zone}: today's sync count must use UTC day boundaries`);

    await page.evaluate(() => { location.hash = "#/audit"; });
    const auditTime = page.locator(".audit-row span").filter({ hasText: "UTC_E2E" });
    await auditTime.waitFor();
    assert.match(await auditTime.innerText(), /00:34.*UTC/, `${zone}: audit time must use UTC`);

    await page.evaluate(() => { location.hash = "#/stores"; });
    const row = page.locator('.stores-workspace tr[data-row-id="utc-store"]');
    await row.waitFor();
    assert.match(await row.locator('td[data-column-key="lastSyncAt"]').innerText(), /00:34.*UTC/, `${zone}: sync time must use UTC`);
    const storeName = row.locator('td[data-column-key="name"]');
    await storeName.dblclick();
    const dialog = page.locator("dialog.saas-store-dialog");
    const form = dialog.getByRole("form", { name: "Editar", exact: true });
    await form.waitFor();
    const expiry = form.locator(".date-time-trigger");
    assert.match(await expiry.inputValue(), /15.*12:34.*UTC/, `${zone}: initial expiry must show UTC minute`);
    assert.equal(await form.getByLabel("Zona horaria de la tienda", { exact: true }).inputValue(), "Atlantic/Canary");

    await form.getByRole("button", { name: "Guardar", exact: true }).click();
    await dialog.waitFor({ state: "hidden" });
    assert.equal(writes.at(-1).validUntil, originalExpiry, `${zone}: unchanged expiry must preserve seconds and milliseconds`);
    assert.equal(writes.at(-1).timeZoneId, "Atlantic/Canary");

    await storeName.dblclick();
    await form.waitFor();
    await expiry.click();
    const calendar = form.locator('.date-time-popover[role="dialog"]');
    await calendar.locator(".date-time-days button:not(.outside)").filter({ hasText: /^16$/ }).click();
    await calendar.locator('input[type="time"]').fill("07:08");
    assert.match(await expiry.inputValue(), /16.*0?7:08.*UTC/, `${zone}: selected day and time must remain in UTC`);
    if (zone === "Europe/Madrid") await page.screenshot({ path: screenshotDirectory + "/saas-utc-store-calendar.png" });
    await calendar.getByRole("button", { name: "Cerrar calendario", exact: true }).click();
    await form.getByRole("button", { name: "Guardar", exact: true }).click();
    await dialog.waitFor({ state: "hidden" });
    assert.equal(writes.at(-1).validUntil, selectedExpiry, `${zone}: selected UTC time must round-trip without a zone shift`);
    assert.equal(writes.at(-1).timeZoneId, "Atlantic/Canary", `${zone}: store operational zone must survive an expiry edit`);
    assert.deepEqual(pageErrors, [], `${zone}: no browser errors`);
  } finally {
    await context.close();
  }
}

try {
  await mkdir(screenshotDirectory, { recursive: true });
  await start();
  browser = await playwright.chromium.launch({ headless: true });
  for (const zone of ["UTC", "Europe/Madrid", "Asia/Shanghai"]) await runZone(zone);
  console.log("UTC smoke passed in UTC, Europe/Madrid and Asia/Shanghai");
} finally {
  await browser?.close();
  server.kill();
}
