import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { apiRequest } from "../api/client";
import type { LocaleCode } from "../types";
import { TableSortButton } from "./TableSortButton";
import { nextTableSort, sortTableRows, type TableSort } from "./tableSorting";

export type PurchaseDocument = {
  id: string;
  documentType?: "ALBARAN_ENTRADA" | "FACTURA_ENTRADA";
  status?: string;
  number?: string | null;
  externalNumber?: string | null;
  date?: string;
  supplierId?: string | null;
  warehouseId?: string | null;
  tipo?: "ALBARAN_ENTRADA" | "FACTURA_ENTRADA";
  estado?: string;
  numero?: string | null;
  numeroExterno?: string | null;
  fecha?: string;
  proveedorNombre?: string | null;
  almacenNombre?: string | null;
  lineas?: number;
};

export type GoodsCheckDocumentTypeFilter = "all" | "deliveryNotes" | "invoices";
type GoodsCheckDocumentSortColumn = "type" | "number" | "date" | "supplier" | "warehouse";

export type GoodsCheckItem = {
  productId: string;
  code: string;
  name: string;
  salePrice?: number | string | null;
  expectedQuantity: number | string;
  registeredQuantity: number | string;
  missingQuantity: number | string;
  extraQuantity: number | string;
};

export type GoodsCheckView = {
  id: string;
  documentId: string;
  status: "ABIERTA" | "COMPLETA" | "CON_DIFERENCIAS";
  todos: GoodsCheckItem[];
  faltantes: GoodsCheckItem[];
  registrados: GoodsCheckItem[];
};


export type GoodsCheckLineFilter = "all" | "missing" | "registered";

export function goodsCheckProgress(check: GoodsCheckView | null) {
  const total = check?.todos.length ?? 0;
  const balanced = check?.todos.filter((item) => Number(item.registeredQuantity) === Number(item.expectedQuantity)).length ?? 0;
  return { total, balanced, percent: total ? Math.round((balanced / total) * 100) : 0 };
}

export function filterGoodsCheckItems(check: GoodsCheckView, filter: GoodsCheckLineFilter) {
  if (filter === "missing") {
    return check.todos.filter((item) => Number(item.missingQuantity) > 0 || Number(item.extraQuantity) > 0);
  }
  if (filter === "registered") {
    return check.todos.filter((item) => Number(item.registeredQuantity) > 0);
  }
  return check.todos;
}
type PagedResult<T> = {
  items: T[];
  nextCursor?: string | null;
  hasMore?: boolean;
};

type GoodsCheckPanelProps = {
  locale: LocaleCode;
  token?: string;
  t: (key: string) => string;
  warehouses?: GoodsCheckWarehouseOption[];
  suppliers?: GoodsCheckSupplierOption[];
  separateWorkflow?: boolean;
  onWorkflowViewChange?: (view: "documents" | "check") => void;
};

type GoodsCheckWarehouseOption = {
  id: string;
  name?: string | null;
  nombre?: string | null;
};

type GoodsCheckSupplierOption = {
  id: string;
  supplierId?: string | null;
  legalName?: string | null;
  razonSocial?: string | null;
  tradeName?: string | null;
};

const PAGE_LIMIT = 500;

export function goodsCheckDocumentPath(documentId: string) {
  return `/goods-checks/documents/${encodeURIComponent(documentId)}/import`;
}

export function goodsCheckScanPath(checkId: string) {
  return `/goods-checks/${encodeURIComponent(checkId)}/scan`;
}

export function goodsCheckClosePath(checkId: string) {
  return `/goods-checks/${encodeURIComponent(checkId)}/close`;
}

export function goodsCheckDocumentIsAvailable(document: PurchaseDocument) {
  const type = document.documentType ?? document.tipo;
  const status = document.status ?? (document.estado === "CONFIRMADO" ? "CONFIRMADA" : document.estado);
  const number = document.number ?? document.numero;
  return Boolean(document.id && number)
    && status === "CONFIRMADA"
    && ["ALBARAN_ENTRADA", "FACTURA_ENTRADA"].includes(type ?? "");
}

