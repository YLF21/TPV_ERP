import { useId, useMemo, useState } from "react";
import { createSalesActivityTranslator } from "../i18n/SalesActivityMessages";
import { localeTag } from "../money";
import type { LocaleCode } from "../types";
import "./ReportDateRangeFilter.css";

export type ReportRangePreset = "TODAY" | "YESTERDAY" | "WEEK" | "MONTH" | "QUARTER" | "YEAR" | "CUSTOM";
export type ReportDateRange = { from: string; to: string; label: string; preset?: ReportRangePreset };

function localDate(value: string) {
  const [year, month, day] = value.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function isoDate(value: Date) {
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, "0")}-${String(value.getDate()).padStart(2, "0")}`;
}

export function isValidReportDate(value: unknown): value is string {
  return typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value)
    && isoDate(localDate(value)) === value;
}

export function reportDateRangeLabel(value: ReportDateRange, locale: LocaleCode) {
  const t = createSalesActivityTranslator(locale);
  if (!isValidReportDate(value.from) || !isValidReportDate(value.to)) return value.label;
  const from = localDate(value.from);
  switch (value.preset) {
    case "TODAY": return t("today");
    case "YESTERDAY": return t("yesterday");
    case "WEEK": return t("week");
    case "MONTH": return new Intl.DateTimeFormat(localeTag(locale), { month: "long", year: "numeric" }).format(from);
    case "QUARTER": return `${t("quarter")} ${Math.floor(from.getMonth() / 3) + 1} ${from.getFullYear()}`;
    case "YEAR": return String(from.getFullYear());
    default: return `${new Intl.DateTimeFormat(localeTag(locale)).format(from)} — ${new Intl.DateTimeFormat(localeTag(locale)).format(localDate(value.to))}`;
  }
}

export function ReportDateRangeFilter({ locale, today, earliestDate, value, onChange, disabled = false }: {
  locale: LocaleCode;
  today: string;
  earliestDate: string;
  value: ReportDateRange;
  onChange: (range: ReportDateRange) => void;
  disabled?: boolean;
}) {
  const t = createSalesActivityTranslator(locale);
  const customId = useId();
  const [customOpen, setCustomOpen] = useState(false);
  const [draftFrom, setDraftFrom] = useState("");
  const [draftTo, setDraftTo] = useState("");
  const ready = !disabled && isValidReportDate(today) && isValidReportDate(earliestDate);
  const options = useMemo(() => {
    const months: Array<{ value: string; label: string }> = [];
    const quarters: Array<{ value: string; label: string }> = [];
    const years: string[] = [];
    if (!isValidReportDate(today) || !isValidReportDate(earliestDate)) return { months, quarters, years };
    const current = localDate(today);
    const first = localDate(earliestDate > today ? today : earliestDate);
    const cursor = new Date(current.getFullYear(), current.getMonth(), 1);
    while (cursor >= new Date(first.getFullYear(), first.getMonth(), 1)) {
      months.push({ value: isoDate(cursor).slice(0, 7),
        label: new Intl.DateTimeFormat(localeTag(locale), { month: "long", year: "numeric" }).format(cursor) });
      cursor.setMonth(cursor.getMonth() - 1);
    }
    for (let quarter = current.getFullYear() * 4 + Math.floor(current.getMonth() / 3);
      quarter >= first.getFullYear() * 4 + Math.floor(first.getMonth() / 3); quarter--) {
      const year = Math.floor(quarter / 4);
      const number = quarter % 4 + 1;
      quarters.push({ value: `${year}-Q${number}`, label: `${t("quarter")} ${number} ${year}` });
    }
    for (let year = current.getFullYear(); year >= first.getFullYear(); year--) years.push(String(year));
    return { months, quarters, years };
  }, [today, earliestDate, locale]);

  function select(from: string, to: string, preset: ReportRangePreset) {
    if (!ready || !isValidReportDate(from) || !isValidReportDate(to) || from > to || to > today) return;
    onChange({ from, to, preset, label: "" });
    setCustomOpen(false);
  }

  function selectDay(offset: number) {
    const date = localDate(today);
    date.setDate(date.getDate() + offset);
    const iso = isoDate(date);
    select(iso, iso, offset === 0 ? "TODAY" : "YESTERDAY");
  }

  function selectWeek() {
    const start = localDate(today);
    start.setDate(start.getDate() - ((start.getDay() + 6) % 7));
    select(isoDate(start), today, "WEEK");
  }

  function selectPeriod(period: string, preset: "MONTH" | "QUARTER" | "YEAR") {
    if (!period) return;
    const year = Number(period.slice(0, 4));
    const startMonth = preset === "YEAR" ? 0 : preset === "QUARTER" ? (Number(period.slice(-1)) - 1) * 3 : Number(period.slice(-2)) - 1;
    const length = preset === "YEAR" ? 12 : preset === "QUARTER" ? 3 : 1;
    const from = isoDate(new Date(year, startMonth, 1));
    const end = isoDate(new Date(year, startMonth + length, 0));
    select(from, end > today ? today : end, preset);
  }

  const validDraft = ready && isValidReportDate(draftFrom) && isValidReportDate(draftTo)
    && draftFrom <= draftTo && draftTo <= today;
  const selectedMonth = value.preset === "MONTH" ? value.from.slice(0, 7) : "";
  const selectedQuarter = value.preset === "QUARTER" && isValidReportDate(value.from)
    ? `${value.from.slice(0, 4)}-Q${Math.floor(localDate(value.from).getMonth() / 3) + 1}` : "";
  const selectedYear = value.preset === "YEAR" ? value.from.slice(0, 4) : "";

  return <footer className="report-date-range-filter sales-activity-filter-dock" aria-label={t("currentPeriod")}>
    <button type="button" disabled={!ready} aria-pressed={value.preset === "TODAY"} className={value.preset === "TODAY" ? "selected" : ""} onClick={() => selectDay(0)}>{t("today")}</button>
    <button type="button" disabled={!ready} aria-pressed={value.preset === "YESTERDAY"} className={value.preset === "YESTERDAY" ? "selected" : ""} onClick={() => selectDay(-1)}>{t("yesterday")}</button>
    <button type="button" disabled={!ready} aria-pressed={value.preset === "WEEK"} className={value.preset === "WEEK" ? "selected" : ""} onClick={selectWeek}>{t("week")}</button>
    <label><span>{t("month")}</span><select disabled={!ready} value={selectedMonth} onChange={(event) => selectPeriod(event.target.value, "MONTH")}><option value="">—</option>{options.months.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
    <label><span>{t("quarter")}</span><select disabled={!ready} value={selectedQuarter} onChange={(event) => selectPeriod(event.target.value, "QUARTER")}><option value="">—</option>{options.quarters.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
    <label><span>{t("year")}</span><select disabled={!ready} value={selectedYear} onChange={(event) => selectPeriod(event.target.value, "YEAR")}><option value="">—</option>{options.years.map((year) => <option key={year} value={year}>{year}</option>)}</select></label>
    <button type="button" disabled={!ready} aria-expanded={customOpen} aria-controls={customId} onClick={() => {
      setDraftFrom(value.from); setDraftTo(value.to); setCustomOpen((open) => !open);
    }}>{t("custom")}</button>
    {customOpen && <form id={customId} className="report-date-custom sales-activity-custom-period" onSubmit={(event) => {
      event.preventDefault(); if (validDraft) select(draftFrom, draftTo, "CUSTOM");
    }} onKeyDown={(event) => {
      if (event.key === "Escape") { event.stopPropagation(); setCustomOpen(false); }
    }}>
      <label><span>{t("from")}</span><input type="date" max={today} value={draftFrom} onChange={(event) => setDraftFrom(event.target.value)} /></label>
      <label><span>{t("to")}</span><input type="date" max={today} value={draftTo} onChange={(event) => setDraftTo(event.target.value)} /></label>
      <button type="submit" disabled={!validDraft}>{t("apply")}</button>
    </form>}
  </footer>;
}
