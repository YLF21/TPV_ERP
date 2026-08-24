import { useEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import { apiRequest } from "../api/client";
import { apiBaseUrl } from "../api/runtime";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { AppKind, LocaleCode } from "../types";
import { ErpSelect } from "./ErpSelect";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { enterNavigationIntent, focusRelativeEnterTarget } from "./keyboardNavigation";
import { visibleTableColumns } from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";
import { useOutsidePointerDown } from "./useOutsidePointerDown";
import { formatProductQuantity } from "../sale/productQuantity";
import { sortTableRows, useTableSortPreference } from "./tableSorting";

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
  { key: "store", defaultWidth: 160 },
  { key: "warehouse", defaultWidth: 160 }
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

export function StockSalesHistoryPanel({
  productId,
  productCode = "",
  productName,
  productType,
  productImageSource = "",
  locale,
  app = "venta",
  username = "",
  accessToken,
  token,
  onClose,
  onOpenDocument
}: StockSalesHistoryPanelProps) {
  const t = createTranslator(locale);
  const requestToken = accessToken ?? token;
  const initialRange = useMemo(() => defaultStockSalesHistoryRange(), []);
  const [dateFrom, setDateFrom] = useState(initialRange.from);
  const [dateTo, setDateTo] = useState(initialRange.to);
  const [appliedFrom, setAppliedFrom] = useState(initialRange.from);
  const [appliedTo, setAppliedTo] = useState(initialRange.to);
  const [statusFilter, setStatusFilter] = useState("");
  const [rows, setRows] = useState<StockSalesHistoryRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [documentNotice, setDocumentNotice] = useState("");
  const [exportMenuOpen, setExportMenuOpen] = useState(false);
  const [exportBusy, setExportBusy] = useState(false);
  const [exportError, setExportError] = useState("");
  const historyToolbarRef = useRef<HTMLDivElement | null>(null);
  const applyButtonRef = useRef<HTMLButtonElement | null>(null);
  const exportMenuRef = useRef<HTMLDivElement | null>(null);
  useOutsidePointerDown(exportMenuOpen, exportMenuRef, () => setExportMenuOpen(false));
  const tableLayout = useTableLayoutPreference({
    app,
    username,
    accessToken,
    tableKey: "stock.productSalesHistory",
    definitions: stockSalesHistoryColumnDefinitions
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const tableSort = useTableSortPreference({
    app,
    username,
    tableKey: "stock.productSalesHistory",
    columns: stockSalesHistoryColumnDefinitions.map((column) => column.key),
    defaultSort: null,
    persistent: Boolean(username)
  });

  useEffect(() => {
    function handleKeyDown(event: globalThis.KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        if (exportMenuOpen) {
          setExportMenuOpen(false);
        } else {
          onClose();
        }
      }
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [exportMenuOpen, onClose]);

  useEffect(() => {
    let cancelled = false;
    if (!requestToken || !productId) {
      setRows([]);
      setError(t("stock.history.noAccess"));
      return;
    }
    setLoading(true);
    setError("");
    void apiRequest<StockSalesHistoryRow[]>(stockSalesHistoryPath(productId, appliedFrom, appliedTo), { token: requestToken })
      .then((result) => {
        if (!cancelled) {
          setRows(result);
        }
      })
      .catch((requestError) => {
        if (!cancelled) {
          setRows([]);
          setError(requestError instanceof Error ? requestError.message : t("stock.history.loadError"));
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [appliedFrom, appliedTo, productId, requestToken]);

  const statuses = Array.from(new Set(rows.map((row) => row.status).filter(Boolean))).sort();
  const visibleRows = sortTableRows(
    filterStockSalesHistoryRows(rows, statusFilter),
    tableSort.sort,
    (row, column) => {
      if (column === "occurredAt") return new Date(row.occurredAt);
      if (column === "document") return stockSalesDocumentLabel(row);
      if (column === "status") return row.status;
      if (column === "customer") return row.customerName || row.customerId;
      if (column === "quantity") return Number(row.quantity) || 0;
      if (column === "unitPrice") return Number(row.unitPrice) || 0;
      if (column === "discount") return Number(row.discountPercent) || 0;
      if (column === "total") return Number(row.lineTotal) || 0;
      if (column === "user") return row.userName || row.userId;
      if (column === "store") return row.storeName || row.storeId;
      return row.warehouseName || row.warehouseId;
    },
    locale
  );
  const totals = effectiveStockSalesHistoryTotals(visibleRows);
  const numberFormatter = new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
  const dateFormatter = new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", {
    dateStyle: "short",
    timeStyle: "short"
  });
  const columnLabels: Record<StockSalesHistoryColumnKey, string> = {
    occurredAt: t("stock.history.occurredAt"),
    document: t("stock.history.document"),
    status: t("salesReport.column.status"),
    customer: t("salesReport.column.customer"),
    quantity: t("stock.history.quantity"),
    unitPrice: t("stock.history.unitPrice"),
    discount: t("stock.history.discount"),
    total: t("salesReport.column.total"),
    user: t("salesReport.column.user"),
    store: t("stock.history.store"),
    warehouse: t("stock.column.warehouse")
  };

  function exportColumns() {
    return visibleColumns.map((column) => ({ key: column.key, label: columnLabels[column.key] }));
  }

  function exportFileName(extension: "xlsx" | "pdf") {
    const identity = (productCode || productName || "producto")
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .replace(/[^a-zA-Z0-9_-]+/g, "-")
      .replace(/^-|-$/g, "")
      .toLowerCase();
    return `historial-ventas-${identity || "producto"}.${extension}`;
  }

  function applyFilters() {
    const from = dateFrom || dateTo;
    const to = dateTo || dateFrom;
    if (!from || !to) {
      return;
    }
    setAppliedFrom(from <= to ? from : to);
    setAppliedTo(from <= to ? to : from);
  }

  async function exportExcel() {
    if (!requestToken || exportBusy || visibleRows.length === 0) return;
    setExportMenuOpen(false);
    setExportBusy(true);
    setExportError("");
    try {
      const response = await fetch(
        `${apiBaseUrl}/stock/products/${encodeURIComponent(productId)}/sales-history/export`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${requestToken}`,
          },
          body: JSON.stringify({
            from: appliedFrom,
            to: appliedTo,
            status: statusFilter || null,
            labels: {
              title: t("stock.history.title"),
              product: t("sale.main.product"),
              code: t("stock.column.code"),
              period: t("salesReport.filter.date"),
              status: t("salesReport.filter.status"),
              allStatuses: t("salesReport.filter.all"),
              totalQuantity: t("stock.history.totalQuantity"),
              totalAmount: t("stock.history.totalAmount"),
            },
            columns: exportColumns(),
          }),
        },
      );
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const bytes = new Uint8Array(await response.arrayBuffer());
      const fileName = exportFileName("xlsx");
      if (window.tpvDesktop?.reports) {
        const result = await window.tpvDesktop.reports.saveFile({
          defaultFileName: fileName,
          filters: [{ name: "Excel", extensions: ["xlsx"] }],
          bytes,
        });
        if (!result.ok) throw new Error(result.message);
      } else {
        downloadBytes(bytes, fileName,
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
      }
    } catch (caught) {
      setExportError(caught instanceof Error ? caught.message : t("stock.history.exportError"));
    } finally {
      setExportBusy(false);
    }
  }

  async function exportPdf() {
    if (exportBusy || visibleRows.length === 0) return;
    setExportMenuOpen(false);
    setExportBusy(true);
    setExportError("");
    try {
      if (!requestToken) return;
      const response = await fetch(
        `${apiBaseUrl}/stock/products/${encodeURIComponent(productId)}/sales-history/render`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json", Authorization: `Bearer ${requestToken}` },
          body: JSON.stringify({
            from: appliedFrom,
            to: appliedTo,
            status: statusFilter || null,
            columns: visibleColumns.map((column) => column.key),
            sortBy: tableSort.sort?.column || "occurredAt",
            sortDirection: tableSort.sort?.direction || "asc"
          })
        }
      );
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const rendered = await response.json() as { renderedPdf?: { contentType: string; base64: string }; fileName?: string };
      if (!rendered.renderedPdf?.base64) throw new Error("sales_history_rendered_pdf_missing");
      const binary = window.atob(rendered.renderedPdf.base64);
      const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
      const fileName = rendered.fileName || exportFileName("pdf");
      if (window.tpvDesktop?.reports) {
        const result = await window.tpvDesktop.reports.saveFile({
          defaultFileName: fileName,
          filters: [{ name: "PDF", extensions: ["pdf"] }],
          bytes,
        });
        if (!result.ok) throw new Error(result.message);
      } else {
        downloadBytes(bytes, fileName, "application/pdf");
      }
    } catch (caught) {
      setExportError(caught instanceof Error ? caught.message : t("stock.history.exportError"));
    } finally {
      setExportBusy(false);
    }
  }

  function identifyDocument(row: StockSalesHistoryRow) {
    const label = stockSalesDocumentLabel(row);
    const fallback = () => setDocumentNotice(t("stock.history.documentIdentified").replace("{document}", label));
    if (!onOpenDocument) {
      fallback();
      return;
    }
    try {
      const result = onOpenDocument(row.documentId, row.documentType);
      if (result && typeof result.then === "function") {
        void result.catch(fallback);
      }
    } catch {
      fallback();
    }
  }

  function handleHistoryFilterEnter(event: KeyboardEvent<HTMLElement>) {
    const intent = enterNavigationIntent(event.key, {
      shiftKey: event.shiftKey,
      ctrlKey: event.ctrlKey,
      altKey: event.altKey,
      metaKey: event.metaKey,
      isComposing: event.nativeEvent.isComposing
    });
    if (!intent || !(event.target as HTMLElement).matches("input")) return;
    event.preventDefault();
    focusRelativeEnterTarget(
      historyToolbarRef.current,
      event.target as HTMLElement,
      intent,
      "input:not(:disabled), .erp-select__trigger:not(:disabled), button:not(:disabled)"
    );
  }

  return (
    <section className="stock-sales-history-panel" aria-label={t("stock.history.title")}>
      <div className="stock-history-toolbar" ref={historyToolbarRef} onKeyDown={handleHistoryFilterEnter}>
        <label>
          <span>{t("salesReport.filter.dateFrom")}</span>
          <input type="date" value={dateFrom} onChange={(event) => setDateFrom(event.target.value)} />
        </label>
        <label>
          <span>{t("salesReport.filter.dateTo")}</span>
          <input type="date" value={dateTo} onChange={(event) => setDateTo(event.target.value)} />
        </label>
        <label>
          <span>{t("salesReport.filter.status")}</span>
          <ErpSelect
            className="erp-select--compact"
            aria-label={t("salesReport.filter.status")}
            value={statusFilter}
            options={[
              { value: "", label: t("salesReport.filter.all") },
              ...statuses.map((status) => ({ value: status, label: status }))
            ]}
            onChange={setStatusFilter}
            onCommit={() => applyButtonRef.current?.focus()}
          />
        </label>
        <button ref={applyButtonRef} type="button" onClick={applyFilters}>{t("salesReport.filter.apply")}</button>
        <div className="stock-history-export" ref={exportMenuRef}>
          <button
            type="button"
            aria-haspopup="menu"
            aria-expanded={exportMenuOpen}
            disabled={exportBusy || loading || visibleRows.length === 0}
            onClick={() => setExportMenuOpen((open) => !open)}
          >
            {exportBusy ? t("stock.history.exporting") : t("stock.history.export")}
          </button>
          {exportMenuOpen && (
            <div className="stock-history-export-menu" role="menu">
              <button type="button" role="menuitem" onClick={() => void exportExcel()}>
                {t("stock.history.exportExcel")}
              </button>
              <button type="button" role="menuitem" onClick={() => void exportPdf()}>
                {t("stock.history.exportPdf")}
              </button>
            </div>
          )}
        </div>
      </div>

      <div className="stock-history-context">
        <strong>{productName}</strong>
        <span>{t("stock.history.resultCount").replace("{count}", String(visibleRows.length))}</span>
      </div>

      <div className="stock-history-statuses">
        {loading && <p className="stock-operation-status" aria-live="polite">{t("stock.history.loading")}</p>}
        {error && <p className="stock-operation-status error" role="alert">{error}</p>}
        {documentNotice && <p className="stock-operation-status" aria-live="polite">{documentNotice}</p>}
        {exportError && <p className="stock-operation-status error" role="alert">{exportError}</p>}
      </div>

      <div className="stock-history-table-scroll">
        <table className="report-table stock-history-table">
          <colgroup>
            {visibleColumns.map((column) => (
              <col key={column.key} style={{ width: `${column.width}px` }} />
            ))}
          </colgroup>
          <thead>
            <tr>
              {visibleColumns.map((column) => (
                <TableLayoutHeaderCell
                  column={column}
                  key={column.key}
                  sortDirection={tableSort.sort?.column === column.key ? tableSort.sort.direction : null}
                  sortLabel={`${t("party.sortBy")} ${columnLabels[column.key]}`}
                  onSort={tableSort.toggleSort}
                  resizeLabel={`${t("stock.columns.resize")} ${columnLabels[column.key]}`}
                  onReorder={tableLayout.reorderColumns}
                  onMove={tableLayout.moveColumn}
                  onResize={tableLayout.resizeColumn}
                >
                  {columnLabels[column.key]}
                </TableLayoutHeaderCell>
              ))}
            </tr>
          </thead>
          <tbody>
            {visibleRows.map((row) => (
              <tr
                key={`${row.documentId}-${row.occurredAt}-${row.warehouseId ?? ""}`}
                tabIndex={0}
                onDoubleClick={() => identifyDocument(row)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    identifyDocument(row);
                  }
                }}
              >
                {visibleColumns.map((column) => (
                  <td key={column.key}>
                    {column.key === "occurredAt" && formatOccurredAt(row.occurredAt, dateFormatter)}
                    {column.key === "document" && stockSalesDocumentLabel(row)}
                    {column.key === "status" && row.status}
                    {column.key === "customer" && (row.customerName || row.customerId || "-")}
                    {column.key === "quantity" && formatProductQuantity(row.quantity, productType, locale)}
                    {column.key === "unitPrice" && numberFormatter.format(Number(row.unitPrice) || 0)}
                    {column.key === "discount" && `${numberFormatter.format(Number(row.discountPercent) || 0)}%`}
                    {column.key === "total" && numberFormatter.format(Number(row.lineTotal) || 0)}
                    {column.key === "user" && (row.userName || row.userId || "-")}
                    {column.key === "store" && (row.storeName || row.storeId || "-")}
                    {column.key === "warehouse" && (row.warehouseName || row.warehouseId || "-")}
                  </td>
                ))}
              </tr>
            ))}
            {!loading && !error && visibleRows.length === 0 && (
              <tr>
                <td colSpan={visibleColumns.length}>{t("stock.history.empty")}</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <div className="stock-history-totals" aria-label={t("stock.history.totals")}>
        <div>
          <span>{t("stock.history.totalQuantity")}</span>
          <strong>{formatProductQuantity(totals.quantity, productType, locale)}</strong>
        </div>
        <div>
          <span>{t("stock.history.totalAmount")}</span>
          <strong>{numberFormatter.format(totals.amount)} €</strong>
        </div>
      </div>
    </section>
  );
}

function formattedHistoryCell(
  row: StockSalesHistoryRow,
  column: StockSalesHistoryColumnKey,
  dateFormatter: Intl.DateTimeFormat,
  numberFormatter: Intl.NumberFormat,
  productType?: string | null,
  locale: LocaleCode = "es",
) {
  if (column === "occurredAt") return formatOccurredAt(row.occurredAt, dateFormatter);
  if (column === "document") return stockSalesDocumentLabel(row);
  if (column === "status") return row.status;
  if (column === "customer") return row.customerName || row.customerId || "";
  if (column === "quantity") return formatProductQuantity(row.quantity, productType, locale);
  if (column === "unitPrice") return numberFormatter.format(Number(row.unitPrice) || 0);
  if (column === "discount") return `${numberFormatter.format(Number(row.discountPercent) || 0)}%`;
  if (column === "total") return numberFormatter.format(Number(row.lineTotal) || 0);
  if (column === "user") return row.userName || row.userId || "";
  if (column === "store") return row.storeName || row.storeId || "";
  return row.warehouseName || row.warehouseId || "";
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
