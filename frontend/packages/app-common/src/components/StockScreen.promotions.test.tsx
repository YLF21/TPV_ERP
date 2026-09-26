// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import type { UserSession } from "../types";
import type { PromotionView } from "./PromotionForm";
import { StockScreen } from "./StockScreen";

vi.mock("../api/client", async (importOriginal) => ({
  ...await importOriginal<typeof import("../api/client")>(), apiRequest: vi.fn()
}));

const warehouseId = "11111111-1111-4111-8111-111111111111";
const session: UserSession = {
  username: "promotions", displayName: "Promotions", permissions: ["ADMIN"], accessToken: "test"
};
const props = {
  locale: "es" as const, session, terminalContext: { storeName: "Test", terminalCode: "TEST" },
  onBack: vi.fn(), onLocaleChange: vi.fn(), initialView: "stock.promotions" as const
};
const promotions: PromotionView[] = [
  {
    id: "current", name: "Promoción vigente", type: "PURCHASE_THRESHOLD_DISCOUNT", status: "ACTIVE",
    startDate: "2026-01-01", endDate: null, scope: "PRODUCT_LIST", customerSegment: "ALL",
    memberCategoryId: null, minimumAmount: 10, minimumQuantity: null, buyQuantity: null,
    payQuantity: null, buyXPayYMode: null, discountAmount: null, discountPercent: 10,
    maximumDiscount: null, packPrice: null, used: false,
    targets: [{ type: "PRODUCT", targetId: "p-1" }]
  },
  {
    id: "expired", name: "Promoción caducada", type: "PURCHASE_THRESHOLD_DISCOUNT", status: "INACTIVE",
    startDate: "2025-01-01", endDate: "2025-01-31", scope: "PRODUCT_LIST", customerSegment: "ALL",
    memberCategoryId: null, minimumAmount: 5, minimumQuantity: null, buyQuantity: null,
    payQuantity: null, buyXPayYMode: null, discountAmount: null, discountPercent: 15,
    maximumDiscount: null, packPrice: null, used: true, usageCount: 3,
    targets: [{ type: "PRODUCT", targetId: "p-2" }]
  }
];

function item(id: string, name: string) {
  return {
    product: { id, code: id, name, active: true, productType: "UNIT", familyId: "drinks" },
    stock: [{ productId: id, warehouseId, quantity: 4 }]
  };
}

function pageQueries() {
  return vi.mocked(apiRequest).mock.calls.map(([path]) => path)
    .filter(path => path.startsWith("/stock/page"))
    .map(path => new URL(path, "http://test").searchParams);
}

beforeEach(() => {
  localStorage.clear();
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: vi.fn() });
  vi.stubGlobal("fetch", vi.fn(async () => Response.json([])));
  vi.mocked(apiRequest).mockReset().mockImplementation(async path => {
    if (path.startsWith("/stock/page")) {
      const cursor = new URL(path, "http://test").searchParams.get("cursor");
      return cursor === "second"
        ? { items: [item("p-2", "Café antiguo")], hasMore: false }
        : { items: [item("p-1", "Té actual")], nextCursor: "second", hasMore: true };
    }
    if (path === "/warehouses") return [{ id: warehouseId, name: "GENERAL", active: true, defaultWarehouse: true }];
    if (path === "/promotions") return promotions;
    if (path === "/families") return [{ id: "drinks", name: "Bebidas" }];
    if (path === "/families/drinks/subfamilies") return [];
    return [];
  });
});

afterEach(() => {
  cleanup(); localStorage.clear(); vi.unstubAllGlobals();
  delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView;
});

describe("StockScreen promotions", () => {
  it.each(["venta", "gestion"] as const)("loads every page and shows an inactive expired promotion in %s", async app => {
    render(<StockScreen {...props} app={app} />);
    const expired = await screen.findByRole("button", { name: /Promoción caducada/ });
    expect(pageQueries().map(query => query.get("cursor"))).toEqual([null, "second"]);
    expect(screen.getByRole("button", { name: /Promoción vigente/ })).toBeTruthy();
    expect(expired.textContent).toContain("Caducada");
    expect(expired.className).toContain("expired");
    fireEvent.click(expired);
    expect(screen.getByText("Café antiguo")).toBeTruthy();
    expect(screen.queryByText("Té actual")).toBeNull();
  });

  it("keeps a valid selection through arrow navigation and inventory search", async () => {
    render(<StockScreen {...props} app="venta" />);
    const first = await screen.findByRole("button", { name: /Promoción vigente/ });
    first.focus();
    fireEvent.keyDown(first, { key: "ArrowDown" });
    const expired = screen.getByRole("button", { name: /Promoción caducada/ });
    expect(expired).toHaveProperty("tabIndex", 0);
    expect(screen.getByText("Café antiguo")).toBeTruthy();
    fireEvent.change(screen.getByRole("searchbox", { name: "Buscar artículo" }), { target: { value: "Café" } });
    await waitFor(() => expect(pageQueries().at(-1)?.get("search")).toBe("Café"));
    expect(screen.getByRole("button", { name: /Promoción caducada/ })).toHaveProperty("tabIndex", 0);
    expect(screen.getByText("Café antiguo")).toBeTruthy();
    fireEvent.change(screen.getByRole("searchbox", { name: "Buscar promociones…" }),
      { target: { value: "caducada" } });
    expect(screen.getByRole("button", { name: /Promoción caducada/ })).toHaveProperty("tabIndex", 0);
    expect(screen.getByText("Café antiguo")).toBeTruthy();
  });

  it("does not show a partial promotion detail when a later stock page fails", async () => {
    vi.mocked(apiRequest).mockImplementation(async path => {
      if (path.startsWith("/stock/page")) {
        if (new URL(path, "http://test").searchParams.has("cursor")) throw new Error("page failed");
        return { items: [item("p-1", "Té actual")], nextCursor: "second", hasMore: true };
      }
      if (path === "/warehouses") return [{ id: warehouseId, name: "GENERAL", active: true, defaultWarehouse: true }];
      if (path === "/promotions") return promotions;
      return [];
    });
    render(<StockScreen {...props} app="venta" />);
    await waitFor(() => expect(pageQueries()).toHaveLength(2));
    await waitFor(() => expect(screen.queryByText("Té actual")).toBeNull());
    expect(screen.queryByText("Café antiguo")).toBeNull();
    expect(screen.queryByRole("button", { name: /Promoción vigente/ })).toBeNull();
    expect(screen.queryByRole("button", { name: /Promoción caducada/ })).toBeNull();
  });
});
