// @vitest-environment jsdom

import { renderToStaticMarkup } from "react-dom/server";
import { cleanup, createEvent, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { hardwareRouteColumnDefinitions, HardwareSettingsScreen } from "./HardwareSettingsScreen";
import { defaultHardwareConfig } from "../hardware/hardware";
import type { HardwareBridge } from "../hardware/hardware";
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

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, "tpvDesktop");
});

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

  it("uses the shared configuration and summary layout in every hardware section", () => {
    render(
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

    const sections = [
      "Impresora de ticket",
      "Cajón de dinero",
      "Escáner código de barras",
      "ESC/POS",
      "Impresora A4 y documentos",
      "Pantalla cliente",
      "Diagnóstico"
    ];

    for (const section of sections) {
      fireEvent.click(screen.getByRole("button", { name: section }));
      expect(screen.getByRole("complementary", { name: "Resumen" })).toBeTruthy();
      expect(screen.getByRole("button", { name: "Guardar configuración" })).toBeTruthy();
      expect(document.querySelector(".hardware-config-main")).toBeTruthy();
    }

    expect(document.querySelector(".hardware-footer")).toBeNull();
  });

  it("verifies a scanner with the fixed automatic timing rule", async () => {
    const testScannerInput = vi.fn(async (code: string) => ({
      ok: true as const,
      code,
      readAt: "2026-07-25T12:00:00Z",
    }));
    const hardware: HardwareBridge = {
      listPrinters: vi.fn(async () => ({ ok: true as const, printers: [] })),
      listCustomerDisplays: vi.fn(async () => ({ ok: true as const, displays: [] })),
      getHardwareConfig: vi.fn(async () => defaultHardwareConfig),
      saveHardwareConfig: vi.fn(async () => ({ ok: true as const })),
      printTicket: vi.fn(async () => ({ ok: true as const })),
      exportTicketPdf: vi.fn(async () => ({ ok: true as const, canceled: false })),
      exportA4DocumentPdf: vi.fn(async () => ({ ok: true as const, canceled: false })),
      printA4Document: vi.fn(async () => ({ ok: true as const })),
      printProductLabel: vi.fn(async () => ({ ok: true as const })),
      exportProductLabelPdf: vi.fn(async () => ({ ok: true as const, canceled: false })),
      openCashDrawer: vi.fn(async () => ({ ok: true as const })),
      testScannerInput,
      openCustomerDisplay: vi.fn(async () => ({ ok: true as const })),
      closeCustomerDisplay: vi.fn(async () => ({ ok: true as const })),
      updateCustomerDisplay: vi.fn(async () => ({ ok: true as const })),
    };
    Object.defineProperty(window, "tpvDesktop", {
      configurable: true,
      value: { hardware },
    });
    render(<HardwareSettingsScreen
      app="venta"
      locale="es"
      session={session}
      terminalContext={terminalContext}
      onBack={vi.fn()}
      onLocaleChange={vi.fn()}
      onLogout={vi.fn()}
    />);

    fireEvent.click(screen.getByRole("button", { name: "Escáner código de barras" }));
    expect(screen.queryByRole("spinbutton")).toBeNull();
    const input = screen.getByPlaceholderText("Escanea o escribe código y pulsa Enter");
    fireEvent.change(input, { target: { value: "12345" } });
    const ordinaryEnter = createEvent.keyDown(input, { key: "Enter" });
    Object.defineProperty(ordinaryEnter, "timeStamp", { value: 1000 });
    fireEvent(input, ordinaryEnter);

    expect(testScannerInput).not.toHaveBeenCalled();
    expect(screen.getAllByText("La lectura no cumple los tiempos configurados del escáner")).toHaveLength(2);

    let scanned = "";
    for (const [index, key] of Array.from("841234").entries()) {
      const event = createEvent.keyDown(input, { key });
      Object.defineProperty(event, "timeStamp", { value: 2000 + index * 20 });
      fireEvent(input, event);
      scanned += key;
      fireEvent.change(input, { target: { value: scanned } });
    }
    const scannerEnter = createEvent.keyDown(input, { key: "Enter" });
    Object.defineProperty(scannerEnter, "timeStamp", { value: 2120 });
    fireEvent(input, scannerEnter);

    await waitFor(() => expect(testScannerInput).toHaveBeenCalledWith("841234"));
    expect(await screen.findAllByText("Lector verificado por velocidad de escritura")).toHaveLength(2);
  });
});
