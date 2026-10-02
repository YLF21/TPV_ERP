import { TicketCommentComposer, validTicketComment } from "./TicketCommentComposer";
import { repairSession } from "../supervision/repair-session";
import { useSupportLabels, type SupportTarget } from "./support-labels";
import "./support-workflow.css";
import { TicketInterventionsPanel } from "../supervision/TicketInterventionsPanel";
import { useInterventionLabels } from "../supervision/intervention-labels";
import { useRefreshVersion } from "../../app/RefreshContext";
import { FormEvent, type ReactNode, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../../lib/api";
import { isCurrentSelection } from "../../lib/frontend-runtime.mjs";
import type { AdminNotification, Credentials, LicenseSummary, SaasStatus, SupportTicket, SupportTicketComment, TechnicalStatus } from "../../lib/types";
import { Notice } from "../../shared/types";
import { useI18n } from "../../i18n/index";
import { uniqueCompanies, isMissingPhase3Endpoint, isRecoverableBackendDataError, errorMessage, formatDate, ticketStatusLabel, ticketPriorityLabel } from "../../shared/lib";
import { RetryError, Metric, SectionHeader, EmptyState, Input, Select, StatusPill } from "../../shared/ui";

export function SupportView({
  credentials,
  licenses,
  permissions,
  onNotice, target, onReturnToFailure, onClearTarget
}: {
  credentials: Credentials;
  licenses: LicenseSummary[];
  permissions: Set<string>;
  onNotice: (notice: Notice) => void;
  target?: SupportTarget | null;
  onReturnToFailure?: (key: string) => void;
  onClearTarget?: () => void;
}) {
  const { t } = useI18n();
  const refreshVersion = useRefreshVersion();
  const q = useSupportLabels();
  const session = repairSession(credentials);
  const [workflowRevision, setWorkflowRevision] = useState(0);
  const [commentsBlocked, setCommentsBlocked] = useState<Record<string, boolean>>({});
  const [queueFilter, setQueueFilter] = useState("");
  const [expandedTicket, setExpandedTicket] = useState<string | null>(target?.ticketId ?? null);
  const [ticketsLoading, setTicketsLoading] = useState(false);
  const resolvedTarget = useRef<SupportTarget | null>(null);
  const overviewRequestId = useRef(0);
  const fallbackCompanies = useMemo(() => uniqueCompanies(licenses), [licenses]);
  const [loadedCompanies, setLoadedCompanies] = useState<Array<{ companyId: string; companyName: string }> | null>(null);
  const companies = loadedCompanies ?? fallbackCompanies;
  useEffect(() => {
    let active = true;
    void api.companies(credentials).then(value => { if (active) setLoadedCompanies(value); })
      .catch(error => { if (active) onNotice({ type: "error", text: errorMessage(error) }); });
    return () => { active = false; };
  }, [credentials, refreshVersion]);
  const [companyId, setCompanyId] = useState(target?.companyId ?? "");
  const [notifications, setNotifications] = useState<AdminNotification[]>([]);
  const confirmedReadNotifications = useRef(new Set<string>());
  const pendingReadNotifications = useRef(new Set<string>());
  const [readingNotifications, setReadingNotifications] = useState<Set<string>>(new Set());
  useEffect(() => {
    confirmedReadNotifications.current.clear(); pendingReadNotifications.current.clear();
    setReadingNotifications(new Set());
  }, [credentials]);
  const [technicalStatus, setTechnicalStatus] = useState<TechnicalStatus | null>(null);
  const [saasStatus, setSaasStatus] = useState<SaasStatus | null>(null);
  const [tickets, setTickets] = useState<SupportTicket[]>([]);
  const [commentsByTicket, setCommentsByTicket] = useState<Record<string, SupportTicketComment[]>>({});
  const [statusFilter, setStatusFilter] = useState("");
  const [priorityFilter, setPriorityFilter] = useState("");
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [priority, setPriority] = useState("NORMAL");
  const [busy, setBusy] = useState<string | null>(null);
  const ticketRequestId = useRef(0);
  const selectedSupportCompanyRef = useRef(companyId);
  selectedSupportCompanyRef.current = companyId;
  const mounted = useRef(true);
  const currentScope = useRef({ credentials, companyId }); currentScope.current = { credentials, companyId };
  const current = () => mounted.current && currentScope.current.credentials === credentials && currentScope.current.companyId === companyId;
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; ticketRequestId.current++; overviewRequestId.current++; }; }, []);
  useEffect(() => { setBusy(null); }, [credentials, companyId]);
  const canManage = permissions.has("MANAGE_SUPPORT_TICKETS");
  const filteredTickets = tickets.filter((ticket) =>
    (!statusFilter || ticket.status === statusFilter) &&
    (!priorityFilter || ticket.priority === priorityFilter) &&
    (!queueFilter || queueFilter === "mine" && ticket.assigneeUserId != null && ticket.assignee === credentials.username && ticket.status !== "RESUELTO"
      || queueFilter === "unassigned" && !ticket.assigneeUserId && ticket.status !== "RESUELTO"
      || queueFilter === "visits" && !!ticket.visitAt && ticket.status !== "RESUELTO" && ["ONSITE_REQUIRED", "ONSITE_IN_PROGRESS"].includes(ticket.interventionStatus ?? "")
      || queueFilter === "due" && !!ticket.nextReviewAt && Date.parse(ticket.nextReviewAt) <= Date.now() && ["WAITING_CUSTOMER", "WAITING_MATERIAL"].includes(ticket.interventionStatus ?? ""))
  );

  useEffect(() => {
    if (target?.companyId === companyId) return;
    if (!companies.some(c => c.companyId === companyId)) {
      setCompanyId(companies[0]?.companyId ?? "");
    }
  }, [companies, companyId, target]);

  useEffect(() => {
    if (!target) return;
    resolvedTarget.current = null;
    setCompanyId(target.companyId); setStatusFilter(""); setPriorityFilter(""); setQueueFilter(""); setExpandedTicket(target.ticketId ?? null);
  }, [target]);
  useEffect(() => {
    if (!target || resolvedTarget.current === target || target.companyId !== companyId || !target.failureKey || target.ticketId) return;
    const linked = tickets.find(ticket => ticket.failureKey === target.failureKey);
    if (linked) { setExpandedTicket(linked.id); resolvedTarget.current = target; }
  }, [target, companyId, tickets]);

  useEffect(() => {
    void loadOverview();
  }, [credentials.accessToken, refreshVersion]);

  useEffect(() => {
    ticketRequestId.current += 1;
    setTickets([]);
    setTicketsLoading(!!companyId);
    setCommentsByTicket({});
    if (companyId) void loadTickets(companyId);
  }, [companyId, credentials.accessToken, refreshVersion]);

  async function loadOverview() {
    const id = ++overviewRequestId.current;
    const [nextNotifications, nextTechnicalStatus, nextSaasStatus] = await Promise.allSettled([
      api.notifications(credentials),
      api.technicalStatus(credentials),
      api.saasStatus(credentials)
    ]);

    if (id !== overviewRequestId.current) return;
    if (nextNotifications.status === "fulfilled") {
      setNotifications(nextNotifications.value.map(notification => ({ ...notification, read: notification.read || confirmedReadNotifications.current.has(notification.id) })));
    } else if (isMissingPhase3Endpoint(nextNotifications.reason) || isRecoverableBackendDataError(nextNotifications.reason)) {
      setNotifications([]);
    } else {
      onNotice({ type: "error", text: errorMessage(nextNotifications.reason) });
    }

    if (nextTechnicalStatus.status === "fulfilled") {
      setTechnicalStatus(nextTechnicalStatus.value);
    } else {
      setTechnicalStatus(null);
      onNotice({ type: "error", text: errorMessage(nextTechnicalStatus.reason) });
    }

    if (nextSaasStatus.status === "fulfilled") {
      setSaasStatus(nextSaasStatus.value);
    } else if (isMissingPhase3Endpoint(nextSaasStatus.reason) || isRecoverableBackendDataError(nextSaasStatus.reason)) {
      setSaasStatus(null);
    } else {
      onNotice({ type: "error", text: errorMessage(nextSaasStatus.reason) });
    }
  }

  async function loadTickets(nextCompanyId: string) {
    const requestId = ++ticketRequestId.current;
    try {
      const nextTickets = await api.supportTickets(credentials, nextCompanyId);
      if (!current() || requestId !== ticketRequestId.current || !isCurrentSelection(nextCompanyId, selectedSupportCompanyRef.current)) return;
      setTickets(nextTickets.filter(ticket => ticket.companyId === nextCompanyId));
      await loadTicketComments(nextTickets.filter(ticket => ticket.companyId === nextCompanyId), requestId);
    } catch (error) {
      if (!current() || requestId !== ticketRequestId.current || !isCurrentSelection(nextCompanyId, selectedSupportCompanyRef.current)) return;
      if (isMissingPhase3Endpoint(error) || isRecoverableBackendDataError(error)) {
        setTickets([]);
        setCommentsByTicket({});
        onNotice(null);
        return;
      }
      onNotice({ type: "error", text: errorMessage(error) });
    } finally { if (current() && requestId === ticketRequestId.current) setTicketsLoading(false); }
  }

  async function loadTicketComments(nextTickets: SupportTicket[], requestId = ticketRequestId.current) {
    const entries = await Promise.all(
      nextTickets.map(async (ticket) => {
        try {
          const rows = await api.supportTicketComments(credentials, ticket.id);
          if (!Array.isArray(rows) || !rows.every(row => validTicketComment(row, ticket.id))) throw new Error("Invalid comments response");
          return [ticket.id, rows] as const;
        } catch (error) {
          if (isMissingPhase3Endpoint(error) || isRecoverableBackendDataError(error)) return [ticket.id, []] as const;
          throw error;
        }
      })
    );
    if (current() && requestId === ticketRequestId.current) setCommentsByTicket(Object.fromEntries(entries));
  }

  async function createTicket(event: FormEvent) {
    event.preventDefault();
    if (!companyId || busy || !canManage) return;
    setBusy("create");
    try {
      await api.createSupportTicket(credentials, companyId, { title, description, priority });
      if (!current()) return;
      setTitle("");
      setDescription("");
      setPriority("NORMAL");
      await Promise.all([loadTickets(companyId), loadOverview()]);
      if (current()) onNotice({ type: "success", text: t("ticketCreated") });
    } catch (error) {
      if (current()) onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      if (current()) setBusy(null);
    }
  }

  async function markNotificationRead(notificationId: string) {
    if (pendingReadNotifications.current.has(notificationId) || confirmedReadNotifications.current.has(notificationId)) return;
    pendingReadNotifications.current.add(notificationId);
    setReadingNotifications(new Set(pendingReadNotifications.current));
    const activeSession = () => mounted.current && currentScope.current.credentials === credentials;
    try {
      await api.markNotificationRead(credentials, notificationId);
      if (!activeSession()) return;
      confirmedReadNotifications.current.add(notificationId);
      setNotifications(rows => rows.map(notification => notification.id === notificationId ? { ...notification, read: true } : notification));
      onNotice({ type: "success", text: t("notificationRead") });
    } catch (error) {
      if (activeSession()) onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      if (activeSession()) {
        pendingReadNotifications.current.delete(notificationId);
        setReadingNotifications(new Set(pendingReadNotifications.current));
      }
    }
  }

  return (
    <div className="view-grid">
      {!saasStatus && !technicalStatus && <RetryError message={t("technicalDegraded")} onRetry={() => void loadOverview()} />}
      <section className="metric-grid support-metrics">
        <Metric label={t("backendStatus")} value={saasStatus || technicalStatus ? t("technicalOk") : t("technicalDegraded")} detail={saasStatus ? `${saasStatus.apiVersion} · ${saasStatus.expectedMigration}` : technicalStatus ? `${t("generatedAt")} ${formatDate(technicalStatus.generatedAt)}` : t("loadingSaas")} />
        <Metric label={t("company")} value={technicalStatus?.companies ?? "-"} />
        <Metric label={t("licenses")} value={technicalStatus?.licenses ?? "-"} />
        <Metric label={t("eventsToday")} value={technicalStatus?.eventsToday ?? "-"} />
        <Metric label={t("openTickets")} value={technicalStatus?.openTickets ?? "-"} tone={(technicalStatus?.openTickets ?? 0) > 0 ? "warning" : undefined} />
        <Metric label={t("lastSync")} value={technicalStatus?.lastSyncAt ? formatDate(technicalStatus.lastSyncAt) : t("pending")} />
      </section>

      <section className="content-section two-column support-layout">
        <div>
          <SectionHeader title={t("notifications")} subtitle={t("notificationsSubtitle")} />
          <NotificationList notifications={notifications} pendingIds={readingNotifications} onMarkRead={(notificationId) => void markNotificationRead(notificationId)} />
        </div>
        <div>
          <SectionHeader title={t("technicalPanel")} subtitle={t("technicalPanelSubtitle")} />
          <div className="technical-card">
            <Metric label={t("installations")} value={technicalStatus?.installations ?? "-"} />
            <Metric label={t("staleInstallations")} value={technicalStatus?.staleInstallations ?? "-"} tone={(technicalStatus?.staleInstallations ?? 0) > 0 ? "warning" : undefined} />
          </div>
        </div>
      </section>

      <section className="content-section support-tickets-panel">
        <SectionHeader title={t("supportTickets")} subtitle={t("supportTicketsSubtitle")} />
        {companies.length === 0 ? (
          <EmptyState text={t("selectCompany")} />
        ) : (
          <>
            <label className="company-ticket-selector">
              {t("company")}
              <select className="control-input" value={companyId} disabled={!!busy} onChange={(event) => { onClearTarget?.(); setExpandedTicket(null); setCompanyId(event.target.value); }}>
                {companyId && !companies.some(company => company.companyId === companyId) && <option value={companyId}>{companyId}</option>}
                {companies.map((company) => (
                  <option key={company.companyId} value={company.companyId}>
                    {company.companyName}
                  </option>
                ))}
              </select>
            </label>
            {canManage && (
              <form className="support-ticket-form" onSubmit={createTicket}>
                <div className="support-ticket-form-top">
                  <Input label={t("title")} value={title} onChange={setTitle} required />
                  <Select label={t("priority")} value={priority} options={["NORMAL", "ALTA", "URGENTE"]} onChange={setPriority} />
                  <div className="form-actions">
                    <button className="primary-button" type="submit" disabled={!!busy}>
                      {busy === "create" ? t("saving") : t("createTicket")}
                    </button>
                  </div>
                </div>
                <label className="support-ticket-description">
                  {t("description")}
                  <textarea
                    className="control-input text-area"
                    value={description}
                    onChange={(event) => setDescription(event.target.value)}
                  />
                </label>
              </form>
            )}
            {target && target.companyId === companyId && <div className="support-context-banner"><span>{q("context")}</span>{target.failureKey && onReturnToFailure && <button type="button" className="secondary-button" onClick={() => onReturnToFailure(target.failureKey!)}>{q("back")}</button>}</div>}
            {!ticketsLoading && target?.ticketId && target.companyId === companyId && !tickets.some(ticket => ticket.id === target.ticketId) && <p role="alert">{q("missing")}</p>}
            <div className="support-ticket-filters">
              <label>{q("queue")}<select className="control-input" value={queueFilter} onChange={event => setQueueFilter(event.target.value)}><option value="">{q("all")}</option>{(["mine", "unassigned", "visits", "due"] as const).map(value => <option key={value} value={value}>{q(value)}</option>)}</select></label>
              <Select label={t("status")} value={statusFilter} options={["", "ABIERTO", "EN_CURSO", "RESUELTO"]} onChange={setStatusFilter} emptyLabel={t("allStatuses")} />
              <Select label={t("priority")} value={priorityFilter} options={["", "NORMAL", "ALTA", "URGENTE"]} onChange={setPriorityFilter} emptyLabel={t("allPriorities")} />
            </div>
            <p className="support-queue-scope">{q("queueScope")} · {filteredTickets.length} / {tickets.length}</p>
            <TicketList
              tickets={filteredTickets}
              commentsByTicket={commentsByTicket}
              expanded={expandedTicket} onToggle={id => setExpandedTicket(expandedTicket === id ? null : id)}
              onReturnToFailure={onReturnToFailure}
              canManage={canManage}
              busy={busy}
              renderWorkflow={ticket => <TicketInterventionsPanel key={session.id + ":" + ticket.id}
                credentials={credentials} companyId={ticket.companyId} ticketId={ticket.id} ticketStatus={ticket.status}
                canManage={canManage} blocked={(!!busy && busy !== "workflow:" + ticket.id) || session.tickets.has(ticket.id) || (canManage && commentsBlocked[ticket.id] !== false)} refreshVersion={refreshVersion + workflowRevision}
                onBusyChange={value => { if (current()) setBusy(value ? "workflow:" + ticket.id : null); }}
                onChanged={() => { if (current()) void Promise.all([loadTickets(ticket.companyId), loadOverview()]); }} />}
              renderComposer={ticket => <TicketCommentComposer key={session.id + ":comment:" + ticket.id} credentials={credentials} ticketId={ticket.id}
                disabled={!!busy && busy !== "comment:" + ticket.id}
                onBlockedChange={value => { if (current()) setCommentsBlocked(previous => previous[ticket.id] === value ? previous : {...previous, [ticket.id]: value}); }}
                onBusyChange={value => { if (current()) setBusy(value ? "comment:" + ticket.id : null); }}
                onChanged={() => { if (current()) { setWorkflowRevision(value => value + 1); void loadTicketComments(tickets).catch(error => { if (current()) onNotice({type: "error", text: errorMessage(error)}); }); } }} />}
            />
          </>
        )}
      </section>
    </div>
  );
}

