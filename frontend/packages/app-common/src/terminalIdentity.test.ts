import { describe, expect, it, vi } from "vitest";
import { loadTerminalIdentity, resolveTerminalIdentity } from "./terminalIdentity";

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
});
