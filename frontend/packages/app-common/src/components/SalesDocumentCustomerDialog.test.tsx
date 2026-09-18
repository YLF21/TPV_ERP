// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { SaleCustomer } from "./SaleScreen";
import { SalesDocumentCustomerDialog } from "./SalesDocumentCustomerDialog";
import { SaleTouchKeyboardScope } from "./SaleTouchKeyboardScope";

const customers: SaleCustomer[] = [
  { id: "customer-one", clientId: "CLI-001", fiscalName: "Cliente Uno SL", documentNumber: "B11111111" },
  { id: "customer-two", clientId: "CLI-002", fiscalName: "Cliente Dos SL", documentNumber: "B22222222" },
];

afterEach(cleanup);

describe("SalesDocumentCustomerDialog", () => {
  it("shows identifying columns and selects a clicked row only after confirmation", () => {
    const onSelect = vi.fn();
    render(<SalesDocumentCustomerDialog locale="es" customers={customers} onSelect={onSelect} onClose={vi.fn()} />);

    expect(screen.getByText("Código")).toBeInTheDocument();
    expect(screen.getByText("Nombre o razón social")).toBeInTheDocument();
    expect(screen.getByText("Documento")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Buscar cliente" })).toHaveFocus();
    expect(screen.getByRole("status")).toHaveTextContent("2 resultados");

    const second = screen.getByRole("option", { name: /Cliente Dos SL/ });
    fireEvent.click(second);
    expect(second).toHaveAttribute("aria-selected", "true");
    expect(onSelect).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Seleccionar cliente" }));
    expect(onSelect).toHaveBeenCalledExactlyOnceWith(customers[1]);
  });

  it("filters by customer code, fiscal name or document and resets the highlighted result", () => {
    const onSelect = vi.fn();
    render(<SalesDocumentCustomerDialog locale="es" customers={customers} selectedCustomerId="customer-two" onSelect={onSelect} onClose={vi.fn()} />);
    const search = screen.getByRole("textbox", { name: "Buscar cliente" });
    expect(screen.getByRole("option", { name: /Cliente Dos SL/ })).toHaveAttribute("aria-selected", "true");

    for (const value of ["  cli-001  ", "UNO sl", "b11111111"]) {
      fireEvent.change(search, { target: { value } });
      const result = screen.getByRole("option", { name: /Cliente Uno SL/ });
      expect(screen.getAllByRole("option")).toHaveLength(1);
      expect(result).toHaveAttribute("aria-selected", "true");
      expect(search).toHaveAttribute("aria-activedescendant", result.id);
    }
    fireEvent.keyDown(search, { key: "Enter" });
    expect(onSelect).toHaveBeenCalledExactlyOnceWith(customers[0]);
  });

  it("navigates with arrow keys and accepts with Insert or a double click", async () => {
    const onSelect = vi.fn();
    render(<SalesDocumentCustomerDialog locale="es" customers={customers} onSelect={onSelect} onClose={vi.fn()} />);
    const search = screen.getByRole("textbox", { name: "Buscar cliente" });
    const first = screen.getByRole("option", { name: /Cliente Uno SL/ });
    const second = screen.getByRole("option", { name: /Cliente Dos SL/ });

    fireEvent.keyDown(search, { key: "ArrowDown" });
    expect(second).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(search, { key: "Insert" });
    expect(onSelect).toHaveBeenLastCalledWith(customers[1]);
    fireEvent.keyDown(search, { key: "ArrowDown" });
    expect(first).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(search, { key: "ArrowUp" });
    expect(second).toHaveAttribute("aria-selected", "true");

    fireEvent.doubleClick(first);
    expect(onSelect).toHaveBeenLastCalledWith(customers[0]);

    fireEvent.click(first);
    fireEvent.keyDown(first, { key: "ArrowDown" });
    await waitFor(() => expect(second).toHaveFocus());
    fireEvent.keyDown(second, { key: "Enter" });
    expect(onSelect).toHaveBeenLastCalledWith(customers[1]);
  });

  it("keeps button keyboard actions separate from customer acceptance and ignores composing input", () => {
    const onSelect = vi.fn();
    const onClose = vi.fn();
    render(<SalesDocumentCustomerDialog locale="es" customers={customers} onSelect={onSelect} onClose={onClose} />);
    const search = screen.getByRole("textbox", { name: "Buscar cliente" });

    fireEvent.keyDown(search, { key: "Enter", isComposing: true });
    fireEvent.keyDown(search, { key: "Enter", repeat: true });
    fireEvent.keyDown(screen.getByRole("button", { name: "Cancelar" }), { key: "Enter" });
    expect(onSelect).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("displays loading and empty states without allowing a stale customer to be accepted", () => {
    const onSelect = vi.fn();
    const props = { locale: "es" as const, customers, onSelect, onClose: vi.fn() };
    const { rerender } = render(<SalesDocumentCustomerDialog {...props} loading />);

    expect(screen.getByText("Cargando clientes...")).toBeInTheDocument();
    expect(screen.getByRole("listbox")).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("button", { name: "Seleccionar cliente" })).toBeDisabled();
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "Insert" });
    expect(onSelect).not.toHaveBeenCalled();

    rerender(<SalesDocumentCustomerDialog {...props} />);
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "NO-EXISTE" } });
    expect(screen.getByText("No hay clientes que coincidan con la búsqueda")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Seleccionar cliente" })).toBeDisabled();
    expect(screen.getByRole("textbox")).not.toHaveAttribute("aria-activedescendant");
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "Enter" });
    expect(onSelect).not.toHaveBeenCalled();
  });

  it("limits the rendered list to 100 while allowing a search beyond the initial results", () => {
    const manyCustomers = Array.from({ length: 120 }, (_, index) => ({
      id: String(index), clientId: `CLI-${index}`, fiscalName: `Cliente ${index}`,
    }));
    render(<SalesDocumentCustomerDialog locale="es" customers={manyCustomers} onSelect={vi.fn()} onClose={vi.fn()} />);
    expect(screen.getAllByRole("option")).toHaveLength(100);
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "CLI-119" } });
    expect(screen.getByRole("option", { name: /Cliente 119/ })).toHaveAttribute("aria-selected", "true");
  });

  it("traps focus, closes with Escape and restores the original focused control", () => {
    const origin = document.createElement("button");
    document.body.appendChild(origin);
    origin.focus();
    const onClose = vi.fn();
    const { unmount } = render(<SalesDocumentCustomerDialog locale="es" customers={customers} onSelect={vi.fn()} onClose={onClose} />);
    const dialog = screen.getByRole("dialog");
    const firstButton = within(dialog).getByRole("button", { name: "Cerrar" });
    const lastButton = within(dialog).getByRole("button", { name: "Seleccionar cliente" });

    lastButton.focus();
    fireEvent.keyDown(lastButton, { key: "Tab" });
    expect(firstButton).toHaveFocus();
    fireEvent.keyDown(firstButton, { key: "Tab", shiftKey: true });
    expect(lastButton).toHaveFocus();
    fireEvent.keyDown(lastButton, { key: "Escape" });
    expect(onClose).toHaveBeenCalledOnce();

    unmount();
    expect(origin).toHaveFocus();
    origin.remove();
  });

  it("uses the parent touch keyboard to filter and keeps the explicit customer action available", () => {
    const onSelect = vi.fn();
    render(<SaleTouchKeyboardScope locale="es" interfaceMode="TOUCH">
      <SalesDocumentCustomerDialog locale="es" customers={customers} onSelect={onSelect} onClose={vi.fn()} />
    </SaleTouchKeyboardScope>);
    const keyboard = screen.getByRole("group", { name: "Teclado alfanumérico" });
    const search = screen.getByRole("textbox", { name: "Buscar cliente" });
    for (const letter of ["D", "O", "S"]) fireEvent.click(within(keyboard).getByRole("button", { name: letter }));
    expect(search).toHaveValue("DOS");
    expect(screen.getByRole("option", { name: /Cliente Dos SL/ })).toHaveAttribute("aria-selected", "true");
    fireEvent.click(screen.getByRole("button", { name: "Seleccionar cliente" }));
    expect(onSelect).toHaveBeenCalledExactlyOnceWith(customers[1]);
  });
});
