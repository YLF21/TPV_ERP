import { expect, test } from "@playwright/test";
import { fileURLToPath } from "node:url";
import { apiGet, apiPost, apiPut, apiUrl, authorization, cleanupBulkDrafts, createProductFixture,
  deleteProductFixture, expectApiOk, loginApi, productById, uniqueMarker } from "./support/testApi";
import { loginUi, openStock, openBulkEdit } from "./support/ui";

function persistedRow(product: Record<string, any>) {
  const snapshot: Record<string, unknown> = { productId: product.id, version: product.version };
  for (const field of ["code", "barcode", "barcode2", "name", "description", "comments", "familyId", "subfamilyId", "taxId",
    "productType", "purchasePrice", "purchaseDiscountPercent", "salePrice", "memberPrice", "wholesalePrice", "offerPrice",
    "offerDiscountPercent", "taxesIncluded", "offerActive", "offerFrom", "offerUntil", "stockMin", "stockMax", "packageQuantity", "active"]) {
    snapshot[field] = product[field] == null ? "" : String(product[field]);
  }
  snapshot.discountType = product.priceUseMode;
  snapshot.backendDiscountType = product.discountType;
  return { id: `row-${product.id}`, selected: false, query: product.code, product: snapshot, draft: {}, suppliers: [] };
}

for (const app of ["venta", "gestion"] as const) {
  test(`${app}: guarda, reabre, aplica y vuelve a editar una lista con borrador parcial`, async ({ page, request }) => {
    const session = await loginApi(request);
    const marker = uniqueMarker("BULK-REGRESSION");
    const product = await createProductFixture(request, session.accessToken, marker);
    const listName = `REGRESION ${app} ${marker}`;
    const pageErrors: string[] = [];
    page.on("pageerror", error => pageErrors.push(error.message));
    try {
      await apiPost(request, session.accessToken, "/product-bulk-edits", { name: listName, content: [persistedRow(product)] });
      await page.setViewportSize({ width: 1920, height: 1080 });
      await loginUi(page, app);
      await openStock(page, app);
      await openBulkEdit(page);
      await page.getByRole("row").filter({ hasText: listName }).dblclick();
      const price = page.getByRole("textbox", { name: "Precio venta", exact: true }).first();
      await expect(price).toHaveValue("10");
      await price.fill("12.75");
      await page.getByRole("button", { name: "Guardar", exact: true }).click();
      await expect(page.locator(".bulk-edit-status")).toContainText("Lista guardada");
      await page.getByRole("button", { name: "Volver a listas", exact: true }).click();
      await page.getByRole("row").filter({ hasText: listName }).dblclick();
      await expect(price).toHaveValue("12.75");
      for (const expected of [12.75, 13.5]) {
        if (expected === 13.5) await price.fill("13.50");
        await page.getByRole("button", { name: "Aplicar cambios", exact: true }).click();
        const appliedResponse = page.waitForResponse(response =>
          response.request().method() === "POST" && /\/product-bulk-edits\/[^/]+\/apply$/.test(response.url()));
        await page.getByRole("dialog", { name: "Aplicar cambios" }).getByRole("button", { name: "Aplicar cambios", exact: true }).click();
        expect((await appliedResponse).ok()).toBeTruthy();
        await expect(page.locator(".bulk-edit-status")).toContainText("Cambios aplicados");
        await expect.poll(async () => (await productById(request, session.accessToken, product.id)).salePrice).toBe(expected);
      }
      expect(pageErrors).toEqual([]);
    } finally {
      await cleanupBulkDrafts(request, session.accessToken, listName);
      await deleteProductFixture(request, session.accessToken, product.id);
    }
  });
}

