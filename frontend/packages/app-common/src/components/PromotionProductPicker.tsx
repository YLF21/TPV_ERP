import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { createPortal } from "react-dom";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { ErpConfirmDialog } from "./ErpConfirmDialog";
import { ErpSelect } from "./ErpSelect";
import { ensureTableRowVisible } from "./ensureTableRowVisible";
import { defaultProductSearchSort, sortProductsByCode } from "./productSearchOrdering";
import { TableSortButton } from "./TableSortButton";
import { nextTableSort, sortTableRows, type TableSort } from "./tableSorting";
import { PromotionProductExcelImportButton } from "./PromotionProductExcelImportButton";
import { WindowCloseButton } from "./WindowCloseButton";
import "./ProductCreateDialog.css";
import "./PromotionProductPicker.css";

export type PromotionCatalogProduct = {
  id: string;
  code?: string | null;
  barcode?: string | null;
  barcode2?: string | null;
  name?: string | null;
  familyId?: string | null;
  subfamilyId?: string | null;
  active?: boolean | null;
};

type NamedOption = {
  id: string;
  name?: string | null;
  familyId?: string | null;
  active?: boolean | null;
  defaultWarehouse?: boolean | null;
};
type StockLevel = { productId: string; warehouseId: string; quantity?: number | string | null };
type Availability = "all" | "available" | "unavailable";

export type PromotionProductPickerProps = {
  locale: LocaleCode;
  token?: string;
  products: PromotionCatalogProduct[];
  selectedProductIds: string[];
  initialWarehouseId?: string;
  initialSelectedOnly?: boolean;
  productsLoading?: boolean;
  productsError?: boolean;
  onRetryProducts?: () => void;
  onApply: (ids: string[]) => void;
  onClose: () => void;
};

