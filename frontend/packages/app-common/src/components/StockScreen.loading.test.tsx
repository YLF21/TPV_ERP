// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import type { UserSession } from "../types";
import { StockScreen } from "./StockScreen";
import { tableSortStorageKey } from "./tableSorting";

vi.mock("../api/client", async (importOriginal) => ({
  ...await importOriginal<typeof import("../api/client")>(), apiRequest: vi.fn()
}));

const warehouseId = "11111111-1111-4111-8111-111111111111";
const warehouses = [{ id: warehouseId, name: "GENERAL", defaultWarehouse: true, active: true }];
const props = {
  app: "venta" as const,
  locale: "es" as const,
  session: { username: "stock-loading", displayName: "Stock", permissions: ["ADMIN"], accessToken: "test" } satisfies UserSession,
  terminalContext: { storeName: "Test", terminalCode: "TEST" },
  onBack: vi.fn(),
  onLocaleChange: vi.fn()
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((fulfil) => { resolve = fulfil; });
  return { promise, resolve };
}

function page(codes: string[], nextCursor: string | null = null, productOverrides: { priceUseMode?: string; discountType?: string } = {}) {
  return {
    items: codes.map((code) => ({
      product: {
        id: `product-${code}`, code, name: `Artículo ${code}`, salePrice: "1.50", productType: "UNIT",
        priceUseMode: "OFFER_PRICE", discountType: "DISCOUNT_PRICE", active: true, ...productOverrides
      },
      stock: [{ productId: `product-${code}`, warehouseId, quantity: 10 }]
    })),
    hasMore: nextCursor !== null,
    nextCursor
  };
}

function pageCalls() {
  return vi.mocked(apiRequest).mock.calls
    .map(([path]) => path)
    .filter((path) => path.startsWith("/stock/page"));
}

function displayedCodes(container: HTMLElement) {
  return Array.from(container.querySelectorAll("article.stock-row"), (row) => row.children[0]?.textContent);
}

beforeEach(() => {
  localStorage.clear();
  vi.mocked(apiRequest).mockReset();
});
afterEach(() => { cleanup(); localStorage.clear(); });

