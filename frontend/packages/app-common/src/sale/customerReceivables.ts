export type PendingDocumentType = "ALBARAN_VENTA" | "FACTURA_VENTA";
export type PendingPaymentStatus = "APPROVED" | "PENDING" | "SENT" | "TIMEOUT" | "DECLINED" | "ERROR" | "CANCELLED";

export type PendingSaleLine = {
  productId: string;
  quantity: number;
  code: string;
  name: string;
  rate?: string | null;
  price: string;
  discount: string;
  taxesIncluded: boolean;
  taxRegime: string;
  taxPercentage: string;
};

export type PendingSaleDraft = {
  checkoutId: string;
  warehouseId: string;
  type: PendingDocumentType;
  date: string;
  customerId: string;
  dueDate: string;
  globalDiscount: string;
  lines: PendingSaleLine[];
};

export type PendingPaymentAllocation = {
  id: string;
  kind: "CASH" | "TRANSFER" | "INTEGRATED_CARD";
  methodId: string;
  amountCents: number;
  status: PendingPaymentStatus;
  deliveredCents?: number;
  changeCents?: number;
  reference?: string;
  operationId?: string;
  mode?: "INTEGRATED";
};

export function pendingSummary(totalCents: number, payments: PendingPaymentAllocation[]) {
  const paidCents = payments
    .filter((payment) => payment.status === "APPROVED")
    .reduce((sum, payment) => sum + payment.amountCents, 0);
  return { totalCents, paidCents, pendingCents: totalCents - paidCents };
}

export function addLocalDays(now: Date, days: number) {
  const value = new Date(now.getFullYear(), now.getMonth(), now.getDate() + days);
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, "0");
  const date = String(value.getDate()).padStart(2, "0");
  return `${year}-${month}-${date}`;
}

export function centsFromInput(value: string) {
  const normalized = value.trim().replace(",", ".");
  if (!/^\d+(?:\.\d{1,2})?$/.test(normalized)) return 0;
  return Math.round(Number(normalized) * 100);
}

export function pendingAllocationCents(value: string, remainingCents: number) {
  const amountCents = centsFromInput(value);
  return amountCents > 0 && Number.isInteger(remainingCents) && amountCents <= remainingCents
    ? amountCents
    : 0;
}

export function centsAsMoney(cents: number) {
  return (cents / 100).toFixed(2);
}

export function pendingCreateBody(draft: PendingSaleDraft, payments: PendingPaymentAllocation[], quotedTotalCents: number) {
  const approved = payments.filter((payment) => payment.status === "APPROVED");
  return {
    ...draft,
    lines: draft.lines.map((line) => ({
      productoId: line.productId,
      cantidad: line.quantity,
      codigo: line.code,
      nombre: line.name,
      tarifa: line.rate ?? null,
      precioUnitario: line.price,
      descuento: line.discount,
      impuestosIncluidos: line.taxesIncluded,
      regimenImpuesto: line.taxRegime,
      porcentajeImpuesto: line.taxPercentage,
      lineType: "PRODUCT",
      promotionId: null,
      promotionVersionId: null,
      promotionalCouponId: null,
    })),
    payments: approved.map((payment, index) => ({
      kind: payment.kind === "INTEGRATED_CARD" ? "INTEGRATED_CARD" : "STANDARD",
      methodId: payment.methodId,
      amount: centsAsMoney(payment.amountCents),
      principal: index === 0,
      delivered: payment.deliveredCents === undefined ? null : centsAsMoney(payment.deliveredCents),
      change: payment.changeCents === undefined ? null : centsAsMoney(payment.changeCents),
      voucherCode: null,
      reference: payment.reference ?? null,
      requestId: payment.id,
      paymentTerminalOperationId: payment.operationId ?? null,
    })),
    quotedTotal: centsAsMoney(quotedTotalCents),
  };
}

export function pendingHasUncertainCard(payments: PendingPaymentAllocation[]) {
  return payments.some((payment) => payment.kind === "INTEGRATED_CARD"
    && ["PENDING", "SENT", "TIMEOUT"].includes(payment.status));
}

export function pendingHasCardEffect(payments: PendingPaymentAllocation[]) {
  return payments.some((payment) => payment.kind === "INTEGRATED_CARD");
}
