// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import type { UserSession } from "../types";
import { StockScreen, type StockInventoryRow, type StockTopSalesRow } from "./StockScreen";
import type { StockBulkDraftView, StockBulkEditRowData } from "./stockBulkEdit";
import { tableSortStorageKey } from "./tableSorting";

vi.mock("../api/client", async (importOriginal) => ({
  ...await importOriginal<typeof import("../api/client")>(), apiRequest: vi.fn()
}));

const warehouseId = "11111111-1111-4111-8111-111111111111";
const session: UserSession = { username: "filters", displayName: "Filters", permissions: ["ADMIN"], accessToken: "test" };
const props = {
  locale: "es" as const, session, terminalContext: { storeName: "Test", terminalCode: "TEST" },
  onBack: vi.fn(), onLocaleChange: vi.fn()
};
const topRows: StockTopSalesRow[] = [
  { productId: "p-1", code: "1", barcode: "", name: "Café", familyId: "drinks", familyName: "Bebidas", subfamilyId: "coffee", subfamilyName: "Café",
    suppliers: [{ supplierId: "supplier-1", supplierCode: "P1", supplierName: "Proveedor Uno" }], soldQuantity: 10, netAmount: 20, currentStock: 10, warehouseId, warehouseName: "GENERAL" },
  { productId: "p-2", code: "2", barcode: "", name: "Agua", familyId: "drinks", familyName: "Bebidas", subfamilyId: "water", subfamilyName: "Agua",
    suppliers: [{ supplierId: "supplier-2", supplierCode: "P2", supplierName: "Proveedor Dos" }], soldQuantity: 8, netAmount: 10, currentStock: 10, warehouseId, warehouseName: "GENERAL" }
];
const originalScrollIntoView = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "scrollIntoView");

function pageQueries() {
  return vi.mocked(apiRequest).mock.calls.map(([path]) => path).filter(path => path.startsWith("/stock/page"))
    .map(path => new URL(path, "http://test").searchParams);
}

beforeEach(() => {
  localStorage.clear();
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: vi.fn() });
  vi.stubGlobal("fetch", vi.fn(async () => Response.json([])));
  vi.mocked(apiRequest).mockReset().mockImplementation(async path => {
    if (path.startsWith("/stock/page")) return { items: [], hasMore: false };
    if (path === "/warehouses") return [{ id: warehouseId, name: "GENERAL", active: true, defaultWarehouse: true }];
    if (path.startsWith("/stock/top-sales")) return topRows;
    return [];
  });
});
afterEach(() => {
  cleanup(); localStorage.clear(); vi.unstubAllGlobals();
  if (originalScrollIntoView) Object.defineProperty(HTMLElement.prototype, "scrollIntoView", originalScrollIntoView);
  else delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView;
});

async function applyInventoryType() {
  fireEvent.click(screen.getByRole("button", { name: "Filtrar stock" }));
  const dialog = screen.getByRole("dialog", { name: "Filtrar stock" });
  const field = within(dialog).getByText("Tipo", { exact: true }).closest(".filter-field")!;
  fireEvent.click(within(field as HTMLElement).getByRole("button"));
  fireEvent.click(within(field as HTMLElement).getByRole("button", { name: "Unidad" }));
  fireEvent.click(within(dialog).getByRole("button", { name: "Aplicar filtro" }));
  await waitFor(() => expect(pageQueries().at(-1)?.get("type")).toBe("UNIT"));
}

