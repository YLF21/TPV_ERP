import { describe, expect, it, vi } from "vitest";
import type { HardwareBridge } from "../hardware/hardware";
import { issuedVoucherPrintRequest, outputIssuedVoucher } from "./voucherPrinting";

const voucher = {
  code: "VABC123",
  amount: "25.50",
  issuedAt: "2026-08-04T12:00:00Z",
  originTicketNumber: "R-1",
};
const terminal = { storeName: "Tienda", terminalCode: "T1" };

describe("voucher printing", () => {
  it("builds the separate voucher note with its exact code and origin", () => {
    expect(issuedVoucherPrintRequest(voucher, terminal, "es")).toEqual(
      expect.objectContaining({
        documentNumber: "VABC123",
        total: 25.5,
        lines: [expect.objectContaining({
          name: expect.stringContaining("R-1"),
          total: 25.5,
        })],
      }),
    );
  });

  it("returns a retryable failure without issuing another voucher", async () => {
    const hardware = {
      printTicket: vi.fn().mockResolvedValue({
        ok: false,
        code: "PRINT_FAILED",
        message: "printer offline",
      }),
    } as unknown as HardwareBridge;

    await expect(outputIssuedVoucher(voucher, terminal, "es", hardware))
      .resolves.toEqual({ status: "FAILED", technicalMessage: "printer offline" });
    expect(hardware.printTicket).toHaveBeenCalledOnce();
  });
});
