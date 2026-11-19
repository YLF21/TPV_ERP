import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";
import { statisticsAsOf, statisticsOptions, statisticsPath, statisticsSnapshot } from "./statistics-fixture.mjs";

const base = "http://127.0.0.1:5205/";
const root = fileURLToPath(new URL("..", import.meta.url));
const vite = fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url));
const screenshots = fileURLToPath(new URL("../../output/playwright/", import.meta.url));
const server = spawn(process.execPath, [vite, "--host", "127.0.0.1", "--port", "5205", "--strictPort", "--configLoader", "runner"], {
  cwd: root, stdio: "inherit", windowsHide: true,
});

const adminLogin = "/api/v1/auth/admin/login";
const adminSession = "/api/v1/admin/me";
const optionsPath = `${statisticsPath}/options`;
const documentsPath = "/api/v1/admin/reports/documents";
const allowedPaths = new Set([adminLogin, adminSession, optionsPath, statisticsPath, documentsPath]);
const currencyCaption = "Importes por moneda";
const totalsCaption = "Recuentos del período";
const provinceCaption = "Documentos por provincia";
const dayCaption = "Documentos por día";

async function start() {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error("Statistics E2E Vite server exited before startup");
    try { if ((await fetch(base)).ok) return; } catch { /* bounded startup retry */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("Statistics E2E Vite startup timed out");
}

async function open(browser, permissions = ["VIEW_REPORTS"], initialResponse) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, timezoneId: "America/Los_Angeles" });
  page.setDefaultTimeout(10_000);
  await page.clock.setFixedTime(new Date(statisticsAsOf));
  const calls = [];
  const pageErrors = [];
  let respond = initialResponse ?? (query => ({ json: statisticsSnapshot(query) }));
  page.on("pageerror", error => pageErrors.push(error.message));
  await page.route("**/api/v1/**", async route => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();
    calls.push({ path: url.pathname, method, query: url.searchParams, authorization: request.headers().authorization });
    if (url.pathname === adminLogin && method === "POST") {
      return route.fulfill({ json: { username: "STATISTICS_E2E", accessToken: "statistics-synthetic-token", mode: "admin", passwordChangeRequired: false, expiresAt: "2099-01-01T00:00:00Z" } });
    }
    if (url.pathname === adminSession && method === "GET") {
      return route.fulfill({ json: { username: "STATISTICS_E2E", permissions } });
    }
    if (url.pathname === optionsPath && method === "GET") {
      assert.equal(url.search, "");
      return route.fulfill({ json: statisticsOptions });
    }
    if (url.pathname === documentsPath && method === "GET") {
      const { asOf, period, coverage, page, size } = statisticsSnapshot(url.searchParams);
      return route.fulfill({ json: { asOf, period, coverage, page, size, totalRows: 0, rows: [] } });
    }
    if (url.pathname === statisticsPath && method === "GET") {
      const response = await respond(url.searchParams);
      try {
        return await route.fulfill(response);
      } catch (error) {
        if (!request.failure() && !page.isClosed()) throw error;
      }
      return;
    }
    return route.fulfill({ status: 404, body: "Unexpected statistics smoke request" });
  });
  await page.goto(base + "#/reports");
  await page.locator('input[autocomplete="username"]').fill("STATISTICS_E2E");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  await page.locator(".topbar h1").waitFor();
  return { page, calls, pageErrors, setResponse(nextResponse) { respond = nextResponse; } };
}

const table = (page, name) => page.getByRole("table", { name, exact: true });
const filter = (page, name) => page.getByRole("combobox", { name, exact: true });
const apply = page => page.getByRole("button", { name: "Aplicar filtros", exact: true });
const queryMatches = (query, entries) => Object.entries(entries).every(([key, value]) => query.get(key) === value);

async function submit(page, action, entries, path = statisticsPath) {
  const [response] = await Promise.all([
    page.waitForResponse(result => {
      const url = new URL(result.url());
      return url.pathname === path && queryMatches(url.searchParams, entries);
    }),
    action.click(),
  ]);
  return response;
}

