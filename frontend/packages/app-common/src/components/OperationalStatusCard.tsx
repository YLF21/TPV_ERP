import { useCallback, useEffect, useState } from "react";
import "./OperationalStatusCard.css";
import { ApiError, apiRequest } from "../api/client";
import type { LocaleCode } from "../types";

type RequestFunction = typeof apiRequest;

type VerifactuStatus = {
  certificateConfigured: boolean;
  certificateValid: boolean;
  warning?: string | null;
  subject?: string | null;
  endpointMode?: string | null;
  workerEnabled: boolean;
  signatureRequired: boolean;
  signatureMode?: string | null;
  verifactuActive: boolean;
  activationMode?: string | null;
  firstSubmissionAt?: string | null;
};

type ClockStatus = {
  warning?: boolean;
  warningCode?: string | null;
  driftSeconds?: number;
  thresholdSeconds?: number;
  checkedAt?: string | null;
};

type OutboxStatus = {
  pending?: number;
  sending?: number;
  sent?: number;
  error?: number;
  deadLetter?: number;
};

type SyncOutboxIncident = {
  eventId: string;
  entityType: string;
  entityId: string;
  operation: string;
  status: string;
  attempts: number;
  lastError?: string | null;
  updatedAt?: string | null;
  version: number;
};

type MemberBalanceRecoveryIncident = {
  sessionId: string;
  recoveryKind: string;
  paymentStatus: string;
  ticketId?: string | null;
  ticketNumber?: string | null;
  reservationId?: string | null;
  requestedAmount?: number | null;
  appliedAmount?: number | null;
  attempts: number;
  nextAttemptAt?: string | null;
  lastError?: string | null;
  manualReviewRequired: boolean;
  disposition?: string | null;
  retryAllowed?: boolean;
  updatedAt?: string | null;
  version: number;
};

type PendingManualRetry = {
  kind: "outbox" | "memberBalance";
  id: string;
  version: number;
};

type Props = {
  locale: LocaleCode;
  token?: string;
  request?: RequestFunction;
};

