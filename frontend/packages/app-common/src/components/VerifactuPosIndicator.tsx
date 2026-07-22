import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  getVerifactuPosQueue,
  getVerifactuPosStatus,
  type VerifactuPosPresentationStatus,
  type VerifactuPosQueueItem,
  type VerifactuPosStatus,
  type VerifactuSubmissionStatus
} from "../api/verifactuPos";
import type { LocaleCode } from "../types";
import { useOutsidePointerDown } from "./useOutsidePointerDown";
import "./VerifactuPosIndicator.css";

export type VerifactuPosTranslator = (key: string) => string;

type VerifactuPosIndicatorProps = {
  token: string;
  locale: LocaleCode;
  t: VerifactuPosTranslator;
  refreshSignal?: number;
  pollIntervalMs?: number;
};

const statusKeys: Record<VerifactuPosPresentationStatus, string> = {
  INACTIVO: "verifactu.pos.presentation.inactive",
  OPERATIVO: "verifactu.pos.presentation.operational",
  PENDIENTES: "verifactu.pos.presentation.pending",
  ENVIANDO: "verifactu.pos.presentation.sending",
  REQUIERE_REVISION: "verifactu.pos.presentation.reviewRequired",
  DESCONOCIDO: "verifactu.pos.presentation.unknown"
};

const queueStatusKeys: Record<VerifactuSubmissionStatus, string> = {
  PENDIENTE: "verifactu.pos.queueStatus.pending",
  ENVIANDO: "verifactu.pos.queueStatus.sending",
  ENVIADO: "verifactu.pos.queueStatus.sent",
  RECHAZADO: "verifactu.pos.queueStatus.rejected",
  DEFECTUOSO: "verifactu.pos.queueStatus.defective",
  ACEPTADO_CON_ERRORES: "verifactu.pos.queueStatus.acceptedWithErrors"
};

function documentIsVisible() {
  return typeof document === "undefined" || document.visibilityState !== "hidden";
}

