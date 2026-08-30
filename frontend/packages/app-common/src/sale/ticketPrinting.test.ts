import { describe, expect, it, vi } from "vitest";
import { defaultHardwareConfig } from "../hardware/hardware";
import type { HardwareBridge } from "../hardware/hardware";
import type { TerminalContext } from "../types";
import {
  commercialDocumentAsA4Document,
  printCustomerReceivablePaymentReceipt,
  outputConfirmedTicket,
  printPendingCommercialDocument,
  printConfirmedTicketAutomatically,
  retryConfirmedTicketPrint,
  ticketAsA4Document,
  ticketPrintRequest,
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
  total: "7",
  baseTotal: "5.79",
  taxTotal: "1.21",
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
  it("normalizes payment method labels across ticket and A4 fallback routes", () => {
    const paymentSnapshot = {
      ...snapshot,
      payments: [
        { method: "credito_devolucion", amount: "4.39" },
        { method: "tarjeta", amount: "2.00" },
        { method: "pago_personalizado", amount: "1.00" },
      ],
    };

    expect(ticketPrintRequest(paymentSnapshot, terminal, "zh").payments).toEqual([
      { method: "CREDITO DEVOLUCION", amount: 4.39 },
      { method: "TARJETA", amount: 2 },
      { method: "PAGO PERSONALIZADO", amount: 1 },
    ]);
    expect(ticketAsA4Document(paymentSnapshot, terminal, "en").metadata).toEqual([
      { label: "CREDITO DEVOLUCION", value: "4.39" },
      { label: "TARJETA", value: "2.00" },
      { label: "PAGO PERSONALIZADO", value: "1.00" },
    ]);
  });

  it("forwards the complete frozen fiscal snapshot to every desktop renderer", () => {
    const fiscal = {
      formatVersion: "AEAT_QR_0.5.0",
      generatorVersion: "TPV-ERP-2026.08.25",
      mode: "NO_VERIFACTU" as const,
      environment: "TEST" as const,
      qrUrl: "https://prewww2.aeat.es/frozen",
      qrPayloadSha256: "A".repeat(64),
      prefix: "Prefijo congelado:",
      legend: null,
      testNotice: "Aviso congelado",
      issuerName: "Obligado congelado SL",
      issuerTaxId: "B12345674",
      issuerAddress: {
        linea1: "Calle congelada 7",
        codigoPostal: "35007",
        ciudad: "Telde",
        provincia: "Las Palmas",
        pais: "ES",
      },
    };

    expect(ticketPrintRequest({
      ...snapshot,
      qrUrl: fiscal.qrUrl,
      qrImage: "data:image/png;base64,QR==",
      fiscal,
    }, terminal, "es")).toEqual(expect.objectContaining({
      fiscal,
      issuer: expect.objectContaining({
        name: "Obligado congelado SL",
        taxId: "B12345674",
        address: "Calle congelada 7, 35007 Telde, Las Palmas, ES",
      }),
    }));

    expect(commercialDocumentAsA4Document({
      kind: "COMMERCIAL_DOCUMENT",
      documentType: "FACTURA_VENTA",
      documentNumber: "FV-FROZEN",
      lines: [],
      total: 0,
      qrUrl: fiscal.qrUrl,
      qrImage: "data:image/png;base64,QR==",
      fiscal,
    }, terminal, "es")).toEqual(expect.objectContaining({ fiscal }));
  });

  it("keeps a compensating exchange summary non-fiscal in raw and A4 routes", () => {
    const summary = {
      ...snapshot,
      nonFiscalSummary: true,
      qrUrl: "https://prewww2.aeat.es/should-not-print",
      qrImage: "data:image/png;base64,QR==",
      fiscal: {
        formatVersion: "AEAT_QR_0.5.0",
        generatorVersion: "TPV-ERP-2026.08.25",
        mode: "VERIFACTU" as const,
        environment: "TEST" as const,
        qrUrl: "https://prewww2.aeat.es/should-not-print",
        qrPayloadSha256: "A".repeat(64),
        prefix: "QR tributario:",
        legend: "VERI*FACTU",
        testNotice: "PRUEBA",
      },
      ticketRenderedPdf: { contentType: "application/pdf" as const, base64: "JVBERg==" },
      ticketRenderedImage: { contentType: "image/png" as const, base64: "iVBORw==" },
    };

    for (const request of [
      ticketPrintRequest(summary, terminal, "es"),
      ticketAsA4Document(summary, terminal, "es"),
    ]) {
      expect(request.requireRenderedDocument).toBe(false);
      expect(request).not.toHaveProperty("qrUrl");
      expect(request).not.toHaveProperty("qrImage");
      expect(request).not.toHaveProperty("fiscal");
      expect(request).not.toHaveProperty("renderedPdf");
      expect(request).not.toHaveProperty("documentRaster");
    }
  });

  it("passes a backend-rendered Jasper PDF through to the desktop bridge", () => {
    const renderedPdf = { contentType: "application/pdf" as const, base64: "JVBERi0xLjc=" };

    expect(commercialDocumentAsA4Document({
      kind: "COMMERCIAL_DOCUMENT",
      documentType: "FACTURA_VENTA",
      documentNumber: "FV-JASPER",
      lines: [{ code: "P-1", barcode: "8430000000010", name: "Cafe", quantity: 1, unitPrice: 2, total: 2 }],
      total: 2,
      renderedPdf,
    }, terminal, "es")).toEqual(expect.objectContaining({
      requireRenderedDocument: true,
      renderedPdf,
      lines: [expect.objectContaining({ barcode: "8430000000010" })],
    }));
  });

  it("prints the authoritative snapshot when automatic ticket printing is enabled", async () => {
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    const ticketRenderedPdf = {
      contentType: "application/pdf" as const,
      base64: "JVBERi10aWNrZXQ=",
    };
    const ticketRenderedImage = {
      contentType: "image/png" as const,
      base64: "iVBORw0KGgo=",
    };
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(hardwareConfig(true)),
      printTicket
    } as unknown as HardwareBridge;

    const result = await printConfirmedTicketAutomatically({
      ...snapshot,
      ticketRenderedPdf,
      ticketRenderedImage,
    }, terminal, hardware);

    expect(result).toEqual({ status: "PRINTED" });
    expect(printTicket).toHaveBeenCalledWith({
      requireRenderedDocument: true,
      documentNumber: "T-1",
      storeName: "Tienda",
      terminalCode: "CAJA-1",
      issuedAt: "2026-07-15T10:15:30Z",
      lines: [{ name: "Cafe", quantity: 2, price: 3.5, total: 7 }],
      payments: [{ method: "EFECTIVO", amount: 7 }],
      total: 7,
      subtotal: 5.79,
      tax: 1.21,
      renderedPdf: ticketRenderedPdf,
      documentRaster: "data:image/png;base64,iVBORw0KGgo=",
      labels: expect.objectContaining({ discount: "Descuento" }),
      escposLabels: expect.objectContaining({ discount: "Descuento" }),
    }, expect.objectContaining({ documentPrintRoutes: expect.any(Array) }));
  });

  it("maps the aggregated F11 discount to one printable summary amount", () => {
    expect(ticketPrintRequest({
      ...snapshot,
      checkoutDiscountTotal: "10.00",
      total: "20.00",
    }, terminal, "es")).toEqual(expect.objectContaining({
      discount: 10,
      total: 20,
      labels: expect.objectContaining({ discount: "Descuento" }),
      escposLabels: expect.objectContaining({ discount: "Descuento" }),
    }));
  });

  it("maps member balance separately from F11 in the printable summary", () => {
    const request = ticketPrintRequest({
      ...snapshot,
      memberBalanceTotal: "4.85",
      checkoutDiscountTotal: "1.00",
      total: "1.15",
    }, terminal, "es");

    expect(request).toEqual(expect.objectContaining({
      memberBalance: 4.85,
      discount: 1,
      total: 1.15,
      labels: expect.objectContaining({ memberBalance: "Saldo socio" }),
      escposLabels: expect.objectContaining({ memberBalance: "Saldo socio" }),
    }));
    expect(buildTicketBuffer(request).toString("latin1"))
      .toContain("Saldo socio                          -4.85");
  });

  it("prints confirmed tickets even when a legacy route disabled automatic printing", async () => {
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(hardwareConfig(false)),
      printTicket
    } as unknown as HardwareBridge;

    await expect(printConfirmedTicketAutomatically(snapshot, terminal, hardware))
      .resolves.toEqual({ status: "PRINTED" });
    expect(printTicket).toHaveBeenCalledOnce();
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

  it("keeps the fiscal ticket but skips physical output when this sale selects no print", async () => {
    const hardware = {
      getHardwareConfig: vi.fn(),
      printTicket: vi.fn(),
      printA4Document: vi.fn(),
      exportTicketPdf: vi.fn(),
    } as unknown as HardwareBridge;

    await expect(outputConfirmedTicket(snapshot, terminal, "NONE", "es", hardware))
      .resolves.toEqual({ status: "SKIPPED" });
    expect(hardware.getHardwareConfig).not.toHaveBeenCalled();
    expect(hardware.printTicket).not.toHaveBeenCalled();
  });

  it("exports the authoritative ticket snapshot as a PDF", async () => {
    const exportTicketPdf = vi.fn().mockResolvedValue({
      ok: true,
      canceled: false,
      filePath: "T-1.pdf",
    });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig),
      exportTicketPdf,
    } as unknown as HardwareBridge;

    await expect(outputConfirmedTicket(snapshot, terminal, "PDF", "es", hardware))
      .resolves.toEqual({ status: "PRINTED" });
    expect(exportTicketPdf).toHaveBeenCalledWith(
      expect.objectContaining({ documentNumber: "T-1", total: 7 }),
      "T-1.pdf",
    );
  });

  it("routes a ticket to the configured A4 printer for this sale only", async () => {
    const printA4Document = vi.fn().mockResolvedValue({ ok: true });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue({
        ...defaultHardwareConfig,
        a4PrinterName: "A4 CAJA",
      }),
      printA4Document,
    } as unknown as HardwareBridge;

    await expect(outputConfirmedTicket(snapshot, terminal, "A4_PRINTER", "es", hardware))
      .resolves.toEqual({ status: "PRINTED" });
    expect(printA4Document).toHaveBeenCalledWith(
      expect.objectContaining({
        documentType: "REPORT",
        title: "Ticket T-1",
        subtotal: 5.79,
        tax: 1.21,
        total: 7,
      }),
      expect.objectContaining({
        documentPrintRoutes: expect.arrayContaining([
          expect.objectContaining({
            documentType: "REPORT",
            printerTarget: "A4_PRINTER",
            printerName: "A4 CAJA",
          }),
        ]),
      }),
    );
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
      issuer: { name: "TPV ERP SL", taxId: "B12345678", address: { line1: "Calle Mayor 1", postalCode: "28001", city: "Madrid", province: "Madrid", country: "ES" } },
      customer: { name: "Cliente Fiscal SL", taxId: "B87654321", address: { line1: "Avenida Sur 2", postalCode: "41001", city: "Sevilla", province: "Sevilla", country: "ES" } },
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
      issuer: expect.objectContaining({ name: "TPV ERP SL", taxId: "B12345678" }),
      customer: expect.objectContaining({ name: "Cliente Fiscal SL", taxId: "B87654321" }),
      labels: expect.objectContaining({ description: "Descripción", quantity: "Cantidad", tax: "Impuesto" })
    }), expect.anything());
  });

  it("selects the Jasper 80 mm PDF for a Windows ticket-printer route", async () => {
    const printA4Document = vi.fn().mockResolvedValue({ ok: true });
    const ticketRenderedPdf = {
      contentType: "application/pdf" as const,
      base64: "JVBERi10aWNrZXQ=",
    };
    const config = {
      ...defaultHardwareConfig,
      ticketPrinterDriver: "WINDOWS_DRIVER" as const,
      documentPrintRoutes: defaultHardwareConfig.documentPrintRoutes.map((route) =>
        route.documentType === "INVOICE"
          ? { ...route, printerTarget: "TICKET_PRINTER" as const, paperSize: "TICKET_80" as const }
          : route),
    };
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(config),
      printA4Document,
    } as unknown as HardwareBridge;

    await printPendingCommercialDocument({
      kind: "COMMERCIAL_DOCUMENT",
      documentType: "FACTURA_VENTA",
      documentNumber: "FV-80",
      lines: snapshot.lines,
      total: "7.00",
      renderedPdf: { contentType: "application/pdf", base64: "JVBERi1hNA==" },
      ticketRenderedPdf,
    }, terminal, hardware);

    expect(printA4Document).toHaveBeenCalledWith(
      expect.objectContaining({
        requireRenderedDocument: true,
        renderedPdf: ticketRenderedPdf,
      }),
      config,
    );
  });

  it("exports a pending commercial document as PDF when selected for this sale", async () => {
    const exportA4DocumentPdf = vi.fn().mockResolvedValue({
      ok: true,
      canceled: false,
      filePath: "FV-1.pdf",
    });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig),
      exportA4DocumentPdf,
    } as unknown as HardwareBridge;

    await expect(printPendingCommercialDocument({
      kind: "COMMERCIAL_DOCUMENT",
      documentType: "FACTURA_VENTA",
      documentNumber: "FV-1",
      issuedAt: "2026-07-16T10:00:00Z",
      lines: snapshot.lines,
      total: "7.00",
    }, terminal, hardware, "es", "PDF")).resolves.toEqual({ status: "PRINTED" });

    expect(exportA4DocumentPdf).toHaveBeenCalledWith(
      expect.objectContaining({
        documentType: "INVOICE",
        title: "Factura FV-1",
        total: 7,
      }),
      "FV-1.pdf",
    );
  });

  it("keeps a pending document but skips output when no print is selected", async () => {
    const hardware = {
      getHardwareConfig: vi.fn(),
      printTicket: vi.fn(),
      printA4Document: vi.fn(),
      exportA4DocumentPdf: vi.fn(),
    } as unknown as HardwareBridge;

    await expect(printPendingCommercialDocument({
      kind: "COMMERCIAL_DOCUMENT",
      documentType: "ALBARAN_VENTA",
      documentNumber: "AV-1",
      lines: snapshot.lines,
      total: "7.00",
    }, terminal, hardware, "es", "NONE")).resolves.toEqual({ status: "SKIPPED" });

    expect(hardware.getHardwareConfig).not.toHaveBeenCalled();
    expect(hardware.printA4Document).not.toHaveBeenCalled();
  });

  it.each([
    { documentType: "FACTURA_VENTA" as const, routeType: "INVOICE" as const, documentNumber: "FV-RAW" },
    { documentType: "ALBARAN_VENTA" as const, routeType: "DELIVERY_NOTE" as const, documentNumber: "AV-RAW" }
  ])("routes $documentType parties and authoritative fiscal totals through ESC/POS", async ({ documentType, routeType, documentNumber }) => {
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    const config = {
      ...defaultHardwareConfig,
      ticketPrinterDriver: "ESCPOS_RAW" as const,
      documentPrintRoutes: defaultHardwareConfig.documentPrintRoutes.map((route) => route.documentType === routeType
        ? { ...route, printerTarget: "TICKET_PRINTER" as const, paperSize: "TICKET_80" as const }
        : route)
    };
    const hardware = { getHardwareConfig: vi.fn().mockResolvedValue(config), printTicket } as unknown as HardwareBridge;

    await printPendingCommercialDocument({
      kind: "COMMERCIAL_DOCUMENT", documentType, documentNumber,
      issuer: { name: "TPV ERP SL", taxId: "B12345678", address: { line1: "Calle Mayor 1", postalCode: "28001", city: "Madrid", province: "Madrid", country: "ES" } },
      customer: { name: "Cliente Fiscal SL", taxId: "B87654321", address: { line1: "Avenida Sur 2", postalCode: "41001", city: "Sevilla", province: "Sevilla", country: "ES" } },
      lines: snapshot.lines, baseTotal: "100.00", taxTotal: "21.00", total: "121.00"
    }, terminal, hardware);

    expect(printTicket).toHaveBeenCalledWith(expect.objectContaining({
      documentNumber,
      issuer: expect.objectContaining({ name: "TPV ERP SL", taxId: "B12345678" }),
      customer: expect.objectContaining({ name: "Cliente Fiscal SL", taxId: "B87654321" }),
      subtotal: 100,
      tax: 21,
      total: 121,
      escposLabels: expect.objectContaining({ base: "Base", tax: "Impuesto", total: "Total" })
    }), config);
  });

  it("sends the Jasper ticket raster through the ESC/POS route", async () => {
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    const config = {
      ...defaultHardwareConfig,
      ticketPrinterDriver: "ESCPOS_RAW" as const,
      documentPrintRoutes: defaultHardwareConfig.documentPrintRoutes.map((route) => route.documentType === "INVOICE"
        ? { ...route, printerTarget: "TICKET_PRINTER" as const, paperSize: "TICKET_80" as const }
        : route),
    };
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(config),
      printTicket,
    } as unknown as HardwareBridge;

    await printPendingCommercialDocument({
      kind: "COMMERCIAL_DOCUMENT",
      documentType: "FACTURA_VENTA",
      documentNumber: "FV-JASPER-80",
      lines: snapshot.lines,
      total: "7.00",
      ticketRenderedImage: { contentType: "image/png", base64: "iVBORw0KGgo=" },
    }, terminal, hardware);

    expect(printTicket).toHaveBeenCalledWith(expect.objectContaining({
      documentRaster: "data:image/png;base64,iVBORw0KGgo=",
    }), config);
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
      method: "credito_devolucion",
      amount: "20.00",
      remaining: "50.00",
      renderedPdf: { contentType: "application/pdf", base64: "JVBERi0=" },
      ticketRenderedImage: { contentType: "image/png", base64: "iVBORw0KGgo=" }
    }, terminal, hardware)).resolves.toEqual({ status: "PRINTED" });

    expect(printTicket).toHaveBeenCalledWith(expect.objectContaining({
      documentNumber: "COBRO FV-1 / pay-1",
      issuedAt: "2026-07-20T09:00:00Z",
      payments: [{ method: "CREDITO DEVOLUCION", amount: 20 }],
      total: 20,
      renderedPdf: { contentType: "application/pdf", base64: "JVBERi0=" },
      documentRaster: "data:image/png;base64,iVBORw0KGgo="
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
