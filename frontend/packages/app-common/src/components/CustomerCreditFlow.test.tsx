// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { PendingSaleDraft } from "../sale/customerReceivables";
import { CustomerPendingSaleDialog } from "./CustomerPendingSaleDialog";
import {
  CustomerReceivablePaymentDialog,
  type CustomerReceivable,
} from "./CustomerReceivablePaymentDialog";

afterEach(() => {
  cleanup();
  localStorage.clear();
});

const draft: PendingSaleDraft = {
  checkoutId: "checkout-flow",
  warehouseId: "warehouse-1",
  type: "FACTURA_VENTA",
  date: "2026-07-20",
  customerId: "customer-1",
  dueDate: "2026-08-19",
  globalDiscount: "0.00",
  lines: [{
    productId: "product-1", quantity: 1, code: "P-1", name: "Producto", price: "100.00",
    discount: "0.00", taxesIncluded: true, taxRegime: "IVA", taxPercentage: "21.00",
  }],
};

describe("customer credit full flow", () => {
  it("creates debt with an initial payment, collects it partially and fully, and prints every result", async () => {
    let receivable: CustomerReceivable = {
      documentId: "document-flow", documentType: "FACTURA_VENTA", documentNumber: "FV-FLOW",
      customerId: "customer-1", customerName: "Cliente flujo", issueDate: "2026-07-20",
      dueDate: "2026-08-19", total: "100.00", paidTotal: "30.00", pendingTotal: "70.00",
      status: "PARCIAL", overdue: false,
    };
    const salePrint = vi.fn().mockResolvedValue({ status: "PRINTED" });
    const saleRequest = vi.fn(async (path: string, options?: { body?: any }) => {
      if (path.endsWith("/quote")) return {
        total: "100.00",
        credit: {
          enabled: true, creditRequired: true, blocked: false, blockReason: null, limit: "500.00",
          outstandingDebt: "0.00", overdueDebt: "0.00", availableCredit: "500.00",
          paymentTermDays: 30, proposedOutstanding: "100.00", requiresOverride: false,
          limitExceeded: false, overdueBlocked: false, manualBlocked: false,
        },
      };
      if (path === "/pos/customer-pending-sales") {
        expect(options?.body.payments).toEqual([
          expect.objectContaining({ methodId: "cash-method", amount: "30.00" }),
        ]);
        return { receivable, printDocument: {} };
      }
      throw new Error(`unexpected ${path}`);
    });
    const saleCompleted = vi.fn();
    render(<CustomerPendingSaleDialog
      customerName="Cliente flujo"
      draft={draft}
      paymentMethods={{ cash: "cash-method" }}
      request={saleRequest as never}
      terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      printDocument={salePrint as never}
      onCancel={vi.fn()}
      onSuccess={saleCompleted}
    />);

    fireEvent.change(await screen.findByLabelText(/importe inicial/i), { target: { value: "30" } });
    fireEvent.click(screen.getByRole("button", { name: /añadir efectivo/i }));
    const cash = screen.getByRole("dialog", { name: /cobro en efectivo/i });
    fireEvent.change(within(cash).getByRole("textbox", { name: /dinero recibido/i }), { target: { value: "30" } });
    fireEvent.click(within(cash).getByRole("button", { name: /confirmar cobro/i }));
    fireEvent.click(screen.getByRole("button", { name: /confirmar venta pendiente/i }));
    await waitFor(() => expect(saleCompleted).toHaveBeenCalledWith(receivable));
    expect(salePrint).toHaveBeenCalledOnce();

    cleanup();
    const receiptPrint = vi.fn().mockResolvedValue({ status: "PRINTED" });
    const collectionRequest = vi.fn(async (path: string, options?: { body?: any }) => {
      if (path === "/payment-methods") return [{ id: "transfer-method", name: "TRANSFERENCIA", active: true }];
      if (path.endsWith("/payments")) {
        const amount = Number(options?.body.pagos[0].importe);
        const paid = Number(receivable.paidTotal) + amount;
        const pending = Number(receivable.total) - paid;
        receivable = {
          ...receivable,
          paidTotal: paid.toFixed(2),
          pendingTotal: pending.toFixed(2),
          status: pending === 0 ? "PAGADO" : "PARCIAL",
        };
        return {
          receivable,
          paymentReceipt: {
            paymentId: `payment-${paid}`, documentNumber: receivable.documentNumber,
            collectedAt: "2026-07-20T12:00:00Z", method: "TRANSFERENCIA",
            amount: amount.toFixed(2), remaining: pending.toFixed(2),
          },
        };
      }
      throw new Error(`unexpected ${path}`);
    });

    const collect = async (amount: string) => {
      const paid = vi.fn();
      render(<CustomerReceivablePaymentDialog
        receivable={receivable}
        terminalCode="01"
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        request={collectionRequest as never}
        printReceipt={receiptPrint as never}
        onCancel={vi.fn()}
        onPaid={paid}
      />);
      await waitFor(() => expect(screen.getByRole("button", { name: "Transferencia" })).toBeEnabled());
      fireEvent.change(screen.getByLabelText("Importe a cobrar"), { target: { value: amount } });
      fireEvent.click(screen.getByRole("button", { name: "Transferencia" }));
      fireEvent.change(screen.getByLabelText("Referencia"), { target: { value: `TR-${amount}` } });
      fireEvent.click(screen.getByRole("button", { name: "Confirmar transferencia" }));
      await waitFor(() => expect(paid).toHaveBeenCalledWith(receivable));
      cleanup();
    };

    await collect("20");
    expect(receivable).toMatchObject({ paidTotal: "50.00", pendingTotal: "50.00", status: "PARCIAL" });
    await collect("50");
    expect(receivable).toMatchObject({ paidTotal: "100.00", pendingTotal: "0.00", status: "PAGADO" });
    expect(receiptPrint).toHaveBeenCalledTimes(2);
  });
});
