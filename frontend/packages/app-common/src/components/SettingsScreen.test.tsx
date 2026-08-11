// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SettingsScreen } from "./SettingsScreen";
import {
  loadSaleInterfaceConfiguration,
  saveSaleInterfaceConfiguration
} from "./saleInterfacePreferences";
import type { TerminalContext, UserSession } from "../types";
import { persistCashInputModeSelection } from "../sale/cashInputMode";
import { readSalesReportOutputPreferences } from "./salesReportOutputPreferences";
import { ApiError } from "../api/client";
import type { apiRequest } from "../api/client";

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
    expect(html).toContain("Impresión de documentos");
    expect(html).toContain("Configurar impresión");
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

  it("loads and saves the typed mode through the current-terminal API", async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({ terminalId: "terminal-1", saleMode: "KEYBOARD" })
      .mockResolvedValueOnce({ terminalId: "terminal-1", saleMode: "TOUCH" });

    await expect(loadSaleInterfaceConfiguration("token", request)).resolves.toEqual({
      terminalId: "terminal-1",
      saleMode: "KEYBOARD"
    });
    await expect(saveSaleInterfaceConfiguration("TOUCH", "token", request)).resolves.toEqual({
      terminalId: "terminal-1",
      saleMode: "TOUCH"
    });
    expect(request).toHaveBeenNthCalledWith(1, "/terminal-configuration/interface", { token: "token" });
    expect(request).toHaveBeenNthCalledWith(2, "/terminal-configuration/interface", {
      token: "token",
      method: "PATCH",
      body: { saleMode: "TOUCH" }
    });
  });

  it("changes the sales presentation for the current terminal with permission", async () => {
    const requestMock = vi.fn((path: string, options?: { method?: string }) => {
      if (path === "/terminal-configuration/interface") {
        return Promise.resolve({
          terminalId: "terminal-1",
          saleMode: options?.method === "PATCH" ? "TOUCH" : "KEYBOARD"
        });
      }
      return Promise.reject(new Error("not_part_of_test"));
    });
    const request = requestMock as unknown as typeof apiRequest;
    const onSaleInterfaceModeChange = vi.fn();
    render(
      <SettingsScreen
        app="venta"
        locale="es"
        session={{ ...session, accessToken: "token" }}
        terminalContext={{ ...terminalContext, terminalId: "terminal-1" }}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onSaleInterfaceModeChange={onSaleInterfaceModeChange}
        request={request}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Interfaz de venta" }));
    expect(await screen.findByRole("radio", { name: /Ordenador con teclado/ })).toBeTruthy();
    fireEvent.click(screen.getByRole("radio", { name: /Pantalla táctil/ }));
    fireEvent.click(screen.getByRole("button", { name: "Guardar para esta terminal" }));

    await waitFor(() => expect(requestMock).toHaveBeenCalledWith(
      "/terminal-configuration/interface",
      { token: "token", method: "PATCH", body: { saleMode: "TOUCH" } }
    ));
    expect(onSaleInterfaceModeChange).toHaveBeenCalledWith("TOUCH");
  });

  it("shows the safe API reference when saving the sales interface fails", async () => {
    const requestMock = vi.fn((path: string, options?: { method?: string }) => {
      if (path !== "/terminal-configuration/interface") {
        return Promise.reject(new Error("not_part_of_test"));
      }
      if (options?.method === "PATCH") {
        return Promise.reject(new ApiError(
          "No se pudo completar la operación (Ref: interface-save-ref)",
          500,
          undefined,
          "interface-save-ref"
        ));
      }
      return Promise.resolve({ terminalId: "terminal-1", saleMode: "KEYBOARD" });
    });

    render(
      <SettingsScreen
        app="venta"
        locale="es"
        session={{ ...session, accessToken: "token" }}
        terminalContext={{ ...terminalContext, terminalId: "terminal-1" }}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        request={requestMock as unknown as typeof apiRequest}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Interfaz de venta" }));
    fireEvent.click(await screen.findByRole("radio", { name: /Pantalla táctil/ }));
    fireEvent.click(screen.getByRole("button", { name: "Guardar para esta terminal" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "No se pudo guardar la interfaz de venta. No se pudo completar la operación (Ref: interface-save-ref)"
    );
  });

  it("keeps the terminal interface read-only without the configuration permission", async () => {
    const requestMock = vi.fn((path: string) => path === "/terminal-configuration/interface"
      ? Promise.resolve({ terminalId: "terminal-1", saleMode: "TOUCH" })
      : Promise.reject(new Error("not_part_of_test")));
    render(
      <SettingsScreen
        app="venta"
        locale="es"
        session={{
          username: "venta",
          displayName: "VENTA",
          permissions: ["VENTA"],
          accessToken: "token"
        }}
        terminalContext={{ ...terminalContext, terminalId: "terminal-1" }}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        request={requestMock as unknown as typeof apiRequest}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Interfaz de venta" }));
    const touchOption = await screen.findByRole("radio", { name: /Pantalla táctil/ });
    expect((touchOption.closest("fieldset") as HTMLFieldSetElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Guardar para esta terminal" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/Solo un administrador/)).toBeTruthy();
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
