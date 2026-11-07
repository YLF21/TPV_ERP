import { expect, test } from "@playwright/test";
import {
  cleanupBulkDrafts,
  createProductFixture,
  deleteProductFixture,
  loginApi,
  productById,
  uniqueMarker
} from "./support/testApi";
import { addBulkProduct, loginUi, openBulkEdit, openStock } from "./support/ui";

test("aplica acciones de precio a la lista y conserva el catálogo hasta aplicar cambios", async ({ page, request }) => {
  const session = await loginApi(request);
  const marker = uniqueMarker("E2E-PRICE");
  const listName = `E2E ACCIONES ${marker}`;
  const product = await createProductFixture(request, session.accessToken, marker);

  try {
    await loginUi(page, "venta");
    await openStock(page, "venta");
    await openBulkEdit(page);
    await page.getByRole("button", { name: "Nuevo F5", exact: true }).click();
    await addBulkProduct(page, marker);
    await page.getByRole("checkbox", { name: "Seleccionar toda la lista" }).check();
    await page.getByRole("button", { name: "Precio venta", exact: true }).click();
    await page.getByRole("button", { name: "Aplicar acciones de precio", exact: true }).click();

    const dialog = page.getByRole("dialog", { name: "Aplicar acciones de precio" });
    await dialog.getByRole("spinbutton", { name: "Precio fijo" }).fill("13.25");
    await dialog.getByRole("button", { name: "Aplicar", exact: true }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByRole("textbox", { name: "Precio venta", exact: true }).first()).toHaveValue("13.25");
    expect((await productById(request, session.accessToken, product.id)).salePrice).toBe(10);

    await page.getByRole("button", { name: "Guardar", exact: true }).click();
    const saveDialog = page.getByRole("dialog", { name: "Guardar lista de productos" });
    await saveDialog.getByLabel("Nombre de la lista").fill(listName);
    await saveDialog.getByRole("button", { name: "Guardar", exact: true }).click();
    await expect(page.locator(".bulk-edit-status")).toContainText("Lista guardada");
    await page.getByRole("button", { name: "Aplicar cambios", exact: true }).click();
    await page.getByRole("dialog", { name: "Aplicar cambios" })
      .getByRole("button", { name: "Aplicar cambios", exact: true }).click();
    await expect.poll(async () => (await productById(request, session.accessToken, product.id)).salePrice).toBe(13.25);
  } finally {
    await cleanupBulkDrafts(request, session.accessToken, listName);
    await deleteProductFixture(request, session.accessToken, product.id);
  }
});
