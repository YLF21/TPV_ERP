// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import type { UserSession } from "../types";
import { StockScreen, type StockTopSalesRow } from "./StockScreen";

vi.mock("../api/client", async (importOriginal) => ({
  ...await importOriginal<typeof import("../api/client")>(), apiRequest: vi.fn(),
}));

const topSalesRows: StockTopSalesRow[] = [
  { productId: "product-1", code: "CAFE-1", barcode: "8430000000001", name: "Café de prueba",
    familyId: null, familyName: "Bebidas", subfamilyId: null, subfamilyName: "Café", suppliers: [],
    soldQuantity: 12, netAmount: 54, currentStock: 5, warehouseId: "warehouse-1", warehouseName: "GENERAL" },
  { productId: "product-2", code: "TE-2", barcode: "8430000000002", name: "Té de prueba",
    familyId: null, familyName: "Bebidas", subfamilyId: null, subfamilyName: "Té", suppliers: [],
    soldQuantity: 8, netAmount: 32, currentStock: 7, warehouseId: "warehouse-2", warehouseName: "RESERVA" },
];

const products = [
  { id: "product-1", code: "CAFE-1", barcode: "8430000000001", name: "Café de prueba",
    description: "Tueste natural completo", productType: "UNIT", salePrice: "4.50" },
  { id: "product-2", code: "TE-2", barcode: "8430000000002", name: "Té de prueba",
    description: "Té verde completo", productType: "UNIT", salePrice: "4.00" },
];

const stockByProduct = [
  [{ productId: "product-1", warehouseId: "warehouse-1", quantity: 3 },
    { productId: "product-1", warehouseId: "warehouse-2", quantity: 2 }],
  [{ productId: "product-2", warehouseId: "warehouse-1", quantity: 1 },
    { productId: "product-2", warehouseId: "warehouse-2", quantity: 6 }],
];

function setupApi() {
  vi.mocked(apiRequest).mockImplementation(async (path) => {
    if (path.startsWith("/stock/page")) return { items: [], hasMore: false };
    if (path.startsWith("/stock/top-sales")) return topSalesRows;
    if (path === "/document-reports/date-options?report=topSales") {
      return { currentDate: "2026-10-18", earliestDate: "2026-01-01" };
    }
    if (path === "/warehouses") return [
      { id: "warehouse-1", name: "GENERAL", defaultWarehouse: true, active: true },
      { id: "warehouse-2", name: "RESERVA", defaultWarehouse: false, active: true },
    ];
    const productMatch = /^\/products(?:\/management)?\/(product-[12])$/.exec(path);
    if (productMatch) return products[Number(productMatch[1].at(-1)) - 1];
    const stockMatch = /^\/stock\?productId=(product-[12])$/.exec(path);
    if (stockMatch) return stockByProduct[Number(stockMatch[1].at(-1)) - 1];
    return [];
  });
}

function renderTopSales(app: "venta" | "gestion", permissions: UserSession["permissions"] = ["STOCK_READ"]) {
  return render(<StockScreen app={app} initialView="stock.topSales" locale="es"
    session={{ username: `top-sales-${app}`, displayName: "Top Sales", permissions, accessToken: "test-token" }}
    terminalContext={{ storeName: "Tienda", terminalCode: "001" }}
    onBack={vi.fn()} onLocaleChange={vi.fn()} />);
}

async function rows(container: HTMLElement) {
  await screen.findByText("Café de prueba");
  await screen.findByText("Té de prueba");
  return [...container.querySelectorAll<HTMLElement>(".stock-top-sales-table article.stock-row")];
}

beforeEach(() => { localStorage.clear(); vi.mocked(apiRequest).mockReset(); setupApi(); });
afterEach(() => { cleanup(); localStorage.clear(); vi.resetAllMocks(); });

