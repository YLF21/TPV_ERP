import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import type { ReactElement, ReactNode } from "react";
import {
  CashPaymentResultContent,
  CashPaymentResultDialog,
  activateCashResultFocusTrap,
  focusTrapTarget,
} from "./CashPaymentResultDialog";

const tpvCss = readFileSync(resolve(process.cwd(), "packages/app-common/src/styles/tpv.css"), "utf8");

function findButton(node: ReactNode): ReactElement<{ onClick?: () => void; autoFocus?: boolean }> | null {
  if (!node || typeof node !== "object" || !("props" in node)) return null;
  const element = node as ReactElement<{ children?: ReactNode; onClick?: () => void; autoFocus?: boolean }>;
  if (element.type === "button") return element;
  const children = Array.isArray(element.props.children) ? element.props.children : [element.props.children];
  for (const child of children) {
    const button = findButton(child);
    if (button) return button;
  }
  return null;
}

describe("CashPaymentResultDialog", () => {
  it("uses the approved compact rectangular ERP result layout", () => {
    expect(tpvCss).toMatch(/\.cash-payment-result-dialog\s*{[^}]*width:\s*min\(420px,\s*calc\(100vw - 32px\)\)\s*!important;[^}]*padding:\s*0\s*!important;[^}]*border:\s*1px solid var\(--tpv-v3-line\)\s*!important;[^}]*border-radius:\s*4px\s*!important;/s);
    expect(tpvCss).toMatch(/\.cash-payment-result-dialog\s*>\s*header\s*{[^}]*min-height:\s*38px;[^}]*border-bottom:\s*1px solid var\(--tpv-v3-line\);/s);
    expect(tpvCss).toMatch(/\.cash-payment-result-dialog \.cash-payment-summary\s*>\s*div\s*{[^}]*min-height:\s*34px;[^}]*border-radius:\s*3px;/s);
    expect(tpvCss).toMatch(/\.cash-payment-result-dialog \.cash-payment-summary strong\s*{[^}]*font-size:\s*16px;[^}]*font-variant-numeric:\s*tabular-nums;/s);
    expect(tpvCss).toMatch(/\.cash-payment-result-dialog \.cash-payment-summary span\s*{[^}]*font-weight:\s*800;/s);
    expect(tpvCss).toMatch(/\.cash-payment-result-dialog \.cash-payment-actions button\s*{[^}]*min-height:\s*34px;[^}]*border-radius:\s*3px;/s);
  });

  it("shows the completed cash payment summary and finish action", () => {
    const html = renderToStaticMarkup(
      <CashPaymentResultDialog
        ticketNumber="T-0042"
        totalCents={1543}
        receivedCents={2000}
        changeCents={457}
        onFinish={vi.fn()}
      />,
    );

    expect(html).toContain('role="dialog"');
    expect(html).toContain('aria-modal="true"');
    expect(html).toContain('aria-labelledby="cash-payment-result-title"');
    expect(html).toContain('autofocus=""');
    expect(html).toContain("Pago completado");
    expect(html).toContain("T-0042");
    expect(html).toContain("Total");
    expect(html).toContain("15,43");
    expect(html).toContain("Dinero recibido");
    expect(html).toContain("20,00");
    expect(html).toContain("Cambio");
    expect(html).toContain("4,57");
    expect(html).toContain("Finalizar");
  });

  it("wires the finish button to onFinish", () => {
    const onFinish = vi.fn();
    const content = CashPaymentResultContent({
      ticketNumber: "T-0042",
      totalCents: 1543,
      receivedCents: 2000,
      changeCents: 457,
      onFinish,
    });

    const button = findButton(content);
    expect(button?.props.autoFocus).toBe(true);
    button?.props.onClick?.();
    expect(onFinish).toHaveBeenCalledOnce();
  });

  it("shows card metadata without cash-only received and change rows", () => {
    const html = renderToStaticMarkup(<CashPaymentResultDialog ticketNumber="T-9" totalCents={1234} method="Tarjeta" authorization="A-1" reference="R-1" onFinish={vi.fn()} />);
    expect(html).toContain("Método");
    expect(html).toContain("Autorización");
    expect(html).toContain("Referencia");
    expect(html).not.toContain("Dinero recibido");
    expect(html).not.toContain("Cambio");
  });

  it("wraps focus at both ends of the modal", () => {
    const first = {} as HTMLElement;
    const middle = {} as HTMLElement;
    const last = {} as HTMLElement;
    const focusables = [first, middle, last];

    expect(focusTrapTarget(focusables, last, false)).toBe(first);
    expect(focusTrapTarget(focusables, first, true)).toBe(last);
    expect(focusTrapTarget(focusables, middle, false)).toBeNull();
  });

  it("installs, cycles, and cleans up the modal focus trap", () => {
    type TrapRoot = Parameters<typeof activateCashResultFocusTrap>[0];
    type TrapListener = Parameters<TrapRoot["addEventListener"]>[1];
    type TrapKeyEvent = Parameters<TrapListener>[0];
    let listener: TrapListener | undefined;
    const doc = { activeElement: null as unknown };
    const previous = { focus: vi.fn(() => { doc.activeElement = previous; }) };
    const first = { focus: vi.fn(() => { doc.activeElement = first; }) };
    const last = { focus: vi.fn(() => { doc.activeElement = last; }) };
    doc.activeElement = previous;
    const root = {
      querySelectorAll: vi.fn(() => [first, last]),
      contains: vi.fn(() => false),
      addEventListener: vi.fn((_type: string, callback: TrapListener) => { listener = callback; }),
      removeEventListener: vi.fn((_type: string, callback: TrapListener) => {
        if (listener === callback) listener = undefined;
      }),
    };

    const cleanup = activateCashResultFocusTrap(root, doc);
    expect(first.focus).toHaveBeenCalledOnce();
    expect(root.addEventListener).toHaveBeenCalledWith("keydown", expect.any(Function));

    doc.activeElement = last;
    const forwardPreventDefault = vi.fn();
    const forward: TrapKeyEvent = { key: "Tab", shiftKey: false, preventDefault: () => forwardPreventDefault() };
    listener?.(forward);
    expect(forwardPreventDefault).toHaveBeenCalledOnce();
    expect(first.focus).toHaveBeenCalledTimes(2);

    doc.activeElement = first;
    const backwardPreventDefault = vi.fn();
    const backward: TrapKeyEvent = { key: "Tab", shiftKey: true, preventDefault: () => backwardPreventDefault() };
    listener?.(backward);
    expect(backwardPreventDefault).toHaveBeenCalledOnce();
    expect(last.focus).toHaveBeenCalledOnce();

    cleanup();
    expect(root.removeEventListener).toHaveBeenCalledWith("keydown", expect.any(Function));
    expect(listener).toBeUndefined();
    expect(previous.focus).toHaveBeenCalledOnce();
  });
});
