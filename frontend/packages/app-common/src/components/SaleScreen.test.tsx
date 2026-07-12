import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  SaleScreen,
  addSaleLine,
  effectiveSaleProductPrice,
  filterSaleCustomers,
  filterSaleProducts,
  removeSaleLine,
  selectedProductAfterRemoval,
  saleLineSubtotal,
  saleOfferIsCurrent,
  saleProductBlocksManualDiscount,
  saleTotal,
  selectSaleProduct,
  updateSaleLineDiscount,
  updateSaleLineQuantity,
  type SaleCustomer,
  type SaleProduct
} from "./SaleScreen";
import type { TerminalContext, UserSession } from "../types";

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"]
};

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01"
};

const products: SaleProduct[] = [
  { id: "coffee", code: "CAF-001", barcode: "8410000000011", barcode2: "ALT-CAFE", name: "Cafe molido", salePrice: 10 },
  { id: "bread", code: "PAN-001", barcode: "8410000000028", name: "Pan integral", salePrice: "2.50" },
  { id: "milk", code: "LEC-001", barcode: "8410000000035", name: "Leche fresca", salePrice: 1.75 }
];

const customers: SaleCustomer[] = [
  { id: "customer-1", clientId: "C-001", fiscalName: "Cliente Pruebas SL", documentNumber: "B11111111" },
  { id: "customer-2", clientId: "C-002", fiscalName: "Maria Lopez", documentNumber: "12345678Z" }
];

