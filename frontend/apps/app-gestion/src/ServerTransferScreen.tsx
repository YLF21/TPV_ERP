import { useCallback, useEffect, useState, type FormEvent } from "react";
import type { UserSession } from "@tpverp/app-common";
import { cancelServerTransfer, loadServerTransferStatus, prepareServerTransfer, sealServerTransfer, type ServerTransferStatus } from "./serverTransferApi";
import "./BackupManagementScreen.css";

type Props = { session: UserSession; t: (key: string) => string; onOpenMaintenance: () => void };

export function ServerTransferScreen({ session, t, onOpenMaintenance }: Props) {
  const allowed = session.permissions.includes("ADMIN");
  const [status, setStatus] = useState<ServerTransferStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [password, setPassword] = useState("");
  const [destinationPublicKey, setDestinationPublicKey] = useState("");
  const [destinationProof, setDestinationProof] = useState("");
  const token = session.accessToken ?? "";

  const refresh = useCallback(async () => {
    if (!allowed || !token || !window.tpvDesktop?.serverTransfer?.requestOrigin) return;
    setError("");
    try { setStatus(await loadServerTransferStatus(token)); }
    catch { setError(t("gestion.transfer.loadError")); }
  }, [allowed, token, t]);
  useEffect(() => { void refresh(); }, [refresh]);

  async function prepare(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(""); setNotice("");
    try {
      setStatus(await prepareServerTransfer(token, {
        adminPassword: password, destinationPublicKey: destinationPublicKey.trim(), destinationProof: destinationProof.trim()
      }));
      setPassword(""); setDestinationPublicKey(""); setDestinationProof("");
      setNotice(t("gestion.transfer.prepared"));
    } catch { setError(t("gestion.transfer.prepareError")); }
    finally { setBusy(false); }
  }

  async function transition(action: "seal" | "cancel") {
    if (!status?.transferId || !password) { setError(t("gestion.transfer.passwordRequired")); return; }
    setBusy(true); setError(""); setNotice("");
    try {
      setStatus(await (action === "seal"
        ? sealServerTransfer(token, status.transferId, password)
        : cancelServerTransfer(token, status.transferId, password)));
      setPassword("");
      setNotice(t(action === "seal" ? "gestion.transfer.sealed" : "gestion.transfer.cancelled"));
    } catch { setError(t(action === "seal" ? "gestion.transfer.sealError" : "gestion.transfer.cancelError")); }
    finally { setBusy(false); }
  }

  async function importRequest() {
    const result = await window.tpvDesktop?.serverTransfer?.openRequest();
    if (result?.ok && result.destinationPublicKey && result.destinationProof) {
      setDestinationPublicKey(result.destinationPublicKey);
      setDestinationProof(result.destinationProof);
      setError("");
      setNotice(t("gestion.transfer.requestLoaded"));
    } else if (result && !("canceled" in result && result.canceled)) setError(t("gestion.transfer.requestError"));
  }

  if (!allowed) return <section className="gestion-workspace"><p role="alert">{t("gestion.transfer.noAccess")}</p></section>;
  if (!window.tpvDesktop?.serverTransfer?.requestOrigin) return <section className="gestion-workspace"><p role="alert">{t("gestion.transfer.desktopOnly")}</p></section>;
  return <section className="gestion-workspace gestion-backups-workspace gestion-transfer-workspace">
    <header className="gestion-dashboard-toolbar gestion-security-header"><div><span className="gestion-eyebrow">{t("gestion.security.eyebrow")}</span><h2>{t("gestion.transfer.title")}</h2><p>{t("gestion.transfer.subtitle")}</p></div><div className="gestion-dashboard-actions"><button type="button" onClick={() => void refresh()} disabled={busy}>{t("common.refresh")}</button></div></header>
    {error && <p className="gestion-backups-feedback error" role="alert">{error}</p>}
    {notice && <p className="gestion-backups-feedback" role="status">{notice}</p>}
    <div className="gestion-transfer-columns">
      <section className="gestion-transfer-panel"><h3>{t("gestion.transfer.origin")}</h3>
        <dl><div><dt>{t("gestion.transfer.state")}</dt><dd>{t(`gestion.transfer.state.${status?.state ?? "NONE"}`)}</dd></div><div><dt>{t("gestion.transfer.id")}</dt><dd>{status?.transferId ?? "—"}</dd></div><div><dt>{t("gestion.transfer.fingerprint")}</dt><dd>{status?.destinationKeyFingerprint ?? "—"}</dd></div><div><dt>{t("gestion.transfer.backupDigest")}</dt><dd>{status?.backupSha256 ?? "—"}</dd></div></dl>
        {(status?.state === "NONE" || status?.state === "CANCELLED" || status?.state === "ACTIVE") && <form onSubmit={(event) => void prepare(event)}>
          <p>{t("gestion.transfer.prepareHint")}</p>
          {window.tpvDesktop?.serverTransfer && <button type="button" onClick={() => void importRequest()}>{t("gestion.transfer.importRequest")}</button>}
          <details><summary>{t("gestion.transfer.advancedRequest")}</summary>
            <label>{t("gestion.transfer.publicKey")}<textarea value={destinationPublicKey} onChange={e => setDestinationPublicKey(e.target.value)} /></label>
            <label>{t("gestion.transfer.proof")}<textarea value={destinationProof} onChange={e => setDestinationProof(e.target.value)} /></label>
          </details>
          <label>{t("gestion.transfer.adminPassword")}<input type="password" required autoComplete="off" value={password} onChange={e => setPassword(e.target.value)} /></label>
          <button className="primary" disabled={busy || !password || !destinationPublicKey.trim() || !destinationProof.trim()}>{t("gestion.transfer.prepare")}</button>
        </form>}
        {(status?.state === "PREPARED" || status?.state === "FROZEN" || status?.state === "SEALED") && <div className="gestion-transfer-actions">
          <label>{t("gestion.transfer.adminPassword")}<input type="password" autoComplete="off" value={password} onChange={e => setPassword(e.target.value)} /></label>
          {status.state !== "SEALED" && <button type="button" className="primary" disabled={busy || !password} onClick={() => void transition("seal")}>{t("gestion.transfer.seal")}</button>}
          <button type="button" disabled={busy || !password} onClick={() => void transition("cancel")}>{t(status.state === "SEALED" ? "gestion.transfer.cancelSealed" : "gestion.transfer.cancel")}</button>
        </div>}
        {status?.state === "SEALED" && <p className="gestion-backups-feedback warning">{t("gestion.transfer.sealedHint")}</p>}
      </section>
      <section className="gestion-transfer-panel"><h3>{t("gestion.transfer.destination")}</h3>
        <p>{t("gestion.transfer.destinationHint")}</p>
        <p className="gestion-backups-feedback warning">{t("gestion.transfer.destinationOffline")}</p>
        {window.tpvDesktop?.serverTransfer && <button type="button" onClick={onOpenMaintenance}>{t("gestion.transfer.maintenanceTitle")}</button>}
      </section>
    </div>
  </section>;
}
