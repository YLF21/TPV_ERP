import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { apiGet, apiPost, apiPut, authorization, apiUrl, createProductFixture,
  loginApi, terminalId, uniqueMarker, type ProductView } from "./support/testApi";
import { loginUi } from "./support/ui";

test.use({ actionTimeout: 10_000 });

// These tests create real documents. Run only against the disposable integration installation.
test.beforeAll(() => {
  if (process.env.E2E_CONTROL_ALERTS_DISPOSABLE !== "true") {
    throw new Error("Control-alert sale flows require E2E_CONTROL_ALERTS_DISPOSABLE=true and an isolated installation");
  }
});

type Rule = { id: string; type: string; active: boolean; configuration: Record<string, number>; version: number; ruleVersion: number };
type Alert = { id: string; type: string; documentId?: string; documentNumber?: string; occurredAt: string; ruleVersion: number; data: Record<string, unknown> };
type Alerts = { content: Alert[]; totalElements: number };

async function prepare(request: APIRequestContext, types: Record<string, Record<string, number>>, salePrice = 10) {
  const session = await loginApi(request);
  const token = session.accessToken;
  const installation = await apiGet<{ mode: string }>(request, token, "/installation/status");
  expect(installation.mode, "Use only the disposable DEVELOPMENT installation").toBe("DEVELOPMENT");
  const originals = await apiGet<Rule[]>(request, token, "/control/rules");
  const configured: Rule[] = [];
  for (const [type, configuration] of Object.entries(types)) {
    const existing = originals.find(rule => rule.type === type);
    configured.push(existing
      ? await apiPut<Rule>(request, token, `/control/rules/${existing.id}`, { active: true, configuration, version: existing.version })
      : await apiPost<Rule>(request, token, "/control/rules", { type, active: true, configuration }));
  }
  const cash = await apiGet<{ status: string }>(request, token, `/cash/status?terminalId=${terminalId}`);
  if (cash.status !== "ABIERTA") await apiPost(request, token, "/cash/sessions/open", { terminalId });
  const marker = uniqueMarker("CONTROL");
  const product = await createProductFixture(request, token, marker, salePrice);
  return { token, product, configured, async restore() {
    for (const rule of configured) {
      const current = await apiGet<Rule>(request, token, `/control/rules/${rule.id}`);
      const original = originals.find(value => value.id === rule.id);
      const response = await request.put(`${apiUrl}/control/rules/${rule.id}`, {
        headers: authorization(token), data: { active: original?.active ?? false,
          configuration: original?.configuration ?? rule.configuration, version: current.version }
      });
      expect.soft(response.ok(), `Restore rule ${rule.type}: HTTP ${response.status()}`).toBeTruthy();
    }
  } };
}

async function openSale(page: Page) {
  await loginUi(page, "venta");
  await page.getByRole("button", { name: "VENTA", exact: true }).click();
  await expect(page.getByRole("combobox", { name: "Buscar producto", exact: true })).toBeEditable();
}

async function add(page: Page, product: ProductView) {
  const search = page.getByRole("combobox", { name: "Buscar producto", exact: true });
  await search.fill(product.code!);
  await search.press("Enter");
  await expect(page.getByRole("table", { name: "Líneas del ticket" }).getByRole("row").filter({ hasText: product.code! })).toBeVisible();
}

async function price(page: Page, value: string) {
  await page.keyboard.press("Control+PageUp");
  await expect(page.getByRole("dialog")).toBeVisible();
  if (await page.getByLabel("Tu contraseña", { exact: true }).isVisible()) {
    await page.getByLabel("Tu contraseña", { exact: true }).fill(process.env.E2E_ADMIN_PASSWORD ?? "0000");
    await page.getByRole("button", { name: "Confirmar y continuar", exact: true }).click();
  }
  const dialog = page.getByRole("dialog", { name: "Cambiar precio en esta compra" });
  await dialog.getByLabel("Precio para esta compra").fill(value);
  const quote = page.waitForResponse(response => new URL(response.url()).pathname === "/api/v1/pos/sales/quote"
    && response.request().postDataJSON()?.lines?.some((line: { openUnitPrice?: number }) => line.openUnitPrice === Number(value)));
  await dialog.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(dialog).toBeHidden();
  const password = page.getByLabel("Tu contraseña", { exact: true });
  if (await password.isVisible()) {
    await password.fill(process.env.E2E_ADMIN_PASSWORD ?? "0000");
    await page.getByRole("button", { name: "Confirmar y continuar", exact: true }).click();
    await expect(password).toBeHidden();
  }
  expect((await quote).ok()).toBeTruthy();
}

