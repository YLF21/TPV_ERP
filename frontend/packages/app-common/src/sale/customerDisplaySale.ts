import type { CustomerDisplayState } from "../hardware/hardware";

type Amount = number | string;

export type CustomerDisplayCartLine = {
  id: string;
  name: string;
  quantity: number;
};

export type CustomerDisplayQuoteLine = {
  lineId: string;
  lineType?: string;
  productId?: string | null;
  name: string;
  quantity: Amount;
  baseUnitPrice: Amount;
  baseSubtotal: Amount;
  commercialSubtotal: Amount;
  finalSubtotal: Amount;
  manualDiscount: Amount;
  memberDiscount: Amount;
  promotionDiscount: Amount;
  couponDiscount: Amount;
  documentDiscountAmount?: Amount;
  manualDiscountPercent: Amount;
};

export type CustomerDisplayQuote = {
  total: Amount;
  memberBalanceTotal?: Amount;
  lineBreakdown: CustomerDisplayQuoteLine[];
};

export type CustomerDisplaySaleInput = {
  lines: CustomerDisplayCartLine[];
  // The caller must supply only a quote confirmed for the current request key.
  quote: CustomerDisplayQuote | null;
  quoteError?: boolean;
  lastItemId?: string;
  payment?: { totalCents: number; changeCents?: number } | null;
  paymentLocked?: boolean;
  reservedTotalCents?: number | null;
};

function amount(value: unknown): number | null {
  if (value == null || value === "") return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function cents(value: unknown): number | null {
  const number = amount(value);
  return number == null ? null : Math.round(number * 100);
}

function sumCents(values: unknown[]): number | null {
  const parsed = values.map(cents);
  return parsed.some(value => value == null)
    ? null
    : parsed.reduce<number>((total, value) => total + value!, 0);
}

function isProduct(line: CustomerDisplayQuoteLine) {
  return line.lineType === "PRODUCT" || (!line.lineType && Boolean(line.productId));
}

export function customerDisplayLastItemId(
  previous: CustomerDisplayCartLine[],
  current: CustomerDisplayCartLine[],
  previousLastId?: string,
): string | undefined {
  const previousQuantities = new Map(previous.map(line => [line.id, line.quantity]));
  const changed = current.filter(line =>
    !previousQuantities.has(line.id) || line.quantity > previousQuantities.get(line.id)!,
  );
  return changed.at(-1)?.id
    ?? (current.some(line => line.id === previousLastId) ? previousLastId : current.at(-1)?.id);
}

export function createCustomerDisplaySaleState(input: CustomerDisplaySaleInput): CustomerDisplayState {
  const { lines, quote } = input;
  if (lines.length === 0) {
    if (input.payment && Number.isFinite(input.payment.totalCents)) {
      return {
        line1: "", line2: "", phase: "PAYMENT",
        payment: {
          total: input.payment.totalCents / 100,
          ...(Number.isFinite(input.payment.changeCents)
            ? { change: input.payment.changeCents! / 100 } : {}),
        },
      };
    }
    if (input.paymentLocked && input.reservedTotalCents != null
      && Number.isFinite(input.reservedTotalCents)) {
      return {
        line1: "", line2: "", phase: "PAYMENT",
        payment: { total: input.reservedTotalCents / 100, pending: true },
      };
    }
    return { line1: "", line2: "", phase: "IDLE" };
  }

  const total = cents(quote?.total);
  const finalSum = quote ? sumCents(quote.lineBreakdown.map(line => line.finalSubtotal)) : null;
  const products = quote?.lineBreakdown.filter(isProduct) ?? [];
  const usableQuote = quote && total != null && finalSum === total && products.length === lines.length;
  if (!usableQuote) {
    return {
      line1: "", line2: "", phase: "SALE",
      sale: {
        items: lines.map(line => ({ ...line, unitPrice: null, total: null, discount: null })),
        lastItemId: input.lastItemId,
        subtotal: null, discount: null, total: null,
        status: input.quoteError || quote ? "ERROR" : "PENDING",
      },
    };
  }

  const adjustments: NonNullable<NonNullable<CustomerDisplayState["sale"]>["adjustments"]> = [];
  const memberBalance = cents(quote.memberBalanceTotal ?? 0);
  if (memberBalance) adjustments.push({ kind: "MEMBER_BALANCE", amount: -memberBalance / 100 });
  let productIndex = 0;
  let subtotalCents = 0;
  let discountCents = 0;
  let breakdownValid = memberBalance != null;
  const items = quote.lineBreakdown.map(line => {
    const product = isProduct(line);
    const lineTotal = cents(line.finalSubtotal)!;
    const local = product ? lines[productIndex++] : undefined;
    const commercialDiscount = sumCents([line.manualDiscount, line.memberDiscount, line.promotionDiscount]);
    if (product) {
      const base = cents(line.baseSubtotal);
      const reductions = sumCents([
        line.manualDiscount, line.memberDiscount, line.promotionDiscount,
        line.couponDiscount, line.documentDiscountAmount ?? 0,
      ]);
      breakdownValid &&= base != null && reductions != null;
      subtotalCents += base ?? 0;
      discountCents += reductions ?? 0;
    } else if (line.lineType === "MANUAL_DISCOUNT") {
      // This discount is its own economic row, not allocated into product fields.
      discountCents -= lineTotal;
    } else {
      adjustments.push({
        kind: line.lineType === "RETURN_ADJUSTMENT" ? "RETURN_ADJUSTMENT" : "OTHER",
        amount: lineTotal / 100,
      });
    }
    return {
      id: local?.id ?? line.lineId,
      name: line.name || local?.name || "",
      quantity: amount(line.quantity) ?? 0,
      unitPrice: product ? amount(line.baseUnitPrice) : null,
      total: product ? amount(line.commercialSubtotal) : lineTotal / 100,
      discount: product && commercialDiscount != null ? commercialDiscount / 100 : null,
      // This percentage only describes the manual component; omit it when other
      // benefits coexist, so the display cannot mislabel an aggregate discount.
      discountPercent: product && commercialDiscount === cents(line.manualDiscount)
        ? amount(line.manualDiscountPercent) : null,
    };
  });
  const adjustmentCents = sumCents(adjustments.map(adjustment => adjustment.amount)) ?? 0;
  const reconciles = breakdownValid && subtotalCents - discountCents + adjustmentCents === total;
  return {
    line1: "", line2: "", phase: "SALE",
    sale: {
      items, lastItemId: input.lastItemId,
      subtotal: reconciles ? subtotalCents / 100 : null,
      discount: reconciles ? discountCents / 100 : null,
      total: total / 100,
      status: "READY",
      adjustments,
    },
  };
}
