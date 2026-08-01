import { getHardwareBridge } from "../hardware/hardware";
import type {
  A4DocumentPrintRequest,
  DocumentPrintRoute,
  HardwareBridge,
  HardwareConfig,
  TicketPrintRequest,
} from "../hardware/hardware";
import type { TerminalContext } from "../types";
import type { LocaleCode } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";

type NumericValue = number | string;
type FiscalPartySnapshot = {
  name: string;
  taxId: string;
  address: { line1?: string; postalCode?: string; city?: string; province?: string; country?: string };
};

export type ConfirmedTicketPrintSnapshot = {
  documentId: string;
  documentNumber: string;
  issuedAt: string;
  lines: Array<{
    name: string;
    quantity: NumericValue;
    price: NumericValue;
    total: NumericValue;
    serialNumbers?: string[];
  }>;
  payments: Array<{
    method: string;
    amount: NumericValue;
  }>;
  total: NumericValue;
  baseTotal?: NumericValue;
  taxTotal?: NumericValue;
};

export type TicketPrintOutcome = {
  status: "PRINTED" | "FAILED" | "SKIPPED";
  technicalMessage?: string;
};

export type SalePrintMode =
  | "DEFAULT"
  | "TICKET_PRINTER"
  | "A4_PRINTER"
  | "PDF"
  | "NONE";

export type PendingCommercialDocumentPrintSnapshot = {
  kind: "COMMERCIAL_DOCUMENT";
  documentType: "ALBARAN_VENTA" | "FACTURA_VENTA";
  documentNumber: string;
  issuedAt?: string;
  issueDate?: string;
  issuer?: FiscalPartySnapshot;
  customer?: FiscalPartySnapshot;
  lines: Array<{ name: string; quantity: NumericValue; unitPrice?: NumericValue; price?: NumericValue; total: NumericValue; taxesIncluded?: boolean; serialNumbers?: string[] }>;
  baseTotal?: NumericValue;
  taxTotal?: NumericValue;
  total: NumericValue;
};

function printableAddress(address: FiscalPartySnapshot["address"] | undefined) {
  if (!address) return "";
  return [address.line1, [address.postalCode, address.city].filter(Boolean).join(" "), address.province, address.country]
    .filter((value, index, values) => Boolean(value) && values.indexOf(value) === index)
    .join(", ");
}

function partyLabels(locale: LocaleCode) {
  if (locale === "en") return { issuer: "Issuer", customer: "Customer", taxId: "Tax ID" };
  if (locale === "zh") return { issuer: "Fang", customer: "Kehu", taxId: "Shuihao" };
  return { issuer: "Emisor", customer: "Cliente", taxId: "NIF" };
}

export type CustomerReceivablePaymentReceiptSnapshot = {
  kind: "PAYMENT_RECEIPT";
  paymentId: string;
  documentNumber: string;
  collectedAt: string;
  method: string;
  amount: NumericValue;
  remaining: NumericValue;
};

export function ticketPrintRequest(
  snapshot: ConfirmedTicketPrintSnapshot,
  terminal: TerminalContext
): TicketPrintRequest {
  return {
    documentNumber: snapshot.documentNumber,
    storeName: terminal.storeName,
    terminalCode: terminal.terminalCode,
    issuedAt: snapshot.issuedAt,
    lines: snapshot.lines.map((line) => ({
      name: line.name,
      quantity: Number(line.quantity),
      price: Number(line.price),
      total: Number(line.total),
      ...(line.serialNumbers?.length ? { serialNumbers: line.serialNumbers } : {})
    })),
    payments: snapshot.payments.map((payment) => ({
      method: payment.method,
      amount: Number(payment.amount)
    })),
    total: Number(snapshot.total),
    ...(snapshot.baseTotal == null ? {} : { subtotal: Number(snapshot.baseTotal) }),
    ...(snapshot.taxTotal == null ? {} : { tax: Number(snapshot.taxTotal) }),
  };
}

function ticketRoute(
  config: HardwareConfig,
  target: DocumentPrintRoute["printerTarget"],
): HardwareConfig {
  const printerName = target === "TICKET_PRINTER"
    ? config.ticketPrinterName
    : config.a4PrinterName;
  return {
    ...config,
    documentPrintRoutes: [
      ...config.documentPrintRoutes.filter((route) => route.documentType !== "TICKET"),
      {
        documentType: "TICKET",
        printerTarget: target,
        printerName,
        paperSize: target === "TICKET_PRINTER" ? "TICKET_80" : "A4",
        orientation: "PORTRAIT",
        copies: 1,
        printAutomatically: true,
        showPrintDialog: false,
      },
    ],
  };
}

