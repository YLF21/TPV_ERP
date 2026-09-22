import { useEffect, useMemo, useRef, useState } from "react";

import { paginateRows } from "../lib/frontend-runtime.mjs";
import type { FiscalAddress } from "../lib/types";
import { useI18n, activeLocale, LANGUAGE_OPTIONS, Language } from "../i18n/index";
import { parsePickerDate, monthStart, calendarDays, calendarWeekDays, toDateInput, toLocalInput, formatPickerDate, addMonths, sameCalendarDay } from "./lib";
import { ProvinceSelect } from "./provinces/ProvinceSelect";
import { EuropeanCountrySelect } from "./countries/EuropeanCountrySelect";

export function NavButton({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button className={active ? "nav-button active" : "nav-button"} type="button" aria-current={active ? "page" : undefined} onClick={onClick}>
      {label}
    </button>
  );
}

export function Metric({ label, value, detail, tone }: { label: string; value: number | string; detail?: string; tone?: "warning" }) {
  return (
    <article className={tone === "warning" ? "metric warning" : "metric"}>
      <span>{label}</span>
      <strong>{value}</strong>
      {detail && <small>{detail}</small>}
    </article>
  );
}

export function SectionHeader({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <div className="section-header">
      <div>
        <h2>{title}</h2>
        <p>{subtitle}</p>
      </div>
    </div>
  );
}

export function EmptyState({ text }: { text: string }) {
  return <div className="empty-state">{text}</div>;
}

export function RetryError({ message, onRetry }: { message: string; onRetry: () => void }) {
  const { t } = useI18n();
  return <div className="notice error retry-error" role="alert"><span>{message}</span><button className="small-button" type="button" onClick={onRetry}>{t("retry")}</button></div>;
}

export function usePagination<T>(rows: T[], pageSize = 20) {
  const [page, setPage] = useState(1);
  const pagination = paginateRows(rows, page, pageSize);
  useEffect(() => setPage((current) => Math.min(current, pagination.pages)), [pagination.pages]);
  return { ...pagination, setPage };
}

export function PaginationControls({ page, pages, total, pageSize, setPage }: ReturnType<typeof usePagination<unknown>>) {
  const { t } = useI18n();
  if (total <= pageSize) return null;
  return <nav className="pagination-controls" aria-label={t("pageLabel")}>
    <button className="small-button" type="button" disabled={page <= 1} onClick={() => setPage(page - 1)}>{t("previousPage")}</button>
    <span>{t("pageLabel")} {page} / {pages} | {total}</span>
    <button className="small-button" type="button" disabled={page >= pages} onClick={() => setPage(page + 1)}>{t("nextPage")}</button>
  </nav>;
}

export function StatusPill({ status, tone }: { status: string; tone: "ok" | "warning" | "muted" }) {
  return <span className={`status-pill ${tone}`}>{status}</span>;
}

export function Input({
  label,
  value,
  onChange,
  type = "text",
  required,
  min,
  minLength,
  maxLength,
  step,
  disabled
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  required?: boolean;
  min?: number;
  minLength?: number;
  maxLength?: number;
  step?: string;
  disabled?: boolean;
}) {
  if (type === "date" || type === "datetime-local") {
    return (
      <DateTimePicker
        label={label}
        value={value}
        onChange={onChange}
        required={required}
        disabled={disabled}
        dateOnly={type === "date"}
      />
    );
  }

  return (
    <label>
      {label}
      <input
        className="control-input"
        type={type}
        value={value}
        min={min}
        minLength={minLength}
        maxLength={maxLength}
        step={step}
        onChange={(event) => onChange(event.target.value)}
        required={required}
        disabled={disabled}
      />
    </label>
  );
}

