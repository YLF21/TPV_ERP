import { ErpListSelect } from "../../../packages/app-common/src/components/ErpListSelect";
import { compactListFilter, listFilterChips, listFilterValues } from "../../../packages/app-common/src/components/listFilterValues";
import { useEffect, useMemo, useRef, useState } from "react";
import {
  TableLayoutHeaderCell,
  nextTableSort,
  useTableLayoutPreference,
  useTableSortPreference,
  visibleTableColumns,
  type LocaleCode,
  type TableColumnDefinition
} from "@tpverp/app-common";
import { FiscalWorkspaceDialog } from "./FiscalWorkspaceDialog";
import {
  loadVerifactuAdminDefectiveRecords,
  verifactuDocumentTypes,
  verifactuOperations,
  type VerifactuAdminDefectiveFilters,
  type VerifactuAdminDefectiveRecordPage
} from "./verifactuManagementApi";
import {
  formatVerifactuDate,
  formatVerifactuDateTime,
  verifactuOperationLabel,
  verifactuStatusLabel,
  type VerifactuTranslator
} from "./verifactuPresentation";
import { fiscalErrorMessage } from "./verifactuErrorPresentation";
import { ErpFilterChips } from "../../../packages/app-common/src/components/ErpFilterChips";
import { ReportDateRangeSummary } from "../../../packages/app-common/src/components/ReportDateRangeFilter";
import { useTableRowArrowFocus } from "../../../packages/app-common/src/components/useTableRowArrowFocus";
import { GestionSearchFrame } from "../../../packages/app-common/src/components/GestionSearchFrame";
import { Funnel } from "@phosphor-icons/react";
import "./GestionFiscalListFilters.css";

const defectiveStatuses = ["RECHAZADO", "DEFECTUOSO", "ACEPTADO_CON_ERRORES"] as const;
const defectiveSortColumns = ["sequence", "document", "documentType", "fiscalOperation", "issueDate", "status", "updatedAt", "errorCode"] as const;
type DefectiveSortColumn = typeof defectiveSortColumns[number];
const defectiveColumnDefinitions = [
  { key: "sequence", defaultWidth: 88 },
  { key: "document", defaultWidth: 190 },
  { key: "documentType", defaultWidth: 110 },
  { key: "fiscalOperation", defaultWidth: 150 },
  { key: "issueDate", defaultWidth: 120 },
  { key: "status", defaultWidth: 150 },
  { key: "updatedAt", defaultWidth: 180 },
  { key: "errorCode", defaultWidth: 140 }
] as const satisfies readonly TableColumnDefinition<DefectiveSortColumn>[];

const emptyFilters: VerifactuAdminDefectiveFilters = {
  dateFrom: "",
  dateTo: "",
  status: "",
  documentType: "",
  operation: "",
  documentNumber: "",
  page: 0,
  size: 25
};

const emptyPage: VerifactuAdminDefectiveRecordPage = {
  items: [], page: 0, size: 25, totalElements: 0, totalPages: 0
};

