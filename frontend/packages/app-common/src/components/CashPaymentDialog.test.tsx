// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CashPaymentDialog, cashPaymentKeyAction } from "./CashPaymentDialog";
import { activateModalFocusTrap } from "./modalFocusTrap";

const baseProps = {
  totalCents: 1543,
  submitting: false,
  error: "",
  onCancel: vi.fn(),
  onConfirm: vi.fn(),
};

afterEach(cleanup);

describe("CashPaymentDialog", () => {
  it("starts with touch controls and can switch to the physical keyboard for this opening", () => {
    render(<CashPaymentDialog {...baseProps} totalCents={1210} initialMode="touch" />);

    expect(screen.getByRole("button", { name: "Tecla 7" })).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Usar teclado físico" }));
    expect(screen.queryByRole("button", { name: "Tecla 7" })).not.toBeInTheDocument();
  });
  it("labels and scopes the cash-entry modal", () => {
    const html = renderToStaticMarkup(<CashPaymentDialog {...baseProps} initialMode="touch" />);
    expect(html).toContain('class="cash-payment-dialog cash-payment-entry-dialog"');
    expect(html).toContain('aria-labelledby="cash-payment-title"');
    expect(html).toContain('<h2 id="cash-payment-title">Cobro en efectivo</h2>');
    expect(html).not.toContain('aria-label="Cobro en efectivo"');
  });

  it("cycles focus and restores the previously focused control", () => {
    type TrapRoot = Parameters<typeof activateModalFocusTrap>[0];
    type Listener = Parameters<TrapRoot["addEventListener"]>[1];
    let listener: Listener | undefined;
    const doc = { activeElement: null as unknown };
    const previous = { focus: vi.fn(() => { doc.activeElement = previous; }) };
    const first = { focus: vi.fn(() => { doc.activeElement = first; }) };
    const last = { focus: vi.fn(() => { doc.activeElement = last; }) };
    doc.activeElement = previous;
    const root = {
      querySelectorAll: vi.fn(() => [first, last]),
      contains: vi.fn(() => false),
      addEventListener: vi.fn((_type: string, callback: Listener) => { listener = callback; }),
      removeEventListener: vi.fn((_type: string, callback: Listener) => { if (listener === callback) listener = undefined; }),
    };
    const cleanup = activateModalFocusTrap(root, doc);
    doc.activeElement = last;
    const preventForward = vi.fn();
    listener?.({ key: "Tab", shiftKey: false, preventDefault: () => preventForward() });
    expect(first.focus).toHaveBeenCalledTimes(2);
    expect(preventForward).toHaveBeenCalledOnce();
    doc.activeElement = first;
    listener?.({ key: "Tab", shiftKey: true, preventDefault: vi.fn() });
    expect(last.focus).toHaveBeenCalledOnce();
    cleanup();
    expect(previous.focus).toHaveBeenCalledOnce();
  });

  it("preserves an auto-focused input already inside the modal", () => {
    type TrapRoot = Parameters<typeof activateModalFocusTrap>[0];
    type Listener = Parameters<TrapRoot["addEventListener"]>[1];
    const input = { focus: vi.fn() };
    const first = { focus: vi.fn() };
    const doc = { activeElement: input as unknown };
    const root = {
      querySelectorAll: vi.fn(() => [first, input]),
      contains: vi.fn((node: unknown) => node === input),
      addEventListener: vi.fn((_type: string, _callback: Listener) => undefined),
      removeEventListener: vi.fn((_type: string, _callback: Listener) => undefined),
    };

    const cleanup = activateModalFocusTrap(root, doc);
    expect(first.focus).not.toHaveBeenCalled();
    expect(input.focus).not.toHaveBeenCalled();
    cleanup();
    expect(input.focus).toHaveBeenCalledOnce();
  });
  it("shows keypad and shortcuts in touch mode with a physical keyboard switch", () => {
    const html = renderToStaticMarkup(<CashPaymentDialog {...baseProps} initialMode="touch" />);
    expect(html).toContain("Exacto");
    expect(html).toContain('aria-label="Tecla 1"');
    expect(html).toContain("Usar teclado físico");
  });

  it("hides touch controls in keyboard mode and offers the touch keyboard", () => {
    const html = renderToStaticMarkup(<CashPaymentDialog {...baseProps} initialMode="keyboard" />);
    expect(html).not.toContain("Exacto");
    expect(html).not.toContain('aria-label="Tecla 1"');
    expect(html).toContain("Mostrar teclado táctil");
  });

  it("disables every touch control while submitting", () => {
    const html = renderToStaticMarkup(<CashPaymentDialog {...baseProps} initialMode="touch" submitting />);
    expect(html.match(/<button[^>]*disabled=""/g)).toHaveLength(22);
  });

  it("keeps validation hidden while typing and warns only after confirming an insufficient amount", () => {
    const onConfirm = vi.fn();
    render(<CashPaymentDialog {...baseProps} totalCents={1210} initialMode="touch" onConfirm={onConfirm} />);
    const input = screen.getByRole("textbox", { name: "Dinero recibido" });

    fireEvent.change(input, { target: { value: "5" } });
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Confirmar cobro" }));

    expect(screen.getByRole("alertdialog", { name: "Aviso" })).toHaveTextContent("El importe recibido no cubre el total.");
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it("uses the formal empty-amount warning", () => {
    render(<CashPaymentDialog {...baseProps} totalCents={1210} initialMode="touch" />);

    fireEvent.click(screen.getByRole("button", { name: "Confirmar cobro" }));

    expect(screen.getByRole("alertdialog")).toHaveTextContent("Debe indicar el importe recibido.");
  });

  it("preserves the amount and returns focus to the input after accepting", () => {
    render(<CashPaymentDialog {...baseProps} totalCents={1210} initialMode="touch" />);
    const input = screen.getByRole("textbox", { name: "Dinero recibido" });
    fireEvent.change(input, { target: { value: "5" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar cobro" }));

    fireEvent.click(screen.getByRole("button", { name: "Aceptar" }));

    expect(input).toHaveValue("5");
    expect(input).toHaveFocus();
  });

  it("validates Enter and confirms only an amount that covers the total", () => {
    const onConfirm = vi.fn();
    render(<CashPaymentDialog {...baseProps} totalCents={1210} initialMode="keyboard" onConfirm={onConfirm} />);
    const input = screen.getByRole("textbox", { name: "Dinero recibido" });

    fireEvent.keyDown(window, { key: "Enter" });
    expect(screen.getByRole("alertdialog")).toHaveTextContent("Debe indicar el importe recibido.");
    fireEvent.keyDown(screen.getByRole("alertdialog"), { key: "Escape" });
    fireEvent.change(input, { target: { value: "12,10" } });
    fireEvent.keyDown(window, { key: "Enter" });

    expect(onConfirm).toHaveBeenCalledWith(1210);
  });

  it.each([
    ["Escape", false, false, "cancel"],
    ["Enter", false, false, "confirm"],
    ["a", false, false, "none"],
    ["Escape", true, false, "none"],
    ["Enter", true, false, "none"],
    ["Escape", false, true, "none"],
    ["Enter", false, true, "none"],
  ] as const)("decides %s with submitting=%s validationOpen=%s as %s", (key, submitting, validationOpen, action) => {
    expect(cashPaymentKeyAction(key, submitting, validationOpen)).toBe(action);
  });
});
