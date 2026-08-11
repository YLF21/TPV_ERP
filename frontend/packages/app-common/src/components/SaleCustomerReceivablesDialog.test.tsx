// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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

afterEach(cleanup);

describe("SaleCustomerReceivablesDialog", () => {
  it("shows only pending documents, overdue debt in red and opens one document at a time", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/customer-receivables?customerId=customer-1") return rows;
      if (path === "/payment-methods") return [];
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
    const payment = await screen.findByRole("dialog", { name: "Cobrar deuda" });
    expect(payment).toHaveTextContent("AV-1");
    expect(payment).not.toHaveTextContent("FV-2");
    expect(request).toHaveBeenCalledWith("/payment-methods", expect.anything());
  });

  it("navigates with arrows, opens by double click and closes with Escape", async () => {
    const onClose = vi.fn();
    const request = vi.fn(async (path: string) => path === "/payment-methods" ? [] : rows);
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
    const firstPayment = await screen.findByRole("dialog", { name: "Cobrar deuda" });
    expect(firstPayment).toHaveTextContent("FV-2");

    fireEvent.click(within(firstPayment).getAllByRole("button", { name: "Cerrar" })[0]);
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Cobrar deuda" })).not.toBeInTheDocument());
    const overdueRow = within(dialog).getByRole("row", { name: /AV-1/ });
    fireEvent.doubleClick(overdueRow);
    const secondPayment = await screen.findByRole("dialog", { name: "Cobrar deuda" });
    expect(secondPayment).toHaveTextContent("AV-1");

    fireEvent.click(within(secondPayment).getAllByRole("button", { name: "Cerrar" })[0]);
    fireEvent.keyDown(section, { key: "Escape" });
    expect(onClose).toHaveBeenCalledOnce();
  });
});
