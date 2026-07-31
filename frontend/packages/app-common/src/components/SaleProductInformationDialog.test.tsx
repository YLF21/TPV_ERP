// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { SaleProduct } from "./SaleScreen";
import {
  buildSaleProductInformationRow,
  SaleProductInformationDialog,
} from "./SaleProductInformationDialog";

const product: SaleProduct = {
  id: "product-1",
  code: "CAF-001",
  name: "Café molido",
  salePrice: 6.5,
  taxId: "tax-1",
  taxesIncluded: true,
  taxRegime: "IGIC",
  taxPercentage: 7,
};

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("SaleProductInformationDialog", () => {
  it("builds the existing product-information row with stock and active promotions", () => {
    const row = buildSaleProductInformationRow(
      {
        id: "product-1",
        name: "Café molido",
        familyId: "family-1",
        subfamilyId: "subfamily-1",
        taxId: "tax-1",
        priceUseMode: "OFFER_PRICE",
        offerActive: true,
        salePrice: 6.5,
      },
      [
        { productId: "product-1", warehouseId: "warehouse-1", quantity: 3 },
        { productId: "product-1", warehouseId: "warehouse-2", quantity: 2 },
      ],
      {
        families: [{ id: "family-1", name: "Bebidas" }],
        subfamilies: [{ id: "subfamily-1", familyId: "family-1", name: "Café" }],
        taxes: [{ id: "tax-1", percentage: 7 }],
        promotions: [
          {
            name: "Oferta café",
            status: "ACTIVE",
            scope: "PRODUCT",
            targets: [{ type: "PRODUCT", targetId: "product-1" }],
          },
        ],
      },
    );

    expect(row.totalQuantity).toBe(5);
    expect(row.familyName).toBe("Bebidas");
    expect(row.subfamilyName).toBe("Café");
    expect(row.taxName).toBe("7%");
    expect(row.promotionNames).toBe("Oferta café");
  });

  it("loads the full information and adds with Insert in keyboard mode", async () => {
    const fetchMock = vi.fn(async (url: string) => {
      const parsed = new URL(url, "http://localhost");
      if (parsed.pathname.endsWith("/products/product-1")) {
        return jsonResponse({
          ...product,
          productType: "UNIT",
          discountType: "NORMAL",
          priceUseMode: "NORMAL",
          familyId: "family-1",
          subfamilyId: "subfamily-1",
          active: true,
          offerActive: false,
        });
      }
      if (parsed.pathname.endsWith("/stock")) {
        return jsonResponse([
          { productId: "product-1", warehouseId: "warehouse-1", quantity: 5 },
        ]);
      }
      if (parsed.pathname.endsWith("/families")) {
        return jsonResponse([{ id: "family-1", name: "Bebidas" }]);
      }
      if (parsed.pathname.endsWith("/families/family-1/subfamilies")) {
        return jsonResponse([{ id: "subfamily-1", familyId: "family-1", name: "Café" }]);
      }
      if (parsed.pathname.endsWith("/taxes/selectable")) {
        return jsonResponse([{ id: "tax-1", percentage: 7 }]);
      }
      return jsonResponse([]);
    });
    vi.stubGlobal("fetch", fetchMock);
    const onAdd = vi.fn();
    render(
      <SaleProductInformationDialog
        product={product}
        locale="es"
        token="token"
        interfaceMode="KEYBOARD"
        canManageProducts={false}
        onAdd={onAdd}
        onClose={vi.fn()}
      />,
    );

    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveAttribute("open");
    const content = dialog.querySelector<HTMLElement>(".sale-product-information-content");
    expect(content).not.toBeNull();
    dialog.scrollLeft = 90;
    content!.scrollLeft = 90;

    expect(await screen.findByText("Bebidas")).toBeVisible();
    expect(dialog.querySelector(".sale-product-details")).not.toBeNull();
    expect(dialog.querySelector(".stock-product-information")).toBeNull();
    expect(dialog.scrollLeft).toBe(0);
    expect(content!.scrollLeft).toBe(0);
    expect(screen.getAllByText("5,00")).not.toHaveLength(0);
    const addButton = screen.getByRole("button", { name: /Añadir al carrito/ });
    expect(addButton.querySelector("kbd")).toHaveTextContent("Insert");
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes("/products/management/"))).toBe(false);

    fireEvent.keyDown(dialog, { key: "Insert" });
    await waitFor(() => expect(onAdd).toHaveBeenCalledWith(product));
  });

  it("uses the management endpoint and exposes protected fields only with permission", async () => {
    const fetchMock = vi.fn(async (url: string) => {
      const parsed = new URL(url, "http://localhost");
      if (parsed.pathname.endsWith("/products/management/product-1")) {
        return jsonResponse({
          ...product,
          productType: "UNIT",
          discountType: "NORMAL",
          priceUseMode: "NORMAL",
          purchasePrice: 4.2,
          purchaseDiscountPercent: 20,
          active: true,
          offerActive: false,
        });
      }
      return jsonResponse([]);
    });
    vi.stubGlobal("fetch", fetchMock);

    render(
      <SaleProductInformationDialog
        product={product}
        locale="es"
        token="token"
        interfaceMode="KEYBOARD"
        canManageProducts
        onAdd={vi.fn()}
        onClose={vi.fn()}
      />,
    );

    expect(await screen.findByText("Precio compra neto")).toBeVisible();
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes("/products/management/product-1"))).toBe(true);
    await waitFor(() => {
      expect(fetchMock.mock.calls.some(([url]) => String(url).includes("/products/product-1/suppliers"))).toBe(true);
    });
  });

  it("shows the add action without a shortcut label in touch mode", () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse([])));
    const onAdd = vi.fn();
    render(
      <SaleProductInformationDialog
        product={product}
        locale="es"
        token="token"
        interfaceMode="TOUCH"
        canManageProducts={false}
        onAdd={onAdd}
        onClose={vi.fn()}
      />,
    );

    const addButton = screen.getByRole("button", { name: "Añadir al carrito" });
    expect(addButton.querySelector("kbd")).toBeNull();
    fireEvent.click(addButton);
    expect(onAdd).toHaveBeenCalledWith(product);
  });
});
