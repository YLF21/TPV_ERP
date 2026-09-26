import { useEffect, useId, useRef, useState, type CSSProperties, type ReactNode } from "react";
import { ArrowDownRight, ArrowRight, ArrowUpRight, Bell, DotsSixVertical, Info, ArrowsOutSimple, ArrowsInSimple, X } from "@phosphor-icons/react";
import { DashboardBars } from "./DashboardBars";
import type { LocaleCode } from "@tpverp/app-common";
import type { ActivePromotionData, ControlAlertsSummaryData, DashboardOptions, DashboardWidgetLayout, SalesOverviewData } from "./dashboardModel";

export type DashboardTranslator = (key: string) => string;
export type DashboardDataState<T> = { loading: boolean; error: boolean; data?: T; updatedAt?: number };
type WidgetProps<T> = { state: DashboardDataState<T>; t: DashboardTranslator; locale: LocaleCode; onOpen: () => void };

export function DashboardWidgetFrame({ widget, customizing, selected, disabled, t, onSelect, onDragStart, onDrop, onRemove, children, toolbar }: {
  widget: DashboardWidgetLayout; customizing: boolean; selected: boolean; disabled?: boolean; t: DashboardTranslator;
  onSelect: () => void; onDragStart: () => void; onDrop: () => void; onRemove: () => void; children: ReactNode; toolbar?: ReactNode;
}) {
  const [expanded, setExpanded] = useState(false);
  const frameRef = useRef<HTMLElement>(null);
  useEffect(() => {
    if (expanded && !customizing) frameRef.current?.scrollIntoView?.({ block: "start", behavior: "instant" });
  }, [expanded, customizing]);
  const kpi = ["sales.today", "sales.operations", "sales.average", "sales.units"].includes(widget.key);
  return <article ref={frameRef} className={`gd-widget ${kpi ? "gd-kpi-widget" : ""} ${expanded && !customizing ? "is-expanded" : ""} ${customizing && selected ? "is-selected" : ""}`}
    data-widget-key={widget.key} style={{ "--widget-width": widget.width, "--widget-height": widget.height } as CSSProperties}
    onDragOver={(event) => { if (customizing && !disabled) event.preventDefault(); }} onDrop={onDrop}>
    <header className="gd-widget-header">
      {customizing ? <button type="button" className="gd-widget-select" aria-pressed={selected} disabled={disabled}
        onClick={onSelect} draggable={!disabled} onDragStart={onDragStart} title={t("gestion.dashboard.selectWidget")}>
        <DotsSixVertical size={17} aria-hidden="true" /><strong>{t(`gestion.widget.${widget.key}`)}</strong>
      </button> : <strong>{t(`gestion.widget.${widget.key}`)}</strong>}
      {!customizing && !kpi && <button type="button" className="gd-icon-button" aria-expanded={expanded}
        aria-label={`${t(expanded ? "gestion.dashboard.collapse" : "gestion.dashboard.expand")} ${t(`gestion.widget.${widget.key}`)}`}
        onClick={() => setExpanded(value => !value)}>{expanded ? <ArrowsInSimple size={16} /> : <ArrowsOutSimple size={16} />}</button>}
      {customizing && <button type="button" className="gd-icon-button" disabled={disabled} onClick={onRemove}
        aria-label={`${t("gestion.dashboard.remove")} ${t(`gestion.widget.${widget.key}`)}`}><X size={15} aria-hidden="true" /></button>}
    </header>
    {toolbar && <div className="gd-widget-toolbar">{toolbar}</div>}
    <div className="gd-widget-content">{children}</div>
  </article>;
}

