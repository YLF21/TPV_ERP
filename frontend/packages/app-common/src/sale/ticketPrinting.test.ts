import { describe, expect, it, vi } from "vitest";
import { defaultHardwareConfig } from "../hardware/hardware";
import type { HardwareBridge } from "../hardware/hardware";
import type { TerminalContext } from "../types";
import {
  printCustomerReceivablePaymentReceipt,
  printPendingCommercialDocument,
  printConfirmedTicketAutomatically,
  retryConfirmedTicketPrint
} from "./ticketPrinting";
import type { ConfirmedTicketPrintSnapshot } from "./ticketPrinting";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const { buildTicketBuffer } = require("../../../../desktop/escpos.cjs");

const snapshot: ConfirmedTicketPrintSnapshot = {
  documentId: "document-1",
  documentNumber: "T-1",
  issuedAt: "2026-07-15T10:15:30Z",
  lines: [{ name: "Cafe", quantity: "2", price: "3.5", total: "7" }],
  payments: [{ method: "EFECTIVO", amount: "7" }],
  total: "7"
};

const terminal: TerminalContext = {
  storeName: "Tienda",
  terminalCode: "CAJA-1"
};

function hardwareConfig(printAutomatically: boolean) {
  return {
    ...defaultHardwareConfig,
    documentPrintRoutes: defaultHardwareConfig.documentPrintRoutes.map((route) =>
      route.documentType === "TICKET" ? { ...route, printAutomatically } : route
    )
  };
}

