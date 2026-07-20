// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { StrictMode } from "react";
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
    const query = await screen.findByRole("button", { name: "Consultar estado de tarjeta" }); await waitFor(() => expect(query).toBeEnabled());
    const retained = JSON.parse(localStorage.getItem(receivablePaymentAttemptKey("01", "doc-1")) ?? "null");
    fireEvent.click(query);
    await waitFor(() => expect((request.mock.calls as any[]).some(([path]) => path === `/customer-receivables/doc-1/card-charges/${retained.paymentId}/query`)).toBe(true));
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

  it("retains a stable transfer request id after a lost response and retries without duplication", async () => {
    let calls = 0;
    const request = vi.fn(async (path: string) => {
      if (path === "/payment-methods") return methods;
      if (path.endsWith("/payments") && calls++ === 0) throw new Error("lost");
      return receivable;
    });
    render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="01" request={request as any} onCancel={vi.fn()} onPaid={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Transferencia" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Transferencia" })); fireEvent.change(screen.getByLabelText("Referencia"), { target: { value: "TR-X" } }); fireEvent.click(screen.getByRole("button", { name: "Confirmar transferencia" }));
    expect(await screen.findByRole("button", { name: "Reintentar cobro" })).toBeVisible();
    const first = (request.mock.calls as any[]).find(([path]) => path.endsWith("/payments"))[1].body.pagos[0].requestId;
    fireEvent.click(screen.getByRole("button", { name: "Reintentar cobro" }));
    await waitFor(() => expect((request.mock.calls as any[]).filter(([path]) => path.endsWith("/payments"))).toHaveLength(2));
    expect((request.mock.calls as any[]).filter(([path]) => path.endsWith("/payments"))[1][1].body.pagos[0].requestId).toBe(first);
  });

  it("blocks closing uncertain card effects and allows explicit rotation after declined", async () => {
    const onCancel = vi.fn(); let status = "TIMEOUT";
    const request = vi.fn(async (path: string) => path === "/payment-methods" ? methods : path.endsWith("/card-charges") ? { status } : receivable);
    const firstView = render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="01" request={request as any} onCancel={onCancel} onPaid={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Tarjeta" })).toBeEnabled()); fireEvent.click(screen.getByRole("button", { name: "Tarjeta" }));
    await screen.findByRole("button", { name: "Consultar estado de tarjeta" }); fireEvent.keyDown(window, { key: "Escape" }); expect(onCancel).not.toHaveBeenCalled(); expect(screen.getByLabelText("Cerrar")).toBeDisabled();
    status = "DECLINED"; localStorage.clear(); firstView.unmount(); render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="02" request={request as any} onCancel={onCancel} onPaid={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Tarjeta" })).toBeEnabled()); fireEvent.click(screen.getByRole("button", { name: "Tarjeta" })); expect(await screen.findByRole("button", { name: "Descartar intento rechazado" })).toBeVisible();
  });

  it("keeps an uncertain ERROR locked to the same id and only allows discarding a final ERROR", async () => {
    const key = receivablePaymentAttemptKey("01", "doc-1");
    localStorage.setItem(key, JSON.stringify({ paymentId: "op-error", amount: "75.00", methodId: "card", status: "ERROR", finalOutcome: false }));
    const request = vi.fn().mockResolvedValue(methods);
    const first = render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="01" request={request as any} onCancel={vi.fn()} onPaid={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Efectivo" })).toBeDisabled());
    expect(screen.queryByRole("button", { name: "Descartar intento rechazado" })).not.toBeInTheDocument();
    expect(screen.getByLabelText("Cerrar")).toBeDisabled();

    first.unmount();
    localStorage.setItem(key, JSON.stringify({ paymentId: "op-error", amount: "75.00", methodId: "card", status: "ERROR", finalOutcome: true }));
    render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="01" request={request as any} onCancel={vi.fn()} onPaid={vi.fn()} />);
    expect(await screen.findByRole("button", { name: "Descartar intento rechazado" })).toBeVisible();
    expect(screen.getByLabelText("Cerrar")).toBeEnabled();
  });

  it("hydrates methods in StrictMode and traps focus, restoring it after safe close", async () => {
    const request = vi.fn().mockResolvedValue(methods); const onCancel = vi.fn();
    const host = document.createElement("button"); host.textContent = "Origen"; document.body.append(host); host.focus();
    const view = render(<StrictMode><CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="01" request={request as any} onCancel={onCancel} onPaid={vi.fn()} /></StrictMode>);
    await waitFor(() => expect(screen.getByRole("button", { name: "Efectivo" })).toBeEnabled());
    expect(screen.getByRole("dialog")).toContainElement(document.activeElement as HTMLElement);
    fireEvent.keyDown(window, { key: "Escape" }); expect(onCancel).toHaveBeenCalled(); view.unmount(); expect(document.activeElement).toBe(host); host.remove();
  });

  it("recovers a lost transfer after restart with immutable stored payload and blocks crossed methods", async () => {
    const key = `${receivablePaymentAttemptKey("01", "doc-1")}.standard`;
    localStorage.setItem(key, JSON.stringify({ requestId: "stable", kind: "transfer", item: { metodoPagoId: "transfer", importe: "20.00", reference: "TR-STORED", requestId: "stable" } }));
    const request = vi.fn(async (path: string) => path === "/payment-methods" ? methods : receivable);
    render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="01" request={request as any} onCancel={vi.fn()} onPaid={vi.fn()} />);
    expect(await screen.findByText(/Transferencia.*20.00.*TR-STORED/)).toBeVisible();
    expect(screen.getByLabelText("Importe a cobrar")).toBeDisabled(); expect(screen.getByRole("button", { name: "Efectivo" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Reintentar cobro" }));
    await waitFor(() => expect((request.mock.calls as any[]).some(([path]) => path.endsWith("/payments"))).toBe(true));
    expect((request.mock.calls as any[]).find(([path]) => path.endsWith("/payments"))[1].body.pagos[0]).toMatchObject({ metodoPagoId: "transfer", importe: "20.00", reference: "TR-STORED", requestId: "stable" });
  });

  it("one Escape closes only the nested transfer editor", async () => {
    const onCancel = vi.fn(); const request = vi.fn().mockResolvedValue(methods);
    render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="01" request={request as any} onCancel={onCancel} onPaid={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Transferencia" })).toBeEnabled()); fireEvent.click(screen.getByRole("button", { name: "Transferencia" }));
    fireEvent.keyDown(window, { key: "Escape" }); expect(onCancel).not.toHaveBeenCalled(); expect(screen.queryByLabelText("Referencia")).not.toBeInTheDocument();
  });

  it("does not let an older payment-method request overwrite the current token", async () => {
    let resolveOld!: (value: unknown) => void; const request = vi.fn((path: string, options?: { token?: string }) => options?.token === "old" ? new Promise((resolve) => { resolveOld = resolve; }) : Promise.resolve(methods));
    const view = render(<CustomerReceivablePaymentDialog receivable={receivable} token="old" terminalCode="01" request={request as any} onCancel={vi.fn()} onPaid={vi.fn()} />);
    view.rerender(<CustomerReceivablePaymentDialog receivable={receivable} token="new" terminalCode="01" request={request as any} onCancel={vi.fn()} onPaid={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Efectivo" })).toBeEnabled()); resolveOld([]); await Promise.resolve(); expect(screen.getByRole("button", { name: "Efectivo" })).toBeEnabled();
  });

  it("blocks amount, cash and transfer while a TIMEOUT or APPROVED card effect exists", async () => {
    for (const status of ["TIMEOUT", "APPROVED"]) {
      localStorage.setItem(receivablePaymentAttemptKey(status, "doc-1"), JSON.stringify({ paymentId: `op-${status}`, amount: "75.00", methodId: "card", status }));
      const request = vi.fn().mockResolvedValue(methods); const view = render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode={status} request={request as any} onCancel={vi.fn()} onPaid={vi.fn()} />);
      await waitFor(() => expect(screen.getByRole("button", { name: "Efectivo" })).toBeDisabled());
      expect(screen.getByLabelText("Importe a cobrar")).toBeDisabled(); expect(screen.getByRole("button", { name: "Transferencia" })).toBeDisabled(); expect(screen.getByRole("button", { name: "Tarjeta" })).toBeDisabled(); view.unmount();
    }
  });

  it("performs all cleanup before onPaid unmounts the dialog", async () => {
    const key = `${receivablePaymentAttemptKey("01", "doc-1")}.standard`; localStorage.setItem(key, JSON.stringify({ requestId: "stable", kind: "transfer", item: { metodoPagoId: "transfer", importe: "20.00", reference: "TR", requestId: "stable" } }));
    const request = vi.fn(async (path: string) => path === "/payment-methods" ? methods : receivable); let view!: ReturnType<typeof render>;
    const onPaid = vi.fn(() => { expect(localStorage.getItem(key)).toBeNull(); view.unmount(); });
    view = render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="01" request={request as any} onCancel={vi.fn()} onPaid={onPaid} />);
    fireEvent.click(await screen.findByRole("button", { name: "Reintentar cobro" })); await waitFor(() => expect(onPaid).toHaveBeenCalledOnce());
  });

  it("keeps a mismatched stored card attempt recoverable without leaving the dialog busy", async () => {
    localStorage.setItem(receivablePaymentAttemptKey("01", "doc-1"), JSON.stringify({ paymentId: "old-op", amount: "20.00", methodId: "old-card", status: "APPROVED" }));
    const request = vi.fn().mockResolvedValue(methods);
    render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="01" request={request as any} onCancel={vi.fn()} onPaid={vi.fn()} />);
    const retry = await screen.findByRole("button", { name: "Reintentar confirmación de tarjeta" }); fireEvent.click(retry);
    expect(await screen.findByRole("alert")).toHaveTextContent("otro importe");
    expect(screen.getByRole("button", { name: "Consultar estado de tarjeta" })).toBeEnabled(); expect(retry).toBeEnabled();
    expect(JSON.parse(localStorage.getItem(receivablePaymentAttemptKey("01", "doc-1")) ?? "null")).toMatchObject({ paymentId: "old-op", amount: "20.00", methodId: "old-card" });
  });

  it("refuses an already paid document", async () => {
    render(<CustomerReceivablePaymentDialog receivable={{ ...receivable, pendingTotal: "0.00", status: "PAGADO" }} token="token" terminalCode="01" request={vi.fn().mockResolvedValue(methods)} onCancel={vi.fn()} onPaid={vi.fn()} />);
    expect(screen.getByText("Este documento ya está pagado")).toBeVisible();
    expect(screen.getByRole("button", { name: "Efectivo" })).toBeDisabled();
  });

  it("prints the authoritative receipt returned by its own mutation without a follow-up GET", async () => {
    const printReceipt = vi.fn().mockResolvedValue({ status: "PRINTED" });
    const receipt = { paymentId: "stable", documentId: "doc-1", documentNumber: "FV-1", collectedAt: "2026-07-20T09:00:00Z", method: "TRANSFERENCIA", amount: "20.00", remaining: "55.00" };
    const key = `${receivablePaymentAttemptKey("01", "doc-1")}.standard`;
    localStorage.setItem(key, JSON.stringify({ requestId: "stable", kind: "transfer", item: { metodoPagoId: "transfer", importe: "20.00", reference: "TR", requestId: "stable" } }));
    const request = vi.fn(async (path: string) => path === "/payment-methods" ? methods : { receivable, paymentReceipt: receipt });
    render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="01" terminalContext={{ storeName: "Tienda", terminalCode: "01" }} printReceipt={printReceipt} request={request as any} onCancel={vi.fn()} onPaid={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: "Reintentar cobro" }));
    await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
    expect(printReceipt).toHaveBeenCalledWith(receipt, { storeName: "Tienda", terminalCode: "01" }, undefined, "es");
  });

  it("clears the stable payment attempt and reports paid when printing fails", async () => {
    const onPaid = vi.fn();
    localStorage.setItem("tpverp.receivable.01.doc-1.card-attempt.standard", JSON.stringify({ requestId: "stable", kind: "cash", item: { metodoPagoId: "cash", importe: "75.00", principal: true, requestId: "stable" } }));
    const request = vi.fn(async (path: string) => path === "/payment-methods" ? methods : { receivable, paymentReceipt: { paymentId: "stable" } });
    render(<CustomerReceivablePaymentDialog receivable={receivable} token="token" terminalCode="01" terminalContext={{ storeName: "Tienda", terminalCode: "01" }} printReceipt={vi.fn().mockResolvedValue({ status: "FAILED", technicalMessage: "printer offline" })} request={request as any} onCancel={vi.fn()} onPaid={onPaid} />);
    fireEvent.click(await screen.findByRole("button", { name: "Reintentar cobro" }));
    await waitFor(() => expect(onPaid).toHaveBeenCalledWith(receivable, expect.any(Function)));
    expect(localStorage.getItem("tpverp.receivable.01.doc-1.card-attempt.standard")).toBeNull();
  });
});
