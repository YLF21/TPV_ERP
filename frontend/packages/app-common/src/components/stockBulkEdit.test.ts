import { describe, expect, it, vi } from "vitest";
import { readSheet } from "read-excel-file/browser";
import {
  buildStockBulkSupplierAssignments,
  buildStockBulkUpdates,
  finalizeStockBulkSupplierAssignments,
  hydrateStockBulkSupplierData,
  importStockBulkFile,
  mergeStockBulkPurchaseDocumentProducts,
  mergeStockBulkSupplierProducts,
  requestStockBulkXlsx,
  stockBulkEffectiveProduct,
  stockOfferPriceFromDiscount,
  stockBulkExportFileName,
  stockBulkRowsChanged,
  stockBulkVersionedDeletePath,
  validateStockBulkRows
} from "./stockBulkEdit";
import type { StockInventoryRow } from "./StockScreen";

vi.mock("read-excel-file/browser", () => ({
  readSheet: vi.fn()
}));

const readSheetMock = vi.mocked(readSheet);

const product: StockInventoryRow = {
  productId: "product-1",
  version: 4,
  warehouseId: "warehouse-1",
  code: "A001",
  barcode: "8430000000011",
  barcode2: "",
  name: "Agua",
  description: "Botella",
  comments: "",
  purchasePrice: "0.50",
  purchaseDiscountPercent: "5",
  salePrice: "1.00",
  memberPrice: "0.90",
  wholesalePrice: "0.80",
  offerPrice: "0.75",
  offerDiscountPercent: "25",
  productType: "UNIT",
  discountType: "NORMAL",
  backendDiscountType: "NONE",
  familyId: "family-1",
  familyName: "Bebidas",
  subfamilyId: "subfamily-1",
  subfamilyName: "Agua",
  taxId: "tax-1",
  taxName: "7%",
  taxesIncluded: "common.yes",
  offerActive: "common.no",
  offerFrom: "-",
  offerUntil: "-",
  warehouseName: "GENERAL",
  quantity: 2,
  totalQuantity: 2
};

