import { useCallback, useEffect, useMemo, useState } from "react";
import { apiRequest, type LocaleCode } from "@tpverp/app-common";
import { buildReplenishmentSuggestions, pdaReplenishmentPagePath, type StockItem } from "./PdaReplenishment";
import { readPdaWorkQueue } from "./PdaWorkboard";

type WarehouseOption = { id: string; name?: string | null; nombre?: string | null; defaultWarehouse?: boolean; active?: boolean };
type GoodsCheck = { status: string };
type WorkItem = { type: string; status: string };
type StockPageItem = { product: { id: string; code?: string | null; name?: string | null; active?: boolean | null; productType?: string | null; stockMin?: number | string | null; stockMax?: number | string | null }; stock: StockItem[] };
type StockPage = { items: StockPageItem[]; nextCursor?: string | null; hasMore: boolean };
type Module = "check" | "replenishment" | "work";
const LAST_SYNC_KEY = "tpverp.pda.lastSyncAt.v1";
export function summarizePdaHome(checks: GoodsCheck[], work: WorkItem[], inventory: StockPageItem[], warehouses: WarehouseOption[], targetWarehouseId: string) {
  return {
    pending: checks.filter((item) => item.status === "ABIERTA").length,
    low: buildReplenishmentSuggestions(inventory, warehouses, targetWarehouseId).length,
    assigned: work.filter((item) => item.type === "PICKING" && item.status === "OPEN").length,
    incidents: work.filter((item) => item.type === "INCIDENT" && item.status === "OPEN").length
  };
}

const copy = {
  es: { title: "Resumen de hoy", pending: "Comprobaciones pendientes", low: "Productos bajo mínimo", assigned: "Reposiciones asignadas", incidents: "Incidencias abiertas", connection: "Conexión", online: "En línea", offline: "Sin conexión", last: "Última sincronización", never: "Todavía no sincronizado", refresh: "Actualizar panel", error: "No se pudo actualizar el panel completo." },
  en: { title: "Today’s overview", pending: "Pending goods checks", low: "Products below minimum", assigned: "Assigned replenishments", incidents: "Open incidents", connection: "Connection", online: "Online", offline: "Offline", last: "Last synchronization", never: "Not synchronized yet", refresh: "Refresh dashboard", error: "The complete dashboard could not be refreshed." },
  zh: { title: "今日概览", pending: "待验货", low: "低于最低库存", assigned: "已分配补货", incidents: "未关闭异常", connection: "连接状态", online: "在线", offline: "离线", last: "上次同步", never: "尚未同步", refresh: "刷新面板", error: "无法完整刷新面板。" }
} as const;

export function PdaHomeDashboard({ token, locale, warehouses, onOpen }: { token?: string; locale: LocaleCode; warehouses: WarehouseOption[]; onOpen: (module: Module) => void }) {
  const c = copy[locale];
  const [counts, setCounts] = useState({ pending: 0, low: 0, assigned: 0, incidents: 0 });
  const [online, setOnline] = useState(navigator.onLine);
  const [lastSync, setLastSync] = useState(() => localStorage.getItem(LAST_SYNC_KEY) ?? "");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const localeTag = locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES";
  const date = useMemo(() => new Intl.DateTimeFormat(localeTag, { dateStyle: "short", timeStyle: "short" }), [localeTag]);
  const activeWarehouses = useMemo(() => warehouses.filter((warehouse) => warehouse.active !== false), [warehouses]);
  const targetWarehouseId = activeWarehouses.find((warehouse) => warehouse.defaultWarehouse)?.id ?? activeWarehouses[0]?.id ?? "";

  const load = useCallback(async () => {
    if (!token || !navigator.onLine) return;
    setLoading(true);
    setError("");
    try {
      const stockItems: StockPageItem[] = [];
      let cursor: string | null | undefined;
      const visited = new Set<string>();
      const stockPromise = (async () => {
        do {
          const page = await apiRequest<StockPage>(pdaReplenishmentPagePath(cursor), { token });
          stockItems.push(...page.items);
          const next = page.hasMore ? page.nextCursor : null;
          cursor = next && !visited.has(next) ? next : null;
          if (cursor) visited.add(cursor);
        } while (cursor);
        return stockItems;
      })();
      const [checks, work, inventory] = await Promise.all([
        apiRequest<GoodsCheck[]>("/goods-checks", { token }),
        apiRequest<WorkItem[]>("/pda-work?status=OPEN", { token }),
        stockPromise
      ]);
      setCounts(summarizePdaHome(checks, work, inventory, activeWarehouses, targetWarehouseId));
      const now = new Date().toISOString();
      localStorage.setItem(LAST_SYNC_KEY, now);
      setLastSync(now);
    } catch {
      setError(c.error);
    } finally {
      setLoading(false);
    }
  }, [activeWarehouses, c.error, targetWarehouseId, token]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    const up = () => { setOnline(true); void load(); };
    const down = () => setOnline(false);
    window.addEventListener("online", up);
    window.addEventListener("offline", down);
    return () => { window.removeEventListener("online", up); window.removeEventListener("offline", down); };
  }, [load]);

  const queueLength = readPdaWorkQueue().length;
  return <section className="pda-home-dashboard">
    <header><h2>{c.title}</h2><button type="button" disabled={loading || !online} onClick={() => void load()}>{c.refresh}</button></header>
    {error && <p role="alert">{error}</p>}
    <div className="pda-home-kpis">
      <button type="button" onClick={() => onOpen("check")}><span>{c.pending}</span><b>{counts.pending}</b><i>→</i></button>
      <button type="button" onClick={() => onOpen("replenishment")}><span>{c.low}</span><b>{counts.low}</b><i>→</i></button>
      <button type="button" onClick={() => onOpen("work")}><span>{c.assigned}</span><b>{counts.assigned}</b><i>→</i></button>
      <button type="button" className={counts.incidents ? "warning" : ""} onClick={() => onOpen("work")}><span>{c.incidents}</span><b>{counts.incidents}</b><i>→</i></button>
      <article className={online ? "online" : "offline"}><div><span>{c.connection}</span><b>{online ? c.online : c.offline}</b></div><small>{c.last}: {lastSync ? date.format(new Date(lastSync)) : c.never}{queueLength ? ` · ${queueLength}` : ""}</small></article>
    </div>
  </section>;
}
