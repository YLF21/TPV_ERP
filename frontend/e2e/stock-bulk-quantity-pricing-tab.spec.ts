import { expect, test } from "@playwright/test";
import { gestionUrl } from "./support/testApi";
import { openBulkEdit, openStock } from "./support/ui";

// The page is real; every API response is local to this test and never touches store data.
test("Edición masiva muestra Precio por cantidad entre Oferta e Imagen", async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1920, height: 900 });
  const product = {
    id: "quantity-bulk-e2e", version: 4, code: "CANT-E2E", name: "Café de prueba",
    familyId: "family-1", taxId: "tax-1", salePrice: "4.50", purchasePrice: "2.00",
    productType: "UNIT", priceUseMode: "NORMAL", discountType: "NORMAL", active: true,
    taxesIncluded: true, quantityPricing: { mode: "PACK", enabled: false,
      tiers: [{ quantity: 3, price: "9.99" }, { quantity: 5, price: "15.25" }] }
  };
  const row = {
    id: "row-quantity-e2e", selected: true, query: product.code, draft: {},
    product: {
      productId: product.id, version: product.version, code: product.code, name: product.name,
      familyId: product.familyId, taxId: product.taxId, salePrice: product.salePrice,
      purchasePrice: product.purchasePrice, productType: product.productType,
      discountType: product.discountType, backendDiscountType: product.discountType,
      priceUseMode: product.priceUseMode, active: "common.yes", taxesIncluded: "common.yes",
      quantityPricing: product.quantityPricing, totalQuantity: 3
    }
  };
  const draft = {
    id: "draft-quantity-e2e", code: "EM-E2E", seriesId: "series-1", versionNumber: 1,
    name: "Lista cantidad de prueba", status: "PENDING", version: 2, content: [row],
    createdById: "user-1", createdBy: "DEMO", createdAt: "2026-09-20T10:00:00Z",
    updatedById: "user-1", updatedBy: "DEMO", updatedAt: "2026-09-20T10:00:00Z", comments: []
  };
  const writes: string[] = [];
  await page.route("**/api/v1/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname.slice("/api/v1".length);
    if (path === "/auth/login" && request.method() === "POST") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
        accessToken: "fixture-token", userName: "DEMO", role: "ADMIN", permissions: ["ADMIN"]
      }) });
      return;
    }
    if (request.method() !== "GET") {
      writes.push(`${request.method()} ${path}`);
      await route.fulfill({ status: 500, contentType: "application/json", body: '{"code":"unexpected_write"}' });
      return;
    }
    const body = path.startsWith("/stock/page")
      ? { items: [{ product, stock: [{ productId: product.id, warehouseId: "warehouse-1", quantity: 3 }] }], hasMore: false }
      : path === "/warehouses"
        ? [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true, active: true }]
        : path === "/families"
          ? [{ id: "family-1", familyCode: "001", name: "Bebidas", defaultFamily: true }]
          : path === "/taxes/selectable"
            ? [{ id: "tax-1", percentage: "7", defaultTax: true }]
            : path === "/product-bulk-edits" ? [draft]
              : path === `/product-bulk-edits/${draft.id}` ? draft
                : path === "/products" ? [product] : [];
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  });

  await page.goto(gestionUrl);
  await page.getByLabel("Usuario", { exact: true }).fill("DEMO");
  await page.getByLabel("Contraseña", { exact: true }).fill("0000");
  await page.getByRole("button", { name: "Entrar" }).click();
  await openStock(page, "gestion");
  await openBulkEdit(page);
  await page.getByText(draft.name, { exact: true }).dblclick();

  const tabs = page.locator(".bulk-edit-tabs button");
  await expect(tabs).toContainText([
    "Página principal", "Información de productos", "Precio venta", "Precio de miembro",
    "Precio mayor", "Precio oferta", "Precio por cantidad", "Imagen"
  ]);
  await tabs.filter({ hasText: "Precio por cantidad" }).click();
  await expect(page.getByRole("button", { name: "Configurar precios por cantidad" })).toHaveCount(0);
  const header = page.locator(".bulk-edit-head");
  await expect(header.locator(":scope > *")).toHaveCount(12); // 11 data columns plus selection.
  await expect(header).toContainText("Regla de precio por cantidad");
  await expect(header).toContainText("Cantidad");
  await expect(header).toContainText("Precio");
  await expect(header).toContainText("Stock");
  const productRow = page.locator('[data-bulk-row-id="row-quantity-e2e"]');
  await expect(productRow).toContainText(product.code);
  const ruleSelector = productRow.getByRole("combobox", { name: "Precios por cantidad" });
  await expect(ruleSelector).toBeInViewport();
  await expect(ruleSelector).toHaveValue("PACK");
  await expect(ruleSelector.locator("option")).toContainText([
    "Sin precios por cantidad", "Precio por lotes", "Precio unitario por umbral"
  ]);
  await expect(productRow.getByText("No aplicado")).toBeVisible();
  await expect(productRow.getByRole("textbox", { name: "Cantidad 1" })).toHaveValue("3");
  await expect(productRow.getByRole("textbox", { name: "Precio del lote 2" })).toHaveValue("15.25");
  expect(writes).toEqual([]);
  await page.screenshot({ path: testInfo.outputPath("precio-por-cantidad.png") });
});
