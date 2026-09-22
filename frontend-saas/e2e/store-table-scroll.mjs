import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const playwright = await import("playwright").catch(() => import("../../frontend/node_modules/playwright/index.mjs"));
const root = fileURLToPath(new URL("..", import.meta.url));
const output = fileURLToPath(new URL("../../output/playwright/", import.meta.url));
const baseUrl = "http://127.0.0.1:5192/";
const server = spawn(process.execPath, [fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url)), "--host", "127.0.0.1", "--port", "5192", "--strictPort", "--configLoader", "runner"], { cwd: root, stdio: "ignore", windowsHide: true });
const messages = [];
const report = message => { messages.push(message); console.log(message); };
let browser;
try {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error("Store table test could not start Vite on 5192");
    try { if ((await fetch(baseUrl)).ok) break; } catch { /* bounded startup retry */ }
    if (attempt === 99) throw new Error("Store table test startup timed out");
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  browser = await playwright.chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  const browserErrors = [];
  page.on("pageerror", error => browserErrors.push(error.message));
  const calls = [];
  const permissions = ["VIEW_ADMIN_DATA", "ADD_COMPANY", "EDIT_COMPANY_DATA", "RENEW_LICENSE"];
  const address = { linea1: "Calle de Prueba 1", ciudad: "Las Palmas", codigoPostal: "35001", provincia: "Las Palmas", pais: "ES" };
  const companies = ["a", "b"].map((key, index) => ({ companyId: `company-${key}`, companyName: `Sociedad de prueba ${key.toUpperCase()}`,
    taxId: `B0000000${index + 1}`, taxpayerType: "SOCIEDAD", companyAddress: address, owners: [], createdAt: "2026-09-20T08:00:00Z" }));
  const stores = Array.from({ length: 82 }, (_, index) => ({
    id: `store-${index + 1}`, companyId: companies[index < 41 ? 0 : 1].companyId, companyName: companies[index < 41 ? 0 : 1].companyName,
    code: String(index + 1).padStart(3, "0"), internalCode: String(3500001 + index), name: `${index < 2 ? "Tienda pareja" : "Tienda demostración"} ${String(index + 1).padStart(3, "0")}`,
    active: index % 2 === 1, storeAddress: { ...address }, timeZoneId: "Atlantic/Canary", installations: 0, activeInstallations: 0,
    lastSyncAt: null, createdAt: "2026-09-20T08:00:00Z", taxRegime: "IGIC", commercialProfile: "MINORISTA", taxRegimeLocked: false,
    servicePrice: "29.90", billingPeriod: "MONTHLY", maxWindows: 1, maxPda: 0, validUntil: "2099-11-15T10:11:12.123Z",
  }));
  let failNext = true;
  let holdNext = false;
  let overlapNext = false;
  let failProfile = true;
  let failSave = true;
  let saveStarted; const saveRequested = new Promise(resolve => { saveStarted = resolve; });
  let releaseSave; const saveReleased = new Promise(resolve => { releaseSave = resolve; });
  let holdDashboard = false;
  let dashboardStarted; const dashboardRequested = new Promise(resolve => { dashboardStarted = resolve; });
  let releaseDashboard; const dashboardReleased = new Promise(resolve => { releaseDashboard = resolve; });
  let heldStarted; const heldRequested = new Promise(resolve => { heldStarted = resolve; });
  let releaseHeld; const heldReleased = new Promise(resolve => { releaseHeld = resolve; });
  let heldFinished; const heldDone = new Promise(resolve => { heldFinished = resolve; });
  await page.route("**/api/**", async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname; const method = request.method();
    const body = request.postData() ? JSON.parse(request.postData()) : null;
    const params = Object.fromEntries(url.searchParams);
    calls.push({ path, method, body, params });
    const json = value => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/v1/auth/admin/login") return json({ username: "STORES_SCROLL_DEMO", accessToken: "synthetic-token", mode: "admin", passwordChangeRequired: false, expiresAt: "2099-01-01T00:00:00Z" });
    if (path === "/api/v1/admin/me") return json({ username: "STORES_SCROLL_DEMO", permissions });
    if (path === "/api/v1/admin/companies") return json(companies);
    if (path === "/api/v1/admin/licenses") {
      if (holdDashboard) { holdDashboard = false; dashboardStarted(); await dashboardReleased; }
      return json([]);
    }
    if (path === "/api/v1/admin/sync/sales-summary") return json({ documentCount: 0, total: "0.00" });
    if (path === "/api/v1/admin/reports/advanced") return json({ companies: 2, invoices: 0, invoicedTotal: "0.00", paidTotal: "0.00", salesDocuments: 0, salesTotal: "0.00", inventoryMovements: 0, integrations: 0, activeIntegrations: 0 });
    if (path === "/api/v1/admin/stores" && method === "GET") {
      const pageNumber = Number(params.page); const size = Number(params.size);
      assert.equal(size, 25, "Store listing must retain the backend page size of 25");
      const q = (params.q ?? "").toLocaleLowerCase("es-ES");
      const sortKeys = ["internalCode", "companyName", "code", "name", "active", "taxRegime", "commercialProfile", "servicePrice", "billingPeriod", "validUntil", "maxWindows", "maxPda", "activeInstallations", "lastSyncAt"];
      assert.ok(sortKeys.includes(params.sortBy), `Unknown store sort: ${params.sortBy}`);
      assert.ok(["ASC", "DESC"].includes(params.sortDirection));
      const direction = params.sortDirection === "DESC" ? -1 : 1;
      const rows = stores.filter(store => (!params.companyId || store.companyId === params.companyId)
        && (!params.active || String(store.active) === params.active)
        && (!q || [store.name, store.code, store.internalCode, store.companyName].some(value => value.toLocaleLowerCase("es-ES").includes(q))))
        .sort((a, b) => (String(a[params.sortBy] ?? "").localeCompare(String(b[params.sortBy] ?? ""), "es-ES", { numeric: true })
          || a.id.localeCompare(b.id)) * direction);
      const result = structuredClone({ items: rows.slice(pageNumber * size, (pageNumber + 1) * size), page: pageNumber, size, total: rows.length, totalPages: Math.ceil(rows.length / size) });
      if (pageNumber === 1 && !params.companyId && !params.active && !params.q) {
        if (failNext) {
          failNext = false;
          return route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Synthetic next-page failure" }) });
        }
        if (holdNext) { holdNext = false; heldStarted(); await heldReleased; await json(result); heldFinished(); return; }
        if (overlapNext) {
          overlapNext = false;
          // Simulate an offset page shifted by concurrent changes: still 25 rows,
          // but the first ID overlaps the preceding page.
          result.items = structuredClone([rows[24], ...rows.slice(25, 49)]);
        }
      }
      return json(result);
    }
    if (/^\/api\/v1\/admin\/stores\/store-\d+$/.test(path)) {
      const row = stores.find(store => store.id === path.split("/").at(-1));
      assert.ok(row);
      if (method === "GET") {
        if (failProfile) return route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Synthetic store profile failure" }) });
        return json(row);
      }
      if (method === "PUT") {
        if (failSave) {
          saveStarted(); await saveReleased; failSave = false;
          return route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Synthetic store save failure" }) });
        }
        Object.assign(row, body); return json(row);
      }
    }
    if (method !== "GET") throw new Error(`Unexpected mutation: ${method} ${path}`);
    return json([]);
  });

  await page.goto(baseUrl);
  await page.locator('input[autocomplete="username"]').fill("STORES_SCROLL_DEMO");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  await page.locator(".saas-dashboard").waitFor();
  await page.locator(".top-nav-list").getByRole("button", { name: "Tiendas", exact: true }).click();
  const workspace = page.locator(".stores-workspace");
  const toolbar = workspace.getByRole("search", { name: "Tiendas", exact: true });
  const table = workspace.getByRole("table", { name: "Tiendas", exact: true });
  const scroll = workspace.locator(".stores-table-scroll");
  const search = toolbar.getByLabel("Buscar empresa, tienda o referencia", { exact: true });
  const company = toolbar.getByRole("combobox", { name: "Empresa", exact: true });
  const active = toolbar.getByLabel("Estado", { exact: true });
  const create = workspace.getByRole("button", { name: "Crear", exact: true });
  const applied = toolbar.getByRole("group", { name: "Filtros aplicados", exact: true });
  const chip = label => applied.getByRole("button", { name: `Quitar filtro ${label}`, exact: true });
  const rows = table.locator("tbody tr");
  const storeCalls = () => calls.filter(call => call.path === "/api/v1/admin/stores");
  const waitRows = count => page.waitForFunction(expected => document.querySelectorAll('.stores-table-scroll table tbody tr').length === expected, count);
  const settle = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  const bottom = () => scroll.evaluate(element => { element.scrollTop = element.scrollHeight; });
  const codes = () => rows.locator('td[data-column-key="internalCode"]').allTextContents();
  const chooseCompany = async (query, expected, keyboard = false) => {
    const before = storeCalls().length;
    await company.fill(query); await settle();
    assert.equal(storeCalls().length, before, "Searching company options must not change the applied server filter");
    const option = toolbar.getByRole("option", { name: new RegExp(`${expected.companyName}.*${expected.taxId}`) });
    await option.waitFor();
    if (keyboard) {
      const id = await option.getAttribute("id");
      for (let index = 0; await company.getAttribute("aria-activedescendant") !== id && index < await toolbar.getByRole("option").count(); index++) await company.press("ArrowDown");
      assert.equal(await company.getAttribute("aria-activedescendant"), id);
      await company.press("Enter");
    } else await option.click();
    assert.equal(await company.inputValue(), `${expected.companyName} · ${expected.taxId}`);
  };
  const filter = async (action, expectedCount, expectedParams) => {
    const start = storeCalls().length;
    await action(); await waitRows(expectedCount); await settle();
    const requests = storeCalls().slice(start);
    assert.ok(requests.length > 0, "Changing a filter must request fresh server data");
    assert.equal(requests[0].params.page, "0", "A changed filter must start again at page zero");
    for (const [key, value] of Object.entries(expectedParams)) assert.equal(requests[0].params[key] ?? "", value);
  };
  await waitRows(25); await settle();
  assert.equal(await active.count(), 0, "Activity is an advanced filter, initially collapsed");
  assert.equal(await applied.count(), 0, "The unfiltered store directory has no applied filter chips");
  assert.equal(await workspace.getByRole("button", { name: "Columnas", exact: true }).count(), 0);
  assert.ok(storeCalls().length > 0 && storeCalls().every(call => call.params.page === "0"), "Initial entry must not eagerly fetch all store pages");
  const initialRequests = storeCalls().length; // React StrictMode may replay the initial effect in Vite development.
  assert.equal(await workspace.locator(".pagination-controls").count(), 0);
  const initialCodes = await codes();
  await bottom();
  await workspace.locator(".retry-error").waitFor(); await settle();
  assert.deepEqual(await codes(), initialCodes, "A failed later page must preserve the existing rows");
  assert.deepEqual(storeCalls().slice(initialRequests).map(call => call.params.page), ["1"], "A next-page error must wait for an explicit retry");
  await workspace.getByRole("button", { name: "Reintentar", exact: true }).click();
  await waitRows(50);
  assert.deepEqual(storeCalls().slice(initialRequests).map(call => call.params.page), ["1", "1"], "Retry must repeat the failed page without resetting the directory");
  assert.deepEqual((await codes()).slice(0, 25), initialCodes);
  report("Store scrolling E2E passed: initial page only, automatic next page, retained rows on error and explicit retry of the failed page.");

  await mkdir(output, { recursive: true });
  for (const viewport of [{ width: 1280, height: 900 }, { width: 1600, height: 1000 }, { width: 1366, height: 768 }]) {
    await page.setViewportSize(viewport); await settle();
    for (let attempt = 0; await rows.count() < 82 && attempt < 4; attempt++) {
      const count = await rows.count(); await bottom();
      await page.waitForFunction(previous => document.querySelectorAll('.stores-table-scroll table tbody tr').length > previous, count);
    }
    assert.equal(await rows.count(), 82);
    assert.equal(new Set(await codes()).size, 82, "Concatenated pages must not duplicate stores");
    await scroll.evaluate(element => { element.scrollTop = 0; element.scrollLeft = 0; }); await settle();
    const before = await measure(page);
    assertListingGeometry(before, viewport);
    const headerBefore = await table.locator("thead th").first().boundingBox();
    const controlsBefore = await toolbar.boundingBox();
    await bottom(); await settle();
    const after = await measure(page);
    assertListingGeometry(after, viewport);
    const headerAfter = await table.locator("thead th").first().boundingBox();
    assert.ok(Math.abs(headerBefore.y - headerAfter.y) <= 1, "Store column headings must remain fixed during internal scrolling");
    assert.deepEqual(await toolbar.boundingBox(), controlsBefore, "Filters and creation must not move with the rows");
    const last = await rows.last().boundingBox();
    assert.ok(last.y >= after.scroll.top && last.y + last.height <= after.scroll.bottom + 1, "The final store must be visible inside the table viewport");
    await assertWithin(create, viewport);
    await page.mouse.move(after.scroll.left + 100, after.scroll.bottom - 40); await page.mouse.wheel(0, 1200); await settle();
    assertListingGeometry(await measure(page), viewport);
    await page.screenshot({ path: `${output}/saas-stores-scroll-${viewport.width}x${viewport.height}.png`, animations: "disabled" });

    await filter(() => search.fill("pareja"), 2, { q: "pareja" });
    const few = await measure(page); assertListingGeometry(few, viewport);
    assert.ok(Math.abs(few.scroll.bottom - before.scroll.bottom) <= 2, "Two store rows must retain the full table viewport");
    await page.screenshot({ path: `${output}/saas-stores-few-${viewport.width}x${viewport.height}.png`, animations: "disabled" });
    await filter(() => search.fill("NO-STORE-FOUND"), 0, { q: "NO-STORE-FOUND" });
    await workspace.getByText("SIN DATOS", { exact: true }).waitFor();
    assertListingGeometry(await measure(page), viewport, true);
    await page.screenshot({ path: `${output}/saas-stores-empty-${viewport.width}x${viewport.height}.png`, animations: "disabled" });
    await filter(() => search.fill(""), 25, { q: "" });
  }
  report("Store scrolling E2E passed: 82/2/0 rows, stable controls and sticky headings, full available size and no outer scrolling at 1280x900, 1600x1000 and 1366x768.");

  overlapNext = true; await bottom(); await waitRows(49);
  assert.equal(new Set(await codes()).size, 49, "An overlapping ID on the following page must update the existing row without duplication");
  assert.equal(await table.locator('tr[data-row-id="store-25"]').count(), 1);
  report("Store scrolling E2E passed: overlapping IDs across 25-row pages are merged without duplicate table rows.");

  await table.getByRole("button", { name: "Ordenar por Código interno", exact: true }).click();
  await waitRows(25); await settle();
  assert.equal(storeCalls().at(-1).params.sortDirection, "ASC");
  assert.equal((await codes())[0], "3500001");
  const sortStart = storeCalls().length;
  await table.getByRole("button", { name: "Ordenar por Código interno", exact: true }).click();
  await waitRows(25); await settle();
  assert.equal(storeCalls()[sortStart].params.page, "0");
  assert.equal(storeCalls()[sortStart].params.sortBy, "internalCode");
  assert.equal(storeCalls()[sortStart].params.sortDirection, "DESC");
  assert.equal((await codes())[0], "3500082", "Sorting must include stores outside the pages previously loaded");
  for (let attempt = 0; await rows.count() < 82 && attempt < 4; attempt++) {
    const count = await rows.count(); await bottom();
    await page.waitForFunction(previous => document.querySelectorAll('.stores-table-scroll table tbody tr').length > previous, count);
  }
  assert.deepEqual(await codes(), stores.map(store => store.internalCode).reverse(), "Every server page must use the same global descending order");
  assert.ok(storeCalls().slice(sortStart).every(call => call.params.sortBy === "internalCode" && call.params.sortDirection === "DESC"));
  await scroll.evaluate(element => { element.scrollTop = 0; element.scrollLeft = 0; });
  const headers = () => table.locator("thead th").evaluateAll(elements => elements.map(element => element.dataset.columnKey));
  const statusHeading = table.locator('th[data-column-key="active"]');
  await statusHeading.focus(); await page.keyboard.press("Control+ArrowLeft");
  assert.ok((await headers()).indexOf("active") < (await headers()).indexOf("name"));
  await table.getByRole("button", { name: "Opciones de columna Estado", exact: true }).click();
  await page.getByRole("menuitemcheckbox", { name: "Impuestos", exact: true }).click();
  await page.keyboard.press("Escape");
  assert.equal((await headers()).includes("taxRegime"), false);
  const originalWidth = (await statusHeading.boundingBox()).width;
  await statusHeading.getByRole("button", { name: "Cambiar ancho de Estado", exact: true }).focus(); await page.keyboard.press("ArrowRight");
  const resizedWidth = (await statusHeading.boundingBox()).width;
  assert.ok(resizedWidth > originalWidth);
  await table.locator('th[data-column-key="name"]').dragTo(table.locator('th[data-column-key="internalCode"]'));
  assert.equal((await headers())[0], "name");
  const savedHeaders = await headers();
  await page.reload();
  await page.locator('input[autocomplete="username"]').fill("STORES_SCROLL_DEMO");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click(); await page.locator(".top-nav-list").waitFor();
  await page.locator(".top-nav-list").getByRole("button", { name: "Tiendas", exact: true }).click();
  await waitRows(25);
  assert.deepEqual(await headers(), savedHeaders, "Column order and visibility must survive a browser reload");
  assert.equal((await statusHeading.boundingBox()).width, resizedWidth, "Column width must survive a browser reload");
  const preferences = await page.evaluate(() => Object.entries(localStorage).filter(([key]) => key.includes(":table:stores:")));
  assert.equal(preferences.length, 1); assert.match(preferences[0][0], /stores_scroll_demo/);
  assert.equal(preferences[0][1].includes("synthetic-token"), false);
  await table.getByRole("button", { name: "Opciones de columna Estado", exact: true }).click();
  await page.getByRole("menuitem", { name: "Restablecer columnas", exact: true }).click();
  report("Store table E2E passed: global server sorting across 82 rows, keyboard move, drag reorder, hide, resize and per-account column persistence.");

  assert.equal(await company.evaluate(element => element.required), false, "Company filtering must remain optional");
  await filter(() => chooseCompany("sociedad de prueba a", companies[0], true), 25, { companyId: "company-a" });
  await bottom(); await waitRows(41);
  const selectedCompanyLabel = await company.inputValue();
  const beforeCancel = storeCalls().length;
  for (const cancellation of ["Escape", "Tab", "outside"]) {
    await company.fill("B00000002");
    await toolbar.getByRole("option", { name: /Sociedad de prueba B.*B00000002/ }).waitFor();
    assert.match(await chip("Empresa").locator("..").innerText(), /Sociedad de prueba A/);
    if (cancellation === "outside") await search.click(); else await company.press(cancellation);
    await settle();
    assert.equal(await company.inputValue(), selectedCompanyLabel, `${cancellation} must restore the applied company label`);
    assert.equal(await company.getAttribute("aria-expanded"), "false");
    assert.equal(storeCalls().length, beforeCancel, "Cancelling company search must preserve the applied filter and loaded pages");
    assert.equal(await rows.count(), 41);
  }
  await toolbar.getByRole("button", { name: "+ Más filtros", exact: true }).click();
  await filter(() => active.selectOption("false"), 21, { companyId: "company-a", active: "false" });
  assert.ok((await rows.locator('td[data-column-key="companyName"]').allTextContents()).every(name => name === companies[0].companyName));
  assert.ok((await rows.locator('td[data-column-key="active"]').allTextContents()).every(status => status === "Inactiva"));
  await filter(() => search.fill("pareja"), 1, { companyId: "company-a", active: "false", q: "pareja" });
  assert.equal(await applied.getByRole("button", { name: /^Quitar filtro / }).count(), 3);
  assert.match(await chip("Empresa").locator("..").innerText(), /Sociedad de prueba A/);
  assert.match(await chip("Estado").locator("..").innerText(), /Inactiva/);
  const beforeDisclosure = storeCalls().length;
  await toolbar.getByRole("button", { name: "− Menos filtros", exact: true }).click(); await settle();
  assert.equal(await active.count(), 0);
  assert.equal(await chip("Estado").isVisible(), true, "Collapsed advanced filters remain represented by chips");
  assert.equal(storeCalls().length, beforeDisclosure, "Collapsing filters must not change the server query");
  for (const viewport of [{ width: 1280, height: 900 }, { width: 1600, height: 1000 }]) {
    await page.setViewportSize(viewport); await settle();
    assertListingGeometry(await measure(page), viewport);
    await page.screenshot({ path: `${output}/saas-stores-filters-${viewport.width}.png`, animations: "disabled" });
    await company.click(); await toolbar.getByRole("listbox", { name: "Empresa", exact: true }).waitFor();
    await page.screenshot({ path: `${output}/saas-stores-company-picker-${viewport.width}.png`, animations: "disabled" });
    await company.press("Escape");
  }
  await page.setViewportSize({ width: 1366, height: 768 }); await settle();
  await filter(() => chip("Buscar empresa, tienda o referencia").click(), 21, { companyId: "company-a", active: "false", q: "" });
  await toolbar.getByRole("button", { name: "+ Más filtros", exact: true }).click();
  assert.equal(await active.inputValue(), "false", "Expanding filters must preserve the selected activity");
  await filter(() => chip("Estado").click(), 25, { companyId: "company-a", active: "", q: "" });
  await filter(() => chooseCompany("B00000002", companies[1]), 25, { companyId: "company-b" });
  assert.ok((await rows.locator('td[data-column-key="companyName"]').allTextContents()).every(name => name === companies[1].companyName));
  await filter(() => chip("Empresa").click(), 25, { companyId: "", active: "", q: "" });
  assert.equal(await applied.count(), 0);
  await filter(() => chooseCompany("B00000001", companies[0]), 25, { companyId: "company-a" });
  await filter(() => active.selectOption("false"), 21, { active: "false" });
  await filter(() => search.fill("pareja"), 1, { q: "pareja" });
  await filter(() => applied.getByRole("button", { name: "Limpiar todos", exact: true }).click(), 25, { companyId: "", active: "", q: "" });
  assert.equal(await applied.count(), 0);
  assert.equal(await search.inputValue(), ""); assert.equal(await company.inputValue(), ""); assert.equal(await active.inputValue(), "");
  await filter(() => chooseCompany("sociedad de prueba a", companies[0]), 25, { companyId: "company-a" });
  await filter(async () => {
    await company.click(); await toolbar.getByRole("listbox", { name: "Empresa", exact: true }).getByRole("option", { name: "Todas", exact: true }).click();
  }, 25, { companyId: "", active: "", q: "" });
  assert.equal(await applied.count(), 0, "The Todas option must remove the company chip");
  await toolbar.getByRole("button", { name: "− Menos filtros", exact: true }).click();
  holdNext = true; await bottom(); await heldRequested;
  await filter(() => search.fill("pareja"), 2, { q: "pareja" });
  const latestCodes = await codes(); releaseHeld(); await heldDone; await settle();
  assert.deepEqual(await codes(), latestCodes, "A delayed page from the old filter must never append to the new results");
  report("Store filters E2E passed: optional company name/NIF picker with keyboard selection, Escape/Tab/outside cancellation preserving the applied company, Todas/chips/clear-all, server-side AND queries and page-zero resets, advanced disclosure preserving state, stable 1280/1600 layouts and delayed-page isolation.");

  assert.equal(await table.getByRole("button", { name: /^(Editar|Activar|Desactivar)$/ }).count(), 0, "Rows open their modal instead of containing mutation buttons");
  const modal = page.locator("dialog.saas-store-dialog");
  await create.click();
  const newForm = modal.getByRole("form", { name: "Crear", exact: true });
  await verifyModalForm(page, modal, newForm, { width: 1366, height: 768 });
  await newForm.getByRole("button", { name: "Cancelar", exact: true }).click();
  await modal.waitFor({ state: "hidden" });
  assert.equal(await create.evaluate(element => document.activeElement === element), true);
  assertListingGeometry(await measure(page), { width: 1366, height: 768 });
  holdDashboard = true;
  await page.getByRole("button", { name: "Actualizar", exact: true }).click();
  await dashboardRequested;
  stores[0].name = "Tienda pareja ficha actual";
  await rows.first().dblclick();
  await modal.locator(".retry-error").waitFor();
  assert.equal(await modal.getByRole("form").count(), 0, "A profile failure must not expose a form based on stale table data");
  failProfile = false;
  await modal.getByRole("button", { name: "Reintentar", exact: true }).click();
  const editForm = modal.getByRole("form", { name: "Editar", exact: true });
  await editForm.waitFor();
  assert.equal(await editForm.getByLabel("Nombre", { exact: true }).inputValue(), "Tienda pareja ficha actual", "The editor must use a fresh store GET");
  await verifyModalForm(page, modal, editForm, { width: 1366, height: 768 });
  await editForm.getByLabel("Nombre", { exact: true }).fill("Tienda pareja editada");
  assert.equal(await editForm.locator('button[type="submit"]').count(), 1);
  await editForm.getByRole("button", { name: "Guardar", exact: true }).click();
  await saveRequested;
  await page.keyboard.press("Escape");
  assert.equal(await modal.isVisible(), true, "Escape must not close a pending save");
  assert.equal(await modal.getByRole("button", { name: "Cerrar", exact: true }).isDisabled(), true);
  assert.equal(await editForm.getByRole("button", { name: "Guardar", exact: true }).isDisabled(), true);
  const profileReads = calls.filter(call => call.path === "/api/v1/admin/stores/store-1" && call.method === "GET").length;
  const refreshedDirectory = page.waitForResponse(response => {
    const url = new URL(response.url());
    return url.pathname === "/api/v1/admin/stores" && url.searchParams.get("page") === "0" && url.searchParams.get("q") === "pareja";
  });
  releaseDashboard(); await refreshedDirectory; await settle();
  assert.equal(await editForm.getByLabel("Nombre", { exact: true }).inputValue(), "Tienda pareja editada",
    "Completing an earlier global refresh must not discard the open store draft");
  assert.equal(await editForm.getByRole("button", { name: "Guardar", exact: true }).isDisabled(), true,
    "A global refresh must retain the state of the in-flight save");
  assert.equal(calls.filter(call => call.path === "/api/v1/admin/stores/store-1" && call.method === "GET").length, profileReads,
    "An open store editor must not reload its profile because the global directory refreshed");
  releaseSave();
  await modal.getByRole("alert").waitFor();
  assert.equal(await editForm.getByLabel("Nombre", { exact: true }).inputValue(), "Tienda pareja editada", "A failed save must retain the draft");
  await editForm.getByRole("button", { name: "Guardar", exact: true }).click();
  await modal.waitFor({ state: "hidden" }); await waitRows(2);
  assert.ok((await rows.locator('td[data-column-key="name"]').allTextContents()).some(name => name.includes("Tienda pareja editada")));
  assertListingGeometry(await measure(page), { width: 1366, height: 768 });
  const mutations = calls.filter(call => call.method !== "GET" && call.path !== "/api/v1/auth/admin/login");
  assert.equal(mutations.length, 2); assert.ok(mutations.every(call => call.method === "PUT"));
  assert.equal(mutations[1].body.validUntil, "2099-11-15T10:11:12.123Z");
  await rows.first().focus(); await page.keyboard.press("Enter"); await editForm.waitFor();
  await page.screenshot({ path: `${output}/saas-store-detail-1366x768.png`, animations: "disabled" });
  await page.keyboard.press("Escape"); await modal.waitFor({ state: "hidden" });
  assert.equal(await rows.first().evaluate(element => document.activeElement === element), true, "Closing the store modal must restore row focus");
  report("Store modal E2E passed: double-click/Enter, fresh profile and retry, single save, pending Escape guard, draft/in-flight save retained across delayed global refresh, failed-save recovery, calendar access and focus restoration.");

  permissions.splice(0, permissions.length, "VIEW_ADMIN_DATA");
  await Promise.all([page.waitForResponse(response => response.url().endsWith("/api/v1/admin/me")), page.getByRole("button", { name: "Actualizar", exact: true }).click()]);
  await waitRows(2); assert.equal(await create.count(), 0);
  await rows.first().dblclick(); await editForm.waitFor();
  assert.equal(await editForm.getByLabel("Nombre", { exact: true }).isDisabled(), true);
  assert.equal(await editForm.getByLabel("Perfil comercial", { exact: true }).isDisabled(), true);
  assert.equal(await modal.getByRole("button", { name: /^(Guardar|Activar|Desactivar)$/ }).count(), 0);
  await page.keyboard.press("Escape"); await modal.waitFor({ state: "hidden" });
  assert.equal(calls.filter(call => call.method !== "GET" && call.path !== "/api/v1/auth/admin/login").length, mutations.length);
  assert.deepEqual(browserErrors, []);
  report("Store modal E2E passed: viewer can inspect the fresh profile with disabled controls and no mutation actions.");
  await writeFile(`${output}/store-table-scroll.log`, `Command: node e2e/store-table-scroll.mjs\nExit code: 0\nSynthetic API fixtures only; no real backend data.\n${messages.join("\n")}\n`);
} finally {
  await browser?.close(); server.kill();
}

