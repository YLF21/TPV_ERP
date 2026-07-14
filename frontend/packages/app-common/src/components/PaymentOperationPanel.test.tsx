import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { PaymentOperationPanel, sanitizeReceiptText } from "./PaymentOperationPanel";

describe("PaymentOperationPanel", () => {
  it("gates void and refund by both provider capability and user permission", () => {
    const base = { operation: { id: "op", status: "APPROVED", amount: "12.00", provider: "PAYTEF", authorization: "****1234" }, events: [], onQuery: vi.fn(), onPrintReceipt: vi.fn() };
    const allowed = renderToStaticMarkup(<PaymentOperationPanel {...base} capabilities={["QUERY", "VOID", "REFUND", "RECEIPT"]} permissions={["PAYMENT_TERMINAL_VOID", "PAYMENT_TERMINAL_REFUND"]} onVoid={vi.fn()} onRefund={vi.fn()} />);
    expect(allowed).toContain("Anular");
    expect(allowed).toContain("Reembolsar");
    const denied = renderToStaticMarkup(<PaymentOperationPanel {...base} capabilities={["QUERY", "VOID", "REFUND"]} permissions={[]} onVoid={vi.fn()} onRefund={vi.fn()} />);
    expect(denied).not.toContain("Anular");
    expect(denied).not.toContain("Reembolsar");
  });

  it("sanitizes receipt control characters before HardwareBridge printing", () => {
    expect(sanitizeReceiptText("OK\u0000\u001b[31m\nTOTAL 12.00")).toBe("OK[31m\nTOTAL 12.00");
  });
});
