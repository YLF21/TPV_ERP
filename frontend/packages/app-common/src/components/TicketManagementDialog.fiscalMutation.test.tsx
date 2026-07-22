// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { printConfirmedTicketAutomatically } from "../sale/ticketPrinting";
import { TicketManagementDialog } from "./TicketManagementDialog";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));
vi.mock("../sale/ticketPrinting", () => ({
  printConfirmedTicketAutomatically: vi.fn()
}));

const request = vi.mocked(apiRequest);
const printTicket = vi.mocked(printConfirmedTicketAutomatically);

const confirmedTicket = {
  id: "ticket-1",
  estado: "CONFIRMADO",
  numero: "T-001",
  fecha: "2026-07-21",
  customerId: null,
  customerName: null,
  total: "10.00",
  pendingTotal: "10.00",
  payments: []
};
const customer = { id: "customer-1", fiscalName: "Cliente Fiscal", clientId: "C-001" };
const voucher = { code: "VALE-1", balance: "25.00", status: "ACTIVE" };
const printSnapshot = {
  documentId: "ticket-1",
  documentNumber: "T-001",
  issuedAt: "2026-07-21T12:00:00Z",
  lines: [{ name: "Producto", quantity: 1, price: 10, total: 10 }],
  payments: [{ method: "EFECTIVO", amount: 10 }],
  total: 10
};
const refundOptions = [{
  lineId: "line-1",
  code: "P-1",
  name: "Producto",
  lineType: "PRODUCT",
  purchasedQuantity: 1,
  refundableQuantity: 1,
  unitPrice: "10.00",
  refundableTotal: "10.00"
}];

let loadedTickets = [confirmedTicket];
let operationFailurePath = "";

beforeEach(() => {
  loadedTickets = [confirmedTicket];
  operationFailurePath = "";
  request.mockReset();
  printTicket.mockReset();
  localStorage.clear();
  request.mockImplementation(async (path) => {
    if (path === "/tickets") return loadedTickets as never;
    if (path === "/customers/sale-options") return [customer] as never;
    if (path === "/vouchers") return [voucher] as never;
    if (path === "/tickets/ticket-1/return-options") return refundOptions as never;
    if (path === "/tickets/ticket-1/print") return printSnapshot as never;
    if (path === operationFailurePath) throw new Error("operación rechazada");
    if (path === "/tickets/ticket-1/returns") {
      return { receipt: printSnapshot } as never;
    }
    if (path.startsWith("/tickets/") || path.startsWith("/vouchers/")) {
      return {} as never;
    }
    throw new Error(`Petición inesperada: ${path}`);
  });
  printTicket.mockResolvedValue({ status: "PRINTED" });
});

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe("TicketManagementDialog onFiscalMutation", () => {
  it("notifies after a successful cancellation and invoice conversion", async () => {
    const onFiscalMutation = vi.fn();
    renderDialog(onFiscalMutation);

    fireEvent.change(await screen.findByLabelText("Motivo de anulación"), {
      target: { value: "Error de cobro" }
    });
    fireEvent.click(screen.getByRole("button", { name: "Anular ticket" }));

    await waitFor(() => expect(onFiscalMutation).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText("Cliente para la factura"), {
      target: { value: customer.id }
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "Convertir" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Convertir" }));

    await waitFor(() => expect(onFiscalMutation).toHaveBeenCalledTimes(2));
    expect(request).toHaveBeenCalledWith("/tickets/ticket-1/cancel", {
      token: "token",
      method: "POST",
      body: { reason: "Error de cobro" }
    });
    expect(request).toHaveBeenCalledWith("/tickets/ticket-1/invoice", {
      token: "token",
      method: "POST",
      body: { customerId: customer.id }
    });
  });

  it("notifies a confirmed return even when its later printing fails", async () => {
    const onFiscalMutation = vi.fn();
    printTicket.mockResolvedValue({ status: "FAILED", technicalMessage: "printer offline" });
    renderDialog(onFiscalMutation);

    fireEvent.click(await screen.findByRole("button", { name: "Preparar devolución" }));
    fireEvent.change(await screen.findByLabelText("Contraseña del usuario actual"), {
      target: { value: "1234" }
    });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar devolución" }));

    await waitFor(() => expect(onFiscalMutation).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(printTicket).toHaveBeenCalledWith(
      printSnapshot,
      { storeName: "Tienda", terminalCode: "01" }
    ));
    expect(request).toHaveBeenCalledWith("/tickets/ticket-1/returns", expect.objectContaining({
      token: "token",
      method: "POST"
    }));
  });

  it("does not notify for reprinting or voucher consumption", async () => {
    loadedTickets = [confirmedTicket];
    const onFiscalMutation = vi.fn();
    renderDialog(onFiscalMutation);

    fireEvent.click(await screen.findByRole("button", { name: "Reimprimir ticket" }));
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(1));

    fireEvent.change(screen.getByLabelText("Vale activo"), {
      target: { value: voucher.code }
    });
    fireEvent.click(screen.getByRole("button", { name: "Consumir vale" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/vouchers/VALE-1/consume",
      expect.objectContaining({ method: "POST", token: "token" })
    ));
    await waitFor(() => expect(screen.getByRole("button", { name: "Consumir vale" })).toBeEnabled());

    expect(onFiscalMutation).not.toHaveBeenCalled();
  });

  it("does not notify when the fiscal operation fails", async () => {
    operationFailurePath = "/tickets/ticket-1/cancel";
    const onFiscalMutation = vi.fn();
    renderDialog(onFiscalMutation);

    fireEvent.change(await screen.findByLabelText("Motivo de anulación"), {
      target: { value: "Operación inválida" }
    });
    fireEvent.click(screen.getByRole("button", { name: "Anular ticket" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("operación rechazada");
    expect(onFiscalMutation).not.toHaveBeenCalled();
  });
});

function renderDialog(onFiscalMutation: () => void) {
  return render(
    <TicketManagementDialog
      token="token"
      locale="es"
      permissions={["ADMIN"]}
      terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      onClose={vi.fn()}
      onFiscalMutation={onFiscalMutation}
    />
  );
}
