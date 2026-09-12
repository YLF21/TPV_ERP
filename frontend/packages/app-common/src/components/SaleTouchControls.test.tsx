// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  TouchSaleBottomActions, TouchSaleMoreOptionsDialog, TouchSaleSideActions,
  TouchSaleTopActions, type SaleTouchAction,
} from "./SaleTouchControls";

afterEach(cleanup);

function action(id: string, label = id, disabled = false): SaleTouchAction {
  return { id, label, disabled, onClick: vi.fn() };
}

describe("Touch sale controls", () => {
  it("keeps the document action immediately before calculator and supports its absence", () => {
    const documentAction = action("document", "Factura / albarán");
    const calculator = action("calculator", "Calculadora");
    const { rerender } = render(<TouchSaleTopActions document={documentAction} calculator={calculator} />);
    expect(screen.getAllByRole("button").map((button) => button.textContent)).toEqual(["Factura / albarán", "Calculadora"]);
    fireEvent.click(screen.getByRole("button", { name: "Calculadora" }));
    expect(calculator.onClick).toHaveBeenCalledOnce();
    expect(documentAction.onClick).not.toHaveBeenCalled();
    rerender(<TouchSaleTopActions calculator={calculator} />);
    expect(screen.queryByRole("button", { name: "Factura / albarán" })).toBeNull();
  });

  it("shows just the four approved left actions, with icons and visible text", () => {
    const parked = action("parked", "Ventas aparcadas");
    render(<TouchSaleSideActions parked={parked} returnAction={action("return", "Devolución")}
      copy={action("copy", "Copia último ticket")} more={action("more", "Más opciones")} />);
    const buttons = screen.getAllByRole("button");
    expect(buttons.map((button) => button.textContent)).toEqual([
      "Ventas aparcadas", "Devolución", "Copia último ticket", "Más opciones",
    ]);
    buttons.forEach((button) => expect(button.querySelector('svg[aria-hidden="true"]')).not.toBeNull());
    fireEvent.click(buttons[0]);
    expect(parked.onClick).toHaveBeenCalledOnce();
  });

  it("exposes two fixed editing rows and a single checkout without bypassing disabled actions", () => {
    const actions = {
      previous: action("previous", "Fila anterior", true),
      next: action("next", "Fila siguiente"),
      increase: action("increase", "+1"),
      decrease: action("decrease", "−1", true),
      quantity: action("quantity", "Cantidad"),
      price: action("price", "Precio"),
      discount: action("discount", "Descuento línea"),
      remove: action("remove", "Anular línea"),
      checkout: action("checkout", "Cobrar"),
    };
    render(<TouchSaleBottomActions {...actions} />);
    expect(screen.getAllByRole("button").map((button) => button.dataset.touchSlot)).toEqual([
      "previous", "increase", "quantity", "price", "checkout", "next", "decrease", "discount", "remove",
    ]);
    expect(screen.getAllByRole("button", { name: "Cobrar" })).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "−1" }));
    fireEvent.click(screen.getByRole("button", { name: "Fila anterior" }));
    expect(actions.decrease.onClick).not.toHaveBeenCalled();
    expect(actions.previous.onClick).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "+1" }));
    expect(actions.increase.onClick).toHaveBeenCalledOnce();
    expect(screen.getByRole("button", { name: "Anular línea" }).classList.contains("sale-touch-button-danger")).toBe(true);
  });
});

describe("Touch more options", () => {
  const ids = ["temporary-name", "serial-number", "sale-discount", "sale-comment", "convert-ticket", "gift-receipt", "clear-lines", "clear-sale", "cancel-ticket"];

  it("organizes only allowed actions into three columns without executing them on open", () => {
    const actions = ids.map((id) => action(id));
    const unapproved = action("cash-withdrawal");
    render(<TouchSaleMoreOptionsDialog locale="es" actions={[...actions, unapproved]} onClose={vi.fn()} />);
    const dialog = screen.getByRole("dialog", { name: "Más opciones" });
    expect(within(dialog).getAllByRole("heading", { level: 3 }).map((heading) => heading.textContent)).toEqual([
      "Producto y documento", "Comentarios y tickets", "Eliminación y anulación",
    ]);
    expect(dialog.querySelectorAll("[data-touch-action]")).toHaveLength(9);
    expect(within(dialog).queryByRole("button", { name: "cash-withdrawal" })).toBeNull();
    [...actions, unapproved].forEach((item) => expect(item.onClick).not.toHaveBeenCalled());
    expect(dialog.querySelectorAll(".sale-touch-button-danger")).toHaveLength(3);
    fireEvent.click(within(dialog).getByRole("button", { name: "sale-comment" }));
    expect(actions[3].onClick).toHaveBeenCalledOnce();
  });

  it("shows the supplied document discount state and disabled reason without changing it", () => {
    const discount = { ...action("sale-discount", "Eliminar descuento documento", true), title: "Operación bloqueada" };
    const { rerender } = render(<TouchSaleMoreOptionsDialog locale="es" actions={[discount]} onClose={vi.fn()} />);
    const disabled = screen.getByRole<HTMLButtonElement>("button", { name: "Eliminar descuento documento" });
    expect(disabled.disabled).toBe(true);
    expect(disabled.title).toBe("Operación bloqueada");
    fireEvent.click(disabled);
    expect(discount.onClick).not.toHaveBeenCalled();
    rerender(<TouchSaleMoreOptionsDialog locale="es" actions={[{ ...discount, disabled: false }]} onClose={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Eliminar descuento documento" }));
    expect(discount.onClick).toHaveBeenCalledOnce();
  });

  it("traps Tab, closes with Escape without propagating it, and restores the opener focus", () => {
    const opener = document.createElement("button");
    document.body.appendChild(opener);
    opener.focus();
    const onClose = vi.fn();
    const onGlobalKeyDown = vi.fn();
    document.addEventListener("keydown", onGlobalKeyDown);
    const { unmount } = render(<TouchSaleMoreOptionsDialog locale="es" actions={ids.map((id) => action(id))} onClose={onClose} />);
    const closeButtons = screen.getAllByRole("button", { name: "Cerrar" });
    expect(document.activeElement).toBe(closeButtons[0]);
    fireEvent.keyDown(closeButtons[0], { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(closeButtons[1]);
    fireEvent.keyDown(closeButtons[1], { key: "Tab" });
    expect(document.activeElement).toBe(closeButtons[0]);
    fireEvent.keyDown(closeButtons[0], { key: "Escape" });
    expect(onClose).toHaveBeenCalledOnce();
    expect(onGlobalKeyDown).not.toHaveBeenCalled();
    unmount();
    expect(document.activeElement).toBe(opener);
    document.removeEventListener("keydown", onGlobalKeyDown);
    opener.remove();
  });

  it.each([
    ["es", "Más opciones", "Cerrar"],
    ["en", "More options", "Close"],
    ["zh", "更多选项", "关闭"],
  ] as const)("localizes the dialog in %s", (locale, title, close) => {
    render(<TouchSaleMoreOptionsDialog locale={locale} actions={[]} onClose={vi.fn()} />);
    expect(screen.getByRole("dialog", { name: title })).not.toBeNull();
    expect(screen.getAllByRole("button", { name: close })).toHaveLength(2);
    expect(screen.queryByText(/^sale\.touch\./)).toBeNull();
  });
});
