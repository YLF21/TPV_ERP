import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { ErpSelect, type LocaleCode, type UserSession } from "@tpverp/app-common";
import {
  VerifactuAttemptHistoryPanel,
  type VerifactuAttemptTarget
} from "./VerifactuAttemptHistoryPanel";
import { VerifactuDefectiveRecordsView } from "./VerifactuDefectiveRecordsView";
import { VerifactuDiagnosticsView } from "./VerifactuDiagnosticsView";
import { VerifactuCertificateView } from "./VerifactuCertificateView";
import {
  VerifactuResolutionPanel,
  type VerifactuResolutionTarget
} from "./VerifactuResolutionPanel";
import {
  loadVerifactuAdminSubmissions,
  loadVerifactuAdminSummary,
  verifactuDocumentTypes,
  verifactuOperations,
  verifactuSubmissionStatuses,
  type VerifactuAdminSubmissionFilters,
  type VerifactuAdminSubmissionPage,
  type VerifactuAdminSummary
} from "./verifactuManagementApi";
import {
  formatVerifactuDateTime as formatDateTime,
  humanizeVerifactuValue as humanize,
  verifactuEndpointLabel as endpointLabel,
  verifactuOperationLabel as operationLabel,
  verifactuStatusLabel as statusLabel,
  type VerifactuTranslator as Translator
} from "./verifactuPresentation";

type ViewKey = "summary" | "queue" | "defective" | "certificate" | "diagnostics";

type VerifactuManagementScreenProps = {
  locale: LocaleCode;
  session: UserSession;
  t: Translator;
};

const emptyFilters: VerifactuAdminSubmissionFilters = {
  dateFrom: "",
  dateTo: "",
  status: "",
  documentType: "",
  operation: "",
  documentNumber: "",
  page: 0,
  size: 25
};

const emptyPage: VerifactuAdminSubmissionPage = {
  items: [],
  page: 0,
  size: 25,
  totalElements: 0,
  totalPages: 0
};

