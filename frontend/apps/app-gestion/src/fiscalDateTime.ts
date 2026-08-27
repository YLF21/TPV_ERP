/**
 * Converts a datetime-local wall-clock value to an instant using the store's
 * IANA timezone. It deliberately does not use the browser timezone.
 */
export function datetimeLocalToIso(value: string, timeZone: string): string {
  const match = value.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?$/);
  if (!match || !timeZone) throw new Error("invalid_fiscal_local_datetime");

  const [, yearText, monthText, dayText, hourText, minuteText, secondText = "00", fractionText = ""] = match;
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const hour = Number(hourText);
  const minute = Number(minuteText);
  const second = Number(secondText);
  const millisecond = Number(fractionText.padEnd(3, "0") || "0");
  const naive = Date.UTC(year, month - 1, day, hour, minute, second, millisecond);
  if (!Number.isFinite(naive) || !sameParts(new Date(naive), { year, month, day, hour, minute, second, millisecond }, "UTC")) {
    throw new Error("invalid_fiscal_local_datetime");
  }

  // Resolve the offset with the timezone itself, then verify the exact wall
  // clock. Verification rejects nonexistent DST times instead of silently
  // normalising them (which Date normally does).
  const desired = { year, month, day, hour, minute, second, millisecond };
  const firstOffset = wallClockAsUtc(formatParts(naive, timeZone)) - naive;
  const candidate = naive - firstOffset;
  const secondOffset = wallClockAsUtc(formatParts(candidate, timeZone)) - candidate;
  const resolved = naive - secondOffset;
  if (!sameParts(new Date(resolved), desired, timeZone)) {
    throw new Error("nonexistent_fiscal_local_datetime");
  }
  return new Date(resolved).toISOString();
}

export function isValidDatetimeLocal(value: string, timeZone: string): boolean {
  try {
    datetimeLocalToIso(value, timeZone);
    return true;
  } catch {
    return false;
  }
}

type DateParts = { year: number; month: number; day: number; hour: number; minute: number; second: number; millisecond: number };

function formatParts(instant: number, timeZone: string): DateParts {
  const formatter = new Intl.DateTimeFormat("en-CA", {
    timeZone,
    calendar: "gregory",
    numberingSystem: "latn",
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  });
  const parts = Object.fromEntries(formatter.formatToParts(new Date(instant))
    .filter(({ type }) => type !== "literal")
    .map(({ type, value }) => [type, Number(value)]));
  return {
    year: parts.year,
    month: parts.month,
    day: parts.day,
    hour: parts.hour,
    minute: parts.minute,
    second: parts.second,
    millisecond: new Date(instant).getUTCMilliseconds()
  };
}

function wallClockAsUtc(parts: DateParts) {
  return Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute, parts.second, parts.millisecond);
}

function sameParts(date: Date, desired: DateParts, timeZone: string) {
  const actual = timeZone === "UTC"
    ? { year: date.getUTCFullYear(), month: date.getUTCMonth() + 1, day: date.getUTCDate(), hour: date.getUTCHours(), minute: date.getUTCMinutes(), second: date.getUTCSeconds(), millisecond: date.getUTCMilliseconds() }
    : formatParts(date.getTime(), timeZone);
  return Object.keys(desired).every((key) => actual[key as keyof DateParts] === desired[key as keyof DateParts]);
}
