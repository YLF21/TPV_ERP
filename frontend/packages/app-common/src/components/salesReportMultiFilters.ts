import type { LocaleCode } from "../types";
import type { ErpSelectOption } from "./ErpSelect";

export type ReportMultiFilterKey = "user" | "customer" | "supplier" | "payment" | "terminal" | "status" | "warehouse";
export type ReportMultiFilters = Partial<Record<ReportMultiFilterKey, readonly string[]>>;
type Row = Record<string, string>;

const paymentOrder = ["EFECTIVO", "TARJETA", "VALE", "salesReport.payment.pending", "TRANSFERENCIA", "DESCUENTO",
  "salesReport.payment.memberBalance", "salesReport.payment.returnCredit"];
const paymentLabels: Record<string, string> = {
  EFECTIVO: "salesReport.payment.cash", TARJETA: "salesReport.payment.card", VALE: "salesReport.payment.voucher",
  TRANSFERENCIA: "salesReport.payment.transfer", DESCUENTO: "salesReport.column.discount"
};
export function normalizeReportPayment(value: string): string {
  const trimmed = value.trim();
  if (!trimmed || trimmed === "—" || trimmed === "-") return "";
  const normalized = trimmed.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toUpperCase();
  const aliases: Record<string, string> = {
    CASH: "EFECTIVO", EFECTIVO: "EFECTIVO", CARD: "TARJETA", TARJETA: "TARJETA", VOUCHER: "VALE", VALE: "VALE",
    TRANSFER: "TRANSFERENCIA", TRANSFERENCIA: "TRANSFERENCIA", DISCOUNT: "DESCUENTO", DESCUENTO: "DESCUENTO",
    PENDING: "salesReport.payment.pending", PENDIENTE: "salesReport.payment.pending",
    MEMBER_BALANCE: "salesReport.payment.memberBalance", SALDO_MIEMBRO: "salesReport.payment.memberBalance",
    MEMBER_CREDIT: "salesReport.payment.returnCredit", CREDITO_DEVOLUCION: "salesReport.payment.returnCredit"
  };
  return aliases[normalized] ?? trimmed;
}

export function reportPaymentValues(row: Row): string[] {
  let raw = (row.payment ?? "").split(" + ");
  if (row.__filterPaymentMethods) {
    try {
      const parsed: unknown = JSON.parse(row.__filterPaymentMethods);
      if (Array.isArray(parsed) && parsed.every(value => typeof value === "string")) raw = parsed;
    } catch { /* Older report rows still expose their combined payment text. */ }
  }
  return [...new Set(raw.map(normalizeReportPayment).filter(Boolean))];
}

export function reportMultiFilterValueLabel(field: ReportMultiFilterKey, value: string, t: (key: string) => string) {
  return t(field === "payment" ? paymentLabels[value] ?? value : value);
}

export function reportMultiFilterOptions(rows: readonly Row[], field: ReportMultiFilterKey, locale: LocaleCode,
  t: (key: string) => string, retainedValues: readonly string[] = []): ErpSelectOption[] {
  const values = new Map<string, string>();
  for (const row of rows) {
    if (field === "payment") {
      for (const value of reportPaymentValues(row)) values.set(value, t(paymentLabels[value] ?? value));
    } else {
      const value = row[field] || (field === "customer" ? row.customerName : field === "supplier" ? row.supplierName : "");
      if (!value?.trim()) continue;
      const name = field === "customer" ? row.customerName : field === "supplier" ? row.supplierName : "";
      values.set(value, name && name !== value ? `${t(value)} · ${name}` : t(value));
    }
  }
  // Keep selected values available after changing the period, in their normal order.
  for (const value of retainedValues) {
    if (value && !values.has(value)) values.set(value, reportMultiFilterValueLabel(field, value, t));
  }
  return [...values].map(([value, label]) => ({ value, label })).sort((a, b) => {
    if (field === "payment") {
      const rank = (value: string) => paymentOrder.includes(value) ? paymentOrder.indexOf(value) : paymentOrder.length;
      const difference = rank(a.value) - rank(b.value);
      if (difference) return difference;
    }
    return a.label.localeCompare(b.label, locale, { numeric: true, sensitivity: "base" });
  });
}

export function rowMatchesReportMultiFilters(row: Row, filters: ReportMultiFilters): boolean {
  return (Object.entries(filters) as Array<[ReportMultiFilterKey, readonly string[]]>).every(([field, selected]) => {
    if (!selected?.length) return true;
    if (field === "payment") return selected.some(value => reportPaymentValues(row).includes(normalizeReportPayment(value)));
    const value = row[field] || (field === "customer" ? row.customerName : field === "supplier" ? row.supplierName : "");
    return selected.includes(value);
  });
}

export function reportMultiFilterExport(filters: ReportMultiFilters) {
  const names: Record<ReportMultiFilterKey, string> = { user: "users", customer: "customers", supplier: "suppliers",
    payment: "payments", terminal: "terminals", status: "statuses", warehouse: "warehouses" };
  return Object.fromEntries((Object.entries(filters) as Array<[ReportMultiFilterKey, readonly string[]]>)
    .filter(([, values]) => values?.length).map(([key, values]) => [names[key], [...new Set(values)]]));
}
