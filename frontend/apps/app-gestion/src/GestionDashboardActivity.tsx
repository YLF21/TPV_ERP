import { type ReactNode } from "react";
import type { LocaleCode } from "@tpverp/app-common";
import { DashboardBars } from "./DashboardBars";
import type { ReceivablesSummary, SalesOverviewData } from "./dashboardModel";
import { formatDashboardDate, formatDashboardMoney, formatDashboardNumber, type DashboardDataState, type DashboardTranslator } from "./GestionDashboardWidgets";

type Props<T> = { state: DashboardDataState<T>; t: DashboardTranslator; locale: LocaleCode; display: "BAR" | "TABLE" };
function Content<T>({ state, t, children }: { state: DashboardDataState<T>; t: DashboardTranslator; children: ReactNode }) {
  if (!state.data) return <p className="gd-widget-message" role="status">{t(state.error ? "gestion.widget.loadError" : "common.loading")}</p>;
  return <div className="gd-analysis" aria-busy={state.loading}>{children}
    {state.error && <p className="gd-widget-message error" role="status">{t("gestion.dashboard.staleData")}</p>}</div>;
}
export function CorrectionsWidget({ state, t, locale, display }: Props<SalesOverviewData>) {
  const rows = state.data?.corrections ?? [];
  const money = (value: number) => formatDashboardMoney(value, locale, state.data?.currency);
  return <Content state={state} t={t}><p className="gd-widget-note">{t("gestion.dashboard.correctionsScope")}</p>
    {!rows.length ? <p className="gd-widget-message">{t("gestion.dashboard.noData")}</p>
      : display === "BAR" ? <DashboardBars rows={rows.map(row => ({ key: row.kind, label: t(`gestion.dashboard.correction.${row.kind}`), value: row.amount }))} format={money} currentLabel={t("gestion.dashboard.amount")} />
      : <div className="gd-table-wrap"><table className="gd-table"><thead><tr><th>{t("gestion.dashboard.type")}</th><th className="numeric">{t("gestion.widget.sales.operations")}</th><th className="numeric">{t("gestion.dashboard.amount")}</th></tr></thead>
        <tbody>{rows.map(row => <tr key={row.kind}><td>{t(`gestion.dashboard.correction.${row.kind}`)}</td><td className="numeric">{formatDashboardNumber(row.operations, locale)}</td><td className="numeric">{money(row.amount)}</td></tr>)}</tbody>
        <tfoot><tr><th>{t("gestion.dashboard.total")}</th><td className="numeric">{formatDashboardNumber(rows.reduce((sum, row) => sum + row.operations, 0), locale)}</td><td className="numeric">{money(rows.reduce((sum, row) => sum + row.amount, 0))}</td></tr></tfoot>
      </table></div>}
  </Content>;
}

export function PaymentsWidget({ state, t, locale, display }: Props<SalesOverviewData>) {
  const rows = state.data?.payments ?? [];
  const money = (value: number) => formatDashboardMoney(value, locale, state.data?.currency);
  const method = (value: string) => ["EFECTIVO", "TARJETA", "TRANSFERENCIA", "VALE", "SALDO_MIEMBRO"].includes(value) ? t(`gestion.dashboard.method.${value}`) : value;
  return <Content state={state} t={t}><p className="gd-widget-note">{t("gestion.dashboard.paymentsScope")}</p>
    {!rows.length ? <p className="gd-widget-message">{t("gestion.dashboard.noData")}</p>
      : display === "BAR" ? <DashboardBars rows={rows.map(row => ({ key: row.method, label: method(row.method), value: row.net }))} format={money} currentLabel={t("gestion.dashboard.netCollected")} />
      : <div className="gd-table-wrap"><table className="gd-table"><thead><tr><th>{t("gestion.dashboard.paymentMethod")}</th><th className="numeric">{t("gestion.dashboard.collected")}</th><th className="numeric">{t("gestion.dashboard.refunded")}</th><th className="numeric">{t("gestion.dashboard.netCollected")}</th></tr></thead>
        <tbody>{rows.map(row => <tr key={row.method}><td>{method(row.method)}</td><td className="numeric">{money(row.collected)}</td><td className="numeric">{money(row.refunded)}</td><td className="numeric">{money(row.net)}</td></tr>)}</tbody>
        <tfoot><tr><th>{t("gestion.dashboard.total")}</th>{(["collected", "refunded", "net"] as const).map(key => <td className="numeric" key={key}>{money(rows.reduce((sum, row) => sum + row[key], 0))}</td>)}</tr></tfoot>
      </table></div>}
  </Content>;
}

export function ReceivablesWidget({ state, t, locale, display }: Props<ReceivablesSummary>) {
  const rows = state.data?.balances ?? [];
  const money = (value: number) => formatDashboardMoney(value, locale, state.data?.currency);
  return <Content state={state} t={t}><p className="gd-widget-note">{t("gestion.dashboard.receivablesScope")} {state.data && formatDashboardDate(state.data.asOf, locale)}</p>
    <strong className="gd-balance-total">{money(rows.reduce((sum, row) => sum + row.amount, 0))}</strong>
    {!rows.length ? <p className="gd-widget-message">{t("gestion.dashboard.noData")}</p>
      : display === "BAR" ? <DashboardBars rows={rows.map(row => ({ key: row.kind, label: t(`gestion.dashboard.balance.${row.kind}`), value: row.amount }))} format={money} currentLabel={t("gestion.widget.finance.receivables")} />
      : <div className="gd-table-wrap"><table className="gd-table"><thead><tr><th>{t("gestion.dashboard.status")}</th><th className="numeric">{t("gestion.dashboard.documents")}</th><th className="numeric">{t("gestion.dashboard.amount")}</th></tr></thead>
        <tbody>{rows.map(row => <tr key={row.kind}><td>{t(`gestion.dashboard.balance.${row.kind}`)}</td><td className="numeric">{formatDashboardNumber(row.documents, locale)}</td><td className="numeric">{money(row.amount)}</td></tr>)}</tbody>
      </table></div>}
  </Content>;
}