describe("Stock loads one consistent ordered result", () => {
  it.each([
    { app: "venta", initialView: "stock.current" },
    { app: "gestion", initialView: "stock.current" },
    { app: "gestion", initialView: "stock.offers" },
    { app: "gestion", initialView: "stock.memberPrice" },
    { app: "gestion", initialView: "stock.noDiscount" }
  ] as const)("waits for the warehouse and renders $app $initialView once in code order without flashing a saved order", async ({ app, initialView }) => {
    if (app === "gestion") {
      localStorage.setItem(tableSortStorageKey(app, props.session.username, `stock.inventory.${initialView}`),
        JSON.stringify({ column: "name", direction: "desc" }));
    }
    const warehouseResponse = deferred<typeof warehouses>();
    const firstPage = deferred<ReturnType<typeof page>>();
    vi.mocked(apiRequest).mockImplementation(async (path) => {
      if (path === "/warehouses") return warehouseResponse.promise;
      if (path.startsWith("/stock/page")) return firstPage.promise;
      return [];
    });
    const { container } = render(<StockScreen {...props} app={app} initialView={initialView} embedded={app === "gestion"} />);
    const renderedOrders: string[] = [];
    const observer = new MutationObserver(() => {
      const codes = displayedCodes(container);
      if (codes.length) renderedOrders.push(codes.join(","));
    });
    observer.observe(container, { childList: true, subtree: true, characterData: true });
    try {
      await act(async () => { await Promise.resolve(); });
      expect(pageCalls()).toEqual([]);
      expect(displayedCodes(container)).toEqual([]);

      await act(async () => { warehouseResponse.resolve(warehouses); });
      await waitFor(() => expect(pageCalls()).toHaveLength(1));
      const query = new URL(pageCalls()[0], "http://test").searchParams;
      expect(query.get("sortBy")).toBe("code");
      expect(query.get("sortDirection")).toBe("asc");
      expect(query.get("warehouseId")).toBe(warehouseId);
      expect(displayedCodes(container)).toEqual([]);

      const productOverrides = initialView === "stock.memberPrice"
        ? { priceUseMode: "MEMBER_PRICE", discountType: "MEMBER_PRICE" }
        : initialView === "stock.noDiscount" ? { priceUseMode: "NORMAL", discountType: "NONE" } : {};
      await act(async () => { firstPage.resolve(page(["1", "2", "10"], null, productOverrides)); });
      await screen.findByText("Artículo 10");
      await act(async () => { await Promise.resolve(); });
      expect(displayedCodes(container)).toEqual(["1", "2", "10"]);
      expect([...new Set(renderedOrders)]).toEqual(["1,2,10"]);
      expect(pageCalls()).toHaveLength(1);
    } finally {
      observer.disconnect();
    }
  });

  it("does not display the previous order while loading a new sort", async () => {
    const descendingPage = deferred<ReturnType<typeof page>>();
    vi.mocked(apiRequest).mockImplementation(async (path) => {
      if (path === "/warehouses") return warehouses;
      if (path.startsWith("/stock/page")) {
        return path.includes("sortDirection=desc") ? descendingPage.promise : page(["1", "2", "10"]);
      }
      return [];
    });
    const { container } = render(<StockScreen {...props} />);
    await screen.findByText("Artículo 10");
    fireEvent.click(screen.getByRole("button", { name: "Código" }));
    expect(displayedCodes(container)).toEqual([]);
    await waitFor(() => expect(pageCalls().some((path) => path.includes("sortDirection=desc"))).toBe(true));
    expect(displayedCodes(container)).toEqual([]);
    await act(async () => { descendingPage.resolve(page(["10", "2", "1"])); });
    await screen.findByText("Artículo 10");
    expect(displayedCodes(container)).toEqual(["10", "2", "1"]);
  });

  it("does not carry the previous view's rows into a view that is still loading", async () => {
    const offersPage = deferred<ReturnType<typeof page>>();
    vi.mocked(apiRequest).mockImplementation(async (path) => {
      if (path === "/warehouses") return warehouses;
      if (path.startsWith("/stock/page")) {
        return path.includes("view=offers") ? offersPage.promise : page(["1"]);
      }
      return [];
    });
    const { container } = render(<StockScreen {...props} />);
    await screen.findByText("Artículo 1");
    fireEvent.click(screen.getByRole("button", { name: "Productos con oferta" }));
    expect(displayedCodes(container)).toEqual([]);
    await waitFor(() => expect(pageCalls().some((path) => path.includes("view=offers"))).toBe(true));
    await act(async () => { offersPage.resolve(page(["2"])); });
    await screen.findByText("Artículo 2");
    expect(displayedCodes(container)).toEqual(["2"]);
    expect(screen.queryByText("Artículo 1")).toBeNull();
  });

  it("discards an old pagination response after changing the sort", async () => {
    const oldNextPage = deferred<ReturnType<typeof page>>();
    const descendingPage = deferred<ReturnType<typeof page>>();
    vi.mocked(apiRequest).mockImplementation(async (path) => {
      if (path === "/warehouses") return warehouses;
      if (path.startsWith("/stock/page")) {
        if (path.includes("cursor=old-asc-page")) return oldNextPage.promise;
        if (path.includes("sortDirection=desc")) return descendingPage.promise;
        return page(["1", "2"], "old-asc-page");
      }
      return [];
    });
    const { container } = render(<StockScreen {...props} />);
    await screen.findByText("Artículo 2");
    fireEvent.scroll(container.querySelector(".stock-table")!);
    await waitFor(() => expect(pageCalls().some((path) => path.includes("cursor=old-asc-page"))).toBe(true));
    fireEvent.click(screen.getByRole("button", { name: "Código" }));
    await waitFor(() => expect(pageCalls().some((path) => path.includes("sortDirection=desc"))).toBe(true));
    await act(async () => { descendingPage.resolve(page(["10", "2"])); });
    await screen.findByText("Artículo 10");
    expect(displayedCodes(container)).toEqual(["10", "2"]);
    await act(async () => { oldNextPage.resolve(page(["3", "4"])); });
    expect(displayedCodes(container)).toEqual(["10", "2"]);
    expect(screen.queryByText("Artículo 3")).toBeNull();
    expect(screen.queryByText("Artículo 4")).toBeNull();
  });
});
