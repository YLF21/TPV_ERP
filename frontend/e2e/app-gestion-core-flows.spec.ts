import { expect, test, type Page, type Response } from "@playwright/test";
import { gestionUrl } from "./support/testApi";

const apiPath = (response: Response, path: string) => {
  const url = new URL(response.url());
  return response.request().method() === "GET" && url.pathname.endsWith(path);
};

async function loginGestion(page: Page) {
  await page.goto(gestionUrl);
  await expect(page.locator(".login-screen")).toBeVisible();
  await page.locator('input[autocomplete="username"]').fill(
    process.env.E2E_ADMIN_USERNAME ?? "ADMIN"
  );
  await page.locator('input[autocomplete="current-password"]').fill(
    process.env.E2E_ADMIN_PASSWORD ?? "0000"
  );

  const loginResponse = page.waitForResponse((response) => (
    response.request().method() === "POST"
      && new URL(response.url()).pathname.endsWith("/api/v1/auth/login")
  ));
  await page.locator(".login-panel button[type=submit]").click();
  await expect((await loginResponse).ok()).toBeTruthy();
  await expect(page.locator(".gestion-screen")).toBeVisible();
}

test.describe("APP GESTIÓN · recorridos esenciales", () => {
  test("autentica al administrador y entra en el dashboard", async ({ page }) => {
    await loginGestion(page);

    await expect(page.locator(".gestion-nav")).toBeVisible();
    await expect(page.locator(".gestion-dashboard-toolbar")).toBeVisible();
    await expect(page.locator(".gestion-dashboard-toolbar h2")).toHaveText("Resumen");
  });

  test("aplica fecha y almacén al alcance de los datos del dashboard", async ({ page }) => {
    await loginGestion(page);

    const date = page.locator('.gestion-dashboard-scope-control input[type="date"]');
    const warehouse = page.locator(".gestion-dashboard-scope-control select");
    await expect(date).toBeVisible();
    await expect(warehouse).toBeVisible();
    await expect.poll(() => warehouse.locator("option").count()).toBeGreaterThan(1);

    const warehouseId = await warehouse.locator('option:not([value=""])').first().getAttribute("value");
    expect(warehouseId).toBeTruthy();
    await warehouse.selectOption(warehouseId!);

    const selectedDate = "2026-08-01";
    const scopedResponse = page.waitForResponse((response) => {
      if (!apiPath(response, "/api/v1/gestion/dashboard/data/sales-today")) return false;
      const url = new URL(response.url());
      return url.searchParams.get("date") === selectedDate
        && url.searchParams.get("warehouseId") === warehouseId;
    });
    await date.fill(selectedDate);

    expect((await scopedResponse).ok()).toBeTruthy();
    await expect(date).toHaveValue(selectedDate);
    await expect(warehouse).toHaveValue(warehouseId!);
  });

  test("abre alertas de control, carga el resumen y aplica un periodo rápido", async ({ page }) => {
    await loginGestion(page);

    const initialGroups = page.waitForResponse((response) => (
      apiPath(response, "/api/v1/control/alerts/groups")
    ));
    await page.locator(".gestion-nav").getByRole("button", {
      name: "Alertas de control",
      exact: true
    }).click();
    expect((await initialGroups).ok()).toBeTruthy();
    await expect(page.locator(".gestion-control-workspace")).toBeVisible();
    await expect(page.locator(".gestion-control-analytics")).toBeVisible();

    const sevenDaysResponse = page.waitForResponse((response) => (
      apiPath(response, "/api/v1/control/alerts/groups")
    ));
    await page.locator(".gestion-control-date-presets").getByRole("button", {
      name: "7 días",
      exact: true
    }).click();
    const response = await sevenDaysResponse;
    expect(response.ok()).toBeTruthy();
    const query = new URL(response.url()).searchParams;
    const from = new Date(query.get("from")!);
    const to = new Date(query.get("to")!);
    expect((to.getTime() - from.getTime()) / 86_400_000).toBe(7);
  });

  test("navega desde el menú de almacén hasta su gestión", async ({ page }) => {
    await loginGestion(page);

    const navigation = page.locator(".gestion-nav");
    await navigation.getByRole("button", { name: "Almacén", exact: true }).click();
    const warehousesResponse = page.waitForResponse((response) => (
      apiPath(response, "/api/v1/warehouses")
    ));
    await navigation.getByRole("button", { name: "Almacenes", exact: true }).click();

    expect((await warehousesResponse).ok()).toBeTruthy();
    const workspace = page.locator(".gestion-warehouse-workspace");
    await expect(workspace).toBeVisible();
    await expect(workspace.locator(".gestion-warehouse-list")).toBeVisible();
    await expect.poll(() => (
      workspace.locator('.gestion-warehouse-row[role="row"]').count()
    )).toBeGreaterThan(1);
  });
});
