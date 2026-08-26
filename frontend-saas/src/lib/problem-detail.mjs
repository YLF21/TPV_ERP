const MAX_MESSAGE_LENGTH = 512;

function safeMessage(value) {
  if (typeof value !== "string") return null;
  const message = value.trim();
  if (!message || /<\s*(?:!doctype|html|head|body|script|style|title)(?:\s|>)/i.test(message)) {
    return null;
  }
  return message.slice(0, MAX_MESSAGE_LENGTH);
}

/**
 * Extracts an actionable message from RFC 9457 ProblemDetail and legacy
 * Spring error payloads without ever returning an HTML response body.
 */
export function extractApiErrorMessage(raw) {
  const body = typeof raw === "string" ? raw.trim() : "";
  if (!body) return null;

  try {
    const payload = JSON.parse(body);
    if (payload && typeof payload === "object" && !Array.isArray(payload)) {
      for (const key of ["detail", "message", "error"]) {
        const message = safeMessage(payload[key]);
        if (message) return message;
      }
      if (typeof payload.status === "number") return `Error ${payload.status}`;
      return null;
    }
  } catch {
    // Plain-text legacy responses are handled below.
  }

  return safeMessage(body);
}
