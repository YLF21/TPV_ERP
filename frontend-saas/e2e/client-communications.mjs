import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import { chromium } from "playwright";
import { emptySummary, summaryPath } from "./summary-fixture.mjs";

// All authentication, customer data and mail operations are intercepted.
const root = fileURLToPath(new URL("..", import.meta.url));
const base = "http://127.0.0.1:5226";
const api = "/api/v1/admin/communications";
const screenshots = fileURLToPath(new URL("../../output/mail-redesign/", import.meta.url));
const server = spawn(process.execPath, [
  fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url)),
  "--host", "127.0.0.1", "--port", "5226", "--strictPort", "--configLoader", "runner",
], { cwd: root, stdio: "ignore", windowsHide: true });

const draftId = "7bd461b6-8948-4370-a866-215000000001";
const reviewId = "7bd461b6-8948-4370-a866-215000000002";
const accountsPath = "/api/v1/admin/smtp-settings/accounts";
const primarySender = { id: "7bd461b6-8948-4370-a866-215000000003", fromAddress: "central@example.test", fromName: "Central", testReady: true, defaultAccount: true };
const sender = { ...primarySender, id: "7bd461b6-8948-4370-a866-215000000004", fromAddress: "avisos@example.test", fromName: "esPOS", defaultAccount: false };
const replacement = { ...sender, id: "7bd461b6-8948-4370-a866-215000000005", fromAddress: "nueva@example.test" };
let accounts = [primarySender, sender];
let removeSenderOnSave = false;
const timestamp = "2026-11-21T10:12:13Z";
const title = "Horario de atención";
const cleanHtml = '<p><strong>Horario actualizado</strong></p><p><a href="https://portal.example.test/horario">Más información</a></p>';
const plainText = "Horario actualizado\nMás información https://portal.example.test/horario";
const unsafeHtml = '<img src="https://tracking.example.test/hidden"><script>alert(1)</script><a href="javascript:alert(2)">Imagen</a>';
const audience = Array.from({ length: 27 }, (_, index) => {
  const ordinal = String(index + 1).padStart(2, "0");
  return {
    id: `9bd461b6-8948-4370-a866-2150000000${ordinal}`, label: `Cliente ${ordinal}`,
    email: `cliente${ordinal}@example.test`, eligible: true, reason: null,
    companyNames: index === 0 ? ["Empresa Norte", "Empresa Sur"] : [`Empresa ${ordinal}`],
    sources: index === 0 ? ["COMPANY_CONTACT", "COMPANY_OWNER"] : index === 25 ? ["COMPANY_OWNER"] : ["CLIENT_USER"],
  };
});
const absent = { ...audience[0], id: "omitted", label: "Sin dirección", email: null, eligible: false, reason: "MISSING_EMAIL", companyNames: [], sources: ["CLIENT_USER"] };
const delivery = audience.map(({ id, label, email }) => ({ id, label, email, status: "REVIEW", attempts: 0, lastError: null, sentAt: null }));
const metrics = { pending: 0, sending: 0, sent: 0, failed: 0, uncertain: 0, cancelled: 0 };
const errors = [];
const calls = [];
let draft;
let reviewed = [];
let sendPayload;
let browser;

function paged(url, items) {
  const size = Number(url.searchParams.get("size"));
  const page = Number(url.searchParams.get("page"));
  assert.equal(size, 25);
  return { items: items.slice(page * size, (page + 1) * size), total: items.length };
}