const rowBatchSize = 50;
const sortColumns = ["code", "barcode", "name", "stock"] as const;
type SortColumn = (typeof sortColumns)[number];
type PickerView = {
  query: string;
  familyId: string;
  subfamilyId: string;
  warehouseId: string;
  availability: Availability;
  sort: TableSort<SortColumn>;
  visibleCount: number;
};
const initialView: PickerView = {
  query: "", familyId: "", subfamilyId: "", warehouseId: "", availability: "all",
  sort: defaultProductSearchSort, visibleCount: rowBatchSize
};
const labels = {
  es: {
    title: "Seleccionar productos",
    search: "Código, nombre o código de barras",
    family: "Familia",
    subfamily: "Subfamilia",
    warehouse: "Almacén",
    availability: "Disponibilidad",
    all: "Todos",
    available: "Con stock",
    unavailable: "Sin stock",
    code: "Código",
    barcode: "Código de barras",
    name: "Producto",
    stock: "Stock disponible",
    select: "Seleccionar",
    results: (n: number) => n === 1 ? "1 resultado" : `${n} resultados`,
    selected: (n: number) => n === 1 ? "1 seleccionado en total" : `${n} seleccionados en total`,
    selectResults: (n: number) => n === 1 ? "Seleccionar el resultado del filtro" : `Seleccionar los ${n} resultados del filtro`,
    deselectResults: (n: number) => n === 1 ? "Quitar el resultado del filtro" : `Quitar los ${n} resultados del filtro`,
    clear: (n: number) => n === 0 ? "Quitar seleccionados (0)" : n === 1 ? "Quitar 1 seleccionado" : `Quitar los ${n} seleccionados`,
    removed: (n: number) => n === 1 ? "Se quitó 1 producto." : `Se quitaron ${n} productos.`,
    undo: "Deshacer",
    discardTitle: "¿Descartar los cambios en la selección?",
    discardMessage: "Si sales, se perderán los cambios en los productos seleccionados.",
    keepEditing: "Seguir editando",
    discardAndClose: "Descartar y salir",
    selectedOnly: "Solo seleccionados",
    empty: "No hay productos que coincidan",
    loading: "Cargando productos…",
    stockLoading: "Cargando stock…",
    stockUnavailable: "Stock no disponible",
    warehouseUnavailable: "No se pudieron cargar los almacenes",
    retry: "Reintentar"
  },
  en: {
    title: "Select products",
    search: "Code, name or barcode",
    family: "Family",
    subfamily: "Subfamily",
    warehouse: "Warehouse",
    availability: "Availability",
    all: "All",
    available: "In stock",
    unavailable: "Out of stock",
    code: "Code",
    barcode: "Barcode",
    name: "Product",
    stock: "Available stock",
    select: "Select",
    results: (n: number) => n === 1 ? "1 result" : `${n} results`,
    selected: (n: number) => `${n} selected in total`,
    selectResults: (n: number) => n === 1 ? "Select the filtered result" : `Select all ${n} filtered results`,
    deselectResults: (n: number) => n === 1 ? "Deselect the filtered result" : `Deselect all ${n} filtered results`,
    clear: (n: number) => n === 1 ? "Remove 1 selected product" : `Remove ${n} selected products`,
    removed: (n: number) => n === 1 ? "1 product removed." : `${n} products removed.`,
    undo: "Undo",
    discardTitle: "Discard changes to the selection?",
    discardMessage: "If you leave, your changes to the selected products will be lost.",
    keepEditing: "Keep editing",
    discardAndClose: "Discard and exit",
    selectedOnly: "Selected only",
    empty: "No matching products",
    loading: "Loading products…",
    stockLoading: "Loading stock…",
    stockUnavailable: "Stock unavailable",
    warehouseUnavailable: "Could not load warehouses",
    retry: "Retry"
  },
  zh: {
    title: "选择商品",
    search: "编码、名称或条码",
    family: "类别",
    subfamily: "子类别",
    warehouse: "仓库",
    availability: "库存状态",
    all: "全部",
    available: "有库存",
    unavailable: "无库存",
    code: "编码",
    barcode: "条码",
    name: "商品",
    stock: "可用库存",
    select: "选择",
    results: (n: number) => `${n} 个结果`,
    selected: (n: number) => `共选中 ${n} 个商品`,
    selectResults: (n: number) => `选中全部 ${n} 个筛选结果`,
    deselectResults: (n: number) => `取消选中全部 ${n} 个筛选结果`,
    clear: (n: number) => `移除已选的 ${n} 个商品`,
    removed: (n: number) => `已移除 ${n} 个商品。`,
    undo: "撤销",
    discardTitle: "放弃对所选商品的更改？",
    discardMessage: "退出后，您对所选商品的更改将丢失。",
    keepEditing: "继续编辑",
    discardAndClose: "放弃更改并退出",
    selectedOnly: "仅已选择",
    empty: "没有匹配的商品",
    loading: "正在加载商品…",
    stockLoading: "正在加载库存…",
    stockUnavailable: "库存不可用",
    warehouseUnavailable: "无法加载仓库",
    retry: "重试"
  }
} as const;

function normalize(value: string | null | undefined, locale: LocaleCode) {
  return (value ?? "").trim().toLocaleLowerCase(locale);
}

export function filterPromotionProducts(
  products: PromotionCatalogProduct[],
  query: string,
  familyId: string,
  subfamilyId: string,
  availability: Availability,
  stock: ReadonlyMap<string, number | null> | null,
  locale: LocaleCode,
  includeInactive = false
) {
  const search = normalize(query, locale);
  return sortProductsByCode(
    products.filter((product) => {
      if (product.active === false && !includeInactive) return false;
      if (familyId && product.familyId !== familyId) return false;
      if (subfamilyId && product.subfamilyId !== subfamilyId) return false;
      if (
        search &&
        ![product.code, product.barcode, product.barcode2, product.name].some((value) =>
          normalize(value, locale).includes(search)
        )
      )
        return false;
      if (availability !== "all" && (stock === null || stock.get(product.id) === null)) return false;
      if (availability === "available" && (stock?.get(product.id) ?? 0) <= 0) return false;
      if (availability === "unavailable" && (stock?.get(product.id) ?? 0) > 0) return false;
      return true;
    }),
    locale
  );
}

