import { useMemo, useRef, useState, type FormEvent } from "react";
import { apiRequest, productLabelEanBits, type LocaleCode } from "@tpverp/app-common";

type PriceResult = {
  productId: string;
  code: string;
  name: string;
  salePrice: number | string;
  activePriceType: string;
  memberPrice?: number | string | null;
  offerPrice?: number | string | null;
  offerDiscountPercent?: number | string | null;
  offerUntil?: string | null;
};

type ProductDetail = {
  barcode?: string | null;
  barcode2?: string | null;
};

type StockItem = { productId: string; warehouseId: string; quantity: number | string };
type WarehouseOption = { id: string; name?: string | null; nombre?: string | null };

export function pdaPriceLookupPath(identifier: string) {
  return `/products/sale/price-consultation?identifier=${encodeURIComponent(identifier.trim())}`;
}

export function pdaStockLookupPath(productId: string) {
  return `/stock?productId=${encodeURIComponent(productId)}`;
}

export function pdaProductPath(productId: string) {
  return `/products/${encodeURIComponent(productId)}`;
}

export function pdaPrintableBarcode(product: ProductDetail, scannedIdentifier: string) {
  return [product.barcode, product.barcode2, scannedIdentifier.trim()]
    .find((value): value is string => Boolean(value && productLabelEanBits(value))) ?? "";
}

function ProductBarcode({ code }: { code: string }) {
  const bits = productLabelEanBits(code);
  return <svg className="pda-label-barcode" viewBox={`-9 0 ${bits.length + 18} 42`} preserveAspectRatio="none" aria-label={code}>
    {bits.split("").map((value, index) => value === "1"
      ? <rect key={index} x={index} y="0" width="1" height="42" />
      : null)}
  </svg>;
}

export function PdaProductLookup({ token, locale, warehouses, storeName, t }: {
  token?: string;
  locale: LocaleCode;
  warehouses: WarehouseOption[];
  storeName: string;
  t: (key: string) => string;
}) {
  const [identifier, setIdentifier] = useState("");
  const [result, setResult] = useState<PriceResult | null>(null);
  const [stock, setStock] = useState<StockItem[]>([]);
  const [barcode, setBarcode] = useState("");
  const [copies, setCopies] = useState(1);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const inputRef = useRef<HTMLInputElement | null>(null);
  const number = useMemo(() => new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", { maximumFractionDigits: 3 }), [locale]);
  const currency = useMemo(() => new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", { style: "currency", currency: "EUR" }), [locale]);
  const totalStock = stock.reduce((total, item) => total + Number(item.quantity), 0);

  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const scannedIdentifier = identifier.trim();
    if (!token || !scannedIdentifier) return;
    setBusy(true);
    setError("");
    try {
      const price = await apiRequest<PriceResult>(pdaPriceLookupPath(scannedIdentifier), { token });
      const [stockValues, product] = await Promise.all([
        apiRequest<StockItem[]>(pdaStockLookupPath(price.productId), { token }),
        apiRequest<ProductDetail>(pdaProductPath(price.productId), { token })
      ]);
      setResult(price);
      setStock(stockValues);
      setBarcode(pdaPrintableBarcode(product, scannedIdentifier));
      setCopies(1);
      setIdentifier("");
      navigator.vibrate?.(60);
    } catch {
      setResult(null);
      setStock([]);
      setBarcode("");
      setError(t("pda.lookup.notFound"));
      navigator.vibrate?.([100, 60, 100]);
    } finally {
      setBusy(false);
      window.setTimeout(() => inputRef.current?.focus(), 0);
    }
  }

  function updateCopies(value: string) {
    const parsed = Math.round(Number(value));
    setCopies(Number.isFinite(parsed) ? Math.min(99, Math.max(1, parsed)) : 1);
  }

  return (
    <section className="pda-lookup">
      <header><span>{t("pda.lookup.eyebrow")}</span><h2>{t("pda.lookup.title")}</h2><p>{t("pda.lookup.subtitle")}</p></header>
      <form onSubmit={search}>
        <label><span>{t("goodsCheck.productCode")}</span><input ref={inputRef} autoFocus value={identifier} disabled={busy} onChange={(event) => setIdentifier(event.target.value)} /></label>
        <button type="submit" disabled={busy || !identifier.trim()}>{busy ? t("common.loading") : t("pda.lookup.search")}</button>
      </form>
      {error && <p className="pda-lookup-error" role="alert">{error}</p>}
      {!result && !error && <div className="pda-lookup-empty">{t("pda.lookup.scanPrompt")}</div>}
      {result && <>
        <article className="pda-lookup-result">
          <header><span>{result.code}</span><h3>{result.name}</h3></header>
          <div className="pda-lookup-metrics">
            <div className="price"><span>{t("pda.lookup.salePrice")}</span><strong>{currency.format(Number(result.salePrice))}</strong></div>
            <div><span>{t("pda.lookup.totalStock")}</span><strong>{number.format(totalStock)}</strong></div>
          </div>
          <section className="pda-lookup-stock">
            <h4>{t("pda.lookup.byWarehouse")}</h4>
            {stock.map((item) => (
              <div key={item.warehouseId}>
                <span>{warehouses.find((warehouse) => warehouse.id === item.warehouseId)?.name ?? warehouses.find((warehouse) => warehouse.id === item.warehouseId)?.nombre ?? item.warehouseId}</span>
                <strong>{number.format(Number(item.quantity))}</strong>
              </div>
            ))}
            {stock.length === 0 && <p>{t("pda.lookup.noStock")}</p>}
          </section>
          <section className="pda-label-actions">
            <label><span>{t("pda.lookup.labelCopies")}</span><input type="number" inputMode="numeric" min="1" max="99" value={copies} onChange={(event) => updateCopies(event.currentTarget.value)} /></label>
            <button type="button" disabled={!barcode} onClick={() => window.print()}>{t("pda.lookup.printLabel")}</button>
            {!barcode && <small>{t("pda.lookup.labelUnavailable")}</small>}
          </section>
        </article>
        <section className="pda-label-print-area" aria-hidden="true">
          {Array.from({ length: copies }, (_, index) => <article className="pda-print-label" key={index}>
            <small>{storeName}</small>
            <strong className="pda-print-label-name">{result.name}</strong>
            <div className="pda-print-label-meta"><span>{result.code}</span><b>{currency.format(Number(result.salePrice))}</b></div>
            {barcode && <><ProductBarcode code={barcode} /><span className="pda-print-label-ean">{barcode}</span></>}
          </article>)}
        </section>
      </>}
    </section>
  );
}