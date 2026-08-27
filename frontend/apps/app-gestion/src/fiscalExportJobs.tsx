import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  createFiscalExportJob,
  createFiscalRequiredSubmissionExportJob,
  downloadFiscalExportJob,
  loadFiscalExportJobs,
  retryFiscalExportJob,
  type FiscalExportJob,
  type FiscalExportJobRequest,
  type FiscalExportJobStatus,
  type FiscalExportKind,
  type FiscalExportJobScope
} from "./verifactuManagementApi";
import { downloadFiscalExportBlob } from "./fiscalExportDownload";
import type { VerifactuTranslator } from "./verifactuPresentation";

const ACTIVE_JOB_STATUSES: readonly FiscalExportJobStatus[] = ["QUEUED", "RUNNING"];

export type FiscalExportJobEntry = FiscalExportJob & {
  request?: FiscalExportJobRequest;
};

function delayWithSignal(delay: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const finish = () => {
      signal.removeEventListener("abort", abort);
      resolve();
    };
    const timeout = window.setTimeout(finish, delay);
    const abort = () => {
      window.clearTimeout(timeout);
      signal.removeEventListener("abort", abort);
      reject(new DOMException("Aborted", "AbortError"));
    };
    signal.addEventListener("abort", abort, { once: true });
  });
}

export function isFiscalExportJobActive(job: FiscalExportJob) {
  return ACTIVE_JOB_STATUSES.includes(job.status);
}

export function fiscalExportJobKindLabel(kind: FiscalExportKind | undefined, t: VerifactuTranslator) {
  return kind === "EVENTS"
    ? t("verifactu.exportJobs.kindEvents")
    : kind === "BILLING"
      ? t("verifactu.exportJobs.kindBilling")
      : t("verifactu.exportJobs.kindUnknown");
}

export function fiscalExportJobStatusLabel(status: FiscalExportJobStatus, t: VerifactuTranslator) {
  const labels: Record<FiscalExportJobStatus, string> = {
    QUEUED: "verifactu.exportJobs.statusQueued",
    RUNNING: "verifactu.exportJobs.statusRunning",
    COMPLETED: "verifactu.exportJobs.statusCompleted",
    FAILED: "verifactu.exportJobs.statusFailed",
    EXPIRED: "verifactu.exportJobs.statusExpired"
  };
  return t(labels[status]);
}

function fiscalExportJobScopeLabel(scope: FiscalExportJobScope | undefined, t: VerifactuTranslator) {
  const labels: Partial<Record<FiscalExportJobScope, string>> = {
    PERIOD: "verifactu.exportJobs.scopePeriod",
    FILTERED: "verifactu.exportJobs.scopeFiltered",
    SELECTED: "verifactu.exportJobs.scopeSelected",
    CURRENT: "verifactu.exportJobs.scopeCurrent"
  };
  return scope && labels[scope] ? t(labels[scope] as string) : null;
}

