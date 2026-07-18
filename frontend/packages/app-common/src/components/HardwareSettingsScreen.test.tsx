import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { hardwareRouteColumnDefinitions, HardwareSettingsScreen } from "./HardwareSettingsScreen";
import type { TerminalContext, UserSession } from "../types";

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"]
};

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01"
};

describe("HardwareSettingsScreen", () => {
  it("defines a persistent configurable layout for every print route field", () => {
    expect(hardwareRouteColumnDefinitions.map((column) => column.key)).toEqual([
      "document",
      "target",
      "printer",
      "paper",
      "orientation",
      "copies",
      "auto",
      "dialog"
    ]);
  });

  it("renders the shared user controls and context footer", () => {
    const html = renderToStaticMarkup(
      <HardwareSettingsScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
      />
    );

    expect(html).toContain('class="report-user-button"');
    expect(html).toContain('class="language-button"');
    expect(html).toContain('class="shutdown-button"');
    expect(html).toContain('class="report-footer-context"');
    expect(html).toContain("DB:");
    expect(html).toContain("Conexión");
  });
  it("renders the hardware navigation and cash drawer panel", () => {
    const html = renderToStaticMarkup(
      <HardwareSettingsScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
      />
    );

    expect(html).toContain("Impresora de ticket");
    expect(html).toContain("Cajón de dinero");
    expect(html).toContain("Escáner código de barras");
    expect(html).toContain("Diagnóstico");
    expect(html).toContain("Abrir cajón al imprimir ticket");
    expect(html).toContain("Abrir cajón");
  });

  it("uses the shared ERP select instead of native selects", () => {
    const html = renderToStaticMarkup(
      <HardwareSettingsScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
      />
    );

    expect(html).toContain('class="erp-select__trigger"');
    expect(html).not.toContain("<select");
  });
});
