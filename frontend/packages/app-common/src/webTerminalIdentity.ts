import { ApiConnectionError, ApiError } from "./api/client";
import { apiBaseUrl } from "./api/runtime";
import type { TerminalContext } from "./types";

export type WebTerminalStatusName = "UNREGISTERED" | "PENDING" | "ACTIVE" | "DISABLED" | "RELEASED" | "CANCELLED" | "EXPIRED";
export type WebTerminalStatus = {
  status: WebTerminalStatusName;
  context?: TerminalContext & { credentialMode: "browser-cookie" };
  slots: Array<{ code: string; status: string; name?: string }>;
  expiresAt?: string;
};

export function isWebTerminalMode({ development, optIn, desktop }: {
  development: boolean; optIn?: string; desktop: boolean;
}): boolean {
  return !desktop && (!development || optIn === "true");
}

const statuses = new Set<WebTerminalStatusName>(["UNREGISTERED", "PENDING", "ACTIVE", "DISABLED", "RELEASED", "CANCELLED", "EXPIRED"]);
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === "object" && !Array.isArray(value);
const text = (value: unknown): value is string => typeof value === "string" && value.trim().length > 0;
export const isWebWorkstationCode = (value: unknown): value is string => typeof value === "string" && /^(?:\d{3}|[1-9]\d{3,8})$/.test(value) && Number(value) >= 2;

function invalidResponse(): ApiError {
  return new ApiError("Invalid browser terminal response", 502, { code: "WEB_TERMINAL_INVALID_RESPONSE" });
}

/** Only a fresh server response can supply an active browser terminal context. Nothing is persisted by this module. */
export function validateWebTerminalStatus(raw: unknown): WebTerminalStatus {
  if (!record(raw) || !statuses.has(raw.status as WebTerminalStatusName) || !Array.isArray(raw.slots)) throw invalidResponse();
  const result: WebTerminalStatus = {
    status: raw.status as WebTerminalStatusName,
    slots: raw.slots.filter(record).filter(slot => isWebWorkstationCode(slot.code) && text(slot.status))
      .map(slot => ({ code: slot.code as string, status: slot.status as string, ...(text(slot.name) ? { name: slot.name } : {}) })),
    ...(text(raw.expiresAt) ? { expiresAt: raw.expiresAt } : {}),
  };
  if (result.status !== "ACTIVE") return result;
  const context = raw.context;
  if (!record(context) || context.credentialMode !== "browser-cookie" || context.terminalCredential !== undefined
    || !isWebWorkstationCode(context.terminalCode)
    || ![context.terminalId, context.installationId, context.storeId, context.bindingId, context.storeName].every(text)) {
    throw invalidResponse();
  }
  result.context = {
    credentialMode: "browser-cookie", terminalCode: context.terminalCode,
    terminalId: context.terminalId as string, installationId: context.installationId as string,
    storeId: context.storeId as string, bindingId: context.bindingId as string, storeName: context.storeName as string,
    ...(text(context.terminalName) ? { terminalName: context.terminalName } : {}),
    ...(text(context.companyName) ? { companyName: context.companyName } : {}),
    ...(text(context.storeInternalCode) ? { storeInternalCode: context.storeInternalCode } : {}),
  };
  return result;
}

export async function webTerminalRequest<T>(action: "status" | "request" | "cancel" | "login", body: unknown = {}, signal?: AbortSignal): Promise<T> {
  if (!navigator.locks?.request) {
    throw new ApiError("A browser with Web Locks is required", 412, { code: "WEB_TERMINAL_BROWSER_UNSUPPORTED" });
  }
  // All tabs share the terminal cookie. Serialize the complete response so an
  // older Set-Cookie cannot overwrite a newer registration or cancellation.
  return navigator.locks.request("espos.web-terminal", { ...(signal ? { signal } : {}) },
    () => performWebTerminalRequest<T>(action, body, signal));
}

async function performWebTerminalRequest<T>(action: "status" | "request" | "cancel" | "login", body: unknown, signal?: AbortSignal): Promise<T> {
  const base = new URL(apiBaseUrl, window.location.href);
  if (base.origin !== window.location.origin || !["https:", "http:"].includes(base.protocol)
    || base.username || base.password || base.search || base.hash) {
    throw new ApiError("Browser terminals require a same-origin backend", 403, { code: "WEB_TERMINAL_ORIGIN_FORBIDDEN" });
  }
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal?.aborted) controller.abort();
  else signal?.addEventListener("abort", abort, { once: true });
  const timeout = window.setTimeout(abort, 10_000);
  try {
    const response = await fetch(`${base.href.replace(/\/$/, "")}/web-terminal/${action}`, {
      method: "POST", credentials: "same-origin", redirect: "error", cache: "no-store",
      headers: { "Content-Type": "application/json", "X-espos-web-terminal": "1" },
      body: JSON.stringify(body), signal: controller.signal,
    });
    const raw: unknown = await response.json().catch(() => undefined);
    if (!response.ok) {
      const problem = record(raw) ? raw : undefined;
      throw new ApiError("Browser terminal request failed", response.status, problem);
    }
    if (raw === undefined) throw invalidResponse();
    return raw as T;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (signal?.aborted) throw new DOMException("Request cancelled", "AbortError");
    throw new ApiConnectionError();
  } finally {
    window.clearTimeout(timeout);
    signal?.removeEventListener("abort", abort);
  }
}

export async function loadWebTerminalStatus(signal?: AbortSignal): Promise<WebTerminalStatus> {
  return validateWebTerminalStatus(await webTerminalRequest("status", {}, signal));
}
export async function requestWebTerminal(code: string, name: string, signal?: AbortSignal): Promise<WebTerminalStatus> {
  if (!isWebWorkstationCode(code) || !name.trim()) throw new ApiError("Invalid workstation", 400);
  return validateWebTerminalStatus(await webTerminalRequest("request", { code, name: name.trim() }, signal));
}
export async function cancelWebTerminal(signal?: AbortSignal): Promise<WebTerminalStatus> {
  return validateWebTerminalStatus(await webTerminalRequest("cancel", {}, signal));
}
