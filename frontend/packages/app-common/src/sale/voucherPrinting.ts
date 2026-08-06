import { getHardwareBridge } from "../hardware/hardware";
import type { HardwareBridge, TicketPrintRequest } from "../hardware/hardware";
import type { LocaleCode, TerminalContext } from "../types";
import type { TicketPrintOutcome } from "./ticketPrinting";

export type IssuedVoucherPrintSnapshot = {
  code: string;
  amount: number | string;
  issuedAt: string;
  originTicketNumber: string;
};

const copy = {
  es: { title: "VALE DE DEVOLUCIÓN", origin: "Ticket de origen" },
  en: { title: "REFUND VOUCHER", origin: "Original ticket" },
  zh: { title: "退款代金券", origin: "原始小票" },
} as const;

export function issuedVoucherPrintRequest(
  voucher: IssuedVoucherPrintSnapshot,
  terminal: TerminalContext,
  locale: LocaleCode,
): TicketPrintRequest {
  const amount = Number(voucher.amount);
  const text = copy[locale];
  return {
    documentNumber: voucher.code,
    storeName: terminal.storeName,
    terminalCode: terminal.terminalCode,
    issuedAt: voucher.issuedAt,
    lines: [{
      name: `${text.title}\n${text.origin}: ${voucher.originTicketNumber}`,
      quantity: 1,
      price: amount,
      total: amount,
    }],
    payments: [],
    total: amount,
  };
}

export async function outputIssuedVoucher(
  voucher: IssuedVoucherPrintSnapshot,
  terminal: TerminalContext,
  locale: LocaleCode,
  hardware: HardwareBridge = getHardwareBridge(),
): Promise<TicketPrintOutcome> {
  try {
    const result = await hardware.printTicket(
      issuedVoucherPrintRequest(voucher, terminal, locale),
    );
    return result.ok
      ? { status: "PRINTED" }
      : { status: "FAILED", technicalMessage: result.message };
  } catch (failure) {
    return {
      status: "FAILED",
      technicalMessage: failure instanceof Error ? failure.message : undefined,
    };
  }
}
