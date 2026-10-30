// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { WarehouseSupplierDialog } from "./WarehouseSupplierDialog";
import type { SupplierView } from "./PartyDirectoryPanel";

const suppliers: SupplierView[] = [
  { id: "active", supplierId: "P001", legalName: "Activo", documentType: "NIF", documentNumber: "A001", active: true },
  { id: "inactive", supplierId: "P002", legalName: "Inactivo", documentType: "NIF", documentNumber: "B002", active: false }
];
afterEach(() => { cleanup(); vi.useRealTimers(); });

describe("Warehouse supplier filter chips", () => {
  it("prepares the first supplier on opening and moves without selecting it for the document", () => {
    const onSelected = vi.fn();
    const { container } = render(<WarehouseSupplierDialog open locale="es" suppliers={suppliers}
      onSelected={onSelected} onChanged={vi.fn()} onClose={vi.fn()} />);
    const rows = [...container.querySelectorAll<HTMLElement>(".warehouse-supplier-table-row")];
    expect(rows[0]).toHaveFocus();
    expect(rows[0]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(rows[0], { key: "ArrowDown" });
    expect(rows[1]).toHaveAttribute("aria-selected", "true");
    expect(rows[1]).toHaveFocus();
    expect(onSelected).not.toHaveBeenCalled();
  });

  it("repeats supplier highlighting from the search while keeping input focus", async () => {
    const third = { ...suppliers[0], id: "third", supplierId: "P003", legalName: "Tercero" };
    const fourth = { ...suppliers[0], id: "fourth", supplierId: "P004", legalName: "Cuarto" };
    const onSelected = vi.fn();
    const { container } = render(<WarehouseSupplierDialog open locale="es" suppliers={[...suppliers, third, fourth]}
      selectedId="active" onSelected={onSelected} onChanged={vi.fn()} onClose={vi.fn()} />);
    const input = screen.getByRole("textbox");
    input.focus();
    vi.useFakeTimers();
    fireEvent.keyDown(input, { key: "ArrowDown" });
    expect(container.querySelector(".warehouse-supplier-table-row.selected")).toHaveTextContent("P002");
    expect(input).toHaveFocus();
    await act(async () => { vi.advanceTimersByTime(180); });
    expect(container.querySelector(".warehouse-supplier-table-row.selected")).toHaveTextContent("P003");
    fireEvent.keyUp(window, { key: "ArrowDown" });
    await act(async () => { vi.advanceTimersByTime(210); });
    expect(container.querySelector(".warehouse-supplier-table-row.selected")).toHaveTextContent("P003");
    expect(input).toHaveFocus();
    expect(onSelected).not.toHaveBeenCalled();
  });

  it("repeats from the current supplier and stops when focus leaves the table", async () => {
    const third = { ...suppliers[0], id: "third", supplierId: "P003", legalName: "Tercero" };
    const { container } = render(<WarehouseSupplierDialog open locale="es" suppliers={[...suppliers, third]}
      selectedId="active" onSelected={vi.fn()} onChanged={vi.fn()} onClose={vi.fn()} />);
    const first = screen.getByRole("row", { name: /P001/ });
    first.focus();
    vi.useFakeTimers();
    fireEvent.keyDown(first, { key: "ArrowDown" });
    expect(container.querySelector(".warehouse-supplier-table-row.selected")).toHaveTextContent("P002");
    await act(async () => { vi.advanceTimersByTime(180); });
    expect(container.querySelector(".warehouse-supplier-table-row.selected")).toHaveTextContent("P003");
    screen.getByRole("textbox").focus();
    await act(async () => { vi.advanceTimersByTime(200); });
    expect(container.querySelector(".warehouse-supplier-table-row.selected")).toHaveTextContent("P003");
  });
  it("clears the displayed search without changing the active supplier or enabling inactive suppliers", () => {
    const onSelected = vi.fn();
    const { container } = render(<WarehouseSupplierDialog open locale="es" suppliers={suppliers} selectedId="active"
      tableTheme="erp-blue-classic" onSelected={onSelected} onChanged={vi.fn()} onClose={vi.fn()} />);
    const input = screen.getByRole("textbox");
    fireEvent.change(input, { target: { value: "P001" } });
    expect(container.querySelectorAll(".warehouse-supplier-table-row")).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: /^Quitar filtro/ }));
    expect(input).toHaveValue("");
    expect(container.querySelectorAll(".warehouse-supplier-table-row")).toHaveLength(2);
    expect(container.querySelector(".warehouse-supplier-table-row.selected")).toHaveTextContent("P001");
    expect(onSelected).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("row", { name: /P002/ }));
    expect(screen.getByRole("button", { name: "Seleccionar proveedor" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: /Nuevo proveedor/ })).not.toBeInTheDocument();
  });

  it("keeps callers without opt-in on their existing search", () => {
    const { container } = render(<WarehouseSupplierDialog open locale="es" suppliers={suppliers}
      onSelected={vi.fn()} onChanged={vi.fn()} onClose={vi.fn()} />);
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "P001" } });
    expect(container.querySelectorAll(".warehouse-supplier-table-row")).toHaveLength(1);
    expect(container.querySelector(".warehouse-picker-classic")).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
  });
});
