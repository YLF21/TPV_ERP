import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import { chromium } from "playwright";
import { emptySummary, summaryPath } from "./summary-fixture.mjs";

// Synthetic accounts and API responses only. No live customer data is changed.
const root = fileURLToPath(new URL("..", import.meta.url));
const base = "http://127.0.0.1:5224/";
const publicPath = "/api/v1/public/client-access-requests";
const requestsPath = "/api/v1/admin/client-access-requests";
const usersPath = "/api/v1/admin/tenant-users";
const screenshotDirectory = fileURLToPath(new URL("../../output/playwright/client-access-requests/", import.meta.url));
const server = spawn(process.execPath, [fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url)),
  "--host", "127.0.0.1", "--port", "5224", "--strictPort", "--configLoader", "runner"],
{ cwd: root, stdio: "ignore", windowsHide: true });
let browser;
async function until(check, label) {
  for (let i = 0; i < 120; i++) { if (await check()) return; await delay(100); }
  throw new Error(`Timed out: ${label}`);
}

try {
  await until(async () => { try { return (await fetch(base)).ok; } catch { return false; } }, "Vite startup");
  browser = await chromium.launch({ headless: true });
  await mkdir(screenshotDirectory, { recursive: true });

  async function publicPage(language = "es") {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    page.setDefaultTimeout(10_000);
    const calls = [], errors = [];
    let failSubmission = false;
    page.on("pageerror", error => errors.push(error.message));
    await page.addInitScript(language => localStorage.setItem("tpv-saas-language", language), language);
    await page.route("**/api/**", async route => {
      const request = route.request(), path = new URL(request.url()).pathname;
      calls.push({ path, method: request.method(), body: request.postDataJSON(), headers: request.headers() });
      if (path !== publicPath) { errors.push(`Unexpected public request: ${path}`); return route.fulfill({ status: 403 }); }
      await delay(150);
      return route.fulfill({ status: typeof failSubmission === "number" ? failSubmission : failSubmission ? 503 : 202,
        ...(failSubmission === 200 ? { contentType: "text/html", body: "<html>Unexpected gateway page</html>" } : {}) });
    });
    await page.goto(`${base}tienda.html`, { waitUntil: "domcontentloaded" });
    await page.locator('a[href="#/request-access"]').click();
    await page.locator("#tenant-access-request-title").waitFor();
    return { page, calls, errors, fail: value => { failSubmission = value; } };
  }

  const fields = { username: "CLIENT_REQUEST_TEST", password: "synthetic-secure-password", confirmation: "synthetic-secure-password",
    fullName: "Cliente de prueba", taxId: "TEST-0001", email: "client@example.test", phone: "+34900000000",
    address: "Calle de prueba 12", postalCode: "35001", city: "Las Palmas", province: "Las Palmas", country: "España" };
  async function fillRequest(page) {
    for (const [field, value] of Object.entries(fields)) await page.locator(`input[name="${field}"]`).fill(value);
  }

  const publicCase = await publicPage();
  const p = publicCase.page;
  await p.getByRole("button", { name: "Enviar solicitud", exact: true }).click();
  await p.getByRole("alert").waitFor();
  assert.equal(publicCase.calls.length, 0, "Incomplete requests stay in the form");
  await fillRequest(p);
  await p.locator('input[name="confirmation"]').fill("another-password");
  await p.getByRole("button", { name: "Enviar solicitud", exact: true }).click();
  assert.match(await p.getByRole("alert").innerText(), /coinciden/);
  assert.equal(publicCase.calls.length, 0);
  await p.locator('input[name="confirmation"]').fill(fields.password);
  publicCase.fail(true);
  await p.getByRole("button", { name: "Enviar solicitud", exact: true }).click();
  await p.getByRole("alert").waitFor();
  await p.getByRole("button", { name: "Enviar solicitud", exact: true }).click();
  await until(() => publicCase.calls.length === 2, "Retry submission");
  await p.getByRole("alert").waitFor();
  assert.equal(publicCase.calls[0].body.submissionId, publicCase.calls[1].body.submissionId, "Unchanged retries are idempotent");
  await p.locator('input[name="password"]').fill("new-synthetic-password");
  await p.locator('input[name="confirmation"]').fill("new-synthetic-password");
  publicCase.fail(false);
  await p.getByRole("button", { name: "Enviar solicitud", exact: true }).click();
  await p.getByRole("heading", { name: "Solicitud recibida", exact: true }).waitFor();
  assert.notEqual(publicCase.calls[2].body.submissionId, publicCase.calls[0].body.submissionId, "Changed passwords use a new submission ID");
  assert.deepEqual(publicCase.calls[2].body.requestedStores, [], "Zero stores is a supported request");
  assert.equal(publicCase.calls[2].headers.authorization, undefined);
  assert.equal(await p.locator('input[type="password"]').count(), 0);
  assert.equal(await p.evaluate(() => JSON.stringify(localStorage).includes("password")), false);
  assert.deepEqual(publicCase.errors, []);
  await p.screenshot({ path: `${screenshotDirectory}/request-success.png`, fullPage: true });
  await p.getByRole("link", { name: "Volver al inicio de sesión", exact: true }).click();
  await p.locator('input[autocomplete="current-password"]').waitFor();
  await p.close();

  const invalidReceipt = await publicPage();
  await fillRequest(invalidReceipt.page);
  invalidReceipt.fail(200);
  await invalidReceipt.page.locator('form button[type="submit"]').click();
  await invalidReceipt.page.getByRole("alert").waitFor();
  assert.equal(await invalidReceipt.page.locator('input[name="password"]').inputValue(), fields.password,
    "An HTML 200 response must not erase the draft or claim submission success");
  assert.equal(await invalidReceipt.page.getByRole("heading", { name: "Solicitud recibida" }).count(), 0);
  await invalidReceipt.page.close();

  for (const language of ["es", "en", "zh"]) {
    const localized = await publicPage(language);
    const page = localized.page;
    await fillRequest(page);
    await page.locator(".tenant-access-add-store").click();
    await page.locator('input[name="requestedStores"]').fill("Tienda Norte · Empresa de prueba · Calle 123");
    await page.locator(".tenant-access-add-store").click();
    await page.locator('input[name="requestedStores"]').nth(1).fill("Tienda Sur · Calle 456");
    await page.locator(".tenant-access-store button").first().click();
    assert.equal(await page.locator('input[name="requestedStores"]').count(), 1);
    if (language === "es") {
      await page.screenshot({ path: `${screenshotDirectory}/request-form-desktop.png`, fullPage: true });
      await page.setViewportSize({ width: 390, height: 844 });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, "No mobile page overflow");
      await page.screenshot({ path: `${screenshotDirectory}/request-form-mobile.png`, fullPage: true });
    }
    await page.locator('form button[type="submit"]').click();
    await page.getByRole("status").waitFor();
    assert.deepEqual(localized.calls[0].body.requestedStores, ["Tienda Sur · Calle 456"]);
    assert.deepEqual(localized.errors, []);
    await page.close();
  }

  const companyA = "10000000-0000-4000-8000-000000000001", companyB = "10000000-0000-4000-8000-000000000002";
  const stores = [
    { id: "20000000-0000-4000-8000-000000000001", companyId: companyA, companyName: "Empresa A", code: "NORTE", name: "Tienda Norte", internalCode: "3500001", active: true },
    { id: "20000000-0000-4000-8000-000000000002", companyId: companyB, companyName: "Empresa B", code: "SUR", name: "Tienda Sur", internalCode: "3500002", active: true },
  ];
  const admin = await browser.newPage({ viewport: { width: 1440, height: 1000 }, timezoneId: "Asia/Shanghai" });
  admin.setDefaultTimeout(10_000);
  await admin.addInitScript(() => localStorage.setItem("tpv-saas-language", "es"));
  const adminErrors = [], adminCalls = [], users = [], access = new Map();
  let failRequests = false, conflict = false, failReview = false;
  const requests = ["ZERO_STORES", "MANY_STORES", "REJECT_ME", "STALE_REVIEW"].map((username, i) => ({
    id: `30000000-0000-4000-8000-00000000000${i + 1}`, username, fullName: `Solicitante ${i + 1}`, email: `${username}@example.test`,
    taxId: "TEST-ID", phone: "900000000", address: "Calle de pruebas 1", postalCode: "35001", city: "Las Palmas",
    province: "Las Palmas", country: "España", notes: "Revisar relación con las tiendas.", requestedStores: ["Tienda Norte declarada por el solicitante"],
    status: "PENDING", version: 0, createdAt: "2026-11-21T12:00:00Z", reviewedAt: null, reviewedBy: null, reviewReason: null, tenantUserId: null,
  }));
  admin.on("pageerror", error => adminErrors.push(error.message));
  await admin.route("**/api/**", async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname, method = request.method();
    const params = Object.fromEntries(url.searchParams), body = request.postDataJSON();
    adminCalls.push({ path, method, params, body });
    const json = value => route.fulfill({ status: 200, contentType: "application/json", json: value });
    const problem = (status, detail) => route.fulfill({ status, contentType: "application/problem+json", json: { detail } });
    if (path === "/api/v1/auth/admin/login") return json({ username: "ADMIN_TEST", accessToken: "synthetic-request-admin", mode: "admin", passwordChangeRequired: false, expiresAt: "2099-01-01T00:00:00Z" });
    assert.equal(request.headers().authorization, "Bearer synthetic-request-admin");
    if (path === "/api/v1/admin/me") return json({ username: "ADMIN_TEST", permissions: ["VIEW_ADMIN_DATA", "MANAGE_TENANT_USERS", "VIEW_REPORTS"] });
    if (path === requestsPath) {
      if (failRequests) return problem(503, "Fallo sintético de solicitudes");
      let items = requests.filter(row => !params.status || row.status === params.status)
        .filter(row => !params.q || `${row.username} ${row.fullName} ${row.email}`.toLowerCase().includes(params.q.toLowerCase()));
      const key = params.sort || "createdAt", direction = params.direction === "asc" ? 1 : -1;
      items.sort((a, b) => String(a[key]).localeCompare(String(b[key])) * direction);
      return json({ items: items.slice(Number(params.page || 0) * Number(params.size || 25), (Number(params.page || 0) + 1) * Number(params.size || 25)), total: items.length });
    }
    if (path.startsWith(`${requestsPath}/`)) {
      const entry = requests.find(row => path.split("/")[5] === row.id);
      assert.ok(entry, `Known request ${path}`);
      if (method === "GET") return json(entry);
      await delay(120);
      if (failReview) { failReview = false; return problem(503, "Fallo transitorio de revisión"); }
      if (conflict) {
        conflict = false; entry.status = "REJECTED"; entry.version++; entry.reviewedBy = "ANOTHER_STAFF";
        entry.reviewReason = "Revisada por otra persona"; entry.reviewedAt = "2026-11-21T12:30:00Z";
        return problem(409, "Solicitud ya revisada");
      }
      assert.equal(body.version, entry.version);
      assert.equal(entry.status, "PENDING");
      entry.status = path.endsWith("/approve") ? "APPROVED" : "REJECTED";
      entry.version++; entry.reviewedBy = "ADMIN_TEST"; entry.reviewedAt = "2026-11-21T12:30:00Z"; entry.reviewReason = body.reason || null;
      if (entry.status === "APPROVED") {
        entry.tenantUserId = entry.id;
        users.push({ id: entry.id, username: entry.username, fullName: entry.fullName, email: entry.email, active: true,
          createdAt: entry.reviewedAt, companyCount: body.grants.length, storeCount: body.grants.reduce((n, grant) => n + grant.storeIds.length, 0) });
        access.set(entry.username, { username: entry.username, companies: body.grants.map(grant => ({ companyId: grant.companyId,
          companyName: grant.companyId === companyA ? "Empresa A" : "Empresa B", roleName: "VIEWER", companyPrivileges: [],
          stores: grant.storeIds.map(id => { const store = stores.find(item => item.id === id); return { ...store, storeId: id }; }) })) });
      }
      return json(entry);
    }
    if (path === usersPath) return json({ items: users.filter(user => !params.q || user.username.includes(params.q)), total: users.length });
    if (path.includes("/access")) {
      const username = decodeURIComponent(path.split("/")[5]), entry = access.get(username);
      assert.ok(entry, `Known account ${username}`);
      if (method === "PUT" || method === "DELETE") {
        const companyId = path.split("/").at(-1);
        entry.companies = entry.companies.filter(company => company.companyId !== companyId);
        if (method === "PUT") entry.companies.push({ companyId, companyName: companyId === companyA ? "Empresa A" : "Empresa B",
          roleName: body.roleName, companyPrivileges: body.companyPrivileges, stores: body.storeIds.map(id => ({ ...stores.find(store => store.id === id), storeId: id })) });
        const user = users.find(user => user.username === username);
        user.companyCount = entry.companies.length; user.storeCount = entry.companies.reduce((n, company) => n + company.stores.length, 0);
      }
      return json(entry);
    }
    if (path === "/api/v1/admin/companies") return json([{ companyId: companyA, companyName: "Empresa A" }, { companyId: companyB, companyName: "Empresa B" }]);
    if (path === "/api/v1/admin/stores") {
      const items = stores.filter(store => !params.companyId || store.companyId === params.companyId);
      return json({ items, page: 0, size: 25, total: items.length, totalPages: 1 });
    }
    if (path === summaryPath) return json(emptySummary);
    if (path === `${summaryPath}/options`) return json({ companies: [], stores: [] });
    adminErrors.push(`Unexpected admin API: ${method} ${path}`);
    return problem(404, "Unexpected API call");
  });
  await admin.goto(base, { waitUntil: "domcontentloaded" });
  await admin.locator('input[autocomplete="username"]').fill("ADMIN_TEST");
  await admin.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await admin.locator('form button[type="submit"]').click();
  await admin.locator(".saas-dashboard").waitFor();
  await admin.locator(".top-nav-list").getByRole("button", { name: "Usuarios cliente", exact: true, includeHidden: true }).waitFor({ state: "attached" });
  await admin.evaluate(() => { location.hash = "#/client-users"; });
  await admin.getByRole("tab", { name: "Solicitudes", exact: true }).waitFor().catch(async error => {
    console.error({ url: admin.url(), errors: adminErrors, calls: adminCalls.map(call => call.path), page: (await admin.locator("body").innerText()).slice(-1800) });
    throw error;
  });
  await admin.getByRole("tab", { name: "Solicitudes", exact: true }).click();
  await admin.getByRole("row").filter({ hasText: "ZERO_STORES" }).dblclick();
  let dialog = admin.getByRole("dialog");
  await dialog.getByText("Tienda Norte declarada por el solicitante", { exact: true }).waitFor();
  await dialog.getByRole("button", { name: "Aprobar", exact: true }).click();
  await dialog.getByText("Aprobada", { exact: true }).waitFor();
  assert.deepEqual(adminCalls.find(call => call.path.endsWith(`${requests[0].id}/approve`)).body, { version: 0, grants: [] });
  await admin.keyboard.press("Escape");

  await admin.getByRole("row").filter({ hasText: "MANY_STORES" }).dblclick();
  dialog = admin.getByRole("dialog");
  await dialog.getByRole("button", { name: "Asignar tiendas", exact: true }).click();
  await dialog.getByLabel("Empresa", { exact: true }).selectOption(companyA).catch(async error => {
    console.error({ errors: adminErrors, dialog: await dialog.innerText(), calls: adminCalls.slice(-5) });
    throw error;
  });
  await dialog.getByRole("checkbox", { name: /NORTE/ }).check();
  await dialog.getByLabel("Empresa", { exact: true }).selectOption(companyB);
  await dialog.getByRole("checkbox", { name: /SUR/ }).check();
  await dialog.screenshot({ path: `${screenshotDirectory}/review-explicit-stores.png` });
  failReview = true;
  await dialog.getByRole("button", { name: "Aprobar", exact: true }).click();
  await dialog.getByRole("alert").waitFor();
  await until(() => dialog.getByRole("button", { name: "Aprobar", exact: true }).isEnabled(), "Review revalidated after transient error");
  assert.equal(await dialog.getByRole("checkbox", { name: /SUR/ }).isChecked(), true,
    "Transient review errors retain the selected stores");
  await dialog.getByRole("button", { name: "Aprobar", exact: true }).click();
  await dialog.getByText("Aprobada", { exact: true }).waitFor();
  const repeatedApproval = adminCalls.filter(call => call.path.endsWith(`${requests[1].id}/approve`));
  assert.equal(repeatedApproval.length, 2);
  for (const call of repeatedApproval) assert.deepEqual(call.body.grants,
    [{ companyId: companyA, storeIds: [stores[0].id] }, { companyId: companyB, storeIds: [stores[1].id] }]);
  await admin.keyboard.press("Escape");
  await admin.getByRole("row").filter({ hasText: "REJECT_ME" }).dblclick();
  dialog = admin.getByRole("dialog");
  await dialog.getByRole("button", { name: "Rechazar", exact: true }).click();
  assert.equal(await dialog.getByRole("button", { name: "Confirmar rechazo", exact: true }).isDisabled(), true);
  await dialog.getByLabel("Motivo del rechazo", { exact: true }).fill("No se ha podido verificar la vinculación.");
  failReview = true;
  await dialog.getByRole("button", { name: "Confirmar rechazo", exact: true }).click();
  await dialog.getByRole("alert").waitFor();
  await until(() => dialog.getByRole("button", { name: "Confirmar rechazo", exact: true }).isEnabled(), "Rejection revalidated after transient error");
  assert.equal(await dialog.getByLabel("Motivo del rechazo", { exact: true }).inputValue(), "No se ha podido verificar la vinculación.");
  await dialog.getByRole("button", { name: "Confirmar rechazo", exact: true }).click();
  await dialog.getByText("Rechazada", { exact: true }).waitFor();
  await admin.keyboard.press("Escape");
  await admin.getByRole("row").filter({ hasText: "STALE_REVIEW" }).dblclick();
  conflict = true;
  await admin.getByRole("dialog").getByRole("button", { name: "Aprobar", exact: true }).click();
  await admin.getByRole("dialog").getByText("Rechazada", { exact: true }).waitFor();
  assert.equal(await admin.getByRole("dialog").getByRole("button", { name: "Aprobar", exact: true }).count(), 0);
  await admin.keyboard.press("Escape");
  await admin.getByRole("tab", { name: "Usuarios cliente", exact: true }).click();
  await admin.getByRole("row").filter({ hasText: "ZERO_STORES" }).waitFor();
  await admin.getByRole("row").filter({ hasText: "MANY_STORES" }).dblclick();
  dialog = admin.getByRole("dialog");
  await dialog.getByRole("button", { name: "Quitar todas las tiendas", exact: true }).click();
  await dialog.getByRole("button", { name: "Guardar", exact: true }).click();
  await until(() => adminCalls.some(call => call.method === "PUT" && call.body.storeIds.length === 0), "Remove all stores from a company");
  await dialog.getByRole("button", { name: "Guardar", exact: true }).waitFor();
  assert.equal(await dialog.getByRole("checkbox", { name: /NORTE/ }).isChecked(), false);
  await dialog.getByLabel("Empresa", { exact: true }).selectOption(companyB);
  assert.equal(await dialog.getByRole("checkbox", { name: /SUR/ }).isChecked(), true, "Other company grants remain intact");
  await dialog.screenshot({ path: `${screenshotDirectory}/client-access-editor.png` });
  await admin.keyboard.press("Escape");
  await admin.screenshot({ path: `${screenshotDirectory}/client-directory.png`, fullPage: true });
  assert.deepEqual(adminErrors, []);
  await admin.close();
  console.log("Customer request E2E passed: public validation/retries/ES/EN/ZH/mobile; staff approval with zero/multiple stores, rejection, stale review, global accounts and store removal.");
} finally {
  await browser?.close();
  server.kill();
}
