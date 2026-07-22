import { expect, test } from "@playwright/test";
import {
  apiGet,
  apiPost,
  authorization,
  backendUrl,
  loginApi,
  type ProductView
} from "./support/testApi";

type SaleCustomerOption = {
  id: string;
  fiscalName: string;
  creditEnabled: boolean;
  creditLimit: number | null;
};

type CreditAccount = {
  customerId: string;
  outstandingDebt: number;
  overdueDebt: number;
  entries: unknown[];
};

type AuthoritativeQuote = {
  total: number;
  currency: string;
  quoteFingerprint: string;
  lines: Array<{ productId: string; total: number }>;
  promotionPreview: { appliedPromotions: unknown[] };
};

test.describe("APP VENTA production readiness", () => {
  test("publica probes y protege las metricas operativas", async ({ request }) => {
    for (const probe of ["liveness", "readiness"]) {
      const response = await request.get(`${backendUrl}/actuator/health/${probe}`);
      expect(response.ok(), `${probe}: ${await response.text()}`).toBeTruthy();
    }

    const anonymous = await request.get(`${backendUrl}/actuator/metrics`);
    expect([401, 403]).toContain(anonymous.status());

    const session = await loginApi(request);
    const authenticated = await request.get(
      `${backendUrl}/actuator/metrics`,
      { headers: authorization(session.accessToken) }
    );
    expect(authenticated.ok(), await authenticated.text()).toBeTruthy();
  });

  test("cotiza en backend y expone la cuenta corriente aislada", async ({ request }) => {
    const session = await loginApi(request);
    const [products, customers] = await Promise.all([
      apiGet<ProductView[]>(request, session.accessToken, "/products"),
      apiGet<SaleCustomerOption[]>(request, session.accessToken, "/customers/sale-options")
    ]);
    expect(products.length, "La prueba necesita al menos un producto activo").toBeGreaterThan(0);
    expect(customers.length, "La prueba necesita al menos un cliente").toBeGreaterThan(0);

    const product = products[0];
    const customer = customers[0];
    const quote = await apiPost<AuthoritativeQuote>(
      request,
      session.accessToken,
      "/pos/cash/quote",
      {
        customerId: customer.id,
        lines: [{ productId: product.id, quantity: 1, discount: 0 }],
        promotionalCouponCode: null
      }
    );
    expect(Number(quote.total)).toBeGreaterThan(0);
    expect(quote.currency).toBe("EUR");
    expect(quote.quoteFingerprint).not.toBe("");
    expect(quote.lines.some((line) => line.productId === product.id)).toBeTruthy();
    expect(Array.isArray(quote.promotionPreview.appliedPromotions)).toBeTruthy();

    const account = await apiGet<CreditAccount>(
      request,
      session.accessToken,
      `/customer-credit-accounts/${encodeURIComponent(customer.id)}`
    );
    expect(account.customerId).toBe(customer.id);
    expect(Number(account.outstandingDebt)).toBeGreaterThanOrEqual(0);
    expect(Number(account.overdueDebt)).toBeGreaterThanOrEqual(0);
    expect(Array.isArray(account.entries)).toBeTruthy();
  });
});
