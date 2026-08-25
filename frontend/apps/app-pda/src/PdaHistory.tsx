import { useCallback, useEffect, useMemo, useState } from "react";
import { apiRequest, type LocaleCode } from "@tpverp/app-common";

type GoodsCheckSummary = {
  id: string;
  documentId: string;
  documentNumber?: string | null;
  status: "ABIERTA" | "COMPLETA" | "CON_DIFERENCIAS";
  createdAt: string;
  closedAt?: string | null;
  lineCount: number;
  differenceCount: number;
};

type StockCountSummary = {
  id: string;
  warehouseId: string;
  status: "DRAFT" | "CONFIRMED" | "CANCELLED";
  notes?: string | null;
  createdAt: string;
  confirmedAt?: string | null;
  cancelledAt?: string | null;
  lineCount: number;
  totalDifference: number | string;
};

type WarehouseOption = { id: string; name?: string | null; nombre?: string | null };

export function PdaHistory({ token, locale, warehouses, t }: {
  token?: string;
  locale: LocaleCode;
  warehouses: WarehouseOption[];
  t: (key: string) => string;
}) {
  const [checks, setChecks] = useState<GoodsCheckSummary[]>([]);
  const [counts, setCounts] = useState<StockCountSummary[]>([]);
  const [filter, setFilter] = useState<"all" | "checks" | "counts">("all");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const localeTag = locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES";
  const date = useMemo(() => new Intl.DateTimeFormat(localeTag, { dateStyle: "medium", timeStyle: "short" }), [localeTag]);
  const number = useMemo(() => new Intl.NumberFormat(localeTag, { maximumFractionDigits: 3 }), [localeTag]);
  const warehouseName = (id: string) => warehouses.find((warehouse) => warehouse.id === id)?.name
    ?? warehouses.find((warehouse) => warehouse.id === id)?.nombre ?? id;

  const load = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    setError("");
    try {
      const [goodsChecks, stockCounts] = await Promise.all([
        apiRequest<GoodsCheckSummary[]>("/goods-checks", { token }),
        apiRequest<StockCountSummary[]>("/stock-counts", { token })
      ]);
      setChecks(goodsChecks);
      setCounts(stockCounts);
    } catch {
      setError(t("pda.history.loadError"));
    } finally {
      setLoading(false);
    }
  }, [t, token]);

  useEffect(() => { void load(); }, [load]);

  const events = useMemo(() => [
    ...checks.map((item) => ({
      key: `check-${item.id}`,
      kind: "check" as const,
      at: item.closedAt ?? item.createdAt,
      title: item.documentNumber || item.documentId,
      status: item.status,
      lines: item.lineCount,
      difference: item.differenceCount
    })),
    ...counts.map((item) => ({
      key: `count-${item.id}`,
      kind: "count" as const,
      at: item.confirmedAt ?? item.cancelledAt ?? item.createdAt,
      title: warehouseName(item.warehouseId),
      status: item.status,
      lines: item.lineCount,
      difference: Number(item.totalDifference)
    }))
  ].filter((item) => filter === "all" || (filter === "checks" ? item.kind === "check" : item.kind === "count"))
    .sort((left, right) => new Date(right.at).getTime() - new Date(left.at).getTime()), [checks, counts, filter, warehouses]);

  return <section className="pda-history">
    <header className="pda-history-heading"><div><span>{t("pda.history.eyebrow")}</span><h2>{t("pda.history.title")}</h2><p>{t("pda.history.subtitle")}</p></div><button type="button" disabled={loading} onClick={() => void load()}>{t("pda.history.refresh")}</button></header>
    <nav className="pda-history-filters" aria-label={t("pda.history.filters")}>
      {(["all", "checks", "counts"] as const).map((value) => <button type="button" className={filter === value ? "active" : ""} key={value} onClick={() => setFilter(value)}>{t(`pda.history.${value}`)}</button>)}
    </nav>
    {error && <p className="pda-count-error" role="alert">{error}</p>}
    {loading && <p className="pda-history-empty">{t("common.loading")}</p>}
    {!loading && events.length === 0 && <p className="pda-history-empty">{t("pda.history.empty")}</p>}
    <section className="pda-history-list">{events.map((item) => <article key={item.key}>
      <div className={`pda-history-icon ${item.kind}`}>{item.kind === "check" ? "✓" : "≣"}</div>
      <header><span>{t(item.kind === "check" ? "pda.history.check" : "pda.history.count")}</span><strong>{item.title}</strong><time>{date.format(new Date(item.at))}</time></header>
      <dl><div><dt>{t("pda.history.lines")}</dt><dd>{item.lines}</dd></div><div><dt>{t("pda.history.difference")}</dt><dd>{number.format(item.difference)}</dd></div></dl>
      <span className={`pda-history-state state-${item.status.toLowerCase()}`}>{t(`pda.history.status.${item.status}`)}</span>
    </article>)}</section>
  </section>;
}