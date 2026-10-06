import { WindowCloseButton } from "./WindowCloseButton";
import { DialogDismissButton } from "./DialogDismissButton";
import { X } from "@phosphor-icons/react";
import { sortProductsByCode } from "./productSearchOrdering";
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { AppKind, LocaleCode } from "../types";
import { useProductInformationResources } from "./productInformationResources";
import type { SaleProduct } from "./SaleScreen";
import { StockSalesHistoryPanel } from "./StockSalesHistoryPanel";

type SaleProductSalesHistoryDialogProps = {
  products: SaleProduct[];
  initialProduct?: SaleProduct | null;
  locale: LocaleCode;
  app?: AppKind;
  username?: string;
  accessToken?: string;
  onClose: () => void;
};

function normalized(value: string | null | undefined) {
  return value?.trim().toLocaleLowerCase() ?? "";
}

function matchesProduct(product: SaleProduct, query: string) {
  const search = normalized(query);
  if (!search) return false;
  return [product.code, product.barcode, product.barcode2, product.name]
    .some((candidate) => normalized(candidate).includes(search));
}

function exactProduct(products: SaleProduct[], query: string) {
  const search = normalized(query);
  if (!search) return undefined;
  return products.find((product) => [product.code, product.barcode, product.barcode2]
    .some((candidate) => normalized(candidate) === search));
}

