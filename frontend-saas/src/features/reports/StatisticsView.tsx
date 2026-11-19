import { useEffect, useState, type FormEvent, type ReactNode, type SetStateAction } from "react";
import { useRemote } from "../../app/RefreshContext";
import { useSummaryCategoryLabel, useSummaryLabels } from "../dashboard/labels";
import { localeFor, useI18n } from "../../i18n";
import { request } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import { AnalyticalChart } from "../../shared/analytics/AnalyticalChart";
import { FilterDisclosure } from "../../shared/analytics/FilterDisclosure";
import { ReportTools, useReportPreferences } from "../../shared/analytics/ReportTools";
import { EmptyState, PaginationControls } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";
import { PeriodFilterFields } from "./PeriodFilterFields";
import { FilterActions } from "./PeriodShortcuts";
import { InventoryProvinceCoverage } from "./InventoryProvinceCoverage";
import { ReportTable, type ReportColumn } from "./ReportTable";
import { readAnalyticsFilters, useAnalyticsContext } from "./analytics-context";
import { chartGroupLimit, loadChartReport } from "./chart-report";
import {
  chartAmount, decimalChange, documentReportLink, filtersQuery, formatDecimal, initialFilters, isTimeGrouping, missing,
  provinces, reportPageSize, reportQuery, restorePeriodFilters, statisticsPath, validateFilters,
} from "./report-query";
import { useStatisticsLabels } from "./statistics-labels";
import type {
  PeriodMetrics, StatisticsBreakdown, StatisticsFilters, StatisticsGrouping, StatisticsMode,
  StatisticsOptions, StatisticsPeriod, StatisticsReport, StatisticsRow, StatisticsSyncCount,
} from "./statistics-types";
import "./statistics.css";

const documentMetrics = ["documents", "eligibleDocuments", "cancelledDocuments"] as const;
const syncMetrics = ["syncEvents", "syncErrors", "stockMovementEvents"] as const;
const salesMetrics = [...documentMetrics, "saleDocuments", "returnDocuments", "stores", "licenses"] as const;
const groupingCaptions = {
  DAY: { documents: "dayDocuments", sync: "daySync", sales: "netSalesByDay" },
  WEEK: { documents: "weekDocuments", sync: "weekSync", sales: "netSalesByWeek" },
  MONTH: { documents: "monthDocuments", sync: "monthSync", sales: "netSalesByMonth" },
  COMPANY: { documents: "companyDocuments", sync: "companySync", sales: "netSalesByCompany" },
  STORE: { documents: "storeDocuments", sync: "storeSync", sales: "netSalesByStore" },
  PROVINCE: { documents: "provinceDocuments", sync: "provinceSync", sales: "netSalesByProvince" },
} as const;
const averageMetrics = [
  { field: "averageAmount", title: "average" },
  { field: "amountPerDay", title: "daily" },
  { field: "amountPerStore", title: "averageStore" },
] as const;
type CountMetric = typeof salesMetrics[number] | typeof syncMetrics[number];
type Preferences = ReturnType<typeof useReportPreferences>;

export function StatisticsView({ credentials, permissions, mode = "sales" }: {
  credentials: Credentials;
  permissions: ReadonlySet<string>;
  mode?: StatisticsMode;
}) {
  const { t } = useI18n();
  if (!permissions.has("VIEW_REPORTS")) return <EmptyState text={t("noPermissionAction")} />;
  return <StatisticsContent key={credentials.accessToken} credentials={credentials} mode={mode} />;
}

