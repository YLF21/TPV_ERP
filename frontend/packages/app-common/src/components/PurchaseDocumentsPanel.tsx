import { useEffect, useMemo, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import type { LocaleCode } from "../types";
import { ErpSelect } from "./ErpSelect";

export type PurchaseDocumentQueryMode = "deliveryNote" | "invoice";

export type PurchaseDocumentQueryLine = {
  id: string;
  productId?: string | null;
  lineType: string;
  position: number;
  code?: string | null;
  name?: string | null;
  quantity: number | string;
  unitPrice: number | string;
  discount: number | string;
  taxRegime?: string | null;
  taxPercentage: number | string;
  base: number | string;
  tax: number | string;
  total: number | string;
};

export type PurchaseDocumentQueryView = {
  id: string;
  type: "ALBARAN_COMPRA" | "FACTURA_COMPRA";
  status: string;
  number?: string | null;
  externalNumber?: string | null;
  date: string;
  supplierId?: string | null;
  supplierName?: string | null;
  warehouseId?: string | null;
  warehouseName?: string | null;
  base: number | string;
  tax: number | string;
  total: number | string;
  paid: number | string;
  pending: number | string;
  lines: PurchaseDocumentQueryLine[];
};

type Props = {
  mode: PurchaseDocumentQueryMode;
  token?: string;
  locale: LocaleCode;
  t: (key: string) => string;
  request?: typeof apiRequest;
};

export function purchaseDocumentsPath(mode: PurchaseDocumentQueryMode) {
  const type = mode === "invoice" ? "FACTURA_COMPRA" : "ALBARAN_COMPRA";
  return `/purchase-documents?type=${type}`;
}

export function filterPurchaseDocuments(
  documents: PurchaseDocumentQueryView[],
  query: string,
  status: string
) {
  const normalizedQuery = normalize(query);
  return documents.filter((document) => {
    if (status && document.status !== status) return false;
    if (!normalizedQuery) return true;
    return normalize([
      document.number,
      document.externalNumber,
      document.date,
      document.supplierName,
      document.warehouseName,
      document.status,
      document.total,
      document.pending
    ].join(" ")).includes(normalizedQuery);
  });
}

export function PurchaseDocumentsPanel({
  mode,
  token,
  locale,
  t,
  request = apiRequest
}: Props) {
  const [documents, setDocuments] = useState<PurchaseDocumentQueryView[]>([]);
  const [loading, setLoading] = useState(Boolean(token));
  const [error, setError] = useState("");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const [selectedId, setSelectedId] = useState("");
  const [openDocument, setOpenDocument] = useState<PurchaseDocumentQueryView | null>(null);
  const selectedRowRef = useRef<HTMLTableRowElement>(null);
  const labels = purchaseDocumentLabels(t, mode);
  const visibleDocuments = useMemo(
    () => filterPurchaseDocuments(documents, query, status),
    [documents, query, status]
  );
  const statuses = useMemo(
    () => Array.from(new Set(documents.map((document) => document.status))).sort(),
    [documents]
  );
  const selected = documents.find((document) => document.id === selectedId) ?? null;
  const numberFormatter = useMemo(
    () => new Intl.NumberFormat(localeName(locale), { maximumFractionDigits: 3 }),
    [locale]
  );
  const currencyFormatter = useMemo(
    () => new Intl.NumberFormat(localeName(locale), { style: "currency", currency: "EUR" }),
    [locale]
  );
  const dateFormatter = useMemo(
    () => new Intl.DateTimeFormat(localeName(locale), { dateStyle: "short", timeZone: "UTC" }),
    [locale]
  );

  useEffect(() => {
    setQuery("");
    setStatus("");
    setSelectedId("");
    setOpenDocument(null);
  }, [mode]);

  useEffect(() => {
    let cancelled = false;
    if (!token) {
      setDocuments([]);
      setLoading(false);
      setError(labels.noAccess);
      return;
    }
    setLoading(true);
    setError("");
    void request<PurchaseDocumentQueryView[]>(purchaseDocumentsPath(mode), { token })
      .then((result) => {
        if (!cancelled) setDocuments(Array.isArray(result) ? result : []);
      })
      .catch(() => {
        if (!cancelled) {
          setDocuments([]);
          setError(labels.loadError);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [labels.loadError, labels.noAccess, mode, request, token]);

  useEffect(() => {
    if (!openDocument) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      selectedRowRef.current?.focus();
      setOpenDocument(null);
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [openDocument]);

  function consult(document: PurchaseDocumentQueryView) {
    setSelectedId(document.id);
    setOpenDocument(document);
  }

  function closeConsultation() {
    selectedRowRef.current?.focus();
    setOpenDocument(null);
  }

  return (
    <section className="stock-sales-history-panel purchase-documents-panel" aria-label={labels.title}>
      <div className="stock-history-toolbar">
        <label>
          <span>{labels.search}</span>
          <input
            type="search"
            value={query}
            placeholder={labels.searchPlaceholder}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
        <div className="stock-history-filter-field">
          <span>{labels.status}</span>
          <ErpSelect
            aria-label={labels.status}
            value={status}
            options={[
              { value: "", label: labels.all },
              ...statuses.map((value) => ({ value, label: statusLabel(value, t) }))
            ]}
            onChange={setStatus}
          />
        </div>
        <div className="filter-actions filter-wide warehouse-document-actions">
          <button type="button" disabled={!selected} onClick={() => selected && consult(selected)}>
            {labels.view}
          </button>
        </div>
      </div>

      <div className="stock-history-context">
        <strong>{labels.title}</strong>
        <span>{labels.resultCount.replace("{count}", String(visibleDocuments.length))}</span>
      </div>

      {loading && <p className="stock-operation-status" aria-live="polite">{labels.loading}</p>}
      {error && <p className="stock-operation-status error" role="alert">{error}</p>}

      <div className="stock-history-table-scroll">
        <table className="report-table warehouse-document-table purchase-document-query-table">
          <thead>
            <tr>
              <th>{labels.number}</th>
              <th>{labels.externalNumber}</th>
              <th>{labels.date}</th>
              <th>{labels.supplier}</th>
              <th>{labels.warehouse}</th>
              <th>{labels.status}</th>
              <th>{labels.lines}</th>
              <th>{labels.total}</th>
              <th>{labels.pending}</th>
            </tr>
          </thead>
          <tbody>
            {visibleDocuments.map((document) => (
              <tr
                key={document.id}
                ref={selectedId === document.id ? selectedRowRef : undefined}
                className={selectedId === document.id ? "selected" : ""}
                aria-selected={selectedId === document.id}
                tabIndex={0}
                onClick={() => setSelectedId(document.id)}
                onFocus={() => setSelectedId(document.id)}
                onDoubleClick={() => consult(document)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    event.preventDefault();
                    consult(document);
                  }
                }}
              >
                <td>{document.number || labels.unnumbered}</td>
                <td>{document.externalNumber || "-"}</td>
                <td>{formatDate(document.date, dateFormatter)}</td>
                <td>{document.supplierName || "-"}</td>
                <td>{document.warehouseName || "-"}</td>
                <td>{statusLabel(document.status, t)}</td>
                <td>{document.lines.length}</td>
                <td>{currencyFormatter.format(number(document.total))}</td>
                <td>{currencyFormatter.format(number(document.pending))}</td>
              </tr>
            ))}
            {!loading && !error && visibleDocuments.length === 0 && (
              <tr><td colSpan={9}>{labels.empty}</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {openDocument && (
        <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="purchase-query-title">
          <section className="filter-dialog purchase-query-dialog">
            <header className="filter-header">
              <div>
                <h2 id="purchase-query-title">{labels.detailTitle}</h2>
                <span>{openDocument.number || labels.unnumbered}</span>
              </div>
              <button type="button" onClick={closeConsultation}>{labels.close}</button>
            </header>

            <dl className="purchase-query-summary">
              <div><dt>{labels.externalNumber}</dt><dd>{openDocument.externalNumber || "-"}</dd></div>
              <div><dt>{labels.date}</dt><dd>{formatDate(openDocument.date, dateFormatter)}</dd></div>
              <div><dt>{labels.supplier}</dt><dd>{openDocument.supplierName || "-"}</dd></div>
              <div><dt>{labels.warehouse}</dt><dd>{openDocument.warehouseName || "-"}</dd></div>
              <div><dt>{labels.status}</dt><dd>{statusLabel(openDocument.status, t)}</dd></div>
              <div><dt>{labels.total}</dt><dd>{currencyFormatter.format(number(openDocument.total))}</dd></div>
            </dl>

            <div className="stock-history-table-scroll purchase-query-lines">
              <table className="report-table">
                <thead>
                  <tr>
                    <th>{labels.code}</th>
                    <th>{labels.product}</th>
                    <th>{labels.quantity}</th>
                    <th>{labels.unitPrice}</th>
                    <th>{labels.discount}</th>
                    <th>{labels.tax}</th>
                    <th>{labels.total}</th>
                  </tr>
                </thead>
                <tbody>
                  {openDocument.lines.map((line) => (
                    <tr key={line.id}>
                      <td>{line.code || "-"}</td>
                      <td>{line.name || "-"}</td>
                      <td>{numberFormatter.format(number(line.quantity))}</td>
                      <td>{currencyFormatter.format(number(line.unitPrice))}</td>
                      <td>{numberFormatter.format(number(line.discount))}%</td>
                      <td>{numberFormatter.format(number(line.taxPercentage))}%</td>
                      <td>{currencyFormatter.format(number(line.total))}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <footer className="purchase-query-totals">
              <span>{labels.base}: <strong>{currencyFormatter.format(number(openDocument.base))}</strong></span>
              <span>{labels.tax}: <strong>{currencyFormatter.format(number(openDocument.tax))}</strong></span>
              <span>{labels.paid}: <strong>{currencyFormatter.format(number(openDocument.paid))}</strong></span>
              <span>{labels.pending}: <strong>{currencyFormatter.format(number(openDocument.pending))}</strong></span>
              <span>{labels.total}: <strong>{currencyFormatter.format(number(openDocument.total))}</strong></span>
            </footer>
          </section>
        </div>
      )}
    </section>
  );
}

function purchaseDocumentLabels(t: (key: string) => string, mode: PurchaseDocumentQueryMode) {
  const text = (key: string, fallback: string) => {
    const translated = t(key);
    return translated === key ? fallback : translated;
  };
  const titleKey = mode === "invoice"
    ? "warehouseScreen.purchaseInvoices"
    : "warehouseScreen.purchaseDeliveryNotes";
  return {
    title: text(titleKey, mode === "invoice" ? "Facturas de compra" : "Albaranes de compra"),
    detailTitle: text("warehouseScreen.purchaseDocumentDetail", "Consulta de documento de compra"),
    search: text("salesReport.search", "Buscar"),
    searchPlaceholder: text("warehouseScreen.purchaseSearchPlaceholder", "Buscar por número, proveedor o almacén"),
    status: text("salesReport.filter.status", "Estado"),
    all: text("salesReport.filter.all", "Todos"),
    view: text("warehouseDocument.view", "Consultar documento"),
    resultCount: text("warehouseOperations.resultCount", "{count} documentos"),
    loading: text("common.loading", "Cargando..."),
    empty: text("warehouseScreen.purchaseEmpty", "Sin documentos para la búsqueda y estado seleccionados"),
    loadError: text("warehouseScreen.purchaseLoadError", "No se pudieron cargar los documentos de compra"),
    noAccess: text("warehouseScreen.noAccess", "Sin acceso a la gestión de almacén"),
    number: text("warehouseOperations.column.number", "Número"),
    externalNumber: text("purchaseDocument.externalNumber", "Número proveedor"),
    date: text("salesReport.column.date", "Fecha"),
    supplier: text("warehouseDocument.supplier", "Proveedor"),
    warehouse: text("stock.column.warehouse", "Almacén"),
    lines: text("warehouseOperations.column.lines", "Líneas"),
    base: text("salesReport.column.base", "Base"),
    tax: text("salesReport.column.tax", "Impuestos"),
    paid: text("salesReport.column.paid", "Pagado"),
    pending: text("salesReport.column.pending", "Pendiente"),
    total: text("salesReport.column.total", "Total"),
    code: text("warehouseDocument.column.code", "Código"),
    product: text("warehouseDocument.column.name", "Producto"),
    quantity: text("warehouseDocument.quantity", "Cantidad"),
    unitPrice: text("purchaseDocument.unitPrice", "Precio unitario"),
    discount: text("purchaseDocument.discount", "Descuento"),
    close: text("common.close", "Cerrar"),
    unnumbered: text("warehouseOperations.unnumbered", "Sin número")
  };
}

function normalize(value: unknown) {
  return String(value ?? "")
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .trim()
    .toLocaleLowerCase();
}

function number(value: unknown) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function localeName(locale: LocaleCode) {
  return locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES";
}

function formatDate(value: string, formatter: Intl.DateTimeFormat) {
  const date = new Date(`${value.slice(0, 10)}T00:00:00Z`);
  return Number.isNaN(date.getTime()) ? value : formatter.format(date);
}

function statusLabel(status: string, t: (key: string) => string) {
  const key = `warehouseDocument.status.${status.trim().toLocaleUpperCase()}`;
  const translated = t(key);
  return translated === key ? status : translated;
}

export default PurchaseDocumentsPanel;