async function cash(page: Page) {
  await page.keyboard.press("PageDown");
  const checkout = page.getByRole("dialog", { name: "COBRO", exact: true });
  await checkout.getByRole("button", { name: "Efectivo", exact: true }).click();
  await checkout.getByRole("button", { name: "ACEPTAR", exact: true }).click();
  const result = page.getByRole("region", { name: "Pago completado" });
  await expect(result).toBeVisible();
  const receipt = (await result.innerText()).match(/Ticket\s+(\S+)/)?.[1];
  expect(receipt).toBeTruthy();
  return receipt!;
}

async function alerts(request: APIRequestContext, token: string, search: string) {
  return apiGet<Alerts>(request, token, `/control/alerts?size=100&search=${encodeURIComponent(search)}`);
}

for (const scenario of [
  { label: "bajada inferior", price: "9.5", thresholdAlert: false },
  { label: "bajada igual", price: "9", thresholdAlert: false },
  { label: "bajada superior por un céntimo", price: "8.99", thresholdAlert: true },
  { label: "subida", price: "12", thresholdAlert: false },
] as const) {
  test(`precio desde caja: ${scenario.label} al umbral del 10%`, async ({ page, request }) => {
    const fixture = await prepare(request, { MANUAL_PRICE_CHANGED: {}, MANUAL_PRICE_CHANGE_OVER_PERCENT: { thresholdPercent: 10 } });
    try {
      await openSale(page);
      await add(page, fixture.product);
      await price(page, scenario.price);
      const receipt = await cash(page);
      const found = await alerts(request, fixture.token, receipt);
      expect(found.content.filter(value => value.type === "MANUAL_PRICE_CHANGED")).toHaveLength(1);
      expect(found.content.filter(value => value.type === "MANUAL_PRICE_CHANGE_OVER_PERCENT"))
        .toHaveLength(scenario.thresholdAlert ? 1 : 0);
      if (scenario.thresholdAlert) {
        const gestion = await page.context().newPage();
        await loginUi(gestion, "gestion");
        await gestion.getByRole("button", { name: "Alertas de control", exact: true }).click();
        await gestion.getByRole("textbox", { name: /^buscar$/i }).fill(receipt);
        await gestion.getByRole("button", { name: "Aplicar filtros", exact: true }).click();
        await expect(gestion.getByRole("table", { name: "Listado por fecha y hora" }))
          .toContainText(receipt);
        await expect(gestion.getByRole("table", { name: "Listado por fecha y hora" }))
          .toContainText("Bajada manual de precio superior al porcentaje");
        await gestion.close();
      }
    } finally { await fixture.restore(); }
  });
}

