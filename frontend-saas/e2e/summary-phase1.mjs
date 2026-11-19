import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";
import { accumulatedSummary, emptySummary, summaryAsOf, summaryPath } from "./summary-fixture.mjs";
import { statisticsPath, statisticsSnapshot } from "./statistics-fixture.mjs";

const base = "http://127.0.0.1:5204/";
const root = fileURLToPath(new URL("..", import.meta.url));
const vite = fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url));
const screenshots = fileURLToPath(new URL("../../output/playwright/", import.meta.url));
const env = { ...process.env };
delete env.VITE_SAAS_SUPERVISION_ENABLED;
const server = spawn(process.execPath, [vite, "--host", "127.0.0.1", "--port", "5204", "--strictPort", "--configLoader", "runner"], {
  cwd: root, env, stdio: "inherit", windowsHide: true,
});
const adminLogin = "/api/v1/auth/admin/login";
const adminSession = "/api/v1/admin/me";
const supervision = [
  ["health", "Estado de clientes"],
  ["failures", "Fallos de tiendas"],
  ["sync", "Sincronizacion"],
  ["fiscal", "Estado fiscal"],
  ["outbox", "Recuperación de entregas"],
  ["support", "Soporte"],
];
const accumulatedLabel = "Acumulado en SaaS";
const portfolioLabel = "Cartera actual";
const incompleteLabel = "Documentos con líneas o relaciones incompletas";
const currenciesLabel = "Importes documentales por moneda";
const filterOptions = {
  companies: [{ id: "company-1", name: "Empresa de resumen" }],
  stores: [{ id: "store-1", companyId: "company-1", name: "Tienda de resumen" }],
};
const recentLicense = accumulatedSummary.recentLicenses[0];
const licenseDetailPath = "/api/v1/admin/license-workspace/" + recentLicense.id;

async function start() {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error("Summary E2E Vite server exited before startup");
    try { if ((await fetch(base)).ok) return; } catch { /* bounded startup retry */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("Summary E2E Vite startup timed out");
}

async function open(browser, hash = "#/dashboard", permissions = ["VIEW_ADMIN_DATA"], drillDowns = false) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, timezoneId: "Asia/Shanghai" });
  page.setDefaultTimeout(10_000);
  await page.clock.setFixedTime(new Date(summaryAsOf));
  const pageErrors = [];
  const calls = [];
  const unexpected = [];
  let snapshot = accumulatedSummary;
  page.on("pageerror", error => pageErrors.push(error.message));
  await page.addInitScript(() => {
    window.renderedTitles = [];
    new MutationObserver(() => {
      const title = document.querySelector(".topbar h1")?.textContent;
      if (title) window.renderedTitles.push(title.trim());
    }).observe(document, { childList: true, characterData: true, subtree: true });
  });
  await page.route("**/api/v1/**", async route => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();
    calls.push({ path: url.pathname, method, query: url.search, authorization: request.headers().authorization });
    if (url.pathname === adminLogin && method === "POST") {
      return route.fulfill({ json: { username: "SUMMARY_E2E", accessToken: "summary-synthetic-token", mode: "admin", passwordChangeRequired: false, expiresAt: "2099-01-01T00:00:00Z" } });
    }
    if (url.pathname === adminSession && method === "GET") {
      return route.fulfill({ json: { username: "SUMMARY_E2E", permissions } });
    }
    if (url.pathname === summaryPath + "/options" && method === "GET") {
      return route.fulfill({ json: filterOptions });
    }
    if (url.pathname === summaryPath && method === "GET") {
      return snapshot === null
        ? route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Synthetic summary error" }) })
        : route.fulfill({ json: snapshot });
    }
    if (drillDowns && method === "GET") {
      if (url.pathname === statisticsPath + "/options") return route.fulfill({ json: filterOptions });
      if (url.pathname === statisticsPath) return route.fulfill({ json: statisticsSnapshot(url.searchParams) });
      if (["licenses", "installations", "fiscal-status", "companies", "users", "audit", "sync/events"].some(path => url.pathname === "/api/v1/admin/" + path)) {
        return route.fulfill({ json: [] });
      }
      if (url.pathname === "/api/v1/admin/sync/sales-summary") return route.fulfill({ json: { documentCount: 0, totalAmount: 0 } });
      if (url.pathname === "/api/v1/admin/reports/advanced") return route.fulfill({ json: null });
      if (url.pathname === "/api/v1/admin/license-workspace") {
        return route.fulfill({ json: { items: [], page: 0, size: 25, total: 0, totalPages: 0 } });
      }
      if (url.pathname === licenseDetailPath) {
        return route.fulfill({ json: {
          ...recentLicense, status: "CADUCADA", companyId: "company-1", taxId: "B00000000", stores: [],
          maxWindows: 1, maxPda: 0, activeInstallations: 0, lastValidatedAt: null, lastSyncAt: null,
          billingScope: "COMPANY", companyBillingStatus: null, companyDebt: [],
        } });
      }
    }
    unexpected.push(`${method} ${url.pathname}${url.search}`);
    return route.fulfill({ status: 404, body: "Unexpected summary smoke request" });
  });
  await page.goto(base + hash);
  await page.locator('input[autocomplete="username"]').fill("SUMMARY_E2E");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  await page.locator(".topbar h1").waitFor();
  return {
    page, calls, unexpected, pageErrors,
    setSnapshot(value) { snapshot = value; },
  };
}

