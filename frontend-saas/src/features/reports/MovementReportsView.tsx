import { useState, type FormEvent } from "react";
import { useRemote } from "../../app/RefreshContext";
import { localeFor, useI18n } from "../../i18n";
import { request } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import { formatUtcDateTime } from "../../lib/utc-date-time.mjs";
import { AnalyticalChart } from "../../shared/analytics/AnalyticalChart";
import { ReportTools, useReportPreferences } from "../../shared/analytics/ReportTools";
import { FilterDisclosure } from "../../shared/analytics/FilterDisclosure";
import { Input, PaginationControls } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";
import { PeriodFilterFields } from "./PeriodFilterFields";
import { FilterActions } from "./PeriodShortcuts";
import { nextSort, ReportTable, type ReportColumn, type ReportSort } from "./ReportTable";
import { readAnalyticsFilters, useAnalyticsContext } from "./analytics-context";
import type { InventoryPage, InventoryProduct, InventoryWarehouse } from "./inventory-types";
import { movementTypes, useMovementLabels } from "./movement-labels";
import {
  filtersQuery, formatDecimal, initialFilters, missing, provinces, reportLinkFilters, reportPageSize, restorePeriodFilters, statisticsPath, validateFilters,
} from "./report-query";
import { useStatisticsLabels } from "./statistics-labels";
import type { StatisticsFilters, StatisticsOptions, StatisticsPeriod } from "./statistics-types";
import { useReportPage } from "./useReportPage";

const path = "/api/v1/admin/reports/movements";
const inventoryPath = "/api/v1/admin/reports/inventory";
const groups = ["TYPE", "WAREHOUSE", "STORE", "PROVINCE", "DAY"] as const;
const ledgerSorts = ["OCCURRED_AT", "RECEIVED_AT", "TYPE", "PRODUCT", "WAREHOUSE"];
type Group = typeof groups[number];
type MovementFilters = StatisticsFilters & {
  movementGroup: Group;
  warehouseId: string;
  warehouseName: string;
  productId: string;
  productName: string;
  type: string;
};
type MovementGroupRow = { key: string; label: string | null; comparisonLabel: string | null; currentCount: number | null; comparisonCount: number | null };
type GroupPage = {
  page: number; size: number; totalGroups: number; groupBy: Group; asOf: string;
  period: StatisticsPeriod; comparisonPeriod: StatisticsPeriod | null;
  currentCount: number; comparisonCount: number | null;
  coverage: { malformedReceipts: number; conflictingMovements: number; duplicateReceipts: number };
  rows: MovementGroupRow[];
};
type LedgerRow = {
  movementId: string; companyId: string; companyName: string; storeId: string; storeName: string; province: string | null;
  warehouseId: string; warehouseName: string | null; productId: string; productName: string | null;
  type: string; quantity: string; occurredAt: string; receivedAt: string;
};
const defaultSort: ReportSort = { key: "OCCURRED_AT", direction: "DESC" };

function movementFilters(saved: Record<string, string> = {}): MovementFilters {
  const defaults = initialFilters();
  const period = restorePeriodFilters(saved, defaults) ?? defaults;
  const movementGroup = groups.find((group) => group === saved.movementGroup) ?? "TYPE";
  const scoped = Boolean(period.storeId);
  return {
    ...period, movementGroup,
    warehouseId: scoped ? saved.warehouseId ?? "" : "", warehouseName: scoped ? saved.warehouseName ?? "" : "",
    productId: scoped ? saved.productId ?? "" : "", productName: scoped ? saved.productName ?? "" : "",
    type: movementTypes.some((type) => type === saved.type) ? saved.type : "",
  };
}

function MovementTime({ value }: { value: string }) {
  const { language } = useI18n();
  return <time dateTime={value}>{formatUtcDateTime(value, localeFor(language), true)}</time>;
}

