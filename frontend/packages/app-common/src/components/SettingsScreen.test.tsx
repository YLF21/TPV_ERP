import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SettingsScreen } from "./SettingsScreen";
import type { TerminalContext, UserSession } from "../types";
import { persistCashInputModeSelection } from "../sale/cashInputMode";

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
  afterEach(() => vi.unstubAllGlobals());

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
});
