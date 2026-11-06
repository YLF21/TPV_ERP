// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { apiRequest } from "../api/client";
import { ReplenishmentScreen } from "./ReplenishmentScreen";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));
const item = { id: "r1", version: 3, productId: "p1", warehouseId: "w1", warehouseName: "General", code: "A1", barcode: "123", name: "Café", quantity: 2.5, reason: "Reposición", stock: 0, purchasePrice: null, supplierId: "s1", supplierName: "Proveedor", familyId: "f1", familyName: "Bebidas", subfamilyId: "sf1", subfamilyName: "Café", status: "PENDING" as const, orderDate: null };
beforeEach(() => {
  vi.mocked(apiRequest).mockImplementation(async (path) => {
    if (path === "/warehouses") return [{ id: "w1", name: "General", active: true, defaultWarehouse: true }] as never;
    if (path.startsWith("/replenishment-requests") && !path.includes("/export")) return [item] as never;
    return undefined as never;
  });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("replenishment UI", () => {
  it("toggles each row from its cells and checks boxes only once, preserving multi-selection", async () => {
    const second = { ...item, id: "r2", name: "Agua", code: "A2" };
    vi.mocked(apiRequest).mockImplementation(async path => {
      if (path === "/warehouses") return [] as never;
      if (path === "/replenishment-requests") return [item, second] as never;
      return undefined as never;
    });
    render(<ReplenishmentScreen token="token" locale="es" />);
    const cafe = await screen.findByRole("checkbox", { name: "Seleccionar Café" }) as HTMLInputElement;
    const water = screen.getByRole("checkbox", { name: "Seleccionar Agua" }) as HTMLInputElement;
    const all = screen.getByRole("checkbox", { name: "Seleccionar todas" }) as HTMLInputElement;
    fireEvent.click(cafe.closest("tr")!);
    expect(cafe.checked).toBe(true);
    fireEvent.click(screen.getByRole("cell", { name: "A2" }));
    expect(water.checked).toBe(true);
    expect(all.checked).toBe(true);
    expect(screen.getByText("2 seleccionadas")).toBeTruthy();
    fireEvent.click(screen.getByRole("cell", { name: "A1" }));
    expect(cafe.checked).toBe(false);
    expect(water.checked).toBe(true);
    fireEvent.click(cafe);
    expect(cafe.checked).toBe(true);
    fireEvent.click(cafe);
    expect(cafe.checked).toBe(false);
    fireEvent.click(all);
    expect(cafe.checked && water.checked).toBe(true);
    fireEvent.click(all);
    expect(cafe.checked || water.checked).toBe(false);
  });

  it("keeps pending date blank and marks only selected versioned requests", async () => {
    render(<ReplenishmentScreen token="token" locale="es" />);
    await screen.findByRole("checkbox", { name: "Seleccionar Café" });
    const row = screen.getByRole("checkbox", { name: "Seleccionar Café" }).closest("tr")!;
    expect(row.lastElementChild?.textContent).toBe("");
    expect(row.textContent).not.toContain("0€");
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar Café" }));
    fireEvent.click(screen.getByRole("button", { name: "Marcar pedido" }));
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/replenishment-requests/status", { token: "token", body: { items: [{ id: "r1", version: 3 }], ordered: true } }));
  });

  it("clears selection and prevents stale actions while a filtered request loads or fails", async () => {
    let rejectFiltered!: (reason: Error) => void;
    vi.mocked(apiRequest).mockImplementation(async (path) => {
      if (path === "/warehouses") return [{ id: "w1", name: "General", active: true }] as never;
      if (path === "/replenishment-requests") return [item] as never;
      if (path === "/replenishment-requests?warehouseId=w1") return new Promise((_, reject) => { rejectFiltered = reject; });
      return undefined as never;
    });
    render(<ReplenishmentScreen token="token" locale="es" />);
    fireEvent.click(await screen.findByRole("checkbox", { name: "Seleccionar Café" }));
    expect((screen.getByRole("button", { name: "Marcar pedido" }) as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Filtrar" }));
    fireEvent.click(screen.getAllByRole("button", { name: "Almacén" }).find(element => element.getAttribute("aria-haspopup") === "listbox")!);
    fireEvent.click(screen.getByRole("option", { name: "General" }));
    expect(screen.getByText("0 seleccionadas")).toBeTruthy();
    expect((screen.getByRole("button", { name: "Marcar pedido" }) as HTMLButtonElement).disabled).toBe(true);
    await waitFor(() => expect(rejectFiltered).toBeTypeOf("function"));
    rejectFiltered(new Error("Sin conexión"));
    await screen.findByRole("alert");
    expect(screen.queryByRole("checkbox", { name: "Seleccionar Café" })).toBeNull();
    expect((screen.getByRole("button", { name: "Marcar pedido" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("retains rows and selection during refresh, then reconciles selected ids", async () => {
    let resolveRefresh!: (rows: typeof item[]) => void;
    let reads = 0;
    vi.mocked(apiRequest).mockImplementation(async path => {
      if (path === "/warehouses") return [] as never;
      if (path === "/replenishment-requests") {
        reads += 1;
        return reads === 1 ? [item] as never : new Promise(resolve => { resolveRefresh = resolve; });
      }
      return undefined as never;
    });
    render(<ReplenishmentScreen token="token" locale="es" />);
    fireEvent.click(await screen.findByRole("checkbox", { name: "Seleccionar Café" }));
    fireEvent.click(screen.getByRole("button", { name: "Actualizar" }));
    expect(screen.getByRole("checkbox", { name: "Seleccionar Café" })).toBeTruthy();
    expect(screen.getByText("1 seleccionadas")).toBeTruthy();
    expect((screen.getByRole("button", { name: "Marcar pedido" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("cell", { name: "A1" }));
    expect(screen.getByText("1 seleccionadas")).toBeTruthy();
    await waitFor(() => expect(resolveRefresh).toBeTypeOf("function"));
    resolveRefresh([item]);
    await waitFor(() => expect((screen.getByRole("button", { name: "Marcar pedido" }) as HTMLButtonElement).disabled).toBe(false));
  });

  it("sorts visible rows and sends both selected versions in one status change", async () => {
    const second = { ...item, id: "r2", version: 7, name: "Agua", code: "A0", barcode: "456" };
    vi.mocked(apiRequest).mockImplementation(async path => {
      if (path === "/warehouses") return [] as never;
      if (path === "/replenishment-requests") return [item, second] as never;
      return undefined as never;
    });
    render(<ReplenishmentScreen token="token" locale="es" username="test-user" />);
    await screen.findByRole("checkbox", { name: "Seleccionar Agua" });
    fireEvent.click(screen.getAllByRole("button", { name: "Código" }).find(element => element.classList.contains("table-layout-sort-button"))!);
    const rows = screen.getByRole("table").querySelectorAll("tbody tr");
    expect(rows[0]?.textContent).toContain("Agua");
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar Café" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar Agua" }));
    fireEvent.click(screen.getByRole("button", { name: "Marcar pedido" }));
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/replenishment-requests/status", {
      token: "token", body: { items: [{ id: "r1", version: 3 }, { id: "r2", version: 7 }], ordered: true },
    }));
  });
});