export function VerifactuManagementScreen({ locale, session, t }: VerifactuManagementScreenProps) {
  const token = session.accessToken;
  const [view, setView] = useState<ViewKey>("summary");
  const [summary, setSummary] = useState<VerifactuAdminSummary | null>(null);
  const [summaryLoading, setSummaryLoading] = useState(true);
  const [summaryError, setSummaryError] = useState(false);
  const [summaryRevision, setSummaryRevision] = useState(0);
  const [draftFilters, setDraftFilters] = useState(emptyFilters);
  const [filters, setFilters] = useState(emptyFilters);
  const [filterError, setFilterError] = useState(false);
  const [queue, setQueue] = useState(emptyPage);
  const [queueLoading, setQueueLoading] = useState(false);
  const [queueError, setQueueError] = useState(false);
  const [queueRevision, setQueueRevision] = useState(0);
  const [reviewRevision, setReviewRevision] = useState(0);
  const [certificateRevision, setCertificateRevision] = useState(0);
  const [attemptTarget, setAttemptTarget] = useState<VerifactuAttemptTarget | null>(null);
  const [resolutionTarget, setResolutionTarget] = useState<VerifactuResolutionTarget | null>(null);
  const summaryRequest = useRef(0);
  const queueRequest = useRef(0);
  const attemptReturnFocus = useRef<HTMLElement | null>(null);
  const canManageCertificates = session.permissions.includes("ADMIN");

  useEffect(() => {
    const requestId = ++summaryRequest.current;
    setSummaryLoading(true);
    setSummaryError(false);
    void loadVerifactuAdminSummary(token)
      .then((next) => {
        if (requestId !== summaryRequest.current) return;
        setSummary(next);
      })
      .catch(() => {
        if (requestId !== summaryRequest.current) return;
        setSummary(null);
        setSummaryError(true);
      })
      .finally(() => {
        if (requestId === summaryRequest.current) setSummaryLoading(false);
      });
    return () => { summaryRequest.current += 1; };
  }, [summaryRevision, token]);

  useEffect(() => {
    if (view !== "queue") {
      queueRequest.current += 1;
      return;
    }
    const requestId = ++queueRequest.current;
    setQueueLoading(true);
    setQueueError(false);
    void loadVerifactuAdminSubmissions(filters, token)
      .then((next) => {
        if (requestId !== queueRequest.current) return;
        setQueue(next);
      })
      .catch(() => {
        if (requestId !== queueRequest.current) return;
        setQueue(emptyPage);
        setQueueError(true);
      })
      .finally(() => {
        if (requestId === queueRequest.current) setQueueLoading(false);
      });
    return () => { queueRequest.current += 1; };
  }, [filters, queueRevision, token, view]);

  function refresh() {
    if (view === "summary") setSummaryRevision((current) => current + 1);
    else if (view === "queue") setQueueRevision((current) => current + 1);
    else if (view === "certificate") setCertificateRevision((current) => current + 1);
    else setReviewRevision((current) => current + 1);
  }

  function applyFilters(event: FormEvent) {
    event.preventDefault();
    if (draftFilters.dateFrom && draftFilters.dateTo && draftFilters.dateFrom > draftFilters.dateTo) {
      setFilterError(true);
      return;
    }
    setFilterError(false);
    setFilters({ ...draftFilters, page: 0 });
    setQueueRevision((current) => current + 1);
  }

  function clearFilters() {
    setFilterError(false);
    setDraftFilters(emptyFilters);
    setFilters(emptyFilters);
    setQueueRevision((current) => current + 1);
  }

  function openPage(page: number) {
    setFilters((current) => ({ ...current, page }));
  }

  function openView(next: ViewKey) {
    setAttemptTarget(null);
    setResolutionTarget(null);
    attemptReturnFocus.current = null;
    setView(next);
  }

  function openAttempts(recordId: string, documentNumber: string, returnFocus: HTMLElement) {
    attemptReturnFocus.current = returnFocus;
    setResolutionTarget(null);
    setAttemptTarget({ recordId, documentNumber });
  }

  function openResolution(recordId: string, documentNumber: string, returnFocus: HTMLElement) {
    attemptReturnFocus.current = returnFocus;
    setAttemptTarget(null);
    setResolutionTarget({ recordId, documentNumber });
  }

  function closeAttempts() {
    setAttemptTarget(null);
    requestAnimationFrame(() => attemptReturnFocus.current?.focus());
  }

  function closeResolution() {
    setResolutionTarget(null);
    requestAnimationFrame(() => attemptReturnFocus.current?.focus());
  }

  function fiscalActionCompleted() {
    setSummaryRevision((current) => current + 1);
    setQueueRevision((current) => current + 1);
    setReviewRevision((current) => current + 1);
  }

  return (
    <section className="gestion-workspace gestion-verifactu-workspace">
      <header className="gestion-dashboard-toolbar gestion-verifactu-header">
        <div>
          <span className="gestion-eyebrow">{t("verifactu.management.eyebrow")}</span>
          <h2>{t("verifactu.management.title")}</h2>
          <p>{t("verifactu.management.readOnlyHint")}</p>
        </div>
        <div className="gestion-dashboard-actions">
          <button type="button" onClick={refresh} disabled={summaryLoading || queueLoading}>
            {t("verifactu.management.refresh")}
          </button>
        </div>
      </header>

      <nav className="gestion-verifactu-tabs" aria-label={t("verifactu.management.views")}>
        <button
          type="button"
          className={view === "summary" ? "active" : ""}
          aria-current={view === "summary" ? "page" : undefined}
          onClick={() => openView("summary")}
        >
          {t("verifactu.management.summary")}
        </button>
        <button
          type="button"
          className={view === "queue" ? "active" : ""}
          aria-current={view === "queue" ? "page" : undefined}
          onClick={() => openView("queue")}
        >
          {t("verifactu.management.queue")}
        </button>
        <button
          type="button"
          className={view === "defective" ? "active" : ""}
          aria-current={view === "defective" ? "page" : undefined}
          onClick={() => openView("defective")}
        >
          {t("verifactu.management.defective")}
        </button>
        {canManageCertificates && (
          <button
            type="button"
            className={view === "certificate" ? "active" : ""}
            aria-current={view === "certificate" ? "page" : undefined}
            onClick={() => openView("certificate")}
          >
            {t("verifactu.management.certificate")}
          </button>
        )}
        <button
          type="button"
          className={view === "diagnostics" ? "active" : ""}
          aria-current={view === "diagnostics" ? "page" : undefined}
          onClick={() => openView("diagnostics")}
        >
          {t("verifactu.management.diagnostics")}
        </button>
      </nav>

      <div className={`gestion-verifactu-review-layout ${attemptTarget || resolutionTarget ? "has-detail" : ""}`}>
        <div className="gestion-verifactu-view">
          {view === "summary" && (
            <SummaryView locale={locale} summary={summary} loading={summaryLoading} error={summaryError} t={t} />
          )}
          {view === "queue" && (
            <QueueView
              locale={locale}
              draftFilters={draftFilters}
              onDraftChange={setDraftFilters}
              onApply={applyFilters}
              onClear={clearFilters}
              filterError={filterError}
              page={queue}
              loading={queueLoading}
              error={queueError}
              onOpenPage={openPage}
              onOpenAttempts={openAttempts}
              onOpenResolution={openResolution}
              t={t}
            />
          )}
          {view === "defective" && (
            <VerifactuDefectiveRecordsView
              locale={locale}
              token={token}
              revision={reviewRevision}
              t={t}
              onOpenAttempts={openAttempts}
              onOpenResolution={openResolution}
            />
          )}
          {view === "diagnostics" && (
            <VerifactuDiagnosticsView locale={locale} token={token} revision={reviewRevision} t={t} />
          )}
          {view === "certificate" && canManageCertificates && (
            <VerifactuCertificateView
              locale={locale}
              token={token}
              revision={certificateRevision}
              t={t}
              onChanged={() => {
                setCertificateRevision((current) => current + 1);
                setSummaryRevision((current) => current + 1);
              }}
            />
          )}
        </div>
        <VerifactuAttemptHistoryPanel
          target={attemptTarget}
          token={token}
          locale={locale}
          t={t}
          onClose={closeAttempts}
        />
        <VerifactuResolutionPanel
          target={resolutionTarget}
          token={token}
          locale={locale}
          t={t}
          onClose={closeResolution}
          onCompleted={fiscalActionCompleted}
        />
      </div>
    </section>
  );
}

