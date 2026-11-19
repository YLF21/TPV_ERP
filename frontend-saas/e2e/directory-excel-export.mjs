import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import { emptySummary, summaryPath } from "./summary-fixture.mjs";

const { chromium } = await import("playwright").catch(() => import("../../frontend/node_modules/playwright/index.mjs"));
const root = fileURLToPath(new URL("..", import.meta.url));
const base = "http://127.0.0.1:5217/";
const screenshots = process.env.SAAS_DIRECTORY_EXCEL_SCREENSHOTS;
const exportPath = "/api/v1/admin/directories/export/";
const date = "2026-09-21";
const mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
const datasetNames = {
  companies: { es: "Empresas", en: "Companies", zh: "企业" },
  stores: { es: "Tiendas", en: "Stores", zh: "门店" },
  licenses: { es: "Licencias", en: "Licenses", zh: "许可证" },
};
const exportLabels = { es: "Exportar Excel", en: "Export Excel", zh: "导出 Excel" };
const fileName = (dataset, language) => `espos-${datasetNames[dataset][language]}-${date}.xlsx`;
// Backend tests validate the OOXML. Distinct bytes here detect stale or crossed browser downloads.
const workbook = (dataset, language) => Buffer.from(`PK\u0003\u0004synthetic-${dataset}-${language}\u0000\u00ff`, "latin1");
const id = number => `10000000-0000-4000-8000-${String(number).padStart(12, "0")}`;
const address = { linea1: "Calle sintética", provincia: "Las Palmas", ciudad: "Las Palmas", codigoPostal: "35001", pais: "ES" };
const makeCompany = (number, contactName = "Contacto Demo") => ({
  companyId: id(number), companyName: `Empresa Cohorte ${String(number).padStart(2, "0")}`, taxId: `B${String(number).padStart(8, "0")}`,
  taxpayerType: "SOCIEDAD", companyAddress: address, createdAt: "2026-09-20T12:00:00Z", contactName,
  contactPhone: "600000000", contactEmail: "contacto@example.invalid", supportStatus: "NORMAL",
  owners: [{ name: "Persona Principal", taxId: "SYNTHETIC-1" }, { name: "Persona Secundaria", taxId: "SYNTHETIC-2" }],
});
const companies = Array.from({ length: 36 }, (_, index) => makeCompany(index + 1));
companies.splice(14, 0, makeCompany(37, "Otro contacto"));
const stores = Array.from({ length: 36 }, (_, index) => ({
  id: id(101 + index), companyId: companies[0].companyId, companyName: companies[0].companyName,
  code: String(101 + index), internalCode: String(3500000 + index), name: `Tienda Cohorte ${101 + index}`,
  active: index === 35, storeAddress: address, timeZoneId: "Atlantic/Canary", installations: 0, activeInstallations: 0,
  taxRegime: "IGIC", commercialProfile: "MINORISTA", billingPeriod: "MONTHLY", servicePrice: "29.00",
  maxWindows: 1, maxPda: 0, validUntil: "2026-09-24T00:00:00Z", lastSyncAt: null,
}));
const licenses = stores.map((store, index) => ({
  id: id(201 + index), reference: `LIC-COHORTE-${201 + index}`, companyId: store.companyId,
  companyName: store.companyName, taxId: companies[0].taxId, status: index === 35 ? "VALIDA" : "BLOQUEADA_MANUAL",
  validUntil: "2026-09-24T00:00:00Z", activeInstallations: 1, maxWindows: 1, maxPda: 0,
  lastValidatedAt: null, lastSyncAt: null, stores: [store], companyBillingStatus: "PENDIENTE", companyDebt: [],
}));

