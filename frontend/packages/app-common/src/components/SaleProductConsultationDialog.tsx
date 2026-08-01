import { useEffect, useMemo, useState } from "react";
import { apiRequest } from "../api/client";
import type { SaleProduct } from "./SaleScreen";
import { useProductInformationResources } from "./productInformationResources";

type StockPage = {
  items?: Array<{
    product?: { id?: string };
    stock?: Array<{ quantity?: number | string | null }>;
  }>;
};

type Props = {
  products: SaleProduct[];
  initialProduct?: SaleProduct | null;
  token?: string;
  onClose: () => void;
};

function matches(product: SaleProduct, query: string) {
  const value = query.trim().toLocaleLowerCase();
  if (!value) return true;
  return [product.code, product.barcode, product.barcode2, product.name]
    .some((candidate) => candidate?.toLocaleLowerCase().includes(value));
}

function money(value: unknown) {
  if (value === null || value === undefined || value === "") return "—";
  const number = Number(value);
  if (!Number.isFinite(number)) return "—";
  return number.toLocaleString("es-ES", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
}

function SaleConsultationProductImage({ product, token }: { product: SaleProduct; token?: string }) {
  const { imageSource } = useProductInformationResources({
    productId: product.id,
    imageId: product.imageId,
    token,
    canReadSuppliers: false,
  });
  const name = product.name?.trim() || "Producto";

  return (
    <div className="sale-consultation-product-image">
      {imageSource
        ? <img src={imageSource} alt={`Imagen de ${name}`} />
        : <span className="sale-consultation-image-placeholder" aria-label="Producto sin imagen">
            {name.slice(0, 1).toLocaleUpperCase()}
          </span>}
    </div>
  );
}

export function SaleProductConsultationDialog({
  products,
  initialProduct = null,
  token,
  onClose,
}: Props) {
  const [query, setQuery] = useState(initialProduct?.code ?? "");
  const [selected, setSelected] = useState<SaleProduct | null>(initialProduct);
  const [stock, setStock] = useState<number | null>(null);
  const [stockError, setStockError] = useState("");
  const results = useMemo(() => products.filter((product) => matches(product, query)).slice(0, 30), [products, query]);

  useEffect(() => {
    if (!selected) {
      setStock(null);
      setStockError("");
      return;
    }
    let active = true;
    const search = selected.code ?? selected.barcode ?? selected.barcode2 ?? selected.id;
    apiRequest<StockPage>(`/stock/page?limit=100&search=${encodeURIComponent(search)}`, { token })
      .then((page) => {
        if (!active) return;
        const item = page.items?.find((candidate) => candidate.product?.id === selected.id);
        const total = item?.stock?.reduce((sum, row) => sum + Number(row.quantity ?? 0), 0) ?? 0;
        setStock(total);
        setStockError("");
      })
      .catch((error) => {
        if (!active) return;
        setStock(null);
        setStockError(error instanceof Error ? error.message : "No se pudo consultar el stock");
      });
    return () => { active = false; };
  }, [selected, token]);

  function choose(product: SaleProduct | undefined) {
    if (!product) return;
    setSelected(product);
    setQuery(product.code ?? product.barcode ?? product.barcode2 ?? product.name ?? "");
  }

  return (
    <div className="sale-action-overlay" role="presentation">
      <section className="sale-action-dialog wide sale-product-consultation" role="dialog" aria-modal="true"
        aria-label="Consulta de stock">
        <header>
          <h2>Consulta de stock</h2>
          <button type="button" aria-label="Cerrar" onClick={onClose}>×</button>
        </header>
        <div className="sale-consultation-search">
          <label>
            Código, código de barras o nombre
            <input
              autoFocus
              value={query}
              onChange={(event) => {
                setQuery(event.target.value);
                setSelected(null);
              }}
              onKeyDown={(event) => {
                if (event.key === "Escape") onClose();
                if (event.key === "Enter") {
                  event.preventDefault();
                  choose(results[0]);
                }
              }}
            />
          </label>
        </div>

        <div className="sale-consultation-content">
          {!selected && (
            <div className="sale-consultation-results" role="listbox" aria-label="Productos">
              {results.map((product) => (
                <button type="button" role="option" aria-selected="false" key={product.id} onClick={() => choose(product)}>
                  <span>{product.code ?? product.barcode ?? product.barcode2 ?? "—"}</span>
                  <strong>{product.name ?? "Producto sin nombre"}</strong>
                  <b>{money(product.salePrice)} €</b>
                </button>
              ))}
              {results.length === 0 && <p>No se encontraron productos.</p>}
            </div>
          )}
          {selected && (
            <article className="sale-consultation-product">
              <SaleConsultationProductImage product={selected} token={token} />
              <div className="sale-consultation-product-data">
                <p className="sale-consultation-eyebrow">Producto consultado</p>
                <h3 title={selected.name ?? ""}>{selected.name ?? "Producto sin nombre"}</h3>
                <div className="sale-consultation-metrics">
                  <section>
                    <span>Precio de venta</span>
                    <strong>{money(selected.salePrice)} <small>€</small></strong>
                  </section>
                  <section className={stock == null ? "loading" : stock < 0 ? "negative" : stock === 0 ? "empty" : "positive"}>
                    <span>Stock disponible</span>
                    <strong aria-live="polite">{stock == null ? "Consultando…" : money(stock)}</strong>
                  </section>
                </div>
                <dl className="sale-consultation-metadata">
                  <div><dt>Código</dt><dd>{selected.code ?? selected.barcode ?? selected.barcode2 ?? "—"}</dd></div>
                  <div><dt>Cantidad por paquete</dt><dd>{money(selected.packageQuantity ?? 1)}</dd></div>
                </dl>
              </div>
            </article>
          )}
          {stockError && <p className="sale-action-error" role="alert">{stockError}</p>}
        </div>

        <footer className="sale-action-buttons"><button type="button" onClick={onClose}>Cerrar</button></footer>
      </section>
    </div>
  );
}
