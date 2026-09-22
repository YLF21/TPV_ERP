import { FormEvent, useEffect, useRef, useState } from "react";
import { tenantApi } from "../../lib/tenant-api";
import type { Credentials, SupportTicket, SupportTicketComment } from "../../lib/types";
import type { Notice } from "../../shared/types";
import { errorMessage, formatDate } from "../../shared/lib";
import { EmptyState, RetryError } from "../../shared/ui";
import { useTenantLabels } from "./labels";
import { TenantTicketList } from "./TenantPortal";

export function TenantSupportConversation({ credentials, tickets, onNotice, revision }: {
  credentials: Credentials; tickets: SupportTicket[]; onNotice: (notice: Notice) => void; revision: number;
}) {
  const l = useTenantLabels();
  const [selectedId, setSelectedId] = useState("");
  const ticket = tickets.find(item => item.id === selectedId) ?? tickets[0];
  const ticketId = ticket?.id ?? "";
  const [comments, setComments] = useState<{ context: string; items: SupportTicketComment[] } | null>(null);
  const [draft, setDraft] = useState("");
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const context = JSON.stringify([credentials.accessToken, credentials.companyId, ticketId]);
  const currentContext = useRef(context); currentContext.current = context;
  const active = useRef(false);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  useEffect(() => {
    let cancelled = false; setError(null); setDraft(""); setBusy(false);
    if (!ticketId) { setComments(null); setLoading(false); return; }
    setLoading(true);
    tenantApi.comments(credentials, ticketId).then(items => { if (!cancelled) setComments({ context, items }); })
      .catch(failure => { if (!cancelled) { setComments(null); setError(errorMessage(failure)); } })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [context, revision, retry]);

  async function send(event: FormEvent) {
    event.preventDefault(); if (!draft.trim() || !ticketId) return;
    setBusy(true); setError(null);
    try {
      const comment = await tenantApi.comment(credentials, ticketId, draft.trim());
      if (active.current && currentContext.current === context) {
        setComments(previous => ({ context, items: [...(previous?.context === context ? previous.items.filter(item => item.id !== comment.id) : []), comment] }));
        setDraft(""); onNotice({ type: "success", text: l("sent") });
      }
    } catch (failure) { if (active.current && currentContext.current === context) setError(errorMessage(failure)); }
    finally { if (active.current && currentContext.current === context) setBusy(false); }
  }

  return <div className="tenant-conversation">
    <h3>{l("conversation")}</h3>
    {ticket ? <>
      <label>{l("ticket")}<select aria-label={l("ticket")} className="control-input" value={ticketId} disabled={busy} onChange={event => setSelectedId(event.target.value)}>
        {tickets.map(item => <option key={item.id} value={item.id}>{item.title}</option>)}
      </select></label>
      <TenantTicketList tickets={[ticket]} />
      {loading && <p role="status">{l("loading")}</p>}
      {error && <RetryError message={error} onRetry={() => setRetry(value => value + 1)} />}
      <div className="tenant-comments" aria-live="polite">
        {comments?.context === context && comments.items.map(comment => <article className="ticket-card" key={comment.id}>
          <strong>{comment.author}</strong><small>{formatDate(comment.createdAt)}</small><p>{comment.message}</p>
        </article>)}
      </div>
      <form onSubmit={send}><label>{l("message")}<textarea className="control-input" rows={3} value={draft} onChange={event => setDraft(event.target.value)} required maxLength={4000} disabled={busy} /></label>
        <button type="submit" className="primary-button" disabled={busy || loading || !draft.trim()}>{l("send")}</button>
      </form>
    </> : <EmptyState text={l("empty")} />}
  </div>;
}
