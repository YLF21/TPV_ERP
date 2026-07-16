import { describe, expect, it } from "vitest";
import {
  applyStockBulkPriceRulePreview,
  cloneStockBulkPriceRuleDraft,
  newStockBulkPriceRuleAction,
  newStockBulkPriceRuleCondition,
  newStockBulkPriceRuleDraft,
  newStockBulkPriceRuleForm,
  stockBulkRuleActionTypes,
  stockBulkRuleConditionTypes,
  stockBulkRuleNumericFieldsForScope,
  stockBulkRulePriceFieldsForScope,
  stockBulkRuleReferenceFieldsForScope,
  stockBulkPriceRuleDeletePath,
  stockBulkPriceRuleExecutionBody,
  stockBulkPriceRulePreviewPath,
  stockBulkPriceRuleRequest,
  validateStockBulkPriceRuleDraft
} from "./stockBulkPriceRules";

describe("stock bulk price rule contract", () => {
  it("builds exact typed condition and action shapes", () => {
    expect(newStockBulkPriceRuleCondition("REFERENCE")).toEqual({
      type: "REFERENCE",
      field: "SUPPLIER",
      comparator: "IN",
      values: []
    });
    expect(newStockBulkPriceRuleAction("OFFER")).toEqual({
      type: "OFFER",
      active: true,
      discountPercent: null,
      from: null,
      until: null
    });
  });

  it("includes optimistic versions in update, execution and delete payloads", () => {
    const draft = newStockBulkPriceRuleDraft();
    draft.name = "Tarifa";
    expect(stockBulkPriceRuleRequest(draft, 7)).toEqual(expect.objectContaining({ version: 7, name: "Tarifa" }));
    expect(stockBulkPriceRuleExecutionBody(7, ["product-1", "product-2", "product-1"])).toEqual({
      ruleVersion: 7,
      productIds: ["product-1", "product-2"]
    });
    expect(stockBulkPriceRuleDeletePath("rule/1", 7)).toBe("/product-price-rules/rule%2F1?version=7");
    expect(stockBulkPriceRulePreviewPath("rule/1")).toBe("/product-price-rules/rule%2F1/preview");
    expect(validateStockBulkPriceRuleDraft(draft)).toBeNull();
  });

  it("applies a preview only to selected rows already present in the list", () => {
    const selected = { selected: true, product: { productId: "product-1" }, draft: { salePrice: "10" } };
    const unselected = { selected: false, product: { productId: "product-2" }, draft: { salePrice: "20" } };
    const rows = applyStockBulkPriceRulePreview([selected, unselected], {
      ruleId: "rule-1",
      ruleVersion: 1,
      matchedProducts: 3,
      products: [
        { productId: "product-1", productName: "Uno", changes: [
          { field: "SALE_PRICE", before: 10, after: 15.5, formIndexes: [0] },
          { field: "OFFER_ACTIVE", before: false, after: true, formIndexes: [0] }
        ] },
        { productId: "product-2", productName: "Dos", changes: [
          { field: "SALE_PRICE", before: 20, after: 25, formIndexes: [0] }
        ] },
        { productId: "outside-list", productName: "Externo", changes: [
          { field: "SALE_PRICE", before: 1, after: 99, formIndexes: [0] }
        ] }
      ]
    });

    expect(rows).toEqual([
      expect.objectContaining({ draft: { salePrice: "15.5", offerActive: "common.yes" } }),
      unselected
    ]);
    expect(rows).toHaveLength(2);
  });

  it("normalizes numeric values returned by the backend before validation", () => {
    const draft = cloneStockBulkPriceRuleDraft({
      name: "Tarifa backend",
      forms: [{
        scope: "PRICES",
        conditions: [{
          type: "NUMBER",
          field: "GROSS_COST",
          comparator: "GTE",
          value: 10 as unknown as string,
          warehouseId: null
        }],
        actions: [{
          type: "FIXED_PRICE",
          field: "SALE_PRICE",
          value: 18 as unknown as string
        }]
      }]
    });

    expect(draft.forms[0].conditions[0]).toEqual(expect.objectContaining({ value: "10" }));
    expect(draft.forms[0].actions[0]).toEqual(expect.objectContaining({ value: "18" }));
    expect(validateStockBulkPriceRuleDraft(draft)).toBeNull();
  });

  it("rejects forms without actions and invalid decimal endings", () => {
    const noActions = { name: "Regla", forms: [{ scope: "PRICES" as const, conditions: [], actions: [] }] };
    expect(validateStockBulkPriceRuleDraft(noActions)).toBe("stock.bulkEdit.rules.validation.actions");
    const draft = newStockBulkPriceRuleDraft();
    draft.name = "Regla";
    draft.forms[0].actions = [{ type: "DECIMAL_ENDING", field: "SALE_PRICE", cents: 100 }];
    expect(validateStockBulkPriceRuleDraft(draft)).toBe("stock.bulkEdit.rules.validation.cents");
  });

  it("builds forms and options that match each backend scope", () => {
    expect(newStockBulkPriceRuleForm("STOCK").conditions).toEqual([
      { type: "NUMBER", field: "LOCAL_STOCK", comparator: "GTE", value: "0", warehouseId: null }
    ]);
    expect(newStockBulkPriceRuleForm("FAMILY").conditions[0]).toEqual(expect.objectContaining({
      type: "REFERENCE",
      field: "FAMILY"
    }));
    expect(stockBulkRuleConditionTypes("PRODUCT_LIST")).toEqual(["NUMBER", "PRODUCT_LIST"]);
    expect(stockBulkRuleNumericFieldsForScope("OFFER")).toEqual(["GROSS_COST", "NET_COST"]);
    expect(stockBulkRuleReferenceFieldsForScope("SUPPLIER")).toEqual(["SUPPLIER"]);
    expect(stockBulkRuleActionTypes("PRICES")).not.toContain("OFFER");
    expect(stockBulkRulePriceFieldsForScope("STOCK")).toEqual(["OFFER_PRICE"]);
  });

  it("requires the selector associated with filtered scopes", () => {
    const draft = newStockBulkPriceRuleDraft();
    draft.name = "Stock";
    draft.forms = [{
      scope: "STOCK",
      conditions: [{ type: "NUMBER", field: "NET_COST", comparator: "GTE", value: "0", warehouseId: null }],
      actions: [{ type: "FIXED_PRICE", field: "OFFER_PRICE", value: "1" }]
    }];
    expect(validateStockBulkPriceRuleDraft(draft)).toBe("stock.bulkEdit.rules.validation.scope");
  });
});
