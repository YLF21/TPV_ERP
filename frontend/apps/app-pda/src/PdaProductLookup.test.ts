import { describe, expect, it } from "vitest";
import {
  pdaExpandLabelJobs,
  pdaPriceLookupPath,
  pdaPrintableBarcode,
  pdaProductPath,
  pdaStockLookupPath
} from "./PdaProductLookup";

describe("PdaProductLookup", () => {
  it("encodes scanned identifiers and product ids", () => {
    expect(pdaPriceLookupPath(" 84/10 ")).toBe("/products/sale/price-consultation?identifier=84%2F10");
    expect(pdaStockLookupPath("product/1")).toBe("/stock?productId=product%2F1");
    expect(pdaProductPath("product/1")).toBe("/products/product%2F1");
  });

  it("prefers the primary EAN and falls back to a scanned EAN", () => {
    expect(pdaPrintableBarcode({ barcode: "8412345678901", barcode2: "12345670" }, "00000000"))
      .toBe("8412345678901");
    expect(pdaPrintableBarcode({ barcode: "INTERNAL" }, " 12345670 "))
      .toBe("12345670");
    expect(pdaPrintableBarcode({ barcode: "INTERNAL" }, "DEV-CAFE"))
      .toBe("");
  });

  it("expands every queued label copy for batch printing", () => {
    const labels = pdaExpandLabelJobs([
      { id: "a", productId: "p1", code: "A", name: "One", price: 1, barcode: "12345670", copies: 2 },
      { id: "b", productId: "p2", code: "B", name: "Two", price: 2, barcode: "8412345678901", copies: 1 }
    ]);
    expect(labels.map((label) => `${label.id}-${label.copyIndex}`)).toEqual(["a-0", "a-1", "b-0"]);
  });
});