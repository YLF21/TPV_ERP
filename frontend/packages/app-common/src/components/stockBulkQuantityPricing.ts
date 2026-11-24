import { isQuantityPricingEnabled, type QuantityPricing } from "../quantityPricing";
import type { StockBulkEditRowData } from "./stockBulkEdit";

export type QuantityPricingAction = "missing" | "replace" | "remove" | "enable" | "disable";

function decimalKey(price: string | number) {
  const [integer, fraction = ""] = String(price).replace(",", ".").split(".");
  return (integer.replace(/^0+(?=\d)/, "") || "0") + "." + fraction.replace(/0+$/, "");
}

export function equalQuantityPricing(a?: QuantityPricing, b?: QuantityPricing) {
  if (!a || !b) return !a && !b;
  return isQuantityPricingEnabled(a) === isQuantityPricingEnabled(b) &&
    a.mode === b.mode && a.tiers.length === b.tiers.length && a.tiers.every((tier, index) =>
    tier.quantity === b.tiers[index].quantity && decimalKey(tier.price) === decimalKey(b.tiers[index].price));
}

export function bulkQuantityPricingPreview(
  rows: StockBulkEditRowData[], action: QuantityPricingAction, rule: QuantityPricing, preserveEnabled = false
) {
  return rows.flatMap(row => {
    if (!row.selected || !row.product) return [];
    const before = row.draft.quantityPricing === undefined ? row.product.quantityPricing : row.draft.quantityPricing;
    // A legacy response without this field cannot be used to decide whether a rule exists.
    if (before === undefined) return [];
    let next: QuantityPricing = before;
    if (action === "remove") next = null;
    else if (action === "enable" && before) next = { ...before, enabled: true };
    else if (action === "disable" && before) next = { ...before, enabled: false };
    else if (rule && (action === "replace" || (action === "missing" && before === null))) {
      next = preserveEnabled ? { ...rule, enabled: isQuantityPricingEnabled(before) } : rule;
    }
    const change = !equalQuantityPricing(before, next);
    return [{
      id: row.id,
      basePrice: row.draft.salePrice ?? row.product.salePrice,
      before,
      after: change ? next : before,
      change
    }];
  });
}
