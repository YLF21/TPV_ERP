// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import { LoginScreen } from "./LoginScreen";
import type { TerminalContext } from "../types";

const mocks = vi.hoisted(() => ({
  authenticateRemote: vi.fn(),
  checkBackendConnection: vi.fn()
}));

vi.mock("../auth/auth", async (importOriginal) => ({
  ...await importOriginal<typeof import("../auth/auth")>(),
  authenticateRemote: mocks.authenticateRemote
}));

vi.mock("../api/client", async (importOriginal) => ({
  ...await importOriginal<typeof import("../api/client")>(),
  checkBackendConnection: mocks.checkBackendConnection
}));

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01"
};

describe("LoginScreen", () => {
  beforeEach(() => {
    mocks.checkBackendConnection.mockResolvedValue(true);
    mocks.authenticateRemote.mockReset();
    sessionStorage.clear();
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    vi.unstubAllGlobals();
  });

  it("renders the shared brand and context footer without user control", () => {
    const html = renderToStaticMarkup(
      <LoginScreen
        app="venta"
        locale="es"
        terminalContext={terminalContext}
        onLocaleChange={vi.fn()}
        onLogin={vi.fn()}
      />
    );

    expect(html).toContain('class="entry-topbar"');
    expect(html).toContain('class="top-date-time"');
    expect(html).toContain("APP VENTA");
    expect(html).toContain('class="report-footer-context"');
    expect(html).toContain("DB:");
    expect(html).toContain("Conexión");
    expect(html).not.toContain('class="report-user-button"');
  });

  it("renders an embedded login without desktop chrome", () => {
    const html = renderToStaticMarkup(
      <LoginScreen
        app="gestion"
        locale="es"
        terminalContext={terminalContext}
        onLocaleChange={vi.fn()}
        onLogin={vi.fn()}
        presentation="embedded"
        heading="Acceso PDA"
      />
    );

    expect(html).toContain('class="login-screen login-screen-embedded"');
    expect(html).toContain("Acceso PDA");
    expect(html).toContain("Tienda Principal");
    expect(html).not.toContain('class="entry-topbar"');
    expect(html).not.toContain('class="top-date-time"');
    expect(html).not.toContain('class="report-footer-context"');
    expect(html).not.toContain('class="language-button"');
  });

  it("shows the invalid-credentials warning, clears the password and returns focus to it", async () => {
    mocks.authenticateRemote.mockRejectedValueOnce(new ApiError("invalid_credentials", 401));
    render(
      <LoginScreen
        app="venta"
        locale="es"
        terminalContext={terminalContext}
        onLocaleChange={vi.fn()}
        onLogin={vi.fn()}
      />
    );

    await waitFor(() => expect(screen.getByRole("button", { name: "Entrar" })).toBeEnabled());
    const username = screen.getByLabelText("Usuario");
    const password = screen.getByLabelText("Contraseña");
    fireEvent.change(username, { target: { value: "ADMIN" } });
    fireEvent.change(password, { target: { value: "incorrecta" } });
    fireEvent.click(screen.getByRole("button", { name: "Entrar" }));

    expect(await screen.findByText("Usuario o contraseña incorrectos")).toBeVisible();
    await waitFor(() => {
      expect(password).toHaveValue("");
      expect(password).toHaveFocus();
    });
    expect(username).toHaveValue("ADMIN");
  });

  it("blocks login while the backend is offline and offers an explicit retry", async () => {
    mocks.checkBackendConnection.mockResolvedValue(false);
    render(
      <LoginScreen
        app="venta"
        locale="es"
        terminalContext={terminalContext}
        onLocaleChange={vi.fn()}
        onLogin={vi.fn()}
      />
    );

    expect(await screen.findByRole("alert")).toHaveTextContent("Sin conexion con backend");
    expect(screen.getByRole("button", { name: "Entrar" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Reintentar conexion" })).toBeEnabled();
  });

  it("enables login after a successful backend retry", async () => {
    mocks.checkBackendConnection
      .mockResolvedValueOnce(false)
      .mockResolvedValueOnce(true);
    render(
      <LoginScreen
        app="venta"
        locale="es"
        terminalContext={terminalContext}
        onLocaleChange={vi.fn()}
        onLogin={vi.fn()}
      />
    );

    fireEvent.click(await screen.findByRole("button", { name: "Reintentar conexion" }));

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Backend conectado"));
    expect(screen.getByRole("button", { name: "Entrar" })).toBeEnabled();
  });

  it("keeps recent usernames only for the current browser session", async () => {
    mocks.authenticateRemote.mockResolvedValue({
      userId: "user-1",
      username: "cajero",
      displayName: "Cajero",
      permissions: []
    });
    render(
      <LoginScreen
        app="venta"
        locale="es"
        terminalContext={terminalContext}
        onLocaleChange={vi.fn()}
        onLogin={vi.fn()}
      />
    );
    await waitFor(() => expect(screen.getByRole("button", { name: "Entrar" })).toBeEnabled());

    fireEvent.change(screen.getByLabelText("Usuario"), { target: { value: "cajero" } });
    fireEvent.change(screen.getByLabelText("Contraseña"), { target: { value: "secreto" } });
    fireEvent.click(screen.getByRole("button", { name: "Entrar" }));

    await waitFor(() =>
      expect(sessionStorage.getItem("tpverp.venta.loginUsers")).toContain("cajero")
    );
    expect(localStorage.getItem("tpverp.venta.loginUsers")).toBeNull();
  });
});
