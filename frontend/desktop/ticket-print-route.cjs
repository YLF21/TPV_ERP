function resolveTicketPrintRoute(config = {}) {
  const route = (config.documentPrintRoutes || []).find((item) => item.documentType === "TICKET");
  return {
    printerName: String(route?.printerName || config.ticketPrinterName || ""),
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

module.exports = { buildTicketCopyBuffers, resolveTicketPrintRoute, withTicketPrinterRoute };
