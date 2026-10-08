// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import type { UserSession } from "../types";
import { StockScreen } from "./StockScreen";

vi.mock("../api/client", async (importOriginal) => ({
  ...await importOriginal<typeof import("../api/client")>(),
  apiRequest: vi.fn(),
}));

const baseSession: UserSession = {
  username: "shortcuts", displayName: "Shortcuts", permissions: ["ADMIN"], accessToken: "test-token",
};
const props = {
  locale: "es" as const,
  session: baseSession,
  terminalContext: { storeName: "Test", terminalCode: "TEST" },
  onBack: vi.fn(),
  onLocaleChange: vi.fn(),
};

beforeEach(() => {
  localStorage.clear();
  vi.mocked(apiRequest).mockReset().mockImplementation(async (path) => {
    if (path.startsWith("/stock/page")) return {
      items: [{
        product: {
          id: "product-1", code: "CAFE-1", name: "Café de prueba", familyId: "family-1", taxId: "tax-1",
          salePrice: "4.50", purchasePrice: "2.00", productType: "UNIT", priceUseMode: "NORMAL",
          discountType: "NORMAL", active: true, taxesIncluded: true, packageQuantity: "1",
        },
        stock: [{ productId: "product-1", warehouseId: "warehouse-1", quantity: 3 }],
      }],
      hasMore: false,
    };
    if (path === "/warehouses") return [{ id: "warehouse-1", name: "GENERAL", defaultWarehouse: true, active: true }];
    if (path === "/families") return [{ id: "family-1", familyCode: "001", name: "Bebidas", defaultFamily: true }];
    if (path === "/taxes/selectable") return [{ id: "tax-1", percentage: "7", defaultTax: true }];
    if (path === "/products") return [];
    if (path === "/product-bulk-edits") return [];
    return [];
  });
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.resetAllMocks();
});

async function renderStock(options: {
  app?: "venta" | "gestion";
  session?: UserSession;
  initialView?: "stock.current" | "stock.bulkEdit";
  initialPartyDirectory?: "customers";
} = {}) {
  const view = render(<StockScreen
    {...props}
    app={options.app ?? "venta"}
    session={options.session ?? baseSession}
    initialView={options.initialView}
    initialPartyDirectory={options.initialPartyDirectory}
  />);
  if (options.initialPartyDirectory) {
    await screen.findByRole("heading", { name: "Clientes" });
  } else if (options.initialView === "stock.bulkEdit") {
    await screen.findByRole("button", { name: "Nuevo" });
  } else {
    await screen.findByText("Café de prueba");
  }
  return view;
}

describe("StockScreen F5 add-product shortcut", () => {
  it("opens Añadir producto in APP VENTA for a manager while the search field has focus", async () => {
    await renderStock();
    const search = screen.getByRole("searchbox", { name: "Buscar artículo" });
    search.focus();
    fireEvent.keyDown(search, { key: "F5" });

    expect(await screen.findByRole("dialog", { name: "Añadir producto" })).toBeTruthy();
  });

  it("does not open Añadir producto for a read-only stock user", async () => {
    await renderStock({ session: { ...baseSession, permissions: ["STOCK_READ"] } });

    fireEvent.keyDown(window, { key: "F5" });

    expect(screen.queryByRole("dialog", { name: "Añadir producto" })).toBeNull();
    expect(screen.queryByRole("button", { name: /Añadir producto/ })).toBeNull();
  });

  it("keeps F5 on the product detail modal for switching to the Stock tab", async () => {
    const { container } = await renderStock();
    fireEvent.doubleClick(container.querySelector("article.stock-row")!);
    const detail = await screen.findByRole("dialog", { name: "Información del producto" });
    fireEvent.click(within(detail).getByRole("tab", { name: "Historial de venta F6" }));
    expect(within(detail).getByRole("tab", { name: "Historial de venta F6" }).getAttribute("aria-selected")).toBe("true");

    fireEvent.keyDown(detail, { key: "F5" });

    expect(within(detail).getByRole("tab", { name: "Información F5" }).getAttribute("aria-selected")).toBe("true");
    expect(screen.queryByRole("dialog", { name: "Añadir producto" })).toBeNull();
  });

  it("does not open Añadir producto in APP GESTIÓN", async () => {
    await renderStock({ app: "gestion" });

    fireEvent.keyDown(window, { key: "F5" });

    expect(screen.queryByRole("dialog", { name: "Añadir producto" })).toBeNull();
  });

  it.each([
    ["party directory", { initialPartyDirectory: "customers" as const }],
    ["bulk edit", { initialView: "stock.bulkEdit" as const }],
  ])("does not open Añadir producto from %s where the add button is unavailable", async (_surface, options) => {
    await renderStock(options);

    fireEvent.keyDown(window, { key: "F5" });

    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Añadir producto" })).toBeNull());
    expect(screen.queryByRole("button", { name: /Añadir producto/ })).toBeNull();
  });

  it("does not open Añadir producto while another aria-modal dialog is open", async () => {
    await renderStock();
    fireEvent.click(screen.getByRole("button", { name: "Filtrar stock" }));
    const filter = screen.getByRole("dialog", { name: "Filtrar stock" });

    fireEvent.keyDown(filter, { key: "F5" });

    expect(screen.queryByRole("dialog", { name: "Añadir producto" })).toBeNull();
  });
});