function StatisticsContent({ credentials, mode }: { credentials: Credentials; mode: StatisticsMode }) {
  const l = useStatisticsLabels();
  const [saved] = useState(() => readAnalyticsFilters(credentials, mode));
  const [backPath] = useState(() => mode === "sales" && window.location.hash.split("?")[0] === "#/reports"
    ? "#/reports" : `#/reports/${mode}`);
  const [draft, setDraft] = useState(() => {
    const defaults = initialFilters(mode);
    const linked = restorePeriodFilters(saved, defaults) ?? defaults;
    return mode === "provinces" ? { ...linked, groupBy: "PROVINCE" as const } : linked;
  });
  const [applied, setApplied] = useState(draft);
  const [page, setPage] = useState(() => /^\d{1,9}$/.test(saved.page) ? Number(saved.page) : 0);
  const [chartCurrency, setChartCurrency] = useState(() => /^[A-Z]{3}$/.test(saved.chartCurrency) ? saved.chartCurrency : "");
  const [pageSnapshot, setPageSnapshot] = useState<StatisticsReport | null>(null);
  const [validation, setValidation] = useState<ReturnType<typeof validateFilters>>(null);
  const preferences = useReportPreferences(credentials, "statistics-" + mode);
  const query = filtersQuery(applied);
  const pagedQuery = reportQuery(query, page);
  const context = { ...applied, page: String(page), chartCurrency };
  useAnalyticsContext(credentials, mode, context);
  const options = useRemote(
    () => request<StatisticsOptions>(credentials, statisticsPath + "/options"),
    [credentials.accessToken],
  );
  const report = useRemote(
    () => request<StatisticsReport>(credentials, statisticsPath + "?" + pagedQuery),
    [credentials.accessToken, pagedQuery],
  );
  const chart = useRemote(() => loadChartReport<StatisticsRow, StatisticsReport>(
    (chartPage, size) => request<StatisticsReport>(credentials, statisticsPath + "?" + reportQuery(query, chartPage, size)),
    (data) => data.totalGroups,
    (row) => row.key,
  ), [credentials.accessToken, query.toString()]);
  const visible = report.data ?? (report.loading ? pageSnapshot : null);
  const back = `${backPath}?${new URLSearchParams({ ...context, page: String(visible?.page ?? page) })}`;
  const comparing = visible ? visible.comparisonPeriod !== null : applied.comparison !== "NONE";
  const sync = mode === "sync";
  const averages = mode === "averages";
  const dirty = filtersQuery(draft).toString() !== query.toString();
  const chartNotice = chart.data?.limitReached ? l("chartGroupLimit").replace("{count}", String(chartGroupLimit))
    : chart.error || (chart.data && !chart.data.report) ? l("chartUnavailable") : chart.loading ? l("loadingChart") : "";

  useEffect(() => {
    if (report.error !== null || report.data) setPageSnapshot(null);
    if (!report.data) return;
    const last = Math.max(0, Math.ceil(report.data.totalGroups / report.data.size) - 1);
    if (page > last) setPage(last);
  }, [report.data, report.error, page]);

  function apply(next: StatisticsFilters) {
    const invalid = validateFilters(next);
    setValidation(invalid);
    if (invalid) return;
    if (query.toString() === filtersQuery(next).toString()) {
      if (page === 0) report.reload();
      chart.reload();
    }
    setPageSnapshot(null);
    setApplied(next);
    setPage(0);
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    apply(draft);
  }

  function reset() {
    const next = initialFilters(mode);
    setDraft(next);
    setChartCurrency("");
    apply(next);
  }

  function goToPage(nextPage: SetStateAction<number>) {
    if (report.loading) return;
    if (report.data) setPageSnapshot(report.data);
    setPage((typeof nextPage === "function" ? nextPage(page + 1) : nextPage) - 1);
  }

  const amountColumns = [
    { id: "current", label: l("current") },
    { id: "compared", label: l("compared") },
    { id: "difference", label: l("difference") },
    { id: "percent", label: l("percent") },
  ];
  const optionalColumns = sync ? syncMetrics.map((key) => ({ id: key, label: l(key) }))
    : averages ? amountColumns
    : [...amountColumns, ...documentMetrics.map((key) => ({ id: key, label: l(key) }))];

  return (
    <div className="saas-summary-view saas-statistics view-grid">
      <section className="saas-summary-panel" aria-labelledby="statistics-heading">
        <header className="saas-summary-heading"><h2 id="statistics-heading">{l(mode === "sales" ? "title" : mode)}</h2></header>
        <div className="saas-summary-panel-body">
          <FilterDisclosure filters={applied} options={options.data} dirty={dirty}>
            <form id="statistics-filters" aria-label={l("filters")} onSubmit={submit}>
              <div className="statistics-filter-grid">
                <PeriodFilterFields draft={draft} options={options.data} grouping={mode !== "provinces"}
                  onChange={(changes) => { setDraft({ ...draft, ...changes }); setValidation(null); }} />
              </div>
              {validation && <p className="notice error" role="alert">{l(validation)}</p>}
            </form>
          </FilterDisclosure>
          <ReportTools credentials={credentials} preferences={preferences} filters={{
            ...applied,
            companyName: options.data?.companies.find((item) => item.id === applied.companyId)?.name ?? "",
            storeName: options.data?.stores.find((item) => item.id === applied.storeId)?.name ?? "",
          }} columns={optionalColumns} exportName={l(mode)}
            exportPath={visible ? `/api/v1/admin/reports/export/statistics?${query}&view=${mode}` : undefined}
            leadingAction={<>
              <button className="primary-button" type="submit" form="statistics-filters">{l("apply")}</button>
              <FilterActions dirty={dirty} onReset={reset} />
            </>} />
          <LoadState {...options} />
        </div>
      </section>
      <div className="statistics-report-status"><LoadState {...report} /></div>
      <StatisticsResults report={visible} chart={chart.data?.report ?? null}
        chartNotice={chartNotice}
        unavailable={!visible && l(report.error !== null ? "noReport" : "loadingReport")}
        filters={applied} comparing={comparing} mode={mode} preferences={preferences} busy={report.loading}
        page={page} pageInfo={report.data ?? pageSnapshot} onPage={goToPage} back={back}
        chartCurrency={chartCurrency} onChartCurrency={setChartCurrency} />
      {mode === "provinces" && <InventoryProvinceCoverage credentials={credentials} filters={applied} back={back} />}
    </div>
  );
}

