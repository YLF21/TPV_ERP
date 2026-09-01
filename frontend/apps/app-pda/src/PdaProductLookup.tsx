import { useMemo, useRef, useState, type FormEvent } from "react";
import {
  apiRequest,
  getHardwareBridge,
  productLabelEanBits,
  type HardwareConfig,
  type LocaleCode,
  type ProductLabelPrintRequest,
  type ProductLabelProfile
} from "@tpverp/app-common";
import { PhysicalScannerStatus, usePhysicalScanner } from "./usePhysicalScanner";

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
export type PdaLabelTemplate = "40x30" | "50x30" | "58x40";
export type PdaLabelJob = {
  id: string;
  productId: string;
  code: string;
  name: string;
  price: number;
  barcode: string;
  copies: number;
};

export function pdaExpandLabelJobs(jobs: PdaLabelJob[]) {
  return jobs.flatMap((job) => Array.from({ length: job.copies }, (_, copyIndex) => ({ ...job, copyIndex })));
}

const LABEL_SIZE: Record<PdaLabelTemplate, { widthMm: number; heightMm: number }> = {
  "40x30": { widthMm: 40, heightMm: 30 },
  "50x30": { widthMm: 50, heightMm: 30 },
  "58x40": { widthMm: 58, heightMm: 40 }
};

export function pdaLabelProfile(template: PdaLabelTemplate, config: HardwareConfig): ProductLabelProfile {
  const size = LABEL_SIZE[template];
  const configured = config.productLabelProfiles.find((profile) =>
    profile.widthMm === size.widthMm && profile.heightMm === size.heightMm)
    ?? config.productLabelProfiles.find((profile) => profile.id === config.defaultProductLabelProfileId);
  return {
    id: configured?.id ?? `pda-${template}`,
    name: configured?.name ?? `PDA ${template}`,
    destination: configured?.destination ?? "LABEL_PRINTER",
    printerName: configured?.printerName ?? "",
    widthMm: size.widthMm,
    heightMm: size.heightMm,
    orientation: configured?.orientation ?? "PORTRAIT",
    marginTopMm: configured?.marginTopMm ?? 1,
    marginRightMm: configured?.marginRightMm ?? 1,
    marginBottomMm: configured?.marginBottomMm ?? 1,
    marginLeftMm: configured?.marginLeftMm ?? 1,
    horizontalGapMm: configured?.horizontalGapMm ?? 0,
    verticalGapMm: configured?.verticalGapMm ?? 0,
    copies: 1,
    showStoreName: configured?.showStoreName ?? true
  };
}

