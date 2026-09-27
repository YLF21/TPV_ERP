import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";
import { setup, companyId } from "./failure-workflow-fixture.mjs";

const base = "http://127.0.0.1:5197/";
const server = spawn(process.execPath, [fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url)), "--host", "127.0.0.1", "--port", "5197", "--strictPort", "--configLoader", "runner"], { cwd: fileURLToPath(new URL("..", import.meta.url)), stdio: "ignore", windowsHide: true });
let browser;
try {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error("Notification test server did not start");
    try { if ((await fetch(base)).ok) break; } catch {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({ headless: true });
  const errors = [];
  const { page } = await setup(browser, errors, base, undefined, true);
  const readIds = new Set(["already-read"]);
  const notices = ["already-read", "pending", "save-error", "late-result"];
  let rejectSave = false;
  let writeCount = 0;
  let delayGet = null;
  let delayPut = null;
  let getStarted;
  let putStarted;
  await page.route("**/api/v1/admin/notifications**", async route => {
    const request = route.request();
    const json = (value, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(value) });
    if (request.method() === "GET") {
      const snapshot = notices.map(id => ({ id, companyId, companyName: "Demo notification", severity: "WARNING", title: "Notice " + id, detail: "Synthetic notification", createdAt: "2026-09-27T10:00:00Z", read: readIds.has(id) }));
      getStarted?.();
      if (delayGet) await delayGet;
      return json(snapshot);
    }
    assert.equal(request.method(), "PUT");
    writeCount++;
    putStarted?.();
    if (delayPut) await delayPut;
    if (rejectSave) return json({ detail: "Cannot save notification" }, 503);
    const id = decodeURIComponent(new URL(request.url()).pathname.split("/").at(-2));
    readIds.add(id);
    return route.fulfill({ status: 204 });
  });
  await page.route("**/api/v1/admin/technical-status", route => route.fulfill({ json: { companies: 1, licenses: 1, eventsToday: 0, openTickets: 0, installations: 1, staleInstallations: 1 } }));
  const card = id => page.locator(".notification-card").filter({ hasText: "Notice " + id });
  const mark = id => card(id).getByRole("button", { name: "Marcar leida", exact: true });
  const success = page.locator(".notice.success");
  const openSupport = async () => {
    const section = page.locator(".top-nav-list").getByRole("button", { name: "Supervisión", exact: true });
    if (await section.getAttribute("aria-expanded") !== "true") await section.click();
    await page.locator(".top-nav-list").getByRole("button", { name: "Soporte", exact: true }).click();
    await card("save-error").waitFor();
  };
  const home = () => page.locator(".top-nav-list").getByRole("button", { name: "Resumen", exact: true }).click();
  await openSupport();
  assert.equal(await card("already-read").count(), 0, "Server-read notifications must be hidden");

  // A GET started before the PUT must not undo its successful result.
  let releaseGet;
  delayGet = new Promise(resolve => { releaseGet = resolve; });
  const startedGet = new Promise(resolve => { getStarted = resolve; });
  await page.locator(".topbar").getByRole("button", { name: "Actualizar", exact: true }).click();
  await startedGet;
  let releasePut;
  delayPut = new Promise(resolve => { releasePut = resolve; });
  const startedPut = new Promise(resolve => { putStarted = resolve; });
  await mark("pending").click();
  await startedPut;
  assert.equal(await mark("pending").isDisabled(), true, "Pending save cannot be submitted twice");
  releasePut(); delayPut = null;
  await success.waitFor();
  assert.equal(writeCount, 1);
  assert.equal(await card("pending").count(), 0);
  const staleResponse = page.waitForResponse(response => response.url().endsWith("/notifications") && response.request().method() === "GET");
  releaseGet(); delayGet = null;
  await staleResponse;
  await page.waitForFunction(() => !document.querySelector('.topbar button')?.disabled);
  assert.equal(await card("pending").count(), 0, "Stale overview cannot resurrect an acknowledged notification");
  await home();
  assert.equal(await success.count(), 0, "Success notice must clear when navigating");
  await openSupport();
  assert.equal(await card("pending").count(), 0, "Read state survives remount");
  assert.equal(await success.count(), 0);

  rejectSave = true;
  await mark("save-error").click();
  await page.locator('.notice.error').waitFor();
  assert.equal(await card("save-error").count(), 1, "Rejected save must retain the notification");
  assert.equal(await mark("save-error").isEnabled(), true);
  assert.equal(readIds.has("save-error"), false);
  rejectSave = false;

  delayPut = new Promise(resolve => { releasePut = resolve; });
  const latePut = new Promise(resolve => { putStarted = resolve; });
  await mark("late-result").click();
  await latePut;
  await home();
  const lateResponse = page.waitForResponse(response => response.request().method() === "PUT");
  releasePut(); delayPut = null;
  await lateResponse;
  assert.equal(await success.count(), 0, "Late response cannot show success in another module");
  await openSupport();
  assert.equal(await card("late-result").count(), 0);

  await page.reload();
  await page.locator('input[autocomplete="username"]').fill("REPAIRS");
  await page.locator('input[autocomplete="current-password"]').fill("synthetic-password");
  await page.locator('form button[type="submit"]').click();
  await card("save-error").waitFor();
  for (const id of readIds) assert.equal(await card(id).count(), 0, "Read state must survive reload and login");
  assert.equal(await success.count(), 0);
  assert.deepEqual(errors, []);
  console.log("Support notifications E2E passed: persisted read state, remount/reload/login, stale GET, duplicate prevention, failed save and stale success notices.");
} finally {
  if (browser) await browser.close();
  server.kill();
}
