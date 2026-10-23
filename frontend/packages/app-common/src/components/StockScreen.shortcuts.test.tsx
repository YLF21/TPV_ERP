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
    if (path.startsWith("/document-reports/date-options?report=topSales")) {
      return { currentDate: "2026-10-15", earliestDate: "2026-01-01" };
    }
    if (path.startsWith("/stock/top-sales")) return [];
    if (path === "/promotions") return [];
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
  app?: "venta" | "gestion" | "pda";
  session?: UserSession;
  initialView?: "stock.current" | "stock.topSales" | "stock.offers" | "stock.memberPrice" | "stock.promotions" | "stock.noDiscount" | "stock.bulkEdit";
  initialPartyDirectory?: "customers";
  allowSafeRetirement?: boolean;
} = {}) {
  const view = render(<StockScreen
    {...props}
    app={options.app ?? "venta"}
    session={options.session ?? baseSession}
    initialView={options.initialView}
    initialPartyDirectory={options.initialPartyDirectory}
    allowSafeRetirement={options.allowSafeRetirement}
  />);
  if (options.initialPartyDirectory) {
    await screen.findByRole("heading", { name: "Clientes" });
  } else if (options.initialView === "stock.bulkEdit") {
    await screen.findByRole("button", { name: options.app === "pda" ? "Nuevo" : "Nuevo F5" });
  } else if (options.app === "gestion") {
    await screen.findByRole("button", { name: "Añadir producto F5" });
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

  it("keeps F5 on the APP VENTA product detail modal for switching to the Stock tab", async () => {
    const { container } = await renderStock();
    fireEvent.doubleClick(container.querySelector("article.stock-row")!);
    const detail = await screen.findByRole("dialog", { name: "Información del producto" });
    fireEvent.click(within(detail).getByRole("tab", { name: "Historial de venta F6" }));
    expect(within(detail).getByRole("tab", { name: "Historial de venta F6" }).getAttribute("aria-selected")).toBe("true");

    fireEvent.keyDown(detail, { key: "F5" });

    expect(within(detail).getByRole("tab", { name: "Información F5" }).getAttribute("aria-selected")).toBe("true");
    expect(screen.queryByRole("dialog", { name: "Añadir producto" })).toBeNull();
  });

  it.each([
    "stock.current",
    "stock.topSales",
    "stock.offers",
    "stock.memberPrice",
    "stock.noDiscount",
  ] as const)("opens Añadir producto with F5 in APP GESTIÓN %s", async (initialView) => {
    await renderStock({ app: "gestion", initialView, allowSafeRetirement: true });

    fireEvent.keyDown(window, { key: "F5" });

    expect(await screen.findByRole("dialog", { name: "Añadir producto" })).toBeTruthy();
  });

  it("opens product creation with F5 when the APP GESTIÓN stock table has focus", async () => {
    const { container } = await renderStock({ app: "gestion", allowSafeRetirement: true });
    const table = container.querySelector<HTMLElement>(".stock-table")!;
    table.focus();

    fireEvent.keyDown(table, { key: "F5" });

    expect(await screen.findByRole("dialog", { name: "Añadir producto" })).toBeTruthy();
  });

  it.each([
    { repeat: true },
    { ctrlKey: true },
    { altKey: true },
    { metaKey: true },
    { shiftKey: true },
  ])("ignores repeated or modified F5 in APP GESTIÓN", async (eventOptions) => {
    await renderStock({ app: "gestion", allowSafeRetirement: true });

    fireEvent.keyDown(window, { key: "F5", ...eventOptions });

    expect(screen.queryByRole("dialog", { name: "Añadir producto" })).toBeNull();
  });

  it("keeps product creation out of APP PDA and leaves its button without F5", async () => {
    await renderStock({ app: "pda" });
    const addButton = screen.getByRole("button", { name: "Añadir producto" });

    fireEvent.keyDown(window, { key: "F5" });

    expect(addButton.getAttribute("aria-keyshortcuts")).not.toBe("F5");
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

describe("StockScreen bulk workspace creation shortcut", () => {
  it.each(["gestion", "venta"] as const)("opens a new list with F5 while the %s workspace table has focus", async (app) => {
    const { container } = await renderStock({ app, initialView: "stock.bulkEdit", allowSafeRetirement: app === "gestion" });
    expect(screen.getAllByRole("button", { name: "Nuevo F5" })).toHaveLength(1);
    expect(screen.getByRole("button", { name: "Nuevo F5" }).getAttribute("aria-keyshortcuts")).toBe("F5");
    const table = container.querySelector<HTMLElement>(".stock-bulk-workspace-table-wrap")!;
    table.focus();

    fireEvent.keyDown(table, { key: "F5" });

    expect(await screen.findByText("Lista nueva sin guardar")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Aplicar cambios" })).toBeTruthy();
    expect(screen.queryByRole("dialog", { name: "Añadir producto" })).toBeNull();
    expect(vi.mocked(apiRequest).mock.calls.some(([, options]) => options?.method === "POST" || options?.method === "PUT")).toBe(false);
  });

  it.each(["gestion", "venta"] as const)("ignores repeated or modified F5 before creating a list in %s", async (app) => {
    await renderStock({ app, initialView: "stock.bulkEdit" });
    for (const modifier of [{ repeat: true }, { ctrlKey: true }, { altKey: true }, { metaKey: true }, { shiftKey: true }]) {
      fireEvent.keyDown(window, { key: "F5", ...modifier });
    }

    expect(screen.queryByText("Lista nueva sin guardar")).toBeNull();
    expect(screen.getByRole("button", { name: "Nuevo F5" })).toBeTruthy();
  });

  it.each(["gestion", "venta"] as const)("keeps the %s editor and its save dialog when F5 is pressed again", async (app) => {
    await renderStock({ app, initialView: "stock.bulkEdit" });
    fireEvent.click(screen.getByRole("button", { name: "Nuevo F5" }));
    await screen.findByText("Lista nueva sin guardar");
    fireEvent.keyDown(screen.getByRole("textbox", { name: "Código" }), { key: "s", ctrlKey: true });
    const dialog = await screen.findByRole("dialog", { name: "Guardar lista de productos" });
    const name = within(dialog).getByRole("textbox", { name: "Nombre de la lista" });
    fireEvent.change(name, { target: { value: "Lista pendiente" } });

    expect(fireEvent.keyDown(name, { key: "F5" })).toBe(false);

    expect((name as HTMLInputElement).value).toBe("Lista pendiente");
    expect(screen.getByRole("dialog", { name: "Guardar lista de productos" })).toBeTruthy();
  });
});
