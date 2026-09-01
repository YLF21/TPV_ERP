import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { apiRequest, type LocaleCode } from "@tpverp/app-common";
import { pdaPriceLookupPath, pdaStockLookupPath } from "./PdaProductLookup";
import {
  buildReplenishmentSuggestions,
  hasAtMostThreeDecimals,
  numeric,
  pdaReplenishmentPagePath,
  suggestedReplenishmentWarehouses,
  type ReplenishmentSuggestion,
  type StockItem,
  type StockPageItem,
  type WarehouseOption
} from "./PdaReplenishmentUtils";
import { PhysicalScannerStatus, usePhysicalScanner } from "./usePhysicalScanner";

type ProductResult = { productId: string; code: string; name: string };
type PagedResult<T> = { items: T[]; nextCursor?: string | null; hasMore: boolean };
type TransferResult = {
  sourceWarehouseId: string;
  targetWarehouseId: string;
  sourceQuantity: number | string;
  targetQuantity: number | string;
};
type BatchTransferResult = { batchId: string; transfers: Array<TransferResult & { productId: string }> };

function updateInventoryStock(items: StockPageItem[], productId: string, result: TransferResult) {
  return items.map((item) => {
    if (item.product.id !== productId) return item;
    const values = new Map(item.stock.map((stockItem) => [stockItem.warehouseId, stockItem]));
    values.set(result.sourceWarehouseId, { productId, warehouseId: result.sourceWarehouseId, quantity: result.sourceQuantity });
    values.set(result.targetWarehouseId, { productId, warehouseId: result.targetWarehouseId, quantity: result.targetQuantity });
    return { ...item, stock: [...values.values()] };
  });
}

