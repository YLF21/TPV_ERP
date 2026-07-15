function resolveTicketPrintRoute(config = {}) {
  const route = (config.documentPrintRoutes || []).find((item) => item.documentType === "TICKET");
  const routePrinter = String(route?.printerName || "").trim();
  const legacyPrinter = String(config.ticketPrinterName || "").trim();
  return {
    printerName: routePrinter || legacyPrinter,
    copies: Math.max(1, Number.isFinite(Number(route?.copies)) ? Math.trunc(Number(route.copies)) : 1),
    printAutomatically: route?.printAutomatically !== false
  };
}

function buildTicketCopyBuffers(ticketBuffer, copies, drawerBuffer) {
  return Array.from({ length: copies }, (_, index) => (
    index === 0 && drawerBuffer ? Buffer.concat([drawerBuffer, ticketBuffer]) : ticketBuffer
  ));
}

function withTicketPrinterRoute(config, route) {
  return { ...config, ticketPrinterName: route.printerName };
}

function resolveExternalDrawerAction(shouldOpenDrawer, connection, openDrawer) {
  return shouldOpenDrawer && connection !== "PRINTER" && connection !== "NONE"
    ? openDrawer
    : undefined;
}

async function executeWindowsTicketPrint({
  webContents,
  printerName,
  copies,
  openDrawer,
  structuredError
}) {
  try {
    await new Promise((resolve, reject) => {
      webContents.print({
        silent: true,
        deviceName: printerName,
        copies,
        printBackground: true
      }, (success, failureReason) => {
        if (success) resolve();
        else reject(new Error(failureReason || "PRINT_FAILED"));
      });
    });
    if (openDrawer) await openDrawer();
    return { ok: true };
  } catch (error) {
    return structuredError("PRINT_FAILED", error instanceof Error ? error.message : "Error de impresion");
  }
}

async function executeEscposTicketPrint({
  sendBuffer,
  ticketBuffer,
  drawerBuffer,
  copies,
  openExternalDrawer,
  structuredError
}) {
  try {
    for (const copyBuffer of buildTicketCopyBuffers(ticketBuffer, copies, drawerBuffer)) {
      await sendBuffer(copyBuffer);
    }
    if (openExternalDrawer) await openExternalDrawer();
    return { ok: true };
  } catch (error) {
    return structuredError("ESCPOS_NOT_AVAILABLE", error instanceof Error ? error.message : "Error ESC/POS");
  }
}

module.exports = {
  buildTicketCopyBuffers,
  executeEscposTicketPrint,
  executeWindowsTicketPrint,
  resolveExternalDrawerAction,
  resolveTicketPrintRoute,
  withTicketPrinterRoute
};