export function DateTimePicker({ label, value, onChange, required, disabled, dateOnly = false }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  disabled?: boolean;
  dateOnly?: boolean;
}) {
  const { t } = useI18n();
  const containerRef = useRef<HTMLDivElement | null>(null);
  const selectedDate = parsePickerDate(value, dateOnly);
  const [open, setOpen] = useState(false);
  const [visibleMonth, setVisibleMonth] = useState(() => monthStart(selectedDate ?? new Date()));

  useEffect(() => {
    if (selectedDate) setVisibleMonth(monthStart(selectedDate));
  }, [value]);

  useEffect(() => {
    if (!open) return;
    function close(event: MouseEvent | KeyboardEvent) {
      if (event instanceof KeyboardEvent && event.key === "Escape") setOpen(false);
      if (event instanceof MouseEvent && !containerRef.current?.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", close);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", close);
    };
  }, [open]);

  const days = useMemo(() => calendarDays(visibleMonth), [visibleMonth]);
  const weekDays = useMemo(() => calendarWeekDays(), []);

  function selectDate(day: Date) {
    const time = selectedDate ?? new Date();
    const next = new Date(day.getFullYear(), day.getMonth(), day.getDate(), time.getHours(), time.getMinutes());
    onChange(dateOnly ? toDateInput(next) : toLocalInput(next));
  }

  function selectTime(time: string) {
    const [hours, minutes] = time.split(":").map(Number);
    const next = selectedDate ?? new Date();
    next.setHours(hours, minutes, 0, 0);
    onChange(toLocalInput(next));
  }

  function selectToday() {
    const now = new Date();
    setVisibleMonth(monthStart(now));
    onChange(dateOnly ? toDateInput(now) : toLocalInput(now));
  }

  return (
    <div className="date-time-picker" ref={containerRef}>
      <label>
        {label}
        <input
          className="control-input date-time-trigger"
          value={formatPickerDate(value, dateOnly)}
          readOnly
          required={required}
          disabled={disabled}
          aria-haspopup="dialog"
          aria-expanded={open}
          onFocus={() => !disabled && setOpen(true)}
          onClick={() => !disabled && setOpen(true)}
        />
      </label>
      {open && (
        <div className="date-time-popover" role="dialog" aria-label={label}>
          <div className="date-time-calendar-header">
            <button type="button" onClick={() => setVisibleMonth(addMonths(visibleMonth, -1))} aria-label={t("previousMonth")}>‹</button>
            <strong>{new Intl.DateTimeFormat(activeLocale, { month: "long", year: "numeric" }).format(visibleMonth)}</strong>
            <button type="button" onClick={() => setVisibleMonth(addMonths(visibleMonth, 1))} aria-label={t("nextMonth")}>›</button>
          </div>
          <div className="date-time-weekdays" aria-hidden="true">
            {weekDays.map((day) => <span key={day}>{day}</span>)}
          </div>
          <div className="date-time-days" role="grid">
            {days.map((day) => {
              const selected = selectedDate ? sameCalendarDay(day, selectedDate) : false;
              return (
                <button
                  type="button"
                  key={day.toISOString()}
                  className={`${day.getMonth() === visibleMonth.getMonth() ? "" : "outside"} ${selected ? "selected" : ""} ${sameCalendarDay(day, new Date()) ? "today" : ""}`.trim()}
                  aria-pressed={selected}
                  onClick={() => selectDate(day)}
                >
                  {day.getDate()}
                </button>
              );
            })}
          </div>
          <div className={`date-time-footer ${dateOnly ? "date-only" : ""}`}>
            {!dateOnly && <label>{t("time")}<input type="time" value={value.slice(11, 16) || "00:00"} onChange={(event) => selectTime(event.target.value)} /></label>}
            <div>
              <button type="button" className="secondary-button" onClick={selectToday}>{t("today")}</button>
              <button type="button" className="primary-button" onClick={() => setOpen(false)}>{t("closeCalendar")}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export function ProjectionMetric({ label, value, warning = false }: { label: string; value: number | string; warning?: boolean }) {
  return (
    <div className={warning ? "projection-metric warning" : "projection-metric"}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

export function AddressFields({
  title,
  value,
  onChange,
  disabled = false,
  countryOptions
}: {
  title: string;
  value: FiscalAddress;
  onChange: (value: FiscalAddress) => void;
  disabled?: boolean;
  countryOptions?: "europe";
}) {
  const { t } = useI18n();
  const update = (field: keyof FiscalAddress, next: string) => onChange({ ...value, [field]: next });
  return (
    <fieldset className="address-fields">
      <legend>{title}</legend>
      <Input label={t("addressLine")} value={value.linea1} onChange={(next) => update("linea1", next)} disabled={disabled} required />
      <Input label={t("city")} value={value.ciudad} onChange={(next) => update("ciudad", next)} disabled={disabled} required />
      <Input label={t("postalCode")} value={value.codigoPostal} onChange={(next) => update("codigoPostal", next)} disabled={disabled} required />
      {countryOptions === "europe" && value.pais.trim().toUpperCase() !== "ES"
        ? <Input label={t("province")} value={value.provincia} onChange={next => update("provincia", next)} disabled={disabled} required />
        : <ProvinceSelect value={value.provincia} onChange={(next) => update("provincia", next)} disabled={disabled} />}
      {countryOptions === "europe"
        ? <EuropeanCountrySelect value={value.pais} onChange={next => update("pais", next)} disabled={disabled} />
        : <Input label={t("country")} value={value.pais} onChange={(next) => update("pais", next)} disabled={disabled} required />}
    </fieldset>
  );
}

export function Select({
  label,
  value,
  options,
  onChange,
  disabled,
  emptyLabel
}: {
  label: string;
  value: string;
  options: string[];
  onChange: (value: string) => void;
  disabled?: boolean;
  emptyLabel?: string;
}) {
  return (
    <label>
      {label}
      <select className="control-input" value={value} onChange={(event) => onChange(event.target.value)} disabled={disabled}>
        {options.map((option) => (
          <option key={option} value={option}>
            {option || emptyLabel || option}
          </option>
        ))}
      </select>
    </label>
  );
}

export function Segmented({ value, options, onChange }: { value: string; options: [string, string][]; onChange: (value: string) => void }) {
  return (
    <div className="segmented">
      {options.map(([optionValue, label]) => (
        <button key={optionValue} className={value === optionValue ? "active" : ""} type="button" onClick={() => onChange(optionValue)}>
          {label}
        </button>
      ))}
    </div>
  );
}

export function LanguageSelector({ variant = "sidebar" }: { variant?: "sidebar" | "floating" }) {
  const { language, setLanguage, t } = useI18n();
  const [open, setOpen] = useState(false);
  const selectorRef = useRef<HTMLDivElement | null>(null);
  const menuId = useMemo(() => `language-menu-${variant}`, [variant]);
  const current = LANGUAGE_OPTIONS.find((option) => option.value === language) ?? LANGUAGE_OPTIONS[0];
  const isFloating = variant === "floating";

  useEffect(() => {
    function dismiss(event: MouseEvent | KeyboardEvent) {
      if (event instanceof KeyboardEvent && event.key === "Escape" && selectorRef.current?.querySelector(".language-menu")) { setOpen(false); selectorRef.current.querySelector<HTMLButtonElement>(".language-trigger")?.focus(); }
      if (event instanceof MouseEvent && !selectorRef.current?.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", dismiss);
    document.addEventListener("keydown", dismiss);
    return () => { document.removeEventListener("mousedown", dismiss); document.removeEventListener("keydown", dismiss); };
  }, []);

  function choose(nextLanguage: Language) {
    setLanguage(nextLanguage);
    setOpen(false);
  }

  return (
    <div ref={selectorRef} className={`language-selector language-selector-${variant}`}>
      {!isFloating && <span>{t("language")}</span>}
      <button className="language-trigger" type="button" onClick={() => setOpen((value) => !value)} aria-expanded={open} aria-haspopup="true" aria-controls={menuId} aria-label={t("language")}>
        {isFloating ? (
          <svg className="language-globe" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <circle cx="12" cy="12" r="9" />
            <path d="M3 12h18" />
            <path d="M12 3a13 13 0 0 1 0 18" />
            <path d="M12 3a13 13 0 0 0 0 18" />
          </svg>
        ) : (
          <>
            {current.label}
            <span aria-hidden="true">⌄</span>
          </>
        )}
      </button>
      {open && (
        <div id={menuId} className="language-menu" role="group" aria-label={t("language")}>
          {LANGUAGE_OPTIONS.map((option) => (
            <button
              key={option.value}
              className={option.value === language ? "active" : ""}
              type="button"
              aria-pressed={option.value === language}
              onClick={() => choose(option.value)}
            >
              <span>{option.label}</span>
              <small>{option.short}</small>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