function region(page, name = portfolioLabel) {
  return page.getByRole("region", { name, exact: true });
}

function summaryFilters(page) {
  return page.getByRole("heading", { name: "Ámbito y período", exact: true }).locator("..").locator("..");
}

async function assertNoSupervision(fixture) {
  const { page, unexpected, pageErrors } = fixture;
  assert.equal(new URL(page.url()).hash.split("?")[0], "#/dashboard");
  assert.deepEqual(unexpected, [], "Each view must use its authorized fixture endpoints");
  assert.deepEqual(pageErrors, []);
  const renderedTitles = await page.evaluate(() => window.renderedTitles);
  for (const [, title] of supervision) assert.equal(renderedTitles.includes(title), false, `${title} must never mount`);
}

async function assertUtc(page) {
  for (const [instant, clock] of [
    [summaryAsOf, "00:34"], [accumulatedSummary.events.firstReceivedAt, "23:58"], [accumulatedSummary.events.lastReceivedAt, "23:59"],
  ]) {
    const times = await page.locator(`.saas-dashboard time[datetime="${instant}"]`).allTextContents();
    assert.ok(times.length > 0, `Missing semantic date ${instant}`);
    assert.ok(times.every(text => text.includes(clock) && text.includes("UTC")), `Wrong UTC clock for ${instant}: ${times}`);
  }
}

async function selectLanguage(page, option) {
  await page.locator(".app-system-bar .language-trigger").click();
  await page.locator(".language-menu").getByRole("button", { name: option }).click();
}