export function VerifactuDefectiveRecordsView({
  locale,
  timezone = null,
  token,
  username,
  revision,
  t,
  onOpenAttempts,
  onOpenResolution
}: {
  locale: LocaleCode;
  timezone?: string | null;
  token?: string;
  username: string;
  revision: number;
  t: VerifactuTranslator;
  onOpenAttempts: (recordId: string, documentNumber: string, returnFocus: HTMLElement) => void;
  onOpenResolution: (recordId: string, documentNumber: string, returnFocus: HTMLElement) => void;
}) {
  const sorting = useTableSortPreference({
    app: "gestion",
    username,
    tableKey: "gestion.verifactu.defective",
    columns: defectiveSortColumns,
    defaultSort: null
  });
  const tableLayout = useTableLayoutPreference({
    app: "gestion",
    username,
    accessToken: token,
    tableKey: "gestion.verifactu.defective",
    definitions: defectiveColumnDefinitions
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const initialFilters = { ...emptyFilters, sortBy: sorting.sort?.column, sortDirection: sorting.sort?.direction };
  const [draft, setDraft] = useState<VerifactuAdminDefectiveFilters>(initialFilters);
  const [filters, setFilters] = useState<VerifactuAdminDefectiveFilters>(initialFilters);
  const [page, setPage] = useState(emptyPage);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [filterError, setFilterError] = useState(false);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const request = useRef(0);
  const tableScrollRef = useRef<HTMLDivElement>(null);
  const handleRowArrow = useTableRowArrowFocus(tableScrollRef, "tbody tr", !loading && !error && !filtersOpen, filters.page);

  useEffect(() => {
    const requestId = ++request.current;
    setLoading(true);
    setError(false);
    void loadVerifactuAdminDefectiveRecords(filters, token)
      .then((next) => {
        if (requestId === request.current) setPage(next);
      })
      .catch(() => {
        if (requestId !== request.current) return;
        setPage(emptyPage);
        setError(true);
      })
      .finally(() => {
        if (requestId === request.current) setLoading(false);
      });
    return () => { request.current += 1; };
  }, [filters, revision, token]);

  const statusOptions = useMemo(() => [
    { value: "", label: t("verifactu.management.allReviewStatuses") },
    ...defectiveStatuses.map((status) => ({ value: status, label: verifactuStatusLabel(status, t) }))
  ], [t]);
  const documentTypeOptions = useMemo(() => [
    { value: "", label: t("verifactu.management.allTypes") },
    ...verifactuDocumentTypes.map((type) => ({ value: type, label: type }))
  ], [t]);
  const operationOptions = useMemo(() => [
    { value: "", label: t("verifactu.management.allOperations") },
    ...verifactuOperations.map((operation) => ({ value: operation, label: verifactuOperationLabel(operation, t) }))
  ], [t]);

  function changeFilters(next: VerifactuAdminDefectiveFilters) {
    setDraft(next);
    if (next.dateFrom && next.dateTo && next.dateFrom > next.dateTo) {
      setFilterError(true);
      return;
    }
    setFilterError(false);
    setFilters({ ...next, page: 0 });
  }

  function clear() {
    setFilterError(false);
    const cleared = { ...emptyFilters, sortBy: sorting.sort?.column, sortDirection: sorting.sort?.direction };
    setDraft(cleared);
    setFilters(cleared);
  }

  function changeSort(column: DefectiveSortColumn) {
    const next = nextTableSort(sorting.sort, column);
    sorting.setSort(next);
    setDraft((current) => ({ ...current, sortBy: next.column, sortDirection: next.direction }));
    setFilters((current) => ({ ...current, sortBy: next.column, sortDirection: next.direction, page: 0 }));
  }

  function removeFilter(field: "dateFrom" | "dateTo" | "status" | "documentType" | "operation" | "documentNumber") {
    const nextDraft = { ...draft, [field]: "" };
    setFilterError(Boolean(nextDraft.dateFrom && nextDraft.dateTo && nextDraft.dateFrom > nextDraft.dateTo));
    setDraft((current) => ({ ...current, [field]: "" }));
    setFilters((current) => ({ ...current, [field]: "", page: 0 }));
  }

  return (
    <div className="gestion-verifactu-defective">
      {filtersOpen && <FiscalWorkspaceDialog
        id="verifactu-defective-filters"
        title={t("verifactu.ui.filters")}
        closeLabel={t("verifactu.ui.close")}
        onClose={() => setFiltersOpen(false)}
        purpose="filters"
        footer={<>
          <button type="button" onClick={clear}>{t("verifactu.management.clearFilters")}</button>

        </>}
      >
      <form id="verifactu-defective-filters-form" className="gestion-verifactu-filters gestion-filter-toolbar gestion-module-fields" onSubmit={(event) => event.preventDefault()}>
        <label data-filter-active={Boolean(draft.status)}>
          <span>{t("verifactu.management.status")}</span>
          <ErpListSelect
            values={listFilterValues(draft.status)}
            options={statusOptions}
            aria-label={`${t("verifactu.management.status")}: ${listFilterValues(draft.status).map(value => selectedLabel(statusOptions, value)).join(", ") || selectedLabel(statusOptions, "")}`}
            onChange={values => changeFilters({ ...draft, status: compactListFilter(values) })}
          />
        </label>
        <label data-filter-active={Boolean(draft.documentType)}>
          <span>{t("verifactu.management.documentType")}</span>
          <ErpListSelect
            values={listFilterValues(draft.documentType)}
            options={documentTypeOptions}
            aria-label={`${t("verifactu.management.documentType")}: ${listFilterValues(draft.documentType).map(value => selectedLabel(documentTypeOptions, value)).join(", ") || selectedLabel(documentTypeOptions, "")}`}
            onChange={values => changeFilters({ ...draft, documentType: compactListFilter(values) })}
          />
        </label>
        <label data-filter-active={Boolean(draft.operation)}>
          <span>{t("verifactu.management.fiscalOperation")}</span>
          <ErpListSelect
            values={listFilterValues(draft.operation)}
            options={operationOptions}
            aria-label={`${t("verifactu.management.fiscalOperation")}: ${listFilterValues(draft.operation).map(value => selectedLabel(operationOptions, value)).join(", ") || selectedLabel(operationOptions, "")}`}
            onChange={values => changeFilters({ ...draft, operation: compactListFilter(values) })}
          />
        </label>
        {filterError && <p role="alert">{t("verifactu.management.invalidDateRange")}</p>}
      </form>
      </FiscalWorkspaceDialog>}

      <section className="gestion-verifactu-table-panel gestion-module-panel erp-list-filter-host">
        <header>
          <div>
            <h3>{t("verifactu.management.defectiveTitle")}</h3>
            <span>{page.totalElements} {t("verifactu.management.records")}</span>
          </div>
          {loading && <span className="gestion-verifactu-loading" role="status" aria-live="polite">{t("verifactu.management.updating")}</span>}
        </header>
        <form className="gestion-fiscal-list-toolbar" onSubmit={(event) => event.preventDefault()}>
          <label className="gestion-fiscal-list-search"><span>{t("verifactu.management.documentNumber")}</span><GestionSearchFrame><input className="erp-search-input" maxLength={64} placeholder={t("verifactu.management.documentNumber")} value={draft.documentNumber} onChange={(event) => changeFilters({ ...draft, documentNumber: event.target.value })} /></GestionSearchFrame></label>
          <button type="button" aria-haspopup="dialog" aria-expanded={filtersOpen} onClick={() => setFiltersOpen(true)}><Funnel size={18} aria-hidden="true" />{t("verifactu.ui.filters")}</button>
        </form>
        <ErpFilterChips locale={locale} translate={t} onClear={clear} chips={[
          { key: "dateFrom", label: t("verifactu.management.dateFrom"), value: filters.dateFrom ? formatVerifactuDate(filters.dateFrom, locale) : "", onRemove: () => removeFilter("dateFrom") },
          { key: "dateTo", label: t("verifactu.management.dateTo"), value: filters.dateTo ? formatVerifactuDate(filters.dateTo, locale) : "", onRemove: () => removeFilter("dateTo") },
          ...listFilterChips("status", t("verifactu.management.status"), listFilterValues(filters.status), values => {
      const value = compactListFilter(values);
      setDraft(current => ({ ...current, status: value }));
      setFilters(current => ({ ...current, status: value, page: 0 }));
    }, value => verifactuStatusLabel(value, t)),
          ...listFilterChips("documentType", t("verifactu.management.documentType"), listFilterValues(filters.documentType), values => {
      const value = compactListFilter(values);
      setDraft(current => ({ ...current, documentType: value }));
      setFilters(current => ({ ...current, documentType: value, page: 0 }));
    }),
          ...listFilterChips("operation", t("verifactu.management.fiscalOperation"), listFilterValues(filters.operation), values => {
      const value = compactListFilter(values);
      setDraft(current => ({ ...current, operation: value }));
      setFilters(current => ({ ...current, operation: value, page: 0 }));
    }, value => verifactuOperationLabel(value, t)),
          { key: "documentNumber", label: t("verifactu.management.documentNumber"), value: filters.documentNumber, onRemove: () => removeFilter("documentNumber") }
        ]} />
        {!loading && error ? (
          <div className="gestion-verifactu-message error" role="alert">{t("verifactu.management.defectiveError")}</div>
        ) : !loading && page.items.length === 0 ? (
          <div className="gestion-verifactu-message">{t("verifactu.management.emptyDefective")}</div>
        ) : (
          <div ref={tableScrollRef} className="gestion-verifactu-table-scroll gestion-module-table erp-list-filter-table">
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
                  sortDirection={sorting.sort?.column === column.key ? sorting.sort.direction : null}
                  sortLabel={label}
                  onSort={changeSort}
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
              <tr key={item.recordId} tabIndex={0} onKeyDown={handleRowArrow}>
                {visibleColumns.map((column) => <td data-column-key={column.key} key={column.key}>{renderDefectiveCell(item, column.key, locale, timezone, t)}</td>)}
                <td>
                    <button
                      type="button"
                      className="gestion-verifactu-link-button gestion-module-action"
                      aria-label={`${t("verifactu.management.viewAttempts")} ${item.documentNumber}`}
                      onClick={(event) => onOpenAttempts(item.recordId, item.documentNumber, event.currentTarget)}
                    >
                      {t("verifactu.management.viewAttempts")}
                    </button>
                  </td>
                  <td>
                    <button
                      type="button"
                      className="gestion-verifactu-link-button gestion-module-action"
                      aria-label={`${t("verifactu.resolution.review")} ${item.documentNumber}`}
                      onClick={(event) => onOpenResolution(item.recordId, item.documentNumber, event.currentTarget)}
                    >
                      {t("verifactu.resolution.review")}
                    </button>
                  </td>
                </tr>
              ))}</tbody>
          </table>
          </div>
        )}
        <footer className="gestion-verifactu-pagination">
          <button type="button" disabled={loading || filters.page === 0} onClick={() => setFilters((current) => ({ ...current, page: Math.max(0, current.page - 1) }))}>{t("verifactu.management.previous")}</button>
          <span>{t("verifactu.management.page")} {page.page + 1} / {Math.max(1, page.totalPages)}</span>
          <button type="button" disabled={loading || page.totalPages === 0 || page.page + 1 >= page.totalPages} onClick={() => setFilters((current) => ({ ...current, page: current.page + 1 }))}>{t("verifactu.management.next")}</button>
        </footer>
        <form className="gestion-fiscal-list-datebar" onSubmit={(event) => event.preventDefault()}>
          <label><span>{t("verifactu.management.dateFrom")}</span><input type="date" value={draft.dateFrom} onChange={(event) => changeFilters({ ...draft, dateFrom: event.target.value })} /></label>
          <label><span>{t("verifactu.management.dateTo")}</span><input type="date" value={draft.dateTo} onChange={(event) => changeFilters({ ...draft, dateTo: event.target.value })} /></label>

          <ReportDateRangeSummary locale={locale} value={{ from: filters.dateFrom, to: filters.dateTo, label: "" }} />
          {filterError && <p role="alert">{t("verifactu.management.invalidDateRange")}</p>}
        </form>
      </section>
    </div>
  );
}

