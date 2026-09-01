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

// The backend also permits store-defined tender identifiers. Known integrated
// methods are translated; custom identifiers must remain literal.
type PaymentMethod = string;

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
  currentDate?: string;
  operations?: {
    collectedCurrent: number | string;
    newPending: number | string;
    priorDebtCollected: number | string;
    cashInflow: number | string;
    pendingCollectionsByPaymentMethod: Record<string, number | string>;
    refundsByPaymentMethod: Record<string, number | string>;
    openingCashFund?: number | string | null;
    cashEntries?: number | string;
    cashWithdrawals?: number | string;
    expectedCash?: number | string | null;
  };
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
  currentDate: string;
};

type DailyDocumentRow = {
  date: string;
  ticketCount: number;
  invoiceCount: number;
  total: number | string;
};

type DailyDocumentPage = {
  items: DailyDocumentRow[];
  nextCursor?: string | null;
  hasMore: boolean;
  ticketCount: number;
  invoiceCount: number;
  total: number | string;
  dateFrom: string;
  dateTo: string;
  currentDate?: string;
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

type RangePreset = "TODAY" | "YESTERDAY" | "WEEK" | "MONTH" | "QUARTER" | "YEAR" | "CUSTOM";
type DateRange = { from: string; to: string; label: string; preset?: RangePreset };
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

function currentDayRange(label: string, currentDate: string): DateRange {
  return { from: currentDate, to: currentDate, label, preset: "TODAY" };
}

function isValidIsoDate(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = localDate(value);
  return !Number.isNaN(parsed.getTime()) && isoDate(parsed) === value;
}

function paymentLabel(method: string, t: ReturnType<typeof createSalesActivityTranslator>) {
  const normalized = method.toUpperCase();
  return ({
    EFECTIVO: t("cash"), TARJETA: t("card"), TRANSFERENCIA: t("transfer"),
    VALE: t("voucher"), PENDIENTE: t("pending"), OTROS: t("other")
  } as Record<string, string>)[normalized] ?? method;
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
  const rangeLabel = (value: DateRange) => value.preset === "TODAY" ? t("today")
    : value.preset === "YESTERDAY" ? t("yesterday")
      : value.preset === "WEEK" ? t("week")
        : value.preset === "MONTH" ? new Intl.DateTimeFormat(localeTag(locale), { month: "long", year: "numeric" }).format(localDate(value.from))
          : value.preset === "QUARTER" ? `${t("quarter")} ${Math.floor(localDate(value.from).getMonth() / 3) + 1} ${localDate(value.from).getFullYear()}`
            : value.preset === "YEAR" ? localDate(value.from).getFullYear().toString()
              : value.preset === "CUSTOM" ? `${formatDate(value.from, locale)} — ${formatDate(value.to, locale)}`
                : value.label;
  const [today, setToday] = useState("");
  const [dailyDate, setDailyDate] = useState("");
  const [daily, setDaily] = useState<DailySummary | null>(null);
  const [rows, setRows] = useState<DocumentRow[]>([]);
  const [page, setPage] = useState<DocumentPage | null>(null);
  const [dailyRows, setDailyRows] = useState<DailyDocumentRow[]>([]);
  const [dailyPage, setDailyPage] = useState<DailyDocumentPage | null>(null);
  const [range, setRange] = useState<DateRange>({ from: "", to: "", label: "" });
  const [draftFrom, setDraftFrom] = useState("");
  const [draftTo, setDraftTo] = useState("");
  const [customOpen, setCustomOpen] = useState(false);
  const [earliestDate, setEarliestDate] = useState("");
  const [filterOptionsReady, setFilterOptionsReady] = useState(false);
  const [filterReload, setFilterReload] = useState(0);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState("");
  const [errorKey, setErrorKey] = useState<SalesActivityMessageKey | null>(null);
  const [reload, setReload] = useState(0);
  const [viewMode, setViewMode] = useState<PrintGrouping>("DAY");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<{
    kind: "success" | "error" | "info";
    text?: string;
    key?: SalesActivityMessageKey;
  } | null>(null);
  const requestVersionRef = useRef(0);
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
      if (columnKey === "paymentMethods") {
        return row.paymentMethods.map((method) => paymentLabel(method, t)).join(", ");
      }
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

  type CurrentDateResolution = "accepted" | "rollover" | "invalid";
  function failClosedForCurrentDate() {
    setToday(""); setDailyDate(""); setDraftFrom(""); setDraftTo("");
    setRange({ from: "", to: "", label: "" });
    setDaily(null); setRows([]); setPage(null); setDailyRows([]); setDailyPage(null);
    setError(""); setErrorKey("operationFailed"); setFilterOptionsReady(false);
  }

  function reconcileServerCurrentDate(
    currentDate: string | undefined,
    wasOnServerToday: boolean,
    onRollover: (nextDate: string) => void,
    required = false
  ): CurrentDateResolution {
    if ((required && !isValidIsoDate(currentDate))
        || (!required && currentDate !== undefined && !isValidIsoDate(currentDate))) {
      failClosedForCurrentDate();
      return "invalid";
    }
    if (!currentDate || currentDate === today) return "accepted";
    setToday(currentDate);
    if (!wasOnServerToday) return "accepted";
    onRollover(currentDate);
    return "rollover";
  }

  useEffect(() => {
    if (!token) { setFilterOptionsReady(false); return; }
    let cancelled = false;
    setFilterOptionsReady(false);
    void request<{ earliestDate: string; currentDate: string }>("/sales-activity/filter-options", { token })
      .then((value) => {
        if (cancelled) return;
        const currentDate = value.currentDate;
        if (!isValidIsoDate(currentDate)) {
          setToday(""); setDailyDate(""); setDraftFrom(""); setDraftTo("");
          setRange({ from: "", to: "", label: "" });
          setError(""); setErrorKey("operationFailed");
          setFilterOptionsReady(false);
          return;
        }
        setToday(currentDate);
        setError(""); setErrorKey(null);
        setEarliestDate(isValidIsoDate(value.earliestDate) ? value.earliestDate : currentDate);
        setDailyDate(currentDate);
        setDraftFrom(currentDate);
        setDraftTo(currentDate);
        setRange(currentDayRange(t("today"), currentDate));
        setFilterOptionsReady(true);
      })
      .catch(() => {
        if (cancelled) return;
        setToday(""); setDailyDate(""); setDraftFrom(""); setDraftTo("");
        setRange({ from: "", to: "", label: "" });
        setError(""); setErrorKey("operationFailed");
        setFilterOptionsReady(false);
      });
    return () => { cancelled = true; setFilterOptionsReady(false); };
  }, [mode, filterReload, request, token]);

  useEffect(() => {
    if (mode !== "daily" || !token || !filterOptionsReady) return;
    let cancelled = false;
    setLoading(true); setError(""); setErrorKey(null); setDaily(null);
    void request<DailySummary>(`/sales-activity/daily?date=${encodeURIComponent(dailyDate)}`, { token })
      .then((value) => {
        if (cancelled) return;
        const result = reconcileServerCurrentDate(
          value.currentDate,
          dailyDate === today,
          (nextDate) => setDailyDate(nextDate));
        if (result !== "accepted") return;
        setDaily(value);
      })
      .catch((failure) => {
        if (!cancelled) {
          if (failure instanceof Error) { setError(failure.message); setErrorKey(null); }
          else { setError(""); setErrorKey("operationFailed"); }
        }
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [dailyDate, filterOptionsReady, mode, reload, request, token]);

  useEffect(() => {
    const requestVersion = requestVersionRef.current + 1;
    requestVersionRef.current = requestVersion;
    if (mode !== "documents" || !token || !filterOptionsReady) return;
    let cancelled = false;
    setLoading(true);
    setLoadingMore(false);
    setError(""); setErrorKey(null);
    setNotice(null);
    if (viewMode === "DAY") {
      setDailyRows([]);
      setDailyPage(null);
    } else {
      setRows([]);
      setPage(null);
    }
    const resource = viewMode === "DAY" ? "documents/by-day" : "documents";
    const path = `/sales-activity/${resource}?dateFrom=${encodeURIComponent(range.from)}&dateTo=${encodeURIComponent(range.to)}&limit=250`;
    void request<DocumentPage | DailyDocumentPage>(path, { token })
      .then((value) => {
        if (cancelled || requestVersion !== requestVersionRef.current) return;
        if (viewMode === "DAY") {
          const dailyValue = value as DailyDocumentPage;
          const result = reconcileServerCurrentDate(
            dailyValue.currentDate,
            range.preset === "TODAY" && range.from === today && range.to === today,
            (nextDate) => {
              setRange({ from: nextDate, to: nextDate, label: "", preset: "TODAY" });
              setDraftFrom(nextDate); setDraftTo(nextDate);
            });
          if (result !== "accepted") return;
          setDailyRows(dailyValue.items);
          setDailyPage(dailyValue);
        } else {
          const documentValue = value as DocumentPage;
          const result = reconcileServerCurrentDate(
            documentValue.currentDate,
            range.preset === "TODAY" && range.from === today && range.to === today,
            (nextDate) => {
              setRows([]); setPage(null);
              setRange({ from: nextDate, to: nextDate, label: "", preset: "TODAY" });
              setDraftFrom(nextDate); setDraftTo(nextDate);
            }, true);
          if (result !== "accepted") return;
          setRows(documentValue.items);
          setPage(documentValue);
        }
      })
      .catch((failure) => {
        if (!cancelled && requestVersion === requestVersionRef.current) {
          if (failure instanceof Error) { setError(failure.message); setErrorKey(null); }
          else { setError(""); setErrorKey("operationFailed"); }
        }
      })
      .finally(() => {
        if (!cancelled && requestVersion === requestVersionRef.current) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [filterOptionsReady, mode, range.from, range.to, reload, request, token, viewMode]);

  const monthOptions = useMemo(() => {
    if (!isValidIsoDate(earliestDate) || !isValidIsoDate(today)) return [];
    const earliest = localDate(earliestDate);
    const current = localDate(today);
    const cursor = new Date(current.getFullYear(), current.getMonth(), 1);
    const values: Array<{ value: string; label: string }> = [];
    while (cursor >= new Date(earliest.getFullYear(), earliest.getMonth(), 1)) {
      values.push({
        value: `${cursor.getFullYear()}-${String(cursor.getMonth() + 1).padStart(2, "0")}`,
        label: new Intl.DateTimeFormat(localeTag(locale), { month: "long", year: "numeric" }).format(cursor)
      });
      cursor.setMonth(cursor.getMonth() - 1);
    }
    return values;
  }, [earliestDate, locale, today]);

  const quarterOptions = useMemo(() => {
    if (!isValidIsoDate(earliestDate) || !isValidIsoDate(today)) return [];
    const earliest = localDate(earliestDate);
    const current = localDate(today);
    let year = current.getFullYear();
    let quarter = Math.floor(current.getMonth() / 3) + 1;
    const minimum = earliest.getFullYear() * 4 + Math.floor(earliest.getMonth() / 3);
    const values: Array<{ value: string; label: string }> = [];
    while (year * 4 + quarter - 1 >= minimum) {
      values.push({ value: `${year}-Q${quarter}`, label: `${t("quarter")} ${quarter} ${year}` });
      quarter -= 1;
      if (quarter === 0) { quarter = 4; year -= 1; }
    }
    return values;
  }, [earliestDate, locale, t, today]);

  const yearOptions = useMemo(() => {
    if (!isValidIsoDate(earliestDate) || !isValidIsoDate(today)) return [];
    const first = localDate(earliestDate).getFullYear();
    const currentYear = localDate(today).getFullYear();
    return Array.from({ length: currentYear - first + 1 }, (_, index) => String(currentYear - index));
  }, [earliestDate, today]);

  function selectRange(next: DateRange) {
    setRange(next); setDraftFrom(next.from); setDraftTo(next.to); setCustomOpen(false);
  }

  function selectYesterday() {
    const value = localDate(today); value.setDate(value.getDate() - 1);
    const iso = isoDate(value); selectRange({ from: iso, to: iso, label: t("yesterday"), preset: "YESTERDAY" });
  }

  function selectWeek() {
    const end = localDate(today);
    const start = new Date(end); start.setDate(end.getDate() - ((end.getDay() + 6) % 7));
    selectRange({ from: isoDate(start), to: isoDate(end), label: t("week"), preset: "WEEK" });
  }

  function selectMonth(value: string) {
    if (!value) return;
    const [year, month] = value.split("-").map(Number);
    const from = new Date(year, month - 1, 1);
    const naturalEnd = endOfMonth(year, month - 1);
    const current = localDate(today);
    const end = naturalEnd > current ? current : naturalEnd;
    const label = monthOptions.find((option) => option.value === value)?.label ?? value;
    selectRange({ from: isoDate(from), to: isoDate(end), label, preset: "MONTH" });
  }

  function selectQuarter(value: string) {
    const match = value.match(/^(\d{4})-Q([1-4])$/);
    if (!match) return;
    const year = Number(match[1]); const quarter = Number(match[2]);
    const start = new Date(year, (quarter - 1) * 3, 1);
    const naturalEnd = endOfMonth(year, quarter * 3 - 1);
    const current = localDate(today);
    const end = naturalEnd > current ? current : naturalEnd;
    const label = quarterOptions.find((option) => option.value === value)?.label ?? value;
    selectRange({ from: isoDate(start), to: isoDate(end), label, preset: "QUARTER" });
  }

  function selectYear(value: string) {
    if (!value) return;
    const year = Number(value);
    const current = localDate(today);
    const end = year === current.getFullYear() ? current : new Date(year, 11, 31);
    selectRange({ from: `${year}-01-01`, to: isoDate(end), label: value, preset: "YEAR" });
  }

  async function loadMore() {
    const requestVersion = requestVersionRef.current;
    const requestedViewMode = viewMode;
    const currentPage = viewMode === "DAY" ? dailyPage : page;
    if (!currentPage?.hasMore || !currentPage.nextCursor || !token || loadingMore) return;
    setNotice(null);
    setLoadingMore(true);
    try {
      const resource = viewMode === "DAY" ? "documents/by-day" : "documents";
      const path = `/sales-activity/${resource}?dateFrom=${encodeURIComponent(range.from)}&dateTo=${encodeURIComponent(range.to)}&limit=250&cursor=${encodeURIComponent(currentPage.nextCursor)}`;
      const next = await request<DocumentPage | DailyDocumentPage>(path, { token });
      if (requestVersion !== requestVersionRef.current) return;
      if (requestedViewMode === "DAY") {
        const dailyValue = next as DailyDocumentPage;
        const result = reconcileServerCurrentDate(
          dailyValue.currentDate,
          range.preset === "TODAY" && range.from === today && range.to === today,
          (nextDate) => {
            setDailyRows([]); setDailyPage(null);
            setRange({ from: nextDate, to: nextDate, label: "", preset: "TODAY" });
            setDraftFrom(nextDate); setDraftTo(nextDate);
          });
        if (result !== "accepted") return;
        setDailyRows((current) => [...current, ...dailyValue.items]);
        setDailyPage(dailyValue);
      } else {
        const documentValue = next as DocumentPage;
        const result = reconcileServerCurrentDate(
          documentValue.currentDate,
          range.preset === "TODAY" && range.from === today && range.to === today,
          (nextDate) => {
            setRows([]); setPage(null);
            setRange({ from: nextDate, to: nextDate, label: "", preset: "TODAY" });
            setDraftFrom(nextDate); setDraftTo(nextDate);
          }, true);
        if (result !== "accepted") return;
        setRows((current) => [...current, ...documentValue.items]);
        setPage(documentValue);
      }
    } catch (failure) {
      if (requestVersion === requestVersionRef.current) {
        setNotice(failure instanceof Error
          ? { kind: "error", text: failure.message }
          : { kind: "error", key: "operationFailed" });
      }
    } finally {
      if (requestVersion === requestVersionRef.current) setLoadingMore(false);
    }
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
    if (!token || busy || !filterOptionsReady || loading
      || (mode === "daily" ? !daily : (viewMode === "DAY" ? !dailyPage : !page))) return;
    setBusy(true); setNotice({ kind: "info", key: "loading" });
    try {
      const query = mode === "daily"
        ? `/sales-activity/daily/excel?date=${encodeURIComponent(dailyDate)}`
        : `/sales-activity/documents/excel?dateFrom=${encodeURIComponent(range.from)}&dateTo=${encodeURIComponent(range.to)}&grouping=${viewMode}`;
      const response = await fetch(`${apiBaseUrl}${query}`, { headers: { Authorization: `Bearer ${token}` } });
      if (!response.ok) throw new Error(await response.text());
      await saveBytes(new Uint8Array(await response.arrayBuffer()),
        mode === "daily" ? `resumen-ventas-${dailyDate}.xlsx` : `documentos-ventas-${range.from}-${range.to}.xlsx`,
        "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
      setNotice({ kind: "success", key: "exportSuccess" });
    } catch (failure) {
      setNotice(failure instanceof Error
        ? { kind: "error", text: failure.message }
        : { kind: "error", key: "operationFailed" });
    } finally { setBusy(false); }
  }

  async function rendered(format: PrintFormat) {
    if (!token) throw new Error(t("operationFailed"));
    const path = mode === "daily"
      ? `/sales-activity/daily/render?date=${encodeURIComponent(dailyDate)}&format=${format}`
      : `/sales-activity/documents/render?dateFrom=${encodeURIComponent(range.from)}&dateTo=${encodeURIComponent(range.to)}&grouping=${viewMode}&format=${format}`;
    return request<RenderedReport>(path, { token });
  }

  async function exportPdf() {
    if (busy || !filterOptionsReady || loading
      || (mode === "daily" ? !daily : (viewMode === "DAY" ? !dailyPage : !page))) return;
    setBusy(true); setNotice({ kind: "info", key: "loading" });
    try {
      const value = await rendered("A4");
      await saveBytes(base64Bytes(value.renderedPdf.base64),
        mode === "daily" ? `resumen-ventas-${dailyDate}.pdf` : `documentos-ventas-${range.from}-${range.to}.pdf`,
        "pdf", "application/pdf");
      setNotice({ kind: "success", key: "exportSuccess" });
    } catch (failure) {
      setNotice(failure instanceof Error
        ? { kind: "error", text: failure.message }
        : { kind: "error", key: "operationFailed" });
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
    if (busy || !filterOptionsReady || loading
      || (mode === "daily" ? !daily : (viewMode === "DAY" ? !dailyPage : !page))) return;
    setBusy(true); setNotice({ kind: "info", key: "printing" });
    try {
      if (!window.tpvDesktop?.hardware) {
        throw new Error(t("ticketPrinterUnavailable"));
      }
      const value = await rendered("TICKET_80");
      const hardware = getHardwareBridge();
      const config = await hardware.getHardwareConfig();
      const total = Number(mode === "daily"
        ? daily?.netSalesTotal ?? 0
        : (viewMode === "DAY" ? dailyPage?.total ?? 0 : page?.total ?? 0));
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
      setNotice({ kind: "success", key: "printSuccess" });
    } catch (failure) {
      setNotice(failure instanceof Error
        ? { kind: "error", text: failure.message }
        : { kind: "error", key: "operationFailed" });
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
    const outputReady = filterOptionsReady && !loading
      && (mode === "daily" ? daily != null : (viewMode === "DAY" ? dailyPage != null : page != null));
    return <div className="sales-activity-toolbar__actions">
      {includeVisualization && <>
        <div className="sales-activity-visualization" role="group" aria-label={t("visualization")}>
          <button
            type="button"
            className={viewMode === "DAY" ? "selected" : ""}
            aria-pressed={viewMode === "DAY"}
            onClick={() => setViewMode("DAY")}
          >
            {t("byDay")}
          </button>
          <button
            type="button"
            className={viewMode === "DOCUMENT" ? "selected" : ""}
            aria-pressed={viewMode === "DOCUMENT"}
            onClick={() => setViewMode("DOCUMENT")}
          >
            {t("byDocument")}
          </button>
        </div>
      </>}
      <div className="sales-activity-output-actions">
        <button type="button" aria-keyshortcuts="F5" title="F5" onClick={() => void executePrint()} disabled={busy || !outputReady}>
          <span>{t("print")}</span><kbd aria-hidden="true">F5</kbd>
        </button>
        <button type="button" aria-keyshortcuts="F6" title="F6" onClick={() => void exportExcel()} disabled={busy || !outputReady}>
          <span>{t("excel")}</span><kbd aria-hidden="true">F6</kbd>
        </button>
        <button type="button" aria-keyshortcuts="F7" title="F7" onClick={() => void exportPdf()} disabled={busy || !outputReady}>
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
    if (columnKey === "paymentMethods") {
      return row.paymentMethods.map((method) => paymentLabel(method, t)).join(", ");
    }
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

  function dailyDocumentTable() {
    return <table className="sales-documents-table sales-daily-documents-table">
      <thead><tr>
        <th scope="col">{t("day")}</th>
        <th scope="col">{t("ticketCount")}</th>
        <th scope="col">{t("invoiceCount")}</th>
        <th scope="col" className="sales-documents-column--total">{t("total")}</th>
      </tr></thead>
      <tbody>{dailyRows.map((row) => <tr key={row.date}>
        <td>{formatDate(row.date, locale)}</td>
        <td>{row.ticketCount}</td>
        <td>{row.invoiceCount}</td>
        <td className="sales-documents-column--total">{formatEuroAmount(row.total, locale)}</td>
      </tr>)}</tbody>
      <tfoot><tr>
        <th scope="row">{t("total")}</th>
        <th>{dailyPage?.ticketCount ?? 0}</th>
        <th>{dailyPage?.invoiceCount ?? 0}</th>
        <th className="sales-documents-column--total">{formatEuroAmount(dailyPage?.total ?? 0, locale)}</th>
      </tr></tfoot>
    </table>;
  }

  function operationsBlock() {
    const report = daily?.operations;
    if (!report) return null;
    const amount = (value: number | string | undefined | null) => (
      <strong>{formatEuroAmount(value ?? 0, locale)}</strong>
    );
    const paymentKeys = ["cash", "card", "transfer", "voucher", "pending", "other"] as const;
    const breakdownTotal = (breakdown: Record<string, number | string>) =>
      paymentKeys.reduce<number>((sum, key) => sum + Number(breakdown?.[key] ?? 0), 0);
    return <section className="sales-daily-authoritative" aria-label={t("operationsSection")}>
      <header><span>{t("operationsSection")}</span></header>
      <div className="sales-daily-authoritative-grid">
        <div><span>{t("currentCollections")}</span>{amount(report.collectedCurrent)}</div>
        <div><span>{t("newPendingAmount")}</span>{amount(report.newPending)}</div>
        <div><span>{t("priorDebtCollections")}</span>{amount(report.priorDebtCollected)}</div>
        <div><span>{t("realCashInflow")}</span>{amount(report.cashInflow)}</div>
      </div>
      <div className="sales-daily-authoritative-subsections">
        <section><h4>{t("priorDebtCollections")}</h4><div><span>{t("total")}</span>{amount(breakdownTotal(report.pendingCollectionsByPaymentMethod))}</div>{paymentKeys.filter((key) => Number(report.pendingCollectionsByPaymentMethod?.[key] ?? 0) !== 0).map((key) => (
          <div key={`debt-${key}`}><span>{paymentLabel(({ cash: "EFECTIVO", card: "TARJETA", transfer: "TRANSFERENCIA", voucher: "VALE", pending: "PENDIENTE", other: "OTROS" } as Record<string, string>)[key], t)}</span>{amount(report.pendingCollectionsByPaymentMethod?.[key])}</div>
        ))}</section>
        <section><h4>{t("refundsSection")}</h4><div><span>{t("total")}</span>{amount(breakdownTotal(report.refundsByPaymentMethod))}</div>{paymentKeys.filter((key) => Number(report.refundsByPaymentMethod?.[key] ?? 0) !== 0).map((key) => (
          <div key={`refund-${key}`}><span>{paymentLabel(({ cash: "EFECTIVO", card: "TARJETA", transfer: "TRANSFERENCIA", voucher: "VALE", pending: "PENDIENTE", other: "OTROS" } as Record<string, string>)[key], t)}</span>{amount(report.refundsByPaymentMethod?.[key])}</div>
        ))}</section>
      </div>
      <div className="sales-daily-authoritative-cash">
        <h4>{t("cashSection")}</h4>
        {report.openingCashFund != null && <div><span>{t("openingCashFund")}</span>{amount(report.openingCashFund)}</div>}
        {report.cashEntries != null && <div><span>{t("cashEntries")}</span>{amount(report.cashEntries)}</div>}
        {report.cashWithdrawals != null && <div><span>{t("cashWithdrawals")}</span>{amount(report.cashWithdrawals)}</div>}
        {report.expectedCash != null && <div><span>{t("expectedCash")}</span>{amount(report.expectedCash)}</div>}
      </div>
    </section>;
  }

  function retry() {
    setReload((value) => value + 1);
    if (!filterOptionsReady) setFilterReload((value) => value + 1);
  }

  function dailyContent() {
    return <>
      <div className="sales-activity-toolbar">
        <label className="sales-activity-date"><span>{t("date")}</span><input type="date" disabled={!filterOptionsReady} max={today} value={dailyDate} onChange={(event) => setDailyDate(event.target.value)} /></label>
        {outputActions(false)}
      </div>
      {loading && <p className="sales-activity-state" role="status">{t("loading")}</p>}
      {(error || errorKey) && <div className="sales-activity-state sales-activity-state--error" role="alert"><span>{errorKey ? t(errorKey) : error}</span><button type="button" onClick={retry}>{t("retry")}</button></div>}
      {daily && <div className="sales-daily-layout">
        {renderSummaryBlock(daily, daily.companyName, true)}
        {operationsBlock()}
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
        <div className="sales-documents-period"><span>{t("currentPeriod")}</span><strong>{rangeLabel(range)}</strong><small>{formatDate(range.from, locale)} — {formatDate(range.to, locale)}</small></div>
        {outputActions(true)}
      </div>
      {loading && <p className="sales-activity-state" role="status">{t("loading")}</p>}
      {(error || errorKey) && <div className="sales-activity-state sales-activity-state--error" role="alert"><span>{errorKey ? t(errorKey) : error}</span><button type="button" onClick={retry}>{t("retry")}</button></div>}
      {!loading && !error && !errorKey && <div className="sales-documents-table-scroll">
        {viewMode === "DAY" && dailyRows.length === 0 && <p className="sales-activity-state">{t("noDocuments")}</p>}
        {viewMode === "DOCUMENT" && rows.length === 0 && <p className="sales-activity-state">{t("noDocuments")}</p>}
        {viewMode === "DAY" ? (dailyRows.length > 0 ? dailyDocumentTable() : null) : (rows.length > 0 ? <table className="sales-documents-table" style={documentTableStyle}>
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
        </table> : null)}
        {((viewMode === "DAY" ? dailyPage : page)?.hasMore) && <button className="sales-documents-load-more" type="button" disabled={loadingMore} onClick={() => void loadMore()}>{loadingMore ? t("loading") : t("loadMore")}</button>}
      </div>}
      <footer className="sales-activity-filter-dock" aria-label={t("currentPeriod")}>
        <button type="button" disabled={!filterOptionsReady} className={range.preset === "TODAY" ? "selected" : ""} onClick={() => selectRange(currentDayRange(t("today"), today))}>{t("today")}</button>
        <button type="button" disabled={!filterOptionsReady} onClick={selectYesterday}>{t("yesterday")}</button>
        <button type="button" disabled={!filterOptionsReady} onClick={selectWeek}>{t("week")}</button>
        <label><span>{t("month")}</span><select disabled={!filterOptionsReady} defaultValue="" onChange={(event) => selectMonth(event.target.value)}><option value="">—</option>{monthOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
        <label><span>{t("quarter")}</span><select disabled={!filterOptionsReady} defaultValue="" onChange={(event) => selectQuarter(event.target.value)}><option value="">—</option>{quarterOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
        <label><span>{t("year")}</span><select disabled={!filterOptionsReady} defaultValue="" onChange={(event) => selectYear(event.target.value)}><option value="">—</option>{yearOptions.map((year) => <option key={year} value={year}>{year}</option>)}</select></label>
        <button type="button" disabled={!filterOptionsReady} onClick={() => setCustomOpen((value) => !value)}>{t("custom")}</button>
        {customOpen && <div className="sales-activity-custom-period"><label><span>{t("from")}</span><input type="date" value={draftFrom} onChange={(event) => setDraftFrom(event.target.value)} /></label><label><span>{t("to")}</span><input type="date" max={today} value={draftTo} onChange={(event) => setDraftTo(event.target.value)} /></label><button type="button" disabled={!draftFrom || !draftTo || draftTo < draftFrom} onClick={() => selectRange({ from: draftFrom, to: draftTo, label: "", preset: "CUSTOM" })}>{t("apply")}</button></div>}
      </footer>
    </div>;
  }

  return <section className={`sales-activity-panel sales-activity-panel--${mode}`}>
    {mode === "daily" ? dailyContent() : documentsContent()}
    {notice && <div className={`sales-activity-notice sales-activity-notice--${notice.kind}`} role={notice.kind === "error" ? "alert" : "status"}>{notice.key ? t(notice.key) : notice.text}</div>}
  </section>;
}
