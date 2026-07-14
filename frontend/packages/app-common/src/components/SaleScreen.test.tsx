// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  SaleScreen,
  addSaleLine,
  applyMemberDiscounts,
  cashPaymentErrorTransition,
  cashPaymentSuccessTransition,
  cashResultFromFinalization,
  finishCashPaymentResult,
  readCashModeForOpening,
  runGuardedCashSubmission,
  resolveCardPaymentOutcome,
  cardRetryCheckoutId,
  cardTransportFailureOutcome,
  buildCardChargeBody,
  runGuardedCardOpening,
  effectiveSaleLineDiscount,
  effectiveSaleProductPrice,
  filterSaleCustomers,
  filterSaleProducts,
  removeSaleLine,
  resolveCashPaymentResult,
  selectedProductAfterRemoval,
  saleLineSubtotal,
  saleDisplayedTotal,
  saleOfferIsCurrent,
  saleProductBlocksManualDiscount,
  saleTotal,
  selectSaleProduct,
  updateSaleLineDiscount,
  updateSaleLineQuantity,
  type SaleCustomer,
  type SaleProduct
} from "./SaleScreen";
import type { TerminalContext, UserSession } from "../types";

const { prepareApplicationClose, prepareLogout, checkoutHandle, checkoutProps } = vi.hoisted(() => ({
  prepareApplicationClose: vi.fn(),
  prepareLogout: vi.fn(),
  checkoutHandle: { attached: true },
  checkoutProps: { current: null as null | { testCashEnabled?: boolean } }
}));

vi.mock("./SalePaymentCheckout", async () => {
  const { forwardRef, useImperativeHandle } = await import("react");
  return {
    SalePaymentCheckout: forwardRef(function MockSalePaymentCheckout(props, ref) {
      checkoutProps.current = props;
      useImperativeHandle(ref, () => checkoutHandle.attached ? ({ prepareApplicationClose, prepareLogout }) : null);
      return null;
    })
  };
});

afterEach(() => {
  cleanup();
  prepareApplicationClose.mockReset();
  prepareLogout.mockReset();
  checkoutHandle.attached = true;
  checkoutProps.current = null;
  delete window.tpvDesktop;
});

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"]
};

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01"
};

const products: SaleProduct[] = [
  { id: "coffee", code: "CAF-001", barcode: "8410000000011", barcode2: "ALT-CAFE", name: "Cafe molido", salePrice: 10 },
  { id: "bread", code: "PAN-001", barcode: "8410000000028", name: "Pan integral", salePrice: "2.50" },
  { id: "milk", code: "LEC-001", barcode: "8410000000035", name: "Leche fresca", salePrice: 1.75 }
];

const memberDiscountProduct: SaleProduct = {
  id: "member-coffee",
  code: "MEM-CAFE",
  name: "Cafe socio",
  salePrice: 10,
  discountType: "MEMBER_DISCOUNT"
};

const customers: SaleCustomer[] = [
  { id: "customer-1", clientId: "C-001", fiscalName: "Cliente Pruebas SL", documentNumber: "B11111111" },
  { id: "customer-2", clientId: "C-002", fiscalName: "Maria Lopez", documentNumber: "12345678Z" }
];