function SummaryView({
  locale,
  summary,
  loading,
  error,
  t
}: {
  locale: LocaleCode;
  summary: VerifactuAdminSummary | null;
  loading: boolean;
  error: boolean;
  t: Translator;
}) {
  if (loading && !summary) {
    return <div className="gestion-verifactu-message">{t("verifactu.management.loadingSummary")}</div>;
  }
  if (error || !summary) {
    return <div className="gestion-verifactu-message error" role="alert">{t("verifactu.management.summaryError")}</div>;
  }

  const pending = count(summary, "PENDIENTE") + count(summary, "ENVIANDO") + count(summary, "ENVIADO");
  const review = count(summary, "RECHAZADO") + count(summary, "DEFECTUOSO") + count(summary, "ACEPTADO_CON_ERRORES");
  const accepted = count(summary, "ACEPTADO") + count(summary, "SUBSANADO");
  return (
    <div className="gestion-verifactu-summary">
      <section className={`gestion-verifactu-status ${summary.active ? "active" : "inactive"}`}>
        <div>
          <span>{t("verifactu.management.currentState")}</span>
          <strong>{summary.active ? t("verifactu.management.active") : t("verifactu.management.inactive")}</strong>
        </div>
        <p>{activationLabel(summary.activationMode, t)}</p>
      </section>

      <section className="gestion-verifactu-metrics" aria-label={t("verifactu.management.queueSummary")}>
        <Metric label={t("verifactu.management.pending") } value={pending} tone={pending > 0 ? "warning" : "neutral"} />
        <Metric label={t("verifactu.management.requiresReview")} value={review} tone={review > 0 ? "danger" : "neutral"} />
        <Metric label={t("verifactu.management.completed")} value={accepted} tone="success" />
        <Metric
          label={t("verifactu.management.oldestPending")}
          value={summary.oldestPendingAt ? formatAge(summary.oldestPendingAt) : "—"}
          tone="neutral"
        />
      </section>

      <div className="gestion-verifactu-summary-grid">
        <section className="gestion-verifactu-panel">
          <header><h3>{t("verifactu.management.operation")}</h3></header>
          <dl className="gestion-verifactu-details">
            <Detail label={t("verifactu.management.environment")} value={endpointLabel(summary.endpointMode, t)} />
            <Detail
              label={t("verifactu.management.worker")}
              value={summary.workerEnabled ? t("verifactu.management.workerEnabled") : t("verifactu.management.workerDisabled")}
            />
            <Detail
              label={t("verifactu.management.firstSubmission")}
              value={formatDateTime(summary.firstSubmissionAt, locale)}
            />
            <Detail
              label={t("verifactu.management.activationDate")}
              value={formatDateTime(summary.effectiveActivationAt, locale)}
            />
          </dl>
        </section>

        <section className="gestion-verifactu-panel">
          <header><h3>{t("verifactu.management.controls")}</h3></header>
          <dl className="gestion-verifactu-details">
            <Detail
              label={t("verifactu.management.certificate")}
              value={certificateLabel(summary, t)}
              tone={summary.certificate.valid ? "success" : "danger"}
            />
            <Detail
              label={t("verifactu.management.certificateValidUntil")}
              value={formatDateTime(summary.certificate.validUntil, locale)}
            />
            <Detail
              label={t("verifactu.management.clock")}
              value={clockLabel(summary, t)}
              tone={!summary.clock.available || summary.clock.warning ? "danger" : "success"}
            />
            <Detail
              label={t("verifactu.management.clockCheckedAt")}
              value={formatDateTime(summary.clock.checkedAt, locale)}
            />
          </dl>
        </section>
      </div>

      <section className="gestion-verifactu-panel gestion-verifactu-counts">
        <header><h3>{t("verifactu.management.countsByStatus")}</h3></header>
        <div>
          {verifactuSubmissionStatuses.map((status) => (
            <span key={status}>
              <b>{statusLabel(status, t)}</b>
              <strong>{count(summary, status)}</strong>
            </span>
          ))}
        </div>
      </section>
    </div>
  );
}

