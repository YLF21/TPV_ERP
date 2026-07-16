import { expect, test } from "@playwright/test";
import { mkdirSync, writeFileSync } from "node:fs";
import {
  apiUrl,
  authorization,
  cleanupBulkDrafts,
  createProductFixture,
  deleteProductFixture,
  loginApi,
  productById,
  uniqueMarker
} from "./support/testApi";
import { chooseFileAction, loginUi, openBulkEdit, openStock } from "./support/ui";

const tinyPng = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64"
);

test("empareja una imagen por código, la guarda y la aplica al producto", async ({ page, request }, testInfo) => {
  test.setTimeout(120_000);
  const session = await loginApi(request);
  const marker = uniqueMarker("E2E-IMAGE");
  const listName = `E2E IMAGEN ${marker}`;
  const product = await createProductFixture(request, session.accessToken, marker);

  try {
    const stockPageResponse = await request.get(`${apiUrl}/stock/page?limit=250`, {
      headers: authorization(session.accessToken)
    });
    expect(stockPageResponse.ok(), await stockPageResponse.text()).toBeTruthy();
    const stockPage = await stockPageResponse.json() as { items: Array<{ product: { id: string } }> };
    expect(stockPage.items.some((item) => item.product.id === product.id)).toBeTruthy();

    await loginUi(page, "venta");
    await openStock(page, "venta");
    await openBulkEdit(page);
    await page.getByRole("button", { name: "Nuevo", exact: true }).click();

    const codeInput = page.getByPlaceholder("Código o código de barras");
    await codeInput.fill(marker);
    await codeInput.press("Enter");
    await expect(page.locator(".bulk-edit-row").filter({ hasText: marker })).toBeVisible();

    await page.getByRole("button", { name: "Imagen", exact: true }).click();
    const imageDirectory = testInfo.outputPath("imagenes");
    mkdirSync(imageDirectory, { recursive: true });
    writeFileSync(`${imageDirectory}/${marker}.png`, tinyPng);
    const chooserPromise = page.waitForEvent("filechooser");
    await page.getByRole("button", { name: "Abrir carpeta", exact: true }).click();
    await (await chooserPromise).setFiles(imageDirectory);
    await expect(page.getByText(`${marker}.png`, { exact: true })).toBeVisible();

    await page.getByRole("button", { name: "Comparar por código", exact: true }).click();
    await expect(page.getByRole("status")).toContainText("1 coincidencias");
    await expect(page.locator(".stock-bulk-image-row.matched")).toContainText(product.name);

    await page.keyboard.press("Control+s");
    const saveDialog = page.getByRole("dialog", { name: "Guardar lista de productos" });
    await saveDialog.getByLabel("Nombre de la lista").fill(listName);
    await saveDialog.getByLabel("Nombre de la lista").press("Enter");
    await expect(page.getByRole("status")).toContainText("Lista guardada");

    await page.getByRole("button", { name: "Página principal", exact: true }).click();
    await chooseFileAction(page, "Aplicar cambios");
    const applyDialog = page.getByRole("dialog", { name: "Aplicar cambios" });
    await applyDialog.getByRole("button", { name: "Confirmar" }).press("Enter");
    await expect(page.getByRole("status")).toContainText("Cambios aplicados");

    await expect.poll(async () => (await productById(request, session.accessToken, product.id)).imageId)
      .not.toBeNull();
    const imageResponse = await request.get(
      `${apiUrl}/products/${encodeURIComponent(product.id)}/image`,
      { headers: authorization(session.accessToken) }
    );
    expect(imageResponse.ok()).toBeTruthy();
    expect((await imageResponse.body()).byteLength).toBeGreaterThan(0);

    await page.getByRole("button", { name: "Volver a listas", exact: true }).click();
    await page.getByRole("button", { name: "Stock", exact: true }).click();
    await page.getByRole("searchbox", { name: "Buscar artículo" }).fill(marker);
    const stockRow = page.locator(".stock-row").filter({ hasText: marker });
    await expect(stockRow).toBeVisible();
    await stockRow.dblclick();

    const detailDialog = page.getByRole("dialog").filter({ hasText: product.name });
    await expect(detailDialog.getByRole("heading", { name: "Información del producto" })).toBeVisible();
    await expect(detailDialog.getByRole("button", { name: "Stock por almacén" })).toBeVisible();
    await expect(detailDialog.locator(".stock-product-information-image img")).toBeVisible();
    const detailScreenshotPath = testInfo.outputPath("detalle-producto.png");
    await page.screenshot({ fullPage: true, path: detailScreenshotPath });
    await testInfo.attach("detalle-producto", { path: detailScreenshotPath, contentType: "image/png" });

    await detailDialog.getByRole("button", { name: "Historial de venta" }).click();
    await expect(detailDialog.getByRole("heading", { name: "Información del producto" })).toBeVisible();

    await detailDialog.getByRole("button", { name: "Modificar producto" }).click();
    const editDialog = page.getByRole("dialog", { name: "Modificar producto" });
    await expect(editDialog).toBeVisible();
    await expect(editDialog.locator(".product-image-preview img")).toBeVisible();
  } finally {
    await cleanupBulkDrafts(request, session.accessToken, listName);
    await deleteProductFixture(request, session.accessToken, product.id);
  }
});
