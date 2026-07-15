import { getHardwareBridge } from "../hardware/hardware";
import type { HardwareBridge, HardwareConfig, TicketPrintRequest } from "../hardware/hardware";
import type { TerminalContext } from "../types";

type NumericValue = number | string;

export type ConfirmedTicketPrintSnapshot = {
  documentId: string;
  documentNumber: string;
  issuedAt: string;
  lines: Array<{
    name: string;
    quantity: NumericValue;
    price: NumericValue;
    total: NumericValue;
  }>;
  payments: Array<{
    method: string;
    amount: NumericValue;
  }>;
  total: NumericValue;
};

export type TicketPrintOutcome = {
  status: "PRINTED" | "FAILED" | "SKIPPED";
  technicalMessage?: string;
};

function ticketPrintRequest(
  snapshot: ConfirmedTicketPrintSnapshot,
  terminal: TerminalContext
): TicketPrintRequest {
  return {
    documentNumber: snapshot.documentNumber,
    storeName: terminal.storeName,
    terminalCode: terminal.terminalCode,
    issuedAt: snapshot.issuedAt,
    lines: snapshot.lines.map((line) => ({
      name: line.name,
      quantity: Number(line.quantity),
      price: Number(line.price),
      total: Number(line.total)
    })),
    payments: snapshot.payments.map((payment) => ({
      method: payment.method,
      amount: Number(payment.amount)
    })),
    total: Number(snapshot.total)
  };
}

async function sendConfirmedTicket(
  snapshot: ConfirmedTicketPrintSnapshot,
  terminal: TerminalContext,
  hardware: HardwareBridge,
  config: HardwareConfig
): Promise<TicketPrintOutcome> {
  const result = await hardware.printTicket(ticketPrintRequest(snapshot, terminal), config);
  return result.ok
    ? { status: "PRINTED" }
    : { status: "FAILED", technicalMessage: result.message };
}

function failedOutcome(error: unknown): TicketPrintOutcome {
  return {
    status: "FAILED",
    technicalMessage: error instanceof Error ? error.message : String(error)
  };
}

export async function printConfirmedTicketAutomatically(
  snapshot: ConfirmedTicketPrintSnapshot,
  terminal: TerminalContext,
  hardware: HardwareBridge = getHardwareBridge()
): Promise<TicketPrintOutcome> {
  try {
    const config = await hardware.getHardwareConfig();
    const route = config.documentPrintRoutes.find((item) => item.documentType === "TICKET");
    if (route?.printAutomatically === false) return { status: "SKIPPED" };
    return await sendConfirmedTicket(snapshot, terminal, hardware, config);
  } catch (error) {
    return failedOutcome(error);
  }
}

export async function retryConfirmedTicketPrint(
  snapshot: ConfirmedTicketPrintSnapshot,
  terminal: TerminalContext,
  hardware: HardwareBridge = getHardwareBridge()
): Promise<TicketPrintOutcome> {
  try {
    return await sendConfirmedTicket(
      snapshot,
      terminal,
      hardware,
      await hardware.getHardwareConfig()
    );
  } catch (error) {
    return failedOutcome(error);
  }
}
