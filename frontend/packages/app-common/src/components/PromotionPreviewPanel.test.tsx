import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { PromotionPreviewPanel, type PromotionPreview } from "./PromotionPreviewPanel";

const tpvCss = readFileSync(resolve(process.cwd(), "packages/app-common/src/styles/tpv.css"), "utf8");

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

  it("limits the applied promotions area to three visible rows with vertical scrolling", () => {
    expect(tpvCss).toMatch(/\.promotion-preview-list\s*\{[^}]*max-height:\s*130px;[^}]*overflow-x:\s*hidden;[^}]*overflow-y:\s*auto;/s);
    expect(tpvCss).toMatch(/\.promotion-preview-row strong\s*\{[^}]*text-overflow:\s*ellipsis;[^}]*white-space:\s*nowrap;/s);
  });

  it("hides stale promotions while an authoritative recalculation is pending", () => {
    const html = renderToStaticMarkup(
      <PromotionPreviewPanel
        locale="es"
        preview={{
          appliedPromotions: [{ name: "Promoción obsoleta", discountAmount: "5,00" }],
          usedCoupon: { code: "CUPON-ANTERIOR", amount: "2,00" },
        }}
        status={{
          label: "Recalculando",
          detail: "Calculando el total y las promociones con las condiciones actuales…",
          kind: "LOADING",
        }}
      />,
    );

    expect(html).toContain("Recalculando");
    expect(html).toContain("Calculando el total y las promociones");
    expect(html).not.toContain("Promoción obsoleta");
    expect(html).not.toContain("CUPON-ANTERIOR");
  });
});
