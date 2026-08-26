import { useEffect, useRef, useState, type FormEvent } from "react";
import {
  TableLayoutHeaderCell,
  useTableLayoutPreference,
  visibleTableColumns,
  type LocaleCode,
  type TableColumnDefinition
} from "@tpverp/app-common";
import {
  loadFiscalEventsCursor,
  loadFiscalExportHistoryCursor,
  loadFiscalRequiredSubmissionsCursor,
  registerFiscalRequiredSubmission,
  createFiscalIntegrityJob,
  loadFiscalIntegrityJobStatus,
  retryFiscalIntegrityJob,
  type FiscalEvent,
  type FiscalExportKind,
  type FiscalExportHistory,
  type FiscalIntegrityCheck,
  type FiscalIntegrityJob,
  type FiscalMode,
  type FiscalRequiredSubmission,
  type FiscalRequiredSubmissionHistory,
  type FiscalEventCursorPage,
  type FiscalHistoryCursorPage
} from "./verifactuManagementApi";
import {
  formatVerifactuDateTime,
  humanizeVerifactuValue,
  type VerifactuTranslator
} from "./verifactuPresentation";
import { datetimeLocalToIso, isValidDatetimeLocal } from "./fiscalDateTime";
import { FiscalWorkspaceDialog } from "./FiscalWorkspaceDialog";
import { FiscalExportJobsList, useFiscalExportJobs } from "./fiscalExportJobs";

type FiscalEventColumn = "sequence" | "eventType" | "fiscalMode" | "generatedAt" | "signature" | "hash";
type FiscalExportColumn = "kind" | "generatedAt" | "records" | "hash";
type FiscalRequirementColumn = "reference" | "status" | "generatedAt" | "actions";

export const fiscalComplianceEventsTableKey = "gestion.verifactu.events";
export const fiscalComplianceExportHistoryTableKey = "gestion.verifactu.export-history";
export const fiscalComplianceRequirementsTableKey = "gestion.verifactu.requirements";

export const fiscalComplianceEventColumnDefinitions = [
  { key: "sequence", defaultWidth: 100 },
  { key: "eventType", defaultWidth: 230 },
  { key: "fiscalMode", defaultWidth: 170 },
  { key: "generatedAt", defaultWidth: 190 },
  { key: "signature", defaultWidth: 130 },
  { key: "hash", defaultWidth: 210 }
] as const satisfies readonly TableColumnDefinition<FiscalEventColumn>[];

export const fiscalComplianceExportColumnDefinitions = [
  { key: "kind", defaultWidth: 220 },
  { key: "generatedAt", defaultWidth: 190 },
  { key: "records", defaultWidth: 120 },
  { key: "hash", defaultWidth: 240 }
] as const satisfies readonly TableColumnDefinition<FiscalExportColumn>[];

export const fiscalComplianceRequirementColumnDefinitions = [
  { key: "reference", defaultWidth: 220 },
  { key: "status", defaultWidth: 170 },
  { key: "generatedAt", defaultWidth: 190 },
  { key: "actions", defaultWidth: 180 }
] as const satisfies readonly TableColumnDefinition<FiscalRequirementColumn>[];

type FiscalComplianceViewProps = {
  locale: LocaleCode;
  token?: string;
  mode: FiscalMode;
  timezone?: string | null;
  username: string;
  canManage: boolean;
  canAttendRequirements?: boolean;
  revision: number;
  t: VerifactuTranslator;
  onChanged: () => void;
};

