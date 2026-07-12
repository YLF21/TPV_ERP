export type StockBulkPriceRuleScope =
  | "PRICES"
  | "OFFER"
  | "STOCK"
  | "SUPPLIER"
  | "FAMILY"
  | "PRODUCT_TYPE"
  | "PRODUCT_LIST";

export type StockBulkNumericField = "GROSS_COST" | "NET_COST" | "LOCAL_STOCK" | "TOTAL_STOCK";
export type StockBulkNumericComparator = "EQ" | "NE" | "GT" | "GTE" | "LT" | "LTE";
export type StockBulkReferenceField = "SUPPLIER" | "FAMILY" | "SUBFAMILY";
export type StockBulkSetComparator = "IN" | "NOT_IN";
export type StockBulkPriceField = "SALE_PRICE" | "MEMBER_PRICE" | "WHOLESALE_PRICE" | "OFFER_PRICE";
export type StockBulkPriceUseMode = "NORMAL" | "MEMBER_PRICE" | "OFFER_PRICE" | "OFFER_DISCOUNT";

export type StockBulkPriceRuleCondition =
  | {
      type: "NUMBER";
      field: StockBulkNumericField;
      comparator: StockBulkNumericComparator;
      value: string;
      warehouseId: string | null;
    }
  | {
      type: "REFERENCE";
      field: StockBulkReferenceField;
      comparator: StockBulkSetComparator;
      values: string[];
    }
  | {
      type: "PRODUCT_TYPE";
      comparator: StockBulkSetComparator;
      values: string[];
    }
  | {
      type: "PRODUCT_LIST";
      comparator: StockBulkSetComparator;
      productIds: string[];
    };

export type StockBulkPriceRuleAction =
  | { type: "FIXED_PRICE"; field: StockBulkPriceField; value: string }
  | {
      type: "INVERSE_MARGIN";
      field: StockBulkPriceField;
      costBasis: "GROSS" | "NET";
      marginPercent: string;
    }
  | { type: "DECIMAL_ENDING"; field: StockBulkPriceField; cents: number }
  | { type: "PRICE_USE_MODE"; value: StockBulkPriceUseMode }
  | {
      type: "OFFER";
      active: boolean;
      discountPercent: string | null;
      from: string | null;
      until: string | null;
    };

export type StockBulkPriceRuleForm = {
  scope: StockBulkPriceRuleScope;
  conditions: StockBulkPriceRuleCondition[];
  actions: StockBulkPriceRuleAction[];
};

export type StockBulkPriceRuleView = {
  id: string;
  name: string;
  forms: StockBulkPriceRuleForm[];
  createdById: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  version: number;
};

export type StockBulkPriceRulePreview = {
  ruleId: string;
  ruleVersion: number;
  matchedProducts: number;
  products: Array<{
    productId: string;
    productName: string;
    changes: Array<{
      field: string;
      before: unknown;
      after: unknown;
      formIndexes: number[];
    }>;
  }>;
};

export type StockBulkPriceRuleDraft = {
  name: string;
  forms: StockBulkPriceRuleForm[];
};

export const stockBulkRuleScopes: StockBulkPriceRuleScope[] = [
  "PRICES", "OFFER", "STOCK", "SUPPLIER", "FAMILY", "PRODUCT_TYPE", "PRODUCT_LIST"
];
export const stockBulkRuleNumericFields: StockBulkNumericField[] = [
  "GROSS_COST", "NET_COST", "LOCAL_STOCK", "TOTAL_STOCK"
];
export const stockBulkRuleNumericComparators: StockBulkNumericComparator[] = [
  "EQ", "NE", "GT", "GTE", "LT", "LTE"
];
export const stockBulkRuleReferenceFields: StockBulkReferenceField[] = ["SUPPLIER", "FAMILY", "SUBFAMILY"];
export const stockBulkRuleSetComparators: StockBulkSetComparator[] = ["IN", "NOT_IN"];
export const stockBulkRulePriceFields: StockBulkPriceField[] = [
  "SALE_PRICE", "MEMBER_PRICE", "WHOLESALE_PRICE", "OFFER_PRICE"
];
export const stockBulkRulePriceUseModes: StockBulkPriceUseMode[] = [
  "NORMAL", "MEMBER_PRICE", "OFFER_PRICE", "OFFER_DISCOUNT"
];

const costFields: StockBulkNumericField[] = ["GROSS_COST", "NET_COST"];
const offerScopes: StockBulkPriceRuleScope[] = ["OFFER", "STOCK"];

export function stockBulkRuleConditionTypes(scope: StockBulkPriceRuleScope): StockBulkPriceRuleCondition["type"][] {
  if (scope === "PRICES" || scope === "OFFER") return ["NUMBER"];
  if (scope === "STOCK") return ["NUMBER"];
  if (scope === "SUPPLIER" || scope === "FAMILY") return ["NUMBER", "REFERENCE"];
  if (scope === "PRODUCT_TYPE") return ["NUMBER", "PRODUCT_TYPE"];
  return ["NUMBER", "PRODUCT_LIST"];
}

