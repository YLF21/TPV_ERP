// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { defaultHardwareConfig, type HardwareBridge } from "../hardware/hardware";
import { printCustomerReceivablePaymentReceipt } from "../sale/ticketPrinting";
import {
  CustomerReceivablePaymentDialog,
  receivablePaymentAttemptKey,
} from "./CustomerReceivablePaymentDialog";

const receivable = {
  documentId: "doc-1",
  documentType: "FACTURA_VENTA",
  documentNumber: "FV-1",
  customerId: "customer-1",
  customerName: "Cliente Uno",
  issueDate: "2026-07-01",
  dueDate: "2026-07-31",
  total: "100.00",
  paidTotal: "25.00",
  pendingTotal: "75.00",
  status: "PARCIAL",
  overdue: false,
} as const;

const methods = [
  { id: "cash", name: "EFECTIVO", active: true },
  { id: "card", name: "TARJETA", active: true },
  { id: "transfer", name: "TRANSFERENCIA", active: true },
];

const terminalConfiguration = {
  rules: { cardManualEnabled: true, integratedCardEnabled: true },
  configuration: { provider: "PAYTEF", enabled: true },
};

const transferReceipt = {
  kind: "PAYMENT_RECEIPT",
  paymentId: "payment-1",
  documentNumber: "FV-1",
  collectedAt: "2026-08-11T10:00:00Z",
  method: "TRANSFERENCIA",
  amount: "20.00",
  remaining: "55.00",
  transferDate: "2026-08-10",
} as const;

const paymentResult = {
  receivable: { ...receivable, paidTotal: "45.00", pendingTotal: "55.00" },
  paymentReceipt: transferReceipt,
};

