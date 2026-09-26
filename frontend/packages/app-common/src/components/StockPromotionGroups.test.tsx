import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PromotionView } from "./PromotionForm";
import { writeStoredTableLayout } from "./tableLayoutPreferences";
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
  "promotion.detail.price": "PVP",
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
  "promotion.detail.conditions": "Conditions",
  "promotion.detail.products": "Products",
  "promotion.detail.usageCount": "Uses",
  "promotion.rule.minimumAmount": "Minimum amount {value}",
  "promotion.rule.discountPercent": "Discount {value}%",
  "promotion.rule.maximumDiscount": "Maximum discount {value}",
  "promotion.rule.scope.FAMILY": "Family scope",
  "promotion.rule.segment.ALL": "All customers",
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

function createMemoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    get length() { return values.size; },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => Array.from(values.keys())[index] ?? null,
    removeItem: (key) => values.delete(key),
    setItem: (key, value) => values.set(key, value)
  };
}

function renderedColumnKeys(html: string) {
  return Array.from(html.matchAll(/data-column-key="([^"]+)"/g), (match) => match[1]);
}

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
    totalQuantity: 7,
    salePrice: 12.5
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
    totalQuantity: 7,
    salePrice: 12.5
  },
  {
    id: "product-2",
    code: "T-01",
    name: "Green tea",
    familyId: "family-2",
    familyName: "Tea",
    subfamilyId: "subfamily-2",
    subfamilyName: "Green",
    quantity: 5,
    salePrice: 8
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
  let storage: Storage;

  beforeEach(() => {
    storage = createMemoryStorage();
    vi.stubGlobal("localStorage", storage);
  });

  afterEach(() => vi.unstubAllGlobals());

  it("keeps every promotion status, one group per promotion, and deduplicates warehouse rows", () => {
    const active = promotion();
    const groups = buildStockPromotionGroups([
      active,
      promotion({ id: "promotion-inactive", name: "Old offer", status: "INACTIVE" }),
      { ...active, name: "Duplicated response row" }
    ], productRows);

    expect(groups).toHaveLength(2);
    expect(groups[0].promotion.name).toBe("Coffee week");
    expect(groups[1].promotion).toMatchObject({ id: "promotion-inactive", status: "INACTIVE" });
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

  it("renders every promotion as a selectable list item without an overview table", () => {
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
    expect(html).toContain('<button type="button"');
    expect(html).toContain('tabindex="0"');
    expect(html).toContain('aria-pressed="true"');
    expect(html).toContain("Coffee week");
    expect(html).toContain("Inactive promotion");
    expect(html).not.toContain('data-column-key="promotion"');
    expect(html).not.toMatch(/stock-promotion-list-row[^>]*aria-expanded=/);
  });

  it("selects the first promotion automatically and shows usage and conditions before products", () => {
    const html = renderToStaticMarkup(
      <StockPromotionGroups
        locale="en"
        promotions={[promotion({ usageCount: 4 })]}
        productRows={productRows}
        t={t}
        defaultExpandedPromotionIds={["promotion-1"]}
      />
    );

    expect(html).not.toMatch(/stock-promotion-list-row[^>]*aria-expanded=/);
    expect(html).toContain("Uses</dt><dd>4</dd>");
    expect(html).toContain("Coffee beans");
    expect(html).not.toContain("Green tea");
    expect(html).toContain("Conditions");
    expect(html).toContain("Minimum amount");
    expect(html).toContain("10%");
    expect(html).toContain("Coffee");
    expect(html.indexOf("Conditions")).toBeLessThan(html.indexOf('<table class="report-table">'));
  });

  it("supports bounded arrow navigation plus Home and End", () => {
    expect(stockPromotionNavigationIndex(0, "ArrowDown", 3)).toBe(1);
    expect(stockPromotionNavigationIndex(2, "ArrowDown", 3)).toBe(2);
    expect(stockPromotionNavigationIndex(0, "ArrowUp", 3)).toBe(0);
    expect(stockPromotionNavigationIndex(2, "Home", 3)).toBe(0);
    expect(stockPromotionNavigationIndex(0, "End", 3)).toBe(2);
    expect(stockPromotionNavigationIndex(0, "End", 0)).toBe(-1);
  });

  it("hydrates product-column layout and renders product cells in the same order", () => {
    const defaultHtml = renderToStaticMarkup(
      <StockPromotionGroups
        locale="en"
        promotions={[promotion()]}
        productRows={productRows}
        t={t}
        app="venta"
        username="admin"
      />
    );
    const defaultTableHtml = defaultHtml.slice(defaultHtml.indexOf('<table class="report-table">'));
    expect(renderedColumnKeys(defaultTableHtml).slice(0, 6))
      .toEqual(["code", "name", "price", "stock", "family", "subfamily"]);

    writeStoredTableLayout("venta", "admin", "stock.promotions.products", [
      { key: "stock", width: 94, visible: true },
      { key: "name", width: 218, visible: true },
      { key: "code", width: 120, visible: true },
      { key: "price", width: 102, visible: true }
    ], storage);
    const html = renderToStaticMarkup(
      <StockPromotionGroups
        locale="en"
        promotions={[promotion()]}
        productRows={productRows}
        t={t}
        app="venta"
        username="admin"
        defaultExpandedPromotionIds={["promotion-1"]}
      />
    );

    const tableHtml = html.slice(html.indexOf('<table class="report-table">'));
    const keys = renderedColumnKeys(tableHtml);
    expect(keys.slice(0, 6)).toEqual(["stock", "name", "code", "price", "family", "subfamily"]);
    expect(keys.slice(6, 12)).toEqual(keys.slice(0, 6));
    expect(tableHtml).toContain('style="width:94px"');
    expect(tableHtml).toContain("12.50");
    expect(tableHtml.match(/draggable="true"/g)).toHaveLength(12);
    expect(tableHtml.match(/aria-keyshortcuts="Control\+ArrowLeft Control\+ArrowRight"/g)).toHaveLength(6);
    expect(tableHtml.match(/table-layout-column-resizer/g)).toHaveLength(6);
  });
});