describe("SaleScreen", () => {
  it("renders the sales workspace with shared frame controls", () => {
    const html = renderToStaticMarkup(
      <SaleScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
        onLogout={vi.fn()}
      />
    );

    expect(html).toContain('class="sale-screen work-screen"');
    expect(html).toContain('class="report-user-button"');
    expect(html).toContain('class="language-button"');
    expect(html).toContain('class="shutdown-button"');
    expect(html).toContain('class="report-footer-context"');
    expect(html).toContain("Venta");
    expect(html).toContain("Añadir producto");
    expect(html).toContain("Ticket actual");
    expect(html).toContain("Cobro");
    expect(html).toContain("Sin venta iniciada");
    expect(html).toContain('aria-label="Buscar producto"');
    expect(html).toContain('aria-controls="sale-product-results"');
    expect(html).toContain("Cargando productos");
    expect(html).not.toContain("Cafe molido");
    expect(html).not.toContain("Pan integral");
    expect(html).not.toContain("Leche fresca");
    expect(html).not.toContain("15,15");
  });

  it("filters products by name without case sensitivity", () => {
    expect(filterSaleProducts(products, "  CAFE ").map((product) => product.id)).toEqual(["coffee"]);
  });

  it("filters products by internal code or barcode", () => {
    expect(filterSaleProducts(products, "PAN-0").map((product) => product.id)).toEqual(["bread"]);
    expect(filterSaleProducts(products, "0000000011").map((product) => product.id)).toEqual(["coffee"]);
    expect(filterSaleProducts(products, "alt-cafe").map((product) => product.id)).toEqual(["coffee"]);
  });

  it("limits visible search results", () => {
    const manyProducts = Array.from({ length: 12 }, (_, index) => ({
      id: String(index),
      code: `CODE-${index}`,
      name: `Product ${index}`,
      salePrice: index
    }));

    expect(filterSaleProducts(manyProducts, "product")).toHaveLength(10);
  });

  it("prioritizes an exact code or barcode when selecting with Enter", () => {
    const ambiguous: SaleProduct[] = [
      ...products,
      { id: "code-in-name", code: "OTHER", name: "Accessory CAF-001", salePrice: 3 }
    ];

    expect(selectSaleProduct(ambiguous, "caf-001")?.id).toBe("coffee");
    expect(selectSaleProduct(products, "8410000000028")?.id).toBe("bread");
    expect(selectSaleProduct(products, "alt-cafe")?.id).toBe("coffee");
  });

  it("selects the only partial match with Enter", () => {
    expect(selectSaleProduct(products, "leche")?.id).toBe("milk");
    expect(selectSaleProduct(products, "00")).toBeUndefined();
  });

  it("adds products, increments repeated quantities and calculates the total", () => {
    const first = addSaleLine([], products[0]);
    const repeated = addSaleLine(first, products[0]);
    const completed = addSaleLine(repeated, products[1]);

    expect(completed).toEqual([
      { product: products[0], quantity: 2, discountPercent: 0 },
      { product: products[1], quantity: 1, discountPercent: 0 }
    ]);
    expect(saleTotal(completed)).toBe(22.5);
  });

  it("displays the configured member or valid offer price and falls back after expiry", () => {
    expect(effectiveSaleProductPrice({
      id: "member",
      salePrice: 10,
      memberPrice: 8.5,
      priceUseMode: "MEMBER_PRICE"
    }, "2026-07-11")).toBe(8.5);

    const offered: SaleProduct = {
      id: "offer",
      salePrice: 10,
      offerPrice: 7.5,
      priceUseMode: "OFFER_PRICE",
      offerActive: true,
      offerFrom: "2026-07-01",
      offerUntil: "2026-07-31"
    };
    expect(saleOfferIsCurrent(offered, "2026-07-11")).toBe(true);
    expect(effectiveSaleProductPrice(offered, "2026-07-11")).toBe(7.5);
    expect(effectiveSaleProductPrice(offered, "2026-08-01")).toBe(10);
    expect(effectiveSaleProductPrice({
      ...offered,
      offerPrice: null,
      offerDiscountPercent: 25,
      priceUseMode: "OFFER_DISCOUNT"
    }, "2026-07-11")).toBe(7.5);
    expect(effectiveSaleProductPrice({ ...offered, offerActive: false }, "2026-07-11")).toBe(10);
  });

  it("updates quantity only with valid integer values", () => {
    const lines = addSaleLine([], products[0]);

    expect(updateSaleLineQuantity(lines, "coffee", 4)[0].quantity).toBe(4);
    expect(() => updateSaleLineQuantity(lines, "coffee", 0)).toThrow("invalid_quantity");
    expect(() => updateSaleLineQuantity(lines, "coffee", 1.5)).toThrow("invalid_quantity");
  });

  it("applies a line discount and recalculates subtotal and total", () => {
    const lines = updateSaleLineQuantity(addSaleLine([], products[0]), "coffee", 2);
    const discounted = updateSaleLineDiscount(lines, "coffee", 25);

    expect(discounted[0].discountPercent).toBe(25);
    expect(saleLineSubtotal(discounted[0])).toBe(15);
    expect(saleTotal(discounted)).toBe(15);
    expect(() => updateSaleLineDiscount(lines, "coffee", 101)).toThrow("invalid_discount");
    expect(() => updateSaleLineDiscount(lines, "coffee", 12.345)).toThrow("invalid_discount");
  });

  it("blocks manual discounts when the backend discount type is NONE", () => {
    const blockedProduct: SaleProduct = { ...products[0], discountType: "NONE" };
    const lines = addSaleLine([], blockedProduct);

    expect(saleProductBlocksManualDiscount(blockedProduct)).toBe(true);
    expect(() => updateSaleLineDiscount(lines, "coffee", 10)).toThrow("discount_blocked");
    expect(updateSaleLineDiscount(lines, "coffee", 0)[0].discountPercent).toBe(0);
  });

  it("keeps the next available line selected after removal", () => {
    const lines = [
      { product: products[0], quantity: 1, discountPercent: 0 },
      { product: products[1], quantity: 1, discountPercent: 0 },
      { product: products[2], quantity: 1, discountPercent: 0 }
    ];

    expect(selectedProductAfterRemoval(lines, "bread")).toBe("milk");
    expect(selectedProductAfterRemoval(lines, "milk")).toBe("bread");
  });

  it("does not increment a line above the maximum quantity", () => {
    const lines = [{ product: products[0], quantity: 9999, discountPercent: 0 }];
    expect(addSaleLine(lines, products[0])[0].quantity).toBe(9999);
  });

  it("removes only the requested sale line", () => {
    const lines = addSaleLine(addSaleLine([], products[0]), products[1]);

    expect(removeSaleLine(lines, "coffee")).toEqual([{ product: products[1], quantity: 1, discountPercent: 0 }]);
  });

  it("filters customers by name, document or client code", () => {
    expect(filterSaleCustomers(customers, "pruebas").map((customer) => customer.id)).toEqual(["customer-1"]);
    expect(filterSaleCustomers(customers, "12345678").map((customer) => customer.id)).toEqual(["customer-2"]);
    expect(filterSaleCustomers(customers, "c-002").map((customer) => customer.id)).toEqual(["customer-2"]);
  });
});
