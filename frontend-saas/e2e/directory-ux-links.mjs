import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import { emptySummary, summaryPath } from "./summary-fixture.mjs";
import { tableLayoutKey } from "../src/shared/table/table-layout.mjs";

const { chromium } = await import("playwright").catch(() => import("../../frontend/node_modules/playwright/index.mjs"));
const root = fileURLToPath(new URL("..", import.meta.url));
const port = "5218";
const base = `http://127.0.0.1:${port}/`;
const adminPath = "/api/v1/admin";
const exportPath = `${adminPath}/directories/export/stores`;
const storesPath = `${adminPath}/stores`;
const licensesPath = `${adminPath}/license-workspace`;
const token = "synthetic-directory-token";
const username = "DIRECTORY_LINKS_DEMO";
const screenshots = process.env.SAAS_DIRECTORY_UX_SCREENSHOTS;
const fixedTime = new Date("2026-11-19T23:30:00Z");
const workbookName = "espos-Tiendas-2026-11-19.xlsx";
const workbook = Buffer.from("PK\u0003\u0004synthetic-stores-commercial-configuration", "latin1");
const id = number => `10000000-0000-4000-8000-${String(number).padStart(12, "0")}`;
const padded = number => String(number).padStart(3, "0");
const address = { linea1: "Calle sintética", provincia: "Las Palmas", ciudad: "Las Palmas", codigoPostal: "35001", pais: "ES" };
const companies = Array.from({ length: 38 }, (_, index) => ({
  companyId: id(index + 1), companyName: `Grupo Demo ${padded(index + 1)}`, taxId: `B${String(index + 1).padStart(8, "0")}`,
  taxpayerType: "SOCIEDAD", companyAddress: address, createdAt: "2026-09-20T12:00:00Z", contactName: "Contacto Demo",
  contactPhone: "600000123", contactEmail: "contacto@example.invalid", supportStatus: "NORMAL",
  owners: [{ name: "Persona Demo", taxId: "SYNTHETIC" }], storeCount: 0, licenseCount: 0,
}));
const [company, otherCompany] = companies;
companies.at(-1).companyName = "Sociedad fuera del filtro";
const stores = Array.from({ length: 39 }, (_, index) => ({
  id: id(101 + index), companyId: company.companyId, companyName: company.companyName,
  code: padded(index + 1), internalCode: String(3500001 + index), name: `Tienda Demo ${padded(index + 1)}`,
  active: true, storeAddress: address, timeZoneId: "Atlantic/Canary", installations: 0, activeInstallations: 0,
  taxRegime: "IGIC", commercialProfile: "MINORISTA", billingPeriod: "MONTHLY", servicePrice: "29.00",
  maxWindows: 1, maxPda: 0, validUntil: "2027-01-05T00:00:00Z", lastSyncAt: null,
}));
const [targetStore, siblingStore, completeStore, inactiveStore, outsideSearch] = stores;
const commercialFields = ["commercialProfile", "servicePrice", "billingPeriod", "validUntil"];
for (const [index, store] of stores.entries()) {
  if (store !== completeStore) store[commercialFields[index % commercialFields.length]] = null;
  if (store === inactiveStore) store.active = false;
  if (store === outsideSearch) store.name = "Tienda sin coincidencia";
}
const otherStore = { ...completeStore, id: id(200), companyId: otherCompany.companyId, companyName: otherCompany.companyName };
stores.push(otherStore);
const license = (number, reference, store, validUntil, status = "VALIDA") => ({
  id: id(300 + number), reference, licenseReference: reference, companyId: store.companyId, companyName: store.companyName,
  taxId: companies.find(row => row.companyId === store.companyId).taxId, status, validUntil,
  maxWindows: number, maxPda: 0, activeInstallations: 0, lastValidatedAt: null, lastSyncAt: null, stores: [store],
  billingScope: "COMPANY", companyBillingStatus: "PENDIENTE", companyDebt: [],
});
const week = license(1, "LIC-DEMO-WEEK", targetStore, "2026-11-26T23:30:00Z");
const month = license(2, "LIC-DEMO-MONTH", targetStore, "2026-11-26T23:30:01Z");
const later = license(3, "LIC-DEMO-LATER", targetStore, "2026-12-22T00:00:00Z");
const expired = license(4, "LIC-DEMO-EXPIRED", targetStore, "2026-11-18T00:00:00Z", "CADUCADA");
const blocked = license(5, "LIC-DEMO-BLOCKED", targetStore, "2026-11-24T00:00:00Z", "BLOQUEADA_MANUAL");
const sibling = license(6, "LIC-DEMO-SIBLING", siblingStore, "2026-11-24T00:00:00Z");
const other = license(7, "LIC-DEMO-OTHER-COMPANY", otherStore, "2026-11-24T00:00:00Z");
const licenses = [week, month, later, expired, blocked, sibling, other];
for (const row of companies) {
  row.storeCount = stores.filter(store => store.companyId === row.companyId).length;
  row.licenseCount = licenses.filter(license => license.companyId === row.companyId).length;
}

