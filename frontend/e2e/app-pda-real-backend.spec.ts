import { expect, test } from "@playwright/test";
import { adminLogin, bearer, readPdaRealFixture, realPdaApiUrl } from "./support/pdaRealFixture";

test("vincula, autentica, escanea y sincroniza una finalización offline contra el backend real", async ({ page, context, request }) => {
  const fixture = readPdaRealFixture();

  await page.goto("/");
  await expect(page.getByText("Registrar este PDA", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Vincular existente" }).click();
  await page.getByLabel("Código temporal").fill(fixture.pairingCode);
  await page.getByRole("button", { name: "Vincular PDA" }).click();

  await expect(page.getByText("Backend conectado")).toBeVisible();
  await page.getByLabel("Usuario").fill(fixture.userName);
  await page.getByLabel("Contraseña").fill(fixture.userPassword);
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.getByRole("heading", { name: "¿Qué quieres hacer?" })).toBeVisible();

  // La rotación debe invalidar el secreto anterior sin expulsar la sesión que
  // la solicita ni interrumpir el trabajo que viene a continuación.
  await page.getByRole("button", { name: "Rotar credencial" }).click();
  await expect(page.getByText("Credencial del PDA renovada.")).toBeVisible();

  await page.getByRole("button", { name: /Consultar producto/ }).click();
  await expect(page.getByRole("heading", { name: "Stock y precio" })).toBeVisible();
  await dispatchScannerBurst(page, fixture.productCode);
  await expect(page.getByRole("heading", { name: fixture.productName })).toBeVisible();
  await expect(page.getByText(`Código leído: ${fixture.productCode}`)).toBeVisible();
  const isolatedStock = page.locator(".pda-lookup-stock").filter({ hasText: fixture.warehouseName });
  await expect(isolatedStock).toContainText(String(fixture.stockQuantity));

  await page.getByRole("button", { name: /Volver al inicio/ }).click();
  await page.getByRole("button", { name: /Operaciones/ }).last().click();
  await expect(page.getByRole("heading", { name: "Operaciones de almacén" })).toBeVisible();
  const work = page.locator(".pda-work-list article").filter({ hasText: fixture.workTitle });
  await expect(work).toBeVisible();
  await work.getByRole("button", { name: "Iniciar" }).click();
  await expect(work).toHaveClass(/status-in_progress/);

  await context.setOffline(true);
  await expect(page.locator(".pda-workboard").getByText("Sin conexión", { exact: true })).toBeVisible();
  await work.getByRole("button", { name: "Completar" }).click();
  await expect(page.getByText("Sin conexión: acción guardada en la cola.")).toBeVisible();
  await expect(page.locator(".pda-work-queue")).toContainText("finish");

  await context.setOffline(false);
  await expect(page.getByText("Cola sincronizada.")).toBeVisible({ timeout: 30_000 });
  await expect(page.locator(".pda-work-queue")).toHaveCount(0);

  const admin = await adminLogin(request);
  await expect.poll(async () => {
    const response = await request.get(`${realPdaApiUrl}/pda-work/${encodeURIComponent(fixture.workId)}`, {
      headers: bearer(admin.accessToken)
    });
    if (!response.ok()) return `HTTP_${response.status()}`;
    return ((await response.json()) as { status: string }).status;
  }, { timeout: 30_000, message: "La finalización offline debe persistirse en PostgreSQL al reconectar" }).toBe("DONE");

  await expect.poll(async () => {
    const response = await request.get(
      `${realPdaApiUrl}/pda-observability?terminalId=${encodeURIComponent(fixture.pdaTerminalId)}&windowHours=1&size=100`,
      { headers: bearer(admin.accessToken) }
    );
    if (!response.ok()) return false;
    const dashboard = await response.json() as {
      events: Array<{ type: string; code: string; workId?: string | null }>;
    };
    const forWork = (type: string, code: string) => dashboard.events.some((event) => event.type === type
      && event.code === code && event.workId === fixture.workId);
    return forWork("REQUEST_LATENCY", "PDA_QUEUE_SYNC_LATENCY")
      && forWork("RECOVERED", "PDA_QUEUE_SYNC_RECOVERED");
  }, { timeout: 30_000, message: "La recuperación debe registrar latencia y recuperación reales" }).toBe(true);

  await page.setViewportSize({ width: 1024, height: 768 });
  await page.locator(".pda-header-actions select").selectOption("en");
  await expect(page.getByText("Warehouse operations", { exact: true }).first()).toBeVisible();
  await expectNoHorizontalOverflow(page);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator(".pda-header-actions select").selectOption("zh");
  await expect(page.getByText("仓库作业", { exact: true }).first()).toBeVisible();
  await expectNoHorizontalOverflow(page);
});

async function dispatchScannerBurst(page: import("@playwright/test").Page, value: string) {
  await page.evaluate((code) => {
    let timestamp = performance.now();
    const dispatch = (key: string) => {
      const event = new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true });
      Object.defineProperty(event, "timeStamp", { value: timestamp });
      window.dispatchEvent(event);
      timestamp += 8;
    };
    [...code].forEach(dispatch);
    dispatch("Enter");
  }, value);
}

async function expectNoHorizontalOverflow(page: import("@playwright/test").Page) {
  await expect.poll(() => page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth
  )).toBeLessThanOrEqual(1);
}
