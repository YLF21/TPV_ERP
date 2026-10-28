// datetime-local represents the operator's civil time, never a UTC substring.
export function toLocalActionDate(value) {
  if (!value) return "";
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) throw new RangeError("Invalid action date");
  const pad = number => String(number).padStart(2, "0");
  return `${String(date.getFullYear()).padStart(4, "0")}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function actionDateToInstant(value) {
  if (!value) return null;
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) throw new RangeError("Invalid action date");
  const date = new Date(`${value}:00`);
  // Reject invalid calendar dates and local times skipped by a daylight-saving transition.
  if (!Number.isFinite(date.getTime()) || toLocalActionDate(date.toISOString()) !== value) throw new RangeError("Invalid local action date");
  return date.toISOString();
}

export function isDemoActionOverdue(request, now = Date.now()) {
  return request.status !== "CLOSED" && Boolean(request.nextAction?.trim()) && Boolean(request.nextActionAt)
    && Number.isFinite(Date.parse(request.nextActionAt)) && Date.parse(request.nextActionAt) < now;
}
