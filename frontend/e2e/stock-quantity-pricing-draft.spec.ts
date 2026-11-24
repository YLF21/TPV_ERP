import { expect, test, type Locator, type Page } from "@playwright/test";
import { gestionUrl } from "./support/testApi";
import { openStock } from "./support/ui";

async function bounds(locator: Locator) {
  const box = await locator.boundingBox();
  expect(box, "el editor debe tener geometría visible").not.toBeNull();
  return box!;
}

async function expectInsideViewport(page: Page, dialog: Locator) {
  const box = await bounds(dialog);
  const viewport = page.viewportSize()!;
  expect(box.x).toBeGreaterThanOrEqual(0);
  expect(box.y).toBeGreaterThanOrEqual(0);
  expect(box.x + box.width).toBeLessThanOrEqual(viewport.width + 1);
  expect(box.y + box.height).toBeLessThanOrEqual(viewport.height + 1);
}

// API fixture: a real Gestión page and real product dialogs, without a backend session.
test("Gestión mantiene el editor de precios por cantidad estable y controla su borrador", async ({ page }) => {
  await page.setViewportSize({ width: 1100, height: 760 });
  const product = {
    id: "quantity-e2e-product", code: "E2E-LOTES", name: "Café de prueba",
    familyId: "family-1", taxId: "tax-1", salePrice: "4.50",
    purchasePrice: "2.00", productType: "UNIT", priceUseMode: "NORMAL",
    discountType: "NORMAL", active: true, taxesIncluded: true,
    packageQuantity: "1", quantityPricing: null,
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
    const body = path === "/auth/login" ? {}
      : path.startsWith("/stock/page")
      ? { items: [{ product, stock: [{ productId: product.id, warehouseId: "warehouse-1", quantity: 3 }] }], hasMore: false }
      : path === "/warehouses"
        ? [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true, active: true }]
        : path === "/families"
          ? [{ id: "family-1", familyCode: "001", name: "Bebidas", defaultFamily: true }]
          : path === "/taxes/selectable"
            ? [{ id: "tax-1", percentage: "7", defaultTax: true }]
            : path === "/products" ? [product] : [];
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  });
  await page.goto(gestionUrl);
  await page.getByLabel("Usuario", { exact: true }).fill("DEMO");
  await page.getByLabel("Contraseña", { exact: true }).fill("0000");
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.locator(".gestion-screen")).toBeVisible();
  await openStock(page, "gestion");
  await expect(page.getByText(product.name, { exact: true }).first()).toBeVisible();
  await page.locator("article.stock-row").filter({ hasText: product.code }).dblclick();
  const information = page.getByRole("dialog", { name: "Información del producto" });
  await expect(information).toBeVisible();
  await information.getByRole("button", { name: /Modificar producto/ }).click();
  const edit = page.getByRole("dialog", { name: "Modificar producto" });
  await expect(edit).toBeVisible();
  await edit.getByRole("button", { name: "Configurar precios por cantidad" }).click();
  const prices = page.getByRole("dialog", { name: "Precios por cantidad" });
  await expect(prices).toBeVisible();
  const modes = prices.getByRole("radio");
  await expect(modes).toHaveCount(2);
  await expect(prices.getByRole("radio", { name: "Precio por lotes" })).toBeChecked();
  await expect(prices.locator(".quantity-pricing-tier")).toHaveCount(1);
  await expect(prices.locator(".quantity-pricing-tier").getByLabel("Cantidad")).toHaveValue("2");
  await expect(prices.getByLabel("Precio del lote")).toHaveValue("");
  const firstTier = prices.locator(".quantity-pricing-tier").first();
  const quantityInput = await bounds(firstTier.getByLabel("Cantidad"));
  const priceInput = await bounds(firstTier.getByLabel("Precio del lote"));
  const removeButton = await bounds(firstTier.getByRole("button", { name: "Eliminar tramo 1" }));
  for (const input of [quantityInput, priceInput]) {
    expect(Math.abs(removeButton.width - input.height)).toBeLessThanOrEqual(1);
    expect(Math.abs(removeButton.height - input.height)).toBeLessThanOrEqual(1);
    expect(Math.abs(removeButton.y - input.y)).toBeLessThanOrEqual(1);
    expect(Math.abs(removeButton.y + removeButton.height - input.y - input.height)).toBeLessThanOrEqual(1);
  }
  await prices.getByRole("button", { name: "Aplicar", exact: true }).click();
  await expect(prices).toBeVisible();
  await expect(prices.getByRole("alert")).toHaveCount(1);
  await expect(prices.getByRole("alert")).toHaveText("Hay campos sin rellenar.");
  await prices.getByLabel("Precio del lote").fill("1.50");
  await expect(prices.getByRole("alert")).toHaveCount(0);
  const initial = await bounds(prices);
  const selectedMode = prices.locator(".quantity-pricing-modes label").filter({ has: page.getByRole("radio", { name: "Precio por lotes" }) });
  await expect.poll(async () => selectedMode.evaluate(element => getComputedStyle(element).borderColor)).toBe("rgb(0, 101, 254)");
  await expect.poll(async () => selectedMode.evaluate(element => getComputedStyle(element).backgroundColor)).toBe("rgb(0, 101, 254)");
  await expect.poll(async () => (await bounds(prices)).height).toBe(initial.height);
  if (process.env.E2E_CAPTURE_PRICING_WINDOW) await prices.screenshot({ path: process.env.E2E_CAPTURE_PRICING_WINDOW });
  await expect(prices.getByLabel("Precio del lote")).toHaveValue("1.50");
  await prices.getByRole("radio", { name: "Precio unitario por umbral" }).check();
  await expect.poll(async () => (await bounds(prices)).height).toBe(initial.height);
  await prices.getByRole("radio", { name: "Precio por lotes" }).check();
  await expect(prices.getByLabel("Precio del lote")).toHaveValue("1.50");

  const add = prices.getByRole("button", { name: /añadir tramo/i });
  for (let index = 0; index < 14; index++) await add.click();
  await expect(prices.locator(".quantity-pricing-tier")).toHaveCount(15);
  await prices.getByRole("button", { name: "Eliminar tramo 15" }).click();
  await expect(prices.locator(".quantity-pricing-tier")).toHaveCount(14);
  const remove = prices.getByRole("button", { name: "Eliminar tramo 1", exact: true });
  await expect(remove).toBeVisible();
  await expect.poll(async () => remove.evaluate(element => getComputedStyle(element).backgroundColor)).toBe("rgb(225, 230, 235)");
  await expect.poll(async () => remove.evaluate(element => getComputedStyle(element).borderStyle)).toBe("solid");
  await add.click();
  await expect(prices.locator(".quantity-pricing-tier")).toHaveCount(15);
  await expect.poll(async () => (await bounds(prices)).height).toBe(initial.height);
  const body = prices.locator(".quantity-pricing-body");
  expect(await body.evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true);
  await body.evaluate(element => { element.scrollTop = element.scrollHeight; });
  expect(await body.evaluate(element => element.scrollTop)).toBeGreaterThan(0);
  await expect(prices.locator(".quantity-pricing-header")).toBeVisible();
  await expect(prices.getByRole("button", { name: "Guardar", exact: true })).toBeVisible();
  await expect(prices.getByRole("button", { name: "Aplicar", exact: true })).toBeVisible();
  await expect(prices.getByRole("button", { name: "No aplicar", exact: true })).toBeVisible();
  expect(Number(await prices.getByRole("button", { name: "Aplicar", exact: true }).evaluate(element => getComputedStyle(element).fontWeight))).toBeGreaterThanOrEqual(700);
  await expectInsideViewport(page, prices);

  const header = prices.locator(".quantity-pricing-header");
  const beforeDrag = await bounds(prices);
  const handle = await bounds(header);
  await page.mouse.move(handle.x + 90, handle.y + handle.height / 2);
  await page.mouse.down();
  await page.mouse.move(handle.x + 340, handle.y + 170, { steps: 8 });
  await page.mouse.up();
  const afterDrag = await bounds(prices);
  expect(afterDrag.x).toBeGreaterThan(beforeDrag.x + 20);
  expect(afterDrag.y).toBeGreaterThan(beforeDrag.y + 20);
  await expectInsideViewport(page, prices);
  await page.mouse.move(afterDrag.x + 90, afterDrag.y + handle.height / 2);
  await page.mouse.down();
  await page.mouse.move(-100, -100, { steps: 8 });
  await page.mouse.up();
  await expectInsideViewport(page, prices);
  await page.setViewportSize({ width: 480, height: 400 });
  await expectInsideViewport(page, prices);
  expect(await body.evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true);
  await expect(prices.getByRole("button", { name: "Aplicar", exact: true })).toBeVisible();
  await page.setViewportSize({ width: 1100, height: 760 });
  await expectInsideViewport(page, prices);

  // Playwright input events update every React draft before saving.
  for (const input of await prices.getByLabel("Precio del lote").all()) {
    if (await input.inputValue() !== "1.50") await input.fill("1.50");
  }
  await prices.getByRole("button", { name: "Cerrar" }).click();
  const warning = page.getByRole("alertdialog", { name: "Cambios sin confirmar" });
  await expect(warning).toBeVisible();
  await expect(warning.getByRole("button", { name: "Seguir editando", exact: true })).toBeFocused();
  await warning.getByRole("button", { name: "Seguir editando", exact: true }).click();
  await expect(warning).toBeHidden();
  await expect(prices.getByLabel("Precio del lote").first()).toHaveValue("1.50");
  await prices.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(prices).toBeHidden();
  await expect(edit.getByText("Lotes: 15 tramos")).toBeVisible();
  await expect(edit.getByText("No aplicado", { exact: true })).toBeVisible();

  await edit.getByRole("button", { name: "Configurar precios por cantidad" }).click();
  await expect(prices).toBeVisible();
  await expect(prices.locator(".quantity-pricing-tier")).toHaveCount(15);
  await expect(prices.getByLabel("Precio del lote").first()).toHaveValue("1.50");
  await prices.getByRole("button", { name: "Aplicar", exact: true }).click();
  await expect(prices).toBeHidden();
  await expect(edit.getByText("Aplicado", { exact: true })).toBeVisible();
  await expect(edit.getByText("Lotes: 15 tramos")).toBeVisible();

  await edit.getByRole("button", { name: "Configurar precios por cantidad" }).click();
  await expect(prices).toBeVisible();
  await prices.getByRole("button", { name: "No aplicar", exact: true }).click();
  await expect(prices).toBeHidden();
  await expect(edit.getByText("No aplicado", { exact: true })).toBeVisible();
  await expect(edit.getByText("Lotes: 15 tramos")).toBeVisible();

  await edit.getByRole("button", { name: "Configurar precios por cantidad" }).click();
  await expect(prices.locator(".quantity-pricing-tier")).toHaveCount(15);
  await prices.getByRole("button", { name: "Aplicar", exact: true }).click();
  await expect(prices).toBeHidden();
  await expect(edit.getByText("Aplicado", { exact: true })).toBeVisible();
  await expect(edit.getByText("Lotes: 15 tramos")).toBeVisible();

  await edit.getByRole("button", { name: "Configurar precios por cantidad" }).click();
  await expect(prices).toBeVisible();
  await prices.getByLabel("Precio del lote").first().fill("1.25");
  await prices.getByRole("button", { name: "Guardar", exact: true }).click();
  await expect(prices).toBeHidden();
  await expect(edit.getByText("Aplicado", { exact: true })).toBeVisible();
  await expect(edit.getByText("Lotes: 15 tramos")).toBeVisible();

  await edit.getByRole("button", { name: "Configurar precios por cantidad" }).click();
  await expect(prices.getByLabel("Precio del lote").first()).toHaveValue("1.25");
  await prices.getByLabel("Precio del lote").first().fill("1.10");
  await prices.getByRole("button", { name: "Cerrar" }).click();
  await expect(warning).toBeVisible();
  await warning.getByRole("button", { name: /descartar/i }).click();
  await expect(prices).toBeHidden();
  await expect(edit.getByText("Lotes: 15 tramos")).toBeVisible();
  await expect(edit.getByText("Aplicado", { exact: true })).toBeVisible();
  expect(writes).toEqual([]);

  await edit.getByRole("button", { name: "Cerrar" }).click();
  await expect(edit).toBeHidden();
  await expect(information).toBeVisible();
  expect(writes).toEqual([]);
  await information.getByRole("button", { name: /Modificar producto/ }).click();
  const reopened = page.getByRole("dialog", { name: "Modificar producto" });
  await expect(reopened.getByText("Lotes: 15 tramos")).toBeHidden();
  await expect(reopened.getByText("No aplicado", { exact: true })).toBeVisible();
  await reopened.getByRole("button", { name: "Configurar precios por cantidad" }).click();
  await expect(prices.locator(".quantity-pricing-tier")).toHaveCount(1);
  await expect(prices.getByLabel("Precio del lote")).toHaveValue("");
  expect(writes).toEqual([]);
});
