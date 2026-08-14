// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  SaleCashSessionDialog,
  type CashCloseUiFlow,
} from "./SaleCashSessionDialog";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("SaleCashSessionDialog", () => {
  it("uses a neutral cancel action when cash opening starts from Home", async () => {
    const onExitSales = vi.fn();
    render(
      <SaleCashSessionDialog
        locale="es"
        mode="OPEN"
        openContext="HOME"
        terminalId="terminal-1"
        token="token"
        onExitSales={onExitSales}
      />,
    );

    expect(screen.queryByRole("button", { name: "Salir de Ventas" })).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(onExitSales).toHaveBeenCalledOnce();
  });

  it("closes a cash session with the retained fund and exits Sales after success", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      id: "session-1",
      terminalId: "terminal-1",
      status: "CERRADA",
      openedAt: "2026-07-25T08:00:00Z",
      openingFund: 50,
      retainedFund: 40,
      closedAt: "2026-07-25T18:00:00Z",
      closedByAttempt: true,
    }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    const onClosed = vi.fn();

    render(
      <SaleCashSessionDialog
        locale="es"
        mode="CLOSE"
        terminalId="terminal-1"
        token="token"
        onClosed={onClosed}
      />,
    );

    await userEvent.clear(screen.getByLabelText("Fondo que queda en caja"));
    await userEvent.type(screen.getByLabelText("Fondo que queda en caja"), "40");
    await userEvent.clear(screen.getByLabelText("Retirada final"));
    await userEvent.type(screen.getByLabelText("Retirada final"), "10");
    await userEvent.type(screen.getByLabelText("Comentario de la retirada"), "Cierre");
    await userEvent.click(screen.getByRole("button", { name: "Cerrar caja" }));

    expect(onClosed).toHaveBeenCalledWith(expect.objectContaining({ status: "CERRADA" }));
    const request = fetchMock.mock.calls[0][1] as RequestInit;
    expect(JSON.parse(String(request.body))).toEqual(expect.objectContaining({
      terminalId: "terminal-1",
      retainedFund: 40,
      finalWithdrawalAmount: 10,
      finalWithdrawalComment: "Cierre",
      closeOperationId: expect.stringMatching(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      ),
      reconciliationAttemptId: expect.stringMatching(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      ),
    }));
  });

  it("keeps the close operation and rotates the idempotency key for the second cash count", async () => {
    const firstAttempt = {
      id: "session-1",
      terminalId: "terminal-1",
      status: "ABIERTA",
      openedAt: "2026-07-25T08:00:00Z",
      openingFund: 100,
      reconciliationAttempt: 1,
      closedByAttempt: false,
    };
    const secondAttempt = {
      ...firstAttempt,
      status: "CERRADA",
      retainedFund: 80,
      closedAt: "2026-07-25T18:00:00Z",
      reconciliationAttempt: 2,
      closedByAttempt: true,
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(firstAttempt), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify(secondAttempt), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }));
    vi.stubGlobal("fetch", fetchMock);
    const onClosed = vi.fn();

    render(
      <SaleCashSessionDialog
        locale="es"
        mode="CLOSE"
        terminalId="terminal-1"
        token="token"
        onClosed={onClosed}
      />,
    );

    await userEvent.clear(screen.getByLabelText("Fondo que queda en caja"));
    await userEvent.type(screen.getByLabelText("Fondo que queda en caja"), "70");
    await userEvent.clear(screen.getByLabelText("Retirada final"));
    await userEvent.type(screen.getByLabelText("Retirada final"), "20");
    await userEvent.type(screen.getByLabelText("Comentario de la retirada"), "Cierre");
    await userEvent.click(screen.getByRole("button", { name: "Cerrar caja" }));
    await screen.findByRole("alert");

    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();
    expect(screen.getByLabelText("Retirada final")).toBeDisabled();
    expect(screen.getByLabelText("Comentario de la retirada")).toBeDisabled();
    expect(screen.getByLabelText("Fondo que queda en caja")).toBeEnabled();
    await userEvent.clear(screen.getByLabelText("Fondo que queda en caja"));
    await userEvent.type(screen.getByLabelText("Fondo que queda en caja"), "80");
    await userEvent.click(screen.getByRole("button", { name: "Reintentar cierre" }));
    await waitFor(() => expect(onClosed).toHaveBeenCalledTimes(1));

    const firstBody = JSON.parse(String((fetchMock.mock.calls[0][1] as RequestInit).body));
    const secondBody = JSON.parse(String((fetchMock.mock.calls[1][1] as RequestInit).body));
    expect(firstBody.closeOperationId).toBeTruthy();
    expect(secondBody.closeOperationId).toBe(firstBody.closeOperationId);
    expect(firstBody.reconciliationAttemptId).toBeTruthy();
    expect(secondBody.reconciliationAttemptId)
      .not.toBe(firstBody.reconciliationAttemptId);
  });

  it("unlocks a rejected close when the backend confirms the operation was not created", async () => {
    const rejected = {
      status: 400,
      title: "Solicitud no valida",
      detail: "La retirada supera el efectivo disponible en caja",
      code: "VALIDATION_ERROR",
    };
    const notFound = {
      status: 404,
      title: "Recurso no encontrado",
      detail: "Recurso no encontrado",
      code: "NOT_FOUND",
    };
    const closedSession = {
      id: "session-1",
      terminalId: "terminal-1",
      status: "CERRADA",
      openedAt: "2026-07-25T08:00:00Z",
      openingFund: 22.28,
      retainedFund: 10,
      closedAt: "2026-07-25T18:00:00Z",
      reconciliationAttempt: 1,
      closedByAttempt: true,
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(rejected), {
        status: 400,
        headers: { "Content-Type": "application/problem+json" },
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify(notFound), {
        status: 404,
        headers: { "Content-Type": "application/problem+json" },
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify(closedSession), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }));
    vi.stubGlobal("fetch", fetchMock);
    const onClosed = vi.fn();

    render(
      <SaleCashSessionDialog
        locale="es"
        mode="CLOSE"
        terminalId="terminal-1"
        token="token"
        onClosed={onClosed}
      />,
    );

    await userEvent.clear(screen.getByLabelText("Fondo que queda en caja"));
    await userEvent.type(screen.getByLabelText("Fondo que queda en caja"), "10");
    await userEvent.clear(screen.getByLabelText("Retirada final"));
    await userEvent.type(screen.getByLabelText("Retirada final"), "100");
    await userEvent.click(screen.getByRole("button", { name: "Cerrar caja" }));

    expect(await screen.findByText("La retirada supera el efectivo disponible en caja"))
      .toBeInTheDocument();
    expect(screen.getByLabelText("Fondo que queda en caja")).toBeEnabled();
    expect(screen.getByLabelText("Retirada final")).toBeEnabled();
    expect(screen.getByRole("button", { name: "Cancelar" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Cerrar caja" })).toBeEnabled();

    await userEvent.clear(screen.getByLabelText("Retirada final"));
    await userEvent.type(screen.getByLabelText("Retirada final"), "12.28");
    await userEvent.click(screen.getByRole("button", { name: "Cerrar caja" }));

    await waitFor(() => expect(onClosed).toHaveBeenCalledWith(closedSession));
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("preserves and locks an initiated close flow when the dialog remounts", async () => {
    const closedSession = {
      id: "session-1",
      terminalId: "terminal-1",
      status: "CERRADA",
      openedAt: "2026-07-25T08:00:00Z",
      openingFund: 100,
      retainedFund: 80,
      closedAt: "2026-07-25T18:00:00Z",
      reconciliationAttempt: 2,
      closedByAttempt: true,
    };
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new Error("Respuesta de cierre desconocida"))
      .mockResolvedValueOnce(new Response(JSON.stringify(closedSession), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }));
    vi.stubGlobal("fetch", fetchMock);
    const onCancel = vi.fn();
    const onClosed = vi.fn();
    const flows: CashCloseUiFlow[] = [];
    const onCloseFlowChange = vi.fn((flow: CashCloseUiFlow) => flows.push(flow));

    const firstRender = render(
      <SaleCashSessionDialog
        locale="es"
        mode="CLOSE"
        terminalId="terminal-1"
        token="token"
        onCancel={onCancel}
        onClosed={onClosed}
        onCloseFlowChange={onCloseFlowChange}
      />,
    );

    await userEvent.clear(screen.getByLabelText("Fondo que queda en caja"));
    await userEvent.type(screen.getByLabelText("Fondo que queda en caja"), "80");
    await userEvent.clear(screen.getByLabelText("Retirada final"));
    await userEvent.type(screen.getByLabelText("Retirada final"), "20");
    await userEvent.type(screen.getByLabelText("Comentario de la retirada"), "Cierre");
    await userEvent.click(screen.getByRole("button", { name: "Cerrar caja" }));
    await screen.findByRole("alert");

    const initiatedFlow = flows.at(-1);
    expect(initiatedFlow).toEqual(expect.objectContaining({
      phase: "ATTEMPTED",
      retainedFund: "80",
      finalWithdrawal: "20",
      comment: "Cierre",
      closeOperationId: expect.any(String),
      reconciliationAttemptId: expect.any(String),
    }));
    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();

    firstRender.unmount();
    render(
      <SaleCashSessionDialog
        locale="es"
        mode="CLOSE"
        terminalId="terminal-1"
        token="token"
        closeFlow={initiatedFlow}
        onCancel={onCancel}
        onClosed={onClosed}
        onCloseFlowChange={onCloseFlowChange}
      />,
    );

    expect(screen.getByLabelText("Fondo que queda en caja")).toHaveValue("80");
    expect(screen.getByLabelText("Retirada final")).toHaveValue("20");
    expect(screen.getByLabelText("Comentario de la retirada")).toHaveValue("Cierre");
    expect(screen.getByLabelText("Fondo que queda en caja")).toBeDisabled();
    expect(screen.getByLabelText("Retirada final")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();

    await userEvent.keyboard("{Escape}");
    expect(onCancel).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Reintentar cierre" }));
    await waitFor(() => expect(onClosed).toHaveBeenCalledTimes(1));

    const firstBody = JSON.parse(String((fetchMock.mock.calls[0][1] as RequestInit).body));
    const secondBody = JSON.parse(String((fetchMock.mock.calls[1][1] as RequestInit).body));
    expect(secondBody.closeOperationId).toBe(firstBody.closeOperationId);
    expect(secondBody.reconciliationAttemptId)
      .toBe(firstBody.reconciliationAttemptId);
    expect(secondBody).toEqual(expect.objectContaining({
      retainedFund: 80,
      finalWithdrawalAmount: 20,
      finalWithdrawalComment: "Cierre",
    }));
  });
});