test("descuento manual desde caja conserva su evidencia sin inventar un cambio de precio", async ({ page, request }) => {
  const fixture = await prepare(request, { PRODUCT_DISCOUNT_APPLIED: {}, MANUAL_DISCOUNT_OVER_PERCENT: { thresholdPercent: 20 },
    MANUAL_PRICE_CHANGED: {}, MANUAL_PRICE_CHANGE_OVER_PERCENT: { thresholdPercent: 20 } }, 8.2);
  try {
    await openSale(page);
    await add(page, fixture.product);
    const quote = page.waitForResponse(response => new URL(response.url()).pathname === "/api/v1/pos/sales/quote"
      && response.request().postDataJSON()?.lines?.some((line: { discount?: number }) => line.discount === 51.22));
    await page.getByRole("combobox", { name: "Buscar producto", exact: true }).fill("4");
    await page.keyboard.press("PageUp");
    expect((await quote).ok()).toBeTruthy();
    const receipt = await cash(page);
    const types = (await alerts(request, fixture.token, receipt)).content.map(alert => alert.type);
    expect(types).toContain("PRODUCT_DISCOUNT_APPLIED");
    expect(types).toContain("MANUAL_DISCOUNT_OVER_PERCENT");
    expect(types).not.toContain("MANUAL_PRICE_CHANGED");
    expect(types).not.toContain("MANUAL_PRICE_CHANGE_OVER_PERCENT");
    expect(types).toHaveLength(2);

    await page.setViewportSize({ width: 1920, height: 1080 });
    await loginUi(page, "gestion");
    await page.getByRole("button", { name: "Alertas de control", exact: true }).click();
    await page.getByRole("textbox", { name: /^buscar$/i }).fill(receipt);
    await page.getByRole("button", { name: "Aplicar filtros", exact: true }).click();
    const table = page.getByRole("table", { name: "Listado por fecha y hora", exact: true });
    const row = table.getByRole("row").filter({ hasText: "Descuento manual aplicado a producto" });
    await expect(row).toHaveCount(1);
    await expect(table.getByRole("row").first().getByText("Comentario de revisión", { exact: true })).toBeVisible();
    const columnEdges = await table.getByRole("row").first().evaluate(element => ({
      count: element.children.length,
      gap: element.getBoundingClientRect().right - element.lastElementChild!.getBoundingClientRect().right
    }));
    expect(columnEdges.count).toBe(5);
    expect(Math.abs(columnEdges.gap), "The final comment column fills the row without a blank trailing column").toBeLessThan(2);
    await row.click();
    await expect(page.getByRole("dialog", { name: "Detalle de la alerta", exact: true })).toBeHidden();
    await row.dblclick();
    const detail = page.getByRole("dialog", { name: "Detalle de la alerta", exact: true });
    await expect(detail).toContainText(fixture.product.name);
    await expect(detail).toContainText(fixture.product.code!);
    await expect(detail).not.toContainText(fixture.product.id);
    await expect(detail).not.toContainText(terminalId);
    await expect(detail).toContainText(/servidor/i);
    await detail.getByRole("button", { name: "Abrir venta relacionada", exact: true }).click();
    const related = page.getByRole("dialog").filter({ has: page.getByRole("heading", { name: receipt, exact: false }) });
    await expect(related).toBeVisible();
    await expect(related).toContainText("4,00");
    await page.keyboard.press("Escape");
    await expect(related).toBeHidden();
    await expect(detail).toBeVisible();
    const comment = uniqueMarker("REVISION");
    await detail.getByLabel("Comentario de revisión", { exact: true }).fill(comment);
    await detail.getByRole("button", { name: "Marcar revisada", exact: true }).click();
    await expect(detail.getByRole("button", { name: "Marcar revisada", exact: true })).toBeDisabled();
    await expect(detail.getByRole("button", { name: "Cerrar", exact: true })).toBeEnabled();
    await expect(detail.getByLabel("Comentario de revisión", { exact: true })).toHaveValue("");
    await page.keyboard.press("Escape");
    await expect(detail).toBeHidden();
    await expect(row).toContainText(comment);
    await expect(row).toContainText("Revisada");
    await expect(table.getByRole("row").filter({ hasText: "Descuento manual superior al porcentaje" })).toContainText("Nueva");
    await loginUi(page, "gestion");
    await page.getByRole("button", { name: "Alertas de control", exact: true }).click();
    await page.getByRole("textbox", { name: /^buscar$/i }).fill(receipt);
    await page.getByRole("button", { name: "Aplicar filtros", exact: true }).click();
    await expect(row).toContainText(comment);

    await row.dblclick();
    await expect(detail.getByRole("button", { name: "Reabrir alerta", exact: true })).toBeEnabled();
    let reopenComment = "";
    let reopenedCount = 0;
    for (const action of [null, "Cerrar alerta", "Descartar alerta"]) {
      if (action) {
        await detail.getByRole("button", { name: action, exact: true }).click();
        await expect(detail.getByRole("button", { name: "Cerrar", exact: true })).toBeEnabled();
        await expect(detail.getByRole("button", { name: "Marcar revisada", exact: true })).toBeDisabled();
      }
      reopenComment = uniqueMarker("REAPERTURA");
      if (action === "Descartar alerta") reopenComment += ` ${"Comprobar el motivo de la revisión y conservar el historial. ".repeat(7)}`.trimEnd();
      await detail.getByLabel("Comentario de revisión", { exact: true }).fill(reopenComment);
      await detail.getByRole("button", { name: "Reabrir alerta", exact: true }).click();
      await expect(detail.getByRole("button", { name: "Reabrir alerta", exact: true })).toBeHidden();
      await expect(detail.getByRole("button", { name: "Marcar revisada", exact: true })).toBeEnabled();
      await expect(detail.getByText("Reabierta", { exact: true })).toHaveCount(++reopenedCount);
      await expect(detail.getByText(comment, { exact: true })).toBeVisible();
      await expect(row).toContainText("Nueva");
      await expect(row).toContainText(reopenComment);
      const headerBox = await table.getByRole("row").first().boundingBox();
      const rowBox = await row.boundingBox();
      expect(Math.abs(rowBox!.width - headerBox!.width), "Long comments retain alignment with the header").toBeLessThan(2);
    }
    await page.keyboard.press("Escape");
    await expect(detail).toBeHidden();
    await loginUi(page, "gestion");
    await page.getByRole("button", { name: "Alertas de control", exact: true }).click();
    await page.getByRole("textbox", { name: /^buscar$/i }).fill(receipt);
    await page.getByRole("button", { name: "Aplicar filtros", exact: true }).click();
    await expect(row).toContainText("Nueva");
    await expect(row).toContainText(reopenComment);
  } finally { await fixture.restore(); }
});

