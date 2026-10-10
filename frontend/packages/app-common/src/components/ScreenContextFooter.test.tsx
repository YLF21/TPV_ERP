// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { CONNECTION_REFRESH_MS, CONNECTION_TIMEOUT_MS } from "./useScreenConnectionStatus";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));
const request = vi.mocked(apiRequest);
const addressFetch = vi.fn();
const terminalContext = { storeName: "Tienda Principal", terminalCode: "SERVIDOR" };

async function mountFooter(terminalNameOnly = false) {
  let view!: ReturnType<typeof render>;
  await act(async () => { view = render(<ScreenContextFooter locale="es" terminalContext={terminalContext} terminalNameOnly={terminalNameOnly} />); });
  return view;
}

describe("ScreenContextFooter real connectivity", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    request.mockReset();
    request.mockResolvedValue({ saasConnected: false });
    addressFetch.mockReset();
    addressFetch.mockResolvedValue({ ok: true, json: async () => ({ backendLabel: "LOCAL" }) });
    vi.stubGlobal("fetch", addressFetch);
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it.each([false, true])("uses the terminal name only when explicitly requested: %s", async (terminalNameOnly) => {
    await act(async () => { render(<ScreenContextFooter locale="es"
      terminalContext={{ ...terminalContext, terminalName: "Caja Principal" }} terminalNameOnly={terminalNameOnly} />); });
    expect(screen.getByText(terminalNameOnly ? "Caja Principal" : "Terminal: SERVIDOR")).toBeInTheDocument();
    expect(screen.queryByText(terminalNameOnly ? "Terminal: SERVIDOR" : "Caja Principal")).not.toBeInTheDocument();
  });

  it("uses the code without a prefix when a terminal name is not available", async () => {
    await mountFooter(true);
    expect(screen.getByText("SERVIDOR")).toBeInTheDocument();
    expect(screen.queryByText("Terminal: SERVIDOR")).not.toBeInTheDocument();
  });

  it("shows only a verified seven-digit SaaS store code next to the store name", async () => {
    await act(async () => { render(<ScreenContextFooter locale="es"
      terminalContext={{ ...terminalContext, storeInternalCode: "3500002" }} />); });
    expect(screen.getByText("Tienda Principal · 3500002")).toHaveAttribute("title", "Código de tienda en SaaS");
    expect(screen.getByText("Terminal: SERVIDOR")).toBeInTheDocument();
  });

  it.each(["", "123", "12345678", "DEMO001", "0000000", "3500000", "5300001", "3500002 "])("omits an invalid store code: %s", async (storeInternalCode) => {
    await act(async () => { render(<ScreenContextFooter locale="es"
      terminalContext={{ ...terminalContext, storeInternalCode }} />); });
    expect(screen.getByText("Tienda Principal")).not.toHaveAttribute("title");
    expect(screen.queryByText(/Tienda Principal ·/)).not.toBeInTheDocument();
  });

  it("does not infer a SaaS connection from the browser having a network", async () => {
    expect(navigator.onLine).toBe(true);
    await mountFooter();
    expect(screen.getByRole("status", { name: "Sin conexión con SaaS" })).toHaveClass("offline");
    expect(screen.getByText("DB: LOCAL").querySelector("svg")).toBeInTheDocument();
    expect(screen.getByRole("status").querySelector("svg")).toBeInTheDocument();
    expect(request).toHaveBeenCalledWith("/connectivity", { signal: expect.any(AbortSignal) });
  });

  it("polls and recovers automatically when the actual SaaS response changes", async () => {
    request.mockResolvedValueOnce({ saasConnected: true })
      .mockRejectedValueOnce(new Error("Backend unavailable"))
      .mockResolvedValueOnce({ saasConnected: true });
    await mountFooter();
    expect(screen.getByRole("status", { name: "Conectado con SaaS" })).toHaveClass("online");
    await act(async () => { await vi.advanceTimersByTimeAsync(CONNECTION_REFRESH_MS); });
    expect(screen.getByRole("status")).toHaveClass("offline");
    await act(async () => { await vi.advanceTimersByTimeAsync(CONNECTION_REFRESH_MS); });
    expect(screen.getByRole("status")).toHaveClass("online");
    expect(request).toHaveBeenCalledTimes(3);
  });

  it("shows the actual proxy backend IP even when SaaS is unavailable", async () => {
    addressFetch.mockResolvedValue({ ok: true, json: async () => ({ backendLabel: "192.168.1.25" }) });
    request.mockRejectedValue(new Error("offline"));
    await mountFooter();
    expect(screen.getByText("DB: 192.168.1.25")).toBeInTheDocument();
    expect(addressFetch).toHaveBeenCalledWith("/__tpv/backend-address", { cache: "no-store", signal: expect.any(AbortSignal) });
    expect(screen.getByRole("status")).toHaveClass("offline");
  });

  it("does not claim the database is local when the proxy metadata is unavailable", async () => {
    addressFetch.mockResolvedValue({ ok: false });
    request.mockResolvedValue({ saasConnected: true });
    await mountFooter();
    expect(screen.getByText("DB: —")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveClass("online");
  });

  it.each([undefined, {}, { saasConnected: "true" }, { saasConnected: 1 }])("rejects an invalid SaaS status: %j", async (result) => {
    request.mockResolvedValue(result);
    await mountFooter();
    expect(screen.getByRole("status")).toHaveClass("offline");
  });

  it("turns red on timeout and retries without overlapping requests", async () => {
    request.mockResolvedValueOnce({ saasConnected: true }).mockImplementationOnce((_path, options) => new Promise((_resolve, reject) => {
      options?.signal?.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
    })).mockResolvedValue({ saasConnected: true });
    await mountFooter();
    await act(async () => { await vi.advanceTimersByTimeAsync(CONNECTION_REFRESH_MS); });
    await act(async () => { fireEvent(window, new Event("online")); });
    expect(request).toHaveBeenCalledTimes(2);
    await act(async () => { await vi.advanceTimersByTimeAsync(CONNECTION_TIMEOUT_MS); });
    expect(screen.getByRole("status")).toHaveClass("offline");
    await act(async () => { await vi.advanceTimersByTimeAsync(CONNECTION_REFRESH_MS - CONNECTION_TIMEOUT_MS); });
    expect(screen.getByRole("status")).toHaveClass("online");
  });

  it("ignores an old response after losing the network and probing again", async () => {
    let finishOld!: (result: { saasConnected: boolean }) => void;
    request.mockReturnValueOnce(new Promise((resolve) => { finishOld = resolve; }));
    await mountFooter();
    const oldSignal = request.mock.calls[0][1]?.signal;
    await act(async () => { fireEvent(window, new Event("offline")); });
    expect(oldSignal?.aborted).toBe(true);
    request.mockResolvedValue({ saasConnected: false });
    await act(async () => { fireEvent(window, new Event("online")); });
    await act(async () => { finishOld({ saasConnected: true }); });
    expect(screen.getByRole("status")).toHaveClass("offline");
  });

  it("aborts outstanding work and stops polling when the footer unmounts", async () => {
    request.mockReturnValue(new Promise(() => {}));
    const view = await mountFooter();
    const signal = request.mock.calls[0][1]?.signal;
    view.unmount();
    expect(signal?.aborted).toBe(true);
    await act(async () => { await vi.advanceTimersByTimeAsync(2 * CONNECTION_REFRESH_MS); });
    fireEvent(window, new Event("online"));
    expect(request).toHaveBeenCalledTimes(1);
  });
});