export function PromotionProductPicker({
  locale,
  token,
  products,
  selectedProductIds,
  initialWarehouseId,
  initialSelectedOnly = false,
  productsLoading = false,
  productsError = false,
  onRetryProducts,
  onApply,
  onClose
}: PromotionProductPickerProps) {
  const l = labels[locale];
  const t = createTranslator(locale);
  const [initialSelection] = useState(() => new Set(selectedProductIds));
  const [selectedIds, setSelectedIds] = useState(() => [...initialSelection]);
  const [discardPromptOpen, setDiscardPromptOpen] = useState(false);
  const [removedIds, setRemovedIds] = useState<string[]>([]);
  const [view, setView] = useState(initialView);
  const { query, familyId, subfamilyId, availability, sort, visibleCount } = view;
  const [selectedOnly, setSelectedOnly] = useState(initialSelectedOnly);
  const [warehouses, setWarehouses] = useState<NamedOption[]>([]);
  const warehouseId = view.warehouseId || warehouses.find(w => w.id === initialWarehouseId)?.id
    || warehouses.find(w => w.defaultWarehouse)?.id || warehouses[0]?.id || "";
  const [warehouseError, setWarehouseError] = useState(false);
  const [families, setFamilies] = useState<NamedOption[]>([]);
  const [familyOptions, setFamilyOptions] = useState<{
    token?: string;
    rows: Map<string, NamedOption[]>;
  }>(() => ({ token, rows: new Map() }));
  const subfamilies = familyOptions.token === token ? familyOptions.rows.get(familyId) : undefined;
  const [warehouseStock, setWarehouseStock] = useState<{
    warehouseId: string; token?: string; levels: Map<string, number | null> | null;
  } | null>(null);
  const stockLoading = !!warehouseId && (warehouseStock?.warehouseId !== warehouseId || warehouseStock.token !== token);
  const stock = warehouseId && !stockLoading ? warehouseStock?.levels ?? null : null;
  const [focusId, setFocusId] = useState("");
  const dialogRef = useRef<HTMLElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const focusBeforeDiscard = useRef<HTMLElement | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const rowRefs = useRef(new Map<string, HTMLTableRowElement>());
  const pendingRowFocus = useRef("");
  const catalogView = useRef({ view: initialView, focusId: "", top: 0, left: 0 });
  const restoreScroll = useRef(false);
  const clearButton = useRef<HTMLButtonElement>(null);
  const undoButton = useRef<HTMLButtonElement>(null);
  const selectionActionFocus = useRef<"clear" | "undo" | null>(null);

  useEffect(() => {
    const root = dialogRef.current;
    if (!root) return;
    const deactivate = activateModalFocusTrap(root as ModalFocusRoot, document);
    searchRef.current?.focus();
    return deactivate;
  }, []);

  useEffect(() => {
    if (discardPromptOpen || !focusBeforeDiscard.current) return;
    const previous = focusBeforeDiscard.current;
    focusBeforeDiscard.current = null;
    (previous.isConnected ? previous : searchRef.current)?.focus({ preventScroll: true });
  }, [discardPromptOpen]);

  useEffect(() => {
    let live = true;
    void apiRequest<NamedOption[]>("/families", { token })
      .then((rows) => {
        if (live) setFamilies(rows);
      })
      .catch(() => {
        if (live) setFamilies([]);
      });
    void apiRequest<NamedOption[]>("/warehouses", { token })
      .then((warehouseRows) => {
        if (!live) return;
        const active = warehouseRows.filter((warehouse) => warehouse.active !== false);
        setWarehouses(active);
        setWarehouseError(false);
      })
      .catch(() => {
        if (!live) return;
        setWarehouseError(true);
      });
    return () => {
      live = false;
    };
  }, [token]);

  useEffect(() => {
    if (!familyId || subfamilies) return;
    let live = true;
    function storeOptions(options: NamedOption[]) {
      if (!live) return;
      setFamilyOptions(current => ({
        token,
        rows: new Map(current.token === token ? current.rows : []).set(familyId, options)
      }));
    }
    void apiRequest<NamedOption[]>(`/families/${encodeURIComponent(familyId)}/subfamilies`, { token })
      .then(storeOptions)
      .catch(() => { /* Leave the list empty and retry when the family is revisited. */ });
    return () => {
      live = false;
    };
  }, [familyId, token, subfamilies]);

  useEffect(() => {
    if (!warehouseId) return;
    let live = true;
    void apiRequest<StockLevel[]>(`/stock?warehouseId=${encodeURIComponent(warehouseId)}`, { token })
      .then((rows) => {
        if (!live) return;
        const values = new Map<string, number | null>();
        for (const row of rows) {
          const quantity = Number(row.quantity);
          if (row.warehouseId === warehouseId)
            values.set(
              row.productId,
              row.quantity === null || row.quantity === undefined || !Number.isFinite(quantity) ? null : quantity
            );
        }
        setWarehouseStock({ warehouseId, token, levels: values });
      })
      .catch(() => {
        if (live) setWarehouseStock({ warehouseId, token, levels: null });
      });
    return () => {
      live = false;
    };
  }, [warehouseId, token]);

  const productById = useMemo(() => new Map(products.map((product) => [product.id, product])), [products]);
  const availableStock = useCallback((id: string) => {
    if (stockLoading || !warehouseId || !stock || !productById.has(id)) return null;
    return stock.has(id) ? stock.get(id) : 0;
  }, [stockLoading, warehouseId, stock, productById]);
  const reviewProducts = useMemo(
    () => selectedIds.map((id) => productById.get(id) ?? { id }),
    [selectedIds, productById]
  );
  const candidates = selectedOnly ? reviewProducts : products;
  const filtered = useMemo(
    () =>
      filterPromotionProducts(candidates, query, familyId, subfamilyId, availability, stock, locale, selectedOnly),
    [candidates, query, familyId, subfamilyId, availability, stock, locale, selectedOnly]
  );
  const ordered = useMemo(() => {
    if (sort.column === "code" && sort.direction === "asc") return filtered;
    return sortTableRows(filtered, sort, (product, column) => {
      switch (column) {
        case "code": return product.code?.trim();
        case "barcode": return product.barcode || product.barcode2;
        case "name": return product.name || product.code || product.id;
        case "stock": return availableStock(product.id);
      }
    }, locale);
  }, [filtered, sort, availableStock, locale]);
  const visible = useMemo(() => ordered.slice(0, visibleCount), [ordered, visibleCount]);
  const selected = useMemo(() => new Set(selectedIds), [selectedIds]);
  const selectedResultCount = useMemo(
    () => filtered.reduce((count, product) => count + Number(selected.has(product.id)), 0),
    [filtered, selected]
  );
  const allResultsSelected = filtered.length > 0 && selectedResultCount === filtered.length;
  const bulkLabel = allResultsSelected ? l.deselectResults(filtered.length) : l.selectResults(filtered.length);

  useLayoutEffect(() => {
    if (!restoreScroll.current || !scrollRef.current || productsLoading) return;
    if (stockLoading && (availability !== "all" || sort.column === "stock")) return;
    scrollRef.current.scrollTop = catalogView.current.top;
    scrollRef.current.scrollLeft = catalogView.current.left;
    restoreScroll.current = false;
  }, [selectedOnly, productsLoading, stockLoading, availability, sort.column]);

  useLayoutEffect(() => {
    if (!selectionActionFocus.current) return;
    const button = selectionActionFocus.current === "undo" ? undoButton : clearButton;
    button.current?.focus({ preventScroll: true });
    selectionActionFocus.current = null;
  }, [removedIds]);

  useLayoutEffect(() => {
    if (!pendingRowFocus.current) return;
    const row = rowRefs.current.get(pendingRowFocus.current);
    pendingRowFocus.current = "";
    if (!row || !scrollRef.current) return;
    row.focus({ preventScroll: true });
    ensureTableRowVisible(scrollRef.current, row);
  }, [visible, focusId]);

  useEffect(() => {
    if (!selectedOnly || !focusId || visible.some((product) => product.id === focusId)) return;
    const nextId = visible[0]?.id;
    if (nextId) {
      setFocusId(nextId);
      rowRefs.current.get(nextId)?.focus();
    } else {
      setFocusId("");
      searchRef.current?.focus();
    }
  }, [selectedOnly, focusId, visible]);

  function changeView(changes: Partial<PickerView>) {
    restoreScroll.current = false;
    if (scrollRef.current) scrollRef.current.scrollTop = 0;
    pendingRowFocus.current = "";
    setView(current => ({ ...current, ...changes, visibleCount: rowBatchSize }));
    setFocusId("");
  }
  function reviewSelection(review: boolean) {
    pendingRowFocus.current = "";
    if (review) {
      const scroller = scrollRef.current;
      if (!restoreScroll.current) {
        catalogView.current = { view, focusId, top: scroller?.scrollTop ?? 0, left: scroller?.scrollLeft ?? 0 };
      }
      changeView({ ...initialView, warehouseId: view.warehouseId, sort });
      if (scroller) scroller.scrollLeft = 0;
    } else {
      restoreScroll.current = true;
      setView(catalogView.current.view);
      setFocusId(catalogView.current.focusId);
    }
    setSelectedOnly(review);
  }
  function showMoreRows() {
    if (!restoreScroll.current && visible.length < filtered.length) {
      setView(current => ({ ...current, visibleCount: visible.length + rowBatchSize }));
    }
  }
  function requestClose() {
    if (selected.size !== initialSelection.size || selectedIds.some(id => !initialSelection.has(id))) {
      const active = document.activeElement;
      focusBeforeDiscard.current = active instanceof HTMLElement && dialogRef.current?.contains(active)
        ? active : searchRef.current;
      setDiscardPromptOpen(true);
    } else {
      onClose();
    }
  }
  function clearSelection() {
    selectionActionFocus.current = "undo";
    setRemovedIds(selectedIds);
    setSelectedIds([]);
    if (selectedOnly) setFocusId("");
  }
  function undoClear() {
    selectionActionFocus.current = "clear";
    setSelectedIds(current => [...new Set([...removedIds, ...current])]);
    setRemovedIds([]);
  }
  function toggle(id: string) {
    setSelectedIds((current) => (current.includes(id) ? current.filter((item) => item !== id) : [...current, id]));
  }
  function toggleResults() {
    const resultIds = new Set(filtered.map((product) => product.id));
    if (allResultsSelected) {
      setRemovedIds(selectedIds.filter(id => resultIds.has(id)));
      setSelectedIds(current => current.filter(id => !resultIds.has(id)));
    } else {
      setSelectedIds(current => [...new Set([...current, ...resultIds])]);
    }
  }
  function onRowKeyDown(event: KeyboardEvent<HTMLTableRowElement>, index: number) {
    if (event.key === " " || event.key === "Enter") {
      event.preventDefault();
      toggle(visible[index].id);
      return;
    }
    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
    event.preventDefault();
    const nextIndex = index + (event.key === "ArrowDown" ? 1 : -1);
    const next = ordered[nextIndex];
    if (next) {
      pendingRowFocus.current = next.id;
      if (nextIndex >= visible.length) showMoreRows();
      setFocusId(next.id);
    }
  }

  return createPortal(
    <div
      className="promotion-product-picker-overlay"
      role="presentation"
      onKeyDown={(event) => {
        if (event.key === "Escape" && (event.target as Element).closest('[role="dialog"]') === dialogRef.current) {
          event.stopPropagation();
          if (event.defaultPrevented) return;
          event.preventDefault();
          requestClose();
        }
        if (event.key === "Enter") event.stopPropagation();
      }}
    >
      <section
        ref={dialogRef}
        className="promotion-product-picker"
        role="dialog"
        aria-modal={discardPromptOpen ? undefined : true}
        inert={discardPromptOpen}
        aria-labelledby="promotion-product-picker-title"
      >
        <header className="product-editor-header">
          <h2 id="promotion-product-picker-title">{l.title}</h2>
          <WindowCloseButton type="button" aria-label={t("common.close")} onClick={requestClose}>
            {t("common.close")}
          </WindowCloseButton>
        </header>
        <div className="promotion-product-picker-filters">
          <label className="promotion-product-picker-search">
            {l.search}
            <input
              ref={searchRef}
              type="search"
              value={query}
              onChange={(event) => changeView({ query: event.target.value })}
              onKeyDown={(event) => {
                if (event.key === "Enter") event.preventDefault();
              }}
            />
          </label>
          <label>
            {l.family}
            <ErpSelect
              aria-label={l.family}
              value={familyId}
              onChange={(value) => changeView({ familyId: value, subfamilyId: "" })}
              options={[
                { value: "", label: l.all },
                ...families.map((family) => ({ value: family.id, label: family.name || family.id }))
              ]}
            />
          </label>
          <label>
            {l.subfamily}
            <ErpSelect
              aria-label={l.subfamily}
              value={subfamilyId}
              disabled={!familyId}
              onChange={(value) => changeView({ subfamilyId: value })}
              options={[
                { value: "", label: l.all },
                ...(subfamilies ?? []).map((subfamily) => ({ value: subfamily.id, label: subfamily.name || subfamily.id }))
              ]}
            />
          </label>
          <label>
            {l.warehouse}
            <ErpSelect
              aria-label={l.warehouse}
              value={warehouseId}
              onChange={(value) => changeView({ warehouseId: value })}
              options={[
                { value: "", label: l.warehouse, disabled: true },
                ...warehouses.map((warehouse) => ({ value: warehouse.id, label: warehouse.name || warehouse.id }))
              ]}
            />
          </label>
          <label>
            {l.availability}
            <ErpSelect
              aria-label={l.availability}
              value={availability}
              onChange={(value) => changeView({ availability: value as Availability })}
              options={[
                { value: "all", label: l.all },
                { value: "available", label: l.available },
                { value: "unavailable", label: l.unavailable }
              ]}
            />
          </label>
        </div>
        <div className="promotion-product-picker-status">
          <span role="status">{l.results(filtered.length)} · {l.selected(selectedIds.length)}</span>
          <label className="promotion-product-picker-selected-only">
            <input
              type="checkbox"
              checked={selectedOnly}
              onChange={(event) => reviewSelection(event.target.checked)}
            />
            {l.selectedOnly}
          </label>
          <span className="promotion-product-picker-bulk-hint" aria-hidden="true">{bulkLabel}</span>
          {productsLoading && <span role="status">{l.loading}</span>}
          {productsError && onRetryProducts && (
            <button type="button" onClick={onRetryProducts}>
              {l.retry}
            </button>
          )}
          {warehouseError && <span role="status">{l.warehouseUnavailable}</span>}
          {stockLoading && <span role="status">{l.stockLoading}</span>}
          {!stockLoading && warehouseId && stock === null && <span role="status">{l.stockUnavailable}</span>}
        </div>
        <div ref={scrollRef} className="promotion-product-picker-table-scroll erp-classic-tables warehouse-classic-table"
          onScroll={({ currentTarget }) => {
            if (currentTarget.clientHeight > 0 && currentTarget.scrollHeight - currentTarget.scrollTop - currentTarget.clientHeight < 160) {
              showMoreRows();
            }
          }}>
          <table>
            <thead>
              <tr>
                <th scope="col">
                  <input
                    type="checkbox"
                    aria-label={bulkLabel}
                    title={bulkLabel}
                    checked={allResultsSelected}
                    ref={(input) => {
                      if (input)
                        input.indeterminate =
                          !allResultsSelected && selectedResultCount > 0;
                    }}
                    onChange={toggleResults}
                    disabled={!visible.length || selectedOnly}
                  />
                </th>
                {sortColumns.map(column => {
                  const direction = sort.column === column ? sort.direction : null;
                  return (
                    <th key={column} scope="col" aria-sort={direction === "asc" ? "ascending" : direction === "desc" ? "descending" : undefined}>
                      <TableSortButton label={`${t("party.sortBy")} ${l[column]}`} direction={direction}
                        onSort={() => changeView({ sort: nextTableSort(sort, column) })}>
                        {l[column]}
                      </TableSortButton>
                    </th>
                  );
                })}
              </tr>
            </thead>
            <tbody>
              {visible.map((product, index) => (
                <tr
                  key={product.id}
                  ref={(node) => {
                    if (node) rowRefs.current.set(product.id, node);
                    else rowRefs.current.delete(product.id);
                  }}
                  tabIndex={focusId === product.id ? 0 : focusId === "" && product.id === visible[0]?.id ? 0 : -1}
                  aria-selected={selected.has(product.id)}
                  onFocus={() => setFocusId(product.id)}
                  onKeyDown={(event) => onRowKeyDown(event, index)}
                  onClick={() => toggle(product.id)}
                >
                  <td>
                    <input
                      type="checkbox"
                      tabIndex={-1}
                      aria-label={`${l.select} ${product.code || product.name || product.id}`}
                      checked={selected.has(product.id)}
                      onChange={() => toggle(product.id)}
                      onClick={(event) => event.stopPropagation()}
                    />
                  </td>
                  <td>{product.code || "—"}</td>
                  <td>{product.barcode || product.barcode2 || "—"}</td>
                  <td className="promotion-product-name" title={product.name ?? undefined}>
                    {product.name || product.code || product.id}
                  </td>
                  <td>
                    {availableStock(product.id)?.toLocaleString(locale) ?? "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!productsLoading && !productsError && visible.length === 0 && (
            <p className="promotion-product-picker-empty">{l.empty}</p>
          )}
        </div>
        <footer className="promotion-product-picker-footer">
          {removedIds.length > 0 && (
            <div className="promotion-product-picker-undo">
              <span role="status">{l.removed(removedIds.length)}</span>
              <button ref={undoButton} type="button" onClick={undoClear}>{l.undo}</button>
            </div>
          )}
          <div className="promotion-product-picker-actions">
            <PromotionProductExcelImportButton
              products={products}
              selectedProductIds={selectedIds}
              onAddProducts={(ids) => setSelectedIds((current) => [...new Set([...current, ...ids])])}
              locale={locale}
              token={token}
            />
            <button ref={clearButton} type="button" disabled={!selectedIds.length} onClick={clearSelection}>
              {l.clear(selectedIds.length)}
            </button>
            <button type="button" className="erp-dialog-action-confirm" onClick={() => onApply(selectedIds)}>
              {t("common.confirm")}
            </button>
          </div>
        </footer>
      </section>
      {discardPromptOpen && (
        <ErpConfirmDialog
          title={l.discardTitle}
          message={l.discardMessage}
          cancelLabel={l.keepEditing}
          confirmLabel={l.discardAndClose}
          initialFocus="cancel"
          onCancel={() => setDiscardPromptOpen(false)}
          onConfirm={onClose}
        />
      )}
    </div>,
    document.body
  );
}
