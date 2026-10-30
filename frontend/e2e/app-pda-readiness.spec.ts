import { expect, test } from "@playwright/test";
import { loginPda } from "./support/ui";

async function mockPdaBackend(page: import("@playwright/test").Page) {
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const json = (value: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(value) });
    if (path.endsWith("/auth/login")) return json({
      accessToken: "e2e-pda-token", userId: "10000000-0000-0000-0000-000000000001",
      userName: "ADMIN", role: "ADMIN", permissions: ["GESTION_ALMACEN", "STOCK_ADJUST", "STOCK_TRANSFER"]
    });
    if (path.endsWith("/warehouses")) return json([{ id: "20000000-0000-0000-0000-000000000001", name: "GENERAL", active: true, defaultWarehouse: true }]);
    if (path.endsWith("/suppliers") || path.endsWith("/goods-checks") || path.endsWith("/pda-work")) return json([]);
    if (path.endsWith("/stock/page")) return json({ items: [], hasMore: false, nextCursor: null });
    return json({ status: "ok" });
  });
}

const phoneViewports = [
  { name: "compacto", width: 360, height: 740 },
  { name: "pda", width: 430, height: 932 }
] as const;

for (const viewport of phoneViewports) {
  test(`PDA mantiene una interfaz usable en formato ${viewport.name}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await mockPdaBackend(page);
    await loginPda(page);

    const shell = page.locator(".pda-app-shell");
    await expect(shell).toBeVisible();
    await expect(page.locator(".pda-home-menu")).toBeVisible();
    await expect.poll(async () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);

    const header = page.locator(".pda-app-header");
    const spanishHeight = await header.evaluate((element) => element.getBoundingClientRect().height);
    await header.getByRole("combobox").selectOption("zh");
    await expect(page.locator("html")).toHaveAttribute("lang", "zh-CN");
    const chineseHeight = await header.evaluate((element) => element.getBoundingClientRect().height);
    expect(Math.abs(chineseHeight - spanishHeight)).toBeLessThanOrEqual(2);
    await expect.poll(async () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });
}

test("PDA navega a un módulo y vuelve sin cambiar el ancho", async ({ page }) => {
  await page.setViewportSize({ width: 430, height: 932 });
  await mockPdaBackend(page);
  await loginPda(page);

  const initialWidth = await page.locator(".pda-app-shell").evaluate((element) => element.getBoundingClientRect().width);
  await page.getByRole("button", { name: /consultar producto/i }).click();
  await expect(page.locator(".pda-module-toolbar")).toBeVisible();
  await expect(page.locator(".pda-module-view")).toBeVisible();
  const moduleWidth = await page.locator(".pda-app-shell").evaluate((element) => element.getBoundingClientRect().width);
  expect(Math.abs(moduleWidth - initialWidth)).toBeLessThanOrEqual(1);

  await page.getByRole("button", { name: /volver al inicio/i }).click();
  await expect(page.locator(".pda-home")).toBeVisible();
  await expect.poll(async () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});
