import { getHardwareBridge } from "../hardware/hardware";
import type {
  A4DocumentPrintRequest,
  DocumentPrintRoute,
  FiscalPrintSnapshot,
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
  phone?: string;
  logo?: string;
  address: { line1?: string; postalCode?: string; city?: string; province?: string; country?: string };
};

export type ConfirmedTicketPrintSnapshot = {
  documentId: string;
  documentNumber: string;
  issuedAt: string;
  lines: Array<{
    code?: string;
    barcode?: string;
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
  checkoutDiscountTotal?: NumericValue;
  memberBalanceTotal?: NumericValue;
  observations?: string;
  logo?: string;
  qrUrl?: string;
  qrImage?: string;
  fiscal?: FiscalPrintSnapshot;
  nonFiscalSummary?: boolean;
  ticketRenderedPdf?: { contentType: "application/pdf"; base64: string };
  ticketRenderedImage?: { contentType: "image/png"; base64: string };
};

export type TicketPrintOutcome = {
  status: "PRINTED" | "FAILED" | "SKIPPED";
  technicalMessage?: string;
  failedDocuments?: Array<{
    documentId: string;
    documentNumber: string;
    technicalMessage?: string;
  }>;
};

export type ConfirmedTicketPrintSet = {
  /** Replacement sale, or the only ticket for non-exchange operations. */
  printTicket: ConfirmedTicketPrintSnapshot;
  /** Ordered related fiscal documents; rectification precedes replacement sale. */
  additionalPrintTickets: ConfirmedTicketPrintSnapshot[];
  /** Optional customer summary. It is never part of automatic fiscal printing. */
  nonFiscalSummary?: ConfirmedTicketPrintSnapshot | null;
};

export type SalePrintMode =
  | "DEFAULT"
  | "TICKET_PRINTER"
  | "A4_PRINTER"
  | "PDF"
  | "NONE";

export type PendingCommercialDocumentPrintSnapshot = {
  kind: "COMMERCIAL_DOCUMENT";
  documentType: "ALBARAN_VENTA" | "FACTURA_VENTA" | "RECTIFICATIVA_VENTA";
  documentNumber: string;
  issuedAt?: string;
  issueDate?: string;
  issuer?: FiscalPartySnapshot;
  customer?: FiscalPartySnapshot;
  lines: Array<{ code?: string; barcode?: string; name: string; quantity: NumericValue; unitPrice?: NumericValue; price?: NumericValue; total: NumericValue; taxesIncluded?: boolean; taxPercentage?: NumericValue; base?: NumericValue; tax?: NumericValue; serialNumbers?: string[] }>;
  payments?: Array<{ method: string; amount: NumericValue; reference?: string }>;
  baseTotal?: NumericValue;
  taxTotal?: NumericValue;
  total: NumericValue;
  fiscalProfile?: "IVA" | "IGIC" | "IGIC_MINORISTA";
  observations?: string;
  bankAccounts?: Array<{ bankName: string; iban: string }>;
  qrUrl?: string;
  qrImage?: string;
  fiscal?: FiscalPrintSnapshot;
  renderedPdf?: { contentType: "application/pdf"; base64: string };
  ticketRenderedPdf?: { contentType: "application/pdf"; base64: string };
  ticketRenderedImage?: { contentType: "image/png"; base64: string };
};

function printableAddress(address: FiscalPartySnapshot["address"] | undefined) {
  if (!address) return "";
  return [address.line1, [address.postalCode, address.city].filter(Boolean).join(" "), address.province, address.country]
    .filter((value, index, values) => Boolean(value) && values.indexOf(value) === index)
    .join(", ");
}

function frozenTicketIssuer(fiscal: FiscalPrintSnapshot | undefined, logo?: string) {
  if (!fiscal?.issuerName || !fiscal.issuerTaxId || !fiscal.issuerAddress) return undefined;
  const address = fiscal.issuerAddress;
  return {
    name: fiscal.issuerName,
    taxId: fiscal.issuerTaxId,
    address: [
      address.linea1,
      [address.codigoPostal, address.ciudad].filter(Boolean).join(" "),
      address.provincia,
      address.pais,
    ].filter((value, index, values) => Boolean(value) && values.indexOf(value) === index).join(", "),
    ...(logo ? { logo } : {}),
  };
}

function frozenA4Issuer(fiscal: FiscalPrintSnapshot | undefined, logo?: string) {
  if (!fiscal?.issuerName || !fiscal.issuerTaxId || !fiscal.issuerAddress) return undefined;
  return {
    name: fiscal.issuerName,
    taxId: fiscal.issuerTaxId,
    phone: fiscal.issuerAddress.telefono,
    logo,
    address: {
      line1: fiscal.issuerAddress.linea1,
      postalCode: fiscal.issuerAddress.codigoPostal,
      city: fiscal.issuerAddress.ciudad,
      province: fiscal.issuerAddress.provincia,
      country: fiscal.issuerAddress.pais,
    },
  };
}

function partyLabels(locale: LocaleCode) {
  if (locale === "en") return { issuer: "Issuer", customer: "Customer", taxId: "Tax ID" };
  if (locale === "zh") return { issuer: "Fang", customer: "Kehu", taxId: "Shuihao" };
  return { issuer: "Emisor", customer: "Cliente", taxId: "NIF" };
}

function invoiceLabels(locale: LocaleCode) {
  if (locale === "en") return { phone: "Phone", paymentMethod: "Payment method", code: "Code" };
  if (locale === "zh") return { phone: "电话", paymentMethod: "付款方式", code: "编码" };
  return { phone: "Teléfono", paymentMethod: "Forma de pago", code: "Código" };
}

function paymentMethodLabel(method: string, locale: LocaleCode) {
  const normalized = method.trim().toUpperCase();
  if (normalized === "CREDITO_DEVOLUCION" || normalized === "MEMBER_CREDIT") {
    return locale === "es" ? "Saldo a favor" : locale === "en" ? "Return credit" : "退货余额";
  }
  if (normalized === "SALDO_MIEMBRO" || normalized === "MEMBER_BALANCE") {
    return locale === "es" ? "Saldo socio" : locale === "en" ? "Member balance" : "会员余额";
  }
  return method;
}

export type CustomerReceivablePaymentReceiptSnapshot = {
  kind: "PAYMENT_RECEIPT";
  paymentId: string;
  documentNumber: string;
  collectedAt: string;
  method: string;
  amount: NumericValue;
  remaining: NumericValue;
  transferDate?: string | null;
  renderedPdf?: { contentType: "application/pdf"; base64: string } | null;
  ticketRenderedImage?: { contentType: "image/png"; base64: string } | null;
};

export function ticketPrintRequest(
  snapshot: ConfirmedTicketPrintSnapshot,
  terminal: TerminalContext,
  locale: LocaleCode = "es",
): TicketPrintRequest {
  const t = createTranslator(locale);
  const labels = {
    terminal: t("print.a4.terminal"),
    item: t("print.ticket.item"),
    quantity: t("print.ticket.quantity"),
    price: t("print.ticket.price"),
    discount: t("print.ticket.discount"),
    base: t("print.a4.base"),
    tax: t("print.a4.tax"),
    total: t("print.a4.total"),
  };
  const fiscalDocument = !snapshot.nonFiscalSummary;
  return {
    requireRenderedDocument: fiscalDocument,
    documentNumber: snapshot.documentNumber,
    storeName: terminal.storeName,
    terminalCode: terminal.terminalCode,
    issuedAt: snapshot.issuedAt,
    lines: snapshot.lines.map((line) => ({
      code: line.code,
      barcode: line.barcode,
      name: line.name,
      quantity: Number(line.quantity),
      price: Number(line.price),
      total: Number(line.total),
      ...(line.serialNumbers?.length ? { serialNumbers: line.serialNumbers } : {})
    })),
    payments: snapshot.payments.map((payment) => ({
      method: paymentMethodLabel(payment.method, locale),
      amount: Number(payment.amount)
    })),
    total: Number(snapshot.total),
    ...(snapshot.baseTotal == null ? {} : { subtotal: Number(snapshot.baseTotal) }),
    ...(snapshot.checkoutDiscountTotal == null
      ? {}
      : { discount: Number(snapshot.checkoutDiscountTotal) }),
    ...(snapshot.memberBalanceTotal == null
      ? {}
      : { memberBalance: Number(snapshot.memberBalanceTotal) }),
    ...(snapshot.taxTotal == null ? {} : { tax: Number(snapshot.taxTotal) }),
    labels: { ...labels, memberBalance: t("print.ticket.memberBalance") },
    escposLabels: { ...labels, memberBalance: t("print.ticket.memberBalance") },
    ...(snapshot.logo ? { logo: snapshot.logo } : {}),
    ...(fiscalDocument && snapshot.qrUrl ? { qrUrl: snapshot.qrUrl } : {}),
    ...(fiscalDocument && snapshot.qrImage ? { qrImage: snapshot.qrImage } : {}),
    ...(fiscalDocument && snapshot.fiscal ? { fiscal: snapshot.fiscal } : {}),
    ...(fiscalDocument && frozenTicketIssuer(snapshot.fiscal, snapshot.logo)
      ? { issuer: frozenTicketIssuer(snapshot.fiscal, snapshot.logo) }
      : {}),
    ...(fiscalDocument && snapshot.ticketRenderedPdf
      ? { renderedPdf: snapshot.ticketRenderedPdf }
      : {}),
    ...(fiscalDocument && snapshot.ticketRenderedImage
      ? { documentRaster: `data:${snapshot.ticketRenderedImage.contentType};base64,${snapshot.ticketRenderedImage.base64}` }
      : {}),
    ...(snapshot.observations ? { notes: [snapshot.observations] } : {}),
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

export function ticketAsA4Document(
  snapshot: ConfirmedTicketPrintSnapshot,
  terminal: TerminalContext,
  locale: LocaleCode,
): A4DocumentPrintRequest {
  const t = createTranslator(locale);
  const fiscalDocument = !snapshot.nonFiscalSummary;
  return {
    requireRenderedDocument: fiscalDocument,
    documentType: "REPORT",
    locale,
    title: `${t("salesReport.tickets")} ${snapshot.documentNumber}`,
    storeName: terminal.storeName,
    terminalCode: terminal.terminalCode,
    issuedAt: snapshot.issuedAt,
    ...(fiscalDocument && snapshot.ticketRenderedPdf
      ? { renderedPdf: snapshot.ticketRenderedPdf }
      : {}),
    ...(fiscalDocument && snapshot.qrUrl ? { qrUrl: snapshot.qrUrl } : {}),
    ...(fiscalDocument && snapshot.qrImage ? { qrImage: snapshot.qrImage } : {}),
    ...(fiscalDocument && snapshot.fiscal ? { fiscal: snapshot.fiscal } : {}),
    ...(fiscalDocument && frozenA4Issuer(snapshot.fiscal, snapshot.logo)
      ? { issuer: frozenA4Issuer(snapshot.fiscal, snapshot.logo) }
      : {}),
    lines: snapshot.lines.map((line) => ({
      name: line.name,
      quantity: Number(line.quantity),
      price: Number(line.price),
      total: Number(line.total),
      ...(line.serialNumbers?.length ? { serialNumbers: line.serialNumbers } : {}),
    })),
    subtotal: Number(snapshot.baseTotal ?? snapshot.total),
    ...(snapshot.checkoutDiscountTotal == null
      ? {}
      : { discount: Number(snapshot.checkoutDiscountTotal) }),
    tax: Number(snapshot.taxTotal ?? 0),
    taxIncluded: true,
    total: Number(snapshot.total),
    ...(snapshot.logo ? { logo: snapshot.logo } : {}),
    ...(snapshot.observations ? { notes: [snapshot.observations] } : {}),
    metadata: snapshot.payments.map((payment) => ({
      label: paymentMethodLabel(payment.method, locale),
      value: Number(payment.amount).toFixed(2),
    })),
    labels: {
      terminal: t("print.a4.terminal"),
      description: t("print.a4.description"),
      quantity: t("print.a4.quantity"),
      unitPrice: t("print.a4.unitPrice"),
      base: t("print.a4.base"),
      discount: t("print.ticket.discount"),
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
      return await sendConfirmedTicket(snapshot, terminal, hardware, config, locale);
    }
    if (mode === "PDF") {
      const safeNumber = snapshot.documentNumber.replace(/[<>:"/\\|?*\u0000-\u001f]/g, "-");
      const result = await hardware.exportTicketPdf(
        ticketPrintRequest(snapshot, terminal, locale),
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
      locale,
    );
  } catch (error) {
    return failedOutcome(error);
  }
}

/**
 * Prints a related fiscal set in the supplied order and keeps the exact failed
 * document references so a retry can target only those snapshots.
 */
export async function outputConfirmedTicketsSequentially(
  snapshots: readonly ConfirmedTicketPrintSnapshot[],
  terminal: TerminalContext,
  mode: SalePrintMode,
  locale: LocaleCode = "es",
  hardware: HardwareBridge = getHardwareBridge(),
): Promise<TicketPrintOutcome> {
  const failedDocuments: NonNullable<TicketPrintOutcome["failedDocuments"]> = [];
  let printed = false;
  for (const snapshot of snapshots) {
    const outcome = await outputConfirmedTicket(snapshot, terminal, mode, locale, hardware);
    if (outcome.status === "PRINTED") printed = true;
    if (outcome.status === "FAILED") {
      failedDocuments.push({
        documentId: snapshot.documentId,
        documentNumber: snapshot.documentNumber,
        technicalMessage: outcome.technicalMessage,
      });
    }
  }
  if (failedDocuments.length > 0) {
    return {
      status: "FAILED",
      failedDocuments,
      technicalMessage: failedDocuments
        .map((failure) => `${failure.documentNumber}: ${failure.technicalMessage ?? "print_failed"}`)
        .join("; "),
    };
  }
  return { status: printed ? "PRINTED" : "SKIPPED" };
}

async function sendConfirmedTicket(
  snapshot: ConfirmedTicketPrintSnapshot,
  terminal: TerminalContext,
  hardware: HardwareBridge,
  config: HardwareConfig,
  locale: LocaleCode = "es",
): Promise<TicketPrintOutcome> {
  const result = await hardware.printTicket(
    ticketPrintRequest(snapshot, terminal, locale),
    config,
  );
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
  return snapshot.documentType === "ALBARAN_VENTA" ? "DELIVERY_NOTE" as const : "INVOICE" as const;
}

function pendingDocumentTitle(
  snapshot: PendingCommercialDocumentPrintSnapshot,
  locale: LocaleCode,
) {
  const t = createTranslator(locale);
  return `${t(snapshot.documentType === "ALBARAN_VENTA" ? "receivables.type.deliveryNote" : "receivables.type.invoice")} ${snapshot.documentNumber}`;
}

export function commercialDocumentAsA4Document(
  snapshot: PendingCommercialDocumentPrintSnapshot,
  terminal: TerminalContext,
  locale: LocaleCode,
  format: "A4" | "TICKET_80" = "A4",
): A4DocumentPrintRequest {
  const t = createTranslator(locale);
  const invoice = invoiceLabels(locale);
  return {
    requireRenderedDocument: true,
    documentType: pendingDocumentType(snapshot),
    locale,
    title: pendingDocumentTitle(snapshot, locale),
    documentNumber: snapshot.documentNumber,
    storeName: terminal.storeName,
    terminalCode: terminal.terminalCode,
    issuedAt: snapshot.issuedAt ?? snapshot.issueDate ?? "",
    issuer: snapshot.issuer,
    customer: snapshot.customer,
    payments: (snapshot.payments ?? []).map((payment) => ({
      method: paymentMethodLabel(payment.method, locale),
      amount: Number(payment.amount),
      reference: payment.reference,
    })),
    fiscalProfile: snapshot.fiscalProfile,
    bankAccounts: snapshot.bankAccounts,
    qrUrl: snapshot.qrUrl,
    qrImage: snapshot.qrImage,
    fiscal: snapshot.fiscal,
    renderedPdf: format === "TICKET_80" ? snapshot.ticketRenderedPdf : snapshot.renderedPdf,
    lines: snapshot.lines.map((line) => ({
      code: line.code,
      barcode: line.barcode,
      name: line.name,
      quantity: Number(line.quantity),
      price: Number(line.unitPrice ?? line.price),
      total: Number(line.total),
      taxesIncluded: line.taxesIncluded,
      taxPercentage: line.taxPercentage == null ? undefined : Number(line.taxPercentage),
      base: line.base == null ? undefined : Number(line.base),
      tax: line.tax == null ? undefined : Number(line.tax),
      ...(line.serialNumbers?.length ? { serialNumbers: line.serialNumbers } : {}),
    })),
    subtotal: Number(snapshot.baseTotal ?? snapshot.total),
    tax: Number(snapshot.taxTotal ?? 0),
    taxIncluded: snapshot.lines.every((line) => line.taxesIncluded !== false) ? true
      : snapshot.lines.every((line) => line.taxesIncluded === false) ? false : "MIXED",
    total: Number(snapshot.total),
    notes: snapshot.observations ? [snapshot.observations] : [],
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
      phone: invoice.phone,
      paymentMethod: invoice.paymentMethod,
      bankDetails: t("gestion.invoicePrint.title"),
      bankName: t("gestion.invoicePrint.bankName"),
      iban: t("gestion.invoicePrint.iban"),
      taxRate: snapshot.fiscalProfile === "IVA" ? "IVA %" : "IGIC %",
      code: invoice.code,
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
        commercialDocumentAsA4Document(snapshot, terminal, locale),
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
        payments: [], total: Number(snapshot.total),
        ...(snapshot.issuer?.logo ? { logo: snapshot.issuer.logo } : {}),
        ...(snapshot.qrUrl ? { qrUrl: snapshot.qrUrl } : {}),
        ...(snapshot.qrImage ? { qrImage: snapshot.qrImage } : {}),
        ...(snapshot.fiscal ? { fiscal: snapshot.fiscal } : {}),
        ...(snapshot.ticketRenderedImage
          ? { documentRaster: `data:${snapshot.ticketRenderedImage.contentType};base64,${snapshot.ticketRenderedImage.base64}` }
          : {}),
        ...(snapshot.observations ? { notes: [snapshot.observations] } : {}),
      }, config);
      return result.ok ? { status: "PRINTED" } : { status: "FAILED", technicalMessage: result.message };
    }
    const result = await hardware.printA4Document(
      commercialDocumentAsA4Document(
        snapshot,
        terminal,
        locale,
        route?.paperSize === "TICKET_80" ? "TICKET_80" : "A4",
      ),
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
      details: snapshot.transferDate
        ? [{ label: t("receivables.column.transferDate"), value: snapshot.transferDate }]
        : undefined,
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
      } : undefined,
      ...(snapshot.renderedPdf ? { renderedPdf: snapshot.renderedPdf } : {}),
      ...(snapshot.ticketRenderedImage ? {
        documentRaster: `data:${snapshot.ticketRenderedImage.contentType};base64,${snapshot.ticketRenderedImage.base64}`,
      } : {}),
    }, config);
    return result.ok
      ? { status: "PRINTED" }
      : { status: "FAILED", technicalMessage: result.message };
  } catch (error) {
    return failedOutcome(error);
  }
}
