const { app, BrowserWindow, dialog, ipcMain, Menu, safeStorage, screen } = require("electron");
const { execFile } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const { renderA4DocumentHtml } = require("./a4-renderer.cjs");
const { renderTicketHtml } = require("./ticket-renderer.cjs");
const { renderProductLabelHtml, normalizedProfile } = require("./product-label-renderer.cjs");
const { renderTableReportHtml } = require("./table-report-renderer.cjs");
const { restrictNavigation, trustedOrigin } = require("./navigation-security.cjs");
const { buildCashDrawerBuffer, buildTicketBuffer, sendEscposBuffer, shouldOpenCashDrawerForTicket } = require("./escpos.cjs");
const {
  executeEscposTicketPrint,
  executeWindowsTicketPrint,
  resolveExternalDrawerAction,
  resolveTicketPrintRoute,
  withTicketPrinterRoute
} = require("./ticket-print-route.cjs");

const appName = process.env.TPV_DESKTOP_APP_NAME || "TPV ERP";
const appUrl = process.env.TPV_DESKTOP_APP_URL;
const mainWindowMode = process.env.TPV_DESKTOP_WINDOW_MODE === "MAXIMIZED" ? "MAXIMIZED" : "FULLSCREEN";
const defaultHardwareConfig = {
  scannerMode: "KEYBOARD",
  scannerSubmitKey: "ENTER",
  ticketPrinterDriver: "WINDOWS_DRIVER",
  ticketPrinterConnection: "WINDOWS_PRINTER",
  ticketPrinterName: "",
  openCashDrawerWithTicket: true,
  cashDrawerOpeningPaymentMethods: ["EFECTIVO"],
  cashDrawerConnection: "PRINTER",
  cashDrawerCommandProfile: "ESCPOS_STANDARD",
  escposDevicePath: "",
  escposSerialBaudRate: 9600,
  escposHost: "",
  escposPort: 9100,
  cashDrawerDevicePath: "",
  cashDrawerSerialBaudRate: 9600,
  cashDrawerHost: "",
  cashDrawerPort: 9100,
  customerDisplayEnabled: false,
  customerDisplayMode: "COMPACT",
  customerDisplayIdleLine1: "BIENVENIDO",
  customerDisplayIdleLine2: "GRACIAS POR SU COMPRA",
  customerDisplayScreenId: "",
  a4PrinterName: "",
  documentPrintRoutes: [
    {
      documentType: "TICKET",
      printerTarget: "TICKET_PRINTER",
      printerName: "",
      paperSize: "TICKET_80",
      orientation: "PORTRAIT",
      copies: 1,
      printAutomatically: true,
      showPrintDialog: false
    },
    {
      documentType: "INVOICE",
      printerTarget: "A4_PRINTER",
      printerName: "",
      paperSize: "A4",
      orientation: "PORTRAIT",
      copies: 1,
      printAutomatically: false,
      showPrintDialog: true
    },
    {
      documentType: "DELIVERY_NOTE",
      printerTarget: "A4_PRINTER",
      printerName: "",
      paperSize: "A4",
      orientation: "PORTRAIT",
      copies: 1,
      printAutomatically: false,
      showPrintDialog: true
    },
    {
      documentType: "REPORT",
      printerTarget: "A4_PRINTER",
      printerName: "",
      paperSize: "A4",
      orientation: "PORTRAIT",
      copies: 1,
      printAutomatically: false,
      showPrintDialog: true
    }
  ],
  defaultProductLabelProfileId: "ticket-58x40",
  productLabelProfiles: [{
    id: "ticket-58x40",
    name: "Ticket 58 x 40 mm",
    destination: "TICKET_PRINTER",
    printerName: "",
    widthMm: 58,
    heightMm: 40,
    orientation: "PORTRAIT",
    marginTopMm: 5,
    marginRightMm: 5,
    marginBottomMm: 5,
    marginLeftMm: 5,
    horizontalGapMm: 2,
    verticalGapMm: 2,
    copies: 1,
    showStoreName: true
  }]
};

let mainWindow;
let customerDisplayWindow;
let salesDocumentWindow;
const salesDocumentBootstraps = new Map();
let salesUtilityWindow;
let salesUtilityResult;
const salesUtilityBootstraps = new Map();

if (!appUrl) {
  throw new Error("TPV_DESKTOP_APP_URL is required");
}

const trustedAppOrigin = trustedOrigin(appUrl);

function createWindow() {
  Menu.setApplicationMenu(null);

  const opensMaximized = mainWindowMode === "MAXIMIZED";

  mainWindow = new BrowserWindow({
    title: appName,
    fullscreen: !opensMaximized,
    frame: true,
    show: !opensMaximized,
    autoHideMenuBar: true,
    backgroundColor: "#263033",
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });

  if (opensMaximized) {
    mainWindow.once("ready-to-show", () => {
      if (!mainWindow || mainWindow.isDestroyed()) return;
      mainWindow.maximize();
      mainWindow.show();
    });
  }

  restrictNavigation(mainWindow, trustedAppOrigin);
  mainWindow.loadURL(appUrl);
}

