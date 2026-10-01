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
  normalizeHardwareConfigForUi,
} from "../hardware/hardware";
import type {
  CashDrawerPaymentMethod,
  CustomerDisplayScreen,
  DocumentPrintRoute,
  HardwareBridge,
  HardwareConfig,
  HardwarePrinter,
  ProductLabelProfile,
} from "../hardware/hardware";
import {
  defaultScannerTimingConfig,
  idleScannerTimingCapture,
  scannerTimingKeyDecision,
} from "../hardware/scannerTimingDetection";
import { ErpSelect, type ErpSelectOption } from "./ErpSelect";
import { PaymentTerminalSettings } from "./PaymentTerminalSettings";
import { productLabelMinimumSize } from "./productLabelLayout";
import { SaleSettingsShell, type SaleSettingsDestination } from "./SaleSettingsShell";
import { OperationalStatusCard } from "./OperationalStatusCard";
import { SystemCompatibilityCard } from "./SystemCompatibilityCard";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { visibleTableColumns } from "./tableLayoutPreferences";
import type { TableColumnDefinition } from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";
import { useSettingsNavigationGuard, type SettingsSaveResult } from "./useSettingsNavigationGuard";
import "./ErpClassicTables.css";

type HardwareDiagnosticKey = "electron" | "printers" | "ticket" | "a4" | "drawer" | "customerDisplay";
type HardwareSettingsMode = "devices" | "printers" | "printing" | "diagnostics";
type HardwareDeviceTab = "drawer" | "scanner" | "customerDisplay" | "paymentTerminal";
type HardwarePrinterTab = "tickets" | "a4" | "routes" | "labels";
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
  const effectiveMode = documentRoutingOnly || mode === "printers" ? "printing" : mode;
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
  const routeTableWidth = visibleRouteColumns.reduce((sum, column) => sum + column.width, 0);
  const routeGridStyle = {
    gridTemplateColumns: visibleRouteColumns
      .map((column) => `minmax(${column.width}px, ${column.width}fr)`)
      .join(" "),
    minWidth: routeTableWidth,
  };
  const [config, setConfig] = useState<HardwareConfig>(defaultHardwareConfig);
  const [printers, setPrinters] = useState<HardwarePrinter[]>([]);
  const [customerDisplays, setCustomerDisplays] = useState<CustomerDisplayScreen[]>([]);
  const [status, setStatus] = useState("");
  const [scannerValue, setScannerValue] = useState("");
  const scannerCaptureRef = useRef(idleScannerTimingCapture);
  const [lastScan, setLastScan] = useState("");
  const [deviceTab, setDeviceTab] = useState<HardwareDeviceTab>("drawer");
  const [printerTab, setPrinterTab] = useState<HardwarePrinterTab>("tickets");
  const [profileId, setProfileId] = useState(defaultHardwareConfig.defaultProductLabelProfileId);
  const [savedConfig, setSavedConfig] = useState(JSON.stringify(defaultHardwareConfig));
  const [savingConfig, setSavingConfig] = useState(false);
  const dirty = JSON.stringify(config) !== savedConfig;
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
        if (active) {
          const normalized = normalizeHardwareConfigForUi(loaded);
          setConfig(normalized);
          setSavedConfig(JSON.stringify(normalized));
          setProfileId(normalized.defaultProductLabelProfileId);
        }
      })
      .catch((error: unknown) => {
        if (active) setStatus(errorMessage(error));
      });
    void refreshCustomerDisplays();
    return () => { active = false; };
    // Hardware is stable for the lifetime of the permitted session.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hardware]);

  useEffect(() => {
    if (!dirty) return;
    const preventUnload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
    window.addEventListener("beforeunload", preventUnload);
    return () => window.removeEventListener("beforeunload", preventUnload);
  }, [dirty]);

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
    } catch (error) {
      setStatus(errorMessage(error));
    }
  }

  async function saveConfig(): Promise<SettingsSaveResult> {
    if (savingConfig) return { ok: false, error: t("settings.unsavedChanges.saveError") };
    if (!hardware) return { ok: false, error: t("hardware.status.desktopUnavailable") };
    setSavingConfig(true);
    try {
      const result = await hardware.saveHardwareConfig(config);
      setStatus(result.ok ? t("hardware.status.saved") : result.message);
      if (result.ok) {
        setSavedConfig(JSON.stringify(config));
        return { ok: true };
      }
      return { ok: false, error: result.message };
    } catch (error) {
      const message = errorMessage(error);
      setStatus(message);
      return { ok: false, error: message };
    } finally {
      setSavingConfig(false);
    }
  }

  function discardConfig() {
    const restored = JSON.parse(savedConfig) as HardwareConfig;
    setConfig(restored);
    setProfileId(restored.defaultProductLabelProfileId);
    setStatus("");
  }

  const { requestNavigation, confirmationDialog } = useSettingsNavigationGuard({
    dirty,
    saving: savingConfig,
    locale,
    save: saveConfig,
    discard: discardConfig,
  });

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
    requestNavigation(() => onNavigateSettings?.(destination));
  }

  function handleBack() {
    requestNavigation(onBack);
  }

  function updateProfile(patch: Partial<ProductLabelProfile>) {
    updateConfig({ productLabelProfiles: config.productLabelProfiles.map((profile) =>
      profile.id === profileId ? { ...profile, ...patch } : profile) });
  }

  function updateProfileNumber(key: keyof ProductLabelProfile, value: string, min: number, max: number) {
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) return;
    updateProfile({ [key]: Math.min(max, Math.max(min, parsed)) });
  }

  const profile = config.productLabelProfiles.find((item) => item.id === profileId)
    ?? config.productLabelProfiles[0];
  const minimumLabelSize = productLabelMinimumSize(profile.showStoreName);

  const shellProps = {
    app,
    locale,
    session,
    terminalContext,
    active: (effectiveMode === "printing" ? "printers" : effectiveMode) as SaleSettingsDestination,
    onNavigate: handleNavigate,
    onBack: handleBack,
    onLocaleChange: (nextLocale: LocaleCode) => requestNavigation(() => onLocaleChange(nextLocale)),
    onLogout: onLogout ? () => requestNavigation(onLogout) : undefined,
    heading: t(effectiveMode === "devices"
      ? "hardware.devices.title"
      : effectiveMode === "printing"
        ? "settings.printers"
        : "settings.diagnosticsMaintenance"),
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
      {status && <div className="hardware-status" aria-live="polite">{status}</div>}

      {effectiveMode === "devices" && <>
        <nav className="hardware-device-tabs" aria-label={t("hardware.devices.title")}>
          {([
            ["drawer", "hardware.devices.tab.drawer"],
            ["scanner", "hardware.devices.tab.scanner"],
            ["customerDisplay", "hardware.devices.tab.customerDisplay"],
            ["paymentTerminal", "hardware.devices.tab.paymentTerminal"],
          ] as const).map(([key, labelKey]) => <button type="button" key={key}
            className={deviceTab === key ? "selected" : ""}
            aria-pressed={deviceTab === key}
            onClick={() => setDeviceTab(key)}>{t(labelKey)}</button>)}
        </nav>

        {deviceTab === "drawer" && <div className="hardware-combined-sections">
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
            {config.cashDrawerConnection === "PRINTER" && <div className="hardware-device-summary hardware-printer-summary">
              <span>{t("hardware.windowsPrinter")}</span>
              <strong>{config.ticketPrinterName || t("hardware.selectPrinter")}</strong>
              <button type="button" className="sale-settings-action-button" onClick={() => handleNavigate("printers")}>{t("hardware.configurePrinter")}</button>
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
              <button type="button" onClick={openCashDrawer} disabled={!desktopHardwareAvailable}>{t("hardware.openDrawer")}</button>
            </div>
          </section>
        </div>}

        {deviceTab === "scanner" && <div className="hardware-combined-sections">
          <section className="hardware-section">
            <h2>{t("hardware.scanner")}</h2>
            <label><span>{t("hardware.scannerMode")}</span>
              <ErpSelect aria-label={t("hardware.scannerMode")} value={config.scannerMode}
                onChange={() => updateConfig({ scannerMode: "KEYBOARD" })}
                options={[{ value: "KEYBOARD", label: t("hardware.mode.keyboard") }]} />
            </label>
            <p className="hardware-device-summary">{t("hardware.scannerTimingHelp")}</p>
            <label><span>{t("hardware.scannerTest")}</span>
              <input autoFocus disabled={!desktopHardwareAvailable} value={scannerValue} onChange={(event) => setScannerValue(event.target.value)}
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
            <button type="button" onClick={refreshCustomerDisplays} disabled={!desktopHardwareAvailable}>{t("hardware.detectScreens")}</button>
            <button type="button" onClick={openCustomerDisplay} disabled={!desktopHardwareAvailable}>{t("hardware.openCustomerDisplay")}</button>
            <button type="button" onClick={closeCustomerDisplay} disabled={!desktopHardwareAvailable}>{t("hardware.closeCustomerDisplay")}</button>
          </div>
          <div className="hardware-inline-actions">
            <button type="button" disabled={!desktopHardwareAvailable} onClick={() => updateCustomerDisplay(
              createCustomerDisplayIdleState(config.customerDisplayIdleLine1, config.customerDisplayIdleLine2))}>
              {t("hardware.sendIdleDisplay")}
            </button>
            <button type="button" disabled={!desktopHardwareAvailable} onClick={() => updateCustomerDisplay(
              createCustomerDisplaySaleState({ name: "TEST HARDWARE", quantity: 1, price: 1 }))}>
              {t("hardware.sendSaleDisplay")}
            </button>
            <button type="button" disabled={!desktopHardwareAvailable} onClick={() => updateCustomerDisplay(
              createCustomerDisplayPaymentState({ total: 12.5, change: 2.5 }))}>
              {t("hardware.sendPaymentDisplay")}
            </button>
          </div>
        </section>}

        {deviceTab === "paymentTerminal" && <PaymentTerminalSettings locale={locale} token={session.accessToken} />}

        {deviceTab !== "paymentTerminal" && <div className="hardware-settings-actions">
          <button type="button" className="hardware-save-button" disabled={savingConfig} onClick={saveConfig}>{t("hardware.save")}</button>
        </div>}
      </>}

      {effectiveMode === "printing" && <>
        <nav className="hardware-device-tabs" aria-label={t("hardware.printing.title")}>
          {([
            ["tickets", "hardware.printers.tab.tickets"],
            ["a4", "hardware.printers.tab.a4"],
            ["routes", "hardware.printers.tab.routes"],
            ["labels", "hardware.printers.tab.labels"],
          ] as const).map(([key, labelKey]) => <button type="button" key={key}
            className={printerTab === key ? "selected" : ""}
            aria-pressed={printerTab === key}
            onClick={() => setPrinterTab(key)}>{t(labelKey)}</button>)}
        </nav>

        {printerTab === "tickets" && <div className="hardware-combined-sections">
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
              <label><span>{t("hardware.escposAdditionalFeedLines")}</span>
                <input
                  type="number"
                  min={0}
                  max={12}
                  step={1}
                  aria-describedby="escpos-additional-feed-help"
                  value={config.escposAdditionalFeedLines}
                  onChange={(event) => updateConfig({
                    escposAdditionalFeedLines: Math.min(12, Math.max(0, Math.trunc(Number(event.target.value) || 0))),
                  })}
                />
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
            <p id="escpos-additional-feed-help" className="hardware-desktop-note">
              {t("hardware.escposAdditionalFeedLinesHelp")}
            </p>
          </section>
        </div>}

        {printerTab === "a4" && <section className="hardware-section hardware-section-wide">
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
          <div className="hardware-inline-actions">
            <button type="button" onClick={refreshPrinters} disabled={!desktopHardwareAvailable}>{t("hardware.detectPrinters")}</button>
            <button type="button" onClick={printA4TestDocument} disabled={!desktopHardwareAvailable}>{t("hardware.printA4Test")}</button>
          </div>
        </section>}

        {printerTab === "routes" && <section className="hardware-section hardware-section-wide">
          <h2>{t("hardware.printers.tab.routes")}</h2>
          <div className="hardware-route-table erp-classic-tables">
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
        </section>}

        {printerTab === "labels" && <section className="hardware-label-tools">
          <h2>{t("hardware.printing.labelsTitle")}</h2>
          <div className="hardware-escpos-grid">
            <label><span>{t("hardware.labels.profile")}</span>
              <ErpSelect aria-label={t("hardware.labels.profile")} value={profile.id}
                onChange={setProfileId}
                options={config.productLabelProfiles.map((item) => ({ value: item.id, label: item.name }))} />
            </label>
            <label><span>{t("hardware.labels.name")}</span>
              <input value={profile.name} onChange={(event) => updateProfile({ name: event.target.value })} />
            </label>
            <label><span>{t("hardware.labels.destination")}</span>
              <ErpSelect aria-label={t("hardware.labels.destination")} value={profile.destination}
                onChange={(value) => updateProfile({ destination: value as ProductLabelProfile["destination"], printerName: "" })}
                options={[
                  { value: "LABEL_PRINTER", label: t("hardware.labels.destination.labelPrinter") },
                  { value: "TICKET_PRINTER", label: t("hardware.labels.destination.ticketPrinter") },
                  { value: "A4", label: t("hardware.labels.destination.a4") },
                ]} />
            </label>
            <label><span>{t("hardware.labels.printer")}</span>
              <ErpSelect aria-label={t("hardware.labels.printer")} value={profile.printerName}
                onChange={(value) => updateProfile({ printerName: value })}
                options={[
                  { value: "", label: profile.destination === "TICKET_PRINTER"
                    ? config.ticketPrinterName || t("hardware.labels.useDefault")
                    : profile.destination === "A4" ? config.a4PrinterName || t("hardware.labels.useDefault")
                      : t("hardware.labels.useDefault") },
                  ...printers.map((printer) => ({ value: printer.name, label: printer.displayName })),
                ]} />
            </label>
            <label><span>{t("hardware.labels.width")}</span>
              <input type="number" min={minimumLabelSize.widthMm} max={210} step={1} value={profile.widthMm}
                onChange={(event) => updateProfileNumber("widthMm", event.target.value, minimumLabelSize.widthMm, 210)} />
            </label>
            <label><span>{t("hardware.labels.height")}</span>
              <input type="number" min={minimumLabelSize.heightMm} max={297} step={1} value={profile.heightMm}
                onChange={(event) => updateProfileNumber("heightMm", event.target.value, minimumLabelSize.heightMm, 297)} />
            </label>
            <label><span>{t("hardware.labels.orientation")}</span>
              <ErpSelect aria-label={t("hardware.labels.orientation")} value={profile.orientation}
                onChange={(value) => updateProfile({ orientation: value as ProductLabelProfile["orientation"] })}
                options={[
                  { value: "PORTRAIT", label: t("hardware.route.portrait") },
                  { value: "LANDSCAPE", label: t("hardware.route.landscape") },
                ]} />
            </label>
            <label><span>{t("hardware.labels.copies")}</span>
              <input type="number" min={1} max={999} step={1} value={profile.copies}
                onChange={(event) => updateProfileNumber("copies", event.target.value, 1, 999)} />
            </label>
          </div>
          <label className="hardware-check"><input type="checkbox" checked={profile.showStoreName}
            onChange={(event) => {
              const minimum = productLabelMinimumSize(event.target.checked);
              updateProfile({ showStoreName: event.target.checked,
                widthMm: Math.max(profile.widthMm, minimum.widthMm),
                heightMm: Math.max(profile.heightMm, minimum.heightMm) });
            }} /><span>{t("hardware.labels.showStoreName")}</span></label>
          <label className="hardware-check"><input type="checkbox" checked={config.defaultProductLabelProfileId === profile.id}
            onChange={() => updateConfig({ defaultProductLabelProfileId: profile.id })} />
            <span>{t("hardware.labels.defaultProfile")}</span></label>
          {profile.destination === "A4" && <>
            <fieldset><legend>{t("hardware.labels.margins")}</legend>
              <div className="hardware-escpos-grid">
                {([[
                  "marginTopMm", "hardware.labels.margins.top", 50,
                ], ["marginRightMm", "hardware.labels.margins.right", 50],
                ["marginBottomMm", "hardware.labels.margins.bottom", 50],
                ["marginLeftMm", "hardware.labels.margins.left", 50]] as const).map(([key, label, max]) =>
                  <label key={key}><span>{t(label)}</span><input type="number" min={0} max={max}
                    value={profile[key]} onChange={(event) => updateProfileNumber(key, event.target.value, 0, max)} /></label>)}
              </div>
            </fieldset>
            <fieldset><legend>{t("hardware.labels.gaps")}</legend>
              <div className="hardware-escpos-grid">
                {([[
                  "horizontalGapMm", "hardware.labels.margins.horizontalGap",
                ], ["verticalGapMm", "hardware.labels.margins.verticalGap"]] as const).map(([key, label]) =>
                  <label key={key}><span>{t(label)}</span><input type="number" min={0} max={25}
                    value={profile[key]} onChange={(event) => updateProfileNumber(key, event.target.value, 0, 25)} /></label>)}
              </div>
            </fieldset>
          </>}
          <div className="hardware-inline-actions">
            <button type="button" onClick={refreshPrinters} disabled={!desktopHardwareAvailable}>{t("hardware.detectPrinters")}</button>
            {onOpenProductLabels && <button type="button" onClick={() => requestNavigation(onOpenProductLabels)}>{t("hardware.printing.openLabels")}</button>}
          </div>
        </section>}

        <div className="hardware-settings-actions">
          <button type="button" className="hardware-save-button" disabled={savingConfig} onClick={saveConfig}>{t("hardware.save")}</button>
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
              <button type="button" disabled={!desktopHardwareAvailable} onClick={() => void runDiagnostic(item.key)}>{t("hardware.diagnostics.test")}</button>
            </article>;
          })}
        </section>
        <div className="hardware-settings-actions">
          <button type="button" className="hardware-save-button" disabled={!desktopHardwareAvailable} onClick={() => void runAllDiagnostics()}>
            {t("hardware.diagnostics.runAll")}
          </button>
        </div>
        <div className="sale-settings-diagnostic-services">
          <SystemCompatibilityCard locale={locale} token={session.accessToken} />
          <OperationalStatusCard locale={locale} token={session.accessToken} />
        </div>
      </>}
    </section>
    {confirmationDialog}
  </SaleSettingsShell>;
}
