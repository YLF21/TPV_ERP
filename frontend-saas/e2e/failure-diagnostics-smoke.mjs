import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

// Browser contract coverage uses synthetic responses, not a running backend.
const root = fileURLToPath(new URL("..", import.meta.url));
const base = "http://127.0.0.1:5197/";
const companyId = "10000000-0000-4000-8000-000000000001";
const storeId = "20000000-0000-4000-8000-000000000001";
const traceId = "30000000-0000-4000-8000-000000000001";
const record = {
  id: "LOCAL_APPLICATION:40000000-0000-4000-8000-000000000001", source: "LOCAL_APPLICATION",
  sourceId: "40000000-0000-4000-8000-000000000001", companyId, companyName: "Empresa sin licencia",
  storeId, storeName: "Tienda diagnóstica", internalCode: "3500007", installationId: "50000000-0000-4000-8000-000000000001",
  installationReference: "INST-DIAGNOSTIC", status: "OPEN", severity: "DANGER", code: "APPLICATION_ERROR",
  detail: "Application operation failed. Trace: " + traceId, module: "SALES", appVersion: "1.4.2-test",
  traceId, exceptionType: "java.lang.IllegalStateException", errorLocation: "com.tpverp.SaleService.save:42",
  firstSeenAt: "2026-09-20T08:00:00Z", lastSeenAt: "2026-09-20T09:00:00Z", receivedAt: "2026-09-20T12:00:00Z",
  occurrences: 3, central: false, storeActive: true,
};
const legacy = { ...record, id: "LOCAL_SYNC:40000000-0000-4000-8000-000000000002", source: "LOCAL_SYNC", code: "SYNC_DELIVERY_FAILED",
  detail: "Synthetic legacy delivery failure", module: undefined, appVersion: undefined, traceId: undefined,
  exceptionType: undefined, errorLocation: undefined, receivedAt: undefined };
const companyB = "10000000-0000-4000-8000-000000000002";
const storeRows = Array.from({ length: 108 }, (_, i) => ({
  id: i === 0 ? storeId : `20000000-0000-4000-8000-${String(i + 1).padStart(12, "0")}`,
  companyId: i === 0 ? companyId : companyB, companyName: i === 0 ? record.companyName : "Otra empresa",
  code: String(i + 1).padStart(3, "0"), internalCode: i === 0 ? record.internalCode : String(3500010 + i),
  name: i === 0 ? record.storeName : `Sucursal ${i}`, active: true,
}));
storeRows[107].internalCode = "3599999";
const distantStore = storeRows[107];
const records = [record, legacy, ...Array.from({ length: 53 }, (_, i) => ({ ...record,
  id: `LOCAL_APPLICATION:40000000-0000-4000-8000-${String(i + 3).padStart(12, "0")}`,
  sourceId: `40000000-0000-4000-8000-${String(i + 3).padStart(12, "0")}`,
  companyId: companyB, companyName: "Otra empresa", storeId: i === 52 ? distantStore.id : storeRows[i + 1].id,
  internalCode: i === 52 ? distantStore.internalCode : storeRows[i + 1].internalCode,
  storeName: i === 52 ? distantStore.name : storeRows[i + 1].name,
  traceId: `trace-secondary-${i}`, detail: `Synthetic secondary failure ${i}`,
}))];
const calls = []; const errors = []; let browser;
const server = spawn(process.execPath, [fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url)),
  "--host", "127.0.0.1", "--port", "5197", "--strictPort", "--configLoader", "runner"], { cwd: root, stdio: "ignore", windowsHide: true });
