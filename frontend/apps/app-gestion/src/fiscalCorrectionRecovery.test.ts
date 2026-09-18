// @vitest-environment jsdom
import { webcrypto } from "node:crypto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { clearFiscalCorrectionAttempt, loadFiscalCorrectionAttempt, reserveFiscalCorrectionAttempt } from "./fiscalCorrectionRecovery";

const draft = { reason: "Motivo privado", recipientTaxId: "B12345674", recipientName: "Cliente privado" };
beforeEach(() => { vi.stubGlobal("crypto", webcrypto); sessionStorage.clear(); });
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe("fiscal correction recovery", () => {
  it("aísla por usuario y registro sin persistir identidad ni datos personales", async () => {
    const first = await reserveFiscalCorrectionAttempt("operador-1", "record-1", draft);
    const second = await reserveFiscalCorrectionAttempt("operador-2", "record-1", draft);
    const third = await reserveFiscalCorrectionAttempt("operador-1", "record-2", draft);
    expect(new Set([first.idempotencyKey, second.idempotencyKey, third.idempotencyKey]).size).toBe(3);
    for (let i = 0; i < sessionStorage.length; i++) {
      const key = sessionStorage.key(i)!;
      const value = key + sessionStorage.getItem(key);
      for (const privateValue of ["operador-", "record-", draft.reason, draft.recipientTaxId, draft.recipientName]) {
        expect(value).not.toContain(privateValue);
      }
    }
  });

  it("reutiliza datos normalizados y solo libera la clave tras confirmar", async () => {
    const first = await reserveFiscalCorrectionAttempt("user", "record", draft);
    const retry = await reserveFiscalCorrectionAttempt("user", "record", { ...draft, reason: ` ${draft.reason} ` });
    expect(retry.idempotencyKey).toBe(first.idempotencyKey);
    expect(retry.newAttempt).toBe(false);
    await expect(reserveFiscalCorrectionAttempt("user", "record", { ...draft, reason: "Otro" })).rejects.toThrow("fiscal_correction_pending_payload");
    clearFiscalCorrectionAttempt(first);
    const next = await reserveFiscalCorrectionAttempt("user", "record", { ...draft, reason: "Otro" });
    expect(next.idempotencyKey).not.toBe(first.idempotencyKey);
    clearFiscalCorrectionAttempt(first);
    expect((await loadFiscalCorrectionAttempt("user", "record"))?.idempotencyKey).toBe(next.idempotencyKey);
  });

  it("no sobrescribe una recuperación corrupta", async () => {
    const first = await reserveFiscalCorrectionAttempt("user", "record", draft);
    sessionStorage.setItem(first.storageKey, "corrupt recovery");
    await expect(reserveFiscalCorrectionAttempt("user", "record", draft)).rejects.toThrow();
    expect(sessionStorage.getItem(first.storageKey)).toBe("corrupt recovery");
  });

  it("rechaza el intento si no puede conservarse antes del envío", async () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("storage disabled"); });
    await expect(reserveFiscalCorrectionAttempt("user", "record", draft)).rejects.toThrow("storage disabled");
    expect(sessionStorage.length).toBe(0);
  });
});
