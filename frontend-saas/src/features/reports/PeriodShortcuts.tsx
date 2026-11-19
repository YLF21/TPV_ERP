import { useI18n } from "../../i18n";
import { adjacentPeriod, matchingPeriod, periodPreset, periodPresets } from "./period-shortcuts.mjs";
import "../../shared/analytics/analytics.css";

export function PeriodShortcuts({ from, to, onChange }: { from: string; to: string; onChange: (range: { from: string; to: string }) => void }) {
  const { language } = useI18n();
  const l = (es: string, en: string, zh: string) => language === "zh" ? zh : language === "en" ? en : es;
  const names: Record<string, string> = {
    TODAY: l("Hoy", "Today", "今天"), YESTERDAY: l("Ayer", "Yesterday", "昨天"),
    WEEK: l("Últimos 7 días", "Last 7 days", "最近7天"), LAST_30: l("Últimos 30 días", "Last 30 days", "最近30天"),
    MONTH: l("Este mes", "This month", "本月"), LAST_MONTH: l("Mes anterior", "Last month", "上个月"), YEAR: l("Este año", "This year", "今年"),
  };
  return <div className="analytics-period-shortcuts">
    <label>{l("Período", "Period", "期间")}<select className="control-input" aria-label={l("Período", "Period", "期间")} value={matchingPeriod(from, to)} onChange={event => {
      const range = periodPreset(event.target.value); if (range) onChange(range);
    }}><option value="">{l("Personalizado", "Custom", "自定义")}</option>{periodPresets.map(key => <option key={key} value={key}>{names[key]}</option>)}</select></label>
    {([-1, 1] as const).map(direction => <button className="small-button" type="button" key={direction}
      disabled={!adjacentPeriod(from, to, direction)} onClick={() => { const range = adjacentPeriod(from, to, direction); if (range) onChange(range); }}>
      {direction < 0 ? l("← Período anterior", "← Previous period", "← 上一期间") : l("Período siguiente →", "Next period →", "下一期间 →")}
    </button>)}
  </div>;
}

export function FilterActions({ dirty, onReset }: { dirty: boolean; onReset: () => void }) {
  const { language } = useI18n();
  const l = (es: string, en: string, zh: string) => language === "zh" ? zh : language === "en" ? en : es;
  return <div className="analytics-filter-actions">
    <button type="button" className="small-button" onClick={onReset}>{l("Restablecer filtros", "Reset filters", "重置筛选")}</button>
    <span role="status" className="analytics-pending">{dirty ? l("Cambios pendientes · pulsa Aplicar filtros", "Pending changes · select Apply filters", "有待应用的更改 · 请点击应用筛选") : ""}</span>
  </div>;
}
