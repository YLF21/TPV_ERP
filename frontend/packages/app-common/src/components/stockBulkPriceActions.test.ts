import { describe, expect, it } from "vitest";
import { applyStockBulkPriceActionChanges, newStockBulkPriceAction, validateStockBulkPriceActions } from "./stockBulkPriceActions";

describe("stock bulk price actions", () => {
  it("defaults to the active price field and requires an entered price", () => {
    const action = newStockBulkPriceAction("MEMBER_PRICE");
    expect(action).toEqual({ type: "FIXED_PRICE", field: "MEMBER_PRICE", value: "" });
    expect(validateStockBulkPriceActions([action])).toBe("stock.bulkEdit.rules.validation.price");
  });

  it("rejects missing or out-of-range decimal endings", () => {
    for (const cents of ["", 100] as const) {
      expect(validateStockBulkPriceActions([{ type: "DECIMAL_ENDING", field: "SALE_PRICE", cents }]))
        .toBe("stock.bulkEdit.rules.validation.cents");
    }
  });

  it("applies a preview only to selected rows already present in the list", () => {
    const selected = { selected: true, product: { productId: "product-1" }, draft: { salePrice: "10" } };
    const unselected = { selected: false, product: { productId: "product-2" }, draft: { salePrice: "20" } };
    const rows = applyStockBulkPriceActionChanges([selected, unselected], {
      products: [
        { productId: "product-1", changes: [
          { field: "SALE_PRICE", after: 15.5 },
          { field: "OFFER_ACTIVE", after: true }
        ] },
        { productId: "product-2", changes: [
          { field: "SALE_PRICE", after: 25 }
        ] },
        { productId: "outside-list", changes: [
          { field: "SALE_PRICE", after: 99 }
        ] }
      ]
    });

    expect(rows).toEqual([
      expect.objectContaining({ draft: { salePrice: "15.5", offerActive: "common.yes" } }),
      unselected
    ]);
    expect(rows).toHaveLength(2);
  });

});
