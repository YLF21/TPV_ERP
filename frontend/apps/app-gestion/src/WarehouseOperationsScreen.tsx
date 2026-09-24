import { AdjustmentProductSearch } from "./AdjustmentProductSearch";
import { StockAdjustmentHistoryTable } from "./StockAdjustmentHistoryTable";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { UserSession } from "../../../packages/app-common/src/types";
import { ErpSelect } from "../../../packages/app-common/src/components/ErpSelect";
import { ErpFilterChips } from "../../../packages/app-common/src/components/ErpFilterChips";
import {
  activateModalFocusTrap,
  type ModalFocusRoot
} from "../../../packages/app-common/src/components/modalFocusTrap";
import {
  cancelStockCount,
  confirmStockCount,
  createStockAdjustment,
  createStockCount,
  createStockTransfer,
  exportStockCount,
  loadStockAdjustmentHistory,
  loadStockBalance,
  loadStockCount,
  loadStockCounts,
  loadWarehouseOptions,
  loadWarehouseOperationResources,
  updateStockCountLine,
  type ProductOption,
  type StockBalance,
  type StockAdjustmentHistoryRow,
  type StockCountDetail,
  type StockCountStatus,
  type StockCountSummary,
  type WarehouseOption
} from "./warehouseOperationsApi";
import "../../../packages/app-common/src/components/WarehouseClassicTables.css";

export type WarehouseOperationMode = "transfer" | "adjustment" | "count";

type Props = {
  session: UserSession;
  mode: WarehouseOperationMode;
  t: (key: string) => string;
};

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

function numeric(value: unknown) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function hasAtMostThreeDecimals(value: number) {
  return Math.abs(value * 1000 - Math.round(value * 1000)) < 1e-7;
}

function productLabel(product: ProductOption) {
  return [product.code, product.name].filter(Boolean).join(" · ") || product.id;
}

function hasPermission(session: UserSession, permission: "STOCK_TRANSFER" | "STOCK_ADJUST") {
  return session.permissions.includes("ADMIN")
    || session.permissions.includes("GESTION_ALMACEN")
    || session.permissions.includes(permission);
}

