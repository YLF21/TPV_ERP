// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { useState } from "react";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CashDenominationDialog } from "./CashDenominationDialog";
import { SaleTouchKeyboardScope } from "./SaleTouchKeyboardScope";

afterEach(cleanup);

describe("CashDenominationDialog", () => {
  it("counts an exact 200 euros in cents and returns only configured denominations", () => {
    const onAccept = vi.fn();
    render(<CashDenominationDialog locale="es" title="Fondo que queda" denominations={[0.2, 50, 100, 0.01, 20]} onAccept={onAccept} onCancel={vi.fn()} />);
    const dialog = screen.getByRole("dialog", { name: "Fondo que queda" });
    expect(within(dialog).getByText("Billetes")).toBeInTheDocument();
    expect(within(dialog).getByText("Monedas")).toBeInTheDocument();
    expect(within(dialog).getAllByRole("row")).toHaveLength(7);
    fireEvent.change(screen.getByRole("spinbutton", { name: /Unidades 100/ }), { target: { value: "1" } });
    fireEvent.change(screen.getByRole("spinbutton", { name: /Unidades 50/ }), { target: { value: "2" } });
    fireEvent.click(screen.getByRole("button", { name: "Aceptar" }));
    expect(onAccept).toHaveBeenCalledExactlyOnceWith([
      { denomination: 100, quantity: 1 }, { denomination: 50, quantity: 2 },
      { denomination: 20, quantity: 0 }, { denomination: 0.2, quantity: 0 },
      { denomination: 0.01, quantity: 0 },
    ], 200);
  });

  it.each(["-1", "1.5", "9007199254740992", "1e3"])("rejects invalid units %s", raw => {
    const onAccept = vi.fn();
    render(<CashDenominationDialog locale="en" title="Remaining float" denominations={[0.01]} onAccept={onAccept} onCancel={vi.fn()} />);
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: raw } });
    expect(screen.getByRole("spinbutton")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByRole("button", { name: "Accept" })).toBeDisabled();
    fireEvent.submit(screen.getByRole("button", { name: "Accept" }).closest("form")!);
    expect(onAccept).not.toHaveBeenCalled();
  });

  it("blocks a subtotal that exceeds safe integer cents", () => {
    const onAccept = vi.fn();
    render(<CashDenominationDialog locale="es" title="Fondo" denominations={[100]} onAccept={onAccept} onCancel={vi.fn()} />);
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "90071992547410" } });
    expect(screen.getByRole("button", { name: "Aceptar" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("límite de cálculo seguro");
    expect(onAccept).not.toHaveBeenCalled();
  });

  it("traps focus, cancels with Escape, and restores the opener focus", () => {
    const opener = document.createElement("button");
    opener.textContent = "Open";
    document.body.append(opener);
    opener.focus();
    const onCancel = vi.fn();
    const { unmount } = render(<CashDenominationDialog locale="zh" title="保留备用金" denominations={[5]} onAccept={vi.fn()} onCancel={onCancel} />);
    const quantity = screen.getByRole("spinbutton");
    const accept = screen.getByRole("button", { name: "确认" });
    expect(quantity).toHaveFocus();
    accept.focus();
    fireEvent.keyDown(accept, { key: "Tab" });
    expect(quantity).toHaveFocus();
    const parentKeyDown = vi.fn();
    document.addEventListener("keydown", parentKeyDown);
    fireEvent.keyDown(quantity, { key: "Escape" });
    expect(onCancel).toHaveBeenCalledOnce();
    expect(parentKeyDown).not.toHaveBeenCalled();
    unmount();
    expect(opener).toHaveFocus();
    document.removeEventListener("keydown", parentKeyDown);
    opener.remove();
  });

  it("advances Enter through banknotes and coins, then accepts on the next Enter", async () => {
    const user = userEvent.setup();
    const onAccept = vi.fn();
    function Counter() {
      const [open, setOpen] = useState(true);
      return open ? <CashDenominationDialog locale="es" title="Recuento" denominations={[0.01, 2, 100, 50]} onAccept={(rows, total) => { onAccept(rows, total); setOpen(false); }} onCancel={vi.fn()} /> : <span>Recuento aplicado</span>;
    }
    render(<Counter />);
    const fields = screen.getAllByRole("spinbutton");
    await user.type(fields[0], "2{Enter}");
    expect(fields[1]).toHaveFocus();
    await user.keyboard("3{Enter}");
    expect(fields[2]).toHaveFocus();
    await user.keyboard("4{Enter}");
    expect(fields[3]).toHaveFocus();
    await user.keyboard("5{Enter}");
    expect(screen.getByRole("button", { name: "Aceptar" })).toHaveFocus();
    expect(onAccept).not.toHaveBeenCalled();
    await user.keyboard("{Enter}");
    expect(onAccept).toHaveBeenCalledExactlyOnceWith([
      { denomination: 100, quantity: 2 }, { denomination: 50, quantity: 3 },
      { denomination: 2, quantity: 4 }, { denomination: 0.01, quantity: 5 },
    ], 358.05);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByText("Recuento aplicado")).toBeInTheDocument();
  });

  it("keeps held Enter from skipping fields or accepting the count", () => {
    const onAccept = vi.fn();
    render(<CashDenominationDialog locale="es" title="Recuento" denominations={[50, 1]} onAccept={onAccept} onCancel={vi.fn()} />);
    const fields = screen.getAllByRole("spinbutton");
    fireEvent.keyDown(fields[0], { key: "Enter", repeat: true });
    expect(fields[0]).toHaveFocus();
    fireEvent.keyDown(fields[0], { key: "Enter" });
    fireEvent.keyDown(fields[1], { key: "Enter" });
    const accept = screen.getByRole("button", { name: "Aceptar" });
    expect(accept).toHaveFocus();
    fireEvent.keyDown(accept, { key: "Enter", repeat: true });
    expect(onAccept).not.toHaveBeenCalled();
  });

  it("uses Previous and Next beside a single touch keypad without submitting early", async () => {
    const user = userEvent.setup();
    const onAccept = vi.fn();
    render(<SaleTouchKeyboardScope locale="es" interfaceMode="TOUCH"><CashDenominationDialog locale="es" interfaceMode="TOUCH" title="Recuento" denominations={[100, 0.01]} value={[{ denomination: 100, quantity: 12 }]} onAccept={onAccept} onCancel={vi.fn()} /></SaleTouchKeyboardScope>);
    const fields = screen.getAllByRole("spinbutton");
    const previous = screen.getByRole("button", { name: "Anterior" });
    const next = screen.getByRole("button", { name: "Siguiente" });
    const enter = screen.getByRole("button", { name: "Enter" });
    expect(previous).toBeDisabled();
    await user.click(await screen.findByRole("button", { name: "3" }));
    expect(document.querySelectorAll(".touch-numeric-keypad")).toHaveLength(1);
    expect(fields[0]).toHaveValue(3);
    await user.click(next);
    expect(fields[1]).toHaveFocus();
    expect(previous).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "5" }));
    expect(fields[1]).toHaveValue(5);
    await user.click(previous);
    expect(fields[0]).toHaveFocus();
    expect(fields[0]).toHaveValue(3);
    await user.click(next);
    await user.click(enter);
    const accept = screen.getByRole("button", { name: "Aceptar" });
    expect(accept).toHaveFocus();
    expect(next).toBeDisabled();
    expect(screen.getByRole("button", { name: "5" })).toBeDisabled();
    expect(onAccept).not.toHaveBeenCalled();
    await user.click(previous);
    expect(fields[1]).toHaveFocus();
    await user.click(next);
    await user.click(enter);
    expect(onAccept).toHaveBeenCalledExactlyOnceWith([{ denomination: 100, quantity: 3 }, { denomination: 0.01, quantity: 5 }], 300.05);
  });

  it("returns to the invalid quantity when advancing from the last field", () => {
    const onAccept = vi.fn();
    render(<CashDenominationDialog locale="en" title="Count" interfaceMode="TOUCH" denominations={[100, 0.01]} onAccept={onAccept} onCancel={vi.fn()} />);
    const fields = screen.getAllByRole("spinbutton");
    fireEvent.change(fields[0], { target: { value: "-1" } });
    fireEvent.keyDown(fields[0], { key: "Enter" });
    expect(fields[1]).toHaveFocus();
    fireEvent.keyDown(fields[1], { key: "Enter" });
    expect(fields[0]).toHaveFocus();
    expect(screen.getByRole("button", { name: "Accept" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Enter" }));
    expect(onAccept).not.toHaveBeenCalled();
  });

  it("keeps the desktop counter compact with visible denomination value markers", () => {
    render(<CashDenominationDialog locale="es" title="Recuento" denominations={[100, 50, 0.5, 0.01]} onAccept={vi.fn()} onCancel={vi.fn()} />);
    const dialog = screen.getByRole("dialog");
    expect(dialog.querySelector(".cash-value-icon--banknote--10000 text")).toHaveTextContent("100");
    expect(dialog.querySelector(".cash-value-icon--banknote--5000 text")).toHaveTextContent("50");
    expect(dialog.querySelector(".cash-value-icon--coin--50 text")).toHaveTextContent("50c");
    expect(dialog.querySelector(".cash-value-icon--coin--1 text")).toHaveTextContent("1c");
    expect(dialog.querySelectorAll('svg[aria-hidden="true"]')).toHaveLength(4);
    expect(screen.queryByRole("button", { name: "Siguiente" })).not.toBeInTheDocument();
    expect(dialog.querySelector(".touch-numeric-keypad")).not.toBeInTheDocument();
  });
});
