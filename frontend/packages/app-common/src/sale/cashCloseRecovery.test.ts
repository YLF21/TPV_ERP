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
  const identity = { terminalCode: "002", installationId: "33333333-3333-4333-8333-333333333333",
    terminalId: "44444444-4444-4444-8444-444444444444", bindingId: "55555555-5555-4555-8555-555555555555" };
  it("isolates replacements with the same terminal code and logical UUID", () => {
    const storage = new MemoryStorage();
    saveCashCloseRecovery(storage, identity, flow());
    expect(loadCashCloseRecovery(storage, { ...identity, terminalCode: "003" }).status).toBe("valid");
    expect(loadCashCloseRecovery(storage, { ...identity, bindingId: "66666666-6666-4666-8666-666666666666" }).status).toBe("empty");
    expect(loadCashCloseRecovery(storage, { ...identity, installationId: "77777777-7777-4777-8777-777777777777" }).status).toBe("empty");
  });
  it("migrates a draft only with a verified legacy identity and does not resurrect it after clearing", () => {
    const storage = new MemoryStorage();
    saveCashCloseRecovery(storage, "OLD-CODE", flow());
    expect(loadCashCloseRecovery(storage, identity).status).toBe("empty");
    const adopted = { ...identity, legacyTerminalCode: "OLD-CODE" };
    expect(loadCashCloseRecovery(storage, adopted)).toMatchObject({ status: "valid", envelope: { version: 2, flow: flow() } });
    expect(loadCashCloseRecovery(storage, "OLD-CODE").status).toBe("empty");
    clearCashCloseRecovery(storage, adopted);
    saveCashCloseRecovery(storage, "OLD-CODE", flow());
    expect(loadCashCloseRecovery(storage, adopted).status).toBe("empty");
  });
  it("restores counted denominations and rejects malformed quantities", () => {
    const storage = new MemoryStorage();
    const counted = { ...flow(), retainedFundDenominations: [{ denomination: 20, quantity: 2 }], finalWithdrawalDenominations: [{ denomination: 10, quantity: 1 }] };
    saveCashCloseRecovery(storage, "T-01", counted);
    expect(loadCashCloseRecovery(storage, "T-01")).toMatchObject({ status: "valid", envelope: { flow: counted } });
    const envelope = JSON.parse(storage.getItem(cashCloseRecoveryKey("T-01"))!);
    envelope.flow.retainedFundDenominations[0].quantity = -1;
    storage.setItem(cashCloseRecoveryKey("T-01"), JSON.stringify(envelope));
    expect(loadCashCloseRecovery(storage, "T-01").status).toBe("blocked");
  });
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