test("importar y cambiar un precio conserva producto inactivo y control de números de serie", async ({ page, request }) => {
  const session = await loginApi(request);
  const marker = uniqueMarker("BULK-EXCEL-FLAGS");
  const product = await createProductFixture(request, session.accessToken, marker);
  const listName = `REGRESION FLAGS ${marker}`;
  try {
    const configured = await apiPut<Record<string, any>>(request, session.accessToken, `/products/${product.id}`, {
      ...product, active: false, requiresSerialNumber: true
    });
    const row = persistedRow(configured);
    const exportResponse = await request.post(`${apiUrl}/product-bulk-edits/export`, {
      headers: authorization(session.accessToken), data: { content: [row] }
    });
    await expectApiOk(exportResponse, "Exportar producto para la regresión Excel");
    const file = { name: "regression.xlsx", mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buffer: await exportResponse.body() };
    const readResponse = await request.post(`${apiUrl}/product-excel-imports/read`, { headers: authorization(session.accessToken), multipart: { file } });
    await expectApiOk(readResponse, "Leer Excel");
    const read = await readResponse.json();
    const config = { mapping: { code: "A" }, edits: [], startRow: 2, expectedSha256: read.sha256,
      options: { globalValues: {}, valueSources: {}, context: "STOCK", skipZeroPriceUpdate: false }, updateFields: {}, resolvedProducts: {} };
    const previewResponse = await request.post(`${apiUrl}/product-excel-imports/preview`, {
      headers: authorization(session.accessToken), multipart: { file, config: { name: "config", mimeType: "application/json", buffer: Buffer.from(JSON.stringify(config)) } }
    });
    await expectApiOk(previewResponse, "Previsualizar Excel");
    const preview = await previewResponse.json();
    const databaseData = preview.rows.find((candidate: any) => candidate.databaseData?.id === product.id)?.databaseData;
    expect(databaseData).toBeTruthy();
    expect(databaseData).toMatchObject({ active: "0", requiresSerialNumber: "1" });
    await page.goto("/");
    const prepared = await page.evaluate(async ({ data, screenPath, bulkPath }) => {
      const { stockBulkProductFromPreviewDatabaseData, normalizeStockBulkContent } = await import(screenPath);
      const { buildStockBulkUpdates, stockBulkEffectiveProduct } = await import(bulkPath);
      const product = stockBulkProductFromPreviewDatabaseData(data);
      const rows = [{ id: "imported-row", selected: false, query: product.code, product, draft: { salePrice: "12.75" }, suppliers: [] }];
      return { content: normalizeStockBulkContent(rows), updates: buildStockBulkUpdates(rows),
        applied: normalizeStockBulkContent(rows).map((row: any) => ({ ...row, product: stockBulkEffectiveProduct(row), draft: stockBulkEffectiveProduct(row) })) };
    }, {
      data: databaseData,
      screenPath: `/@fs/${fileURLToPath(new URL("../packages/app-common/src/components/StockScreen.tsx", import.meta.url)).replace(/\\/g, "/")}`,
      bulkPath: `/@fs/${fileURLToPath(new URL("../packages/app-common/src/components/stockBulkEdit.ts", import.meta.url)).replace(/\\/g, "/")}`
    });
    const draft = await apiPost<{ id: string; version: number }>(request, session.accessToken, "/product-bulk-edits", { name: listName, content: prepared.content });
    await apiPost(request, session.accessToken, `/product-bulk-edits/${draft.id}/apply`, {
      version: draft.version, content: prepared.applied, updates: prepared.updates, creates: [], supplierAssignments: [], supplierPrincipalAssignments: []
    });
    const updated = await apiGet<Record<string, any>>(request, session.accessToken, `/products/management/${product.id}`);
    expect(updated).toMatchObject({ active: false, requiresSerialNumber: true, taxesIncluded: true, salePrice: 12.75 });
  } finally {
    await cleanupBulkDrafts(request, session.accessToken, listName);
    await deleteProductFixture(request, session.accessToken, product.id);
  }
});

test("una lista antigua rechaza sobrescribir el precio aplicado desde otra lista", async ({ request }) => {
  const session = await loginApi(request);
  const marker = uniqueMarker("BULK-CONFLICT");
  const product = await createProductFixture(request, session.accessToken, marker);
  const listName = `REGRESION CONFLICTO ${marker}`;
  const row = persistedRow(product);
  const content = (price: string) => [{ ...row, draft: { salePrice: price } }];
  try {
    const first = await apiPost<{ id: string; version: number }>(request, session.accessToken,
      "/product-bulk-edits", { name: `${listName} A`, content: content("12") });
    const stale = await apiPost<{ id: string; version: number }>(request, session.accessToken,
      "/product-bulk-edits", { name: `${listName} B`, content: content("14") });
    const apply = (version: number, price: string) => ({
      version, content: content(price),
      updates: [{ productId: product.id, expectedVersion: product.version, product: { ...product, salePrice: price } }],
      creates: [], supplierAssignments: [], supplierPrincipalAssignments: []
    });
    await apiPost(request, session.accessToken, `/product-bulk-edits/${first.id}/apply`, apply(first.version, "12"));
    const conflict = await request.post(`${apiUrl}/product-bulk-edits/${stale.id}/apply`, {
      headers: authorization(session.accessToken), data: apply(stale.version, "14")
    });
    expect(conflict.status(), await conflict.text()).toBe(409);
    expect(await conflict.json()).toMatchObject({ code: "BULK_EDIT_PRODUCT_VERSION_CONFLICT" });
    expect((await productById(request, session.accessToken, product.id)).salePrice).toBe(12);
    expect(await apiGet(request, session.accessToken, `/product-bulk-edits/${stale.id}`)).toMatchObject({ status: "PENDING" });
  } finally {
    await cleanupBulkDrafts(request, session.accessToken, listName);
    await deleteProductFixture(request, session.accessToken, product.id);
  }
});
