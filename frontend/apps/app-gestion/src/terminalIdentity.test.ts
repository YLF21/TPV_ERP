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
      terminalId: "06d2ce45-8ead-349d-b844-4ecdead5e1ec"
    });
    expect(resolveGestionTerminalIdentity({ ok: true, identity: null }, true))
      .toEqual(devTerminalContext);
    expect(resolveGestionTerminalIdentity({ ok: true, identity: null }, false)).toBeNull();
    expect(resolveGestionTerminalIdentity({ ok: false }, false)).toBeNull();
  });
});
