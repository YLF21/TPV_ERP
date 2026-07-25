// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { printConfirmedTicketAutomatically } from "../sale/ticketPrinting";
import { TicketReturnDialog } from "./TicketReturnDialog";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));
vi.mock("../sale/ticketPrinting", () => ({
  printConfirmedTicketAutomatically: vi.fn(),
}));

const request = vi.mocked(apiRequest);
const print = vi.mocked(printConfirmedTicketAutomatically);

describe("TicketReturnDialog", () => {
  beforeEach(() => {
    request.mockReset();
    print.mockReset();
    localStorage.clear();
    request.mockImplementation(async (path) => {
      if (path === "/tickets/return-preview?ticketNumber=T-001") {
        return {
          ticketId: "ticket-1",
          ticketNumber: "T-001",
          date: "2026-07-24",
          total: "20.00",
          payments: [],
          lines: [{
            lineId: "line-1",
            code: "PORTATIL",
            name: "Portátil",
            lineType: "PRODUCT",
            purchasedQuantity: 2,
            refundableQuantity: 1,
            unitPrice: "10.00",
            refundableTotal: "10.00",
            serialNumbers: ["SN-001", "SN-002"],
            refundableSerialNumbers: ["SN-002"],
          }],
        } as never;
      }
      if (path === "/tickets/ticket-1/returns") {
        return {
          documentId: "return-1",
          receipt: {
            documentId: "return-1",
            documentNumber: "T-RET-1",
            issuedAt: "2026-07-24T12:00:00Z",
            lines: [],
            payments: [],
            total: -10,
          },
        } as never;
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    print.mockResolvedValue({ status: "PRINTED" });
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
  });

  it("searches by exact ticket code and returns only the still available serial number", async () => {
    const onFiscalMutation = vi.fn();
    render(
      <TicketReturnDialog
        token="token"
        locale="es"
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        onClose={vi.fn()}
        onFiscalMutation={onFiscalMutation}
      />,
    );

    fireEvent.change(screen.getByLabelText("Código de ticket"), {
      target: { value: "T-001" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Buscar ticket" }));

    expect(await screen.findByText("S/N: SN-002")).toBeInTheDocument();
    expect(screen.queryByText("S/N: SN-001")).toBeNull();
    fireEvent.click(screen.getByLabelText("S/N: SN-002"));
    fireEvent.change(screen.getByLabelText("Contraseña del usuario actual"), {
      target: { value: "1234" },
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "Confirmar devolución" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Confirmar devolución" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/tickets/ticket-1/returns",
      expect.objectContaining({
        token: "token",
        method: "POST",
        body: expect.objectContaining({
          cashAmount: "10.00",
          lines: [{
            lineId: "line-1",
            quantity: "1",
            serialNumbers: ["SN-002"],
          }],
        }),
      }),
    ));
    expect(onFiscalMutation).toHaveBeenCalledOnce();
    expect(print).toHaveBeenCalledOnce();
  });
});
