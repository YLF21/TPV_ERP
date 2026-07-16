// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  addLocalDays,
  pendingCreateBody,
  pendingHasCardEffect,
  pendingSummary,
  type PendingSaleDraft,
} from "../sale/customerReceivables";
import { CustomerPendingSaleDialog } from "./CustomerPendingSaleDialog";

afterEach(cleanup);

const draft: PendingSaleDraft = {
  checkoutId: "checkout-1",
  warehouseId: "warehouse-1",
  type: "ALBARAN_VENTA",
  date: "2026-07-16",
  customerId: "customer-1",
  dueDate: "2026-08-15",
  globalDiscount: "0.00",
  lines: [{
    productId: "product-1", quantity: 1, code: "P-1", name: "Cafe", price: "10.00",
    discount: "0.00", taxesIncluded: true, taxRegime: "GENERAL", taxPercentage: "21.00",
  }],
};

describe("customer receivable checkout helpers", () => {
  it("uses local calendar days and integer cents", () => {
    expect(addLocalDays(new Date(2026, 6, 16, 23, 30), 30)).toBe("2026-08-15");
    expect(pendingSummary(1001, [
      { id: "cash", kind: "CASH", methodId: "m-cash", amountCents: 333, status: "APPROVED" },
      { id: "card", kind: "INTEGRATED_CARD", methodId: "m-card", amountCents: 200, status: "TIMEOUT" },
    ])).toEqual({ totalCents: 1001, paidCents: 333, pendingCents: 668 });
  });

  it.each(["PENDING", "SENT", "TIMEOUT", "APPROVED", "DECLINED", "ERROR", "CANCELLED"] as const)("treats %s as a durable card effect", (status) => {
    expect(pendingHasCardEffect([{ id: "card", kind: "INTEGRATED_CARD", methodId: "m-card", amountCents: 100, operationId: "op", status }])).toBe(true);
  });

  it("serializes only real approved payments and never invents PENDIENTE", () => {
    const body = pendingCreateBody(draft, [
      { id: "cash", kind: "CASH", methodId: "m-cash", amountCents: 400, deliveredCents: 500, changeCents: 100, status: "APPROVED" },
      { id: "timeout", kind: "INTEGRATED_CARD", methodId: "m-card", amountCents: 600, operationId: "op-1", status: "TIMEOUT" },
    ], 1000);
    expect(body.payments).toEqual([expect.objectContaining({ methodId: "m-cash", amount: "4.00", delivered: "5.00", change: "1.00" })]);
    expect(body.lines).toEqual([expect.objectContaining({ productoId: "product-1", cantidad: 1, precioUnitario: "10.00", impuestosIncluidos: true })]);
    expect(body.lines[0]).not.toHaveProperty("productId");
    expect(JSON.stringify(body)).not.toContain("PENDIENTE");
  });
});

