import { toUtcInput, utcInputToInstant } from "../../lib/utc-date-time.mjs";

export function toUtcActionDate(value) {
  if (!value) return "";
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) throw new RangeError("Invalid action date");
  return toUtcInput(date);
}

export function actionDateToInstant(value) {
  if (!value) return null;
  return utcInputToInstant(value);
}

export function isDemoActionOverdue(request, now = Date.now()) {
  return request.status !== "CLOSED" && Boolean(request.nextAction?.trim()) && Boolean(request.nextActionAt)
    && Number.isFinite(Date.parse(request.nextActionAt)) && Date.parse(request.nextActionAt) < now;
}
