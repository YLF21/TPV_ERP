import { FormEvent, useEffect, useRef, useState } from "react";
import { useRemote } from "../../app/RefreshContext";
import { useWorkspaceLabels } from "../../i18n/workspace";
import { api, request } from "../../lib/api";
import type { Credentials, LicenseSummary } from "../../lib/types";
import { workspaceApi, type FailureRow } from "../../lib/workspace-api";
import { formatDate, uniqueCompanies } from "../../shared/lib";
import type { Notice } from "../../shared/types";
import { Input, SectionHeader, StatusPill } from "../../shared/ui";
import { SaasDataTable, type DataColumn } from "../../shared/table/SaasDataTable";
import "./failures.css";
import { LoadState } from "../../shared/workspace-ui";
import { failureDateRange, validInstallationId } from "./failure-filters.mjs";
import { repairSession } from "./repair-session";
import { FailureRepairsPanel } from "./FailureRepairsPanel";
import { FailureOverview, FailureTechnicalDetails } from "./FailureDetailContent";
import { FAILURE_SOURCES, FAILURE_STATUSES, useFailureLabels } from "./failure-labels";

const initialFilters = { companyId: "", storeId: "", installationId: "", source: "", status: "", from: "", to: "", q: "", activeStoresOnly: true };
type Filters = typeof initialFilters;
type Destination = "sync" | "outbox" | "support";

