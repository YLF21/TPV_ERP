import { getHardwareBridge } from "../hardware/hardware";
import type { HardwareBridge, HardwareConfig, TicketPrintRequest } from "../hardware/hardware";
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
  }>;
  payments: Array<{
    method: string;
    amount: NumericValue;
  }>;
  total: NumericValue;
};

export type TicketPrintOutcome = {
  status: "PRINTED" | "FAILED" | "SKIPPED";
  technicalMessage?: string;
};

export type PendingCommercialDocumentPrintSnapshot = {
  kind: "COMMERCIAL_DOCUMENT";
  documentType: "ALBARAN_VENTA" | "FACTURA_VENTA";
  documentNumber: string;
  issuedAt?: string;
  issueDate?: string;
  issuer?: FiscalPartySnapshot;
  customer?: FiscalPartySnapshot;
  lines: Array<{ name: string; quantity: NumericValue; unitPrice?: NumericValue; price?: NumericValue; total: NumericValue; taxesIncluded?: boolean }>;
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

function ticketPrintRequest(
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
      total: Number(line.total)
    })),
    payments: snapshot.payments.map((payment) => ({
      method: payment.method,
      amount: Number(payment.amount)
    })),
    total: Number(snapshot.total)
  };
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
    const route = config.documentPrintRoutes.find((item) => item.documentType === "TICKET");
    if (route?.printAutomatically === false) return { status: "SKIPPED" };
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

export async function printPendingCommercialDocument(
  snapshot: PendingCommercialDocumentPrintSnapshot,
  terminal: TerminalContext,
  hardware: HardwareBridge = getHardwareBridge(),
  locale: LocaleCode = "es"
): Promise<TicketPrintOutcome> {
  try {
    const t = createTranslator(locale);
    const config = await hardware.getHardwareConfig();
    const documentType = snapshot.documentType === "FACTURA_VENTA" ? "INVOICE" : "DELIVERY_NOTE";
    const title = `${t(snapshot.documentType === "FACTURA_VENTA" ? "receivables.type.invoice" : "receivables.type.deliveryNote")} ${snapshot.documentNumber}`;
    const route = config.documentPrintRoutes.find((item) => item.documentType === documentType);
    const parties = partyLabels(locale);
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
          total: Number(line.total), taxesIncluded: line.taxesIncluded
        })),
        payments: [], total: Number(snapshot.total)
      }, config);
      return result.ok ? { status: "PRINTED" } : { status: "FAILED", technicalMessage: result.message };
    }
    const result = await hardware.printA4Document({
      documentType,
      title,
      storeName: terminal.storeName,
      terminalCode: terminal.terminalCode,
      issuedAt: snapshot.issuedAt ?? snapshot.issueDate ?? "",
      issuer: snapshot.issuer,
      customer: snapshot.customer,
      lines: snapshot.lines.map((line) => ({
        name: line.name,
        quantity: Number(line.quantity),
        price: Number(line.unitPrice ?? line.price),
        total: Number(line.total), taxesIncluded: line.taxesIncluded
      })),
      subtotal: Number(snapshot.baseTotal ?? snapshot.total),
      tax: Number(snapshot.taxTotal ?? 0),
      taxIncluded: snapshot.lines.every((line) => line.taxesIncluded !== false) ? true
        : snapshot.lines.every((line) => line.taxesIncluded === false) ? false : "MIXED",
      total: Number(snapshot.total),
      labels: {
        terminal: t("print.a4.terminal"), description: t("print.a4.description"),
        quantity: t("print.a4.quantity"), unitPrice: t("print.a4.unitPrice"),
        base: t("print.a4.base"), tax: t("print.a4.tax"),
        taxIncluded: t("print.a4.taxIncluded"), yes: t("common.yes"), no: t("common.no"), mixed: t("print.a4.mixed"),
        total: t("print.a4.total"), ...parties
      }
    }, config);
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
