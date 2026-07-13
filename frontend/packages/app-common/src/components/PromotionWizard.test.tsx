import { readFileSync } from "node:fs";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { ErpSelect } from "./ErpSelect";
import {
  advancePromotionWizardStep,
  createDefaultPromotionDraft,
  filterPromotionTargetOptions,
  promotionFamiliesPath,
  promotionMemberCategoriesPath,
  promotionProductsPath,
  promotionSubfamiliesPath,
  type PromotionWizardStep,
  promotionWizardSteps,
  submitPromotionDraft
} from "./PromotionWizard";

const promotionWizardSource = readFileSync(new URL("./PromotionWizard.tsx", import.meta.url), "utf8");

describe("PromotionWizard", () => {
  it("advances through the wizard and submits a draft promotion", async () => {
    let step: PromotionWizardStep = promotionWizardSteps[0];

    for (let index = 1; index < promotionWizardSteps.length; index += 1) {
      step = advancePromotionWizardStep(step);
      expect(step).toBe(promotionWizardSteps[index]);
    }

    const request = vi.fn().mockResolvedValue({
      id: "promotion-1",
      status: "DRAFT"
    });
    const draft = {
      ...createDefaultPromotionDraft("2026-07-09"),
      name: "Promo verano",
      type: "BUY_X_PAY_Y" as const,
      startDate: "2026-07-09",
      endDate: "2026-07-31",
      scope: "SALE" as const,
      customerSegment: "ALL" as const,
      buyQuantity: "3",
      payQuantity: "2"
    };

    await submitPromotionDraft(draft, "token-123", request);

    expect(request).toHaveBeenCalledWith("/promotions", {
      token: "token-123",
      method: "POST",
      body: {
        name: "Promo verano",
        type: "BUY_X_PAY_Y",
        startDate: "2026-07-09",
        endDate: "2026-07-31",
        scope: "SALE",
        customerSegment: "ALL",
        memberCategoryId: null,
        minimumAmount: null,
        minimumQuantity: null,
        buyQuantity: "3",
        payQuantity: "2",
        buyXPayYMode: "SAME_PRODUCT",
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
      }
    });
  });

  it("does not call the API for an invalid type-specific draft", async () => {
    const request = vi.fn();
    const draft = {
      ...createDefaultPromotionDraft("2026-07-11"),
      name: "Descuento incompleto"
    };

    await expect(submitPromotionDraft(draft, "token-123", request)).rejects.toThrow("promotion draft is invalid");
    expect(request).not.toHaveBeenCalled();
  });

  it("uses the catalog endpoints and filters formal target options", () => {
    expect(promotionProductsPath).toBe("/products");
    expect(promotionFamiliesPath).toBe("/families");
    expect(promotionSubfamiliesPath("family/1")).toBe("/families/family%2F1/subfamilies");
    expect(promotionMemberCategoriesPath).toBe("/member-categories");
    expect(filterPromotionTargetOptions([
      { id: "1", label: "A001 - Cafe" },
      { id: "2", label: "B002 - Te" }
    ], "cafe")).toEqual([{ id: "1", label: "A001 - Cafe" }]);
  });

  it("uses the shared ERP select markup without native selects", () => {
    const html = renderToStaticMarkup(
      <ErpSelect
        aria-label="Tipo"
        value="BUY_X_PAY_Y"
        options={[{ value: "BUY_X_PAY_Y", label: "Compra X paga Y" }]}
        onChange={vi.fn()}
      />
    );

    expect(html).toContain("erp-select__trigger");
    expect(html).not.toContain("<select");
    expect(promotionWizardSource.match(/<ErpSelect/g)).toHaveLength(8);
    expect(promotionWizardSource).not.toContain("<select");
  });
});
