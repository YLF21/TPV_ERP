import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ApiError, apiRequest } from "../api/client";
import { loadCashTimeline, type CashTimeline, type CashTimelineEntry } from "../sale/cashSessions";
import type { LocaleCode } from "../types";
import { ErpFilterChips } from "./ErpFilterChips";
import { ErpSelect } from "./ErpSelect";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { visibleTableColumns, type TableColumnDefinition } from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";
import { sortTableRows } from "./tableSorting";
import "./ErpClassicTables.css";
import "./CashActivityPanel.css";

type CashActivityColumn = "reference" | "hour" | "user" | "action" | "cashState" | "concept" | "quantity" | "balance";
type Props = { locale: LocaleCode; showTitle?: boolean; refreshContainer?: HTMLElement | null; currentUsername?: string; token?: string; terminalId?: string; request?: typeof apiRequest };

const columns = [
  { key: "reference", defaultWidth: 160, minWidth: 120 },
  { key: "hour", defaultWidth: 90, minWidth: 80 },
  { key: "user", defaultWidth: 150, minWidth: 100 },
  { key: "action", defaultWidth: 180, minWidth: 120 },
  { key: "cashState", defaultWidth: 130, minWidth: 110 },
  { key: "concept", defaultWidth: 280, minWidth: 160 },
  { key: "quantity", defaultWidth: 140, minWidth: 100 },
  { key: "balance", defaultWidth: 140, minWidth: 100 },
] satisfies readonly TableColumnDefinition<CashActivityColumn>[];

const copy = {
  es: { title: "Actividad caja", timeline: "Historial de hoy", refresh: "Actualizar", missingContext: "Inicia sesión y configura un terminal para consultar la actividad de caja.", error: "No se pudo consultar la actividad de caja.", loading: "Consultando actividad de caja…", reference: "Referencia", hour: "Hora", user: "Usuario", action: "Acción", cashState: "Estado", concept: "Concepto", quantity: "Cantidad", balance: "Saldo", filterUser: "Filtrar usuario", filterAction: "Filtrar acción", filterState: "Filtrar estado", all: "Todos", noData: "SIN DATOS", open: "Caja abierta", closed: "Caja cerrada", resize: "Cambiar ancho de", actions: { OPENING: "Apertura", CLOSING: "Cierre", ENTRADA: "Entrada", RETIRADA: "Retirada", RETIRADA_CIERRE: "Retirada de cierre", ENTRADA_ENTRE_SESIONES: "Entrada entre sesiones", RETIRADA_ENTRE_SESIONES: "Retirada entre sesiones", COBRO_EFECTIVO: "Cobro en efectivo", DEVOLUCION_EFECTIVO: "Devolución en efectivo" } },
  en: { title: "Cash activity", timeline: "Today's history", refresh: "Refresh", missingContext: "Sign in and configure a terminal to view cash activity.", error: "Cash activity could not be loaded.", loading: "Loading cash activity…", reference: "Reference", hour: "Time", user: "User", action: "Action", cashState: "Status", concept: "Concept", quantity: "Amount", balance: "Balance", filterUser: "Filter user", filterAction: "Filter action", filterState: "Filter status", all: "All", noData: "NO DATA", open: "Register open", closed: "Register closed", resize: "Resize", actions: { OPENING: "Opening", CLOSING: "Closing", ENTRADA: "Entry", RETIRADA: "Withdrawal", RETIRADA_CIERRE: "Closing withdrawal", ENTRADA_ENTRE_SESIONES: "Between-session entry", RETIRADA_ENTRE_SESIONES: "Between-session withdrawal", COBRO_EFECTIVO: "Cash payment", DEVOLUCION_EFECTIVO: "Cash refund" } },
  zh: { title: "钱箱活动", timeline: "今日流水", refresh: "刷新", missingContext: "请登录并配置终端后查看钱箱活动。", error: "无法查询钱箱活动。", loading: "正在查询钱箱活动…", reference: "参考编号", hour: "时间", user: "用户", action: "操作", cashState: "状态", concept: "项目", quantity: "金额", balance: "余额", filterUser: "筛选用户", filterAction: "筛选操作", filterState: "筛选状态", all: "全部", noData: "无数据", open: "钱箱已打开", closed: "钱箱已关闭", resize: "调整列宽", actions: { OPENING: "开箱", CLOSING: "关箱", ENTRADA: "存入", RETIRADA: "取出", RETIRADA_CIERRE: "关箱取出", ENTRADA_ENTRE_SESIONES: "班次间存入", RETIRADA_ENTRE_SESIONES: "班次间取出", COBRO_EFECTIVO: "现金收款", DEVOLUCION_EFECTIVO: "现金退款" } },
} as const;

