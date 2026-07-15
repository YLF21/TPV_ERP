const { app, BrowserWindow, ipcMain, Menu, screen } = require("electron");
const { execFile } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const { buildCashDrawerBuffer, buildTicketBuffer, sendEscposBuffer, shouldOpenCashDrawerForTicket } = require("./escpos.cjs");
const { resolveTicketPrintRoute } = require("./ticket-print-route.cjs");

const appName = process.env.TPV_DESKTOP_APP_NAME || "TPV ERP";
const appUrl = process.env.TPV_DESKTOP_APP_URL;
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
  ]
};

let mainWindow;
let customerDisplayWindow;

if (!appUrl) {
  throw new Error("TPV_DESKTOP_APP_URL is required");
}

function createWindow() {
  Menu.setApplicationMenu(null);

  mainWindow = new BrowserWindow({
    title: appName,
    fullscreen: true,
    autoHideMenuBar: true,
    backgroundColor: "#263033",
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });

  mainWindow.loadURL(appUrl);
}

function hardwareConfigPath() {
  return path.join(app.getPath("userData"), "hardware-config.json");
}

function structuredError(code, message) {
  return { ok: false, code, message };
}

function normalizeHardwareConfig(config) {
  const nextConfig = { ...defaultHardwareConfig, ...config };
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
  nextConfig.documentPrintRoutes = defaultHardwareConfig.documentPrintRoutes.map((defaultRoute) => ({
    ...defaultRoute,
    ...(configuredRoutes.find((route) => route.documentType === defaultRoute.documentType) || {})
  }));
  return nextConfig;
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

function renderTicketHtml(ticket) {
  const lineRows = (ticket.lines || [])
    .map(
      (line) => `
        <tr>
          <td>${escapeHtml(line.name)}</td>
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
      <tr><th>Articulo</th><th class="right">Cant.</th><th class="right">Precio</th><th class="right">Total</th></tr>
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

function renderA4DocumentHtml(document) {
  const rows = (document.lines || [])
    .map(
      (line) => `
        <tr>
          <td>${escapeHtml(line.name)}</td>
          <td class="right">${escapeHtml(line.quantity)}</td>
          <td class="right">${formatMoney(line.price)}</td>
          <td class="right">${formatMoney(line.total)}</td>
        </tr>`
    )
    .join("");

  return `<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    @page { size: A4 portrait; margin: 14mm; }
    body { margin: 0; color: #111827; font-family: Arial, "Segoe UI", sans-serif; font-size: 12px; }
    header { display: flex; justify-content: space-between; gap: 20px; border-bottom: 2px solid #111827; padding-bottom: 12px; margin-bottom: 20px; }
    h1 { margin: 0; font-size: 26px; }
    .meta { text-align: right; line-height: 1.6; }
    table { width: 100%; border-collapse: collapse; margin-top: 18px; }
    th { background: #e8eef7; text-align: left; }
    th, td { border: 1px solid #c8d2e0; padding: 8px; }
    .right { text-align: right; }
    .totals { width: 260px; margin-left: auto; margin-top: 20px; }
    .totals .row { display: flex; justify-content: space-between; padding: 7px 0; border-bottom: 1px solid #d5dce8; }
    .totals .total { font-size: 18px; font-weight: 800; border-bottom: 0; }
  </style>
</head>
<body>
  <header>
    <div>
      <h1>${escapeHtml(document.title || "Documento")}</h1>
      <div>${escapeHtml(document.storeName || "")}</div>
    </div>
    <div class="meta">
      <div>Terminal ${escapeHtml(document.terminalCode || "")}</div>
      <div>${escapeHtml(document.issuedAt || "")}</div>
    </div>
  </header>
  <table>
    <thead>
      <tr><th>Descripcion</th><th class="right">Cantidad</th><th class="right">Precio</th><th class="right">Total</th></tr>
    </thead>
    <tbody>${rows}</tbody>
  </table>
  <section class="totals">
    <div class="row"><span>Base</span><strong>${formatMoney(document.subtotal)}</strong></div>
    <div class="row"><span>Impuestos incluidos</span><strong>${document.taxIncluded ? "Si" : "No"}</strong></div>
    <div class="row total"><span>Total</span><strong>${formatMoney(document.total)}</strong></div>
  </section>
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

  if (nextConfig.ticketPrinterDriver === "ESCPOS_RAW") {
    try {
      const shouldOpenDrawer = shouldOpenCashDrawerForTicket(nextConfig, ticket);
      const ticketBuffer = buildTicketBuffer(ticket);
      for (let copy = 0; copy < route.copies; copy += 1) {
        const copyBuffer = copy === 0 && shouldOpenDrawer && nextConfig.cashDrawerConnection === "PRINTER"
          ? Buffer.concat([buildCashDrawerBuffer(), ticketBuffer])
          : ticketBuffer;
        await sendTicketPrinterRawBuffer({ ...nextConfig, ticketPrinterName: printerName }, copyBuffer);
      }
      if (shouldOpenDrawer && nextConfig.cashDrawerConnection !== "PRINTER") {
        await openCashDrawerWithConfig(nextConfig);
      }
      return { ok: true };
    } catch (error) {
      return structuredError("ESCPOS_NOT_AVAILABLE", error instanceof Error ? error.message : "Error ESC/POS");
    }
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
    await new Promise((resolve, reject) => {
      printWindow.webContents.print({ silent: true, deviceName: printerName, copies: route.copies, printBackground: true }, (success, failureReason) => {
        if (success) {
          resolve();
          return;
        }
        reject(new Error(failureReason || "PRINT_FAILED"));
      });
    });
    if (shouldOpenCashDrawerForTicket(nextConfig, ticket) && nextConfig.cashDrawerConnection !== "NONE") {
      await openCashDrawerWithConfig(nextConfig);
    }
    return { ok: true };
  } catch (error) {
    return structuredError("PRINT_FAILED", error instanceof Error ? error.message : "Error de impresion");
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

ipcMain.handle("tpv:close-application", () => {
  app.quit();
});

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

ipcMain.handle("tpv:hardware:print-a4-document", (_event, document, config) => printA4Document(document, config));

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

app.whenReady().then(createWindow);

app.on("window-all-closed", () => {
  app.quit();
});

app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createWindow();
  }
});