function StatisticsResults({ report, chart, chartNotice, unavailable, filters, comparing, mode, preferences, busy, page, pageInfo, onPage,
  back, chartCurrency, onChartCurrency }: {
  report: StatisticsReport | null;
  chart: StatisticsReport | null;
  chartNotice: string;
  unavailable: string | false;
  filters: StatisticsFilters;
  comparing: boolean;
  mode: StatisticsMode;
  preferences: Preferences;
  busy: boolean;
  page: number;
  pageInfo: StatisticsReport | null;
  onPage: (page: SetStateAction<number>) => void;
  back: string;
  chartCurrency: string;
  onChartCurrency: (currency: string) => void;
}) {
  const l = useStatisticsLabels();
  const summaryLabel = useSummaryLabels();
  const amountsBlocked = (report?.coverage.unknownRelationships ?? 0) > 0;
  const size = pageInfo?.size ?? reportPageSize;
  const totalGroups = pageInfo?.totalGroups ?? 0;
  const rows = report?.rows ?? [];
  const sync = mode === "sync";
  const averages = mode === "averages";
  const grouping = filters.groupBy;
  const captions = groupingCaptions[grouping];
  const sales = !sync && !averages;
  const periodAmounts = report && !amountsBlocked && !sync ? amountRows(report.totals, averages) : [];
  const hasSalesStrips = sales && periodAmounts.length > 0;
  const hasPeriodAmounts = periodAmounts.some(({ current, previous }) => current != null || previous != null);
  const groupedCounts = !averages && <GroupedCounts title={l(sync ? captions.sync : captions.documents)} grouping={grouping} rows={rows}
    comparing={comparing} metrics={sync ? syncMetrics : documentMetrics} unavailable={unavailable}
    eligibleBlocked={amountsBlocked} visible={preferences.isVisible}
    documentLinks={sales && report ? (row) => <SalesDocumentLinks report={report} filters={filters} back={back} row={row} /> : undefined} />;
  const totalsTable = !averages && <TotalsTable totals={report?.totals} comparing={comparing} unavailable={unavailable}
    eligibleBlocked={amountsBlocked} metrics={sync ? syncMetrics : salesMetrics} />;
  const amountTable = !sync && <AmountTable totals={report?.totals} comparing={comparing} unavailable={unavailable}
    blocked={amountsBlocked} averages={averages} visible={preferences.isVisible} />;
  const drillThrough = report ? { report, filters, back } : undefined;

  return (
    <div className="statistics-results" aria-busy={busy}>
      {!sync && amountsBlocked && <p className="statistics-coverage-warning" role="status">{l("amountsUnavailable")}</p>}
      {report && hasSalesStrips
        ? <SalesAmountStrips entries={periodAmounts} period={report.period} comparison={report.comparisonPeriod} />
        : <section className="saas-summary-panel" aria-labelledby="statistics-totals-heading">
          <header className="saas-summary-heading"><h2 id="statistics-totals-heading">{l("summary")}</h2></header>
          <div className="saas-summary-panel-body">
            {sync ? totalsTable : amountTable}
          </div>
        </section>}
      {chartNotice && <p className="statistics-coverage-warning" role="status">{chartNotice}</p>}
      {chart && <StatisticsChart report={chart} mode={mode} blocked={amountsBlocked || chart.coverage.unknownRelationships > 0}
        selection={chartCurrency} onSelection={onChartCurrency} />}
      <section className="saas-summary-panel" aria-labelledby="statistics-group-heading">
        <header className="saas-summary-heading">
          <h2 id="statistics-group-heading">{l(sync ? captions.sync : averages ? "breakdown" : captions.sales)}</h2>
          <PaginationControls
            page={(report?.page ?? page) + 1} pages={Math.max(1, Math.ceil(totalGroups / size))} total={totalGroups}
            pageSize={size} rows={rows} setPage={onPage}
          />
        </header>
        <div className="saas-summary-panel-body">
          {sync ? groupedCounts : <AmountTable grouping={grouping} rows={rows} comparing={comparing}
            periodTotals={sales ? periodAmounts : undefined} hasPeriodAmounts={hasPeriodAmounts}
            unavailable={unavailable} blocked={amountsBlocked} averages={averages} visible={preferences.isVisible}
            drillThrough={drillThrough} provinceStores={mode === "provinces"} />}
          {!sync && report && !amountsBlocked && <p className="statistics-note">{l("amountLegend")}
            {comparing && <> {l("percentNeedsBase")}</>}
          </p>}
        </div>
      </section>
      {sync && <section className="saas-summary-panel" aria-labelledby="statistics-sync-heading">
        <header className="saas-summary-heading"><h2 id="statistics-sync-heading">{l("synchronization")}</h2></header>
        <div className="saas-summary-panel-body statistics-sync-tables">
          <SyncCountsTable title={l("syncByType")} category="type" rows={report?.syncByType ?? []} comparing={comparing} unavailable={unavailable} />
          <SyncCountsTable title={l("syncByStatus")} category="status" rows={report?.syncByStatus ?? []} comparing={comparing} unavailable={unavailable} />
        </div>
        <div className="saas-summary-panel-body"><p className="statistics-note">{summaryLabel("syncFailureHelp")}</p></div>
      </section>}
      {report && <div className="statistics-metadata">
        <ReportTimestamp asOf={report.asOf} />
        {!hasSalesStrips && <ReportPeriods report={report} />}
        {!sync && <CoverageBrief coverage={report.coverage} />}
        {!sync && <SalesDocumentLinks report={report} filters={filters} back={back} />}
      </div>}
      {(report || sales) && <details className="saas-summary-panel statistics-details">
        <summary>{l("reportDetails")}</summary>
        <div className="saas-summary-panel-body">
          {report && <ReportCoverage report={report} mode={mode} />}
          {sales && <>
            <div className="statistics-overview">
              {totalsTable}
              {hasSalesStrips && amountTable}
            </div>
            {groupedCounts}
          </>}
          {report && !sync && <p className="statistics-note">{l("amountScope")}</p>}
        </div>
      </details>}
    </div>
  );
}

