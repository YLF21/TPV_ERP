/** SaaS operators view and enter instants in UTC, independently of the browser zone. */
export function formatUtcDateTime(value, locale, seconds = false) {
  const date = value instanceof Date ? value : new Date(value);
  return `${new Intl.DateTimeFormat(locale, {
    timeZone: "UTC", year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", ...(seconds ? { second: "2-digit" } : {}),
    hour12: false,
  }).format(date).replace(", ", " ")} UTC`;
}

export function formatSaasDate(value, locale) {
  // Civil dates (e.g. activation dates) have no time or zone to convert.
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    const date = parseUtcDateOnly(value);
    if (!date) throw new RangeError("Invalid calendar date");
    return new Intl.DateTimeFormat(locale, {
      timeZone: "UTC", year: "numeric", month: "2-digit", day: "2-digit",
    }).format(date);
  }
  return formatUtcDateTime(value, locale);
}

export function toUtcInput(date) {
  return date.toISOString().slice(0, 16);
}

export function parseUtcDateTime(value) {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return null;
  const date = new Date(`${value}:00.000Z`);
  return Number.isFinite(date.getTime()) && toUtcInput(date) === value ? date : null;
}

export function utcInputToInstant(value) {
  const date = parseUtcDateTime(value);
  if (!date) throw new RangeError("Invalid UTC date and time");
  return date.toISOString();
}

export function parseUtcDateOnly(value) {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) ? parseUtcDateTime(`${value}T00:00`) : null;
}

export function parsePickerDate(value, dateOnly) {
  return dateOnly ? parseUtcDateOnly(value) : parseUtcDateTime(value);
}

export function toDateInput(date) {
  return date.toISOString().slice(0, 10);
}

export function monthStart(date) {
  const next = new Date(date);
  next.setUTCDate(1);
  next.setUTCHours(0, 0, 0, 0);
  return next;
}

export function addMonths(date, amount) {
  const next = monthStart(date);
  next.setUTCMonth(next.getUTCMonth() + amount);
  return next;
}

export function calendarDays(month) {
  const first = monthStart(month);
  first.setUTCDate(1 - (first.getUTCDay() + 6) % 7);
  return Array.from({ length: 42 }, (_, index) => addDays(first, index));
}

export function sameCalendarDay(left, right) {
  return left.getUTCFullYear() === right.getUTCFullYear()
    && left.getUTCMonth() === right.getUTCMonth()
    && left.getUTCDate() === right.getUTCDate();
}

export function addYears(date, years) {
  const next = new Date(date);
  next.setUTCFullYear(next.getUTCFullYear() + years);
  return next;
}

export function addDays(date, days) {
  const next = new Date(date);
  next.setUTCDate(next.getUTCDate() + days);
  return next;
}
