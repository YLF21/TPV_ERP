import { useRefreshVersion } from "../../app/RefreshContext";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
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
  onNotice
}: {
  credentials: Credentials;
  licenses: LicenseSummary[];
  permissions: Set<string>;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const refreshVersion = useRefreshVersion();
  const overviewRequestId = useRef(0);
  const companies = useMemo(() => uniqueCompanies(licenses), [licenses]);
  const [companyId, setCompanyId] = useState("");
  const [notifications, setNotifications] = useState<AdminNotification[]>([]);
  const [technicalStatus, setTechnicalStatus] = useState<TechnicalStatus | null>(null);
  const [saasStatus, setSaasStatus] = useState<SaasStatus | null>(null);
  const [tickets, setTickets] = useState<SupportTicket[]>([]);
  const [commentsByTicket, setCommentsByTicket] = useState<Record<string, SupportTicketComment[]>>({});
  const [commentDrafts, setCommentDrafts] = useState<Record<string, string>>({});
  const [statusFilter, setStatusFilter] = useState("");
  const [priorityFilter, setPriorityFilter] = useState("");
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [priority, setPriority] = useState("NORMAL");
  const [busy, setBusy] = useState<string | null>(null);
  const ticketRequestId = useRef(0);
  const selectedSupportCompanyRef = useRef(companyId);
  selectedSupportCompanyRef.current = companyId;
  const canManage = permissions.has("MANAGE_SUPPORT_TICKETS");
  const filteredTickets = tickets.filter((ticket) =>
    (!statusFilter || ticket.status === statusFilter) &&
    (!priorityFilter || ticket.priority === priorityFilter)
  );

  useEffect(() => {
    if (!companies.some(c => c.companyId === companyId)) {
      setCompanyId(companies[0]?.companyId ?? "");
    }
  }, [companies, companyId]);

  useEffect(() => {
    void loadOverview();
  }, [credentials.accessToken, refreshVersion]);

  useEffect(() => {
    ticketRequestId.current += 1;
    setTickets([]);
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
      setNotifications(nextNotifications.value);
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
      if (requestId !== ticketRequestId.current || !isCurrentSelection(nextCompanyId, selectedSupportCompanyRef.current)) return;
      setTickets(nextTickets);
      await loadTicketComments(nextTickets, requestId);
    } catch (error) {
      if (requestId !== ticketRequestId.current || !isCurrentSelection(nextCompanyId, selectedSupportCompanyRef.current)) return;
      if (isMissingPhase3Endpoint(error) || isRecoverableBackendDataError(error)) {
        setTickets([]);
        setCommentsByTicket({});
        onNotice(null);
        return;
      }
      onNotice({ type: "error", text: errorMessage(error) });
    }
  }

  async function loadTicketComments(nextTickets: SupportTicket[], requestId = ticketRequestId.current) {
    const entries = await Promise.all(
      nextTickets.map(async (ticket) => {
        try {
          return [ticket.id, await api.supportTicketComments(credentials, ticket.id)] as const;
        } catch (error) {
          if (isMissingPhase3Endpoint(error) || isRecoverableBackendDataError(error)) return [ticket.id, []] as const;
          throw error;
        }
      })
    );
    if (requestId === ticketRequestId.current) setCommentsByTicket(Object.fromEntries(entries));
  }

  async function createTicket(event: FormEvent) {
    event.preventDefault();
    if (!companyId) return;
    setBusy("create");
    try {
      await api.createSupportTicket(credentials, companyId, { title, description, priority });
      setTitle("");
      setDescription("");
      setPriority("NORMAL");
      await Promise.all([loadTickets(companyId), loadOverview()]);
      onNotice({ type: "success", text: t("ticketCreated") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function updateTicket(ticket: SupportTicket, status: string) {
    setBusy(ticket.id);
    try {
      await api.updateSupportTicket(credentials, ticket.id, { status, priority: ticket.priority });
      await Promise.all([loadTickets(ticket.companyId), loadOverview()]);
      onNotice({ type: "success", text: t("ticketUpdated") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function addComment(ticket: SupportTicket) {
    const message = (commentDrafts[ticket.id] ?? "").trim();
    if (!message) return;
    setBusy(`comment:${ticket.id}`);
    try {
      await api.createSupportTicketComment(credentials, ticket.id, message);
      setCommentDrafts((current) => ({ ...current, [ticket.id]: "" }));
      await loadTicketComments(tickets);
      onNotice({ type: "success", text: t("commentAdded") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function markNotificationRead(notificationId: string) {
    const removed = notifications.find((notification) => notification.id === notificationId);
    try {
      setNotifications((current) => current.filter((notification) => notification.id !== notificationId));
      await api.markNotificationRead(credentials, notificationId);
      onNotice({ type: "success", text: t("notificationRead") });
    } catch (error) {
      if (removed) setNotifications((current) => current.some((item) => item.id === removed.id) ? current : [removed, ...current]);
      if (!isMissingPhase3Endpoint(error) && !isRecoverableBackendDataError(error)) {
        onNotice({ type: "error", text: errorMessage(error) });
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
          <NotificationList notifications={notifications} onMarkRead={(notificationId) => void markNotificationRead(notificationId)} />
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
              <select className="control-input" value={companyId} onChange={(event) => setCompanyId(event.target.value)}>
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
                    <button className="primary-button" type="submit" disabled={busy === "create"}>
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
            <div className="support-ticket-filters">
              <Select label={t("status")} value={statusFilter} options={["", "ABIERTO", "EN_CURSO", "RESUELTO"]} onChange={setStatusFilter} emptyLabel={t("allStatuses")} />
              <Select label={t("priority")} value={priorityFilter} options={["", "NORMAL", "ALTA", "URGENTE"]} onChange={setPriorityFilter} emptyLabel={t("allPriorities")} />
            </div>
            <TicketList
              tickets={filteredTickets}
              commentsByTicket={commentsByTicket}
              commentDrafts={commentDrafts}
              canManage={canManage}
              busy={busy}
              onUpdate={updateTicket}
              onCommentDraftChange={(ticketId, message) => setCommentDrafts((current) => ({ ...current, [ticketId]: message }))}
              onAddComment={(ticket) => void addComment(ticket)}
            />
          </>
        )}
      </section>
    </div>
  );
}

export function NotificationList({ notifications, onMarkRead }: { notifications: AdminNotification[]; onMarkRead: (notificationId: string) => void }) {
  const { t } = useI18n();
  if (notifications.length === 0) return <EmptyState text={t("noNotifications")} />;
  return (
    <div className="notification-list">
      {notifications.map((notification) => (
        <article className={`notification-card ${notification.severity.toLowerCase()}`} key={notification.id}>
          <div>
            <strong>{notification.title}</strong>
            <span>{notification.companyName}</span>
          </div>
          <p>{notification.detail}</p>
          <button className="small-button" type="button" onClick={() => onMarkRead(notification.id)}>
            {t("markRead")}
          </button>
        </article>
      ))}
    </div>
  );
}

export function TicketList({
  tickets,
  commentsByTicket,
  commentDrafts,
  canManage,
  busy,
  onUpdate,
  onCommentDraftChange,
  onAddComment
}: {
  tickets: SupportTicket[];
  commentsByTicket: Record<string, SupportTicketComment[]>;
  commentDrafts: Record<string, string>;
  canManage: boolean;
  busy: string | null;
  onUpdate: (ticket: SupportTicket, status: string) => void;
  onCommentDraftChange: (ticketId: string, message: string) => void;
  onAddComment: (ticket: SupportTicket) => void;
}) {
  const { t } = useI18n();
  if (tickets.length === 0) return <EmptyState text={t("noTickets")} />;
  return (
    <div className="ticket-list">
      {tickets.map((ticket) => (
        <article className="ticket-card" key={ticket.id}>
          <div className="ticket-main">
            <div>
              <strong>{ticket.title}</strong>
              <span>{ticket.companyName} - {ticket.createdBy} - {formatDate(ticket.createdAt)}</span>
            </div>
            <div className="ticket-badges">
              <StatusPill status={ticketStatusLabel(ticket.status, t)} tone={ticket.status === "RESUELTO" ? "ok" : "warning"} />
              <StatusPill status={ticketPriorityLabel(ticket.priority, t)} tone={ticket.priority === "URGENTE" ? "warning" : "muted"} />
            </div>
          </div>
          {ticket.description && <p>{ticket.description}</p>}
          <div className="ticket-comments">
            {(commentsByTicket[ticket.id] ?? []).length === 0 ? (
              <span>{t("noComments")}</span>
            ) : (
              (commentsByTicket[ticket.id] ?? []).map((comment) => (
                <div className="ticket-comment" key={comment.id}>
                  <strong>{comment.author}</strong>
                  <span>{formatDate(comment.createdAt)}</span>
                  <p>{comment.message}</p>
                </div>
              ))
            )}
          </div>
          {canManage && ticket.status !== "RESUELTO" && (
            <div className="ticket-actions">
              {ticket.status !== "EN_CURSO" && (
                <button className="small-button" type="button" disabled={busy === ticket.id} onClick={() => onUpdate(ticket, "EN_CURSO")}>
                  {t("inProgress")}
                </button>
              )}
              <button className="small-button" type="button" disabled={busy === ticket.id} onClick={() => onUpdate(ticket, "RESUELTO")}>
                {t("resolve")}
              </button>
            </div>
          )}
          {canManage && (
            <div className="ticket-comment-form">
              <input
                className="control-input"
                value={commentDrafts[ticket.id] ?? ""}
                onChange={(event) => onCommentDraftChange(ticket.id, event.target.value)}
                placeholder={t("comment")}
              />
              <button className="small-button" type="button" disabled={busy === `comment:${ticket.id}`} onClick={() => onAddComment(ticket)}>
                {t("addComment")}
              </button>
            </div>
          )}
        </article>
      ))}
    </div>
  );
}
