import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
const { chromium } = await import("playwright").catch(() => import("../../frontend/node_modules/playwright/index.mjs"));

// The directory and company profiles are synthetic. This test never writes business data.
const root = fileURLToPath(new URL("..", import.meta.url));
const base = "http://127.0.0.1:5196/";
const output = fileURLToPath(new URL("../../output/playwright/", import.meta.url));
const address = { linea1: "Calle Sintética 1", ciudad: "Las Palmas", codigoPostal: "35001", provincia: "Palmas, Las", pais: "ES" };
const company = (id, name, overrides = {}) => ({ companyId: id, companyName: name, taxId: "B00000001", taxpayerType: "SOCIEDAD",
  companyAddress: { ...address }, createdAt: "2026-09-20T12:00:00Z", contactName: "Elena Sol", contactPhone: "600111111",
  contactEmail: "elena@example.invalid", supportStatus: "NORMAL", notes: "Datos sintéticos", owners: [
    { name: "Marta Principal", taxId: "11111111H", phone: "600111112", email: "marta@example.invalid" },
    { name: "Sara Segunda", taxId: "X2222222J", phone: "600111113", email: "sara@example.invalid" },
  ], ...overrides });
const companies = [
  company("company-a", "Álamo Comercial"),
  company("company-b", "Álamo Autónomo", { taxId: "12345678Z", taxpayerType: "AUTONOMO", contactName: "Elena Norte", contactPhone: "600222222", contactEmail: "norte@example.invalid",
    companyAddress: { ...address, ciudad: "La Laguna", provincia: "Santa Cruz de Tenerife", codigoPostal: "38201" }, createdAt: "2026-09-10T12:00:00Z", owners: [{ name: "Persona Autónoma", taxId: "12345678Z", phone: "", email: "" }] }),
  company("company-c", "Cedro Servicios", { taxId: "B00000003", contactName: "Pablo Sur", contactPhone: "600333333", contactEmail: "cedro@example.invalid",
    companyAddress: { ...address, ciudad: "Telde", provincia: "Las Palmas", codigoPostal: "35200" }, createdAt: "2026-09-22T12:00:00Z", owners: [{ name: "Persona Cedro", taxId: "33333333P", phone: "", email: "" }] }),
  company("company-d", "Delta Histórico", { taxId: "B00000004", contactName: "Rosa Histórica", contactPhone: "600444444", contactEmail: "delta@example.invalid",
    companyAddress: { ...address, ciudad: "Villa Antigua", provincia: "Provincia histórica", codigoPostal: "99001" }, createdAt: "2026-08-01T12:00:00Z", owners: [] }),
  ...Array.from({ length: 60 }, (_, index) => company(`company-scroll-${index + 1}`, `ZZZ Empresa Sintética ${String(index + 1).padStart(2, "0")}`, {
    taxId: `B${String(80000000 + index)}`, contactName: `Contacto ${index + 1}`, contactPhone: "", contactEmail: "",
    companyAddress: { ...address, provincia: index % 2 ? "Madrid" : "Las Palmas", ciudad: index % 2 ? "Madrid" : "Arucas", codigoPostal: index % 2 ? "28001" : "35400" },
    owners: [{ name: `Propietario ${index + 1}`, taxId: `SYNTHETIC-${index + 1}`, phone: "", email: "" }], createdAt: "2026-08-15T12:00:00Z",
  })),
];
let browser, server;
const calls = [], browserErrors = [];
async function until(check, label = "expected UI state") {
  for (let attempt = 0; attempt < 100; attempt++) {
    const result = await check(); if (result) return result;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error(`Timed out waiting for ${label}`);
}
async function calendarDate(page, label, day) {
  await page.getByLabel(label, { exact: true }).click();
  const calendar = page.getByRole("dialog", { name: label, exact: true });
  await calendar.getByRole("grid").getByRole("button", { name: String(day), exact: true }).click();
  await calendar.getByRole("button", { name: "Cerrar calendario", exact: true }).click();
}

try {
  server = spawn(process.execPath, [fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url)), "--host", "127.0.0.1", "--port", "5196", "--strictPort", "--configLoader", "runner"], { cwd: root, stdio: "ignore", windowsHide: true });
  await until(async () => { if (server.exitCode !== null) throw new Error("Company filters Vite failed to start"); try { return (await fetch(base)).ok; } catch { return false; } }, "Vite startup");
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 }, timezoneId: "Atlantic/Canary" });
  page.setDefaultTimeout(10_000);
  await page.clock.setFixedTime(new Date("2026-09-21T12:00:00Z"));
  page.on("pageerror", error => browserErrors.push(error.message));
  await page.route("**/api/**", async route => {
    const request = route.request(), path = new URL(request.url()).pathname, method = request.method();
    calls.push({ path, method });
    const json = value => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/v1/auth/admin/login") return json({ username: "COMPANY_FILTERS_DEMO", accessToken: "synthetic-token", mode: "admin", expiresAt: "2099-01-01T00:00:00Z", passwordChangeRequired: false });
    if (path === "/api/v1/admin/me") return json({ username: "COMPANY_FILTERS_DEMO", permissions: ["VIEW_ADMIN_DATA", "ADD_COMPANY", "EDIT_COMPANY_DATA"] });
    if (path === "/api/v1/admin/licenses") return json(companies.slice(0, 2).map(company => ({ ...company, licenseReference: `LIC-${company.companyId}`, status: "VALIDA", validUntil: "2099-01-01T00:00:00Z", maxWindows: 1, maxPda: 0 })));
    if (path === "/api/v1/admin/installations") return json([{ installationId: "installation-a", installationReference: "INST-FILTER-DEMO", companyId: "company-a", storeId: "store-a",
      licenseReference: "LIC-company-a", linkedAt: "2026-09-20T10:00:00Z", lastValidatedAt: "2026-09-21T10:00:00Z", lastSyncAt: "2026-09-21T11:00:00Z", active: true,
      appVersion: "synthetic", operatingSystem: "Test", terminalName: "Terminal sintético", lastIp: null, revokedAt: null, revokedBy: null, revocationReason: null, version: 1 }]);
    if (path === "/api/v1/admin/companies") return json(companies);
    if (/\/companies\/[^/]+\/profile$/.test(path) && method === "GET") {
      const selected = companies.find(item => item.companyId === path.split("/")[5]); assert.ok(selected); return json(selected);
    }
    if (path === "/api/v1/admin/sync/sales-summary") return json({ documentCount: 0, total: "0.00" });
    if (path === "/api/v1/admin/reports/advanced") return json({ companies: companies.length, invoices: 0, invoicedTotal: "0.00", paidTotal: "0.00", salesDocuments: 0, salesTotal: "0.00", inventoryMovements: 0, integrations: 0, activeIntegrations: 0 });
    if (method !== "GET") throw new Error(`Filtering/inspection must not mutate business data: ${method} ${path}`);
    return json([]);
  });
  await page.goto(base);
  await page.locator('input[autocomplete="username"]').fill("COMPANY_FILTERS_DEMO");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  await page.locator(".top-nav-list").getByRole("button", { name: "Estado de clientes", exact: true }).click();
  await page.getByLabel("Buscar empresa, licencia, NIF o tienda", { exact: true }).fill("NO-CUSTOMER-MATCHES");
  await page.locator(".top-nav-list").getByRole("button", { name: "Empresas", exact: true }).click();
  const table = page.getByRole("table", { name: "Empresas", exact: true });
  await until(async () => await table.locator("tbody tr").count() === companies.length, "complete synthetic directory");
  const panel = page.locator(".company-list-section");
  const query = panel.getByLabel("Empresa / NIF", { exact: true });
  const province = panel.getByLabel("Provincia", { exact: true });
  const type = panel.getByLabel("Tipo", { exact: true });
  const more = panel.getByRole("button", { name: /Más filtros|Menos filtros/ });
  const chip = label => panel.getByRole("button", { name: `Quitar filtro ${label}`, exact: true });
  const clear = panel.getByRole("button", { name: "Limpiar todos", exact: true });
  const rowIds = () => table.locator("tbody tr").evaluateAll(rows => rows.map(row => row.dataset.rowId));
  const assertRows = async ids => assert.deepEqual((await rowIds()).sort(), [...ids].sort());
  assert.equal(await page.locator(".global-search-criterion, .global-search-value").count(), 0);
  assert.equal(await panel.getByRole("button", { name: "Columnas", exact: true }).count(), 0);
  assert.equal(await panel.getByLabel("Contacto", { exact: true }).count(), 0);
  assert.equal(await panel.getByLabel("Desde", { exact: true }).count(), 0);
  assert.equal(await table.getByRole("columnheader").filter({ hasText: "Propietario 1" }).count(), 1);
  const alamoRow = table.locator('tr[data-row-id="company-a"]');
  assert.equal(await alamoRow.locator('td[data-column-key="owners"]').innerText(), "Marta Principal");
  assert.equal(await alamoRow.getByText("Sara Segunda", { exact: true }).count(), 0, "The compact table displays only the first owner");

  await query.fill("alamo"); await assertRows(["company-a", "company-b"]);
  await province.selectOption("35"); await assertRows(["company-a"]);
  assert.ok((await chip("Provincia").evaluate(button => button.parentElement.textContent)).includes("Las Palmas"), "The INE code filter must display the natural province name");
  await type.selectOption("AUTONOMO"); await assertRows([]);
  await panel.getByText("SIN DATOS", { exact: true }).waitFor();
  await chip("Tipo").click(); await assertRows(["company-a"]);
  assert.equal(await type.inputValue(), "");
  await chip("Empresa / NIF").click();
  assert.equal(await query.inputValue(), "");
  assert.equal((await rowIds()).length, 32, "Both historical and natural Las Palmas spellings share the same province filter");
  await clear.click(); assert.equal((await rowIds()).length, companies.length);
  assert.equal(await panel.getByRole("button", { name: /^Quitar filtro / }).count(), 0);
  console.log("Company filters E2E: panel-only filters, accent-insensitive company search, AND combinations, province aliases, removable chips and clear-all passed.");

  await more.click();
  const owner = panel.getByLabel("Propietario (nombre o DNI/NIE)", { exact: true });
  await owner.fill("sara segunda"); await assertRows(["company-a"]);
  await owner.fill("X2222222J"); await assertRows(["company-a"]);
  assert.equal(await alamoRow.locator('td[data-column-key="owners"]').innerText(), "Marta Principal");
  await alamoRow.dblclick();
  const detailDialog = page.getByRole("dialog", { name: "Ficha de empresa: Álamo Comercial", exact: true });
  const detail = detailDialog.getByRole("form", { name: "Ficha de empresa", exact: true });
  await detail.waitFor();
  const contact = detail.getByRole("group", { name: "Contacto", exact: true });
  assert.equal(await contact.getByLabel("Nombre", { exact: true }).inputValue(), "Elena Sol");
  assert.equal(await detail.getByRole("group", { name: "Propietario 1", exact: true }).getByLabel("Nombre completo", { exact: true }).inputValue(), "Marta Principal");
  assert.equal(await detail.getByRole("group", { name: "Propietario 2", exact: true }).getByLabel("Nombre completo", { exact: true }).inputValue(), "Sara Segunda");
  assert.equal(await detail.getByRole("group", { name: "Propietario 2", exact: true }).getByLabel("DNI/NIE", { exact: true }).inputValue(), "X2222222J");
  assert.equal(await detailDialog.getByText("INST-FILTER-DEMO", { exact: true }).count(), 1,
    "An unrelated global search from another screen must not hide this company's linked installations");
  await page.keyboard.press("Escape"); await detailDialog.waitFor({ state: "hidden" });
  assert.equal(await alamoRow.evaluate(row => document.activeElement === row), true);
  await clear.click();

  const secondary = [
    ["Contacto", "elena sol"], ["Teléfono", "600111111"], ["Email contacto", "ELENA@EXAMPLE.INVALID"],
    ["Propietario (nombre o DNI/NIE)", "X2222222J"], ["Ciudad", "las palmas"], ["Codigo postal", "35001"],
  ];
  await query.fill("B00000001"); await province.selectOption("35"); await type.selectOption("SOCIEDAD");
  for (const [label, value] of secondary) { await panel.getByLabel(label, { exact: true }).fill(value); await assertRows(["company-a"]); }
  assert.equal(await panel.getByRole("button", { name: /^Quitar filtro / }).count(), 9);
  await more.click();
  assert.equal(await owner.count(), 0, "Closing the advanced panel does not leave hidden editable controls");
  assert.equal(await panel.getByRole("button", { name: /^Quitar filtro / }).count(), 9, "Active filters remain visible as removable chips when the panel is collapsed");
  await assertRows(["company-a"]);
  await chip("Contacto").click();
  assert.equal(await panel.getByRole("button", { name: /^Quitar filtro / }).count(), 8);
  await more.click(); assert.equal(await panel.getByLabel("Contacto", { exact: true }).inputValue(), "");
  await panel.getByRole("button", { name: "Fecha de alta", exact: true }).click();
  for (const { width, height } of [{ width: 1366, height: 768 }, { width: 1280, height: 720 }]) {
    await page.setViewportSize({ width, height });
    const geometry = await panel.locator(".company-table-region .saas-data-table-scroll").evaluate(element => ({
      height: element.clientHeight, header: element.querySelector("thead").getBoundingClientRect().height,
      row: element.querySelector("tbody tr").getBoundingClientRect().height,
    }));
    assert.ok(geometry.height >= geometry.header + geometry.row,
      `Expanded filters, dates and eight chips must retain at least one full data row at ${width}x${height}: ${JSON.stringify(geometry)}`);
  }
  await page.setViewportSize({ width: 1600, height: 1000 });
  await panel.getByRole("button", { name: "Fecha de alta", exact: true }).click();
  await clear.click();
  for (const [label] of secondary) assert.equal(await panel.getByLabel(label, { exact: true }).inputValue(), "");
  await more.click();
  console.log("Company filters E2E: secondary contact/address filters, second-owner name/DNI search, first-owner table and complete untouched owner profile passed.");

  await panel.getByRole("button", { name: "Fecha de alta", exact: true }).click();
  await calendarDate(page, "Desde", 20);
  await assertRows(["company-a", "company-c"]);
  await calendarDate(page, "Hasta", 20);
  await assertRows(["company-a"]);
  await chip("Fecha de alta").waitFor();
  await panel.getByRole("button", { name: "Fecha de alta", exact: true }).click();
  assert.equal(await panel.getByLabel("Desde", { exact: true }).count(), 0);
  await assertRows(["company-a"]);
  await chip("Fecha de alta").click(); assert.equal((await rowIds()).length, companies.length);
  await panel.getByRole("button", { name: "Fecha de alta", exact: true }).click();
  assert.equal(await panel.getByLabel("Desde", { exact: true }).inputValue(), "");
  assert.equal(await panel.getByLabel("Hasta", { exact: true }).inputValue(), "");
  await panel.getByRole("button", { name: "Fecha de alta", exact: true }).click();
  console.log("Company filters E2E: shared date-picker open/bounded range, inclusive endpoints and one chip clearing both dates passed.");

  await query.fill("ZZZ"); await province.selectOption("35");
  const scroll = panel.locator(".company-table-region .saas-data-table-scroll");
  const last = table.locator('tr[data-row-id="company-scroll-59"]');
  await until(async () => await table.locator("tbody tr").count() === 30);
  await mkdir(output, { recursive: true });
  for (const { width, height } of [{ width: 1600, height: 1000 }, { width: 1280, height: 900 }]) {
    await page.setViewportSize({ width, height });
    await scroll.evaluate(element => { element.scrollTop = 0; element.scrollLeft = 0; });
    const heading = table.locator('th[data-column-key="companyName"]'); const before = await heading.boundingBox();
    const queryBefore = await query.boundingBox();
    await table.locator("tbody tr").first().focus(); await page.keyboard.press("End");
    assert.equal(await last.evaluate(element => document.activeElement === element), true);
    const after = await heading.boundingBox(); assert.ok(before && after && Math.abs(before.y - after.y) <= 1);
    assert.deepEqual(await query.boundingBox(), queryBefore, "Filter controls must not move when the filtered table scrolls");
    const geometry = await page.evaluate(() => [document.querySelector("main"), document.scrollingElement].map(element => ({
      scrollTop: element.scrollTop, scrollLeft: element.scrollLeft, clientWidth: element.clientWidth, scrollWidth: element.scrollWidth,
      clientHeight: element.clientHeight, scrollHeight: element.scrollHeight,
    })));
    for (const outer of geometry) assert.ok(outer.scrollTop <= 1 && outer.scrollLeft <= 1 && outer.scrollWidth <= outer.clientWidth + 1 && outer.scrollHeight <= outer.clientHeight + 1,
      `Only the company table should scroll at ${width}x${height}: ${JSON.stringify(outer)}`);
    await page.mouse.move(width - 20, 60);
    await page.screenshot({ path: `${output}/saas-company-filters-${width}.png`, animations: "disabled" });
  }
  await last.dblclick();
  await page.getByRole("dialog", { name: "Ficha de empresa: ZZZ Empresa Sintética 59", exact: true }).getByRole("form", { name: "Ficha de empresa", exact: true }).waitFor();
  await page.keyboard.press("Escape");
  assert.equal(calls.filter(call => call.method !== "GET" && call.path !== "/api/v1/auth/admin/login").length, 0);
  assert.deepEqual(browserErrors, []);
  console.log("Company filters E2E: filtered internal scrolling, sticky headings, stable controls and double-click profile at 1600/1280 passed.");
  await writeFile(`${output}/company-filters-smoke.log`, "PASS: company panel filters, AND matching, removable chips, date range, owner2 search/owner1 display, preserved profile, internal scrolling and no business mutations.\n");
} catch (error) {
  const page = browser?.contexts()[0]?.pages()[0];
  if (page) {
    await mkdir(output, { recursive: true });
    await writeFile(`${output}/company-filters-failure.txt`, await page.locator("body").innerText());
    await page.screenshot({ path: `${output}/saas-company-filters-failure.png`, fullPage: true });
  }
  throw error;
} finally { await browser?.close(); server?.kill(); }
