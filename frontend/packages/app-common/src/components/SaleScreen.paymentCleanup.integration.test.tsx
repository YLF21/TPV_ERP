// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import type { TerminalContext, UserSession } from "../types";
import { SaleScreen } from "./SaleScreen";
import { prepareOfflineApplicationClose } from "../sale/offlineClosePreparation";

const { apiRequestMock } = vi.hoisted(() => ({ apiRequestMock: vi.fn() }));
vi.mock("../api/client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/client")>()),
  apiRequest: apiRequestMock
}));

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"],
  accessToken: "access-token",
};
const terminal: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01",
  terminalId: "01",
};
const oldSession = {
  id: "task-4-old-session",
  total: "12.10",
  status: "COLLECTING",
  allocations: [{ id: "old-allocation", idempotencyKey: "old-allocation", kind: "INTEGRATED_CARD", amount: "12.10", status: "PENDING" }]
};
const configuration = { rules: { cardManualEnabled: false, integratedCardEnabled: true }, providerDescriptors: [{ provider: "REDSYS_TPV_PC", capabilities: [] }], configuration: { provider: "REDSYS_TPV_PC", enabled: true } };
const product = {
  id: "coffee",
  code: "CAF-001",
  barcode: "8410000000011",
  name: "Cafe molido",
  salePrice: 10,
  taxId: "tax-iva-21",
  taxesIncluded: true,
  taxRegime: "IVA" as const,
  taxPercentage: 21,
};

const authoritativeQuote = {
  total: "10.00",
  productTotal: "10.00",
  promotionPreview: { appliedPromotions: [] },
  pricingVersion: 2,
  quoteFingerprint: "payment-cleanup-quote",
  lineBreakdown: [{
    lineId: "product:coffee:1", position: 1, productId: "coffee", code: "CAF-001",
    name: "Cafe molido", quantity: "1.000", normalUnitPrice: "10.00",
    memberUnitPrice: null, baseUnitPrice: "10.00", priceSource: "SALE",
    memberPriceSaving: "0.00", memberDiscountPercent: "0.00", memberDiscount: "0.00",
    manualDiscountPercent: "0.00", manualDiscount: "0.00", promotionDiscount: "0.00",
    couponDiscount: "0.00", taxIncluded: true, taxRegime: "IVA", taxPercent: "21.00",
    taxBase: "8.26", tax: "1.74", baseSubtotal: "10.00", commercialSubtotal: "10.00", roundingAdjustment: "0.00",
    finalSubtotal: "10.00",
  }],
};

function mount(onLogout = vi.fn()) {
  return render(<SaleScreen app="venta" locale="es" session={session} terminalContext={terminal} interfaceMode="TOUCH" onBack={vi.fn()} onLocaleChange={vi.fn()} onLogout={onLogout} />);
}

afterEach(() => {
  cleanup();
  apiRequestMock.mockReset();
  localStorage.clear();
  sessionStorage.clear();
  delete window.tpvDesktop;
});

