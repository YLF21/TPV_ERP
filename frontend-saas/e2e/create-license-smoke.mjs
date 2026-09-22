import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
const { chromium } = await import("playwright").catch(() => import("../../frontend/node_modules/playwright/index.mjs"));

// All companies, shops, licenses and activation codes are synthetic API fixtures.
const root = fileURLToPath(new URL("..", import.meta.url));
const base = "http://127.0.0.1:5194/";
const output = fileURLToPath(new URL("../../output/playwright/", import.meta.url));
const id = number => `80000000-0000-4000-8000-${String(number).padStart(12, "0")}`;
const companyA = id(1), companyB = id(2), companyEmpty = id(3);
const serverEpoch = Date.parse("2026-09-21T12:00:00.000Z");
const allPermissions = ["VIEW_ADMIN_DATA", "ADD_COMPANY", "REGENERATE_PAIRING_CODE"];
let browser, server;
const errors = [];

async function until(predicate, description = "expected browser/API state") {
  for (let attempt = 0; attempt < 100; attempt++) {
    const value = await predicate(); if (value) return value;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error(`Timed out waiting for ${description}`);
}
function deferred() {
  const held = {};
  held.requested = new Promise(resolve => { held.started = resolve; });
  held.released = new Promise(resolve => { held.release = resolve; });
  held.done = new Promise(resolve => { held.finished = resolve; });
  return held;
}
async function nav(page, label) { await page.locator(".top-nav-list").getByRole("button", { name: label, exact: true }).click(); }
async function login(page, username) {
  await page.locator('input[autocomplete="username"]').fill(username);
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  await page.locator(".top-nav-list").waitFor();
}
async function setup({ permissions = allPermissions, username = "CREATE_LICENSE_DEMO" } = {}) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  const page = await context.newPage(); page.on("pageerror", error => errors.push(error.message));
  const browserLogs = [];
  page.on("console", message => browserLogs.push(message.text()));
  // Deliberately incorrect workstation date: expiry must use server time plus elapsed time.
  await page.clock.install({ time: new Date("2031-01-01T00:00:00Z") });
  const startedAt = Date.now(); let elapsedOffset = 0;
  const now = () => serverEpoch + Date.now() - startedAt + elapsedOffset;
  const companies = [
    { companyId: companyA, companyName: "Compañía Álamo", taxId: "B00000001" },
    { companyId: companyB, companyName: "Empresa Beta sin licencia", taxId: "B00000002" },
    { companyId: companyEmpty, companyName: "Empresa sin tiendas", taxId: "B00000003" },
  ];
  const stores = Array.from({ length: 31 }, (_, index) => ({ id: id(100 + index), companyId: companyA, companyName: companies[0].companyName,
    internalCode: String(3500001 + index), code: String(index + 1).padStart(3, "0"), name: `Tienda Álamo ${String(index + 1).padStart(2, "0")}`, active: true,
    taxRegime: "IGIC", commercialProfile: "MINORISTA", taxRegimeLocked: false, servicePrice: "29.00", billingPeriod: "MONTHLY", maxWindows: 1, maxPda: 0,
    validUntil: "2099-01-01T00:00:00Z", installations: 0, activeInstallations: 0, lastSyncAt: null }));
  stores.push(...[0, 1].map(index => ({ ...stores[0], id: id(200 + index), companyId: companyB, companyName: companies[1].companyName,
    internalCode: String(3600001 + index), code: String(index + 1).padStart(3, "0"), name: `Tienda Beta ${index ? "inactiva" : "activa"}`, active: index === 0 })));
  let codes = Array.from({ length: 27 }, (_, index) => ({ id: id(300 + index), companyId: companyA, companyName: companies[0].companyName,
    storeId: stores[index].id, storeName: stores[index].name, internalCode: stores[index].internalCode, storeCode: stores[index].code,
    licenseId: id(400 + index), reference: `LIC-HISTORICAL-${String(index + 1).padStart(2, "0")}`, pairingCode: `SYNTHETIC-OLD-${String(index + 1).padStart(2, "0")}`,
    // Historical validity is authoritative and deliberately exceeds the new 30-minute duration.
    pairingExpiresAt: new Date(serverEpoch + 2 * 60 * 60_000 + index * 1000).toISOString() }));
  const calls = [];
  const knownCodeIds = new Set(codes.map(code => code.id));
  let failCompanies = false, failCodes = false, failStores = false, failGeneration = false, failDashboard = false, failDeletion = false;
  let heldStores = null, heldGeneration = null, heldCodes = null, heldDeletion = null;
  let generatedCount = 0;
  const jsonError = (route, detail, status = 503) => route.fulfill({ status, contentType: "application/problem+json", body: JSON.stringify({ detail }) });
  await context.route("**/api/**", async route => {
    const request = route.request(), url = new URL(request.url()), path = decodeURIComponent(url.pathname), method = request.method();
    const body = request.postData() ? JSON.parse(request.postData()) : null;
    const query = Object.fromEntries(url.searchParams); calls.push({ path, method, query, body });
    const json = value => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/v1/auth/admin/login") return json({ username, accessToken: "synthetic-token", mode: "admin", expiresAt: "2099-01-01T00:00:00Z", passwordChangeRequired: false });
    if (path === "/api/v1/admin/me") return json({ username, permissions });
    if (path === "/api/v1/admin/licenses") return failDashboard ? jsonError(route, "Synthetic dashboard unavailable") : json([]);
    if (path === "/api/v1/admin/installations") return json([]);
    if (path === "/api/v1/admin/companies") return failCompanies ? jsonError(route, "Synthetic company directory unavailable") : json(companies);
    if (path === "/api/v1/admin/stores" && method === "GET") {
      assert.ok(query.companyId, "Creation must not fetch an unscoped store directory");
      assert.equal(Number(query.size), 25);
      if (failStores) return jsonError(route, "Synthetic store directory unavailable");
      const filtered = stores.filter(store => store.companyId === query.companyId && (!query.active || String(store.active) === query.active));
      const pageNumber = Number(query.page || 0), size = Number(query.size);
      const result = { items: filtered.slice(pageNumber * size, (pageNumber + 1) * size), page: pageNumber, size, total: filtered.length, totalPages: Math.ceil(filtered.length / size) };
      if (heldStores && query.companyId === companyA && pageNumber === 1) {
        const held = heldStores; heldStores = null; held.started(); await held.released; await json(result); held.finished(); return;
      }
      return json(result);
    }
    if (path === "/api/v1/admin/license-workspace/activation-codes" && method === "GET") {
      assert.equal(Number(query.size), 25);
      assert.equal(query.companyId, undefined, "Visible active codes are global and independent of the company picker");
      if (failCodes) return jsonError(route, "Synthetic active codes unavailable", failCodes === 403 ? 403 : 503);
      const pageNumber = Number(query.page || 0), size = Number(query.size);
      const active = codes.filter(code => Date.parse(code.pairingExpiresAt) > now());
      const result = structuredClone({ items: active.slice(pageNumber * size, (pageNumber + 1) * size), page: pageNumber, size, total: active.length,
        totalPages: Math.ceil(active.length / size), serverNow: new Date(now()).toISOString() });
      if (heldCodes) { const held = heldCodes; heldCodes = null; held.started(); await held.released; await json(result); held.finished(); return; }
      return json(result);
    }
    if (path.startsWith("/api/v1/admin/license-workspace/activation-codes/") && method === "DELETE") {
      const codeId = path.split("/").at(-1);
      assert.match(codeId, /^80000000-0000-4000-8000-\d{12}$/, "Deletion must address the activation-code UUID");
      assert.equal(body, null, "Deleting a code must not send its secret in the body");
      assert.equal(query.pairingCode, undefined);
      if (heldDeletion) { const held = heldDeletion; heldDeletion = null; held.started(); await held.released; held.finished(); }
      if (failDeletion) return jsonError(route, "Synthetic activation deletion unavailable", Number(failDeletion));
      if (!knownCodeIds.has(codeId)) return jsonError(route, "Unknown synthetic activation code", 404);
      codes = codes.filter(code => code.id !== codeId);
      return route.fulfill({ status: 204 });
    }
    if (path === "/api/v1/admin/license-workspace" && method === "POST") {
      assert.deepEqual(Object.keys(body), ["storeId"]);
      const store = stores.find(store => store.id === body.storeId); assert.ok(store?.active);
      if (heldGeneration) { const held = heldGeneration; heldGeneration = null; held.started(); await held.released; held.finished(); }
      if (failGeneration) return jsonError(route, "Synthetic activation generation unavailable");
      generatedCount++;
      const serverNow = new Date(now()).toISOString();
      const result = { id: id(500), pairingCodeId: id(500 + generatedCount), companyId: store.companyId, storeId: store.id, reference: "LIC-NEW-BETA", pairingCode: `SYNTHETIC-NEW-BETA${generatedCount > 1 ? `-${generatedCount}` : ""}`,
        pairingExpiresAt: new Date(Date.parse(serverNow) + 30 * 60_000).toISOString(), serverNow };
      codes = codes.filter(code => code.storeId !== store.id);
      knownCodeIds.add(result.pairingCodeId);
      codes.unshift({ id: result.pairingCodeId, companyId: store.companyId, companyName: store.companyName, storeId: store.id, storeName: store.name,
        internalCode: store.internalCode, storeCode: store.code, licenseId: result.id, reference: result.reference, pairingCode: result.pairingCode, pairingExpiresAt: result.pairingExpiresAt });
      return json(result);
    }
    if (path === "/api/v1/admin/sync/sales-summary") return json({ documentCount: 0, total: "0.00" });
    if (path === "/api/v1/admin/reports/advanced") return json({ companies: companies.length, invoices: 0, invoicedTotal: "0.00", paidTotal: "0.00", salesDocuments: 0, salesTotal: "0.00", inventoryMovements: 0, integrations: 0, activeIntegrations: 0 });
    if (method !== "GET") throw new Error(`Unexpected mutation: ${method} ${path}`);
    return json([]);
  });
  await page.goto(base); await login(page, username);
  return { page, context, calls, stores, now, username, browserLogs, setFailure: (kind, value) => {
    if (kind === "companies") failCompanies = value;
    else if (kind === "codes") failCodes = value;
    else if (kind === "stores") failStores = value;
    else if (kind === "generation") failGeneration = value;
    else if (kind === "dashboard") failDashboard = value;
    else if (kind === "deletion") failDeletion = value;
    else throw new Error(`Unknown failure fixture: ${kind}`);
  }, holdStorePage: () => { heldStores = deferred(); return heldStores; }, holdGeneration: () => { heldGeneration = deferred(); return heldGeneration; },
  holdCodes: () => { heldCodes = deferred(); return heldCodes; },
  holdDeletion: () => { heldDeletion = deferred(); return heldDeletion; },
  consumeCode: code => { codes = codes.filter(item => item.pairingCode !== code); },
  forgetCode: code => {
    const row = codes.find(item => item.pairingCode === code); assert.ok(row);
    knownCodeIds.delete(row.id); codes = codes.filter(item => item.id !== row.id);
  },
  replaceCode: (code, replacementId, replacementSecret) => {
    const row = codes.find(item => item.pairingCode === code); assert.ok(row);
    codes = codes.filter(item => item.storeId !== row.storeId);
    codes.unshift({ ...row, id: replacementId, pairingCode: replacementSecret, pairingExpiresAt: new Date(now() + 30 * 60_000).toISOString() });
    knownCodeIds.add(replacementId);
  },
  advance: async milliseconds => { elapsedOffset += milliseconds; await page.clock.fastForward(milliseconds); } };
}