export function SalesMetricWidget({ state, t, locale, onOpen, metric, showComparison }: WidgetProps<SalesOverviewData> & {
  metric: "netSales" | "operationCount" | "averageAmount" | "netUnits"; showComparison: boolean;
}) {
  const descriptionId = useId();
  if (!state.data) return <WidgetMessage text={t(state.error ? "gestion.widget.loadError" : "common.loading")} error={state.error} />;
  const description = metric === "operationCount" ? t("gestion.dashboard.operationsDefinition")
    : metric === "averageAmount" ? t("gestion.dashboard.averageDefinition") : undefined;
  const { current, previous, currency } = state.data;
  const value = current[metric];
  const previousValue = previous[metric];
  const noAverage = metric === "averageAmount" && current.operationCount === 0;
  const noPreviousAverage = metric === "averageAmount" && previous.operationCount === 0;
  const delta = value == null || previousValue == null || previousValue === 0 || noAverage || noPreviousAverage ? null : (value - previousValue) / Math.abs(previousValue) * 100;
  const negative = delta != null && delta < 0;
  return <div className="gd-metric" aria-busy={state.loading}>
    <button type="button" className="gd-metric-value" onClick={onOpen}
      title={description ? `${description}\n${t("gestion.widget.openSales")}` : t("gestion.widget.openSales")}
      aria-describedby={description ? descriptionId : undefined}>
      {noAverage || value == null ? "—" : metric === "operationCount" || metric === "netUnits" ? formatDashboardNumber(value, locale, metric === "operationCount" ? 0 : 3) : formatDashboardMoney(value, locale, currency)}
    </button>
    {description && <span id={descriptionId} className="gd-visually-hidden">{description}</span>}
    {showComparison && <div className="gd-metric-comparison">
      <span className={`gd-change ${delta == null ? "neutral" : negative ? "negative" : "positive"}`}>
        {delta != null && (negative ? <ArrowDownRight size={14} aria-hidden="true" /> : <ArrowUpRight size={14} aria-hidden="true" />)}
        {delta == null ? "—" : `${delta > 0 ? "+" : ""}${formatDashboardNumber(delta, locale, 1)} %`}
      </span><span title={formatDashboardRange(state.data.previousFrom, state.data.previousTo, locale)}>{t(delta == null ? "gestion.widget.noComparison" : "gestion.dashboard.vsPrevious")}
        {previousValue != null && !noAverage && !noPreviousAverage && <> · {metric === "operationCount" || metric === "netUnits" ? formatDashboardNumber(previousValue, locale) : formatDashboardMoney(previousValue, locale, currency)}</>}</span>
    </div>}
    {state.error && <WidgetMessage text={t("gestion.dashboard.staleData")} error />}
  </div>;
}

export function SalesTrendWidget({ state, t, locale, options }: Omit<WidgetProps<SalesOverviewData>, "onOpen"> & { options: DashboardOptions }) {
  if (!state.data) return <WidgetMessage text={t(state.error ? "gestion.widget.loadError" : "common.loading")} error={state.error} />;
  const data = state.data;
  return <div className="gd-trend" aria-busy={state.loading}>
    <div className="gd-chart-legend">
      <span><i className="current" />{formatDashboardRange(data.from, data.to, locale)}</span>
      {options.showComparison && <span><i className="previous" />{formatDashboardRange(data.previousFrom, data.previousTo, locale)}</span>}
    </div>
    {options.trendDisplay === "TABLE" ? <SalesTrendTable data={data} locale={locale} t={t} comparison={options.showComparison} /> : <>
      <SalesTrendChart data={data} locale={locale} t={t} bars={options.trendDisplay === "BAR"} comparison={options.showComparison} />
      <details className="gd-chart-data"><summary>{t("gestion.dashboard.chartData")}</summary>
        <SalesTrendTable data={data} locale={locale} t={t} comparison={options.showComparison} />
      </details>
    </>}
    {state.error && <WidgetMessage text={t("gestion.dashboard.staleData")} error />}
  </div>;
}

