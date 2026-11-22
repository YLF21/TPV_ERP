import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import { chromium } from "playwright";
import { emptySummary, summaryPath } from "./summary-fixture.mjs";

// Every mail mutation is intercepted, including SMTP tests, OAuth and request notifications.
const root = fileURLToPath(new URL("..", import.meta.url));
const base = "http://127.0.0.1:5225/";
const settingsPath = "/api/v1/admin/smtp-settings";
const accountsPath = `${settingsPath}/accounts`;
const oauthPath = "/api/v1/admin/mail-oauth";
const requestPath = "/api/v1/admin/client-access-requests";
const screenshots = fileURLToPath(new URL("../../output/mail-redesign/", import.meta.url));
const server = spawn(process.execPath, [fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url)),
  "--host", "127.0.0.1", "--port", "5225", "--strictPort", "--configLoader", "runner"],
{ cwd: root, stdio: "ignore", windowsHide: true });
const initialId = "30000000-0000-4000-8000-000000000001";
const placeholder = { id: initialId, defaultAccount: true, enabled: false, host: "", port: 587, security: "STARTTLS",
  username: "", hasPassword: false, fromAddress: "", fromName: "", replyTo: "", portalUrl: "", version: 0,
  cryptoReady: true, configured: false, testReady: false, oauthProvider: null };
let browser;
async function until(check, name) {
  for (let i = 0; i < 100; i++) { if (await check()) return; await delay(100); }
  throw new Error(`Timed out: ${name}`);
}

