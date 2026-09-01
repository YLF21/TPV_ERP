import { describe, expect, it, vi } from "vitest";
import { readSheet } from "read-excel-file/browser";
import {
  buildStockBulkSupplierPrincipalAssignments,
  buildStockBulkSupplierAssignments,
  buildStockBulkUpdates,
  finalizeStockBulkSupplierAssignments,
  hydrateStockBulkSupplierData,
  importStockBulkFile,
  mergeStockBulkPurchaseDocumentProducts,
  mergeStockBulkSupplierProducts,
  requestStockBulkXlsx,
  resolveStockBulkImportedClassification,
  shouldClearStockBulkImportedSubfamily,
  stageStockBulkPrincipalSupplier,
  stockBulkClassificationCodesForRows,
  stockBulkDisplayedSupplier,
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
  active: "common.yes",
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
        taxesIncluded: true,
        active: true
      })
    })]);
  });

  it("persists product activation changes", () => {
    const rows = [{
      id: "row-1",
      selected: true,
      query: "A001",
      product,
      draft: { ...product, active: "common.no" }
    }];

    expect(buildStockBulkUpdates(rows)[0].product.active).toBe(false);
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

  it("posts readable classification codes beside the legacy UUID references", async () => {
    const request = vi.fn(async () => new Response(new Blob(["xlsx"]), {
      headers: { "Content-Type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" }
    }));
    const rows = [{
      id: "row-1",
      selected: false,
      query: "A001",
      product,
      draft: { ...product }
    }];

    await requestStockBulkXlsx("/api/v1", "token", rows, {
      familyCodes: { "family-1": "010" },
      subfamilyCodes: { "subfamily-1": "010002" }
    }, request as typeof fetch);

    expect(request).toHaveBeenCalledWith("/api/v1/product-bulk-edits/export", expect.objectContaining({
      body: JSON.stringify({
        content: rows,
        familyCodes: { "family-1": "010" },
        subfamilyCodes: { "subfamily-1": "010002" }
      })
    }));
  });

  it("exports classification codes only for UUIDs referenced by the selected rows", () => {
    const unrelatedSubfamilies = Array.from({ length: 5_001 }, (_, index) => ({
      id: `unused-subfamily-${index}`,
      familyId: "unused-family",
      subfamilySuffix: String(index % 1_000).padStart(3, "0"),
      subfamilyCode: `999${String(index % 1_000).padStart(3, "0")}`
    }));

    const codes = stockBulkClassificationCodesForRows([{
      id: "row-1",
      selected: false,
      query: "A001",
      product,
      draft: { ...product }
    }], {
      families: [
        { id: "family-1", familyCode: "010" },
        { id: "unused-family", familyCode: "999" }
      ],
      subfamilies: [
        { id: "subfamily-1", familyId: "family-1", subfamilySuffix: "002" },
        ...unrelatedSubfamilies
      ]
    });

    expect(codes).toEqual({
      familyCodes: { "family-1": "010" },
      subfamilyCodes: { "subfamily-1": "010002" }
    });
  });

  it("clears imported subfamilies per row without treating an unmapped column as blank", () => {
    expect(shouldClearStockBulkImportedSubfamily({
      familyColumnMapped: false,
      familyReference: undefined,
      subfamilyColumnMapped: true,
      subfamilyReference: ""
    })).toBe(true);
    expect(shouldClearStockBulkImportedSubfamily({
      familyColumnMapped: true,
      familyReference: "010",
      subfamilyColumnMapped: false,
      subfamilyReference: undefined
    })).toBe(true);
    expect(shouldClearStockBulkImportedSubfamily({
      familyColumnMapped: false,
      familyReference: undefined,
      subfamilyColumnMapped: false,
      subfamilyReference: undefined
    })).toBe(false);
    expect(shouldClearStockBulkImportedSubfamily({
      familyColumnMapped: true,
      familyReference: "010",
      subfamilyColumnMapped: true,
      subfamilyReference: "010002"
    })).toBe(false);
  });

  it("moves a classified product to family 002 and clears a blank mapped subfamily", () => {
    const catalog = {
      families: [
        { id: "family-001", familyCode: "001", name: "COCINA" },
        { id: "family-002", familyCode: "002", name: "ELECTRONICA" }
      ],
      subfamilies: [{
        id: "subfamily-001001",
        familyId: "family-001",
        subfamilyCode: "001001",
        subfamilySuffix: "001",
        name: "UTENSILIOS"
      }]
    };

    expect(resolveStockBulkImportedClassification({
      currentFamilyId: "family-001",
      familyColumnMapped: true,
      familyReference: "002",
      subfamilyColumnMapped: true,
      subfamilyReference: "",
      catalog
    })).toEqual({
      ok: true,
      draft: {
        familyId: "family-002",
        familyName: "ELECTRONICA",
        subfamilyId: "-",
        subfamilyName: "-"
      }
    });

    expect(resolveStockBulkImportedClassification({
      currentFamilyId: "family-001",
      familyColumnMapped: false,
      familyReference: undefined,
      subfamilyColumnMapped: false,
      subfamilyReference: undefined,
      catalog
    })).toEqual({ ok: true, draft: {} });
  });

  it("prefers commercial codes over names that contain the same numeric value", () => {
    const catalog = {
      families: [
        { id: "family-code", familyCode: "001", familyId: "COCINA", name: "COCINA" },
        { id: "family-name", familyCode: "002", familyId: "DOS", name: "001" }
      ],
      subfamilies: [
        {
          id: "subfamily-code",
          familyId: "family-code",
          subfamilyCode: "001001",
          subfamilySuffix: "001",
          subfamilyId: "UTENSILIOS",
          name: "UTENSILIOS"
        },
        {
          id: "subfamily-name",
          familyId: "family-code",
          subfamilyCode: "001002",
          subfamilySuffix: "002",
          subfamilyId: "OTRA",
          name: "001001"
        }
      ]
    };

    expect(resolveStockBulkImportedClassification({
      currentFamilyId: "family-name",
      familyColumnMapped: true,
      familyReference: "001",
      subfamilyColumnMapped: true,
      subfamilyReference: "001001",
      catalog
    })).toEqual({
      ok: true,
      draft: {
        familyId: "family-code",
        familyName: "COCINA",
        subfamilyId: "subfamily-code",
        subfamilyName: "UTENSILIOS"
      }
    });
  });

  it("prefers legacy aliases over colliding names and keeps the selected family scope", () => {
    const catalog = {
      families: [
        { id: "family-alias", familyCode: "010", familyId: "LEGACY_FAMILY", name: "COCINA" },
        { id: "family-name", familyCode: "020", familyId: "OTHER", name: "LEGACY_FAMILY" }
      ],
      subfamilies: [
        {
          id: "subfamily-alias",
          familyId: "family-alias",
          subfamilyCode: "010001",
          subfamilySuffix: "001",
          subfamilyId: "LEGACY_CHILD",
          name: "UTENSILIOS"
        },
        {
          id: "subfamily-name",
          familyId: "family-alias",
          subfamilyCode: "010002",
          subfamilySuffix: "002",
          subfamilyId: "OTHER_CHILD",
          name: "LEGACY_CHILD"
        },
        {
          id: "same-alias-other-family",
          familyId: "family-name",
          subfamilyCode: "020001",
          subfamilySuffix: "001",
          subfamilyId: "LEGACY_CHILD",
          name: "OTRA"
        }
      ]
    };

    expect(resolveStockBulkImportedClassification({
      currentFamilyId: "family-name",
      familyColumnMapped: true,
      familyReference: "LEGACY_FAMILY",
      subfamilyColumnMapped: true,
      subfamilyReference: "LEGACY_CHILD",
      catalog
    })).toEqual({
      ok: true,
      draft: {
        familyId: "family-alias",
        familyName: "COCINA",
        subfamilyId: "subfamily-alias",
        subfamilyName: "UTENSILIOS"
      }
    });
  });

  it("prefers internal UUIDs over aliases and names with the same value", () => {
    const familyUuid = "11111111-1111-4111-8111-111111111111";
    const subfamilyUuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    const catalog = {
      families: [
        { id: familyUuid, familyCode: "030", familyId: "FAMILY_UUID", name: "HOGAR" },
        { id: "22222222-2222-4222-8222-222222222222", familyCode: "040", familyId: familyUuid, name: familyUuid }
      ],
      subfamilies: [
        {
          id: subfamilyUuid,
          familyId: familyUuid,
          subfamilyCode: "030001",
          subfamilySuffix: "001",
          subfamilyId: "SUBFAMILY_UUID",
          name: "TEXTIL"
        },
        {
          id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
          familyId: familyUuid,
          subfamilyCode: "030002",
          subfamilySuffix: "002",
          subfamilyId: subfamilyUuid,
          name: subfamilyUuid
        }
      ]
    };

    expect(resolveStockBulkImportedClassification({
      currentFamilyId: "",
      familyColumnMapped: true,
      familyReference: familyUuid,
      subfamilyColumnMapped: true,
      subfamilyReference: subfamilyUuid,
      catalog
    })).toEqual({
      ok: true,
      draft: {
        familyId: familyUuid,
        familyName: "HOGAR",
        subfamilyId: subfamilyUuid,
        subfamilyName: "TEXTIL"
      }
    });
  });

  it("resolves a six-digit subfamily code globally when no family column is mapped", () => {
    const catalog = {
      families: [
        { id: "family-001", familyCode: "001", name: "COCINA" },
        { id: "family-002", familyCode: "002", name: "ELECTRONICA" }
      ],
      subfamilies: [
        {
          id: "local-name-collision",
          familyId: "family-001",
          subfamilyCode: "001001",
          subfamilySuffix: "001",
          name: "002001"
        },
        {
          id: "remote-code",
          familyId: "family-002",
          subfamilyCode: "002001",
          subfamilySuffix: "001",
          name: "CABLES"
        }
      ]
    };

    expect(resolveStockBulkImportedClassification({
      currentFamilyId: "family-001",
      familyColumnMapped: false,
      familyReference: undefined,
      subfamilyColumnMapped: true,
      subfamilyReference: "002001",
      catalog
    })).toEqual({
      ok: true,
      draft: {
        familyId: "family-002",
        familyName: "ELECTRONICA",
        subfamilyId: "remote-code",
        subfamilyName: "CABLES"
      }
    });
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
        "Precio venta", "Precio de miembro", "Precio mayor", "Precio oferta", "Usar precio",
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

  it("resolves readable family and subfamily codes to UUID references", async () => {
    readSheetMock.mockResolvedValueOnce([
      ["Codigo", "Codigo familia", "Codigo subfamilia"],
      ["A001", "010", "010002"]
    ] as never);

    const rows = await importStockBulkFile(
      new File(["xlsx"], "productos.xlsx"),
      [product],
      {
        families: [{ id: "family-2", name: "Alimentacion", code: "010", legacyCode: "ALIMENTACION" }],
        subfamilies: [{
          id: "subfamily-2",
          familyId: "family-2",
          name: "Conservas",
          code: "010002",
          legacyCode: "CONSERVAS"
        }]
      }
    );

    expect(rows[0].draft).toEqual(expect.objectContaining({
      familyId: "family-2",
      familyName: "Alimentacion",
      subfamilyId: "subfamily-2",
      subfamilyName: "Conservas"
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

  it("stages a manual principal supplier and otherwise displays the latest supplier", () => {
    const latest = {
      id: "supplier-latest",
      supplierCode: "P-002",
      legalName: "Proveedor reciente",
      documentNumber: "B00000002",
      active: true,
      lastSupplier: true,
      principal: false
    };
    const preferred = {
      id: "supplier-preferred",
      supplierCode: "P-001",
      legalName: "Proveedor principal",
      documentNumber: "B00000001",
      active: true,
      lastSupplier: false,
      principal: false
    };
    const row = {
      id: "row-1",
      selected: false,
      query: "A001",
      product,
      draft: {},
      suppliers: [latest, preferred],
      principalSupplierChanged: true,
      pendingPrincipalSupplierId: preferred.id
    };

    expect(stockBulkRowsChanged([row])).toBe(true);
    expect(stockBulkDisplayedSupplier(row)).toEqual(preferred);
    expect(buildStockBulkSupplierPrincipalAssignments([row])).toEqual([{
      productId: product.productId,
      supplierId: preferred.id
    }]);

    const finalized = finalizeStockBulkSupplierAssignments([row]);
    expect(finalized[0].suppliers?.find((supplier) => supplier.id === preferred.id)?.principal).toBe(true);
    expect(finalized[0]).not.toHaveProperty("principalSupplierChanged");
    expect(finalized[0]).not.toHaveProperty("pendingPrincipalSupplierId");
  });

  it("links and marks a principal supplier across the selected bulk rows", () => {
    const supplier = {
      id: "supplier-principal",
      supplierCode: "P-003",
      legalName: "Proveedor masivo",
      documentNumber: "B00000003",
      active: true,
      lastSupplier: false,
      principal: false
    };
    const rows = [
      { id: "row-1", selected: true, query: "A001", product, draft: {}, suppliers: [] },
      { id: "row-2", selected: true, query: "A002", product: { ...product, productId: "product-2" }, draft: {}, suppliers: [supplier] },
      { id: "row-3", selected: false, query: "A003", product: { ...product, productId: "product-3" }, draft: {}, suppliers: [] }
    ];

    const staged = stageStockBulkPrincipalSupplier(rows, ["row-1", "row-2"], supplier);

    expect(staged[0]).toEqual(expect.objectContaining({
      pendingSupplier: supplier,
      principalSupplierChanged: true,
      pendingPrincipalSupplierId: supplier.id
    }));
    expect(staged[1]).toEqual(expect.objectContaining({
      pendingSupplier: undefined,
      principalSupplierChanged: true,
      pendingPrincipalSupplierId: supplier.id
    }));
    expect(staged[2]).toEqual(rows[2]);
    expect(buildStockBulkSupplierAssignments(staged)).toEqual([{
      supplierId: supplier.id,
      productIds: [product.productId]
    }]);
    expect(buildStockBulkSupplierPrincipalAssignments(staged)).toEqual([
      { productId: product.productId, supplierId: supplier.id },
      { productId: "product-2", supplierId: supplier.id }
    ]);
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