export function NotificationList({ notifications, onMarkRead, pendingIds }: { notifications: AdminNotification[]; onMarkRead: (notificationId: string) => void; pendingIds?: ReadonlySet<string> }) {
  const { t } = useI18n();
  const unread = notifications.filter(notification => !notification.read);
  if (unread.length === 0) return <EmptyState text={t("noNotifications")} />;
  return (
    <div className="notification-list">
      {unread.map((notification) => (
        <article className={`notification-card ${notification.severity.toLowerCase()}`} key={notification.id}>
          <div>
            <strong>{notification.title}</strong>
            <span>{notification.companyName}</span>
          </div>
          <p>{notification.detail}</p>
          <button className="small-button" type="button" disabled={pendingIds?.has(notification.id)} onClick={() => onMarkRead(notification.id)}>
            {t("markRead")}
          </button>
        </article>
      ))}
    </div>
  );
}

export function TicketList({tickets,commentsByTicket,canManage,busy,renderWorkflow,renderComposer,expanded,onToggle,onReturnToFailure}: {
 tickets:SupportTicket[];commentsByTicket:Record<string,SupportTicketComment[]>;canManage:boolean;busy:string|null;
 renderWorkflow:(ticket:SupportTicket)=>ReactNode;renderComposer:(ticket:SupportTicket)=>ReactNode;
 expanded:string|null;onToggle:(id:string)=>void;onReturnToFailure?:(key:string)=>void;
}) {
 const {t}=useI18n();const f=useInterventionLabels();const q=useSupportLabels();
 useEffect(()=>{if(expanded)document.getElementById("support-ticket-"+expanded)?.scrollIntoView({block:"start"});},[expanded,tickets.length]);
 if(!tickets.length)return <EmptyState text={t("noTickets")}/>;
 return <div className="ticket-list">{tickets.map(ticket=><article className="ticket-card" id={"support-ticket-"+ticket.id} key={ticket.id}>
  <div className="ticket-main"><div><strong>{ticket.title}</strong><span>{ticket.companyName} - {ticket.createdBy} - {formatDate(ticket.createdAt)}</span></div>
  <div className="ticket-badges"><StatusPill status={ticketStatusLabel(ticket.status,t)} tone={ticket.status==="RESUELTO"?"ok":"warning"}/><StatusPill status={ticketPriorityLabel(ticket.priority,t)} tone={ticket.priority==="URGENTE"?"warning":"muted"}/></div></div>
  <div className="support-ticket-context"><span>{f(ticket.interventionStatus ?? (ticket.status==="RESUELTO"?"RESOLVED":"REMOTE_PENDING"))}</span><span>{q("owner")}: {ticket.assignee ?? q("unassigned")}</span>{ticket.visitAt&&<span>{q("visit")}: {formatDate(ticket.visitAt)}</span>}{ticket.nextReviewAt&&<span>{q("review")}: {formatDate(ticket.nextReviewAt)}</span>}</div>
  {ticket.status==="RESUELTO"&&ticket.failureKey&&ticket.failureStatus!=="RESOLVED"&&<p className="support-technical-pending">{q("closedPending")}{ticket.failureReceivedAt&&<> · {q("lastSignal")}: {formatDate(ticket.failureReceivedAt)}</>}</p>}
  {ticket.description&&<p>{ticket.description}</p>}
  {expanded!==ticket.id&&<details className="ticket-comments"><summary>{q("showComments")} ({(commentsByTicket[ticket.id]??[]).length})</summary>{(commentsByTicket[ticket.id]??[]).map(comment=><div className="ticket-comment" key={comment.id}><strong>{comment.author}</strong><span>{formatDate(comment.createdAt)}</span><p>{comment.message}</p></div>)}</details>}
  <div className="ticket-actions"><button className="primary-button" type="button" disabled={!!busy} aria-expanded={expanded===ticket.id} onClick={()=>onToggle(ticket.id)}>{f(expanded===ticket.id?"closePanel":"manage")}</button>{ticket.failureKey&&onReturnToFailure&&<button className="secondary-button" type="button" disabled={!!busy} onClick={()=>onReturnToFailure(ticket.failureKey!)}>{q("back")}</button>}</div>
  {expanded===ticket.id&&<div className="support-workflow"><p>{f("scope")}</p>{renderWorkflow(ticket)}</div>}
  {canManage&&renderComposer(ticket)}
 </article>)}</div>;
}
