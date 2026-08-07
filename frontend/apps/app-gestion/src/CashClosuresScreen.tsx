import {
  ErpSelect,
  TableLayoutHeaderCell,
  tableLayoutGridTemplate,
  useTableLayoutPreference,
  useTableSortPreference,
  visibleTableColumns,
  type TableColumnDefinition,
  type UserSession
} from "@tpverp/app-common";
import { useEffect, useMemo, useRef, useState, type CSSProperties, type FormEvent, type UIEvent } from "react";
import {
  loadCashClosureFilterOptions,
  loadCashClosures,
  type CashClosure,
  type CashClosureFilterOptions,
  type CashClosureFilters
} from "./cashClosuresApi";

type Translator = (key: string) => string;
type Props = { session: UserSession; t: Translator };
type ColumnKey = "terminal" | "date" | "time" | "user" | "expectedCash" | "retainedFund" | "discrepancy";

export const cashClosuresTableKey = "gestion.cashClosures.history";
export const cashClosureColumnDefinitions = [
  { key: "terminal", defaultWidth: 180 },
  { key: "date", defaultWidth: 118 },
  { key: "time", defaultWidth: 100 },
  { key: "user", defaultWidth: 210 },
  { key: "expectedCash", defaultWidth: 155 },
  { key: "retainedFund", defaultWidth: 155 },
  { key: "discrepancy", defaultWidth: 145 }
] as const satisfies readonly TableColumnDefinition<ColumnKey>[];

export function canReadCashClosures(session: UserSession) {
  return session.permissions.some((permission) => (
    permission === "ADMIN" || permission === "GESTION_CUENTAS" || permission === "CASH_READ"
  ));
}

