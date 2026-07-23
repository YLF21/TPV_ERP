// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "../api/client";
import { CashOperationsCard } from "./CashOperationsCard";

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

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
});
