import { useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent } from "react";
import { createPortal } from "react-dom";
import { localeFor, useI18n } from "../i18n";
import { addMonths, calendarDays, monthStart, parsePickerDate, toDateInput } from "./lib";
import "./date-range-picker.css";

type Range = { from: string; to: string };
type Side = keyof Range;

/** A range is committed atomically; dismissing the calendar leaves the filters intact. */
export function DateRangePicker({ fromLabel, toLabel, from, to, onChange, required = false, disabled = false }: Range & {
  fromLabel: string;
  toLabel: string;
  onChange: (range: Range) => void;
  required?: boolean;
  disabled?: boolean;
}) {
  const { language, t } = useI18n();
  const locale = localeFor(language);
  const id = useId();
  const fromRef = useRef<HTMLInputElement>(null), toRef = useRef<HTMLInputElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);
  const restoringFocus = useRef(false);
  const [open, setOpen] = useState(false);
  const [origin, setOrigin] = useState<Side>("from");
  const [step, setStep] = useState<Side | null>("from");
  const [draft, setDraft] = useState<Range>({ from, to });
  const [hover, setHover] = useState<string | null>(null);
  const [month, setMonth] = useState(() => monthStart(parsePickerDate(from, true) ?? new Date()));
  const [focusDate, setFocusDate] = useState(from || toDateInput(new Date()));
  const focusDayPending = useRef(false);
  const [position, setPosition] = useState({ left: 12, top: 12, width: 360 });
  const l = (es: string, en: string, zh: string) => language === "zh" ? zh : language === "en" ? en : es;
  const format = (value: string) => {
    const date = parsePickerDate(value, true);
    return date ? new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeZone: "UTC" }).format(date) : "";
  };
  const days = calendarDays(month);
  const fullDate = new Intl.DateTimeFormat(locale, { dateStyle: "full", timeZone: "UTC" });
  const weekDays = Array.from({ length: 7 }, (_, index) => new Intl.DateTimeFormat(locale, { weekday: "short", timeZone: "UTC" })
    .format(new Date(Date.UTC(2026, 0, 5 + index))).replace(".", ""));
  const end = step === "to" && hover ? hover : draft.to;
  const startHighlight = draft.from && end ? (draft.from < end ? draft.from : end) : draft.from;
  const endHighlight = draft.from && end ? (draft.from < end ? end : draft.from) : draft.from;
  const complete = Boolean(draft.from && draft.to && draft.from <= draft.to);

  function begin(side: Side) {
    if (disabled || restoringFocus.current) return;
    const date = parsePickerDate(side === "from" ? from : to, true) ?? parsePickerDate(from, true) ?? new Date();
    setDraft({ from, to }); setStep(side); setOrigin(side); setHover(null);
    setMonth(monthStart(date)); setFocusDate(toDateInput(date));
    focusDayPending.current = true;
    setOpen(true);
  }

  function close(restore = true) {
    setOpen(false); setHover(null);
    if (restore) {
      restoringFocus.current = true;
      (origin === "from" ? fromRef : toRef).current?.focus({ preventScroll: true });
      restoringFocus.current = false;
    }
  }

  useLayoutEffect(() => {
    if (!open) return;
    function positionPopover() {
      const anchor = (origin === "from" ? fromRef : toRef).current?.getBoundingClientRect();
      if (!anchor) return;
      const width = Math.min(360, window.innerWidth - 24);
      const height = Math.min(popoverRef.current?.scrollHeight ?? 440, window.innerHeight - 24);
      const below = anchor.bottom + 6;
      const top = below + height <= window.innerHeight - 12 ? below : Math.max(12, anchor.top - height - 6);
      setPosition({ width, left: Math.max(12, Math.min(anchor.left, window.innerWidth - width - 12)), top });
    }
    positionPopover();
    window.addEventListener("resize", positionPopover);
    window.addEventListener("scroll", positionPopover, true);
    return () => {
      window.removeEventListener("resize", positionPopover);
      window.removeEventListener("scroll", positionPopover, true);
    };
  }, [open, origin, language]);

  useLayoutEffect(() => {
    if (!open || !focusDayPending.current) return;
    focusDayPending.current = false;
    popoverRef.current?.querySelector<HTMLButtonElement>(`[data-date="${focusDate}"]`)?.focus({ preventScroll: true });
  }, [open, focusDate, month]);

  useEffect(() => {
    if (!open) return;
    const inside = (target: EventTarget | null) => target instanceof Node
      && (popoverRef.current?.contains(target) || fromRef.current === target || toRef.current === target);
    const outside = (event: Event) => { if (!inside(event.target)) close(false); };
    const escape = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); close(); }
    };
    document.addEventListener("pointerdown", outside);
    document.addEventListener("focusin", outside);
    document.addEventListener("keydown", escape);
    return () => {
      document.removeEventListener("pointerdown", outside);
      document.removeEventListener("focusin", outside);
      document.removeEventListener("keydown", escape);
    };
  }, [open, origin]);

  function select(value: string) {
    if (step !== "to" || !draft.from) {
      setDraft({ from: value, to: "" }); setStep("to");
    } else {
      setDraft(value < draft.from ? { from: value, to: draft.from } : { from: draft.from, to: value });
      setStep(null);
    }
    setHover(null); setFocusDate(value);
    const day = parsePickerDate(value, true)!;
    if (day.getUTCMonth() !== month.getUTCMonth() || day.getUTCFullYear() !== month.getUTCFullYear()) {
      focusDayPending.current = true; setMonth(monthStart(day));
    }
  }

  function navigate(event: KeyboardEvent<HTMLButtonElement>, day: Date) {
    let next = new Date(day);
    const weekday = (day.getUTCDay() + 6) % 7;
    const offset = ({ ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7, Home: -weekday, End: 6 - weekday } as Record<string, number>)[event.key];
    if (offset !== undefined) next.setUTCDate(next.getUTCDate() + offset);
    else if (event.key === "PageUp" || event.key === "PageDown") {
      next = addMonths(day, event.key === "PageUp" ? -1 : 1);
      const last = new Date(Date.UTC(next.getUTCFullYear(), next.getUTCMonth() + 1, 0)).getUTCDate();
      next.setUTCDate(Math.min(day.getUTCDate(), last));
    } else return;
    event.preventDefault();
    focusDayPending.current = true;
    setFocusDate(toDateInput(next)); setMonth(monthStart(next));
    if (step === "to") setHover(toDateInput(next));
  }

  function changeMonth(offset: number) {
    const next = addMonths(month, offset);
    setMonth(next); setFocusDate(toDateInput(next)); setHover(null);
  }

  function changeSide(side: Side) {
    setStep(side); setHover(null);
    const date = parsePickerDate(draft[side], true) ?? parsePickerDate(draft.from, true);
    if (date) { setMonth(monthStart(date)); setFocusDate(toDateInput(date)); }
  }

  const trigger = (side: Side, label: string, value: string) => <div className="date-time-picker">
    <label>{label}<input ref={side === "from" ? fromRef : toRef} className="control-input date-time-trigger"
      value={format(value)} readOnly required={required} disabled={disabled}
      aria-haspopup="dialog" aria-expanded={open} aria-controls={open ? id : undefined}
      onFocus={() => begin(side)} onClick={() => { if (!open) begin(side); }}
      onKeyDown={event => { if (["ArrowDown", "Enter", " "].includes(event.key)) { event.preventDefault(); begin(side); } }} />
    </label>
  </div>;

  return <>
    <div className="date-range-fields" role="group" aria-label={`${fromLabel} – ${toLabel}`}>
      {trigger("from", fromLabel, from)}{trigger("to", toLabel, to)}
    </div>
    {open && createPortal(<div ref={popoverRef} id={id} className="date-time-popover date-range-popover"
      style={position} role="dialog" aria-label={`${fromLabel} – ${toLabel}`} aria-describedby={`${id}-help`}>
      <div className="date-range-endpoints">
        {(["from", "to"] as const).map(side => <button key={side} type="button" aria-pressed={step === side}
          className={step === side ? "active" : ""} onClick={() => changeSide(side)}>
          <span>{side === "from" ? fromLabel : toLabel}</span><strong>{format(draft[side]) || "—"}</strong>
        </button>)}
      </div>
      <p id={`${id}-help`} className="date-range-help" aria-live="polite">{step === "from"
        ? l("Selecciona la fecha de inicio.", "Select the start date.", "请选择开始日期。")
        : step === "to" ? l("Ahora selecciona la fecha de fin.", "Now select the end date.", "现在请选择结束日期。")
          : l("Período seleccionado", "Selected period", "已选择日期范围")}</p>
      <div className="date-time-calendar-header">
        <button type="button" onClick={() => changeMonth(-1)} aria-label={t("previousMonth")}>‹</button>
        <strong aria-live="polite">{new Intl.DateTimeFormat(locale, { month: "long", year: "numeric", timeZone: "UTC" }).format(month)}</strong>
        <button type="button" onClick={() => changeMonth(1)} aria-label={t("nextMonth")}>›</button>
      </div>
      <div className="date-time-weekdays" aria-hidden="true">{weekDays.map((day, index) => <span key={index}>{day}</span>)}</div>
      <div className="date-time-days date-range-days" role="grid" aria-label={l("Seleccionar período", "Select period", "选择日期范围")}
        aria-multiselectable="true" onMouseLeave={() => setHover(null)}>
        {Array.from({ length: 6 }, (_, week) => <div className="date-range-week" role="row" key={week}>
          {days.slice(week * 7, week * 7 + 7).map(day => {
            const value = toDateInput(day);
            const inRange = Boolean(startHighlight && value >= startHighlight && value <= endHighlight);
            const isStart = value === startHighlight, isEnd = value === endHighlight;
            return <div role="gridcell" aria-selected={inRange} key={value}><button type="button" data-date={value}
              data-in-range={inRange} data-range-start={isStart} data-range-end={isEnd}
              tabIndex={focusDate === value ? 0 : -1} aria-label={fullDate.format(day)}
              aria-current={value === toDateInput(new Date()) ? "date" : undefined}
              className={`${day.getUTCMonth() !== month.getUTCMonth() ? "outside" : ""} ${inRange ? "in-range" : ""} ${isStart || isEnd ? "range-endpoint" : ""}`}
              onClick={() => select(value)} onKeyDown={event => navigate(event, day)}
              onFocus={() => setFocusDate(value)} onMouseEnter={() => { if (step === "to") setHover(value); }}>
              {day.getUTCDate()}</button></div>;
          })}
        </div>)}
      </div>
      <div className="date-range-actions">
        <button type="button" className="secondary-button" onClick={() => close()}>{l("Cancelar", "Cancel", "取消")}</button>
        <button type="button" className="primary-button" disabled={!complete} onClick={() => { onChange(draft); close(); }}>{l("Aceptar", "Apply", "确定")}</button>
      </div>
    </div>, document.body)}
  </>;
}
