import { useState } from "react";
import { remainingPaymentCents, type AllocationKind, type PaymentSession } from "../sale/paymentOrchestration";

type Props = {
  session: PaymentSession;
  providers: string[];
  manualCardEnabled: boolean;
  onAdd: (input: { kind: AllocationKind; amountCents: number; provider?: string; reference?: string }) => void;
  onQuery: (operationId: string) => void;
  onManage?: (operationId: string) => void;
};

const statusLabel: Record<string, string> = { APPROVED: "APROBADO", DECLINED: "RECHAZADO", TIMEOUT: "RESULTADO INCIERTO", PENDING: "PROCESANDO", READY: "PREPARADO", ERROR: "ERROR", CANCELLED: "CANCELADO" };
const money = (cents: number) => (cents / 100).toLocaleString("es-ES", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export function PaymentAllocationPanel({ session, providers, manualCardEnabled, onAdd, onQuery, onManage }: Props) {
  const remaining = remainingPaymentCents(session);
  const [amount, setAmount] = useState(String(remaining / 100));
  const amountCents = Math.round(Number(amount.replace(",", ".")) * 100);
  return <section className="payment-allocation-panel" aria-label="Cobro dividido">
    <h3>Cobro dividido</h3>
    <strong>Pendiente: {money(remaining)}</strong>
    <ul>{session.allocations.map((allocation) => <li key={allocation.idempotencyKey}>
      <span>{allocation.provider ?? (allocation.kind === "CASH" ? "Efectivo" : "Tarjeta manual")}</span>{" · "}
      <span>{money(allocation.amountCents)}</span>{" · "}<b>{statusLabel[allocation.status]}</b>
      {allocation.authorization && <span>{` · ${allocation.authorization}`}</span>}
      {allocation.message && <span>{` · ${allocation.message}`}</span>}
      {(allocation.status === "TIMEOUT" || allocation.status === "PENDING") && allocation.operationId &&
        <button type="button" onClick={() => onQuery(allocation.operationId!)}>Consultar estado</button>}
      {allocation.operationId && onManage && <button type="button" onClick={() => onManage(allocation.operationId!)}>Gestionar operación</button>}
    </li>)}</ul>
    {remaining > 0 && <div>
      <label>Importe <input value={amount} onChange={(event) => setAmount(event.currentTarget.value)} /></label>
      <button type="button" disabled={amountCents <= 0 || amountCents > remaining} onClick={() => onAdd({ kind: "CASH", amountCents })}>Efectivo</button>
      {manualCardEnabled && <button type="button" disabled={amountCents <= 0 || amountCents > remaining} onClick={() => { const reference=globalThis.prompt?.("Referencia obligatoria de la tarjeta manual")?.trim(); if(reference)onAdd({ kind: "MANUAL_CARD", amountCents, reference }); }}>Tarjeta manual</button>}
      {providers.map((provider) => <button key={provider} type="button" disabled={amountCents <= 0 || amountCents > remaining} onClick={() => onAdd({ kind: "INTEGRATED_CARD", amountCents, provider })}>{provider}</button>)}
    </div>}
  </section>;
}
