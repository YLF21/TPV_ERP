// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import type { UserSession } from "../types";
import { StockScreen } from "./StockScreen";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof import("../api/client")>("../api/client");
  return { ...actual, apiRequest: vi.fn() };
});

afterEach(() => { cleanup(); localStorage.clear(); vi.resetAllMocks(); });

function mockStockAndHistory() {
  vi.mocked(apiRequest).mockImplementation(async (path) => {
    if (path.startsWith("/stock/page")) return { items: [{
      product: {
        id: "product-1", code: "CAFE-1", name: "Cafe de prueba", salePrice: "4.50", productType: "UNIT",
        description: "Tueste natural", barcode: "8430000000001",
      },
      stock: [{ productId: "product-1", warehouseId: "warehouse-1", quantity: 3 }],
    }], hasMore: false };
    if (path === "/warehouses") return [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true, active: true }];
    if (path.includes("/sales-history/saas?")) return {
      companyId: "company-1", productCode: "CAFE-1", coverage: "RECEIVED_IN_SAAS", items: [], totals: [], comparison: [],
      stores: [{ id: "store-1", code: "S01", name: "Principal" }], hasMore: false, nextCursor: null, incompleteDocuments: 0,
    };
    return [];
  });
}

async function openProductInformation(app: "venta" | "gestion", permissions: UserSession["permissions"] = ["STOCK_READ"]) {
  const { container } = render(<StockScreen app={app} locale="es"
    session={{ username: "demo", displayName: "DEMO", permissions, accessToken: "test-token" }}
    terminalContext={{ storeName: "Tienda de prueba", terminalCode: "DEMO" }}
    onBack={vi.fn()} onLocaleChange={vi.fn()} />);
  await screen.findByText("Cafe de prueba");
  fireEvent.doubleClick(container.querySelector("article.stock-row")!);
  return screen.getByRole("dialog", { name: "Información del producto" });
}

