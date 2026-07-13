import { apiRequest } from "../api/client";
import type { HardwareBridge } from "../hardware/hardware";
import type { TerminalContext } from "../types";
import { sanitizeReceiptText, type PaymentOperationEvent, type PaymentOperationView } from "../components/PaymentOperationPanel";

type Request = <T>(path: string, options?: { method?: string; token?: string; body?: unknown }) => Promise<T>;
const path = (id: string, suffix = "") => `/payment-terminal/operations/${encodeURIComponent(id)}${suffix}`;

export const queryPaymentOperation = (id: string, token: string | undefined, request: Request = apiRequest) =>
  request<PaymentOperationView>(path(id, "/query"), { method: "POST", token });

export const loadPaymentOperationHistory = (id: string, token: string | undefined, request: Request = apiRequest) =>
  request<PaymentOperationEvent[]>(path(id, "/events"), { token });

export const voidPaymentOperation = (id: string, token: string | undefined, password: string, key: string, request: Request = apiRequest) =>
  request<PaymentOperationView>(path(id, "/void"), { token, body: { operationId: id, idempotencyKey: key, password } });

export const refundPaymentOperation = (id: string, token: string | undefined, amount: string, password: string, key: string, request: Request = apiRequest) =>
  request<PaymentOperationView>(path(id, "/refund"), { token, body: { operationId: id, idempotencyKey: key, amount, password } });

export async function printPaymentReceipt(
  id: string,
  token: string | undefined,
  terminal: Pick<TerminalContext, "storeName" | "terminalCode">,
  hardware: HardwareBridge,
  request: Request = apiRequest
) {
  const receipt = await request<{ status: string; code: string; text: string }>(path(id, "/receipt"), { token });
  const text = sanitizeReceiptText(receipt.text);
  return hardware.printTicket({
    documentNumber: id,
    storeName: terminal.storeName,
    terminalCode: terminal.terminalCode,
    issuedAt: new Date().toISOString(),
    lines: [{ name: text, quantity: 1, price: 0, total: 0 }],
    payments: [],
    total: 0
  });
}
