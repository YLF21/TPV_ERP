// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("react-dom/client", () => ({ createRoot: vi.fn(() => ({ render: vi.fn() })) }));
vi.mock("./GestionLoginScreen", () => ({ default: () => <section aria-label="gestion login">Login</section> }));
vi.mock("./ServerTerminalSetupScreen", () => ({ ServerTerminalSetupScreen: ({ locale, onLocaleChange, onConfigureConnection, onRecoverFromBackup, newInstallation }: {
  locale: string; onLocaleChange: (locale: "en") => void; onConfigureConnection: () => void; onRecoverFromBackup?: () => void; newInstallation?: boolean;
}) => <section aria-label="gestion setup" data-locale={locale}>Setup
  <button onClick={() => onLocaleChange("en")}>English</button>
  <button onClick={onConfigureConnection}>Configure connection</button>
  {newInstallation && onRecoverFromBackup && <button onClick={onRecoverFromBackup}>Recuperar desde copia</button>}
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
    const load = vi.fn().mockResolvedValue({ ok: true, identity: null, newInstallation: true });
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
      backendConnection: {},
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
    fireEvent.click(screen.getByRole("button", { name: "REINTENTAR CONEXIÓN" }));
    expect(await screen.findByLabelText("gestion login")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(load).toHaveBeenCalledTimes(2);
    expect(retry).toHaveBeenCalledTimes(1);
  });

  it("reserves SaaS backup recovery for first installation without an offline modal blocking it", async () => {
    const root = document.createElement("div");
    root.id = "root";
    document.body.append(root);
    const load = vi.fn().mockResolvedValue({ ok: true, identity: null, newInstallation: true });
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
      const recoveryButton = await screen.findByRole("button", { name: "Recuperar desde copia" });
      expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
      expect(root.inert).not.toBe(true);
      recoveryButton.focus();
      expect(recoveryButton).toHaveFocus();
      fireEvent.keyDown(recoveryButton, { key: "Enter" });
      fireEvent.click(recoveryButton);

      expect(await screen.findByRole("heading", { name: "Recuperación del servidor desde copia" })).toBeInTheDocument();
      expect(root.inert).not.toBe(true);
      expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
      expect(await screen.findByText("Genera la solicitud en este servidor nuevo y facilita el archivo público a soporte.")).toBeInTheDocument();
      expect(readStatus).toHaveBeenCalledOnce();
      expect(startMaintenance).not.toHaveBeenCalled();

      fireEvent.click(screen.getByRole("button", { name: "Cerrar" }));
      expect(await screen.findByLabelText("gestion setup")).toBeInTheDocument();
      expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
      expect(root.inert).not.toBe(true);
      expect(load).toHaveBeenCalledTimes(2);
      expect(retry).not.toHaveBeenCalled();
    } finally {
      root.remove();
    }
  });

  it.each(["offline", "unreadable"])("hides recovery and opens connection settings for an existing or %s identity", async scenario => {
    const load = scenario === "offline"
      ? vi.fn().mockResolvedValue({ ok: true, identity: null, newInstallation: false, connectionUnavailable: true })
      : vi.fn().mockRejectedValue(new Error("Unreadable identity"));
    window.tpvDesktop = {
      terminalIdentity: { load, save: vi.fn() }, backendConnection: {},
      disasterRecovery: { readStatus: vi.fn(), startMaintenance: vi.fn() },
      serverTransfer: { readStatus: vi.fn(), startMaintenance: vi.fn() },
      connectionRecovery: {
        status: vi.fn().mockResolvedValue({ ok: true, state: "OFFLINE" }),
        retry: vi.fn(), onStatus: vi.fn(() => vi.fn()),
      },
    } as unknown as Window["tpvDesktop"];
    render(<App />);
    const modal = await screen.findByRole("alertdialog");
    expect(modal.querySelectorAll("button")).toHaveLength(2);
    expect(screen.getByRole("button", { name: "REINTENTAR CONEXIÓN" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Recuperar desde copia" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "CONFIGURAR CONEXIÓN" }));
    expect(await screen.findByLabelText("terminal connection")).toBeInTheDocument();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Back to setup" }));
    expect(await screen.findByRole("alertdialog")).toBeInTheDocument();
  });

  it("offers only connection actions after a runtime disconnect and keeps the saved terminal on retry", async () => {
    const load = vi.fn().mockResolvedValue({ ok: true, newInstallation: false, identity: {
      storeName: "Tienda Real", terminalCode: "001", terminalId: "verified-terminal", terminalCredential: "verified-proof",
    } });
    let publish!: (value: { ok: true; state: "OFFLINE" }) => void;
    window.tpvDesktop = {
      terminalIdentity: { load, save: vi.fn() }, backendConnection: {},
      disasterRecovery: { readStatus: vi.fn(), startMaintenance: vi.fn() },
      connectionRecovery: {
        status: vi.fn().mockResolvedValue({ ok: true, state: "CONNECTED" }),
        retry: vi.fn().mockResolvedValue({ ok: true, state: "CONNECTED" }),
        onStatus: vi.fn((callback: (value: { ok: true; state: "OFFLINE" }) => void) => { publish = callback; return vi.fn(); }),
      },
    } as unknown as Window["tpvDesktop"];
    render(<App />);
    expect(await screen.findByLabelText("gestion login")).toBeInTheDocument();
    await waitFor(() => expect(publish).toBeDefined());
    act(() => publish({ ok: true, state: "OFFLINE" }));
    const modal = await screen.findByRole("alertdialog");
    expect(modal.querySelectorAll("button")).toHaveLength(2);
    expect(screen.queryByRole("button", { name: "Recuperar desde copia" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "CONFIGURAR CONEXIÓN" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "REINTENTAR CONEXIÓN" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(screen.getByLabelText("gestion login")).toBeInTheDocument();
    expect(load).toHaveBeenCalledOnce();
  });

  it("reloads the existing terminal when returning from configuration after connectivity resumes", async () => {
    const load = vi.fn().mockResolvedValueOnce({ ok: true, identity: null, newInstallation: false })
      .mockResolvedValue({ ok: true, newInstallation: false, identity: {
        storeName: "Tienda Real", terminalCode: "001", terminalId: "verified-terminal", terminalCredential: "verified-proof",
      } });
    window.tpvDesktop = {
      terminalIdentity: { load, save: vi.fn() }, backendConnection: {},
      connectionRecovery: {
        status: vi.fn().mockResolvedValueOnce({ ok: true, state: "OFFLINE" }).mockResolvedValue({ ok: true, state: "CONNECTED" }),
        retry: vi.fn(), onStatus: vi.fn(() => vi.fn()),
      },
    } as unknown as Window["tpvDesktop"];
    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "CONFIGURAR CONEXIÓN" }));
    expect(await screen.findByLabelText("terminal connection")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Back to setup" }));
    expect(await screen.findByLabelText("gestion login")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(load).toHaveBeenCalledTimes(2);
  });
});