async function setup(language = "es") {
  const page = await browser.newPage({ viewport: { width: 1440, height: 960 }, timezoneId: "Asia/Shanghai" });
  page.setDefaultTimeout(8_000);
  await page.addInitScript(lang => localStorage.setItem("tpv-saas-language", lang), language);
  const state = { calls: [], errors: [], accounts: [], sequence: 0, failLoad: false, pendingNotices: false,
    deleteInUse: false, saveConflict: false, testFailure: null, authorization: null, authorizationUrlOverride: null,
    providers: [{ provider: "GOOGLE", available: false }, { provider: "MICROSOFT", available: false }],
    entry: { id: "40000000-0000-4000-8000-000000000001", username: "CLIENT_MAIL_TEST", fullName: "Cliente de prueba",
      email: "client@example.test", status: "REJECTED", version: 1, createdAt: "2026-11-21T10:00:00Z", reviewedAt: "2026-11-21T12:00:00Z",
      reviewedBy: "ADMIN_TEST", taxId: "TEST-ID", phone: "900000000", address: "Calle de pruebas 1", postalCode: "35001",
      city: "Las Palmas", province: "Las Palmas", country: "España", requestedStores: [], notes: null, reviewReason: "Datos no verificados.",
      tenantUserId: null, delivery: { status: "FAILED", attempts: 8, lastError: "SMTP_AUTH_FAILED", nextAttemptAt: null, deliveredAt: null } },
  };
  page.on("pageerror", error => state.errors.push(error.message));
  await page.route("**/api/**", async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname, method = request.method();
    const body = request.postDataJSON(); state.calls.push({ path, method, body });
    const json = data => route.fulfill({ status: 200, contentType: "application/json", json: data });
    const error = (status, code) => route.fulfill({ status, contentType: "application/problem+json", json: { code, detail: "Hidden diagnostic must never appear" } });
    try {
      if (path === "/api/v1/auth/admin/login") return json({ username: "ADMIN_TEST", accessToken: "synthetic-smtp-token", mode: "admin", passwordChangeRequired: false });
      assert.equal(request.headers().authorization, "Bearer synthetic-smtp-token");
      if (path === "/api/v1/admin/me") return json({ username: "ADMIN_TEST", permissions: ["VIEW_ADMIN_DATA", "MANAGE_TENANT_USERS", "VIEW_REPORTS"] });
      const defaultAccount = state.accounts.find(account => account.defaultAccount) ?? placeholder;
      const queryAccount = state.accounts.find(account => account.id === url.searchParams.get("accountId"));
      if (path === `${oauthPath}/status`) return json({ providers: state.providers,
        connection: queryAccount?.oauthProvider ? { provider: queryAccount.oauthProvider, email: queryAccount.fromAddress } : null });
      if (path === `${oauthPath}/connect`) {
        const account = state.accounts.find(item => item.id === body.accountId);
        assert.equal(body.version, account.version);
        assert.equal(body.password, undefined);
        state.authorization = body;
        return json({ authorizationUrl: state.authorizationUrlOverride ?? (body.provider === "GOOGLE"
          ? "https://accounts.google.com/o/oauth2/v2/auth?state=synthetic"
          : "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?state=synthetic") });
      }
      if (path === `${oauthPath}/connection` && method === "DELETE") {
        assert.equal(Number(url.searchParams.get("version")), queryAccount.version);
        Object.assign(queryAccount, { oauthProvider: null, enabled: false, configured: false, testReady: false, version: queryAccount.version + 1 });
        return route.fulfill({ status: 204 });
      }
      if (path === settingsPath && method === "GET") return json(defaultAccount);
      if (path === `${settingsPath}/notices` && method === "PUT") {
        const selected = state.accounts.find(item => item.id === body.accountId);
        assert.equal(body.version, selected.version);
        assert.equal(body.defaultVersion, defaultAccount.version);
        assert.equal(body.defaultAccountId, defaultAccount.id);
        const switching = selected.id !== defaultAccount.id;
        if (switching && state.pendingNotices && !body.confirmPending) return error(409, "SMTP_NOTICES_PENDING");
        const version = Math.max(defaultAccount.version, selected.version) + 1;
        if (switching) Object.assign(defaultAccount, { defaultAccount: false, enabled: false, portalUrl: "", version: defaultAccount.version + 1 });
        Object.assign(selected, { defaultAccount: true, enabled: body.enabled, portalUrl: body.portalUrl,
          configured: selected.testReady && Boolean(body.portalUrl), version });
        return json(selected);
      }
      if (path === accountsPath && method === "GET") return state.failLoad ? error(503, "UNAVAILABLE") : json(state.accounts);
      const accountId = path.slice(accountsPath.length + 1).split("/")[0];
      const account = state.accounts.find(item => item.id === accountId);
      if ((path === accountsPath && method === "POST") || (account && method === "PUT")) {
        if (state.saveConflict) {
          state.saveConflict = false;
          account.version++;
          return error(409, "SMTP_VERSION_CONFLICT");
        }
        assert.equal(body.version, account?.version ?? 0);
        assert.equal(body.enabled, false);
        assert.equal(body.portalUrl, "");
        assert.equal(body.testReady, undefined);
        if (state.accounts.some(other => other.id !== accountId && other.fromAddress === body.fromAddress)) {
          return error(409, "SMTP_ACCOUNT_DUPLICATE");
        }
        const { password, clearPassword, portalUrl, enabled, ...visible } = body;
        const hasPassword = !clearPassword && (Boolean(password) || Boolean(account?.hasPassword));
        const testReady = Boolean(body.host && body.fromAddress && Boolean(body.username) === hasPassword);
        const createdId = `30000000-0000-4000-8000-${String(++state.sequence).padStart(12, "0")}`;
        const saved = { ...placeholder, ...account, ...visible, id: account?.id ?? createdId,
          defaultAccount: account?.defaultAccount ?? state.accounts.length === 0, version: body.version + 1,
          hasPassword, testReady, configured: testReady && Boolean(account?.portalUrl) };
        if (account) Object.assign(account, saved);
        else state.accounts.push(saved);
        return json(saved);
      }
      if (account && method === "DELETE") {
        assert.equal(Number(url.searchParams.get("version")), account.version);
        if (state.deleteInUse) return error(409, "SMTP_ACCOUNT_IN_USE");
        state.accounts = state.accounts.filter(item => item.id !== accountId);
        return route.fulfill({ status: 204 });
      }
      if (account && path.endsWith("/test")) {
        assert.equal(method, "POST");
        if (!account.testReady) return error(409, "SMTP_NOT_READY");
        return state.testFailure ? error(state.testFailure === "SMTP_TEST_RATE_LIMITED" ? 429 : 502, state.testFailure) : route.fulfill({ status: 204 });
      }
      if (path === requestPath) return json({ items: [state.entry], total: 1 });
      if (path === `${requestPath}/${state.entry.id}`) return json(state.entry);
      if (path === `${requestPath}/${state.entry.id}/retry-email`) {
        assert.equal(state.entry.delivery.status, "FAILED");
        state.entry.delivery = { status: "PENDING", attempts: 0, lastError: null, nextAttemptAt: null, deliveredAt: null };
        return json(state.entry);
      }
      if (path === "/api/v1/admin/communications") return json({ items: [], total: 0 });
      if (path === summaryPath) return json(emptySummary);
      if (path === `${summaryPath}/options`) return json({ companies: [], stores: [] });
      state.errors.push(`Unexpected ${method} ${path}`); return error(404, "UNEXPECTED");
    } catch (failure) {
      state.errors.push(failure.stack);
      return error(500, "MOCK_ASSERTION_FAILED");
    }
  });
  await page.goto(base, { waitUntil: "domcontentloaded" });
  await page.locator('input[autocomplete="username"]').fill("ADMIN_TEST");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  const label = language === "es" ? "Correo SMTP" : language === "en" ? "SMTP email" : "SMTP 邮件";
  await page.locator(".top-nav-list").getByRole("button", { name: label, includeHidden: true }).waitFor({ state: "attached" });
  await page.evaluate(() => { location.hash = "#/smtp"; });
  await page.getByRole("heading", { level: 1, name: label }).waitFor();
  return { page, state };
}

