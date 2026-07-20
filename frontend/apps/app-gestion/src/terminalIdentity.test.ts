import { describe, expect, it } from "vitest";
import { devTerminalContext } from "@tpverp/app-common";
import { resolveGestionTerminalIdentity } from "./terminalIdentity";

describe("resolveGestionTerminalIdentity", () => {
  it("uses the protected identity when Electron already has one", () => {
    const identity = {
      storeName: "TIENDA REAL",
      terminalCode: "SERVIDOR",
      terminalId: "terminal-real",
      terminalCredential: "secret"
    };

    expect(resolveGestionTerminalIdentity({ ok: true, identity }, true)).toEqual(identity);
  });

  it("uses deterministic demo data only in development", () => {
    expect(devTerminalContext).toMatchObject({
      storeName: "TIENDA DEMO",
      terminalCode: "SERVIDOR",
      terminalId: "7f931e55-370a-4516-8b48-1f67d1a07b8f"
    });
    expect(resolveGestionTerminalIdentity({ ok: true, identity: null }, true))
      .toEqual(devTerminalContext);
    expect(resolveGestionTerminalIdentity({ ok: true, identity: null }, false)).toBeNull();
    expect(resolveGestionTerminalIdentity({ ok: false }, false)).toBeNull();
  });
});
