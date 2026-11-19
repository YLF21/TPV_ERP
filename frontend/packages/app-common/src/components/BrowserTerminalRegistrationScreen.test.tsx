// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import { cancelWebTerminal, loadWebTerminalStatus, requestWebTerminal, type WebTerminalStatus } from "../webTerminalIdentity";
import { BrowserTerminalRegistrationScreen } from "./BrowserTerminalRegistrationScreen";

vi.mock("../webTerminalIdentity", () => ({ loadWebTerminalStatus: vi.fn(), requestWebTerminal: vi.fn(), cancelWebTerminal: vi.fn() }));
const free: WebTerminalStatus = { status: "UNREGISTERED", slots: [{ code: "002", status: "FREE" }, { code: "003", status: "ACTIVE" }] };
const context = { credentialMode: "browser-cookie" as const, terminalCode: "002", terminalId: "terminal", installationId: "installation", bindingId: "binding", storeId: "store", storeName: "My store" };
const renderScreen = (initialStatus: WebTerminalStatus = free, onReady = vi.fn()) => {
  render(<BrowserTerminalRegistrationScreen locale="es" onLocaleChange={vi.fn()} initialStatus={initialStatus} onReady={onReady} />);
  return onReady;
};
beforeEach(() => vi.resetAllMocks());
afterEach(() => { cleanup(); vi.useRealTimers(); });

describe("browser terminal registration", () => {
  it.each([
    ["es", "Idioma", "es"], ["en", "Language", "en"], ["zh", "语言", "zh-CN"]
  ] as const)("keeps an accessible language name and content language in %s", (locale, label, lang) => {
    render(<BrowserTerminalRegistrationScreen locale={locale} onLocaleChange={vi.fn()} initialStatus={free} onReady={vi.fn()} />);
    expect(screen.getByRole("combobox", { name: label })).toHaveAttribute("aria-label", label);
    expect(screen.getByRole("heading", { level: 1 }).closest(".web-terminal-page")).toHaveAttribute("lang", lang);
  });

  it("requests only a free workstation, waits for approval and opens login only after ACTIVE", async () => {
    vi.mocked(requestWebTerminal).mockResolvedValue({ status: "PENDING", slots: [] });
    vi.mocked(loadWebTerminalStatus).mockResolvedValue({ status: "ACTIVE", context, slots: [] });
    const ready = renderScreen();
    expect(screen.queryByRole("option", { name: "003" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Solicitar registro" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Puesto disponible"), { target: { value: "002" } });
    fireEvent.change(screen.getByLabelText("Nombre de este puesto"), { target: { value: " Caja " } });
    fireEvent.click(screen.getByRole("button", { name: "Solicitar registro" }));
    expect(await screen.findByRole("heading", { name: "Esperando aprobación" })).toBeInTheDocument();
    expect(requestWebTerminal).toHaveBeenCalledWith("002", " Caja ", expect.any(AbortSignal));
    expect(ready).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Comprobar estado" }));
    await waitFor(() => expect(ready).toHaveBeenCalledExactlyOnceWith(context));
  });

  it("requires a separate cookie reset before requesting a replacement terminal", async () => {
    vi.mocked(cancelWebTerminal).mockResolvedValue(free);
    renderScreen({ status: "EXPIRED", slots: free.slots });
    expect(screen.queryByLabelText("Nombre de este puesto")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Preparar nuevo registro" }));
    expect(await screen.findByLabelText("Nombre de este puesto")).toBeInTheDocument();
    expect(cancelWebTerminal).toHaveBeenCalledOnce();
    expect(requestWebTerminal).not.toHaveBeenCalled();
  });

  it("explains disabled web access without displaying server internals", async () => {
    vi.mocked(loadWebTerminalStatus).mockRejectedValue(new ApiError("private database detail", 404, { code: "WEB_TERMINAL_DISABLED" }));
    renderScreen();
    fireEvent.click(screen.getByRole("button", { name: "Comprobar estado" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("todavía no está habilitado");
    expect(screen.queryByText("private database detail")).not.toBeInTheDocument();
  });

  it("does not continue an approval response after leaving the registration screen", async () => {
    let resolve!: (state: WebTerminalStatus) => void;
    vi.mocked(loadWebTerminalStatus).mockReturnValue(new Promise(done => { resolve = done; }));
    const ready = renderScreen({ status: "PENDING", slots: [] });
    fireEvent.click(screen.getByRole("button", { name: "Comprobar estado" }));
    cleanup();
    await act(async () => resolve({ status: "ACTIVE", context, slots: [] }));
    expect(ready).not.toHaveBeenCalled();
  });

  it("pauses pending polling while hidden and resumes when the page is visible", async () => {
    vi.useFakeTimers();
    vi.mocked(loadWebTerminalStatus).mockResolvedValue({ status: "PENDING", slots: [] });
    renderScreen({ status: "PENDING", slots: [] });
    const visibility = vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    await act(async () => vi.advanceTimersByTimeAsync(5000));
    expect(loadWebTerminalStatus).not.toHaveBeenCalled();
    visibility.mockReturnValue("visible");
    await act(async () => { fireEvent(document, new Event("visibilitychange")); });
    expect(loadWebTerminalStatus).toHaveBeenCalledOnce();
    visibility.mockRestore();
  });
});
