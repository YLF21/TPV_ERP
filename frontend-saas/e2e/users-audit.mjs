import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import { chromium } from "playwright";
import { emptySummary, summaryPath } from "./summary-fixture.mjs";

// All API traffic is intercepted: this suite never creates real users or modifies audit records.
const root = fileURLToPath(new URL("..", import.meta.url));
const base = "http://127.0.0.1:5221/";
const auditPath = "/api/v1/admin/audit/search";
const exportPath = "/api/v1/admin/audit/export";
const usersPath = "/api/v1/admin/users";
const token = "synthetic-users-audit-token";
const companyId = "10000000-0000-4000-8000-000000000001";
const staffPermissions = ["VIEW_ADMIN_DATA", "ADD_COMPANY", "EDIT_COMPANY_DATA", "MANAGE_ADMIN_USERS", "MANAGE_TENANT_USERS",
  "RENEW_LICENSE", "BLOCK_LICENSE", "UNBLOCK_LICENSE", "REGENERATE_PAIRING_CODE", "REVOKE_INSTALLATION", "VIEW_REPORTS",
  "MANAGE_SUPPORT_TICKETS", "MANAGE_BILLING", "MANAGE_ERP_MASTERS", "MANAGE_FISCAL_POLICY", "MANAGE_OPERATIONAL_INCIDENTS",
  "MANAGE_OPERATIONS", "MANAGE_INTEGRATIONS"];
const workbook = Buffer.from("PK\u0003\u0004synthetic-saas-audit-workbook", "latin1");
const audit = Array.from({ length: 121 }, (_, index) => ({
  id: `00000000-0000-4000-8000-${String(index).padStart(12, "0")}`,
  username: index % 2 ? "BOB" : "ALICE", action: index % 2 ? "RENEW_LICENSE" : "UPDATE_STORE",
  targetType: index % 2 ? "LICENSE" : "STORE", targetId: `TARGET-${String(index).padStart(3, "0")}`,
  details: `Registro de prueba ${index}`, createdAt: new Date(Date.UTC(2026, 10, 20, 0, index)).toISOString(),
})).reverse();

async function until(check, label) {
  for (let attempt = 0; attempt < 160; attempt++) {
    if (await check()) return;
    await delay(50);
  }
  throw new Error(`Timed out: ${label}`);
}

async function navigate(page, view) {
  await page.evaluate(view => { location.hash = `#/${view}`; }, view);
}

function matching(params) {
  const includes = (value, filter) => !filter || value.toLowerCase().includes(filter.toLowerCase());
  return audit.filter(row => (!params.from || row.createdAt.slice(0, 10) >= params.from)
    && (!params.to || row.createdAt.slice(0, 10) <= params.to)
    && includes(row.username, params.username) && includes(row.action, params.action)
    && includes(Object.values(row).join(" "), params.q))
    .sort((a, b) => ((a[params.sortBy || "createdAt"] || "").localeCompare(b[params.sortBy || "createdAt"] || "")
      || a.id.localeCompare(b.id)) * (params.sortDirection === "ASC" ? 1 : -1));
}

