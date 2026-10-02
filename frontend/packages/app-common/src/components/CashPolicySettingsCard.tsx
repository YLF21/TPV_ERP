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
    title: "Apertura antes de vender",
    description: "Se aplica a todas las terminales de esta tienda. Cada terminal conserva su propio saldo.",
    required: "Modo de apertura",
    yes: "Sí, exigir apertura manual con recuento",
    no: "No, apertura automática",
    helpYes: "Cada usuario cuenta el efectivo e introduce el fondo inicial antes de vender.",
    helpNo: "Cada terminal utiliza su propio saldo del último cierre y los movimientos posteriores.",
    differences: "Diferencias en la apertura",
    differencesHelp: "La caja se abre aunque el recuento no coincida. Se registra una alerta para revisión en Caja → Cierres de caja.",
    expected: "Saldo esperado",
    expectedValue: "Saldo calculado antes de abrir",
    counted: "Efectivo contado",
    countedValue: "Fondo inicial real de la sesión",
    difference: "Diferencia",
    differenceValue: "Contado − esperado",
    loading: "Consultando configuración…",
    save: "Guardar configuración",
    saving: "Guardando…",
    saved: "Configuración guardada.",
    error: "No se pudo guardar la configuración de caja.",
  },
  en: {
    title: "Opening before sales",
    description: "Applies to every terminal in this store. Each terminal keeps its own balance.",
    required: "Opening mode",
    yes: "Yes, require manual opening with cash count",
    no: "No, open automatically",
    helpYes: "Each user counts cash and enters the initial fund before selling.",
    helpNo: "Each terminal uses its own balance from the last closure and subsequent movements.",
    differences: "Differences at opening",
    differencesHelp: "The session opens even if the count differs. An alert is recorded for review under Cash → Cash closures.",
    expected: "Expected balance",
    expectedValue: "Balance calculated before opening",
    counted: "Counted cash",
    countedValue: "Actual opening fund for the session",
    difference: "Difference",
    differenceValue: "Counted − expected",
    loading: "Loading configuration…",
    save: "Save configuration",
    saving: "Saving…",
    saved: "Configuration saved.",
    error: "The cash configuration could not be saved.",
  },
  zh: {
    title: "销售前开启钱箱",
    description: "适用于本店所有终端。每台终端保留各自的余额。",
    required: "开启方式",
    yes: "是，手动清点后开启",
    no: "否，自动开启",
    helpYes: "每位用户销售前清点现金并输入实际初始金额。",
    helpNo: "每台终端使用上次关账留存的余额和之后的现金变动。",
    differences: "开箱差额",
    differencesHelp: "清点金额不符时仍可开启钱箱，并在现金管理 → 关账记录中生成待审核警报。",
    expected: "预期余额",
    expectedValue: "开启前计算的余额",
    counted: "清点现金",
    countedValue: "会话的实际初始金额",
    difference: "差额",
    differenceValue: "清点 − 预期",
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
          <section className="settings-cash-policy-differences">
            <h4>{t.differences}</h4>
            <p>{t.differencesHelp}</p>
            <table><thead><tr><th>{t.expected}</th><th>{t.counted}</th><th>{t.difference}</th></tr></thead>
              <tbody><tr><td>{t.expectedValue}</td><td>{t.countedValue}</td><td>{t.differenceValue}</td></tr></tbody></table>
          </section>
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
