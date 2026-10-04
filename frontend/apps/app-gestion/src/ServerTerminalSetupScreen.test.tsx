/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ServerTerminalSetupScreen } from "./ServerTerminalSetupScreen";

function response(body: unknown) {
  return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
}

describe("ServerTerminalSetupScreen", () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks(); delete window.tpvDesktop; });

  it("opens protected linking when the installation is already provisioned", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 200 }))
      .mockResolvedValueOnce(response({ organizationProvisioned: true }));
    const onOrganizationReady = vi.fn();
    render(<ServerTerminalSetupScreen locale="es" onOrganizationReady={onOrganizationReady} onConfigureConnection={vi.fn()} />);
    await waitFor(() => expect(onOrganizationReady).toHaveBeenCalledOnce());
    expect(fetchMock.mock.calls.every(call => !String(call[0]).includes("/terminals/server/provision"))).toBe(true);
  });

  it("links an initial SaaS license then opens linking without rotating terminal credentials", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(null, { status: 200 }))
      .mockResolvedValueOnce(response({ organizationProvisioned: false }))
      .mockResolvedValueOnce(response({ accessToken: "installation-token", mustChangePassword: false }))
      .mockResolvedValueOnce(response({ licenseReference: "LIC-1" }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const onOrganizationReady = vi.fn();
    render(<ServerTerminalSetupScreen locale="es" onOrganizationReady={onOrganizationReady} onConfigureConnection={vi.fn()} />);
    fireEvent.change(await screen.findByLabelText("Código de emparejamiento"), { target: { value: " PAIR-NEW " } });
    fireEvent.change(screen.getByLabelText("Contraseña"), { target: { value: "1234" } });
    fireEvent.click(screen.getByRole("button", { name: "Configurar terminal" }));
    await waitFor(() => expect(onOrganizationReady).toHaveBeenCalledOnce());
    expect(fetchMock.mock.calls.some(call => String(call[0]).includes("/licenses/link-saas/bootstrap-empty"))).toBe(true);
    expect(fetchMock.mock.calls.some(call => String(call[0]).includes("/terminals/server/provision"))).toBe(false);
  });

  it("lets an offline installation open connection settings", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(new Response(null, { status: 503 }));
    const onConfigureConnection = vi.fn();
    render(<ServerTerminalSetupScreen locale="es" onOrganizationReady={vi.fn()} onConfigureConnection={onConfigureConnection} />);
    fireEvent.click(await screen.findByRole("button", { name: "CONFIGURAR CONEXIÓN" }));
    expect(onConfigureConnection).toHaveBeenCalledOnce();
  });
});
