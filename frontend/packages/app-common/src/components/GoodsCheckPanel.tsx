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

type GoodsCheckItem = {
  productId: string;
  code: string;
  name: string;
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

export function GoodsCheckPanel({ locale, token, t, warehouses = [], suppliers = [] }: GoodsCheckPanelProps) {
  const [documents, setDocuments] = useState<PurchaseDocument[]>([]);
  const [selectedDocumentId, setSelectedDocumentId] = useState("");
  const [search, setSearch] = useState("");
  const [typeFilter, setTypeFilter] = useState<GoodsCheckDocumentTypeFilter>("all");
  const [check, setCheck] = useState<GoodsCheckView | null>(null);
  const [code, setCode] = useState("");
  const [quantity, setQuantity] = useState("1");
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const [documentSort, setDocumentSort] = useState<TableSort<GoodsCheckDocumentSortColumn> | null>(null);
  const codeRef = useRef<HTMLInputElement | null>(null);
  const numberFormatter = useMemo(() => new Intl.NumberFormat(
    locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES",
    { maximumFractionDigits: 3 }
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
      setCode("");
      setQuantity("1");
      setStatus(t("goodsCheck.registered"));
      window.setTimeout(() => codeRef.current?.focus(), 0);
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("goodsCheck.scanError"));
    } finally {
      setBusy(false);
    }
  }

  async function closeCheck() {
    if (!token || !check || check.status !== "ABIERTA") return;
    setBusy(true);
    setStatus("");
    try {
      const value = await apiRequest<GoodsCheckView>(goodsCheckClosePath(check.id), {
        token,
        method: "POST"
      });
      setCheck(value);
      setStatus(value.status === "COMPLETA" ? t("goodsCheck.complete") : t("goodsCheck.differences"));
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("goodsCheck.closeError"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="goods-check-panel">
      <div className="goods-check-documents">
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
                  <td>{t((document.documentType ?? document.tipo) === "ALBARAN_ENTRADA" ? "goodsCheck.deliveryNote" : "goodsCheck.invoice")}</td>
                  <td>{document.number ?? document.numero}</td>
                  <td>{document.date ?? document.fecha}</td>
                  <td title={document.supplierId ?? undefined}>{document.proveedorNombre ?? document.supplierId ?? "-"}</td>
                  <td title={document.warehouseId ?? undefined}>{document.almacenNombre ?? document.warehouseId ?? "-"}</td>
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
      </div>

      <div className="goods-check-workspace">
        <div className="stock-history-context goods-check-active-heading">
          <div>
            <small>{check ? t("goodsCheck.active") : t("goodsCheck.noActive")}</small>
            {check && <strong>{selectedDocument?.numero ?? ""}</strong>}
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
            </form>
            <div className="stock-history-table-scroll goods-check-lines">
              <table className="report-table">
                <thead>
                  <tr>
                    <th>{t("goodsCheck.column.code")}</th>
                    <th>{t("goodsCheck.column.product")}</th>
                    <th>{t("goodsCheck.column.expected")}</th>
                    <th>{t("goodsCheck.column.registered")}</th>
                    <th>{t("goodsCheck.column.missing")}</th>
                    <th>{t("goodsCheck.column.extra")}</th>
                  </tr>
                </thead>
                <tbody>
                  {check.todos.map((item) => (
                    <tr key={item.productId} className={Number(item.missingQuantity) > 0 || Number(item.extraQuantity) > 0 ? "goods-check-difference" : ""}>
                      <td>{item.code}</td>
                      <td>{item.name}</td>
                      <td>{numberFormatter.format(Number(item.expectedQuantity))}</td>
                      <td>{numberFormatter.format(Number(item.registeredQuantity))}</td>
                      <td>{numberFormatter.format(Number(item.missingQuantity))}</td>
                      <td>{numberFormatter.format(Number(item.extraQuantity))}</td>
                    </tr>
                  ))}
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
      </div>
    </section>
  );
}

export default GoodsCheckPanel;
