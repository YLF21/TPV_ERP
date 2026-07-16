import { describe, expect, it } from "vitest";
import {
  promotionActionDisabled,
  promotionActionRequest,
  promotionDateRange,
  promotionListColumnDefinitions,
  promotionListTableKey
} from "./PromotionListScreen";
import type { PromotionView } from "./PromotionWizard";

function promotion(overrides: Partial<PromotionView> = {}): PromotionView {
  return {
    id: "promotion/1",
    name: "Pack cafe",
    type: "FIXED_PACK_PRICE",
    status: "DRAFT",
    startDate: "2026-07-11",
    endDate: null,
    scope: "SALE",
    customerSegment: "ALL",
    memberCategoryId: null,
    minimumAmount: null,
    minimumQuantity: null,
    buyQuantity: 3,
    payQuantity: null,
    buyXPayYMode: null,
    discountAmount: null,
    discountPercent: null,
    maximumDiscount: null,
    packPrice: 9.99,
    used: false,
    targets: [],
    ...overrides
  };
}

describe("PromotionListScreen", () => {
  it("builds encoded mutation endpoints", () => {
    expect(promotionActionRequest("duplicate", "promotion/1")).toEqual({
      path: "/promotions/promotion%2F1/duplicate",
      method: "POST"
    });
    expect(promotionActionRequest("delete", "promotion/1")).toEqual({
      path: "/promotions/promotion%2F1",
      method: "DELETE"
    });
  });

  it("only enables actions accepted by each promotion state", () => {
    const draft = promotion();
    expect(promotionActionDisabled("duplicate", draft)).toBe(false);
    expect(promotionActionDisabled("activate", draft)).toBe(false);
    expect(promotionActionDisabled("deactivate", draft)).toBe(true);
    expect(promotionActionDisabled("delete", draft)).toBe(false);

    const active = promotion({ status: "ACTIVE" });
    expect(promotionActionDisabled("activate", active)).toBe(true);
    expect(promotionActionDisabled("deactivate", active)).toBe(false);
    expect(promotionActionDisabled("delete", active)).toBe(true);

    expect(promotionActionDisabled("delete", promotion({ status: "INACTIVE", used: true }))).toBe(true);
  });

  it("formats open and bounded validity dates", () => {
    expect(promotionDateRange(promotion())).toBe("2026-07-11");
    expect(promotionDateRange(promotion({ endDate: "2026-07-31" })))
      .toBe("2026-07-11 - 2026-07-31");
  });

  it("defines the persisted promotion data columns without the fixed actions column", () => {
    expect(promotionListTableKey).toBe("promotions.list");
    expect(promotionListColumnDefinitions.map((column) => column.key))
      .toEqual(["name", "status", "type", "date", "segment"]);
  });
});