function configuredRequest(paymentResponse: unknown = paymentResult) {
  return vi.fn(async (path: string) => {
    if (path === "/payment-methods") return methods;
    if (path === "/terminal-configuration/payment") return terminalConfiguration;
    if (path.endsWith("/payments")) return paymentResponse;
    throw new Error(path);
  });
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe("CustomerReceivablePaymentDialog", () => {
  it("reuses the real checkout panel and exposes only debt payment methods", async () => {
    render(<CustomerReceivablePaymentDialog
      receivable={receivable}
      token="token"
      terminalCode="01"
      request={configuredRequest() as any}
      onCancel={vi.fn()}
      onPaid={vi.fn()}
    />);

    expect(screen.getByRole("dialog", { name: "COBRO" })).toBeVisible();
    await waitFor(() => expect(screen.getByRole("button", { name: "Efectivo" })).toBeEnabled());
    expect(screen.getByRole("button", { name: "Tarjeta" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Transferencia" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "ACEPTAR" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "Vale" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Pendiente" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Descuento" })).not.toBeInTheDocument();
  });

  it("accepts a manual card payment when no integrated terminal is configured", async () => {
    const onPayment = vi.fn();
    const onPaid = vi.fn();
    const request = vi.fn(async (path: string) => {
      if (path === "/payment-methods") return methods;
      if (path === "/terminal-configuration/payment") return {
        rules: { cardManualEnabled: true, integratedCardEnabled: false },
        configuration: { provider: "", enabled: false },
      };
      if (path.endsWith("/payments")) return paymentResult;
      throw new Error(path);
    });
    render(<CustomerReceivablePaymentDialog
      receivable={receivable}
      token="token"
      terminalCode="01"
      request={request as any}
      onCancel={vi.fn()}
      onPayment={onPayment}
      onPaid={onPaid}
    />);

    const cardButton = await screen.findByRole("button", { name: "Tarjeta" });
    await waitFor(() => expect(cardButton).toBeEnabled());
    fireEvent.click(cardButton);
    await waitFor(() => expect(cardButton).toHaveClass("selected"));
    fireEvent.change(screen.getByLabelText("IMPORTE / RECIBIDO"), { target: { value: "20" } });
    fireEvent.click(screen.getByRole("button", { name: "ACEPTAR" }));

    await waitFor(() => expect(onPayment).toHaveBeenCalledWith(paymentResult.receivable, undefined));
    expect(onPaid).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog", { name: "COBRO" })).toBeVisible();
    expect((request.mock.calls as any[]).some(([path]) => path.endsWith("/card-charges"))).toBe(false);
    const payment = (request.mock.calls as any[]).find(([path]) => path.endsWith("/payments"))?.[1].body.pagos[0];
    expect(payment).toMatchObject({
      metodoPagoId: "card",
      importe: "20.00",
      cardMode: "MANUAL",
      requestId: expect.any(String),
    });
  });

  it("registers a partial transfer with its optional transfer date and stable request id", async () => {
    const onPayment = vi.fn();
    const onPaid = vi.fn();
    const request = configuredRequest();
    render(<CustomerReceivablePaymentDialog
      receivable={receivable}
      token="token"
      terminalCode="01"
      request={request as any}
      onCancel={vi.fn()}
      onPayment={onPayment}
      onPaid={onPaid}
    />);

    await waitFor(() => expect(screen.getByRole("button", { name: "Transferencia" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Transferencia" }));
    fireEvent.change(screen.getByLabelText("IMPORTE / RECIBIDO"), { target: { value: "20" } });
    fireEvent.change(screen.getByLabelText("Nº DOCUMENTO"), { target: { value: "TR-123" } });
    const transferDate = await screen.findByLabelText("FECHA DE TRANSFERENCIA");
    fireEvent.change(transferDate, { target: { value: "2026-08-10" } });
    fireEvent.keyDown(transferDate, { key: "Enter" });

    await waitFor(() => expect(onPayment).toHaveBeenCalledWith(paymentResult.receivable, undefined));
    expect(onPaid).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog", { name: "COBRO" })).toBeVisible();
    const paymentCall = (request.mock.calls as any[]).find(([path]) => path.endsWith("/payments"));
    expect(paymentCall?.[1].body.pagos[0]).toMatchObject({
      metodoPagoId: "transfer",
      importe: "20.00",
      reference: "TR-123",
      transferDate: "2026-08-10",
      requestId: expect.any(String),
    });
  });

  it("keeps a pending-debt checkout open after a partial payment and closes only when fully paid", async () => {
    const onPayment = vi.fn();
    const onPaid = vi.fn();
    let paymentCalls = 0;
    const request = vi.fn(async (path: string) => {
      if (path === "/payment-methods") return methods;
      if (path === "/terminal-configuration/payment") return {
        rules: { cardManualEnabled: true, integratedCardEnabled: false },
        configuration: { provider: "", enabled: false },
      };
      if (path.endsWith("/payments")) {
        paymentCalls += 1;
        return paymentCalls === 1
          ? paymentResult
          : {
              receivable: {
                ...receivable,
                paidTotal: "100.00",
                pendingTotal: "0.00",
                status: "PAGADO",
              },
              paymentReceipt: {
                ...transferReceipt,
                paymentId: "payment-2",
                method: "TARJETA",
                amount: "55.00",
                remaining: "0.00",
              },
            };
      }
      throw new Error(path);
    });
    render(<CustomerReceivablePaymentDialog
      receivable={receivable}
      token="token"
      terminalCode="01"
      request={request as any}
      onCancel={vi.fn()}
      onPayment={onPayment}
      onPaid={onPaid}
    />);

    const transferButton = await screen.findByRole("button", { name: "Transferencia" });
    await waitFor(() => expect(transferButton).toBeEnabled());
    fireEvent.click(transferButton);
    await waitFor(() => expect(transferButton).toHaveClass("selected"));
    fireEvent.change(screen.getByLabelText("IMPORTE / RECIBIDO"), { target: { value: "20" } });
    fireEvent.click(screen.getByRole("button", { name: "ACEPTAR" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/customer-receivables/doc-1/payments",
      {
        token: "token",
        body: { pagos: [expect.objectContaining({ metodoPagoId: "transfer", importe: "20.00" })] },
      },
    ));
    await waitFor(() => expect(onPayment).toHaveBeenCalledWith(paymentResult.receivable, undefined));
    expect(onPaid).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByLabelText("IMPORTE / RECIBIDO")).toHaveValue("55,00"));
    const cardButton = await screen.findByRole("button", { name: "Tarjeta" });
    fireEvent.click(cardButton);
    await waitFor(() => expect(cardButton).toHaveClass("selected"));
    fireEvent.click(screen.getByRole("button", { name: "ACEPTAR" }));

    await waitFor(() => expect(onPaid).toHaveBeenCalledWith(
      expect.objectContaining({ status: "PAGADO", pendingTotal: "0.00" }),
      undefined,
    ));
    expect(onPayment).toHaveBeenCalledTimes(1);
    expect(paymentCalls).toBe(2);
  });

  it("limits the optional transfer date input to today", async () => {
    render(<CustomerReceivablePaymentDialog
      receivable={receivable}
      token="token"
      terminalCode="01"
      request={configuredRequest() as any}
      onCancel={vi.fn()}
      onPaid={vi.fn()}
    />);

    await waitFor(() => expect(screen.getByRole("button", { name: "Transferencia" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Transferencia" }));
    const input = await screen.findByLabelText("FECHA DE TRANSFERENCIA");
    expect(input).toHaveAttribute("type", "date");
    expect(input).toHaveAttribute("max", expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/));
  });

  it("records cash through checkout and keeps the completion acknowledgement", async () => {
    const onPayment = vi.fn();
    const onPaid = vi.fn();
    const cashReceipt = {
      kind: "PAYMENT_RECEIPT",
      paymentId: "cash-payment",
      documentNumber: "FV-1",
      collectedAt: "2026-08-11T10:00:00Z",
      method: "EFECTIVO",
      amount: "20.00",
      remaining: "55.00",
    } as const;
    const request = configuredRequest({
      receivable: paymentResult.receivable,
      paymentReceipt: cashReceipt,
    });
    render(<CustomerReceivablePaymentDialog
      receivable={receivable}
      token="token"
      terminalCode="01"
      request={request as any}
      onCancel={vi.fn()}
      onPayment={onPayment}
      onPaid={onPaid}
    />);

    await waitFor(() => expect(screen.getByRole("button", { name: "Efectivo" })).toBeEnabled());
    const amount = screen.getByLabelText("IMPORTE / RECIBIDO");
    fireEvent.change(amount, { target: { value: "20" } });
    fireEvent.keyDown(amount, { key: "Enter" });

    expect(await screen.findByRole("region", { name: "Pago completado" })).toBeVisible();
    expect(onPaid).not.toHaveBeenCalled();
    const payment = (request.mock.calls as any[]).find(([path]) => path.endsWith("/payments"))?.[1].body.pagos[0];
    expect(payment).toMatchObject({
      metodoPagoId: "cash",
      importe: "20.00",
      entregado: "20.00",
      cambio: "0.00",
    });
    fireEvent.keyDown(window, { key: "Enter" });
    await waitFor(() => expect(onPayment)
      .toHaveBeenCalledWith(paymentResult.receivable, undefined));
    expect(onPaid).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog", { name: "COBRO" })).toBeVisible();
  });

  it("keeps the same card operation id through approval and document payment", async () => {
    let resolvePayment!: (value: unknown) => void;
    const request = vi.fn((path: string) => {
      if (path === "/payment-methods") return Promise.resolve(methods);
      if (path === "/terminal-configuration/payment") return Promise.resolve(terminalConfiguration);
      if (path.endsWith("/card-charges")) return Promise.resolve({ status: "APPROVED", finalOutcome: true });
      if (path.endsWith("/payments")) return new Promise((resolve) => { resolvePayment = resolve; });
      return Promise.reject(new Error(path));
    });
    render(<CustomerReceivablePaymentDialog
      receivable={receivable}
      token="token"
      terminalCode="01"
      request={request as any}
      onCancel={vi.fn()}
      onPaid={vi.fn()}
    />);

    const cardButton = await screen.findByRole("button", { name: "Tarjeta" });
    await waitFor(() => expect(cardButton).toBeEnabled());
    fireEvent.click(cardButton);
    await waitFor(() => expect(cardButton).toHaveClass("selected"));
    const amount = screen.getByLabelText("IMPORTE / RECIBIDO");
    fireEvent.change(amount, { target: { value: "30" } });
    fireEvent.keyDown(amount, { key: "Enter" });

    await waitFor(() => expect(request.mock.calls.some(([path]) => path.endsWith("/payments"))).toBe(true));
    const stored = JSON.parse(localStorage.getItem(receivablePaymentAttemptKey("01", "doc-1")) ?? "null");
    const charge = (request.mock.calls as any[]).find(([path]) => path.endsWith("/card-charges"))?.[1].body;
    const payment = (request.mock.calls as any[]).find(([path]) => path.endsWith("/payments"))?.[1].body.pagos[0];
    expect(charge.paymentId).toBe(stored.paymentId);
    expect(payment.requestId).toBe(stored.paymentId);
    expect(payment.paymentTerminalOperationId).toBe(stored.paymentId);

    resolvePayment(paymentResult);
    await waitFor(() => expect(localStorage.getItem(receivablePaymentAttemptKey("01", "doc-1"))).toBeNull());
  });

  it("recovers an uncertain card charge with the retained operation id", async () => {
    const onPayment = vi.fn();
    const onPaid = vi.fn();
    const request = vi.fn(async (path: string) => {
      if (path === "/payment-methods") return methods;
      if (path === "/terminal-configuration/payment") return terminalConfiguration;
      if (path.endsWith("/card-charges")) return { status: "TIMEOUT", finalOutcome: false };
      if (path.endsWith("/query")) return { status: "APPROVED", finalOutcome: true };
      if (path.endsWith("/payments")) return paymentResult;
      throw new Error(path);
    });
    render(<CustomerReceivablePaymentDialog
      receivable={receivable}
      token="token"
      terminalCode="01"
      request={request as any}
      onCancel={vi.fn()}
      onPayment={onPayment}
      onPaid={onPaid}
    />);

    const cardButton = await screen.findByRole("button", { name: "Tarjeta" });
    await waitFor(() => expect(cardButton).toBeEnabled());
    fireEvent.click(cardButton);
    await waitFor(() => expect(cardButton).toHaveClass("selected"));
    fireEvent.keyDown(screen.getByLabelText("IMPORTE / RECIBIDO"), { key: "Enter" });
    const retained = await waitFor(() => {
      const stored = localStorage.getItem(receivablePaymentAttemptKey("01", "doc-1"));
      expect(stored).not.toBeNull();
      return JSON.parse(stored!);
    });
    fireEvent.click(await screen.findByRole("button", { name: "Consultar estado" }));

    await waitFor(() => expect(onPayment).toHaveBeenCalled());
    expect(onPaid).not.toHaveBeenCalled();
    expect((request.mock.calls as any[]).some(([path]) =>
      path === `/customer-receivables/doc-1/card-charges/${retained.paymentId}/query`)).toBe(true);
    const payment = (request.mock.calls as any[]).find(([path]) => path.endsWith("/payments"))?.[1].body.pagos[0];
    expect(payment.requestId).toBe(retained.paymentId);
  });

  it("retries an uncertain transfer without changing its request id or transfer date", async () => {
    let paymentCalls = 0;
    const request = vi.fn(async (path: string) => {
      if (path === "/payment-methods") return methods;
      if (path === "/terminal-configuration/payment") return terminalConfiguration;
      if (path.endsWith("/payments") && paymentCalls++ === 0) throw new Error("lost");
      if (path.endsWith("/payments")) return paymentResult;
      throw new Error(path);
    });
    render(<CustomerReceivablePaymentDialog
      receivable={receivable}
      token="token"
      terminalCode="01"
      request={request as any}
      onCancel={vi.fn()}
      onPaid={vi.fn()}
    />);

    await waitFor(() => expect(screen.getByRole("button", { name: "Transferencia" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Transferencia" }));
    fireEvent.change(screen.getByLabelText("IMPORTE / RECIBIDO"), { target: { value: "20" } });
    const date = await screen.findByLabelText("FECHA DE TRANSFERENCIA");
    fireEvent.change(date, { target: { value: "2026-08-10" } });
    fireEvent.keyDown(date, { key: "Enter" });

    const first = await waitFor(() => {
      const call = (request.mock.calls as any[]).find(([path]) => path.endsWith("/payments"));
      expect(call).toBeDefined();
      return call[1].body.pagos[0];
    });
    fireEvent.click(await screen.findByRole("button", { name: "Consultar estado" }));
    await waitFor(() => expect((request.mock.calls as any[]).filter(([path]) => path.endsWith("/payments"))).toHaveLength(2));
    const second = (request.mock.calls as any[]).filter(([path]) => path.endsWith("/payments"))[1][1].body.pagos[0];
    expect(second.requestId).toBe(first.requestId);
    expect(second.transferDate).toBe("2026-08-10");
  });

  it("refuses an already paid document", async () => {
    render(<CustomerReceivablePaymentDialog
      receivable={{ ...receivable, pendingTotal: "0.00", status: "PAGADO" }}
      token="token"
      terminalCode="01"
      request={configuredRequest() as any}
      onCancel={vi.fn()}
      onPaid={vi.fn()}
    />);

    expect(screen.getByRole("alert")).toHaveTextContent("Este documento ya está pagado");
    await waitFor(() => expect(screen.getByRole("button", { name: "Efectivo" })).toBeDisabled());
    expect(screen.getByRole("button", { name: "Transferencia" })).toBeDisabled();
  });

  it("prints the authoritative transfer receipt including its transfer date", async () => {
    const printReceipt = vi.fn().mockResolvedValue({ status: "PRINTED" });
    const request = configuredRequest();
    render(<CustomerReceivablePaymentDialog
      receivable={receivable}
      token="token"
      terminalCode="01"
      terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      printReceipt={printReceipt}
      request={request as any}
      onCancel={vi.fn()}
      onPaid={vi.fn()}
    />);

    await waitFor(() => expect(screen.getByRole("button", { name: "Transferencia" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Transferencia" }));
    fireEvent.change(screen.getByLabelText("IMPORTE / RECIBIDO"), { target: { value: "20" } });
    const date = await screen.findByLabelText("FECHA DE TRANSFERENCIA");
    fireEvent.change(date, { target: { value: "2026-08-10" } });
    fireEvent.keyDown(date, { key: "Enter" });

    await waitFor(() => expect(printReceipt).toHaveBeenCalledWith(
      transferReceipt,
      { storeName: "Tienda", terminalCode: "01" },
      undefined,
      "es",
    ));
  });

  it("keeps a confirmed cash payment and change while regenerating a failed receipt without collecting again", async () => {
    const cashReceipt = { ...transferReceipt, paymentId: "cash-request", method: "EFECTIVO", amount: "75.00", remaining: "0.00", renderedPdf: null, ticketRenderedImage: null };
    const refreshedReceipt = { ...cashReceipt, renderedPdf: { contentType: "application/pdf" as const, base64: "fresh-pdf" }, ticketRenderedImage: { contentType: "image/png" as const, base64: "fresh-png" } };
    const paid = { ...receivable, paidTotal: "100.00", pendingTotal: "0.00", status: "PAGADO" };
    let resolveReceipt!: (value: unknown) => void;
    let receiptRequests = 0;
    const request = vi.fn(async (path: string) => {
      if (path === "/payment-methods") return methods;
      if (path === "/terminal-configuration/payment") return terminalConfiguration;
      if (path.endsWith("/payments")) return { receivable: paid, paymentReceipt: cashReceipt };
      if (path === "/customer-receivables/doc-1/payments/cash-request/receipt") {
        receiptRequests += 1;
        if (receiptRequests === 1) throw new Error("Jasper unavailable");
        return new Promise((resolve) => { resolveReceipt = resolve; });
      }
      throw new Error(path);
    });
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue({ ...defaultHardwareConfig, ticketPrinterDriver: "WINDOWS_DRIVER" }),
      printTicket,
    } as unknown as HardwareBridge;
    const printReceipt = vi.fn<typeof printCustomerReceivablePaymentReceipt>((snapshot, terminal, _bridge, locale) =>
      printCustomerReceivablePaymentReceipt(snapshot, terminal, hardware, locale));
    const onPaid = vi.fn();
    render(<CustomerReceivablePaymentDialog
      receivable={receivable} token="token" terminalCode="01"
      terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      request={request as any} printReceipt={printReceipt} onCancel={vi.fn()} onPaid={onPaid}
    />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Efectivo" })).toBeEnabled());
    fireEvent.change(screen.getByLabelText("IMPORTE / RECIBIDO"), { target: { value: "100" } });
    fireEvent.click(screen.getByRole("button", { name: "ACEPTAR" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Cobro realizado; impresión pendiente. No repitas el cobro.");
    expect(printTicket).not.toHaveBeenCalled();
    expect(screen.getByText("75,00")).toBeVisible();
    expect(screen.getByText("100,00")).toBeVisible();
    expect(screen.getByText("25,00")).toBeVisible();
    expect(localStorage.getItem(`${receivablePaymentAttemptKey("01", "doc-1")}.standard`)).toBeNull();
    fireEvent.keyDown(window, { key: "Enter" });
    expect(onPaid).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Reintentar impresión" }));
    expect(await screen.findByRole("button", { name: "Reintentar impresión" })).toBeVisible();
    expect(printReceipt).toHaveBeenCalledTimes(1);

    const retry = screen.getByRole("button", { name: "Reintentar impresión" });
    fireEvent.click(retry);
    fireEvent.click(retry);
    await waitFor(() => expect(receiptRequests).toBe(2));
    await act(async () => { resolveReceipt(refreshedReceipt); });
    await waitFor(() => expect(printReceipt).toHaveBeenLastCalledWith(refreshedReceipt, expect.anything(), undefined, "es"));
    expect(printTicket).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ requireRenderedDocument: true, renderedPdf: refreshedReceipt.renderedPdf }), expect.anything());
    expect(request).toHaveBeenCalledWith("/customer-receivables/doc-1/payments/cash-request/receipt", { token: "token" });
    expect(request.mock.calls.filter(([path]) => path.endsWith("/payments"))).toHaveLength(1);
    expect(screen.getByText("25,00")).toBeVisible();
    fireEvent.keyDown(window, { key: "Enter" });
    await waitFor(() => expect(onPaid).toHaveBeenCalledWith(paid, undefined));
  });

  it.each(["Transferencia", "Tarjeta"])("retains a %s receipt retry that reloads its original payment after the dialog closes", async (method) => {
    const initialReceipt = { ...transferReceipt, renderedPdf: null, ticketRenderedImage: null };
    const refreshedReceipt = { ...transferReceipt, renderedPdf: { contentType: "application/pdf", base64: "fresh-pdf" } };
    const request = vi.fn(async (path: string) => {
      if (path === "/payment-methods") return methods;
      if (path === "/terminal-configuration/payment") return { rules: { cardManualEnabled: true } };
      if (path.endsWith("/payments")) return { ...paymentResult, paymentReceipt: initialReceipt };
      if (path === "/customer-receivables/doc-1/payments/payment-1/receipt") return refreshedReceipt;
      throw new Error(path);
    });
    const printReceipt = vi.fn().mockResolvedValueOnce({ status: "FAILED" })
      .mockResolvedValueOnce({ status: "FAILED" }).mockResolvedValue({ status: "PRINTED" });
    const onPayment = vi.fn();
    const view = render(<CustomerReceivablePaymentDialog
      receivable={receivable} token="token" terminalCode="01"
      terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      request={request as any} printReceipt={printReceipt} onCancel={vi.fn()} onPaid={vi.fn()} onPayment={onPayment}
    />);
    await waitFor(() => expect(screen.getByRole("button", { name: method })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: method }));
    fireEvent.change(screen.getByLabelText("IMPORTE / RECIBIDO"), { target: { value: "20" } });
    fireEvent.click(screen.getByRole("button", { name: "ACEPTAR" }));
    await waitFor(() => expect(onPayment).toHaveBeenCalledWith(paymentResult.receivable, expect.any(Function)));
    expect(screen.getByRole("alert")).toHaveTextContent("Cobro realizado; impresión pendiente. No repitas el cobro.");
    expect(printReceipt).toHaveBeenCalledWith(initialReceipt, expect.anything(), undefined, "es");
    expect(request.mock.calls.filter(([path]) => path.endsWith("/receipt"))).toHaveLength(0);
    const retry = onPayment.mock.calls[0][1] as () => Promise<unknown>;
    view.unmount();

    const first = retry();
    expect(retry()).toBe(first);
    expect(await first).toEqual({ status: "FAILED" });
    expect(await retry()).toEqual({ status: "PRINTED" });
    expect(request.mock.calls.filter(([path]) => path.endsWith("/receipt"))).toHaveLength(2);
    expect(printReceipt).toHaveBeenLastCalledWith(refreshedReceipt, expect.anything(), undefined, "es");
    expect(request.mock.calls.filter(([path]) => path.endsWith("/payments"))).toHaveLength(1);
    expect(request.mock.calls.some(([path]) => path.endsWith("/card-charges"))).toBe(false);
  });
});
