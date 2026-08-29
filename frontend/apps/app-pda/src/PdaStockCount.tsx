import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { apiRequest, type LocaleCode } from "@tpverp/app-common";
import { pdaPriceLookupPath } from "./PdaProductLookup";
import { PhysicalScannerStatus, usePhysicalScanner } from "./usePhysicalScanner";

type WarehouseOption = { id: string; name?: string | null; nombre?: string | null; defaultWarehouse?: boolean; active?: boolean };
type StockCountStatus = "DRAFT" | "CONFIRMED" | "CANCELLED";
type StockCountLine = {
  productId: string;
  productCode?: string | null;
  productName?: string | null;
  expectedQuantity: number | string;
  countedQuantity?: number | string | null;
  difference?: number | string | null;
  appliedDifference?: number | string | null;
};
type StockCountSummary = {
  id: string;
  warehouseId: string;
  status: StockCountStatus;
  notes?: string | null;
  createdAt: string;
  lineCount: number;
  totalDifference: number | string;
};
type StockCountDetail = Omit<StockCountSummary, "lineCount" | "totalDifference"> & { lines: StockCountLine[] };
type ProductLookup = { productId: string; code: string; name: string };

export function pdaStockCountListPath(status?: StockCountStatus, warehouseId?: string) {
  const query = new URLSearchParams();
  if (status) query.set("status", status);
  if (warehouseId) query.set("warehouseId", warehouseId);
  return `/stock-counts${query.size ? `?${query.toString()}` : ""}`;
}

export function nextCountedQuantity(current: unknown, increment: unknown) {
  const previous = Number(current ?? 0);
  const addition = Number(increment);
  if (!Number.isFinite(previous) || !Number.isFinite(addition) || addition <= 0) return null;
  const value = previous + addition;
  return Math.round(value * 1000) / 1000;
}

