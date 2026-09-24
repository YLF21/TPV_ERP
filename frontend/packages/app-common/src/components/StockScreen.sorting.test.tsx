// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import type { AppKind, UserSession } from "../types";
import { StockScreen, sortProductWarehouseRows, type StockTopSalesRow } from "./StockScreen";
import { StockBulkWorkspaceList } from "./StockBulkWorkspaceList";
import { StockPromotionGroups } from "./StockPromotionGroups";
import type { StockBulkDraftView } from "./stockBulkEdit";
import type { PromotionView } from "./PromotionForm";
import { tableSortStorageKey } from "./tableSorting";

vi.mock("../api/client", async (importOriginal) => ({
  ...await importOriginal<typeof import("../api/client")>(), apiRequest: vi.fn()
}));

const session: UserSession = { username: "sorting", displayName: "Sorting", permissions: ["ADMIN"], accessToken: "test" };
const stockProps = {
  locale: "es" as const, session,
  terminalContext: { storeName: "Test", terminalCode: "TEST" },
  onBack: vi.fn(), onLocaleChange: vi.fn()
};
const topRows: StockTopSalesRow[] = ["10", "2", "1"].map((code, index) => ({
  productId: `p-${code}`, code, barcode: "", name: `Producto ${code}`, familyId: null,
  familyName: "", subfamilyId: null, subfamilyName: "", suppliers: [],
  soldQuantity: 100 - index, netAmount: 100 - index, currentStock: 10,
  warehouseId: "warehouse", warehouseName: "GENERAL"
}));
const filteredViews = [
  ["stock.offers", "Productos con oferta", "offers"],
  ["stock.memberPrice", "Productos con precio de miembro", "member_price"],
  ["stock.noDiscount", "Productos prohibidos a descuento", "no_discount"]
] as const;

function stockPageQueries() {
  return vi.mocked(apiRequest).mock.calls
    .map(([path]) => path)
    .filter((path) => path.startsWith("/stock/page"))
    .map((path) => new URL(path, "http://test").searchParams);
}

beforeEach(() => {
  localStorage.clear();
  vi.mocked(apiRequest).mockReset().mockImplementation(async (path) => {
    if (path.startsWith("/stock/page")) return { items: [], hasMore: false };
    if (path.startsWith("/customers/management/page")) return { items: [], hasMore: false };
    if (path.startsWith("/stock/top-sales")) return topRows;
    return [];
  });
});
afterEach(() => { cleanup(); localStorage.clear(); });

