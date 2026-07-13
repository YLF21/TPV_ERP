export type PaymentOperationView = { id: string; status: string; amount: string | number; provider: string; authorization?: string | null; reference?: string | null };
export type PaymentOperationEvent = { status: string; code?: string | null; diagnostic?: string | null; createdAt?: string };

type Props = {
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

export function PaymentOperationPanel({ operation, events, capabilities, permissions, onQuery, onVoid, onRefund, onPrintReceipt }: Props) {
  const can = (capability: string, permission: string) => capabilities.includes(capability) && (permissions.includes("ADMIN") || permissions.includes(permission));
  return <section className="payment-operation-panel" aria-label="Operación de pago">
    <h3>Operación {operation.id}</h3>
    <p>{operation.provider} · {operation.amount} · {operation.status} {operation.authorization ?? ""}</p>
    {capabilities.includes("QUERY") && <button type="button" onClick={onQuery}>Consultar estado</button>}
    {can("VOID", "PAYMENT_TERMINAL_VOID") && onVoid && <button type="button" onClick={onVoid}>Anular</button>}
    {can("REFUND", "PAYMENT_TERMINAL_REFUND") && onRefund && <button type="button" onClick={onRefund}>Reembolsar</button>}
    {capabilities.includes("RECEIPT") && <button type="button" onClick={onPrintReceipt}>Reimprimir recibo</button>}
    <ol>{events.map((event, index) => <li key={`${event.createdAt ?? "event"}-${index}`}>{event.status} {event.code ?? ""} {event.diagnostic ?? ""}</li>)}</ol>
  </section>;
}
