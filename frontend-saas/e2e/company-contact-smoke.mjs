import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
const { chromium } = await import("playwright").catch(() => import("../../frontend/node_modules/playwright/index.mjs"));

// All API reads and writes are local synthetic fixtures; no commercial entities are created.
const root = fileURLToPath(new URL("..", import.meta.url));
const base = "http://127.0.0.1:5198/";
const output = fileURLToPath(new URL("../../output/playwright/", import.meta.url));
const address = { linea1: "Calle de Prueba 1", ciudad: "Las Palmas", codigoPostal: "35001", provincia: "Las Palmas", pais: "ES" };
const original = { companyId: "company-contact", companyName: "Empresa Contacto Demo", taxId: "B00000001", taxpayerType: "SOCIEDAD", companyAddress: address,
  createdAt: "2026-09-21T10:00:00Z", contactName: "Contacto independiente", contactPhone: "600000000", contactEmail: "Original.Contact+Demo@example.invalid", supportStatus: "NORMAL", notes: "Notas en texto original",
  owners: [
    { name: "Ana Propietaria", taxId: "11111111H", phone: "+34 600 111 222", email: "Ana.Owner+Demo@example.invalid" },
    { name: "Beatriz Sin Datos", taxId: "22222222J", phone: "", email: "" },
    { name: "Carlos Tercero", taxId: "33333333P", phone: "600333333", email: "Carlos.Third@example.invalid" },
  ] };
const profileKeys = ["name", "companyAddress", "contactName", "contactPhone", "contactEmail", "supportStatus", "notes", "owners"];
let browser, server;
const browserErrors = [];
async function until(check, label = "expected UI state") {
  for (let attempt = 0; attempt < 100; attempt++) {
    const result = await check(); if (result) return result;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error(`Timed out waiting for ${label}`);
}
async function setup(viewer = false, initialCompany = original) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage(); page.setDefaultTimeout(10_000);
  page.on("pageerror", error => browserErrors.push(error.message));
  const companies = [structuredClone(initialCompany)], calls = [];
  await context.route("**/api/**", async route => {
    const request = route.request(), path = new URL(request.url()).pathname, method = request.method();
    const body = request.postData() ? JSON.parse(request.postData()) : null;
    calls.push({ path, method, body });
    const json = value => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
    if (path === "/api/v1/auth/admin/login") return json({ username: viewer ? "CONTACT_VIEWER" : "CONTACT_DEMO", accessToken: "synthetic-token", mode: "admin", expiresAt: "2099-01-01T00:00:00Z", passwordChangeRequired: false });
    if (path === "/api/v1/admin/me") return json({ username: viewer ? "CONTACT_VIEWER" : "CONTACT_DEMO", permissions: viewer ? ["VIEW_ADMIN_DATA"] : ["VIEW_ADMIN_DATA", "ADD_COMPANY", "EDIT_COMPANY_DATA"] });
    if (path === "/api/v1/admin/companies") {
      if (method === "POST") {
        assert.equal(viewer, false); assert.deepEqual(Object.keys(body).sort(), [...profileKeys, "taxId", "taxpayerType"].sort());
        const { name, ...values } = body;
        const saved = { ...values, companyId: "created-company", companyName: name, createdAt: "2026-09-21T12:00:00Z" };
        companies.push(saved); return json(saved);
      }
      return json(companies);
    }
    if (/\/companies\/[^/]+\/profile$/.test(path)) {
      const company = companies.find(row => row.companyId === path.split("/")[5]); assert.ok(company);
      if (method === "PUT") {
        assert.equal(viewer, false); assert.deepEqual(Object.keys(body).sort(), profileKeys.sort());
        const { name, ...values } = body; Object.assign(company, values, { companyName: name });
      }
      return json(company);
    }
    if (path === "/api/v1/admin/sync/sales-summary") return json({ documentCount: 0, total: "0.00" });
    if (path === "/api/v1/admin/reports/advanced") return json({ companies: companies.length, invoices: 0, invoicedTotal: "0.00", paidTotal: "0.00", salesDocuments: 0, salesTotal: "0.00", inventoryMovements: 0, integrations: 0, activeIntegrations: 0 });
    if (method !== "GET") throw new Error(`Unexpected mutation: ${method} ${path}`);
    return json([]);
  });
  await page.goto(base);
  await page.locator('input[autocomplete="username"]').fill(viewer ? "CONTACT_VIEWER" : "CONTACT_DEMO");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  await page.locator(".top-nav-list").getByRole("button", { name: "Empresas", exact: true }).click();
  await page.locator('tr[data-row-id="company-contact"]').waitFor();
  return { page, context, calls, companies };
}
function contactFields(form) {
  const group = form.getByRole("group", { name: "Contacto", exact: true });
  return { group, name: group.getByLabel("Nombre", { exact: true }), phone: group.getByLabel("Teléfono", { exact: true }),
    email: group.getByLabel("Email contacto", { exact: true }), selector: group.getByLabel("Usar datos de propietario", { exact: true }) };
}
async function values(fields) { return { name: await fields.name.inputValue(), phone: await fields.phone.inputValue(), email: await fields.email.inputValue() }; }
async function assertVisualOnlyUppercase(page, dialog) {
  const styled = await dialog.locator("h3, legend, label, button, input, select, textarea").evaluateAll(elements => elements.map(element => ({
    tag: element.tagName, text: element.textContent?.slice(0, 50), transform: getComputedStyle(element).textTransform,
  })));
  assert.ok(styled.length > 10);
  assert.deepEqual(styled.filter(element => element.transform !== "uppercase"), [], "Every visible company-dialog text/control uses visual uppercase");
  assert.notEqual(await page.getByLabel("Empresa / NIF", { exact: true }).evaluate(element => getComputedStyle(element).textTransform), "uppercase",
    "The company-dialog uppercase style must not affect the directory filters");
  assert.notEqual(await page.locator('tr[data-row-id="company-contact"] td').first().evaluate(element => getComputedStyle(element).textTransform), "uppercase",
    "The company-dialog uppercase style must not affect company table values");
}

