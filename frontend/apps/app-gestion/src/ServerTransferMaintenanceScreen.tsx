import { useEffect, useState } from "react";
import type { LocaleCode } from "@tpverp/app-common";
import { WindowCloseButton } from "../../../packages/app-common/src/components/WindowCloseButton";
import "./BackupManagementScreen.css";

type Action = "request" | "receive" | "activate" | "status" | "cancel";
type State = "NONE" | "DESTINATION_READY" | "VALIDATING" | "DATABASE_RESTORED" | "RECEIVED"
  | "ACTIVATING" | "ACTIVE" | "ACTIVE_SERVICE_UNREADY" | "CANCELLED";

export function ServerTransferMaintenanceScreen({ locale: _locale, t, onExit }: {
  locale: LocaleCode; t: (key: string) => string; onExit: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [status, setStatus] = useState<{ state: State; transferId?: string; destinationPublicKey?: string;
    destinationProof?: string; destinationKeyFingerprint?: string } | null>(null);

  async function refresh() {
    setBusy(true);
    try {
      const value = await window.tpvDesktop?.serverTransfer?.readStatus();
      if (value?.ok && value.state) { setStatus(value as NonNullable<typeof status>); setError(""); }
      else { setStatus(null); setError(t("gestion.transfer.statusError")); }
    } catch { setStatus(null); setError(t("gestion.transfer.statusError")); }
    finally { setBusy(false); }
  }
  useEffect(() => { void refresh(); }, []);

  async function start(action: Action) {
    setBusy(true); setError(""); setNotice("");
    try {
      const result = await window.tpvDesktop?.serverTransfer?.startMaintenance({ action });
      if (result?.ok && result.started) setNotice(t("gestion.transfer.maintenanceStarted"));
      else setError(t("gestion.transfer.maintenanceError"));
    } catch { setError(t("gestion.transfer.maintenanceError")); }
    finally { setBusy(false); }
  }

  const state = status?.state;
  return <main className="gestion-workspace gestion-backups-workspace gestion-transfer-maintenance">
    <header className="gestion-dashboard-toolbar gestion-security-header"><div><span className="gestion-eyebrow">{t("gestion.security.eyebrow")}</span><h2>{t("gestion.transfer.maintenanceTitle")}</h2><p>{t("gestion.transfer.maintenanceSubtitle")}</p></div><WindowCloseButton aria-label={t("common.close")} title={t("common.close")} onClick={onExit} /></header>
    <div className="gestion-transfer-maintenance-body">
    <p className="gestion-backups-feedback warning">{t("gestion.transfer.maintenanceWarning")}</p>
    {error && <p className="gestion-backups-feedback error" role="alert">{error}</p>}
    {notice && <p className="gestion-backups-feedback" role="status">{notice}</p>}
    <section className="gestion-transfer-panel">
      <p>{t("gestion.transfer.maintenanceConsole")}</p>
      <dl><div><dt>{t("gestion.transfer.state")}</dt><dd>{status?.state ? t(`gestion.transfer.localState.${status.state}`) : "—"}</dd></div><div><dt>{t("gestion.transfer.id")}</dt><dd>{status?.transferId ?? "—"}</dd></div><div><dt>{t("gestion.transfer.fingerprint")}</dt><dd>{status?.destinationKeyFingerprint ?? "—"}</dd></div></dl>
      <div className="gestion-transfer-maintenance-actions">
        <button type="button" disabled={busy} onClick={() => void refresh()}>{t("gestion.transfer.readLocalStatus")}</button>
        <button type="button" disabled={busy} onClick={() => void start("status")}>{t("gestion.transfer.checkStatus")}</button>
        {state === "NONE" && <button type="button" disabled={busy} onClick={() => void start("request")}>{t("gestion.transfer.request")}</button>}
        {(state === "DESTINATION_READY" || state === "VALIDATING" || state === "DATABASE_RESTORED") &&
          <button type="button" disabled={busy} onClick={() => void start("receive")}>{t("gestion.transfer.receive")}</button>}
        {(state === "RECEIVED" || state === "ACTIVATING" || state === "ACTIVE_SERVICE_UNREADY") &&
          <button type="button" className="primary" disabled={busy} onClick={() => void start("activate")}>{t("gestion.transfer.activate")}</button>}
        {status && state !== "ACTIVE" && <button type="button" disabled={busy} onClick={() => void start("cancel")}>{t("gestion.transfer.cancelDestination")}</button>}
      </div>
      {status?.destinationPublicKey && <label>{t("gestion.transfer.publicKey")}<textarea readOnly value={status.destinationPublicKey} /></label>}
      {status?.destinationProof && <label>{t("gestion.transfer.proof")}<textarea readOnly value={status.destinationProof} /></label>}
    </section>
    </div>
  </main>;
}
