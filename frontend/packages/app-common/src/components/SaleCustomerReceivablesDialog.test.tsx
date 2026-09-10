// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { UserSession } from "../types";
import { SaleCustomerReceivablesDialog } from "./SaleCustomerReceivablesDialog";

const session: UserSession = {
  username: "venta",
  displayName: "Venta",
  accessToken: "token",
  permissions: ["CUSTOMER_RECEIVABLES_READ", "CUSTOMER_RECEIVABLES_PAY"],
};

const rows = [
  {
    documentId: "doc-current",
    documentType: "FACTURA_VENTA",
    documentNumber: "FV-2",
    customerId: "customer-1",
    customerName: "Cliente Uno",
    issueDate: "2026-08-01",
    dueDate: "2026-08-31",
    total: "30.00",
    paidTotal: "10.00",
    pendingTotal: "20.00",
    status: "PARCIAL",
    overdue: false,
  },
  {
    documentId: "doc-overdue",
    documentType: "ALBARAN_VENTA",
    documentNumber: "AV-1",
    customerId: "customer-1",
    customerName: "Cliente Uno",
    issueDate: "2026-06-01",
    dueDate: "2026-06-30",
    total: "40.00",
    paidTotal: "0.00",
    pendingTotal: "40.00",
    status: "PENDIENTE",
    overdue: true,
  },
  {
    documentId: "doc-paid",
    documentType: "FACTURA_VENTA",
    documentNumber: "FV-PAID",
    customerId: "customer-1",
    customerName: "Cliente Uno",
    issueDate: "2026-05-01",
    dueDate: "2026-05-31",
    total: "15.00",
    paidTotal: "15.00",
    pendingTotal: "0.00",
    status: "PAGADO",
    overdue: false,
  },
] as const;

afterEach(() => { cleanup(); localStorage.clear(); });

