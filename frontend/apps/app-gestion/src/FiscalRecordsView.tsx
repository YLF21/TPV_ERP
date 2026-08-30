import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";
import {
  ErpSelect,
  TableLayoutHeaderCell,
  useTableLayoutPreference,
  visibleTableColumns,
  type LocaleCode,
  type TableColumnDefinition
} from "@tpverp/app-common";
import {
  loadFiscalRecord,
  loadFiscalRecordsCursor,
  type FiscalRecordDetail,
  type FiscalRecordArtifact,
  type FiscalRecordCursorFilters,
  type FiscalRecordCursorPage,
  type FiscalRecordListItem,
  type FiscalRecordMode,
} from "./fiscalRecordsApi";
import {
  formatVerifactuDate,
  formatVerifactuDateTime,
  humanizeVerifactuValue,
  verifactuEndpointLabel,
  verifactuOperationLabel,
  verifactuStatusLabel,
  type VerifactuTranslator
} from "./verifactuPresentation";
import { type FiscalExportScope } from "./verifactuManagementApi";
import { datetimeLocalToIso, isValidDatetimeLocal } from "./fiscalDateTime";
import { FiscalWorkspaceDialog } from "./FiscalWorkspaceDialog";
import { FiscalExportJobsList, useFiscalExportJobs } from "./fiscalExportJobs";
import { fiscalErrorMessage } from "./verifactuErrorPresentation";

const emptyFilters: FiscalRecordCursorFilters = {
  dateFrom: "",
  dateTo: "",
  number: "",
  numberMatch: "PREFIX",
  operation: "",
  documentType: "",
  fiscalMode: "",
  size: 25
};

const emptyPage: FiscalRecordCursorPage = {
  items: [], size: 25, nextCursor: null, previousCursor: null,
  hasNext: false, hasPrevious: false, snapshotSequence: 0
};
const operationValues = ["ALTA", "ANULACION"] as const;
const documentTypeValues = ["F1", "F2", "F3", "R1", "R2", "R3", "R4", "R5"] as const;
// PRE_SIF is a pre-activation state and never creates fiscal records.
// Keep it out of the normal catalogue; legacy rows are still handled by the API.
const modeValues = ["NO_VERIFACTU", "VERIFACTU"] as const;
const MAX_SELECTION = 1000;
const MAX_RENDERED_RECORDS = 100;

type RecordColumnKey = "number" | "issueDate" | "operation" | "documentType" | "fiscalMode" | "amount" | "status";
const recordColumnDefinitions: readonly TableColumnDefinition<RecordColumnKey>[] = [
  { key: "number", defaultWidth: 220, minWidth: 150 },
  { key: "issueDate", defaultWidth: 125, minWidth: 105 },
  { key: "operation", defaultWidth: 130, minWidth: 110 },
  { key: "documentType", defaultWidth: 100, minWidth: 80 },
  { key: "fiscalMode", defaultWidth: 150, minWidth: 125 },
  { key: "amount", defaultWidth: 120, minWidth: 100 },
  { key: "status", defaultWidth: 150, minWidth: 125 }
];