async function selectRange(page, fromLabel, toLabel, from, to, monthsBack = 0) {
  await page.getByRole("textbox", { name: fromLabel, exact: true }).click();
  const calendar = page.getByRole("dialog", { name: `${fromLabel} – ${toLabel}`, exact: true });
  for (let month = 0; month < monthsBack; month++) {
    await calendar.getByRole("button", { name: "Mes anterior", exact: true }).click();
  }
  await calendar.locator(`button[data-date="${from}"]`).click();
  await calendar.locator(`button[data-date="${to}"]`).click();
  await calendar.getByRole("button", { name: "Aceptar", exact: true }).click();
}

async function selectLanguage(page, option) {
  await page.locator(".app-system-bar .language-trigger").click();
  await page.locator(".language-menu").getByRole("button", { name: option }).click();
}

async function capture(page, { width, height }, name) {
  await page.setViewportSize({ width, height });
  await page.evaluate(() => {
    window.scrollTo(0, 0);
    document.querySelector(".main-panel").scrollTo(0, 0);
  });
  await page.screenshot({ path: `${screenshots}/saas-statistics-phase2-${name}.png`, animations: "disabled", fullPage: true });
}

async function amountCells(page, code, caption = currencyCaption, metric) {
  const matching = table(page, caption).getByRole("row")
    .filter({ has: page.getByRole("rowheader", { name: code, exact: true }) });
  const row = metric ? matching.filter({ has: page.getByRole("cell", { name: metric, exact: true }) }) : matching;
  await row.waitFor();
  return row.getByRole("cell").allTextContents();
}

const normalizedDecimal = value => value.replace(/[^\d,−-]/g, "").replace("−", "-");
const details = page => page.locator(".statistics-details");
const toggleDetails = page => details(page).locator("summary").press("Enter");
const salesStrip = (page, currency) => page.getByRole("region", { name: "Ventas netas · " + currency, exact: true });

async function verifyIsolation({ page, calls, pageErrors }, permitted) {
  assert.match(new URL(page.url()).hash.split("?")[0], /^#\/reports(?:\/sales)?$/);
  assert.deepEqual(pageErrors, []);
  assert.deepEqual(calls.filter(call => !allowedPaths.has(call.path)), [], "Statistics must not call advanced reports, manual exports, dashboard or fiscal services");
  const reportCalls = calls.filter(call => call.path === statisticsPath || call.path === optionsPath);
  if (permitted) {
    assert.ok(reportCalls.some(call => call.path === optionsPath));
    assert.ok(reportCalls.some(call => call.path === statisticsPath));
    assert.ok(calls.findIndex(call => call.path === adminSession) < calls.findIndex(call => call.path === optionsPath));
    assert.ok(calls.findIndex(call => call.path === adminSession) < calls.findIndex(call => call.path === statisticsPath));
    assert.ok(reportCalls.every(call => call.method === "GET" && call.authorization === "Bearer statistics-synthetic-token"));
  } else {
    assert.deepEqual(reportCalls, [], "Both endpoints require VIEW_REPORTS");
    assert.equal(calls.some(call => call.path === documentsPath), false, "Document details also require VIEW_REPORTS");
  }
}

async function verifyDrillThrough(page) {
  const scope = { from: "2026-07-01", to: "2026-07-31", groupBy: "DAY", comparison: "PREVIOUS_YEAR", province: "35" };
  const articleScope = new URLSearchParams({ from: "2026-06-01", to: "2026-06-30", currency: "CAD", productCode: "SEED" });
  articleScope.set("return", `#/reports?${new URLSearchParams(scope)}`);
  await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === documentsPath),
    page.evaluate(hash => { window.location.hash = hash; }, "#/reports/documents?" + articleScope),
  ]);
  const back = page.getByRole("link", { name: "Volver al informe", exact: true });
  await submit(page, back, scope);
  const tabs = page.getByRole("group", { name: "Informes", exact: true });
  await submit(page, tabs.getByRole("button", { name: "Medias", exact: true }), scope);
  await submit(page, tabs.getByRole("button", { name: "Ventas", exact: true }), scope);

  const currency = filter(page, "Moneda del gráfico");
  await currency.selectOption("USD");
  const result = table(page, "Importes por grupo y moneda");
  const prior = result.getByRole("link", { name: /^Ver documentos · Período comparado · .* · EUR$/ }).first();
  await submit(page, prior, { from: "2025-07-01", to: "2025-07-01", province: "35", currency: "EUR", productCode: null }, documentsPath);
  await submit(page, back, scope);
  assert.equal(await currency.inputValue(), "USD");

  const allCurrent = page.locator(".statistics-metadata").getByRole("link", { name: "Ver documentos · Período consultado", exact: true });
  await submit(page, allCurrent, { from: scope.from, to: scope.to, province: "35", currency: null, productCode: null }, documentsPath);
  await submit(page, back, scope);
}