function selectedLabel(options: readonly { value: string; label: string }[], value: string) {
  return options.find((option) => option.value === value)?.label ?? "—";
}

function countActiveFilters(filters: VerifactuAdminDefectiveFilters) {
  return [filters.dateFrom, filters.dateTo, filters.status, filters.documentType, filters.operation, filters.documentNumber]
    .filter((value) => Boolean(value)).length;
}

function renderDefectiveCell(
  item: VerifactuAdminDefectiveRecordPage["items"][number],
  column: DefectiveSortColumn,
  locale: LocaleCode,
  timezone: string | null,
  t: VerifactuTranslator
) {
  if (column === "sequence") return <span className="numeric">{item.sequence}</span>;
  if (column === "document") return item.documentNumber;
  if (column === "documentType") return item.documentType;
  if (column === "fiscalOperation") return verifactuOperationLabel(item.operation, t);
  if (column === "issueDate") return formatVerifactuDate(item.issueDate, locale);
  if (column === "status") return <span className={`gestion-verifactu-state state-${item.status.toLowerCase()}`}>{verifactuStatusLabel(item.status, t)}</span>;
  if (column === "updatedAt") return formatVerifactuDateTime(item.updatedAt, locale, timezone);
  return fiscalErrorMessage(item.errorCode, t, locale) || "—";
}
