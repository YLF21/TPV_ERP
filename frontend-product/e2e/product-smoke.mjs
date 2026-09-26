import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const root = fileURLToPath(new URL("..", import.meta.url));
const vite = fileURLToPath(new URL("../node_modules/vite/bin/vite.js", import.meta.url));
const base = "http://127.0.0.1:5186/";
const server = spawn(process.execPath, [vite, "preview", "--port", "5186", "--strictPort", "--configLoader", "runner"], {
  cwd: root, stdio: "ignore", windowsHide: true
});
let browser;
try {
  let ready = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw Error("Product preview could not start");
    try { if ((await fetch(base)).ok) { ready = true; break; } } catch {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(ready, "Product preview startup timed out");
  browser = await chromium.launch({
    headless: true,
    ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : {})
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = [];
  const apiRequests = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/api/**", route => {
    apiRequests.push(route.request().url());
    return route.abort();
  });
  await page.goto(base, { waitUntil: "networkidle" });
  assert.match(await page.locator("h1").innerText(), /Todo tu negocio/);
  assert.equal(await page.locator('input[autocomplete="current-password"]').count(), 0);
  await page.waitForFunction(() => [...document.images].every(image => image.complete && image.naturalWidth > 0));
  const tabs = ["inicio", "solucion", "funcionalidades", "apps", "ventajas", "contacto"];
  for (const tab of tabs) {
    await page.locator('#mk-navigation a:not(.mk-mobile-client-link)[href="#/producto/' + tab + '"]').click();
    await page.waitForFunction(value => location.hash === "#/producto/" + value, tab);
    await page.locator("h1").waitFor({ state: "visible" });
    assert.equal(await page.locator("#mk-navigation a[aria-current=page]").getAttribute("href"), "#/producto/" + tab);
    await page.reload({ waitUntil: "networkidle" });
    await page.locator("h1").waitFor({ state: "visible" });
  }
  await page.goto(base);
  for (const [language, htmlLanguage] of [["en", "en"], ["zh", "zh-CN"], ["es", "es"]]) {
    await page.locator(".mk-header-actions select").selectOption(language);
    await page.waitForFunction(value => document.documentElement.lang === value, htmlLanguage);
    await page.reload({ waitUntil: "networkidle" });
    assert.equal(await page.locator("html").getAttribute("lang"), htmlLanguage);
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator(".mk-menu-button").click();
  await page.locator('#mk-navigation a:not(.mk-mobile-client-link)[href="#/producto/contacto"]').click();
  await page.locator(".mk-contact-form").waitFor({ state: "visible" });
  assert.equal(await page.locator(".mk-menu-button").getAttribute("aria-expanded"), "false");
  assert.ok(await page.evaluate(() => {
    const page = document.querySelector(".mk-page");
    return page.scrollWidth <= page.clientWidth && document.documentElement.scrollWidth <= innerWidth;
  }), "Mobile page must not overflow horizontally");
  assert.deepEqual(apiRequests, [], "Public website must not call a SaaS API");
  assert.deepEqual(errors, [], "Public website must not raise browser errors");
  console.log("Product E2E passed: independent production build, six routes, images, three languages and mobile navigation.");
} finally {
  await browser?.close();
  server.kill();
}
