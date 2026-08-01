import { apiRequest } from "../api/client";
import { getHardwareBridge, type HardwareBridge } from "../hardware/hardware";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, TerminalContext } from "../types";
import type { TicketPrintOutcome } from "./ticketPrinting";

export type CashWithdrawalDenomination = {
  denomination: number;
  quantity: number;
};

export type CashWithdrawalInput = {
  terminalId: string;
  amount: number;
  comment: string;
  denominations: CashWithdrawalDenomination[];
  authorizerUsername?: string;
  authorizerPassword?: string;
};

export type CashWithdrawalMovement = {
  id: string;
  terminalId: string;
  sessionId: string;
  type: "ENTRADA" | "RETIRADA";
  amount: number;
  createdAt: string;
  userId: string;
  authorizerUserId: string;
  comment: string;
};

export type CashWithdrawalReceipt = {
  movementId: string;
  sessionId?: string | null;
  terminalId: string;
  terminalName: string;
  createdAt: string;
  userName: string;
  amount: number;
  denominations: CashWithdrawalDenomination[];
  giverSignatureLabel?: string | null;
  receiverSignatureLabel?: string | null;
  authorizerName?: string | null;
  comment?: string | null;
};

type RequestFunction = typeof apiRequest;

export function registerCashWithdrawal(
  input: CashWithdrawalInput,
  token: string,
  request: RequestFunction = apiRequest,
) {
  return request<CashWithdrawalMovement>("/cash/movements/withdrawal", {
    token,
    method: "POST",
    body: {
      terminalId: input.terminalId,
      amount: input.amount,
      comment: input.comment.trim(),
      denominations: input.denominations,
      withdrawal: true,
      ...(input.authorizerUsername?.trim()
        ? { authorizerUsername: input.authorizerUsername.trim() }
        : {}),
      ...(input.authorizerPassword
        ? { authorizerPassword: input.authorizerPassword }
        : {}),
    },
  });
}

export function registerCashEntry(
  input: CashWithdrawalInput,
  token: string,
  request: RequestFunction = apiRequest,
) {
  return request<CashWithdrawalMovement>("/cash/movements/entry", {
    token,
    method: "POST",
    body: {
      terminalId: input.terminalId,
      amount: input.amount,
      comment: input.comment.trim(),
      denominations: input.denominations,
      ...(input.authorizerUsername?.trim()
        ? { authorizerUsername: input.authorizerUsername.trim() }
        : {}),
      ...(input.authorizerPassword
        ? { authorizerPassword: input.authorizerPassword }
        : {}),
    },
  });
}

function safeReceiptText(value: string | null | undefined) {
  return String(value ?? "").replace(/[\u0000-\u0009\u000b-\u001f\u007f]/g, "").trim();
}

export async function printCashWithdrawalReceipt(
  movementId: string,
  token: string,
  terminal: Pick<TerminalContext, "storeName" | "terminalCode">,
  locale: LocaleCode = "es",
  hardware: HardwareBridge = getHardwareBridge(),
  request: RequestFunction = apiRequest,
): Promise<TicketPrintOutcome> {
  try {
    const t = createTranslator(locale);
    const receipt = await request<CashWithdrawalReceipt>(
      `/cash/receipts/withdrawals/${encodeURIComponent(movementId)}`,
      { token },
    );
    const config = await hardware.getHardwareConfig();
    const amount = Number(receipt.amount);
    const detail = [
      `${t("sale.cashWithdrawal.receiptReason")}: ${safeReceiptText(receipt.comment) || "-"}`,
      `${t("sale.cashWithdrawal.receiptOperator")}: ${safeReceiptText(receipt.userName) || "-"}`,
      `${t("sale.cashWithdrawal.receiptAuthorizer")}: ${safeReceiptText(receipt.authorizerName) || "-"}`,
    ].join(" · ");
    const denominationLines = (receipt.denominations ?? [])
      .filter((row) => Number(row.quantity) > 0 && Number(row.denomination) > 0)
      .map((row) => ({
        name: `${t("sale.cashWithdrawal.receiptDenomination")} ${Number(row.denomination).toFixed(2)}`,
        quantity: Number(row.quantity),
        price: Number(row.denomination),
        total: Number(row.denomination) * Number(row.quantity),
      }));
    const result = await hardware.printTicket({
      documentNumber: `${t("sale.cashWithdrawal.receiptTitle")} ${movementId.slice(0, 12)}`,
      storeName: terminal.storeName,
      terminalCode: terminal.terminalCode,
      issuedAt: receipt.createdAt,
      lines: denominationLines.length
        ? [{ name: detail, quantity: 1, price: 0, total: 0 }, ...denominationLines]
        : [{ name: detail, quantity: 1, price: amount, total: amount }],
      payments: [],
      total: amount,
      labels: {
        terminal: t("print.a4.terminal"),
        item: t("print.ticket.item"),
        quantity: t("print.ticket.quantity"),
        price: t("print.ticket.price"),
        total: t("print.a4.total"),
      },
    }, config);
    return result.ok
      ? { status: "PRINTED" }
      : { status: "FAILED", technicalMessage: result.message };
  } catch (error) {
    return {
      status: "FAILED",
      technicalMessage: error instanceof Error ? error.message : String(error),
    };
  }
}
