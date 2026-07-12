import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { PromotionView } from "./PromotionForm";
import {
  buildStockPromotionGroups,
  StockPromotionGroups,
  stockPromotionNavigationIndex,
  type StockPromotionProductRow
} from "./StockPromotionGroups";

const messages: Record<string, string> = {
  "stock.promotions": "Products with promotion",
  "stock.promotions.groups.empty": "No active promotions",
  "stock.promotions.groups.noProducts": "No matching products",
  "stock.promotions.groups.rules": "Rules",
  "stock.column.promotion": "Promotion",
  "stock.column.promotionType": "Promotion type",
  "stock.column.promotionValidity": "Validity",
  "stock.column.promotionStatus": "Status",
  "stock.column.code": "Code",
  "stock.column.name": "Name",
  "stock.column.family": "Family",
  "stock.column.subfamily": "Subfamily",
  "stock.column.stock": "Stock",
  "stock.column.amount": "Amount",
  "promotion.field.minimumAmount": "Minimum amount",
  "promotion.field.minimumQuantity": "Minimum quantity",
  "promotion.field.discountAmount": "Discount amount",
  "promotion.field.packPrice": "Pack price",
  "promotion.field.buyXPayYMode": "Buy X pay Y mode",
  "promotion.field.maximumDiscount": "Maximum discount",
  "salesReport.column.product": "Product",
  "salesReport.column.products": "Products",
  "warehouseDocument.product": "Product",
  "promotion.step.conditions": "Conditions",
  "promotion.step.benefit": "Benefit",
  "promotion.field.startDate": "Start date",
  "promotion.field.endDate": "End date",
  "promotion.field.scope": "Scope",
  "promotion.field.customerSegment": "Customer segment",
  "promotion.field.memberCategoryId": "Member category",
  "promotion.field.buyQuantity": "Buy quantity",
  "promotion.field.payQuantity": "Pay quantity",
  "promotion.field.discountPercent": "Discount %",
  "promotion.field.type": "Mode",
  "promotion.noEndDate": "No end",
  "promotion.type.PURCHASE_THRESHOLD_DISCOUNT": "Amount discount",
  "promotion.type.FIXED_PACK_PRICE": "Fixed pack price",
  "promotion.scope.SALE": "Sale",
  "promotion.scope.PRODUCT_LIST": "Product list",
  "promotion.scope.FAMILY": "Family",
  "promotion.scope.SUBFAMILY": "Subfamily",
  "promotion.segment.ALL": "All customers",
  "promotion.status.ACTIVE": "Active",
  "venta.column.price": "Price"
};

const t = (key: string) => messages[key] ?? key;

const productRows: StockPromotionProductRow[] = [
  {
    productId: "product-1",
    code: "C-01",
    name: "Coffee beans",
    familyId: "family-1",
    familyName: "Coffee",
    subfamilyId: "subfamily-1",
    subfamilyName: "Beans",
    quantity: 3,
    totalQuantity: 7
  },
  {
    productId: "product-1",
    code: "C-01",
    name: "Coffee beans",
    familyId: "family-1",
    familyName: "Coffee",
    subfamilyId: "subfamily-1",
    subfamilyName: "Beans",
    quantity: 4,
    totalQuantity: 7
  },
  {
    id: "product-2",
    code: "T-01",
    name: "Green tea",
    familyId: "family-2",
    familyName: "Tea",
    subfamilyId: "subfamily-2",
    subfamilyName: "Green",
    quantity: 5
  }
];

function promotion(overrides: Partial<PromotionView> = {}): PromotionView {
  return {
    id: "promotion-1",
    name: "Coffee week",
    type: "PURCHASE_THRESHOLD_DISCOUNT",
    status: "ACTIVE",
    startDate: "2026-07-11",
    endDate: null,
    scope: "FAMILY",
    customerSegment: "ALL",
    memberCategoryId: null,
    minimumAmount: 20,
    minimumQuantity: null,
    buyQuantity: null,
    payQuantity: null,
    buyXPayYMode: null,
    discountAmount: null,
    discountPercent: 10,
    maximumDiscount: 5,
    packPrice: null,
    used: false,
    targets: [{ type: "FAMILY", targetId: "family-1" }],
    ...overrides
  };
}

