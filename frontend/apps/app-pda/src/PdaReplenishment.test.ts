import { describe, expect, it } from "vitest";
import {
  buildReplenishmentSuggestions,
  hasAtMostThreeDecimals,
  pdaReplenishmentPagePath,
  suggestedReplenishmentWarehouses
} from "./PdaReplenishment";

describe("PdaReplenishment", () => {
  const warehouses = [
    { id: "shop", name: "Tienda" },
    { id: "reserve", name: "Reserva" },
    { id: "general", name: "General" }
  ];

  it("suggests the warehouse with the highest stock as source", () => {
    expect(suggestedReplenishmentWarehouses([
      { productId: "p1", warehouseId: "shop", quantity: 2 },
      { productId: "p1", warehouseId: "reserve", quantity: 14 },
      { productId: "p1", warehouseId: "general", quantity: 5 }
    ], warehouses)).toEqual({ sourceId: "reserve", targetId: "general" });
  });

  it("finds products below minimum and suggests filling up to maximum", () => {
    const suggestions = buildReplenishmentSuggestions([{
      product: { id: "p1", code: "CAFE", name: "Café", stockMin: 4, stockMax: 10, active: true },
      stock: [
        { productId: "p1", warehouseId: "shop", quantity: 2 },
        { productId: "p1", warehouseId: "reserve", quantity: 20 }
      ]
    }, {
      product: { id: "p2", code: "TE", name: "Té", stockMin: 3, stockMax: 8, active: true },
      stock: [{ productId: "p2", warehouseId: "shop", quantity: 3 }]
    }], warehouses, "shop");

    expect(suggestions).toHaveLength(1);
    expect(suggestions[0]).toMatchObject({
      sourceWarehouseId: "reserve",
      targetWarehouseId: "shop",
      currentQuantity: 2,
      minimumQuantity: 4,
      targetQuantity: 10,
      suggestedQuantity: 8
    });
  });

  it("limits the suggestion to stock available at the source", () => {
    const [suggestion] = buildReplenishmentSuggestions([{
      product: { id: "p1", stockMin: 5, stockMax: 12 },
      stock: [
        { productId: "p1", warehouseId: "shop", quantity: 1 },
        { productId: "p1", warehouseId: "reserve", quantity: 3 }
      ]
    }], warehouses, "shop");
    expect(suggestion?.suggestedQuantity).toBe(3);
  });

  it("builds an encoded inventory page path", () => {
    expect(pdaReplenishmentPagePath("next/value")).toContain("cursor=next%2Fvalue");
  });

  it("accepts at most three decimals", () => {
    expect(hasAtMostThreeDecimals(1)).toBe(true);
    expect(hasAtMostThreeDecimals(1.125)).toBe(true);
    expect(hasAtMostThreeDecimals(1.1255)).toBe(false);
  });
});