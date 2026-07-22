import { describe, expect, it } from "vitest";
import type { UserSession } from "@tpverp/app-common";
import { canOpenGestionModule, visibleGestionModules } from "./gestionAccess";

function session(permissions: UserSession["permissions"]): UserSession {
  return { username: "user", displayName: "USER", permissions };
}

describe("APP GESTION module access", () => {
  it("does not turn app access into module access", () => {
    expect(visibleGestionModules(session(["APP_GESTION_ACCESS"]))).toEqual([]);
  });

  it("opens stock to product or warehouse managers", () => {
    expect(canOpenGestionModule(
      session(["APP_GESTION_ACCESS", "GESTION_PRODUCTO"]),
      "gestion.stock"
    )).toBe(true);
    expect(canOpenGestionModule(
      session(["APP_GESTION_ACCESS", "GESTION_ALMACEN"]),
      "gestion.stock"
    )).toBe(true);
    expect(canOpenGestionModule(
      session(["APP_GESTION_ACCESS", "WAREHOUSES_MANAGE"]),
      "gestion.stock"
    )).toBe(true);
  });

  it("does not use a module permission as app access", () => {
    expect(canOpenGestionModule(session(["GESTION_PRODUCTO"]), "gestion.products")).toBe(false);
  });

  it("keeps role administration separate from user management", () => {
    expect(canOpenGestionModule(
      session(["APP_GESTION_ACCESS", "ROLES_MANAGE"]),
      "gestion.roles"
    )).toBe(true);
    expect(canOpenGestionModule(
      session(["APP_GESTION_ACCESS", "ROLES_MANAGE"]),
      "gestion.users"
    )).toBe(false);
    expect(canOpenGestionModule(
      session(["APP_GESTION_ACCESS", "GESTION_USUARIO"]),
      "gestion.users"
    )).toBe(true);
  });

  it("allows ADMIN into every module", () => {
    expect(visibleGestionModules(session(["ADMIN"]))).toHaveLength(9);
  });

  it("opens VeriFactu only with APP GESTION access and fiscal read permission", () => {
    expect(canOpenGestionModule(
      session(["APP_GESTION_ACCESS", "VERIFACTU_READ"]),
      "gestion.verifactu"
    )).toBe(true);
    expect(canOpenGestionModule(
      session(["VERIFACTU_READ"]),
      "gestion.verifactu"
    )).toBe(false);
    expect(canOpenGestionModule(
      session(["APP_GESTION_ACCESS", "VERIFACTU_MANAGE"]),
      "gestion.verifactu"
    )).toBe(false);
    expect(canOpenGestionModule(
      session(["APP_GESTION_ACCESS", "VERIFACTU_CORRECT"]),
      "gestion.verifactu"
    )).toBe(false);
  });

  it("opens control alerts to readers and managers only through APP GESTION", () => {
    expect(canOpenGestionModule(
      session(["APP_GESTION_ACCESS", "CONTROL_ALERTS_READ"]),
      "gestion.controlAlerts"
    )).toBe(true);
    expect(canOpenGestionModule(
      session(["APP_GESTION_ACCESS", "CONTROL_ALERTS_MANAGE"]),
      "gestion.controlAlerts"
    )).toBe(true);
    expect(canOpenGestionModule(
      session(["CONTROL_ALERTS_MANAGE"]),
      "gestion.controlAlerts"
    )).toBe(false);
  });
});