const server = spawn(process.execPath, [fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url)),
  "--host", "127.0.0.1", "--port", "5221", "--strictPort", "--configLoader", "runner"],
{ cwd: root, stdio: "ignore", windowsHide: true });
let browser;
try {
  await until(async () => {
    if (server.exitCode !== null) throw new Error("Users/audit test could not start Vite");
    try { return (await fetch(base)).ok; } catch { return false; }
  }, "Vite startup");
  browser = await chromium.launch({ headless: true });

  async function setup({ language = "es" } = {}) {
    const page = await browser.newPage({ acceptDownloads: true, viewport: { width: 1440, height: 900 }, timezoneId: "Asia/Shanghai" });
    page.setDefaultTimeout(10_000);
    await page.clock.setFixedTime(new Date("2026-11-20T12:00:00Z"));
    await page.addInitScript(language => localStorage.setItem("tpv-saas-language", language), language);
    const state = { errors: [], mutations: [], searches: [], exports: [], failSearch: false, failExport: false, exportFailureStatus: 503,
      users: [{ username: "ADMIN_TEST", active: true, createdAt: "2026-11-01T12:00:00Z" }] };
    page.on("pageerror", error => state.errors.push(error.message));
    await page.route("**/api/**", async route => {
      const request = route.request(), url = new URL(request.url()), path = url.pathname;
      const method = request.method(), params = Object.fromEntries(url.searchParams);
      const json = data => route.fulfill({ status: 200, contentType: "application/json", json: data });
      const error = detail => route.fulfill({ status: 503, contentType: "application/problem+json", json: { detail } });
      if (path === "/api/v1/auth/admin/login") return json({ username: "ADMIN_TEST", accessToken: token, mode: "admin",
        passwordChangeRequired: false, expiresAt: "2099-01-01T00:00:00Z" });
      if (path === "/api/v1/admin/me") return json({ username: "ADMIN_TEST",
        permissions: staffPermissions });
      assert.equal(request.headers().authorization, `Bearer ${token}`);
      if (path === usersPath && method === "POST") {
        const body = request.postDataJSON();
        state.mutations.push({ method, path, body });
        await delay(150);
        assert.equal(body.roleName, undefined, "Staff creation does not assign a role");
        const user = { username: body.username, active: true, createdAt: "2026-11-20T12:00:00Z" };
        state.users.push(user);
        return json(user);
      }
      if (method !== "GET") {
        state.errors.push(`Unexpected mutation ${method} ${path}`);
        return error("Unexpected mutation");
      }
      if (path === usersPath) return json(state.users);
      if (path === "/api/v1/admin/companies") return json([{ companyId, companyName: "Empresa de prueba usuarios",
        taxId: "B00000001", taxpayerType: "SOCIEDAD", commercialProfile: "MINORISTA", createdAt: "2026-11-01T12:00:00Z" }]);
      if (path === "/api/v1/admin/client-access-requests") return json({ items: [], total: 0 });
      if (path === "/api/v1/admin/tenant-users") return json({ items: [
        { id: companyId, username: "CLIENT_TEST", fullName: "Cliente de prueba", email: "client@example.test",
          active: true, companyCount: 0, storeCount: 0, createdAt: "2026-11-01T12:00:00Z" }], total: 1 });
      if (path === auditPath) {
        state.searches.push(params);
        if (state.failSearch) { state.failSearch = false; return error("Fallo de consulta sintético"); }
        const rows = matching(params), page = Number(params.page ?? 0), size = Number(params.size ?? 50);
        return json({ items: rows.slice(page * size, (page + 1) * size), page, size, total: rows.length });
      }
      if (path === exportPath) {
        state.exports.push(params);
        if (state.failExport) {
          state.failExport = false;
          return route.fulfill({ status: state.exportFailureStatus, contentType: "application/problem+json",
            json: { detail: "Fallo de exportación sintético" } });
        }
        return route.fulfill({ status: 200, contentType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          body: workbook, headers: { "Content-Disposition": 'attachment; filename="audit.xlsx"' } });
      }
      if (path === `${summaryPath}/options`) return json({ companies: [], stores: [] });
      if (path === summaryPath) return json(emptySummary);
      if (path === "/api/v1/admin/sync/sales-summary") return json({ documentCount: 0, total: "0.00" });
      if (path === "/api/v1/admin/reports/advanced") return json({ companies: 0, invoices: 0, invoicedTotal: 0,
        paidTotal: 0, salesDocuments: 0, salesTotal: 0, inventoryMovements: 0, integrations: 0, activeIntegrations: 0 });
      return json([]);
    });
    await page.goto(base, { waitUntil: "domcontentloaded", timeout: 30_000 });
    await page.locator('input[autocomplete="username"]').fill("ADMIN_TEST");
    await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
    await page.locator('form button[type="submit"]').click();
    await page.locator(".saas-dashboard").waitFor();
    return { page, state };
  }

  const { page, state } = await setup();
  await navigate(page, "users");
  const createForm = page.getByRole("form", { name: "Crear usuario SaaS", exact: true });
  await createForm.waitFor();
  assert.equal(await createForm.getByRole("combobox").count(), 0, "Staff has no role selector");
  assert.equal(await page.getByRole("form", { name: "Crear usuario cliente", exact: true }).count(), 0, "Client creation is separated");
  assert.equal(await page.getByRole("columnheader", { name: "Rol", exact: true }).count(), 0, "Staff list has no role column");
  const ownDeactivate = page.getByRole("row").filter({ hasText: "ADMIN_TEST" }).getByRole("button", { name: "Desactivar", exact: true });
  assert.equal(await ownDeactivate.isDisabled(), true, "Staff cannot deactivate their own account");
  assert.match(await ownDeactivate.getAttribute("title"), /propia cuenta/);
  await createForm.getByLabel("Usuario", { exact: true }).fill("NEW_STAFF");
  await createForm.getByLabel("Contraseña", { exact: true }).fill("synthetic-new-password");
  await createForm.locator('button[type="submit"]').click();
  await until(() => state.mutations.length === 1, "one create request");
  assert.equal(await createForm.locator('button[type="submit"]').isDisabled(), true);
  await page.getByRole("row").filter({ hasText: "NEW_STAFF" }).waitFor();
  assert.equal(await page.getByRole("row").filter({ hasText: "NEW_STAFF" }).getByRole("button", { name: "Desactivar", exact: true }).isEnabled(), true);
  assert.deepEqual(state.mutations[0].body, { username: "NEW_STAFF", password: "synthetic-new-password" });
  assert.equal(await createForm.getByLabel("Contraseña", { exact: true }).inputValue(), "");
  if (process.env.SAAS_USERS_AUDIT_SCREENSHOTS) {
    await mkdir(process.env.SAAS_USERS_AUDIT_SCREENSHOTS, { recursive: true });
    await page.screenshot({ path: join(process.env.SAAS_USERS_AUDIT_SCREENSHOTS, "users-es.png"), fullPage: true });
  }

  const clientsGroup = page.locator(".top-nav-list .nav-group").filter({
    has: page.locator(".nav-group-title").filter({ hasText: /^Clientes$/ }) });
  if (await clientsGroup.locator(".nav-group-toggle").getAttribute("aria-expanded") !== "true") await clientsGroup.locator(".nav-group-toggle").click();
  await clientsGroup.getByRole("button", { name: "Usuarios cliente", exact: true }).click();
  await page.getByRole("tab", { name: "Usuarios cliente", exact: true }).click();
  await page.getByRole("row").filter({ hasText: "CLIENT_TEST" }).waitFor();
  assert.match(page.url(), /#\/client-users\/users$/);
  assert.equal(await createForm.count(), 0, "Staff creation is absent from client accounts");
  assert.equal(await page.getByRole("row").filter({ hasText: "ADMIN_TEST" }).count(), 0, "Staff accounts are absent from client accounts");
  assert.equal(await page.getByRole("form", { name: "Crear usuario cliente", exact: true }).count(), 0,
    "New customers are created through reviewed requests");
  if (process.env.SAAS_USERS_AUDIT_SCREENSHOTS) {
    await page.screenshot({ path: join(process.env.SAAS_USERS_AUDIT_SCREENSHOTS, "client-users-es.png"), fullPage: true });
  }

  await navigate(page, "audit");
  const auditTable = page.locator("main table");
  const filters = page.getByRole("form", { name: "Filtros de auditoría", exact: true });
  const rowCount = () => auditTable.locator("tbody tr").count();
  await until(async () => await rowCount() === 50, "first audit page");
  assert.match(await auditTable.innerText(), /UTC/);
  assert.match(await auditTable.innerText(), /TARGET-120/);
  assert.equal(await auditTable.locator("tbody").getByRole("button").count(), 0, "Audit has no mutation actions");
  const headings = () => auditTable.locator("th").evaluateAll(nodes => nodes.map(node => node.dataset.columnKey));
  const header = key => auditTable.locator(`th[data-column-key="${key}"]`);
  const selectedRow = auditTable.locator('tbody tr[aria-selected="true"]');
  const initialColumns = await headings();
  assert.equal(initialColumns.includes("id"), false);
  assert.equal(await selectedRow.evaluate(row => getComputedStyle(row).fontWeight), "700", "Selected row is bold");
  await selectedRow.focus();
  await page.keyboard.press("ArrowDown");
  assert.match(await selectedRow.innerText(), /TARGET-119/);
  await page.keyboard.press("Enter");
  const detail = page.getByRole("dialog", { name: "Detalle de auditoría", exact: true });
  await detail.waitFor();
  assert.match(await detail.innerText(), /TARGET-119/);
  assert.match(await detail.innerText(), /ID de auditoría/);
  assert.equal(await detail.locator("input,textarea,select").count(), 0, "Entry details are read-only");
  assert.deepEqual(await detail.getByRole("button").allTextContents(), ["Cerrar"]);
  await page.keyboard.press("Escape");
  assert.equal(await selectedRow.evaluate(row => row === document.activeElement), true, "Closing restores row focus");
  await selectedRow.dblclick(); await detail.waitFor();
  await detail.getByRole("button", { name: "Cerrar", exact: true }).click();
  const viewport = page.locator(".audit-table-region .saas-data-table-scroll");
  assert.equal(await page.locator(".audit-footer").evaluate(node => node.getBoundingClientRect().bottom <= innerHeight), true,
    "Pagination remains visible without scrolling the page");
  const headerTop = await header("createdAt").evaluate(node => node.getBoundingClientRect().top);
  await selectedRow.focus(); await page.keyboard.press("End");
  assert.equal(await viewport.evaluate(node => node.scrollTop > 0), true, "Keyboard selection scrolls inside the table");
  assert.equal(await header("createdAt").evaluate(node => node.getBoundingClientRect().top), headerTop, "Headers stay fixed while scrolling");
  await page.keyboard.press("Home");

  await page.mouse.move(5, 5);
  assert.equal(await header("username").locator(".saas-layout-menu-trigger").evaluate(node => getComputedStyle(node).opacity), "0");
  await header("username").hover();
  assert.equal(await header("username").locator(".saas-layout-sort-indicator").evaluate(node => getComputedStyle(node).opacity), "1");
  await header("username").getByRole("button", { name: "Opciones de columna Usuario", exact: true }).click();
  const columnMenu = page.getByRole("menu", { name: "Opciones de columna Usuario", exact: true });
  await columnMenu.getByRole("menuitemcheckbox", { name: "Tipo de destino", exact: true }).click();
  assert.equal((await headings()).includes("targetType"), false);
  await columnMenu.getByRole("menuitemcheckbox", { name: "ID de auditoría", exact: true }).click();
  assert.equal((await headings()).includes("id"), true);
  await page.keyboard.press("Escape");
  await header("username").dragTo(header("createdAt"));
  assert.equal((await headings())[0], "username", "Columns can be dragged");
  const resize = header("username").getByRole("button", { name: "Cambiar ancho de Usuario", exact: true });
  const widthBefore = await header("username").evaluate(node => node.getBoundingClientRect().width);
  await resize.focus(); await resize.press("ArrowRight");
  assert.equal(await header("username").evaluate(node => node.getBoundingClientRect().width), widthBefore + 8);
  await navigate(page, "users"); await createForm.waitFor();
  await navigate(page, "audit"); await until(async () => await rowCount() === 50, "restored audit view");
  assert.equal((await headings())[0], "username", "Column order persists when reopening Audit");
  assert.equal((await headings()).includes("targetType"), false, "Hidden columns persist");
  assert.equal(await header("username").evaluate(node => node.getBoundingClientRect().width), widthBefore + 8, "Widths persist");
  await header("username").focus(); await page.keyboard.press("Control+ArrowRight");
  assert.equal((await headings())[1], "username", "Keyboard column reordering is available");
  await header("username").getByRole("button", { name: "Opciones de columna Usuario", exact: true }).click();
  await columnMenu.getByRole("menuitem", { name: "Restablecer columnas", exact: true }).click();
  assert.deepEqual(await headings(), initialColumns);

  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await until(() => state.searches.at(-1)?.page === "1", "second audit page");
  await until(async () => (await auditTable.innerText()).includes("TARGET-070"), "second page rows");
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await until(async () => await rowCount() === 21, "history beyond the old 100-row limit");
  await header("username").getByRole("button", { name: "Ordenar por Usuario", exact: true }).click();
  await until(() => state.searches.at(-1)?.sortBy === "username" && state.searches.at(-1)?.page === "0", "global sorting resets pagination");
  await until(async () => (await auditTable.locator("tbody tr").first().innerText()).includes("TARGET-000"), "username sort includes records outside the previous page");
  assert.equal(await header("username").getAttribute("aria-sort"), "ascending");
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await until(async () => (await auditTable.locator("tbody tr").first().innerText()).includes("TARGET-100"), "sorting is consistent across pages");

  await filters.getByLabel("Usuario", { exact: true }).fill("bob");
  assert.equal(await page.getByRole("button", { name: "Exportar Excel", exact: true }).isDisabled(), true,
    "Pending filter edits cannot export a different scope than displayed");
  await filters.getByLabel("Acción", { exact: true }).fill("renew");
  await filters.getByLabel("Texto", { exact: true }).fill("TARGET-119");
  await filters.getByLabel("Desde (UTC)", { exact: true }).click();
  const calendar = page.getByRole("dialog", { name: "Desde (UTC) – Hasta (UTC)", exact: true });
  await calendar.locator('[data-date="2026-11-20"]').click();
  await calendar.locator('[data-date="2026-11-20"]').click();
  await calendar.getByRole("button", { name: "Aceptar", exact: true }).click();
  await filters.getByRole("button", { name: "Aplicar filtros", exact: true }).click();
  await until(async () => await rowCount() === 1 && (await auditTable.innerText()).includes("TARGET-119"), "combined filters");
  assert.equal(state.searches.at(-1).page, "0", "Filtering resets pagination");
  const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "Exportar Excel", exact: true }).click()]);
  assert.match(download.suggestedFilename(), /\.xlsx$/);
  assert.deepEqual(await readFile(await download.path()), workbook);
  assert.equal(state.exports.at(-1).username, "bob");
  assert.equal(state.exports.at(-1).action, "renew");
  assert.equal(state.exports.at(-1).q, "TARGET-119");
  assert.equal(state.exports.at(-1).from, "2026-11-20");
  assert.equal(state.exports.at(-1).to, "2026-11-20");
  assert.equal(state.exports.at(-1).page, undefined, "Export covers all matching pages");
  assert.equal(state.exports.at(-1).language, "es");
  assert.equal(state.exports.at(-1).sortBy, "username");
  assert.equal(state.exports.at(-1).sortDirection, "ASC", "Excel retains global table order");
  await download.delete();

  state.failExport = true;
  await page.getByRole("button", { name: "Exportar Excel", exact: true }).click();
  await page.getByRole("alert").filter({ hasText: "No se pudo exportar la auditoría" }).waitFor();
  assert.equal(await page.getByRole("button", { name: "Exportar Excel", exact: true }).isEnabled(), true);
  state.failExport = true; state.exportFailureStatus = 413;
  await page.getByRole("button", { name: "Exportar Excel", exact: true }).click();
  await page.getByRole("alert").filter({ hasText: /50[.,]000/ }).waitFor();
  await filters.getByLabel("Texto", { exact: true }).fill("NO-SUCH-RECORD");
  await filters.getByRole("button", { name: "Aplicar filtros", exact: true }).click();
  await until(() => state.searches.at(-1)?.q === "NO-SUCH-RECORD", "empty filter request");
  await until(async () => !(await auditTable.innerText()).includes("TARGET-119"), "empty state replaces old data");
  assert.ok(await auditTable.getByRole("columnheader").count() >= 5, "Empty audit retains column headings");

  state.failSearch = true;
  await filters.getByRole("button", { name: "Limpiar filtros", exact: true }).click();
  await page.getByRole("alert").filter({ hasText: "No se pudo consultar la auditoría" }).waitFor();
  assert.equal(await page.getByRole("button", { name: "Exportar Excel", exact: true }).isDisabled(), true);
  await page.getByRole("button", { name: /Reintentar/ }).click();
  await until(async () => await rowCount() === 50, "query retry");
  assert.equal(state.mutations.length, 1, "Audit browsing, filtering and exporting never mutate data");
  assert.deepEqual(state.errors, []);
  if (process.env.SAAS_USERS_AUDIT_SCREENSHOTS) {
    await mkdir(process.env.SAAS_USERS_AUDIT_SCREENSHOTS, { recursive: true });
    await page.screenshot({ path: join(process.env.SAAS_USERS_AUDIT_SCREENSHOTS, "audit-es.png"), fullPage: true });
  }
  await page.close();

  for (const [language, exportLabel] of [["es", "Exportar Excel"], ["en", "Export Excel"], ["zh", "导出 Excel"]]) {
    const { page, state } = await setup({ language });
    await navigate(page, "users");
    await page.getByRole("row").filter({ hasText: "ADMIN_TEST" }).waitFor();
    assert.equal(await page.locator('main form input[type="password"]').count(), 1, "Each staff account can create another staff account");
    assert.equal(await page.locator('main form select').count(), 0, "Staff role selector is absent in every language");
    await navigate(page, "audit");
    await until(() => state.searches.length > 0, "reader audit request");
    await until(async () => await page.locator("main table tbody tr").count() === 50, "reader audit rows");
    const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: exportLabel, exact: true }).click()]);
    assert.equal(state.exports.at(-1).language, language);
    await download.delete();
    assert.deepEqual(state.mutations, []);
    assert.deepEqual(state.errors, []);
    await page.close();
  }
  console.log("Users/audit E2E passed: separate staff/client screens, role-free staff creation, UTC, 121 records, global sorting, columns, keyboard/selection, read-only detail, filters, XLSX and ES/EN/ZH.");
} finally {
  if (browser) await browser.close();
  server.kill();
}
