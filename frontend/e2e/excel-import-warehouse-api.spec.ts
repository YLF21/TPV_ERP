import { expect, test } from "@playwright/test";
import { strToU8, zipSync } from "fflate";
import { loginApi, apiGet, apiPost, authorization, apiUrl, expectApiOk, uniqueMarker, createProductFixture, productById } from "./support/testApi";

function workbook(marker: string) {
  const esc = (v: string) => v.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  const rows = [["Código", "Nombre", "Precio de compra", "Descuento de compra", "Precio de venta"], [marker, `E2E ${marker}`, "7", "10", "10"]];
  const xml = rows.map((row, r) => `<row r="${r + 1}">${row.map((value, c) => `<c r="${String.fromCharCode(65 + c)}${r + 1}" t="inlineStr"><is><t>${esc(value)}</t></is></c>`).join("")}</row>`).join("");
  const files: Record<string, Uint8Array> = {
    "[Content_Types].xml": strToU8('<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>'),
    "_rels/.rels": strToU8('<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>'),
    "xl/workbook.xml": strToU8('<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Productos" sheetId="1" r:id="rId1"/></sheets></workbook>'),
    "xl/_rels/workbook.xml.rels": strToU8('<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>'),
    "xl/worksheets/sheet1.xml": strToU8(`<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${xml}</sheetData></worksheet>`)
  };
  return Buffer.from(zipSync(files));
}

function config(hash: string, marker: string) {
  return {
    mapping: { code: "A", name: "B", purchasePrice: "C", purchaseDiscountPercent: "D", salePrice: "E" }, edits: [],
    options: { globalValues: {}, valueSources: {}, showOnlyImported: false, context: "WAREHOUSE_INPUT", skipZeroPriceUpdate: false, requireQuantity: false, documentPriceSource: "purchasePrice" },
    expectedSha256: hash, startRow: 2, updateFields: { name: true, purchasePrice: true }, resolvedProducts: {}
  };
}

