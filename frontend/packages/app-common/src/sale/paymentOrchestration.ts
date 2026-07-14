import { ApiError } from "../api/client";

export type AllocationKind = "CASH" | "MANUAL_CARD" | "INTEGRATED_CARD";
export type AllocationStatus = "READY" | "PENDING" | "APPROVED" | "DECLINED" | "TIMEOUT" | "ERROR" | "CANCELLED";

export type PaymentAllocation = {
  kind: AllocationKind;
  amountCents: number;
  idempotencyKey: string;
  status: AllocationStatus;
  provider?: string;
  reference?: string;
  authorization?: string;
  operationId?: string;
  message?: string;
};

export type PaymentSession = {
  id: string;
  totalCents: number;
  status: "COLLECTING" | "COVERED" | "COMPENSATION_REQUIRED";
  allocations: PaymentAllocation[];
};

type NewAllocation = Omit<PaymentAllocation, "idempotencyKey" | "status"> & Partial<Pick<PaymentAllocation, "idempotencyKey" | "status">>;

export function createPaymentSession(totalCents: number, generateId: () => string): PaymentSession {
  if (!Number.isInteger(totalCents) || totalCents <= 0) throw new Error("invalid_payment_total");
  return { id: generateId(), totalCents, status: "COLLECTING", allocations: [] };
}

export function addPaymentAllocation(session: PaymentSession, input: NewAllocation, generateId: () => string): PaymentSession {
  if (!Number.isInteger(input.amountCents) || input.amountCents <= 0) throw new Error("invalid_allocation_amount");
  const key = input.idempotencyKey || generateId();
  const existing = session.allocations.findIndex((allocation) => allocation.idempotencyKey === key);
  const allocation: PaymentAllocation = { ...input, idempotencyKey: key, status: input.status ?? "READY" };
  const allocations = existing < 0
    ? [...session.allocations, allocation]
    : session.allocations.map((current, index) => index === existing ? { ...current, ...allocation, idempotencyKey: current.idempotencyKey } : current);
  return deriveSession(session, allocations);
}

export function applyAllocationOutcome(
  session: PaymentSession,
  idempotencyKey: string,
  outcome: Pick<PaymentAllocation, "status"> & Partial<Pick<PaymentAllocation, "operationId" | "reference" | "authorization" | "message">>
): PaymentSession {
  const allocations = session.allocations.map((allocation) => allocation.idempotencyKey === idempotencyKey
    ? { ...allocation, ...outcome }
    : allocation);
  return deriveSession(session, allocations);
}

export function remainingPaymentCents(session: PaymentSession) {
  const approved = session.allocations
    .filter((allocation) => allocation.status === "APPROVED")
    .reduce((sum, allocation) => sum + allocation.amountCents, 0);
  return Math.max(0, session.totalCents - approved);
}

export function paymentFailureKind(error: unknown): "HTTP" | "UNCERTAIN" {
  return error instanceof ApiError ? "HTTP" : "UNCERTAIN";
}

function deriveSession(session: PaymentSession, allocations: PaymentAllocation[]): PaymentSession {
  const next = { ...session, allocations };
  return { ...next, status: remainingPaymentCents(next) === 0 ? "COVERED" : "COLLECTING" };
}
