import { useEffect, useRef, useState, type FormEvent } from "react";
import { api, ApiError } from "../../lib/api";
import type { Credentials, DemoRequest, DemoRequestAssignee, DemoRequestFollowUp, DemoRequestNote, DemoRequestStatus } from "../../lib/types";
import { useI18n } from "../../i18n";
import { useRefreshVersion } from "../../app/RefreshContext";
import { EmptyState, RetryError, SectionHeader, StatusPill } from "../../shared/ui";
import { formatDate } from "../../shared/lib";
import { WorkspaceDialog } from "../../shared/WorkspaceDialog";
import { demoRequestStatuses } from "./demo-request-query.mjs";
import { actionDateToInstant, isDemoActionOverdue, toLocalActionDate } from "./demo-follow-up.mjs";
import "./demo-requests.css";

const PAGE_SIZE = 20;
type FollowUpDraft = { assigneeUsername: string; nextAction: string; nextActionAt: string; note: string };
const emptyDraft: FollowUpDraft = { assigneeUsername: "", nextAction: "", nextActionAt: "", note: "" };
function draftFor(request: DemoRequest): FollowUpDraft {
  return { assigneeUsername: request.assigneeUsername ?? "", nextAction: request.nextAction ?? "", nextActionAt: toLocalActionDate(request.nextActionAt), note: "" };
}

