// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CustomerReceivablesScreen, effectiveReceivableStatus } from "./CustomerReceivablesScreen";
import type { UserSession } from "../types";
import { retryPrintSucceeded } from "../sale/printRetry";

const session: UserSession = { username: "cashier", displayName: "Caja", accessToken: "token", permissions: ["CUSTOMER_RECEIVABLES_READ", "CUSTOMER_RECEIVABLES_PAY"] };
const row = { documentId: "doc-1", documentType: "FACTURA_VENTA", documentNumber: "FV-1", customerId: "customer-1", customerName: "Cliente Uno", issueDate: "2026-07-01", dueDate: "2026-07-31", total: "100.00", paidTotal: "25.00", pendingTotal: "75.00", status: "PARCIAL", overdue: false } as const;

afterEach(cleanup);

it("keeps print retry after two failures and clears only after success", async () => {
  const retry = vi.fn().mockResolvedValueOnce({ status: "FAILED" })
    .mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce({ status: "PRINTED" });
  expect(await retryPrintSucceeded(retry)).toBe(false);
  expect(await retryPrintSucceeded(retry)).toBe(false);
  expect(await retryPrintSucceeded(retry)).toBe(true);
});

describe("CustomerReceivablesScreen", () => {
  it("normalizes legacy zero-balance rows as paid", async () => {
    const paid = { ...row, paidTotal: "100.00", pendingTotal: "0.00", status: "PARCIAL" as const };
    expect(effectiveReceivableStatus(paid)).toBe("PAGADO");
    const request = vi.fn().mockResolvedValue([paid]);
    render(<CustomerReceivablesScreen locale="es" session={session} terminalContext={{ storeName: "Tienda", terminalCode: "01" }} request={request as any} onBack={vi.fn()} onLocaleChange={vi.fn()} />);

    expect(await screen.findByRole("cell", { name: "Pagado" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Cobrar FV-1" })).toBeDisabled();
  });

  it("loads the tenant-scoped endpoint with the customer prefilter and renders the financial columns", async () => {
    const request = vi.fn().mockResolvedValue([row]);
    render(<CustomerReceivablesScreen locale="es" session={session} terminalContext={{ storeName: "Tienda", terminalCode: "01" }} initialCustomerId="customer-1" request={request as any} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    expect(await screen.findByText("FV-1")).toBeVisible();
    expect(request).toHaveBeenCalledWith("/customer-receivables?customerId=customer-1", { token: "token" });
    for (const heading of ["Documento", "Cliente", "Emisión", "Vencimiento", "Total", "Pagado", "Pendiente", "Estado"]) expect(screen.getByRole("columnheader", { name: heading })).toBeVisible();
  });

  it("sends text, status, type, overdue and due-date filters without any company id", async () => {
    const request = vi.fn().mockResolvedValue([]);
    render(<CustomerReceivablesScreen locale="es" session={session} terminalContext={{ storeName: "Tienda", terminalCode: "01" }} request={request as any} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    await waitFor(() => expect(request).toHaveBeenCalledTimes(1));
    expect(screen.getByLabelText("Estado")).toHaveTextContent("Pagado");
    fireEvent.change(screen.getByLabelText("Buscar deuda"), { target: { value: "Ana" } });
    fireEvent.change(screen.getByLabelText("Estado"), { target: { value: "PAGADO" } });
    fireEvent.change(screen.getByLabelText("Tipo de documento"), { target: { value: "FACTURA_VENTA" } });
    fireEvent.click(screen.getByLabelText("Solo vencidos"));
    fireEvent.change(screen.getByLabelText("Vencimiento desde"), { target: { value: "2026-07-01" } });
    fireEvent.change(screen.getByLabelText("Vencimiento hasta"), { target: { value: "2026-07-31" } });
    await waitFor(() => expect(String(request.mock.calls.at(-1)?.[0])).toContain("search=Ana"));
    const path = String(request.mock.calls.at(-1)?.[0]);
    expect(path).toContain("status=PAGADO"); expect(path).toContain("documentType=FACTURA_VENTA"); expect(path).toContain("overdue=true"); expect(path).toContain("dueFrom=2026-07-01"); expect(path).toContain("dueTo=2026-07-31"); expect(path).not.toContain("companyId");
  });

  it("filters payment history and consults and reprints a persisted receipt without collecting again", async () => {
    const history = {
      paymentId: "pay-1", requestId: "request-1", documentId: "doc-1", documentType: "FACTURA_VENTA",
      documentNumber: "FV-1", customerId: "customer-1", customerName: "Cliente Uno", issueDate: "2026-07-01",
      collectedAt: "2026-07-20T09:30:00Z", paymentMethodId: "transfer", paymentMethodName: "TRANSFERENCIA",
      amount: "25.00", reference: "TR-1"
    } as const;
    const receipt = { kind: "PAYMENT_RECEIPT", paymentId: "pay-1", documentNumber: "FV-1", collectedAt: "2026-07-20T09:30:00Z", method: "TRANSFERENCIA", amount: "25.00", remaining: "75.00" } as const;
    const request = vi.fn(async (path: string) => {
      if (path === "/payment-methods") return [{ id: "transfer", name: "TRANSFERENCIA", active: true }];
      if (path === "/customer-receivables/doc-1/payments/pay-1/print") return receipt;
      if (path.startsWith("/customer-receivables/payment-history")) return [history];
      return [];
    });
    const printReceipt = vi.fn().mockResolvedValue({ status: "PRINTED" });
    const terminalContext = { storeName: "Tienda", terminalCode: "01" };
    render(<CustomerReceivablesScreen locale="es" session={session} terminalContext={terminalContext} request={request as any} printReceipt={printReceipt as any} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    await waitFor(() => expect(request).toHaveBeenCalledWith("/customer-receivables", { token: "token" }));

    fireEvent.click(screen.getByRole("button", { name: "Histórico de cobros" }));
    expect(await screen.findByText("TR-1")).toBeVisible();
    fireEvent.change(screen.getByLabelText("Buscar cliente o documento"), { target: { value: "Cliente Uno" } });
    fireEvent.change(screen.getByLabelText("Forma de pago"), { target: { value: "transfer" } });
    fireEvent.change(screen.getByLabelText("Cobrado desde"), { target: { value: "2026-07-01" } });
    fireEvent.change(screen.getByLabelText("Cobrado hasta"), { target: { value: "2026-07-20" } });
    await waitFor(() => {
      const path = String(request.mock.calls.at(-1)?.[0]);
      expect(path).toContain("search=Cliente+Uno");
      expect(path).toContain("paymentMethodId=transfer");
      expect(path).toContain("collectedFrom=2026-07-01");
      expect(path).toContain("collectedTo=2026-07-20");
    });

    fireEvent.click(screen.getByRole("button", { name: "Consultar FV-1" }));
    expect(await screen.findByRole("dialog", { name: "Detalle del cobro" })).toBeVisible();
    await waitFor(() => expect(request).toHaveBeenCalledWith("/customer-receivables/doc-1/payments/pay-1/print", { token: "token" }));
    fireEvent.click(screen.getByRole("button", { name: "Reimprimir justificante" }));
    await waitFor(() => expect(printReceipt).toHaveBeenCalledWith(receipt, terminalContext, undefined, "es"));
    expect((request.mock.calls as any[]).every(([, options]) => !options?.method || options.method === "GET")).toBe(true);
    expect(request.mock.calls.some(([path]) => String(path) === "/customer-receivables/doc-1/payments")).toBe(false);
  });

  it("retries a failed load and refreshes the row after a payment", async () => {
    const request = vi.fn(async (path: string) => {
      if (request.mock.calls.length === 1) throw new Error("sin red");
      if (path === "/payment-methods") return [{ id: "transfer", name: "TRANSFERENCIA", active: true }];
      if (path.endsWith("/payments")) return { receivable: { ...row, paidTotal: "50.00", pendingTotal: "50.00" }, paymentReceipt: {
        paymentId: "payment-1", documentNumber: "FV-1", collectedAt: "2026-07-16T10:00:00Z",
        method: "TRANSFERENCIA", amount: "25.00", remaining: "50.00"
      } };
      return request.mock.calls.some(([called]) => String(called).endsWith("/payments")) ? [{ ...row, paidTotal: "50.00", pendingTotal: "50.00" }] : [row];
    });
    render(<CustomerReceivablesScreen locale="es" session={session} terminalContext={{ storeName: "Tienda", terminalCode: "01" }} request={request as any} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("sin red");
    fireEvent.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(await screen.findByText("FV-1")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Cobrar FV-1" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Transferencia" })).toBeEnabled());
    fireEvent.change(screen.getByLabelText("Importe a cobrar"), { target: { value: "25" } });
    fireEvent.click(screen.getByRole("button", { name: "Transferencia" }));
    fireEvent.change(screen.getByLabelText("Referencia"), { target: { value: "TR-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar transferencia" }));
    await waitFor(() => expect(screen.getAllByText("50,00")).toHaveLength(2));
  });

  it("ignores an older filter response that arrives after the current request", async () => {
    let resolveOld!: (value: unknown) => void;
    const request = vi.fn((path: string) => path.includes("search=old") ? new Promise((resolve) => { resolveOld = resolve; }) : Promise.resolve(path.includes("search=new") ? [{ ...row, documentNumber: "NEW" }] : []));
    render(<CustomerReceivablesScreen locale="es" session={session} terminalContext={{ storeName: "Tienda", terminalCode: "01" }} request={request as any} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("Buscar deuda"), { target: { value: "old" } }); await waitFor(() => expect(request.mock.calls.some(([path]) => String(path).includes("search=old"))).toBe(true));
    fireEvent.change(screen.getByLabelText("Buscar deuda"), { target: { value: "new" } }); expect(await screen.findByText("NEW")).toBeVisible();
    resolveOld([{ ...row, documentNumber: "OLD" }]); await Promise.resolve(); expect(screen.queryByText("OLD")).not.toBeInTheDocument();
  });

  it("uses semantic cells and ignores a response after unmount", async () => {
    let resolve!: (value: unknown) => void; const request = vi.fn(() => new Promise((done) => { resolve = done; }));
    const view = render(<CustomerReceivablesScreen locale="es" session={session} terminalContext={{ storeName: "Tienda", terminalCode: "01" }} request={request as any} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    view.unmount(); resolve([row]); await Promise.resolve();
    const immediate = vi.fn().mockResolvedValue([row]); render(<CustomerReceivablesScreen locale="es" session={session} terminalContext={{ storeName: "Tienda", terminalCode: "01" }} request={immediate as any} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    expect(await screen.findAllByRole("cell")).toHaveLength(9);
  });
});
