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

type RenderedDocumentResponse = {
  renderedPdf: { contentType: "application/pdf"; base64: string };
  ticketRenderedImage: { contentType: "image/png"; base64: string } | null;
  fileName?: string;
  template?: { code?: string; sha256?: string };
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
    const rendered = await request<RenderedDocumentResponse>(
      `/cash/receipts/withdrawals/${encodeURIComponent(movementId)}/print-document`,
      { token },
    );
    if (!rendered.renderedPdf || !rendered.ticketRenderedImage) {
      throw new Error("cash_withdrawal_rendered_document_missing");
    }
    const config = await hardware.getHardwareConfig();
    const result = await hardware.printTicket({
      requireRenderedDocument: true,
      documentNumber: rendered.fileName ?? `${t("sale.cashWithdrawal.receiptTitle")} ${movementId.slice(0, 12)}`,
      storeName: terminal.storeName,
      terminalCode: terminal.terminalCode,
      issuedAt: new Date().toISOString(),
      lines: [],
      payments: [],
      total: 0,
      renderedPdf: rendered.renderedPdf,
      documentRaster: `data:${rendered.ticketRenderedImage.contentType};base64,${rendered.ticketRenderedImage.base64}`,
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
