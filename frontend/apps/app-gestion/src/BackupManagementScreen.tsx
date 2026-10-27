import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import type { LocaleCode, UserSession } from "@tpverp/app-common";
import { WindowCloseButton } from "../../../packages/app-common/src/components/WindowCloseButton";
import {
  loadBackupConfiguration, loadBackupDetail, loadBackupHistory, retryBackupCloudUpload, runBackup, saveBackupConfiguration,
  type BackupConfiguration, type BackupHistory, type BackupItem
} from "./backupApi";
import "./BackupManagementScreen.css";

type Props = { session: UserSession; locale: LocaleCode; t: (key: string) => string; onTransferServer?: () => void };
const initialConfig: BackupConfiguration = { time: "03:00", dailyRetention: 30, monthlyRetention: 72, active: true, directory: "", configured: false };

export function BackupManagementScreen({ session, locale, t, onTransferServer }: Props) {
  const allowed = session.permissions.includes("ADMIN") || session.permissions.includes("BACKUPS_MANAGE");
  const [history, setHistory] = useState<BackupHistory | null>(null);
  const [config, setConfig] = useState<BackupConfiguration>(initialConfig);
  const [page, setPage] = useState(0);
  const pageRef = useRef(page);
  const [selected, setSelected] = useState<BackupItem | null>(null);
  const detailDialog = useRef<HTMLDialogElement>(null);
  const pollInFlight = useRef(false);
  const firstLoad = useRef(true);
  const [pendingRunId, setPendingRunId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [retryingCloudId, setRetryingCloudId] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [showConfig, setShowConfig] = useState(false);
  const [adminPassword, setAdminPassword] = useState("");
  const [recoverySecret, setRecoverySecret] = useState("");

  const refresh = useCallback(async () => {
    if (!allowed) return;
    if (firstLoad.current) setLoading(true);
    setError("");
    try {
      const [nextHistory, nextConfig] = await Promise.all([
        loadBackupHistory(page, session.accessToken), loadBackupConfiguration(session.accessToken)
      ]);
      setHistory(nextHistory);
      setConfig(nextConfig);
    } catch { setError(t("gestion.backups.loadError")); }
    finally { firstLoad.current = false; setLoading(false); }
  }, [allowed, page, session.accessToken, t]);

  pageRef.current = page;
  useEffect(() => { void refresh(); }, [refresh]);
  useEffect(() => { if (selected && !detailDialog.current?.open) detailDialog.current?.showModal(); }, [selected]);

  const pollRunning = useCallback(async () => {
    if (!allowed || pollInFlight.current) return;
    pollInFlight.current = true;
    try {
      const nextHistory = await loadBackupHistory(page, session.accessToken);
      if (pageRef.current === page) setHistory(nextHistory);
      const ids = new Set<string>();
      if (selected?.result === "EN_CURSO" || selected?.cloud?.state === "PENDING" || selected?.cloud?.state === "UPLOADING") ids.add(selected.id);
      if (pendingRunId) ids.add(pendingRunId);
      for (const id of ids) {
        const detail = await loadBackupDetail(id, session.accessToken);
        setSelected(current => current?.id === id ? detail : current);
        if (detail.result !== "EN_CURSO") setPendingRunId(current => current === id ? null : current);
      }
    } catch { /* Keep the current rows visible and retry on the next interval. */ }
    finally { pollInFlight.current = false; }
  }, [allowed, page, pendingRunId, selected?.id, selected?.result, selected?.cloud?.state, session.accessToken]);

  useEffect(() => {
    if (!history?.items.some(item => item.result === "EN_CURSO" || item.cloud?.state === "PENDING" || item.cloud?.state === "UPLOADING")
        && selected?.result !== "EN_CURSO" && selected?.cloud?.state !== "PENDING"
        && selected?.cloud?.state !== "UPLOADING" && !pendingRunId) return;
    const timer = window.setInterval(() => { void pollRunning(); }, 4500);
    return () => window.clearInterval(timer);
  }, [history?.items, selected?.result, selected?.cloud?.state, pendingRunId, pollRunning]);

  async function openDetail(item: BackupItem) {
    setError("");
    try { setSelected(await loadBackupDetail(item.id, session.accessToken)); }
    catch { setError(t("gestion.backups.detailError")); }
  }

  async function retryCloudUpload(item: BackupItem) {
    if (retryingCloudId || !item.available || item.cloud?.state !== "FAILED") return;
    setRetryingCloudId(item.id); setError("");
    try {
      await retryBackupCloudUpload(item.id, session.accessToken);
      const [nextHistory, detail] = await Promise.all([
        loadBackupHistory(page, session.accessToken), loadBackupDetail(item.id, session.accessToken)
      ]);
      setHistory(nextHistory);
      setSelected(current => current?.id === item.id ? detail : current);
    } catch { setError(t("gestion.backups.cloudRetryError")); }
    finally { setRetryingCloudId(null); }
  }

  async function createBackup() {
    setBusy(true); setError(""); setNotice("");
    try {
      const started = await runBackup(session.accessToken);
      setPendingRunId(started.id);
      setPage(0);
      setNotice(t("gestion.backups.startedNotice"));
      setHistory(await loadBackupHistory(0, session.accessToken));
    } catch { setError(t("gestion.backups.runError")); }
    finally { setBusy(false); }
  }

  async function saveConfig(event: FormEvent) {
    event.preventDefault();
    if (config.dailyRetention < 30 || config.monthlyRetention < 72) {
      setError(t("gestion.backups.retentionMinimum")); return;
    }
    if (!adminPassword || !recoverySecret) {
      setError(t("gestion.backups.credentialsRequired")); return;
    }
    setBusy(true); setError(""); setNotice("");
    try {
      const saved = await saveBackupConfiguration({
        time: config.time, dailyRetention: config.dailyRetention,
        monthlyRetention: config.monthlyRetention, active: config.active,
        directory: config.directory.trim(),
        adminPassword, recoverySecret
      }, session.accessToken);
      setConfig(saved); setAdminPassword(""); setRecoverySecret(""); setShowConfig(false);
      setNotice(t("gestion.backups.saved"));
      await refresh();
    } catch { setError(t("gestion.backups.saveError")); }
    finally { setBusy(false); }
  }

  async function openDirectory() {
    setError("");
    const result = await window.tpvDesktop?.backups?.openDirectory(session.accessToken ?? "");
    if (!result?.ok) setError(t(result?.code === "BACKUP_FOLDER_LOCAL_ONLY" || result?.code === "BACKUP_FOLDER_SERVER_ONLY"
      ? "gestion.backups.openDirectoryLocalOnly" : "gestion.backups.openDirectoryError"));
  }

  const date = (value?: string | null) => value ? new Intl.DateTimeFormat(locale, { dateStyle: "short", timeStyle: "medium" }).format(new Date(value)) : "—";
  const day = (value?: string | null) => value ? new Intl.DateTimeFormat(locale, { dateStyle: "short" }).format(new Date(value)) : "—";
  const time = (value?: string | null) => value ? new Intl.DateTimeFormat(locale, { timeStyle: "medium" }).format(new Date(value)) : "—";
  const size = (bytes?: number | null) => bytes == null ? "—" : `${new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(bytes / 1024 / 1024)} MB`;
  const selectedManifest = selected?.metadata?.manifest;
  const durationSeconds = selected?.finishedAt && selected.startedAt
    ? Math.max(0, Math.round((new Date(selected.finishedAt).getTime() - new Date(selected.startedAt).getTime()) / 1000)) : null;
  const duration = durationSeconds == null ? "—" : `${Math.floor(durationSeconds / 60)} ${t("gestion.backups.minutes")} ${durationSeconds % 60} ${t("gestion.backups.seconds")}`;

  if (!allowed) return <section className="gestion-workspace"><p role="alert">{t("gestion.backups.noAccess")}</p></section>;
  return <section className="gestion-workspace gestion-backups-workspace">
    <header className="gestion-dashboard-toolbar gestion-security-header">
      <div><span className="gestion-eyebrow">{t("gestion.security.eyebrow")}</span><h2>{t("gestion.backups.title")}</h2><p>{t("gestion.backups.subtitle")}</p></div>
      <div className="gestion-dashboard-actions">
        <button type="button" onClick={() => void refresh()} disabled={loading || busy}>{t("common.refresh")}</button>
        {session.permissions.includes("ADMIN") && onTransferServer && <button type="button" onClick={onTransferServer}>{t("gestion.transfer.navigation")}</button>}
        <button type="button" onClick={() => setShowConfig(value => !value)}>{t("gestion.backups.configuration")}</button>
        <button type="button" className="primary gestion-backups-create" onClick={() => void createBackup()} disabled={busy || !config.configured}>{t("gestion.backups.create")}</button>
      </div>
    </header>
    {error && <p className="gestion-backups-feedback error" role="alert">{error}</p>}
    {notice && <p className="gestion-backups-feedback" role="status">{notice}</p>}
    {history?.overdue && <p className="gestion-backups-feedback warning" role="status">{t("gestion.backups.overdue")}</p>}
    <div className="gestion-backups-summary">
      <span>{t("gestion.backups.lastSuccessful")}: <strong>{date(history?.lastSuccessfulAt)}</strong></span>
      <span>{t("gestion.backups.nextScheduled")}: <strong>{date(history?.nextScheduledAt)}</strong></span>
      <span>{t("gestion.backups.total")}: <strong>{history?.total ?? "—"}</strong></span>
      <button type="button" disabled={!window.tpvDesktop?.backups || !config.configured} onClick={() => void openDirectory()}>{t("gestion.backups.openDirectory")}</button>
      {!window.tpvDesktop?.backups && <span>{t("gestion.backups.openDirectoryLocalOnly")}</span>}
    </div>
    {showConfig && <form className="gestion-backups-config" onSubmit={(event) => void saveConfig(event)}>
      <label>{t("gestion.backups.time")}<input type="time" required value={config.time?.slice(0, 5) ?? "03:00"} onChange={e => setConfig({ ...config, time: e.target.value })} /></label>
      <label>{t("gestion.backups.directory")}<input required value={config.directory} onChange={e => setConfig({ ...config, directory: e.target.value })} /></label>
      <label>{t("gestion.backups.dailyRetention")}<input type="number" min={30} required value={config.dailyRetention} onChange={e => setConfig({ ...config, dailyRetention: Number(e.target.value) })} /></label>
      <label>{t("gestion.backups.monthlyRetention")}<input type="number" min={72} required value={config.monthlyRetention} onChange={e => setConfig({ ...config, monthlyRetention: Number(e.target.value) })} /></label>
      <label className="gestion-backups-check"><input type="checkbox" checked={config.active} onChange={e => setConfig({ ...config, active: e.target.checked })} />{t("gestion.backups.active")}</label>
      <label>{t("gestion.backups.adminPassword")}<input type="password" autoComplete="off" required value={adminPassword} onChange={e => setAdminPassword(e.target.value)} /></label>
      <label>{t("gestion.backups.recoverySecret")}<input type="password" autoComplete="new-password" required value={recoverySecret} onChange={e => setRecoverySecret(e.target.value)} /></label>
      <p>{t("gestion.backups.recoveryHint")}</p>
      <button type="submit" className="primary" disabled={busy}>{t("common.save")}</button>
    </form>}
    <div className="gestion-backups-history">
      <div className="gestion-backups-history-scroll" tabIndex={0} role="region" aria-label={t("gestion.backups.title")}>
        <table><thead><tr>
          <th>{t("gestion.backups.id")}</th><th>{t("gestion.backups.date")}</th><th>{t("gestion.backups.hour")}</th><th>{t("gestion.backups.size")}</th><th>{t("gestion.backups.result")}</th><th aria-label={t("gestion.backups.detail")}></th>
        </tr></thead><tbody>
          {history?.items.map(item => <tr key={item.id} onDoubleClick={() => void openDetail(item)}>
            <td>{item.sequenceNumber ?? "—"}</td><td>{day(item.metadata?.manifest?.capturedAt ?? item.startedAt)}</td><td>{time(item.metadata?.manifest?.capturedAt ?? item.startedAt)}</td><td>{size(item.sizeBytes)}</td><td>{t(`gestion.backups.result.${item.result}`)}{!item.available && <span className="gestion-backups-unavailable">{t("gestion.backups.unavailable")}</span>}{item.cloud && <span className="gestion-backups-cloud-state">{t(`gestion.backups.cloud.${item.cloud.state}`)}</span>}</td><td><button type="button" onClick={() => void openDetail(item)}>{t("gestion.backups.detail")}</button></td>
          </tr>)}
          {!loading && !history?.items.length && <tr><td colSpan={6}>{t("gestion.backups.empty")}</td></tr>}
          {loading && <tr><td colSpan={6}>{t("common.loading")}</td></tr>}
        </tbody></table>
      </div>
      <footer className="gestion-backups-pager"><span>{t("gestion.backups.page")} {page + 1}</span><button type="button" disabled={page === 0 || loading} onClick={() => setPage(page - 1)}>‹</button><button type="button" disabled={loading || !history || (page + 1) * history.size >= history.total} onClick={() => setPage(page + 1)}>›</button></footer>
    </div>
    {selected && <dialog ref={detailDialog} className="gestion-backups-dialog" onClose={() => setSelected(null)} aria-label={t("gestion.backups.detail")}>
      <div className="gestion-backups-detail">
        <header><h3>{t("gestion.backups.detail")} · {selected.sequenceNumber ?? "—"}</h3><WindowCloseButton aria-label={t("common.close")} title={t("common.close")} onClick={() => detailDialog.current?.close()} /></header>
        <div className="gestion-backups-detail-body">
          <dl><div><dt>{t("gestion.backups.id")}</dt><dd>{selected.sequenceNumber ?? "—"}</dd></div><div><dt>{t("gestion.backups.result")}</dt><dd>{t(`gestion.backups.result.${selected.result}`)}</dd></div><div><dt>{t("gestion.backups.availability")}</dt><dd>{t(selected.available ? "gestion.backups.available" : "gestion.backups.unavailable")}</dd></div><div><dt>{t("gestion.backups.origin")}</dt><dd>{t(`gestion.backups.origin.${selected.origin}`)}</dd></div><div><dt>{t("gestion.backups.phase")}</dt><dd>{selected.phase ? t(`gestion.backups.phase.${selected.phase}`) : "—"}</dd></div><div><dt>{t("gestion.backups.started")}</dt><dd>{date(selected.startedAt)}</dd></div><div><dt>{t("gestion.backups.finished")}</dt><dd>{date(selected.finishedAt)}</dd></div><div><dt>{t("gestion.backups.duration")}</dt><dd>{duration}</dd></div><div><dt>{t("gestion.backups.size")}</dt><dd>{size(selected.sizeBytes)}</dd></div><div><dt>{t("gestion.backups.verified")}</dt><dd>{date(selected.metadata?.verifiedAt)}</dd></div><div><dt>{t("gestion.backups.version")}</dt><dd>{selectedManifest?.applicationVersion || "—"}</dd></div><div><dt>{t("gestion.backups.installation")}</dt><dd>{selectedManifest?.installationId || "—"}</dd></div><div><dt>{t("gestion.backups.captured")}</dt><dd>{date(selectedManifest?.capturedAt)}</dd></div>{selected.cloud && <><div><dt>{t("gestion.backups.cloudStatus")}</dt><dd>{t(`gestion.backups.cloud.${selected.cloud.state}`)}</dd></div><div><dt>{t("gestion.backups.cloudAttempts")}</dt><dd>{selected.cloud.attempts}</dd></div><div><dt>{t("gestion.backups.cloudUploadedAt")}</dt><dd>{date(selected.cloud.uploadedAt)}</dd></div><div><dt>{t("gestion.backups.cloudNextAttempt")}</dt><dd>{date(selected.cloud.nextAttemptAt)}</dd></div></>}</dl>
          {selected.cloud?.state === "FAILED" && <button type="button" disabled={!!retryingCloudId || !selected.available} onClick={() => void retryCloudUpload(selected)}>{t("gestion.backups.cloudRetry")}</button>}
          {selected.cloud?.errorReason && <details><summary>{t("gestion.backups.cloudErrorDetail")}</summary><p>{selected.cloud.errorReason}</p></details>}
          {selected.errorReason && <p className="gestion-backups-feedback error">{selected.errorReason}</p>}
          {selected.result === "EXITO" && selectedManifest?.portableSecretsVersion !== undefined && selectedManifest.portableSecretsVersion >= 1 &&
            selectedManifest.portableSecretsComplete === false &&
            <p className="gestion-backups-feedback warning" role="alert">{t("gestion.backups.portableSecretsIncomplete")}</p>}
          {selected.result === "EXITO" && (!selectedManifest || selectedManifest.portableSecretsVersion == null || selectedManifest.portableSecretsVersion < 1) &&
            <p className="gestion-backups-feedback warning">{t("gestion.backups.legacyRecoveryLimit")}</p>}
          {!selectedManifest && <p>{t("gestion.backups.legacyDetail")}</p>}
          {selectedManifest && <>
            <ManifestTable title={t("gestion.backups.categories")} values={selectedManifest.categories} t={t} prefix="gestion.backups.category" />
            <ManifestTable title={t("gestion.backups.documentsByType")} values={selectedManifest.documentsByType} t={t} prefix="gestion.backups.documentType" />
            <DocumentList title={t("gestion.backups.lastDocuments")} documents={selectedManifest.lastDocuments} t={t} date={date} />
            <DocumentCounters title={t("gestion.backups.documentCounters")} counters={selectedManifest.documentCounters} t={t} />
            <details className="gestion-backups-manifest"><summary>{t("gestion.backups.tableCounts")}</summary><ManifestTable title="" values={selectedManifest.tableCounts} t={t} /></details>
          </>}
        </div>
      </div>
    </dialog>}
  </section>;
}

function ManifestTable({ title, values, t, prefix }: { title: string; values?: Record<string, string | number | null>; t: (key: string) => string; prefix?: string }) {
  return <section className="gestion-backups-manifest">{title && <h4>{title}</h4>}<dl>{Object.entries(values ?? {}).map(([key, value]) => <div key={key}><dt>{prefix ? t(`${prefix}.${key}`) : key}</dt><dd>{value ?? "—"}</dd></div>)}</dl></section>;
}

function DocumentList({ title, documents, t, date }: { title: string; documents?: NonNullable<import("./backupApi").BackupManifest["lastDocuments"]>; t: (key: string) => string; date: (value?: string | null) => string }) {
  return <section className="gestion-backups-manifest"><h4>{title}</h4>
    <div className="gestion-backups-table-scroll" tabIndex={0} role="region" aria-label={title}><table><thead><tr><th>{t("gestion.backups.documentType")}</th><th>{t("gestion.backups.documentNumber")}</th><th>{t("gestion.backups.documentDate")}</th></tr></thead><tbody>
      {(documents ?? []).map((document, index) => <tr key={`${document.documentId ?? index}-${document.type}`}><td>{t(`gestion.backups.documentType.${document.type}`)}{document.storeCode ? ` · ${document.storeCode}` : ""}</td><td>{document.number || "—"}</td><td>{date(document.confirmedAt ?? document.date)}</td></tr>)}
    </tbody></table></div>
  </section>;
}

function DocumentCounters({ title, counters, t }: { title: string; counters?: Array<{ storeId: string; type: string; period: string; lastSequence: number }>; t: (key: string) => string }) {
  return <section className="gestion-backups-manifest"><h4>{title}</h4>
    <div className="gestion-backups-table-scroll" tabIndex={0} role="region" aria-label={title}><table><thead><tr><th>{t("gestion.backups.counterStore")}</th><th>{t("gestion.backups.documentType")}</th><th>{t("gestion.backups.counterPeriod")}</th><th>{t("gestion.backups.counterSequence")}</th></tr></thead><tbody>
      {(counters ?? []).map((counter, index) => <tr key={`${counter.storeId}-${counter.type}-${counter.period}-${index}`}><td>{counter.storeId}</td><td>{t(`gestion.backups.documentType.${counter.type}`)}</td><td>{counter.period}</td><td>{counter.lastSequence}</td></tr>)}
    </tbody></table></div>
  </section>;
}
