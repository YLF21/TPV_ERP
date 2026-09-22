// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { WarehouseSupplierDialog } from "./WarehouseSupplierDialog";
import type { SupplierView } from "./PartyDirectoryPanel";

const suppliers: SupplierView[] = [
  { id: "active", supplierId: "P001", legalName: "Activo", documentType: "NIF", documentNumber: "A001", active: true },
  { id: "inactive", supplierId: "P002", legalName: "Inactivo", documentType: "NIF", documentNumber: "B002", active: false }
];
afterEach(cleanup);

describe("Warehouse supplier filter chips", () => {
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
    expect(screen.getByRole("button", { name: "Seleccionar" })).toBeDisabled();
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
