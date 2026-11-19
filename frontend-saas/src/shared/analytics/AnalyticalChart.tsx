import { useEffect, useId, useRef, useState } from "react";
import { localeFor, useI18n } from "../../i18n";
import "./analytics.css";

type Point = { label: string; value: number | null; valueLabel?: string };
type Series = { name: string; color?: string; values: Point[] };
const palette = ["#12385f", "#79b7f2", "#608aab", "#745a91"];

/** Charts are an overview; associated tables retain exact decimal values. */
export function AnalyticalChart({ title, kind = "line", series, note }: { title: string; kind?: "line" | "bar"; series: Series[]; note?: string }) {
  const id = useId();
  const { language } = useI18n();
  const svgRef = useRef<SVGSVGElement>(null);
  const [width, setWidth] = useState(660);
  useEffect(() => {
    const svg = svgRef.current;
    if (!svg) return;
    const observer = new ResizeObserver(([entry]) => {
      const available = Math.round(entry.contentRect.width);
      if (available > 0) setWidth(Math.max(280, available));
    });
    observer.observe(svg);
    return () => observer.disconnect();
  }, []);
  const [focus, setFocus] = useState<number | null>(null);
  const [activePoint, setActivePoint] = useState(0);
  const count = Math.max(0, ...series.map(item => item.values.length));
  const tabPoint = Math.min(activePoint, Math.max(0, count - 1));
  const pointText = (point: Point | undefined) => point?.valueLabel ?? point?.value ?? "—";
  const numbers = series.flatMap(item => item.values.map(point => point.value)).filter((value): value is number => value !== null && Number.isFinite(value));
  const low = Math.min(0, ...numbers), high = Math.max(1, ...numbers);
  const height = 160, left = 52, right = 12, top = 12, bottom = 32;
  const plotWidth = width - left - right, plotHeight = height - top - bottom;
  const tickCount = Math.min(count, 7, Math.max(2, Math.floor(plotWidth / 110) + 1));
  const ticks = new Set(Array.from({ length: tickCount }, (_, index) => Math.round(index * (count - 1) / Math.max(1, tickCount - 1))));
  const x = (index: number) => left + plotWidth * (index + 0.5) / Math.max(1, count);
  const y = (value: number) => top + plotHeight * (high - value) / (high - low);
  const compact = new Intl.NumberFormat(localeFor(language), { notation: "compact", maximumFractionDigits: 1 });
  const axisDate = new Intl.DateTimeFormat(localeFor(language), { day: "2-digit", month: "2-digit", timeZone: "UTC" });
  const axisLabel = (label: string) => {
    if (/^\d{4}-\d{2}-\d{2}$/.test(label)) {
      const date = new Date(label + "T00:00:00Z");
      if (Number.isFinite(date.getTime())) return axisDate.format(date);
    }
    const spacing = plotWidth * Math.max(1, count - 1) / Math.max(1, count) / Math.max(1, tickCount - 1);
    const budget = Math.max(24, Math.min(110, spacing * (tickCount === 2 ? .45 : .58)));
    let used = 0;
    let shortened = "";
    for (const character of label) {
      const advance = /[\u2e80-\u9fff\uac00-\ud7af\uf900-\ufaff\uff00-\uffef]/u.test(character) ? 11 : 6.5;
      if (used + advance > budget - 7) return shortened + "…";
      shortened += character;
      used += advance;
    }
    return shortened;
  };
  const focused = focus === null ? null : series.map(item => ({ name: item.name, point: item.values[focus] })).filter(item => item.point);
  return <figure className="analytics-chart" aria-labelledby={id}>
    <figcaption id={id}>{title}</figcaption>
    <svg ref={svgRef} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={title}>
      {[low, (high + low) / 2, high].map((value, index) => <g key={index}><line x1={left} x2={width - right} y1={y(value)} y2={y(value)} className="analytics-grid-line" /><text x={left - 6} y={y(value) + 4} textAnchor="end">{compact.format(value)}</text></g>)}
      {series.map((item, seriesIndex) => <g key={item.name} style={{ color: item.color ?? palette[seriesIndex % palette.length] }}>
        {kind === "line" ? <path fill="none" stroke="currentColor" strokeWidth={2} d={item.values.map((point, index) => point.value === null || !Number.isFinite(point.value) ? "" : `${index === 0 || item.values[index - 1].value === null ? "M" : "L"}${x(index)},${y(point.value)}`).join(" ")} />
          : item.values.map((point, index) => point.value === null || !Number.isFinite(point.value) ? null : <rect key={index} x={x(index) - plotWidth / Math.max(1, count) * .35 + seriesIndex * plotWidth / Math.max(1, count) * .7 / series.length} y={Math.min(y(0), y(point.value))} width={Math.max(1, plotWidth / Math.max(1, count) * .7 / series.length)} height={Math.abs(y(0) - y(point.value))} fill="currentColor" />)}
        {kind === "line" && item.values.map((point, index) => point.value === null || !Number.isFinite(point.value)
          ? null : <circle key={index} cx={x(index)} cy={y(point.value)} r={2} fill="currentColor" />)}
      </g>)}
      {Array.from({ length: count }, (_, index) => <g key={index} data-chart-point={index}
        tabIndex={index === tabPoint ? 0 : -1} role="group"
        aria-keyshortcuts="ArrowLeft ArrowRight Home End"
        aria-label={series.map(item => `${item.values[index]?.label ?? ""} · ${item.name}: ${pointText(item.values[index])}`).join("; ")}
        onFocus={() => { setActivePoint(index); setFocus(index); }} onBlur={() => setFocus(null)}
        onMouseEnter={() => setFocus(index)} onMouseLeave={event => { if (document.activeElement !== event.currentTarget) setFocus(null); }}
        onKeyDown={event => {
          const next = event.key === "ArrowRight" ? Math.min(count - 1, index + 1)
            : event.key === "ArrowLeft" ? Math.max(0, index - 1)
            : event.key === "Home" ? 0 : event.key === "End" ? count - 1 : null;
          if (next === null) return;
          event.preventDefault();
          event.currentTarget.ownerSVGElement?.querySelector<SVGGElement>(`[data-chart-point="${next}"]`)?.focus();
        }}>
        <rect x={x(index) - plotWidth / Math.max(1, count) / 2} y={top} width={plotWidth / Math.max(1, count)} height={plotHeight} fill="transparent" />
        {ticks.has(index) && <text x={x(index)} y={height - 9} textAnchor={index === 0 ? "start" : index === count - 1 ? "end" : "middle"}>{axisLabel(series[0]?.values[index]?.label ?? "")}</text>}
      </g>)}
    </svg>
    <div className="analytics-chart-legend">{series.map((item, index) => <span key={item.name}><i style={{ background: item.color ?? palette[index % palette.length] }} />{item.name}</span>)}</div>
    <div className="analytics-chart-focus" aria-live="polite">{focused?.map(item => `${item.point.label} · ${item.name}: ${pointText(item.point)}`).join(" / ") ?? note ?? ""}</div>
  </figure>;
}