let browser;
let activeFixture;
try {
  await start();
  await mkdir(screenshots, { recursive: true });
  browser = await chromium.launch({ headless: true });

  const initial = Promise.withResolvers();
  const fixture = await open(browser, ["VIEW_REPORTS"], async query => {
    await initial.promise;
    return { json: statisticsSnapshot(query) };
  });
  activeFixture = fixture;
  const { page, calls } = fixture;
  await page.getByRole("heading", { name: "Estadísticas", exact: true }).waitFor();
  await page.locator(".statistics-report-status").getByRole("status").filter({ hasText: "Cargando…" }).waitFor();
  initial.resolve();
  await page.locator(".top-nav-list").getByRole("button", { name: "Informes", exact: true }).waitFor();
  await salesStrip(page, "EUR").getByText("EUR 9.007.199.254.740.993,17", { exact: true }).waitFor();
  await salesStrip(page, "EUR").getByText("EUR 603,00", { exact: true }).waitFor();
  await salesStrip(page, "USD").getByText("USD -15,25", { exact: true }).waitFor();
  assert.equal(await salesStrip(page, "USD").getByText("—", { exact: true }).count(), 2);
  await salesStrip(page, "GBP").getByText("GBP 0,00", { exact: true }).waitFor();
  const chart = page.locator(".statistics-chart");
  await chart.locator('svg g[tabindex="0"]').first().focus();
  await chart.locator(".analytics-chart-focus").filter({ hasText: "9.007.199.254.740.993,17" }).waitFor();
  assert.ok(await chart.locator('rect[fill="currentColor"]').count() > 1, "Large decimal amounts still have a plotted bar");
  const groupSection = page.locator('section[aria-labelledby="statistics-group-heading"]');
  const chartPoints = chart.locator("[data-chart-point]");
  const points = await chartPoints.count();
  assert.ok(points > 25, "The chart includes groups beyond the first table page");
  const chartRequests = () => calls.filter(call => call.path === statisticsPath && new URLSearchParams(call.query).get("size") === "100").length;
  const chartRequestCount = chartRequests();
  await submit(page, groupSection.getByRole("button", { name: "Siguiente", exact: true }), { page: "1", size: "25" });
  assert.equal(await chartPoints.count(), points);
  assert.equal(chartRequests(), chartRequestCount, "The chart scope remains stable as the table changes pages");
  await submit(page, groupSection.getByRole("button", { name: "Anterior", exact: true }), { page: "0", size: "25" });
  assert.equal(await details(page).getAttribute("open"), null);
  await toggleDetails(page);
  await table(page, totalsCaption).getByRole("row", { name: /Documentos de venta.*243/ }).waitFor();
  assert.ok((await amountCells(page, "EUR")).map(normalizedDecimal).includes("9007199254740993,17"));
  assert.ok((await amountCells(page, "USD")).map(normalizedDecimal).includes("-15,25"));
  assert.ok((await amountCells(page, "GBP")).map(normalizedDecimal).includes("0,00"));
  assert.ok((await amountCells(page, "EUR")).map(normalizedDecimal).includes("603,00"));

  const reportTabs = page.getByRole("group", { name: "Informes", exact: true });
  await reportTabs.getByRole("button", { name: "Sincronización", exact: true }).click();
  await table(page, totalsCaption).getByRole("row", { name: /Eventos recibidos.*523/ }).waitFor();
  await table(page, totalsCaption).getByRole("row", { name: /Eventos de stock recibidos.*211/ }).waitFor();
  await table(page, "Eventos por tipo").getByRole("row", { name: /Documento.*312/i }).waitFor();
  await table(page, "Eventos por estado").getByRole("row", { name: /Error.*17.*8/i }).waitFor();
  await reportTabs.getByRole("button", { name: "Medias", exact: true }).click();
  assert.ok((await amountCells(page, "EUR", "Medias por moneda", "Media por documento")).map(normalizedDecimal).includes("44811936590751,21"));
  assert.ok((await amountCells(page, "EUR", "Medias por moneda", "Media diaria")).map(normalizedDecimal).includes("300239975158033,11"));
  await filter(page, "Agrupar por").selectOption("PROVINCE");
  await submit(page, apply(page), { groupBy: "PROVINCE", page: "0" });
  await table(page, "Medias por grupo y moneda").getByRole("rowheader", { name: "Araba/Álava", exact: true }).first().waitFor();
  await submit(page, page.getByRole("button", { name: "Siguiente", exact: true }), { groupBy: "PROVINCE", page: "1" });
  await table(page, "Medias por grupo y moneda").getByText("No hay importes computables en los grupos de esta página.", { exact: true }).waitFor();
  await reportTabs.getByRole("button", { name: "Ventas", exact: true }).click();
  const timestamp = page.locator(`time[datetime="${statisticsAsOf}"]`);
  assert.match(await timestamp.innerText(), /00:34.*UTC/);
  await toggleDetails(page);

  const comparison = filter(page, "Comparación");
  const grouping = filter(page, "Agrupar por");
  const company = filter(page, "Empresa");
  const store = filter(page, "Tienda");
  const [atlantic, mainland] = statisticsOptions.companies;
  const [portStore, inlandStore] = statisticsOptions.stores;
  const serviceUnavailable = { status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Synthetic statistics error" }) };
  const error = page.getByRole("alert").filter({ hasText: "El servicio SaaS no está disponible temporalmente." });
  const nextPage = page.getByRole("button", { name: "Siguiente", exact: true });
  const priorPage = page.getByRole("button", { name: "Anterior", exact: true });

  await comparison.selectOption("PREVIOUS_PERIOD");
  await grouping.selectOption("PROVINCE");
  await submit(page, apply(page), { comparison: "PREVIOUS_PERIOD", groupBy: "PROVINCE", page: "0" });
  const firstProvince = table(page, provinceCaption).getByRole("rowheader", { name: "Araba/Álava", exact: true });
  await firstProvince.waitFor();
  await table(page, totalsCaption).getByRole("row", { name: /Documentos de venta.*243.*205/ }).waitFor();
  const pageOneTotals = await table(page, totalsCaption).innerText();
  const groupedAmounts = table(page, "Importes por grupo y moneda");
  const pageOneAmountTotal = await groupedAmounts.locator("tfoot").innerText();
  assert.match(pageOneAmountTotal, /Total del período.*EUR/s);
  assert.equal(await table(page, provinceCaption).locator("tbody tr").count(), 25);
  const secondProvincePage = { groupBy: "PROVINCE", page: "1", size: "25" };
  fixture.setResponse(() => serviceUnavailable);
  await submit(page, nextPage, secondProvincePage);
  await error.waitFor();
  fixture.setResponse(query => ({ json: statisticsSnapshot(query) }));
  await submit(page, error.getByRole("button", { name: "Reintentar", exact: true }), secondProvincePage);
  await table(page, provinceCaption).getByRole("rowheader", { name: "Provincia sin identificar", exact: true }).waitFor();
  assert.equal(await table(page, provinceCaption).locator("tbody tr").count(), 1);
  assert.equal(await table(page, totalsCaption).innerText(), pageOneTotals, "Pagination must preserve server totals across all groups");
  assert.equal(await groupedAmounts.locator("tfoot").innerText(), pageOneAmountTotal, "Grouped amount totals cover the entire period");
  await submit(page, priorPage, { groupBy: "PROVINCE", page: "0" });
  await firstProvince.waitFor();

  await comparison.selectOption("PREVIOUS_YEAR");
  await submit(page, apply(page), { comparison: "PREVIOUS_YEAR", page: "0" });
  await page.locator(".statistics-periods time[datetime^='2025-']").first().waitFor();

  await company.selectOption(atlantic.id);
  await store.selectOption(portStore.id);
  await grouping.selectOption("STORE");
  await submit(page, apply(page), { companyId: atlantic.id, storeId: portStore.id, groupBy: "STORE" });
  await table(page, "Documentos por tienda").getByRole("rowheader", { name: portStore.name, exact: true }).waitFor();
  assert.equal(await store.getByRole("option", { name: inlandStore.name, exact: true }).count(), 0);
  await company.selectOption(mainland.id);
  assert.equal(await store.inputValue(), "", "Changing company must clear the prior store");
  assert.equal(await store.getByRole("option", { name: portStore.name, exact: true }).count(), 0);
  await submit(page, apply(page), { companyId: mainland.id, storeId: null, groupBy: "STORE", page: "0" });
  await table(page, "Documentos por tienda").getByRole("rowheader", { name: inlandStore.name, exact: true }).waitFor();

  await company.selectOption("");
  await grouping.selectOption("DAY");
  await comparison.selectOption("CUSTOM");
  await selectRange(page, "Desde", "Hasta", "2026-07-01", "2026-07-03");
  await selectRange(page, "Desde (comparación)", "Hasta (comparación)", "2026-06-08", "2026-06-11", 1);
  fixture.setResponse(query => ({ json: statisticsSnapshot(query, { ordinaryAmounts: true }) }));
  await submit(page, apply(page), {
    comparison: "CUSTOM", groupBy: "DAY", from: "2026-07-01", to: "2026-07-03", comparisonFrom: "2026-06-08", comparisonTo: "2026-06-11", page: "0",
  });
  const daily = table(page, dayCaption);
  const firstBusinessDate = daily.locator('time[datetime="2026-07-01"]');
  await firstBusinessDate.waitFor();
  assert.equal(await firstBusinessDate.innerText(), new Intl.DateTimeFormat("es-ES", { dateStyle: "short", timeZone: "UTC" }).format(new Date("2026-07-01T00:00:00Z")));
  assert.doesNotMatch(await firstBusinessDate.innerText(), /UTC/);
  await daily.locator('time[datetime="2026-06-11"]').waitFor();
  assert.equal(await daily.locator("tbody tr").count(), 4, "Comparison-only dates must appear");

  await toggleDetails(page);
  await capture(page, { width: 1440, height: 2200 }, "desktop");
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.locator("body").evaluate(body => body.scrollWidth <= document.documentElement.clientWidth), true, "Statistics must not overflow the mobile viewport");
  await capture(page, { width: 390, height: 844 }, "mobile");
  await page.setViewportSize({ width: 1440, height: 1000 });
  await toggleDetails(page);

  await comparison.selectOption("NONE");
  await submit(page, apply(page), { comparison: "NONE", comparisonFrom: null, comparisonTo: null, groupBy: "DAY", page: "0" });
  await daily.getByRole("columnheader", { name: "Período comparado", exact: false }).first().waitFor({ state: "hidden" });
  assert.equal(await daily.locator("tbody tr").count(), 3);
  assert.equal(await page.locator(".statistics-periods").getByText("Período comparado", { exact: true }).count(), 0);

  for (const [option, title, caption] of [
    [/^English/, "Statistics", "Amounts by currency"],
    [/^中文/, "统计", "按币种统计金额"],
    [/^Español/, "Estadísticas", currencyCaption],
  ]) {
    await selectLanguage(page, option);
    await page.getByRole("heading", { name: title, exact: true }).waitFor();
    await table(page, caption).waitFor();
    const formattedTimestamp = await timestamp.innerText();
    assert.match(formattedTimestamp, /00:34/);
    assert.match(formattedTimestamp, /UTC/);
  }

  fixture.setResponse(query => ({ json: statisticsSnapshot(query, { unknown: true }) }));
  await submit(page, apply(page), { groupBy: "DAY", comparison: "NONE", page: "0" });
  const unknownCurrency = table(page, currencyCaption);
  await unknownCurrency.getByText(/Importes y medias no disponibles mientras haya relaciones documentales/).waitFor();
  await page.locator(".statistics-coverage-warning").filter({ hasText: /Importes y medias no disponibles/ }).waitFor();
  assert.equal(await unknownCurrency.getByText("0,00", { exact: true }).count(), 0, "Unknown amounts must not look like real zero amounts");
  assert.deepEqual(await table(page, totalsCaption).getByRole("row", { name: /Documentos computables/ }).getByRole("cell").allTextContents(), ["—"]);

  const releaseStale = Promise.withResolvers();
  fixture.setResponse(async query => {
    if (query.get("groupBy") === "COMPANY") {
      await releaseStale.promise;
      return { json: statisticsSnapshot(query, { marker: 991 }) };
    }
    return { json: statisticsSnapshot(query, { marker: 867 }) };
  });
  const isStale = request => {
    const url = new URL(request.url());
    return url.pathname === statisticsPath && url.searchParams.get("groupBy") === "COMPANY";
  };
  await grouping.selectOption("COMPANY");
  await Promise.all([page.waitForRequest(isStale), apply(page).click()]);
  const staleRequest = page.waitForEvent("requestfailed", { predicate: isStale }).catch(() => null);
  const staleResponse = page.waitForEvent("requestfinished", { predicate: isStale }).catch(() => null);
  await grouping.selectOption("PROVINCE");
  await submit(page, apply(page), { groupBy: "PROVINCE", page: "0" });
  await table(page, totalsCaption).getByRole("row", { name: /Documentos de venta.*867/ }).waitFor();
  releaseStale.resolve();
  assert.ok(await Promise.race([staleRequest, staleResponse]), "The stale request should either settle or be cancelled");
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await table(page, provinceCaption).waitFor();
  assert.equal(await page.getByText("991", { exact: true }).count(), 0, "A stale response must not replace the active filter result");

  fixture.setResponse(() => serviceUnavailable);
  await grouping.selectOption("STORE");
  await submit(page, apply(page), { groupBy: "STORE", page: "0" });
  await error.waitFor();
  assert.equal(await page.getByText("Synthetic statistics error", { exact: false }).count(), 0);
  assert.equal(await page.getByText("867", { exact: true }).count(), 0);
  fixture.setResponse(query => ({ json: statisticsSnapshot(query, { empty: true }) }));
  const attempts = calls.filter(call => call.path === statisticsPath).length;
  await submit(page, error.getByRole("button", { name: "Reintentar", exact: true }), { groupBy: "STORE", page: "0" });
  await table(page, totalsCaption).getByRole("row", { name: /Documentos de venta.*0/ }).waitFor();
  await table(page, "Documentos por tienda").getByText("No hay datos para esta agrupación.", { exact: true }).waitFor();
  await table(page, currencyCaption).getByText("No hay importes computables para este período.", { exact: true }).waitFor();
  await error.waitFor({ state: "hidden" });
  assert.equal(calls.filter(call => call.path === statisticsPath).length, attempts + 1);
  await reportTabs.getByRole("button", { name: "Sincronización", exact: true }).click();
  await table(page, "Eventos por tipo").getByText("No hay eventos recibidos para estos filtros.", { exact: true }).waitFor();
  await reportTabs.getByRole("button", { name: "Ventas", exact: true }).click();
  await verifyIsolation(fixture, true);
  await page.close();

  const drillThrough = await open(browser);
  activeFixture = drillThrough;
  await verifyDrillThrough(drillThrough.page);
  await verifyIsolation(drillThrough, true);
  await drillThrough.page.close();

  const denied = await open(browser, ["VIEW_ADMIN_DATA"]);
  activeFixture = denied;
  await denied.page.getByText("Tu usuario no tiene permiso para esta accion.", { exact: true }).waitFor();
  assert.equal(await denied.page.locator(".top-nav-list").getByRole("button", { name: "Informes", exact: true }).count(), 0);
  await verifyIsolation(denied, false);
  await denied.page.close();

  console.log("Statistics E2E passed: permissions, isolated endpoints, exact currencies, comparisons, server pagination, report context and document links, UTC and business dates, races, retries, ES/EN/ZH and responsive screenshots.");
} catch (error) {
  if (activeFixture && !activeFixture.page.isClosed()) {
    const { page, calls, pageErrors } = activeFixture;
    console.error("Statistics calls:", calls.slice(-5).map(call => `${call.method} ${call.path}?${call.query}`));
    if (pageErrors.length) console.error("Browser errors:", pageErrors);
    const content = await page.locator("main").innerText({ timeout: 1_000 }).catch(() => "");
    console.error("Current report:", content.slice(0, 2000));
  }
  throw error;
} finally {
  await browser?.close();
  server.kill();
}