function ticketAsA4Document(
  snapshot: ConfirmedTicketPrintSnapshot,
  terminal: TerminalContext,
  locale: LocaleCode,
): A4DocumentPrintRequest {
  const t = createTranslator(locale);
  return {
    documentType: "REPORT",
    locale,
    title: `${t("salesReport.tickets")} ${snapshot.documentNumber}`,
    storeName: terminal.storeName,
    terminalCode: terminal.terminalCode,
    issuedAt: snapshot.issuedAt,
    lines: snapshot.lines.map((line) => ({
      name: line.name,
      quantity: Number(line.quantity),
      price: Number(line.price),
      total: Number(line.total),
      ...(line.serialNumbers?.length ? { serialNumbers: line.serialNumbers } : {}),
    })),
    subtotal: Number(snapshot.baseTotal ?? snapshot.total),
    tax: Number(snapshot.taxTotal ?? 0),
    taxIncluded: true,
    total: Number(snapshot.total),
    metadata: snapshot.payments.map((payment) => ({
      label: payment.method,
      value: Number(payment.amount).toFixed(2),
    })),
    labels: {
      terminal: t("print.a4.terminal"),
      description: t("print.a4.description"),
      quantity: t("print.a4.quantity"),
      unitPrice: t("print.a4.unitPrice"),
      base: t("print.a4.base"),
      tax: t("print.a4.tax"),
      taxIncluded: t("print.a4.taxIncluded"),
      yes: t("common.yes"),
      no: t("common.no"),
      mixed: t("print.a4.mixed"),
      total: t("print.a4.total"),
    },
  };
}

