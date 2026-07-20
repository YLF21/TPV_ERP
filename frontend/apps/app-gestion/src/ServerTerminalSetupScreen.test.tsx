/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ServerTerminalSetupScreen } from "./ServerTerminalSetupScreen";

describe("ServerTerminalSetupScreen", () => {
  afterEach(() => {
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
    expect(fetchMock.mock.calls[1][0]).toContain("/terminals/server/provision");
  });
});
