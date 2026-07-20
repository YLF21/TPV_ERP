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
    readonly problem?: Record<string, unknown>
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
  try {
    response = await fetch(`${apiBaseUrl}${path}`, {
      method: options.method ?? (options.body === undefined ? "GET" : "POST"),
      headers: {
        "Content-Type": "application/json",
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
    try {
      const body = await response.json() as Record<string, unknown>;
      problem = body;
      message = String(body.detail || body.code || message);
    } catch {
      // Keep the HTTP status text when the backend does not return a problem body.
    }
    throw new ApiError(message, response.status, problem);
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

export async function checkBackendConnection(): Promise<boolean> {
  try {
    const response = await fetch(`${apiBaseUrl}/auth/login`, { method: "GET" });
    return response.status < 500;
  } catch {
    return false;
  }
}