export function FiscalComplianceView({
  locale,
  token,
  mode,
  timezone,
  username,
  canManage,
  canAttendRequirements = false,
  revision,
  t,
  onChanged
}: FiscalComplianceViewProps) {
  const fiscalTimezone = timezone?.trim() || "";
  const [events, setEvents] = useState<FiscalEvent[]>([]);
  const [exportHistory, setExportHistory] = useState<FiscalExportHistory[]>([]);
  const [requirements, setRequirements] = useState<FiscalRequiredSubmissionHistory[]>([]);
  const [eventsLoading, setEventsLoading] = useState(true);
  const [eventsError, setEventsError] = useState(false);
  const [exportHistoryLoading, setExportHistoryLoading] = useState(true);
  const [exportHistoryError, setExportHistoryError] = useState(false);
  const [requirementsLoading, setRequirementsLoading] = useState(true);
  const [requirementsError, setRequirementsError] = useState(false);
  const [eventsPage, setEventsPage] = useState<FiscalEventCursorPage | null>(null);
  const [exportHistoryPage, setExportHistoryPage] = useState<FiscalHistoryCursorPage<FiscalExportHistory> | null>(null);
  const [requirementsPage, setRequirementsPage] = useState<FiscalHistoryCursorPage<FiscalRequiredSubmissionHistory> | null>(null);
  const [eventsCursor, setEventsCursor] = useState<string | null>(null);
  const [exportHistoryCursor, setExportHistoryCursor] = useState<string | null>(null);
  const [requirementsCursor, setRequirementsCursor] = useState<string | null>(null);
  const [historyRefresh, setHistoryRefresh] = useState(0);
  const [integrity, setIntegrity] = useState<FiscalIntegrityCheck | null>(null);
  const [integrityJob, setIntegrityJob] = useState<FiscalIntegrityJob | null>(null);
  const [integrityWorking, setIntegrityWorking] = useState(false);
  const [integrityError, setIntegrityError] = useState(false);
  const [exportKind, setExportKind] = useState<FiscalExportKind>("BILLING");
  const [periodStart, setPeriodStart] = useState("");
  const [periodEnd, setPeriodEnd] = useState("");
  const [exportError, setExportError] = useState(false);
  const [exportSuccess, setExportSuccess] = useState(false);
  const [exportDialogOpen, setExportDialogOpen] = useState(false);
  const [requirementReference, setRequirementReference] = useState("");
  const [requirement, setRequirement] = useState<FiscalRequiredSubmission | null>(null);
  const [requirementPeriodStart, setRequirementPeriodStart] = useState("");
  const [requirementPeriodEnd, setRequirementPeriodEnd] = useState("");
  const [requirementWorking, setRequirementWorking] = useState(false);
  const [requirementError, setRequirementError] = useState(false);
  const exportJobs = useFiscalExportJobs(token, canManage);
  const requestId = useRef(0);
  const requestController = useRef<AbortController | null>(null);
  const integrityController = useRef<AbortController | null>(null);
  const observedRequirementJobs = useRef(new Set<string>());
  const eventsTableLayout = useTableLayoutPreference({
    app: "gestion",
    username,
    accessToken: token,
    tableKey: fiscalComplianceEventsTableKey,
    definitions: fiscalComplianceEventColumnDefinitions
  });
  const exportHistoryTableLayout = useTableLayoutPreference({
    app: "gestion",
    username,
    accessToken: token,
    tableKey: fiscalComplianceExportHistoryTableKey,
    definitions: fiscalComplianceExportColumnDefinitions
  });
  const requirementsTableLayout = useTableLayoutPreference({
    app: "gestion",
    username,
    accessToken: token,
    tableKey: fiscalComplianceRequirementsTableKey,
    definitions: fiscalComplianceRequirementColumnDefinitions
  });
  const visibleEventColumns = visibleTableColumns(eventsTableLayout.layout);
  const visibleExportColumns = visibleTableColumns(exportHistoryTableLayout.layout);
  const visibleRequirementColumns = visibleTableColumns(requirementsTableLayout.layout);

  useEffect(() => {
    requestController.current?.abort();
    const controller = new AbortController();
    requestController.current = controller;
    const currentRequest = ++requestId.current;
    setEventsLoading(true);
    setExportHistoryLoading(true);
    setRequirementsLoading(true);
    setEventsError(false);
    setExportHistoryError(false);
    setRequirementsError(false);

    const load = async <T,>(promise: Promise<T>, onSuccess: (value: T) => void, onFailure: () => void, onDone: () => void) => {
      try {
        const value = await promise;
        if (!controller.signal.aborted && currentRequest === requestId.current) onSuccess(value);
      } catch {
        if (!controller.signal.aborted && currentRequest === requestId.current) onFailure();
      } finally {
        if (!controller.signal.aborted && currentRequest === requestId.current) onDone();
      }
    };

    void load(loadFiscalEventsCursor(token, 50, eventsCursor, controller.signal),
      (page) => { setEventsPage(page); setEvents(page.items); },
      () => { setEventsPage(null); setEvents([]); setEventsError(true); },
      () => setEventsLoading(false));
    void load(loadFiscalExportHistoryCursor(token, 50, exportHistoryCursor, controller.signal),
      (page) => { setExportHistoryPage(page); setExportHistory(page.items); },
      () => { setExportHistoryPage(null); setExportHistory([]); setExportHistoryError(true); },
      () => setExportHistoryLoading(false));
    void load(loadFiscalRequiredSubmissionsCursor(token, 50, requirementsCursor, controller.signal),
      (page) => { setRequirementsPage(page); setRequirements(page.items); },
      () => { setRequirementsPage(null); setRequirements([]); setRequirementsError(true); },
      () => setRequirementsLoading(false));

    return () => controller.abort();
  }, [revision, token, eventsCursor, exportHistoryCursor, requirementsCursor, historyRefresh]);

  useEffect(() => () => integrityController.current?.abort(), []);

  useEffect(() => {
    const terminalJob = exportJobs.jobs.find((job) =>
      job.requiredSubmissionId && (job.status === "COMPLETED" || job.status === "FAILED")
        && !observedRequirementJobs.current.has(`${job.id}:${job.status}`)
    );
    if (!terminalJob) return;
    observedRequirementJobs.current.add(`${terminalJob.id}:${terminalJob.status}`);
    setHistoryRefresh((value) => value + 1);
  }, [exportJobs.jobs]);

  useEffect(() => {
    if (!requirement) return;
    const current = requirements.find((item) => item.id === requirement.id);
    if (current) setRequirement(current);
  }, [requirements, requirement?.id]);

  async function checkIntegrity(retryId?: string) {
    integrityController.current?.abort();
    const controller = new AbortController();
    integrityController.current = controller;
    setIntegrityWorking(true);
    setIntegrityError(false);
    setIntegrity(null);
    try {
      let job = retryId
        ? await retryFiscalIntegrityJob(retryId, token, controller.signal)
        : await createFiscalIntegrityJob(token, controller.signal);
      setIntegrityJob(job);
      let delay = 600;
      while (job.status === "QUEUED" || job.status === "RUNNING") {
        await waitForIntegrityPoll(delay, controller.signal);
        job = await loadFiscalIntegrityJobStatus(job.id, token, controller.signal);
        setIntegrityJob(job);
        delay = Math.min(3000, Math.round(delay * 1.5));
      }
      if (job.status !== "COMPLETED") {
        setIntegrityError(true);
      } else {
        setIntegrity({
          checkedAt: job.completedAt ?? job.updatedAt,
          mode: job.mode,
          ok: job.anomaliesTotal === 0,
          anomalies: job.evidenceCodes,
          billingRecordsChecked: job.billingChecked,
          eventRecordsChecked: job.eventsChecked,
          anomaliesTotal: job.anomaliesTotal,
          billingAnomalies: job.billingAnomalies,
          eventAnomalies: job.eventAnomalies
        });
        onChanged();
      }
    } catch {
      if (controller.signal.aborted) return;
      setIntegrity(null);
      setIntegrityError(true);
    } finally {
      setIntegrityWorking(false);
    }
  }

  function waitForIntegrityPoll(milliseconds: number, signal: AbortSignal) {
    return new Promise<void>((resolve, reject) => {
      const timer = window.setTimeout(resolve, milliseconds);
      signal.addEventListener("abort", () => {
        window.clearTimeout(timer);
        reject(new DOMException("Aborted", "AbortError"));
      }, { once: true });
    });
  }

  async function exportRecords(event: FormEvent) {
    event.preventDefault();
    if (!validRequiredPeriod(periodStart, periodEnd, fiscalTimezone)) {
      setExportError(true);
      return;
    }
    setExportError(false);
    setExportSuccess(false);
    const job = await exportJobs.create({
      kind: exportKind,
      scope: "PERIOD",
      periodStart: periodStart ? toOffsetDateTime(periodStart, fiscalTimezone) : null,
      periodEnd: periodEnd ? toOffsetDateTime(periodEnd, fiscalTimezone) : null,
      recordIds: []
    });
    if (job) {
      setExportSuccess(true);
      onChanged();
    } else {
      setExportError(true);
    }
  }

  async function registerRequirement(event: FormEvent) {
    event.preventDefault();
    if (!requirementReference.trim()) return;
    setRequirementWorking(true);
    setRequirementError(false);
    try {
      setRequirement(await registerFiscalRequiredSubmission(requirementReference, token));
      onChanged();
    } catch {
      setRequirementError(true);
    } finally {
      setRequirementWorking(false);
    }
  }

  async function exportRequirement() {
    if (!requirement || !validRequiredPeriod(requirementPeriodStart, requirementPeriodEnd, fiscalTimezone)) {
      setRequirementError(true);
      return;
    }
    setRequirementWorking(true);
    setRequirementError(false);
    try {
      const job = await exportJobs.createRequiredSubmissionJob(
        requirement.id,
        toOffsetDateTime(requirementPeriodStart, fiscalTimezone),
        toOffsetDateTime(requirementPeriodEnd, fiscalTimezone)
      );
      if (!job) {
        setRequirementError(true);
        return;
      }
      // The durable worker marks the request EXPORTADO only after the ZIP and
      // fiscal export evidence have committed. Keep the requirement pending in
      // this view until the next refresh; the job is visible immediately.
      setExportSuccess(true);
      setExportDialogOpen(true);
      void exportJobs.refresh();
      setRequirementWorking(false);
      onChanged();
    } catch {
      setRequirementError(true);
    } finally {
      setRequirementWorking(false);
    }
  }

  return (
    <div className="gestion-fiscal-compliance">
      {!fiscalTimezone && <p className="gestion-verifactu-message error" role="alert">{t("verifactu.management.timezoneUnavailable")}</p>}
      <section className="gestion-verifactu-panel gestion-fiscal-integrity">
        <header>
          <div>
            <span className="gestion-eyebrow">{t("verifactu.compliance.controlEyebrow")}</span>
            <h3>{t("verifactu.compliance.integrityTitle")}</h3>
          </div>
          {canManage && <div className="gestion-fiscal-integrity-actions">
            <button type="button" className="primary" disabled={integrityWorking} onClick={() => void checkIntegrity()}>
              {integrityWorking ? t("verifactu.compliance.checking") : t("verifactu.compliance.runIntegrity")}
            </button>
            {integrityJob?.status === "FAILED" && <button type="button" disabled={integrityWorking} onClick={() => void checkIntegrity(integrityJob.id)}>
              {t("verifactu.exportJobs.retryRequest")}
            </button>}
          </div>}
        </header>
        <p>{t("verifactu.compliance.integrityHint")}</p>
        {!canManage && <p className="gestion-verifactu-readonly-note">{t("verifactu.compliance.managePermission")}</p>}
        {integrityError && <p className="gestion-verifactu-message error" role="alert">{t("verifactu.compliance.integrityError")}</p>}
        {integrityJob && (integrityJob.status === "QUEUED" || integrityJob.status === "RUNNING") && (
          <div className="gestion-fiscal-integrity-progress" role="status">
            <strong>{integrityJob.status === "QUEUED" ? t("verifactu.exportJobs.statusQueued") : t("verifactu.exportJobs.statusRunning")}</strong>
            <span>{integrityJob.billingChecked.toLocaleString()} / {integrityJob.billingSnapshotSequence.toLocaleString()} {t("verifactu.compliance.billingChecked").toLocaleLowerCase()}</span>
            <span>{integrityJob.eventsChecked.toLocaleString()} / {integrityJob.eventSnapshotSequence.toLocaleString()} {t("verifactu.compliance.eventsChecked").toLocaleLowerCase()}</span>
          </div>
        )}
        {integrity && (
          <div className={`gestion-fiscal-integrity-result ${integrity.ok ? "is-ok" : "has-anomalies"}`} role="status">
            <strong>{integrity.ok ? t("verifactu.compliance.integrityOk") : t("verifactu.compliance.integrityAnomalies")}</strong>
            <span>{formatVerifactuDateTime(integrity.checkedAt, locale, fiscalTimezone)}</span>
            <dl>
              <div><dt>{t("verifactu.compliance.billingChecked")}</dt><dd>{integrity.billingRecordsChecked}</dd></div>
              <div><dt>{t("verifactu.compliance.eventsChecked")}</dt><dd>{integrity.eventRecordsChecked}</dd></div>
            </dl>
            {integrity.anomalies.length > 0 && (
              <ul>{integrity.anomalies.map((anomaly) => <li key={anomaly}>{anomaly}</li>)}</ul>
            )}
          </div>
        )}
      </section>

      <section className="gestion-verifactu-panel gestion-fiscal-events">
        <header>
          <div>
            <span className="gestion-eyebrow">{t("verifactu.compliance.auditEyebrow")}</span>
            <h3>{t("verifactu.compliance.eventsTitle")}</h3>
          </div>
          <span>{t("verifactu.compliance.lastEvents")}</span>
        </header>
        {eventsError ? (
          <div className="gestion-verifactu-message error" role="alert">{t("verifactu.compliance.eventsError")} <button type="button" onClick={() => setHistoryRefresh((value) => value + 1)}>{t("verifactu.exportJobs.retry")}</button></div>
        ) : eventsLoading ? (
          <div className="gestion-verifactu-message">{t("verifactu.compliance.eventsLoading")}</div>
        ) : events.length === 0 ? (
          <div className="gestion-verifactu-message">{t("verifactu.compliance.eventsEmpty")}</div>
        ) : (
          <>
          <div className="gestion-verifactu-table-scroll">
            <table className="gestion-verifactu-table">
              <colgroup>{visibleEventColumns.map((column) => <col key={column.key} style={{ width: `${column.width}px` }} />)}</colgroup>
              <thead><tr>
                {visibleEventColumns.map((column) => (
                  <TableLayoutHeaderCell
                    key={column.key}
                    column={column}
                    resizeLabel={`${t("verifactu.ui.resizeColumn")} ${eventColumnLabel(column.key, t)}`}
                    onReorder={eventsTableLayout.reorderColumns}
                    onMove={eventsTableLayout.moveColumn}
                    onResize={eventsTableLayout.resizeColumn}
                  >
                    {eventColumnLabel(column.key, t)}
                  </TableLayoutHeaderCell>
                ))}
              </tr></thead>
              <tbody>{events.map((item) => (
                <tr key={item.id}>
                  {visibleEventColumns.map((column) => (
                    <td className={column.key === "sequence" ? "numeric" : undefined} key={column.key}>
                      {column.key === "sequence" && item.sequence}
                      {column.key === "eventType" && <strong>{eventTypeLabel(item.type, t)}</strong>}
                      {column.key === "fiscalMode" && fiscalModeLabel(item.fiscalMode, t)}
                      {column.key === "generatedAt" && formatVerifactuDateTime(item.generatedAt, locale, fiscalTimezone)}
                      {column.key === "signature" && (item.signed ? t("verifactu.compliance.signed") : t("verifactu.compliance.notSigned"))}
                      {column.key === "hash" && <ComplianceHashValue value={item.hash} t={t} />}
                    </td>
                  ))}
                </tr>
              ))}</tbody>
            </table>
          </div>
          <FiscalCursorNavigation page={eventsPage} visibleCount={events.length} onPrevious={() => setEventsCursor(eventsPage?.previousCursor ?? null)} onNext={() => setEventsCursor(eventsPage?.nextCursor ?? null)} t={t} />
          </>
        )}
      </section>

      <section className="gestion-verifactu-panel gestion-fiscal-export">
        <header>
          <div><span className="gestion-eyebrow">{t("verifactu.compliance.conservationEyebrow")}</span><h3>{t("verifactu.compliance.exportTitle")}</h3></div>
          {canManage ? (
            <button type="button" className="primary" aria-haspopup="dialog" aria-expanded={exportDialogOpen} disabled={!fiscalTimezone} onClick={() => { setExportError(false); setExportSuccess(false); setExportDialogOpen(true); void exportJobs.refresh(); }}>
              {t("verifactu.ui.export")}
            </button>
          ) : <span className="gestion-verifactu-readonly-note">{t("verifactu.compliance.managePermission")}</span>}
        </header>
        <p>{t("verifactu.compliance.exportHint")}</p>
        {exportHistoryError && <p className="gestion-verifactu-message error" role="alert">{t("verifactu.compliance.exportHistoryError")} <button type="button" onClick={() => setHistoryRefresh((value) => value + 1)}>{t("verifactu.exportJobs.retry")}</button></p>}
        {exportHistoryLoading && <p className="gestion-verifactu-message" role="status">{t("verifactu.exportJobs.loading")}</p>}
        {!exportHistoryLoading && !exportHistoryError && exportHistory.length === 0 && <p className="gestion-verifactu-message">{t("verifactu.exportJobs.empty")}</p>}
        {!exportHistoryLoading && !exportHistoryError && exportHistory.length > 0 && <><div className="gestion-verifactu-table-scroll">
          <table className="gestion-verifactu-table">
            <colgroup>{visibleExportColumns.map((column) => <col key={column.key} style={{ width: `${column.width}px` }} />)}</colgroup>
            <thead><tr>{visibleExportColumns.map((column) => (
              <TableLayoutHeaderCell
                key={column.key}
                column={column}
                resizeLabel={`${t("verifactu.ui.resizeColumn")} ${exportColumnLabel(column.key, t)}`}
                onReorder={exportHistoryTableLayout.reorderColumns}
                onMove={exportHistoryTableLayout.moveColumn}
                onResize={exportHistoryTableLayout.resizeColumn}
              >
                {exportColumnLabel(column.key, t)}
              </TableLayoutHeaderCell>
            ))}</tr></thead>
            <tbody>{exportHistory.map((item) => <tr key={item.exportId}>
              {visibleExportColumns.map((column) => (
                <td className={column.key === "records" ? "numeric" : undefined} key={column.key}>
                  {column.key === "kind" && (item.kind === "BILLING" ? t("verifactu.compliance.exportBilling") : t("verifactu.compliance.exportEvents"))}
                  {column.key === "generatedAt" && formatVerifactuDateTime(item.exportedAt, locale, fiscalTimezone)}
                  {column.key === "records" && item.recordCount}
                  {column.key === "hash" && <ComplianceHashValue value={item.contentHash} t={t} />}
                </td>
              ))}
            </tr>)}</tbody>
          </table>
        </div><FiscalCursorNavigation page={exportHistoryPage} visibleCount={exportHistory.length} onPrevious={() => setExportHistoryCursor(exportHistoryPage?.previousCursor ?? null)} onNext={() => setExportHistoryCursor(exportHistoryPage?.nextCursor ?? null)} t={t} /></>}
      </section>

      {exportDialogOpen && canManage && (
        <FiscalWorkspaceDialog
          id="fiscal-export-dialog"
          title={t("verifactu.ui.exportDialogTitle")}
          closeLabel={t("verifactu.ui.close")}
          onClose={() => setExportDialogOpen(false)}
          variant="modal"
          purpose="export"
          closeDisabled={exportJobs.creating}
          className="gestion-fiscal-export-dialog"
          footer={<>
            <button type="button" disabled={exportJobs.creating} onClick={() => setExportDialogOpen(false)}>{t("verifactu.ui.close")}</button>
            <button type="submit" form="fiscal-export-dialog-form" className="primary" disabled={exportJobs.creating || !fiscalTimezone}>
              {exportJobs.creating ? t("verifactu.compliance.exporting") : t("verifactu.compliance.createExport")}
            </button>
          </>}
        >
          <form id="fiscal-export-dialog-form" onSubmit={(event) => void exportRecords(event)}>
            <ExportFields
              exportKind={exportKind}
              onKindChange={setExportKind}
              periodStart={periodStart}
              periodEnd={periodEnd}
              onPeriodStartChange={setPeriodStart}
              onPeriodEndChange={setPeriodEnd}
              disabled={exportJobs.creating || !fiscalTimezone}
              t={t}
            />
            <p>{t("verifactu.exportJobs.periodRegulatory")}</p>
            {exportSuccess && <p className="gestion-form-success" role="status">{t("verifactu.exportJobs.created")}</p>}
            {exportError && <p className="gestion-form-error" role="alert">{t("verifactu.compliance.exportError")}</p>}
          </form>
          <FiscalExportJobsList jobs={exportJobs.jobs} loading={exportJobs.loading} error={exportJobs.error} downloadId={exportJobs.downloadId} downloadError={exportJobs.downloadError} onRefresh={() => void exportJobs.refresh()} onRetry={(id) => void exportJobs.retry(id)} onDownload={(job) => void exportJobs.download(job)} t={t} />
        </FiscalWorkspaceDialog>
      )}

      {mode === "NO_VERIFACTU" && (
        <section className="gestion-verifactu-panel gestion-fiscal-requirement">
          <header><div><span className="gestion-eyebrow">{t("verifactu.compliance.aeatEyebrow")}</span><h3>{t("verifactu.compliance.requirementTitle")}</h3></div></header>
          <p>{t("verifactu.compliance.requirementHint")}</p>
          {canAttendRequirements ? <>
            <form onSubmit={registerRequirement}>
              <label>
                <span>{t("verifactu.compliance.requirementReference")}</span>
                <input maxLength={18} value={requirementReference} onChange={(event) => setRequirementReference(event.target.value)} />
              </label>
              <button type="submit" disabled={requirementWorking || !requirementReference.trim()}>{t("verifactu.compliance.registerRequirement")}</button>
            </form>
            <div className="gestion-fiscal-requirement-period">
              <label><span>{t("verifactu.management.dateFrom")}</span><input disabled={!fiscalTimezone} type="datetime-local" value={requirementPeriodStart} onChange={(event) => setRequirementPeriodStart(event.target.value)} /></label>
              <label><span>{t("verifactu.management.dateTo")}</span><input disabled={!fiscalTimezone} type="datetime-local" value={requirementPeriodEnd} onChange={(event) => setRequirementPeriodEnd(event.target.value)} /></label>
            </div>
          </> : <p className="gestion-verifactu-readonly-note">{t("verifactu.compliance.requirementPermission")}</p>}
          {canAttendRequirements && requirement && (
            <div className="gestion-fiscal-requirement-ready" role="status">
              <strong>{requirement.reference}</strong>
              <span>{requirementStatusLabel(requirement.status, t)}</span>
              {requirement.status === "PENDIENTE" && (
                <button type="button" className="primary" disabled={requirementWorking || !validRequiredPeriod(requirementPeriodStart, requirementPeriodEnd, fiscalTimezone)} onClick={() => void exportRequirement()}>
                  {t("verifactu.compliance.attendRequirement")}
                </button>
              )}
            </div>
          )}
          {requirementsError && <p className="gestion-verifactu-message error" role="alert">{t("verifactu.compliance.requirementsError")} <button type="button" onClick={() => setHistoryRefresh((value) => value + 1)}>{t("verifactu.exportJobs.retry")}</button></p>}
          {requirementsLoading && <p className="gestion-verifactu-message" role="status">{t("verifactu.exportJobs.loading")}</p>}
          {!requirementsLoading && !requirementsError && requirements.length === 0 && <p className="gestion-verifactu-message">{t("verifactu.exportJobs.empty")}</p>}
          {!requirementsLoading && !requirementsError && requirements.length > 0 && <><div className="gestion-verifactu-table-scroll">
            <table className="gestion-verifactu-table">
              <colgroup>{visibleRequirementColumns.map((column) => <col key={column.key} style={{ width: `${column.width}px` }} />)}</colgroup>
              <thead><tr>{visibleRequirementColumns.map((column) => (
                <TableLayoutHeaderCell
                  key={column.key}
                  column={column}
                  resizeLabel={`${t("verifactu.ui.resizeColumn")} ${requirementColumnLabel(column.key, t)}`}
                  onReorder={requirementsTableLayout.reorderColumns}
                  onMove={requirementsTableLayout.moveColumn}
                  onResize={requirementsTableLayout.resizeColumn}
                >
                  {requirementColumnLabel(column.key, t)}
                </TableLayoutHeaderCell>
              ))}</tr></thead>
              <tbody>{requirements.map((item) => <tr key={item.id}>
                {visibleRequirementColumns.map((column) => (
                  <td key={column.key}>
                    {column.key === "reference" && <strong>{item.reference}</strong>}
                    {column.key === "status" && requirementStatusLabel(item.status, t)}
                    {column.key === "generatedAt" && formatVerifactuDateTime(item.requestedAt, locale, fiscalTimezone)}
                    {column.key === "actions" && canAttendRequirements && item.status === "PENDIENTE" && <button type="button" onClick={() => {
                      setRequirement(item);
                      setRequirementReference(item.reference);
                    }}>{t("verifactu.compliance.selectRequirement")}</button>}
                  </td>
                ))}
              </tr>)}</tbody>
            </table>
          </div><FiscalCursorNavigation page={requirementsPage} visibleCount={requirements.length} onPrevious={() => setRequirementsCursor(requirementsPage?.previousCursor ?? null)} onNext={() => setRequirementsCursor(requirementsPage?.nextCursor ?? null)} t={t} /></>}
          {requirementError && <p className="gestion-form-error" role="alert">{t("verifactu.compliance.requirementError")}</p>}
        </section>
      )}
    </div>
  );
}

