// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SaleCashSessionDialog } from "./SaleCashSessionDialog";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("SaleCashSessionDialog", () => {
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
    }));
  });
});