export function CashClosuresScreen({ session, t }: Props) {
  const token = session.accessToken;
  const [options, setOptions] = useState<CashClosureFilterOptions | null>(null);
  const [draft, setDraft] = useState<CashClosureFilters | null>(null);
  const [filters, setFilters] = useState<CashClosureFilters | null>(null);
  const [rows, setRows] = useState<CashClosure[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [filterOpen, setFilterOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [optionsReload, setOptionsReload] = useState(0);
  const requestGeneration = useRef(0);
  const loadingMoreRef = useRef(false);
  const tableLayout = useTableLayoutPreference({
    app: "gestion",
    username: session.username,
    accessToken: token,
    tableKey: cashClosuresTableKey,
    definitions: cashClosureColumnDefinitions
  });
  const tableSorting = useTableSortPreference({
    app: "gestion",
    username: session.username,
    tableKey: cashClosuresTableKey,
    columns: cashClosureColumnDefinitions.map((column) => column.key),
    defaultSort: null
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const tableStyle = { gridTemplateColumns: tableLayoutGridTemplate(tableLayout.layout) } as CSSProperties;

  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadError(false);
    void loadCashClosureFilterOptions(token)
      .then((loaded) => {
        if (!active) return;
        const initial = todayFilters(loaded.businessDate);
        setOptions(loaded);
        setDraft(initial);
        setFilters(initial);
      })
      .catch(() => {
        if (active) {
          setOptions(null);
          setLoadError(true);
          setLoading(false);
        }
      });
    return () => { active = false; };
  }, [optionsReload, token]);

  useEffect(() => {
    if (!filters) return;
    const generation = ++requestGeneration.current;
    setLoading(true);
    setLoadError(false);
    setRows([]);
    setNextCursor(null);
    setHasMore(false);
    loadingMoreRef.current = false;
    setLoadingMore(false);
    void loadCashClosures(filters, null, token, tableSorting.sort)
      .then((page) => {
        if (requestGeneration.current !== generation) return;
        setRows(page.items);
        setNextCursor(page.nextCursor ?? null);
        setHasMore(Boolean(page.hasMore));
      })
      .catch(() => {
        if (requestGeneration.current !== generation) return;
        setLoadError(true);
      })
      .finally(() => {
        if (requestGeneration.current === generation) setLoading(false);
      });
  }, [filters, tableSorting.sort, token]);

  const dateFormatter = useMemo(() => new Intl.DateTimeFormat("es-ES", {
    dateStyle: "short",
    timeZone: options?.timezone
  }), [options?.timezone]);
  const timeFormatter = useMemo(() => new Intl.DateTimeFormat("es-ES", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    timeZone: options?.timezone
  }), [options?.timezone]);
  const moneyFormatter = useMemo(() => new Intl.NumberFormat("es-ES", {
    style: "currency",
    currency: "EUR"
  }), []);

  async function loadMore() {
    if (!filters || !nextCursor || !hasMore || loadingMoreRef.current) return;
    loadingMoreRef.current = true;
    setLoadingMore(true);
    const generation = requestGeneration.current;
    try {
      const page = await loadCashClosures(filters, nextCursor, token, tableSorting.sort);
      if (requestGeneration.current !== generation) return;
      setRows((current) => appendUniqueClosures(current, page.items));
      setNextCursor(page.nextCursor ?? null);
      setHasMore(Boolean(page.hasMore));
    } catch {
      if (requestGeneration.current === generation) setLoadError(true);
    } finally {
      loadingMoreRef.current = false;
      if (requestGeneration.current === generation) setLoadingMore(false);
    }
  }

  function handleScroll(event: UIEvent<HTMLDivElement>) {
    const table = event.currentTarget;
    if (table.scrollHeight - table.scrollTop - table.clientHeight <= 240) void loadMore();
  }

  function applyFilters(event: FormEvent) {
    event.preventDefault();
    if (!draft || !validRange(draft)) return;
    setFilters({ ...draft });
    setFilterOpen(false);
  }

  function resetToday() {
    if (!options) return;
    const initial = todayFilters(options.businessDate);
    setDraft(initial);
    setFilters(initial);
    setFilterOpen(false);
  }

  function refresh() {
    if (filters) {
      setFilters({ ...filters });
    } else {
      setOptionsReload((current) => current + 1);
    }
  }

  function renderCell(row: CashClosure, column: ColumnKey) {
    const closedAt = new Date(row.closedAt);
    if (column === "terminal") return <strong>{row.terminalName}</strong>;
    if (column === "date") return dateFormatter.format(closedAt);
    if (column === "time") return timeFormatter.format(closedAt);
    if (column === "user") {
      const showLogin = row.closingUsername
        && row.closingUsername.toLocaleLowerCase() !== row.closingUserName.toLocaleLowerCase();
      return <span className="gestion-cash-closure-user"><strong>{row.closingUserName}</strong>{showLogin && <small>{row.closingUsername}</small>}</span>;
    }
    if (column === "expectedCash") return moneyFormatter.format(row.expectedCash);
    if (column === "retainedFund") return <strong>{moneyFormatter.format(row.retainedFund)}</strong>;
    const state = discrepancyState(row.discrepancy);
    return <strong className={`gestion-cash-discrepancy ${state}`}>{moneyFormatter.format(row.discrepancy)}</strong>;
  }

  const summary = filters && options ? filterSummary(filters, options, t) : "";

  return (
    <section className="gestion-workspace gestion-cash-closures-workspace">
      <header className="gestion-dashboard-toolbar gestion-cash-closures-header">
        <div>
          <span className="gestion-eyebrow">{t("gestion.cashClosures.eyebrow")}</span>
          <h2>{t("gestion.cashClosures.title")}</h2>
          <p>{t("gestion.cashClosures.subtitle")}</p>
        </div>
        <button type="button" onClick={refresh} disabled={!filters || loading}>{t("common.refresh")}</button>
      </header>

      <div className="gestion-cash-closures-toolbar">
        <div>
          <span>{t("gestion.cashClosures.activeFilter")}</span>
          <strong>{summary || t("common.loading")}</strong>
        </div>
        <button type="button" className={filterOpen ? "active" : ""} onClick={() => setFilterOpen((current) => !current)} disabled={!draft}>
          {t("gestion.cashClosures.filter")}
        </button>
      </div>

      {filterOpen && draft && options && (
        <form className="gestion-cash-closures-filters" onSubmit={applyFilters}>
          <label>
            <span>{t("gestion.cashClosures.from")}</span>
            <input type="date" required value={draft.from} max={draft.to} onChange={(event) => setDraft({ ...draft, from: event.target.value })} />
          </label>
          <label>
            <span>{t("gestion.cashClosures.to")}</span>
            <input type="date" required value={draft.to} min={draft.from} onChange={(event) => setDraft({ ...draft, to: event.target.value })} />
          </label>
          <label>
            <span>{t("gestion.cashClosures.terminal")}</span>
            <ErpSelect
              value={draft.terminalId}
              options={[{ value: "", label: t("gestion.cashClosures.allTerminals") }, ...options.terminals.map((option) => ({ value: option.id, label: option.name }))]}
              onChange={(terminalId) => setDraft({ ...draft, terminalId })}
              aria-label={t("gestion.cashClosures.terminal")}
            />
          </label>
          <label>
            <span>{t("gestion.cashClosures.user")}</span>
            <ErpSelect
              value={draft.userId}
              options={[{ value: "", label: t("gestion.cashClosures.allUsers") }, ...options.users.map((option) => ({
                value: option.id,
                label: option.secondaryName && option.secondaryName.toLocaleLowerCase() !== option.name.toLocaleLowerCase()
                  ? `${option.name} · ${option.secondaryName}`
                  : option.name
              }))]}
              onChange={(userId) => setDraft({ ...draft, userId })}
              aria-label={t("gestion.cashClosures.user")}
            />
          </label>
          <label className="gestion-cash-closures-check">
            <input type="checkbox" checked={draft.onlyDiscrepancies} onChange={(event) => setDraft({ ...draft, onlyDiscrepancies: event.target.checked })} />
            <span>{t("gestion.cashClosures.onlyDiscrepancies")}</span>
          </label>
          <div className="gestion-cash-closures-filter-actions">
            <button type="button" onClick={resetToday}>{t("gestion.cashClosures.resetToday")}</button>
            <button type="submit" className="primary" disabled={!validRange(draft)}>{t("gestion.cashClosures.apply")}</button>
          </div>
        </form>
      )}

      <section className="gestion-cash-closures-list" aria-label={t("gestion.cashClosures.title")}>
        <div className="gestion-cash-closures-table" role="table" aria-rowcount={rows.length} onScroll={handleScroll}>
          <div className="gestion-cash-closure-row head" role="row" style={tableStyle}>
            {visibleColumns.map((column) => (
              <TableLayoutHeaderCell
                as="span"
                column={column}
                key={column.key}
                sortDirection={tableSorting.sort?.column === column.key ? tableSorting.sort.direction : null}
                sortLabel={`${t("party.sortBy")} ${t(`gestion.cashClosures.column.${column.key}`)}`}
                onSort={tableSorting.toggleSort}
                resizeLabel={`${t("gestion.cashClosures.resize")} ${t(`gestion.cashClosures.column.${column.key}`)}`}
                onReorder={tableLayout.reorderColumns}
                onMove={tableLayout.moveColumn}
                onResize={tableLayout.resizeColumn}
              >
                {t(`gestion.cashClosures.column.${column.key}`)}
              </TableLayoutHeaderCell>
            ))}
          </div>
          {rows.map((row, index) => (
            <div
              className={`gestion-cash-closure-row ${index === 0 || rows[index - 1]?.terminalId !== row.terminalId ? "terminal-start" : ""}`}
              role="row"
              style={tableStyle}
              key={row.id}
            >
              {visibleColumns.map((column) => (
                <span role="cell" data-column-key={column.key} key={column.key}>{renderCell(row, column.key)}</span>
              ))}
            </div>
          ))}
          {loading && <div className="gestion-cash-closures-state">{t("common.loading")}</div>}
          {!loading && loadError && <div className="gestion-cash-closures-state error"><span>{t("gestion.cashClosures.loadError")}</span><button type="button" onClick={refresh}>{t("gestion.cashClosures.retry")}</button></div>}
          {!loading && !loadError && rows.length === 0 && <div className="gestion-cash-closures-state">{t("gestion.cashClosures.empty")}</div>}
          {loadingMore && <div className="gestion-cash-closures-more">{t("gestion.cashClosures.loadingMore")}</div>}
        </div>
        <footer>
          <span>{t("gestion.cashClosures.loaded").replace("{count}", String(rows.length))}</span>
          {hasMore && !loadingMore && <span>{t("gestion.cashClosures.scrollMore")}</span>}
        </footer>
      </section>
    </section>
  );
}

function todayFilters(businessDate: string): CashClosureFilters {
  return { from: businessDate, to: businessDate, terminalId: "", userId: "", onlyDiscrepancies: false };
}

function validRange(filters: CashClosureFilters) {
  return Boolean(filters.from && filters.to && filters.from <= filters.to);
}

function appendUniqueClosures(current: CashClosure[], next: CashClosure[]) {
  const ids = new Set(current.map((row) => row.id));
  return [...current, ...next.filter((row) => !ids.has(row.id))];
}

function discrepancyState(discrepancy: number) {
  if (discrepancy < 0) return "shortage";
  if (discrepancy > 0) return "surplus";
  return "balanced";
}

function filterSummary(filters: CashClosureFilters, options: CashClosureFilterOptions, t: Translator) {
  const range = filters.from === filters.to
    ? formatBusinessDate(filters.from)
    : `${formatBusinessDate(filters.from)} — ${formatBusinessDate(filters.to)}`;
  const terminal = options.terminals.find((option) => option.id === filters.terminalId)?.name
    ?? t("gestion.cashClosures.allTerminals");
  const user = options.users.find((option) => option.id === filters.userId)?.name
    ?? t("gestion.cashClosures.allUsers");
  const discrepancy = filters.onlyDiscrepancies ? ` · ${t("gestion.cashClosures.onlyDiscrepancies")}` : "";
  return `${range} · ${terminal} · ${user}${discrepancy}`;
}

function formatBusinessDate(value: string) {
  const [year, month, day] = value.split("-");
  return year && month && day ? `${day}/${month}/${year}` : value;
}