export function stockBulkRuleNumericFieldsForScope(scope: StockBulkPriceRuleScope): StockBulkNumericField[] {
  return scope === "STOCK" ? [...costFields, "LOCAL_STOCK", "TOTAL_STOCK"] : costFields;
}

export function stockBulkRuleReferenceFieldsForScope(scope: StockBulkPriceRuleScope): StockBulkReferenceField[] {
  if (scope === "SUPPLIER") return ["SUPPLIER"];
  if (scope === "FAMILY") return ["FAMILY", "SUBFAMILY"];
  return [];
}

export function stockBulkRuleActionTypes(scope: StockBulkPriceRuleScope): StockBulkPriceRuleAction["type"][] {
  if (scope === "PRICES") return ["FIXED_PRICE", "INVERSE_MARGIN", "DECIMAL_ENDING", "PRICE_USE_MODE"];
  if (offerScopes.includes(scope)) return ["FIXED_PRICE", "INVERSE_MARGIN", "DECIMAL_ENDING", "OFFER"];
  return ["FIXED_PRICE", "INVERSE_MARGIN", "DECIMAL_ENDING", "PRICE_USE_MODE", "OFFER"];
}

export function stockBulkRulePriceFieldsForScope(scope: StockBulkPriceRuleScope): StockBulkPriceField[] {
  if (scope === "PRICES") return ["SALE_PRICE", "MEMBER_PRICE", "WHOLESALE_PRICE"];
  if (offerScopes.includes(scope)) return ["OFFER_PRICE"];
  return stockBulkRulePriceFields;
}

export function newStockBulkPriceRuleCondition(type: StockBulkPriceRuleCondition["type"] = "NUMBER"): StockBulkPriceRuleCondition {
  if (type === "REFERENCE") return { type, field: "SUPPLIER", comparator: "IN", values: [] };
  if (type === "PRODUCT_TYPE") return { type, comparator: "IN", values: ["UNIT"] };
  if (type === "PRODUCT_LIST") return { type, comparator: "IN", productIds: [] };
  return { type, field: "GROSS_COST", comparator: "GTE", value: "0", warehouseId: null };
}

export function newStockBulkPriceRuleAction(type: StockBulkPriceRuleAction["type"] = "FIXED_PRICE"): StockBulkPriceRuleAction {
  if (type === "INVERSE_MARGIN") {
    return { type, field: "SALE_PRICE", costBasis: "NET", marginPercent: "0" };
  }
  if (type === "DECIMAL_ENDING") return { type, field: "SALE_PRICE", cents: 95 };
  if (type === "PRICE_USE_MODE") return { type, value: "NORMAL" };
  if (type === "OFFER") {
    return { type, active: true, discountPercent: null, from: null, until: null };
  }
  return { type, field: "SALE_PRICE", value: "0" };
}

export function newStockBulkPriceRuleConditionForScope(
  scope: StockBulkPriceRuleScope,
  type: StockBulkPriceRuleCondition["type"] = "NUMBER"
): StockBulkPriceRuleCondition {
  const condition = newStockBulkPriceRuleCondition(type);
  if (condition.type === "REFERENCE") {
    return { ...condition, field: scope === "FAMILY" ? "FAMILY" : "SUPPLIER" };
  }
  return condition;
}

export function newStockBulkPriceRuleActionForScope(
  scope: StockBulkPriceRuleScope,
  type: StockBulkPriceRuleAction["type"] = "FIXED_PRICE"
): StockBulkPriceRuleAction {
  const action = newStockBulkPriceRuleAction(type);
  if (action.type === "FIXED_PRICE" || action.type === "INVERSE_MARGIN" || action.type === "DECIMAL_ENDING") {
    return { ...action, field: offerScopes.includes(scope) ? "OFFER_PRICE" : "SALE_PRICE" };
  }
  return action;
}

export function newStockBulkPriceRuleForm(scope: StockBulkPriceRuleScope = "PRICES"): StockBulkPriceRuleForm {
  const conditions: StockBulkPriceRuleCondition[] = (() => {
    if (scope === "STOCK") {
      return [{ type: "NUMBER", field: "LOCAL_STOCK", comparator: "GTE", value: "0", warehouseId: null }];
    }
    if (scope === "SUPPLIER" || scope === "FAMILY") {
      return [newStockBulkPriceRuleConditionForScope(scope, "REFERENCE")];
    }
    if (scope === "PRODUCT_TYPE") return [newStockBulkPriceRuleCondition("PRODUCT_TYPE")];
    if (scope === "PRODUCT_LIST") return [newStockBulkPriceRuleCondition("PRODUCT_LIST")];
    return [];
  })();
  return { scope, conditions, actions: [newStockBulkPriceRuleActionForScope(scope)] };
}