describe("SaleScreen payment cleanup across restart", () => {
  it("blocks real-sale payment shortcuts until hydration and the authoritative quote complete", async () => {
    let resolveActive!: (value: null) => void;
    const activeResponse = new Promise<null>((resolve) => { resolveActive = resolve; });
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === "/cash/sessions/prepare-sales") {
        return { cashSessionRequired: false, open: true, session: null };
      }
      if (path === "/products/sale") return [product];
      if (path === "/pos/sales/quote") return authoritativeQuote;
      if (path === "/terminal-configuration/payment") return configuration;
      if (path === "/pos/payment-sessions/active") return activeResponse;
      if (path === "/pos/payment-sessions") return { id: "new-card-session", total: "10.00", status: "COLLECTING", allocations: [] };
      if (path === "/pos/payment-sessions/new-card-session/allocations") return { id: "new-card-session", total: "10.00", status: "COLLECTING", allocations: [{ id: "card-allocation", idempotencyKey: "card-allocation", kind: "INTEGRATED_CARD", amount: "10.00", status: "DECLINED" }] };
      if (path === "/pos/cash/quote") return { total: "10.00" };
      if (path === "/customers/sale-options") return [];
      throw new Error(`unexpected request ${path}`);
    });

    mount();
    await waitFor(() => expect(apiRequestMock).toHaveBeenCalledWith("/pos/payment-sessions/active", expect.anything()));
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    fireEvent.change(search, { target: { value: "CAF-001" } });
    fireEvent.keyDown(search, { key: "Enter" });

    expect(screen.queryByText(/Venta reservada en cobro/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cobrar" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: /Efectivo/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Tarjeta/ })).not.toBeInTheDocument();
    fireEvent.keyDown(window, { key: "PageDown" });
    expect(apiRequestMock.mock.calls.filter(([path]) => path === "/pos/cash/quote")).toHaveLength(0);
    expect(apiRequestMock.mock.calls.filter(([path]) => path === "/pos/payment-sessions")).toHaveLength(0);
    expect(apiRequestMock.mock.calls.filter(([path]) => path === "/customers/sale-options")).toHaveLength(0);

    await act(async () => { resolveActive(null); await activeResponse; });
    await waitFor(() => expect(screen.getByRole("button", { name: "Cobrar" })).toBeEnabled());

    fireEvent.keyDown(window, { key: "PageDown" });
    expect(await screen.findByRole("dialog", { name: "COBRO" }, { timeout: 3000 })).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole("button", { name: "CANCELAR" })[0]);
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "COBRO" })).not.toBeInTheDocument());
  });

  it("reopens as an ordinary empty sale after stale simulator cleanup is confirmed CANCELLED", async () => {
    const storageKey = "tpverp.payment-session.01";
    localStorage.setItem(`${storageKey}.allocation-attempt`, "old-attempt");
    let activeCalls = 0;
    apiRequestMock.mockImplementation(async (path: string, options?: { body?: unknown }) => {
      if (path === "/cash/sessions/prepare-sales") {
        return { cashSessionRequired: false, open: true, session: null };
      }
      if (path === "/products/sale") return [];
      if (path === "/terminal-configuration/payment") return configuration;
      if (path === "/pos/payment-sessions/active") return activeCalls++ === 0 ? oldSession : null;
      if (path.endsWith("/simulator-discard")) {
        expect(options?.body).toEqual({ reason: "sale_entry_cleanup" });
        return { ...oldSession, status: "CANCELLED" };
      }
      throw new Error(`unexpected request ${path}`);
    });

    const first = mount();
    await waitFor(() => expect(apiRequestMock.mock.calls.filter(([path]) => path.endsWith("/simulator-discard"))).toHaveLength(1));
    await waitFor(() => expect(sessionStorage.getItem(storageKey)).toBeNull());
    await waitFor(() => expect(localStorage.getItem(`${storageKey}.allocation-attempt`)).toBeNull());
    first.unmount();

    mount();
    await waitFor(() => expect(activeCalls).toBe(2));
    expect(await screen.findByText("0,00", { selector: ".sale-total strong" })).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Líneas del ticket" })).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Líneas del ticket" }).querySelectorAll(".sale-cart-row")).toHaveLength(0);
    expect(screen.queryByText("Cobro pendiente")).not.toBeInTheDocument();
    expect(screen.queryByText(/El ticket está reservado/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cobrar" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: /Efectivo/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Tarjeta/ })).not.toBeInTheDocument();
  });

  it("fails closed for live rejection and logs out only after a later CANCELLED cleanup", async () => {
    const closeApplication = vi.fn().mockResolvedValue(undefined);
    window.tpvDesktop = { closeApplication };
    const onLogout = vi.fn();
    let discardCalls = 0;
    let allowCleanup = false;
    let resolveCleanup!: (value: typeof oldSession & { status: string }) => void;
    const pendingCleanup = new Promise<typeof oldSession & { status: string }>((resolve) => { resolveCleanup = resolve; });
    const liveSession = { ...oldSession, id: "task-4-live-session" };
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === "/cash/sessions/prepare-sales") {
        return { cashSessionRequired: false, open: true, session: null };
      }
      if (path === "/products/sale") return [];
      if (path === "/terminal-configuration/payment") return configuration;
      if (path === "/pos/payment-sessions/active") return liveSession;
      if (path.endsWith("/simulator-discard")) {
        discardCalls += 1;
        if (!allowCleanup) throw new ApiError("terminal live", 409);
        return pendingCleanup;
      }
      throw new Error(`unexpected request ${path}`);
    });
    mount(onLogout);

    await screen.findByText("12,10", { selector: ".sale-total strong" });
    await waitFor(() => expect(discardCalls).toBe(1));
    expect(screen.getByText(/El ticket está reservado/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Apagar" }));
    fireEvent.click(screen.getByRole("button", { name: "Sí" }));
    await waitFor(() => expect(discardCalls).toBe(2));
    expect(closeApplication).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "ADMIN" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Cerrar usuario" }));
    await waitFor(() => expect(discardCalls).toBe(3));
    expect(onLogout).not.toHaveBeenCalled();
    expect(apiRequestMock.mock.calls.filter(([path]) => path === "/pos/payment-sessions")).toHaveLength(0);
    expect(apiRequestMock.mock.calls.filter(([path]) => path.includes("/allocations"))).toHaveLength(0);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(discardCalls).toBe(3);

    allowCleanup = true;
    fireEvent.click(screen.getByRole("button", { name: "ADMIN" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Cerrar usuario" }));
    await waitFor(() => expect(discardCalls).toBe(4));
    expect(onLogout).not.toHaveBeenCalled();
    resolveCleanup({ ...liveSession, status: "CANCELLED" });
    await waitFor(() => expect(onLogout).toHaveBeenCalledTimes(1));
  });
});


describe("SaleScreen durable offline close", () => {
  const boundTerminal = { ...terminal, installationId: "installation", storeId: "store", bindingId: "binding" };
  const owner = { ...session, userId: "owner" };
  const mountBound = (user = owner) => render(<SaleScreen app="venta" locale="es" session={user} terminalContext={boundTerminal} onBack={vi.fn()} onLocaleChange={vi.fn()} onLogout={vi.fn()} />);
  it("saves a real ticket without backend mutations and restores it after browser storage is lost", async () => {
    let persisted: unknown = null;
    const save = vi.fn(async (value: unknown) => { persisted = value; return { ok: true as const }; });
    window.tpvDesktop = { closeApplication: vi.fn(async () => undefined), workRecovery: { load: vi.fn(async () => ({ ok: true as const, value: persisted })), save, clear: vi.fn(async () => ({ ok: true as const })) } };
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === "/cash/sessions/prepare-sales") return { cashSessionRequired: false, open: true, session: null };
      if (path === "/products/sale") return [product];
      if (path === "/pos/sales/quote") return authoritativeQuote;
      if (path === "/terminal-configuration/payment") return configuration;
      if (path === "/pos/payment-sessions/active") return null;
      if (path === "/customers/sale-options" || path === "/vouchers") return [];
      throw new Error(`unexpected request ${path}`);
    });
    const first = mountBound();
    const search = await screen.findByRole("combobox", { name: "Buscar producto" });
    await waitFor(() => expect(search).toBeEnabled());
    fireEvent.change(search, { target: { value: "CAF-001" } });
    fireEvent.keyDown(search, { key: "Enter" });
    await waitFor(() => expect(screen.getByRole("table", { name: "Líneas del ticket" }).querySelectorAll(".sale-cart-row")).toHaveLength(1));
    const before = apiRequestMock.mock.calls.length;
    await act(() => prepareOfflineApplicationClose());
    expect(save).toHaveBeenCalledTimes(1);
    expect(persisted).toMatchObject({ schemaVersion: 1, ownerUserId: "owner", installationId: "installation", ticket: { lines: [{ product: { id: "coffee" }, quantity: 1 }] } });
    expect(apiRequestMock.mock.calls.slice(before).some(([path]) => /cancel|discard|park|compensat|finalize/.test(String(path)))).toBe(false);
    first.unmount(); localStorage.clear(); sessionStorage.clear();
    mountBound();
    await screen.findByRole("table", { name: "Líneas del ticket" });
    await waitFor(() => expect(screen.getByRole("table", { name: "Líneas del ticket" }).querySelectorAll(".sale-cart-row")).toHaveLength(1));
  });
  it("blocks another user without mounting checkout or exposing the saved ticket", async () => {
    window.tpvDesktop = { closeApplication: vi.fn(async () => undefined), workRecovery: { load: vi.fn(async () => ({ ok: true as const, value: { schemaVersion: 1, ownerUserId: "different-owner", installationId: "installation", storeId: "store", terminalId: "01", bindingId: "binding", ticket: { lines: [{ product, quantity: 1 }] } } })), save: vi.fn(), clear: vi.fn() } };
    mountBound();
    expect(await screen.findByRole("alert")).toHaveTextContent("usuario original");
    expect(screen.queryByRole("table", { name: "Líneas del ticket" })).not.toBeInTheDocument();
    expect(apiRequestMock).not.toHaveBeenCalled();
    expect(window.tpvDesktop?.workRecovery?.clear).not.toHaveBeenCalled();
  });
});


