import { describe, expect, it } from "vitest";
import printerHealth from "./ticket-printer-health.cjs";

const { ticketPrinterHealthFromPrinters, unavailableTicketPrinterHealth } = printerHealth;

const config = {
  ticketPrinterConnection: "WINDOWS_PRINTER",
  ticketPrinterName: "Legacy",
  documentPrintRoutes: [{
    documentType: "TICKET",
    printerName: "RP-12N (copy 1)",
    printAutomatically: true
  }]
};

describe("ticket printer health", () => {
it("reports the configured Windows ticket queue as ready", () => {
  expect(ticketPrinterHealthFromPrinters(config, [
    { name: "RP-12N (copy 1)", displayName: "RP-12N" }
  ])).toEqual({
    status: "READY",
    printerName: "RP-12N (copy 1)"
  });
});

it("reports a missing configured queue without falling back to another printer", () => {
  expect(ticketPrinterHealthFromPrinters(config, [
    { name: "Microsoft Print to PDF", displayName: "Microsoft Print to PDF" }
  ])).toEqual({
    status: "NOT_FOUND",
    printerName: "RP-12N (copy 1)"
  });
});

it("does not require a Windows queue when automatic printing is disabled", () => {
  expect(ticketPrinterHealthFromPrinters({
    ...config,
    documentPrintRoutes: [{ ...config.documentPrintRoutes[0], printAutomatically: false }]
  }, []).status).toBe("DISABLED");
});

it("keeps non-Windows connections outside the Windows queue monitor", () => {
  expect(ticketPrinterHealthFromPrinters({
    ...config,
    ticketPrinterConnection: "NETWORK"
  }, []).status).toBe("UNMONITORED");
});

it("retains the technical cause when Windows cannot enumerate printers", () => {
  expect(unavailableTicketPrinterHealth(config, new Error("Spooler unavailable"))).toEqual({
    status: "UNAVAILABLE",
    printerName: "RP-12N (copy 1)",
    technicalMessage: "Spooler unavailable"
  });
});
});