async function chooseCompany(page, query, companyName, keyboard = false) {
  const picker = page.getByRole("combobox", { name: "Empresa", exact: true });
  await picker.click();
  await picker.fill(query);
  const option = page.getByRole("option", { name: new RegExp(companyName) }); await option.waitFor();
  if (keyboard) { await picker.press("ArrowDown"); await page.keyboard.press("Enter"); }
  else await option.click();
  await until(async () => (await picker.inputValue()).includes(companyName), "committed company selection");
}
function codeRow(page, code) { return page.getByRole("row").filter({ has: page.getByText(code, { exact: true }) }); }
async function remainingSeconds(row) {
  const text = await row.getByRole("timer").innerText();
  assert.match(text, /^\d+:\d{2}$/);
  const [minutes, seconds] = text.split(":").map(Number);
  assert.ok(seconds < 60);
  return minutes * 60 + seconds;
}
async function assertNoStoredCodes(page) {
  const storage = await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }));
  assert.doesNotMatch(storage, /SYNTHETIC-(?:OLD|NEW)-/, "Activation codes must never be persisted in browser storage");
}

async function verifyCodeActions() {
  const fixture = await setup({ username: "CODE_ACTIONS_DEMO" });
  const { page, calls } = fixture;
  const workspace = page.locator(".saas-create-license-workspace");
  const activeCodes = page.getByRole("region", { name: "Códigos de activación vigentes", exact: true });
  const refresh = activeCodes.getByRole("button", { name: "Actualizar códigos", exact: true });
  const deletes = () => calls.filter(call => call.method === "DELETE");
  const settle = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  try {
    await nav(page, "Crear licencia"); await codeRow(page, "SYNTHETIC-OLD-01").waitFor();
    // Exercise both clipboard paths without touching the system clipboard or persisting secrets.
    await page.evaluate(() => {
      const state = { rejectApi: false, fallback: "success", writes: [], fallbackCalls: 0 };
      window.__clipboardTest = state;
      Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: async value => {
        if (state.rejectApi) throw new DOMException("Synthetic clipboard denial", "NotAllowedError");
        state.writes.push(value);
      } } });
      document.execCommand = command => {
        if (command !== "copy") throw new Error("Unexpected clipboard fallback command");
        state.fallbackCalls++;
        if (state.fallback === "throw") throw new Error("Synthetic fallback rejection");
        const textarea = [...document.querySelectorAll("textarea")].find(element => element.style.left === "-9999px");
        if (!textarea) throw new Error("Clipboard fallback has no temporary selection");
        state.writes.push(textarea.value); return true;
      };
    });
    const textareasBefore = await page.locator("textarea").count();
    const historicalCopy = codeRow(page, "SYNTHETIC-OLD-01").getByRole("button", { name: "Copiar", exact: true });
    await historicalCopy.click(); await workspace.getByRole("status").filter({ hasText: "Código copiado" }).waitFor();
    assert.equal(await page.evaluate(() => window.__clipboardTest.writes.at(-1) === "SYNTHETIC-OLD-01"), true, "Copy must contain exactly the selected secret");
    await page.evaluate(() => { window.__clipboardTest.rejectApi = true; });
    await codeRow(page, "SYNTHETIC-OLD-02").getByRole("button", { name: "Copiar", exact: true }).click();
    await until(() => page.evaluate(() => window.__clipboardTest.fallbackCalls === 1));
    assert.equal(await page.evaluate(() => window.__clipboardTest.writes.at(-1) === "SYNTHETIC-OLD-02"), true);
    assert.equal(await page.locator("textarea").count(), textareasBefore, "Successful fallback must remove its temporary textarea");
    await page.evaluate(() => { window.__clipboardTest.fallback = "throw"; });
    await historicalCopy.click();
    await workspace.getByRole("alert").filter({ hasText: "No se ha podido copiar el código" }).waitFor();
    assert.equal(await page.locator("textarea").count(), textareasBefore, "A throwing fallback must remove the secret-bearing textarea");
    assert.equal(await historicalCopy.evaluate(element => document.activeElement === element), true, "Clipboard fallback must restore focus even after failure");
    await page.evaluate(() => { window.__clipboardTest.rejectApi = false; });
    await assertNoStoredCodes(page);
    console.log("Activation actions E2E: exact clipboard copy, rejected API fallback, throwing fallback cleanup/focus and visible error passed without persistent secrets.");

    fixture.setFailure("deletion", 503);
    await codeRow(page, "SYNTHETIC-OLD-02").getByRole("button", { name: "Eliminar", exact: true }).click();
    await until(() => deletes().length === 1); await workspace.getByRole("alert").waitFor();
    assert.equal(await codeRow(page, "SYNTHETIC-OLD-02").isVisible(), true, "Failed deletion must retain the code for retry");
    assert.equal(deletes()[0].path, `/api/v1/admin/license-workspace/activation-codes/${id(301)}`);
    fixture.setFailure("deletion", false);
    await until(() => refresh.isEnabled());
    const oldSnapshot = fixture.holdCodes(); await refresh.click(); await oldSnapshot.requested;
    const pendingDelete = fixture.holdDeletion();
    await codeRow(page, "SYNTHETIC-OLD-02").getByRole("button", { name: "Eliminar", exact: true }).dblclick();
    await pendingDelete.requested; await settle();
    assert.equal(deletes().length, 2, "A double click must start exactly one DELETE while pending");
    assert.equal(await codeRow(page, "SYNTHETIC-OLD-02").getByRole("button", { name: "Eliminando…", exact: true }).isDisabled(), true);
    pendingDelete.release();
    await page.getByText("SYNTHETIC-OLD-02", { exact: true }).waitFor({ state: "hidden" });
    const freshSnapshot = fixture.holdCodes(); oldSnapshot.release(); await oldSnapshot.done; await freshSnapshot.requested; await settle();
    assert.equal(await page.getByText("SYNTHETIC-OLD-02", { exact: true }).count(), 0, "An older GET must not revive a deleted code");
    assert.equal(await codeRow(page, "SYNTHETIC-OLD-01").isVisible(), true, "Deleting one code must preserve unrelated rows");
    freshSnapshot.release(); await freshSnapshot.done; await until(() => refresh.isEnabled());

    fixture.consumeCode("SYNTHETIC-OLD-03");
    const consumedResponse = page.waitForResponse(response => response.request().method() === "DELETE" && response.url().endsWith(id(302)));
    await codeRow(page, "SYNTHETIC-OLD-03").getByRole("button", { name: "Eliminar", exact: true }).click();
    assert.equal((await consumedResponse).status(), 204, "Known already-consumed codes are idempotently revoked");
    await page.getByText("SYNTHETIC-OLD-03", { exact: true }).waitFor({ state: "hidden" });
    await until(() => refresh.isEnabled());
    fixture.forgetCode("SYNTHETIC-OLD-04");
    const missingResponse = page.waitForResponse(response => response.request().method() === "DELETE" && response.url().endsWith(id(303)));
    await codeRow(page, "SYNTHETIC-OLD-04").getByRole("button", { name: "Eliminar", exact: true }).click();
    assert.equal((await missingResponse).status(), 404);
    await workspace.getByRole("alert").waitFor();
    assert.equal(await codeRow(page, "SYNTHETIC-OLD-04").isVisible(), true, "A missing UUID reports an error without silently deleting another code");
    console.log("Activation actions E2E: deletion error/retry, one DELETE per double click, exact UUID removal, stale-snapshot protection, consumed-code 204 and missing-code 404 passed.");

    await chooseCompany(page, "B00000002", "Empresa Beta sin licencia");
    const generate = page.getByRole("table", { name: "Tiendas de la empresa", exact: true }).getByRole("row").filter({ has: page.getByRole("cell", { name: "Tienda Beta activa", exact: true }) }).getByRole("button", { name: "Generar código de activación", exact: true });
    fixture.setFailure("dashboard", true);
    const generatedSnapshot = fixture.holdCodes(); await generate.click(); await generatedSnapshot.requested;
    const issued = activeCodes.locator(".saas-issued-code"); await issued.waitFor();
    await issued.getByRole("button", { name: "Copiar", exact: true }).click();
    assert.equal(await page.evaluate(() => window.__clipboardTest.writes.at(-1) === "SYNTHETIC-NEW-BETA"), true, "A just-issued code must be copyable before its list refresh completes");
    await mkdir(output, { recursive: true });
    await page.screenshot({ path: `${output}/saas-activation-code-actions-1600.png`, fullPage: true });
    const issuedDeletion = page.waitForResponse(response => response.request().method() === "DELETE" && response.url().endsWith(id(501)));
    await issued.getByRole("button", { name: "Eliminar", exact: true }).click();
    assert.equal((await issuedDeletion).status(), 204, "Just-issued deletion must use pairingCodeId, not licenseId");
    await issued.waitFor({ state: "hidden" });
    const afterIssuedDeletion = fixture.holdCodes(); generatedSnapshot.release(); await generatedSnapshot.done; await afterIssuedDeletion.requested;
    assert.equal(await page.getByText("SYNTHETIC-NEW-BETA", { exact: true }).count(), 0, "Deleting the issued card must also invalidate older list snapshots");
    afterIssuedDeletion.release(); await afterIssuedDeletion.done; await until(() => refresh.isEnabled());

    await generate.click(); await codeRow(page, "SYNTHETIC-NEW-BETA-2").waitFor(); await until(() => refresh.isEnabled());
    const oldDelete = fixture.holdDeletion();
    await codeRow(page, "SYNTHETIC-NEW-BETA-2").getByRole("button", { name: "Eliminar", exact: true }).click(); await oldDelete.requested;
    assert.equal(await generate.isDisabled(), true, "Generation is blocked during code deletion");
    fixture.replaceCode("SYNTHETIC-NEW-BETA-2", id(900), "SYNTHETIC-NEW-EXTERNAL");
    oldDelete.release(); await codeRow(page, "SYNTHETIC-NEW-EXTERNAL").waitFor();
    assert.equal(await page.getByText("SYNTHETIC-NEW-BETA-2", { exact: true }).count(), 0);
    assert.equal(deletes().at(-1).path, `/api/v1/admin/license-workspace/activation-codes/${id(502)}`);
    assert.equal(await activeCodes.getByText("SYNTHETIC-NEW-EXTERNAL", { exact: true }).count(), 1, "Deleting an old UUID must preserve the replacement for the same license/store");
    await page.setViewportSize({ width: 1280, height: 1000 });
    await page.screenshot({ path: `${output}/saas-activation-code-actions-1280.png`, fullPage: true });
    await assertNoStoredCodes(page);
    assert.equal(fixture.browserLogs.some(message => /SYNTHETIC-(?:OLD|NEW)-/.test(message)), false, "Activation secrets must never enter browser logs");
    assert.ok(deletes().every(call => /^\/api\/v1\/admin\/license-workspace\/activation-codes\/[^/]+$/.test(call.path) && call.body === null), "No action may delete a license or transmit its code in the request body");
    const copiedBeforeExpiry = await page.evaluate(() => window.__clipboardTest.writes.length);
    const expiredRefresh = fixture.holdCodes();
    // Simulate a suspended render timer: the code is still painted, but the
    // monotonic clock read by its click handler has advanced beyond expiry.
    await codeRow(page, "SYNTHETIC-NEW-EXTERNAL").getByRole("button", { name: "Copiar", exact: true }).evaluate(button => {
      const descriptor = Object.getOwnPropertyDescriptor(performance, "now");
      const elapsed = performance.now() + 3 * 60 * 60_000;
      Object.defineProperty(performance, "now", { configurable: true, value: () => elapsed });
      try { button.click(); }
      finally { if (descriptor) Object.defineProperty(performance, "now", descriptor); else delete performance.now; }
    });
    await expiredRefresh.requested;
    await workspace.getByRole("alert").filter({ hasText: "El código ha caducado. Actualiza la lista." }).waitFor();
    assert.equal(await page.evaluate(() => window.__clipboardTest.writes.length), copiedBeforeExpiry, "A code expiring after its last render must not reach the clipboard");
    expiredRefresh.release(); await expiredRefresh.done;
    console.log("Activation actions E2E: issued-card copy/delete uses pairingCodeId, deleted snapshots stay hidden, external regeneration survives an old deletion, and no license deletion/log/storage leakage passed.");
    console.log("Activation actions E2E: expired-code click with a suspended render timer refreshes the list and performs no clipboard write.");
  } finally { await fixture.context.close(); }

  const limited = await setup({ permissions: ["VIEW_ADMIN_DATA", "ADD_COMPANY"], username: "CODE_COPY_ONLY_DEMO" });
  try {
    await nav(limited.page, "Crear licencia"); await codeRow(limited.page, "SYNTHETIC-OLD-01").waitFor();
    assert.ok(await limited.page.getByRole("button", { name: "Copiar", exact: true }).count() > 0);
    assert.equal(await limited.page.getByRole("button", { name: "Eliminar", exact: true }).count(), 0, "Code deletion requires REGENERATE_PAIRING_CODE");
    assert.equal(limited.calls.some(call => call.method === "DELETE"), false);
    console.log("Activation actions E2E: account without regeneration permission can see copy actions but no delete action.");
  } finally { await limited.context.close(); }
}

