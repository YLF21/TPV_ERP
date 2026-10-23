import { useEffect, useRef, useState, type ChangeEvent } from "react";
import type { Credentials } from "../../lib/types";
import { workspaceApi, type BackupRecoveryDescriptor, type BackupRecoveryInstallation, type BackupRecoveryState } from "../../lib/workspace-api";
import { useBackupRecoveryLabels } from "./backup-recovery-labels";
import { parseDestinationRequest } from "./destination-request.mjs";
import { recoveryNoticeKey } from "./backup-recovery-notices.mjs";

type Props = { credentials: Credentials; ticketId: string; companyId: string; canManage: boolean; blocked: boolean };
const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;

export function TicketBackupRecoveryPanel({ credentials, ticketId, companyId, canManage, blocked }: Props) {
  const f = useBackupRecoveryLabels();
  const [installations, setInstallations] = useState<BackupRecoveryInstallation[]>([]);
  const [installationId, setInstallationId] = useState("");
  const [backups, setBackups] = useState<BackupRecoveryDescriptor[]>([]);
  const [backupId, setBackupId] = useState("");
  const [requestKey, setRequestKey] = useState("");
  const [fingerprint, setFingerprint] = useState("");
  const [note, setNote] = useState("");
  const [recoveryIdInput, setRecoveryIdInput] = useState("");
  const [state, setState] = useState<BackupRecoveryState | null>(null);
  const [sourceRetired, setSourceRetired] = useState(false);
  const [externalReview, setExternalReview] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const createRequestId = useRef(crypto.randomUUID());

  useEffect(() => {
    if (!canManage) return;
    let active = true;
    void workspaceApi.recoveryInstallations(credentials, ticketId).then(items => {
      if (active) setInstallations(items);
    }).catch(() => { if (active) setError(f("loadInstallationsError")); });
    return () => { active = false; };
  }, [credentials, ticketId, companyId, canManage, f]);

  useEffect(() => {
    setBackups([]); setBackupId("");
    if (!canManage || !uuid.test(installationId)) return;
    let active = true;
    void workspaceApi.recoveryBackups(credentials, ticketId, installationId).then(items => {
      if (active) setBackups(items.slice(0, 3));
    }).catch(() => { if (active) setError(f("loadBackupsError")); });
    return () => { active = false; };
  }, [credentials, ticketId, installationId, canManage, f]);

  async function importRequest(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    try {
      if (file.name.toLowerCase() !== "destination-request.json" || file.size > 32768) throw new Error("request");
      const value = parseDestinationRequest(JSON.parse(await file.text()));
      if (!value) throw new Error("request");
      setRequestKey(value.destinationPublicKey);
      setFingerprint(value.destinationKeyFingerprint);
      setState(null); setRecoveryIdInput(""); setSourceRetired(false); setExternalReview(false);
      createRequestId.current = crypto.randomUUID();
      setError("");
    } catch { setRequestKey(""); setFingerprint(""); setError(f("invalidRequest")); }
  }

  async function run(task: () => Promise<void>) {
    if (busy || blocked) return;
    setBusy(true); setError("");
    try { await task(); }
    catch { setError(f("operationError")); }
    finally { setBusy(false); }
  }

  async function refreshRecovery() {
    const id = recoveryIdInput.trim();
    if (!uuid.test(id)) { setError(f("invalidRecoveryId")); return; }
    if (state?.recoveryId !== id) { setSourceRetired(false); setExternalReview(false); }
    await run(async () => { const next = await workspaceApi.backupRecovery(credentials, ticketId, id);
      setState(next); setRecoveryIdInput(next.recoveryId); });
  }

  async function download(kind: "artifact" | "wrapped-key") {
    if (!state || busy || blocked) return;
    await run(async () => {
      const blob = await workspaceApi.downloadBackupRecovery(credentials, ticketId, state.recoveryId, kind);
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = kind === "artifact" ? `${state.backupId}.tpvb` : "tpv-backup-recovery.key";
      document.body.append(link);
      link.click(); link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
  }

  if (!canManage) return null;
  const selected = backups.find(item => item.backupId === backupId);
  const expired = !!state && Number.isFinite(Date.parse(state.expiresAt)) && Date.parse(state.expiresAt) <= Date.now();
  const canApprove = state?.state === "SUPPORT_REVIEW" && !expired && state.blockingReasons.length === 0
    && sourceRetired && externalReview && !!note.trim();
  return <section className="support-backup-recovery" aria-label={f("title")}>
    <h4>{f("title")}</h4><p>{f("scope")}</p>
    {error && <p role="alert" className="support-technical-pending">{error}</p>}
    <label>{f("installation")}<select className="control-input" value={installationId} disabled={busy || blocked}
      onChange={event => { setInstallationId(event.target.value); setBackups([]); setBackupId(""); setState(null);
        setRecoveryIdInput(""); setSourceRetired(false); setExternalReview(false);
        createRequestId.current = crypto.randomUUID(); }}>
      <option value="">{f("chooseInstallation")}</option>
      {installations.map(item => <option key={item.installationId} value={item.installationId}>
        {item.reference} · {item.storeName}
      </option>)}
    </select></label>
    {uuid.test(installationId) && <div className="support-backup-recovery-list"><strong>{f("availableBackups")}</strong>
      {backups.length === 0 && <p>{f("noBackups")}</p>}
      {backups.map(item => <label key={item.backupId}><input type="radio" name={`backup-${ticketId}`} value={item.backupId}
        checked={backupId === item.backupId} disabled={busy || blocked} onChange={() => {
          setBackupId(item.backupId); setState(null); setRecoveryIdInput("");
          setSourceRetired(false); setExternalReview(false); createRequestId.current = crypto.randomUUID();
        }} />
        #{item.sequenceNumber} · {new Date(item.capturedAt).toLocaleString()} · {(item.sizeBytes / 1048576).toFixed(1)} MB
      </label>)}
    </div>}
    {selected && <p>{f("selectedBackup")} #{selected.sequenceNumber} · {selected.applicationVersion}</p>}
    <label>{f("destinationRequest")}<input type="file" accept=".json,application/json" disabled={busy || blocked}
      onChange={event => void importRequest(event)} /></label>
    {requestKey && <p role="status">{f("requestLoaded")}{fingerprint ? ` · ${fingerprint}` : ""}</p>}
    <details><summary>{f("technicalDetails")}</summary>
      <label>{f("destinationPublicKey")}<textarea readOnly value={requestKey} /></label>
    </details>
    <label>{f("note")}<textarea className="control-input" value={note} maxLength={4000} disabled={busy || blocked}
      onChange={event => setNote(event.target.value)} /></label>
    <div className="ticket-actions"><button type="button" disabled={busy || blocked || !selected || !requestKey || !note.trim()}
      onClick={() => void run(async () => {
        const next = await workspaceApi.createBackupRecovery(credentials, ticketId, {
          requestId: createRequestId.current, installationId, backupId: selected!.backupId,
          destinationPublicKey: requestKey, note: note.trim()
        });
        setState(next); setRecoveryIdInput(next.recoveryId); setSourceRetired(false); setExternalReview(false);
        createRequestId.current = crypto.randomUUID();
      })}>{f("authorizeDownload")}</button></div>
    <label>{f("recoveryId")}<input className="control-input" value={recoveryIdInput} disabled={busy || blocked}
      onChange={event => {
        const nextId = event.target.value;
        setRecoveryIdInput(nextId);
        if (nextId.trim() !== state?.recoveryId) {
          setState(null); setSourceRetired(false); setExternalReview(false);
        }
      }} /></label>
    <button type="button" disabled={busy || blocked || !uuid.test(recoveryIdInput)} onClick={() => void refreshRecovery()}>{f("refreshRecovery")}</button>
    {state && <div className="support-backup-recovery-state"><p><strong>{f("state")}: {f(`state.${state.state}`)}</strong></p>
      <p>{f("recoveryId")}: {state.recoveryId}</p>
      <p>{f("authorizedBackup")}: {state.backupDescriptor ? `#${state.backupDescriptor.sequenceNumber} · ` : ""}{state.backupId}</p>
      <p>{f("sequenceFloor")}: {state.sequenceFloor}</p>
      <p>{f("expiresAt")}: {new Date(state.expiresAt).toLocaleString()}</p>
      {expired && <p className="support-technical-pending" role="alert">{f("expired")}</p>}
      {(state.warnings ?? []).length > 0 && <div className="support-technical-pending"><ul>
        {state.warnings.map(warning => <li key={warning}>{f(recoveryNoticeKey(warning, "warning"))}</li>)}
      </ul></div>}
      {state.blockingReasons.length > 0 && <div className="support-technical-pending" role="alert">
        <strong>{f("blockedReview")}</strong><ul>
          {state.blockingReasons.map(reason => <li key={reason}>{f(recoveryNoticeKey(reason, "blocker"))}</li>)}
        </ul>
      </div>}
      <p>{f("noCentralRebuild")}</p>
      {state.state !== "CANCELLED" && <div className="ticket-actions">
        <button type="button" disabled={busy || blocked} onClick={() => void download("artifact")}>{f("downloadArtifact")}</button>
        <button type="button" disabled={busy || blocked} onClick={() => void download("wrapped-key")}>{f("downloadKey")}</button>
      </div>}
      {state.state === "SUPPORT_REVIEW" && <label><input type="checkbox" checked={sourceRetired} disabled={busy || blocked}
        onChange={event => setSourceRetired(event.target.checked)} />{f("sourceRetired")}</label>}
      {state.state === "SUPPORT_REVIEW" && <label><input type="checkbox" checked={externalReview} disabled={busy || blocked}
        onChange={event => setExternalReview(event.target.checked)} />{f("externalReviewConfirmed")}</label>}
      {state.state === "SUPPORT_REVIEW" && <button type="button" disabled={busy || blocked || !canApprove}
        onClick={() => void run(async () => setState(await workspaceApi.approveBackupRecovery(credentials, ticketId,
          state.recoveryId, { expectedVersion: state.version, note: note.trim(), sourceRetiredConfirmed: true })))}>{f("approve")}</button>}
      {expired && !(["ACTIVATED", "CANCELLED"] as string[]).includes(state.state) && <button type="button"
        disabled={busy || blocked || !note.trim()} onClick={() => void run(async () => {
          const next = await workspaceApi.renewBackupRecovery(credentials, ticketId, state.recoveryId,
            { expectedVersion: state.version, note: note.trim(), sourceRetiredConfirmed: false });
          setState(next); setSourceRetired(false); setExternalReview(false);
        })}>{f("renew")}</button>}
      {!(["ACTIVATED", "CANCELLED"] as string[]).includes(state.state) && <button type="button" disabled={busy || blocked || !note.trim()}
        onClick={() => void run(async () => setState(await workspaceApi.cancelBackupRecovery(credentials, ticketId,
          state.recoveryId, { expectedVersion: state.version, note: note.trim() })))}>{f("cancel")}</button>}
    </div>}
  </section>;
}
