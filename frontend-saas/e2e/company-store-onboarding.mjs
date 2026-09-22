import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
const playwright = await import("playwright").catch(() => import("../../frontend/node_modules/playwright/index.mjs"));
const root = fileURLToPath(new URL("..", import.meta.url));
const baseUrl = "http://127.0.0.1:5190/";
const screenshots = fileURLToPath(new URL("../../output/playwright/", import.meta.url));
const server = spawn(process.execPath, [fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url)), "--host", "127.0.0.1", "--port", "5190", "--strictPort", "--configLoader", "runner"], { cwd: root, stdio: "ignore", windowsHide: true });
let browser;
try {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error("Company/store test could not start Vite on 5190");
    try { if ((await fetch(baseUrl)).ok) break; } catch { /* bounded startup retry */ }
    if (attempt === 99) throw new Error("Company/store test startup timed out");
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  browser = await playwright.chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  const browserErrors = [];
  page.on("pageerror", error => browserErrors.push(error.message));
  const calls = [];
  const permissions = ["VIEW_ADMIN_DATA", "ADD_COMPANY", "EDIT_COMPANY_DATA"];
  const address = { linea1: "Calle de Prueba 1", ciudad: "Las Palmas", codigoPostal: "35001", provincia: "Las Palmas", pais: "ES" };
  const companies = [{ companyId: "company-a", companyName: "Sociedad existente", taxId: "B00000001", taxpayerType: "SOCIEDAD", companyAddress: address,
    contactName: "Contacto anterior", contactPhone: "600000001", contactEmail: "anterior@example.invalid", supportStatus: "NORMAL", notes: "Datos sintéticos",
    owners: [{ name: "Propietario anterior", taxId: "12345678Z", phone: "", email: "" }], createdAt: "2026-09-20T08:00:00Z" }];
  let companiesFail = true;
  let failCreation = true;
  let creationStarted;
  const creationRequested = new Promise(resolve => { creationStarted = resolve; });
  let releaseCreation;
  const creationReleased = new Promise(resolve => { releaseCreation = resolve; });
  let stores = [];
  let oldProfileStarted;
  const oldProfileRequested = new Promise(resolve => { oldProfileStarted = resolve; });
  let releaseOldProfile;
  const oldProfileReleased = new Promise(resolve => { releaseOldProfile = resolve; });
  let oldProfileCompleted;
  const oldProfileDone = new Promise(resolve => { oldProfileCompleted = resolve; });
  let holdOldProfile = true;
  let profileFail = true;
  const profileKeys = ["name", "companyAddress", "contactName", "contactPhone", "contactEmail", "supportStatus", "notes", "owners"];

  await page.route("**/api/**", async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname; const method = request.method();
    const body = request.postData() ? JSON.parse(request.postData()) : null;
    calls.push({ path, method, body });
    const json = value => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/v1/auth/admin/login") return json({ username: "ONBOARDING_DEMO", accessToken: "synthetic-token", mode: "admin", passwordChangeRequired: false, expiresAt: "2099-01-01T00:00:00Z" });
    if (path === "/api/v1/admin/me") return json({ username: "ONBOARDING_DEMO", permissions });
    if (path === "/api/v1/admin/licenses") return json([]);
    if (path === "/api/v1/admin/companies") {
      if (method === "POST") {
        assert.deepEqual(Object.keys(body).sort(), [...profileKeys, "taxId", "taxpayerType"].sort());
        assert.equal(body.owners.length, 1);
        assert.ok(body.owners.every(owner => owner.name && owner.taxId));
        if (failCreation) {
          creationStarted(); await creationReleased;
          return route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Alta temporalmente no disponible" }) });
        }
        const { name, ...details } = body;
        const company = { ...details, companyId: "company-new", companyName: name, createdAt: "2026-09-20T10:00:00Z" };
        companies.push(company); return json(company);
      }
      if (companiesFail) return route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "No se pudo cargar la lista sintética de empresas" }) });
      return json(companies);
    }
    if (path === "/api/v1/admin/sync/sales-summary") return json({ documentCount: 0, total: "0.00" });
    if (path === "/api/v1/admin/reports/advanced") return json({ companies: companies.length, invoices: 0, invoicedTotal: "0.00", paidTotal: "0.00", salesDocuments: 0, salesTotal: "0.00", inventoryMovements: 0, integrations: 0, activeIntegrations: 0 });
    if (path.endsWith("/profile")) {
      const companyId = path.split("/")[5];
      const company = companies.find(item => item.companyId === companyId);
      if (companyId === "company-a" && holdOldProfile) {
        const old = structuredClone(company);
        oldProfileStarted();
        await oldProfileReleased; await json(old); oldProfileCompleted(); return;
      }
      if (profileFail) return route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Profile temporarily unavailable" }) });
      if (method === "PUT") {
        assert.deepEqual(Object.keys(body).sort(), profileKeys.sort());
        assert.ok(body.owners.length > 0 && body.owners.every(owner => owner.name && owner.taxId));
        const { name, ...details } = body;
        Object.assign(company, details, { companyName: name });
      }
      return json(company);
    }
    if (path === "/api/v1/admin/stores") return json({ items: stores, page: 0, size: 25, total: stores.length, totalPages: 1 });
    if (path === "/api/v1/admin/companies/company-new/stores" && method === "POST") {
      const store = { ...body, companyId: "company-new", companyName: companies[1].companyName, id: "store-new", internalCode: "3500001", active: true, taxRegimeLocked: false, activeInstallations: 0, installations: 0, lastSyncAt: null, createdAt: "2026-09-20T11:00:00Z" };
      stores.push(store); return json(store);
    }
    if (path === "/api/v1/admin/stores/store-new/activity" && method === "PUT") { stores[0].active = body.active; return json(stores[0]); }
    if (path === "/api/v1/admin/stores/store-new" && method === "GET") return json(stores[0]);
    if (path === "/api/v1/admin/stores/store-new" && method === "PUT") { Object.assign(stores[0], body); return json(stores[0]); }
    if (method !== "GET") throw new Error(`Unexpected mutation: ${method} ${path}`);
    return json([]);
  });
  await page.goto(baseUrl);
  await page.locator('input[autocomplete="username"]').fill("ONBOARDING_DEMO");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  await page.locator(".saas-dashboard").waitFor();
  const nav = label => page.locator(".top-nav-list").getByRole("button", { name: label, exact: true }).click();
  await nav("Empresas");
  await page.locator("main .retry-error").waitFor();
  assert.equal(await page.locator("main form").count(), 0);
  assert.equal(await page.getByRole("dialog").count(), 0);
  companiesFail = false;
  await page.locator("main").getByRole("button", { name: "Reintentar", exact: true }).click();
  const existingRow = page.getByRole("row").filter({ has: page.getByRole("cell", { name: "Sociedad existente", exact: true }) });
  await existingRow.waitFor();
  assert.equal(await page.locator("main form").count(), 0, "The company landing screen must show a list without editing or creation forms");
  assert.equal(await page.getByLabel("Seleccionar empresa", { exact: true }).count(), 0);
  assert.equal(calls.some(call => call.path.endsWith("/profile")), false, "Company detail must load only when a row is opened");

  const createButton = page.locator("main").getByRole("button", { name: "Alta nueva empresa", exact: true });
  const createDialog = page.getByRole("dialog", { name: "Alta nueva empresa", exact: true });
  await createButton.click();
  await createDialog.waitFor();
  await page.keyboard.press("Escape");
  await createDialog.waitFor({ state: "hidden" });
  assert.equal(await createButton.evaluate(element => document.activeElement === element), true);

  await existingRow.dblclick();
  const companyDialog = page.getByRole("dialog", { name: /^Ficha de empresa/i });
  await companyDialog.waitFor();
  await oldProfileRequested;
  await page.keyboard.press("Escape");
  await companyDialog.waitFor({ state: "hidden" });
  assert.equal(await existingRow.evaluate(element => document.activeElement === element), true);

  await createButton.click();
  const create = createDialog.getByRole("form", { name: "Alta de empresa", exact: true });
  await create.waitFor();
  assert.equal(await create.getByLabel("Impuestos", { exact: true }).count(), 0);
  assert.equal(await create.getByLabel("Perfil comercial", { exact: true }).count(), 0);
  assert.equal(await create.locator('input[type="number"], input[type="datetime-local"]').count(), 0);
  assert.equal(await create.getByText("Domicilio fiscal de la tienda", { exact: true }).count(), 0);
  await create.getByLabel("Empresa", { exact: true }).fill("Sociedad sin licencia");
  await create.getByLabel("NIF/CIF", { exact: true }).fill("B00000002");
  await create.getByLabel("Direccion", { exact: true }).fill(address.linea1);
  await create.getByLabel("Ciudad", { exact: true }).fill(address.ciudad);
  await create.getByLabel("Codigo postal", { exact: true }).fill(address.codigoPostal);
  const province = create.getByLabel("Provincia", { exact: true });
  assert.equal(await province.locator("option").count(), 53);
  for (const name of ["Araba/Álava", "Segovia", "Ceuta", "Melilla", "A Coruña", "Las Palmas", "La Rioja", "Illes Balears"]) {
    assert.equal(await province.getByRole("option", { name, exact: true }).count(), 1);
  }
  await province.selectOption(address.provincia);
  const createContact = create.getByRole("group", { name: "Contacto", exact: true });
  await createContact.getByLabel("Nombre", { exact: true }).fill("Contacto nueva sociedad");
  await createContact.getByLabel("Teléfono", { exact: true }).fill("600000002");
  await createContact.getByLabel("Email contacto", { exact: true }).fill("demo@example.invalid");
  const ownerOne = create.getByRole("group", { name: "Propietario 1", exact: true });
  assert.equal(await ownerOne.getByRole("button", { name: "Eliminar propietario 1", exact: true }).isDisabled(), true);
  await create.getByRole("button", { name: "Alta de empresa", exact: true }).click();
  assert.equal(calls.some(call => call.path === "/api/v1/admin/companies" && call.method === "POST"), false, "Owner name and DNI/NIE are required before sending company creation");
  await ownerOne.getByLabel("Nombre completo", { exact: true }).fill("Persona Demo Principal");
  await ownerOne.getByLabel("DNI/NIE", { exact: true }).fill("12345678Z");
  await create.getByRole("button", { name: "Añadir propietario", exact: true }).click();
  const ownerTwo = create.getByRole("group", { name: "Propietario 2", exact: true });
  await ownerTwo.getByLabel("Nombre completo", { exact: true }).fill("Persona Demo Temporal");
  await ownerTwo.getByLabel("DNI/NIE", { exact: true }).fill("87654321X");
  await ownerTwo.getByRole("button", { name: "Eliminar propietario 2", exact: true }).click();
  assert.equal(await ownerTwo.count(), 0);
  assert.equal(await ownerOne.getByLabel("Nombre completo", { exact: true }).inputValue(), "Persona Demo Principal");
  assert.equal(await ownerOne.getByRole("button", { name: "Eliminar propietario 1", exact: true }).isDisabled(), true);
  await mkdir(screenshots, { recursive: true });
  await page.setViewportSize({ width: 1280, height: 900 });
  await createDialog.locator(".saas-workspace-dialog-body").evaluate(element => { element.scrollTop = 0; });
  await page.screenshot({ path: `${screenshots}/saas-companies-create-1280.png`, animations: "disabled" });
  await create.getByRole("button", { name: "Alta de empresa", exact: true }).click();
  await creationRequested;
  await page.keyboard.press("Escape");
  assert.equal(await createDialog.isVisible(), true, "Escape must not close a pending company creation");
  assert.equal(await createDialog.getByRole("button", { name: "Cerrar", exact: true }).isDisabled(), true);
  assert.equal(await create.locator('button[type="submit"]').isDisabled(), true);
  releaseCreation();
  await createDialog.getByRole("alert").filter({ hasText: "El servicio SaaS no está disponible temporalmente. Vuelve a intentarlo." }).waitFor();
  assert.equal(await create.getByLabel("Empresa", { exact: true }).inputValue(), "Sociedad sin licencia");
  assert.equal(await create.getByLabel("NIF/CIF", { exact: true }).inputValue(), "B00000002");
  assert.equal(await create.getByRole("button", { name: "Alta de empresa", exact: true }).isEnabled(), true);
  assert.equal(companies.length, 1, "Failed creation must not add a company to the directory");
  failCreation = false;
  await create.getByRole("button", { name: "Alta de empresa", exact: true }).click();
  await createDialog.waitFor({ state: "hidden" });
  const newRow = page.getByRole("row").filter({ has: page.getByRole("cell", { name: "Sociedad sin licencia", exact: true }) });
  await newRow.waitFor();
  assert.equal(await page.getByRole("dialog").count(), 0, "Creation must return to the refreshed list");
  assert.equal(await page.locator("main form").count(), 0);
  const table = page.getByRole("table", { name: "Empresas", exact: true });
  const companyNames = table.locator('tbody td[data-column-key="companyName"]');
  assert.deepEqual(await companyNames.allTextContents(), ["Sociedad existente", "Sociedad sin licencia"]);
  await table.getByRole("button", { name: "Ordenar por Empresa", exact: true }).click();
  assert.deepEqual(await companyNames.allTextContents(), ["Sociedad sin licencia", "Sociedad existente"]);
  assert.equal(await table.locator('th[data-column-key="companyName"]').getAttribute("aria-sort"), "descending");
  const companySearch = page.getByLabel("Empresa / NIF", { exact: true });
  await companySearch.fill("B00000002");
  assert.deepEqual(await companyNames.allTextContents(), ["Sociedad sin licencia"]);
  await companySearch.fill("");
  await table.getByRole("button", { name: "Ordenar por Empresa", exact: true }).click();
  assert.deepEqual(await companyNames.allTextContents(), ["Sociedad existente", "Sociedad sin licencia"]);
  await page.getByRole("button", { name: "+ Más filtros", exact: true }).click();
  const ownerFilter = page.getByLabel("Propietario (nombre o DNI/NIE)", { exact: true });
  await ownerFilter.fill("Persona Demo Principal");
  assert.deepEqual(await companyNames.allTextContents(), ["Sociedad sin licencia"]);
  await ownerFilter.fill("");
  await page.getByRole("button", { name: /Más filtros|Menos filtros/ }).click();
  await verifyHeaderControls(page, table, companySearch, browser);
  for (const width of [1600, 1280]) {
    await page.setViewportSize({ width, height: 900 });
    const createBounds = await createButton.boundingBox();
    assert.ok(createBounds && createBounds.x >= 0 && createBounds.x + createBounds.width <= width
      && createBounds.y >= 0 && createBounds.y + createBounds.height <= 900,
    `Company creation must fit the viewport at ${width}px: ${JSON.stringify(createBounds)}`);
    const mainWidth = await page.locator("main").evaluate(element => ({ scroll: element.scrollWidth, client: element.clientWidth }));
    assert.ok(mainWidth.scroll <= mainWidth.client, `Only the table may scroll horizontally at ${width}px: ${JSON.stringify(mainWidth)}`);
    await page.mouse.move(width - 30, 150);
    await page.screenshot({ path: `${screenshots}/saas-companies-table-${width}.png`, animations: "disabled" });
  }
  // Simulate a pre-existing INE spelling. Merely opening/saving other fields must preserve it.
  companies[1].companyAddress.provincia = "Palmas, Las";
  await newRow.dblclick();
  await companyDialog.locator(".retry-error").waitFor();
  assert.equal(await companyDialog.getByRole("form").count(), 0, "Profile load failure must not create writable defaults");
  profileFail = false;
  await companyDialog.getByRole("button", { name: "Reintentar", exact: true }).click();
  const detail = companyDialog.getByRole("form", { name: "Ficha de empresa", exact: true });
  await detail.waitFor();
  holdOldProfile = false; releaseOldProfile(); await oldProfileDone;
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.equal(await companyDialog.getByLabel("Nombre", { exact: true }).inputValue(), "Contacto nueva sociedad");
  await page.screenshot({ path: `${screenshots}/saas-companies-detail-1280.png`, animations: "disabled" });
  assert.equal(await page.locator(".pairing-panel, .tenant-access-panel").count(), 0);
  assert.equal(calls.filter(call => call.method === "POST" && call.path !== "/api/v1/auth/admin/login").length, 2);
  console.log("Company onboarding E2E passed: list-first entry, load/create failure retry, pending Escape guard, table search/sorting, focus restoration, visible unlicensed company and stale detail response isolation.");

  const contact = detail.getByRole("group", { name: "Contacto", exact: true });
  await contact.getByLabel("Nombre", { exact: true }).fill("Nuevo contacto");
  await contact.getByLabel("Teléfono", { exact: true }).fill("600000003");
  await detail.locator("textarea").fill("Ficha completa actualizada");
  await detail.getByLabel("Empresa", { exact: true }).fill("Sociedad Demo actualizada");
  assert.equal(await detail.getByLabel("Provincia", { exact: true }).inputValue(), "Las Palmas");
  await detail.getByRole("button", { name: "Añadir propietario", exact: true }).click();
  const secondOwner = detail.getByRole("group", { name: "Propietario 2", exact: true });
  await secondOwner.getByLabel("Nombre completo", { exact: true }).fill("Persona Demo Segunda");
  await secondOwner.getByLabel("DNI/NIE", { exact: true }).fill("87654321X");
  const updatesBefore = calls.filter(call => call.method === "PUT").length;
  assert.equal(await detail.locator('button[type="submit"]').count(), 1);
  await Promise.all([
    page.waitForResponse(response => response.url().endsWith("/api/v1/admin/companies") && response.request().method() === "GET"),
    page.waitForResponse(response => response.url().endsWith("/api/v1/admin/companies/company-new/profile") && response.request().method() === "GET"),
    detail.getByRole("button", { name: "Guardar ficha", exact: true }).click(),
  ]);
  await companyDialog.getByText("Ficha de empresa guardada.", { exact: true }).waitFor();
  assert.equal(calls.filter(call => call.method === "PUT").length, updatesBefore + 1);
  const profileUpdate = calls.findLast(call => call.path.endsWith("/profile") && call.method === "PUT");
  assert.equal(profileUpdate.body.name, "Sociedad Demo actualizada");
  assert.equal(profileUpdate.body.contactName, "Nuevo contacto");
  assert.equal(profileUpdate.body.contactPhone, "600000003");
  assert.equal(profileUpdate.body.notes, "Ficha completa actualizada");
  assert.equal(profileUpdate.body.owners.length, 2);
  assert.equal(profileUpdate.body.owners[1].taxId, "87654321X");
  assert.equal(profileUpdate.body.companyAddress.provincia, "Palmas, Las", "Display alias normalization must not rewrite a historical address without explicit selection");
  assert.equal(calls.some(call => call.path.endsWith("/operations")), false);
  assert.equal(await page.getByLabel("Precio mensual", { exact: true }).count(), 0);
  await page.keyboard.press("Escape");
  await companyDialog.waitFor({ state: "hidden" });
  const editedRow = page.getByRole("row").filter({ has: page.getByRole("cell", { name: "Sociedad Demo actualizada", exact: true }) });
  await editedRow.waitFor();
  assert.equal(await editedRow.evaluate(element => document.activeElement === element), true);
  await editedRow.focus();
  await page.keyboard.press("Enter");
  await contact.waitFor();
  assert.equal(await contact.getByLabel("Nombre", { exact: true }).inputValue(), "Nuevo contacto");
  assert.equal(await detail.locator("textarea").inputValue(), "Ficha completa actualizada");
  assert.equal(await detail.getByRole("group", { name: "Propietario 2", exact: true }).getByLabel("Nombre completo", { exact: true }).inputValue(), "Persona Demo Segunda");
  await page.keyboard.press("Escape");
  await companyDialog.waitFor({ state: "hidden" });
  assert.equal(await editedRow.evaluate(element => document.activeElement === element), true);
  console.log("Company onboarding E2E passed: repeatable required owners, single atomic profile save, historical province preservation, double-click/Enter and Escape focus.");

  await nav("Tiendas");
  const storeCompanyFilter = page.getByRole("search", { name: "Tiendas", exact: true });
  await storeCompanyFilter.getByRole("combobox", { name: "Empresa", exact: true }).fill("B00000002");
  await storeCompanyFilter.getByRole("option", { name: /Sociedad Demo actualizada.*B00000002/ }).click();
  await page.getByRole("button", { name: "Crear", exact: true }).click();
  const storeForm = page.getByRole("form", { name: "Crear", exact: true });
  assert.equal(await storeForm.getByLabel("Empresa", { exact: true }).inputValue(), "company-new");
  await storeForm.getByLabel("Código local (3 cifras)", { exact: true }).fill("001");
  await storeForm.getByLabel("Nombre", { exact: true }).fill("Tienda Demo nueva");
  await storeForm.getByLabel("Impuestos", { exact: true }).selectOption("IGIC");
  assert.equal(await storeForm.getByLabel("Perfil comercial", { exact: true }).inputValue(), "");
  await storeForm.getByLabel("Perfil comercial", { exact: true }).selectOption("MINORISTA");
  assert.equal(await storeForm.getByLabel("Precio SaaS (EUR)", { exact: true }).inputValue(), "");
  assert.equal(await storeForm.getByLabel("Periodicidad", { exact: true }).inputValue(), "");
  await storeForm.getByLabel("Precio SaaS (EUR)", { exact: true }).fill("123.45");
  await storeForm.getByLabel("Periodicidad", { exact: true }).selectOption("ANNUAL");
  await storeForm.getByLabel("Windows", { exact: true }).fill("3");
  await storeForm.getByLabel("PDA", { exact: true }).fill("2");
  await storeForm.getByLabel("Valida hasta", { exact: true }).click();
  const calendar = page.getByRole("dialog", { name: "Valida hasta", exact: true });
  await calendar.getByRole("button", { name: "Mes siguiente", exact: true }).click();
  await calendar.getByRole("grid").getByRole("button", { name: "15", exact: true }).click();
  await calendar.getByRole("button", { name: "Cerrar calendario", exact: true }).click();
  await storeForm.getByLabel("Direccion", { exact: true }).fill(address.linea1);
  await storeForm.getByLabel("Ciudad", { exact: true }).fill(address.ciudad);
  await storeForm.getByLabel("Codigo postal", { exact: true }).fill(address.codigoPostal);
  await storeForm.getByLabel("Provincia", { exact: true }).selectOption(address.provincia);
  await storeForm.getByRole("button", { name: "Guardar", exact: true }).click();
  await page.getByRole("cell", { name: "3500001", exact: true }).waitFor();
  const createStore = calls.find(call => call.path.endsWith("/company-new/stores") && call.method === "POST");
  assert.equal(createStore.body.taxRegime, "IGIC");
  assert.equal(createStore.body.commercialProfile, "MINORISTA");
  assert.equal(createStore.body.servicePrice, "123.45");
  assert.equal(createStore.body.billingPeriod, "ANNUAL");
  assert.equal(createStore.body.maxWindows, 3); assert.equal(createStore.body.maxPda, 2);
  assert.ok(Date.parse(createStore.body.validUntil) > Date.now());
  assert.equal(calls.filter(call => call.path === "/api/v1/admin/license-workspace" && call.method === "POST").length, 0);
  stores[0].taxRegimeLocked = true;
  stores[0].validUntil = "2099-11-15T10:11:12.123Z";
  const storeRow = page.locator('.stores-workspace tr[data-row-id="store-new"]');
  const storeDialog = page.locator("dialog.saas-store-dialog");
  const openStore = async () => { await storeRow.dblclick(); await storeDialog.getByRole("form", { name: "Editar", exact: true }).waitFor(); };
  await openStore();
  await storeDialog.getByRole("button", { name: "Desactivar", exact: true }).click();
  await storeDialog.waitFor({ state: "hidden" });
  await openStore();
  await storeDialog.getByRole("button", { name: "Activar", exact: true }).waitFor();
  assert.equal(await page.getByRole("form", { name: "Editar", exact: true }).getByLabel("Impuestos", { exact: true }).isDisabled(), true);
  assert.equal(await page.getByRole("form", { name: "Editar", exact: true }).getByLabel("Windows", { exact: true }).isDisabled(), true);
  assert.equal(await page.getByRole("form", { name: "Editar", exact: true }).getByLabel("Precio SaaS (EUR)", { exact: true }).isEnabled(), true);
  const editStore = page.getByRole("form", { name: "Editar", exact: true });
  await editStore.getByLabel("Precio SaaS (EUR)", { exact: true }).fill("234.56");
  await editStore.getByLabel("Perfil comercial", { exact: true }).selectOption("MAYORISTA");
  await editStore.getByRole("button", { name: "Guardar", exact: true }).click();
  await editStore.waitFor({ state: "hidden" });
  const storeUpdate = calls.findLast(call => call.path === "/api/v1/admin/stores/store-new" && call.method === "PUT");
  assert.equal(storeUpdate.body.validUntil, "2099-11-15T10:11:12.123Z");
  assert.equal(storeUpdate.body.servicePrice, "234.56");
  assert.equal(storeUpdate.body.commercialProfile, "MAYORISTA");
  permissions.push("RENEW_LICENSE");
  await page.getByRole("button", { name: "Actualizar", exact: true }).click();
  await page.getByRole("cell", { name: "3500001", exact: true }).waitFor();
  await openStore();
  assert.equal(await editStore.getByLabel("Windows", { exact: true }).isEnabled(), true);
  assert.equal(await editStore.getByLabel("Valida hasta", { exact: true }).isEnabled(), true);
  assert.equal(await editStore.getByLabel("Impuestos", { exact: true }).isDisabled(), true);
  await editStore.getByLabel("Windows", { exact: true }).fill("4");
  await editStore.getByLabel("Periodicidad", { exact: true }).selectOption("MONTHLY");
  await editStore.getByRole("button", { name: "Guardar", exact: true }).click();
  await editStore.waitFor({ state: "hidden" });
  const renewed = calls.findLast(call => call.path === "/api/v1/admin/stores/store-new" && call.method === "PUT");
  assert.equal(renewed.body.maxWindows, 4); assert.equal(renewed.body.billingPeriod, "MONTHLY");
  stores[0].servicePrice = null; stores[0].billingPeriod = null; stores[0].validUntil = null;
  await page.getByRole("button", { name: "Actualizar", exact: true }).click();
  await page.getByRole("cell", { name: "Sin configurar", exact: true }).first().waitFor();
  await openStore();
  assert.equal(await editStore.getByLabel("Precio SaaS (EUR)", { exact: true }).inputValue(), "");
  assert.equal(await editStore.getByLabel("Periodicidad", { exact: true }).inputValue(), "");
  assert.equal(await editStore.getByLabel("Valida hasta", { exact: true }).inputValue(), "");
  await editStore.getByLabel("Precio SaaS (EUR)", { exact: true }).fill("29.90");
  await editStore.getByLabel("Periodicidad", { exact: true }).selectOption("MONTHLY");
  await editStore.getByRole("button", { name: "Guardar", exact: true }).click();
  await editStore.waitFor({ state: "hidden" });
  const legacyUpdate = calls.findLast(call => call.path === "/api/v1/admin/stores/store-new" && call.method === "PUT");
  assert.equal(legacyUpdate.body.validUntil, null);
  assert.equal(legacyUpdate.body.maxWindows, 4);
  await openStore();
  assert.deepEqual(browserErrors, []);
  await mkdir(fileURLToPath(new URL("../../output/playwright/", import.meta.url)), { recursive: true });
  await page.screenshot({ path: fileURLToPath(new URL("../../output/playwright/saas-company-store.png", import.meta.url)), fullPage: true });
  await page.keyboard.press("Escape");
  await storeDialog.waitFor({ state: "hidden" });
  console.log("Store onboarding E2E passed: store fiscal identity, monthly/annual decimal price, explicit expiry, renewal permissions, preserved timestamps, no invented legacy price and editable legacy null expiry.");

  permissions.splice(0, permissions.length, "VIEW_ADMIN_DATA");
  await Promise.all([
    page.waitForResponse(response => response.url().endsWith("/api/v1/admin/me")),
    page.getByRole("button", { name: "Actualizar", exact: true }).click(),
  ]);
  await nav("Empresas");
  await editedRow.waitFor();
  assert.equal(await createButton.count(), 0, "Viewer must not see company creation");
  assert.equal(await page.locator("main form").count(), 0);
  const mutationsBeforeViewer = calls.filter(call => call.method !== "GET").length;
  companies[1].companyAddress.provincia = "Provincia histórica";
  await editedRow.focus();
  await page.keyboard.press("Enter");
  await companyDialog.getByRole("form", { name: "Ficha de empresa", exact: true }).waitFor();
  assert.equal(await companyDialog.getByRole("button", { name: "Guardar ficha", exact: true }).count(), 0);
  assert.equal(await companyDialog.getByRole("button", { name: "Añadir propietario", exact: true }).count(), 0);
  assert.equal(await companyDialog.getByLabel("Empresa", { exact: true }).isDisabled(), true);
  assert.equal(await companyDialog.getByLabel("Nombre", { exact: true }).isDisabled(), true);
  assert.equal(await companyDialog.getByLabel("Nombre", { exact: true }).inputValue(), "Nuevo contacto");
  const historicalProvince = companyDialog.getByLabel("Provincia", { exact: true });
  assert.equal(await historicalProvince.inputValue(), "Provincia histórica");
  assert.equal(await historicalProvince.getByRole("option", { name: "Provincia histórica (valor actual)", exact: true }).count(), 1);
  await page.keyboard.press("Escape");
  await companyDialog.waitFor({ state: "hidden" });
  assert.equal(await editedRow.evaluate(element => document.activeElement === element), true);
  assert.equal(calls.filter(call => call.method !== "GET").length, mutationsBeforeViewer);
  assert.deepEqual(browserErrors, []);
  console.log("Company onboarding E2E passed: viewer can inspect a company without creation, saving or API mutations.");

  // A large synthetic directory must remain one scrollable list, with no page boundary at 25 rows.
  companies.push(...Array.from({ length: 80 }, (_, index) => ({
    companyId: `company-overflow-${index + 1}`, companyName: `ZZZ Sociedad de prueba ${String(index + 1).padStart(3, "0")}`,
    taxId: `B${String(70000001 + index)}`, taxpayerType: "SOCIEDAD", companyAddress: { ...address },
    contactName: `Contacto de prueba ${index + 1}`, contactPhone: "", contactEmail: "prueba@example.invalid",
    supportStatus: "NORMAL", notes: "Datos sintéticos de desplazamiento", owners: [], createdAt: "2026-09-20T12:00:00Z",
  })));
  permissions.splice(0, permissions.length, "VIEW_ADMIN_DATA", "ADD_COMPANY", "EDIT_COMPANY_DATA");
  await Promise.all([
    page.waitForResponse(response => response.url().endsWith("/api/v1/admin/companies") && response.request().method() === "GET"),
    page.getByRole("button", { name: "Actualizar", exact: true }).click(),
  ]);
  await createButton.waitFor();
  await table.locator('tr[data-row-id="company-overflow-80"]').waitFor({ state: "attached" });
  assert.equal(await table.locator("tbody tr").count(), companies.length, "All 82 companies must be in the same table without pagination");
  await verifyCompanyTableViewport(page, table, companySearch, createButton, screenshots);
  assert.equal(calls.filter(call => call.method !== "GET").length, mutationsBeforeViewer, "Inspecting the long directory must not mutate any company");
  assert.deepEqual(browserErrors, []);
  console.log("Company directory E2E passed: 82 rows without pagination, two-row and empty filters; full available width/height, internal scrolling, sticky header and visible controls at 1280x900, 1600x1000 and 1366x768.");
} finally {
  await browser?.close(); server.kill();
}

