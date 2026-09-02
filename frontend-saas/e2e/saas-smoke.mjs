import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import playwright from "playwright";
const { chromium } = playwright;

const baseUrl = process.env.SAAS_E2E_URL ?? "http://127.0.0.1:5185/";
const username = process.env.SAAS_E2E_USERNAME ?? "ADMIN";
const password = process.env.SAAS_E2E_PASSWORD ?? "0000";

let devServer = null;
async function ensureFrontend() {
  if (process.env.SAAS_E2E_URL) {
    try {
      const response = await fetch(baseUrl);
      if (response.ok) return;
    } catch {
      // Report the configured endpoint below.
    }
    throw new Error(`No hay frontend disponible en ${baseUrl}`);
  }
  const frontendRoot = fileURLToPath(new URL("..", import.meta.url));
  const viteBin = fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url));
  devServer = spawn(process.execPath, [viteBin, "--host", "127.0.0.1", "--port", "5185", "--strictPort", "--configLoader", "runner"], {
    cwd: frontendRoot,
    stdio: "ignore"
  });
  for (let attempt = 0; attempt < 50; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 100));
    if (devServer.exitCode !== null) throw new Error("El servidor Vite SaaS no pudo iniciarse");
    try {
      const response = await fetch(baseUrl);
      if (response.ok) return;
    } catch {
      // Retry until the bounded startup deadline.
    }
  }
  throw new Error(`El frontend SaaS no respondio en ${baseUrl}`);
}

