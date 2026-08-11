// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { getHardwareBridge } from "../hardware/hardware";
import { SaleTicketCancellationDialog } from "./SaleTicketCancellationDialog";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));
vi.mock("../hardware/hardware", () => ({ getHardwareBridge: vi.fn() }));

const request = vi.mocked(apiRequest);
const hardware = vi.mocked(getHardwareBridge);

const preview = {
  ticket: {
    id: "ticket-1",
    numero: "T-001",
    fecha: "2026-07-30",
    total: "25.00",
  },
  manualReferences: [{
    paymentId: "payment-1",
    paymentMethod: "TRANSFERENCIA",
    amount: "25.00",
  }],
  integratedCardPayments: [],
  cashAmount: "0.00",
  openCashDrawer: false,
  consumedVoucherCodes: [],
  generatedVoucherCodes: [],
};

const cancellationReceipt = {
  operationId: "operation-1",
  originalTicketNumber: "T-001",
  originalIssuedAt: "2026-07-30T10:00:00Z",
  cancelledAt: "2026-07-30T12:00:00Z",
  total: "25.00",
  reason: "Error de cobro",
  operatorUsername: "CAJERO",
  authorizerUsername: "ADMIN",
  delegated: true,
  payments: [{
    method: "TRANSFERENCIA",
    amount: "25.00",
    reference: "DEV-123",
  }],
};