export function newStockBulkPriceRuleDraft(): StockBulkPriceRuleDraft {
  return {
    name: "",
    forms: [newStockBulkPriceRuleForm()]
  };
}

export function cloneStockBulkPriceRuleDraft(rule: Pick<StockBulkPriceRuleView, "name" | "forms">): StockBulkPriceRuleDraft {
  const cloned = JSON.parse(JSON.stringify({ name: rule.name, forms: rule.forms })) as StockBulkPriceRuleDraft;
  return {
    ...cloned,
    forms: cloned.forms.map((form) => ({
      ...form,
      conditions: form.conditions.map((condition) => condition.type === "NUMBER"
        ? { ...condition, value: String(condition.value ?? "") }
        : condition),
      actions: form.actions.map((action) => {
        if (action.type === "FIXED_PRICE") return { ...action, value: String(action.value ?? "") };
        if (action.type === "INVERSE_MARGIN") {
          return { ...action, marginPercent: String(action.marginPercent ?? "") };
        }
        if (action.type === "OFFER") {
          return {
            ...action,
            discountPercent: action.discountPercent === null
              ? null
              : String(action.discountPercent)
          };
        }
        return action;
      })
    }))
  };
}

export function serializeStockBulkPriceRuleDraft(draft: StockBulkPriceRuleDraft) {
  return JSON.stringify(draft);
}

function validNumber(value: string | number | null, minimum: number, maximum = Number.POSITIVE_INFINITY) {
  if (value === null || String(value).trim() === "") return false;
  const parsed = Number(String(value).replace(",", "."));
  return Number.isFinite(parsed) && parsed >= minimum && parsed <= maximum;
}

export function validateStockBulkPriceRuleDraft(draft: StockBulkPriceRuleDraft) {
  if (!draft.name.trim()) return "stock.bulkEdit.rules.validation.name";
  if (draft.forms.length === 0) return "stock.bulkEdit.rules.validation.forms";
  for (const form of draft.forms) {
    if (form.actions.length === 0) return "stock.bulkEdit.rules.validation.actions";
    const hasRequiredSelector = form.scope === "PRICES" || form.scope === "OFFER" || form.conditions.some((condition) => {
      if (form.scope === "STOCK") {
        return condition.type === "NUMBER" && (condition.field === "LOCAL_STOCK" || condition.field === "TOTAL_STOCK");
      }
      if (form.scope === "SUPPLIER") return condition.type === "REFERENCE" && condition.field === "SUPPLIER";
      if (form.scope === "FAMILY") return condition.type === "REFERENCE" && (condition.field === "FAMILY" || condition.field === "SUBFAMILY");
      if (form.scope === "PRODUCT_TYPE") return condition.type === "PRODUCT_TYPE";
      return condition.type === "PRODUCT_LIST";
    });
    if (!hasRequiredSelector) return "stock.bulkEdit.rules.validation.scope";
    for (const condition of form.conditions) {
      if (condition.type === "NUMBER" && !validNumber(condition.value, Number.NEGATIVE_INFINITY)) {
        return "stock.bulkEdit.rules.validation.number";
      }
      if (condition.type === "REFERENCE" && condition.values.length === 0) {
        return "stock.bulkEdit.rules.validation.references";
      }
      if (condition.type === "PRODUCT_TYPE" && condition.values.length === 0) {
        return "stock.bulkEdit.rules.validation.productTypes";
      }
      if (condition.type === "PRODUCT_LIST" && condition.productIds.length === 0) {
        return "stock.bulkEdit.rules.validation.products";
      }
    }
    for (const action of form.actions) {
      if (action.type === "FIXED_PRICE" && !validNumber(action.value, 0)) {
        return "stock.bulkEdit.rules.validation.price";
      }
      if (action.type === "INVERSE_MARGIN" && !validNumber(action.marginPercent, 0, 99.999999)) {
        return "stock.bulkEdit.rules.validation.margin";
      }
      if (action.type === "DECIMAL_ENDING"
          && (!Number.isInteger(action.cents) || action.cents < 0 || action.cents > 99)) {
        return "stock.bulkEdit.rules.validation.cents";
      }
      if (action.type === "OFFER") {
        if (action.discountPercent !== null && !validNumber(action.discountPercent, 0, 100)) {
          return "stock.bulkEdit.rules.validation.discount";
        }
        if (action.until && (!action.from || action.until < action.from)) {
          return "stock.bulkEdit.rules.validation.dates";
        }
      }
    }
  }
  return null;
}

export function stockBulkPriceRuleRequest(draft: StockBulkPriceRuleDraft, version?: number) {
  const body = {
    name: draft.name.trim(),
    forms: draft.forms
  };
  return version === undefined ? body : { version, ...body };
}

export function stockBulkPriceRuleDeletePath(id: string, version: number) {
  return `/product-price-rules/${encodeURIComponent(id)}?version=${encodeURIComponent(String(version))}`;
}

export function stockBulkPriceRuleExecutionBody(ruleVersion: number) {
  return { ruleVersion };
}
