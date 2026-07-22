import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { PaymentOperationPanel, sanitizeReceiptText } from "./PaymentOperationPanel";
import { createTranslator } from "../i18n/LocalizedMessages";

describe("PaymentOperationPanel", () => {
  it("gates void and refund by both provider capability and user permission", () => {
    const base = { t: createTranslator("es"), operation: { id: "op", status: "APPROVED", amount: "12.00", provider: "PAYTEF", authorization: "****1234" }, events: [], onQuery: vi.fn(), onPrintReceipt: vi.fn() };
    const allowed = renderToStaticMarkup(<PaymentOperationPanel {...base} capabilities={["QUERY", "VOID", "REFUND", "RECEIPT"]} permissions={["PAYMENT_TERMINAL_VOID", "PAYMENT_TERMINAL_REFUND"]} onVoid={vi.fn()} onRefund={vi.fn()} />);
    expect(allowed).toContain("Anular");
    expect(allowed).toContain("Reembolsar");
    const denied = renderToStaticMarkup(<PaymentOperationPanel {...base} capabilities={["QUERY", "VOID", "REFUND"]} permissions={[]} onVoid={vi.fn()} onRefund={vi.fn()} />);
    expect(denied).not.toContain("Anular");
    expect(denied).not.toContain("Reembolsar");
  });

  it("requires resolving an uncertain charge before offering void or refund", () => {
    const html = renderToStaticMarkup(<PaymentOperationPanel
      t={createTranslator("es")}
      operation={{ id: "op-timeout", status: "TIMEOUT", amount: "10.28", provider: "GLOBAL_PAYMENTS" }}
      events={[]}
      capabilities={["QUERY", "VOID", "REFUND"]}
      permissions={["ADMIN"]}
      onQuery={vi.fn()}
      onVoid={vi.fn()}
      onRefund={vi.fn()}
      onPrintReceipt={vi.fn()}
    />);

    expect(html).toContain("Resultado incierto; consulta el estado antes de continuar");
    expect(html).toContain("Consultar estado");
    expect(html).not.toContain(">Anular<");
    expect(html).not.toContain(">Reembolsar<");
  });

  it("sanitizes receipt control characters before HardwareBridge printing", () => {
    expect(sanitizeReceiptText("OK\u0000\u001b[31m\nTOTAL 12.00")).toBe("OK[31m\nTOTAL 12.00");
  });

  it("localizes provider statuses, event codes and diagnostics in Chinese", () => {
    const html = renderToStaticMarkup(<PaymentOperationPanel
      t={createTranslator("zh")}
      operation={{ id: "op-timeout", status: "TIMEOUT", amount: "10.28", provider: "GLOBAL_PAYMENTS" }}
      events={[
        { status: "PENDING", code: "RESERVED" },
        { status: "SENT", code: "GATEWAY_SEND" },
        { status: "TIMEOUT", code: "TIMEOUT", diagnostic: "Operacion simulada timeout" },
      ]}
      capabilities={["QUERY", "VOID", "REFUND", "RECEIPT"]}
      permissions={["ADMIN"]}
      onQuery={vi.fn()}
      onVoid={vi.fn()}
      onRefund={vi.fn()}
      onPrintReceipt={vi.fn()}
    />);

    expect(html).toContain("支付操作");
    expect(html).toContain("已创建支付预留");
    expect(html).toContain("已发送至支付服务商");
    expect(html).toContain("模拟器等待超时");
    expect(html).not.toContain("PENDING");
    expect(html).not.toContain("GATEWAY_SEND");
    expect(html).not.toContain("Operacion simulada timeout");
  });
});
