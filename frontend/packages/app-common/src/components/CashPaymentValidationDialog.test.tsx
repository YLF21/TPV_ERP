// @vitest-environment jsdom

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CashPaymentValidationDialog } from "./CashPaymentValidationDialog";

const tpvCss = readFileSync(resolve(process.cwd(), "packages/app-common/src/styles/tpv.css"), "utf8");

afterEach(cleanup);

describe("CashPaymentValidationDialog", () => {
  it("renders a compact accessible warning with one acceptance action", () => {
    render(<CashPaymentValidationDialog message="Debe indicar el importe recibido." onAccept={vi.fn()} />);

    const dialog = screen.getByRole("alertdialog", { name: "Aviso" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toHaveTextContent("Debe indicar el importe recibido.");
    expect(screen.getByRole("button", { name: "Aceptar" })).toHaveFocus();
    expect(tpvCss).toMatch(/\.cash-payment-validation-dialog\s*{[^}]*width:\s*min\(360px,\s*calc\(100vw - 32px\)\);[^}]*border-radius:\s*4px;/s);
    expect(tpvCss).toMatch(/\.cash-payment-validation-dialog button\s*{[^}]*min-height:\s*34px;/s);
  });

  it.each(["Enter", "Escape"])("accepts the warning with %s", (key) => {
    const onAccept = vi.fn();
    render(<CashPaymentValidationDialog message="El importe recibido no cubre el total." onAccept={onAccept} />);

    fireEvent.keyDown(screen.getByRole("alertdialog"), { key });

    expect(onAccept).toHaveBeenCalledOnce();
  });

  it("accepts the warning from its button", () => {
    const onAccept = vi.fn();
    render(<CashPaymentValidationDialog message="Debe indicar el importe recibido." onAccept={onAccept} />);

    fireEvent.click(screen.getByRole("button", { name: "Aceptar" }));

    expect(onAccept).toHaveBeenCalledOnce();
  });

  it("does not restore focus when its focus trap is removed", () => {
    const { unmount } = render(<CashPaymentValidationDialog message="Debe indicar el importe recibido." onAccept={vi.fn()} />);
    const accept = screen.getByRole("button", { name: "Aceptar" });
    const focus = vi.spyOn(accept, "focus");

    unmount();

    expect(focus).not.toHaveBeenCalled();
  });
});