function paged(rows, { page, size, sortBy, sortDirection }) {
  const offset = Number(page) * Number(size);
  const sorted = [...rows].sort((a, b) => (String(a[sortBy] ?? "").localeCompare(String(b[sortBy] ?? ""), "es", { numeric: true })
    || a.id.localeCompare(b.id)) * (sortDirection === "DESC" ? -1 : 1));
  return { items: sorted.slice(offset, offset + Number(size)), page: Number(page), size: Number(size), total: rows.length, totalPages: Math.ceil(rows.length / Number(size)) };
}

function filterStores({ companyId, q, active, commercialConfiguration }) {
  return stores.filter(row => (!companyId || row.companyId === companyId)
    && (!q || `${row.name} ${row.companyName} ${row.code}`.toLowerCase().includes(q.toLowerCase()))
    && (!active || String(row.active) === active)
    && (!commercialConfiguration || commercialFields.every(field => row[field] != null) === (commercialConfiguration === "complete")));
}

function filterLicenses({ companyId, storeId, status, expiresBefore }) {
  return licenses.filter(row => (!companyId || row.companyId === companyId)
    && (!storeId || row.stores.some(store => store.id === storeId))
    && (!status || row.status === status)
    && (!expiresBefore || Date.parse(row.validUntil) <= Date.parse(expiresBefore)));
}

const legacyOrders = {
  companies: ["companyName", "taxId", "contactName", "contactPhone", "contactEmail", "owners", "taxpayerType", "city", "province", "createdAt"],
  stores: ["internalCode", "companyName", "code", "name", "active", "taxRegime", "commercialProfile", "servicePrice", "billingPeriod", "validUntil", "maxWindows", "maxPda", "activeInstallations", "lastSyncAt"],
  licenses: ["reference", "status", "company", "stores", "expiry", "days", "validation", "sync", "windows", "pda", "installations", "billing", "debt"],
};
const preference = key => ({ key, visible: key !== "lastSyncAt", ...(key === "internalCode" ? { width: 190 } : {}) });

async function until(check, label) {
  for (let attempt = 0; attempt < 160; attempt++) {
    if (await check()) return;
    await delay(50);
  }
  throw new Error(`Timed out waiting for ${label}`);
}

async function navigate(page, name) {
  const sidebar = page.locator(".top-nav-list");
  await sidebar.locator(".nav-group-toggle").first().waitFor();
  const target = sidebar.getByRole("button", { name, exact: true });
  if (!await target.isVisible()) {
    for (const toggle of await sidebar.locator(".nav-group-toggle").all()) {
      if (await toggle.getAttribute("aria-expanded") !== "true") await toggle.click();
      if (await target.isVisible()) break;
    }
  }
  await target.click();
}

async function assertInside(locator, message) {
  const bounds = await locator.boundingBox();
  assert.ok(bounds && bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= 1280 && bounds.y + bounds.height <= 720,
    `${message} must fit the 1280×720 viewport: ${JSON.stringify(bounds)}`);
}

