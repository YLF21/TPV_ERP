import { useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { apiBaseUrl } from "../api/runtime";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import type { SaleInterfaceMode } from "./saleInterfacePreferences";

export type SaleProductSearchOption = {
  id: string;
  imageId?: string | null;
  code?: string | null;
  barcode?: string | null;
  barcode2?: string | null;
  name?: string | null;
  salePrice?: number | string | null;
};

type SaleProductSearchLabels = {
  title: string;
  query: string;
  image: string;
  code: string;
  barcode: string;
  barcode2: string;
  name: string;
  price: string;
  empty: string;
  close: string;
  unnamedProduct: string;
  missingCode: string;
};

type SaleProductSearchDialogProps<T extends SaleProductSearchOption> = {
  initialQuery: string;
  initialSelectedId?: string;
  interfaceMode?: SaleInterfaceMode;
  labels: SaleProductSearchLabels;
  products: T[];
  token?: string;
  onClose: () => void;
  onInspect?: (product: T) => void;
  onQueryChange?: (query: string) => void;
  onSelectionChange?: (productId: string) => void;
  onSelect: (product: T) => void;
};

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
  const dialogRef = useRef<HTMLElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const touchClickTimerRef = useRef<number | null>(null);
  const lastPointerTypeRef = useRef("mouse");
  const results = useMemo(() => filterSaleProductSearch(products, query), [products, query]);
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
      if (touchClickTimerRef.current !== null) {
        window.clearTimeout(touchClickTimerRef.current);
      }
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
    if (event.key === "ArrowUp" || event.key === "ArrowDown") {
      event.preventDefault();
      moveSelection(event.key === "ArrowUp" ? -1 : 1);
      return;
    }
    if ((event.key === "Enter" || event.key === "Insert") && activeId) {
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
    if (interfaceMode !== "TOUCH" || !onInspect) {
      onSelect(product);
      return;
    }
    if (lastPointerTypeRef.current === "mouse") return;
    if (touchClickTimerRef.current !== null) {
      window.clearTimeout(touchClickTimerRef.current);
    }
    touchClickTimerRef.current = window.setTimeout(() => {
      touchClickTimerRef.current = null;
      onInspect(product);
    }, 300);
  }

  function handleProductDoubleClick(product: T) {
    if (interfaceMode === "KEYBOARD" || lastPointerTypeRef.current === "mouse") {
      onInspect?.(product);
      return;
    }
    if (interfaceMode !== "TOUCH") return;
    if (touchClickTimerRef.current !== null) {
      window.clearTimeout(touchClickTimerRef.current);
      touchClickTimerRef.current = null;
    }
    onSelect(product);
  }

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
          <div>
            <h2 id="sale-product-search-title">{labels.title}</h2>
            <span>{results.length}</span>
          </div>
          <button type="button" aria-label={labels.close} onClick={onClose}>×</button>
        </header>

        <label className="sale-product-search-field">
          <span>{labels.query}</span>
          <input
            ref={inputRef}
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

        <div className="sale-product-search-table" role="listbox" aria-label={labels.title}>
          <div className="sale-product-search-head" aria-hidden="true">
            <span>{labels.image}</span>
            <span>{labels.code}</span>
            <span>{labels.barcode}</span>
            <span>{labels.barcode2}</span>
            <span>{labels.name}</span>
            <span>{labels.price}</span>
          </div>
          <div className="sale-product-search-body">
            {results.length === 0 && <p className="sale-product-search-empty">{labels.empty}</p>}
            {results.map((product) => (
              <button
                id={`sale-product-search-option-${encodeURIComponent(product.id)}`}
                type="button"
                role="option"
                aria-selected={product.id === activeId}
                className={`sale-product-search-row${product.id === activeId ? " selected" : ""}`}
                key={product.id}
                onPointerDown={(event) => {
                  lastPointerTypeRef.current = event.pointerType || "mouse";
                }}
                onClick={() => handleProductClick(product)}
                onDoubleClick={() => handleProductDoubleClick(product)}
                onMouseEnter={() => selectProduct(product.id)}
              >
                <SaleProductSearchThumbnail product={product} token={token} />
                <span>{product.code || labels.missingCode}</span>
                <span>{product.barcode || "—"}</span>
                <span>{product.barcode2 || "—"}</span>
                <strong>{product.name || labels.unnamedProduct}</strong>
                <b>{formatAmount(product.salePrice)}</b>
              </button>
            ))}
          </div>
        </div>
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
