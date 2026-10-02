// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "../api/client";
import { defaultHardwareConfig } from "../hardware/hardware";
import { saveCashCloseRecovery } from "../sale/cashCloseRecovery";
import type { CashDenominationCount } from "./CashDenominationDialog";
import { CashOperationsCard } from "./CashOperationsCard";
import { createCashCloseUiFlow } from "./SaleCashSessionDialog";

const hardware = vi.hoisted(() => ({ getHardwareConfig: vi.fn(), printTicket: vi.fn() }));
vi.mock("../hardware/hardware", async () => ({
  ...await vi.importActual<typeof import("../hardware/hardware")>("../hardware/hardware"),
  getHardwareBridge: () => hardware,
}));

vi.mock("./tableLayoutPreferences", async () => ({
  ...await vi.importActual<typeof import("./tableLayoutPreferences")>("./tableLayoutPreferences"),
  loadTablePreference: vi.fn().mockResolvedValue(null),
  saveTablePreference: vi.fn(async (app, tableKey, columns) => ({ app, tableKey, columns })),
}));

beforeEach(() => {
  hardware.getHardwareConfig.mockReset().mockResolvedValue(defaultHardwareConfig);
  hardware.printTicket.mockReset().mockResolvedValue({ ok: true });
});
afterEach(() => { cleanup(); localStorage.clear(); vi.clearAllMocks(); });

const openSession = { id: "session-1", terminalId: "terminal-1", status: "ABIERTA", openedAt: "2026-10-01T08:00:00Z", openingFund: 200, expectedCash: 215, availableCash: 215, retainedFund: 5 };
const readiness = { cashSessionRequired: true, open: true, session: openSession, requireEntryBreakdown: false, entryDenominations: [100, 50, 20, 10, 5], requireWithdrawalBreakdown: false, withdrawalDenominations: [100, 50, 20, 10, 5], requireClosingBreakdown: false, closingDenominations: [100, 50, 20, 10, 5] };
const operation = (code: string, permissions: string[] = []) => ({ code, category: "CASH", shortcuts: [], permissions, defaultRequirePermission: true, defaultRequirePassword: true, requirePermission: true, requirePassword: true, customized: false });

function createRequest(options: { session?: boolean; view?: unknown; security?: unknown; close?: () => Promise<unknown>; movement?: () => Promise<unknown>; readiness?: unknown; recovery?: () => Promise<unknown> } = {}) {
  let hasSession = options.session !== false;
  return vi.fn(async (path: string, init?: { method?: string; body?: Record<string, unknown> }) => {
    if (path.startsWith("/cash/status")) {
      if (!hasSession) throw new ApiError("No hay una sesión de caja abierta", 404, { detail: "No hay una sesión de caja abierta" });
      return options.view ?? openSession;
    }
    if (path.startsWith("/cash/sessions/readiness")) return options.readiness ?? readiness;
    if (path === "/sales/operation-security") return options.security ?? { storeId: "store-1", version: 1, operations: [operation("CLOSE_CASH_SESSION", ["CASH_CLOSE"]), operation("CASH_MOVEMENT", ["CASH_MOVE"])] };
    if (path === "/cash/sessions/open" && init?.method === "POST") { hasSession = true; return openSession; }
    if (path === "/cash/sessions/prepare-sales" && init?.method === "POST") { hasSession = true; return { ...readiness, open: true, session: openSession }; }
    if (path.startsWith("/cash/sessions/close-operations/")) return options.recovery?.() ?? { status: "INICIADA", sessionId: "session-1", terminalId: "terminal-1", finalWithdrawalAmount: 180 };
    if (path === "/cash/sessions/close" && init?.method === "POST") {
      const result = await (options.close?.() ?? Promise.resolve({ ...openSession, status: "CERRADA" }));
      if ((result as { status?: string })?.status === "CERRADA") hasSession = false;
      return result;
    }
    if (path.startsWith("/cash/movements/") && init?.method === "POST") return options.movement?.() ?? { id: "new-movement" };
    if (path.startsWith("/cash/receipts/")) return {
      fileName: path.includes("/entries/") ? "entrada.pdf" : "retirada.pdf",
      renderedPdf: { contentType: "application/pdf", base64: "rendered-pdf" },
      ticketRenderedImage: { contentType: "image/png", base64: "rendered-raster" },
    };
    return undefined;
  });
}

