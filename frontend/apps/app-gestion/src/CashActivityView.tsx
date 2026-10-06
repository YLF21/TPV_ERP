import { WindowCloseButton } from "../../../packages/app-common/src/components/WindowCloseButton";
import { useEffect, useMemo, useRef, useState, type CSSProperties, type UIEvent } from "react";
import {
  ErpSelect, TableLayoutHeaderCell, useTableLayoutPreference,
  useTableSortPreference, visibleTableColumns,
  type LocaleCode, type TableColumnDefinition, type UserSession
} from "@tpverp/app-common";
import { ErpFilterChips } from "../../../packages/app-common/src/components/ErpFilterChips";
import { ReportDateRangeFilter, type ReportDateRange } from "../../../packages/app-common/src/components/ReportDateRangeFilter";
import { loadCashClosure, type CashClosure } from "./cashClosuresApi";
import {
  loadCashActivity, loadCashActivityFilterOptions,
  type CashActivityFilterOptions, type CashActivityFilters, type CashActivityRow
} from "./cashActivityApi";

type Column = "reference" | "terminal" | "date" | "hour" | "user" | "action" | "cashState" | "concept" | "quantity" | "balance";
type Translator = (key: string) => string;
type Props = { session: UserSession; t: Translator; locale: LocaleCode; refreshSignal: number };

export const cashActivityColumns = [
  { key: "reference", defaultWidth: 145 }, { key: "terminal", defaultWidth: 150 },
  { key: "date", defaultWidth: 110 }, { key: "hour", defaultWidth: 90 },
  { key: "user", defaultWidth: 145 }, { key: "action", defaultWidth: 175 },
  { key: "cashState", defaultWidth: 130 }, { key: "concept", defaultWidth: 250 },
  { key: "quantity", defaultWidth: 130 }, { key: "balance", defaultWidth: 130 }
] as const satisfies readonly TableColumnDefinition<Column>[];

const actions = ["OPENING", "CLOSING", "ENTRADA", "RETIRADA", "RETIRADA_CIERRE", "ENTRADA_ENTRE_SESIONES", "RETIRADA_ENTRE_SESIONES", "DEVOLUCION_EFECTIVO"] as const;
const states = ["ABIERTA", "CERRADA"] as const;
const emptyFilters = (day: string): CashActivityFilters => ({ from: day, to: day, terminalId: "", userId: "", action: "", cashState: "" });

