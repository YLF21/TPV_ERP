import { useMemo, useRef, useState, type KeyboardEvent } from "react";
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

function SalesHistoryProductImage({ product, imageSource }: { product: SaleProduct; imageSource: string }) {
  const productName = product.name?.trim() || "Producto";

  return (
    <div className="sale-sales-history-product-image" aria-hidden="true">
      {imageSource
        ? <img src={imageSource} alt="" />
        : <span>{productName.slice(0, 1).toLocaleUpperCase()}</span>}
    </div>
  );
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
  const inputRef = useRef<HTMLInputElement>(null);
  const { imageSource } = useProductInformationResources({
    productId: selectedProduct?.id ?? "",
    imageId: selectedProduct?.imageId,
    token: accessToken,
    canReadSuppliers: false,
  });
  const results = useMemo(
    () => query.trim() && !selectedProduct
      ? products.filter((product) => matchesProduct(product, query)).slice(0, 12)
      : [],
    [products, query, selectedProduct],
  );

  function selectProduct(product: SaleProduct | undefined) {
    if (!product) return;
    setSelectedProduct(product);
    setQuery(product.code ?? product.barcode ?? product.barcode2 ?? product.name ?? "");
  }

  function handleSearchKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key !== "Enter") return;
    event.preventDefault();
    selectProduct(exactProduct(products, query) ?? results[0]);
  }

  return (
    <div className="sale-action-overlay sale-sales-history-overlay" role="presentation">
      <section
        className="sale-action-dialog sale-sales-history-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={t("stock.history.title")}
      >
        <header>
          <h2>{t("stock.history.title")}</h2>
          <button type="button" aria-label={t("common.close")} onClick={onClose}>×</button>
        </header>

        <div className="sale-sales-history-search">
          <label>
            <span>{t("sale.searchDialog.query")}</span>
            <input
              ref={inputRef}
              autoFocus={!initialProduct}
              value={query}
              onChange={(event) => {
                setQuery(event.currentTarget.value);
                setSelectedProduct(null);
              }}
              onKeyDown={handleSearchKeyDown}
            />
          </label>
          {selectedProduct && (
            <div className="sale-sales-history-product">
              <SalesHistoryProductImage product={selectedProduct} imageSource={imageSource} />
              <div>
                <span>{selectedProduct.code ?? selectedProduct.barcode ?? selectedProduct.barcode2 ?? "—"}</span>
                <strong title={selectedProduct.name ?? ""}>{selectedProduct.name ?? t("sale.main.unnamedProduct")}</strong>
              </div>
            </div>
          )}
        </div>

        {!selectedProduct && results.length > 0 && (
          <div className="sale-sales-history-results" role="listbox" aria-label={t("sale.searchDialog.title")}>
            {results.map((product) => (
              <button
                type="button"
                role="option"
                aria-selected="false"
                key={product.id}
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
              locale={locale}
              app={app}
              username={username}
              accessToken={accessToken}
              onClose={onClose}
            />
          </div>
        )}

        <footer className="sale-action-buttons">
          <button type="button" onClick={onClose}>{t("common.close")}</button>
        </footer>
      </section>
    </div>
  );
}