export function PdaReplenishment({ token, locale, warehouses, t }: {
  token?: string;
  locale: LocaleCode;
  warehouses: WarehouseOption[];
  t: (key: string) => string;
}) {
  const activeWarehouses = useMemo(() => warehouses.filter((warehouse) => warehouse.active !== false), [warehouses]);
  const [identifier, setIdentifier] = useState("");
  const [product, setProduct] = useState<ProductResult | null>(null);
  const [stock, setStock] = useState<StockItem[]>([]);
  const [inventoryItems, setInventoryItems] = useState<StockPageItem[]>([]);
  const [selectedProductIds, setSelectedProductIds] = useState<Set<string>>(() => new Set());
  const [sourceWarehouseId, setSourceWarehouseId] = useState("");
  const [targetWarehouseId, setTargetWarehouseId] = useState("");
  const [quantity, setQuantity] = useState("1");
  const [busy, setBusy] = useState(false);
  const [loadingSuggestions, setLoadingSuggestions] = useState(false);
  const [saving, setSaving] = useState(false);
  const [bulkSaving, setBulkSaving] = useState(false);
  const [bulkReviewOpen, setBulkReviewOpen] = useState(false);
  const [error, setError] = useState("");
  const [suggestionsError, setSuggestionsError] = useState("");
  const [status, setStatus] = useState("");
  const inputRef = useRef<HTMLInputElement | null>(null);
  const bulkConfirmRef = useRef<HTMLButtonElement | null>(null);
  const number = useMemo(() => new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", { maximumFractionDigits: 3 }), [locale]);
  const warehouseName = useCallback((id: string) => {
    const warehouse = activeWarehouses.find((item) => item.id === id);
    return warehouse?.name ?? warehouse?.nombre ?? id;
  }, [activeWarehouses]);
  const balance = (warehouseId: string) => numeric(stock.find((item) => item.warehouseId === warehouseId)?.quantity);

  useEffect(() => {
    setTargetWarehouseId((current) => activeWarehouses.some((warehouse) => warehouse.id === current)
      ? current
      : activeWarehouses.find((warehouse) => warehouse.defaultWarehouse)?.id ?? activeWarehouses[0]?.id ?? "");
  }, [activeWarehouses]);

  const loadSuggestions = useCallback(async () => {
    if (!token) return;
    setLoadingSuggestions(true);
    setSuggestionsError("");
    try {
      const loaded: StockPageItem[] = [];
      let cursor: string | null | undefined;
      const visited = new Set<string>();
      do {
        const page = await apiRequest<PagedResult<StockPageItem>>(pdaReplenishmentPagePath(cursor), { token });
        loaded.push(...page.items);
        const next = page.hasMore ? page.nextCursor : null;
        if (!next || visited.has(next)) {
          cursor = null;
        } else {
          visited.add(next);
          cursor = next;
        }
      } while (cursor);
      setInventoryItems(loaded);
    } catch {
      setSuggestionsError(t("pda.replenishment.listError"));
    } finally {
      setLoadingSuggestions(false);
    }
  }, [t, token]);

  useEffect(() => { void loadSuggestions(); }, [loadSuggestions]);

  const suggestions = useMemo(
    () => buildReplenishmentSuggestions(inventoryItems, activeWarehouses, targetWarehouseId),
    [activeWarehouses, inventoryItems, targetWarehouseId]
  );
  const selectableSuggestions = suggestions.filter((suggestion) => suggestion.suggestedQuantity > 0);
  const selectedSuggestions = selectableSuggestions.filter((suggestion) => selectedProductIds.has(suggestion.product.id));

  useEffect(() => {
    const available = new Set(selectableSuggestions.map((suggestion) => suggestion.product.id));
    setSelectedProductIds((current) => new Set([...current].filter((id) => available.has(id))));
  }, [inventoryItems, targetWarehouseId]);

  useEffect(() => {
    if (bulkReviewOpen) window.setTimeout(() => bulkConfirmRef.current?.focus(), 0);
  }, [bulkReviewOpen]);

  function chooseSuggestion(suggestion: ReplenishmentSuggestion) {
    setProduct({ productId: suggestion.product.id, code: suggestion.product.code ?? "", name: suggestion.product.name ?? suggestion.product.code ?? suggestion.product.id });
    setStock(suggestion.stock);
    setSourceWarehouseId(suggestion.sourceWarehouseId);
    setTargetWarehouseId(suggestion.targetWarehouseId);
    setQuantity(String(suggestion.suggestedQuantity));
    setError("");
    setStatus("");
    window.setTimeout(() => document.querySelector(".pda-replenishment-card")?.scrollIntoView({ behavior: "smooth", block: "start" }), 0);
  }

  function toggleSuggestion(productId: string) {
    setSelectedProductIds((current) => {
      const next = new Set(current);
      if (next.has(productId)) next.delete(productId); else next.add(productId);
      return next;
    });
  }

  async function searchIdentifier(scannedIdentifier: string, manualSignal = false) {
    if (!token || !scannedIdentifier.trim()) return false;
    setBusy(true);
    setError("");
    setStatus("");
    try {
      const found = await apiRequest<ProductResult>(pdaPriceLookupPath(scannedIdentifier.trim()), { token });
      const stockValues = await apiRequest<StockItem[]>(pdaStockLookupPath(found.productId), { token });
      const suggestion = suggestedReplenishmentWarehouses(stockValues, activeWarehouses, targetWarehouseId);
      setProduct(found);
      setStock(stockValues);
      setSourceWarehouseId(suggestion.sourceId);
      setTargetWarehouseId(suggestion.targetId);
      setIdentifier("");
      setQuantity("1");
      if (manualSignal) navigator.vibrate?.(60);
      return true;
    } catch {
      setProduct(null);
      setStock([]);
      setError(t("pda.replenishment.notFound"));
      if (manualSignal) navigator.vibrate?.([100, 60, 100]);
      return false;
    } finally {
      setBusy(false);
      window.setTimeout(() => inputRef.current?.focus(), 0);
    }
  }

  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!identifier.trim()) return;
    await searchIdentifier(identifier, true);
  }
  async function createTransfer(productId: string, sourceId: string, targetId: string, transferQuantity: number) {
    if (!token) throw new Error("missing_token");
    return apiRequest<TransferResult>("/stock/transfers", {
      token,
      body: { productId, sourceWarehouseId: sourceId, targetWarehouseId: targetId, quantity: transferQuantity }
    });
  }

  async function transfer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!token || !product) return;
    setError("");
    setStatus("");
    const parsedQuantity = Number(quantity);
    if (!Number.isFinite(parsedQuantity) || parsedQuantity <= 0 || !hasAtMostThreeDecimals(parsedQuantity)) {
      setError(t("pda.replenishment.quantityInvalid"));
      return;
    }
    if (!sourceWarehouseId || !targetWarehouseId || sourceWarehouseId === targetWarehouseId) {
      setError(t("pda.replenishment.sameWarehouse"));
      return;
    }
    setSaving(true);
    try {
      const result = await createTransfer(product.productId, sourceWarehouseId, targetWarehouseId, parsedQuantity);
      setStock((current) => updateInventoryStock([{ product: { id: product.productId }, stock: current }], product.productId, result)[0]?.stock ?? current);
      setInventoryItems((current) => updateInventoryStock(current, product.productId, result));
      setStatus(t("pda.replenishment.completed"));
      setQuantity("1");
      navigator.vibrate?.([60, 40, 60]);
    } catch {
      setError(t("pda.replenishment.transferError"));
      navigator.vibrate?.([100, 60, 100]);
    } finally {
      setSaving(false);
    }
  }

  async function confirmSelectedTransfers() {
    if (!token || selectedSuggestions.length === 0) return;
    setBulkSaving(true);
    setError("");
    setStatus("");
    try {
      const result = await apiRequest<BatchTransferResult>("/stock/transfers/batch", {
        token,
        body: { transfers: selectedSuggestions.map((suggestion) => ({
          productId: suggestion.product.id,
          sourceWarehouseId: suggestion.sourceWarehouseId,
          targetWarehouseId: suggestion.targetWarehouseId,
          quantity: suggestion.suggestedQuantity
        })) }
      });
      setInventoryItems((current) => result.transfers.reduce((values, item) => updateInventoryStock(values, item.productId, item), current));
      setSelectedProductIds(new Set());
      setBulkReviewOpen(false);
      setStatus(t("pda.replenishment.bulkCompleted").replace("{count}", String(result.transfers.length)));
      navigator.vibrate?.([60, 40, 60]);
    } catch {
      setError(t("pda.replenishment.bulkAtomicError"));
      navigator.vibrate?.([100, 60, 100]);
    } finally {
      setBulkSaving(false);
    }
  }

  const physicalScanner = usePhysicalScanner({
    enabled: Boolean(token && !busy && !saving && !bulkSaving),
    locale,
    onScan: (value) => searchIdentifier(value),
    duplicateWindowMs: 1200
  });

  return <section className="pda-replenishment">
    <header><span>{t("pda.replenishment.eyebrow")}</span><h2>{t("pda.replenishment.title")}</h2><p>{t("pda.replenishment.subtitle")}</p></header>
    <section className="pda-replenishment-suggestions">
      <header>
        <div><h3>{t("pda.replenishment.suggestions")}</h3><p>{t("pda.replenishment.suggestionsHelp")}</p></div>
        <label><span>{t("pda.replenishment.destination")}</span><select value={targetWarehouseId} onChange={(event) => setTargetWarehouseId(event.target.value)}>{activeWarehouses.map((warehouse) => <option key={warehouse.id} value={warehouse.id}>{warehouseName(warehouse.id)}</option>)}</select></label>
        <button type="button" className="secondary" disabled={loadingSuggestions} onClick={() => void loadSuggestions()}>{loadingSuggestions ? t("common.loading") : t("pda.replenishment.refresh")}</button>
      </header>
      {suggestionsError && <p className="pda-replenishment-error" role="alert">{suggestionsError}</p>}
      {!suggestionsError && loadingSuggestions && <div className="pda-replenishment-list-empty">{t("common.loading")}</div>}
      {!suggestionsError && !loadingSuggestions && suggestions.length === 0 && <div className="pda-replenishment-list-empty">{t("pda.replenishment.none")}</div>}
      {suggestions.length > 0 && <>
        <div className="pda-replenishment-list-summary"><strong>{suggestions.length} {t("pda.replenishment.products")}</strong><button type="button" onClick={() => setSelectedProductIds(new Set(selectableSuggestions.map((suggestion) => suggestion.product.id)))}>{t("pda.replenishment.selectAll")}</button></div>
        <div className="pda-replenishment-list">
          {suggestions.map((suggestion) => <article className={suggestion.suggestedQuantity <= 0 ? "unavailable" : ""} key={suggestion.product.id}>
            <label className="pda-replenishment-check"><input type="checkbox" checked={selectedProductIds.has(suggestion.product.id)} disabled={suggestion.suggestedQuantity <= 0} onChange={() => toggleSuggestion(suggestion.product.id)} /><span className="sr-only">{t("pda.replenishment.select")}</span></label>
            <button type="button" className="pda-replenishment-item" disabled={suggestion.suggestedQuantity <= 0} onClick={() => chooseSuggestion(suggestion)}>
              <span className="code">{suggestion.product.code}</span><strong>{suggestion.product.name}</strong>
              <span>{t("pda.replenishment.current")}: <b>{number.format(suggestion.currentQuantity)}</b></span>
              <span>{t("pda.replenishment.minimum")}: <b>{number.format(suggestion.minimumQuantity)}</b></span>
              <span>{t("pda.replenishment.maximum")}: <b>{number.format(suggestion.targetQuantity)}</b></span>
              <span>{t("pda.replenishment.suggested")}: <b>{number.format(suggestion.suggestedQuantity)}</b></span>
              <small>{suggestion.suggestedQuantity > 0 ? `${t("pda.replenishment.from")} ${warehouseName(suggestion.sourceWarehouseId)}` : t("pda.replenishment.noSourceStock")}</small>
            </button>
          </article>)}
        </div>
        <footer><button type="button" disabled={bulkSaving || selectedSuggestions.length === 0} onClick={() => setBulkReviewOpen(true)}>{bulkSaving ? t("common.loading") : `${t("pda.replenishment.replenishSelected")} (${selectedSuggestions.length})`}</button></footer>
      </>}
    </section>
    {bulkReviewOpen && <div className="pda-replenishment-dialog-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !bulkSaving) setBulkReviewOpen(false); }}>
      <section className="pda-replenishment-dialog" role="dialog" aria-modal="true" aria-labelledby="pda-bulk-review-title" aria-describedby="pda-bulk-review-help">
        <header><h3 id="pda-bulk-review-title">{t("pda.replenishment.bulkReviewTitle")}</h3><p id="pda-bulk-review-help">{t("pda.replenishment.bulkReviewHelp")}</p></header>
        <ul>{selectedSuggestions.map((suggestion) => <li key={suggestion.product.id}><span><b>{suggestion.product.code}</b>{suggestion.product.name}</span><strong>{number.format(suggestion.suggestedQuantity)}</strong><small>{warehouseName(suggestion.sourceWarehouseId)} → {warehouseName(suggestion.targetWarehouseId)}</small></li>)}</ul>
        <footer>
          <button type="button" className="secondary" disabled={bulkSaving} onClick={() => setBulkReviewOpen(false)}>{t("pda.replenishment.bulkCancel")}</button>
          <button ref={bulkConfirmRef} type="button" disabled={bulkSaving} onClick={() => void confirmSelectedTransfers()}>{bulkSaving ? t("common.loading") : t("pda.replenishment.bulkConfirm")}</button>
        </footer>
      </section>
    </div>}
    <form className="pda-replenishment-search" onSubmit={search}>
      <label><span>{t("pda.replenishment.manualScan")}</span><input ref={inputRef} data-physical-scanner-input value={identifier} disabled={busy} onChange={(event) => setIdentifier(event.target.value)} /></label>
      <button type="submit" disabled={busy || !identifier.trim()}>{busy ? t("common.loading") : t("pda.replenishment.scan")}</button>
    </form>
    <PhysicalScannerStatus {...physicalScanner} />
    {error && <p className="pda-replenishment-error" role="alert">{error}</p>}
    {status && <p className="pda-replenishment-status" role="status">{status}</p>}
    {product && <article className="pda-replenishment-card">
      <header><span>{product.code}</span><h3>{product.name}</h3></header>
      <section className="pda-replenishment-stock">
        {activeWarehouses.map((warehouse) => <div key={warehouse.id}><span>{warehouseName(warehouse.id)}</span><strong>{number.format(balance(warehouse.id))}</strong></div>)}
      </section>
      <form onSubmit={transfer}>
        <label><span>{t("pda.replenishment.source")}</span><select value={sourceWarehouseId} onChange={(event) => setSourceWarehouseId(event.target.value)}>{activeWarehouses.map((warehouse) => <option key={warehouse.id} value={warehouse.id}>{warehouseName(warehouse.id)} · {number.format(balance(warehouse.id))}</option>)}</select></label>
        <label><span>{t("pda.replenishment.target")}</span><select value={targetWarehouseId} onChange={(event) => setTargetWarehouseId(event.target.value)}>{activeWarehouses.map((warehouse) => <option key={warehouse.id} value={warehouse.id}>{warehouseName(warehouse.id)} · {number.format(balance(warehouse.id))}</option>)}</select></label>
        <label><span>{t("pda.replenishment.quantity")}</span><input type="number" inputMode="decimal" min="0.001" step="0.001" value={quantity} onChange={(event) => setQuantity(event.target.value)} /></label>
        <button type="submit" disabled={saving || activeWarehouses.length < 2}>{saving ? t("common.loading") : t("pda.replenishment.confirm")}</button>
      </form>
    </article>}
  </section>;
}
