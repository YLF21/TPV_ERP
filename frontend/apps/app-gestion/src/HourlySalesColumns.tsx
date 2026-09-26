import { useLayoutEffect, useRef, useState } from "react";
import type { HourSales } from "./dashboardModel";

type Metric = "units" | "sales" | "operations";
type Row = { hour: number; current: HourSales; previous: HourSales };

type Props = {
  rows: Row[];
  metric: Metric;
  compare: boolean;
  currentLabel: string;
  previousLabel: string;
  metricLabel: string;
  format: (value: number) => string;
  focusedHour?: number;
  onFocus: (hour: number) => void;
};

const hourTime = (hour: number) => `${String(hour).padStart(2, "0")}:00`;
const hourLabel = (hour: number) => `${hourTime(hour)} – ${hourTime(hour + 1)}`;

export function HourlyColumns({ rows, metric, compare, currentLabel, previousLabel, metricLabel, format, focusedHour, onFocus }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });

  useLayoutEffect(() => {
    const element = container.current;
    if (!element) return;
    const measure = () => {
      const { width, height } = element.getBoundingClientRect();
      setSize(previous => previous.width === width && previous.height === height ? previous : { width, height });
    };
    measure();
    if (typeof ResizeObserver === "undefined") {
      window.addEventListener("resize", measure);
      return () => window.removeEventListener("resize", measure);
    }
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const { width, height } = size;
  if (width <= 0 || height <= 0) return <div ref={container} className="gd-hourly-chart-scroll" />;

  const values = rows.flatMap(row => compare ? [row.current[metric], row.previous[metric]] : [row.current[metric]]);
  const low = Math.min(0, ...values), high = Math.max(0, ...values);
  const showHourLabels = height >= 55;
  const chartTop = Math.min(14, height * .12);
  const chartBottom = Math.max(chartTop + 1, height - (showHourLabels ? 28 : Math.min(4, height * .1)));
  const targetIntervals = Math.max(1, Math.min(6, Math.floor((chartBottom - chartTop) / 38)));
  const roughStep = (high - low || 1) / targetIntervals;
  const power = 10 ** Math.floor(Math.log10(roughStep));
  const unit = roughStep / power;
  const step = (unit <= 1 ? 1 : unit <= 2 ? 2 : unit <= 5 ? 5 : 10) * power;
  const bottom = Math.floor(low / step) * step;
  const top = Math.ceil(high / step) * step || (low === 0 ? step * targetIntervals : 0);
  const ticks = Array.from({ length: Math.round((top - bottom) / step) + 1 }, (_, index) => bottom + index * step);
  // Axis labels retain their normal font size; only their frequency changes as space shrinks.
  const axisLabelWidth = Math.max(0, ...ticks.map(tick => format(tick).length * 7));
  const left = Math.min(Math.max(36, axisLabelWidth + 16), width * .45);
  const right = width - Math.min(18, width * .1);
  const y = (value: number) => chartBottom - (value - bottom) / (top - bottom) * (chartBottom - chartTop);
  const slot = (right - left) / Math.max(rows.length, 1);
  const gap = Math.min(2, slot * .08);
  const barWidth = compare ? Math.min(30, (slot * .88 - gap) / 2) : Math.min(30, slot * .65);
  const labelEvery = Math.max(1, Math.ceil(38 / slot));
  const tickEvery = Math.max(1, Math.ceil(25 / ((chartBottom - chartTop) / Math.max(ticks.length - 1, 1))));

  return <div ref={container} className="gd-hourly-chart-scroll"><svg className="gd-hourly-chart" width={width} height={height} viewBox={`0 0 ${width} ${height}`}
    role="group" aria-label={`${metricLabel} · ${currentLabel}${compare ? ` / ${previousLabel}` : ""}`}>
    <title>{metricLabel}</title>
    {ticks.map((tick, index) => <g key={index}><line x1={left} y1={y(tick)} x2={right} y2={y(tick)} className={Math.abs(tick) < step / 100 ? "gd-chart-zero" : "gd-chart-gridline"} />
      {height >= 28 && (index % tickEvery === 0 || Math.abs(tick) < step / 100) && format(tick).length * 7 <= left - 10 &&
        <text x={left - 10} y={y(tick) + 4} textAnchor="end">{format(tick)}</text>}
    </g>)}
    {rows.map((row, index) => {
      const center = left + slot * (index + .5);
      const description = `${hourLabel(row.hour)} · ${currentLabel}: ${format(row.current[metric])}${compare ? ` · ${previousLabel}: ${format(row.previous[metric])}` : ""}`;
      return <g key={row.hour} role="button" tabIndex={0} aria-label={description} aria-pressed={row.hour === focusedHour}
        onFocus={() => onFocus(row.hour)} onMouseEnter={() => onFocus(row.hour)} onClick={() => onFocus(row.hour)}
        onKeyDown={event => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); onFocus(row.hour); } }}>
        <title>{description}</title>
        <rect className="gd-hourly-hit" x={center - slot / 2} y={chartTop} width={slot} height={chartBottom - chartTop} fill={row.hour === focusedHour ? "#edf5ff" : "transparent"} />
        {(compare ? ["current", "previous"] as const : ["current"] as const).map(series => {
          const value = row[series][metric];
          const x = center + (compare ? series === "current" ? -barWidth - gap / 2 : gap / 2 : -barWidth / 2);
          return <rect key={series} className={`gd-hourly-column ${series}`} x={x} y={Math.min(y(value), y(0))} width={barWidth} height={Math.abs(y(value) - y(0))} />;
        })}
        {showHourLabels && index % labelEvery === 0 && <text x={center} y={chartBottom + 24} textAnchor="middle">{hourTime(row.hour)}</text>}
      </g>;
    })}
  </svg></div>;
}
