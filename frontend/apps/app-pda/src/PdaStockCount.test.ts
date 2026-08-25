import { describe, expect, it } from "vitest";
import { nextCountedQuantity, pdaStockCountListPath } from "./PdaStockCount";

describe("PdaStockCount", () => {
  it("builds filters with encoded warehouse identifiers", () => {
    expect(pdaStockCountListPath("DRAFT", "warehouse/value"))
      .toBe("/stock-counts?status=DRAFT&warehouseId=warehouse%2Fvalue");
  });

  it("accumulates scans with at most three decimals", () => {
    expect(nextCountedQuantity("1.125", "2.25")).toBe(3.375);
    expect(nextCountedQuantity(undefined, 1)).toBe(1);
  });

  it("rejects invalid increments", () => {
    expect(nextCountedQuantity(2, 0)).toBeNull();
    expect(nextCountedQuantity(2, -1)).toBeNull();
    expect(nextCountedQuantity("invalid", 1)).toBeNull();
  });
});