function FiscalCursorNavigation({
  page,
  visibleCount,
  onPrevious,
  onNext,
  t
}: {
  page: { hasPrevious: boolean; hasNext: boolean } | null;
  visibleCount: number;
  onPrevious: () => void;
  onNext: () => void;
  t: VerifactuTranslator;
}) {
  if (!page || (!page.hasPrevious && !page.hasNext)) return null;
  return <nav className="gestion-fiscal-cursor-navigation" aria-label={t("verifactu.compliance.paginationLabel")}>
    <button type="button" disabled={!page.hasPrevious} onClick={onPrevious}>{t("verifactu.management.previous")}</button>
    <span aria-live="polite">{t("verifactu.compliance.visibleRecords").replace("{count}", visibleCount.toLocaleString())}</span>
    <button type="button" disabled={!page.hasNext} onClick={onNext}>{t("verifactu.management.next")}</button>
  </nav>;
}

function ExportFields({
  exportKind,
  onKindChange,
  periodStart,
  periodEnd,
  onPeriodStartChange,
  onPeriodEndChange,
  disabled,
  t
}: {
  exportKind: FiscalExportKind;
  onKindChange: (kind: FiscalExportKind) => void;
  periodStart: string;
  periodEnd: string;
  onPeriodStartChange: (value: string) => void;
  onPeriodEndChange: (value: string) => void;
  disabled: boolean;
  t: VerifactuTranslator;
}) {
  return <div className="gestion-fiscal-export-fields">
    <label>
      <span>{t("verifactu.compliance.exportKind")}</span>
      <select disabled={disabled} value={exportKind} onChange={(event) => onKindChange(event.target.value as FiscalExportKind)}>
        <option value="BILLING">{t("verifactu.compliance.exportBilling")}</option>
        <option value="EVENTS">{t("verifactu.compliance.exportEvents")}</option>
      </select>
    </label>
    <label><span>{t("verifactu.management.dateFrom")}</span><input disabled={disabled} type="datetime-local" value={periodStart} onChange={(event) => onPeriodStartChange(event.target.value)} /></label>
    <label><span>{t("verifactu.management.dateTo")}</span><input disabled={disabled} type="datetime-local" value={periodEnd} onChange={(event) => onPeriodEndChange(event.target.value)} /></label>
  </div>;
}

