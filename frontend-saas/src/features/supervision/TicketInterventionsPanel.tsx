import { useEffect, useId, useRef, useState } from "react";
import { ApiError } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import { workspaceApi, type InterventionAction, type TicketInterventionRequest, type TicketInterventionState } from "../../lib/workspace-api";
import { formatDate } from "../../shared/lib";
import { StatusPill } from "../../shared/ui";
import { IncidentTimeline } from "./IncidentTimeline";
import { repairSession, type InterventionDraft } from "./repair-session";
import { useFailureLabels } from "./failure-labels";
import { useInterventionLabels } from "./intervention-labels";

const waitingActions: InterventionAction[] = ["WAIT_CUSTOMER", "WAIT_MATERIAL"];
const actions: Record<string, InterventionAction[]> = {
  REMOTE_PENDING: ["START_SAAS", "START_REMOTE", "REQUIRE_ONSITE", ...waitingActions],
  SAAS_IN_PROGRESS: ["REQUEST_VERIFICATION", "START_REMOTE", "REQUIRE_ONSITE", ...waitingActions],
  REMOTE_IN_PROGRESS: ["REQUEST_VERIFICATION", "REQUIRE_ONSITE", ...waitingActions],
  ONSITE_REQUIRED: ["START_ONSITE", ...waitingActions], ONSITE_IN_PROGRESS: ["REQUEST_VERIFICATION", ...waitingActions],
  AWAITING_VERIFICATION: ["RESOLVE", "VERIFICATION_FAILED", "REQUIRE_ONSITE", ...waitingActions],
  WAITING_CUSTOMER: ["RESUME", ...waitingActions], WAITING_MATERIAL: ["RESUME", ...waitingActions], RESOLVED: ["REOPEN"],
};
const validRemoteId = (value: unknown) => value == null || (typeof value === "string" && /^\d{6,15}$/.test(value));
function validDetails(value: { assignee?: string | null; visitAt?: string | null; resolutionSummary?: string | null; verificationNotes?: string | null; confirmedBy?: string | null; assigneeUserId?: string | null; nextReviewAt?: string | null; resumeStatus?: string | null }) {
  return [value.assignee, value.resolutionSummary, value.verificationNotes, value.confirmedBy, value.assigneeUserId, value.resumeStatus].every(text => text == null || typeof text === "string")
    && [value.visitAt, value.nextReviewAt].every(date => date == null || typeof date === "string" && Number.isFinite(Date.parse(date)));
}
function validState(value: TicketInterventionState | null | undefined, ticketId: string, companyId: string): value is TicketInterventionState {
  return !!value && value.ticketId === ticketId && value.companyId === companyId
    && typeof value.status === "string" && typeof value.ticketStatus === "string" && validRemoteId(value.teamViewerId)
    && (value.assignees == null || Array.isArray(value.assignees) && value.assignees.every(user => user && typeof user.id === "string" && typeof user.username === "string"))
    && validDetails(value) && (value.failure == null || typeof value.failure.key === "string" && typeof value.failure.status === "string"
      && typeof value.failure.code === "string" && (value.failure.storeName == null || typeof value.failure.storeName === "string")
      && typeof value.failure.receivedAt === "string" && Number.isFinite(Date.parse(value.failure.receivedAt)))
    && Number.isSafeInteger(value.version) && value.version >= 0
    && Array.isArray(value.events) && value.events.every(event => event && typeof event.requestId === "string"
      && Number.isSafeInteger(event.version) && typeof event.action === "string" && typeof event.status === "string"
      && typeof event.note === "string" && typeof event.actor === "string" && typeof event.createdAt === "string"
      && Number.isFinite(Date.parse(event.createdAt)) && validRemoteId(event.teamViewerId) && validDetails(event));
}
export function TicketInterventionsPanel({ credentials, ticketId, companyId, ticketStatus, canManage, blocked, refreshVersion, onChanged, onBusyChange }: {
  credentials: Credentials; ticketId: string; companyId: string; ticketStatus: string; canManage: boolean;
  blocked: boolean; refreshVersion: number; onChanged: () => void; onBusyChange: (busy: boolean) => void;
}) {
  const fieldId = useId();
  const f = useInterventionLabels();
  const failureLabel = useFailureLabels();
  const session = repairSession(credentials);
  const pendingRequests = session.interventions;
  const pending = pendingRequests.get(ticketId);
  const [state, setState] = useState<TicketInterventionState | null>(null);
  const [draft, setDraft] = useState<InterventionDraft>(() => {
    const saved = session.interventionDrafts.get(ticketId);
    if (saved) return saved;
    const initial: InterventionDraft = { note: pending?.action === "SAVE_DETAILS" ? "" : pending?.note ?? "", remoteId: pending?.teamViewerId ?? "",
      assigneeUserId: pending?.assigneeUserId ?? "", visitAt: pending?.visitAt ? localDateTime(pending.visitAt) : "",
      resolution: pending?.resolutionSummary ?? "", checks: pending?.verificationNotes ?? "", confirmedBy: pending?.confirmedBy ?? "",
      nextReviewAt: pending?.nextReviewAt ? localDateTime(pending.nextReviewAt) : "", planningDirty: pending?.action === "SAVE_DETAILS", revisions: {} };
    session.interventionDrafts.set(ticketId, initial); return initial;
  });
  const { note, remoteId, assigneeUserId, visitAt, resolution, checks, confirmedBy, nextReviewAt } = draft;
  function edit(field: Exclude<keyof InterventionDraft, "revisions" | "planningDirty">, value: string) {
    const previous = session.interventionDrafts.get(ticketId) ?? draft;
    const next = { ...previous, [field]: value, planningDirty: previous.planningDirty || field === "assigneeUserId" || field === "visitAt",
      revisions: { ...previous.revisions, [field]: (previous.revisions[field] ?? 0) + 1 } };
    session.interventionDrafts.set(ticketId, next); setDraft(next);
  }
  function acceptDraft(nextState: TicketInterventionState, request?: TicketInterventionRequest) {
    const previous = session.interventionDrafts.get(ticketId) ?? draft;
    const next = { ...previous, revisions: { ...previous.revisions } };
    const submitted = request && session.interventionSubmissions.get(request.requestId);
    if (submitted) {
      for (const [field, revision] of Object.entries(submitted.fields)) {
        if ((previous.revisions[field] ?? 0) === revision) {
          if (field === "assigneeUserId") next.assigneeUserId = nextState.assigneeUserId ?? "";
          else if (field === "visitAt") next.visitAt = nextState.visitAt ? localDateTime(nextState.visitAt) : "";
          else if (field in next && field !== "revisions" && field !== "planningDirty") (next as unknown as Record<string, unknown>)[field] = "";
        }
      }
      if (request?.action === "SAVE_DETAILS" && ["assigneeUserId", "visitAt"].every(field => (previous.revisions[field] ?? 0) === submitted.fields[field as keyof InterventionDraft])) next.planningDirty = false;
      session.interventionSubmissions.delete(request!.requestId);
    }
    if (!next.planningDirty) { next.assigneeUserId = nextState.assigneeUserId ?? ""; next.visitAt = nextState.visitAt ? localDateTime(nextState.visitAt) : ""; }
    session.interventionDrafts.set(ticketId, next);
    if (current()) setDraft(next);
  }
  const [loading, setLoading] = useState(true);
  const [readFailed, setReadFailed] = useState(false);
  const [busy, setBusy] = useState(false); const busyRef = useRef(false);
  const [mustRefresh, setMustRefresh] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const notifiedSnapshot = useRef("");
  const generation = useRef(0); const writeGeneration = useRef(0); const alive = useRef(true);
  const scope = useRef({ credentials, ticketId, companyId }); scope.current = { credentials, ticketId, companyId };
  const current = () => alive.current && scope.current.credentials === credentials && scope.current.ticketId === ticketId && scope.current.companyId === companyId;
  const callbacks = useRef({ onChanged, onBusyChange }); callbacks.current = { onChanged, onBusyChange };
  useEffect(() => { alive.current = true; return () => { alive.current = false; generation.current++; writeGeneration.current++; }; }, []);
  useEffect(() => {
    const id = ++generation.current;
    setLoading(true);
    void workspaceApi.ticketInterventions(credentials, ticketId).then(next => {
      if (!current() || id !== generation.current) return;
      if (!validState(next, ticketId, companyId)) throw new Error("Invalid intervention state");
      const request = pendingRequests.get(ticketId);
      const confirmed = request && next.events.some(event => event.requestId === request.requestId);
      if (confirmed) { pendingRequests.delete(ticketId); setMessage(f("saved")); }
      acceptDraft(next, confirmed ? request : undefined);
      setState(next); setReadFailed(false); setMustRefresh(false);
      const snapshot = `${next.version}:${next.ticketStatus}`;
      if ((confirmed || next.ticketStatus !== ticketStatus) && notifiedSnapshot.current !== snapshot) {
        notifiedSnapshot.current = snapshot; callbacks.current.onChanged();
      }
    }).catch(() => { if (current() && id === generation.current) setReadFailed(true); })
      .finally(() => { if (current() && id === generation.current) setLoading(false); });
    return () => { generation.current++; };
  }, [credentials.accessToken, ticketId, companyId, refresh, refreshVersion]);

  async function write(action: InterventionAction) {
    if (!state || !canManage || blocked || loading || readFailed || mustRefresh || busyRef.current) return;
    const existing = pendingRequests.get(ticketId);
    if (existing ? existing.action !== action : !(action === "SAVE_DETAILS" ? state.status !== "RESOLVED" && actions[state.status] : actions[state.status]?.includes(action))) return;
    const text = (existing?.note ?? (action === "SAVE_DETAILS" ? f("planning") : note)).trim();
    const id = action === "START_REMOTE" ? (existing?.teamViewerId ?? remoteId).trim() : "";
    if (Array.from(text).length < 5 || Array.from(text).length > 2000) { setMessage(f("noteRequired")); return; }
    if (id && !/^\d{6,15}$/.test(id)) { setMessage(f("invalidId")); return; }
    if (!existing && action === "SAVE_DETAILS" && ((assigneeUserId && !state.assignees?.some(user => user.id === assigneeUserId)) || (visitAt && (!assigneeUserId || !Number.isFinite(new Date(visitAt).getTime()))))) { setMessage(f("planningRequired")); return; }
    if (!existing && action === "START_ONSITE" && !state.visitAt) { setMessage(f("visitRequired")); return; }
    if (!existing && ((action === "REQUEST_VERIFICATION" && Array.from(resolution.trim()).length < 5)
      || (action === "RESOLVE" && (Array.from(checks.trim()).length < 5 || Array.from(confirmedBy.trim()).length < 2)))) { setMessage(f("evidenceRequired")); return; }
    if (!existing && waitingActions.includes(action) && (!nextReviewAt || !Number.isFinite(new Date(nextReviewAt).getTime()) || new Date(nextReviewAt).getTime() <= Date.now())) { setMessage(f("reviewRequired")); return; }
    const request = existing ?? { requestId: crypto.randomUUID(), expectedVersion: state.version, expectedTicketStatus: state.ticketStatus,
      action, note: text, teamViewerId: id || null,
      ...(action === "SAVE_DETAILS" ? { assigneeUserId: assigneeUserId || null, visitAt: visitAt ? new Date(visitAt).toISOString() : null } : {}),
      ...(waitingActions.includes(action) ? { nextReviewAt: new Date(nextReviewAt).toISOString() } : {}),
      ...(action === "REQUEST_VERIFICATION" ? { resolutionSummary: resolution.trim() } : {}),
      ...(action === "RESOLVE" ? { verificationNotes: checks.trim(), confirmedBy: confirmedBy.trim() } : {}) };
    if (!existing) {
      const submittedFields = action === "SAVE_DETAILS" ? ["assigneeUserId", "visitAt"] : ["note", ...(action === "START_REMOTE" ? ["remoteId"] : []), ...(action === "REQUEST_VERIFICATION" ? ["resolution"] : []), ...(action === "RESOLVE" ? ["checks", "confirmedBy"] : []), ...(waitingActions.includes(action) ? ["nextReviewAt"] : [])];
      const activeDraft = session.interventionDrafts.get(ticketId) ?? draft;
      session.interventionSubmissions.set(request.requestId, { fields: Object.fromEntries(submittedFields.map(field => [field, activeDraft.revisions[field] ?? 0])) });
    }
    pendingRequests.set(ticketId, request);
    const writeId = ++writeGeneration.current;
    const ownsWrite = () => current() && writeGeneration.current === writeId;
    busyRef.current = true; setBusy(true); setMessage(null); generation.current++; callbacks.current.onBusyChange(true);
    try {
      const next = await workspaceApi.recordTicketIntervention(credentials, ticketId, request);
      if (!validState(next, ticketId, companyId) || !next.events.some(event => event.requestId === request.requestId)) throw new Error("Unconfirmed intervention response");
      if (!ownsWrite() || pendingRequests.get(ticketId)?.requestId !== request.requestId) return;
      generation.current++;
      pendingRequests.delete(ticketId); acceptDraft(next, request);
      setState(next); setMessage(f("saved")); notifiedSnapshot.current = `${next.version}:${next.ticketStatus}`; callbacks.current.onChanged();
    } catch (error) {
      const rejected = error instanceof ApiError && [400, 403, 404, 409, 422].includes(error.status);
      if (!ownsWrite() || pendingRequests.get(ticketId)?.requestId !== request.requestId) return;
      if (rejected && pendingRequests.get(ticketId)?.requestId === request.requestId) { pendingRequests.delete(ticketId); session.interventionSubmissions.delete(request.requestId); }
      setMessage(f(error instanceof ApiError && error.status === 409 ? "conflict" : rejected ? "rejected" : "writeError"));
      if (rejected) setMustRefresh(true);
    } finally {
      if (ownsWrite()) { busyRef.current = false; setBusy(false); callbacks.current.onBusyChange(false); }
    }
  }
  async function copy(id: string) {
    try { await navigator.clipboard.writeText(id); if (current()) setMessage(f("copied")); }
    catch { if (current()) setMessage(f("copyFailed")); }
  }
  const disabled = blocked || loading || readFailed || mustRefresh || busy || !canManage;
  const waitReason = state?.status.startsWith("WAITING_") ? [...state.events].sort((a, b) => b.version - a.version).find(event => waitingActions.includes(event.action as InterventionAction))?.note : null;
  const displayedId = state?.status !== "REMOTE_PENDING" && state?.teamViewerId && /^\d{6,15}$/.test(state.teamViewerId) ? state.teamViewerId : null;
  return <section className="content-section ticket-interventions" aria-label={f("title")}>
    <header className="intervention-heading"><h5>{f("title")}</h5>
      <button className="secondary-button" type="button" disabled={busy || loading} onClick={() => { setLoading(true); setRefresh(value => value + 1); }}>{f("reload")}</button>
    </header>
    {loading && <p role="status">{f("loading")}</p>}
    {readFailed && <p role="alert">{f("readError")}</p>}
    {message && <p role="status">{message}</p>}
    {pending && <p role="status">{f("pending")}</p>}
    {state && <div className={"intervention-phase" + (state.status === "RESOLVED" ? " intervention-phase--resolved" : "")}>
      <div><span>{f("phase")}</span><StatusPill status={f(state.status)} tone={state.status === "RESOLVED" ? "ok" : "muted"} /></div>
      {actions[state.status] && <p>{f(state.status + "_HELP")}</p>}
    </div>}
    {state?.status === "RESOLVED" && state.failure && state.failure.status !== "RESOLVED" && <div className="intervention-recovery-warning" role="status"><strong>{f("closedPending")}</strong><p>{f("technicalPending")}</p><small>{f("latestReceipt")}: {formatDate(state.failure.receivedAt)}</small></div>}
    {state && <div className="intervention-context-grid">
      <div className="intervention-failure-context"><h6>{f("linkedFailure")}</h6>
        {state.failure ? <><strong>{state.failure.storeName ?? state.failure.code}</strong> <StatusPill status={failureLabel(state.failure.status)} tone={state.failure.status === "RESOLVED" ? "ok" : "warning"} />
          <p>{f(state.failure.status === "RESOLVED" ? "technicalRecovered" : "technicalPending")}</p><small>{state.failure.code} · {formatDate(state.failure.receivedAt)}</small></>
          : <p>{f("noLinkedFailure")}</p>}
      </div>
      <div className="intervention-planning"><h6>{f("planning")}</h6>
        {state.status === "RESOLVED" ? <p>{state.assignee ?? "—"}{state.visitAt && <> · {formatDate(state.visitAt)}</>}</p> : <>
          <label>{f("assignee")}<select className="control-input" value={assigneeUserId} disabled={disabled || !!pending} onChange={e => edit("assigneeUserId", e.target.value)}><option value="">{f("unassigned")}</option>{state.assignees?.map(user => <option key={user.id} value={user.id}>{user.username}</option>)}{assigneeUserId && !state.assignees?.some(user => user.id === assigneeUserId) && <option value={assigneeUserId} disabled>{state.assignee ?? assigneeUserId}</option>}</select></label>
          {!state.assigneeUserId && state.assignee && <p className="field-hint">{f("legacyAssignee")}: {state.assignee}</p>}
          <label>{f("visitAt")}<input className="control-input" type="datetime-local" value={visitAt} disabled={disabled || !!pending} onChange={e => edit("visitAt", e.target.value)} /></label>
          <p className="field-hint">{f("visitHelp")}</p><button className="secondary-button" type="button" disabled={disabled || !!pending} onClick={() => void write("SAVE_DETAILS")}>{f("SAVE_DETAILS")}</button>
        </>}
      </div>
    </div>}
    {state?.status === "RESOLVED" && <div className="intervention-verification-record">
      {state.verificationNotes ? <><h6>{f("resolutionSummary")}</h6><p>{state.resolutionSummary}</p><h6>{f("verificationNotes")}</h6><p>{state.verificationNotes}</p><strong>{f("confirmedBy")}: {state.confirmedBy}</strong></> : <p>{f("legacyClosure")}</p>}
    </div>}
    <div className="intervention-workspace">
      {state && <div className="intervention-action-card">
        <h6>{f("nextStep")}</h6>
        <label><span id={`${fieldId}-note-label`}>{f("privateNote")}</span><span id={`${fieldId}-note-hint`} className="field-hint">{f("notePrivacy")}</span><textarea aria-labelledby={`${fieldId}-note-label`} aria-describedby={`${fieldId}-note-hint`} className="control-input" rows={4} placeholder={f("notePlaceholder")} minLength={5} maxLength={4000} required value={note} disabled={busy || !!pending || !canManage}
          onChange={event => edit("note", event.target.value)} /></label>
        {((state.status === "REMOTE_PENDING" || state.status === "SAAS_IN_PROGRESS") || pending?.action === "START_REMOTE") && <label><span id={`${fieldId}-remote-label`}>{f("remoteId")}</span><span id={`${fieldId}-remote-hint`} className="field-hint">{f("emptyRemoteId")}</span><input aria-labelledby={`${fieldId}-remote-label`} aria-describedby={`${fieldId}-remote-hint`} className="control-input" inputMode="numeric" maxLength={15} value={remoteId} disabled={busy || !!pending || !canManage} onChange={event => edit("remoteId", event.target.value)} /></label>}
        {(state.status === "SAAS_IN_PROGRESS" || state.status === "REMOTE_IN_PROGRESS" || state.status === "ONSITE_IN_PROGRESS" || pending?.action === "REQUEST_VERIFICATION") && <label>{f("resolutionSummary")}<textarea className="control-input" rows={3} maxLength={4000} value={resolution} disabled={disabled || !!pending} onChange={e => edit("resolution", e.target.value)} /></label>}
        {(state.status === "AWAITING_VERIFICATION" || pending?.action === "RESOLVE") && <>
          <div className="intervention-solution"><strong>{f("resolutionSummary")}</strong><p>{state.resolutionSummary}</p></div>
          <label>{f("verificationNotes")}<textarea className="control-input" rows={3} maxLength={4000} value={checks} disabled={disabled || !!pending} onChange={e => edit("checks", e.target.value)} /></label>
          <label>{f("confirmedBy")}<input className="control-input" maxLength={120} value={confirmedBy} disabled={disabled || !!pending} onChange={e => edit("confirmedBy", e.target.value)} /></label>
        </>}
        {state.status !== "RESOLVED" && <label>{f("nextReviewAt")}<input className="control-input" type="datetime-local" value={nextReviewAt} disabled={disabled || !!pending} onChange={e => edit("nextReviewAt", e.target.value)} /></label>}
        {state.nextReviewAt && <div className="intervention-wait-details">{waitReason && <p><strong>{f("waitReason")}: </strong>{waitReason}</p>}<strong>{f("reviewDate")}: {formatDate(state.nextReviewAt)}</strong>{state.resumeStatus && <span>{f("resumePhase")}: {f(state.resumeStatus)}</span>}</div>}
        <div className="intervention-actions">
          {pending ? <button className="primary-button" type="button" disabled={disabled} onClick={() => void write(pending.action)}>{f("retry")}</button>
            : (actions[state.status] ?? []).map((action, index) => <button className={index === 0 ? "primary-button" : "secondary-button"} key={action} type="button" disabled={disabled} onClick={() => void write(action)}>{f(action)}</button>)}
        </div>
        {!canManage && <p>{f("permission")}</p>}
      </div>}
      <aside className="intervention-connection">
        <div className="intervention-connection-title"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><rect x="3" y="3" width="18" height="13" rx="2" /><path d="M8 21h8M12 16v5M7 9h10m-3-3 3 3-3 3" /></svg><h6>{f("remoteTools")}</h6></div>
        <p>{f("external")}</p>
        {displayedId && <div className="intervention-remote-id"><span>TeamViewer ID</span><code>{displayedId}</code><button className="secondary-button" type="button" onClick={() => void copy(displayedId)}>{f("copy")}</button></div>}
        <a className="intervention-external-link" href="https://web.teamviewer.com/" target="_blank" rel="noopener noreferrer">{f("open")}<span aria-hidden="true">↗</span></a>
      </aside>
    </div>
    {state && <IncidentTimeline credentials={credentials} state={state} refreshVersion={refreshVersion + refresh} />}
  </section>;
}

function localDateTime(value: string) {
  const date = new Date(value);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0,16);
}
