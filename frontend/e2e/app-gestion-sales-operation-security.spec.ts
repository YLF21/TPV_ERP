import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { expect, test } from "@playwright/test";

const gestionCss = readFileSync(
  fileURLToPath(new URL("../apps/app-gestion/src/gestion.css", import.meta.url)),
  "utf8",
);

const operationRows = Array.from({ length: 28 }, (_, index) => `
  <article class="gestion-operation-security-row">
    <span class="gestion-operation-security-function">
      <strong>Operación ${index + 1}</strong>
    </span>
    <span></span>
    <span></span>
    <span>
      <label class="gestion-operation-security-switch">
        <input
          type="checkbox"
          role="switch"
          aria-label="Pedir permiso: Operación ${index + 1}"
        >
        <i aria-hidden="true"></i>
        <b>No</b>
      </label>
    </span>
    <span></span>
    <span class="gestion-operation-security-effective" role="list">
      <span role="listitem">Acceso directo con permiso.</span>
      <span role="listitem">Autorización delegada si no lo tiene.</span>
    </span>
  </article>
`).join("");

test("editing a lower sales-operation switch does not scroll the application viewport", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.setContent(`
    <style>
      * { box-sizing: border-box; }
      html, body, #root { width: 100%; height: 100%; margin: 0; overflow: hidden; }
      .app-titlebar { min-height: 32px; background: #222; color: #fff; }
      ${gestionCss}
    </style>
    <div id="root">
      <div class="app-frame">
        <header class="app-titlebar">APP GESTIÓN</header>
        <main class="gestion-screen">
          <aside class="gestion-nav">Navegación</aside>
          <section class="gestion-module-stage">
            <section class="gestion-workspace gestion-operation-security-workspace">
              <header class="gestion-operation-security-header">
                <div><h2>Seguridad de funciones de venta</h2></div>
              </header>
              <div class="gestion-operation-security-toolbar">Cambios sin guardar</div>
              <section class="gestion-operation-security-panel">
                ${operationRows}
              </section>
            </section>
          </section>
        </main>
      </div>
    </div>
  `);

  const panel = page.locator(".gestion-operation-security-panel");
  await panel.evaluate((element) => {
    element.scrollTop = element.scrollHeight;
  });
  await page.evaluate(() => window.scrollTo(0, 0));

  const target = page.getByRole("switch", {
    name: "Pedir permiso: Operación 28",
  });
  const targetLabel = page.locator(".gestion-operation-security-switch").filter({ has: target });
  await expect(target).toBeVisible();
  await targetLabel.click();

  await expect(target).toBeChecked();
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
  await expect(page.locator(".app-titlebar")).toBeVisible();

  const effectiveProtection = target.locator("xpath=ancestor::article").getByRole("list");
  const protectionItems = effectiveProtection.getByRole("listitem");
  const firstItem = await protectionItems.nth(0).boundingBox();
  const secondItem = await protectionItems.nth(1).boundingBox();
  expect(firstItem).not.toBeNull();
  expect(secondItem).not.toBeNull();
  expect(secondItem!.y).toBeGreaterThan(firstItem!.y);
  await expect(protectionItems.nth(0)).toHaveCSS("display", "grid");
  expect(await protectionItems.nth(0).evaluate((element) => (
    getComputedStyle(element, "::before").content
  ))).toBe('"•"');
});
