// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { retryPrintSucceeded } from "../sale/printRetry";
import {
  SaleScreen,
  SaleDeletionControlSequence,
  addSaleLine,
  applyMemberDiscounts,
  cashPaymentErrorTransition,
  cashPaymentSuccessTransition,
  cashPaymentResultForAutomaticPrinting,
  updateCashResultPrintOutcome,
  cashResultFromFinalization,
  finishCashPaymentResult,
  readCashModeForOpening,
  runGuardedCashSubmission,
  resolveCardPaymentOutcome,
  cardRetryCheckoutId,
  cardTransportFailureOutcome,
  buildCardChargeBody,
  runGuardedCardOpening,
  saleMainMessage,
  saleMainProductCount,
  pendingSaleDraftForCustomer,
  saleSelectableProducts,
  effectiveSaleLineDiscount,
  effectiveSaleProductPrice,
  filterSaleCustomers,
  filterSaleProducts,
  removeSaleLine,
  resolveCashPaymentResult,
  saleLineSelectionAfterArrow,
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
import { createTranslator } from "../i18n/LocalizedMessages";
import type { TerminalContext, UserSession } from "../types";
import type { PaymentFinalizationSummary, SalePaymentCheckoutHandle } from "./SalePaymentCheckout";
import { defaultHardwareConfig } from "../hardware/hardware";
import type { ConfirmedTicketPrintSnapshot } from "../sale/ticketPrinting";
import { pendingSaleRecoveryKey, savePendingSaleRecovery } from "../sale/pendingSaleRecovery";

type CheckoutMockProps = {
  testCashEnabled?: boolean;
  disabled?: boolean;
  onCash?: () => void;
  onHydrationChange?: (hydrated: boolean) => void;
  onLockedChange?: (locked: boolean, reservedTotalCents?: number) => void;
  onFinalized: (printTicket: ConfirmedTicketPrintSnapshot, summary: PaymentFinalizationSummary) => void;
};

const { prepareApplicationClose, prepareLogout, triggerCash, triggerCard, triggerPending, checkoutHandle, checkoutProps } = vi.hoisted(() => ({
  prepareApplicationClose: vi.fn(),
  prepareLogout: vi.fn(),
  triggerCash: vi.fn(),
  triggerCard: vi.fn(),
  triggerPending: vi.fn(),
  checkoutHandle: { attached: true },
  checkoutProps: {
    current: null as CheckoutMockProps | null,
  }
}));

vi.mock("./SalePaymentCheckout", async () => {
  const { forwardRef, useEffect, useImperativeHandle } = await import("react");
  return {
    SalePaymentCheckout: forwardRef<SalePaymentCheckoutHandle, CheckoutMockProps>(function MockSalePaymentCheckout(props, ref) {
      checkoutProps.current = props;
      useEffect(() => { props.onHydrationChange?.(true); props.onLockedChange?.(false); }, []);
      useImperativeHandle(checkoutHandle.attached ? ref : null, () => ({
        prepareApplicationClose,
        prepareLogout,
        triggerCash,
        triggerCard,
        triggerPending,
      }) as unknown as SalePaymentCheckoutHandle);
      return <button type="button" disabled={props.disabled} onClick={props.onCash}>Efectivo <kbd>AvPág</kbd></button>;
    })
  };
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  prepareApplicationClose.mockReset();
  prepareLogout.mockReset();
  triggerCash.mockReset();
  triggerCard.mockReset();
  triggerPending.mockReset();
  checkoutHandle.attached = true;
  checkoutProps.current = null;
  localStorage.clear();
  delete window.tpvDesktop;
});

it("keeps sale print retry after two failures and clears only after success", async () => {
  const retry = vi.fn().mockResolvedValueOnce({ status: "FAILED" })
    .mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce({ status: "PRINTED" });
  expect(await retryPrintSucceeded(retry)).toBe(false);
  expect(await retryPrintSucceeded(retry)).toBe(false);
  expect(await retryPrintSucceeded(retry)).toBe(true);
});

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"],
  accessToken: "access-token"
};

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01"
};

const printSnapshot = (documentNumber: string): ConfirmedTicketPrintSnapshot => ({
  documentId: `document-${documentNumber}`,
  documentNumber,
  issuedAt: "2026-07-15T12:00:00.000Z",
  lines: [],
  payments: [],
  total: "12.10",
});

function installTicketHardware(printTicket: ReturnType<typeof vi.fn>) {
  window.tpvDesktop = {
    closeApplication: vi.fn().mockResolvedValue(undefined),
    hardware: {
      getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig),
      printTicket,
    } as never,
  };
}

const products: SaleProduct[] = [
  { id: "coffee", code: "CAF-001", barcode: "8410000000011", barcode2: "ALT-CAFE", name: "Cafe molido", salePrice: 10, taxId: "tax-iva-21", taxesIncluded: true, taxPercentage: 21, taxRegime: "IVA" },
  { id: "bread", code: "PAN-001", barcode: "8410000000028", name: "Pan integral", salePrice: "2.50", taxId: "tax-iva-21", taxesIncluded: true, taxPercentage: 21, taxRegime: "IVA" },
  { id: "milk", code: "LEC-001", barcode: "8410000000035", name: "Leche fresca", salePrice: 1.75, taxId: "tax-iva-21", taxesIncluded: true, taxPercentage: 21, taxRegime: "IVA" }
];

const memberDiscountProduct: SaleProduct = {
  id: "member-coffee",
  code: "MEM-CAFE",
  name: "Cafe socio",
  salePrice: 10,
  discountType: "MEMBER_DISCOUNT",
  taxId: "tax-iva-21",
  taxesIncluded: true,
  taxPercentage: 21,
  taxRegime: "IVA",
};

const customers: SaleCustomer[] = [
  { id: "customer-1", clientId: "C-001", fiscalName: "Cliente Pruebas SL", documentNumber: "B11111111" },
  { id: "customer-2", clientId: "C-002", fiscalName: "Maria Lopez", documentNumber: "12345678Z" }
];

