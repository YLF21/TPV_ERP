import { useEffect, useState } from "react";
import type { LocaleCode } from "@tpverp/app-common";
import "./BackupManagementScreen.css";

type State = "NONE" | "DESTINATION_READY" | "DOWNLOAD_AUTHORIZED" | "RECEIVING" | "RECEIVED_QUARANTINED" | "SUPPORT_REVIEW"
  | "ACTIVATION_APPROVED" | "ACTIVATING" | "ACTIVATED" | "ACTIVATED_SERVICE_UNREADY" | "COMPLETE" | "CANCELLED";
type RecoveryStatus = { state: State; recoveryId?: string; destinationKeyFingerprint?: string;
  destinationPublicKey?: string; checkedAt?: string };
type Action = "request" | "receive" | "activate" | "status";

export function DisasterRecoveryMaintenanceScreen({ locale: _locale, t, onExit }: {
  locale: LocaleCode; t: (key: string) => string; onExit: () => void;
}) {
  const [status, setStatus] = useState<RecoveryStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  async function refresh() {
    setBusy(true);
    try {
      const result = await window.tpvDesktop?.disasterRecovery?.readStatus();
      if (result?.ok && result.state) { setStatus(result as RecoveryStatus); setError(""); }
      else { setStatus(null); setError(t("gestion.disaster.statusError")); }
    } catch { setStatus(null); setError(t("gestion.disaster.statusError")); }
    finally { setBusy(false); }
  }
  useEffect(() => { void refresh(); }, []);

  async function start(action: Action) {
    setBusy(true); setError(""); setNotice("");
    try {
      const result = await window.tpvDesktop?.disasterRecovery?.startMaintenance({ action });
      if (result?.ok && result.started) setNotice(t("gestion.disaster.consoleStarted"));
      else setError(t("gestion.disaster.startError"));
    } catch { setError(t("gestion.disaster.startError")); }
    finally { setBusy(false); }
  }

  const state = status?.state ?? "NONE";
  return <main className="gestion-workspace gestion-backups-workspace gestion-transfer-maintenance">
    <header className="gestion-dashboard-toolbar gestion-security-header"><div>
      <span className="gestion-eyebrow">{t("gestion.security.eyebrow")}</span>
      <h2>{t("gestion.disaster.title")}</h2><p>{t("gestion.disaster.subtitle")}</p>
    </div><button type="button" onClick={onExit}>{t("common.close")}</button></header>
    <p className="gestion-backups-feedback warning">{t("gestion.disaster.warning")}</p>
    <p className="gestion-backups-feedback warning">{t("gestion.disaster.dataLossWarning")}</p>
    {error && <p className="gestion-backups-feedback error" role="alert">{error}</p>}
    {notice && <p className="gestion-backups-feedback" role="status">{notice}</p>}
    <section className="gestion-transfer-panel">
      <dl><div><dt>{t("gestion.transfer.state")}</dt><dd>{status ? t(`gestion.disaster.state.${state}`) : "—"}</dd></div>
        <div><dt>{t("gestion.disaster.recoveryId")}</dt><dd>{status?.recoveryId ?? "—"}</dd></div>
        <div><dt>{t("gestion.transfer.fingerprint")}</dt><dd>{status?.destinationKeyFingerprint ?? "—"}</dd></div></dl>
      {status && <p>{t(`gestion.disaster.step.${state}`)}</p>}
      {(state === "RECEIVED_QUARANTINED" || state === "SUPPORT_REVIEW") &&
        <p className="gestion-backups-feedback warning">{t("gestion.disaster.centralEvidenceWarning")}</p>}
      <div className="gestion-transfer-maintenance-actions">
        <button type="button" disabled={busy} onClick={() => void refresh()}>{t("gestion.disaster.readLocalStatus")}</button>
        <button type="button" disabled={busy} onClick={() => void start("status")}>
          {t((["RECEIVED_QUARANTINED", "SUPPORT_REVIEW", "ACTIVATING", "ACTIVATED_SERVICE_UNREADY"] as State[]).includes(state)
            ? "gestion.disaster.checkApproval" : "gestion.disaster.checkInstalledStatus")}
        </button>
        {status && (state === "NONE" || state === "CANCELLED") && <button type="button" className="primary" disabled={busy} onClick={() => void start("request")}>{t("gestion.disaster.request")}</button>}
        {(state === "DESTINATION_READY" || state === "DOWNLOAD_AUTHORIZED") && <button type="button" disabled={busy} onClick={() => void start("receive")}>{t("gestion.disaster.receive")}</button>}
        {state === "RECEIVING" && <button type="button" disabled={busy} onClick={() => void start("receive")}>{t("gestion.disaster.resumeReceive")}</button>}
        {state === "SUPPORT_REVIEW" && <button type="button" disabled={busy} onClick={() => void start("receive")}>{t("gestion.disaster.revalidateReceive")}</button>}
        {state === "ACTIVATION_APPROVED" && <button type="button" className="primary" disabled={busy} onClick={() => void start("activate")}>{t("gestion.disaster.activate")}</button>}
      </div>
      {status?.destinationPublicKey && <details><summary>{t("gestion.disaster.technicalDetails")}</summary>
        <label>{t("gestion.transfer.publicKey")}<textarea readOnly value={status.destinationPublicKey} /></label>
      </details>}
      <p>{t("gestion.disaster.consoleHint")}</p>
    </section>
  </main>;
}
