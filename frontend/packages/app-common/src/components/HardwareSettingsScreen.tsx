import { useEffect, useMemo, useState } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";
import {
  createA4TestDocument,
  createTestTicket,
  createCustomerDisplayIdleState,
  createCustomerDisplayPaymentState,
  createCustomerDisplaySaleState,
  defaultHardwareConfig,
  getHardwareBridge
} from "../hardware/hardware";
import type {
  CashDrawerPaymentMethod,
  CustomerDisplayScreen,
  DocumentPrintRoute,
  HardwareConfig,
  HardwarePrinter
} from "../hardware/hardware";

type HardwareDiagnosticKey = "electron" | "printers" | "ticket" | "a4" | "drawer" | "customerDisplay";
type HardwareSectionKey = "printer" | "cashDrawer" | "scanner" | "escpos" | "a4" | "customerDisplay" | "diagnostics";

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
  "PENDIENTE"
];

type HardwareSettingsScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  onBack: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
};

export function HardwareSettingsScreen({
  app,
  locale,
  session,
  terminalContext,
  onBack,
  onLocaleChange,
  onLogout
}: HardwareSettingsScreenProps) {
  const t = createTranslator(locale);
  const hardware = useMemo(() => getHardwareBridge(), []);
  const [config, setConfig] = useState<HardwareConfig>(defaultHardwareConfig);
  const [printers, setPrinters] = useState<HardwarePrinter[]>([]);
  const [customerDisplays, setCustomerDisplays] = useState<CustomerDisplayScreen[]>([]);
  const [status, setStatus] = useState(t("hardware.status.ready"));
  const [scannerValue, setScannerValue] = useState("");
  const [lastScan, setLastScan] = useState("");
  const [diagnostics, setDiagnostics] = useState<Partial<Record<HardwareDiagnosticKey, HardwareDiagnosticResult>>>({});
  const [selectedSection, setSelectedSection] = useState<HardwareSectionKey>("cashDrawer");

  const diagnosticItems: Array<{ key: HardwareDiagnosticKey; label: string }> = [
    { key: "electron", label: t("hardware.diagnostics.electron") },
    { key: "printers", label: t("hardware.diagnostics.printers") },
    { key: "ticket", label: t("hardware.diagnostics.ticket") },
    { key: "a4", label: t("hardware.diagnostics.a4") },
    { key: "drawer", label: t("hardware.diagnostics.drawer") },
    { key: "customerDisplay", label: t("hardware.diagnostics.customerDisplay") }
  ];
  const hardwareSections: Array<{ key: HardwareSectionKey; label: string }> = [
    { key: "printer", label: t("hardware.printer") },
    { key: "cashDrawer", label: t("hardware.cashDrawer") },
    { key: "scanner", label: t("hardware.scanner") },
    { key: "escpos", label: "ESC/POS" },
    { key: "a4", label: t("hardware.a4Printer") },
    { key: "customerDisplay", label: t("hardware.customerDisplay") },
    { key: "diagnostics", label: t("hardware.diagnostics.title") }
  ];
  const selectedSectionLabel = hardwareSections.find((section) => section.key === selectedSection)?.label ?? t("hardware.title");

  useEffect(() => {
    void hardware.getHardwareConfig().then(setConfig);
    void refreshPrinters();
    void refreshCustomerDisplays();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function updateConfig(nextValues: Partial<HardwareConfig>) {
    setConfig((current) => ({ ...current, ...nextValues }));
  }

  function updateDiagnostic(key: HardwareDiagnosticKey, ok: boolean, message: string) {
    setDiagnostics((current) => ({
      ...current,
      [key]: {
        ok,
        message,
        checkedAt: new Date().toLocaleTimeString()
      }
    }));
  }

  async function refreshPrinters() {
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
    if (!config.ticketPrinterName) {
      const defaultPrinter = result.printers.find((printer) => printer.isDefault) ?? result.printers[0];
      if (defaultPrinter) {
        updateConfig({ ticketPrinterName: defaultPrinter.name });
      }
    }
  }

  async function refreshCustomerDisplays() {
    const result = await hardware.listCustomerDisplays();
    if (!result.ok) {
      setStatus(result.message);
      return;
    }
    setCustomerDisplays(result.displays);
    if (!config.customerDisplayScreenId) {
      const secondary = result.displays.find((display) => !display.primary) ?? result.displays[0];
      if (secondary) {
        updateConfig({ customerDisplayScreenId: secondary.id });
      }
    }
  }

  async function saveConfig() {
    const result = await hardware.saveHardwareConfig(config);
    setStatus(result.ok ? t("hardware.status.saved") : result.message);
    updateDiagnostic("electron", result.ok, result.ok ? t("hardware.status.saved") : result.message);
  }

  async function printTestTicket() {
    const result = await hardware.printTicket(createTestTicket(terminalContext), config);
    setStatus(result.ok ? t("hardware.status.ticketSent") : result.message);
    updateDiagnostic("ticket", result.ok, result.ok ? t("hardware.status.ticketSent") : result.message);
  }

  async function printA4TestDocument() {
    const result = await hardware.printA4Document(createA4TestDocument(terminalContext), config);
    setStatus(result.ok ? t("hardware.status.a4Sent") : result.message);
    updateDiagnostic("a4", result.ok, result.ok ? t("hardware.status.a4Sent") : result.message);
  }

  async function openCashDrawer() {
    const result = await hardware.openCashDrawer(config);
    setStatus(result.ok ? t("hardware.status.drawerOpened") : result.message);
    updateDiagnostic("drawer", result.ok, result.ok ? t("hardware.status.drawerOpened") : result.message);
  }

  async function testScanner(code: string) {
    if (!code.trim()) {
      return;
    }
    const result = await hardware.testScannerInput(code.trim());
    if (result.ok) {
      setLastScan(`${result.code} · ${new Date(result.readAt).toLocaleTimeString()}`);
      setScannerValue("");
      setStatus(t("hardware.status.scannerRead"));
      return;
    }
    setStatus(result.message);
  }

  async function openCustomerDisplay() {
    const state = createCustomerDisplayIdleState(config.customerDisplayIdleLine1, config.customerDisplayIdleLine2);
    const result = await hardware.openCustomerDisplay(config, state);
    setStatus(result.ok ? t("hardware.status.customerDisplayOpened") : result.message);
    updateDiagnostic("customerDisplay", result.ok, result.ok ? t("hardware.status.customerDisplayOpened") : result.message);
  }

  async function closeCustomerDisplay() {
    const result = await hardware.closeCustomerDisplay();
    setStatus(result.ok ? t("hardware.status.customerDisplayClosed") : result.message);
    updateDiagnostic("customerDisplay", result.ok, result.ok ? t("hardware.status.customerDisplayClosed") : result.message);
  }

  async function sendIdleDisplay() {
    const result = await hardware.updateCustomerDisplay(
      createCustomerDisplayIdleState(config.customerDisplayIdleLine1, config.customerDisplayIdleLine2)
    );
    setStatus(result.ok ? t("hardware.status.customerDisplaySent") : result.message);
  }

  async function sendSaleDisplay() {
    const result = await hardware.updateCustomerDisplay(
      createCustomerDisplaySaleState({ name: "TEST HARDWARE", quantity: 0, price: 0 })
    );
    setStatus(result.ok ? t("hardware.status.customerDisplaySent") : result.message);
  }

  async function sendPaymentDisplay() {
    const result = await hardware.updateCustomerDisplay(createCustomerDisplayPaymentState({ total: 12.5, change: 2.5 }));
    setStatus(result.ok ? t("hardware.status.customerDisplaySent") : result.message);
  }

  async function runAllDiagnostics() {
    await saveConfig();
    await refreshPrinters();
    await printTestTicket();
    await printA4TestDocument();
    await openCashDrawer();
    await openCustomerDisplay();
  }

  function updateDocumentRoute(documentType: DocumentPrintRoute["documentType"], values: Partial<DocumentPrintRoute>) {
    updateConfig({
      documentPrintRoutes: config.documentPrintRoutes.map((route) =>
        route.documentType === documentType ? { ...route, ...values } : route
      )
    });
  }

  function toggleCashDrawerPaymentMethod(method: CashDrawerPaymentMethod, enabled: boolean) {
    const current = new Set(config.cashDrawerOpeningPaymentMethods);
    if (enabled) {
      current.add(method);
    } else {
      current.delete(method);
    }
    updateConfig({ cashDrawerOpeningPaymentMethods: Array.from(current) as CashDrawerPaymentMethod[] });
  }

  return (
    <main className="hardware-screen">
      <SessionTopControls
        locale={locale}
        session={session}
        languageLabel={t("login.language")}
        shutdownLabel={t("login.shutdown")}
        changePasswordLabel={t("common.changePassword")}
        logoutLabel={t("common.logout")}
        shutdownConfirmTitle={t("login.shutdownConfirmTitle")}
        shutdownConfirmText={t("login.shutdownConfirmText")}
        noLabel={t("common.no")}
        yesLabel={t("common.yes")}
        onLocaleChange={onLocaleChange}
        onLogout={onLogout}
      />
      <header className="hardware-topbar">
        <button type="button" className="report-brand-back hardware-brand" onClick={onBack}>
          {t(app === "venta" ? "venta.title" : "gestion.title")}
        </button>
        <div>
          <h1>{t("hardware.title")}</h1>
          <span>{session.displayName} · {terminalContext.storeName} · {t("login.terminalPrefix")} {terminalContext.terminalCode}</span>
        </div>
      </header>

      <section className="hardware-layout">
        <aside className="hardware-nav">
          <strong>{t("settings.sections")}</strong>
          {hardwareSections.map((section) => (
            <button
              type="button"
              className={selectedSection === section.key ? "selected" : ""}
              key={section.key}
              onClick={() => setSelectedSection(section.key)}
            >
              {section.label}
            </button>
          ))}
          <button type="button" className="report-back" onClick={onBack}>
            {t("common.back")}
          </button>
        </aside>

        <section className="hardware-workspace">
          <header className="hardware-workspace-heading">
            <h2>{selectedSectionLabel}</h2>
            <span>{status}</span>
          </header>

          {selectedSection === "printer" && (
            <div className="hardware-section">
              <h2>{t("hardware.printer")}</h2>
          <label>
            <span>{t("hardware.printerMode")}</span>
            <select
              value={config.ticketPrinterDriver}
              onChange={(event) => updateConfig({ ticketPrinterDriver: event.target.value as HardwareConfig["ticketPrinterDriver"] })}
            >
              <option value="WINDOWS_DRIVER">{t("hardware.mode.windows")}</option>
              <option value="ESCPOS_RAW">{t("hardware.mode.escpos")}</option>
            </select>
          </label>
          <label>
            <span>{t("hardware.windowsPrinter")}</span>
            <select
              value={config.ticketPrinterName}
              onChange={(event) => updateConfig({ ticketPrinterName: event.target.value })}
            >
              <option value="">{t("hardware.selectPrinter")}</option>
              {printers.map((printer) => (
                <option key={printer.name} value={printer.name}>
                  {printer.displayName}{printer.isDefault ? ` · ${t("hardware.defaultPrinter")}` : ""}
                </option>
              ))}
            </select>
          </label>
          <div className="hardware-actions">
            <button type="button" onClick={refreshPrinters}>{t("hardware.detectPrinters")}</button>
            <button type="button" onClick={printTestTicket}>{t("hardware.printTest")}</button>
          </div>
            </div>
          )}

          {selectedSection === "cashDrawer" && (
            <div className="hardware-section hardware-section-focus">
              <h2>{t("hardware.cashDrawer")}</h2>
          <label>
            <span>{t("hardware.connectionType")}</span>
            <select
              value={config.cashDrawerConnection}
              onChange={(event) => updateConfig({ cashDrawerConnection: event.target.value as HardwareConfig["cashDrawerConnection"] })}
            >
              <option value="NONE">{t("hardware.drawer.none")}</option>
              <option value="PRINTER">{t("hardware.drawer.printer")}</option>
              <option value="SERIAL">COM</option>
              <option value="NETWORK">LAN</option>
            </select>
          </label>
          {config.cashDrawerConnection === "PRINTER" && (
          <div className="hardware-device-summary">
            <span>{t("hardware.windowsPrinter")}</span>
            <strong>{config.ticketPrinterName || t("hardware.selectPrinter")}</strong>
          </div>
          )}
          {config.cashDrawerConnection === "SERIAL" && (
            <label>
              <span>{t("hardware.devicePath")}</span>
              <input
                value={config.cashDrawerDevicePath}
                onChange={(event) => updateConfig({ cashDrawerDevicePath: event.target.value })}
                placeholder="COM3"
              />
            </label>
          )}
          {config.cashDrawerConnection === "NETWORK" && (
            <div className="hardware-escpos-grid">
              <label>
                <span>{t("hardware.host")}</span>
                <input value={config.cashDrawerHost} onChange={(event) => updateConfig({ cashDrawerHost: event.target.value })} />
              </label>
              <label>
                <span>{t("hardware.port")}</span>
                <input
                  type="number"
                  value={config.cashDrawerPort}
                  onChange={(event) => updateConfig({ cashDrawerPort: Number(event.target.value) || 9100 })}
                />
              </label>
            </div>
          )}
          <label className="hardware-check">
            <input
              type="checkbox"
              checked={config.openCashDrawerWithTicket}
              onChange={(event) => updateConfig({ openCashDrawerWithTicket: event.target.checked })}
            />
            <span>{t("hardware.openDrawerWithTicket")}</span>
          </label>
          <div className="hardware-payment-methods">
            <strong>{t("hardware.drawer.paymentMethods")}</strong>
            <div>
              {cashDrawerPaymentMethods.map((method) => (
                <label className="hardware-check" key={method}>
                  <input
                    type="checkbox"
                    checked={config.cashDrawerOpeningPaymentMethods.includes(method)}
                    onChange={(event) => toggleCashDrawerPaymentMethod(method, event.target.checked)}
                  />
                  <span>{t(method)}</span>
                </label>
              ))}
            </div>
          </div>
          <label>
            <span>{t("hardware.drawerProfile")}</span>
            <select
              value={config.cashDrawerCommandProfile}
              onChange={(event) => updateConfig({ cashDrawerCommandProfile: event.target.value as HardwareConfig["cashDrawerCommandProfile"] })}
            >
              <option value="ESCPOS_STANDARD">ESC/POS standard</option>
            </select>
          </label>
          <div className="hardware-actions">
            <button type="button" onClick={openCashDrawer}>{t("hardware.openDrawer")}</button>
          </div>
            </div>
          )}

          {selectedSection === "scanner" && (
            <div className="hardware-section">
              <h2>{t("hardware.scanner")}</h2>
          <label>
            <span>{t("hardware.scannerMode")}</span>
            <select value={config.scannerMode} onChange={() => updateConfig({ scannerMode: "KEYBOARD" })}>
              <option value="KEYBOARD">{t("hardware.mode.keyboard")}</option>
            </select>
          </label>
          <label>
            <span>{t("hardware.scannerTest")}</span>
            <input
              value={scannerValue}
              onChange={(event) => setScannerValue(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  void testScanner(scannerValue);
                }
              }}
              placeholder={t("hardware.scannerPlaceholder")}
            />
          </label>
          <div className="hardware-last-scan">
            <span>{t("hardware.lastScan")}</span>
            <strong>{lastScan || "-"}</strong>
          </div>
            </div>
          )}

          {selectedSection === "escpos" && (
            <div className="hardware-section hardware-section-wide">
              <h2>ESC/POS</h2>
          <div className="hardware-escpos-grid">
            <label>
              <span>{t("hardware.connectionType")}</span>
              <select
                value={config.ticketPrinterConnection}
                onChange={(event) => updateConfig({ ticketPrinterConnection: event.target.value as HardwareConfig["ticketPrinterConnection"] })}
              >
                <option value="WINDOWS_PRINTER">USB / Windows RAW</option>
                <option value="SERIAL">COM</option>
                <option value="NETWORK">LAN</option>
              </select>
            </label>
            <label>
              <span>{t("hardware.devicePath")}</span>
              <input value={config.escposDevicePath} onChange={(event) => updateConfig({ escposDevicePath: event.target.value })} />
            </label>
            <label>
              <span>{t("hardware.host")}</span>
              <input value={config.escposHost} onChange={(event) => updateConfig({ escposHost: event.target.value })} />
            </label>
            <label>
              <span>{t("hardware.port")}</span>
              <input
                type="number"
                value={config.escposPort}
                onChange={(event) => updateConfig({ escposPort: Number(event.target.value) || 9100 })}
              />
            </label>
          </div>
            </div>
          )}

          {selectedSection === "a4" && (
            <div className="hardware-section hardware-section-wide">
              <h2>{t("hardware.a4Printer")}</h2>
          <div className="hardware-a4-grid">
            <label>
              <span>{t("hardware.a4PrinterName")}</span>
              <select value={config.a4PrinterName} onChange={(event) => updateConfig({ a4PrinterName: event.target.value })}>
                <option value="">{t("hardware.selectPrinter")}</option>
                {printers.map((printer) => (
                  <option key={printer.name} value={printer.name}>
                    {printer.displayName}{printer.isDefault ? ` · ${t("hardware.defaultPrinter")}` : ""}
                  </option>
                ))}
              </select>
            </label>
            <div className="hardware-actions">
              <button type="button" onClick={printA4TestDocument}>{t("hardware.printA4Test")}</button>
            </div>
          </div>
          <div className="hardware-route-table">
            <div className="hardware-route-header">
              <span>{t("hardware.route.document")}</span>
              <span>{t("hardware.route.target")}</span>
              <span>{t("hardware.route.printer")}</span>
              <span>{t("hardware.route.paper")}</span>
              <span>{t("hardware.route.orientation")}</span>
              <span>{t("hardware.route.copies")}</span>
              <span>{t("hardware.route.auto")}</span>
              <span>{t("hardware.route.dialog")}</span>
            </div>
            {config.documentPrintRoutes.map((route) => (
              <div className="hardware-route-row" key={route.documentType}>
                <strong>{t(`hardware.document.${route.documentType}`)}</strong>
                <select
                  value={route.printerTarget}
                  onChange={(event) =>
                    updateDocumentRoute(route.documentType, {
                      printerTarget: event.target.value as DocumentPrintRoute["printerTarget"],
                      paperSize: event.target.value === "A4_PRINTER" ? "A4" : "TICKET_80"
                    })
                  }
                >
                  <option value="TICKET_PRINTER">{t("hardware.route.ticketPrinter")}</option>
                  <option value="A4_PRINTER">{t("hardware.route.a4Printer")}</option>
                </select>
                <select
                  value={route.printerName}
                  onChange={(event) => updateDocumentRoute(route.documentType, { printerName: event.target.value })}
                >
                  <option value="">{t("hardware.route.useDefault")}</option>
                  {printers.map((printer) => (
                    <option key={printer.name} value={printer.name}>{printer.displayName}</option>
                  ))}
                </select>
                <select
                  value={route.paperSize}
                  onChange={(event) => updateDocumentRoute(route.documentType, { paperSize: event.target.value as DocumentPrintRoute["paperSize"] })}
                >
                  <option value="TICKET_80">Ticket 80</option>
                  <option value="A4">A4</option>
                </select>
                <select
                  value={route.orientation}
                  onChange={(event) =>
                    updateDocumentRoute(route.documentType, { orientation: event.target.value as DocumentPrintRoute["orientation"] })
                  }
                >
                  <option value="PORTRAIT">{t("hardware.route.portrait")}</option>
                  <option value="LANDSCAPE">{t("hardware.route.landscape")}</option>
                </select>
                <input
                  type="number"
                  min={1}
                  max={9}
                  value={route.copies}
                  onChange={(event) => updateDocumentRoute(route.documentType, { copies: Math.max(1, Number(event.target.value) || 1) })}
                />
                <label className="hardware-route-check">
                  <input
                    type="checkbox"
                    checked={route.printAutomatically}
                    onChange={(event) => updateDocumentRoute(route.documentType, { printAutomatically: event.target.checked })}
                  />
                  <span>{t("hardware.route.autoShort")}</span>
                </label>
                <label className="hardware-route-check">
                  <input
                    type="checkbox"
                    checked={route.showPrintDialog}
                    onChange={(event) => updateDocumentRoute(route.documentType, { showPrintDialog: event.target.checked })}
                  />
                  <span>{t("hardware.route.dialogShort")}</span>
                </label>
              </div>
            ))}
          </div>
            </div>
          )}

          {selectedSection === "customerDisplay" && (
            <div className="hardware-section hardware-section-wide">
              <h2>{t("hardware.customerDisplay")}</h2>
          <div className="hardware-display-grid">
            <label className="hardware-check">
              <input
                type="checkbox"
                checked={config.customerDisplayEnabled}
                onChange={(event) => updateConfig({ customerDisplayEnabled: event.target.checked })}
              />
              <span>{t("hardware.customerDisplayEnabled")}</span>
            </label>
            <label>
              <span>{t("hardware.customerDisplayScreen")}</span>
              <select
                value={config.customerDisplayScreenId}
                onChange={(event) => updateConfig({ customerDisplayScreenId: event.target.value })}
              >
                <option value="">{t("hardware.customerDisplayAutoScreen")}</option>
                {customerDisplays.map((display) => (
                  <option key={display.id} value={display.id}>
                    {display.label}{display.primary ? ` · ${t("hardware.primaryScreen")}` : ""}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span>{t("hardware.customerDisplayIdleLine1")}</span>
              <input
                value={config.customerDisplayIdleLine1}
                onChange={(event) => updateConfig({ customerDisplayIdleLine1: event.target.value })}
              />
            </label>
            <label>
              <span>{t("hardware.customerDisplayIdleLine2")}</span>
              <input
                value={config.customerDisplayIdleLine2}
                onChange={(event) => updateConfig({ customerDisplayIdleLine2: event.target.value })}
              />
            </label>
          </div>
          <div className="hardware-actions hardware-actions-wrap">
            <button type="button" onClick={refreshCustomerDisplays}>{t("hardware.detectScreens")}</button>
            <button type="button" onClick={openCustomerDisplay}>{t("hardware.openCustomerDisplay")}</button>
            <button type="button" onClick={closeCustomerDisplay}>{t("hardware.closeCustomerDisplay")}</button>
            <button type="button" onClick={sendIdleDisplay}>{t("hardware.sendIdleDisplay")}</button>
            <button type="button" onClick={sendSaleDisplay}>{t("hardware.sendSaleDisplay")}</button>
            <button type="button" onClick={sendPaymentDisplay}>{t("hardware.sendPaymentDisplay")}</button>
          </div>
            </div>
          )}

          {selectedSection === "diagnostics" && (
            <div className="hardware-section hardware-section-wide hardware-diagnostics">
              <div className="hardware-section-title-row">
                <h2>{t("hardware.diagnostics.title")}</h2>
                <button type="button" onClick={runAllDiagnostics}>{t("hardware.diagnostics.runAll")}</button>
              </div>
          <div className="hardware-diagnostics-grid">
            {diagnosticItems.map((item) => {
              const result = diagnostics[item.key];
              return (
                <div className="hardware-diagnostic-card" key={item.key}>
                  <div>
                    <strong>{item.label}</strong>
                    <span>{result?.checkedAt || t("hardware.diagnostics.notChecked")}</span>
                  </div>
                  <span className={result?.ok ? "hardware-diagnostic-ok" : result ? "hardware-diagnostic-error" : "hardware-diagnostic-pending"}>
                    {result ? (result.ok ? "OK" : "ERROR") : "-"}
                  </span>
                  <p>{result?.message || t("hardware.diagnostics.pending")}</p>
                </div>
              );
            })}
          </div>
            </div>
          )}
        </section>
      </section>

      <footer className="hardware-footer">
        <button type="button" onClick={onBack}>{t("common.back")}</button>
        <div className="hardware-status">{status}</div>
        <button type="button" onClick={saveConfig}>{t("hardware.save")}</button>
      </footer>
      <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
    </main>
  );
}
