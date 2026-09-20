import { describe, expect, it, vi } from "vitest";
import { defaultHardwareConfig } from "../hardware/hardware";
import { printCashEntryReceipt, printCashWithdrawalReceipt, registerCashEntry } from "./cashWithdrawal";

const receiptTypes = [
  { print: printCashWithdrawalReceipt, path: "withdrawals", code: "RETIRADA_CAJA" },
  { print: printCashEntryReceipt, path: "entries", code: "ENTRADA_CAJA" },
] as const;

describe("cash movement receipt printing", () => {
  it("registers a manual cash entry with operation credentials", async () => {
    const request = vi.fn().mockResolvedValue({ id: "entry-1", type: "ENTRADA" });

    await registerCashEntry({
      terminalId: "terminal-1",
      amount: 15,
      comment: " Aporte de cambio ",
      denominations: [],
      authorizerUsername: " manager ",
      authorizerPassword: "secret",
    }, "token", request);

    expect(request).toHaveBeenCalledWith("/cash/movements/entry", {
      token: "token",
      method: "POST",
      body: {
        terminalId: "terminal-1",
        amount: 15,
        comment: "Aporte de cambio",
        denominations: [],
        authorizerUsername: "manager",
        authorizerPassword: "secret",
      },
    });
  });

  it.each(receiptTypes)("prints $code from its Jasper endpoint without rebuilding HTML/text", async ({ print, path, code }) => {
    const request = vi.fn().mockResolvedValue({
      renderedPdf: { contentType: "application/pdf", base64: "JVBERi0=" },
      ticketRenderedImage: { contentType: "image/png", base64: "iVBORw0=" },
      fileName: `${code}-001.pdf`,
    });
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig),
      printTicket,
    };

    const outcome = await print(
      "movement-123456789",
      "token",
      { storeName: "Tienda", terminalCode: "01" },
      "es",
      hardware as never,
      request,
    );

    expect(outcome).toEqual({ status: "PRINTED" });
    expect(request).toHaveBeenCalledWith(
      `/cash/receipts/${path}/movement-123456789/print-document`,
      { token: "token" },
    );
    const printed = printTicket.mock.calls[0][0];
    expect(printed.requireRenderedDocument).toBe(true);
    expect(printed.documentNumber).toBe(`${code}-001.pdf`);
    expect(printed.renderedPdf).toEqual({ contentType: "application/pdf", base64: "JVBERi0=" });
    expect(printed.documentRaster).toBe("data:image/png;base64,iVBORw0=");
  });

  it.each(receiptTypes)("returns a failed $code outcome instead of throwing", async ({ print }) => {
    const outcome = await print(
      "movement-1",
      "token",
      { storeName: "Tienda", terminalCode: "01" },
      "es",
      {} as never,
      vi.fn().mockRejectedValue(new Error("offline")),
    );

    expect(outcome).toEqual({ status: "FAILED", technicalMessage: "offline" });
  });

  it.each(receiptTypes)("does not print $code without its Jasper raster", async ({ print }) => {
    const printTicket = vi.fn();
    const hardware = { getHardwareConfig: vi.fn(), printTicket };
    const request = vi.fn().mockResolvedValue({
      renderedPdf: { contentType: "application/pdf", base64: "JVBERi0=" },
      ticketRenderedImage: null,
    });

    const outcome = await print(
      "movement-1", "token", { storeName: "Tienda", terminalCode: "01" },
      "es", hardware as never, request,
    );

    expect(outcome.status).toBe("FAILED");
    expect(printTicket).not.toHaveBeenCalled();
  });
});