describe("stock bulk edit", () => {
  it("hydrates every product supplier and prioritizes the last and principal links", () => {
    const rows = [{
      id: "row-1",
      selected: false,
      query: "A001",
      product,
      draft: { ...product },
      suppliers: [{
        id: "stale",
        supplierCode: "OLD",
        legalName: "Proveedor antiguo",
        documentNumber: "OLD",
        active: true
      }],
      pendingSupplier: {
        id: "pending",
        supplierCode: "PENDING",
        legalName: "Proveedor pendiente",
        documentNumber: "PENDING",
        active: true
      }
    }];

    const hydrated = hydrateStockBulkSupplierData(rows, [
      {
        productId: product.productId,
        supplierId: "supplier-principal",
        supplierCode: "P1",
        legalName: "Proveedor principal",
        documentNumber: "B1",
        active: true,
        principal: true,
        lastSupplier: false
      },
      {
        productId: product.productId,
        supplierId: "supplier-last",
        supplierCode: "P2",
        legalName: "Ultimo proveedor",
        documentNumber: "B2",
        active: true,
        principal: false,
        lastSupplier: true,
        grossPurchasePrice: "0.50",
        purchaseDiscount: "5",
        netPurchasePrice: "0.48",
        lastEntryAt: "2026-07-10T10:30:00Z"
      }
    ]);

    expect(hydrated[0].suppliers?.map((supplier) => supplier.id)).toEqual([
      "supplier-last",
      "supplier-principal"
    ]);
    expect(hydrated[0].suppliers?.[0]).toEqual(expect.objectContaining({
      lastSupplier: true,
      netPurchasePrice: "0.48"
    }));
    expect(hydrated[0].pendingSupplier?.id).toBe("pending");
  });

  it("builds a complete backend update and preserves NONE", () => {
    const rows = [{
      id: "row-1",
      selected: false,
      query: "A001",
      product,
      draft: { ...product, salePrice: "1.20" }
    }];

    expect(validateStockBulkRows(rows)).toEqual([]);
    expect(buildStockBulkUpdates(rows)).toEqual([expect.objectContaining({
      productId: "product-1",
      expectedVersion: 4,
      product: expect.objectContaining({
        code: "A001",
        familyId: "family-1",
        taxId: "tax-1",
        discountType: "NONE",
        priceUseMode: "NORMAL",
        salePrice: 1.2,
        purchaseDiscountPercent: 5,
        taxesIncluded: true
      })
    })]);
  });

  it("preserves product concurrency data when a persisted draft contains nulls", () => {
    const effective = stockBulkEffectiveProduct({
      id: "row-1",
      selected: false,
      query: "A001",
      product: { ...product, imageId: "image-1" },
      draft: { version: null as unknown as number, imageId: null, salePrice: "1.20" }
    });

    expect(effective).toEqual(expect.objectContaining({
      version: 4,
      imageId: "image-1",
      salePrice: "1.20"
    }));
  });

  it("detects edited rows", () => {
    const rows = [{
      id: "row-1",
      selected: false,
      query: "A001",
      product,
      draft: { ...product, name: "Agua mineral" }
    }];

    expect(stockBulkRowsChanged(rows)).toBe(true);
  });

  it("builds versioned delete paths and reads XLSX attachment names", () => {
    expect(stockBulkVersionedDeletePath("draft/1", 7))
      .toBe("/product-bulk-edits/draft%2F1?version=7");
    expect(stockBulkExportFileName(
      "attachment; filename=\"productos.xlsx\"",
      "fallback.xlsx"
    )).toBe("productos.xlsx");
    expect(stockBulkExportFileName(
      "attachment; filename*=UTF-8''edicion%20masiva.xlsx",
      "fallback.xlsx"
    )).toBe("edicion masiva.xlsx");
  });

  it("posts typed content and receives the XLSX blob", async () => {
    const request = vi.fn(async () => new Response(new Blob(["xlsx"]), {
      headers: {
        "Content-Type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "Content-Disposition": "attachment; filename=\"productos.xlsx\""
      }
    }));
    const rows = [{
      id: "row-1",
      selected: false,
      query: "A001",
      product,
      draft: { ...product }
    }];

    const result = await requestStockBulkXlsx("/api/v1", "token", rows, request as typeof fetch);

    expect(result.fileName).toBe("productos.xlsx");
    expect(result.blob.type).toBe("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    expect(request).toHaveBeenCalledWith("/api/v1/product-bulk-edits/export", expect.objectContaining({
      method: "POST",
      body: JSON.stringify({ content: rows })
    }));
  });

  it("imports every editable Spanish XLSX column including references and typed values", async () => {
    const targetReferences = {
      ...product,
      productId: "product-2",
      code: "A002",
      barcode: "8430000000028",
      familyId: "family-2",
      familyName: "Alimentacion",
      subfamilyId: "subfamily-2",
      subfamilyName: "Conservas",
      taxId: "tax-2",
      taxName: "3%"
    };
    readSheetMock.mockResolvedValueOnce([
      [
        "Codigo", "Nombre", "Descripcion", "Precio compra", "Descuento compra",
        "Precio venta", "Precio socio", "Precio mayor", "Precio oferta", "Usar precio",
        "Descuento oferta", "Oferta desde", "Oferta hasta", "Comentarios", "Familia ID",
        "Familia", "Subfamilia ID", "Subfamilia", "Impuesto ID", "Impuesto",
        "Impuestos incluidos", "Oferta activa"
      ],
      [
        "A001", "Agua con gas", "Botella retornable", 0.65, 7.5,
        1.35, 1.15, 0.95, 0.85, "OFFER_PRICE",
        10, new Date(2026, 6, 1), new Date(2026, 6, 31), "Campana verano",
        "family-2", "Alimentacion", "subfamily-2", "Conservas", "tax-2", "3%",
        false, true
      ]
    ] as never);

    const rows = await importStockBulkFile(
      new File(["xlsx"], "productos.xlsx"),
      [product, targetReferences]
    );

    expect(rows).toHaveLength(1);
    expect(rows[0].product).toBe(product);
    expect(rows[0].draft).toEqual(expect.objectContaining({
      name: "Agua con gas",
      description: "Botella retornable",
      purchasePrice: "0.65",
      purchaseDiscountPercent: "7.5",
      salePrice: "1.35",
      memberPrice: "1.15",
      wholesalePrice: "0.95",
      offerPrice: "0.85",
      discountType: "OFFER_PRICE",
      offerDiscountPercent: "10",
      offerFrom: "2026-07-01",
      offerUntil: "2026-07-31",
      comments: "Campana verano",
      familyId: "family-2",
      familyName: "Alimentacion",
      subfamilyId: "subfamily-2",
      subfamilyName: "Conservas",
      taxId: "tax-2",
      taxName: "3%",
      taxesIncluded: "common.no",
      offerActive: "common.yes"
    }));
  });

  it("imports English reference names and preserves fields absent from a partial sheet", async () => {
    readSheetMock.mockResolvedValueOnce([
      ["Product ID", "Family", "Subfamily", "Tax", "Taxes included", "Offer active"],
      ["product-1", "Bebidas", "Agua", "7%", "yes", "no"]
    ] as never);

    const rows = await importStockBulkFile(new File(["xlsx"], "products.xlsx"), [product]);

    expect(rows[0].draft).toEqual(expect.objectContaining({
      description: "Botella",
      comments: "",
      purchasePrice: "0.50",
      familyId: "family-1",
      familyName: "Bebidas",
      subfamilyId: "subfamily-1",
      subfamilyName: "Agua",
      taxId: "tax-1",
      taxName: "7%",
      taxesIncluded: "common.yes",
      offerActive: "common.no"
    }));
  });

  it("rejects ambiguous family names unless the XLSX supplies the ID", async () => {
    const duplicateFamilyName = {
      ...product,
      productId: "product-2",
      code: "A002",
      barcode: "8430000000028",
      familyId: "family-2",
      familyName: "Bebidas",
      subfamilyId: "subfamily-2",
      subfamilyName: "Refrescos"
    };
    readSheetMock.mockResolvedValueOnce([
      ["Codigo", "Familia"],
      ["A001", "Bebidas"]
    ] as never);

    await expect(importStockBulkFile(
      new File(["xlsx"], "productos.xlsx"),
      [product, duplicateFamilyName]
    )).rejects.toThrow(/Fila 2: la referencia familia "Bebidas" es ambigua; indica su ID/);
  });

  it("accepts valid catalog references that no existing product uses yet", async () => {
    readSheetMock.mockResolvedValueOnce([
      ["Codigo", "Familia ID", "Familia", "Subfamilia ID", "Subfamilia", "Impuesto ID", "Impuesto"],
      ["A001", "family-new", "Novedades", "subfamily-new", "Temporada", "tax-new", "5%"]
    ] as never);

    const rows = await importStockBulkFile(
      new File(["xlsx"], "productos.xlsx"),
      [product],
      {
        families: [{ id: "family-new", name: "Novedades" }],
        subfamilies: [{ id: "subfamily-new", familyId: "family-new", name: "Temporada" }],
        taxes: [{ id: "tax-new", name: "5%" }]
      }
    );

    expect(rows[0].draft).toEqual(expect.objectContaining({
      familyId: "family-new",
      subfamilyId: "subfamily-new",
      taxId: "tax-new"
    }));
  });

  it("returns localized import errors", async () => {
    readSheetMock.mockResolvedValueOnce([
      ["Code"],
      ["MISSING"]
    ] as never);

    await expect(importStockBulkFile(
      new File(["xlsx"], "products.xlsx"),
      [product],
      { locale: "en" }
    )).rejects.toThrow(/Row 2: product "MISSING" does not exist/);
  });

  it("rejects nonexistent or inconsistent family, subfamily and tax references", async () => {
    readSheetMock.mockResolvedValueOnce([
      ["Codigo", "Familia ID", "Familia", "Subfamilia ID", "Impuesto ID"],
      ["A001", "family-1", "Otra familia", "subfamily-missing", "tax-missing"]
    ] as never);

    await expect(importStockBulkFile(
      new File(["xlsx"], "productos.xlsx"),
      [product]
    )).rejects.toThrow(
      /el ID y el nombre de familia no corresponden[\s\S]*subfamilia "subfamily-missing" no existe[\s\S]*impuesto "tax-missing" no existe/
    );
  });

  it("does not silently discard XLSX rows whose product does not exist", async () => {
    readSheetMock.mockResolvedValueOnce([
      ["Codigo", "Nombre"],
      ["NO-EXISTE", "Producto externo"]
    ] as never);

    await expect(importStockBulkFile(
      new File(["xlsx"], "productos.xlsx"),
      [product]
    )).rejects.toThrow(/Fila 2: el producto "NO-EXISTE" no existe/);
  });

  it("validates required product fields, percentages, dates and duplicate rows", () => {
    const invalidProduct = {
      ...product,
      familyId: "-",
      salePrice: "texto",
      discountType: "OFFER_DISCOUNT",
      offerDiscountPercent: "120",
      offerFrom: "2026-02-30",
      offerUntil: "2026-02-01"
    };
    const rows = ["row-1", "row-2"].map((id) => ({
      id,
      selected: false,
      query: "A001",
      product: invalidProduct,
      draft: { ...invalidProduct }
    }));

    expect(validateStockBulkRows(rows)).toEqual(expect.arrayContaining([
      expect.objectContaining({ rowId: "row-1", field: "productId", code: "duplicate" }),
      expect.objectContaining({ rowId: "row-2", field: "productId", code: "duplicate" }),
      expect.objectContaining({ rowId: "row-1", field: "familyId", code: "required" }),
      expect.objectContaining({ rowId: "row-1", field: "salePrice", code: "invalidNumber" }),
      expect.objectContaining({ rowId: "row-1", field: "offerDiscountPercent", code: "invalidPercentage" }),
      expect.objectContaining({ rowId: "row-1", field: "offerFrom", code: "invalidDate" }),
      expect.objectContaining({ rowId: "row-1", field: "offerUntil", code: "invalidDate" })
    ]));
  });

  it("calculates offer price from sale price and offer discount", () => {
    expect(stockOfferPriceFromDiscount("10,00", "15")).toBe("8.50");
    expect(stockOfferPriceFromDiscount("10.00", "101")).toBeNull();

    const rows = [{
      id: "row-1",
      selected: false,
      query: "A001",
      product,
      draft: {
        ...product,
        discountType: "OFFER_DISCOUNT",
        salePrice: "20.00",
        offerPrice: "1.00",
        offerDiscountPercent: "25",
        offerFrom: "2026-07-01"
      }
    }];

    expect(buildStockBulkUpdates(rows)[0].product).toEqual(expect.objectContaining({
      offerPrice: 15,
      offerDiscountPercent: 25,
      offerActive: true
    }));
  });

  it("groups pending supplier assignments and finalizes them after apply", () => {
    const supplier = {
      id: "supplier-1",
      supplierCode: "P-001",
      legalName: "Proveedor Uno",
      documentNumber: "B00000001",
      active: true
    };
    const rows = [{
      id: "row-1",
      selected: true,
      query: "A001",
      product,
      draft: { ...product },
      pendingSupplier: supplier
    }];

    expect(stockBulkRowsChanged(rows)).toBe(true);
    expect(buildStockBulkSupplierAssignments(rows)).toEqual([{
      supplierId: "supplier-1",
      productIds: ["product-1"]
    }]);
    const finalized = finalizeStockBulkSupplierAssignments(rows);
    expect(finalized).toEqual([expect.objectContaining({ suppliers: [supplier] })]);
    expect(finalized[0]).not.toHaveProperty("pendingSupplier");
  });

  it("imports all products linked to a supplier without duplicating existing rows", () => {
    const secondProduct = { ...product, productId: "product-2", code: "A002", name: "Cafe" };
    const supplier = {
      id: "supplier-1",
      supplierCode: "P-001",
      legalName: "Proveedor Uno",
      documentNumber: "B00000001",
      active: true
    };
    const rows = [{
      id: "row-1",
      selected: false,
      query: "A001",
      product,
      draft: { ...product }
    }];

    const merged = mergeStockBulkSupplierProducts(rows, [product, secondProduct], supplier, [
      { productId: "product-1", lastSupplier: false, grossPurchasePrice: "0.50" },
      { productId: "product-2", lastSupplier: true, grossPurchasePrice: "0.60" }
    ]);

    expect(merged.filter((row) => row.product)).toHaveLength(2);
    expect(merged.filter((row) => row.product).map((row) => row.product?.productId))
      .toEqual(["product-1", "product-2"]);
    expect(merged[0].suppliers?.[0]).toEqual(expect.objectContaining({
      id: "supplier-1",
      lastSupplier: false
    }));
  });

  it("imports purchase document prices and keeps the last repeated product line", () => {
    const secondProduct = { ...product, productId: "product-2", code: "A002", name: "Cafe" };
    const rows = [{
      id: "row-1",
      selected: false,
      query: "A001",
      product,
      draft: { ...product, name: "Agua editada" }
    }];

    const merged = mergeStockBulkPurchaseDocumentProducts(rows, [product, secondProduct], [
      { productId: "product-1", grossPurchasePrice: "0.60", purchaseDiscount: "5.00" },
      { productId: "product-2", grossPurchasePrice: "1.20", purchaseDiscount: "0.00" },
      { productId: "product-1", grossPurchasePrice: "0.75", purchaseDiscount: "10.00" }
    ]);

    expect(merged.filter((row) => row.product)).toHaveLength(2);
    expect(merged[0].draft).toEqual(expect.objectContaining({
      name: "Agua editada",
      purchasePrice: "0.75",
      purchaseDiscountPercent: "10.00"
    }));
    expect(merged[1].draft).toEqual(expect.objectContaining({
      purchasePrice: "1.20",
      purchaseDiscountPercent: "0.00"
    }));
  });
});
