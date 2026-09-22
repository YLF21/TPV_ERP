import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import { CalendarBlank, CaretDown } from "@phosphor-icons/react";
import { apiRequest } from "../api/client";
import { apiBaseUrl } from "../api/runtime";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { AppKind, LocaleCode } from "../types";
import { ErpSelect } from "./ErpSelect";
import { ErpFilterChips, type ErpFilterChip } from "./ErpFilterChips";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { enterNavigationIntent, focusRelativeEnterTarget } from "./keyboardNavigation";
import { visibleTableColumns } from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";
import { useOutsidePointerDown } from "./useOutsidePointerDown";
import { localeTag } from "../money";
import { customerDocumentAmount } from "./customerDocumentAmount";
import { compareSaasHistoryDecimals, formatSaasHistoryDecimal, saasSalesHistoryBasePath, saasSalesHistoryPath } from "../api/stockSalesHistory";
import type { SaasSalesHistoryItem, SaasSalesHistoryResponse, SaasSalesHistoryStore, SaasSalesHistoryView } from "../api/stockSalesHistory";
import { useTableSortPreference } from "./tableSorting";

export type StockSalesHistoryRow = {
  documentId: string;
  documentType: string;
  documentNumber?: string | null;
  status: string;
  occurredAt: string;
  customerId?: string | null;
  customerName?: string | null;
  quantity: number;
  unitPrice: number;
  discountPercent: number;
  lineTotal: number;
  userId?: string | null;
  userName?: string | null;
  storeId?: string | null;
  storeName?: string | null;
  warehouseId?: string | null;
  warehouseName?: string | null;
};

type StockSalesHistoryPanelProps = {
  productId: string;
  productCode?: string;
  productName: string;
  showProductHeading?: boolean;
  productType?: string | null;
  productImageSource?: string;
  locale: LocaleCode;
  app?: AppKind;
  username?: string;
  accessToken?: string;
  token?: string;
  onClose: () => void;
  onOpenDocument?: (documentId: string, documentType: string) => void | Promise<void>;
};

const stockSalesHistoryColumnDefinitions = [
  { key: "occurredAt", defaultWidth: 160 },
  { key: "document", defaultWidth: 180 },
  { key: "status", defaultWidth: 130 },
  { key: "customer", defaultWidth: 200 },
  { key: "quantity", defaultWidth: 110 },
  { key: "unitPrice", defaultWidth: 130 },
  { key: "discount", defaultWidth: 110 },
  { key: "total", defaultWidth: 130 },
  { key: "user", defaultWidth: 150 },
  { key: "store", defaultWidth: 160 }
] as const;

type StockSalesHistoryColumnKey = typeof stockSalesHistoryColumnDefinitions[number]["key"];

