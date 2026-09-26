import { useEffect, useId, useRef, useState } from "react";
import { CaretDown, Gear } from "@phosphor-icons/react";
import { HourlyColumns } from "./HourlySalesColumns";
import type { LocaleCode } from "@tpverp/app-common";
import { loadHourlySales, validDashboardRange, type HourSales, type HourlySalesData, type HourlySalesScope, type SalesOverviewData } from "./dashboardModel";
import { formatDashboardDate, formatDashboardRange, formatDashboardMoney, formatDashboardNumber, type DashboardDataState, type DashboardTranslator } from "./GestionDashboardWidgets";

type DateRange = { from: string; to: string };
type Metric = "units" | "sales" | "operations";
type Row = { hour: number; current: HourSales; previous: HourSales };
type Props = {
  state: DashboardDataState<SalesOverviewData>; t: DashboardTranslator; locale: LocaleCode; display: "BAR" | "TABLE";
  token?: string; warehouseId?: string; refresh: number;
  load?: (token: string | undefined, scope: HourlySalesScope, signal?: AbortSignal) => Promise<HourlySalesData>;
};
const hourLabel = (hour: number) => `${String(hour).padStart(2, "0")}:00 – ${String(hour + 1).padStart(2, "0")}:00`;
const validDay = (day: string) => day >= "0001-01-01" && validDashboardRange({ from: day, to: day });
const weekBefore = (day: string) => {
  if (!validDay(day) || day < "0001-01-08") return "";
  const date = new Date(`${day}T12:00:00Z`);
  date.setUTCDate(date.getUTCDate() - 7);
  return date.toISOString().slice(0, 10);
};
const previousPeriod = (range: DateRange): DateRange => {
  if (!validDashboardRange(range)) return { from: "", to: "" };
  const start = new Date(`${range.from}T12:00:00Z`);
  const days = Math.round((Date.parse(`${range.to}T12:00:00Z`) - start.getTime()) / 86_400_000) + 1;
  start.setUTCDate(start.getUTCDate() - days);
  if (start.getUTCFullYear() < 1) return { from: "", to: "" };
  const end = new Date(`${range.from}T12:00:00Z`);
  end.setUTCDate(end.getUTCDate() - 1);
  return { from: start.toISOString().slice(0, 10), to: end.toISOString().slice(0, 10) };
};
const emptyHour = (date: string, hour: number): HourSales => ({ date, hour, units: 0, sales: 0, operations: 0 });
const hasActivity = (row: HourSales) => row.operations !== 0 || row.units !== 0 || row.sales !== 0;

