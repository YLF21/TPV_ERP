import { useEffect, useState, type FormEvent } from "react";
import { type UserSession } from "@tpverp/app-common";
import {
  loadVoucherConfiguration,
  saveVoucherConfiguration,
  type VoucherConfiguration
} from "./vouchersApi";
import "./VoucherSettingsScreen.css";

type Props = {
  session: UserSession;
  storeName: string;
  t: (key: string) => string;
};

export function VoucherSettingsScreen({ session, storeName, t }: Props) {
  const [saved, setSaved] = useState<VoucherConfiguration | null>(null);
  const [mode, setMode] = useState<VoucherConfiguration["expirationMode"]>("DAYS");
  const [days, setDays] = useState(365);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ kind: "success" | "error"; text: string } | null>(null);
  const canManage = session.permissions.includes("ADMIN");

  useEffect(() => {
    let active = true;
    setLoading(true);
    void loadVoucherConfiguration(session.accessToken)
      .then((configuration) => {
        if (!active) return;
        setSaved(configuration);
        setMode(configuration.expirationMode);
        setDays(configuration.validityDays);
      })
      .catch(() => {
        if (active) setMessage({ kind: "error", text: t("gestion.voucherSettings.loadError") });
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [session.accessToken, t]);

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!canManage || saving || days < 1 || days > 36500) return;
    setSaving(true);
    setMessage(null);
    try {
      const configuration = await saveVoucherConfiguration(
        mode, days, session.accessToken
      );
      setSaved(configuration);
      setMode(configuration.expirationMode);
      setDays(configuration.validityDays);
      setMessage({ kind: "success", text: t("gestion.voucherSettings.saved") });
    } catch {
      setMessage({ kind: "error", text: t("gestion.voucherSettings.saveError") });
    } finally {
      setSaving(false);
    }
  }

  const changed = saved !== null
    && (saved.expirationMode !== mode || saved.validityDays !== days);

  return (
    <section className="gestion-workspace gestion-voucher-settings-workspace">
      <header className="gestion-voucher-settings-header">
        <div>
          <span>{t("gestion.configuration.navigation")}</span>
          <h2>{t("gestion.voucherSettings.title")}</h2>
          <p>{t("gestion.voucherSettings.description")}</p>
        </div>
        <aside>
          <strong>{t("gestion.voucherSettings.storeScope")}</strong>
          <small>{storeName}</small>
        </aside>
      </header>

      {message && <p className={`gestion-voucher-settings-message ${message.kind}`} role={message.kind === "error" ? "alert" : "status"}>{message.text}</p>}

      {loading ? <p className="gestion-voucher-settings-loading">{t("common.loading")}</p> : (
        <form className="gestion-voucher-settings-panel" onSubmit={save}>
          <div className="gestion-voucher-settings-intro">
            <span className="gestion-voucher-settings-index">{t("gestion.voucherSettings.sectionNumber")}</span>
            <div>
              <h3>{t("gestion.voucherSettings.policyTitle")}</h3>
              <p>{t("gestion.voucherSettings.futureOnly")}</p>
            </div>
          </div>

          <fieldset disabled={!canManage || saving}>
            <legend>{t("gestion.voucherSettings.chooseMode")}</legend>
            <label className={`gestion-voucher-mode-option ${mode === "DAYS" ? "selected" : ""}`}>
              <input type="radio" name="voucher-expiration-mode" value="DAYS" checked={mode === "DAYS"} onChange={() => setMode("DAYS")} />
              <span className="gestion-voucher-mode-marker" aria-hidden="true" />
              <span>
                <strong>{t("gestion.voucherSettings.modeDays")}</strong>
                <small>{t("gestion.voucherSettings.modeDaysHelp")}</small>
              </span>
            </label>

            <div className={`gestion-voucher-days-control ${mode === "DAYS" ? "enabled" : ""}`}>
              <label htmlFor="voucher-validity-days">{t("gestion.voucherSettings.validityDays")}</label>
              <div>
                <input id="voucher-validity-days" type="number" min="1" max="36500" value={days} disabled={!canManage || saving || mode !== "DAYS"} onChange={(event) => setDays(Number(event.target.value))} />
                <span>{t("gestion.voucherSettings.daysUnit")}</span>
              </div>
              <small>{t("gestion.voucherSettings.validityHelp")}</small>
            </div>

            <label className={`gestion-voucher-mode-option ${mode === "NEVER" ? "selected" : ""}`}>
              <input type="radio" name="voucher-expiration-mode" value="NEVER" checked={mode === "NEVER"} onChange={() => setMode("NEVER")} />
              <span className="gestion-voucher-mode-marker" aria-hidden="true" />
              <span>
                <strong>{t("gestion.voucherSettings.modeNever")}</strong>
                <small>{t("gestion.voucherSettings.modeNeverHelp")}</small>
              </span>
            </label>
          </fieldset>

          <footer>
            <div>
              <strong>{mode === "DAYS" ? t("gestion.voucherSettings.summaryDays").replace("{days}", String(days)) : t("gestion.voucherSettings.summaryNever")}</strong>
              <small>{t("gestion.voucherSettings.noRetroactiveChanges")}</small>
            </div>
            <button type="submit" className="primary" disabled={!canManage || saving || !changed || days < 1 || days > 36500}>{saving ? t("common.saving") : t("common.save")}</button>
          </footer>
        </form>
      )}
    </section>
  );
}
