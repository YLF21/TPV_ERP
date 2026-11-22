import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

// Browser-only fixtures: no live credentials, accounts or business data.
const root = fileURLToPath(new URL("..", import.meta.url));
const base = "http://127.0.0.1:5223/";
const loginPath = "/api/v1/auth/tenant/login";
const server = spawn(process.execPath, [fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url)),
  "--host", "127.0.0.1", "--port", "5223", "--strictPort", "--configLoader", "runner"],
{ cwd: root, stdio: "ignore", windowsHide: true });
let browser;
try {
  let ready = false;
  for (let attempt = 0; attempt < 120; attempt++) {
    if (server.exitCode !== null) throw new Error("Portal test server failed to start");
    try { ready = (await fetch(base)).ok; } catch { /* Startup only. */ }
    if (ready) break;
    await delay(100);
  }
  assert.equal(ready, true);
  browser = await chromium.launch({ headless: true });

  async function setup(mode, passwordChangeRequired = false) {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    page.setDefaultTimeout(10_000);
    const calls = [], errors = [];
    page.on("pageerror", error => errors.push(error.message));
    await page.addInitScript(() => {
      localStorage.setItem("tpv-saas-language", "es");
      window.__portalMounted = false;
      new MutationObserver(() => { window.__portalMounted ||= Boolean(document.querySelector(".tenant-shell")); })
        .observe(document, { subtree: true, childList: true });
    });
    const session = { username: "CLIENT_PORTAL_TEST", mode, accessToken: `synthetic-${mode}-token`,
      passwordChangeRequired, expiresAt: "2099-01-01T00:00:00Z" };
    await page.route("**/api/**", async route => {
      const request = route.request(), path = new URL(request.url()).pathname;
      calls.push({ path, method: request.method(), authorization: request.headers().authorization });
      const json = data => route.fulfill({ status: 200, contentType: "application/json", json: data });
      if (path === loginPath) return json(session);
      if (path === "/api/v1/auth/logout") {
        assert.equal(request.headers().authorization, `Bearer ${session.accessToken}`);
        return route.fulfill({ status: 204 });
      }
      if (path === "/api/v1/tenant/access" && mode === "tenant" && !passwordChangeRequired) {
        return json({ username: session.username, companies: [] });
      }
      errors.push(`Unexpected API access: ${path}`);
      return route.fulfill({ status: 403 });
    });
    await page.goto(`${base}tienda.html`, { waitUntil: "domcontentloaded", timeout: 30_000 });
    await page.locator('input[autocomplete="username"]').fill(session.username);
    await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
    await page.locator('form button[type="submit"]').click();
    return { page, calls, errors };
  }

  for (const passwordChangeRequired of [false, true]) {
    const { page, calls, errors } = await setup("admin", passwordChangeRequired);
    await page.getByRole("alert").waitFor();
    assert.equal(await page.evaluate(() => window.__portalMounted), false);
    assert.equal(await page.locator("#password-change-title, .saas-dashboard, .tenant-shell").count(), 0);
    assert.deepEqual(calls.map(call => call.path), [loginPath, "/api/v1/auth/logout"]);
    assert.deepEqual(errors, []);
    await page.close();
  }

  const valid = await setup("tenant");
  await valid.page.locator(".tenant-shell").waitFor();
  await valid.page.getByRole("button", { name: "Actualizar", exact: true }).waitFor({ state: "visible" });
  assert.equal(valid.calls.some(call => call.path.startsWith("/api/v1/admin/")), false);
  assert.equal(valid.calls[0].path, loginPath);
  assert.equal(valid.calls.some(call => call.path === "/api/v1/tenant/access"), true);
  assert.deepEqual(valid.errors, []);
  await valid.page.close();

  const pending = await setup("tenant", true);
  await pending.page.locator("#password-change-title").waitFor();
  assert.deepEqual(pending.calls.map(call => call.path), [loginPath], "Password change precedes business-data access");
  assert.equal(await pending.page.evaluate(() => window.__portalMounted), false);
  assert.deepEqual(pending.errors, []);
  await pending.page.close();
  console.log("Portal separation E2E passed: dedicated customer login, staff sessions rejected/revoked, no cross-portal data, mandatory password gate preserved.");
} finally {
  await browser?.close();
  server.kill();
}
