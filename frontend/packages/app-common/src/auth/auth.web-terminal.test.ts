// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { authenticateRemote } from "./auth";
import type { TerminalContext } from "../types";

const context: TerminalContext = { credentialMode: "browser-cookie", terminalId: "terminal", terminalCode: "002", storeName: "Store" };
beforeEach(() => {
  vi.stubGlobal("navigator", { locks: { request: vi.fn((_name: string, _options: unknown, action: () => Promise<unknown>) => action()) } });
});
afterEach(() => vi.unstubAllGlobals());

describe("browser terminal user authentication", () => {
  it("uses the HttpOnly terminal proof without sending JavaScript terminal identifiers or credentials", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ accessToken: "user-token", userName: "Cashier", role: "VENTA", permissions: ["VENTA"] }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const session = await authenticateRemote("cashier", "transient-password", "venta", context);
    expect(session.accessToken).toBe("user-token");
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining("/web-terminal/login"), expect.objectContaining({ credentials: "same-origin" }));
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ userName: "cashier", password: "transient-password" });
    expect(session).not.toHaveProperty("password");
    expect(session).not.toHaveProperty("terminalCredential");
  });

  it("preserves invalid-user responses for the login form and never falls back to desktop authentication", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: "INVALID_CREDENTIALS" }), { status: 401 }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(authenticateRemote("cashier", "wrong", "venta", context)).rejects.toMatchObject({ status: 401 });
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(String(fetchMock.mock.calls[0][0])).toContain("/web-terminal/login");
  });
});
