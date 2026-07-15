function resolveTicketPrintRoute(config = {}) {
  const route = (config.documentPrintRoutes || []).find((item) => item.documentType === "TICKET");
  return {
    printerName: String(route?.printerName || config.ticketPrinterName || ""),
    copies: Math.max(1, Number.isFinite(Number(route?.copies)) ? Math.trunc(Number(route.copies)) : 1),
    printAutomatically: route?.printAutomatically !== false
  };
}

module.exports = { resolveTicketPrintRoute };
