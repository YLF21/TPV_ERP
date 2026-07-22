import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { ErpSelect, type LocaleCode } from "@tpverp/app-common";
import {
  loadVerifactuAdminDefectiveRecords,
  verifactuDocumentTypes,
  verifactuOperations,
  type VerifactuAdminDefectiveFilters,
  type VerifactuAdminDefectiveRecordPage
} from "./verifactuManagementApi";
import {
  formatVerifactuDateTime,
  verifactuOperationLabel,
  verifactuStatusLabel,
  type VerifactuTranslator
} from "./verifactuPresentation";

const defectiveStatuses = ["RECHAZADO", "DEFECTUOSO", "ACEPTADO_CON_ERRORES"] as const;

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
  token,
  revision,
  t,
  onOpenAttempts,
  onOpenResolution
}: {
  locale: LocaleCode;
  token?: string;
  revision: number;
  t: VerifactuTranslator;
  onOpenAttempts: (recordId: string, documentNumber: string, returnFocus: HTMLElement) => void;
  onOpenResolution: (recordId: string, documentNumber: string, returnFocus: HTMLElement) => void;
}) {
  const [draft, setDraft] = useState(emptyFilters);
  const [filters, setFilters] = useState(emptyFilters);
  const [page, setPage] = useState(emptyPage);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [filterError, setFilterError] = useState(false);
  const request = useRef(0);

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

  function apply(event: FormEvent) {
    event.preventDefault();
    if (draft.dateFrom && draft.dateTo && draft.dateFrom > draft.dateTo) {
      setFilterError(true);
      return;
    }
    setFilterError(false);
    setFilters({ ...draft, page: 0 });
  }

  function clear() {
    setFilterError(false);
    setDraft(emptyFilters);
    setFilters(emptyFilters);
  }

  return (
    <div className="gestion-verifactu-defective">
      <form className="gestion-verifactu-filters" onSubmit={apply}>
        <label>
          <span>{t("verifactu.management.dateFrom")}</span>
          <input type="date" value={draft.dateFrom} onChange={(event) => setDraft({ ...draft, dateFrom: event.target.value })} />
        </label>
        <label>
          <span>{t("verifactu.management.dateTo")}</span>
          <input type="date" value={draft.dateTo} onChange={(event) => setDraft({ ...draft, dateTo: event.target.value })} />
        </label>
        <label>
          <span>{t("verifactu.management.status")}</span>
          <ErpSelect
            value={draft.status}
            options={statusOptions}
            aria-label={`${t("verifactu.management.status")}: ${selectedLabel(statusOptions, draft.status)}`}
            onChange={(value) => setDraft({ ...draft, status: value as VerifactuAdminDefectiveFilters["status"] })}
          />
        </label>
        <label>
          <span>{t("verifactu.management.documentType")}</span>
          <ErpSelect
            value={draft.documentType}
            options={documentTypeOptions}
            aria-label={`${t("verifactu.management.documentType")}: ${selectedLabel(documentTypeOptions, draft.documentType)}`}
            onChange={(value) => setDraft({ ...draft, documentType: value as VerifactuAdminDefectiveFilters["documentType"] })}
          />
        </label>
        <label>
          <span>{t("verifactu.management.fiscalOperation")}</span>
          <ErpSelect
            value={draft.operation}
            options={operationOptions}
            aria-label={`${t("verifactu.management.fiscalOperation")}: ${selectedLabel(operationOptions, draft.operation)}`}
            onChange={(value) => setDraft({ ...draft, operation: value as VerifactuAdminDefectiveFilters["operation"] })}
          />
        </label>
        <label className="gestion-verifactu-number-filter">
          <span>{t("verifactu.management.documentNumber")}</span>
          <input maxLength={64} value={draft.documentNumber} onChange={(event) => setDraft({ ...draft, documentNumber: event.target.value })} />
        </label>
        <div className="gestion-verifactu-filter-actions">
          <button type="submit" className="primary">{t("verifactu.management.applyFilters")}</button>
          <button type="button" onClick={clear}>{t("verifactu.management.clearFilters")}</button>
        </div>
        {filterError && <p role="alert">{t("verifactu.management.invalidDateRange")}</p>}
      </form>

      <section className="gestion-verifactu-table-panel">
        <header>
          <div>
            <h3>{t("verifactu.management.defectiveTitle")}</h3>
            <span>{page.totalElements} {t("verifactu.management.records")}</span>
          </div>
          {loading && <span className="gestion-verifactu-loading" role="status" aria-live="polite">{t("verifactu.management.updating")}</span>}
        </header>
        {!loading && error ? (
          <div className="gestion-verifactu-message error" role="alert">{t("verifactu.management.defectiveError")}</div>
        ) : !loading && page.items.length === 0 ? (
          <div className="gestion-verifactu-message">{t("verifactu.management.emptyDefective")}</div>
        ) : (
          <div className="gestion-verifactu-table-scroll">
            <table className="gestion-verifactu-table">
              <thead><tr>
                <th>{t("verifactu.management.sequence")}</th>
                <th>{t("verifactu.management.document")}</th>
                <th>{t("verifactu.management.documentType")}</th>
                <th>{t("verifactu.management.fiscalOperation")}</th>
                <th>{t("verifactu.management.issueDate")}</th>
                <th>{t("verifactu.management.status")}</th>
                <th>{t("verifactu.management.updatedAt")}</th>
                <th>{t("verifactu.management.errorCode")}</th>
                <th>{t("verifactu.management.attempts")}</th>
                <th>{t("verifactu.resolution.actions")}</th>
              </tr></thead>
              <tbody>{page.items.map((item) => (
                <tr key={item.recordId}>
                  <td>{item.sequence}</td>
                  <td>{item.documentNumber}</td>
                  <td>{item.documentType}</td>
                  <td>{verifactuOperationLabel(item.operation, t)}</td>
                  <td>{formatDate(item.issueDate, locale)}</td>
                  <td><span className={`gestion-verifactu-state state-${item.status.toLowerCase()}`}>{verifactuStatusLabel(item.status, t)}</span></td>
                  <td>{formatVerifactuDateTime(item.updatedAt, locale)}</td>
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
              ))}</tbody>
            </table>
          </div>
        )}
        <footer className="gestion-verifactu-pagination">
          <button type="button" disabled={loading || filters.page === 0} onClick={() => setFilters((current) => ({ ...current, page: Math.max(0, current.page - 1) }))}>{t("verifactu.management.previous")}</button>
          <span>{t("verifactu.management.page")} {page.page + 1} / {Math.max(1, page.totalPages)}</span>
          <button type="button" disabled={loading || page.totalPages === 0 || page.page + 1 >= page.totalPages} onClick={() => setFilters((current) => ({ ...current, page: current.page + 1 }))}>{t("verifactu.management.next")}</button>
        </footer>
      </section>
    </div>
  );
}

function selectedLabel(options: readonly { value: string; label: string }[], value: string) {
  return options.find((option) => option.value === value)?.label ?? "—";
}

function formatDate(value: string, locale: LocaleCode) {
  const date = new Date(`${value}T00:00:00`);
  return Number.isNaN(date.getTime()) ? "—" : new Intl.DateTimeFormat(locale, { dateStyle: "short" }).format(date);
}
