import { useEffect, useLayoutEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { DownloadSimple } from "@phosphor-icons/react";
import { apiRequest } from "../api/client";
import type { AppKind, LocaleCode } from "../types";
import { ErpFilterChips, type ErpFilterChip } from "./ErpFilterChips";
import { ErpListFilterPanel } from "./ErpListFilterPanel";
import { ErpListSelect } from "./ErpListSelect";
import { listFilterChips, matchesListFilter } from "./listFilterValues";
import { TableSortButton } from "./TableSortButton";
import { nextTableSort, sortTableRows, type TableSort } from "./tableSorting";
import { WarehouseSearchFrame } from "./WarehouseSearchFrame";
import { useTableInitialFocus } from "./useTableInitialFocus";
import { useTableArrowRepeat } from "./useTableArrowRepeat";
import { ensureTableRowVisible } from "./ensureTableRowVisible";
import "./WarehouseClassicTables.css";
import "./ErpSearchField.css";

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


export type GoodsCheckLineFilter = "all" | "missing" | "registered" | "complete";

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
  if (filter === "complete") {
    return check.todos.filter((item) => Number(item.registeredQuantity) === Number(item.expectedQuantity));
  }
  return check.todos;
}
type PagedResult<T> = {
  items: T[];
  nextCursor?: string | null;
  hasMore?: boolean;
};