export function useFiscalExportJobs(token?: string, canManage = false) {
  const [jobs, setJobs] = useState<FiscalExportJobEntry[]>([]);
  const [loading, setLoading] = useState(canManage);
  const [error, setError] = useState(false);
  const [creating, setCreating] = useState(false);
  const [retryingId, setRetryingId] = useState<string | null>(null);
  const [downloadId, setDownloadId] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState(false);
  const listController = useRef<AbortController | null>(null);
  const createController = useRef<AbortController | null>(null);
  const downloadController = useRef<AbortController | null>(null);
  const retryController = useRef<AbortController | null>(null);
  const createRequestId = useRef(0);
  const retryRequestId = useRef(0);
  const downloadRequestId = useRef(0);

  const refresh = useCallback(async () => {
    if (!canManage) return;
    listController.current?.abort();
    const controller = new AbortController();
    listController.current = controller;
    setLoading(true);
    setError(false);
    try {
      const page = await loadFiscalExportJobs(token, 0, 20, controller.signal);
      if (controller.signal.aborted) return;
      setJobs(page.content.map((job) => ({ ...job, request: job.request })));
    } catch {
      if (!controller.signal.aborted) setError(true);
    } finally {
      if (!controller.signal.aborted) setLoading(false);
    }
  }, [canManage, token]);

  useEffect(() => {
    if (!canManage) {
      setJobs([]);
      setLoading(false);
      return;
    }
    void refresh();
    return () => listController.current?.abort();
  }, [canManage, refresh]);

  useEffect(() => () => {
    listController.current?.abort();
    createController.current?.abort();
    downloadController.current?.abort();
    retryController.current?.abort();
  }, []);

  const activeJobIds = useMemo(
    () => jobs.filter(isFiscalExportJobActive).map((job) => job.id).join(","),
    [jobs]
  );

  useEffect(() => {
    if (!canManage || !activeJobIds) return;
    const controller = new AbortController();
    let wait = 1000;
    const poll = async () => {
      while (!controller.signal.aborted) {
        try {
          await delayWithSignal(wait, controller.signal);
          const page = await loadFiscalExportJobs(token, 0, 20, controller.signal);
          if (controller.signal.aborted) return;
          setJobs((current) => page.content.map((job) => ({ ...job, request: job.request ?? current.find((item) => item.id === job.id)?.request })));
          wait = Math.min(10000, Math.round(wait * 1.5));
        } catch {
          if (controller.signal.aborted) return;
          wait = Math.min(10000, Math.round(wait * 1.5));
        }
      }
    };
    void poll();
    return () => controller.abort();
  }, [activeJobIds, canManage, token]);

  const create = useCallback(async (request: FiscalExportJobRequest) => {
    if (!canManage || creating) return null;
    createController.current?.abort();
    const controller = new AbortController();
    createController.current = controller;
    const requestId = ++createRequestId.current;
    setCreating(true);
    try {
      const job = await createFiscalExportJob(request, token, controller.signal);
      if (controller.signal.aborted) return null;
      setJobs((current) => [{ ...job, request }, ...current.filter((item) => item.id !== job.id)]);
      return job;
    } catch {
      return null;
    } finally {
      if (requestId === createRequestId.current) setCreating(false);
    }
  }, [canManage, creating, token]);

  const createRequiredSubmissionJob = useCallback(async (
    requirementId: string,
    periodStart: string,
    periodEnd: string
  ) => {
    if (!canManage || creating) return null;
    createController.current?.abort();
    const controller = new AbortController();
    createController.current = controller;
    const requestId = ++createRequestId.current;
    setCreating(true);
    try {
      const job = await createFiscalRequiredSubmissionExportJob(
        requirementId, periodStart, periodEnd, token, controller.signal
      );
      if (controller.signal.aborted) return null;
      setJobs((current) => [{ ...job, requiredSubmissionId: requirementId }, ...current.filter((item) => item.id !== job.id)]);
      return job;
    } catch {
      return null;
    } finally {
      if (requestId === createRequestId.current) setCreating(false);
    }
  }, [canManage, creating, token]);

  const retry = useCallback(async (id: string) => {
    if (!canManage || retryingId) return null;
    retryController.current?.abort();
    const controller = new AbortController();
    retryController.current = controller;
    const requestId = ++retryRequestId.current;
    setRetryingId(id);
    try {
      const job = await retryFiscalExportJob(id, token, controller.signal);
      if (controller.signal.aborted) return null;
      const request = jobs.find((item) => item.id === id)?.request;
      setJobs((current) => [{ ...job, request }, ...current.filter((item) => item.id !== job.id)]);
      return job;
    } catch {
      return null;
    } finally {
      if (requestId === retryRequestId.current) setRetryingId(null);
    }
  }, [canManage, jobs, retryingId, token]);

  const download = useCallback(async (job: FiscalExportJobEntry) => {
    if (!canManage || job.status !== "COMPLETED" || !job.downloadAvailable || downloadId) return false;
    downloadController.current?.abort();
    const controller = new AbortController();
    downloadController.current = controller;
    const requestId = ++downloadRequestId.current;
    setDownloadId(job.id);
    setDownloadError(false);
    try {
      const blob = await downloadFiscalExportJob(job.id, token, controller.signal);
      if (controller.signal.aborted) return false;
      const kind = job.kind ?? job.request?.kind ?? "BILLING";
      downloadFiscalExportBlob(blob, `exportacion-fiscal-${kind.toLowerCase()}-${job.id}.zip`);
      return true;
    } catch {
      if (!controller.signal.aborted) setDownloadError(true);
      return false;
    } finally {
      if (requestId === downloadRequestId.current) setDownloadId(null);
    }
  }, [canManage, downloadId, token]);

  return { jobs, loading, error, creating, retryingId, downloadId, downloadError, refresh, create, createRequiredSubmissionJob, retry, download };
}