let browser;
try {
  await start();
  await mkdir(screenshots, { recursive: true });
  browser = await chromium.launch({ headless: true });
  const fixture = await open(browser);
  const { page, calls } = fixture;
  const summary = page.locator(".saas-dashboard");
  const details = summary.locator("details.saas-summary-details");
  const detailToggle = details.locator("summary");
  await region(page).waitFor();
  assert.equal(calls.find(call => call.path === summaryPath)?.authorization, "Bearer summary-synthetic-token");
  assert.ok(calls.findIndex(call => call.path === adminSession) < calls.findIndex(call => call.path === summaryPath));
  assert.equal(await summary.getByText("523", { exact: true }).isVisible(), true);
  const byType = summary.getByRole("table", { name: "Eventos por tipo", exact: true });
  await byType.getByRole("row", { name: /Documento de venta.*320/ }).waitFor();
  await byType.getByRole("row", { name: /total.*523/i }).waitFor();
  await summary.getByRole("region", { name: "Sincronizaciones por tipo", exact: true }).getByText(accumulatedLabel, { exact: true }).waitFor();
  const registrations = summary.getByRole("table", { name: "Últimas 8 altas de licencias", exact: true });
  await registrations.waitFor();
  assert.equal(await details.evaluate(element => element.open), false);
  await detailToggle.focus();
  await detailToggle.press("Enter");
  await summary.getByRole("table", { name: "Resultado de sincronización", exact: true }).getByRole("row", { name: /Procesado.*500/ }).waitFor();
  const currencyTable = summary.getByRole("table", { name: currenciesLabel, exact: true });
  await currencyTable.getByRole("row", { name: /EUR.*195.*198[,.]75/ }).waitFor();
  await currencyTable.getByRole("row", { name: /USD.*13.*[-−]\s*15[,.]25/ }).waitFor();
  await summary.getByText(incompleteLabel, { exact: false }).waitFor();
  assert.equal(await registrations.locator("tbody tr").count(), 8);
  await registrations.getByRole("row", { name: /LIC-RESUMEN-1.*Sin tienda asignada/ }).waitFor();
  await registrations.getByRole("row", { name: /LIC-RESUMEN-2.*Varias tiendas \(3\)/ }).waitFor();
  await registrations.getByRole("row", { name: /LIC-RESUMEN-3.*Tienda de resumen/ }).waitFor();
  await assertUtc(page);
  await assertNoSupervision(fixture);
  const catalogue = page.getByRole("heading", { name: "Catálogo recibido · última captura por tienda", exact: true }).locator("..").locator("..");
  const catalogueMetric = region(page).getByText("Artículos recibidos", { exact: true }).locator("..");
  await catalogueMetric.getByText("762", { exact: true }).waitFor();
  await catalogueMetric.getByText(/Cobertura 6 de 9 tiendas/).waitFor();
  await catalogue.getByText("741", { exact: true }).waitFor();
  await catalogue.getByText("6 / 9", { exact: true }).waitFor();
  const periodActivity = region(page, "Actividad del período");
  await periodActivity.getByText("120", { exact: true }).waitFor();
  await periodActivity.getByText("6 con errores · 5 %", { exact: true }).waitFor();
  await periodActivity.getByText("EUR 270,00", { exact: true }).waitFor();
  await page.getByRole("img", { name: "Ventas netas · EUR", exact: true }).waitFor();
  await page.getByRole("img", { name: "Evolución de sincronización", exact: true }).waitFor();
  assert.equal(await summary.locator('a[href^="#/reports/"]').count(), 0, "Report links require VIEW_REPORTS");
  const filters = summaryFilters(page);
  const summaryRequests = () => calls.filter(call => call.path === summaryPath);
  const latestSummaryQuery = () => new URLSearchParams(summaryRequests().at(-1).query);
  const requestsBeforeDraft = summaryRequests().length;
  assert.equal(await filters.getByLabel("Período", { exact: true }).inputValue(), "LAST_30");
  await filters.getByLabel("Período", { exact: true }).selectOption("YESTERDAY");
  await filters.getByText("Cambios pendientes · pulsa Aplicar filtros", { exact: true }).waitFor();
  await filters.getByLabel("Empresa", { exact: true }).selectOption("company-1");
  await filters.getByLabel("Tienda", { exact: true }).selectOption("store-1");
  await filters.getByLabel("Provincia", { exact: true }).selectOption("28");
  assert.equal(summaryRequests().length, requestsBeforeDraft, "Period shortcuts and scope inputs change the draft only");
  await filters.getByRole("button", { name: "Aplicar filtros", exact: true }).click();
  await region(page).getByText("762", { exact: true }).waitFor();
  const applied = latestSummaryQuery();
  assert.equal(applied.get("from"), "2026-06-30"); assert.equal(applied.get("to"), "2026-06-30");
  assert.equal(applied.get("companyId"), "company-1"); assert.equal(applied.get("storeId"), "store-1"); assert.equal(applied.get("province"), "28");
  const stored = await page.evaluate(() => Object.entries(localStorage).filter(([key]) => key.startsWith("espos.analytics.context.v1.")));
  assert.equal(stored.length, 1); assert.ok(stored[0][0].includes('SUMMARY_E2E')); assert.ok(!stored[0][1].includes('synthetic-token'));
  assert.equal(await filters.getByText("Consultas y columnas", { exact: true }).count(), 0);
  await filters.getByRole("button", { name: "Restablecer filtros", exact: true }).click();
  await region(page).getByText("762", { exact: true }).waitFor();
  assert.equal(latestSummaryQuery().get("from"), "2026-06-02");
  assert.equal(latestSummaryQuery().get("companyId"), null);
  assert.equal(latestSummaryQuery().get("province"), null);
  await currencyTable.waitFor();
  await detailToggle.click();
  await page.setViewportSize({ width: 1440, height: 1340 });
  await page.screenshot({ path: screenshots + "/saas-summary-phase1-desktop.png", animations: "disabled", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.locator("body").evaluate(body => body.scrollWidth <= document.documentElement.clientWidth), true, "The dashboard must not overflow the mobile viewport");
  assert.equal(await registrations.locator("..").evaluate(wrapper => wrapper.scrollWidth > wrapper.clientWidth), true, "License columns must stay readable within their scroll area");
  await page.screenshot({ path: screenshots + "/saas-summary-phase1-mobile.png", animations: "disabled", fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await detailToggle.click();

  for (const [option, title, caption] of [
    [/^English/, "Current portfolio", "Document amounts by currency"],
    [/^中文/, "当前客户概况", "按币种列示单据金额"],
    [/^Español/, portfolioLabel, currenciesLabel],
  ]) {
    await selectLanguage(page, option);
    await region(page, title).waitFor();
    await summary.getByRole("table", { name: caption, exact: true }).waitFor();
    await assertUtc(page);
  }

  const sidebar = page.locator(".top-nav-list");
  const search = page.locator(".saas-nav-search input");
  assert.equal(await sidebar.getByRole("button", { name: "Supervisión", exact: true }).count(), 0);
  await search.fill("VeriFactu");
  await sidebar.getByRole("button", { name: "Activacion global de VeriFactu", exact: true }).waitFor();
  for (const [view, title] of supervision) {
    await search.fill(title);
    assert.equal(await sidebar.getByRole("button", { name: title, exact: true }).count(), 0);
    await search.clear();

    const initial = await open(browser, `#/${view}`);
    await region(initial.page).waitFor();
    await assertNoSupervision(initial);
    await initial.page.close();

    await page.evaluate(route => { location.hash = `#/${route}/synthetic-deep-link`; }, view);
    await page.waitForFunction(() => location.hash.split("?")[0] === "#/dashboard");
    await assertNoSupervision(fixture);

    const historyEntry = await page.evaluate(route => new Promise(resolve => {
      addEventListener("popstate", event => resolve(event.state?.disabledRoute), { once: true });
      history.pushState({ disabledRoute: route }, "", `#/${route}`);
      history.pushState({}, "", "#/dashboard");
      history.back();
    }), view);
    assert.equal(historyEntry, view, "Browser back must exercise the disabled history entry");
    await page.waitForURL(url => url.hash.split("?")[0] === "#/dashboard");
    await assertNoSupervision(fixture);
  }

  const refresh = page.locator(".topbar").getByRole("button", { name: "Actualizar", exact: true });
  const usd = { currency: "USD", documents: 2, amount: "-9007199254740993.17", averageAmount: "-4503599627370496.59", amountPerDay: "-300239975158033.11" };
  const exactUsdAmount = "-9.007.199.254.740.993,17";
  fixture.setSnapshot({
    ...accumulatedSummary,
    catalogue: { ...accumulatedSummary.catalogue, coveredStores: 0, products: null, activeProducts: null },
    events: {
      ...accumulatedSummary.events,
      byType: [
        { key: "DOCUMENTO", count: 320 }, { key: "STOCK_MOVEMENT", count: 155 },
        { key: "STORE_FAILURE", count: 20 }, { key: "FISCAL_STATUS", count: 20 }, { key: "GOODS_CHECK", count: 8 },
      ],
    },
    periodActivity: { ...accumulatedSummary.periodActivity, currencies: [...accumulatedSummary.periodActivity.currencies, usd] },
    daily: [...accumulatedSummary.daily, { date: "2026-06-03", syncEvents: 0, currencies: [usd] }],
  });
  await refresh.click();
  await periodActivity.getByText("EUR 270,00", { exact: true }).waitFor();
  await periodActivity.getByText(`USD ${exactUsdAmount}`, { exact: true }).waitFor();
  const usdChart = page.getByRole("img", { name: "Ventas netas · USD", exact: true }).locator("..");
  const usdChartPoint = usdChart.locator("svg [tabindex='0']");
  await usdChartPoint.focus();
  await usdChartPoint.press("ArrowRight");
  assert.ok((await usdChartPoint.getAttribute("aria-label"))?.includes(exactUsdAmount), "Chart keyboard points retain exact monetary precision");
  await usdChart.getByText(`2026-06-03 · USD: ${exactUsdAmount}`, { exact: true }).waitFor();
  await region(page).getByText("—", { exact: true }).waitFor();
  await catalogueMetric.getByText(/Sin captura de catálogo · Cobertura 0 de 9 tiendas/).waitFor();
  await byType.getByRole("row", { name: /Incidencia comunicada por tienda.*20/ }).waitFor();
  const syncPager = byType.locator("..");
  assert.equal(await byType.locator("tbody tr").count(), 4);
  await syncPager.getByRole("button", { name: "Ver todos los tipos", exact: true }).press("Enter");
  await byType.getByRole("row", { name: /Comprobación de pedido.*8/ }).waitFor();
  assert.equal(await syncPager.getByRole("button", { name: "Ver menos tipos", exact: true }).getAttribute("aria-expanded"), "true");
  await syncPager.getByRole("button", { name: "Ver menos tipos", exact: true }).click();
  await byType.getByRole("row", { name: /Documento de venta.*320/ }).waitFor();
  assert.equal(await byType.locator("tbody tr").count(), 4);

  fixture.setSnapshot({
    ...accumulatedSummary,
    documents: { ...accumulatedSummary.documents, unknownRelationships: 2, amounts: [] },
  });
  await refresh.click();
  await summary.getByText(/Importe no disponible/).waitFor();
  assert.equal(await currencyTable.getByRole("row", { name: /EUR|USD/ }).count(), 0);

  fixture.setSnapshot(null);
  await refresh.click();
  await page.getByRole("alert").filter({ hasText: "El servicio SaaS no está disponible temporalmente." }).waitFor();
  assert.equal(await page.getByText("Synthetic summary error", { exact: false }).count(), 0);
  assert.equal(await summary.getByText("523", { exact: true }).count(), 0);
  fixture.setSnapshot(emptySummary);
  const attempts = calls.filter(call => call.path === summaryPath).length;
  await page.getByRole("button", { name: "Reintentar", exact: true }).click();
  await summary.getByRole("table", { name: "Eventos por tipo", exact: true }).getByText("Todavía no se han recibido eventos.", { exact: true }).waitFor();
  await summary.getByRole("table", { name: "Documentos por tipo", exact: true }).getByText("Todavía no hay documentos de venta disponibles para estadísticas.", { exact: true }).waitFor();
  assert.equal(await page.getByRole("alert").count(), 0);
  assert.equal(calls.filter(call => call.path === summaryPath).length, attempts + 1);
  const emptyChart = region(page, "Evolución de ventas del período");
  await emptyChart.getByText("Sin actividad de ventas en este período.", { exact: true }).waitFor();
  assert.equal(await emptyChart.getByRole("img").count(), 0);
  await periodActivity.getByText("Sin sincronizaciones en este período.", { exact: true }).waitFor();
  const zeroCurrency = { currency: "EUR", documents: 2, amount: "0.00", averageAmount: "0.00", amountPerDay: "0.00" };
  fixture.setSnapshot({
    ...emptySummary,
    catalogue: { stores: 2, coveredStores: 2, products: 0, activeProducts: 0 },
    periodActivity: { ...emptySummary.periodActivity, documents: 2, currencies: [zeroCurrency] },
    daily: [{ date: "2026-06-02", syncEvents: 0, currencies: [zeroCurrency] }],
  });
  await refresh.click();
  await catalogueMetric.getByText("0", { exact: true }).waitFor();
  await catalogueMetric.getByText(/Cobertura 2 de 2 tiendas/).waitFor();
  await periodActivity.getByText("EUR 0,00", { exact: true }).first().waitFor();
  await page.getByRole("img", { name: "Ventas netas · EUR", exact: true }).waitFor();
  await assertNoSupervision(fixture);
  await page.close();

  const drillDown = await open(browser, "#/dashboard", ["VIEW_ADMIN_DATA", "VIEW_REPORTS"], true);
  const drillPage = drillDown.page;
  const drillFilters = summaryFilters(drillPage);
  await drillFilters.getByLabel("Período", { exact: true }).selectOption("YESTERDAY");
  await drillFilters.getByLabel("Empresa", { exact: true }).selectOption("company-1");
  await drillFilters.getByLabel("Tienda", { exact: true }).selectOption("store-1");
  await drillFilters.getByLabel("Provincia", { exact: true }).selectOption("28");
  await drillFilters.getByRole("button", { name: "Aplicar filtros", exact: true }).click();
  const salesLink = drillPage.getByRole("link", { name: /Ventas netas del período EUR/ });
  await salesLink.waitFor();
  const drillDetails = drillPage.locator("details.saas-summary-details");
  await drillDetails.locator("summary").click();
  for (const [metric, route] of [
    [/Empresas/, "#/companies"], [/Tiendas/, "#/stores"], [/Licencias/, "#/licenses"], [/Artículos recibidos/, "#/reports/inventory?"],
    [/Importe medio por documento/, "#/reports/averages?"], [/Sincronizaciones recibidas/, "#/reports/sync?"],
  ]) {
    const href = await drillPage.locator(".saas-summary-metrics").getByRole("link", { name: metric }).getAttribute("href");
    assert.ok(href?.startsWith(route), href);
  }
  const reportLoaded = drillPage.waitForResponse(response => new URL(response.url()).pathname === statisticsPath);
  await salesLink.click();
  await drillPage.waitForURL(url => url.hash.startsWith("#/reports/sales?"));
  await drillPage.getByRole("heading", { name: "Estadísticas", exact: true }).waitFor();
  await reportLoaded;
  const salesQuery = new URLSearchParams(drillDown.calls.find(call => call.path === statisticsPath).query);
  assert.equal(salesQuery.get("companyId"), "company-1");
  assert.equal(salesQuery.get("storeId"), "store-1");
  assert.equal(salesQuery.get("province"), "28");
  assert.equal(salesQuery.get("from"), "2026-06-30");
  await drillPage.goBack();
  await salesLink.waitFor();
  assert.equal(await drillDetails.evaluate(element => element.open), true);
  assert.equal(await drillFilters.getByLabel("Período", { exact: true }).inputValue(), "YESTERDAY");
  assert.equal(await drillFilters.getByLabel("Provincia", { exact: true }).inputValue(), "28");
  const recentLink = drillPage.getByRole("link", { name: recentLicense.reference, exact: true });
  await recentLink.focus();
  await recentLink.press("Enter");
  await drillPage.getByRole("dialog", { name: recentLicense.reference, exact: true }).waitFor();
  assert.deepEqual([...new Set(drillDown.calls
    .filter(call => call.path.startsWith("/api/v1/admin/license-workspace/")).map(call => call.path))], [licenseDetailPath]);
  assert.ok(drillDown.calls.filter(call => call.path === "/api/v1/admin/license-workspace")
    .every(call => !new URLSearchParams(call.query).has("q")), "Direct detail uses its UUID rather than searching the directory");
  await drillPage.goBack();
  await recentLink.waitFor();
  assert.equal(await drillFilters.getByLabel("Provincia", { exact: true }).inputValue(), "28");
  await recentLink.and(drillPage.locator(":focus")).waitFor();
  await assertNoSupervision(drillDown);
  await drillPage.close();

  const denied = await open(browser, "#/dashboard", []);
  await denied.page.getByText("Tu usuario no tiene permiso para esta accion.", { exact: true }).waitFor();
  assert.equal(denied.calls.some(call => call.path === summaryPath), false, "Summary requires VIEW_ADMIN_DATA");
  await assertNoSupervision(denied);
  console.log("Summary E2E passed: independent aggregates, coverage, currencies, UTC, ES/EN/ZH, screenshots, routing, permissions, empty and retry, draft filters, and drill-down return.");
} finally {
  await browser?.close();
  server.kill();
}
