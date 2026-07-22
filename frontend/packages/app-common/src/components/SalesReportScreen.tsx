import { useEffect, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { apiBaseUrl } from "../api/runtime";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import {
  applySavedVisualizationPreferences,
  loadReportVisualizationPreferences,
  saveReportVisualizationPreference
} from "./salesReportVisualizationPreferences";
import { TopDateTime } from "./TopDateTime";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { visibleTableColumns } from "./tableLayoutPreferences";
import type { TableColumnDefinition } from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";
import type { UseTableLayoutPreferenceResult } from "./useTableLayoutPreference";
import { useOutsidePointerDown } from "./useOutsidePointerDown";
import { readSalesReportOutputPreferences } from "./salesReportOutputPreferences";
import {
  allReports,
  isPurchaseDocumentReport,
  outputReports,
  visibleSalesReports
} from "./salesReportAccess";
export { isPurchaseDocumentReport, salesReportAccess, visibleSalesReports } from "./salesReportAccess";
import languageIcon from "../assets/language.png";
import lockIcon from "../assets/lock.png";
import deliveryNoteIcon from "../assets/reports/delivery-note.png";
import invoiceIcon from "../assets/reports/invoice.png";
import dailySalesIcon from "../assets/reports/daily-sales.png";
import ticketIcon from "../assets/reports/ticket.png";
import warehouseInputIcon from "../assets/reports/warehouse-input.png";
import warehouseOutputIcon from "../assets/reports/warehouse-output.png";
import filterIcon from "../assets/reports/filter.png";
import printIcon from "../assets/reports/print.png";
import searchIcon from "../assets/reports/search.png";
import visualizeIcon from "../assets/reports/visualize.png";
import "../styles/report-print.css";

type SalesReportScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  onBack: () => void;
  onLogout?: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  embedded?: boolean;
  initialReport?: string;
  request?: <T>(path: string, options?: { token?: string }) => Promise<T>;
};

type SalesReportRequest = NonNullable<SalesReportScreenProps["request"]>;

type DailyCommercialReport = {
  storeId: string;
  date: string;
  invoiced: number | string;
  ticketSales?: number | string;
  collectedCurrent: number | string;
  newPending: number | string;
  priorDebtCollected: number | string;
  cashInflow: number | string;
};

const languageOptions: Array<{ code: LocaleCode; label: string }> = [
  { code: "es", label: "Español" },
  { code: "en", label: "English" },
  { code: "zh", label: "中文" }
];

function apiServerLabel() {
  if (apiBaseUrl.startsWith("/")) {
    return "local";
  }
  try {
    return new URL(apiBaseUrl).host;
  } catch {
    return apiBaseUrl;
  }
}

function currentOnlineStatus() {
  return typeof navigator === "undefined" ? false : navigator.onLine;
}

const reportIcon: Record<string, string> = {
  "salesReport.dailySales": dailySalesIcon,
  "salesReport.tickets": ticketIcon,
  "salesReport.deliveryNotes": deliveryNoteIcon,
  "salesReport.invoices": invoiceIcon,
  "salesReport.warehouseOutputs": warehouseOutputIcon,
  "salesReport.inputDeliveryNotes": deliveryNoteIcon,
  "salesReport.inputInvoices": invoiceIcon,
  "salesReport.inputWarehouse": warehouseInputIcon
};

const attributeLabelKey: Record<string, string> = {
  date: "salesReport.column.date",
  time: "salesReport.column.time",
  ticket: "salesReport.column.ticket",
  invoice: "salesReport.column.invoice",
  invoiced: "salesReport.column.invoiced",
  deliveryNote: "salesReport.column.deliveryNote",
  terminal: "salesReport.column.terminal",
  user: "salesReport.column.user",
  productCount: "salesReport.column.productCount",
  customer: "salesReport.column.customerId",
  customerName: "salesReport.column.customerName",
  supplier: "salesReport.column.supplier",
  supplierName: "salesReport.column.supplierName",
  comment: "salesReport.column.comment",
  warehouse: "salesReport.column.warehouse",
  input: "salesReport.column.input",
  output: "salesReport.column.output",
  total: "salesReport.column.total",
  pending: "salesReport.column.pending",
  payment: "salesReport.column.payment",
  status: "salesReport.column.status",
  reason: "salesReport.column.reason",
  origin: "salesReport.column.origin",
  dueDate: "salesReport.column.dueDate",
  tickets: "salesReport.column.tickets"
};

const attributeDefaultWidth: Record<string, number> = {
  date: 112,
  time: 80,
  ticket: 128,
  invoice: 128,
  invoiced: 104,
  deliveryNote: 144,
  terminal: 112,
  user: 144,
  productCount: 120,
  customer: 128,
  customerName: 200,
  supplier: 128,
  supplierName: 200,
  comment: 240,
  warehouse: 160,
  input: 128,
  output: 128,
  total: 112,
  pending: 112,
  payment: 152,
  status: 128,
  reason: 184,
  origin: 184,
  dueDate: 112,
  tickets: 88
};

type ReportSample = {
  availableAttributes: string[];
  defaultVisibleAttributes: string[];
  rows: Array<Record<string, string>>;
  totals: Record<string, string>;
  dailySummaries?: Record<string, DailySalesSummary>;
};

type ReportTableLayout = UseTableLayoutPreferenceResult<string>;

export function buildReportColumnDefinitions(report: ReportSample): TableColumnDefinition[] {
  const defaultVisible = new Set(report.defaultVisibleAttributes);
  return report.availableAttributes.map((attribute) => ({
    key: attribute,
    defaultWidth: attributeDefaultWidth[attribute] ?? 144,
    defaultVisible: defaultVisible.has(attribute)
  }));
}

export function reportTableKey(reportKey: string) {
  return `reports.${reportKey}`;
}

export function moveVisibleReportColumn(
  tableLayout: ReportTableLayout,
  attribute: string,
  direction: -1 | 1
) {
  const movableColumns = visibleTableColumns(tableLayout.layout)
    .filter((column) => column.key !== "total");
  const visibleIndex = movableColumns.findIndex((column) => column.key === attribute);
  const target = movableColumns[visibleIndex + direction];
  if (visibleIndex < 0 || !target) {
    return;
  }

  const layoutIndex = tableLayout.layout.findIndex((column) => column.key === attribute);
  const targetLayoutIndex = tableLayout.layout.findIndex((column) => column.key === target.key);
  const moveCount = Math.abs(targetLayoutIndex - layoutIndex);
  for (let index = 0; index < moveCount; index += 1) {
    tableLayout.moveColumn(attribute, direction);
  }
}

export function moveReportColumnBeforeTotal(tableLayout: ReportTableLayout, attribute: string) {
  if (attribute === "total") {
    return;
  }
  const columnIndex = tableLayout.layout.findIndex((column) => column.key === attribute);
  const totalIndex = tableLayout.layout.findIndex((column) => column.key === "total");
  if (columnIndex < 0 || totalIndex < 0) {
    return;
  }

  if (!tableLayout.layout[columnIndex].visible) {
    tableLayout.toggleColumnVisibility(attribute);
  }

  const direction: -1 | 1 = columnIndex < totalIndex ? 1 : -1;
  const targetIndex = columnIndex < totalIndex ? totalIndex - 1 : totalIndex;
  const moveCount = Math.abs(targetIndex - columnIndex);
  for (let index = 0; index < moveCount; index += 1) {
    tableLayout.moveColumn(attribute, direction);
  }
}

export function normalizeRequiredTotal(tableLayout: ReportTableLayout) {
  const totalIndex = tableLayout.layout.findIndex((column) => column.key === "total");
  if (totalIndex < 0) {
    return;
  }
  if (!tableLayout.layout[totalIndex].visible) {
    tableLayout.toggleColumnVisibility("total");
  }
  for (let index = totalIndex; index < tableLayout.layout.length - 1; index += 1) {
    tableLayout.moveColumn("total", 1);
  }
}

type DocumentPaymentView = {
  methodName?: string;
  amount?: number | string;
  paymentDate?: string;
  createdAt?: string;
  creadoEn?: string;
};

type DocumentView = {
  id?: string;
  tipo?: string;
  estado?: string;
  numero?: string;
  numeroExterno?: string | null;
  fecha?: string;
  fechaVencimiento?: string | null;
  pendiente?: number | string;
  descuentoGlobal?: number | string;
  total?: number | string;
  numTicket?: string | null;
  origenStock?: boolean;
  clienteId?: string | null;
  clienteCodigo?: string | null;
  clienteNombre?: string | null;
  proveedorId?: string | null;
  proveedorCodigo?: string | null;
  proveedorNombre?: string | null;
  almacenId?: string | null;
  almacenNombre?: string | null;
  lineas?: number | string;
  payments?: DocumentPaymentView[];
  usuario?: string;
  user?: string;
  userName?: string;
  vendedor?: string;
  usuarioId?: string | null;
  usuarioNombre?: string | null;
  terminalOrigenId?: string | null;
  terminalOrigenNombre?: string | null;
  ocurridoEn?: string | null;
};

type PagedResult<T> = {
  items: T[];
  nextCursor?: string | null;
  hasMore?: boolean;
};

type DocumentOperationalEventType = "CREADO" | "CONFIRMADO" | "ANULADO" | "MODIFICADO" | "COBRADO" | "CONVERTIDO" | "RECTIFICADO";

type DocumentOperationalTimeline = {
  documentId: string;
  documentType: string;
  documentStatus: string;
  documentNumber?: string | null;
  documentDate: string;
  originUserId?: string | null;
  originUserName?: string | null;
  originTerminalId?: string | null;
  originTerminalName?: string | null;
  events: Array<{
    id: string;
    type: DocumentOperationalEventType;
    userId: string;
    userName?: string | null;
    terminalId?: string | null;
    terminalName?: string | null;
    occurredAt: string;
    data?: Record<string, unknown> | null;
  }>;
};

export function loadDocumentOperationalTimeline(documentId: string, token?: string) {
  return apiRequest<DocumentOperationalTimeline>(
    `/documents/${encodeURIComponent(documentId)}/operational-events`,
    { token }
  );
}

export function canOpenOperationalTimeline(
  app: AppKind,
  session: Pick<UserSession, "permissions">,
  reportKey: string,
  row: Record<string, string> | undefined
) {
  if (app !== "gestion" || !row?.__documentId) return false;
  if (session.permissions.includes("ADMIN")) return true;
  if (!session.permissions.includes("APP_GESTION_ACCESS")) return false;
  if (isPurchaseDocumentReport(reportKey)) {
    return session.permissions.some((permission) => ["GESTION_PRODUCTO", "GESTION_ALMACEN", "GESTION_CUENTAS"].includes(permission));
  }
  return session.permissions.includes("GESTION_VENTAS");
}

type WarehouseOutputView = {
  id?: string;
  number?: string | null;
  numero?: string | null;
  date?: string;
  fecha?: string;
  warehouseId?: string;
  almacenId?: string;
  destination?: string | null;
  destino?: string | null;
  concept?: string | null;
  concepto?: string | null;
  status?: string;
  estado?: string;
  lines?: Array<{ productId?: string; productoId?: string; quantity?: number | string; cantidad?: number | string }>;
};

type WarehouseInputView = {
  id?: string;
  number?: string | null;
  numero?: string | null;
  date?: string;
  fecha?: string;
  warehouseId?: string;
  almacenId?: string;
  supplierId?: string | null;
  proveedorId?: string | null;
  origin?: string | null;
  origen?: string | null;
  concept?: string | null;
  concepto?: string | null;
  status?: string;
  estado?: string;
  lines?: Array<{ productId?: string; productoId?: string; quantity?: number | string; cantidad?: number | string }>;
};

type StockMovementView = {
  id?: string;
  productId?: string;
  warehouseId?: string;
  userId?: string;
  type?: string;
  tipo?: string;
  quantity?: number | string;
  cantidad?: number | string;
  reason?: string | null;
  motivo?: string | null;
  createdAt?: string;
  creadoEn?: string;
  documentId?: string | null;
  warehouseOutputId?: string | null;
};

const REPORT_PAGE_LIMIT = 500;

type DailyPaymentLine = {
  method: string;
  operations: number;
  amount: number;
};

type DailyUserSummary = {
  user: string;
  payments: DailyPaymentLine[];
  invoicedTotal: number;
  newPending: DailyPaymentLine;
  pendingCollections: DailyPaymentLine[];
  total: number;
};

type DailySalesSummary = {
  date: string;
  payments: DailyPaymentLine[];
  invoicedTotal: number;
  newPending: DailyPaymentLine;
  pendingCollections: DailyPaymentLine[];
  total: number;
  users: DailyUserSummary[];
};

type ReportFilters = {
  dateFrom: string;
  dateTo: string;
  user: string;
  customer: string;
  supplier: string;
  payment: string;
  terminal: string;
  status: string;
  warehouse: string;
};

const emptyFilters: ReportFilters = {
  dateFrom: "",
  dateTo: "",
  user: "",
  customer: "",
  supplier: "",
  payment: "",
  terminal: "",
  status: "",
  warehouse: ""
};

