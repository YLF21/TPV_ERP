import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  isWebTerminalMode, loadWebTerminalStatus, requestWebTerminal, validateWebTerminalStatus, webTerminalRequest,
} from "./webTerminalIdentity";
import { authenticateRemote } from "./auth/auth";

const runtime = vi.hoisted(() => ({ apiBaseUrl: "/api/v1" }));
vi.mock("./api/runtime", () => ({ get apiBaseUrl() { return runtime.apiBaseUrl; } }));

const origin = "https://venta.example.test";
const active = {
  status: "ACTIVE", slots: [{ code: "002", status: "ACTIVE" }],
  context: {
    credentialMode: "browser-cookie", terminalCode: "002", terminalId: "terminal-id",
    installationId: "installation-id", storeId: "store-id", bindingId: "binding-id", storeName: "Store",
  },
};

function serialLocks() {
  let pending: Promise<unknown> = Promise.resolve();
  return { request: vi.fn((_name: string, options: { signal?: AbortSignal }, callback: () => unknown) => {
    if (options.signal?.aborted) return Promise.reject(new DOMException("Request cancelled", "AbortError"));
    const next = pending.then(() => callback());
    pending = next.catch(() => undefined);
    return next;
  }) };
}

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("browser terminal security boundary", () => {
  beforeEach(() => {
    runtime.apiBaseUrl = "/api/v1";
    vi.stubGlobal("window", {
      location: { href: `${origin}/`, origin },
      setTimeout: globalThis.setTimeout, clearTimeout: globalThis.clearTimeout,
    });
    vi.stubGlobal("navigator", { locks: serialLocks() });
  });
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.useRealTimers(); });

  it("uses the same-origin cookie protocol without supplying a reusable terminal proof", async () => {
    const fetch = vi.fn().mockResolvedValue(response(active));
    vi.stubGlobal("fetch", fetch);
    await expect(loadWebTerminalStatus()).resolves.toEqual(active);
    expect(fetch).toHaveBeenCalledWith(`${origin}/api/v1/web-terminal/status`, expect.objectContaining({
      method: "POST", credentials: "same-origin", redirect: "error", cache: "no-store",
      headers: { "Content-Type": "application/json", "X-espos-web-terminal": "1" }, body: "{}",
    }));
    const [, options] = fetch.mock.calls[0];
    expect(options.headers).not.toHaveProperty("Cookie");
    expect(options.headers).not.toHaveProperty("Authorization");
    expect(options.body).not.toMatch(/terminalCredential|requestId|deviceId/);
  });

  it("fails closed before making a request if safe coordination across tabs is unavailable", async () => {
    vi.stubGlobal("navigator", {});
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    await expect(loadWebTerminalStatus()).rejects.toMatchObject({
      status: 412, problem: { code: "WEB_TERMINAL_BROWSER_UNSUPPORTED" },
    });
    expect(fetch).not.toHaveBeenCalled();
  });

  it("serializes the complete response before another operation can change the shared cookie", async () => {
    let finishFirstBody!: (value: unknown) => void;
    const firstBody = new Promise((resolve) => { finishFirstBody = resolve; });
    const fetch = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: () => firstBody })
      .mockResolvedValueOnce(response({ status: "UNREGISTERED", slots: [] }));
    vi.stubGlobal("fetch", fetch);

    const status = loadWebTerminalStatus();
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    const cancel = webTerminalRequest("cancel");
    await Promise.resolve();
    await Promise.resolve();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(navigator.locks.request).toHaveBeenNthCalledWith(1, "espos.web-terminal", {}, expect.any(Function));
    expect(navigator.locks.request).toHaveBeenNthCalledWith(2, "espos.web-terminal", {}, expect.any(Function));
    finishFirstBody(active);
    await expect(status).resolves.toEqual(active);
    await expect(cancel).resolves.toMatchObject({ status: "UNREGISTERED" });
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("authenticates a browser user without sending the displayed terminal or binding as authorization", async () => {
    const fetch = vi.fn().mockResolvedValue(response({ accessToken: "memory-only-user-token", userName: "Test", role: "VENDEDOR" }));
    vi.stubGlobal("fetch", fetch);
    const context = validateWebTerminalStatus(active).context!;
    await expect(authenticateRemote("test-user", "test-password", "venta", context)).resolves.toMatchObject({
      accessToken: "memory-only-user-token", permissions: expect.arrayContaining(["VENTA"]),
    });
    expect(fetch).toHaveBeenCalledWith(`${origin}/api/v1/web-terminal/login`, expect.objectContaining({
      body: JSON.stringify({ userName: "test-user", password: "test-password" }), credentials: "same-origin", redirect: "error",
    }));
    expect(fetch.mock.calls[0][1].body).not.toMatch(/terminalId|bindingId|terminalCredential|requestId/);
  });

  it.each([
    "https://other.example.test/api/v1", "//other.example.test/api/v1", "http://venta.example.test/api/v1",
    "https://username:password@venta.example.test/api/v1", "/api/v1?destination=https://evil.example.test",
    "/api/v1#fragment", "data:application/json,{}", "ftp://venta.example.test/api/v1",
  ])("rejects an API destination before transmitting credentials: %s", async (apiBaseUrl) => {
    runtime.apiBaseUrl = apiBaseUrl;
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    await expect(webTerminalRequest("login", { userName: "test-user", password: "test-password" })).rejects.toMatchObject({
      status: 403, problem: { code: "WEB_TERMINAL_ORIGIN_FORBIDDEN" },
    });
    expect(fetch).not.toHaveBeenCalled();
  });

  it("does not read cookies or use browser storage to persist an approved context", async () => {
    const forbiddenStorage = { getItem: vi.fn(() => { throw new Error("Storage must not be read"); }),
      setItem: vi.fn(() => { throw new Error("Storage must not be written"); }), removeItem: vi.fn() };
    vi.stubGlobal("localStorage", forbiddenStorage);
    vi.stubGlobal("sessionStorage", forbiddenStorage);
    vi.stubGlobal("indexedDB", { open: vi.fn(() => { throw new Error("Storage must not be opened"); }) });
    const cookieRead = vi.fn(() => { throw new Error("Cookie must not be read"); });
    const document = {};
    Object.defineProperty(document, "cookie", { get: cookieRead });
    vi.stubGlobal("document", document);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(active)));

    await expect(loadWebTerminalStatus()).resolves.toEqual(active);
    expect(forbiddenStorage.getItem).not.toHaveBeenCalled();
    expect(forbiddenStorage.setItem).not.toHaveBeenCalled();
    expect(cookieRead).not.toHaveBeenCalled();
  });

  it.each(["UNREGISTERED", "PENDING", "DISABLED", "RELEASED", "CANCELLED", "EXPIRED"])(
    "does not promote a stale ACTIVE context when the fresh state is %s", (status) => {
      expect(validateWebTerminalStatus({ ...active, status })).not.toHaveProperty("context");
    },
  );

  it.each([
    { terminalCode: "001" }, { terminalCode: "000" }, { terminalCode: "2" },
    { credentialMode: "desktop" }, { terminalCredential: "must-not-arrive-in-javascript" },
    { bindingId: "" }, { installationId: undefined }, { storeId: "" }, { terminalId: "" },
  ])("rejects an invalid ACTIVE browser context: %j", (context) => {
    expect(() => validateWebTerminalStatus({ ...active, context: { ...active.context, ...context } })).toThrow();
  });

  it("filters the protected server slot and strips unknown fields rather than retaining raw responses", () => {
    const validated = validateWebTerminalStatus({ ...active, secret: "discard", context: { ...active.context, password: "discard" },
      slots: [{ code: "001", status: "FREE" }, { code: "002", status: "ACTIVE", credential: "discard" }] });
    expect(validated).toEqual(active);
    expect(JSON.stringify(validated)).not.toContain("discard");
  });

  it("rejects the server slot before creating a browser registration", async () => {
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    await expect(requestWebTerminal("001", "Server")).rejects.toMatchObject({ status: 400 });
    expect(fetch).not.toHaveBeenCalled();
  });

  it("preserves explicit cancellation without reclassifying it as a fresh identity", async () => {
    const controller = new AbortController();
    controller.abort();
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new DOMException("Cancelled", "AbortError")));
    await expect(loadWebTerminalStatus(controller.signal)).rejects.toMatchObject({ name: "AbortError" });
  });

  it("requires explicit web mode in development and preserves the Electron identity flow", () => {
    expect(isWebTerminalMode({ development: true, desktop: false })).toBe(false);
    expect(isWebTerminalMode({ development: true, desktop: false, optIn: "true" })).toBe(true);
    expect(isWebTerminalMode({ development: false, desktop: false })).toBe(true);
    expect(isWebTerminalMode({ development: false, desktop: true, optIn: "true" })).toBe(false);
  });
});
