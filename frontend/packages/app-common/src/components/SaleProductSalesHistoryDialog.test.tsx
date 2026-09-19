// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import type { SaleProduct } from "./SaleScreen";
import { SaleProductSalesHistoryDialog } from "./SaleProductSalesHistoryDialog";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof import("../api/client")>("../api/client");
  return { ...actual, apiRequest: vi.fn() };
});

const apiRequestMock = vi.mocked(apiRequest);
const product: SaleProduct = {
  id: "product-1",
  code: "2004461",
  barcode: "8435606744034",
  barcode2: "ALT-2004461",
  name: "Cargador de red rápida",
  salePrice: 8.2,
  taxId: "tax-1",
  taxesIncluded: true,
  taxRegime: "IVA",
  taxPercentage: 21,
};

describe("SaleProductSalesHistoryDialog", () => {
  beforeEach(() => {
    apiRequestMock.mockReset();
    apiRequestMock.mockImplementation(async (path) => path.includes("/sales-history/saas?") ? {
      companyId: "company-1", productCode: "2004461", coverage: "RECEIVED_IN_SAAS",
      items: [], stores: [{ id: "store-1", code: "S01", name: "Principal" }], totals: [], comparison: [],
      nextCursor: null, hasMore: false, incompleteDocuments: 0,
    } : []);
    localStorage.clear();
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
  });

  it("opens empty and searches a product inside the history window", async () => {
    render(
      <SaleProductSalesHistoryDialog
        products={[product]}
        locale="es"
        username="ADMIN"
        accessToken="access-token"
        onClose={vi.fn()}
      />,
    );

    expect(screen.getByRole("dialog", { name: "Historial de ventas" })).toBeTruthy();
    expect(screen.getByText("Busca un producto para consultar sus ventas")).toBeTruthy();
    const search = screen.getByRole("textbox", { name: "Código, código de barras o nombre" });
    fireEvent.change(search, { target: { value: "8435606744034" } });
    fireEvent.keyDown(search, { key: "Enter" });

    expect((await screen.findAllByText(product.name ?? "")).length).toBeGreaterThan(0);
    await waitFor(() => expect(apiRequestMock).toHaveBeenCalledWith(
      expect.stringContaining("/stock/products/product-1/sales-history/saas?"),
      { token: "access-token", signal: expect.any(AbortSignal) },
    ));
  });

  it("loads the selected cart product directly", async () => {
    render(
      <SaleProductSalesHistoryDialog
        products={[product]}
        initialProduct={product}
        locale="es"
        accessToken="access-token"
        onClose={vi.fn()}
      />,
    );

    expect((screen.getByRole("textbox", { name: "Código, código de barras o nombre" }) as HTMLInputElement).value)
      .toBe("2004461");
    expect(screen.getAllByText(product.name ?? "")).toHaveLength(1);
    expect(screen.getByText("Código: 2004461")).toBeTruthy();
    await waitFor(() => expect(apiRequestMock).toHaveBeenCalledWith(
      expect.stringContaining("/stock/products/product-1/sales-history/saas?"),
      { token: "access-token", signal: expect.any(AbortSignal) },
    ));
  });

  it("clears the current article and returns focus to search for the next one", async () => {
    const onClose = vi.fn();
    render(<SaleProductSalesHistoryDialog products={[product]} initialProduct={product}
      locale="es" accessToken="access-token" onClose={onClose} />);
    await waitFor(() => expect(apiRequestMock).toHaveBeenCalled());

    fireEvent.click(screen.getByRole("button", { name: "Limpiar" }));
    const search = screen.getByRole("textbox", { name: "Código, código de barras o nombre" });
    expect((search as HTMLInputElement).value).toBe("");
    expect(document.activeElement).toBe(search);
    expect(screen.queryByRole("table")).toBeNull();
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.change(search, { target: { value: product.barcode2 } });
    fireEvent.keyDown(search, { key: "Enter" });
    expect(screen.getAllByText(product.name ?? "")).toHaveLength(1);
    await waitFor(() => expect(apiRequestMock.mock.calls.filter(([path]) => path.includes("/sales-history")))
      .toHaveLength(2));
  });

  it("closes with Escape from a search result before an article is selected", () => {
    const onClose = vi.fn();
    render(<SaleProductSalesHistoryDialog products={[product]} locale="es" onClose={onClose} />);
    fireEvent.change(screen.getByRole("textbox", { name: "Código, código de barras o nombre" }),
      { target: { value: "cargador" } });
    const result = screen.getByRole("option");
    result.focus();
    fireEvent.keyDown(result, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("uses the shared SaaS store and comparison controls from F6", async () => {
    const onClose = vi.fn();
    render(<SaleProductSalesHistoryDialog products={[product]} initialProduct={product}
      locale="es" accessToken="access-token" onClose={onClose} />);
    await screen.findByText("SIN DATOS");
    expect(screen.queryByText("Solo datos recibidos en SaaS")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Comparación por tienda" }));
    expect(screen.getByRole("columnheader", { name: /Cantidad total/ })).toBeTruthy();
    const store = screen.getByRole("button", { name: "Tienda" });
    fireEvent.click(store);
    fireEvent.keyDown(screen.getByRole("option", { name: "Todas las tiendas" }), { key: "Escape" });
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.keyDown(store, { key: "Escape" });
    expect(onClose).toHaveBeenCalledOnce();
  });
});