export function FiscalExportJobsList({
  jobs,
  loading,
  error,
  downloadId,
  downloadError,
  onRefresh,
  onRetry,
  onDownload,
  t
}: {
  jobs: FiscalExportJobEntry[];
  loading: boolean;
  error: boolean;
  downloadId: string | null;
  downloadError: boolean;
  onRefresh: () => void;
  onRetry: (id: string) => void;
  onDownload: (job: FiscalExportJobEntry) => void;
  t: VerifactuTranslator;
}) {
  const currentJobId = jobs.find(isFiscalExportJobActive)?.id;
  return <section className="gestion-fiscal-export-jobs" aria-label={t("verifactu.exportJobs.recentTitle")}>
    <header><h3>{t("verifactu.exportJobs.recentTitle")}</h3><button type="button" onClick={onRefresh} disabled={loading}>{t("verifactu.exportJobs.refresh")}</button></header>
    {loading && <p className="gestion-verifactu-message" role="status">{t("verifactu.exportJobs.loading")}</p>}
    {error && <p className="gestion-verifactu-message error" role="alert">{t("verifactu.exportJobs.error")} <button type="button" onClick={onRefresh}>{t("verifactu.exportJobs.retry")}</button></p>}
    {downloadError && <p className="gestion-verifactu-message error" role="alert">{t("verifactu.exportJobs.downloadError")}</p>}
    {!loading && !error && jobs.length === 0 && <p className="gestion-verifactu-message">{t("verifactu.exportJobs.empty")}</p>}
    {!loading && !error && jobs.length > 0 && <ul>
      {jobs.map((job) => <li key={job.id}>
        <div><strong>{fiscalExportJobKindLabel(job.kind ?? job.request?.kind, t)}</strong>{fiscalExportJobScopeLabel(job.scope, t) && <span>{fiscalExportJobScopeLabel(job.scope, t)}</span>}{job.id === currentJobId && <span>{t("verifactu.exportJobs.current")}</span>}<span>{fiscalExportJobStatusLabel(job.status, t)}</span></div>
        <span>{job.processed} {t("verifactu.exportJobs.processed")}</span>
        <span>{formatJobDate(job.createdAt)}{job.fileSize > 0 ? ` · ${formatFileSize(job.fileSize)}` : ""}</span>
        {job.status === "FAILED" && <span>{t("verifactu.exportJobs.failedHint")}</span>}
        {job.status === "EXPIRED" && <span>{t("verifactu.exportJobs.expiredHint")}</span>}
        <div>
          {job.status === "COMPLETED" && job.downloadAvailable && <button type="button" disabled={downloadId === job.id || downloadId !== null} onClick={() => onDownload(job)}>{downloadId === job.id ? t("verifactu.exportJobs.downloading") : t("verifactu.exportJobs.download")}</button>}
          {(job.status === "FAILED" || job.status === "EXPIRED") && <button type="button" onClick={() => onRetry(job.id)}>{t("verifactu.exportJobs.retryRequest")}</button>}
        </div>
      </li>)}
    </ul>}
  </section>;
}

function formatJobDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : new Intl.DateTimeFormat(undefined, { dateStyle: "short", timeStyle: "short" }).format(date);
}

function formatFileSize(value: number) {
  if (!Number.isFinite(value) || value < 0) return "";
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${Math.round(value / 1024)} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}
