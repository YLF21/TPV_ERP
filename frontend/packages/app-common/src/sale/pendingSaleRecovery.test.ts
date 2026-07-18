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
  methodId: "card-method", amountCents: 3_000, status: "TIMEOUT", mode: "INTEGRATED",
};

const envelope = (): PendingSaleRecoveryEnvelope => ({
  version: 2,
  phase: "CARD_IN_FLIGHT",
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
      status: "blocked", reason: "TERMINAL_MISMATCH", identifiers: expect.arrayContaining(["checkout-1"]),
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

  it("accepts an empty payment list only when the exact create request is ready", () => {
    const storage = new MemoryStorage();
    const ready = { ...envelope(), phase: "READY_TO_CREATE" as const, payments: [] };
    savePendingSaleRecovery(storage, ready);
    expect(loadPendingSaleRecovery(storage, "T-01")).toEqual({ status: "valid", envelope: ready });
  });

  it.each([
    ["line without request fields", (value: any) => { value.draft.lines = [{}]; }],
    ["missing due date", (value: any) => { delete value.draft.dueDate; }],
    ["invalid calendar date", (value: any) => { value.draft.dueDate = "2026-02-31"; }],
    ["invalid quantity", (value: any) => { value.draft.lines[0].quantity = null; }],
    ["non finite price", (value: any) => { value.draft.lines[0].price = "NaN"; }],
    ["invalid discount", (value: any) => { value.draft.lines[0].discount = "101"; }],
    ["invalid tax", (value: any) => { value.draft.lines[0].taxPercentage = "Infinity"; }],
    ["string quote", (value: any) => { value.quoteCents = "10000"; }],
    ["zero quote", (value: any) => { value.quoteCents = 0; value.phase = "READY_TO_CREATE"; value.payments = []; }],
    ["unsafe quote", (value: any) => { value.quoteCents = Number.MAX_SAFE_INTEGER + 1; value.phase = "READY_TO_CREATE"; value.payments = []; }],
    ["unsafe integer quantity", (value: any) => { value.draft.lines[0].quantity = Number.MAX_SAFE_INTEGER + 1; }],
    ["unsafe payment cents", (value: any) => { value.quoteCents = Number.MAX_SAFE_INTEGER + 1; value.payments[0].amountCents = Number.MAX_SAFE_INTEGER + 1; }],
    ["unsafe cash arithmetic", (value: any) => { value.phase = "READY_TO_CREATE"; value.payments = [{ id: "cash-unsafe", kind: "CASH", methodId: "cash", amountCents: 1, deliveredCents: Number.MAX_SAFE_INTEGER + 1, changeCents: Number.MAX_SAFE_INTEGER, status: "APPROVED" }]; }],
    ["overflowing allocation sum", (value: any) => { value.quoteCents = Number.MAX_SAFE_INTEGER; value.phase = "READY_TO_CREATE"; value.payments = [{ id: "transfer-1", kind: "TRANSFER", methodId: "transfer", amountCents: Number.MAX_SAFE_INTEGER - 1, reference: "A", status: "APPROVED" }, { id: "transfer-2", kind: "TRANSFER", methodId: "transfer", amountCents: 2, reference: "B", status: "APPROVED" }]; }],
    ["over allocation", (value: any) => { value.payments[0].amountCents = 10_001; }],
    ["invalid status", (value: any) => { value.payments[0].status = "UNKNOWN"; }],
    ["empty in-flight payments", (value: any) => { value.payments = []; }],
    ["approved card in flight", (value: any) => { value.payments[0].status = "APPROVED"; }],
    ["cash arithmetic", (value: any) => { value.phase = "READY_TO_CREATE"; value.payments = [{ id: "cash-1", kind: "CASH", methodId: "cash", amountCents: 3000, deliveredCents: 5000, changeCents: 1000, status: "APPROVED" }]; }],
    ["cash missing received", (value: any) => { value.phase = "READY_TO_CREATE"; value.payments = [{ id: "cash-1", kind: "CASH", methodId: "cash", amountCents: 3000, changeCents: 0, status: "APPROVED" }]; }],
    ["blank transfer reference", (value: any) => { value.phase = "READY_TO_CREATE"; value.payments = [{ id: "transfer-1", kind: "TRANSFER", methodId: "transfer", amountCents: 3000, reference: "  ", status: "APPROVED" }]; }],
    ["card identity", (value: any) => { value.payments[0].operationId = "other"; }],
    ["uncertain card ready to create", (value: any) => { value.phase = "READY_TO_CREATE"; }],
  ])("blocks parseable corruption: %s", (_name, mutate) => {
    const storage = new MemoryStorage();
    const value: any = structuredClone(envelope());
    mutate(value);
    const raw = JSON.stringify(value);
    storage.setItem(pendingSaleRecoveryKey("T-01"), raw);
    expect(loadPendingSaleRecovery(storage, "T-01")).toMatchObject({ status: "blocked", raw });
    expect(storage.getItem(pendingSaleRecoveryKey("T-01"))).toBe(raw);
  });
});
