import { describe, expect, it, vi } from "vitest";
import {
  advancePromotionWizardStep,
  createDefaultPromotionDraft,
  type PromotionWizardStep,
  promotionWizardSteps,
  submitPromotionDraft
} from "./PromotionWizard";

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
        buyQuantity: "3",
        payQuantity: "2",
        discountPercent: null
      }
    });
  });
});