function bucketRange(period: StatisticsPeriod, grouping: StatisticsGrouping, date: string | null) {
  if (!isTimeGrouping(grouping)) return period;
  if (!date) return null;
  const end = new Date(date + "T00:00:00Z");
  if (grouping === "MONTH") end.setUTCMonth(end.getUTCMonth() + 1, 0);
  if (grouping === "WEEK") end.setUTCDate(end.getUTCDate() + (7 - (end.getUTCDay() + 6) % 7) - 1);
  const bucketEnd = end.toISOString().slice(0, 10);
  return { from: date, to: bucketEnd < period.to ? bucketEnd : period.to };
}

type SalesDocumentScope = { report: StatisticsReport; filters: StatisticsFilters; back: string };

function SalesDocumentLinks({ report, filters, back, row, currency, compact }: SalesDocumentScope & {
  row?: StatisticsRow;
  currency?: string;
  compact?: boolean;
}) {
  const l = useStatisticsLabels();
  const { date } = useStatisticsFormats();
  const scope = { companyId: filters.companyId, storeId: filters.storeId, province: filters.province, currency };
  if (row && report.groupBy === "STORE") {
    if (row.key === "UNKNOWN") return <span title={l("unknownStoreDocuments")}>{missing}</span>;
    scope.storeId = row.key;
  }
  if (row && report.groupBy === "COMPANY") scope.companyId = row.key;
  if (row && report.groupBy === "PROVINCE") scope.province = row.key;
  const current = row ? bucketRange(report.period, report.groupBy, row.label) : report.period;
  const previous = report.comparisonPeriod && (row ? bucketRange(report.comparisonPeriod, report.groupBy, row.comparisonLabel) : report.comparisonPeriod);
  if (!current && !previous) return missing;

  function name(period: "current" | "compared", from: string) {
    if (!compact || !row) return `${l("viewDocuments")} · ${l(period)}`;
    const label = isTimeGrouping(report.groupBy) ? date.format(new Date(from + "T00:00:00Z"))
      : report.groupBy === "PROVINCE" ? provinces.get(row.key) ?? l("unknownProvince")
      : row.label ?? row.comparisonLabel ?? row.key;
    return [l("viewDocuments"), l(period), label, currency].filter(Boolean).join(" · ");
  }

  return <div className="report-row-links">
    {!row && <span>{l("viewDocuments")}</span>}
    {current && <a className="small-button report-link" href={documentReportLink({ ...scope, ...current }, back)}
      aria-label={name("current", current.from)}>{l(compact ? "currentShort" : "current")}</a>}
    {previous && <a className="small-button report-link" href={documentReportLink({ ...scope, ...previous }, back)}
      aria-label={name("compared", previous.from)}>{l(compact ? "comparedShort" : "compared")}</a>}
  </div>;
}

function ProvinceStoreLink({ province, filters, back }: { province: string; filters: StatisticsFilters; back: string }) {
  const l = useStatisticsLabels();
  const query = new URLSearchParams({ province, overview: "1", return: back });
  if (filters.companyId) query.set("companyId", filters.companyId);
  if (filters.storeId) query.set("storeId", filters.storeId);
  return <a className="small-button report-link" href={"#/reports/inventory?" + query}
    aria-label={`${l("viewStores")} · ${provinces.get(province) ?? l("unknownProvince")}`}>{l("viewStores")}</a>;
}

function useStatisticsFormats() {
  const { language } = useI18n();
  const locale = localeFor(language);
  return {
    locale,
    count: new Intl.NumberFormat(locale).format,
    change: new Intl.NumberFormat(locale, { signDisplay: "exceptZero" }).format,
    date: new Intl.DateTimeFormat(locale, { timeZone: "UTC", dateStyle: "short" }),
  };
}

function BusinessDate({ date }: { date: string | null }) {
  const l = useStatisticsLabels();
  const { date: format } = useStatisticsFormats();
  return date ? <time dateTime={date}>{format.format(new Date(date + "T00:00:00Z"))}</time>
    : <span title={l("dateOutsidePeriod")}>{missing}</span>;
}