const server = spawn(process.execPath,
  [fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url)), "--host", "127.0.0.1", "--port", port, "--strictPort", "--configLoader", "runner"],
  { cwd: root, stdio: "ignore", windowsHide: true });
let browser;
try {
  await until(async () => {
    if (server.exitCode !== null) throw new Error(`Directory test could not start Vite on ${port}`);
    try { return (await fetch(base)).ok; } catch { return false; }
  }, "Vite startup");
  browser = await chromium.launch({ headless: true });
  // Here the local calendar date is November 20, but the UTC date is November 19.
  const page = await browser.newPage({ acceptDownloads: true, viewport: { width: 1280, height: 720 }, timezoneId: "Pacific/Kiritimati" });
  page.setDefaultTimeout(8_000);
  await page.clock.setFixedTime(fixedTime);
  await page.addInitScript(() => {
    window.copiedValues = [];
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: async text => window.copiedValues.push(text) } });
  });
  await page.addInitScript(preferences => {
    for (const [key, value] of preferences) localStorage.setItem(key, value);
  }, Object.entries(legacyOrders).map(([table, columns]) => [tableLayoutKey(username, table), JSON.stringify(columns.map(preference))]));
  let permissions = ["VIEW_ADMIN_DATA"];
  const errors = [], mutations = [], calls = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/api/**", async route => {
    const request = route.request();
    const { pathname: path, searchParams } = new URL(request.url());
    const method = request.method(), query = Object.fromEntries(searchParams);
    const authorization = await request.headerValue("authorization");
    const json = value => route.fulfill({ json: value });
    if (path === "/api/v1/auth/admin/login") return json({ username, accessToken: token, mode: "admin", expiresAt: "2099-01-01T00:00:00Z", passwordChangeRequired: false });
    calls.push({ path, method, query, authorization });
    if (method !== "GET") {
      mutations.push({ path, method });
      return route.fulfill({ status: 403, json: { detail: "Unexpected mutation" } });
    }
    if (path === `${adminPath}/me`) return json({ username, permissions });
    if (path === `${summaryPath}/options`) return json({ companies: [], stores: [] });
    if (path === summaryPath) return json(emptySummary);
    if (path === `${adminPath}/companies`) return json(companies);
    const profile = companies.find(row => path === `${adminPath}/companies/${row.companyId}/profile`);
    if (profile) return json(profile);
    if (path === `${adminPath}/licenses`) return json(licenses);
    if (path === storesPath) return json(paged(filterStores(query), query));
    const store = stores.find(row => path === `${storesPath}/${row.id}`);
    if (store) return json(store);
    if (path === licensesPath) return json(paged(filterLicenses(query), query));
    const detail = licenses.find(row => path === `${licensesPath}/${row.id}`);
    if (detail) return json(detail);
    if (path === exportPath) return route.fulfill({ contentType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      headers: { "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(workbookName)}` }, body: workbook });
    if (path === `${adminPath}/sync/sales-summary`) return json({ documentCount: 0, total: "0.00" });
    if (path === `${adminPath}/reports/advanced`) return json({ companies: companies.length, invoices: 0, invoicedTotal: 0, paidTotal: 0, salesDocuments: 0, salesTotal: 0, inventoryMovements: 0, integrations: 0, activeIntegrations: 0 });
    return json([]);
  });

  await page.goto(base);
  await page.locator('input[autocomplete="username"]').fill(username);
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();

  const main = page.locator("main");
  const table = main.locator(".saas-data-table");
  const viewport = main.locator(".saas-data-table-scroll");
  const row = rowId => table.locator(`tbody tr[data-row-id="${rowId}"]`);
  const rowIds = () => table.locator("tbody tr").evaluateAll(elements => elements.map(item => item.dataset.rowId));
  const headers = () => table.locator("thead th").evaluateAll(elements => elements.map(th => th.dataset.columnKey));
  const contentHeading = () => main.locator(".directory-license-heading h2");
  const exportButton = main.getByRole("button", { name: "Exportar Excel", exact: true });
  const linkTo = (row, hash) => row.getByRole("link").and(row.locator(`a[href="${hash}"]`));
  const finishLoading = () => until(async () => !await main.locator('[aria-busy="true"]').count(), "the directory request");
  const assertQuery = (path, expected) => {
    const call = calls.findLast(call => call.path === path && (!call.query.page || call.query.page === "0"));
    assert.ok(call, `Missing request to ${path}`);
    assert.equal(call.authorization, `Bearer ${token}`);
    for (const [key, value] of Object.entries(expected)) assert.equal(call.query[key] ?? "", value, `${path} query parameter ${key}`);
  };
  const exactHash = async hash => assert.equal(await page.evaluate(() => location.hash), hash);
  const screenshot = async name => {
    if (!screenshots) return;
    await mkdir(screenshots, { recursive: true });
    await page.mouse.move(1275, 10);
    await page.screenshot({ path: join(screenshots, `${name}.png`), animations: "disabled" });
  };
  const keyboardLink = async link => {
    await link.scrollIntoViewIfNeeded();
    await link.focus();
    const scroll = await viewport.evaluate(node => ({ top: node.scrollTop, left: node.scrollLeft }));
    await page.keyboard.press("Enter");
    return scroll;
  };
  const verifyReturn = async (link, position, goBack = () => page.goBack()) => {
    await goBack();
    await link.waitFor();
    await until(() => link.evaluate(node => document.activeElement === node), "focus on the originating link");
    const current = await viewport.evaluate(node => ({ top: node.scrollTop, left: node.scrollLeft }));
    assert.ok(Math.abs(current.top - position.top) <= 2 && Math.abs(current.left - position.left) <= 2,
      `Returning must restore directory scroll: expected ${JSON.stringify(position)}, got ${JSON.stringify(current)}`);
  };

  await navigate(page, "Empresas");
  const companyRow = row(company.companyId);
  await companyRow.waitFor();
  assert.deepEqual((await headers()).slice(0, 4), ["companyName", "taxId", "storeCount", "licenseCount"], "Legacy company layouts should show the new count columns beside identity");
  await screenshot("companies");
  for (const [column, value] of [["taxId", company.taxId], ["contactName", company.contactName], ["contactPhone", company.contactPhone], ["contactEmail", company.contactEmail]]) {
    const cell = companyRow.locator(`td[data-column-key="${column}"]`);
    const copy = cell.getByRole("button", { name: /^Copiar / });
    await copy.focus();
    await page.keyboard.press("Enter");
    await until(async () => await page.evaluate(() => window.copiedValues.at(-1)) === value, `copy of ${column}`);
    await cell.getByRole("status").filter({ hasText: "Copiado" }).waitFor({ state: "attached" });
    assert.equal(await page.getByRole("dialog").count(), 0, `Copying ${column} must keep the company directory open`);
  }
  await page.evaluate(() => {
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: undefined });
    Object.defineProperty(crypto, "randomUUID", { configurable: true, value: undefined });
    window.nativeCopies = [];
    const execCommand = document.execCommand.bind(document);
    document.execCommand = (command, ...args) => {
      const success = execCommand(command, ...args);
      if (command === "copy") window.nativeCopies.push(success);
      return success;
    };
    // Let Chromium dispatch the native copy command without writing to the host clipboard.
    document.addEventListener("copy", event => event.preventDefault(), { once: true });
  });
  const fallbackCopy = row(otherCompany.companyId).locator('td[data-column-key="taxId"]');
  await fallbackCopy.getByRole("button", { name: /^Copiar / }).press("Enter");
  await fallbackCopy.getByRole("status").filter({ hasText: "Copiado" }).waitFor({ state: "attached" });
  assert.deepEqual(await page.evaluate(() => window.nativeCopies), [true]);
  assert.equal(await page.getByRole("dialog").count(), 0);

  const companySearch = main.getByLabel("Empresa / NIF", { exact: true });
  await companySearch.fill("Grupo Demo");
  await main.getByRole("button", { name: "Ordenar por Empresa", exact: true }).click();
  assert.equal((await rowIds()).at(-1), company.companyId);
  assert.equal((await rowIds()).length, companies.length - 1);
  const companyStoresHash = `#/stores?companyId=${company.companyId}`;
  const companyLicensesHash = `#/licenses?companyId=${company.companyId}&status=`;
  const companyStores = linkTo(companyRow, companyStoresHash);
  const companyLicenses = linkTo(companyRow, companyLicensesHash);
  assert.equal((await companyStores.innerText()).trim(), String(company.storeCount));
  assert.equal((await companyLicenses.innerText()).trim(), String(company.licenseCount));
  await assertInside(exportButton, "Company export");
  const companyPosition = await keyboardLink(companyStores);
  assert.ok(companyPosition.top > 0, "The starting company must be below the initial viewport");
  await main.getByRole("heading", { name: "Tiendas", exact: true, level: 2 }).waitFor();
  await exactHash(companyStoresHash);
  await row(targetStore.id).waitFor();
  assertQuery(storesPath, { companyId: company.companyId });
  assert.deepEqual((await headers()).slice(0, 5), ["code", "name", "internalCode", "companyName", "licenses"], "Legacy default store layouts should start with code and name");
  assert.equal((await headers()).includes("lastSyncAt"), false, "The migrated store layout must retain hidden columns");
  assert.equal((await table.locator('th[data-column-key="internalCode"]').boundingBox()).width, 190, "The migrated store layout must retain custom widths");
  await verifyReturn(companyStores, companyPosition);
  assert.equal(await companySearch.inputValue(), "Grupo Demo");
  assert.equal((await rowIds()).at(-1), company.companyId);

  const companyLicensePosition = await keyboardLink(companyLicenses);
  await contentHeading().waitFor();
  await exactHash(companyLicensesHash);
  await row(expired.id).waitFor();
  assert.equal(await contentHeading().innerText(), "Todas las licencias");
  await main.getByRole("heading", { name: "Todas las licencias", exact: true, level: 1 }).waitFor();
  assertQuery(licensesPath, { companyId: company.companyId, storeId: "", status: "" });
  assert.deepEqual(new Set(await rowIds()), new Set([week, month, later, expired, blocked, sibling].map(row => row.id)));
  assert.equal(await table.getByRole("button", { name: /Renovar/i }).count(), 0, "Read-only users cannot start renewals");
  await verifyReturn(companyLicenses, companyLicensePosition, () => main.getByRole("button", { name: "Volver", exact: true }).click());

  await keyboardLink(companyStores);
  await row(targetStore.id).waitFor();
  const storeFilters = main.getByRole("search", { name: "Tiendas", exact: true });
  await storeFilters.getByLabel("Buscar empresa, tienda o referencia", { exact: true }).fill("Tienda Demo");
  await storeFilters.getByRole("button", { name: /Más filtros/ }).click();
  const commercial = storeFilters.getByLabel("Configuración comercial", { exact: true });
  await commercial.selectOption("complete");
  await row(completeStore.id).waitFor();
  await finishLoading();
  assert.deepEqual(await rowIds(), [completeStore.id]);
  assertQuery(storesPath, { companyId: company.companyId, q: "Tienda Demo", commercialConfiguration: "complete" });
  await commercial.selectOption("pending");
  await storeFilters.getByLabel("Estado", { exact: true }).selectOption("true");
  await storeFilters.getByRole("button", { name: /Menos filtros/ }).click();
  const storeSort = table.locator('th[data-column-key="internalCode"]').getByRole("button", { name: /^Ordenar por/ });
  await storeSort.click();
  await storeSort.click();
  await finishLoading();
  const storeQuery = { companyId: company.companyId, q: "Tienda Demo", active: "true", commercialConfiguration: "pending", sortBy: "internalCode", sortDirection: "DESC" };
  assertQuery(storesPath, storeQuery);
  await assertInside(exportButton, "Store export");
  const [download] = await Promise.all([page.waitForEvent("download"), exportButton.click()]);
  assert.equal(download.suggestedFilename(), workbookName);
  assert.deepEqual(await readFile(await download.path()), workbook);
  await download.delete();
  assertQuery(exportPath, { ...storeQuery, language: "es" });
  await screenshot("stores");

  await viewport.evaluate(node => { node.scrollTop = node.scrollHeight; });
  const storeRow = row(targetStore.id);
  await storeRow.waitFor();
  assert.ok(calls.some(call => call.path === storesPath && call.query.page === "1" && call.query.commercialConfiguration === "pending"));
  const companyProfileHash = `#/companies?companyId=${company.companyId}`;
  const storeLicensesHash = `#/licenses?companyId=${company.companyId}&storeId=${targetStore.id}&status=`;
  const companyProfile = linkTo(storeRow, companyProfileHash);
  const storeLicenses = linkTo(storeRow, storeLicensesHash);
  const beforeProfile = await keyboardLink(companyProfile);
  assert.ok(beforeProfile.top > 0, "Company profile link must be opened from a scrolled store page");
  const dialog = page.getByRole("dialog", { name: new RegExp(company.companyName) });
  const companyForm = dialog.getByRole("form", { name: "Ficha de empresa", exact: true });
  await companyForm.waitFor();
  await exactHash(companyProfileHash);
  assertQuery(`${adminPath}/companies/${company.companyId}/profile`, {});
  assert.equal(await companyForm.getByLabel("Empresa", { exact: true }).inputValue(), company.companyName);
  assert.equal(await companyForm.getByLabel("Empresa", { exact: true }).isDisabled(), true);
  assert.equal(await companyForm.getByRole("button", { name: "Guardar ficha", exact: true }).count(), 0);
  await verifyReturn(companyProfile, beforeProfile);
  assertQuery(storesPath, storeQuery);
  assert.equal(await storeFilters.getByLabel("Buscar empresa, tienda o referencia", { exact: true }).inputValue(), "Tienda Demo");
  assert.equal(await table.locator('th[data-column-key="internalCode"]').getAttribute("aria-sort"), "descending");

  const beforeLicenses = await keyboardLink(storeLicenses);
  await row(expired.id).waitFor();
  await exactHash(storeLicensesHash);
  assertQuery(licensesPath, { companyId: company.companyId, storeId: targetStore.id, status: "" });
  assert.deepEqual(new Set(await rowIds()), new Set([week, month, later, expired, blocked].map(row => row.id)));
  await verifyReturn(storeLicenses, beforeLicenses);
  await keyboardLink(storeLicenses);
  await row(expired.id).waitFor();

  const status = main.getByLabel("Estado", { exact: true });
  await status.selectOption("CADUCADA");
  await finishLoading();
  assert.equal(await contentHeading().innerText(), "Licencias caducadas");
  assert.deepEqual(await rowIds(), [expired.id]);
  await status.selectOption("BLOQUEADA_MANUAL");
  await finishLoading();
  assert.equal(await contentHeading().innerText(), "Licencias bloqueadas");
  await main.getByRole("heading", { name: "Licencias bloqueadas", exact: true, level: 1 }).waitFor();
  assert.deepEqual(await rowIds(), [blocked.id]);
  const sevenDays = main.getByRole("button", { name: "Vencen en 7 días", exact: true });
  const thirtyDays = main.getByRole("button", { name: "Vencen en 30 días", exact: true });
  await sevenDays.click();
  await row(week.id).waitFor();
  await finishLoading();
  assert.equal(await contentHeading().innerText(), "Licencias activas");
  assert.equal(await status.inputValue(), "VALIDA");
  const scopedActive = { companyId: company.companyId, storeId: targetStore.id, status: "VALIDA" };
  assertQuery(licensesPath, { ...scopedActive, expiresBefore: "2026-11-26T23:30:00.000Z" });
  assert.equal(await sevenDays.getAttribute("aria-pressed"), "true");
  assert.deepEqual(await rowIds(), [week.id], "The seven-day shortcut includes the exact UTC deadline and excludes the next second");
  await thirtyDays.click();
  await row(month.id).waitFor();
  await finishLoading();
  assertQuery(licensesPath, { ...scopedActive, expiresBefore: "2026-12-19T23:30:00.000Z" });
  assert.equal(await sevenDays.getAttribute("aria-pressed"), "false");
  assert.equal(await thirtyDays.getAttribute("aria-pressed"), "true");
  assert.deepEqual(await rowIds(), [week.id, month.id]);
  await assertInside(sevenDays, "Seven-day expiry filter");
  await assertInside(thirtyDays, "Thirty-day expiry filter");
  await assertInside(exportButton, "License export");
  await assertInside(viewport, "License table viewport");

  permissions = ["VIEW_ADMIN_DATA", "RENEW_LICENSE"];
  await Promise.all([
    page.waitForResponse(response => response.url().endsWith(`${adminPath}/me`)),
    page.getByRole("button", { name: "Actualizar", exact: true }).click(),
  ]);
  await row(week.id).waitFor();
  const renew = row(week.id).getByRole("button", { name: "Renovar", exact: true });
  await renew.waitFor();
  assert.deepEqual((await headers()).slice(0, 3), ["reference", "status", "actions"], "Legacy license layouts should show the new renewal column beside status");
  await screenshot("licenses");
  await renew.focus();
  await page.keyboard.press("Enter");
  const licenseDialog = page.getByRole("dialog", { name: week.reference, exact: true });
  const renewForm = licenseDialog.locator("form");
  await renewForm.waitFor();
  const renewalHeading = licenseDialog.getByRole("heading", { name: "Renovación", exact: true });
  await until(() => renewalHeading.evaluate(node => document.activeElement === node), "focus on the renewal form heading");
  assertQuery(`${licensesPath}/${week.id}`, {});
  assert.equal(await renewForm.getByLabel("Terminales Windows", { exact: true }).inputValue(), String(week.maxWindows));
  assert.equal(await renewForm.locator('button[type="submit"]').isEnabled(), true);
  assert.deepEqual(mutations, [], "Opening renewal must only fetch license detail");
  await page.keyboard.press("Escape");
  await licenseDialog.waitFor({ state: "hidden" });
  await until(() => renew.evaluate(node => document.activeElement === node), "focus on the license renewal button");

  const chooseLanguage = async name => {
    await page.locator(".language-trigger").click();
    await page.locator(".language-menu").getByRole("button", { name: new RegExp(name) }).click();
  };
  for (const [language, heading, expiry, renewLabel] of [
    ["English", "Active licenses", "Expiring within 30 days", "Renew"],
    ["中文", "有效许可证", "30 天内到期", "续期"],
  ]) {
    await chooseLanguage(language);
    await main.getByRole("heading", { name: heading, level: 2, exact: true }).waitFor();
    assert.equal(await main.getByRole("button", { name: expiry, exact: true }).getAttribute("aria-pressed"), "true");
    await row(week.id).getByRole("button", { name: renewLabel, exact: true }).waitFor();
    assert.deepEqual(await rowIds(), [week.id, month.id], `Changing to ${language} must keep the same license scope`);
  }
  await chooseLanguage("Español");
  const [internalCode, companyName, code, name, ...remainingStoreColumns] = legacyOrders.stores;
  const customOrder = [name, internalCode, companyName, code, ...remainingStoreColumns];
  await page.evaluate(({ key, layout }) => localStorage.setItem(key, JSON.stringify(layout)),
    { key: tableLayoutKey(username, "stores"), layout: customOrder.map(preference) });
  await navigate(page, "Tiendas");
  await row(targetStore.id).waitFor();
  assert.deepEqual(await headers(), [...customOrder.filter(column => column !== "lastSyncAt"), "licenses"], "The new column must preserve the user's manual store layout");
  assert.deepEqual(mutations, []);
  assert.deepEqual(errors, []);
  console.log("Directory links E2E passed: company copying and counts, store configuration and Excel filters, keyboard and browser return, UTC license filters, and read-only renewal permissions.");
} finally {
  await browser?.close();
  server.kill();
}
