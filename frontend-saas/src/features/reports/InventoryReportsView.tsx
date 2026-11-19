import { useEffect, useRef, useState } from "react";
import { useRefreshVersion, useRemote } from "../../app/RefreshContext";
import { localeFor, useI18n } from "../../i18n";
import { request } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import { formatUtcDateTime } from "../../lib/utc-date-time.mjs";
import { ReportTools, useReportPreferences } from "../../shared/analytics/ReportTools";
import { FilterDisclosure } from "../../shared/analytics/FilterDisclosure";
import { EmptyState, Input, Metric, StatusPill } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";
import { comparisonProblem, InventoryComparisonView } from "./InventoryComparisonView";
import { ProvinceFilter } from "./PeriodFilterFields";
import { FilterActions } from "./PeriodShortcuts";
import { nextSort, ReportTable, type ReportColumn, type ReportSort } from "./ReportTable";
import { readAnalyticsFilters, useAnalyticsContext } from "./analytics-context";
import { documentReportLink, formatDecimal, initialFilters, missing, provinces, reportLinkFilters, reportQuery, restorePeriodFilters } from "./report-query";
import { useInventoryLabels } from "./inventory-labels";
import type {
  InventoryBalance, InventoryOverview, InventoryPage, InventoryProduct, InventoryScope,
  InventorySnapshot, InventoryStore, InventoryWarehouse, SnapshotPage,
} from "./inventory-types";
import type { StatisticsOptions } from "./statistics-types";
import { useReportPage } from "./useReportPage";
import "./inventory.css";

const inventoryPath = "/api/v1/admin/reports/inventory";
const optionsPath = "/api/v1/admin/reports/statistics/options";
const inventoryExport = "/api/v1/admin/reports/export/inventory";
const defaultOverviewSort: ReportSort = { key: "STORE", direction: "ASC" };
const overviewSorts = ["COMPANY", "STORE", "PRODUCTS", "BALANCES", "CAPTURED_AT"];

function inventoryScope(filters: Record<string, string>) {
  return {
    companyId: filters.companyId ?? "", storeId: filters.storeId ?? "",
    province: provinces.has(filters.province) || filters.province === "UNKNOWN" ? filters.province : "",
  };
}

function inventoryParams(filters: Record<string, string>) {
  const query = new URLSearchParams();
  for (const [name, value] of Object.entries(filters)) {
    if (value) query.set(name, value);
  }
  return query;
}

function useInventoryPage<T extends InventoryPage<unknown>>(
  credentials: Credentials, endpoint: string, filters: Record<string, string>, enabled = true,
) {
  return useReportPage<T>(credentials, `${inventoryPath}/${endpoint}`, inventoryParams(filters), (data) => data.total, enabled);
}

function inventorySort(saved: Record<string, string>, keys: string[], fallback: ReportSort): ReportSort {
  return keys.includes(saved.sort) ? { key: saved.sort, direction: saved.direction === "DESC" ? "DESC" : "ASC" } : fallback;
}

export function InventoryReportsView({ credentials, permissions }: {
  credentials: Credentials;
  permissions: ReadonlySet<string>;
}) {
  const { t } = useI18n();
  if (!permissions.has("VIEW_REPORTS")) return <EmptyState text={t("noPermissionAction")} />;
  return <InventoryContent key={credentials.accessToken} credentials={credentials} />;
}