test("responsables de alertas excluye usuarios sin acceso y rechaza asignaciones forzadas", async ({ page, request }) => {
  const fixture = await prepare(request, { MANUAL_PRICE_CHANGED: {} });
  try {
    await apiPost(request, fixture.token, "/auth/gestion-groups/SEGURIDAD/unlock", {
      password: process.env.E2E_ADMIN_PASSWORD ?? "0000"
    });
    const users = await apiGet<Array<{ id: string; name: string; userName: string; role: string }>>(request, fixture.token, "/users");
    const seller = users.find(user => user.name === "VENDEDOR");
    expect(seller, "The isolated fixture includes a seller without access to Gestión").toBeTruthy();
    const roles = await apiGet<Array<{ name: string; permissions: string[] }>>(request, fixture.token, "/roles");
    expect(roles.find(role => role.name === seller!.role)?.permissions).not.toContain("APP_GESTION_ACCESS");
    const candidates = await apiGet<Array<{ id: string; userName: string }>>(request, fixture.token, "/control/alerts/assignees");
    expect(candidates.some(candidate => candidate.id === seller!.id)).toBe(false);
    const admin = candidates.find(candidate => candidate.userName === "ADMIN");
    expect(admin).toBeTruthy();

    await openSale(page);
    await add(page, fixture.product);
    await price(page, "9");
    const receipt = await cash(page);
    const alert = (await alerts(request, fixture.token, receipt)).content.find(value => value.type === "MANUAL_PRICE_CHANGED")!;
    expect(alert).toBeTruthy();
    type Detail = { alert: { version: number; priority: string; assigneeId: string | null }; workHistory: unknown[] };
    const before = await apiGet<Detail>(request, fixture.token, `/control/alerts/${alert.id}`);
    const rejected = await request.put(`${apiUrl}/control/alerts/${alert.id}/work`, {
      headers: { ...authorization(fixture.token), "Accept-Language": "es" },
      data: { priority: before.alert.priority, assigneeId: seller!.id, dueAt: null, comment: null, version: before.alert.version }
    });
    expect(rejected.status()).toBe(400);
    expect(await rejected.text()).toContain("poder consultar Alertas de control");
    expect(await apiGet<Detail>(request, fixture.token, `/control/alerts/${alert.id}`)).toEqual(before);

    await loginUi(page, "gestion");
    await page.getByRole("button", { name: "Alertas de control", exact: true }).click();
    await page.getByRole("textbox", { name: /^buscar$/i }).fill(receipt);
    await page.getByRole("button", { name: "Aplicar filtros", exact: true }).click();
    const row = page.getByRole("table", { name: "Listado por fecha y hora", exact: true })
      .getByRole("row").filter({ hasText: "Cambio manual de precio" });
    await row.dblclick();
    const detail = page.getByRole("dialog").filter({ has: page.getByRole("heading", { name: "Detalle de la alerta", exact: true }) });
    const responsible = detail.getByRole("combobox", { name: "Responsable", exact: true });
    await expect(responsible).toBeVisible();
    await expect(responsible.getByRole("option", { name: /VENDEDOR/i })).toHaveCount(0);
    await responsible.selectOption(admin!.id);
    await detail.getByRole("button", { name: "Guardar asignación", exact: true }).click();
    await expect(detail.getByRole("button", { name: "Guardar asignación", exact: true })).toBeHidden();
    const assigned = await apiGet<Detail>(request, fixture.token, `/control/alerts/${alert.id}`);
    expect(assigned.alert.assigneeId).toBe(admin!.id);
    expect(assigned.workHistory).toHaveLength(before.workHistory.length + 1);
    await page.keyboard.press("Escape");
    await row.dblclick();
    await expect(responsible).toHaveValue(admin!.id);
    await responsible.selectOption("");
    await detail.getByRole("button", { name: "Guardar asignación", exact: true }).click();
    await expect(detail.getByRole("button", { name: "Guardar asignación", exact: true })).toBeHidden();
    const unassigned = await apiGet<Detail>(request, fixture.token, `/control/alerts/${alert.id}`);
    expect(unassigned.alert.assigneeId).toBeNull();
    expect(unassigned.workHistory).toHaveLength(before.workHistory.length + 2);
  } finally { await fixture.restore(); }
});

