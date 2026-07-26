import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
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
});
