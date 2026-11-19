import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";
import { inventoryOptions, inventoryPath, inventoryResponse } from "./inventory-fixture.mjs";
import { statisticsPath, statisticsSnapshot } from "./statistics-fixture.mjs";

const base = "http://127.0.0.1:5206/";
const root = fileURLToPath(new URL("..", import.meta.url));
const vite = fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url));
const screenshots = new URL("../../output/playwright/", import.meta.url);
const server = spawn(process.execPath, [vite, "--host", "127.0.0.1", "--port", "5206", "--strictPort", "--configLoader", "runner"], {
  cwd: root, stdio: "inherit", windowsHide: true,
});
const login = "/api/v1/auth/admin/login";
const session = "/api/v1/admin/me";
const token = "inventory-e2e-token";
const inventoryEndpoints = ["overview", "history", "products", "balances", "warehouses"];
const allowed = new Set([login, session, statisticsPath, statisticsPath + "/options",
  ...inventoryEndpoints.map(endpoint => inventoryPath + "/" + endpoint)]);
const calls = [];
const errors = [];
const table = (page, name) => page.getByRole("table", { name, exact: true });
const apply = page => page.getByRole("button", { name: "Aplicar filtros", exact: true });
const combo = (page, name) => page.getByRole("combobox", { name, exact: true });
const stockName = "Existencias por producto y almacén";
const scopeName = "Inventario por tienda";
const historyName = "Historial de capturas";
const stockTab = page => page.getByRole("group", { name: "Catálogo y existencias", exact: true }).getByRole("button", { name: "Existencias", exact: true });

let failProducts = false;
let revision = 0;
let releaseObsolete;
const obsolete = new Promise(resolve => { releaseObsolete = resolve; });

