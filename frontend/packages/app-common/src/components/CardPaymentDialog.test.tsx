import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { CardPaymentDialog, activateCardPaymentFocusTrap, cardPaymentKeyAction } from "./CardPaymentDialog";

describe("CardPaymentDialog", () => {
  it("shows the waiting state without retry controls", () => {
    const html = renderToStaticMarkup(<CardPaymentDialog totalCents={1234} status="PENDING" submitting message="Esperando" onCancel={vi.fn()} onRetry={vi.fn()} />);
    expect(html).toContain("Procesando pago con tarjeta");
    expect(html).toContain("12,34");
    expect(html).not.toContain("Reintentar");
  });

  it("offers a safe new operation for a declined payment", () => {
    const html = renderToStaticMarkup(<CardPaymentDialog totalCents={1234} status="DECLINED" submitting={false} message="Denegada" onCancel={vi.fn()} onRetry={vi.fn()} />);
    expect(html).toContain("Denegada");
    expect(html).toContain("Nueva operación");
  });

  it("requires review after timeout instead of offering retry", () => {
    const html = renderToStaticMarkup(<CardPaymentDialog totalCents={1234} status="TIMEOUT" submitting={false} message="Comprueba el datafono" onCancel={vi.fn()} onRetry={vi.fn()} />);
    expect(html).toContain("Comprueba el datafono");
    expect(html).toContain("Consultar estado");
    expect(html).not.toContain("Nueva operación");
  });

  it("maps Escape only to a safe cancel", () => {
    expect(cardPaymentKeyAction("Escape", false)).toBe("cancel");
    expect(cardPaymentKeyAction("Escape", true)).toBeNull();
    expect(cardPaymentKeyAction("Enter", false)).toBeNull();
  });

  it("installs the shared focus trap and restores prior focus", () => {
    let listener: ((event: { key: string; shiftKey: boolean; preventDefault: () => void }) => void) | undefined;
    const previous = { focus: vi.fn() };
    const first = { focus: vi.fn() };
    const root = { querySelectorAll: vi.fn(() => [first]), contains: vi.fn(() => false), addEventListener: vi.fn((_type, fn) => { listener = fn; }), removeEventListener: vi.fn((_type, fn) => { if (listener === fn) listener = undefined; }) };
    const cleanup = activateCardPaymentFocusTrap(root, { activeElement: previous });
    expect(first.focus).toHaveBeenCalledOnce();
    cleanup();
    expect(previous.focus).toHaveBeenCalledOnce();
    expect(listener).toBeUndefined();
  });
});
