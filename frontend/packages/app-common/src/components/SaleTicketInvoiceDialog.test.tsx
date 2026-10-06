// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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

  it("dismisses fiscal customer results on Escape or an outside press without closing or converting the invoice", async () => {
    const onClose = vi.fn();
    render(<SaleTicketInvoiceDialog token="token" locale="es" terminalContext={terminalContext} printInvoice={printInvoice} onClose={onClose} />);
    await screen.findByText("T-001");
    const search = screen.getByLabelText("Buscar cliente fiscal");
    fireEvent.change(search, { target: { value: "Cliente" } });
    await screen.findByText("Cliente Uno");
    expect(search).toHaveAttribute("aria-expanded", "true");
    fireEvent.keyDown(search, { key: "Escape" });
    expect(search).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText("Cliente Uno")).not.toBeInTheDocument();
    expect(search).toHaveValue("Cliente");
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.pointerDown(search);
    expect(screen.getByText("Cliente Uno")).toBeInTheDocument();
    fireEvent.pointerDown(screen.getByRole("heading", { name: "Convertir ticket a factura" }));
    expect(screen.queryByText("Cliente Uno")).not.toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(request.mock.calls.some(([path]) => path.endsWith("/invoice"))).toBe(false);
  });

  it("opens the Fin customer list, skips inactive customers and returns the chosen fiscal customer without converting", async () => {
    const original = request.getMockImplementation()!;
    request.mockImplementation(async (path, options) => path === "/customers/sale-options/search?q=&limit=50"
      ? [
        { id: "first", clientId: "C1", fiscalName: "Cliente Primero", active: true },
        { id: "inactive", clientId: "C2", fiscalName: "Cliente Baja", active: false },
        { id: "second", clientId: "C3", fiscalName: "Cliente Segundo", documentNumber: "B33333333", active: true },
      ] as never : original(path, options));
    render(<SaleTicketInvoiceDialog token="token" locale="es" terminalContext={terminalContext} printInvoice={printInvoice} onClose={vi.fn()} />);
    await screen.findByText("T-001");
    fireEvent.click(screen.getByRole("button", { name: "Lista de clientes" }));
    const list = screen.getByRole("dialog", { name: "Seleccionar cliente" });
    await within(list).findByRole("button", { name: "C1 · Cliente Primero" });
    expect(within(list).getAllByRole("columnheader")).toHaveLength(7);
    expect(within(list).getByRole("button", { name: "C2 · Cliente Baja" })).toBeDisabled();
    expect(request).toHaveBeenCalledWith("/customers/sale-options/search?q=&limit=50", { token: "token" });
    const search = within(list).getByRole("textbox", { name: "Buscar cliente" });
    fireEvent.keyDown(search, { key: "ArrowDown" });
    fireEvent.keyDown(search, { key: "Insert" });
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Seleccionar cliente" })).not.toBeInTheDocument());
    expect(screen.getByText("Cliente Segundo")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear factura" })).toBeEnabled();
    expect(request.mock.calls.some(([path]) => path.endsWith("/invoice"))).toBe(false);
    expect(printInvoice).not.toHaveBeenCalled();
  });

  it("closes only the customer list on Escape and restores its trigger", async () => {
    const original = request.getMockImplementation()!;
    request.mockImplementation(async (path, options) => path === "/customers/sale-options/search?q=&limit=50"
      ? [] as never : original(path, options));
    const onClose = vi.fn();
    render(<SaleTicketInvoiceDialog token="token" locale="es" terminalContext={terminalContext} printInvoice={printInvoice} onClose={onClose} />);
    await screen.findByText("T-001");
    await waitFor(() => expect(screen.getByRole("textbox", { name: "Código de ticket" })).toHaveFocus());
    const trigger = screen.getByRole("button", { name: "Lista de clientes" });
    trigger.focus();
    fireEvent.click(trigger);
    const list = screen.getByRole("dialog", { name: "Seleccionar cliente" });
    fireEvent.keyDown(within(list).getByRole("textbox", { name: "Buscar cliente" }), { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "Seleccionar cliente" })).not.toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Convertir ticket a factura" })).toBeInTheDocument();
    expect(trigger).toHaveFocus();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("keeps invoice creation disabled when the customer list contains only inactive customers", async () => {
    const original = request.getMockImplementation()!;
    request.mockImplementation(async (path, options) => path === "/customers/sale-options/search?q=&limit=50"
      ? [{ id: "inactive", clientId: "C9", fiscalName: "Cliente Baja", active: false }] as never : original(path, options));
    render(<SaleTicketInvoiceDialog token="token" locale="es" terminalContext={terminalContext} printInvoice={printInvoice} onClose={vi.fn()} />);
    await screen.findByText("T-001");
    fireEvent.click(screen.getByRole("button", { name: "Lista de clientes" }));
    const list = screen.getByRole("dialog", { name: "Seleccionar cliente" });
    await within(list).findByRole("button", { name: "C9 · Cliente Baja" });
    expect(within(list).getByRole("button", { name: "Seleccionar cliente" })).toBeDisabled();
    fireEvent.keyDown(within(list).getByRole("textbox", { name: "Buscar cliente" }), { key: "Enter" });
    expect(list).toBeInTheDocument();
    fireEvent.click(within(list).getAllByRole("button", { name: "Cerrar" })[0]);
    expect(screen.getByRole("button", { name: "Crear factura" })).toBeDisabled();
  });

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
    await waitFor(() => {
      expect(ticketNumber).toHaveFocus();
      expect(ticketNumber.selectionStart).toBe(0);
      expect(ticketNumber.selectionEnd).toBe(ticketNumber.value.length);
    });
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

  it("keeps an active customer selectable when the ticket already references it", async () => {
    request.mockImplementation(async (path) => {
      if (path === "/tickets/by-number?number=T-CUSTOMER") {
        return {
          id: "ticket-customer",
          numero: "T-CUSTOMER",
          fecha: "2026-08-11",
          total: "16.40",
          customerId: "customer-1",
          customerName: "Cliente Uno",
        } as never;
      }
      if (path === "/customers/sale-options/customer-1") {
        return {
          id: "customer-1",
          clientId: "C1",
          fiscalName: "Cliente Uno",
          documentNumber: "B12345678",
          active: true,
          activeMember: false,
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
        initialTicketNumber="T-CUSTOMER"
        onClose={vi.fn()}
      />,
    );

    expect(await screen.findAllByText("Cliente Uno")).toHaveLength(2);
    expect(screen.getByText("Activo")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crear factura" })).toBeEnabled();
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
