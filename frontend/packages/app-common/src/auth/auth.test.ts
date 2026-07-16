import { describe, expect, it } from "vitest";
import { authenticate, authenticateRemote, canAccessApp } from "./auth";
import { afterEach, vi } from "vitest";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("auth", () => {
  it("allows VENTA users into APP VENTA", () => {
    expect(authenticate("venta", "venta", "venta").username).toBe("venta");
  });

  it("allows product-only users into APP VENTA", () => {
    expect(authenticate("producto", "producto", "venta").username).toBe("producto");
  });

  it("allows gestion users into APP GESTION", () => {
    expect(canAccessApp(["GESTION_VENTAS"], "gestion")).toBe(true);
  });

  it("uses the permission codes returned by the backend", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ "content-type": "application/json" }),
      json: async () => ({
        accessToken: "token",
        userId: "user-1",
        userName: "ALMACEN",
        role: "OPERADOR",
        permissions: ["VENTA", "STOCK_READ", "GESTION_ALMACEN"]
      })
    }));

    const session = await authenticateRemote("almacen", "0000", "venta", {
      storeName: "DEMO",
      terminalCode: "SERVIDOR",
      terminalId: "terminal-1",
      terminalCredential: "credential"
    });

    expect(session.permissions).toContain("STOCK_READ");
    expect(session.permissions).toContain("GESTION_ALMACEN");
    expect(session.userId).toBe("user-1");
  });
});
