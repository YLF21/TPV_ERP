import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  commercialChannelColumnDefinitions,
  loadMemberLoyalty,
  memberCardDeliveryColumnDefinitions,
  memberCategoryColumnDefinitions,
  memberLoyaltyAdjustmentBody,
  memberLoyaltyPermissions,
  memberLoyaltyTableKeys,
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

  it("defines the four loyalty preference namespaces and their data columns", () => {
    expect(memberLoyaltyTableKeys).toEqual({
      movements: "party.members.movements",
      categories: "party.memberCategories",
      channels: "party.commercialChannels",
      deliveries: "party.memberCardDeliveries"
    });
    expect(memberMovementColumnDefinitions.map((column) => column.key)).toEqual(["date", "movement", "amount", "reason"]);
    expect(memberCategoryColumnDefinitions.map((column) => column.key)).toEqual(["code", "name", "minPoints", "discount", "status"]);
    expect(commercialChannelColumnDefinitions.map((column) => column.key)).toEqual(["code", "name", "status"]);
    expect(memberCardDeliveryColumnDefinitions.map((column) => column.key)).toEqual(["email", "status", "date"]);
  });
});
