import { getHardwareBridge } from "../hardware/hardware";
import type {
  A4DocumentPrintRequest,
  HardwareBridge,
  HardwareConfig,
  TicketPrintRequest,
} from "../hardware/hardware";
import type { LocaleCode, TerminalContext } from "../types";
import type { TicketPrintOutcome } from "./ticketPrinting";

export type IssuedVoucherPrintSnapshot = {
  code: string;
  amount: number | string;
  issuedAt: string;
  originTicketNumber: string;
  traceability?: Array<{
    documentNumber: string;
    documentType?: string | null;
    documentDate?: string | null;
    operation: string;
  }>;
  observations?: string | null;
  renderedPdf?: { contentType: "application/pdf"; base64: string } | null;
  ticketRenderedImage?: { contentType: "image/png"; base64: string } | null;
};

const copy = {
  es: { title: "VALE DE DEVOLUCIÓN", origin: "Ticket de origen" },
  en: { title: "REFUND VOUCHER", origin: "Original ticket" },
  zh: { title: "退款代金券", origin: "原始小票" },
} as const;

export function issuedVoucherPrintRequest(
  voucher: IssuedVoucherPrintSnapshot,
  terminal: TerminalContext,
  locale: LocaleCode,
): TicketPrintRequest {
  const amount = Number(voucher.amount);
  const text = copy[locale];
  return {
    requireRenderedDocument: true,
    documentNumber: voucher.code,
    storeName: terminal.storeName,
    terminalCode: terminal.terminalCode,
    issuedAt: voucher.issuedAt,
    lines: [{
      name: `${text.title}\n${text.origin}: ${voucher.originTicketNumber}`,
      quantity: 1,
      price: amount,
      total: amount,
    }],
    payments: [],
    total: amount,
  };
}

export async function outputIssuedVoucher(
  voucher: IssuedVoucherPrintSnapshot,
  terminal: TerminalContext,
  locale: LocaleCode,
  hardware: HardwareBridge = getHardwareBridge(),
): Promise<TicketPrintOutcome> {
  try {
    const fallback = issuedVoucherPrintRequest(voucher, terminal, locale);
    if (!voucher.renderedPdf || !voucher.ticketRenderedImage) {
      const result = await hardware.printTicket(fallback);
      return result.ok
        ? { status: "PRINTED" }
        : { status: "FAILED", technicalMessage: result.message };
    }
    const config = await hardware.getHardwareConfig();
    const result = config.ticketPrinterDriver === "ESCPOS_RAW"
      ? await hardware.printTicket({
        ...fallback,
        documentRaster: `data:${voucher.ticketRenderedImage.contentType};base64,${voucher.ticketRenderedImage.base64}`,
      }, config)
      : await hardware.printA4Document(
        voucherAsJasperDocument(voucher, terminal, locale),
        voucherTicketRoute(config),
      );
    return result.ok
      ? { status: "PRINTED" }
      : { status: "FAILED", technicalMessage: result.message };
  } catch (failure) {
    return {
      status: "FAILED",
      technicalMessage: failure instanceof Error ? failure.message : undefined,
    };
  }
}

function voucherTicketRoute(config: HardwareConfig): HardwareConfig {
  return {
    ...config,
    documentPrintRoutes: [
      ...config.documentPrintRoutes.filter((route) => route.documentType !== "REPORT"),
      {
        documentType: "REPORT",
        printerTarget: "TICKET_PRINTER",
        printerName: config.ticketPrinterName,
        paperSize: "TICKET_80",
        orientation: "PORTRAIT",
        copies: 1,
        printAutomatically: true,
        showPrintDialog: false,
      },
    ],
  };
}

function voucherAsJasperDocument(
  voucher: IssuedVoucherPrintSnapshot,
  terminal: TerminalContext,
  locale: LocaleCode,
): A4DocumentPrintRequest {
  const text = copy[locale];
  const amount = Number(voucher.amount);
  return {
    requireRenderedDocument: true,
    documentType: "REPORT",
    locale,
    title: text.title,
    documentNumber: voucher.code,
    storeName: terminal.storeName,
    terminalCode: terminal.terminalCode,
    issuedAt: voucher.issuedAt,
    lines: [],
    subtotal: amount,
    tax: 0,
    taxIncluded: true,
    total: amount,
    renderedPdf: voucher.renderedPdf ?? undefined,
    notes: voucher.observations ? [voucher.observations] : [],
    labels: {
      terminal: "Terminal",
      description: "Documento",
      quantity: "Cantidad",
      unitPrice: "Importe",
      base: "Base",
      tax: "Impuesto",
      taxIncluded: "Impuestos incluidos",
      yes: "Sí",
      no: "No",
      mixed: "Mixto",
      total: "Total",
    },
  };
}
