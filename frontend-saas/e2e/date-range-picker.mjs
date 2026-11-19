import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";
import { accumulatedSummary, summaryAsOf, summaryPath } from "./summary-fixture.mjs";
import { statisticsOptions, statisticsPath, statisticsSnapshot } from "./statistics-fixture.mjs";

const port = "5208";
const base = `http://127.0.0.1:${port}/`;
const root = fileURLToPath(new URL("..", import.meta.url));
const vite = fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url));
const screenshots = fileURLToPath(new URL("../../output/playwright/", import.meta.url));
const server = spawn(process.execPath, [vite, "--host", "127.0.0.1", "--port", port, "--strictPort", "--configLoader", "runner"], {
  cwd: root, stdio: "inherit", windowsHide: true,
});

async function start() {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error("Date range E2E Vite server exited before startup");
    try { if ((await fetch(base)).ok) return; } catch { /* bounded startup retry */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("Date range E2E Vite startup timed out");
}

async function open(browser, hash, { path, permission, snapshot }) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, timezoneId: "America/Los_Angeles" });
  page.setDefaultTimeout(10_000);
  await page.clock.setFixedTime(new Date(summaryAsOf));
  const calls = [];
  const unexpected = [];
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/api/v1/**", async route => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();
    if (url.pathname === "/api/v1/auth/admin/login" && method === "POST") {
      return route.fulfill({ json: {
        username: "RANGE_E2E", accessToken: "range-synthetic-token", mode: "admin",
        passwordChangeRequired: false, expiresAt: "2099-01-01T00:00:00Z",
      } });
    }
    if (url.pathname === "/api/v1/admin/me" && method === "GET") {
      return route.fulfill({ json: { username: "RANGE_E2E", permissions: [permission] } });
    }
    if (url.pathname === `${path}/options` && method === "GET") {
      return route.fulfill({ json: statisticsOptions });
    }
    if (url.pathname === path && method === "GET") {
      calls.push(url.searchParams);
      return route.fulfill({ json: snapshot(url.searchParams) });
    }
    unexpected.push(`${method} ${url.pathname}${url.search}`);
    return route.fulfill({ status: 404, body: "Unexpected date range E2E request" });
  });

  await page.goto(base + hash);
  await page.locator('input[autocomplete="username"]').fill("RANGE_E2E");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === path && response.ok()),
    page.locator('form button[type="submit"]').click(),
  ]);
  return { page, calls, unexpected, errors };
}

const field = (page, name) => page.getByRole("textbox", { name, exact: true });
const button = (container, name) => container.getByRole("button", { name, exact: true });
const day = (dialog, date) => dialog.locator(`button[data-date="${date}"]`);
const formattedDate = (date, locale = "es-ES", dateStyle = "medium") =>
  new Intl.DateTimeFormat(locale, { dateStyle, timeZone: "UTC" }).format(new Date(`${date}T00:00:00Z`));

async function assertRange(from, to, expectedFrom, expectedTo) {
  assert.deepEqual(await Promise.all([from.inputValue(), to.inputValue()]), [
    formattedDate(expectedFrom), formattedDate(expectedTo),
  ]);
}

async function assertRangeHighlight(dialog, start, middle, end, outside) {
  assert.equal(await day(dialog, start).getAttribute("data-range-start"), "true");
  assert.equal(await day(dialog, middle).getAttribute("data-in-range"), "true");
  assert.equal(await day(dialog, end).getAttribute("data-range-end"), "true");
  assert.notEqual(await day(dialog, outside).getAttribute("data-in-range"), "true");
}

async function applyFilters(page, path, query) {
  await Promise.all([
    page.waitForResponse(response => {
      const url = new URL(response.url());
      return response.ok() && url.pathname === path
        && Object.entries(query).every(([key, value]) => url.searchParams.get(key) === value);
    }),
    button(page, "Aplicar filtros").click(),
  ]);
}

async function selectLanguage(page, name) {
  await page.locator(".app-system-bar .language-trigger").click();
  await page.locator(".language-menu").getByRole("button", { name }).click();
}

async function checkSummary(browser) {
  const { page, calls, unexpected, errors } = await open(browser, "#/dashboard", {
    path: summaryPath, permission: "VIEW_ADMIN_DATA", snapshot: () => accumulatedSummary,
  });
  const from = field(page, "Desde");
  const to = field(page, "Hasta");
  const dialog = page.getByRole("dialog", { name: "Desde – Hasta", exact: true });
  const accept = button(dialog, "Aceptar");
  const cancel = button(dialog, "Cancelar");
  const next = button(dialog, "Mes siguiente");
  const nextFrame = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));

  await page.getByRole("region", { name: "Cartera actual", exact: true }).waitFor();
  const initialRequests = calls.length;
  await assertRange(from, to, "2026-06-02", "2026-07-01");
  await from.click();
  await dialog.waitFor();
  const originalDialog = await dialog.elementHandle();
  assert.equal(await dialog.count(), 1);
  assert.equal(await day(dialog, "2026-06-29").getAttribute("aria-label"), formattedDate("2026-06-29", "es-ES", "full"));
  await day(dialog, "2026-06-29").click();
  assert.equal(await originalDialog.evaluate(element => element.isConnected), true, "The first selection must keep the same calendar open");
  await assertRange(from, to, "2026-06-02", "2026-07-01");
  await next.click();
  await day(dialog, "2026-07-03").click();
  assert.equal(await originalDialog.evaluate(element => element.isConnected), true, "Both days must be selected in one calendar");
  await assertRangeHighlight(dialog, "2026-06-29", "2026-07-01", "2026-07-03", "2026-07-04");
  assert.equal(await day(dialog, "2026-07-01").getAttribute("aria-current"), "date", "Today follows UTC in a browser still on June 30");
  await page.screenshot({ path: `${screenshots}/saas-date-range-picker-desktop.png`, animations: "disabled" });
  await accept.click();
  await dialog.waitFor({ state: "hidden" });
  await assertRange(from, to, "2026-06-29", "2026-07-03");
  await nextFrame();
  assert.equal(calls.length, initialRequests, "Accepting the calendar must only edit the filter draft");
  await applyFilters(page, summaryPath, { from: "2026-06-29", to: "2026-07-03" });

  await from.click();
  await next.click();
  await day(dialog, "2026-07-10").click();
  await day(dialog, "2026-07-04").click();
  await accept.click();
  await assertRange(from, to, "2026-07-04", "2026-07-10");

  await from.click();
  await day(dialog, "2026-07-05").click();
  await day(dialog, "2026-07-08").click();
  await cancel.click();
  await dialog.waitFor({ state: "hidden" });
  assert.equal(await from.evaluate(input => input === document.activeElement), true);
  await assertRange(from, to, "2026-07-04", "2026-07-10");

  await to.click();
  await day(dialog, "2026-07-13").click();
  await page.keyboard.press("Escape");
  await dialog.waitFor({ state: "hidden" });
  assert.equal(await to.evaluate(input => input === document.activeElement), true);
  await assertRange(from, to, "2026-07-04", "2026-07-10");

  await to.press("Enter");
  await dialog.waitFor();
  await dialog.locator('button[data-date="2026-07-10"]:focus').waitFor();
  await page.keyboard.press("ArrowRight");
  await dialog.locator('button[data-date="2026-07-11"]:focus').waitFor();
  await page.keyboard.press("Enter");
  await accept.press("Enter");
  await dialog.waitFor({ state: "hidden" });
  await assertRange(from, to, "2026-07-04", "2026-07-11");
  await nextFrame();
  assert.equal(calls.length, initialRequests + 1, "Keyboard selection, cancellation and Escape must leave the applied filter alone");

  await page.setViewportSize({ width: 390, height: 844 });
  await from.click();
  await dialog.waitFor();
  const viewport = await dialog.evaluate(element => {
    const bounds = element.getBoundingClientRect();
    return { left: bounds.left, right: bounds.right, width: document.documentElement.clientWidth, scrollWidth: document.body.scrollWidth };
  });
  assert.ok(viewport.left >= 0 && viewport.right <= viewport.width, "The calendar must fit within the mobile viewport");
  assert.ok(viewport.scrollWidth <= viewport.width, "Opening the calendar must not add horizontal page overflow");
  await page.screenshot({ path: `${screenshots}/saas-date-range-picker-mobile.png`, animations: "disabled" });
  await cancel.click();
  await page.setViewportSize({ width: 1440, height: 1000 });

  for (const [language, locale, startLabel, endLabel, nextMonth, confirm, dismiss] of [
    [/^English/, "en-GB", "From", "To", "Next month", "Apply", "Cancel"],
    [/^中文/, "zh-CN", "开始日期", "结束日期", "下个月", "确定", "取消"],
  ]) {
    await selectLanguage(page, language);
    const translatedDialog = page.getByRole("dialog", { name: `${startLabel} – ${endLabel}`, exact: true });
    await field(page, startLabel).click();
    await button(translatedDialog, nextMonth).waitFor();
    await button(translatedDialog, confirm).waitFor();
    assert.equal(await day(translatedDialog, "2026-07-04").getAttribute("aria-label"), formattedDate("2026-07-04", locale, "full"));
    await button(translatedDialog, dismiss).click();
    await translatedDialog.waitFor({ state: "hidden" });
  }
  assert.deepEqual(unexpected, []);
  assert.deepEqual(errors, []);
  await page.close();
}

async function checkStatistics(browser) {
  const { page, calls, unexpected, errors } = await open(browser, "#/reports", {
    path: statisticsPath, permission: "VIEW_REPORTS", snapshot: query => statisticsSnapshot(query),
  });
  await page.getByRole("region", { name: "Ventas netas · EUR", exact: true }).waitFor();
  const initialRequests = calls.length;
  await page.getByRole("combobox", { name: "Comparación", exact: true }).selectOption("CUSTOM");
  const dialog = page.getByRole("dialog", { name: "Desde – Hasta", exact: true });
  const comparisonDialog = page.getByRole("dialog", { name: "Desde (comparación) – Hasta (comparación)", exact: true });
  const from = field(page, "Desde");
  const to = field(page, "Hasta");
  const comparedFrom = field(page, "Desde (comparación)");
  const comparedTo = field(page, "Hasta (comparación)");

  await from.click();
  await day(dialog, "2026-06-08").click();
  await day(dialog, "2026-06-12").click();
  await button(dialog, "Aceptar").click();
  await assertRange(from, to, "2026-06-08", "2026-06-12");
  assert.equal(await comparedFrom.inputValue(), "");
  assert.equal(await comparedTo.inputValue(), "");

  await comparedFrom.click();
  await button(comparisonDialog, "Mes anterior").click();
  await day(comparisonDialog, "2026-06-01").click();
  await day(comparisonDialog, "2026-06-05").click();
  await button(comparisonDialog, "Aceptar").click();
  await assertRange(comparedFrom, comparedTo, "2026-06-01", "2026-06-05");
  await assertRange(from, to, "2026-06-08", "2026-06-12");
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
  assert.equal(calls.length, initialRequests);
  await applyFilters(page, statisticsPath, {
    from: "2026-06-08", to: "2026-06-12", comparison: "CUSTOM", comparisonFrom: "2026-06-01", comparisonTo: "2026-06-05",
  });
  assert.deepEqual(unexpected, []);
  assert.deepEqual(errors, []);
  await page.close();
}

let browser;
try {
  await start();
  await mkdir(screenshots, { recursive: true });
  browser = await chromium.launch({ headless: true });
  await checkSummary(browser);
  await checkStatistics(browser);
  console.log("Date range E2E passed: summary, independent report periods, keyboard, cancellation, localization and mobile layout.");
} finally {
  await browser?.close();
  server.kill();
}