export function FiscalRecordsView({
  locale,
  timezone = null,
  token,
  username = "anonymous",
  revision,
  t,
  canManage = false
}: {
  locale: LocaleCode;
  timezone?: string | null;
  token?: string;
  username?: string;
  revision: number;
  t: VerifactuTranslator;
  canManage?: boolean;
}) {
  const [draft, setDraft] = useState(emptyFilters);
  const [filters, setFilters] = useState(emptyFilters);
  const [page, setPage] = useState(emptyPage);
  const [cursor, setCursor] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<FiscalRecordDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadedOnce, setLoadedOnce] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState(false);
  const [detailError, setDetailError] = useState(false);
  const [filterError, setFilterError] = useState(false);
  const [selectedRecords, setSelectedRecords] = useState<Set<string>>(new Set());
  const [selectionLimitReached, setSelectionLimitReached] = useState(false);
  const [exportScope, setExportScope] = useState<"CURRENT" | "SELECTED" | "FILTERED" | "PERIOD">("PERIOD");
  const [exportStart, setExportStart] = useState("");
  const [exportEnd, setExportEnd] = useState("");
  const [exportMessage, setExportMessage] = useState<string | null>(null);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);
  const exportJobs = useFiscalExportJobs(token, canManage);
  const request = useRef(0);
  const detailRequest = useRef(0);

  const tableLayout = useTableLayoutPreference({
    app: "gestion",
    username,
    accessToken: token,
    tableKey: "gestion.verifactu.records",
    definitions: recordColumnDefinitions
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const filterFingerprint = useMemo(() => [
    filters.dateFrom,
    filters.dateTo,
    filters.number,
    filters.numberMatch ?? "PREFIX",
    filters.operation,
    filters.documentType,
    filters.fiscalMode
  ].join("\u001f"), [filters]);

  useEffect(() => {
    const controller = new AbortController();
    const requestId = ++request.current;
    setLoading(true);
    setError(false);
    void loadFiscalRecordsCursor({ ...filters, cursor }, token, controller.signal)
      .then((next) => {
        if (requestId !== request.current) return;
        const boundedPage = boundFiscalRecordPage(next);
        setPage(boundedPage);
        setLoadedOnce(true);
        if (selectedId && !boundedPage.items.some((item) => item.recordId === selectedId)) {
          setSelectedId(null);
          setDetail(null);
        }
      })
      .catch(() => {
        if (controller.signal.aborted) return;
        if (requestId !== request.current) return;
        setError(true);
      })
      .finally(() => {
        if (requestId === request.current) setLoading(false);
      });
    return () => {
      controller.abort();
      request.current += 1;
    };
  }, [filterFingerprint, filters, cursor, revision, token]);

  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      setDetailError(false);
      setDetailLoading(false);
      return;
    }
    const controller = new AbortController();
    const requestId = ++detailRequest.current;
    setDetailLoading(true);
    setDetailError(false);
    void loadFiscalRecord(selectedId, token, controller.signal)
      .then((next) => { if (requestId === detailRequest.current) setDetail(next); })
      .catch(() => {
        if (controller.signal.aborted) return;
        if (requestId === detailRequest.current) { setDetail(null); setDetailError(true); }
      })
      .finally(() => { if (requestId === detailRequest.current) setDetailLoading(false); });
    return () => {
      controller.abort();
      detailRequest.current += 1;
    };
  }, [selectedId, revision, token]);

  const operationOptions = useMemo(() => optionsWithTranslatedValues(operationValues, "verifactu.records.allOperations", operationLabel, t), [t]);
  const documentTypeOptions = useMemo(() => optionsWithAll(documentTypeValues, "verifactu.records.allTypes", t), [t]);
  const modeOptions = useMemo(() => optionsWithTranslatedValues(modeValues, "verifactu.records.allModes", modeLabel, t), [t]);
  const numberMatchOptions = useMemo(() => [
    { value: "PREFIX", label: t("verifactu.records.numberPrefix") },
    { value: "EXACT", label: t("verifactu.records.numberExact") }
  ], [t]);

  function apply(event: FormEvent) {
    event.preventDefault();
    if (draft.dateFrom && draft.dateTo && draft.dateFrom > draft.dateTo) {
      setFilterError(true);
      return;
    }
    setFilterError(false);
    setCursor(null);
    setFilters({ ...draft, cursor: null });
    setFiltersOpen(false);
  }

  function clear() {
    setFilterError(false);
    setDraft(emptyFilters);
    setCursor(null);
    setFilters({ ...emptyFilters, cursor: null });
  }

  const activeFilterCount = countActiveFilters(filters);
  const initialLoading = loading && !loadedOnce;
  const refreshing = loading && loadedOnce;

  function select(item: FiscalRecordListItem) {
    setSelectedId((current) => current === item.recordId ? null : item.recordId);
  }

  function nextPage() {
    if (loading || !page.hasNext || !page.nextCursor) return;
    setCursor(page.nextCursor);
  }

  function previousPage() {
    if (loading || !page.hasPrevious) return;
    setCursor(page.previousCursor ?? null);
  }

  function retry() {
    setError(false);
    setFilters((current) => ({ ...current }));
  }

  function toggleRecord(recordId: string) {
    setSelectedRecords((current) => {
      const next = new Set(current);
      if (next.has(recordId)) {
        next.delete(recordId);
        setSelectionLimitReached(false);
      } else if (next.size >= MAX_SELECTION) {
        setSelectionLimitReached(true);
        return current;
      } else next.add(recordId);
      return next;
    });
  }

  function selectVisibleRecords(checked: boolean) {
    if (!checked) {
      setSelectedRecords((current) => {
        const next = new Set(current);
        page.items.forEach((item) => next.delete(item.recordId));
        if (next.size < MAX_SELECTION) setSelectionLimitReached(false);
        return next;
      });
      return;
    }
    const available = Math.max(0, MAX_SELECTION - selectedRecords.size);
    const unselected = page.items.filter((item) => !selectedRecords.has(item.recordId));
    setSelectionLimitReached(unselected.length > available);
    setSelectedRecords((current) => addFiscalRecordIdsWithinLimit(current, page.items.map((item) => item.recordId)));
  }

  async function exportRecords() {
    if (!canManage || exportJobs.creating) return;
    const fiscalTimezone = timezone?.trim() || "";
    if (exportScope === "PERIOD" && (!fiscalTimezone || !exportStart || !exportEnd
      || !isValidDatetimeLocal(exportStart, fiscalTimezone)
      || !isValidDatetimeLocal(exportEnd, fiscalTimezone)
      || new Date(datetimeLocalToIso(exportStart, fiscalTimezone)).getTime()
        > new Date(datetimeLocalToIso(exportEnd, fiscalTimezone)).getTime())) {
      setExportMessage(t("verifactu.records.exportInvalidPeriod"));
      return;
    }
    if (exportScope === "CURRENT" && !selectedId) {
      setExportMessage(t("verifactu.records.exportCurrentRequired"));
      return;
    }
    if (exportScope === "SELECTED" && selectedRecords.size === 0) {
      setExportMessage(t("verifactu.records.exportSelectionRequired"));
      return;
    }
    const scope: FiscalExportScope = exportScope === "CURRENT"
      ? { recordIds: selectedId ? [selectedId] : [] }
      : exportScope === "SELECTED"
        ? { recordIds: [...selectedRecords] }
        : exportScope === "FILTERED"
          ? { dateFrom: filters.dateFrom || null, dateTo: filters.dateTo || null, documentNumber: filters.numberMatch === "EXACT" ? filters.number || null : null, documentNumberPrefix: filters.numberMatch === "PREFIX" ? filters.number || null : null, operation: filters.operation || null, documentType: filters.documentType || null, fiscalMode: filters.fiscalMode || null }
          : {};
    setExportMessage(null);
    const job = await exportJobs.create({
      kind: "BILLING",
      scope: exportScope,
      periodStart: exportScope === "PERIOD" ? datetimeLocalToIso(exportStart, fiscalTimezone) : null,
      periodEnd: exportScope === "PERIOD" ? datetimeLocalToIso(exportEnd, fiscalTimezone) : null,
      recordIds: scope.recordIds ?? [],
      dateFrom: scope.dateFrom ?? null,
      dateTo: scope.dateTo ?? null,
      documentNumber: scope.documentNumber?.trim() || null,
      documentNumberPrefix: scope.documentNumberPrefix?.trim() || null,
      operation: scope.operation ?? null,
      documentType: scope.documentType ?? null,
      fiscalMode: scope.fiscalMode ?? null
    });
    if (job) setExportMessage(t("verifactu.exportJobs.created"));
    else setExportMessage(t("verifactu.records.exportError"));
  }

  return (
    <div className="gestion-verifactu-records">
      <section className="gestion-verifactu-table-panel" aria-label={t("verifactu.records.title")} aria-busy={loading}>
        <header>
          <div><h3>{t("verifactu.records.title")}</h3><span>{refreshing ? t("verifactu.records.refreshing") : `${page.items.length} ${t("verifactu.records.visibleCount")}`}</span></div>
          <div className="fiscal-records-toolbar">
            {initialLoading && <span role="status" aria-live="polite" aria-label={t("verifactu.records.loading")}>{t("verifactu.records.loading")}</span>}
            {refreshing && <span role="status" aria-live="polite" aria-label={t("verifactu.records.refreshing")}>{t("verifactu.records.refreshing")}</span>}
            <button type="button" aria-haspopup="dialog" aria-expanded={filtersOpen} onClick={() => setFiltersOpen(true)}>{t("verifactu.ui.filters")}{activeFilterCount ? ` (${activeFilterCount})` : ""}</button>
            {canManage && <button type="button" className="primary" aria-haspopup="dialog" aria-expanded={exportOpen} onClick={() => { setExportMessage(null); setExportOpen(true); void exportJobs.refresh(); }}>{t("verifactu.ui.export")}</button>}
          </div>
        </header>
        {selectionLimitReached && <div className="gestion-verifactu-message" role="alert">{t("verifactu.records.selectionLimit")}</div>}
        {initialLoading ? <div className="gestion-verifactu-message" role="status" aria-label={t("verifactu.records.loading")}>{t("verifactu.records.loading")}</div> : error ? <div className="gestion-verifactu-message error" role="alert"><span>{t("verifactu.records.loadError")}</span><button type="button" onClick={retry}>{t("verifactu.records.retry")}</button></div> : page.items.length === 0 ? <div className="gestion-verifactu-message">{t(activeFilterCount ? "verifactu.records.emptyFiltered" : "verifactu.records.empty")}</div> : (
          <div className="gestion-verifactu-table-scroll"><table className="gestion-verifactu-table"><colgroup>
            {canManage && <col style={{ width: 58 }} />}
            {visibleColumns.map((column) => <col key={column.key} style={{ width: column.width }} />)}
            <col style={{ width: 110 }} />
          </colgroup><thead><tr>
            {canManage && <th><span className="sr-only">{t("verifactu.records.selectRecord")}</span><input type="checkbox" aria-label={t("verifactu.records.selectAll")} checked={page.items.length > 0 && page.items.every((item) => selectedRecords.has(item.recordId))} onChange={(event) => selectVisibleRecords(event.target.checked)} /></th>}
            {visibleColumns.map((column) => <TableLayoutHeaderCell key={column.key} column={column} resizeLabel={`${t("verifactu.ui.resizeColumn")} ${columnLabel(column.key, t)}`} onReorder={tableLayout.reorderColumns} onMove={tableLayout.moveColumn} onResize={tableLayout.resizeColumn}>{columnLabel(column.key, t)}</TableLayoutHeaderCell>)}
            <th>{t("verifactu.records.detail")}</th>
          </tr></thead><tbody>{page.items.map((item) => <tr key={item.recordId} aria-selected={selectedId === item.recordId}>
            {canManage && <td><input type="checkbox" aria-label={`${t("verifactu.records.selectRecord")} ${item.number}`} checked={selectedRecords.has(item.recordId)} disabled={!selectedRecords.has(item.recordId) && selectedRecords.size >= 1000} onChange={() => toggleRecord(item.recordId)} /></td>}
            {visibleColumns.map((column) => <td key={column.key} className={column.key === "amount" ? "numeric" : undefined}>{renderRecordCell(column.key, item, locale, t)}</td>)}
            <td><button type="button" className="gestion-verifactu-link-button" aria-haspopup="dialog" aria-expanded={selectedId === item.recordId} onClick={() => select(item)}>{selectedId === item.recordId ? t("verifactu.records.closeDetail") : t("verifactu.records.viewDetail")}</button></td>
          </tr>)}</tbody></table></div>
        )}
        <footer className="gestion-verifactu-pagination"><button type="button" disabled={loading || !page.hasPrevious} onClick={previousPage}>{t("verifactu.records.previous")}</button><span>{page.items.length} {t("verifactu.records.visibleCount")}</span><button type="button" disabled={loading || !page.hasNext} onClick={nextPage}>{t("verifactu.records.next")}</button></footer>
      </section>

      {filtersOpen && <FiscalWorkspaceDialog id="fiscal-record-filters" purpose="filters" className="gestion-rules-dialog" title={t("verifactu.ui.filters")} closeLabel={t("verifactu.ui.close")} onClose={() => setFiltersOpen(false)} footer={<><button type="submit" form="fiscal-record-filter-form" className="primary">{t("verifactu.records.applyFilters")}</button><button type="button" onClick={clear}>{t("verifactu.records.clearFilters")}</button><button type="button" onClick={() => setFiltersOpen(false)}>{t("verifactu.ui.close")}</button></>}>
        <form id="fiscal-record-filter-form" className="gestion-verifactu-filters" onSubmit={apply}>
          <label><span>{t("verifactu.records.dateFrom")}</span><input type="date" value={draft.dateFrom} onChange={(event) => setDraft({ ...draft, dateFrom: event.target.value })} /></label>
          <label><span>{t("verifactu.records.dateTo")}</span><input type="date" value={draft.dateTo} onChange={(event) => setDraft({ ...draft, dateTo: event.target.value })} /></label>
          <label htmlFor="fiscal-record-number"><span>{t("verifactu.records.number")}</span><input id="fiscal-record-number" aria-describedby="fiscal-record-number-hint" maxLength={64} value={draft.number} onChange={(event) => setDraft({ ...draft, number: event.target.value })} /><small id="fiscal-record-number-hint">{draft.numberMatch === "PREFIX" ? t("verifactu.records.numberPrefixHint") : ""}</small></label>
          <label><span>{t("verifactu.records.numberMatch")}</span><ErpSelect value={draft.numberMatch ?? "PREFIX"} options={numberMatchOptions} aria-label={`${t("verifactu.records.numberMatch")}: ${selectedLabel(numberMatchOptions, draft.numberMatch ?? "PREFIX")}`} onChange={(value) => setDraft({ ...draft, numberMatch: value as "EXACT" | "PREFIX" })} /></label>
          <label><span>{t("verifactu.records.operation")}</span><ErpSelect value={draft.operation} options={operationOptions} aria-label={`${t("verifactu.records.operation")}: ${selectedLabel(operationOptions, draft.operation)}`} onChange={(value) => setDraft({ ...draft, operation: value })} /></label>
          <label><span>{t("verifactu.records.documentType")}</span><ErpSelect value={draft.documentType} options={documentTypeOptions} aria-label={`${t("verifactu.records.documentType")}: ${selectedLabel(documentTypeOptions, draft.documentType)}`} onChange={(value) => setDraft({ ...draft, documentType: value })} /></label>
          <label><span>{t("verifactu.records.fiscalMode")}</span><ErpSelect value={draft.fiscalMode} options={modeOptions} aria-label={`${t("verifactu.records.fiscalMode")}: ${selectedLabel(modeOptions, draft.fiscalMode)}`} onChange={(value) => setDraft({ ...draft, fiscalMode: value })} /></label>
          {filterError && <p role="alert">{t("verifactu.records.invalidDateRange")}</p>}
        </form>
      </FiscalWorkspaceDialog>}

      {exportOpen && canManage && <FiscalWorkspaceDialog id="fiscal-record-export" purpose="export" className="gestion-rules-dialog" title={t("verifactu.records.exportTitle")} closeLabel={t("verifactu.ui.close")} closeDisabled={exportJobs.creating} onClose={() => setExportOpen(false)} footer={<><button type="submit" form="fiscal-record-export-form" className="primary" disabled={exportJobs.creating}>{exportJobs.creating ? t("verifactu.records.exporting") : t("verifactu.records.export")}</button><button type="button" disabled={exportJobs.creating} onClick={() => setExportOpen(false)}>{t("verifactu.ui.close")}</button></>}>
        <form id="fiscal-record-export-form" className="gestion-verifactu-export-options" onSubmit={(event) => { event.preventDefault(); void exportRecords(); }}>
          <label><span>{t("verifactu.records.exportScope")}</span><ErpSelect value={exportScope} aria-label={`${t("verifactu.records.exportScope")}: ${exportScopeLabel(exportScope, t)}`} options={[{ value: "PERIOD", label: t("verifactu.records.exportPeriod") }, { value: "CURRENT", label: t("verifactu.records.exportCurrent") }, { value: "SELECTED", label: t("verifactu.records.exportSelected") }, { value: "FILTERED", label: t("verifactu.records.exportFiltered") }]} onChange={(value) => setExportScope(value as typeof exportScope)} /></label>
          {exportScope === "PERIOD" && <><label><span>{t("verifactu.records.exportStart")}</span><input type="datetime-local" value={exportStart} onChange={(event) => setExportStart(event.target.value)} /></label><label><span>{t("verifactu.records.exportEnd")}</span><input type="datetime-local" value={exportEnd} onChange={(event) => setExportEnd(event.target.value)} /></label></>}
          <p>{exportScope === "PERIOD" ? t("verifactu.exportJobs.periodRegulatory") : t("verifactu.exportJobs.partialHint")}</p>
          {exportMessage && <p role="status" aria-live="polite">{exportMessage}</p>}
        </form>
        <FiscalExportJobsList jobs={exportJobs.jobs} loading={exportJobs.loading} error={exportJobs.error} downloadId={exportJobs.downloadId} downloadError={exportJobs.downloadError} onRefresh={() => void exportJobs.refresh()} onRetry={(id) => void exportJobs.retry(id)} onDownload={(job) => void exportJobs.download(job)} t={t} />
      </FiscalWorkspaceDialog>}

      {selectedId && <FiscalRecordDetailPanel locale={locale} timezone={timezone} detail={detail} loading={detailLoading} error={detailError} t={t} onClose={() => setSelectedId(null)} onNavigate={setSelectedId} />}
    </div>
  );
}

