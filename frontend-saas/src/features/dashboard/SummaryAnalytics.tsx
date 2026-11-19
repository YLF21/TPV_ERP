import { useState, type FormEvent } from "react";
import { useRemote } from "../../app/RefreshContext";
import { localeFor, useI18n } from "../../i18n";
import { request } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import { Metric } from "../../shared/ui";
import { DateRangePicker } from "../../shared/DateRangePicker";
import { LoadState } from "../../shared/workspace-ui";
import { formatDate, formatPickerDate } from "../../shared/lib";
import { AnalyticalChart } from "../../shared/analytics/AnalyticalChart";
import { FilterDisclosure } from "../../shared/analytics/FilterDisclosure";
import { SPANISH_PROVINCES } from "../../shared/provinces/spanish-provinces.mjs";
import { FilterActions, PeriodShortcuts } from "../reports/PeriodShortcuts";
import { formatDocumentAmount } from "./format";
import { useSummaryLabels } from "./labels";
import type { AdminSummary } from "./types";
import type { PeriodMetrics, StatisticsOptions } from "../reports/statistics-types";
import "./summary-analytics.css";

export type SummaryFilters = { from: string; to: string; companyId: string; storeId: string; province: string };
const day = 86400000;
export function initialSummaryFilters(): SummaryFilters {
  const now = new Date();
  return { from: new Date(now.getTime() - 29 * day).toISOString().slice(0, 10), to: now.toISOString().slice(0, 10), companyId: "", storeId: "", province: "" };
}
export function summaryQuery(filters: SummaryFilters) {
  const result = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => { if (value) result.set(key, value); });
  return result.toString();
}

function validSummaryRange({ from, to }: SummaryFilters) {
  const start = Date.parse(from + "T00:00:00Z"), end = Date.parse(to + "T00:00:00Z");
  return Number.isFinite(start) && end >= start && end - start < 366 * day;
}

export function restoreSummaryFilters(saved: Record<string, string>): SummaryFilters {
  const defaults = initialSummaryFilters();
  const { from = defaults.from, to = defaults.to, companyId = "", storeId = "", province = "" } = saved;
  const restored = { from, to, companyId, storeId, province };
  return validSummaryRange(restored) ? restored : defaults;
}