export function DemoRequestsView({ credentials, permissions }: { credentials: Credentials; permissions: Set<string> }) {
  const { t } = useI18n();
  const refreshVersion = useRefreshVersion();
  const canRead = permissions.has("VIEW_ADMIN_DATA");
  const canManage = permissions.has("MANAGE_OPERATIONS");
  const [status, setStatus] = useState<DemoRequestStatus | "">("NEW");
  const [searchDraft, setSearchDraft] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [reload, setReload] = useState(0);
  const [items, setItems] = useState<DemoRequest[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selected, setSelected] = useState<DemoRequest | null>(null);
  const [busy, setBusy] = useState(false);
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const [result, setResult] = useState<{ error: boolean; text: string } | null>(null);
  const [draft, setDraft] = useState<FollowUpDraft>(emptyDraft);
  const [notes, setNotes] = useState<DemoRequestNote[]>([]);
  const [hasMoreNotes, setHasMoreNotes] = useState(false);
  const [assignees, setAssignees] = useState<DemoRequestAssignee[]>([]);
  const [detailReady, setDetailReady] = useState(false);
  const [draftInitialized, setDraftInitialized] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [detailReload, setDetailReload] = useState(0);
  const [conflictLoaded, setConflictLoaded] = useState(false);
  const [now, setNow] = useState(Date.now);
  const opener = useRef<HTMLElement | null>(null);
  const searchInput = useRef<HTMLInputElement | null>(null);
  const mounted = useRef(false);
  const activeToken = useRef(credentials.accessToken);
  const activePermissions = useRef(permissions);
  const selectedId = useRef<string | null>(null);
  const selectionToken = useRef<string | null>(null);
  const operation = useRef(0);
  const writeOperation = useRef<number | null>(null);
  const preserveDraftOnReload = useRef(false);
  activeToken.current = credentials.accessToken;
  activePermissions.current = permissions;
  selectedId.current = selected?.id ?? null;
  const baseline = selected ? draftFor(selected) : emptyDraft;
  const dirty = draftInitialized && (draft.assigneeUsername !== baseline.assigneeUsername || draft.nextAction.trim() !== baseline.nextAction
    || draft.nextActionAt !== baseline.nextActionAt || Boolean(draft.note.trim()));

  function failureText(error: unknown) {
    if (error instanceof ApiError && error.status === 403) return t("demoForbidden");
    if (error instanceof ApiError && error.status === 400) return t("demoInvalidRequest");
    if (error instanceof ApiError && error.status === 404) return t("demoNotFound");
    if (error instanceof ApiError && error.status === 409) return t("demoStatusConflict");
    return t("demoRequestFailed");
  }

  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 60_000); return () => clearInterval(timer); }, []);
  useEffect(() => {
    operation.current += 1;
    selectionToken.current = null; writeOperation.current = null;
    setSelected(null); setResult(null); setBusy(false); setNeedsRefresh(false); setPage(0);
    setDraft(emptyDraft); setDraftInitialized(false); setNotes([]); setAssignees([]); setDetailReady(false); setDetailError(null); setDetailLoading(false); setConflictLoaded(false);
  }, [credentials.accessToken, canRead]);
  useEffect(() => {
    let current = true;
    setItems([]); setTotal(0); setLoadError(null);
    if (!canRead) { setLoading(false); return; }
    setLoading(true);
    api.demoRequests(credentials, { page, size: PAGE_SIZE, status: status || undefined, query })
      .then(response => {
        if (!current) return;
        const lastPage = Math.max(0, Math.ceil(response.total / PAGE_SIZE) - 1);
        if (page > lastPage) { setPage(lastPage); return; }
        setItems(response.items); setTotal(response.total);
      })
      .catch(error => { if (current) setLoadError(failureText(error)); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [credentials.accessToken, canRead, refreshVersion, page, status, query, reload]);

  useEffect(() => {
    if (!selected?.id || !canRead || selectionToken.current !== credentials.accessToken) return;
    const token = credentials.accessToken;
    const id = selected.id;
    const generation = ++operation.current;
    const preserveDraft = preserveDraftOnReload.current;
    preserveDraftOnReload.current = false;
    const stillCurrent = () => mounted.current && operation.current === generation && selectedId.current === id
      && activeToken.current === token && activePermissions.current.has("VIEW_ADMIN_DATA");
    setBusy(true); setDetailLoading(true); setDetailReady(false); setDetailError(null);
    if (!preserveDraft) { setDraft(emptyDraft); setDraftInitialized(false); setNotes([]); setHasMoreNotes(false); }
    void Promise.allSettled([api.demoRequestFollowUp(credentials, id), canManage ? api.demoRequestAssignees(credentials) : Promise.resolve([] as DemoRequestAssignee[])])
      .then(([detail, people]) => {
        if (!stillCurrent()) return;
        if (detail.status === "rejected") {
          setDetailError(failureText(detail.reason));
          return;
        }
        const response = detail.value;
        setSelected(response.request); setNotes(response.notes); setHasMoreNotes(response.hasMoreNotes);
        if (!preserveDraft) { setDraft(draftFor(response.request)); setDraftInitialized(true); }
        else setConflictLoaded(true);
        if (people.status === "rejected") {
          setAssignees([]); setDetailError(t("demoFollowUpLoadFailed")); return;
        }
        setAssignees(people.value); setDetailReady(true);
        if (!preserveDraft) { setNeedsRefresh(false); setConflictLoaded(false); }
        if (detailReload > 0) setResult({ error: false, text: t("demoDetailRefreshed") });
      })
      .finally(() => { if (stillCurrent()) { setBusy(false); setDetailLoading(false); } });
    return () => { if (operation.current === generation) operation.current += 1; };
  }, [credentials.accessToken, canRead, canManage, selected?.id, detailReload]);

  function search(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setPage(0); setQuery(searchDraft.trim()); setReload(value => value + 1);
  }
  function open(request: DemoRequest) {
    if (busy || writeOperation.current !== null) return;
    opener.current = document.activeElement as HTMLElement;
    operation.current += 1; selectedId.current = request.id; selectionToken.current = credentials.accessToken;
    setSelected(request); setResult(null); setNeedsRefresh(false); setConflictLoaded(false); setBusy(true);
    setDetailReady(false); setDetailError(null); setDraft(emptyDraft); setDraftInitialized(false); setNotes([]); setHasMoreNotes(false);
  }
  async function refreshSelected() {
    if (!selected || busy || writeOperation.current !== null) return;
    if (dirty && !needsRefresh) { setResult({ error: true, text: t("demoFollowUpDirty") }); return; }
    // On conflict, show the saved notes without losing the unsaved note. A
    // deliberate discard is required before another write can be attempted.
    preserveDraftOnReload.current = dirty;
    setBusy(true); setDetailReload(value => value + 1); setReload(value => value + 1);
  }
  async function updateStatus(nextStatus: DemoRequestStatus) {
    if (!selected || busy || writeOperation.current !== null || needsRefresh || !detailReady || dirty || !canManage || selected.status === nextStatus) return;
    const token = credentials.accessToken;
    const requestId = selected.id;
    const generation = ++operation.current;
    writeOperation.current = generation;
    const stillCurrent = () => mounted.current && operation.current === generation && selectedId.current === requestId
      && activeToken.current === token && activePermissions.current.has("VIEW_ADMIN_DATA") && activePermissions.current.has("MANAGE_OPERATIONS");
    setBusy(true); setResult(null);
    try {
      const updated = await api.updateDemoRequestStatus(credentials, requestId, nextStatus, selected.status);
      if (!stillCurrent()) return;
      // Only show the returned state after the server has persisted the operation.
      setSelected(updated); setDraft(draftFor(updated)); setResult({ error: false, text: t("demoStatusSaved") });
      setReload(value => value + 1);
    } catch (error) {
      if (stillCurrent()) {
        if (error instanceof ApiError && error.status === 409) {
          setNeedsRefresh(true); setConflictLoaded(false); setReload(value => value + 1);
        }
        setResult({ error: true, text: failureText(error) });
      }
    } finally { if (writeOperation.current === generation) writeOperation.current = null; if (stillCurrent()) setBusy(false); }
  }
  function discardDraft() {
    if (!selected || busy || writeOperation.current !== null || !draftInitialized) return;
    setDraft(draftFor(selected)); setResult(null);
    if (conflictLoaded) { setNeedsRefresh(false); setConflictLoaded(false); }
  }
  function closeDetail() {
    if ((busy && !detailLoading) || writeOperation.current !== null) return;
    if (dirty) { setResult({ error: true, text: t("demoFollowUpDirty") }); return; }
    operation.current += 1; selectedId.current = null; selectionToken.current = null;
    setSelected(null); setResult(null); setDraft(emptyDraft); setDraftInitialized(false); setNotes([]); setDetailReady(false); setBusy(false); setDetailLoading(false);
  }
  async function saveFollowUp(event: FormEvent) {
    event.preventDefault();
    if (!selected || busy || writeOperation.current !== null || needsRefresh || !detailReady || !canManage || !dirty) return;
    if (draft.nextActionAt && !draft.nextAction.trim()) { setResult({ error: true, text: t("demoDateNeedsAction") }); return; }
    let nextActionAt: string | null;
    try {
      // Preserve server precision and the original offset at a repeated local
      // time when the operator has not edited the displayed date and minute.
      nextActionAt = draft.nextActionAt === baseline.nextActionAt ? selected.nextActionAt : actionDateToInstant(draft.nextActionAt);
    } catch { setResult({ error: true, text: t("demoInvalidActionDate") }); return; }
    const token = credentials.accessToken;
    const requestId = selected.id;
    const generation = ++operation.current;
    writeOperation.current = generation;
    const stillCurrent = () => mounted.current && operation.current === generation && selectedId.current === requestId
      && activeToken.current === token && activePermissions.current.has("VIEW_ADMIN_DATA") && activePermissions.current.has("MANAGE_OPERATIONS");
    setBusy(true); setResult(null);
    try {
      const updated: DemoRequestFollowUp = await api.updateDemoRequestFollowUp(credentials, requestId, {
        assigneeUsername: draft.assigneeUsername || null, nextAction: draft.nextAction.trim() || null,
        nextActionAt, note: draft.note.trim() || null, expectedVersion: selected.followUpVersion
      });
      if (!stillCurrent()) return;
      setSelected(updated.request); setDraft(draftFor(updated.request)); setNotes(updated.notes); setHasMoreNotes(updated.hasMoreNotes);
      setResult({ error: false, text: t("demoFollowUpSaved") }); setReload(value => value + 1);
    } catch (error) {
      if (stillCurrent()) {
        if (error instanceof ApiError && error.status === 409) { setNeedsRefresh(true); setConflictLoaded(false); setReload(value => value + 1); }
        setResult({ error: true, text: error instanceof ApiError && error.status === 409 ? t("demoFollowUpConflict") : failureText(error) });
      }
    } finally { if (writeOperation.current === generation) writeOperation.current = null; if (stillCurrent()) setBusy(false); }
  }
  function statusLabel(value: DemoRequestStatus) { return t(`demoStatus${value}`); }
  function products(request: DemoRequest) {
    return (request.products?.length ? request.products : [request.product]).map(value => t(`demoProduct${value}`)).join(" · ");
  }
  function date(value: string | null) { return value && Number.isFinite(Date.parse(value)) ? formatDate(value) : t("notAvailable"); }
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  if (!canRead) return <section className="content-section"><EmptyState text={t("demoForbidden")} /></section>;
  return <section className="content-section demo-inbox" aria-busy={loading}>
    <SectionHeader title={t("demoRequests")} subtitle={t("demoRequestsHelp")} />
    <form className="toolbar demo-inbox-filters" role="search" aria-label={t("demoSearch")} onSubmit={search}>
      <label className="toolbar-field">{t("demoStatus")}
        <select className="control-input" value={status} disabled={loading || busy} onChange={event => { setStatus(event.target.value as DemoRequestStatus | ""); setPage(0); }}>
          <option value="">{t("demoAllStatuses")}</option>
          {demoRequestStatuses.map(value => <option value={value} key={value}>{statusLabel(value)}</option>)}
        </select>
      </label>
      <label className="toolbar-field demo-inbox-search">{t("demoSearch")}
        <input ref={searchInput} className="control-input" type="search" value={searchDraft} maxLength={160} placeholder={t("demoSearchHint")} disabled={busy} onChange={event => setSearchDraft(event.target.value)} />
      </label>
      <button className="secondary-button" type="submit" disabled={loading || busy}>{t("demoApplySearch")}</button>
      {(query || searchDraft) && <button className="small-button" type="button" disabled={loading || busy} onClick={() => { setSearchDraft(""); setQuery(""); setPage(0); }}>{t("demoClearSearch")}</button>}
    </form>
    {loadError ? <RetryError message={loadError} onRetry={() => setReload(value => value + 1)} /> : loading ? <div role="status"><EmptyState text={t("loadingSaas")} /></div> : items.length === 0 ? <EmptyState text={t("demoEmpty")} /> : <div className="table-wrap">
      <table>
        <caption className="demo-inbox-caption">{t("demoCount").replace("{total}", String(total))}</caption>
        <thead><tr><th scope="col">{t("demoCreatedAt")}</th><th scope="col">{t("demoContact")}</th><th scope="col">{t("demoCompany")}</th><th scope="col">{t("demoProducts")}</th><th scope="col">{t("demoFollowUp")}</th><th scope="col">{t("demoStatus")}</th><th scope="col">{t("demoDetails")}</th></tr></thead>
        <tbody>{items.map(request => <tr key={request.id}>
          <td>{date(request.createdAt)}</td><td><strong>{request.name}</strong><br /><span>{request.email}</span></td><td>{request.company}</td><td>{products(request)}</td>
          <td className="demo-follow-up-summary"><strong>{request.assigneeUsername || t("demoUnassigned")}</strong>
            <span className="demo-action-preview" title={request.nextAction ?? undefined}>{request.nextAction || t("demoNoNextAction")}</span>
            {request.nextActionAt && <span>{date(request.nextActionAt)} {isDemoActionOverdue(request, now) && <strong className="demo-action-overdue">{t("demoOverdue")}</strong>}</span>}
          </td>
          <td><StatusPill status={statusLabel(request.status)} tone={request.status === "NEW" ? "warning" : request.status === "CONTACTED" ? "ok" : "muted"} /></td>
          <td><button className="small-button" type="button" disabled={busy} aria-label={`${t("demoDetails")}: ${request.name}`} onClick={() => open(request)}>{t("demoDetails")}</button></td>
        </tr>)}</tbody>
      </table>
    </div>}
    {!loadError && <nav className="pagination-controls" aria-label={t("pageLabel")}>
      <button className="small-button" type="button" disabled={loading || busy || page === 0} onClick={() => setPage(value => value - 1)}>{t("previousPage")}</button>
      <span role="status">{t("pageLabel")} {page + 1} / {pages} · {t("demoCount").replace("{total}", String(total))}</span>
      <button className="small-button" type="button" disabled={loading || busy || page + 1 >= pages} onClick={() => setPage(value => value + 1)}>{t("nextPage")}</button>
    </nav>}
    {selected && <WorkspaceDialog title={selected.company} subtitle={t("demoDetails")} closeLabel={t("demoCloseDetail")} busy={busy && !detailLoading} onClose={closeDetail} restoreFocus={() => opener.current?.isConnected ? opener.current : searchInput.current} className="demo-request-dialog">
      {result && <div className={`notice ${result.error ? "error" : "success"}`} role={result.error ? "alert" : "status"}>{result.text}</div>}
      <dl className="demo-request-details">
        <div><dt>{t("demoContact")}</dt><dd>{selected.name}</dd></div><div><dt>{t("demoCompany")}</dt><dd>{selected.company}</dd></div>
        <div><dt>{t("demoEmail")}</dt><dd>{selected.email}</dd></div><div><dt>{t("demoPhone")}</dt><dd>{selected.phone || t("notAvailable")}</dd></div>
        <div><dt>{t("demoProducts")}</dt><dd>{products(selected)}</dd></div><div><dt>{t("demoStatus")}</dt><dd>{statusLabel(selected.status)}</dd></div>
        <div><dt>{t("demoCreatedAt")}</dt><dd>{date(selected.createdAt)}</dd></div><div><dt>{t("demoLocale")}</dt><dd>{selected.locale}</dd></div>
        <div className="demo-request-message"><dt>{t("demoMessage")}</dt><dd>{selected.message || t("notAvailable")}</dd></div>
        <div><dt>{t("demoPrivacyAcceptedAt")}</dt><dd>{date(selected.privacyAcceptedAt)}</dd></div><div><dt>{t("demoReference")}</dt><dd>{selected.id}</dd></div>
      </dl>
      <details className="demo-request-attribution"><summary>{t("demoAttribution")}</summary><dl className="demo-request-details">
        <div><dt>{t("demoLandingPath")}</dt><dd>{selected.landingPath || t("notAvailable")}</dd></div><div><dt>{t("demoReferrer")}</dt><dd>{selected.referrer || t("notAvailable")}</dd></div>
        <div><dt>{t("demoUtmSource")}</dt><dd>{selected.utmSource || "—"}</dd></div>
        <div><dt>{t("demoUtmMedium")}</dt><dd>{selected.utmMedium || "—"}</dd></div>
        <div><dt>{t("demoUtmCampaign")}</dt><dd>{selected.utmCampaign || "—"}</dd></div>
      </dl></details>
      <section className="demo-follow-up-section" aria-labelledby="demo-follow-up-title" aria-busy={detailLoading}>
        <h4 id="demo-follow-up-title">{t("demoFollowUp")}</h4>
        {detailLoading && <p role="status">{t("demoFollowUpLoading")}</p>}
        {detailError && <div className="notice error" role="alert"><p>{detailError}</p><button className="secondary-button" type="button" disabled={busy} onClick={() => void refreshSelected()}>{t("demoRefreshDetail")}</button></div>}
        {canManage && detailReady ? <form className="demo-follow-up-form" onSubmit={event => void saveFollowUp(event)}>
          <div className="demo-follow-up-fields">
            <label>{t("demoAssignee")}<select className="control-input" value={draft.assigneeUsername} disabled={busy || needsRefresh} onChange={event => setDraft(value => ({ ...value, assigneeUsername: event.target.value }))}>
              <option value="">{t("demoUnassigned")}</option>
              {draft.assigneeUsername && !assignees.some(person => person.username === draft.assigneeUsername) && <option value={draft.assigneeUsername} disabled>{draft.assigneeUsername} · {t("demoAssigneeUnavailable")}</option>}
              {assignees.map(person => <option key={person.username} value={person.username}>{person.username}</option>)}
            </select></label>
            <label>{t("demoNextActionAt")}<input className="control-input" type="datetime-local" step={60} value={draft.nextActionAt} disabled={busy || needsRefresh} aria-describedby="demo-follow-up-timezone" onChange={event => setDraft(value => ({ ...value, nextActionAt: event.target.value }))} /><span id="demo-follow-up-timezone" className="module-help">{t("demoLocalTime")}</span></label>
            <label className="demo-follow-up-wide">{t("demoNextAction")}<textarea className="control-input" rows={2} maxLength={500} value={draft.nextAction} disabled={busy} readOnly={needsRefresh} onChange={event => setDraft(value => ({ ...value, nextAction: event.target.value }))} /></label>
            <label className="demo-follow-up-wide">{t("demoInternalNote")}<textarea className="control-input" rows={2} maxLength={2000} value={draft.note} disabled={busy} readOnly={needsRefresh} aria-describedby="demo-follow-up-note-help" onChange={event => setDraft(value => ({ ...value, note: event.target.value }))} /><span id="demo-follow-up-note-help" className="module-help">{t("demoInternalNoteHelp")}</span></label>
          </div>
          <div className="demo-request-actions"><button className="primary-button" type="submit" disabled={busy || needsRefresh || !dirty}>{t("demoFollowUpSave")}</button></div>
        </form> : !canManage && detailReady && <dl className="demo-request-details">
          <div><dt>{t("demoAssignee")}</dt><dd>{selected.assigneeUsername || t("demoUnassigned")}</dd></div>
          <div><dt>{t("demoNextActionAt")}</dt><dd>{date(selected.nextActionAt)}</dd></div>
          <div className="demo-follow-up-wide"><dt>{t("demoNextAction")}</dt><dd>{selected.nextAction || t("demoNoNextAction")}</dd></div>
        </dl>}
        {dirty && <div className="demo-follow-up-unsaved"><p className="module-help">{t("demoFollowUpDirty")}</p><button className="small-button" type="button" disabled={busy} onClick={discardDraft}>{t("demoDiscardFollowUp")}</button></div>}
        {needsRefresh && <div className="demo-follow-up-unsaved"><p className="module-help">{t("demoFollowUpConflict")}</p><button className="secondary-button" type="button" disabled={busy} onClick={() => void refreshSelected()}>{t("demoRefreshDetail")}</button></div>}
        {draftInitialized && <details className="demo-follow-up-notes"><summary>{t("demoNotes")} · {notes.length}</summary>
          {hasMoreNotes && <p className="module-help">{t("demoNotesLimited")}</p>}
          {notes.length ? <ol>{notes.map(note => <li key={note.id}><div><strong>{note.author}</strong><time dateTime={note.createdAt}>{date(note.createdAt)}</time></div><p>{note.body}</p></li>)}</ol> : <p className="module-help">{t("demoNoNotes")}</p>}
        </details>}
      </section>
      {canManage ? <div className="demo-request-actions" aria-label={t("demoUpdateStatus")} aria-busy={busy}>
        {selected.status !== "CONTACTED" && <button className="primary-button" type="button" disabled={busy || needsRefresh || dirty || !detailReady} onClick={() => void updateStatus("CONTACTED")}>{t("demoMarkContacted")}</button>}
        {selected.status !== "CLOSED" && <button className="secondary-button" type="button" disabled={busy || needsRefresh || dirty || !detailReady} onClick={() => void updateStatus("CLOSED")}>{t("demoMarkClosed")}</button>}
        {selected.status !== "NEW" && <button className="small-button" type="button" disabled={busy || needsRefresh || dirty || !detailReady} onClick={() => void updateStatus("NEW")}>{t("demoReopen")}</button>}
        {!detailError && !needsRefresh && <button className="small-button" type="button" disabled={busy || dirty} onClick={() => void refreshSelected()}>{t("demoRefreshDetail")}</button>}
        {busy && !detailLoading && <span role="status">{t("demoSaving")}</span>}
      </div> : <div className="demo-request-actions"><p className="module-help">{t("demoReadOnly")}</p><button className="small-button" type="button" disabled={busy} onClick={() => void refreshSelected()}>{t("demoRefreshDetail")}</button></div>}
      <p className="module-help">{t("demoManualFollowup")}</p>
    </WorkspaceDialog>}
  </section>;
}