export function FailuresView({ credentials, licenses, onNotice, onNavigate, permissions }: {
  credentials: Credentials;
  licenses: LicenseSummary[];
  onNotice: (notice: Notice) => void;
  onNavigate?: (view: Destination) => void;
  permissions?: Set<string>;
}) {
  const l = useWorkspaceLabels();
  const f = useFailureLabels();
  const [draft, setDraft] = useState<Filters>(initialFilters);
  const [filters, setFilters] = useState<Filters>(initialFilters);
  const [storeSearch, setStoreSearch] = useState("");
  const [cursors, setCursors] = useState([""]);
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const detailRef = useRef<HTMLElement>(null);
  const detailOpener = useRef<HTMLElement | null>(null);
  const detailButtons = useRef(new Map<string, HTMLButtonElement>());
  // A datalist displays code + name; the server searches individual fields.
  const storeQuery = storeSearch.split(" · ", 1)[0].trim();
  const companyState = useRemote(() => api.companies(credentials), [credentials.accessToken]);
  const companies = companyState.data ?? uniqueCompanies(licenses);
  const state = useRemote(() => workspaceApi.failures(credentials, { ...filters, cursor: cursors[page], size: 50 }),
    [credentials.accessToken, filters, cursors[page]]);
  const stores = useRemote(() => workspaceApi.stores(credentials, { companyId: draft.companyId, q: storeQuery,
    ...(draft.activeStoresOnly ? { active: true } : {}), page: 0, size: 100 }),
    [credentials.accessToken, draft.companyId, storeQuery, draft.activeStoresOnly]);
  const detail = useRemote(() => selectedId
    ? request<FailureRow>(credentials, `/api/v1/admin/supervision/failures/${encodeURIComponent(selectedId)}`)
    : Promise.resolve(null), [credentials.accessToken, selectedId]);

  // Resolve pasted labels after their asynchronous lookup has arrived too.
  const selectedStoreId = draft.storeId || stores.data?.items.find(store => storeLabel(store) === storeSearch)?.id || "";
  useEffect(() => {
    if (!selectedId) return;
    detailRef.current?.focus({ preventScroll: true });
    detailRef.current?.scrollIntoView({ block: "start" });
  }, [selectedId]);
  function openDetail(id: string) {
    detailOpener.current = document.activeElement as HTMLElement;
    setSelectedId(id);
  }
  function closeDetail() {
    if (detailOpener.current?.isConnected) detailOpener.current.focus();
    else if (selectedId) detailButtons.current.get(selectedId)?.focus();
    setSelectedId(null);
  }

  function apply(event: FormEvent) {
    event.preventDefault();
    if (!validInstallationId(draft.installationId.trim())) { onNotice({ type: "error", text: f("invalidInstallation") }); return; }
    if (storeSearch.trim() && !selectedStoreId) { onNotice({ type: "error", text: f("invalidStore") }); return; }
    try {
      setFilters({ ...draft, storeId: selectedStoreId, installationId: draft.installationId.trim(), q: draft.q.trim(), ...failureDateRange(draft.from, draft.to) });
      setCursors([""]); setPage(0); setSelectedId(null);
    } catch { onNotice({ type: "error", text: f("invalidDates") }); }
  }
  function clear() {
    setDraft(initialFilters); setFilters(initialFilters); setStoreSearch(""); setCursors([""]); setPage(0); setSelectedId(null);
  }
  function next() {
    if (!state.data?.nextCursor) return;
    setCursors([...cursors.slice(0, page + 1), state.data.nextCursor]); setPage(page + 1); setSelectedId(null);
  }
  function storeLabel(store: { internalCode: string | null; code: string; name: string }) { return `${store.internalCode ?? store.code} · ${store.name}`; }

  const columns: DataColumn<FailureRow, string>[] = [
    { key: "company", label: l("company"), defaultWidth: 210, render: row => <strong>{row.companyName ?? l("central")}</strong> },
    { key: "store", label: l("store"), defaultWidth: 205, render: row => row.storeName ? <><strong>{row.internalCode}</strong><br />{row.storeName}{row.storeActive === false && <small> · {l("inactive")}</small>}</> : l("central") },
    { key: "source", label: l("source"), defaultWidth: 245, render: row => <>{f(row.source)}<br /><small>{row.code}{row.module && <> · {f("module_" + row.module)}</>}</small></> },
    { key: "severity", label: l("severity"), defaultWidth: 125, render: row => <span className={"failure-severity tone-" + row.severity.toLowerCase()}>{f(row.severity)}</span> },
    { key: "status", label: l("status"), defaultWidth: 130, render: row => <StatusPill status={f(row.status)} tone={row.status === "RESOLVED" ? "ok" : row.status === "OPEN" ? "warning" : "muted"} /> },
    { key: "lastSeen", label: l("lastSeen"), defaultWidth: 175, render: row => formatDate(row.lastSeenAt) },
    { key: "occurrences", label: l("occurrences"), defaultWidth: 115, align: "right", render: row => row.occurrences },
    { key: "detail", label: l("detail"), defaultWidth: 105, render: row => <button className="small-button" type="button" disabled={state.loading || Boolean(state.error)} ref={button => { if (button) detailButtons.current.set(row.id, button); else detailButtons.current.delete(row.id); }} aria-expanded={selectedId === row.id} aria-controls={selectedId === row.id ? "failure-detail" : undefined} onClick={() => openDetail(row.id)}>{l("detail")}</button> },
    { key: "installation", label: f("installation"), defaultWidth: 210, defaultVisible: false, render: row => row.installationReference ?? row.installationId ?? "—" },
    { key: "firstSeen", label: l("firstSeen"), defaultWidth: 175, defaultVisible: false, render: row => formatDate(row.firstSeenAt) },
  ];

  return <section className="content-section failures-workspace">
    <div className="failures-heading">
      <SectionHeader title={f("title")} subtitle={f("scope")} />
    </div>
    <form onSubmit={apply} className="failure-filters" aria-label={f("filters")}>
      <div className="failure-filter-main">
      <Input label={f("search")} maxLength={200} value={draft.q} onChange={q => setDraft({ ...draft, q })} />
      <label>{l("company")}<select className="control-input" value={draft.companyId} onChange={event => {
        setDraft({ ...draft, companyId: event.target.value, storeId: "", installationId: "" }); setStoreSearch("");
      }}><option value="">{l("all")}</option>{companies.map(company => <option key={company.companyId} value={company.companyId}>{company.companyName}</option>)}</select></label>

      <label>{f("storeSearch")}<input className="control-input" list="failure-stores" value={storeSearch} maxLength={200} onChange={event => {
        const value = event.target.value;
        const store = stores.data?.items.find(item => storeLabel(item) === value);
        setStoreSearch(value); setDraft({ ...draft, storeId: store?.id ?? "" });
      }} /><datalist id="failure-stores">{stores.data?.items.map(store => <option key={store.id} value={storeLabel(store)} />)}</datalist></label>
      <label>{l("status")}<select className="control-input" value={draft.status} onChange={event => setDraft({ ...draft, status: event.target.value })}><option value="">{l("all")}</option>{FAILURE_STATUSES.map(status => <option key={status} value={status}>{f(status)}</option>)}</select></label>
      </div>
      <div className="failure-filter-extra">
      <Input label={f("installation")} value={draft.installationId} maxLength={36} onChange={installationId => setDraft({ ...draft, installationId })} />
      <label>{l("source")}<select className="control-input" value={draft.source} onChange={event => setDraft({ ...draft, source: event.target.value })}><option value="">{l("all")}</option>{FAILURE_SOURCES.map(source => <option key={source} value={source}>{f(source)}</option>)}</select></label>
      <Input label={l("from")} type="date" value={draft.from} onChange={from => setDraft({ ...draft, from })} />
      <Input label={l("to")} type="date" value={draft.to} onChange={to => setDraft({ ...draft, to })} />
      </div>
      <div className="failure-filter-footer">
      <label className="failure-active-toggle"><input type="checkbox" checked={draft.activeStoresOnly} onChange={event => { setDraft({ ...draft, activeStoresOnly: event.target.checked, storeId: "" }); setStoreSearch(""); }} />{l("activeStores")}</label>
      <div className="failure-filter-actions"><button className="secondary-button" type="button" onClick={clear}>{f("clear")}</button><button className="primary-button" type="submit" disabled={state.loading}>{f("apply")}</button></div>
      </div>
      {companyState.error && <LoadState {...companyState} />}
      {(stores.loading || stores.error) && <LoadState {...stores} />}
    </form>
    <div className="failure-filter-help"><p>{f("searchHint")}</p><p>{f("dateScope")} {f("centralScope")}</p></div>
    <LoadState {...state} />
    {state.data && <>
      <div className="failure-results-heading">
        <h3>{f("results")}</h3><span aria-live="polite">{state.data.items.length} {f("onThisPage")}</span>
      </div>
      <div className="failure-table-region" aria-busy={state.loading}>
        <SaasDataTable username={credentials.username} tableKey="failures" label={f("title")} columns={columns} rows={state.data.items}
          sort={{ key: "", direction: "asc" }} onSort={() => undefined} onOpen={row => openDetail(row.id)} disabled={state.loading || Boolean(state.error)} />
        {!state.loading && !state.error && state.data.items.length === 0 && <div className="empty-state failure-empty" role="status">
          <strong>{f("emptyTitle")}</strong><p>{f("emptyHint")}</p>
        </div>}
      </div>
      <nav className="pagination-controls failure-pagination" aria-label={f("pages")}><span>{f("page")} {page + 1}</span><div>
        <button className="small-button" type="button" disabled={page === 0 || state.loading || Boolean(state.error)} onClick={() => { setPage(page - 1); setSelectedId(null); }}>{l("previous")}</button>
        <button className="small-button" type="button" disabled={!state.data.hasMore || state.loading || Boolean(state.error)} onClick={next}>{l("next")}</button>
      </div></nav>
    </>}
    {selectedId && <section id="failure-detail" ref={detailRef} tabIndex={-1} className="content-section failure-detail" aria-label={l("detail")}>
      <div className="failure-detail-heading"><h3>{l("detail")}</h3><button className="secondary-button" type="button" onClick={closeDetail}>{f("close")}</button></div><LoadState {...detail} />
      {detail.data && <>
        <FailureOverview failure={detail.data} />
        <FailureRepairsPanel key={`${repairSession(credentials).id}:${detail.data.id}`} credentials={credentials} failureKey={detail.data.id} companyId={detail.data.companyId} permissions={permissions} onSupport={onNavigate ? () => onNavigate("support") : undefined} />
        <FailureTechnicalDetails key={detail.data.id} failure={detail.data} onNotice={onNotice} />
        {onNavigate && <div className="toolbar">
          {detail.data.source === "SYNC_PROJECTION" && <button type="button" onClick={() => onNavigate("sync")}>{f("sync")}</button>}
          {["CENTRAL_SECURITY", "CENTRAL_INTEGRATION"].includes(detail.data.source) && permissions?.has("MANAGE_OPERATIONS") && <button type="button" onClick={() => onNavigate("outbox")}>{l("recovery")}</button>}
          <button type="button" onClick={() => onNavigate("support")}>{f("support")}</button>
        </div>}
      </>}
    </section>}
  </section>;
}
