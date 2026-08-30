/**
 * Converts a datetime-local wall-clock value to an instant using the store's
 * IANA timezone. It deliberately does not use the browser timezone.
 */
export type FiscalDateTimeCandidate = { iso: string; offsetMinutes: number; offsetLabel: string };

export function datetimeLocalToIso(value: string, timeZone: string, explicitOffsetMinutes?: number): string {
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

  const desired = { year, month, day, hour, minute, second, millisecond };
  const candidates = candidatesForWallClock(naive, desired, timeZone);
  if (candidates.length === 0) {
    throw new Error("nonexistent_fiscal_local_datetime");
  }
  if (explicitOffsetMinutes == null && candidates.length > 1) {
    throw new Error("ambiguous_fiscal_local_datetime");
  }
  const selected = explicitOffsetMinutes == null
    ? candidates[0]
    : candidates.find((candidate) => candidate.offsetMinutes === explicitOffsetMinutes);
  if (!selected) throw new Error("invalid_fiscal_local_offset");
  const resolved = naive - selected.offsetMinutes * 60_000;
  return new Date(resolved).toISOString();
}

export function isValidDatetimeLocal(value: string, timeZone: string, explicitOffsetMinutes?: number): boolean {
  try {
    datetimeLocalToIso(value, timeZone, explicitOffsetMinutes);
    return true;
  } catch {
    return false;
  }
}

export function datetimeLocalCandidates(value: string, timeZone: string): FiscalDateTimeCandidate[] {
  try {
    const match = value.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?$/);
    if (!match || !timeZone) return [];
    const [, yearText, monthText, dayText, hourText, minuteText, secondText = "00", fractionText = ""] = match;
    const desired = { year: Number(yearText), month: Number(monthText), day: Number(dayText), hour: Number(hourText), minute: Number(minuteText), second: Number(secondText), millisecond: Number(fractionText.padEnd(3, "0") || "0") };
    const naive = Date.UTC(desired.year, desired.month - 1, desired.day, desired.hour, desired.minute, desired.second, desired.millisecond);
    if (!sameParts(new Date(naive), desired, "UTC")) return [];
    return candidatesForWallClock(naive, desired, timeZone).map((candidate) => ({
      iso: new Date(naive - candidate.offsetMinutes * 60_000).toISOString(),
      offsetMinutes: candidate.offsetMinutes,
      offsetLabel: formatOffset(candidate.offsetMinutes)
    }));
  } catch {
    return [];
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

function candidatesForWallClock(naive: number, desired: DateParts, timeZone: string) {
  const offsets = new Set<number>();
  for (const delta of [-172800000, -86400000, 0, 86400000, 172800000]) {
    const instant = naive + delta;
    offsets.add(wallClockAsUtc(formatParts(instant, timeZone)) - instant);
  }
  return [...offsets]
    .map((offset) => ({ offsetMinutes: Math.round(offset / 60000) }))
    .filter(({ offsetMinutes }) => sameParts(new Date(naive - offsetMinutes * 60000), desired, timeZone))
    .sort((left, right) => left.offsetMinutes - right.offsetMinutes);
}

function formatOffset(offsetMinutes: number) {
  const sign = offsetMinutes >= 0 ? "+" : "-";
  const absolute = Math.abs(offsetMinutes);
  return `UTC${sign}${String(Math.floor(absolute / 60)).padStart(2, "0")}:${String(absolute % 60).padStart(2, "0")}`;
}

function sameParts(date: Date, desired: DateParts, timeZone: string) {
  const actual = timeZone === "UTC"
    ? { year: date.getUTCFullYear(), month: date.getUTCMonth() + 1, day: date.getUTCDate(), hour: date.getUTCHours(), minute: date.getUTCMinutes(), second: date.getUTCSeconds(), millisecond: date.getUTCMilliseconds() }
    : formatParts(date.getTime(), timeZone);
  return Object.keys(desired).every((key) => actual[key as keyof DateParts] === desired[key as keyof DateParts]);
}