async function measure(page) {
  return page.evaluate(() => {
    const read = element => {
      const rect = element.getBoundingClientRect(); const css = getComputedStyle(element);
      return { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom,
        innerLeft: rect.left + parseFloat(css.borderLeftWidth) + parseFloat(css.paddingLeft),
        innerRight: rect.right - parseFloat(css.borderRightWidth) - parseFloat(css.paddingRight),
        innerBottom: rect.bottom - parseFloat(css.borderBottomWidth) - parseFloat(css.paddingBottom),
        scrollHeight: element.scrollHeight, clientHeight: element.clientHeight, scrollWidth: element.scrollWidth, clientWidth: element.clientWidth,
        scrollTop: element.scrollTop, scrollLeft: element.scrollLeft };
    };
    return { main: read(document.querySelector("main")), section: read(document.querySelector(".stores-workspace")),
      region: read(document.querySelector(".stores-table-region")), scroll: read(document.querySelector(".stores-table-scroll")), document: read(document.scrollingElement) };
  });
}
function assertListingGeometry(geometry, viewport, empty = false) {
  const size = `${viewport.width}x${viewport.height}`;
  for (const key of ["main", "document"]) {
    const element = geometry[key];
    assert.ok(element.scrollHeight <= element.clientHeight + 1 && element.scrollWidth <= element.clientWidth + 1,
      `${key} must not overflow in listing mode at ${size}: ${JSON.stringify(element)}`);
    assert.ok(element.scrollTop <= 1 && element.scrollLeft <= 1, `${key} must not move when store rows scroll at ${size}`);
  }
  assert.ok(Math.abs(geometry.scroll.left - geometry.section.innerLeft) <= 2 && Math.abs(geometry.scroll.right - geometry.section.innerRight) <= 2,
    `Store table must fill available width at ${size}: ${JSON.stringify(geometry)}`);
  assert.ok(Math.abs(geometry.section.bottom - geometry.main.innerBottom) <= 2 && Math.abs(geometry.region.bottom - geometry.section.innerBottom) <= 2,
    `Store region must reach the available bottom edge at ${size}: ${JSON.stringify(geometry)}`);
  if (!empty) assert.ok(Math.abs(geometry.scroll.bottom - geometry.region.bottom) <= 2, `Store scroll container must fill the region at ${size}: ${JSON.stringify(geometry)}`);
  assert.ok(geometry.scroll.clientHeight > 100, `Store table must retain usable height at ${size}`);
}
async function assertWithin(locator, viewport) {
  const rect = await locator.boundingBox();
  assert.ok(rect && rect.x >= 0 && rect.y >= 0 && rect.x + rect.width <= viewport.width && rect.y + rect.height <= viewport.height,
    `Control must be visible in the viewport: ${JSON.stringify(rect)}`);
}
async function verifyModalForm(page, modal, form, viewport) {
  await form.waitFor();
  const expiry = form.getByLabel("Valida hasta", { exact: true });
  await expiry.click();
  const calendar = form.getByRole("dialog", { name: "Valida hasta", exact: true });
  await calendar.waitFor();
  const calendarAction = calendar.getByRole("button").last();
  await calendarAction.scrollIntoViewIfNeeded();
  await assertWithin(calendarAction, viewport);
  await calendarAction.click();
  await calendar.waitFor({ state: "hidden" });
  for (const name of ["Guardar", "Cancelar"]) {
    const action = form.getByRole("button", { name, exact: true });
    await action.scrollIntoViewIfNeeded(); await action.focus();
    await assertWithin(action, viewport); assert.equal(await action.isEnabled(), true);
  }
  await assertWithin(modal, viewport);
  assert.equal(await modal.evaluate(element => element.scrollWidth <= element.clientWidth), true, "Store dialog must not overflow horizontally");
}
