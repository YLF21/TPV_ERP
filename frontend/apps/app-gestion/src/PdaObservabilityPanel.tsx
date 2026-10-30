import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { apiRequest } from "@tpverp/app-common";
import type { LocaleCode } from "@tpverp/app-common";
import type { RegisteredTerminal } from "./TerminalManagementScreen";
import "./PdaObservabilityPanel.css";

type Category = "failures" | "blocked" | "inactive";
type PdaObservationEvent = {
  id: string; terminalId?: string | null; terminalName?: string | null; code: string;
  workId?: string | null; occurredAt: string; traceId?: string | null;
};
type InactiveTerminal = { terminalId: string; terminalName: string; lastSeenAt?: string | null; inactiveSince: string };
export type PdaObservabilityView = {
  generatedAt: string; windowFrom: string;
  summary: { syncFailures: number; syncConflicts: number; blockedWork: number; inactiveTerminals: number; averageLatencyMs: number; p95LatencyMs: number };
  syncFailures: PdaObservationEvent[]; blockedWork: PdaObservationEvent[]; inactiveTerminals: InactiveTerminal[];
  performance: { sampleCount: number; averageLatencyMs: number; p95LatencyMs: number; maxLatencyMs: number };
  page: number; size: number; totalEvents: number; totalPages: number;
};

export function pdaObservabilityPath(windowHours: number, terminalId: string) {
  const query = new URLSearchParams({ windowHours: String(windowHours), page: "0", size: "50" });
  if (terminalId) query.set("terminalId", terminalId);
  return `/pda-observability?${query.toString()}`;
}

function formatInstant(value: string | null | undefined, locale: LocaleCode) {
  if (!value) return "—";
  const date = new Date(value);
  const localeTag = locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES";
  return Number.isNaN(date.getTime()) ? "—" : new Intl.DateTimeFormat(localeTag, { dateStyle: "short", timeStyle: "medium" }).format(date);
}

