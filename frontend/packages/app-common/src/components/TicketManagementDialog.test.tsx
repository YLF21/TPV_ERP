// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { TicketManagementDialog } from "./TicketManagementDialog";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));

const request = vi.mocked(apiRequest);
const tickets = Array.from({ length: 25 }, (_, index) => ({
  id: `ticket-${index + 1}`,
  estado: index % 5 === 0 ? "ANULADO" : "CONFIRMADO",
  numero: `T-${String(index + 1).padStart(3, "0")}`,
  fecha: index < 10 ? "2026-07-10" : "2026-07-20",
  customerName: index % 2 === 0 ? "Cliente Norte" : "Cliente Sur",
  total: "12.10",
  pendingTotal: "0.00",
  payments: []
}));

afterEach(cleanup);
beforeEach(() => {
  request.mockReset();
  request.mockImplementation(async (path) => {
    if (path === "/tickets") return tickets as never;
    return [] as never;
  });
});

describe("TicketManagementDialog", () => {
  it("filters tickets by status and date without losing the selected result", async () => {
    render(<TicketManagementDialog token="token" locale="es" terminalContext={{ storeName: "Tienda", terminalCode: "01" }} onClose={vi.fn()} />);

    expect(await screen.findByText("25 resultados")).toBeVisible();
    fireEvent.change(screen.getByLabelText("Estado"), { target: { value: "ANULADO" } });
    expect(await screen.findByText("5 resultados")).toBeVisible();
    expect(screen.getByRole("button", { name: /T-001/ })).toHaveAttribute("aria-pressed", "true");

    fireEvent.change(screen.getByLabelText("Desde"), { target: { value: "2026-07-20" } });
    await waitFor(() => expect(screen.getByText("3 resultados")).toBeVisible());
    expect(screen.queryByRole("button", { name: /T-001/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /T-011/ })).toHaveAttribute("aria-pressed", "true");
  });

  it("paginates long ticket histories", async () => {
    render(<TicketManagementDialog token="token" locale="es" terminalContext={{ storeName: "Tienda", terminalCode: "01" }} onClose={vi.fn()} />);

    expect(await screen.findByText("Página 1 de 2")).toBeVisible();
    expect(screen.getByRole("button", { name: /T-020/ })).toBeVisible();
    expect(screen.queryByRole("button", { name: /T-021/ })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Siguiente" }));
    expect(await screen.findByText("Página 2 de 2")).toBeVisible();
    expect(screen.getByRole("button", { name: /T-021/ })).toBeVisible();
    expect(screen.getByRole("button", { name: "Siguiente" })).toBeDisabled();
  });

  it("creates a voucher return without allocating cash", async () => {
    const sale = {
      id: "ticket-sale",
      estado: "CONFIRMADO",
      numero: "T-100",
      fecha: "2026-07-22",
      customerName: "Cliente",
      total: "12.10",
      pendingTotal: "0.00",
      payments: []
    };
    request.mockImplementation(async (path) => {
      if (path === "/tickets") return [sale] as never;
      if (path === "/tickets/ticket-sale/return-options") return [{
        lineId: "line-1",
        code: "P-1",
        name: "Producto",
        refundableQuantity: "1",
        refundableTotal: "12.10"
      }] as never;
      if (path === "/tickets/ticket-sale/returns") return {
        documentId: "ticket-return",
        voucherCode: "VTEST123",
        receipt: {
          documentId: "ticket-return",
          documentNumber: "T-101",
          issuedAt: "2026-07-22T12:00:00Z",
          lines: [],
          payments: [{ method: "VALE", amount: "-12.10" }],
          total: "-12.10"
        }
      } as never;
      return [] as never;
    });

    render(<TicketManagementDialog
      token="token"
      locale="es"
      permissions={["ADMIN"]}
      terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      onClose={vi.fn()}
    />);

    fireEvent.click(await screen.findByRole("button", { name: "Preparar devolución" }));
    fireEvent.click(await screen.findByRole("button", { name: "Reembolsar todo en un vale" }));
    fireEvent.change(screen.getByLabelText("Contraseña del usuario actual"), { target: { value: "0000" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar devolución" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/tickets/ticket-sale/returns",
      expect.objectContaining({
        method: "POST",
        body: expect.objectContaining({ cashAmount: "0.00", voucherAmount: "12.10", password: "0000" })
      })
    ));
    expect(await screen.findByText(/VTEST123/)).toBeVisible();
  });
});
