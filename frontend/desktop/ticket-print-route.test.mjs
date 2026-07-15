import { createRequire } from "node:module";
import { describe, expect, it } from "vitest";

const require = createRequire(import.meta.url);
const { resolveTicketPrintRoute } = require("./ticket-print-route.cjs");

describe("ticket print route", () => {
  it("uses the configured TICKET route printer and copies", () => {
    expect(resolveTicketPrintRoute({
      ticketPrinterName: "Legacy",
      documentPrintRoutes: [{
        documentType: "TICKET",
        printerName: "Caja 1",
        copies: 2,
        printAutomatically: true
      }]
    })).toEqual({ printerName: "Caja 1", copies: 2, printAutomatically: true });
  });

  it("falls back to the legacy printer and normalizes copies", () => {
    expect(resolveTicketPrintRoute({
      ticketPrinterName: "Legacy",
      documentPrintRoutes: [{ documentType: "TICKET", printerName: "", copies: 0, printAutomatically: true }]
    })).toEqual({ printerName: "Legacy", copies: 1, printAutomatically: true });
  });
});
