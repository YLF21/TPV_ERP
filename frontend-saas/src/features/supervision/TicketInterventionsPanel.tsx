import { useEffect, useRef, useState } from "react";
import { ApiError } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import { workspaceApi, type InterventionAction, type TicketInterventionState } from "../../lib/workspace-api";
import { formatDate } from "../../shared/lib";
import { StatusPill } from "../../shared/ui";
import { repairSession } from "./repair-session";
import { useInterventionLabels } from "./intervention-labels";

const actions: Record<string, InterventionAction[]> = {
  REMOTE_PENDING: ["START_REMOTE", "REQUIRE_ONSITE"], REMOTE_IN_PROGRESS: ["REQUIRE_ONSITE", "RESOLVE"],
  ONSITE_REQUIRED: ["START_ONSITE"], ONSITE_IN_PROGRESS: ["RESOLVE"], RESOLVED: ["REOPEN"],
};
const validRemoteId = (value: unknown) => value == null || (typeof value === "string" && /^\d{6,15}$/.test(value));
function validState(value: TicketInterventionState | null | undefined, ticketId: string, companyId: string): value is TicketInterventionState {
  return !!value && value.ticketId === ticketId && value.companyId === companyId
    && typeof value.status === "string" && typeof value.ticketStatus === "string" && validRemoteId(value.teamViewerId)
    && Number.isSafeInteger(value.version) && value.version >= 0
    && Array.isArray(value.events) && value.events.every(event => event && typeof event.requestId === "string"
      && Number.isSafeInteger(event.version) && typeof event.action === "string" && typeof event.status === "string"
      && typeof event.note === "string" && typeof event.actor === "string" && typeof event.createdAt === "string"
      && Number.isFinite(Date.parse(event.createdAt)) && validRemoteId(event.teamViewerId));
}
export function TicketInterventionsPanel({ credentials, ticketId, companyId, ticketStatus, canManage, blocked, refreshVersion, onChanged, onBusyChange }: {
  credentials: Credentials; ticketId: string; companyId: string; ticketStatus: string; canManage: boolean;
  blocked: boolean; refreshVersion: number; onChanged: () => void; onBusyChange: (busy: boolean) => void;
}) {
  const f = useInterventionLabels();
  const pendingRequests = repairSession(credentials).interventions;
  const pending = pendingRequests.get(ticketId);
  const [state, setState] = useState<TicketInterventionState | null>(null);
  const [note, setNote] = useState(() => pending?.note ?? "");
  const [remoteId, setRemoteId] = useState(() => pending?.teamViewerId ?? "");
  const [loading, setLoading] = useState(true);
  const [readFailed, setReadFailed] = useState(false);
  const [busy, setBusy] = useState(false); const busyRef = useRef(false);
  const [mustRefresh, setMustRefresh] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const notifiedSnapshot = useRef("");
  const generation = useRef(0); const alive = useRef(true);
  const scope = useRef({ credentials, ticketId, companyId }); scope.current = { credentials, ticketId, companyId };
  const current = () => alive.current && scope.current.credentials === credentials && scope.current.ticketId === ticketId && scope.current.companyId === companyId;
  const callbacks = useRef({ onChanged, onBusyChange }); callbacks.current = { onChanged, onBusyChange };
  useEffect(() => { alive.current = true; return () => { alive.current = false; generation.current++; }; }, []);
  useEffect(() => {
    const id = ++generation.current;
    setLoading(true);
    void workspaceApi.ticketInterventions(credentials, ticketId).then(next => {
      if (!current() || id !== generation.current) return;
      if (!validState(next, ticketId, companyId)) throw new Error("Invalid intervention state");
      const request = pendingRequests.get(ticketId);
      const confirmed = request && next.events.some(event => event.requestId === request.requestId);
      if (confirmed) { pendingRequests.delete(ticketId); setNote(""); setRemoteId(""); setMessage(f("saved")); }
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
    if (existing ? existing.action !== action : !actions[state.status]?.includes(action)) return;
    const text = (existing?.note ?? note).trim();
    const id = action === "START_REMOTE" ? (existing?.teamViewerId ?? remoteId).trim() : "";
    if (Array.from(text).length < 5 || Array.from(text).length > 2000) { setMessage(f("noteRequired")); return; }
    if (id && !/^\d{6,15}$/.test(id)) { setMessage(f("invalidId")); return; }
    const request = existing ?? { requestId: crypto.randomUUID(), expectedVersion: state.version, expectedTicketStatus: state.ticketStatus,
      action, note: text, teamViewerId: id || null };
    pendingRequests.set(ticketId, request);
    busyRef.current = true; setBusy(true); setMessage(null); generation.current++; callbacks.current.onBusyChange(true);
    try {
      const next = await workspaceApi.recordTicketIntervention(credentials, ticketId, request);
      if (!validState(next, ticketId, companyId) || !next.events.some(event => event.requestId === request.requestId)) throw new Error("Unconfirmed intervention response");
      if (!current()) return;
      if (pendingRequests.get(ticketId)?.requestId === request.requestId) pendingRequests.delete(ticketId);
      setState(next); setNote(""); setRemoteId(""); setMessage(f("saved")); notifiedSnapshot.current = `${next.version}:${next.ticketStatus}`; callbacks.current.onChanged();
    } catch (error) {
      const rejected = error instanceof ApiError && [400, 403, 404, 409, 422].includes(error.status);
      if (!current()) return;
      if (rejected && pendingRequests.get(ticketId)?.requestId === request.requestId) pendingRequests.delete(ticketId);
      setMessage(f(error instanceof ApiError && error.status === 409 ? "conflict" : rejected ? "rejected" : "writeError"));
      if (rejected) setMustRefresh(true);
    } finally {
      if (current()) { busyRef.current = false; setBusy(false); callbacks.current.onBusyChange(false); }
    }
  }
  async function copy(id: string) {
    try { await navigator.clipboard.writeText(id); if (current()) setMessage(f("copied")); }
    catch { if (current()) setMessage(f("copyFailed")); }
  }
  const disabled = blocked || loading || readFailed || mustRefresh || busy || !canManage;
  const displayedId = state?.status !== "REMOTE_PENDING" && state?.teamViewerId && /^\d{6,15}$/.test(state.teamViewerId) ? state.teamViewerId : null;
  return <section className="content-section" aria-label={f("title")}>
    <h5>{f("title")}</h5>
    {loading && <p role="status">{f("loading")}</p>}
    {readFailed && <p role="alert">{f("readError")}</p>}
    {message && <p role="status">{message}</p>}
    {pending && <p role="status">{f("pending")}</p>}
    <button type="button" disabled={busy || loading} onClick={() => { setLoading(true); setRefresh(value => value + 1); }}>{f("reload")}</button>
    <p><a href="https://web.teamviewer.com/" target="_blank" rel="noopener noreferrer">{f("open")}</a></p>
    <p>{f("external")}</p>
    {displayedId && <p>TeamViewer ID: <code>{displayedId}</code> <button type="button" onClick={() => void copy(displayedId)}>{f("copy")}</button></p>}
    {state && <>
      <p>{f("phase")}: <StatusPill status={f(state.status)} tone={state.status === "RESOLVED" ? "ok" : "muted"} /></p>
      <label>{f("note")}<textarea className="control-input" minLength={5} maxLength={4000} required value={note} disabled={busy || !!pending || !canManage}
        onChange={event => setNote(event.target.value)} /></label>
      {(state.status === "REMOTE_PENDING" || pending?.action === "START_REMOTE") && <label>{f("remoteId")}<span className="field-hint">{f("emptyRemoteId")}</span><input className="control-input" inputMode="numeric" maxLength={15} value={remoteId} disabled={busy || !!pending || !canManage} onChange={event => setRemoteId(event.target.value)} /></label>}
      <div className="toolbar">
        {pending ? <button type="button" disabled={disabled} onClick={() => void write(pending.action)}>{f("retry")}</button>
          : (actions[state.status] ?? []).map(action => <button key={action} type="button" disabled={disabled} onClick={() => void write(action)}>{f(action)}</button>)}
      </div>
      {!canManage && <p>{f("permission")}</p>}
      <h6>{f("history")}</h6>
      {!state.events.length && <p>{f("noHistory")}</p>}
      {[...state.events].sort((a, b) => b.version - a.version).map(event => <article key={event.requestId}>
        <strong>{f(event.action)}</strong> · {f(event.status)}
        <p>{f("actor")}: {event.actor} · {f("time")}: {formatDate(event.createdAt)}</p>
        <p style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{event.note}</p>
        {event.teamViewerId && /^\d{6,15}$/.test(event.teamViewerId) && <p>TeamViewer ID: {event.teamViewerId}</p>}
      </article>)}
    </>}
  </section>;
}
