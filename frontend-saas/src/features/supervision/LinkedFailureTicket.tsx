import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "../../lib/api";
import type { Credentials, SupportTicket, SupportTicketComment } from "../../lib/types";
import { useI18n } from "../../i18n";
import { formatDate } from "../../shared/lib";
import { StatusPill } from "../../shared/ui";
import { repairSession } from "./repair-session";
import { TicketInterventionsPanel } from "./TicketInterventionsPanel";
import { useRepairLabels } from "./repair-labels";

function validComment(value: SupportTicketComment | null | undefined, ticketId: string): value is SupportTicketComment {
  return !!value && typeof value.id === "string" && !!value.id && value.ticketId === ticketId
    && typeof value.author === "string" && typeof value.message === "string"
    && typeof value.createdAt === "string" && Number.isFinite(Date.parse(value.createdAt))
    && (value.requestId == null || typeof value.requestId === "string");
}
export function LinkedFailureTicket({ credentials, companyId, ticketId, canManage }: {
  credentials: Credentials; companyId: string; ticketId: string; canManage: boolean;
}) {
  const f = useRepairLabels(); const { t } = useI18n();
  const writes = repairSession(credentials).tickets;
  const scope = useRef({ credentials, companyId, ticketId });
  scope.current = { credentials, companyId, ticketId };
  const current = () => alive.current && scope.current.credentials === credentials && scope.current.companyId === companyId && scope.current.ticketId === ticketId;
  const pendingWrite = writes.get(ticketId);
  const [ticket, setTicket] = useState<SupportTicket | null>(null);
  const [comments, setComments] = useState<SupportTicketComment[]>([]);
  const [comment, setComment] = useState(() => { const pending = writes.get(ticketId); return pending?.kind === "comment" ? pending.value : ""; });
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false); const busyRef = useRef(false);
  const [revision, setRevision] = useState(0);
  const [mustRefresh, setMustRefresh] = useState(false);
  const [saved, setSaved] = useState(false);
  const [interventionBusy, setInterventionBusy] = useState(false);
  useEffect(() => { setInterventionBusy(false); }, [credentials, ticketId, companyId]);
  const alive = useRef(true); const generation = useRef(0);
  useEffect(() => { alive.current = true; return () => { alive.current = false; generation.current++; }; }, []);
  useEffect(() => {
    const requestId = ++generation.current;
    setLoading(true);
    void Promise.all([api.supportTickets(credentials, companyId), api.supportTicketComments(credentials, ticketId)])
      .then(([tickets, rows]) => {
        if (!current() || requestId !== generation.current) return;
        if (!Array.isArray(rows) || !rows.every(row => validComment(row, ticketId))) throw new Error("Invalid comments response");
        const loadedTicket = tickets.find(item => item.id === ticketId && item.companyId === companyId) ?? null;
        const pending = writes.get(ticketId);
        const confirmed = pending && rows.some(row => row.requestId === pending.requestId && row.author === pending.username && row.message === pending.value);
        if (confirmed) { writes.delete(ticketId); if (pending.kind === "comment") setComment(""); setSaved(true); }
        setMustRefresh(false); setLoadError(!loadedTicket); setTicket(loadedTicket); setComments(rows); setError(loadedTicket ? null : f("ticketMissing"));
      }).catch(() => { if (current() && requestId === generation.current) { setLoadError(true); setError(f("ticketUnavailable")); } })
      .finally(() => { if (current() && requestId === generation.current) setLoading(false); });
    return () => { generation.current++; };
  }, [credentials.accessToken, companyId, ticketId, revision]);
  async function write() {
    if (!ticket || !canManage || busyRef.current || interventionBusy || loading || loadError || mustRefresh || !comment.trim()) return;
    busyRef.current = true; setBusy(true); setSaved(false); setError(null); generation.current++;
    const write = pendingWrite ?? { kind: "comment" as const, value: comment.trim(),
      requestId: crypto.randomUUID(), username: credentials.username, uncertain: false };
    writes.set(ticketId, write);
    try {
      const added = await api.createSupportTicketComment(credentials, ticketId, write.value, write.requestId);
      if (!validComment(added, ticketId) || added.requestId !== write.requestId || added.author !== write.username || added.message !== write.value) throw new Error("Unconfirmed comment response");
      if (!current()) return;
      setComments(rows => rows.some(row => row.id === added.id) ? rows : [...rows, added]); setComment("");
      if (writes.get(ticketId) === write) writes.delete(ticketId);
      setSaved(true);
    } catch (error) {
      if (!current()) return;
      const rejected = error instanceof ApiError && [400, 403, 404, 409, 422].includes(error.status);
      if (rejected) { if (writes.get(ticketId) === write) writes.delete(ticketId); setMustRefresh(true); }
      else write.uncertain = true;
      setError(f(rejected ? "manualRejected" : "manualSaveFailed"));
    }
    finally { if (current()) { busyRef.current = false; setBusy(false); } }
  }
  return <section aria-label={f("ticket")} className="content-section">
    <h5>{f("ticket")}</h5>
    <p>{f("ticketScope")}</p>
    {loading && <p role="status">{f("loading")}</p>}
    {error && <p role="alert">{error}</p>}
    {pendingWrite && <p role="status">{f("ticketPending")}</p>}
    {saved && <p role="status">{f("manualSaved")}</p>}
    <button type="button" disabled={busy || interventionBusy || loading} onClick={() => setRevision(value => value + 1)}>{f("ticketReload")}</button>
    {ticket && <>
      <p><strong>{ticket.title}</strong> <StatusPill status={f(ticket.status === "RESUELTO" ? "ticketResolved" : ticket.status === "EN_CURSO" ? "ticketInProgress" : ticket.status === "ABIERTO" ? "ticketOpen" : "unknown")} tone={ticket.status === "RESUELTO" ? "ok" : "warning"} /></p>
      {ticket.description && <p style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{ticket.description}</p>}
      {comments.map(item => <article key={item.id}><strong>{item.author}</strong> · {formatDate(item.createdAt)}<p style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{item.message}</p></article>)}
      <TicketInterventionsPanel key={`${repairSession(credentials).id}:${ticketId}`} credentials={credentials} ticketId={ticketId} companyId={companyId} ticketStatus={ticket.status} canManage={canManage}
        blocked={loading || loadError || mustRefresh || busy || !!pendingWrite} refreshVersion={revision} onChanged={() => setRevision(value => value + 1)} onBusyChange={setInterventionBusy} />
      {canManage && <>
        <label>{f("manualComment")}<textarea className="control-input" value={comment} maxLength={4000} disabled={busy || interventionBusy || !!pendingWrite} onChange={event => setComment(event.target.value)} /></label>
        <button type="button" disabled={busy || interventionBusy || loading || loadError || mustRefresh || !comment.trim()} onClick={() => void write()}>{pendingWrite?.kind === "comment" ? f("retryComment") : t("addComment")}</button>
      </>}
    </>}
  </section>;
}
