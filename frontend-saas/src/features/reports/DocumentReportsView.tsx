import { useState, type FormEvent } from "react";
import { useRemote } from "../../app/RefreshContext";
import { localeFor, useI18n } from "../../i18n";
import { request } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import { ReportTools, useReportPreferences } from "../../shared/analytics/ReportTools";
import { FilterDisclosure } from "../../shared/analytics/FilterDisclosure";
import { Input, PaginationControls, StatusPill } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";
import { useSummaryCategoryLabel } from "../dashboard/labels";
import { PeriodFilterFields } from "./PeriodFilterFields";
import { FilterActions } from "./PeriodShortcuts";
import { nextSort, ReportTable, type ReportColumn, type ReportSort } from "./ReportTable";
import { readAnalyticsFilters, useAnalyticsContext } from "./analytics-context";
import { filtersQuery, formatDecimal, initialFilters, reportPageSize, restorePeriodFilters,
  statisticsPath, validateFilters } from "./report-query";
import { useStatisticsLabels } from "./statistics-labels";
import type { StatisticsFilters, StatisticsOptions, StatisticsReport } from "./statistics-types";
import { useReportPage } from "./useReportPage";

const path = "/api/v1/admin/reports/documents";
const defaultSort: ReportSort = { key: "DATE", direction: "DESC" };
const technicalColumns = ["lines", "relationships"];
type DocumentFilters = StatisticsFilters & { currency: string; productId: string; productCode: string; productName: string };
type DocumentRow = {
  documentId: string;
  type: string;
  number: string | null;
  status: string;
  businessDate: string;
  companyId: string;
  companyName: string | null;
  storeId: string | null;
  storeName: string | null;
  currency: string;
  total: string;
  eligible: boolean | null;
  linesComplete: boolean;
  relationshipsComplete: boolean;
};
type DocumentPage = Pick<StatisticsReport, "asOf" | "period" | "coverage" | "page" | "size"> & {
  totalRows: number;
  rows: DocumentRow[];
};

function readFilters(data: Record<string, string>): DocumentFilters | null {
  const range = restorePeriodFilters({ ...data, comparison: "NONE" }, { ...initialFilters(), comparison: "NONE" });
  if (!range) return null;
  const currency = /^[A-Z]{3}$/.test(data.currency) ? data.currency : "";
  const productId = data.productId ?? "";
  return {
    ...range, currency, productId, productCode: productId ? "" : data.productCode?.slice(0, 120) ?? "",
    productName: data.productName?.slice(0, 200) ?? "",
  };
}

function readSort(data: Record<string, string>): ReportSort {
  if (!["DATE", "NUMBER", "AMOUNT", "CURRENCY"].includes(data.sort)) return defaultSort;
  return { key: data.sort, direction: data.direction === "ASC" ? "ASC" : "DESC" };
}

