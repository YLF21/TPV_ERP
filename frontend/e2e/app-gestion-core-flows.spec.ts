import { expect, test, type APIResponse, type Page, type Response } from "@playwright/test";
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

async function safeResponseSummary(response: APIResponse | Response) {
  const body: unknown = await response.json().catch(() => null);
  const code = body && typeof body === "object" && "code" in body ? body.code : undefined;
  // Keep diagnostics useful without copying tokens, user data or arbitrary response text into reports.
  const safeBody = typeof code === "string" && /^[A-Z][A-Z0-9_]{0,79}$/.test(code) ? { code } : {};
  return `HTTP ${response.status()} ${JSON.stringify(safeBody)}`;
}

async function restoreDashboardPreference(page: Page, url: string, authorization: string, original: { widgets: unknown; options: unknown }) {
  try {
    const response = await page.request.put(url, {
      headers: { Authorization: authorization }, data: { widgets: original.widgets, options: original.options }
    });
    expect.soft(response.ok(), `Restauración de la configuración previa: ${await safeResponseSummary(response)}`).toBeTruthy();
  } catch {
    expect.soft(false, "No se pudo enviar la petición de restauración de la configuración previa").toBeTruthy();
  }
}

test.describe("APP GESTIÓN · recorridos esenciales", () => {
  test("autentica al administrador y entra en el dashboard", async ({ page }) => {
    await loginGestion(page);

    await expect(page.locator(".gestion-nav")).toBeVisible();
    await expect(page.locator(".gd-toolbar")).toBeVisible();
    await expect(page.locator(".gd-toolbar h2")).toHaveText("Resumen");
  });

  test("aplica periodo y almacén al alcance de los datos del dashboard", async ({ page }) => {
    const preferenceResponse = page.waitForResponse((response) => apiPath(response, "/api/v1/gestion/dashboard/preference"));
    await loginGestion(page);
    const initialResponse = await preferenceResponse;
    expect(initialResponse.ok()).toBeTruthy();
    const original = await initialResponse.json();
    let authorization = initialResponse.request().headers().authorization;
    expect(authorization).toBeTruthy();

    try {
      // A saved empty layout is valid. Prepare sales explicitly rather than depending on user defaults.
      const prepared = await page.request.put(initialResponse.url(), {
        headers: { Authorization: authorization },
        data: { widgets: [{ key: "sales.today", width: 4, height: 1 }], options: original.options }
      });
      expect(prepared.ok(), `Preparación del bloque de ventas: ${await safeResponseSummary(prepared)}`).toBeTruthy();
      const preparedPreference = page.waitForResponse((response) => apiPath(response, "/api/v1/gestion/dashboard/preference"));
      await loginGestion(page);
      const preparedResponse = await preparedPreference;
      expect(preparedResponse.ok()).toBeTruthy();
      authorization = preparedResponse.request().headers().authorization;

      const filters = page.locator(".gd-filters");
      const fromDate = filters.getByLabel("Desde", { exact: true });
      const toDate = filters.getByLabel("Hasta", { exact: true });
      const warehouse = filters.getByRole("combobox", { name: "Almacén", exact: true });
      await expect(fromDate).toBeVisible();
      await expect(toDate).toBeVisible();
      await expect(warehouse).toBeVisible();
      await expect.poll(() => warehouse.locator("option").count()).toBeGreaterThan(1);

      const warehouseId = await warehouse.locator('option:not([value=""])').first().getAttribute("value");
      expect(warehouseId).toBeTruthy();
      await warehouse.selectOption(warehouseId!);

      const selectedFrom = "2026-08-01";
      const selectedTo = "2026-08-07";
      const scopedResponse = page.waitForResponse((response) => {
        if (!apiPath(response, "/api/v1/gestion/dashboard/data/sales-overview")) return false;
        const url = new URL(response.url());
        return url.searchParams.get("from") === selectedFrom
          && url.searchParams.get("to") === selectedTo
          && url.searchParams.get("warehouseId") === warehouseId;
      });
      await fromDate.fill(selectedFrom);
      await toDate.fill(selectedTo);
      await filters.getByRole("button", { name: "Aplicar", exact: true }).click();

      const response = await scopedResponse;
      expect(response.ok()).toBeTruthy();
      const body = await response.json();
      expect(body).toMatchObject({ from: selectedFrom, to: selectedTo, previousFrom: "2026-07-25", previousTo: "2026-07-31" });
      expect(body.daily).toHaveLength(7);
      expect(body.previousDaily).toHaveLength(7);
      await expect(fromDate).toHaveValue(selectedFrom);
      await expect(toDate).toHaveValue(selectedTo);
      await expect(warehouse).toHaveValue(warehouseId!);
    } finally {
      await restoreDashboardPreference(page, initialResponse.url(), authorization, original);
    }
  });

  test("guarda explícitamente la configuración del dashboard y la recupera al iniciar sesión", async ({ page }) => {
    const preferenceResponse = page.waitForResponse((response) => apiPath(response, "/api/v1/gestion/dashboard/preference"));
    await loginGestion(page);
    const initialResponse = await preferenceResponse;
    expect(initialResponse.ok()).toBeTruthy();
    const original = await initialResponse.json();
    let authorization = initialResponse.request().headers().authorization;
    expect(authorization).toBeTruthy();
    const workspace = page.locator(".gd-workspace");

    try {
      await workspace.getByRole("button", { name: "Personalizar", exact: true }).click();
      const configuration = page.getByRole("complementary", { name: "Mi configuración", exact: true });
      await configuration.getByRole("button", { name: "Equilibrada", exact: true }).click();
      await configuration.getByRole("button", { name: "Datos", exact: true }).click({ timeout: 5_000 });
      await configuration.getByRole("button", { name: "1/2", exact: true }).click();
      await configuration.getByRole("button", { name: "Mover antes", exact: true }).click();

      // Editing the draft must leave the authenticated server preference unchanged.
      const unchangedResponse = await page.request.get(initialResponse.url(), { headers: { Authorization: authorization } });
      expect(unchangedResponse.ok()).toBeTruthy();
      const unchanged = await unchangedResponse.json();
      expect(unchanged.widgets).toEqual(original.widgets);
      expect(unchanged.options).toEqual(original.options);

      const savedResponse = page.waitForResponse((response) => response.request().method() === "PUT"
        && new URL(response.url()).pathname.endsWith("/api/v1/gestion/dashboard/preference"));
      await configuration.getByRole("button", { name: "Guardar mi configuración", exact: true }).click();
      const saved = await savedResponse;
      expect(saved.ok(), `Guardado explícito de preferencias: ${await safeResponseSummary(saved)}`).toBeTruthy();
      const submitted = saved.request().postDataJSON();
      expect(submitted.options.trendDisplay).toBe("TABLE");
      expect(submitted.widgets[3]).toEqual({ key: "sales.trend", width: 6, height: 2 });
      await expect(configuration).toBeHidden();
      const trend = workspace.locator('[data-widget-key="sales.trend"]');
      await expect(trend).toHaveAttribute("style", /--widget-width:\s*6/);
      await expect(trend.locator("table")).toBeVisible();
      await expect(workspace.locator(".gd-grid > [data-widget-key]").nth(3)).toHaveAttribute("data-widget-key", "sales.trend");

      const reloadedPreference = page.waitForResponse((response) => apiPath(response, "/api/v1/gestion/dashboard/preference"));
      await loginGestion(page);
      const reloadedResponse = await reloadedPreference;
      expect(reloadedResponse.ok()).toBeTruthy();
      authorization = reloadedResponse.request().headers().authorization;
      const reloaded = await reloadedResponse.json();
      expect(reloaded.widgets).toEqual(submitted.widgets);
      expect(reloaded.options).toEqual(submitted.options);
      await expect(trend).toHaveAttribute("style", /--widget-width:\s*6/);
      await expect(trend.locator("table")).toBeVisible();
      await expect(workspace.locator(".gd-grid > [data-widget-key]").nth(3)).toHaveAttribute("data-widget-key", "sales.trend");
    } finally {
      await restoreDashboardPreference(page, initialResponse.url(), authorization, original);
    }
  });

  test("abre la cronología de alertas y aplica un periodo rápido de siete días", async ({ page }) => {
    await loginGestion(page);

    const preferenceResponse = page.waitForResponse((response) => apiPath(response, "/api/v1/control/alerts/view-preference"));
    const initialGroups = page.waitForResponse((response) => (
      apiPath(response, "/api/v1/control/alerts/groups")
    ));
    const initialList = page.waitForResponse((response) => apiPath(response, "/api/v1/control/alerts"));
    await page.locator(".gestion-nav").getByRole("button", {
      name: "Alertas de control",
      exact: true
    }).click();
    expect((await initialGroups).ok()).toBeTruthy();
    expect((await initialList).ok()).toBeTruthy();
    const preference = await preferenceResponse;
    expect(preference.ok()).toBeTruthy();
    const { storeTimezone, showIndicators } = await preference.json();
    const workspace = page.locator(".gestion-control-workspace");
    await expect(workspace).toBeVisible();
    await expect(workspace.getByRole("table", { name: "Listado por fecha y hora", exact: true })).toBeVisible();
    if (showIndicators) await expect(workspace.locator(".gestion-control-type-strip")).toBeVisible();

    const dates = workspace.locator(".gestion-control-date-toolbar");
    const quickPeriod = dates.getByRole("combobox", { name: "Periodos rápidos", exact: true });
    // Seven days may already be the saved default; move to Today before requesting it again.
    if (await dates.getByLabel("Desde", { exact: true }).inputValue() !== await dates.getByLabel("Hasta", { exact: true }).inputValue()) {
      const todayResponse = page.waitForResponse((response) => apiPath(response, "/api/v1/control/alerts/groups"));
      const todayListResponse = page.waitForResponse((response) => apiPath(response, "/api/v1/control/alerts"));
      await quickPeriod.selectOption("TODAY");
      expect((await todayResponse).ok()).toBeTruthy();
      expect((await todayListResponse).ok()).toBeTruthy();
    }

    const sevenDaysResponse = page.waitForResponse((response) => (
      apiPath(response, "/api/v1/control/alerts/groups")
    ));
    const sevenDaysListResponse = page.waitForResponse((response) => apiPath(response, "/api/v1/control/alerts"));
    await quickPeriod.selectOption("LAST_7_DAYS");
    const response = await sevenDaysResponse;
    expect(response.ok()).toBeTruthy();
    const query = new URL(response.url()).searchParams;
    const from = new Date(query.get("from")!);
    const to = new Date(query.get("to")!);
    const selectedFrom = await dates.getByLabel("Desde", { exact: true }).inputValue();
    const selectedTo = await dates.getByLabel("Hasta", { exact: true }).inputValue();
    const storeDay = new Intl.DateTimeFormat("en-CA", { timeZone: storeTimezone, year: "numeric", month: "2-digit", day: "2-digit" });
    expect(storeDay.format(from)).toBe(selectedFrom);
    expect(storeDay.format(new Date(to.getTime() - 1))).toBe(selectedTo);
    // Calendar days remain correct across 23/25-hour daylight-saving days.
    expect((Date.parse(`${selectedTo}T12:00:00Z`) - Date.parse(`${selectedFrom}T12:00:00Z`)) / 86_400_000).toBe(6);
    const storeTime = new Intl.DateTimeFormat("en-GB", { timeZone: storeTimezone, hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" });
    expect(storeTime.format(from)).toBe("00:00:00");
    expect(storeTime.format(to)).toBe("00:00:00");
    const listResponse = await sevenDaysListResponse;
    expect(listResponse.ok()).toBeTruthy();
    const listQuery = new URL(listResponse.url()).searchParams;
    expect(listQuery.get("from")).toBe(query.get("from"));
    expect(listQuery.get("to")).toBe(query.get("to"));
    expect(listQuery.has("ruleId")).toBe(false);
    expect(listQuery.has("type")).toBe(false);
    expect(listQuery.get("sortBy")).toBe("occurredAt");
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
    await expect(workspace.locator(".gestion-warehouse-cards")).toBeVisible();
    await expect.poll(() => (
      workspace.locator('.gestion-warehouse-card').count()
    )).toBeGreaterThan(0);
  });
});
