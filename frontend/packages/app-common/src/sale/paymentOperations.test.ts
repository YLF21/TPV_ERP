import { describe, expect, it, vi } from "vitest";
import { queryPaymentOperation, printPaymentReceipt, refundPaymentOperation, voidPaymentOperation } from "./paymentOperations";

describe("payment operation commands", () => {
  it("queries a timed out operation through query endpoint and never calls charge", async () => {
    const request = vi.fn().mockResolvedValue({ id: "op-1", status: "APPROVED" });
    await queryPaymentOperation("op-1", "token", request);
    expect(request).toHaveBeenCalledWith("/payment-terminal/operations/op-1/query", { method: "POST", token: "token" });
    expect(request.mock.calls.flat().join(" ")).not.toContain("charge");
  });

  it("creates a distinct idempotent operation when voiding an approved charge", async () => {
    const request = vi.fn().mockResolvedValue({ id: "void-op", status: "CANCELLED" });
    await voidPaymentOperation("original-charge", "token", "0000", "void-op", request);
    expect(request).toHaveBeenCalledWith("/payment-terminal/operations/original-charge/void", {
      token: "token",
      body: { operationId: "void-op", idempotencyKey: "void-op", password: "0000" }
    });
  });

  it("creates a distinct idempotent operation when refunding an approved charge", async () => {
    const request = vi.fn().mockResolvedValue({ id: "refund-op", status: "REFUNDED" });
    await refundPaymentOperation("original-charge", "token", "5.00", "0000", "refund-op", [], request);
    expect(request).toHaveBeenCalledWith("/payment-terminal/operations/original-charge/refund", {
      token: "token",
      body: { operationId: "refund-op", idempotencyKey: "refund-op", amount: "5.00", password: "0000", lines: [] }
    });
  });

  it("sanitizes the provider receipt and prints it through HardwareBridge", async () => {
    const request = vi.fn().mockResolvedValue({ status: "APPROVED", code: "OK", text: "RECIBO\u0000\nTOTAL 1.00" });
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    await printPaymentReceipt("op-1", "token", { storeName: "Tienda", terminalCode: "T1" }, { printTicket } as never, request);
    expect(printTicket).toHaveBeenCalledWith(expect.objectContaining({ documentNumber: "op-1", lines: [expect.objectContaining({ name: "RECIBO\nTOTAL 1.00" })] }));
  });
});