describe("SaleScreen", () => {
  function renderSaleScreen(onLogout = vi.fn()) {
    render(
      <SaleScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={onLogout}
      />
    );
    return onLogout;
  }

  function logoutButton() {
    fireEvent.click(screen.getByRole("button", { name: "ADMIN" }));
    return screen.getByRole("menuitem", { name: "Cerrar usuario" });
  }

  function confirmShutdown() {
    fireEvent.click(screen.getByRole("button", { name: "Apagar" }));
    fireEvent.click(screen.getByRole("button", { name: "Sí" }));
  }

  it("enables test cash for APP VENTA only in Vite development", async () => {
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current).not.toBeNull());
    expect(checkoutProps.current?.testCashEnabled).toBe(import.meta.env.DEV);
  });

  it("never enables test cash when SaleScreen is wired for APP GESTION", async () => {
    render(
      <SaleScreen
        app="gestion"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
      />
    );

    await waitFor(() => expect(checkoutProps.current).not.toBeNull());
    expect(checkoutProps.current?.testCashEnabled).toBe(false);
  });

  it("closes the application only after payment checkout is ready", async () => {
    let resolvePreparation!: (result: "READY") => void;
    prepareApplicationClose.mockImplementation(() => new Promise((resolve) => {
      resolvePreparation = resolve;
    }));
    const closeApplication = vi.fn().mockResolvedValue(undefined);
    window.tpvDesktop = { closeApplication };
    renderSaleScreen();

    confirmShutdown();

    expect(prepareApplicationClose).toHaveBeenCalledTimes(1);
    expect(closeApplication).not.toHaveBeenCalled();
    resolvePreparation("READY");
    await waitFor(() => expect(closeApplication).toHaveBeenCalledTimes(1));
  });

  it.each([
    ["BLOCKED", vi.fn().mockResolvedValue("BLOCKED")],
    ["rejection", vi.fn().mockRejectedValue(new Error("cleanup failed"))]
  ])("keeps the application open after shutdown preparation %s", async (_label, implementation) => {
    prepareApplicationClose.mockImplementation(implementation);
    const closeApplication = vi.fn().mockResolvedValue(undefined);
    window.tpvDesktop = { closeApplication };
    renderSaleScreen();

    confirmShutdown();

    await waitFor(() => expect(prepareApplicationClose).toHaveBeenCalledTimes(1));
    expect(closeApplication).not.toHaveBeenCalled();
  });

  it("fails closed when payment checkout has not attached its handle", async () => {
    checkoutHandle.attached = false;
    const closeApplication = vi.fn().mockResolvedValue(undefined);
    window.tpvDesktop = { closeApplication };
    renderSaleScreen();

    confirmShutdown();

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(prepareApplicationClose).not.toHaveBeenCalled();
    expect(closeApplication).not.toHaveBeenCalled();
  });

  it("logs out only after payment checkout is ready", async () => {
    prepareLogout.mockResolvedValue("READY");
    const onLogout = renderSaleScreen();

    fireEvent.click(logoutButton());

    await waitFor(() => expect(prepareLogout).toHaveBeenCalledTimes(1));
    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it("does not log out when payment checkout blocks it", async () => {
    prepareLogout.mockResolvedValue("BLOCKED");
    const onLogout = renderSaleScreen();

    fireEvent.click(logoutButton());

    await waitFor(() => expect(prepareLogout).toHaveBeenCalledTimes(1));
    expect(onLogout).not.toHaveBeenCalled();
  });

  it("does not log out when payment checkout preparation rejects", async () => {
    prepareLogout.mockRejectedValue(new Error("cleanup failed"));
    const onLogout = renderSaleScreen();

    fireEvent.click(logoutButton());

    await waitFor(() => expect(prepareLogout).toHaveBeenCalledTimes(1));
    expect(onLogout).not.toHaveBeenCalled();
  });

  it("ignores a second logout click while payment preparation is pending", async () => {
    let resolvePreparation!: (result: "READY") => void;
    prepareLogout.mockImplementation(() => new Promise((resolve) => {
      resolvePreparation = resolve;
    }));
    const onLogout = renderSaleScreen();
    fireEvent.click(logoutButton());
    fireEvent.click(logoutButton());

    expect(onLogout).not.toHaveBeenCalled();
    resolvePreparation("READY");

    await waitFor(() => expect(onLogout).toHaveBeenCalledTimes(1));
    expect(prepareLogout).toHaveBeenCalledTimes(1);
  });

  it("preserves cash received by individual checkout and falls back to the total", () => {
    expect(cashResultFromFinalization("T-1", 1210, 2000)).toEqual({ ticketNumber: "T-1", totalCents: 1210, receivedCents: 2000 });
    expect(cashResultFromFinalization("T-2", 1210)).toEqual({ ticketNumber: "T-2", totalCents: 1210, receivedCents: 1210 });
  });
  it("shows the authoritative reserved total when a recovered payment locks an empty local cart", () => {
    expect(saleDisplayedTotal(0, true, 0, 1210)).toBe(12.1);
    expect(saleDisplayedTotal(5, false, 0, 1210)).toBe(5);
  });
  it("renders the sales workspace with shared frame controls", () => {
    const html = renderToStaticMarkup(
      <SaleScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
      />
    );

    expect(html).toContain('class="sale-screen work-screen keyboard-mode"');
    expect(html).toContain('class="report-user-button"');
    expect(html).toContain('class="language-button"');
    expect(html).toContain('class="shutdown-button"');
    expect(html).toContain('class="report-footer-context"');
    expect(html).toContain("Venta");
    expect(html).toContain("Añadir producto");
    expect(html).toContain("Lineas de venta");
    expect(html).toContain("Cobro");
    expect(html).toContain("F5");
    expect(html).toContain("F10");
    expect(html).toContain("Sin venta iniciada");
    expect(html).toContain('aria-label="Buscar producto"');
    expect(html).toContain('aria-controls="sale-product-results"');
    expect(html).toContain("Cargando productos");
    expect(html).not.toContain("Cafe molido");
    expect(html).not.toContain("Pan integral");
    expect(html).not.toContain("Leche fresca");
    expect(html).not.toContain("15,15");
  });

  it("filters products by name without case sensitivity", () => {
    expect(filterSaleProducts(products, "  CAFE ").map((product) => product.id)).toEqual(["coffee"]);
  });

  it("filters products by internal code or barcode", () => {
    expect(filterSaleProducts(products, "PAN-0").map((product) => product.id)).toEqual(["bread"]);
    expect(filterSaleProducts(products, "0000000011").map((product) => product.id)).toEqual(["coffee"]);
    expect(filterSaleProducts(products, "alt-cafe").map((product) => product.id)).toEqual(["coffee"]);
  });

  it("limits visible search results", () => {
    const manyProducts = Array.from({ length: 12 }, (_, index) => ({
      id: String(index),
      code: `CODE-${index}`,
      name: `Product ${index}`,
      salePrice: index
    }));

    expect(filterSaleProducts(manyProducts, "product")).toHaveLength(10);
  });

  it("prioritizes an exact code or barcode when selecting with Enter", () => {
    const ambiguous: SaleProduct[] = [
      ...products,
      { id: "code-in-name", code: "OTHER", name: "Accessory CAF-001", salePrice: 3 }
    ];

    expect(selectSaleProduct(ambiguous, "caf-001")?.id).toBe("coffee");
    expect(selectSaleProduct(products, "8410000000028")?.id).toBe("bread");
    expect(selectSaleProduct(products, "alt-cafe")?.id).toBe("coffee");
  });

  it("selects the only partial match with Enter", () => {
    expect(selectSaleProduct(products, "leche")?.id).toBe("milk");
    expect(selectSaleProduct(products, "00")).toBeUndefined();
  });

  it("adds products, increments repeated quantities and calculates the total", () => {
    const first = addSaleLine([], products[0]);
    const repeated = addSaleLine(first, products[0]);
    const completed = addSaleLine(repeated, products[1]);

    expect(completed).toEqual([
      { product: products[0], quantity: 2, discountPercent: 0 },
      { product: products[1], quantity: 1, discountPercent: 0 }
    ]);
    expect(saleTotal(completed)).toBe(22.5);
  });

  it("displays the configured member or valid offer price and falls back after expiry", () => {
    expect(effectiveSaleProductPrice({
      id: "member",
      salePrice: 10,
      memberPrice: 8.5,
      priceUseMode: "MEMBER_PRICE"
    }, "2026-07-11")).toBe(8.5);

    const offered: SaleProduct = {
      id: "offer",
      salePrice: 10,
      offerPrice: 7.5,
      priceUseMode: "OFFER_PRICE",
      offerActive: true,
      offerFrom: "2026-07-01",
      offerUntil: "2026-07-31"
    };
    expect(saleOfferIsCurrent(offered, "2026-07-11")).toBe(true);
    expect(effectiveSaleProductPrice(offered, "2026-07-11")).toBe(7.5);
    expect(effectiveSaleProductPrice(offered, "2026-08-01")).toBe(10);
    expect(effectiveSaleProductPrice({
      ...offered,
      offerPrice: null,
      offerDiscountPercent: 25,
      priceUseMode: "OFFER_DISCOUNT"
    }, "2026-07-11")).toBe(7.5);
    expect(effectiveSaleProductPrice({ ...offered, offerActive: false }, "2026-07-11")).toBe(10);
  });

  it("updates quantity only with valid integer values", () => {
    const lines = addSaleLine([], products[0]);

    expect(updateSaleLineQuantity(lines, "coffee", 4)[0].quantity).toBe(4);
    expect(() => updateSaleLineQuantity(lines, "coffee", 0)).toThrow("invalid_quantity");
    expect(() => updateSaleLineQuantity(lines, "coffee", 1.5)).toThrow("invalid_quantity");
  });

  it("applies a line discount and recalculates subtotal and total", () => {
    const lines = updateSaleLineQuantity(addSaleLine([], products[0]), "coffee", 2);
    const discounted = updateSaleLineDiscount(lines, "coffee", 25);

    expect(discounted[0].discountPercent).toBe(25);
    expect(saleLineSubtotal(discounted[0])).toBe(15);
    expect(saleTotal(discounted)).toBe(15);
    expect(() => updateSaleLineDiscount(lines, "coffee", 101)).toThrow("invalid_discount");
    expect(() => updateSaleLineDiscount(lines, "coffee", 12.345)).toThrow("invalid_discount");
  });

  it("blocks manual discounts when the backend discount type is NONE", () => {
    const blockedProduct: SaleProduct = { ...products[0], discountType: "NONE" };
    const lines = addSaleLine([], blockedProduct);

    expect(saleProductBlocksManualDiscount(blockedProduct)).toBe(true);
    expect(() => updateSaleLineDiscount(lines, "coffee", 10)).toThrow("discount_blocked");
    expect(updateSaleLineDiscount(lines, "coffee", 0)[0].discountPercent).toBe(0);
  });

  it("keeps the next available line selected after removal", () => {
    const lines = [
      { product: products[0], quantity: 1, discountPercent: 0 },
      { product: products[1], quantity: 1, discountPercent: 0 },
      { product: products[2], quantity: 1, discountPercent: 0 }
    ];

    expect(selectedProductAfterRemoval(lines, "bread")).toBe("milk");
    expect(selectedProductAfterRemoval(lines, "milk")).toBe("bread");
  });

  it("does not increment a line above the maximum quantity", () => {
    const lines = [{ product: products[0], quantity: 9999, discountPercent: 0 }];
    expect(addSaleLine(lines, products[0])[0].quantity).toBe(9999);
  });

  it("removes only the requested sale line", () => {
    const lines = addSaleLine(addSaleLine([], products[0]), products[1]);

    expect(removeSaleLine(lines, "coffee")).toEqual([{ product: products[1], quantity: 1, discountPercent: 0 }]);
  });

  it("filters customers by name, document or client code", () => {
    expect(filterSaleCustomers(customers, "pruebas").map((customer) => customer.id)).toEqual(["customer-1"]);
    expect(filterSaleCustomers(customers, "12345678").map((customer) => customer.id)).toEqual(["customer-2"]);
    expect(filterSaleCustomers(customers, "c-002").map((customer) => customer.id)).toEqual(["customer-2"]);
  });

  it("applies the bronze member discount only to MEMBER_DISCOUNT products", () => {
    const lines = addSaleLine(addSaleLine([], memberDiscountProduct), products[0]);
    const bronze: SaleCustomer = {
      id: "bronze",
      fiscalName: "Cliente Bronce",
      activeMember: true,
      memberCategoryName: "Bronce",
      memberDiscountPercent: 5
    };

    const discounted = applyMemberDiscounts(lines, bronze);

    expect(discounted[0].memberDiscountPercent).toBe(5);
    expect(effectiveSaleLineDiscount(discounted[0])).toBe(5);
    expect(discounted[1].memberDiscountPercent).toBe(0);
  });

  it("preserves a greater manual discount when a member is selected or removed", () => {
    const manuallyDiscounted = updateSaleLineDiscount(addSaleLine([], memberDiscountProduct), "member-coffee", 8);
    const bronze: SaleCustomer = { id: "bronze", activeMember: true, memberDiscountPercent: 5 };

    const withMember = applyMemberDiscounts(manuallyDiscounted, bronze);
    const withoutMember = applyMemberDiscounts(withMember, null);

    expect(effectiveSaleLineDiscount(withMember[0])).toBe(8);
    expect(withMember[0].memberDiscountPercent).toBe(5);
    expect(effectiveSaleLineDiscount(withoutMember[0])).toBe(8);
    expect(withoutMember[0].memberDiscountPercent).toBe(0);
  });

  it("applies member discount to a product added after selecting the customer", () => {
    const bronze: SaleCustomer = { id: "bronze", activeMember: true, memberDiscountPercent: 5 };
    const added = applyMemberDiscounts(addSaleLine([], memberDiscountProduct), bronze);

    expect(saleTotal(added)).toBe(9.5);
  });

  it("uses confirmed server amounts in the cash payment result", () => {
    expect(resolveCashPaymentResult(
      { number: "T-42", total: "12.34", received: "50.00", change: "7.66" },
      1230,
      2000
    )).toEqual({
      ticketNumber: "T-42",
      totalCents: 1234,
      receivedCents: 2000,
      changeCents: 766
    });
  });

  it("reads the current cash mode on every opening", () => {
    let value = "touch";
    const storage = { getItem: vi.fn(() => value) } as unknown as Storage;

    expect(readCashModeForOpening(storage)).toBe("touch");
    value = "keyboard";
    expect(readCashModeForOpening(storage)).toBe("keyboard");
    expect(storage.getItem).toHaveBeenCalledTimes(2);
  });

  it("transitions a successful payment to a clean sale with a result", () => {
    const result = { ticketNumber: "T-44", totalCents: 1200, receivedCents: 2000, changeCents: 800 };
    expect(cashPaymentSuccessTransition(result)).toEqual({
      cashDialogOpen: false,
      cashResult: result,
      lines: [],
      selectedProductId: null,
      selectedCustomer: null,
      query: ""
    });
  });

  it("keeps the sale snapshot and dialog on a payment error", () => {
    const snapshot = { cashDialogOpen: true, lines: [{ id: "line" }], selectedProductId: "coffee" };
    expect(cashPaymentErrorTransition(snapshot, "Servidor no disponible")).toEqual({
      ...snapshot,
      cashError: "Servidor no disponible"
    });
  });

  it("finishes the result by clearing it and restoring search focus", () => {
    const clear = vi.fn();
    const focus = vi.fn();
    finishCashPaymentResult(clear, focus);
    expect(clear).toHaveBeenCalledWith(null);
    expect(focus).toHaveBeenCalledOnce();
  });

  it("allows only one immediate cash submission until the first settles", async () => {
    const guard = { current: false };
    let release!: () => void;
    const pending = new Promise<void>((resolve) => { release = resolve; });
    const request = vi.fn(() => pending);

    const first = runGuardedCashSubmission(guard, request);
    const second = runGuardedCashSubmission(guard, request);
    expect(request).toHaveBeenCalledOnce();
    expect(await second).toBe(false);
    release();
    expect(await first).toBe(true);
    expect(guard.current).toBe(false);
  });

  it("falls back safely to the quote and sent cash when optional server amounts are absent", () => {
    expect(resolveCashPaymentResult(
      { number: "T-43", change: "7.70" },
      1230,
      2000
    )).toEqual({
      ticketNumber: "T-43",
      totalCents: 1230,
      receivedCents: 2000,
      changeCents: 770
    });
  });

  it("clears the sale only when a card payment is approved", () => {
    expect(resolveCardPaymentOutcome({ status: "APPROVED", ticketNumber: "T-9", total: "12.34", authorization: "AUTH-1" }, 1200).clearSale).toBe(true);
    expect(resolveCardPaymentOutcome({ status: "DECLINED", message: "Denegada" }, 1200)).toMatchObject({ clearSale: false, retryable: true });
  });

  it("does not offer a new checkout after an uncertain timeout", () => {
    expect(resolveCardPaymentOutcome({ status: "TIMEOUT", message: "Sin respuesta" }, 1200)).toMatchObject({ clearSale: false, retryable: false, uncertain: true });
    expect(cardRetryCheckoutId("TIMEOUT", () => "new-id")).toBeNull();
    expect(cardRetryCheckoutId("DECLINED", () => "new-id")).toBe("new-id");
    expect(cardRetryCheckoutId("ERROR", () => "new-id")).toBe("new-id");
    expect(cardRetryCheckoutId("CANCELLED", () => "new-id")).toBe("new-id");
  });

  it("retains the checkout and request body after a transport failure", () => {
    expect(cardTransportFailureOutcome("checkout-1", "Sin conexión")).toMatchObject({ status: "UNCERTAIN", checkoutId: "checkout-1", uncertain: true });
    expect(buildCardChargeBody("checkout-1", { customerId: null, lines: [] }, 1234)).toEqual({ checkoutId: "checkout-1", sale: { customerId: null, lines: [] }, quotedTotal: "12.34" });
  });

  it("guards quote and initial charge as one synchronous card opening", async () => {
    const guard = { current: false, generation: 0 };
    let release!: () => void;
    const pending = new Promise<void>((resolve) => { release = resolve; });
    const quoteAndCharge = vi.fn(async () => pending);
    const first = runGuardedCardOpening(guard, quoteAndCharge);
    const second = runGuardedCardOpening(guard, quoteAndCharge);
    expect(quoteAndCharge).toHaveBeenCalledOnce();
    expect(await second).toBe(false);
    release();
    expect(await first).toBe(true);
    expect(guard.current).toBe(false);
  });

  it("releases a failed opening and ignores stale completion tokens", async () => {
    const guard = { current: false, generation: 0 };
    await expect(runGuardedCardOpening(guard, async () => { throw new Error("quote failed"); })).rejects.toThrow("quote failed");
    expect(guard.current).toBe(false);
    expect(await runGuardedCardOpening(guard, async (opening) => {
      guard.generation += 1;
      expect(opening.isCurrent()).toBe(false);
    })).toBe(true);
  });
});