export function filterGoodsCheckDocuments(
  documents: PurchaseDocument[],
  search: string,
  typeFilter: GoodsCheckDocumentTypeFilter
) {
  const query = search.trim().toLocaleLowerCase();
  return documents.filter((document) => {
    const type = document.documentType ?? document.tipo;
    if (typeFilter === "deliveryNotes" && type !== "ALBARAN_ENTRADA") return false;
    if (typeFilter === "invoices" && type !== "FACTURA_ENTRADA") return false;
    if (!query) return true;
    return [
      document.number ?? document.numero,
      document.externalNumber ?? document.numeroExterno,
      document.proveedorNombre,
      document.supplierId,
      document.almacenNombre,
      document.warehouseId,
      document.date ?? document.fecha
    ].some((value) => String(value ?? "").toLocaleLowerCase().includes(query));
  });
}

export function goodsCheckSupplierLabel(
  document: PurchaseDocument,
  suppliers: GoodsCheckSupplierOption[] = []
) {
  const supplierId = document.supplierId?.trim();
  const supplier = suppliers.find((option) => (
    option.id === supplierId || option.supplierId === supplierId
  ));
  return supplier?.tradeName?.trim()
    || supplier?.legalName?.trim()
    || supplier?.razonSocial?.trim()
    || document.proveedorNombre?.trim()
    || "-";
}

export function goodsCheckWarehouseLabel(
  document: PurchaseDocument,
  warehouses: GoodsCheckWarehouseOption[] = []
) {
  const warehouseId = document.warehouseId?.trim();
  const warehouse = warehouses.find((option) => option.id === warehouseId);
  return warehouse?.name?.trim()
    || warehouse?.nombre?.trim()
    || document.almacenNombre?.trim()
    || "-";
}

async function loadDocumentPages(path: string, token: string) {
  const values: PurchaseDocument[] = [];
  const cursors = new Set<string>();
  let cursor: string | null = null;
  do {
    const params = new URLSearchParams({ limit: String(PAGE_LIMIT) });
    if (cursor) params.set("cursor", cursor);
    const page = await apiRequest<PagedResult<PurchaseDocument>>(
      `${path}${path.includes("?") ? "&" : "?"}${params.toString()}`,
      { token }
    );
    values.push(...page.items);
    const nextCursor: string | null = page.nextCursor?.trim() || null;
    if (!page.hasMore || !nextCursor || cursors.has(nextCursor)) break;
    cursors.add(nextCursor);
    cursor = nextCursor;
  } while (cursor);
  return values;
}

export async function loadGoodsCheckDocuments(token: string) {
  const [invoices, deliveryNotes] = await Promise.all([
    loadDocumentPages("/warehouse-inputs?type=FACTURA_ENTRADA", token),
    loadDocumentPages("/warehouse-inputs?type=ALBARAN_ENTRADA", token)
  ]);
  return [...invoices, ...deliveryNotes]
    .filter(goodsCheckDocumentIsAvailable)
    .sort((left, right) => (right.date ?? right.fecha ?? "").localeCompare(left.date ?? left.fecha ?? "") || (right.number ?? right.numero ?? "").localeCompare(left.number ?? left.numero ?? ""));
}