describe("SaleCustomerReceivablesDialog", () => {
  it("shows only pending documents, overdue debt in red and opens one document at a time", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/customer-receivables?customerId=customer-1") return rows;
      if (path === "/payment-methods") return [{ id: "cash", name: "EFECTIVO", active: true }];
      if (path === "/terminal-configuration/payment") return {};
      throw new Error(`Unexpected request: ${path}`);
    });

    render(<SaleCustomerReceivablesDialog
      locale="es"
      session={session}
      terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      customer={{ id: "customer-1", clientId: "C-001", fiscalName: "Cliente Uno" }}
      request={request as never}
      onClose={vi.fn()}
    />);

    const dialog = await screen.findByRole("dialog", { name: "Documentos pendientes del cliente" });
    expect(within(dialog).getByText("60,00 €")).toHaveClass("debt");
    expect(within(dialog).getAllByText("40,00 €").some((node) => node.classList.contains("overdue-debt"))).toBe(true);
    expect(within(dialog).queryByText("FV-PAID")).not.toBeInTheDocument();

    const documentRows = within(dialog).getAllByRole("row").slice(1);
    expect(documentRows[0]).toHaveTextContent("AV-1");
    expect(within(documentRows[0]).getAllByText("40,00 €").some((node) => node.classList.contains("overdue-debt"))).toBe(true);

    fireEvent.keyDown(dialog.querySelector("section")!, { key: "Enter" });
    const payment = await screen.findByRole("dialog", { name: "COBRO" });
    expect(within(payment).getByLabelText("IMPORTE / RECIBIDO")).toHaveValue("40,00");
    expect(request).toHaveBeenCalledWith("/payment-methods", expect.anything());
  });

  it("navigates with arrows, opens by double click and closes with Escape", async () => {
    const onClose = vi.fn();
    const request = vi.fn(async (path: string) => {
      if (path === "/payment-methods") return [{ id: "cash", name: "EFECTIVO", active: true }];
      if (path === "/terminal-configuration/payment") return {};
      return rows;
    });
    render(<SaleCustomerReceivablesDialog
      locale="es"
      session={session}
      terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      customer={{ id: "customer-1", clientId: "C-001", fiscalName: "Cliente Uno" }}
      request={request as never}
      onClose={onClose}
    />);

    const dialog = await screen.findByRole("dialog", { name: "Documentos pendientes del cliente" });
    const section = dialog.querySelector("section")!;
    fireEvent.keyDown(section, { key: "ArrowDown" });
    await waitFor(() => expect(within(dialog).getByRole("row", { name: /FV-2/ })).toHaveAttribute("aria-current", "true"));
    fireEvent.keyDown(section, { key: "Enter" });
    const firstPayment = await screen.findByRole("dialog", { name: "COBRO" });
    expect(within(firstPayment).getByLabelText("IMPORTE / RECIBIDO")).toHaveValue("20,00");

    fireEvent.click(within(firstPayment).getAllByRole("button", { name: "CANCELAR" })[0]);
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "COBRO" })).not.toBeInTheDocument());
    const overdueRow = within(dialog).getByRole("row", { name: /AV-1/ });
    fireEvent.doubleClick(overdueRow);
    const secondPayment = await screen.findByRole("dialog", { name: "COBRO" });
    expect(within(secondPayment).getByLabelText("IMPORTE / RECIBIDO")).toHaveValue("40,00");

    fireEvent.click(within(secondPayment).getAllByRole("button", { name: "CANCELAR" })[0]);
    fireEvent.keyDown(section, { key: "Escape" });
    expect(onClose).toHaveBeenCalledOnce();
  });

  it.each(["Tarjeta", "Transferencia", "Efectivo"])("retains a failed %s receipt after payment closes and retries without collecting the next document", async (method) => {
    const paid = { ...rows[1], paidTotal: "40.00", pendingTotal: "0.00", status: "PAGADO" };
    const receipt = { paymentId: "paid-request", documentNumber: "AV-1", collectedAt: "2026-08-11T10:00:00Z", method: method.toUpperCase(), amount: "40.00", remaining: "0.00", renderedPdf: null, ticketRenderedImage: null };
    const refreshed = { ...receipt, renderedPdf: { contentType: "application/pdf", base64: "fresh-pdf" }, ticketRenderedImage: { contentType: "image/png", base64: "fresh-png" } };
    let paymentRecorded = false;
    const onClose = vi.fn();
    let receiptRequests = 0;
    let resolveReceipt!: (value: unknown) => void;
    const request = vi.fn(async (path: string) => {
      if (path === "/customer-receivables?customerId=customer-1") return paymentRecorded ? [rows[0], paid, rows[2]] : rows;
      if (path === "/payment-methods") return [
        { id: "card", name: "TARJETA", active: true },
        { id: "cash", name: "EFECTIVO", active: true },
        { id: "transfer", name: "TRANSFERENCIA", active: true },
      ];
      if (path === "/terminal-configuration/payment") return { rules: { cardManualEnabled: true } };
      if (path === "/customer-receivables/doc-overdue/payments") {
        paymentRecorded = true;
        return { receivable: paid, paymentReceipt: receipt };
      }
      if (path === "/customer-receivables/doc-overdue/payments/paid-request/receipt") {
        receiptRequests += 1;
        return receiptRequests === 1 ? new Promise((resolve) => { resolveReceipt = resolve; }) : refreshed;
      }
      throw new Error(path);
    });
    const printReceipt = vi.fn().mockResolvedValueOnce({ status: "FAILED" })
      .mockResolvedValueOnce({ status: "FAILED" }).mockResolvedValue({ status: "PRINTED" });
    render(<SaleCustomerReceivablesDialog
      locale="es" session={session} terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      customer={{ id: "customer-1", clientId: "C-001", fiscalName: "Cliente Uno" }}
      request={request as never} printReceipt={printReceipt} onClose={onClose}
    />);
    const dialog = await screen.findByRole("dialog", { name: "Documentos pendientes del cliente" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Cobrar" }));
    const payment = await screen.findByRole("dialog", { name: "COBRO" });
    await waitFor(() => expect(within(payment).getByRole("button", { name: method })).toBeEnabled());
    fireEvent.click(within(payment).getByRole("button", { name: method }));
    fireEvent.click(within(payment).getByRole("button", { name: "ACEPTAR" }));
    if (method === "Efectivo") {
      const completion = await screen.findByRole("region", { name: "Pago completado" });
      fireEvent.click(await within(completion).findByRole("button", { name: "Cerrar" }));
    }
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "COBRO" })).not.toBeInTheDocument());
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Cobro realizado; impresión pendiente. No repitas el cobro.");
    await waitFor(() => expect(within(dialog).getByRole("row", { name: /FV-2/ })).toHaveAttribute("aria-current", "true"));
    expect(within(dialog).queryByRole("row", { name: /AV-1/ })).not.toBeInTheDocument();
    const retry = within(dialog).getByRole("button", { name: "Reintentar impresión" });
    fireEvent.click(retry);
    fireEvent.click(retry);
    expect(within(dialog).getByRole("button", { name: "Imprimiendo..." })).toBeDisabled();
    expect(within(dialog).getByRole("button", { name: "Cobrar" })).toBeDisabled();
    expect(within(dialog).getAllByRole("button", { name: "Cerrar" }).every((button) => (button as HTMLButtonElement).disabled)).toBe(true);
    fireEvent.keyDown(dialog.querySelector("section")!, { key: "Escape" });
    expect(onClose).not.toHaveBeenCalled();
    await waitFor(() => expect(receiptRequests).toBe(1));
    await act(async () => { resolveReceipt(refreshed); });
    fireEvent.click(await within(dialog).findByRole("button", { name: "Reintentar impresión" }));
    await waitFor(() => expect(within(dialog).queryByRole("alert")).not.toBeInTheDocument());
    expect(printReceipt).toHaveBeenLastCalledWith(refreshed, expect.anything(), undefined, "es");
    expect(request).toHaveBeenCalledWith("/customer-receivables/doc-overdue/payments/paid-request/receipt", { token: "token" });
    expect(receiptRequests).toBe(2);
    expect(request.mock.calls.filter(([path]) => path.endsWith("/payments"))).toHaveLength(1);
    expect(request.mock.calls.some(([path]) => path.startsWith("/customer-receivables/doc-current/payments"))).toBe(false);
  });
});
