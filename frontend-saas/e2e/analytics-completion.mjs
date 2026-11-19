import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";
import { inventoryPath, reportFixture, reportsPath, statisticsAsOf, statisticsPath } from "./analytics-completion-fixture.mjs";

const root = fileURLToPath(new URL("..", import.meta.url));
const vite = fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url));
const base = "http://127.0.0.1:5207/";
const server = spawn(process.execPath, [vite, "--host", "127.0.0.1", "--port", "5207", "--strictPort", "--configLoader", "runner"], {
  cwd: root, stdio: "inherit", windowsHide: true,
});
const token = "analytics-completion-test-token";
const loginPath = "/api/v1/auth/admin/login";
const sessionPath = "/api/v1/admin/me";
const articlePath = reportsPath + "/articles";
const documentsPath = reportsPath + "/documents";
const movementsPath = reportsPath + "/movements";
const ledgerPath = movementsPath + "/ledger";
const comparisonPath = inventoryPath + "/comparison";
const exportsPath = reportsPath + "/export";
const table = (page, name) => page.getByRole("table", { name, exact: true });
const button = (page, name) => page.getByRole("button", { name, exact: true });
const filter = (page, name) => page.getByRole("combobox", { name, exact: true });
const nav = (page, name) => button(page.getByRole("group", { name: "Informes", exact: true }), name);
const apply = page => button(page, "Aplicar filtros");
const errors = [];
let active;
let browser;

