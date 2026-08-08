import { useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { apiBaseUrl } from "../api/runtime";
import { formatProductQuantity } from "../sale/productQuantity";
import type { LocaleCode } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import type { SaleInterfaceMode } from "./saleInterfacePreferences";
import { TableSortButton } from "./TableSortButton";
import { nextTableSort, sortTableRows, type TableSort } from "./tableSorting";

export type SaleProductSearchOption = {
  id: string;
  imageId?: string | null;
  code?: string | null;
  barcode?: string | null;
  barcode2?: string | null;
  name?: string | null;
  productType?: string | null;
  salePrice?: number | string | null;
  totalStock?: number | string | null;
};

type SaleProductSearchLabels = {
  title: string;
  query: string;
  image: string;
  code: string;
  barcode: string;
  name: string;
  stock: string;
  price: string;
  result: string;
  results: string;
  empty: string;
  close: string;
  add: string;
  details: string;
  navigate: string;
  selected: string;
  unnamedProduct: string;
  missingCode: string;
};

type SaleProductSearchDialogProps<T extends SaleProductSearchOption> = {
  initialQuery: string;
  initialSelectedId?: string;
  interfaceMode?: SaleInterfaceMode;
  locale?: LocaleCode;
  labels: SaleProductSearchLabels;
  products: T[];
  token?: string;
  onClose: () => void;
  onInspect?: (product: T) => void;
  onQueryChange?: (query: string) => void;
  onSelectionChange?: (productId: string) => void;
  onSelect: (product: T) => void;
};

type SaleProductSearchSortColumn = "code" | "barcode" | "name" | "stock" | "price";

function normalizedSearchValue(value: string | null | undefined) {
  return value?.trim().toLocaleLowerCase() ?? "";
}

export function filterSaleProductSearch<T extends SaleProductSearchOption>(
  products: T[],
  query: string,
  limit = 100,
) {
  const normalizedQuery = normalizedSearchValue(query);
  if (!normalizedQuery) return [];
  return products
    .filter((product) => [product.code, product.barcode, product.barcode2, product.name]
      .some((value) => normalizedSearchValue(value).includes(normalizedQuery)))
    .slice(0, limit);
}

export function SaleProductSearchDialog<T extends SaleProductSearchOption>({
  initialQuery,
  initialSelectedId = "",
  interfaceMode,
  locale = "es",
  labels,
  products,
  token,
  onClose,
  onInspect,
  onQueryChange,
  onSelectionChange,
  onSelect,
}: SaleProductSearchDialogProps<T>) {
  const [query, setQuery] = useState(initialQuery);
  const [selectedId, setSelectedId] = useState(initialSelectedId);
  const [sort, setSort] = useState<TableSort<SaleProductSearchSortColumn> | null>(null);
  const dialogRef = useRef<HTMLElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const results = useMemo(() => sortTableRows(
    filterSaleProductSearch(products, query),
    sort,
    (product, column) => {
      if (column === "code") return product.code;
      if (column === "barcode") return product.barcode;
      if (column === "name") return product.name;
      if (column === "stock") return product.totalStock == null ? null : Number(product.totalStock);
      return product.salePrice == null ? null : Number(product.salePrice);
    }
  ), [products, query, sort]);
  const activeId = results.some((product) => product.id === selectedId)
    ? selectedId
    : results[0]?.id ?? "";

  useEffect(() => {
    const root = dialogRef.current;
    if (!root) return;
    const deactivate = activateModalFocusTrap(root as unknown as ModalFocusRoot, document);
    inputRef.current?.focus();
    inputRef.current?.select();
    return () => {
      deactivate();
    };
  }, []);

  useEffect(() => {
    if (activeId !== selectedId) {
      setSelectedId(activeId);
      onSelectionChange?.(activeId);
    }
  }, [activeId, onSelectionChange, selectedId]);

  function selectProduct(productId: string) {
    setSelectedId(productId);
    onSelectionChange?.(productId);
  }

  function moveSelection(offset: -1 | 1) {
    if (results.length === 0) return;
    const index = Math.max(0, results.findIndex((product) => product.id === activeId));
    const next = results[Math.min(Math.max(index + offset, 0), results.length - 1)];
    selectProduct(next.id);
    queueMicrotask(() => {
      document.getElementById(`sale-product-search-option-${encodeURIComponent(next.id)}`)
        ?.scrollIntoView?.({ block: "nearest" });
    });
  }

  function handleKeyDown(event: ReactKeyboardEvent<HTMLElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onClose();
      return;
    }
    const target = event.target instanceof HTMLElement ? event.target : null;
    const acceptsSearchShortcuts = event.target === inputRef.current
      || Boolean(target?.closest(".sale-product-search-row"));
    if (event.key === "ArrowUp" || event.key === "ArrowDown") {
      if (!acceptsSearchShortcuts) return;
      event.preventDefault();
      moveSelection(event.key === "ArrowUp" ? -1 : 1);
      return;
    }
    if ((event.key === "Enter" || event.key === "Insert") && activeId) {
      if (!acceptsSearchShortcuts) return;
      event.preventDefault();
      const product = results.find((candidate) => candidate.id === activeId);
      if (!product) return;
      if (interfaceMode === "KEYBOARD") {
        if (event.key === "Insert") onSelect(product);
        else onInspect?.(product);
        return;
      }
      if (event.key === "Enter") onSelect(product);
    }
  }

  function handleProductClick(product: T) {
    selectProduct(product.id);
    if (interfaceMode === "KEYBOARD") return;
    if (interfaceMode !== "TOUCH") {
      onSelect(product);
    }
  }

  function handleProductDoubleClick(product: T) {
    if (interfaceMode === "KEYBOARD") {
      onInspect?.(product);
      return;
    }
  }

  const activeProduct = results.find((product) => product.id === activeId);

  return (
    <div
      className="sale-action-overlay sale-product-search-overlay"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        ref={dialogRef}
        className="sale-product-search-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sale-product-search-title"
        onKeyDown={handleKeyDown}
      >
        <header>
          <div className="sale-product-search-heading">
            <h2 id="sale-product-search-title">{labels.title}</h2>
            <span className="sale-product-search-count" role="status">
              <strong>{results.length}</strong>
              {" "}
              {results.length === 1 ? labels.result : labels.results}
            </span>
          </div>
          <button type="button" aria-label={labels.close} onClick={onClose}>×</button>
        </header>

        <label className="sale-product-search-field">
          <span>{labels.query}</span>
          <input
            ref={inputRef}
            role="combobox"
            aria-autocomplete="list"
            aria-controls="sale-product-search-results"
            aria-expanded="true"
            aria-activedescendant={activeId
              ? `sale-product-search-option-${encodeURIComponent(activeId)}`
              : undefined}
            autoComplete="off"
            value={query}
            onChange={(event) => {
              const nextQuery = event.target.value;
              setQuery(nextQuery);
              onQueryChange?.(nextQuery);
              setSelectedId("");
              onSelectionChange?.("");
            }}
          />
        </label>

        <div className="sale-product-search-table">
          <div className="sale-product-search-head">
            <span>{labels.image}</span>
            {([
              ["code", labels.code],
              ["barcode", labels.barcode],
              ["name", labels.name],
              ["stock", labels.stock],
              ["price", labels.price]
            ] as const).map(([column, label]) => (
              <span key={column}>
                <TableSortButton
                  direction={sort?.column === column ? sort.direction : null}
                  label={label}
                  onSort={() => setSort((current) => nextTableSort(current, column))}
                >
                  {label}
                </TableSortButton>
              </span>
            ))}
          </div>
          <div
            id="sale-product-search-results"
            className="sale-product-search-body"
            role="listbox"
            aria-label={labels.title}
          >
            {results.map((product) => (
              <div
                id={`sale-product-search-option-${encodeURIComponent(product.id)}`}
                role="option"
                tabIndex={-1}
                aria-selected={product.id === activeId}
                aria-label={productSearchOptionLabel(product, labels, locale)}
                className={`sale-product-search-row${product.id === activeId ? " selected" : ""}`}
                key={product.id}
                onClick={() => handleProductClick(product)}
                onDoubleClick={() => handleProductDoubleClick(product)}
              >
                <span className="sale-product-search-image-cell">
                  <SaleProductSearchThumbnail product={product} token={token} />
                </span>
                <span>{product.code || labels.missingCode}</span>
                <span>{product.barcode || "—"}</span>
                <strong>{product.name || labels.unnamedProduct}</strong>
                <span className="sale-product-search-stock">
                  {formatProductQuantity(product.totalStock, product.productType, locale)}
                </span>
                <b>{formatAmount(product.salePrice)}</b>
              </div>
            ))}
          </div>
          {results.length === 0 && (
            <p className="sale-product-search-empty" role="status">{labels.empty}</p>
          )}
        </div>
        {interfaceMode === "KEYBOARD" && (
          <footer className="sale-product-search-keyboard-actions">
            <p>
              <span>{labels.selected}</span>
              <strong>{activeProduct?.name || activeProduct?.code || labels.unnamedProduct}</strong>
            </p>
            <div>
              <span><kbd>↑↓</kbd>{labels.navigate}</span>
              <span><kbd>Enter</kbd>{labels.details}</span>
              <span><kbd>Insert</kbd>{labels.add}</span>
              <span><kbd>Esc</kbd>{labels.close}</span>
            </div>
          </footer>
        )}
        {interfaceMode === "TOUCH" && (
          <footer className="sale-product-search-touch-actions">
            <p aria-live="polite">
              <span>{labels.selected}</span>
              <strong>{activeProduct?.name || activeProduct?.code || labels.unnamedProduct}</strong>
            </p>
            <button type="button" disabled={!activeProduct || !onInspect} onClick={() => activeProduct && onInspect?.(activeProduct)}>
              {labels.details}
            </button>
            <button type="button" className="primary" disabled={!activeProduct} onClick={() => activeProduct && onSelect(activeProduct)}>
              {labels.add}
            </button>
          </footer>
        )}
      </section>
    </div>
  );
}