export function addFiscalRecordIdsWithinLimit(current: ReadonlySet<string>, ids: readonly string[]) {
  const next = new Set(current);
  for (const id of ids) {
    if (next.size >= MAX_SELECTION) break;
    next.add(id);
  }
  return next;
}

function boundFiscalRecordPage(page: FiscalRecordCursorPage): FiscalRecordCursorPage {
  const declaredSize = Number.isInteger(page.size) && page.size > 0 ? page.size : 25;
  const renderSize = Math.min(MAX_RENDERED_RECORDS, declaredSize);
  return page.items.length > renderSize ? { ...page, items: page.items.slice(0, renderSize) } : page;
}

function FiscalRecordDetailPanel({ detail, locale, timezone, loading, error, t, onClose, onNavigate }: { detail: FiscalRecordDetail | null; locale: LocaleCode; timezone?: string | null; loading: boolean; error: boolean; t: VerifactuTranslator; onClose: () => void; onNavigate: (recordId: string) => void }) {
  const detailTimezone: string | undefined = detail?.timezone ?? timezone ?? undefined;
  const qrUrl = detail?.artifact ? trustedQrUrl(detail.artifact.qrUrl, detail.artifact) : null;
  return <FiscalWorkspaceDialog id="fiscal-record-detail" variant="drawer" className="gestion-verifactu-record-detail" title={detail?.number ?? t("verifactu.records.detailTitle")} closeLabel={t("verifactu.records.closeDetail")} onClose={onClose}>
    <div className="fiscal-record-detail-navigation">
      <span className="gestion-eyebrow">{t("verifactu.records.detailEyebrow")}</span>
      <button type="button" disabled={loading || !detail?.previousRecordId} onClick={() => detail?.previousRecordId && onNavigate(detail.previousRecordId)}>{t("verifactu.records.previousRecord")}</button>
      <button type="button" disabled={loading || !detail?.nextRecordId} onClick={() => detail?.nextRecordId && onNavigate(detail.nextRecordId)}>{t("verifactu.records.nextRecord")}</button>
    </div>
    {loading && <div className="gestion-verifactu-message" role="status">{t("verifactu.records.detailLoading")}</div>}
    {!loading && error && <div className="gestion-verifactu-message error" role="alert">{t("verifactu.records.detailError")}</div>}
    {!loading && !error && detail && <div className="gestion-verifactu-record-detail-body">
      <dl className="gestion-verifactu-details">
        <Detail label={t("verifactu.records.chainSequence")} value={String(detail.sequence)} /><Detail label={t("verifactu.records.fiscalMode")} value={modeLabel(detail.fiscalMode, t)} /><Detail label={t("verifactu.records.operation")} value={operationLabel(detail.operation, t)} /><Detail label={t("verifactu.records.issueDate")} value={formatVerifactuDate(detail.issueDate, locale)} /><Detail label={t("verifactu.records.generatedAt")} value={formatVerifactuDateTime(detail.generatedAt, locale, detailTimezone)} /><Detail label={t("verifactu.records.issuerTaxId")} value={detail.issuerTaxId} /><Detail label={t("verifactu.records.totalTax")} value={formatAmount(detail.totalTax, locale)} /><Detail label={t("verifactu.records.amount")} value={formatAmount(detail.totalAmount, locale)} /><Detail label={t("verifactu.records.previousHash")} value={<HashValue value={detail.previousHash} t={t} />} /><Detail label={t("verifactu.records.hash")} value={<HashValue value={detail.hash} t={t} />} /><Detail label={t("verifactu.records.snapshotHash")} value={<HashValue value={detail.snapshotHash} t={t} />} /><Detail label={t("verifactu.records.applicationVersion")} value={detail.applicationVersion} />
      </dl>
      <div className="gestion-verifactu-record-detail-columns">
        <DetailBlock title={t("verifactu.records.chainTitle")}><Detail label={t("verifactu.records.adjacentChain")} value={<span className={`gestion-verifactu-state state-${(detail.adjacentChainStatus ?? "ADJACENT_UNAVAILABLE").toLowerCase()}`}>{adjacentChainLabel(detail.adjacentChainStatus, t)}</span>} /><Detail label={t("verifactu.records.previousRecord")} value={shortId(detail.previousRecordId, t("verifactu.records.none"))} /><Detail label={t("verifactu.records.nextRecord")} value={shortId(detail.nextRecordId, t("verifactu.records.none"))} /><Detail label={t("verifactu.records.relations")} value={detail.relations.length ? detail.relations.map((relation) => `${relationTypeLabel(relation.type, t)} (${shortId(relation.relatedRecordId, "")})`).join(", ") : t("verifactu.records.none")} /></DetailBlock>
        <DetailBlock title={t("verifactu.records.artifactTitle")}><Detail label={t("verifactu.records.environment")} value={detail.artifact ? `${verifactuEndpointLabel(detail.artifact.environment, t)}${detail.artifact.sandbox ? ` · ${t("verifactu.records.sandbox")}` : ""}` : t("verifactu.records.none")} /><Detail label={t("verifactu.records.issuer")} value={detail.artifact?.issuerName ?? t("verifactu.records.none")} /><Detail label={t("verifactu.records.xmlHash")} value={<HashValue value={detail.artifact?.xmlHash} t={t} />} /><Detail label={t("verifactu.records.qrHash")} value={<HashValue value={detail.artifact?.qrHash} t={t} />} />{qrUrl && <div className="gestion-verifactu-details-link"><span>{t("verifactu.records.qrUrl")}</span><a href={qrUrl} target="_blank" rel="noreferrer">{t("verifactu.records.openQr")}</a></div>}</DetailBlock>
        <DetailBlock title={t("verifactu.records.documentTitle")}><Detail label={t("verifactu.records.documentNumber")} value={detail.document?.number ?? detail.number} /><Detail label={t("verifactu.records.documentStatus")} value={detail.document ? documentStatusLabel(detail.document.status, t) : t("verifactu.records.none")} /><Detail label={t("verifactu.records.documentType")} value={detail.document?.type ?? detail.documentType} /></DetailBlock>
        <DetailBlock title={t("verifactu.records.submissionTitle")}><Detail label={t("verifactu.records.status")} value={detail.submission ? statusLabel(detail.submission.status, t) : t("verifactu.records.notSubmitted")} /><Detail label={t("verifactu.records.updatedAt")} value={detail.submission?.updatedAt ? formatVerifactuDateTime(detail.submission.updatedAt, locale, detailTimezone) : t("verifactu.records.none")} /><Detail label={t("verifactu.records.errorCode")} value={fiscalErrorMessage(detail.submission?.errorCode, t, locale) ?? t("verifactu.records.none")} /></DetailBlock>
      </div>
    </div>}
  </FiscalWorkspaceDialog>;
}

