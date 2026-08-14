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

  it("renders the grouped APP VENTA shell and starts in sales settings for an authorized user", () => {
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
        onOpenDocumentPrinting={vi.fn()}
      />
    );

    expect(html).toContain('class="settings-screen sale-settings-screen"');
    expect(html).toContain('class="settings-shell sale-settings-shell"');
    expect(html).toContain("Mis preferencias");
    expect(html).toContain("Este puesto");
    expect(html).toContain("Soporte");
    expect(html).toContain("Mi cuenta");
    expect(html).not.toContain("Idioma y región");
    expect(html).toContain("Seguridad");
    expect(html).toContain("Informes");
    expect(html).toContain('aria-current="page"');
    expect(html).toContain("Venta y cobro");
    expect(html).toContain("Dispositivos");
    expect(html).toContain("Impresión y etiquetas");
    expect(html).toContain("Diagnóstico");
    expect(html).toContain("Entrada de cobro");
    expect(html).toContain("Datáfono");
    expect(html).toContain("Caja y turno");
    expect(html.indexOf("Caja y turno")).toBeLessThan(html.indexOf("Interfaz de venta"));
  });

  it("routes workstation destinations through the existing callbacks", () => {
    const onOpenHardware = vi.fn();
    const onOpenDocumentPrinting = vi.fn();
    const onOpenDiagnostics = vi.fn();
    render(
      <SettingsScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onOpenHardware={onOpenHardware}
        onOpenDocumentPrinting={onOpenDocumentPrinting}
        onOpenDiagnostics={onOpenDiagnostics}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Dispositivos" }));
    fireEvent.click(screen.getByRole("button", { name: "Impresión y etiquetas" }));
    fireEvent.click(screen.getByRole("button", { name: "Diagnóstico" }));

    expect(onOpenHardware).toHaveBeenCalledOnce();
    expect(onOpenDocumentPrinting).toHaveBeenCalledOnce();
    expect(onOpenDiagnostics).toHaveBeenCalledOnce();
  });

  it("opens the personal destination requested by another settings screen", () => {
    render(
      <SettingsScreen
        app="venta"
        locale="es"
        session={{ username: "venta", displayName: "VENTA", permissions: ["VENTA"] }}
        terminalContext={terminalContext}
        initialDestination="security"
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    expect(screen.getByRole("heading", { name: "Seguridad", level: 2 })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Seguridad" })).toHaveAttribute("aria-current", "page");
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

  it("localizes the protected sales settings", () => {
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

    expect(html).toContain("Sales and payments");
    expect(html).toContain("Cash input");
    expect(html).toContain("Choose how amounts are entered when taking cash payments.");
    expect(html).toContain("Touch");
    expect(html).toContain("Standard keyboard");
  });

  it("does not expose APP VENTA workstation settings in APP GESTION", () => {
    const html = renderToStaticMarkup(
      <SettingsScreen
        app="gestion"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    expect(html).toContain("Mi cuenta");
    expect(html).not.toContain("Venta y cobro");
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

    fireEvent.click(await screen.findByRole("radio", { name: /Pantalla táctil/ }));
    fireEvent.click(screen.getByRole("button", { name: "Guardar para esta terminal" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "No se pudo guardar la interfaz de venta. No se pudo completar la operación (Ref: interface-save-ref)"
    );
  });

  it("shows only personal settings and performs no protected request without permission", async () => {
    const requestMock = vi.fn();
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

    expect(screen.getByRole("heading", { name: "Mi cuenta" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Mi cuenta" })).toHaveAttribute("aria-current", "page");
    expect(screen.queryByRole("button", { name: "Venta y cobro" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Dispositivos" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Impresión y etiquetas" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Diagnóstico" })).toBeNull();
    expect(screen.queryByText("Datáfono")).toBeNull();
    await waitFor(() => expect(requestMock).not.toHaveBeenCalled());
  });

  it("unifies language with account and keeps security behavior", async () => {
    const request = vi.fn((path: string, options?: { method?: string }) => {
      if (path === "/terminal-configuration/interface") {
        return Promise.resolve({ terminalId: "terminal-1", saleMode: "KEYBOARD" });
      }
      if (path === "/auth/password" && options?.method === "PUT") return Promise.resolve(undefined);
      return Promise.reject(new Error("not_part_of_test"));
    });
    const onLocaleChange = vi.fn();
    render(
      <SettingsScreen
        app="venta"
        locale="es"
        session={{ ...session, accessToken: "token", role: "ADMIN", maxDiscountPercent: 20 }}
        terminalContext={{ ...terminalContext, terminalId: "terminal-1" }}
        onBack={vi.fn()}
        onLocaleChange={onLocaleChange}
        request={request as unknown as typeof apiRequest}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Mi cuenta" }));
    expect(screen.getByText("Perfil activo")).toBeTruthy();
    expect(screen.getByText("20%")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Idioma y región" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "English" }));
    expect(onLocaleChange).toHaveBeenCalledWith("en");

    fireEvent.click(screen.getByRole("button", { name: "Seguridad" }));
    fireEvent.change(screen.getByLabelText("Contraseña actual"), { target: { value: "0000" } });
    fireEvent.change(screen.getByLabelText("Nueva contraseña"), { target: { value: "1234" } });
    fireEvent.change(screen.getByLabelText("Confirmar nueva contraseña"), { target: { value: "1234" } });
    const passwordAction = screen.getByRole("button", { name: "Cambiar contraseña" });
    expect(passwordAction).toHaveClass("sale-settings-action-button");
    fireEvent.click(passwordAction);

    await waitFor(() => expect(request).toHaveBeenCalledWith("/auth/password", {
      token: "token",
      method: "PUT",
      body: { currentPassword: "0000", newPassword: "1234" }
    }));
    expect(await screen.findByText("Contraseña cambiada correctamente.")).toBeTruthy();
  });

  it("configures report display and output from its own personal section", () => {
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

    const openReports = screen.getByRole("button", { name: "Abrir informes y configurar columnas" });
    expect(openReports).toHaveClass("sale-settings-action-button");
    fireEvent.click(openReports);
    expect(onOpenReports).toHaveBeenCalledOnce();
  });
});
