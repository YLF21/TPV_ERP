import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { ManualCardReferenceDialog, PaymentAllocationPanel, manualCardDialogState } from "./PaymentAllocationPanel";
import type { PaymentSession } from "../sale/paymentOrchestration";
import { createTranslator } from "../i18n/LocalizedMessages";

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
    expect(html).toContain("El proveedor rechazó la operación");
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

  it.each([
    ["es", "Cobro pendiente", "Iniciar cobro pendiente", "Cobro dividido", "Pendiente: 7,00"],
    ["en", "Pending payment", "Start pending payment", "Split payment", "Remaining: 7.00"],
    ["zh", "待处理付款", "开始待处理付款", "分拆支付", "待付: 7.00"],
  ] as const)("presents pending payment in %s without legacy split-payment copy", (locale, title, start, legacyTitle, remaining) => {
    const html = renderToStaticMarkup(<PaymentAllocationPanel locale={locale} session={session} providers={[]} manualCardEnabled onAdd={vi.fn()} onQuery={vi.fn()} />);
    expect(html).toContain(title);
    expect(html).not.toContain(legacyTitle);
    expect(html).toContain(remaining);
    expect(createTranslator(locale)("payment.split.start")).toBe(start);
  });

  it("shows compensation explicitly and offers no new tender", () => {
    const compensating = { ...session, status: "COMPENSATION_REQUIRED" as const };
    const html = renderToStaticMarkup(<PaymentAllocationPanel locale="es" session={compensating} providers={["PAYTEF"]} manualCardEnabled onAdd={vi.fn()} onQuery={vi.fn()} />);
    expect(html).toContain("Compensación obligatoria");
    expect(html).not.toContain(">Efectivo<");
    expect(html).not.toContain(">Tarjeta manual<");
    expect(html).not.toContain(">PAYTEF</button>");
  });

  it("renders an accessible manual-card reference dialog without using a browser prompt", () => {
    const source = PaymentAllocationPanel.toString();
    expect(source).not.toContain("prompt");

    const html = renderToStaticMarkup(<ManualCardReferenceDialog locale="es" reference="" onReferenceChange={vi.fn()} onCancel={vi.fn()} onConfirm={vi.fn()} />);
    expect(html).toContain('role="dialog"');
    expect(html).toContain('aria-modal="true"');
    expect(html).toContain("Referencia obligatoria de la tarjeta manual");
    expect(html).toContain("Confirmar");
    expect(html).toContain("Cancelar");
    expect(html).toContain("disabled");
  });

  it("keeps the manual reference ephemeral and clears it on cancel and submit", () => {
    expect(manualCardDialogState({ open: false, reference: "" }, { type: "open" })).toEqual({ open: true, reference: "" });
    expect(manualCardDialogState({ open: true, reference: "  REF-42  " }, { type: "cancel" })).toEqual({ open: false, reference: "" });
    expect(manualCardDialogState({ open: true, reference: "REF-42" }, { type: "submit" })).toEqual({ open: false, reference: "" });
  });

  it("uses a middle dot as the allocation separator", () => {
    const html = renderToStaticMarkup(<PaymentAllocationPanel locale="es" session={session} providers={[]} manualCardEnabled={false} onAdd={vi.fn()} onQuery={vi.fn()} />);
    expect(html).toContain(" · ");
    expect(html).not.toContain("路");
  });

  it("does not expose a Spanish idempotency diagnostic when the interface is Chinese", () => {
    const recovered = {
      ...session,
      allocations: [{ ...session.allocations[0], message: "Operacion recuperada por idempotencia" }],
    };
    const html = renderToStaticMarkup(<PaymentAllocationPanel locale="zh" session={recovered} providers={[]} manualCardEnabled={false} onAdd={vi.fn()} onQuery={vi.fn()} />);
    expect(html).toContain("已安全恢复原支付操作");
    expect(html).not.toContain("Operacion recuperada por idempotencia");
  });
});