function ReportTimestamp({ asOf }: { asOf: string }) {
  const l = useStatisticsLabels();
  const { locale } = useStatisticsFormats();
  const timestamp = new Intl.DateTimeFormat(locale, {
    year: "numeric", month: "short", day: "numeric",
    hour: "2-digit", minute: "2-digit", timeZone: "UTC", timeZoneName: "short",
  });
  return <p className="saas-summary-timestamp">{l("asOf")} <time dateTime={asOf}>{timestamp.format(new Date(asOf))}</time></p>;
}

function PeriodRange({ period }: { period: StatisticsPeriod }) {
  return <><BusinessDate date={period.from} /> – <BusinessDate date={period.to} /></>;
}

function ReportPeriods({ report }: { report: StatisticsReport }) {
  const l = useStatisticsLabels();
  return <dl className="statistics-periods statistics-metadata-periods" aria-label={l("periods")}>
    <div><dt>{l("current")}</dt><dd><PeriodRange period={report.period} /></dd></div>
    {report.comparisonPeriod && <div><dt>{l("compared")}</dt><dd><PeriodRange period={report.comparisonPeriod} /></dd></div>}
  </dl>;
}

function CoverageBrief({ coverage }: { coverage: StatisticsReport["coverage"] }) {
  const l = useStatisticsLabels();
  const { count } = useStatisticsFormats();
  return <div className="statistics-coverage-brief">
    <dl aria-label={l("coverage")}>
      <div><dt>{l("shortIncompleteCurrent")}</dt><dd>{count(coverage.currentIncompleteDocuments)}</dd></div>
      {coverage.comparisonIncompleteDocuments !== null && <div>
        <dt>{l("shortIncompleteComparison")}</dt><dd>{count(coverage.comparisonIncompleteDocuments)}</dd>
      </div>}
      {coverage.unknownRelationships > 0 && <div>
        <dt>{l("shortUnknownRelationships")}</dt><dd>{count(coverage.unknownRelationships)}</dd>
      </div>}
    </dl>
  </div>;
}

function ReportCoverage({ report, mode }: { report: StatisticsReport; mode: StatisticsMode }) {
  const l = useStatisticsLabels();
  const { count } = useStatisticsFormats();
  const { coverage, groupBy } = report;

  return (
    <div className="statistics-context">
      {mode !== "sync" && <div className="saas-summary-coverage">
        <h3>{l("coverage")}</h3>
        <dl className="statistics-coverage-counts">
          <div><dt>{l("unknownRelationships")}</dt><dd>{count(coverage.unknownRelationships)}</dd></div>
          <div><dt>{l("incompleteCurrent")}</dt><dd>{count(coverage.currentIncompleteDocuments)}</dd></div>
          {coverage.comparisonIncompleteDocuments !== null && (
            <div><dt>{l("incompleteComparison")}</dt><dd>{count(coverage.comparisonIncompleteDocuments)}</dd></div>
          )}
        </dl>
        <p>{l("incompleteScope")}</p>
      </div>}
      <p className="statistics-note">{l(mode === "sync" ? "syncDateScope" : "salesDateScope")}
        {mode === "sync" && <> {l("stockScope")}</>}{groupBy === "PROVINCE" && <> {l("provinceScope")}</>}</p>
    </div>
  );
}

function TotalsTable({ totals, comparing, unavailable, eligibleBlocked, metrics }: {
  totals?: StatisticsBreakdown;
  comparing: boolean;
  unavailable: string | false;
  eligibleBlocked: boolean;
  metrics: readonly CountMetric[];
}) {
  const l = useStatisticsLabels();
  const { count, change } = useStatisticsFormats();
  return (
    <Table title={l("summaryCounts")}>
      <thead><tr>
        <th scope="col">{l("metric")}</th>
        <th scope="col" className="saas-summary-number">{l("current")}</th>
        {comparing && <th scope="col" className="saas-summary-number">{l("compared")}</th>}
        {comparing && <th scope="col" className="saas-summary-number">{l("difference")}</th>}
      </tr></thead>
      <tbody>
        {totals && metrics.filter((metric) => totals.current[metric] !== undefined).map((metric) => {
          const blocked = eligibleBlocked && (metric === "eligibleDocuments" || metric === "saleDocuments" || metric === "returnDocuments");
          const current = totals.current[metric];
          const compared = totals.comparison?.[metric];
          return (
            <tr key={metric}>
              <th scope="row">{l(metric)}</th>
              <td className="saas-summary-number">{blocked || current == null ? missing : count(current)}</td>
              {comparing && <td className="saas-summary-number">{blocked || compared == null ? missing : count(compared)}</td>}
              {comparing && <td className="saas-summary-number">{blocked || current == null || compared == null ? missing : change(current - compared)}</td>}
            </tr>
          );
        })}
        {!totals && <EmptyRow columns={comparing ? 4 : 2}>{unavailable || l("noReport")}</EmptyRow>}
      </tbody>
    </Table>
  );
}

function GroupLabel({ row, grouping, compared = false }: { row: StatisticsRow; grouping: StatisticsGrouping; compared?: boolean }) {
  const l = useStatisticsLabels();
  if (isTimeGrouping(grouping)) return <BusinessDate date={compared ? row.comparisonLabel : row.label} />;
  if (grouping === "PROVINCE") return provinces.get(row.key) ?? l("unknownProvince");
  if (grouping === "STORE" && row.key === "UNKNOWN") return l("unknownStore");
  return row.label ?? row.comparisonLabel ?? row.key;
}