describe("Stock's shared SaaS product history", () => {
  it.each(["venta", "gestion"] as const)("keeps header keyboard actions separate from opening product information in %s", async (app) => {
    mockStockAndHistory();
    const { container } = render(<StockScreen app={app} locale="es"
      session={{ username: "demo", displayName: "DEMO", permissions: ["STOCK_READ"], accessToken: "test-token" }}
      terminalContext={{ storeName: "Tienda de prueba", terminalCode: "DEMO" }}
      onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    await screen.findByText("Cafe de prueba");
    const header = container.querySelector('.stock-header-cell[data-column-key="code"]') as HTMLElement;
    expect(header.getAttribute("aria-sort")).toBe("ascending");
    const menuButton = within(header).getByRole("button", { name: "Opciones de columna" });
    expect(fireEvent.keyDown(menuButton, { key: "Enter" })).toBe(true);
    expect(screen.queryByRole("dialog", { name: "Información del producto" })).toBeNull();
    fireEvent.click(menuButton);
    const menu = screen.getByRole("menu", { name: "Opciones de columna" });
    const sort = within(menu).getByRole("menuitem", { name: "Cambiar orden" });
    expect(fireEvent.keyDown(sort, { key: "Enter" })).toBe(true);
    fireEvent.click(sort);
    expect(header.getAttribute("aria-sort")).toBe("descending");
    expect(screen.queryByRole("dialog", { name: "Información del producto" })).toBeNull();

    await screen.findByText("Cafe de prueba");
    fireEvent.keyDown(container.querySelector(".stock-table")!, { key: "Enter" });
    expect(screen.getByRole("dialog", { name: "Información del producto" })).toBeTruthy();
  });

  it.each(["venta", "gestion"] as const)("combines product information and warehouse stock in F5, and dedicates F6 to history in %s", async (app) => {
    mockStockAndHistory();
    const dialog = await openProductInformation(app);
    const informationTab = within(dialog).getByRole("tab", { name: "Información F5" });
    const historyTab = within(dialog).getByRole("tab", { name: "Historial de venta F6" });

    expect(informationTab.getAttribute("aria-selected")).toBe("true");
    const information = within(dialog).getByRole("tabpanel", { name: "Información F5" });
    expect(within(information).getByText("Tueste natural")).toBeTruthy();
    expect(within(information).getByText("8430000000001")).toBeTruthy();
    expect(within(information).getByText("GENERAL")).toBeTruthy();
    expect(within(information).getAllByText("Stock total").length).toBeGreaterThan(0);
    expect(within(dialog).queryByRole("button", { name: "Modificar producto F7" })).toBeNull();
    fireEvent.keyDown(dialog, { key: "F7" });
    expect(screen.queryByRole("dialog", { name: "Modificar producto" })).toBeNull();

    fireEvent.click(historyTab);
    await within(dialog).findByText("SIN DATOS");
    expect(historyTab.getAttribute("aria-selected")).toBe("true");
    expect(within(dialog).getByRole("tabpanel", { name: "Historial de venta F6" })).toBeTruthy();
    expect(dialog.querySelector(".stock-product-information")).toBeNull();
    expect(within(dialog).queryByText("Tueste natural")).toBeNull();
    expect(within(dialog).queryByText("8430000000001")).toBeNull();
    expect(within(dialog).queryByText("Stock total")).toBeNull();
    expect(within(dialog).getByRole("button", { name: "Aplicar filtro" })).toBeTruthy();
    expect(within(dialog).getByRole("button", { name: "Comparación por tienda" })).toBeTruthy();
    expect(within(dialog).getByRole("button", { name: "Exportar" })).toBeTruthy();

    fireEvent.keyDown(dialog, { key: "F5" });
    expect(informationTab.getAttribute("aria-selected")).toBe("true");
    expect(within(dialog).getByText("Tueste natural")).toBeTruthy();
    expect(within(dialog).getAllByText("Stock total").length).toBeGreaterThan(0);
    expect(within(dialog).queryByRole("button", { name: "Aplicar filtro" })).toBeNull();

    fireEvent.keyDown(dialog, { key: "F6" });
    await within(dialog).findByText("SIN DATOS");
    expect(dialog.querySelector(".stock-product-information")).toBeNull();
    fireEvent.click(informationTab);
    expect(within(dialog).getByText("Tueste natural")).toBeTruthy();
    fireEvent.keyDown(dialog, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "Información del producto" })).toBeNull();
  });

  it.each(["venta", "gestion"] as const)("retains permitted product editing with F7 from the history view in %s", async (app) => {
    mockStockAndHistory();
    const dialog = await openProductInformation(app, ["ADMIN"]);
    fireEvent.keyDown(dialog, { key: "F6" });
    await within(dialog).findByText("SIN DATOS");
    expect(within(dialog).getByRole("button", { name: "Modificar producto F7" })).toBeTruthy();
    fireEvent.keyDown(dialog, { key: "F7" });
    expect(await screen.findByRole("dialog", { name: "Modificar producto" })).toBeTruthy();
  });

  it.each(["venta", "gestion"] as const)("uses central history from %s and closes dropdowns before the product dialog", async (app) => {
    mockStockAndHistory();
    const dialog = await openProductInformation(app);
    fireEvent.keyDown(dialog, { key: "F6" });
    await within(dialog).findByText("SIN DATOS");
    expect(within(dialog).queryByText("Solo datos recibidos en SaaS")).toBeNull();
    fireEvent.click(within(dialog).getByRole("button", { name: "Comparación por tienda" }));
    expect(within(dialog).getByRole("columnheader", { name: /Cantidad total/ })).toBeTruthy();
    const store = within(dialog).getByRole("button", { name: "Tienda" });
    fireEvent.click(store);
    fireEvent.keyDown(screen.getByRole("option", { name: "Todas las tiendas" }), { key: "Escape" });
    expect(screen.getByRole("dialog", { name: "Información del producto" })).toBeTruthy();
    expect(document.activeElement).toBe(store);
    fireEvent.keyDown(store, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Información del producto" })).toBeNull());
    expect(vi.mocked(apiRequest).mock.calls.filter(([path]) => path.includes("/sales-history"))
      .every(([path]) => path.includes("/sales-history/saas?"))).toBe(true);
  });
});