try {
  let started = false;
  for (let i = 0; i < 100; i++) {
    if (server.exitCode !== null) throw new Error("Vite exited before diagnostics test startup");
    try { if ((await fetch(base)).ok) { started = true; break; } } catch { /* startup polling */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(started, "Vite started");
  browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/api/**", async route => {
    const req = route.request(); const url = new URL(req.url()); const path = decodeURIComponent(url.pathname);
    calls.push({ path, method: req.method(), query: Object.fromEntries(url.searchParams) });
    const json = value => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/v1/auth/admin/login") return json({ username: "DIAGNOSTICS", accessToken: "synthetic-token", mode: "admin", expiresAt: "2099-01-01T00:00:00Z", passwordChangeRequired: false });
    if (path === "/api/v1/admin/me") return json({ username: "DIAGNOSTICS", permissions: ["VIEW_ADMIN_DATA"] });
    if (path === "/api/v1/admin/companies") return json([{ companyId, companyName: record.companyName, taxId: "B00000001" }, { companyId: companyB, companyName: "Otra empresa", taxId: "B00000002" }]);
    if (path === "/api/v1/admin/licenses") return json([]);
    if (path === "/api/v1/admin/sync/sales-summary") return json({ documentCount: 0, total: "0.00" });
    if (path === "/api/v1/admin/reports/advanced") return json({ companies: 1, invoices: 0, invoicedTotal: "0.00", paidTotal: "0.00", salesDocuments: 0, salesTotal: "0.00", inventoryMovements: 0, integrations: 0, activeIntegrations: 0 });
    if (path === "/api/v1/admin/stores") {
      const q = (url.searchParams.get("q") ?? "").trim().toLowerCase();
      const company = url.searchParams.get("companyId");
      const filtered = storeRows.filter(s => (!company || s.companyId === company)
        && (!q || [s.internalCode, s.code, s.name, s.companyName].some(v => v.toLowerCase().includes(q))));
      // Make late responses observable without relying on a particular network speed.
      if (q === "3599999") await new Promise(resolve => setTimeout(resolve, 180));
      return json({ items: filtered.slice(0, 100), page: 0, size: 100, total: filtered.length, totalPages: Math.ceil(filtered.length / 100) });
    }
    if (path === "/api/v1/admin/supervision/failures") {
      const p = url.searchParams; const q = (p.get("q") ?? "").trim().toLowerCase();
      const filtered = records.filter(r => (!p.get("source") || r.source === p.get("source"))
        && (!p.get("companyId") || r.companyId === p.get("companyId"))
        && (!p.get("storeId") || r.storeId === p.get("storeId"))
        && (!p.get("installationId") || r.installationId === p.get("installationId"))
        && (!p.get("status") || r.status === p.get("status"))
        && (!q || [r.companyName, r.storeName, r.internalCode, r.installationReference, r.installationId, r.code, r.sourceId, r.traceId, r.module].join(" ").toLowerCase().includes(q)));
      const offset = Number(p.get("cursor") || 0); const size = Number(p.get("size") || 50);
      const hasMore = filtered.length > offset + size;
      return json({ items: filtered.slice(offset, offset + size), nextCursor: hasMore ? String(offset + size) : null, hasMore });
    }
    if (path.endsWith("/repairs")) return json({ remoteEligible: false, ineligibleReason: "UNSUPPORTED_SOURCE", commands: [], manualTicketId: null });
    if (path.startsWith("/api/v1/admin/supervision/failures/")) {
      const selected = records.find(r => path.endsWith(`/${r.id}`));
      return selected ? json(selected) : route.fulfill({ status: 404, body: "Not found" });
    }
    assert.equal(req.method(), "GET", `Unexpected mutation: ${req.method()} ${path}`);
    return json([]);
  });
  await page.goto(`${base}#/login`, { waitUntil: "domcontentloaded" });
  await page.locator('input[autocomplete="username"]').fill("DIAGNOSTICS");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  await page.locator(".saas-dashboard").waitFor();
  await page.locator(".top-nav-list").getByRole("button", { name: "Fallos de tiendas", exact: true }).click();
  await page.getByRole("cell", { name: /APPLICATION_ERROR/ }).first().waitFor();
  const captureDirectory = fileURLToPath(new URL("../../output/playwright/", import.meta.url));
  await mkdir(captureDirectory, { recursive: true });
  await page.screenshot({ path: captureDirectory + "/saas-failures-list.png", fullPage: true });
  assert.equal(await page.locator(".saas-data-table").count(), 1, "Failures use the shared directory table");
  const toggle = page.getByLabel("Solo tiendas activas", { exact: true });
  assert.ok(await toggle.evaluate(input => {
    const label = input.closest("label").getBoundingClientRect();
    const checkbox = input.getBoundingClientRect();
    return checkbox.x - label.x < 12 && Math.abs(checkbox.y + checkbox.height / 2 - label.y - label.height / 2) < 4;
  }), "Active store checkbox stays beside its label");
  const detail = page.getByRole("region", { name: "Detalle", exact: true });
  const detailButton = page.getByRole("button", { name: "Detalle", exact: true }).first();
  assert.equal(await page.locator("tbody tr").count(), 50, "Full first page exercises long-list detail navigation");
  await detailButton.click();
  await detail.locator(".failure-overview").waitFor();
  assert.equal(await detail.locator(".failure-technical").getAttribute("open"), null, "Technical identifiers are collapsed by default");
  assert.equal(await detail.getByRole("button", { name: "Copiar ID de instalación" }).isVisible(), false);
  assert.equal(await detail.evaluate(el => document.activeElement === el), true, "Opening detail moves keyboard focus");
  const detailBounds = await detail.boundingBox();
  assert.ok(detailBounds.y >= 0 && detailBounds.y < 1000, "Detail scrolls into view below long table");
  await detail.getByRole("button", { name: "Cerrar detalle" }).click();
  assert.equal(await detailButton.evaluate(el => document.activeElement === el), true, "Close returns focus to originating row");
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll("tbody tr").length === 5);
  await page.getByRole("button", { name: "Anterior", exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll("tbody tr").length === 50);
  const storeInput = page.getByLabel("Buscar tienda por nombre o código", { exact: true });
  // Paste a full label for a store absent from the initial 100 options.
  assert.equal(await page.locator(`#failure-stores option[value="${distantStore.internalCode} · ${distantStore.name}"]`).count(), 0);
  await storeInput.fill(`${distantStore.internalCode} · ${distantStore.name}`);
  await page.locator(`#failure-stores option[value="${distantStore.internalCode} · ${distantStore.name}"]`).waitFor({ state: "attached" });
  await page.getByRole("button", { name: "Aplicar filtros", exact: true }).click();
  await page.getByRole("cell", { name: new RegExp(distantStore.name) }).waitFor();
  assert.equal(await page.locator("tbody tr").count(), 1);
  assert.ok(calls.some(c => c.path.endsWith("supervision/failures") && c.query.storeId === distantStore.id));
  // Editing the label clears the selected id; choosing a new suggestion uses its real code.
  await storeInput.fill(record.internalCode);
  await page.locator(`#failure-stores option[value="${record.internalCode} · ${record.storeName}"]`).waitFor({ state: "attached" });
  await storeInput.fill(`${record.internalCode} · ${record.storeName}`);
  await page.getByRole("button", { name: "Aplicar filtros", exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll("tbody tr").length === 2);
  assert.ok(calls.some(c => c.path.endsWith("supervision/failures") && c.query.storeId === storeId));
  assert.ok(!calls.some(c => c.path === "/api/v1/admin/stores" && c.query.q?.includes(" · ")), "Display labels never become server lookup queries");
  await page.getByRole("button", { name: "Limpiar filtros", exact: true }).click();
  await page.getByLabel(/^Empresa/).selectOption(companyId);
  assert.equal(await page.getByLabel(/^Empresa/).inputValue(), companyId, "Companies without licenses remain searchable");
  await page.getByLabel("Buscar nombre, código o referencia", { exact: true }).fill(traceId);
  await page.getByLabel(/^Origen/).selectOption("LOCAL_APPLICATION");
  await page.getByRole("button", { name: "Aplicar filtros", exact: true }).click();
  await page.getByRole("button", { name: "Detalle", exact: true }).click();
  await detail.locator(".failure-technical summary").click();
  await detail.getByText(record.errorLocation, { exact: true }).waitFor();
  assert.equal(await page.locator("tbody tr").count(), 1, "Company/source/trace actually filter displayed results");
  assert.ok(calls.some(c => c.query.q === traceId && c.query.source === "LOCAL_APPLICATION" && c.query.companyId === companyId), "Trace, company and source filters reach API");
  for (const value of [record.appVersion, record.exceptionType, record.traceId, record.detail, "Ventas", "Última recepción en SaaS"]) {
    assert.ok(await detail.getByText(value, { exact: true }).isVisible(), `Diagnostic is visible: ${value}`);
  }
  assert.notEqual(await detail.locator("dt").filter({ hasText: /^Última recepción en SaaS$/ }).locator("+ dd").innerText(), "No comunicado");
  await page.evaluate(() => Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: async () => { throw new Error("Synthetic clipboard denied"); } } }));
  await detail.getByRole("button", { name: "Copiar ID de seguimiento" }).click();
  await page.getByText("No se pudo copiar. Selecciona y copia el ID de seguimiento manualmente.", { exact: true }).waitFor();
  await page.evaluate(() => Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: async value => { window.copiedTrace = value; } } }));
  await detail.getByRole("button", { name: "Copiar ID de seguimiento" }).click();
  await page.getByText("ID de seguimiento copiado.", { exact: true }).waitFor();
  assert.equal(await page.evaluate(() => window.copiedTrace), traceId);
  await detail.getByRole("button", { name: "Copiar ID de instalación", exact: true }).click();
  assert.equal(await page.evaluate(() => window.copiedTrace), record.installationId);
  await detail.getByRole("button", { name: "Copiar referencia del origen", exact: true }).click();
  assert.equal(await page.evaluate(() => window.copiedTrace), record.sourceId);
  const output = fileURLToPath(new URL("../../output/playwright/", import.meta.url));
  await mkdir(output, { recursive: true });
  await page.screenshot({ path: `${output}/saas-failure-diagnostics.png`, fullPage: true });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), "Diagnostics page does not overflow viewport");
  await page.getByRole("button", { name: "Limpiar filtros", exact: true }).click();
  await page.getByRole("row").filter({ hasText: "SYNC_DELIVERY_FAILED" }).getByRole("button", { name: "Detalle", exact: true }).click();
  await detail.locator(".failure-technical summary").click();
  await detail.getByText(legacy.detail, { exact: true }).waitFor();
  for (const label of ["Última recepción en SaaS", "Módulo", "Versión de la aplicación", "ID de seguimiento", "Tipo de excepción", "Ubicación del error"]) {
    const value = detail.locator("dt").filter({ hasText: new RegExp("^" + label + "$") }).locator("+ dd");
    assert.match(await value.innerText(), /^No comunicado/, "Missing legacy field: " + label);
  }
  assert.equal(await detail.getByRole("button", { name: "Copiar ID de seguimiento" }).count(), 0);
  await detail.getByRole("button", { name: "Cerrar detalle" }).click();
  await page.getByLabel("Buscar nombre, código o referencia", { exact: true }).fill("missing-trace-000");
  await page.getByRole("button", { name: "Aplicar filtros", exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll("tbody tr").length === 0 && document.querySelector(".empty-state"));
  await page.getByText("No hay fallos para estos filtros", { exact: true }).waitFor();
  assert.equal(await page.locator(".saas-data-table thead").count(), 1, "Empty results preserve the column headings");
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.screenshot({ path: captureDirectory + "/saas-failures-empty.png", fullPage: true });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "Empty state fits the desktop viewport");
  assert.deepEqual(errors, []);
  assert.ok(calls.every(c => c.method === "GET" || c.path === "/api/v1/auth/admin/login"), "Phase one does not mutate failure state");
  console.log("Failure diagnostics E2E: 50-row focus/return, pagination, pasted and edited store selections, real query filtering, diagnostics, clipboard outcomes and legacy payload passed.");
} finally {
  await browser?.close();
  server.kill();
}
