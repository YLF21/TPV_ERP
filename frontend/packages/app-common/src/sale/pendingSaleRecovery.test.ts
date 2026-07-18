import { describe, expect, it } from "vitest";
import type { PendingSaleDraft, PendingPaymentAllocation } from "./customerReceivables";
import {
  clearPendingSaleRecovery,
  loadPendingSaleRecovery,
  pendingSaleRecoveryKey,
  savePendingSaleRecovery,
  type PendingSaleRecoveryEnvelope,
} from "./pendingSaleRecovery";

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();
  get length() { return this.values.size; }
  clear() { this.values.clear(); }
  getItem(key: string) { return this.values.get(key) ?? null; }
  key(index: number) { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string) { this.values.delete(key); }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

const draft: PendingSaleDraft = {
  checkoutId: "checkout-1", warehouseId: "warehouse-1", type: "FACTURA_VENTA",
  date: "2026-07-18", customerId: "customer-1", dueDate: "2026-08-17",
  globalDiscount: "0.00", lines: [{ productId: "product-1", quantity: 1,
    code: "P-1", name: "Producto", price: "100.00", discount: "0.00",
    taxesIncluded: true, taxRegime: "GENERAL", taxPercentage: "21.00" }],
};

const card: PendingPaymentAllocation = {
  id: "checkout-1", operationId: "checkout-1", kind: "INTEGRATED_CARD",
  methodId: "card-method", amountCents: 3_000, status: "TIMEOUT",
};

const envelope = (): PendingSaleRecoveryEnvelope => ({
  version: 1,
  terminalCode: "T-01",
  customer: { id: "customer-1", name: "Cliente Uno" },
  draft,
  quoteCents: 10_000,
  quoteReady: true,
  payments: [card],
  savedAt: "2026-07-18T10:00:00.000Z",
});

describe("pending sale recovery envelope", () => {
  it("round-trips the exact authoritative checkout under its terminal key", () => {
    const storage = new MemoryStorage();
    savePendingSaleRecovery(storage, envelope());

    expect(loadPendingSaleRecovery(storage, "T-01")).toEqual({ status: "valid", envelope: envelope() });
    expect(storage.getItem(pendingSaleRecoveryKey("T-01"))).toContain('"checkoutId":"checkout-1"');

    clearPendingSaleRecovery(storage, "T-01");
    expect(loadPendingSaleRecovery(storage, "T-01")).toEqual({ status: "empty" });
  });

  it("fails closed when an envelope belongs to another terminal", () => {
    const storage = new MemoryStorage();
    storage.setItem(pendingSaleRecoveryKey("T-01"), JSON.stringify({ ...envelope(), terminalCode: "T-02" }));

    expect(loadPendingSaleRecovery(storage, "T-01")).toMatchObject({
      status: "blocked", reason: "TERMINAL_MISMATCH", identifiers: ["checkout-1"],
    });
    expect(storage.getItem(pendingSaleRecoveryKey("T-01"))).not.toBeNull();
  });

  it("fails closed when customer or card operation identity does not match the draft", () => {
    const storage = new MemoryStorage();
    storage.setItem(pendingSaleRecoveryKey("T-01"), JSON.stringify({
      ...envelope(), customer: { id: "customer-2", name: "Otro" },
      payments: [{ ...card, operationId: "operation-other" }],
    }));

    expect(loadPendingSaleRecovery(storage, "T-01")).toMatchObject({
      status: "blocked", reason: "IDENTITY_MISMATCH",
      identifiers: expect.arrayContaining(["checkout-1", "operation-other"]),
    });
  });

  it("keeps corrupt JSON and extracts identifiers for administrative recovery", () => {
    const storage = new MemoryStorage();
    const raw = '{"checkoutId":"checkout-recoverable","operationId":"operation-recoverable"';
    storage.setItem(pendingSaleRecoveryKey("T-01"), raw);

    expect(loadPendingSaleRecovery(storage, "T-01")).toEqual({
      status: "blocked", reason: "CORRUPT", raw,
      identifiers: ["checkout-recoverable", "operation-recoverable"],
    });
    expect(storage.getItem(pendingSaleRecoveryKey("T-01"))).toBe(raw);
  });
});