async function openBulkWorkspace() {
  const content: StockBulkEditRowData[] = ["Café", "Agua"].map((name, index) => ({
    id: `row-${index}`, selected: true, query: String(index + 1), draft: {},
    product: { productId: `p-${index + 1}`, warehouseId, warehouseName: "GENERAL", code: String(index + 1), barcode: "", name,
      salePrice: index === 0 ? "5" : "2", purchasePrice: "1", version: 4 + index, productType: "UNIT", discountType: "NORMAL",
      quantity: 10, totalQuantity: 10, active: "common.yes", familyId: "drinks", familyName: "Bebidas",
      subfamilyId: index === 0 ? "coffee" : "water", subfamilyName: name, taxId: "", taxName: "-",
      taxesIncluded: "common.yes", offerActive: "common.no", offerFrom: "-", offerUntil: "-" } as StockInventoryRow
  }));
  const draft: StockBulkDraftView = { id: "saved", code: "EM-1", seriesId: "series", versionNumber: 1, version: 2,
    name: "Lista filtro", status: "PENDING", content, comments: [], createdById: "user-1", updatedById: "user-1", createdBy: "Filters", updatedBy: "Filters",
    createdAt: "2026-09-22T10:00:00Z", updatedAt: "2026-09-22T10:00:00Z" };
  const fallback = vi.mocked(apiRequest).getMockImplementation()!;
  vi.mocked(apiRequest).mockImplementation(async (path, options) => {
    if (path.startsWith("/stock/page")) return { items: content.map(row => ({
      product: { ...row.product, id: row.product!.productId, active: true, taxesIncluded: true, offerActive: false },
      stock: [{ productId: row.product!.productId, warehouseId, quantity: 10 }]
    })), hasMore: false };
    if (path === "/product-bulk-edits") return [draft];
    if (path === "/product-bulk-edits/saved") return structuredClone(draft);
    if (path === "/families") return [{ id: "drinks", name: "Bebidas" }];
    if (path === "/families/drinks/subfamilies") return [
      { id: "coffee", familyId: "drinks", name: "Café" },
      { id: "water", familyId: "drinks", name: "Agua" }
    ];
    return fallback(path, options);
  });
  const view = render(<StockScreen {...props} app="venta" initialView="stock.bulkEdit" />);
  fireEvent.doubleClick((await screen.findByText("Lista filtro")).closest("tr")!);
  await screen.findByRole("button", { name: "Aplicar cambios" });
  return view;
}

