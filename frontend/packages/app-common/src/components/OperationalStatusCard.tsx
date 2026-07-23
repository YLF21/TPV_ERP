import { useCallback, useEffect, useState } from "react";
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
};

type Props = {
  locale: LocaleCode;
  token?: string;
  request?: RequestFunction;
};

const copy = {
  es: {
    title: "Estado operativo",
    description: "Supervisa fiscalidad, reloj del sistema y sincronización sin salir del puesto de venta.",
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
    retryFiscal: "Reintentar envío fiscal",
    flushSync: "Sincronizar ahora",
    actionDone: "Operación solicitada correctamente.",
    unavailable: "El estado operativo no está disponible para este usuario o entorno.",
  },
  en: {
    title: "Operational status",
    description: "Monitor tax reporting, system clock and synchronization from the point of sale.",
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
    retryFiscal: "Retry tax submission",
    flushSync: "Synchronize now",
    actionDone: "Operation requested successfully.",
    unavailable: "Operational status is unavailable for this user or environment.",
  },
  zh: {
    title: "运行状态",
    description: "在销售终端中监控税务、系统时钟和数据同步。",
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
    retryFiscal: "重试税务提交",
    flushSync: "立即同步",
    actionDone: "操作请求成功。",
    unavailable: "当前用户或环境无法查看运行状态。",
  },
} as const;

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
  const [loading, setLoading] = useState(false);
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
    ]);
    setFiscal(results[0].status === "fulfilled" ? results[0].value : null);
    setClock(results[1].status === "fulfilled" ? results[1].value : null);
    setOutbox(results[2].status === "fulfilled" ? results[2].value : null);
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
              <span className={`status-pill ${(outbox?.error ?? 0) > 0 ? "warning" : "success"}`}>
                {(outbox?.error ?? 0) > 0 ? t.errors : "OK"}
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
      ) : null}
    </section>
  );
}