function GroupedCounts({ title, grouping, rows, comparing, metrics, unavailable, eligibleBlocked, visible, documentLinks }: {
  title: string;
  grouping: StatisticsGrouping;
  rows: StatisticsRow[];
  comparing: boolean;
  metrics: readonly CountMetric[];
  unavailable: string | false;
  eligibleBlocked?: boolean;
  visible: (key: string) => boolean;
  documentLinks?: (row: StatisticsRow) => ReactNode;
}) {
  const l = useStatisticsLabels();
  const { count } = useStatisticsFormats();
  const timedComparison = comparing && isTimeGrouping(grouping);
  const metricValue = (data: PeriodMetrics | null, metric: CountMetric) => {
    const value = data?.[metric];
    return value == null || (eligibleBlocked && metric === "eligibleDocuments") ? missing : count(value);
  };
  const displayed = metrics.filter(visible);
  const columns = 1 + Number(timedComparison) + displayed.length * (comparing ? 2 : 1) + Number(Boolean(documentLinks));
  return (
    <Table title={title}>
      <colgroup span={timedComparison ? 2 : 1} />
      {displayed.length > 0 && <colgroup span={displayed.length} />}
      {comparing && displayed.length > 0 && <colgroup span={displayed.length} />}
      {documentLinks && <colgroup />}
      <thead>
        <tr>
          <th scope="col" rowSpan={comparing && displayed.length ? 2 : 1}>{l(grouping)}</th>
          {timedComparison && <th scope="col" rowSpan={displayed.length ? 2 : 1}>{l(grouping === "DAY" ? "comparisonDay" : "comparisonGroup")}</th>}
          {comparing && displayed.length > 0 ? <>
            <th scope="colgroup" colSpan={displayed.length}>{l("current")}</th>
            <th scope="colgroup" colSpan={displayed.length}>{l("compared")}</th>
          </> : displayed.map((metric) => <th scope="col" key={metric} className="saas-summary-number">{l(metric)}</th>)}
          {documentLinks && <th scope="col" rowSpan={comparing && displayed.length ? 2 : 1}>{l("viewDocuments")}</th>}
        </tr>
        {comparing && displayed.length > 0 && <tr>
          {displayed.map((metric) => <th scope="col" key={metric} className="saas-summary-number">{l(metric)}</th>)}
          {displayed.map((metric) => <th scope="col" key={metric + "-comparison"} className="saas-summary-number">{l(metric)}</th>)}
        </tr>}
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.key}>
            <th scope="row"><GroupLabel row={row} grouping={grouping} /></th>
            {timedComparison && <td><GroupLabel row={row} grouping={grouping} compared /></td>}
            {displayed.map((metric) => <td key={metric} className="saas-summary-number">{metricValue(row.current, metric)}</td>)}
            {comparing && displayed.map((metric) => (
              <td key={metric + "-comparison"} className="saas-summary-number">{metricValue(row.comparison, metric)}</td>
            ))}
            {documentLinks && <td>{documentLinks(row)}</td>}
          </tr>
        ))}
        {!rows.length && <EmptyRow columns={columns}>{unavailable || l("noGroups")}</EmptyRow>}
      </tbody>
    </Table>
  );
}

type AmountRow = {
  key: string;
  currency: string;
  metric: "amount" | typeof averageMetrics[number]["title"];
  current: string | null | undefined;
  previous: string | null | undefined;
  difference: string | null;
  percent: string | null;
  group?: StatisticsRow;
};

function amountRows(breakdown: StatisticsBreakdown | StatisticsRow, averages: boolean, group?: StatisticsRow): AmountRow[] {
  const current = new Map(breakdown.current?.currencies.map((entry) => [entry.currency, entry]));
  const comparison = new Map(breakdown.comparison?.currencies.map((entry) => [entry.currency, entry]));
  const currencies = [...new Set([...current.keys(), ...comparison.keys()])].sort();
  const metrics = averages ? averageMetrics : [{ field: "amount", title: "amount" } as const];
  return currencies.flatMap((currency) => metrics.map(({ field, title }) => {
    const currentValue = current.get(currency)?.[field];
    const previousValue = comparison.get(currency)?.[field];
    const change = field === "amount" ? breakdown.deltas?.find((entry) => entry.currency === currency) : undefined;
    const delta = change ?? decimalChange(currentValue, previousValue);
    return {
      key: [group?.key, currency, field].join(":"), group, currency, metric: title,
      current: currentValue, previous: previousValue, difference: delta.amount, percent: delta.percent,
    };
  }));
}