test.describe("product Excel warehouse API against isolated backend", () => {
  test("WAREHOUSE_INPUT preview is readonly and PREPARE_DESTINATION returns a proof response", async ({ request }) => {
    const session = await loginApi(request);
    const marker = uniqueMarker("E2E-WH");
    const product = await createProductFixture(request, session.accessToken, marker);
    const file = workbook(marker);
    // The server computes the hash; first read is intentionally the only read-side setup.
    const readResponse = await request.post(`${apiUrl}/product-excel-imports/read`, { headers: authorization(session.accessToken), multipart: { file: { name: "warehouse.xlsx", mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buffer: file } } });
    await expectApiOk(readResponse, "read warehouse workbook");
    const read = await readResponse.json() as { sha256: string };
    const previewConfig = config(read.sha256, marker);
    const previewResponse = await request.post(`${apiUrl}/product-excel-imports/preview`, { headers: authorization(session.accessToken), multipart: { file: { name: "warehouse.xlsx", mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buffer: file }, config: { name: "config", mimeType: "application/json", buffer: Buffer.from(JSON.stringify(previewConfig)) } } });
    await expectApiOk(previewResponse, "preview warehouse import");
    const preview = await previewResponse.json() as { previewFingerprint?: string; rows: unknown[] };
    expect(preview.rows.length).toBeGreaterThan(0);
    expect(preview.previewFingerprint).toMatch(/^[a-f0-9]{64}$/i);

    const [warehouses, suppliers] = await Promise.all([
      apiGet<Array<{ id: string }>>(request, session.accessToken, "/warehouses"),
      apiGet<Array<{ id: string }>>(request, session.accessToken, "/suppliers")
    ]);
    expect(warehouses.length).toBeGreaterThan(0);
    expect(suppliers.length).toBeGreaterThan(0);
    const operation = { operation: "PREPARE_DESTINATION", expectedPreviewFingerprint: preview.previewFingerprint, preview: previewConfig, expectedConcurrencyTokens: {}, autoAddMissing: false, confirmMasterChanges: false, updateSupplier: true, warehouseId: warehouses[0].id, supplierId: suppliers[0].id, documentDate: "2026-09-07" };
    const applyResponse = await request.post(`${apiUrl}/product-excel-imports/apply`, { headers: authorization(session.accessToken), multipart: { file: { name: "warehouse.xlsx", mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buffer: file }, config: { name: "config", mimeType: "application/json", buffer: Buffer.from(JSON.stringify(operation)) } } });
    await expectApiOk(applyResponse, "prepare warehouse destination");
    const applied = await applyResponse.json() as { warehouseMetadata?: { lines?: Array<{ productId: string; quantity?: number; purchasePrice?: number }> }; warehouseProvenanceToken?: string | null; appliedCount?: number };
    expect(applied.warehouseMetadata).toBeTruthy();
    expect(applied.warehouseProvenanceToken).toMatch(/^WXP1\.A\./);
    const line = applied.warehouseMetadata?.lines?.find((candidate) => candidate.productId === product.id) as ({ productId: string; quantity?: number; grossPurchasePrice?: number; supplierReference?: string } | undefined);
    expect(line).toBeTruthy();
    const draft = await apiPost<{ id: string; estado?: string }>(request, session.accessToken, "/warehouse-inputs", {
      warehouseId: warehouses[0].id, date: "2026-09-07", supplierId: suppliers[0].id, origin: "E2E", concept: "Excel API",
      lines: [{ productId: product.id, quantity: 1, unitPrice: line?.grossPurchasePrice ?? 7, discount: 10, priceOverridden: false, productName: product.name }],
      excelImport: applied.warehouseMetadata, excelImportProvenanceToken: applied.warehouseProvenanceToken
    });
    const before = await apiGet<{ items?: Array<{ id: string }> }>(request, session.accessToken, "/warehouse-inputs");
    expect(before.items?.some((item) => item.id === draft.id)).toBeTruthy();
    const suppliersBefore = await apiGet<unknown[]>(request, session.accessToken, `/products/${product.id}/suppliers`);
    expect(suppliersBefore).toHaveLength(0);
    const confirmed = await request.post(`${apiUrl}/warehouse-inputs/${draft.id}/confirm`, { headers: authorization(session.accessToken) });
    await expectApiOk(confirmed, "confirm warehouse input");
    const suppliersAfter = await apiGet<Array<{ supplierReference?: string; grossPurchasePrice?: number; purchaseDiscount?: number; netPurchasePrice?: number; lastEntryAt?: string }>>(request, session.accessToken, `/products/${product.id}/suppliers`);
    expect(suppliersAfter).toHaveLength(1);
    expect(suppliersAfter[0].supplierReference).toBe(marker);
    expect(suppliersAfter[0].grossPurchasePrice).toBe(7);
    expect(suppliersAfter[0].purchaseDiscount).toBe(10);
    expect(suppliersAfter[0].netPurchasePrice).toBe(6.3);
    expect(suppliersAfter[0].lastEntryAt).toBe("2026-09-06T23:00:00Z");
    const stock = await apiGet<Array<{ productId: string; warehouseId: string; quantity: number }>>(request, session.accessToken, `/stock?productId=${product.id}&warehouseId=${warehouses[0].id}`);
    const stockRows = stock.filter((item) => item.productId === product.id && item.warehouseId === warehouses[0].id);
    expect(stockRows).toHaveLength(1);
    expect(stockRows[0].quantity).toBe(1);
    const second = await request.post(`${apiUrl}/warehouse-inputs/${draft.id}/confirm`, { headers: authorization(session.accessToken) });
    expect([400, 409]).toContain(second.status());
    const stockAfterSecond = await apiGet<Array<{ productId: string; warehouseId: string; quantity: number }>>(request, session.accessToken, `/stock?productId=${product.id}&warehouseId=${warehouses[0].id}`);
    const afterRows = stockAfterSecond.filter((item) => item.productId === product.id && item.warehouseId === warehouses[0].id);
    expect(afterRows).toHaveLength(1);
    expect(afterRows[0].quantity).toBe(1);
  });

  test("warehouse input permission endpoint is reachable only with the authenticated session", async ({ request }) => {
    const response = await request.get(`${apiUrl}/warehouse-inputs`);
    expect([401, 403]).toContain(response.status());
  });

  test("UPDATE_PURCHASE_PRICE from warehouse updates only purchase price and rejects replay", async ({ request }) => {
    const session = await loginApi(request);
    const marker = uniqueMarker("E2E-WH-PRICE");
    const product = await createProductFixture(request, session.accessToken, marker);
    const file = workbook(marker);
    const readResponse = await request.post(`${apiUrl}/product-excel-imports/read`, { headers: authorization(session.accessToken), multipart: { file: { name: "warehouse-price.xlsx", mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buffer: file } } });
    await expectApiOk(readResponse, "read price workbook");
    const read = await readResponse.json() as { sha256: string };
    const previewConfig = config(read.sha256, marker);
    const previewResponse = await request.post(`${apiUrl}/product-excel-imports/preview`, { headers: authorization(session.accessToken), multipart: { file: { name: "warehouse-price.xlsx", mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buffer: file }, config: { name: "config", mimeType: "application/json", buffer: Buffer.from(JSON.stringify(previewConfig)) } } });
    await expectApiOk(previewResponse, "preview price workbook");
    const preview = await previewResponse.json() as { previewFingerprint: string; rows: Array<{ rowNumber: number; concurrencyToken?: string }> };
    const token = preview.rows.find((row) => row.rowNumber === 2)?.concurrencyToken;
    expect(token).toBeTruthy();
    const operation = { operation: "UPDATE_PURCHASE_PRICE", expectedPreviewFingerprint: preview.previewFingerprint, preview: previewConfig, expectedConcurrencyTokens: { "2": token }, autoAddMissing: false, confirmMasterChanges: true, updateSupplier: false };
    const first = await request.post(`${apiUrl}/product-excel-imports/apply`, { headers: authorization(session.accessToken), multipart: { file: { name: "warehouse-price.xlsx", mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buffer: file }, config: { name: "config", mimeType: "application/json", buffer: Buffer.from(JSON.stringify(operation)) } } });
    await expectApiOk(first, "update purchase price");
    const after = await apiGet<any>(request, session.accessToken, `/products/management/${product.id}`);
    expect(after.purchasePrice).toBe(7);
    expect(after.name).toBe(product.name);
    expect(after.purchaseDiscountPercent).toBe(product.purchaseDiscountPercent);
    const replay = await request.post(`${apiUrl}/product-excel-imports/apply`, { headers: authorization(session.accessToken), multipart: { file: { name: "warehouse-price.xlsx", mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", buffer: file }, config: { name: "config", mimeType: "application/json", buffer: Buffer.from(JSON.stringify(operation)) } } });
    expect(replay.status()).toBe(409);
    const replayBody = await replay.json() as { errors?: Array<{ code?: string }> };
    expect(replayBody.errors?.some((error) => error.code === "VERSION_STALE")).toBeTruthy();
    const afterReplay = await apiGet<any>(request, session.accessToken, `/products/management/${product.id}`);
    expect(afterReplay.purchasePrice).toBe(7);
    expect(afterReplay.name).toBe(product.name);
    expect(afterReplay.purchaseDiscountPercent).toBe(product.purchaseDiscountPercent);
  });
});
