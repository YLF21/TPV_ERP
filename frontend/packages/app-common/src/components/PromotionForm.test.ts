import { describe, expect, it } from "vitest";
import {
  buildPromotionRequest,
  createDefaultPromotionDraft,
  promotionTargetTypeForScope,
  validatePromotionDraft,
  type PromotionDraft,
  type PromotionRequest,
  type PromotionType
} from "./PromotionForm";

function draftFor(type: PromotionType): PromotionDraft {
  const draft = {
    ...createDefaultPromotionDraft("2026-07-11"),
    name: "Promocion completa",
    type
  };
  switch (type) {
    case "PURCHASE_THRESHOLD_COUPON":
      return {
        ...draft,
        minimumAmount: "50.00",
        couponAmount: "5.00",
        couponMaximumDiscount: "10.00",
        couponMinimumAmount: "20.00",
        couponValidFromDays: "1",
        couponValidDays: "30"
      };
    case "PURCHASE_THRESHOLD_DISCOUNT":
      return {
        ...draft,
        minimumAmount: "100.00",
        discountPercent: "10.00",
        maximumDiscount: "25.00"
      };
    case "BUY_X_PAY_Y":
      return {
        ...draft,
        buyQuantity: "3",
        payQuantity: "2",
        buyXPayYMode: "MIXED_TARGETS"
      };
    case "SECOND_UNIT_PERCENT":
      return { ...draft, discountPercent: "50.00" };
    case "FIXED_PACK_PRICE":
      return { ...draft, buyQuantity: "4", packPrice: "9.99" };
    case "QUANTITY_DISCOUNT":
      return {
        ...draft,
        minimumQuantity: "2.500",
        discountAmount: "1.50",
        maximumDiscount: "8.00"
      };
  }
}

function emptyRequest(type: PromotionType): PromotionRequest {
  return {
    name: "Promocion completa",
    type,
    startDate: "2026-07-11",
    endDate: null,
    scope: "SALE",
    customerSegment: "ALL",
    memberCategoryId: null,
    minimumAmount: null,
    minimumQuantity: null,
    buyQuantity: null,
    payQuantity: null,
    buyXPayYMode: null,
    discountAmount: null,
    discountPercent: null,
    maximumDiscount: null,
    packPrice: null,
    couponAmount: null,
    couponPercent: null,
    couponMaximumDiscount: null,
    couponMinimumAmount: null,
    couponValidFromDate: null,
    couponValidFromDays: null,
    couponValidUntilDate: null,
    couponValidDays: null,
    targets: []
  };
}

describe("PromotionForm payload", () => {
  it.each([
    ["PURCHASE_THRESHOLD_COUPON", {
      minimumAmount: "50.00",
      couponAmount: "5.00",
      couponMaximumDiscount: "10.00",
      couponMinimumAmount: "20.00",
      couponValidFromDays: 1,
      couponValidDays: 30
    }],
    ["PURCHASE_THRESHOLD_DISCOUNT", {
      minimumAmount: "100.00",
      discountPercent: "10.00",
      maximumDiscount: "25.00"
    }],
    ["BUY_X_PAY_Y", {
      buyQuantity: "3",
      payQuantity: "2",
      buyXPayYMode: "MIXED_TARGETS"
    }],
    ["SECOND_UNIT_PERCENT", { discountPercent: "50.00" }],
    ["FIXED_PACK_PRICE", { buyQuantity: "4", packPrice: "9.99" }],
    ["QUANTITY_DISCOUNT", {
      minimumQuantity: "2.500",
      discountAmount: "1.50",
      maximumDiscount: "8.00"
    }]
  ] as const)("builds the complete %s request", (type, fields) => {
    const draft = draftFor(type);
    expect(validatePromotionDraft(draft)).toEqual([]);
    expect(buildPromotionRequest(draft)).toEqual({
      ...emptyRequest(type),
      ...fields
    });
  });

  it.each([
    ["PRODUCT_LIST", "PRODUCT"],
    ["FAMILY", "FAMILY"],
    ["SUBFAMILY", "SUBFAMILY"]
  ] as const)("maps %s targets to %s", (scope, targetType) => {
    const draft = {
      ...draftFor("SECOND_UNIT_PERCENT"),
      scope,
      targetIds: ["target-1", "target-2"]
    };

    expect(buildPromotionRequest(draft).targets).toEqual([
      { type: targetType, targetId: "target-1" },
      { type: targetType, targetId: "target-2" }
    ]);
    expect(promotionTargetTypeForScope(scope)).toBe(targetType);
    expect(validatePromotionDraft(draft)).toEqual([]);
  });

  it("only sends the member category for the matching segment", () => {
    const categoryDraft = {
      ...draftFor("SECOND_UNIT_PERCENT"),
      customerSegment: "MEMBER_CATEGORY" as const,
      memberCategoryId: "category-1"
    };
    expect(buildPromotionRequest(categoryDraft).memberCategoryId).toBe("category-1");
    expect(buildPromotionRequest({
      ...categoryDraft,
      customerSegment: "MEMBERS_ONLY"
    }).memberCategoryId).toBeNull();
  });

  it("builds a percentage coupon with absolute validity dates", () => {
    const draft = {
      ...draftFor("PURCHASE_THRESHOLD_COUPON"),
      couponAmount: "",
      couponPercent: "12.50",
      couponValidFromDays: "",
      couponValidFromDate: "2026-07-15",
      couponValidDays: "",
      couponValidUntilDate: "2026-08-15"
    };

    expect(validatePromotionDraft(draft)).toEqual([]);
    expect(buildPromotionRequest(draft)).toMatchObject({
      couponAmount: null,
      couponPercent: "12.50",
      couponValidFromDate: "2026-07-15",
      couponValidFromDays: null,
      couponValidUntilDate: "2026-08-15",
      couponValidDays: null
    });
  });
});