describe("StockPromotionGroups", () => {
  it("keeps only ACTIVE promotions, one group per promotion, and deduplicates warehouse rows", () => {
    const active = promotion();
    const groups = buildStockPromotionGroups([
      active,
      promotion({ id: "promotion-inactive", name: "Old offer", status: "INACTIVE" }),
      { ...active, name: "Duplicated response row" }
    ], productRows);

    expect(groups).toHaveLength(1);
    expect(groups[0].promotion.name).toBe("Coffee week");
    expect(groups[0].products).toHaveLength(1);
    expect(groups[0].products[0]).toMatchObject({
      productId: "product-1",
      name: "Coffee beans",
      stock: 7
    });
  });

  it("matches product, subfamily, and sale-wide targets against Product or Stock rows", () => {
    const groups = buildStockPromotionGroups([
      promotion({
        id: "by-product",
        scope: "PRODUCT_LIST",
        targets: [{ type: "PRODUCT", targetId: "product-2" }]
      }),
      promotion({
        id: "by-subfamily",
        scope: "SUBFAMILY",
        targets: [{ type: "SUBFAMILY", targetId: "subfamily-2" }]
      }),
      promotion({ id: "whole-sale", scope: "SALE", targets: [] })
    ], productRows);

    expect(groups[0].products.map((product) => product.productId)).toEqual(["product-2"]);
    expect(groups[1].products.map((product) => product.productId)).toEqual(["product-2"]);
    expect(groups[2].products.map((product) => product.productId)).toEqual(["product-1", "product-2"]);
    expect(groups[2].products[1].stock).toBe(5);
  });

  it("renders an accessible, keyboard-focusable row for each active promotion", () => {
    const html = renderToStaticMarkup(
      <StockPromotionGroups
        locale="en"
        promotions={[
          promotion(),
          promotion({ id: "promotion-inactive", name: "Inactive promotion", status: "INACTIVE" })
        ]}
        productRows={productRows}
        t={t}
      />
    );

    expect(html).toContain('aria-label="Products with promotion"');
    expect(html).toContain('role="list"');
    expect(html).toContain('role="button"');
    expect(html).toContain('tabindex="0"');
    expect(html).toContain('aria-expanded="false"');
    expect(html).toContain("Coffee week");
    expect(html).not.toContain("Inactive promotion");
  });

  it("shows participant products and readable conditions, benefits, and rules when expanded", () => {
    const html = renderToStaticMarkup(
      <StockPromotionGroups
        locale="en"
        promotions={[promotion()]}
        productRows={productRows}
        t={t}
        defaultExpandedPromotionIds={["promotion-1"]}
      />
    );

    expect(html).toContain('aria-expanded="true"');
    expect(html).toContain('role="region"');
    expect(html).toContain("Coffee beans");
    expect(html).not.toContain("Green tea");
    expect(html).toContain("Conditions");
    expect(html).toContain("Benefit");
    expect(html).toContain("Rules");
    expect(html).toContain("Minimum amount");
    expect(html).toContain("10%");
    expect(html).toContain("Coffee");
  });

  it("supports bounded arrow navigation plus Home and End", () => {
    expect(stockPromotionNavigationIndex(0, "ArrowDown", 3)).toBe(1);
    expect(stockPromotionNavigationIndex(2, "ArrowDown", 3)).toBe(2);
    expect(stockPromotionNavigationIndex(0, "ArrowUp", 3)).toBe(0);
    expect(stockPromotionNavigationIndex(2, "Home", 3)).toBe(0);
    expect(stockPromotionNavigationIndex(0, "End", 3)).toBe(2);
    expect(stockPromotionNavigationIndex(0, "End", 0)).toBe(-1);
  });
});