async function verifyCompanyTableViewport(page, table, search, createButton, screenshots) {
  const scroll = page.locator(".company-table-region .saas-data-table-scroll");
  const lastRow = table.locator('tr[data-row-id="company-overflow-80"]');
  const heading = table.locator('th[data-column-key="companyName"]');
  const settleLayout = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  const outerGeometry = () => page.evaluate(() => {
    const measure = element => {
      const rect = element.getBoundingClientRect(); const css = getComputedStyle(element);
      return { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom,
        innerLeft: rect.left + parseFloat(css.borderLeftWidth) + parseFloat(css.paddingLeft),
        innerRight: rect.right - parseFloat(css.borderRightWidth) - parseFloat(css.paddingRight),
        innerBottom: rect.bottom - parseFloat(css.borderBottomWidth) - parseFloat(css.paddingBottom),
        scrollWidth: element.scrollWidth, clientWidth: element.clientWidth, scrollHeight: element.scrollHeight,
        clientHeight: element.clientHeight, scrollTop: element.scrollTop, scrollLeft: element.scrollLeft };
    };
    return { main: measure(document.querySelector("main")), section: measure(document.querySelector(".company-list-section")),
      scroll: measure(document.querySelector(".company-table-region .saas-data-table-scroll")), document: measure(document.scrollingElement) };
  });
  const assertOuterStill = (geometry, size) => {
    for (const key of ["main", "document"]) {
      const element = geometry[key];
      assert.ok(element.scrollHeight <= element.clientHeight + 1 && element.scrollWidth <= element.clientWidth + 1,
        `${key} must not overflow at ${size}: ${JSON.stringify(element)}`);
      assert.ok(element.scrollTop <= 1 && element.scrollLeft <= 1, `${key} must not move while the company table scrolls at ${size}`);
    }
  };
  const assertControlInside = async (control, width, height, name) => {
    const rect = await control.boundingBox();
    assert.ok(rect && rect.x >= 0 && rect.y >= 0 && rect.x + rect.width <= width && rect.y + rect.height <= height,
      `${name} must stay inside ${width}x${height}: ${JSON.stringify(rect)}`);
    return rect;
  };
  for (const { width, height } of [{ width: 1280, height: 900 }, { width: 1600, height: 1000 }, { width: 1366, height: 768 }]) {
    const size = `${width}x${height}`;
    await page.setViewportSize({ width, height });
    await scroll.evaluate(element => { element.scrollTop = 0; element.scrollLeft = 0; });
    await search.focus(); await settleLayout();
    const before = await outerGeometry();
    assertOuterStill(before, size);
    assert.ok(before.scroll.clientHeight > 150 && before.scroll.scrollHeight > before.scroll.clientHeight,
      `The long company list must have an internal vertical scrollbar at ${size}`);
    assert.ok(Math.abs(before.scroll.left - before.section.innerLeft) <= 2 && Math.abs(before.scroll.right - before.section.innerRight) <= 2,
      `The table viewport must fill the section width at ${size}: ${JSON.stringify(before)}`);
    assert.ok(Math.abs(before.scroll.bottom - before.section.innerBottom) <= 2 && Math.abs(before.section.bottom - before.main.innerBottom) <= 2,
      `The table viewport must reach the available lower edge at ${size}: ${JSON.stringify(before)}`);
    const createBefore = await assertControlInside(createButton, width, height, "Company creation");
    const searchBefore = await assertControlInside(search, width, height, "Company search");
    const headingBefore = await heading.boundingBox();
    await table.locator("tbody tr").first().focus();
    await page.keyboard.press("End");
    await settleLayout();
    assert.equal(await lastRow.evaluate(element => document.activeElement === element), true, `End must reach the final company at ${size}`);
    const after = await outerGeometry();
    assertOuterStill(after, size);
    assert.ok(after.scroll.scrollTop > 0 && Math.abs(after.scroll.scrollHeight - after.scroll.clientHeight - after.scroll.scrollTop) <= 2,
      `End must scroll internally to the last company at ${size}: ${JSON.stringify(after.scroll)}`);
    const lastBounds = await lastRow.boundingBox();
    const headingAfter = await heading.boundingBox();
    assert.ok(lastBounds && lastBounds.y >= after.scroll.top && lastBounds.y + lastBounds.height <= after.scroll.bottom,
      `The final company must be visible inside the table viewport at ${size}`);
    assert.ok(headingBefore && headingAfter && Math.abs(headingAfter.y - headingBefore.y) <= 1 && headingAfter.y >= after.scroll.top,
      `The column header must remain fixed and visible after scrolling at ${size}`);
    assert.deepEqual(await assertControlInside(createButton, width, height, "Company creation after scroll"), createBefore);
    assert.deepEqual(await assertControlInside(search, width, height, "Company search after scroll"), searchBefore);
    // A further wheel gesture at the bottom must not chain into a page/main scroll.
    await page.mouse.move(after.scroll.left + 100, after.scroll.bottom - 50);
    await page.mouse.wheel(0, 1200); await settleLayout();
    assertOuterStill(await outerGeometry(), size);
    await page.mouse.move(width - 20, 60);
    await page.screenshot({ path: `${screenshots}/saas-companies-scroll-${size}.png`, animations: "disabled" });

    await search.fill("B0000000"); await settleLayout();
    assert.equal(await table.locator("tbody tr").count(), 2, "Filtering must retain both original companies without a page-size dependency");
    const few = await outerGeometry();
    assertOuterStill(few, size);
    assert.ok(few.scroll.top >= before.scroll.top - 2 && Math.abs(few.scroll.bottom - before.scroll.bottom) <= 2,
      `The two-row table must use the available height below the active filter chips at ${size}`);
    assert.ok(few.scroll.clientHeight > 100, `Filter chips must retain usable table height at ${size}`);
    assert.ok(few.scroll.scrollHeight <= few.scroll.clientHeight + 1, `Two rows must not require vertical scrolling at ${size}`);
    await page.screenshot({ path: `${screenshots}/saas-companies-few-${size}.png`, animations: "disabled" });

    await search.fill("NO-COMPANY-FOUND"); await settleLayout();
    assert.equal(await table.locator("tbody tr").count(), 0);
    const emptyState = page.locator(".company-table-region .empty-state");
    assert.equal(await emptyState.innerText(), "SIN DATOS");
    const emptyBounds = await emptyState.boundingBox();
    const emptyOccupiedHeight = await emptyState.evaluate(element => {
      const css = getComputedStyle(element);
      return element.getBoundingClientRect().height + parseFloat(css.marginTop) + parseFloat(css.marginBottom);
    });
    const empty = await outerGeometry();
    assertOuterStill(empty, size);
    assert.ok(emptyBounds && emptyBounds.y >= empty.scroll.bottom && emptyBounds.y + emptyBounds.height <= empty.section.innerBottom + 1,
      `The empty-state message must stay visible below the table at ${size}`);
    assert.ok(Math.abs(empty.scroll.top - few.scroll.top) <= 2
      && Math.abs(empty.scroll.bottom + emptyOccupiedHeight - before.scroll.bottom) <= 2,
    `The empty table and its message must use the same available height at ${size}`);
    await assertControlInside(createButton, width, height, "Company creation with no results");
    await assertControlInside(search, width, height, "Company search with no results");
    await page.screenshot({ path: `${screenshots}/saas-companies-empty-${size}.png`, animations: "disabled" });
    await search.fill(""); await settleLayout();
    assert.equal(await table.locator("tbody tr").count(), 82);
  }
}

