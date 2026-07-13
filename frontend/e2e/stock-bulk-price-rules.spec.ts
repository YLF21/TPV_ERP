import { expect, test } from "@playwright/test";
import {
  apiGet,
  apiPost,
  apiUrl,
  authorization,
  createProductFixture,
  deleteProductFixture,
  loginApi,
  productById,
  uniqueMarker
} from "./support/testApi";
import { loginUi, openBulkEdit, openStock } from "./support/ui";

type PriceRuleView = {
  id: string;
  name: string;
  version: number;
};

function fixedSalePriceForm(productId: string, salePrice: number) {
  return {
    scope: "PRODUCT_LIST",
    conditions: [{ type: "PRODUCT_LIST", comparator: "IN", productIds: [productId] }],
    actions: [{ type: "FIXED_PRICE", field: "SALE_PRICE", value: salePrice }]
  };
}

async function deleteRule(request: Parameters<typeof apiGet>[0], token: string, ruleId: string) {
  const rule = await apiGet<PriceRuleView>(request, token, `/product-price-rules/${encodeURIComponent(ruleId)}`);
  const response = await request.delete(
    `${apiUrl}/product-price-rules/${encodeURIComponent(ruleId)}?version=${rule.version}`,
    { headers: authorization(token) }
  );
  if (response.status() !== 404) expect(response.ok()).toBeTruthy();
}

test("previsualiza y aplica una regla real y rechaza formularios en conflicto", async ({ page, request }) => {
  const session = await loginApi(request);
  const marker = uniqueMarker("E2E-RULE");
  const ruleName = `E2E REGLA ${marker}`;
  const conflictName = `E2E CONFLICTO ${marker}`;
  const product = await createProductFixture(request, session.accessToken, marker);
  const rule = await apiPost<PriceRuleView>(request, session.accessToken, "/product-price-rules", {
    name: ruleName,
    forms: [fixedSalePriceForm(product.id, 13.25)]
  });
  const conflictRule = await apiPost<PriceRuleView>(request, session.accessToken, "/product-price-rules", {
    name: conflictName,
    forms: [fixedSalePriceForm(product.id, 14), fixedSalePriceForm(product.id, 15)]
  });

  try {
    const conflictResponse = await request.post(
      `${apiUrl}/product-price-rules/${encodeURIComponent(conflictRule.id)}/preview`,
      {
        headers: authorization(session.accessToken),
        data: { ruleVersion: conflictRule.version }
      }
    );
    expect(conflictResponse.status()).toBe(409);
    await expect(conflictResponse.json()).resolves.toMatchObject({
      code: "PRODUCT_PRICE_RULE_CONFLICT",
      productId: product.id,
      field: "SALE_PRICE",
      formIndexes: [0, 1]
    });

    await loginUi(page, "venta");
    await openStock(page, "venta");
    await openBulkEdit(page);
    await page.getByRole("button", { name: "Nuevo", exact: true }).click();
    const codeInput = page.getByPlaceholder("Código o código de barras");
    await codeInput.fill(marker);
    await codeInput.press("Enter");
    await page.getByRole("button", { name: "Precio venta", exact: true }).click();
    await page.getByRole("button", { name: "Aplicar regla de precio", exact: true }).click();

    const dialog = page.getByRole("dialog", { name: "Reglas de precio" });
    await expect(dialog.getByText(ruleName, { exact: true })).toBeVisible();
    await dialog.getByText(ruleName, { exact: true }).click();
    await dialog.getByRole("button", { name: "Vista previa", exact: true }).click();
    await expect(dialog.getByText("1 productos coincidentes", { exact: true })).toBeVisible();
    await expect(dialog.getByRole("row").filter({ hasText: product.name })).toContainText("13.25");

    await dialog.getByRole("button", { name: "Aplicar regla", exact: true }).click();
    await dialog.getByRole("alertdialog").getByRole("button", { name: "Confirmar" }).press("Enter");
    await expect.poll(async () => (await productById(request, session.accessToken, product.id)).salePrice)
      .toBe(13.25);
  } finally {
    await deleteRule(request, session.accessToken, conflictRule.id).catch(() => undefined);
    await deleteRule(request, session.accessToken, rule.id).catch(() => undefined);
    await deleteProductFixture(request, session.accessToken, product.id);
  }
});
