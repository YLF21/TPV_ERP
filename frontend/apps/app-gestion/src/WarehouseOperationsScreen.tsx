import { useCallback, useEffect, useMemo, useState } from "react";
import type { UserSession } from "../../../packages/app-common/src/types";
import {
  cancelStockCount,
  confirmStockCount,
  createStockAdjustment,
  createStockCount,
  createStockTransfer,
  loadStockCount,
  loadStockCounts,
  loadWarehouseOperationResources,
  updateStockCountLine,
  type ProductOption,
  type StockBalance,
  type StockCountDetail,
  type StockCountStatus,
  type StockCountSummary,
  type WarehouseOption
} from "./warehouseOperationsApi";

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

  const [counts, setCounts] = useState<StockCountSummary[]>([]);
  const [countStatus, setCountStatus] = useState<"" | StockCountStatus>("");
  const [countWarehouseId, setCountWarehouseId] = useState("");
  const [countNotes, setCountNotes] = useState("");
  const [countProductId, setCountProductId] = useState("");
  const [countProductQuantity, setCountProductQuantity] = useState("");
  const [selectedCount, setSelectedCount] = useState<StockCountDetail | null>(null);
  const [countedValues, setCountedValues] = useState<Record<string, string>>({});

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
  }, [allowed, t, token]);

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
    } catch (cause) {
      setError(errorMessage(cause, t("warehouse.adjustment.error")));
    } finally {
      setSaving(false);
    }
  }

  async function openCount(id: string) {
    setLoading(true);
    setError("");
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
        ? await confirmStockCount(selectedCount.id, token)
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

  if (!allowed) {
    return <div className="gestion-security-state error" role="alert">{t("warehouse.operations.noAccess")}</div>;
  }

  const titleKey = mode === "transfer"
    ? "warehouse.transfer.title"
    : mode === "adjustment"
      ? "warehouse.adjustment.title"
      : "warehouse.count.title";

  return (
    <section className="gestion-warehouse-operations" aria-labelledby="warehouse-operation-title">
      <header className="gestion-warehouse-operations-header">
        <div>
          <span>{t("warehouse.operations.section")}</span>
          <h2 id="warehouse-operation-title">{t(titleKey)}</h2>
          <p>{t(`${titleKey}.subtitle`)}</p>
        </div>
        <button type="button" onClick={() => void loadResources()} disabled={loading || saving}>
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
        <form className="gestion-warehouse-operation-form" onSubmit={submitAdjustment}>
          <label><span>{t("warehouse.operations.product")}</span><select value={productId} onChange={(event) => setProductId(event.target.value)} required>{products.map((product) => <option value={product.id} key={product.id}>{productLabel(product)}</option>)}</select></label>
          <label><span>{t("warehouse.operations.warehouse")}</span><select value={warehouseId} onChange={(event) => setWarehouseId(event.target.value)} required>{warehouses.map((warehouse) => <option value={warehouse.id} key={warehouse.id}>{warehouse.name}</option>)}</select></label>
          <fieldset><legend>{t("warehouse.adjustment.type")}</legend><label><input type="radio" name="direction" checked={direction === "positive"} onChange={() => setDirection("positive")} /> {t("warehouse.adjustment.positive")}</label><label><input type="radio" name="direction" checked={direction === "negative"} onChange={() => setDirection("negative")} /> {t("warehouse.adjustment.negative")}</label></fieldset>
          <label><span>{t("warehouse.operations.quantity")}</span><input type="number" min="0.001" step="0.001" value={quantity} onChange={(event) => setQuantity(event.target.value)} required /></label>
          <label className="wide"><span>{t("warehouse.adjustment.reason")}</span><textarea value={reason} onChange={(event) => setReason(event.target.value)} maxLength={250} required /></label>
          <div className="gestion-warehouse-balance"><span>{t("warehouse.operations.currentStock")}</span><strong>{selectedBalance.toLocaleString(undefined, { maximumFractionDigits: 3 })}</strong></div>
          <footer><button type="submit" disabled={saving}>{saving ? t("warehouse.operations.saving") : t("warehouse.adjustment.submit")}</button></footer>
        </form>
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
              <label><span>{t("warehouse.count.status")}</span><select value={countStatus} onChange={(event) => setCountStatus(event.target.value as "" | StockCountStatus)}><option value="">{t("warehouse.count.all")}</option><option value="DRAFT">{t("warehouse.count.status.DRAFT")}</option><option value="CONFIRMED">{t("warehouse.count.status.CONFIRMED")}</option><option value="CANCELLED">{t("warehouse.count.status.CANCELLED")}</option></select></label>
              <button type="button" onClick={() => void refreshCounts()}>{t("warehouse.operations.refresh")}</button>
            </div>
            <div className="gestion-stock-count-items">
              {counts.map((count) => <button type="button" key={count.id} className={selectedCount?.id === count.id ? "selected" : ""} onClick={() => void openCount(count.id)}><strong>{warehouses.find((warehouse) => warehouse.id === count.warehouseId)?.name ?? count.warehouseId}</strong><span className={`gestion-stock-count-status ${count.status.toLowerCase()}`}>{t(`warehouse.count.status.${count.status}`)}</span><small>{new Date(count.createdAt).toLocaleString()}</small></button>)}
              {counts.length === 0 && <p>{t("warehouse.count.empty")}</p>}
            </div>
          </section>

          <section className="gestion-stock-count-detail" aria-label={t("warehouse.count.detail")}>
            {!selectedCount && <div className="gestion-security-state">{t("warehouse.count.select")}</div>}
            {selectedCount && <>
              <header><div><strong>{warehouses.find((warehouse) => warehouse.id === selectedCount.warehouseId)?.name ?? selectedCount.warehouseId}</strong><span className={`gestion-stock-count-status ${selectedCount.status.toLowerCase()}`}>{t(`warehouse.count.status.${selectedCount.status}`)}</span></div>{selectedCount.status === "DRAFT" && <div><button type="button" disabled={saving} onClick={() => void changeCountStatus("cancel")}>{t("warehouse.count.cancel")}</button><button type="button" disabled={saving} onClick={() => void changeCountStatus("confirm")}>{t("warehouse.count.confirm")}</button></div>}</header>
              {selectedCount.status === "DRAFT" && (
                <form className="gestion-stock-count-add-line" onSubmit={addCountLine}>
                  <label><span>{t("warehouse.operations.product")}</span><select value={countProductId} onChange={(event) => setCountProductId(event.target.value)} required><option value="">{t("warehouse.count.selectProduct")}</option>{products.filter((product) => !selectedCount.lines.some((line) => line.productId === product.id)).map((product) => <option value={product.id} key={product.id}>{productLabel(product)}</option>)}</select></label>
                  <label><span>{t("warehouse.count.counted")}</span><input type="number" min="0" step="0.001" value={countProductQuantity} onChange={(event) => setCountProductQuantity(event.target.value)} required /></label>
                  <button type="submit" disabled={saving || !countProductId}>{t("warehouse.count.addLine")}</button>
                </form>
              )}
              <div className="gestion-stock-count-table" role="table">
                <div role="row" className="head"><span role="columnheader">{t("warehouse.operations.product")}</span><span role="columnheader">{t("warehouse.count.expected")}</span><span role="columnheader">{t("warehouse.count.counted")}</span><span role="columnheader">{t("warehouse.count.difference")}</span><span role="columnheader">{t("warehouse.count.action")}</span></div>
                {selectedCount.lines.map((line) => {
                  const product = products.find((item) => item.id === line.productId);
                  const difference = countedValues[line.productId] === "" ? null : numeric(countedValues[line.productId]) - numeric(line.expectedQuantity);
                  const differenceTone = difference == null || difference === 0 ? "neutral" : difference > 0 ? "positive" : "negative";
                  return <div role="row" key={line.productId}><span role="cell" className="product-cell"><strong>{line.productCode || product?.code}</strong><small>{line.productName || product?.name}</small></span><span role="cell">{numeric(line.expectedQuantity).toLocaleString()}</span><span role="cell"><input aria-label={`${t("warehouse.count.counted")} ${line.productName || product?.name || line.productId}`} type="number" min="0" step="0.001" disabled={selectedCount.status !== "DRAFT"} value={countedValues[line.productId] ?? ""} onChange={(event) => setCountedValues((current) => ({ ...current, [line.productId]: event.target.value }))} /></span><span role="cell"><strong className={`gestion-stock-count-difference ${differenceTone}`}>{difference == null ? "—" : difference > 0 ? `+${difference.toLocaleString()}` : difference.toLocaleString()}</strong></span><span role="cell">{selectedCount.status === "DRAFT" && <button type="button" disabled={saving} onClick={() => void saveCountLine(product, line.productId)}>{t("warehouse.count.saveLine")}</button>}</span></div>;
                })}
              </div>
            </>}
          </section>
        </div>
      )}
    </section>
  );
}

export default WarehouseOperationsScreen;