test("vaciar el carrito desde caja genera una única alerta persistida", async ({ page, request }) => {
  const fixture = await prepare(request, { SALE_SCREEN_CLEARED: {} });
  try {
    await openSale(page);
    await add(page, fixture.product);
    const before = await apiGet<Alerts>(request, fixture.token, "/control/alerts?type=SALE_SCREEN_CLEARED&size=100");
    await page.keyboard.press("Control+F4");
    const clear = page.getByRole("dialog", { name: "Eliminar venta actual" });
    const recorded = page.waitForResponse(response => new URL(response.url()).pathname === "/api/v1/sale-line-deletions"
      && response.request().method() === "POST");
    await clear.getByRole("button", { name: "Eliminar venta", exact: true }).click();
    const response = await recorded;
    expect(response.ok(), `Record deleted lines: HTTP ${response.status()}`).toBeTruthy();
    const payload = response.request().postDataJSON();
    const retry = await request.post(`${apiUrl}/sale-line-deletions`, { headers: authorization(fixture.token), data: payload });
    expect(retry.ok()).toBeTruthy();
    const after = await apiGet<Alerts>(request, fixture.token, "/control/alerts?type=SALE_SCREEN_CLEARED&size=100");
    expect(after.totalElements).toBe(before.totalElements + 1);
    const created = after.content.find(candidate => !before.content.some(value => value.id === candidate.id));
    expect(created?.data).toMatchObject({ lineCount: 1 });
  } finally { await fixture.restore(); }
});

async function clearCart(page: Page) {
  await page.keyboard.press("Control+F4");
  await page.getByRole("dialog", { name: "Eliminar venta actual" })
    .getByRole("button", { name: "Eliminar venta", exact: true }).click();
}

test("una eliminación sin conexión sobrevive al cierre de la pestaña y se entrega una sola vez", async ({ page, request }) => {
  const fixture = await prepare(request, { SALE_SCREEN_CLEARED: {} });
  const routePattern = "**/api/v1/sale-line-deletions";
  let queued: Record<string, unknown> | undefined;
  await page.context().route(routePattern, async route => {
    if (route.request().method() !== "POST") return route.continue();
    queued = route.request().postDataJSON();
    await route.abort("connectionfailed");
  });
  try {
    await openSale(page);
    await add(page, fixture.product);
    const before = await apiGet<Alerts>(request, fixture.token, "/control/alerts?type=SALE_SCREEN_CLEARED&size=100");
    await clearCart(page);
    await expect(page.getByText("Sin conexión con control.", { exact: false })).toBeVisible();
    await expect(page.getByRole("table", { name: "Líneas del ticket" })).not.toContainText(fixture.product.code!);
    expect(queued?.occurredAt).toBeTruthy();
    await page.close();

    await page.context().unroute(routePattern);
    const restarted = await page.context().newPage();
    const recovered = restarted.waitForResponse(response => new URL(response.url()).pathname === "/api/v1/sale-line-deletions"
      && response.request().method() === "POST");
    await openSale(restarted);
    const response = await recovered;
    expect(response.ok()).toBeTruthy();
    expect(response.request().postDataJSON()).toEqual(queued);
    await expect(restarted.getByRole("button", { name: "Reintentar envío", exact: true })).toBeHidden();
    const after = await apiGet<Alerts>(request, fixture.token, "/control/alerts?type=SALE_SCREEN_CLEARED&size=100");
    expect(after.totalElements).toBe(before.totalElements + 1);
    await restarted.close();
  } finally { await fixture.restore(); }
});

