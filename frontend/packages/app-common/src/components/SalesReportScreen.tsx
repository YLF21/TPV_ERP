import { useEffect, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { lazy, Suspense } from "react";
import type { UIEvent } from "react";
import { flushSync } from "react-dom";
import { apiBaseUrl } from "../api/runtime";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { formatEuroAmount, localeTag, parseMoneyValue } from "../money";
import {
  commercialDocumentAsA4Document,
  outputConfirmedTicket,
  printPendingCommercialDocument,
  type ConfirmedTicketPrintSnapshot,
  type PendingCommercialDocumentPrintSnapshot
} from "../sale/ticketPrinting";
import { getHardwareBridge } from "../hardware/hardware";
import {
  buildWarehouseA4Document,
  hasDesktopHardwareBridge,
  printWarehouseA4Document,
  writeWarehouseDocumentPreview
} from "../warehouse/warehouseDocumentPrinting";
import { createTranslator } from "../i18n/LocalizedMessages";
import {
  applySavedVisualizationPreferences,
  loadReportVisualizationPreferences,
  saveReportVisualizationPreference
} from "./salesReportVisualizationPreferences";
import { SalesInvoiceRectificationDialog } from "./SalesInvoiceRectificationDialog";
import {
  findSaleOperationAuthorization,
  type SalesOperationSecurityConfiguration
} from "../sale/operationAuthorization";
import { ErpSelect } from "./ErpSelect";
import { ModuleNavBackButton } from "./ModuleNavBackButton";
import { ModuleNavItem } from "./ModuleNavItem";
import { TopDateTime } from "./TopDateTime";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { SalesActivityPanel } from "./SalesActivityPanel";
import { visibleTableColumns } from "./tableLayoutPreferences";
import type { TableColumnDefinition, TableLayout } from "./tableLayoutPreferences";
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

const REPORT_OUTPUT_SHORTCUTS = {
  print: "F5",
  excel: "F6",
  pdf: "F7"
} as const;

const SaleTicketInvoiceDialog = lazy(() => import("./SaleTicketInvoiceDialog")
  .then((module) => ({ default: module.SaleTicketInvoiceDialog })));
const SaleTicketCancellationDialog = lazy(() => import("./SaleTicketCancellationDialog")
  .then((module) => ({ default: module.SaleTicketCancellationDialog })));
import searchIcon from "../assets/reports/search.png";
import visualizeIcon from "../assets/reports/visualize.png";
import "../styles/report-command-toolbar.css";
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
  loadVisualizationPreferences?: typeof loadReportVisualizationPreferences;
  printCommercialDocument?: typeof printPendingCommercialDocument;
};

type SalesReportRequest = NonNullable<SalesReportScreenProps["request"]>;

type DailyCommercialReportDay = {
  date: string;
  invoiced: number | string;
  ticketSales?: number | string;
  collectedCurrent: number | string;
  newPending: number | string;
  priorDebtCollected: number | string;
  refunds?: number | string;
  cashInflow: number | string;
  ticketCount?: number;
  invoiceCount?: number;
  salesTotal?: number | string;
};

type DailyPaymentBreakdown = {
  cash: number | string;
  card: number | string;
  transfer: number | string;
  voucher: number | string;
  pending: number | string;
  other: number | string;
};

type DailyCommercialReport = DailyCommercialReportDay & {
  storeId: string;
  salesByPaymentMethod?: DailyPaymentBreakdown;
  pendingCollectionsByPaymentMethod?: DailyPaymentBreakdown;
  refundsByPaymentMethod?: DailyPaymentBreakdown;
  openingCashFund?: number | string;
  cashEntries?: number | string;
  cashWithdrawals?: number | string;
  expectedCash?: number | string;
  days?: DailyCommercialReportDay[];
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

function renderedPdfBlob(renderedPdf: { contentType: "application/pdf"; base64: string }): Blob {
  const binary = window.atob(renderedPdf.base64);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return new Blob([bytes], { type: renderedPdf.contentType });
}

function showPdfPreview(preview: Window, pdf: Blob): void {
  const objectUrl = URL.createObjectURL(pdf);
  preview.opener = null;
  preview.location.replace(objectUrl);
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000);
}

const reportIcon: Record<string, string> = {
  "salesReport.dailySales": dailySalesIcon,
  "salesReport.salesDocuments": ticketIcon,
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
  documentType: "salesReport.column.documentType",
  status: "salesReport.column.status",
  reason: "salesReport.column.reason",
  origin: "salesReport.column.origin",
  base: "salesReport.column.base",
  tax: "salesReport.column.tax",
  discount: "salesReport.column.discount",
  memberBalance: "salesReport.column.memberBalance",
  dueDate: "salesReport.column.dueDate",
  tickets: "salesReport.column.tickets"
};

export function reportAttributeLabelKey(reportKey: string, attribute: string) {
  if (attribute === "payment" && reportKey === "salesReport.tickets") {
    return "salesReport.column.paymentOrRefund";
  }
  if (attribute === "total" && reportKey === "salesReport.warehouseOutputs") {
    return "salesReport.column.saleTotal";
  }
  if (attribute === "total" && reportKey === "salesReport.inputWarehouse") {
    return "salesReport.column.purchaseTotal";
  }
  return attributeLabelKey[attribute] ?? attribute;
}

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
  documentType: 152,
  status: 128,
  reason: 184,
  origin: 184,
  base: 112,
  tax: 112,
  discount: 112,
  memberBalance: 128,
  dueDate: 112,
  tickets: 88
};

const attributeMinimumWidth: Record<string, number> = {
  date: 120,
  time: 104,
  ticket: 152,
  invoiced: 128,
  terminal: 156,
  user: 148,
  productCount: 112,
  customer: 136,
  customerName: 176,
  payment: 120,
  comment: 192,
  base: 120,
  tax: 120,
  discount: 120,
  memberBalance: 128,
  total: 120
};

type ReportSample = {
  availableAttributes: string[];
  defaultVisibleAttributes: string[];
  rows: Array<Record<string, string>>;
  totals: Record<string, string>;
  dailySummaries?: Record<string, DailySalesSummary>;
};

type ReportNotice = {
  kind: "info" | "success" | "error";
  message: string;
};

type ReportTableLayout = UseTableLayoutPreferenceResult<string>;

