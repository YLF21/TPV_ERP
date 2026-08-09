// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "../api/client";
import { SaleTicketInvoiceDialog } from "./SaleTicketInvoiceDialog";

vi.mock("../api/client", async (importOriginal) => ({
  ...await importOriginal<typeof import("../api/client")>(),
  apiRequest: vi.fn(),
}));
const request = vi.mocked(apiRequest);
const terminalContext = { storeName: "Tienda", terminalCode: "001" };
const printInvoice = vi.fn();

describe("SaleTicketInvoiceDialog", () => {
  beforeEach(() => {
    request.mockReset();
    printInvoice.mockReset();
    printInvoice.mockResolvedValue({ status: "PRINTED" });
    request.mockImplementation(async (path) => {
      if (path === "/tickets/last-current-terminal"
        || path === "/tickets/by-number?number=T-001") {
        return {
          id: "ticket-1",
          numero: "T-001",
          fecha: "2026-07-30",
          total: "15.00",
        } as never;
      }
      if (path === "/customers/sale-options/search?q=Cliente&limit=25") {
        return [{
          id: "customer-1",
          clientId: "C1",
          fiscalName: "Cliente Uno",
          documentNumber: "B12345678",
          active: true,
        }] as never;
      }
      if (path === "/tickets/ticket-1/invoice") return { id: "invoice-1" } as never;
      if (path === "/invoices/invoice-1/print-document") return {
          documentId: "invoice-1",
          documentType: "FACTURA_VENTA",
          documentNumber: "FV-001",
          lines: [],
          total: "15.00",
      } as never;
      throw new Error(`Unexpected request: ${path}`);
    });
  });

  afterEach(cleanup);

  it("defaults to the latest terminal ticket and converts it for the selected customer", async () => {
    const onFiscalMutation = vi.fn();
    render(
      <SaleTicketInvoiceDialog
        token="token"
        locale="es"
        terminalContext={terminalContext}
        printInvoice={printInvoice}
        onClose={vi.fn()}
        onFiscalMutation={onFiscalMutation}
      />,
    );

    expect(await screen.findByText("T-001")).toBeInTheDocument();
    const dialog = screen.getByRole("dialog", { name: "Convertir ticket a factura" });
    expect(dialog).toHaveClass("sale-ticket-invoice-dialog");
    expect(dialog.querySelector("header kbd")).not.toBeInTheDocument();
    expect(screen.getByText("Total")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cerrar" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Buscar" })).not.toBeInTheDocument();
    const ticketNumber = screen.getByLabelText("Código de ticket") as HTMLInputElement;
    expect(ticketNumber).toHaveFocus();
    expect(ticketNumber.selectionStart).toBe(0);
    expect(ticketNumber.selectionEnd).toBe(ticketNumber.value.length);
    fireEvent.submit(ticketNumber.closest("form")!);
    const customerSearch = screen.getByLabelText("Buscar cliente fiscal");
    await waitFor(() => expect(customerSearch).toHaveFocus());
    fireEvent.change(customerSearch, {
      target: { value: "Cliente" },
    });
    fireEvent.click(await screen.findByText("Cliente Uno"));
    fireEvent.keyDown(customerSearch, { key: "Enter" });

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/tickets/ticket-1/invoice",
      {
        token: "token",
        body: { customerId: "customer-1" },
      },
    ));
    expect(printInvoice).toHaveBeenCalledWith(
      expect.objectContaining({
        kind: "COMMERCIAL_DOCUMENT",
        documentType: "FACTURA_VENTA",
        documentNumber: "FV-001",
      }),
      terminalContext,
      "es",
    );
    expect(onFiscalMutation).toHaveBeenCalledOnce();
  });

  it("loads the selected ticket number when opened from a report", async () => {
    request.mockImplementation(async (path) => {
      if (path === "/tickets/by-number?number=T-REPORT-7") {
        return {
          id: "ticket-report-7",
          numero: "T-REPORT-7",
          fecha: "2026-08-05",
          total: "31.50",
        } as never;
      }
      throw new Error(`Unexpected request: ${path}`);
    });

    render(
      <SaleTicketInvoiceDialog
        token="token"
        locale="es"
        terminalContext={terminalContext}
        printInvoice={printInvoice}
        initialTicketNumber="T-REPORT-7"
        onClose={vi.fn()}
      />,
    );

    expect(await screen.findByText("T-REPORT-7")).toBeInTheDocument();
    expect(request).toHaveBeenCalledWith(
      "/tickets/by-number?number=T-REPORT-7",
      { token: "token" },
    );
    expect(request).not.toHaveBeenCalledWith(
      "/tickets/last-current-terminal",
      expect.anything(),
    );
  });

  it("retries only printing when the invoice was already created", async () => {
    printInvoice
      .mockResolvedValueOnce({ status: "FAILED", technicalMessage: "offline" })
      .mockResolvedValueOnce({ status: "PRINTED" });
    render(
      <SaleTicketInvoiceDialog
        token="token"
        locale="es"
        terminalContext={terminalContext}
        printInvoice={printInvoice}
        onClose={vi.fn()}
      />,
    );

    await screen.findByText("T-001");
    const customerSearch = screen.getByLabelText("Buscar cliente fiscal");
    fireEvent.change(customerSearch, { target: { value: "Cliente" } });
    fireEvent.click(await screen.findByText("Cliente Uno"));
    fireEvent.keyDown(customerSearch, { key: "Enter" });

    const retry = await screen.findByRole("button", { name: /Reintentar impresi/ });
    expect(screen.getByRole("alert")).toHaveTextContent(
      "La factura se ha creado, pero no se pudo imprimir.",
    );
    fireEvent.click(retry);

    await waitFor(() => expect(printInvoice).toHaveBeenCalledTimes(2));
    expect(request.mock.calls.filter(([path]) => path === "/tickets/ticket-1/invoice"))
      .toHaveLength(1);
  });

  it("shows inactive customers as unavailable and never selects them", async () => {
    request.mockImplementation(async (path) => {
      if (path === "/tickets/last-current-terminal") {
        return {
          id: "ticket-1",
          numero: "T-001",
          fecha: "2026-07-30",
          total: "15.00",
        } as never;
      }
      if (path === "/customers/sale-options/search?q=Baja&limit=25") {
        return [{
          id: "customer-inactive",
          clientId: "C9",
          fiscalName: "Cliente Baja",
          documentNumber: "B99999999",
          active: false,
        }] as never;
      }
      throw new Error(`Unexpected request: ${path}`);
    });

    render(
      <SaleTicketInvoiceDialog
        token="token"
        locale="es"
        terminalContext={terminalContext}
        printInvoice={printInvoice}
        onClose={vi.fn()}
      />,
    );

    await screen.findByText("T-001");
    fireEvent.change(screen.getByLabelText("Buscar cliente fiscal"), {
      target: { value: "Baja" },
    });

    const inactiveName = await screen.findByText("Cliente Baja");
    const inactiveRow = inactiveName.closest("tr");
    expect(inactiveRow).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByText("Desactivado")).toBeInTheDocument();
    fireEvent.click(inactiveName);
    expect(screen.getByRole("button", { name: "Crear factura" })).toBeDisabled();
    expect(request).not.toHaveBeenCalledWith(
      "/tickets/ticket-1/invoice",
      expect.anything(),
    );
  });

  it("shows an already invoiced ticket as a business error without trace reference", async () => {
    render(
      <SaleTicketInvoiceDialog
        token="token"
        locale="es"
        terminalContext={terminalContext}
        printInvoice={printInvoice}
        onClose={vi.fn()}
      />,
    );

    await screen.findByText("T-001");
    fireEvent.change(screen.getByLabelText("Buscar cliente fiscal"), {
      target: { value: "Cliente" },
    });
    fireEvent.click(await screen.findByText("Cliente Uno"));
    request.mockRejectedValueOnce(new ApiError(
      "Este ticket ya está facturado (Ref: trace-123)",
      409,
      { code: "TICKET_ALREADY_INVOICED" },
      "trace-123",
    ));

    fireEvent.click(screen.getByRole("button", { name: "Crear factura" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Este ticket ya está facturado.");
    expect(alert).not.toHaveTextContent("Ref:");
  });

  it("blocks duplicate conversion while the invoice is being created", async () => {
    let finishConversion!: (value: { id: string }) => void;
    const pendingConversion = new Promise<{ id: string }>((resolve) => {
      finishConversion = resolve;
    });
    request.mockImplementation(async (path) => {
      if (path === "/tickets/last-current-terminal") {
        return {
          id: "ticket-1",
          numero: "T-001",
          fecha: "2026-07-30",
          total: "15.00",
        } as never;
      }
      if (path === "/customers/sale-options/search?q=Cliente&limit=25") {
        return [{
          id: "customer-1",
          clientId: "C1",
          fiscalName: "Cliente Uno",
          documentNumber: "B12345678",
          active: true,
        }] as never;
      }
      if (path === "/tickets/ticket-1/invoice") {
        return await pendingConversion as never;
      }
      if (path === "/invoices/invoice-1/print-document") {
        return {
          documentId: "invoice-1",
          documentType: "FACTURA_VENTA",
          documentNumber: "FV-001",
          lines: [],
          total: "15.00",
        } as never;
      }
      throw new Error(`Unexpected request: ${path}`);
    });

    render(
      <SaleTicketInvoiceDialog
        token="token"
        locale="es"
        terminalContext={terminalContext}
        printInvoice={printInvoice}
        onClose={vi.fn()}
      />,
    );
    await screen.findByText("T-001");
    const customerSearch = screen.getByLabelText("Buscar cliente fiscal");
    fireEvent.change(customerSearch, { target: { value: "Cliente" } });
    fireEvent.click(await screen.findByText("Cliente Uno"));
    fireEvent.click(screen.getByRole("button", { name: "Crear factura" }));

    const progress = await screen.findByRole("button", { name: "Creando factura…" });
    expect(progress).toBeDisabled();
    expect(screen.getByRole("dialog", { name: "Convertir ticket a factura" }))
      .toHaveAttribute("aria-busy", "true");
    expect(request.mock.calls.filter(([path]) => path === "/tickets/ticket-1/invoice"))
      .toHaveLength(1);

    finishConversion({ id: "invoice-1" });
    await screen.findByText("Factura creada e impresa correctamente.");
  });
});
