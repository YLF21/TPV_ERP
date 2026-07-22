// @vitest-environment jsdom
import { renderToStaticMarkup } from "react-dom/server";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SettingsScreen } from "./SettingsScreen";
import {
  readSaleInterfaceTouchMode,
  saleInterfaceTouchModeStorageKey,
  saveSaleInterfaceTouchMode
} from "./saleInterfacePreferences";
import type { TerminalContext, UserSession } from "../types";
import { persistCashInputModeSelection } from "../sale/cashInputMode";
import { readSalesReportOutputPreferences } from "./salesReportOutputPreferences";

function storageWith(value: string | null): Storage {
  return {
    getItem: vi.fn(() => value),
    setItem: vi.fn()
  } as unknown as Storage;
}

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"]
};

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01"
};

describe("SettingsScreen", () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("renders a settings hub with formal controls and hardware entry", () => {
    const html = renderToStaticMarkup(
      <SettingsScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
        onOpenHardware={vi.fn()}
      />
    );

    expect(html).toContain('class="settings-screen"');
    expect(html).toContain('class="report-user-button"');
    expect(html).toContain('class="language-button"');
    expect(html).toContain('class="shutdown-button"');
    expect(html).toContain('class="report-footer-context"');
    expect(html).toContain("DB:");
    expect(html).toContain("Conexión");
    expect(html).toContain("AJUSTES");
    expect(html).toContain("Terminal");
    expect(html).toContain("Hardware");
    expect(html).toContain("Entrada de cobro");
    expect(html).toContain('<label for="cash-input-mode">Entrada de cobro</label>');
    expect(html).toContain('<select id="cash-input-mode"');
    expect(html).toContain('value="touch" selected=""');
    expect(html).toContain("Táctil");
    expect(html).toContain("Teclado normal");
    expect(html).toContain("Datáfono");
    expect(html).toContain("Cargando configuración del datáfono");
    expect(html).toContain("Interfaz de venta");
  });

  it("initializes the cash input selector from the stored keyboard preference", () => {
    vi.stubGlobal("localStorage", storageWith("keyboard"));

    const html = renderToStaticMarkup(
      <SettingsScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    expect(html).toContain('value="keyboard" selected=""');
  });

  it("persists a valid cash input selection", () => {
    const storage = storageWith("touch");

    expect(persistCashInputModeSelection("keyboard", storage)).toBe("keyboard");
    expect(storage.setItem).toHaveBeenCalledWith("tpverp.cashInputMode.v1", "keyboard");
  });

  it("localizes the cash input setting", () => {
    const html = renderToStaticMarkup(
      <SettingsScreen
        app="venta"
        locale="en"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    expect(html).toContain("Cash input");
    expect(html).toContain("Choose how amounts are entered when taking cash payments.");
    expect(html).toContain("Touch");
    expect(html).toContain("Standard keyboard");
  });

  it("keeps the sale interface section scoped to APP VENTA", () => {
    const html = renderToStaticMarkup(
      <SettingsScreen
        app="gestion"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
        onOpenHardware={vi.fn()}
      />
    );

    expect(html).not.toContain("Interfaz de venta");
  });

  it("persists touch mode by app and terminal", () => {
    const values = new Map<string, string>();
    vi.stubGlobal("window", {
      localStorage: {
        getItem: (key: string) => values.get(key) ?? null,
        removeItem: (key: string) => {
          values.delete(key);
        },
        setItem: (key: string, value: string) => {
          values.set(key, value);
        }
      }
    });

    const ventaKey = saleInterfaceTouchModeStorageKey("venta", terminalContext);
    const gestionKey = saleInterfaceTouchModeStorageKey("gestion", terminalContext);

    saveSaleInterfaceTouchMode("venta", terminalContext, true);
    expect(values.get(ventaKey)).toBe("enabled");
    expect(values.has(gestionKey)).toBe(false);
    expect(readSaleInterfaceTouchMode("venta", terminalContext)).toBe(true);
    expect(readSaleInterfaceTouchMode("gestion", terminalContext)).toBe(false);

    saveSaleInterfaceTouchMode("venta", terminalContext, false);
    expect(values.has(ventaKey)).toBe(false);
  });

  it("shows the active user settings and changes the authenticated password", async () => {
    const request = vi.fn().mockResolvedValue(undefined);
    const onLocaleChange = vi.fn();
    render(
      <SettingsScreen
        app="venta"
        locale="es"
        session={{ ...session, accessToken: "token", role: "ADMIN", maxDiscountPercent: 20 }}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={onLocaleChange}
        request={request}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Usuario" }));
    expect(screen.getByText("Perfil activo")).toBeTruthy();
    expect(screen.getByText("20%")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "English" }));
    expect(onLocaleChange).toHaveBeenCalledWith("en");

    fireEvent.change(screen.getByLabelText("Contraseña actual"), { target: { value: "0000" } });
    fireEvent.change(screen.getByLabelText("Nueva contraseña"), { target: { value: "1234" } });
    fireEvent.change(screen.getByLabelText("Confirmar nueva contraseña"), { target: { value: "1234" } });
    fireEvent.click(screen.getByRole("button", { name: "Cambiar contraseña" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith("/auth/password", {
      token: "token",
      method: "PUT",
      body: { currentPassword: "0000", newPassword: "1234" }
    }));
    expect(await screen.findByText("Contraseña cambiada correctamente.")).toBeTruthy();
  });

  it("configures report display and output instead of showing an empty placeholder", () => {
    const onOpenReports = vi.fn();
    render(
      <SettingsScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onOpenReports={onOpenReports}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Informes" }));
    expect(screen.getByText("Visualización de informes")).toBeTruthy();
    expect(screen.getByText("Impresión y exportación")).toBeTruthy();

    fireEvent.change(screen.getByLabelText("Densidad de filas"), { target: { value: "compact" } });
    fireEvent.change(screen.getByLabelText("Acción principal"), { target: { value: "pdf" } });
    expect(readSalesReportOutputPreferences("venta", "admin", terminalContext)).toEqual({
      density: "compact",
      primaryAction: "pdf"
    });

    fireEvent.click(screen.getByRole("button", { name: "Abrir informes y configurar columnas" }));
    expect(onOpenReports).toHaveBeenCalledOnce();
  });
});
