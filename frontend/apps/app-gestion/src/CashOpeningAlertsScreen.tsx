import { useEffect, useMemo, useRef, useState, type FormEvent, type UIEvent } from "react";
import { ErpSelect, type LocaleCode, type UserSession } from "@tpverp/app-common";
import { ErpFilterChips } from "../../../packages/app-common/src/components/ErpFilterChips";
import { ReportDateRangeFilter, type ReportDateRange } from "../../../packages/app-common/src/components/ReportDateRangeFilter";
import { loadCashActivityFilterOptions, type CashActivityFilterOptions } from "./cashActivityApi";
import { loadCashAlertDetail, loadCashAlerts, reviewCashAlert, type CashAlert, type CashAlertDetail, type CashAlertFilters } from "./cashOpeningAlertsApi";

type Props = { session: UserSession; t: (key: string) => string; locale: LocaleCode; refreshSignal: number; onReviewed: () => void };
const alertKey = (alert: CashAlert) => `${alert.type}:${alert.id}`;

export function CashOpeningAlertsScreen({ session, t, locale, refreshSignal, onReviewed }: Props) {
  const [options, setOptions] = useState<CashActivityFilterOptions | null>(null);
  const [draft, setDraft] = useState<CashAlertFilters | null>(null);
  const [filters, setFilters] = useState<CashAlertFilters | null>(null);
  const [range, setRange] = useState<ReportDateRange>({ from: "", to: "", label: "" });
  const [rows, setRows] = useState<CashAlert[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detailOpen, setDetailOpen] = useState(true);
  const [comment, setComment] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [alertDetail, setAlertDetail] = useState<CashAlertDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState(false);
  const [detailSelectionSignal, setDetailSelectionSignal] = useState(0);
  const generation = useRef(0);
  const detailGeneration = useRef(0);
  const moreRef = useRef(false);
  const tableRef = useRef<HTMLDivElement>(null);
  const selected = rows.find(row => alertKey(row) === selectedId) ?? null;
  const showDetail = detailOpen && selected !== null;
  const canReview = session.permissions.includes("ADMIN") || session.permissions.includes("GESTION_CUENTAS");
  const currency = useMemo(() => new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", { style: "currency", currency: "EUR" }), [locale]);
  const amount = (value: number | null) => value === null ? "—" : currency.format(value);
  const date = useMemo(() => new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", { timeZone: options?.timezone, dateStyle: "short" }), [locale, options?.timezone]);
  const time = useMemo(() => new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", { timeZone: options?.timezone, timeStyle: "short" }), [locale, options?.timezone]);

  useEffect(() => {
    let active = true;
    void loadCashActivityFilterOptions(session.accessToken).then(value => {
      if (!active) return;
      const initial: CashAlertFilters = { from: "", to: "", terminalId: "", userId: "", status: "" };
      setOptions(value);
      setDraft(initial);
      setFilters(initial);
      setRange({ from: "", to: "", label: "" });
    }).catch(() => { if (active) { setLoading(false); setError(t("gestion.cashOpeningAlerts.loadError")); } });
    return () => { active = false; };
  }, [session.accessToken, t]);

  useEffect(() => {
    if (!filters) return;
    const current = ++generation.current;
    setRows([]);
    setCursor(null);
    setHasMore(false);
    setLoading(true);
    setError(null);
    void loadCashAlerts(filters, null, session.accessToken).then(page => {
      if (generation.current !== current) return;
      setRows(page.items);
      setCursor(page.nextCursor ?? null);
      setHasMore(page.hasMore);
      setSelectedId(existing => page.items.some(row => alertKey(row) === existing) ? existing : page.items[0] ? alertKey(page.items[0]) : null);
    }).catch(() => { if (generation.current === current) setError(t("gestion.cashOpeningAlerts.loadError")); })
      .finally(() => { if (generation.current === current) setLoading(false); });
  }, [filters, refreshSignal, session.accessToken, t]);

  useEffect(() => {
    setComment(selected?.comment ?? "");
    setNotice(null);
  }, [selectedId]);

  useEffect(() => {
    const current = ++detailGeneration.current;
    setAlertDetail(null);
    setDetailError(false);
    if (!showDetail || selected?.type !== "CLOSING") {
      setDetailLoading(false);
      return;
    }
    const controller = new AbortController();
    setDetailLoading(true);
    void loadCashAlertDetail(selected.id, selected.type, session.accessToken, controller.signal).then(value => {
      if (detailGeneration.current === current && value.alert.id === selected.id && value.alert.type === selected.type) {
        setAlertDetail(value);
        setRows(rows => rows.map(row => alertKey(row) === alertKey(value.alert) ? value.alert : row));
      }
    }).catch(() => {
      if (detailGeneration.current === current && !controller.signal.aborted) setDetailError(true);
    }).finally(() => {
      if (detailGeneration.current === current) setDetailLoading(false);
    });
    return () => { controller.abort(); detailGeneration.current++; };
  }, [showDetail, selected?.id, selected?.type, session.accessToken, detailSelectionSignal]);

  async function loadMore() {
    if (!filters || !cursor || !hasMore || moreRef.current) return;
    moreRef.current = true;
    setLoadingMore(true);
    const current = generation.current;
    try {
      const page = await loadCashAlerts(filters, cursor, session.accessToken);
      if (generation.current !== current) return;
      setRows(value => [...value, ...page.items.filter(item => !value.some(existing => alertKey(existing) === alertKey(item)))]);
      setCursor(page.nextCursor ?? null);
      setHasMore(page.hasMore);
    } catch { if (generation.current === current) setError(t("gestion.cashOpeningAlerts.loadError")); }
    finally { moreRef.current = false; setLoadingMore(false); }
  }

  function onScroll(event: UIEvent<HTMLDivElement>) {
    const element = event.currentTarget;
    if (element.scrollHeight - element.scrollTop - element.clientHeight <= 240) void loadMore();
  }

  function apply(event: FormEvent) {
    event.preventDefault();
    if (draft && validRange(draft)) {
      if (draft.from !== filters?.from || draft.to !== filters?.to) {
        setRange({ from: draft.from, to: draft.to, label: "", preset: draft.from || draft.to ? "CUSTOM" : undefined });
      }
      setFilters({ ...draft });
    }
  }

  function remove(field: "period" | "terminalId" | "userId" | "status") {
    if (!options) return;
    const change = field === "period" ? { from: "", to: "" }
      : field === "status" ? { status: "" as const } : { [field]: "" };
    setDraft(current => current ? { ...current, ...change } : current);
    setFilters(current => current ? { ...current, ...change } : current);
    if (field === "period") setRange({ from: "", to: "", label: "" });
  }

  function selectRange(value: ReportDateRange) {
    setRange(value);
    setDraft(current => current ? { ...current, from: value.from, to: value.to } : current);
    setFilters(current => current ? { ...current, from: value.from, to: value.to } : current);
  }

  function selectAlert(row: CashAlert) {
    setAlertDetail(null);
    setSelectedId(alertKey(row));
    setDetailOpen(true);
    setDetailSelectionSignal(value => value + 1);
  }

  function closeDetail() {
    if (saving) return;
    setDetailOpen(false);
    tableRef.current?.querySelector<HTMLElement>('[role="row"][aria-selected="true"]')?.focus();
  }

  async function review() {
    if (!selected || saving || selected.status !== "PENDING") return;
    if (!comment.trim()) { setNotice(t("gestion.cashOpeningAlerts.commentRequired")); return; }
    setSaving(true);
    setNotice(null);
    try {
      const updated = await reviewCashAlert(selected.id, selected.type, comment.trim(), selected.version, session.accessToken);
      setRows(current => current.map(row => alertKey(row) === alertKey(updated) ? updated : row));
      setNotice(t("gestion.cashOpeningAlerts.reviewSaved"));
      onReviewed();
    } catch { setNotice(t("gestion.cashOpeningAlerts.reviewError")); }
    finally { setSaving(false); }
  }

  return <section className="gestion-cash-alerts" aria-label={t("gestion.cashOpeningAlerts.title")}>
    <div className="gestion-cash-alerts-intro"><h3>{t("gestion.cashOpeningAlerts.title")}</h3><p>{t("gestion.cashOpeningAlerts.subtitle")}</p></div>
    {options && draft && <>
      <form className="gestion-cash-alerts-filters" onSubmit={apply}>
        <label><span>{t("gestion.cashClosures.terminal")}</span><ErpSelect value={draft.terminalId} aria-label={t("gestion.cashClosures.terminal")} options={[{ value: "", label: t("gestion.cashClosures.allTerminals") }, ...options.terminals.map(item => ({ value: item.id, label: item.name }))]} onChange={terminalId => setDraft({ ...draft, terminalId })} /></label>
        <label><span>{t("gestion.cashClosures.user")}</span><ErpSelect value={draft.userId} aria-label={t("gestion.cashClosures.user")} options={[{ value: "", label: t("gestion.cashClosures.allUsers") }, ...options.users.map(item => ({ value: item.id, label: item.name }))]} onChange={userId => setDraft({ ...draft, userId })} /></label>
        <label><span>{t("gestion.cashOpeningAlerts.status")}</span><ErpSelect value={draft.status} aria-label={t("gestion.cashOpeningAlerts.status")} options={[{ value: "", label: t("gestion.cashOpeningAlerts.allStatuses") }, { value: "PENDING", label: t("gestion.cashOpeningAlerts.pending") }, { value: "REVIEWED", label: t("gestion.cashOpeningAlerts.reviewed") }]} onChange={status => setDraft({ ...draft, status: status as CashAlertFilters["status"] })} /></label>
        <button type="submit" disabled={!validRange(draft)}>{t("gestion.cashClosures.apply")}</button>
      </form>
      <ErpFilterChips translate={t} onClear={() => { const reset: CashAlertFilters = { from: "", to: "", terminalId: "", userId: "", status: "" }; setDraft(reset); setFilters(reset); setRange({ from: "", to: "", label: "" }); }} chips={[
        { key: "period", label: `${t("gestion.cashClosures.from")} / ${t("gestion.cashClosures.to")}`, value: filters?.from && filters.to ? `${filters.from} — ${filters.to}` : filters?.from ? `${t("gestion.cashClosures.from")}: ${filters.from}` : filters?.to ? `${t("gestion.cashClosures.to")}: ${filters.to}` : "", onRemove: () => remove("period") },
        { key: "terminal", label: t("gestion.cashClosures.terminal"), value: filters?.terminalId ? options.terminals.find(item => item.id === filters.terminalId)?.name ?? filters.terminalId : "", onRemove: () => remove("terminalId") },
        { key: "user", label: t("gestion.cashClosures.user"), value: filters?.userId ? options.users.find(item => item.id === filters.userId)?.name ?? filters.userId : "", onRemove: () => remove("userId") },
        { key: "status", label: t("gestion.cashOpeningAlerts.status"), value: filters?.status ? t(`gestion.cashOpeningAlerts.${filters.status.toLowerCase()}`) : "", onRemove: () => remove("status") }
      ]} />
    </>}
    <div className={`gestion-cash-alerts-body${showDetail ? " with-detail" : ""}`}>
    <div className="gestion-cash-alerts-list">
    <div className="gestion-cash-alerts-table-wrap" role="table" aria-rowcount={rows.length} onScroll={onScroll} ref={tableRef}>
      <div className="gestion-cash-alert-row head" role="row">{["date", "time", "type", "terminal", "user", "expected", "counted", "difference", "status"].map(key => <span key={key} role="columnheader">{t(key === "date" || key === "time" || key === "terminal" || key === "user" ? `gestion.cashClosures.column.${key}` : `gestion.cashOpeningAlerts.${key}`)}</span>)}</div>
      {rows.map(row => <div className={`gestion-cash-alert-row${alertKey(row) === selectedId ? " selected" : ""}`} role="row" aria-selected={alertKey(row) === selectedId} tabIndex={0} key={alertKey(row)} onClick={() => selectAlert(row)} onKeyDown={event => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); selectAlert(row); } }}>
        <span role="cell">{date.format(new Date(row.occurredAt))}</span><span role="cell">{time.format(new Date(row.occurredAt))}</span><span role="cell">{t(`gestion.cashOpeningAlerts.type.${row.type.toLowerCase()}`)}</span><span role="cell">{row.terminalName}</span><span role="cell">{row.userName}</span><span role="cell">{amount(row.expectedFund)}</span><span role="cell">{amount(row.countedFund)}</span><span role="cell" className={row.difference === null ? "" : row.difference < 0 ? "shortage" : row.difference > 0 ? "surplus" : ""}>{amount(row.difference)}</span><span role="cell">{t(`gestion.cashOpeningAlerts.${row.status.toLowerCase()}`)}</span>
      </div>)}
      {loading && <div className="gestion-cash-closures-state">{t("common.loading")}</div>}
      {!loading && error && <div className="gestion-cash-closures-state error" role="alert">{error}</div>}
      {!loading && !error && rows.length === 0 && <div className="gestion-cash-closures-state">{t("gestion.cashOpeningAlerts.empty")}</div>}
      {loadingMore && <div className="gestion-cash-closures-more">{t("gestion.cashClosures.loadingMore")}</div>}
    </div>
    <footer className="gestion-cash-alerts-count">{t("gestion.cashOpeningAlerts.loaded").replace("{count}", String(rows.length))}</footer>
    </div>
    {showDetail && selected && <section className="gestion-cash-alert-detail" aria-label={t("gestion.cashOpeningAlerts.detail")} onKeyDown={event => { if (event.key === "Escape" && !saving) { event.preventDefault(); event.stopPropagation(); closeDetail(); } }}>
      <header className="gestion-cash-alert-detail-header"><h3>{t("gestion.cashOpeningAlerts.detail")}</h3><button type="button" aria-label={t("common.close")} title={t("common.close")} onClick={closeDetail} disabled={saving}>×</button></header><strong>{selected.terminalName} · {selected.userName} · {date.format(new Date(selected.occurredAt))} {time.format(new Date(selected.occurredAt))}</strong>
      {selected.type === "CLOSING" && detailLoading && <p className="gestion-cash-alert-detail-state" role="status">{t("common.loading")}</p>}
      {selected.type === "CLOSING" && detailError && <p className="gestion-cash-alert-detail-state" role="status">{t("gestion.cashOpeningAlerts.detailError")}</p>}
      {selected.type === "CLOSING" && alertDetail?.alert.id === selected.id && alertDetail.alert.type === selected.type && alertDetail.attempts.length > 0 ?
        <div className="gestion-cash-alert-attempts" aria-label={t("gestion.cashOpeningAlerts.attempts")}>
          {alertDetail.attempts.map(attempt => <section key={attempt.id} className="gestion-cash-alert-attempt">
            <h4>{t("gestion.cashOpeningAlerts.attempt").replace("{attempt}", String(attempt.attemptNumber))}</h4>
            <div className="gestion-cash-alert-attempt-meta"><span>{attempt.userName || attempt.username} · {date.format(new Date(attempt.occurredAt))} {time.format(new Date(attempt.occurredAt))}</span><span>{t("gestion.cashOpeningAlerts.sessionStateAtAttempt")}: {attempt.sessionClosed === null ? "—" : t(`gestion.cashOpeningAlerts.session.${attempt.sessionClosed ? "closed" : "open"}`)}</span></div>
            <div className="gestion-cash-alert-amounts"><div><span>{t("gestion.cashOpeningAlerts.expected")}</span><strong>{amount(attempt.expectedFund)}</strong></div><div><span>{t("gestion.cashOpeningAlerts.counted")}</span><strong>{amount(attempt.countedFund)}</strong></div><div><span>{t("gestion.cashOpeningAlerts.difference")}</span><strong className={attempt.difference !== null && attempt.difference < 0 ? "shortage" : ""}>{amount(attempt.difference)}</strong></div></div>
          </section>)}
        </div> : <>
          {selected.type === "CLOSING" && <div className="gestion-cash-alert-context"><span>{t("gestion.cashOpeningAlerts.attemptNumber")}: {selected.attemptNumber ?? "—"}</span><span>{t("gestion.cashOpeningAlerts.sessionStateAtAttempt")}: {selected.sessionClosed === null ? "—" : t(`gestion.cashOpeningAlerts.session.${selected.sessionClosed ? "closed" : "open"}`)}</span></div>}
          <div className="gestion-cash-alert-amounts"><div><span>{t("gestion.cashOpeningAlerts.expected")}</span><strong>{amount(selected.expectedFund)}</strong></div><div><span>{t("gestion.cashOpeningAlerts.counted")}</span><strong>{amount(selected.countedFund)}</strong></div><div><span>{t("gestion.cashOpeningAlerts.difference")}</span><strong className={selected.difference !== null && selected.difference < 0 ? "shortage" : ""}>{amount(selected.difference)}</strong></div></div>
        </>}
      {selected.status === "PENDING" && canReview && <><label className="gestion-cash-alert-comment"><span>{t("gestion.cashOpeningAlerts.reviewComment")}</span><textarea value={comment} maxLength={1000} onChange={event => setComment(event.target.value)} /></label><button type="button" onClick={() => void review()} disabled={saving}>{t("gestion.cashOpeningAlerts.markReviewed")}</button></>}
      {selected.status === "REVIEWED" && <div className="gestion-cash-alert-reviewed"><span>{t("gestion.cashOpeningAlerts.reviewer")}: {selected.reviewerName ?? selected.reviewerUsername}</span><span>{t("gestion.cashOpeningAlerts.reviewedAt")}: {selected.reviewedAt ? `${date.format(new Date(selected.reviewedAt))} ${time.format(new Date(selected.reviewedAt))}` : ""}</span><p>{selected.comment}</p></div>}
      {notice && <p role="status">{notice}</p>}
    </section>}
    </div>
    <ReportDateRangeFilter locale={locale} today={options?.businessDate || ""} earliestDate={options?.earliestDate || ""} value={range}
      disabled={!options} onChange={selectRange} />
  </section>;
}

function validRange(filters: CashAlertFilters) {
  return !(filters.from && filters.to && filters.from > filters.to);
}