await ensureFrontend();
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
  await page.goto(baseUrl, { waitUntil: "domcontentloaded", timeout: 15_000 });
  await page.locator("input[autocomplete=username]").fill(username);
  await page.locator("input[autocomplete=current-password]").fill(password);
  await page.locator("form button[type=submit]").click();
  const destination = await Promise.race([
    page.locator(".saas-dashboard").waitFor({ state: "visible", timeout: 15_000 }).then(() => "dashboard"),
    page.locator("#password-change-title").waitFor({ state: "visible", timeout: 15_000 }).then(() => "password-change")
  ]);

  if (destination === "password-change") {
    const passwordFields = page.locator('.login-panel input[autocomplete="new-password"]');
    assert.equal(await passwordFields.count(), 2);
    assert.deepEqual(await passwordFields.evaluateAll((fields) => fields.map((field) => field.minLength)), [12, 12]);
    assert.equal(await page.locator("#password-change-title").isVisible(), true);

    // Leave the gate without changing the shared ADMIN credential, then inspect both recovery steps.
    await page.locator(".login-panel button.secondary-button").click();
    await page.locator('input[autocomplete="current-password"]').waitFor({ state: "visible" });
    await page.locator(".login-recovery-actions button").nth(0).click();
    assert.equal(await page.locator('input[autocomplete="current-password"]').count(), 0);
    await page.locator(".login-recovery-actions button").nth(1).click();
    assert.equal(await page.locator('input[autocomplete="one-time-code"]').isVisible(), true);
    const recoveryPasswords = page.locator('.login-panel input[autocomplete="new-password"]');
    assert.equal(await recoveryPasswords.count(), 2);
    assert.deepEqual(await recoveryPasswords.evaluateAll((fields) => fields.map((field) => field.minLength)), [12, 12]);

    console.log("SaaS E2E passed: mandatory password gate and non-destructive recovery UI.");
  } else {
    assert.equal(await page.evaluate(() => location.hash), "#/dashboard");
    await page.locator(".top-nav-list button").filter({ hasText: "Factur" }).click();
    await page.waitForFunction(() => location.hash === "#/billing");
    await page.goBack();
    await page.waitForFunction(() => location.hash === "#/dashboard");

    const language = page.locator(".system-session .language-trigger");
    await language.click();
    assert.equal(await language.getAttribute("aria-expanded"), "true");
    await page.keyboard.press("Escape");
    assert.equal(await language.getAttribute("aria-expanded"), "false");

    console.log("SaaS E2E passed: login, deep-link navigation, history and keyboard accessibility.");
  }

  const adminPage = await browser.newPage({ viewport: { width: 1280, height: 720 } });
  await adminPage.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const json = (body) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (path === "/api/v1/auth/login") {
      await json({ username: "ADMIN_E2E", accessToken: "admin-token", mode: "admin", expiresAt: "2099-01-01T00:00:00Z", passwordChangeRequired: false });
    } else if (path === "/api/v1/admin/me") {
      await json({ username: "ADMIN_E2E", permissions: [] });
    } else if (path === "/api/v1/admin/sync/sales-summary") {
      await json({ documentCount: 0, total: "0.00" });
    } else if (path === "/api/v1/admin/reports/advanced") {
      await json({ companies: 0, subscriptions: 0, subscriptionMrr: "0.00", invoices: 0, invoicedTotal: "0.00", paidTotal: "0.00", salesDocuments: 0, salesTotal: "0.00", inventoryMovements: 0, integrations: 0, activeIntegrations: 0 });
    } else if (path.startsWith("/api/v1/admin/")) {
      await json([]);
    } else {
      await route.continue();
    }
  });
  await adminPage.goto(baseUrl, { waitUntil: "domcontentloaded", timeout: 15_000 });
  await adminPage.locator('input[autocomplete="username"]').fill("ADMIN_E2E");
  await adminPage.locator('input[autocomplete="current-password"]').fill("not-a-real-password");
  await adminPage.locator("form button[type=submit]").click();
  await adminPage.locator(".saas-dashboard").waitFor({ state: "visible", timeout: 15_000 });
  assert.equal(await adminPage.locator('.top-nav-list button[aria-current="page"]').count(), 1);
  const billingNavigation = adminPage.locator(".top-nav-list button").nth(7);
  await billingNavigation.click();
  await adminPage.waitForFunction(() => location.hash === "#/billing");
  assert.equal(await billingNavigation.getAttribute("aria-current"), "page");
  await adminPage.goBack();
  await adminPage.waitForFunction(() => location.hash === "#/dashboard");
  const adminLanguage = adminPage.locator(".system-session .language-trigger");
  await adminLanguage.click();
  await adminPage.keyboard.press("Escape");
  assert.equal(await adminLanguage.getAttribute("aria-expanded"), "false");
  console.log("SaaS E2E passed: successful admin dashboard, history and accessible current navigation.");
  const tenantPage = await browser.newPage({ viewport: { width: 1280, height: 720 } });
  let adminRequests = 0;
  await tenantPage.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.startsWith("/api/v1/admin/")) {
      adminRequests += 1;
      await route.fulfill({ status: 500, body: "Admin endpoint must not be called for tenant login" });
    } else if (path === "/api/v1/auth/login") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ username: "OWNER_DEMO", accessToken: "tenant-token", mode: "tenant", expiresAt: "2099-01-01T00:00:00Z", passwordChangeRequired: false }) });
    } else if (path === "/api/v1/tenant/me") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ username: "OWNER_DEMO", companyId: "company-1", companyName: "Tenant E2E", roleName: "OWNER" }) });
    } else if (path === "/api/v1/tenant/dashboard") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ companyId: "company-1", companyName: "Tenant E2E", licenses: 0, stores: 0, installations: 0, openTickets: 0, billingStatus: "PAGADO", renewalDate: null, monthlyPrice: null }) });
    } else if (path === "/api/v1/tenant/erp/products") {
      await route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Products temporarily unavailable" }) });
    } else if (path.startsWith("/api/v1/tenant/")) {
      await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
    } else {
      await route.continue();
    }
  });
  await tenantPage.goto(baseUrl, { waitUntil: "domcontentloaded", timeout: 15_000 });
  await tenantPage.locator('input[autocomplete="username"]').fill("OWNER_DEMO");
  await tenantPage.locator('input[autocomplete="current-password"]').fill("not-a-real-password");
  await tenantPage.locator("form button[type=submit]").click();
  await tenantPage.locator(".tenant-shell").waitFor({ state: "visible", timeout: 15_000 });
  assert.equal(adminRequests, 0);
  assert.match(await tenantPage.locator(".notice.error").innerText(), /products.*temporarily unavailable/i);
  await tenantPage.locator(".tenant-top-nav button").nth(2).click();
  await tenantPage.waitForFunction(() => location.hash === "#tenant-masters");
  await tenantPage.goBack();
  assert.notEqual(await tenantPage.evaluate(() => location.hash), "#tenant-masters");
  console.log("SaaS E2E passed: tenant realm routing, partial data tolerance and tenant history navigation.");
} finally {
  await browser.close();
  devServer?.kill();
}
