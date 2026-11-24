import type { KeyboardEventHandler } from "react";
import { quantityPricingMessages } from "../i18n/QuantityPricingMessages";
import type { QuantityPricing, QuantityPricingMode } from "../quantityPricing";
import type { LocaleCode } from "../types";
import "./StockBulkQuantityPricingCell.css";

export type StockBulkQuantityPricingField = "quantityRule" | "tierQuantity" | "tierPrice";

export type StockBulkQuantityPricingCellProps = {
  field: StockBulkQuantityPricingField;
  value: QuantityPricing | undefined;
  locale: LocaleCode;
  disabled: boolean;
  invalid: boolean;
  onChange: (rule: QuantityPricing) => void;
  onKeyDown: KeyboardEventHandler<HTMLElement>;
  quickField: string | null;
};

function draftQuantity(text: string): number {
  if (!/^\d+$/.test(text)) return 0;
  const value = Number(text);
  return Number.isSafeInteger(value) ? value : 0;
}

/** One grid cell; quantity and price use matching tier rows so their inputs stay aligned. */
export function StockBulkQuantityPricingCell({ field, value, locale, disabled, invalid, onChange, onKeyDown, quickField }: StockBulkQuantityPricingCellProps) {
  const words = quantityPricingMessages(locale);
  const locked = disabled || value === undefined;
  const tiers = value?.tiers ?? [];
  const inputProps = {
    "data-bulk-entry": true,
    "data-bulk-quick-field": field,
    disabled: locked,
    onKeyDown
  };

  function updateTier(index: number, change: { quantity?: number; price?: string }) {
    if (!value || locked) return;
    onChange({ ...value, tiers: value.tiers.map((tier, tierIndex) => tierIndex === index ? { ...tier, ...change } : tier) });
  }

  function removeTier(index: number) {
    if (!value || locked) return;
    const next = value.tiers.filter((_, tierIndex) => tierIndex !== index);
    onChange(next.length ? { ...value, tiers: next } : null);
  }

  const className = `bulk-edit-cell bulk-quantity-pricing-cell bulk-quantity-pricing-${field}${invalid ? " invalid" : ""}`;

  if (field === "quantityRule") {
    return <div className={className}>
      {value === undefined ? !disabled && <span className="bulk-quantity-pricing-unknown">{words.unknownState}</span> : <>
        <select {...inputProps} value={value?.mode ?? ""} aria-label={words.title} aria-invalid={invalid}
          onChange={event => {
            if (locked) return;
            const mode = event.target.value as QuantityPricingMode;
            if (value) onChange({ ...value, mode });
            else onChange({ mode, tiers: [{ quantity: 0, price: "" }] });
          }}>
          <option value="" disabled>{words.none}</option>
          <option value="PACK">{words.pack}</option>
          <option value="UNIT_THRESHOLD">{words.threshold}</option>
        </select>
        {value && <small className="bulk-quantity-pricing-state" data-enabled={value.enabled !== false}>
          {value.enabled === false ? words.notApplied : words.applied}
        </small>}
      </>}
    </div>;
  }

  return <div className={className}>
    {value === undefined ? null : value === null ?
      <span className="bulk-quantity-pricing-empty" aria-hidden="true">—</span> : <>
        <div className="bulk-quantity-pricing-tiers">
          {tiers.map((tier, index) => <div className="bulk-quantity-pricing-tier" key={index}>
            {field === "tierQuantity" ? <>
              <input {...inputProps} inputMode="numeric" type="text" value={tier.quantity === 0 ? "" : String(tier.quantity)}
                aria-label={`${words.quantity} ${index + 1}`} aria-invalid={invalid || tier.quantity === 0}
                onFocus={event => { if (quickField === field) event.currentTarget.select(); }}
                onClick={event => { if (quickField === field) event.currentTarget.select(); }}
                onChange={event => updateTier(index, { quantity: draftQuantity(event.target.value) })} />
              <button type="button" className="bulk-quantity-pricing-remove" disabled={locked}
                aria-label={`${words.remove} ${index + 1}`} title={`${words.remove} ${index + 1}`}
                onClick={() => removeTier(index)}>×</button>
            </> : <input {...inputProps} inputMode="decimal" type="text" value={String(tier.price)}
              aria-label={`${value.mode === "PACK" ? words.packPrice : words.unitPrice} ${index + 1}`} aria-invalid={invalid || String(tier.price).trim() === ""}
              onFocus={event => { if (quickField === field) event.currentTarget.select(); }}
              onClick={event => { if (quickField === field) event.currentTarget.select(); }}
              onChange={event => updateTier(index, { price: event.target.value })} />}
          </div>)}
        </div>
        {field === "tierQuantity" ? <button type="button" className="bulk-quantity-pricing-add" disabled={locked || tiers.length >= 100}
          title={tiers.length >= 100 ? words.tooMany : words.add}
          aria-label={words.add}
          onClick={() => value && onChange({ ...value, tiers: [...value.tiers, { quantity: 0, price: "" }] })}>+</button>
          : <div className="bulk-quantity-pricing-footer" aria-hidden="true" />}
      </>}
  </div>;
}
