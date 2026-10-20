import { describe, expect, it } from "vitest";
import {
  createCustomerDisplaySaleState,
  customerDisplayLastItemId,
  type CustomerDisplayQuoteLine,
} from "./customerDisplaySale";

const cart = [{ id: "cart-a", name: "Artículo", quantity: 2 }];
const product: CustomerDisplayQuoteLine = {
  lineId: "product:p:1", lineType: "PRODUCT", productId: "p",
  name: "Nombre confirmado", quantity: "2", baseUnitPrice: "10",
  baseSubtotal: "20", commercialSubtotal: "17", finalSubtotal: "12",
  manualDiscount: "2", memberDiscount: "0", promotionDiscount: "1",
  couponDiscount: "1", documentDiscountAmount: "2", manualDiscountPercent: "10",
};

describe("customer display confirmed sale", () => {
  it("separates commercial discounts from member balance and reconciles the quote", () => {
    const result = createCustomerDisplaySaleState({
      lines: cart, lastItemId: "cart-a",
      quote: { total: "12", memberBalanceTotal: "2", lineBreakdown: [product] },
    });
    expect(result.sale).toMatchObject({
      status: "READY", subtotal: 20, discount: 6, total: 12,
      adjustments: [{ kind: "MEMBER_BALANCE", amount: -2 }],
      items: [{
        id: "cart-a", name: "Nombre confirmado", quantity: 2, total: 17,
        discount: 3, discountPercent: null,
      }],
    });
  });

  it("keeps global manual discounts and return adjustments as economic rows", () => {
    const manual = {
      ...product, lineId: "manual-discount", productId: null, lineType: "MANUAL_DISCOUNT",
      name: "DESCUENTO", quantity: 1, finalSubtotal: "-1", commercialSubtotal: "-1",
    };
    const adjustment = {
      ...manual, lineId: "return-adjustment", lineType: "RETURN_ADJUSTMENT",
      name: "Ajuste devolución", finalSubtotal: "0.5", commercialSubtotal: "0.5",
    };
    const result = createCustomerDisplaySaleState({
      lines: cart, quote: { total: "11.50", memberBalanceTotal: 2, lineBreakdown: [product, manual, adjustment] },
    });
    expect(result.sale).toMatchObject({
      subtotal: 20, discount: 7, total: 11.5,
      adjustments: [
        { kind: "MEMBER_BALANCE", amount: -2 },
        { kind: "RETURN_ADJUSTMENT", amount: 0.5 },
      ],
    });
    expect(result.sale?.items.map(item => item.name)).toEqual([
      "Nombre confirmado", "DESCUENTO", "Ajuste devolución",
    ]);
  });

  it("preserves negative return totals without fabricating a positive discount", () => {
    const returned = {
      ...product, quantity: -1, baseSubtotal: -10, commercialSubtotal: -10,
      finalSubtotal: -10, manualDiscount: 0, promotionDiscount: 0,
      couponDiscount: 0, documentDiscountAmount: 0,
    };
    const result = createCustomerDisplaySaleState({
      lines: [{ ...cart[0], quantity: -1 }], quote: { total: -10, lineBreakdown: [returned] },
    });
    expect(result.sale).toMatchObject({ total: -10, subtotal: -10, discount: 0 });
  });

  it("omits an unprovable breakdown while preserving the confirmed total", () => {
    const result = createCustomerDisplaySaleState({
      lines: cart,
      quote: { total: "12", lineBreakdown: [{ ...product, baseSubtotal: "999" }] },
    });
    expect(result.sale).toMatchObject({ total: 12, subtotal: null, discount: null, status: "READY" });
  });

  it.each([false, true])("does not reuse previous monetary values when no current quote exists (error=%s)", quoteError => {
    const result = createCustomerDisplaySaleState({ lines: cart, quote: null, quoteError });
    expect(result.sale).toMatchObject({
      subtotal: null, discount: null, total: null, status: quoteError ? "ERROR" : "PENDING",
      items: [{ id: "cart-a", quantity: 2, unitPrice: null, total: null, discount: null }],
    });
  });

  it("rejects a total mismatch and missing product lines", () => {
    for (const quote of [
      { total: 99, lineBreakdown: [product] },
      { total: 0, lineBreakdown: [] },
    ]) {
      expect(createCustomerDisplaySaleState({ lines: cart, quote }).sale)
        .toMatchObject({ total: null, status: "ERROR" });
    }
  });

  it("uses payment totals only after clearing the cart and never invents mixed payment change", () => {
    expect(createCustomerDisplaySaleState({
      lines: [], quote: null, payment: { totalCents: 1250, changeCents: 750 },
    }).payment).toEqual({ total: 12.5, change: 7.5 });
    expect(createCustomerDisplaySaleState({
      lines: [], quote: null, payment: { totalCents: 1250 },
    }).payment).toEqual({ total: 12.5 });
    expect(createCustomerDisplaySaleState({
      lines: cart, quote: null, payment: { totalCents: 1250 },
    }).phase).toBe("SALE");
  });

  it("distinguishes reserved payment from completed payment and idle", () => {
    expect(createCustomerDisplaySaleState({
      lines: [], quote: null, paymentLocked: true, reservedTotalCents: 550,
    })).toMatchObject({ phase: "PAYMENT", payment: { total: 5.5, pending: true } });
    expect(createCustomerDisplaySaleState({ lines: [], quote: null }).phase).toBe("IDLE");
  });

  it("tracks a rescanned existing row without following keyboard selection", () => {
    const previous = [...cart, { id: "cart-b", name: "B", quantity: 1 }];
    const current = [{ ...cart[0], quantity: 3 }, previous[1]];
    expect(customerDisplayLastItemId(previous, current, "cart-b")).toBe("cart-a");
    expect(customerDisplayLastItemId(current, current, "cart-a")).toBe("cart-a");
    expect(customerDisplayLastItemId(current, [current[1]], "cart-a")).toBe("cart-b");
    expect(customerDisplayLastItemId(current, [], "cart-a")).toBeUndefined();
  });
});
