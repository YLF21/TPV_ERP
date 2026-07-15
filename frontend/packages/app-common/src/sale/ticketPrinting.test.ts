import { describe, expect, it, vi } from "vitest";
import { defaultHardwareConfig } from "../hardware/hardware";
import type { HardwareBridge } from "../hardware/hardware";
import type { TerminalContext } from "../types";
import {
  printConfirmedTicketAutomatically,
  retryConfirmedTicketPrint
} from "./ticketPrinting";
import type { ConfirmedTicketPrintSnapshot } from "./ticketPrinting";

const snapshot: ConfirmedTicketPrintSnapshot = {
  documentId: "document-1",
  documentNumber: "T-1",
  issuedAt: "2026-07-15T10:15:30Z",
  lines: [{ name: "Cafe", quantity: "2", price: "3.5", total: "7" }],
  payments: [{ method: "EFECTIVO", amount: "7" }],
  total: "7"
};

const terminal: TerminalContext = {
  storeName: "Tienda",
  terminalCode: "CAJA-1"
};

function hardwareConfig(printAutomatically: boolean) {
  return {
    ...defaultHardwareConfig,
    documentPrintRoutes: defaultHardwareConfig.documentPrintRoutes.map((route) =>
      route.documentType === "TICKET" ? { ...route, printAutomatically } : route
    )
  };
}

describe("confirmed ticket printing", () => {
  it("prints the authoritative snapshot when automatic ticket printing is enabled", async () => {
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(hardwareConfig(true)),
      printTicket
    } as unknown as HardwareBridge;

    const result = await printConfirmedTicketAutomatically(snapshot, terminal, hardware);

    expect(result).toEqual({ status: "PRINTED" });
    expect(printTicket).toHaveBeenCalledWith({
      documentNumber: "T-1",
      storeName: "Tienda",
      terminalCode: "CAJA-1",
      issuedAt: "2026-07-15T10:15:30Z",
      lines: [{ name: "Cafe", quantity: 2, price: 3.5, total: 7 }],
      payments: [{ method: "EFECTIVO", amount: 7 }],
      total: 7
    }, expect.objectContaining({ documentPrintRoutes: expect.any(Array) }));
  });

  it("skips automatic printing when the ticket route disables it", async () => {
    const printTicket = vi.fn();
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(hardwareConfig(false)),
      printTicket
    } as unknown as HardwareBridge;

    await expect(printConfirmedTicketAutomatically(snapshot, terminal, hardware))
      .resolves.toEqual({ status: "SKIPPED" });
    expect(printTicket).not.toHaveBeenCalled();
  });

  it("returns a structured failure when hardware rejects the ticket", async () => {
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(hardwareConfig(true)),
      printTicket: vi.fn().mockResolvedValue({
        ok: false,
        code: "PRINT_FAILED",
        message: "printer offline"
      })
    } as unknown as HardwareBridge;

    await expect(printConfirmedTicketAutomatically(snapshot, terminal, hardware))
      .resolves.toEqual({ status: "FAILED", technicalMessage: "printer offline" });
  });

  it("converts a rejected hardware call into a structured failure", async () => {
    const hardware = {
      getHardwareConfig: vi.fn().mockRejectedValue(new Error("bridge unavailable")),
      printTicket: vi.fn()
    } as unknown as HardwareBridge;

    await expect(printConfirmedTicketAutomatically(snapshot, terminal, hardware))
      .resolves.toEqual({ status: "FAILED", technicalMessage: "bridge unavailable" });
  });

  it("retries printing even when automatic ticket printing is disabled", async () => {
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    const hardware = {
      getHardwareConfig: vi.fn().mockResolvedValue(hardwareConfig(false)),
      printTicket
    } as unknown as HardwareBridge;

    await expect(retryConfirmedTicketPrint(snapshot, terminal, hardware))
      .resolves.toEqual({ status: "PRINTED" });
    expect(printTicket).toHaveBeenCalledOnce();
  });
});