async function mockApi(route) {
  const request = route.request();
  const url = new URL(request.url());
  const path = url.pathname;
  const method = request.method();
  const body = request.postDataJSON();
  const json = data => route.fulfill({ status: 200, contentType: "application/json", json: data });
  const error = (status, code) => route.fulfill({ status, contentType: "application/problem+json", json: { code, detail: "private-provider-diagnostic" } });
  if (path === "/api/v1/auth/admin/login") return json({ username: "COMMS_TEST", accessToken: "synthetic-communications", mode: "admin" });
  assert.equal(request.headers().authorization, "Bearer synthetic-communications");
  if (path === "/api/v1/admin/me") return json({ username: "COMMS_TEST", permissions: [] });
  if (path === summaryPath) return json(emptySummary);
  if (path === `${summaryPath}/options`) return json({ companies: [], stores: [] });
  calls.push({ path, method, body });
  if (path === accountsPath && method === "GET") return json(accounts);
  if (path === api && method === "GET") return json(paged(url, draft ? [draft] : []));
  if (path === api && method === "POST") {
    assert.equal(body.senderAccountId, sender.id);
    if (removeSenderOnSave) {
      accounts = [primarySender, replacement];
      return error(404, "SMTP_ACCOUNT_NOT_FOUND");
    }
    assert.equal(draft, undefined);
    assert.equal(body.subject, title);
    assert.equal(body.html, cleanHtml + unsafeHtml);
    assert.equal(body.audienceMode, "SELECTED");
    assert.deepEqual(body.recipientIds, [audience[0].id, audience[25].id]);
    draft = {
      ...body, id: draftId, html: cleanHtml, text: plainText, status: "DRAFT", version: 1,
      createdAt: timestamp, updatedAt: timestamp, createdBy: "COMMS_TEST", counts: { ...metrics },
    };
    return json(draft);
  }
  if (path === `${api}/audience`) {
    const q = url.searchParams.get("q").toLowerCase();
    const items = [...audience, absent].filter(row => [row.label, row.email, ...row.companyNames].join(" ").toLowerCase().includes(q));
    return json(paged(url, items));
  }
  if (path === `${api}/preview`) {
    assert.equal(body.html, cleanHtml + unsafeHtml);
    // Also exercise browser isolation if an unexpected remote element reached the preview response.
    return json({ html: cleanHtml + '<img src="https://tracking.example.test/from-response">', text: plainText });
  }
  const item = `${api}/${draftId}`;
  if (path === item && method === "GET") return json(draft);
  if (path === item && method === "PUT") {
    assert.equal(body.senderAccountId, sender.id);
    assert.equal(body.version, draft.version);
    assert.equal(body.audienceMode, "ALL");
    assert.deepEqual(body.recipientIds, []);
    draft = { ...draft, ...body, version: draft.version + 1 };
    return json(draft);
  }
  if (path === `${item}/test`) {
    assert.equal(draft.senderAccountId, sender.id);
    assert.deepEqual(body, { version: 1, recipient: "prueba@example.test" });
    return route.fulfill({ status: 204 });
  }
  if (path === `${item}/review`) {
    assert.equal(body.version, draft.version);
    reviewed = draft.audienceMode === "ALL" ? delivery : delivery.filter(row => draft.recipientIds.includes(row.id));
    return json({
      reviewId, version: draft.version, subject: title, html: cleanHtml, text: plainText, recipientCount: reviewed.length,
      omittedCount: draft.audienceMode === "ALL" ? 1 : 0, duplicateCount: draft.audienceMode === "ALL" ? 1 : 0,
      smtpReady: true, fromAddress: "avisos@example.test", fromName: "esPOS", replyTo: "soporte@example.test", smtpVersion: 4,
      senderAccountId: draft.senderAccountId,
    });
  }
  if (path === `${item}/recipients`) {
    if (url.searchParams.has("reviewId")) {
      assert.equal(url.searchParams.get("reviewId"), reviewId);
      return json(paged(url, reviewed));
    }
    return json(paged(url, delivery));
  }
  if (path === `${item}/send`) {
    if (sendPayload) {
      assert.deepEqual(body, sendPayload, "uncertain response retries the same review and version");
      return json(draft);
    }
    assert.deepEqual(body, { version: draft.version, reviewId, recipientCount: 27 });
    sendPayload = body;
    draft = { ...draft, status: "IN_PROGRESS", version: draft.version + 1, fromAddress: sender.fromAddress, fromName: sender.fromName,
      counts: { ...metrics, sent: 1, uncertain: 1, failed: 1, pending: 24 } };
    delivery.forEach((row, index) => Object.assign(row, {
      status: ["SENT", "UNCERTAIN", "FAILED"][index] ?? "PENDING",
      attempts: index < 3 ? 2 : 0,
      sentAt: index === 0 ? timestamp : null,
      lastError: [null, "SMTP_DELIVERY_UNCERTAIN", "SMTP_SENDER_CHANGED"][index] ?? null,
    }));
    return error(504, "TIMEOUT");
  }
  if (path === `${item}/recipients/${audience[1].id}/retry`) {
    assert.deepEqual(body, { attempts: 2, confirmUncertain: true });
    Object.assign(delivery[1], { status: "PENDING", lastError: null });
    draft = { ...draft, version: draft.version + 1, counts: { ...draft.counts, uncertain: 0, pending: 25 } };
    return json(draft);
  }
  if (path === `${item}/cancel`) {
    assert.equal(body.version, draft.version);
    draft = { ...draft, status: "CANCELLED", version: draft.version + 1, counts: { ...draft.counts, pending: 0, cancelled: 25 } };
    for (const row of delivery) if (row.status === "PENDING") row.status = "CANCELLED";
    return json(draft);
  }
  errors.push(`Unexpected request: ${method} ${path}`);
  return error(404, "UNEXPECTED");
}