try {
  server = spawn(process.execPath, [fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url)), "--host", "127.0.0.1", "--port", "5194", "--strictPort", "--configLoader", "runner"], { cwd: root, stdio: "ignore", windowsHide: true });
  await until(async () => { if (server.exitCode !== null) throw new Error("Vite creation test failed to start"); try { return (await fetch(base)).ok; } catch { return false; } }, "Vite startup");
  browser = await chromium.launch({ headless: true });
  const fixture = await setup();
  const { page, calls } = fixture;
  fixture.setFailure("companies", true); fixture.setFailure("codes", true);
  await nav(page, "Crear licencia");
  const companyRegion = page.locator(".saas-activation-company");
  const activeCodes = page.getByRole("region", { name: "Códigos de activación vigentes", exact: true });
  await companyRegion.locator(".retry-error").waitFor();
  await activeCodes.locator(".retry-error").waitFor();
  assert.equal(calls.some(call => call.path === "/api/v1/admin/stores"), false, "No shops are fetched before a company is selected");
  fixture.setFailure("companies", false); fixture.setFailure("codes", false);
  await companyRegion.getByRole("button", { name: "Reintentar", exact: true }).click();
  await activeCodes.getByRole("button", { name: "Reintentar", exact: true }).click();
  await codeRow(page, "SYNTHETIC-OLD-01").waitFor();
  const codeTable = activeCodes.getByRole("table", { name: "Códigos de activación vigentes", exact: true });
  assert.equal(await codeTable.locator("tbody tr").count(), 25);
  assert.ok(await remainingSeconds(codeRow(page, "SYNTHETIC-OLD-01")) > 110 * 60, "Historical expiration must not be replaced with the new 30-minute duration or the workstation date");
  await activeCodes.getByRole("button", { name: "Siguiente", exact: true }).click();
  await codeRow(page, "SYNTHETIC-OLD-27").waitFor();
  assert.equal(await codeTable.locator("tbody tr").count(), 2);
  assert.ok(calls.some(call => call.path.endsWith("activation-codes") && call.query.page === "1"));
  await activeCodes.getByRole("button", { name: "Anterior", exact: true }).click();
  await codeRow(page, "SYNTHETIC-OLD-01").waitFor();
  assert.equal(calls.some(call => call.path === "/api/v1/admin/license-workspace" && call.method === "POST"), false);
  await assertNoStoredCodes(page);
  console.log("Create license E2E: separate directory/recovery errors retry safely and existing codes load without issuing a new code.");

  const picker = page.getByRole("combobox", { name: "Empresa", exact: true });
  await picker.fill("does-not-match");
  assert.equal(await page.getByRole("option").count(), 0);
  await picker.fill("alamo");
  await page.getByRole("option", { name: /Compañía Álamo.*B00000001/ }).waitFor();
  await picker.press("Escape");
  assert.equal(await page.getByRole("option").count(), 0);
  fixture.setFailure("stores", true);
  await chooseCompany(page, "alamo", "Compañía Álamo", true);
  const storeRegion = page.getByRole("region", { name: "Tiendas de la empresa", exact: true });
  await storeRegion.locator(".retry-error").waitFor();
  fixture.setFailure("stores", false);
  await storeRegion.getByRole("button", { name: "Reintentar", exact: true }).click();
  const storeTable = page.getByRole("table", { name: "Tiendas de la empresa", exact: true });
  await until(async () => await storeTable.locator("tbody tr").count() === 25, "first 25 company shops");
  assert.equal(calls.some(call => call.path === "/api/v1/admin/stores" && call.query.page === "1"), false, "Stores beyond the first page must load on demand");
  const oldPage = fixture.holdStorePage();
  await storeTable.evaluate(table => { const parent = table.parentElement; parent.scrollTop = parent.scrollHeight; parent.dispatchEvent(new Event("scroll")); });
  await oldPage.requested;
  await chooseCompany(page, "B00000002", "Empresa Beta sin licencia");
  await storeTable.getByRole("cell", { name: "Tienda Beta activa", exact: true }).waitFor();
  oldPage.release(); await oldPage.done;
  assert.equal(await storeTable.locator("tbody tr").count(), 2, "Late pages from the previous company must be ignored");
  assert.equal(await storeTable.getByText(/Tienda Álamo/).count(), 0);
  await chooseCompany(page, "alamo", "Compañía Álamo");
  await until(async () => await storeTable.locator("tbody tr").count() === 25, "reselected company first page");
  await storeTable.evaluate(table => { const parent = table.parentElement; parent.scrollTop = parent.scrollHeight; parent.dispatchEvent(new Event("scroll")); });
  await until(async () => await storeTable.locator("tbody tr").count() === 31, "remaining company shops after scroll");
  assert.equal(new Set(await storeTable.locator("tbody tr td:first-child").allTextContents()).size, 31);
  await chooseCompany(page, "B00000002", "Empresa Beta sin licencia");
  await storeTable.getByRole("cell", { name: "Tienda Beta activa", exact: true }).waitFor();
  const activeStore = storeTable.getByRole("row").filter({ has: page.getByRole("cell", { name: "3600001", exact: true }) });
  const inactiveStore = storeTable.getByRole("row").filter({ has: page.getByRole("cell", { name: "3600002", exact: true }) });
  assert.equal(await inactiveStore.getByRole("button", { name: "Generar código de activación", exact: true }).isDisabled(), true);
  console.log("Create license E2E: accent-insensitive name/NIF keyboard selection, scoped lazy shops, inactive guard and stale-response rejection passed.");

  fixture.setFailure("generation", true);
  await activeStore.getByRole("button", { name: "Generar código de activación", exact: true }).click();
  await page.locator('.saas-create-license-workspace > [role="alert"]').waitFor();
  assert.equal(await page.getByText("SYNTHETIC-NEW-BETA", { exact: true }).count(), 0);
  fixture.setFailure("generation", false);
  const pending = fixture.holdGeneration();
  const mutationsBefore = calls.filter(call => call.method === "POST" && call.path.endsWith("license-workspace")).length;
  await activeStore.getByRole("button", { name: "Generar código de activación", exact: true }).click(); await pending.requested;
  assert.equal(await activeStore.getByRole("button", { name: "Generando…", exact: true }).isDisabled(), true);
  assert.equal(await picker.isDisabled(), true);
  assert.equal(calls.filter(call => call.method === "POST" && call.path.endsWith("license-workspace")).length, mutationsBefore + 1);
  pending.release();
  const generated = codeRow(page, "SYNTHETIC-NEW-BETA"); await generated.waitFor();
  assert.deepEqual(calls.findLast(call => call.method === "POST" && call.path.endsWith("license-workspace")).body, { storeId: id(200) });
  await assertNoStoredCodes(page);
  console.log("Create license E2E: failed/pending generation retains selection, prevents duplicate submission and posts only the selected store id.");

  await until(async () => await activeCodes.getByRole("button", { name: "Actualizar códigos", exact: true }).isEnabled(), "completed code refresh");
  const initialRemaining = await remainingSeconds(generated);
  assert.ok(initialRemaining <= 1800 && initialRemaining >= 1785, `New code should start near 30 minutes; got ${initialRemaining}`);
  await page.clock.setFixedTime(new Date("2041-06-01T00:00:00Z"));
  await fixture.advance(5000);
  const afterFiveSeconds = await remainingSeconds(generated);
  assert.ok(afterFiveSeconds <= initialRemaining - 4 && afterFiveSeconds >= initialRemaining - 7,
    `Countdown must follow elapsed time despite a changed PC clock: ${initialRemaining} -> ${afterFiveSeconds}`);

  const postsBeforeReload = calls.filter(call => call.method === "POST" && call.path.endsWith("license-workspace")).length;
  const readsBeforeReload = calls.filter(call => call.path.endsWith("activation-codes")).length;
  await page.reload(); await login(page, fixture.username);
  await codeRow(page, "SYNTHETIC-NEW-BETA").waitFor();
  assert.ok(calls.filter(call => call.path.endsWith("activation-codes")).length > readsBeforeReload);
  assert.equal(calls.filter(call => call.method === "POST" && call.path.endsWith("license-workspace")).length, postsBeforeReload,
    "Recovering an active code after reauthentication must not generate another code");
  assert.ok(await remainingSeconds(generated) <= afterFiveSeconds + 1, "Reload must preserve the original expiration");
  await assertNoStoredCodes(page);

  await chooseCompany(page, "B00000003", "Empresa sin tiendas");
  await page.getByRole("region", { name: "Tiendas de la empresa", exact: true }).getByText("SIN DATOS", { exact: true }).waitFor();
  assert.equal(await storeTable.locator("tbody tr").count(), 0);
  await chooseCompany(page, "B00000002", "Empresa Beta sin licencia");
  await storeTable.getByRole("cell", { name: "Tienda Beta activa", exact: true }).waitFor();
  await mkdir(output, { recursive: true });
  for (const width of [1600, 1280]) {
    await page.setViewportSize({ width, height: 1000 });
    await page.screenshot({ path: `${output}/saas-create-license-${width}.png`, fullPage: true });
  }
  await fixture.advance(30 * 60_000);
  await page.getByText("SYNTHETIC-NEW-BETA", { exact: true }).waitFor({ state: "hidden" });
  await codeRow(page, "SYNTHETIC-OLD-01").waitFor();
  assert.ok(await remainingSeconds(codeRow(page, "SYNTHETIC-OLD-01")) > 80 * 60, "Historical codes retain their own longer validity");
  fixture.consumeCode("SYNTHETIC-OLD-01");
  await until(async () => await activeCodes.getByRole("button", { name: "Actualizar códigos", exact: true }).isEnabled());
  await activeCodes.getByRole("button", { name: "Actualizar códigos", exact: true }).click();
  await page.getByText("SYNTHETIC-OLD-01", { exact: true }).waitFor({ state: "hidden" });
  assert.equal(calls.filter(call => call.method === "POST" && call.path.endsWith("license-workspace")).length, postsBeforeReload);
  console.log("Create license E2E: global code pagination, server-anchored countdown, fresh recovery after reload, local expiry and server consumption refresh passed.");

  // Recover independently when the dashboard refresh fails, then deliver an older GET after a regeneration.
  fixture.setFailure("dashboard", true);
  await activeStore.getByRole("button", { name: "Generar código de activación", exact: true }).click();
  await codeRow(page, "SYNTHETIC-NEW-BETA-2").waitFor();
  await until(async () => await activeCodes.getByRole("button", { name: "Actualizar códigos", exact: true }).isEnabled());
  const oldCodes = fixture.holdCodes();
  await activeCodes.getByRole("button", { name: "Actualizar códigos", exact: true }).click(); await oldCodes.requested;
  await activeStore.getByRole("button", { name: "Generar código de activación", exact: true }).click();
  await activeCodes.getByText("SYNTHETIC-NEW-BETA-3", { exact: true }).waitFor();
  assert.equal(await activeCodes.getByText("SYNTHETIC-NEW-BETA-2", { exact: true }).count(), 0,
    "Regeneration must immediately hide the previous code for the same license");
  // Hold the queued fresh GET too, so the old response cannot be accidentally masked by its successor.
  const freshCodes = fixture.holdCodes(); oldCodes.release(); await oldCodes.done; await freshCodes.requested;
  assert.equal(await activeCodes.getByText("SYNTHETIC-NEW-BETA-2", { exact: true }).count(), 0);
  assert.equal(await activeCodes.getByText("SYNTHETIC-NEW-BETA-3", { exact: true }).isVisible(), true,
    "A GET begun before the POST cannot hide the newly issued code");
  const issuedTimer = activeCodes.locator(".saas-issued-code");
  const beforeLatency = await remainingSeconds(issuedTimer);
  await fixture.advance(5000);
  freshCodes.release(); await freshCodes.done;
  await codeRow(page, "SYNTHETIC-NEW-BETA-3").waitFor();
  assert.ok(await remainingSeconds(codeRow(page, "SYNTHETIC-NEW-BETA-3")) <= beforeLatency - 4,
    "Receiving a delayed snapshot must not extend the code's remaining validity");

  fixture.consumeCode("SYNTHETIC-NEW-BETA-3");
  await activeCodes.getByRole("button", { name: "Actualizar códigos", exact: true }).click();
  await activeCodes.getByText("SYNTHETIC-NEW-BETA-3", { exact: true }).waitFor({ state: "hidden" });
  const afterConsumption = fixture.holdCodes();
  await activeCodes.getByRole("button", { name: "Actualizar códigos", exact: true }).click(); await afterConsumption.requested;
  assert.equal(await activeCodes.getByText("SYNTHETIC-NEW-BETA-3", { exact: true }).count(), 0,
    "A server-confirmed consumed code must not reappear during another refresh");
  afterConsumption.release(); await afterConsumption.done;

  fixture.setFailure("codes", 403);
  await activeCodes.getByRole("button", { name: "Actualizar códigos", exact: true }).click();
  await activeCodes.locator(".retry-error").waitFor();
  assert.equal(await activeCodes.locator(".saas-activation-code").count(), 0, "Forbidden code recovery must clear every previously displayed secret");
  await assertNoStoredCodes(page);
  console.log("Create license E2E: regeneration replaces the old code, pre-POST reads cannot overwrite it, delayed reads do not extend expiry, confirmed consumption stays hidden and 403 clears secrets.");
  await fixture.context.close();

  await verifyCodeActions();

  const viewer = await setup({ permissions: ["VIEW_ADMIN_DATA"], username: "CREATE_LICENSE_VIEWER" });
  assert.equal(await viewer.page.locator(".top-nav-list").getByRole("button", { name: "Crear licencia", exact: true }).count(), 0);
  await viewer.page.evaluate(() => { window.location.hash = "#/create-license"; });
  await viewer.page.locator("main").getByRole("heading", { name: "Crear licencia", exact: true, level: 1 }).waitFor();
  assert.equal(await viewer.page.locator(".saas-create-license-workspace").count(), 0);
  assert.equal(viewer.calls.some(call => call.path.endsWith("activation-codes") || call.path.endsWith("license-workspace") && call.method === "POST"), false,
    "Direct navigation by a viewer must not fetch activation secrets or expose creation");
  await viewer.context.close();
  assert.deepEqual(errors, []);
  await writeFile(`${output}/create-license-smoke.log`, "PASS: searchable company picker, scoped shops, activation errors/concurrency, countdown/recovery, clipboard success/fallback/error cleanup, code-ID deletion/error/retry/idempotence/stale-response isolation, external regeneration, no secret storage/logging and permission guards.\n");
} finally { await browser?.close(); server?.kill(); }