async function until(check, label) {
  for (let attempt = 0; attempt < 100; attempt++) {
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

async function calendarDate(container, label, day) {
  await container.getByLabel(label, { exact: true }).click();
  const calendar = container.page().getByRole("dialog", { name: label, exact: true });
  await calendar.getByRole("grid").getByRole("button", { name: String(day), exact: true }).click();
  await calendar.getByRole("button", { name: "Cerrar calendario", exact: true }).click();
}

function listPage(rows, { page, size, sortBy, sortDirection }) {
  const offset = Number(page) * Number(size);
  const sorted = [...rows].sort((a, b) => String(a[sortBy] ?? "").localeCompare(String(b[sortBy] ?? "")) * (sortDirection === "DESC" ? -1 : 1));
  return { items: sorted.slice(offset, offset + Number(size)), page: Number(page), size: Number(size), total: rows.length, totalPages: Math.ceil(rows.length / Number(size)) };
}

const server = spawn(process.execPath, [fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url)), "--host", "127.0.0.1", "--port", "5217", "--strictPort", "--configLoader", "runner"], { cwd: root, stdio: "ignore", windowsHide: true });
let browser;
try {
  await until(async () => {
    if (server.exitCode !== null) throw new Error("Directory export test could not start Vite on 5217");
    try { return (await fetch(base)).ok; } catch { return false; }
  }, "Vite startup");
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ acceptDownloads: true, viewport: { width: 1280, height: 800 }, timezoneId: "Atlantic/Canary" });
  page.setDefaultTimeout(8_000);
  await page.clock.setFixedTime(new Date(`${date}T12:00:00Z`));
  const errors = [], exports = [], downloads = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("download", download => downloads.push(download));
  let heldExport, attemptedExportFailure = false, heldStores;
  const hold = () => {
    const pending = {};
    pending.requested = new Promise(resolve => { pending.started = resolve; });
    pending.released = new Promise(resolve => { pending.release = resolve; });
    return pending;
  };
  const problem = route => route.fulfill({ status: 503, contentType: "application/problem+json", json: { detail: "Fallo de exportación sintético" } });
  const token = "synthetic-token";
  const username = "EXCEL_DIRECTORY_DEMO";
  await page.route("**/api/**", async route => {
    const request = route.request();
    const { pathname: path, searchParams } = new URL(request.url());
    const params = Object.fromEntries(searchParams);
    const method = request.method();
    const json = value => route.fulfill({ status: 200, contentType: "application/json", json: value });
    if (path === "/api/v1/auth/admin/login") return json({ username, accessToken: token, mode: "admin", passwordChangeRequired: false, expiresAt: "2099-01-01T00:00:00Z" });
    if (path === "/api/v1/admin/me") return json({ username, permissions: ["VIEW_ADMIN_DATA"] });
    if (path === `${summaryPath}/options`) return json({ companies: [], stores: [] });
    if (path === summaryPath) return json(emptySummary);
    if (path === "/api/v1/admin/companies" && method === "GET") return json(companies);
    if (path === "/api/v1/admin/stores" && method === "GET") {
      const pending = heldStores;
      if (pending) {
        pending.started();
        if (await pending.released === "fail") return problem(route);
      }
      return json(listPage(stores.filter(row => (!params.companyId || row.companyId === params.companyId)
        && (!params.q || row.name.toLowerCase().includes(params.q.toLowerCase()))
        && (params.active === undefined || String(row.active) === params.active)), params));
    }
    if (path === "/api/v1/admin/license-workspace" && method === "GET") {
      return json(listPage(licenses.filter(row => (!params.companyId || row.companyId === params.companyId)
        && (!params.q || row.reference.toLowerCase().includes(params.q.toLowerCase()))
        && (!params.status || row.status === params.status)
        && (!params.expiresBefore || Date.parse(row.validUntil) < Date.parse(params.expiresBefore))
        && (!params.hasConnections || String(row.activeInstallations > 0) === params.hasConnections)
        && (!params.billingStatus || row.companyBillingStatus === params.billingStatus)), params));
    }
    if (path.startsWith(exportPath)) {
      const dataset = path.slice(exportPath.length);
      const authorization = await request.headerValue("authorization");
      exports.push({ dataset, method, params, body: request.postDataJSON(), authorization });
      const pending = heldExport;
      if (pending) {
        heldExport = undefined;
        pending.started();
        await pending.released;
      }
      if (attemptedExportFailure) {
        attemptedExportFailure = false;
        return problem(route);
      }
      const name = fileName(dataset, params.language);
      return route.fulfill({ status: 200, contentType: mimeType, body: workbook(dataset, params.language),
        headers: { "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(name)}` } });
    }
    if (path === "/api/v1/admin/sync/sales-summary") return json({ documentCount: 0, total: "0.00" });
    if (path === "/api/v1/admin/reports/advanced") return json({ companies: companies.length, invoices: 0, invoicedTotal: 0, paidTotal: 0, salesDocuments: 0, salesTotal: 0, inventoryMovements: 0, integrations: 0, activeIntegrations: 0 });
    if (method !== "GET") {
      errors.push(`Unexpected mutation ${method} ${path}`);
      return problem(route);
    }
    return json([]);
  });

  await page.goto(base);
  await page.locator('input[autocomplete="username"]').fill(username);
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  const main = page.locator("main");
  const exportButton = (language = "es") => main.getByRole("button", { name: exportLabels[language], exact: true });
  const busy = () => main.getByRole("button", { name: "Exportando…", exact: true });
  const rows = () => main.locator(".saas-data-table tbody tr");
  const assertLoaded = async count => {
    await until(async () => await rows().count() === count && await exportButton().isEnabled(), `${count} directory rows and enabled export`);
  };
  const confirmDownload = async (download, dataset, language) => {
    assert.equal(download.suggestedFilename(), fileName(dataset, language));
    assert.deepEqual(await readFile(await download.path()), workbook(dataset, language));
    await download.delete();
  };
  const downloadWith = async (dataset, language = "es") => {
    const [download] = await Promise.all([page.waitForEvent("download"), exportButton(language).click()]);
    await confirmDownload(download, dataset, language);
  };
  const assertExport = (dataset, method, params, body = null) => {
    assert.deepEqual(exports.at(-1), { dataset, method, params, body, authorization: `Bearer ${token}` });
  };
  const chooseCompany = async container => {
    await container.getByRole("combobox", { name: "Empresa", exact: true }).fill(companies[0].taxId);
    await container.getByRole("option", { name: /Empresa Cohorte 01/ }).click();
  };
  const fits = async (dataset, heading) => {
    for (const width of [960, 1280]) {
      await page.setViewportSize({ width, height: 800 });
      const bounds = await exportButton().boundingBox();
      assert.ok(bounds && bounds.x >= 0 && bounds.x + bounds.width <= width && bounds.y >= 0 && bounds.y + bounds.height <= 800,
        `${dataset} export button must stay within the ${width}×800 viewport`);
    }
    if (screenshots) {
      await mkdir(screenshots, { recursive: true });
      await main.locator(heading).screenshot({ path: join(screenshots, `${dataset}-heading.png`) });
    }
  };
  const releaseWithoutDownload = async (pending, label) => {
    const count = downloads.length;
    const late = page.waitForEvent("download", { timeout: 600 }).catch(error => {
      if (error.name !== "TimeoutError") throw error;
      return null;
    });
    pending.release();
    assert.equal(await late, null, label);
    assert.equal(downloads.length, count, label);
  };

  await navigate(page, "Empresas");
  const companiesSection = main.locator(".company-list-section");
  await assertLoaded(companies.length);
  await fits("companies", ".company-list-heading");
  await companiesSection.getByLabel("Empresa / NIF", { exact: true }).fill("cohorte");
  await companiesSection.getByLabel("Provincia", { exact: true }).selectOption("35");
  await companiesSection.getByLabel("Tipo", { exact: true }).selectOption("SOCIEDAD");
  await companiesSection.getByRole("button", { name: "Fecha de alta (UTC)", exact: true }).click();
  await calendarDate(companiesSection, "Desde", 20);
  await calendarDate(companiesSection, "Hasta", 21);
  await companiesSection.getByRole("button", { name: "Fecha de alta (UTC)", exact: true }).click();
  await companiesSection.getByRole("button", { name: /Más filtros/ }).click();
  for (const [label, value] of [
    ["Contacto", "contacto demo"], ["Teléfono", "600 000 000"], ["Email contacto", "@example.invalid"],
    ["Propietario (nombre o DNI/NIE)", "secundaria"], ["Ciudad", "las palmas"], ["Codigo postal", "35001"],
  ]) await companiesSection.getByLabel(label, { exact: true }).fill(value);
  await companiesSection.getByRole("button", { name: /Menos filtros/ }).click();
  await companiesSection.getByRole("button", { name: "Ordenar por Empresa", exact: true }).click();
  await assertLoaded(36);
  const companyFilters = {
    query: "cohorte", province: "35", taxpayerType: "SOCIEDAD", dateFrom: "2026-09-20", dateTo: "2026-09-21",
    contactName: "contacto demo", contactPhone: "600 000 000", contactEmail: "@example.invalid", owner: "secundaria", city: "las palmas", postalCode: "35001",
  };
  const filteredIds = Array.from({ length: 36 }, (_, index) => id(36 - index));
  const companyRequest = { companyIds: filteredIds, filters: companyFilters, sortBy: "companyName", sortDirection: "DESC" };
  const companyExport = heldExport = hold();
  const companyDownload = page.waitForEvent("download");
  await exportButton().click();
  await companyExport.requested;
  await busy().waitFor();
  assert.equal(await busy().isDisabled(), true);
  await busy().evaluate(button => button.click());
  assert.equal(exports.length, 1, "Repeated export clicks must not send another request");
  assertExport("companies", "POST", { language: "es" }, companyRequest);
  companyExport.release();
  await confirmDownload(await companyDownload, "companies", "es");
  assert.equal(exports.length, 1, "The repeated click must not send a delayed export request");

  const storesLoad = heldStores = hold();
  await navigate(page, "Tiendas");
  await storesLoad.requested;
  await exportButton().waitFor();
  assert.equal(await exportButton().isDisabled(), true, "Store export must wait for the directory query");
  heldStores = undefined;
  storesLoad.release("fail");
  await main.locator(".retry-error").waitFor();
  assert.equal(await exportButton().isDisabled(), true, "Store export must remain disabled after directory failure");
  await main.getByRole("button", { name: "Reintentar", exact: true }).click();
  await assertLoaded(25);
  await fits("stores", ".stores-list-heading");
  const storesToolbar = main.getByRole("search", { name: "Tiendas", exact: true });
  const storeSearch = storesToolbar.getByLabel("Buscar empresa, tienda o referencia", { exact: true });
  await storeSearch.fill("cohorte");
  await chooseCompany(storesToolbar);
  await storesToolbar.getByRole("button", { name: /Más filtros/ }).click();
  await storesToolbar.getByLabel("Estado", { exact: true }).selectOption("false");
  await storesToolbar.getByRole("button", { name: /Menos filtros/ }).click();
  const sortStore = main.getByRole("button", { name: "Ordenar por Código interno", exact: true });
  await sortStore.click();
  await sortStore.click();
  await assertLoaded(25);
  const storesParams = { companyId: companies[0].companyId, q: "cohorte", active: "false", sortBy: "internalCode", sortDirection: "DESC", language: "es" };
  await downloadWith("stores");
  assertExport("stores", "GET", storesParams);

  attemptedExportFailure = true;
  await exportButton().click();
  const alert = main.getByRole("alert");
  await alert.waitFor();
  assert.match(await alert.innerText(), /no está disponible temporalmente.*vuelve a intentarlo/i);
  assert.equal(await exportButton().isEnabled(), true, "The same export can be retried after an error");
  const staleStore = heldExport = hold();
  await exportButton().click();
  await staleStore.requested;
  await storeSearch.fill("cohorte 1");
  await assertLoaded(25);
  await releaseWithoutDownload(staleStore, "An export for the previous store filter must not download");
  await downloadWith("stores");
  assertExport("stores", "GET", { ...storesParams, q: "cohorte 1" });

  await navigate(page, "Licencias activas");
  await assertLoaded(1);
  await fits("licenses", ".directory-license-heading");
  const licenseToolbar = main.getByRole("search");
  await licenseToolbar.getByLabel("Buscar empresa, tienda o referencia", { exact: true }).fill("lic-cohorte");
  await chooseCompany(licenseToolbar);
  await licenseToolbar.getByLabel("Estado", { exact: true }).selectOption("BLOQUEADA_MANUAL");
  await licenseToolbar.getByRole("button", { name: /Más filtros/ }).click();
  await calendarDate(licenseToolbar, "Caduca antes de (UTC)", 25);
  await licenseToolbar.getByLabel("Con instalaciones activas", { exact: true }).selectOption("true");
  await licenseToolbar.getByLabel("Facturación de la empresa", { exact: true }).selectOption("PENDIENTE");
  await licenseToolbar.getByRole("button", { name: /Menos filtros/ }).click();
  const sortLicense = main.getByRole("button", { name: "Ordenar por Referencia", exact: true });
  await sortLicense.click();
  await sortLicense.click();
  await assertLoaded(25);
  const licenseParams = { companyId: companies[0].companyId, q: "lic-cohorte", status: "BLOQUEADA_MANUAL", expiresBefore: "2026-09-25T00:00:00.000Z",
    hasConnections: "true", billingStatus: "PENDIENTE", sortBy: "reference", sortDirection: "DESC" };
  await downloadWith("licenses");
  assertExport("licenses", "GET", { ...licenseParams, language: "es" });

  const staleLanguage = heldExport = hold();
  await exportButton().click();
  await staleLanguage.requested;
  const chooseLanguage = async label => {
    await page.locator(".language-trigger").click();
    await page.locator(".language-menu").getByRole("button", { name: new RegExp(label) }).click();
  };
  await chooseLanguage("English");
  await until(() => exportButton("en").isEnabled(), "English export after canceling the previous language");
  await releaseWithoutDownload(staleLanguage, "An export for the previous language must not download");
  await downloadWith("licenses", "en");
  assertExport("licenses", "GET", { ...licenseParams, language: "en" });
  await chooseLanguage("中文");
  await downloadWith("licenses", "zh");
  assertExport("licenses", "GET", { ...licenseParams, language: "zh" });

  await chooseLanguage("Español");
  const staleView = heldExport = hold();
  await exportButton().click();
  await staleView.requested;
  await navigate(page, "Empresas");
  await main.locator(".company-list-section").waitFor();
  await releaseWithoutDownload(staleView, "An export from a closed screen must not download");
  assert.deepEqual(errors, []);
  console.log("Directory Excel E2E: complete filtered requests, downloads ES/EN/ZH, permissions, compact headers, loading, errors and cancellation passed.");
} finally {
  await browser?.close();
  server.kill();
}