export function VerifactuPosIndicator({
  token,
  locale,
  t,
  refreshSignal,
  pollIntervalMs = 60_000
}: VerifactuPosIndicatorProps) {
  const [open, setOpen] = useState(false);
  const [status, setStatus] = useState<VerifactuPosStatus | null>(null);
  const [queue, setQueue] = useState<VerifactuPosQueueItem[]>([]);
  const [statusLoading, setStatusLoading] = useState(false);
  const [queueLoading, setQueueLoading] = useState(false);
  const [statusError, setStatusError] = useState(false);
  const [queueError, setQueueError] = useState(false);
  const [visible, setVisible] = useState(documentIsVisible);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const closeRef = useRef<HTMLButtonElement | null>(null);
  const mountedRef = useRef(true);
  const openRef = useRef(open);
  const requestSequenceRef = useRef(0);
  const activeRequestRef = useRef<number | null>(null);
  const previousRefreshSignalRef = useRef(refreshSignal);
  const previousOpenRef = useRef(open);

  openRef.current = open;

  const load = useCallback(async (includeQueue: boolean, skipIfBusy = false) => {
    if (skipIfBusy && activeRequestRef.current !== null) return;
    if (!token) {
      setStatus(null);
      setQueue([]);
      setStatusError(true);
      if (includeQueue) setQueueError(true);
      return;
    }
    const sequence = ++requestSequenceRef.current;
    activeRequestRef.current = sequence;
    setStatusLoading(true);
    setStatusError(false);
    if (includeQueue) {
      setQueueLoading(true);
      setQueueError(false);
    }

    const [statusResult, queueResult] = await Promise.allSettled([
      getVerifactuPosStatus(token),
      includeQueue ? getVerifactuPosQueue(token) : Promise.resolve(null)
    ]);
    if (!mountedRef.current || sequence !== requestSequenceRef.current) {
      if (activeRequestRef.current === sequence) activeRequestRef.current = null;
      return;
    }

    if (statusResult.status === "fulfilled") {
      setStatus(statusResult.value);
    } else {
      setStatus(null);
      setStatusError(true);
    }
    setStatusLoading(false);

    if (includeQueue) {
      if (queueResult.status === "fulfilled" && queueResult.value) {
        setQueue(queueResult.value);
      } else {
        setQueue([]);
        setQueueError(true);
      }
      setQueueLoading(false);
    }
    if (activeRequestRef.current === sequence) activeRequestRef.current = null;
  }, [token]);

  useEffect(() => {
    mountedRef.current = true;
    requestSequenceRef.current += 1;
    activeRequestRef.current = null;
    setStatus(null);
    setQueue([]);
    setStatusError(false);
    setQueueError(false);
    setStatusLoading(false);
    setQueueLoading(false);
    void load(openRef.current);
    return () => {
      mountedRef.current = false;
      requestSequenceRef.current += 1;
      activeRequestRef.current = null;
    };
  }, [load]);

  useEffect(() => {
    const becameOpen = open && !previousOpenRef.current;
    previousOpenRef.current = open;
    if (becameOpen) void load(true);
  }, [load, open]);

  useEffect(() => {
    if (Object.is(previousRefreshSignalRef.current, refreshSignal)) return;
    previousRefreshSignalRef.current = refreshSignal;
    void load(openRef.current);
  }, [load, refreshSignal]);

  useEffect(() => {
    function handleVisibilityChange() {
      const nextVisible = documentIsVisible();
      setVisible(nextVisible);
      if (nextVisible) void load(openRef.current, true);
    }
    document.addEventListener("visibilitychange", handleVisibilityChange);
    return () => document.removeEventListener("visibilitychange", handleVisibilityChange);
  }, [load]);

  useEffect(() => {
    if (!visible || pollIntervalMs <= 0) return;
    const interval = window.setInterval(() => {
      void load(openRef.current, true);
    }, pollIntervalMs);
    return () => window.clearInterval(interval);
  }, [load, pollIntervalMs, visible]);

  useEffect(() => {
    if (!open) return;
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      event.preventDefault();
      setOpen(false);
    }
    window.addEventListener("keydown", closeOnEscape);
    closeRef.current?.focus();
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [open]);

  const wasOpenRef = useRef(false);
  useEffect(() => {
    if (wasOpenRef.current && !open) triggerRef.current?.focus();
    wasOpenRef.current = open;
  }, [open]);

  useOutsidePointerDown(open, containerRef, () => setOpen(false));

  const presentationStatus = status?.presentationStatus ?? "DESCONOCIDO";
  const attentionCount = status
    ? status.pendingCount + status.sendingCount + status.reviewRequiredCount
    : 0;
  const dateFormatter = useMemo(() => new Intl.DateTimeFormat(
    locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES",
    { dateStyle: "short", timeStyle: "short" }
  ), [locale]);

  return (
    <div className="verifactu-pos" ref={containerRef}>
      <button
        ref={triggerRef}
        type="button"
        className={`verifactu-pos__trigger verifactu-pos__trigger--${presentationStatus.toLowerCase()}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls="verifactu-pos-panel"
        aria-busy={statusLoading}
        onClick={() => setOpen((current) => !current)}
      >
        <span className="verifactu-pos__signal" aria-hidden="true" />
        <span className="verifactu-pos__trigger-copy">
          <strong>VERI*FACTU</strong>
          <span>{statusError ? t("verifactu.pos.loadError") : t(statusKeys[presentationStatus])}</span>
        </span>
        {attentionCount > 0 && (
          <span className="verifactu-pos__count" aria-label={`${attentionCount} ${t("verifactu.pos.attentionCount")}`}>
            {attentionCount}
          </span>
        )}
      </button>

      {open && (
        <section
          id="verifactu-pos-panel"
          className="verifactu-pos__panel"
          role="dialog"
          aria-modal="false"
          aria-labelledby="verifactu-pos-title"
          aria-describedby="verifactu-pos-description"
          aria-busy={statusLoading || queueLoading}
        >
          <header className="verifactu-pos__header">
            <div>
              <p className="verifactu-pos__eyebrow">{t("verifactu.pos.terminalQueue")}</p>
              <h2 id="verifactu-pos-title">VERI*FACTU</h2>
              <p id="verifactu-pos-description">{t("verifactu.pos.readOnlyDescription")}</p>
            </div>
            <button
              ref={closeRef}
              type="button"
              className="verifactu-pos__close"
              aria-label={t("verifactu.pos.close")}
              onClick={() => setOpen(false)}
            >
              ×
            </button>
          </header>

          <div className={`verifactu-pos__summary verifactu-pos__summary--${presentationStatus.toLowerCase()}`}>
            <span className="verifactu-pos__summary-mark" aria-hidden="true" />
            <div>
              <span>{t("verifactu.pos.currentStatus")}</span>
              <strong>{statusError ? t("verifactu.pos.loadError") : t(statusKeys[presentationStatus])}</strong>
            </div>
            <button
              type="button"
              className="verifactu-pos__refresh"
              disabled={statusLoading || queueLoading}
              onClick={() => void load(true)}
            >
              {t("verifactu.pos.refresh")}
            </button>
          </div>

          {queueError && (
            <p className="verifactu-pos__notice verifactu-pos__notice--error" role="alert">
              {t("verifactu.pos.queueLoadError")}
            </p>
          )}
          {queueLoading && queue.length === 0 && (
            <p className="verifactu-pos__notice" role="status">
              {t("verifactu.pos.loadingQueue")}
            </p>
          )}
          {!queueLoading && !queueError && queue.length === 0 && (
            <p className="verifactu-pos__notice">{t("verifactu.pos.emptyQueue")}</p>
          )}

          {queue.length > 0 && (
            <div className="verifactu-pos__table-wrap">
              <table className="verifactu-pos__table">
                <thead>
                  <tr>
                    <th>{t("verifactu.pos.document")}</th>
                    <th>{t("verifactu.pos.updatedAt")}</th>
                    <th>{t("verifactu.pos.status")}</th>
                  </tr>
                </thead>
                <tbody>
                  {queue.map((item) => (
                    <tr key={`${item.documentType}:${item.documentNumber}:${item.updatedAt}`}>
                      <td>
                        <strong>{item.documentNumber}</strong>
                        <span>{item.documentType}</span>
                      </td>
                      <td>{dateFormatter.format(new Date(item.updatedAt))}</td>
                      <td>
                        <span className={`verifactu-pos__state verifactu-pos__state--${item.submissionStatus.toLowerCase()}`}>
                          {t(queueStatusKeys[item.submissionStatus])}
                        </span>
                        {item.operationalMessageCode && (
                          <small>{t("verifactu.pos.reviewInManagement")}</small>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </div>
  );
}
