import { DashboardBars } from "./DashboardBars";
import type { FamilySales, SalesOverviewData } from "./dashboardModel";
import { formatDashboardMoney, formatDashboardNumber, formatDashboardRange, type DashboardDataState, type DashboardTranslator } from "./GestionDashboardWidgets";
import type { LocaleCode } from "@tpverp/app-common";

export function familyLabel(row: FamilySales, t: DashboardTranslator) {
  return row.key === "ADJUSTMENTS" ? t("gestion.dashboard.unassignedAdjustments")
    : row.name || t("gestion.dashboard.unclassified");
}

export function FamilySalesWidget({ state, t, locale, display, comparison }: {
  state: DashboardDataState<SalesOverviewData>; t: DashboardTranslator; locale: LocaleCode;
  display: "BAR" | "TABLE"; comparison: boolean;
}) {
  const data = state.data;
  if (!data) return <p className="gd-widget-message">{t(state.error ? "gestion.widget.loadError" : "common.loading")}</p>;
  const rows = data.families ?? [];
  const money = (value: number) => formatDashboardMoney(value, locale, data.currency);
  const percent = (value: number) => `${formatDashboardNumber(value, locale, 1)} %`;
  const families = rows.filter(row => row.key !== "ADJUSTMENTS");
  const visible = families.slice(0, 8);
  const remaining = families.slice(8);
  const chart = visible.map(row => ({ key: row.key, label: familyLabel(row, t), value: row.currentSales, previous: comparison ? row.previousSales : undefined }));
  if (remaining.length) chart.push({ key: "OTHERS", label: t("gestion.dashboard.others"),
    value: remaining.reduce((sum, row) => sum + row.currentSales, 0), previous: comparison ? remaining.reduce((sum, row) => sum + row.previousSales, 0) : undefined });
  const adjustment = rows.find(row => row.key === "ADJUSTMENTS");
  if (adjustment) chart.push({ key: adjustment.key, label: familyLabel(adjustment, t), value: adjustment.currentSales, previous: comparison ? adjustment.previousSales : undefined });
  return <div className="gd-analysis" aria-busy={state.loading}>
    <p className="gd-widget-note" title={t("gestion.dashboard.familyDetail")}>{t("gestion.dashboard.currentFamilies")}</p>
    {!rows.length ? <p className="gd-widget-message">{t("gestion.dashboard.noData")}</p>
      : display === "BAR" ? <DashboardBars rows={chart} format={money}
        currentLabel={formatDashboardRange(data.from, data.to, locale)}
        previousLabel={comparison ? formatDashboardRange(data.previousFrom, data.previousTo, locale) : undefined} />
      : <div className="gd-table-wrap"><table className="gd-table gd-family-table">
        <thead><tr><th>{t("gestion.dashboard.family")}</th><th className="numeric">{t("gestion.widget.sales.today")}</th>
          {comparison && <><th className="numeric">{t("gestion.dashboard.previousSales")}</th><th className="numeric">{t("gestion.dashboard.variation")}</th></>}
          <th className="numeric">{t("gestion.dashboard.netUnits")}</th><th className="numeric">{t("gestion.dashboard.share")}</th></tr></thead>
        <tbody>{rows.map(row => <tr key={row.key} className={row.key === "ADJUSTMENTS" ? "gd-adjustment-row" : undefined}>
          <td>{familyLabel(row, t)}</td><td className="numeric">{money(row.currentSales)}</td>
          {comparison && <><td className="numeric">{money(row.previousSales)}</td><td className="numeric">{row.previousSales === 0 ? "—" : percent((row.currentSales - row.previousSales) / Math.abs(row.previousSales) * 100)}</td></>}
          <td className="numeric">{row.key === "ADJUSTMENTS" ? "—" : formatDashboardNumber(row.currentUnits, locale)}</td>
          <td className="numeric">{data.current.netSales > 0 ? percent(row.currentSales / data.current.netSales * 100) : "—"}</td>
        </tr>)}</tbody><tfoot><tr><th>{t("gestion.dashboard.total")}</th><td className="numeric">{money(data.current.netSales)}</td>
          {comparison && <><td className="numeric">{money(data.previous.netSales)}</td><td className="numeric">{data.previous.netSales === 0 ? "—" : percent((data.current.netSales - data.previous.netSales) / Math.abs(data.previous.netSales) * 100)}</td></>}
          <td className="numeric">{data.current.netUnits == null ? "—" : formatDashboardNumber(data.current.netUnits, locale)}</td><td className="numeric">{data.current.netSales > 0 ? percent(100) : "—"}</td></tr></tfoot>
      </table></div>}
    {state.error && <p className="gd-widget-message error" role="status">{t("gestion.dashboard.staleData")}</p>}
  </div>;
}