export function MovementReportsView({ credentials }: { credentials: Credentials }) {
  const { language } = useI18n();
  const s = useStatisticsLabels();
  const l = useMovementLabels();
  const locale = localeFor(language);
  const [link] = useState(reportLinkFilters);
  const ledgerOnly = link.detail === "ledger";
  const contextKey = ledgerOnly ? "movements-ledger" : "movements";
  const [initial] = useState(() => {
    if (Object.keys(link).length) return link;
    const filters = readAnalyticsFilters(credentials, contextKey);
    const saved = readAnalyticsFilters(credentials, contextKey, { shareScope: false });
    if (filters.companyId === saved.companyId && filters.storeId === saved.storeId) return filters;
    return { ...filters, productId: "", productName: "", warehouseId: "", warehouseName: "" };
  });
  const [draft, setDraft] = useState(() => movementFilters(initial));
  const [applied, setApplied] = useState(draft);
  const [sort, setSort] = useState<ReportSort>(() => ledgerSorts.includes(initial.sort)
    ? { key: initial.sort, direction: initial.direction === "ASC" ? "ASC" : "DESC" } : defaultSort);
  const [productSearch, setProductSearch] = useState("");
  const [validation, setValidation] = useState<ReturnType<typeof validateFilters>>(null);
  const preferences = useReportPreferences(credentials, "movements", ["receivedAt", "province"]);
  const summaryPreferences = useReportPreferences(credentials, "movements-summary");
  const back = /^#\/reports\/(?:articles|inventory|stock|movements)(?:\?.*)?$/.test(link.return ?? "") ? link.return : "";
  const saved = { ...applied, sort: sort.key, direction: sort.direction };
  useAnalyticsContext(credentials, contextKey, { ...saved, return: back, ...(ledgerOnly ? { detail: "ledger" } : {}) },
    { shareScope: !ledgerOnly, route: "movements" });
  const options = useRemote(() => request<StatisticsOptions>(credentials, statisticsPath + "/options"), [credentials.accessToken]);
  const store = options.data?.stores.find(({ id }) => id === draft.storeId);
  const inventoryQuery = store ? new URLSearchParams({ companyId: store.companyId, storeId: store.id, size: "100" }).toString() : "";
  const warehouses = useRemote(
    () => inventoryQuery ? request<InventoryPage<InventoryWarehouse>>(credentials, inventoryPath + "/warehouses?" + inventoryQuery) : Promise.resolve(null),
    [credentials.accessToken, inventoryQuery],
  );
  const products = useRemote(
    () => inventoryQuery ? request<InventoryPage<InventoryProduct>>(credentials, inventoryPath + "/products?" + inventoryQuery
      + "&search=" + encodeURIComponent(productSearch)) : Promise.resolve(null),
    [credentials.accessToken, inventoryQuery, productSearch],
  );
  const query = filtersQuery(applied, false);
  for (const key of ["warehouseId", "productId", "type"] as const) if (applied[key]) query.set(key, applied[key]);
  const groupedQuery = new URLSearchParams(query);
  groupedQuery.set("groupBy", applied.movementGroup);
  const ledgerQuery = new URLSearchParams(query);
  for (const name of ["comparison", "comparisonFrom", "comparisonTo"]) ledgerQuery.delete(name);
  ledgerQuery.set("sort", sort.key);
  ledgerQuery.set("direction", sort.direction);
  const summary = useReportPage<GroupPage>(credentials, path, groupedQuery, (data) => data.totalGroups, !ledgerOnly);
  const ledger = useReportPage<InventoryPage<LedgerRow>>(credentials, path + "/ledger", ledgerQuery, (data) => data.total);
  const report = summary.result;
  const summaryRows = report?.rows ?? [];
  const rows = ledger.result?.rows ?? [];
  const comparing = report ? report.comparisonCount !== null : applied.comparison !== "NONE";
  const count = new Intl.NumberFormat(locale).format;
  const change = new Intl.NumberFormat(locale, { signDisplay: "exceptZero" }).format;
  const day = new Intl.DateTimeFormat(locale, { dateStyle: "short", timeZone: "UTC" });
  const groupNames = { TYPE: l("type"), WAREHOUSE: l("warehouse"), STORE: s("STORE"), PROVINCE: s("PROVINCE"), DAY: s("DAY") };

  function updateDraft(changes: Partial<MovementFilters>) {
    const newScope = "companyId" in changes || "storeId" in changes;
    const cleared = newScope ? { warehouseId: "", warehouseName: "", productId: "", productName: "" } : {};
    if (newScope) setProductSearch("");
    setDraft({ ...draft, ...cleared, ...changes });
    setValidation(null);
  }

  function apply(next: MovementFilters) {
    const invalid = validateFilters(next);
    setValidation(invalid);
    if (invalid) return;
    if (JSON.stringify(next) === JSON.stringify(applied)) {
      if (ledger.page === 0) ledger.reload();
      if (summary.page === 0) summary.reload();
    }
    setDraft(next);
    setApplied(next);
    summary.setPage(0);
    ledger.setPage(0);
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    apply(draft);
  }

  function reset() {
    setSort(defaultSort);
    setProductSearch("");
    const defaults = movementFilters();
    apply(ledgerOnly ? { ...defaults, comparison: "NONE" } : defaults);
  }

  function filterFromRow(row: LedgerRow, field: "product" | "warehouse") {
    const scoped = applied.storeId === row.storeId && applied.companyId === row.companyId ? applied : {
      ...applied, companyId: row.companyId, storeId: row.storeId, productId: "", warehouseId: "", productName: "", warehouseName: "",
    };
    const next = field === "product" ? { ...scoped, productId: row.productId, productName: row.productName ?? row.productId }
      : { ...scoped, warehouseId: row.warehouseId, warehouseName: row.warehouseName ?? row.warehouseId };
    setDraft(next);
    apply(next);
  }

  function groupLabel(row: MovementGroupRow, other = false) {
    const value = other ? row.comparisonLabel : row.label;
    if (applied.movementGroup === "DAY") return value ? day.format(new Date(value + "T00:00:00Z")) : missing;
    if (applied.movementGroup === "TYPE") return l(row.key);
    if (applied.movementGroup === "PROVINCE") return provinces.get(row.key) ?? s("unknownProvince");
    return value ?? row.key;
  }

  function groupedLedger(row: MovementGroupRow, other: boolean) {
    const period = other ? report?.comparisonPeriod : report?.period;
    const rowCount = other ? row.comparisonCount : row.currentCount;
    if (!period || !rowCount || applied.movementGroup === "WAREHOUSE") return null;
    const filter = { ...saved, from: period.from, to: period.to, comparison: "NONE", comparisonFrom: "", comparisonTo: "" };
    switch (applied.movementGroup) {
      case "TYPE":
        filter.type = row.key;
        break;
      case "STORE": {
        const groupStore = options.data?.stores.find(({ id }) => id === row.key);
        if (!groupStore) return null;
        filter.companyId = groupStore.companyId;
        filter.storeId = groupStore.id;
        break;
      }
      case "PROVINCE":
        if (!provinces.has(row.key) && row.key !== "UNKNOWN") return null;
        filter.province = row.key;
        break;
      case "DAY": {
        const date = other ? row.comparisonLabel : row.label;
        if (!date) return null;
        filter.from = date;
        filter.to = date;
        break;
      }
    }
    const parent = new URLSearchParams(saved);
    if (back) parent.set("return", back);
    const target = new URLSearchParams({ ...filter, detail: "ledger", return: "#/reports/movements?" + parent });
    return <a className="report-link" href={"#/reports/movements?" + target}
      aria-label={l("viewLedger") + " · " + groupLabel(row, other) + " · " + (other ? s("compared") : s("current"))}>
      {count(rowCount)}
    </a>;
  }

  const ledgerColumns: ReportColumn<LedgerRow>[] = [
    { id: "occurredAt", label: l("occurredAt"), sort: "OCCURRED_AT", required: true, sticky: true, rowHeader: true, cell: (row) => <MovementTime value={row.occurredAt} /> },
    { id: "receivedAt", label: l("receivedAt"), sort: "RECEIVED_AT", defaultHidden: true, cell: (row) => <MovementTime value={row.receivedAt} /> },
    { id: "company", label: s("COMPANY"), cell: (row) => row.companyName ?? row.companyId },
    { id: "store", label: s("STORE"), cell: (row) => row.storeName ?? row.storeId },
    { id: "province", label: s("PROVINCE"), defaultHidden: true, cell: (row) => provinces.get(row.province ?? "") ?? s("unknownProvince") },
    { id: "warehouse", label: l("warehouse"), sort: "WAREHOUSE", cell: (row) => (
      <button className="small-button" type="button" aria-label={s("apply") + " · " + (row.warehouseName || row.warehouseId)}
        onClick={() => filterFromRow(row, "warehouse")}>{row.warehouseName || row.warehouseId}</button>
    ) },
    { id: "product", label: l("product"), sort: "PRODUCT", required: true, cell: (row) => (
      <button className="small-button" type="button" aria-label={s("apply") + " · " + (row.productName || row.productId)}
        onClick={() => filterFromRow(row, "product")}>{row.productName || row.productId}</button>
    ) },
    { id: "type", label: l("type"), sort: "TYPE", cell: (row) => l(row.type) },
    { id: "quantity", label: l("quantity"), numeric: true, cell: (row) => formatDecimal(row.quantity, locale, 3) },
  ];
  const summaryColumns: ReportColumn<MovementGroupRow>[] = [
    { id: "group", label: groupNames[applied.movementGroup], required: true, sticky: true, rowHeader: true, cell: (row) => groupLabel(row) },
  ];
  if (comparing && applied.movementGroup === "DAY") summaryColumns.push({
    id: "compareDay", label: s("comparisonDay"), required: true, cell: (row) => groupLabel(row, true),
  });
  function groupedCount(row: MovementGroupRow, other = false) {
    const value = other ? row.comparisonCount : row.currentCount;
    if (value == null) return missing;
    return groupedLedger(row, other) ?? count(value);
  }
  summaryColumns.push({ id: "current", label: s("current"), numeric: true, cell: (row) => groupedCount(row) });
  if (comparing) summaryColumns.push(
    { id: "comparison", label: s("compared"), numeric: true, cell: (row) => groupedCount(row, true) },
    { id: "change", label: s("difference"), numeric: true, cell: (row) => row.currentCount == null || row.comparisonCount == null ? missing : change(row.currentCount - row.comparisonCount) },
  );
  const points = summaryRows.slice(0, applied.movementGroup === "DAY" ? summaryRows.length : 24);
  const series = [{ name: s("current"), values: points.map((row) => ({ label: groupLabel(row), value: row.currentCount })) }];
  if (comparing) series.push({
    name: s("compared"), values: points.map((row) => ({ label: groupLabel(row), value: row.comparisonCount })),
  });
  const displayPeriod = (period: StatisticsPeriod) => day.format(new Date(period.from + "T00:00:00Z")) + " – " + day.format(new Date(period.to + "T00:00:00Z"));

  return <div className="saas-summary-view saas-statistics view-grid">
    <section className="saas-summary-panel" aria-labelledby="movement-heading">
      <header className="saas-summary-heading">
        <h2 id="movement-heading">{ledgerOnly ? l("ledger") : s("movements")}</h2>
        {back && <a className="secondary-button report-link" href={back}>{s("backToReport")}</a>}
      </header>
      <div className="saas-summary-panel-body">
        <FilterDisclosure filters={{ ...applied, typeLabel: applied.type ? l(applied.type) : "" }} options={options.data} dirty={JSON.stringify(draft) !== JSON.stringify(applied)}>
        <form aria-label={s("filters")} onSubmit={submit}>
          <div className="statistics-filter-grid">
            <PeriodFilterFields draft={draft} options={options.data} comparison={!ledgerOnly} onChange={updateDraft} />
            <label>{l("type")}
              <select className="control-input" value={draft.type} onChange={(event) => updateDraft({ type: event.target.value })}>
                <option value="">{l("allTypes")}</option>
                {movementTypes.map((type) => <option key={type} value={type}>{l(type)}</option>)}
              </select>
            </label>
            <label>{l("warehouse")}
              <select className="control-input" value={draft.warehouseId} disabled={!draft.storeId && !draft.warehouseId}
                onChange={(event) => updateDraft({ warehouseId: event.target.value, warehouseName: event.target.selectedOptions[0]?.text ?? "" })}>
                <option value="">{l("allWarehouses")}</option>
                {draft.warehouseId && !warehouses.data?.rows.some((row) => row.warehouseId === draft.warehouseId)
                  && <option value={draft.warehouseId}>{draft.warehouseName || draft.warehouseId}</option>}
                {warehouses.data?.rows.map((row) => <option key={row.warehouseId} value={row.warehouseId}>{row.name}</option>)}
              </select>
            </label>
            {draft.storeId && <Input label={s("articleSearch")} type="search" value={productSearch} onChange={setProductSearch} />}
            <label>{l("product")}
              <select className="control-input" value={draft.productId} disabled={!draft.storeId && !draft.productId}
                onChange={(event) => updateDraft({ productId: event.target.value, productName: event.target.selectedOptions[0]?.text ?? "" })}>
                <option value="">{l("allProducts")}</option>
                {draft.productId && !products.data?.rows.some((row) => row.productId === draft.productId)
                  && <option value={draft.productId}>{draft.productName || draft.productId}</option>}
                {products.data?.rows.map((row) => <option key={row.productId} value={row.productId}>
                  {row.code ? row.code + " · " + row.name : row.name}
                </option>)}
              </select>
            </label>
            {!ledgerOnly && <label>{s("groupBy")}
              <select className="control-input" value={draft.movementGroup}
                onChange={(event) => updateDraft({ movementGroup: event.target.value as Group })}>
                {groups.map((group) => <option key={group} value={group}>{groupNames[group]}</option>)}
              </select>
            </label>}
          </div>
          <div className="analytics-tools">
            <button type="submit" className="primary-button">{s("apply")}</button>
            <FilterActions dirty={JSON.stringify(draft) !== JSON.stringify(applied)} onReset={reset} />
          </div>
          {validation && <p className="notice error" role="alert">{s(validation)}</p>}
        </form>
        </FilterDisclosure>
        <LoadState {...options} />
        {draft.storeId && <><LoadState {...warehouses} /><LoadState {...products} /></>}
      </div>
    </section>
    {!ledgerOnly && <section className="saas-summary-panel" aria-busy={summary.loading} aria-labelledby="movement-summary-heading">
      <header className="saas-summary-heading">
        <h2 id="movement-summary-heading">{l("summary")}</h2>
        {report?.asOf && <p className="saas-summary-timestamp">{s("asOf")} <MovementTime value={report.asOf} /></p>}
      </header>
      <div className="saas-summary-panel-body">
        <LoadState {...summary} />
        <ReportTools credentials={credentials} preferences={summaryPreferences} columns={summaryColumns}
          exportPath={report ? "/api/v1/admin/reports/export/movements?kind=summary&" + groupedQuery : undefined} />
        {report && <div className="saas-summary-coverage">
          <h3>{s("coverage")}</h3>
          <dl>
            <div><dt>{l("unique")} · {s("current")}</dt><dd>{count(report.currentCount)}</dd></div>
            {report.comparisonCount !== null && <div><dt>{l("unique")} · {s("compared")}</dt><dd>{count(report.comparisonCount)}</dd></div>}
            <div><dt>{l("malformed")}</dt><dd>{count(report.coverage.malformedReceipts)}</dd></div>
            <div><dt>{l("conflicts")}</dt><dd>{count(report.coverage.conflictingMovements)}</dd></div>
            <div><dt>{l("duplicate")}</dt><dd>{count(report.coverage.duplicateReceipts)}</dd></div>
          </dl>
          <p>{l("scope")}</p>
          {report.period && <p>{s("current")} · {displayPeriod(report.period)}</p>}
          {report.comparisonPeriod && <p>{s("compared")} · {displayPeriod(report.comparisonPeriod)}</p>}
        </div>}
        {points.length > 0 && <AnalyticalChart title={l("unique")} kind={applied.movementGroup === "DAY" ? "line" : "bar"}
          series={series} note={s("chartScope")} />}
        <ReportTable title={l("summary")} columns={summaryColumns} rows={summaryRows} rowKey={(row) => row.key} visible={summaryPreferences.isVisible}
          empty={summary.error ? s("noReport") : summary.loading ? missing : l("noRows")} />
        <PaginationControls page={(report?.page ?? summary.page) + 1}
          pages={Math.max(1, Math.ceil((report?.totalGroups ?? 0) / (report?.size ?? reportPageSize)))}
          pageSize={report?.size ?? reportPageSize} total={report?.totalGroups ?? 0} rows={summaryRows}
          setPage={(page) => { if (!summary.loading && typeof page === "number") summary.setPage(page - 1); }} />
      </div>
    </section>}
    <section className="saas-summary-panel" aria-busy={ledger.loading} aria-labelledby="movement-ledger-heading">
      <header className="saas-summary-heading"><h2 id="movement-ledger-heading">{l("ledger")}</h2></header>
      <div className="saas-summary-panel-body">
        <LoadState {...ledger} />
        <ReportTools credentials={credentials} preferences={preferences} columns={ledgerColumns}
          exportPath={ledger.result ? "/api/v1/admin/reports/export/movements?kind=ledger&" + ledgerQuery : undefined} />
        <ReportTable title={l("ledger")} columns={ledgerColumns} rows={rows} rowKey={(row) => [row.companyId, row.storeId, row.movementId].join(":")}
          visible={preferences.isVisible} empty={ledger.error ? s("noReport") : ledger.loading ? missing : l("noRows")}
          sort={sort} onSort={(key) => setSort(nextSort(sort, key, key.endsWith("_AT") ? "DESC" : "ASC"))} />
        <PaginationControls page={(ledger.result?.page ?? ledger.page) + 1}
          pages={Math.max(1, Math.ceil((ledger.result?.total ?? 0) / (ledger.result?.size ?? reportPageSize)))}
          pageSize={ledger.result?.size ?? reportPageSize} total={ledger.result?.total ?? 0} rows={rows}
          setPage={(page) => { if (!ledger.loading && typeof page === "number") ledger.setPage(page - 1); }} />
      </div>
    </section>
  </div>;
}
