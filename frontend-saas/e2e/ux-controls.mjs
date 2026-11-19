import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";
import { inventoryOptions, reportFixture, statisticsAsOf } from "./analytics-completion-fixture.mjs";
import { emptySummary, summaryPath } from "./summary-fixture.mjs";

const root = fileURLToPath(new URL("..", import.meta.url));
const base = "http://127.0.0.1:5214/";
const server = spawn(process.execPath, [fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url)), "--host", "127.0.0.1", "--port", "5214", "--strictPort", "--configLoader", "runner"], { cwd: root, stdio: "inherit", windowsHide: true });
let browser;
try {
  let ready = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    try { ready = (await fetch(base)).ok; } catch { /* bounded startup */ }
    if (ready || server.exitCode !== null) break;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(ready, "Vite started");
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 960, height: 1032 } });
  page.setDefaultTimeout(10_000);
  await page.clock.setFixedTime(new Date(statisticsAsOf));
  const errors = [], exports = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/api/v1/**", async route => {
    const { pathname, searchParams } = new URL(route.request().url());
    if (pathname === "/api/v1/auth/admin/login") return route.fulfill({ json: { username: "UX_CONTROLS", mode: "admin", accessToken: "synthetic-token", expiresAt: "2099-01-01T00:00:00Z" } });
    if (pathname === "/api/v1/admin/me") return route.fulfill({ json: { username: "UX_CONTROLS", permissions: ["VIEW_REPORTS", "VIEW_ADMIN_DATA"] } });
    if (pathname === summaryPath) return route.fulfill({ json: emptySummary });
    if (pathname === summaryPath + "/options") return route.fulfill({ json: inventoryOptions });
    if (pathname.includes("/export/")) {
      exports.push(Object.fromEntries(searchParams));
      return route.fulfill({ contentType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", body: "xlsx-download-fixture" });
    }
    return route.fulfill({ json: reportFixture(pathname, searchParams) });
  });
  await page.goto(base + "#/reports/sales");
  await page.locator('input[autocomplete="username"]').fill("UX_CONTROLS");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  const excel = page.getByRole("button", { name: "Exportar Excel", exact: true });
  await excel.waitFor();
  assert.equal(await page.getByRole("button", { name: "Exportar CSV", exact: true }).count(), 0);

  const form = page.locator("#statistics-filters");
  const initialScope = await page.locator(".analytics-applied-scope").innerText();
  assert.match(initialScope, /UTC/);
  await page.getByRole("button", { name: "Ocultar filtros", exact: true }).click();
  assert.equal(await form.isVisible(), false);
  assert.equal(await page.locator(".analytics-applied-scope").innerText(), initialScope);
  await page.getByRole("button", { name: "Mostrar filtros", exact: true }).click();
  await page.getByRole("combobox", { name: "Comparación", exact: true }).selectOption("CUSTOM");
  assert.equal(await page.getByRole("button", { name: "Ocultar filtros", exact: true }).isDisabled(), true);
  const pairs = page.locator(".date-range-fields");
  assert.equal(await pairs.count(), 2);
  for (const pair of await pairs.all()) {
    const inputs = pair.locator("input");
    const a = await inputs.nth(0).boundingBox(), b = await inputs.nth(1).boundingBox();
    assert.ok(a && b && Math.abs(a.y - b.y) < 2, "A date pair stays together at 960px");
    assert.ok(a.x >= 0 && b.x + b.width <= 960, "Date fields stay in viewport");
  }
  await page.getByRole("button", { name: "Restablecer filtros", exact: true }).click();
  assert.equal(await page.getByRole("button", { name: "Ocultar filtros", exact: true }).isEnabled(), true);

  const content = page.getByRole("combobox", { name: "Contenido", exact: true });
  assert.equal(await content.inputValue(), "readable");
  for (const layout of ["readable", "complete"]) {
    await content.selectOption(layout);
    const [file] = await Promise.all([page.waitForEvent("download"), excel.click()]);
    assert.match(file.suggestedFilename(), /Ventas.*\.xlsx$/);
    assert.equal(exports.at(-1).format, "xlsx");
    assert.equal(exports.at(-1).layout, layout);
    assert.equal(exports.at(-1).view, "sales");
  }

  const search = page.getByRole("searchbox", { name: "Buscar módulo o pantalla", exact: true });
  await page.keyboard.press("Control+f");
  assert.equal(await search.evaluate(node => node === document.activeElement), true);
  await search.fill("resumen");
  await search.press("Enter");
  await page.locator(".topbar h1").filter({ hasText: "Resumen" }).waitFor();
  await page.keyboard.press("Control+f");
  await search.fill("es");
  await search.press("ArrowDown");
  const first = await page.locator(".nav-button:focus").innerText();
  await page.keyboard.press("ArrowDown");
  assert.notEqual(await page.locator(".nav-button:focus").innerText(), first);
  await page.keyboard.press("ArrowUp");
  assert.equal(await page.locator(".nav-button:focus").innerText(), first);
  await page.keyboard.press("Escape");
  assert.equal(await search.inputValue(), "");
  assert.equal(await search.evaluate(node => node === document.activeElement), true);
  await search.fill("informes");
  await search.press("Enter");
  await page.locator(".topbar h1").filter({ hasText: "Informes" }).waitFor();
  assert.deepEqual(errors, []);
  console.log("UX controls passed: paired dates, applied scope, filter folding, readable/complete exports, Ctrl+F/Enter/arrows/Escape.");
} finally {
  await browser?.close();
  server.kill();
}