function SalesTrendChart({ data, locale, t, bars, comparison }: { data: SalesOverviewData; locale: LocaleCode; t: DashboardTranslator; bars: boolean; comparison: boolean }) {
  const svgRef = useRef<SVGSVGElement>(null);
  const [size, setSize] = useState({ width: 750, height: 210 });
  useEffect(() => {
    const element = svgRef.current;
    if (!element || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect;
      if (width > 0 && height > 0) setSize({ width, height });
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  const series = data.daily;
  const values = [...series.map((point) => point.netSales), ...(comparison ? data.previousDaily.map((point) => point.netSales) : [])];
  const min = Math.min(0, ...values);
  const max = Math.max(1, ...values);
  const extent = max - min || 1;
  const left = 65, right = size.width - 20, top = 16, bottom = size.height - 30;
  const count = Math.max(series.length, 1);
  const x = (index: number) => left + (count === 1 ? (right - left) / 2 : index / (count - 1) * (right - left));
  const y = (value: number) => bottom - (value - min) / extent * (bottom - top);
  const path = (points: typeof series) => points.map((point, index) => `${index ? "L" : "M"}${x(index)},${y(point.netSales)}`).join(" ");
  const width = Math.max(1, Math.min(18, (right - left) / count / (comparison ? 3 : 1.8)));
  const labelEvery = Math.max(1, Math.ceil(count / Math.max(2, Math.floor((right - left) / 65))));
  return <svg ref={svgRef} className="gd-chart" viewBox={`0 0 ${size.width} ${size.height}`} role="img" aria-label={`${t("gestion.widget.sales.trend")}. ${formatDashboardRange(data.from, data.to, locale)}`}>
    <title>{t("gestion.widget.sales.trend")}</title>
    <desc>{t("gestion.dashboard.chartDescription")}</desc>
    {[0, 1, 2, 3, 4].map((step) => {
      const value = min + extent * step / 4;
      return <g key={step}><line x1={left} x2={right} y1={y(value)} y2={y(value)} className="gd-chart-gridline" />
        <text x={left - 10} y={y(value) + 4} textAnchor="end">{new Intl.NumberFormat(dashboardLocale(locale), { maximumFractionDigits: extent < 10 ? 2 : 0 }).format(value)}</text></g>;
    })}
    <text x={left - 10} y={10} textAnchor="end">{data.currency}</text>
    {min < 0 && <line x1={left} x2={right} y1={y(0)} y2={y(0)} className="gd-chart-zero" />}
    {bars ? <>
      {comparison && data.previousDaily.map((point, index) => <rect key={point.date} className="gd-chart-bar previous" x={x(index) - width} y={Math.min(y(0), y(point.netSales))} width={width} height={Math.max(0.5, Math.abs(y(0) - y(point.netSales)))}>
        <title>{`${formatDashboardDate(point.date, locale)}: ${formatDashboardMoney(point.netSales, locale, data.currency)}`}</title></rect>)}
      {series.map((point, index) => <rect key={point.date} className="gd-chart-bar current" x={x(index) - (comparison ? 0 : width / 2)} y={Math.min(y(0), y(point.netSales))} width={width} height={Math.max(0.5, Math.abs(y(0) - y(point.netSales)))}>
        <title>{`${formatDashboardDate(point.date, locale)}: ${formatDashboardMoney(point.netSales, locale, data.currency)}`}</title></rect>)}
    </> : <>
      {comparison && <path d={path(data.previousDaily)} className="gd-chart-line previous" />}
      {comparison && data.previousDaily.length === 1 && <circle cx={x(0)} cy={y(data.previousDaily[0].netSales)} r={4.8}
        className="gd-chart-previous-dot" fill="#fff" stroke="#a8b5c0" strokeWidth={2}>
        <title>{`${formatDashboardDate(data.previousDaily[0].date, locale)}: ${formatDashboardMoney(data.previousDaily[0].netSales, locale, data.currency)}`}</title>
      </circle>}
      <path d={path(series)} className="gd-chart-line current" />
      {series.map((point, index) => <circle key={point.date} cx={x(index)} cy={y(point.netSales)} r={count > 60 ? 1.7 : 3.2} className="gd-chart-dot">
        <title>{`${formatDashboardDate(point.date, locale)}: ${formatDashboardMoney(point.netSales, locale, data.currency)}`}</title></circle>)}
    </>}
    {series.map((point, index) => index % labelEvery === 0 && (index === 0 || index < count - Math.max(1, labelEvery / 2)) || index === count - 1 ? <text key={point.date} x={x(index)} y={size.height - 8} textAnchor="middle">{formatDashboardDate(point.date, locale, true)}</text> : null)}
  </svg>;
}

function SalesTrendTable({ data, locale, t, comparison }: { data: SalesOverviewData; locale: LocaleCode; t: DashboardTranslator; comparison: boolean }) {
  return <div className="gd-table-wrap"><table className="gd-table"><caption className="gd-visually-hidden">{t("gestion.widget.sales.trend")}</caption>
    <thead><tr><th>{t("gestion.dashboard.date")}</th><th className="numeric">{t("gestion.widget.sales.today")}</th><th className="numeric">{t("gestion.widget.sales.operations")}</th>
      {comparison && <><th>{t("gestion.dashboard.previousDate")}</th><th className="numeric">{t("gestion.dashboard.previousSales")}</th></>}
    </tr></thead><tbody>{data.daily.map((point, index) => <tr key={point.date}>
      <td>{formatDashboardDate(point.date, locale)}</td><td className="numeric">{formatDashboardMoney(point.netSales, locale, data.currency)}</td><td className="numeric">{formatDashboardNumber(point.operationCount, locale, 0)}</td>
      {comparison && <><td>{data.previousDaily[index] ? formatDashboardDate(data.previousDaily[index].date, locale) : "—"}</td>
        <td className="numeric">{data.previousDaily[index] ? formatDashboardMoney(data.previousDaily[index].netSales, locale, data.currency) : "—"}</td></>}
    </tr>)}</tbody></table></div>;
}

export function TopProductsWidget({ state, t, locale, onOpen, display, sort = "QUANTITY" }: WidgetProps<SalesOverviewData> & { display: DashboardOptions["productDisplay"]; sort?: DashboardOptions["productSort"] }) {
  if (!state.data) return <WidgetMessage text={t(state.error ? "gestion.widget.loadError" : "common.loading")} error={state.error} />;
  const data = state.data;
  const rows = sort === "AMOUNT" ? data.topProductsByAmount ?? [] : data.topProducts;
  return <div className="gd-ranking" aria-busy={state.loading}>
    <p className="gd-widget-note">{t("gestion.dashboard.rankingScope")}</p>
    {rows.length === 0 ? <WidgetMessage text={t("gestion.dashboard.noData")} /> : display === "BAR" ? <DashboardBars
      rows={rows.map(row => ({ key: row.productId, label: row.name, value: sort === "AMOUNT" ? row.netAmount ?? 0 : row.netQuantity }))}
      format={value => sort === "AMOUNT" ? formatDashboardMoney(value, locale, data.currency) : formatDashboardNumber(value, locale)}
      currentLabel={t(sort === "AMOUNT" ? "gestion.widget.sales.today" : "gestion.dashboard.netUnits")} /> : <div className="gd-table-wrap"><table className="gd-table gd-product-table">
      <thead><tr><th>#</th><th>{t("gestion.widget.product")}</th><th>{t("gestion.dashboard.family")}</th><th className="numeric">{t("gestion.dashboard.netUnits")}</th><th className="numeric">{t("gestion.widget.sales.today")}</th><th className="numeric">{t("gestion.dashboard.share")}</th></tr></thead>
      <tbody>{rows.map((row, index) => <tr key={row.productId}>
        <td>{index + 1}</td><td><span title={`${row.code} ${row.name}`}>{row.name}</span><small>{row.code}</small>
        </td><td>{row.familyName || t("gestion.dashboard.unclassified")}</td><td className="numeric">{formatDashboardNumber(row.netQuantity, locale)}</td>
        <td className="numeric">{row.netAmount == null ? "—" : formatDashboardMoney(row.netAmount, locale, data.currency)}</td>
        <td className="numeric">{data.current.netSales > 0 && row.netAmount != null ? `${formatDashboardNumber(row.netAmount / data.current.netSales * 100, locale, 1)} %` : "—"}</td>
      </tr>)}</tbody>
    </table></div>}
    {state.error && <WidgetMessage text={t("gestion.dashboard.staleData")} error />}
    <WidgetFooter label={t("gestion.widget.openStock")} onOpen={onOpen} />
  </div>;
}

export function ActivePromotionsWidget({ state, t, locale, onOpen, date, display = "TABLE" }: WidgetProps<ActivePromotionData[]> & { date: string; display?: "BAR" | "TABLE" }) {
  if (!state.data) return <WidgetMessage text={t(state.error ? "gestion.widget.loadError" : "common.loading")} error={state.error} />;
  return <div className="gd-promotions" aria-busy={state.loading}>
    <p className="gd-widget-note">{t("gestion.dashboard.activeOn")} {formatDashboardDate(date, locale)}</p>
    <div className="gd-activity-count"><strong>{formatDashboardNumber(state.data.length, locale, 0)}</strong><span>{t("gestion.widget.activeCount")}</span></div>
    {state.data.length === 0 ? <WidgetMessage text={t("gestion.dashboard.noData")} /> : display === "BAR" ? <DashboardBars
      rows={Object.entries(state.data.reduce<Record<string, number>>((counts, promotion) => { counts[promotion.type] = (counts[promotion.type] ?? 0) + 1; return counts; }, {}))
        .map(([type, value]) => ({ key: type, label: t(`promotion.type.${type}`), value }))}
      format={value => formatDashboardNumber(value, locale, 0)} currentLabel={t("gestion.widget.activeCount")} />
      : <ul className="gd-activity-list">{state.data.map((promotion) =>
      <li key={promotion.id}><div><strong>{promotion.name}</strong><small>{promotionSummary(promotion, t, locale)}</small>
        <small>{promotion.startDate ? formatDashboardDate(promotion.startDate, locale) : "—"} – {promotion.endDate ? formatDashboardDate(promotion.endDate, locale) : t("gestion.dashboard.noEndDate")}</small></div></li>)}</ul>}
    {state.error && <WidgetMessage text={t("gestion.dashboard.staleData")} error />}
    <WidgetFooter label={t("gestion.widget.openPromotions")} onOpen={onOpen} />
  </div>;
}

export function ControlAlertsWidget({ state, t, locale, onOpen, timeZone, display = "TABLE" }: WidgetProps<ControlAlertsSummaryData> & { timeZone?: string; display?: "BAR" | "TABLE" }) {
  if (!state.data) return <WidgetMessage text={t(state.error ? "gestion.widget.loadError" : "common.loading")} error={state.error} />;
  const data = state.data;
  return <div className="gd-alerts" aria-busy={state.loading}>
    <p className="gd-widget-note"><Info size={14} aria-hidden="true" />{t("gestion.dashboard.alertsScope")}</p>
    <div className="gd-alert-counts"><div><Bell size={22} aria-hidden="true" /><strong>{formatDashboardNumber(data.newCount, locale, 0)}</strong><span>{t("gestion.widget.controlAlerts.new")}</span></div>
      <small><span>{formatDashboardNumber(data.reviewedCount, locale, 0)}</span> {t("gestion.widget.controlAlerts.reviewed")}</small></div>
    {display === "BAR" ? <DashboardBars rows={[
      { key: "NEW", value: data.newCount }, { key: "REVIEWED", value: data.reviewedCount },
      { key: "CLOSED", value: data.closedCount ?? 0 }, { key: "DISMISSED", value: data.dismissedCount ?? 0 }
    ].map(row => ({ ...row, label: t(`gestion.controlAlerts.status.${row.key}`) }))}
      format={value => formatDashboardNumber(value, locale, 0)} currentLabel={t("gestion.widget.control.alerts")} /> : <>
    <p className="gd-widget-note">{t("gestion.dashboard.recentActivity")}</p>
    {data.recentAlerts.length === 0 ? <WidgetMessage text={t("gestion.widget.controlAlerts.empty")} /> : <ul className="gd-activity-list">{data.recentAlerts.slice(0, 5).map((alert) =>
      <li key={alert.id}><span className={`gd-alert-dot ${alert.status.toLowerCase()}`} /><div><strong>{t(`gestion.controlAlerts.type.${alert.type}`)}</strong><small>{alert.documentNumber || alert.userName || "—"} · {t(`gestion.controlAlerts.status.${alert.status}`)}</small></div>
        <time dateTime={alert.occurredAt}>{formatDashboardDateTime(alert.occurredAt, locale, timeZone)}</time></li>)}</ul>}
    </>}
    {state.error && <WidgetMessage text={t("gestion.dashboard.staleData")} error />}
    <WidgetFooter label={t("gestion.widget.controlAlerts.open")} onOpen={onOpen} />
  </div>;
}

function promotionSummary(promotion: ActivePromotionData, t: DashboardTranslator, locale: LocaleCode) {
  const number = (value: number) => formatDashboardNumber(value, locale);
  const money = (value: number) => formatDashboardMoney(value, locale);
  const label = t(`promotion.type.${promotion.type}`);
  const discount = promotion.discountPercent != null ? `${number(promotion.discountPercent)} %`
    : promotion.discountAmount != null ? money(promotion.discountAmount) : "";
  if (promotion.type === "BUY_X_PAY_Y" && promotion.buyQuantity != null && promotion.payQuantity != null) {
    return `${label}: ${number(promotion.buyQuantity)} × ${number(promotion.payQuantity)}`;
  }
  if (promotion.type === "FIXED_PACK_PRICE" && promotion.packPrice != null) {
    return `${label}: ${promotion.minimumQuantity == null ? "" : `${number(promotion.minimumQuantity)} · `}${money(promotion.packPrice)}`;
  }
  const threshold = promotion.minimumAmount != null ? `${t("gestion.dashboard.fromAmount")} ${money(promotion.minimumAmount)}`
    : promotion.minimumQuantity != null ? `${t("gestion.dashboard.fromQuantity")} ${number(promotion.minimumQuantity)}` : "";
  return [label, discount, threshold].filter(Boolean).join(" · ");
}

function WidgetMessage({ text, error = false }: { text: string; error?: boolean }) {
  return <div className={`gd-widget-message ${error ? "error" : ""}`} role={error ? "status" : undefined}>{text}</div>;
}
function WidgetFooter({ label, onOpen }: { label: string; onOpen: () => void }) {
  return <footer className="gd-widget-footer"><button type="button" onClick={onOpen}>{label}<ArrowRight size={15} aria-hidden="true" /></button></footer>;
}
export function dashboardLocale(locale: LocaleCode) { return locale === "en" ? "en-GB" : locale === "zh" ? "zh-CN" : "es-ES"; }
export function formatDashboardMoney(value: number, locale: LocaleCode, currency = "EUR") {
  return new Intl.NumberFormat(dashboardLocale(locale), { style: "currency", currency }).format(value);
}
export function formatDashboardNumber(value: number, locale: LocaleCode, maximumFractionDigits = 3) {
  return new Intl.NumberFormat(dashboardLocale(locale), { maximumFractionDigits }).format(value);
}
export function formatDashboardDate(value: string, locale: LocaleCode, short = false) {
  const date = new Date(`${value.slice(0, 10)}T12:00:00Z`);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(dashboardLocale(locale), { day: "2-digit", month: "2-digit", ...(short ? {} : { year: "numeric" as const }), timeZone: "UTC" }).format(date);
}
export function formatDashboardRange(from: string, to: string, locale: LocaleCode) {
  return `${formatDashboardDate(from, locale)} – ${formatDashboardDate(to, locale)}`;
}
function formatDashboardDateTime(value: string, locale: LocaleCode, timeZone?: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(dashboardLocale(locale), { dateStyle: "short", timeStyle: "short", timeZone }).format(date);
}