export function HourlySalesWidget({ state, t, locale, display, token, warehouseId, refresh, load = loadHourlySales }: Props) {
  const [settingsOpen, setSettingsOpen] = useState(false);
  const settingsId = useId();
  const settingsButton = useRef<HTMLButtonElement>(null);
  const [mode, setMode] = useState<"DAY" | "PERIOD">("DAY");
  const [periodSelection, setPeriodSelection] = useState<DateRange & { context: string }>();
  const [periodComparisonMode, setPeriodComparisonMode] = useState<"AUTO" | "CUSTOM" | "NONE">("AUTO");
  const [customComparison, setCustomComparison] = useState<DateRange>({ from: "", to: "" });
  const [selectedDay, setSelectedDay] = useState("");
  const [manualComparison, setManualComparison] = useState("");
  const [sameWeekday, setSameWeekday] = useState(true);
  const [showEmpty, setShowEmpty] = useState(false);
  const [metric, setMetric] = useState<Metric>("units");
  const [selectedHour, setSelectedHour] = useState<number>();
  const [retry, setRetry] = useState(0);
  const [result, setResult] = useState<{ key: string; data?: HourlySalesData; error?: boolean; loading: boolean }>();
  const range = state.data;
  const day = range && selectedDay >= range.from && selectedDay <= range.to ? selectedDay : range?.to ?? "";
  const comparisonDay = sameWeekday ? weekBefore(day) : manualComparison;
  const rangeContext = `${range?.from}/${range?.to}`;
  const periodRange = periodSelection?.context === rangeContext ? periodSelection : { from: range?.from ?? "", to: range?.to ?? "" };
  const currentRange = mode === "DAY" ? { from: day, to: day } : periodRange;
  const comparisonRange = mode === "DAY" ? { from: comparisonDay, to: comparisonDay }
    : periodComparisonMode === "NONE" ? { from: "", to: "" }
    : periodComparisonMode === "AUTO" ? previousPeriod(currentRange) : customComparison;
  const { from, to } = currentRange;
  const { from: comparisonFrom, to: comparisonTo } = comparisonRange;
  const validWindow = (value: DateRange) => validDay(value.from) && validDay(value.to) && validDashboardRange(value);
  const wantsComparison = mode === "DAY" ? !!comparisonDay : periodComparisonMode !== "NONE";
  const valid = validWindow(currentRange) && (!wantsComparison || validWindow(comparisonRange));
  const requestKey = `${from}/${to}/${comparisonFrom}/${comparisonTo}/${warehouseId ?? ""}`;
  const updatePeriod = (patch: Partial<DateRange>) => setPeriodSelection({ ...periodRange, ...patch, context: rangeContext });
  const updateComparison = (patch: Partial<DateRange>) => {
    setCustomComparison({ ...comparisonRange, ...patch }); setPeriodComparisonMode("CUSTOM");
  };

  useEffect(() => {
    if (!valid) return;
    const controller = new AbortController();
    setResult(previous => ({ key: requestKey, data: previous?.key === requestKey ? previous.data : undefined, loading: true }));
    void load(token, { from, to, comparisonFrom: comparisonFrom || undefined, comparisonTo: comparisonTo || undefined, warehouseId }, controller.signal)
      .then(data => { if (!controller.signal.aborted) setResult({ key: requestKey, data, loading: false }); })
      .catch(() => { if (!controller.signal.aborted) setResult(previous => ({ key: requestKey, data: previous?.key === requestKey ? previous.data : undefined, loading: false, error: true })); });
    return () => controller.abort();
  }, [load, token, from, to, comparisonFrom, comparisonTo, warehouseId, requestKey, valid, refresh, retry]);

  const currentResult = valid && result?.key === requestKey ? result : undefined;
  const data = currentResult?.data;
  const compare = !!data?.comparisonFrom;
  const metricLabels: Record<Metric, string> = { units: t("gestion.dashboard.units"), sales: t("gestion.dashboard.amount"), operations: t("gestion.widget.sales.operations") };
  const format = (value: number) => metric === "sales" ? formatDashboardMoney(value, locale, data?.currency) : formatDashboardNumber(value, locale);
  const number = (value: number) => formatDashboardNumber(value, locale);
  const percent = (value: number) => `${value > 0 ? "+" : ""}${formatDashboardNumber(value, locale, 1)} %`;
  const delta = (row: Row) => row.current[metric] - row.previous[metric];
  const variation = (row: Row) => row.previous[metric] === 0 ? "—" : percent(delta(row) / Math.abs(row.previous[metric]) * 100);
  const rows: Row[] = Array.from({ length: 24 }, (_, hour) => ({ hour,
    current: data?.current.find(row => row.hour === hour) ?? emptyHour(from, hour),
    previous: data?.previous.find(row => row.hour === hour) ?? emptyHour(comparisonFrom, hour) }));
  const unknownCurrent = data?.current.find(row => row.hour === -1);
  const unknownPrevious = data?.previous.find(row => row.hour === -1);
  const hasUnknown = !!unknownCurrent || !!unknownPrevious;
  const activeRows = rows.filter(row => hasActivity(row.current) || (compare && hasActivity(row.previous)));
  // Keep intervening empty hours on the time axis, even when hiding empty table rows.
  const chartRows = showEmpty ? rows : activeRows.length ? rows.slice(activeRows[0].hour, activeRows[activeRows.length - 1].hour + 1) : [];
  const tableRows = showEmpty ? [...rows] : [...activeRows];
  if (hasUnknown) tableRows.push({ hour: -1, current: unknownCurrent ?? emptyHour(from, -1), previous: unknownPrevious ?? emptyHour(comparisonFrom, -1) });
  const peak = Math.max(0, ...rows.map(row => row.current[metric]));
  const peakHours = rows.filter(row => peak > 0 && row.current[metric] === peak);
  const focused = chartRows.find(row => row.hour === selectedHour) ?? peakHours[0] ?? chartRows[0];
  const label = (row: Row) => row.hour < 0 ? t("gestion.dashboard.unknownHour") : hourLabel(row.hour);
  const rangeLabel = (start: string, end: string) => start === end ? formatDashboardDate(start, locale) : formatDashboardRange(start, end, locale);
  const currentLabel = data ? rangeLabel(data.from, data.to) : "";
  const previousLabel = data?.comparisonFrom && data.comparisonTo ? rangeLabel(data.comparisonFrom, data.comparisonTo) : "";
  const totals: Row = { hour: -1,
    current: (data?.current ?? []).reduce((sum, row) => ({ ...sum, units: sum.units + row.units, sales: sum.sales + row.sales, operations: sum.operations + row.operations }), emptyHour(from, -1)),
    previous: (data?.previous ?? []).reduce((sum, row) => ({ ...sum, units: sum.units + row.units, sales: sum.sales + row.sales, operations: sum.operations + row.operations }), emptyHour(comparisonFrom, -1)) };

  if (!range) return <p className="gd-widget-message" role="status">{t(state.error ? "gestion.widget.loadError" : "common.loading")}</p>;
  return <div className={`gd-analysis gd-hourly ${display === "BAR" ? "is-chart" : ""}`} aria-busy={currentResult?.loading ?? (valid && !currentResult)}>
    <div className="gd-hourly-settings-bar">
      <div className="gd-hourly-selection" title={`${metricLabels[metric]} · ${validWindow(currentRange) ? rangeLabel(from, to) : "—"}${wantsComparison && validWindow(comparisonRange) ? ` · ${t("gestion.dashboard.comparedWith")} ${rangeLabel(comparisonFrom, comparisonTo)}` : ""}`}>
        <strong>{metricLabels[metric]}</strong>
        <span>{validWindow(currentRange) ? rangeLabel(from, to) : "—"}</span>
        {wantsComparison && validWindow(comparisonRange) && <span>{t("gestion.dashboard.comparedWith")} {rangeLabel(comparisonFrom, comparisonTo)}</span>}
      </div>
      <button type="button" ref={settingsButton} id={`${settingsId}-button`} className="gd-hourly-settings-button"
        aria-expanded={settingsOpen} aria-controls={settingsId} aria-label={t("gestion.dashboard.hourlySettings")} title={t("gestion.dashboard.hourlySettings")}
        onClick={() => setSettingsOpen(open => !open)}>
        <Gear size={15} aria-hidden="true" /><span>{t("gestion.dashboard.hourlySettings")}</span><CaretDown size={13} aria-hidden="true" />
      </button>
    </div>
    <div id={settingsId} className="gd-hourly-settings" role="region" aria-labelledby={`${settingsId}-button`} hidden={!settingsOpen}
      onKeyDown={event => {
        if (event.key === "Escape") { event.stopPropagation(); setSettingsOpen(false); settingsButton.current?.focus(); }
      }}>
    <div className="gd-segmented gd-hourly-metric gd-hourly-period-mode" role="group" aria-label={t("gestion.dashboard.hourlyGrouping")}>
      <button type="button" aria-pressed={mode === "DAY"} onClick={() => setMode("DAY")}>{t("gestion.dashboard.day")}</button>
      <button type="button" aria-pressed={mode === "PERIOD"} onClick={() => setMode("PERIOD")}>{t("gestion.dashboard.period")}</button>
    </div>
    {mode === "DAY" ? <div className="gd-hourly-toolbar">
      <label>{t("gestion.dashboard.day")}<input type="date" value={day} min={range?.from} max={range?.to} onChange={event => setSelectedDay(event.target.value)} /></label>
      <label>{t("gestion.dashboard.compareDay")}<input type="date" value={comparisonDay} min="0001-01-01" max="9999-12-31"
        onChange={event => { setManualComparison(event.target.value); setSameWeekday(false); }} /></label>
      <label className="gd-checkbox"><input type="checkbox" checked={sameWeekday} onChange={event => {
        setManualComparison(comparisonDay); setSameWeekday(event.target.checked);
      }} />{t("gestion.dashboard.sameWeekday")}</label>
    </div> : <>
      <div className="gd-hourly-toolbar">
        <label>{t("gestion.dashboard.from")}<input type="date" value={from} min="0001-01-01" max="9999-12-31" onChange={event => updatePeriod({ from: event.target.value })} /></label>
        <label>{t("gestion.dashboard.to")}<input type="date" value={to} min="0001-01-01" max="9999-12-31" onChange={event => updatePeriod({ to: event.target.value })} /></label>
        <label>{t("gestion.dashboard.compareDay")}<select value={periodComparisonMode} onChange={event => {
          const value = event.target.value as "AUTO" | "CUSTOM" | "NONE";
          if (value === "CUSTOM") setCustomComparison(comparisonRange.from ? comparisonRange : previousPeriod(currentRange));
          setPeriodComparisonMode(value);
        }}>
          <option value="AUTO">{t("gestion.dashboard.previousSameLength")}</option>
          <option value="CUSTOM">{t("gestion.dashboard.period.CUSTOM")}</option>
          <option value="NONE">{t("gestion.dashboard.noHourlyComparison")}</option>
        </select></label>
      </div>
      {periodComparisonMode !== "NONE" && <div className="gd-hourly-toolbar">
        <label>{t("gestion.dashboard.comparisonFrom")}<input type="date" value={comparisonFrom} min="0001-01-01" max="9999-12-31" onChange={event => updateComparison({ from: event.target.value })} /></label>
        <label>{t("gestion.dashboard.comparisonTo")}<input type="date" value={comparisonTo} min="0001-01-01" max="9999-12-31" onChange={event => updateComparison({ to: event.target.value })} /></label>
      </div>}
      <p className="gd-widget-note">{t("gestion.dashboard.hourlyPeriodSum")}</p>
    </>}
    <div className="gd-hourly-toolbar">
      <div className="gd-segmented gd-hourly-metric" role="group" aria-label={t("gestion.dashboard.hourlyMetric")}>
        {(["sales", "operations", "units"] as const).map(value => <button type="button" key={value} aria-pressed={metric === value} onClick={() => setMetric(value)}>{metricLabels[value]}</button>)}
      </div>
      <label className="gd-checkbox"><input type="checkbox" checked={showEmpty} onChange={event => setShowEmpty(event.target.checked)} />{t("gestion.dashboard.showEmptyHours")}</label>
    </div>
    <p className="gd-widget-note" title={t("gestion.dashboard.hourlyDetail")}>{t("gestion.dashboard.hourlyScope")} · {data?.storeTimezone ?? range?.storeTimezone}</p>
    </div>
    {!valid ? <p className="gd-widget-message" role="status">{t("gestion.dashboard.invalidRange")}</p>
      : !data && !currentResult?.error ? <p className="gd-widget-message" role="status">{t(state.error ? "gestion.widget.loadError" : "common.loading")}</p> : null}
    {currentResult?.error && <p className="gd-widget-message error" role="alert">{t("gestion.widget.loadError")} {data && t("gestion.dashboard.staleData")} <button type="button" onClick={() => setRetry(value => value + 1)}>{t("gestion.dashboard.retry")}</button></p>}
    {data && <>
      {currentResult.loading && <p className="gd-widget-note" role="status">{t("common.loading")}</p>}
      {peakHours.length > 0 && <p className="gd-peak-label">{t("gestion.dashboard.peakHour")}: {peakHours.map(label).join(", ")} · {format(peak)} · {metricLabels[metric]}</p>}
      {!activeRows.length && !hasUnknown ? <p className="gd-widget-message">{t("gestion.dashboard.noData")}</p> : <>
        {display === "BAR" && chartRows.length > 0 && <>
          <div className="gd-hourly-detail" aria-live="polite">{focused && <><strong>{label(focused)}</strong><span>{currentLabel}: <b>{format(focused.current[metric])}</b></span>
            {compare && <><span>{previousLabel}: {format(focused.previous[metric])}</span><strong className={delta(focused) >= 0 ? "gd-positive" : "gd-negative"}>{variation(focused)}</strong></>}</>}</div>
          <HourlyColumns rows={chartRows} metric={metric} compare={compare} currentLabel={currentLabel} previousLabel={previousLabel}
            metricLabel={metricLabels[metric]} format={format} focusedHour={focused?.hour} onFocus={setSelectedHour} />
          <div className="gd-hourly-legend"><span><i />{currentLabel}</span>{compare && <span><i className="previous" />{previousLabel}</span>}</div>
        </>}
        {display === "TABLE" && <>
        <h4 className="gd-hourly-table-title">{t(compare ? "gestion.dashboard.comparisonDetail" : "gestion.dashboard.display.TABLE")} · {metricLabels[metric]}</h4>
        <div className="gd-table-wrap gd-hourly-table"><table className="gd-table">
          <caption className="gd-visually-hidden">{t("gestion.widget.sales.hourly")} · {metricLabels[metric]}</caption>
          <thead><tr><th>{t("gestion.dashboard.hour")}</th><th className="numeric">{currentLabel} · {metricLabels[metric]}</th>
            {compare ? <><th className="numeric">{previousLabel}</th><th className="numeric">{t("gestion.dashboard.variation")}</th><th className="numeric">%</th></>
              : (["units", "sales", "operations"] as const).filter(value => value !== metric).map(value => <th className="numeric" key={value}>{metricLabels[value]}</th>)}
          </tr></thead>
          <tbody>{tableRows.map(row => <tr key={row.hour} className={row.hour === focused?.hour ? "gd-peak-row" : undefined} onMouseEnter={() => { if (row.hour >= 0) setSelectedHour(row.hour); }}>
            <td>{label(row)}</td><td className="numeric">{format(row.current[metric])}</td>
            {compare ? <><td className="numeric">{format(row.previous[metric])}</td><td className={`numeric ${delta(row) >= 0 ? "gd-positive" : "gd-negative"}`}>{delta(row) > 0 ? "+" : ""}{format(delta(row))}</td><td className="numeric">{variation(row)}</td></>
              : (["units", "sales", "operations"] as const).filter(value => value !== metric).map(value => <td className="numeric" key={value}>{value === "sales" ? formatDashboardMoney(row.current[value], locale, data.currency) : number(row.current[value])}</td>)}
          </tr>)}</tbody>
          <tfoot><tr><th>{t("gestion.dashboard.total")}</th><td className="numeric">{format(totals.current[metric])}</td>
            {compare ? <><td className="numeric">{format(totals.previous[metric])}</td><td className="numeric">{delta(totals) > 0 ? "+" : ""}{format(delta(totals))}</td><td className="numeric">{variation(totals)}</td></>
              : (["units", "sales", "operations"] as const).filter(value => value !== metric).map(value => <td className="numeric" key={value}>{value === "sales" ? formatDashboardMoney(totals.current[value], locale, data.currency) : number(totals.current[value])}</td>)}
          </tr></tfoot>
        </table></div>
        </>}
        {hasUnknown && <p className="gd-widget-note">{t("gestion.dashboard.unknownHourNote")}</p>}
      </>}
    </>}
  </div>;
}