try {
  await until(async () => { try { return (await fetch(base)).ok; } catch { return false; } }, "Vite startup");
  browser = await chromium.launch({ headless: true });
  await mkdir(screenshots, { recursive: true });
  const { page, state } = await setup();
  const add = page.getByRole("button", { name: "Añadir correo" });
  const accountDialog = page.getByRole("dialog");
  const sender = accountDialog.getByLabel("Correo del remitente", { exact: true });
  const password = accountDialog.getByLabel("Contraseña SMTP", { exact: true });
  const provider = accountDialog.getByLabel("Proveedor SMTP", { exact: true });
  const connect = accountDialog.getByRole("button", { name: "Conectar correo", exact: true });
  const close = accountDialog.getByRole("button", { name: "Cerrar", exact: true });
  const accepted = accountDialog.getByText("Correo de prueba aceptado por el proveedor de correo.", { exact: true });
  await page.getByText("No hay cuentas de correo conectadas.").waitFor();
  await add.click();
  await page.getByRole("dialog", { name: "Añadir correo", exact: true }).waitFor();
  await sender.fill("notificaciones@example.test");
  await connect.click();
  await accountDialog.getByText("Introduce la contraseña para el usuario SMTP indicado.").waitFor();
  assert.equal(state.accounts.length, 0);
  assert.equal(await accountDialog.getByLabel("URL pública del portal cliente (HTTPS)").count(), 0);
  assert.equal(await accountDialog.getByLabel("Puerto", { exact: true }).isVisible(), false);
  await page.screenshot({ path: `${screenshots}mock-add-smtp.png` });
  await page.setViewportSize({ width: 700, height: 800 });
  await accountDialog.getByText("Ajustes avanzados", { exact: true }).click();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  assert.equal(await accountDialog.locator(".saas-workspace-dialog-body").evaluate(node =>
    node.scrollWidth <= node.clientWidth), true, "The SMTP form fits horizontally when advanced settings are open");
  await password.fill("synthetic-compact-password");
  await connect.click({ trial: true });
  await page.screenshot({ path: `${screenshots}mock-compact-smtp.png` });
  await password.fill("");
  await page.setViewportSize({ width: 1440, height: 960 });
  const presets = [
    ["gmail", "587", "STARTTLS", "https://myaccount.google.com/apppasswords"],
    ["yahoo", "465", "SSL", "https://login.yahoo.com/account/security"],
    ["qq", "465", "SSL", "https://mail.qq.com/"],
    ["icloud", "587", "STARTTLS", "https://account.apple.com/"],
    ["netease163", "465", "SSL", "https://mail.163.com/"],
    ["exmail", "465", "SSL", "https://exmail.qq.com/login"],
  ];
  for (const [id, port, security, helpUrl] of presets) {
    await provider.selectOption(id);
    assert.equal(await accountDialog.getByLabel("Puerto", { exact: true }).inputValue(), port);
    assert.equal(await accountDialog.getByLabel("Seguridad del transporte", { exact: true }).inputValue(), security);
    assert.equal(await accountDialog.locator(".smtp-help-link").getAttribute("href"), helpUrl);
    assert.equal(await accountDialog.locator(".smtp-help-link").getAttribute("rel"), "noopener noreferrer");
    if (id === "gmail") await password.fill("google-only-secret");
    if (id === "yahoo") assert.equal(await password.inputValue(), "", "Changing destination clears unsaved credentials");
  }
  await provider.selectOption("gmail");
  await password.fill("synthetic-gmail-password");
  await connect.click();
  await accountDialog.getByText("Cuenta de correo configurada.").waitFor();
  await page.getByRole("dialog", { name: "Enviar correo de prueba", exact: true }).waitFor();
  const firstAccount = state.accounts[0];
  assert.equal(firstAccount.testReady, true);
  assert.equal(firstAccount.enabled, false);
  assert.equal(firstAccount.configured, false);
  assert.equal(await password.count(), 0);
  assert.equal(state.calls.filter(call => call.path.endsWith("/test")).length, 0);
  const recipient = accountDialog.getByLabel("Destinatario de prueba");
  const confirm = accountDialog.getByRole("checkbox");
  const sendTest = accountDialog.getByRole("button", { name: "Enviar correo de prueba", exact: true });
  await recipient.fill("tester@example.test");
  assert.equal(await sendTest.isDisabled(), true);
  await confirm.check();
  await sendTest.click();
  await accepted.waitFor();
  assert.deepEqual(state.calls.filter(call => call.path.endsWith("/test")).map(call => ({ path: call.path, body: call.body })), [
    { path: `${accountsPath}/${firstAccount.id}/test`, body: { recipient: "tester@example.test" } },
  ]);
  state.testFailure = "SMTP_TEST_RATE_LIMITED";
  await confirm.check();
  await sendTest.click();
  await accountDialog.getByText("Espera 30 segundos").waitFor();
  state.testFailure = null;

  await accountDialog.getByRole("button", { name: "Editar conexión" }).click();
  await page.getByRole("dialog", { name: "Editar conexión", exact: true }).waitFor();
  await accountDialog.getByLabel("Nombre del remitente", { exact: true }).fill("esPOS Central");
  await connect.click();
  await accountDialog.getByText("Cuenta de correo configurada.").waitFor();
  await page.getByRole("dialog", { name: "Enviar correo de prueba", exact: true }).waitFor();
  assert.equal(state.calls.filter(call => call.method === "PUT").at(-1).body.password, undefined, "Blank fields keep the saved password");
  await accountDialog.getByRole("button", { name: "Editar conexión" }).click();
  await password.fill("   ");
  await connect.click();
  await accountDialog.getByText("Cuenta de correo configurada.").waitFor();
  assert.equal(state.calls.filter(call => call.method === "PUT").at(-1).body.password, "   ", "Preserve exact password whitespace");
  await accountDialog.getByRole("button", { name: "Editar conexión" }).click();
  await password.fill("new destination cannot inherit");
  await accountDialog.getByText("Ajustes avanzados", { exact: true }).click();
  const host = accountDialog.getByLabel("Servidor SMTP", { exact: true });
  await host.fill("smtp.example.test");
  assert.equal(await password.inputValue(), "");
  const changes = state.calls.filter(call => call.method === "PUT").length;
  await connect.click();
  await accountDialog.getByText("Introduce la contraseña para el usuario SMTP indicado.").waitFor();
  assert.equal(state.calls.filter(call => call.method === "PUT").length, changes);
  await host.fill("smtp.gmail.com");
  state.saveConflict = true;
  await accountDialog.getByLabel("Nombre del remitente", { exact: true }).fill("Borrador conservado");
  await connect.click();
  await accountDialog.getByText(/configuración del servidor ha cambiado/).waitFor();
  assert.equal(await accountDialog.getByLabel("Nombre del remitente", { exact: true }).inputValue(), "Borrador conservado");
  page.once("dialog", item => item.accept());
  await accountDialog.getByRole("button", { name: "Reintentar", exact: true }).click();
  await accountDialog.getByRole("button", { name: "Editar conexión", exact: true }).waitFor();
  await close.click();

  await add.click();
  await provider.selectOption("custom");
  await sender.fill("relay@example.test");
  await host.fill("relay.example.test");
  await connect.click();
  await accountDialog.getByText("Cuenta de correo configurada.").waitFor();
  const relay = state.accounts[1];
  assert.equal(relay.username, "");
  assert.equal(relay.hasPassword, false);
  assert.equal(relay.testReady, true, "Custom TLS relays can omit authentication");
  await close.click();
  assert.equal(await page.locator(".smtp-settings input, .smtp-settings fieldset").count(), 0);
  await page.screenshot({ path: `${screenshots}mock-connected-smtp.png` });

  await page.evaluate(() => { location.hash = "#/communications"; });
  await page.getByRole("button", { name: "Avisos automáticos", exact: true }).click();
  const notices = page.getByRole("dialog", { name: "Avisos automáticos" });
  const noticeSender = notices.getByLabel("Remitente de los avisos");
  await noticeSender.selectOption(relay.id);
  state.pendingNotices = true;
  await notices.getByLabel("Activar avisos de solicitudes de acceso").check();
  await notices.getByLabel("URL pública del portal cliente (HTTPS)").fill("https://clientes.example.test/tienda.html");
  page.once("dialog", item => item.dismiss());
  await notices.getByRole("button", { name: "Guardar", exact: true }).click();
  await until(() => notices.getByRole("button", { name: "Guardar", exact: true }).isEnabled(), "Declined pending-notice reassignment");
  assert.equal(firstAccount.defaultAccount, true);
  page.once("dialog", item => item.accept());
  await notices.getByRole("button", { name: "Guardar", exact: true }).click();
  await notices.getByText("Avisos automáticos guardados.").waitFor();
  assert.equal(relay.defaultAccount, true);
  assert.equal(relay.enabled, true);
  await noticeSender.selectOption(firstAccount.id);
  state.pendingNotices = false;
  await notices.getByRole("button", { name: "Guardar", exact: true }).click();
  await notices.getByText("Avisos automáticos guardados.").waitFor();
  assert.equal(firstAccount.defaultAccount, true);
  assert.equal(firstAccount.enabled, true);
  assert.equal(relay.enabled, false);
  await notices.getByRole("button", { name: "Cerrar", exact: true }).click();

  await page.evaluate(() => { location.hash = "#/smtp"; });
  await add.click();
  state.providers.forEach(item => { item.available = true; });
  await close.click();
  await add.click();
  await sender.fill("operator@example.test");
  await accountDialog.getByText("Otras formas de conexión", { exact: true }).click();
  await accountDialog.getByRole("button", { name: "Conectar Google", exact: true }).click();
  const authorizeLink = accountDialog.getByRole("link", { name: "Continuar en la página segura del proveedor", exact: true });
  await authorizeLink.waitFor();
  assert.equal(new URL(await authorizeLink.getAttribute("href")).hostname, "accounts.google.com");
  assert.equal(state.authorization.email, "operator@example.test");
  assert.equal(state.authorization.enabled, false);
  assert.equal(firstAccount.enabled, true, "Starting OAuth preserves automatic notices on the configured sender");
  const oauthAccount = state.accounts.find(item => item.id === state.authorization.accountId);
  Object.assign(oauthAccount, { username: oauthAccount.fromAddress, oauthProvider: "GOOGLE", testReady: true, version: oauthAccount.version + 1 });
  await accountDialog.getByRole("button", { name: "Actualizar autorización", exact: true }).click();
  await accountDialog.getByText("Cuenta de correo configurada.").waitFor();
  await accountDialog.getByRole("button", { name: "Editar conexión" }).click();
  await accountDialog.getByRole("button", { name: "Renovar autorización", exact: true }).waitFor();
  assert.equal(await sender.isEditable(), false);
  assert.equal(await provider.count(), 0);
  page.once("dialog", item => item.accept());
  await accountDialog.getByRole("button", { name: "Desconectar cuenta", exact: true }).click();
  await accountDialog.getByText("Cuenta desconectada.", { exact: true }).waitFor();
  await accountDialog.getByText("Otras formas de conexión", { exact: true }).click();
  await accountDialog.getByRole("button", { name: "Conectar Microsoft", exact: true }).click();
  await authorizeLink.waitFor();
  assert.equal(new URL(await authorizeLink.getAttribute("href")).hostname, "login.microsoftonline.com");
  await close.click();

  await page.getByRole("button", { name: "operator@example.test", exact: true }).click();
  await page.getByRole("dialog", { name: "Completar conexión", exact: true }).waitFor();
  state.authorizationUrlOverride = "https://accounts.google.com.attacker.example.test/authorize";
  await accountDialog.getByText("Otras formas de conexión", { exact: true }).click();
  await accountDialog.getByRole("button", { name: "Conectar Google", exact: true }).click();
  await accountDialog.getByText("No se pudo iniciar la autorización. Vuelve a intentarlo.", { exact: true }).waitFor();
  assert.equal(await authorizeLink.count(), 0, "Untrusted authorization hosts must never become links");
  await close.click();

  const remove = page.getByRole("button", { name: "Eliminar correo relay@example.test", exact: true });
  page.once("dialog", item => item.dismiss());
  await remove.click();
  assert.equal(state.accounts.includes(relay), true);
  state.deleteInUse = true;
  page.once("dialog", item => item.accept());
  await remove.click();
  await page.getByText("Hay comunicaciones pendientes o en curso con este correo.").waitFor();
  assert.equal(state.accounts.includes(relay), true);
  state.deleteInUse = false;
  page.once("dialog", item => item.accept());
  await remove.click();
  await page.getByText("Cuenta de correo eliminada.").waitFor();
  assert.equal(state.accounts.includes(relay), false);
  assert.equal(firstAccount.enabled, true);

  await page.evaluate(() => { location.hash = "#/client-users"; });
  await page.getByRole("row").filter({ hasText: "CLIENT_MAIL_TEST" }).dblclick();
  const dialog = page.getByRole("dialog");
  await dialog.getByText("Envío fallido", { exact: true }).waitFor();
  await dialog.getByRole("button", { name: "Reintentar envío", exact: true }).click();
  await dialog.getByText("En cola", { exact: true }).waitFor();
  assert.equal(state.calls.filter(call => call.path.endsWith("retry-email")).length, 1);
  assert.equal(await dialog.getByRole("button", { name: "Reintentar envío", exact: true }).count(), 0);
  state.entry.delivery = { status: "FAILED", attempts: 1, lastError: "SMTP_DELIVERY_UNCERTAIN", nextAttemptAt: null, deliveredAt: null };
  await dialog.getByRole("button", { name: "Actualizar estado del correo", exact: true }).click();
  await dialog.getByText(/No se pudo confirmar la entrega/).waitFor();
  page.once("dialog", item => item.dismiss());
  await dialog.getByRole("button", { name: "Reintentar envío", exact: true }).click();
  assert.equal(state.calls.filter(call => call.path.endsWith("retry-email")).length, 1);
  page.once("dialog", item => item.accept());
  await dialog.getByRole("button", { name: "Reintentar envío", exact: true }).click();
  await dialog.getByText("En cola", { exact: true }).waitFor();
  assert.equal(state.calls.filter(call => call.path.endsWith("retry-email")).length, 2);
  state.entry.delivery = { status: "DELIVERED", attempts: 1, lastError: null, nextAttemptAt: null, deliveredAt: "2026-11-21T12:34:00Z" };
  await dialog.getByRole("button", { name: "Actualizar estado del correo", exact: true }).click();
  await dialog.getByText("Aceptado por el proveedor de correo", { exact: true }).waitFor();
  assert.match(await dialog.innerText(), /12:34 UTC/);
  await dialog.screenshot({ path: `${screenshots}mock-decision-delivery.png` });
  await dialog.getByRole("button", { name: "Cerrar", exact: true }).click();

  assert.equal(await page.getByText("Hidden diagnostic must never appear").count(), 0);
  assert.deepEqual(state.errors, []);
  await page.close();
  for (const [language, addLabel] of [["en", "Add email account"], ["zh", "添加邮箱"]]) {
    const localized = await setup(language);
    await localized.page.getByRole("button", { name: addLabel, exact: true }).click();
    assert.equal(await localized.page.getByRole("dialog", { name: addLabel, exact: true }).locator('input[type="password"]').count(), 1);
    assert.deepEqual(localized.state.errors, []);
    await localized.page.close();
  }
  console.log("Mail E2E passed: multiple accounts, SMTP presets, editing and testing, safe OAuth links, notification sender changes, confirmed deletion, UTC and ES/EN/ZH.");
} catch (error) {
  for (const page of browser?.contexts().flatMap(context => context.pages()) ?? []) {
    await page.screenshot({ path: `${screenshots}mock-smtp-failure.png`, fullPage: true });
    console.error("Failure location:", page.url(), (await page.locator("body").innerText()).slice(-2500));
  }
  throw error;
} finally {
  await browser?.close();
  server.kill();
}