async function verifyHeaderControls(page, table, search, browser) {
  const heading = table.locator('th[data-column-key="companyName"]');
  const indicator = heading.locator(".saas-layout-sort-indicator");
  const menu = heading.getByRole("button", { name: "Opciones de columna Empresa", exact: true });
  const opacity = locator => locator.evaluate(element => getComputedStyle(element).opacity);
  await search.focus(); await page.mouse.move(1250, 150);
  const initialWidth = (await heading.boundingBox()).width;
  assert.equal(await opacity(indicator), "0"); assert.equal(await opacity(menu), "0");
  await heading.hover();
  assert.equal(await opacity(indicator), "1"); assert.equal(await opacity(menu), "1");
  await page.mouse.move(1250, 150);
  await heading.getByRole("button", { name: "Ordenar por Empresa", exact: true }).focus();
  assert.equal(await opacity(indicator), "1"); assert.equal(await opacity(menu), "1");
  await menu.click();
  await page.getByRole("menu").getByRole("menuitem").first().focus();
  await page.mouse.move(1250, 150);
  assert.equal(await opacity(indicator), "1"); assert.equal(await opacity(menu), "1");
  await page.keyboard.press("Escape");
  await search.focus();
  assert.equal(await opacity(indicator), "0"); assert.equal(await opacity(menu), "0");
  assert.equal((await heading.boundingBox()).width, initialWidth, "Header actions must not change the column width");

  const touch = await browser.newPage({ hasTouch: true, viewport: { width: 1280, height: 900 } });
  try {
    await touch.setContent(await table.evaluate(element => element.outerHTML));
    await touch.addStyleTag({ path: fileURLToPath(new URL("../src/shared/table/table-layout.css", import.meta.url)) });
    assert.equal(await touch.evaluate(() => matchMedia("(any-pointer: coarse)").matches), true);
    assert.equal(await touch.locator(".saas-layout-sort-indicator").first().evaluate(element => getComputedStyle(element).opacity), "1");
    assert.equal(await touch.locator(".saas-layout-menu-trigger").first().evaluate(element => getComputedStyle(element).opacity), "1");
  } finally { await touch.close(); }
}