describe("SaleScreen", () => {
  it("resets the deletion sequence for every real cart boundary", () => {
    const ids = ["sale-1", "after-add", "after-finalize", "after-empty", "after-park"];
    const sequence = new SaleDeletionControlSequence(() => ids.shift()!);

    expect(sequence.currentSaleOperationId()).toBe("sale-1");
    sequence.reset("PRODUCT_ADDED");
    expect(sequence.currentSaleOperationId()).toBe("after-add");
    sequence.reset("SALE_FINALIZED");
    expect(sequence.currentSaleOperationId()).toBe("after-finalize");
    sequence.reset("CART_EMPTIED");
    expect(sequence.currentSaleOperationId()).toBe("after-empty");
    sequence.reset("SALE_PARKED");
    expect(sequence.currentSaleOperationId()).toBe("after-park");
  });

  it("serializes deletion records so rapid removals preserve their database order", async () => {
    const sequence = new SaleDeletionControlSequence(() => "sale-1");
    const events: string[] = [];
    let releaseFirst!: () => void;
    const firstGate = new Promise<void>((resolve) => { releaseFirst = resolve; });
    const onError = vi.fn();

    const first = sequence.enqueue(async () => {
      events.push("first:start");
      await firstGate;
      events.push("first:end");
    }, onError);
    const second = sequence.enqueue(async () => {
      events.push("second:start");
    }, onError);

    await Promise.resolve();
    expect(events).toEqual(["first:start"]);
    releaseFirst();
    await Promise.all([first, second]);
    expect(events).toEqual(["first:start", "first:end", "second:start"]);
    expect(onError).not.toHaveBeenCalled();
  });
  function renderSaleScreen(onLogout = vi.fn(), locale: "es" | "en" | "zh" = "es") {
    render(
      <SaleScreen
        app="venta"
        locale={locale}
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

  it("focuses product search with F5 and opens customer selection with F6", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("[]", {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    fireEvent.keyDown(window, { key: "F5" });
    expect(search).toHaveFocus();

    fireEvent.keyDown(window, { key: "F6" });
    expect(screen.getByRole("dialog", { name: "Seleccionar cliente" })).toBeInTheDocument();
  });

  it("opens quantity, discount and remove-line dialogs from their shortcuts", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([products[0]]), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "CAF-001" } });
    fireEvent.click(await screen.findByRole("option", { name: /Cafe molido/ }));

    fireEvent.keyDown(window, { key: "F2" });
    expect(screen.getByRole("dialog", { name: "Cambiar cantidad" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Cancelar" }));

    fireEvent.keyDown(window, { key: "F7" });
    expect(screen.getByRole("dialog", { name: "Aplicar descuento" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Cancelar" }));

    fireEvent.keyDown(window, { key: "Delete" });
    expect(screen.getByRole("dialog", { name: "Anular linea" })).toBeInTheDocument();
  });

  it("cancels remove-line confirmation with Escape without removing the line", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([products[0]]), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "CAF-001" } });
    fireEvent.click(await screen.findByRole("option", { name: /Cafe molido/ }));

    fireEvent.keyDown(window, { key: "Delete" });
    expect(screen.getByRole("dialog", { name: "Anular linea" })).toBeInTheDocument();
    await user.keyboard("{Escape}");

    expect(screen.queryByRole("dialog", { name: "Anular linea" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Cafe molido.*1 x 10,00/s })).toBeInTheDocument();
  });

  it("confirms remove-line confirmation with Enter", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([products[0]]), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "CAF-001" } });
    fireEvent.click(await screen.findByRole("option", { name: /Cafe molido/ }));

    fireEvent.keyDown(window, { key: "Delete" });
    expect(screen.getByRole("dialog", { name: "Anular linea" })).toBeInTheDocument();
    await user.keyboard("{Enter}");

    expect(screen.queryByRole("dialog", { name: "Anular linea" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Cafe molido.*1 x 10,00/s })).not.toBeInTheDocument();
  });

  it("records the removed line and identifies a complete cart clear", async () => {
    const fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
      const path = new URL(String(url), "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([products[0]]), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/sale-line-deletions")) {
        return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      }
      return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
    });
    vi.stubGlobal("fetch", fetchMock);
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    for (let index = 0; index < 2; index += 1) {
      fireEvent.change(search, { target: { value: "CAF-001" } });
      fireEvent.click(await screen.findByRole("option", { name: /Cafe molido/ }));
    }

    fireEvent.keyDown(window, { key: "Delete" });
    fireEvent.click(screen.getByRole("button", { name: "Anular linea" }));

    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith("/sale-line-deletions"))).toBe(true));
    const [, request] = fetchMock.mock.calls.find(([url]) => String(url).endsWith("/sale-line-deletions"))!;
    expect(request).toMatchObject({
      method: "POST",
      headers: expect.objectContaining({ Authorization: "Bearer access-token" }),
    });
    expect(JSON.parse(String(request?.body))).toEqual({
      saleOperationId: expect.stringMatching(/^[0-9a-f-]{36}$/),
      deletionOperationId: expect.stringMatching(/^[0-9a-f-]{36}$/),
      fullTicketClear: true,
      lines: [{
        productId: "coffee",
        code: "CAF-001",
        name: "Cafe molido",
        quantity: 2,
        unitPrice: 10,
      }],
    });
  });

  it("removes one line even when recording the best-effort event fails", async () => {
    const warning = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const fetchMock = vi.fn(async (url: string, _options?: RequestInit) => {
      const path = new URL(String(url), "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify(products.slice(0, 2)), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/sale-line-deletions")) {
        throw new Error("control endpoint unavailable");
      }
      return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
    });
    vi.stubGlobal("fetch", fetchMock);
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "CAF-001" } });
    fireEvent.click(await screen.findByRole("option", { name: /Cafe molido/ }));
    fireEvent.change(search, { target: { value: "PAN-001" } });
    fireEvent.click(await screen.findByRole("option", { name: /Pan integral/ }));

    fireEvent.keyDown(window, { key: "Delete" });
    fireEvent.click(screen.getByRole("button", { name: "Anular linea" }));

    expect(screen.queryByRole("button", { name: /Pan integral.*1 x 2,50/s })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Cafe molido.*1 x 10,00/s })).toBeInTheDocument();
    await waitFor(() => expect(warning).toHaveBeenCalledWith("sale_line_deletion_not_recorded", expect.any(Error)));
    const [, request] = fetchMock.mock.calls.find(([url]) => String(url).endsWith("/sale-line-deletions"))!;
    expect(JSON.parse(String(request?.body))).toMatchObject({
      saleOperationId: expect.stringMatching(/^[0-9a-f-]{36}$/),
      deletionOperationId: expect.stringMatching(/^[0-9a-f-]{36}$/),
      fullTicketClear: false,
      lines: [{ productId: "bread", code: "PAN-001", name: "Pan integral", quantity: 1, unitPrice: 2.5 }],
    });
    warning.mockRestore();
  });

  it("replaces the selected quantity from the keyboard and cancels later edits with Escape", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([products[0]]), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "CAF-001" } });
    fireEvent.click(await screen.findByRole("option", { name: /Cafe molido/ }));

    fireEvent.keyDown(window, { key: "F2" });
    const quantityInput = screen.getByRole("spinbutton", { name: "Nueva cantidad" });
    expect(quantityInput).toHaveFocus();
    await user.keyboard("2{Enter}");

    expect(screen.queryByRole("dialog", { name: "Cambiar cantidad" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Cafe molido.*2 x 10,00/s })).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "F2" });
    await user.keyboard("9{Escape}");

    expect(screen.queryByRole("dialog", { name: "Cambiar cantidad" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Cafe molido.*2 x 10,00/s })).toBeInTheDocument();
  });

  it("replaces the selected discount from the keyboard and cancels later edits with Escape", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([products[0]]), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "CAF-001" } });
    fireEvent.click(await screen.findByRole("option", { name: /Cafe molido/ }));

    fireEvent.keyDown(window, { key: "F7" });
    const discountInput = screen.getByRole("spinbutton", { name: "Nuevo descuento" });
    expect(discountInput).toHaveFocus();
    await user.keyboard("10{Enter}");

    expect(screen.queryByRole("dialog", { name: "Aplicar descuento" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Cafe molido.*10,00%/s })).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "F7" });
    await user.keyboard("25{Escape}");

    expect(screen.queryByRole("dialog", { name: "Aplicar descuento" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Cafe molido.*10,00%/s })).toBeInTheDocument();
  });

  it("cancels customer selection with Escape without changing the customer", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("[]", {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    fireEvent.keyDown(window, { key: "F6" });
    const customerSearch = await screen.findByRole("textbox", { name: "Buscar cliente" });
    await user.type(customerSearch, "Maria");
    await user.keyboard("{Escape}");

    expect(screen.queryByRole("dialog", { name: "Seleccionar cliente" })).not.toBeInTheDocument();
    expect(screen.queryByText(/Cliente: /)).not.toBeInTheDocument();
  });

  it("delegates PageDown and F11 to the checkout actions and ignores F10, repeats, or open modals", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([products[0]]), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "Cafe" } });
    fireEvent.click(await screen.findByRole("option", { name: /Cafe molido/ }));

    fireEvent.keyDown(window, { key: "PageDown" });
    fireEvent.keyDown(window, { key: "F11" });
    expect(triggerCash).toHaveBeenCalledTimes(1);
    expect(triggerCard).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(window, { key: "F10" });
    fireEvent.keyDown(window, { key: "PageDown", repeat: true });
    expect(triggerCash).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(window, { key: "F6" });
    expect(await screen.findByRole("dialog", { name: "Seleccionar cliente" })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "PageDown" });
    expect(triggerCash).toHaveBeenCalledTimes(1);
  });

  it("loads the sale catalog from the fiscal sale endpoint", async () => {
    const apiPaths: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      apiPaths.push(path.replace("/api/v1", ""));
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([{
          ...products[0],
          taxId: "tax-iva-21",
          taxesIncluded: true,
          taxPercentage: 21,
          taxRegime: "IVA",
        }]), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      throw new Error(`unexpected request ${path}`);
    }));

    renderSaleScreen();

    await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(apiPaths).toContain("/products/sale"));
    expect(apiPaths).not.toContain("/products");
  });

  it("opens F12 through customer selection, uses local plus 30 days and clears only after create succeeds", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date(2026, 6, 16, 12));
    const fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) return new Response(JSON.stringify(products), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/customers/sale-options")) return new Response(JSON.stringify([{ ...customers[0], activeMember: true, memberDiscountPercent: 5 }, customers[1]]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/warehouses")) return new Response(JSON.stringify([{ id: "warehouse-1", defaultWarehouse: true, active: true }]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/customer-pending-sales/quote")) {
        const body = JSON.parse(String(options?.body));
        expect(body.customerId).toBe("customer-1");
        expect(body.lines[0].descuento).toBe("0.00");
        expect(body.lines[0]).not.toHaveProperty("memberDiscountPercent");
        return new Response(JSON.stringify({ total: "9.50" }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/payment-methods")) return new Response(JSON.stringify([
        { id: "cash-method", name: "EFECTIVO", active: true },
        { id: "card-method", name: "TARJETA", active: true },
        { id: "transfer-method", name: "TRANSFERENCIA", active: true },
      ]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/customer-pending-sales")) {
        const body = JSON.parse(String(options?.body));
        expect(body).toMatchObject({ customerId: "customer-1", dueDate: "2026-08-15", payments: [], quotedTotal: "9.50" });
        expect(body.lines[0].descuento).toBe("0.00");
        expect(body).not.toHaveProperty("paymentMethod");
        return new Response(JSON.stringify({ receivable: { documentId: "doc-1", documentNumber: "AV-1" }, printDocument: {
          documentId: "doc-1", documentType: "ALBARAN_VENTA", documentNumber: "AV-1",
          issueDate: "2026-07-16", lines: [], baseTotal: "9.50", taxTotal: "0.00", total: "9.50"
        } }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/terminal-configuration/payment")) return new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/payment-sessions/active")) return new Response("null", { status: 200, headers: { "Content-Type": "application/json" } });
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "CAF-001" } });
    fireEvent.click(await screen.findByRole("option", { name: /Cafe molido/ }));

    act(() => checkoutProps.current?.onLockedChange?.(true, 1000));
    fireEvent.keyDown(window, { key: "F12" });
    expect(screen.queryByRole("dialog", { name: "Seleccionar cliente" })).not.toBeInTheDocument();
    act(() => checkoutProps.current?.onLockedChange?.(false));
    fireEvent.keyDown(window, { key: "F12" });
    fireEvent.click(await screen.findByRole("button", { name: /Cliente Pruebas/ }));
    expect(await screen.findByRole("dialog", { name: /venta pendiente/i })).toBeVisible();
    expect(screen.getByLabelText(/vencimiento/i)).toHaveValue("2026-08-15");
    expect(screen.getAllByText("9,50")).not.toHaveLength(0);
    act(() => checkoutProps.current?.onLockedChange?.(true, 1000));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: /venta pendiente/i })).not.toBeInTheDocument());
    act(() => checkoutProps.current?.onLockedChange?.(false));
    fireEvent.keyDown(window, { key: "F12" });
    expect(await screen.findByRole("dialog", { name: /venta pendiente/i })).toBeVisible();
    const confirm = await screen.findByRole("button", { name: /confirmar venta pendiente/i });
    await waitFor(() => expect(confirm).toBeEnabled());
    fireEvent.click(confirm);

    await waitFor(() => expect(screen.queryByRole("dialog", { name: /venta pendiente/i })).not.toBeInTheDocument());
    expect(screen.queryByRole("button", { name: /Cafe molido.*1 x/s })).not.toBeInTheDocument();
    vi.useRealTimers();
  });

  it("shows the fiscal catalog error instead of opening a pending-sale dialog", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([{ ...products[0], taxesIncluded: null as never }]), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/customers/sale-options")) {
        return new Response(JSON.stringify([customers[0]]), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/warehouses")) {
        return new Response(JSON.stringify([{ id: "warehouse-1", defaultWarehouse: true, active: true }]), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      throw new Error(`unexpected request ${path}`);
    }));

    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "CAF-001" } });
    fireEvent.click(await screen.findByRole("option", { name: /Cafe molido/ }));
    fireEvent.keyDown(window, { key: "F12" });
    fireEvent.click(await screen.findByRole("button", { name: /Cliente Pruebas/ }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Producto sin configuración de impuestos válida");
    expect(screen.queryByRole("dialog", { name: /venta pendiente/i })).not.toBeInTheDocument();
  });

  it("auto-opens the same uncertain pending checkout after unmount and reload without requoting", async () => {
    const recoveredDraft = pendingSaleDraftForCustomer([
      { product: products[0], quantity: 1, discountPercent: 0 },
    ], { ...customers[0], activeMember: false }, "warehouse-1", new Date(2026, 6, 16, 12), "checkout-reload");
    savePendingSaleRecovery(localStorage, {
      version: 2,
      phase: "CARD_IN_FLIGHT",
      terminalCode: terminalContext.terminalCode,
      customer: { id: "customer-1", name: "Cliente Pruebas SL" },
      draft: recoveredDraft,
      quoteCents: 1_000,
      quoteReady: true,
      payments: [{ id: "checkout-reload", operationId: "checkout-reload", mode: "INTEGRATED", kind: "INTEGRATED_CARD", methodId: "card-method", amountCents: 300, status: "TIMEOUT" }],
      savedAt: "2026-07-18T08:00:00.000Z",
    });
    const fetchMock = vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/payment-methods")) return new Response(JSON.stringify([{ id: "card-method", name: "TARJETA", active: true }]), { status: 200, headers: { "Content-Type": "application/json" } });
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    renderSaleScreen();
    expect(await screen.findByRole("dialog", { name: /venta pendiente/i })).toBeVisible();
    expect(screen.getByRole("button", { name: /consultar tarjeta/i })).toBeEnabled();
    cleanup();
    renderSaleScreen();
    expect(await screen.findByRole("dialog", { name: /venta pendiente/i })).toBeVisible();
    expect(fetchMock.mock.calls.every(([url]) => !String(url).includes("/quote"))).toBe(true);
  });

  it("auto-reopens and byte-replays a READY_TO_CREATE sale without card after a lost response", async () => {
    const recoveredDraft = pendingSaleDraftForCustomer([
      { product: products[0], quantity: 1, discountPercent: 0 },
    ], { ...customers[0], activeMember: false }, "warehouse-1", new Date(2026, 6, 16, 12), "checkout-ready");
    savePendingSaleRecovery(localStorage, {
      version: 2, phase: "READY_TO_CREATE", terminalCode: terminalContext.terminalCode,
      customer: { id: "customer-1", name: "Cliente Pruebas SL" }, draft: recoveredDraft,
      quoteCents: 1_000, quoteReady: true, payments: [], savedAt: "2026-07-18T08:00:00.000Z",
      createAttempted: true,
    });
    const bodies: string[] = [];
    let creates = 0;
    const fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale") || path.endsWith("/payment-methods")) return new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/customer-pending-sales")) {
        bodies.push(String(options?.body)); creates += 1;
        if (creates === 1) throw new Error("response lost");
        return new Response(JSON.stringify({ receivable: { documentId: "doc-ready" }, printDocument: {} }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);

    renderSaleScreen();
    fireEvent.click(await screen.findByRole("button", { name: /confirmar venta pendiente/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent("response lost");
    expect(localStorage.getItem(pendingSaleRecoveryKey(terminalContext.terminalCode))).not.toBeNull();
    const durableDialog = screen.getByRole("dialog", { name: /venta pendiente/i });
    fireEvent.click(screen.getByRole("button", { name: "Cancelar" }));
    fireEvent.keyDown(window, { key: "F12" });
    expect(durableDialog).toBeVisible();
    expect(durableDialog).not.toHaveAttribute("aria-hidden", "true");
    cleanup();
    renderSaleScreen();
    fireEvent.click(await screen.findByRole("button", { name: /confirmar venta pendiente/i }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: /venta pendiente/i })).not.toBeInTheDocument());
    expect(bodies).toHaveLength(2);
    expect(bodies[1]).toBe(bodies[0]);
    expect(localStorage.getItem(pendingSaleRecoveryKey(terminalContext.terminalCode))).toBeNull();
    expect(fetchMock.mock.calls.every(([url]) => !String(url).includes("/quote"))).toBe(true);
  });

  it("discards a legacy local-only draft instead of reopening it on a new sale entry", async () => {
    const recoveredDraft = pendingSaleDraftForCustomer([
      { product: products[0], quantity: 1, discountPercent: 0 },
    ], { ...customers[0], activeMember: false }, "warehouse-1", new Date(2026, 6, 16, 12), "checkout-stale");
    savePendingSaleRecovery(localStorage, {
      version: 2, phase: "READY_TO_CREATE", terminalCode: terminalContext.terminalCode,
      customer: { id: "customer-1", name: "Cliente Pruebas SL" }, draft: recoveredDraft,
      quoteCents: 1_000, quoteReady: true,
      payments: [{ id: "cash-stale", kind: "CASH", methodId: "cash-method", amountCents: 200,
        deliveredCents: 200, changeCents: 0, status: "APPROVED" }],
      savedAt: "2026-07-18T08:00:00.000Z",
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("[]", {
      status: 200, headers: { "Content-Type": "application/json" },
    })));

    renderSaleScreen();

    await waitFor(() => expect(screen.queryByRole("dialog", { name: /venta pendiente/i })).not.toBeInTheDocument());
    expect(localStorage.getItem(pendingSaleRecoveryKey(terminalContext.terminalCode))).toBeNull();
  });

  it("fails closed on corrupt recovery data and exposes its recoverable identifier without deleting it", async () => {
    const raw = '{"checkoutId":"checkout-corrupt","broken":';
    localStorage.setItem(pendingSaleRecoveryKey(terminalContext.terminalCode), raw);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } })));
    const previous = document.createElement("button");
    document.body.appendChild(previous);
    previous.focus();

    renderSaleScreen();
    const dialog = await screen.findByRole("dialog", { name: /recuperaci[oó]n de cobro bloqueada/i });
    expect(dialog).toBeVisible();
    expect(screen.getByRole("alert")).toHaveTextContent(/no se han eliminado/i);
    expect(screen.getByText("checkout-corrupt")).toBeInTheDocument();
    const rawField = screen.getByLabelText(/datos t[eé]cnicos guardados/i);
    const copy = screen.getByRole("button", { name: /copiar datos/i });
    expect(rawField).toHaveValue(raw);
    expect(rawField).toHaveFocus();
    copy.focus(); fireEvent.keyDown(copy, { key: "Tab" }); expect(rawField).toHaveFocus();
    fireEvent.keyDown(rawField, { key: "Tab", shiftKey: true }); expect(copy).toHaveFocus();
    fireEvent.keyDown(copy, { key: "Escape" });
    expect(dialog).toBeVisible();
    expect(document.querySelector(".work-shell")).toHaveAttribute("aria-hidden", "true");
    expect(localStorage.getItem(pendingSaleRecoveryKey(terminalContext.terminalCode))).toBe(raw);
    fireEvent.keyDown(window, { key: "F12" });
    expect(screen.queryByRole("dialog", { name: /seleccionar cliente/i })).not.toBeInTheDocument();
    cleanup();
    expect(previous).toHaveFocus();
    previous.remove();
  });

  it("does not run global sale shortcuts from focused editable controls", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([products[0]]), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "Cafe" } });
    fireEvent.click(await screen.findByRole("option", { name: /Cafe molido/ }));

    const contentEditable = document.createElement("div");
    contentEditable.contentEditable = "true";
    contentEditable.tabIndex = 0;
    document.body.appendChild(contentEditable);

    for (const target of [search, contentEditable]) {
      target.focus();
      expect(target).toHaveFocus();
      await user.keyboard("{PageDown}{F11}{F2}{F7}{F6}{Delete}");
      expect(triggerCash).not.toHaveBeenCalled();
      expect(triggerCard).not.toHaveBeenCalled();
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: /Cafe molido.*1 x 10,00/s })).toHaveAttribute("aria-pressed", "true");
    }
  });

  it("does not start cash payment from PageDown when checkout is disabled for an empty sale", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("[]", {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const cashButton = await screen.findByRole("button", { name: /Efectivo/ });
    expect(cashButton).toBeDisabled();

    fireEvent.keyDown(window, { key: "PageDown" });

    expect(triggerCash).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("moves ticket-line selection with vertical arrows without wrapping", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(products.slice(0, 2)), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "Cafe" } });
    fireEvent.click(await screen.findByRole("option", { name: /Cafe molido/ }));
    fireEvent.change(search, { target: { value: "Pan" } });
    fireEvent.click(await screen.findByRole("option", { name: /Pan integral/ }));
    const coffee = screen.getByRole("button", { name: /Cafe molido.*1 x 10,00/s });
    const bread = screen.getByRole("button", { name: /Pan integral.*1 x 2,50/s });

    expect(bread).toHaveAttribute("aria-pressed", "true");
    const handledArrow = new KeyboardEvent("keydown", { key: "ArrowUp", cancelable: true });
    act(() => window.dispatchEvent(handledArrow));
    expect(handledArrow.defaultPrevented).toBe(true);
    expect(coffee).toHaveAttribute("aria-pressed", "true");
    fireEvent.keyDown(window, { key: "ArrowUp" });
    expect(coffee).toHaveAttribute("aria-pressed", "true");
    fireEvent.keyDown(window, { key: "ArrowDown" });
    expect(bread).toHaveAttribute("aria-pressed", "true");
    fireEvent.keyDown(window, { key: "ArrowDown" });
    expect(bread).toHaveAttribute("aria-pressed", "true");
  });

  it("leaves vertical arrows to editable targets and ignores repeats, payment locks, and modals", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(products.slice(0, 2)), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "Cafe" } });
    fireEvent.click(await screen.findByRole("option", { name: /Cafe molido/ }));
    fireEvent.change(search, { target: { value: "Pan" } });
    fireEvent.click(await screen.findByRole("option", { name: /Pan integral/ }));
    const coffee = screen.getByRole("button", { name: /Cafe molido.*1 x 10,00/s });
    const bread = screen.getByRole("button", { name: /Pan integral.*1 x 2,50/s });

    const editable = document.createElement("div");
    editable.contentEditable = "true";
    document.body.appendChild(editable);
    for (const target of [search, document.createElement("textarea"), document.createElement("select"), editable]) {
      if (!target.isConnected) document.body.appendChild(target);
      const event = new KeyboardEvent("keydown", { key: "ArrowUp", bubbles: true, cancelable: true });
      target.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
      expect(bread).toHaveAttribute("aria-pressed", "true");
    }

    fireEvent.keyDown(window, { key: "ArrowUp", repeat: true });
    expect(bread).toHaveAttribute("aria-pressed", "true");
    act(() => checkoutProps.current?.onLockedChange?.(true, 1250));
    fireEvent.keyDown(window, { key: "ArrowUp" });
    expect(bread).toHaveAttribute("aria-pressed", "true");
    act(() => checkoutProps.current?.onLockedChange?.(false));
    fireEvent.keyDown(window, { key: "F6" });
    expect(await screen.findByRole("dialog", { name: "Seleccionar cliente" })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "ArrowUp" });
    expect(bread).toHaveAttribute("aria-pressed", "true");
    expect(coffee).toHaveAttribute("aria-pressed", "false");
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

  it("preserves cash received and calculates non-negative change for individual checkout", () => {
    expect(cashResultFromFinalization("T-1", 1210, 2000)).toEqual({
      ticketNumber: "T-1",
      totalCents: 1210,
      receivedCents: 2000,
      changeCents: 790,
    });
    expect(cashResultFromFinalization("T-2", 1210, 1210)).toEqual({
      ticketNumber: "T-2",
      totalCents: 1210,
      receivedCents: 1210,
      changeCents: 0,
    });
    expect(cashResultFromFinalization("T-3", 1210, 1000).changeCents).toBe(0);
  });

  it("maps explicit checkout finalization summaries to card, cash, or mixed result details", async () => {
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current?.onFinalized).toBeTypeOf("function"));

    act(() => checkoutProps.current?.onFinalized?.(printSnapshot("CARD-1"), { kind: "CARD", totalCents: 1210 }));

    const cardResult = within(screen.getByRole("dialog"));
    expect(cardResult.getByText("Tarjeta")).toBeInTheDocument();
    expect(cardResult.queryByText("Dinero recibido")).not.toBeInTheDocument();
    expect(cardResult.queryByText("Cambio")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Finalizar" }));
    act(() => checkoutProps.current?.onFinalized?.(printSnapshot("CASH-1"), { kind: "CASH", totalCents: 1210, receivedCents: 2000 }));

    const cashResult = within(screen.getByRole("dialog"));
    expect(cashResult.getByText("Dinero recibido")).toBeInTheDocument();
    expect(cashResult.getByText("Cambio")).toBeInTheDocument();
    expect(cashResult.getByText("7,90")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Finalizar" }));
    act(() => checkoutProps.current?.onFinalized?.(printSnapshot("MIXED-1"), { kind: "MIXED", totalCents: 1210 }));

    const mixedResult = within(screen.getByRole("dialog"));
    expect(mixedResult.getByText("Mixto")).toBeInTheDocument();
    expect(mixedResult.queryByText("Dinero recibido")).not.toBeInTheDocument();
    expect(mixedResult.queryByText("Cambio")).not.toBeInTheDocument();
  });

  it("shows completed CASH checkout as PRINTING before ticket hardware settles", async () => {
    let resolvePrint!: (result: { ok: true }) => void;
    const printTicket = vi.fn(() => new Promise<{ ok: true }>((resolve) => { resolvePrint = resolve; }));
    installTicketHardware(printTicket);
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current?.onFinalized).toBeTypeOf("function"));

    act(() => checkoutProps.current?.onFinalized(printSnapshot("CASH-PRINT"), { kind: "CASH", totalCents: 1210, receivedCents: 2000 }));

    expect(screen.getByText("Pago completado")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Imprimiendo ticket");
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(1));
    resolvePrint({ ok: true });
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Ticket enviado a la impresora"));
  });

  it("skips automatic ticket printing for a pure CARD checkout", async () => {
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    installTicketHardware(printTicket);
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current?.onFinalized).toBeTypeOf("function"));

    act(() => checkoutProps.current?.onFinalized(printSnapshot("CARD-NO-PRINT"), { kind: "CARD", totalCents: 1210 }));

    expect(screen.getByText("Pago completado")).toBeInTheDocument();
    expect(printTicket).not.toHaveBeenCalled();
    expect(screen.queryByText("Imprimiendo ticket…")).not.toBeInTheDocument();
  });

  it("automatically prints a MIXED checkout ticket", async () => {
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    installTicketHardware(printTicket);
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current?.onFinalized).toBeTypeOf("function"));

    act(() => checkoutProps.current?.onFinalized(printSnapshot("MIXED-PRINT"), { kind: "MIXED", totalCents: 1210 }));

    expect(screen.getByRole("status")).toHaveTextContent("Imprimiendo ticket");
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Ticket enviado a la impresora"));
  });

  it("keeps completion after print failure and retries hardware without finalizing payment again", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    const printTicket = vi.fn()
      .mockResolvedValueOnce({ ok: false, code: "PRINT_FAILED", message: "paper jam" })
      .mockResolvedValueOnce({ ok: true });
    installTicketHardware(printTicket);
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current?.onFinalized).toBeTypeOf("function"));

    act(() => checkoutProps.current?.onFinalized(printSnapshot("CASH-RETRY"), { kind: "CASH", totalCents: 1210, receivedCents: 1210 }));

    expect(await screen.findByRole("alert")).toHaveTextContent("El cobro se ha completado");
    expect(screen.getByRole("button", { name: "Finalizar" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Reintentar impresión" }));
    expect(screen.getByRole("status")).toHaveTextContent("Imprimiendo ticket");
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(2));
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/pos/cash"))).toHaveLength(0);
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Ticket enviado a la impresora"));
  });

  it("ignores a late print result after the completed-payment dialog is closed", async () => {
    let resolvePrint!: (result: { ok: false; code: "PRINT_FAILED"; message: string }) => void;
    const printTicket = vi.fn(() => new Promise((resolve) => { resolvePrint = resolve; }));
    installTicketHardware(printTicket);
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current?.onFinalized).toBeTypeOf("function"));
    act(() => checkoutProps.current?.onFinalized(printSnapshot("CASH-CLOSED"), { kind: "CASH", totalCents: 1210, receivedCents: 1210 }));
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("button", { name: "Finalizar" }));

    resolvePrint({ ok: false, code: "PRINT_FAILED", message: "late failure" });
    await act(async () => { await Promise.resolve(); });
    expect(screen.queryByText("Pago completado")).not.toBeInTheDocument();
  });

  it("does not apply an old print result to a newer completed ticket", async () => {
    const resolvers: Array<(result: { ok: boolean; code?: "PRINT_FAILED"; message?: string }) => void> = [];
    const printTicket = vi.fn(() => new Promise((resolve) => { resolvers.push(resolve); }));
    installTicketHardware(printTicket);
    renderSaleScreen();
    await waitFor(() => expect(checkoutProps.current?.onFinalized).toBeTypeOf("function"));
    act(() => checkoutProps.current?.onFinalized(printSnapshot("CASH-OLD"), { kind: "CASH", totalCents: 1210, receivedCents: 1210 }));
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(1));
    act(() => checkoutProps.current?.onFinalized(printSnapshot("CASH-NEW"), { kind: "CASH", totalCents: 1210, receivedCents: 1210 }));
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(2));

    resolvers[0]({ ok: false, code: "PRINT_FAILED", message: "old failure" });
    await act(async () => { await Promise.resolve(); });
    expect(screen.getByText("CASH-NEW")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Imprimiendo ticket");
    resolvers[1]({ ok: true });
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Ticket enviado a la impresora"));
  });
  it("shows the authoritative reserved total when a recovered payment locks an empty local cart", () => {
    expect(saleDisplayedTotal(0, true, 0, 1210)).toBe(12.1);
    expect(saleDisplayedTotal(5, false, 0, 1210)).toBe(5);
  });
  it("renders the sales workspace with shared frame controls", () => {
    const openCustomerReceivables = vi.fn();
    const html = renderToStaticMarkup(
      <SaleScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
        onOpenCustomerReceivables={openCustomerReceivables}
      />
    );

    expect(html).toContain('class="sale-screen work-screen keyboard-mode"');
    expect(html).toContain('class="report-user-button"');
    expect(html).toContain('class="language-button"');
    expect(html).toContain('class="shutdown-button"');
    expect(html).toContain('class="report-footer-context"');
    expect(html).toContain("Venta");
    expect(html).toContain("Añadir producto");
    expect(html).toContain("Líneas de venta");
    expect(html).toContain("Cobro");
    expect(html).toContain("Deudas de clientes");
    expect(html).toContain('class="sale-receivables-entry"');
    expect(html).toContain("F5");
    expect(html).toContain("AvPág");
    expect(html).not.toContain("F10");
    expect(html).toContain("Sin venta iniciada");
    expect(html).toContain('aria-label="Buscar producto"');
    expect(html).toContain('aria-label="Búsqueda y cobro"');
    expect(html).toContain("Entrada rápida por código, nombre o referencia");
    expect(html).toContain('placeholder="Código o nombre"');
    expect(html).toContain("Anular línea");
    expect(html).toContain('aria-controls="sale-product-results"');
    expect(html).toContain("Cargando productos");
    expect(html).not.toContain("Cafe molido");
    expect(html).not.toContain("Pan integral");
    expect(html).not.toContain("Leche fresca");
    expect(html).not.toContain("15,15");
  });

  it.each([
    ["es", ["Venta", "Líneas de venta", "Sin venta iniciada", "Buscar producto", "Cobro"], null],
    ["en", ["Sale", "Sale lines", "No sale started", "Search product", "Payment"], ["Sale", "Current ticket", "Search and payment", "Search product", "Payment", "Sale shortcuts"]],
    ["zh", ["销售", "销售明细", "尚未开始销售", "搜索商品", "收款"], ["销售", "当前小票", "商品搜索与收款", "搜索商品", "收款", "销售快捷键"]],
  ] as const)("localizes the main sale view in %s", (locale, labels, ariaLabels) => {
    const html = renderToStaticMarkup(
      <SaleScreen
        app="venta"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
      />,
    );

    labels.forEach((label) => expect(html).toContain(label));
    ariaLabels?.forEach((label) => expect(html).toContain(`aria-label="${label}"`));
    expect(html).toContain("0,00");
  });

  it.each([
    ["es", ["Gesti\u00f3n", "Ventas aparcadas", "Guardar o recuperar", "Gestionar tickets", "Buscar y realizar acciones"]],
    ["en", ["Management", "Parked sales", "Save or recover", "Manage tickets", "Search and perform actions"]],
    ["zh", ["\u7ba1\u7406", "\u6682\u5b58\u9500\u552e", "\u4fdd\u5b58\u6216\u6062\u590d", "\u7968\u636e\u7ba1\u7406", "\u641c\u7d22\u5e76\u6267\u884c\u64cd\u4f5c"]],
  ] as const)("localizes sale management actions in %s", (locale, labels) => {
    const html = renderToStaticMarkup(
      <SaleScreen
        app="venta"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
      />,
    );

    labels.forEach((label) => expect(html).toContain(label));
  });

  it.each(["en", "zh"] as const)("keeps dynamic product names and codes literal in %s", async (locale) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify([
      { id: "literal-product", name: "Café 原样", code: "SKU-原样-001", salePrice: 12.34 },
    ]), { status: 200, headers: { "Content-Type": "application/json" } })));
    renderSaleScreen(vi.fn(), locale);

    const searchInput = await screen.findByRole("combobox", { name: locale === "en" ? "Search product" : "搜索商品" });
    await waitFor(() => expect(searchInput).toBeEnabled());
    fireEvent.change(searchInput, { target: { value: "SKU-原样-001" } });

    expect(await screen.findByText("Café 原样")).toBeInTheDocument();
    expect(screen.getByText("SKU-原样-001")).toBeInTheDocument();
  });

  it.each(["en", "zh"] as const)("does not leak fixed Spanish main-view labels in %s", (locale) => {
    const html = renderToStaticMarkup(
      <SaleScreen app="venta" locale={locale} session={session} terminalContext={terminalContext} onBack={vi.fn()} onLocaleChange={vi.fn()} onLogout={vi.fn()} />,
    );

    ["Líneas de venta", "Sin venta iniciada", "Buscar producto", "Cantidad", "Descuento", "Anular línea", "Cobro"].forEach((label) => {
      expect(html).not.toContain(label);
    });
  });

  it("interpolates localized customer and product counters", () => {
    const tEn = createTranslator("en");
    const tZh = createTranslator("zh");
    expect(saleMainMessage(tEn, "sale.main.selectedCustomer", { name: "ACME" })).toBe("Customer: ACME");
    expect(saleMainProductCount(tEn, 1)).toBe("1 product");
    expect(saleMainProductCount(tEn, 2)).toBe("2 products");
    expect(saleMainProductCount(tZh, 2)).toBe("2 件商品");
  });

  it("filters products by name without case sensitivity", () => {
    expect(filterSaleProducts(products, "  CAFE ").map((product) => product.id)).toEqual(["coffee"]);
  });

  it("filters products by internal code or barcode", () => {
    expect(filterSaleProducts(products, "PAN-0").map((product) => product.id)).toEqual(["bread"]);
    expect(filterSaleProducts(products, "0000000011").map((product) => product.id)).toEqual(["coffee"]);
    expect(filterSaleProducts(products, "alt-cafe").map((product) => product.id)).toEqual(["coffee"]);
  });

  it("excludes inactive products unless the store setting allows them", () => {
    const inactive = { ...products[0], id: "inactive", active: false };

    expect(saleSelectableProducts([...products, inactive], false)).not.toContainEqual(inactive);
    expect(saleSelectableProducts([...products, inactive], true)).toContainEqual(inactive);
  });

  it("hides inactive products from sale search when the store setting is disabled", async () => {
    const inactive = { id: "inactive", code: "OFF-001", name: "Producto desactivado", salePrice: 3, active: false };
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([inactive]), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: false }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();

    const searchInput = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(searchInput).toBeEnabled());
    fireEvent.change(searchInput, { target: { value: "OFF-001" } });

    expect(await screen.findByText("No se encontraron productos")).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: /Producto desactivado/ })).not.toBeInTheDocument();
  });

  it("requires Enter confirmation before adding an allowed inactive product and cancels with Escape", async () => {
    const inactive = { id: "inactive", code: "OFF-001", name: "Producto desactivado", salePrice: 3, active: false };
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) {
        return new Response(JSON.stringify([inactive]), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      if (path.endsWith("/stock/settings")) {
        return new Response(JSON.stringify({ allowInactiveProductSales: true }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();

    const searchInput = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(searchInput).toBeEnabled());
    fireEvent.change(searchInput, { target: { value: "OFF-001" } });
    fireEvent.click(await screen.findByRole("option", { name: /Producto desactivado/ }));

    const confirmationDialog = screen.getByRole("dialog", { name: "Producto desactivado" });
    expect(confirmationDialog).toBeInTheDocument();
    fireEvent.keyDown(confirmationDialog, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "Producto desactivado" })).not.toBeInTheDocument();
    expect(screen.getAllByText("Sin venta iniciada")).not.toHaveLength(0);

    fireEvent.click(screen.getByRole("option", { name: /Producto desactivado/ }));
    fireEvent.keyDown(screen.getByRole("dialog", { name: "Producto desactivado" }), { key: "Enter" });

    expect(screen.queryByRole("dialog", { name: "Producto desactivado" })).not.toBeInTheDocument();
    expect(screen.getByText("1 producto")).toBeInTheDocument();
  });

  it("limits visible search results", () => {
    const manyProducts = Array.from({ length: 12 }, (_, index) => ({
      ...products[0],
      id: String(index),
      code: `CODE-${index}`,
      name: `Product ${index}`,
      salePrice: index
    }));

    expect(filterSaleProducts(manyProducts, "product")).toHaveLength(10);
  });

  it("prioritizes an exact code or barcode when selecting with Enter", () => {
    const ambiguous: SaleProduct[] = [
      { ...products[0], id: "code-in-name", code: "OTHER", name: "Accessory CAF-001", salePrice: 3 },
      ...products
    ];

    expect(selectSaleProduct(ambiguous, "caf-001")?.id).toBe("coffee");
    expect(selectSaleProduct(products, "8410000000028")?.id).toBe("bread");
    expect(selectSaleProduct(products, "alt-cafe")?.id).toBe("coffee");
  });

  it("selects the only partial match with Enter", () => {
    expect(selectSaleProduct(products, "leche")?.id).toBe("milk");
  });

  it("selects and adds the first result with Enter when a query has multiple matches", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(products.slice(0, 2)), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());

    fireEvent.change(search, { target: { value: "00" } });

    const results = await screen.findByRole("listbox", { name: "Buscar producto" });
    const coffeeResult = within(results).getByRole("option", { name: /Cafe molido/ });
    const breadResult = within(results).getByRole("option", { name: /Pan integral/ });
    expect(coffeeResult).toHaveAttribute("aria-selected", "true");
    expect(coffeeResult).toHaveClass("selected");
    expect(breadResult).toHaveAttribute("aria-selected", "false");
    expect(search).toHaveAttribute("aria-controls", results.id);
    expect(search).toHaveAttribute("aria-activedescendant", coffeeResult.id);

    fireEvent.keyDown(search, { key: "Enter" });

    expect(await screen.findByRole("button", { name: /Cafe molido.*1 x 10,00/s })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Pan integral.*1 x 2,50/s })).not.toBeInTheDocument();
  });

  it("keeps the active search option within a disjoint result set after the query changes", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(products.slice(0, 2)), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    })));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "Cafe" } });
    expect(await screen.findByRole("option", { name: /Cafe molido/ })).toHaveAttribute("aria-selected", "true");

    fireEvent.change(search, { target: { value: "Pan" } });

    const currentOptions = screen.getAllByRole("option");
    const selectedOptions = currentOptions.filter((option) => option.getAttribute("aria-selected") === "true");
    expect(selectedOptions).toHaveLength(1);
    expect(selectedOptions[0]).toHaveAccessibleName(/Pan integral/);
    expect(search).toHaveAttribute("aria-activedescendant", selectedOptions[0].id);
    expect(document.getElementById(search.getAttribute("aria-activedescendant")!)).toBe(selectedOptions[0]);
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

  it("uses a valid member price only for an active member", () => {
    expect(effectiveSaleProductPrice({
      ...products[0],
      id: "member",
      salePrice: 10,
      memberPrice: 8.5,
      discountType: "MEMBER_PRICE"
    }, true)).toBe(8.5);
    expect(effectiveSaleProductPrice({
      ...products[0],
      id: "member",
      salePrice: 10,
      memberPrice: 8.5,
      discountType: "MEMBER_PRICE"
    }, false)).toBe(10);
    expect(effectiveSaleProductPrice({
      ...products[0],
      id: "member",
      salePrice: 10,
      memberPrice: 0,
      discountType: "MEMBER_PRICE"
    }, true)).toBe(10);
    expect(effectiveSaleProductPrice({ ...products[0], id: "normal", salePrice: 10 }, true)).toBe(10);
  });

  it("displays a current offer price and falls back after expiry", () => {

    const offered: SaleProduct = {
      ...products[0],
      id: "offer",
      salePrice: 10,
      offerPrice: 7.5,
      priceUseMode: "OFFER_PRICE",
      offerActive: true,
      offerFrom: "2026-07-01",
      offerUntil: "2026-07-31"
    };
    expect(saleOfferIsCurrent(offered, "2026-07-11")).toBe(true);
    expect(effectiveSaleProductPrice(offered, false, "2026-07-11")).toBe(7.5);
    expect(effectiveSaleProductPrice(offered, false, "2026-08-01")).toBe(10);
    expect(effectiveSaleProductPrice({
      ...offered,
      offerPrice: null,
      offerDiscountPercent: 25,
      priceUseMode: "OFFER_DISCOUNT"
    }, false, "2026-07-11")).toBe(7.5);
    expect(effectiveSaleProductPrice({ ...offered, offerActive: false }, false, "2026-07-11")).toBe(10);
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

  it("selects ticket lines after vertical arrows and stops at the boundaries", () => {
    const lines = addSaleLine(addSaleLine([], products[0]), products[1]);

    expect(saleLineSelectionAfterArrow([], null, "ArrowDown")).toBeNull();
    expect(saleLineSelectionAfterArrow(lines, null, "ArrowDown")).toBe("coffee");
    expect(saleLineSelectionAfterArrow(lines, null, "ArrowUp")).toBe("bread");
    expect(saleLineSelectionAfterArrow(lines, "coffee", "ArrowDown")).toBe("bread");
    expect(saleLineSelectionAfterArrow(lines, "bread", "ArrowUp")).toBe("coffee");
    expect(saleLineSelectionAfterArrow(lines, "coffee", "ArrowUp")).toBe("coffee");
    expect(saleLineSelectionAfterArrow(lines, "bread", "ArrowDown")).toBe("bread");
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

  it("applies the member tier discount to every product line", () => {
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
    expect(discounted[1].memberDiscountPercent).toBe(5);
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

  it("uses a greater member tier discount than the manual discount", () => {
    const manuallyDiscounted = updateSaleLineDiscount(addSaleLine([], memberDiscountProduct), "member-coffee", 3);
    const bronze: SaleCustomer = { id: "bronze", activeMember: true, memberDiscountPercent: 5 };

    expect(effectiveSaleLineDiscount(applyMemberDiscounts(manuallyDiscounted, bronze)[0])).toBe(5);
  });

  it("applies member discount to a product added after selecting the customer", () => {
    const bronze: SaleCustomer = { id: "bronze", activeMember: true, memberDiscountPercent: 5 };
    const added = applyMemberDiscounts(addSaleLine([], memberDiscountProduct), bronze);

    expect(saleTotal(added)).toBe(9.5);
  });

  it("applies member discount when the customer is selected after adding a product", () => {
    const bronze: SaleCustomer = { id: "bronze", activeMember: true, memberDiscountPercent: 5 };
    const addedBeforeSelection = addSaleLine([], products[0]);

    expect(saleTotal(applyMemberDiscounts(addedBeforeSelection, bronze))).toBe(9.5);
  });

  it("keeps member pricing backend-authoritative and serializes only the manual discount", () => {
    const member: SaleCustomer = { id: "member-customer", activeMember: true, memberDiscountPercent: 5 };
    const lines = [{ ...addSaleLine([], memberDiscountProduct)[0], discountPercent: 3, memberDiscountPercent: 5 }];

    const pending = pendingSaleDraftForCustomer(lines, member, "warehouse-1", new Date(2026, 6, 16), "checkout-1");

    expect(pending.customerId).toBe("member-customer");
    expect(pending.lines[0].discount).toBe("3.00");
    expect(pending.lines[0]).not.toHaveProperty("memberDiscountPercent");
  });

  it("uses the selected customer's configured payment term", () => {
    const pending = pendingSaleDraftForCustomer(
      addSaleLine([], products[0]),
      { ...customers[0], paymentTermDays: 15 },
      "warehouse-1",
      new Date(2026, 6, 16, 23, 30),
      "checkout-terms",
    );

    expect(pending.date).toBe("2026-07-16");
    expect(pending.dueDate).toBe("2026-07-31");
  });

  it("requires a valid fiscal percentage and regime for every pending-sale line", () => {
    const validLines = addSaleLine([], products[0]);
    const customer = customers[0];
    const now = new Date(2026, 6, 16);

    expect(pendingSaleDraftForCustomer(validLines, customer, "warehouse-1", now, "checkout-1")
      .lines[0]).toMatchObject({ taxPercentage: "21.00", taxRegime: "IVA" });

    expect(() => pendingSaleDraftForCustomer(
      [{ ...validLines[0], product: { ...validLines[0].product, taxPercentage: undefined as never } }],
      customer, "warehouse-1", now, "checkout-1",
    )).toThrow("Producto sin porcentaje fiscal válido");

    expect(() => pendingSaleDraftForCustomer(
      [{ ...validLines[0], product: { ...validLines[0].product, taxRegime: "GENERAL" as never } }],
      customer, "warehouse-1", now, "checkout-1",
    )).toThrow("Producto sin régimen fiscal válido");
  });

  it("rejects empty, blank, and null fiscal percentages", () => {
    const validLine = addSaleLine([], products[0])[0];
    const customer = customers[0];
    const now = new Date(2026, 6, 16);

    for (const taxPercentage of ["", " ", null]) {
      expect(() => pendingSaleDraftForCustomer(
        [{ ...validLine, product: { ...validLine.product, taxPercentage: taxPercentage as never } }],
        customer, "warehouse-1", now, "checkout-1",
      )).toThrow("Producto sin porcentaje fiscal válido");
    }
  });

  it("rejects missing, null, and non-boolean tax inclusion flags", () => {
    const validLine = addSaleLine([], products[0])[0];
    const customer = customers[0];
    const now = new Date(2026, 6, 16);
    const { taxesIncluded: _taxesIncluded, ...withoutTaxesIncluded } = validLine.product;

    for (const product of [
      withoutTaxesIncluded,
      { ...validLine.product, taxesIncluded: undefined as never },
      { ...validLine.product, taxesIncluded: null as never },
      { ...validLine.product, taxesIncluded: "true" as never },
    ]) {
      expect(() => pendingSaleDraftForCustomer(
        [{ ...validLine, product: product as SaleProduct }],
        customer, "warehouse-1", now, "checkout-1",
      )).toThrow("Producto sin configuración de impuestos válida");
    }
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

  it("opens direct cash completion in PRINTING with the authoritative snapshot", () => {
    const snapshot = printSnapshot("DIRECT-CASH");

    expect(cashPaymentResultForAutomaticPrinting(
      { number: "DIRECT-CASH", total: "12.10", change: "7.90", printTicket: snapshot },
      1210,
      2000,
    )).toEqual({
      ticketNumber: "DIRECT-CASH",
      totalCents: 1210,
      receivedCents: 2000,
      changeCents: 790,
      printTicket: snapshot,
      printStatus: "PRINTING",
    });
  });

  it("retains the automatic print technical failure for diagnostics without exposing it as UI text", () => {
    const current = cashPaymentResultForAutomaticPrinting(
      { number: "T-DIAG", total: "12.10", change: "0.00", printTicket: printSnapshot("T-DIAG") },
      1210,
      1210,
    );

    expect(updateCashResultPrintOutcome(current, "document-T-DIAG", {
      status: "FAILED",
      technicalMessage: "USB endpoint stalled",
    })).toMatchObject({
      printStatus: "FAILED",
      printTechnicalMessage: "USB endpoint stalled",
    });
  });

  it("replaces the retained diagnostic when a retry fails for a different technical reason", () => {
    const current = {
      ...cashPaymentResultForAutomaticPrinting(
        { number: "T-RETRY", total: "12.10", change: "0.00", printTicket: printSnapshot("T-RETRY") },
        1210,
        1210,
      ),
      printStatus: "PRINTING" as const,
      printTechnicalMessage: "paper jam",
    };

    expect(updateCashResultPrintOutcome(current, "document-T-RETRY", {
      status: "FAILED",
      technicalMessage: "printer offline",
    })).toMatchObject({
      printStatus: "FAILED",
      printTechnicalMessage: "printer offline",
    });
  });

  it("sends only the manual discount when member pricing is active", async () => {
    const user = userEvent.setup();
    const activeMember: SaleCustomer = {
      id: "member-customer",
      fiscalName: "Cliente Bronce",
      activeMember: true,
      memberDiscountPercent: 5,
    };
    const snapshot: ConfirmedTicketPrintSnapshot = {
      ...printSnapshot("DIRECT-UI"),
      total: "10.00",
      lines: [{ name: "Cafe molido", quantity: "1", price: "10.00", total: "10.00" }],
      payments: [{ method: "EFECTIVO", amount: "10.00" }],
    };
    const fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) return new Response(JSON.stringify([memberDiscountProduct]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/customers/sale-options")) return new Response(JSON.stringify([activeMember]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/cash/quote")) return new Response(JSON.stringify({ total: "10.00" }), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/cash")) {
        expect(options?.method).toBe("POST");
        const request = JSON.parse(String(options?.body));
        expect(request.sale).toEqual({
          customerId: "member-customer",
          lines: [{ productId: "member-coffee", quantity: 1, discount: 3 }],
        });
        return new Response(JSON.stringify({ number: "DIRECT-UI", total: "10.00", change: "10.00", printTicket: snapshot }), { status: 200, headers: { "Content-Type": "application/json" } });
      }
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    let failFirstPrint!: (result: { ok: false; code: "PRINT_FAILED"; message: string }) => void;
    const printTicket = vi.fn()
      .mockImplementationOnce(() => new Promise((resolve) => { failFirstPrint = resolve; }))
      .mockResolvedValueOnce({ ok: true });
    installTicketHardware(printTicket);
    renderSaleScreen();

    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "MEM-CAFE" } });
    fireEvent.click(await screen.findByRole("option", { name: /Cafe socio/ }));
    fireEvent.keyDown(window, { key: "F7" });
    await user.keyboard("3{Enter}");
    fireEvent.keyDown(window, { key: "F6" });
    fireEvent.click(await screen.findByRole("button", { name: /Cliente Bronce/ }));
    fireEvent.click(screen.getByRole("button", { name: /Efectivo.*AvPág/ }));
    const cashDialog = await screen.findByRole("dialog", { name: "Cobro en efectivo" });
    fireEvent.click(within(cashDialog).getByRole("button", { name: /20/ }));
    fireEvent.click(within(cashDialog).getByRole("button", { name: "Confirmar cobro" }));

    expect(await screen.findByText("Pago completado")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Imprimiendo ticket");
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(1));
    expect(printTicket).toHaveBeenNthCalledWith(1, expect.objectContaining({ documentNumber: "DIRECT-UI" }), expect.anything());
    failFirstPrint({ ok: false, code: "PRINT_FAILED", message: "paper jam" });
    expect(await screen.findByRole("alert")).toHaveTextContent("El cobro se ha completado");
    expect(screen.getByRole("button", { name: "Finalizar" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Reintentar impresión" }));

    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(2));
    expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url), "http://localhost").pathname.endsWith("/pos/cash"))).toHaveLength(1);
    expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url), "http://localhost").pathname.endsWith("/finalize"))).toHaveLength(0);
  });

  it("excludes duplicate cash quotes and ignores a quote resolved after finalization", async () => {
    let resolveQuote!: (response: Response) => void;
    const pendingQuote = new Promise<Response>((resolve) => { resolveQuote = resolve; });
    const fetchMock = vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) return new Response(JSON.stringify([products[0]]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/cash/quote")) return pendingQuote;
      throw new Error(`unexpected request ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "CAF-001" } });
    fireEvent.click(await screen.findByRole("option", { name: /Cafe molido/ }));

    const cashAction = screen.getByRole("button", { name: /Efectivo.*AvPág/ });
    fireEvent.click(cashAction);
    fireEvent.click(cashAction);
    expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url), "http://localhost").pathname.endsWith("/pos/cash/quote"))).toHaveLength(1);
    expect(cashAction).toBeDisabled();
    act(() => checkoutProps.current?.onFinalized(printSnapshot("CARD-WINS"), { kind: "CARD", totalCents: 1000 }));
    resolveQuote(new Response(JSON.stringify({ total: "10.00" }), { status: 200, headers: { "Content-Type": "application/json" } }));
    await act(async () => { await Promise.resolve(); });

    expect(screen.getByText("CARD-WINS")).toBeInTheDocument();
    expect(screen.queryByRole("dialog", { name: "Cobro en efectivo" })).not.toBeInTheDocument();
  });

  it("ignores an obsolete quote rejection after another payment finalizes", async () => {
    let rejectQuote!: (error: Error) => void;
    const pendingQuote = new Promise<Response>((_resolve, reject) => { rejectQuote = reject; });
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url, "http://localhost").pathname;
      if (path.endsWith("/products/sale")) return new Response(JSON.stringify([products[0]]), { status: 200, headers: { "Content-Type": "application/json" } });
      if (path.endsWith("/pos/cash/quote")) return pendingQuote;
      throw new Error(`unexpected request ${path}`);
    }));
    renderSaleScreen();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "CAF-001" } });
    fireEvent.click(await screen.findByRole("option", { name: /Cafe molido/ }));
    fireEvent.click(screen.getByRole("button", { name: /Efectivo.*AvPág/ }));
    act(() => checkoutProps.current?.onFinalized(printSnapshot("CARD-WINS-ERROR"), { kind: "CARD", totalCents: 1000 }));

    rejectQuote(new Error("stale quote failure"));
    await act(async () => { await Promise.resolve(); });

    expect(screen.getByText("CARD-WINS-ERROR")).toBeInTheDocument();
    expect(screen.queryByText("stale quote failure")).not.toBeInTheDocument();
    expect(screen.queryByRole("dialog", { name: "Cobro en efectivo" })).not.toBeInTheDocument();
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
