import type { LocaleCode } from "./types";
import { quantityPricingMessages } from "./i18n/QuantityPricingMessages";

export type QuantityPricingMode = "PACK" | "UNIT_THRESHOLD";
export type QuantityPricingTier = { quantity: number; price: number | string };
export type QuantityPricing = { mode: QuantityPricingMode; tiers: QuantityPricingTier[]; enabled?: boolean } | null;

/** The API expects ascending quantities and decimal points. Keep the editing order until submission. */
export function normalizeQuantityPricingTiers(rule: NonNullable<QuantityPricing>): NonNullable<QuantityPricing> {
  return { ...rule, tiers: [...rule.tiers].sort((a, b) => a.quantity - b.quantity).map(tier => ({
    quantity: tier.quantity,
    price: typeof tier.price === "string" ? tier.price.trim().replace(",", ".") : tier.price
  })) };
}

// Rules saved before activation was configurable applied whenever they were present.
export function isQuantityPricingEnabled(value?: QuantityPricing): boolean {
  return value != null && value.enabled !== false;
}

export function quantityPricingSummary(value: QuantityPricing, _basePrice: number | string, locale: LocaleCode): string {
  const words = quantityPricingMessages(locale);
  if (!value) return words.none;
  return `${value.mode === "PACK" ? words.summaryPack : words.summaryThreshold}: ${value.tiers.length} ${value.tiers.length === 1 ? words.summaryTier : words.summaryTiers}`;
}

/** Format decimal text without converting it to a binary floating point number. */
export function formatQuantityPrice(value: number | string, locale: LocaleCode): string {
  const raw = String(value).trim().replace(",", ".");
  const match = /^(\d+)(?:\.(\d+))?$/.exec(raw);
  if (!match) return `${raw} €`;
  const thousands = locale === "es" ? "." : ",";
  const decimal = locale === "es" ? "," : ".";
  const whole = BigInt(match[1]).toString().replace(/\B(?=(\d{3})+(?!\d))/g, thousands);
  const fraction = (match[2] ?? "").padEnd(2, "0");
  return `${whole}${decimal}${fraction} €`;
}

/** Prices use decimal text throughout; no binary floating point enters the simulation. */
function decimalParts(value: number | string): [bigint, number] | null {
  const text = String(value).trim().replace(",", ".");
  const match = /^(\d{1,17})(?:\.(\d{1,3}))?$/.exec(text);
  if (!match) return null;
  const fraction = match[2] ?? "";
  return [BigInt(match[1] + fraction), fraction.length];
}

function moneyUnits(value: number | string): bigint | null {
  const parsed = decimalParts(value);
  return parsed ? parsed[0] * 10n ** BigInt(3 - parsed[1]) : null;
}

function moneyText(units: bigint): string {
  const whole = units / 1000n;
  const fraction = (units % 1000n).toString().padStart(3, "0").replace(/0+$/, "");
  return fraction ? `${whole}.${fraction}` : `${whole}`;
}

function totalText(units: bigint): string {
  return moneyText(((units + 5n) / 10n) * 10n);
}

export type QuantityPricingIssue = "empty" | "tooMany" | "quantity" | "price" | "duplicate";

export function validateQuantityPricing(value: QuantityPricing): QuantityPricingIssue[] {
  if (!value) return [];
  if (!value.tiers.length) return ["empty"];
  const issues: QuantityPricingIssue[] = [];
  if (value.tiers.length > 100) issues.push("tooMany");
  const seen = new Set<number>();
  for (const tier of value.tiers) {
    if (!Number.isSafeInteger(tier.quantity) || tier.quantity < 1 || tier.quantity > 2147483647) issues.push("quantity");
    if (moneyUnits(tier.price) === null) issues.push("price");
    if (seen.has(tier.quantity)) issues.push("duplicate");
    seen.add(tier.quantity);
  }
  return [...new Set(issues)];
}

export type QuantityPricingSimulation = {
  total: string;
  unitPrice?: string;
  packs: { quantity: number; count: number }[];
  looseUnits: number;
};

/** PACK minimizes the full purchase cost over all pack combinations and loose units. */
export function simulateQuantityPricing(value: QuantityPricing, basePrice: number | string, quantity: number): QuantityPricingSimulation | null {
  const base = moneyUnits(basePrice);
  if (base === null || !Number.isSafeInteger(quantity) || quantity < 1 || quantity > 10000 || validateQuantityPricing(value).length) return null;
  if (!value || !isQuantityPricingEnabled(value)) return { total: totalText(base * BigInt(quantity)), packs: [], looseUnits: quantity };
  if (value.mode === "UNIT_THRESHOLD") {
    const tier = [...value.tiers].filter((item) => item.quantity <= quantity && moneyUnits(item.price)! < base)
      .sort((a, b) => {
        const left = moneyUnits(a.price)!;
        const right = moneyUnits(b.price)!;
        return left < right ? -1 : left > right ? 1 : b.quantity - a.quantity;
      })[0];
    const unit = tier ? moneyUnits(tier.price)! : base;
    return { total: totalText(unit * BigInt(quantity)), unitPrice: moneyText(unit), packs: [], looseUnits: 0 };
  }
  const tiers = value.tiers.map((tier) => ({ quantity: tier.quantity, price: moneyUnits(tier.price)! }));
  const costs: bigint[] = [0n];
  const choices: number[] = [0];
  for (let count = 1; count <= quantity; count++) {
    let best = costs[count - 1] + base;
    let choice = -1;
    tiers.forEach((tier, index) => {
      if (tier.quantity > count) return;
      const candidate = costs[count - tier.quantity] + tier.price;
      if (candidate < best) { best = candidate; choice = index; }
    });
    costs.push(best);
    choices.push(choice);
  }
  let remaining = quantity;
  let looseUnits = 0;
  const packCounts = new Map<number, number>();
  while (remaining > 0) {
    const choice = choices[remaining];
    if (choice < 0) { looseUnits++; remaining--; }
    else {
      const packSize = value.tiers[choice].quantity;
      packCounts.set(packSize, (packCounts.get(packSize) ?? 0) + 1);
      remaining -= packSize;
    }
  }
  return { total: totalText(costs[quantity]), packs: [...packCounts].sort((a, b) => a[0] - b[0]).map(([packSize, count]) => ({ quantity: packSize, count })), looseUnits };
}