export function CashActivityView({ session, t, locale, refreshSignal }: Props) {
  const token = session.accessToken;
  const canSeeAmounts = session.permissions.includes("ADMIN") || session.permissions.includes("GESTION_CUENTAS");
  const [options, setOptions] = useState<CashActivityFilterOptions | null>(null);
  const [filters, setFilters] = useState<CashActivityFilters | null>(null);
  const [range, setRange] = useState<ReportDateRange>({ from: "", to: "", preset: "TODAY", label: "" });
  const [rows, setRows] = useState<CashActivityRow[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState(false);
  const [metadataRetry, setMetadataRetry] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<CashClosure | null>(null);
  const generation = useRef(0);
  const moreController = useRef<AbortController | null>(null);
  const loadingMoreRef = useRef(false);
  const detailGeneration = useRef(0);
  const layout = useTableLayoutPreference({ app: "gestion", username: session.username, accessToken: token,
    tableKey: "gestion.cash.activity", definitions: cashActivityColumns });
  const sorting = useTableSortPreference({ app: "gestion", username: session.username, tableKey: "gestion.cash.activity",
    columns: cashActivityColumns.filter(column => canSeeAmounts || (column.key !== "quantity" && column.key !== "balance")).map(column => column.key),
    defaultSort: { column: "reference", direction: "desc" } });
  const effectiveSort = !canSeeAmounts && (sorting.sort?.column === "quantity" || sorting.sort?.column === "balance") ? null : sorting.sort;
  const visible = visibleTableColumns(layout.layout);
  const flexibleColumn = visible.some(column => column.key === "concept") ? "concept" : visible.at(-1)?.key;
  const tableStyle = {
    gridTemplateColumns: visible.map(column => column.key === flexibleColumn
      ? `minmax(${column.width}px, 1fr)` : `${column.width}px`).join(" "),
    minWidth: visible.reduce((width, column) => width + column.width, 0)
  } as CSSProperties;
  const dateLocale = locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES";
  const date = useMemo(() => new Intl.DateTimeFormat(dateLocale, { dateStyle: "short", timeZone: options?.timezone || "UTC" }), [dateLocale, options?.timezone]);
  const hour = useMemo(() => new Intl.DateTimeFormat(dateLocale, { hour: "2-digit", minute: "2-digit", second: "2-digit", timeZone: options?.timezone || "UTC" }), [dateLocale, options?.timezone]);
  const money = useMemo(() => new Intl.NumberFormat(dateLocale, { style: "currency", currency: "EUR" }), [dateLocale]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    void loadCashActivityFilterOptions(token, controller.signal).then(loaded => {
      if (controller.signal.aborted) return;
      setOptions(loaded);
      setFilters(current => current ?? emptyFilters(loaded.businessDate));
      setRange(current => current.from ? current : { from: loaded.businessDate, to: loaded.businessDate, preset: "TODAY", label: "" });
    }).catch(() => {
      if (!controller.signal.aborted) { setError(true); setLoading(false); }
    });
    return () => controller.abort();
  }, [token, refreshSignal, metadataRetry]);

  useEffect(() => {
    if (!filters) return;
    const revision = ++generation.current;
    const controller = new AbortController();
    moreController.current?.abort();
    moreController.current = null;
    loadingMoreRef.current = false;
    setRows([]); setCursor(null); setHasMore(false); setSelectedId(null); setDetail(null);
    setLoading(true); setLoadingMore(false); setError(false);
    void loadCashActivity(filters, null, token, effectiveSort, controller.signal).then(page => {
      if (generation.current !== revision || controller.signal.aborted) return;
      setRows(page.items.filter(row => row.action !== "COBRO_EFECTIVO"));
      setCursor(page.nextCursor); setHasMore(page.hasMore);
    }).catch(() => { if (generation.current === revision && !controller.signal.aborted) setError(true); })
      .finally(() => { if (generation.current === revision && !controller.signal.aborted) setLoading(false); });
    return () => { controller.abort(); generation.current++; };
  }, [filters, effectiveSort, token, refreshSignal]);

  useEffect(() => {
    const revision = ++detailGeneration.current;
    if (!selectedId) { setDetail(null); return; }
    const row = rows.find(item => item.id === selectedId);
    if (row?.action !== "CLOSING" || !row.sessionId) { setDetail(null); return; }
    void loadCashClosure(row.sessionId, token).then(result => {
      if (detailGeneration.current === revision) setDetail(result);
    }).catch(() => { if (detailGeneration.current === revision) setDetail(null); });
    return () => { detailGeneration.current++; };
  }, [rows, selectedId, token]);

  function changeFilter(key: "terminalId" | "userId" | "action" | "cashState", value: string) {
    setFilters(current => current ? { ...current, [key]: value } : current);
  }

  async function loadMore() {
    if (!filters || !cursor || !hasMore || loading || loadingMoreRef.current) return;
    const revision = generation.current;
    const controller = new AbortController();
    moreController.current = controller;
    loadingMoreRef.current = true;
    setLoadingMore(true);
    try {
      const page = await loadCashActivity(filters, cursor, token, effectiveSort, controller.signal);
      if (generation.current !== revision || controller.signal.aborted) return;
      setRows(current => {
        const ids = new Set(current.map(row => row.id));
        return [...current, ...page.items.filter(row => row.action !== "COBRO_EFECTIVO" && !ids.has(row.id))];
      });
      setCursor(page.nextCursor); setHasMore(page.hasMore);
    } catch { if (generation.current === revision && !controller.signal.aborted) setError(true); }
    finally {
      if (moreController.current === controller) {
        moreController.current = null;
        loadingMoreRef.current = false;
        if (generation.current === revision) setLoadingMore(false);
      }
    }
  }

  function onScroll(event: UIEvent<HTMLDivElement>) {
    const element = event.currentTarget;
    if (element.scrollHeight - element.scrollTop - element.clientHeight <= 240) void loadMore();
  }

  function selectRow(row: CashActivityRow) {
    setSelectedId(row.id);
  }

  function selectAdjacent(index: number, direction: number) {
    const next = rows[index + direction];
    if (!next) return;
    setSelectedId(next.id);
    document.querySelector<HTMLElement>(`[data-cash-activity-id="${CSS.escape(next.id)}"]`)?.focus();
  }

  function labelForAction(action: string) { return t(`gestion.cashActivity.action.${action}`); }
  function labelForState(state: string | null) { return state ? t(`gestion.cashActivity.state.${state}`) : "—"; }
  function cell(row: CashActivityRow, column: Column) {
    switch (column) {
      case "reference": return row.reference || "—";
      case "terminal": return row.terminalName || row.terminalCode || "—";
      case "date": return date.format(new Date(row.occurredAt));
      case "hour": return hour.format(new Date(row.occurredAt));
      case "user": return row.userName || row.username || "—";
      case "action": return labelForAction(row.action);
      case "cashState": return labelForState(row.cashState);
      case "concept": return row.action === "OPENING" || row.action === "CLOSING" ? labelForAction(row.action) : row.concept || "—";
      case "quantity": return canSeeAmounts && row.amount != null ? money.format(row.amount) : "—";
      case "balance": return canSeeAmounts && row.balance != null ? money.format(row.balance) : "—";
    }
  }

  return <section className="gestion-cash-activity-view">
    <div className="gestion-cash-activity-filters">
      <label><span>{t("gestion.cashClosures.terminal")}</span><ErpSelect value={filters?.terminalId || ""} disabled={!options}
        options={[{ value: "", label: t("gestion.cashClosures.allTerminals") }, ...(options?.terminals || []).map(item => ({ value: item.id, label: item.name }))]}
        onChange={value => changeFilter("terminalId", value)} aria-label={t("gestion.cashClosures.terminal")} /></label>
      <label><span>{t("gestion.cashClosures.user")}</span><ErpSelect value={filters?.userId || ""} disabled={!options}
        options={[{ value: "", label: t("gestion.cashClosures.allUsers") }, ...(options?.users || []).map(item => ({ value: item.id, label: item.secondaryName && item.secondaryName !== item.name ? `${item.name} · ${item.secondaryName}` : item.name }))]}
        onChange={value => changeFilter("userId", value)} aria-label={t("gestion.cashClosures.user")} /></label>
      <label><span>{t("gestion.cashActivity.column.action")}</span><ErpSelect value={filters?.action || ""} disabled={!options}
        options={[{ value: "", label: t("gestion.cashActivity.allActions") }, ...actions.map(action => ({ value: action, label: labelForAction(action) }))]}
        onChange={value => changeFilter("action", value)} aria-label={t("gestion.cashActivity.column.action")} /></label>
      <label><span>{t("gestion.cashActivity.column.cashState")}</span><ErpSelect value={filters?.cashState || ""} disabled={!options}
        options={[{ value: "", label: t("gestion.cashActivity.allStates") }, ...states.map(state => ({ value: state, label: labelForState(state) }))]}
        onChange={value => changeFilter("cashState", value)} aria-label={t("gestion.cashActivity.column.cashState")} /></label>
    </div>
    {filters && options && <ErpFilterChips translate={t} onClear={() => setFilters({ ...filters, terminalId: "", userId: "", action: "", cashState: "" })}
      chips={[
        { key: "terminal", label: t("gestion.cashClosures.terminal"), value: options.terminals.find(item => item.id === filters.terminalId)?.name || "", onRemove: () => changeFilter("terminalId", "") },
        { key: "user", label: t("gestion.cashClosures.user"), value: options.users.find(item => item.id === filters.userId)?.name || "", onRemove: () => changeFilter("userId", "") },
        { key: "action", label: t("gestion.cashActivity.column.action"), value: filters.action ? labelForAction(filters.action) : "", onRemove: () => changeFilter("action", "") },
        { key: "state", label: t("gestion.cashActivity.column.cashState"), value: filters.cashState ? labelForState(filters.cashState) : "", onRemove: () => changeFilter("cashState", "") }
      ]} />}
    <div className="gestion-cash-activity-table-wrap" role="table" aria-label={t("gestion.cashClosures.title")} aria-rowcount={rows.length} onScroll={onScroll}>
      <div className="gestion-cash-activity-row head" role="row" style={tableStyle}>
        {visible.map(column => <TableLayoutHeaderCell as="span" key={column.key} column={column}
          sortDirection={effectiveSort?.column === column.key ? effectiveSort.direction : null}
          sortLabel={`${t("party.sortBy")} ${t(`gestion.cashActivity.column.${column.key}`)}`}
          onSort={canSeeAmounts || (column.key !== "quantity" && column.key !== "balance") ? sorting.toggleSort : undefined}
          resizeLabel={`${t("gestion.cashClosures.resize")} ${t(`gestion.cashActivity.column.${column.key}`)}`}
          onReorder={layout.reorderColumns} onMove={layout.moveColumn} onResize={layout.resizeColumn}
          onToggleVisibility={layout.toggleColumnVisibility}
          columnVisibilityOptions={layout.layout.map(item => ({ key: item.key, label: t(`gestion.cashActivity.column.${item.key}`), visible: item.visible }))}
          constrainColumnMenuToViewport>
          {t(`gestion.cashActivity.column.${column.key}`)}
        </TableLayoutHeaderCell>)}
      </div>
      {rows.map((row, index) => <div className={`gestion-cash-activity-row${selectedId === row.id ? " selected" : ""}`}
        role="row" aria-selected={selectedId === row.id} tabIndex={0} data-cash-activity-id={row.id}
        key={row.id} style={tableStyle} onClick={() => selectRow(row)}
        onKeyDown={event => {
          if (event.key === "Enter" || event.key === " ") { event.preventDefault(); selectRow(row); }
          if (event.key === "ArrowDown") { event.preventDefault(); selectAdjacent(index, 1); }
          if (event.key === "ArrowUp") { event.preventDefault(); selectAdjacent(index, -1); }
        }}>
        {visible.map(column => <span role="cell" data-column-key={column.key} key={column.key}>{cell(row, column.key)}</span>)}
      </div>)}
      {loading && <div className="gestion-cash-activity-state">{t("common.loading")}</div>}
      {!loading && error && <div className="gestion-cash-activity-state error">{t("gestion.cashActivity.loadError")}
        {!options && <button type="button" onClick={() => { setError(false); setLoading(true); setMetadataRetry(value => value + 1); }}>{t("gestion.cashClosures.retry")}</button>}
      </div>}
      {!loading && !error && rows.length === 0 && <div className="gestion-cash-activity-state">{t("gestion.cashActivity.empty")}</div>}
      {loadingMore && <div className="gestion-cash-activity-state">{t("gestion.cashActivity.loadingMore")}</div>}
    </div>
    <footer className="gestion-cash-activity-count">
      <span>{t("gestion.cashActivity.loaded").replace("{count}", String(rows.length))}</span>
      {hasMore && <button type="button" disabled={loadingMore} onClick={() => void loadMore()}>{t("gestion.cashActivity.more")}</button>}
    </footer>
    <ReportDateRangeFilter locale={locale} today={options?.businessDate || ""} earliestDate={options?.earliestDate || ""} value={range}
      disabled={!options} onChange={value => { setRange(value); setFilters(current => current ? { ...current, from: value.from, to: value.to } : current); }} />
    {detail && <section className="gestion-cash-closure-detail" aria-label={t("gestion.cashClosures.detail")}>
      <header><h3>{t("gestion.cashClosures.detail")}</h3><WindowCloseButton type="button" onLight aria-label={t("gestion.cashClosures.closeDetail")} onClick={() => setSelectedId(null)} >{t("gestion.cashClosures.closeDetail")}</WindowCloseButton></header>
      <div className="gestion-cash-closure-detail-summary"><span>{detail.terminalName}</span><span>{date.format(new Date(detail.closedAt))} {hour.format(new Date(detail.closedAt))}</span><span>{t("gestion.cashClosures.user")}: <strong>{detail.closingUserName}{detail.closingUsername.toLocaleLowerCase() !== detail.closingUserName.toLocaleLowerCase() ? ` · ${detail.closingUsername}` : ""}</strong></span>
        {canSeeAmounts && <><span>{t("gestion.cashClosures.finalWithdrawal")}: <strong>{money.format(detail.finalWithdrawalAmount)}</strong></span><span>{t("gestion.cashClosures.column.retainedFund")}: <strong>{money.format(detail.retainedFund)}</strong></span></>}</div>
      {canSeeAmounts && <div className="gestion-cash-closure-breakdowns">
        {(["retainedFundDenominations", "finalWithdrawalDenominations"] as const).map(key => <div key={key}>
          <h4>{t(key === "retainedFundDenominations" ? "gestion.cashClosures.retainedBreakdown" : "gestion.cashClosures.withdrawalBreakdown")}</h4>
          {detail[key]?.length ? <table><thead><tr><th>{t("gestion.cashClosures.denomination")}</th><th>{t("gestion.cashClosures.quantity")}</th><th>{t("gestion.cashClosures.total")}</th></tr></thead><tbody>{detail[key].map(item => <tr key={item.denomination}><td>{money.format(item.denomination)}</td><td>{item.quantity}</td><td>{money.format(item.denomination * item.quantity)}</td></tr>)}</tbody></table>
            : <p>{t("gestion.cashClosures.noBreakdown")}</p>}
        </div>)}
      </div>}
    </section>}
  </section>;
}