type SelectFilterKey = "user" | "payment" | "terminal" | "status" | "warehouse";
type FilterOption = { value: string; label: string };

function createDefaultFilters(): ReportFilters {
  const today = toIsoDate(new Date());
  return { ...emptyFilters, dateFrom: today, dateTo: today };
}

const reportSamples: Record<string, ReportSample> = {
  "salesReport.dailySales": {
    availableAttributes: ["date", "user", "terminal", "tickets", "invoice", "comment", "total"],
    defaultVisibleAttributes: ["date", "user", "terminal", "tickets", "invoice", "total"],
    rows: [],
    totals: { date: "salesReport.total", tickets: "0", invoice: "0", invoicedTicketTotal: "0.00", total: "0.00" }
  },
  "salesReport.tickets": {
    availableAttributes: ["date", "time", "ticket", "invoiced", "terminal", "user", "productCount", "customer", "customerName", "payment", "comment", "total"],
    defaultVisibleAttributes: ["date", "time", "terminal", "productCount", "customer", "payment", "invoiced", "total"],
    rows: [],
    totals: { date: "salesReport.total", productCount: "0", invoiced: "0", total: "0.00" }
  },
  "salesReport.deliveryNotes": {
    availableAttributes: ["date", "time", "deliveryNote", "terminal", "user", "customer", "customerName", "comment", "status", "total"],
    defaultVisibleAttributes: ["deliveryNote", "customer", "date", "status", "total"],
    rows: [],
    totals: { deliveryNote: "salesReport.total", status: "0", total: "0.00" }
  },
  "salesReport.invoices": {
    availableAttributes: ["date", "time", "invoice", "terminal", "user", "customer", "customerName", "payment", "pending", "comment", "total"],
    defaultVisibleAttributes: ["invoice", "customer", "payment", "pending", "total"],
    rows: [],
    totals: { invoice: "salesReport.total", pending: "0.00", total: "0.00" }
  },
  "salesReport.warehouseOutputs": {
    availableAttributes: ["date", "time", "output", "terminal", "user", "warehouse", "productCount", "comment", "reason", "total"],
    defaultVisibleAttributes: ["output", "warehouse", "productCount", "reason", "total"],
    rows: [],
    totals: { output: "salesReport.total", productCount: "0", total: "0.00" }
  },
  "salesReport.inputDeliveryNotes": {
    availableAttributes: ["date", "time", "deliveryNote", "terminal", "user", "supplier", "supplierName", "warehouse", "productCount", "pending", "comment", "total"],
    defaultVisibleAttributes: ["deliveryNote", "supplier", "productCount", "pending", "date", "total"],
    rows: [],
    totals: { deliveryNote: "salesReport.total", productCount: "0", pending: "0.00", total: "0.00" }
  },
  "salesReport.inputInvoices": {
    availableAttributes: ["date", "time", "invoice", "terminal", "user", "supplier", "supplierName", "warehouse", "pending", "dueDate", "comment", "status", "total"],
    defaultVisibleAttributes: ["invoice", "supplier", "dueDate", "status", "pending", "total"],
    rows: [],
    totals: { invoice: "salesReport.total", status: "0", pending: "0.00", total: "0.00" }
  },
  "salesReport.inputWarehouse": {
    availableAttributes: ["date", "time", "input", "terminal", "user", "warehouse", "productCount", "comment", "origin", "total"],
    defaultVisibleAttributes: ["input", "warehouse", "productCount", "origin", "total"],
    rows: [],
    totals: { input: "salesReport.total", productCount: "0", total: "0.00" }
  }
};

function parseReportDate(value: string) {
  const [day, month, year] = value.split("/");
  if (!day || !month || !year) {
    return "";
  }
  return `${year}-${month.padStart(2, "0")}-${day.padStart(2, "0")}`;
}

function parseIsoDate(value: string) {
  const [year, month, day] = value.split("-").map(Number);
  if (!year || !month || !day) {
    return null;
  }
  return new Date(year, month - 1, day);
}

function toIsoDate(value: Date) {
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, "0");
  const day = String(value.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function startOfMonth(value: Date) {
  return new Date(value.getFullYear(), value.getMonth(), 1);
}

function formatFilterDate(value: string, locale: LocaleCode) {
  const date = parseIsoDate(value);
  if (!date) {
    return "";
  }
  const browserLocale = locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES";
  return new Intl.DateTimeFormat(browserLocale).format(date);
}

function formatDateRange(filters: ReportFilters, locale: LocaleCode) {
  const from = formatFilterDate(filters.dateFrom, locale);
  const to = formatFilterDate(filters.dateTo, locale);
  if (!from && !to) {
    return "";
  }
  if (!to || from === to) {
    return from;
  }
  return `${from}-${to}`;
}

function formatSingleFilterDate(filters: ReportFilters, locale: LocaleCode) {
  return formatFilterDate(filters.dateFrom || filters.dateTo, locale);
}

function formatDateFilterText(filters: ReportFilters, locale: LocaleCode, singleDay: boolean) {
  return singleDay ? formatSingleFilterDate(filters, locale) : formatDateRange(filters, locale);
}

function dateRangeDayCount(from: string, to: string) {
  const start = parseIsoDate(from);
  const end = parseIsoDate(to || from);
  if (!start || !end) {
    return 0;
  }
  return Math.max(1, Math.round((end.getTime() - start.getTime()) / 86_400_000) + 1);
}

function selectedDaysText(count: number, locale: LocaleCode) {
  if (locale === "zh") {
    return `已选择 ${count} 天`;
  }
  if (locale === "en") {
    return `${count} days selected`;
  }
  return `${count} días seleccionados`;
}

function parseManualDate(value: string) {
  const trimmed = value.trim();
  const separated = trimmed.match(/^(\d{1,2})[\/\-.](\d{1,2})[\/\-.](\d{2}|\d{4})$/);
  if (separated) {
    return buildIsoDate(separated[1], separated[2], separated[3]);
  }
  const digits = trimmed.replace(/\D/g, "");
  if (digits.length === 6) {
    return buildIsoDate(digits.slice(0, 2), digits.slice(2, 4), digits.slice(4, 6));
  }
  if (digits.length === 8) {
    return buildIsoDate(digits.slice(0, 2), digits.slice(2, 4), digits.slice(4, 8));
  }
  return "";
}

function buildIsoDate(dayValue: string, monthValue: string, yearValue: string) {
  const day = Number(dayValue);
  const month = Number(monthValue);
  const year = Number(yearValue.length === 2 ? `20${yearValue}` : yearValue);
  const date = new Date(year, month - 1, day);
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) {
    return "";
  }
  return toIsoDate(date);
}

function normalizeDateRange(from: string, to: string) {
  if (!from && !to) {
    return null;
  }
  const start = from || to;
  const end = to || from;
  return start <= end ? { dateFrom: start, dateTo: end } : { dateFrom: end, dateTo: start };
}

function parseDateRangeInput(value: string) {
  const trimmed = value.trim();
  if (!trimmed) {
    return null;
  }
  const compact = trimmed.replace(/\D/g, "");
  const spacedDashRange = trimmed.match(/^(.+?)\s+-\s+(.+)$/);
  if (spacedDashRange) {
    return normalizeDateRange(parseManualDate(spacedDashRange[1]), parseManualDate(spacedDashRange[2]));
  }
  const slashOrDotRange = trimmed.match(/^(\d{1,2}[/.]\d{1,2}[/.](?:\d{2}|\d{4}))\s*-\s*(\d{1,2}[/.]\d{1,2}[/.](?:\d{2}|\d{4}))$/);
  if (slashOrDotRange) {
    return normalizeDateRange(parseManualDate(slashOrDotRange[1]), parseManualDate(slashOrDotRange[2]));
  }
  const compactDashRange = trimmed.match(/^(\d{6}|\d{8})\s*-\s*(\d{6}|\d{8})$/);
  if (compactDashRange) {
    return normalizeDateRange(parseManualDate(compactDashRange[1]), parseManualDate(compactDashRange[2]));
  }
  if (/^\d+$/.test(trimmed) && compact.length === 16) {
    return normalizeDateRange(parseManualDate(compact.slice(0, 8)), parseManualDate(compact.slice(8, 16)));
  }
  if (/^\d+$/.test(trimmed) && compact.length === 12) {
    return normalizeDateRange(parseManualDate(compact.slice(0, 6)), parseManualDate(compact.slice(6, 12)));
  }
  const singleDate = parseManualDate(trimmed);
  if (singleDate) {
    return normalizeDateRange(singleDate, singleDate);
  }
  if (/^\d+$/.test(trimmed) && compact.length === 8) {
    const date = parseManualDate(compact);
    return normalizeDateRange(date, date);
  }
  if (/^\d+$/.test(trimmed) && compact.length === 6) {
    const date = parseManualDate(compact);
    return normalizeDateRange(date, date);
  }
  return null;
}

function buildCalendarDays(month: Date) {
  const firstDay = startOfMonth(month);
  const firstWeekday = (firstDay.getDay() + 6) % 7;
  const daysInMonth = new Date(firstDay.getFullYear(), firstDay.getMonth() + 1, 0).getDate();
  const blanks = Array.from({ length: firstWeekday }, () => null);
  const days = Array.from({ length: daysInMonth }, (_, index) => new Date(firstDay.getFullYear(), firstDay.getMonth(), index + 1));
  return [...blanks, ...days];
}

function weekdayLabels(locale: LocaleCode) {
  if (locale === "zh") {
    return ["一", "二", "三", "四", "五", "六", "日"];
  }
  if (locale === "en") {
    return ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
  }
  return ["L", "M", "X", "J", "V", "S", "D"];
}

function parseAmount(value: string) {
  const normalized = value.replace(/,/g, "");
  const amount = Number(normalized);
  return Number.isFinite(amount) ? amount : 0;
}

