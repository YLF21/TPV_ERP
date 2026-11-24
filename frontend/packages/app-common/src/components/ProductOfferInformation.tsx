import { useId, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import { quantityPricingMessages } from "../i18n/QuantityPricingMessages";
import { formatQuantityPrice, isQuantityPricingEnabled, type QuantityPricing } from "../quantityPricing";
import type { AppKind, LocaleCode } from "../types";
import type { StockInventoryRow } from "./StockScreen";
import "./ProductOfferInformation.css";

type Props = {
  product: StockInventoryRow;
  app: AppKind;
  locale: LocaleCode;
  children: ReactNode;
};

export function ProductOfferInformation({ product, app, locale, children }: Props) {
  const t = createTranslator(locale);
  const offerTitle = t("stock.detail.offer");
  const rule = product.quantityPricing;
  const locked = (product.backendDiscountType || product.discountType) === "NONE";
  const enabled = isQuantityPricingEnabled(rule);
  const availableAtCheckout = enabled && !locked && product.active !== "common.no"
    && product.code !== "0" && Number(String(product.salePrice).replace(",", ".")) > 0;
  if (!rule?.tiers.length || app === "pda" || (app === "venta" && !availableAtCheckout)) {
    return <><h3>{offerTitle}</h3>{children}</>;
  }

  const words = quantityPricingMessages(locale);
  const status = !enabled ? words.notApplied : locked ? words.blocked : undefined;
  return (
    <OfferTabs key={product.productId} rule={rule} locale={locale} offerTitle={offerTitle} status={status}>
      {children}
    </OfferTabs>
  );
}

function OfferTabs({ rule, locale, offerTitle, status, children }: {
  rule: NonNullable<QuantityPricing>;
  locale: LocaleCode;
  offerTitle: string;
  status?: string;
  children: ReactNode;
}) {
  const words = quantityPricingMessages(locale);
  const id = useId();
  const [active, setActive] = useState<"offer" | "quantity">("offer");
  const offerButton = useRef<HTMLButtonElement>(null);
  const quantityButton = useRef<HTMLButtonElement>(null);
  const isPack = rule.mode === "PACK";
  const title = isPack ? words.pack : words.threshold;

  function navigate(event: KeyboardEvent<HTMLDivElement>) {
    let next: typeof active;
    switch (event.key) {
      case "Home": next = "offer"; break;
      case "End": next = "quantity"; break;
      case "ArrowLeft":
      case "ArrowRight": next = active === "offer" ? "quantity" : "offer"; break;
      default: return;
    }
    event.preventDefault();
    event.stopPropagation();
    setActive(next);
    (next === "offer" ? offerButton : quantityButton).current?.focus({ preventScroll: true });
  }

  return (
    <>
      <div className="product-offer-tabs" role="tablist" aria-label={createTranslator(locale)("stock.detail.prices")} onKeyDown={navigate}>
        {(["offer", "quantity"] as const).map((tab) => (
          <button type="button" role="tab" key={tab} id={id + "-tab-" + tab}
            ref={tab === "offer" ? offerButton : quantityButton}
            aria-controls={id + "-panel-" + tab} aria-selected={active === tab}
            tabIndex={active === tab ? 0 : -1} onClick={() => setActive(tab)}>
            {tab === "offer" ? offerTitle : words.infoTab}
          </button>
        ))}
      </div>
      <div className="product-offer-panels">
        <div className="product-offer-panel" role="tabpanel" id={id + "-panel-offer"}
          aria-labelledby={id + "-tab-offer"} aria-hidden={active !== "offer"} inert={active !== "offer"}>
          {children}
        </div>
        <div className="product-offer-panel product-quantity-panel" role="tabpanel" id={id + "-panel-quantity"}
          aria-labelledby={id + "-tab-quantity"} aria-hidden={active !== "quantity"} inert={active !== "quantity"}>
          <h4>{title}</h4>
          {status && <p className="product-quantity-status">{status}</p>}
          <div className="product-quantity-tiers" tabIndex={active === "quantity" ? 0 : -1}
            role="region" aria-label={title}>
            <table aria-label={title}>
              <thead><tr>
                <th scope="col">{isPack ? words.perPack : words.fromQuantity}</th>
                <th scope="col">{isPack ? words.packPrice : words.unitPrice}</th>
              </tr></thead>
              <tbody>{[...rule.tiers].sort((a, b) => a.quantity - b.quantity).map((tier) => (
                <tr key={tier.quantity}>
                  <td>{isPack ? tier.quantity.toLocaleString(locale) : words.unitCount(tier.quantity)}</td>
                  <td>{formatQuantityPrice(tier.price, locale)}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
          <p>{isPack ? words.looseUnitHint : words.thresholdHelp}</p>
        </div>
      </div>
    </>
  );
}
