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
    apiRequestMock.mockResolvedValue([]);
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
      expect.stringContaining("/stock/products/product-1/sales-history"),
      { token: "access-token" },
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
    expect(document.querySelector(".sale-sales-history-product-image")?.textContent).toBe("C");
    await waitFor(() => expect(apiRequestMock).toHaveBeenCalledWith(
      expect.stringContaining("/stock/products/product-1/sales-history"),
      { token: "access-token" },
    ));
  });
});
