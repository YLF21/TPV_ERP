import { describe, expect, it } from "vitest";
import { authenticate, authenticateRemote, canAccessApp, hasPermission } from "./auth";
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

  it("allows users with APP_GESTION_ACCESS into APP GESTION", () => {
    expect(canAccessApp(["APP_GESTION_ACCESS"], "gestion")).toBe(true);
  });

  it("does not use module permissions as access to APP GESTION", () => {
    expect(canAccessApp(["GESTION_VENTAS"], "gestion")).toBe(false);
    expect(canAccessApp(["GESTION_PRODUCTO"], "gestion")).toBe(false);
    expect(canAccessApp(["GESTION_ALMACEN"], "gestion")).toBe(false);
  });

  it("keeps ADMIN as implicit access to APP GESTION", () => {
    expect(canAccessApp(["ADMIN"], "gestion")).toBe(true);
  });

  it("allows warehouse operators into APP PDA without granting APP GESTION", () => {
    expect(canAccessApp(["GESTION_ALMACEN"], "pda")).toBe(true);
    expect(canAccessApp(["STOCK_READ"], "pda")).toBe(true);
    expect(canAccessApp(["STOCK_ADJUST"], "pda")).toBe(true);
    expect(canAccessApp(["STOCK_TRANSFER"], "pda")).toBe(true);
    expect(canAccessApp(["PRODUCTS_READ"], "pda")).toBe(true);
    expect(canAccessApp(["GESTION_ALMACEN"], "gestion")).toBe(false);
  });

  it("rejects sales-only and management-shell-only users from APP PDA", () => {
    expect(canAccessApp(["VENTA"], "pda")).toBe(false);
    expect(canAccessApp(["APP_GESTION_ACCESS"], "pda")).toBe(false);
  });

  it("does not grant internal module permissions with APP_GESTION_ACCESS", () => {
    expect(hasPermission({
      username: "resumen",
      displayName: "RESUMEN",
      permissions: ["APP_GESTION_ACCESS"]
    }, "GESTION_PRODUCTO")).toBe(false);
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

  it("authenticates a warehouse-only remote user into APP PDA", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ "content-type": "application/json" }),
      json: async () => ({
        accessToken: "pda-token",
        userId: "pda-user-1",
        userName: "ALMACEN01",
        role: "PDA",
        permissions: ["GESTION_ALMACEN", "STOCK_READ", "STOCK_ADJUST", "STOCK_TRANSFER"]
      })
    }));

    const session = await authenticateRemote("almacen01", "0000", "pda", {
      storeName: "DEMO",
      terminalCode: "PDA-01",
      terminalId: "terminal-1",
      terminalCredential: "credential"
    });

    expect(session.userId).toBe("pda-user-1");
    expect(session.role).toBe("PDA");
    expect(session.permissions).toEqual(expect.arrayContaining([
      "GESTION_ALMACEN",
      "STOCK_READ",
      "STOCK_ADJUST",
      "STOCK_TRANSFER"
    ]));
  });
});
