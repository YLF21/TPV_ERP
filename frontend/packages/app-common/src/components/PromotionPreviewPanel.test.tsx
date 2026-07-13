import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { PromotionPreviewPanel, type PromotionPreview } from "./PromotionPreviewPanel";

describe("PromotionPreviewPanel", () => {
  it("renders an applied promotion and generated coupon code", () => {
    const preview: PromotionPreview = {
      appliedPromotions: [
        {
          id: "promo-1",
          name: "Segunda unidad 50%",
          discountAmount: "2,50"
        }
      ],
      generatedCoupon: {
        code: "PROMO-123",
        amount: "5,00",
        validFrom: "2026-07-09",
        validUntil: "2026-07-31"
      }
    };

    const html = renderToStaticMarkup(<PromotionPreviewPanel locale="es" preview={preview} />);

    expect(html).toContain("Promociones");
    expect(html).toContain("Segunda unidad 50%");
    expect(html).toContain("2,50");
    expect(html).toContain("Cupón generado");
    expect(html).toContain("PROMO-123");
    expect(html).toContain("5,00");
    expect(html).toContain("2026-07-09 - 2026-07-31");
  });
});
