import { useEffect, useState } from "react";
import { api } from "../../lib/api";
import type { Credentials, SupportTicketComment } from "../../lib/types";
import { workspaceApi, type FailureRepairCommand, type TicketInterventionState } from "../../lib/workspace-api";
import { formatDate } from "../../shared/lib";
import { StatusPill } from "../../shared/ui";
import { useInterventionLabels } from "./intervention-labels";
import { useRepairLabels } from "./repair-labels";

export function IncidentTimeline({ credentials, state, refreshVersion }: {
  credentials: Credentials; state: TicketInterventionState; refreshVersion: number;
}) {
  const f = useInterventionLabels(); const repair = useRepairLabels();
  const [comments, setComments] = useState<SupportTicketComment[]>([]);
  const [commands, setCommands] = useState<FailureRepairCommand[]>([]);
  const [failed, setFailed] = useState(false);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let active = true; setLoading(true); setFailed(false); setComments([]); setCommands([]);
    void Promise.allSettled([
      api.supportTicketComments(credentials, state.ticketId),
      state.failure ? workspaceApi.failureRepairs(credentials, state.failure.key) : Promise.resolve(null),
    ]).then(([commentResult, commandResult]) => {
      if (!active) return;
      const validComments = commentResult.status === "fulfilled" && Array.isArray(commentResult.value) && commentResult.value.every(row => row && row.ticketId === state.ticketId && typeof row.id === "string" && typeof row.author === "string" && typeof row.message === "string" && Number.isFinite(Date.parse(row.createdAt)));
      const validCommands = commandResult.status === "fulfilled" && (!state.failure || !!commandResult.value && Array.isArray(commandResult.value.commands) && commandResult.value.commands.every(row => row && typeof row.commandId === "string" && typeof row.status === "string" && typeof row.reason === "string" && typeof row.requestedBy === "string" && Number.isFinite(Date.parse(row.createdAt)) && Number.isFinite(Date.parse(row.updatedAt))));
      if (validComments && commentResult.status === "fulfilled") setComments(commentResult.value);
      if (validCommands && commandResult.status === "fulfilled") setCommands(commandResult.value?.commands ?? []);
      setFailed(!validComments || !validCommands); setLoading(false);
    });
    return () => { active = false; };
  }, [credentials, state.ticketId, state.companyId, state.failure?.key, state.version, refreshVersion]);
  const entries = [
    ...state.events.map(event => ({ id: `intervention:${event.requestId}`, time: event.createdAt, kind: "internal" as const, event })),
    ...comments.map(comment => ({ id: `comment:${comment.id}`, time: comment.createdAt, kind: "comment" as const, comment })),
    ...commands.map(command => ({ id: `command:${command.commandId}`, time: command.updatedAt, kind: "automatic" as const, command })),
  ].sort((a, b) => Date.parse(b.time) - Date.parse(a.time) || (a.kind === "internal" && b.kind === "internal" ? b.event.version - a.event.version : 0) || a.id.localeCompare(b.id));
  return <section className="incident-timeline" aria-label={f("timeline")}>
    <header><h6>{f("timeline")}</h6><span>{entries.length}</span></header>
    {loading && <p role="status">{f("loading")}</p>}
    {failed && <p role="alert">{f("timelineError")}</p>}
    {!loading && !failed && !entries.length && <p className="intervention-empty">{f("timelineEmpty")}</p>}
    <ol>{entries.map(entry => <li key={entry.id} className={`incident-timeline-entry incident-timeline-entry--${entry.kind}`}>
      <div className="incident-timeline-meta"><span>{f(entry.kind === "comment" ? "customerComment" : entry.kind)}</span><time dateTime={entry.time}>{formatDate(entry.time)}</time></div>
      {entry.kind === "internal" && <>
        <div className="incident-timeline-title"><strong>{f(entry.event.action)}</strong><StatusPill status={f(entry.event.status)} tone={entry.event.status === "RESOLVED" ? "ok" : "muted"} /></div>
        <p>{entry.event.note}</p><small>{f("actor")}: {entry.event.actor}</small>
        {entry.event.assignee && <p>{f("assignee")}: {entry.event.assignee}{entry.event.visitAt && <> · {formatDate(entry.event.visitAt)}</>}</p>}
        {entry.event.nextReviewAt && <p>{f("reviewDate")}: {formatDate(entry.event.nextReviewAt)}</p>}
        {entry.event.action === "REQUEST_VERIFICATION" && entry.event.resolutionSummary && <p>{f("resolutionSummary")}: {entry.event.resolutionSummary}</p>}
        {entry.event.action === "RESOLVE" && entry.event.verificationNotes && <p>{f("verificationNotes")}: {entry.event.verificationNotes} · {f("confirmedBy")}: {entry.event.confirmedBy}</p>}
        {entry.event.action === "START_REMOTE" && entry.event.teamViewerId && <p>TeamViewer ID: {entry.event.teamViewerId}</p>}
      </>}
      {entry.kind === "comment" && <><strong>{entry.comment.author}</strong><p>{entry.comment.message}</p></>}
      {entry.kind === "automatic" && <>
        <div className="incident-timeline-title"><strong>{repair("retry")}</strong><StatusPill status={repair(entry.command.status)} tone={entry.command.status === "SUCCEEDED" ? "ok" : entry.command.status === "FAILED" || entry.command.status === "EXPIRED" ? "warning" : "muted"} /></div>
        <p>{entry.command.reason}</p>{entry.command.resultCode && <p>{repair(entry.command.resultCode)}</p>}
        <small>{repair("by")}: {entry.command.requestedBy} · {repair("created")}: {formatDate(entry.command.createdAt)} · {repair("expires")}: {formatDate(entry.command.expiresAt)}</small>
      </>}
    </li>)}</ol>
  </section>;
}