export function PdaObservabilityPanel({ token, terminals, t, locale = "es" }: {
  token?: string; terminals: RegisteredTerminal[]; t: (key: string) => string; locale?: LocaleCode;
}) {
  const [windowHours, setWindowHours] = useState(24);
  const [terminalId, setTerminalId] = useState("");
  const [category, setCategory] = useState<Category>("failures");
  const [value, setValue] = useState<PdaObservabilityView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const requestRef = useRef<AbortController | null>(null);

  const load = useCallback(async () => {
    requestRef.current?.abort();
    const controller = new AbortController(); requestRef.current = controller;
    setLoading(true); setError(""); setValue(null);
    try {
      const next = await apiRequest<PdaObservabilityView>(pdaObservabilityPath(windowHours, terminalId), { token, signal: controller.signal });
      if (!controller.signal.aborted) setValue(next);
    } catch {
      if (!controller.signal.aborted) setError(t("gestion.pdaObservability.loadError"));
    } finally {
      if (requestRef.current === controller) { requestRef.current = null; setLoading(false); }
    }
  }, [t, terminalId, token, windowHours]);

  useEffect(() => { void load(); return () => requestRef.current?.abort(); }, [load]);
  const visibleTerminals = useMemo(() => {
    const map = new Map(terminals.filter((terminal) => terminal.type === "PDA").map((terminal) => [terminal.id, terminal.name]));
    value?.inactiveTerminals.forEach((terminal) => map.set(terminal.terminalId, terminal.terminalName));
    return Array.from(map, ([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name));
  }, [terminals, value]);
  const rows = category === "failures" ? value?.syncFailures ?? [] : category === "blocked" ? value?.blockedWork ?? [] : value?.inactiveTerminals ?? [];

  return <section id="gestion-terminal-observability-panel" className="gestion-pda-observability" role="tabpanel" aria-labelledby="gestion-terminal-observability-tab pda-observability-title" aria-busy={loading}>
    <header>
      <div><span>{t("gestion.pdaObservability.eyebrow")}</span><h3 id="pda-observability-title">{t("gestion.pdaObservability.title")}</h3><p>{t("gestion.pdaObservability.subtitle")}</p></div>
      <button type="button" disabled={loading} onClick={() => void load()}>{t("common.refresh")}</button>
    </header>
    <div className="gestion-pda-observability-filters" role="group" aria-label={t("gestion.pdaObservability.filters")}>
      <label><span>{t("gestion.pdaObservability.window")}</span><select value={windowHours} onChange={event => setWindowHours(Number(event.target.value))}>
        <option value={1}>{t("gestion.pdaObservability.window.1")}</option><option value={24}>{t("gestion.pdaObservability.window.24")}</option><option value={168}>{t("gestion.pdaObservability.window.168")}</option>
      </select></label>
      <label><span>{t("gestion.pdaObservability.terminal")}</span><select value={terminalId} onChange={event => setTerminalId(event.target.value)}>
        <option value="">{t("gestion.pdaObservability.allTerminals")}</option>{visibleTerminals.map(terminal => <option key={terminal.id} value={terminal.id}>{terminal.name}</option>)}
      </select></label>
      <label><span>{t("gestion.pdaObservability.category")}</span><select value={category} onChange={event => setCategory(event.target.value as Category)}>
        <option value="failures">{t("gestion.pdaObservability.failures")}</option><option value="blocked">{t("gestion.pdaObservability.blocked")}</option><option value="inactive">{t("gestion.pdaObservability.inactive")}</option>
      </select></label>
    </div>
    {error && <div className="gestion-pda-observability-error" role="alert"><span>{error}</span><button type="button" onClick={() => void load()}>{t("gestion.pdaObservability.retry")}</button></div>}
    {loading && !value ? <div className="gestion-pda-observability-loading" role="status">{t("common.loading")}</div> : value && <>
      <div className="gestion-pda-observability-kpis" aria-label={t("gestion.pdaObservability.summary")}>
        <button type="button" className={category === "failures" ? "selected danger" : "danger"} onClick={() => setCategory("failures")}><span>{t("gestion.pdaObservability.failures")}</span><strong>{value.summary.syncFailures}</strong><small>{value.summary.syncConflicts} {t("gestion.pdaObservability.conflicts")}</small></button>
        <button type="button" className={category === "blocked" ? "selected warning" : "warning"} onClick={() => setCategory("blocked")}><span>{t("gestion.pdaObservability.blocked")}</span><strong>{value.summary.blockedWork}</strong></button>
        <button type="button" className={category === "inactive" ? "selected" : ""} onClick={() => setCategory("inactive")}><span>{t("gestion.pdaObservability.inactive")}</span><strong>{value.summary.inactiveTerminals}</strong></button>
        <article><span>{t("gestion.pdaObservability.latency")}</span><strong>{`${Math.round(value.performance.p95LatencyMs)} ms`}</strong><small>{`${t("gestion.pdaObservability.average")}: ${Math.round(value.performance.averageLatencyMs)} ms · ${value.performance.sampleCount} ${t("gestion.pdaObservability.samples")}`}</small></article>
      </div>
      <div className="gestion-pda-observability-table-wrap">
        <table><caption className="sr-only">{t(`gestion.pdaObservability.${category}`)}</caption><thead><tr>
          <th>{t("gestion.pdaObservability.terminal")}</th><th>{category === "inactive" ? t("gestion.pdaObservability.lastSeen") : t("gestion.pdaObservability.code")}</th>
          {category !== "inactive" && <th>{t("gestion.pdaObservability.occurredAt")}</th>}
        </tr></thead><tbody>{rows.map(row => category === "inactive" ? <tr key={(row as InactiveTerminal).terminalId}>
          <td>{(row as InactiveTerminal).terminalName}</td><td>{formatInstant((row as InactiveTerminal).lastSeenAt, locale)}</td></tr> : <tr key={(row as PdaObservationEvent).id}>
          <td>{(row as PdaObservationEvent).terminalName || t("gestion.pdaObservability.unknownTerminal")}</td><td><strong>{(row as PdaObservationEvent).code}</strong></td>
          <td>{formatInstant((row as PdaObservationEvent).occurredAt, locale)}</td></tr>)}</tbody></table>
        {!rows.length && <p className="gestion-pda-observability-empty">{t("gestion.pdaObservability.empty")}</p>}
      </div>
      <footer><span>{t("gestion.pdaObservability.updated")}: {formatInstant(value.generatedAt, locale)}</span></footer>
    </>}
  </section>;
}
