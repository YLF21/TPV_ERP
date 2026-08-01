import { describe, expect, it } from "vitest";
import {
  cashCloseRecoveryKey,
  clearCashCloseRecovery,
  loadCashCloseRecovery,
  saveCashCloseRecovery,
  type CashCloseRecoveryFlow,
} from "./cashCloseRecovery";

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();
  get length() { return this.values.size; }
  clear() { this.values.clear(); }
  getItem(key: string) { return this.values.get(key) ?? null; }
  key(index: number) { return Array.from(this.values.keys())[index] ?? null; }
  removeItem(key: string) { this.values.delete(key); }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

const flow = (): CashCloseRecoveryFlow => ({
  closeOperationId: "11111111-1111-4111-8111-111111111111",
  reconciliationAttemptId: "22222222-2222-4222-8222-222222222222",
  phase: "ATTEMPTED",
  retainedFund: "40",
  finalWithdrawal: "10",
  comment: "Retirada final",
});

describe("cash close recovery", () => {
  it("persists only non-secret close identities and restores them by terminal", () => {
    const storage = new MemoryStorage();
    saveCashCloseRecovery(storage, "T-01", flow());

    expect(loadCashCloseRecovery(storage, "T-01")).toMatchObject({
      status: "valid",
      envelope: { terminalCode: "T-01", flow: flow() },
    });
    expect(storage.getItem(cashCloseRecoveryKey("T-01"))).not.toContain("password");

    clearCashCloseRecovery(storage, "T-01");
    expect(loadCashCloseRecovery(storage, "T-01")).toEqual({ status: "empty" });
  });

  it("blocks corrupt or cross-terminal recovery instead of discarding it", () => {
    const storage = new MemoryStorage();
    storage.setItem(cashCloseRecoveryKey("T-01"), "not-json");
    expect(loadCashCloseRecovery(storage, "T-01")).toEqual({
      status: "blocked",
      raw: "not-json",
    });

    saveCashCloseRecovery(storage, "T-02", flow());
    storage.setItem(
      cashCloseRecoveryKey("T-01"),
      storage.getItem(cashCloseRecoveryKey("T-02")) ?? "",
    );
    expect(loadCashCloseRecovery(storage, "T-01").status).toBe("blocked");
  });
});
