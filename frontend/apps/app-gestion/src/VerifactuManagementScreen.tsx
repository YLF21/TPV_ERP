import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import {
  ErpSelect,
  TableLayoutHeaderCell,
  nextTableSort,
  useTableLayoutPreference,
  useTableSortPreference,
  visibleTableColumns,
  type LocaleCode,
  type TableColumnDefinition,
  type TableSort,
  type UserSession
} from "@tpverp/app-common";
import {
  VerifactuAttemptHistoryPanel,
  type VerifactuAttemptTarget
} from "./VerifactuAttemptHistoryPanel";
import { VerifactuDefectiveRecordsView } from "./VerifactuDefectiveRecordsView";
import { VerifactuDiagnosticsView } from "./VerifactuDiagnosticsView";
import { VerifactuCertificateView } from "./VerifactuCertificateView";
import { FiscalComplianceView } from "./FiscalComplianceView";
import { FiscalModeControlView } from "./FiscalModeControlView";
import { FiscalRecordsView } from "./FiscalRecordsView";
import { FiscalWorkspaceDialog } from "./FiscalWorkspaceDialog";
import { fiscalErrorMessage } from "./verifactuErrorPresentation";
import {
  VerifactuResolutionPanel,
  type VerifactuResolutionTarget
} from "./VerifactuResolutionPanel";
import {
  loadVerifactuAdminSubmissions,
  loadVerifactuAdminSummary,
  loadFiscalStatus,
  loadFiscalSandboxStatus,
  setFiscalSandboxScenario,
  dispatchFiscalSandboxNext,
  verifactuDocumentTypes,
  verifactuOperations,
  verifactuActiveSubmissionStatuses,
  verifactuSubmissionStatuses,
  type VerifactuAdminSubmissionFilters,
  type VerifactuAdminSubmissionPage,
  type VerifactuAdminSummary,
  type FiscalMode
} from "./verifactuManagementApi";
import {
  formatVerifactuDate,
  formatVerifactuDateTime as formatDateTime,
  humanizeVerifactuValue as humanize,
  verifactuEndpointLabel as endpointLabel,
  verifactuOperationLabel as operationLabel,
  verifactuStatusLabel as statusLabel,
  type VerifactuTranslator as Translator
} from "./verifactuPresentation";

type ViewKey = "summary" | "records" | "queue" | "defective" | "compliance" | "mode" | "certificate" | "diagnostics";
const queueSortColumns = ["sequence", "document", "fiscalOperation", "status", "updatedAt", "errorCode"] as const;
type QueueSortColumn = typeof queueSortColumns[number];
const queueColumnDefinitions = [
  { key: "sequence", defaultWidth: 88 },
  { key: "document", defaultWidth: 190 },
  { key: "fiscalOperation", defaultWidth: 150 },
  { key: "status", defaultWidth: 150 },
  { key: "updatedAt", defaultWidth: 180 },
  { key: "errorCode", defaultWidth: 140 }
] as const satisfies readonly TableColumnDefinition<QueueSortColumn>[];

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
  totalPages: 0,
  truncated: false
};

