import { FormEvent, useState } from "react";
import { useRemote } from "../../app/RefreshContext";
import { useWorkspaceLabels } from "../../i18n/workspace";
import { request } from "../../lib/api";
import type { Credentials, LicenseSummary } from "../../lib/types";
import { workspaceApi, type FailureRow } from "../../lib/workspace-api";
import { formatDate, uniqueCompanies } from "../../shared/lib";
import type { Notice } from "../../shared/types";
import { EmptyState, Input, SectionHeader, StatusPill } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";
import { failureDateRange, validInstallationId } from "./failure-filters.mjs";
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
  const companies = uniqueCompanies(licenses);
  const state = useRemote(() => workspaceApi.failures(credentials, { ...filters, cursor: cursors[page], size: 50 }),
    [credentials.accessToken, filters, cursors[page]]);
  const stores = useRemote(() => workspaceApi.stores(credentials, { companyId: draft.companyId, q: storeSearch,
    ...(draft.activeStoresOnly ? { active: true } : {}), page: 0, size: 100 }),
    [credentials.accessToken, draft.companyId, storeSearch, draft.activeStoresOnly]);
  const detail = useRemote(() => selectedId
    ? request<FailureRow>(credentials, `/api/v1/admin/supervision/failures/${encodeURIComponent(selectedId)}`)
    : Promise.resolve(null), [credentials.accessToken, selectedId]);

  function apply(event: FormEvent) {
    event.preventDefault();
    if (!validInstallationId(draft.installationId.trim())) { onNotice({ type: "error", text: f("invalidInstallation") }); return; }
    if (storeSearch.trim() && !draft.storeId) { onNotice({ type: "error", text: f("invalidStore") }); return; }
    try {
      setFilters({ ...draft, installationId: draft.installationId.trim(), q: draft.q.trim(), ...failureDateRange(draft.from, draft.to) });
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

  return <section className="content-section">
    <SectionHeader title={f("title")} subtitle={f("scope")} />
    <form onSubmit={apply} className="compact-form-grid">
      <Input label={f("search")} maxLength={200} value={draft.q} onChange={q => setDraft({ ...draft, q })} />
      <label>{l("company")}<select className="control-input" value={draft.companyId} onChange={event => {
        setDraft({ ...draft, companyId: event.target.value, storeId: "", installationId: "" }); setStoreSearch("");
      }}><option value="">{l("all")}</option>{companies.map(company => <option key={company.companyId} value={company.companyId}>{company.companyName}</option>)}</select></label>
      <label>{f("storeSearch")}<input className="control-input" list="failure-stores" value={storeSearch} maxLength={200} onChange={event => {
        const value = event.target.value;
        const store = stores.data?.items.find(item => storeLabel(item) === value);
        setStoreSearch(value); setDraft({ ...draft, storeId: store?.id ?? "" });
      }} /><datalist id="failure-stores">{stores.data?.items.map(store => <option key={store.id} value={storeLabel(store)} />)}</datalist></label>
      {stores.error && <LoadState {...stores} />}
      <Input label={f("installation")} value={draft.installationId} maxLength={36} onChange={installationId => setDraft({ ...draft, installationId })} />
      <label>{l("source")}<select className="control-input" value={draft.source} onChange={event => setDraft({ ...draft, source: event.target.value })}><option value="">{l("all")}</option>{FAILURE_SOURCES.map(source => <option key={source} value={source}>{f(source)}</option>)}</select></label>
      <label>{l("status")}<select className="control-input" value={draft.status} onChange={event => setDraft({ ...draft, status: event.target.value })}><option value="">{l("all")}</option>{FAILURE_STATUSES.map(status => <option key={status} value={status}>{f(status)}</option>)}</select></label>
      <Input label={l("from")} type="date" value={draft.from} onChange={from => setDraft({ ...draft, from })} />
      <Input label={l("to")} type="date" value={draft.to} onChange={to => setDraft({ ...draft, to })} />
      <label className="access-option"><input type="checkbox" checked={draft.activeStoresOnly} onChange={event => { setDraft({ ...draft, activeStoresOnly: event.target.checked, storeId: "" }); setStoreSearch(""); }} />{l("activeStores")}</label>
      <div className="toolbar"><button type="submit">{f("apply")}</button><button type="button" onClick={clear}>{f("clear")}</button></div>
    </form>
    <p className="field-hint">{f("dateScope")} {f("centralScope")}</p>
    <LoadState {...state} />
    {state.data && <>
      {state.data.items.length === 0 ? <EmptyState text={l("empty")} /> : <div className="table-wrap"><table>
        <thead><tr>{[l("company"), l("store"), f("installation"), l("source"), l("severity"), l("status"), l("firstSeen"), l("lastSeen"), l("occurrences"), l("detail")].map(label => <th key={label} scope="col">{label}</th>)}</tr></thead>
        <tbody>{state.data.items.map(row => <tr key={row.id}>
          <td>{row.companyName ?? l("central")}</td>
          <td>{row.storeName ? <>{row.internalCode && <strong>{row.internalCode}<br /></strong>}{row.storeName}{row.storeActive === false && <><br />{l("inactive")}</>}</> : l("central")}</td>
          <td>{row.installationReference ?? row.installationId ?? "—"}</td>
          <td>{f(row.source)}<br /><small>{row.code}</small></td><td>{f(row.severity)}</td>
          <td><StatusPill status={f(row.status)} tone={row.status === "RESOLVED" ? "ok" : row.status === "OPEN" ? "warning" : "muted"} /></td>
          <td>{formatDate(row.firstSeenAt)}</td><td>{formatDate(row.lastSeenAt)}</td><td>{row.occurrences}</td>
          <td><button type="button" onClick={() => setSelectedId(row.id)}>{l("detail")}</button></td>
        </tr>)}</tbody>
      </table></div>}
      <nav className="pagination-controls"><button type="button" disabled={page === 0} onClick={() => { setPage(page - 1); setSelectedId(null); }}>{l("previous")}</button><span>{page + 1}</span><button type="button" disabled={!state.data.hasMore} onClick={next}>{l("next")}</button></nav>
    </>}
    {selectedId && <section className="content-section" aria-label={l("detail")}>
      <h3>{l("detail")}</h3><button type="button" onClick={() => setSelectedId(null)}>{f("close")}</button><LoadState {...detail} />
      {detail.data && <>
        <p>{f(`detail_${detail.data.source}`)}</p>
        <dl>
          <dt>{l("company")}</dt><dd>{detail.data.companyName ?? l("central")}</dd>
          <dt>{l("store")}</dt><dd>{detail.data.storeName ? `${detail.data.internalCode ?? ""} · ${detail.data.storeName}` : l("central")}</dd>
          <dt>{f("installation")}</dt><dd>{detail.data.installationId ?? "—"}</dd>
          <dt>{f("reference")}</dt><dd>{detail.data.sourceId}</dd>
          <dt>{f("code")}</dt><dd>{detail.data.code}</dd>
          <dt>{l("status")}</dt><dd>{f(detail.data.status)}</dd>
          <dt>{l("firstSeen")}</dt><dd>{formatDate(detail.data.firstSeenAt)}</dd>
          <dt>{l("lastSeen")}</dt><dd>{formatDate(detail.data.lastSeenAt)}</dd>
          <dt>{l("occurrences")}</dt><dd>{detail.data.occurrences}</dd>
        </dl>
        {onNavigate && <div className="toolbar">
          {detail.data.source === "SYNC_PROJECTION" && <button type="button" onClick={() => onNavigate("sync")}>{f("sync")}</button>}
          {["CENTRAL_SECURITY", "CENTRAL_INTEGRATION"].includes(detail.data.source) && permissions?.has("MANAGE_OPERATIONS") && <button type="button" onClick={() => onNavigate("outbox")}>{l("recovery")}</button>}
          <button type="button" onClick={() => onNavigate("support")}>{f("support")}</button>
        </div>}
      </>}
    </section>}
  </section>;
}
