import { useRef, type RefObject } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import "./ErpFilterChips.css";

export type ErpFilterChip = { key: string; label: string; value: string; onRemove: () => void; removeLabel?: string };

/** Applied criteria only: callers own queries, defaults and draft synchronization. */
export function ErpFilterChips({ chips, onClear, focusRef, className = "", locale = "es", translate }: {
  chips: readonly ErpFilterChip[];
  onClear?: () => void;
  focusRef?: RefObject<HTMLElement | null>;
  className?: string;
  locale?: LocaleCode;
  translate?: (key: string) => string;
}) {
  const root = useRef<HTMLDivElement>(null);
  const t = translate ?? createTranslator(locale);
  const visible = chips.filter(chip => chip.value.trim() !== "");

  function fallbackTarget() {
    if (focusRef?.current) return focusRef.current;
    let parent = root.current?.parentElement;
    while (parent) {
      const target = parent.querySelector<HTMLElement>("input:not(:disabled):not([type=hidden]), select:not(:disabled), .erp-select__trigger:not(:disabled)");
      if (target) return target;
      if (parent.matches('[role="dialog"], main')) break;
      parent = parent.parentElement;
    }
    return null;
  }

  function remove(chip: ErpFilterChip, index: number) {
    const fallback = fallbackTarget();
    chip.onRemove();
    requestAnimationFrame(() => {
      const remaining = root.current?.querySelectorAll<HTMLButtonElement>(".erp-filter-chip button");
      const target = remaining?.[Math.min(index, remaining.length - 1)] ?? fallback;
      if (target?.isConnected) target.focus();
    });
  }

  if (visible.length === 0) return null;
  return <div ref={root} className={`erp-filter-applied ${className}`} role="group" aria-label={t("filters.applied")}
    onKeyDown={event => { if (event.key === "Enter" || event.key === " ") event.stopPropagation(); }}>
    <div className="erp-filter-chips">{visible.map((chip, index) => <span className="erp-filter-chip" key={chip.key}>
      <span title={`${chip.label}: ${chip.value}`}>{chip.label}: <strong>{chip.value}</strong></span>
      <button type="button" aria-label={chip.removeLabel ?? `${t("filters.remove")} ${chip.label}`} title={chip.removeLabel} onClick={event => {
        event.stopPropagation(); remove(chip, index);
      }}>×</button>
    </span>)}</div>
    {onClear && <button className="erp-filter-clear" type="button" onClick={event => {
      event.stopPropagation();
      const fallback = fallbackTarget();
      onClear();
      requestAnimationFrame(() => { if (fallback?.isConnected) fallback.focus(); });
    }}>{t("filters.clearAll")}</button>}
  </div>;
}
