// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { useState } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ErpMultiSelect } from "./ErpMultiSelect";
import type { ErpSelectOption } from "./ErpSelect";

const options: readonly ErpSelectOption[] = [
  { value: "", label: "Todos" },
  { value: "cash", label: "Efectivo" },
  { value: "blocked", label: "Bloqueado", disabled: true },
  { value: "card", label: "Tarjeta" },
  { value: "transfer", label: "Transferencia" },
];

function Harness({ initial = [], searchable = false, onChange = vi.fn() }: {
  initial?: string[]; searchable?: boolean; onChange?: (values: string[]) => void;
}) {
  const [values, setValues] = useState(initial);
  return <ErpMultiSelect values={values} options={options} aria-label="Método de pago" placeholder="Todos"
    searchPlaceholder={searchable ? "Buscar método" : undefined}
    onChange={(next) => { setValues(next); onChange(next); }} />;
}

function openMenu() {
  fireEvent.click(screen.getByRole("button", { name: "Método de pago" }));
}

afterEach(cleanup);

describe("ErpMultiSelect", () => {
  it("keeps the menu open while adding and removing different values", () => {
    const change = vi.fn();
    render(<Harness onChange={change} />);
    openMenu();
    expect(screen.getByRole("listbox")).toHaveAttribute("aria-multiselectable", "true");
    fireEvent.click(screen.getByRole("option", { name: "Efectivo" }));
    fireEvent.click(screen.getByRole("option", { name: "Tarjeta" }));
    expect(change).toHaveBeenLastCalledWith(["cash", "card"]);
    expect(screen.getByRole("option", { name: "Efectivo" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("option", { name: "Tarjeta" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("button", { name: "Método de pago" })).toHaveTextContent("Efectivo, Tarjeta");
    fireEvent.click(screen.getByRole("option", { name: "Efectivo" }));
    expect(change).toHaveBeenLastCalledWith(["card"]);
    expect(screen.getByRole("listbox")).toBeInTheDocument();
  });

  it("clears every value using Todos without storing the sentinel", () => {
    const change = vi.fn();
    render(<Harness initial={["cash", "card"]} onChange={change} />);
    openMenu();
    fireEvent.click(screen.getByRole("option", { name: "Todos" }));
    expect(change).toHaveBeenLastCalledWith([]);
    expect(screen.getByRole("option", { name: "Todos" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("button", { name: "Método de pago" })).toHaveTextContent("Todos");
  });

  it("normalizes duplicate values when the user changes the selection", () => {
    const change = vi.fn();
    render(<Harness initial={["cash", "cash", ""]} onChange={change} />);
    openMenu();
    fireEvent.click(screen.getByRole("option", { name: "Tarjeta" }));
    expect(change).toHaveBeenLastCalledWith(["cash", "card"]);
  });

  it("navigates enabled options and toggles with Enter and Space without submitting the parent", () => {
    const parentKeys = vi.fn();
    const change = vi.fn();
    render(<div onKeyDown={parentKeys}><Harness initial={["cash"]} onChange={change} /></div>);
    fireEvent.keyDown(screen.getByRole("button", { name: "Método de pago" }), { key: "ArrowDown" });
    expect(screen.getByRole("option", { name: "Efectivo" })).toHaveFocus();
    fireEvent.keyDown(document.activeElement!, { key: "ArrowDown" });
    expect(screen.getByRole("option", { name: "Tarjeta" })).toHaveFocus();
    fireEvent.keyDown(document.activeElement!, { key: "Enter" });
    expect(change).toHaveBeenLastCalledWith(["cash", "card"]);
    fireEvent.keyDown(document.activeElement!, { key: " " });
    expect(change).toHaveBeenLastCalledWith(["cash"]);
    expect(screen.getByRole("listbox")).toBeInTheDocument();
    expect(parentKeys).not.toHaveBeenCalled();
    fireEvent.keyDown(document.activeElement!, { key: "End" });
    expect(screen.getByRole("option", { name: "Transferencia" })).toHaveFocus();
    fireEvent.keyDown(document.activeElement!, { key: "Home" });
    expect(screen.getByRole("option", { name: "Todos" })).toHaveFocus();
  });

  it("closes on Escape, restores the trigger focus and does not close an ancestor dialog", () => {
    const ancestorEscape = vi.fn();
    render(<div onKeyDown={ancestorEscape}><Harness /></div>);
    openMenu();
    fireEvent.keyDown(document.activeElement!, { key: "Escape" });
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Método de pago" })).toHaveFocus();
    expect(ancestorEscape).not.toHaveBeenCalled();
  });

  it("closes on an outside pointer event even if its target stops propagation", () => {
    render(<><Harness /><button onPointerDown={(event) => event.stopPropagation()}>Fuera</button></>);
    openMenu();
    fireEvent.pointerDown(screen.getByRole("button", { name: "Fuera" }));
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("searches labels, retains hidden selections and never creates an unknown value", () => {
    const change = vi.fn();
    render(<Harness initial={["cash"]} searchable onChange={change} />);
    openMenu();
    const search = screen.getByRole("textbox", { name: "Buscar método" });
    expect(search).toHaveFocus();
    fireEvent.change(search, { target: { value: "TARJ" } });
    expect(screen.queryByRole("option", { name: "Efectivo" })).not.toBeInTheDocument();
    fireEvent.keyDown(search, { key: "ArrowDown" });
    expect(screen.getByRole("option", { name: "Tarjeta" })).toHaveFocus();
    fireEvent.focus(search);
    fireEvent.keyDown(search, { key: "ArrowDown" });
    expect(screen.getByRole("option", { name: "Tarjeta" })).toHaveFocus();
    fireEvent.keyDown(document.activeElement!, { key: "Enter" });
    expect(change).toHaveBeenLastCalledWith(["cash", "card"]);
    fireEvent.change(search, { target: { value: "Desconocido" } });
    expect(search).toHaveFocus();
    change.mockClear();
    fireEvent.keyDown(search, { key: "Enter" });
    expect(change).not.toHaveBeenCalled();
    fireEvent.keyDown(search, { key: "Escape" });
    openMenu();
    expect(screen.getByRole("textbox", { name: "Buscar método" })).toHaveValue("");
    expect(screen.getByRole("option", { name: "Efectivo" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("option", { name: "Tarjeta" })).toHaveAttribute("aria-selected", "true");
  });

  it("respects disabled options and a disabled control", () => {
    const change = vi.fn();
    const { rerender } = render(<ErpMultiSelect values={[]} options={options} onChange={change} placeholder="Todos" aria-label="Método de pago" />);
    openMenu();
    fireEvent.click(screen.getByRole("option", { name: "Bloqueado" }));
    expect(change).not.toHaveBeenCalled();
    rerender(<ErpMultiSelect values={[]} options={options} onChange={change} placeholder="Todos" aria-label="Método de pago" disabled />);
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Método de pago" })).toBeDisabled();
  });
});
