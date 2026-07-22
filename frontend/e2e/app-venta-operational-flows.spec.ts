import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import {
  apiGet,
  apiPost,
  authorization,
  backendUrl,
  expectApiOk,
  loginApi,
  terminalId
} from "./support/testApi";
import { loginUi } from "./support/ui";

type SaleProduct = {
  id: string;
  code?: string | null;
  barcode?: string | null;
  name: string;
  discountType?: string;
  memberPrice?: number | string | null;
};

type SaleCustomer = {
  id: string;
  clientId?: string | null;
  fiscalName?: string | null;
  activeMember?: boolean;
  memberDiscountPercent?: number | string | null;
  creditEnabled?: boolean;
};

type Quote = {
  total: number | string;
  quoteFingerprint?: string;
  lineBreakdown?: Array<{
    productId: string;
    memberPriceSaving: number | string;
    memberDiscount: number | string;
  }>;
};

type CreditAccount = {
  customerId: string;
  outstandingDebt: number | string;
  overdueDebt: number | string;
  creditLimit?: number | string | null;
  availableCredit?: number | string | null;
  entries: unknown[];
};

type ParkedSale = { id: string; comment?: string | null };
type PaymentSession = {
  id: string;
  status: string;
  allocations: Array<{ id: string; status: string; operationId?: string }>;
};

type PaymentConfiguration = {
  rules: { integratedCardEnabled: boolean };
  providerDescriptors: Array<{ provider: string }>;
  configuration: {
    cardMode: string;
    provider: string;
    displayName: string;
    enabled: boolean;
    testMode: boolean;
    providerParameters: Record<string, string>;
    secretConfigured: boolean;
    secretVersion?: number | null;
  };
};

const apiUrl = `${backendUrl}/api/v1`;

async function openSale(page: Page) {
  await loginUi(page, "venta");
  await page.locator(".home-action-sale").click();
  await expect(page.locator(".sale-screen")).toBeVisible();
  await expect(page.getByRole("button", { name: /Efectivo/ })).toBeVisible();
}

async function addProductWithKeyboard(page: Page, product: SaleProduct) {
  const search = page.getByRole("combobox", { name: /Buscar producto/i });
  await expect(search).toBeVisible();
  await expect(search).toBeEnabled();
  await expect(search).toBeEditable();
  await page.keyboard.press("F5");
  await expect(search).toBeFocused();
  await search.fill(product.code || product.barcode || product.name);
  const first = page.getByRole("option").first();
  await expect(first).toHaveAttribute("aria-selected", "true");
  await search.press("Enter");
  await expect(page.locator(".sale-ticket-line", { hasText: product.name })).toBeVisible();
}

async function discardActivePaymentSession(request: APIRequestContext, token: string) {
  const active = await getActivePaymentSession(request, token);
  if (!active || active.status === "FINALIZED" || active.status === "CANCELLED") return;
  const discarded = await request.post(
    `${apiUrl}/pos/payment-sessions/${encodeURIComponent(active.id)}/simulator-discard`,
    { headers: authorization(token), data: { reason: "sale_entry_cleanup" } }
  );
  await expectApiOk(discarded, "Discard active simulator session");
}

async function getActivePaymentSession(request: APIRequestContext, token: string) {
  const response = await request.get(`${apiUrl}/pos/payment-sessions/active`, {
    headers: authorization(token)
  });
  await expectApiOk(response, "GET active payment session");
  if (response.status() === 204) return null;
  const responseBody = await response.text();
  return responseBody.trim() ? JSON.parse(responseBody) as PaymentSession : null;
}

async function ensureCashSession(request: APIRequestContext, token: string) {
  const status = await apiGet<{ status: "ABIERTA" | "CERRADA" }>(
    request,
    token,
    `/cash/status?terminalId=${encodeURIComponent(terminalId)}`
  );
  if (status.status !== "ABIERTA") {
    await apiPost(request, token, "/cash/sessions/open", { terminalId });
  }
}

async function simulatorConfiguration(
  request: APIRequestContext,
  token: string,
  outcome: "APPROVED" | "DECLINED" | "TIMEOUT",
  queryOutcome: "APPROVED" | "DECLINED" | "PENDING" = "APPROVED"
) {
  const original = await apiGet<PaymentConfiguration>(
    request,
    token,
    "/terminal-configuration/payment"
  );
  test.skip(original.configuration.secretConfigured,
    "La prueba no sustituye una configuración local que ya contiene secretos de producción");
  test.skip(!original.rules.integratedCardEnabled || original.providerDescriptors.length === 0,
    "La tienda debe permitir un proveedor de tarjeta integrada");
  const allowed = original.providerDescriptors.map(({ provider }) => provider);
  const provider = allowed.includes(original.configuration.provider)
    ? original.configuration.provider : allowed[0];
  const update = {
    cardMode: "INTEGRATED",
    provider,
    displayName: "E2E SIMULATOR",
    enabled: true,
    testMode: true,
    providerParameters: { simulatorOutcome: outcome, simulatorQueryOutcome: queryOutcome },
    secretReference: null,
    secretVersion: null
  };
  const response = await request.patch(`${apiUrl}/terminal-configuration/payment`, {
    headers: authorization(token), data: update
  });
  await expectApiOk(response, `Configure simulator ${outcome}`);
  return { original, provider };
}

