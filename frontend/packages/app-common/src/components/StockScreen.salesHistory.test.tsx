// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { StockScreen } from "./StockScreen";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof import("../api/client")>("../api/client");
  return { ...actual, apiRequest: vi.fn() };
});

afterEach(() => { cleanup(); localStorage.clear(); vi.resetAllMocks(); });

describe("Stock's shared SaaS product history", () => {
  it.each(["venta", "gestion"] as const)("uses central history from %s and closes dropdowns before the product dialog", async (app) => {
    vi.mocked(apiRequest).mockImplementation(async (path) => {
      if (path.startsWith("/stock/page")) return { items: [{
        product: { id: "product-1", code: "CAFE-1", name: "Cafe de prueba", salePrice: "4.50", productType: "UNIT" },
        stock: [{ productId: "product-1", warehouseId: "warehouse-1", quantity: 3 }],
      }], hasMore: false };
      if (path === "/warehouses") return [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true, active: true }];
      if (path.includes("/sales-history/saas?")) return {
        companyId: "company-1", productCode: "CAFE-1", coverage: "RECEIVED_IN_SAAS", items: [], totals: [], comparison: [],
        stores: [{ id: "store-1", code: "S01", name: "Principal" }], hasMore: false, nextCursor: null, incompleteDocuments: 0,
      };
      return [];
    });
    const { container } = render(<StockScreen app={app} locale="es"
      session={{ username: "demo", displayName: "DEMO", permissions: ["STOCK_READ"], accessToken: "test-token" }}
      terminalContext={{ storeName: "Tienda de prueba", terminalCode: "DEMO" }}
      onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    await screen.findByText("Cafe de prueba");
    fireEvent.doubleClick(container.querySelector("article.stock-row")!);
    const dialog = screen.getByRole("dialog", { name: "Cafe de prueba" });
    fireEvent.keyDown(dialog, { key: "F6" });
    await within(dialog).findByText(/Sin líneas recibidas en SaaS/);
    expect(within(dialog).queryByText("Solo datos recibidos en SaaS")).toBeNull();
    fireEvent.click(within(dialog).getByRole("button", { name: "Comparación por tienda" }));
    expect(within(dialog).getByRole("columnheader", { name: /Cantidad total/ })).toBeTruthy();
    const store = within(dialog).getByRole("button", { name: "Tienda" });
    fireEvent.click(store);
    fireEvent.keyDown(screen.getByRole("option", { name: "Todas las tiendas" }), { key: "Escape" });
    expect(screen.getByRole("dialog", { name: "Cafe de prueba" })).toBeTruthy();
    expect(document.activeElement).toBe(store);
    fireEvent.keyDown(store, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Cafe de prueba" })).toBeNull());
    expect(vi.mocked(apiRequest).mock.calls.filter(([path]) => path.includes("/sales-history"))
      .every(([path]) => path.includes("/sales-history/saas?"))).toBe(true);
  });
});