export function buildReportColumnDefinitions(report: ReportSample): TableColumnDefinition[] {
  const defaultVisible = new Set(report.defaultVisibleAttributes);
  return report.availableAttributes.map((attribute) => ({
    key: attribute,
    defaultWidth: attributeDefaultWidth[attribute] ?? 144,
    minWidth: attributeMinimumWidth[attribute],
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
  comentarioInterno?: string | null;
  fecha?: string;
  fechaVencimiento?: string | null;
  base?: number | string;
  impuesto?: number | string;
  pendiente?: number | string;
  descuentoGlobal?: number | string;
  saldoSocio?: number | string;
  total?: number | string;
  effectiveTotal?: number | string;
  numTicket?: string | null;
  origenStock?: boolean;
  clienteId?: string | null;
  clienteCodigo?: string | null;
  clienteNombre?: string | null;
  customerId?: string | null;
  customerCode?: string | null;
  customerName?: string | null;
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
  paymentMethods?: string[];
  refundMethods?: string[];
  invoiceNumber?: string | null;
  lifecycleStatus?: string | null;
};

type SalesDocumentDetail = {
  id: string;
  type: string;
  status: string;
  number?: string | null;
  date: string;
  base: number | string;
  tax: number | string;
  discount: number | string;
  total: number | string;
  originTicket?: {
    id: string;
    number?: string | null;
  } | null;
  lines: Array<{
    id: string;
    position: number;
    code: string;
    name: string;
    quantity: number | string;
    unitPrice: number | string;
    discount: number | string;
    taxRegime: string;
    taxPercentage: number | string;
    total: number | string;
  }>;
};

type SalesDocumentPrintCopy = Omit<PendingCommercialDocumentPrintSnapshot, "kind">;

type TicketCancellationReceipt = {
  operationId: string;
  originalTicketNumber: string;
  originalIssuedAt?: string | null;
  cancelledAt: string;
  total: number | string;
  reason: string;
  operatorUsername: string;
  authorizerUsername: string;
  delegated: boolean;
  payments: Array<{ method: string; amount: number | string; reference?: string | null }>;
  renderedPdf?: { contentType: "application/pdf"; base64: string } | null;
  ticketRenderedImage?: { contentType: "image/png"; base64: string } | null;
};

function canOpenDocumentPreview(row: Record<string, string> | undefined) {
  return Boolean(row?.__documentId || row?.__warehouseDocumentPayload);
}

function warehouseDocumentDetail(row: Record<string, string>): SalesDocumentDetail {
  const payload = JSON.parse(row.__warehouseDocumentPayload || "{}") as WarehouseInputView | WarehouseOutputView;
  const input = row.__warehouseDocumentKind === "INPUT";
  const lines = payload.lines ?? [];
  return {
    id: payload.id || row.__warehouseDocumentId || "",
    type: input ? "WAREHOUSE_INPUT" : "WAREHOUSE_OUTPUT",
    status: payload.status || payload.estado || "",
    number: input ? row.input : row.output,
    date: payload.date || payload.fecha || row.date || "",
    base: row.total || "0",
    tax: 0,
    discount: 0,
    total: row.total || "0",
    lines: lines.map((line, index) => {
      const inputLine = line as NonNullable<WarehouseInputView["lines"]>[number];
      const outputLine = line as NonNullable<WarehouseOutputView["lines"]>[number];
      const productId = line.productId || line.productoId || "-";
      const productCode = line.productCode || line.codigoProducto || productId;
      const productName = line.productName || line.nombreProducto || productCode;
      const unitPrice = input ? inputLine.purchaseUnitPrice : outputLine.saleUnitPrice;
      const total = input ? inputLine.purchaseTotal : outputLine.saleTotal;
      return {
        id: `${productId}-${index}`,
        position: index + 1,
        code: productCode,
        name: productName,
        quantity: line.quantity ?? line.cantidad ?? 0,
        unitPrice: unitPrice ?? 0,
        discount: 0,
        taxRegime: "",
        taxPercentage: 0,
        total: total ?? 0
      };
    })
  };
}

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

export function canManageSalesInvoiceRectification(
  app: AppKind,
  session: Pick<UserSession, "permissions">,
  reportKey: string,
  row: Record<string, string> | undefined
) {
  if (app !== "gestion" || reportKey !== "salesReport.invoices" || !row?.__documentId) return false;
  if (!canAccessSalesInvoiceRectification(session)) return false;
  if (row.__documentType === "RECTIFICATIVA_VENTA") return row.__documentStatus === "BORRADOR";
  return row.__documentType === "FACTURA_VENTA"
    && row.__documentStatus !== "BORRADOR"
    && row.__documentStatus !== "ANULADO";
}

export function canConvertSelectedTicketToInvoice(
  session: Pick<UserSession, "permissions">,
  reportKey: string,
  row: Record<string, string> | undefined
) {
  return reportKey === "salesReport.tickets"
    && Boolean(row?.__documentId && row.ticket)
    && row?.__documentStatus === "CONFIRMADO"
    && row?.status === "salesReport.status.confirmed"
    && !row?.invoiced
    && parseAmount(row?.total ?? "0") >= 0
    && session.permissions.some((permission) =>
      permission === "ADMIN" || permission === "GESTION_VENTAS" || permission === "VENTA"
    );
}

export function canCancelSelectedTicket(
  session: Pick<UserSession, "permissions">,
  reportKey: string,
  row: Record<string, string> | undefined
) {
  return reportKey === "salesReport.tickets"
    && Boolean(row?.__documentId && row.ticket)
    && row?.__documentStatus === "CONFIRMADO"
    && row?.status === "salesReport.status.confirmed"
    && !row?.invoiced
    && parseAmount(row?.total ?? "0") >= 0
    && session.permissions.some((permission) =>
      ["ADMIN", "GESTION_VENTAS", "GESTION_CUENTAS", "TICKETS_CANCEL", "VENTA"].includes(permission)
    );
}

export function canAccessSalesInvoiceRectification(
  session: Pick<UserSession, "permissions">
) {
  const permissions = session.permissions;
  const hasAppAccess = permissions.includes("ADMIN") || permissions.includes("APP_GESTION_ACCESS");
  const canWrite = permissions.includes("ADMIN")
    || permissions.includes("GESTION_VENTAS")
    || permissions.includes("INVOICES_WRITE");
  return hasAppAccess && canWrite;
}

export function canConfirmSalesInvoiceRectification(
  session: Pick<UserSession, "permissions">
) {
  return session.permissions.some((permission) =>
    permission === "ADMIN"
      || permission === "GESTION_VENTAS"
      || permission === "INVOICES_CONFIRM"
  );
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
  lines?: Array<{
    productId?: string;
    productoId?: string;
    productCode?: string;
    codigoProducto?: string;
    productName?: string;
    nombreProducto?: string;
    quantity?: number | string;
    cantidad?: number | string;
    saleUnitPrice?: number | string;
    saleTotal?: number | string;
  }>;
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
  lines?: Array<{
    productId?: string;
    productoId?: string;
    productCode?: string;
    codigoProducto?: string;
    productName?: string;
    nombreProducto?: string;
    quantity?: number | string;
    cantidad?: number | string;
    purchaseUnitPrice?: number | string;
    purchaseTotal?: number | string;
  }>;
};

type ReportWarehouseOption = {
  id: string;
  name?: string | null;
  nombre?: string | null;
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

export type ReportSort = {
  attribute: string;
  direction: "asc" | "desc";
};

type SavedReportView = {
  id: string;
  name: string;
  reportKey: string;
  filters: ReportFilters;
  search: string;
  sort: ReportSort | null;
  layout: TableLayout<string>;
};

type TicketCustomerDisplayMode = "code" | "name";

const reportSavedViewsStorageKey = (app: AppKind, username: string) =>
  `tpv-erp:${app}:user:${encodeURIComponent(username.trim().toLowerCase())}:report-views`;

const ticketCustomerDisplayStorageKey = (app: AppKind, username: string) =>
  `tpv-erp:${app}:user:${encodeURIComponent(username.trim().toLowerCase())}:ticket-customer-display`;

function readTicketCustomerDisplayMode(app: AppKind, username: string): TicketCustomerDisplayMode {
  try {
    return localStorage.getItem(ticketCustomerDisplayStorageKey(app, username)) === "name"
      ? "name"
      : "code";
  } catch {
    return "code";
  }
}

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

export function quickDateRange(kind: "today" | "week" | "month", now = new Date()) {
  const end = toIsoDate(now);
  if (kind === "today") return { dateFrom: end, dateTo: end };
  const start = new Date(now);
  if (kind === "week") {
    start.setDate(start.getDate() - ((start.getDay() + 6) % 7));
  } else {
    start.setDate(1);
  }
  return { dateFrom: toIsoDate(start), dateTo: end };
}

function readSavedReportViews(app: AppKind, username: string): SavedReportView[] {
  try {
    const parsed = JSON.parse(localStorage.getItem(reportSavedViewsStorageKey(app, username)) ?? "[]");
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((view): view is SavedReportView =>
      view && typeof view.id === "string" && typeof view.name === "string"
      && typeof view.reportKey === "string" && Array.isArray(view.layout)
    );
  } catch {
    return [];
  }
}

function writeSavedReportViews(app: AppKind, username: string, views: SavedReportView[]) {
  localStorage.setItem(reportSavedViewsStorageKey(app, username), JSON.stringify(views));
}

const reportSamples: Record<string, ReportSample> = {
  "salesReport.dailySales": {
    // Daily sales is an aggregated accounting summary. There is no single
    // document comment that can be represented truthfully in this report.
    availableAttributes: ["date", "user", "terminal", "tickets", "invoice", "total"],
    defaultVisibleAttributes: ["date", "user", "terminal", "tickets", "invoice", "total"],
    rows: [],
    totals: { date: "salesReport.total", tickets: "0", invoice: "0", invoicedTicketTotal: "0.00", total: "0.00" }
  },
  "salesReport.salesDocuments": {
    availableAttributes: [],
    defaultVisibleAttributes: [],
    rows: [],
    totals: {}
  },
  "salesReport.tickets": {
    availableAttributes: ["date", "time", "ticket", "status", "invoiced", "terminal", "user", "customer", "customerName", "payment", "comment", "base", "tax", "discount", "memberBalance", "total"],
    defaultVisibleAttributes: ["date", "time", "status", "terminal", "customer", "payment", "invoiced", "total"],
    rows: [],
    totals: { date: "salesReport.total", base: "0.00", tax: "0.00", discount: "0.00", memberBalance: "0.00", total: "0.00" }
  },
  "salesReport.deliveryNotes": {
    availableAttributes: ["date", "time", "deliveryNote", "terminal", "user", "customer", "customerName", "comment", "status", "base", "tax", "discount", "total"],
    defaultVisibleAttributes: ["deliveryNote", "customer", "date", "status", "total"],
    rows: [],
    totals: { deliveryNote: "salesReport.total", status: "0", base: "0.00", tax: "0.00", discount: "0.00", total: "0.00" }
  },
  "salesReport.invoices": {
    availableAttributes: ["date", "time", "invoice", "documentType", "terminal", "user", "customer", "customerName", "payment", "status", "pending", "comment", "base", "tax", "discount", "memberBalance", "total"],
    defaultVisibleAttributes: ["invoice", "documentType", "customer", "status", "pending", "total"],
    rows: [],
    totals: { invoice: "salesReport.total", status: "0", pending: "0.00", base: "0.00", tax: "0.00", discount: "0.00", memberBalance: "0.00", total: "0.00" }
  },
  "salesReport.warehouseOutputs": {
    availableAttributes: ["date", "time", "output", "terminal", "user", "warehouse", "productCount", "comment", "reason", "total"],
    defaultVisibleAttributes: ["output", "warehouse", "productCount", "reason", "total"],
    rows: [],
    totals: { output: "salesReport.total", productCount: "0", total: "0.00" }
  },
  "salesReport.inputDeliveryNotes": {
    availableAttributes: ["date", "time", "deliveryNote", "terminal", "user", "supplier", "supplierName", "warehouse", "productCount", "pending", "comment", "base", "tax", "discount", "total"],
    defaultVisibleAttributes: ["deliveryNote", "supplier", "productCount", "pending", "date", "total"],
    rows: [],
    totals: { deliveryNote: "salesReport.total", productCount: "0", pending: "0.00", base: "0.00", tax: "0.00", discount: "0.00", total: "0.00" }
  },
  "salesReport.inputInvoices": {
    availableAttributes: ["date", "time", "invoice", "terminal", "user", "supplier", "supplierName", "warehouse", "pending", "dueDate", "comment", "status", "base", "tax", "discount", "total"],
    defaultVisibleAttributes: ["invoice", "supplier", "dueDate", "status", "pending", "total"],
    rows: [],
    totals: { invoice: "salesReport.total", status: "0", pending: "0.00", base: "0.00", tax: "0.00", discount: "0.00", total: "0.00" }
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
  return parseMoneyValue(value) ?? 0;
}

function formatAmount(value: number) {
  return value.toFixed(2);
}

function formatWholeNumber(value: number) {
  return Math.round(value).toString();
}

function normalizeSearchText(value: string) {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();
}

function translateCompositeReportValue(value: string, translate: (key: string) => string) {
  return value.split(" + ").map((part) => translate(part)).join(" + ");
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
    .flatMap((value) => [value, translateCompositeReportValue(value, translate)])
    .map(normalizeSearchText)
    .join(" ");
  return haystack.includes(needle);
}

function buildFilteredTotals(
  reportKey: string,
  sample: ReportSample,
  rows: Array<Record<string, string>>
) {
  return Object.fromEntries(
    Object.keys(sample.totals).map((attribute) => {
      const originalValue = sample.totals[attribute];
      if (originalValue === "salesReport.total") {
        return [attribute, originalValue];
      }
      if (["total", "pending", "invoicedTicketTotal", "base", "tax", "discount", "memberBalance"].includes(attribute)) {
        return [attribute, formatAmount(rows.reduce((sum, row) => {
          const value = reportKey === "salesReport.tickets" && attribute === "total"
            ? row.__effectiveTotal ?? row.total ?? ""
            : row[attribute] ?? "";
          return sum + parseAmount(value);
        }, 0))];
      }
      if (["tickets", "invoice", "productCount"].includes(attribute)) {
        return [attribute, formatWholeNumber(rows.reduce((sum, row) => sum + parseAmount(row[attribute] ?? ""), 0))];
      }
      if (attribute === "status") {
        return [attribute, formatWholeNumber(rows.length)];
      }
      return [attribute, rows.length ? originalValue : ""];
    })
  );
}

const REPORT_MONETARY_ATTRIBUTES = new Set([
  "total", "pending", "invoicedTicketTotal", "base", "tax", "discount", "memberBalance"
]);

export function sortReportRows(
  rows: Array<Record<string, string>>,
  sort: ReportSort | null,
  locale: LocaleCode
) {
  if (!sort) return rows;
  const multiplier = sort.direction === "asc" ? 1 : -1;
  return [...rows].sort((left, right) => {
    const leftValue = left[sort.attribute] ?? "";
    const rightValue = right[sort.attribute] ?? "";
    if (
      REPORT_MONETARY_ATTRIBUTES.has(sort.attribute)
      || ["productCount", "tickets", "invoice"].includes(sort.attribute)
    ) {
      return (parseAmount(leftValue) - parseAmount(rightValue)) * multiplier;
    }
    if (sort.attribute === "date" || sort.attribute === "dueDate") {
      return parseReportDate(leftValue).localeCompare(parseReportDate(rightValue)) * multiplier;
    }
    return leftValue.localeCompare(rightValue, localeTag(locale), {
      numeric: true,
      sensitivity: "base"
    }) * multiplier;
  });
}

export function formatReportDisplayValue(
  attribute: string,
  value: string,
  locale: LocaleCode
) {
  if (!REPORT_MONETARY_ATTRIBUTES.has(attribute) || !value.trim()) {
    return value;
  }
  return formatEuroAmount(value, locale);
}

export async function salesReportResponseError(response: Response) {
  try {
    const body = await response.clone().json() as { detail?: unknown; message?: unknown };
    const detail = typeof body.detail === "string"
      ? body.detail
      : typeof body.message === "string"
        ? body.message
        : "";
    if (detail.trim()) return detail;
  } catch {
    try {
      const detail = await response.text();
      if (detail.trim()) return detail;
    } catch {
      // Keep the stable HTTP fallback below.
    }
  }
  return `HTTP ${response.status}`;
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

function sumOutputSaleTotal(output: WarehouseOutputView) {
  return (output.lines ?? []).reduce((sum, line) => {
    const persistedTotal = Number(line.saleTotal);
    if (Number.isFinite(persistedTotal)) return sum + persistedTotal;
    const quantity = Number(line.quantity ?? line.cantidad ?? 0);
    const unitPrice = Number(line.saleUnitPrice ?? 0);
    return sum + (Number.isFinite(quantity) && Number.isFinite(unitPrice) ? quantity * unitPrice : 0);
  }, 0);
}

function sumInputQuantity(input: WarehouseInputView) {
  return (input.lines ?? []).reduce((sum, line) => sum + Number(line.quantity ?? line.cantidad ?? 0), 0);
}

function sumInputPurchaseTotal(input: WarehouseInputView) {
  return (input.lines ?? []).reduce((sum, line) => {
    const persistedTotal = Number(line.purchaseTotal);
    if (Number.isFinite(persistedTotal)) return sum + persistedTotal;
    const quantity = Number(line.quantity ?? line.cantidad ?? 0);
    const unitPrice = Number(line.purchaseUnitPrice ?? 0);
    return sum + (Number.isFinite(quantity) && Number.isFinite(unitPrice) ? quantity * unitPrice : 0);
  }, 0);
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
  if (document.paymentMethods?.length) {
    return Array.from(new Set(document.paymentMethods.filter(Boolean))).join(" + ");
  }
  const names = Array.from(new Set((document.payments ?? []).map((payment) => payment.methodName).filter(Boolean)));
  return names.join(" + ");
}

function normalizedTicketPaymentLabel(value: string) {
  const normalized = value.trim().normalize("NFD").replace(/[\u0300-\u036f]/g, "").toUpperCase();
  const labels: Record<string, string> = {
    CASH: "EFECTIVO",
    EFECTIVO: "EFECTIVO",
    CARD: "TARJETA",
    TARJETA: "TARJETA",
    TRANSFER: "TRANSFERENCIA",
    TRANSFERENCIA: "TRANSFERENCIA",
    VOUCHER: "VALE",
    VALE: "VALE",
    MEMBER_BALANCE: "salesReport.payment.memberBalance",
    SALDO_MIEMBRO: "salesReport.payment.memberBalance",
    MEMBER_CREDIT: "salesReport.payment.returnCredit",
    CREDITO_DEVOLUCION: "salesReport.payment.returnCredit",
    PENDING: "salesReport.payment.pending",
    PENDIENTE: "salesReport.payment.pending",
    DISCOUNT: "DESCUENTO",
    DESCUENTO: "DESCUENTO"
  };
  return labels[normalized] ?? "";
}

function ticketPaymentText(document: DocumentView) {
  const rawMethods = Number(document.total ?? 0) < 0
    ? document.refundMethods ?? []
    : document.paymentMethods?.length
      ? document.paymentMethods
      : (document.payments ?? []).map((payment) => payment.methodName);
  const labels = rawMethods
    .filter((method): method is string => Boolean(method))
    .map(normalizedTicketPaymentLabel)
    .filter(Boolean);
  if (["PENDIENTE", "PARCIAL"].includes(String(document.estado ?? "").toUpperCase())) {
    labels.push("salesReport.payment.pending");
  }
  const uniqueLabels = Array.from(new Set(labels));
  return uniqueLabels.length ? uniqueLabels.join(" + ") : "—";
}

function ticketLifecycleStatus(document: DocumentView) {
  const status = String(document.lifecycleStatus ?? "").toUpperCase();
  if (String(document.estado ?? "").toUpperCase() === "ANULADO") {
    return "salesReport.status.ticketCancelled";
  }
  if (status === "CANCELLED") return "salesReport.status.ticketCancelled";
  if (status === "INVOICED") return "salesReport.status.invoiced";
  if (status === "PARTIALLY_RETURNED") return "salesReport.status.partiallyReturned";
  if (status === "RETURNED") return "salesReport.status.returned";
  return documentStatus(document);
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
  if (status === "BORRADOR") {
    return "salesReport.status.draft";
  }
  if (status === "ANULADO") {
    return "salesReport.status.cancelled";
  }
  if (status === "CONFIRMADO") {
    return "salesReport.status.confirmed";
  }
  if (status.includes("PENDIENTE")) {
    return "salesReport.status.pending";
  }
  if (status.includes("PARCIAL")) {
    return "salesReport.status.partial";
  }
  if (status.includes("PAG")) {
    return "salesReport.status.paid";
  }
  if (status.includes("FACT")) {
    return "salesReport.status.invoiced";
  }
  return status;
}

function salesDocumentType(document: DocumentView) {
  return document.tipo === "RECTIFICATIVA_VENTA"
    ? "salesReport.documentType.rectification"
    : "salesReport.documentType.invoice";
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
  _terminalContext: TerminalContext,
  warehouses: ReportWarehouseOption[] = []
): Partial<Record<string, ReportSample>> {
  const unavailable = "salesReport.value.unavailable";
  const terminal = unavailable;
  const user = unavailable;
  const warehouseNames = new Map(warehouses.map((warehouse) => [
    warehouse.id,
    warehouse.name ?? warehouse.nombre ?? warehouse.id
  ]));
  const warehouseName = (warehouseId: string) => warehouseNames.get(warehouseId) ?? warehouseId;
  const ticketRows = tickets.map((document) => ({
    __documentId: document.id || "",
    __documentStatus: document.estado || "",
    date: formatBackendDate(document.fecha),
    time: formatBackendTime(document.ocurridoEn ?? undefined),
    ticket: document.numTicket || document.numero || "",
    status: ticketLifecycleStatus(document),
    invoiced: document.invoiceNumber || "",
    terminal: documentTerminal(document, terminal),
    user: documentUser(document, user),
    customer: document.customerCode || document.clienteCodigo || document.customerId || document.clienteId || "",
    customerName: document.customerName || document.clienteNombre || "",
    payment: ticketPaymentText(document),
    comment: document.comentarioInterno || "",
    base: formatAmount(Number(document.base ?? 0)),
    tax: formatAmount(Number(document.impuesto ?? 0)),
    discount: formatAmount(Number(document.descuentoGlobal ?? 0)),
    memberBalance: formatAmount(Number(document.saldoSocio ?? 0)),
    total: formatAmount(Number(document.total ?? 0)),
    __effectiveTotal: formatAmount(Number(document.effectiveTotal ?? document.total ?? 0))
  }));
  const invoiceRows = invoices.filter(isSalesDocument).map((document) => ({
    __documentId: document.id || "",
    __documentType: document.tipo || "",
    __documentStatus: document.estado || "",
    date: formatBackendDate(document.fecha),
    time: formatBackendTime(document.ocurridoEn ?? undefined),
    invoice: document.numero || "",
    documentType: salesDocumentType(document),
    terminal: documentTerminal(document, terminal),
    user: documentUser(document, user),
    customer: document.clienteCodigo || document.clienteId || "",
    customerName: document.clienteNombre || "",
    payment: paymentText(document),
    status: documentStatus(document),
    pending: formatAmount(pendingAmount(document)),
    comment: document.comentarioInterno || document.numeroExterno || "",
    base: formatAmount(Number(document.base ?? 0)),
    tax: formatAmount(Number(document.impuesto ?? 0)),
    discount: formatAmount(Number(document.descuentoGlobal ?? 0)),
    memberBalance: formatAmount(Number(document.saldoSocio ?? 0)),
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
    comment: document.comentarioInterno || document.numeroExterno || "",
    status: documentStatus(document),
    base: formatAmount(Number(document.base ?? 0)),
    tax: formatAmount(Number(document.impuesto ?? 0)),
    discount: formatAmount(Number(document.descuentoGlobal ?? 0)),
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
    comment: document.comentarioInterno || document.numeroExterno || "",
    status: documentStatus(document),
    base: formatAmount(Number(document.base ?? 0)),
    tax: formatAmount(Number(document.impuesto ?? 0)),
    discount: formatAmount(Number(document.descuentoGlobal ?? 0)),
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
    comment: document.comentarioInterno || document.numeroExterno || "",
    base: formatAmount(Number(document.base ?? 0)),
    tax: formatAmount(Number(document.impuesto ?? 0)),
    discount: formatAmount(Number(document.descuentoGlobal ?? 0)),
    total: formatAmount(Number(document.total ?? 0))
  }));
  const warehouseOutputRows = warehouseOutputs.map((output) => ({
    __warehouseDocumentId: output.id || "",
    __warehouseDocumentKind: "OUTPUT",
    __warehouseDocumentPayload: JSON.stringify(output),
    date: formatBackendDate(output.date ?? output.fecha),
    time: "",
    output: output.number || output.numero || output.id || "",
    terminal,
    user,
    warehouse: warehouseName(output.warehouseId || output.almacenId || ""),
    productCount: formatQuantity(sumOutputQuantity(output)),
    comment: output.concept || output.concepto || "",
    reason: output.destination || output.destino || output.status || output.estado || "",
    total: formatAmount(sumOutputSaleTotal(output))
  }));
  const inputWarehouseRows = warehouseInputs.map((input) => ({
    __warehouseDocumentId: input.id || "",
    __warehouseDocumentKind: "INPUT",
    __warehouseDocumentPayload: JSON.stringify(input),
    date: formatBackendDate(input.date ?? input.fecha),
    time: "",
    input: input.number || input.numero || input.id || "",
    terminal,
    user,
    warehouse: warehouseName(input.warehouseId || input.almacenId || ""),
    productCount: formatQuantity(sumInputQuantity(input)),
    comment: input.concept || input.concepto || "",
    origin: input.origin || input.origen || input.supplierId || input.proveedorId || input.status || input.estado || "",
    total: formatAmount(sumInputPurchaseTotal(input))
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

type ReportPageKey = "tickets" | "invoices" | "deliveryNotes" | "warehouseOutputs" | "warehouseInputs";

function reportPageKey(reportKey: string): ReportPageKey | "" {
  if (reportKey === "salesReport.tickets") {
    return "tickets";
  }
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
    tickets: "/document-reports/tickets",
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
    const next = { date, user, terminal, tickets: "0", invoice: "0", invoicedTicketTotal: "0.00", total: "0.00" };
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

function buildInvoicedTicketTotal(reportKey: string, totals: Record<string, string>) {
  if (reportKey === "salesReport.dailySales") {
    return totals.invoicedTicketTotal ?? "";
  }
  return "";
}

export function buildTicketReportCounters(rows: Array<Record<string, string>>) {
  return rows.reduce((summary, row) => {
    if (row.status === "salesReport.status.ticketCancelled") {
      summary.cancelled += 1;
    }
    if (
      row.status === "salesReport.status.invoiced"
      || row.status === "salesReport.status.partiallyReturned"
    ) {
      summary.invoiced += 1;
    }
    return summary;
  }, { invoiced: 0, cancelled: 0 });
}

function filterOptionsFromRows(rows: Array<Record<string, string>>, attribute: string, translate: (key: string) => string): FilterOption[] {
  const values = Array.from(new Set(rows.map((row) => row[attribute]).filter(Boolean)));
  return [
    { value: "", label: translate("salesReport.filter.all") },
    ...values.map((value) => ({ value, label: translateCompositeReportValue(value, translate) }))
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
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(false);
    void loadDocumentOperationalTimeline(documentId, token)
      .then((value) => { if (active) setTimeline(value); })
      .catch(() => { if (active) { setTimeline(null); setError(true); } })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [documentId, reload, token]);

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
        {!loading && error && <div className="document-activity-state error" role="alert"><span>{t("salesReport.activity.loadError")}</span><button type="button" onClick={() => setReload((current) => current + 1)}>{t("salesReport.retry")}</button></div>}
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
  request = apiRequest,
  loadVisualizationPreferences = loadReportVisualizationPreferences,
  printCommercialDocument = printPendingCommercialDocument
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
  const [reportExportProgress, setReportExportProgress] = useState(0);
  const [reportNotice, setReportNotice] = useState<ReportNotice | null>(null);
  const [filterOpen, setFilterOpen] = useState(false);
  const [viewsOpen, setViewsOpen] = useState(false);
  const [moreActionsOpen, setMoreActionsOpen] = useState(false);
  const [reportDocumentPrinting, setReportDocumentPrinting] = useState(false);
  const [savedViews, setSavedViews] = useState<SavedReportView[]>(() =>
    readSavedReportViews(app, session.username)
  );
  const [savedViewName, setSavedViewName] = useState("");
  const [selectedSavedViewId, setSelectedSavedViewId] = useState("");
  const [sortByReport, setSortByReport] = useState<Record<string, ReportSort | null>>({});
  const [ticketCustomerDisplayMode, setTicketCustomerDisplayMode] = useState<TicketCustomerDisplayMode>(() =>
    readTicketCustomerDisplayMode(app, session.username)
  );
  const printMenuRef = useRef<HTMLDivElement | null>(null);
  const moreActionsRef = useRef<HTMLDivElement | null>(null);
  const reportShortcutActionsRef = useRef<Record<"F5" | "F6" | "F7", () => void>>({
    F5: () => undefined,
    F6: () => undefined,
    F7: () => undefined
  });
  const userMenuRef = useRef<HTMLDivElement | null>(null);
  const languagePickerRef = useRef<HTMLDivElement | null>(null);
  const reportTableScrollRef = useRef<HTMLDivElement | null>(null);
  const [filters, setFilters] = useState<ReportFilters>(() => createDefaultFilters());
  const [draftFilters, setDraftFilters] = useState<ReportFilters>(() => createDefaultFilters());
  const [dateRangeText, setDateRangeText] = useState(() => formatDateRange(createDefaultFilters(), locale));
  const [dateRangeStart, setDateRangeStart] = useState<string | null>(null);
  const [reportSearch, setReportSearch] = useState("");
  const [openFilterControl, setOpenFilterControl] = useState<keyof ReportFilters | null>(null);
  const [calendarMonth, setCalendarMonth] = useState(() => startOfMonth(new Date()));
  const [remoteReports, setRemoteReports] = useState<Partial<Record<string, ReportSample>>>({});
  const [reportWarehouses, setReportWarehouses] = useState<ReportWarehouseOption[]>([]);
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
  const [operationSecurity, setOperationSecurity] = useState<SalesOperationSecurityConfiguration | null>(null);
  const [ticketInvoiceNumber, setTicketInvoiceNumber] = useState<string | null>(null);
  const [ticketCancellationNumber, setTicketCancellationNumber] = useState<string | null>(null);
  const [activityDocumentId, setActivityDocumentId] = useState<string | null>(null);
  const [documentPreviewRow, setDocumentPreviewRow] = useState<Record<string, string> | null>(null);
  const [documentPreview, setDocumentPreview] = useState<SalesDocumentDetail | null>(null);
  const [documentPreviewLoading, setDocumentPreviewLoading] = useState(false);
  const [documentPreviewError, setDocumentPreviewError] = useState("");
  const [documentPreviewPrinting, setDocumentPreviewPrinting] = useState(false);
  const [documentPreviewExporting, setDocumentPreviewExporting] = useState(false);
  const [documentPreviewPrintMessage, setDocumentPreviewPrintMessage] = useState("");
  const [rectificationTarget, setRectificationTarget] = useState<{
    documentId: string;
    continueDraft: boolean;
  } | null>(null);
  const [selectedReport, setSelectedReport] = useState(initialReport);
  const [visualReport, setVisualReport] = useState(initialReport);
  const [dragAttribute, setDragAttribute] = useState<string | null>(null);
  const [selectedRowByReport, setSelectedRowByReport] = useState<Record<string, number>>(() =>
    Object.fromEntries(allReports.map((reportKey) => [reportKey, -1]))
  );
  const [visibleAttributesByReport, setVisibleAttributesByReport] = useState<Record<string, string[]>>(() =>
    Object.fromEntries(allReports.map((reportKey) => [reportKey, reportSamples[reportKey].defaultVisibleAttributes]))
  );
  const reports: Record<string, ReportSample> = { ...reportSamples, ...(remoteReports as Record<string, ReportSample>) };
  const sample = reports[selectedReport] ?? reportSamples["salesReport.dailySales"];
  const visualSample = reports[visualReport] ?? reportSamples["salesReport.dailySales"];
  const isDailySalesReport = selectedReport === "salesReport.dailySales";
  const isSalesDocumentsReport = selectedReport === "salesReport.salesDocuments";
  const isSalesActivityReport = isDailySalesReport || isSalesDocumentsReport;
  const isDailyVisualReport = visualReport === "salesReport.dailySales";
  const selectedColumnDefinitions = buildReportColumnDefinitions(sample);
  const selectedReportTableLayout = useTableLayoutPreference({
    app,
    username: session.username,
    accessToken: isSalesActivityReport ? undefined : session.accessToken,
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
  const visibleColumnLayout = isSalesActivityReport
    ? []
    : visibleTableColumns(selectedReportTableLayout.layout);
  const reportTableWidth = visibleColumnLayout.reduce((width, column) => width + column.width, 0);
  const matchingRows = sample.rows.filter((row) => rowMatchesFilters(row, filters) && rowMatchesSearch(row, reportSearch, t));
  const displayedRows = selectedReport === "salesReport.tickets" && ticketCustomerDisplayMode === "name"
    ? matchingRows.map((row) => ({ ...row, customer: row.customerName || row.customer }))
    : matchingRows;
  const activeSort = sortByReport[selectedReport] ?? null;
  const filteredRows = sortReportRows(displayedRows, activeSort, locale);
  const filteredTotals = buildFilteredTotals(selectedReport, sample, filteredRows);
  const invoicedTicketTotal = buildInvoicedTicketTotal(selectedReport, filteredTotals);
  const ticketCounters = buildTicketReportCounters(filteredRows);
  const warehouseReconciliation = (() => {
    const inputs = (reports["salesReport.inputWarehouse"]?.rows ?? []).filter((row) => rowMatchesFilters(row, filters));
    const outputs = (reports["salesReport.warehouseOutputs"]?.rows ?? []).filter((row) => rowMatchesFilters(row, filters));
    const inputUnits = inputs.reduce((sum, row) => sum + parseAmount(row.productCount ?? ""), 0);
    const outputUnits = outputs.reduce((sum, row) => sum + parseAmount(row.productCount ?? ""), 0);
    return {
      inputUnits,
      outputUnits,
      unitBalance: inputUnits - outputUnits,
      purchaseValue: inputs.reduce((sum, row) => sum + parseAmount(row.total ?? ""), 0),
      saleValue: outputs.reduce((sum, row) => sum + parseAmount(row.total ?? ""), 0)
    };
  })();
  const selectedDailySummary = sample.dailySummaries?.[filters.dateFrom] ?? emptyDailySalesSummary(filters.dateFrom);
  const selectedRowIndex = selectedRowByReport[selectedReport] ?? -1;
  const selectedReportRow = filteredRows[selectedRowIndex];
  const canOpenSelectedActivity = canOpenOperationalTimeline(app, session, selectedReport, selectedReportRow);
  const canOpenSelectedRectification = canManageSalesInvoiceRectification(
    app,
    session,
    selectedReport,
    selectedReportRow
  );
  const canAccessRectification = canAccessSalesInvoiceRectification(session);
  const canConvertSelectedTicket = canConvertSelectedTicketToInvoice(session, selectedReport, selectedReportRow);
  const ticketInvoiceAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "CONVERT_TICKET_TO_INVOICE",
    session.permissions
  );
  const canCancelSelectedTicketRow = canCancelSelectedTicket(session, selectedReport, selectedReportRow);
  const ticketCancellationAuthorization = findSaleOperationAuthorization(
    operationSecurity,
    "CANCEL_TICKET",
    session.permissions
  );
  const selectedIsRectificationDraft = selectedReportRow?.__documentType === "RECTIFICATIVA_VENTA"
    && selectedReportRow.__documentStatus === "BORRADOR";
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
  const hasStatusFilter = !isDailySalesReport && sample.availableAttributes.includes("status");
  const hasWarehouseFilter = !isDailySalesReport && sample.availableAttributes.includes("warehouse");
  const selectedReportPage = reportPages[reportPageKey(selectedReport)];
  const selectedReportLoadError = reportLoadErrors[selectedReport] ?? "";

  useEffect(() => {
    try {
      localStorage.setItem(
        ticketCustomerDisplayStorageKey(app, session.username),
        ticketCustomerDisplayMode
      );
    } catch {
      // The preference is optional when local storage is unavailable.
    }
  }, [app, session.username, ticketCustomerDisplayMode]);

  useEffect(() => {
    if (reportNotice?.kind !== "success") return;
    const timeout = window.setTimeout(() => setReportNotice(null), 4500);
    return () => window.clearTimeout(timeout);
  }, [reportNotice]);

  useEffect(() => {
    if (!session.accessToken) {
      setOperationSecurity(null);
      return;
    }
    let cancelled = false;
    request<SalesOperationSecurityConfiguration>("/sales/operation-security", {
      token: session.accessToken
    }).then((configuration) => {
      if (!cancelled) setOperationSecurity(configuration);
    }).catch(() => {
      if (!cancelled) setOperationSecurity(null);
    });
    return () => {
      cancelled = true;
    };
  }, [request, session.accessToken]);

  useOutsidePointerDown(printMenuOpen, printMenuRef, () => setPrintMenuOpen(false));
  useOutsidePointerDown(moreActionsOpen, moreActionsRef, () => setMoreActionsOpen(false));
  useOutsidePointerDown(userMenuOpen, userMenuRef, () => setUserMenuOpen(false));
  useOutsidePointerDown(languageOpen, languagePickerRef, () => setLanguageOpen(false));

  reportShortcutActionsRef.current = {
    F5: () => { void printSelectedDocument(); },
    F6: () => { void exportExcelReport(); },
    F7: () => { void exportPdfReport(); }
  };

  useEffect(() => {
    function handleReportOutputShortcut(event: KeyboardEvent) {
      if (isSalesActivityReport) return;
      if (event.ctrlKey || event.altKey || event.metaKey || event.shiftKey || event.repeat) return;
      if (event.key !== "F5" && event.key !== "F6" && event.key !== "F7") return;
      event.preventDefault();
      event.stopPropagation();
      reportShortcutActionsRef.current[event.key]();
    }

    window.addEventListener("keydown", handleReportOutputShortcut, true);
    return () => window.removeEventListener("keydown", handleReportOutputShortcut, true);
  }, [isSalesActivityReport]);

  useEffect(() => {
    if (!isSalesActivityReport) {
      normalizeRequiredTotal(selectedReportTableLayout);
    }
  }, [isSalesActivityReport, selectedReport, selectedReportTableLayout.layout]);

  useEffect(() => {
    if (visualReport !== selectedReport && !isDailyVisualReport) {
      normalizeRequiredTotal(inactiveVisualTableLayout);
    }
  }, [inactiveVisualTableLayout.layout, isDailyVisualReport, selectedReport, visualReport]);

  useEffect(() => {
    let cancelled = false;
    void loadVisualizationPreferences(app, session.accessToken)
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
  }, [app, loadVisualizationPreferences, session.accessToken]);
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
      setReportWarehouses([]);
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
        const [ticketResource, invoiceResource, deliveryNoteResource, warehouseOutputResource, warehouseInputResource, warehouseResource] = await Promise.all([
          loadReportResource<PagedResult<DocumentView>>(request, reportPagePath("tickets"), token, { items: [], nextCursor: null, hasMore: false }),
          loadReportResource<PagedResult<DocumentView>>(request, reportPagePath("invoices"), token, { items: [], nextCursor: null, hasMore: false }),
          loadReportResource<PagedResult<DocumentView>>(request, reportPagePath("deliveryNotes"), token, { items: [], nextCursor: null, hasMore: false }),
          loadReportResource<PagedResult<WarehouseOutputView>>(request, reportPagePath("warehouseOutputs"), token, { items: [], nextCursor: null, hasMore: false }),
          loadReportResource<PagedResult<WarehouseInputView>>(request, reportPagePath("warehouseInputs"), token, { items: [], nextCursor: null, hasMore: false }),
          loadReportResource<ReportWarehouseOption[]>(request, "/warehouses", token, [])
        ]);
        if (!cancelled) {
          const tickets = ticketResource.value;
          const invoices = invoiceResource.value;
          const deliveryNotes = deliveryNoteResource.value;
          const warehouseOutputs = warehouseOutputResource.value;
          const warehouseInputs = warehouseInputResource.value;
          const warehouses = Array.isArray(warehouseResource.value) ? warehouseResource.value : [];
          setReportWarehouses(warehouses);
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
            tickets: { nextCursor: tickets.nextCursor ?? null, hasMore: Boolean(tickets.hasMore) },
            invoices: { nextCursor: invoices.nextCursor ?? null, hasMore: Boolean(invoices.hasMore) },
            deliveryNotes: { nextCursor: deliveryNotes.nextCursor ?? null, hasMore: Boolean(deliveryNotes.hasMore) },
            warehouseOutputs: { nextCursor: warehouseOutputs.nextCursor ?? null, hasMore: Boolean(warehouseOutputs.hasMore) },
            warehouseInputs: { nextCursor: warehouseInputs.nextCursor ?? null, hasMore: Boolean(warehouseInputs.hasMore) }
          });
          setRemoteReports(buildDocumentReports(
            tickets.items,
            invoices.items,
            deliveryNotes.items,
            warehouseOutputs.items,
            [],
            warehouseInputs.items,
            session,
            terminalContext,
            warehouses
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

  useEffect(() => {
    if (!documentPreviewRow) return;
    function closePreviewOnEscape(event: globalThis.KeyboardEvent) {
      if (event.key === "Escape") closeDocumentPreview();
    }
    window.addEventListener("keydown", closePreviewOnEscape);
    return () => window.removeEventListener("keydown", closePreviewOnEscape);
  }, [documentPreviewRow]);

  function closeDocumentPreview() {
    setDocumentPreviewRow(null);
    setDocumentPreview(null);
    setDocumentPreviewLoading(false);
    setDocumentPreviewError("");
    setDocumentPreviewPrinting(false);
    setDocumentPreviewExporting(false);
    setDocumentPreviewPrintMessage("");
  }

  async function loadDocumentDetail(row: Record<string, string>) {
    if (row.__warehouseDocumentPayload) return warehouseDocumentDetail(row);
    return request<SalesDocumentDetail>(
        `/documents/${encodeURIComponent(row.__documentId)}/detail`,
        { token: session.accessToken }
      );
  }

  async function openDocumentPreview(row: Record<string, string>) {
    if (!canOpenDocumentPreview(row)) return;
    setDocumentPreviewRow(row);
    setDocumentPreview(null);
    setDocumentPreviewError("");
    setDocumentPreviewPrintMessage("");
    setDocumentPreviewLoading(true);
    try {
      setDocumentPreview(await loadDocumentDetail(row));
    } catch {
      setDocumentPreviewError(t("salesReport.documentLoadError"));
    } finally {
      setDocumentPreviewLoading(false);
    }
  }

  function openOriginTicket() {
    const ticket = documentPreview?.originTicket;
    if (!ticket?.id) return;
    void openDocumentPreview({
      __documentId: ticket.id,
      ticket: ticket.number || ticket.id
    });
  }

  async function performDocumentPrint(
    documentPreviewRow: Record<string, string>,
    documentPreview: SalesDocumentDetail,
    feedbackTarget: "preview" | "report",
    preopenedBrowserPreview?: Window | null
  ) {
    const setPrintFeedback = (message: string) => {
      if (feedbackTarget === "preview") {
        setDocumentPreviewPrintMessage(message);
        return;
      }
      if (!message) {
        setReportNotice(null);
        return;
      }
      setReportNotice({
        kind: message === t("salesReport.documentPrintError") ? "error" : "success",
        message
      });
    };
    const useDesktopPrinting = hasDesktopHardwareBridge();
    const browserPreview = useDesktopPrinting
      ? null
      : preopenedBrowserPreview ?? window.open("", "_blank", "popup=yes,width=1040,height=820");
    if (!useDesktopPrinting && !browserPreview) {
      setPrintFeedback(t("salesReport.documentPrintError"));
      return;
    }
    try {
      if (documentPreviewRow.__warehouseDocumentPayload) {
        const printRequest = buildWarehouseA4Document({
          title: t(selectedReport),
          locale,
          storeName: terminalContext.storeName,
          terminalCode: terminalContext.terminalCode,
          documentNumber: documentPreview.number ?? undefined,
          issuedAt: documentPreview.date,
          warehouse: documentPreviewRow.warehouse,
          partner: documentPreviewRow.origin || documentPreviewRow.reason || "",
          lines: documentPreview.lines.map((line) => ({
            code: line.code,
            name: line.name,
            quantity: Number(line.quantity),
            unitPrice: Number(line.unitPrice),
            total: Number(line.total)
          })),
          subtotal: Number(documentPreview.base),
          total: Number(documentPreview.total),
          labels: {
            documentNumber: t("warehouseDocument.print.documentNumber"),
            warehouse: t("warehouseDocument.print.warehouse"),
            discount: t("warehouseDocument.print.discount"),
            partner: t("warehouseDocument.print.partner"),
            terminal: t("warehouseDocument.print.terminal"),
            description: t("warehouseDocument.print.description"),
            quantity: t("warehouseDocument.print.quantity"),
            unitPrice: t("warehouseDocument.print.unitPrice"),
            base: t("warehouseDocument.print.base"),
            tax: t("warehouseDocument.print.tax"),
            taxIncluded: t("warehouseDocument.print.taxIncluded"),
            yes: t("warehouseDocument.print.yes"),
            no: t("warehouseDocument.print.no"),
            mixed: t("warehouseDocument.print.mixed"),
            total: t("warehouseDocument.print.total"),
            print: t("warehouseDocument.print.print"),
            close: t("warehouseDocument.print.close")
          }
        });
        if (browserPreview) {
          writeWarehouseDocumentPreview(browserPreview, printRequest, { autoPrint: true });
          setPrintFeedback(t("salesReport.documentPrintSuccess"));
          return;
        }
        const outcome = await printWarehouseA4Document(printRequest);
        setPrintFeedback(outcome.ok
          ? t("salesReport.documentPrintSuccess")
          : t("salesReport.documentPrintError"));
        return;
      }
      if (documentPreview.type === "TICKET") {
        if (documentPreview.status === "ANULADO") {
          const receipt = await request<TicketCancellationReceipt>(
            `/tickets/${encodeURIComponent(documentPreviewRow.__documentId)}/cancellation-receipt`,
            { token: session.accessToken }
          );
          if (browserPreview) {
            if (!receipt.renderedPdf) throw new Error("ticket_cancellation_rendered_pdf_missing");
            showPdfPreview(browserPreview, renderedPdfBlob(receipt.renderedPdf));
            setPrintFeedback(t("salesReport.documentPrintSuccess"));
            return;
          }
          const result = await getHardwareBridge().printTicket({
            requireRenderedDocument: Boolean(receipt.renderedPdf && receipt.ticketRenderedImage),
            layout: "CANCELLATION_RECEIPT",
            title: t("sale.ticketCancel.receipt.title"),
            notice: t("sale.ticketCancel.receipt.nonFiscal"),
            documentNumber: `AN-${receipt.originalTicketNumber}`,
            storeName: terminalContext.storeName,
            terminalCode: terminalContext.terminalCode,
            issuedAt: receipt.cancelledAt,
            details: [
              { label: t("sale.ticketCancel.receipt.originalTicket"), value: receipt.originalTicketNumber },
              { label: t("sale.ticketCancel.receipt.reason"), value: receipt.reason },
              { label: t("sale.ticketCancel.receipt.operator"), value: receipt.operatorUsername },
              { label: t("sale.ticketCancel.receipt.authorizer"), value: receipt.authorizerUsername }
            ],
            lines: [],
            payments: receipt.payments.map((payment) => ({
              method: payment.method,
              amount: Number(payment.amount),
              reference: payment.reference ?? undefined
            })),
            total: Number(receipt.total),
            labels: {
              terminal: t("sale.ticketCancel.receipt.terminal"),
              item: "",
              quantity: "",
              price: "",
              total: t("sale.ticketCancel.receipt.total")
            },
            ...(receipt.renderedPdf ? { renderedPdf: receipt.renderedPdf } : {}),
            ...(receipt.ticketRenderedImage ? {
              documentRaster: `data:${receipt.ticketRenderedImage.contentType};base64,${receipt.ticketRenderedImage.base64}`
            } : {})
          });
          setPrintFeedback(result.ok
            ? t("salesReport.documentPrintSuccess")
            : t("salesReport.documentPrintError"));
          return;
        }
        const snapshot = await request<ConfirmedTicketPrintSnapshot>(
          `/tickets/${encodeURIComponent(documentPreviewRow.__documentId)}/print`,
          { token: session.accessToken }
        );
        if (browserPreview) {
          let pdf: Blob;
          if (snapshot.ticketRenderedPdf) {
            pdf = renderedPdfBlob(snapshot.ticketRenderedPdf);
          } else {
            const response = await fetch(
              `${apiBaseUrl}/tickets/${encodeURIComponent(documentPreviewRow.__documentId)}/pdf`,
              { headers: { Authorization: `Bearer ${session.accessToken}` } }
            );
            if (!response.ok) throw new Error(await salesReportResponseError(response));
            pdf = await response.blob();
          }
          showPdfPreview(browserPreview, pdf);
          setPrintFeedback(t("salesReport.documentPrintSuccess"));
          return;
        }
        const outcome = await outputConfirmedTicket(
          snapshot,
          terminalContext,
          "DEFAULT",
          locale
        );
        setPrintFeedback(outcome.status === "FAILED"
          ? t("salesReport.documentPrintError")
          : outcome.status === "PRINTED" ? t("salesReport.documentPrintSuccess") : "");
        return;
      }
      const snapshot = await request<SalesDocumentPrintCopy>(
        `/documents/${encodeURIComponent(documentPreviewRow.__documentId)}/print-copy`,
        { token: session.accessToken }
      );
      const commercialDocument = { ...snapshot, kind: "COMMERCIAL_DOCUMENT" } as const;
      if (browserPreview) {
        if (snapshot.renderedPdf) {
          showPdfPreview(browserPreview, renderedPdfBlob(snapshot.renderedPdf));
        } else {
          writeWarehouseDocumentPreview(
            browserPreview,
            commercialDocumentAsA4Document(commercialDocument, terminalContext, locale),
            { autoPrint: true }
          );
        }
        setPrintFeedback(t("salesReport.documentPrintSuccess"));
        return;
      }
      const outcome = await printCommercialDocument(commercialDocument, terminalContext, undefined, locale);
      setPrintFeedback(outcome.status === "FAILED"
        ? t("salesReport.documentPrintError")
        : outcome.status === "PRINTED" ? t("salesReport.documentPrintSuccess") : "");
    } catch {
      browserPreview?.close();
      setPrintFeedback(t("salesReport.documentPrintError"));
    }
  }

  async function printDocumentCopy() {
    if (!documentPreviewRow || !documentPreview || documentPreviewPrinting) return;
    setDocumentPreviewPrinting(true);
    setDocumentPreviewPrintMessage("");
    try {
      await performDocumentPrint(documentPreviewRow, documentPreview, "preview");
    } finally {
      setDocumentPreviewPrinting(false);
    }
  }

  async function printSelectedDocument() {
    if (!selectedReportRow || !canOpenDocumentPreview(selectedReportRow) || reportDocumentPrinting) return;
    const preopenedBrowserPreview = hasDesktopHardwareBridge()
      ? undefined
      : window.open("", "_blank", "popup=yes,width=1040,height=820");
    if (preopenedBrowserPreview === null) {
      setReportNotice({ kind: "error", message: t("salesReport.documentPrintError") });
      return;
    }
    setReportDocumentPrinting(true);
    setReportNotice({ kind: "info", message: t("salesReport.documentPrinting") });
    try {
      const detail = await loadDocumentDetail(selectedReportRow);
      await performDocumentPrint(selectedReportRow, detail, "report", preopenedBrowserPreview);
    } catch {
      preopenedBrowserPreview?.close();
      setReportNotice({ kind: "error", message: t("salesReport.documentPrintError") });
    } finally {
      setReportDocumentPrinting(false);
    }
  }

  async function exportDocumentCopyExcel() {
    if (!documentPreviewRow || !session.accessToken || documentPreviewExporting) return;
    setDocumentPreviewExporting(true);
    setDocumentPreviewPrintMessage("");
    try {
      const warehouseKind = documentPreviewRow.__warehouseDocumentKind === "INPUT"
        ? "warehouse-inputs"
        : "warehouse-outputs";
      const exportPath = documentPreviewRow.__documentId
        ? `documents/${encodeURIComponent(documentPreviewRow.__documentId)}`
        : `${warehouseKind}/${encodeURIComponent(documentPreviewRow.__warehouseDocumentId)}`;
      const response = await fetch(
        `${apiBaseUrl}/excel/${exportPath}/export`,
        { headers: { Authorization: `Bearer ${session.accessToken}` } }
      );
      if (!response.ok) throw new Error(await salesReportResponseError(response));
      const bytes = new Uint8Array(await response.arrayBuffer());
      const documentNumber = documentPreview?.number
        || documentPreviewRow.invoice
        || documentPreviewRow.deliveryNote
        || documentPreviewRow.input
        || documentPreviewRow.output
        || "documento";
      const safeNumber = documentNumber.replace(/[<>:"/\\|?*\u0000-\u001f]/g, "-");
      await saveExportBytes(
        bytes,
        `${safeNumber || "documento"}.xlsx`,
        "xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
      );
      setDocumentPreviewPrintMessage(t("salesReport.documentExcelSuccess"));
    } catch {
      setDocumentPreviewPrintMessage(t("salesReport.documentExcelError"));
    } finally {
      setDocumentPreviewExporting(false);
    }
  }

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
    return keys.map((key) => ({ key, label: t(reportAttributeLabelKey(selectedReport, key)) }));
  }

  async function saveExportBytes(
    bytes: Uint8Array,
    fileName: string,
    extension: "xlsx" | "pdf",
    mimeType: string
  ) {
    if (window.tpvDesktop?.reports) {
      const result = await window.tpvDesktop.reports.saveFile({
        defaultFileName: fileName,
        filters: [{
          name: extension === "xlsx" ? "Excel" : "PDF",
          extensions: [extension]
        }],
        bytes
      });
      if (!result.ok) throw new Error(result.message);
      return;
    }
    const url = URL.createObjectURL(new Blob([bytes.slice().buffer as ArrayBuffer], { type: mimeType }));
    const link = document.createElement("a");
    link.href = url;
    link.download = fileName;
    link.click();
    URL.revokeObjectURL(url);
  }

  async function exportExcelReport() {
    if (!session.accessToken || reportExportBusy) return;
    setPrintMenuOpen(false);
    setReportNotice(null);
    setReportExportBusy(true);
    setReportExportProgress(10);
    setReportNotice({ kind: "info", message: t("salesReport.exportingExcel") });
    try {
      const response = await fetch(`${apiBaseUrl}/sales-reports/export`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Accept-Language": localeTag(locale),
          Authorization: `Bearer ${session.accessToken}`
        },
        body: JSON.stringify({
          reportKey: selectedReport,
          filters,
          search: reportSearch,
          columns: exportColumns()
        })
      });
      if (!response.ok) throw new Error(await salesReportResponseError(response));
      setReportExportProgress(70);
      const bytes = new Uint8Array(await response.arrayBuffer());
      const fileName = reportFileName("xlsx");
      setReportExportProgress(90);
      await saveExportBytes(
        bytes,
        fileName,
        "xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
      );
      setReportExportProgress(100);
      setReportNotice({ kind: "success", message: t("salesReport.exportExcelSuccess") });
    } catch (error) {
      setReportNotice({
        kind: "error",
        message: `${t("salesReport.exportFailed")}: ${error instanceof Error ? error.message : ""}`
      });
    } finally {
      setReportExportBusy(false);
      window.setTimeout(() => setReportExportProgress(0), 700);
    }
  }

  async function exportPdfReport() {
    if (!session.accessToken || reportExportBusy) return;
    setPrintMenuOpen(false);
    setReportExportBusy(true);
    setReportExportProgress(10);
    setReportNotice({ kind: "info", message: t("salesReport.exportingPdf") });
    try {
      const response = await fetch(`${apiBaseUrl}/sales-reports/export-pdf`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Accept-Language": localeTag(locale),
          Authorization: `Bearer ${session.accessToken}`
        },
        body: JSON.stringify({
          reportKey: selectedReport,
          filters,
          search: reportSearch,
          columns: exportColumns()
        })
      });
      if (!response.ok) throw new Error(await salesReportResponseError(response));
      setReportExportProgress(75);
      const bytes = new Uint8Array(await response.arrayBuffer());
      await saveExportBytes(bytes, reportFileName("pdf"), "pdf", "application/pdf");
      setReportExportProgress(100);
      setReportNotice({ kind: "success", message: t("salesReport.exportPdfSuccess") });
    } catch (error) {
      setReportNotice({
        kind: "error",
        message: `${t("salesReport.exportFailed")}: ${error instanceof Error ? error.message : ""}`
      });
    } finally {
      setReportExportBusy(false);
      window.setTimeout(() => setReportExportProgress(0), 700);
    }
  }

  async function printCurrentReport() {
    setReportNotice(null);
    if (!window.tpvDesktop?.reports) {
      flushSync(() => setPrintMenuOpen(false));
      window.print();
      return;
    }
    setPrintMenuOpen(false);
    try {
      const result = await window.tpvDesktop.reports.print();
      if (!result.ok) {
        setReportNotice({
          kind: "error",
          message: `${t("salesReport.printFailed")}: ${result.message}`
        });
      }
    } catch (error) {
      setReportNotice({
        kind: "error",
        message: `${t("salesReport.printFailed")}: ${error instanceof Error ? error.message : ""}`
      });
    }
  }

  function selectReport(reportKey: string) {
    const defaultFilters = createDefaultFilters();
    setSelectedReport(reportKey);
    setFilters(defaultFilters);
    setDraftFilters(defaultFilters);
    setDateRangeText(formatDateRange(defaultFilters, locale));
    setDateRangeStart(null);
    setOpenFilterControl(null);
    setSelectedRowByReport((current) => ({ ...current, [reportKey]: current[reportKey] ?? -1 }));
  }

  function openFilters() {
    setPrintMenuOpen(false);
    setDraftFilters(filters);
    setDateRangeText(formatDateRange(filters, locale));
    setDateRangeStart(null);
    setOpenFilterControl(null);
    setFilterOpen(true);
  }

  function updateDraftFilter(key: keyof ReportFilters, value: string) {
    setDraftFilters((current) => ({ ...current, [key]: value }));
  }

  function updateReportSearch(value: string) {
    setReportSearch(value);
    setSelectedRowByReport((current) => ({ ...current, [selectedReport]: -1 }));
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
        pageKey === "tickets" ? nextPage.items as DocumentView[] : [],
        pageKey === "invoices" ? nextPage.items as DocumentView[] : [],
        pageKey === "deliveryNotes" ? nextPage.items as DocumentView[] : [],
        pageKey === "warehouseOutputs" ? nextPage.items as WarehouseOutputView[] : [],
        [],
        pageKey === "warehouseInputs" ? nextPage.items as WarehouseInputView[] : [],
        session,
        terminalContext,
        reportWarehouses
      );
      const affectedReportsByPageKey: Record<ReportPageKey, string[]> = {
        tickets: ["salesReport.tickets"],
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

  useEffect(() => {
    const container = reportTableScrollRef.current;
    if (!container || reportLoading || reportLoadingMore || !selectedReportPage?.hasMore) {
      return;
    }
    if (container.scrollHeight <= container.clientHeight + 80) {
      void loadMoreReportRows();
    }
  }, [
    reportLoading,
    reportLoadingMore,
    selectedReport,
    selectedReportPage?.hasMore,
    selectedReportPage?.nextCursor
  ]);

  function handleReportTableScroll(event: UIEvent<HTMLDivElement>) {
    const container = event.currentTarget;
    const remaining = container.scrollHeight - container.scrollTop - container.clientHeight;
    if (remaining <= 80) {
      void loadMoreReportRows();
    }
  }

  function clearFilters() {
    const defaultFilters = createDefaultFilters();
    setDraftFilters(defaultFilters);
    setFilters(defaultFilters);
    setDateRangeText(formatDateRange(defaultFilters, locale));
    setDateRangeStart(null);
    setOpenFilterControl(null);
    setSelectedRowByReport((current) => ({ ...current, [selectedReport]: -1 }));
  }

  function applyFilters() {
    const nextFilters = draftFilters;
    setFilters(nextFilters);
    setDateRangeText(formatDateRange(nextFilters, locale));
    setSelectedRowByReport((current) => ({ ...current, [selectedReport]: -1 }));
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
    const range = parseDateRangeInput(value);
    if (range) {
      setDraftFilters((current) => ({ ...current, ...range }));
      setDateRangeStart(null);
    }
  }

  function applyDateRangeText() {
    const range = parseDateRangeInput(dateRangeText);
    const nextFilters = range ? { ...draftFilters, ...range } : draftFilters;
    setDraftFilters(nextFilters);
    setFilters(nextFilters);
    setDateRangeText(formatDateRange(nextFilters, locale));
    setSelectedRowByReport((current) => ({ ...current, [selectedReport]: -1 }));
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
      addFilter(t("salesReport.column.date"), formatDateRange(filters, locale));
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
            placeholder={t("salesReport.filter.dateRangePlaceholder")}
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
              <span>{selectedStart ? selectedDaysText(dateRangeDayCount(selectedStart, selectedEnd), locale) : t("salesReport.filter.pickDateFrom")}</span>
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
    return (
      <div className={`filter-field report-filter-select ${wide ? "filter-wide" : ""}`}>
        <span id={`report-filter-${field}-label`}>{label}</span>
        <ErpSelect
          value={draftFilters[field]}
          options={options}
          aria-labelledby={`report-filter-${field}-label`}
          onChange={(value) => updateDraftFilter(field, value)}
        />
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

  function toggleSort(attribute: string) {
    setSortByReport((current) => {
      const previous = current[selectedReport];
      const next: ReportSort | null = previous?.attribute !== attribute
        ? { attribute, direction: "asc" }
        : previous.direction === "asc"
          ? { attribute, direction: "desc" }
          : null;
      return { ...current, [selectedReport]: next };
    });
    selectRow(-1);
  }

  function applyQuickFilter(kind: "today" | "week" | "month" | "pending") {
    const next = kind === "pending"
      ? { ...filters, status: filters.status ? "" : "salesReport.status.pending" }
      : { ...filters, ...quickDateRange(kind) };
    setFilters(next);
    setDraftFilters(next);
    setDateRangeText(formatDateRange(next, locale));
    selectRow(-1);
  }

  function saveCurrentView() {
    const name = savedViewName.trim();
    if (!name) return;
    const view: SavedReportView = {
      id: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
      name,
      reportKey: selectedReport,
      filters,
      search: reportSearch,
      sort: activeSort,
      layout: selectedReportTableLayout.layout
    };
    const next = [...savedViews.filter((candidate) =>
      candidate.reportKey !== selectedReport || candidate.name.toLocaleLowerCase() !== name.toLocaleLowerCase()
    ), view];
    setSavedViews(next);
    writeSavedReportViews(app, session.username, next);
    setSelectedSavedViewId(view.id);
    setSavedViewName("");
    setReportNotice({ kind: "success", message: t("salesReport.views.saved") });
  }

  function applySavedView() {
    const view = savedViews.find((candidate) => candidate.id === selectedSavedViewId);
    if (!view || view.reportKey !== selectedReport) return;
    setFilters(view.filters);
    setDraftFilters(view.filters);
    setReportSearch(view.search);
    setSortByReport((current) => ({ ...current, [selectedReport]: view.sort }));
    selectedReportTableLayout.replaceLayout(view.layout);
    setDateRangeText(formatDateRange(view.filters, locale));
    setViewsOpen(false);
    selectRow(-1);
  }

  function deleteSavedView() {
    const next = savedViews.filter((candidate) => candidate.id !== selectedSavedViewId);
    setSavedViews(next);
    writeSavedReportViews(app, session.username, next);
    setSelectedSavedViewId("");
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
      const numeric = (value: number | string | undefined) => Number(value ?? 0);
      const salesTotal = numeric(dailyCommercialReport.salesTotal ?? (
        numeric(dailyCommercialReport.invoiced)
        + numeric(dailyCommercialReport.ticketSales)
        - numeric(dailyCommercialReport.refunds)
      ));
      const emptyBreakdown: DailyPaymentBreakdown = {
        cash: 0, card: 0, transfer: 0, voucher: 0, pending: 0, other: 0
      };
      const sales = dailyCommercialReport.salesByPaymentMethod ?? {
        ...emptyBreakdown, other: salesTotal
      };
      const pendingCollections = dailyCommercialReport.pendingCollectionsByPaymentMethod ?? {
        ...emptyBreakdown, other: dailyCommercialReport.priorDebtCollected
      };
      const refundMethods = dailyCommercialReport.refundsByPaymentMethod ?? {
        ...emptyBreakdown, other: dailyCommercialReport.refunds ?? 0
      };
      const hasAmount = (values: Array<number | string | undefined>) => (
        values.some((value) => Math.abs(numeric(value)) >= 0.005)
      );
      const pendingOther = numeric(pendingCollections.voucher)
        + numeric(pendingCollections.pending)
        + numeric(pendingCollections.other);
      const hasPendingCollections = hasAmount([
        pendingCollections.cash, pendingCollections.card,
        pendingCollections.transfer, pendingOther
      ]);
      const hasRefunds = hasAmount([
        refundMethods.cash, refundMethods.card, refundMethods.transfer,
        refundMethods.voucher, refundMethods.other
      ]);
      const amountLine = (key: string, value: number | string, className = "daily-payment-line") => (
        <div className={className} key={key}>
          <span>{t(key)}</span>
          <strong>{`${formatAmount(numeric(value))} €`}</strong>
        </div>
      );
      const days = dailyCommercialReport.days ?? [];
      return (
        <div className="daily-summary-scroll">
          <div className="daily-authoritative-report">
            <section className="daily-summary-card daily-authoritative-summary" aria-label={t("salesReport.daily.authoritativeSummary")}>
              <div className="daily-period-summary-heading">
                <div>
                  <span>{t("salesReport.daily.periodSummary")}</span>
                  <h2 id="daily-sales-methods-title">{t("salesReport.daily.salesSection")}</h2>
                </div>
                {days.length > 0 && <strong>{`${days.length} ${t("salesReport.daily.daysIncluded")}`}</strong>}
              </div>
              <section className="daily-summary-group" aria-labelledby="daily-sales-methods-title">
                {amountLine("salesReport.daily.totalSales", salesTotal, "daily-final-total-line")}
                {amountLine("EFECTIVO", sales.cash)}
                {amountLine("TARJETA", sales.card)}
                {amountLine("TRANSFERENCIA", sales.transfer)}
                {amountLine("salesReport.daily.incomingVouchers", sales.voucher)}
                {amountLine("salesReport.daily.pending", sales.pending)}
                {hasAmount([sales.other]) && amountLine("salesReport.daily.other", sales.other)}
              </section>
              {hasPendingCollections && (
                <section className="daily-summary-group" aria-labelledby="daily-pending-collections-title">
                  <h3 id="daily-pending-collections-title">{t("salesReport.daily.pendingCollectionsSection")}</h3>
                  {amountLine("EFECTIVO", pendingCollections.cash)}
                  {amountLine("TARJETA", pendingCollections.card)}
                  {amountLine("TRANSFERENCIA", pendingCollections.transfer)}
                  {hasAmount([pendingOther]) && amountLine("salesReport.daily.other", pendingOther)}
                </section>
              )}
              {hasRefunds && (
                <section className="daily-summary-group" aria-labelledby="daily-refunds-title">
                  <h3 id="daily-refunds-title">{t("salesReport.daily.refundsSection")}</h3>
                  {amountLine("EFECTIVO", refundMethods.cash)}
                  {amountLine("TARJETA", refundMethods.card)}
                  {amountLine("salesReport.daily.outgoingVouchers", refundMethods.voucher)}
                  {amountLine("TRANSFERENCIA", refundMethods.transfer)}
                  {hasAmount([refundMethods.other]) && amountLine("salesReport.daily.other", refundMethods.other)}
                </section>
              )}
              <section className="daily-summary-group daily-cash-summary-group" aria-labelledby="daily-cash-title">
                <h3 id="daily-cash-title">{t("salesReport.daily.cashSection")}</h3>
                {dailyCommercialReport.openingCashFund != null
                  && amountLine("salesReport.daily.openingCashFund", dailyCommercialReport.openingCashFund)}
                {amountLine("salesReport.daily.cashEntries", dailyCommercialReport.cashEntries ?? 0)}
                {amountLine("salesReport.daily.cashWithdrawals", dailyCommercialReport.cashWithdrawals ?? 0)}
                {dailyCommercialReport.expectedCash != null
                  && amountLine("salesReport.daily.expectedCash", dailyCommercialReport.expectedCash, "daily-final-total-line")}
              </section>
            </section>
            {days.length > 0 && (
              <section className="daily-breakdown-card" aria-label={t("salesReport.daily.dailyBreakdown")}>
                <header className="daily-breakdown-heading">
                  <div>
                    <span>{t("salesReport.daily.breakdownEyebrow")}</span>
                    <h2>{t("salesReport.daily.dailyBreakdown")}</h2>
                  </div>
                  <p>{formatDateRange(filters, locale)}</p>
                </header>
                <div className="daily-breakdown-scroll">
                  <table className="daily-breakdown-table">
                    <thead>
                      <tr>
                        <th scope="col">{t("salesReport.daily.date")}</th>
                        <th scope="col">{t("salesReport.daily.ticketCount")}</th>
                        <th scope="col">{t("salesReport.daily.invoiceCount")}</th>
                        <th scope="col">{t("salesReport.daily.totalSales")}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {days.map((day) => (
                        <tr key={day.date}>
                          <th scope="row">{formatFilterDate(day.date, locale)}</th>
                          <td>{day.ticketCount ?? 0}</td>
                          <td>{day.invoiceCount ?? 0}</td>
                          <td>{`${formatAmount(numeric(day.salesTotal))} €`}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            )}
          </div>
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
    const todayRange = quickDateRange("today");
    const weekRange = quickDateRange("week");
    const monthRange = quickDateRange("month");
    const periodLabel = filters.dateFrom === todayRange.dateFrom && filters.dateTo === todayRange.dateTo
      ? t("salesReport.quick.today")
      : filters.dateFrom === weekRange.dateFrom && filters.dateTo === weekRange.dateTo
        ? t("salesReport.quick.week")
        : filters.dateFrom === monthRange.dateFrom && filters.dateTo === monthRange.dateTo
          ? t("salesReport.quick.month")
          : formatDateRange(filters, locale);
    const selectedDocumentCanPrint = canOpenDocumentPreview(selectedReportRow) && !reportDocumentPrinting;
    const weekIsSelected = filters.dateFrom === weekRange.dateFrom && filters.dateTo === weekRange.dateTo;
    const monthIsSelected = filters.dateFrom === monthRange.dateFrom && filters.dateTo === monthRange.dateTo;

    return (
      <header className="report-data-toolbar report-command-toolbar">
        <div className="report-command-period">
          <span>{t("salesReport.currentPeriod")}</span>
          <strong>{periodLabel}</strong>
          <small>{formatFilterDate(filters.dateFrom, locale)} — {formatFilterDate(filters.dateTo, locale)}</small>
        </div>
        <div className="report-quick-filters" aria-label={t("salesReport.quickFilters")}>
          <button
            type="button"
            className={weekIsSelected ? "active" : ""}
            aria-pressed={weekIsSelected}
            onClick={() => applyQuickFilter("week")}
          >
            {t("salesReport.quick.week")}
          </button>
          <button
            type="button"
            className={monthIsSelected ? "active" : ""}
            aria-pressed={monthIsSelected}
            onClick={() => applyQuickFilter("month")}
          >
            {t("salesReport.quick.month")}
          </button>
        </div>
        <div className="report-output-cluster">
          <div className="report-output-actions">
            <button
              type="button"
              aria-keyshortcuts="F5"
              title={t("salesReport.printSelectedTitle")}
              disabled={!selectedDocumentCanPrint}
              onClick={() => void printSelectedDocument()}
            >
              <span>{t("salesReport.print")}</span><kbd aria-hidden="true">{REPORT_OUTPUT_SHORTCUTS.print}</kbd>
            </button>
            <button
              type="button"
              aria-keyshortcuts="F6"
              title={t("salesReport.exportVisibleExcelTitle")}
              disabled={reportExportBusy}
              onClick={() => void exportExcelReport()}
            >
              <span>{t("salesReport.excel")}</span><kbd aria-hidden="true">{REPORT_OUTPUT_SHORTCUTS.excel}</kbd>
            </button>
            <button
              type="button"
              aria-keyshortcuts="F7"
              title={t("salesReport.exportVisiblePdfTitle")}
              disabled={reportExportBusy}
              onClick={() => void exportPdfReport()}
            >
              <span>{t("salesReport.pdf")}</span><kbd aria-hidden="true">{REPORT_OUTPUT_SHORTCUTS.pdf}</kbd>
            </button>
          </div>
        </div>
        <div className="report-utility-actions">
          <button type="button" onClick={openFilters}>
            <img alt="" className="report-action-icon" src={filterIcon} />
            {t("salesReport.filter")}
          </button>
          <button
            type="button"
            hidden={isDailySalesReport}
            onClick={() => {
              setMoreActionsOpen(false);
              setVisualReport(selectedReport);
              setVisualizationOpen(true);
            }}
          >
            <img alt="" className="report-action-icon" src={visualizeIcon} />
            {t("salesReport.visualization")}
          </button>
          {selectedReport === "salesReport.tickets" && session.permissions.some(
            (permission) => ["ADMIN", "GESTION_VENTAS", "GESTION_CUENTAS", "TICKETS_CANCEL", "VENTA"].includes(permission)
          ) && (
            <button
              type="button"
              className="report-danger-action"
              disabled={!canCancelSelectedTicketRow || !ticketCancellationAuthorization}
              title={t("sale.ticketCancel.title")}
              onClick={() => {
                if (canCancelSelectedTicketRow && ticketCancellationAuthorization && selectedReportRow?.ticket) {
                  setTicketCancellationNumber(selectedReportRow.ticket);
                }
              }}
            >
              {t("sale.ticketCancel.title")}
            </button>
          )}
          <div className="report-more-actions" ref={moreActionsRef}>
            <button
              type="button"
              aria-expanded={moreActionsOpen}
              aria-haspopup="menu"
              onClick={() => setMoreActionsOpen((open) => !open)}
            >
              {t("salesReport.moreActions")} <span aria-hidden="true">▾</span>
            </button>
            {moreActionsOpen && (
              <div className="report-more-menu" role="menu">
              <button type="button" role="menuitem" onClick={() => {
                setMoreActionsOpen(false);
                setViewsOpen((open) => !open);
              }}>
                {t("salesReport.views")}
              </button>
              {hasStatusFilter && selectedReport !== "salesReport.tickets" && (
                <button type="button" role="menuitem" onClick={() => {
                  setMoreActionsOpen(false);
                  applyQuickFilter("pending");
                }}>
                  {t("salesReport.quick.pending")}
                </button>
              )}
              {app === "gestion" && !isDailySalesReport && (
                <button type="button" role="menuitem" disabled={!canOpenSelectedActivity} onClick={() => {
                  setMoreActionsOpen(false);
                  if (selectedReportRow?.__documentId && canOpenSelectedActivity) {
                    setActivityDocumentId(selectedReportRow.__documentId);
                  }
                }}>
                  {t("salesReport.activity.open")}
                </button>
              )}
              {canOpenDocumentPreview(selectedReportRow) && (
                <button type="button" role="menuitem" onClick={() => {
                  setMoreActionsOpen(false);
                  void openDocumentPreview(selectedReportRow);
                }}>
                  {t("salesReport.openDocument")}
                </button>
              )}
              {app === "gestion" && selectedReport === "salesReport.invoices" && canAccessRectification && (
                <button type="button" role="menuitem" disabled={!canOpenSelectedRectification} onClick={() => {
                  setMoreActionsOpen(false);
                  if (selectedReportRow?.__documentId && canOpenSelectedRectification) {
                    setRectificationTarget({
                      documentId: selectedReportRow.__documentId,
                      continueDraft: selectedIsRectificationDraft
                    });
                  }
                }}>
                  {t(selectedIsRectificationDraft ? "rectification.continue" : "rectification.open")}
                </button>
              )}
              {selectedReport === "salesReport.tickets" && session.permissions.some(
                (permission) => permission === "ADMIN" || permission === "GESTION_VENTAS" || permission === "VENTA"
              ) && (
                <button type="button" role="menuitem" disabled={!canConvertSelectedTicket || !ticketInvoiceAuthorization} onClick={() => {
                  setMoreActionsOpen(false);
                  if (canConvertSelectedTicket && ticketInvoiceAuthorization && selectedReportRow?.ticket) {
                    setTicketInvoiceNumber(selectedReportRow.ticket);
                  }
                }}>
                  {t("sale.shortcut.convertInvoice")}
                </button>
              )}
              </div>
            )}
          </div>
        </div>
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
      {reportNotice && (
        <div
          className={`report-feedback ${reportNotice.kind}`}
          role={reportNotice.kind === "error" ? "alert" : "status"}
          aria-live={reportNotice.kind === "error" ? "assertive" : "polite"}
        >
          <span>{reportNotice.message}</span>
          {reportExportBusy && (
            <progress value={reportExportProgress} max={100} aria-label={reportNotice.message} />
          )}
          <button type="button" aria-label={t("common.close")} onClick={() => setReportNotice(null)}>×</button>
        </div>
      )}
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
            <ModuleNavItem
              icon={<img alt="" className="report-menu-icon" src={reportIcon[reportKey]} />}
              label={t(reportKey)}
              selected={selectedReport === reportKey}
              key={reportKey}
              onClick={() => selectReport(reportKey)}
            />
          ))}

          {availableReports.visibleInputReports.length > 0 && <strong className="report-nav-section">{t("salesReport.input")}</strong>}
          {availableReports.visibleInputReports.map((reportKey) => (
            <ModuleNavItem
              icon={<img alt="" className="report-menu-icon" src={reportIcon[reportKey]} />}
              label={t(reportKey)}
              selected={selectedReport === reportKey}
              key={reportKey}
              onClick={() => selectReport(reportKey)}
            />
          ))}

          <ModuleNavBackButton label={t("common.back")} onBack={onBack} />
        </aside>}

        <section className="report-workspace">
          <header className="report-options">
            <div className="report-heading">
              <h1>{t(selectedReport)}</h1>
              {!isSalesActivityReport && activeFilterDetails.length > 0 && (
                <div className="active-filter-summary" aria-label={t("salesReport.filter")}>
                  {activeFilterDetails.map((filter) => (
                    <span key={`${filter.label}-${filter.value}`}>
                      <strong>{`${filter.label}:`}</strong>
                      {filter.value}
                    </span>
                  ))}
                </div>
              )}
              {!isSalesActivityReport && <div className="report-print-meta">
                <span>
                  {`${t("salesReport.generatedAt")}: ${new Intl.DateTimeFormat(localeTag(locale), {
                    dateStyle: "short",
                    timeStyle: "short"
                  }).format(new Date())}`}
                </span>
                <span>{`${t("salesReport.visibleLines")}: ${filteredRows.length}`}</span>
              </div>}
            </div>
          </header>
          {isSalesActivityReport ? (
            <SalesActivityPanel
              app={app}
              mode={isDailySalesReport ? "daily" : "documents"}
              locale={locale}
              username={session.username}
              token={session.accessToken}
              terminalContext={terminalContext}
              request={request}
            />
          ) : (
            <div className="report-data">
              {renderReportToolbar()}
              {viewsOpen && (
                <section className="report-saved-views" aria-label={t("salesReport.views")}>
                  <div className="report-saved-views__group">
                    <label className="report-saved-views__field report-saved-views__field--select">
                      <span>{t("salesReport.views.available")}</span>
                      <select value={selectedSavedViewId} onChange={(event) => setSelectedSavedViewId(event.target.value)}>
                        <option value="">{t("salesReport.views.select")}</option>
                        {savedViews.filter((view) => view.reportKey === selectedReport).map((view) => (
                          <option key={view.id} value={view.id}>{view.name}</option>
                        ))}
                      </select>
                    </label>
                    <div className="report-saved-views__actions">
                      <button type="button" disabled={!selectedSavedViewId} onClick={applySavedView}>{t("common.apply")}</button>
                      <button className="report-saved-views__delete" type="button" disabled={!selectedSavedViewId} onClick={deleteSavedView}>{t("common.delete")}</button>
                    </div>
                  </div>
                  <div className="report-saved-views__group report-saved-views__group--create">
                    <label className="report-saved-views__field report-saved-views__field--name">
                      <span>{t("salesReport.views.name")}</span>
                      <input value={savedViewName} onChange={(event) => setSavedViewName(event.target.value)} />
                    </label>
                    <button className="report-saved-views__save" type="button" disabled={!savedViewName.trim()} onClick={saveCurrentView}>{t("salesReport.views.save")}</button>
                  </div>
                </section>
              )}
              <div className="report-table-region">
                {isWarehouseDocumentReport(selectedReport) && (
                  <section className="warehouse-reconciliation" aria-label={t("salesReport.reconciliation")}>
                    <header>
                      <strong>{t("salesReport.reconciliation")}</strong>
                      <span>{filters.warehouse || t("salesReport.filter.all")}</span>
                    </header>
                    <div><span>{t("salesReport.reconciliation.inputs")}</span><strong>{formatQuantity(warehouseReconciliation.inputUnits)}</strong></div>
                    <div><span>{t("salesReport.reconciliation.outputs")}</span><strong>{formatQuantity(warehouseReconciliation.outputUnits)}</strong></div>
                    <div><span>{t("salesReport.reconciliation.balance")}</span><strong>{formatQuantity(warehouseReconciliation.unitBalance)}</strong></div>
                    <div><span>{t("salesReport.column.purchaseTotal")}</span><strong>{formatEuroAmount(warehouseReconciliation.purchaseValue, locale)}</strong></div>
                    <div><span>{t("salesReport.column.saleTotal")}</span><strong>{formatEuroAmount(warehouseReconciliation.saleValue, locale)}</strong></div>
                  </section>
                )}
                <div
                  className="report-table-scroll"
                  ref={reportTableScrollRef}
                  onScroll={handleReportTableScroll}
                >
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
                data-report-key={selectedReport}
                style={{ width: "100%", minWidth: `${reportTableWidth}px` }}
              >
                <colgroup>
                  {visibleColumnLayout.map((column) => (
                    <col
                      key={column.key}
                      data-column-key={column.key}
                      style={{ width: column.width }}
                    />
                  ))}
                </colgroup>
                  <thead>
                    <tr>
                      {visibleColumnLayout.map((column) => (
                        <TableLayoutHeaderCell
                          column={column}
                          key={column.key}
                          className={REPORT_MONETARY_ATTRIBUTES.has(column.key) ? "report-column-numeric" : ""}
                          movable={column.key !== "total"}
                          sortDirection={activeSort?.attribute === column.key ? activeSort.direction : null}
                          sortLabel={`${t(reportAttributeLabelKey(selectedReport, column.key))} ${t("salesReport.sort")}`}
                          onSort={() => toggleSort(column.key)}
                          headerAction={selectedReport === "salesReport.tickets" && column.key === "customer" ? (
                            <button
                              type="button"
                              className="report-customer-display-toggle"
                              draggable={false}
                              aria-label={t(ticketCustomerDisplayMode === "code"
                                ? "salesReport.customerDisplay.showName"
                                : "salesReport.customerDisplay.showCode")}
                              title={t(ticketCustomerDisplayMode === "code"
                                ? "salesReport.customerDisplay.showName"
                                : "salesReport.customerDisplay.showCode")}
                              onPointerDown={(event) => event.stopPropagation()}
                              onClick={(event) => {
                                event.stopPropagation();
                                setTicketCustomerDisplayMode((current) => current === "code" ? "name" : "code");
                              }}
                            >
                              {ticketCustomerDisplayMode === "code" ? "N" : "C"}
                            </button>
                          ) : undefined}
                          resizeLabel={`${t("stock.columns.resize")} ${t(reportAttributeLabelKey(selectedReport, column.key))}`}
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
                          {t(reportAttributeLabelKey(selectedReport, column.key))}
                        </TableLayoutHeaderCell>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                      {filteredRows.map((row, rowIndex) => {
                        const isCancelledTicket = selectedReport === "salesReport.tickets"
                          && String(row.__documentStatus ?? "").toUpperCase() === "ANULADO";
                        return (
                          <tr
                            key={`${selectedReport}-${rowIndex}`}
                            className={[
                              selectedRowIndex === rowIndex ? "selected" : "",
                              isCancelledTicket ? "report-table-row--cancelled" : ""
                            ].filter(Boolean).join(" ")}
                            title={isCancelledTicket ? t("salesReport.status.ticketCancelled") : undefined}
                            tabIndex={0}
                            aria-selected={selectedRowIndex === rowIndex}
                            onClick={() => selectRow(rowIndex)}
                            onFocus={() => selectRow(rowIndex)}
                            onDoubleClick={() => {
                              if (canOpenDocumentPreview(row)) void openDocumentPreview(row);
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
                              if (event.key === "Enter" && canOpenDocumentPreview(row)) {
                                event.preventDefault();
                                void openDocumentPreview(row);
                              }
                            }}
                          >
                            {visibleColumnLayout.map((column) => (
                              <td
                                key={column.key}
                                data-column-key={column.key}
                                className={REPORT_MONETARY_ATTRIBUTES.has(column.key) ? "report-column-numeric" : undefined}
                              >
                                {column.key === "status" && isCancelledTicket ? (
                                  <span className="report-status-badge report-status-badge--cancelled">
                                    {translateCompositeReportValue(row[column.key] ?? "", t)}
                                  </span>
                                ) : REPORT_MONETARY_ATTRIBUTES.has(column.key)
                                  ? formatReportDisplayValue(column.key, row[column.key] ?? "", locale)
                                  : translateCompositeReportValue(row[column.key] ?? "", t)}
                              </td>
                            ))}
                          </tr>
                        );
                      })}
                  </tbody>
                  </table>
                  {reportLoadingMore && (
                    <div className="report-auto-load-state" role="status" aria-live="polite">
                      {t("salesReport.loadingMore")}
                    </div>
                  )}
                </div>
              </div>
              <div className="report-total-row">
                <span>{`${t("salesReport.visibleLines")}: ${filteredRows.length}`}</span>
                {invoicedTicketTotal && (
                  <strong>{`${t("salesReport.invoicedTicketTotal")}: ${formatReportDisplayValue("invoicedTicketTotal", invoicedTicketTotal, locale)}`}</strong>
                )}
                {selectedReport === "salesReport.tickets" && (
                  <>
                    <strong>{`${t("salesReport.invoicedTicketCount")}: ${ticketCounters.invoiced}`}</strong>
                    <strong>{`${t("salesReport.cancelledTicketCount")}: ${ticketCounters.cancelled}`}</strong>
                  </>
                )}
                <strong className="report-main-total">
                  {`${t(reportAttributeLabelKey(selectedReport, "total"))}: ${formatReportDisplayValue("total", filteredTotals.total ?? "0.00", locale)}`}
                </strong>
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

      {documentPreviewRow && (
        <div
          className="document-activity-overlay"
          role="dialog"
          aria-modal="true"
          aria-labelledby="report-document-preview-title"
          onMouseDown={(event) => { if (event.target === event.currentTarget) closeDocumentPreview(); }}
        >
          <section className="document-activity-dialog report-document-preview">
            <header>
              <div>
                <span>{t("salesReport.openDocument")}</span>
                <h2 id="report-document-preview-title">
                  {documentPreviewRow.invoice || documentPreviewRow.deliveryNote || documentPreviewRow.ticket
                    || documentPreviewRow.input || documentPreviewRow.output
                    || documentPreviewRow.__documentId || documentPreviewRow.__warehouseDocumentId}
                </h2>
              </div>
              <button type="button" aria-label={t("common.close")} onClick={closeDocumentPreview}>×</button>
            </header>
            <dl className="document-activity-summary">
              {visibleColumnLayout.map((column) => (
                <div key={column.key}>
                  <dt>{t(reportAttributeLabelKey(selectedReport, column.key))}</dt>
                  <dd>{REPORT_MONETARY_ATTRIBUTES.has(column.key)
                    ? formatReportDisplayValue(column.key, documentPreviewRow[column.key] ?? "", locale)
                    : translateCompositeReportValue(documentPreviewRow[column.key] ?? "", t)}</dd>
                </div>
              ))}
            </dl>
            <section className="report-document-lines" aria-labelledby="report-document-lines-title">
              <h3 id="report-document-lines-title">{t("salesDocument.lines")}</h3>
              {documentPreviewLoading && <p role="status">{t("common.loading")}</p>}
              {documentPreviewError && <p role="alert" className="sale-action-error">{documentPreviewError}</p>}
              {!documentPreviewLoading && !documentPreviewError && documentPreview?.lines.length === 0 && (
                <p>{t("salesReport.documentLinesEmpty")}</p>
              )}
              {documentPreview && documentPreview.lines.length > 0 && (
                <div className="report-document-lines-scroll">
                  <table>
                    <thead><tr>
                      <th>{t("sale.searchDialog.code")}</th>
                      <th>{t("sale.searchDialog.name")}</th>
                      <th>{t("sale.main.quantity")}</th>
                      <th>{t("sale.searchDialog.price")}</th>
                      <th>{t("stock.column.discount")}</th>
                      <th>{t("stock.column.tax")}</th>
                      <th>{t("sale.main.total")}</th>
                    </tr></thead>
                    <tbody>
                      {documentPreview.lines.map((line) => (
                        <tr key={line.id}>
                          <td>{line.code}</td>
                          <td>{line.name}</td>
                          <td>{new Intl.NumberFormat(localeTag(locale), { maximumFractionDigits: 3 }).format(Number(line.quantity))}</td>
                          <td>{formatEuroAmount(line.unitPrice, locale)}</td>
                          <td>{`${new Intl.NumberFormat(localeTag(locale), { maximumFractionDigits: 2 }).format(Number(line.discount))} %`}</td>
                          <td>{`${new Intl.NumberFormat(localeTag(locale), { maximumFractionDigits: 2 }).format(Number(line.taxPercentage))} %`}</td>
                          <td>{formatEuroAmount(line.total, locale)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
            <footer>
              {documentPreviewPrintMessage && (
                <span className="report-document-print-message" role="status">{documentPreviewPrintMessage}</span>
              )}
              <div className="report-document-preview-actions">
                {documentPreview?.originTicket && (
                  <button
                    type="button"
                    className="secondary"
                    disabled={documentPreviewLoading}
                    onClick={openOriginTicket}
                  >
                    {t("salesReport.viewOriginTicket")}
                  </button>
                )}
                {canOpenDocumentPreview(documentPreviewRow) && (
                  <>
                    <button
                      type="button"
                      className="secondary"
                      disabled={documentPreviewLoading || Boolean(documentPreviewError) || !documentPreview || documentPreviewExporting}
                      onClick={() => void exportDocumentCopyExcel()}
                    >
                      {documentPreviewExporting
                        ? t("salesReport.documentExportingExcel")
                        : t("salesReport.exportDocumentExcel")}
                    </button>
                    <button
                      type="button"
                      className="secondary"
                      disabled={documentPreviewLoading || Boolean(documentPreviewError) || !documentPreview || documentPreviewPrinting}
                      onClick={() => void printDocumentCopy()}
                    >
                      {documentPreviewPrinting
                        ? t("salesReport.documentPrinting")
                        : t("salesReport.printDocumentCopy")}
                    </button>
                  </>
                )}
                <button type="button" onClick={closeDocumentPreview}>{t("common.close")}</button>
              </div>
            </footer>
          </section>
        </div>
      )}

      {rectificationTarget && session.accessToken && (
        <SalesInvoiceRectificationDialog
          token={session.accessToken}
          locale={locale}
          documentId={rectificationTarget.documentId}
          continueDraft={rectificationTarget.continueDraft}
          canConfirm={canConfirmSalesInvoiceRectification(session)}
          t={t}
          onClose={() => setRectificationTarget(null)}
          onChanged={() => setReportReloadKey((current) => current + 1)}
        />
      )}

      {ticketInvoiceNumber && session.accessToken && ticketInvoiceAuthorization && (
        <Suspense fallback={null}>
          <SaleTicketInvoiceDialog
            token={session.accessToken}
            locale={locale}
            terminalContext={terminalContext}
            currentUsername={session.username}
            initialTicketNumber={ticketInvoiceNumber}
            authorization={ticketInvoiceAuthorization}
            onClose={() => setTicketInvoiceNumber(null)}
            onFiscalMutation={() => setReportReloadKey((current) => current + 1)}
          />
        </Suspense>
      )}

      {ticketCancellationNumber && session.accessToken && ticketCancellationAuthorization && (
        <Suspense fallback={null}>
          <SaleTicketCancellationDialog
            token={session.accessToken}
            locale={locale}
            currentUsername={session.username}
            permissions={session.permissions}
            authorization={ticketCancellationAuthorization}
            terminalContext={terminalContext}
            mode="BY_NUMBER"
            initialTicketNumber={ticketCancellationNumber}
            onClose={() => setTicketCancellationNumber(null)}
            onFiscalMutation={() => setReportReloadKey((current) => current + 1)}
          />
        </Suspense>
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
                      {t(reportAttributeLabelKey(visualReport, attribute))}
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
                        {t(reportAttributeLabelKey(visualReport, attribute))}
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

