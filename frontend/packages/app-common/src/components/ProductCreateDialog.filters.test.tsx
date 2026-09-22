// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ProductCreateDialog } from "./ProductCreateDialog";

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.endsWith("/families")) return Response.json([
      { id: "drinks", familyCode: "123", name: "Bebidas" },
      { id: "coffee", familyCode: "456", name: "Café" }
    ]);
    return Response.json([]);
  }));
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("ProductCreateDialog opt-in family filter chips", () => {
  it("removes a search without discarding the selected family or the product draft", async () => {
    const { container } = render(<ProductCreateDialog open filterChips locale="es" token="test"
      initialForm={{ name: "Producto pendiente" }} onClose={vi.fn()} />);
    await waitFor(() => expect(screen.getAllByText("Bebidas").length).toBeGreaterThan(0));
    fireEvent.click(screen.getByRole("button", { name: "Explorar" }));
    const tree = screen.getByRole("tree");
    fireEvent.click(within(tree).getByRole("button", { name: "123Bebidas" }));
    const search = screen.getByRole("searchbox", { name: "Buscar por código o nombre" });
    fireEvent.change(search, { target: { value: "cafe" } });
    expect(within(tree).queryByRole("button", { name: "123Bebidas" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Buscar por código o nombre" }));
    expect((search as HTMLInputElement).value).toBe("");
    expect(container.querySelector('[data-family-tree-key="family:drinks"]')?.getAttribute("aria-selected")).toBe("true");
    expect(container.querySelector<HTMLInputElement>('[data-product-field-name="name"]')?.value).toBe("Producto pendiente");
    await waitFor(() => expect(document.activeElement).toBe(search));
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).toBeNull();
  });

  it("leaves callers outside Stock unchanged unless they opt in", async () => {
    render(<ProductCreateDialog open locale="es" token="test" onClose={vi.fn()} />);
    await waitFor(() => expect(screen.getAllByText("Bebidas").length).toBeGreaterThan(0));
    fireEvent.click(screen.getByRole("button", { name: "Explorar" }));
    fireEvent.change(screen.getByRole("searchbox", { name: "Buscar por código o nombre" }), { target: { value: "cafe" } });
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).toBeNull();
    expect(screen.queryByRole("button", { name: /Quitar filtro/ })).toBeNull();
  });
});