it("blocks ordinary exit and offers retry if a consumed recovery cannot be deleted or replaced", async () => {
  const owner = { ...session, userId: "owner" };
  const boundTerminal = { ...terminal, installationId: "installation", storeId: "store", bindingId: "binding" };
  const value = { schemaVersion: 1, ownerUserId: "owner", installationId: "installation", storeId: "store", terminalId: "01", bindingId: "binding", ticket: { lines: [] }, paymentSessionId: null, allocationAttempt: null, cashAttempt: null, memberReservation: null, pendingSale: null, cashClose: null };
  const clear = vi.fn().mockResolvedValue({ ok: false, code: "DISK", message: "disk" });
  const save = vi.fn().mockResolvedValue({ ok: false, code: "DISK", message: "disk" });
  window.tpvDesktop = { closeApplication: vi.fn(async () => undefined), workRecovery: { load: vi.fn(async () => ({ ok: true as const, value })), clear, save } };
  apiRequestMock.mockImplementation(async (path: string) => {
    if (path === "/cash/sessions/prepare-sales") return { cashSessionRequired: false, open: true, session: null };
    if (path === "/products/sale" || path === "/vouchers") return [];
    if (path === "/terminal-configuration/payment") return configuration;
    if (path === "/pos/payment-sessions/active") return null;
    throw new Error(`unexpected request ${path}`);
  });
  const blocked = vi.fn();
  render(<SaleScreen app="venta" locale="es" session={owner} terminalContext={boundTerminal} onBack={vi.fn()} onLocaleChange={vi.fn()} onExitBlockedChange={blocked} />);
  const message = await screen.findByText("No se pudo actualizar la recuperación guardada. Reintenta antes de salir de Ventas.");
  await waitFor(() => expect(blocked).toHaveBeenLastCalledWith(true));
  const unload = new Event("beforeunload", { cancelable: true });
  window.dispatchEvent(unload);
  expect(unload.defaultPrevented).toBe(true);
  save.mockResolvedValue({ ok: true });
  fireEvent.click(message.parentElement!.querySelector("button")!);
  await waitFor(() => expect(screen.queryByText("No se pudo actualizar la recuperación guardada. Reintenta antes de salir de Ventas.")).not.toBeInTheDocument());
  await waitFor(() => expect(blocked).toHaveBeenLastCalledWith(false));
  expect(save).toHaveBeenLastCalledWith(expect.objectContaining({ tombstone: true, ticket: { lines: [] } }));
});


