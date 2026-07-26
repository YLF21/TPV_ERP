import { useEffect, useState } from "react";
import { ApiError, apiRequest } from "../api/client";
import type { LocaleCode } from "../types";

type CashStoreConfig = {
  storeId: string;
  discrepancyTolerance: number;
  requireEntryBreakdown: boolean;
  requireWithdrawalBreakdown: boolean;
  requireClosingBreakdown: boolean;
  cashSessionRequired: boolean;
};

type Props = {
  locale: LocaleCode;
  token?: string;
  request?: typeof apiRequest;
};

const copy = {
  es: {
    title: "Sesión de caja en Ventas",
    description: "Configura cómo debe iniciarse la caja al entrar en la pantalla de Ventas.",
    required: "Sesión de caja obligatoria",
    yes: "Sí, exigir apertura manual",
    no: "No, abrir automáticamente",
    helpYes: "Sin una caja abierta, Ventas queda bloqueada hasta abrirla o salir.",
    helpNo: "Ventas abre la caja automáticamente con el saldo conservado del cierre anterior.",
    loading: "Consultando configuración…",
    save: "Guardar configuración",
    saving: "Guardando…",
    saved: "Configuración guardada.",
    error: "No se pudo guardar la configuración de caja.",
  },
  en: {
    title: "Cash session in Sales",
    description: "Configure how the cash session starts when entering Sales.",
    required: "Cash session required",
    yes: "Yes, require manual opening",
    no: "No, open automatically",
    helpYes: "Without an open session, Sales remains blocked until it is opened or exited.",
    helpNo: "Sales opens the session automatically with the balance retained at the previous close.",
    loading: "Loading configuration…",
    save: "Save configuration",
    saving: "Saving…",
    saved: "Configuration saved.",
    error: "The cash configuration could not be saved.",
  },
  zh: {
    title: "销售收银会话",
    description: "配置进入销售界面时收银会话的启动方式。",
    required: "必须开启收银会话",
    yes: "是，需要手动开启",
    no: "否，自动开启",
    helpYes: "未开启收银会话时，只能开启会话或退出销售。",
    helpNo: "销售会使用上次关账保留的余额自动开启收银会话。",
    loading: "正在读取配置…",
    save: "保存配置",
    saving: "正在保存…",
    saved: "配置已保存。",
    error: "无法保存收银配置。",
  },
} as const;

function errorText(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    const detail = error.problem?.detail;
    const title = error.problem?.title;
    return (typeof detail === "string" && detail)
      || (typeof title === "string" && title)
      || error.message
      || fallback;
  }
  return error instanceof Error ? error.message : fallback;
}

export function CashPolicySettingsCard({ locale, token, request = apiRequest }: Props) {
  const t = copy[locale];
  const [config, setConfig] = useState<CashStoreConfig | null>(null);
  const [savedValue, setSavedValue] = useState(false);
  const [loading, setLoading] = useState(Boolean(token));
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ kind: "success" | "error"; text: string } | null>(null);

  useEffect(() => {
    let active = true;
    if (!token) {
      setLoading(false);
      return () => { active = false; };
    }
    setLoading(true);
    void request<CashStoreConfig>("/cash/config", { token })
      .then((result) => {
        if (!active) return;
        setConfig(result);
        setSavedValue(result.cashSessionRequired);
      })
      .catch((error) => {
        if (active) setMessage({ kind: "error", text: errorText(error, t.error) });
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
  }, [request, t.error, token]);

  async function save() {
    if (!token || !config) return;
    setSaving(true);
    setMessage(null);
    try {
      const saved = await request<CashStoreConfig>("/cash/config", {
        token,
        method: "PUT",
        body: {
          discrepancyTolerance: config.discrepancyTolerance,
          requireEntryBreakdown: config.requireEntryBreakdown,
          requireWithdrawalBreakdown: config.requireWithdrawalBreakdown,
          requireClosingBreakdown: config.requireClosingBreakdown,
          cashSessionRequired: config.cashSessionRequired,
        },
      });
      setConfig(saved);
      setSavedValue(saved.cashSessionRequired);
      setMessage({ kind: "success", text: t.saved });
    } catch (error) {
      setMessage({ kind: "error", text: errorText(error, t.error) });
    } finally {
      setSaving(false);
    }
  }

  return (
    <article className="settings-card settings-card-wide settings-cash-policy-card">
      <h3>{t.title}</h3>
      <p>{t.description}</p>
      {loading ? <p role="status">{t.loading}</p> : config ? (
        <>
          <fieldset disabled={saving}>
            <legend>{t.required}</legend>
            <label>
              <input
                type="radio"
                name="cash-session-required"
                checked={config.cashSessionRequired}
                onChange={() => setConfig({ ...config, cashSessionRequired: true })}
              />
              <span><strong>{t.yes}</strong><small>{t.helpYes}</small></span>
            </label>
            <label>
              <input
                type="radio"
                name="cash-session-required"
                checked={!config.cashSessionRequired}
                onChange={() => setConfig({ ...config, cashSessionRequired: false })}
              />
              <span><strong>{t.no}</strong><small>{t.helpNo}</small></span>
            </label>
          </fieldset>
          <button
            type="button"
            disabled={saving || config.cashSessionRequired === savedValue}
            onClick={() => void save()}
          >
            {saving ? t.saving : t.save}
          </button>
        </>
      ) : null}
      {message && (
        <p className={`settings-user-message ${message.kind}`} role={message.kind === "error" ? "alert" : "status"}>
          {message.text}
        </p>
      )}
    </article>
  );
}
