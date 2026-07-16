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

  it("blocks product-only users from APP VENTA", () => {
    expect(() => authenticate("producto", "producto", "venta")).toThrow("no_access");
  });

  it("allows gestion users into APP GESTION", () => {
    expect(canAccessApp(["GESTION_VENTAS"], "gestion")).toBe(true);
  });

  it("assigns customer receivables permissions to the ADMIN profile", () => {
    const session = authenticate("admin", "admin", "gestion");

    expect(session.permissions).toEqual(expect.arrayContaining([
      "CUSTOMER_RECEIVABLES_READ",
      "CUSTOMER_RECEIVABLES_CREATE",
      "CUSTOMER_RECEIVABLES_PAY"
    ]));
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
        permissions: ["VENTA", "STOCK_READ", "WAREHOUSE_INPUTS_WRITE"]
      })
    }));

    const session = await authenticateRemote("almacen", "0000", "venta", {
      storeName: "DEMO",
      terminalCode: "SERVIDOR",
      terminalId: "terminal-1",
      terminalCredential: "credential"
    });

    expect(session.permissions).toContain("STOCK_READ");
    expect(session.permissions).toContain("WAREHOUSE_INPUTS_WRITE");
    expect(session.userId).toBe("user-1");
  });
});
