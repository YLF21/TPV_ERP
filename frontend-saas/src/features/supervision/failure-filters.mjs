/** Convert UTC calendar days to an inclusive-start/exclusive-end API interval. */
export function failureDateRange(from, to) {
  if (from && to && from > to) throw new RangeError("invalidDates");
  const parseDay = value => {
    if (!value) return null;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new RangeError("invalidDates");
    const date = new Date(`${value}T00:00:00Z`);
    if (!Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== value) throw new RangeError("invalidDates");
    return date;
  };
  const start = parseDay(from);
  const end = parseDay(to);
  if (end) end.setUTCDate(end.getUTCDate() + 1);
  return { from: start?.toISOString() ?? "", to: end?.toISOString() ?? "" };
}

export function validInstallationId(value) {
  return !value || /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}
