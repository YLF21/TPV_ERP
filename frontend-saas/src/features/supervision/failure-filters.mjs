/** Convert local calendar days to an inclusive-start/exclusive-end API interval. */
export function failureDateRange(from, to) {
  if (from && to && from > to) throw new RangeError("invalidDates");
  const start = from ? new Date(`${from}T00:00:00`) : null;
  const end = to ? new Date(`${to}T00:00:00`) : null;
  if ((start && !Number.isFinite(start.getTime())) || (end && !Number.isFinite(end.getTime()))) throw new RangeError("invalidDates");
  if (end) end.setDate(end.getDate() + 1);
  return { from: start?.toISOString() ?? "", to: end?.toISOString() ?? "" };
}

export function validInstallationId(value) {
  return !value || /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}
