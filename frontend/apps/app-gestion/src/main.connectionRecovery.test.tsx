// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("react-dom/client", () => ({ createRoot: vi.fn(() => ({ render: vi.fn() })) }));
vi.mock("./GestionLoginScreen", () => ({ default: () => <section aria-label="gestion login">Login</section> }));
vi.mock("./ServerTerminalSetupScreen", () => ({ ServerTerminalSetupScreen: ({ locale, onLocaleChange, onConfigureConnection }: {
  locale: string; onLocaleChange: (locale: "en") => void; onConfigureConnection: () => void;
}) => <section aria-label="gestion setup" data-locale={locale}>Setup
  <button onClick={() => onLocaleChange("en")}>English</button>
  <button onClick={onConfigureConnection}>Configure connection</button>
</section> }));
vi.mock("../../../packages/app-common/src/components/TerminalConnectionScreen", () => ({ TerminalConnectionScreen: ({ locale, onLocaleChange, onBack }: {
  locale: string; onLocaleChange: (locale: "zh") => void; onBack: () => void;
}) => <section aria-label="terminal connection" data-locale={locale}>
  <button onClick={() => onLocaleChange("zh")}>中文</button>
  <button onClick={onBack}>Back to setup</button>
</section> }));

import { App } from "./main";

afterEach(() => {
  cleanup();
  delete window.tpvDesktop;
});

describe("APP GESTIÓN startup connection recovery", () => {
  it("keeps the language while moving between connection and installation setup without reloading identity", async () => {
    const load = vi.fn().mockResolvedValue({ ok: true, identity: null });
    window.tpvDesktop = {
      terminalIdentity: { load, save: vi.fn() }, backendConnection: {},
    } as unknown as Window["tpvDesktop"];
    render(<App />);
    expect(await screen.findByLabelText("gestion setup")).toHaveAttribute("data-locale", "es");
    fireEvent.click(screen.getByRole("button", { name: "English" }));
    fireEvent.click(screen.getByRole("button", { name: "Configure connection" }));
    expect(screen.getByLabelText("terminal connection")).toHaveAttribute("data-locale", "en");
    fireEvent.click(screen.getByRole("button", { name: "中文" }));
    fireEvent.click(screen.getByRole("button", { name: "Back to setup" }));
    expect(screen.getByLabelText("gestion setup")).toHaveAttribute("data-locale", "zh");
    expect(load).toHaveBeenCalledOnce();
  });

  it("reloads the saved terminal after a successful retry and returns to login", async () => {
    const load = vi.fn().mockResolvedValueOnce({ ok: true, identity: null })
      .mockResolvedValue({ ok: true, identity: {
        storeName: "Tienda Real", terminalCode: "001", terminalId: "verified-terminal",
        terminalCredential: "verified-proof",
      } });
    const retry = vi.fn(async () => ({ ok: true as const, state: "CONNECTED" as const }));
    window.tpvDesktop = {
      closeApplication: vi.fn(async () => undefined),
      terminalIdentity: { load, save: vi.fn() },
      connectionRecovery: {
        status: vi.fn(async () => ({ ok: true as const, state: "OFFLINE" as const })),
        retry,
        onStatus: vi.fn(() => vi.fn()),
      },
    } as unknown as Window["tpvDesktop"];

    render(<App />);
    expect(await screen.findByLabelText("gestion setup")).toBeInTheDocument();
    expect(await screen.findByText("No se pudo conectar con el servidor al iniciar")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(await screen.findByLabelText("gestion login")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(load).toHaveBeenCalledTimes(2);
    expect(retry).toHaveBeenCalledTimes(1);
  });

  it("opens installed SaaS backup recovery while offline and clears modal inert state between views", async () => {
    const root = document.createElement("div");
    root.id = "root";
    document.body.append(root);
    const load = vi.fn().mockResolvedValue({ ok: true, identity: null });
    const readStatus = vi.fn().mockResolvedValue({ ok: true, state: "NONE" });
    const startMaintenance = vi.fn();
    const status = vi.fn().mockResolvedValue({ ok: true, state: "OFFLINE" });
    const retry = vi.fn().mockResolvedValue({ ok: true, state: "CONNECTED" });
    window.tpvDesktop = {
      terminalIdentity: { load, save: vi.fn() },
      disasterRecovery: { readStatus, startMaintenance },
      connectionRecovery: { status, retry, onStatus: vi.fn(() => vi.fn()) },
    } as unknown as Window["tpvDesktop"];

    try {
      render(<App />, { container: root });
      expect(await screen.findByRole("alertdialog")).toBeInTheDocument();
      expect(root.inert).toBe(true);
      const recoveryButton = screen.getByRole("button", { name: "Recuperar desde copia" });
      recoveryButton.focus();
      expect(recoveryButton).toHaveFocus();
      fireEvent.keyDown(recoveryButton, { key: "Enter" });
      fireEvent.click(recoveryButton);

      expect(await screen.findByRole("heading", { name: "Recuperación del servidor desde copia" })).toBeInTheDocument();
      await waitFor(() => expect(root.inert).toBe(false));
      expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
      expect(await screen.findByText("Genera la solicitud en este servidor nuevo y facilita el archivo público a soporte.")).toBeInTheDocument();
      expect(readStatus).toHaveBeenCalledOnce();
      expect(startMaintenance).not.toHaveBeenCalled();

      fireEvent.click(screen.getByRole("button", { name: "Cerrar" }));
      expect(await screen.findByRole("alertdialog")).toBeInTheDocument();
      expect(root.inert).toBe(true);
      fireEvent.click(screen.getByRole("button", { name: "Reintentar" }));
      await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
      expect(root.inert).toBe(false);
      expect(await screen.findByLabelText("gestion setup")).toBeInTheDocument();
      expect(load).toHaveBeenCalledTimes(3);
      expect(retry).toHaveBeenCalledOnce();
    } finally {
      root.remove();
    }
  });
});
