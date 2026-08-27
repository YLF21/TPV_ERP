// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";

import { renderToStaticMarkup } from "react-dom/server";
import { cleanup, createEvent, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { defaultHardwareConfig } from "../hardware/hardware";
import type { HardwareBridge } from "../hardware/hardware";
import type { TerminalContext, UserSession } from "../types";
import { hardwareRouteColumnDefinitions, HardwareSettingsScreen } from "./HardwareSettingsScreen";

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"],
};

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01",
};

function createHardwareBridge(overrides: Partial<HardwareBridge> = {}): HardwareBridge {
  return {
    listPrinters: vi.fn(async () => ({ ok: true as const, printers: [] })),
    getTicketPrinterHealth: vi.fn(async () => ({
      status: "READY" as const,
      printerName: "RP-12N",
      checkedAt: "2026-08-22T20:00:00Z",
    })),
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
    testScannerInput: vi.fn(async (code: string) => ({ ok: true as const, code, readAt: "2026-07-25T12:00:00Z" })),
    openCustomerDisplay: vi.fn(async () => ({ ok: true as const })),
    closeCustomerDisplay: vi.fn(async () => ({ ok: true as const })),
    updateCustomerDisplay: vi.fn(async () => ({ ok: true as const })),
    ...overrides,
  };
}

function installHardware(hardware: HardwareBridge) {
  Object.defineProperty(window, "tpvDesktop", {
    configurable: true,
    value: { hardware },
  });
}

function renderHardware(
  props: Partial<React.ComponentProps<typeof HardwareSettingsScreen>> = {},
) {
  return render(<HardwareSettingsScreen
    app="venta"
    locale="es"
    session={session}
    terminalContext={terminalContext}
    onBack={vi.fn()}
    onLocaleChange={vi.fn()}
    onLogout={vi.fn()}
    {...props}
  />);
}

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, "tpvDesktop");
});