test("perder la respuesta del servidor conserva el evento y su reintento no duplica la alerta", async ({ page, request }) => {
  const fixture = await prepare(request, { SALE_SCREEN_CLEARED: {} });
  let lostResponse = false;
  let initialBody: unknown;
  await page.route("**/api/v1/sale-line-deletions", async route => {
    if (route.request().method() !== "POST" || lostResponse) return route.continue();
    initialBody = route.request().postDataJSON();
    const committed = await route.fetch();
    expect(committed.ok()).toBeTruthy();
    lostResponse = true;
    await route.abort("connectionfailed");
  });
  try {
    await openSale(page);
    await add(page, fixture.product);
    const before = await apiGet<Alerts>(request, fixture.token, "/control/alerts?type=SALE_SCREEN_CLEARED&size=100");
    await clearCart(page);
    await expect(page.getByText("Sin conexión con control.", { exact: false })).toBeVisible();
    const retry = page.waitForResponse(response => new URL(response.url()).pathname === "/api/v1/sale-line-deletions"
      && response.request().method() === "POST");
    await page.getByRole("button", { name: "Reintentar envío", exact: true }).click();
    const response = await retry;
    expect(response.ok()).toBeTruthy();
    expect(response.request().postDataJSON()).toEqual(initialBody);
    await expect(page.getByRole("button", { name: "Reintentar envío", exact: true })).toBeHidden();
    const after = await apiGet<Alerts>(request, fixture.token, "/control/alerts?type=SALE_SCREEN_CLEARED&size=100");
    expect(after.totalElements).toBe(before.totalElements + 1);
  } finally { await fixture.restore(); }
});

test("si falla el almacenamiento local se conservan los productos y no se envía la eliminación", async ({ page, request }) => {
  const fixture = await prepare(request, { SALE_SCREEN_CLEARED: {} });
  await page.addInitScript(() => {
    const original = IDBObjectStore.prototype.add;
    IDBObjectStore.prototype.add = function (...args: Parameters<IDBObjectStore["add"]>) {
      if (this.transaction.db.name === "tpverp-sale-control") throw new DOMException("Fixture: storage full", "QuotaExceededError");
      return original.apply(this, args);
    };
  });
  let submissions = 0;
  page.on("request", request => {
    if (new URL(request.url()).pathname === "/api/v1/sale-line-deletions" && request.method() === "POST") submissions++;
  });
  try {
    await openSale(page);
    await add(page, fixture.product);
    await clearCart(page);
    await expect(page.getByText("No se pudo guardar el registro de control.", { exact: false }).first()).toBeVisible();
    await expect(page.getByRole("table", { name: "Líneas del ticket" })).toContainText(fixture.product.code!);
    expect(submissions).toBe(0);
  } finally { await fixture.restore(); }
});

test("eliminaciones consecutivas desde caja alcanzan el umbral y no repiten la alerta", async ({ page, request }) => {
  const fixture = await prepare(request, { CONSECUTIVE_LINE_DELETIONS: { minimumCount: 2 } });
  try {
    const second = await createProductFixture(request, fixture.token, uniqueMarker("CONTROL-SEQ"));
    const third = await createProductFixture(request, fixture.token, uniqueMarker("CONTROL-SEQ"));
    await openSale(page);
    for (const product of [fixture.product, second, third]) await add(page, product);
    const before = await apiGet<Alerts>(request, fixture.token, "/control/alerts?type=CONSECUTIVE_LINE_DELETIONS&size=100");
    for (let index = 0; index < 3; index++) {
      const recorded = page.waitForResponse(response => new URL(response.url()).pathname === "/api/v1/sale-line-deletions"
        && response.request().method() === "POST");
      await page.getByRole("combobox", { name: "Buscar producto", exact: true }).fill("0");
      await page.keyboard.press("Pause");
      expect((await recorded).ok()).toBeTruthy();
      const current = await apiGet<Alerts>(request, fixture.token, "/control/alerts?type=CONSECUTIVE_LINE_DELETIONS&size=100");
      expect(current.totalElements).toBe(before.totalElements + (index >= 1 ? 1 : 0));
    }
  } finally { await fixture.restore(); }
});

