import { useState, type FormEvent } from "react";
import { useRemote } from "../../app/RefreshContext";
import { localeFor, useI18n } from "../../i18n";
import { request } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import { AnalyticalChart } from "../../shared/analytics/AnalyticalChart";
import { FilterDisclosure } from "../../shared/analytics/FilterDisclosure";
import { ReportTools, useReportPreferences } from "../../shared/analytics/ReportTools";
import { Input, PaginationControls } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";
import { PeriodFilterFields } from "./PeriodFilterFields";
import { FilterActions } from "./PeriodShortcuts";
import { nextSort, ReportTable, type ReportColumn, type ReportSort } from "./ReportTable";
import { readAnalyticsFilters, useAnalyticsContext } from "./analytics-context";
import { useArticleLabels } from "./article-labels";
import { chartGroupLimit, loadChartReport } from "./chart-report";
import { chartAmount, documentReportLink, filtersQuery, formatDecimal, initialFilters, missing, reportLinkFilters, reportPageSize,
  reportQuery, restorePeriodFilters, statisticsPath, validateFilters } from "./report-query";
import { useStatisticsLabels } from "./statistics-labels";
import type { StatisticsFilters, StatisticsOptions, StatisticsPeriod, StatisticsReport } from "./statistics-types";
import { useReportPage } from "./useReportPage";

const articlePath = "/api/v1/admin/reports/articles";
const defaultSort: ReportSort = { key: "AMOUNT", direction: "DESC" };
const articleSorts = ["AMOUNT", "QUANTITY", "RETURNS", "CODE"];
const optionalColumns = [
  "rank", "quantitySold", "quantityReturned", "salesAmount", "returnsAmount",
  "saleDocuments", "averageQuantity", "amountShare", "priorQty", "deltaQty",
  "priorAmount", "deltaAmount", "deltaPercent", "priorAverageQuantity", "priorShare",
];
type ArticleMetrics = {
  quantitySold: string;
  quantityReturned: string;
  netQuantity: string;
  salesAmount: string;
  returnsAmount: string;
  netAmount: string;
  saleDocuments: number;
  averageQuantityPerSale: string | null;
  amountSharePercent: string | null;
};
type ArticleRow = {
  rank: number;
  companyId: string;
  companyName: string;
  storeId: string | null;
  storeName: string | null;
  productId: string | null;
  productCode: string | null;
  productName: string;
  currency: string;
  current: ArticleMetrics | null;
  comparison: ArticleMetrics | null;
  deltaAmount: string | null;
  deltaPercent: string | null;
  deltaQuantity: string | null;
};
type ArticlePage = Pick<StatisticsReport, "asOf" | "period" | "comparisonPeriod" | "coverage" | "page" | "size"> & {
  totalRows: number;
  rows: ArticleRow[];
};
type ArticleFilters = StatisticsFilters & { search: string };

function articleFilters(values: Record<string, string> = {}): ArticleFilters {
  const defaults = initialFilters();
  return { ...(restorePeriodFilters(values, defaults) ?? defaults), search: values.search?.slice(0, 120) ?? "" };
}

function rowKey(row: ArticleRow) {
  const product = row.productId ? "id:" + row.productId : "code:" + row.productCode;
  return [row.companyId, row.storeId, product, row.currency].join(":");
}

