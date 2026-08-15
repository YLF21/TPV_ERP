import { describe, expect, it, vi } from "vitest";
import { defaultHardwareConfig, type HardwareBridge } from "../hardware/hardware";
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

  it("prints the backend Jasper PDF through the Windows ticket printer", async () => {
    const printA4Document = vi.fn().mockResolvedValue({ ok: true });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue({
        ...defaultHardwareConfig,
        ticketPrinterName: "EPSON",
        ticketPrinterDriver: "WINDOWS_DRIVER",
      }),
      printA4Document,
    } as unknown as HardwareBridge;
    const jasperVoucher = {
      ...voucher,
      renderedPdf: { contentType: "application/pdf" as const, base64: "JVBERi0=" },
      ticketRenderedImage: { contentType: "image/png" as const, base64: "iVBORw0=" },
    };

    await expect(outputIssuedVoucher(jasperVoucher, terminal, "es", hardware))
      .resolves.toEqual({ status: "PRINTED" });

    expect(printA4Document).toHaveBeenCalledWith(
      expect.objectContaining({
        documentType: "REPORT",
        documentNumber: "VABC123",
        renderedPdf: jasperVoucher.renderedPdf,
      }),
      expect.objectContaining({
        documentPrintRoutes: expect.arrayContaining([
          expect.objectContaining({
            documentType: "REPORT",
            printerTarget: "TICKET_PRINTER",
            printerName: "EPSON",
            paperSize: "TICKET_80",
          }),
        ]),
      }),
    );
  });

  it("prints the Jasper raster through ESC POS RAW", async () => {
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue({
        ...defaultHardwareConfig,
        ticketPrinterDriver: "ESCPOS_RAW",
      }),
      printTicket,
    } as unknown as HardwareBridge;

    await expect(outputIssuedVoucher({
      ...voucher,
      renderedPdf: { contentType: "application/pdf", base64: "JVBERi0=" },
      ticketRenderedImage: { contentType: "image/png", base64: "iVBORw0=" },
    }, terminal, "es", hardware)).resolves.toEqual({ status: "PRINTED" });

    expect(printTicket).toHaveBeenCalledWith(
      expect.objectContaining({
        documentRaster: "data:image/png;base64,iVBORw0=",
      }),
      expect.objectContaining({ ticketPrinterDriver: "ESCPOS_RAW" }),
    );
  });
});