type GoodsCheckPanelProps = {
  app?: AppKind;
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
  typeFilter: GoodsCheckDocumentTypeFilter | readonly string[],
  parties: {
    supplierId?: string; supplierCode?: string | null; warehouseId?: string;
    supplierIds?: readonly string[]; warehouseIds?: readonly string[];
  } = {}
) {
  const query = search.trim().toLocaleLowerCase();
  return documents.filter((document) => {
    const type = document.documentType ?? document.tipo;
    const selectedTypes = typeof typeFilter === "string" ? (typeFilter === "all" ? [] : [typeFilter]) : typeFilter;
    const category = type === "ALBARAN_ENTRADA" ? "deliveryNotes" : type === "FACTURA_ENTRADA" ? "invoices" : undefined;
    if (!matchesListFilter(selectedTypes, category)
      || !matchesListFilter(parties.supplierIds, document.supplierId)
      || !matchesListFilter(parties.warehouseIds, document.warehouseId)) return false;
    if (parties.supplierId && document.supplierId !== parties.supplierId
      && (!parties.supplierCode || document.supplierId !== parties.supplierCode)) return false;
    if (parties.warehouseId && document.warehouseId !== parties.warehouseId) return false;
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

export function GoodsCheckPanel({ app = "venta", locale, token, t, warehouses = [], suppliers = [], separateWorkflow = false, onWorkflowViewChange }: GoodsCheckPanelProps) {
  const [documents, setDocuments] = useState<PurchaseDocument[]>([]);
  const [selectedDocumentId, setSelectedDocumentId] = useState("");
  const [search, setSearch] = useState("");
  const [typeFilter, setTypeFilter] = useState<string[]>([]);
  const [supplierFilter, setSupplierFilter] = useState<string[]>([]);
  const [warehouseFilter, setWarehouseFilter] = useState<string[]>([]);
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
  const quantityRef = useRef<HTMLInputElement | null>(null);
  const focusCodeAfterRender = useRef(false);
  const searchRef = useRef<HTMLInputElement | null>(null);
  const documentListRef = useRef<HTMLDivElement>(null);
  const allLinesRef = useRef<HTMLButtonElement | null>(null);
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
  const supplierFilterOptions = useMemo(() => {
    const options = suppliers.map(item => ({ value: item.id, code: item.supplierId,
      label: [item.supplierId, item.tradeName || item.legalName || item.razonSocial].filter(Boolean).join(" · ") || item.id }));
    for (const item of displayDocuments) {
      if (item.supplierId && !options.some(option => option.value === item.supplierId || option.code === item.supplierId)) {
        options.push({ value: item.supplierId, code: undefined, label: item.proveedorNombre || item.supplierId });
      }
    }
    return options;
  }, [displayDocuments, suppliers]);
  const warehouseFilterOptions = useMemo(() => {
    const options = warehouses.map(item => ({ value: item.id, label: item.name || item.nombre || item.id }));
    for (const item of displayDocuments) {
      if (item.warehouseId && !options.some(option => option.value === item.warehouseId)) {
        options.push({ value: item.warehouseId, label: item.almacenNombre || item.warehouseId });
      }
    }
    return options;
  }, [displayDocuments, warehouses]);
  const visibleDocuments = useMemo(() => sortTableRows(
    filterGoodsCheckDocuments(displayDocuments, search, typeFilter, app !== "pda" ? {
      supplierIds: supplierFilter.flatMap(id => {
        const code = supplierFilterOptions.find(option => option.value === id)?.code;
        return code ? [id, code] : [id];
      }),
      warehouseIds: warehouseFilter
    } : undefined),
    documentSort,
    (document, column) => {
      if (column === "type") return t((document.documentType ?? document.tipo) === "ALBARAN_ENTRADA" ? "goodsCheck.deliveryNote" : "goodsCheck.invoice");
      if (column === "number") return document.number ?? document.numero;
      if (column === "date") return new Date(document.date ?? document.fecha ?? "");
      if (column === "supplier") return document.proveedorNombre ?? document.supplierId;
      return document.almacenNombre ?? document.warehouseId;
    },
    locale
  ), [app, displayDocuments, documentSort, locale, search, t, typeFilter, supplierFilter, supplierFilterOptions, warehouseFilter]);
  const selectedDocument = visibleDocuments.find(
    (document) => document.id === selectedDocumentId
  ) ?? null;
  useTableInitialFocus(documentListRef, "tbody tr[data-document-id]", {
    enabled: app !== "pda" && (!separateWorkflow || workflowView === "documents"),
    ready: !loading && visibleDocuments.length > 0,
    scopeKey: `${token ?? ""}:${workflowView}`,
    onSelect: (row) => setSelectedDocumentId(row.dataset.documentId ?? ""),
  });
  useLayoutEffect(() => {
    const list = documentListRef.current;
    const row = Array.from(list?.querySelectorAll<HTMLElement>("tbody tr[data-document-id]") ?? [])
      .find((candidate) => candidate.dataset.documentId === selectedDocumentId);
    if (!list || !row) return;
    const focused = document.activeElement;
    if (focused instanceof HTMLElement && list.contains(focused)
      && focused.closest("tbody tr[data-document-id]") && focused !== row) row.focus({ preventScroll: true });
    ensureTableRowVisible(list, row);
  }, [selectedDocumentId]);
  const repeatDocumentArrow = useTableArrowRepeat((direction) => {
    const list = documentListRef.current;
    const focused = document.activeElement;
    const row = focused instanceof HTMLElement && list?.contains(focused)
      ? focused.closest<HTMLElement>("tbody tr[data-document-id]") : null;
    if (!row || loading || busy || visibleDocuments.length < 2) return false;
    const index = visibleDocuments.findIndex((candidate) => candidate.id === row.dataset.documentId);
    const nextIndex = Math.max(0, Math.min(visibleDocuments.length - 1, index + (direction === "ArrowDown" ? 1 : -1)));
    if (index < 0 || nextIndex === index) return false;
    setSelectedDocumentId(visibleDocuments[nextIndex].id);
    return true;
  }, !loading && !busy && app !== "pda" && (!separateWorkflow || workflowView === "documents"));
  const visibleItems = useMemo(
    () => check ? filterGoodsCheckItems(check, lineFilter) : [],
    [check, lineFilter]);
  const activeDocument = displayDocuments.find((document) => document.id === check?.documentId) ?? selectedDocument;
  const { balanced: balancedLines, percent: progressPercent } = goodsCheckProgress(check);
  const completedLineFilter = app === "gestion" ? "complete" : "registered";
  const documentFilterChips: ErpFilterChip[] = [];
  if (search.trim()) documentFilterChips.push({
    key: "search", label: t("salesReport.search"), value: search.trim(), onRemove: () => setSearch("")
  });
  documentFilterChips.push(...listFilterChips("type", t("goodsCheck.column.type"), typeFilter, setTypeFilter,
    value => t(`goodsCheck.filter.${value}`)));
  if (app !== "pda") {
    documentFilterChips.push(...listFilterChips("supplier", t("stock.column.supplier"), supplierFilter, setSupplierFilter,
      value => supplierFilterOptions.find(item => item.value === value)?.label || value));
    documentFilterChips.push(...listFilterChips("warehouse", t("stock.column.warehouse"), warehouseFilter, setWarehouseFilter,
      value => warehouseFilterOptions.find(item => item.value === value)?.label || value));
  }

  function openWorkflowView(nextView: "documents" | "check") {
    setWorkflowView(nextView);
    onWorkflowViewChange?.(nextView);
  }

  useEffect(() => {
    if (busy || !focusCodeAfterRender.current) return;
    // Restore focus after React has enabled the input again.
    focusCodeAfterRender.current = false;
    codeRef.current?.focus();
  }, [busy, check, workflowView]);

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
      focusCodeAfterRender.current = true;
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("goodsCheck.importError"));
    } finally {
      setBusy(false);
    }
  }

  async function registerProduct(event: FormEvent) {
    event.preventDefault();
    if (!token || !check || check.status !== "ABIERTA" || !code.trim() || busy) return;
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
      focusCodeAfterRender.current = true;
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
      focusCodeAfterRender.current = true;
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
  const documentSearchInput = <input
    ref={searchRef}
    className={app !== "pda" ? "erp-search-input" : undefined}
    type="search"
    value={search}
    placeholder={t("goodsCheck.searchPlaceholder")}
    onChange={(event) => setSearch(event.target.value)}
  />;

  return (
    <section className={`goods-check-panel${separateWorkflow ? " goods-check-panel-separate" : ""}${app === "gestion" ? " gestion-warehouse-screen" : ""}`}>
      {(!separateWorkflow || workflowView === "documents") && <div className={`goods-check-documents${app !== "pda" ? " erp-list-filter-host" : ""}`}>
        <header className="goods-check-documents-header">
          {app === "pda" ? <><div className="stock-history-toolbar goods-check-toolbar">
            <label className="goods-check-search">
              <span>{t("salesReport.search")}</span>
              {documentSearchInput}
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
                className={(typeFilter[0] ?? "all") === value ? "selected" : ""}
                aria-pressed={(typeFilter[0] ?? "all") === value}
                onClick={() => setTypeFilter(value === "all" ? [] : [value])}
              >
                {t(label)}
              </button>
            ))}
          </div></> : <ErpListFilterPanel
            translate={t}
            search={<label><span>{t("salesReport.search")}</span><WarehouseSearchFrame>{documentSearchInput}</WarehouseSearchFrame></label>}
            filters={<><label><span>{t("goodsCheck.filter.type")}</span><ErpListSelect
              aria-label={t("goodsCheck.filter.type")}
              values={typeFilter}
              options={([
                ["", "goodsCheck.filter.all"],
                ["deliveryNotes", "goodsCheck.filter.deliveryNotes"],
                ["invoices", "goodsCheck.filter.invoices"]
              ] as const).map(([value, label]) => ({ value, label: t(label) }))}
              onChange={setTypeFilter}

            /></label>
              <label><span>{t("stock.column.supplier")}</span><ErpListSelect aria-label={t("stock.column.supplier")} values={supplierFilter}
                options={[{ value: "", label: t("goodsCheck.filter.all") }, ...supplierFilterOptions]} onChange={setSupplierFilter}
                /></label>
              <label><span>{t("stock.column.warehouse")}</span><ErpListSelect aria-label={t("stock.column.warehouse")} values={warehouseFilter}
                options={[{ value: "", label: t("goodsCheck.filter.all") }, ...warehouseFilterOptions]} onChange={setWarehouseFilter}
                /></label>
            </>}
            actions={<button type="button" disabled={!selectedDocument || busy} onClick={() => void importDocument()}>
              <DownloadSimple size={16} aria-hidden="true" />{busy ? t("common.loading") : t("goodsCheck.import")}
            </button>}
          />}
          {app !== "pda" && <ErpFilterChips locale={locale} chips={documentFilterChips}
            className="goods-check-document-filter-chips" focusRef={searchRef}
            onClear={() => { setSearch(""); setTypeFilter([]); setSupplierFilter([]); setWarehouseFilter([]); }} />}
          {app === "pda" && <div className="goods-check-documents-summary">
            <div>
              <strong>{t("goodsCheck.availableDocuments")}</strong>
              <small>{t("goodsCheck.purchaseDocuments")}</small>
            </div>
            <span className="goods-check-document-count">
              <strong>{visibleDocuments.length}</strong>
              <small>{t("goodsCheck.documents")}</small>
            </span>
          </div>}
          {separateWorkflow && check?.status === "ABIERTA" && (
            <button type="button" className="goods-check-resume" onClick={() => openWorkflowView("check")}>
              <span>{t("goodsCheck.active")}</span>
              <strong>{activeDocument?.number ?? activeDocument?.numero ?? ""}</strong>
              <small>{t("goodsCheck.continue")}</small>
            </button>
          )}
        </header>
        <div ref={documentListRef} className={`stock-history-table-scroll goods-check-document-list${app !== "pda" ? " erp-classic-tables warehouse-classic-table erp-list-filter-table" : ""}`}>
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
                  data-document-id={document.id}
                  className={selectedDocumentId === document.id ? "selected" : ""}
                  tabIndex={0}
                  onFocus={(event) => { setSelectedDocumentId(document.id); if (documentListRef.current) ensureTableRowVisible(documentListRef.current, event.currentTarget); }}
                  onClick={() => setSelectedDocumentId(document.id)}
                  onDoubleClick={() => {
                    void importDocument(document);
                  }}
                  onKeyDown={(event) => {
                    if (event.target !== event.currentTarget) return;
                    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                      event.preventDefault();
                      repeatDocumentArrow(event);
                      return;
                    }
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
                <input ref={codeRef} value={code} disabled={check.status !== "ABIERTA" || busy} onChange={(event) => setCode(event.target.value)}
                  onKeyDown={(event) => {
                    if (app === "gestion" && event.key === "Enter" && !event.nativeEvent.isComposing) {
                      event.preventDefault();
                      if (code.trim()) {
                        quantityRef.current?.focus();
                        quantityRef.current?.select();
                      }
                    }
                  }} />
              </label>
              <div className="goods-check-register-control">
                <label>
                  <span>{t("goodsCheck.quantityToRegister")}</span>
                  <input ref={quantityRef} inputMode="decimal" value={quantity} disabled={check.status !== "ABIERTA" || busy} onChange={(event) => setQuantity(event.target.value)} />
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
              <button ref={allLinesRef} type="button" className={lineFilter === "all" ? "selected" : ""} onClick={() => setLineFilter("all")}>
                <span>{t("goodsCheck.filter.linesAll")}</span><strong>{check.todos.length}</strong>
              </button>
              <button type="button" className={lineFilter === "missing" ? "selected" : ""} onClick={() => setLineFilter("missing")}>
                <span>{t(app === "gestion" ? "goodsCheck.filter.linesPending" : "goodsCheck.filter.linesDifferences")}</span>
                <strong>{check.todos.filter((item) => Number(item.missingQuantity) > 0 || Number(item.extraQuantity) > 0).length}</strong>
              </button>
              <button type="button" className={lineFilter === completedLineFilter ? "selected" : ""} onClick={() => setLineFilter(completedLineFilter)}>
                <span>{t(app === "gestion" ? "goodsCheck.filter.linesComplete" : "goodsCheck.filter.linesRegistered")}</span><strong>{app === "gestion" ? balancedLines : check.registrados.length}</strong>
              </button>
              <span className="goods-check-extra-summary">
                <small>{t("goodsCheck.extraLines")}</small>
                <strong>{check.todos.filter((item) => Number(item.extraQuantity) > 0).length}</strong>
              </span>
            </div>
            {app === "venta" && <ErpFilterChips locale={locale} className="goods-check-line-filter-chips" focusRef={allLinesRef}
              chips={lineFilter === "all" ? [] : [{ key: "lines", label: t("salesReport.column.lines"),
                value: t(lineFilter === "missing" ? "goodsCheck.filter.linesDifferences" : "goodsCheck.filter.linesRegistered"),
                onRemove: () => setLineFilter("all") }]}
              onClear={() => setLineFilter("all")} />}
            <div className={`stock-history-table-scroll goods-check-lines${app !== "pda" ? " erp-classic-tables warehouse-classic-table" : ""}`}>
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