it("preserves restored member balance intention until reservation, wallet and pricing reconcile", async () => {
  const owner = { ...session, userId: "owner" };
  const boundTerminal = { ...terminal, installationId: "installation", storeId: "store", bindingId: "binding" };
  const member = { id: "member", fiscalName: "Member", activeMember: true, memberBalance: "20.00" };
  const snapshot = {
    schemaVersion: 1, ownerUserId: "owner", installationId: "installation", storeId: "store", terminalId: "01", bindingId: "binding",
    ticket: { lines: [{ cartLineId: "restored-line", product, quantity: 1, discountPercent: 0 }], selectedCustomer: member, memberBalanceCents: 500 },
    paymentSessionId: null, allocationAttempt: null, cashAttempt: null,
    memberReservation: JSON.stringify({ version: 1, customerId: "member", saleId: "saved-sale", reservationId: "saved-reservation" }),
    pendingSale: null, cashClose: null,
  };
  const save = vi.fn().mockResolvedValue({ ok: true });
  window.tpvDesktop = { closeApplication: vi.fn(async () => undefined), workRecovery: { load: vi.fn(async () => ({ ok: true as const, value: snapshot })), clear: vi.fn(), save } };
  let resolveHeartbeat!: (value: unknown) => void;
  const heartbeat = new Promise(resolve => { resolveHeartbeat = resolve; });
  let resolveWallet!: (value: unknown) => void;
  const wallet = new Promise(resolve => { resolveWallet = resolve; });
  apiRequestMock.mockImplementation(async (path: string, options?: { body?: unknown }) => {
    if (path === "/cash/sessions/prepare-sales") return { cashSessionRequired: false, open: true, session: null };
    if (path === "/products/sale") return [product];
    if (path === "/vouchers") return [];
    if (path === "/terminal-configuration/payment") return configuration;
    if (path === "/pos/payment-sessions/active") return null;
    if (path === "/member-balance-reservations/saved-reservation/heartbeat") return heartbeat;
    if (path === "/customers/member/member-wallet") return wallet;
    if (path === "/pos/sales/quote") {
      expect(options?.body).toMatchObject({ memberBalanceAmount: 5 });
      return { ...authoritativeQuote, total: "5.00", memberBalanceTotal: "5.00", memberBalanceEligibleTotal: "10.00", lineBreakdown: [...authoritativeQuote.lineBreakdown, { ...authoritativeQuote.lineBreakdown[0], lineId: "member-balance", lineType: "MEMBER_BALANCE", productId: null, finalSubtotal: "-5.00", commercialSubtotal: "0.00" }] };
    }
    throw new Error(`unexpected request ${path}`);
  });
  render(<SaleScreen app="venta" locale="es" interfaceMode="TOUCH" session={owner} terminalContext={boundTerminal} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
  await waitFor(() => expect(apiRequestMock).toHaveBeenCalledWith("/member-balance-reservations/saved-reservation/heartbeat", expect.objectContaining({ body: { saleId: "saved-sale" } })));
  expect(screen.getByRole("button", { name: "Cobrar" })).toBeDisabled();
  await act(() => prepareOfflineApplicationClose());
  expect(save).toHaveBeenLastCalledWith(expect.objectContaining({ ticket: expect.objectContaining({ memberBalanceCents: 500 }) }));
  await act(async () => { resolveHeartbeat({ status: "ACTIVE", reservedLoyaltyAmount: "20.00", reservedReturnCreditAmount: "0.00" }); await heartbeat; });
  expect(screen.getByRole("button", { name: "Cobrar" })).toBeDisabled();
  await act(async () => { resolveWallet({ loyaltyAvailable: "20.00", returnCreditAvailable: "0.00", totalAvailable: "20.00", lots: [] }); await wallet; });
  await waitFor(() => expect(screen.getByRole("button", { name: "Cobrar" })).toBeEnabled());
  await act(() => prepareOfflineApplicationClose());
  expect(save).toHaveBeenLastCalledWith(expect.objectContaining({ ticket: expect.objectContaining({ memberBalanceCents: 500 }), memberReservation: snapshot.memberReservation }));
  expect(apiRequestMock.mock.calls.some(([path]) => path === "/member-balance-reservations" || path === "/pos/payment-sessions")).toBe(false);
});