export function VerifactuManagementScreen({ locale, session, t }: VerifactuManagementScreenProps) {
  const token = session.accessToken;
  const queueSorting = useTableSortPreference({
    app: "gestion",
    username: session.username,
    tableKey: "gestion.verifactu.queue",
    columns: queueSortColumns,
    defaultSort: null
  });
  const queueTableLayout = useTableLayoutPreference({
    app: "gestion",
    username: session.username,
    accessToken: token,
    tableKey: "gestion.verifactu.queue",
    definitions: queueColumnDefinitions
  });
  const initialQueueFilters = { ...emptyFilters, sortBy: queueSorting.sort?.column, sortDirection: queueSorting.sort?.direction };
  const [view, setView] = useState<ViewKey>("summary");
  const [summary, setSummary] = useState<VerifactuAdminSummary | null>(null);
  const [summaryLoading, setSummaryLoading] = useState(true);
  const [summaryError, setSummaryError] = useState(false);
  const [summaryRevision, setSummaryRevision] = useState(0);
  const [draftFilters, setDraftFilters] = useState<VerifactuAdminSubmissionFilters>(initialQueueFilters);
  const [filters, setFilters] = useState<VerifactuAdminSubmissionFilters>(initialQueueFilters);
  const [filterError, setFilterError] = useState(false);
  const [queue, setQueue] = useState(emptyPage);
  const [queueLoading, setQueueLoading] = useState(false);
  const [queueError, setQueueError] = useState(false);
  const [queueRevision, setQueueRevision] = useState(0);
  const [reviewRevision, setReviewRevision] = useState(0);
  const [certificateRevision, setCertificateRevision] = useState(0);
  const [sandboxStatus, setSandboxStatus] = useState<Awaited<ReturnType<typeof loadFiscalSandboxStatus>> | null>(null);
  const [fiscalStatus, setFiscalStatus] = useState<Awaited<ReturnType<typeof loadFiscalStatus>> | null>(null);
  const [fiscalStatusLoading, setFiscalStatusLoading] = useState(true);
  const [fiscalStatusError, setFiscalStatusError] = useState(false);
  const [sandboxOutcome, setSandboxOutcome] = useState("ACCEPTED");
  const [sandboxError, setSandboxError] = useState(false);
  const [sandboxScenarioWorking, setSandboxScenarioWorking] = useState(false);
  const [sandboxDispatchWorking, setSandboxDispatchWorking] = useState(false);
  const [attemptTarget, setAttemptTarget] = useState<VerifactuAttemptTarget | null>(null);
  const [resolutionTarget, setResolutionTarget] = useState<VerifactuResolutionTarget | null>(null);
  const summaryRequest = useRef(0);
  const queueRequest = useRef(0);
  const attemptReturnFocus = useRef<HTMLElement | null>(null);
  const canManageCertificates = session.permissions.includes("ADMIN");
  const canReadFiscalStatus = canManageCertificates || session.permissions.includes("VERIFACTU_READ");
  const canManageSandbox = canManageCertificates;
  const canManageFiscalCompliance = canManageCertificates
    || (session.permissions.includes("APP_GESTION_ACCESS")
      && session.permissions.includes("VERIFACTU_READ")
      && session.permissions.includes("VERIFACTU_MANAGE"));
  const canCorrectFiscalRecords = canManageCertificates || session.permissions.includes("VERIFACTU_CORRECT");
  const managementHint = canManageFiscalCompliance
    ? "verifactu.management.manageHint"
    : canCorrectFiscalRecords
      ? "verifactu.management.correctionHint"
      : "verifactu.management.readOnlyHint";

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
    if (!canReadFiscalStatus) {
      setFiscalStatusLoading(false);
      return;
    }
    setFiscalStatusLoading(true);
    setFiscalStatusError(false);
    void loadFiscalStatus(token)
      .then((fiscal) => setFiscalStatus(fiscal))
      .catch(() => {
        setFiscalStatus(null);
        setFiscalStatusError(true);
      })
      .finally(() => setFiscalStatusLoading(false));
  }, [canReadFiscalStatus, summaryRevision, token]);

  useEffect(() => {
    if (!canManageSandbox) return;
    void loadFiscalSandboxStatus(token)
      .then((sandbox) => {
        setSandboxStatus(sandbox);
        setSandboxOutcome(sandbox.nextOutcome);
      })
      .catch(() => setSandboxStatus(null));
  }, [canManageSandbox, summaryRevision, token]);

  async function configureSandboxOutcome() {
    if (sandboxScenarioWorking || sandboxDispatchWorking || !sandboxStatus) return;
    setSandboxScenarioWorking(true);
    setSandboxError(false);
    try {
      const next = await setFiscalSandboxScenario(sandboxOutcome as NonNullable<typeof sandboxStatus>["nextOutcome"], token);
      setSandboxStatus(next);
    } catch {
      setSandboxError(true);
    } finally {
      setSandboxScenarioWorking(false);
    }
  }

  async function dispatchSandbox() {
    if (sandboxScenarioWorking || sandboxDispatchWorking || !sandboxStatus) return;
    setSandboxDispatchWorking(true);
    setSandboxError(false);
    try {
      await dispatchFiscalSandboxNext(token);
      const [next, fiscal] = await Promise.all([loadFiscalSandboxStatus(token), loadFiscalStatus(token)]);
      setSandboxStatus(next);
      setSandboxOutcome(next.nextOutcome);
      setFiscalStatus(fiscal);
      setSummaryRevision((current) => current + 1);
    } catch {
      setSandboxError(true);
    } finally {
      setSandboxDispatchWorking(false);
    }
  }

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
    setSummaryRevision((current) => current + 1);
    if (view === "summary") return;
    if (view === "queue") setQueueRevision((current) => current + 1);
    else if (view === "certificate") setCertificateRevision((current) => current + 1);
    else setReviewRevision((current) => current + 1);
  }

  function applyFilters(event: FormEvent): boolean {
    event.preventDefault();
    if (draftFilters.dateFrom && draftFilters.dateTo && draftFilters.dateFrom > draftFilters.dateTo) {
      setFilterError(true);
      return false;
    }
    setFilterError(false);
    setFilters({ ...draftFilters, page: 0 });
    setQueueRevision((current) => current + 1);
    return true;
  }

  function clearFilters() {
    setFilterError(false);
    const cleared = { ...emptyFilters, sortBy: queueSorting.sort?.column, sortDirection: queueSorting.sort?.direction };
    setDraftFilters(cleared);
    setFilters(cleared);
    setQueueRevision((current) => current + 1);
  }

  function changeQueueSort(column: QueueSortColumn) {
    const next = nextTableSort(queueSorting.sort, column);
    queueSorting.setSort(next);
    setDraftFilters((current) => ({ ...current, sortBy: next.column, sortDirection: next.direction }));
    setFilters((current) => ({ ...current, sortBy: next.column, sortDirection: next.direction, page: 0 }));
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
      <div className="gestion-verifactu-navigation">
      <nav className="gestion-verifactu-tabs" role="tablist" aria-label={t("verifactu.management.views")}>
        <button
          id="verifactu-tab-summary"
          type="button"
          className={view === "summary" ? "active" : ""}
          role="tab"
          aria-selected={view === "summary"}
          aria-controls="verifactu-view-panel"
          onClick={() => openView("summary")}
        >
          {t("verifactu.management.summary")}
        </button>
        <button
          id="verifactu-tab-records"
          type="button"
          className={view === "records" ? "active" : ""}
          role="tab"
          aria-selected={view === "records"}
          aria-controls="verifactu-view-panel"
          onClick={() => openView("records")}
        >
          {t("verifactu.management.recordsView")}
        </button>
        <button
          id="verifactu-tab-queue"
          type="button"
          className={view === "queue" ? "active" : ""}
          role="tab"
          aria-selected={view === "queue"}
          aria-controls="verifactu-view-panel"
          onClick={() => openView("queue")}
        >
          {t("verifactu.management.queue")}
        </button>
        <button
          id="verifactu-tab-defective"
          type="button"
          className={view === "defective" ? "active" : ""}
          role="tab"
          aria-selected={view === "defective"}
          aria-controls="verifactu-view-panel"
          onClick={() => openView("defective")}
        >
          {t("verifactu.management.defective")}
        </button>
        <button
          id="verifactu-tab-compliance"
          type="button"
          className={view === "compliance" ? "active" : ""}
          role="tab"
          aria-selected={view === "compliance"}
          aria-controls="verifactu-view-panel"
          onClick={() => openView("compliance")}
        >
          {t("verifactu.management.compliance")}
        </button>
        {canManageCertificates && (
          <button
            id="verifactu-tab-mode"
            type="button"
            className={view === "mode" ? "active" : ""}
            role="tab"
            aria-selected={view === "mode"}
            aria-controls="verifactu-view-panel"
            onClick={() => openView("mode")}
          >
            {t("verifactu.management.modeControl")}
          </button>
        )}
        {canManageCertificates && (
          <button
            id="verifactu-tab-certificate"
            type="button"
            className={view === "certificate" ? "active" : ""}
            role="tab"
            aria-selected={view === "certificate"}
            aria-controls="verifactu-view-panel"
            onClick={() => openView("certificate")}
          >
            {t("verifactu.management.certificate")}
          </button>
        )}
        <button
          id="verifactu-tab-diagnostics"
          type="button"
          className={view === "diagnostics" ? "active" : ""}
          role="tab"
          aria-selected={view === "diagnostics"}
          aria-controls="verifactu-view-panel"
          onClick={() => openView("diagnostics")}
        >
          {t("verifactu.management.diagnostics")}
        </button>
      </nav>
      <button
        type="button"
        className="gestion-verifactu-refresh"
        onClick={refresh}
        disabled={summaryLoading || queueLoading || fiscalStatusLoading}
      >
        {t("verifactu.management.refresh")}
      </button>
      </div>

      <div className={`gestion-verifactu-review-layout ${attemptTarget || resolutionTarget ? "has-detail" : ""}`}>
        <div id="verifactu-view-panel" className="gestion-verifactu-view" role="tabpanel" tabIndex={0} aria-labelledby={`verifactu-tab-${view}`} aria-label={viewPanelLabel(view, t)}>
          {view === "summary" && (
            <>
              <header className="gestion-dashboard-toolbar gestion-verifactu-header">
                <div>
                  <span className="gestion-eyebrow">{t("verifactu.management.eyebrow")}</span>
                  <h2>{t("verifactu.management.title")}</h2>
                  <p>{t(managementHint)}</p>
                </div>
              </header>
              <FiscalStatusStrip
                locale={locale}
                status={fiscalStatus}
                loading={fiscalStatusLoading}
                error={fiscalStatusError}
                t={t}
              />
              {(fiscalStatus?.runtimeClass === "SANDBOX" || sandboxStatus?.runtimeClass === "SANDBOX") && (
                <SandboxPanel
                  locale={locale}
                  fiscalStatus={fiscalStatus}
                  sandboxStatus={sandboxStatus}
                  summary={summary}
                  canManageSandbox={canManageSandbox}
                  sandboxOutcome={sandboxOutcome}
                  sandboxScenarioWorking={sandboxScenarioWorking}
                  sandboxDispatchWorking={sandboxDispatchWorking}
                  sandboxError={sandboxError}
                  onOutcomeChange={setSandboxOutcome}
                  onConfigure={() => void configureSandboxOutcome()}
                  onDispatch={() => void dispatchSandbox()}
                  t={t}
                />
              )}
              <SummaryView locale={locale} timezone={fiscalStatus?.timezone ?? null} summary={summary} fiscalMode={fiscalStatus?.mode ?? null} loading={summaryLoading} error={summaryError} t={t} />
            </>
          )}
          {view === "queue" && (
            <QueueView
              locale={locale}
              timezone={fiscalStatus?.timezone ?? null}
              tableLayout={queueTableLayout}
              activeFilterCount={countActiveFilters(filters)}
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
              sort={queueSorting.sort}
              onSort={changeQueueSort}
              t={t}
            />
          )}
          {view === "defective" && (
            <VerifactuDefectiveRecordsView
              locale={locale}
              timezone={fiscalStatus?.timezone ?? null}
              token={token}
              username={session.username}
              revision={reviewRevision}
              t={t}
              onOpenAttempts={openAttempts}
              onOpenResolution={openResolution}
            />
          )}
          {view === "diagnostics" && (
            <VerifactuDiagnosticsView locale={locale} timezone={fiscalStatus?.timezone ?? null} token={token} revision={reviewRevision} t={t} />
          )}
          {view === "records" && (
            <FiscalRecordsView locale={locale} timezone={fiscalStatus?.timezone ?? null} token={token} username={session.username} revision={reviewRevision} t={t} canManage={canManageFiscalCompliance} />
          )}
          {view === "compliance" && (
            <FiscalComplianceView
              locale={locale}
              token={token}
              mode={fiscalStatus?.mode ?? "PRE_SIF"}
              timezone={fiscalStatus?.timezone ?? null}
              canManage={canManageFiscalCompliance}
              username={session.username}
              canAttendRequirements={canManageCertificates}
              revision={reviewRevision}
              t={t}
              onChanged={fiscalActionCompleted}
            />
          )}
          {view === "mode" && canManageCertificates && (
            <FiscalModeControlView
              locale={locale}
              timezone={fiscalStatus?.timezone ?? null}
              token={token}
              status={fiscalStatus}
              t={t}
              onChanged={(next) => {
                setFiscalStatus(next);
                fiscalActionCompleted();
              }}
            />
          )}
          {view === "certificate" && canManageCertificates && (
            <VerifactuCertificateView
              locale={locale}
              timezone={fiscalStatus?.timezone ?? null}
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
          timezone={fiscalStatus?.timezone ?? null}
          t={t}
          onClose={closeAttempts}
        />
        <VerifactuResolutionPanel
          target={resolutionTarget}
          token={token}
          locale={locale}
          timezone={fiscalStatus?.timezone ?? null}
          t={t}
          onClose={closeResolution}
          onCompleted={fiscalActionCompleted}
        />
      </div>
    </section>
  );
}

function FiscalStatusStrip({
  locale,
  status,
  loading,
  error,
  t
}: {
  locale: LocaleCode;
  status: Awaited<ReturnType<typeof loadFiscalStatus>> | null;
  loading: boolean;
  error: boolean;
  t: Translator;
}) {
  const stateLabel = loading
    ? t("verifactu.management.fiscalStatusLoading")
    : error || !status
      ? t("verifactu.management.fiscalStatusUnavailable")
      : fiscalModeLabel(status.mode, t);
  return (
    <section className={`gestion-verifactu-fiscal-strip ${status ? `runtime-${status.runtimeClass.toLowerCase()}` : "is-unavailable"}`} aria-label={t("verifactu.management.fiscalStatus")}>
      <div className="gestion-verifactu-fiscal-primary">
        <span>{t("verifactu.management.fiscalStatus")}</span>
        <strong aria-live="polite">{stateLabel}</strong>
      </div>
      {status && (
        <dl className="gestion-verifactu-fiscal-facts">
          <div><dt>{t("verifactu.management.fiscalRuntime")}</dt><dd>{fiscalRuntimeLabel(status.runtimeClass, t)}</dd></div>
          <div><dt>{t("verifactu.management.fiscalEndpoint")}</dt><dd>{fiscalEndpointLabel(status.endpointEnvironment, t)}</dd></div>
          <div><dt>{t("verifactu.management.fiscalTransport")}</dt><dd>{fiscalTransportLabel(status.transportMode, t)}</dd></div>
          {status.timezone && <div><dt>{t("verifactu.management.timezone")}</dt><dd><code>{status.timezone}</code></dd></div>}
          <div><dt>{t("verifactu.management.fiscalSince")}</dt><dd>{formatDateTime(status.modeSince, locale, status.timezone ?? null)}</dd></div>
        </dl>
      )}
      {status && (status.verifactuBlockedUntil || status.scheduledTransition
        || (status.runtimeClass === "REAL" && status.endpointEnvironment === "PRODUCTION" && !status.productionEnabled)) && (
        <div className={`gestion-verifactu-fiscal-notices ${status.scheduledTransition?.status === "FALLIDA" ? "has-error" : ""}`} role={status.scheduledTransition?.status === "FALLIDA" ? "alert" : "status"}>
          {status.verifactuBlockedUntil && <span>
            <strong>{t("verifactu.management.fiscalPermanence")}</strong>
            {formatVerifactuDate(status.verifactuBlockedUntil, locale)}
          </span>}
          {status.scheduledTransition && <span>
            <strong>{status.scheduledTransition.status === "FALLIDA"
              ? t("verifactu.management.fiscalTransitionFailed")
              : t("verifactu.management.fiscalTransitionScheduled")}</strong>
            {fiscalModeLabel(status.scheduledTransition.newMode, t)} · {formatDateTime(status.scheduledTransition.effectiveAt, locale, status.timezone ?? null)}
            {status.scheduledTransition.lastErrorCode && <span> · {fiscalErrorMessage(status.scheduledTransition.lastErrorCode, t, locale)}</span>}
          </span>}
          {status.runtimeClass === "REAL" && status.endpointEnvironment === "PRODUCTION" && !status.productionEnabled && <span>
            <strong>{t("verifactu.management.fiscalProductionBlocked")}</strong>
            {t("verifactu.management.fiscalProductionBlockedHint")}
          </span>}
        </div>
      )}
    </section>
  );
}

function SummaryView({
  locale,
  timezone = null,
  summary,
  fiscalMode,
  loading,
  error,
  t
}: {
  locale: LocaleCode;
  timezone?: string | null;
  summary: VerifactuAdminSummary | null;
  fiscalMode: FiscalMode | null;
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
  const modeIsNoVerifactu = fiscalMode === "NO_VERIFACTU";
  const modeIsPreSif = fiscalMode === "PRE_SIF";
  return (
    <div className="gestion-verifactu-summary">
      <section className={`gestion-verifactu-status ${modeIsPreSif || (!fiscalMode && !summary.active) ? "inactive" : "active"}`}>
        <div>
          <span>{t("verifactu.management.currentState")}</span>
          <strong>{fiscalMode ? fiscalModeLabel(fiscalMode, t) : summary.active ? t("verifactu.management.active") : t("verifactu.management.inactive")}</strong>
        </div>
        <p>{modeIsNoVerifactu || modeIsPreSif ? t(`verifactu.mode.explanation.${fiscalMode}`) : activationLabel(summary.activationMode, t)}</p>
      </section>

      <section className="gestion-verifactu-metrics" aria-label={t("verifactu.management.queueSummary")}>
        <Metric label={t("verifactu.management.pending") } value={pending} tone={pending > 0 ? "warning" : "neutral"} />
        <Metric label={t("verifactu.management.requiresReview")} value={review} tone={review > 0 ? "danger" : "neutral"} />
        <Metric label={t("verifactu.management.completed")} value={accepted} tone="success" />
        <Metric
          label={t("verifactu.management.oldestPending")}
          value={summary.oldestPendingAt ? formatAge(summary.oldestPendingAt, locale, t) : "—"}
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
              value={formatDateTime(summary.firstSubmissionAt, locale, timezone)}
            />
            <Detail
              label={t("verifactu.management.activationDate")}
              value={formatDateTime(summary.effectiveActivationAt, locale, timezone)}
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
              value={formatDateTime(summary.certificate.validUntil, locale, timezone)}
            />
            <Detail
              label={t("verifactu.management.clock")}
              value={clockLabel(summary, t)}
              tone={!summary.clock.available || summary.clock.warning ? "danger" : "success"}
            />
            <Detail
              label={t("verifactu.management.clockCheckedAt")}
              value={formatDateTime(summary.clock.checkedAt, locale, timezone)}
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
  timezone = null,
  tableLayout,
  activeFilterCount,
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
  sort,
  onSort,
  t
}: {
  locale: LocaleCode;
  timezone?: string | null;
  tableLayout: ReturnType<typeof useTableLayoutPreference<QueueSortColumn>>;
  activeFilterCount: number;
  draftFilters: VerifactuAdminSubmissionFilters;
  onDraftChange: (filters: VerifactuAdminSubmissionFilters) => void;
  onApply: (event: FormEvent) => boolean;
  onClear: () => void;
  filterError: boolean;
  page: VerifactuAdminSubmissionPage;
  loading: boolean;
  error: boolean;
  onOpenPage: (page: number) => void;
  onOpenAttempts: (recordId: string, documentNumber: string, returnFocus: HTMLElement) => void;
  onOpenResolution: (recordId: string, documentNumber: string, returnFocus: HTMLElement) => void;
  sort: TableSort<QueueSortColumn> | null;
  onSort: (column: QueueSortColumn) => void;
  t: Translator;
}) {
  const [filtersOpen, setFiltersOpen] = useState(false);
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const statusOptions = useMemo(() => [
    { value: "", label: t("verifactu.management.allStatuses") },
    ...verifactuActiveSubmissionStatuses.map((status) => ({ value: status, label: statusLabel(status, t) }))
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
      {filtersOpen && <FiscalWorkspaceDialog
        id="verifactu-queue-filters"
        title={t("verifactu.ui.filters")}
        closeLabel={t("verifactu.ui.close")}
        onClose={() => setFiltersOpen(false)}
        closeDisabled={false}
        purpose="filters"
        footer={<>
          <button type="button" onClick={onClear}>{t("verifactu.management.clearFilters")}</button>
          <button type="submit" form="verifactu-queue-filters-form" className="primary">{t("verifactu.management.applyFilters")}</button>
        </>}
      >
        <form id="verifactu-queue-filters-form" className="gestion-verifactu-filters" onSubmit={(event) => { if (onApply(event)) setFiltersOpen(false); }}>
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
        {filterError && <p role="alert">{t("verifactu.management.invalidDateRange")}</p>}
        </form>
      </FiscalWorkspaceDialog>}

      <section className="gestion-verifactu-table-panel">
        <header>
          <div>
            <h3>{t("verifactu.management.queueTitle")}</h3>
            <span>{page.totalElements} {t("verifactu.management.records")}</span>
          </div>
          <div className="fiscal-records-toolbar">
            {loading && <span className="gestion-verifactu-loading">{t("verifactu.management.updating")}</span>}
            <button
              type="button"
              className="gestion-verifactu-filter-trigger"
              aria-haspopup="dialog"
              aria-expanded={filtersOpen}
              aria-label={`${t("verifactu.ui.filters")}${activeFilterCount ? ` (${activeFilterCount})` : ""}`}
              onClick={() => setFiltersOpen(true)}
            >
              {t("verifactu.ui.filters")}{activeFilterCount ? ` (${activeFilterCount})` : ""}
            </button>
          </div>
        </header>
        {page.truncated && (
          <div className="gestion-verifactu-message warning" role="status">
            {t("verifactu.management.queueTruncated")}
          </div>
        )}
        {error ? (
          <div className="gestion-verifactu-message error" role="alert">{t("verifactu.management.queueError")}</div>
        ) : !loading && page.items.length === 0 ? (
          <div className="gestion-verifactu-message">{t("verifactu.management.emptyQueue")}</div>
        ) : (
          <div className="gestion-verifactu-table-scroll">
          <table className="gestion-verifactu-table" aria-rowcount={page.totalElements}>
            <colgroup>
              {visibleColumns.map((column) => <col key={column.key} style={{ width: `${column.width}px` }} />)}
              <col style={{ width: "170px" }} />
              <col style={{ width: "170px" }} />
            </colgroup>
            <thead><tr>
              {visibleColumns.map((column) => {
                const label = t(`verifactu.management.${column.key}`);
                return <TableLayoutHeaderCell
                  column={column}
                  key={column.key}
                  sortDirection={sort?.column === column.key ? sort.direction : null}
                  sortLabel={label}
                  onSort={onSort}
                  resizeLabel={`${t("verifactu.ui.resizeColumn")} ${label}`}
                  onReorder={tableLayout.reorderColumns}
                  onMove={tableLayout.moveColumn}
                  onResize={tableLayout.resizeColumn}
                >{label}</TableLayoutHeaderCell>;
              })}
              <th>{t("verifactu.management.attempts")}</th>
              <th>{t("verifactu.resolution.actions")}</th>
            </tr></thead>
            <tbody>{page.items.map((item) => (
              <tr key={item.recordId}>
                {visibleColumns.map((column) => <td data-column-key={column.key} key={column.key}>{renderQueueCell(item, column.key, locale, timezone, t)}</td>)}
                <td>
                  <button
                    type="button"
                    className="gestion-verifactu-link-button"
                    aria-label={`${t("verifactu.management.viewAttempts")} ${item.documentNumber}`}
                    onClick={(event) => onOpenAttempts(item.recordId, item.documentNumber, event.currentTarget)}
                  >{t("verifactu.management.viewAttempts")}</button>
                </td>
                <td>
                  <button
                    type="button"
                    className="gestion-verifactu-link-button"
                    aria-label={`${t("verifactu.resolution.review")} ${item.documentNumber}`}
                    onClick={(event) => onOpenResolution(item.recordId, item.documentNumber, event.currentTarget)}
                  >{t("verifactu.resolution.review")}</button>
                </td>
              </tr>
            ))}</tbody>
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

function renderQueueCell(
  item: VerifactuAdminSubmissionPage["items"][number],
  column: QueueSortColumn,
  locale: LocaleCode,
  timezone: string | null,
  t: Translator
) {
  if (column === "sequence") return <span className="numeric">{item.sequence}</span>;
  if (column === "document") return <><strong>{item.documentNumber}</strong><small>{item.documentType}</small></>;
  if (column === "fiscalOperation") return operationLabel(item.operation, t);
  if (column === "status") return <span className={`gestion-verifactu-state state-${item.status.toLowerCase()}`}>{statusLabel(item.status, t)}</span>;
  if (column === "updatedAt") return formatDateTime(item.updatedAt, locale, timezone);
  return item.errorCode || "—";
}

function countActiveFilters(filters: VerifactuAdminSubmissionFilters) {
  return [filters.dateFrom, filters.dateTo, filters.status, filters.documentType, filters.operation, filters.documentNumber]
    .filter((value) => Boolean(value)).length;
}

function SandboxPanel({
  locale,
  fiscalStatus,
  sandboxStatus,
  summary,
  canManageSandbox,
  sandboxOutcome,
  sandboxScenarioWorking,
  sandboxDispatchWorking,
  sandboxError,
  onOutcomeChange,
  onConfigure,
  onDispatch,
  t
}: {
  locale: LocaleCode;
  fiscalStatus: Awaited<ReturnType<typeof loadFiscalStatus>> | null;
  sandboxStatus: Awaited<ReturnType<typeof loadFiscalSandboxStatus>> | null;
  summary: VerifactuAdminSummary | null;
  canManageSandbox: boolean;
  sandboxOutcome: string;
  sandboxScenarioWorking: boolean;
  sandboxDispatchWorking: boolean;
  sandboxError: boolean;
  onOutcomeChange: (outcome: string) => void;
  onConfigure: () => void;
  onDispatch: () => void;
  t: Translator;
}) {
  return (
    <section className="gestion-verifactu-sandbox" aria-label={t("verifactu.management.sandboxTitle")}>
      <strong>{t("verifactu.management.sandboxBanner")}</strong>
      {fiscalStatus && <span>{t("verifactu.management.sandboxFiscalMode")}: {fiscalModeLabel(fiscalStatus.mode, t)}</span>}
      {sandboxStatus && <span>{t("verifactu.management.sandboxMode")}: {fiscalTransportLabel(sandboxStatus.transportMode, t)} / {fiscalEndpointLabel(sandboxStatus.endpointEnvironment, t)}</span>}
      {summary && <span>{t("verifactu.management.sandboxQueue")}: {count(summary, "PENDIENTE") + count(summary, "ENVIANDO") + count(summary, "ENVIADO")}</span>}
      {summary && <span>{t("verifactu.management.sandboxCertificate")}: {certificateLabel(summary, t)}</span>}
      <span>{t("verifactu.management.sandboxManualDispatch")}: {t("verifactu.management.sandboxManualDispatchReady")}</span>
      {fiscalStatus?.scheduledTransition && <span>
        {t("verifactu.management.sandboxScheduledTransition")}: {fiscalModeLabel(fiscalStatus.scheduledTransition.newMode, t)} ({formatDateTime(fiscalStatus.scheduledTransition.effectiveAt, locale, fiscalStatus.timezone ?? null)})
      </span>}
      {canManageSandbox && sandboxStatus && <>
        <label htmlFor="verifactu-sandbox-scenario">
          <span>{t("verifactu.management.sandboxScenario")}</span>
          <select id="verifactu-sandbox-scenario" disabled={sandboxScenarioWorking || sandboxDispatchWorking} value={sandboxOutcome} onChange={(event) => onOutcomeChange(event.target.value)}>
            {(["ACCEPTED", "ACCEPTED_WITH_ERRORS", "REJECTED", "DUPLICATE", "TIMEOUT", "HTTP_ERROR", "INVALID_RESPONSE"] as const).map((outcome) => <option key={outcome} value={outcome}>{sandboxOutcomeLabel(outcome, t)}</option>)}
          </select>
        </label>
        <button type="button" disabled={sandboxScenarioWorking || sandboxDispatchWorking} onClick={onConfigure}>{sandboxScenarioWorking ? t("verifactu.management.sandboxWorking") : t("verifactu.management.sandboxApply")}</button>
        <button type="button" disabled={sandboxScenarioWorking || sandboxDispatchWorking} onClick={onDispatch}>{sandboxDispatchWorking ? t("verifactu.management.sandboxWorking") : t("verifactu.management.sandboxDispatch")}</button>
      </>}
      {sandboxError && <span role="alert">{t("verifactu.management.sandboxError")}</span>}
    </section>
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

export function formatAge(value: string, locale: LocaleCode, t: Translator) {
  const timestamp = new Date(value).getTime();
  if (!Number.isFinite(timestamp)) return "—";
  const minutes = Math.max(0, Math.floor((Date.now() - timestamp) / 60_000));
  if (minutes < 60) return ageLabel("minute", minutes, locale, t);
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return ageLabel("hour", hours, locale, t);
  return ageLabel("day", Math.floor(hours / 24), locale, t);
}

function ageLabel(unit: "minute" | "hour" | "day", count: number, _locale: LocaleCode, t: Translator) {
  const key = `verifactu.management.age.${unit}.${count === 1 ? "one" : "many"}`;
  return t(key).replace("{count}", String(count));
}

function sandboxOutcomeLabel(outcome: string, t: Translator) {
  const key = `verifactu.management.sandboxOutcome.${outcome}`;
  const translated = t(key);
  return translated === key ? humanize(outcome) : translated;
}

function viewPanelLabel(view: ViewKey, t: Translator) {
  const labels: Record<ViewKey, string> = {
    summary: "verifactu.management.summary",
    records: "verifactu.management.recordsView",
    queue: "verifactu.management.queue",
    defective: "verifactu.management.defective",
    compliance: "verifactu.management.compliance",
    mode: "verifactu.management.modeControl",
    certificate: "verifactu.management.certificate",
    diagnostics: "verifactu.management.diagnostics"
  };
  return t(labels[view]);
}

function fiscalModeLabel(mode: string, t: Translator) {
  const key = `verifactu.management.fiscalMode.${mode}`;
  const translated = t(key);
  return translated === key ? humanize(mode) : translated;
}

function fiscalRuntimeLabel(runtime: string, t: Translator) {
  const key = `verifactu.management.fiscalRuntime.${runtime}`;
  const translated = t(key);
  return translated === key ? humanize(runtime) : translated;
}

function fiscalEndpointLabel(endpoint: string, t: Translator) {
  const key = `verifactu.management.fiscalEndpoint.${endpoint}`;
  const translated = t(key);
  return translated === key ? humanize(endpoint) : translated;
}

function fiscalTransportLabel(transport: string, t: Translator) {
  const key = `verifactu.management.fiscalTransport.${transport}`;
  const translated = t(key);
  return translated === key ? humanize(transport) : translated;
}