describe("HardwareSettingsScreen", () => {
  it("defines a persistent configurable layout for every print route field", () => {
    expect(hardwareRouteColumnDefinitions.map((column) => column.key)).toEqual([
      "document", "target", "printer", "paper", "orientation", "copies", "auto", "dialog",
    ]);
  });

  it("uses the shared settings shell with user controls and context footer", () => {
    const html = renderToStaticMarkup(<HardwareSettingsScreen
      app="venta"
      locale="es"
      session={session}
      terminalContext={terminalContext}
      onBack={vi.fn()}
      onLocaleChange={vi.fn()}
      onLogout={vi.fn()}
    />);

    expect(html).toContain("sale-settings-shell");
    expect(html).toContain('class="report-user-button"');
    expect(html).toContain('class="language-button"');
    expect(html).toContain('class="shutdown-button"');
    expect(html).toContain('class="report-footer-context"');
  });

  it("groups devices into the three selected UI tabs without losing drawer controls", () => {
    installHardware(createHardwareBridge());
    renderHardware();

    expect(document.querySelectorAll(".hardware-device-tabs button")).toHaveLength(3);
    expect(screen.getByText("Impresora de ticket")).toBeTruthy();
    expect(screen.getByText("Cajón de dinero")).toBeTruthy();
    expect(screen.getByText("Métodos que abren cajón")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Lector y conexión" }));
    expect(screen.getByText("Escáner código de barras")).toBeTruthy();
    expect(screen.getByText("ESC/POS")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Visor de cliente" }));
    expect(screen.getByText("Pantalla cliente")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Prueba venta" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Prueba cobro" })).toBeTruthy();
  });

  it("configures only additional ESC/POS feed while preserving the current minimum", async () => {
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({ saveHardwareConfig }));
    renderHardware();

    fireEvent.click(screen.getByRole("button", { name: "Lector y conexión" }));
    const input = screen.getByRole("spinbutton", { name: "Espacio final adicional (líneas)" });
    expect(input).toHaveAttribute("min", "0");
    expect(input).toHaveAttribute("max", "12");
    expect(input).toHaveValue(0);

    fireEvent.change(input, { target: { value: "5" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));

    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledWith(
      expect.objectContaining({ escposAdditionalFeedLines: 5 }),
    ));
  });

  it("keeps documentRoutingOnly compatible and renders all four real routes", async () => {
    installHardware(createHardwareBridge());
    renderHardware({ documentRoutingOnly: true });

    expect(screen.getByText("Factura")).toBeTruthy();
    expect(screen.getByText("Albaran")).toBeTruthy();
    expect(screen.getByText("Ticket")).toBeTruthy();
    expect(screen.getByText("Informe")).toBeTruthy();
    expect(screen.queryByText("Cajón de dinero")).toBeNull();
  });

  it("shows the real product-label action only when its callback is wired", () => {
    installHardware(createHardwareBridge());
    const onOpenProductLabels = vi.fn();
    const { rerender } = renderHardware({ mode: "printing" });

    expect(screen.queryByRole("button", { name: "Abrir impresión de etiquetas" })).toBeNull();

    rerender(<HardwareSettingsScreen
      app="venta"
      locale="es"
      session={session}
      terminalContext={terminalContext}
      onBack={vi.fn()}
      onLocaleChange={vi.fn()}
      mode="printing"
      onOpenProductLabels={onOpenProductLabels}
    />);
    fireEvent.click(screen.getByRole("button", { name: "Abrir impresión de etiquetas" }));
    expect(onOpenProductLabels).toHaveBeenCalledOnce();
  });

  it("does not load or execute the hardware bridge without CONFIGURACION_TERMINAL or ADMIN", async () => {
    const hardware = createHardwareBridge();
    installHardware(hardware);
    renderHardware({
      session: { username: "venta", displayName: "VENTA", permissions: ["VENTA"] },
    });

    expect(screen.getByRole("alert")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Detectar impresoras" })).toBeNull();
    await Promise.resolve();
    expect(hardware.getHardwareConfig).not.toHaveBeenCalled();
    expect(hardware.listPrinters).not.toHaveBeenCalled();
    expect(hardware.listCustomerDisplays).not.toHaveBeenCalled();
  });

  it("detects Windows printers only on request and never replaces the saved printer", async () => {
    const listPrinters = vi.fn(async () => ({
      ok: true as const,
      printers: [
        { name: "WPS Print to PDF", displayName: "WPS Print to PDF", isDefault: true },
        { name: "EPSON TM-T20", displayName: "EPSON TM-T20", isDefault: false },
      ],
    }));
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    const getHardwareConfig = vi.fn(async () => ({
      ...defaultHardwareConfig,
      ticketPrinterName: "EPSON TM-T20",
    }));
    installHardware(createHardwareBridge({
      listPrinters,
      getHardwareConfig,
      saveHardwareConfig,
    }));
    renderHardware();

    await waitFor(() => expect(getHardwareConfig).toHaveBeenCalledOnce());
    expect(listPrinters).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Detectar impresoras" }));
    await waitFor(() => expect(listPrinters).toHaveBeenCalledOnce());
    const printerSelect = screen.getByRole("button", { name: "Impresora Windows" });
    await waitFor(() => expect(printerSelect.textContent).toContain("EPSON TM-T20"));

    fireEvent.click(printerSelect);
    fireEvent.click(screen.getByRole("option", { name: /WPS Print to PDF/ }));
    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));
    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledWith(
      expect.objectContaining({ ticketPrinterName: "WPS Print to PDF" }),
    ));
  });

  it("prints a real test ticket through the desktop hardware bridge", async () => {
    const printTicket = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({ printTicket }));
    renderHardware();

    fireEvent.click(screen.getByRole("button", { name: "Imprimir prueba" }));
    await waitFor(() => expect(printTicket).toHaveBeenCalledOnce());
    expect(screen.getByText("Ticket de prueba enviado")).toBeTruthy();
  });

  it("does not present desktop-only printer actions as functional in a browser", () => {
    renderHardware();

    expect(screen.getByRole("button", { name: "Detectar impresoras" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Imprimir prueba" })).toBeDisabled();
    expect(screen.getByText(/disponibles en APP VENTA de escritorio/)).toBeTruthy();
  });

  it("preserves the configured customer display when screen detection finishes later", async () => {
    let finishDisplayDetection: ((value: Awaited<ReturnType<HardwareBridge["listCustomerDisplays"]>>) => void) | undefined;
    const listCustomerDisplays = vi.fn(() => new Promise<Awaited<ReturnType<HardwareBridge["listCustomerDisplays"]>>>((resolve) => {
      finishDisplayDetection = resolve;
    }));
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({
      getHardwareConfig: vi.fn(async () => ({ ...defaultHardwareConfig, customerDisplayScreenId: "saved-display" })),
      listCustomerDisplays,
      saveHardwareConfig,
    }));
    renderHardware();
    await waitFor(() => expect(listCustomerDisplays).toHaveBeenCalledOnce());
    finishDisplayDetection?.({
      ok: true,
      displays: [
        { id: "primary", label: "Principal", width: 1920, height: 1080, primary: true },
        { id: "secondary", label: "Secundaria", width: 1920, height: 1080, primary: false },
      ],
    });

    fireEvent.click(screen.getByRole("button", { name: "Visor de cliente" }));
    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));
    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledWith(
      expect.objectContaining({ customerDisplayScreenId: "saved-display" }),
    ));
  });

  it("verifies a scanner with the fixed automatic timing rule", async () => {
    const testScannerInput = vi.fn(async (code: string) => ({
      ok: true as const,
      code,
      readAt: "2026-07-25T12:00:00Z",
    }));
    installHardware(createHardwareBridge({ testScannerInput }));
    renderHardware();
    fireEvent.click(screen.getByRole("button", { name: "Lector y conexión" }));

    const input = screen.getByPlaceholderText("Escanea o escribe código y pulsa Enter");
    fireEvent.change(input, { target: { value: "12345" } });
    const ordinaryEnter = createEvent.keyDown(input, { key: "Enter" });
    Object.defineProperty(ordinaryEnter, "timeStamp", { value: 1000 });
    fireEvent(input, ordinaryEnter);
    expect(testScannerInput).not.toHaveBeenCalled();

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
    expect(screen.getByText("Lector verificado por velocidad de escritura")).toBeTruthy();
  });

  it("runs real diagnostics without saving configuration and keeps going after failures", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    const getHardwareConfig = vi.fn(async () => defaultHardwareConfig);
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    const printTicket = vi.fn(async () => ({ ok: false as const, code: "PRINT_FAILED" as const, message: "ticket failed" }));
    const printA4Document = vi.fn(async () => ({ ok: true as const }));
    const openCashDrawer = vi.fn(async () => ({ ok: true as const }));
    const openCustomerDisplay = vi.fn(async () => ({ ok: true as const }));
    const hardware = createHardwareBridge({
      getHardwareConfig,
      saveHardwareConfig,
      printTicket,
      printA4Document,
      openCashDrawer,
      openCustomerDisplay,
    });
    installHardware(hardware);
    renderHardware({ mode: "diagnostics" });
    await waitFor(() => expect(getHardwareConfig).toHaveBeenCalledOnce());

    fireEvent.click(screen.getByRole("button", { name: "Probar todo" }));
    await waitFor(() => expect(openCustomerDisplay).toHaveBeenCalledOnce());

    expect(confirm).toHaveBeenCalledWith(expect.stringContaining("imprimir documentos"));
    expect(getHardwareConfig).toHaveBeenCalledTimes(2);
    expect(printTicket).toHaveBeenCalledOnce();
    expect(printA4Document).toHaveBeenCalledOnce();
    expect(openCashDrawer).toHaveBeenCalledOnce();
    expect(saveHardwareConfig).not.toHaveBeenCalled();
    expect(screen.getByText("ticket failed")).toBeTruthy();
  });
});