const copy = {
  es: {
    title: "Estado operativo",
    description: "Supervisa fiscalidad, reloj del sistema y sincronización desde este panel.",
    signIn: "Inicia sesión para consultar el estado operativo.",
    refresh: "Actualizar",
    loading: "Consultando servicios…",
    fiscal: "VERI*FACTU",
    active: "Activo",
    inactive: "Inactivo",
    certificate: "Certificado",
    configured: "Configurado",
    missing: "No configurado",
    valid: "Válido",
    invalid: "No válido",
    worker: "Envío automático",
    endpoint: "Entorno",
    clock: "Reloj",
    drift: "Desfase",
    synchronization: "Sincronización",
    pending: "Pendientes",
    sending: "Enviando",
    sent: "Enviados",
    errors: "Errores",
    deadLetter: "Bloqueados",
    retryFiscal: "Reintentar envío fiscal",
    flushSync: "Sincronizar ahora",
    actionDone: "Operación solicitada correctamente.",
    unavailable: "El estado operativo no está disponible para este usuario o entorno.",
    incidents: "Incidencias operativas",
    incidentsDescription: "Elementos que requieren intervención administrativa y quedan registrados en auditoría.",
    incidentsUnavailable: "No se pudieron consultar todas las incidencias operativas.",
    outboxIncidents: "Sincronización bloqueada",
    memberBalanceIncidents: "Recuperación de saldo de miembro",
    noOutboxIncidents: "No hay eventos de sincronización bloqueados.",
    noMemberBalanceIncidents: "No hay recuperaciones de saldo pendientes de intervención.",
    reference: "Referencia",
    entity: "Entidad",
    operation: "Operación",
    attempts: "Intentos",
    classification: "Clasificación",
    updated: "Actualizado",
    detail: "Detalle",
    action: "Acción",
    ticket: "Ticket",
    amount: "Importe",
    retryEvent: "Reintentar evento",
    retryBalance: "Reintentar recuperación",
    manualReconciliation: "Conciliación manual obligatoria",
    manualReview: "Revisión administrativa",
    automaticRecovery: "Recuperación automática",
    retryScheduled: "Reintento programado",
    retryNotAllowed: "Sin reintento automático",
    retryReason: "Motivo del reintento",
    retryReasonPlaceholder: "Indica la comprobación realizada antes de reabrir la incidencia",
    confirmRetry: "Confirmar reintento",
    cancel: "Cancelar",
  },
  en: {
    title: "Operational status",
    description: "Monitor tax reporting, system clock and synchronization from this panel.",
    signIn: "Sign in to check operational status.",
    refresh: "Refresh",
    loading: "Checking services…",
    fiscal: "VERI*FACTU",
    active: "Active",
    inactive: "Inactive",
    certificate: "Certificate",
    configured: "Configured",
    missing: "Not configured",
    valid: "Valid",
    invalid: "Invalid",
    worker: "Automatic submission",
    endpoint: "Environment",
    clock: "Clock",
    drift: "Drift",
    synchronization: "Synchronization",
    pending: "Pending",
    sending: "Sending",
    sent: "Sent",
    errors: "Errors",
    deadLetter: "Blocked",
    retryFiscal: "Retry tax submission",
    flushSync: "Synchronize now",
    actionDone: "Operation requested successfully.",
    unavailable: "Operational status is unavailable for this user or environment.",
    incidents: "Operational incidents",
    incidentsDescription: "Items requiring administrator intervention, with every action recorded in the audit trail.",
    incidentsUnavailable: "Not all operational incidents could be loaded.",
    outboxIncidents: "Blocked synchronization",
    memberBalanceIncidents: "Member balance recovery",
    noOutboxIncidents: "There are no blocked synchronization events.",
    noMemberBalanceIncidents: "There are no member balance recoveries awaiting intervention.",
    reference: "Reference",
    entity: "Entity",
    operation: "Operation",
    attempts: "Attempts",
    classification: "Classification",
    updated: "Updated",
    detail: "Detail",
    action: "Action",
    ticket: "Ticket",
    amount: "Amount",
    retryEvent: "Retry event",
    retryBalance: "Retry recovery",
    manualReconciliation: "Manual reconciliation required",
    manualReview: "Administrator review",
    automaticRecovery: "Automatic recovery",
    retryScheduled: "Retry scheduled",
    retryNotAllowed: "Automatic retry unavailable",
    retryReason: "Retry reason",
    retryReasonPlaceholder: "Describe the check completed before reopening the incident",
    confirmRetry: "Confirm retry",
    cancel: "Cancel",
  },
  zh: {
    title: "运行状态",
    description: "在此面板中监控税务、系统时钟和数据同步。",
    signIn: "请登录后查看运行状态。",
    refresh: "刷新",
    loading: "正在查询服务…",
    fiscal: "VERI*FACTU",
    active: "已启用",
    inactive: "未启用",
    certificate: "证书",
    configured: "已配置",
    missing: "未配置",
    valid: "有效",
    invalid: "无效",
    worker: "自动提交",
    endpoint: "环境",
    clock: "系统时钟",
    drift: "时间偏差",
    synchronization: "数据同步",
    pending: "待处理",
    sending: "发送中",
    sent: "已发送",
    errors: "错误",
    deadLetter: "已阻止",
    retryFiscal: "重试税务提交",
    flushSync: "立即同步",
    actionDone: "操作请求成功。",
    unavailable: "当前用户或环境无法查看运行状态。",
    incidents: "运行事件",
    incidentsDescription: "需要管理员介入的项目，所有操作均记录在审计跟踪中。",
    incidentsUnavailable: "无法加载所有运行事件。",
    outboxIncidents: "同步已阻止",
    memberBalanceIncidents: "会员余额恢复",
    noOutboxIncidents: "没有被阻止的同步事件。",
    noMemberBalanceIncidents: "没有等待处理的会员余额恢复。",
    reference: "参考号",
    entity: "实体",
    operation: "操作",
    attempts: "尝试次数",
    classification: "分类",
    updated: "更新时间",
    detail: "详情",
    action: "操作",
    ticket: "小票",
    amount: "金额",
    retryEvent: "重试事件",
    retryBalance: "重试恢复",
    manualReconciliation: "必须手动对账",
    manualReview: "管理员审查",
    automaticRecovery: "自动恢复",
    retryScheduled: "已安排重试",
    retryNotAllowed: "不允许自动重试",
    retryReason: "重试原因",
    retryReasonPlaceholder: "说明重新打开事件前已完成的检查",
    confirmRetry: "确认重试",
    cancel: "取消",
  },
} as const;

const localeTags: Record<LocaleCode, string> = {
  es: "es-ES",
  en: "en-GB",
  zh: "zh-CN",
};

function shortReference(value?: string | null) {
  return value ? value.slice(0, 8).toUpperCase() : "—";
}

