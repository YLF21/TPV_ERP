import { useEffect, useMemo, useRef, useState } from "react";
import { hasPermission } from "../auth/auth";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import {
  createA4TestDocument,
  createCustomerDisplayIdleState,
  createCustomerDisplayPaymentState,
  createCustomerDisplaySaleState,
  createTestTicket,
  defaultHardwareConfig,
  getHardwareBridge,
} from "../hardware/hardware";
import type {
  CashDrawerPaymentMethod,
  CustomerDisplayScreen,
  DocumentPrintRoute,
  HardwareBridge,
  HardwareConfig,
  HardwarePrinter,
} from "../hardware/hardware";
import {
  defaultScannerTimingConfig,
  idleScannerTimingCapture,
  scannerTimingKeyDecision,
} from "../hardware/scannerTimingDetection";
import { ErpSelect, type ErpSelectOption } from "./ErpSelect";
import { SaleSettingsShell, type SaleSettingsDestination } from "./SaleSettingsShell";
import { OperationalStatusCard } from "./OperationalStatusCard";
import { SystemCompatibilityCard } from "./SystemCompatibilityCard";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { visibleTableColumns } from "./tableLayoutPreferences";
import type { TableColumnDefinition } from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";

type HardwareDiagnosticKey = "electron" | "printers" | "ticket" | "a4" | "drawer" | "customerDisplay";
type HardwareSettingsMode = "devices" | "printing" | "diagnostics";
type HardwareDeviceTab = "printerDrawer" | "scannerConnection" | "customerDisplay";
type HardwareRouteColumnKey = "document" | "target" | "printer" | "paper" | "orientation" | "copies" | "auto" | "dialog";

type HardwareRouteColumnDefinition = TableColumnDefinition<HardwareRouteColumnKey> & {
  labelKey: string;
};

export const hardwareRouteColumnDefinitions = [
  { key: "document", labelKey: "hardware.route.document", defaultWidth: 120 },
  { key: "target", labelKey: "hardware.route.target", defaultWidth: 150 },
  { key: "printer", labelKey: "hardware.route.printer", defaultWidth: 190 },
  { key: "paper", labelKey: "hardware.route.paper", defaultWidth: 96 },
  { key: "orientation", labelKey: "hardware.route.orientation", defaultWidth: 120 },
  { key: "copies", labelKey: "hardware.route.copies", defaultWidth: 72 },
  { key: "auto", labelKey: "hardware.route.auto", defaultWidth: 100 },
  { key: "dialog", labelKey: "hardware.route.dialog", defaultWidth: 108 },
] satisfies readonly HardwareRouteColumnDefinition[];

type HardwareDiagnosticResult = {
  ok: boolean;
  message: string;
  checkedAt: string;
};

const cashDrawerPaymentMethods: CashDrawerPaymentMethod[] = [
  "EFECTIVO",
  "TARJETA",
  "TRANSFERENCIA",
  "VALE",
  "DESCUENTO",
  "OTRO",
  "PENDIENTE",
];

type HardwareSettingsScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  onBack: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
  mode?: HardwareSettingsMode;
  documentRoutingOnly?: boolean;
  onNavigateSettings?: (destination: SaleSettingsDestination) => void;
  onOpenProductLabels?: () => void;
};

