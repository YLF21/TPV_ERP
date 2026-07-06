import { describe, expect, it } from "vitest";
import { authenticate, canAccessApp } from "./auth";

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
});