function createSalesDocumentWindow(bootstrap) {
  if (salesDocumentWindow && !salesDocumentWindow.isDestroyed()) {
    if (salesDocumentWindow.isMinimized()) salesDocumentWindow.restore();
    salesDocumentWindow.focus();
    return { ok: true, focused: true };
  }
  if (!bootstrap?.session?.accessToken || !bootstrap?.terminalContext?.terminalCode) {
    return structuredError(
      "SALES_DOCUMENT_BOOTSTRAP_INVALID",
      "No se puede abrir la ventana documental sin una sesion y terminal validos"
    );
  }
  salesDocumentWindow = new BrowserWindow({
    title: `${appName} - Factura / Albaran`,
    width: 1380,
    height: 860,
    minWidth: 1050,
    minHeight: 700,
    show: false,
    parent: mainWindow,
    modal: false,
    autoHideMenuBar: true,
    backgroundColor: "#e8edf3",
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  restrictNavigation(salesDocumentWindow, trustedAppOrigin);
  const salesDocumentWebContentsId = salesDocumentWindow.webContents.id;
  salesDocumentBootstraps.set(salesDocumentWebContentsId, structuredClone(bootstrap));
  const target = new URL(appUrl);
  target.searchParams.set("window", "sales-document");
  salesDocumentWindow.loadURL(target.toString());
  salesDocumentWindow.once("ready-to-show", () => salesDocumentWindow?.show());
  salesDocumentWindow.on("closed", () => {
    salesDocumentBootstraps.delete(salesDocumentWebContentsId);
    salesDocumentWindow = undefined;
  });
  return { ok: true, focused: false };
}

function hardwareConfigPath() {
  return path.join(app.getPath("userData"), "hardware-config.json");
}

function terminalIdentityPath() {
  return path.join(app.getPath("userData"), "server-terminal-identity.dpapi");
}

function readTerminalIdentity() {
  const target = terminalIdentityPath();
  if (!fs.existsSync(target)) {
    return { ok: true, identity: null };
  }
  if (!safeStorage.isEncryptionAvailable()) {
    return structuredError("SECURE_STORAGE_UNAVAILABLE", "El almacenamiento seguro de Windows no esta disponible");
  }
  try {
    const encrypted = fs.readFileSync(target);
    return { ok: true, identity: JSON.parse(safeStorage.decryptString(encrypted)) };
  } catch (error) {
    return structuredError("TERMINAL_IDENTITY_INVALID", error instanceof Error ? error.message : "No se pudo leer la identidad del terminal");
  }
}

function writeTerminalIdentity(identity) {
  if (!safeStorage.isEncryptionAvailable()) {
    return structuredError("SECURE_STORAGE_UNAVAILABLE", "El almacenamiento seguro de Windows no esta disponible");
  }
  if (!identity?.terminalId || !identity?.terminalCredential || !identity?.terminalCode || !identity?.storeName) {
    return structuredError("TERMINAL_IDENTITY_INVALID", "La identidad del terminal esta incompleta");
  }
  const target = terminalIdentityPath();
  const temporary = `${target}.tmp`;
  try {
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(temporary, safeStorage.encryptString(JSON.stringify(identity)));
    fs.renameSync(temporary, target);
    return { ok: true };
  } catch (error) {
    try { fs.rmSync(temporary, { force: true }); } catch {}
    return structuredError("TERMINAL_IDENTITY_WRITE_FAILED", error instanceof Error ? error.message : "No se pudo guardar la identidad del terminal");
  }
}

async function saveBinaryFile(request) {
  if (!mainWindow) {
    return structuredError("WINDOW_UNAVAILABLE", "Ventana principal no disponible");
  }
  const result = await dialog.showSaveDialog(mainWindow, {
    defaultPath: request.defaultFileName,
    filters: request.filters || []
  });
  if (result.canceled || !result.filePath) {
    return { ok: true, canceled: true };
  }
  try {
    fs.writeFileSync(result.filePath, Buffer.from(request.bytes));
    return { ok: true, canceled: false, filePath: result.filePath };
  } catch (error) {
    return structuredError("FILE_WRITE_FAILED", error instanceof Error ? error.message : "No se pudo guardar el archivo");
  }
}

async function exportCurrentPagePdf(defaultFileName) {
  if (!mainWindow) {
    return structuredError("WINDOW_UNAVAILABLE", "Ventana principal no disponible");
  }
  const result = await dialog.showSaveDialog(mainWindow, {
    defaultPath: defaultFileName || "informe.pdf",
    filters: [{ name: "PDF", extensions: ["pdf"] }]
  });
  if (result.canceled || !result.filePath) {
    return { ok: true, canceled: true };
  }
  try {
    const contents = await mainWindow.webContents.printToPDF({
      printBackground: true,
      landscape: true,
      pageSize: "A4"
    });
    fs.writeFileSync(result.filePath, contents);
    return { ok: true, canceled: false, filePath: result.filePath };
  } catch (error) {
    return structuredError("PDF_EXPORT_FAILED", error instanceof Error ? error.message : "No se pudo generar el PDF");
  }
}

function printCurrentPage() {
  if (!mainWindow) {
    return Promise.resolve(structuredError("WINDOW_UNAVAILABLE", "Ventana principal no disponible"));
  }
  return new Promise((resolve) => {
    mainWindow.webContents.print({ silent: false, printBackground: true }, (success, reason) => {
      resolve(success ? { ok: true } : structuredError("PRINT_FAILED", reason || "No se pudo imprimir"));
    });
  });
}

function structuredError(code, message) {
  return { ok: false, code, message };
}

function normalizeHardwareConfig(config) {
  const nextConfig = { ...defaultHardwareConfig, ...config };
  delete nextConfig.scannerMinimumLength;
  delete nextConfig.scannerMaximumInterKeyMs;
  delete nextConfig.scannerMaximumDurationMs;
  if (config?.ticketPrinterMode === "ESCPOS") {
    nextConfig.ticketPrinterDriver = "ESCPOS_RAW";
    nextConfig.ticketPrinterConnection =
      config.escposConnectionType === "SERIAL" ? "SERIAL" : config.escposConnectionType === "NETWORK" ? "NETWORK" : "WINDOWS_PRINTER";
  } else if (config?.ticketPrinterMode === "WINDOWS_PRINTER") {
    nextConfig.ticketPrinterDriver = "WINDOWS_DRIVER";
    nextConfig.ticketPrinterConnection = "WINDOWS_PRINTER";
  }
  if (!nextConfig.cashDrawerConnection) {
    nextConfig.cashDrawerConnection = "PRINTER";
  }
  if (!Array.isArray(nextConfig.cashDrawerOpeningPaymentMethods)) {
    nextConfig.cashDrawerOpeningPaymentMethods = ["EFECTIVO"];
  }
  const configuredRoutes = Array.isArray(config?.documentPrintRoutes) ? config.documentPrintRoutes : [];
  nextConfig.documentPrintRoutes = defaultHardwareConfig.documentPrintRoutes.map((defaultRoute) => {
    const configuredRoute = configuredRoutes.find((route) => route.documentType === defaultRoute.documentType) || {};
    return {
      ...defaultRoute,
      ...configuredRoute,
      ...(defaultRoute.documentType === "TICKET" ? { printAutomatically: true } : {})
    };
  });
  const configuredLabelProfiles = Array.isArray(config?.productLabelProfiles)
    ? config.productLabelProfiles.filter((profile) => profile && typeof profile.id === "string")
    : [];
  nextConfig.productLabelProfiles = configuredLabelProfiles.length > 0
    ? configuredLabelProfiles.map((profile) => ({
        ...profile,
        ...normalizedProfile(profile),
        id: String(profile.id),
        name: String(profile.name || profile.id)
      }))
    : defaultHardwareConfig.productLabelProfiles;
  if (!nextConfig.productLabelProfiles.some((profile) => profile.id === nextConfig.defaultProductLabelProfileId)) {
    nextConfig.defaultProductLabelProfileId = nextConfig.productLabelProfiles[0].id;
  }
  return nextConfig;
}

async function exportTableReportPdf(report, defaultFileName) {
  if (!mainWindow) {
    return structuredError("WINDOW_UNAVAILABLE", "Ventana principal no disponible");
  }
  const result = await dialog.showSaveDialog(mainWindow, {
    defaultPath: defaultFileName || "informe.pdf",
    filters: [{ name: "PDF", extensions: ["pdf"] }]
  });
  if (result.canceled || !result.filePath) {
    return { ok: true, canceled: true };
  }
  const printWindow = new BrowserWindow({
    show: false,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  try {
    await printWindow.loadURL(
      `data:text/html;charset=utf-8,${encodeURIComponent(renderTableReportHtml(report))}`
    );
    const contents = await printWindow.webContents.printToPDF({
      printBackground: true,
      landscape: false,
      pageSize: "A4",
      margins: { top: 0, right: 0, bottom: 0, left: 0 }
    });
    fs.writeFileSync(result.filePath, contents);
    return { ok: true, canceled: false, filePath: result.filePath };
  } catch (error) {
    return structuredError(
      "PDF_EXPORT_FAILED",
      error instanceof Error ? error.message : "No se pudo generar el PDF del informe"
    );
  } finally {
    printWindow.destroy();
  }
}

function createSalesUtilityWindow(bootstrap) {
  if (salesUtilityWindow && !salesUtilityWindow.isDestroyed()) {
    if (salesUtilityWindow.isMinimized()) salesUtilityWindow.restore();
    salesUtilityWindow.focus();
    return Promise.resolve(structuredError(
      "SALES_UTILITY_ALREADY_OPEN",
      "Ya existe una herramienta de venta abierta"
    ));
  }
  if (!mainWindow || mainWindow.isDestroyed()
      || !["INTERNAL_EAN", "PRODUCT_LABEL"].includes(bootstrap?.kind)
      || !bootstrap?.session?.accessToken
      || !bootstrap?.terminalContext?.terminalCode) {
    return Promise.resolve(structuredError(
      "SALES_UTILITY_BOOTSTRAP_INVALID",
      "No se puede abrir la herramienta sin una sesion y terminal validos"
    ));
  }
  const isLabel = bootstrap.kind === "PRODUCT_LABEL";
  salesUtilityResult = { ok: true, canceled: true };
  salesUtilityWindow = new BrowserWindow({
    title: `${appName} - ${isLabel ? "Imprimir etiqueta" : "Generador EAN"}`,
    width: isLabel ? 1280 : 1040,
    height: isLabel ? 880 : 780,
    minWidth: isLabel ? 1080 : 860,
    minHeight: isLabel ? 720 : 640,
    show: false,
    parent: mainWindow,
    modal: true,
    autoHideMenuBar: true,
    backgroundColor: "#e8edf3",
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  const contentsId = salesUtilityWindow.webContents.id;
  salesUtilityBootstraps.set(contentsId, structuredClone(bootstrap));
  const target = new URL(appUrl);
  target.searchParams.set("window", "sales-utility");
  salesUtilityWindow.loadURL(target.toString());
  salesUtilityWindow.once("ready-to-show", () => salesUtilityWindow?.show());
  return new Promise((resolve) => {
    salesUtilityWindow.on("closed", () => {
      salesUtilityBootstraps.delete(contentsId);
      const result = salesUtilityResult ?? { ok: true, canceled: true };
      salesUtilityResult = undefined;
      salesUtilityWindow = undefined;
      resolve(result);
    });
  });
}

function readHardwareConfig() {
  try {
    const raw = fs.readFileSync(hardwareConfigPath(), "utf8");
    return normalizeHardwareConfig(JSON.parse(raw));
  } catch {
    return defaultHardwareConfig;
  }
}

function writeHardwareConfig(config) {
  const nextConfig = normalizeHardwareConfig(config);
  fs.mkdirSync(path.dirname(hardwareConfigPath()), { recursive: true });
  fs.writeFileSync(hardwareConfigPath(), JSON.stringify(nextConfig, null, 2), "utf8");
  return nextConfig;
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

function formatMoney(value) {
  return Number(value || 0).toFixed(2);
}

function legacyRenderTicketHtml(ticket) {
  const lineRows = (ticket.lines || [])
    .map(
      (line) => `
        <tr>
          <td>${escapeHtml(line.name)}${(line.serialNumbers || []).map((serial) => `<div class="serial">S/N: ${escapeHtml(serial)}</div>`).join("")}</td>
          <td class="right">${escapeHtml(line.quantity)}</td>
          <td class="right">${formatMoney(line.price)}</td>
          <td class="right">${formatMoney(line.total)}</td>
        </tr>`
    )
    .join("");
  const paymentRows = (ticket.payments || [])
    .map(
      (payment) => `
        <div class="row">
          <span>${escapeHtml(payment.method)}</span>
          <strong>${formatMoney(payment.amount)}</strong>
        </div>`
    )
    .join("");

  return `<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    @page { margin: 4mm; size: 80mm auto; }
    body { width: 72mm; margin: 0; color: #000; font-family: Arial, sans-serif; font-size: 11px; }
    h1 { margin: 0 0 6px; text-align: center; font-size: 16px; }
    .meta { text-align: center; margin-bottom: 8px; }
    table { width: 100%; border-collapse: collapse; }
    th { border-bottom: 1px solid #000; text-align: left; }
    td { padding: 2px 0; }
    .serial { font-size: 10px; margin-top: 1px; }
    .right { text-align: right; }
    .separator { border-top: 1px dashed #000; margin: 8px 0; }
    .row { display: flex; justify-content: space-between; gap: 8px; }
    .total { font-size: 16px; font-weight: 800; }
  </style>
</head>
<body>
  <h1>${escapeHtml(ticket.storeName || "APP VENTA")}</h1>
  <div class="meta">
    <div>${escapeHtml(ticket.documentNumber || "")}</div>
    <div>Terminal ${escapeHtml(ticket.terminalCode || "")}</div>
    <div>${escapeHtml(ticket.issuedAt || "")}</div>
  </div>
  <table>
    <thead>
      <tr><th>Item</th><th class="right">Qty.</th><th class="right">Price</th><th class="right">Total</th></tr>
    </thead>
    <tbody>${lineRows}</tbody>
  </table>
  <div class="separator"></div>
  ${paymentRows}
  <div class="separator"></div>
  <div class="row total"><span>Total</span><strong>${formatMoney(ticket.total)}</strong></div>
</body>
</html>`;
}

function renderCustomerDisplayHtml(state) {
  return `<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    html, body {
      width: 100%;
      height: 100%;
      margin: 0;
      overflow: hidden;
      background: #05070b;
      color: #f8fbff;
      font-family: "Segoe UI", "Microsoft YaHei UI", Arial, sans-serif;
    }
    body {
      display: grid;
      grid-template-rows: 1fr 1fr;
      align-items: center;
      justify-items: center;
      padding: 5vh 5vw;
    }
    .line {
      width: 100%;
      text-align: center;
      font-size: clamp(42px, 11vw, 150px);
      line-height: 1.05;
      font-weight: 900;
      letter-spacing: 0;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .line + .line {
      color: #9fd0ff;
    }
  </style>
</head>
<body>
  <div class="line">${escapeHtml(state?.line1 || "")}</div>
  <div class="line">${escapeHtml(state?.line2 || "")}</div>
</body>
</html>`;
}

function loadCustomerDisplayState(state) {
  if (!customerDisplayWindow || customerDisplayWindow.isDestroyed()) {
    return structuredError("CUSTOMER_DISPLAY_NOT_OPEN", "Pantalla cliente no esta abierta");
  }
  customerDisplayWindow.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(renderCustomerDisplayHtml(state))}`);
  return { ok: true };
}

function encodePowerShellCommand(command) {
  return Buffer.from(command, "utf16le").toString("base64");
}

function sendWindowsRawPrinterBuffer(printerName, buffer) {
  if (process.platform !== "win32") {
    return Promise.reject(new Error("Impresion RAW Windows solo disponible en Windows"));
  }

  const bytes = Array.from(buffer).join(",");
  const command = `
$printerName = $env:TPV_RAW_PRINTER_NAME
$source = @'
using System;
using System.Runtime.InteropServices;
public class TpvRawPrinter {
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Ansi)] public class DOCINFOA { [MarshalAs(UnmanagedType.LPStr)] public string pDocName; [MarshalAs(UnmanagedType.LPStr)] public string pOutputFile; [MarshalAs(UnmanagedType.LPStr)] public string pDataType; }
  [DllImport("winspool.Drv", EntryPoint="OpenPrinterA", SetLastError=true, CharSet=CharSet.Ansi, ExactSpelling=true, CallingConvention=CallingConvention.StdCall)] public static extern bool OpenPrinter(string name, out IntPtr printer, IntPtr defaults);
  [DllImport("winspool.Drv", EntryPoint="ClosePrinter", SetLastError=true, ExactSpelling=true, CallingConvention=CallingConvention.StdCall)] public static extern bool ClosePrinter(IntPtr printer);
  [DllImport("winspool.Drv", EntryPoint="StartDocPrinterA", SetLastError=true, CharSet=CharSet.Ansi, ExactSpelling=true, CallingConvention=CallingConvention.StdCall)] public static extern bool StartDocPrinter(IntPtr printer, Int32 level, [In, MarshalAs(UnmanagedType.LPStruct)] DOCINFOA doc);
  [DllImport("winspool.Drv", EntryPoint="EndDocPrinter", SetLastError=true, ExactSpelling=true, CallingConvention=CallingConvention.StdCall)] public static extern bool EndDocPrinter(IntPtr printer);
  [DllImport("winspool.Drv", EntryPoint="StartPagePrinter", SetLastError=true, ExactSpelling=true, CallingConvention=CallingConvention.StdCall)] public static extern bool StartPagePrinter(IntPtr printer);
  [DllImport("winspool.Drv", EntryPoint="EndPagePrinter", SetLastError=true, ExactSpelling=true, CallingConvention=CallingConvention.StdCall)] public static extern bool EndPagePrinter(IntPtr printer);
  [DllImport("winspool.Drv", EntryPoint="WritePrinter", SetLastError=true, ExactSpelling=true, CallingConvention=CallingConvention.StdCall)] public static extern bool WritePrinter(IntPtr printer, byte[] bytes, Int32 count, out Int32 written);
  public static int Send(string printerName, byte[] bytes) {
    IntPtr printer;
    if (!OpenPrinter(printerName, out printer, IntPtr.Zero)) return -1;
    try {
      DOCINFOA doc = new DOCINFOA(); doc.pDocName = "TPV ERP RAW"; doc.pDataType = "RAW";
      if (!StartDocPrinter(printer, 1, doc)) return -2;
      try {
        if (!StartPagePrinter(printer)) return -3;
        try { int written; return WritePrinter(printer, bytes, bytes.Length, out written) ? written : -4; }
        finally { EndPagePrinter(printer); }
      } finally { EndDocPrinter(printer); }
    } finally { ClosePrinter(printer); }
  }
}
'@
Add-Type -TypeDefinition $source -ErrorAction Stop
$written = [TpvRawPrinter]::Send($printerName, [byte[]](${bytes}))
if ($written -ne ${buffer.length}) { throw "RAW_WRITE_FAILED:$written" }
`;

  return new Promise((resolve, reject) => {
    execFile(
      "powershell.exe",
      ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encodePowerShellCommand(command)],
      {
        windowsHide: true,
        timeout: 8000,
        env: { ...process.env, TPV_RAW_PRINTER_NAME: printerName }
      },
      (error, stdout, stderr) => {
        if (error) {
          reject(new Error(stderr || stdout || error.message));
          return;
        }
        resolve();
      }
    );
  });
}

async function openCashDrawerWithConfig(config) {
  const nextConfig = { ...readHardwareConfig(), ...config };
  if (nextConfig.cashDrawerConnection === "NONE") {
    throw new Error("Cajon no configurado");
  }
  if (nextConfig.cashDrawerConnection === "PRINTER") {
    await sendTicketPrinterRawBuffer(nextConfig, buildCashDrawerBuffer());
    return;
  }
  await sendDrawerRawBuffer(nextConfig, buildCashDrawerBuffer());
}

async function sendTicketPrinterRawBuffer(config, buffer) {
  if (config.ticketPrinterConnection === "WINDOWS_PRINTER") {
    if (!config.ticketPrinterName) {
      throw new Error("Impresora no configurada");
    }
    await sendWindowsRawPrinterBuffer(config.ticketPrinterName, buffer);
    return;
  }
  await sendEscposBuffer(
    {
      escposConnectionType: config.ticketPrinterConnection,
      escposDevicePath: config.escposDevicePath,
      escposSerialBaudRate: config.escposSerialBaudRate,
      escposHost: config.escposHost,
      escposPort: config.escposPort
    },
    buffer
  );
}

async function sendDrawerRawBuffer(config, buffer) {
  await sendEscposBuffer(
    {
      escposConnectionType: config.cashDrawerConnection,
      escposDevicePath: config.cashDrawerDevicePath,
      escposSerialBaudRate: config.cashDrawerSerialBaudRate,
      escposHost: config.cashDrawerHost,
      escposPort: config.cashDrawerPort
    },
    buffer
  );
}

function listCustomerDisplays() {
  const primaryDisplay = screen.getPrimaryDisplay();
  return screen.getAllDisplays().map((display, index) => ({
    id: String(display.id),
    label: `Pantalla ${index + 1} (${display.size.width}x${display.size.height})`,
    width: display.size.width,
    height: display.size.height,
    primary: display.id === primaryDisplay.id
  }));
}

function findCustomerDisplay(config) {
  const displays = screen.getAllDisplays();
  const selected = displays.find((display) => String(display.id) === String(config?.customerDisplayScreenId || ""));
  if (selected) {
    return selected;
  }
  return displays.find((display) => display.id !== screen.getPrimaryDisplay().id) ?? screen.getPrimaryDisplay();
}

function openCustomerDisplay(config, state) {
  const targetDisplay = findCustomerDisplay(config);
  if (customerDisplayWindow && !customerDisplayWindow.isDestroyed()) {
    customerDisplayWindow.setBounds(targetDisplay.bounds);
    customerDisplayWindow.show();
    return loadCustomerDisplayState(state);
  }

  customerDisplayWindow = new BrowserWindow({
    x: targetDisplay.bounds.x,
    y: targetDisplay.bounds.y,
    width: targetDisplay.bounds.width,
    height: targetDisplay.bounds.height,
    frame: false,
    fullscreen: true,
    autoHideMenuBar: true,
    backgroundColor: "#05070b",
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  customerDisplayWindow.on("closed", () => {
    customerDisplayWindow = undefined;
  });
  return loadCustomerDisplayState(state);
}

async function printTicket(ticket, config) {
  const nextConfig = normalizeHardwareConfig({ ...readHardwareConfig(), ...config });
  const route = resolveTicketPrintRoute(nextConfig);
  const printerName = route.printerName;
  const routedConfig = withTicketPrinterRoute(nextConfig, route);

  if (nextConfig.ticketPrinterDriver === "ESCPOS_RAW") {
    const shouldOpenDrawer = shouldOpenCashDrawerForTicket(nextConfig, ticket);
    return executeEscposTicketPrint({
      sendBuffer: (buffer) => sendTicketPrinterRawBuffer(routedConfig, buffer),
      ticketBuffer: buildTicketBuffer(ticket),
      drawerBuffer: shouldOpenDrawer && nextConfig.cashDrawerConnection === "PRINTER"
        ? buildCashDrawerBuffer()
        : undefined,
      copies: route.copies,
      openExternalDrawer: resolveExternalDrawerAction(
        shouldOpenDrawer,
        nextConfig.cashDrawerConnection,
        () => openCashDrawerWithConfig(routedConfig)
      ),
      structuredError
    });
  }

  if (!printerName) {
    return structuredError("PRINTER_NOT_CONFIGURED", "Impresora no configurada");
  }

  const printWindow = new BrowserWindow({
    show: false,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });

  try {
    await printWindow.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(renderTicketHtml(ticket))}`);
    return executeWindowsTicketPrint({
      webContents: printWindow.webContents,
      printerName,
      copies: route.copies,
      openDrawer: shouldOpenCashDrawerForTicket(nextConfig, ticket)
          && nextConfig.cashDrawerConnection !== "NONE"
        ? () => openCashDrawerWithConfig(routedConfig)
        : undefined,
      structuredError
    });
  } catch (error) {
    return structuredError("PRINT_FAILED", error instanceof Error ? error.message : "Error de impresion");
  } finally {
    printWindow.destroy();
  }
}

async function exportTicketPdf(ticket, defaultFileName) {
  if (!mainWindow) {
    return structuredError("WINDOW_UNAVAILABLE", "Ventana principal no disponible");
  }
  const result = await dialog.showSaveDialog(mainWindow, {
    defaultPath: defaultFileName || "ticket.pdf",
    filters: [{ name: "PDF", extensions: ["pdf"] }]
  });
  if (result.canceled || !result.filePath) {
    return { ok: true, canceled: true };
  }
  const printWindow = new BrowserWindow({
    show: false,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  try {
    await printWindow.loadURL(
      `data:text/html;charset=utf-8,${encodeURIComponent(renderTicketHtml(ticket))}`
    );
    const contents = await printWindow.webContents.printToPDF({
      printBackground: true,
      landscape: false,
      pageSize: "A4"
    });
    fs.writeFileSync(result.filePath, contents);
    return { ok: true, canceled: false, filePath: result.filePath };
  } catch (error) {
    return structuredError(
      "PDF_EXPORT_FAILED",
      error instanceof Error ? error.message : "No se pudo generar el PDF del ticket"
    );
  } finally {
    printWindow.destroy();
  }
}

async function exportA4DocumentPdf(document, defaultFileName) {
  if (!mainWindow) {
    return structuredError("WINDOW_UNAVAILABLE", "Ventana principal no disponible");
  }
  const result = await dialog.showSaveDialog(mainWindow, {
    defaultPath: defaultFileName || "documento.pdf",
    filters: [{ name: "PDF", extensions: ["pdf"] }]
  });
  if (result.canceled || !result.filePath) {
    return { ok: true, canceled: true };
  }
  const printWindow = new BrowserWindow({
    show: false,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  try {
    await printWindow.loadURL(
      `data:text/html;charset=utf-8,${encodeURIComponent(renderA4DocumentHtml(document))}`
    );
    const contents = await printWindow.webContents.printToPDF({
      printBackground: true,
      landscape: false,
      pageSize: "A4"
    });
    fs.writeFileSync(result.filePath, contents);
    return { ok: true, canceled: false, filePath: result.filePath };
  } catch (error) {
    return structuredError(
      "PDF_EXPORT_FAILED",
      error instanceof Error ? error.message : "No se pudo generar el PDF del documento"
    );
  } finally {
    printWindow.destroy();
  }
}

async function printA4Document(document, config) {
  const nextConfig = normalizeHardwareConfig({ ...readHardwareConfig(), ...config });
  const route = (nextConfig.documentPrintRoutes || []).find((item) => item.documentType === document.documentType);
  const printerName =
    route?.printerName || (route?.printerTarget === "TICKET_PRINTER" ? nextConfig.ticketPrinterName : nextConfig.a4PrinterName);
  const showPrintDialog = Boolean(route?.showPrintDialog);
  if (!printerName && !showPrintDialog) {
    return structuredError("PRINTER_NOT_CONFIGURED", "Impresora A4 no configurada");
  }

  const printWindow = new BrowserWindow({
    show: false,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });

  try {
    await printWindow.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(renderA4DocumentHtml(document))}`);
    await new Promise((resolve, reject) => {
      const printOptions = {
        silent: !showPrintDialog,
        printBackground: true,
        landscape: route?.orientation === "LANDSCAPE",
        copies: Math.max(1, Number(route?.copies) || 1)
      };
      if (printerName) {
        printOptions.deviceName = printerName;
      }
      printWindow.webContents.print(printOptions, (success, failureReason) => {
        if (success) {
          resolve();
          return;
        }
        reject(new Error(failureReason || "PRINT_FAILED"));
      });
    });
    return { ok: true };
  } catch (error) {
    return structuredError("PRINT_FAILED", error instanceof Error ? error.message : "Error de impresion A4");
  } finally {
    printWindow.destroy();
  }
}

function productLabelPrinterName(profile, config) {
  if (profile.printerName) return profile.printerName;
  if (profile.destination === "TICKET_PRINTER") return config.ticketPrinterName;
  if (profile.destination === "A4") return config.a4PrinterName;
  return "";
}

async function printProductLabel(request, config) {
  const nextConfig = normalizeHardwareConfig({ ...readHardwareConfig(), ...config });
  const profile = normalizedProfile(request?.profile);
  const printerName = productLabelPrinterName(profile, nextConfig);
  if (!printerName) {
    return structuredError("PRINTER_NOT_CONFIGURED", "Impresora de etiquetas no configurada");
  }
  const printWindow = new BrowserWindow({
    show: false,
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true }
  });
  try {
    await printWindow.loadURL(
      `data:text/html;charset=utf-8,${encodeURIComponent(renderProductLabelHtml({ ...request, profile }))}`
    );
    await new Promise((resolve, reject) => {
      const options = {
        silent: true,
        printBackground: true,
        deviceName: printerName,
        landscape: profile.orientation === "LANDSCAPE",
        margins: { marginType: "none" },
        pageSize: profile.destination === "A4"
          ? "A4"
          : { width: Math.round(profile.widthMm * 1000), height: Math.round(profile.heightMm * 1000) }
      };
      printWindow.webContents.print(options, (success, failureReason) => {
        if (success) resolve();
        else reject(new Error(failureReason || "PRINT_FAILED"));
      });
    });
    return { ok: true };
  } catch (error) {
    return structuredError(
      "PRINT_FAILED",
      error instanceof Error ? error.message : "No se pudo imprimir la etiqueta"
    );
  } finally {
    printWindow.destroy();
  }
}

async function exportProductLabelPdf(request, defaultFileName) {
  if (!mainWindow) {
    return structuredError("WINDOW_UNAVAILABLE", "Ventana principal no disponible");
  }
  const result = await dialog.showSaveDialog(mainWindow, {
    defaultPath: defaultFileName || "etiqueta-producto.pdf",
    filters: [{ name: "PDF", extensions: ["pdf"] }]
  });
  if (result.canceled || !result.filePath) {
    return { ok: true, canceled: true };
  }
  const profile = normalizedProfile(request?.profile);
  const printWindow = new BrowserWindow({
    show: false,
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true }
  });
  try {
    await printWindow.loadURL(
      `data:text/html;charset=utf-8,${encodeURIComponent(renderProductLabelHtml({ ...request, profile }))}`
    );
    const contents = await printWindow.webContents.printToPDF({
      printBackground: true,
      landscape: profile.orientation === "LANDSCAPE",
      pageSize: profile.destination === "A4"
        ? "A4"
        : { width: Math.round(profile.widthMm * 1000), height: Math.round(profile.heightMm * 1000) },
      margins: { top: 0, right: 0, bottom: 0, left: 0 }
    });
    fs.writeFileSync(result.filePath, contents);
    return { ok: true, canceled: false, filePath: result.filePath };
  } catch (error) {
    return structuredError(
      "PDF_EXPORT_FAILED",
      error instanceof Error ? error.message : "No se pudo generar el PDF de etiquetas"
    );
  } finally {
    printWindow.destroy();
  }
}

ipcMain.handle("tpv:close-application", () => {
  app.quit();
});

ipcMain.handle("tpv:terminal-identity:load", () => readTerminalIdentity());
ipcMain.handle("tpv:terminal-identity:save", (_event, identity) => writeTerminalIdentity(identity));
ipcMain.handle("tpv:reports:save-file", (_event, request) => saveBinaryFile(request));
ipcMain.handle("tpv:reports:export-pdf", (_event, defaultFileName) => exportCurrentPagePdf(defaultFileName));
ipcMain.handle("tpv:reports:export-table-pdf", (_event, report, defaultFileName) =>
  exportTableReportPdf(report, defaultFileName));
ipcMain.handle("tpv:reports:print", () => printCurrentPage());

ipcMain.handle("tpv:hardware:list-printers", async () => {
  if (!mainWindow) {
    return structuredError("HARDWARE_UNAVAILABLE", "Ventana principal no disponible");
  }

  try {
    const printers = await mainWindow.webContents.getPrintersAsync();
    return {
      ok: true,
      printers: printers.map((printer) => ({
        name: printer.name,
        displayName: printer.displayName || printer.name,
        isDefault: Boolean(printer.isDefault)
      }))
    };
  } catch (error) {
    return structuredError("HARDWARE_UNAVAILABLE", error instanceof Error ? error.message : "No se pueden listar impresoras");
  }
});

ipcMain.handle("tpv:hardware:list-customer-displays", () => ({
  ok: true,
  displays: listCustomerDisplays()
}));

ipcMain.handle("tpv:hardware:get-config", () => readHardwareConfig());

ipcMain.handle("tpv:hardware:save-config", (_event, config) => {
  writeHardwareConfig(config);
  return { ok: true };
});

ipcMain.handle("tpv:hardware:print-ticket", (_event, ticket, config) => printTicket(ticket, config));
ipcMain.handle("tpv:hardware:export-ticket-pdf", (_event, ticket, defaultFileName) =>
  exportTicketPdf(ticket, defaultFileName));
ipcMain.handle("tpv:hardware:export-a4-document-pdf", (_event, document, defaultFileName) =>
  exportA4DocumentPdf(document, defaultFileName));

ipcMain.handle("tpv:hardware:print-a4-document", (_event, document, config) => printA4Document(document, config));
ipcMain.handle("tpv:hardware:print-product-label", (_event, request, config) => printProductLabel(request, config));
ipcMain.handle("tpv:hardware:export-product-label-pdf", (_event, request, defaultFileName) =>
  exportProductLabelPdf(request, defaultFileName));

ipcMain.handle("tpv:hardware:open-cash-drawer", async (_event, config) => {
  try {
    await openCashDrawerWithConfig(config);
    return { ok: true };
  } catch (error) {
    const message = error instanceof Error ? error.message : "Error al abrir cajon";
    if (message.includes("Impresora no configurada")) {
      return structuredError("PRINTER_NOT_CONFIGURED", message);
    }
    return structuredError("CASH_DRAWER_UNAVAILABLE", message);
  }
});

ipcMain.handle("tpv:hardware:test-scanner-input", (_event, code) => ({
  ok: true,
  code: String(code || ""),
  readAt: new Date().toISOString()
}));

ipcMain.handle("tpv:hardware:open-customer-display", (_event, config, state) => {
  try {
    return openCustomerDisplay({ ...readHardwareConfig(), ...config }, state);
  } catch (error) {
    return structuredError("CUSTOMER_DISPLAY_UNAVAILABLE", error instanceof Error ? error.message : "No se pudo abrir pantalla cliente");
  }
});

ipcMain.handle("tpv:hardware:close-customer-display", () => {
  if (!customerDisplayWindow || customerDisplayWindow.isDestroyed()) {
    return structuredError("CUSTOMER_DISPLAY_NOT_OPEN", "Pantalla cliente no esta abierta");
  }
  customerDisplayWindow.close();
  customerDisplayWindow = undefined;
  return { ok: true };
});

ipcMain.handle("tpv:hardware:update-customer-display", (_event, state) => loadCustomerDisplayState(state));

ipcMain.handle("tpv:sales-documents:open", (_event, bootstrap) =>
  createSalesDocumentWindow(bootstrap));

ipcMain.handle("tpv:sales-documents:consume-bootstrap", (event) => {
  const bootstrap = salesDocumentBootstraps.get(event.sender.id) ?? null;
  salesDocumentBootstraps.delete(event.sender.id);
  return bootstrap;
});

ipcMain.handle("tpv:sales-documents:close", () => {
  if (salesDocumentWindow && !salesDocumentWindow.isDestroyed()) {
    salesDocumentWindow.close();
  }
  return { ok: true };
});

ipcMain.handle("tpv:sales-utility:open", (_event, bootstrap) =>
  createSalesUtilityWindow(bootstrap));

ipcMain.handle("tpv:sales-utility:consume-bootstrap", (event) => {
  const bootstrap = salesUtilityBootstraps.get(event.sender.id) ?? null;
  salesUtilityBootstraps.delete(event.sender.id);
  return bootstrap;
});

ipcMain.handle("tpv:sales-utility:complete", (event, result) => {
  if (!salesUtilityWindow || salesUtilityWindow.isDestroyed()
      || event.sender.id !== salesUtilityWindow.webContents.id) {
    return structuredError(
      "SALES_UTILITY_WINDOW_INVALID",
      "La ventana de la herramienta ya no esta disponible"
    );
  }
  salesUtilityResult = {
    ok: true,
    canceled: false,
    catalogChanged: result?.catalogChanged === true,
    printed: result?.printed === true,
    pdf: result?.pdf === true
  };
  salesUtilityWindow.close();
  return { ok: true };
});

ipcMain.handle("tpv:sales-utility:close", (event) => {
  if (salesUtilityWindow && !salesUtilityWindow.isDestroyed()
      && event.sender.id === salesUtilityWindow.webContents.id) {
    salesUtilityWindow.close();
  }
  return { ok: true };
});

app.whenReady().then(createWindow);

app.on("window-all-closed", () => {
  app.quit();
});

app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createWindow();
  }
});
