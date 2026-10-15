export const demoFieldLimits = { name: 160, company: 200, email: 160, phone: 40, message: 2000 } as const;

export type DemoRequestFailure = "validation" | "conflict" | "rateLimited" | "timeout" | "network" | "unavailableError" | "invalidReceipt";
export type DemoReceipt = { id: string; receivedAt: string };

export class DemoRequestError extends Error {
  constructor(public readonly kind: DemoRequestFailure, public readonly fields: Record<string, string> = {}) {
    super(kind);
    this.name = "DemoRequestError";
  }
}

const apiBase = (import.meta.env?.VITE_SAAS_API_BASE_URL ?? "").replace(/\/+$/, "");
const endpoint = `${apiBase}/api/v1/public/demo-requests`;
const requestTimeoutMs = 18_000;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

async function request<T>(path: string, options: RequestInit, signal: AbortSignal, consume: (response: Response) => Promise<T>): Promise<T> {
  const controller = new AbortController();
  let timedOut = false;
  const abort = () => controller.abort();
  if (signal.aborted) controller.abort();
  signal.addEventListener("abort", abort, { once: true });
  const timer = window.setTimeout(() => { timedOut = true; controller.abort(); }, requestTimeoutMs);
  try {
    const response = await fetch(path, { ...options, cache: "no-store", credentials: "omit", signal: controller.signal });
    return await consume(response);
  } catch (error) {
    if (signal.aborted) throw error;
    if (error instanceof DemoRequestError && !timedOut) throw error;
    throw new DemoRequestError(timedOut ? "timeout" : "network");
  } finally {
    window.clearTimeout(timer);
    signal.removeEventListener("abort", abort);
  }
}

async function readJson(response: Response): Promise<unknown> {
  if (!/\bapplication\/(?:[\w.+-]+\+)?json\b/i.test(response.headers.get("Content-Type") ?? "")) {
    throw new DemoRequestError("invalidReceipt");
  }
  try { return await response.json(); }
  catch { throw new DemoRequestError("invalidReceipt"); }
}

export async function checkDemoAvailability(signal: AbortSignal): Promise<boolean> {
  return request(`${endpoint}/availability`, { method: "GET", headers: { Accept: "application/json" } }, signal, async (response) => {
    if (!response.ok) return false;
    const result = await readJson(response);
    return !!result && typeof result === "object" && "available" in result && result.available === true;
  });
}

export async function submitDemoRequest(body: string, idempotencyKey: string, signal: AbortSignal): Promise<DemoReceipt> {
  return request(endpoint, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json", "Idempotency-Key": idempotencyKey },
    body
  }, signal, async (response) => {
    if (!response.ok) {
      if (response.status === 400 || response.status === 422) {
        let fields: Record<string, string> = {};
        // Only allow known field names through; never display server-provided text or rejected values.
        try {
          const problem = await readJson(response);
          if (problem && typeof problem === "object" && "fields" in problem && problem.fields && typeof problem.fields === "object" && !Array.isArray(problem.fields)) {
            fields = Object.fromEntries(Object.entries(problem.fields).filter(([field, code]) =>
              (Object.prototype.hasOwnProperty.call(demoFieldLimits, field) || field === "privacyAccepted")
              && typeof code === "string" && ["REQUIRED", "TOO_LONG", "INVALID", "MUST_ACCEPT"].includes(code)));
          }
        } catch { /* The status is still a validation error when the body is empty or malformed. */ }
        throw new DemoRequestError("validation", fields);
      }
      if (response.status === 409) throw new DemoRequestError("conflict");
      if (response.status === 429) throw new DemoRequestError("rateLimited");
      throw new DemoRequestError("unavailableError");
    }
    const receipt = await readJson(response);
    if (!receipt || typeof receipt !== "object" || !("id" in receipt) || !("receivedAt" in receipt)
      || typeof receipt.id !== "string" || !uuidPattern.test(receipt.id)
      || typeof receipt.receivedAt !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(receipt.receivedAt)
      || !Number.isFinite(Date.parse(receipt.receivedAt))) {
      throw new DemoRequestError("invalidReceipt");
    }
    return { id: receipt.id, receivedAt: receipt.receivedAt };
  });
}

export function createDemoRequestKey(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const value = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`;
}

export function createDemoRequestAttempts(maxEntries = 3) {
  const attempts = new Map<string, string>();
  return {
    keyFor(body: string): string {
      const key = attempts.get(body) ?? createDemoRequestKey();
      // Retain a few normalized bodies in memory so reversible edits do not duplicate a lost response.
      attempts.delete(body);
      attempts.set(body, key);
      while (attempts.size > Math.max(1, maxEntries)) attempts.delete(attempts.keys().next().value!);
      return key;
    },
    complete(body: string) { attempts.delete(body); }
  };
}
