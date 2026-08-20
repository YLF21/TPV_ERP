import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  loadMemberLoyalty,
  memberCardDeliveryColumnDefinitions,
  memberLoyaltyAdjustmentBody,
  memberLoyaltyPermissions,
  memberLoyaltyTableKeys,
  memberMovementPresentation,
  memberMovementColumnDefinitions,
  MemberLoyaltyPanel
} from "./MemberLoyaltyPanel";

const session = { accessToken: "token", refreshToken: "refresh", userId: "u", username: "user", displayName: "User", roles: [], permissions: ["CUSTOMERS_READ", "CUSTOMERS_WRITE"] } as any;

describe("MemberLoyaltyPanel", () => {
  it("loads member detail, movements and categories from the existing contracts", async () => {
    const request = vi.fn().mockResolvedValueOnce({ id: "m1" }).mockResolvedValueOnce([]).mockResolvedValueOnce([]) as any;
    await loadMemberLoyalty("m1", "token", request);
    expect(request.mock.calls.map((call: any[]) => call[0])).toEqual([
      "/members/m1", "/members/m1/movements", "/member-categories"
    ]);
  });

  it("requires a non-zero adjustment and a reason", () => {
    expect(memberLoyaltyAdjustmentBody("12", " Correccion ", "points")).toEqual({ points: 12, reason: "Correccion" });
    expect(memberLoyaltyAdjustmentBody("2.50", "Abono", "balance")).toEqual({ amount: 2.5, reason: "Abono" });
    expect(() => memberLoyaltyAdjustmentBody("1", " ", "points")).toThrow("party.members.reasonRequired");
    expect(() => memberLoyaltyAdjustmentBody("1.5", "x", "points")).toThrow("party.members.adjustmentInvalid");
  });

  it("allows customer managers to maintain loyalty categories", () => {
    expect(memberLoyaltyPermissions(session)).toEqual({ canWrite: true, canSetCategory: true });
    expect(memberLoyaltyPermissions({ ...session, permissions: ["ADMIN"] })).toEqual({ canWrite: true, canSetCategory: true });
    expect(memberLoyaltyPermissions({
      ...session,
      permissions: ["GESTION_CLIENTE_PROVEEDOR"]
    })).toEqual({ canWrite: true, canSetCategory: true });
  });

  it("renders an accessible loyalty region while data loads", () => {
    const request = vi.fn(() => new Promise(() => undefined)) as any;
    const html = renderToStaticMarkup(<MemberLoyaltyPanel memberId="m1" session={session} t={(key) => key} request={request} />);
    expect(html).toContain('aria-label="party.members.loyaltyTitle"');
    expect(html).toContain('role="tablist"');
  });

  it("defines the member-specific loyalty preference namespaces and their data columns", () => {
    expect(memberLoyaltyTableKeys).toEqual({
      movements: "party.members.movements",
      deliveries: "party.memberCardDeliveries"
    });
    expect(memberMovementColumnDefinitions.map((column) => column.key)).toEqual(["date", "movement", "amount", "reason"]);
    expect(memberCardDeliveryColumnDefinitions.map((column) => column.key)).toEqual(["email", "status", "date"]);
  });

  it("presents credits, debits, manual adjustments and category changes explicitly", () => {
    const translate = (key: string) => key.split(".").at(-1)!;
    const movement = (type: string, balanceAmount: number, pointsAmount = 0) => ({
      id: type, type, balanceAmount, pointsAmount, createdAt: "2026-08-19T20:00:00Z"
    });

    expect(memberMovementPresentation(movement("ACUMULACION_PUNTOS", 0, 2), [], translate, "es-ES"))
      .toEqual({ tone: "credit", label: "ACUMULACION_PUNTOS", amount: "+2 pt" });
    expect(memberMovementPresentation(movement("USO_SALDO", -4.85), [], translate, "es-ES"))
      .toEqual({ tone: "debit", label: "USO_SALDO", amount: "-4,85 €" });
    expect(memberMovementPresentation(movement("AJUSTE_MANUAL_SALDO", -1), [], translate, "es-ES").tone)
      .toBe("manual");
    expect(memberMovementPresentation({
      ...movement("CAMBIO_CATEGORIA", 0), previousCategoryId: "silver", newCategoryId: "gold"
    }, [
      { id: "silver", code: "PLATA", name: "Plata", minPoints: 0, discountPercent: 0, discountEnabled: true, manualOnly: false, active: true, sortOrder: 1 },
      { id: "gold", code: "ORO", name: "Oro", minPoints: 100, discountPercent: 5, discountEnabled: true, manualOnly: false, active: true, sortOrder: 2 }
    ], translate, "es-ES").amount).toBe("Plata → Oro");
  });
});