async function restoreConfiguration(
  request: APIRequestContext,
  token: string,
  original: PaymentConfiguration
) {
  const value = original.configuration;
  const response = await request.patch(`${apiUrl}/terminal-configuration/payment`, {
    headers: authorization(token),
    data: {
      cardMode: value.cardMode,
      provider: value.provider,
      displayName: value.displayName,
      enabled: value.enabled,
      testMode: value.testMode,
      providerParameters: value.providerParameters,
      secretReference: null,
      secretVersion: null
    }
  });
  await expectApiOk(response, "Restore payment terminal configuration");
}

async function firstProductAndSession(request: APIRequestContext) {
  const session = await loginApi(request);
  await discardActivePaymentSession(request, session.accessToken);
  const products = await apiGet<SaleProduct[]>(request, session.accessToken, "/products/sale");
  expect(products.length, "La prueba necesita al menos un producto vendible").toBeGreaterThan(0);
  return { session, product: products[0] };
}

test.describe("APP VENTA operational flows", () => {
  test("busca con teclado, selecciona un socio y usa la cotización autoritativa", async ({ page, request }) => {
    const session = await loginApi(request);
    await discardActivePaymentSession(request, session.accessToken);
    const [products, customers] = await Promise.all([
      apiGet<SaleProduct[]>(request, session.accessToken, "/products/sale"),
      apiGet<SaleCustomer[]>(request, session.accessToken, "/customers/sale-options")
    ]);
    const product = products.find(candidate => candidate.discountType !== "FORBIDDEN") ?? products[0];
    const customer = customers.find(candidate =>
      candidate.activeMember && Number(candidate.memberDiscountPercent ?? 0) > 0);
    test.skip(!product || !customer,
      "La prueba necesita un producto vendible y un cliente socio con descuento");

    await openSale(page);
    await addProductWithKeyboard(page, product);
    await page.keyboard.press("F6");
    const customerDialog = page.getByRole("dialog", { name: "Seleccionar cliente" });
    await expect(customerDialog).toBeVisible();
    await customerDialog.getByRole("textbox", { name: "Buscar cliente" })
      .fill(customer!.fiscalName || customer!.clientId || customer!.id);
    const quoteResponse = page.waitForResponse(response =>
      response.url().includes("/api/v1/pos/sales/quote")
      && response.request().method() === "POST"
      && (response.request().postData() ?? "").includes(customer!.id));
    await customerDialog.getByRole("button", {
      name: new RegExp(escapeRegex(customer!.fiscalName || customer!.clientId || customer!.id), "i")
    }).click();

    const response = await quoteResponse;
    if (!response.ok()) {
      throw new Error(`La cotizacion autoritativa fallo (${response.status()}): ${await response.text()}`);
    }
    const quote = await response.json() as Quote;
    expect(Number(quote.total)).toBeGreaterThan(0);
    expect(quote.quoteFingerprint).not.toBe("");
    expect(quote.lineBreakdown?.some(line =>
      line.productId === product.id
      && (Number(line.memberPriceSaving) > 0 || Number(line.memberDiscount) > 0))).toBeTruthy();
    await expect(page.getByText(new RegExp(`Cliente: ${escapeRegex(customer!.fiscalName || customer!.clientId || "")}`, "i"))).toBeVisible();
  });

  test("aparca y recupera una venta con confirmación atómica", async ({ page, request }) => {
    const { session, product } = await firstProductAndSession(request);
    const marker = `E2E APARCADA ${Date.now()}`;
    await openSale(page);
    await addProductWithKeyboard(page, product);
    await page.getByRole("button", { name: /Ventas aparcadas/ }).click();
    const dialog = page.getByRole("dialog", { name: "Ventas aparcadas" });
    await dialog.getByLabel("Comentario de la venta").fill(marker);
    await dialog.getByRole("button", { name: "Aparcar venta actual" }).click();
    await expect(dialog.getByText(marker)).toBeVisible();

    await dialog.getByRole("button", { name: `Recuperar ${marker}` }).click();
    await expect(page.locator(".sale-ticket-line", { hasText: product.name })).toBeVisible();
    await expect(dialog).toBeHidden();
    await expect.poll(async () => {
      const sales = await apiGet<ParkedSale[]>(request, session.accessToken, "/parked-sales");
      return sales.some(sale => sale.comment === marker);
    }).toBe(false);
  });

  test("cobra en efectivo desde AvPág y muestra el resultado final", async ({ page, request }) => {
    const { session, product } = await firstProductAndSession(request);
    await ensureCashSession(request, session.accessToken);
    await openSale(page);
    await addProductWithKeyboard(page, product);
    await expect(page.getByRole("button", { name: /Efectivo/ })).toBeEnabled();
    await page.keyboard.press("PageDown");
    const cash = page.getByRole("dialog", { name: "Cobro en efectivo" });
    await expect(cash).toBeVisible();
    await cash.getByRole("button", { name: "Exacto" }).click();
    await cash.getByRole("button", { name: "Confirmar cobro" }).click();
    const result = page.getByRole("dialog", { name: "Pago completado" });
    await expect(result).toBeVisible();
    await expect(result.getByText("Dinero recibido")).toBeVisible();
    await expect(result.getByText("Cambio")).toBeVisible();
    await result.getByRole("button", { name: "Finalizar" }).click();
    await expect(page.locator(".sale-ticket-lines.sale-empty-state")).toHaveText("Sin venta iniciada");
  });

  for (const scenario of [
    { outcome: "APPROVED", expected: "completed" },
    { outcome: "DECLINED", expected: "DECLINED" },
    { outcome: "TIMEOUT", expected: "recovered" }
  ] as const) {
    test(`procesa tarjeta ${scenario.outcome.toLowerCase()} con el simulador`, async ({ page, request }) => {
      const { session, product } = await firstProductAndSession(request);
      const configured = await simulatorConfiguration(
        request, session.accessToken, scenario.outcome, "APPROVED");
      try {
        await openSale(page);
        await addProductWithKeyboard(page, product);
        await expect(page.getByRole("button", { name: /Tarjeta/ })).toBeEnabled();
        await page.keyboard.press("F11");

        if (scenario.expected === "completed") {
          await expect(page.getByRole("dialog", { name: "Pago completado" })).toBeVisible();
        } else {
          await expect.poll(async () => {
            const active = await getActivePaymentSession(request, session.accessToken);
            return active?.allocations[0]?.status;
          }).toBe(scenario.outcome);
          if (scenario.expected === "recovered") {
            await expect(page.getByText("RESULTADO INCIERTO")).toBeVisible();
            await page.getByRole("button", { name: "Consultar estado" }).click();
            await expect(page.getByRole("dialog", { name: "Pago completado" })).toBeVisible();
          } else {
            await expect(page.getByRole("button", { name: /Tarjeta/ })).toBeEnabled();
          }
        }
      } finally {
        await discardActivePaymentSession(request, session.accessToken);
        await restoreConfiguration(request, session.accessToken, configured.original);
      }
    });
  }

  test("abre crédito, gestión posterior y vuelve limpio tras cerrar usuario", async ({ page, request }) => {
    const session = await loginApi(request);
    const [products, customers] = await Promise.all([
      apiGet<SaleProduct[]>(request, session.accessToken, "/products/sale"),
      apiGet<SaleCustomer[]>(request, session.accessToken, "/customers/sale-options")
    ]);
    const customer = customers.find(candidate => candidate.creditEnabled) ?? customers[0];
    test.skip(!products[0] || !customer, "La prueba necesita producto y cliente");
    const account = await apiGet<CreditAccount>(
      request, session.accessToken,
      `/customer-credit-accounts/${encodeURIComponent(customer!.id)}`
    );
    expect(account.customerId).toBe(customer!.id);
    expect(Number(account.outstandingDebt)).toBeGreaterThanOrEqual(0);
    expect(Number(account.overdueDebt)).toBeGreaterThanOrEqual(0);
    expect(Array.isArray(account.entries)).toBeTruthy();

    await openSale(page);
    await addProductWithKeyboard(page, products[0]);
    await page.keyboard.press("F6");
    const customerDialog = page.getByRole("dialog", { name: "Seleccionar cliente" });
    await customerDialog.getByRole("textbox", { name: "Buscar cliente" })
      .fill(customer!.fiscalName || customer!.clientId || customer!.id);
    await customerDialog.getByRole("button", {
      name: new RegExp(escapeRegex(customer!.fiscalName || customer!.clientId || customer!.id), "i")
    }).click();
    await page.getByRole("button", { name: /Deudas de clientes/ }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Deudas de clientes" })).toBeVisible();
    await page.getByRole("button", { name: "Cuenta corriente" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Cuenta corriente del cliente" })).toBeVisible();
    await expect(page.getByText("Límite de crédito")).toBeVisible();
    await page.getByRole("button", { name: "Volver" }).click();

    await page.getByRole("button", { name: /Gestionar tickets/ }).click();
    const tickets = page.getByRole("dialog", { name: "Gestión posterior de tickets" });
    await expect(tickets).toBeVisible();
    await expect(tickets.getByPlaceholder("Número, cliente, fecha o estado")).toBeVisible();
    await tickets.getByRole("button", { name: "Cerrar", exact: true }).click();

    await page.locator(".report-user-button").click();
    await page.getByRole("menuitem", { name: "Cerrar usuario" }).click();
    await expect(page.locator(".login-screen")).toBeVisible();
    await loginUi(page, "venta");
    await page.locator(".home-action-sale").click();
    await expect(page.locator(".sale-ticket-lines.sale-empty-state")).toHaveText("Sin venta iniciada");
    await expect(page.locator(".sale-total strong")).toHaveText(/0,00/);
  });
});

function escapeRegex(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
