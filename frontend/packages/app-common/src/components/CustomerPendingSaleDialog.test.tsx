// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  addLocalDays,
  pendingAllocationCents,
  pendingCreateBody,
  pendingHasCardEffect,
  pendingSummary,
  type PendingSaleDraft,
} from "../sale/customerReceivables";
import type { PendingSaleRecoveryEnvelope } from "../sale/pendingSaleRecovery";
import { cardQueryResultStatus, CustomerPendingSaleDialog } from "./CustomerPendingSaleDialog";

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
  it("never downgrades an approved card from a stale query response", () => {
    expect(cardQueryResultStatus("APPROVED", "TIMEOUT")).toBe("APPROVED");
    expect(cardQueryResultStatus("APPROVED", "DECLINED")).toBe("APPROVED");
    expect(cardQueryResultStatus("TIMEOUT", "APPROVED")).toBe("APPROVED");
  });
  it.each([
    ["30.00", 10_000, 3_000],
    ["30,25", 10_000, 3_025],
    ["0", 10_000, 0],
    ["-1", 10_000, 0],
    ["100.01", 10_000, 0],
    ["1.001", 10_000, 0],
    ["texto", 10_000, 0],
  ])("validates allocation %s against the remaining cents", (input, remaining, expected) => {
    expect(pendingAllocationCents(input, remaining)).toBe(expected);
  });

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
  it("persists the exact draft and pending card operation before the terminal side effect", async () => {
    const persist = vi.fn();
    const request = vi.fn(async (path: string) => {
      if (path.endsWith("/quote")) return { total: "100.00" };
      if (path.endsWith("/card-charges")) {
        expect(persist).toHaveBeenCalledWith(expect.objectContaining({
          draft: expect.objectContaining({ checkoutId: "checkout-1" }),
          quoteCents: 10_000,
          payments: [expect.objectContaining({ id: "checkout-1", operationId: "checkout-1", amountCents: 3_000, status: "PENDING" })],
        }));
        return { status: "APPROVED" };
      }
      throw new Error(`unexpected ${path}`);
    });
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} paymentMethods={{ card: "card-method" }}
      terminalContext={{ storeName: "Tienda", terminalCode: "T-1" }} request={request as never}
      onPersistRecovery={persist} onClearRecovery={vi.fn()} onCancel={vi.fn()} onSuccess={vi.fn()} />);

    fireEvent.change(await screen.findByLabelText(/importe inicial/i), { target: { value: "30" } });
    fireEvent.click(screen.getByRole("button", { name: /tarjeta/i }));
    await screen.findByText(/tarjeta aprobada/i);
    expect(persist).toHaveBeenLastCalledWith(expect.objectContaining({
      payments: [expect.objectContaining({ status: "APPROVED" })],
    }));
  });

  it("does not call the payment terminal when durable persistence fails", async () => {
    const request = vi.fn().mockResolvedValueOnce({ total: "100.00" });
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} paymentMethods={{ card: "card-method" }}
      terminalContext={{ storeName: "Tienda", terminalCode: "T-1" }} request={request}
      onPersistRecovery={() => { throw new Error("storage unavailable"); }} onCancel={vi.fn()} onSuccess={vi.fn()} />);
    fireEvent.change(await screen.findByLabelText(/importe inicial/i), { target: { value: "30" } });
    fireEvent.click(screen.getByRole("button", { name: /tarjeta/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/recuperaci[oó]n/i);
    expect(request).toHaveBeenCalledTimes(1);
  });

  it("restores an uncertain card without requoting and reuses its identifiers through query and creation", async () => {
    const recovery: PendingSaleRecoveryEnvelope = {
      version: 2,
      phase: "CARD_IN_FLIGHT",
      terminalCode: "T-1",
      customer: { id: "customer-1", name: "Cliente" },
      draft,
      quoteCents: 10_000,
      quoteReady: true,
      payments: [{ id: "checkout-1", operationId: "checkout-1", mode: "INTEGRATED", kind: "INTEGRATED_CARD", methodId: "card-method", amountCents: 3_000, status: "TIMEOUT" }],
      savedAt: "2026-07-18T08:00:00.000Z",
    };
    const clear = vi.fn();
    const persist = vi.fn();
    const request = vi.fn()
      .mockResolvedValueOnce({ status: "APPROVED" })
      .mockResolvedValueOnce({ receivable: { documentId: "doc-recovered" }, printDocument: {} });
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} recovery={recovery}
      paymentMethods={{ card: "card-method" }} terminalContext={{ storeName: "Tienda", terminalCode: "T-1" }}
      request={request} onPersistRecovery={persist} onClearRecovery={clear} onCancel={vi.fn()} onSuccess={vi.fn()} />);

    expect(request).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: /consultar tarjeta/i }));
    await screen.findByText(/tarjeta aprobada/i);
    expect(request.mock.calls[0][0]).toContain("checkout-1");
    fireEvent.click(screen.getByRole("button", { name: /confirmar venta pendiente/i }));
    await waitFor(() => expect(clear).toHaveBeenCalledOnce());
    expect(request.mock.calls[1][1].body).toMatchObject({ checkoutId: "checkout-1", quotedTotal: "100.00", payments: [
      { requestId: "checkout-1", paymentTerminalOperationId: "checkout-1", amount: "30.00" },
    ] });
  });

  it("runs card query single-flight and disables its action until the response arrives", async () => {
    let resolve!: (value: { status: "APPROVED" }) => void;
    const deferred = new Promise<{ status: "APPROVED" }>((done) => { resolve = done; });
    const recovery: PendingSaleRecoveryEnvelope = {
      version: 2, phase: "CARD_IN_FLIGHT", terminalCode: "T-1", customer: { id: "customer-1", name: "Cliente" }, draft,
      quoteCents: 10_000, quoteReady: true, savedAt: "2026-07-18T08:00:00.000Z",
      payments: [{ id: "checkout-1", operationId: "checkout-1", mode: "INTEGRATED", kind: "INTEGRATED_CARD", methodId: "card-method", amountCents: 3_000, status: "TIMEOUT" }],
    };
    const request = vi.fn().mockReturnValue(deferred);
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} recovery={recovery} paymentMethods={{ card: "card-method" }}
      terminalContext={{ storeName: "Tienda", terminalCode: "T-1" }} request={request}
      onPersistRecovery={vi.fn()} onClearRecovery={vi.fn()} onCancel={vi.fn()} onSuccess={vi.fn()} />);
    const query = screen.getByRole("button", { name: /consultar tarjeta/i });
    fireEvent.click(query);
    fireEvent.click(query);
    expect(request).toHaveBeenCalledOnce();
    expect(query).toBeDisabled();
    resolve({ status: "APPROVED" });
    expect(await screen.findByText(/tarjeta aprobada/i)).toBeInTheDocument();
  });

  it("ignores a late card query response after unmount", async () => {
    let resolve!: (value: { status: "APPROVED" }) => void;
    const deferred = new Promise<{ status: "APPROVED" }>((done) => { resolve = done; });
    const recovery: PendingSaleRecoveryEnvelope = {
      version: 2, phase: "CARD_IN_FLIGHT", terminalCode: "T-1", customer: { id: "customer-1", name: "Cliente" }, draft,
      quoteCents: 10_000, quoteReady: true, savedAt: "2026-07-18T08:00:00.000Z",
      payments: [{ id: "checkout-1", operationId: "checkout-1", mode: "INTEGRATED", kind: "INTEGRATED_CARD", methodId: "card-method", amountCents: 3_000, status: "TIMEOUT" }],
    };
    const persist = vi.fn();
    const view = render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} recovery={recovery} paymentMethods={{ card: "card-method" }}
      terminalContext={{ storeName: "Tienda", terminalCode: "T-1" }} request={vi.fn().mockReturnValue(deferred)}
      onPersistRecovery={persist} onClearRecovery={vi.fn()} onCancel={vi.fn()} onSuccess={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: /consultar tarjeta/i }));
    view.unmount();
    resolve({ status: "APPROVED" });
    await Promise.resolve(); await Promise.resolve();
    expect(persist).not.toHaveBeenCalled();
  });

  it("creates an already approved recovered checkout exactly once with its original identifiers", async () => {
    const recovery: PendingSaleRecoveryEnvelope = {
      version: 2, phase: "READY_TO_CREATE", terminalCode: "T-1", customer: { id: "customer-1", name: "Cliente" }, draft,
      quoteCents: 10_000, quoteReady: true, savedAt: "2026-07-18T08:00:00.000Z",
      payments: [{ id: "checkout-1", operationId: "checkout-1", mode: "INTEGRATED", kind: "INTEGRATED_CARD", methodId: "card-method", amountCents: 3_000, status: "APPROVED" }],
    };
    const clear = vi.fn();
    const request = vi.fn().mockResolvedValue({ receivable: { documentId: "doc-approved" }, printDocument: {} });
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} recovery={recovery} paymentMethods={{ card: "card-method" }}
      terminalContext={{ storeName: "Tienda", terminalCode: "T-1" }} request={request} onClearRecovery={clear} onCancel={vi.fn()} onSuccess={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: /confirmar venta pendiente/i }));
    await waitFor(() => expect(request).toHaveBeenCalledOnce());
    expect(request.mock.calls[0][0]).toBe("/pos/customer-pending-sales");
    expect(request.mock.calls[0][1].body).toMatchObject({ checkoutId: "checkout-1", payments: [
      { requestId: "checkout-1", paymentTerminalOperationId: "checkout-1", amount: "30.00" },
    ] });
    expect(clear).toHaveBeenCalledOnce();
  });

  it("replays the same recovered create body after a lost response and clears only after confirmation", async () => {
    const recovery: PendingSaleRecoveryEnvelope = {
      version: 2, phase: "READY_TO_CREATE", terminalCode: "T-1", customer: { id: "customer-1", name: "Cliente" }, draft,
      quoteCents: 10_000, quoteReady: true, savedAt: "2026-07-18T08:00:00.000Z",
      payments: [{ id: "checkout-1", operationId: "checkout-1", mode: "INTEGRATED", kind: "INTEGRATED_CARD", methodId: "card-method", amountCents: 3_000, status: "APPROVED" }],
    };
    const clear = vi.fn();
    const firstRequest = vi.fn().mockRejectedValue(new Error("response lost"));
    const first = render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} recovery={recovery} paymentMethods={{ card: "card-method" }}
      terminalContext={{ storeName: "Tienda", terminalCode: "T-1" }} request={firstRequest} onClearRecovery={clear} onCancel={vi.fn()} onSuccess={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: /confirmar venta pendiente/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent("response lost");
    expect(clear).not.toHaveBeenCalled();
    const firstBody = firstRequest.mock.calls[0][1].body;
    first.unmount();

    const secondRequest = vi.fn().mockResolvedValue({ receivable: { documentId: "doc-replayed" }, printDocument: {} });
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} recovery={recovery} paymentMethods={{ card: "card-method" }}
      terminalContext={{ storeName: "Tienda", terminalCode: "T-1" }} request={secondRequest} onClearRecovery={clear} onCancel={vi.fn()} onSuccess={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: /confirmar venta pendiente/i }));
    await waitFor(() => expect(clear).toHaveBeenCalledOnce());
    expect(secondRequest.mock.calls[0][1].body).toEqual(firstBody);
  });

  it.each(["DECLINED", "ERROR", "CANCELLED"] as const)("requires explicit discard for recovered %s and rotates the next card operation", async (status) => {
    const recovery: PendingSaleRecoveryEnvelope = {
      version: 2, phase: "CARD_FINAL_FAILURE", terminalCode: "T-1", customer: { id: "customer-1", name: "Cliente" }, draft,
      quoteCents: 10_000, quoteReady: true, savedAt: "2026-07-18T08:00:00.000Z",
      payments: [{ id: "checkout-1", operationId: "checkout-1", mode: "INTEGRATED", kind: "INTEGRATED_CARD", methodId: "card-method", amountCents: 3_000, status }],
    };
    const clear = vi.fn();
    const persist = vi.fn();
    const request = vi.fn().mockResolvedValue({ status: "APPROVED" });
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} recovery={recovery} paymentMethods={{ card: "card-method" }}
      terminalContext={{ storeName: "Tienda", terminalCode: "T-1" }} request={request}
      onPersistRecovery={persist} onClearRecovery={clear} onCancel={vi.fn()} onSuccess={vi.fn()} />);

    expect(clear).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Eliminar" }));
    expect(clear).toHaveBeenCalledOnce();
    fireEvent.change(screen.getByLabelText(/importe inicial/i), { target: { value: "10" } });
    fireEvent.click(screen.getByRole("button", { name: /tarjeta/i }));
    await waitFor(() => expect(request).toHaveBeenCalledOnce());
    const body = request.mock.calls[0][1].body;
    expect(body.sale.checkoutId).not.toBe("checkout-1");
    expect(body.sale.payments[0].requestId).toBe(body.sale.checkoutId);
    expect(body.sale.payments[0].paymentTerminalOperationId).toBe(body.sale.checkoutId);
  });

  it("persists a fully pending exact create request before POST", async () => {
    const persist = vi.fn();
    const request = vi.fn(async (path: string) => {
      if (path.endsWith("/quote")) return { total: "100.00" };
      expect(persist).toHaveBeenCalledWith(expect.objectContaining({ version: 2, phase: "READY_TO_CREATE", payments: [], quoteCents: 10_000 }));
      return { receivable: { documentId: "doc-pending" }, printDocument: {} };
    });
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} paymentMethods={{}}
      terminalContext={{ storeName: "Tienda", terminalCode: "T-1" }} request={request as never}
      onPersistRecovery={persist} onClearRecovery={vi.fn()} onCancel={vi.fn()} onSuccess={vi.fn()} />);
    await screen.findAllByText("100,00");
    fireEvent.click(screen.getByRole("button", { name: /confirmar venta pendiente/i }));
    await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
  });

  it("persists exact cash and transfer allocations before their create POST", async () => {
    const persist = vi.fn();
    const request = vi.fn(async (path: string) => {
      if (path.endsWith("/quote")) return { total: "100.00" };
      expect(persist).toHaveBeenLastCalledWith(expect.objectContaining({
        phase: "READY_TO_CREATE",
        payments: [
          expect.objectContaining({ kind: "CASH", amountCents: 2_000, deliveredCents: 5_000, changeCents: 3_000 }),
          expect.objectContaining({ kind: "TRANSFER", amountCents: 3_000, reference: "TR-50" }),
        ],
      }));
      return { receivable: { documentId: "doc-standard" }, printDocument: {} };
    });
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft}
      paymentMethods={{ cash: "cash-method", transfer: "transfer-method" }}
      terminalContext={{ storeName: "Tienda", terminalCode: "T-1" }} request={request as never}
      onPersistRecovery={persist} onClearRecovery={vi.fn()} onCancel={vi.fn()} onSuccess={vi.fn()} />);
    const amount = await screen.findByLabelText(/importe inicial/i);
    fireEvent.change(amount, { target: { value: "20" } });
    fireEvent.click(screen.getByRole("button", { name: /efectivo/i }));
    const cashDialog = screen.getByRole("dialog", { name: /cobro en efectivo/i });
    fireEvent.change(within(cashDialog).getByRole("textbox", { name: /dinero recibido/i }), { target: { value: "50" } });
    fireEvent.click(within(cashDialog).getByRole("button", { name: /confirmar cobro/i }));
    fireEvent.click(screen.getByRole("button", { name: /transferencia/i }));
    const transfer = screen.getByRole("group", { name: /transferencia/i });
    fireEvent.change(within(transfer).getByLabelText(/importe/i), { target: { value: "30" } });
    fireEvent.change(within(transfer).getByLabelText(/referencia/i), { target: { value: "TR-50" } });
    fireEvent.click(within(transfer).getByRole("button", { name: /guardar transferencia/i }));
    fireEvent.click(screen.getByRole("button", { name: /confirmar venta pendiente/i }));
    await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
  });

  it("reopens and byte-replays a fully pending create after its response is lost", async () => {
    let recovery!: PendingSaleRecoveryEnvelope;
    const firstRequest = vi.fn().mockResolvedValueOnce({ total: "100.00" }).mockRejectedValueOnce(new Error("response lost"));
    const first = render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} paymentMethods={{}}
      terminalContext={{ storeName: "Tienda", terminalCode: "T-1" }} request={firstRequest}
      onPersistRecovery={(value) => { recovery = value; }} onClearRecovery={vi.fn()} onCancel={vi.fn()} onSuccess={vi.fn()} />);
    await screen.findAllByText("100,00");
    fireEvent.click(screen.getByRole("button", { name: /confirmar venta pendiente/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent("response lost");
    const firstBody = firstRequest.mock.calls[1][1].body;
    expect(recovery).toMatchObject({ phase: "READY_TO_CREATE", payments: [], draft: { checkoutId: "checkout-1" } });
    first.unmount();

    const secondRequest = vi.fn().mockResolvedValue({ receivable: { documentId: "doc-replayed" }, printDocument: {} });
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} recovery={recovery} paymentMethods={{}}
      terminalContext={{ storeName: "Tienda", terminalCode: "T-1" }} request={secondRequest}
      onPersistRecovery={vi.fn()} onClearRecovery={vi.fn()} onCancel={vi.fn()} onSuccess={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: /confirmar venta pendiente/i }));
    await waitFor(() => expect(secondRequest).toHaveBeenCalledOnce());
    expect(secondRequest.mock.calls[0][1].body).toEqual(firstBody);
  });

  it("allocates 30 cash from a 100 invoice and keeps 70 pending with received/change", async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({ total: "100.00" })
      .mockResolvedValueOnce({ receivable: { documentId: "doc-cash" }, printDocument: {} });
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={{ ...draft, type: "FACTURA_VENTA" }}
      paymentMethods={{ cash: "cash-method" }} request={request} onCancel={vi.fn()} onSuccess={vi.fn()} />);

    const amount = await screen.findByLabelText(/importe inicial/i);
    fireEvent.change(amount, { target: { value: "30,00" } });
    fireEvent.click(screen.getByRole("button", { name: /efectivo/i }));
    const cashDialog = screen.getByRole("dialog", { name: /cobro en efectivo/i });
    expect(within(cashDialog).getAllByText("30,00").length).toBeGreaterThan(0);
    fireEvent.change(within(cashDialog).getByRole("textbox", { name: /dinero recibido/i }), { target: { value: "50" } });
    fireEvent.click(within(cashDialog).getByRole("button", { name: /confirmar cobro/i }));

    expect(await screen.findByText("70,00")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /confirmar venta pendiente/i }));
    await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
    expect(request.mock.calls[1][1].body).toMatchObject({ quotedTotal: "100.00", payments: [
      { methodId: "cash-method", amount: "30.00", delivered: "50.00", change: "20.00" },
    ] });
  });

  it("charges exactly 30 by card from a 100 delivery note and confirms 70 pending", async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({ total: "100.00" })
      .mockResolvedValueOnce({ status: "APPROVED" })
      .mockResolvedValueOnce({ receivable: { documentId: "doc-card" }, printDocument: {} });
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft}
      paymentMethods={{ card: "card-method" }} request={request} onCancel={vi.fn()} onSuccess={vi.fn()} />);

    fireEvent.change(await screen.findByLabelText(/importe inicial/i), { target: { value: "30.00" } });
    fireEvent.click(screen.getByRole("button", { name: /tarjeta/i }));
    await screen.findByText(/tarjeta aprobada/i);

    expect(request.mock.calls[1][0]).toBe("/pos/customer-pending-sales/card-charges");
    expect(request.mock.calls[1][1].body).toMatchObject({ amount: "30.00", sale: { quotedTotal: "100.00", payments: [
      { amount: "30.00", requestId: "checkout-1", paymentTerminalOperationId: "checkout-1" },
    ] } });
    expect(screen.getByText("70,00")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /confirmar venta pendiente/i }));
    await waitFor(() => expect(request).toHaveBeenCalledTimes(3));
    expect(request.mock.calls[2][1].body.payments[0].amount).toBe("30.00");
  });

  it("supports multiple partial allocations and rejects zero or overpayment", async () => {
    const request = vi.fn().mockResolvedValueOnce({ total: "100.00" });
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft}
      paymentMethods={{ cash: "cash-method", card: "card-method", transfer: "transfer-method" }}
      request={request} onCancel={vi.fn()} onSuccess={vi.fn()} />);

    const amount = await screen.findByLabelText(/importe inicial/i);
    fireEvent.change(amount, { target: { value: "0" } });
    fireEvent.click(screen.getByRole("button", { name: /tarjeta/i }));
    expect(screen.getByRole("alert")).toHaveTextContent(/importe/i);
    fireEvent.change(amount, { target: { value: "100.01" } });
    fireEvent.click(screen.getByRole("button", { name: /tarjeta/i }));
    expect(request).toHaveBeenCalledTimes(1);

    fireEvent.change(amount, { target: { value: "20" } });
    fireEvent.click(screen.getByRole("button", { name: /efectivo/i }));
    const cashDialog = screen.getByRole("dialog", { name: /cobro en efectivo/i });
    fireEvent.change(within(cashDialog).getByRole("textbox", { name: /dinero recibido/i }), { target: { value: "20" } });
    fireEvent.click(within(cashDialog).getByRole("button", { name: /confirmar cobro/i }));
    fireEvent.click(screen.getByRole("button", { name: /transferencia/i }));
    const transfer = screen.getByRole("group", { name: /transferencia/i });
    fireEvent.change(within(transfer).getByLabelText(/importe/i), { target: { value: "30" } });
    fireEvent.change(within(transfer).getByLabelText(/referencia/i), { target: { value: "TR-30" } });
    fireEvent.click(within(transfer).getByRole("button", { name: /guardar transferencia/i }));
    expect(screen.getAllByText("50,00")).toHaveLength(2);
  });

  it("quotes authoritatively, edits type/due date and submits without a fake pending payment", async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({ total: "10.01" })
      .mockResolvedValueOnce({ receivable: { documentId: "doc-1", documentNumber: "AV-1" }, printDocument: {} });
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
    fireEvent.change(screen.getByLabelText(/importe inicial/i), { target: { value: "10" } });
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

    const cash = screen.getByRole("button", { name: /efectivo/i });
    await waitFor(() => expect(cash).toBeEnabled());
    expect(screen.getByRole("button", { name: /tarjeta/i })).toBeEnabled();
    expect(screen.getByRole("button", { name: /transferencia/i })).toBeEnabled();
    fireEvent.change(screen.getByLabelText(/importe inicial/i), { target: { value: "10" } });
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
    fireEvent.change(screen.getByLabelText(/importe inicial/i), { target: { value: "10" } });
    fireEvent.click(screen.getByRole("button", { name: /añadir tarjeta/i }));
    expect(await screen.findByText(/Rechazada/)).toBeInTheDocument();

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

  it("prints the authoritative document returned by its own mutation without a follow-up GET", async () => {
    const printDocument = vi.fn().mockResolvedValue({ status: "PRINTED" });
    const authoritative = { documentId: "doc-1", documentType: "FACTURA_VENTA", documentNumber: "FV-1", issuedAt: "2026-07-16T10:00:00Z", lines: [], baseTotal: "100.00", taxTotal: "21.00", total: "121.00" };
    const result = { receivable: { documentId: "doc-1" }, printDocument: authoritative };
    const request = vi.fn().mockResolvedValueOnce({ total: "10.00" })
      .mockResolvedValueOnce(result);
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} paymentMethods={{}} request={request} terminalContext={{ storeName: "Tienda", terminalCode: "01" }} printDocument={printDocument} onCancel={vi.fn()} onSuccess={vi.fn()} />);
    await screen.findAllByText("10,00"); fireEvent.click(screen.getByRole("button", { name: /confirmar venta pendiente/i }));
    await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
    expect(printDocument).toHaveBeenCalledWith(authoritative, { storeName: "Tienda", terminalCode: "01" }, undefined, "es");
  });

  it("completes the financial mutation even when document printing fails", async () => {
    const onSuccess = vi.fn();
    const authoritative = { documentId: "doc-1", documentType: "FACTURA_VENTA", documentNumber: "FV-1", lines: [], baseTotal: "100.00", taxTotal: "21.00", total: "121.00" };
    const request = vi.fn().mockResolvedValueOnce({ total: "121.00" })
      .mockResolvedValueOnce({ receivable: { documentId: "doc-1" }, printDocument: authoritative });
    render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft} paymentMethods={{}} request={request} terminalContext={{ storeName: "Tienda", terminalCode: "01" }} printDocument={vi.fn().mockResolvedValue({ status: "FAILED", technicalMessage: "printer offline" })} onCancel={vi.fn()} onSuccess={onSuccess} />);
    fireEvent.click(await screen.findByRole("button", { name: /confirmar venta pendiente/i }));
    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith({ documentId: "doc-1" }, expect.any(Function)));
  });
});