function QueueView({
  locale,
  draftFilters,
  onDraftChange,
  onApply,
  onClear,
  filterError,
  page,
  loading,
  error,
  onOpenPage,
  onOpenAttempts,
  onOpenResolution,
  t
}: {
  locale: LocaleCode;
  draftFilters: VerifactuAdminSubmissionFilters;
  onDraftChange: (filters: VerifactuAdminSubmissionFilters) => void;
  onApply: (event: FormEvent) => void;
  onClear: () => void;
  filterError: boolean;
  page: VerifactuAdminSubmissionPage;
  loading: boolean;
  error: boolean;
  onOpenPage: (page: number) => void;
  onOpenAttempts: (recordId: string, documentNumber: string, returnFocus: HTMLElement) => void;
  onOpenResolution: (recordId: string, documentNumber: string, returnFocus: HTMLElement) => void;
  t: Translator;
}) {
  const statusOptions = useMemo(() => [
    { value: "", label: t("verifactu.management.allStatuses") },
    ...verifactuSubmissionStatuses.map((status) => ({ value: status, label: statusLabel(status, t) }))
  ], [t]);
  const documentTypeOptions = useMemo(() => [
    { value: "", label: t("verifactu.management.allTypes") },
    ...verifactuDocumentTypes.map((type) => ({ value: type, label: type }))
  ], [t]);
  const operationOptions = useMemo(() => [
    { value: "", label: t("verifactu.management.allOperations") },
    ...verifactuOperations.map((operation) => ({ value: operation, label: operationLabel(operation, t) }))
  ], [t]);
  const selectedStatusLabel = statusOptions.find((option) => option.value === draftFilters.status)?.label
    ?? t("verifactu.management.allStatuses");
  const selectedDocumentTypeLabel = documentTypeOptions.find((option) => option.value === draftFilters.documentType)?.label
    ?? t("verifactu.management.allTypes");
  const selectedOperationLabel = operationOptions.find((option) => option.value === draftFilters.operation)?.label
    ?? t("verifactu.management.allOperations");
  return (
    <div className="gestion-verifactu-queue">
      <form className="gestion-verifactu-filters" onSubmit={onApply}>
        <label>
          <span>{t("verifactu.management.dateFrom")}</span>
          <input
            type="date"
            value={draftFilters.dateFrom}
            onChange={(event) => onDraftChange({ ...draftFilters, dateFrom: event.target.value })}
          />
        </label>
        <label>
          <span>{t("verifactu.management.dateTo")}</span>
          <input
            type="date"
            value={draftFilters.dateTo}
            onChange={(event) => onDraftChange({ ...draftFilters, dateTo: event.target.value })}
          />
        </label>
        <label>
          <span>{t("verifactu.management.status")}</span>
          <ErpSelect
            value={draftFilters.status}
            options={statusOptions}
            aria-label={`${t("verifactu.management.status")}: ${selectedStatusLabel}`}
            onChange={(value) => onDraftChange({ ...draftFilters, status: value as VerifactuAdminSubmissionFilters["status"] })}
          />
        </label>
        <label>
          <span>{t("verifactu.management.documentType")}</span>
          <ErpSelect
            value={draftFilters.documentType}
            options={documentTypeOptions}
            aria-label={`${t("verifactu.management.documentType")}: ${selectedDocumentTypeLabel}`}
            onChange={(value) => onDraftChange({ ...draftFilters, documentType: value as VerifactuAdminSubmissionFilters["documentType"] })}
          />
        </label>
        <label>
          <span>{t("verifactu.management.fiscalOperation")}</span>
          <ErpSelect
            value={draftFilters.operation}
            options={operationOptions}
            aria-label={`${t("verifactu.management.fiscalOperation")}: ${selectedOperationLabel}`}
            onChange={(value) => onDraftChange({ ...draftFilters, operation: value as VerifactuAdminSubmissionFilters["operation"] })}
          />
        </label>
        <label className="gestion-verifactu-number-filter">
          <span>{t("verifactu.management.documentNumber")}</span>
          <input
            maxLength={64}
            value={draftFilters.documentNumber}
            onChange={(event) => onDraftChange({ ...draftFilters, documentNumber: event.target.value })}
          />
        </label>
        <div className="gestion-verifactu-filter-actions">
          <button type="submit" className="primary">{t("verifactu.management.applyFilters")}</button>
          <button type="button" onClick={onClear}>{t("verifactu.management.clearFilters")}</button>
        </div>
        {filterError && <p role="alert">{t("verifactu.management.invalidDateRange")}</p>}
      </form>

      <section className="gestion-verifactu-table-panel">
        <header>
          <div>
            <h3>{t("verifactu.management.queueTitle")}</h3>
            <span>{page.totalElements} {t("verifactu.management.records")}</span>
          </div>
          {loading && <span className="gestion-verifactu-loading">{t("verifactu.management.updating")}</span>}
        </header>
        {error ? (
          <div className="gestion-verifactu-message error" role="alert">{t("verifactu.management.queueError")}</div>
        ) : !loading && page.items.length === 0 ? (
          <div className="gestion-verifactu-message">{t("verifactu.management.emptyQueue")}</div>
        ) : (
          <div className="gestion-verifactu-table-scroll">
            <table className="gestion-verifactu-table">
              <thead>
                <tr>
                  <th>{t("verifactu.management.sequence")}</th>
                  <th>{t("verifactu.management.document")}</th>
                  <th>{t("verifactu.management.fiscalOperation")}</th>
                  <th>{t("verifactu.management.status")}</th>
                  <th>{t("verifactu.management.updatedAt")}</th>
                  <th>{t("verifactu.management.errorCode")}</th>
                  <th>{t("verifactu.management.attempts")}</th>
                  <th>{t("verifactu.resolution.actions")}</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((item) => (
                  <tr key={item.recordId}>
                    <td className="numeric">{item.sequence}</td>
                    <td><strong>{item.documentNumber}</strong><small>{item.documentType}</small></td>
                    <td>{operationLabel(item.operation, t)}</td>
                    <td><span className={`gestion-verifactu-state state-${item.status.toLowerCase()}`}>{statusLabel(item.status, t)}</span></td>
                    <td>{formatDateTime(item.updatedAt, locale)}</td>
                    <td>{item.errorCode || "—"}</td>
                    <td>
                      <button
                        type="button"
                        className="gestion-verifactu-link-button"
                        aria-label={`${t("verifactu.management.viewAttempts")} ${item.documentNumber}`}
                        onClick={(event) => onOpenAttempts(item.recordId, item.documentNumber, event.currentTarget)}
                      >
                        {t("verifactu.management.viewAttempts")}
                      </button>
                    </td>
                    <td>
                      <button
                        type="button"
                        className="gestion-verifactu-link-button"
                        aria-label={`${t("verifactu.resolution.review")} ${item.documentNumber}`}
                        onClick={(event) => onOpenResolution(item.recordId, item.documentNumber, event.currentTarget)}
                      >
                        {t("verifactu.resolution.review")}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <footer className="gestion-verifactu-pagination">
          <span>{t("verifactu.management.page")} {page.totalPages === 0 ? 0 : page.page + 1} / {page.totalPages}</span>
          <div>
            <button type="button" disabled={loading || page.page <= 0} onClick={() => onOpenPage(page.page - 1)}>
              {t("verifactu.management.previous")}
            </button>
            <button type="button" disabled={loading || page.page + 1 >= page.totalPages} onClick={() => onOpenPage(page.page + 1)}>
              {t("verifactu.management.next")}
            </button>
          </div>
        </footer>
      </section>
    </div>
  );
}

function Metric({ label, value, tone }: { label: string; value: string | number; tone: string }) {
  return <div className={`gestion-verifactu-metric ${tone}`}><span>{label}</span><strong>{value}</strong></div>;
}

function Detail({ label, value, tone = "" }: { label: string; value: string; tone?: string }) {
  return <div><dt>{label}</dt><dd className={tone}>{value}</dd></div>;
}

function count(summary: VerifactuAdminSummary, status: string) {
  return Number(summary.countsByStatus[status] ?? 0);
}

function activationLabel(mode: string, t: Translator) {
  const key = `verifactu.management.activation.${mode}`;
  const translated = t(key);
  return translated === key ? humanize(mode) : translated;
}

function certificateLabel(summary: VerifactuAdminSummary, t: Translator) {
  if (!summary.certificate.configured) return t("verifactu.management.certificateNotConfigured");
  if (summary.certificate.valid) return t("verifactu.management.certificateValid");
  return summary.certificate.warningCode === "CERTIFICATE_EXPIRED"
    ? t("verifactu.management.certificateExpired")
    : t("verifactu.management.certificateInvalid");
}

function clockLabel(summary: VerifactuAdminSummary, t: Translator) {
  if (!summary.clock.available) return t("verifactu.management.unavailable");
  if (summary.clock.warning) return t("verifactu.management.clockWarning");
  const drift = summary.clock.driftSeconds ?? 0;
  return `${t("verifactu.management.clockCorrect")} · ${drift} s`;
}

function formatAge(value: string) {
  const timestamp = new Date(value).getTime();
  if (!Number.isFinite(timestamp)) return "—";
  const minutes = Math.max(0, Math.floor((Date.now() - timestamp) / 60_000));
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours} h`;
  return `${Math.floor(hours / 24)} d`;
}
