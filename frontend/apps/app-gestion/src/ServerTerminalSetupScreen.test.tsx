/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ServerTerminalSetupScreen } from "./ServerTerminalSetupScreen";

describe("ServerTerminalSetupScreen", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.localStorage.clear();
    delete window.tpvDesktop;
  });

  it("stores the one-time server credential only through the protected desktop bridge", async () => {
    const save = vi.fn().mockResolvedValue({ ok: true });
    window.tpvDesktop = {
      closeApplication: vi.fn(),
      terminalIdentity: { load: vi.fn(), save }
    };
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 200 }))
      .mockResolvedValueOnce(response({ organizationProvisioned: true }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        accessToken: "installation-token",
        mustChangePassword: false
      }), { status: 200, headers: { "Content-Type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        terminalId: "terminal-1",
        terminalCode: "SERVIDOR",
        storeName: "TIENDA 001",
        terminalCredential: "one-time-secret"
      }), { status: 200, headers: { "Content-Type": "application/json" } }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const onProvisioned = vi.fn();
    render(<ServerTerminalSetupScreen locale="es" onProvisioned={onProvisioned} />);

    await screen.findByText("Backend conectado");
    fireEvent.change(screen.getByLabelText("Contraseña"), { target: { value: "1234" } });
    fireEvent.click(screen.getByRole("button", { name: "Configurar terminal" }));

    await waitFor(() => expect(onProvisioned).toHaveBeenCalled());
    expect(save).toHaveBeenCalledWith({
      storeName: "TIENDA 001",
      terminalCode: "SERVIDOR",
      terminalId: "terminal-1",
      terminalCredential: "one-time-secret"
    });
    expect(window.localStorage.length).toBe(0);
    expect(fetchMock.mock.calls[3][0]).toContain("/terminals/server/provision");
  });

  it("explains an offline backend and lets the administrator retry", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
      .mockResolvedValueOnce(new Response(null, { status: 200 }))
      .mockResolvedValueOnce(response({ organizationProvisioned: true }));

    render(<ServerTerminalSetupScreen locale="es" onProvisioned={vi.fn()} />);

    expect(await screen.findByText("No se puede conectar con el servidor.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Configurar terminal" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: /Reintentar/ }));
    expect(await screen.findByText("Backend conectado")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("allows checking the entered password without changing its value", async () => {
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 200 }))
      .mockResolvedValueOnce(response({ organizationProvisioned: true }));
    render(<ServerTerminalSetupScreen locale="es" onProvisioned={vi.fn()} />);
    await screen.findByText("Backend conectado");

    const password = screen.getByLabelText("Contraseña");
    fireEvent.change(password, { target: { value: "1234" } });
    fireEvent.click(screen.getByRole("button", { name: "Mostrar" }));

    expect(password).toHaveAttribute("type", "text");
    expect(password).toHaveValue("1234");
    expect(screen.getByRole("button", { name: "Ocultar" })).toHaveAttribute("aria-pressed", "true");
  });

  it("links an initial SaaS licence before provisioning an empty installation", async () => {
    const save = vi.fn().mockResolvedValue({ ok: true });
    window.tpvDesktop = {
      closeApplication: vi.fn(),
      terminalIdentity: { load: vi.fn(), save }
    };
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 200 }))
      .mockResolvedValueOnce(response({ organizationProvisioned: false }))
      .mockResolvedValueOnce(response({
        accessToken: "installation-token",
        mustChangePassword: false
      }))
      .mockResolvedValueOnce(response({ licenseReference: "LIC-1" }))
      .mockResolvedValueOnce(response({
        terminalId: "terminal-1",
        terminalCode: "SERVIDOR",
        storeName: "TIENDA 001",
        terminalCredential: "one-time-secret"
      }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));

    render(<ServerTerminalSetupScreen locale="es" onProvisioned={vi.fn()} />);

    fireEvent.change(await screen.findByLabelText("Código de emparejamiento"), {
      target: { value: "  PAIR-NEW  " }
    });
    fireEvent.change(screen.getByLabelText("Contraseña"), { target: { value: "1234" } });
    fireEvent.click(screen.getByRole("button", { name: "Configurar terminal" }));

    await waitFor(() => expect(save).toHaveBeenCalled());
    expect(fetchMock.mock.calls[3][0]).toContain("/licenses/link-saas/bootstrap-empty");
    expect(fetchMock.mock.calls[3][1]).toMatchObject({
      method: "POST",
      body: JSON.stringify({ pairingCode: "PAIR-NEW" })
    });
    expect(fetchMock.mock.calls[4][0]).toContain("/terminals/server/provision");
  });

  it("does not blame the licence when terminal provisioning fails after a successful initial link", async () => {
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 200 }))
      .mockResolvedValueOnce(response({ organizationProvisioned: false }))
      .mockResolvedValueOnce(response({
        accessToken: "installation-token",
        mustChangePassword: false
      }))
      .mockResolvedValueOnce(response({ licenseReference: "LIC-1" }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ message: "provision_failed" }), {
        status: 500,
        headers: { "Content-Type": "application/json" }
      }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));

    render(<ServerTerminalSetupScreen locale="es" onProvisioned={vi.fn()} />);

    fireEvent.change(await screen.findByLabelText("Código de emparejamiento"), {
      target: { value: "PAIR-NEW" }
    });
    fireEvent.change(screen.getByLabelText("Contraseña"), { target: { value: "1234" } });
    fireEvent.click(screen.getByRole("button", { name: "Configurar terminal" }));

    expect(await screen.findByText("No se pudo configurar el terminal servidor.")).toBeInTheDocument();
    expect(screen.queryByText(/No se pudo vincular la licencia inicial/)).not.toBeInTheDocument();
  });
});

function response(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });
}