export async function outputConfirmedTicket(
  snapshot: ConfirmedTicketPrintSnapshot,
  terminal: TerminalContext,
  mode: SalePrintMode,
  locale: LocaleCode = "es",
  hardware: HardwareBridge = getHardwareBridge(),
): Promise<TicketPrintOutcome> {
  if (mode === "NONE") return { status: "SKIPPED" };
  try {
    const config = await hardware.getHardwareConfig();
    if (mode === "DEFAULT") {
      return await sendConfirmedTicket(snapshot, terminal, hardware, config);
    }
    if (mode === "PDF") {
      const safeNumber = snapshot.documentNumber.replace(/[<>:"/\\|?*\u0000-\u001f]/g, "-");
      const result = await hardware.exportTicketPdf(
        ticketPrintRequest(snapshot, terminal),
        `${safeNumber || "ticket"}.pdf`,
      );
      return result.ok
        ? { status: result.canceled ? "SKIPPED" : "PRINTED" }
        : { status: "FAILED", technicalMessage: result.message };
    }
    if (mode === "A4_PRINTER") {
      const result = await hardware.printA4Document(
        ticketAsA4Document(snapshot, terminal, locale),
        {
          ...config,
          documentPrintRoutes: [
            ...config.documentPrintRoutes.filter((route) => route.documentType !== "REPORT"),
            {
              documentType: "REPORT",
              printerTarget: "A4_PRINTER",
              printerName: config.a4PrinterName,
              paperSize: "A4",
              orientation: "PORTRAIT",
              copies: 1,
              printAutomatically: true,
              showPrintDialog: false,
            },
          ],
        },
      );
      return result.ok
        ? { status: "PRINTED" }
        : { status: "FAILED", technicalMessage: result.message };
    }
    return await sendConfirmedTicket(
      snapshot,
      terminal,
      hardware,
      ticketRoute(config, "TICKET_PRINTER"),
    );
  } catch (error) {
    return failedOutcome(error);
  }
}

async function sendConfirmedTicket(
  snapshot: ConfirmedTicketPrintSnapshot,
  terminal: TerminalContext,
  hardware: HardwareBridge,
  config: HardwareConfig
): Promise<TicketPrintOutcome> {
  const result = await hardware.printTicket(ticketPrintRequest(snapshot, terminal), config);
  return result.ok
    ? { status: "PRINTED" }
    : { status: "FAILED", technicalMessage: result.message };
}

function failedOutcome(error: unknown): TicketPrintOutcome {
  return {
    status: "FAILED",
    technicalMessage: error instanceof Error ? error.message : String(error)
  };
}

export async function printConfirmedTicketAutomatically(
  snapshot: ConfirmedTicketPrintSnapshot,
  terminal: TerminalContext,
  hardware: HardwareBridge = getHardwareBridge()
): Promise<TicketPrintOutcome> {
  try {
    const config = await hardware.getHardwareConfig();
    return await sendConfirmedTicket(snapshot, terminal, hardware, config);
  } catch (error) {
    return failedOutcome(error);
  }
}

export async function retryConfirmedTicketPrint(
  snapshot: ConfirmedTicketPrintSnapshot,
  terminal: TerminalContext,
  hardware: HardwareBridge = getHardwareBridge()
): Promise<TicketPrintOutcome> {
  try {
    return await sendConfirmedTicket(
      snapshot,
      terminal,
      hardware,
      await hardware.getHardwareConfig()
    );
  } catch (error) {
    return failedOutcome(error);
  }
}

function pendingDocumentType(snapshot: PendingCommercialDocumentPrintSnapshot) {
  return snapshot.documentType === "FACTURA_VENTA" ? "INVOICE" as const : "DELIVERY_NOTE" as const;
}

function pendingDocumentTitle(
  snapshot: PendingCommercialDocumentPrintSnapshot,
  locale: LocaleCode,
) {
  const t = createTranslator(locale);
  return `${t(snapshot.documentType === "FACTURA_VENTA" ? "receivables.type.invoice" : "receivables.type.deliveryNote")} ${snapshot.documentNumber}`;
}

function pendingAsA4Document(
  snapshot: PendingCommercialDocumentPrintSnapshot,
  terminal: TerminalContext,
  locale: LocaleCode,
): A4DocumentPrintRequest {
  const t = createTranslator(locale);
  return {
    documentType: pendingDocumentType(snapshot),
    locale,
    title: pendingDocumentTitle(snapshot, locale),
    storeName: terminal.storeName,
    terminalCode: terminal.terminalCode,
    issuedAt: snapshot.issuedAt ?? snapshot.issueDate ?? "",
    issuer: snapshot.issuer,
    customer: snapshot.customer,
    lines: snapshot.lines.map((line) => ({
      name: line.name,
      quantity: Number(line.quantity),
      price: Number(line.unitPrice ?? line.price),
      total: Number(line.total),
      taxesIncluded: line.taxesIncluded,
      ...(line.serialNumbers?.length ? { serialNumbers: line.serialNumbers } : {}),
    })),
    subtotal: Number(snapshot.baseTotal ?? snapshot.total),
    tax: Number(snapshot.taxTotal ?? 0),
    taxIncluded: snapshot.lines.every((line) => line.taxesIncluded !== false) ? true
      : snapshot.lines.every((line) => line.taxesIncluded === false) ? false : "MIXED",
    total: Number(snapshot.total),
    labels: {
      terminal: t("print.a4.terminal"),
      description: t("print.a4.description"),
      quantity: t("print.a4.quantity"),
      unitPrice: t("print.a4.unitPrice"),
      base: t("print.a4.base"),
      tax: t("print.a4.tax"),
      taxIncluded: t("print.a4.taxIncluded"),
      yes: t("common.yes"),
      no: t("common.no"),
      mixed: t("print.a4.mixed"),
      total: t("print.a4.total"),
      ...partyLabels(locale),
    },
  };
}

function pendingDocumentRoute(
  config: HardwareConfig,
  documentType: "INVOICE" | "DELIVERY_NOTE",
  target: DocumentPrintRoute["printerTarget"],
): HardwareConfig {
  return {
    ...config,
    documentPrintRoutes: [
      ...config.documentPrintRoutes.filter((route) => route.documentType !== documentType),
      {
        documentType,
        printerTarget: target,
        printerName: target === "TICKET_PRINTER" ? config.ticketPrinterName : config.a4PrinterName,
        paperSize: target === "TICKET_PRINTER" ? "TICKET_80" : "A4",
        orientation: "PORTRAIT",
        copies: 1,
        printAutomatically: true,
        showPrintDialog: false,
      },
    ],
  };
}

export async function printPendingCommercialDocument(
  snapshot: PendingCommercialDocumentPrintSnapshot,
  terminal: TerminalContext,
  hardware: HardwareBridge = getHardwareBridge(),
  locale: LocaleCode = "es",
  mode: SalePrintMode = "DEFAULT",
): Promise<TicketPrintOutcome> {
  if (mode === "NONE") return { status: "SKIPPED" };
  try {
    const t = createTranslator(locale);
    const storedConfig = await hardware.getHardwareConfig();
    const documentType = pendingDocumentType(snapshot);
    const config = mode === "DEFAULT"
      ? storedConfig
      : pendingDocumentRoute(
        storedConfig,
        documentType,
        mode === "TICKET_PRINTER" ? "TICKET_PRINTER" : "A4_PRINTER",
      );
    const route = config.documentPrintRoutes.find((item) => item.documentType === documentType);
    const parties = partyLabels(locale);
    if (mode === "PDF") {
      const safeNumber = snapshot.documentNumber.replace(/[<>:"/\\|?*\u0000-\u001f]/g, "-");
      const result = await hardware.exportA4DocumentPdf(
        pendingAsA4Document(snapshot, terminal, locale),
        `${safeNumber || "documento"}.pdf`,
      );
      return result.ok
        ? { status: result.canceled ? "SKIPPED" : "PRINTED" }
        : { status: "FAILED", technicalMessage: result.message };
    }
    if (route?.printerTarget === "TICKET_PRINTER" && config.ticketPrinterDriver === "ESCPOS_RAW") {
      const result = await hardware.printTicket({
        documentNumber: snapshot.documentNumber,
        storeName: terminal.storeName,
        terminalCode: terminal.terminalCode,
        issuedAt: snapshot.issuedAt ?? snapshot.issueDate ?? "",
        issuer: snapshot.issuer ? { ...snapshot.issuer, address: printableAddress(snapshot.issuer.address) } : undefined,
        customer: snapshot.customer ? { ...snapshot.customer, address: printableAddress(snapshot.customer.address) } : undefined,
        partyLabels: parties,
        subtotal: Number(snapshot.baseTotal ?? snapshot.total),
        tax: Number(snapshot.taxTotal ?? 0),
        escposLabels: {
          terminal: t("print.a4.terminal"), item: t("print.a4.description"),
          quantity: t("print.a4.quantity"), price: t("print.a4.unitPrice"),
          base: t("print.a4.base"), tax: t("print.a4.tax"), total: t("print.a4.total")
        },
        lines: snapshot.lines.map((line) => ({
          name: line.name, quantity: Number(line.quantity), price: Number(line.unitPrice ?? line.price),
          total: Number(line.total), taxesIncluded: line.taxesIncluded,
          ...(line.serialNumbers?.length ? { serialNumbers: line.serialNumbers } : {})
        })),
        payments: [], total: Number(snapshot.total)
      }, config);
      return result.ok ? { status: "PRINTED" } : { status: "FAILED", technicalMessage: result.message };
    }
    const result = await hardware.printA4Document(
      pendingAsA4Document(snapshot, terminal, locale),
      config,
    );
    return result.ok
      ? { status: "PRINTED" }
      : { status: "FAILED", technicalMessage: result.message };
  } catch (error) {
    return failedOutcome(error);
  }
}

export async function printCustomerReceivablePaymentReceipt(
  snapshot: CustomerReceivablePaymentReceiptSnapshot,
  terminal: TerminalContext,
  hardware: HardwareBridge = getHardwareBridge(),
  locale: LocaleCode = "es"
): Promise<TicketPrintOutcome> {
  try {
    const t = createTranslator(locale);
    const config = await hardware.getHardwareConfig();
    const amount = Number(snapshot.amount);
    const result = await hardware.printTicket({
      documentNumber: `${t("receivables.print.collection")} ${snapshot.documentNumber} / ${snapshot.paymentId}`,
      storeName: terminal.storeName,
      terminalCode: terminal.terminalCode,
      issuedAt: snapshot.collectedAt,
      lines: [{
        name: `${t("receivables.print.collectionOf")} ${snapshot.documentNumber} · ${t("receivables.column.pending")} ${Number(snapshot.remaining).toFixed(2)}`,
        quantity: 1,
        price: amount,
        total: amount
      }],
      payments: [{ method: snapshot.method, amount }],
      total: amount,
      labels: { terminal: t("print.a4.terminal"), item: t("print.ticket.item"),
        quantity: t("print.ticket.quantity"), price: t("print.ticket.price"), total: t("print.a4.total") },
      escposLabels: locale === "zh"
        ? { terminal: "Zhongduan", item: "Shangpin", quantity: "Shuliang", price: "Jiage", total: "Zongji" }
        : { terminal: "Terminal", item: locale === "es" ? "Articulo" : "Item", quantity: locale === "es" ? "Cant." : "Qty.", price: locale === "es" ? "Precio" : "Price", total: locale === "es" ? "TOTAL" : "Total" },
      escposContent: locale === "zh" ? {
        storeName: "Dianpu", terminalCode: `terminal-${terminal.terminalCode.replace(/[^A-Za-z0-9_-]/g, "").replace(/^-+/, "") || "local"}`,
        documentNumber: `Shoukuan ${snapshot.paymentId}`,
        lineNames: [`Kehu ${snapshot.documentNumber.replace(/[^\x20-\x7e]/g, "") || snapshot.paymentId}`],
        paymentMethods: ["Fangshi CARD"]
      } : undefined
    }, config);
    return result.ok
      ? { status: "PRINTED" }
      : { status: "FAILED", technicalMessage: result.message };
  } catch (error) {
    return failedOutcome(error);
  }
}
