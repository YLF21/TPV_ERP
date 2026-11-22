import { useEffect, useRef, useState, type FormEvent } from "react";
import { useRemote } from "../../app/RefreshContext";
import { localeFor, useI18n } from "../../i18n";
import { ApiError, request } from "../../lib/api";
import type { AuditLog, AuditPage, Credentials } from "../../lib/types";
import { formatUtcDateTime } from "../../lib/utc-date-time.mjs";
import { query } from "../../lib/workspace-api";
import { DateRangePicker } from "../../shared/DateRangePicker";
import { ExcelExportButton } from "../../shared/table/DirectoryExcelExport";
import { SaasDataTable, type DataColumn } from "../../shared/table/SaasDataTable";
import { WorkspaceDialog } from "../../shared/WorkspaceDialog";
import { useDirectoryState } from "../../shared/directory-navigation";
import { Input, RetryError, SectionHeader } from "../../shared/ui";
import { auditActionLabel } from "./audit-action-labels";
import "./audit.css";

const AUDIT_PATH = "/api/v1/admin/audit";
const PAGE_SIZE = 50;
const emptyFilters = { from: "", to: "", username: "", action: "", q: "" };

export function AuditView({ credentials }: { credentials: Credentials }) {
  const { language, t } = useI18n();
  const identity = credentials.accessToken + ":audit:";
  const [draft, setDraft] = useDirectoryState(identity + "draft", emptyFilters);
  const [filters, setFilters] = useDirectoryState(identity + "filters", emptyFilters);
  const [page, setPage] = useDirectoryState(identity + "page", 0);
  const [sort, setSort] = useDirectoryState(identity + "sort", { key: "createdAt", direction: "desc" as "asc" | "desc" });
  const [opened, setOpened] = useState<AuditLog | null>(null);
  const tableScroll = useRef<HTMLDivElement>(null);
  const sortParameters = { sortBy: sort.key, sortDirection: sort.direction.toUpperCase() };
  const appliedQuery = query(filters);
  const draftFilters = { ...draft, username: draft.username.trim(), action: draft.action.trim(), q: draft.q.trim() };
  const hasPendingFilters = query(draftFilters) !== appliedQuery;
  const hasAppliedFilters = Object.values(filters).some(Boolean);
  const hasDraftFilters = Object.values(draft).some(Boolean);

  const search = useRemote(async () => {
    try {
      return await request<AuditPage>(credentials, `${AUDIT_PATH}/search${query({ ...filters, ...sortParameters, page, size: PAGE_SIZE })}`);
    } catch (reason) {
      throw new Error(t(reason instanceof ApiError && reason.status === 403 ? "auditReadForbidden" : "auditLoadFailed"));
    }
  }, [credentials.accessToken, appliedQuery, page, language, sort.key, sort.direction]);
  const result = search.data;
  const pages = result ? Math.max(1, Math.ceil(result.total / result.size)) : 0;
  const locale = localeFor(language);
  const numbers = new Intl.NumberFormat(locale);
  useEffect(() => { tableScroll.current?.scrollTo({ top: 0 }); }, [appliedQuery, page, sort.key, sort.direction]);

  const columns: DataColumn<AuditLog, string>[] = [
    { key: "createdAt", label: t("auditDate"), defaultWidth: 205, sortKey: "createdAt",
      render: row => <time dateTime={row.createdAt}>{formatUtcDateTime(row.createdAt, locale, true)}</time> },
    { key: "username", label: t("username"), defaultWidth: 140, sortKey: "username", render: row => <span title={row.username}>{row.username}</span> },
    { key: "action", label: t("auditAction"), defaultWidth: 235, sortKey: "action", render: row => {
      const label = auditActionLabel(row.action, language);
      return <span title={label ? `${label} (${row.action})` : row.action}>{label || row.action}{label && <small>{row.action}</small>}</span>;
    } },
    { key: "targetType", label: t("auditTargetType"), defaultWidth: 160, sortKey: "targetType", render: row => <span title={row.targetType}>{row.targetType}</span> },
    { key: "targetId", label: t("auditTargetId"), defaultWidth: 225, sortKey: "targetId", render: row => <span title={row.targetId}>{row.targetId || "—"}</span> },
    { key: "details", label: t("auditDetails"), defaultWidth: 310, sortKey: "details", render: row => <span title={row.details || undefined}>{row.details || "—"}</span> },
    { key: "id", label: t("auditId"), defaultWidth: 280, defaultVisible: false, sortKey: "id", render: row => <span title={row.id}>{row.id}</span> },
  ];

  function changeSort(key: string) {
    setSort(current => ({ key, direction: current.key === key && current.direction === "asc" ? "desc" : "asc" }));
    setPage(0);
  }

  function applyFilters(event: FormEvent) {
    event.preventDefault();
    setDraft(draftFilters);
    if (hasPendingFilters) setFilters(draftFilters);
    else if (page === 0) search.reload();
    setPage(0);
  }

  function clearFilters() {
    setDraft(emptyFilters);
    setFilters(emptyFilters);
    setPage(0);
  }

  function exportError(reason: unknown) {
    if (reason instanceof ApiError) {
      if (reason.status === 413) return t("auditExportTooLarge");
      if (reason.status === 403) return t("auditReadForbidden");
    }
    return t("auditExportFailed");
  }

  return (
    <section className="content-section table-workspace audit-workspace">
      <SectionHeader title={t("adminAudit")} subtitle={t("auditHelp")} />
      <p className="module-help audit-table-hint">{t("auditTableHint")}</p>
      <div className="audit-toolbar">
        <form className="toolbar audit-filters" aria-label={t("auditFilters")} onSubmit={applyFilters}>
          <DateRangePicker from={draft.from} to={draft.to}
            fromLabel={t("auditFrom")} toLabel={t("auditTo")}
            onChange={range => setDraft(current => ({ ...current, ...range }))} />
          <Input label={t("username")} value={draft.username} maxLength={250}
            onChange={username => setDraft(current => ({ ...current, username }))} />
          <Input label={t("auditAction")} value={draft.action} maxLength={250}
            onChange={action => setDraft(current => ({ ...current, action }))} />
          <Input label={t("auditText")} value={draft.q} maxLength={250}
            onChange={q => setDraft(current => ({ ...current, q }))} />
          <div className="audit-filter-actions">
            <button className="primary-button" type="submit">{t("auditApplyFilters")}</button>
            <button className="secondary-button" type="button" onClick={clearFilters}
              disabled={!hasDraftFilters && !hasAppliedFilters}>{t("auditClearFilters")}</button>
          </div>
        </form>
        <ExcelExportButton credentials={credentials} filename="espos-saas-audit"
          path={`${AUDIT_PATH}/export${query({ ...filters, ...sortParameters, language })}`}
          disabled={!result || hasPendingFilters} describeError={exportError} />
      </div>
      <div className="audit-table-region" aria-busy={search.loading}>
        <SaasDataTable username={credentials.username} tableKey="audit" label={t("adminAudit")}
          columns={columns} rows={result?.items ?? []} sort={sort} onSort={changeSort}
          onOpen={setOpened} disabled={opened !== null || search.loading} scrollRef={tableScroll} />
        {search.error && <RetryError message={search.error} onRetry={search.reload} />}
        {result?.items.length === 0 && <p role="status">{t(hasAppliedFilters ? "auditNoMatches" : "noAuditActions")}</p>}
      </div>
      {opened && <WorkspaceDialog title={t("auditEntryDetail")} subtitle={t("auditHelp")} closeLabel={t("close")}
        busy={false} onClose={() => setOpened(null)} className="audit-detail-dialog"
        restoreFocus={() => tableScroll.current?.querySelector<HTMLTableRowElement>(`tr[data-row-id="${CSS.escape(opened.id)}"]`)
          ?? tableScroll.current?.querySelector<HTMLTableRowElement>('tr[aria-selected="true"]') ?? null}>
        <dl className="audit-entry-detail">{columns.map(column => <div key={column.key} className={column.key === "details" ? "audit-entry-full" : undefined}>
          <dt>{column.label}</dt><dd>{column.render(opened)}</dd>
        </div>)}</dl>
      </WorkspaceDialog>}
      <div className="audit-footer">
        <p role="status">{result ? t("auditTotal").replace("{total}", numbers.format(result.total)) : search.loading ? t("auditLoading") : ""}</p>
        <nav className="pagination-controls" aria-label={t("pageLabel")}>
          <button type="button" className="small-button" disabled={!result || page === 0}
            onClick={() => setPage(page - 1)}>{t("previousPage")}</button>
          <span>{t("pageLabel")} {numbers.format(page + 1)}{pages > 0 ? ` / ${numbers.format(pages)}` : ""}</span>
          <button type="button" className="small-button" disabled={!result || page + 1 >= pages}
            onClick={() => setPage(page + 1)}>{t("nextPage")}</button>
        </nav>
      </div>
    </section>
  );
}
