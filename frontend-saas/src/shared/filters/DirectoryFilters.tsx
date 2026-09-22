import { useId, useRef, useState, type ReactNode } from "react";
import { useI18n } from "../../i18n";
import "./directory-filters.css";

export type DirectoryFilterChip = { key: string; label: string; value: string; onRemove: () => void };
const labels = {
  more: ["Más filtros", "More filters", "更多筛选"],
  less: ["Menos filtros", "Fewer filters", "收起筛选"],
  selected: ["Filtros aplicados", "Applied filters", "已应用筛选"],
  remove: ["Quitar filtro", "Remove filter", "移除筛选"],
  clear: ["Limpiar todos", "Clear all", "清除全部"],
} as const;

/** The caller owns filter values and server queries; collapsing never clears them. */
export function DirectoryFilters({ label, children, advanced, chips, onClear }: {
  label: string; children: ReactNode; advanced?: ReactNode; chips: readonly DirectoryFilterChip[]; onClear: () => void;
}) {
  const { language } = useI18n();
  const l = (key: keyof typeof labels) => labels[key][language === "zh" ? 2 : language === "en" ? 1 : 0];
  const [more, setMore] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const id = useId();
  function focusFirst() { root.current?.querySelector<HTMLElement>("input:not(:disabled), select:not(:disabled)")?.focus(); }
  function remove(chip: DirectoryFilterChip, index: number) {
    chip.onRemove();
    requestAnimationFrame(() => {
      const remaining = root.current?.querySelectorAll<HTMLButtonElement>(".directory-filter-chip button");
      const target = remaining?.[Math.min(index, remaining.length - 1)];
      if (target) target.focus(); else focusFirst();
    });
  }
  return <div ref={root} className="directory-filters" role="search" aria-label={label}>
    <div className="directory-filter-main">
      {children}
      {advanced && <button className="secondary-button directory-more-filters" type="button" aria-expanded={more} aria-controls={id}
        onClick={() => setMore(!more)}>{more ? `− ${l("less")}` : `+ ${l("more")}`}</button>}
    </div>
    {advanced && more && <div className="directory-filter-extra" id={id}>{advanced}</div>}
    {chips.length > 0 && <div className="directory-filter-applied" role="group" aria-label={l("selected")}>
      <div className="directory-filter-chips">{chips.map((chip, index) => <span className="directory-filter-chip" key={chip.key}>
        <span title={`${chip.label}: ${chip.value}`}>{chip.label}: <strong>{chip.value}</strong></span>
        <button type="button" aria-label={`${l("remove")} ${chip.label}`} onClick={() => remove(chip, index)}>×</button>
      </span>)}</div>
      <button className="small-button directory-filter-clear" type="button" onClick={() => { onClear(); focusFirst(); }}>{l("clear")}</button>
    </div>}
  </div>;
}
