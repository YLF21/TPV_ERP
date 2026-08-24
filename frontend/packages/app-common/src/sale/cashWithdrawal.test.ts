import { describe, expect, it, vi } from "vitest";
import { defaultHardwareConfig } from "../hardware/hardware";
import { printCashWithdrawalReceipt, registerCashEntry } from "./cashWithdrawal";

describe("cash withdrawal receipt printing", () => {
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

  it("prints the persisted Jasper ticket raster without rebuilding HTML/text", async () => {
    const request = vi.fn().mockResolvedValue({
      renderedPdf: { contentType: "application/pdf", base64: "JVBERi0=" },
      ticketRenderedImage: { contentType: "image/png", base64: "iVBORw0=" },
      fileName: "RETIRADA_CAJA-001.pdf",
    });
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig),
      printTicket,
    };

    const outcome = await printCashWithdrawalReceipt(
      "movement-123456789",
      "token",
      { storeName: "Tienda", terminalCode: "01" },
      "es",
      hardware as never,
      request,
    );

    expect(outcome).toEqual({ status: "PRINTED" });
    expect(request).toHaveBeenCalledWith(
      "/cash/receipts/withdrawals/movement-123456789/print-document",
      { token: "token" },
    );
    const printed = printTicket.mock.calls[0][0];
    expect(printed.requireRenderedDocument).toBe(true);
    expect(printed.renderedPdf).toEqual({ contentType: "application/pdf", base64: "JVBERi0=" });
    expect(printed.documentRaster).toBe("data:image/png;base64,iVBORw0=");
  });

  it("returns a failed outcome instead of throwing so retry cannot duplicate the movement", async () => {
    const outcome = await printCashWithdrawalReceipt(
      "movement-1",
      "token",
      { storeName: "Tienda", terminalCode: "01" },
      "es",
      {} as never,
      vi.fn().mockRejectedValue(new Error("offline")),
    );

    expect(outcome).toEqual({ status: "FAILED", technicalMessage: "offline" });
  });
});
