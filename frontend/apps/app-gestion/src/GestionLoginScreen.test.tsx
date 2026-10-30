// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import GestionLoginScreen from "./GestionLoginScreen";
import { createTranslator } from "../../../packages/app-common/src/i18n/LocalizedMessages";
import type { LocaleCode } from "../../../packages/app-common/src/types";

vi.mock("../../../packages/app-common/src/api/client", async (importOriginal) => ({
  ...await importOriginal<typeof import("../../../packages/app-common/src/api/client")>(),
  checkBackendConnection: vi.fn().mockResolvedValue(true)
}));

afterEach(cleanup);

function renderLogin(locale: LocaleCode = "es", available = true, embedded = false) {
  const onReceive = vi.fn();
  const onLocaleChange = vi.fn();
  render(<GestionLoginScreen app="gestion" locale={locale}
    terminalContext={{ companyName: "Empresa de prueba", storeName: "Tienda de prueba", terminalCode: "001" }}
    onLogin={vi.fn()} onLocaleChange={onLocaleChange}
    presentation={embedded ? "embedded" : "desktop"}
    secondaryActionLabel={available ? createTranslator(locale)("gestion.transfer.maintenanceTitle") : undefined}
    onSecondaryAction={onReceive} />);
  return { onReceive, onLocaleChange };
}

describe("GestionLoginScreen maintenance settings", () => {
  it("hides reception below Entrar and opens it from the settings menu without starting maintenance", async () => {
    const { onReceive } = renderLogin();
    const user = userEvent.setup();
    const settings = screen.getByRole("button", { name: "Ajustes" });
    const language = screen.getByRole("button", { name: "Cambiar idioma" });
    expect(settings.compareDocumentPosition(language) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);
    expect(settings).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText("Recepción de servidor")).not.toBeInTheDocument();
    await user.click(settings);
    expect(settings).toHaveAttribute("aria-expanded", "true");
    const reception = within(screen.getByRole("menu", { name: "Ajustes" })).getByRole("menuitem", { name: "Recepción de servidor" });
    expect(reception).toHaveFocus();
    expect(onReceive).not.toHaveBeenCalled();
    await user.click(reception);
    expect(onReceive).toHaveBeenCalledOnce();
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("opens by keyboard, closes with Escape and returns focus to settings", async () => {
    renderLogin();
    const user = userEvent.setup();
    const settings = screen.getByRole("button", { name: "Ajustes" });
    settings.focus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitem", { name: "Recepción de servidor" })).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(settings).toHaveFocus();
    expect(settings).toHaveAttribute("aria-expanded", "false");
  });

  it("closes on an outside click and keeps the language selector available", async () => {
    const { onReceive, onLocaleChange } = renderLogin();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Ajustes" }));
    await user.click(screen.getByRole("button", { name: "Cambiar idioma" }));
    expect(screen.queryByRole("menu", { name: "Ajustes" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /English\s*EN/ }));
    expect(onLocaleChange).toHaveBeenCalledWith("en");
    expect(onReceive).not.toHaveBeenCalled();
  });

  it("closes on Tab and lets focus continue to the language selector", async () => {
    renderLogin();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Ajustes" }));
    await user.tab();
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cambiar idioma" })).toHaveFocus();
  });

  it("omits settings when the reception action is unavailable", async () => {
    renderLogin("es", false);
    await waitFor(() => expect(screen.getByRole("button", { name: "Entrar" })).toBeEnabled());
    expect(screen.queryByRole("button", { name: "Ajustes" })).not.toBeInTheDocument();
    expect(screen.queryByText("Recepción de servidor")).not.toBeInTheDocument();
  });

  it("preserves the secondary action for embedded logins", async () => {
    const { onReceive } = renderLogin("es", true, true);
    await userEvent.setup().click(screen.getByRole("button", { name: "Recepción de servidor" }));
    expect(onReceive).toHaveBeenCalledOnce();
    expect(screen.queryByRole("button", { name: "Ajustes" })).not.toBeInTheDocument();
  });

  it.each(["en", "zh"] as const)("localizes settings and its action in %s", async (locale) => {
    renderLogin(locale);
    await userEvent.setup().click(screen.getByRole("button", { name: locale === "en" ? "Settings" : "设置" }));
    expect(screen.getByRole("menuitem", { name: createTranslator(locale)("gestion.transfer.maintenanceTitle") })).toBeVisible();
  });
});