function DetailBlock({ title, children }: { title: string; children: ReactNode }) { return <section className="gestion-verifactu-panel"><header><h3>{title}</h3></header><dl className="gestion-verifactu-details">{children}</dl></section>; }
function Detail({ label, value }: { label: string; value: ReactNode }) { return <div><dt>{label}</dt><dd>{value}</dd></div>; }
function countActiveFilters(filters: FiscalRecordCursorFilters) {
  return [filters.dateFrom, filters.dateTo, filters.number, filters.operation, filters.documentType, filters.fiscalMode].filter(Boolean).length;
}
function columnLabel(column: RecordColumnKey, t: VerifactuTranslator) {
  return column === "number" ? t("verifactu.records.number")
      : column === "issueDate" ? t("verifactu.records.issueDate")
        : column === "operation" ? t("verifactu.records.operation")
          : column === "documentType" ? t("verifactu.records.documentType")
            : column === "fiscalMode" ? t("verifactu.records.fiscalMode")
              : column === "amount" ? t("verifactu.records.amount")
                : t("verifactu.records.status");
}
function renderRecordCell(column: RecordColumnKey, item: FiscalRecordListItem, locale: LocaleCode, t: VerifactuTranslator): ReactNode {
  if (column === "number") return <><strong>{item.number}</strong><HashValue value={item.hash} t={t} compact /></>;
  if (column === "issueDate") return formatVerifactuDate(item.issueDate, locale);
  if (column === "operation") return operationLabel(item.operation, t);
  if (column === "documentType") return item.documentType;
  if (column === "fiscalMode") return modeLabel(item.fiscalMode, t);
  if (column === "amount") return formatAmount(item.totalAmount, locale);
  return item.submissionStatus
    ? <span className={`gestion-verifactu-state state-${item.submissionStatus.toLowerCase()}`}>{statusLabel(item.submissionStatus, t)}</span>
    : <span className="gestion-verifactu-state">{t("verifactu.records.notSubmitted")}</span>;
}
function HashValue({ value, t, compact = false }: { value: string | null | undefined; t: VerifactuTranslator; compact?: boolean }) {
  const [state, setState] = useState<"idle" | "copied" | "error">("idle");
  if (!value) return <span>—</span>;
  const hashValue = value;
  async function copy() {
    try {
      if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(hashValue);
      else { const area = document.createElement("textarea"); area.value = hashValue; area.style.position = "fixed"; area.style.opacity = "0"; document.body.appendChild(area); area.select(); document.execCommand("copy"); area.remove(); }
      setState("copied");
    } catch { setState("error"); }
  }
  return <span className={`gestion-verifactu-hash ${compact ? "compact" : ""}`}><code title={hashValue}>{compact ? shortHash(hashValue) : hashValue}</code><button type="button" aria-label={`${t("verifactu.records.copyHash")} ${compact ? shortHash(hashValue) : hashValue}`} onClick={() => void copy()}>{t("verifactu.records.copyHash")}</button>{state !== "idle" && <span role="status" aria-live="polite">{state === "copied" ? t("verifactu.records.copiedHash") : t("verifactu.records.copyHashError")}</span>}</span>;
}
function optionsWithAll(values: readonly string[], allKey: string, t: VerifactuTranslator) { return [{ value: "", label: t(allKey) }, ...values.map((value) => ({ value, label: humanizeVerifactuValue(value) }))]; }
function optionsWithTranslatedValues(values: readonly string[], allKey: string, label: (value: string, t: VerifactuTranslator) => string, t: VerifactuTranslator) { return [{ value: "", label: t(allKey) }, ...values.map((value) => ({ value, label: label(value, t) }))]; }
function selectedLabel(options: readonly { value: string; label: string }[], value: string) { return options.find((option) => option.value === value)?.label ?? "—"; }
function modeLabel(mode: FiscalRecordMode, t: VerifactuTranslator) { const translated = t(`verifactu.management.fiscalMode.${mode}`); return translated === `verifactu.management.fiscalMode.${mode}` ? humanizeVerifactuValue(mode) : translated; }
function operationLabel(operation: string, t: VerifactuTranslator) { return verifactuOperationLabel(operation, t); }
function statusLabel(status: string, t: VerifactuTranslator) { return verifactuStatusLabel(status, t); }
function relationTypeLabel(type: string, t: VerifactuTranslator) {
  const key = `verifactu.records.relation.${type}`;
  const translated = t(key);
  return translated === key ? humanizeVerifactuValue(type) : translated;
}
function documentStatusLabel(status: string, t: VerifactuTranslator) {
  const key = `verifactu.records.documentStatus.${status}`;
  const translated = t(key);
  return translated === key ? humanizeVerifactuValue(status) : translated;
}
function adjacentChainLabel(status: string | null | undefined, t: VerifactuTranslator) {
  const key = status === "ADJACENT_VALID" ? "verifactu.records.adjacentChain.valid" : status === "ADJACENT_ANOMALOUS" ? "verifactu.records.adjacentChain.anomalous" : "verifactu.records.adjacentChain.unavailable";
  return t(key);
}
function exportScopeLabel(scope: "CURRENT" | "SELECTED" | "FILTERED" | "PERIOD", t: VerifactuTranslator) {
  return t(scope === "CURRENT" ? "verifactu.records.exportCurrent" : scope === "SELECTED" ? "verifactu.records.exportSelected" : scope === "FILTERED" ? "verifactu.records.exportFiltered" : "verifactu.records.exportPeriod");
}
function shortHash(value: string | null | undefined) { if (!value) return "—"; return value.length <= 16 ? value : `${value.slice(0, 8)}…${value.slice(-8)}`; }
function shortId(value: string | null | undefined, fallback: string) { return value ? shortHash(value) : fallback; }
function formatAmount(value: number | string | null | undefined, locale: LocaleCode) { if (value === null || value === undefined || value === "") return "—"; const numeric = typeof value === "number" ? value : Number(value); return Number.isFinite(numeric) ? new Intl.NumberFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(numeric) : "—"; }
export function trustedQrUrl(
  value: string | null | undefined,
  artifact?: Pick<FiscalRecordArtifact, "fiscalMode" | "environment" | "sandbox"> | null
) {
  if (!value) return null;
  try {
    const authority = value.match(/^[a-z][a-z\d+.-]*:\/\/([^/?#]*)/i)?.[1] ?? "";
    const url = new URL(value);
    if (url.protocol !== "https:" || url.username || url.password || url.port || authority.includes(":")) return null;
    const officialPath = url.pathname === "/wlpl/TIKE-CONT/ValidarQR"
      || url.pathname === "/wlpl/TIKE-CONT/ValidarQRNoVerifactu";
    if (!officialPath || !["prewww2.aeat.es", "www2.agenciatributaria.gob.es"].includes(url.hostname.toLowerCase())) return null;
    if (artifact) {
      const expectedPath = artifact.fiscalMode === "NO_VERIFACTU"
        ? "/wlpl/TIKE-CONT/ValidarQRNoVerifactu"
        : artifact.fiscalMode === "VERIFACTU"
          ? "/wlpl/TIKE-CONT/ValidarQR"
          : null;
      const expectedHost = artifact.environment === "TEST"
        ? "prewww2.aeat.es"
        : artifact.environment === "PRODUCTION"
          ? "www2.agenciatributaria.gob.es"
          : null;
      if ((expectedPath && url.pathname !== expectedPath) || (expectedHost && url.hostname.toLowerCase() !== expectedHost)) return null;
    }
    return url.toString();
  } catch {
    return null;
  }
}
