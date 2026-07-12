import { expect, test } from "@playwright/test";
import {
  apiGet,
  apiPost,
  apiUrl,
  authorization,
  createProductFixture,
  deleteProductFixture,
  loginApi,
  uniqueMarker
} from "./support/testApi";
import { executeE2eSql, sqlUuid } from "./support/database";
import {
  chooseFileAction,
  chooseImportAction,
  clearBulkRows,
  loginUi,
  openBulkEdit,
  openStock
} from "./support/ui";

test("Archivo ejecuta todas las importaciones reales y la exportación Excel", async ({ page, request }) => {
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
  const documentLine = {
    productoId: product.id,
    cantidad: 1,
    codigo: marker,
    nombre: product.name,
    tarifa: null,
    precioUnitario: 5,
    descuento: 0,
    impuestosIncluidos: true,
    regimenImpuesto: "IVA",
    porcentajeImpuesto: 21,
    lineType: "PRODUCT",
    promotionId: null,
    promotionVersionId: null,
    promotionalCouponId: null
  };
  const invoice = await apiPost<{ id: string }>(request, admin.accessToken, "/invoices", {
    almacenId: warehouse.id,
    tipo: "FACTURA_COMPRA",
    fecha: "2026-07-12",
    clienteId: null,
    proveedorId: supplier.id,
    numeroExterno: `FC-${marker}`,
    descuentoGlobal: 0,
    directo: false,
    lineas: [documentLine]
  });
  const deliveryNote = await apiPost<{ id: string }>(request, admin.accessToken, "/delivery-notes", {
    almacenId: warehouse.id,
    tipo: "ALBARAN_COMPRA",
    fecha: "2026-07-12",
    clienteId: null,
    proveedorId: supplier.id,
    numeroExterno: `AC-${marker}`,
    descuentoGlobal: 0,
    directo: false,
    lineas: [documentLine]
  });

  try {
    const [suppliers, invoices, deliveryNotes] = await Promise.all([
      apiGet<unknown[]>(request, admin.accessToken, "/product-bulk-edits/suppliers"),
      apiGet<unknown[]>(request, admin.accessToken, "/product-bulk-edits/purchase-invoices"),
      apiGet<unknown[]>(request, admin.accessToken, "/product-bulk-edits/purchase-delivery-notes")
    ]);
    expect(suppliers.length, "La prueba necesita un proveedor").toBeGreaterThan(0);
    expect(invoices.length, "La prueba necesita una factura de compra").toBeGreaterThan(0);
    expect(deliveryNotes.length, "La prueba necesita un albarán de compra").toBeGreaterThan(0);

    await loginUi(page, "venta");
    await openStock(page, "venta");
    await openBulkEdit(page);
    await page.getByRole("button", { name: "Nuevo", exact: true }).click();

    const codeInput = page.getByPlaceholder("Código o código de barras");
    await codeInput.fill(marker);
    await codeInput.press("Enter");
    await expect(page.locator(".bulk-edit-row").filter({ hasText: marker })).toBeVisible();

    const downloadPromise = page.waitForEvent("download");
    await chooseFileAction(page, "Exportar Excel");
    const download = await downloadPromise;
    const exportedFile = await download.path();
    expect(exportedFile).not.toBeNull();

    await clearBulkRows(page);
    const chooserPromise = page.waitForEvent("filechooser");
    await chooseImportAction(page, "Importar Excel");
    await (await chooserPromise).setFiles(exportedFile!);
    await expect(page.locator(".bulk-edit-row").filter({ hasText: marker })).toBeVisible();

    await clearBulkRows(page);
    await chooseImportAction(page, "Importar proveedor");
    const supplierDialog = page.getByRole("dialog", { name: "Importar proveedor" });
    await expect(supplierDialog.getByRole("option").first()).toBeVisible();
    await supplierDialog.getByRole("option").first().press("Enter");
    await expect(page.getByRole("status")).toContainText(/productos de/i);

    await clearBulkRows(page);
    await chooseImportAction(page, "Importar factura de compra");
    const invoiceDialog = page.getByRole("dialog", { name: "Importar factura de compra" });
    await expect(invoiceDialog.getByRole("option").first()).toBeVisible();
    await invoiceDialog.getByRole("option").first().press("Enter");
    await expect(page.getByRole("status")).toContainText(/productos importados de la factura/i);

    await clearBulkRows(page);
    await chooseImportAction(page, "Importar albarán de compra");
    const deliveryDialog = page.getByRole("dialog", { name: "Importar albarán de compra" });
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
    executeE2eSql(`delete from documento where id in (${sqlUuid(invoice.id)}, ${sqlUuid(deliveryNote.id)});`);
    await request.delete(
      `${apiUrl}/products/${encodeURIComponent(product.id)}/suppliers/${encodeURIComponent(supplier.id)}`,
      { headers: authorization(admin.accessToken) }
    ).catch(() => undefined);
    await request.delete(`${apiUrl}/suppliers/${encodeURIComponent(supplier.id)}`, {
      headers: authorization(admin.accessToken)
    }).catch(() => undefined);
    await deleteProductFixture(request, admin.accessToken, product.id).catch(() => undefined);
  }
});