async function start() {
  for (let attempt = 0; attempt < 100; attempt++) {
    try { if ((await fetch(base)).ok) return; } catch {}
    await delay(100);
  }
  throw new Error("Vite did not start");
}

async function openWorkspace(context, language, label) {
  const page = await context.newPage();
  page.setDefaultTimeout(8_000);
  page.on("pageerror", error => errors.push(error.message));
  await page.addInitScript(lang => {
    if (window === top) localStorage.setItem("tpv-saas-language", lang);
  }, language);
  await page.goto(base, { waitUntil: "domcontentloaded" });
  await page.locator('input[autocomplete="username"]').fill("COMMS_TEST");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  const nav = page.locator(".top-nav-list");
  await nav.getByRole("button", { name: label, exact: true, includeHidden: true }).waitFor({ state: "attached" });
  if (language === "es") {
    await nav.getByRole("button", { name: "Clientes", exact: true }).click();
    await nav.getByRole("button", { name: label, exact: true }).click();
  } else await page.evaluate(() => { location.hash = "#/communications"; });
  await page.getByRole("heading", { level: 1, name: label }).waitFor();
  return page;
}

async function assertTableLayout(page, label) {
  const table = page.getByRole("table", { name: label, exact: true });
  const scroll = table.locator("..");
  const menu = table.locator(".saas-layout-menu-trigger").first();
  await table.waitFor();
  await page.mouse.move(4, 4);
  const canHover = await page.evaluate(() => matchMedia("(any-hover: hover)").matches);
  assert.equal(await menu.evaluate(node => getComputedStyle(node).opacity), canHover ? "0" : "1",
    "Column actions are quiet when hover is available and visible on touch-only devices");
  await menu.focus();
  assert.equal(await menu.evaluate(node => getComputedStyle(node).opacity), "1", "Keyboard focus reveals column actions");
  await menu.blur();
  return (await scroll.boundingBox()).height;
}