function localIsoDate(date: Date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

export function defaultStockSalesHistoryRange(date = new Date()) {
  const from = new Date(date);
  from.setDate(from.getDate() - 29);
  return { from: localIsoDate(from), to: localIsoDate(date) };
}

export function stockSalesHistoryPath(productId: string, from: string, to: string) {
  const query = new URLSearchParams({ from, to });
  return `/stock/products/${encodeURIComponent(productId)}/sales-history?${query.toString()}`;
}

export function filterStockSalesHistoryRows(rows: StockSalesHistoryRow[], status: string) {
  if (!status) {
    return rows;
  }
  return rows.filter((row) => row.status === status);
}

export function effectiveStockSalesHistoryTotals(rows: StockSalesHistoryRow[]) {
  return rows.reduce((totals, row) => {
    if (row.status === "ANULADO") return totals;
    totals.quantity += Number(row.quantity) || 0;
    totals.amount += Number(row.lineTotal) || 0;
    return totals;
  }, { quantity: 0, amount: 0 });
}

export function stockSalesDocumentLabel(row: Pick<StockSalesHistoryRow, "documentId" | "documentNumber" | "documentType">) {
  return [row.documentType, row.documentNumber || row.documentId].filter(Boolean).join(" ");
}

const comparisonColumnDefinitions = [
  { key: "store", defaultWidth: 260 },
  { key: "quantitySold", defaultWidth: 160 },
  { key: "quantityReturned", defaultWidth: 160 },
  { key: "netQuantity", defaultWidth: 170 },
  { key: "netAmount", defaultWidth: 200 },
] as const;
type ComparisonColumnKey = typeof comparisonColumnDefinitions[number]["key"];

export function StockSalesHistoryPanel({
  productId, productCode = "", productName, showProductHeading = true,
  locale, app = "venta", username = "", accessToken, token, onClose,
}: StockSalesHistoryPanelProps) {
  const t = createTranslator(locale);
  const requestToken = accessToken ?? token;
  const initialRange = useMemo(() => defaultStockSalesHistoryRange(), []);
  const [dateFrom, setDateFrom] = useState(initialRange.from);
  const [dateTo, setDateTo] = useState(initialRange.to);
  const [appliedFrom, setAppliedFrom] = useState(initialRange.from);
  const [appliedTo, setAppliedTo] = useState(initialRange.to);
  const [statusFilter, setStatusFilter] = useState("");
  const [storeFilter, setStoreFilter] = useState("");
  const [view, setView] = useState<SaasSalesHistoryView>("detail");
  const [stores, setStores] = useState<SaasSalesHistoryStore[]>([]);
  const statuses = ["CONFIRMADO", "ANULADO", "PENDIENTE", "PARCIAL", "PAGADO"];
  const [result, setResult] = useState<{ query: string; token: string; path: string; data: SaasSalesHistoryResponse } | null>(null);
  const [page, setPage] = useState<{ query: string; token?: string; cursor: string | null }>({ query: "", cursor: null });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const [documentNotice, setDocumentNotice] = useState("");
  const [exportMenuOpen, setExportMenuOpen] = useState(false);
  const [exportBusy, setExportBusy] = useState(false);
  const [exportError, setExportError] = useState("");
  const historyToolbarRef = useRef<HTMLDivElement | null>(null);
  const applyButtonRef = useRef<HTMLButtonElement | null>(null);
  const exportMenuRef = useRef<HTMLDivElement | null>(null);
  const tableScrollRef = useRef<HTMLDivElement | null>(null);
  useOutsidePointerDown(exportMenuOpen, exportMenuRef, () => setExportMenuOpen(false));
  const tableLayout = useTableLayoutPreference({
    app, username, accessToken, tableKey: "stock.productSalesHistory",
    definitions: stockSalesHistoryColumnDefinitions,
  });
  const comparisonLayout = useTableLayoutPreference({
    app, username, accessToken, tableKey: "stock.productSalesHistory.saasComparison",
    definitions: comparisonColumnDefinitions,
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const comparisonColumns = visibleTableColumns(comparisonLayout.layout);
  const tableSort = useTableSortPreference({
    app, username, tableKey: "stock.productSalesHistory",
    columns: stockSalesHistoryColumnDefinitions.map((column) => column.key),
    defaultSort: { column: "occurredAt", direction: "desc" }, persistent: Boolean(username),
  });
  const comparisonSort = useTableSortPreference({
    app, username, tableKey: "stock.productSalesHistory.saasComparison",
    columns: comparisonColumnDefinitions.map((column) => column.key),
    defaultSort: { column: "netQuantity", direction: "desc" }, persistent: Boolean(username),
  });
  const query = {
    from: appliedFrom, to: appliedTo, status: statusFilter,
    storeIds: storeFilter ? [storeFilter] : [],
    sortBy: tableSort.sort?.column ?? "occurredAt",
    sortDirection: tableSort.sort?.direction ?? "desc" as const,
  };
  const queryPath = saasSalesHistoryPath(productId, query);
  const cursor = page.query === queryPath && page.token === requestToken ? page.cursor : null;
  const requestPath = saasSalesHistoryPath(productId, query, cursor);
  const data = result?.query === queryPath && result.token === requestToken ? result.data : null;
  const busy = loading || (result?.path !== requestPath && !error);
  const rows = data?.items ?? [];
  const comparison = useMemo(() => [...(data?.comparison ?? [])].sort((left, right) => {
    const sort = comparisonSort.sort ?? { column: "netQuantity", direction: "desc" };
    const result = sort.column === "store"
      ? left.storeName.localeCompare(right.storeName, localeTag(locale), { numeric: true })
      : compareSaasHistoryDecimals(left[sort.column], right[sort.column]);
    return result * (sort.direction === "desc" ? -1 : 1)
      || left.storeCode.localeCompare(right.storeCode) || left.currency.localeCompare(right.currency);
  }), [data, comparisonSort.sort, locale]);
  const displayedCount = view === "detail" ? rows.length : comparison.length;
  const currentColumns = view === "detail" ? visibleColumns : comparisonColumns;
  const tableMinWidth = currentColumns.reduce((total, column) => total + column.width, 0);
  const dateFormatter = new Intl.DateTimeFormat(localeTag(locale), { dateStyle: "short", timeStyle: "short" });
  const columnLabels: Record<StockSalesHistoryColumnKey, string> = {
    occurredAt: t("stock.history.occurredAt"), document: t("stock.history.document"),
    status: t("salesReport.column.status"), customer: t("salesReport.column.customer"),
    quantity: t("stock.history.quantity"), unitPrice: t("stock.history.unitPrice"),
    discount: t("stock.history.discount"), total: t("salesReport.column.total"),
    user: t("salesReport.column.user"), store: t("stock.history.store"),
  };
  const comparisonLabels: Record<ComparisonColumnKey, string> = {
    store: t("stock.history.store"), quantitySold: t("stock.history.saas.quantitySold"),
    quantityReturned: t("stock.history.saas.quantityReturned"),
    netQuantity: t("stock.history.saas.netQuantity"), netAmount: t("stock.history.saas.netAmount"),
  };

  useEffect(() => {
    function handleKeyDown(event: globalThis.KeyboardEvent) {
      if (event.key !== "Escape" || event.defaultPrevented) return;
      event.preventDefault();
      if (exportMenuOpen) setExportMenuOpen(false);
      else onClose();
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [exportMenuOpen, onClose]);

  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();
    setPage((current) => current.query === queryPath && current.token === requestToken
      ? current : { query: queryPath, token: requestToken, cursor: null });
    if (!cursor) {
      setResult(null);
      if (tableScrollRef.current) tableScrollRef.current.scrollTop = 0;
    }
    setError("");
    setDocumentNotice("");
    setExportError("");
    if (!requestToken || !productId) {
      setError("stock.history.noAccess");
      setLoading(false);
      return;
    }
    setLoading(true);
    void apiRequest<SaasSalesHistoryResponse>(requestPath, { token: requestToken, signal: controller.signal })
      .then((response) => {
        if (cancelled) return;
        if (response.coverage !== "RECEIVED_IN_SAAS" || !Array.isArray(response.items)
            || !Array.isArray(response.totals) || !Array.isArray(response.comparison)
            || response.hasMore && (!response.nextCursor || response.nextCursor === cursor || !response.items.length)) {
          throw new Error("sales_history_saas_response_invalid");
        }
        setResult((current) => {
          const previous = cursor && current?.query === queryPath && current.token === requestToken ? current.data.items : [];
          const items = new Map(previous.map((row) => [`${row.storeId}/${row.documentId}/${row.linePosition}`, row]));
          for (const row of response.items) items.set(`${row.storeId}/${row.documentId}/${row.linePosition}`, row);
          return { query: queryPath, token: requestToken, path: requestPath, data: { ...response, items: [...items.values()] } };
        });
        setStores(response.stores);
      })
      .catch(() => { if (!cancelled) setError("stock.history.saas.unavailable"); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; controller.abort(); };
  }, [queryPath, cursor, requestPath, requestToken, retry]);

  const loadMore = useCallback(() => {
    if (view !== "detail" || busy || error || !data?.hasMore || !data.nextCursor) return;
    const nextCursor = data.nextCursor;
    setPage((current) => current.query === queryPath && current.token === requestToken && current.cursor === nextCursor
      ? current : { query: queryPath, token: requestToken, cursor: nextCursor });
  }, [view, busy, error, data, queryPath, requestToken]);

  useEffect(() => {
    const body = tableScrollRef.current;
    // Fill a tall viewport, then wait for the user to approach the end of the loaded rows.
    if (body && body.clientHeight > 0 && body.scrollHeight <= body.clientHeight) loadMore();
  }, [loadMore]);

  function applyFilters() {
    const from = dateFrom || dateTo;
    const to = dateTo || dateFrom;
    if (app === "pda" && (!from || !to)) return;
    setAppliedFrom(from <= to ? from : to);
    setAppliedTo(from <= to ? to : from);
    if (app !== "pda") {
      setDateFrom(from <= to ? from : to);
      setDateTo(from <= to ? to : from);
    }
    setPage({ query: queryPath, token: requestToken, cursor: null });
    setRetry((value) => value + 1);
  }

  function removeFilter(key: "period" | "status" | "store") {
    if (key === "period") {
      setDateFrom("");
      setDateTo("");
      setAppliedFrom("");
      setAppliedTo("");
    } else if (key === "status") setStatusFilter("");
    else setStoreFilter("");
    setPage({ query: "", cursor: null });
  }

  function clearFilters() {
    setDateFrom("");
    setDateTo("");
    setAppliedFrom("");
    setAppliedTo("");
    setStatusFilter("");
    setStoreFilter("");
    setPage({ query: "", cursor: null });
  }

  const dateLabel = (value: string) => new Intl.DateTimeFormat(localeTag(locale), { dateStyle: "short" })
    .format(new Date(`${value}T00:00:00`));
  const selectedStore = stores.find((store) => store.id === storeFilter);
  const filterChips: ErpFilterChip[] = [
    ...(appliedFrom || appliedTo ? [{
      key: "period", label: t("salesReport.filter.dateRange"),
      value: appliedFrom && appliedTo ? `${dateLabel(appliedFrom)} – ${dateLabel(appliedTo)}`
        : `${t(appliedFrom ? "salesReport.filter.dateFrom" : "salesReport.filter.dateTo")} ${dateLabel(appliedFrom || appliedTo)}`,
      onRemove: () => removeFilter("period"),
    }] : []),
    ...(statusFilter ? [{ key: "status", label: t("salesReport.filter.status"), value: statusFilter,
      onRemove: () => removeFilter("status") }] : []),
    ...(storeFilter ? [{ key: "store", label: t("stock.history.store"),
      value: selectedStore ? `${selectedStore.code} · ${selectedStore.name}` : storeFilter,
      onRemove: () => removeFilter("store") }] : []),
  ];

  function exportFileName(extension: "xlsx" | "pdf") {
    const identity = (productCode || productName || "producto").normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "").replace(/[^a-zA-Z0-9_-]+/g, "-")
      .replace(/^-|-$/g, "").toLowerCase();
    return `historial-ventas-${identity || "producto"}.${extension}`;
  }

  async function exportReport(extension: "xlsx" | "pdf") {
    if (!requestToken || exportBusy || busy || !data || displayedCount === 0) return;
    setExportMenuOpen(false);
    setExportBusy(true);
    setExportError("");
    try {
      const response = await fetch(`${apiBaseUrl}${saasSalesHistoryBasePath(productId)}/${extension === "xlsx" ? "export" : "render"}`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${requestToken}` },
        body: JSON.stringify({
          ...query, from: appliedFrom || null, to: appliedTo || null, status: statusFilter || null, view, locale,
          comparisonSortBy: comparisonSort.sort?.column ?? "netQuantity",
          comparisonSortDirection: comparisonSort.sort?.direction ?? "desc",
          columns: view === "detail"
            ? visibleColumns.map((column) => ({ key: column.key, label: columnLabels[column.key] }))
            : comparisonColumns.map((column) => ({ key: column.key, label: comparisonLabels[column.key] })),
          labels: {
            title: t("stock.history.title"), product: t("sale.main.product"), code: t("stock.column.code"),
            period: t("salesReport.filter.date"), status: t("salesReport.filter.status"),
            allStatuses: t("salesReport.filter.all"), totalQuantity: t("stock.history.saas.netQuantity"),
            totalAmount: t("stock.history.saas.netAmount"),
          },
        }),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      let bytes: Uint8Array;
      let fileName = exportFileName(extension);
      if (extension === "pdf") {
        const rendered = await response.json() as { renderedPdf?: { base64: string }; fileName?: string };
        if (!rendered.renderedPdf?.base64) throw new Error("sales_history_rendered_pdf_missing");
        bytes = Uint8Array.from(window.atob(rendered.renderedPdf.base64), (character) => character.charCodeAt(0));
        fileName = rendered.fileName || fileName;
      } else bytes = new Uint8Array(await response.arrayBuffer());
      if (window.tpvDesktop?.reports) {
        const saved = await window.tpvDesktop.reports.saveFile({
          defaultFileName: fileName, filters: [{ name: extension === "xlsx" ? "Excel" : "PDF", extensions: [extension] }], bytes,
        });
        if (!saved.ok) throw new Error(saved.message);
      } else downloadBytes(bytes, fileName, extension === "xlsx"
        ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/pdf");
    } catch {
      setExportError("stock.history.exportError");
    } finally { setExportBusy(false); }
  }

  function identifyDocument(row: SaasSalesHistoryItem) {
    // A central document ID must never be resolved against the local store's document routes.
    setDocumentNotice(t("stock.history.saas.remoteDocument")
      .replace("{document}", stockSalesDocumentLabel(row)).replace("{store}", row.storeName || row.storeCode));
  }

  function openStore(storeId: string) {
    applyButtonRef.current?.focus();
    setStoreFilter(storeId);
    setView("detail");
    setPage({ query: "", cursor: null });
  }

  function handleHistoryFilterEnter(event: KeyboardEvent<HTMLElement>) {
    const intent = enterNavigationIntent(event.key, {
      shiftKey: event.shiftKey, ctrlKey: event.ctrlKey, altKey: event.altKey, metaKey: event.metaKey,
      isComposing: event.nativeEvent.isComposing,
    });
    if (!intent || !(event.target as HTMLElement).matches("input")) return;
    event.preventDefault();
    focusRelativeEnterTarget(historyToolbarRef.current, event.target as HTMLElement, intent,
      "input:not(:disabled), .erp-select__trigger:not(:disabled), button:not(:disabled)");
  }

  return (
    <section className="stock-sales-history-panel stock-product-sales-history-panel" aria-label={t("stock.history.title")}>
      <div className="stock-history-toolbar" ref={historyToolbarRef} onKeyDown={handleHistoryFilterEnter}>
        <label><span>{t("salesReport.filter.dateFrom")}</span><span className="stock-history-date-input">
          <input type="date" value={dateFrom} onChange={(event) => setDateFrom(event.target.value)} />
          <CalendarBlank aria-hidden="true" size={18} />
        </span></label>
        <label><span>{t("salesReport.filter.dateTo")}</span><span className="stock-history-date-input">
          <input type="date" value={dateTo} onChange={(event) => setDateTo(event.target.value)} />
          <CalendarBlank aria-hidden="true" size={18} />
        </span></label>
        <label><span>{t("salesReport.filter.status")}</span><ErpSelect className="erp-select--compact"
          aria-label={t("salesReport.filter.status")} value={statusFilter}
          options={[{ value: "", label: t("salesReport.filter.all") }, ...statuses.map((status) => ({ value: status, label: status }))]}
          onChange={setStatusFilter} onCommit={() => applyButtonRef.current?.focus()} /></label>
        <label><span>{t("stock.history.store")}</span><ErpSelect className="erp-select--compact"
          aria-label={t("stock.history.store")} value={storeFilter}
          options={[{ value: "", label: t("stock.history.saas.allStores") }, ...stores.map((store) => ({ value: store.id, label: `${store.code} · ${store.name}` }))]}
          onChange={setStoreFilter} onCommit={() => applyButtonRef.current?.focus()} /></label>
        <button ref={applyButtonRef} type="button" onClick={applyFilters}>{t("salesReport.filter.apply")}</button>
        <div className="stock-history-export" ref={exportMenuRef}>
          <button type="button" aria-haspopup="menu" aria-expanded={exportMenuOpen}
            disabled={exportBusy || busy || !data || displayedCount === 0} onClick={() => setExportMenuOpen((open) => !open)}>
            {exportBusy ? t("stock.history.exporting") : t("stock.history.export")}<CaretDown aria-hidden="true" size={14} />
          </button>
          {exportMenuOpen && <div className="stock-history-export-menu" role="menu">
            <button type="button" role="menuitem" onClick={() => void exportReport("xlsx")}>{t("stock.history.exportExcel")}</button>
            <button type="button" role="menuitem" onClick={() => void exportReport("pdf")}>{t("stock.history.exportPdf")}</button>
          </div>}
        </div>
      </div>
      {app !== "pda" && <ErpFilterChips chips={filterChips} onClear={clearFilters}
        focusRef={applyButtonRef} locale={locale} />}
      <div className="stock-history-context">
        {showProductHeading && <strong>{productName}</strong>}
        <div className="stock-history-views" role="group" aria-label={t("stock.history.saas.view")}>
          <button type="button" aria-pressed={view === "detail"} onClick={() => setView("detail")}>{t("stock.history.saas.detail")}</button>
          <button type="button" aria-pressed={view === "comparison"} onClick={() => setView("comparison")}>{t("stock.history.saas.comparison")}</button>
        </div>
      </div>
      <div className="stock-history-statuses">
        {busy && !data && <p className="stock-operation-status" aria-live="polite">{t("stock.history.loading")}</p>}
        {error && <p className="stock-operation-status error" role="alert">{t(error)}
          <button type="button" onClick={() => setRetry((value) => value + 1)}>{t("stock.history.saas.retry")}</button></p>}
        {!!data?.incompleteDocuments && <p className="stock-operation-status" role="status">{t("stock.history.saas.incomplete").replace("{count}", String(data.incompleteDocuments))}</p>}
        {documentNotice && <p className="stock-operation-status" aria-live="polite">{documentNotice}</p>}
        {exportError && <p className="stock-operation-status error" role="alert">{t(exportError)}</p>}
      </div>
      <div className="stock-history-table-scroll" ref={tableScrollRef} aria-busy={busy} tabIndex={0}
        onScroll={(event) => {
          const body = event.currentTarget;
          if (body.clientHeight > 0 && body.scrollHeight - body.scrollTop - body.clientHeight < 160) loadMore();
        }}>
        <table className="report-table stock-history-table" style={{ width: "100%", minWidth: tableMinWidth }}>
          <colgroup>{currentColumns.map((column) => <col key={column.key} style={{ width: `${column.width}px` }} />)}</colgroup>
          <thead><tr>{view === "detail" ? visibleColumns.map((column) => (
            <TableLayoutHeaderCell column={column} key={column.key} className={stockHistoryCellClass(column.key)}
              sortDirection={tableSort.sort?.column === column.key ? tableSort.sort.direction : null}
              sortLabel={`${t("party.sortBy")} ${columnLabels[column.key]}`} onSort={tableSort.toggleSort}
              resizeLabel={`${t("stock.columns.resize")} ${columnLabels[column.key]}`}
              onReorder={tableLayout.reorderColumns} onMove={tableLayout.moveColumn} onResize={tableLayout.resizeColumn}>
              {columnLabels[column.key]}
            </TableLayoutHeaderCell>
          )) : comparisonColumns.map((column) => (
            <TableLayoutHeaderCell column={column} key={column.key}
              className={column.key === "store" ? "stock-history-cell-text" : "stock-history-cell-numeric"}
              sortDirection={comparisonSort.sort?.column === column.key ? comparisonSort.sort.direction : null}
              sortLabel={`${t("party.sortBy")} ${comparisonLabels[column.key]}`} onSort={comparisonSort.toggleSort}
              resizeLabel={`${t("stock.columns.resize")} ${comparisonLabels[column.key]}`}
              onReorder={comparisonLayout.reorderColumns} onMove={comparisonLayout.moveColumn} onResize={comparisonLayout.resizeColumn}>
              {comparisonLabels[column.key]}
            </TableLayoutHeaderCell>
          ))}</tr></thead>
          <tbody>
            {view === "detail" ? rows.map((row) => (
              <tr key={`${row.storeId}-${row.documentId}-${row.linePosition}`} tabIndex={0}
                onDoubleClick={() => identifyDocument(row)} onKeyDown={(event) => {
                  if (event.key === "Enter") { event.preventDefault(); identifyDocument(row); }
                }}>
                {visibleColumns.map((column) => {
                  const value = formattedHistoryCell(row, column.key, dateFormatter, locale);
                  return <td key={column.key} data-column-key={column.key} className={stockHistoryCellClass(column.key, row)}>
                    <span className="stock-history-cell-value" title={value}>{value}</span>
                  </td>;
                })}
              </tr>
            )) : comparison.map((row) => (
              <tr key={`${row.storeId}-${row.currency}`} onClick={() => openStore(row.storeId)}>
                {comparisonColumns.map((column) => {
                  const value = column.key === "store" ? `${row.storeCode} · ${row.storeName}`
                    : column.key === "netAmount" ? formatHistoryAmount(row.netAmount, row.currency, locale)
                    : formatSaasHistoryDecimal(row[column.key], locale);
                  return <td key={column.key} data-column-key={column.key}
                    className={column.key === "store" ? "stock-history-cell-text" : "stock-history-cell-numeric"}>
                    {column.key === "store"
                      ? <button type="button" className="stock-history-store-link" title={value} onClick={(event) => { event.stopPropagation(); openStore(row.storeId); }}>{value}</button>
                      : <span className="stock-history-cell-value" title={value}>{value}</span>}
                  </td>;
                })}
              </tr>
            ))}
            {!busy && !error && data && displayedCount === 0 && <tr><td colSpan={currentColumns.length} className="stock-history-cell-text">{t("stock.history.saas.empty")}</td></tr>}
          </tbody>
        </table>
      </div>
      <div className="stock-history-pagination">
        <span>{data ? t(view === "detail" ? "stock.history.saas.pageCount" : "stock.history.saas.storeCount").replace("{count}", String(displayedCount)) : "—"}</span>
        {busy && data && <span role="status">{t("stock.history.loading")}</span>}
        {data?.receivedAt && <span>{t("stock.history.saas.receivedAt")} {formatOccurredAt(data.receivedAt, dateFormatter)}</span>}
      </div>
      <div className="stock-history-totals stock-history-saas-totals" aria-label={t("stock.history.totals")}>
        {data ? data.totals.length ? data.totals.map((total) => <div key={total.currency}>
          <span>{t("stock.history.saas.netQuantity")} ({total.currency})</span><strong>{formatSaasHistoryDecimal(total.netQuantity, locale)}</strong>
          <span>{t("stock.history.saas.netAmount")}</span><strong>{formatHistoryAmount(total.netAmount, total.currency, locale)}</strong>
        </div>) : <span>{t("stock.history.saas.noTotals")}</span> : <span>{t("stock.history.saas.netAmount")}: —</span>}
      </div>
    </section>
  );
}

function stockHistoryCellClass(column: StockSalesHistoryColumnKey, row?: SaasSalesHistoryItem) {
  const numeric = column === "quantity" || column === "unitPrice" || column === "discount" || column === "total";
  const value = column === "quantity" ? row?.quantity : column === "unitPrice" ? row?.unitPrice : column === "total" ? row?.lineTotal : undefined;
  return (numeric ? "stock-history-cell-numeric" : "stock-history-cell-text")
    + (value?.startsWith("-") && compareSaasHistoryDecimals(value, "0") < 0 ? " stock-history-cell-negative" : "");
}

function formattedHistoryCell(row: SaasSalesHistoryItem, column: StockSalesHistoryColumnKey, dateFormatter: Intl.DateTimeFormat, locale: LocaleCode) {
  if (column === "occurredAt") return formatOccurredAt(row.occurredAt, dateFormatter);
  if (column === "document") return stockSalesDocumentLabel(row);
  if (column === "status") return row.status;
  if (column === "customer") return row.customerName || "-";
  if (column === "quantity") return formatSaasHistoryDecimal(row.quantity, locale);
  if (column === "unitPrice") return formatSaasHistoryDecimal(row.unitPrice, locale, 2);
  if (column === "discount") return `${formatSaasHistoryDecimal(row.discountPercent, locale, 2)}%`;
  if (column === "total") return formatHistoryAmount(row.lineTotal, row.currency, locale);
  if (column === "user") return row.userName || "-";
  return row.storeName || row.storeCode || row.storeId;
}

function formatHistoryAmount(value: string, currency: string, locale: LocaleCode) {
  if (!/^-?\d+(?:\.\d+)?$/.test(value) || !/^[A-Z]{3}$/.test(currency)) return "—";
  const normalized = value.includes(".") ? value.replace(/0+$/, "").replace(/\.$/, "") : value;
  if ((normalized.split(".")[1]?.length ?? 0) <= 2) {
    return customerDocumentAmount(normalized, currency, localeTag(locale));
  }
  // Historical SaaS values may have a wider scale; display them without rounding away received precision.
  return `${formatSaasHistoryDecimal(normalized, locale, 2)} ${currency}`;
}

function downloadBytes(bytes: Uint8Array, fileName: string, type: string) {
  const copy = new Uint8Array(bytes.length);
  copy.set(bytes);
  const url = URL.createObjectURL(new Blob([copy.buffer], { type }));
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}

function formatOccurredAt(value: string, formatter: Intl.DateTimeFormat) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : formatter.format(date);
}
