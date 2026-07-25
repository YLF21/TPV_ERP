import { useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

export type SaleProductSearchOption = {
  id: string;
  code?: string | null;
  barcode?: string | null;
  barcode2?: string | null;
  name?: string | null;
  salePrice?: number | string | null;
};

type SaleProductSearchLabels = {
  title: string;
  query: string;
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
  labels: SaleProductSearchLabels;
  products: T[];
  onClose: () => void;
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
  labels,
  products,
  onClose,
  onSelect,
}: SaleProductSearchDialogProps<T>) {
  const [query, setQuery] = useState(initialQuery);
  const [selectedId, setSelectedId] = useState("");
  const dialogRef = useRef<HTMLElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
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
    return deactivate;
  }, []);

  useEffect(() => {
    if (activeId !== selectedId) setSelectedId(activeId);
  }, [activeId, selectedId]);

  function moveSelection(offset: -1 | 1) {
    if (results.length === 0) return;
    const index = Math.max(0, results.findIndex((product) => product.id === activeId));
    const next = results[Math.min(Math.max(index + offset, 0), results.length - 1)];
    setSelectedId(next.id);
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
    if (event.key === "Enter" && activeId) {
      event.preventDefault();
      const product = results.find((candidate) => candidate.id === activeId);
      if (product) onSelect(product);
    }
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
              setQuery(event.target.value);
              setSelectedId("");
            }}
          />
        </label>

        <div className="sale-product-search-table" role="listbox" aria-label={labels.title}>
          <div className="sale-product-search-head" aria-hidden="true">
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
                onClick={() => onSelect(product)}
                onMouseEnter={() => setSelectedId(product.id)}
              >
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

function formatAmount(value: number | string | null | undefined) {
  const amount = Number(value);
  if (!Number.isFinite(amount)) return "—";
  return new Intl.NumberFormat(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}