test("reintentos simultáneos de la misma eliminación generan una sola alerta", async ({ request }) => {
  const fixture = await prepare(request, { SALE_SCREEN_CLEARED: {} });
  try {
    const before = await apiGet<Alerts>(request, fixture.token, "/control/alerts?type=SALE_SCREEN_CLEARED&size=100");
    const payload = { saleOperationId: randomUUID(), deletionOperationId: randomUUID(), fullTicketClear: true,
      lines: [{ productId: fixture.product.id, code: fixture.product.code, name: fixture.product.name, quantity: 1, unitPrice: 10 }] };
    const responses = await Promise.all(Array.from({ length: 8 }, () =>
      request.post(`${apiUrl}/sale-line-deletions`, { headers: authorization(fixture.token), data: payload })));
    for (const response of responses) expect(response.ok(), `Concurrent retry: ${response.status()} ${response.ok() ? "" : await response.text()}`).toBeTruthy();
    const rows = await Promise.all(responses.map(response => response.json()));
    expect(new Set(rows.map(values => values[0].id)).size).toBe(1);
    const after = await apiGet<Alerts>(request, fixture.token, "/control/alerts?type=SALE_SCREEN_CLEARED&size=100");
    expect(after.totalElements).toBe(before.totalElements + 1);
  } finally { await fixture.restore(); }
});

test("la recuperación respeta el umbral vigente al borrar aunque se cambie durante la desconexión", async ({ page, request }) => {
  const fixture = await prepare(request, { CONSECUTIVE_LINE_DELETIONS: { minimumCount: 2 } });
  const pattern = "**/api/v1/sale-line-deletions";
  await page.route(pattern, route => route.request().method() === "POST" ? route.abort("connectionfailed") : route.continue());
  try {
    const second = await createProductFixture(request, fixture.token, uniqueMarker("CONTROL-HISTORY"));
    const third = await createProductFixture(request, fixture.token, uniqueMarker("CONTROL-HISTORY"));
    await openSale(page);
    for (const product of [fixture.product, second, third]) await add(page, product);
    const before = await apiGet<Alerts>(request, fixture.token, "/control/alerts?type=CONSECUTIVE_LINE_DELETIONS&size=100");
    for (const removed of [third, second]) {
      await page.getByRole("combobox", { name: "Buscar producto", exact: true }).fill("0");
      await page.keyboard.press("Pause");
      await expect(page.getByRole("table", { name: "Líneas del ticket" })).not.toContainText(removed.code!);
    }
    await expect(page.getByText("Sin conexión con control.", { exact: false })).toBeVisible();
    const original = fixture.configured[0];
    const updated = await apiPut<Rule>(request, fixture.token, `/control/rules/${original.id}`,
      { active: true, configuration: { minimumCount: 5 }, version: original.version });
    expect(updated.ruleVersion).toBeGreaterThan(original.ruleVersion);
    await page.unroute(pattern);
    await page.getByRole("button", { name: "Reintentar envío", exact: true }).click();
    await expect(page.getByRole("button", { name: "Reintentar envío", exact: true })).toBeHidden();
    const after = await apiGet<Alerts>(request, fixture.token, "/control/alerts?type=CONSECUTIVE_LINE_DELETIONS&size=100");
    expect(after.totalElements).toBe(before.totalElements + 1);
    const created = after.content.find(candidate => !before.content.some(value => value.id === candidate.id));
    expect(created?.ruleVersion).toBe(original.ruleVersion);
    expect(created?.data).toMatchObject({ minimumCount: 2, deletionCount: 2 });
  } finally { await fixture.restore(); }
});