describe("confirmed ticket printing", () => {
  it("prints the authoritative snapshot when automatic ticket printing is enabled", async () => {
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(hardwareConfig(true)),
      printTicket
    } as unknown as HardwareBridge;

    const result = await printConfirmedTicketAutomatically(snapshot, terminal, hardware);

    expect(result).toEqual({ status: "PRINTED" });
    expect(printTicket).toHaveBeenCalledWith({
      documentNumber: "T-1",
      storeName: "Tienda",
      terminalCode: "CAJA-1",
      issuedAt: "2026-07-15T10:15:30Z",
      lines: [{ name: "Cafe", quantity: 2, price: 3.5, total: 7 }],
      payments: [{ method: "EFECTIVO", amount: 7 }],
      total: 7
    }, expect.objectContaining({ documentPrintRoutes: expect.any(Array) }));
  });

  it("skips automatic printing when the ticket route disables it", async () => {
    const printTicket = vi.fn();
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(hardwareConfig(false)),
      printTicket
    } as unknown as HardwareBridge;

    await expect(printConfirmedTicketAutomatically(snapshot, terminal, hardware))
      .resolves.toEqual({ status: "SKIPPED" });
    expect(printTicket).not.toHaveBeenCalled();
  });

  it("returns a structured failure when hardware rejects the ticket", async () => {
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(hardwareConfig(true)),
      printTicket: vi.fn().mockResolvedValue({
        ok: false,
        code: "PRINT_FAILED",
        message: "printer offline"
      })
    } as unknown as HardwareBridge;

    await expect(printConfirmedTicketAutomatically(snapshot, terminal, hardware))
      .resolves.toEqual({ status: "FAILED", technicalMessage: "printer offline" });
  });

  it("converts a rejected hardware call into a structured failure", async () => {
    const hardware = {
      getHardwareConfig: vi.fn().mockRejectedValue(new Error("bridge unavailable")),
      printTicket: vi.fn()
    } as unknown as HardwareBridge;

    await expect(printConfirmedTicketAutomatically(snapshot, terminal, hardware))
      .resolves.toEqual({ status: "FAILED", technicalMessage: "bridge unavailable" });
  });

  it("retries printing even when automatic ticket printing is disabled", async () => {
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(hardwareConfig(false)),
      printTicket
    } as unknown as HardwareBridge;

    await expect(retryConfirmedTicketPrint(snapshot, terminal, hardware))
      .resolves.toEqual({ status: "PRINTED" });
    expect(printTicket).toHaveBeenCalledOnce();
  });

  it("prints a pending commercial sale as its authoritative A4 document", async () => {
    const printA4Document = vi.fn().mockResolvedValue({ ok: true });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig),
      printA4Document
    } as unknown as HardwareBridge;

    await expect(printPendingCommercialDocument({
      kind: "COMMERCIAL_DOCUMENT",
      documentType: "FACTURA_VENTA",
      documentNumber: "FV-1",
      issuedAt: "2026-07-16T10:00:00Z",
      lines: snapshot.lines,
      baseTotal: "100.00",
      taxTotal: "21.00",
      total: "100.00"
    }, terminal, hardware)).resolves.toEqual({ status: "PRINTED" });

    expect(printA4Document).toHaveBeenCalledWith(expect.objectContaining({
      documentType: "INVOICE",
      title: "Factura FV-1",
      subtotal: 100,
      tax: 21,
      total: 100,
      labels: expect.objectContaining({ description: "Descripción", quantity: "Cantidad", tax: "Impuesto" })
    }), expect.anything());
  });

  it("preserves authoritative per-line tax inclusion for mixed documents", async () => {
    const printA4Document = vi.fn().mockResolvedValue({ ok: true });
    const hardware = { getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig), printA4Document } as unknown as HardwareBridge;
    await printPendingCommercialDocument({ kind: "COMMERCIAL_DOCUMENT", documentType: "FACTURA_VENTA", documentNumber: "FV-MIX",
      lines: [{ name: "Included", quantity: 1, unitPrice: 10, total: 10, taxesIncluded: true },
        { name: "Excluded", quantity: 1, unitPrice: 10, total: 12.1, taxesIncluded: false }],
      baseTotal: 20, taxTotal: 2.1, total: 22.1 }, terminal, hardware, "en");
    expect(printA4Document).toHaveBeenCalledWith(expect.objectContaining({
      lines: [expect.objectContaining({ taxesIncluded: true }), expect.objectContaining({ taxesIncluded: false })],
      taxIncluded: "MIXED"
    }), expect.anything());
  });

  it("prints a later collection as a payment receipt and not as the original sale", async () => {
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig),
      printTicket
    } as unknown as HardwareBridge;

    await expect(printCustomerReceivablePaymentReceipt({
      kind: "PAYMENT_RECEIPT",
      paymentId: "pay-1",
      documentNumber: "FV-1",
      collectedAt: "2026-07-20T09:00:00Z",
      method: "TRANSFERENCIA",
      amount: "20.00",
      remaining: "50.00"
    }, terminal, hardware)).resolves.toEqual({ status: "PRINTED" });

    expect(printTicket).toHaveBeenCalledWith(expect.objectContaining({
      documentNumber: "COBRO FV-1 / pay-1",
      issuedAt: "2026-07-20T09:00:00Z",
      payments: [{ method: "TRANSFERENCIA", amount: 20 }],
      total: 20
    }), expect.anything());
  });

  it("sends separate Unicode and printable Chinese ESC/POS labels end to end", async () => {
    let payload: any;
    const hardware = { getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig),
      printTicket: vi.fn().mockImplementation((value) => { payload = value; return Promise.resolve({ ok: true }); }) } as unknown as HardwareBridge;
    await printCustomerReceivablePaymentReceipt({ kind: "PAYMENT_RECEIPT", paymentId: "p", documentNumber: "F",
      collectedAt: "now", method: "CARD", amount: 2, remaining: 0 }, terminal, hardware, "zh");
    expect(payload.labels.item).toBe("商品");
    expect(payload.escposLabels).toEqual({ terminal: "Zhongduan", item: "Shangpin", quantity: "Shuliang", price: "Jiage", total: "Zongji" });
    const raw = buildTicketBuffer(payload).toString("latin1");
    expect(raw).toContain("Zhongduan terminal-CAJA-1"); expect(raw).toContain("Shangpin / Shuliang / Jiage");
    expect(raw.match(/Zhongduan terminal-CAJA-1|Shangpin \/ Shuliang \/ Jiage|Zongji/g)?.join(" ")).not.toContain("??");
  });

  it("produces a complete readable Chinese ESC/POS receipt without replacement markers", async () => {
    let payload: any;
    const hardware = { getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig),
      printTicket: vi.fn().mockImplementation((value) => { payload = value; return Promise.resolve({ ok: true }); }) } as unknown as HardwareBridge;
    await printCustomerReceivablePaymentReceipt({ kind: "PAYMENT_RECEIPT", paymentId: "pay-01", documentNumber: "发票-01",
      collectedAt: "now", method: "银行卡", amount: 2, remaining: 0 },
      { storeName: "商店", terminalCode: "终端-01" }, hardware, "zh");
    const raw = buildTicketBuffer(payload).toString("latin1");
    expect(raw).not.toContain("??");
    expect(raw).toContain("Dianpu"); expect(raw).toContain("Zhongduan terminal-01");
    expect(raw).toContain("Shoukuan pay-01"); expect(raw).toContain("Fangshi CARD");
  });

  it("localizes customer receivable print copy", async () => {
    const printA4Document = vi.fn().mockResolvedValue({ ok: true });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig),
      printA4Document
    } as unknown as HardwareBridge;

    await printPendingCommercialDocument({
      kind: "COMMERCIAL_DOCUMENT",
      documentType: "FACTURA_VENTA",
      documentNumber: "FV-2",
      issuedAt: "2026-07-16T10:00:00Z",
      lines: snapshot.lines,
      total: "7.00"
    }, terminal, hardware, "en");

    expect(printA4Document).toHaveBeenCalledWith(
      expect.objectContaining({ title: "Invoice FV-2" }),
      expect.anything()
    );
  });
});