describe("Top ventas product selection", () => {
  it.each(["venta", "gestion"] as const)("keeps the same product selected through sorting and filtering in %s", async app => {
    const { container } = renderTopSales(app);
    const [, second] = await rows(container);
    fireEvent.click(second);

    const tableRows = () => [...container.querySelectorAll<HTMLElement>(".stock-top-sales-table article.stock-row")];
    const sortByName = within(container.querySelector<HTMLElement>('.stock-top-sales-table [data-column-key="name"]')!)
      .getByRole("button", { name: "Nombre" });
    fireEvent.click(sortByName);
    fireEvent.click(sortByName);
    expect(tableRows()[0].textContent).toContain("Té de prueba");
    expect(tableRows()[0].classList.contains("selected")).toBe(true);

    const search = screen.getByRole("searchbox", { name: "Buscar top ventas" });
    fireEvent.change(search, { target: { value: "Té" } });
    expect(tableRows()).toHaveLength(1);
    expect(tableRows()[0].classList.contains("selected")).toBe(true);
    fireEvent.change(search, { target: { value: "" } });
    expect(tableRows()).toHaveLength(2);
    expect(tableRows().find(row => row.textContent?.includes("Té de prueba"))?.classList.contains("selected")).toBe(true);
  });

  it.each(["venta", "gestion"] as const)("selects on one click and opens complete product information on double click in %s", async app => {
    const { container } = renderTopSales(app);
    const [first, second] = await rows(container);

    fireEvent.click(second);
    expect(second.classList.contains("selected")).toBe(true);
    expect(first.classList.contains("selected")).toBe(false);
    expect(screen.queryByRole("dialog", { name: "Información del producto" })).toBeNull();
    expect(vi.mocked(apiRequest).mock.calls.some(([path]) => path.startsWith("/products/"))).toBe(false);

    fireEvent.doubleClick(second);
    const dialog = await screen.findByRole("dialog", { name: "Información del producto" });
    const information = within(dialog).getByRole("tabpanel", { name: "Información F5" });
    expect(within(information).getByText("Té verde completo")).toBeTruthy();
    expect(within(information).getByText("RESERVA")).toBeTruthy();
    expect(within(information).getByText("GENERAL")).toBeTruthy();
    expect(vi.mocked(apiRequest)).toHaveBeenCalledWith("/products/product-2", expect.objectContaining({ token: "test-token" }));
    expect(vi.mocked(apiRequest)).toHaveBeenCalledWith("/stock?productId=product-2", expect.objectContaining({ token: "test-token" }));
    expect(screen.queryByRole("dialog", { name: "Modificar producto" })).toBeNull();
    expect(within(dialog).queryByRole("button", { name: "Modificar producto F7" })).toBeNull();
  });

  it.each(["venta", "gestion"] as const)("moves selection with arrows and opens the selected row with Enter in %s", async app => {
    const { container } = renderTopSales(app);
    const [first, second] = await rows(container);
    const table = container.querySelector<HTMLElement>(".stock-top-sales-table")!;
    table.focus();
    expect(document.activeElement).toBe(table);

    fireEvent.keyDown(table, { key: "ArrowDown" });
    expect(second.classList.contains("selected")).toBe(true);
    fireEvent.keyDown(table, { key: "ArrowUp" });
    expect(first.classList.contains("selected")).toBe(true);
    fireEvent.keyDown(table, { key: "ArrowDown" });
    fireEvent.keyDown(table, { key: "Enter" });

    const dialog = await screen.findByRole("dialog", { name: "Información del producto" });
    expect(within(dialog).getByText("Té verde completo")).toBeTruthy();
    expect(vi.mocked(apiRequest)).toHaveBeenCalledWith("/products/product-2", expect.anything());
  });

  it.each(["venta", "gestion"] as const)("returns focus to the table and keeps selection when the detail closes in %s", async app => {
    const { container } = renderTopSales(app);
    const [, second] = await rows(container);
    const table = container.querySelector<HTMLElement>(".stock-top-sales-table")!;
    table.focus();
    fireEvent.doubleClick(second);
    const dialog = await screen.findByRole("dialog", { name: "Información del producto" });
    fireEvent.keyDown(dialog, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Información del producto" })).toBeNull());
    expect(document.activeElement).toBe(table);
    expect(second.classList.contains("selected")).toBe(true);
  });

  it.each(["venta", "gestion"] as const)("uses the management read route only with product-management permission in %s", async app => {
    const { container } = renderTopSales(app, ["ADMIN"]);
    const [first] = await rows(container);
    fireEvent.doubleClick(first);
    const dialog = await screen.findByRole("dialog", { name: "Información del producto" });
    expect(within(dialog).getByText("Tueste natural completo")).toBeTruthy();
    expect(vi.mocked(apiRequest)).toHaveBeenCalledWith("/products/management/product-1", expect.objectContaining({ token: "test-token" }));
    expect(vi.mocked(apiRequest)).toHaveBeenCalledWith("/stock?productId=product-1", expect.anything());
    expect(vi.mocked(apiRequest).mock.calls.some(([path]) => path === "/products/product-1")).toBe(false);
  });

  it("never invents a detail dialog when loading the selected product fails", async () => {
    vi.mocked(apiRequest).mockImplementation(async path => {
      if (path.startsWith("/stock/top-sales")) return topSalesRows;
      if (path === "/document-reports/date-options?report=topSales") return { currentDate: "2026-10-18", earliestDate: "2026-01-01" };
      if (path === "/warehouses") return [];
      if (path === "/products/product-1") throw new Error("Product unavailable");
      return [];
    });
    const { container } = renderTopSales("venta");
    const [first] = await rows(container);
    fireEvent.doubleClick(first);
    await waitFor(() => expect(vi.mocked(apiRequest)).toHaveBeenCalledWith("/products/product-1", expect.anything()));
    expect(screen.queryByRole("dialog", { name: "Información del producto" })).toBeNull();
    expect(await screen.findByText("No se pudo cargar la información del producto.")).toBeTruthy();
  });

  it("discards a late product response after leaving Top ventas", async () => {
    let completeProduct!: (product: typeof products[0]) => void;
    const delayedProduct = new Promise<typeof products[0]>(resolve => { completeProduct = resolve; });
    vi.mocked(apiRequest).mockImplementation(async path => {
      if (path.startsWith("/stock/top-sales")) return topSalesRows;
      if (path.startsWith("/stock/page")) return { items: [], hasMore: false };
      if (path === "/products/product-1") return delayedProduct;
      if (path === "/stock?productId=product-1") return stockByProduct[0];
      if (path === "/document-reports/date-options?report=topSales") return { currentDate: "2026-10-18", earliestDate: "2026-01-01" };
      if (path === "/warehouses") return [];
      return [];
    });
    const session: UserSession = { username: "late-top-sales", displayName: "Top Sales", permissions: ["STOCK_READ"], accessToken: "test-token" };
    const props = {
      app: "venta" as const, locale: "es" as const, session,
      terminalContext: { storeName: "Tienda", terminalCode: "001" }, onBack: vi.fn(), onLocaleChange: vi.fn(),
    };
    const view = render(<StockScreen {...props} initialView="stock.topSales" />);
    const [first] = await rows(view.container);
    fireEvent.doubleClick(first);
    await waitFor(() => expect(vi.mocked(apiRequest)).toHaveBeenCalledWith("/products/product-1", expect.anything()));
    view.rerender(<StockScreen {...props} initialView="stock.current" />);
    await screen.findByRole("searchbox", { name: "Buscar artículo" });
    await act(async () => { completeProduct(products[0]); });
    expect(screen.queryByRole("dialog", { name: "Información del producto" })).toBeNull();
    expect(screen.queryByText("Tueste natural completo")).toBeNull();
  });

  it("discards a late product response after selecting another row", async () => {
    let completeProduct!: (product: typeof products[0]) => void;
    const delayedProduct = new Promise<typeof products[0]>(resolve => { completeProduct = resolve; });
    vi.mocked(apiRequest).mockImplementation(async path => {
      if (path.startsWith("/stock/top-sales")) return topSalesRows;
      if (path.startsWith("/stock/page")) return { items: [], hasMore: false };
      if (path === "/products/product-1") return delayedProduct;
      if (path === "/stock?productId=product-1") return stockByProduct[0];
      if (path === "/document-reports/date-options?report=topSales") return { currentDate: "2026-10-18", earliestDate: "2026-01-01" };
      if (path === "/warehouses") return [];
      return [];
    });
    const { container } = renderTopSales("venta");
    const [first, second] = await rows(container);
    fireEvent.doubleClick(first);
    await waitFor(() => expect(vi.mocked(apiRequest)).toHaveBeenCalledWith("/products/product-1", expect.anything()));
    fireEvent.click(second);
    await act(async () => { completeProduct(products[0]); });
    expect(second.classList.contains("selected")).toBe(true);
    expect(screen.queryByRole("dialog", { name: "Información del producto" })).toBeNull();
  });
});
