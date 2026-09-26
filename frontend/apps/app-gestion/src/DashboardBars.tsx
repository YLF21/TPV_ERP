import type { CSSProperties } from "react";

export type DashboardBar = { key: string; label: string; value: number; previous?: number };

/** A shared zero axis keeps negative sales visible and comparable with positive amounts. */
export function DashboardBars({ rows, format, currentLabel, previousLabel }: {
  rows: DashboardBar[]; format: (value: number) => string; currentLabel: string; previousLabel?: string;
}) {
  const values = rows.flatMap(row => [row.value, row.previous ?? 0]);
  const minimum = Math.min(0, ...values);
  const maximum = Math.max(0, ...values);
  const extent = maximum - minimum || 1;
  const zero = -minimum / extent * 100;
  const style = (value: number): CSSProperties => ({ left: `${(Math.min(0, value) - minimum) / extent * 100}%`, width: `${Math.abs(value) / extent * 100}%` });
  return <div className="gd-bars">
    {previousLabel && <div className="gd-chart-legend"><span><i />{currentLabel}</span><span><i className="previous" />{previousLabel}</span></div>}
    <ul className="gd-bar-list">{rows.map(row => <li key={row.key}>
      <div className="gd-bar-caption"><span title={row.label}>{row.label}</span><strong>{format(row.value)}</strong></div>
      <div className="gd-bar-track" aria-hidden="true"><span className="gd-bar-zero" style={{ left: `${zero}%` }} /><i className={row.value < 0 ? "negative" : ""} style={style(row.value)} /></div>
      {previousLabel && row.previous !== undefined && <>
        <div className="gd-bar-track previous" aria-hidden="true"><span className="gd-bar-zero" style={{ left: `${zero}%` }} /><i style={style(row.previous)} /></div>
        <small>{previousLabel}: {format(row.previous)}</small>
      </>}
    </li>)}</ul>
  </div>;
}
