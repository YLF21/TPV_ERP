// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CustomerReceivablePaymentDialog, receivablePaymentAttemptKey } from "./CustomerReceivablePaymentDialog";

const receivable = { documentId: "doc-1", documentType: "FACTURA_VENTA", documentNumber: "FV-1", customerId: "customer-1", customerName: "Cliente Uno", issueDate: "2026-07-01", dueDate: "2026-07-31", total: "100.00", paidTotal: "25.00", pendingTotal: "75.00", status: "PARCIAL", overdue: false } as const;
const methods = [{ id: "cash", name: "EFECTIVO", active: true }, { id: "card", name: "TARJETA", active: true }, { id: "transfer", name: "TRANSFERENCIA", active: true }];

afterEach(() => { cleanup(); localStorage.clear(); });

describe("CustomerReceivablePaymentDialog", () => {
  it("defaults to the pending amount, blocks overpayment and requires a transfer reference", async () => {
    const request = vi.fn(async (path: string) => path === "/payment-methods" ? methods : receivable);
    render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="01" request={request as any} onCancel={vi.fn()} onPaid={vi.fn()} />);
    expect(screen.getByLabelText("Importe a cobrar")).toHaveValue("75.00");
    await waitFor(() => expect(screen.getByRole("button", { name: "Transferencia" })).toBeEnabled());
    fireEvent.change(screen.getByLabelText("Importe a cobrar"), { target: { value: "80" } });
    fireEvent.click(screen.getByRole("button", { name: "Transferencia" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("no puede superar");
    fireEvent.change(screen.getByLabelText("Importe a cobrar"), { target: { value: "20" } });
    fireEvent.click(screen.getByRole("button", { name: "Transferencia" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirmar transferencia" }));
    expect(screen.getByRole("alert")).toHaveTextContent("referencia");
  });

  it("registers a partial transfer with a stable payment id", async () => {
    const onPaid = vi.fn();
    const request = vi.fn(async (path: string) => path === "/payment-methods" ? methods : ({ ...receivable, paidTotal: "45.00", pendingTotal: "55.00" }));
    render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="01" request={request as any} onCancel={vi.fn()} onPaid={onPaid} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Transferencia" })).toBeEnabled());
    fireEvent.change(screen.getByLabelText("Importe a cobrar"), { target: { value: "20" } });
    fireEvent.click(screen.getByRole("button", { name: "Transferencia" }));
    fireEvent.change(screen.getByLabelText("Referencia"), { target: { value: "TR-123" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar transferencia" }));
    await waitFor(() => expect(onPaid).toHaveBeenCalled());
    const paymentCall = (request.mock.calls as any[]).find(([path]) => path === "/customer-receivables/doc-1/payments");
    expect(paymentCall?.[1].body.pagos[0]).toMatchObject({ metodoPagoId: "transfer", importe: "20.00", reference: "TR-123", requestId: expect.any(String) });
  });

  it("keeps the same card payment id locally through terminal approval and backend confirmation", async () => {
    let resolvePayment!: (value: unknown) => void;
    const request = vi.fn((path: string) => {
      if (path === "/payment-methods") return Promise.resolve(methods);
      if (path.endsWith("/card-charges")) return Promise.resolve({ status: "APPROVED", code: "00", message: "ok" });
      if (path.endsWith("/payments")) return new Promise((resolve) => { resolvePayment = resolve; });
      return Promise.reject(new Error(path));
    });
    render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="01" request={request as any} onCancel={vi.fn()} onPaid={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Tarjeta" })).toBeEnabled());
    fireEvent.change(screen.getByLabelText("Importe a cobrar"), { target: { value: "30" } });
    fireEvent.click(screen.getByRole("button", { name: "Tarjeta" }));
    await waitFor(() => expect(request.mock.calls.some(([path]) => path.endsWith("/payments"))).toBe(true));
    const stored = JSON.parse(localStorage.getItem(receivablePaymentAttemptKey("01", "doc-1")) ?? "null");
    const charge = (request.mock.calls as any[]).find(([path]) => path.endsWith("/card-charges"))?.[1].body;
    const payment = (request.mock.calls as any[]).find(([path]) => path.endsWith("/payments"))?.[1].body.pagos[0];
    expect(charge.paymentId).toBe(stored.paymentId); expect(payment.requestId).toBe(stored.paymentId); expect(payment.paymentTerminalOperationId).toBe(stored.paymentId);
    resolvePayment(receivable); await waitFor(() => expect(localStorage.getItem(receivablePaymentAttemptKey("01", "doc-1"))).toBeNull());
  });

  it("queries an uncertain card operation and confirms the backend payment with the retained id", async () => {
    const request = vi.fn(async (path: string) => {
      if (path === "/payment-methods") return methods;
      if (path.endsWith("/card-charges")) return { status: "TIMEOUT" };
      if (path.endsWith("/query")) return { status: "APPROVED" };
      if (path.endsWith("/payments")) return receivable;
      throw new Error(path);
    });
    render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="01" request={request as any} onCancel={vi.fn()} onPaid={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Tarjeta" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Tarjeta" }));
    const query = await screen.findByRole("button", { name: "Consultar estado de tarjeta" });
    const retained = JSON.parse(localStorage.getItem(receivablePaymentAttemptKey("01", "doc-1")) ?? "null");
    fireEvent.click(query);
    await waitFor(() => expect((request.mock.calls as any[]).some(([path]) => path === `/payment-terminal/operations/${retained.paymentId}/query`)).toBe(true));
    const payment = (request.mock.calls as any[]).find(([path]) => path.endsWith("/payments"))?.[1].body.pagos[0];
    expect(payment.requestId).toBe(retained.paymentId);
  });

  it("uses the cash calculator received amount and change for a partial collection", async () => {
    const onPaid = vi.fn();
    const request = vi.fn(async (path: string) => path === "/payment-methods" ? methods : receivable);
    render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="01" request={request as any} onCancel={vi.fn()} onPaid={onPaid} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Efectivo" })).toBeEnabled());
    fireEvent.change(screen.getByLabelText("Importe a cobrar"), { target: { value: "20" } });
    fireEvent.click(screen.getByRole("button", { name: "Efectivo" }));
    fireEvent.change(screen.getByLabelText("Dinero recibido"), { target: { value: "50" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar cobro" }));
    await waitFor(() => expect(onPaid).toHaveBeenCalled());
    const payment = (request.mock.calls as any[]).find(([path]) => path.endsWith("/payments"))?.[1].body.pagos[0];
    expect(payment).toMatchObject({ metodoPagoId: "cash", importe: "20.00", entregado: "50.00", cambio: "30.00" });
  });

  it("refuses an already paid document", async () => {
    render(<CustomerReceivablePaymentDialog receivable={{ ...receivable, pendingTotal: "0.00", status: "PAGADO" }} token="token" terminalCode="01" request={vi.fn().mockResolvedValue(methods)} onCancel={vi.fn()} onPaid={vi.fn()} />);
    expect(screen.getByText("Este documento ya esta pagado")).toBeVisible();
    expect(screen.getByRole("button", { name: "Efectivo" })).toBeDisabled();
  });
});
