import { useState } from "react";
import { useRemote } from "../../app/RefreshContext";
import { localeFor, useI18n } from "../../i18n";
import { request } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import { formatUtcDateTime } from "../../lib/utc-date-time.mjs";
import { AnalyticalChart } from "../../shared/analytics/AnalyticalChart";
import { ReportTools, useReportPreferences } from "../../shared/analytics/ReportTools";
import { Input, Metric, PaginationControls, StatusPill } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";
import { FilterActions } from "./PeriodShortcuts";
import { nextSort, ReportTable, type ReportColumn, type ReportSort } from "./ReportTable";
import { readAnalyticsFilters, useAnalyticsContext } from "./analytics-context";
import { useInventoryLabels } from "./inventory-labels";
import type {
  InventoryComparison, InventoryComparisonRow, InventoryScope, InventorySnapshot, InventoryWarehouse, SnapshotPage,
} from "./inventory-types";
import { formatDecimal, missing, reportPageSize, reportQuery } from "./report-query";
import { useStatisticsLabels } from "./statistics-labels";
import { useReportPage } from "./useReportPage";

const path = "/api/v1/admin/reports/inventory";
const inventoryExport = "/api/v1/admin/reports/export/comparison";
const defaultFilters = { search: "", warehouseId: "", warehouseName: "", changedOnly: "false" };
const defaultSort: ReportSort = { key: "PRODUCT", direction: "ASC" };
const comparisonSorts = ["PRODUCT", "WAREHOUSE", "STATUS", "DELTA"];
const counts = [["added", "ADDED"], ["removed", "REMOVED"], ["changed", "CHANGED"], ["unchanged", "UNCHANGED"]] as const;
const catalogue = [["added", "ADDED"], ["removed", "REMOVED"], ["modified", "CHANGED"], ["activated", "activated"], ["deactivated", "deactivated"]] as const;

export function comparisonProblem(current: InventorySnapshot, previous: InventorySnapshot) {
  if (current.snapshotId === previous.snapshotId || Date.parse(current.capturedAt) <= Date.parse(previous.capturedAt)) return "invalidPair";
  if (current.installationId && previous.installationId && current.installationId !== previous.installationId) return "differentInstallations";
  if (current.currentInstallation !== previous.currentInstallation) return "differentInstallations";
  return null;
}

