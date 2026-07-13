import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { PaymentAllocationPanel } from "./PaymentAllocationPanel";
import type { PaymentSession } from "../sale/paymentOrchestration";

const session: PaymentSession = {
  id: "sale-1", totalCents: 1200, status: "COLLECTING", allocations: [
    { kind: "INTEGRATED_CARD", amountCents: 500, idempotencyKey: "op-1", operationId: "op-1", provider: "PAYCOMET", status: "APPROVED", authorization: "****1234" },
    { kind: "INTEGRATED_CARD", amountCents: 700, idempotencyKey: "op-2", operationId: "op-2", provider: "PAYTEF", status: "DECLINED", message: "Denegada" }
  ]
};

describe("PaymentAllocationPanel", () => {
  it("shows previous approvals after an intermediate decline and all enabled tender choices", () => {
    const html = renderToStaticMarkup(<PaymentAllocationPanel locale="es" session={session} providers={["PAYTEF", "PAYCOMET"]} manualCardEnabled onAdd={vi.fn()} onQuery={vi.fn()} />);
    expect(html).toContain("PAYCOMET");
    expect(html).toContain("APROBADO");
    expect(html).toContain("Denegada");
    expect(html).toContain("Efectivo");
    expect(html).toContain("Tarjeta manual");
    expect(html).toContain("PAYTEF");
    expect(html).toContain("Pendiente: 7,00");
  });

  it("offers query for timeout without a new charge action on that allocation", () => {
    const timedOut = { ...session, allocations: [{ ...session.allocations[0], status: "TIMEOUT" as const }] };
    const html = renderToStaticMarkup(<PaymentAllocationPanel locale="es" session={timedOut} providers={["PAYTEF"]} manualCardEnabled={false} onAdd={vi.fn()} onQuery={vi.fn()} />);
    expect(html).toContain("Consultar estado");
    expect(html).not.toContain("Reintentar cargo");
  });

  it("renders split-payment controls in the selected locale", () => {
    const html = renderToStaticMarkup(<PaymentAllocationPanel locale="en" session={session} providers={[]} manualCardEnabled onAdd={vi.fn()} onQuery={vi.fn()} />);
    expect(html).toContain("Split payment");
    expect(html).toContain("Remaining: 7.00");
    expect(html).toContain("Cash");
    expect(html).toContain("Manual card");
    expect(html).not.toContain("Cobro dividido");
  });

  it("shows compensation explicitly and offers no new tender", () => {
    const compensating = { ...session, status: "COMPENSATION_REQUIRED" as const };
    const html = renderToStaticMarkup(<PaymentAllocationPanel locale="es" session={compensating} providers={["PAYTEF"]} manualCardEnabled onAdd={vi.fn()} onQuery={vi.fn()} />);
    expect(html).toContain("Compensación obligatoria");
    expect(html).not.toContain(">Efectivo<");
    expect(html).not.toContain(">Tarjeta manual<");
    expect(html).not.toContain(">PAYTEF</button>");
  });
});
