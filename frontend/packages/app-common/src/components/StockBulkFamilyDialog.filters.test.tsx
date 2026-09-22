// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { StockBulkFamilyDialog } from "./StockBulkFamilyDialog";

const families = [
  { id: "drinks", name: "Bebidas", subfamilies: [{ id: "water", name: "Agua" }] },
  { id: "food", name: "Alimentación", subfamilies: [{ id: "bread", name: "Pan" }] }
];

afterEach(cleanup);

describe("StockBulkFamilyDialog applied search", () => {
  it.each(["remove", "clear"])("restores the family tree and keeps selected families after %s", async (action) => {
    const onApply = vi.fn();
    render(<StockBulkFamilyDialog open app="venta" locale="es" families={families}
      initialFamilyIds={["food"]} onApply={onApply} onClose={vi.fn()} />);
    const search = screen.getByRole("searchbox", { name: "Buscar familia o subfamilia" });
    fireEvent.change(search, { target: { value: "  Agua  " } });
    expect(screen.queryByText("Alimentación")).toBeNull();
    expect(screen.getByRole("group", { name: "Filtros aplicados" }).textContent)
      .toContain("Buscar familia o subfamilia: Agua");

    fireEvent.click(screen.getByRole("button", { name: action === "remove"
      ? "Quitar filtro Buscar familia o subfamilia" : "Limpiar todos" }));
    expect((search as HTMLInputElement).value).toBe("");
    expect(screen.getByRole("checkbox", { name: "Alimentación" })).toHaveProperty("checked", true);
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(search));
    fireEvent.click(screen.getByRole("button", { name: "Añadir productos" }));
    expect(onApply).toHaveBeenCalledWith(["food"], []);
  });

  it("restores the family tree from the same applied-search chip in APP GESTIÓN", () => {
    render(<StockBulkFamilyDialog open app="gestion" locale="es" families={families}
      onApply={vi.fn()} onClose={vi.fn()} />);
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "Agua" } });
    expect(screen.queryByText("Alimentación")).toBeNull();
    expect(screen.getByRole("group", { name: "Filtros aplicados" }).textContent).toContain("Agua");
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Buscar familia o subfamilia" }));
    expect(screen.getByText("Alimentación")).toBeTruthy();
    expect(screen.getByRole("searchbox")).toHaveProperty("value", "");
  });
});