export function ArticleReportsView({ credentials }: { credentials: Credentials }) {
  const s = useStatisticsLabels();
  const l = useArticleLabels();
  const { language, t } = useI18n();
  const locale = localeFor(language);
  const [initial] = useState(() => {
    const link = reportLinkFilters();
    return Object.keys(link).length ? link : readAnalyticsFilters(credentials, "articles");
  });
  const [draft, setDraft] = useState(() => articleFilters(initial));
  const [applied, setApplied] = useState(draft);
  const [sort, setSort] = useState(() => restoreSort(initial));
  const [chartCurrency, setChartCurrency] = useState(() => /^[A-Z]{3}$/.test(initial.chartCurrency) ? initial.chartCurrency : "");
  const [validation, setValidation] = useState<ReturnType<typeof validateFilters>>(null);
  const preferences = useReportPreferences(credentials, "articles", optionalColumns);
  const options = useRemote(
    () => request<StatisticsOptions>(credentials, statisticsPath + "/options"),
    [credentials.accessToken],
  );
  const scope = filtersQuery(applied, false);
  if (applied.search) scope.set("search", applied.search);
  const query = new URLSearchParams(scope);
  query.set("sort", sort.key);
  query.set("direction", sort.direction);
  const report = useReportPage<ArticlePage>(credentials, articlePath, query, (data) => data.totalRows);
  const chartQuery = new URLSearchParams(scope);
  chartQuery.set("sort", defaultSort.key);
  chartQuery.set("direction", defaultSort.direction);
  const chart = useRemote(() => loadChartReport<ArticleRow, ArticlePage>(
    (page, size) => request<ArticlePage>(credentials, articlePath + "?" + reportQuery(chartQuery, page, size)),
    (data) => data.totalRows,
    rowKey,
  ), [credentials.accessToken, chartQuery.toString()]);
  const dirty = JSON.stringify(draft) !== JSON.stringify(applied);
  const chartNotice = chart.data?.limitReached ? l("rankingChartLimit").replace("{count}", String(chartGroupLimit))
    : chart.error || (chart.data && !chart.data.report) ? s("chartUnavailable") : chart.loading ? s("loadingChart") : "";
  const result = report.result;
  const comparing = result ? result.comparisonPeriod !== null : applied.comparison !== "NONE";
  const rows = result?.rows ?? [];
  const count = new Intl.NumberFormat(locale).format;
  const amount = (value: string | null | undefined, signed = false) => formatDecimal(value, locale, 2, signed);
  const quantity = (value: string | null | undefined, signed = false) => formatDecimal(value, locale, 3, signed);
  const percent = (value: string | null | undefined) => value == null ? missing : amount(value) + " %";
  const incomplete = result && (result.coverage.unknownRelationships > 0 || result.coverage.currentIncompleteDocuments > 0
    || (result.coverage.comparisonIncompleteDocuments ?? 0) > 0);
  useAnalyticsContext(credentials, "articles", {
    ...applied, sort: sort.key, direction: sort.direction, page: String(report.page), chartCurrency,
  });

  function apply(next: ArticleFilters, nextSort = sort) {
    const invalid = validateFilters(next);
    setValidation(invalid);
    if (invalid) return;
    const trimmed = { ...next, search: next.search.trim() };
    if (JSON.stringify(trimmed) === JSON.stringify(applied)) {
      if (report.page === 0 && JSON.stringify(nextSort) === JSON.stringify(sort)) report.reload();
      chart.reload();
    }
    setDraft(trimmed);
    setApplied(trimmed);
    setSort(nextSort);
    report.setPage(0);
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    apply(draft);
  }

  function relatedReports(row: ArticleRow) {
    if (!result || !row.storeId || !row.productId) return missing;
    const product = { companyId: row.companyId, storeId: row.storeId, productId: row.productId, productName: row.productName };
    const details = { ...product, province: applied.province, currency: row.currency };
    const backQuery = new URLSearchParams(query);
    backQuery.set("page", String(result.page));
    if (chartCurrency) backQuery.set("chartCurrency", chartCurrency);
    const back = "#/reports/articles?" + backQuery;
    const movement = new URLSearchParams(filtersQuery(applied, false));
    for (const [key, value] of Object.entries(product)) movement.set(key, value);
    movement.set("return", back);
    const stock = new URLSearchParams({ ...product, from: result.period.from, to: result.period.to, mode: "stock", return: back });
    return <div className="report-row-links">
      <a className="small-button report-link" href={documentReportLink({ ...details, ...result.period }, back)}
        aria-label={s("viewDocuments") + " · " + row.productName + " · " + s("current")}>{l("viewSales")}</a>
      {result.comparisonPeriod && <a className="small-button report-link" href={documentReportLink({ ...details, ...result.comparisonPeriod }, back)}
        aria-label={s("viewDocuments") + " · " + row.productName + " · " + s("compared")}>{l("viewComparisonSales")}</a>}
      <a className="small-button report-link" href={"#/reports/inventory?" + stock}
        aria-label={l("viewStock") + " · " + row.productName}>{l("viewStock")}</a>
      <a className="small-button report-link" href={"#/reports/movements?" + movement}
        aria-label={s("movements") + " · " + row.productName}>{s("movements")}</a>
    </div>;
  }

  const columns: ReportColumn<ArticleRow>[] = [
    { id: "rank", label: l("rank"), numeric: true, cell: (row) => count(row.rank) },
    { id: "company", label: s("COMPANY"), minWidth: 110, cell: (row) => row.companyName },
    { id: "store", label: s("STORE"), minWidth: 110, cell: (row) => row.storeName ?? s("unknownStore") },
    { id: "code", label: l("code"), minWidth: 100, sort: "CODE", cell: (row) => row.productCode || missing },
    { id: "product", label: l("article"), minWidth: 180, required: true, sticky: true, rowHeader: true, cell: (row) => row.productName },
    { id: "currency", label: t("currency"), required: true, cell: (row) => row.currency },
    { id: "quantitySold", label: l("quantitySold"), numeric: true, cell: (row) => quantity(row.current?.quantitySold) },
    { id: "quantityReturned", label: l("quantityReturned"), numeric: true, cell: (row) => quantity(row.current?.quantityReturned) },
    { id: "netQuantity", label: l("netQuantity"), sort: "QUANTITY", numeric: true, cell: (row) => quantity(row.current?.netQuantity) },
    { id: "salesAmount", label: l("salesAmount"), numeric: true, cell: (row) => amount(row.current?.salesAmount) },
    { id: "returnsAmount", label: l("returnsAmount"), sort: "RETURNS", numeric: true, cell: (row) => amount(row.current?.returnsAmount) },
    { id: "netAmount", label: l("netAmount"), sort: "AMOUNT", numeric: true, cell: (row) => amount(row.current?.netAmount) },
    { id: "saleDocuments", label: l("saleDocuments"), numeric: true, cell: (row) => row.current?.saleDocuments == null ? missing : count(row.current.saleDocuments) },
    { id: "averageQuantity", label: l("averageQuantityPerSale"), numeric: true, cell: (row) => quantity(row.current?.averageQuantityPerSale) },
    { id: "amountShare", label: l("amountSharePercent"), numeric: true, cell: (row) => percent(row.current?.amountSharePercent) },
  ];
  if (comparing) columns.push(
    { id: "priorQty", label: l("previousQuantity"), numeric: true, cell: (row) => quantity(row.comparison?.netQuantity) },
    { id: "deltaQty", label: l("deltaQuantity"), numeric: true, cell: (row) => quantity(row.deltaQuantity, true) },
    { id: "priorAmount", label: l("previousAmount"), numeric: true, cell: (row) => amount(row.comparison?.netAmount) },
    { id: "deltaAmount", label: s("difference"), numeric: true, cell: (row) => amount(row.deltaAmount, true) },
    { id: "deltaPercent", label: s("percent"), numeric: true, cell: (row) => row.deltaPercent == null ? missing : amount(row.deltaPercent, true) + " %" },
    { id: "priorAverageQuantity", label: l("previousAverageQuantity"), numeric: true, cell: (row) => quantity(row.comparison?.averageQuantityPerSale) },
    { id: "priorShare", label: l("previousAmountShare"), numeric: true, cell: (row) => percent(row.comparison?.amountSharePercent) },
  );
  columns.push({ id: "document", label: l("relatedReports"), required: true, sticky: true, cell: relatedReports });
  for (const column of columns) column.defaultHidden = optionalColumns.includes(column.id);

  return <div className="saas-summary-view saas-statistics view-grid">
    <section className="saas-summary-panel" aria-labelledby="article-report-heading">
      <header className="saas-summary-heading"><h2 id="article-report-heading">{s("articles")}</h2></header>
      <div className="saas-summary-panel-body">
        <FilterDisclosure filters={applied} options={options.data} dirty={dirty}>
          <form id="article-filters" aria-label={s("filters")} onSubmit={submit}>
            <div className="statistics-filter-grid">
              <PeriodFilterFields draft={draft} options={options.data} onChange={(changes) => {
                setDraft({ ...draft, ...changes });
                setValidation(null);
              }} />
              <Input label={l("search")} type="search" value={draft.search} onChange={(search) => setDraft({ ...draft, search: search.slice(0, 120) })} />
            </div>
            {validation && <p className="notice error" role="alert">{s(validation)}</p>}
          </form>
        </FilterDisclosure>
        <ReportTools credentials={credentials} preferences={preferences} columns={columns}
          exportPath={result ? "/api/v1/admin/reports/export/articles?" + query : undefined}
          leadingAction={<>
            <button type="submit" className="primary-button" form="article-filters">{s("apply")}</button>
            <FilterActions dirty={dirty} onReset={() => apply(articleFilters(), defaultSort)} />
          </>} />
        <LoadState {...options} />
      </div>
    </section>
    <section className="saas-summary-panel" aria-busy={report.loading} aria-labelledby="article-ranking-heading">
      <header className="saas-summary-heading">
        <h2 id="article-ranking-heading">{l("ranking")}</h2>
        <PaginationControls page={(result?.page ?? report.page) + 1}
          pages={Math.max(1, Math.ceil((result?.totalRows ?? 0) / (result?.size ?? reportPageSize)))}
          pageSize={result?.size ?? reportPageSize} total={result?.totalRows ?? 0} rows={rows}
          setPage={(page) => { if (!report.loading && typeof page === "number") report.setPage(page - 1); }} />
      </header>
      <div className="saas-summary-panel-body">
        <LoadState {...report} />
        {result && <div className="saas-summary-coverage">
          <h3>{s("coverage")}</h3>
          <dl>
            <div><dt>{s("unknownRelationships")}</dt><dd>{count(result.coverage.unknownRelationships)}</dd></div>
            <div><dt>{s("incompleteCurrent")}</dt><dd>{count(result.coverage.currentIncompleteDocuments)}</dd></div>
            {result.coverage.comparisonIncompleteDocuments != null &&
              <div><dt>{s("incompleteComparison")}</dt><dd>{count(result.coverage.comparisonIncompleteDocuments)}</dd></div>}
          </dl>
          <p>{s("salesDateScope")}</p>
        </div>}
        {chartNotice && <p className="statistics-coverage-warning" role="status">{chartNotice}</p>}
        {!incomplete && chart.data?.report && chart.data.report.rows.length > 0 && <ArticleChart report={chart.data.report}
          selected={chartCurrency} onSelect={setChartCurrency} />}
        {rows.length > 0 && <p className="statistics-note">{l("metricScope")}</p>}
        <ReportTable title={l("ranking")} columns={columns} rows={rows} rowKey={rowKey} visible={preferences.isVisible}
          empty={report.error ? s("noReport") : report.loading ? missing : l(incomplete ? "dataGaps" : "noArticles")} sort={sort}
          onSort={(key) => setSort(nextSort(sort, key, key === "CODE" ? "ASC" : "DESC"))} />
      </div>
    </section>
  </div>;
}

