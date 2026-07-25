import { apiRequest } from "../api/client";

export type PaymentMethodView = {
  id: string;
  companyId: string;
  name: string;
  protectedMethod: boolean;
  active: boolean;
  requiresReference: boolean;
  opensCashDrawer: boolean;
};

export type CheckoutPaymentMethodConfiguration = {
  cashActive: boolean;
  cardActive: boolean;
  voucherActive: boolean;
  transferActive: boolean;
  cardRequiresReference: boolean;
  transferRequiresReference: boolean;
};

export const defaultCheckoutPaymentMethodConfiguration: CheckoutPaymentMethodConfiguration = {
  cashActive: true,
  cardActive: true,
  voucherActive: true,
  transferActive: true,
  cardRequiresReference: false,
  transferRequiresReference: false,
};

export const managedCheckoutPaymentMethodNames = [
  "EFECTIVO",
  "TARJETA",
  "TRANSFERENCIA",
  "VALE",
] as const;

export const referenceConfigurablePaymentMethodNames = [
  "TARJETA",
  "TRANSFERENCIA",
] as const;

export function isReferenceConfigurablePaymentMethod(name: string) {
  return referenceConfigurablePaymentMethodNames.includes(
    name.trim().toUpperCase() as (typeof referenceConfigurablePaymentMethodNames)[number],
  );
}

export function resolveCheckoutPaymentMethodConfiguration(
  methods: PaymentMethodView[],
): CheckoutPaymentMethodConfiguration {
  const byName = new Map(methods.map((method) => [method.name.trim().toUpperCase(), method]));
  const cash = byName.get("EFECTIVO");
  const card = byName.get("TARJETA");
  const voucher = byName.get("VALE");
  const transfer = byName.get("TRANSFERENCIA");
  return {
    cashActive: cash?.active ?? false,
    cardActive: card?.active ?? false,
    voucherActive: voucher?.active ?? false,
    transferActive: transfer?.active ?? false,
    cardRequiresReference: card?.requiresReference ?? false,
    transferRequiresReference: transfer?.requiresReference ?? false,
  };
}

export function loadPaymentMethods(token?: string, request = apiRequest) {
  return request<PaymentMethodView[]>("/payment-methods", { token });
}

export function setPaymentMethodActive(
  method: PaymentMethodView,
  active: boolean,
  token?: string,
  request = apiRequest,
) {
  return request<PaymentMethodView>(`/payment-methods/${method.id}/active`, {
    token,
    method: "PATCH",
    body: { active },
  });
}

export function setPaymentMethodReferenceRequirement(
  method: PaymentMethodView,
  requiresReference: boolean,
  token?: string,
  request = apiRequest,
) {
  return request<PaymentMethodView>(`/payment-methods/${method.id}/configuration`, {
    token,
    method: "PATCH",
    body: {
      requiresReference,
      opensCashDrawer: method.opensCashDrawer,
    },
  });
}
