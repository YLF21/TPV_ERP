import { readFileSync } from "node:fs";
import { expect, test } from "@playwright/test";
import {
  apiGet,
  apiPost,
  apiUrl,
  authorization,
  createProductFixture,
  expectApiOk,
  loginApi,
  uniqueMarker
} from "./support/testApi";
import {
  chooseFileAction,
  chooseImportAction,
  clearBulkRows,
  loginUi,
  openBulkEdit,
  openStock
} from "./support/ui";

test("Archivo ejecuta todas las importaciones reales y la exportación Excel", async ({ page, request }, testInfo) => {
  const admin = await loginApi(request);
  const marker = uniqueMarker("E2E-IMPORT");
  const product = await createProductFixture(request, admin.accessToken, marker);
  const [warehouse] = await apiGet<Array<{ id: string }>>(request, admin.accessToken, "/warehouses");
  const supplier = await apiPost<{ id: string }>(request, admin.accessToken, "/suppliers", {
    legalName: `PROVEEDOR ${marker}`,
    tradeName: null,
    documentType: "OTRO",
    documentNumber: marker,
    address: null,
    phone: null,
    email: null,
    notes: "Proveedor temporal E2E"
  });
  await apiPost(request, admin.accessToken, `/products/${encodeURIComponent(product.id)}/suppliers`, {
    supplierId: supplier.id,
    supplierReference: marker,
    principal: true
  });
  const warehouseInputLine = {
    productId: product.id,
    quantity: 1,
    unitPrice: 5,
    discount: 0,
    priceOverridden: true,
    productName: product.name
  };
  const invoiceDraft = await apiPost<{ id: string }>(request, admin.accessToken, "/warehouse-inputs", {
    warehouseId: warehouse.id,
    date: "2026-07-12",
    supplierId: supplier.id,
    origin: "E2E",
    externalNumber: `FC-${marker}`,
    concept: "Factura de compra E2E",
    documentType: "FACTURA_ENTRADA",
    priceSource: "PURCHASE",
    globalDiscount: 0,
    sourceDeliveryNoteIds: [],
    lines: [warehouseInputLine]
  });
  const deliveryDraft = await apiPost<{ id: string }>(request, admin.accessToken, "/warehouse-inputs", {
    warehouseId: warehouse.id,
    date: "2026-07-12",
    supplierId: supplier.id,
    origin: "E2E",
    externalNumber: `AC-${marker}`,
    concept: "Albarán de compra E2E",
    documentType: "ALBARAN_ENTRADA",
    priceSource: "PURCHASE",
    globalDiscount: 0,
    sourceDeliveryNoteIds: [],
    lines: [warehouseInputLine]
  });

  try {
    await expectApiOk(
      await request.post(`${apiUrl}/warehouse-inputs/${encodeURIComponent(invoiceDraft.id)}/confirm`, {
        headers: authorization(admin.accessToken)
      }),
      "confirmar factura de compra E2E"
    );
    await expectApiOk(
      await request.post(`${apiUrl}/warehouse-inputs/${encodeURIComponent(deliveryDraft.id)}/confirm`, {
        headers: authorization(admin.accessToken)
      }),
      "confirmar albarán de compra E2E"
    );

    const [suppliers, invoices, deliveryNotes] = await Promise.all([
      apiGet<unknown[]>(request, admin.accessToken, "/product-bulk-edits/suppliers"),
      apiGet<Array<{ id: string; status?: string }>>(request, admin.accessToken, "/product-bulk-edits/purchase-invoices"),
      apiGet<Array<{ id: string; status?: string }>>(request, admin.accessToken, "/product-bulk-edits/purchase-delivery-notes")
    ]);
    expect(suppliers.length, "La prueba necesita un proveedor").toBeGreaterThan(0);
    expect(invoices).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: invoiceDraft.id, status: "CONFIRMADA" })
    ]));
    expect(deliveryNotes).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: deliveryDraft.id, status: "CONFIRMADA" })
    ]));

    await loginUi(page, "venta");
    await openStock(page, "venta");
    await openBulkEdit(page);
    await page.getByRole("button", { name: "Nuevo", exact: true }).click();

    const codeInput = page.getByPlaceholder("Código o código de barras");
    await codeInput.fill(marker);
    await codeInput.press("Enter");
    // Stock is paged: products outside the loaded page are resolved through
    // the existing finder, which requires choosing its matching result.
    const initialProductRow = page.locator(".bulk-edit-row").filter({ hasText: marker });
    const foundProduct = page.getByRole("dialog", { name: "Buscar producto", exact: true })
      .getByRole("button", { name: new RegExp(marker) });
    await expect(initialProductRow.or(foundProduct)).toBeVisible();
    if (await foundProduct.isVisible()) await foundProduct.click();
    await expect(page.locator(".bulk-edit-row").filter({ hasText: marker })).toBeVisible();

    const downloadPromise = page.waitForEvent("download");
    await chooseFileAction(page, "Exportar Excel");
    const download = await downloadPromise;
    const exportedFile = await download.path();
    expect(exportedFile).not.toBeNull();
    const roundTripPath = testInfo.outputPath("bulk-roundtrip.xlsx");
    await download.saveAs(roundTripPath);
    const exportedWorkbook = readFileSync(roundTripPath);

    await clearBulkRows(page);
    await chooseImportAction(page, "Importar Excel");
    const importer = page.getByRole("dialog", { name: "Importar Excel", exact: true });
    const chooserPromise = page.waitForEvent("filechooser");
    const readResponsePromise = page.waitForResponse((response) =>
      response.url().endsWith("/product-excel-imports/read") && response.request().method() === "POST"
    );
    await importer.getByRole("button", { name: "Abrir Excel", exact: true }).click();
    await (await chooserPromise).setFiles({
      name: download.suggestedFilename(),
      mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      buffer: exportedWorkbook
    });
    const readResponse = await readResponsePromise;
    const readBody = await readResponse.text();
    expect(readResponse.ok(), `read response ${readResponse.status()}: ${readBody}`).toBeTruthy();
    await importer.getByRole("button", { name: "Configuración del archivo", exact: true }).click();
    const codeColumn = importer.getByLabel("Código Columna Excel");
    await expect(codeColumn).toBeEnabled({ timeout: 30000 });
    await codeColumn.fill("A");
    await page.getByRole("button", { name: "Aplicar", exact: true }).click();
    await importer.getByRole("button", { name: /^Productos importables/ }).click();
    await expect(importer.getByRole("button", { name: "Importar a edición masiva", exact: true })).toBeEnabled();
    await importer.getByRole("button", { name: "Importar a edición masiva", exact: true }).click();
    await expect(page.locator(".bulk-edit-row").filter({ hasText: marker })).toBeVisible();

    await clearBulkRows(page);
    await chooseImportAction(page, "Importar proveedor");
    const supplierDialog = page.getByRole("dialog", { name: "Importar proveedor" });
    await expect(supplierDialog.getByRole("option").first()).toBeVisible();
    await supplierDialog.getByRole("option").first().press("Enter");
    await expect(page.getByRole("status")).toContainText(/productos de/i);

    await clearBulkRows(page);
    await chooseImportAction(page, "Importar factura de entrada");
    const invoiceDialog = page.getByRole("dialog", { name: "Importar factura de entrada" });
    await expect(invoiceDialog.getByRole("option").first()).toBeVisible();
    await invoiceDialog.getByRole("option").first().press("Enter");
    await expect(page.getByRole("status")).toContainText(/productos importados de la factura/i);

    await clearBulkRows(page);
    await chooseImportAction(page, "Importar albarán de entrada");
    const deliveryDialog = page.getByRole("dialog", { name: "Importar albarán de entrada" });
    await expect(deliveryDialog.getByRole("option").first()).toBeVisible();
    await deliveryDialog.getByRole("option").first().press("Enter");
    await expect(page.getByRole("status")).toContainText(/productos importados del albarán/i);

    await clearBulkRows(page);
    await chooseImportAction(page, "Importar familias de productos");
    const familyDialog = page.getByRole("dialog", { name: "Importar familias de productos" });
    await familyDialog.locator(".stock-bulk-family-node input[type='checkbox']").first().check();
    await familyDialog.getByRole("button", { name: "Añadir productos" }).click();
    await expect(page.getByRole("status")).toContainText(/productos nuevos anexados/i);
  } finally {
    await request.delete(
      `${apiUrl}/products/${encodeURIComponent(product.id)}/suppliers/${encodeURIComponent(supplier.id)}`,
      { headers: authorization(admin.accessToken) }
    ).catch(() => undefined);
    await request.delete(`${apiUrl}/suppliers/${encodeURIComponent(supplier.id)}`, {
      headers: authorization(admin.accessToken)
    }).catch(() => undefined);
  }
});