function renderCard(request: ReturnType<typeof createRequest>, permissions: string[] = []) {
  return render(<CashOperationsCard locale="es" token="token" currentUsername="ana" terminalId="terminal-1"
    terminalCode="T1" storeName="Tienda" permissions={permissions} request={request as unknown as typeof apiRequest} />);
}

const printableMovements = [
  { session: true, entry: true, navigation: "Entrada", submit: "Registrar entrada", post: "/cash/movements/entry", receipt: "entries" },
  { session: true, entry: false, navigation: "Retirada", submit: "Registrar retirada", post: "/cash/movements/withdrawal", receipt: "withdrawals" },
  { session: false, entry: true, navigation: "Entrada entre sesiones", submit: "Registrar entrada entre sesiones", post: "/cash/movements/between-sessions", receipt: "entries" },
  { session: false, entry: false, navigation: "Retirada entre sesiones", submit: "Registrar retirada entre sesiones", post: "/cash/movements/between-sessions", receipt: "withdrawals" },
] as const;

async function fillMovement(navigation: string, session: boolean) {
  fireEvent.click(await screen.findByRole("button", { name: navigation }));
  fireEvent.change(screen.getByLabelText("Importe"), { target: { value: "25,50" } });
  const form = within(document.querySelector(".cash-movement-form")!);
  fireEvent.change(form.getByLabelText("Motivo o comentario"), { target: { value: "Cambio" } });
  if (session) fireEvent.change(form.getByLabelText("Tu contraseña"), { target: { value: "secret" } });
}