export function SaleProductSalesHistoryDialog({
  products,
  initialProduct = null,
  locale,
  app = "venta",
  username = "",
  accessToken,
  onClose,
}: SaleProductSalesHistoryDialogProps) {
  const t = createTranslator(locale);
  const [selectedProduct, setSelectedProduct] = useState<SaleProduct | null>(initialProduct);
  const [query, setQuery] = useState(initialProduct?.code ?? "");
  const [activeIndex, setActiveIndex] = useState<number | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const resultRefs = useRef(new Map<string, HTMLButtonElement>());
  const resultsId = useId();
  const { imageSource } = useProductInformationResources({
    productId: selectedProduct?.id ?? "",
    imageId: selectedProduct?.imageId,
    token: accessToken,
    canReadSuppliers: false,
  });
  const results = useMemo(
    () => query.trim() && !selectedProduct
      ? sortProductsByCode(products.filter((product) => matchesProduct(product, query)), locale).slice(0, 12)
      : [],
    [products, query, selectedProduct, locale],
  );
  const exactMatch = exactProduct(products, query);
  const highlightedIndex = results.length
    ? Math.min(activeIndex ?? Math.max(0, results.findIndex((product) => product.id === exactMatch?.id)), results.length - 1)
    : -1;
  const highlightedProduct = results[highlightedIndex];

  useEffect(() => {
    if (highlightedProduct) resultRefs.current.get(highlightedProduct.id)?.scrollIntoView?.({ block: "nearest" });
  }, [highlightedProduct?.id]);

  function selectProduct(product: SaleProduct | undefined) {
    if (!product) return;
    setSelectedProduct(product);
    setQuery(product.code ?? product.barcode ?? product.barcode2 ?? product.name ?? "");
  }

  function handleResultKeyDown(event: KeyboardEvent, focusResult = false) {
    if (!results.length) return false;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      event.stopPropagation();
      const nextIndex = Math.max(0, Math.min(results.length - 1,
        highlightedIndex + (event.key === "ArrowDown" ? 1 : -1)));
      setActiveIndex(nextIndex);
      if (focusResult) resultRefs.current.get(results[nextIndex].id)?.focus();
      return true;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      event.stopPropagation();
      selectProduct(highlightedProduct);
      return true;
    }
    return false;
  }

  function handleSearchKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onClose();
      return;
    }
    if (handleResultKeyDown(event)) return;
    if (event.key !== "Enter") return;
    event.preventDefault();
    selectProduct(exactProduct(products, query) ?? results[0]);
  }

  return (
    <div className="sale-action-overlay sale-sales-history-overlay" role="presentation">
      <section
        className="sale-action-dialog sale-business-dialog sale-sales-history-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={t("stock.history.title")}
        onKeyDown={(event) => {
          if (event.key === "Escape" && !selectedProduct) {
            event.preventDefault();
            event.stopPropagation();
            onClose();
          }
        }}
      >
        <header>
          <h2>{t("stock.history.title")} <kbd aria-hidden="true">F6</kbd></h2>
          <WindowCloseButton type="button" aria-label={t("common.close")} onClick={onClose} >×</WindowCloseButton>
        </header>

        <div className="sale-sales-history-search">
          <label>
            <span>{t("stock.search.article")}</span>
            <span className="sale-sales-history-search-field">
              <input
                ref={inputRef}
                autoFocus={!initialProduct}
                aria-label={t("sale.searchDialog.query")}
                aria-controls={results.length ? resultsId : undefined}
                aria-activedescendant={highlightedProduct ? `${resultsId}-${highlightedProduct.id}` : undefined}
                aria-autocomplete="list"
                placeholder={t("sale.searchDialog.query")}
                value={query}
                onChange={(event) => {
                  setQuery(event.currentTarget.value);
                  setSelectedProduct(null);
                  setActiveIndex(null);
                }}
                onKeyDown={handleSearchKeyDown}
              />
              {query && (
                <button type="button" aria-label={t("sale.touch.keyboard.clear")} title={t("sale.touch.keyboard.clear")}
                  onClick={() => {
                    setQuery("");
                    setSelectedProduct(null);
                    setActiveIndex(null);
                    inputRef.current?.focus();
                  }}>
                  <X size={18} aria-hidden="true" />
                </button>
              )}
            </span>
          </label>
          {selectedProduct && (
            <div className="sale-sales-history-product">
              <div>
                <span>{t("stock.column.code")}: {selectedProduct.code ?? selectedProduct.barcode ?? selectedProduct.barcode2 ?? "—"}</span>
                <strong title={selectedProduct.name ?? ""}>{selectedProduct.name ?? t("sale.main.unnamedProduct")}</strong>
              </div>
            </div>
          )}
        </div>

        {!selectedProduct && results.length > 0 && (
          <div className="sale-sales-history-results" id={resultsId} role="listbox" aria-label={t("sale.searchDialog.title")}
            onKeyDown={(event) => handleResultKeyDown(event, true)}>
            {results.map((product, index) => (
              <button
                type="button"
                role="option"
                id={`${resultsId}-${product.id}`}
                aria-selected={index === highlightedIndex}
                tabIndex={index === highlightedIndex ? 0 : -1}
                key={product.id}
                ref={(node) => {
                  if (node) resultRefs.current.set(product.id, node);
                  else resultRefs.current.delete(product.id);
                }}
                onFocus={() => setActiveIndex(index)}
                onMouseEnter={() => setActiveIndex(index)}
                onClick={() => selectProduct(product)}
              >
                <span>{product.code ?? product.barcode ?? product.barcode2 ?? "—"}</span>
                <strong>{product.name ?? t("sale.main.unnamedProduct")}</strong>
              </button>
            ))}
          </div>
        )}

        {!selectedProduct && results.length === 0 && (
          <div className="sale-sales-history-empty">
            <strong>{query.trim() ? t("sale.main.noProducts") : t("stock.history.searchPrompt")}</strong>
            <span>{t("stock.history.searchHelp")}</span>
          </div>
        )}

        {selectedProduct && (
          <div className="sale-sales-history-panel-wrap">
            <StockSalesHistoryPanel
              productId={selectedProduct.id}
              productCode={selectedProduct.code ?? selectedProduct.barcode ?? selectedProduct.barcode2 ?? ""}
              productName={selectedProduct.name ?? selectedProduct.code ?? ""}
              productType={selectedProduct.productType}
              productImageSource={imageSource}
              showProductHeading={false}
              locale={locale}
              app={app}
              username={username}
              accessToken={accessToken}
              onClose={onClose}
            />
          </div>
        )}

        <footer className="sale-action-buttons erp-dialog-actions-row">
          <DialogDismissButton className="erp-dialog-action-cancel erp-dialog-dismiss" type="button" onClick={onClose}>{t("common.close")}</DialogDismissButton>
        </footer>
      </section>
    </div>
  );
}
