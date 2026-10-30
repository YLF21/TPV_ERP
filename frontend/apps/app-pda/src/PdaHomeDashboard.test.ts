import { describe, expect, it } from "vitest";
import { summarizePdaHome } from "./PdaHomeDashboard";

describe("PdaHomeDashboard", () => {
  it("summarizes pending warehouse work", () => {
    const result = summarizePdaHome(
      [{ status: "ABIERTA" }, { status: "COMPLETA" }],
      [{ type: "PICKING", status: "OPEN" }, { type: "INCIDENT", status: "OPEN" }, { type: "INCIDENT", status: "DONE" }],
      [{ product: { id: "p1", name: "Café", stockMin: 4, stockMax: 10 }, stock: [{ productId: "p1", warehouseId: "shop", quantity: 1 }, { productId: "p1", warehouseId: "reserve", quantity: 20 }] }],
      [{ id: "shop" }, { id: "reserve" }],
      "shop"
    );
    expect(result).toEqual({ pending: 1, low: 1, assigned: 1, incidents: 1, urgent: 0 });
  });

  it("counts only work assigned to the authenticated user", () => {
    const result = summarizePdaHome(
      [],
      [
        { type: "PICKING", status: "OPEN", assignedUserId: "current" },
        { type: "INCIDENT", status: "OPEN", assignedUserId: "another" }
      ],
      [], [], "", "current"
    );
    expect(result.assigned).toBe(1);
    expect(result.incidents).toBe(0);
  });
});
