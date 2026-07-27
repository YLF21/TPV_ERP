// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import { authenticateRemote } from "../auth/auth";
import { LoginScreen } from "./LoginScreen";
import type { TerminalContext } from "../types";

vi.mock("../auth/auth", () => ({
  authenticateRemote: vi.fn(),
}));

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01"
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

describe("LoginScreen", () => {
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

  it("shows the invalid-credentials warning, clears the password and returns focus to it", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 200 })));
    vi.mocked(authenticateRemote).mockRejectedValueOnce(new ApiError("invalid_credentials", 401));

    render(
      <LoginScreen
        app="venta"
        locale="es"
        terminalContext={terminalContext}
        onLocaleChange={vi.fn()}
        onLogin={vi.fn()}
      />,
    );

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
});
