import { apiBaseUrl } from "./runtime";

export type ApiRequestOptions = {
  method?: string;
  token?: string;
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
};

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly problem?: Record<string, unknown>,
    readonly traceId?: string,
  ) {
    super(message);
  }
}

export class ApiConnectionError extends Error {
  constructor(message = "backend_unreachable") {
    super(message);
  }
}

export type ApiFailureKind =
  | "offline"
  | "authentication"
  | "terminal-disabled"
  | "forbidden"
  | "not-found"
  | "conflict"
  | "server"
  | "unknown";

export const gestionGroupLockedEvent = "tpv:gestion-group-locked";

export function apiProblemCode(error: unknown): string | undefined {
  if (!(error instanceof ApiError)) return undefined;
  const code = error.problem?.code;
  return typeof code === "string" ? code : undefined;
}

export function classifyApiFailure(error: unknown): ApiFailureKind {
  if (error instanceof ApiConnectionError) return "offline";
  if (!(error instanceof ApiError)) return "unknown";
  if (apiProblemCode(error) === "TERMINAL_DISABLED") return "terminal-disabled";
  if (error.status === 401) return "authentication";
  if (error.status === 403) return "forbidden";
  if (error.status === 404) return "not-found";
  if (error.status === 409 || error.status === 412) return "conflict";
  if (error.status >= 500) return "server";
  return "unknown";
}

function isFormData(body: unknown): body is FormData {
  return typeof FormData !== "undefined" && body instanceof FormData;
}

function serializeRequestBody(body: unknown): BodyInit | undefined {
  if (body === undefined) return undefined;
  return isFormData(body) ? body : JSON.stringify(body);
}

export async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const multipart = isFormData(options.body);
  let response: Response;
  const requestId = createRequestId();
  try {
    response = await fetch(`${apiBaseUrl}${path}`, {
      method: options.method ?? (options.body === undefined ? "GET" : "POST"),
      headers: {
        ...(!multipart ? { "Content-Type": "application/json" } : {}),
        "X-Request-ID": requestId,
        ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}),
        ...options.headers,
      },
      body: serializeRequestBody(options.body),
      ...(options.signal ? { signal: options.signal } : {}),
    });
  } catch (error) {
    throw new ApiConnectionError(error instanceof Error ? error.message : undefined);
  }

  if (!response.ok) {
    let message = response.statusText || "api_error";
    let problem: Record<string, unknown> | undefined;
    let traceId = response.headers?.get?.("X-Request-ID") ?? undefined;
    try {
      const body = (await response.json()) as Record<string, unknown>;
      problem = body;
      message = String(body.detail || body.code || message);
      traceId = String(body.traceId || traceId || "") || undefined;
    } catch {
      // Keep the HTTP status text when the backend does not return a problem body.
    }
    if (response.status >= 500) {
      message = "No se pudo completar la operación";
    }
    if (response.status === 403 && problem?.code === "GESTION_GROUP_LOCKED"
      && typeof globalThis.dispatchEvent === "function" && typeof CustomEvent !== "undefined") {
      globalThis.dispatchEvent(new CustomEvent(gestionGroupLockedEvent, { detail: problem }));
    }
    throw new ApiError(traceId ? `${message} (Ref: ${traceId})` : message, response.status, problem, traceId);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  if (typeof response.text !== "function") {
    return response.json() as Promise<T>;
  }

  const responseBody = await response.text();
  if (!responseBody) {
    return undefined as T;
  }

  return JSON.parse(responseBody) as T;
}

function createRequestId(): string {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  return `web-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

export async function checkBackendConnection(): Promise<boolean> {
  try {
    const response = await fetch(`${apiBaseUrl}/auth/login`, { method: "GET" });
    return response.status < 500;
  } catch {
    return false;
  }
}
