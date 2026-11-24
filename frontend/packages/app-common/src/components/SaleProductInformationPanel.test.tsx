// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it } from "vitest";
import type { StockInventoryRow } from "./StockScreen";
import { SaleProductInformationPanel } from "./SaleProductInformationPanel";

const product: StockInventoryRow = {
  productId: "product-1",
  active: "common.yes",
  warehouseId: "warehouse-1",
  code: "A001",
  barcode: "8430000000011",
  barcode2: "8430000000012",
  name: "Nombre de producto completo que debe adaptarse al ancho disponible sin quedar recortado",
  description: "Descripción completa",
  comments: "Comentario interno",
  purchasePrice: "4.20",
  purchaseDiscountPercent: "20",
  salePrice: "6.50",
  memberPrice: "6.00",
  wholesalePrice: "5.30",
  offerPrice: "",
  offerDiscountPercent: "",
  productType: "UNIT",
  discountType: "NORMAL",
  familyId: "family-1",
  familyName: "Bebidas",
  subfamilyId: "subfamily-1",
  subfamilyName: "Café",
  taxId: "tax-1",
  taxName: "IGIC 7%",
  taxesIncluded: "common.yes",
  offerActive: "common.no",
  offerFrom: "",
  offerUntil: "",
  warehouseName: "GENERAL",
  quantity: 12,
  totalQuantity: 18,
};

afterEach(cleanup);

function renderSaleInformation(item: StockInventoryRow) {
  return render(
    <SaleProductInformationPanel product={item} locale="es" token="token"
      canReadSuppliers={false} canViewPurchaseFields={false} />,
  );
}

describe("SaleProductInformationPanel", () => {
  it("uses the sale-specific responsive structure and keeps the full product name", () => {
    const html = renderToStaticMarkup(
      <SaleProductInformationPanel
        product={product}
        locale="es"
        token="token"
        canReadSuppliers={false}
        canViewPurchaseFields={false}
      />,
    );

    expect(html).toContain("sale-product-details");
    expect(html).toContain("sale-product-details-hero");
    expect(html).toContain(product.name);
    expect(html).not.toContain("stock-product-information");
    expect(html).not.toContain("Producto desactivado");
    expect(html).toContain("sale-product-details-status\"><b>A001</b>");
  });

  it("shows a prominent warning only when the product is inactive", () => {
    const html = renderToStaticMarkup(
      <SaleProductInformationPanel
        product={{ ...product, active: "common.no" }}
        locale="es"
        token="token"
        canReadSuppliers={false}
        canViewPurchaseFields={false}
      />,
    );

    expect(html).toContain("sale-product-details-inactive");
    expect(html).toContain("Producto desactivado");
    expect(html).not.toContain("sale-product-details-status\"><span");
  });

  it("does not expose purchase economics or supplier data without permission", () => {
    const html = renderToStaticMarkup(
      <SaleProductInformationPanel
        product={product}
        locale="es"
        token="token"
        canReadSuppliers={false}
        canViewPurchaseFields={false}
      />,
    );

    expect(html).not.toContain("Precio compra");
    expect(html).not.toContain("Precio compra neto");
    expect(html).toContain("No tienes permiso para consultar los datos económicos de proveedores");
  });

  it("shows purchase economics only when product management permission is present", () => {
    const html = renderToStaticMarkup(
      <SaleProductInformationPanel
        product={product}
        locale="es"
        token="token"
        canReadSuppliers={true}
        canViewPurchaseFields={true}
      />,
    );

    expect(html).toContain("Precio compra");
    expect(html).toContain("Precio compra neto");
  });

  it("opens offers by default and returns there after consulting active pack tiers", () => {
    renderSaleInformation({ ...product, quantityPricing: {
      mode: "PACK", tiers: [{ quantity: 3, price: "1.234" }],
    } });

    const offers = screen.getByRole("tab", { name: "Oferta y promociones" });
    const quantity = screen.getByRole("tab", { name: "Precio por cantidad" });
    expect(offers).toHaveAttribute("aria-selected", "true");
    expect(quantity).toHaveAttribute("aria-selected", "false");
    expect(screen.getByRole("button", { name: "Historial" })).toBeInTheDocument();

    offers.focus();
    fireEvent.keyDown(offers, { key: "ArrowRight" });
    expect(quantity).toHaveAttribute("aria-selected", "true");
    expect(quantity).toHaveFocus();
    expect(screen.queryByRole("button", { name: "Historial" })).not.toBeInTheDocument();
    const history = document.querySelector(".product-commercial-history-trigger");
    expect(history?.closest("[inert]")).not.toBeNull();
    expect(screen.getByText("Unidades por lote")).toBeInTheDocument();
    expect(screen.getByText("Precio del lote")).toBeInTheDocument();
    expect(screen.getByText("1,234", { exact: false })).toBeInTheDocument();

    fireEvent.keyDown(quantity, { key: "ArrowLeft" });
    expect(offers).toHaveAttribute("aria-selected", "true");
    expect(offers).toHaveFocus();
    expect(screen.getByRole("button", { name: "Historial" })).toBeInTheDocument();
    expect(history?.closest("[inert]")).toBeNull();
  });

  it("shows the unit threshold columns for an active rule", () => {
    renderSaleInformation({ ...product, quantityPricing: {
      mode: "UNIT_THRESHOLD", tiers: [{ quantity: 12, price: "0.875" }],
    } });
    fireEvent.click(screen.getByRole("tab", { name: "Precio por cantidad" }));
    expect(screen.getByText("Desde")).toBeInTheDocument();
    expect(screen.getByText("Precio por unidad")).toBeInTheDocument();
    expect(screen.getByText("0,875", { exact: false })).toBeInTheDocument();
  });

  it("omits the quantity tab without an applicable rule", () => {
    const cases: StockInventoryRow[] = [
      { ...product, quantityPricing: null },
      { ...product, quantityPricing: { mode: "PACK", tiers: [], enabled: true } },
      { ...product, quantityPricing: { mode: "PACK", tiers: [{ quantity: 3, price: "1.234" }], enabled: false } },
      { ...product, discountType: "NONE", quantityPricing: { mode: "PACK", tiers: [{ quantity: 3, price: "1.234" }] } },
    ];
    for (const item of cases) {
      const view = renderSaleInformation(item);
      expect(screen.queryByRole("tab", { name: "Precio por cantidad" })).not.toBeInTheDocument();
      view.unmount();
    }
  });
});
