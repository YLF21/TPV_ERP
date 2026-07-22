import { describe, expect, it } from "vitest";
import { resolveGestionTerminalIdentity } from "./terminalIdentity";

describe("resolveGestionTerminalIdentity", () => {
  it("uses the protected identity when Electron already has one", () => {
    const identity = {
      storeName: "TIENDA REAL",
      terminalCode: "SERVIDOR",
      terminalId: "terminal-real",
      terminalCredential: "secret"
    };

    expect(resolveGestionTerminalIdentity({ ok: true, identity })).toEqual(identity);
  });

  it("requires server terminal setup when Electron has no protected identity", () => {
    expect(resolveGestionTerminalIdentity({ ok: true, identity: null })).toBeNull();
    expect(resolveGestionTerminalIdentity({ ok: false })).toBeNull();
  });
});
