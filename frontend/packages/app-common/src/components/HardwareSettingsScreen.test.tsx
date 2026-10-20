// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";

import { renderToStaticMarkup } from "react-dom/server";
import { act, cleanup, createEvent, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { defaultHardwareConfig } from "../hardware/hardware";
import type { HardwareBridge } from "../hardware/hardware";
import type { TerminalContext, UserSession } from "../types";
import { hardwareRouteColumnDefinitions, HardwareSettingsScreen } from "./HardwareSettingsScreen";

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  accessToken: "hardware-test-token",
  permissions: ["ADMIN"],
};

const printerTestArtifacts = {
  renderedPdf: { contentType: "application/pdf", base64: "JVBERi0xLjQ=" },
  ticketRenderedImage: { contentType: "image/png", base64: "iVBORw0KGgoAAA==" },
};

function mockPrinterTestResponse(artifacts: unknown = printerTestArtifacts) {
  const fetch = vi.fn(async (_url: string) => ({ ok: true, status: 200, headers: { get: () => null }, json: async () => artifacts }));
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01",
};

function createHardwareBridge(overrides: Partial<HardwareBridge> = {}): HardwareBridge {
  return {
    listPrinters: vi.fn(async () => ({ ok: true as const, printers: [] })),
    listSerialPorts: vi.fn(async () => ({ ok: true as const, ports: [{ path: "COM4" }] })),
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

function escposConfig(overrides: Partial<typeof defaultHardwareConfig> = {}) {
  return {
    ...defaultHardwareConfig,
    ticketPrinterDriver: "ESCPOS_RAW" as const,
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
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
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

  it("separates the four device tabs from printer configuration", () => {
    installHardware(createHardwareBridge());
    renderHardware();

    expect(document.querySelectorAll(".hardware-device-tabs button")).toHaveLength(4);
    expect(screen.queryByText("Impresora de ticket")).toBeNull();
    expect(screen.getByText("Cajón de dinero")).toBeTruthy();
    expect(screen.getByText("Métodos que abren cajón")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Lector" }));
    expect(screen.getByText("Escáner código de barras")).toBeTruthy();
    expect(screen.queryByText("ESC/POS")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Visor de cliente" }));
    expect(screen.getByText("Visor de cliente", { selector: "h2" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Prueba venta" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Prueba cobro" })).toBeTruthy();
  });

  it("configures only additional ESC/POS feed while preserving the current minimum", async () => {
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({
      getHardwareConfig: vi.fn(async () => escposConfig()),
      saveHardwareConfig,
    }));
    renderHardware({ mode: "printing" });

    fireEvent.click(screen.getByRole("button", { name: "Tickets" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Modo de impresora" }))
      .toHaveTextContent("ESC/POS directo"));
    const input = screen.getByRole("spinbutton", { name: "Espacio final adicional (líneas)" });
    const help = screen.getByText("Líneas extra antes del corte. 0 mantiene el mínimo.");
    const label = screen.getByText("Espacio final adicional (líneas)");
    expect(label.compareDocumentPosition(help) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(help.compareDocumentPosition(input) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(input).toHaveAttribute("min", "0");
    expect(input).toHaveAttribute("max", "12");
    expect(input).toHaveValue(0);

    fireEvent.change(input, { target: { value: "5" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));

    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledWith(
      expect.objectContaining({ escposAdditionalFeedLines: 5 }),
    ));
  });

  it("keeps ticket driver and ESC/POS transport as separate saved settings", async () => {
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({
      getHardwareConfig: vi.fn(async () => escposConfig({ escposAdditionalFeedLines: 4 })),
      saveHardwareConfig,
    }));
    renderHardware({ mode: "printing" });
    await waitFor(() => expect(screen.getByRole("button", { name: "Modo de impresora" }))
      .toHaveTextContent("ESC/POS directo"));
    await waitFor(() => expect(screen.getByRole("spinbutton", { name: "Espacio final adicional (líneas)" }))
      .toHaveValue(4));

    fireEvent.click(screen.getByRole("button", { name: "Modo de impresora" }));
    fireEvent.click(screen.getByRole("option", { name: "ESC/POS directo" }));
    fireEvent.click(screen.getByRole("button", { name: "Conexión ESC/POS" }));
    fireEvent.click(screen.getByRole("option", { name: "COM" }));
    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));
    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledWith(
      expect.objectContaining({ ticketPrinterDriver: "ESCPOS_RAW", ticketPrinterConnection: "SERIAL" }),
    ));

    fireEvent.click(screen.getByRole("button", { name: "Modo de impresora" }));
    fireEvent.click(screen.getByRole("option", { name: "Impresora Windows" }));
    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));
    await waitFor(() => expect(saveHardwareConfig).toHaveBeenLastCalledWith(
      expect.objectContaining({ ticketPrinterDriver: "WINDOWS_DRIVER", ticketPrinterConnection: "SERIAL" }),
    ));
  });

  it("shows one ticket printer panel with fields matched to the selected driver and transport", async () => {
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    const getHardwareConfig = vi.fn(async () => escposConfig({
      ticketPrinterConnection: "SERIAL",
      ticketPrinterName: "Epson receipt",
      escposDevicePath: "COM7",
      escposSerialBaudRate: 19200,
      escposHost: "192.0.2.7",
      escposPort: 9200,
      escposAdditionalFeedLines: 3,
    }));
    installHardware(createHardwareBridge({ getHardwareConfig, saveHardwareConfig }));
    renderHardware({ mode: "printing" });
    fireEvent.click(screen.getByRole("button", { name: "Tickets" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Modo de impresora" }))
      .toHaveTextContent("ESC/POS directo"));

    const expectWindowsPrinter = () => {
      expect(screen.getAllByRole("button", { name: "Impresora Windows" })).toHaveLength(1);
      expect(screen.getByRole("button", { name: "Detectar impresoras" })).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Conexión ESC/POS" })).toBeNull();
      expect(screen.queryByRole("button", { name: "Dispositivo / puerto" })).toBeNull();
      expect(screen.queryByRole("spinbutton", { name: "Velocidad COM" })).toBeNull();
      expect(screen.queryByRole("textbox", { name: "IP" })).toBeNull();
      expect(screen.queryByRole("spinbutton", { name: "Puerto" })).toBeNull();
      expect(screen.queryByRole("spinbutton", { name: "Espacio final adicional (líneas)" })).toBeNull();
      expect(screen.queryByText(/ESC\/POS directo/i)).toBeNull();
      expect(screen.getByRole("button", { name: "Imprimir prueba" })).toBeInTheDocument();
    };
    const expectSerial = () => {
      expect(screen.getAllByRole("button", { name: "Dispositivo / puerto" })).toHaveLength(1);
      expect(screen.getByRole("combobox", { name: "Velocidad COM" })).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Impresora Windows" })).toBeNull();
      expect(screen.queryByRole("button", { name: "Detectar impresoras" })).toBeNull();
      expect(screen.queryByRole("textbox", { name: "IP" })).toBeNull();
      expect(screen.queryByRole("spinbutton", { name: "Puerto" })).toBeNull();
      expect(screen.getByRole("spinbutton", { name: "Espacio final adicional (líneas)" })).toHaveValue(3);
      expect(screen.getByRole("button", { name: "Imprimir prueba" })).toBeInTheDocument();
    };

    expect(screen.getAllByRole("heading", { name: "Impresora de tickets" })).toHaveLength(1);
    expect(screen.queryByRole("button", { name: "Impresora Windows" })).toBeNull();
    expect(screen.getByRole("button", { name: "Modo de impresora" })).toHaveTextContent("ESC/POS directo");
    expect(screen.getByRole("button", { name: "Conexión ESC/POS" })).toHaveTextContent("COM");
    expectSerial();

    fireEvent.click(screen.getByRole("button", { name: "Conexión ESC/POS" }));
    fireEvent.click(screen.getByRole("option", { name: "LAN" }));
    expect(screen.getByRole("textbox", { name: "IP" })).toHaveValue("192.0.2.7");
    expect(screen.getByRole("spinbutton", { name: "Puerto" })).toHaveValue(9200);
    expect(screen.queryByRole("button", { name: "Dispositivo / puerto" })).toBeNull();
    expect(screen.queryByRole("spinbutton", { name: "Velocidad COM" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Impresora Windows" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Detectar impresoras" })).toBeNull();
    expect(screen.getByRole("spinbutton", { name: "Espacio final adicional (líneas)" })).toHaveValue(3);

    fireEvent.click(screen.getByRole("button", { name: "Conexión ESC/POS" }));
    fireEvent.click(screen.getByRole("option", { name: "USB / Windows RAW" }));
    expect(screen.getAllByRole("button", { name: "Impresora Windows" })).toHaveLength(1);
    expect(screen.getByRole("button", { name: "Detectar impresoras" })).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "IP" })).toBeNull();
    expect(screen.queryByRole("spinbutton", { name: "Puerto" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Dispositivo / puerto" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Modo de impresora" }));
    fireEvent.click(screen.getByRole("option", { name: "Impresora Windows" }));
    expectWindowsPrinter();
    expect(saveHardwareConfig).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Modo de impresora" }));
    fireEvent.click(screen.getByRole("option", { name: "ESC/POS directo" }));
    expect(screen.getByRole("button", { name: "Conexión ESC/POS" })).toHaveTextContent("USB / Windows RAW");
    expect(screen.getAllByRole("button", { name: "Impresora Windows" })).toHaveLength(1);
    expect(screen.getByRole("button", { name: "Detectar impresoras" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Conexión ESC/POS" }));
    fireEvent.click(screen.getByRole("option", { name: "COM" }));
    expectSerial();
    expect(screen.getByRole("button", { name: "Dispositivo / puerto" })).toHaveTextContent("COM7");
    const baudRate = screen.getByRole("combobox", { name: "Velocidad COM" });
    expect(baudRate).toHaveValue("19200");
    expect(screen.queryByRole("textbox", { name: "IP" })).toBeNull();
    expect(screen.queryByRole("spinbutton", { name: "Puerto" })).toBeNull();
    expect(saveHardwareConfig).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));
    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledWith(expect.objectContaining({
      ticketPrinterDriver: "ESCPOS_RAW",
      ticketPrinterConnection: "SERIAL",
      ticketPrinterName: "Epson receipt",
      escposDevicePath: "COM7",
      escposSerialBaudRate: 19200,
      escposHost: "192.0.2.7",
      escposPort: 9200,
      escposAdditionalFeedLines: 3,
    })));
    expect(saveHardwareConfig).toHaveBeenCalledOnce();
    expect(getHardwareConfig).toHaveBeenCalledOnce();
  });

  it("accepts and saves a custom ESC/POS serial speed", async () => {
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({
      getHardwareConfig: vi.fn(async () => escposConfig({ ticketPrinterConnection: "SERIAL", escposSerialBaudRate: 19200 })),
      saveHardwareConfig,
    }));
    renderHardware({ mode: "printing" });
    fireEvent.click(screen.getByRole("button", { name: "Tickets" }));
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Velocidad COM" })).toHaveValue("19200"));

    fireEvent.change(screen.getByRole("combobox", { name: "Velocidad COM" }), { target: { value: "31250" } });
    expect(screen.getByRole("combobox", { name: "Velocidad COM" })).toHaveValue("31250");
    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));

    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledWith(
      expect.objectContaining({ escposSerialBaudRate: 31250 }),
    ));
  });

  it("selects the ESC/POS printer COM port from Windows without changing other hardware or saving on detection", async () => {
    const listSerialPorts = vi.fn()
      .mockResolvedValueOnce({ ok: true, ports: [{ path: "COM1" }, { path: "COM4" }] })
      .mockResolvedValueOnce({ ok: true, ports: [{ path: "COM1" }] });
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    const hardware = createHardwareBridge({
      getHardwareConfig: vi.fn(async () => ({ ...defaultHardwareConfig,
        ticketPrinterDriver: "ESCPOS_RAW" as const, ticketPrinterConnection: "SERIAL" as const,
        escposDevicePath: "COM7", escposSerialBaudRate: 19200,
        customerDisplayDevicePath: "COM2", cashDrawerDevicePath: "COM3" })),
      listSerialPorts, saveHardwareConfig,
    });
    installHardware(hardware);
    renderHardware({ mode: "printing" });
    await waitFor(() => expect(screen.getByRole("button", { name: "Dispositivo / puerto" })).toHaveTextContent("COM7 (no detectado)"));
    expect(screen.queryByRole("textbox", { name: "Dispositivo / puerto" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Dispositivo / puerto" }));
    fireEvent.click(screen.getByRole("option", { name: "COM4" }));
    fireEvent.click(screen.getByRole("button", { name: "Actualizar puertos" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Dispositivo / puerto" })).toHaveTextContent("COM4 (no detectado)"));
    expect(listSerialPorts).toHaveBeenCalledTimes(2);
    expect(saveHardwareConfig).not.toHaveBeenCalled();
    expect(hardware.printTicket).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));
    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledWith(expect.objectContaining({
      ticketPrinterDriver: "ESCPOS_RAW", ticketPrinterConnection: "SERIAL",
      escposDevicePath: "COM4", escposSerialBaudRate: 19200,
      customerDisplayDevicePath: "COM2", cashDrawerDevicePath: "COM3",
    })));
  });

  it("keeps documentRoutingOnly compatible and renders all four real routes", async () => {
    const listPrinters = vi.fn(async () => ({ ok: true as const, printers: [] }));
    installHardware(createHardwareBridge({ listPrinters }));
    renderHardware({ documentRoutingOnly: true });

    await waitFor(() => expect(listPrinters).toHaveBeenCalledOnce());

    expect(document.querySelectorAll(".hardware-device-tabs button")).toHaveLength(4);
    fireEvent.click(screen.getByRole("button", { name: "Asignación de documentos" }));
    await waitFor(() => expect(listPrinters).toHaveBeenCalledTimes(2));
    expect(screen.getByText("Factura")).toBeTruthy();
    expect(screen.getByText("Albaran")).toBeTruthy();
    expect(screen.getByText("Ticket")).toBeTruthy();
    expect(screen.getByText("Informe")).toBeTruthy();
    expect(screen.queryByText("Cajón de dinero")).toBeNull();
    expect(document.querySelectorAll(".hardware-route-row")).toHaveLength(4);
    const routeHeadings = Array.from(document.querySelectorAll<HTMLElement>(".hardware-route-header [data-column-key]"));
    expect(routeHeadings.map((heading) => heading.dataset.columnKey)).toEqual(
      hardwareRouteColumnDefinitions.map((column) => column.key),
    );
    expect(routeHeadings.every((heading) => heading.draggable)).toBe(true);
    expect(document.querySelectorAll(".hardware-route-header .table-layout-column-resizer")).toHaveLength(8);
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
    fireEvent.click(screen.getByRole("button", { name: "Etiquetas" }));
    fireEvent.click(screen.getByRole("button", { name: "Abrir impresión de etiquetas" }));
    expect(onOpenProductLabels).toHaveBeenCalledOnce();
  });

  it("does not load or execute the hardware bridge without CONFIGURACION_TERMINAL or ADMIN", async () => {
    const fetch = mockPrinterTestResponse();
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
    expect(hardware.listSerialPorts).not.toHaveBeenCalled();
    expect(hardware.listCustomerDisplays).not.toHaveBeenCalled();
    expect(fetch.mock.calls.some(([url]) => url === "/api/v1/hardware/printer-test")).toBe(false);
  });

  it("detects Windows printers on entry and refresh while preserving the saved printer", async () => {
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
    renderHardware({ mode: "printing" });

    await waitFor(() => expect(getHardwareConfig).toHaveBeenCalledOnce());
    await waitFor(() => expect(listPrinters).toHaveBeenCalledOnce());
    const printerSelect = screen.getByRole("button", { name: "Impresora Windows" });
    await waitFor(() => expect(printerSelect.textContent).toContain("EPSON TM-T20"));
    expect(printerSelect.textContent).toContain("EPSON TM-T20");

    fireEvent.click(screen.getByRole("button", { name: "Detectar impresoras" }));
    await waitFor(() => expect(listPrinters).toHaveBeenCalledTimes(2));
    expect(printerSelect.textContent).toContain("EPSON TM-T20");

    fireEvent.click(printerSelect);
    fireEvent.click(screen.getByRole("option", { name: /WPS Print to PDF/ }));
    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));
    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledWith(
      expect.objectContaining({ ticketPrinterName: "WPS Print to PDF" }),
    ));
  });

  it("does not choose the Windows default when the saved printer is empty", async () => {
    const listPrinters = vi.fn(async () => ({
      ok: true as const,
      printers: [{ name: "WPS Print to PDF", displayName: "WPS Print to PDF", isDefault: true }],
    }));
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({
      listPrinters,
      getHardwareConfig: vi.fn(async () => ({ ...defaultHardwareConfig, ticketPrinterName: "" })),
      saveHardwareConfig,
    }));
    renderHardware({ mode: "printing" });

    await waitFor(() => expect(listPrinters).toHaveBeenCalledOnce());
    expect(screen.getByRole("button", { name: "Impresora Windows" })).toHaveTextContent("Selecciona impresora");
    expect(screen.queryByText("WPS Print to PDF", { selector: "button" })).toBeNull();
    expect(saveHardwareConfig).not.toHaveBeenCalled();
  });

  it("refreshes printers when entering A4 and preserves its saved printer instead of the Windows default", async () => {
    const listPrinters = vi.fn(async () => ({ ok: true as const, printers: [
      { name: "WPS Print to PDF", displayName: "WPS Print to PDF", isDefault: true },
      { name: "A4 Office", displayName: "A4 Office", isDefault: false },
    ] }));
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({
      listPrinters,
      getHardwareConfig: vi.fn(async () => ({ ...defaultHardwareConfig, a4PrinterName: "A4 Office" })),
      saveHardwareConfig,
    }));
    renderHardware({ mode: "printing" });

    await waitFor(() => expect(listPrinters).toHaveBeenCalledOnce());
    fireEvent.click(screen.getByRole("button", { name: "A4" }));
    await waitFor(() => expect(listPrinters).toHaveBeenCalledTimes(2));
    const a4Printer = screen.getByRole("button", { name: "Impresora A4" });
    await waitFor(() => expect(a4Printer).toHaveTextContent("A4 Office"));
    expect(a4Printer).not.toHaveTextContent("WPS Print to PDF");
    expect(saveHardwareConfig).not.toHaveBeenCalled();
  });

  it("keeps an empty A4 printer selection empty after detection", async () => {
    const listPrinters = vi.fn(async () => ({ ok: true as const, printers: [
      { name: "WPS Print to PDF", displayName: "WPS Print to PDF", isDefault: true },
    ] }));
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({
      listPrinters,
      getHardwareConfig: vi.fn(async () => ({ ...defaultHardwareConfig, a4PrinterName: "" })),
      saveHardwareConfig,
    }));
    renderHardware({ mode: "printing" });

    await waitFor(() => expect(listPrinters).toHaveBeenCalledOnce());
    fireEvent.click(screen.getByRole("button", { name: "A4" }));
    await waitFor(() => expect(listPrinters).toHaveBeenCalledTimes(2));
    const a4Printer = screen.getByRole("button", { name: "Impresora A4" });
    expect(a4Printer).toHaveTextContent("Selecciona impresora");
    expect(a4Printer).not.toHaveTextContent("WPS Print to PDF");
    expect(saveHardwareConfig).not.toHaveBeenCalled();
  });

  it("refreshes again on return to A4 while preserving an unsaved manual selection", async () => {
    const listPrinters = vi.fn(async () => ({ ok: true as const, printers: [
      { name: "First A4", displayName: "First A4", isDefault: true },
      { name: "Manual A4", displayName: "Manual A4", isDefault: false },
    ] }));
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({
      listPrinters,
      getHardwareConfig: vi.fn(async () => ({ ...defaultHardwareConfig, a4PrinterName: "First A4" })),
      saveHardwareConfig,
    }));
    renderHardware({ mode: "printing" });

    await waitFor(() => expect(listPrinters).toHaveBeenCalledOnce());
    fireEvent.click(screen.getByRole("button", { name: "A4" }));
    await waitFor(() => expect(listPrinters).toHaveBeenCalledTimes(2));
    const a4Printer = screen.getByRole("button", { name: "Impresora A4" });
    fireEvent.click(a4Printer);
    fireEvent.click(screen.getByRole("option", { name: "Manual A4" }));
    expect(a4Printer).toHaveTextContent("Manual A4");

    fireEvent.click(screen.getByRole("button", { name: "Asignación de documentos" }));
    await waitFor(() => expect(listPrinters).toHaveBeenCalledTimes(3));
    fireEvent.click(screen.getByRole("button", { name: "A4" }));
    await waitFor(() => expect(listPrinters).toHaveBeenCalledTimes(4));

    expect(a4Printer).toHaveTextContent("Manual A4");
    expect(saveHardwareConfig).not.toHaveBeenCalled();
  });

  it("retains unsaved invoice route edits across printer tabs and saves them only on request", async () => {
    const listPrinters = vi.fn(async () => ({ ok: true as const, printers: [
      { name: "Invoice Printer", displayName: "Invoice Printer", isDefault: false },
    ] }));
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({ listPrinters, saveHardwareConfig }));
    renderHardware({ mode: "printing" });

    await waitFor(() => expect(listPrinters).toHaveBeenCalledOnce());
    fireEvent.click(screen.getByRole("button", { name: "Asignación de documentos" }));
    await waitFor(() => expect(listPrinters).toHaveBeenCalledTimes(2));
    const invoiceRow = screen.getByText("Factura").closest(".hardware-route-row") as HTMLElement;
    const invoice = within(invoiceRow);

    fireEvent.click(invoice.getByRole("button", { name: "Destino" }));
    fireEvent.click(screen.getByRole("option", { name: "Impresora ticket" }));
    fireEvent.click(invoice.getByRole("button", { name: "Impresora" }));
    fireEvent.click(screen.getByRole("option", { name: "Invoice Printer" }));
    fireEvent.change(invoice.getByRole("spinbutton", { name: "Copias" }), { target: { value: "2" } });
    const routeChecks = invoice.getAllByRole("checkbox");
    fireEvent.click(routeChecks[0]);
    fireEvent.click(routeChecks[1]);

    expect(saveHardwareConfig).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "A4" }));
    await waitFor(() => expect(listPrinters).toHaveBeenCalledTimes(3));
    fireEvent.click(screen.getByRole("button", { name: "Asignación de documentos" }));
    await waitFor(() => expect(listPrinters).toHaveBeenCalledTimes(4));

    const retainedInvoiceRow = screen.getByText("Factura").closest(".hardware-route-row") as HTMLElement;
    const retainedInvoice = within(retainedInvoiceRow);
    expect(retainedInvoice.getByRole("button", { name: "Destino" })).toHaveTextContent("Impresora ticket");
    expect(retainedInvoice.getByRole("button", { name: "Impresora" })).toHaveTextContent("Invoice Printer");
    expect(retainedInvoice.getByRole("spinbutton", { name: "Copias" })).toHaveValue(2);
    const retainedChecks = retainedInvoice.getAllByRole("checkbox");
    expect(retainedChecks[0]).toBeChecked();
    expect(retainedChecks[1]).not.toBeChecked();
    expect(saveHardwareConfig).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));
    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledWith(expect.objectContaining({
      documentPrintRoutes: expect.arrayContaining([expect.objectContaining({
        documentType: "INVOICE",
        printerTarget: "TICKET_PRINTER",
        printerName: "Invoice Printer",
        paperSize: "TICKET_80",
        copies: 2,
        printAutomatically: true,
        showPrintDialog: false,
      })]),
    })));
  });

  it("keeps the saved printer after automatic detection fails and allows retry", async () => {
    const listPrinters = vi.fn()
      .mockRejectedValueOnce(new Error("printer enumeration failed"))
      .mockResolvedValueOnce({ ok: true as const, printers: [
        { name: "EPSON TM-T20", displayName: "EPSON TM-T20", isDefault: false },
      ] });
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    const getHardwareConfig = vi.fn(async () => ({ ...defaultHardwareConfig, ticketPrinterName: "EPSON TM-T20" }));
    installHardware(createHardwareBridge({
      listPrinters,
      getHardwareConfig,
      saveHardwareConfig,
    }));
    renderHardware({ mode: "printing" });

    await waitFor(() => expect(listPrinters).toHaveBeenCalledOnce());
    await waitFor(() => expect(getHardwareConfig).toHaveBeenCalledOnce());
    const printerSelect = screen.getByRole("button", { name: "Impresora Windows" });
    fireEvent.click(screen.getByRole("button", { name: "Detectar impresoras" }));
    await waitFor(() => expect(listPrinters).toHaveBeenCalledTimes(2));
    await act(async () => {});
    await waitFor(() => expect(printerSelect).toHaveTextContent("EPSON TM-T20"));
    expect(saveHardwareConfig).not.toHaveBeenCalled();
  });

  it("lets the manual refresh win when it finishes before the automatic enumeration", async () => {
    let finishAutomatic: ((value: { ok: true; printers: { name: string; displayName: string; isDefault: boolean }[] }) => void) | undefined;
    const listPrinters = vi.fn()
      .mockImplementationOnce(() => new Promise((resolve) => { finishAutomatic = resolve; }))
      .mockResolvedValueOnce({ ok: true as const, printers: [
        { name: "Manual Printer", displayName: "Manual Printer", isDefault: false },
      ] });
    installHardware(createHardwareBridge({ listPrinters }));
    renderHardware({ mode: "printing" });

    await waitFor(() => expect(listPrinters).toHaveBeenCalledOnce());
    fireEvent.click(screen.getByRole("button", { name: "Detectar impresoras" }));
    await waitFor(() => expect(listPrinters).toHaveBeenCalledTimes(2));
    fireEvent.click(screen.getByRole("button", { name: "Impresora Windows" }));
    await waitFor(() => expect(screen.getByRole("option", { name: "Manual Printer" })).toBeInTheDocument());
    await act(async () => finishAutomatic?.({ ok: true, printers: [
      { name: "Stale Automatic Printer", displayName: "Stale Automatic Printer", isDefault: false },
    ] }));

    expect(screen.getByRole("option", { name: "Manual Printer" })).toBeInTheDocument();
    expect(screen.queryByText("Stale Automatic Printer")).toBeNull();
  });

  it("starts printer detection when a device screen rerenders into printing mode", async () => {
    const listPrinters = vi.fn(async () => ({ ok: true as const, printers: [] }));
    installHardware(createHardwareBridge({ listPrinters }));
    const { rerender } = renderHardware({ mode: "devices" });

    await act(async () => {});
    expect(listPrinters).not.toHaveBeenCalled();
    rerender(<HardwareSettingsScreen
      app="venta"
      locale="es"
      session={session}
      terminalContext={terminalContext}
      onBack={vi.fn()}
      onLocaleChange={vi.fn()}
      onLogout={vi.fn()}
      mode="printing"
    />);

    await waitFor(() => expect(listPrinters).toHaveBeenCalledOnce());
  });

  it("automatically detects Windows printers in printers mode", async () => {
    const listPrinters = vi.fn(async () => ({ ok: true as const, printers: [] }));
    installHardware(createHardwareBridge({ listPrinters }));
    renderHardware({ mode: "printers" });

    await waitFor(() => expect(listPrinters).toHaveBeenCalledOnce());
  });

  it("does not detect Windows printers in device or diagnostics mode", async () => {
    const listPrinters = vi.fn(async () => ({ ok: true as const, printers: [] }));
    installHardware(createHardwareBridge({ listPrinters }));
    const { rerender } = renderHardware({ mode: "devices" });
    await act(async () => {});
    expect(listPrinters).not.toHaveBeenCalled();

    rerender(<HardwareSettingsScreen
      app="venta"
      locale="es"
      session={session}
      terminalContext={terminalContext}
      onBack={vi.fn()}
      onLocaleChange={vi.fn()}
      onLogout={vi.fn()}
      mode="diagnostics"
    />);
    await act(async () => {});
    expect(listPrinters).not.toHaveBeenCalled();
  });

  it("prints the server-rendered test ticket through the desktop hardware bridge", async () => {
    const fetch = mockPrinterTestResponse();
    const printTicket = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({ printTicket }));
    renderHardware({ mode: "printing" });

    fireEvent.click(screen.getByRole("button", { name: "Imprimir prueba" }));
    await waitFor(() => expect(printTicket).toHaveBeenCalledOnce());
    expect(fetch).toHaveBeenCalledWith("/api/v1/hardware/printer-test", expect.objectContaining({
      method: "GET",
      headers: expect.objectContaining({ Authorization: "Bearer hardware-test-token" }),
    }));
    expect(printTicket).toHaveBeenCalledWith(expect.objectContaining({
      storeName: "Tienda Principal",
      terminalCode: "01",
      requireRenderedDocument: true,
      renderedPdf: printerTestArtifacts.renderedPdf,
      documentRaster: `data:image/png;base64,${printerTestArtifacts.ticketRenderedImage.base64}`,
    }), defaultHardwareConfig);
    expect(screen.getByText("Ticket de prueba enviado")).toBeTruthy();
  });

  it("reports an API failure without invoking the desktop printer", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => ({
      ok: false, status: 503, statusText: "Unavailable", headers: { get: () => null },
      json: async () => ({ detail: "Printer test unavailable" }),
    })));
    const printTicket = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({ printTicket }));
    renderHardware({ mode: "printing" });

    fireEvent.click(screen.getByRole("button", { name: "Imprimir prueba" }));
    await waitFor(() => expect(screen.getByText("No se pudo completar la operación")).toBeInTheDocument());
    expect(printTicket).not.toHaveBeenCalled();
  });

  it.each([
    { renderedPdf: null, ticketRenderedImage: printerTestArtifacts.ticketRenderedImage },
    { renderedPdf: printerTestArtifacts.renderedPdf, ticketRenderedImage: null },
    { renderedPdf: { contentType: "text/html", base64: "JVBERi0xLjQ=" }, ticketRenderedImage: printerTestArtifacts.ticketRenderedImage },
    { renderedPdf: printerTestArtifacts.renderedPdf, ticketRenderedImage: { contentType: "image/png", base64: "invalid" } },
  ])("rejects invalid server artifacts without falling back to HTML: %j", async (artifacts) => {
    mockPrinterTestResponse(artifacts);
    const printTicket = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({ printTicket }));
    renderHardware({ mode: "printing" });

    fireEvent.click(screen.getByRole("button", { name: "Imprimir prueba" }));
    await waitFor(() => expect(screen.getByText("No se pudo obtener el ticket de prueba para imprimir")).toBeInTheDocument());
    expect(printTicket).not.toHaveBeenCalled();
  });

  it("does not request a second ticket while the first print is in progress", async () => {
    let finishRequest: ((value: typeof printerTestArtifacts) => void) | undefined;
    const fetch = vi.fn((url: string) => url.endsWith("/hardware/printer-test") ? new Promise<{
      ok: boolean; status: number; headers: { get: () => null }; json: () => Promise<typeof printerTestArtifacts>;
    }>((resolve) => {
      finishRequest = (artifacts) => resolve({
        ok: true, status: 200, headers: { get: () => null }, json: async () => artifacts,
      });
    }) : Promise.resolve({ ok: true, status: 200, headers: { get: () => null }, json: async () => ({}) }));
    vi.stubGlobal("fetch", fetch);
    const printTicket = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({ printTicket }));
    renderHardware({ mode: "printing" });

    const button = screen.getByRole("button", { name: "Imprimir prueba" });
    fireEvent.click(button);
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(fetch.mock.calls.filter(([url]) => url.endsWith("/hardware/printer-test"))).toHaveLength(1);
    await act(async () => finishRequest?.(printerTestArtifacts));
    await waitFor(() => expect(printTicket).toHaveBeenCalledOnce());
    expect(button).toBeEnabled();
  });

  it("does not present desktop-only printer actions as functional in a browser", () => {
    renderHardware({ mode: "printing" });

    expect(screen.getByRole("button", { name: "Detectar impresoras" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Imprimir prueba" })).toBeDisabled();
    expect(screen.getByText(/disponibles en esPOS VENTA de escritorio/)).toBeTruthy();
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

  it("does not choose a customer display automatically after detection", async () => {
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({
      listCustomerDisplays: vi.fn(async () => ({ ok: true as const, displays: [
        { id: "secondary", label: "Secundaria", width: 1920, height: 1080, primary: false },
      ] })),
      saveHardwareConfig,
    }));
    renderHardware();
    await waitFor(() => expect(screen.getByRole("button", { name: "Visor de cliente" })).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));
    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledWith(
      expect.objectContaining({ customerDisplayScreenId: "" }),
    ));
  });

  it("edits an existing label profile and saves other hardware fields unchanged", async () => {
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    const existing = {
      ...defaultHardwareConfig,
      ticketPrinterName: "EPSON TM-T20",
      productLabelProfiles: [{
        ...defaultHardwareConfig.productLabelProfiles[0],
        id: "existing-label",
        name: "Producto 58 x 40",
      }],
      defaultProductLabelProfileId: "existing-label",
    };
    installHardware(createHardwareBridge({
      getHardwareConfig: vi.fn(async () => existing),
      saveHardwareConfig,
    }));
    renderHardware({ mode: "printing" });
    fireEvent.click(screen.getByRole("button", { name: "Etiquetas" }));
    const profileName = screen.getByRole("textbox", { name: /Nombre/i });
    await waitFor(() => expect(profileName).toHaveValue("Producto 58 x 40"));
    fireEvent.change(profileName, { target: { value: "Producto 60 x 40" } });
    fireEvent.change(screen.getByRole("spinbutton", { name: /Ancho/i }), { target: { value: "60" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));
    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledWith(
      expect.objectContaining({
        ticketPrinterName: "EPSON TM-T20",
        defaultProductLabelProfileId: "existing-label",
        productLabelProfiles: [expect.objectContaining({
          id: "existing-label", name: "Producto 60 x 40", widthMm: 60,
        })],
      }),
    ));
  });

  it("discards unsaved hardware changes and continues when Cancelar is chosen", async () => {
    const confirm = vi.spyOn(window, "confirm");
    const onBack = vi.fn();
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({
      getHardwareConfig: vi.fn(async () => escposConfig({ escposAdditionalFeedLines: 1 })),
      saveHardwareConfig,
    }));
    renderHardware({ mode: "printing", onBack });
    await waitFor(() => expect(screen.getByRole("button", { name: "Modo de impresora" }))
      .toHaveTextContent("ESC/POS directo"));
    const feed = screen.getByRole("spinbutton", { name: "Espacio final adicional (líneas)" });
    await waitFor(() => expect(feed).toHaveValue(1));
    fireEvent.change(feed, { target: { value: "4" } });
    fireEvent.click(screen.getByRole("button", { name: /Volver/i }));
    expect(onBack).not.toHaveBeenCalled();
    expect(screen.getByRole("alertdialog", { name: "¿Desea guardar cambios?" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(onBack).toHaveBeenCalledOnce();
    expect(feed).toHaveValue(1);
    expect(saveHardwareConfig).not.toHaveBeenCalled();
    expect(confirm).not.toHaveBeenCalled();
  });

  it("routes Escape through the ERP save dialog and waits for persistence", async () => {
    let finishSave: ((value: { ok: true }) => void) | undefined;
    const saveHardwareConfig = vi.fn(() => new Promise<{ ok: true }>((resolve) => { finishSave = resolve; }));
    const onBack = vi.fn();
    installHardware(createHardwareBridge({
      getHardwareConfig: vi.fn(async () => escposConfig({ escposAdditionalFeedLines: 1 })),
      saveHardwareConfig,
    }));
    renderHardware({ mode: "printing", onBack });
    await waitFor(() => expect(screen.getByRole("button", { name: "Modo de impresora" }))
      .toHaveTextContent("ESC/POS directo"));
    const feed = screen.getByRole("spinbutton", { name: "Espacio final adicional (líneas)" });
    await waitFor(() => expect(feed).toHaveValue(1));
    fireEvent.change(feed, { target: { value: "2" } });

    act(() => { window.dispatchEvent(new Event("tpv-sale-settings-back", { cancelable: true })); });
    expect(onBack).not.toHaveBeenCalled();
    expect(screen.getByRole("alertdialog", { name: "¿Desea guardar cambios?" })).toBeInTheDocument();
    expect(feed).toHaveValue(2);

    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    expect(saveHardwareConfig).toHaveBeenCalledOnce();
    expect(onBack).not.toHaveBeenCalled();
    await act(async () => { finishSave?.({ ok: true }); });
    await waitFor(() => expect(onBack).toHaveBeenCalledOnce());
    expect(screen.queryByRole("alertdialog")).toBeNull();
  });

  it("keeps the dialog and hardware edits when saving before navigation fails", async () => {
    const onBack = vi.fn();
    const saveHardwareConfig = vi.fn(async () => ({
      ok: false as const,
      code: "HARDWARE_UNAVAILABLE" as const,
      message: "No se pudo guardar",
    }));
    installHardware(createHardwareBridge({
      getHardwareConfig: vi.fn(async () => escposConfig({ escposAdditionalFeedLines: 1 })),
      saveHardwareConfig,
    }));
    renderHardware({ mode: "printing", onBack });
    await waitFor(() => expect(screen.getByRole("button", { name: "Modo de impresora" }))
      .toHaveTextContent("ESC/POS directo"));
    const feed = screen.getByRole("spinbutton", { name: "Espacio final adicional (líneas)" });
    await waitFor(() => expect(feed).toHaveValue(1));
    fireEvent.change(feed, { target: { value: "2" } });
    fireEvent.click(screen.getByRole("button", { name: "Volver" }));
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledOnce());
    expect(onBack).not.toHaveBeenCalled();
    expect(screen.getByRole("alertdialog", { name: "¿Desea guardar cambios?" })).toBeInTheDocument();
    expect(feed).toHaveValue(2);
    expect(screen.getAllByText("No se pudo guardar").length).toBeGreaterThan(0);
  });

  it("allows leaving without another prompt after successive saves", async () => {
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    const onBack = vi.fn();
    installHardware(createHardwareBridge({
      getHardwareConfig: vi.fn(async () => escposConfig()),
      saveHardwareConfig,
    }));
    renderHardware({ mode: "printing", onBack });
    await waitFor(() => expect(screen.getByRole("button", { name: "Modo de impresora" }))
      .toHaveTextContent("ESC/POS directo"));
    const feed = screen.getByRole("spinbutton", { name: "Espacio final adicional (líneas)" });
    fireEvent.change(feed, { target: { value: "2" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));
    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledTimes(1));
    fireEvent.change(feed, { target: { value: "3" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));
    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledTimes(2));
    fireEvent.click(screen.getByRole("button", { name: "Volver" }));
    expect(onBack).toHaveBeenCalledOnce();
    expect(screen.queryByRole("alertdialog")).toBeNull();
  });

  it("verifies a scanner with the fixed automatic timing rule", async () => {
    const testScannerInput = vi.fn(async (code: string) => ({
      ok: true as const,
      code,
      readAt: "2026-07-25T12:00:00Z",
    }));
    installHardware(createHardwareBridge({ testScannerInput }));
    renderHardware();
    fireEvent.click(screen.getByRole("button", { name: "Lector" }));

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
    mockPrinterTestResponse();
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

  it("configures a physical line display and announces a successful save without connection details", async () => {
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({ saveHardwareConfig }));
    const changed = vi.fn();
    window.addEventListener("tpv:hardware-config-changed", changed);
    try {
      renderHardware();
      await act(async () => {});
      fireEvent.click(screen.getByRole("button", { name: "Visor de cliente" }));
      fireEvent.click(screen.getByRole("radio", { name: "Visor físico de líneas" }));
      expect(screen.getByRole("button", { name: "Puerto COM" })).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Pantalla destino" })).toBeNull();
      await waitFor(() => expect(screen.getByRole("button", { name: "Actualizar puertos" })).toBeEnabled());
      fireEvent.click(screen.getByRole("button", { name: "Puerto COM" }));
      fireEvent.click(screen.getByRole("option", { name: "COM4" }));
      fireEvent.click(screen.getByRole("button", { name: "Conexión" }));
      fireEvent.click(screen.getByRole("option", { name: "Red TCP" }));
      fireEvent.change(screen.getByRole("textbox", { name: "Dirección de red" }), { target: { value: "192.0.2.10" } });
      fireEvent.change(screen.getByRole("spinbutton", { name: "Puerto TCP" }), { target: { value: "9000" } });
      fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));
      await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledWith(expect.objectContaining({
        customerDisplayMode: "LINE_DISPLAY", customerDisplayConnection: "NETWORK",
        customerDisplayDevicePath: "COM4", customerDisplayHost: "192.0.2.10", customerDisplayPort: 9000,
      })));
      expect(changed).toHaveBeenCalledOnce();
      expect((changed.mock.calls[0][0] as CustomEvent).detail).toEqual({
        customerDisplayEnabled: false, customerDisplayMode: "LINE_DISPLAY",
      });
    } finally {
      window.removeEventListener("tpv:hardware-config-changed", changed);
    }
  });

  it("keeps a custom line-display baud rate editable and independent from printer settings", async () => {
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    const listSerialPorts = vi.fn()
      .mockResolvedValueOnce({ ok: true, ports: [{ path: "COM4" }] })
      .mockResolvedValueOnce({ ok: true, ports: [{ path: "COM4" }, { path: "COM8" }] });
    installHardware(createHardwareBridge({
      getHardwareConfig: vi.fn(async () => ({
        ...defaultHardwareConfig,
        ticketPrinterConnection: "SERIAL" as const,
        escposDevicePath: "COM2",
        escposSerialBaudRate: 57600,
        customerDisplayMode: "LINE_DISPLAY" as const,
        customerDisplayDevicePath: "COM4",
        customerDisplaySerialBaudRate: 31250,
        customerDisplayLine1: "BIENVENIDOS",
        customerDisplayLine2: "VUELVA PRONTO",
      })),
      listSerialPorts,
      saveHardwareConfig,
    }));
    renderHardware();
    await act(async () => {});
    fireEvent.click(screen.getByRole("button", { name: "Visor de cliente" }));

    const baudRate = screen.getByRole("combobox", { name: "Velocidad (baudios)" });
    expect(baudRate).toHaveValue("31250");
    fireEvent.click(screen.getByRole("button", { name: "Velocidad (baudios)" }));
    expect(screen.getByRole("option", { name: "9600" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "19200" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("option", { name: "19200" }));
    expect(baudRate).toHaveValue("19200");

    fireEvent.change(baudRate, { target: { value: "74880" } });
    expect(baudRate).toHaveValue("74880");
    fireEvent.click(screen.getByRole("radio", { name: "Pantalla digital" }));
    fireEvent.click(screen.getByRole("radio", { name: "Visor físico de líneas" }));
    expect(screen.getByRole("combobox", { name: "Velocidad (baudios)" })).toHaveValue("74880");

    fireEvent.click(screen.getByRole("button", { name: "Actualizar puertos" }));
    await waitFor(() => expect(listSerialPorts).toHaveBeenCalledTimes(2));
    expect(screen.getByRole("combobox", { name: "Velocidad (baudios)" })).toHaveValue("74880");
    expect(saveHardwareConfig).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));
    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledWith(expect.objectContaining({
      customerDisplayMode: "LINE_DISPLAY",
      customerDisplayDevicePath: "COM4",
      customerDisplaySerialBaudRate: 74880,
      customerDisplayLine1: "BIENVENIDOS",
      customerDisplayLine2: "VUELVA PRONTO",
      ticketPrinterConnection: "SERIAL",
      escposDevicePath: "COM2",
      escposSerialBaudRate: 57600,
    })));
  });

  it("refreshes Windows COM options without replacing or automatically saving the chosen port", async () => {
    const listSerialPorts = vi.fn()
      .mockResolvedValueOnce({ ok: true, ports: [{ path: "COM2" }, { path: "COM10" }] })
      .mockResolvedValueOnce({ ok: true, ports: [{ path: "COM10" }] });
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({
      getHardwareConfig: vi.fn(async () => ({ ...defaultHardwareConfig,
        customerDisplayMode: "LINE_DISPLAY" as const, customerDisplayDevicePath: "COM7" })),
      listSerialPorts, saveHardwareConfig,
    }));
    renderHardware();
    fireEvent.click(screen.getByRole("button", { name: "Visor de cliente" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Puerto COM" })).toHaveTextContent("COM7 (no detectado)"));
    expect(listSerialPorts).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByRole("button", { name: "Puerto COM" }));
    expect(screen.getByRole("option", { name: "COM7 (no detectado)" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "COM10" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("option", { name: "COM2" }));
    fireEvent.click(screen.getByRole("button", { name: "Actualizar puertos" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Puerto COM" })).toHaveTextContent("COM2 (no detectado)"));
    expect(saveHardwareConfig).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Guardar configuración" }));
    await waitFor(() => expect(saveHardwareConfig).toHaveBeenCalledWith(expect.objectContaining({ customerDisplayDevicePath: "COM2" })));
  });

  it("shows an empty Windows port list and retains a disconnected configured port", async () => {
    installHardware(createHardwareBridge({
      getHardwareConfig: vi.fn(async () => ({ ...defaultHardwareConfig,
        customerDisplayMode: "LINE_DISPLAY" as const, customerDisplayDevicePath: "COM3" })),
      listSerialPorts: vi.fn(async () => ({ ok: true as const, ports: [] })),
    }));
    renderHardware();
    fireEvent.click(screen.getByRole("button", { name: "Visor de cliente" }));
    await waitFor(() => expect(screen.getByText("No se detectaron puertos COM en Windows.")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Puerto COM" })).toHaveTextContent("COM3 (no detectado)");
    expect(screen.getByRole("button", { name: "Actualizar puertos" })).toBeEnabled();
  });

  it("can retry failed enumeration without losing the saved selection or querying other display types", async () => {
    const listSerialPorts = vi.fn()
      .mockRejectedValueOnce(new Error("enumeration failed"))
      .mockResolvedValueOnce({ ok: true, ports: [{ path: "COM3" }] });
    installHardware(createHardwareBridge({
      getHardwareConfig: vi.fn(async () => ({ ...defaultHardwareConfig, customerDisplayDevicePath: "COM3" })),
      listSerialPorts,
    }));
    renderHardware();
    fireEvent.click(screen.getByRole("button", { name: "Visor de cliente" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Pantalla destino" })).toBeInTheDocument());
    expect(listSerialPorts).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("radio", { name: "Visor físico de líneas" }));
    await waitFor(() => expect(screen.getByText("No se pudieron detectar los puertos COM.")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Puerto COM" })).toHaveTextContent("COM3");
    fireEvent.click(screen.getByRole("button", { name: "Actualizar puertos" }));
    await waitFor(() => expect(screen.queryByText("No se pudieron detectar los puertos COM.")).toBeNull());
    await waitFor(() => expect(screen.getByRole("button", { name: "Actualizar puertos" })).toBeEnabled());
    expect(screen.getByRole("button", { name: "Puerto COM" })).toHaveTextContent("COM3");
    expect(listSerialPorts).toHaveBeenCalledTimes(2);
    fireEvent.click(screen.getByRole("button", { name: "Conexión" }));
    fireEvent.click(screen.getByRole("option", { name: "Red TCP" }));
    expect(screen.queryByRole("button", { name: "Puerto COM" })).toBeNull();
    expect(listSerialPorts).toHaveBeenCalledTimes(2);
  });

  it("offers only secondary screens and enriches display tests with locale and identity", async () => {
    const openCustomerDisplay = vi.fn(async () => ({ ok: true as const }));
    vi.stubGlobal("fetch", vi.fn(async () => { throw new Error("offline"); }));
    installHardware(createHardwareBridge({
      listCustomerDisplays: vi.fn(async () => ({ ok: true as const, displays: [
        { id: "primary", label: "Primary monitor", width: 1920, height: 1080, primary: true },
        { id: "second", label: "Customer monitor", width: 800, height: 600, primary: false },
      ] })),
      openCustomerDisplay,
    }));
    renderHardware();
    fireEvent.click(screen.getByRole("button", { name: "Visor de cliente" }));
    fireEvent.click(screen.getByRole("button", { name: "Pantalla destino" }));
    await waitFor(() => expect(screen.getByRole("option", { name: "Customer monitor" })).toBeInTheDocument());
    expect(screen.queryByRole("option", { name: "Primary monitor" })).toBeNull();
    fireEvent.click(screen.getByRole("option", { name: "Customer monitor" }));
    fireEvent.click(screen.getByRole("button", { name: "Abrir pantalla cliente" }));
    await waitFor(() => expect(openCustomerDisplay).toHaveBeenCalledWith(
      expect.objectContaining({ customerDisplayScreenId: "second" }),
      expect.objectContaining({ phase: "IDLE", locale: "es", identity: expect.objectContaining({ storeName: "Tienda Principal" }) }),
    ));
  });

  it("preserves unsaved connection and idle values when switching the main display type", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new Error("offline"); }));
    const saveHardwareConfig = vi.fn(async () => ({ ok: true as const }));
    const openCustomerDisplay = vi.fn(async () => ({ ok: true as const }));
    installHardware(createHardwareBridge({ saveHardwareConfig, openCustomerDisplay }));
    renderHardware();
    await act(async () => {});
    fireEvent.click(screen.getByRole("button", { name: "Visor de cliente" }));
    expect(screen.getByRole("group", { name: "Tipo de visor" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("radio", { name: "Visor físico de líneas" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Actualizar puertos" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Puerto COM" }));
    fireEvent.click(screen.getByRole("option", { name: "COM4" }));
    fireEvent.change(screen.getByRole("textbox", { name: "Texto reposo línea 1" }), { target: { value: "BIENVENIDOS" } });
    fireEvent.change(screen.getByRole("textbox", { name: "Texto reposo línea 2" }), { target: { value: "VUELVA PRONTO" } });
    fireEvent.click(screen.getByRole("button", { name: "Conexión" }));
    fireEvent.click(screen.getByRole("option", { name: "Red TCP" }));
    fireEvent.change(screen.getByRole("textbox", { name: "Dirección de red" }), { target: { value: "192.0.2.30" } });
    fireEvent.click(screen.getByRole("radio", { name: "Pantalla digital" }));
    expect(screen.getByRole("button", { name: "Pantalla destino" })).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Dirección de red" })).toBeNull();
    expect(screen.getByRole("textbox", { name: "Texto reposo línea 1" })).toHaveValue("BIENVENIDOS");
    expect(screen.getByRole("textbox", { name: "Texto reposo línea 2" })).toHaveValue("VUELVA PRONTO");
    fireEvent.click(screen.getByRole("radio", { name: "Visor físico de líneas" }));
    expect(screen.getByRole("textbox", { name: "Dirección de red" })).toHaveValue("192.0.2.30");
    fireEvent.click(screen.getByRole("button", { name: "Conexión" }));
    fireEvent.click(screen.getByRole("option", { name: "Puerto COM (incluye USB con COM virtual)" }));
    expect(screen.getByRole("button", { name: "Puerto COM" })).toHaveTextContent("COM4");
    expect(saveHardwareConfig).not.toHaveBeenCalled();
    expect(openCustomerDisplay).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Enviar reposo" }));
    await waitFor(() => expect(openCustomerDisplay).toHaveBeenCalledWith(
      expect.objectContaining({ customerDisplayMode: "LINE_DISPLAY", customerDisplayDevicePath: "COM4" }),
      expect.objectContaining({ phase: "IDLE", line1: "BIENVENIDOS", line2: "VUELVA PRONTO" }),
    ));
    expect(saveHardwareConfig).not.toHaveBeenCalled();
  });
});