describe("PromotionForm validation", () => {
  it("requires and validates targets for every non-sale scope", () => {
    expect(validatePromotionDraft({
      ...draftFor("SECOND_UNIT_PERCENT"),
      scope: "PRODUCT_LIST"
    })).toContain("TARGETS_REQUIRED");
    expect(validatePromotionDraft({
      ...draftFor("SECOND_UNIT_PERCENT"),
      scope: "SALE",
      targetIds: ["product-1"]
    })).toContain("TARGETS_NOT_ALLOWED");
    expect(validatePromotionDraft({
      ...draftFor("SECOND_UNIT_PERCENT"),
      scope: "FAMILY",
      targetIds: ["family-1", "family-1"]
    })).toContain("TARGETS_INVALID");
  });

  it("requires a real member category only for MEMBER_CATEGORY", () => {
    expect(validatePromotionDraft({
      ...draftFor("SECOND_UNIT_PERCENT"),
      customerSegment: "MEMBER_CATEGORY"
    })).toContain("MEMBER_CATEGORY_REQUIRED");
  });

  it("validates the specific rules of all six promotion types", () => {
    expect(validatePromotionDraft({
      ...draftFor("PURCHASE_THRESHOLD_COUPON"),
      couponPercent: "15"
    })).toContain("COUPON_VALUE_INVALID");
    expect(validatePromotionDraft({
      ...draftFor("PURCHASE_THRESHOLD_COUPON"),
      couponValidDays: ""
    })).toContain("COUPON_VALID_UNTIL_INVALID");
    expect(validatePromotionDraft({
      ...draftFor("PURCHASE_THRESHOLD_DISCOUNT"),
      discountPercent: ""
    })).toContain("DISCOUNT_VALUE_INVALID");
    expect(validatePromotionDraft({
      ...draftFor("BUY_X_PAY_Y"),
      payQuantity: "3"
    })).toContain("BUY_PAY_RELATION_INVALID");
    expect(validatePromotionDraft({
      ...draftFor("SECOND_UNIT_PERCENT"),
      discountPercent: "0"
    })).toContain("DISCOUNT_VALUE_INVALID");
    expect(validatePromotionDraft({
      ...draftFor("FIXED_PACK_PRICE"),
      buyQuantity: "2.5"
    })).toContain("BUY_QUANTITY_INVALID");
    expect(validatePromotionDraft({
      ...draftFor("QUANTITY_DISCOUNT"),
      minimumQuantity: "0"
    })).toContain("MINIMUM_QUANTITY_INVALID");
  });

  it("rejects invalid promotion and coupon date ranges", () => {
    expect(validatePromotionDraft({
      ...draftFor("SECOND_UNIT_PERCENT"),
      endDate: "2026-07-10"
    })).toContain("DATE_RANGE_INVALID");
    expect(validatePromotionDraft({
      ...draftFor("PURCHASE_THRESHOLD_COUPON"),
      couponValidFromDays: "",
      couponValidFromDate: "2026-08-10",
      couponValidDays: "",
      couponValidUntilDate: "2026-08-01"
    })).toContain("COUPON_DATE_RANGE_INVALID");
  });
});
