import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import playwright from "playwright";
const { chromium } = playwright;

const baseUrl = process.env.SAAS_E2E_URL ?? "http://127.0.0.1:5185/";
const username = process.env.SAAS_E2E_USERNAME ?? "ADMIN";
const password = process.env.SAAS_E2E_PASSWORD ?? "0000";
const runRealBackend = process.env.SAAS_E2E_REAL_BACKEND === "true" || Boolean(process.env.SAAS_E2E_URL);

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
  async function runRealBackendScenario() {
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
      await page.locator(".login-panel button.secondary-button").click();
      await page.locator('input[autocomplete="current-password"]').waitFor({ state: "visible" });
      console.log("SaaS real-backend E2E passed: mandatory password gate.");
    } else {
      assert.equal(await page.evaluate(() => location.hash), "#/dashboard");
      console.log("SaaS real-backend E2E passed: login and dashboard.");
    }
    await page.close();
  }

  const passwordPage = await browser.newPage({ viewport: { width: 1280, height: 720 } });
  await passwordPage.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/v1/auth/login") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ username: "PASSWORD_E2E", accessToken: "pending-token", mode: "admin", expiresAt: "2099-01-01T00:00:00Z", passwordChangeRequired: true }) });
    } else if (path === "/api/v1/auth/password/change") {
      await new Promise((resolve) => setTimeout(resolve, 250));
      await route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Temporary password service failure" }) });
    } else if (path === "/api/v1/auth/logout") {
      await route.fulfill({ status: 204 });
    } else {
      await route.fulfill({ status: 404 });
    }
  });
  await passwordPage.goto(baseUrl, { waitUntil: "domcontentloaded", timeout: 15_000 });
  await passwordPage.locator('input[autocomplete="username"]').fill("PASSWORD_E2E");
  await passwordPage.locator('input[autocomplete="current-password"]').fill("not-a-real-password");
  await passwordPage.locator("form button[type=submit]").click();
  await passwordPage.locator("#password-change-title").waitFor({ state: "visible", timeout: 15_000 });
  const passwordFields = passwordPage.locator('.login-panel input[autocomplete="new-password"]');
  await passwordFields.nth(0).fill("new-password-123");
  await passwordFields.nth(1).fill("new-password-123");
  await passwordPage.locator('button[type="submit"]').click();
  const passwordLogout = passwordPage.locator(".login-panel button.secondary-button");
  assert.equal(await passwordLogout.isDisabled(), true);
  await passwordPage.locator('[role="alert"]').waitFor({ state: "visible", timeout: 5_000 });
  assert.equal(await passwordLogout.isEnabled(), true);
  await passwordPage.close();
  console.log("SaaS E2E passed: mandatory password change blocks incompatible logout while pending.");

  const adminPage = await browser.newPage({ viewport: { width: 1280, height: 720 } });
  let fiscalInvoice = null;
  let fiscalDecisionSaved = false;
  let fiscalPaymentCreated = false;
  let companySwitchStarted = false;
  let staleCompanyReloads = 0;
  let outboxFailureActive = true;
  await adminPage.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const method = route.request().method();
    const json = (body) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (path === "/api/v1/auth/login") {
      await json({ username: "ADMIN_E2E", accessToken: "admin-token", mode: "admin", expiresAt: "2099-01-01T00:00:00Z", passwordChangeRequired: false });
    } else if (path === "/api/v1/admin/me") {
      await json({ username: "ADMIN_E2E", permissions: ["MANAGE_BILLING", "MANAGE_OPERATIONS"] });
    } else if (path === "/api/v1/admin/licenses") {
      await json([
        { licenseReference: "LIC-E2E", companyId: "company-fiscal", companyName: "Fiscal E2E", taxId: "B00000000", taxpayerType: "SOCIEDAD", taxRegime: "IVA", commercialProfile: "MINORISTA", status: "VALIDA", validUntil: "2099-01-01T00:00:00Z", maxWindows: 1, maxPda: 0 },
        { licenseReference: "LIC-OTHER", companyId: "company-other", companyName: "Other E2E", taxId: "B00000001", taxpayerType: "SOCIEDAD", taxRegime: "IVA", commercialProfile: "MINORISTA", status: "VALIDA", validUntil: "2099-01-01T00:00:00Z", maxWindows: 1, maxPda: 0 }
      ]);
    } else if (path === "/api/v1/admin/billing-summary") {
      await json({ totalCompanies: 2, paidCompanies: 0, pendingCompanies: 2, overdueCompanies: 0, renewalsNext30Days: 0, monthlyRecurringRevenue: "100.00", companies: [{ companyId: "company-fiscal", companyName: "Fiscal E2E", taxId: "B00000000", planName: "STANDARD", billingStatus: "PENDIENTE", renewalDate: null, monthlyPrice: "50.00", licenseReference: "LIC-E2E", validUntil: "2099-01-01T00:00:00Z", renewalDueSoon: false, overdue: false }, { companyId: "company-other", companyName: "Other E2E", taxId: "B00000001", planName: "STANDARD", billingStatus: "PENDIENTE", renewalDate: null, monthlyPrice: "50.00", licenseReference: "LIC-OTHER", validUntil: "2099-01-01T00:00:00Z", renewalDueSoon: false, overdue: false }] });
    } else if (path === "/api/v1/admin/companies/company-fiscal/invoices" && method === "POST") {
      const payload = JSON.parse(route.request().postData() ?? "{}");
      if (payload.number === "F-RACE") {
        await new Promise((resolve) => setTimeout(resolve, 300));
        await json({ id: "invoice-race", companyId: "company-fiscal", companyName: "Fiscal E2E", number: "F-RACE", concept: "Race", amount: "10.00", paidAmount: "0.00", currency: "EUR", status: "PENDIENTE", issuedAt: payload.issuedAt, dueAt: payload.dueAt, createdAt: payload.issuedAt });
        return;
      }
      fiscalInvoice = { id: "invoice-fiscal", companyId: "company-fiscal", companyName: "Fiscal E2E", number: "F-E2E-1", concept: "Servicio SaaS", amount: "121.00", paidAmount: "0.00", currency: "EUR", status: "PENDIENTE", issuedAt: "2026-09-08T10:00:00Z", dueAt: "2026-10-08T10:00:00Z", createdAt: "2026-09-08T10:00:00Z" };
      await json(fiscalInvoice);
    } else if (path === "/api/v1/admin/companies/company-fiscal/invoices") {
      if (companySwitchStarted) staleCompanyReloads += 1;
      await json(fiscalInvoice ? [fiscalInvoice] : []);
    } else if (path === "/api/v1/admin/companies/company-other/invoices") {
      await json([{ id: "invoice-other", companyId: "company-other", companyName: "Other E2E", number: "OTHER-1", concept: "Other", amount: "20.00", paidAmount: "0.00", currency: "EUR", status: "PENDIENTE", issuedAt: "2026-09-08T10:00:00Z", dueAt: "2026-10-08T10:00:00Z", createdAt: "2026-09-08T10:00:00Z" }]);
    } else if (path === "/api/v1/admin/invoices/invoice-other/fiscal") {
      await json({ invoiceId: "invoice-other", companyId: "company-other", number: "OTHER-1", series: "O", fiscalYear: 2026, taxRegime: "IVA", fiscalStatus: "CALCULATED", taxBase: "16.53", taxRate: "21.00", taxAmount: "3.47", reason: null, legalBasis: null, evidenceReference: null, total: "20.00", currency: "EUR" });
    } else if (path === "/api/v1/admin/invoices/invoice-fiscal/fiscal" && method === "PUT") {
      const payload = JSON.parse(route.request().postData() ?? "{}");
      assert.deepEqual(payload, { fiscalStatus: "CALCULATED", taxBase: "100.00", taxRate: "21.00", taxAmount: "21.00", reason: null, legalBasis: null, evidenceReference: null });
      fiscalDecisionSaved = true;
      await json({ invoiceId: "invoice-fiscal", companyId: "company-fiscal", number: "F-E2E-1", series: "F", fiscalYear: 2026, taxRegime: "IVA", fiscalStatus: "CALCULATED", taxBase: "100.00", taxRate: "21.00", taxAmount: "21.00", reason: null, legalBasis: null, evidenceReference: null, total: "121.00", currency: "EUR" });
    } else if (path === "/api/v1/admin/invoices/invoice-fiscal/fiscal") {
      await json({ invoiceId: "invoice-fiscal", companyId: "company-fiscal", number: "F-E2E-1", series: "F", fiscalYear: 2026, taxRegime: "IVA", fiscalStatus: fiscalDecisionSaved ? "CALCULATED" : "PENDING_TAX_DATA", taxBase: fiscalDecisionSaved ? "100.00" : null, taxRate: fiscalDecisionSaved ? "21.00" : null, taxAmount: fiscalDecisionSaved ? "21.00" : null, reason: null, legalBasis: null, evidenceReference: null, total: "121.00", currency: "EUR" });
    } else if (path === "/api/v1/admin/invoices/invoice-fiscal/payments" && method === "POST") {
      assert.equal(fiscalDecisionSaved, true);
      fiscalPaymentCreated = true;
      await json({ id: "payment-fiscal", invoiceId: "invoice-fiscal", amount: "121.00", method: "TRANSFERENCIA", reference: "E2E-PAY", paidAt: "2026-09-08T10:05:00Z", createdAt: "2026-09-08T10:05:00Z" });
    } else if (path === "/api/v1/admin/companies/company-fiscal/plan-usage") {
      await json({ companyId: "company-fiscal", planName: "STANDARD", usage: {}, limits: {} });
    } else if (path === "/api/v1/admin/companies/company-fiscal/reconciliations") {
      await json([]);
    } else if (path === "/api/v1/admin/companies/company-other/plan-usage") {
      await json({ companyId: "company-other", planName: "STANDARD", usage: {}, limits: {} });
    } else if (path === "/api/v1/admin/companies/company-other/reconciliations") {
      await json([]);
    } else if (path === "/api/v1/admin/outbox/failures") {
      await json({ items: outboxFailureActive ? [{ id: "outbox-1", channel: "SECURITY", subject: "SECURITY_ALERT", attempts: 3, error: "delivery failed", failedAt: "2026-09-08T10:00:00Z" }] : [], nextCursor: null });
    } else if (path === "/api/v1/admin/outbox/security/outbox-1/requeue" && method === "POST") {
      assert.deepEqual(JSON.parse(route.request().postData() ?? "{}"), { reason: "Reintento validado por soporte" });
      outboxFailureActive = false;
      await route.fulfill({ status: 204 });
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
  await adminPage.getByRole("button", { name: "Facturacion" }).click();
  await adminPage.getByLabel("Numero factura").fill("F-E2E-1");
  await adminPage.getByLabel("Concepto").fill("Servicio SaaS");
  const invoiceForm = adminPage.locator("form").filter({ has: adminPage.getByRole("button", { name: "Crear factura" }) });
  await invoiceForm.getByLabel("Importe").fill("121.00");
  await invoiceForm.getByRole("button", { name: "Crear factura" }).click();
  await adminPage.getByLabel("Detalle fiscal de factura").selectOption("invoice-fiscal");
  await adminPage.getByRole("button", { name: "Ver fiscal" }).click();
  await adminPage.getByText("PENDING_TAX_DATA", { exact: true }).waitFor();
  const fiscalForm = adminPage.locator('form[aria-label="Decisión fiscal"]');
  const paymentForm = adminPage.locator("form").filter({ has: adminPage.getByRole("button", { name: "Registrar pago" }) });
  const fiscalPaymentOption = paymentForm.getByLabel("Facturas").locator('option[value="invoice-fiscal"]');
  assert.equal(await fiscalPaymentOption.evaluate((option) => option.disabled), true);
  await fiscalForm.getByLabel("Base imponible").fill("100.00");
  await fiscalForm.getByLabel("Impuesto", { exact: true }).fill("21.00");
  await fiscalForm.getByLabel("Cuota fiscal").fill("21.00");
  await fiscalForm.getByRole("button", { name: "Guardar decisión fiscal" }).click();
  await fiscalPaymentOption.evaluate((option) => new Promise((resolve) => {
    if (!option.disabled) resolve();
    else new MutationObserver(() => { if (!option.disabled) resolve(); }).observe(option, { attributes: true });
  }));
  await paymentForm.getByLabel("Facturas").selectOption("invoice-fiscal");
  await paymentForm.getByLabel("Referencia").fill("E2E-PAY");
  await paymentForm.getByRole("button", { name: "Registrar pago" }).click();
  await adminPage.waitForFunction(() => document.querySelector('.notice.success')?.textContent?.includes('Registrar pago'));
  assert.equal(fiscalPaymentCreated, true);
  console.log("SaaS E2E passed: invoice creation, mandatory fiscal decision and payment gating.");
  await invoiceForm.getByLabel("Numero factura").fill("F-RACE");
  await invoiceForm.getByLabel("Concepto").fill("Race");
  await invoiceForm.getByLabel("Importe").fill("10.00");
  await invoiceForm.getByRole("button", { name: "Crear factura" }).click();
  companySwitchStarted = true;
  await adminPage.getByLabel("Empresa", { exact: true }).selectOption("company-other");
  await adminPage.getByText("OTHER-1", { exact: true }).waitFor();
  await new Promise((resolve) => setTimeout(resolve, 400));
  assert.equal(staleCompanyReloads, 0);
  assert.equal(await adminPage.getByText("OTHER-1", { exact: true }).isVisible(), true);
  console.log("SaaS E2E passed: company switch isolates an in-flight billing mutation.");
  await adminPage.getByRole("button", { name: "Recuperación de entregas" }).click();
  await adminPage.getByText("SECURITY_ALERT", { exact: true }).waitFor();
  await adminPage.getByRole("button", { name: "Reencolar" }).click();
  const outboxForm = adminPage.locator('form[aria-label="Motivo de resolución"]');
  await outboxForm.getByLabel("Motivo de resolución").fill("Reintento validado por soporte");
  adminPage.once("dialog", (dialog) => dialog.accept());
  await outboxForm.getByRole("button", { name: "Reencolar" }).click();
  await adminPage.getByText("No hay entregas fallidas.", { exact: true }).waitFor();
  assert.equal(outboxFailureActive, false);
  console.log("SaaS E2E passed: paginated V50 outbox recovery and confirmed requeue.");
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
  if (runRealBackend) await runRealBackendScenario();
  else console.log("SaaS E2E: real backend scenario skipped; set SAAS_E2E_REAL_BACKEND=true to enable it.");
} finally {
  await browser.close();
  devServer?.kill();
}
