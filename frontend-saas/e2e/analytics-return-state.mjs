import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";
import { inventoryPath, reportFixture, reportsPath, statisticsAsOf, statisticsPath } from "./analytics-completion-fixture.mjs";

const root = fileURLToPath(new URL("..", import.meta.url));
const vite = fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url));
const port = 5213;
const base = `http://127.0.0.1:${port}/`;
const server = spawn(process.execPath, [vite, "--host", "127.0.0.1", "--port", String(port), "--strictPort", "--configLoader", "runner"], {
  cwd: root, stdio: "inherit", windowsHide: true,
});
const comparePath = inventoryPath + "/comparison";
const movementsPath = reportsPath + "/movements";
const ledgerPath = movementsPath + "/ledger";
const period = "from=2026-07-01&to=2026-07-03&comparison=NONE";
const username = "RETURN_STATE_E2E";
const errors = [];
let browser;

const button = (scope, name) => scope.getByRole("button", { name, exact: true });
const nav = (page, name) => button(page.getByRole("group", { name: "Informes", exact: true }), name);
const table = (page, name) => page.getByRole("table", { name, exact: true });

async function waitForServer() {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error("Vite exited before startup");
    try { if ((await fetch(base)).ok) return; } catch { /* Wait for startup. */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("Vite startup timed out");
}

async function open(hash, override = (response) => response) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 850 } });
  const calls = [];
  page.setDefaultTimeout(10_000);
  await page.clock.setFixedTime(new Date(statisticsAsOf));
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/api/v1/**", route => {
    const { pathname, searchParams } = new URL(route.request().url());
    calls.push({ pathname, searchParams });
    if (pathname === "/api/v1/auth/admin/login") return route.fulfill({ json: {
      username, accessToken: "return-state-token", mode: "admin", passwordChangeRequired: false, expiresAt: "2099-01-01T00:00:00Z",
    } });
    if (pathname === "/api/v1/admin/me") return route.fulfill({ json: { username, permissions: ["VIEW_REPORTS"] } });
    return route.fulfill({ json: override(reportFixture(pathname, searchParams), pathname, searchParams) });
  });
  await page.goto(base + hash);
  await page.locator('input[autocomplete="username"]').fill(username);
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  await page.locator(".topbar h1").waitFor();
  return { page, calls };
}

async function expectRequest(page, path, filters, action) {
  const [response] = await Promise.all([page.waitForResponse(response => {
    const url = new URL(response.url());
    return path === url.pathname && Object.entries(filters).every(([key, value]) => url.searchParams.get(key) === value);
  }), action()]);
  assert.ok(response.ok());
}

async function verifyInventoryReturn() {
  let latestChanged = false;
  let previousUnavailable = false;
  const { page, calls } = await open("#/reports/articles?" + period, (response, path, query) => {
    if (path === inventoryPath + "/overview" && latestChanged) {
      return { ...response, rows: response.rows.map(row => row.storeId === "north"
        ? { ...row, snapshot: { ...row.snapshot, snapshotId: "capture-arrived" } } : row) };
    }
    if (path === inventoryPath + "/products" && previousUnavailable && query.get("snapshotId") === "capture-1") {
      return { ...response, snapshot: null, total: 0, rows: [] };
    }
    if (path === comparePath && query.get("search") === "Café") {
      const firstPage = new URLSearchParams(query);
      firstPage.set("page", "0");
      const [example] = reportFixture(path, firstPage).rows;
      const rows = Array.from({ length: 26 }, (_, index) => ({ ...example, productId: `p1-${index}`, name: `Café ${index}` }));
      const { page, size } = response;
      return { ...response, rows: rows.slice(page * size, (page + 1) * size), total: rows.length };
    }
    return response;
  });
  const coffee = page.getByRole("link", { name: "Existencias · Café natural", exact: true });
  await expectRequest(page, inventoryPath + "/balances", { productId: "p1" }, () => coffee.click());
  await button(page, "Comparar capturas").click();
  await page.locator(".inventory-history summary").click();
  const history = table(page, "Historial de capturas");
  const earlier = history.getByRole("row").filter({ has: page.locator('time[datetime="2026-11-17T08:00:00.000Z"]') });
  const pair = { currentSnapshotId: "capture-current", previousSnapshotId: "capture-1" };
  await expectRequest(page, comparePath, { ...pair, page: "0" }, () => button(earlier, "Comparar con esta captura").click());
  const comparison = table(page, "Comparar capturas");
  const panel = page.locator('[aria-labelledby="inventory-comparison-heading"]');
  const search = panel.getByRole("searchbox", { name: "Buscar producto o código" });
  await search.fill("Café");
  await panel.getByRole("combobox", { name: "Almacén", exact: true }).selectOption("w1");
  await panel.getByRole("checkbox", { name: "Solo saldos con cambios", exact: true }).check();
  const filters = { ...pair, warehouseId: "w1", changedOnly: "true", search: "Café" };
  await expectRequest(page, comparePath, { ...filters, page: "0" }, () => button(panel, "Aplicar filtros").click());
  const sort = { sort: "DELTA", direction: "DESC" };
  await expectRequest(page, comparePath, { ...filters, ...sort }, () => button(comparison, "Variación").click());
  const resultPager = panel.getByRole("navigation").last();
  await expectRequest(page, comparePath, { ...filters, ...sort, page: "1" }, () => button(resultPager, "Siguiente").click());
  await comparison.getByRole("rowheader", { name: "Café 25", exact: true }).waitFor();

  const main = page.locator(".main-panel");
  await main.evaluate(node => { node.scrollTop = 300; });
  const scrollTop = await main.evaluate(node => node.scrollTop);
  assert.ok(scrollTop > 0);
  await nav(page, "Artículos").evaluate(node => node.click());
  await coffee.waitFor();
  latestChanged = true;
  await expectRequest(page, comparePath, { ...filters, ...sort, page: "1" }, () => page.goBack());
  await comparison.getByRole("rowheader", { name: "Café 25", exact: true }).waitFor();
  await page.waitForFunction(({ top }) => document.querySelector(".main-panel").scrollTop === top, { top: scrollTop });
  assert.equal(await nav(page, "Artículos").evaluate(node => node === document.activeElement), true);
  assert.equal(await button(page, "Comparar capturas").getAttribute("aria-pressed"), "true");

  await nav(page, "Artículos").click();
  const flour = page.getByRole("link", { name: "Existencias · Harina de trigo", exact: true });
  await expectRequest(page, inventoryPath + "/balances", { productId: "p2" }, () => flour.click());
  assert.equal(await button(page, "Comparar capturas").getAttribute("aria-pressed"), "false");
  await page.goBack();
  await flour.waitFor();

  previousUnavailable = true;
  const comparisons = calls.filter(call => call.pathname === comparePath).length;
  await page.goBack();
  await panel.getByText("Captura anterior · Datos no disponibles.", { exact: true }).waitFor();
  assert.equal(calls.filter(call => call.pathname === comparePath).length, comparisons);
  await page.close();
}

async function verifyMovementReturn() {
  const { page } = await open("#/reports/movements?" + period);
  const groups = table(page, "Movimientos por grupo");
  const group = groups.getByRole("row").filter({ hasText: "Salida por transferencia" });
  await expectRequest(page, ledgerPath, { type: "TRANSFERENCIA_SALIDA", productId: null }, () => group.getByRole("link").click());
  const ledger = table(page, "Detalle de movimientos");
  const product = button(ledger, "Aplicar filtros · Café natural");
  const selected = { companyId: "retail", storeId: "north", type: "TRANSFERENCIA_SALIDA", productId: "p1" };
  await expectRequest(page, ledgerPath, selected, () => product.click());
  await expectRequest(page, statisticsPath, { from: "2026-07-01", to: "2026-07-03" }, () => nav(page, "Medias").click());
  await expectRequest(page, ledgerPath, selected, () => page.goBack());
  await product.waitFor();
  assert.equal(await page.getByRole("combobox", { name: "Producto", exact: true }).inputValue(), "p1");
  assert.equal(await groups.count(), 0);
  await expectRequest(page, movementsPath, { productId: null, groupBy: "TYPE" },
    () => page.getByRole("link", { name: "Volver al informe", exact: true }).click());
  await groups.waitFor();
  await page.close();
}

try {
  await waitForServer();
  browser = await chromium.launch({ headless: true });
  await verifyInventoryReturn();
  await verifyMovementReturn();
  assert.deepEqual(errors, []);
  console.log("Analytics return-state E2E passed for inventory comparisons and movement detail.");
} finally {
  await browser?.close();
  server.kill();
}
