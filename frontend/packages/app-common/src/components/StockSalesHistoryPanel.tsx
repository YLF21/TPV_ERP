import { useEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { ErpSelect } from "./ErpSelect";
import { enterNavigationIntent, focusRelativeEnterTarget } from "./keyboardNavigation";

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
  productName: string;
  locale: LocaleCode;
  token?: string;
  onClose: () => void;
  onOpenDocument?: (documentId: string, documentType: string) => void | Promise<void>;
};

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

export function stockSalesDocumentLabel(row: Pick<StockSalesHistoryRow, "documentId" | "documentNumber" | "documentType">) {
  return [row.documentType, row.documentNumber || row.documentId].filter(Boolean).join(" ");
}

export function StockSalesHistoryPanel({
  productId,
  productName,
  locale,
  token,
  onClose,
  onOpenDocument
}: StockSalesHistoryPanelProps) {
  const t = createTranslator(locale);
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
  const historyToolbarRef = useRef<HTMLDivElement | null>(null);
  const applyButtonRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    function handleKeyDown(event: globalThis.KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
      }
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  useEffect(() => {
    let cancelled = false;
    if (!token || !productId) {
      setRows([]);
      setError(t("stock.history.noAccess"));
      return;
    }
    setLoading(true);
    setError("");
    void apiRequest<StockSalesHistoryRow[]>(stockSalesHistoryPath(productId, appliedFrom, appliedTo), { token })
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
  }, [appliedFrom, appliedTo, productId, token]);

  const statuses = Array.from(new Set(rows.map((row) => row.status).filter(Boolean))).sort();
  const visibleRows = filterStockSalesHistoryRows(rows, statusFilter);
  const numberFormatter = new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
  const dateFormatter = new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", {
    dateStyle: "short",
    timeStyle: "short"
  });

  function applyFilters() {
    const from = dateFrom || dateTo;
    const to = dateTo || dateFrom;
    if (!from || !to) {
      return;
    }
    setAppliedFrom(from <= to ? from : to);
    setAppliedTo(from <= to ? to : from);
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
      </div>

      <div className="stock-history-context">
        <strong>{productName}</strong>
        <span>{t("stock.history.resultCount").replace("{count}", String(visibleRows.length))}</span>
      </div>

      {loading && <p className="stock-operation-status" aria-live="polite">{t("stock.history.loading")}</p>}
      {error && <p className="stock-operation-status error" role="alert">{error}</p>}
      {documentNotice && <p className="stock-operation-status" aria-live="polite">{documentNotice}</p>}

      <div className="stock-history-table-scroll">
        <table className="report-table stock-history-table">
          <thead>
            <tr>
              <th>{t("stock.history.occurredAt")}</th>
              <th>{t("stock.history.document")}</th>
              <th>{t("salesReport.column.status")}</th>
              <th>{t("salesReport.column.customer")}</th>
              <th>{t("stock.history.quantity")}</th>
              <th>{t("stock.history.unitPrice")}</th>
              <th>{t("stock.history.discount")}</th>
              <th>{t("salesReport.column.total")}</th>
              <th>{t("salesReport.column.user")}</th>
              <th>{t("stock.history.store")}</th>
              <th>{t("stock.column.warehouse")}</th>
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
                <td>{formatOccurredAt(row.occurredAt, dateFormatter)}</td>
                <td>{stockSalesDocumentLabel(row)}</td>
                <td>{row.status}</td>
                <td>{row.customerName || row.customerId || "-"}</td>
                <td>{numberFormatter.format(Number(row.quantity) || 0)}</td>
                <td>{numberFormatter.format(Number(row.unitPrice) || 0)}</td>
                <td>{numberFormatter.format(Number(row.discountPercent) || 0)}%</td>
                <td>{numberFormatter.format(Number(row.lineTotal) || 0)}</td>
                <td>{row.userName || row.userId || "-"}</td>
                <td>{row.storeName || row.storeId || "-"}</td>
                <td>{row.warehouseName || row.warehouseId || "-"}</td>
              </tr>
            ))}
            {!loading && !error && visibleRows.length === 0 && (
              <tr>
                <td colSpan={11}>{t("stock.history.empty")}</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function formatOccurredAt(value: string, formatter: Intl.DateTimeFormat) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : formatter.format(date);
}
