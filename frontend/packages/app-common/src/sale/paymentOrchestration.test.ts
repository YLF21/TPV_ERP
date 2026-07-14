import { describe, expect, it } from "vitest";
import { ApiError } from "../api/client";
import {
  addPaymentAllocation,
  applyAllocationOutcome,
  createPaymentSession,
  paymentFailureKind,
  remainingPaymentCents
} from "./paymentOrchestration";

describe("payment allocation orchestration", () => {
  it("keeps one stable idempotency key for every cash, manual card and integrated card allocation", () => {
    let session = createPaymentSession(10_00, () => "cash-key");
    session = addPaymentAllocation(session, { kind: "CASH", amountCents: 2_00 }, () => "cash-key");
    session = addPaymentAllocation(session, { kind: "MANUAL_CARD", amountCents: 3_00, reference: "M-1" }, () => "manual-key");
    session = addPaymentAllocation(session, { kind: "INTEGRATED_CARD", amountCents: 5_00, provider: "PAYTEF" }, () => "integrated-key");

    expect(session.allocations.map(({ idempotencyKey }) => idempotencyKey)).toEqual(["cash-key", "manual-key", "integrated-key"]);
    expect(addPaymentAllocation(session, session.allocations[2], () => "replacement").allocations[2].idempotencyKey).toBe("integrated-key");
  });

  it("confirms only after approved allocations cover the full ticket", () => {
    let session = createPaymentSession(10_00, () => "session");
    session = addPaymentAllocation(session, { kind: "CASH", amountCents: 2_00 }, () => "cash");
    session = applyAllocationOutcome(session, "cash", { status: "APPROVED" });
    session = addPaymentAllocation(session, { kind: "INTEGRATED_CARD", amountCents: 8_00, provider: "REDSYS_TPV_PC" }, () => "card");
    expect(session.status).toBe("COLLECTING");
    expect(remainingPaymentCents(session)).toBe(8_00);
    session = applyAllocationOutcome(session, "card", { status: "APPROVED", operationId: "card" });
    expect(session.status).toBe("COVERED");
    expect(remainingPaymentCents(session)).toBe(0);
  });

  it("preserves an earlier approved card when a later card is declined and permits another allocation", () => {
    let session = createPaymentSession(12_00, () => "session");
    session = addPaymentAllocation(session, { kind: "INTEGRATED_CARD", amountCents: 5_00, provider: "PAYCOMET" }, () => "first");
    session = applyAllocationOutcome(session, "first", { status: "APPROVED", operationId: "op-1" });
    session = addPaymentAllocation(session, { kind: "INTEGRATED_CARD", amountCents: 7_00, provider: "GLOBAL_PAYMENTS" }, () => "second");
    session = applyAllocationOutcome(session, "second", { status: "DECLINED", operationId: "op-2", message: "Denegada" });
    expect(session.allocations[0]).toMatchObject({ status: "APPROVED", operationId: "op-1" });
    expect(session.allocations[1]).toMatchObject({ status: "DECLINED", operationId: "op-2" });
    expect(remainingPaymentCents(session)).toBe(7_00);
    expect(addPaymentAllocation(session, { kind: "MANUAL_CARD", amountCents: 7_00 }, () => "third").allocations).toHaveLength(3);
  });
});

describe("payment failure classification", () => {
  it("does not label HTTP Problem Details as an uncertain financial result", () => {
    expect(paymentFailureKind(new ApiError("forbidden", 403, { type: "about:blank", detail: "forbidden" }))).toBe("HTTP");
    expect(paymentFailureKind(new TypeError("Failed to fetch"))).toBe("UNCERTAIN");
  });
});
