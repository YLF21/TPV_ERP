// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { prepareOfflineApplicationClose, resetOfflineApplicationClosePreparation } from "../sale/offlineClosePreparation";
import { BackendConnectionRecovery } from "./BackendConnectionRecovery";

vi.mock("../sale/offlineClosePreparation", () => ({
  prepareOfflineApplicationClose: vi.fn(async () => undefined),
  resetOfflineApplicationClosePreparation: vi.fn(),
}));

type RecoveryStatus = Awaited<ReturnType<NonNullable<NonNullable<Window["tpvDesktop"]>["connectionRecovery"]>["status"]>>;

const connected: RecoveryStatus = { ok: true, state: "CONNECTED" };
const offline: RecoveryStatus = { ok: true, state: "OFFLINE" };
let publishStatus: (status: RecoveryStatus) => void;
let unsubscribe: ReturnType<typeof vi.fn>;
let status: ReturnType<typeof vi.fn>;
let retry: ReturnType<typeof vi.fn>;
let closeApplication: ReturnType<typeof vi.fn>;

beforeEach(() => {
  const root = document.createElement("div");
  root.id = "root";
  document.body.append(root);
  unsubscribe = vi.fn();
  status = vi.fn(async () => connected);
  retry = vi.fn(async () => connected);
  closeApplication = vi.fn(async () => undefined);
  window.tpvDesktop = {
    closeApplication,
    connectionRecovery: {
      status,
      retry,
      onStatus: (callback: (status: RecoveryStatus) => void) => {
        publishStatus = callback;
        return unsubscribe;
      },
    },
  } as unknown as Window["tpvDesktop"];
  vi.mocked(prepareOfflineApplicationClose).mockReset();
  vi.mocked(prepareOfflineApplicationClose).mockResolvedValue(undefined);
  vi.mocked(resetOfflineApplicationClosePreparation).mockClear();
});

afterEach(() => {
  cleanup();
  document.getElementById("root")?.remove();
  delete window.tpvDesktop;
  vi.restoreAllMocks();
});

describe("BackendConnectionRecovery", () => {
  it("shows one global modal after a runtime transport loss and keeps it open when retry fails", async () => {
    retry.mockResolvedValue(offline);
    render(<BackendConnectionRecovery locale="es" />);
    await waitFor(() => expect(status).toHaveBeenCalledTimes(1));
    act(() => publishStatus(offline));
    expect(screen.getAllByRole("alertdialog")).toHaveLength(1);
    expect(screen.getByText("Se ha perdido la conexión con el servidor")).toBeInTheDocument();
    expect(document.getElementById("root")?.inert).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Reintentar" }));
    await waitFor(() => expect(retry).toHaveBeenCalledTimes(1));
    expect(screen.getAllByRole("alertdialog")).toHaveLength(1);
    expect(closeApplication).not.toHaveBeenCalled();
  });

  it("shows a startup message, then dismisses on successful retry without changing the app state", async () => {
    status.mockResolvedValue(offline);
    const onRecovered = vi.fn();
    render(<BackendConnectionRecovery locale="en" onRecovered={onRecovered} />);
    expect(await screen.findByText("Could not connect to the server at startup")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(retry).toHaveBeenCalledTimes(1);
    expect(onRecovered).toHaveBeenCalledTimes(1);
    expect(closeApplication).not.toHaveBeenCalled();
  });

  it.each([
    ["es", "REINTENTAR CONEXIÓN", "CONFIGURAR CONEXIÓN"],
    ["en", "RETRY CONNECTION", "CONFIGURE CONNECTION"],
    ["zh", "重试连接", "配置连接"],
  ] as const)("offers only retry and configure with keyboard access in %s", async (locale, retryLabel, configureLabel) => {
    status.mockResolvedValue(offline);
    const onConfigureConnection = vi.fn();
    render(<BackendConnectionRecovery locale={locale} onConfigureConnection={onConfigureConnection} />);
    const retryButton = await screen.findByRole("button", { name: retryLabel });
    const configureButton = screen.getByRole("button", { name: configureLabel });
    expect(screen.getAllByRole("button")).toHaveLength(2);
    const user = userEvent.setup();
    await waitFor(() => expect(retryButton).toHaveFocus());
    expect(document.getElementById("root")?.inert).toBe(true);
    await user.tab();
    expect(configureButton).toHaveFocus();
    await user.tab();
    expect(retryButton).toHaveFocus();
    await user.tab();
    await user.keyboard("{Enter}");
    expect(onConfigureConnection).toHaveBeenCalledOnce();
    expect(retry).not.toHaveBeenCalled();
  });

  it("prepares offline data before closing and blocks closing if preparation fails", async () => {
    status.mockResolvedValue(offline);
    vi.mocked(prepareOfflineApplicationClose).mockRejectedValueOnce(new Error("storage failed"));
    render(<BackendConnectionRecovery locale="es" />);
    fireEvent.click(await screen.findByRole("button", { name: "Cerrar aplicación" }));
    expect(await screen.findByText("No se pudo preparar el cierre seguro. Vuelve a intentarlo.")).toBeInTheDocument();
    expect(closeApplication).not.toHaveBeenCalled();
    expect(resetOfflineApplicationClosePreparation).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "Cerrar aplicación" }));
    await waitFor(() => expect(closeApplication).toHaveBeenCalledTimes(1));
    expect(vi.mocked(prepareOfflineApplicationClose).mock.invocationCallOrder[1])
      .toBeLessThan(closeApplication.mock.invocationCallOrder[0]);
  });

  it("ignores SaaS activity and unsubscribes on unmount", async () => {
    const view = render(<BackendConnectionRecovery locale="zh" />);
    await waitFor(() => expect(status).toHaveBeenCalledTimes(1));
    window.dispatchEvent(new Event("tpv:saas-disconnected"));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    view.unmount();
    expect(unsubscribe).toHaveBeenCalledTimes(1);
  });
  it("contains ERP keydown and keyup shortcuts while retaining default button activation", async () => {
    status.mockResolvedValue(offline);
    const keydown = vi.fn(), keyup = vi.fn();
    window.addEventListener("keydown", keydown);
    window.addEventListener("keyup", keyup);
    const view = render(<BackendConnectionRecovery locale="es" />);
    try {
      const button = await screen.findByRole("button", { name: "Reintentar" });
      await waitFor(() => {
        expect(document.getElementById("root")?.inert).toBe(true);
        expect(button).toHaveFocus();
      });
      for (const key of ["F2", "F3", "+", "-", "PrintScreen", " ", "Enter"]) {
        expect(fireEvent.keyDown(button, { key, bubbles: true, cancelable: true })).toBe(true);
        fireEvent.keyUp(button, { key, bubbles: true });
      }
      expect(keydown).not.toHaveBeenCalled(); expect(keyup).not.toHaveBeenCalled();
      view.unmount();
      expect(document.getElementById("root")?.inert).toBe(false);
      fireEvent.keyDown(window, { key: "F2" });
      expect(keydown).toHaveBeenCalledOnce();
    } finally {
      view.unmount();
      window.removeEventListener("keydown", keydown);
      window.removeEventListener("keyup", keyup);
    }
  });
});