function formatTimestamp(value: string | null | undefined, locale: LocaleCode) {
  if (!value) return "—";
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return new Intl.DateTimeFormat(localeTags[locale], {
    dateStyle: "short",
    timeStyle: "short",
  }).format(parsed);
}

function formatAmount(value: number | null | undefined, locale: LocaleCode) {
  if (value == null) return "—";
  return new Intl.NumberFormat(localeTags[locale], {
    style: "currency",
    currency: "EUR",
  }).format(value);
}

function errorText(error: unknown, fallback: string) {
  if (error instanceof ApiError) {
    const detail = error.problem?.detail;
    const title = error.problem?.title;
    return (
      (typeof detail === "string" && detail) ||
      (typeof title === "string" && title) ||
      error.message ||
      fallback
    );
  }
  return error instanceof Error ? error.message : fallback;
}

export function OperationalStatusCard({ locale, token, request = apiRequest }: Props) {
  const t = copy[locale];
  const [fiscal, setFiscal] = useState<VerifactuStatus | null>(null);
  const [clock, setClock] = useState<ClockStatus | null>(null);
  const [outbox, setOutbox] = useState<OutboxStatus | null>(null);
  const [outboxIncidents, setOutboxIncidents] = useState<SyncOutboxIncident[]>([]);
  const [memberBalanceIncidents, setMemberBalanceIncidents] = useState<MemberBalanceRecoveryIncident[]>([]);
  const [incidentsUnavailable, setIncidentsUnavailable] = useState(false);
  const [pendingRetry, setPendingRetry] = useState<PendingManualRetry | null>(null);
  const [retryReason, setRetryReason] = useState("");
  const [loading, setLoading] = useState(Boolean(token));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    setError(null);
    const results = await Promise.allSettled([
      request<VerifactuStatus>("/verifactu/admin/status", { token }),
      request<ClockStatus>("/verifactu/admin/clock", { token }),
      request<OutboxStatus>("/sync/outbox/status", { token }),
      request<SyncOutboxIncident[]>("/sync/outbox/incidents", { token }),
      request<MemberBalanceRecoveryIncident[]>("/admin/member-balance-recovery", { token }),
    ]);
    setFiscal(results[0].status === "fulfilled" ? results[0].value : null);
    setClock(results[1].status === "fulfilled" ? results[1].value : null);
    setOutbox(results[2].status === "fulfilled" ? results[2].value : null);
    setOutboxIncidents(results[3].status === "fulfilled" && Array.isArray(results[3].value) ? results[3].value : []);
    setMemberBalanceIncidents(results[4].status === "fulfilled" && Array.isArray(results[4].value) ? results[4].value : []);
    setIncidentsUnavailable(results[3].status === "rejected" || results[4].status === "rejected");
    if (results.every((result) => result.status === "rejected")) {
      setError(t.unavailable);
    }
    setLoading(false);
  }, [request, t.unavailable, token]);

  useEffect(() => {
    void load();
  }, [load]);

  const execute = async (path: string) => {
    if (!token) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await request(path, { token, method: "POST" });
      setNotice(t.actionDone);
      await load();
    } catch (operationError) {
      setError(errorText(operationError, t.unavailable));
    } finally {
      setBusy(false);
    }
  };

  const openRetry = (kind: PendingManualRetry["kind"], id: string, version: number) => {
    setPendingRetry({ kind, id, version });
    setRetryReason("");
    setError(null);
    setNotice(null);
  };

  const executeManualRetry = async () => {
    if (!token || !pendingRetry || !retryReason.trim()) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    const path = pendingRetry.kind === "outbox"
      ? `/sync/outbox/events/${encodeURIComponent(pendingRetry.id)}/retry`
      : `/admin/member-balance-recovery/${encodeURIComponent(pendingRetry.id)}/retry`;
    try {
      await request(path, {
        token,
        method: "POST",
        body: {
          expectedVersion: pendingRetry.version,
          reason: retryReason.trim(),
        },
      });
      setPendingRetry(null);
      setRetryReason("");
      setNotice(t.actionDone);
      await load();
    } catch (operationError) {
      setError(errorText(operationError, t.unavailable));
    } finally {
      setBusy(false);
    }
  };

  const memberBalanceClassification = (incident: MemberBalanceRecoveryIncident) => {
    if (incident.disposition === "MANUAL_RECONCILIATION_REQUIRED") return t.manualReconciliation;
    if (incident.manualReviewRequired) return t.manualReview;
    if (incident.nextAttemptAt) return t.retryScheduled;
    return t.automaticRecovery;
  };

  return (
    <section className="settings-card operational-status-card">
      <div className="settings-card-heading operational-status-heading">
        <div>
          <h3>{t.title}</h3>
          <p>{t.description}</p>
        </div>
        <button className="secondary-button" type="button" disabled={!token || loading || busy} onClick={() => void load()}>
          {t.refresh}
        </button>
      </div>

      {!token ? <div className="settings-empty-state">{t.signIn}</div> : null}
      {loading ? <div className="settings-empty-state">{t.loading}</div> : null}
      {error ? <div className="settings-inline-message error">{error}</div> : null}
      {notice ? <div className="settings-inline-message success">{notice}</div> : null}

      {token && !loading ? (
        <>
        <div className="operational-status-grid">
          <div className="operational-status-panel">
            <div className="operational-status-title">
              <h4>{t.fiscal}</h4>
              <span className={`status-pill ${fiscal?.verifactuActive ? "success" : "neutral"}`}>
                {fiscal?.verifactuActive ? t.active : t.inactive}
              </span>
            </div>
            <dl>
              <div>
                <dt>{t.certificate}</dt>
                <dd>
                  {fiscal?.certificateConfigured ? t.configured : t.missing}
                  {fiscal?.certificateConfigured ? ` · ${fiscal.certificateValid ? t.valid : t.invalid}` : ""}
                </dd>
              </div>
              <div>
                <dt>{t.worker}</dt>
                <dd>{fiscal?.workerEnabled ? t.active : t.inactive}</dd>
              </div>
              <div>
                <dt>{t.endpoint}</dt>
                <dd>{fiscal?.endpointMode || "—"}</dd>
              </div>
            </dl>
            {fiscal?.warning ? <div className="settings-inline-message warning">{fiscal.warning}</div> : null}
            <button
              className="secondary-button"
              type="button"
              disabled={busy}
              onClick={() => void execute("/verifactu/admin/retry-next")}
            >
              {t.retryFiscal}
            </button>
          </div>

          <div className="operational-status-panel">
            <div className="operational-status-title">
              <h4>{t.clock}</h4>
              <span className={`status-pill ${clock?.warning ? "warning" : "success"}`}>
                {clock?.warning ? clock.warningCode || "WARN" : "OK"}
              </span>
            </div>
            <dl>
              <div>
                <dt>{t.drift}</dt>
                <dd>{clock?.driftSeconds ?? 0} s</dd>
              </div>
            </dl>
          </div>

          <div className="operational-status-panel">
            <div className="operational-status-title">
              <h4>{t.synchronization}</h4>
              <span className={`status-pill ${(outbox?.error ?? 0) > 0 || (outbox?.deadLetter ?? 0) > 0 ? "warning" : "success"}`}>
                {(outbox?.error ?? 0) > 0 || (outbox?.deadLetter ?? 0) > 0 ? t.errors : "OK"}
              </span>
            </div>
            <div className="outbox-metrics">
              <div>
                <span>{t.pending}</span>
                <strong>{outbox?.pending ?? 0}</strong>
              </div>
              <div>
                <span>{t.sending}</span>
                <strong>{outbox?.sending ?? 0}</strong>
              </div>
              <div>
                <span>{t.sent}</span>
                <strong>{outbox?.sent ?? 0}</strong>
              </div>
              <div>
                <span>{t.errors}</span>
                <strong>{outbox?.error ?? 0}</strong>
              </div>
              <div>
                <span>{t.deadLetter}</span>
                <strong>{outbox?.deadLetter ?? 0}</strong>
              </div>
            </div>
            <button
              className="primary-button"
              type="button"
              disabled={busy}
              onClick={() => void execute("/sync/outbox/flush")}
            >
              {t.flushSync}
            </button>
          </div>
        </div>

        <div className="operational-incidents-board">
          <div className="operational-incidents-heading">
            <div>
              <h4>{t.incidents}</h4>
              <p>{t.incidentsDescription}</p>
            </div>
            <span className={`status-pill ${outboxIncidents.length + memberBalanceIncidents.length > 0 ? "warning" : "success"}`}>
              {outboxIncidents.length + memberBalanceIncidents.length}
            </span>
          </div>

          {incidentsUnavailable ? <div className="settings-inline-message warning">{t.incidentsUnavailable}</div> : null}

          <div className="operational-incident-group">
            <div className="operational-incident-group-title">
              <h5>{t.outboxIncidents}</h5>
              <span>{outboxIncidents.length}</span>
            </div>
            {outboxIncidents.length === 0 ? (
              <div className="operational-incident-empty">{t.noOutboxIncidents}</div>
            ) : (
              <div className="operational-incident-table-wrap">
                <table className="operational-incident-table">
                  <thead>
                    <tr>
                      <th scope="col">{t.reference}</th>
                      <th scope="col">{t.entity}</th>
                      <th scope="col">{t.operation}</th>
                      <th scope="col">{t.attempts}</th>
                      <th scope="col">{t.updated}</th>
                      <th scope="col">{t.detail}</th>
                      <th scope="col">{t.action}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {outboxIncidents.map((incident) => (
                      <tr key={incident.eventId}>
                        <td><code title={incident.eventId}>{shortReference(incident.eventId)}</code></td>
                        <td>
                          <strong>{incident.entityType}</strong>
                          <small title={incident.entityId}>{shortReference(incident.entityId)}</small>
                        </td>
                        <td>{incident.operation}</td>
                        <td className="operational-incident-number">{incident.attempts}</td>
                        <td>{formatTimestamp(incident.updatedAt, locale)}</td>
                        <td className="operational-incident-error" title={incident.lastError ?? undefined}>{incident.lastError || "—"}</td>
                        <td>
                          <button
                            type="button"
                            className="secondary-button operational-incident-action"
                            disabled={busy}
                            onClick={() => openRetry("outbox", incident.eventId, incident.version)}
                          >
                            {t.retryEvent}
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className="operational-incident-group">
            <div className="operational-incident-group-title">
              <h5>{t.memberBalanceIncidents}</h5>
              <span>{memberBalanceIncidents.length}</span>
            </div>
            {memberBalanceIncidents.length === 0 ? (
              <div className="operational-incident-empty">{t.noMemberBalanceIncidents}</div>
            ) : (
              <div className="operational-incident-table-wrap">
                <table className="operational-incident-table member-balance-incident-table">
                  <thead>
                    <tr>
                      <th scope="col">{t.ticket}</th>
                      <th scope="col">{t.amount}</th>
                      <th scope="col">{t.attempts}</th>
                      <th scope="col">{t.classification}</th>
                      <th scope="col">{t.updated}</th>
                      <th scope="col">{t.detail}</th>
                      <th scope="col">{t.action}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {memberBalanceIncidents.map((incident) => (
                      <tr key={incident.sessionId}>
                        <td>
                          <strong>{incident.ticketNumber || shortReference(incident.ticketId || incident.sessionId)}</strong>
                          <small>{incident.recoveryKind}</small>
                        </td>
                        <td>{formatAmount(incident.appliedAmount ?? incident.requestedAmount, locale)}</td>
                        <td className="operational-incident-number">{incident.attempts}</td>
                        <td>
                          <span className={`status-pill ${incident.retryAllowed === true ? "warning" : "neutral"}`}>
                            {memberBalanceClassification(incident)}
                          </span>
                        </td>
                        <td>{formatTimestamp(incident.updatedAt, locale)}</td>
                        <td className="operational-incident-error" title={incident.lastError ?? undefined}>{incident.lastError || "—"}</td>
                        <td>
                          {incident.retryAllowed === true ? (
                            <button
                              type="button"
                              className="secondary-button operational-incident-action"
                              disabled={busy}
                              onClick={() => openRetry("memberBalance", incident.sessionId, incident.version)}
                            >
                              {t.retryBalance}
                            </button>
                          ) : <span className="operational-incident-locked">{t.retryNotAllowed}</span>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {pendingRetry ? (
            <div className="operational-retry-editor" role="group" aria-labelledby="operational-retry-label">
              <label id="operational-retry-label" htmlFor="operational-retry-reason">{t.retryReason}</label>
              <textarea
                id="operational-retry-reason"
                value={retryReason}
                maxLength={500}
                autoFocus
                placeholder={t.retryReasonPlaceholder}
                onChange={(event) => setRetryReason(event.target.value)}
              />
              <div className="operational-retry-actions">
                <button type="button" disabled={busy || !retryReason.trim()} onClick={() => void executeManualRetry()}>
                  {t.confirmRetry}
                </button>
                <button
                  type="button"
                  className="secondary-button"
                  disabled={busy}
                  onClick={() => {
                    setPendingRetry(null);
                    setRetryReason("");
                  }}
                >
                  {t.cancel}
                </button>
              </div>
            </div>
          ) : null}
        </div>
        </>
      ) : null}
    </section>
  );
}