describe("CustomerPendingSaleDialog", () => {
  it("quotes authoritatively, edits type/due date and submits without a fake pending payment", async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({ total: "10.01" })
      .mockResolvedValueOnce({ documentId: "doc-1", documentNumber: "AV-1" });
    const onSuccess = vi.fn();
    render(<CustomerPendingSaleDialog customerName="Cliente Pruebas" draft={draft} paymentMethods={{}} request={request} onCancel={vi.fn()} onSuccess={onSuccess} />);

    expect(screen.getByRole("dialog", { name: /venta pendiente/i })).toBeVisible();
    expect(screen.getByLabelText(/vencimiento/i)).toHaveValue("2026-08-15");
    fireEvent.change(screen.getByLabelText(/tipo de documento/i), { target: { value: "FACTURA_VENTA" } });
    fireEvent.change(screen.getByLabelText(/vencimiento/i), { target: { value: "2026-09-01" } });
    await screen.findAllByText("10,01");
    const confirm = screen.getByRole("button", { name: /confirmar venta pendiente/i });
    await waitFor(() => expect(confirm).toBeEnabled());
    fireEvent.click(confirm);

    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(expect.objectContaining({ documentId: "doc-1" })));
    const create = request.mock.calls[1];
    expect(create[0]).toBe("/pos/customer-pending-sales");
    expect(create[1].body).toMatchObject({ type: "FACTURA_VENTA", dueDate: "2026-09-01", quotedTotal: "10.01", payments: [] });
    expect(create[1].body).not.toHaveProperty("paymentMethod");
  });

  it("retains the draft after create failure and supports Escape before effects", async () => {
    const onCancel = vi.fn();
    const request = vi.fn().mockResolvedValueOnce({ total: "10.00" }).mockRejectedValueOnce(new Error("Servidor no disponible"));
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} paymentMethods={{}} request={request} onCancel={onCancel} onSuccess={vi.fn()} />);
    await screen.findAllByText("10,00");
    fireEvent.change(screen.getByLabelText(/vencimiento/i), { target: { value: "2026-09-03" } });
    fireEvent.click(screen.getByRole("button", { name: /confirmar venta pendiente/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Servidor no disponible");
    expect(screen.getByLabelText(/vencimiento/i)).toHaveValue("2026-09-03");
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onCancel).toHaveBeenCalledOnce();
  });

  it("requires a transfer reference and includes it as a real payment", async () => {
    const request = vi.fn().mockResolvedValueOnce({ total: "10.00" }).mockResolvedValueOnce({ documentId: "doc-1" });
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} paymentMethods={{ transfer: "transfer-method" }} request={request} onCancel={vi.fn()} onSuccess={vi.fn()} />);
    await screen.findAllByText("10,00");
    fireEvent.click(screen.getByRole("button", { name: /añadir transferencia/i }));
    const transfer = screen.getByRole("group", { name: /transferencia/i });
    fireEvent.change(within(transfer).getByLabelText(/importe/i), { target: { value: "4,25" } });
    fireEvent.click(within(transfer).getByRole("button", { name: /guardar transferencia/i }));
    expect(screen.getByRole("alert")).toHaveTextContent(/referencia/i);
    fireEvent.change(within(transfer).getByLabelText(/referencia/i), { target: { value: "TRX-44" } });
    fireEvent.click(within(transfer).getByRole("button", { name: /guardar transferencia/i }));
    expect(screen.getByText("5,75")).toBeInTheDocument();
  });

  it("keeps one stable card operation through timeout/query and never removes an approved card", async () => {
    const onCancel = vi.fn();
    const request = vi.fn()
      .mockResolvedValueOnce({ total: "10.00" })
      .mockRejectedValueOnce(new Error("Sin respuesta"))
      .mockResolvedValueOnce({ status: "APPROVED" });
    const view = render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} paymentMethods={{ card: "card-method" }} request={request} onCancel={onCancel} onSuccess={vi.fn()} />);
    await screen.findAllByText("10,00");
    fireEvent.click(screen.getByRole("button", { name: /añadir tarjeta/i }));
    const query = await screen.findByRole("button", { name: /consultar tarjeta/i });
    expect(screen.getByRole("button", { name: /confirmar venta pendiente/i })).toBeDisabled();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onCancel).not.toHaveBeenCalled();

    const chargeBody = request.mock.calls[1][1].body;
    const cardPayment = chargeBody.sale.payments[0];
    expect(cardPayment.requestId).toBe(cardPayment.paymentTerminalOperationId);
    expect(cardPayment.requestId).toBe(chargeBody.sale.checkoutId);
    expect(request.mock.calls[1][0]).toBe("/pos/customer-pending-sales/card-charges");
    fireEvent.click(query);

    expect(await screen.findByText(/tarjeta aprobada requiere anulaci[oó]n/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /eliminar/i })).not.toBeInTheDocument();
    expect(request.mock.calls[2][0]).toContain(cardPayment.paymentTerminalOperationId);
  });

  it("loads active tenant methods internally and reuses CashPaymentDialog", async () => {
    const request = vi.fn(async (path: string) => {
      if (path.endsWith("/quote")) return { total: "10.00" };
      if (path === "/payment-methods") return [
        { id: "cash-method", name: "EFECTIVO", active: true },
        { id: "card-method", name: "TARJETA", active: true },
        { id: "transfer-method", name: "TRANSFERENCIA", active: true },
        { id: "inactive", name: "OTRO", active: false },
      ];
      throw new Error(`unexpected ${path}`);
    });
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} request={request as never} onCancel={vi.fn()} onSuccess={vi.fn()} />);

    const cash = screen.getByRole("button", { name: /añadir efectivo/i });
    await waitFor(() => expect(cash).toBeEnabled());
    expect(screen.getByRole("button", { name: /añadir tarjeta/i })).toBeEnabled();
    expect(screen.getByRole("button", { name: /añadir transferencia/i })).toBeEnabled();
    fireEvent.click(cash);
    expect(screen.getByRole("dialog", { name: /cobro en efectivo/i })).toBeInTheDocument();
    expect(request).toHaveBeenCalledWith("/payment-methods", expect.objectContaining({ token: undefined }));
  });

  it("never confirms when the authoritative quote fails", async () => {
    const request = vi.fn().mockRejectedValue(new Error("Catalogo no disponible"));
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} paymentMethods={{}} request={request} onCancel={vi.fn()} onSuccess={vi.fn()} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Catalogo no disponible");
    expect(screen.getByRole("button", { name: /confirmar venta pendiente/i })).toBeDisabled();
  });

  it("freezes document identity after every durable card outcome until explicit removal", async () => {
    const onCancel = vi.fn();
    const request = vi.fn().mockResolvedValueOnce({ total: "10.00" }).mockResolvedValueOnce({ status: "DECLINED" });
    const view = render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} paymentMethods={{ card: "card-method" }} request={request} onCancel={onCancel} onSuccess={vi.fn()} />);
    await screen.findAllByText("10,00");
    fireEvent.click(screen.getByRole("button", { name: /añadir tarjeta/i }));
    expect(await screen.findByText(/DECLINED/)).toBeInTheDocument();

    const type = screen.getByLabelText(/tipo de documento/i);
    const dueDate = screen.getByLabelText(/vencimiento/i);
    expect(type).toBeDisabled();
    expect(dueDate).toBeDisabled();
    fireEvent.change(type, { target: { value: "FACTURA_VENTA" } });
    fireEvent.change(dueDate, { target: { value: "2027-01-01" } });
    expect(type).toHaveValue("ALBARAN_VENTA");
    expect(dueDate).toHaveValue("2026-08-15");
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onCancel).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();

    view.rerender(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} paymentMethods={{ card: "card-method" }} request={request} disabled onCancel={onCancel} onSuccess={vi.fn()} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(/recuperaci[oó]n/i);
    expect(screen.getByRole("button", { name: /confirmar venta pendiente/i })).toBeDisabled();

    view.rerender(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} paymentMethods={{ card: "card-method" }} request={request} onCancel={onCancel} onSuccess={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Eliminar" }));
    expect(type).toBeEnabled();
    expect(dueDate).toBeEnabled();
    expect(screen.getByRole("button", { name: "Cancelar" })).toBeEnabled();
  });

  it("closes on a recovered checkout lock before effects and blocks confirmation after effects", async () => {
    const onCancel = vi.fn();
    const request = vi.fn().mockResolvedValueOnce({ total: "10.00" });
    const view = render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} paymentMethods={{ card: "card-method" }} request={request} onCancel={onCancel} onSuccess={vi.fn()} />);
    await screen.findAllByText("10,00");
    view.rerender(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} paymentMethods={{ card: "card-method" }} request={request} disabled onCancel={onCancel} onSuccess={vi.fn()} />);
    expect(onCancel).toHaveBeenCalledOnce();
  });
});
