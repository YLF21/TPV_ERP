// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import type { LocaleCode } from "../types";
import { AppFrame } from "./AppFrame";
import { CONNECTION_REFRESH_MS } from "./useScreenConnectionStatus";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));
const request = vi.mocked(apiRequest);
const addressFetch = vi.fn();

const session = {
  username: "admin",
  displayName: "Administrador",
  permissions: ["ADMIN" as const]
};

function frame(locale: LocaleCode = "es", content = "Contenido", onLocaleChange = vi.fn()) {
  return (
    <AppFrame
      titleKey="gestion.title"
      locale={locale}
      session={session}
      onLocaleChange={onLocaleChange}
      onLogout={vi.fn()}
    >
      <div>{content}</div>
    </AppFrame>
  );
}

async function mountFrame(locale: LocaleCode = "es") {
  let view!: ReturnType<typeof render>;
  await act(async () => { view = render(frame(locale)); });
  return view;
}

beforeEach(() => {
  vi.useFakeTimers();
  request.mockReset();
  request.mockResolvedValue({ saasConnected: false });
  addressFetch.mockReset();
  vi.stubGlobal("fetch", addressFetch);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("AppFrame language selector", () => {
  it("offers all supported languages and reports the selected locale", async () => {
    const onLocaleChange = vi.fn();

    await act(async () => { render(frame("es", "Contenido", onLocaleChange)); });

    fireEvent.click(screen.getByRole("button", { name: "Cambiar idioma" }));
    expect(screen.getByRole("button", { name: /Español/ })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: /English/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /中文/ })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /English/ }));

    expect(onLocaleChange).toHaveBeenCalledWith("en");
    expect(screen.queryByRole("button", { name: /English/ })).not.toBeInTheDocument();
  });
});

describe("AppFrame SaaS connectivity", () => {
  it.each([
    { locale: "es" as const, status: "Conectado con SaaS", action: "Comprobar conexión con SaaS" },
    { locale: "en" as const, status: "Connected to SaaS", action: "Check SaaS connection" },
    { locale: "zh" as const, status: "已连接 SaaS", action: "检查 SaaS 连接" }
  ])("shows the real SaaS status and localized action in $locale", async ({ locale, status, action }) => {
    request.mockResolvedValue({ saasConnected: true });
    await mountFrame(locale);

    expect(screen.getByText("Administrador")).toBeInTheDocument();
    expect(screen.queryByText(/Servidor local/)).not.toBeInTheDocument();
    expect(screen.getByRole("status", { name: status })).toHaveClass("online");
    expect(screen.getByRole("button", { name: action })).toBeEnabled();
    expect(request).toHaveBeenCalledExactlyOnceWith("/connectivity", { signal: expect.any(AbortSignal) });
    expect(addressFetch).not.toHaveBeenCalled();
  });

  it("refreshes on click and prevents overlapping checks while waiting", async () => {
    await mountFrame();
    expect(screen.getByRole("status", { name: "Sin conexión con SaaS" })).toHaveClass("offline");
    let completeCheck!: (result: { saasConnected: boolean }) => void;
    request.mockReturnValueOnce(new Promise((resolve) => { completeCheck = resolve; }));

    fireEvent.click(screen.getByRole("button", { name: "Comprobar conexión con SaaS" }));

    const busyButton = screen.getByRole("button", { name: "Comprobando conexión con SaaS" });
    expect(busyButton).toBeDisabled();
    expect(busyButton).toHaveAttribute("aria-busy", "true");
    expect(request).toHaveBeenCalledTimes(2);
    fireEvent.click(busyButton);
    fireEvent(window, new Event("online"));
    expect(request).toHaveBeenCalledTimes(2);

    await act(async () => { completeCheck({ saasConnected: true }); });

    expect(screen.getByRole("status", { name: "Conectado con SaaS" })).toHaveClass("online");
    expect(screen.getByRole("button", { name: "Comprobar conexión con SaaS" })).toBeEnabled();
    expect(addressFetch).not.toHaveBeenCalled();
  });

  it("recovers automatically every 30 seconds without restarting polling when the view changes", async () => {
    request.mockResolvedValueOnce({ saasConnected: true })
      .mockRejectedValueOnce(new Error("Backend unavailable"))
      .mockResolvedValueOnce({ saasConnected: true });
    const view = await mountFrame();
    await act(async () => { await vi.advanceTimersByTimeAsync(CONNECTION_REFRESH_MS / 2); });
    await act(async () => { view.rerender(frame("en", "Stock")); });

    expect(screen.getByText("Stock")).toBeInTheDocument();
    expect(screen.getByRole("status", { name: "Connected to SaaS" })).toHaveClass("online");
    expect(request).toHaveBeenCalledTimes(1);

    await act(async () => { await vi.advanceTimersByTimeAsync(CONNECTION_REFRESH_MS / 2); });
    expect(screen.getByRole("status", { name: "No connection to SaaS" })).toHaveClass("offline");
    expect(request).toHaveBeenCalledTimes(2);
    await act(async () => { view.rerender(frame("zh", "Ventas")); });
    expect(request).toHaveBeenCalledTimes(2);
    await act(async () => { await vi.advanceTimersByTimeAsync(CONNECTION_REFRESH_MS); });

    expect(screen.getByRole("status", { name: "已连接 SaaS" })).toHaveClass("online");
    expect(screen.getByRole("button", { name: "检查 SaaS 连接" })).toBeEnabled();
    expect(request).toHaveBeenCalledTimes(3);
    expect(addressFetch).not.toHaveBeenCalled();
  });

  it("keeps a new check busy when an aborted request finishes late", async () => {
    let finishOld!: (result: { saasConnected: boolean }) => void;
    request.mockReturnValueOnce(new Promise((resolve) => { finishOld = resolve; }));
    await mountFrame();
    const oldSignal = request.mock.calls[0][1]?.signal;
    fireEvent(window, new Event("offline"));
    expect(oldSignal?.aborted).toBe(true);

    let finishNew!: (result: { saasConnected: boolean }) => void;
    request.mockReturnValueOnce(new Promise((resolve) => { finishNew = resolve; }));
    fireEvent.click(screen.getByRole("button", { name: "Comprobar conexión con SaaS" }));
    await act(async () => { finishOld({ saasConnected: true }); });

    expect(screen.getByRole("status")).toHaveClass("offline");
    expect(screen.getByRole("button", { name: "Comprobando conexión con SaaS" })).toBeDisabled();
    expect(request).toHaveBeenCalledTimes(2);
    await act(async () => { finishNew({ saasConnected: true }); });
    expect(screen.getByRole("status")).toHaveClass("online");
    expect(screen.getByRole("button", { name: "Comprobar conexión con SaaS" })).toBeEnabled();
  });
});