function validRequiredPeriod(start: string, end: string, timezone: string) {
  if (!start || !end || !isValidDatetimeLocal(start, timezone) || !isValidDatetimeLocal(end, timezone)) return false;
  try {
    return new Date(datetimeLocalToIso(end, timezone)).getTime() >= new Date(datetimeLocalToIso(start, timezone)).getTime();
  } catch {
    return false;
  }
}

function toOffsetDateTime(value: string, timezone: string) {
  return datetimeLocalToIso(value, timezone);
}

function shortHash(value: string) {
  return value.length <= 16 ? value : `${value.slice(0, 8)}…${value.slice(-8)}`;
}

function ComplianceHashValue({ value, t }: { value: string | null | undefined; t: VerifactuTranslator }) {
  const [state, setState] = useState<"idle" | "copied" | "error">("idle");
  if (!value) return <span>—</span>;
  const hashValue = value;
  async function copy() {
    try {
      if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(hashValue);
      else {
        const area = document.createElement("textarea");
        area.value = hashValue;
        area.style.position = "fixed";
        area.style.opacity = "0";
        document.body.appendChild(area);
        area.select();
        document.execCommand("copy");
        area.remove();
      }
      setState("copied");
    } catch {
      setState("error");
    }
  }
  return <details className="gestion-verifactu-compliance-hash">
    <summary aria-label={`${t("verifactu.compliance.showFullHash")} ${shortHash(hashValue)}`}><code>{shortHash(hashValue)}</code></summary>
    <div className="gestion-verifactu-compliance-hash-full">
      <code>{hashValue}</code>
      <button type="button" aria-label={`${t("verifactu.records.copyHash")} ${hashValue}`} onClick={() => void copy()}>{t("verifactu.records.copyHash")}</button>
      {state !== "idle" && <span role="status" aria-live="polite">{state === "copied" ? t("verifactu.records.copiedHash") : t("verifactu.records.copyHashError")}</span>}
    </div>
  </details>;
}

