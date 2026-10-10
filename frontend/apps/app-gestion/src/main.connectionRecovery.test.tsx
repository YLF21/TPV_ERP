// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("react-dom/client", () => ({ createRoot: vi.fn(() => ({ render: vi.fn() })) }));
vi.mock("./GestionLoginScreen", () => ({ default: () => <section aria-label="gestion login">Login</section> }));
vi.mock("./ServerTerminalSetupScreen", () => ({ ServerTerminalSetupScreen: () => <section aria-label="gestion setup">Setup</section> }));

import { App } from "./main";

afterEach(() => {
  cleanup();
  delete window.tpvDesktop;
});

describe("APP GESTIÓN startup connection recovery", () => {
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
});
