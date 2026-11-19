const day = 86_400_000;
const iso = value => new Date(value).toISOString().slice(0, 10);
export const periodPresets = ["TODAY", "YESTERDAY", "WEEK", "LAST_30", "MONTH", "LAST_MONTH", "YEAR"];
export function periodPreset(preset, now = new Date()) {
  const to = now.toISOString().slice(0, 10);
  const end = Date.parse(to + "T00:00:00Z");
  if (preset === "TODAY") return { from: to, to };
  if (preset === "YESTERDAY") return { from: iso(end - day), to: iso(end - day) };
  if (preset === "WEEK" || preset === "LAST_30") return { from: iso(end - (preset === "WEEK" ? 6 : 29) * day), to };
  if (preset === "MONTH") return { from: to.slice(0, 8) + "01", to };
  if (preset === "YEAR") return { from: to.slice(0, 5) + "01-01", to };
  if (preset === "LAST_MONTH") {
    const first = new Date(to.slice(0, 8) + "01T00:00:00Z");
    return { from: iso(Date.UTC(first.getUTCFullYear(), first.getUTCMonth() - 1, 1)), to: iso(first.getTime() - day) };
  }
  return null;
}
export function matchingPeriod(from, to, now = new Date()) {
  return periodPresets.find(key => { const range = periodPreset(key, now); return range.from === from && range.to === to; }) ?? "";
}
export function adjacentPeriod(from, to, direction) {
  const start = new Date(from + "T00:00:00Z"), end = new Date(to + "T00:00:00Z");
  const days = Math.round((end.getTime() - start.getTime()) / day) + 1;
  if (!Number.isFinite(days) || days < 1 || days > 366) return null;
  if (start.getUTCMonth() === 0 && start.getUTCDate() === 1 && end.getUTCFullYear() === start.getUTCFullYear()
    && end.getUTCMonth() === 11 && end.getUTCDate() === 31) {
    const year = start.getUTCFullYear() + direction;
    return { from: `${year}-01-01`, to: `${year}-12-31` };
  }
  // Whole calendar months remain whole months, including leap years.
  const monthEnd = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + 1, 0));
  if (start.getUTCDate() === 1 && end.getTime() === monthEnd.getTime()) {
    return { from: iso(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + direction, 1)),
      to: iso(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + direction + 1, 0)) };
  }
  return { from: iso(start.getTime() + direction * days * day), to: iso(end.getTime() + direction * days * day) };
}
