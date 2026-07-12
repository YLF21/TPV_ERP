import { expect, test } from "@playwright/test";
import {
  cleanupBulkDrafts,
  createProductFixture,
  deleteProductFixture,
  loginApi,
  productById,
  uniqueMarker
} from "./support/testApi";
import { chooseFileAction, loginUi, openBulkEdit, openStock } from "./support/ui";

test("crea, guarda, reabre, aplica y versiona una lista conservando comentarios", async ({ page, request }) => {
  const session = await loginApi(request);
  const marker = uniqueMarker("E2E-BULK");
  const listName = `E2E LISTA ${marker}`;
  const comment = `Comentario ${marker}`;
  const product = await createProductFixture(request, session.accessToken, marker);

  try {
    await loginUi(page, "venta");
    await openStock(page, "venta");
    await openBulkEdit(page);
    await page.getByRole("button", { name: "Nuevo", exact: true }).click();

    const codeInput = page.getByPlaceholder("Código o código de barras");
    await codeInput.fill(marker);
    await codeInput.press("Enter");
    await expect(page.locator(".bulk-edit-row").filter({ hasText: marker })).toBeVisible();

    const salePrice = page.getByLabel("Precio venta").first();
    await salePrice.fill("12.75");
    await salePrice.press("Enter");
    await expect(page.getByLabel("Precio socio").first()).toBeFocused();

    await page.keyboard.press("Control+s");
    const saveDialog = page.getByRole("dialog", { name: "Guardar lista de productos" });
    await saveDialog.getByLabel("Nombre de la lista").fill(listName);
    await saveDialog.getByLabel("Nombre de la lista").press("Enter");
    await expect(page.getByRole("status")).toContainText("Lista guardada");

    await chooseFileAction(page, "Comentarios");
    const commentsDialog = page.getByRole("dialog", { name: "Comentarios" });
    await commentsDialog.getByLabel("Nuevo comentario").fill(comment);
    await commentsDialog.getByLabel("Nuevo comentario").press("Enter");
    await expect(commentsDialog.getByText(comment, { exact: true })).toBeVisible();
    await commentsDialog.getByRole("button", { name: "Cerrar" }).last().click();

    await page.keyboard.press("Escape");
    const listRow = page.getByRole("row").filter({ hasText: listName });
    await expect(listRow).toBeVisible();
    await listRow.click();
    await page.locator(".stock-bulk-workspace-table-wrap").press("Enter");
    await expect(page.getByText(listName, { exact: false })).toBeVisible();

    await chooseFileAction(page, "Aplicar cambios");
    const applyDialog = page.getByRole("dialog", { name: "Aplicar cambios" });
    await applyDialog.getByRole("button", { name: "Confirmar" }).press("Enter");
    await expect(page.getByRole("status")).toContainText("Cambios aplicados");
    await expect.poll(async () => (await productById(request, session.accessToken, product.id)).salePrice)
      .toBe(12.75);

    await page.keyboard.press("Escape");
    const appliedRow = page.getByRole("row").filter({ hasText: listName }).filter({ hasText: "Aplicado" });
    await expect(appliedRow).toBeVisible();
    await appliedRow.click();
    await page.locator(".stock-bulk-workspace-table-wrap").press("Enter");
    await expect(page.getByText(/V2/).first()).toBeVisible();

    await chooseFileAction(page, "Comentarios");
    await expect(page.getByRole("dialog", { name: "Comentarios" }).getByText(comment, { exact: true })).toBeVisible();
  } finally {
    await cleanupBulkDrafts(request, session.accessToken, listName);
    await deleteProductFixture(request, session.accessToken, product.id);
  }
});