export function SummaryFilterPanel({ credentials, filters, onApply }: { credentials: Credentials; filters: SummaryFilters; onApply: (filters: SummaryFilters) => void }) {
  const { t, language } = useI18n();
  const labels = useSummaryLabels();
  const l = (es: string, en: string, zh: string) => language === "zh" ? zh : language === "en" ? en : es;
  const options = useRemote(() => request<StatisticsOptions>(credentials, "/api/v1/admin/summary/options"), [credentials.accessToken]);
  const [draft, setDraft] = useState(filters);
  const [invalid, setInvalid] = useState(false);
  function apply(next: SummaryFilters) {
    if (!validSummaryRange(next)) { setInvalid(true); return; }
    setInvalid(false); setDraft(next); onApply(next);
  }
  function submit(event: FormEvent) { event.preventDefault(); apply(draft); }
  function updateDraft(change: Partial<SummaryFilters>) {
    setDraft(current => ({ ...current, ...change }));
    setInvalid(false);
  }
  const companies = options.data?.companies ?? [], stores = options.data?.stores ?? [];
  const appliedScopes = (["companyId", "storeId", "province"] as const).filter(key => filters[key]);
  const dirty = summaryQuery(draft) !== summaryQuery(filters);
  return <section className="saas-summary-panel saas-summary-filters">
    <header className="saas-summary-heading"><h2>{l("Ámbito y período", "Scope and period", "范围与期间")}</h2></header>
    <div className="saas-summary-panel-body">
      <FilterDisclosure filters={filters} options={options.data} dirty={dirty}>
        <form id="summary-filters" className="summary-filter-grid" onSubmit={submit}>
          <div className="summary-filter-wide"><PeriodShortcuts from={draft.from} to={draft.to} onChange={updateDraft} /></div>
          <DateRangePicker fromLabel={l("Desde", "From", "开始日期")} toLabel={l("Hasta", "To", "结束日期")}
            from={draft.from} to={draft.to} onChange={updateDraft} />
          <label>{t("company")}<select aria-label={t("company")} className="control-input" value={draft.companyId} onChange={event => updateDraft({ companyId: event.target.value, storeId: "" })}><option value="">{l("Todas", "All", "全部")}</option>{companies.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <label>{t("store")}<select aria-label={t("store")} className="control-input" value={draft.storeId} onChange={event => updateDraft({ storeId: event.target.value })}><option value="">{l("Todas", "All", "全部")}</option>{stores.filter(item => !draft.companyId || item.companyId === draft.companyId).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <label>{labels("province")}<select aria-label={labels("province")} className="control-input" value={draft.province} onChange={event => updateDraft({ province: event.target.value })}><option value="">{l("Todas", "All", "全部")}</option><option value="UNKNOWN">{l("Sin provincia asignada", "No assigned province", "未分配省份")}</option>{SPANISH_PROVINCES.map(item => <option key={item.code} value={item.code}>{item.name}</option>)}</select></label>
        </form>
        {invalid && <p role="alert">{l("Selecciona un intervalo válido de hasta 366 días.", "Select a valid range of up to 366 days.", "请选择不超过366天的有效日期范围。")}</p>}
      </FilterDisclosure>
      <div className="summary-filter-actions">
        <button type="submit" className="primary-button" form="summary-filters">{l("Aplicar filtros", "Apply filters", "应用筛选")}</button>
        <FilterActions dirty={dirty} onReset={() => apply(initialSummaryFilters())} />
      </div>
      {appliedScopes.length > 0 && <div className="analytics-filter-tags">{appliedScopes.map(key => <button type="button" className="small-button" key={key} onClick={() => apply({ ...filters, [key]: "", ...(key === "companyId" ? { storeId: "" } : {}) })}>
        {key === "companyId" ? companies.find(item => item.id === filters[key])?.name ?? t("company") : key === "storeId" ? stores.find(item => item.id === filters[key])?.name ?? t("store") : SPANISH_PROVINCES.find(item => item.code === filters[key])?.name ?? l("Sin provincia", "No province", "未分配省份")} ×
      </button>)}</div>}
      <LoadState {...options} />
    </div>
  </section>;
}

export type SummaryReportLinks = Record<"sales" | "averages" | "sync" | "inventory", string>;
type SummaryAnalyticsProps = { summary: AdminSummary; links?: SummaryReportLinks };

export function SummaryMetric({ href, label, value, detail }: { href?: string; label: string; value: string; detail?: string }) {
  if (!href) return <Metric label={label} value={value} detail={detail} />;

  return (
    <a className="metric saas-summary-metric-link" href={href}>
      <span>{label}</span>
      <strong>{value}</strong>
      {detail && <small>{detail}</small>}
    </a>
  );
}

function missingSalesLabel(activity: PeriodMetrics | undefined) {
  return activity?.documents === 0 ? "noSalesActivity" : "noPeriodSales";
}

export function SummaryActivity({ summary, links }: SummaryAnalyticsProps) {
  const { language } = useI18n();
  const l = useSummaryLabels();
  const locale = localeFor(language);
  const count = new Intl.NumberFormat(locale).format;
  const { period, periodActivity: activity } = summary;
  const currencies = activity?.currencies ?? [];
  const percentage = activity && activity.syncEvents > 0
    ? `${new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(100 * activity.syncErrors / activity.syncEvents)} %`
    : null;
  const syncDetail = activity == null ? l("noData")
    : activity.syncEvents === 0 ? l("noSyncActivity")
    : [l("syncErrorCount").replace("{count}", count(activity.syncErrors)), percentage].filter(Boolean).join(" · ");
  const money = (value: string | null, currency: string) =>
    value == null ? `${currency} —` : `${currency} ${formatDocumentAmount(value, locale)}`;

  return (
    <section className="saas-summary-panel saas-summary-activity" aria-labelledby="summary-period-heading">
      <header className="saas-summary-heading">
        <h2 id="summary-period-heading">{l("periodActivity")}</h2>
        {period && <p className="saas-summary-timestamp"><time dateTime={period.from}>{formatPickerDate(period.from, true)}</time> – <time dateTime={period.to}>{formatPickerDate(period.to, true)}</time> (UTC)</p>}
      </header>
      <div className="saas-summary-activity-body">
        <div className="saas-summary-currencies">
          {currencies.map(item => (
            <div className="saas-summary-metrics saas-summary-currency" key={item.currency}>
              <SummaryMetric href={links?.sales} label={l("periodSales")} value={money(item.amount, item.currency)}
                detail={item.amount == null || item.documents == null ? l("noPeriodSales")
                  : item.documents === 1 ? l("oneEligibleDocument")
                  : l("eligibleDocumentCount").replace("{count}", count(item.documents))} />
              <SummaryMetric href={links?.averages} label={l("periodAverage")} value={money(item.averageAmount, item.currency)}
                detail={item.averageAmount == null ? l("noAverage") : undefined} />
            </div>
          ))}
          {currencies.length === 0 && <div className="saas-summary-metrics saas-summary-currency">
            <SummaryMetric href={links?.sales} label={l("periodSales")} value="—" detail={l(missingSalesLabel(activity))} />
            <SummaryMetric href={links?.averages} label={l("periodAverage")} value="—" detail={l(missingSalesLabel(activity))} />
          </div>}
        </div>
        <div className="saas-summary-metrics saas-summary-sync-metric">
          <SummaryMetric href={links?.sync} label={l("receivedSyncs")} value={activity ? count(activity.syncEvents) : "—"} detail={syncDetail} />
        </div>
      </div>
    </section>
  );
}

export function SummarySalesChart({ summary, links }: SummaryAnalyticsProps) {
  const { language } = useI18n();
  const l = useSummaryLabels();
  const locale = localeFor(language);
  const daily = summary.daily ?? [];
  const currencies = [...new Set(daily.flatMap(item => item.currencies
    .filter(value => value.amount != null).map(value => value.currency)))];

  return (
    <section className={currencies.length ? "saas-summary-panel saas-summary-sales" : "saas-summary-panel saas-summary-sales is-empty"} aria-labelledby="summary-sales-heading">
      <header className="saas-summary-heading">
        <h2 id="summary-sales-heading">{l("salesTrend")}</h2>
        {links && <a className="small-button" href={links.sales}>{l("viewSales")}</a>}
      </header>
      {currencies.length === 0 && <p className="saas-summary-empty-chart">{l(missingSalesLabel(summary.periodActivity))}</p>}
      {currencies.map(currency => (
        <AnalyticalChart key={currency} title={`${l("netSales")} · ${currency}`}
          series={[{ name: currency, values: daily.map(item => {
            const amount = item.currencies.find(value => value.currency === currency)?.amount;
            return {
              label: item.date,
              value: amount == null ? null : Number(amount),
              valueLabel: amount == null ? undefined : formatDocumentAmount(amount, locale),
            };
          }) }]} />
      ))}
    </section>
  );
}

export function SummaryAnalyticsDetails({ summary, links }: SummaryAnalyticsProps) {
  const { language } = useI18n();
  const l = (es: string, en: string, zh: string) => language === "zh" ? zh : language === "en" ? en : es;
  const labels = useSummaryLabels();
  const locale = localeFor(language);
  const numberFormat = new Intl.NumberFormat(locale);
  const count = (value: number | null | undefined) => value == null ? "—" : numberFormat.format(value);
  const activity = summary.periodActivity, catalogue = summary.catalogue;
  const daily = summary.daily ?? [];
  const syncTrend = l("Evolución de sincronización", "Synchronization trend", "同步趋势");
  return <>
    <section className="saas-summary-panel">
      <header className="saas-summary-heading"><h2>{labels("periodDetails")}</h2></header>
      <div className="saas-summary-metrics">
        <Metric label={l("Empresas nuevas", "New companies", "新企业")} value={count(summary.newCompanies)} />
        <Metric label={l("Tiendas nuevas", "New stores", "新门店")} value={count(summary.newStores)} />
        <Metric label={l("Licencias nuevas", "New licenses", "新许可证")} value={count(summary.newLicenses)} />
        <Metric label={l("Documentos de venta", "Sales documents", "销售单据")} value={count(activity?.saleDocuments)} />
        <Metric label={l("Documentos de devolución", "Return documents", "退货单据")} value={count(activity?.returnDocuments)} />
      </div>
      <div className="saas-summary-panel-body">
        <div className="table-wrap saas-summary-table"><table><caption>{l("Importes y promedios del período", "Period amounts and averages", "期间金额与平均值")}</caption><thead><tr>{[l("Moneda", "Currency", "币种"), l("Documentos computables", "Eligible documents", "可统计单据"), l("Importe", "Amount", "金额"), l("Importe medio por documento", "Amount per document", "单据平均金额"), l("Importe por día", "Amount per day", "每日金额")].map(label => <th key={label}>{label}</th>)}</tr></thead><tbody>
          {activity?.currencies.map(item => <tr key={item.currency}><th scope="row">{item.currency}</th><td className="saas-summary-number">{count(item.documents)}</td>{[item.amount, item.averageAmount, item.amountPerDay].map((value, index) => <td key={index} className="saas-summary-number">{value == null ? "—" : formatDocumentAmount(value, locale)}</td>)}</tr>)}
          {!activity?.currencies.length && <tr><td colSpan={5}>{labels(missingSalesLabel(activity))}</td></tr>}
        </tbody></table></div>
        <p className="saas-summary-note">{l("Ventas por fecha comercial original. Sincronizaciones y nuevas altas por UTC. Promedios por documento computable y por día del intervalo.", "Sales use their original business date. Synchronization and registrations use UTC. Averages use eligible documents and calendar days in the range.", "销售按原始营业日期统计，同步与新登记按UTC统计。平均值以可统计单据数和期间日历天数计算。")}</p>
      </div>
    </section>
    <section className="saas-summary-panel"><header className="saas-summary-heading"><h2>{l("Catálogo recibido · última captura por tienda", "Received catalogue · latest snapshot per store", "已接收目录 · 各门店最新快照")}</h2>{links && <a className="small-button" href={links.inventory}>{l("Consultar catálogo y existencias", "View catalogue and stock", "查看目录与库存")}</a>}</header>
      <div className="saas-summary-metrics"><Metric label={l("Artículos activos", "Active products", "启用商品")} value={count(catalogue?.activeProducts)} detail={catalogue?.activeProducts == null ? labels(catalogue ? "noCatalogueCapture" : "noData") : undefined} /><Metric label={l("Tiendas con catálogo", "Stores with catalogue", "已有目录的门店")} value={catalogue ? `${count(catalogue.coveredStores)} / ${count(catalogue.stores)}` : "—"} /></div>
      <div className="saas-summary-panel-body"><p className="saas-summary-note">{l("Recuentos por tienda a partir de capturas completas de la instalación actual. Las tiendas sin captura no se consideran vacías.", "Counts per store use complete snapshots from the current installation. Stores without a snapshot are not treated as empty.", "按当前安装的完整快照统计各门店。没有快照的门店不视为空库存。")}</p>
        {catalogue?.oldestCapturedAt && catalogue.latestCapturedAt && <p className="saas-summary-note">{l("Fechas de las capturas disponibles", "Available snapshot dates", "可用快照日期")}: <time dateTime={catalogue.oldestCapturedAt}>{formatDate(catalogue.oldestCapturedAt)}</time> – <time dateTime={catalogue.latestCapturedAt}>{formatDate(catalogue.latestCapturedAt)}</time></p>}
      </div>
    </section>
    {daily.some(item => item.syncEvents > 0) ? <AnalyticalChart title={syncTrend} series={[{ name: l("Eventos", "Events", "事件"), values: daily.map(item => ({ label: item.date, value: item.syncEvents })) }]} />
      : <section className="saas-summary-panel">
        <header className="saas-summary-heading"><h2>{syncTrend}</h2></header>
        <p className="saas-summary-empty-chart">{labels(activity?.syncEvents === 0 ? "noSyncActivity" : "noData")}</p>
      </section>}
  </>;
}
