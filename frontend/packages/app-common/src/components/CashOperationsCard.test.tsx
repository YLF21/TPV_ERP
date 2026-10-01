// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "../api/client";
import { CashOperationsCard } from "./CashOperationsCard";

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const openSession = {
  id: "cash-1", terminalId: "terminal-1", status: "ABIERTA",
  openedAt: "2026-09-30T08:00:00Z", openingFund: 200,
  expectedCash: 215, availableCash: 215, retainedFund: 5,
};

function cashRequest(post: () => Promise<unknown> = async () => ({ status: "ABIERTA" })) {
  return vi.fn(async (path: string, options?: { method?: string; body?: Record<string, unknown> }) => {
    if (options?.method === "POST") return post();
    if (path.startsWith("/cash/status")) return openSession;
    if (path.startsWith("/cash/reports")) return {
      totalsByType: { ENTRADA: 30, RETIRADA: 15, COBRO_EFECTIVO: 0, DEVOLUCION_EFECTIVO: 0 },
      retainedFunds: 5, discrepancies: -2.5,
    };
    return undefined;
  });
}

function renderCash(request: ReturnType<typeof cashRequest>) {
  return render(<CashOperationsCard locale="es" token="token" currentUsername="admin"
    terminalId="terminal-1" request={request as unknown as typeof apiRequest} />);
}

