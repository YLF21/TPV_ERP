import { apiBaseUrl } from "./runtime";

type ApiRequestOptions = {
  method?: string;
  token?: string;
  body?: unknown;
};

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly problem?: Record<string, unknown>,
    readonly traceId?: string
  ) {
    super(message);
  }
}

export class ApiConnectionError extends Error {
  constructor(message = "backend_unreachable") {
    super(message);
  }
}

export async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  let response: Response;
  const requestId = createRequestId();
  try {
    response = await fetch(`${apiBaseUrl}${path}`, {
      method: options.method ?? (options.body === undefined ? "GET" : "POST"),
      headers: {
        "Content-Type": "application/json",
        "X-Request-ID": requestId,
        ...(options.token ? { Authorization: `Bearer ${options.token}` } : {})
      },
      body: options.body === undefined ? undefined : JSON.stringify(options.body)
    });
  } catch (error) {
    throw new ApiConnectionError(error instanceof Error ? error.message : undefined);
  }

  if (!response.ok) {
    let message = response.statusText || "api_error";
    let problem: Record<string, unknown> | undefined;
    let traceId = response.headers?.get?.("X-Request-ID") ?? undefined;
    try {
      const body = await response.json() as Record<string, unknown>;
      problem = body;
      message = String(body.detail || body.code || message);
      traceId = String(body.traceId || traceId || "") || undefined;
    } catch {
      // Keep the HTTP status text when the backend does not return a problem body.
    }
    if (response.status >= 500) {
      message = "No se pudo completar la operación";
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
