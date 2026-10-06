import { describe, expect, it, vi } from "vitest";
import { loadTerminalIdentity, loadTerminalLoginContext, resolveTerminalIdentity } from "./terminalIdentity";

const validIdentity = {
  storeName: "TIENDA REAL",
  terminalCode: "SERVIDOR",
  terminalId: "terminal-real",
  terminalCredential: "secret"
};

describe("terminal identity", () => {
  it("accepts a complete protected identity", () => {
    expect(resolveTerminalIdentity({ ok: true, identity: validIdentity })).toEqual(validIdentity);
  });

  it("rejects missing, failed or incomplete identities", () => {
    expect(resolveTerminalIdentity({ ok: true, identity: null })).toBeNull();
    expect(resolveTerminalIdentity({ ok: false })).toBeNull();
    expect(resolveTerminalIdentity({
      ok: true,
      identity: { ...validIdentity, terminalCredential: undefined }
    })).toBeNull();
  });

  it("uses the protected identity instead of the browser fallback", async () => {
    const fallback = { ...validIdentity, terminalId: "development-terminal" };
    const bridge = { load: vi.fn().mockResolvedValue({ ok: true, identity: validIdentity }) };

    await expect(loadTerminalIdentity(bridge, fallback)).resolves.toEqual(validIdentity);
  });

  it("fails closed when protected storage cannot be read", async () => {
    const bridge = { load: vi.fn().mockRejectedValue(new Error("DPAPI unavailable")) };

    await expect(loadTerminalIdentity(bridge, validIdentity)).resolves.toBeNull();
  });

  it("keeps offline presentation separate from identity and strips any cached credentials", async () => {
    const bridge = { load: vi.fn().mockResolvedValue({ ok: true, identity: null,
      connectionUnavailable: true, displayContext: { ...validIdentity, companyName: "Empresa Real" } }) };
    const result = await loadTerminalLoginContext(bridge, validIdentity);
    expect(result.identity).toBeNull();
    expect(result.offlineContext).toEqual({ storeName: "TIENDA REAL", terminalCode: "SERVIDOR",
      companyName: "Empresa Real", terminalName: undefined });
    expect(result.offlineContext).not.toHaveProperty("terminalCredential");
    expect(result.offlineContext).not.toHaveProperty("terminalId");
    await expect(loadTerminalIdentity(bridge, validIdentity)).resolves.toBeNull();
  });

  it.each([false, undefined])("does not display cached metadata without a confirmed connection failure (%s)", async (connectionUnavailable) => {
    const bridge = { load: vi.fn().mockResolvedValue({ ok: true, identity: null,
      connectionUnavailable, displayContext: validIdentity }) };
    await expect(loadTerminalLoginContext(bridge, validIdentity)).resolves.toEqual({ identity: null, offlineContext: null });
  });
});