function SalesAmountStrips({ entries, period, comparison }: {
  entries: AmountRow[];
  period: StatisticsPeriod;
  comparison: StatisticsPeriod | null;
}) {
  const l = useStatisticsLabels();
  const { locale } = useStatisticsFormats();
  const money = (value: string | null | undefined, currency: string, signed = false) => {
    const amount = formatDecimal(value, locale, 2, signed);
    return amount === missing ? <span title={l(signed ? "differenceUnavailable" : "noCurrencyAmounts")}>{missing}</span> : currency + " " + amount;
  };

  return <div className="statistics-sales-strips">
    {entries.map((entry) => <section key={entry.currency} className="saas-summary-panel statistics-sales-strip"
      aria-labelledby={"statistics-currency-" + entry.currency}>
      <header className="saas-summary-heading">
        <h2 id={"statistics-currency-" + entry.currency}>{l("netSales")} · {entry.currency}</h2>
      </header>
      <dl className="statistics-periods statistics-sale-values">
        <div><dt>{l("current")}</dt><dd>{money(entry.current, entry.currency)}</dd>
          <dd className="statistics-sale-period"><PeriodRange period={period} /></dd></div>
        {comparison && <>
          <div><dt>{l("compared")}</dt><dd>{money(entry.previous, entry.currency)}</dd>
            <dd className="statistics-sale-period"><PeriodRange period={comparison} /></dd></div>
          <div><dt>{l("difference")}</dt><dd>
            {money(entry.difference, entry.currency, true)}
            {entry.percent !== null && <span className="statistics-sale-percent"> ({formatDecimal(entry.percent, locale, 2, true)} %)</span>}
          </dd></div>
        </>}
      </dl>
    </section>)}
  </div>;
}

function AmountTable({ totals, grouping, rows, periodTotals, hasPeriodAmounts, comparing, unavailable, blocked, averages, visible,
  drillThrough, provinceStores }: {
  totals?: StatisticsBreakdown;
  grouping?: StatisticsGrouping;
  rows?: StatisticsRow[];
  periodTotals?: AmountRow[];
  hasPeriodAmounts?: boolean;
  comparing: boolean;
  unavailable: string | false;
  blocked: boolean;
  averages: boolean;
  visible: (key: string) => boolean;
  drillThrough?: SalesDocumentScope;
  provinceStores?: boolean;
}) {
  const l = useStatisticsLabels();
  const { t } = useI18n();
  const { locale } = useStatisticsFormats();
  const temporal = grouping != null && isTimeGrouping(grouping);
  const entries = rows?.flatMap((row): AmountRow[] => {
    const amounts = blocked ? [] : amountRows(row, averages, row);
    if (amounts.length) return amounts;
    if (provinceStores || row.current?.documents || row.comparison?.documents) {
      return [{ key: row.key, currency: "", metric: "amount", current: null, previous: null, difference: null, percent: null, group: row }];
    }
    return [];
  }) ?? (totals && !blocked ? amountRows(totals, averages) : []);
  const columns: ReportColumn<AmountRow>[] = [];
  const unavailableValue = (reason: Parameters<typeof l>[0]) => <span title={l(reason)}>{missing}</span>;
  const amount = (value: string | null | undefined, reason: Parameters<typeof l>[0], signed = false) => value == null
    ? unavailableValue(blocked ? "amountsUnavailable" : reason) : formatDecimal(value, locale, 2, signed);
  if (grouping) {
    columns.push({ id: "group", label: l(grouping), required: true, rowHeader: true, sticky: true,
      cell: (row) => row.group ? <GroupLabel row={row.group} grouping={grouping} /> : l("periodTotal") });
    if (comparing && temporal) columns.push({
      id: "comparisonGroup", label: l(grouping === "DAY" ? "comparisonDay" : "comparisonGroup"), required: true,
      cell: (row) => row.group ? <GroupLabel row={row.group} grouping={grouping} compared /> : null,
    });
  }
  columns.push({ id: "currency", label: t("currency"), required: true, rowHeader: true, cell: (row) => row.currency || missing });
  if (averages) columns.push({ id: "metric", label: l("metric"), required: true, cell: (row) => row.currency ? l(row.metric) : missing });
  columns.push({
    id: "current", label: l(comparing || averages ? "current" : "amount"), numeric: true,
    cell: (row) => amount(row.current, row.group && !row.group.label && temporal ? "dateOutsidePeriod" : "noCurrencyAmounts"),
  });
  if (comparing) columns.push(
    { id: "compared", label: l("compared"), numeric: true,
      cell: (row) => amount(row.previous, row.group && !row.group.comparisonLabel && temporal ? "dateOutsidePeriod" : "noCurrencyAmounts") },
    { id: "difference", label: l("difference"), numeric: true, cell: (row) => amount(row.difference, "differenceUnavailable", true) },
    { id: "percent", label: l("percent"), numeric: true, cell: (row) => row.percent == null
      ? unavailableValue(blocked ? "amountsUnavailable" : row.previous != null && Number(row.previous) === 0 ? "percentNeedsBase" : "differenceUnavailable")
      : formatDecimal(row.percent, locale, 2, true) + " %" },
  );
  if (grouping) columns.push({ id: "documentLinks", label: l("viewDocuments"), required: true, sticky: !provinceStores,
    cell: (row) => drillThrough && row.group
      ? <SalesDocumentLinks {...drillThrough} row={row.group} currency={row.currency} compact /> : null });
  if (provinceStores) columns.push({ id: "storeLinks", label: l("storesShort"), required: true, sticky: true,
    cell: (row) => drillThrough && row.group ? <ProvinceStoreLink {...drillThrough} province={row.group.key} /> : null });

  return <ReportTable title={l(averages ? (grouping ? "groupedAverages" : "currencyAverages") : grouping ? "groupedAmounts" : "currencyAmounts")}
    columns={columns} rows={entries} footerRows={periodTotals} rowKey={(row) => row.key} visible={visible}
    empty={unavailable || l(blocked ? "amountsUnavailable" : hasPeriodAmounts ? "noPageAmounts" : "noAmounts")} />;
}