async function start() {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error("Inventory E2E Vite server exited before startup");
    try { if ((await fetch(base)).ok) return; } catch { /* bounded startup retry */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("Inventory E2E Vite startup timed out");
}

async function open(browser, permissions) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, timezoneId: "America/Los_Angeles" });
  page.setDefaultTimeout(10_000);
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/api/v1/**", async route => {
    const incoming = route.request();
    const url = new URL(incoming.url());
    const { pathname, searchParams } = url;
    calls.push({ pathname, searchParams, authorization: incoming.headers().authorization });
    if (pathname === login) return route.fulfill({ json: {
      username: "INVENTORY_E2E", accessToken: token, mode: "admin", passwordChangeRequired: false, expiresAt: "2099-01-01T00:00:00Z",
    } });
    if (pathname === session) return route.fulfill({ json: { username: "INVENTORY_E2E", permissions } });
    if (pathname === statisticsPath + "/options") return route.fulfill({ json: inventoryOptions });
    if (pathname === statisticsPath) return route.fulfill({ json: statisticsSnapshot(searchParams) });
    const endpoint = pathname.slice(inventoryPath.length + 1);
    if (!pathname.startsWith(inventoryPath + "/") || !inventoryEndpoints.includes(endpoint)) {
      return route.fulfill({ status: 404, body: "Unexpected inventory smoke request" });
    }
    if (endpoint === "products" && failProducts) return route.fulfill({ status: 503, body: "Inventory test unavailable" });
    if (endpoint === "products" && searchParams.get("search") === "Café") await obsolete;
    try {
      await route.fulfill({ json: inventoryResponse(endpoint, searchParams, revision) });
    } catch (error) {
      if (!incoming.failure() && !page.isClosed()) throw error;
    }
  });
  await page.goto(base + "#/reports/inventory");
  await page.locator('input[autocomplete="username"]').fill("INVENTORY_E2E");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  await page.locator(".topbar h1").waitFor();
  return page;
}

function matches(endpoint, values) {
  return response => {
    const url = new URL(response.url());
    return url.pathname === inventoryPath + "/" + endpoint
      && Object.entries(values).every(([name, value]) => url.searchParams.get(name) === value);
  };
}

async function query(page, action, endpoint, values) {
  await Promise.all([page.waitForResponse(matches(endpoint, values)), action.click()]);
}

async function selectLanguage(page, option) {
  await page.locator(".app-system-bar .language-trigger").click();
  await page.locator(".language-menu").getByRole("button", { name: option }).click();
}

let browser;
let activePage;
try {
  await start();
  await mkdir(screenshots, { recursive: true });
  browser = await chromium.launch({ headless: true });
  const page = activePage = await open(browser, ["VIEW_REPORTS"]);
  const overview = table(page, scopeName);
  await overview.getByRole("rowheader", { name: "Tienda Norte" }).waitFor();
  const pending = overview.getByRole("row", { name: /Tienda Pendiente/ });
  const empty = overview.getByRole("row", { name: /Tienda Vacía/ });
  assert.match(await pending.innerText(), /Pendiente de datos/);
  assert.equal(await overview.getByRole("columnheader", { name: "Recibido completo (UTC)", exact: true }).count(), 0);
  await page.getByText("Columnas", { exact: true }).click();
  await page.getByRole("checkbox", { name: "Recibido completo (UTC)", exact: true }).check();
  await page.getByText("Columnas", { exact: true }).click();
  assert.deepEqual((await pending.getByRole("cell").allTextContents()).slice(2, 7), ["—", "—", "—", "—", "—"]);
  assert.match(await empty.innerText(), /Completa/);
  assert.deepEqual((await empty.getByRole("cell").allTextContents()).slice(4, 7), ["0", "0", "0"]);
  assert.match(await overview.locator('time[datetime="2026-11-18T09:12:10Z"]').first().innerText(), /09:12:10 UTC/);
  assert.ok(!calls.some(call => call.pathname === statisticsPath), "Inventory must not request sales statistics");
  await page.screenshot({ path: fileURLToPath(new URL("saas-inventory-phase3-overview.png", screenshots)), animations: "disabled" });

  await query(page, pending.getByRole("button"), "products", { companyId: "retail", storeId: "pending", snapshotId: null, size: "1" });
  const catalog = table(page, "Catálogo");
  await catalog.getByText("Pendiente de datos de inventario de la instalación actual.", { exact: true }).waitFor();
  assert.ok(await catalog.getByRole("columnheader", { name: "Producto", exact: true }).isVisible());
  assert.equal(await page.getByText("Registros 0", { exact: true }).count(), 0);
  await page.getByRole("button", { name: "Volver a tiendas" }).click();
  assert.equal(await pending.getByRole("button").evaluate(button => document.activeElement === button), true);

  await combo(page, "Empresa").selectOption("retail");
  await combo(page, "Tienda").selectOption("north");
  assert.equal(await overview.getByRole("rowheader", { name: "Tienda Vacía" }).count(), 1);
  await query(page, apply(page), "overview", { companyId: "retail", storeId: "north", page: "0" });
  await overview.getByRole("rowheader", { name: "Tienda Vacía" }).waitFor({ state: "hidden" });
  await query(page, overview.getByRole("button", { name: /Tienda Norte/ }), "products", { companyId: "retail", storeId: "north", snapshotId: "capture-current" });
  await catalog.getByRole("rowheader", { name: "Instalación a domicilio" }).waitFor();
  assert.equal(await catalog.getByRole("columnheader", { name: "Código de barras secundario", exact: true }).count(), 0);
  await page.getByText("Columnas", { exact: true }).click();
  await page.getByRole("checkbox", { name: "Código de barras secundario", exact: true }).check();
  await page.getByText("Columnas", { exact: true }).click();
  assert.equal(await catalog.getByText("8412345000028", { exact: true }).count(), 1);

  const search = page.getByRole("searchbox", { name: "Buscar producto o código" });
  await search.fill("Café");
  await Promise.all([page.waitForRequest(matches("products", { search: "Café" })), apply(page).click()]);
  await search.fill("HAR");
  await combo(page, "Estado del producto").selectOption("false");
  await query(page, apply(page), "products", { search: "HAR", active: "false", page: "0" });
  await catalog.getByRole("rowheader", { name: "Harina de trigo" }).waitFor();
  await Promise.all([page.waitForResponse(matches("products", { search: "Café" })), Promise.resolve().then(releaseObsolete)]);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.equal(await catalog.getByRole("rowheader", { name: "Café natural" }).count(), 0);

  failProducts = true;
  await query(page, apply(page), "products", { search: "HAR", active: "false" });
  await catalog.getByText("Datos no disponibles.", { exact: true }).waitFor();
  failProducts = false;
  await query(page, page.getByRole("alert").getByRole("button", { name: "Reintentar" }), "products", { search: "HAR", active: "false" });
  await catalog.getByRole("rowheader", { name: "Harina de trigo" }).waitFor();
  await search.fill("");
  await combo(page, "Estado del producto").selectOption("");
  await query(page, apply(page), "products", { search: null, active: null });
  await query(page, stockTab(page), "balances", { snapshotId: "capture-current", warehouseId: null });
  const stock = table(page, stockName);
  await stock.getByText("-0,001", { exact: true }).waitFor();
  assert.equal(await stock.getByText("9.007.199.254.740.993,123", { exact: true }).count(), 1);
  assert.equal(await stock.getByText("0,000", { exact: true }).count(), 1);
  assert.equal(await stock.getByText("Instalación a domicilio", { exact: true }).count(), 0);
  await page.screenshot({ path: fileURLToPath(new URL("saas-inventory-phase3-stock.png", screenshots)), animations: "disabled" });

  const warehousePager = page.getByRole("navigation", { name: "Páginas de almacenes", exact: true });
  await query(page, warehousePager.getByRole("button", { name: "Siguiente" }), "warehouses", { page: "1", snapshotId: "capture-current" });
  await combo(page, "Almacén").selectOption("w26");
  await query(page, apply(page), "balances", { warehouseId: "w26", snapshotId: "capture-current", page: "0" });
  await stock.getByText("Almacén 26", { exact: true }).waitFor();
  assert.equal(await stock.getByText("-0,001", { exact: true }).count(), 0);

  await query(page, page.locator(".inventory-history summary"), "history", { page: "0", storeId: "north" });
  const history = table(page, historyName);
  await history.locator("tbody tr").first().waitFor();
  await query(page, page.locator(".inventory-history").getByRole("button", { name: "Siguiente" }), "history", { page: "1" });
  const older = history.getByRole("row", { name: /Instalación anterior/ });
  await query(page, older.getByRole("button", { name: "Consultar captura" }), "balances", {
    snapshotId: "capture-previous-installation", warehouseId: null, storeId: "north",
  });
  const capture = page.locator(".inventory-capture");
  await capture.getByText("Instalación anterior", { exact: true }).waitFor();
  assert.match(await capture.locator("time").first().innerText(), /18:20:30 UTC/);
  assert.equal(await history.count(), 0, "Selecting a snapshot closes the history and shows its contents");
  await query(page, capture.getByRole("button", { name: "Consultar última captura" }), "balances", { snapshotId: "capture-current", warehouseId: null });
  await capture.getByText("Instalación actual", { exact: true }).waitFor();

  await page.setViewportSize({ width: 390, height: 844 });
  await stock.waitFor();
  assert.ok(await stock.evaluate(element => element.scrollWidth > element.parentElement.clientWidth));
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 390);
  await page.locator("#inventory-detail-heading").evaluate(element => element.scrollIntoView({ block: "start" }));
  await page.screenshot({ path: fileURLToPath(new URL("saas-inventory-phase3-mobile.png", screenshots)), animations: "disabled" });

  await selectLanguage(page, /^English/);
  await table(page, "Stock by product and warehouse").getByText("-0.001", { exact: true }).waitFor();
  await selectLanguage(page, /^中文/);
  await table(page, "按商品和仓库查看库存").getByText("0.000", { exact: true }).waitFor();
  await selectLanguage(page, /^Español/);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await query(page, page.getByRole("button", { name: "Catálogo", exact: true }), "products", { snapshotId: "capture-current", page: "0" });
  await catalog.getByRole("rowheader", { name: "Café natural", exact: true }).waitFor();
  const beforeArrival = calls.length;
  revision = 1;
  const nextCatalogPage = page.locator("#inventory-detail-results-heading").locator("../..").getByRole("button", { name: "Siguiente", exact: true });
  await query(page, nextCatalogPage, "products", { snapshotId: "capture-current", page: "1" });
  await catalog.getByRole("rowheader", { name: "Referencia original 23", exact: true }).waitFor();
  await query(page, stockTab(page), "balances", { snapshotId: "capture-current", page: "0" });
  await stock.getByText("-0,001", { exact: true }).waitFor();
  await combo(page, "Almacén").getByRole("option", { name: "Almacén Principal", exact: true }).waitFor({ state: "attached" });
  assert.ok(calls.slice(beforeArrival).filter(call => call.pathname.startsWith(inventoryPath))
    .every(call => call.searchParams.get("snapshotId") === "capture-current"), "Pagination and tabs must keep the original snapshot");
  assert.equal(await capture.getByText("Captura seleccionada", { exact: true }).count(), 0);

  const latest = capture.getByRole("button", { name: "Consultar última captura" });
  await query(page, latest, "products", { snapshotId: null, size: "1" });
  await stock.getByRole("rowheader", { name: "Producto nuevo 1", exact: true }).waitFor();
  await combo(page, "Almacén").getByRole("option", { name: "Almacén nuevo 1", exact: true }).waitFor({ state: "attached" });
  assert.match(await capture.locator("time").first().innerText(), /11:12:10 UTC/);
  revision = 2;
  await query(page, page.getByRole("button", { name: "Actualizar", exact: true }), "balances", { snapshotId: "capture-arrival-2", page: "0" });
  await stock.getByRole("rowheader", { name: "Producto nuevo 2", exact: true }).waitFor();

  const reportSections = page.getByRole("group", { name: "Informes", exact: true });
  await reportSections.getByRole("button", { name: "Ventas", exact: true }).click();
  await page.getByRole("heading", { name: "Estadísticas", exact: true }).waitFor();
  await page.goBack();
  await stock.getByRole("rowheader", { name: "Producto nuevo 2", exact: true }).waitFor();
  const [inventoryRoute, inventoryQuery] = new URL(page.url()).hash.split("?");
  assert.equal(inventoryRoute, "#/reports/inventory");
  const restoredInventory = new URLSearchParams(inventoryQuery);
  assert.equal(restoredInventory.get("openedStoreId"), "north");
  assert.equal(restoredInventory.get("mode"), "stock");
  await reportSections.getByRole("button", { name: "Ventas", exact: true }).click();
  await page.getByRole("heading", { name: "Estadísticas", exact: true }).waitFor();
  await reportSections.getByRole("button", { name: "Existencias", exact: true }).click();
  await stock.getByRole("rowheader", { name: "Producto nuevo 2", exact: true }).waitFor();

  const productLink = new URLSearchParams({
    companyId: "retail", storeId: "north", mode: "stock", productId: "p1", productName: "Café natural",
    from: "2026-11-01", to: "2026-11-18", return: "#/reports/articles?sort=CODE",
  });
  revision = 0;
  await Promise.all([
    page.waitForResponse(matches("balances", { companyId: "retail", storeId: "north", productId: "p1", search: null, snapshotId: "capture-current" })),
    page.goto(base + "#/reports/inventory?" + productLink),
  ]);
  await stock.getByRole("rowheader", { name: "Café natural", exact: true }).first().waitFor();
  assert.equal(await stock.getByRole("rowheader", { name: "Harina de trigo", exact: true }).count(), 0);
  assert.equal(await page.getByRole("link", { name: "Volver al informe", exact: true }).getAttribute("href"), "#/reports/articles?sort=CODE");
  for (const [label, section] of [["Ventas", "documents"], ["Movimientos", "movements"]]) {
    const href = await stock.getByRole("link", { name: label + " · Café natural", exact: true }).first().getAttribute("href");
    assert.match(href, new RegExp("^#/reports/" + section + "\\?"));
    const params = new URLSearchParams(href.split("?")[1]);
    assert.deepEqual(["companyId", "storeId", "productId", "from", "to"].map(key => params.get(key)),
      ["retail", "north", "p1", "2026-11-01", "2026-11-18"]);
    assert.equal(params.get("productCode"), null);
  }
  await query(page, page.getByRole("button", { name: "Catálogo", exact: true }), "products", { storeId: "north", productId: null });
  await catalog.getByRole("rowheader", { name: "Harina de trigo", exact: true }).waitFor();
  await query(page, stockTab(page), "balances", { storeId: "north", productId: "p1" });
  await query(page, page.getByRole("button", { name: "Restablecer filtros", exact: true }), "balances", { storeId: "north", productId: null });
  await stock.getByRole("rowheader", { name: "Harina de trigo", exact: true }).waitFor();
  await page.getByRole("button", { name: "Volver a tiendas", exact: true }).click();
  await query(page, overview.getByRole("button", { name: /Tienda Norte/ }), "balances", { storeId: "north", productId: null });
  await stock.getByRole("rowheader", { name: "Harina de trigo", exact: true }).waitFor();

  const beforeMismatch = calls.length;
  await Promise.all([
    page.waitForResponse(matches("overview", { companyId: "retail", storeId: "empty" })),
    page.goto(base + "#/reports/inventory?companyId=retail&storeId=empty&mode=stock&productId=p1"),
  ]);
  await overview.getByText("No hay tiendas para estos filtros.", { exact: true }).waitFor();
  assert.ok(calls.slice(beforeMismatch).every(call => !["products", "balances"].some(endpoint => call.pathname.endsWith("/" + endpoint))));
  assert.deepEqual([...new Set(calls.map(call => call.pathname))].filter(path => !allowed.has(path)), []);
  assert.ok(calls.filter(call => call.pathname.startsWith(inventoryPath)).every(call => call.authorization === `Bearer ${token}`));
  assert.deepEqual(errors, []);
  await page.close();

  const beforeDenied = calls.length;
  const denied = activePage = await open(browser, ["VIEW_ADMIN_DATA"]);
  await denied.getByText("Tu usuario no tiene permiso para esta accion.", { exact: true }).waitFor();
  assert.ok(calls.slice(beforeDenied).every(call => !call.pathname.includes("/reports/")));
  await denied.close();
  console.log("Inventory E2E passed: exact quantities, pagination, historical snapshots, filter races, scoped product links, context restore, permissions and translations.");
} catch (error) {
  if (activePage && !activePage.isClosed()) {
    console.error("Inventory calls:", calls.slice(-6).map(call => `${call.pathname}?${call.searchParams}`));
    console.error("Inventory content:", (await activePage.locator("main").innerText({ timeout: 1000 }).catch(() => "")).slice(0, 2000));
  }
  throw error;
} finally {
  releaseObsolete();
  await browser?.close();
  server.kill();
}