export function InventoryComparisonView({ credentials, scope, current, previous, unavailableCaptures, search, warehouse }: {
  credentials: Credentials;
  scope: InventoryScope;
  current: InventorySnapshot | null;
  previous: InventorySnapshot | null;
  unavailableCaptures: ("currentCapture" | "previousCapture")[];
  search: string;
  warehouse: { id: string; name: string } | null;
}) {
  const l = useInventoryLabels();
  const s = useStatisticsLabels();
  const { language } = useI18n();
  const locale = localeFor(language);
  const contextKey = `inventory-comparison:${scope.companyId}:${scope.storeId}`;
  const [saved] = useState(() => readAnalyticsFilters(credentials, contextKey, { shareScope: false }));
  const samePair = current && previous && saved.snapshotId === current.snapshotId && saved.comparisonSnapshotId === previous.snapshotId;
  const [draft, setDraft] = useState(() => {
    return {
      search: saved.search ?? search,
      warehouseId: (samePair ? saved.warehouseId : warehouse?.id) ?? "",
      warehouseName: (samePair ? saved.warehouseName : warehouse?.name) ?? "",
      changedOnly: saved.changedOnly === "true" ? "true" : "false",
    };
  });
  const [applied, setApplied] = useState(draft);
  const [sort, setSort] = useState<ReportSort>(() => comparisonSorts.includes(saved.sort)
    ? { key: saved.sort, direction: saved.direction === "DESC" ? "DESC" : "ASC" } : defaultSort);
  const [warehousePage, setWarehousePage] = useState(() => samePair && /^\d{1,7}$/.test(saved.warehousePage) ? Number(saved.warehousePage) : 0);
  const preferences = useReportPreferences(credentials, "inventory-comparison", ["percent"]);
  const problem = unavailableCaptures.length ? "unavailable" : current && previous ? comparisonProblem(current, previous) : "chooseEarlier";
  const ready = problem === null;
  useAnalyticsContext(credentials, contextKey, {
    ...applied, sort: sort.key, direction: sort.direction, warehousePage: String(warehousePage),
    snapshotId: current?.snapshotId ?? "", comparisonSnapshotId: previous?.snapshotId ?? "",
  }, { shareScope: false, syncUrl: false, enabled: ready });
  const warehouseScope = new URLSearchParams(scope);
  const currentQuery = reportQuery(new URLSearchParams({ ...scope, snapshotId: current?.snapshotId ?? "" }), warehousePage);
  const previousQuery = reportQuery(new URLSearchParams({ ...scope, snapshotId: previous?.snapshotId ?? "" }), warehousePage);
  const currentWarehouses = useRemote(
    () => ready ? request<SnapshotPage<InventoryWarehouse>>(credentials, path + "/warehouses?" + currentQuery) : Promise.resolve(null),
    [credentials.accessToken, ready, currentQuery],
  );
  const previousWarehouses = useRemote(
    () => ready ? request<SnapshotPage<InventoryWarehouse>>(credentials, path + "/warehouses?" + previousQuery) : Promise.resolve(null),
    [credentials.accessToken, ready, previousQuery],
  );
  const warehouseChoices = new Map<string, InventoryWarehouse>();
  for (const data of [previousWarehouses.data, currentWarehouses.data]) {
    for (const warehouse of data?.rows ?? []) warehouseChoices.set(warehouse.warehouseId, warehouse);
  }
  const query = new URLSearchParams(warehouseScope);
  if (current) query.set("currentSnapshotId", current.snapshotId);
  if (previous) query.set("previousSnapshotId", previous.snapshotId);
  if (applied.search) query.set("search", applied.search);
  if (applied.warehouseId) query.set("warehouseId", applied.warehouseId);
  query.set("changedOnly", applied.changedOnly);
  query.set("sort", sort.key);
  query.set("direction", sort.direction);
  const report = useReportPage<InventoryComparison>(credentials, path + "/comparison", query, (data) => data.total, ready);
  const result = report.result;
  const rows = result?.rows ?? [];
  const count = new Intl.NumberFormat(locale).format;
  const warehousePages = Math.max(currentWarehouses.data?.total ?? 0, previousWarehouses.data?.total ?? 0);
  const time = (value: string) => <time dateTime={value}>{formatUtcDateTime(value, locale, true)}</time>;

  function apply(next: typeof draft) {
    const checked = { ...next, search: next.search.trim() };
    if (report.page === 0 && JSON.stringify(checked) === JSON.stringify(applied)) report.reload();
    setDraft(checked);
    setApplied(checked);
    report.setPage(0);
  }

  function reset() {
    setSort(defaultSort);
    setWarehousePage(0);
    apply(defaultFilters);
  }

  const columns: ReportColumn<InventoryComparisonRow>[] = [
    { id: "code", label: l("code"), cell: (row) => row.code || missing },
    { id: "product", label: l("product"), sort: "PRODUCT", required: true, sticky: true, rowHeader: true, cell: (row) => row.name },
    { id: "warehouse", label: l("warehouse"), sort: "WAREHOUSE", required: true, cell: (row) => row.warehouseName },
    { id: "status", label: l("comparisonStatus"), sort: "STATUS", cell: (row) => <StatusPill status={l(row.status)}
      tone={row.status === "REMOVED" ? "warning" : row.status === "UNCHANGED" ? "muted" : "ok"} /> },
    { id: "previous", label: l("previousQuantity"), numeric: true, cell: (row) => formatDecimal(row.previousQuantity, locale, 3) },
    { id: "current", label: l("currentQuantity"), numeric: true, cell: (row) => formatDecimal(row.currentQuantity, locale, 3) },
    { id: "delta", label: s("difference"), sort: "DELTA", numeric: true, cell: (row) => formatDecimal(row.delta, locale, 3, true) },
    { id: "percent", label: s("percent"), numeric: true, defaultHidden: true, cell: (row) => row.percent == null ? missing : formatDecimal(row.percent, locale, 2, true) + " %" },
  ];

  return <section className="saas-summary-panel" aria-labelledby="inventory-comparison-heading" aria-busy={ready && report.loading}>
    <header className="saas-summary-heading"><h2 id="inventory-comparison-heading">{l("compare")}</h2></header>
    <div className="saas-summary-panel-body">
      <div className="saas-summary-coverage">
        <dl>
          <div><dt>{l("previousCapture")}</dt><dd>{previous ? time(previous.capturedAt) : missing}</dd></div>
          <div><dt>{l("currentCapture")}</dt><dd>{current ? time(current.capturedAt) : missing}</dd></div>
        </dl>
        {unavailableCaptures.length
          ? <p role="status">{unavailableCaptures.map((key) => l(key)).join(" / ")} · {l("unavailable")}</p>
          : problem && <p role="status">{l(problem)}</p>}
      </div>
      {ready && <>
        <form id="inventory-comparison-filters" className="inventory-filter-grid" aria-label={l("compare")}
          onSubmit={(event) => { event.preventDefault(); apply(draft); }}>
          <Input label={l("search")} type="search" value={draft.search} onChange={(search) => setDraft({ ...draft, search })} />
          <label>{l("warehouse")}
            <select className="control-input" value={draft.warehouseId} disabled={!warehouseChoices.size && !draft.warehouseId}
              onChange={(event) => setDraft({
                ...draft, warehouseId: event.target.value,
                warehouseName: warehouseChoices.get(event.target.value)?.name ?? "",
              })}>
              <option value="">{l("allWarehouses")}</option>
              {draft.warehouseId && !warehouseChoices.has(draft.warehouseId) &&
                <option value={draft.warehouseId}>{draft.warehouseName || draft.warehouseId}</option>}
              {[...warehouseChoices.values()].map((warehouse) => <option key={warehouse.warehouseId} value={warehouse.warehouseId}>{warehouse.name}</option>)}
            </select>
          </label>
          <label className="report-check-filter"><input type="checkbox" checked={draft.changedOnly === "true"}
            onChange={(event) => setDraft({ ...draft, changedOnly: String(event.target.checked) })} />{l("changedOnly")}</label>
        </form>
        <ReportTools credentials={credentials} preferences={preferences}
          columns={columns} exportPath={result ? inventoryExport + "?" + query : undefined}
          leadingAction={<>
            <button type="submit" className="primary-button" form="inventory-comparison-filters">{l("apply")}</button>
            <FilterActions dirty={JSON.stringify(draft) !== JSON.stringify(applied)} onReset={reset} />
          </>} />
        <PaginationControls page={warehousePage + 1} pages={Math.max(1, Math.ceil(warehousePages / reportPageSize))}
          total={warehousePages} pageSize={reportPageSize} rows={[...warehouseChoices.values()]}
          setPage={(next) => { if (typeof next === "number") setWarehousePage(next - 1); }} />
        <LoadState {...currentWarehouses} />
        <LoadState {...previousWarehouses} />
        <LoadState {...report} />
        {result && <>
          <div className="saas-summary-coverage">
            <h3>{l("comparisonCoverage")}</h3>
            <div className="saas-summary-metrics inventory-coverage">
              {counts.map(([key, label]) => <Metric key={key} label={l(label)} value={count(result.counts[key])} />)}
            </div>
          </div>
          <AnalyticalChart title={l("comparisonCoverage")} kind="bar"
            series={[{ name: l("balances"), values: counts.map(([key, label]) => ({ label: l(label), value: result.counts[key] })) }]} />
          <div className="saas-summary-coverage">
            <h3>{l("catalogueChanges")}</h3>
            <dl>{catalogue.map(([key, label]) => <div key={key}><dt>{l(label)}</dt><dd>{count(result.catalogue[key])}</dd></div>)}</dl>
          </div>
        </>}
        <p className="inventory-note">{l("missingMeaning")}</p>
      </>}
      <ReportTable title={l("compare")} columns={columns} rows={rows} rowKey={(row) => row.productId + ":" + row.warehouseId}
        visible={preferences.isVisible} sort={sort} onSort={ready ? (key) => setSort(nextSort(sort, key, key === "DELTA" ? "DESC" : "ASC")) : undefined}
        empty={problem ? l(problem) : report.error ? l("unavailable") : report.loading ? missing : l("noBalances")} />
      <PaginationControls page={(result?.page ?? report.page) + 1} pages={Math.max(1, Math.ceil((result?.total ?? 0) / (result?.size ?? reportPageSize)))}
        total={result?.total ?? 0} pageSize={result?.size ?? reportPageSize} rows={rows}
        setPage={(next) => { if (!report.loading && typeof next === "number") report.setPage(next - 1); }} />
    </div>
  </section>;
}
