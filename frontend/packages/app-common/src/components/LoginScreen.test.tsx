// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiConnectionError, ApiError } from "../api/client";
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
  companyName: "Empresa Real",
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

  it("renders the sales brand above the card and company context without a corner logo", () => {
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
    expect(html).toContain("esPOS VENTA");
    expect(html).toContain('class="login-sale-brand"');
    expect(html).toContain("Empresa Real");
    expect(html).not.toContain('class="app-brand-static"');
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
    expect(html).not.toContain('class="login-touch-keyboard"');
    expect(html).not.toContain("Mostrar teclado");
  });

  it("does not show the sales touch keyboard in the management login", () => {
    const html = renderToStaticMarkup(
      <LoginScreen app="gestion" locale="es" terminalContext={terminalContext}
        onLocaleChange={vi.fn()} onLogin={vi.fn()} />
    );

    expect(html).not.toContain('class="login-touch-keyboard"');
    expect(html).not.toContain("Mostrar teclado");
  });

  it("opens, folds and reopens the sales keyboard, edits the focused field and submits only on Entrar", async () => {
    mocks.authenticateRemote.mockResolvedValue({ userId: "user-1", permissions: [] });
    render(<LoginScreen app="venta" locale="es" terminalContext={terminalContext}
      onLocaleChange={vi.fn()} onLogin={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Entrar" })).toBeEnabled());

    const username = screen.getByRole<HTMLInputElement>("combobox", { name: "Usuario" });
    const password = screen.getByLabelText<HTMLInputElement>("Contraseña");
    const toggle = screen.getByRole("button", { name: "Mostrar teclado" });
    const submit = screen.getByRole("button", { name: "Entrar" });
    expect(password.compareDocumentPosition(toggle) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);
    expect(toggle.compareDocumentPosition(submit) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("group", { name: "Teclado alfanumérico" })).not.toBeInTheDocument();

    fireEvent.change(username, { target: { value: "USR" } });
    username.focus();
    username.setSelectionRange(2, 2);
    toggle.focus();
    await userEvent.setup().keyboard("{Enter}");
    expect(screen.getByRole("group", { name: "Teclado alfanumérico" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Cerrar teclado" })).toHaveAttribute("aria-expanded", "true");
    expect(mocks.authenticateRemote).not.toHaveBeenCalled();
    expect(username).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: /^E$/ }));
    expect(username).toHaveValue("USER");
    expect(username).toHaveFocus();
    expect(username.selectionStart).toBe(3);

    fireEvent.change(password, { target: { value: "screto" } });
    password.focus();
    password.setSelectionRange(1, 1);
    fireEvent.click(screen.getByRole("button", { name: "Cerrar teclado" }));
    expect(screen.queryByRole("group", { name: "Teclado alfanumérico" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Mostrar teclado" }));
    expect(password).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: /^E$/ }));
    expect(password).toHaveValue("sEcreto");
    expect(password).toHaveAttribute("type", "password");
    expect(password).toHaveFocus();
    expect(password.selectionStart).toBe(2);
    expect(mocks.authenticateRemote).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Entrar" }));
    await waitFor(() => expect(mocks.authenticateRemote).toHaveBeenCalledWith("USER", "sEcreto", "venta", terminalContext));
  });

  it("disables the toggle and keys while authentication is loading", async () => {
    let completeAuthentication!: (session: { userId: string; permissions: never[] }) => void;
    mocks.authenticateRemote.mockImplementationOnce(() => new Promise((resolve) => {
      completeAuthentication = resolve;
    }));
    render(<LoginScreen app="venta" locale="es" terminalContext={terminalContext}
      onLocaleChange={vi.fn()} onLogin={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Entrar" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Mostrar teclado" }));
    const key = screen.getByRole("button", { name: /^A$/ });
    fireEvent.click(screen.getByRole("button", { name: "Entrar" }));

    expect(await screen.findByRole("button", { name: "Entrando..." })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Cerrar teclado" })).toBeDisabled();
    expect(key).toBeDisabled();
    expect(screen.getByRole("combobox", { name: "Usuario" })).toBeDisabled();
    expect(screen.getByLabelText("Contraseña")).toBeDisabled();
    expect(mocks.authenticateRemote).toHaveBeenCalledOnce();
    completeAuthentication({ userId: "user-1", permissions: [] });
    await waitFor(() => expect(screen.getByRole("button", { name: "Entrar" })).toBeEnabled());
  });

  it.each(["es", "en", "zh"] as const)("keeps VENTAS fixed and shows only the terminal name in the sales login in %s", async (locale) => {
    const name = "TERMINAL PRINCIPAL";
    const { container } = render(<LoginScreen app="venta" locale={locale}
      terminalContext={{ ...terminalContext, terminalName: name }} onLocaleChange={vi.fn()} onLogin={vi.fn()} />);

    await waitFor(() => expect(container.querySelector(".login-submit")).toBeEnabled());
    expect(container.querySelector(".login-sale-brand > span")).toHaveTextContent(/^VENTAS$/);
    expect(container.querySelector(".login-context-heading > span")).toHaveTextContent(new RegExp(`^${name}$`));
    expect(container.querySelector(".login-panel-heading > span")).toHaveTextContent(`Tienda Principal · ${name}`);
    expect(container.querySelector(".report-footer-context > span:nth-child(2)")).toHaveTextContent(new RegExp(`^${name}$`));
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

    expect(await screen.findByRole("alert")).toHaveTextContent("Sin conexión con el servidor local");
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

    await waitFor(() => expect(screen.getByText("Backend conectado")).toHaveAttribute("role", "status"));
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

  it("selects a recent username with the keyboard without submitting or changing the password", async () => {
    sessionStorage.setItem("tpverp.venta.loginUsers", JSON.stringify(["ADMIN", "admin", "cajero"]));
    mocks.authenticateRemote.mockResolvedValue({ userId: "user-1", permissions: [] });
    render(<LoginScreen app="venta" locale="es" terminalContext={terminalContext}
      onLocaleChange={vi.fn()} onLogin={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Entrar" })).toBeEnabled());
    const username = screen.getByRole("combobox", { name: "Usuario" });
    const password = screen.getByLabelText("Contraseña");
    fireEvent.change(password, { target: { value: "demo-password" } });
    fireEvent.change(username, { target: { value: "ad" } });
    expect(screen.getAllByRole("option").map((option) => option.textContent)).toEqual(["ADMIN", "admin"]);
    fireEvent.keyDown(username, { key: "ArrowDown" });
    expect(screen.getByRole("option", { name: "admin" })).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(username, { key: "Enter" });
    expect(username).toHaveValue("admin");
    expect(username).toHaveFocus();
    expect(password).toHaveValue("demo-password");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(mocks.authenticateRemote).not.toHaveBeenCalled();
    expect(JSON.parse(sessionStorage.getItem("tpverp.venta.loginUsers")!)).toEqual(["ADMIN", "admin", "cajero"]);
    fireEvent.click(screen.getByRole("button", { name: "Entrar" }));
    await waitFor(() => expect(mocks.authenticateRemote).toHaveBeenCalledWith("admin", "demo-password", "venta", terminalContext));
    await waitFor(() => expect(JSON.parse(sessionStorage.getItem("tpverp.venta.loginUsers")!)).toEqual(["admin", "ADMIN", "cajero"]));
    expect(localStorage.getItem("tpverp.venta.loginUsers")).toBeNull();
  });

  it("opens the username list by pointer, closes on Escape or outside click, and chooses without submitting", async () => {
    sessionStorage.setItem("tpverp.venta.loginUsers", JSON.stringify(["ADMIN", "cajero"]));
    render(<LoginScreen app="venta" locale="es" terminalContext={terminalContext}
      onLocaleChange={vi.fn()} onLogin={vi.fn()} />);
    const username = screen.getByRole("combobox", { name: "Usuario" });
    fireEvent.click(await screen.findByRole("button", { name: "Historial de usuarios" }));
    expect(screen.getByRole("listbox")).toBeVisible();
    fireEvent.keyDown(username, { key: "Escape" });
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(username).toHaveValue("");
    fireEvent.click(screen.getByRole("button", { name: "Historial de usuarios" }));
    fireEvent.pointerDown(screen.getByLabelText("Contraseña"));
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Historial de usuarios" }));
    fireEvent.click(screen.getByRole("option", { name: "cajero" }));
    expect(username).toHaveValue("cajero");
    expect(username).toHaveFocus();
    expect(username).not.toHaveAttribute("list");
    expect(mocks.authenticateRemote).not.toHaveBeenCalled();
  });

  it("toggles password visibility without changing the value or submitting, and authenticates with it", async () => {
    mocks.authenticateRemote.mockResolvedValue({ userId: "user-1", permissions: [] });
    render(<LoginScreen app="venta" locale="es" terminalContext={terminalContext}
      onLocaleChange={vi.fn()} onLogin={vi.fn()} />);
    const password = screen.getByLabelText("Contraseña");
    fireEvent.change(screen.getByLabelText("Usuario"), { target: { value: " ADMIN " } });
    fireEvent.change(password, { target: { value: "secreto" } });
    fireEvent.click(screen.getByRole("button", { name: "Mostrar contraseña" }));
    expect(password).toHaveAttribute("type", "text");
    expect(password).toHaveValue("secreto");
    expect(screen.getByRole("button", { name: "Ocultar contraseña" })).toHaveAttribute("aria-pressed", "true");
    expect(mocks.authenticateRemote).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Ocultar contraseña" }));
    expect(password).toHaveAttribute("type", "password");
    await waitFor(() => expect(screen.getByRole("button", { name: "Entrar" })).toBeEnabled());
    fireEvent.submit(password.closest("form")!);
    await waitFor(() => expect(mocks.authenticateRemote).toHaveBeenCalledWith("ADMIN", "secreto", "venta", terminalContext));
  });

  it("offers connection configuration only after confirming disconnection and hides it after recovery", async () => {
    let completeCheck!: (online: boolean) => void;
    mocks.checkBackendConnection.mockImplementationOnce(() => new Promise<boolean>(resolve => { completeCheck = resolve; }))
      .mockResolvedValueOnce(true);
    const configure = vi.fn();
    render(<LoginScreen app="venta" locale="es" terminalContext={terminalContext}
      onLocaleChange={vi.fn()} onLogin={vi.fn()} onConfigureConnection={configure} />);
    expect(screen.queryByRole("button", { name: "CONFIGURAR CONEXIÓN" })).not.toBeInTheDocument();
    completeCheck(false);
    fireEvent.click(await screen.findByRole("button", { name: "CONFIGURAR CONEXIÓN" }));
    expect(configure).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByRole("button", { name: "Reintentar conexion" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Entrar" })).toBeEnabled());
    expect(screen.queryByRole("button", { name: "CONFIGURAR CONEXIÓN" })).not.toBeInTheDocument();
  });

  it("shows disconnection and configuration when the connection fails during authentication", async () => {
    mocks.authenticateRemote.mockRejectedValueOnce(new ApiConnectionError("offline"));
    render(<LoginScreen app="venta" locale="es" terminalContext={terminalContext}
      onLocaleChange={vi.fn()} onLogin={vi.fn()} onConfigureConnection={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "Entrar" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Entrar" }));
    expect(await screen.findByRole("button", { name: "CONFIGURAR CONEXIÓN" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Entrar" })).toBeDisabled();
  });

  it("never authenticates a terminal with cached display names until its identity is verified", async () => {
    const reconnect = vi.fn().mockResolvedValue(true);
    render(<LoginScreen app="venta" locale="es" terminalContext={terminalContext}
      connectionUnavailable onReconnect={reconnect}
      onLocaleChange={vi.fn()} onLogin={vi.fn()} onConfigureConnection={vi.fn()} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Sin conexión con el servidor local");
    fireEvent.submit(screen.getByLabelText("Contraseña").closest("form")!);
    expect(mocks.authenticateRemote).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Reintentar conexion" }));
    await waitFor(() => expect(reconnect).toHaveBeenCalledOnce());
    expect(screen.getByRole("button", { name: "Entrar" })).toBeDisabled();
    fireEvent.submit(screen.getByLabelText("Contraseña").closest("form")!);
    expect(mocks.authenticateRemote).not.toHaveBeenCalled();
  });

  it.each(["en", "zh"] as const)("localizes the sales password controls and offline notice in %s", async (locale) => {
    mocks.checkBackendConnection.mockResolvedValue(false);
    render(<LoginScreen app="venta" locale={locale} terminalContext={terminalContext}
      onLocaleChange={vi.fn()} onLogin={vi.fn()} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(locale === "en"
      ? "No connection to the local server" : "与本地服务器连接已断开");
    expect(screen.getByRole("button", { name: locale === "en" ? "Show password" : "显示密码" })).toBeVisible();
  });
});