describe("Stock applied filter chips", () => {
  it.each(["venta", "gestion"] as const)("shows TODO and the real warehouse without a local alias in %s", async app => {
    localStorage.setItem(tableSortStorageKey(app, session.username, "stock.inventory.stock.current"),
      JSON.stringify({ column: "code", direction: "asc" }));
    render(<StockScreen {...props} app={app} />);
    await waitFor(() => expect(pageQueries().at(-1)?.get("warehouseId")).toBe(warehouseId));

    const openWarehouseFilter = () => {
      fireEvent.click(screen.getByRole("button", { name: "Filtrar stock" }));
      const dialog = screen.getByRole("dialog", { name: "Filtrar stock" });
      const field = within(dialog).getByText("Almacén", { exact: true }).closest(".filter-field")! as HTMLElement;
      const trigger = within(field).getByRole("button");
      fireEvent.click(trigger);
      return { dialog, field, trigger };
    };

    let filter = openWarehouseFilter();
    expect(filter.trigger.textContent).toContain("GENERAL");
    const options = within(filter.field.querySelector(".filter-popover")! as HTMLElement).getAllByRole("button");
    expect(options.map(option => option.textContent)).toEqual(["TODO", "GENERAL"]);
    expect(options[1].classList.contains("selected")).toBe(true);
    fireEvent.click(options[0]);
    fireEvent.click(within(filter.dialog).getByRole("button", { name: "Aplicar filtro" }));
    await waitFor(() => expect(pageQueries().at(-1)?.has("warehouseId")).toBe(false));
    expect(screen.getByRole("group", { name: "Filtros aplicados" }).textContent).toContain("Almacén: TODO");

    filter = openWarehouseFilter();
    expect(filter.trigger.textContent).toContain("TODO");
    fireEvent.click(within(filter.field).getByRole("button", { name: "GENERAL" }));
    fireEvent.click(within(filter.dialog).getByRole("button", { name: "Aplicar filtro" }));
    await waitFor(() => expect(pageQueries().at(-1)?.get("warehouseId")).toBe(warehouseId));

    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Almacén" }));
    filter = openWarehouseFilter();
    expect(filter.trigger.textContent).toContain("GENERAL");
    expect(within(filter.field).queryByRole("button", { name: "Almacén local" })).toBeNull();
  });

  it.each(["stock.current", "stock.offers", "stock.memberPrice", "stock.noDiscount", "stock.promotions"] as const)(
    "removes only the chosen criterion from %s and its server query", async initialView => {
      render(<StockScreen {...props} app="venta" initialView={initialView} />);
      await waitFor(() => expect(pageQueries()).toHaveLength(1));
      await applyInventoryType();
      const search = screen.getByRole("searchbox", { name: "Buscar artículo" });
      fireEvent.change(search, { target: { value: "café" } });
      await waitFor(() => expect(pageQueries().at(-1)?.get("search")).toBe("café"));
      const beforeRemove = pageQueries().at(-1)!;
      expect(screen.getByRole("group", { name: "Filtros aplicados" }).textContent).toContain("Tipo: Unidad");

      fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Tipo" }));
      await waitFor(() => expect(pageQueries().at(-1)?.get("type")).toBeNull());
      const afterRemove = pageQueries().at(-1)!;
      expect(afterRemove.get("search")).toBe("café");
      expect(afterRemove.get("view")).toBe(beforeRemove.get("view"));
      expect(afterRemove.get("sortBy")).toBe(beforeRemove.get("sortBy"));
      expect(afterRemove.get("sortDirection")).toBe(beforeRemove.get("sortDirection"));
      expect(afterRemove.get("warehouseId")).toBe(warehouseId);
      expect(screen.queryByRole("button", { name: "Quitar filtro Tipo" })).toBeNull();

      fireEvent.click(screen.getByRole("button", { name: "Filtrar stock" }));
      const dialog = screen.getByRole("dialog", { name: "Filtrar stock" });
      const field = within(dialog).getByText("Tipo", { exact: true }).closest(".filter-field")!;
      expect(within(field as HTMLElement).getByRole("button").textContent).toContain("Todas");
      fireEvent.click(within(dialog).getByRole("button", { name: "Cerrar" }));
      fireEvent.click(screen.getByRole("button", { name: "Limpiar todos" }));
      await waitFor(() => expect(pageQueries().at(-1)?.get("search")).toBeNull());
      expect((search as HTMLInputElement).value).toBe("");
      expect(screen.queryByRole("group", { name: "Filtros aplicados" })).toBeNull();
    }
  );

  it("keeps a user-selected inventory order when clearing every filter", async () => {
    localStorage.setItem(tableSortStorageKey("venta", session.username, "stock.inventory.stock.current"),
      JSON.stringify({ column: "name", direction: "desc" }));
    render(<StockScreen {...props} app="venta" />);
    await applyInventoryType();
    fireEvent.click(screen.getByRole("button", { name: "Limpiar todos" }));
    await waitFor(() => expect(pageQueries().at(-1)?.get("type")).toBeNull());
    expect(pageQueries().at(-1)?.get("sortBy")).toBe("name");
    expect(pageQueries().at(-1)?.get("sortDirection")).toBe("desc");
  });

  it("updates top-sales rows locally and resets only the selected period in the backend request", async () => {
    const { container } = render(<StockScreen {...props} app="venta" initialView="stock.topSales" />);
    await waitFor(() => expect(container.querySelectorAll("article.stock-row")).toHaveLength(2));
    fireEvent.click(screen.getByRole("button", { name: "Filtrar top ventas" }));
    const dialog = screen.getByRole("dialog", { name: "Filtrar top ventas" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Mes" }));
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Proveedor" }), { target: { value: "Uno" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Aplicar filtro" }));
    fireEvent.change(screen.getByRole("searchbox", { name: "Buscar top ventas" }), { target: { value: "Café" } });
    expect(screen.queryByText("Agua", { selector: "strong.product-name-text" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Buscar" }));
    expect(container.querySelectorAll("article.stock-row")).toHaveLength(1);
    expect(screen.getByRole("button", { name: "Quitar filtro Proveedor" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Restablecer período a Semana" }));
    await waitFor(() => {
      const path = vi.mocked(apiRequest).mock.calls.map(([path]) => path).filter(path => path.startsWith("/stock/top-sales")).at(-1)!;
      expect(new URL(path, "http://test").searchParams.get("period")).toBe("week");
    });
    expect(screen.getByRole("button", { name: "Quitar filtro Proveedor" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Proveedor" }));
    expect(container.querySelectorAll("article.stock-row")).toHaveLength(2);
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).toBeNull();
  });

  it("restores bulk rows without losing pending edits or the remaining price criterion", async () => {
    const { container } = await openBulkWorkspace();
    const firstRow = container.querySelector<HTMLElement>('[data-bulk-row-id="row-0"]')!;
    fireEvent.change(within(firstRow).getByRole("textbox", { name: "Precio venta" }), { target: { value: "9.75" } });
    fireEvent.click(screen.getByRole("button", { name: /^Filtrar/ }));
    const dialog = screen.getByRole("dialog", { name: "Filtrar edición masiva" });
    fireEvent.change(within(dialog).getByRole("spinbutton", { name: "Precio mínimo" }), { target: { value: "4" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Aplicar" }));
    fireEvent.change(screen.getByRole("searchbox", { name: "Buscar en la lista" }), { target: { value: "Café" } });
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Buscar" }));
    expect(container.querySelector('[data-bulk-row-id="row-1"]')).toBeNull();
    expect(screen.getByRole("button", { name: "Quitar filtro Precio mínimo" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Limpiar todos" }));
    expect(container.querySelector('[data-bulk-row-id="row-1"]')).not.toBeNull();
    expect(within(container.querySelector<HTMLElement>('[data-bulk-row-id="row-0"]')!).getByRole("textbox", { name: "Precio venta" }))
      .toHaveProperty("value", "9.75");
    expect(vi.mocked(apiRequest).mock.calls.some(([, options]) => options?.method === "PUT" || options?.method === "POST")).toBe(false);
  });

  it("keeps an independent bulk subfamily editable when removing only its family chip", async () => {
    const { container } = await openBulkWorkspace();
    fireEvent.click(screen.getByRole("button", { name: /^Filtrar/ }));
    let dialog = screen.getByRole("dialog", { name: "Filtrar edición masiva" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Familia" }));
    fireEvent.click(await screen.findByRole("option", { name: "Bebidas" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Subfamilia" }));
    fireEvent.click(screen.getByRole("option", { name: "Café" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Aplicar" }));
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Familia" }));
    expect(screen.getByRole("group", { name: "Filtros aplicados" }).textContent).toContain("Subfamilia: Café");
    expect(container.querySelector('[data-bulk-row-id="row-0"]')).not.toBeNull();
    expect(container.querySelector('[data-bulk-row-id="row-1"]')).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /^Filtrar/ }));
    dialog = screen.getByRole("dialog", { name: "Filtrar edición masiva" });
    const subfamily = within(dialog).getByRole("button", { name: "Subfamilia" });
    expect(subfamily).toHaveProperty("disabled", false);
    expect(subfamily.textContent).toContain("Café");
    fireEvent.click(subfamily);
    expect(screen.getByRole("option", { name: "Bebidas / Agua" })).toBeTruthy();
    fireEvent.click(screen.getByRole("option", { name: "Bebidas / Café" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancelar" }));
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Subfamilia" }));
    expect(container.querySelector('[data-bulk-row-id="row-1"]')).not.toBeNull();
  });

  it.each(["stock.current", "stock.topSales", "stock.offers", "stock.memberPrice", "stock.noDiscount", "stock.promotions"] as const)("removes the same applied search criterion in APP GESTIÓN %s", async initialView => {
    const { container } = render(<StockScreen {...props} app="gestion" initialView={initialView} />);
    const search = screen.getByRole("searchbox", { name: initialView === "stock.topSales" ? "Buscar top ventas" : "Buscar artículo" });
    fireEvent.change(search, { target: { value: "Café" } });
    expect(container.querySelector(".stock-screen.erp-classic-tables")).not.toBeNull();
    expect(screen.getByRole("group", { name: "Filtros aplicados" }).textContent).toContain("Café");
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Buscar" }));
    expect(search).toHaveProperty("value", "");
    expect(screen.queryByRole("button", { name: "Quitar filtro Buscar" })).toBeNull();
  });
});