export function pdaLabelPrintRequest(
  jobs: PdaLabelJob[], template: PdaLabelTemplate, config: HardwareConfig, storeName: string
): ProductLabelPrintRequest {
  return {
    version: 2,
    kind: "SEQUENTIAL",
    storeName,
    profile: pdaLabelProfile(template, config),
    items: jobs.map((job) => ({
      id: job.id,
      product: { name: job.name, code: job.code, barcode: job.barcode, price: job.price },
      copies: job.copies
    }))
  };
}

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
  const [labelTemplate, setLabelTemplate] = useState<PdaLabelTemplate>("50x30");
  const [labelQueue, setLabelQueue] = useState<PdaLabelJob[]>([]);
  const [printSelection, setPrintSelection] = useState<"current" | "queue">("current");
  const [printHistory, setPrintHistory] = useState<string[]>([]);
  const [printError, setPrintError] = useState("");
  const [printBusy, setPrintBusy] = useState(false);
  const [fallbackSelection, setFallbackSelection] = useState<"current" | "queue" | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const inputRef = useRef<HTMLInputElement | null>(null);
  const number = useMemo(() => new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", { maximumFractionDigits: 3 }), [locale]);
  const currency = useMemo(() => new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", { style: "currency", currency: "EUR" }), [locale]);
  const totalStock = stock.reduce((total, item) => total + Number(item.quantity), 0);

  async function searchIdentifier(scannedIdentifier: string) {
    const normalizedIdentifier = scannedIdentifier.trim();
    if (!token || !normalizedIdentifier || busy) return false;
    setBusy(true);
    setError("");
    try {
      const price = await apiRequest<PriceResult>(pdaPriceLookupPath(normalizedIdentifier), { token });
      const [stockValues, product] = await Promise.all([
        apiRequest<StockItem[]>(pdaStockLookupPath(price.productId), { token }),
        apiRequest<ProductDetail>(pdaProductPath(price.productId), { token })
      ]);
      setResult(price);
      setStock(stockValues);
      setBarcode(pdaPrintableBarcode(product, normalizedIdentifier));
      setCopies(1);
      setIdentifier("");
      navigator.vibrate?.(60);
      return true;
    } catch {
      setResult(null);
      setStock([]);
      setBarcode("");
      setError(t("pda.lookup.notFound"));
      navigator.vibrate?.([100, 60, 100]);
      return false;
    } finally {
      setBusy(false);
      window.setTimeout(() => inputRef.current?.focus(), 0);
    }
  }

  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await searchIdentifier(identifier);
  }

  const physicalScanner = usePhysicalScanner({
    enabled: Boolean(token && !busy),
    locale,
    onScan: searchIdentifier,
    duplicateWindowMs: 1200
  });

  function updateCopies(value: string) {
    const parsed = Math.round(Number(value));
    setCopies(Number.isFinite(parsed) ? Math.min(99, Math.max(1, parsed)) : 1);
  }

  function currentLabelJob(): PdaLabelJob | null {
    if (!result || !barcode) return null;
    return {
      id: `${result.productId}-${Date.now()}`,
      productId: result.productId,
      code: result.code,
      name: result.name,
      price: Number(result.salePrice),
      barcode,
      copies
    };
  }

  function addCurrentToQueue() {
    const job = currentLabelJob();
    if (!job) return;
    setLabelQueue((current) => [...current, job]);
  }

  function browserPrintLabels(selection: "current" | "queue") {
    const jobs = selection === "queue" ? labelQueue : [currentLabelJob()].filter((job): job is PdaLabelJob => Boolean(job));
    if (jobs.length === 0) return;
    setPrintSelection(selection);
    setFallbackSelection(null);
    window.setTimeout(() => window.print(), 0);
  }

  async function printLabels(selection: "current" | "queue") {
    const jobs = selection === "queue" ? labelQueue : [currentLabelJob()].filter((job): job is PdaLabelJob => Boolean(job));
    if (jobs.length === 0 || printBusy) return;
    setPrintError("");
    setFallbackSelection(null);
    setPrintBusy(true);
    try {
      const hardware = getHardwareBridge();
      const config = await hardware.getHardwareConfig();
      const response = await hardware.printProductLabel(
        pdaLabelPrintRequest(jobs, labelTemplate, config, storeName), config
      );
      if (!response.ok) {
        setPrintError(`${t("pda.lookup.nativePrintError")} (${response.code})`);
        setFallbackSelection(selection);
        return;
      }
      if (selection === "queue") setLabelQueue([]);
      setPrintHistory((current) => [
        `${new Date().toLocaleTimeString()} · ${jobs.reduce((total, job) => total + job.copies, 0)} ${t("pda.lookup.labels")}`,
        ...current
      ].slice(0, 5));
    } catch {
      setPrintError(t("pda.lookup.nativePrintError"));
      setFallbackSelection(selection);
    } finally {
      setPrintBusy(false);
    }
  }

  const currentJob = currentLabelJob();
  const printableJobs = printSelection === "queue" ? labelQueue : currentJob ? [currentJob] : [];
  const printableLabels = pdaExpandLabelJobs(printableJobs);
  const queuedCopies = labelQueue.reduce((total, job) => total + job.copies, 0);

  return (
    <section className="pda-lookup">
      <header><span>{t("pda.lookup.eyebrow")}</span><h2>{t("pda.lookup.title")}</h2><p>{t("pda.lookup.subtitle")}</p></header>
      <form onSubmit={search}>
        <label><span>{t("goodsCheck.productCode")}</span><input ref={inputRef} data-physical-scanner-input autoFocus value={identifier} disabled={busy} onChange={(event) => setIdentifier(event.target.value)} /></label>
        <button type="submit" disabled={busy || !identifier.trim()}>{busy ? t("common.loading") : t("pda.lookup.search")}</button>
      </form>
      <PhysicalScannerStatus {...physicalScanner} />
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
          <section className="pda-label-studio">
            <header><div><span>{t("pda.lookup.labelStudio")}</span><strong>{t("pda.lookup.labelStudioHelp")}</strong></div><b>{queuedCopies}</b></header>
            <div className="pda-label-actions">
              <label><span>{t("pda.lookup.labelCopies")}</span><input type="number" inputMode="numeric" min="1" max="99" value={copies} onChange={(event) => updateCopies(event.currentTarget.value)} /></label>
              <label><span>{t("pda.lookup.labelTemplate")}</span><select value={labelTemplate} onChange={(event) => setLabelTemplate(event.currentTarget.value as PdaLabelTemplate)}><option value="40x30">40 × 30 mm</option><option value="50x30">50 × 30 mm</option><option value="58x40">58 × 40 mm</option></select></label>
            </div>
            {barcode && <article className={`pda-label-preview template-${labelTemplate}`}>
              <small>{storeName}</small>
              <strong>{result.name}</strong>
              <div><span>{result.code}</span><b>{currency.format(Number(result.salePrice))}</b></div>
              <ProductBarcode code={barcode} />
              <span>{barcode}</span>
            </article>}
            <div className="pda-label-buttons">
              <button type="button" disabled={!barcode || printBusy} onClick={() => void printLabels("current")}>{printBusy ? t("pda.lookup.printing") : t("pda.lookup.printLabel")}</button>
              <button type="button" className="secondary" disabled={!barcode} onClick={addCurrentToQueue}>{t("pda.lookup.addToQueue")}</button>
              <button type="button" className="secondary" disabled={labelQueue.length === 0 || printBusy} onClick={() => void printLabels("queue")}>{t("pda.lookup.printQueue").replace("{count}", String(queuedCopies))}</button>
              {labelQueue.length > 0 && <button type="button" className="ghost" onClick={() => setLabelQueue([])}>{t("pda.lookup.clearQueue")}</button>}
            </div>
            {printError && <p className="pda-lookup-error" role="alert">{printError}</p>}
            {fallbackSelection && <button type="button" className="secondary" onClick={() => browserPrintLabels(fallbackSelection)}>{t("pda.lookup.browserPrintFallback")}</button>}
            {!barcode && <small>{t("pda.lookup.labelUnavailable")}</small>}
            {labelQueue.length > 0 && <ul className="pda-label-queue">{labelQueue.map((job) => <li key={job.id}><span>{job.name}</span><b>× {job.copies}</b><button type="button" aria-label={t("common.delete")} onClick={() => setLabelQueue((current) => current.filter((item) => item.id !== job.id))}>×</button></li>)}</ul>}
            {printHistory.length > 0 && <div className="pda-label-history"><strong>{t("pda.lookup.printHistory")}</strong>{printHistory.map((entry) => <span key={entry}>{entry}</span>)}</div>}
          </section>
        </article>
        <section className={`pda-label-print-area template-${labelTemplate}`} aria-hidden="true">
          {printableLabels.map((job) => <article className={`pda-print-label template-${labelTemplate}`} key={`${job.id}-${job.copyIndex}`}>
            <small>{storeName}</small>
            <strong className="pda-print-label-name">{job.name}</strong>
            <div className="pda-print-label-meta"><span>{job.code}</span><b>{currency.format(job.price)}</b></div>
            <ProductBarcode code={job.barcode} /><span className="pda-print-label-ean">{job.barcode}</span>
          </article>)}
        </section>
      </>}
    </section>
  );
}