export function PdaStockCount({ token, locale, warehouses, t }: {
  token?: string;
  locale: LocaleCode;
  warehouses: WarehouseOption[];
  t: (key: string) => string;
}) {
  const activeWarehouses = useMemo(() => warehouses.filter((warehouse) => warehouse.active !== false), [warehouses]);
  const [warehouseId, setWarehouseId] = useState("");
  const [drafts, setDrafts] = useState<StockCountSummary[]>([]);
  const [count, setCount] = useState<StockCountDetail | null>(null);
  const [notes, setNotes] = useState("");
  const [identifier, setIdentifier] = useState("");
  const [increment, setIncrement] = useState("1");
  const [lineValues, setLineValues] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [requestedDraftId, setRequestedDraftId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [status, setStatus] = useState("");
  const scanRef = useRef<HTMLInputElement | null>(null);
  const number = useMemo(() => new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", { maximumFractionDigits: 3 }), [locale]);
  const warehouseName = (id: string) => {
    const warehouse = activeWarehouses.find((item) => item.id === id);
    return warehouse?.name ?? warehouse?.nombre ?? id;
  };

  useEffect(() => {
    setWarehouseId((current) => activeWarehouses.some((warehouse) => warehouse.id === current)
      ? current
      : activeWarehouses.find((warehouse) => warehouse.defaultWarehouse)?.id ?? activeWarehouses[0]?.id ?? "");
  }, [activeWarehouses]);

  const loadDrafts = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    setError("");
    try {
      setDrafts(await apiRequest<StockCountSummary[]>(pdaStockCountListPath("DRAFT"), { token }));
    } catch {
      setError(t("pda.count.loadError"));
    } finally {
      setLoading(false);
    }
  }, [t, token]);

  useEffect(() => { void loadDrafts(); }, [loadDrafts]);

  useEffect(() => {
    if (!token || !warehouseId || loading) return;
    const draft = drafts.find((item) => item.warehouseId === warehouseId);
    if (!draft) {
      setRequestedDraftId("");
      setCount((current) => current?.warehouseId === warehouseId && current.status === "DRAFT" ? current : null);
      return;
    }
    if (count?.id === draft.id || requestedDraftId === draft.id) return;
    setRequestedDraftId(draft.id);
    setLoading(true);
    apiRequest<StockCountDetail>(`/stock-counts/${encodeURIComponent(draft.id)}`, { token })
      .then(setCount)
      .catch(() => setError(t("pda.count.loadError")))
      .finally(() => setLoading(false));
  }, [count?.id, drafts, loading, requestedDraftId, t, token, warehouseId]);

  useEffect(() => {
    setLineValues(Object.fromEntries((count?.lines ?? []).map((line) => [line.productId, String(line.countedQuantity ?? 0)])));
  }, [count]);

  async function createCount() {
    if (!token || !warehouseId) return;
    setBusy(true);
    setError("");
    setStatus("");
    try {
      const created = await apiRequest<StockCountDetail>("/stock-counts", { token, body: { warehouseId, notes: notes.trim() || undefined } });
      setCount(created);
      setRequestedDraftId(created.id);
      setDrafts((current) => [{ ...created, lineCount: created.lines.length, totalDifference: 0 }, ...current.filter((item) => item.id !== created.id)]);
      setNotes("");
      setStatus(t("pda.count.created"));
      window.setTimeout(() => scanRef.current?.focus(), 0);
    } catch {
      setError(t("pda.count.createError"));
    } finally {
      setBusy(false);
    }
  }

  async function saveLine(productId: string, countedQuantity: number) {
    if (!token || !count) return;
    const updated = await apiRequest<StockCountDetail>(`/stock-counts/${encodeURIComponent(count.id)}/lines/${encodeURIComponent(productId)}`, {
      method: "PUT", token, body: { countedQuantity }
    });
    setCount(updated);
  }

  async function registerScannedIdentifier(scannedIdentifier: string, manualSignal = false) {
    if (!token || !count || !scannedIdentifier.trim()) return false;
    const addition = Number(increment);
    if (!Number.isFinite(addition) || addition <= 0 || Math.abs(addition * 1000 - Math.round(addition * 1000)) > 1e-7) {
      setError(t("pda.count.quantityInvalid"));
      return false;
    }
    setBusy(true);
    setError("");
    setStatus("");
    try {
      const product = await apiRequest<ProductLookup>(pdaPriceLookupPath(scannedIdentifier.trim()), { token });
      const existing = count.lines.find((line) => line.productId === product.productId);
      const next = nextCountedQuantity(existing?.countedQuantity, addition);
      if (next == null) throw new Error("invalid_quantity");
      await saveLine(product.productId, next);
      setIdentifier("");
      setStatus(t("pda.count.registered").replace("{product}", product.name));
      if (manualSignal) navigator.vibrate?.(60);
      return true;
    } catch {
      setError(t("pda.count.scanError"));
      if (manualSignal) navigator.vibrate?.([100, 60, 100]);
      return false;
    } finally {
      setBusy(false);
      window.setTimeout(() => scanRef.current?.focus(), 0);
    }
  }

  async function scan(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!identifier.trim()) return;
    await registerScannedIdentifier(identifier, true);
  }
  async function updateLine(productId: string) {
    const value = Number(lineValues[productId]);
    if (!Number.isFinite(value) || value < 0 || Math.abs(value * 1000 - Math.round(value * 1000)) > 1e-7) {
      setError(t("pda.count.quantityInvalid"));
      return;
    }
    setBusy(true);
    setError("");
    try {
      await saveLine(productId, value);
      setStatus(t("pda.count.lineSaved"));
    } catch {
      setError(t("pda.count.saveError"));
    } finally {
      setBusy(false);
    }
  }

  async function changeStatus(action: "confirm" | "cancel") {
    if (!token || !count) return;
    if (!window.confirm(t(action === "confirm" ? "pda.count.confirmQuestion" : "pda.count.cancelQuestion"))) return;
    setBusy(true);
    setError("");
    try {
      await apiRequest<StockCountDetail>(`/stock-counts/${encodeURIComponent(count.id)}/${action}`, { method: "POST", token });
      setStatus(t(action === "confirm" ? "pda.count.confirmed" : "pda.count.cancelled"));
      setCount(null);
      await loadDrafts();
    } catch {
      setError(t("pda.count.statusError"));
    } finally {
      setBusy(false);
    }
  }

  const differenceLines = count?.lines.filter((line) => Number(line.difference ?? 0) !== 0).length ?? 0;
  const physicalScanner = usePhysicalScanner({
    enabled: Boolean(token && count && !busy),
    locale,
    onScan: (value) => registerScannedIdentifier(value),
    duplicateWindowMs: 1200
  });

  return <section className="pda-count">
    <header><span>{t("pda.count.eyebrow")}</span><h2>{t("pda.count.title")}</h2><p>{t("pda.count.subtitle")}</p></header>
    <section className="pda-count-setup">
      <label><span>{t("pda.count.warehouse")}</span><select value={warehouseId} disabled={busy} onChange={(event) => setWarehouseId(event.target.value)}>{activeWarehouses.map((warehouse) => <option key={warehouse.id} value={warehouse.id}>{warehouseName(warehouse.id)}</option>)}</select></label>
      {!count && <label><span>{t("pda.count.notes")}</span><input maxLength={500} value={notes} onChange={(event) => setNotes(event.target.value)} /></label>}
      {!count && <button type="button" disabled={busy || loading || !warehouseId} onClick={() => void createCount()}>{loading ? t("common.loading") : t("pda.count.start")}</button>}
      {count && <div className="pda-count-active"><span>{t("pda.count.inProgress")}</span><strong>{warehouseName(count.warehouseId)}</strong><small>{count.notes}</small></div>}
    </section>
    {error && <p className="pda-count-error" role="alert">{error}</p>}
    {status && <p className="pda-count-status" role="status">{status}</p>}
    {!count && !loading && <div className="pda-count-empty">{t("pda.count.empty")}</div>}
    {count && <>
      <form className="pda-count-scan" onSubmit={scan}>
        <label><span>{t("pda.count.scan")}</span><input ref={scanRef} data-physical-scanner-input value={identifier} disabled={busy} onChange={(event) => setIdentifier(event.target.value)} /></label>
        <label><span>{t("pda.count.addQuantity")}</span><input type="number" min="0.001" step="0.001" inputMode="decimal" value={increment} disabled={busy} onChange={(event) => setIncrement(event.target.value)} /></label>
        <button type="submit" disabled={busy || !identifier.trim()}>{t("pda.count.register")}</button>
      </form>
      <PhysicalScannerStatus {...physicalScanner} />
      <section className="pda-count-summary"><div><span>{t("pda.count.lines")}</span><strong>{count.lines.length}</strong></div><div><span>{t("pda.count.differences")}</span><strong>{differenceLines}</strong></div></section>
      <section className="pda-count-lines">
        {count.lines.length === 0 && <p>{t("pda.count.noLines")}</p>}
        {count.lines.map((line) => <article key={line.productId} className={Number(line.difference ?? 0) === 0 ? "balanced" : "different"}>
          <header><span>{line.productCode}</span><strong>{line.productName}</strong></header>
          <div><span>{t("pda.count.expected")}</span><b>{number.format(Number(line.expectedQuantity))}</b></div>
          <label><span>{t("pda.count.counted")}</span><input type="number" min="0" step="0.001" value={lineValues[line.productId] ?? ""} onChange={(event) => setLineValues((current) => ({ ...current, [line.productId]: event.target.value }))} /></label>
          <div><span>{t("pda.count.difference")}</span><b>{number.format(Number(line.difference ?? 0))}</b></div>
          <button type="button" disabled={busy} onClick={() => void updateLine(line.productId)}>{t("pda.count.save")}</button>
        </article>)}
      </section>
      <footer className="pda-count-actions"><button type="button" className="secondary" disabled={busy} onClick={() => void changeStatus("cancel")}>{t("pda.count.cancel")}</button><button type="button" disabled={busy || count.lines.length === 0} onClick={() => void changeStatus("confirm")}>{t("pda.count.confirm")}</button></footer>
    </>}
  </section>;
}