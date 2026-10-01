// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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
    window.localStorage.clear();
  });

  it("renders the grouped APP VENTA shell and starts in visualization for an authorized user", () => {
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
    expect(html).toContain('class="module-nav-back-icon"');
    expect(html.match(/class="module-nav-item-icon"/g)).toHaveLength(6);
    expect(html.match(/class="module-nav-item-label"/g)).toHaveLength(6);
    expect(html).toContain('class="top-date-time"');
    expect(html).toContain('class="report-user-button"');
    expect(html).toContain('class="language-button"');
    expect(html).toContain('class="shutdown-button"');
    expect(html).toContain("Mis preferencias");
    expect(html).toContain("Este puesto");
    expect(html).toContain("Soporte");
    expect(html).toContain("Mi cuenta y seguridad");
    expect(html).not.toContain("Idioma y región");
    expect(html).toContain("Visualización");
    expect(html).toContain('aria-current="page"');
    expect(html).toContain("Interfaz de venta");
    expect(html).toContain("Dispositivos");
    expect(html).toContain("Impresoras");
    expect(html).toContain("Diagnóstico y mantenimiento");
    expect(html).toContain("Entrada de cobro");
    expect(html).not.toContain("Datáfono");
    expect(html).not.toContain("Caja y turno");
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
    fireEvent.click(screen.getByRole("button", { name: "Impresoras" }));
    fireEvent.click(screen.getByRole("button", { name: "Diagnóstico y mantenimiento" }));

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

    expect(screen.getByRole("heading", { name: "Mi cuenta y seguridad", level: 2 })).toBeTruthy();
    expect(screen.getByLabelText("Contraseña actual")).toHaveFocus();
    expect(screen.getByRole("button", { name: "Mi cuenta y seguridad" })).toHaveAttribute("aria-current", "page");
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

    expect(html).toContain("Teclado normal");
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

    expect(html).toContain("Display");
    expect(html).toContain("Cash input");
    expect(html).toContain("Choose how amounts are entered when taking cash payments.");
    expect(html).toContain("Touch");
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

    expect(html).toContain("Mi cuenta y seguridad");
    expect(html).not.toContain("Caja y turno");
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

    expect(screen.getByRole("heading", { name: "Mi cuenta y seguridad" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Mi cuenta y seguridad" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("button", { name: "Visualización" })).toBeTruthy();
    expect(screen.queryByText("Interfaz de venta")).toBeNull();
    expect(screen.queryByRole("button", { name: "Dispositivos" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Impresoras" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Caja" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Diagnóstico y mantenimiento" })).toBeNull();
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

    fireEvent.click(screen.getByRole("button", { name: "Mi cuenta y seguridad" }));
    expect(screen.getByText("Perfil activo")).toBeTruthy();
    expect(screen.getByText("20%")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Idioma y región" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "English" }));
    expect(onLocaleChange).toHaveBeenCalledWith("en");

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

  it("configures report density and opens reports from visualization", () => {
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

    fireEvent.click(screen.getByRole("button", { name: "Visualización" }));
    expect(screen.getByText("Visualización de informes")).toBeTruthy();
    expect(screen.queryByText("Impresión y exportación")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Densidad de filas" }));
    fireEvent.click(screen.getByRole("option", { name: "Compacta" }));
    expect(readSalesReportOutputPreferences("venta", "admin", terminalContext)).toEqual({
      density: "compact",
      primaryAction: "menu"
    });

    const openReports = screen.getByRole("button", { name: "Abrir informes y configurar columnas" });
    expect(openReports).toHaveClass("sale-settings-action-button");
    fireEvent.click(openReports);
    expect(onOpenReports).toHaveBeenCalledOnce();
  });

  it("keeps Caja separate and reuses the operational cash card", async () => {
    render(
      <SettingsScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    expect(screen.queryByText("Caja y turno")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Caja" }));
    expect(screen.getByRole("button", { name: "Caja" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByText("Caja y turno")).toBeTruthy();
    expect(screen.queryByText("Interfaz de venta")).toBeNull();
  });

  it("maps the old reports and sale destinations to visualization", () => {
    const { rerender } = render(
      <SettingsScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        initialDestination="reports"
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    expect(screen.getByRole("button", { name: "Visualización" })).toHaveAttribute("aria-current", "page");
    rerender(
      <SettingsScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        initialDestination="sale"
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );
    expect(screen.getByText("Interfaz de venta")).toBeTruthy();
  });

  it("lets users without terminal permission change report density but hides terminal controls", () => {
    render(
      <SettingsScreen
        app="venta"
        locale="es"
        session={{ username: "venta", displayName: "VENTA", permissions: ["VENTA"] }}
        terminalContext={terminalContext}
        initialDestination="visualization"
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    expect(screen.getByRole("button", { name: "Densidad de filas" })).toBeTruthy();
    expect(screen.queryByText("Interfaz de venta")).toBeNull();
    expect(screen.queryByText("Entrada de cobro")).toBeNull();
  });

  it("keeps APP GESTION's report output choice available", () => {
    render(
      <SettingsScreen
        app="gestion"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        initialDestination="reports"
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "Acción principal" }));
    fireEvent.click(screen.getByRole("option", { name: "Exportar directamente a PDF" }));
    expect(readSalesReportOutputPreferences("gestion", "admin", terminalContext).primaryAction).toBe("pdf");
  });

  it("saves an unsaved terminal mode before navigating and blocks another action while saving", async () => {
    let completeSave!: (value: { terminalId: string; saleMode: string }) => void;
    const pendingSave = new Promise<{ terminalId: string; saleMode: string }>((resolve) => {
      completeSave = resolve;
    });
    const request = vi.fn((_path: string, options?: { method?: string }) =>
      options?.method === "PATCH" ? pendingSave
        : Promise.resolve({ terminalId: "terminal-1", saleMode: "KEYBOARD" }));
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
        request={request as unknown as typeof apiRequest}
      />
    );

    fireEvent.click(await screen.findByRole("radio", { name: /Pantalla táctil/ }));
    fireEvent.click(screen.getByRole("button", { name: "Caja" }));
    const dialog = screen.getByRole("alertdialog", { name: "¿Desea guardar cambios?" });
    expect(screen.getByRole("button", { name: "Visualización" })).toHaveAttribute("aria-current", "page");
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    expect(within(dialog).getByRole("button", { name: "Cancelar" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Visualización" })).toHaveAttribute("aria-current", "page");
    await act(async () => completeSave({ terminalId: "terminal-1", saleMode: "TOUCH" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Caja" })).toHaveAttribute("aria-current", "page"));
    expect(onSaleInterfaceModeChange).toHaveBeenCalledWith("TOUCH");
    expect(request).toHaveBeenCalledWith("/terminal-configuration/interface", {
      token: "token", method: "PATCH", body: { saleMode: "TOUCH" }
    });
  });

  it("discards the draft on Cancelar and continues the requested back navigation without PATCH", async () => {
    const request = vi.fn(() => Promise.resolve({ terminalId: "terminal-1", saleMode: "KEYBOARD" }));
    const onBack = vi.fn();
    render(
      <SettingsScreen
        app="venta"
        locale="es"
        session={{ ...session, accessToken: "token" }}
        terminalContext={{ ...terminalContext, terminalId: "terminal-1" }}
        onBack={onBack}
        onLocaleChange={vi.fn()}
        request={request as unknown as typeof apiRequest}
      />
    );

    fireEvent.click(await screen.findByRole("radio", { name: /Pantalla táctil/ }));
    const back = new Event("tpv-sale-settings-back", { cancelable: true });
    act(() => { window.dispatchEvent(back); });
    expect(back.defaultPrevented).toBe(true);
    expect(onBack).not.toHaveBeenCalled();
    fireEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Cancelar" }));
    expect(onBack).toHaveBeenCalledOnce();
    expect(screen.getByRole("radio", { name: /Ordenador con teclado/ })).toBeChecked();
    expect(request).not.toHaveBeenCalledWith("/terminal-configuration/interface", expect.objectContaining({ method: "PATCH" }));
  });

  it("keeps the unsaved terminal mode and dialog open after a save failure", async () => {
    const request = vi.fn((_path: string, options?: { method?: string }) =>
      options?.method === "PATCH"
        ? Promise.reject(new ApiError("No se pudo completar la operación (Ref: mode-save-ref)", 500))
        : Promise.resolve({ terminalId: "terminal-1", saleMode: "KEYBOARD" }));
    render(
      <SettingsScreen
        app="venta"
        locale="es"
        session={{ ...session, accessToken: "token" }}
        terminalContext={{ ...terminalContext, terminalId: "terminal-1" }}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        request={request as unknown as typeof apiRequest}
      />
    );

    fireEvent.click(await screen.findByRole("radio", { name: /Pantalla táctil/ }));
    fireEvent.click(screen.getByRole("button", { name: "Caja" }));
    const dialog = screen.getByRole("alertdialog");
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("No se pudo guardar la interfaz de venta.");
    expect(screen.getByRole("button", { name: "Visualización" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("radio", { name: /Pantalla táctil/ })).toBeChecked();
  });
});
