import { isValidElement, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { IndividualPaymentActions } from "./IndividualPaymentActions";

type ButtonProps = {
  children?: ReactNode;
  disabled?: boolean;
  onClick?: () => void;
  title?: string;
};

function renderedButtons(node: ReactNode): ButtonProps[] {
  if (!isValidElement<ButtonProps>(node)) return [];
  const current = node.type === "button" ? [node.props] : [];
  const children = Array.isArray(node.props.children) ? node.props.children : [node.props.children];
  return [...current, ...children.flatMap(renderedButtons)];
}

function buttonText(button: ButtonProps) {
  const read = (node: ReactNode): string => {
    if (typeof node === "string") return node;
    if (Array.isArray(node)) return node.map(read).join(" ");
    if (!isValidElement<{ children?: ReactNode }>(node)) return "";
    const children = Array.isArray(node.props.children) ? node.props.children : [node.props.children];
    return children.map(read).join(" ");
  };
  return read(button.children);
}

describe("IndividualPaymentActions", () => {
  it("renders the compact payment actions and invokes cash", () => {
    const onCash = vi.fn();
    const onCard = vi.fn();
    const buttons = renderedButtons(IndividualPaymentActions({ disabled: false, busy: false, cardEnabled: true, onCash, onCard }));

    const cash = buttons.find((button) => buttonText(button).includes("Efectivo"));
    const card = buttons.find((button) => buttonText(button).includes("Tarjeta"));
    const pending = buttons.find((button) => buttonText(button).includes("Pendiente cliente"));

    expect(cash).toBeDefined();
    expect(card).toBeDefined();
    expect(pending).toBeDefined();
    expect(cash?.disabled).toBeFalsy();
    expect(card?.disabled).toBeFalsy();
    expect(pending?.disabled).toBe(true);
    cash?.onClick?.();
    card?.onClick?.();
    expect(onCash).toHaveBeenCalledOnce();
    expect(onCard).toHaveBeenCalledOnce();
  });

  it("disables payment actions according to busy, disabled and card capability", () => {
    const callbacks = { onCash: vi.fn(), onCard: vi.fn() };
    const busy = renderedButtons(IndividualPaymentActions({ disabled: false, busy: true, cardEnabled: true, ...callbacks }));
    const unavailableCard = renderedButtons(IndividualPaymentActions({ disabled: false, busy: false, cardEnabled: false, ...callbacks }));

    expect(busy.slice(0, 2).every((button) => button.disabled)).toBe(true);
    expect(unavailableCard[0]?.disabled).toBe(false);
    expect(unavailableCard[1]?.disabled).toBe(true);
  });
});
