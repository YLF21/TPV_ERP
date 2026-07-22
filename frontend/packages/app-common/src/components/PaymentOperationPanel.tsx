import { localizePaymentDiagnostic, localizePaymentEventCode, localizePaymentStatus, type PaymentTranslator } from "../i18n/PaymentMessages";

export type PaymentOperationView = { id: string; status: string; amount: string | number; provider: string; authorization?: string | null; reference?: string | null };
export type PaymentOperationEvent = { status: string; code?: string | null; diagnostic?: string | null; createdAt?: string };

type Props = {
  t: PaymentTranslator;
  operation: PaymentOperationView;
  events: PaymentOperationEvent[];
  capabilities: string[];
  permissions: string[];
  onQuery: () => void;
  onVoid?: () => void;
  onRefund?: () => void;
  onPrintReceipt: () => void;
};

export function sanitizeReceiptText(text: string) {
  return text.replace(/[\u0000-\u0009\u000b-\u001f\u007f]/g, "").slice(0, 16_384);
}

export function PaymentOperationPanel({ t, operation, events, capabilities, permissions, onQuery, onVoid, onRefund, onPrintReceipt }: Props) {
  const can = (capability: string, permission: string) => capabilities.includes(capability) && (permissions.includes("ADMIN") || permissions.includes(permission));
  const canVoid = operation.status === "APPROVED";
  const canRefund = operation.status === "APPROVED" || operation.status === "PARTIALLY_REFUNDED";
  const requiresStatusQuery = ["PENDING", "SENT", "TIMEOUT", "REVIEW_REQUIRED"].includes(operation.status);
  return <section className="payment-operation-panel" aria-label={t("payment.operation.ariaLabel")}>
    <h3>{t("payment.operation.title")} {operation.id}</h3>
    <p>{operation.provider} · {operation.amount} · {localizePaymentStatus(t, operation.status)} {operation.authorization ?? ""}</p>
    {requiresStatusQuery && <p role="status">{t("payment.operation.diagnostic.uncertainResult")}</p>}
    {capabilities.includes("QUERY") && <button type="button" onClick={onQuery}>{t("payment.operation.query")}</button>}
    {canVoid && can("VOID", "PAYMENT_TERMINAL_VOID") && onVoid && <button type="button" onClick={onVoid}>{t("payment.operation.void")}</button>}
    {canRefund && can("REFUND", "PAYMENT_TERMINAL_REFUND") && onRefund && <button type="button" onClick={onRefund}>{t("payment.operation.refund")}</button>}
    {capabilities.includes("RECEIPT") && <button type="button" onClick={onPrintReceipt}>{t("payment.operation.reprintReceipt")}</button>}
    <ol aria-label={t("payment.operation.history")}>{events.map((event, index) => <li key={`${event.createdAt ?? "event"}-${index}`}>
      {localizePaymentStatus(t, event.status)} · {localizePaymentEventCode(t, event.code)}
      {event.diagnostic && <> · {localizePaymentDiagnostic(t, event.diagnostic, event.status)}</>}
    </li>)}</ol>
  </section>;
}
