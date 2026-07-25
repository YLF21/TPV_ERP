import { describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import {
  isReferenceConfigurablePaymentMethod,
  resolveCheckoutPaymentMethodConfiguration,
  setPaymentMethodReferenceRequirement,
  type PaymentMethodView,
} from "./paymentMethods";

const method = (name: string, overrides: Partial<PaymentMethodView> = {}): PaymentMethodView => ({
  id: name.toLowerCase(),
  companyId: "company-1",
  name,
  protectedMethod: true,
  active: true,
  requiresReference: false,
  opensCashDrawer: false,
  ...overrides,
});

describe("payment method configuration", () => {
  it("exposes external document configuration only for card and transfer", () => {
    expect(isReferenceConfigurablePaymentMethod("TARJETA")).toBe(true);
    expect(isReferenceConfigurablePaymentMethod("transferencia")).toBe(true);
    expect(isReferenceConfigurablePaymentMethod("VALE")).toBe(false);
    expect(isReferenceConfigurablePaymentMethod("EFECTIVO")).toBe(false);
  });

  it("maps active methods and reference requirements for checkout", () => {
    expect(resolveCheckoutPaymentMethodConfiguration([
      method("EFECTIVO"),
      method("TARJETA", { active: false, requiresReference: true }),
      method("TRANSFERENCIA", { requiresReference: true }),
      method("VALE", { requiresReference: true }),
    ])).toEqual({
      cashActive: true,
      cardActive: false,
      voucherActive: true,
      transferActive: true,
      cardRequiresReference: true,
      transferRequiresReference: true,
    });
  });

  it("preserves the drawer policy when changing the external document requirement", async () => {
    const request = vi.fn(async <T,>() => method("TARJETA", { requiresReference: true }) as T);
    const card = method("TARJETA", { opensCashDrawer: true });

    await setPaymentMethodReferenceRequirement(
      card,
      true,
      "token",
      request as unknown as typeof apiRequest,
    );

    expect(request).toHaveBeenCalledWith("/payment-methods/tarjeta/configuration", {
      token: "token",
      method: "PATCH",
      body: {
        requiresReference: true,
        opensCashDrawer: true,
      },
    });
  });
});
