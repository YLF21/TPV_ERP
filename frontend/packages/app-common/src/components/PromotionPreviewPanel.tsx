import type { LocaleCode } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";

export type PromotionPreview = {
  appliedPromotions: PromotionPreviewAppliedPromotion[];
  usedCoupon?: PromotionPreviewUsedCoupon | null;
  generatedCoupon?: PromotionPreviewGeneratedCoupon | null;
};

export type PromotionPreviewAppliedPromotion = {
  id?: string | number;
  name: string;
  discountAmount?: string | number | null;
};

export type PromotionPreviewUsedCoupon = {
  code: string;
  amount?: string | number | null;
};

export type PromotionPreviewGeneratedCoupon = {
  code: string;
  amount?: string | number | null;
  validFrom?: string | null;
  validUntil?: string | null;
};

type PromotionPreviewPanelProps = {
  locale: LocaleCode;
  preview?: PromotionPreview | null;
};

export function PromotionPreviewPanel({ locale, preview }: PromotionPreviewPanelProps) {
  const t = createTranslator(locale);
  const appliedPromotions = preview?.appliedPromotions ?? [];

  return (
    <section className="promotion-preview-panel" aria-label={t("promotion.preview.title")}>
      <header className="promotion-preview-heading">
        <h3>{t("promotion.preview.title")}</h3>
        <span>
          {appliedPromotions.length > 0
            ? t("promotion.preview.appliedCount").replace("{count}", String(appliedPromotions.length))
            : t("promotion.preview.empty")}
        </span>
      </header>

      {appliedPromotions.length > 0 ? (
        <div className="promotion-preview-list">
          {appliedPromotions.map((promotion, index) => (
            <article className="promotion-preview-row" key={promotion.id ?? `${promotion.name}-${index}`}>
              <strong>{promotion.name}</strong>
              {promotion.discountAmount != null && <span>{formatPreviewAmount(promotion.discountAmount)}</span>}
            </article>
          ))}
        </div>
      ) : (
        <p className="promotion-preview-empty">{t("promotion.preview.emptyDetail")}</p>
      )}

      {(preview?.usedCoupon || preview?.generatedCoupon) && (
        <div className="promotion-preview-coupons">
          {preview.usedCoupon && (
            <article>
              <span>{t("promotion.preview.usedCoupon")}</span>
              <strong>{preview.usedCoupon.code}</strong>
              {preview.usedCoupon.amount != null && <b>{formatPreviewAmount(preview.usedCoupon.amount)}</b>}
            </article>
          )}

          {preview.generatedCoupon && (
            <article>
              <span>{t("promotion.preview.generatedCoupon")}</span>
              <strong>{preview.generatedCoupon.code}</strong>
              {preview.generatedCoupon.amount != null && <b>{formatPreviewAmount(preview.generatedCoupon.amount)}</b>}
              {formatValidity(preview.generatedCoupon) && <small>{formatValidity(preview.generatedCoupon)}</small>}
            </article>
          )}
        </div>
      )}
    </section>
  );
}

function formatPreviewAmount(amount: string | number) {
  return typeof amount === "number" ? amount.toFixed(2).replace(".", ",") : amount;
}

function formatValidity(coupon: PromotionPreviewGeneratedCoupon) {
  if (coupon.validFrom && coupon.validUntil) {
    return `${coupon.validFrom} - ${coupon.validUntil}`;
  }

  return coupon.validFrom ?? coupon.validUntil ?? "";
}