export function GoodsCheckPanel({ locale, token, t, warehouses = [], suppliers = [], separateWorkflow = false, onWorkflowViewChange }: GoodsCheckPanelProps) {
  const [documents, setDocuments] = useState<PurchaseDocument[]>([]);
  const [selectedDocumentId, setSelectedDocumentId] = useState("");
  const [search, setSearch] = useState("");
  const [typeFilter, setTypeFilter] = useState<GoodsCheckDocumentTypeFilter>("all");
  const [lineFilter, setLineFilter] = useState<GoodsCheckLineFilter>("all");
  const [check, setCheck] = useState<GoodsCheckView | null>(null);
  const [lastProductId, setLastProductId] = useState<string | null>(null);
  const [lastScan, setLastScan] = useState<{ code: string; quantity: number } | null>(null);
  const [code, setCode] = useState("");
  const [quantity, setQuantity] = useState("1");
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const [workflowView, setWorkflowView] = useState<"documents" | "check">("documents");
  const [documentSort, setDocumentSort] = useState<TableSort<GoodsCheckDocumentSortColumn> | null>(null);
  const codeRef = useRef<HTMLInputElement | null>(null);
  const numberFormatter = useMemo(() => new Intl.NumberFormat(
    locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES",
    { maximumFractionDigits: 3 }
  ), [locale]);
  const currencyFormatter = useMemo(() => new Intl.NumberFormat(
    locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES",
    { style: "currency", currency: "EUR" }
  ), [locale]);
  const displayDocuments = useMemo(() => documents.map((document) => ({
    ...document,
    proveedorNombre: goodsCheckSupplierLabel(document, suppliers),
    almacenNombre: goodsCheckWarehouseLabel(document, warehouses)
  })), [documents, suppliers, warehouses]);
  const visibleDocuments = useMemo(() => sortTableRows(
    filterGoodsCheckDocuments(displayDocuments, search, typeFilter),
    documentSort,
    (document, column) => {
      if (column === "type") return t((document.documentType ?? document.tipo) === "ALBARAN_ENTRADA" ? "goodsCheck.deliveryNote" : "goodsCheck.invoice");
      if (column === "number") return document.number ?? document.numero;
      if (column === "date") return new Date(document.date ?? document.fecha ?? "");
      if (column === "supplier") return document.proveedorNombre ?? document.supplierId;
      return document.almacenNombre ?? document.warehouseId;
    },
    locale
  ), [displayDocuments, documentSort, locale, search, t, typeFilter]);
  const selectedDocument = visibleDocuments.find(
    (document) => document.id === selectedDocumentId
  ) ?? null;
  const visibleItems = useMemo(
    () => check ? filterGoodsCheckItems(check, lineFilter) : [],
    [check, lineFilter]);
  const activeDocument = displayDocuments.find((document) => document.id === check?.documentId) ?? selectedDocument;
  const { balanced: balancedLines, percent: progressPercent } = goodsCheckProgress(check);

  function openWorkflowView(nextView: "documents" | "check") {
    setWorkflowView(nextView);
    onWorkflowViewChange?.(nextView);
  }

  useEffect(() => {
    setSelectedDocumentId((current) => (
      visibleDocuments.some((document) => document.id === current)
        ? current
        : visibleDocuments[0]?.id ?? ""
    ));
  }, [visibleDocuments]);

  useEffect(() => {
    let cancelled = false;
    if (!token) {
      setDocuments([]);
      return;
    }
    setLoading(true);
    void loadGoodsCheckDocuments(token)
      .then((values) => {
        if (cancelled) return;
        setDocuments(values);
        setSelectedDocumentId((current) => values.some((value) => value.id === current) ? current : values[0]?.id ?? "");
      })
      .catch((error) => {
        if (!cancelled) setStatus(error instanceof Error ? error.message : t("goodsCheck.loadError"));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [locale, token]);

  async function importDocument(document: PurchaseDocument | null = selectedDocument) {
    if (!token || !document) return;
    setBusy(true);
    setStatus("");
    try {
      const value = await apiRequest<GoodsCheckView>(goodsCheckDocumentPath(document.id), {
        token,
        method: "POST"
      });
      setSelectedDocumentId(document.id);
      setCheck(value);
      setStatus(t("goodsCheck.imported"));
      if (separateWorkflow) openWorkflowView("check");
      window.setTimeout(() => codeRef.current?.focus(), 0);
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("goodsCheck.importError"));
    } finally {
      setBusy(false);
    }
  }

  async function registerProduct(event: FormEvent) {
    event.preventDefault();
    if (!token || !check || check.status !== "ABIERTA" || !code.trim()) return;
    const normalizedQuantity = Number(quantity.replace(",", "."));
    if (!Number.isFinite(normalizedQuantity) || normalizedQuantity === 0) {
      setStatus(t("goodsCheck.quantityError"));
      return;
    }
    setBusy(true);
    setStatus("");
    try {
      const value = await apiRequest<GoodsCheckView>(goodsCheckScanPath(check.id), {
        token,
        method: "POST",
        body: { code: code.trim(), quantity: normalizedQuantity }
      });
      setCheck(value);
      const changed = value.todos.find((item) => {
        const previous = check.todos.find((candidate) => candidate.productId === item.productId);
        return String(previous?.registeredQuantity ?? "0") !== String(item.registeredQuantity);
      });
      setLastProductId(changed?.productId ?? null);
      setLastScan({ code: code.trim(), quantity: normalizedQuantity });
      setLineFilter("all");
      navigator.vibrate?.(60);
      setCode("");
      setQuantity("1");
      setStatus(t("goodsCheck.registered"));
      window.setTimeout(() => codeRef.current?.focus(), 0);
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("goodsCheck.scanError"));
      navigator.vibrate?.([100, 60, 100]);
    } finally {
      setBusy(false);
    }
  }

  async function undoLastScan() {
    if (!token || !check || check.status !== "ABIERTA" || !lastScan || busy) return;
    setBusy(true);
    setStatus("");
    try {
      const value = await apiRequest<GoodsCheckView>(goodsCheckScanPath(check.id), {
        token,
        method: "POST",
        body: { code: lastScan.code, quantity: -lastScan.quantity }
      });
      setCheck(value);
      setLastScan(null);
      setLastProductId(null);
      setStatus(t("goodsCheck.undoComplete"));
      window.setTimeout(() => codeRef.current?.focus(), 0);
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("goodsCheck.undoError"));
    } finally {
      setBusy(false);
    }
  }
  async function closeCheck() {
    if (!token || !check || check.status !== "ABIERTA") return;
    const extraLines = check.todos.filter((item) => Number(item.extraQuantity) > 0).length;
    if ((check.faltantes.length > 0 || extraLines > 0)
      && !window.confirm(t("goodsCheck.closeConfirm")
        .replace("{missing}", String(check.faltantes.length))
        .replace("{extra}", String(extraLines)))) return;
    setBusy(true);
    setStatus("");
    try {
      const value = await apiRequest<GoodsCheckView>(goodsCheckClosePath(check.id), {
        token,
        method: "POST"
      });
      setCheck(value);
      setStatus(value.status === "COMPLETA" ? t("goodsCheck.complete") : t("goodsCheck.differences"));
      if (separateWorkflow) openWorkflowView("documents");
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("goodsCheck.closeError"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={`goods-check-panel${separateWorkflow ? " goods-check-panel-separate" : ""}`}>
      {(!separateWorkflow || workflowView === "documents") && <div className="goods-check-documents">
        <header className="goods-check-documents-header">
          <div className="stock-history-toolbar goods-check-toolbar">
            <label className="goods-check-search">
              <span>{t("salesReport.search")}</span>
              <input
                type="search"
                value={search}
                placeholder={t("goodsCheck.searchPlaceholder")}
                onChange={(event) => setSearch(event.target.value)}
              />
            </label>
            <button type="button" disabled={!selectedDocument || busy} onClick={() => void importDocument()}>
              {busy ? t("common.loading") : t("goodsCheck.import")}
            </button>
          </div>
          <div
            className="goods-check-type-filter"
            role="group"
            aria-label={t("goodsCheck.filter.type")}
          >
            {([
              ["all", "goodsCheck.filter.all"],
              ["deliveryNotes", "goodsCheck.filter.deliveryNotes"],
              ["invoices", "goodsCheck.filter.invoices"]
            ] as const).map(([value, label]) => (
              <button
                key={value}
                type="button"
                className={typeFilter === value ? "selected" : ""}
                aria-pressed={typeFilter === value}
                onClick={() => setTypeFilter(value)}
              >
                {t(label)}
              </button>
            ))}
          </div>
          <div className="goods-check-documents-summary">
            <div>
              <strong>{t("goodsCheck.availableDocuments")}</strong>
              <small>{t("goodsCheck.purchaseDocuments")}</small>
            </div>
            <span className="goods-check-document-count">
              <strong>{visibleDocuments.length}</strong>
              <small>{t("goodsCheck.documents")}</small>
            </span>
          </div>
          {separateWorkflow && check?.status === "ABIERTA" && (
            <button type="button" className="goods-check-resume" onClick={() => openWorkflowView("check")}>
              <span>{t("goodsCheck.active")}</span>
              <strong>{activeDocument?.number ?? activeDocument?.numero ?? ""}</strong>
              <small>{t("goodsCheck.continue")}</small>
            </button>
          )}
        </header>
        <div className="stock-history-table-scroll goods-check-document-list">
          {loading ? (
            <div className="goods-check-empty" role="status" aria-live="polite">
              <strong>{t("common.loading")}</strong>
            </div>
          ) : visibleDocuments.length > 0 ? (
            <table className="report-table">
            <colgroup>
              <col className="goods-check-col-type" />
              <col className="goods-check-col-number" />
              <col className="goods-check-col-date" />
              <col className="goods-check-col-supplier" />
              <col className="goods-check-col-warehouse" />
            </colgroup>
            <thead>
              <tr>
                {([
                  ["type", t("goodsCheck.column.type")],
                  ["number", t("goodsCheck.column.number")],
                  ["date", t("salesReport.column.date")],
                  ["supplier", t("warehouseDocument.supplier")],
                  ["warehouse", t("stock.column.warehouse")]
                ] as const).map(([column, label]) => (
                  <th aria-sort={documentSort?.column === column ? documentSort.direction === "asc" ? "ascending" : "descending" : "none"} key={column}>
                    <TableSortButton
                      direction={documentSort?.column === column ? documentSort.direction : null}
                      label={`${t("party.sortBy")} ${label}`}
                      onSort={() => setDocumentSort((current) => nextTableSort(current, column))}
                    >
                      {label}
                    </TableSortButton>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {visibleDocuments.map((document) => (
                <tr
                  key={document.id}
                  className={selectedDocumentId === document.id ? "selected" : ""}
                  tabIndex={0}
                  onClick={() => setSelectedDocumentId(document.id)}
                  onDoubleClick={() => {
                    void importDocument(document);
                  }}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") {
                      event.preventDefault();
                      setSelectedDocumentId(document.id);
                    }
                  }}
                >
                  <td data-label={t("goodsCheck.column.type")}>{t((document.documentType ?? document.tipo) === "ALBARAN_ENTRADA" ? "goodsCheck.deliveryNote" : "goodsCheck.invoice")}</td>
                  <td data-label={t("goodsCheck.column.number")}>{document.number ?? document.numero}</td>
                  <td data-label={t("salesReport.column.date")}>{document.date ?? document.fecha}</td>
                  <td data-label={t("warehouseDocument.supplier")} title={document.supplierId ?? undefined}>{document.proveedorNombre ?? document.supplierId ?? "-"}</td>
                  <td data-label={t("stock.column.warehouse")} title={document.warehouseId ?? undefined}>{document.almacenNombre ?? document.warehouseId ?? "-"}</td>
                </tr>
              ))}
            </tbody>
            </table>
          ) : (
            <div className="goods-check-empty">
              <strong>{t("goodsCheck.noDocuments")}</strong>
              <span>{t("goodsCheck.searchPlaceholder")}</span>
            </div>
          )}
        </div>
        {separateWorkflow && status && <p className="stock-operation-status goods-check-list-status" aria-live="polite">{status}</p>}
      </div>}

      {(!separateWorkflow || workflowView === "check") && <div className="goods-check-workspace">
        <div className="stock-history-context goods-check-active-heading">
          {separateWorkflow && (
            <button type="button" className="goods-check-back" disabled={busy} onClick={() => openWorkflowView("documents")}>
              ← {t("common.back")}
            </button>
          )}
          <div>
            <small>{check ? t("goodsCheck.active") : t("goodsCheck.noActive")}</small>
            {check && <strong>{activeDocument?.number ?? activeDocument?.numero ?? ""}</strong>}
          </div>
          {check && (
            <div className={`goods-check-status status-${check.status.toLocaleLowerCase()}`}>
              <small>{t("salesReport.filter.status")}</small>
              <strong>{t(`goodsCheck.status.${check.status}`)}</strong>
            </div>
          )}
        </div>
        {check ? (
          <>
            <form className="goods-check-scan-form" onSubmit={registerProduct}>
              <label>
                <span>{t("goodsCheck.productCode")}</span>
                <input ref={codeRef} value={code} disabled={check.status !== "ABIERTA" || busy} onChange={(event) => setCode(event.target.value)} />
              </label>
              <div className="goods-check-register-control">
                <label>
                  <span>{t("goodsCheck.quantityToRegister")}</span>
                  <input inputMode="decimal" value={quantity} disabled={check.status !== "ABIERTA" || busy} onChange={(event) => setQuantity(event.target.value)} />
                </label>
                <button type="submit" disabled={!code.trim() || check.status !== "ABIERTA" || busy}>{t("goodsCheck.register")}</button>
              </div>
              <button type="button" className="secondary" disabled={check.status !== "ABIERTA" || busy} onClick={() => void closeCheck()}>{t("goodsCheck.close")}</button>
              <button type="button" className="secondary goods-check-undo" disabled={check.status !== "ABIERTA" || busy || !lastScan} onClick={() => void undoLastScan()}>{t("goodsCheck.undoLast")}</button>
            </form>
            <section className="goods-check-guided-progress" aria-label={t("goodsCheck.progress")}>
              <div><strong>{progressPercent}%</strong><span>{t("goodsCheck.balancedProgress").replace("{balanced}", String(balancedLines)).replace("{total}", String(check.todos.length))}</span></div>
              <progress max="100" value={progressPercent}>{progressPercent}%</progress>
            </section>
            <div className="goods-check-progress" role="group" aria-label={t("goodsCheck.progress")}>
              <button type="button" className={lineFilter === "all" ? "selected" : ""} onClick={() => setLineFilter("all")}>
                <span>{t("goodsCheck.filter.linesAll")}</span><strong>{check.todos.length}</strong>
              </button>
              <button type="button" className={lineFilter === "missing" ? "selected" : ""} onClick={() => setLineFilter("missing")}>
                <span>{t("goodsCheck.filter.linesDifferences")}</span>
                <strong>{check.todos.filter((item) => Number(item.missingQuantity) > 0 || Number(item.extraQuantity) > 0).length}</strong>
              </button>
              <button type="button" className={lineFilter === "registered" ? "selected" : ""} onClick={() => setLineFilter("registered")}>
                <span>{t("goodsCheck.filter.linesRegistered")}</span><strong>{check.registrados.length}</strong>
              </button>
              <span className="goods-check-extra-summary">
                <small>{t("goodsCheck.extraLines")}</small>
                <strong>{check.todos.filter((item) => Number(item.extraQuantity) > 0).length}</strong>
              </span>
            </div>
            <div className="stock-history-table-scroll goods-check-lines">
              <table className="report-table">
                <thead>
                  <tr>
                    <th>{t("goodsCheck.column.code")}</th>
                    <th>{t("goodsCheck.column.product")}</th>
                    <th>{t("goodsCheck.column.price")}</th>
                    <th>{t("goodsCheck.column.expected")}</th>
                    <th>{t("goodsCheck.column.registered")}</th>
                    <th>{t("goodsCheck.column.missing")}</th>
                    <th>{t("goodsCheck.column.extra")}</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleItems.map((item) => (
                    <tr key={item.productId} className={`${Number(item.missingQuantity) > 0 || Number(item.extraQuantity) > 0 ? "goods-check-difference" : ""}${item.productId === lastProductId ? " goods-check-last-scan" : ""}`}>
                      <td data-label={t("goodsCheck.column.code")}>{item.code}</td>
                      <td data-label={t("goodsCheck.column.product")}>{item.name}</td>
                      <td data-label={t("goodsCheck.column.price")}>{currencyFormatter.format(Number(item.salePrice ?? 0))}</td>
                      <td data-label={t("goodsCheck.column.expected")}>{numberFormatter.format(Number(item.expectedQuantity))}</td>
                      <td data-label={t("goodsCheck.column.registered")}>{numberFormatter.format(Number(item.registeredQuantity))}</td>
                      <td data-label={t("goodsCheck.column.missing")}>{numberFormatter.format(Number(item.missingQuantity))}</td>
                      <td data-label={t("goodsCheck.column.extra")}>{numberFormatter.format(Number(item.extraQuantity))}</td>
                    </tr>
                  ))}
                  {visibleItems.length === 0 && <tr><td colSpan={7}>{t("goodsCheck.filter.empty")}</td></tr>}
                </tbody>
              </table>
            </div>
          </>
        ) : (
          <div className="goods-check-empty goods-check-workspace-empty">
            <strong>{t("goodsCheck.noActive")}</strong>
            <span>{t("goodsCheck.selectDocument")}</span>
          </div>
        )}
        {status && <p className="stock-operation-status" aria-live="polite">{status}</p>}
      </div>}
    </section>
  );
}

export default GoodsCheckPanel;