describe("Gestión in APP VENTA defaults to ascending code", () => {
  it.each(["stock.current", "stock.offers", "stock.memberPrice", "stock.noDiscount", "stock.promotions"] as const)(
    "requests the %s server page in ascending code order", async (initialView) => {
      const { container } = render(<StockScreen {...stockProps} app="venta" initialView={initialView} />);
      await waitFor(() => expect(vi.mocked(apiRequest).mock.calls.some(([path]) => {
        if (!path.startsWith("/stock/page")) return false;
        const query = new URL(path, "http://test").searchParams;
        return query.get("sortBy") === "code" && query.get("sortDirection") === "asc";
      })).toBe(true));
      if (initialView !== "stock.promotions") {
        expect(container.querySelector('[data-column-key="code"][role="columnheader"]')?.getAttribute("aria-sort")).toBe("ascending");
      }
    }
  );

  it("keeps a saved inventory sort choice", async () => {
    localStorage.setItem(tableSortStorageKey("venta", session.username, "stock.inventory.stock.current"),
      JSON.stringify({ column: "name", direction: "desc" }));
    render(<StockScreen {...stockProps} app="venta" />);
    await waitFor(() => expect(vi.mocked(apiRequest).mock.calls.some(([path]) =>
      path.startsWith("/stock/page") && path.includes("sortBy=name&sortDirection=desc"))).toBe(true));
  });

  it.each(filteredViews)("opens %s with ascending code on every entry in venta", async (initialView, label, serverView) => {
    localStorage.setItem(tableSortStorageKey("venta", session.username, `stock.inventory.${initialView}`),
      JSON.stringify({ column: "name", direction: "desc" }));
    const { container } = render(<StockScreen {...stockProps} app="venta" initialView={initialView} />);
    const displayedOrder = () => container.querySelector('[data-column-key="code"][role="columnheader"]')?.getAttribute("aria-sort");
    const expectLatestRequest = async (view: string, direction: "asc" | "desc") => {
      await waitFor(() => {
        const query = stockPageQueries().at(-1);
        expect(query?.get("view") ?? "").toBe(view);
        expect(query?.get("sortBy")).toBe("code");
        expect(query?.get("sortDirection")).toBe(direction);
      });
    };

    await expectLatestRequest(serverView, "asc");
    expect(stockPageQueries()).toHaveLength(1);
    expect(displayedOrder()).toBe("ascending");

    fireEvent.click(screen.getByRole("button", { name: "Código" }));
    await expectLatestRequest(serverView, "desc");
    expect(displayedOrder()).toBe("descending");

    fireEvent.click(screen.getByRole("button", { name: "Stock" }));
    await expectLatestRequest("", "asc");
    const beforeReturningFromStock = stockPageQueries().length;
    fireEvent.click(screen.getByRole("button", { name: label }));
    await expectLatestRequest(serverView, "asc");
    expect(stockPageQueries()).toHaveLength(beforeReturningFromStock + 1);
    expect(displayedOrder()).toBe("ascending");

    fireEvent.click(screen.getByRole("button", { name: "Código" }));
    await expectLatestRequest(serverView, "desc");
    fireEvent.click(screen.getByRole("button", { name: "Clientes" }));
    await screen.findByRole("button", { name: "Nuevo cliente" });
    const beforeReturningFromCustomers = stockPageQueries().length;
    fireEvent.click(screen.getByRole("button", { name: label }));
    await expectLatestRequest(serverView, "asc");
    expect(stockPageQueries()).toHaveLength(beforeReturningFromCustomers + 1);
    expect(displayedOrder()).toBe("ascending");
  });

  it.each(filteredViews)("preserves the saved %s order in PDA", async (initialView) => {
    localStorage.setItem(tableSortStorageKey("pda", session.username, `stock.inventory.${initialView}`),
      JSON.stringify({ column: "name", direction: "desc" }));
    const { container } = render(<StockScreen {...stockProps} app="pda" initialView={initialView} />);
    await waitFor(() => expect(stockPageQueries()).toHaveLength(1));
    expect(stockPageQueries()[0].get("sortBy")).toBe("name");
    expect(stockPageQueries()[0].get("sortDirection")).toBe("desc");
    expect(container.querySelector('[data-column-key="name"][role="columnheader"]')?.getAttribute("aria-sort")).toBe("descending");
  });

  it.each([
    ["stock.current", ""],
    ...filteredViews.map(([view, _label, serverView]) => [view, serverView] as const)
  ] as const)("opens gestion %s with code ascending on the first request and every embedded re-entry", async (initialView, serverView) => {
    localStorage.setItem(tableSortStorageKey("gestion", session.username, `stock.inventory.${initialView}`),
      JSON.stringify({ column: "name", direction: "desc" }));
    const renderStock = (view: typeof initialView | "stock.topSales", directory: "customers" | null = null) =>
      <StockScreen {...stockProps} app="gestion" embedded initialView={view} initialPartyDirectory={directory} />;
    const view = render(renderStock(initialView));
    const codeHeader = () => view.container.querySelector('[data-column-key="code"][role="columnheader"]');
    const expectNewCodeRequest = async (countBefore: number, direction: "asc" | "desc") => {
      await waitFor(() => expect(stockPageQueries()).toHaveLength(countBefore + 1));
      const query = stockPageQueries()[countBefore];
      expect(query.get("view") ?? "").toBe(serverView);
      expect(query.get("sortBy")).toBe("code");
      expect(query.get("sortDirection")).toBe(direction);
      expect(codeHeader()?.getAttribute("aria-sort")).toBe(direction === "asc" ? "ascending" : "descending");
    };

    await expectNewCodeRequest(0, "asc");
    fireEvent.click(screen.getByRole("button", { name: "Código" }));
    await expectNewCodeRequest(1, "desc");

    // APP GESTIÓN's shell navigates by changing the embedded initialView prop.
    view.rerender(renderStock("stock.topSales"));
    await screen.findByText("Producto 10");
    const beforeReturn = stockPageQueries().length;
    view.rerender(renderStock(initialView));
    await expectNewCodeRequest(beforeReturn, "asc");
    fireEvent.click(screen.getByRole("button", { name: "Código" }));
    await expectNewCodeRequest(beforeReturn + 1, "desc");

    // A directory can hide stock while its selectedView remains unchanged.
    view.rerender(renderStock(initialView, "customers"));
    await screen.findByRole("button", { name: "Nuevo cliente" });
    const beforeDirectoryReturn = stockPageQueries().length;
    view.rerender(renderStock(initialView));
    await expectNewCodeRequest(beforeDirectoryReturn, "asc");
    fireEvent.click(screen.getByRole("button", { name: "Código" }));
    await expectNewCodeRequest(beforeDirectoryReturn + 1, "desc");

    view.unmount();
    const beforeReopen = stockPageQueries().length;
    const reopened = render(renderStock(initialView));
    await waitFor(() => expect(stockPageQueries()).toHaveLength(beforeReopen + 1));
    const firstReopenedQuery = stockPageQueries()[beforeReopen];
    expect(firstReopenedQuery.get("sortBy")).toBe("code");
    expect(firstReopenedQuery.get("sortDirection")).toBe("asc");
    expect(reopened.container.querySelector('[data-column-key="code"][role="columnheader"]')?.getAttribute("aria-sort")).toBe("ascending");
  });

  it.each(["venta", "gestion"] as const)("keeps sales ranking independent from displayed row order in %s", async (app) => {
    if (app === "venta") {
      localStorage.setItem(tableSortStorageKey(app, session.username, "stock.topSales"),
        JSON.stringify({ column: "code", direction: "desc" }));
    }
    const { container } = render(<StockScreen {...stockProps} app={app} initialView="stock.topSales" />);
    await screen.findByText("Producto 10");
    const values = () => Array.from(container.querySelectorAll("article.stock-row"), (row) => ({
      rank: row.children[0].textContent, code: row.children[1].textContent
    }));
    expect(values()).toEqual([{ rank: "1", code: "10" }, { rank: "2", code: "2" }, { rank: "3", code: "1" }]);
    if (app === "venta") {
      expect(container.querySelector('[data-column-key="ranking"]')?.getAttribute("aria-sort")).toBe("ascending");
    }
    fireEvent.click(screen.getByRole("button", { name: "Código" }));
    expect(values()).toEqual([{ rank: "3", code: "1" }, { rank: "2", code: "2" }, { rank: "1", code: "10" }]);
    if (app === "venta") {
      fireEvent.click(screen.getByRole("button", { name: "Stock" }));
      fireEvent.click(screen.getByRole("button", { name: "Top ventas" }));
      expect(values()).toEqual([{ rank: "1", code: "10" }, { rank: "2", code: "2" }, { rank: "3", code: "1" }]);
      expect(container.querySelector('[data-column-key="ranking"]')?.getAttribute("aria-sort")).toBe("ascending");
    }
  });

  it("keeps the server ranking as the tie-breaker for equivalent product codes", async () => {
    const tiedRows = [
      { ...topRows[0], code: "P02", name: "B", soldQuantity: 100 },
      { ...topRows[1], code: "P2", name: "a", soldQuantity: 100 }
    ];
    vi.mocked(apiRequest).mockImplementation(async (path) => {
      if (path.startsWith("/stock/page")) return { items: [], hasMore: false };
      if (path.startsWith("/stock/top-sales")) return tiedRows;
      return [];
    });
    const { container } = render(<StockScreen {...stockProps} app="venta" initialView="stock.topSales" />);
    await screen.findByText("P02");
    fireEvent.click(screen.getByRole("button", { name: "Código" }));
    expect(Array.from(container.querySelectorAll("article.stock-row"), (row) => ({
      rank: row.children[0].textContent, code: row.children[1].textContent
    }))).toEqual([{ rank: "1", code: "P02" }, { rank: "2", code: "P2" }]);
  });

  it.each(["venta", "gestion"] as const)("sets the saved-list default only in venta (%s)", (app: AppKind) => {
    const drafts = ["10", "2", "1"].map((code) => ({
      id: code, code, name: `Lista ${code}`, status: "PENDING", comments: [], content: [],
      createdAt: "2026-09-22T10:00:00Z", updatedAt: "2026-09-22T10:00:00Z"
    } as unknown as StockBulkDraftView));
    const { container } = render(<StockBulkWorkspaceList app={app} locale="es" session={session}
      username={session.username} drafts={drafts} selectedId={null} busy={false}
      onSelect={vi.fn()} onNew={vi.fn()} onOpen={vi.fn()} onComments={vi.fn()} onRename={vi.fn()} onDelete={vi.fn()} />);
    const codes = () => Array.from(container.querySelectorAll('tbody [data-column-key="code"]'), (cell) => cell.textContent);
    expect(codes()).toEqual(app === "venta" ? ["1", "2", "10"] : ["10", "2", "1"]);
    fireEvent.click(screen.getByRole("button", { name: "Ordenar por ID" }));
    expect(codes()).toEqual(app === "venta" ? ["10", "2", "1"] : ["1", "2", "10"]);
  });

  it.each(["venta", "gestion"] as const)("sets the promotion-product default only in venta (%s)", (app) => {
    const promotion = { id: "promo", name: "Promo", status: "ACTIVE", scope: "SALE", type: "FIXED_PACK_PRICE",
      startDate: "2026-09-01", endDate: null, targets: [], customerSegment: "ALL" } as unknown as PromotionView;
    const { container } = render(<StockPromotionGroups app={app} locale="es" username={session.username}
      promotions={[promotion]} productRows={topRows} t={(key) => key} defaultExpandedPromotionIds={["promo"]} />);
    expect(Array.from(container.querySelectorAll('tbody [data-column-key="code"]'), (cell) => cell.textContent))
      .toEqual(app === "venta" ? ["1", "2", "10"] : ["10", "2", "1"]);
  });
});

describe("Product warehouse order", () => {
  const rows = [
    { warehouseName: "ALMACEN 10", quantity: 5 },
    { warehouseName: "GENERAL", quantity: 24 },
    { warehouseName: "ALMACEN 2", quantity: 0 },
    { warehouseName: "ALMACEN 1", quantity: -2 }
  ];
  it("pins GENERAL first and uses natural warehouse name order by default", () => {
    expect(sortProductWarehouseRows(rows, null, "es").map(row => row.warehouseName))
      .toEqual(["GENERAL", "ALMACEN 1", "ALMACEN 2", "ALMACEN 10"]);
    expect(rows[0].warehouseName).toBe("ALMACEN 10");
  });
  it("keeps GENERAL first even when the user sorts by stock", () => {
    expect(sortProductWarehouseRows(rows, {column:"quantity",direction:"asc"}, "es").map(row => row.quantity))
      .toEqual([24, -2, 0, 5]);
  });
});