function SaleProductSearchThumbnail({
  product,
  token,
}: {
  product: SaleProductSearchOption;
  token?: string;
}) {
  const imageRef = useRef<HTMLImageElement>(null);
  const [visible, setVisible] = useState(() => typeof IntersectionObserver === "undefined");
  const [source, setSource] = useState("");

  useEffect(() => {
    if (visible || !product.imageId || !token || !imageRef.current || typeof IntersectionObserver === "undefined") {
      return;
    }
    const observer = new IntersectionObserver((entries) => {
      if (!entries.some((entry) => entry.isIntersecting)) return;
      setVisible(true);
      observer.disconnect();
    }, { rootMargin: "80px" });
    observer.observe(imageRef.current);
    return () => observer.disconnect();
  }, [product.imageId, token, visible]);

  useEffect(() => {
    if (!visible || !product.imageId || !token) {
      setSource("");
      return;
    }
    const controller = new AbortController();
    let objectUrl = "";
    void fetch(`${apiBaseUrl}/products/${encodeURIComponent(product.id)}/image?thumbnail=true`, {
      headers: { Authorization: `Bearer ${token}` },
      signal: controller.signal,
    }).then(async (response) => {
      if (!response.ok) throw new Error("product_image_unavailable");
      objectUrl = URL.createObjectURL(await response.blob());
      setSource(objectUrl);
    }).catch(() => {
      if (!controller.signal.aborted) setSource("");
    });
    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [product.id, product.imageId, token, visible]);

  if (!product.imageId) {
    return <span className="sale-product-search-thumbnail-placeholder" aria-hidden="true" />;
  }

  return (
    <img
      ref={imageRef}
      className="sale-product-search-thumbnail"
      src={source || undefined}
      alt=""
    />
  );
}

function formatAmount(value: number | string | null | undefined) {
  const amount = Number(value);
  if (!Number.isFinite(amount)) return "—";
  return new Intl.NumberFormat(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

function productSearchOptionLabel(
  product: SaleProductSearchOption,
  labels: SaleProductSearchLabels,
  locale: LocaleCode,
) {
  return [
    `${labels.name}: ${product.name || labels.unnamedProduct}`,
    `${labels.code}: ${product.code || labels.missingCode}`,
    product.barcode ? `${labels.barcode}: ${product.barcode}` : "",
    `${labels.stock}: ${formatProductQuantity(product.totalStock, product.productType, locale)}`,
    `${labels.price}: ${formatAmount(product.salePrice)}`,
  ].filter(Boolean).join("; ");
}