export function CashActivityPanel({ locale, showTitle = true, refreshContainer, currentUsername = "", token, terminalId, request = apiRequest }: Props) {
  const t = copy[locale];
  const [timeline, setTimeline] = useState<CashTimeline | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [filterUser, setFilterUser] = useState("");
  const [filterAction, setFilterAction] = useState("");
  const [filterState, setFilterState] = useState("");
  const requestRevision = useRef(0);
  const layout = useTableLayoutPreference<CashActivityColumn>({ app: "venta", username: currentUsername, accessToken: token, tableKey: "cash.activity", definitions: columns });
  const visibleColumns = visibleTableColumns(layout.layout);
  const tableWidth = visibleColumns.reduce((width, column) => width + column.width, 0);
  const dateLocale = locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES";
  const money = useMemo(() => new Intl.NumberFormat(dateLocale, { style: "currency", currency: "EUR" }), [dateLocale]);

  const load = useCallback(async () => {
    const revision = ++requestRevision.current;
    setTimeline(null);
    if (!token || !terminalId) { setError(null); setLoading(false); return; }
    setLoading(true); setError(null);
    try {
      const result = await loadCashTimeline(terminalId, token, request);
      if (requestRevision.current === revision) setTimeline(result);
    }
    catch (failure) {
      if (requestRevision.current === revision) setError(failure instanceof ApiError
        ? (typeof failure.problem?.detail === "string" && failure.problem.detail ? failure.problem.detail : failure.message || t.error)
        : failure instanceof Error ? failure.message : t.error);
    } finally { if (requestRevision.current === revision) setLoading(false); }
  }, [request, t.error, terminalId, token]);
  useEffect(() => { void load(); return () => { requestRevision.current += 1; }; }, [load]);

  // Older servers may still return payments; they are not cash activity rows.
  const items = (timeline?.items ?? []).filter(item => item.action !== "COBRO_EFECTIVO");
  const userOptions = Array.from(new Map(items.map(item => [item.userId || item.username, item.userName || item.username])).entries());
  const actionOptions = Array.from(new Set(items.map(item => item.action)));
  const stateOptions = ["ABIERTA", "CERRADA"] as const;
  const actionLabel = (action: string) => t.actions[action as keyof typeof t.actions] ?? action.replaceAll("_", " ");
  const stateLabel = (state: string) => state === "ABIERTA" ? t.open : state === "CERRADA" ? t.closed : "—";
  const visibleItems = sortTableRows(items.filter(item => (!filterUser || (item.userId || item.username) === filterUser)
    && (!filterAction || item.action === filterAction) && (!filterState || item.cashState === filterState)),
    { column: "reference", direction: "desc" }, item => item.reference, locale);

  function cell(item: CashTimelineEntry, column: CashActivityColumn) {
    switch (column) {
      case "reference": return item.reference || "—";
      case "hour": {
        try { return new Intl.DateTimeFormat(dateLocale, { hour: "2-digit", minute: "2-digit", timeZone: timeline?.timezone || "UTC" }).format(new Date(item.occurredAt)); }
        catch { return "—"; }
      }
      case "user": return item.userName || item.username;
      case "action": return actionLabel(item.action);
      case "cashState": return stateLabel(item.cashState ?? "");
      case "concept": return item.action === "OPENING" || item.action === "CLOSING" ? actionLabel(item.action) : item.concept || "—";
      case "quantity": return item.amount == null ? "—" : money.format(item.amount);
      case "balance": return item.balance == null ? "—" : money.format(item.balance);
    }
  }

  const refreshButton = <button className="secondary-button" type="button" disabled={!token || !terminalId || loading} onClick={() => void load()}>{t.refresh}</button>;
  return <section className="settings-card cash-activity-panel erp-classic-tables">
    {(showTitle || refreshContainer === undefined) && <div className="settings-card-heading">{showTitle && <h3>{t.title}</h3>}{refreshContainer === undefined && refreshButton}</div>}
    {refreshContainer && createPortal(refreshButton, refreshContainer)}
    {!token || !terminalId ? <div className="settings-empty-state">{t.missingContext}</div> : <>
      {error && <p className="cash-timeline-error" role="alert">{error}</p>}
      {loading && !timeline && <p role="status">{t.loading}</p>}
      <div className="cash-timeline-filters"><div><span>{t.filterUser}</span><ErpSelect className="erp-select--compact" aria-label={t.filterUser} value={filterUser} onChange={setFilterUser} options={[{ value: "", label: t.all }, ...userOptions.map(([value, label]) => ({ value, label }))]} /></div>
        <div><span>{t.filterAction}</span><ErpSelect className="erp-select--compact" aria-label={t.filterAction} value={filterAction} onChange={setFilterAction} options={[{ value: "", label: t.all }, ...actionOptions.map(value => ({ value, label: actionLabel(value) }))]} /></div>
        <div><span>{t.filterState}</span><ErpSelect className="erp-select--compact" aria-label={t.filterState} value={filterState} onChange={setFilterState} options={[{ value: "", label: t.all }, ...stateOptions.map(value => ({ value, label: stateLabel(value) }))]} /></div></div>
      <ErpFilterChips locale={locale} chips={[
        { key: "user", label: t.user, value: filterUser ? userOptions.find(([key]) => key === filterUser)?.[1] ?? filterUser : "", onRemove: () => setFilterUser("") },
        { key: "action", label: t.action, value: filterAction ? actionLabel(filterAction) : "", onRemove: () => setFilterAction("") },
        { key: "state", label: t.cashState, value: filterState ? stateLabel(filterState) : "", onRemove: () => setFilterState("") },
      ]} onClear={() => { setFilterUser(""); setFilterAction(""); setFilterState(""); }} />
      <div className="cash-report-table-wrap"><table className="cash-timeline-table" aria-label={t.timeline} style={{ minWidth: tableWidth }}>
        <colgroup>{visibleColumns.map(column => <col key={column.key} data-column-key={column.key} style={{ width: column.width }} />)}</colgroup>
        <thead><tr>{visibleColumns.map(column => <TableLayoutHeaderCell key={column.key} column={column} constrainColumnMenuToViewport
          className={column.key === "quantity" || column.key === "balance" ? "cash-timeline-amount" : ""}
          resizeLabel={`${t.resize} ${t[column.key]}`} onResize={layout.resizeColumn} onReorder={layout.reorderColumns} onMove={layout.moveColumn}
          onToggleVisibility={layout.toggleColumnVisibility} columnVisibilityOptions={layout.layout.map(candidate => ({ key: candidate.key, label: t[candidate.key], visible: candidate.visible, disabled: candidate.visible && visibleColumns.length <= 1 }))}>
          {t[column.key]}</TableLayoutHeaderCell>)}</tr></thead>
        <tbody>{visibleItems.map(item => <tr key={item.id}>{visibleColumns.map(column => <td key={column.key} data-column-key={column.key}
          className={column.key === "quantity" || column.key === "balance" ? "cash-timeline-amount" : column.key === "cashState" ? `cash-activity-state cash-activity-state--${item.cashState?.toLowerCase() ?? "none"}` : undefined}>{cell(item, column.key)}</td>)}</tr>)}
          {visibleItems.length === 0 && <tr><td colSpan={visibleColumns.length}>{t.noData}</td></tr>}</tbody></table></div>
    </>}
  </section>;
}
