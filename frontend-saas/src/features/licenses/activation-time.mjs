/** Anchor the countdown to server time and elapsed monotonic time, not the PC clock. */
export function remainingActivationSeconds(expiresAt, serverNow, requestedAt, monotonicNow) {
  const expiry = Date.parse(expiresAt), serverTime = Date.parse(serverNow);
  if (![expiry, serverTime, requestedAt, monotonicNow].every(Number.isFinite)) return 0;
  const elapsed = Math.max(0, monotonicNow - requestedAt);
  return Math.max(0, Math.ceil((expiry - serverTime - elapsed) / 1000));
}

export function activationCountdown(seconds) {
  const remaining = Number.isFinite(seconds) ? Math.max(0, Math.ceil(seconds)) : 0;
  return `${String(Math.floor(remaining / 60)).padStart(2, "0")}:${String(remaining % 60).padStart(2, "0")}`;
}
