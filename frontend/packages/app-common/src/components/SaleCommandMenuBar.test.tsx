// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SaleCommandMenuBar, type SaleCommandMenu } from "./SaleCommandMenuBar";

const action = vi.fn();
const toggle = vi.fn();
const menus: readonly SaleCommandMenu[] = [
  {
    id: "system",
    label: "SISTEMA",
    entries: [
      {
        type: "action",
        id: "calculator",
        label: "Calculadora",
        shortcut: "F2",
        onSelect: action,
      },
      { type: "separator", id: "separator" },
      {
        type: "action",
        id: "close-cash",
        label: "Cerrar caja",
        shortcut: "F8",
        disabled: true,
        disabledReason: "Carrito con productos",
        onSelect: vi.fn(),
      },
    ],
  },
  {
    id: "visualization",
    label: "VISUALIZACIÓN",
    entries: [
      {
        type: "toggle",
        id: "show-image",
        label: "Mostrar imagen",
        checked: true,
        onToggle: toggle,
      },
    ],
  },
];

describe("SaleCommandMenuBar", () => {
  afterEach(() => {
    cleanup();
    action.mockReset();
    toggle.mockReset();
  });

  it("aligns functions with their shortcuts and executes only connected actions", () => {
    render(<SaleCommandMenuBar ariaLabel="Comandos de venta" menus={menus} />);

    fireEvent.click(screen.getByRole("button", { name: /SISTEMA/ }));
    expect(screen.getByRole("separator")).toBeTruthy();
    expect(screen.getByRole("menuitem", { name: /Calculadora F2/ })).toBeTruthy();
    expect(screen.getByRole("menuitem", { name: /Cerrar caja F8/ })).toBeDisabled();

    fireEvent.click(screen.getByRole("menuitem", { name: /Calculadora F2/ }));
    expect(action).toHaveBeenCalledOnce();
    expect(screen.queryByRole("menu", { name: "SISTEMA" })).toBeNull();
  });

  it("renders persistent visualization entries as checked menu items", () => {
    render(<SaleCommandMenuBar ariaLabel="Comandos de venta" menus={menus} />);

    fireEvent.click(screen.getByRole("button", { name: /VISUALIZACIÓN/ }));
    const item = screen.getByRole("menuitemcheckbox", { name: /Mostrar imagen/ });
    expect(item).toHaveAttribute("aria-checked", "true");
    fireEvent.click(item);
    expect(toggle).toHaveBeenCalledOnce();
  });

  it("closes on Escape and restores focus to its trigger", async () => {
    render(<SaleCommandMenuBar ariaLabel="Comandos de venta" menus={menus} />);
    const trigger = screen.getByRole("button", { name: /SISTEMA/ });

    fireEvent.keyDown(trigger, { key: "ArrowDown" });
    const calculator = screen.getByRole("menuitem", { name: /Calculadora F2/ });
    fireEvent.keyDown(calculator, { key: "Escape" });
    await Promise.resolve();

    expect(screen.queryByRole("menu", { name: "SISTEMA" })).toBeNull();
    expect(trigger).toHaveFocus();
  });
});
