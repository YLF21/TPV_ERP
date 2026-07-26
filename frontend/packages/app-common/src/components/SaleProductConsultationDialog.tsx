import { useEffect, useMemo, useState } from "react";
import { apiRequest } from "../api/client";
import type { SaleProduct } from "./SaleScreen";

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
  return Number(value ?? 0).toLocaleString("es-ES", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
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
        {!selected && (
          <div className="sale-consultation-results" role="listbox" aria-label="Productos">
            {results.map((product) => (
              <button type="button" role="option" aria-selected="false" key={product.id} onClick={() => choose(product)}>
                <strong>{product.name ?? "Producto sin nombre"}</strong>
                <span>{product.code ?? product.barcode ?? product.barcode2 ?? "—"}</span>
              </button>
            ))}
          </div>
        )}
        {selected && (
          <dl className="sale-consultation-summary">
            <div><dt>Producto</dt><dd>{selected.name ?? "—"}</dd></div>
            <div><dt>Código</dt><dd>{selected.code ?? selected.barcode ?? selected.barcode2 ?? "—"}</dd></div>
            <div><dt>Precio</dt><dd>{money(selected.salePrice)} €</dd></div>
            <div><dt>Stock</dt><dd>{stock == null ? "Consultando…" : money(stock)}</dd></div>
            <div><dt>Cantidad por paquete</dt><dd>{money(selected.packageQuantity ?? 1)}</dd></div>
          </dl>
        )}
        {stockError && <p className="sale-action-error" role="alert">{stockError}</p>}
        <div className="sale-action-buttons"><button type="button" onClick={onClose}>Cerrar</button></div>
      </section>
    </div>
  );
}