function StatisticsChart({ report, mode, blocked, selection, onSelection }: {
  report: StatisticsReport;
  mode: StatisticsMode;
  blocked: boolean;
  selection: string;
  onSelection: (currency: string) => void;
}) {
  const l = useStatisticsLabels();
  const { locale, date, count } = useStatisticsFormats();
  const sync = mode === "sync";
  const averages = mode === "averages";
  const timeline = isTimeGrouping(report.groupBy);
  if (!report.rows.length || (blocked && !sync)) return null;
  const currencies = [...new Set([...report.totals.current.currencies, ...report.totals.comparison?.currencies ?? []].map((entry) => entry.currency))].sort();
  const currency = currencies.includes(selection) ? selection : currencies[0];
  if (!currency && !sync) return null;
  const chartLabel = (row: StatisticsRow, compared = false) => {
    if (timeline) {
      const value = compared ? row.comparisonLabel : row.label ?? row.comparisonLabel;
      return value ? date.format(new Date(value + "T00:00:00Z")) : missing;
    }
    if (report.groupBy === "PROVINCE") return provinces.get(row.key) ?? l("unknownProvince");
    if (row.key === "UNKNOWN") return l("unknownStore");
    return row.label ?? row.comparisonLabel ?? row.key;
  };
  const seriesPoint = (row: StatisticsRow, compared = false) => {
    const label = chartLabel(row, compared);
    const data = compared ? row.comparison : row.current;
    if (sync) {
      const events = data?.syncEvents ?? null;
      return { label, value: events, valueLabel: events == null ? missing : count(events) };
    }
    const value = data?.currencies.find((entry) => entry.currency === currency);
    return { label, ...chartAmount(averages ? value?.averageAmount : value?.amount, locale) };
  };
  const periodLabel = (period: StatisticsPeriod) => date.format(new Date(period.from + "T00:00:00Z")) +
    " – " + date.format(new Date(period.to + "T00:00:00Z"));
  const series = [{ name: l("current") + " · " + periodLabel(report.period),
    values: report.rows.map((row) => seriesPoint(row)) }];
  if (report.comparisonPeriod) series.push({
    name: l("compared") + " · " + periodLabel(report.comparisonPeriod),
    values: report.rows.map((row) => seriesPoint(row, true)),
  });
  const hasValues = series.some((item) => item.values.some((point) => point.value !== null));

  return <div className="report-chart-block statistics-chart">
    {!sync && currencies.length > 1 && <label className="report-chart-select">{l("currencyChart")}
      <select className="control-input" value={currency} onChange={(event) => onSelection(event.target.value)}>
        {currencies.map((code) => <option key={code}>{code}</option>)}
      </select>
    </label>}
    <AnalyticalChart title={sync ? l("syncEvents") : l(averages ? "average" : groupingCaptions[report.groupBy].sales) + " · " + currency}
      kind="bar" series={series} note={l(hasValues ? "chartPeriodScope" : "noChartValues")} />
  </div>;
}

function SyncCountsTable({ title, category, rows, comparing, unavailable }: {
  title: string;
  category: "type" | "status";
  rows: StatisticsSyncCount[];
  comparing: boolean;
  unavailable: string | false;
}) {
  const l = useStatisticsLabels();
  const { t } = useI18n();
  const label = useSummaryCategoryLabel();
  const { count, change } = useStatisticsFormats();
  const columns: ReportColumn<StatisticsSyncCount>[] = [
    { id: category, label: t(category), required: true, rowHeader: true, cell: (row) => label(row.key) },
    { id: "current", label: l("current"), numeric: true, cell: (row) => count(row.current) },
  ];
  if (comparing) columns.push(
    { id: "compared", label: l("compared"), numeric: true, cell: (row) => row.comparison === null ? missing : count(row.comparison) },
    { id: "difference", label: l("difference"), numeric: true, cell: (row) => row.comparison === null ? missing : change(row.current - row.comparison) },
  );
  return <ReportTable title={title} columns={columns} rows={rows} rowKey={(row) => row.key} empty={unavailable || l("noEvents")} />;
}

function Table({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="table-wrap saas-summary-table statistics-table" tabIndex={0} role="region" aria-label={title}>
      <table><caption>{title}</caption>{children}</table>
    </div>
  );
}

function EmptyRow({ columns, children }: { columns: number; children: ReactNode }) {
  return <tr><td colSpan={columns} className="saas-summary-empty">{children}</td></tr>;
}
