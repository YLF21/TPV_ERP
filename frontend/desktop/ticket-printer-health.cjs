function configuredTicketPrinter(config = {}) {
  const route = (config.documentPrintRoutes || []).find((item) => item.documentType === "TICKET");
  const printerName = String(route?.printerName || config.ticketPrinterName || "").trim();
  return {
    printerName,
    printAutomatically: route?.printAutomatically !== false,
    connection: config.ticketPrinterConnection || "WINDOWS_PRINTER"
  };
}

function ticketPrinterHealthFromPrinters(config, printers = []) {
  const target = configuredTicketPrinter(config);
  if (!target.printAutomatically) {
    return { status: "DISABLED", printerName: target.printerName };
  }
  if (target.connection !== "WINDOWS_PRINTER") {
    return { status: "UNMONITORED", printerName: target.printerName };
  }
  if (!target.printerName) {
    return { status: "NOT_CONFIGURED", printerName: "" };
  }

  const expected = target.printerName.toLocaleLowerCase();
  const found = printers.some((printer) => (
    String(printer.name || "").trim().toLocaleLowerCase() === expected
      || String(printer.displayName || "").trim().toLocaleLowerCase() === expected
  ));
  return found
    ? { status: "READY", printerName: target.printerName }
    : { status: "NOT_FOUND", printerName: target.printerName };
}

function unavailableTicketPrinterHealth(config, error) {
  const target = configuredTicketPrinter(config);
  return {
    status: "UNAVAILABLE",
    printerName: target.printerName,
    technicalMessage: error instanceof Error ? error.message : String(error)
  };
}

module.exports = {
  configuredTicketPrinter,
  ticketPrinterHealthFromPrinters,
  unavailableTicketPrinterHealth
};