try {
  await start();
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 960 }, timezoneId: "Asia/Shanghai" });
  const blocked = [];
  await context.route("**/*", route => {
    if (new URL(route.request().url()).origin === base) return route.continue();
    blocked.push(route.request().url());
    return route.abort("blockedbyclient");
  });
  await context.route("**/api/**", async route => {
    try {
      return await mockApi(route);
    } catch (error) {
      errors.push(error.stack);
      return route.fulfill({ status: 500, body: "Mock contract error" });
    }
  });
  await mkdir(screenshots, { recursive: true });
  const page = await openWorkspace(context, "es", "Comunicaciones");
  await page.getByText("Todavía no hay comunicaciones.").waitFor();
  const historyHeight = await assertTableLayout(page, "Historial de comunicaciones");
  assert.ok(historyHeight > 350, "The history keeps its workspace height with no results");
  await page.screenshot({ path: `${screenshots}mock-history-empty.png` });
  const hybridContext = await browser.newContext({ viewport: { width: 1440, height: 960 }, hasTouch: true, isMobile: false });
  await hybridContext.route("**/api/**", mockApi);
  const hybrid = await openWorkspace(hybridContext, "es", "Comunicaciones");
  await hybrid.getByText("Todavía no hay comunicaciones.").waitFor();
  assert.equal(await assertTableLayout(hybrid, "Historial de comunicaciones"), historyHeight);
  await hybrid.screenshot({ path: `${screenshots}mock-history-hybrid.png` });
  await hybridContext.close();
  await page.getByRole("button", { name: "Redactar correo", exact: true }).click();
  const senderSelect = page.getByLabel("De", { exact: true });
  assert.equal(await senderSelect.inputValue(), "", "A draft starts without an implicit sender");
  await senderSelect.selectOption(sender.id);
  const subject = page.getByLabel("Asunto", { exact: true });
  await subject.fill(title);
  const visual = page.getByRole("textbox", { name: "Mensaje" });
  await visual.fill("Un texto");
  await visual.press("Control+a");
  await page.getByRole("button", { name: "Negrita" }).click();
  assert.match(await visual.innerHTML(), /<(b|strong)>Un texto<\/(b|strong)>/i);
  await page.getByRole("button", { name: "Insertar enlace" }).click();
  await page.getByLabel("Dirección del enlace").fill("javascript:alert(1)");
  await page.getByRole("button", { name: "Insertar", exact: true }).click();
  await page.getByText("Introduce una dirección válida", { exact: false }).waitFor();
  await page.getByLabel("Dirección del enlace").fill("https://portal.example.test/");
  await page.getByRole("button", { name: "Insertar", exact: true }).click();
  assert.match(await visual.innerHTML(), /href="https:\/\/portal.example.test\//);

  const cancelNavigation = new Promise(resolve => page.once("dialog", async dialog => {
    assert.equal(dialog.type(), "confirm");
    assert.match(dialog.message(), /cambios sin guardar/);
    await dialog.dismiss();
    resolve();
  }));
  await page.evaluate(() => history.back());
  await cancelNavigation;
  await page.waitForURL("**/#/communications");
  assert.equal(await subject.inputValue(), title, "browser Back retains the open draft after dismissal");
  const refreshGuard = new Promise(resolve => page.once("dialog", async dialog => {
    assert.equal(dialog.type(), "beforeunload");
    await dialog.dismiss();
    resolve();
  }));
  await page.evaluate(() => location.reload()).catch(() => {});
  await refreshGuard;
  assert.equal(await subject.inputValue(), title, "refresh retains unsaved text when dismissed");

  await page.getByRole("button", { name: "Código HTML" }).click();
  await page.getByRole("textbox", { name: "Mensaje" }).fill(cleanHtml + unsafeHtml);
  await page.getByRole("button", { name: "Editor visual" }).click();
  await page.getByText("Este formato requiere el editor HTML", { exact: false }).waitFor();
  await page.getByRole("button", { name: "Vista previa", exact: true }).click();
  const preview = page.getByRole("dialog", { name: "Vista previa" });
  const previewFrame = preview.locator("iframe");
  const previewBody = previewFrame.contentFrame().locator("body");
  await previewBody.getByText("Horario actualizado").waitFor();
  assert.equal(await previewBody.locator("a").getAttribute("href"), null);
  assert.equal(await previewBody.locator("img,script").count(), 0);
  assert.equal(await previewFrame.getAttribute("sandbox"), "");
  const srcdoc = await previewFrame.getAttribute("srcdoc");
  assert.match(srcdoc, /default-src 'none'/);
  await preview.getByRole("button", { name: "Cerrar" }).click();

  const nginx = await readFile(new URL("../nginx.conf", import.meta.url), "utf8");
  const productionCsp = nginx.match(/add_header Content-Security-Policy "([^"]+)"/)[1];
  const cspPage = await context.newPage();
  await cspPage.route(`${base}/communications-csp-check`, route => route.fulfill({
    status: 200, contentType: "text/html", headers: { "Content-Security-Policy": productionCsp }, body: "<!doctype html><title>CSP check</title>",
  }));
  await cspPage.goto(`${base}/communications-csp-check`);
  await cspPage.evaluate(source => {
    const iframe = document.createElement("iframe");
    iframe.setAttribute("sandbox", "");
    iframe.srcdoc = source;
    document.body.append(iframe);
  }, srcdoc);
  await cspPage.locator("iframe").contentFrame().getByText("Horario actualizado").waitFor();
  await cspPage.close();

  const chooseRecipients = page.getByRole("button", { name: "Elegir destinatarios" });
  const applyRecipients = page.getByRole("button", { name: "Aplicar destinatarios" });
  await page.setViewportSize({ width: 700, height: 800 });
  await chooseRecipients.click();
  assert.equal(await page.getByRole("radio", { name: "Seleccionar contactos" }).isChecked(), true);
  assert.equal(await page.getByRole("radio", { name: "Todos los contactos" }).isChecked(), false);
  const audienceDialog = page.getByRole("dialog", { name: "Destinatarios", exact: true });
  assert.equal(await audienceDialog.locator(".saas-workspace-dialog-body").evaluate(node =>
    node.scrollWidth <= node.clientWidth), true, "Recipient table scrolling stays within the table");
  await applyRecipients.click({ trial: true });
  await page.screenshot({ path: `${screenshots}mock-compact-recipients.png` });
  await page.setViewportSize({ width: 1440, height: 960 });
  const audienceHeight = await assertTableLayout(page, "Destinatarios");
  await page.getByRole("searchbox", { name: "Buscar contactos o empresas" }).fill("sin coincidencias");
  await page.getByText("No se encontraron contactos.", { exact: true }).waitFor();
  assert.equal(await assertTableLayout(page, "Destinatarios"), audienceHeight);
  await page.screenshot({ path: `${screenshots}mock-audience-empty.png` });
  await page.getByRole("searchbox", { name: "Buscar contactos o empresas" }).fill("");
  await page.getByRole("checkbox", { name: "Seleccionar Cliente 01", exact: true }).check();
  const audienceSelected = page.getByRole("row").filter({ hasText: "Cliente 01" });
  await audienceSelected.focus();
  assert.equal(await audienceSelected.evaluate(node => getComputedStyle(node).fontWeight), "700");
  assert.match(await audienceSelected.innerText(), /Empresa Norte · Empresa Sur/);
  assert.match(await audienceSelected.innerText(), /Contacto · Propietario/);
  await page.getByRole("searchbox", { name: "Buscar contactos o empresas" }).fill("Empresa Sur");
  await page.getByRole("checkbox", { name: "Seleccionar Cliente 01", exact: true }).waitFor();
  await page.getByRole("checkbox", { name: "Seleccionar Cliente 02", exact: true }).waitFor({ state: "hidden" });
  assert.equal(await audienceDialog.getByRole("table").locator("tbody tr").count(), 1);
  assert.equal(await page.getByRole("checkbox", { name: "Seleccionar Cliente 01", exact: true }).isChecked(), true);
  await page.getByRole("searchbox", { name: "Buscar contactos o empresas" }).fill("");
  await page.getByRole("checkbox", { name: "Seleccionar Cliente 02", exact: true }).waitFor();
  await page.screenshot({ path: `${screenshots}mock-company-contacts.png` });
  await page.getByRole("button", { name: "Siguiente", exact: true }).click();
  await page.getByRole("checkbox", { name: "Seleccionar Cliente 26", exact: true }).check();
  assert.equal(await page.getByRole("checkbox", { name: "Seleccionar Sin dirección" }).isDisabled(), true);
  await page.getByRole("searchbox", { name: "Buscar contactos o empresas" }).fill("Cliente 01");
  await page.getByRole("checkbox", { name: "Seleccionar Cliente 01", exact: true }).waitFor();
  assert.equal(await page.getByRole("checkbox", { name: "Seleccionar Cliente 01", exact: true }).isChecked(), true);
  await page.getByText("Seleccionados 2", { exact: true }).waitFor();
  await applyRecipients.click();
  await page.getByRole("button", { name: "Guardar borrador" }).click();
  await page.getByText("Borrador guardado.").waitFor();
  await page.getByRole("button", { name: "Volver al historial" }).click();
  assert.equal(await assertTableLayout(page, "Historial de comunicaciones"), historyHeight);
  await page.getByRole("button", { name: `Abrir ${title}`, exact: true }).click();
  await page.getByText("Seleccionados 2", { exact: true }).waitFor();
  assert.equal(await senderSelect.inputValue(), sender.id);
  await page.screenshot({ path: `${screenshots}mock-composer.png` });
  await page.setViewportSize({ width: 700, height: 800 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  const main = page.locator(".main-panel");
  assert.equal(await main.evaluate(node => node.scrollWidth <= node.clientWidth), true, "The composer fits horizontally");
  await chooseRecipients.scrollIntoViewIfNeeded();
  await page.screenshot({ path: `${screenshots}mock-compact-composer.png` });
  await page.getByRole("button", { name: "Revisar envío", exact: true }).click({ trial: true });
  await page.screenshot({ path: `${screenshots}mock-compact-composer-actions.png` });
  await page.setViewportSize({ width: 1440, height: 960 });

  await page.getByRole("button", { name: "Enviar correo de prueba", exact: true }).click();
  const test = page.getByRole("dialog", { name: "Enviar correo de prueba" });
  await test.getByLabel("Destinatario de prueba").fill("prueba@example.test");
  const testSend = test.getByRole("button", { name: "Enviar correo de prueba", exact: true });
  assert.equal(await testSend.isDisabled(), true);
  await test.getByRole("checkbox").check();
  await testSend.click();
  await page.getByText("El proveedor aceptó el correo de prueba.").waitFor();

  await page.getByRole("button", { name: "Revisar envío", exact: true }).click();
  const review = page.getByRole("dialog", { name: "Revisar envío" });
  await review.getByText("cliente26@example.test", { exact: true }).waitFor();
  assert.equal(await review.getByRole("table").locator("tbody tr").count(), 2);
  await review.getByRole("button", { name: "Cerrar" }).click();
  await chooseRecipients.click();
  await page.getByRole("radio", { name: "Todos los contactos" }).check();
  await applyRecipients.click();
  await page.getByRole("button", { name: "Revisar envío", exact: true }).click();
  await review.getByText("esPOS <avisos@example.test>", { exact: true }).waitFor();
  await review.getByText("cliente01@example.test", { exact: true }).waitFor();
  assert.match(await review.locator("dl").nth(1).innerText(), /Destinatarios\n27\nOmitidos\n1\nCorreos duplicados\n1/);
  const confirm = review.getByRole("checkbox");
  const send = review.getByRole("button", { name: "Confirmar envío" });
  assert.equal(await send.isDisabled(), true);
  await review.getByRole("button", { name: "Siguiente", exact: true }).click();
  await review.getByText("cliente27@example.test", { exact: true }).waitFor();
  assert.equal(await review.getByRole("table").locator("tbody tr").count(), 2);
  await confirm.check();
  await page.screenshot({ path: `${screenshots}mock-review.png` });
  assert.equal(calls.filter(call => call.path.endsWith("/send")).length, 0);
  await send.click();
  await review.getByText("No se pudo confirmar el envío.", { exact: false }).waitFor();
  assert.equal(await review.getByText("private-provider-diagnostic").count(), 0);
  await send.click();
  await page.getByText("El correo se ha puesto en cola.").waitFor();
  await page.getByText("21/11/2026 10:12 UTC", { exact: true }).last().waitFor();
  await page.getByText("El remitente ha cambiado.", { exact: false }).waitFor();
  await page.getByText("esPOS <avisos@example.test>", { exact: true }).waitFor();
  await page.screenshot({ path: `${screenshots}mock-delivery.png` });
  const retry = page.getByRole("button", { name: "Reintentar Cliente 02", exact: true });
  page.once("dialog", dialog => dialog.dismiss());
  await retry.click();
  assert.equal(calls.filter(call => call.path.endsWith("/retry")).length, 0);
  page.once("dialog", async dialog => {
    assert.match(dialog.message(), /cliente02@example.test/);
    await dialog.accept();
  });
  await retry.click();
  await page.getByText("El destinatario se ha puesto de nuevo en cola.").waitFor();
  page.once("dialog", dialog => dialog.accept());
  await page.getByRole("button", { name: "Cancelar pendientes" }).click();
  await page.getByText("Se han cancelado los envíos pendientes.").waitFor().catch(async error => {
    throw new Error(`Cancel failed: ${JSON.stringify({ alerts: await page.getByRole("alert").allTextContents(), lastCalls: calls.slice(-5), errors })}`, { cause: error });
  });
  assert.equal(await page.getByRole("button", { name: "Reintentar Cliente 03", exact: true }).count(), 0);
  await page.getByRole("button", { name: "Volver al historial" }).click();
  await page.getByRole("table", { name: "Historial de comunicaciones" }).getByText("Cancelado", { exact: true }).waitFor();

  await page.getByRole("button", { name: "Redactar correo", exact: true }).click();
  await senderSelect.selectOption(sender.id);
  await subject.fill("Texto que se conserva");
  await visual.fill("Borrador");
  removeSenderOnSave = true;
  await page.getByRole("button", { name: "Guardar borrador" }).click();
  await page.getByText("El remitente guardado no está disponible para enviar.", { exact: false }).first().waitFor();
  assert.equal(await page.getByRole("button", { name: "Revisar envío" }).isDisabled(), true);
  assert.equal(await senderSelect.inputValue(), sender.id, "A disappeared sender must never be replaced implicitly");
  await senderSelect.selectOption(replacement.id);
  assert.equal(await subject.inputValue(), "Texto que se conserva");
  assert.equal(await page.getByRole("button", { name: "Revisar envío" }).isEnabled(), true);
  page.once("dialog", dialog => dialog.accept());
  await page.getByRole("button", { name: "Volver al historial" }).click();

  for (const [language, label, compose] of [["en", "Communications", "Compose email"], ["zh", "客户通信", "撰写邮件"]]) {
    const translated = await openWorkspace(context, language, label);
    await translated.getByRole("button", { name: compose, exact: true }).click();
    await translated.getByRole("heading", { name: compose, exact: true }).waitFor();
    await translated.close();
  }
  assert.deepEqual(errors, []);
  assert.deepEqual(blocked, []);
  console.log("Communications E2E passed: editing, navigation guards, safe preview with production CSP, persistent selection, test, recipient review, idempotent send, retry, cancellation, UTC and ES/EN/ZH.");
} finally {
  await browser?.close();
  server.kill();
}