export function DocumentReportsView({ credentials }: { credentials: Credentials }) {
  const { t, language } = useI18n();
  const s = useStatisticsLabels();
  const category = useSummaryCategoryLabel();
  const locale = localeFor(language);
  const [link] = useState(() => readAnalyticsFilters(credentials, "documents"));
  const [draft, setDraft] = useState(() => readFilters(link) ?? readFilters({})!);
  const [applied, setApplied] = useState(draft);
  const [sort, setSort] = useState(() => readSort(link));
  const [validation, setValidation] = useState<ReturnType<typeof validateFilters> | "invalidCurrency">(null);
  const preferences = useReportPreferences(credentials, "documents", technicalColumns);
  const options = useRemote(
    () => request<StatisticsOptions>(credentials, statisticsPath + "/options"),
    [credentials.accessToken],
  );
  const query = filtersQuery(applied, false);
  query.delete("comparison");
  for (const key of ["currency", "productId", "productCode"] as const) if (applied[key]) query.set(key, applied[key]);
  query.set("sort", sort.key);
  query.set("direction", sort.direction);
  const report = useReportPage<DocumentPage>(credentials, path, query, (data) => data.totalRows);
  const result = report.result;
  const rows = result?.rows ?? [];
  const count = new Intl.NumberFormat(locale).format;
  const date = new Intl.DateTimeFormat(locale, { dateStyle: "short", timeZone: "UTC" });
  const back = /^#\/reports(?:\/(?:sales|averages|provinces|articles|inventory|stock))?(?:\?.*)?$/.test(link.return)
    ? link.return : "#/reports";
  useAnalyticsContext(credentials, "documents", { ...applied, sort: sort.key, direction: sort.direction, page: String(report.page), return: back });

  function apply(next: DocumentFilters, nextSort = sort) {
    const invalid = validateFilters(next) ?? (next.currency && !/^[A-Z]{3}$/.test(next.currency) ? "invalidCurrency" : null);
    setValidation(invalid);
    if (invalid) return;
    if (report.page === 0 && JSON.stringify(next) === JSON.stringify(applied)
      && nextSort.key === sort.key && nextSort.direction === sort.direction) report.reload();
    setApplied(next);
    setSort(nextSort);
    report.setPage(0);
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    apply(draft);
  }

  function reset() {
    const next = readFilters({})!;
    setDraft(next);
    apply(next, defaultSort);
  }

  const completeness = (complete: boolean) => <StatusPill status={s(complete ? "complete" : "incomplete")} tone={complete ? "ok" : "warning"} />;
  const columns: ReportColumn<DocumentRow>[] = [
    { id: "date", label: s("businessDate"), sort: "DATE", required: true, cell: (row) => <time dateTime={row.businessDate}>
      {date.format(new Date(row.businessDate + "T00:00:00Z"))}
    </time> },
    { id: "number", label: t("documentNumber"), sort: "NUMBER", required: true, rowHeader: true, cell: (row) => row.number || s("noDocumentNumber") },
    { id: "type", label: t("type"), cell: (row) => category(row.type) },
    { id: "status", label: t("status"), cell: (row) => category(row.status) },
    { id: "company", label: s("COMPANY"), cell: (row) => row.companyName || row.companyId },
    { id: "store", label: s("STORE"), cell: (row) => row.storeName || s(row.storeId ? "unknownStoreName" : "unknownStore") },
    { id: "currency", label: t("currency"), sort: "CURRENCY", required: true, cell: (row) => row.currency },
    { id: "amount", label: s("sourceAmount"), sort: "AMOUNT", numeric: true, cell: (row) => formatDecimal(row.total, locale, 2) },
    { id: "eligible", label: s("eligible"), cell: (row) => <StatusPill
      status={s(row.eligible == null ? "eligibilityPending" : row.eligible ? "counted" : "excluded")}
      tone={row.eligible == null ? "warning" : row.eligible ? "ok" : "muted"} /> },
    { id: "lines", label: s("lines"), defaultHidden: true, cell: (row) => completeness(row.linesComplete) },
    { id: "relationships", label: s("relationships"), defaultHidden: true, cell: (row) => completeness(row.relationshipsComplete) },
  ];

  return <div className="saas-summary-view saas-statistics view-grid">
    <section className="saas-summary-panel" aria-labelledby="document-report-heading">
      <header className="saas-summary-heading">
        <h2 id="document-report-heading">{s("documentDetail")}</h2>
        <a className="secondary-button report-link" href={back}>{s("backToReport")}</a>
      </header>
      <div className="saas-summary-panel-body">
        <FilterDisclosure filters={applied} options={options.data} dirty={JSON.stringify(draft) !== JSON.stringify(applied)}>
        <form id="document-filters" aria-label={s("filters")} onSubmit={submit}>
          <div className="statistics-filter-grid">
            <PeriodFilterFields draft={draft} options={options.data} comparison={false} onChange={(changes) => {
              setDraft({ ...draft, ...changes });
              setValidation(null);
            }} />
            <Input label={t("currency")} value={draft.currency}
              onChange={(currency) => { setDraft({ ...draft, currency: currency.trim().toUpperCase().slice(0, 3) }); setValidation(null); }} />
          </div>
          {(draft.productId || draft.productCode) && <div className="report-document-scope">
            <span>{draft.productName || draft.productCode || draft.productId}</span>
            <button type="button" className="small-button"
              onClick={() => setDraft({ ...draft, productId: "", productCode: "", productName: "" })}>{s("clearArticle")}</button>
          </div>}
          {validation && <p className="notice error" role="alert">{s(validation)}</p>}
        </form>
        </FilterDisclosure>
        <ReportTools credentials={credentials} preferences={preferences} filters={{
          ...applied,
          companyName: options.data?.companies.find((item) => item.id === applied.companyId)?.name ?? "",
          storeName: options.data?.stores.find((item) => item.id === applied.storeId)?.name ?? "",
        }} columns={columns} exportPath={result ? "/api/v1/admin/reports/export/documents?" + query : undefined}
          leadingAction={<>
            <button type="submit" className="primary-button" form="document-filters">{s("apply")}</button>
            <FilterActions dirty={JSON.stringify(draft) !== JSON.stringify(applied)} onReset={reset} />
          </>} />
        <LoadState {...options} />
      </div>
    </section>
    <section className="saas-summary-panel" aria-busy={report.loading} aria-labelledby="document-report-results">
      <header className="saas-summary-heading"><h2 id="document-report-results">{s("documents")}</h2></header>
      <div className="saas-summary-panel-body">
        <LoadState {...report} />
        {result && <div className="saas-summary-coverage">
          <h3>{s("coverage")}</h3>
          <dl>
            <div><dt>{s("unknownRelationships")}</dt><dd>{count(result.coverage.unknownRelationships)}</dd></div>
            <div><dt>{s("incompleteCurrent")}</dt><dd>{count(result.coverage.currentIncompleteDocuments)}</dd></div>
          </dl>
          <p>{s("salesDateScope")}</p>
        </div>}
        <ReportTable title={s("documentDetail")} columns={columns} rows={rows} rowKey={(row) => row.companyId + ":" + row.documentId}
          visible={preferences.isVisible} empty={s(report.error ? "noReport" : report.loading ? "loadingReport" : "noDocuments")}
          sort={sort} onSort={(key) => setSort(nextSort(sort, key, key === "DATE" || key === "AMOUNT" ? "DESC" : "ASC"))} />
        <PaginationControls page={(result?.page ?? report.page) + 1}
          pages={Math.max(1, Math.ceil((result?.totalRows ?? 0) / (result?.size ?? reportPageSize)))}
          pageSize={result?.size ?? reportPageSize} total={result?.totalRows ?? 0} rows={rows}
          setPage={(page) => { if (!report.loading && typeof page === "number") report.setPage(page - 1); }} />
      </div>
    </section>
  </div>;
}