describe("CashOperationsCard", () => {
  it("opens with the actually counted fund without posting a between-session movement", async () => {
    const request = createRequest({ session: false });
    renderCard(request);
    expect(await screen.findByText("No hay una caja abierta en este terminal.")).toBeVisible();
    fireEvent.change(screen.getByLabelText("Efectivo contado / fondo inicial"), { target: { value: "200" } });
    fireEvent.click(screen.getByRole("button", { name: "Abrir caja" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/cash/sessions/open", {
      token: "token", method: "POST", body: { terminalId: "terminal-1", countedFund: 200, denominations: [] },
    }));
    expect(request.mock.calls.some(([path]) => path === "/cash/movements/between-sessions")).toBe(false);
  });

  it("uses automatic opening when the store does not require a cash session", async () => {
    const request = createRequest({ session: false, readiness: { ...readiness, open: false, session: null, cashSessionRequired: false } });
    renderCard(request);
    expect(await screen.findByText("Apertura automática")).toBeVisible();
    expect(screen.queryByLabelText("Efectivo contado / fondo inicial")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Abrir caja automáticamente" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/cash/sessions/prepare-sales", {
      token: "token", method: "POST", body: { terminalId: "terminal-1" },
    }));
    expect(request.mock.calls.some(([path]) => path === "/cash/sessions/open")).toBe(false);
  });

  it("uses delegated close authorization and passes the shared close payload", async () => {
    const request = createRequest();
    renderCard(request, []);
    expect(await screen.findByRole("button", { name: "Cierre" })).toHaveAttribute("aria-pressed", "true");
    expect(await screen.findByText("1. Retirada de efectivo")).toBeVisible();
    fireEvent.change(screen.getByLabelText("Fondo que queda en caja"), { target: { value: "20" } });
    fireEvent.change(screen.getByLabelText("Retirada final"), { target: { value: "180" } });
    fireEvent.change(screen.getByLabelText("Usuario autorizador"), { target: { value: "jefe" } });
    fireEvent.change(screen.getByLabelText("Contraseña del autorizador"), { target: { value: "secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Cerrar caja" }));
    await waitFor(() => expect(request.mock.calls.some(([path, init]) => path === "/cash/sessions/close"
      && init?.body?.retainedFund === 20 && init?.body?.finalWithdrawalAmount === 180
      && init?.body?.authorizerUsername === "jefe" && init?.body?.authorizerPassword === "secret")).toBe(true));
  });

  it("keeps close mounted and locks navigation after a first reconciliation attempt", async () => {
    const request = createRequest({ close: async () => ({ ...openSession, status: "ABIERTA" }) });
    renderCard(request, ["CASH_CLOSE"]);
    await screen.findByRole("button", { name: "Cierre" });
    fireEvent.change(await screen.findByLabelText("Tu contraseña"), { target: { value: "secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Cerrar caja" }));
    expect(await screen.findByRole("button", { name: "Reintentar cierre" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Entrada" })).toBeDisabled();
    const saved = JSON.parse(localStorage.getItem("tpverp.cash-close.v1.T1")!);
    expect(saved.flow.phase).toBe("RECONCILIATION_REQUIRED");
    expect(saved.flow.authorizerPassword).toBeUndefined();
  });

  it("unlocks automatic opening after a successful close", async () => {
    const request = createRequest({ readiness: { ...readiness, cashSessionRequired: false } });
    renderCard(request, ["CASH_CLOSE"]);
    fireEvent.change(await screen.findByLabelText("Tu contraseña"), { target: { value: "secret" } });
    fireEvent.click(screen.getByRole("button", { name: "Cerrar caja" }));
    await waitFor(() => expect(request.mock.calls.some(([path]) => path === "/cash/sessions/close")).toBe(true));
    expect(await screen.findByRole("button", { name: "Abrir caja automáticamente" })).toBeEnabled();
  });

  it("blocks opening when a confirmed in-progress close has no open session", async () => {
    saveCashCloseRecovery(localStorage, "T1", { ...createCashCloseUiFlow(), phase: "ATTEMPTED", finalWithdrawal: "180" });
    const request = createRequest({ session: false });
    renderCard(request);
    expect(await screen.findByText(/cierre pendiente que no se puede recuperar/i)).toBeVisible();
    expect(screen.queryByRole("button", { name: "Abrir caja" })).toBeNull();
    expect(request.mock.calls.findIndex(([path]) => path.startsWith("/cash/sessions/close-operations/")))
      .toBeLessThan(request.mock.calls.findIndex(([path]) => path.startsWith("/cash/status")));
  });

  it("blocks reuse of a previous close operation for a new open session", async () => {
    saveCashCloseRecovery(localStorage, "T1", { ...createCashCloseUiFlow(), phase: "RECONCILIATION_REQUIRED", finalWithdrawal: "180" });
    const request = createRequest({ recovery: async () => ({ status: "REQUIERE_ARQUEO", sessionId: "older-session", terminalId: "terminal-1", finalWithdrawalAmount: 180 }) });
    renderCard(request);
    expect(await screen.findByText(/cierre pendiente que no se puede recuperar/i)).toBeVisible();
    expect(screen.getByRole("button", { name: "Entrada" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Reintentar cierre" })).toBeNull();
  });

  it("clears a server-confirmed completed close before showing the next opening", async () => {
    saveCashCloseRecovery(localStorage, "T1", { ...createCashCloseUiFlow(), phase: "ATTEMPTED" });
    const request = createRequest({ session: false, recovery: async () => ({ status: "CERRADA", sessionId: "old-session", terminalId: "terminal-1", finalWithdrawalAmount: 0 }) });
    renderCard(request);
    expect(await screen.findByRole("button", { name: "Abrir caja" })).toBeVisible();
    expect(localStorage.getItem("tpverp.cash-close.v1.T1")).toBeNull();
  });

  it("shows redacted amounts as dashes when the API hides expected cash", async () => {
    const request = createRequest({ view: { ...openSession, openingFund: null, expectedCash: null, availableCash: null } });
    renderCard(request);
    expect((await screen.findByText("Fondo inicial")).parentElement).toHaveTextContent("—");
    expect(screen.getByText("Efectivo esperado").parentElement).toHaveTextContent("—");
    expect(screen.getByText("Disponible").parentElement).toHaveTextContent("—");
  });

  it("keeps entry and withdrawal drafts separate and preserves a denied movement draft", async () => {
    const request = createRequest({ movement: async () => { throw new ApiError("Denegado", 403, { detail: "Denegado" }); } });
    renderCard(request, ["CASH_MOVE"]);
    fireEvent.click(await screen.findByRole("button", { name: "Entrada" }));
    fireEvent.change(screen.getByLabelText("Importe"), { target: { value: "25,50" } });
    fireEvent.change(within(document.querySelector(".cash-movement-form")!).getByLabelText("Motivo o comentario"), { target: { value: "Cambio" } });
    fireEvent.change(within(document.querySelector(".cash-movement-form")!).getByLabelText("Tu contraseña"), { target: { value: "bad" } });
    fireEvent.click(screen.getByRole("button", { name: "Retirada" }));
    expect(screen.getByLabelText("Importe")).toHaveValue("");
    fireEvent.click(screen.getByRole("button", { name: "Entrada" }));
    expect(screen.getByLabelText("Importe")).toHaveValue("25,50");
    expect(within(document.querySelector(".cash-movement-form")!).getByLabelText("Motivo o comentario")).toHaveValue("Cambio");
    fireEvent.click(screen.getByRole("button", { name: "Registrar entrada" }));
    expect(await screen.findByText("Denegado")).toBeVisible();
    expect(screen.getByLabelText("Importe")).toHaveValue("25,50");
    expect(within(document.querySelector(".cash-movement-form")!).getByLabelText("Tu contraseña")).toHaveValue("");
    expect(hardware.printTicket).not.toHaveBeenCalled();
    expect(request.mock.calls.some(([path]) => path.startsWith("/cash/receipts/"))).toBe(false);
  });

  it.each(printableMovements)("prints the $navigation template after registering its movement", async movement => {
    const request = createRequest({ session: movement.session });
    renderCard(request, ["CASH_MOVE"]);
    await fillMovement(movement.navigation, movement.session);
    fireEvent.click(screen.getByRole("button", { name: movement.submit }));
    await waitFor(() => expect(hardware.printTicket).toHaveBeenCalledOnce());
    expect(request).toHaveBeenCalledWith(`/cash/receipts/${movement.receipt}/new-movement/print-document`, { token: "token" });
    expect(hardware.printTicket).toHaveBeenCalledWith(expect.objectContaining({
      requireRenderedDocument: true, storeName: "Tienda", terminalCode: "T1",
      documentNumber: movement.entry ? "entrada.pdf" : "retirada.pdf",
      renderedPdf: { contentType: "application/pdf", base64: "rendered-pdf" },
      documentRaster: "data:image/png;base64,rendered-raster",
    }), defaultHardwareConfig);
    expect(request.mock.calls.filter(([path, init]) => path === movement.post && init?.method === "POST")).toHaveLength(1);
    await waitFor(() => expect(screen.getByRole("button", { name: movement.submit })).toBeDisabled());
    expect(screen.getByLabelText("Importe")).toHaveValue("");
    expect(screen.queryByRole("button", { name: "Reimprimir justificante" })).toBeNull();
  });

  it.each(printableMovements)("retries only printing after a failed $navigation receipt", async movement => {
    hardware.printTicket.mockResolvedValueOnce({ ok: false, message: "Impresora sin conexión" });
    const request = createRequest({ session: movement.session });
    renderCard(request, ["CASH_MOVE"]);
    await fillMovement(movement.navigation, movement.session);
    fireEvent.click(screen.getByRole("button", { name: movement.submit }));
    const retry = await screen.findByRole("button", { name: "Reimprimir justificante" });
    expect(screen.getByText(movement.entry
      ? "La entrada se registró, pero no se pudo imprimir el justificante."
      : "La retirada se registró, pero no se pudo imprimir el justificante.")).toBeVisible();
    expect(screen.getByText("Impresora sin conexión")).toBeVisible();
    await waitFor(() => expect(retry).toBeEnabled());
    expect(screen.getByLabelText("Importe")).toHaveValue("");
    fireEvent.click(retry);
    await waitFor(() => expect(screen.queryByRole("button", { name: "Reimprimir justificante" })).toBeNull());
    expect(hardware.printTicket).toHaveBeenCalledTimes(2);
    expect(request.mock.calls.filter(([path, init]) => path.startsWith("/cash/movements/") && init?.method === "POST")).toHaveLength(1);
    expect(request.mock.calls.filter(([path]) => path === `/cash/receipts/${movement.receipt}/new-movement/print-document`)).toHaveLength(2);
  });

  it("keeps each failed receipt when another cash movement is registered", async () => {
    hardware.printTicket.mockResolvedValueOnce({ ok: false }).mockResolvedValueOnce({ ok: false });
    let nextId = 0;
    const request = createRequest({ movement: async () => ({ id: `movement-${++nextId}` }) });
    renderCard(request, ["CASH_MOVE"]);
    for (let index = 0; index < 2; index++) {
      await fillMovement("Entrada", true);
      fireEvent.click(screen.getByRole("button", { name: "Registrar entrada" }));
      await waitFor(() => expect(screen.getAllByRole("button", { name: "Reimprimir justificante" })).toHaveLength(index + 1));
      await waitFor(() => expect(screen.getAllByRole("button", { name: "Reimprimir justificante" })[0]).toBeEnabled());
    }
    fireEvent.click(screen.getAllByRole("button", { name: "Reimprimir justificante" })[0]);
    await waitFor(() => expect(screen.getAllByRole("button", { name: "Reimprimir justificante" })).toHaveLength(1));
    expect(request.mock.calls.filter(([path]) => path === "/cash/receipts/entries/movement-1/print-document")).toHaveLength(2);
    expect(request.mock.calls.filter(([path]) => path === "/cash/receipts/entries/movement-2/print-document")).toHaveLength(1);
    expect(nextId).toBe(2);
  });

  it("leaves history requests to the activity tab", async () => {
    const request = createRequest();
    renderCard(request);
    await screen.findByText("Fondo inicial");
    expect(request.mock.calls.some(([path]) => path.startsWith("/cash/timeline"))).toBe(false);
    expect(screen.queryByRole("table", { name: "Historial de hoy" })).toBeNull();
  });

  it("registers a distinct between-session withdrawal with its counted denominations", async () => {
    const request = createRequest({ session: false, readiness: { ...readiness, open: false, session: null, requireWithdrawalBreakdown: true } });
    renderCard(request);
    await screen.findByText("No hay una caja abierta en este terminal.");
    fireEvent.click(screen.getByRole("button", { name: "Retirada entre sesiones" }));
    fireEvent.click(screen.getAllByRole("button", { name: /Contar monedas y billetes/ }).at(-1)!);
    fireEvent.change(screen.getByRole("spinbutton", { name: /Unidades 20/ }), { target: { value: "1" } });
    fireEvent.click(screen.getByRole("button", { name: "Aceptar" }));
    fireEvent.click(screen.getByRole("button", { name: "Registrar retirada entre sesiones" }));
    await waitFor(() => expect(request.mock.calls.some(([path, init]) => path === "/cash/movements/between-sessions"
      && init?.body?.withdrawal === true && init?.body?.amount === 20
      && Array.isArray(init?.body?.denominations) && init.body.denominations.some((row: CashDenominationCount) => row.denomination === 20 && row.quantity === 1))).toBe(true));
  });
});
