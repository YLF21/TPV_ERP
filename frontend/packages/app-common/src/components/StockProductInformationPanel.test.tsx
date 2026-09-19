import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { StockInventoryRow } from "./StockScreen";
import {
  calculateNetPurchasePrice,
  sortProductInformationSuppliers,
  StockProductInformationPanel
} from "./StockProductInformationPanel";

const product: StockInventoryRow = {
  productId: "product-1",
  imageId: "image-1",
  active: "common.yes",
  warehouseId: "warehouse-1",
  code: "A001",
  barcode: "8430000000011",
  barcode2: "8430000000012",
  name: "Cafe molido",
  description: "Tueste natural",
  comments: "Proveedor habitual",
  purchasePrice: "4.20",
  salePrice: "6.50",
  memberPrice: "6.00",
  wholesalePrice: "5.30",
  offerPrice: "",
  productType: "UNIT",
  discountType: "NORMAL",
  familyId: "family-1",
  familyName: "Bebidas",
  subfamilyId: "subfamily-1",
  subfamilyName: "Cafe",
  taxId: "tax-1",
  taxName: "IGIC 7%",
  taxesIncluded: "common.yes",
  offerActive: "common.no",
  offerFrom: "",
  offerUntil: "",
  warehouseName: "GENERAL",
  quantity: 12,
  totalQuantity: 18
};

describe("StockProductInformationPanel", () => {
  it("calculates the net purchase price from the gross price and discount", () => {
    expect(calculateNetPurchasePrice("4,10", "20")).toBeCloseTo(3.28);
    expect(calculateNetPurchasePrice("4.10", "")).toBeCloseTo(4.10);
    expect(calculateNetPurchasePrice("", "20")).toBeNull();
  });

  it("orders the principal supplier first and the last supplier second", () => {
    const suppliers = sortProductInformationSuppliers([
      { supplierId: "a", legalName: "Alfa", active: true, principal: false, lastSupplier: false },
      { supplierId: "b", legalName: "Beta", active: true, principal: false, lastSupplier: true },
      { supplierId: "c", legalName: "Gamma", active: true, principal: true, lastSupplier: false }
    ]);

    expect(suppliers.map((supplier) => supplier.supplierId)).toEqual(["c", "b", "a"]);
  });

  it("renders product information without exposing supplier economics without permission", () => {
    const html = renderToStaticMarkup(
      <StockProductInformationPanel
        product={product}
        locale="es"
        token="token"
        canReadSuppliers={false}
        canViewPurchaseFields={false}
      />
    );

    expect(html).toContain("stock-product-information");
    expect(html).toContain("Información del producto");
    expect(html).toContain("Cafe molido");
    expect(html).not.toContain("Precio compra");
    expect(html).not.toContain("Precio compra neto");
    expect(html).toContain("Precio venta");
    expect(html).toContain("No tienes permiso para consultar los datos económicos de proveedores");
    expect(html).not.toContain("-%");
  });

  it("renders purchase economics only with product management permission", () => {
    const html = renderToStaticMarkup(
      <StockProductInformationPanel
        product={product}
        locale="es"
        token="token"
        canReadSuppliers={false}
        canViewPurchaseFields={true}
      />
    );

    expect(html).toContain("Precio compra");
    expect(html).toContain("Precio compra neto");
  });

  it("keeps the product fields and warehouse stock together in the information view", () => {
    const html = renderToStaticMarkup(
      <StockProductInformationPanel
        product={{
          ...product,
          purchaseDiscountPercent: "5",
          packageQuantity: "2",
          offerActive: "common.yes",
          offerPrice: "5.75",
          offerDiscountPercent: "10",
          offerFrom: "2026-09-01",
          offerUntil: "2026-09-30",
          promotionNames: "Promoción de septiembre",
          stockMin: "3",
          stockMax: "24"
        }}
        locale="es"
        canReadSuppliers={false}
        canViewPurchaseFields
        stockContent={
          <table aria-label="Stock por almacén">
            <tbody><tr><td>GENERAL</td><td>12</td></tr></tbody>
          </table>
        }
      />
    );

    for (const value of [
      "Cafe molido", "A001", "8430000000011", "8430000000012", "Activo",
      "Tueste natural", "Proveedor habitual", "Unidad", "Bebidas", "Cafe", "IGIC 7%",
      "2,00", "4,20", "5,00%", "3,99", "6,50", "6,00", "5,30",
      "5,75", "10,00%", "1/9/26", "30/9/26", "Promoción de septiembre",
      "3,00", "24,00", "18,00", "GENERAL"
    ]) {
      expect(html).toContain(value);
    }
    expect(html).toContain('<table aria-label="Stock por almacén">');
    expect(html).not.toContain("<button");
  });
});