try {
  server = spawn(process.execPath, [fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url)), "--host", "127.0.0.1", "--port", "5198", "--strictPort", "--configLoader", "runner"], { cwd: root, stdio: "ignore", windowsHide: true });
  await until(async () => { if (server.exitCode !== null) throw new Error("Company contact Vite failed to start"); try { return (await fetch(base)).ok; } catch { return false; } }, "Vite startup");
  browser = await chromium.launch({ headless: true });
  const fixture = await setup(); const { page, calls, companies } = fixture;
  const row = page.locator('tr[data-row-id="company-contact"]');
  await row.dblclick();
  const dialog = page.getByRole("dialog", { name: "Ficha de empresa: Empresa Contacto Demo", exact: true });
  const form = dialog.getByRole("form", { name: "Ficha de empresa", exact: true }); await form.waitFor();
  const country = form.getByRole("combobox", { name: "Pais", exact: true });
  assert.equal(await country.inputValue(), "ES");
  assert.equal(await country.locator("option").count(), 51);
  assert.equal(await country.locator("option").first().getAttribute("value"), "ES", "Spain must be the first option without a preceding placeholder");
  assert.equal(await country.locator("option").first().textContent(), "España");
  await country.selectOption("FR");
  const foreignProvince = form.getByRole("textbox", { name: "Provincia", exact: true });
  assert.equal(await foreignProvince.inputValue(), "Las Palmas", "Changing country must not silently erase the existing province");
  assert.equal(await form.getByRole("combobox", { name: "Provincia", exact: true }).count(), 0);
  await foreignProvince.fill("Paris");
  const contact = contactFields(form);
  const owners = form.getByRole("group", { name: "Propietarios", exact: true });
  assert.equal(await form.evaluate(element => {
    const direct = [...element.querySelectorAll("fieldset.company-fieldset")].map(fieldset => fieldset.querySelector(":scope > legend")?.textContent);
    return direct.indexOf("Propietarios") < direct.indexOf("Contacto");
  }), true, "Owners must precede contact in DOM/keyboard order");
  assert.equal(await contact.group.getByLabel("Contacto", { exact: true }).count(), 0);
  assert.equal(await contact.selector.inputValue(), "");
  assert.deepEqual(await values(contact), { name: original.contactName, phone: original.contactPhone, email: original.contactEmail });
  await assertVisualOnlyUppercase(page, dialog);
  assert.equal(await contact.email.getAttribute("type"), "email");
  await contact.email.fill("Independent.Mixed+Edited@example.invalid");
  await contact.email.press("Home"); await contact.email.press("End");
  assert.equal(await contact.email.inputValue(), "Independent.Mixed+Edited@example.invalid");
  assert.equal(await contact.email.evaluate(input => input.checkValidity()), true);
  assert.equal(await owners.getByRole("group", { name: "Propietario 1", exact: true }).getByLabel("Email contacto", { exact: true }).inputValue(), original.owners[0].email);

  const ownerOne = owners.getByRole("group", { name: "Propietario 1", exact: true });
  await ownerOne.getByLabel("Email contacto", { exact: true }).fill("");
  await contact.selector.selectOption("0");
  assert.deepEqual(await values(contact), { name: original.owners[0].name, phone: original.owners[0].phone, email: "" },
    "An owner missing only email must preserve the provided telephone when copied");
  assert.equal(await contact.email.evaluate(input => input.required && input.validity.valueMissing), true);
  assert.equal(await contact.phone.evaluate(input => input.checkValidity()), true);
  await ownerOne.getByLabel("Email contacto", { exact: true }).fill("Ana.Owner+Updated@example.invalid");
  assert.equal(await contact.email.inputValue(), "Ana.Owner+Updated@example.invalid", "Editing the selected owner must update the linked contact draft");
  await ownerOne.getByLabel("Email contacto", { exact: true }).fill(original.owners[0].email);
  assert.deepEqual(await values(contact), { name: original.owners[0].name, phone: original.owners[0].phone, email: original.owners[0].email });
  await contact.selector.selectOption("1");
  assert.deepEqual(await values(contact), { name: original.owners[1].name, phone: "", email: "" });
  assert.equal(await contact.phone.evaluate(input => input.required && input.validity.valueMissing), true);
  assert.equal(await contact.email.evaluate(input => input.required && input.validity.valueMissing), true);
  await contact.group.locator(".company-contact-missing").waitFor();
  const save = form.getByRole("button", { name: "Guardar ficha", exact: true });
  await save.click();
  assert.equal(calls.filter(call => call.method === "PUT").length, 0, "Selecting an owner without phone/email must require completing the missing contact data");
  await contact.phone.fill("   ");
  assert.equal(await contact.phone.evaluate(input => input.checkValidity()), false, "Whitespace must not satisfy the selected owner's missing telephone");
  await contact.selector.selectOption("");
  assert.equal(await contact.phone.evaluate(input => !input.required && !input.validity.customError), true);
  assert.equal(await contact.email.evaluate(input => !input.required && !input.validity.customError), true,
    "Independent contact must release both the required and custom missing-owner validity constraints");
  await contact.selector.selectOption("1");
  await contact.phone.fill("+34 600 222 333");
  await contact.email.fill("Beatriz.Completed+Contact@example.invalid");
  const ownerTwo = owners.getByRole("group", { name: "Propietario 2", exact: true });
  assert.equal(await ownerTwo.getByLabel("Teléfono", { exact: true }).inputValue(), "+34 600 222 333");
  assert.equal(await ownerTwo.getByLabel("Email contacto", { exact: true }).inputValue(), "Beatriz.Completed+Contact@example.invalid");
  await save.click();
  await dialog.getByText("Ficha de empresa guardada.", { exact: true }).waitFor();
  assert.equal(calls.filter(call => call.method === "PUT").length, 1);
  const saved = calls.findLast(call => call.method === "PUT").body;
  assert.equal(saved.contactName, "Beatriz Sin Datos");
  assert.equal(saved.contactPhone, "+34 600 222 333");
  assert.equal(saved.contactEmail, "Beatriz.Completed+Contact@example.invalid");
  assert.deepEqual(saved.owners[1], { ...original.owners[1], phone: saved.contactPhone, email: saved.contactEmail });
  assert.deepEqual(saved.owners[0], original.owners[0]);
  assert.deepEqual(saved.owners[2], original.owners[2]);
  assert.equal(saved.name, original.companyName); assert.equal(saved.notes, original.notes);
  assert.equal(saved.companyAddress.pais, "FR", "The company API must receive the country code, not the displayed country name");
  assert.equal(saved.companyAddress.provincia, "Paris");
  console.log("Company contact E2E: owners-before-contact order, renamed field, dialog-only visual uppercase, email editing and one atomic save of missing owner/contact data passed.");

  await until(async () => await save.isEnabled(), "profile refresh completion");
  assert.equal(await contact.selector.inputValue(), "", "Accepting a canonical profile must reset the temporary index-based contact link");
  await contact.selector.selectOption("2");
  assert.deepEqual(await values(contact), { name: original.owners[2].name, phone: original.owners[2].phone, email: original.owners[2].email });
  await owners.getByRole("button", { name: "Eliminar propietario 1", exact: true }).click();
  assert.equal(await contact.selector.inputValue(), "1", "Removing an earlier owner must keep the selection attached to the same person");
  assert.equal(await contact.name.inputValue(), "Carlos Tercero");
  await contact.phone.fill("600333334");
  assert.equal(await owners.getByRole("group", { name: "Propietario 2", exact: true }).getByLabel("Teléfono", { exact: true }).inputValue(), "600333334");
  assert.equal(await owners.getByRole("group", { name: "Propietario 1", exact: true }).getByLabel("Teléfono", { exact: true }).inputValue(), "+34 600 222 333");
  await owners.getByRole("button", { name: "Eliminar propietario 2", exact: true }).click();
  assert.equal(await contact.selector.inputValue(), "", "Deleting the selected owner must release the link instead of binding another owner");
  assert.deepEqual(await values(contact), { name: "Carlos Tercero", phone: "600333334", email: original.owners[2].email },
    "The copied contact becomes independent when its owner is removed");
  await contact.phone.fill("600999999");
  assert.equal(await owners.getByRole("group", { name: "Propietario 1", exact: true }).getByLabel("Teléfono", { exact: true }).inputValue(), "+34 600 222 333");
  await contact.selector.selectOption("0");
  const copied = await values(contact);
  await contact.selector.selectOption("");
  assert.deepEqual(await values(contact), copied, "Choosing independent contact keeps its copied values");
  assert.equal(await contact.phone.evaluate(input => input.required), false);
  await contact.name.fill("Contacto ahora independiente");
  assert.equal(await owners.getByRole("group", { name: "Propietario 1", exact: true }).getByLabel("Nombre completo", { exact: true }).inputValue(), "Beatriz Sin Datos");
  await page.keyboard.press("Escape"); await dialog.waitFor({ state: "hidden" });
  assert.equal(companies[0].owners.length, 3, "Cancelled owner removals must not reach the server");
  assert.equal(calls.filter(call => call.method === "PUT").length, 1);
  console.log("Company contact E2E: owner switching, index shifts, selected-owner deletion and cancellation do not overwrite another owner's contact data.");

  await page.getByRole("button", { name: "Alta nueva empresa", exact: true }).click();
  const createDialog = page.getByRole("dialog", { name: "Alta nueva empresa", exact: true });
  const create = createDialog.getByRole("form", { name: "Alta de empresa", exact: true }); await create.waitFor();
  await create.getByLabel("Empresa", { exact: true }).fill("Empresa Alta Contacto");
  await create.getByLabel("NIF/CIF", { exact: true }).fill("B00000002");
  for (const [label, value] of [["Direccion", address.linea1], ["Ciudad", address.ciudad], ["Codigo postal", address.codigoPostal]]) await create.getByLabel(label, { exact: true }).fill(value);
  const newCountry = create.getByRole("combobox", { name: "Pais", exact: true });
  assert.equal(await newCountry.inputValue(), "ES", "New company forms default to Spain");
  assert.equal(await newCountry.locator("option").first().getAttribute("value"), "ES");
  assert.equal(await newCountry.locator("option").first().textContent(), "España");
  await create.getByLabel("Provincia", { exact: true }).selectOption("Las Palmas");
  const newOwner = create.getByRole("group", { name: "Propietario 1", exact: true });
  await newOwner.getByLabel("Nombre completo", { exact: true }).fill("Nueva Propietaria");
  await newOwner.getByLabel("DNI/NIE", { exact: true }).fill("44444444A");
  const newContact = contactFields(create);
  await newContact.selector.selectOption("0");
  await newContact.phone.fill("600444444");
  await newContact.email.fill("New.Owner+Contact@example.invalid");
  await assertVisualOnlyUppercase(page, createDialog);
  await mkdir(output, { recursive: true });
  await createDialog.locator(".saas-workspace-dialog-body").evaluate(element => { element.scrollTop = element.scrollHeight; });
  await page.screenshot({ path: `${output}/saas-company-contact-1280.png`, animations: "disabled" });
  await create.getByRole("button", { name: "Alta de empresa", exact: true }).click();
  await createDialog.waitFor({ state: "hidden" });
  const created = calls.findLast(call => call.path === "/api/v1/admin/companies" && call.method === "POST").body;
  assert.equal(created.contactName, "Nueva Propietaria");
  assert.equal(created.contactPhone, "600444444");
  assert.equal(created.contactEmail, "New.Owner+Contact@example.invalid");
  assert.equal(created.owners[0].phone, created.contactPhone); assert.equal(created.owners[0].email, created.contactEmail);
  assert.equal(created.companyAddress.pais, "ES");
  await fixture.context.close();

  const historical = { ...original, companyAddress: { ...address, pais: "US", provincia: "California", ciudad: "San Francisco", codigoPostal: "94101" } };
  const legacy = await setup(false, historical);
  await legacy.page.locator('tr[data-row-id="company-contact"]').dblclick();
  const legacyDialog = legacy.page.getByRole("dialog", { name: "Ficha de empresa: Empresa Contacto Demo", exact: true });
  const legacyForm = legacyDialog.getByRole("form", { name: "Ficha de empresa", exact: true }); await legacyForm.waitFor();
  const legacyCountry = legacyForm.getByRole("combobox", { name: "Pais", exact: true });
  assert.equal(await legacyCountry.inputValue(), "US");
  assert.equal(await legacyCountry.locator("option").first().getAttribute("value"), "ES");
  assert.equal(await legacyCountry.getByRole("option", { name: /valor actual/i }).getAttribute("value"), "US");
  assert.equal(await legacyForm.getByRole("textbox", { name: "Provincia", exact: true }).inputValue(), "California");
  await legacyForm.locator("textarea").fill("Notas históricas actualizadas");
  await legacyForm.getByRole("button", { name: "Guardar ficha", exact: true }).click();
  await legacyDialog.getByText("Ficha de empresa guardada.", { exact: true }).waitFor();
  const legacySave = legacy.calls.findLast(call => call.method === "PUT");
  assert.deepEqual(legacySave.body.companyAddress, historical.companyAddress, "Editing unrelated fields must preserve a non-European historical country and province");
  await legacy.context.close();

  const viewer = await setup(true, historical);
  await viewer.page.locator('tr[data-row-id="company-contact"]').dblclick();
  const viewerDialog = viewer.page.getByRole("dialog", { name: "Ficha de empresa: Empresa Contacto Demo", exact: true });
  const viewerForm = viewerDialog.getByRole("form", { name: "Ficha de empresa", exact: true }); await viewerForm.waitFor();
  const readonly = contactFields(viewerForm);
  assert.equal(await readonly.name.isDisabled(), true); assert.equal(await readonly.phone.isDisabled(), true); assert.equal(await readonly.email.isDisabled(), true);
  if (await readonly.selector.count()) assert.equal(await readonly.selector.isDisabled(), true);
  const readonlyCountry = viewerForm.getByRole("combobox", { name: "Pais", exact: true });
  assert.equal(await readonlyCountry.inputValue(), "US");
  assert.equal(await readonlyCountry.isDisabled(), true);
  assert.equal(await viewerForm.getByRole("textbox", { name: "Provincia", exact: true }).isDisabled(), true);
  assert.equal(await viewerDialog.getByRole("button", { name: "Guardar ficha", exact: true }).count(), 0);
  assert.equal(await viewerDialog.getByRole("button", { name: "Añadir propietario", exact: true }).count(), 0);
  assert.equal(await viewerDialog.getByRole("button", { name: /^Eliminar propietario/ }).count(), 0);
  assert.equal(viewer.calls.filter(call => call.method !== "GET" && call.path !== "/api/v1/auth/admin/login").length, 0);
  assert.deepEqual(browserErrors, []);
  console.log("Company contact E2E: creation shares the owner/contact draft and viewer cannot change or save it. All scenarios passed.");
  console.log("Company country E2E: Spain first/default, France with preserved editable province and ISO-code save, historical US preservation and readonly country/province passed.");
  await writeFile(`${output}/company-contact-smoke.log`, "PASS: owner/contact copy, missing-data completion, one atomic write, owner index/removal safety, company-only uppercase, preserved email case, creation/viewer guards, Spain-first country list, French province and historical US preservation.\n");
  await viewer.context.close();
} catch (error) {
  const page = browser?.contexts()[0]?.pages()[0];
  if (page) { await mkdir(output, { recursive: true }); await writeFile(`${output}/company-contact-failure.txt`, await page.locator("body").innerText()); await page.screenshot({ path: `${output}/saas-company-contact-failure.png`, fullPage: true }); }
  throw error;
} finally { await browser?.close(); server?.kill(); }