describe("CashOperationsCard", () => {
  it("shows the current cash position and daily reconciliation", async () => {
    const request = vi.fn(async (path: string) => {
      if (path.startsWith("/cash/status")) {
        return {
          id: "cash-1",
          terminalId: "terminal-1",
          status: "OPEN",
          openedAt: "2026-07-23T08:00:00Z",
          openingFund: 100,
          expectedCash: 125.5,
          availableCash: 120.5,
          retainedFund: 5,
        };
      }
      if (path.startsWith("/cash/reports")) {
        return {
          totalsByType: { CASH_SALE: 25.5 },
          retainedFunds: 5,
          discrepancies: 0,
        };
      }
      return undefined;
    }) as unknown as typeof apiRequest;

    render(
      <CashOperationsCard
        locale="es"
        token="token"
        terminalId="terminal-1"
        request={request}
      />,
    );

    expect(await screen.findByText("Caja abierta")).toBeVisible();
    expect(screen.getByText(/125,50/)).toBeVisible();
    expect(screen.getByText("CASH SALE")).toBeVisible();
    expect(screen.getByText(/^25,50/)).toBeVisible();
  });

  it("prepares the opening fund before opening a register", async () => {
    const request = vi.fn(async (path: string) => {
      if (path.startsWith("/cash/status")) {
        throw new ApiError("No hay una sesión de caja abierta", 404, {
          detail: "No hay una sesión de caja abierta",
        });
      }
      if (path.startsWith("/cash/reports")) {
        return { totalsByType: {}, retainedFunds: 0, discrepancies: 0 };
      }
      return undefined;
    }) as unknown as typeof apiRequest;

    render(
      <CashOperationsCard
        locale="es"
        token="token"
        terminalId="terminal-1"
        request={request}
      />,
    );

    expect(await screen.findByText("No hay una caja abierta en este terminal.")).toBeVisible();
    fireEvent.change(screen.getByLabelText("Fondo inicial"), { target: { value: "80" } });
    fireEvent.change(screen.getByLabelText("Motivo o comentario"), {
      target: { value: "Fondo de apertura" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Preparar fondo" }));

    await waitFor(() =>
      expect(request).toHaveBeenCalledWith("/cash/movements/between-sessions", {
        token: "token",
        method: "POST",
        body: {
          terminalId: "terminal-1",
          amount: 80,
          comment: "Fondo de apertura",
          denominations: [],
          withdrawal: false,
        },
      }),
    );
  });

  it("opens Cierre by default and switches forms without posting or losing a draft", async () => {
    const request = cashRequest();
    renderCash(request);
    const close = await screen.findByRole("button", { name: "Cierre" });
    expect(close).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByLabelText("Fondo retenido")).toHaveValue("5");
    expect(screen.queryByLabelText("Importe")).toBeNull();
    expect(screen.queryByRole("button", { name: "Registrar entrada" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Entrada" }));
    fireEvent.change(screen.getByLabelText("Importe"), { target: { value: "25,50" } });
    fireEvent.change(screen.getByLabelText("Motivo o comentario"), { target: { value: "Cambio" } });
    fireEvent.click(screen.getByRole("button", { name: "Retirada" }));
    expect(screen.getByLabelText("Importe")).toHaveValue("");
    fireEvent.change(screen.getByLabelText("Importe"), { target: { value: "10" } });
    fireEvent.click(screen.getByRole("button", { name: "Entrada" }));
    expect(screen.getByLabelText("Importe")).toHaveValue("25,50");
    expect(screen.getByLabelText("Motivo o comentario")).toHaveValue("Cambio");
    expect(request.mock.calls.some(([, options]) => options?.method === "POST")).toBe(false);
  });

  it.each([
    ["Entrada", "Registrar entrada", "/cash/movements/entry"],
    ["Retirada", "Registrar retirada", "/cash/movements/withdrawal"],
  ])("submits only the selected %s operation after reason and authorization", async (operation, action, path) => {
    const request = cashRequest();
    renderCash(request);
    await screen.findByRole("button", { name: "Cierre" });
    fireEvent.click(screen.getByRole("button", { name: operation }));
    fireEvent.change(screen.getByLabelText("Importe"), { target: { value: "10,50" } });
    fireEvent.change(screen.getByLabelText("Motivo o comentario"), { target: { value: " Cambio de turno " } });
    fireEvent.change(screen.getByLabelText("Usuario autorizador"), { target: { value: " ADMIN " } });
    fireEvent.change(screen.getByLabelText("Contraseña autorizador"), { target: { value: "test-pin" } });
    const submit = screen.getByRole("button", { name: action });
    expect(screen.getByLabelText("Contraseña autorizador").compareDocumentPosition(submit)
      & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    fireEvent.click(submit);
    await waitFor(() => expect(request).toHaveBeenCalledWith(path, {
      token: "token", method: "POST", body: {
        terminalId: "terminal-1", amount: 10.5, comment: "Cambio de turno",
        authorizerUsername: "ADMIN", authorizerPassword: "test-pin", denominations: [],
        ...(operation === "Retirada" ? { withdrawal: true } : {}),
      },
    }));
    await waitFor(() => expect(screen.getByLabelText("Contraseña autorizador")).toHaveValue(""));
  });

  it("sends close authorization and keeps one operation id across reconciliation retries", async () => {
    const request = cashRequest();
    renderCash(request);
    await screen.findByRole("button", { name: "Cierre" });
    fireEvent.change(screen.getByLabelText("Fondo retenido"), { target: { value: "200" } });
    fireEvent.change(screen.getByLabelText("Retirada final"), { target: { value: "15" } });
    fireEvent.change(screen.getByLabelText("Motivo o comentario"), { target: { value: " Cierre del turno " } });
    fireEvent.change(screen.getByLabelText("Usuario autorizador"), { target: { value: " ADMIN " } });
    fireEvent.change(screen.getByLabelText("Contraseña autorizador"), { target: { value: "test-pin" } });
    fireEvent.click(screen.getByRole("button", { name: "Cerrar caja" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Cerrar caja" })).toBeEnabled());
    const first = request.mock.calls.find(([path]) => path === "/cash/sessions/close")?.[1]?.body;
    expect(first).toEqual(expect.objectContaining({
      terminalId: "terminal-1", retainedFund: 200, finalWithdrawalAmount: 15,
      finalWithdrawalComment: "Cierre del turno", authorizerUsername: "ADMIN",
      authorizerPassword: "test-pin", retainedFundDenominations: [], finalWithdrawalDenominations: [],
    }));
    expect(first?.closeOperationId).toMatch(/^[\da-f-]{36}$/i);
    expect(first?.reconciliationAttemptId).toMatch(/^[\da-f-]{36}$/i);
    fireEvent.click(screen.getByRole("button", { name: "Cerrar caja" }));
    await waitFor(() => expect(request.mock.calls.filter(([path]) => path === "/cash/sessions/close")).toHaveLength(2));
    const second = request.mock.calls.filter(([path]) => path === "/cash/sessions/close")[1][1]?.body;
    expect(second?.closeOperationId).toBe(first?.closeOperationId);
    expect(second?.reconciliationAttemptId).not.toBe(first?.reconciliationAttemptId);
  });

  it("blocks switching and duplicate submission while a movement is pending", async () => {
    let complete!: (value: unknown) => void;
    const pending = new Promise(resolve => { complete = resolve; });
    const request = cashRequest(() => pending);
    renderCash(request);
    await screen.findByRole("button", { name: "Cierre" });
    fireEvent.click(screen.getByRole("button", { name: "Entrada" }));
    fireEvent.change(screen.getByLabelText("Importe"), { target: { value: "5" } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar entrada" }));
    expect(screen.getByRole("button", { name: "Retirada" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Registrar entrada" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Retirada" }));
    fireEvent.click(screen.getByRole("button", { name: "Registrar entrada" }));
    expect(request.mock.calls.filter(([, options]) => options?.method === "POST")).toHaveLength(1);
    await act(async () => complete({ status: "ABIERTA" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Retirada" })).toBeEnabled());
  });

  it("keeps the selected form and draft after a rejected operation", async () => {
    const request = cashRequest(async () => { throw new ApiError("Autorización rechazada", 403); });
    renderCash(request);
    await screen.findByRole("button", { name: "Cierre" });
    fireEvent.click(screen.getByRole("button", { name: "Retirada" }));
    fireEvent.change(screen.getByLabelText("Importe"), { target: { value: "10" } });
    fireEvent.change(screen.getByLabelText("Motivo o comentario"), { target: { value: "Retirada" } });
    fireEvent.click(screen.getByRole("button", { name: "Registrar retirada" }));
    expect(await screen.findByText("Autorización rechazada")).toBeVisible();
    expect(screen.getByRole("button", { name: "Retirada" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByLabelText("Importe")).toHaveValue("10");
    expect(screen.getByLabelText("Motivo o comentario")).toHaveValue("Retirada");
  });

  it("shows every movement type and reconciliation figures in the final summary table", async () => {
    const request = cashRequest();
    renderCash(request);
    const table = await screen.findByRole("table", { name: "Resumen de hoy" });
    expect(within(table).getAllByRole("columnheader")).toHaveLength(2);
    expect(within(table).getAllByRole("row")).toHaveLength(7);
    expect(within(table).getByText("Fondos retenidos").closest("tr")).toHaveTextContent(/5,00/);
    expect(within(table).getByText("Descuadres").closest("tr")).toHaveTextContent(/-2,50/);
    expect(table.compareDocumentPosition(screen.getByRole("button", { name: "Cerrar caja" }))
      & Node.DOCUMENT_POSITION_PRECEDING).toBeTruthy();
  });

  it("still shows retained funds and discrepancies when the report has no movements", async () => {
    const request = vi.fn(async (path: string) => path.startsWith("/cash/status")
      ? openSession : { totalsByType: {}, retainedFunds: 80, discrepancies: -2.5 });
    render(<CashOperationsCard locale="es" token="token" terminalId="terminal-1"
      request={request as unknown as typeof apiRequest} />);
    const table = await screen.findByRole("table", { name: "Resumen de hoy" });
    expect(within(table).getByText("Fondos retenidos").closest("tr")).toHaveTextContent(/80,00/);
    expect(within(table).getByText("Descuadres").closest("tr")).toHaveTextContent(/-2,50/);
  });
});
