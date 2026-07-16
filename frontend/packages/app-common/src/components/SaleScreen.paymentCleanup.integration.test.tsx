// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import type { TerminalContext, UserSession } from "../types";
import { SaleScreen } from "./SaleScreen";

const { apiRequestMock } = vi.hoisted(() => ({ apiRequestMock: vi.fn() }));
vi.mock("../api/client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/client")>()),
  apiRequest: apiRequestMock
}));

const session: UserSession = { username: "admin", displayName: "ADMIN", permissions: ["ADMIN"] };
const terminal: TerminalContext = { storeName: "Tienda Principal", terminalCode: "01" };
const oldSession = {
  id: "task-4-old-session",
  total: "12.10",
  status: "COLLECTING",
  allocations: [{ id: "old-allocation", idempotencyKey: "old-allocation", kind: "INTEGRATED_CARD", amount: "12.10", status: "PENDING" }]
};
const configuration = { rules: { cardManualEnabled: false, integratedCardEnabled: true }, providerDescriptors: [{ provider: "REDSYS_TPV_PC", capabilities: [] }], configuration: { provider: "REDSYS_TPV_PC", enabled: true } };
const product = { id: "coffee", code: "CAF-001", barcode: "8410000000011", name: "Cafe molido", salePrice: 10 };

function mount(onLogout = vi.fn()) {
  return render(<SaleScreen app="venta" locale="es" session={session} terminalContext={terminal} onBack={vi.fn()} onLocaleChange={vi.fn()} onLogout={onLogout} />);
}

afterEach(() => {
  cleanup();
  apiRequestMock.mockReset();
  localStorage.clear();
  sessionStorage.clear();
  delete window.tpvDesktop;
});

describe("SaleScreen payment cleanup across restart", () => {
  it("blocks real-sale payment shortcuts during hydration and enables them after authoritative absence", async () => {
    let resolveActive!: (value: null) => void;
    const activeResponse = new Promise<null>((resolve) => { resolveActive = resolve; });
    apiRequestMock.mockImplementation(async (path: string) => {
      if (path === "/products") return [product];
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
    fireEvent.click(await screen.findByRole("option", { name: /Cafe molido/ }));

    expect(screen.queryByText(/Venta reservada en cobro/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Efectivo/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /Tarjeta/ })).toBeDisabled();
    fireEvent.keyDown(window, { key: "PageDown" });
    fireEvent.keyDown(window, { key: "F11" });
    fireEvent.keyDown(window, { key: "F12" });
    expect(apiRequestMock.mock.calls.filter(([path]) => path === "/pos/cash/quote")).toHaveLength(0);
    expect(apiRequestMock.mock.calls.filter(([path]) => path === "/pos/payment-sessions")).toHaveLength(0);
    expect(apiRequestMock.mock.calls.filter(([path]) => path === "/customers/sale-options")).toHaveLength(0);

    await act(async () => { resolveActive(null); await activeResponse; });
    await waitFor(() => expect(screen.getByRole("button", { name: /Efectivo/ })).toBeEnabled());
    expect(screen.getByRole("button", { name: /Tarjeta/ })).toBeEnabled();

    fireEvent.keyDown(window, { key: "F12" });
    expect(await screen.findByRole("dialog", { name: "Seleccionar cliente" })).toBeInTheDocument();
    fireEvent.click(screen.getByText("Cerrar", { selector: "button" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Seleccionar cliente" })).not.toBeInTheDocument());
    fireEvent.keyDown(window, { key: "PageDown" });
    expect(await screen.findByRole("dialog", { name: "Cobro en efectivo" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Cancelar" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Cobro en efectivo" })).not.toBeInTheDocument());
    fireEvent.keyDown(window, { key: "F11" });
    await waitFor(() => expect(apiRequestMock.mock.calls.filter(([path]) => path === "/pos/payment-sessions")).toHaveLength(1));
  });

  it("reopens as an ordinary empty sale after stale simulator cleanup is confirmed CANCELLED", async () => {
    const storageKey = "tpverp.payment-session.01";
    localStorage.setItem(`${storageKey}.allocation-attempt`, "old-attempt");
    let activeCalls = 0;
    apiRequestMock.mockImplementation(async (path: string, options?: { body?: unknown }) => {
      if (path === "/products") return [];
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
    expect(localStorage.getItem(`${storageKey}.allocation-attempt`)).toBeNull();
    first.unmount();

    mount();
    await waitFor(() => expect(activeCalls).toBe(2));
    expect(await screen.findByText("0,00", { selector: ".sale-total strong" })).toBeInTheDocument();
    expect(screen.getAllByText("Sin venta iniciada").length).toBeGreaterThan(0);
    expect(screen.queryByText("Cobro pendiente")).not.toBeInTheDocument();
    expect(screen.queryByText(/Venta reservada en cobro/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Efectivo/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /Tarjeta/ })).toBeDisabled();
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
      if (path === "/products") return [];
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
    expect(screen.getByText(/Venta reservada en cobro/)).toBeInTheDocument();

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