async function start() {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error("Analytics Vite server exited before startup");
    try { if ((await fetch(base)).ok) return; } catch { /* Wait for startup. */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("Analytics Vite startup timed out");
}

async function open(permissions, hash, username = "ANALYTICS_E2E") {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, timezoneId: "America/Los_Angeles" });
  const calls = [];
  active = { page, calls };
  page.setDefaultTimeout(10_000);
  await page.clock.setFixedTime(new Date(statisticsAsOf));
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/api/v1/**", async route => {
    const request = route.request();
    const url = new URL(request.url());
    const { pathname, searchParams } = url;
    calls.push({ pathname, searchParams, auth: request.headers().authorization, method: request.method() });
    if (pathname === loginPath) {
      return route.fulfill({ json: { username, accessToken: token, mode: "admin", passwordChangeRequired: false, expiresAt: "2099-01-01T00:00:00Z" } });
    }
    if (pathname === sessionPath) return route.fulfill({ json: { username, permissions } });
    if (pathname.startsWith(exportsPath + "/")) {
      return route.fulfill({ contentType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", body: "xlsx-download-fixture" });
    }
    return route.fulfill({ json: reportFixture(pathname, searchParams) });
  });
  await page.goto(base + hash);
  await page.locator('input[autocomplete="username"]').fill(username);
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  await page.locator(".topbar h1").waitFor();
  return { page, calls };
}

const matches = (pathname, filters) => response => {
  const url = new URL(response.url());
  return url.pathname === pathname && Object.entries(filters).every(([name, value]) => url.searchParams.get(name) === value);
};
async function expectRequest(page, pathname, filters, action) {
  const [response] = await Promise.all([page.waitForResponse(matches(pathname, filters)), action()]);
  assert.ok(response.ok());
}
const go = (page, hash) => page.evaluate(next => { window.location.hash = next; }, hash);

async function verifySales(page) {
  await page.locator(".statistics-details summary").press("Enter");
  const aggregate = table(page, "Importes por moneda");
  const euro = aggregate.getByRole("row").filter({ has: page.getByRole("rowheader", { name: "EUR", exact: true }) });
  await euro.waitFor();
  assert.deepEqual((await euro.getByRole("cell").allTextContents()).slice(0, 3),
    ["9.007.199.254.740.993,17", "603,00", "+9.007.199.254.740.390,17"]);
  const dollar = aggregate.getByRole("row").filter({ has: page.getByRole("rowheader", { name: "USD", exact: true }) });
  assert.deepEqual(await dollar.getByRole("cell").allTextContents(), ["-15,25", "—", "—", "—"]);
  const pound = aggregate.getByRole("row").filter({ has: page.getByRole("rowheader", { name: "GBP", exact: true }) });
  assert.deepEqual(await pound.getByRole("cell").allTextContents(), ["0,00", "—", "—", "—"]);

  await filter(page, "Agrupar por").selectOption("MONTH");
  await expectRequest(page, statisticsPath, { groupBy: "MONTH", province: "35", comparison: "CUSTOM" }, () => apply(page).click());
  await table(page, "Documentos por mes").waitFor();
  await filter(page, "Agrupar por").selectOption("WEEK");
  await expectRequest(page, statisticsPath, { groupBy: "WEEK", province: "35" }, () => apply(page).click());
  const weekly = table(page, "Documentos por semana");
  const priorOnly = weekly.getByRole("row").filter({ has: page.locator('time[datetime="2026-06-08"]') });
  await priorOnly.waitFor();
  assert.deepEqual((await priorOnly.getByRole("cell").allTextContents()).slice(1, 4), ["—", "—", "—"]);
  await filter(page, "Moneda del gráfico").selectOption("USD");
  await page.getByRole("img", { name: "Ventas netas por semana · USD", exact: true }).waitFor();
  await expectRequest(page, documentsPath, { from: "2026-06-08", to: "2026-06-14", province: "35" },
    () => priorOnly.getByRole("link", { name: "Ver documentos · Período comparado", exact: true }).click());
  await table(page, "Detalle de documentos").getByRole("rowheader", { name: "T0001", exact: true }).waitFor();
  await expectRequest(page, statisticsPath, { groupBy: "WEEK", province: "35" },
    () => page.getByRole("link", { name: "Volver al informe", exact: true }).click());

  await expectRequest(page, statisticsPath, { groupBy: "PROVINCE", province: "35" }, () => go(page, "#/reports/provinces?province=35"));
  const province = table(page, "Cobertura de inventario por provincia");
  await province.getByRole("rowheader", { name: "Las Palmas", exact: true }).waitFor();
}

async function verifyArticles(page) {
  await expectRequest(page, articlePath, { sort: "AMOUNT", direction: "DESC", page: "0", from: "2026-07-01", to: "2026-07-03", province: "35" }, () => nav(page, "Artículos").click());
  const ranking = table(page, "Clasificación de artículos");
  await ranking.getByRole("cell", { name: "9.007.199.254.740.993,17", exact: true }).waitFor();
  assert.equal(await page.getByText("Consultas y columnas", { exact: true }).count(), 0);
  assert.equal(await ranking.getByRole("columnheader", { name: "Importe devuelto", exact: true }).count(), 0);
  await page.getByText("Columnas", { exact: true }).click();
  for (const checkbox of await page.locator(".analytics-columns input:not(:disabled)").all()) await checkbox.check();
  await page.getByText("Columnas", { exact: true }).click();
  await ranking.getByRole("cell", { name: "+6,000", exact: true }).first().waitFor();
  const absent = ranking.getByRole("row").filter({ has: page.getByRole("rowheader", { name: "Harina de trigo", exact: true }) });
  assert.ok((await absent.getByRole("cell").allTextContents()).includes("0,000"));
  assert.ok((await absent.getByRole("cell").allTextContents()).includes("—"));
  const coffee = ranking.getByRole("row").filter({ has: page.getByRole("rowheader", { name: "Café natural", exact: true }) });
  await coffee.getByRole("cell", { name: "3,625", exact: true }).waitFor();
  await coffee.getByRole("cell", { name: "70,00 %", exact: true }).waitFor();
  for (const [name, minWidth] of [["Empresa", 110], ["Tienda", 110], ["Artículo", 180]]) {
    const width = await ranking.getByRole("columnheader", { name, exact: true }).evaluate(cell => cell.getBoundingClientRect().width);
    assert.ok(width >= minWidth, name + " must retain a readable width");
  }
  const viewport = page.viewportSize();
  await page.setViewportSize({ width: 390, height: 844 });
  const rankingRegion = page.getByRole("region", { name: "Clasificación de artículos", exact: true }).and(page.locator(".table-wrap"));
  assert.ok(await rankingRegion.evaluate(region => {
    region.scrollLeft = 100;
    return region.scrollLeft > 0 && document.body.scrollWidth <= document.documentElement.clientWidth;
  }), "The article table must scroll horizontally inside its region");
  await page.setViewportSize(viewport);

  const codeSort = button(ranking, "Código");
  const chartCurrency = filter(page, "Moneda del gráfico");
  await chartCurrency.selectOption("USD");
  const dollarChart = page.getByRole("img", { name: "Importe neto · USD", exact: true });
  await dollarChart.waitFor();
  await expectRequest(page, articlePath, { sort: "CODE", direction: "ASC", page: "0" }, () => codeSort.click());
  await ranking.getByRole("columnheader", { name: "Código", exact: true }).and(ranking.locator('[aria-sort="ascending"]')).waitFor();
  await expectRequest(page, articlePath, { sort: "CODE", direction: "ASC", page: "1" }, () => button(page, "Siguiente").click());
  await ranking.getByRole("rowheader", { name: "Referencia 24", exact: true }).waitFor();
  await dollarChart.waitFor();
  assert.equal(await chartCurrency.inputValue(), "USD", "The chart retains currencies beyond the current table page");
  const reference = ranking.getByRole("row").filter({ has: page.getByRole("rowheader", { name: "Referencia 24", exact: true }) });
  await expectRequest(page, documentsPath, { productId: "ref-24" }, () => reference.getByRole("link", { name: "Ver documentos · Referencia 24 · Período consultado", exact: true }).click());
  await expectRequest(page, articlePath, { sort: "CODE", direction: "ASC", page: "1" }, () => page.getByRole("link", { name: "Volver al informe", exact: true }).click());
  await ranking.getByRole("rowheader", { name: "Referencia 24", exact: true }).waitFor();
  await dollarChart.waitFor();
  const search = page.getByRole("searchbox", { name: "Buscar artículo o código" });
  await search.fill("CAF");
  await expectRequest(page, articlePath, { search: "CAF", page: "0" }, () => apply(page).click());
  await ranking.getByRole("rowheader", { name: "Café natural", exact: true }).waitFor();
  assert.equal(await ranking.getByRole("rowheader", { name: "Harina de trigo", exact: true }).count(), 0);

  await page.getByText("Columnas", { exact: true }).click();
  await page.getByRole("checkbox", { name: "Importe devuelto", exact: true }).uncheck();
  assert.equal(await ranking.getByRole("columnheader", { name: "Importe devuelto", exact: true }).count(), 0);
  await nav(page, "Movimientos").click();
  await expectRequest(page, articlePath, { search: "CAF", sort: "CODE", direction: "ASC" }, () => nav(page, "Artículos").click());
  await page.getByText("Columnas", { exact: true }).click();
  assert.equal(await page.getByRole("checkbox", { name: "Importe devuelto", exact: true }).isChecked(), false);
  assert.equal(await search.inputValue(), "CAF");
  await expectRequest(page, documentsPath, { companyId: "retail", storeId: "north", productId: "p1", currency: "EUR" },
    () => coffee.getByRole("link", { name: "Ver documentos · Café natural · Período consultado", exact: true }).click());
  const detail = table(page, "Detalle de documentos");
  await detail.getByRole("cell", { name: "9.007.199.254.740.993,17", exact: true }).waitFor();
  await detail.getByRole("cell", { name: "Excluido", exact: true }).waitFor();
  await expectRequest(page, documentsPath, { sort: "AMOUNT", direction: "DESC", currency: "EUR", productId: "p1" },
    () => button(detail, "Importe documental").click());
  await expectRequest(page, articlePath, { search: "CAF", sort: "CODE", direction: "ASC" },
    () => page.getByRole("link", { name: "Volver al informe", exact: true }).click());

  await expectRequest(page, inventoryPath + "/balances", { companyId: "retail", storeId: "north", productId: "p1" },
    () => coffee.getByRole("link", { name: "Existencias · Café natural", exact: true }).click());
  await page.getByRole("heading", { name: "Existencias por producto y almacén", exact: true }).waitFor();
  await expectRequest(page, articlePath, { search: "CAF", province: "35" }, () => page.getByRole("link", { name: "Volver al informe", exact: true }).click());
  await expectRequest(page, ledgerPath, { companyId: "retail", storeId: "north", productId: "p1", from: "2026-07-01", to: "2026-07-03" },
    () => coffee.getByRole("link", { name: "Movimientos · Café natural", exact: true }).click());
  await expectRequest(page, articlePath, { search: "CAF", province: "35" }, () => page.getByRole("link", { name: "Volver al informe", exact: true }).click());

  assert.equal(await button(page, "Exportar CSV").count(), 0);
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    expectRequest(page, exportsPath + "/articles", { search: "CAF", sort: "CODE", format: "xlsx", layout: "readable", page: null },
      () => button(page, "Exportar Excel").click()),
  ]);
  assert.match(download.suggestedFilename(), /articulos.*2026-07-01.*2026-07-03.*\.xlsx$/);
  assert.equal(await readFile(await download.path(), "utf8"), "xlsx-download-fixture");
}

async function verifyMovements(page) {
  await nav(page, "Movimientos").click();
  const ledger = table(page, "Detalle de movimientos");
  const summary = table(page, "Movimientos por grupo");
  await ledger.getByRole("cell", { name: "-9.007.199.254.740.993,123", exact: true }).waitFor();
  assert.match(await ledger.locator('time[datetime="2026-07-01T03:45:12Z"]').innerText(), /03:45:12 UTC/);
  await summary.getByRole("rowheader", { name: "Salida por transferencia", exact: true }).waitFor();
  await page.getByText("Recepciones duplicadas", { exact: true }).waitFor();
  const ledgerPanel = page.locator('section[aria-labelledby="movement-ledger-heading"]');
  await ledgerPanel.locator(".analytics-preferences summary").click();
  await ledgerPanel.getByRole("checkbox", { name: "Recepción (UTC)", exact: true }).check();
  await ledgerPanel.locator(".analytics-preferences summary").click();
  await expectRequest(page, ledgerPath, { sort: "RECEIVED_AT", direction: "DESC" }, () => button(ledger, "Recepción (UTC)").click());
  await filter(page, "Agrupar por").selectOption("WAREHOUSE");
  await filter(page, "Tipo de movimiento").selectOption("TRANSFERENCIA_SALIDA");
  await expectRequest(page, movementsPath, { type: "TRANSFERENCIA_SALIDA", groupBy: "WAREHOUSE", page: "0" }, () => apply(page).click());
  await summary.getByRole("rowheader", { name: "Almacén Principal", exact: true }).waitFor();
  const product = ledger.getByRole("button", { name: "Aplicar filtros · Café natural", exact: true });
  await expectRequest(page, ledgerPath, { companyId: "retail", storeId: "north", productId: "p1", page: "0" }, () => product.click());
  assert.equal(await filter(page, "Producto").inputValue(), "p1");
  for (const [kind, filters] of [
    ["summary", { groupBy: "WAREHOUSE", comparison: "CUSTOM" }],
    ["ledger", { sort: "RECEIVED_AT", direction: "DESC", comparison: null }],
  ]) {
    const section = page.locator('section[aria-labelledby="movement-' + kind + '-heading"]');
    await button(section, "Exportar Excel").waitFor();
    assert.equal(await button(section, "Exportar CSV").count(), 0);
    const [download] = await Promise.all([
      page.waitForEvent("download"),
      expectRequest(page, exportsPath + "/movements", { ...filters, kind, format: "xlsx", layout: "readable", productId: "p1", page: null },
        () => button(section, "Exportar Excel").click()),
    ]);
    assert.match(download.suggestedFilename(), /\.xlsx$/);
  }
}

async function verifyComparison(page, calls) {
  await nav(page, "Existencias").click();
  await button(page, "Volver a tiendas").waitFor();
  await button(page, "Volver a tiendas").click();
  await table(page, "Inventario por tienda").getByRole("button", { name: "Consultar tienda · Tienda Norte", exact: true }).click();
  const history = table(page, "Historial de capturas");
  await page.locator(".inventory-history summary").click();
  const earlier = history.getByRole("row").filter({ has: page.locator('time[datetime="2026-11-17T08:00:00.000Z"]') });
  await expectRequest(page, comparisonPath, { previousSnapshotId: "capture-1", currentSnapshotId: "capture-current",
    companyId: "retail", storeId: "north", page: "0" }, () => button(earlier, "Comparar con esta captura").click());
  const comparison = table(page, "Comparar capturas");
  const comparisonPanel = page.locator('section[aria-labelledby="inventory-comparison-heading"]');
  await comparisonPanel.locator(".analytics-preferences summary").click();
  await comparisonPanel.getByRole("checkbox", { name: "Variación (%)", exact: true }).check();
  await comparisonPanel.locator(".analytics-preferences summary").click();
  const removed = comparison.getByRole("row").filter({ has: page.getByRole("rowheader", { name: "Harina de trigo", exact: true }) });
  await removed.waitFor();
  assert.deepEqual((await removed.getByRole("cell").allTextContents()).slice(-4), ["0,000", "—", "—", "—"]);
  assert.ok((await comparison.getByRole("row").filter({ has: page.getByRole("rowheader", { name: "Café natural", exact: true }) })
    .getByRole("cell").allTextContents()).includes("+0,122"));
  await page.getByRole("heading", { name: "Variación de catálogo", exact: true }).waitFor();
  await expectRequest(page, comparisonPath, { sort: "DELTA", direction: "DESC" }, () => button(comparison, "Variación").click());
  await filter(page, "Almacén").selectOption("w1");
  await page.getByRole("checkbox", { name: "Solo saldos con cambios", exact: true }).check();
  await expectRequest(page, comparisonPath, { warehouseId: "w1", changedOnly: "true" }, () => apply(page).click());
  assert.equal(await comparison.getByRole("rowheader", { name: "Artículo añadido", exact: true }).count(), 0);

  await page.locator(".inventory-history summary").click();
  const historyPanel = page.locator(".inventory-history");
  await expectRequest(page, inventoryPath + "/history", { page: "1" }, () => button(historyPanel, "Siguiente").click());
  const priorInstallation = history.getByRole("row", { name: /Instalación anterior/ });
  const before = calls.filter(call => call.pathname === comparisonPath).length;
  await button(priorInstallation, "Comparar con esta captura").click();
  await comparison.getByText("Selecciona dos capturas de la misma instalación.", { exact: true }).waitFor();
  assert.equal(calls.filter(call => call.pathname === comparisonPath).length, before);
}

try {
  await start();
  browser = await chromium.launch({ headless: true });
  const hash = "#/reports/sales?from=2026-07-01&to=2026-07-03&comparison=CUSTOM&comparisonFrom=2026-06-01&comparisonTo=2026-06-28&province=35&groupBy=WEEK";
  const { page, calls } = await open(["VIEW_REPORTS"], hash);
  await verifySales(page);
  await verifyArticles(page);
  await verifyMovements(page);
  await verifyComparison(page, calls);
  assert.ok(calls.filter(call => call.pathname.startsWith(reportsPath)).every(call => call.method === "GET" && call.auth === "Bearer " + token));
  assert.equal(await page.evaluate(() => Object.entries(localStorage).filter(([key]) => key.startsWith("espos.analytics.")).every(([,value]) => !value.includes("analytics-completion-test-token"))), true);
  await page.close();

  const denied = await open(["VIEW_ADMIN_DATA"], "#/reports/movements");
  await denied.page.getByText("Tu usuario no tiene permiso para esta accion.", { exact: true }).waitFor();
  assert.ok(!denied.calls.some(call => call.pathname.startsWith(reportsPath)));
  assert.deepEqual(errors, []);
  console.log("Analytics E2E passed: scoped periods, currencies, document drilldown, preferences, exports, ledger and inventory comparisons.");
} catch (error) {
  if (active && !active.page.isClosed()) {
    console.error("Report calls:", active.calls.slice(-8).map(call => call.pathname + "?" + call.searchParams));
    console.error("Report content:", (await active.page.locator("main").innerText({ timeout: 1000 }).catch(() => "")).slice(0, 3000));
  }
  throw error;
} finally {
  await browser?.close();
  server.kill();
}