function InventoryContent({ credentials }: { credentials: Credentials }) {
  const { t } = useI18n();
  const l = useInventoryLabels();
  const [link] = useState(reportLinkFilters);
  const [initial] = useState(() => Object.keys(link).length ? link : readAnalyticsFilters(credentials, "inventory"));
  const [draft, setDraft] = useState(() => inventoryScope(initial));
  const [applied, setApplied] = useState(draft);
  const [sort, setSort] = useState(() => inventorySort(initial, overviewSorts, defaultOverviewSort));
  const [store, setStore] = useState<InventoryStore | null>(null);
  const [openedFromLink, setOpenedFromLink] = useState(false);
  const [openTarget, setOpenTarget] = useState<InventoryScope | null>(() => {
    const explicit = Object.keys(link).length > 0;
    if (link.overview === "1") return null;
    const storeId = explicit ? link.openedStoreId || link.storeId : initial.openedStoreId;
    const companyId = (explicit ? link.openedCompanyId || link.companyId : initial.openedCompanyId) ?? "";
    if (!storeId || (draft.storeId && draft.storeId !== storeId) || (draft.companyId && draft.companyId !== companyId)) return null;
    return { storeId, companyId };
  });
  const preferences = useReportPreferences(credentials, "inventory-overview", ["receivedAt"]);
  const restoreFocus = useRef("");
  const overviewHeading = useRef<HTMLHeadingElement>(null);
  const storeButtons = useRef(new Map<string, HTMLButtonElement>());
  const options = useRemote(
    () => request<StatisticsOptions>(credentials, optionsPath),
    [credentials.accessToken],
  );
  const filters = { ...applied, sort: sort.key, direction: sort.direction };
  const overview = useInventoryPage<InventoryOverview>(credentials, "overview", filters);
  const useOverview = !!openTarget && openTarget.storeId === applied.storeId && openTarget.companyId === applied.companyId;
  const targetQuery = reportQuery(inventoryParams({ ...applied, ...openTarget }), 0, 1);
  const linkedStore = useRemote(
    () => openTarget && !useOverview
      ? request<InventoryOverview>(credentials, `${inventoryPath}/overview?${targetQuery}`) : Promise.resolve(null),
    [credentials.accessToken, targetQuery, !!openTarget, useOverview],
  );
  const target = useOverview ? overview : linkedStore;
  const stores = options.data?.stores.filter((item) => !draft.companyId || item.companyId === draft.companyId);
  const totals = overview.data?.totals;
  const count = useInventoryCount();
  const back = /^#\/reports(?:\/(?:sales|articles|movements|provinces|averages|documents))?(?:\?.*)?$/.test(link.return ?? "") ? link.return : "";

  const overviewContext = {
    ...filters, openedCompanyId: store?.companyId ?? openTarget?.companyId ?? "", openedStoreId: store?.storeId ?? openTarget?.storeId ?? "",
    overview: store || openTarget ? "" : "1",
  };
  useAnalyticsContext(credentials, "inventory", overviewContext,
    { syncUrl: !store && !openTarget, urlFilters: { ...overviewContext, from: link.from ?? "", to: link.to ?? "", return: back } });

  useEffect(() => {
    if (!openTarget) return;
    const row = target.data?.rows.find((item) => item.storeId === openTarget.storeId
      && (!openTarget.companyId || item.companyId === openTarget.companyId));
    if (row) {
      restoreFocus.current = row.storeId;
      setOpenedFromLink(true);
      setStore(row);
      setOpenTarget(null);
    } else if (!target.loading && (target.error || target.data?.total === 0 || !useOverview)) {
      setOpenTarget(null);
    }
  }, [openTarget, target.data, target.error, target.loading, useOverview]);

  useEffect(() => {
    if (store || !restoreFocus.current) return;
    const target = storeButtons.current.get(restoreFocus.current) ?? overviewHeading.current;
    target?.focus();
    restoreFocus.current = "";
  }, [store]);

  function apply(next: typeof draft, nextSort = sort) {
    if (overview.page === 0 && JSON.stringify(next) === JSON.stringify(applied) && nextSort === sort) {
      overview.reload();
    }
    setOpenTarget(null);
    setDraft(next);
    setApplied(next);
    setSort(nextSort);
    overview.setPage(0);
  }

  const columns: ReportColumn<InventoryStore>[] = [
    { id: "company", label: t("company"), sort: "COMPANY", cell: (row) => row.companyName },
    { id: "store", label: l("store"), sort: "STORE", cell: (row) => row.storeName, required: true, sticky: true, rowHeader: true },
    { id: "capture", label: l("capture"), cell: (row) => <StatusPill status={l(row.snapshot ? "complete" : "pending")} tone={row.snapshot ? "ok" : "warning"} /> },
    { id: "capturedAt", label: l("capturedAt"), sort: "CAPTURED_AT", cell: (row) => row.snapshot ? <UtcTime value={row.snapshot.capturedAt} /> : missing },
    { id: "receivedAt", label: l("receivedAt"), defaultHidden: true, cell: (row) => row.snapshot ? <UtcTime value={row.snapshot.completedAt} /> : missing },
    { id: "products", label: l("products"), sort: "PRODUCTS", numeric: true, cell: (row) => row.snapshot ? count(row.snapshot.productCount) : missing },
    { id: "warehouses", label: l("warehouses"), numeric: true, cell: (row) => row.snapshot ? count(row.snapshot.warehouseCount) : missing },
    { id: "balances", label: l("balances"), sort: "BALANCES", numeric: true, cell: (row) => row.snapshot ? count(row.snapshot.balanceCount) : missing },
    { id: "actions", label: "", required: true, sticky: true, cell: (row) => <button className="small-button" type="button"
      ref={(node) => { if (node) storeButtons.current.set(row.storeId, node); else storeButtons.current.delete(row.storeId); }}
      aria-label={`${l("openStore")} · ${row.storeName}`}
      onClick={() => {
        setOpenTarget(null);
        setOpenedFromLink(false);
        restoreFocus.current = row.storeId;
        setStore(row);
      }}>{l("openStore")}</button> },
  ];

  if (store) {
    const linkedCompany = link.openedCompanyId || link.companyId;
    const linkedDetail = openedFromLink && (link.mode || link.productId || link.snapshotId || link.comparisonSnapshotId)
      && (!linkedCompany || store.companyId === linkedCompany) && store.storeId === (link.openedStoreId || link.storeId) ? link : {};
    return <StoreInventory credentials={credentials} store={store}
      link={linkedDetail} overviewContext={overviewContext} back={back}
      onBack={() => setStore(null)} />;
  }

  return (
    <div className="saas-summary-view saas-inventory view-grid">
      <section className="saas-summary-panel" aria-labelledby="inventory-heading">
        <header className="saas-summary-heading">
          <h2 id="inventory-heading" tabIndex={-1} ref={overviewHeading}>{l("title")}</h2>
          {back && <a className="secondary-button report-link" href={back}>{l("backToReport")}</a>}
        </header>
        <div className="saas-summary-panel-body">
          <FilterDisclosure filters={applied} options={options.data} dirty={JSON.stringify(draft) !== JSON.stringify(applied)}>
          <form id="inventory-overview-filters" className="inventory-filter-grid" aria-label={l("title")}
            onSubmit={(event) => { event.preventDefault(); apply(draft); }}>
            <label>
              {t("company")}
              <select className="control-input" value={draft.companyId} disabled={!options.data}
                onChange={(event) => setDraft({ ...draft, companyId: event.target.value, storeId: "" })}>
                <option value="">{t("allCompanies")}</option>
                {options.data?.companies.map((company) => <option key={company.id} value={company.id}>{company.name}</option>)}
              </select>
            </label>
            <label>
              {l("store")}
              <select className="control-input" value={draft.storeId} disabled={!stores?.length}
                onChange={(event) => setDraft({ ...draft, storeId: event.target.value })}>
                <option value="">{l("allStores")}</option>
                {stores?.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
              </select>
            </label>
            <ProvinceFilter value={draft.province} onChange={(province) => setDraft({ ...draft, province })} />
          </form>
          </FilterDisclosure>
          <ReportTools credentials={credentials} preferences={preferences}
            columns={columns.filter((column) => column.label)}
            exportPath={overview.data ? inventoryExport + "?kind=overview&" + new URLSearchParams(filters) : undefined}
            leadingAction={<>
              <button className="primary-button" type="submit" form="inventory-overview-filters">{l("apply")}</button>
              <FilterActions dirty={JSON.stringify(draft) !== JSON.stringify(applied)}
                onReset={() => apply(inventoryScope({}), defaultOverviewSort)} />
            </>} />
          <LoadState {...options} />
        </div>
      </section>
      <section className="saas-summary-panel" aria-labelledby="inventory-coverage-heading" aria-busy={overview.loading}>
        <header className="saas-summary-heading"><h2 id="inventory-coverage-heading">{l("coverage")}</h2></header>
        <div className="saas-summary-metrics inventory-coverage">
          <Metric label={l("reportingStores")} value={totals ? `${count(totals.reportedStores)} / ${count(totals.stores)}` : missing} />
          <Metric label={l("pendingStores")} value={totals ? count(totals.stores - totals.reportedStores) : missing}
            tone={totals && totals.reportedStores < totals.stores ? "warning" : undefined} />
          <Metric label={l("products")} value={totals?.reportedStores ? count(totals.products) : missing} />
          <Metric label={l("balances")} value={totals?.reportedStores ? count(totals.balances) : missing} />
        </div>
        <div className="saas-summary-panel-body">
          <div className="saas-summary-coverage">
            <dl>
              {([
                ["activeProducts", "activeProducts"], ["warehouses", "warehouses"], ["positive", "positiveCount"],
                ["zero", "zeroCount"], ["negative", "negativeCount"],
              ] as const).map(([label, key]) => (
                <div key={label}><dt>{l(label)}</dt><dd>{totals?.reportedStores ? count(totals[key]) : missing}</dd></div>
              ))}
            </dl>
          </div>
          <p className="inventory-note">{l("scope")}</p>
        </div>
      </section>
      <section className="saas-summary-panel" aria-labelledby="inventory-store-list-heading" aria-busy={overview.loading}>
        <header className="saas-summary-heading"><h2 id="inventory-store-list-heading">{l("stores")}</h2></header>
        <div className="saas-summary-panel-body">
          <LoadState {...overview} />
          <ReportTable title={l("stores")} columns={columns} rows={overview.data?.rows ?? []} rowKey={(row) => row.storeId}
            empty={tableMessage(overview, l("noStores"), l("unavailable")) ?? missing} visible={preferences.isVisible}
            sort={sort} onSort={(key) => setSort(nextSort(sort, key, ["PRODUCTS", "BALANCES", "CAPTURED_AT"].includes(key) ? "DESC" : "ASC"))} />
          <InventoryPager data={overview.data} busy={overview.loading} page={overview.page} onPage={overview.setPage} />
        </div>
      </section>
    </div>
  );
}

type InventoryDetailMode = "catalog" | "stock" | "compare";
type WarehouseChoice = { id: string; name: string; snapshotId: string };
type CaptureSelection = { source: "overview" | "latest" } | { source: "history"; snapshotId: string; snapshot?: InventorySnapshot };
type DetailFilters = { search: string; active: string; warehouse: WarehouseChoice | null; productId: string; productName: string };
const unfiltered: DetailFilters = { search: "", active: "", warehouse: null, productId: "", productName: "" };
const defaultDetailSort: ReportSort = { key: "PRODUCT", direction: "ASC" };
const catalogOptionalColumns = ["barcode", "secondaryBarcode", "family", "subfamily", "type"];
const stockOptionalColumns = ["type", "productStatus"];

function detailFilters(saved: Record<string, string>): DetailFilters {
  return {
    search: saved.search?.slice(0, 120) ?? "",
    active: saved.active === "true" || saved.active === "false" ? saved.active : "",
    warehouse: saved.warehouseId && saved.warehouseSnapshotId
      ? { id: saved.warehouseId, name: saved.warehouseName || saved.warehouseId, snapshotId: saved.warehouseSnapshotId }
      : null,
    productId: saved.productId ?? "",
    productName: saved.productId ? saved.productName ?? "" : "",
  };
}

function StoreInventory({ credentials, store, link, overviewContext, back, onBack }: {
  credentials: Credentials;
  store: InventoryStore;
  link: Record<string, string>;
  overviewContext: Record<string, string>;
  back: string;
  onBack: () => void;
}) {
  const l = useInventoryLabels();
  const { language } = useI18n();
  const refreshVersion = useRefreshVersion();
  const [openingVersion] = useState(refreshVersion);
  const scope = { companyId: store.companyId, storeId: store.storeId };
  const contextKey = `inventory-detail:${store.companyId}:${store.storeId}`;
  const [initial] = useState(() => Object.keys(link).length ? link : readAnalyticsFilters(credentials, contextKey, { shareScope: false }));
  const [period] = useState(() => {
    const defaults = initialFilters();
    const dateScope = Object.keys(link).length ? link : readAnalyticsFilters(credentials, "inventory");
    const { from, to } = restorePeriodFilters(dateScope, defaults) ?? defaults;
    return { from, to };
  });
  const [mode, setMode] = useState<InventoryDetailMode>(() => initial.mode === "stock" || initial.mode === "compare" ? initial.mode : "catalog");
  const [selection, setSelection] = useState<CaptureSelection>(() => initial.snapshotId
    ? { source: "history", snapshotId: initial.snapshotId } : { source: "overview" });
  const [comparison, setComparison] = useState<{ snapshotId: string; snapshot?: InventorySnapshot } | null>(() =>
    initial.comparisonSnapshotId ? { snapshotId: initial.comparisonSnapshotId } : null);
  const [comparisonCurrentId, setComparisonCurrentId] = useState(initial.comparisonCurrentSnapshotId ?? "");
  const [sorts, setSorts] = useState(() => ({
    catalog: inventorySort({ sort: initial.catalogSort, direction: initial.catalogDirection }, ["PRODUCT", "CODE", "TYPE", "ACTIVE"], defaultDetailSort),
    stock: inventorySort({ sort: initial.stockSort, direction: initial.stockDirection }, ["PRODUCT", "WAREHOUSE", "QUANTITY"], defaultDetailSort),
  }));
  const [draft, setDraft] = useState(() => detailFilters(initial));
  const [applied, setApplied] = useState(draft);
  const [historyOpen, setHistoryOpen] = useState(false);
  const optionalColumns = mode === "stock" ? stockOptionalColumns : catalogOptionalColumns;
  const preferences = useReportPreferences(credentials, "inventory-" + mode, optionalColumns);
  const heading = useRef<HTMLHeadingElement>(null);
  const comparisonTab = useRef<HTMLButtonElement>(null);
  const stock = mode === "stock";
  const comparing = mode === "compare";
  const historicalId = selection.source === "history" ? selection.snapshotId : "";
  const selectedId = comparing && comparisonCurrentId ? comparisonCurrentId : historicalId;
  const selectedSnapshot = selection.source === "history" ? selection.snapshot ?? null : store.snapshot;
  const knownCapture = selectedId ? [selectedSnapshot, store.snapshot].find((item) => item?.snapshotId === selectedId) ?? null : selectedSnapshot;
  const resolveCapture = !knownCapture || (!selectedId && (selection.source === "latest" || openingVersion !== refreshVersion));
  const captureQuery = reportQuery(inventoryParams({ ...scope, snapshotId: selectedId }), 0, 1);
  const capture = useRemote(
    async () => resolveCapture
      ? (await request<SnapshotPage<InventoryProduct>>(credentials, `${inventoryPath}/products?${captureQuery}`)).snapshot
      : null,
    [credentials.accessToken, captureQuery, resolveCapture],
  );
  const snapshot = resolveCapture ? (!selectedId || capture.data?.snapshotId === selectedId ? capture.data : null) : knownCapture;
  const snapshotId = snapshot?.snapshotId ?? "";
  const comparisonId = comparison?.snapshotId ?? "";
  const resolvePrevious = comparing && !!comparisonId && !comparison?.snapshot;
  const comparisonQuery = reportQuery(inventoryParams({ ...scope, snapshotId: comparisonId }), 0, 1);
  const previous = useRemote(
    async () => resolvePrevious
      ? (await request<SnapshotPage<InventoryProduct>>(credentials, `${inventoryPath}/products?${comparisonQuery}`)).snapshot
      : null,
    [credentials.accessToken, comparisonQuery, resolvePrevious],
  );
  const previousSnapshot = comparison?.snapshot ?? (previous.data?.snapshotId === comparisonId ? previous.data : null);
  const detailMode = stock ? "stock" : "catalog";
  const endpoint = stock ? "balances" : "products";
  const sort = sorts[detailMode];
  const warehouse = stock && applied.warehouse?.snapshotId === snapshotId ? applied.warehouse : null;
  const draftWarehouse = draft.warehouse?.snapshotId === snapshotId ? draft.warehouse : null;
  const filters = { ...scope, snapshotId, search: applied.search, active: applied.active,
    productId: stock ? applied.productId : "", warehouseId: warehouse?.id ?? "",
    sort: sort.key, direction: sort.direction };
  const context = {
    ...scope, ...period, mode, search: applied.search, active: applied.active,
    productId: applied.productId, productName: applied.productName, snapshotId: historicalId,
    comparisonSnapshotId: comparisonId, comparisonCurrentSnapshotId: comparisonCurrentId,
    warehouseId: applied.warehouse?.id ?? "", warehouseName: applied.warehouse?.name ?? "",
    warehouseSnapshotId: applied.warehouse?.snapshotId ?? "",
    catalogSort: sorts.catalog.key, catalogDirection: sorts.catalog.direction,
    stockSort: sorts.stock.key, stockDirection: sorts.stock.direction,
  };
  const urlFilters = { ...context, ...overviewContext, return: back };
  useAnalyticsContext(credentials, contextKey, context, { route: "inventory", urlFilters, shareScope: false });
  const detail = useInventoryPage<SnapshotPage<InventoryProduct | InventoryBalance>>(
    credentials, endpoint, filters, !!snapshotId && !comparing,
  );
  const visibleSnapshot = detail.data?.snapshot ?? snapshot;
  const warehouseEnabled = stock && !!snapshotId;
  const warehouses = useInventoryPage<SnapshotPage<InventoryWarehouse>>(
    credentials, "warehouses", { ...scope, snapshotId }, warehouseEnabled,
  );
  const resolving = resolveCapture && capture.loading;
  const resolvingComparison = resolving || (resolvePrevious && previous.loading);
  const unavailableCaptures: ("currentCapture" | "previousCapture")[] = [];
  if (selectedId && !snapshot) unavailableCaptures.push("currentCapture");
  if (comparisonId && !previousSnapshot) unavailableCaptures.push("previousCapture");
  const resultLoading = resolving || (!!snapshotId && detail.loading);
  const count = useInventoryCount();
  const quantity = (value: string) => formatDecimal(value, localeFor(language), 3);

  useEffect(() => {
    if (!document.activeElement?.closest(".main-panel")) heading.current?.focus();
  }, []);

  function selectCapture(next: InventorySnapshot | null) {
    if (!next && resolveCapture && !historicalId) capture.reload();
    setSelection(next ? { source: "history", snapshotId: next.snapshotId, snapshot: next } : { source: "latest" });
    setComparison(null);
    setComparisonCurrentId("");
    setDraft((current) => ({ ...current, warehouse: null }));
    setApplied((current) => ({ ...current, warehouse: null }));
    detail.setPage(0);
    warehouses.setPage(0);
  }

  function apply(next: DetailFilters) {
    const checked = { ...next, search: next.search.trim() };
    if (detail.page === 0 && JSON.stringify(checked) === JSON.stringify(applied)) detail.reload();
    setDraft(checked);
    setApplied(checked);
    detail.setPage(0);
  }

  function reset() {
    setDetailSort(defaultDetailSort);
    apply(unfiltered);
  }

  function setDetailSort(next: ReportSort) {
    setSorts((current) => ({ ...current, [detailMode]: next }));
  }

  const empty = !snapshotId && !resolving
    ? l(capture.error ? "unavailable" : "noCurrentSnapshot")
    : tableMessage(detail, l(stock ? "noBalances" : "noProducts"), l("unavailable"));
  function relatedReports(row: InventoryProduct | InventoryBalance) {
    const product = { ...scope, productId: row.productId, productName: row.name };
    const returnUrl = "#/reports/inventory?" + inventoryParams(urlFilters);
    const movement = new URLSearchParams({ ...product, ...period, comparison: "NONE", return: returnUrl });
    return <div className="report-row-links">
      {!stock && <button className="small-button" type="button" aria-label={l("stock") + " · " + row.name}
        onClick={() => {
          setMode("stock");
          apply({ ...unfiltered, productId: row.productId, productName: row.name });
        }}>{l("stock")}</button>}
      <a className="small-button report-link" href={documentReportLink({ ...product, ...period, province: "" }, returnUrl)}
        aria-label={l("viewSales") + " · " + row.name}>{l("viewSales")}</a>
      <a className="small-button report-link" href={"#/reports/movements?" + movement}
        aria-label={l("viewMovements") + " · " + row.name}>{l("viewMovements")}</a>
    </div>;
  }
  const sharedColumns: ReportColumn<InventoryProduct | InventoryBalance>[] = [
    { id: "code", label: l("code"), sort: stock ? undefined : "CODE", cell: (row) => row.code || missing },
    { id: "product", label: l("product"), sort: "PRODUCT", required: true, sticky: true, rowHeader: true, cell: (row) => row.name },
  ];
  if (!stock) sharedColumns.push(
    { id: "barcode", label: l("barcode"), cell: (row) => (row as InventoryProduct).barcode || missing },
    { id: "secondaryBarcode", label: l("secondaryBarcode"), cell: (row) => (row as InventoryProduct).secondaryBarcode || missing },
    { id: "family", label: l("family"), cell: (row) => (row as InventoryProduct).familyName || missing },
    { id: "subfamily", label: l("subfamily"), cell: (row) => (row as InventoryProduct).subfamilyName || missing },
  );
  sharedColumns.push(
    { id: "type", label: l("type"), sort: stock ? undefined : "TYPE", cell: (row) => l(row.productType) },
    { id: "productStatus", label: l("productStatus"), sort: stock ? undefined : "ACTIVE", cell: (row) => <ProductStatus active={row.active} /> },
  );
  if (stock) sharedColumns.push(
    { id: "warehouse", label: l("warehouse"), sort: "WAREHOUSE", required: true, cell: (row) => (row as InventoryBalance).warehouseName },
    { id: "quantity", label: l("quantity"), sort: "QUANTITY", numeric: true, cell: (row) => quantity((row as InventoryBalance).quantity) },
  );
  sharedColumns.push({ id: "actions", label: l("relatedReports"), required: true, sticky: true, cell: relatedReports });
  for (const column of sharedColumns) column.defaultHidden = optionalColumns.includes(column.id);

  return (
    <div className="saas-summary-view saas-inventory view-grid">
      <section className="saas-summary-panel" aria-labelledby="inventory-detail-heading">
        <header className="saas-summary-heading">
          <div className="inventory-store-heading">
            <p>{store.companyName}</p>
            <h2 id="inventory-detail-heading" tabIndex={-1} ref={heading}>{store.storeName}</h2>
          </div>
          <div className="report-row-links">
            <button className="secondary-button" type="button" onClick={onBack}>{l("back")}</button>
            {back && <a className="secondary-button report-link" href={back}>{l("backToReport")}</a>}
          </div>
        </header>
        <div className="saas-summary-panel-body">
          <div className="saas-summary-coverage inventory-capture">
            <div className="inventory-capture-heading">
              {visibleSnapshot && <>
                <h3>{l(selection.source === "history" ? "historical" : "latestShown")}</h3>
                <StatusPill status={l(visibleSnapshot.currentInstallation ? "currentInstallation" : "previousInstallation")}
                  tone={visibleSnapshot.currentInstallation ? "ok" : "warning"} />
              </>}
              <button className="small-button" type="button" disabled={resolving} onClick={() => selectCapture(null)}>{l("latest")}</button>
            </div>
            {visibleSnapshot && (
              <dl>
                <div><dt>{l("capturedAt")}</dt><dd><UtcTime value={visibleSnapshot.capturedAt} /></dd></div>
                <div><dt>{l("receivedAt")}</dt><dd><UtcTime value={visibleSnapshot.completedAt} /></dd></div>
              </dl>
            )}
          </div>
          <details className="inventory-history" open={historyOpen} onToggle={(event) => setHistoryOpen(event.currentTarget.open)}>
            <summary>{l("history")}</summary>
            {historyOpen && <SnapshotHistory credentials={credentials} scope={scope} selected={snapshotId} current={snapshot}
              onSelect={(next) => {
                selectCapture(next);
                setHistoryOpen(false);
                heading.current?.focus();
              }} onCompare={(next) => {
                 setComparison({ snapshotId: next.snapshotId, snapshot: next });
                 setComparisonCurrentId(snapshotId);
                setMode("compare");
                setHistoryOpen(false);
                comparisonTab.current?.focus();
              }} />}
          </details>
          <div className="segmented" role="group" aria-label={l("title")}>
            {(["catalog", "stock", "compare"] as const).map((value) => (
              <button key={value} type="button" className={mode === value ? "active" : ""} aria-pressed={mode === value}
                ref={value === "compare" ? comparisonTab : undefined}
                onClick={() => { setMode(value); detail.setPage(0); }}>{l(value)}</button>
            ))}
          </div>
        </div>
      </section>
      {comparing && (resolvingComparison
        ? <section className="saas-summary-panel" aria-labelledby="inventory-comparison-heading" aria-busy="true">
          <header className="saas-summary-heading"><h2 id="inventory-comparison-heading">{l("compare")}</h2></header>
          <div className="saas-summary-panel-body"><LoadState loading error={null} reload={capture.reload} /></div>
        </section>
        : <InventoryComparisonView key={`${snapshotId}:${comparisonId}`} credentials={credentials} scope={scope}
          current={snapshot} previous={previousSnapshot} unavailableCaptures={unavailableCaptures} search={applied.search}
          warehouse={applied.warehouse?.snapshotId === snapshotId ? applied.warehouse : null} />)}
      {!comparing && <section className="saas-summary-panel" aria-labelledby="inventory-detail-results-heading" aria-busy={resultLoading}>
        <header className="saas-summary-heading">
          <h2 id="inventory-detail-results-heading">{l(stock ? "stockByWarehouse" : "catalog")}</h2>
          {detail.data && visibleSnapshot && <span className="inventory-total">{l("records")} {count(detail.data.total)}</span>}
        </header>
        <div className="saas-summary-panel-body">
          {stock && draft.productId && <div className="inventory-product-filter">
            <span>{l("product")} <strong>{draft.productName || draft.productId}</strong></span>
            <button className="small-button" type="button"
              onClick={() => setDraft({ ...draft, productId: "", productName: "" })}>{l("clearProduct")}</button>
          </div>}
          {!stock && applied.productId && <p className="inventory-note">{l("catalogueScope")}</p>}
          <FilterDisclosure filters={{ ...filters, companyName: store.companyName, storeName: store.storeName, productName: stock ? applied.productName : "", warehouseName: warehouse?.name ?? "" }} dirty={JSON.stringify(draft) !== JSON.stringify(applied)}>
          <form id="inventory-item-filters" className="inventory-filter-grid" aria-label={l(stock ? "stock" : "catalog")}
            onSubmit={(event) => { event.preventDefault(); apply(draft); }}>
            <Input label={l("search")} type="search" value={draft.search} onChange={(search) => setDraft({ ...draft, search })} />
            <label>
              {l("productStatus")}
              <select className="control-input" value={draft.active} onChange={(event) => setDraft({ ...draft, active: event.target.value })}>
                <option value="">{l("allStatuses")}</option>
                <option value="true">{l("active")}</option>
                <option value="false">{l("inactive")}</option>
              </select>
            </label>
            {stock && (
              <label>
                {l("warehouse")}
                <select className="control-input" value={draftWarehouse?.id ?? ""} disabled={!warehouses.data && !draftWarehouse}
                  onChange={(event) => {
                    const item = warehouses.data?.rows.find((row) => row.warehouseId === event.target.value);
                    setDraft({ ...draft, warehouse: item ? { id: item.warehouseId, name: item.name, snapshotId } : null });
                  }}>
                  <option value="">{l("allWarehouses")}</option>
                  {draftWarehouse && !warehouses.data?.rows.some((item) => item.warehouseId === draftWarehouse.id) &&
                    <option value={draftWarehouse.id}>{draftWarehouse.name}</option>}
                  {warehouses.data?.rows.map((item) => <option key={item.warehouseId} value={item.warehouseId}>{item.name}</option>)}
                </select>
              </label>
            )}
          </form>
          </FilterDisclosure>
          <ReportTools credentials={credentials} preferences={preferences} columns={sharedColumns}
            exportPath={detail.data ? inventoryExport + `?kind=${endpoint}&` + inventoryParams(filters) : undefined}
            leadingAction={<>
              <button className="primary-button" type="submit" form="inventory-item-filters">{l("apply")}</button>
              <FilterActions dirty={JSON.stringify(draft) !== JSON.stringify(applied)} onReset={reset} />
            </>} />
          {warehouseEnabled && <>
            <LoadState {...warehouses} />
            <InventoryPager data={warehouses.data} busy={warehouses.loading} page={warehouses.page}
              onPage={warehouses.setPage} label={l("warehousePages")} context={l("warehouses")} />
          </>}
          {resolveCapture && <LoadState {...capture} />}
          {snapshotId && <LoadState {...detail} />}
          <ReportTable title={l(stock ? "stockByWarehouse" : "catalog")} columns={sharedColumns} rows={detail.data?.rows ?? []}
            rowKey={(row) => "warehouseId" in row ? row.productId + ":" + row.warehouseId : row.productId}
            empty={empty ?? missing} visible={preferences.isVisible} sort={sort}
            onSort={(key) => setDetailSort(nextSort(sort, key, key === "QUANTITY" ? "DESC" : "ASC"))} />
          <InventoryPager data={detail.data} busy={resultLoading} page={detail.page} onPage={detail.setPage} />
        </div>
      </section>}
    </div>
  );
}

function SnapshotHistory({ credentials, scope, selected, current, onSelect, onCompare }: {
  credentials: Credentials;
  scope: InventoryScope;
  selected?: string;
  current: InventorySnapshot | null;
  onSelect: (snapshot: InventorySnapshot) => void;
  onCompare: (snapshot: InventorySnapshot) => void;
}) {
  const l = useInventoryLabels();
  const [sort, setSort] = useState(() => inventorySort(readAnalyticsFilters(credentials, "inventory-history", { shareScope: false }),
    ["CAPTURED_AT", "PRODUCTS", "BALANCES"], { key: "CAPTURED_AT", direction: "DESC" }));
  const preferences = useReportPreferences(credentials, "inventory-history", ["receivedAt"]);
  const filters = { ...scope, sort: sort.key, direction: sort.direction };
  useAnalyticsContext(credentials, "inventory-history", { sort: sort.key, direction: sort.direction }, { syncUrl: false, shareScope: false });
  const history = useInventoryPage<InventoryPage<InventorySnapshot>>(credentials, "history", filters);
  const count = useInventoryCount();
  const columns: ReportColumn<InventorySnapshot>[] = [
    { id: "capturedAt", label: l("capturedAt"), sort: "CAPTURED_AT", required: true, sticky: true, rowHeader: true, cell: (row) => <UtcTime value={row.capturedAt} /> },
    { id: "receivedAt", label: l("receivedAt"), defaultHidden: true, cell: (row) => <UtcTime value={row.completedAt} /> },
    { id: "capture", label: l("capture"), cell: (row) => <StatusPill status={l(row.currentInstallation ? "currentInstallation" : "previousInstallation")}
      tone={row.currentInstallation ? "ok" : "warning"} /> },
    { id: "products", label: l("products"), numeric: true, sort: "PRODUCTS", cell: (row) => count(row.productCount) },
    { id: "balances", label: l("balances"), numeric: true, sort: "BALANCES", cell: (row) => count(row.balanceCount) },
    { id: "actions", label: "", required: true, sticky: true, cell: (row) => {
      const problem = current && comparisonProblem(current, row);
      return <div className="inventory-history-actions">
        <button className="small-button" type="button" aria-pressed={selected === row.snapshotId} onClick={() => onSelect(row)}>{l("openCapture")}</button>
        {current && selected !== row.snapshotId && <button className="small-button" type="button" title={problem ? l(problem) : undefined}
          onClick={() => onCompare(row)}>{l("chooseComparison")}</button>}
      </div>;
    } },
  ];

  return (
    <div className="inventory-history-body" aria-busy={history.loading}>
      <LoadState {...history} />
      <ReportTools credentials={credentials} preferences={preferences}
        columns={columns.filter((column) => column.label)}
        exportPath={history.data ? inventoryExport + "?kind=history&" + new URLSearchParams(filters) : undefined} />
      <ReportTable title={l("history")} columns={columns} rows={history.data?.rows ?? []} rowKey={(row) => row.snapshotId}
        empty={tableMessage(history, l("noHistory"), l("unavailable")) ?? missing} visible={preferences.isVisible}
        sort={sort} onSort={(key) => setSort(nextSort(sort, key, "DESC"))} />
      <InventoryPager data={history.data} busy={history.loading} page={history.page} onPage={history.setPage} label={l("history")} />
    </div>
  );
}

function InventoryPager({ data, busy, page, onPage, label, context }: {
  data: InventoryPage<unknown> | null;
  busy: boolean;
  page: number;
  onPage: (page: number) => void;
  label?: string;
  context?: string;
}) {
  const { t } = useI18n();
  const count = useInventoryCount();
  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : null;
  if (!busy && (pages ?? 1) <= 1 && page === 0) return null;
  const previousDisabled = busy || page === 0;
  const nextDisabled = busy || pages === null || page + 1 >= pages;
  return (
    <nav className="pagination-controls inventory-pagination" aria-label={label ?? t("pageLabel")}>
      <button className="small-button" type="button" aria-disabled={previousDisabled}
        onClick={() => { if (!previousDisabled) onPage(page - 1); }}>{t("previousPage")}</button>
      <span>{context && `${context} · `}{t("pageLabel")} {page + 1}{data && ` / ${pages} | ${count(data.total)}`}</span>
      <button className="small-button" type="button" aria-disabled={nextDisabled}
        onClick={() => { if (!nextDisabled) onPage(page + 1); }}>{t("nextPage")}</button>
    </nav>
  );
}

function tableMessage(state: { data: InventoryPage<unknown> | null; error: string | null }, empty: string, error: string) {
  if (state.error) return error;
  if (state.data?.rows.length === 0) return empty;
  return null;
}

function useInventoryCount() {
  const { language } = useI18n();
  return new Intl.NumberFormat(localeFor(language)).format;
}

function UtcTime({ value }: { value: string }) {
  const { language } = useI18n();
  return <time dateTime={value}>{formatUtcDateTime(value, localeFor(language), true)}</time>;
}

function ProductStatus({ active }: { active: boolean }) {
  const l = useInventoryLabels();
  return <StatusPill status={l(active ? "active" : "inactive")} tone={active ? "ok" : "muted"} />;
}
