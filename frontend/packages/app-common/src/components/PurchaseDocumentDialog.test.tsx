import { describe, expect, it } from "vitest";
import { buildPurchaseDocumentRequest, confirmedPurchaseDocumentPath, purchaseDocumentLineTotal, purchaseDocumentPath } from "./PurchaseDocumentDialog";

describe("PurchaseDocumentDialog", () => {
  it("maps invoice and delivery-note endpoints", () => {
    expect(purchaseDocumentPath("invoice")).toBe("/invoices");
    expect(purchaseDocumentPath("deliveryNote")).toBe("/delivery-notes");
    expect(confirmedPurchaseDocumentPath("invoice")).toBe("/invoices/confirmed");
    expect(confirmedPurchaseDocumentPath("deliveryNote")).toBe("/delivery-notes/confirmed");
  });

  it("builds a backend purchase invoice command without trusting sales prices", () => {
    expect(buildPurchaseDocumentRequest({
      mode: "invoice",
      warehouseId: "warehouse-1",
      supplierId: "supplier-1",
      date: "2026-07-16",
      externalNumber: " F-EXT-9 ",
      directStock: true,
      lines: [{
        productId: "product-1",
        code: "P-1",
        name: "Producto",
        quantity: 2,
        unitPrice: 4.5,
        discount: 10,
        taxRegime: "IVA",
        taxPercentage: 7,
        taxesIncluded: false
      }]
    })).toEqual({
      almacenId: "warehouse-1",
      tipo: "FACTURA_COMPRA",
      fecha: "2026-07-16",
      proveedorId: "supplier-1",
      numeroExterno: "F-EXT-9",
      descuentoGlobal: 0,
      directo: true,
      lineas: [{
        productoId: "product-1",
        cantidad: 2,
        codigo: "P-1",
        nombre: "Producto",
        tarifa: "COMPRA",
        precioUnitario: 4.5,
        descuento: 10,
        impuestosIncluidos: false,
        regimenImpuesto: "IVA",
        porcentajeImpuesto: 7,
        lineType: "PRODUCT"
      }]
    });
  });

  it("adds tax to totals only when the entered price excludes tax", () => {
    const line = {
      productId: "product-1",
      code: "P-1",
      name: "Producto",
      quantity: 2,
      unitPrice: 10,
      discount: 0,
      taxRegime: "IGIC" as const,
      taxPercentage: 7,
      taxesIncluded: false
    };

    expect(purchaseDocumentLineTotal(line)).toBeCloseTo(21.4);
    expect(purchaseDocumentLineTotal({ ...line, taxesIncluded: true })).toBeCloseTo(20);
  });

  it("never marks a purchase delivery note as a direct invoice", () => {
    const request = buildPurchaseDocumentRequest({
      mode: "deliveryNote",
      warehouseId: "warehouse-1",
      supplierId: "supplier-1",
      date: "2026-07-16",
      externalNumber: "",
      directStock: true,
      lines: []
    });

    expect(request.tipo).toBe("ALBARAN_COMPRA");
    expect(request.directo).toBe(false);
  });
});