describe("SaleTicketCancellationDialog", () => {
  beforeEach(() => {
    request.mockReset();
    hardware.mockReset();
    localStorage.clear();
    hardware.mockReturnValue({
      openCashDrawer: vi.fn().mockResolvedValue({ ok: true }),
      printTicket: vi.fn().mockResolvedValue({ ok: true }),
    } as never);
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
  });

  it("loads the latest ticket and requires the privileged user's password", async () => {
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    hardware.mockReturnValue({
      openCashDrawer: vi.fn().mockResolvedValue({ ok: true }),
      printTicket,
    } as never);
    request.mockImplementation(async (path) => {
      if (path === "/tickets/cancellation-preview/last") return preview as never;
      if (path === "/tickets/ticket-1/cancel") {
        return {
          ticket: preview.ticket,
          restoredVouchers: [],
          invalidatedVoucherCodes: [],
          openCashDrawer: false,
          receipt: cancellationReceipt,
        } as never;
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    const onFiscalMutation = vi.fn();
    render(
      <SaleTicketCancellationDialog
        token="token"
        locale="es"
        permissions={["GESTION_VENTAS"]}
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        mode="LAST"
        onClose={vi.fn()}
        onFiscalMutation={onFiscalMutation}
      />,
    );

    expect(await screen.findByText("T-001")).toBeInTheDocument();
    const dialog = screen.getByRole("dialog", { name: "Anular ticket" });
    expect(dialog).toHaveClass("sale-ticket-cancellation-dialog");
    expect(dialog.querySelector("header kbd")).not.toBeInTheDocument();
    expect(screen.getByText("Total")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cerrar" })).toBeInTheDocument();
    expect(screen.queryByLabelText("Usuario autorizador")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Motivo"), {
      target: { value: "Error de cobro" },
    });
    fireEvent.change(screen.getByLabelText(/Referencia de devoluci/), {
      target: { value: "DEV-123" },
    });
    fireEvent.change(screen.getByLabelText(/contrase/i), {
      target: { value: "secret" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Anular y compensar" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/tickets/ticket-1/cancel",
      expect.objectContaining({
        token: "token",
        body: expect.objectContaining({
          reason: "Error de cobro",
          authorizerPassword: "secret",
          manualCompensations: { "payment-1": "DEV-123" },
        }),
      }),
    ));
    expect(onFiscalMutation).toHaveBeenCalledOnce();
    expect(printTicket).toHaveBeenCalledWith(expect.objectContaining({
      layout: "CANCELLATION_RECEIPT",
      documentNumber: "AN-T-001",
      title: "COMPROBANTE DE ANULACIÓN",
      notice: "DOCUMENTO NO FISCAL",
      total: 25,
      payments: [{
        method: "TRANSFERENCIA",
        amount: 25,
        reference: "DEV-123",
      }],
    }));
  });

  it("moves from the password to the cancellation button before confirming with Enter", async () => {
    const user = userEvent.setup();
    request.mockImplementation(async (path) => {
      if (path === "/tickets/cancellation-preview/last") {
        return { ...preview, manualReferences: [] } as never;
      }
      if (path === "/tickets/ticket-1/cancel") {
        return {
          ticket: preview.ticket,
          restoredVouchers: [],
          invalidatedVoucherCodes: [],
          openCashDrawer: false,
          receipt: cancellationReceipt,
        } as never;
      }
      throw new Error(`Unexpected request: ${path}`);
    });

    render(
      <SaleTicketCancellationDialog
        token="token"
        locale="es"
        permissions={["GESTION_VENTAS"]}
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        mode="LAST"
        onClose={vi.fn()}
      />,
    );

    expect(await screen.findByText("T-001")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Motivo"), "Error de cobro");
    const passwordInput = screen.getByLabelText(/contrase/i);
    const confirmButton = screen.getByRole("button", { name: "Anular y compensar" });

    await user.type(passwordInput, "secret{Enter}");

    expect(confirmButton).toHaveFocus();
    expect(request).not.toHaveBeenCalledWith(
      "/tickets/ticket-1/cancel",
      expect.anything(),
    );

    await user.keyboard("{Enter}");

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/tickets/ticket-1/cancel",
      expect.objectContaining({
        body: expect.objectContaining({ authorizerPassword: "secret" }),
      }),
    ));
  });

  it("allows retrying only the receipt when printing fails after cancellation", async () => {
    const printTicket = vi.fn()
      .mockResolvedValueOnce({ ok: false, message: "Printer offline" })
      .mockResolvedValueOnce({ ok: true });
    hardware.mockReturnValue({
      openCashDrawer: vi.fn().mockResolvedValue({ ok: true }),
      printTicket,
    } as never);
    request.mockImplementation(async (path) => {
      if (path === "/tickets/cancellation-preview/last") {
        return { ...preview, manualReferences: [] } as never;
      }
      if (path === "/tickets/ticket-1/cancel") {
        return {
          ticket: preview.ticket,
          restoredVouchers: [],
          invalidatedVoucherCodes: [],
          openCashDrawer: false,
          receipt: cancellationReceipt,
        } as never;
      }
      throw new Error(`Unexpected request: ${path}`);
    });

    render(
      <SaleTicketCancellationDialog
        token="token"
        locale="es"
        permissions={["GESTION_VENTAS"]}
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        mode="LAST"
        onClose={vi.fn()}
      />,
    );

    expect(await screen.findByText("T-001")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Motivo"), {
      target: { value: "Error de cobro" },
    });
    fireEvent.change(screen.getByLabelText(/contrase/i), {
      target: { value: "secret" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Anular y compensar" }));

    const retry = await screen.findByRole("button", { name: "Reintentar impresión" });
    expect(request).toHaveBeenCalledTimes(2);
    fireEvent.click(retry);
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(2));
    expect(request).toHaveBeenCalledTimes(2);
    expect(await screen.findByText("Comprobante de anulación impreso correctamente."))
      .toBeInTheDocument();
  });

  it("requests delegated credentials from a seller", async () => {
    request.mockResolvedValue(preview as never);
    render(
      <SaleTicketCancellationDialog
        token="token"
        locale="es"
        permissions={["VENTA"]}
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        mode="LAST"
        onClose={vi.fn()}
      />,
    );

    expect(await screen.findByLabelText("Usuario autorizador")).toBeInTheDocument();
  });

  it("loads the selected ticket immediately when opened from the report", async () => {
    request.mockImplementation(async (path) => {
      if (path === "/tickets/cancellation-preview?number=T-REPORT-9") {
        return {
          ...preview,
          ticket: { ...preview.ticket, id: "ticket-report-9", numero: "T-REPORT-9" },
          manualReferences: [],
        } as never;
      }
      throw new Error(`Unexpected request: ${path}`);
    });

    render(
      <SaleTicketCancellationDialog
        token="token"
        locale="es"
        permissions={["GESTION_VENTAS"]}
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        mode="BY_NUMBER"
        initialTicketNumber="T-REPORT-9"
        onClose={vi.fn()}
      />,
    );

    expect(await screen.findByText("T-REPORT-9")).toBeInTheDocument();
    expect(request).toHaveBeenCalledWith(
      "/tickets/cancellation-preview?number=T-REPORT-9",
      { token: "token" },
    );
  });

  it("continues with a full corrective invoice when Ctrl+F11 receives an invoice number", async () => {
    const invoicePreview = {
      sourceType: "SALES_INVOICE",
      sourceCode: "FV-001-26-000019",
      ticketId: "invoice-19",
      ticketNumber: "FV-001-26-000019",
      date: "2026-08-10",
      total: "1000000.00",
      lines: [{
        lineId: "invoice-line-1",
        productId: "product-1",
        code: "ART-1",
        name: "ARTICULO",
        lineType: "PRODUCT",
        productType: "UNIT",
        refundableQuantity: "1.000",
        unitPrice: "1000000.00",
        refundableTotal: "1000000.00",
        refundableSerialNumbers: [],
        discount: "0.00",
        taxesIncluded: true,
        taxRegime: "IGIC",
        taxPercentage: "7.00",
      }],
      paymentAvailability: [],
    };
    request.mockImplementation(async (path) => {
      if (path === "/tickets/cancellation-preview?number=FV-001-26-000019") {
        throw Object.assign(new Error("Ticket no encontrado"), {
          problem: { code: "TICKET_NOT_FOUND" },
        });
      }
      if (path === "/tickets/return-preview?ticketNumber=FV-001-26-000019") {
        return invoicePreview as never;
      }
      if (path === "/tickets/return-valuation") {
        return {
          selectedGross: "1000000.00",
          lostBenefits: "0.00",
          refundableAmount: "1000000.00",
          eligibleRefundableAmount: "1000000.00",
          cumulativeEligibleRefundableAmount: "1000000.00",
          cumulativeRefundableAmount: "1000000.00",
          previouslyRefundedAmount: "0.00",
          remainingBasketValue: "0.00",
        } as never;
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    const onInvoiceAddToCart = vi.fn();

    render(
      <SaleTicketCancellationDialog
        token="token"
        locale="es"
        permissions={["GESTION_VENTAS"]}
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        mode="BY_NUMBER"
        initialTicketNumber="FV-001-26-000019"
        onClose={vi.fn()}
        onInvoiceAddToCart={onInvoiceAddToCart}
      />,
    );

    expect(await screen.findByRole("dialog", { name: "Anular operación facturada" }))
      .toBeInTheDocument();
    expect(await screen.findByText("ARTICULO")).toBeInTheDocument();
    const continueButton = screen.getByRole("button", { name: "Preparar rectificativa completa" });
    await waitFor(() => expect(continueButton).toBeEnabled());
    fireEvent.click(continueButton);

    expect(onInvoiceAddToCart).toHaveBeenCalledWith([
      expect.objectContaining({
        sourceType: "SALES_INVOICE",
        sourceCode: "FV-001-26-000019",
        lineId: "invoice-line-1",
        returnQuantity: 1,
      }),
    ]);
  });

  it("keeps ticket cancellation available but blocks invoice rectification when the cart is not empty", async () => {
    request.mockRejectedValue(Object.assign(new Error("Ticket no encontrado"), {
      problem: { code: "TICKET_NOT_FOUND" },
    }));

    render(
      <SaleTicketCancellationDialog
        token="token"
        locale="es"
        permissions={["GESTION_VENTAS"]}
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        mode="BY_NUMBER"
        initialTicketNumber="FV-001-26-000019"
        canStartInvoiceCancellation={false}
        onClose={vi.fn()}
        onInvoiceAddToCart={vi.fn()}
      />,
    );

    expect(await screen.findByRole("alert"))
      .toHaveTextContent("El carrito debe estar vacío para anular una operación facturada");
    expect(request).toHaveBeenCalledTimes(1);
  });

  it("shows a clear warning when the ticket already has partial returns", async () => {
    request.mockRejectedValue(Object.assign(new Error("generic conflict"), {
      problem: { code: "TICKET_HAS_PREVIOUS_RETURNS" },
    }));

    render(
      <SaleTicketCancellationDialog
        token="token"
        locale="es"
        permissions={["GESTION_VENTAS"]}
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        mode="BY_NUMBER"
        initialTicketNumber="T-RETURNED"
        onClose={vi.fn()}
      />,
    );

    const warning = await screen.findByRole("status");
    expect(warning).toHaveTextContent("Ticket con devoluciones anteriores");
    expect(warning).toHaveTextContent(
      "Este ticket ya tiene devoluciones parciales y no puede anularse completo. Utiliza F10 para devolver los artículos restantes.",
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