function formatAmount(value: number) {
  return value.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatWholeNumber(value: number) {
  return value.toLocaleString("en-US", { maximumFractionDigits: 0 });
}

function normalizeSearchText(value: string) {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();
}

function rowMatchesFilters(row: Record<string, string>, filters: ReportFilters) {
  const rowDate = parseReportDate(row.date ?? "");
  const customerNeedle = filters.customer.trim().toLowerCase();
  const supplierNeedle = filters.supplier.trim().toLowerCase();
  const customerText = [row.customer, row.customerName, row.supplier, row.supplierName]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();
  const supplierText = [row.supplier, row.supplierName]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();

  return (
    (!filters.dateFrom || rowDate >= filters.dateFrom) &&
    (!filters.dateTo || rowDate <= filters.dateTo) &&
    (!filters.user || row.user === filters.user) &&
    (!customerNeedle || customerText.includes(customerNeedle)) &&
    (!supplierNeedle || supplierText.includes(supplierNeedle)) &&
    (!filters.payment || row.payment === filters.payment) &&
    (!filters.terminal || row.terminal === filters.terminal) &&
    (!filters.status || row.status === filters.status || row.payment === filters.status) &&
    (!filters.warehouse || row.warehouse === filters.warehouse)
  );
}

function rowMatchesSearch(row: Record<string, string>, search: string, translate: (key: string) => string) {
  const needle = normalizeSearchText(search.trim());
  if (!needle) {
    return true;
  }
  const haystack = Object.values(row)
    .flatMap((value) => [value, translate(value)])
    .map(normalizeSearchText)
    .join(" ");
  return haystack.includes(needle);
}

function buildFilteredTotals(sample: ReportSample, rows: Array<Record<string, string>>) {
  return Object.fromEntries(
    Object.keys(sample.totals).map((attribute) => {
      const originalValue = sample.totals[attribute];
      if (originalValue === "salesReport.total") {
        return [attribute, originalValue];
      }
      if (["total", "pending", "invoicedTicketTotal"].includes(attribute)) {
        return [attribute, formatAmount(rows.reduce((sum, row) => sum + parseAmount(row[attribute] ?? ""), 0))];
      }
      if (["tickets", "invoice", "productCount", "invoiced"].includes(attribute)) {
        return [attribute, formatWholeNumber(rows.reduce((sum, row) => sum + parseAmount(row[attribute] ?? ""), 0))];
      }
      if (attribute === "status") {
        return [attribute, formatWholeNumber(rows.length)];
      }
      return [attribute, rows.length ? originalValue : ""];
    })
  );
}

function formatBackendDate(value: string | undefined) {
  if (!value) {
    return "";
  }
  const datePart = value.slice(0, 10);
  if (/^\d{4}-\d{2}-\d{2}$/.test(datePart)) {
    value = datePart;
  }
  const [year, month, day] = value.split("-");
  if (!year || !month || !day) {
    return value;
  }
  return `${day.padStart(2, "0")}/${month.padStart(2, "0")}/${year}`;
}

function formatBackendTime(value: string | undefined) {
  if (!value) {
    return "";
  }
  const instant = new Date(value);
  if (!Number.isNaN(instant.getTime())) {
    return `${String(instant.getHours()).padStart(2, "0")}:${String(instant.getMinutes()).padStart(2, "0")}`;
  }
  const match = value.match(/T(\d{2}):(\d{2})/);
  return match ? `${match[1]}:${match[2]}` : "";
}

function formatQuantity(value: number | string | undefined) {
  const quantity = Number(value ?? 0);
  if (!Number.isFinite(quantity)) {
    return "0";
  }
  return quantity.toLocaleString("en-US", { maximumFractionDigits: 3 });
}

function sumOutputQuantity(output: WarehouseOutputView) {
  return (output.lines ?? []).reduce((sum, line) => sum + Number(line.quantity ?? line.cantidad ?? 0), 0);
}

function sumInputQuantity(input: WarehouseInputView) {
  return (input.lines ?? []).reduce((sum, line) => sum + Number(line.quantity ?? line.cantidad ?? 0), 0);
}

function movementType(movement: StockMovementView) {
  return movement.type ?? movement.tipo ?? "";
}

function movementQuantity(movement: StockMovementView) {
  return Number(movement.quantity ?? movement.cantidad ?? 0);
}

function isInputMovement(movement: StockMovementView) {
  return movementQuantity(movement) > 0 && !["TICKET", "FACTURA_VENTA", "ALBARAN_VENTA"].includes(movementType(movement));
}

function paymentText(document: DocumentView) {
  const names = Array.from(new Set((document.payments ?? []).map((payment) => payment.methodName).filter(Boolean)));
  return names.join(" + ");
}

function paymentDate(payment: DocumentPaymentView) {
  return (payment.paymentDate || payment.createdAt || payment.creadoEn || "").slice(0, 10);
}

function documentUser(document: DocumentView, fallbackUser: string) {
  return document.usuarioNombre || document.usuario || document.user || document.userName || document.vendedor || fallbackUser;
}

function documentTerminal(document: DocumentView, fallbackTerminal: string) {
  return document.terminalOrigenNombre || fallbackTerminal;
}

function paidAmount(document: DocumentView) {
  return (document.payments ?? []).reduce((sum, payment) => sum + Number(payment.amount ?? 0), 0);
}

function pendingAmount(document: DocumentView) {
  if (document.pendiente !== undefined) {
    return Number(document.pendiente ?? 0);
  }
  return Math.max(0, Number(document.total ?? 0) - paidAmount(document));
}

function documentStatus(document: DocumentView) {
  const status = (document.estado ?? "").toUpperCase();
  if (status.includes("PENDIENTE")) {
    return "salesReport.status.pending";
  }
  if (status.includes("PAG")) {
    return "salesReport.status.paid";
  }
  if (status.includes("FACT")) {
    return "salesReport.status.invoiced";
  }
  return status;
}

function isPurchaseDocument(document: DocumentView) {
  return (document.tipo ?? "").includes("_COMPRA");
}

function isSalesDocument(document: DocumentView) {
  return !isPurchaseDocument(document);
}

function addPaymentLine(lines: Map<string, DailyPaymentLine>, method: string, amount: number) {
  const normalizedMethod = method || "salesReport.payment.pending";
  const current = lines.get(normalizedMethod) ?? { method: normalizedMethod, operations: 0, amount: 0 };
  current.operations += 1;
  current.amount += amount;
  lines.set(normalizedMethod, current);
}

function sortedPaymentLines(lines: Map<string, DailyPaymentLine>) {
  return Array.from(lines.values()).sort((left, right) => left.method.localeCompare(right.method));
}

function buildDailySalesSummary(date: string, documents: DocumentView[], allDocuments: DocumentView[], fallbackUser: string): DailySalesSummary {
  const storePayments = new Map<string, DailyPaymentLine>();
  const storeNewPending: DailyPaymentLine = { method: "salesReport.daily.newPending", operations: 0, amount: 0 };
  const storePendingCollections = new Map<string, DailyPaymentLine>();
  const users = new Map<string, { payments: Map<string, DailyPaymentLine>; newPending: DailyPaymentLine; pendingCollections: Map<string, DailyPaymentLine> }>();

  documents.forEach((document) => {
    const user = documentUser(document, fallbackUser);
    const userSummary = users.get(user) ?? {
      payments: new Map<string, DailyPaymentLine>(),
      newPending: { method: "salesReport.daily.newPending", operations: 0, amount: 0 },
      pendingCollections: new Map<string, DailyPaymentLine>()
    };
    const payments = document.payments ?? [];
    const documentPending = pendingAmount(document);

    payments.forEach((payment) => {
      const amount = Number(payment.amount ?? 0);
      addPaymentLine(storePayments, payment.methodName ?? "", amount);
      addPaymentLine(userSummary.payments, payment.methodName ?? "", amount);
    });

    if (documentPending > 0) {
      storeNewPending.operations += 1;
      storeNewPending.amount += documentPending;
      userSummary.newPending.operations += 1;
      userSummary.newPending.amount += documentPending;
    }
    users.set(user, userSummary);
  });

  allDocuments.forEach((document) => {
    if (document.fecha === date) {
      return;
    }
    const user = documentUser(document, fallbackUser);
    const userSummary = users.get(user) ?? {
      payments: new Map<string, DailyPaymentLine>(),
      newPending: { method: "salesReport.daily.newPending", operations: 0, amount: 0 },
      pendingCollections: new Map<string, DailyPaymentLine>()
    };
    (document.payments ?? []).forEach((payment) => {
      if (paymentDate(payment) !== date) {
        return;
      }
      const amount = Number(payment.amount ?? 0);
      addPaymentLine(storePendingCollections, payment.methodName ?? "", amount);
      addPaymentLine(userSummary.pendingCollections, payment.methodName ?? "", amount);
    });
    users.set(user, userSummary);
  });

  const storePaidTotal = Array.from(storePayments.values()).reduce((sum, payment) => sum + payment.amount, 0);
  const storePendingCollectionTotal = Array.from(storePendingCollections.values()).reduce((sum, payment) => sum + payment.amount, 0);
  const storeInvoicedTotal = storePaidTotal + storeNewPending.amount;
  return {
    date,
    payments: sortedPaymentLines(storePayments),
    invoicedTotal: storeInvoicedTotal,
    newPending: storeNewPending,
    pendingCollections: sortedPaymentLines(storePendingCollections),
    total: storeInvoicedTotal - storeNewPending.amount + storePendingCollectionTotal,
    users: Array.from(users.entries())
      .map(([user, summary]) => ({
        user,
        payments: sortedPaymentLines(summary.payments),
        invoicedTotal: Array.from(summary.payments.values()).reduce((sum, payment) => sum + payment.amount, 0) + summary.newPending.amount,
        newPending: summary.newPending,
        pendingCollections: sortedPaymentLines(summary.pendingCollections),
        total: Array.from(summary.payments.values()).reduce((sum, payment) => sum + payment.amount, 0)
          + Array.from(summary.pendingCollections.values()).reduce((sum, payment) => sum + payment.amount, 0)
      }))
      .sort((left, right) => left.user.localeCompare(right.user))
  };
}

function buildDailySalesSummaries(documents: DocumentView[], fallbackUser: string): Record<string, DailySalesSummary> {
  const byDate = new Map<string, DocumentView[]>();
  documents.forEach((document) => {
    const date = document.fecha ?? "";
    byDate.set(date, [...(byDate.get(date) ?? []), document]);
    (document.payments ?? []).forEach((payment) => {
      const date = paymentDate(payment);
      if (date && date !== document.fecha && !byDate.has(date)) {
        byDate.set(date, []);
      }
    });
  });
  return Object.fromEntries(
    Array.from(byDate.entries()).map(([date, dailyDocuments]) => [date, buildDailySalesSummary(date, dailyDocuments, documents, fallbackUser)])
  );
}

function emptyDailySalesSummary(date: string): DailySalesSummary {
  return {
    date,
    payments: [],
    invoicedTotal: 0,
    newPending: { method: "salesReport.daily.newPending", operations: 0, amount: 0 },
    pendingCollections: [],
    total: 0,
    users: []
  };
}

export function buildDocumentReports(
  tickets: DocumentView[],
  invoices: DocumentView[],
  deliveryNotes: DocumentView[],
  warehouseOutputs: WarehouseOutputView[],
  stockMovements: StockMovementView[],
  warehouseInputs: WarehouseInputView[],
  _session: UserSession,
  _terminalContext: TerminalContext
): Partial<Record<string, ReportSample>> {
  const unavailable = "salesReport.value.unavailable";
  const terminal = unavailable;
  const user = unavailable;
  const ticketRows = tickets.map((document) => ({
    __documentId: document.id || "",
    date: formatBackendDate(document.fecha),
    time: formatBackendTime(document.ocurridoEn ?? undefined),
    ticket: document.numTicket || document.numero || "",
    invoiced: "",
    terminal: documentTerminal(document, terminal),
    user: documentUser(document, user),
    productCount: "",
    customer: "",
    customerName: "",
    payment: paymentText(document),
    comment: "",
    total: formatAmount(Number(document.total ?? 0))
  }));
  const invoiceRows = invoices.filter(isSalesDocument).map((document) => ({
    __documentId: document.id || "",
    date: formatBackendDate(document.fecha),
    time: formatBackendTime(document.ocurridoEn ?? undefined),
    invoice: document.numero || "",
    terminal: documentTerminal(document, terminal),
    user: documentUser(document, user),
    customer: document.clienteCodigo || document.clienteId || "",
    customerName: document.clienteNombre || "",
    payment: paymentText(document),
    pending: formatAmount(pendingAmount(document)),
    comment: document.numeroExterno || "",
    total: formatAmount(Number(document.total ?? 0))
  }));
  const inputInvoiceRows = invoices.filter(isPurchaseDocument).map((document) => ({
    __documentId: document.id || "",
    date: formatBackendDate(document.fecha),
    time: formatBackendTime(document.ocurridoEn ?? undefined),
    invoice: document.numero || "",
    terminal: documentTerminal(document, terminal),
    user: documentUser(document, user),
    supplier: document.proveedorCodigo || document.proveedorId || "",
    supplierName: document.proveedorNombre || "",
    warehouse: document.almacenNombre || document.almacenId || "",
    pending: formatAmount(pendingAmount(document)),
    dueDate: formatBackendDate(document.fechaVencimiento ?? ""),
    comment: document.numeroExterno || "",
    status: documentStatus(document),
    total: formatAmount(Number(document.total ?? 0))
  }));
  const deliveryNoteRows = deliveryNotes.filter(isSalesDocument).map((document) => ({
    __documentId: document.id || "",
    date: formatBackendDate(document.fecha),
    time: formatBackendTime(document.ocurridoEn ?? undefined),
    deliveryNote: document.numero || "",
    terminal: documentTerminal(document, terminal),
    user: documentUser(document, user),
    customer: document.clienteCodigo || document.clienteId || "",
    customerName: document.clienteNombre || "",
    comment: document.numeroExterno || "",
    status: documentStatus(document),
    total: formatAmount(Number(document.total ?? 0))
  }));
  const inputDeliveryNoteRows = deliveryNotes.filter(isPurchaseDocument).map((document) => ({
    __documentId: document.id || "",
    date: formatBackendDate(document.fecha),
    time: formatBackendTime(document.ocurridoEn ?? undefined),
    deliveryNote: document.numero || "",
    terminal: documentTerminal(document, terminal),
    user: documentUser(document, user),
    supplier: document.proveedorCodigo || document.proveedorId || "",
    supplierName: document.proveedorNombre || "",
    warehouse: document.almacenNombre || document.almacenId || "",
    productCount: formatWholeNumber(Number(document.lineas ?? 0)),
    pending: formatAmount(pendingAmount(document)),
    comment: document.numeroExterno || "",
    total: formatAmount(Number(document.total ?? 0))
  }));
  const warehouseOutputRows = warehouseOutputs.map((output) => ({
    date: formatBackendDate(output.date ?? output.fecha),
    time: "",
    output: output.number || output.numero || output.id || "",
    terminal,
    user,
    warehouse: output.warehouseId || output.almacenId || "",
    productCount: formatQuantity(sumOutputQuantity(output)),
    comment: output.concept || output.concepto || "",
    reason: output.destination || output.destino || output.status || output.estado || "",
    total: "0.00"
  }));
  const inputWarehouseRows = warehouseInputs.map((input) => ({
    date: formatBackendDate(input.date ?? input.fecha),
    time: "",
    input: input.number || input.numero || input.id || "",
    terminal,
    user,
    warehouse: input.warehouseId || input.almacenId || "",
    productCount: formatQuantity(sumInputQuantity(input)),
    comment: input.concept || input.concepto || "",
    origin: input.origin || input.origen || input.supplierId || input.proveedorId || input.status || input.estado || "",
    total: "0.00"
  }));
  const salesDocuments = [...tickets, ...invoices.filter(isSalesDocument)];
  const dailyRows = buildDailySalesRows(ticketRows, invoiceRows, user, terminal);
  const dailySummaries = buildDailySalesSummaries(salesDocuments, user);

  return {
    "salesReport.dailySales": { ...reportSamples["salesReport.dailySales"], rows: dailyRows, dailySummaries },
    "salesReport.tickets": { ...reportSamples["salesReport.tickets"], rows: ticketRows },
    "salesReport.deliveryNotes": { ...reportSamples["salesReport.deliveryNotes"], rows: deliveryNoteRows },
    "salesReport.invoices": { ...reportSamples["salesReport.invoices"], rows: invoiceRows },
    "salesReport.warehouseOutputs": { ...reportSamples["salesReport.warehouseOutputs"], rows: warehouseOutputRows },
    "salesReport.inputDeliveryNotes": { ...reportSamples["salesReport.inputDeliveryNotes"], rows: inputDeliveryNoteRows },
    "salesReport.inputInvoices": { ...reportSamples["salesReport.inputInvoices"], rows: inputInvoiceRows },
    "salesReport.inputWarehouse": { ...reportSamples["salesReport.inputWarehouse"], rows: inputWarehouseRows }
  };
}

export function isWarehouseDocumentReport(reportKey: string) {
  return [
    "salesReport.warehouseOutputs",
    "salesReport.inputWarehouse"
  ].includes(reportKey);
}

type ReportResource<T> = {
  value: T;
  failed: boolean;
};

async function loadReportResource<T>(
  request: SalesReportRequest,
  path: string,
  token: string,
  fallback: T
): Promise<ReportResource<T>> {
  try {
    return { value: await request<T>(path, { token }), failed: false };
  } catch {
    return { value: fallback, failed: true };
  }
}

type ReportPageKey = "invoices" | "deliveryNotes" | "warehouseOutputs" | "warehouseInputs";

function reportPageKey(reportKey: string): ReportPageKey | "" {
  if (reportKey === "salesReport.invoices" || reportKey === "salesReport.inputInvoices") {
    return "invoices";
  }
  if (reportKey === "salesReport.deliveryNotes" || reportKey === "salesReport.inputDeliveryNotes") {
    return "deliveryNotes";
  }
  if (reportKey === "salesReport.warehouseOutputs") {
    return "warehouseOutputs";
  }
  if (reportKey === "salesReport.inputWarehouse") {
    return "warehouseInputs";
  }
  return "";
}

function reportPagePath(pageKey: ReportPageKey, cursor?: string | null) {
  const paths: Record<ReportPageKey, string> = {
    invoices: "/document-reports/invoices",
    deliveryNotes: "/document-reports/delivery-notes",
    warehouseOutputs: "/warehouse-outputs",
    warehouseInputs: "/warehouse-inputs"
  };
  const params = new URLSearchParams({ limit: String(REPORT_PAGE_LIMIT) });
  if (cursor) {
    params.set("cursor", cursor);
  }
  return `${paths[pageKey]}?${params.toString()}`;
}

function buildDailySalesRows(
  tickets: Array<Record<string, string>>,
  invoices: Array<Record<string, string>>,
  user: string,
  terminal: string
) {
  const grouped = new Map<string, Record<string, string>>();
  const rowForDate = (date: string) => {
    const existing = grouped.get(date);
    if (existing) {
      return existing;
    }
    const next = { date, user, terminal, tickets: "0", invoice: "0", comment: "", invoicedTicketTotal: "0.00", total: "0.00" };
    grouped.set(date, next);
    return next;
  };

  tickets.forEach((ticket) => {
    const row = rowForDate(ticket.date ?? "");
    row.tickets = String(parseAmount(row.tickets) + 1);
    row.total = formatAmount(parseAmount(row.total) + parseAmount(ticket.total ?? ""));
  });
  invoices.forEach((invoice) => {
    const row = rowForDate(invoice.date ?? "");
    row.invoice = String(parseAmount(row.invoice) + 1);
    row.total = formatAmount(parseAmount(row.total) + parseAmount(invoice.total ?? ""));
  });

  return Array.from(grouped.values()).sort((left, right) => parseReportDate(left.date).localeCompare(parseReportDate(right.date)));
}

function buildInvoicedTicketTotal(reportKey: string, rows: Array<Record<string, string>>, totals: Record<string, string>) {
  if (reportKey === "salesReport.dailySales") {
    return totals.invoicedTicketTotal ?? "";
  }
  if (reportKey === "salesReport.tickets") {
    return formatAmount(rows.reduce((sum, row) => sum + (row.invoiced ? parseAmount(row.total ?? "") : 0), 0));
  }
  return "";
}

function filterOptionsFromRows(rows: Array<Record<string, string>>, attribute: string, translate: (key: string) => string): FilterOption[] {
  const values = Array.from(new Set(rows.map((row) => row[attribute]).filter(Boolean)));
  return [
    { value: "", label: translate("salesReport.filter.all") },
    ...values.map((value) => ({ value, label: translate(value) }))
  ];
}

function DocumentOperationalTimelineDialog({ documentId, locale, token, t, onClose }: {
  documentId: string;
  locale: LocaleCode;
  token?: string;
  t: (key: string) => string;
  onClose: () => void;
}) {
  const [timeline, setTimeline] = useState<DocumentOperationalTimeline | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(false);
    void loadDocumentOperationalTimeline(documentId, token)
      .then((value) => { if (active) setTimeline(value); })
      .catch(() => { if (active) { setTimeline(null); setError(true); } })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [documentId, token]);

  useEffect(() => {
    function closeOnEscape(event: globalThis.KeyboardEvent) {
      if (event.key === "Escape") onClose();
    }
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [onClose]);

  const unavailable = t("salesReport.value.unavailable");
  return (
    <div
      className="document-activity-overlay"
      role="dialog"
      aria-modal="true"
      aria-labelledby="document-activity-title"
      onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}
    >
      <section className="document-activity-dialog">
        <header>
          <div>
            <span>{t("salesReport.activity.eyebrow")}</span>
            <h2 id="document-activity-title">{timeline?.documentNumber || t("salesReport.activity.title")}</h2>
          </div>
          <button type="button" aria-label={t("common.close")} onClick={onClose}>×</button>
        </header>

        {loading && <div className="document-activity-state">{t("common.loading")}</div>}
        {!loading && error && <div className="document-activity-state error">{t("salesReport.activity.loadError")}</div>}
        {!loading && timeline && (
          <>
            <dl className="document-activity-summary">
              <div><dt>{t("salesReport.activity.documentType")}</dt><dd>{t(`salesReport.activity.documentType.${timeline.documentType}`)}</dd></div>
              <div><dt>{t("salesReport.activity.status")}</dt><dd>{t(`salesReport.activity.documentStatus.${timeline.documentStatus}`)}</dd></div>
              <div><dt>{t("salesReport.column.date")}</dt><dd>{formatBackendDate(timeline.documentDate)}</dd></div>
              <div><dt>{t("salesReport.column.user")}</dt><dd>{timeline.originUserName || unavailable}</dd></div>
              <div><dt>{t("salesReport.column.terminal")}</dt><dd>{timeline.originTerminalName || unavailable}</dd></div>
              <div><dt>{t("salesReport.activity.events")}</dt><dd>{timeline.events.length}</dd></div>
            </dl>
            <div className="document-activity-table-wrap">
              <table className="document-activity-table">
                <thead><tr>
                  <th>{t("salesReport.activity.occurredAt")}</th>
                  <th>{t("salesReport.activity.action")}</th>
                  <th>{t("salesReport.column.user")}</th>
                  <th>{t("salesReport.column.terminal")}</th>
                  <th>{t("salesReport.activity.detail")}</th>
                </tr></thead>
                <tbody>
                  {timeline.events.map((event) => (
                    <tr key={event.id}>
                      <td>{formatOperationalDateTime(event.occurredAt, locale)}</td>
                      <td><span className={`document-activity-type ${event.type.toLowerCase()}`}>{t(`salesReport.activity.type.${event.type}`)}</span></td>
                      <td>{event.userName || unavailable}</td>
                      <td>{event.terminalName || unavailable}</td>
                      <td>{operationalEventDetail(event.data, t) || "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {timeline.events.length === 0 && <div className="document-activity-empty">{t("salesReport.activity.empty")}</div>}
            </div>
          </>
        )}
        <footer>
          <span>{t("salesReport.activity.readOnly")}</span>
          <button type="button" onClick={onClose}>{t("common.close")}</button>
        </footer>
      </section>
    </div>
  );
}

function formatOperationalDateTime(value: string, locale: LocaleCode) {
  const instant = new Date(value);
  if (Number.isNaN(instant.getTime())) return value;
  return new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", {
    day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit"
  }).format(instant);
}

function operationalEventDetail(data: Record<string, unknown> | null | undefined, t: (key: string) => string) {
  if (!data) return "";
  const parts: string[] = [];
  if (typeof data.motivo === "string" && data.motivo.trim()) parts.push(`${t("salesReport.activity.reason")}: ${data.motivo}`);
  if (typeof data.importe === "string" && data.importe.trim()) parts.push(`${t("salesReport.activity.amount")}: ${data.importe}`);
  if (typeof data.documentoRelacionadoId === "string" && data.documentoRelacionadoId.trim()) {
    parts.push(`${t("salesReport.activity.relatedDocument")}: ${data.documentoRelacionadoId}`);
  }
  if (data.migrado === true && parts.length === 0) parts.push(t("salesReport.activity.migrated"));
  return parts.join(" · ");
}

export function SalesReportScreen({
  app,
  locale,
  session,
  terminalContext,
  onBack,
  onLogout,
  onLocaleChange,
  embedded = false,
  initialReport: requestedInitialReport,
  request = apiRequest
}: SalesReportScreenProps) {
  const t = createTranslator(locale);
  const reportOutputPreferences = readSalesReportOutputPreferences(app, session.username, terminalContext);
  const availableReports = visibleSalesReports(session);
  const initialReport = requestedInitialReport && availableReports.all.includes(requestedInitialReport)
    ? requestedInitialReport
    : availableReports.all[0] ?? outputReports[0];
  const [languageOpen, setLanguageOpen] = useState(false);
  const [shutdownOpen, setShutdownOpen] = useState(false);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const [saasConnected, setSaasConnected] = useState(currentOnlineStatus);
  const [visualizationOpen, setVisualizationOpen] = useState(false);
  const [printMenuOpen, setPrintMenuOpen] = useState(false);
  const [reportExportBusy, setReportExportBusy] = useState(false);
  const [filterOpen, setFilterOpen] = useState(false);
  const printMenuRef = useRef<HTMLDivElement | null>(null);
  const userMenuRef = useRef<HTMLDivElement | null>(null);
  const languagePickerRef = useRef<HTMLDivElement | null>(null);
  const [filters, setFilters] = useState<ReportFilters>(() => createDefaultFilters());
  const [draftFilters, setDraftFilters] = useState<ReportFilters>(() => createDefaultFilters());
  const [dateRangeText, setDateRangeText] = useState(() => formatDateRange(createDefaultFilters(), locale));
  const [dateRangeStart, setDateRangeStart] = useState<string | null>(null);
  const [reportSearch, setReportSearch] = useState("");
  const [openFilterControl, setOpenFilterControl] = useState<keyof ReportFilters | null>(null);
  const [calendarMonth, setCalendarMonth] = useState(() => startOfMonth(new Date()));
  const [remoteReports, setRemoteReports] = useState<Partial<Record<string, ReportSample>>>({});
  const [dailyCommercialReport, setDailyCommercialReport] = useState<DailyCommercialReport | null>(null);
  const [dailyReportLoading, setDailyReportLoading] = useState(false);
  const [dailyReportError, setDailyReportError] = useState("");
  const [dailyReportReload, setDailyReportReload] = useState(0);
  const dailyReportGeneration = useRef(0);
  const [reportPages, setReportPages] = useState<Record<string, { nextCursor: string | null; hasMore: boolean }>>({});
  const [reportLoading, setReportLoading] = useState(Boolean(session.accessToken));
  const [reportLoadErrors, setReportLoadErrors] = useState<Record<string, string>>({});
  const [reportLoadingMore, setReportLoadingMore] = useState(false);
  const [reportReloadKey, setReportReloadKey] = useState(0);
  const [activityDocumentId, setActivityDocumentId] = useState<string | null>(null);
  const [selectedReport, setSelectedReport] = useState(initialReport);
  const [visualReport, setVisualReport] = useState(initialReport);
  const [dragAttribute, setDragAttribute] = useState<string | null>(null);
  const [selectedRowByReport, setSelectedRowByReport] = useState<Record<string, number>>(() =>
    Object.fromEntries(allReports.map((reportKey) => [reportKey, 0]))
  );
  const [visibleAttributesByReport, setVisibleAttributesByReport] = useState<Record<string, string[]>>(() =>
    Object.fromEntries(allReports.map((reportKey) => [reportKey, reportSamples[reportKey].defaultVisibleAttributes]))
  );
  const reports: Record<string, ReportSample> = { ...reportSamples, ...(remoteReports as Record<string, ReportSample>) };
  const sample = reports[selectedReport] ?? reportSamples["salesReport.dailySales"];
  const visualSample = reports[visualReport] ?? reportSamples["salesReport.dailySales"];
  const isDailySalesReport = selectedReport === "salesReport.dailySales";
  const isDailyVisualReport = visualReport === "salesReport.dailySales";
  const selectedColumnDefinitions = buildReportColumnDefinitions(sample);
  const selectedReportTableLayout = useTableLayoutPreference({
    app,
    username: session.username,
    accessToken: isDailySalesReport ? undefined : session.accessToken,
    tableKey: reportTableKey(selectedReport),
    definitions: selectedColumnDefinitions
  });
  const visualColumnDefinitions = buildReportColumnDefinitions(visualSample);
  const inactiveVisualTableLayout = useTableLayoutPreference({
    app,
    username: session.username,
    accessToken: visualReport !== selectedReport && !isDailyVisualReport ? session.accessToken : undefined,
    tableKey: reportTableKey(visualReport),
    definitions: visualColumnDefinitions,
    debounceMs: 0
  });
  const visualTableLayout = visualReport === selectedReport
    ? selectedReportTableLayout
    : inactiveVisualTableLayout;
  const visibleColumnLayout = isDailySalesReport
    ? []
    : visibleTableColumns(selectedReportTableLayout.layout);
  const reportTableWidth = visibleColumnLayout.reduce((width, column) => width + column.width, 0);
  const filteredRows = sample.rows.filter((row) => rowMatchesFilters(row, filters) && rowMatchesSearch(row, reportSearch, t));
  const filteredTotals = buildFilteredTotals(sample, filteredRows);
  const invoicedTicketTotal = buildInvoicedTicketTotal(selectedReport, filteredRows, filteredTotals);
  const selectedDailySummary = sample.dailySummaries?.[filters.dateFrom] ?? emptyDailySalesSummary(filters.dateFrom);
  const selectedRowIndex = selectedRowByReport[selectedReport] ?? 0;
  const selectedReportRow = filteredRows[selectedRowIndex];
  const canOpenSelectedActivity = canOpenOperationalTimeline(app, session, selectedReport, selectedReportRow);
  const dbLabel = apiServerLabel();
  const visualVisibleAttributes = isDailyVisualReport
    ? visibleAttributesByReport[visualReport]
    : visibleTableColumns(visualTableLayout.layout).map((column) => column.key);
  const visualAvailableAttributes = visualSample.availableAttributes.filter(
    (attribute) => attribute !== "total" && !visualVisibleAttributes.includes(attribute)
  );
  const monthTitleLocale = locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES";
  const calendarTitle = new Intl.DateTimeFormat(monthTitleLocale, { month: "long", year: "numeric" }).format(calendarMonth);
  const hasDateFilter = sample.availableAttributes.includes("date");
  const hasUserFilter = !isDailySalesReport && sample.availableAttributes.includes("user");
  const hasTerminalFilter = !isDailySalesReport && sample.availableAttributes.includes("terminal");
  const hasCustomerFilter = !isDailySalesReport && (sample.availableAttributes.includes("customer") || sample.availableAttributes.includes("customerName"));
  const hasSupplierFilter = !isDailySalesReport && (sample.availableAttributes.includes("supplier") || sample.availableAttributes.includes("supplierName"));
  const hasPaymentFilter = !isDailySalesReport && sample.availableAttributes.includes("payment");
  const hasStatusFilter = !isDailySalesReport && selectedReport !== "salesReport.tickets" && sample.availableAttributes.includes("status");
  const hasWarehouseFilter = !isDailySalesReport && sample.availableAttributes.includes("warehouse");
  const selectedReportPage = reportPages[reportPageKey(selectedReport)];
  const selectedReportLoadError = reportLoadErrors[selectedReport] ?? "";

  useOutsidePointerDown(printMenuOpen, printMenuRef, () => setPrintMenuOpen(false));
  useOutsidePointerDown(userMenuOpen, userMenuRef, () => setUserMenuOpen(false));
  useOutsidePointerDown(languageOpen, languagePickerRef, () => setLanguageOpen(false));

  useEffect(() => {
    if (!isDailySalesReport) {
      normalizeRequiredTotal(selectedReportTableLayout);
    }
  }, [isDailySalesReport, selectedReport, selectedReportTableLayout.layout]);

  useEffect(() => {
    if (visualReport !== selectedReport && !isDailyVisualReport) {
      normalizeRequiredTotal(inactiveVisualTableLayout);
    }
  }, [inactiveVisualTableLayout.layout, isDailyVisualReport, selectedReport, visualReport]);

  useEffect(() => {
    let cancelled = false;
    void loadReportVisualizationPreferences(app, session.accessToken)
      .then((preferences) => {
        if (cancelled || preferences.length === 0) {
          return;
        }
        setVisibleAttributesByReport((current) => applySavedVisualizationPreferences(current, reports, preferences));
      })
      .catch((error) => {
        console.warn("No se pudo cargar la visualizacion de informes", error);
      });
    return () => {
      cancelled = true;
    };
  }, [app, session.accessToken]);
  const userOptions = filterOptionsFromRows(sample.rows, "user", t);
  const paymentOptions = filterOptionsFromRows(sample.rows, "payment", t);
  const terminalOptions = filterOptionsFromRows(sample.rows, "terminal", t);
  const statusOptions = filterOptionsFromRows(sample.rows, "status", t);
  const warehouseOptions = filterOptionsFromRows(sample.rows, "warehouse", t);
  const activeFilterDetails = buildActiveFilterDetails();

  useEffect(() => {
    let cancelled = false;
    if (!session.accessToken) {
      setRemoteReports({});
      setReportPages({});
      setReportLoadErrors({});
      setReportLoading(false);
      return;
    }

    async function loadReports() {
      const token = session.accessToken;
      if (!token) {
        return;
      }
      setReportLoading(true);
      setReportLoadErrors({});
      try {
        const [ticketResource, invoiceResource, deliveryNoteResource, warehouseOutputResource, warehouseInputResource] = await Promise.all([
          loadReportResource<DocumentView[]>(request, "/tickets", token, []),
          loadReportResource<PagedResult<DocumentView>>(request, reportPagePath("invoices"), token, { items: [], nextCursor: null, hasMore: false }),
          loadReportResource<PagedResult<DocumentView>>(request, reportPagePath("deliveryNotes"), token, { items: [], nextCursor: null, hasMore: false }),
          loadReportResource<PagedResult<WarehouseOutputView>>(request, reportPagePath("warehouseOutputs"), token, { items: [], nextCursor: null, hasMore: false }),
          loadReportResource<PagedResult<WarehouseInputView>>(request, reportPagePath("warehouseInputs"), token, { items: [], nextCursor: null, hasMore: false })
        ]);
        if (!cancelled) {
          const tickets = ticketResource.value;
          const invoices = invoiceResource.value;
          const deliveryNotes = deliveryNoteResource.value;
          const warehouseOutputs = warehouseOutputResource.value;
          const warehouseInputs = warehouseInputResource.value;
          const loadError = t("salesReport.loadError");
          setReportLoadErrors({
            ...(ticketResource.failed ? { "salesReport.tickets": loadError } : {}),
            ...(invoiceResource.failed ? {
              "salesReport.invoices": loadError,
              "salesReport.inputInvoices": loadError
            } : {}),
            ...(deliveryNoteResource.failed ? {
              "salesReport.deliveryNotes": loadError,
              "salesReport.inputDeliveryNotes": loadError
            } : {}),
            ...(warehouseOutputResource.failed ? { "salesReport.warehouseOutputs": loadError } : {}),
            ...(warehouseInputResource.failed ? { "salesReport.inputWarehouse": loadError } : {})
          });
          setReportPages({
            invoices: { nextCursor: invoices.nextCursor ?? null, hasMore: Boolean(invoices.hasMore) },
            deliveryNotes: { nextCursor: deliveryNotes.nextCursor ?? null, hasMore: Boolean(deliveryNotes.hasMore) },
            warehouseOutputs: { nextCursor: warehouseOutputs.nextCursor ?? null, hasMore: Boolean(warehouseOutputs.hasMore) },
            warehouseInputs: { nextCursor: warehouseInputs.nextCursor ?? null, hasMore: Boolean(warehouseInputs.hasMore) }
          });
          setRemoteReports(buildDocumentReports(
            tickets,
            invoices.items,
            deliveryNotes.items,
            warehouseOutputs.items,
            [],
            warehouseInputs.items,
            session,
            terminalContext
          ));
        }
      } catch {
        if (!cancelled) {
          setRemoteReports({});
          setReportPages({});
          setReportLoadErrors(Object.fromEntries(availableReports.all.map((reportKey) => [reportKey, t("salesReport.loadError")])));
        }
      } finally {
        if (!cancelled) setReportLoading(false);
      }
    }

    void loadReports();
    return () => {
      cancelled = true;
    };
  }, [request, session, terminalContext, reportReloadKey]);

  useEffect(() => {
    const generation = ++dailyReportGeneration.current;
    if (!session.accessToken || selectedReport !== "salesReport.dailySales" || !filters.dateFrom) {
      setDailyCommercialReport(null);
      setDailyReportLoading(false); setDailyReportError("");
      return;
    }
    setDailyCommercialReport(null); setDailyReportLoading(true); setDailyReportError("");
    void request<DailyCommercialReport>(
      `/commercial-reports/daily?date=${encodeURIComponent(filters.dateFrom)}`,
      { token: session.accessToken }
    ).then((report) => {
      if (generation === dailyReportGeneration.current) setDailyCommercialReport(report);
    }).catch((failure) => {
      if (generation === dailyReportGeneration.current) setDailyReportError(failure instanceof Error ? failure.message : t("salesReport.daily.loadError"));
    }).finally(() => {
      if (generation === dailyReportGeneration.current) setDailyReportLoading(false);
    });
    return () => { if (generation === dailyReportGeneration.current) dailyReportGeneration.current += 1; };
  }, [dailyReportReload, filters.dateFrom, request, selectedReport, session.accessToken]);

  useEffect(() => {
    function updateConnectionStatus() {
      setSaasConnected(currentOnlineStatus());
    }

    window.addEventListener("online", updateConnectionStatus);
    window.addEventListener("offline", updateConnectionStatus);
    return () => {
      window.removeEventListener("online", updateConnectionStatus);
      window.removeEventListener("offline", updateConnectionStatus);
    };
  }, []);

  function closeApplication() {
    if (window.tpvDesktop) {
      void window.tpvDesktop.closeApplication();
      return;
    }
    window.close();
  }

  function printReport() {
    switch (reportOutputPreferences.primaryAction) {
      case "print":
        void printCurrentReport();
        break;
      case "pdf":
        void exportPdfReport();
        break;
      case "excel":
        void exportExcelReport();
        break;
      default:
        setPrintMenuOpen((open) => !open);
    }
  }

  function reportFileName(extension: "xlsx" | "pdf") {
    const label = t(selectedReport).normalize("NFD").replace(/[\u0300-\u036f]/g, "")
      .replace(/[^a-zA-Z0-9]+/g, "-").replace(/^-|-$/g, "").toLowerCase();
    return `${label || "informe"}.${extension}`;
  }

  function exportColumns() {
    const keys = isDailySalesReport
      ? visibleAttributesByReport[selectedReport]
      : visibleColumnLayout.map((column) => column.key);
    return keys.map((key) => ({ key, label: t(attributeLabelKey[key] ?? key) }));
  }

  async function exportExcelReport() {
    if (!session.accessToken || reportExportBusy) return;
    setPrintMenuOpen(false);
    setReportExportBusy(true);
    try {
      const response = await fetch(`${apiBaseUrl}/sales-reports/export`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${session.accessToken}`
        },
        body: JSON.stringify({
          reportKey: selectedReport,
          filters,
          search: reportSearch,
          columns: exportColumns()
        })
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const bytes = new Uint8Array(await response.arrayBuffer());
      const fileName = reportFileName("xlsx");
      if (window.tpvDesktop?.reports) {
        const result = await window.tpvDesktop.reports.saveFile({
          defaultFileName: fileName,
          filters: [{ name: "Excel", extensions: ["xlsx"] }],
          bytes
        });
        if (!result.ok) throw new Error(result.message);
        return;
      }
      const url = URL.createObjectURL(new Blob([bytes], {
        type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
      }));
      const link = document.createElement("a");
      link.href = url;
      link.download = fileName;
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      window.alert(`${t("salesReport.exportFailed")}: ${error instanceof Error ? error.message : ""}`);
    } finally {
      setReportExportBusy(false);
    }
  }

  async function exportPdfReport() {
    setPrintMenuOpen(false);
    if (!window.tpvDesktop?.reports) {
      window.print();
      return;
    }
    const result = await window.tpvDesktop.reports.exportPdf(reportFileName("pdf"));
    if (!result.ok) window.alert(`${t("salesReport.exportFailed")}: ${result.message}`);
  }

  async function printCurrentReport() {
    setPrintMenuOpen(false);
    if (!window.tpvDesktop?.reports) {
      window.print();
      return;
    }
    const result = await window.tpvDesktop.reports.print();
    if (!result.ok) window.alert(`${t("salesReport.printFailed")}: ${result.message}`);
  }

  function selectReport(reportKey: string) {
    const defaultFilters = createDefaultFilters();
    const singleDay = reportKey === "salesReport.dailySales";
    setSelectedReport(reportKey);
    setFilters(defaultFilters);
    setDraftFilters(defaultFilters);
    setDateRangeText(formatDateFilterText(defaultFilters, locale, singleDay));
    setDateRangeStart(null);
    setOpenFilterControl(null);
    setSelectedRowByReport((current) => ({ ...current, [reportKey]: current[reportKey] ?? 0 }));
  }

  function openFilters() {
    setPrintMenuOpen(false);
    setDraftFilters(filters);
    setDateRangeText(formatDateFilterText(filters, locale, isDailySalesReport));
    setDateRangeStart(null);
    setOpenFilterControl(null);
    setFilterOpen(true);
  }

  function updateDraftFilter(key: keyof ReportFilters, value: string) {
    setDraftFilters((current) => ({ ...current, [key]: value }));
  }

  function updateReportSearch(value: string) {
    setReportSearch(value);
    setSelectedRowByReport((current) => ({ ...current, [selectedReport]: 0 }));
  }

  async function loadMoreReportRows() {
    const pageKey = reportPageKey(selectedReport);
    const page = pageKey ? reportPages[pageKey] : undefined;
    if (!pageKey || !page?.hasMore || !page.nextCursor || !session.accessToken || reportLoadingMore) {
      return;
    }
    setReportLoadingMore(true);
    try {
      const nextPage = await request<PagedResult<DocumentView | WarehouseOutputView | WarehouseInputView>>(
        reportPagePath(pageKey, page.nextCursor),
        { token: session.accessToken }
      );
      const partialReports = buildDocumentReports(
        [],
        pageKey === "invoices" ? nextPage.items as DocumentView[] : [],
        pageKey === "deliveryNotes" ? nextPage.items as DocumentView[] : [],
        pageKey === "warehouseOutputs" ? nextPage.items as WarehouseOutputView[] : [],
        [],
        pageKey === "warehouseInputs" ? nextPage.items as WarehouseInputView[] : [],
        session,
        terminalContext
      );
      const affectedReportsByPageKey: Record<ReportPageKey, string[]> = {
        invoices: ["salesReport.invoices", "salesReport.inputInvoices"],
        deliveryNotes: ["salesReport.deliveryNotes", "salesReport.inputDeliveryNotes"],
        warehouseOutputs: ["salesReport.warehouseOutputs"],
        warehouseInputs: ["salesReport.inputWarehouse"]
      };

      setRemoteReports((current) => {
        const next = { ...current };
        affectedReportsByPageKey[pageKey].forEach((reportKey) => {
          const currentReport = next[reportKey] ?? reportSamples[reportKey];
          const loadedReport = partialReports[reportKey];
          next[reportKey] = {
            ...currentReport,
            rows: [...currentReport.rows, ...(loadedReport?.rows ?? [])]
          };
        });
        return next;
      });
      setReportPages((current) => ({
        ...current,
        [pageKey]: {
          nextCursor: nextPage.nextCursor ?? null,
          hasMore: Boolean(nextPage.hasMore)
        }
      }));
      setReportLoadErrors((current) => {
        const next = { ...current };
        affectedReportsByPageKey[pageKey].forEach((reportKey) => delete next[reportKey]);
        return next;
      });
    } catch {
      setReportLoadErrors((current) => ({
        ...current,
        [selectedReport]: t("salesReport.loadError")
      }));
    } finally {
      setReportLoadingMore(false);
    }
  }

  function clearFilters() {
    const defaultFilters = createDefaultFilters();
    setDraftFilters(defaultFilters);
    setFilters(defaultFilters);
    setDateRangeText(formatDateFilterText(defaultFilters, locale, isDailySalesReport));
    setDateRangeStart(null);
    setOpenFilterControl(null);
    setSelectedRowByReport((current) => ({ ...current, [selectedReport]: 0 }));
  }

  function applyFilters() {
    const selectedDate = draftFilters.dateFrom || draftFilters.dateTo;
    const nextFilters = isDailySalesReport
      ? { ...draftFilters, dateFrom: selectedDate, dateTo: selectedDate }
      : draftFilters;
    setFilters(nextFilters);
    setDateRangeText(formatDateFilterText(nextFilters, locale, isDailySalesReport));
    setSelectedRowByReport((current) => ({ ...current, [selectedReport]: 0 }));
    setDateRangeStart(null);
    setOpenFilterControl(null);
    setFilterOpen(false);
  }

  function openDatePicker() {
    const currentDate = parseIsoDate(draftFilters.dateFrom) ?? new Date();
    setCalendarMonth(startOfMonth(currentDate));
    setDateRangeText(formatDateRange(draftFilters, locale));
    setDateRangeStart(null);
    setOpenFilterControl((current) => (current === "dateFrom" ? null : "dateFrom"));
  }

  function moveCalendarMonth(direction: -1 | 1) {
    setCalendarMonth((current) => new Date(current.getFullYear(), current.getMonth() + direction, 1));
  }

  function selectFilterDate(value: Date) {
    const selected = toIsoDate(value);
    if (isDailySalesReport) {
      const nextFilters = { ...draftFilters, dateFrom: selected, dateTo: selected };
      setDraftFilters(nextFilters);
      setDateRangeText(formatDateFilterText(nextFilters, locale, true));
      setDateRangeStart(null);
      setOpenFilterControl(null);
      return;
    }
    if (!dateRangeStart) {
      setDateRangeStart(selected);
      updateDraftFilter("dateFrom", selected);
      updateDraftFilter("dateTo", selected);
      setDateRangeText(formatDateRange({ ...draftFilters, dateFrom: selected, dateTo: selected }, locale));
      return;
    }
    const range = normalizeDateRange(dateRangeStart, selected);
    if (range) {
      setDraftFilters((current) => ({ ...current, ...range }));
      setDateRangeText(formatDateRange({ ...draftFilters, ...range }, locale));
    }
    setDateRangeStart(null);
  }

  function updateDateRangeText(value: string) {
    setDateRangeText(value);
    if (isDailySalesReport) {
      const date = parseManualDate(value);
      if (date) {
        setDraftFilters((current) => ({ ...current, dateFrom: date, dateTo: date }));
        setDateRangeStart(null);
      }
      return;
    }
    const range = parseDateRangeInput(value);
    if (range) {
      setDraftFilters((current) => ({ ...current, ...range }));
      setDateRangeStart(null);
    }
  }

  function applyDateRangeText() {
    if (isDailySalesReport) {
      const date = parseManualDate(dateRangeText);
      const nextFilters = date ? { ...draftFilters, dateFrom: date, dateTo: date } : draftFilters;
      setDraftFilters(nextFilters);
      setFilters(nextFilters);
      setDateRangeText(formatDateFilterText(nextFilters, locale, true));
      setSelectedRowByReport((current) => ({ ...current, [selectedReport]: 0 }));
      setDateRangeStart(null);
      setOpenFilterControl(null);
      setFilterOpen(false);
      return;
    }
    const range = parseDateRangeInput(dateRangeText);
    const nextFilters = range ? { ...draftFilters, ...range } : draftFilters;
    setDraftFilters(nextFilters);
    setFilters(nextFilters);
    setDateRangeText(formatDateRange(nextFilters, locale));
    setSelectedRowByReport((current) => ({ ...current, [selectedReport]: 0 }));
    setDateRangeStart(null);
    setOpenFilterControl(null);
    setFilterOpen(false);
  }

  function selectedOptionLabel(options: FilterOption[], value: string) {
    return options.find((option) => option.value === value)?.label ?? t("salesReport.filter.all");
  }

  function buildActiveFilterDetails() {
    const items: Array<{ label: string; value: string }> = [];
    const addFilter = (label: string, value: string) => {
      if (value.trim()) {
        items.push({ label, value });
      }
    };

    if (hasDateFilter && (filters.dateFrom || filters.dateTo)) {
      addFilter(t("salesReport.column.date"), formatDateFilterText(filters, locale, isDailySalesReport));
    }
    if (hasUserFilter) {
      addFilter(t("salesReport.filter.user"), filters.user);
    }
    if (hasTerminalFilter) {
      addFilter(t("salesReport.filter.terminal"), filters.terminal);
    }
    if (hasCustomerFilter) {
      addFilter(t("salesReport.filter.customer"), filters.customer);
    }
    if (hasSupplierFilter) {
      addFilter(t("salesReport.filter.supplier"), filters.supplier);
    }
    if (hasPaymentFilter) {
      addFilter(t("salesReport.filter.payment"), filters.payment ? selectedOptionLabel(paymentOptions, filters.payment) : "");
    }
    if (hasWarehouseFilter) {
      addFilter(t("salesReport.filter.warehouse"), filters.warehouse ? selectedOptionLabel(warehouseOptions, filters.warehouse) : "");
    }
    if (hasStatusFilter) {
      addFilter(t("salesReport.filter.status"), filters.status ? selectedOptionLabel(statusOptions, filters.status) : "");
    }

    return items;
  }

  function renderDateRangeFilter(label: string) {
    const isOpen = openFilterControl === "dateFrom";
    const selectedStart = draftFilters.dateFrom;
    const selectedEnd = draftFilters.dateTo;
    return (
      <div className={`filter-field ${isOpen ? "open" : ""}`}>
        <span>{label}</span>
        <div className="date-range-control">
          <input
            type="text"
            value={dateRangeText}
            placeholder={t(isDailySalesReport ? "salesReport.filter.datePlaceholder" : "salesReport.filter.dateRangePlaceholder")}
            onChange={(event) => updateDateRangeText(event.target.value)}
            onFocus={(event) => event.currentTarget.select()}
            onClick={(event) => event.currentTarget.select()}
            onMouseUp={(event) => event.preventDefault()}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                applyDateRangeText();
              }
            }}
          />
          <button type="button" aria-expanded={isOpen} aria-label={t("salesReport.filter.openCalendar")} onClick={openDatePicker}>
            <span className="filter-control-arrow">v</span>
          </button>
        </div>
        {isOpen && (
          <div className="date-popover date-range-popover">
            <div className="date-range-strip">
              <div className={`date-range-strip-cell ${dateRangeStart ? "" : "active"}`}>
                <span>{t("salesReport.filter.dateFrom")}</span>
                <strong>{selectedStart ? formatFilterDate(selectedStart, locale) : "-"}</strong>
              </div>
              <div className={`date-range-strip-cell ${dateRangeStart ? "active" : ""}`}>
                <span>{t("salesReport.filter.dateTo")}</span>
                <strong>{selectedEnd ? formatFilterDate(selectedEnd, locale) : "-"}</strong>
              </div>
            </div>
            <header className="date-calendar-header">
              <button type="button" onClick={() => moveCalendarMonth(-1)}>
                {"<"}
              </button>
              <strong>{calendarTitle}</strong>
              <button type="button" onClick={() => moveCalendarMonth(1)}>
                {">"}
              </button>
            </header>
            <div className="date-calendar-grid">
              {weekdayLabels(locale).map((weekday) => (
                <span className="date-weekday" key={weekday}>
                  {weekday}
                </span>
              ))}
              {buildCalendarDays(calendarMonth).map((day, index) =>
                day ? (
                  <button
                    type="button"
                    className={[
                      "date-day",
                      toIsoDate(day) === selectedStart || toIsoDate(day) === selectedEnd ? "selected" : "",
                      toIsoDate(day) > selectedStart && toIsoDate(day) < selectedEnd ? "in-range" : ""
                    ].filter(Boolean).join(" ")}
                    key={toIsoDate(day)}
                    onClick={() => selectFilterDate(day)}
                  >
                    {day.getDate()}
                  </button>
                ) : (
                  <span className="date-day empty" key={`empty-${index}`} />
                )
              )}
            </div>
            <footer className="date-range-footer">
              <span>{selectedStart ? selectedDaysText(dateRangeDayCount(selectedStart, selectedEnd), locale) : t(isDailySalesReport ? "salesReport.filter.pickDate" : "salesReport.filter.pickDateFrom")}</span>
              <div className="date-range-actions">
                <button type="button" onClick={() => {
                  setDateRangeStart(null);
                  setOpenFilterControl(null);
                }}>
                  {t("common.cancel")}
                </button>
                <button type="button" className="primary" onClick={() => {
                  setDateRangeStart(null);
                  setOpenFilterControl(null);
                }}>
                  {t("common.apply")}
                </button>
              </div>
            </footer>
          </div>
        )}
      </div>
    );
  }

  function renderSelectFilter(field: SelectFilterKey, label: string, options: FilterOption[], wide = false) {
    const isOpen = openFilterControl === field;
    return (
      <div className={`filter-field ${wide ? "filter-wide" : ""} ${isOpen ? "open" : ""}`}>
        <span>{label}</span>
        <button
          type="button"
          className="filter-select-button"
          aria-expanded={isOpen}
          onClick={() => setOpenFilterControl((current) => (current === field ? null : field))}
        >
          <span>{selectedOptionLabel(options, draftFilters[field])}</span>
          <span className="filter-control-arrow">v</span>
        </button>
        {isOpen && (
          <div className="filter-popover">
            {options.map((option) => (
              <button
                type="button"
                className={draftFilters[field] === option.value ? "selected" : ""}
                key={option.value || "all"}
                onClick={() => {
                  updateDraftFilter(field, option.value);
                  setOpenFilterControl(null);
                }}
              >
                {option.label}
              </button>
            ))}
          </div>
        )}
      </div>
    );
  }

  function selectRow(rowIndex: number) {
    setSelectedRowByReport((current) => ({ ...current, [selectedReport]: rowIndex }));
  }

  function moveSelectedRow(rowIndex: number, direction: -1 | 1) {
    const nextIndex = Math.max(0, Math.min(filteredRows.length - 1, rowIndex + direction));
    selectRow(nextIndex);
  }

  function genericTableLayout(reportKey: string): ReportTableLayout | null {
    if (reportKey === "salesReport.dailySales") {
      return null;
    }
    if (reportKey === selectedReport) {
      return selectedReportTableLayout;
    }
    return reportKey === visualReport ? inactiveVisualTableLayout : null;
  }

  function updateLegacyVisibleAttributes(reportKey: string, buildNext: (current: string[]) => string[]) {
    const nextAttributes = buildNext(visibleAttributesByReport[reportKey]);
    setVisibleAttributesByReport((current) => {
      return { ...current, [reportKey]: nextAttributes };
    });
    void saveReportVisualizationPreference(app, session.accessToken, reportKey, nextAttributes)
      .catch((error) => {
        console.warn("No se pudo guardar la visualizacion de informes", error);
      });
  }

  function moveAttribute(reportKey: string, attribute: string, targetIndex: number) {
    if (attribute === "total") {
      return;
    }
    const tableLayout = genericTableLayout(reportKey);
    if (tableLayout) {
      moveReportColumnBeforeTotal(tableLayout, attribute);
      return;
    }
    updateLegacyVisibleAttributes(reportKey, (current) => {
      const currentVisible = current.filter((item) => item !== attribute && item !== "total");
      const next = [...currentVisible];
      next.splice(Math.min(targetIndex, next.length), 0, attribute);
      if (reportSamples[reportKey].availableAttributes.includes("total")) {
        next.push("total");
      }
      return next;
    });
  }

  function removeAttribute(reportKey: string, attribute: string) {
    if (attribute === "total") {
      return;
    }
    const tableLayout = genericTableLayout(reportKey);
    if (tableLayout) {
      const column = tableLayout.layout.find((candidate) => candidate.key === attribute);
      if (column?.visible) {
        tableLayout.toggleColumnVisibility(attribute);
      }
      return;
    }
    updateLegacyVisibleAttributes(reportKey, (current) => current.filter((item) => item !== attribute));
  }

  function moveAttributeStep(reportKey: string, attribute: string, direction: -1 | 1) {
    if (attribute === "total") {
      return;
    }
    const tableLayout = genericTableLayout(reportKey);
    if (tableLayout) {
      moveVisibleReportColumn(tableLayout, attribute, direction);
      return;
    }
    updateLegacyVisibleAttributes(reportKey, (current) => {
      const movable = current.filter((item) => item !== "total");
      const from = movable.indexOf(attribute);
      const to = from + direction;
      if (from < 0 || to < 0 || to >= movable.length) {
        return current;
      }
      const nextMovable = [...movable];
      nextMovable.splice(from, 1);
      nextMovable.splice(to, 0, attribute);
      return reportSamples[reportKey].availableAttributes.includes("total") ? [...nextMovable, "total"] : nextMovable;
    });
  }

  function renderDailyPaymentLines(lines: DailyPaymentLine[]) {
    return (
      <div className="daily-payment-list">
        {lines.map((line) => (
          <div className="daily-payment-line" key={line.method}>
            <span>{`${t(line.method)} (${line.operations})`}</span>
            <strong>{`${formatAmount(line.amount)}€`}</strong>
          </div>
        ))}
      </div>
    );
  }

  function renderDailySummaryBlock(
    title: string,
    summary: Pick<DailySalesSummary, "payments" | "invoicedTotal" | "newPending" | "pendingCollections" | "total">
  ) {
    const hasMovement = summary.payments.length > 0 || summary.newPending.amount > 0 || summary.pendingCollections.length > 0;
    return (
      <section className="daily-summary-card">
        <h2>{title}</h2>
        {summary.payments.length > 0 && renderDailyPaymentLines(summary.payments)}
        {summary.newPending.amount > 0 && (
          <div className="daily-payment-line">
            <span>{`${t(summary.newPending.method)} (${summary.newPending.operations})`}</span>
            <strong>{`${formatAmount(summary.newPending.amount)}€`}</strong>
          </div>
        )}
        <div className="daily-summary-divider" />
        <div className="daily-total-line">
          <span>{t("salesReport.daily.invoicedTotal")}</span>
          <strong>{`${formatAmount(summary.invoicedTotal)}€`}</strong>
        </div>
        {summary.newPending.amount > 0 && (
          <div className="daily-pending-line">
            <span>{t("salesReport.daily.newPendingPlural")}</span>
            <strong>{`-${formatAmount(summary.newPending.amount)}€`}</strong>
          </div>
        )}
        {summary.pendingCollections.map((line) => (
          <div className="daily-pending-collection-line" key={`pending-${line.method}`}>
            <span>{`${t("salesReport.daily.pendingCollection")} ${t("salesReport.daily.in")} ${t(line.method)} (${line.operations})`}</span>
            <strong>{`${formatAmount(line.amount)}€`}</strong>
          </div>
        ))}
        <div className="daily-summary-divider" />
        <div className="daily-final-total-line">
          <span>{t("salesReport.daily.total")}</span>
          <strong>{`${formatAmount(summary.total)}€`}</strong>
        </div>
        {!hasMovement && (
          <p className="daily-summary-empty">{t("salesReport.daily.noPayments")}</p>
        )}
      </section>
    );
  }

  function buildDailyMethodTotals(summary: DailySalesSummary) {
    const totals = new Map<string, DailyPaymentLine>();
    summary.payments.forEach((line) => addPaymentLine(totals, line.method, line.amount));
    summary.pendingCollections.forEach((line) => addPaymentLine(totals, line.method, line.amount));
    return sortedPaymentLines(totals);
  }

  function renderDailyMethodTotals(summary: DailySalesSummary) {
    const totals = buildDailyMethodTotals(summary);
    if (totals.length === 0) {
      return null;
    }
    return (
      <section className="daily-summary-card daily-method-total-card">
        {totals.map((line) => (
          <div className="daily-method-total-line" key={line.method}>
            <span>{`${t("salesReport.daily.total")} ${t(line.method)}`}</span>
            <strong>{`${formatAmount(line.amount)}€`}</strong>
          </div>
        ))}
        <div className="daily-summary-divider" />
        <div className="daily-final-total-line">
          <span>{t("salesReport.daily.total")}</span>
          <strong>{`${formatAmount(summary.total)}€`}</strong>
        </div>
      </section>
    );
  }

  function renderDailySalesSummary() {
    if (dailyReportLoading) return <p className="daily-summary-empty" aria-live="polite">{t("salesReport.daily.loading")}</p>;
    if (dailyReportError) return <div className="daily-summary-error"><p role="alert">{dailyReportError}</p><button type="button" onClick={() => setDailyReportReload((value) => value + 1)}>{t("salesReport.daily.retry")}</button></div>;
    if (dailyCommercialReport) {
      const rows: Array<[string, number | string]> = [
        ["salesReport.daily.invoiced", dailyCommercialReport.invoiced],
        ["salesReport.daily.ticketSales", dailyCommercialReport.ticketSales ?? 0],
        ["salesReport.daily.collectedCurrent", dailyCommercialReport.collectedCurrent],
        ["salesReport.daily.newPending", dailyCommercialReport.newPending],
        ["salesReport.daily.priorDebtCollected", dailyCommercialReport.priorDebtCollected],
        ["salesReport.daily.cashInflow", dailyCommercialReport.cashInflow]
      ];
      return (
        <div className="daily-summary-scroll">
          <section className="daily-summary-card daily-authoritative-summary" aria-label={t("salesReport.daily.authoritativeSummary")}>
            <h2>{t("salesReport.daily.totalAmount")}</h2>
            {rows.map(([key, value]) => (
              <div className={key === "salesReport.daily.cashInflow" ? "daily-final-total-line" : "daily-payment-line"} key={key}>
                <span>{t(key)}</span>
                <strong>{`${formatAmount(Number(value))}€`}</strong>
              </div>
            ))}
          </section>
        </div>
      );
    }
    return (
      <div className="daily-summary-scroll">
        <div className="daily-summary-layout">
          <div className="daily-store-summary">
            {renderDailySummaryBlock(t("salesReport.daily.totalAmount"), selectedDailySummary)}
            {renderDailyMethodTotals(selectedDailySummary)}
          </div>
          <div className="daily-user-summary-list">
            {selectedDailySummary.users.map((userSummary) => (
              <section className="daily-user-summary" key={userSummary.user}>
                {renderDailySummaryBlock(userSummary.user, userSummary)}
              </section>
            ))}
            {selectedDailySummary.users.length === 0 && (
              <p className="daily-summary-empty">{t("salesReport.daily.noUserData")}</p>
            )}
          </div>
        </div>
      </div>
    );
  }

  function renderReportToolbar() {
    return (
      <header className="report-data-toolbar">
        <div className="report-action-menu" ref={printMenuRef}>
          <button
            type="button"
            aria-expanded={printMenuOpen}
            aria-haspopup="menu"
            onClick={printReport}
          >
            <img alt="" className="report-action-icon" src={printIcon} />
            {t("salesReport.print")}
          </button>
          {printMenuOpen && (
            <div className="print-menu" role="menu">
              <button type="button" role="menuitem" onClick={() => void exportPdfReport()}>
                {t("salesReport.exportPdf")}
              </button>
              <button type="button" role="menuitem" disabled={reportExportBusy} onClick={() => void exportExcelReport()}>
                {t("salesReport.exportExcel")}
              </button>
              <button type="button" role="menuitem" onClick={() => void printCurrentReport()}>
                {t("salesReport.printPdf")}
              </button>
            </div>
          )}
        </div>
        <button type="button" onClick={openFilters}>
          <img alt="" className="report-action-icon" src={filterIcon} />
          {t("salesReport.filter")}
        </button>
        {app === "gestion" && !isDailySalesReport && (
          <button
            type="button"
            disabled={!canOpenSelectedActivity}
            title={t("salesReport.activity.hint")}
            onClick={() => {
              if (selectedReportRow?.__documentId && canOpenSelectedActivity) {
                setActivityDocumentId(selectedReportRow.__documentId);
              }
            }}
          >
            {t("salesReport.activity.open")}
          </button>
        )}
        {selectedReportPage?.hasMore && (
          <button
            type="button"
            disabled={reportLoadingMore}
            onClick={() => {
              void loadMoreReportRows();
            }}
          >
            {reportLoadingMore ? t("salesReport.loadingMore") : t("salesReport.loadMore")}
          </button>
        )}
        <button
          type="button"
          hidden={isDailySalesReport}
          onClick={() => {
            setPrintMenuOpen(false);
            setVisualReport(selectedReport);
            setVisualizationOpen(true);
          }}
        >
          <img alt="" className="report-action-icon" src={visualizeIcon} />
          {t("salesReport.visualization")}
        </button>
        <label className="report-search" hidden={isDailySalesReport}>
          <img alt="" src={searchIcon} />
          <input
            type="search"
            value={reportSearch}
            aria-label={t("salesReport.search")}
            placeholder={t("salesReport.searchPlaceholder")}
            onChange={(event) => updateReportSearch(event.target.value)}
          />
        </label>
      </header>
    );
  }

  return (
    <main className={`${embedded ? "report-screen gestion-embedded-module" : "report-screen"} report-density-${reportOutputPreferences.density}`}>
      {!embedded && <TopDateTime locale={locale} />}
      {!embedded && <div ref={userMenuRef} style={{ display: "contents" }}>
        <button
          type="button"
          className="report-user-button"
          aria-expanded={userMenuOpen}
          aria-haspopup="menu"
          aria-label={session.displayName}
          title={session.displayName}
          onClick={() => {
            setLanguageOpen(false);
            setUserMenuOpen((open) => !open);
          }}
        >
          {session.displayName}
        </button>
        {userMenuOpen && (
          <section className="report-user-menu" role="menu" aria-label={session.displayName}>
            <button type="button" role="menuitem" onClick={() => setUserMenuOpen(false)}>
              {t("common.changePassword")}
            </button>
            <button
              type="button"
              role="menuitem"
              onClick={() => {
                setUserMenuOpen(false);
                if (onLogout) {
                  onLogout();
                  return;
                }
                onBack();
              }}
            >
              {t("common.logout")}
            </button>
          </section>
        )}
      </div>}
      {!embedded && <div ref={languagePickerRef} style={{ display: "contents" }}>
        <button
          type="button"
          className="language-button"
          aria-expanded={languageOpen}
          aria-haspopup="listbox"
          aria-label={t("login.language")}
          title={t("login.language")}
          onClick={() => {
            setUserMenuOpen(false);
            setLanguageOpen((open) => !open);
          }}
        >
          <img alt="" src={languageIcon} />
        </button>
        {languageOpen && (
          <section className="language-picker" aria-label={t("login.language")}>
            {languageOptions.map((option) => (
              <button
                type="button"
                className={option.code === locale ? "selected" : ""}
                key={option.code}
                onClick={() => {
                  onLocaleChange(option.code);
                  setLanguageOpen(false);
                }}
              >
                <span>{option.label}</span>
                <strong>{option.code.toUpperCase()}</strong>
              </button>
            ))}
          </section>
        )}
      </div>}
      {!embedded && <button
        type="button"
        className="shutdown-button"
        aria-label={t("login.shutdown")}
        title={t("login.shutdown")}
        onClick={() => setShutdownOpen(true)}
      >
        {"\u23FB"}
      </button>}

      <section className="report-shell" aria-label={t("home.salesReport")}>
        <header className="report-topbar">
          {!embedded && <button type="button" className="report-brand-back" onClick={onBack}>
            {t(app === "venta" ? "venta.title" : "gestion.title")}
          </button>}
          <h1 className="report-title">{t("home.salesReport")}</h1>
        </header>
        {!embedded && <aside className="report-nav">
          {availableReports.visibleOutputReports.length > 0 && <strong>{t("salesReport.output")}</strong>}
          {availableReports.visibleOutputReports.map((reportKey) => (
            <button
              type="button"
              className={selectedReport === reportKey ? "selected" : ""}
              key={reportKey}
              onClick={() => selectReport(reportKey)}
            >
              <img alt="" className="report-menu-icon" src={reportIcon[reportKey]} />
              {t(reportKey)}
            </button>
          ))}

          {availableReports.visibleInputReports.length > 0 && <strong className="report-nav-section">{t("salesReport.input")}</strong>}
          {availableReports.visibleInputReports.map((reportKey) => (
            <button
              type="button"
              className={selectedReport === reportKey ? "selected" : ""}
              key={reportKey}
              onClick={() => selectReport(reportKey)}
            >
              <img alt="" className="report-menu-icon" src={reportIcon[reportKey]} />
              {t(reportKey)}
            </button>
          ))}

          <button type="button" className="report-back" onClick={onBack}>
            {t("common.back")}
          </button>
        </aside>}

        <section className="report-workspace">
          <header className="report-options">
            <div className="report-heading">
              <h1>{t(selectedReport)}</h1>
              {activeFilterDetails.length > 0 && (
                <div className="active-filter-summary" aria-label={t("salesReport.filter")}>
                  {activeFilterDetails.map((filter) => (
                    <span key={`${filter.label}-${filter.value}`}>
                      <strong>{`${filter.label}:`}</strong>
                      {filter.value}
                    </span>
                  ))}
                </div>
              )}
            </div>
          </header>
          {isDailySalesReport ? (
            <div className="daily-summary-data">
              {renderReportToolbar()}
              {renderDailySalesSummary()}
            </div>
          ) : (
            <div className="report-data">
              {renderReportToolbar()}
              <div className="report-table-scroll">
                {reportLoading && (
                  <p className="report-load-state" aria-live="polite">
                    {t("salesReport.loading")}
                  </p>
                )}
                {selectedReportLoadError && (
                  <div className="report-load-state error" role="alert">
                    <span>{selectedReportLoadError}</span>
                    <button type="button" onClick={() => setReportReloadKey((value) => value + 1)}>
                      {t("salesReport.retry")}
                    </button>
                  </div>
                )}
                <table
                  className="report-table"
                  style={{ width: `${reportTableWidth}px`, minWidth: "100%" }}
                >
                  <colgroup>
                    {visibleColumnLayout.map((column) => (
                      <col key={column.key} style={{ width: column.width }} />
                    ))}
                  </colgroup>
                  <thead>
                    <tr>
                      {visibleColumnLayout.map((column) => (
                        <TableLayoutHeaderCell
                          column={column}
                          key={column.key}
                          movable={column.key !== "total"}
                          resizeLabel={`${t("stock.columns.resize")} ${t(attributeLabelKey[column.key] ?? column.key)}`}
                          onReorder={(draggedKey, targetKey) => {
                            if (draggedKey !== "total" && targetKey !== "total") {
                              selectedReportTableLayout.reorderColumns(draggedKey, targetKey);
                            }
                          }}
                          onMove={(attribute, direction) => {
                            moveVisibleReportColumn(selectedReportTableLayout, attribute, direction);
                          }}
                          onResize={selectedReportTableLayout.resizeColumn}
                        >
                          {t(attributeLabelKey[column.key] ?? column.key)}
                        </TableLayoutHeaderCell>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {filteredRows.map((row, rowIndex) => (
                      <tr
                        key={`${selectedReport}-${rowIndex}`}
                        className={selectedRowIndex === rowIndex ? "selected" : ""}
                        tabIndex={0}
                        aria-selected={selectedRowIndex === rowIndex}
                        onClick={() => selectRow(rowIndex)}
                        onFocus={() => selectRow(rowIndex)}
                        onDoubleClick={() => {
                          if (canOpenOperationalTimeline(app, session, selectedReport, row)) {
                            setActivityDocumentId(row.__documentId);
                          }
                        }}
                        onKeyDown={(event) => {
                          if (event.key === "ArrowUp") {
                            event.preventDefault();
                            moveSelectedRow(rowIndex, -1);
                          }
                          if (event.key === "ArrowDown") {
                            event.preventDefault();
                            moveSelectedRow(rowIndex, 1);
                          }
                          if (event.key === "Enter" && canOpenOperationalTimeline(app, session, selectedReport, row)) {
                            event.preventDefault();
                            setActivityDocumentId(row.__documentId);
                          }
                        }}
                      >
                        {visibleColumnLayout.map((column) => (
                          <td key={column.key}>{t(row[column.key] ?? "")}</td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="report-total-row">
                <span>{`${t("salesReport.visibleLines")}: ${filteredRows.length}`}</span>
                {invoicedTicketTotal && <strong>{`${t("salesReport.invoicedTicketTotal")}: ${invoicedTicketTotal}`}</strong>}
                <strong className="report-main-total">{`${t("salesReport.total")}: ${t(filteredTotals.total ?? "0.00")}`}</strong>
              </div>
            </div>
          )}
        </section>

        <footer className="report-footer-context">
          <span>{terminalContext.storeName}</span>
          <span>{`${t("login.terminalPrefix")}: ${terminalContext.terminalCode}`}</span>
          <span>{`DB: ${dbLabel}`}</span>
          <span className={`report-connection ${saasConnected ? "online" : "offline"}`}>
            <i aria-hidden="true" />
            {t("salesReport.connection")}
          </span>
        </footer>
      </section>

      {activityDocumentId && (
        <DocumentOperationalTimelineDialog
          documentId={activityDocumentId}
          locale={locale}
          token={session.accessToken}
          t={t}
          onClose={() => setActivityDocumentId(null)}
        />
      )}

      {visualizationOpen && (
        <div className="visualization-overlay" role="dialog" aria-modal="true" aria-labelledby="visualization-title">
          <section className="visualization-dialog">
            <header className="visualization-header">
              <h2 id="visualization-title">{t("salesReport.visualization")}</h2>
              <button type="button" onClick={() => setVisualizationOpen(false)}>
                {t("common.close")}
              </button>
            </header>
            <div className="visualization-layout">
              <aside className="visualization-reports">
                {availableReports.all.map((reportKey) => (
                  <button
                    type="button"
                    className={visualReport === reportKey ? "selected" : ""}
                    key={reportKey}
                    onClick={() => setVisualReport(reportKey)}
                  >
                    <img alt="" className="report-menu-icon" src={reportIcon[reportKey]} />
                    {t(reportKey)}
                  </button>
                ))}
              </aside>
              <section className="visualization-column">
                <strong>{t("salesReport.availableAttributes")}</strong>
                <div className="attribute-list">
                  {visualAvailableAttributes.map((attribute) => (
                    <button
                      type="button"
                      draggable
                      className="attribute-chip"
                      key={attribute}
                      onClick={() => moveAttribute(visualReport, attribute, visualVisibleAttributes.length)}
                      onDragStart={() => setDragAttribute(attribute)}
                      onDragEnd={() => setDragAttribute(null)}
                    >
                      {t(attributeLabelKey[attribute])}
                    </button>
                  ))}
                </div>
              </section>
              <section
                className="visualization-column visualization-selected"
                onDragOver={(event) => event.preventDefault()}
                onDrop={() => {
                  if (dragAttribute) {
                    moveAttribute(visualReport, dragAttribute, visualVisibleAttributes.length);
                    setDragAttribute(null);
                  }
                }}
              >
                <strong>{t("salesReport.visibleAttributes")}</strong>
                <div className="attribute-list">
                  {visualVisibleAttributes.map((attribute, index) => (
                    <div className="attribute-row" key={attribute}>
                      <div
                        draggable={attribute !== "total"}
                        className={`attribute-chip ${attribute === "total" ? "locked" : ""}`}
                        onDragStart={() => setDragAttribute(attribute)}
                        onDragEnd={() => setDragAttribute(null)}
                      >
                        <span className="drag-handle">::</span>
                        {t(attributeLabelKey[attribute])}
                        {attribute !== "total" && (
                          <span className="attribute-actions">
                            <button
                              type="button"
                              aria-label={t("salesReport.moveUp")}
                              title={t("salesReport.moveUp")}
                              disabled={index === 0}
                              onClick={() => moveAttributeStep(visualReport, attribute, -1)}
                            >
                              {"\u25B2"}
                            </button>
                            <button
                              type="button"
                              aria-label={t("salesReport.moveDown")}
                              title={t("salesReport.moveDown")}
                              disabled={index >= visualVisibleAttributes.filter((item) => item !== "total").length - 1}
                              onClick={() => moveAttributeStep(visualReport, attribute, 1)}
                            >
                              {"\u25BC"}
                            </button>
                            <button
                              type="button"
                              aria-label={t("salesReport.removeColumn")}
                              title={t("salesReport.removeColumn")}
                              onClick={() => removeAttribute(visualReport, attribute)}
                            >
                              x
                            </button>
                          </span>
                        )}
                        {attribute === "total" && (
                          <span className="lock-icon" aria-label={t("salesReport.lockedColumn")} title={t("salesReport.lockedColumn")}>
                            <img alt="" src={lockIcon} />
                          </span>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </section>
            </div>
          </section>
        </div>
      )}

      {filterOpen && (
        <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="filter-title">
          <section className="filter-dialog">
            <header className="filter-header">
              <h2 id="filter-title">{t("salesReport.filter")}</h2>
              <button type="button" onClick={() => setFilterOpen(false)}>
                {t("common.close")}
              </button>
            </header>
            <div className="filter-grid">
              {hasDateFilter && renderDateRangeFilter(t(isDailySalesReport ? "salesReport.filter.date" : "salesReport.filter.dateRange"))}
              {hasUserFilter && renderSelectFilter("user", t("salesReport.filter.user"), userOptions)}
              {hasTerminalFilter && renderSelectFilter("terminal", t("salesReport.filter.terminal"), terminalOptions)}
              {hasCustomerFilter && (
                <label>
                  <span>{t("salesReport.filter.customer")}</span>
                  <input
                    type="text"
                    value={draftFilters.customer}
                    placeholder={t("salesReport.filter.customerPlaceholder")}
                    onChange={(event) => updateDraftFilter("customer", event.target.value)}
                  />
                </label>
              )}
              {hasSupplierFilter && (
                <label>
                  <span>{t("salesReport.filter.supplier")}</span>
                  <input
                    type="text"
                    value={draftFilters.supplier}
                    placeholder={t("salesReport.filter.supplierPlaceholder")}
                    onChange={(event) => updateDraftFilter("supplier", event.target.value)}
                  />
                </label>
              )}
              {hasPaymentFilter && renderSelectFilter("payment", t("salesReport.filter.payment"), paymentOptions)}
              {hasWarehouseFilter && renderSelectFilter("warehouse", t("salesReport.filter.warehouse"), warehouseOptions)}
              {hasStatusFilter && renderSelectFilter("status", t("salesReport.filter.status"), statusOptions, true)}
            </div>
            <footer className="filter-actions">
              <button type="button" onClick={clearFilters}>
                {t("salesReport.filter.clear")}
              </button>
              <button type="button" onClick={applyFilters}>
                {t("salesReport.filter.apply")}
              </button>
            </footer>
          </section>
        </div>
      )}

      {shutdownOpen && (
        <div className="shutdown-overlay" role="dialog" aria-modal="true" aria-labelledby="shutdown-title">
          <section className="shutdown-dialog">
            <h2 id="shutdown-title">{t("login.shutdownConfirmTitle")}</h2>
            <p>{t("login.shutdownConfirmText")}</p>
            <div className="shutdown-actions">
              <button type="button" className="shutdown-no" autoFocus onClick={() => setShutdownOpen(false)}>
                {t("common.no")}
              </button>
              <button type="button" className="shutdown-yes" onClick={closeApplication}>
                {t("common.yes")}
              </button>
            </div>
          </section>
        </div>
      )}
    </main>
  );
}