function fiscalModeLabel(mode: string, t: VerifactuTranslator) {
  const key = `verifactu.management.fiscalMode.${mode}`;
  const translated = t(key);
  return translated === key ? humanizeVerifactuValue(mode) : translated;
}

function eventTypeLabel(type: string, t: VerifactuTranslator) {
  const key = `verifactu.compliance.event.${type}`;
  const translated = t(key);
  return translated === key ? humanizeVerifactuValue(type) : translated;
}

function requirementStatusLabel(status: string, t: VerifactuTranslator) {
  const key = `verifactu.compliance.requirementStatus.${status}`;
  const translated = t(key);
  return translated === key ? humanizeVerifactuValue(status) : translated;
}

function eventColumnLabel(column: FiscalEventColumn, t: VerifactuTranslator) {
  const keys: Record<FiscalEventColumn, string> = {
    sequence: "verifactu.management.sequence",
    eventType: "verifactu.compliance.eventType",
    fiscalMode: "verifactu.management.fiscalMode",
    generatedAt: "verifactu.compliance.generatedAt",
    signature: "verifactu.compliance.signature",
    hash: "verifactu.compliance.hash"
  };
  return t(keys[column]);
}

function exportColumnLabel(column: FiscalExportColumn, t: VerifactuTranslator) {
  const keys: Record<FiscalExportColumn, string> = {
    kind: "verifactu.compliance.exportKind",
    generatedAt: "verifactu.compliance.generatedAt",
    records: "verifactu.management.records",
    hash: "verifactu.compliance.hash"
  };
  return t(keys[column]);
}

function requirementColumnLabel(column: FiscalRequirementColumn, t: VerifactuTranslator) {
  const keys: Record<FiscalRequirementColumn, string> = {
    reference: "verifactu.compliance.requirementReference",
    status: "verifactu.management.status",
    generatedAt: "verifactu.compliance.generatedAt",
    actions: "verifactu.resolution.actions"
  };
  return t(keys[column]);
}
