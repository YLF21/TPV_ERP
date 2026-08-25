import { useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { apiRequest } from "../api/client";
import { apiBaseUrl } from "../api/runtime";
import { getHardwareBridge, type HardwareConfig } from "../hardware/hardware";
import {
  createSalesActivityTranslator,
  type SalesActivityMessageKey
} from "../i18n/SalesActivityMessages";
import { formatEuroAmount, localeTag } from "../money";
import type { AppKind, LocaleCode, TerminalContext } from "../types";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { visibleTableColumns, type TableColumnDefinition } from "./tableLayoutPreferences";
import { sortTableRows, useTableSortPreference } from "./tableSorting";
import { useTableLayoutPreference } from "./useTableLayoutPreference";
import "./SalesActivityPanel.css";

type Request = <T>(path: string, options?: { token?: string }) => Promise<T>;

type PaymentMethod = "EFECTIVO" | "TARJETA" | "TRANSFERENCIA" | "VALE" | "PENDIENTE" | "OTROS";

type PaymentTotal = { method: PaymentMethod; operationCount: number; amount: number | string };

type ActivityCounts = {
  sales: number;
  returns: number;
  cancelled: number;
  pending: number;
};

type DailyUserSummary = {
  userId?: string | null;
  userName: string;
  netSalesTotal: number | string;
  paymentMethods: PaymentTotal[];
  counts: ActivityCounts;
};

type DailySummary = {
  storeId: string;
  companyName: string;
  storeCode: string;
  date: string;
  netSalesTotal: number | string;
  paymentMethods: PaymentTotal[];
  counts: ActivityCounts;
  users: DailyUserSummary[];
};

type DocumentRow = {
  id: string;
  date: string;
  occurredAt?: string | null;
  ticketNumber: string;
  invoiceNumber: string;
  userName: string;
  paymentMethods: string[];
  kind: "SALE" | "RETURN" | "CANCELLED";
  status: string;
  total: number | string;
};

type DocumentPage = {
  items: DocumentRow[];
  nextCursor?: string | null;
  hasMore: boolean;
  ticketCount: number;
  invoiceCount: number;
  total: number | string;
  dateFrom: string;
  dateTo: string;
};

type RenderedReport = {
  renderedPdf: { contentType: "application/pdf"; base64: string };
  renderedImage?: { contentType: "image/png"; base64: string } | null;
};

type Props = {
  app: AppKind;
  mode: "daily" | "documents";
  locale: LocaleCode;
  username: string;
  token?: string;
  terminalContext: TerminalContext;
  request?: Request;
};

type DateRange = { from: string; to: string; label: string };
type PrintFormat = "A4" | "TICKET_80";
type PrintGrouping = "DAY" | "DOCUMENT";
type DocumentColumnKey =
  | "date"
  | "time"
  | "ticketNumber"
  | "invoiceNumber"
  | "userName"
  | "paymentMethods"
  | "status"
  | "total";

type DocumentColumnDefinition = TableColumnDefinition<DocumentColumnKey> & {
  labelKey: SalesActivityMessageKey;
};

const documentColumnDefinitions: readonly DocumentColumnDefinition[] = [
  { key: "date", labelKey: "date", defaultWidth: 150, minWidth: 112 },
  { key: "time", labelKey: "time", defaultWidth: 92, minWidth: 76 },
  { key: "ticketNumber", labelKey: "ticketNumber", defaultWidth: 185, minWidth: 132 },
  { key: "invoiceNumber", labelKey: "invoiceNumber", defaultWidth: 185, minWidth: 132 },
  { key: "userName", labelKey: "user", defaultWidth: 130, minWidth: 96 },
  { key: "paymentMethods", labelKey: "paymentMethod", defaultWidth: 170, minWidth: 125 },
  { key: "status", labelKey: "status", defaultWidth: 135, minWidth: 104 },
  { key: "total", labelKey: "total", defaultWidth: 125, minWidth: 94 }
] as const;
const documentColumnKeys = documentColumnDefinitions.map((definition) => definition.key);

function isoDate(value: Date) {
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, "0");
  const day = String(value.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function localDate(value: string) {
  const [year, month, day] = value.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function formatDate(value: string, locale: LocaleCode) {
  if (!value) return "";
  return new Intl.DateTimeFormat(localeTag(locale)).format(localDate(value));
}

function endOfMonth(year: number, monthIndex: number) {
  return new Date(year, monthIndex + 1, 0);
}

function currentDayRange(label: string): DateRange {
  const today = isoDate(new Date());
  return { from: today, to: today, label };
}

function paymentLabel(method: PaymentMethod, t: ReturnType<typeof createSalesActivityTranslator>) {
  return ({
    EFECTIVO: t("cash"), TARJETA: t("card"), TRANSFERENCIA: t("transfer"),
    VALE: t("voucher"), PENDIENTE: t("pending"), OTROS: t("other")
  })[method];
}

function base64Bytes(value: string) {
  const binary = window.atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

function statusLabel(row: DocumentRow, t: ReturnType<typeof createSalesActivityTranslator>) {
  if (row.kind === "RETURN") return t("returnStatus");
  if (row.kind === "CANCELLED") return t("cancelledStatus");
  return row.status === "CONFIRMADO" ? t("saleStatus") : row.status;
}

export function SalesActivityPanel({
  app,
  mode,
  locale,
  username,
  token,
  terminalContext,
  request = apiRequest
}: Props) {
  const t = createSalesActivityTranslator(locale);
  const today = useMemo(() => isoDate(new Date()), []);
  const [dailyDate, setDailyDate] = useState(today);
  const [daily, setDaily] = useState<DailySummary | null>(null);
  const [rows, setRows] = useState<DocumentRow[]>([]);
  const [page, setPage] = useState<DocumentPage | null>(null);
  const [range, setRange] = useState<DateRange>(() => currentDayRange(t("today")));
  const [draftFrom, setDraftFrom] = useState(today);
  const [draftTo, setDraftTo] = useState(today);
  const [customOpen, setCustomOpen] = useState(false);
  const [earliestDate, setEarliestDate] = useState(today);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const [printGrouping, setPrintGrouping] = useState<PrintGrouping>("DAY");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<{ kind: "success" | "error" | "info"; text: string } | null>(null);
  const shortcutActionsRef = useRef<Record<"F5" | "F6" | "F7", () => Promise<void>>>({
    F5: async () => undefined,
    F6: async () => undefined,
    F7: async () => undefined
  });
  const documentTableLayout = useTableLayoutPreference({
    app,
    username,
    accessToken: mode === "documents" ? token : undefined,
    tableKey: "salesReport.salesDocuments.activity",
    definitions: documentColumnDefinitions
  });
  const documentTableSorting = useTableSortPreference({
    app,
    username,
    tableKey: "salesReport.salesDocuments.activity",
    columns: documentColumnKeys,
    defaultSort: null
  });
  const sortedDocumentRows = useMemo(() => sortTableRows(
    rows,
    documentTableSorting.sort,
    (row, columnKey) => {
      if (columnKey === "date") return row.date;
      if (columnKey === "time") {
        if (!row.occurredAt) return null;
        const value = new Date(row.occurredAt);
        return value.getHours() * 3_600_000
          + value.getMinutes() * 60_000
          + value.getSeconds() * 1_000
          + value.getMilliseconds();
      }
      if (columnKey === "ticketNumber") return row.ticketNumber;
      if (columnKey === "invoiceNumber") return row.invoiceNumber;
      if (columnKey === "userName") return row.userName;
      if (columnKey === "paymentMethods") return row.paymentMethods.join(", ");
      if (columnKey === "status") return `${row.kind}:${row.status}`;
      return Number(row.total);
    },
    localeTag(locale)
  ), [documentTableSorting.sort, locale, rows]);
  const visibleDocumentColumns = visibleTableColumns(documentTableLayout.layout);
  const documentDefinitionsByKey = new Map(
    documentColumnDefinitions.map((definition) => [definition.key, definition])
  );
  const documentTableStyle: CSSProperties = {
    width: "100%",
    minWidth: `${visibleDocumentColumns.reduce((total, column) => total + column.width, 0)}px`
  };

  useEffect(() => {
    if (mode !== "documents" || !token) return;
    void request<{ earliestDate: string; currentDate: string }>("/sales-activity/filter-options", { token })
      .then((value) => setEarliestDate(value.earliestDate || today))
      .catch(() => setEarliestDate(today));
  }, [mode, request, token, today]);

  useEffect(() => {
    if (mode !== "daily" || !token) return;
    let cancelled = false;
    setLoading(true); setError(""); setDaily(null);
    void request<DailySummary>(`/sales-activity/daily?date=${encodeURIComponent(dailyDate)}`, { token })
      .then((value) => { if (!cancelled) setDaily(value); })
      .catch((failure) => { if (!cancelled) setError(failure instanceof Error ? failure.message : t("operationFailed")); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [dailyDate, mode, reload, request, token]);

  useEffect(() => {
    if (mode !== "documents" || !token) return;
    let cancelled = false;
    setLoading(true); setError(""); setRows([]); setPage(null);
    const path = `/sales-activity/documents?dateFrom=${encodeURIComponent(range.from)}&dateTo=${encodeURIComponent(range.to)}&limit=250`;
    void request<DocumentPage>(path, { token })
      .then((value) => { if (!cancelled) { setRows(value.items); setPage(value); } })
      .catch((failure) => { if (!cancelled) setError(failure instanceof Error ? failure.message : t("operationFailed")); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [mode, range.from, range.to, reload, request, token]);

  const monthOptions = useMemo(() => {
    const earliest = localDate(earliestDate);
    const cursor = new Date(new Date().getFullYear(), new Date().getMonth(), 1);
    const values: Array<{ value: string; label: string }> = [];
    while (cursor >= new Date(earliest.getFullYear(), earliest.getMonth(), 1)) {
      values.push({
        value: `${cursor.getFullYear()}-${String(cursor.getMonth() + 1).padStart(2, "0")}`,
        label: new Intl.DateTimeFormat(localeTag(locale), { month: "long", year: "numeric" }).format(cursor)
      });
      cursor.setMonth(cursor.getMonth() - 1);
    }
    return values;
  }, [earliestDate, locale]);

  const quarterOptions = useMemo(() => {
    const earliest = localDate(earliestDate);
    const current = new Date();
    let year = current.getFullYear();
    let quarter = Math.floor(current.getMonth() / 3) + 1;
    const minimum = earliest.getFullYear() * 4 + Math.floor(earliest.getMonth() / 3);
    const values: Array<{ value: string; label: string }> = [];
    while (year * 4 + quarter - 1 >= minimum) {
      values.push({ value: `${year}-Q${quarter}`, label: `${quarter}º ${t("quarter").toLowerCase()} ${year}` });
      quarter -= 1;
      if (quarter === 0) { quarter = 4; year -= 1; }
    }
    return values;
  }, [earliestDate, locale]);

  const yearOptions = useMemo(() => {
    const first = localDate(earliestDate).getFullYear();
    return Array.from({ length: new Date().getFullYear() - first + 1 }, (_, index) => String(new Date().getFullYear() - index));
  }, [earliestDate]);

  function selectRange(next: DateRange) {
    setRange(next); setDraftFrom(next.from); setDraftTo(next.to); setCustomOpen(false);
  }

  function selectYesterday() {
    const value = new Date(); value.setDate(value.getDate() - 1);
    const iso = isoDate(value); selectRange({ from: iso, to: iso, label: t("yesterday") });
  }

  function selectWeek() {
    const end = new Date();
    const start = new Date(end); start.setDate(end.getDate() - ((end.getDay() + 6) % 7));
    selectRange({ from: isoDate(start), to: isoDate(end), label: t("week") });
  }

  function selectMonth(value: string) {
    if (!value) return;
    const [year, month] = value.split("-").map(Number);
    const from = new Date(year, month - 1, 1);
    const naturalEnd = endOfMonth(year, month - 1);
    const end = naturalEnd > new Date() ? new Date() : naturalEnd;
    const label = monthOptions.find((option) => option.value === value)?.label ?? value;
    selectRange({ from: isoDate(from), to: isoDate(end), label });
  }

  function selectQuarter(value: string) {
    const match = value.match(/^(\d{4})-Q([1-4])$/);
    if (!match) return;
    const year = Number(match[1]); const quarter = Number(match[2]);
    const start = new Date(year, (quarter - 1) * 3, 1);
    const naturalEnd = endOfMonth(year, quarter * 3 - 1);
    const end = naturalEnd > new Date() ? new Date() : naturalEnd;
    const label = quarterOptions.find((option) => option.value === value)?.label ?? value;
    selectRange({ from: isoDate(start), to: isoDate(end), label });
  }

  function selectYear(value: string) {
    if (!value) return;
    const year = Number(value);
    const end = year === new Date().getFullYear() ? new Date() : new Date(year, 11, 31);
    selectRange({ from: `${year}-01-01`, to: isoDate(end), label: value });
  }

  async function loadMore() {
    if (!page?.hasMore || !page.nextCursor || !token || loadingMore) return;
    setLoadingMore(true);
    try {
      const path = `/sales-activity/documents?dateFrom=${encodeURIComponent(range.from)}&dateTo=${encodeURIComponent(range.to)}&limit=250&cursor=${encodeURIComponent(page.nextCursor)}`;
      const next = await request<DocumentPage>(path, { token });
      setRows((current) => [...current, ...next.items]); setPage(next);
    } catch (failure) {
      setNotice({ kind: "error", text: failure instanceof Error ? failure.message : t("operationFailed") });
    } finally { setLoadingMore(false); }
  }

  async function saveBytes(bytes: Uint8Array, fileName: string, extension: "xlsx" | "pdf", mime: string) {
    if (window.tpvDesktop?.reports) {
      const result = await window.tpvDesktop.reports.saveFile({
        defaultFileName: fileName,
        filters: [{ name: extension === "xlsx" ? "Excel" : "PDF", extensions: [extension] }],
        bytes
      });
      if (!result.ok) throw new Error(result.message);
      return;
    }
    const url = URL.createObjectURL(new Blob([bytes.slice().buffer as ArrayBuffer], { type: mime }));
    const link = document.createElement("a"); link.href = url; link.download = fileName; link.click();
    URL.revokeObjectURL(url);
  }

  async function exportExcel() {
    if (!token || busy) return;
    setBusy(true); setNotice({ kind: "info", text: t("loading") });
    try {
      const query = mode === "daily"
        ? `/sales-activity/daily/excel?date=${encodeURIComponent(dailyDate)}`
        : `/sales-activity/documents/excel?dateFrom=${encodeURIComponent(range.from)}&dateTo=${encodeURIComponent(range.to)}`;
      const response = await fetch(`${apiBaseUrl}${query}`, { headers: { Authorization: `Bearer ${token}` } });
      if (!response.ok) throw new Error(await response.text());
      await saveBytes(new Uint8Array(await response.arrayBuffer()),
        mode === "daily" ? `resumen-ventas-${dailyDate}.xlsx` : `documentos-ventas-${range.from}-${range.to}.xlsx`,
        "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
      setNotice({ kind: "success", text: t("exportSuccess") });
    } catch (failure) {
      setNotice({ kind: "error", text: failure instanceof Error ? failure.message : t("operationFailed") });
    } finally { setBusy(false); }
  }

  async function rendered(format: PrintFormat) {
    if (!token) throw new Error(t("operationFailed"));
    const path = mode === "daily"
      ? `/sales-activity/daily/render?date=${encodeURIComponent(dailyDate)}&format=${format}`
      : `/sales-activity/documents/render?dateFrom=${encodeURIComponent(range.from)}&dateTo=${encodeURIComponent(range.to)}&grouping=${printGrouping}&format=${format}`;
    return request<RenderedReport>(path, { token });
  }

  async function exportPdf() {
    if (busy) return;
    setBusy(true); setNotice({ kind: "info", text: t("loading") });
    try {
      const value = await rendered("A4");
      await saveBytes(base64Bytes(value.renderedPdf.base64),
        mode === "daily" ? `resumen-ventas-${dailyDate}.pdf` : `documentos-ventas-${range.from}-${range.to}.pdf`,
        "pdf", "application/pdf");
      setNotice({ kind: "success", text: t("exportSuccess") });
    } catch (failure) {
      setNotice({ kind: "error", text: failure instanceof Error ? failure.message : t("operationFailed") });
    } finally { setBusy(false); }
  }

  function ticketRouteConfig(config: HardwareConfig): HardwareConfig {
    return {
      ...config,
      documentPrintRoutes: [
        ...config.documentPrintRoutes.filter((route) => route.documentType !== "TICKET"),
        { documentType: "TICKET", printerTarget: "TICKET_PRINTER", printerName: config.ticketPrinterName,
          paperSize: "TICKET_80", orientation: "PORTRAIT", copies: 1, printAutomatically: true, showPrintDialog: false }
      ]
    };
  }

  async function executePrint() {
    if (busy) return;
    setBusy(true); setNotice({ kind: "info", text: t("printing") });
    try {
      if (!window.tpvDesktop?.hardware) {
        throw new Error(t("ticketPrinterUnavailable"));
      }
      const value = await rendered("TICKET_80");
      const hardware = getHardwareBridge();
      const config = await hardware.getHardwareConfig();
      const total = Number(mode === "daily" ? daily?.netSalesTotal ?? 0 : page?.total ?? 0);
      const result = await hardware.printTicket({
        requireRenderedDocument: true,
        documentNumber: mode === "daily" ? `RESUMEN-${dailyDate}` : `VENTAS-${range.from}-${range.to}`,
        storeName: terminalContext.storeName, terminalCode: terminalContext.terminalCode,
        issuedAt: mode === "daily" ? dailyDate : range.to, lines: [], payments: [], total,
        renderedPdf: value.renderedPdf,
        ...(value.renderedImage ? { documentRaster: `data:${value.renderedImage.contentType};base64,${value.renderedImage.base64}` } : {}),
        labels: { terminal: "Terminal", item: "Concepto", quantity: "Cantidad", price: "Precio", total: "Total" }
      }, ticketRouteConfig(config));
      if (!result.ok) throw new Error(result.message);
      setNotice({ kind: "success", text: t("printSuccess") });
    } catch (failure) {
      setNotice({ kind: "error", text: failure instanceof Error ? failure.message : t("operationFailed") });
    } finally { setBusy(false); }
  }

  shortcutActionsRef.current = {
    F5: executePrint,
    F6: exportExcel,
    F7: exportPdf
  };

  useEffect(() => {
    function handleOutputShortcut(event: KeyboardEvent) {
      if (event.ctrlKey || event.altKey || event.metaKey || event.shiftKey || event.repeat) return;
      if (event.key !== "F5" && event.key !== "F6" && event.key !== "F7") return;
      event.preventDefault();
      event.stopPropagation();
      void shortcutActionsRef.current[event.key]();
    }

    window.addEventListener("keydown", handleOutputShortcut, true);
    return () => window.removeEventListener("keydown", handleOutputShortcut, true);
  }, []);

  function renderSummaryBlock(
    summary: DailyUserSummary | DailySummary,
    title: string,
    primary = false,
    key?: string
  ) {
    return <section key={key} className={`sales-daily-block${primary ? " sales-daily-block--primary" : ""}`}>
      <header>
        <div>
          <span>{primary && daily ? formatDate(daily.date, locale) : t("user")}</span>
          <h3>{title}</h3>
        </div>
      </header>
      <div className="sales-daily-methods" aria-label={t("paymentMethods")}>
        {summary.paymentMethods.filter((method) => Number(method.amount) !== 0).map((method) => <div key={method.method}>
          <span>{`${paymentLabel(method.method, t)}: (${method.operationCount})`}</span>
          <strong>{formatEuroAmount(method.amount, locale)}</strong>
        </div>)}
        <div className="sales-daily-total">
          <span>{t("total")}</span>
          <strong>{formatEuroAmount(summary.netSalesTotal, locale)}</strong>
        </div>
      </div>
      <div className="sales-daily-counts">
        <div><span>{t("sales")}</span><strong>{summary.counts.sales}</strong></div>
        <div><span>{t("returns")}</span><strong>{summary.counts.returns}</strong></div>
        <div><span>{t("cancelled")}</span><strong>{summary.counts.cancelled}</strong></div>
        <div><span>{t("pendingDocuments")}</span><strong>{summary.counts.pending}</strong></div>
      </div>
    </section>;
  }

  function outputActions(includeVisualization: boolean) {
    return <div className="sales-activity-toolbar__actions">
      {includeVisualization && <>
        <div className="sales-activity-visualization" role="group" aria-label={t("visualization")}>
          <button
            type="button"
            className={printGrouping === "DAY" ? "selected" : ""}
            aria-pressed={printGrouping === "DAY"}
            onClick={() => setPrintGrouping("DAY")}
          >
            {t("byDay")}
          </button>
          <button
            type="button"
            className={printGrouping === "DOCUMENT" ? "selected" : ""}
            aria-pressed={printGrouping === "DOCUMENT"}
            onClick={() => setPrintGrouping("DOCUMENT")}
          >
            {t("byDocument")}
          </button>
        </div>
      </>}
      <div className="sales-activity-output-actions">
        <button type="button" aria-keyshortcuts="F5" title="F5" onClick={() => void executePrint()} disabled={busy}>
          <span>{t("print")}</span><kbd aria-hidden="true">F5</kbd>
        </button>
        <button type="button" aria-keyshortcuts="F6" title="F6" onClick={() => void exportExcel()} disabled={busy}>
          <span>{t("excel")}</span><kbd aria-hidden="true">F6</kbd>
        </button>
        <button type="button" aria-keyshortcuts="F7" title="F7" onClick={() => void exportPdf()} disabled={busy}>
          <span>{t("pdf")}</span><kbd aria-hidden="true">F7</kbd>
        </button>
      </div>
    </div>;
  }

  function documentCell(row: DocumentRow, columnKey: DocumentColumnKey) {
    if (columnKey === "date") return formatDate(row.date, locale);
    if (columnKey === "time") return row.occurredAt
      ? new Intl.DateTimeFormat(localeTag(locale), {
          hour: "2-digit",
          minute: "2-digit",
          second: "2-digit"
        }).format(new Date(row.occurredAt))
      : "";
    if (columnKey === "ticketNumber") return row.ticketNumber;
    if (columnKey === "invoiceNumber") return row.invoiceNumber;
    if (columnKey === "userName") return row.userName;
    if (columnKey === "paymentMethods") return row.paymentMethods.join(", ");
    if (columnKey === "status") return <span
      className={`sales-document-status sales-document-status--${row.kind.toLowerCase()}`}
    >
      {statusLabel(row, t)}
    </span>;
    return formatEuroAmount(row.total, locale);
  }

  function documentFooterCell(columnKey: DocumentColumnKey) {
    if (columnKey === "ticketNumber") return `${t("ticketCount")}: ${page?.ticketCount ?? 0}`;
    if (columnKey === "invoiceNumber") return `${t("invoiceCount")}: ${page?.invoiceCount ?? 0}`;
    if (columnKey === "total") return formatEuroAmount(page?.total ?? 0, locale);
    return "";
  }

  function dailyContent() {
    return <>
      <div className="sales-activity-toolbar">
        <label className="sales-activity-date"><span>{t("date")}</span><input type="date" max={today} value={dailyDate} onChange={(event) => setDailyDate(event.target.value)} /></label>
        {outputActions(false)}
      </div>
      {loading && <p className="sales-activity-state" role="status">{t("loading")}</p>}
      {error && <div className="sales-activity-state sales-activity-state--error" role="alert"><span>{error}</span><button type="button" onClick={() => setReload((value) => value + 1)}>{t("retry")}</button></div>}
      {daily && <div className="sales-daily-layout">
        {renderSummaryBlock(daily, daily.companyName, true)}
        <section className="sales-daily-users"><header><span>{t("users")}</span><strong>{daily.users.length}</strong></header>
          <div>{daily.users.map((user) => renderSummaryBlock(
            user, user.userName, false, user.userId || user.userName
          ))}</div>
          {daily.users.length === 0 && <p className="sales-activity-state">{t("noActivity")}</p>}
        </section>
      </div>}
    </>;
  }

  function documentsContent() {
    return <div className="sales-documents-layout">
      <div className="sales-activity-toolbar">
        <div className="sales-documents-period"><span>{t("currentPeriod")}</span><strong>{range.label}</strong><small>{formatDate(range.from, locale)} — {formatDate(range.to, locale)}</small></div>
        {outputActions(true)}
      </div>
      {loading && <p className="sales-activity-state" role="status">{t("loading")}</p>}
      {error && <div className="sales-activity-state sales-activity-state--error" role="alert"><span>{error}</span><button type="button" onClick={() => setReload((value) => value + 1)}>{t("retry")}</button></div>}
      {!loading && !error && <div className="sales-documents-table-scroll">
        <table className="sales-documents-table" style={documentTableStyle}>
          <colgroup>{visibleDocumentColumns.map((column) => <col
            key={column.key}
            data-column-key={column.key}
            style={{ width: column.width }}
          />)}</colgroup>
          <thead><tr>{visibleDocumentColumns.map((column) => {
            const definition = documentDefinitionsByKey.get(column.key)!;
            return <TableLayoutHeaderCell
              column={column}
              key={column.key}
              className={column.key === "total" ? "sales-documents-column--total" : ""}
              resizeLabel={`${t("resizeColumn")} ${t(definition.labelKey)}`}
              sortDirection={documentTableSorting.sort?.column === column.key
                ? documentTableSorting.sort.direction
                : null}
              sortLabel={`${t(definition.labelKey)} ${t("sort")}`}
              onSort={documentTableSorting.toggleSort}
              onReorder={documentTableLayout.reorderColumns}
              onMove={documentTableLayout.moveColumn}
              onResize={documentTableLayout.resizeColumn}
              onToggleVisibility={documentTableLayout.toggleColumnVisibility}
              columnVisibilityOptions={documentTableLayout.layout.map((candidate) => ({
                key: candidate.key,
                label: t(documentDefinitionsByKey.get(candidate.key)!.labelKey),
                visible: candidate.visible,
                disabled: candidate.visible && visibleDocumentColumns.length <= 1
              }))}
            >
              {t(definition.labelKey)}
            </TableLayoutHeaderCell>;
          })}</tr></thead>
          <tbody>{sortedDocumentRows.map((row) => <tr key={row.id} className={`sales-document-row--${row.kind.toLowerCase()}`}>
            {visibleDocumentColumns.map((column) => <td
              key={column.key}
              className={column.key === "total" ? "sales-documents-column--total" : ""}
            >
              {documentCell(row, column.key)}
            </td>)}
          </tr>)}</tbody>
          <tfoot><tr>{visibleDocumentColumns.map((column) => <th
            key={column.key}
            className={column.key === "total" ? "sales-documents-column--total" : ""}
          >
            {documentFooterCell(column.key)}
          </th>)}</tr></tfoot>
        </table>
        {page?.hasMore && <button className="sales-documents-load-more" type="button" disabled={loadingMore} onClick={() => void loadMore()}>{loadingMore ? t("loading") : t("loadMore")}</button>}
      </div>}
      <footer className="sales-activity-filter-dock" aria-label={t("currentPeriod")}>
        <button type="button" className={range.label === t("today") ? "selected" : ""} onClick={() => selectRange(currentDayRange(t("today")))}>{t("today")}</button>
        <button type="button" onClick={selectYesterday}>{t("yesterday")}</button>
        <button type="button" onClick={selectWeek}>{t("week")}</button>
        <label><span>{t("month")}</span><select defaultValue="" onChange={(event) => selectMonth(event.target.value)}><option value="">—</option>{monthOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
        <label><span>{t("quarter")}</span><select defaultValue="" onChange={(event) => selectQuarter(event.target.value)}><option value="">—</option>{quarterOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
        <label><span>{t("year")}</span><select defaultValue="" onChange={(event) => selectYear(event.target.value)}><option value="">—</option>{yearOptions.map((year) => <option key={year} value={year}>{year}</option>)}</select></label>
        <button type="button" onClick={() => setCustomOpen((value) => !value)}>{t("custom")}</button>
        {customOpen && <div className="sales-activity-custom-period"><label><span>{t("from")}</span><input type="date" value={draftFrom} onChange={(event) => setDraftFrom(event.target.value)} /></label><label><span>{t("to")}</span><input type="date" max={today} value={draftTo} onChange={(event) => setDraftTo(event.target.value)} /></label><button type="button" disabled={!draftFrom || !draftTo || draftTo < draftFrom} onClick={() => selectRange({ from: draftFrom, to: draftTo, label: `${formatDate(draftFrom, locale)} — ${formatDate(draftTo, locale)}` })}>{t("apply")}</button></div>}
      </footer>
    </div>;
  }

  return <section className={`sales-activity-panel sales-activity-panel--${mode}`}>
    {mode === "daily" ? dailyContent() : documentsContent()}
    {notice && <div className={`sales-activity-notice sales-activity-notice--${notice.kind}`} role={notice.kind === "error" ? "alert" : "status"}>{notice.text}</div>}
  </section>;
}