export function HardwareSettingsScreen({
  app,
  locale,
  session,
  terminalContext,
  onBack,
  onLocaleChange,
  onLogout,
  mode = "devices",
  documentRoutingOnly = false,
  onNavigateSettings,
  onOpenProductLabels,
}: HardwareSettingsScreenProps) {
  const t = createTranslator(locale);
  const effectiveMode: HardwareSettingsMode = documentRoutingOnly ? "printing" : mode;
  const canConfigureTerminal = hasPermission(session, "CONFIGURACION_TERMINAL");
  const desktopHardwareAvailable = typeof window !== "undefined" && Boolean(window.tpvDesktop?.hardware);
  const hardware = useMemo<HardwareBridge | null>(
    () => canConfigureTerminal ? getHardwareBridge() : null,
    [canConfigureTerminal],
  );
  const routeTableLayout = useTableLayoutPreference({
    app,
    username: session.username,
    accessToken: session.accessToken,
    tableKey: "hardware.printRoutes",
    definitions: hardwareRouteColumnDefinitions,
  });
  const visibleRouteColumns = visibleTableColumns(routeTableLayout.layout);
  const routeTableWidth = visibleRouteColumns.reduce((sum, column) => sum + column.width, 0)
    + Math.max(0, visibleRouteColumns.length - 1) * 10;
  const routeGridStyle = {
    gridTemplateColumns: visibleRouteColumns
      .map((column) => `minmax(${column.width}px, ${column.width}fr)`)
      .join(" "),
    minWidth: routeTableWidth,
  };
  const [config, setConfig] = useState<HardwareConfig>(defaultHardwareConfig);
  const [printers, setPrinters] = useState<HardwarePrinter[]>([]);
  const [customerDisplays, setCustomerDisplays] = useState<CustomerDisplayScreen[]>([]);
  const [status, setStatus] = useState(t("hardware.status.ready"));
  const [scannerValue, setScannerValue] = useState("");
  const scannerCaptureRef = useRef(idleScannerTimingCapture);
  const [lastScan, setLastScan] = useState("");
  const [deviceTab, setDeviceTab] = useState<HardwareDeviceTab>("printerDrawer");
  const [diagnostics, setDiagnostics] = useState<Partial<Record<HardwareDiagnosticKey, HardwareDiagnosticResult>>>({});

  const diagnosticItems: Array<{ key: HardwareDiagnosticKey; label: string }> = [
    { key: "electron", label: t("hardware.diagnostics.electron") },
    { key: "printers", label: t("hardware.diagnostics.printers") },
    { key: "ticket", label: t("hardware.diagnostics.ticket") },
    { key: "a4", label: t("hardware.diagnostics.a4") },
    { key: "drawer", label: t("hardware.diagnostics.drawer") },
    { key: "customerDisplay", label: t("hardware.diagnostics.customerDisplay") },
  ];

  useEffect(() => {
    if (!hardware) return;
    let active = true;
    void hardware.getHardwareConfig()
      .then((loaded) => {
        if (active) setConfig(loaded);
      })
      .catch((error: unknown) => {
        if (active) setStatus(errorMessage(error));
      });
    void refreshCustomerDisplays();
    return () => { active = false; };
    // Hardware is stable for the lifetime of the permitted session.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hardware]);

  function errorMessage(error: unknown) {
    return error instanceof Error ? error.message : t("hardware.status.failed");
  }

  function updateConfig(nextValues: Partial<HardwareConfig>) {
    setConfig((current) => ({ ...current, ...nextValues }));
  }

  function updateDiagnostic(key: HardwareDiagnosticKey, ok: boolean, message: string) {
    setDiagnostics((current) => ({
      ...current,
      [key]: { ok, message, checkedAt: new Date().toLocaleTimeString() },
    }));
  }

  async function refreshPrinters() {
    if (!hardware) return;
    try {
      const result = await hardware.listPrinters();
      if (!result.ok) {
        setStatus(result.message);
        updateDiagnostic("printers", false, result.message);
        return;
      }
      setPrinters(result.printers);
      const message = t("hardware.status.printersDetected").replace("{count}", String(result.printers.length));
      setStatus(message);
      updateDiagnostic("printers", true, message);
    } catch (error) {
      const message = errorMessage(error);
      setStatus(message);
      updateDiagnostic("printers", false, message);
    }
  }

  async function refreshCustomerDisplays() {
    if (!hardware) return;
    try {
      const result = await hardware.listCustomerDisplays();
      if (!result.ok) {
        setStatus(result.message);
        return;
      }
      setCustomerDisplays(result.displays);
      const secondary = result.displays.find((display) => !display.primary) ?? result.displays[0];
      if (secondary) {
        setConfig((current) => current.customerDisplayScreenId
          ? current
          : { ...current, customerDisplayScreenId: secondary.id });
      }
    } catch (error) {
      setStatus(errorMessage(error));
    }
  }

  async function saveConfig() {
    if (!hardware) return;
    try {
      const result = await hardware.saveHardwareConfig(config);
      setStatus(result.ok ? t("hardware.status.saved") : result.message);
    } catch (error) {
      setStatus(errorMessage(error));
    }
  }

  async function testDesktopBridge() {
    if (!hardware) return;
    try {
      if (!window.tpvDesktop?.hardware) {
        throw new Error(t("hardware.status.desktopUnavailable"));
      }
      await hardware.getHardwareConfig();
      const message = t("hardware.status.desktopAvailable");
      setStatus(message);
      updateDiagnostic("electron", true, message);
    } catch (error) {
      const message = errorMessage(error);
      setStatus(message);
      updateDiagnostic("electron", false, message);
    }
  }

  async function printTestTicket() {
    if (!hardware) return;
    try {
      const result = await hardware.printTicket(createTestTicket(terminalContext), config);
      const message = result.ok ? t("hardware.status.ticketSent") : result.message;
      setStatus(message);
      updateDiagnostic("ticket", result.ok, message);
    } catch (error) {
      const message = errorMessage(error);
      setStatus(message);
      updateDiagnostic("ticket", false, message);
    }
  }

  async function printA4TestDocument() {
    if (!hardware) return;
    try {
      const result = await hardware.printA4Document(createA4TestDocument(terminalContext), config);
      const message = result.ok ? t("hardware.status.a4Sent") : result.message;
      setStatus(message);
      updateDiagnostic("a4", result.ok, message);
    } catch (error) {
      const message = errorMessage(error);
      setStatus(message);
      updateDiagnostic("a4", false, message);
    }
  }

  async function openCashDrawer() {
    if (!hardware) return;
    try {
      const result = await hardware.openCashDrawer(config);
      const message = result.ok ? t("hardware.status.drawerOpened") : result.message;
      setStatus(message);
      updateDiagnostic("drawer", result.ok, message);
    } catch (error) {
      const message = errorMessage(error);
      setStatus(message);
      updateDiagnostic("drawer", false, message);
    }
  }

  async function testScanner(code: string) {
    if (!hardware || !code.trim()) return;
    try {
      const result = await hardware.testScannerInput(code.trim());
      if (result.ok) {
        setLastScan(`${result.code} · ${new Date(result.readAt).toLocaleTimeString()}`);
        setScannerValue("");
        scannerCaptureRef.current = idleScannerTimingCapture;
        setStatus(t("hardware.status.scannerTimingVerified"));
        return;
      }
      setStatus(result.message);
    } catch (error) {
      setStatus(errorMessage(error));
    }
  }

  async function openCustomerDisplay() {
    if (!hardware) return;
    try {
      const idleState = createCustomerDisplayIdleState(config.customerDisplayIdleLine1, config.customerDisplayIdleLine2);
      const result = await hardware.openCustomerDisplay(config, idleState);
      const message = result.ok ? t("hardware.status.customerDisplayOpened") : result.message;
      setStatus(message);
      updateDiagnostic("customerDisplay", result.ok, message);
    } catch (error) {
      const message = errorMessage(error);
      setStatus(message);
      updateDiagnostic("customerDisplay", false, message);
    }
  }

  async function closeCustomerDisplay() {
    if (!hardware) return;
    try {
      const result = await hardware.closeCustomerDisplay();
      const message = result.ok ? t("hardware.status.customerDisplayClosed") : result.message;
      setStatus(message);
      updateDiagnostic("customerDisplay", result.ok, message);
    } catch (error) {
      const message = errorMessage(error);
      setStatus(message);
      updateDiagnostic("customerDisplay", false, message);
    }
  }

  async function updateCustomerDisplay(state: ReturnType<typeof createCustomerDisplayIdleState>) {
    if (!hardware) return;
    try {
      const result = await hardware.updateCustomerDisplay(state);
      setStatus(result.ok ? t("hardware.status.customerDisplaySent") : result.message);
    } catch (error) {
      setStatus(errorMessage(error));
    }
  }

  async function runDiagnostic(key: HardwareDiagnosticKey) {
    if (key === "electron") await testDesktopBridge();
    if (key === "printers") await refreshPrinters();
    if (key === "ticket") await printTestTicket();
    if (key === "a4") await printA4TestDocument();
    if (key === "drawer") await openCashDrawer();
    if (key === "customerDisplay") await openCustomerDisplay();
  }

  async function runAllDiagnostics() {
    if (!window.confirm(t("hardware.diagnostics.confirmRunAll"))) return;
    for (const item of diagnosticItems) {
      await runDiagnostic(item.key);
    }
  }

  function updateDocumentRoute(documentType: DocumentPrintRoute["documentType"], values: Partial<DocumentPrintRoute>) {
    updateConfig({
      documentPrintRoutes: config.documentPrintRoutes.map((route) =>
        route.documentType === documentType ? { ...route, ...values } : route),
    });
  }

  function renderDocumentRouteCell(route: DocumentPrintRoute, columnKey: HardwareRouteColumnKey) {
    if (columnKey === "document") return <strong key={columnKey}>{t(`hardware.document.${route.documentType}`)}</strong>;
    if (columnKey === "target") {
      return <ErpSelect key={columnKey} aria-label={t("hardware.route.target")} value={route.printerTarget}
        onChange={(value) => updateDocumentRoute(route.documentType, {
          printerTarget: value as DocumentPrintRoute["printerTarget"],
          paperSize: value === "A4_PRINTER" ? "A4" : "TICKET_80",
        })}
        options={[
          { value: "TICKET_PRINTER", label: t("hardware.route.ticketPrinter") },
          { value: "A4_PRINTER", label: t("hardware.route.a4Printer") },
        ] satisfies readonly ErpSelectOption[]} />;
    }
    if (columnKey === "printer") {
      return <ErpSelect key={columnKey} aria-label={t("hardware.route.printer")} value={route.printerName}
        onChange={(value) => updateDocumentRoute(route.documentType, { printerName: value })}
        options={[
          { value: "", label: t("hardware.route.useDefault") },
          ...printers.map((printer) => ({ value: printer.name, label: printer.displayName })),
        ] satisfies readonly ErpSelectOption[]} />;
    }
    if (columnKey === "paper") {
      return <ErpSelect key={columnKey} aria-label={t("hardware.route.paper")} value={route.paperSize}
        onChange={(value) => updateDocumentRoute(route.documentType, { paperSize: value as DocumentPrintRoute["paperSize"] })}
        options={[
          { value: "TICKET_80", label: "Ticket 80" },
          { value: "A4", label: "A4" },
        ] satisfies readonly ErpSelectOption[]} />;
    }
    if (columnKey === "orientation") {
      return <ErpSelect key={columnKey} aria-label={t("hardware.route.orientation")} value={route.orientation}
        onChange={(value) => updateDocumentRoute(route.documentType, { orientation: value as DocumentPrintRoute["orientation"] })}
        options={[
          { value: "PORTRAIT", label: t("hardware.route.portrait") },
          { value: "LANDSCAPE", label: t("hardware.route.landscape") },
        ] satisfies readonly ErpSelectOption[]} />;
    }
    if (columnKey === "copies") {
      return <input key={columnKey} aria-label={t("hardware.route.copies")} type="number" min={1} max={9}
        value={route.copies}
        onChange={(event) => updateDocumentRoute(route.documentType, { copies: Math.max(1, Number(event.target.value) || 1) })} />;
    }
    if (columnKey === "auto") {
      return <label className="hardware-route-check" key={columnKey}>
        <input type="checkbox" checked={route.documentType === "TICKET" || route.printAutomatically}
          disabled={route.documentType === "TICKET"}
          onChange={(event) => updateDocumentRoute(route.documentType, { printAutomatically: event.target.checked })} />
        <span>{t("hardware.route.autoShort")}</span>
      </label>;
    }
    return <label className="hardware-route-check" key={columnKey}>
      <input type="checkbox" checked={route.showPrintDialog}
        onChange={(event) => updateDocumentRoute(route.documentType, { showPrintDialog: event.target.checked })} />
      <span>{t("hardware.route.dialogShort")}</span>
    </label>;
  }

  function toggleCashDrawerPaymentMethod(method: CashDrawerPaymentMethod, enabled: boolean) {
    const current = new Set(config.cashDrawerOpeningPaymentMethods);
    if (enabled) current.add(method);
    else current.delete(method);
    updateConfig({ cashDrawerOpeningPaymentMethods: Array.from(current) });
  }

  function handleNavigate(destination: SaleSettingsDestination) {
    onNavigateSettings?.(destination);
  }

  const shellProps = {
    app,
    locale,
    session,
    terminalContext,
    active: effectiveMode as SaleSettingsDestination,
    onNavigate: handleNavigate,
    onBack,
    onLocaleChange,
    onLogout,
    heading: t(effectiveMode === "devices"
      ? "hardware.devices.title"
      : effectiveMode === "printing"
        ? "hardware.printing.title"
        : "hardware.diagnostics.title"),
    subtitle: t(effectiveMode === "devices"
      ? "hardware.devices.subtitle"
      : effectiveMode === "printing"
        ? "hardware.printing.subtitle"
        : "hardware.diagnostics.subtitle").replace("{terminal}", terminalContext.terminalCode),
    scopeLabel: t(effectiveMode === "diagnostics" ? "hardware.diagnostics.scope" : "hardware.devices.scope"),
  };

  if (!canConfigureTerminal) {
    return <SaleSettingsShell {...shellProps}>
      <section className="hardware-permission-denied" role="alert">
        <strong>{t("login.noAccess")}</strong>
        <span>{t("settings.documentPrinting.permission")}</span>
      </section>
    </SaleSettingsShell>;
  }

  return <SaleSettingsShell {...shellProps}>
    <section className={`hardware-settings-content hardware-settings-content-${effectiveMode}`}>
      <div className="hardware-status" aria-live="polite">{status}</div>

      {effectiveMode === "devices" && <>
        <nav className="hardware-device-tabs" aria-label={t("hardware.devices.title")}>
          {([
            ["printerDrawer", "hardware.devices.tab.printerDrawer"],
            ["scannerConnection", "hardware.devices.tab.scannerConnection"],
            ["customerDisplay", "hardware.devices.tab.customerDisplay"],
          ] as const).map(([key, labelKey]) => <button type="button" key={key}
            className={deviceTab === key ? "selected" : ""}
            aria-pressed={deviceTab === key}
            onClick={() => setDeviceTab(key)}>{t(labelKey)}</button>)}
        </nav>

        {deviceTab === "printerDrawer" && <div className="hardware-combined-sections">
          <section className="hardware-section">
            <h2>{t("hardware.printer")}</h2>
            <div className="hardware-escpos-grid">
              <label><span>{t("hardware.printerMode")}</span>
                <ErpSelect aria-label={t("hardware.printerMode")} value={config.ticketPrinterDriver}
                  onChange={(value) => updateConfig({ ticketPrinterDriver: value as HardwareConfig["ticketPrinterDriver"] })}
                  options={[
                    { value: "WINDOWS_DRIVER", label: t("hardware.mode.windows") },
                    { value: "ESCPOS_RAW", label: t("hardware.mode.escpos") },
                  ] satisfies readonly ErpSelectOption[]} />
              </label>
              <label><span>{t("hardware.windowsPrinter")}</span>
                <ErpSelect aria-label={t("hardware.windowsPrinter")} value={config.ticketPrinterName}
                  onChange={(value) => updateConfig({ ticketPrinterName: value })}
                  options={[
                    { value: "", label: t("hardware.selectPrinter") },
                    ...printers.map((printer) => ({
                      value: printer.name,
                      label: `${printer.displayName}${printer.isDefault ? ` · ${t("hardware.defaultPrinter")}` : ""}`,
                    })),
                  ] satisfies readonly ErpSelectOption[]} />
              </label>
            </div>
            <div className="hardware-inline-actions">
              <button
                type="button"
                onClick={refreshPrinters}
                disabled={!desktopHardwareAvailable}
                title={!desktopHardwareAvailable ? t("hardware.desktopActionsHelp") : undefined}
              >
                {t("hardware.detectPrinters")}
              </button>
              <button
                type="button"
                onClick={printTestTicket}
                disabled={!desktopHardwareAvailable}
                title={!desktopHardwareAvailable ? t("hardware.desktopActionsHelp") : undefined}
              >
                {t("hardware.printTest")}
              </button>
            </div>
            {!desktopHardwareAvailable ? (
              <p className="hardware-desktop-note">{t("hardware.desktopActionsHelp")}</p>
            ) : null}
          </section>

          <section className="hardware-section">
            <h2>{t("hardware.cashDrawer")}</h2>
            <label className="hardware-control-field"><span>{t("hardware.connectionType")}</span>
              <ErpSelect aria-label={t("hardware.connectionType")} value={config.cashDrawerConnection}
                onChange={(value) => updateConfig({ cashDrawerConnection: value as HardwareConfig["cashDrawerConnection"] })}
                options={[
                  { value: "NONE", label: t("hardware.drawer.none") },
                  { value: "PRINTER", label: t("hardware.drawer.printer") },
                  { value: "SERIAL", label: "COM" },
                  { value: "NETWORK", label: "LAN" },
                ] satisfies readonly ErpSelectOption[]} />
            </label>
            {config.cashDrawerConnection === "PRINTER" && <div className="hardware-device-summary">
              <span>{t("hardware.windowsPrinter")}</span>
              <strong>{config.ticketPrinterName || t("hardware.selectPrinter")}</strong>
            </div>}
            {config.cashDrawerConnection === "SERIAL" && <div className="hardware-escpos-grid">
              <label><span>{t("hardware.devicePath")}</span>
                <input value={config.cashDrawerDevicePath}
                  onChange={(event) => updateConfig({ cashDrawerDevicePath: event.target.value })} placeholder="COM3" />
              </label>
              <label><span>{t("hardware.serialBaudRate")}</span>
                <input type="number" value={config.cashDrawerSerialBaudRate}
                  onChange={(event) => updateConfig({ cashDrawerSerialBaudRate: Number(event.target.value) || 9600 })} />
              </label>
            </div>}
            {config.cashDrawerConnection === "NETWORK" && <div className="hardware-escpos-grid">
              <label><span>{t("hardware.host")}</span>
                <input value={config.cashDrawerHost} onChange={(event) => updateConfig({ cashDrawerHost: event.target.value })} />
              </label>
              <label><span>{t("hardware.port")}</span>
                <input type="number" value={config.cashDrawerPort}
                  onChange={(event) => updateConfig({ cashDrawerPort: Number(event.target.value) || 9100 })} />
              </label>
            </div>}
            <label className="hardware-check">
              <input type="checkbox" checked={config.openCashDrawerWithTicket}
                onChange={(event) => updateConfig({ openCashDrawerWithTicket: event.target.checked })} />
              <span>{t("hardware.openDrawerWithTicket")}</span>
            </label>
            <div className="hardware-payment-methods">
              <strong>{t("hardware.drawer.paymentMethods")}</strong>
              <div>{cashDrawerPaymentMethods.map((method) => <label className="hardware-check" key={method}>
                <input type="checkbox" checked={config.cashDrawerOpeningPaymentMethods.includes(method)}
                  onChange={(event) => toggleCashDrawerPaymentMethod(method, event.target.checked)} />
                <span>{t(method)}</span>
              </label>)}</div>
            </div>
            <label className="hardware-control-field"><span>{t("hardware.drawerProfile")}</span>
              <ErpSelect aria-label={t("hardware.drawerProfile")} value={config.cashDrawerCommandProfile}
                onChange={(value) => updateConfig({ cashDrawerCommandProfile: value as HardwareConfig["cashDrawerCommandProfile"] })}
                options={[{ value: "ESCPOS_STANDARD", label: "ESC/POS standard" }]} />
            </label>
            <div className="hardware-inline-actions">
              <button type="button" onClick={openCashDrawer}>{t("hardware.openDrawer")}</button>
            </div>
          </section>
        </div>}

        {deviceTab === "scannerConnection" && <div className="hardware-combined-sections">
          <section className="hardware-section">
            <h2>{t("hardware.scanner")}</h2>
            <label><span>{t("hardware.scannerMode")}</span>
              <ErpSelect aria-label={t("hardware.scannerMode")} value={config.scannerMode}
                onChange={() => updateConfig({ scannerMode: "KEYBOARD" })}
                options={[{ value: "KEYBOARD", label: t("hardware.mode.keyboard") }]} />
            </label>
            <p className="hardware-device-summary">{t("hardware.scannerTimingHelp")}</p>
            <label><span>{t("hardware.scannerTest")}</span>
              <input autoFocus value={scannerValue} onChange={(event) => setScannerValue(event.target.value)}
                onKeyDown={(event) => {
                  const decision = scannerTimingKeyDecision(scannerCaptureRef.current, event.key,
                    defaultScannerTimingConfig, event.timeStamp, scannerValue);
                  scannerCaptureRef.current = decision.next;
                  if (event.key !== "Enter") return;
                  event.preventDefault();
                  if (!decision.detected) {
                    setScannerValue("");
                    setStatus(t("hardware.status.scannerTimingNotDetected"));
                    return;
                  }
                  void testScanner(decision.completedCode ?? scannerValue);
                }} placeholder={t("hardware.scannerPlaceholder")} />
            </label>
            <div className="hardware-last-scan"><span>{t("hardware.lastScan")}</span><strong>{lastScan || "-"}</strong></div>
          </section>

          <section className="hardware-section hardware-section-wide">
            <h2>ESC/POS</h2>
            <div className="hardware-escpos-grid">
              <label><span>{t("hardware.connectionType")}</span>
                <ErpSelect aria-label={`${t("hardware.connectionType")} ESC/POS`} value={config.ticketPrinterConnection}
                  onChange={(value) => updateConfig({ ticketPrinterConnection: value as HardwareConfig["ticketPrinterConnection"] })}
                  options={[
                    { value: "WINDOWS_PRINTER", label: "USB / Windows RAW" },
                    { value: "SERIAL", label: "COM" },
                    { value: "NETWORK", label: "LAN" },
                  ] satisfies readonly ErpSelectOption[]} />
              </label>
              {config.ticketPrinterConnection === "WINDOWS_PRINTER" && <label><span>{t("hardware.windowsPrinter")}</span>
                <ErpSelect aria-label={`${t("hardware.windowsPrinter")} ESC/POS`} value={config.ticketPrinterName}
                  onChange={(value) => updateConfig({ ticketPrinterName: value })}
                  options={[
                    { value: "", label: t("hardware.selectPrinter") },
                    ...printers.map((printer) => ({ value: printer.name, label: printer.displayName })),
                  ]} />
              </label>}
              {config.ticketPrinterConnection === "SERIAL" && <>
                <label><span>{t("hardware.devicePath")}</span>
                  <input value={config.escposDevicePath} onChange={(event) => updateConfig({ escposDevicePath: event.target.value })} />
                </label>
                <label><span>{t("hardware.serialBaudRate")}</span>
                  <input type="number" value={config.escposSerialBaudRate}
                    onChange={(event) => updateConfig({ escposSerialBaudRate: Number(event.target.value) || 9600 })} />
                </label>
              </>}
              {config.ticketPrinterConnection === "NETWORK" && <>
                <label><span>{t("hardware.host")}</span>
                  <input value={config.escposHost} onChange={(event) => updateConfig({ escposHost: event.target.value })} />
                </label>
                <label><span>{t("hardware.port")}</span>
                  <input type="number" value={config.escposPort}
                    onChange={(event) => updateConfig({ escposPort: Number(event.target.value) || 9100 })} />
                </label>
              </>}
            </div>
          </section>
        </div>}

        {deviceTab === "customerDisplay" && <section className="hardware-section hardware-section-wide">
          <h2>{t("hardware.customerDisplay")}</h2>
          <div className="hardware-display-grid">
            <label className="hardware-check">
              <input type="checkbox" checked={config.customerDisplayEnabled}
                onChange={(event) => updateConfig({ customerDisplayEnabled: event.target.checked })} />
              <span>{t("hardware.customerDisplayEnabled")}</span>
            </label>
            <label><span>{t("hardware.customerDisplayScreen")}</span>
              <ErpSelect aria-label={t("hardware.customerDisplayScreen")} value={config.customerDisplayScreenId}
                onChange={(value) => updateConfig({ customerDisplayScreenId: value })}
                options={[
                  { value: "", label: t("hardware.customerDisplayAutoScreen") },
                  ...customerDisplays.map((display) => ({
                    value: display.id,
                    label: `${display.label}${display.primary ? ` · ${t("hardware.primaryScreen")}` : ""}`,
                  })),
                ]} />
            </label>
            <label><span>{t("hardware.customerDisplayIdleLine1")}</span>
              <input value={config.customerDisplayIdleLine1}
                onChange={(event) => updateConfig({ customerDisplayIdleLine1: event.target.value })} />
            </label>
            <label><span>{t("hardware.customerDisplayIdleLine2")}</span>
              <input value={config.customerDisplayIdleLine2}
                onChange={(event) => updateConfig({ customerDisplayIdleLine2: event.target.value })} />
            </label>
          </div>
          <div className="hardware-inline-actions">
            <button type="button" onClick={refreshCustomerDisplays}>{t("hardware.detectScreens")}</button>
            <button type="button" onClick={openCustomerDisplay}>{t("hardware.openCustomerDisplay")}</button>
            <button type="button" onClick={closeCustomerDisplay}>{t("hardware.closeCustomerDisplay")}</button>
          </div>
          <div className="hardware-inline-actions">
            <button type="button" onClick={() => updateCustomerDisplay(
              createCustomerDisplayIdleState(config.customerDisplayIdleLine1, config.customerDisplayIdleLine2))}>
              {t("hardware.sendIdleDisplay")}
            </button>
            <button type="button" onClick={() => updateCustomerDisplay(
              createCustomerDisplaySaleState({ name: "TEST HARDWARE", quantity: 1, price: 1 }))}>
              {t("hardware.sendSaleDisplay")}
            </button>
            <button type="button" onClick={() => updateCustomerDisplay(
              createCustomerDisplayPaymentState({ total: 12.5, change: 2.5 }))}>
              {t("hardware.sendPaymentDisplay")}
            </button>
          </div>
        </section>}

        <div className="hardware-settings-actions">
          <button type="button" className="hardware-save-button" onClick={saveConfig}>{t("hardware.save")}</button>
        </div>
      </>}

      {effectiveMode === "printing" && <>
        <section className="hardware-section hardware-section-wide">
          <h2>{t("hardware.a4Printer")}</h2>
          <div className="hardware-a4-grid">
            <label><span>{t("hardware.a4PrinterName")}</span>
              <ErpSelect aria-label={t("hardware.a4PrinterName")} value={config.a4PrinterName}
                onChange={(value) => updateConfig({ a4PrinterName: value })}
                options={[
                  { value: "", label: t("hardware.selectPrinter") },
                  ...printers.map((printer) => ({
                    value: printer.name,
                    label: `${printer.displayName}${printer.isDefault ? ` · ${t("hardware.defaultPrinter")}` : ""}`,
                  })),
                ]} />
            </label>
          </div>
          <div className="hardware-route-table">
            <div className="hardware-route-header" style={routeGridStyle}>
              {visibleRouteColumns.map((column) => {
                const definition = hardwareRouteColumnDefinitions.find((candidate) => candidate.key === column.key);
                const label = t(definition?.labelKey ?? column.key);
                return <TableLayoutHeaderCell as="span" className={`hardware-route-heading hardware-route-heading-${column.key}`}
                  column={column} key={column.key} resizeLabel={`${t("stock.columns.resize")} ${label}`}
                  onReorder={routeTableLayout.reorderColumns} onMove={routeTableLayout.moveColumn}
                  onResize={routeTableLayout.resizeColumn}>{label}</TableLayoutHeaderCell>;
              })}
            </div>
            {config.documentPrintRoutes.map((route) => <div className="hardware-route-row" key={route.documentType} style={routeGridStyle}>
              {visibleRouteColumns.map((column) => <div className={`hardware-route-cell hardware-route-cell-${column.key}`} key={column.key}>
                {renderDocumentRouteCell(route, column.key)}
              </div>)}
            </div>)}
          </div>
          <div className="hardware-inline-actions">
            <button type="button" onClick={refreshPrinters}>{t("hardware.detectPrinters")}</button>
            <button type="button" onClick={printA4TestDocument}>{t("hardware.printA4Test")}</button>
          </div>
        </section>

        {onOpenProductLabels && <section className="hardware-label-tools">
          <h2>{t("hardware.printing.labelsTitle")}</h2>
          <p>{t("hardware.printing.labelsDescription")}</p>
          <button type="button" onClick={onOpenProductLabels}>{t("hardware.printing.openLabels")}</button>
        </section>}

        <div className="hardware-settings-actions">
          <button type="button" className="hardware-save-button" onClick={saveConfig}>{t("hardware.save")}</button>
        </div>
      </>}

      {effectiveMode === "diagnostics" && <>
        <section className="hardware-diagnostic-list">
          {diagnosticItems.map((item) => {
            const result = diagnostics[item.key];
            return <article className="hardware-diagnostic-row" key={item.key}>
              <div><strong>{item.label}</strong><span>{result?.message || t("hardware.diagnostics.pending")}</span></div>
              <span className={`hardware-diagnostic-status ${result?.ok ? "ok" : result ? "error" : "pending"}`}>
                {result ? (result.ok ? "OK" : "ERROR") : t("hardware.diagnostics.notChecked")}
              </span>
              <button type="button" onClick={() => void runDiagnostic(item.key)}>{t("hardware.diagnostics.test")}</button>
            </article>;
          })}
        </section>
        <div className="hardware-settings-actions">
          <button type="button" className="hardware-save-button" onClick={() => void runAllDiagnostics()}>
            {t("hardware.diagnostics.runAll")}
          </button>
        </div>
        <div className="sale-settings-diagnostic-services">
          <SystemCompatibilityCard locale={locale} token={session.accessToken} />
          <OperationalStatusCard locale={locale} token={session.accessToken} />
        </div>
      </>}
    </section>
  </SaleSettingsShell>;
}