export function WarehouseOperationsScreen({ session, mode, t }: Props) {
  const token = session.accessToken ?? "";
  const [warehouses, setWarehouses] = useState<WarehouseOption[]>([]);
  const [products, setProducts] = useState<ProductOption[]>([]);
  const [stock, setStock] = useState<StockBalance[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [status, setStatus] = useState("");
  const [saving, setSaving] = useState(false);

  const [productId, setProductId] = useState("");
  const [sourceWarehouseId, setSourceWarehouseId] = useState("");
  const [targetWarehouseId, setTargetWarehouseId] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const [quantity, setQuantity] = useState("");
  const [direction, setDirection] = useState<"positive" | "negative">("positive");
  const [reason, setReason] = useState("");
  const [adjustmentFormOpen, setAdjustmentFormOpen] = useState(false);
  const [adjustmentSearch, setAdjustmentSearch] = useState("");
  const [adjustmentWarehouseFilter, setAdjustmentWarehouseFilter] = useState("");
  const [adjustmentFrom, setAdjustmentFrom] = useState("");
  const [adjustmentTo, setAdjustmentTo] = useState("");
  const adjustmentRequest = useRef(0);
  const [adjustmentPage, setAdjustmentPage] = useState(0);
  const [adjustmentRows, setAdjustmentRows] = useState<StockAdjustmentHistoryRow[]>([]);
  const [adjustmentHasMore, setAdjustmentHasMore] = useState(false);
  const [productQuery, setProductQuery] = useState("");

  const [counts, setCounts] = useState<StockCountSummary[]>([]);
  const [countStatus, setCountStatus] = useState<"" | StockCountStatus>("");
  const countStatusRef = useRef<HTMLSelectElement>(null);
  const [countWarehouseId, setCountWarehouseId] = useState("");
  const [countNotes, setCountNotes] = useState("");
  const [countProductId, setCountProductId] = useState("");
  const [countProductQuantity, setCountProductQuantity] = useState("");
  const [selectedCount, setSelectedCount] = useState<StockCountDetail | null>(null);
  const [countedValues, setCountedValues] = useState<Record<string, string>>({});
  const [confirmingCount, setConfirmingCount] = useState(false);
  const countConfirmDialogRef = useRef<HTMLElement>(null);

  async function downloadCount(format: "pdf" | "xlsx") {
    if (!selectedCount) return;
    setError("");
    try {
      const file = await exportStockCount(selectedCount.id, format, token);
      const url = URL.createObjectURL(file);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `inventario-${selectedCount.id}.${format}`;
      anchor.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (cause) { setError(errorMessage(cause, t("warehouse.count.exportError"))); }
  }

  useEffect(() => {
    if (!confirmingCount) return;
    const dialog = countConfirmDialogRef.current;
    const deactivateFocusTrap = dialog
      ? activateModalFocusTrap(dialog as unknown as ModalFocusRoot, document)
      : undefined;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      setConfirmingCount(false);
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => {
      window.removeEventListener("keydown", closeOnEscape);
      deactivateFocusTrap?.();
    };
  }, [confirmingCount]);

  const allowed = mode === "transfer"
    ? hasPermission(session, "STOCK_TRANSFER")
    : mode === "adjustment"
      ? hasPermission(session, "STOCK_ADJUST")
      : session.permissions.includes("ADMIN") || session.permissions.includes("GESTION_ALMACEN");

  const loadResources = useCallback(async () => {
    if (!allowed) return;
    setLoading(true);
    setError("");
    try {
      if (mode === "adjustment") {
        const activeWarehouses = (await loadWarehouseOptions(token)).filter((item) => item.active !== false);
        setWarehouses(activeWarehouses);
        setWarehouseId((current) => current || activeWarehouses[0]?.id || "");
        return;
      }
      const resources = await loadWarehouseOperationResources(token);
      const activeWarehouses = resources.warehouses.filter((item) => item.active !== false);
      const stockProducts = resources.products.filter((item) => item.active !== false && item.productType !== "SERVICE");
      setWarehouses(activeWarehouses);
      setProducts(stockProducts);
      setStock(resources.stock);
      setProductId((current) => current || stockProducts[0]?.id || "");
      setWarehouseId((current) => current || activeWarehouses[0]?.id || "");
      setSourceWarehouseId((current) => current || activeWarehouses[0]?.id || "");
      setTargetWarehouseId((current) => current || activeWarehouses[1]?.id || "");
      setCountWarehouseId((current) => current || activeWarehouses[0]?.id || "");
      setCountProductId((current) => current || stockProducts[0]?.id || "");
    } catch (cause) {
      setError(errorMessage(cause, t("warehouse.operations.loadError")));
    } finally {
      setLoading(false);
    }
  }, [allowed, mode, t, token]);

  const refreshAdjustments = useCallback(async () => {
    if (mode !== "adjustment" || !allowed) return;
    const request = ++adjustmentRequest.current;
    try {
      const page = await loadStockAdjustmentHistory(token, {
        page: adjustmentPage, warehouseId: adjustmentWarehouseFilter || undefined,
        search: adjustmentSearch.trim() || undefined,
        from: adjustmentFrom ? new Date(`${adjustmentFrom}T00:00:00`).toISOString() : undefined,
        to: adjustmentTo ? new Date(new Date(`${adjustmentTo}T00:00:00`).setDate(new Date(`${adjustmentTo}T00:00:00`).getDate() + 1)).toISOString() : undefined
      });
      if (request !== adjustmentRequest.current) return;
      setAdjustmentRows(page.items);
      setAdjustmentHasMore(page.hasMore);
    } catch (cause) {
      if (request === adjustmentRequest.current) setError(errorMessage(cause, t("warehouse.adjustment.historyError")));
    }
  }, [adjustmentFrom, adjustmentTo, adjustmentPage, adjustmentSearch, adjustmentWarehouseFilter, allowed, mode, t, token]);

  useEffect(() => { void refreshAdjustments(); }, [refreshAdjustments]);

  useEffect(() => {
    if (mode !== "adjustment" || !productId || !warehouseId || !adjustmentFormOpen) return;
    let cancelled = false;
    void loadStockBalance(productId, warehouseId, token)
      .then((balances) => { if (!cancelled) setStock(balances); })
      .catch(() => { if (!cancelled) setStock([]); });
    return () => { cancelled = true; };
  }, [adjustmentFormOpen, mode, productId, token, warehouseId]);

  const refreshCounts = useCallback(async () => {
    if (mode !== "count" || !allowed) return;
    setError("");
    try {
      setCounts(await loadStockCounts(token, {
        status: countStatus || undefined,
        warehouseId: countWarehouseId || undefined
      }));
    } catch (cause) {
      setError(errorMessage(cause, t("warehouse.count.loadError")));
    }
  }, [allowed, countStatus, countWarehouseId, mode, t, token]);

  useEffect(() => { void loadResources(); }, [loadResources]);
  useEffect(() => { void refreshCounts(); }, [refreshCounts]);

  const selectedBalance = useMemo(() => {
    const selectedWarehouse = mode === "transfer" ? sourceWarehouseId : warehouseId;
    return numeric(stock.find((item) => (
      item.productId === productId && item.warehouseId === selectedWarehouse
    ))?.quantity);
  }, [mode, productId, sourceWarehouseId, stock, warehouseId]);

  function validatePositiveQuantity() {
    const value = Number(quantity);
    if (!Number.isFinite(value) || value <= 0 || !hasAtMostThreeDecimals(value)) {
      setError(t("warehouse.operations.quantityInvalid"));
      return null;
    }
    return value;
  }

  async function submitTransfer(event: React.FormEvent) {
    event.preventDefault();
    setError("");
    setStatus("");
    const value = validatePositiveQuantity();
    if (value === null) return;
    if (!productId || !sourceWarehouseId || !targetWarehouseId) {
      setError(t("warehouse.operations.required"));
      return;
    }
    if (sourceWarehouseId === targetWarehouseId) {
      setError(t("warehouse.transfer.sameWarehouse"));
      return;
    }
    setSaving(true);
    try {
      const result = await createStockTransfer({
        productId,
        sourceWarehouseId,
        targetWarehouseId,
        quantity: value
      }, token);
      setStatus(t("warehouse.transfer.completed"));
      setQuantity("");
      setStock((current) => current.map((item) => {
        if (item.productId !== productId) return item;
        if (item.warehouseId === sourceWarehouseId) return { ...item, quantity: numeric(result.sourceQuantity) };
        if (item.warehouseId === targetWarehouseId) return { ...item, quantity: numeric(result.targetQuantity) };
        return item;
      }));
    } catch (cause) {
      setError(errorMessage(cause, t("warehouse.transfer.error")));
    } finally {
      setSaving(false);
    }
  }

  async function submitAdjustment(event: React.FormEvent) {
    event.preventDefault();
    setError("");
    setStatus("");
    const value = validatePositiveQuantity();
    if (value === null) return;
    if (!productId || !warehouseId || !reason.trim()) {
      setError(t("warehouse.adjustment.required"));
      return;
    }
    setSaving(true);
    try {
      const result = await createStockAdjustment({
        productId,
        warehouseId,
        quantity: direction === "negative" ? -value : value,
        reason: reason.trim()
      }, token);
      setStock((current) => {
        const exists = current.some((item) => item.productId === productId && item.warehouseId === warehouseId);
        if (!exists) return [...current, result];
        return current.map((item) => item.productId === productId && item.warehouseId === warehouseId
          ? { ...item, quantity: numeric(result.quantity) }
          : item);
      });
      setStatus(t("warehouse.adjustment.completed"));
      setQuantity("");
      setReason("");
      setAdjustmentFormOpen(false);
      setAdjustmentPage(0);
      await refreshAdjustments();
    } catch (cause) {
      setError(errorMessage(cause, t("warehouse.adjustment.error")));
    } finally {
      setSaving(false);
    }
  }

  async function openCount(id: string) {
    setLoading(true);
    setError("");
    setConfirmingCount(false);
    try {
      const detail = await loadStockCount(id, token);
      setSelectedCount(detail);
      setCountedValues(Object.fromEntries(detail.lines.map((line) => [
        line.productId,
        line.countedQuantity == null ? "" : String(line.countedQuantity)
      ])));
    } catch (cause) {
      setError(errorMessage(cause, t("warehouse.count.detailError")));
    } finally {
      setLoading(false);
    }
  }

  async function submitCount(event: React.FormEvent) {
    event.preventDefault();
    if (!countWarehouseId) {
      setError(t("warehouse.count.warehouseRequired"));
      return;
    }
    setSaving(true);
    setError("");
    try {
      const detail = await createStockCount({
        warehouseId: countWarehouseId,
        notes: countNotes.trim() || undefined
      }, token);
      setCountNotes("");
      await refreshCounts();
      await openCount(detail.id);
      setStatus(t("warehouse.count.created"));
    } catch (cause) {
      setError(errorMessage(cause, t("warehouse.count.createError")));
    } finally {
      setSaving(false);
    }
  }

  async function saveCountLine(product: ProductOption | undefined, productLineId: string) {
    if (!selectedCount) return;
    const value = Number(countedValues[productLineId]);
    if (!Number.isFinite(value) || value < 0 || !hasAtMostThreeDecimals(value)) {
      setError(t("warehouse.count.quantityInvalid"));
      return;
    }
    setSaving(true);
    setError("");
    try {
      const detail = await updateStockCountLine(selectedCount.id, productLineId, value, token);
      setSelectedCount(detail);
      setStatus(`${product ? productLabel(product) : productLineId}: ${t("warehouse.count.lineSaved")}`);
    } catch (cause) {
      setError(errorMessage(cause, t("warehouse.count.lineError")));
    } finally {
      setSaving(false);
    }
  }

  async function addCountLine(event: React.FormEvent) {
    event.preventDefault();
    if (!selectedCount || !countProductId) return;
    const value = Number(countProductQuantity);
    if (!Number.isFinite(value) || value < 0 || !hasAtMostThreeDecimals(value)) {
      setError(t("warehouse.count.quantityInvalid"));
      return;
    }
    setSaving(true);
    setError("");
    try {
      const detail = await updateStockCountLine(selectedCount.id, countProductId, value, token);
      setSelectedCount(detail);
      setCountedValues((current) => ({ ...current, [countProductId]: String(value) }));
      setCountProductQuantity("");
      const remaining = products.find((product) => !detail.lines.some((line) => line.productId === product.id));
      setCountProductId(remaining?.id ?? "");
      setStatus(t("warehouse.count.lineAdded"));
    } catch (cause) {
      setError(errorMessage(cause, t("warehouse.count.lineError")));
    } finally {
      setSaving(false);
    }
  }

  async function changeCountStatus(action: "confirm" | "cancel") {
    if (!selectedCount) return;
    setSaving(true);
    setError("");
    try {
      const detail = action === "confirm"
        ? await confirmStockCount(selectedCount.id, token, selectedCount.lines)
        : await cancelStockCount(selectedCount.id, token);
      setSelectedCount(detail);
      await refreshCounts();
      setStatus(t(action === "confirm" ? "warehouse.count.confirmed" : "warehouse.count.cancelled"));
    } catch (cause) {
      setError(errorMessage(cause, t("warehouse.count.statusError")));
    } finally {
      setSaving(false);
    }
  }

  async function prepareCountConfirmation() {
    if (!selectedCount || selectedCount.status !== "DRAFT" || saving) return;
    setSaving(true);
    setError("");
    try {
      const fresh = await loadStockCount(selectedCount.id, token);
      if (fresh.status !== "DRAFT" || fresh.lines.length !== selectedCount.lines.length
        || fresh.lines.some((line) => {
          const shown = selectedCount.lines.find((item) => item.productId === line.productId);
          return !shown || numeric(shown.countedQuantity) !== numeric(line.countedQuantity)
            || numeric(shown.expectedQuantity) !== numeric(line.expectedQuantity);
        })) {
        setSelectedCount(fresh);
        setCountedValues(Object.fromEntries(fresh.lines.map((line) => [line.productId, String(line.countedQuantity)])));
        throw new Error(t("warehouse.count.changedBeforeConfirm"));
      }
      let saved = fresh;
      for (const line of fresh.lines) {
        const raw = countedValues[line.productId];
        if (raw == null || raw.trim() === "" || !Number.isFinite(Number(raw))
          || Number(raw) < 0 || !hasAtMostThreeDecimals(Number(raw))) {
          throw new Error(t("warehouse.count.quantityInvalid"));
        }
        if (Number(raw) !== numeric(line.countedQuantity)) {
          saved = await updateStockCountLine(fresh.id, line.productId, Number(raw), token);
        }
      }
      const reviewed = await loadStockCount(saved.id, token);
      if (reviewed.status !== "DRAFT" || reviewed.lines.some((line) =>
        Number(countedValues[line.productId]) !== numeric(line.countedQuantity))) {
        setSelectedCount(reviewed);
        throw new Error(t("warehouse.count.changedBeforeConfirm"));
      }
      setSelectedCount(reviewed);
      setCountedValues(Object.fromEntries(reviewed.lines.map((line) => [line.productId, String(line.countedQuantity)])));
      setConfirmingCount(true);
    } catch (cause) {
      setError(errorMessage(cause, t("warehouse.count.statusError")));
    } finally { setSaving(false); }
  }

  if (!allowed) {
    return <div className="gestion-security-state error" role="alert">{t("warehouse.operations.noAccess")}</div>;
  }

  const titleKey = mode === "transfer"
    ? "warehouse.transfer.title"
    : mode === "adjustment"
      ? "warehouse.adjustment.title"
      : "warehouse.count.title";
  const countedProducts = selectedCount?.lines.filter((line) => line.countedQuantity != null).length ?? 0;
  const totalCountProducts = selectedCount?.lines.length ?? 0;
  const countProgress = totalCountProducts === 0
    ? 0
    : Math.min(100, Math.round((countedProducts / totalCountProducts) * 100));
  const countDifferenceSummary = selectedCount?.lines.reduce((summary, line) => {
    const difference = numeric(line.difference);
    if (difference > 0) summary.positive += difference;
    if (difference < 0) summary.negative += Math.abs(difference);
    summary.net += difference;
    return summary;
  }, { positive: 0, negative: 0, net: 0 }) ?? { positive: 0, negative: 0, net: 0 };

  return (
    <section data-warehouse-heading={mode === "adjustment" ? "unified" : undefined} className={`gestion-warehouse-operations ${mode === "adjustment" ? "gestion-adjustment-screen" : ""}`} aria-labelledby="warehouse-operation-title">
      {mode === "adjustment" && <div className="gestion-adjustment-module-title"><h1>{t("home.warehouse")}</h1></div>}
      <header className="gestion-warehouse-operations-header">
        <div>
          {mode !== "adjustment" && <span>{t("warehouse.operations.section")}</span>}
          <h2 id="warehouse-operation-title">{t(titleKey)}</h2>
          <p>{t(`${titleKey}.subtitle`)}</p>
        </div>
        <button type="button" onClick={() => { void loadResources(); if (mode === "adjustment") void refreshAdjustments(); }} disabled={loading || saving}>
          {t("warehouse.operations.refresh")}
        </button>
      </header>

      {error && <p className="gestion-warehouse-operations-message error" role="alert">{error}</p>}
      {status && <p className="gestion-warehouse-operations-message success" role="status">{status}</p>}
      {loading && <div className="gestion-security-state" role="status">{t("warehouse.operations.loading")}</div>}

      {!loading && mode === "transfer" && (
        <form className="gestion-warehouse-operation-form" onSubmit={submitTransfer}>
          <label><span>{t("warehouse.operations.product")}</span><select value={productId} onChange={(event) => setProductId(event.target.value)} required>{products.map((product) => <option value={product.id} key={product.id}>{productLabel(product)}</option>)}</select></label>
          <label><span>{t("warehouse.transfer.source")}</span><select value={sourceWarehouseId} onChange={(event) => setSourceWarehouseId(event.target.value)} required>{warehouses.map((warehouse) => <option value={warehouse.id} key={warehouse.id}>{warehouse.name}</option>)}</select></label>
          <label><span>{t("warehouse.transfer.target")}</span><select value={targetWarehouseId} onChange={(event) => setTargetWarehouseId(event.target.value)} required>{warehouses.map((warehouse) => <option value={warehouse.id} key={warehouse.id}>{warehouse.name}</option>)}</select></label>
          <label><span>{t("warehouse.operations.quantity")}</span><input type="number" min="0.001" step="0.001" value={quantity} onChange={(event) => setQuantity(event.target.value)} required /></label>
          <div className="gestion-warehouse-balance"><span>{t("warehouse.operations.currentStock")}</span><strong>{selectedBalance.toLocaleString(undefined, { maximumFractionDigits: 3 })}</strong></div>
          <footer><button type="submit" disabled={saving || warehouses.length < 2}>{saving ? t("warehouse.operations.saving") : t("warehouse.transfer.submit")}</button></footer>
        </form>
      )}

      {!loading && mode === "adjustment" && (
        <div className="gestion-warehouse-adjustments">
          <div className="gestion-warehouse-adjustments-toolbar">
            <label className="adjustment-search">{t("warehouse.management.search")}
              <input type="search" placeholder={t("warehouse.adjustment.productSearch")} value={adjustmentSearch} onChange={(event) => {
                setAdjustmentSearch(event.target.value); setAdjustmentPage(0);
              }} /></label>
            <label>{t("warehouse.operations.warehouse")}
              <ErpSelect aria-label={t("warehouse.operations.warehouse")} value={adjustmentWarehouseFilter}
                options={[{ value: "", label: t("warehouse.count.all") }, ...warehouses.map((item) => ({ value: item.id, label: item.name }))]}
                onChange={(value) => { setAdjustmentWarehouseFilter(value); setAdjustmentPage(0); }} />
            </label>
            <label>{t("warehouse.report.from")}<input type="date" value={adjustmentFrom} max={adjustmentTo || undefined}
              onChange={(event) => { setAdjustmentFrom(event.target.value); setAdjustmentPage(0); }} /></label>
            <label>{t("warehouse.report.to")}<input type="date" value={adjustmentTo} min={adjustmentFrom || undefined}
              onChange={(event) => { setAdjustmentTo(event.target.value); setAdjustmentPage(0); }} /></label>
          </div>
          <ErpFilterChips translate={t} chips={[
            ...(adjustmentFrom ? [{ key: "from", label: t("warehouse.report.from"), value: adjustmentFrom,
              onRemove: () => { setAdjustmentFrom(""); setAdjustmentPage(0); } }] : []),
            ...(adjustmentTo ? [{ key: "to", label: t("warehouse.report.to"), value: adjustmentTo,
              onRemove: () => { setAdjustmentTo(""); setAdjustmentPage(0); } }] : []),
            ...(adjustmentWarehouseFilter ? [{ key: "warehouse", label: t("warehouse.operations.warehouse"),
              value: warehouses.find((item) => item.id === adjustmentWarehouseFilter)?.name ?? adjustmentWarehouseFilter,
              onRemove: () => { setAdjustmentWarehouseFilter(""); setAdjustmentPage(0); } }] : []),
            ...(adjustmentSearch.trim() ? [{ key: "search", label: t("warehouse.management.search"),
              value: adjustmentSearch.trim(), onRemove: () => { setAdjustmentSearch(""); setAdjustmentPage(0); } }] : [])
          ]} onClear={() => { setAdjustmentFrom(""); setAdjustmentTo(""); setAdjustmentWarehouseFilter(""); setAdjustmentSearch(""); setAdjustmentPage(0); }} />
          <div className="gestion-adjustment-actions"><button type="button" onClick={() => {
            setProductId(""); setProductQuery(""); setAdjustmentFormOpen(true);
          }}>{t("warehouse.adjustment.perform")}</button></div>
          <div className="gestion-warehouse-adjustments-table erp-classic-tables warehouse-classic-table">
            <StockAdjustmentHistoryTable rows={adjustmentRows} session={session} t={t} />
          </div>
          <div className="gestion-warehouse-adjustments-pages">
            <button type="button" disabled={adjustmentPage === 0} onClick={() => setAdjustmentPage(adjustmentPage - 1)}>{t("warehouse.adjustment.previous")}</button>
            <span>{adjustmentPage + 1}</span>
            <button type="button" disabled={!adjustmentHasMore} onClick={() => setAdjustmentPage(adjustmentPage + 1)}>{t("warehouse.adjustment.next")}</button>
          </div>
          {adjustmentFormOpen && <div className="gestion-modal-backdrop"><section className="gestion-security-dialog gestion-warehouse-adjustment-dialog"
            role="dialog" aria-modal="true" aria-labelledby="warehouse-adjustment-title">
            <header><h2 id="warehouse-adjustment-title">{t("warehouse.adjustment.perform")}</h2>
              <button type="button" aria-label={t("common.close")} onClick={() => setAdjustmentFormOpen(false)}>×</button></header>
            <form className="gestion-warehouse-operation-form" onSubmit={submitAdjustment}>
              <AdjustmentProductSearch token={token} value={productQuery} selected={!!productId} t={t}
                onChange={(value) => { setProductQuery(value); setProductId(""); }}
                onSelect={(item) => { setProductId(item.id); setProductQuery(productLabel(item)); }} />
              <label><span>{t("warehouse.operations.warehouse")}</span><select value={warehouseId} onChange={(event) => setWarehouseId(event.target.value)} required>{warehouses.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select></label>
              <fieldset><legend>{t("warehouse.adjustment.type")}</legend><label><input type="radio" name="direction" checked={direction === "positive"} onChange={() => setDirection("positive")} /> {t("warehouse.adjustment.positive")}</label><label><input type="radio" name="direction" checked={direction === "negative"} onChange={() => setDirection("negative")} /> {t("warehouse.adjustment.negative")}</label></fieldset>
              <label><span>{t("warehouse.operations.quantity")}</span><input type="number" min="0.001" step="0.001" value={quantity} onChange={(event) => setQuantity(event.target.value)} required /></label>
              <label className="wide"><span>{t("warehouse.adjustment.reason")}</span><textarea value={reason} onChange={(event) => setReason(event.target.value)} maxLength={250} required /></label>
              <div className="gestion-warehouse-balance"><span>{t("warehouse.operations.currentStock")}</span><strong>{selectedBalance.toLocaleString(undefined, { maximumFractionDigits: 3 })}</strong></div>
              <footer><button type="button" disabled={saving} onClick={() => setAdjustmentFormOpen(false)}>{t("common.cancel")}</button>
                <button type="submit" disabled={saving || !productId}>{saving ? t("warehouse.operations.saving") : t("warehouse.adjustment.submit")}</button></footer>
            </form></section></div>}
        </div>
      )}

      {!loading && mode === "count" && (
        <div className="gestion-stock-count-layout">
          <section className="gestion-stock-count-list" aria-label={t("warehouse.count.list")}>
            <form onSubmit={submitCount} className="gestion-stock-count-create">
              <label><span>{t("warehouse.operations.warehouse")}</span><select value={countWarehouseId} onChange={(event) => setCountWarehouseId(event.target.value)} required>{warehouses.map((warehouse) => <option value={warehouse.id} key={warehouse.id}>{warehouse.name}</option>)}</select></label>
              <label><span>{t("warehouse.count.notes")}</span><input value={countNotes} onChange={(event) => setCountNotes(event.target.value)} maxLength={250} /></label>
              <button type="submit" disabled={saving}>{t("warehouse.count.create")}</button>
            </form>
            <div className="gestion-stock-count-filters">
              <label><span>{t("warehouse.count.status")}</span><select ref={countStatusRef} value={countStatus} onChange={(event) => setCountStatus(event.target.value as "" | StockCountStatus)}><option value="">{t("warehouse.count.all")}</option><option value="DRAFT">{t("warehouse.count.status.DRAFT")}</option><option value="CONFIRMED">{t("warehouse.count.status.CONFIRMED")}</option><option value="CANCELLED">{t("warehouse.count.status.CANCELLED")}</option></select></label>
              <button type="button" onClick={() => void refreshCounts()}>{t("warehouse.operations.refresh")}</button>
            </div>
            <ErpFilterChips translate={t} focusRef={countStatusRef}
              chips={countStatus ? [{ key: "status", label: t("warehouse.count.status"), value: t(`warehouse.count.status.${countStatus}`), onRemove: () => setCountStatus("") }] : []}
              onClear={() => setCountStatus("")} />
            <div className="gestion-stock-count-items erp-classic-tables warehouse-classic-table">
              <table className="report-table"><thead><tr><th>{t("warehouse.transfer.number")}</th><th>{t("warehouse.operations.warehouse")}</th><th>{t("warehouse.count.status")}</th><th>{t("warehouse.transfer.date")}</th></tr></thead>
                <tbody>{counts.map((count) => <tr key={count.id} className={selectedCount?.id === count.id ? "selected" : ""}>
                  <td>{count.number}</td><td><button type="button" onClick={() => void openCount(count.id)}>{warehouses.find((warehouse) => warehouse.id === count.warehouseId)?.name ?? count.warehouseId}</button></td>
                  <td><span className={`gestion-stock-count-status ${count.status.toLowerCase()}`}>{t(`warehouse.count.status.${count.status}`)}</span></td>
                  <td>{new Date(count.createdAt).toLocaleString()}</td>
                </tr>)}
                {counts.length === 0 && <tr><td colSpan={4}>{t("warehouse.count.empty")}</td></tr>}</tbody>
              </table>
            </div>
          </section>

          <section className="gestion-stock-count-detail" aria-label={t("warehouse.count.detail")}>
            {!selectedCount && <div className="gestion-security-state">{t("warehouse.count.select")}</div>}
            {selectedCount && <>
              <header><div><strong>{selectedCount.number} · {warehouses.find((warehouse) => warehouse.id === selectedCount.warehouseId)?.name ?? selectedCount.warehouseId}</strong><span className={`gestion-stock-count-status ${selectedCount.status.toLowerCase()}`}>{t(`warehouse.count.status.${selectedCount.status}`)}</span></div><div><button type="button" onClick={() => void downloadCount("pdf")}>{t("warehouse.count.exportPdf")}</button><button type="button" onClick={() => void downloadCount("xlsx")}>{t("warehouse.count.exportExcel")}</button>{selectedCount.status === "DRAFT" && <><button type="button" disabled={saving} onClick={() => void changeCountStatus("cancel")}>{t("warehouse.count.cancel")}</button><button type="button" disabled={saving || selectedCount.lines.length === 0} onClick={() => void prepareCountConfirmation()}>{t("warehouse.count.confirm")}</button></>}</div></header>
              <div className="gestion-stock-count-progress" aria-label={t("warehouse.count.progress")}>
                <div><strong>{t("warehouse.count.progress")}</strong><span>{countedProducts} / {totalCountProducts} {t("warehouse.count.productsCounted")}</span></div>
                <div className="gestion-stock-count-progress-track" aria-hidden="true"><span style={{ width: `${countProgress}%` }} /></div>
              </div>
              {selectedCount.status === "DRAFT" && <p className="gestion-stock-count-review-note" role="note">
                {t("warehouse.count.reviewBeforeConfirm")}
              </p>}
              {selectedCount.status === "DRAFT" && (
                <form className="gestion-stock-count-add-line" onSubmit={addCountLine}>
                  <label><span>{t("warehouse.operations.product")}</span><select value={countProductId} onChange={(event) => setCountProductId(event.target.value)} required><option value="">{t("warehouse.count.selectProduct")}</option>{products.filter((product) => !selectedCount.lines.some((line) => line.productId === product.id)).map((product) => <option value={product.id} key={product.id}>{productLabel(product)}</option>)}</select></label>
                  <label><span>{t("warehouse.count.counted")}</span><input type="number" min="0" step="0.001" value={countProductQuantity} onChange={(event) => setCountProductQuantity(event.target.value)} required /></label>
                  <button type="submit" disabled={saving || !countProductId}>{t("warehouse.count.addLine")}</button>
                </form>
              )}
              <div className="gestion-stock-count-summary" aria-label={t("warehouse.count.summary")}>
                <div><span>{t("warehouse.count.increases")}</span><strong className="positive">+{countDifferenceSummary.positive.toLocaleString()}</strong></div>
                <div><span>{t("warehouse.count.decreases")}</span><strong className="negative">-{countDifferenceSummary.negative.toLocaleString()}</strong></div>
                <div><span>{t("warehouse.count.netDifference")}</span><strong className={countDifferenceSummary.net > 0 ? "positive" : countDifferenceSummary.net < 0 ? "negative" : "neutral"}>{countDifferenceSummary.net > 0 ? "+" : ""}{countDifferenceSummary.net.toLocaleString()}</strong></div>
              </div>
              <div className="gestion-stock-count-table erp-classic-tables warehouse-classic-table">
                <table className="report-table"><thead><tr>
                  <th>{t("warehouse.adjustment.code")}</th><th>{t("warehouse.adjustment.barcode")}</th>
                  <th>{t("warehouse.adjustment.name")}</th><th>{t("warehouse.count.expected")}</th>
                  <th>{t("warehouse.count.counted")}</th><th>{t("warehouse.count.difference")}</th>
                  <th>{t("warehouse.count.action")}</th>
                </tr></thead><tbody>{selectedCount.lines.map((line) => {
                  const product = products.find((item) => item.id === line.productId);
                  const difference = countedValues[line.productId] === "" ? null : numeric(countedValues[line.productId]) - numeric(line.expectedQuantity);
                  const differenceTone = difference == null || difference === 0 ? "neutral" : difference > 0 ? "positive" : "negative";
                  return <tr key={line.productId}>
                    <td>{line.productCode || product?.code}</td><td>{line.productBarcode || product?.barcode || "—"}</td>
                    <td>{line.productName || product?.name}</td><td className="is-numeric">{numeric(line.expectedQuantity).toLocaleString()}</td>
                    <td><input aria-label={`${t("warehouse.count.counted")} ${line.productName || product?.name || line.productId}`} type="number" min="0" step="0.001" disabled={selectedCount.status !== "DRAFT"} value={countedValues[line.productId] ?? ""} onChange={(event) => setCountedValues((current) => ({ ...current, [line.productId]: event.target.value }))} /></td>
                    <td className="is-numeric"><strong className={`gestion-stock-count-difference ${differenceTone}`}>{difference == null ? "—" : difference > 0 ? `+${difference.toLocaleString()}` : difference.toLocaleString()}</strong></td>
                    <td>{selectedCount.status === "DRAFT" && <button type="button" disabled={saving} onClick={() => void saveCountLine(product, line.productId)}>{t("warehouse.count.saveLine")}</button>}</td>
                  </tr>;
                })}</tbody></table>
              </div>
            </>}
          </section>
        </div>
      )}

      {confirmingCount && selectedCount && (
        <div className="gestion-stock-count-confirm-backdrop" role="presentation">
          <section ref={countConfirmDialogRef} className="gestion-stock-count-confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="stock-count-confirm-title">
            <header>
              <span>{t("warehouse.count.confirmEyebrow")}</span>
              <h3 id="stock-count-confirm-title">{t("warehouse.count.confirmTitle")}</h3>
              <p>{t("warehouse.count.confirmDescription")}</p>
            </header>
            <div className="gestion-stock-count-confirm-metrics">
              <div><span>{t("warehouse.count.productsCounted")}</span><strong>{countedProducts}</strong></div>
              <div><span>{t("warehouse.count.increases")}</span><strong className="positive">+{countDifferenceSummary.positive.toLocaleString()}</strong></div>
              <div><span>{t("warehouse.count.decreases")}</span><strong className="negative">-{countDifferenceSummary.negative.toLocaleString()}</strong></div>
              <div><span>{t("warehouse.count.netDifference")}</span><strong>{countDifferenceSummary.net > 0 ? "+" : ""}{countDifferenceSummary.net.toLocaleString()}</strong></div>
            </div>
            <footer>
              <button type="button" disabled={saving} onClick={() => setConfirmingCount(false)}>{t("warehouse.count.keepEditing")}</button>
              <button type="button" className="primary" disabled={saving} onClick={() => { setConfirmingCount(false); void changeCountStatus("confirm"); }}>{t("warehouse.count.applyDifferences")}</button>
            </footer>
          </section>
        </div>
      )}
    </section>
  );
}

export default WarehouseOperationsScreen;