function restoreSort(filters: Record<string, string>): ReportSort {
  const key = articleSorts.includes(filters.sort) ? filters.sort : defaultSort.key;
  return { key, direction: filters.direction === "ASC" || (key === "CODE" && filters.direction !== "DESC") ? "ASC" : "DESC" };
}

function ArticleChart({ report, selected, onSelect }: { report: ArticlePage; selected: string; onSelect: (currency: string) => void }) {
  const l = useArticleLabels();
  const s = useStatisticsLabels();
  const { language } = useI18n();
  const locale = localeFor(language);
  const { rows, period, comparisonPeriod } = report;
  const date = new Intl.DateTimeFormat(locale, { timeZone: "UTC", dateStyle: "short" });
  const periodLabel = (range: StatisticsPeriod) => date.format(new Date(range.from + "T00:00:00Z")) + " – " + date.format(new Date(range.to + "T00:00:00Z"));
  const currencies = [...new Set(rows.map((row) => row.currency))].sort();
  const currency = currencies.includes(selected) ? selected : currencies[0];
  const points = rows.filter((row) => row.currency === currency).slice(0, 12);
  const values = (period: "current" | "comparison") => points.map((row) => ({
    label: row.productName, ...chartAmount(row[period]?.netAmount, locale),
  }));
  const series = [{ name: s("current") + " · " + periodLabel(period), values: values("current") }];
  if (comparisonPeriod) series.push({ name: s("compared") + " · " + periodLabel(comparisonPeriod), values: values("comparison") });
  return <div className="report-chart-block">
    {currencies.length > 1 && <label className="report-chart-select">{s("currencyChart")}
      <select className="control-input" value={currency} onChange={(event) => onSelect(event.target.value)}>
        {currencies.map((code) => <option key={code}>{code}</option>)}
      </select>
    </label>}
    <AnalyticalChart title={l("netAmount") + " · " + currency} kind="bar" series={series} note={l("rankingScope")} />
  </div>;
}
