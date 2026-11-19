import { useRemote } from "../../app/RefreshContext";
import { localeFor, useI18n } from "../../i18n";
import { request } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import { ReportTools, useReportPreferences } from "../../shared/analytics/ReportTools";
import { LoadState } from "../../shared/workspace-ui";
import { ReportTable, type ReportColumn } from "./ReportTable";
import { useInventoryLabels } from "./inventory-labels";
import type { InventoryTotals } from "./inventory-types";
import { missing, provinces } from "./report-query";
import { useStatisticsLabels } from "./statistics-labels";
import type { StatisticsFilters } from "./statistics-types";

type ProvinceCoverage = { province: string; totals: InventoryTotals };

export function InventoryProvinceCoverage({ credentials, filters, back }: {
  credentials: Credentials;
  filters: StatisticsFilters;
  back?: string;
}) {
  const l = useInventoryLabels();
  const s = useStatisticsLabels();
  const { language } = useI18n();
  const count = new Intl.NumberFormat(localeFor(language)).format;
  const preferences = useReportPreferences(credentials, "inventory-provinces");
  const query = new URLSearchParams();
  for (const key of ["companyId", "storeId", "province"] as const) if (filters[key]) query.set(key, filters[key]);
  const path = "/api/v1/admin/reports/inventory/provinces?" + query;
  const coverage = useRemote(() => request<ProvinceCoverage[]>(credentials, path), [credentials.accessToken, path]);
  const columns: ReportColumn<ProvinceCoverage>[] = [
    { id: "province", label: s("PROVINCE"), required: true, sticky: true, rowHeader: true, cell: (row) => provinces.get(row.province) ?? s("unknownProvince") },
    { id: "stores", label: l("reportingStores"), numeric: true, cell: ({ totals }) => `${count(totals.reportedStores)} / ${count(totals.stores)}` },
    { id: "pending", label: l("pendingStores"), numeric: true, cell: ({ totals }) => count(totals.stores - totals.reportedStores) },
    { id: "products", label: l("products"), numeric: true, cell: ({ totals }) => totals.reportedStores ? count(totals.products) : missing },
    { id: "warehouses", label: l("warehouses"), numeric: true, cell: ({ totals }) => totals.reportedStores ? count(totals.warehouses) : missing },
    { id: "balances", label: l("balances"), numeric: true, cell: ({ totals }) => totals.reportedStores ? count(totals.balances) : missing },
    { id: "storesLink", label: "", required: true, sticky: true, cell: (row) => {
      const target = new URLSearchParams(query);
      target.set("province", row.province);
      target.set("overview", "1");
      if (back) target.set("return", back);
      return <a className="small-button report-link" href={"#/reports/inventory?" + target}
        aria-label={l("viewStores") + " · " + (provinces.get(row.province) ?? s("unknownProvince"))}>{l("viewStores")}</a>;
    } },
  ];

  return <section className="saas-summary-panel" aria-labelledby="inventory-province-heading" aria-busy={coverage.loading}>
    <header className="saas-summary-heading"><h2 id="inventory-province-heading">{l("provinceCoverage")}</h2></header>
    <div className="saas-summary-panel-body">
      <p className="inventory-note">{l("scope")}</p>
      <ReportTools credentials={credentials} preferences={preferences} columns={columns.filter((column) => column.label)}
        exportPath={coverage.data ? "/api/v1/admin/reports/export/inventory?kind=provinces&" + query : undefined} />
      <LoadState {...coverage} />
      <ReportTable title={l("provinceCoverage")} columns={columns} rows={coverage.data ?? []} rowKey={(row) => row.province}
        visible={preferences.isVisible}
        empty={coverage.error ? l("unavailable") : coverage.loading ? missing : l("noStores")} />
    </div>
  </section>;
}
