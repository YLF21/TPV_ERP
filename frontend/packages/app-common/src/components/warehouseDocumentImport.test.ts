import { describe, expect, it } from "vitest";
import { buildWarehouseDocumentLines } from "./warehouseDocumentImport";

const products = [
  { id: "product-1", code: "A001", barcode: "8430000000011", name: "Cafe molido" },
  { id: "product-2", code: "B002", reference: "REF-B", name: "Pan integral" }
];

describe("warehouseDocumentImport", () => {
  it("matches imported rows by code, barcode, reference and name", () => {
    const rows = buildWarehouseDocumentLines(
      [
        { codigo: "A001", cantidad: "2" },
        { barcode: "8430000000011", cantidad: "3" },
        { referencia: "REF-B", cantidad: "4" },
        { producto: "pan integral", cantidad: "5" }
      ],
      products
    );

    expect(rows).toEqual([
      expect.objectContaining({ productId: "product-1", quantity: 2, valid: true }),
      expect.objectContaining({ productId: "product-1", quantity: 3, valid: true }),
      expect.objectContaining({ productId: "product-2", quantity: 4, valid: true }),
      expect.objectContaining({ productId: "product-2", quantity: 5, valid: true })
    ]);
  });

  it("marks unknown products and invalid quantities", () => {
    const rows = buildWarehouseDocumentLines(
      [
        { codigo: "NOPE", cantidad: "2" },
        { codigo: "A001", cantidad: "0" },
        { codigo: "B002", cantidad: "abc" }
      ],
      products
    );

    expect(rows).toEqual([
      expect.objectContaining({ productId: "", quantity: 2, valid: false, errorKey: "warehouseDocument.error.productNotFound" }),
      expect.objectContaining({ productId: "product-1", quantity: 0, valid: false, errorKey: "warehouseDocument.error.invalidQuantity" }),
      expect.objectContaining({ productId: "product-2", quantity: 0, valid: false, errorKey: "warehouseDocument.error.invalidQuantity" })
    ]);
  });
});
