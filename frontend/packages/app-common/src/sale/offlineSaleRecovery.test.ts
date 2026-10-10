// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { isOfflineApplicationClosePrepared, prepareOfflineApplicationClose, registerOfflineClosePreparation } from "./offlineClosePreparation";
import { offlineSaleIdentity, readOfflineRecoveryStorage, restoreOfflineRecoveryStorage, saveOfflineSaleRecovery, retireOfflineSaleRecovery, validateOfflineSaleRecovery, isOfflineSaleRecoveryTombstone, type OfflineSaleRecovery } from "./offlineSaleRecovery";

const terminal = { storeName: "Store", terminalCode: "001", installationId: "installation", storeId: "store", terminalId: "terminal", bindingId: "binding" };
const snapshot = (): OfflineSaleRecovery => ({
  schemaVersion: 1, ...offlineSaleIdentity("owner", terminal),
  ticket: { lines: [{ product: { id: "product" }, quantity: 1, temporaryPriceAuthorization: { token: "secret" } }] },
  paymentSessionId: "payment-session", allocationAttempt: '{"sessionId":"payment-session","allocationId":"attempt","input":{"kind":"CASH","amountCents":100}}',
  cashAttempt: { sessionId: "payment-session", receivedCents: 200 }, memberReservation: null,
  pendingSale: '{"checkoutId":"pending","temporaryPriceAuthorizationToken":"secret"}', cashClose: '{"flow":{"closeOperationId":"close-id"}}',
});
afterEach(() => { localStorage.clear(); sessionStorage.clear(); });

describe("offline sale recovery", () => {
  it("atomically replaces a consumed ticket with an empty durable marker when unlink fails", async () => {
    const save = vi.fn().mockResolvedValue({ ok: true });
    await retireOfflineSaleRecovery({ load: vi.fn(), clear: vi.fn().mockResolvedValue({ ok: false }), save }, "owner", terminal);
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ tombstone: true, ownerUserId: "owner", ticket: { lines: [] }, paymentSessionId: null, allocationAttempt: null }));
    expect(validateOfflineSaleRecovery(save.mock.calls[0][0], "owner", terminal).tombstone).toBe(true);
    expect(isOfflineSaleRecoveryTombstone({ ...save.mock.calls[0][0], ownerUserId: "previous-owner" }, terminal)).toBe(true);
    expect(isOfflineSaleRecoveryTombstone({ ...save.mock.calls[0][0], paymentSessionId: "pending" }, terminal)).toBe(false);
    expect(isOfflineSaleRecoveryTombstone(save.mock.calls[0][0], { ...terminal, bindingId: "other" })).toBe(false);
  });
  it("reports failure if neither deletion nor the empty marker can be persisted", async () => {
    await expect(retireOfflineSaleRecovery({ load: vi.fn(), clear: vi.fn().mockRejectedValue(new Error("disk")), save: vi.fn().mockResolvedValue({ ok: false }) }, "owner", terminal)).rejects.toThrow("SAVE_FAILED");
  });
  it("rejects another owner, terminal binding or installation before restoring any data", () => {
    expect(() => validateOfflineSaleRecovery(snapshot(), "other-owner", terminal)).toThrow("MISMATCH");
    expect(() => validateOfflineSaleRecovery(snapshot(), "owner", { ...terminal, bindingId: "replacement" })).toThrow("MISMATCH");
    expect(() => validateOfflineSaleRecovery(snapshot(), "owner", { ...terminal, installationId: "other" })).toThrow("MISMATCH");
    expect(() => validateOfflineSaleRecovery(snapshot(), "owner", terminal)).not.toThrow();
    expect(() => validateOfflineSaleRecovery({ ...snapshot(), schemaVersion: 2 }, "owner", terminal)).toThrow();
  });
  it("restores payment and economic attempts into a fresh browser origin", () => {
    restoreOfflineRecoveryStorage(snapshot(), terminal);
    const state = readOfflineRecoveryStorage(terminal);
    expect(state.paymentSessionId).toBe("payment-session");
    expect(JSON.parse(state.allocationAttempt!).allocationId).toBe("attempt");
    expect(JSON.parse(state.cashClose!).flow.closeOperationId).toBe("close-id");
    expect(JSON.parse(state.pendingSale!).checkoutId).toBe("pending");
  });
  it("saves only recovery data, removing grants even inside legacy JSON records", async () => {
    const save = vi.fn().mockResolvedValue({ ok: true });
    await saveOfflineSaleRecovery({ load: vi.fn(), clear: vi.fn(), save }, snapshot());
    const serialized = JSON.stringify(save.mock.calls[0][0]);
    expect(serialized).not.toContain("secret");
    expect(serialized).not.toContain("temporaryPriceAuthorization");
    expect(serialized).toContain("attempt");
  });
  it("does not permit native unload when durable storage fails", async () => {
    const dispose = registerOfflineClosePreparation(() => saveOfflineSaleRecovery({ load: vi.fn(), clear: vi.fn(), save: vi.fn().mockResolvedValue({ ok: false }) }, snapshot()));
    try {
      await expect(prepareOfflineApplicationClose()).rejects.toThrow("SAVE_FAILED");
      expect(isOfflineApplicationClosePrepared()).toBe(false);
    } finally { dispose(); }
  });
  it("waits for durable saving and coalesces concurrent close requests", async () => {
    let finish!: () => void;
    const save = vi.fn(() => new Promise<void>(resolve => { finish = resolve; }));
    const dispose = registerOfflineClosePreparation(save);
    try {
      const first = prepareOfflineApplicationClose();
      const second = prepareOfflineApplicationClose();
      expect(first).toBe(second);
      expect(isOfflineApplicationClosePrepared()).toBe(false);
      finish(); await first;
      expect(isOfflineApplicationClosePrepared()).toBe(true);
      expect(save).toHaveBeenCalledTimes(1);
    } finally { dispose(); }
  });
});
