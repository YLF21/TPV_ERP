export type StockBulkPriceField = "SALE_PRICE" | "MEMBER_PRICE" | "WHOLESALE_PRICE" | "OFFER_PRICE";
export type StockBulkPriceUseMode = "NORMAL" | "MEMBER_PRICE" | "OFFER_PRICE" | "OFFER_DISCOUNT";

export type StockBulkPriceAction =
  | { type: "FIXED_PRICE"; field: StockBulkPriceField; value: string }
  | {
      type: "INVERSE_MARGIN";
      field: StockBulkPriceField;
      costBasis: "GROSS" | "NET";
      marginPercent: string;
    }
  | { type: "DECIMAL_ENDING"; field: StockBulkPriceField; cents: number | "" }
  | { type: "PRICE_USE_MODE"; value: StockBulkPriceUseMode }
  | {
      type: "OFFER";
      active: boolean;
      discountPercent: string | null;
      from: string | null;
      until: string | null;
    };

export type StockBulkPriceActionChanges = {
  products: Array<{
    productId: string;
    changes: Array<{ field: string; after: unknown }>;
  }>;
};

type StockBulkPriceActionEditableRow = {
  selected: boolean;
  product?: { productId: string };
  draft: object;
};

export const stockBulkPriceActionTypes: StockBulkPriceAction["type"][] = [
  "FIXED_PRICE", "INVERSE_MARGIN", "DECIMAL_ENDING", "PRICE_USE_MODE", "OFFER"
];
export const stockBulkRulePriceFields: StockBulkPriceField[] = [
  "SALE_PRICE", "MEMBER_PRICE", "WHOLESALE_PRICE", "OFFER_PRICE"
];
export const stockBulkRulePriceUseModes: StockBulkPriceUseMode[] = [
  "NORMAL", "MEMBER_PRICE", "OFFER_PRICE", "OFFER_DISCOUNT"
];

export function newStockBulkPriceAction(
  field: StockBulkPriceField,
  type: StockBulkPriceAction["type"] = "FIXED_PRICE"
): StockBulkPriceAction {
  switch (type) {
    case "FIXED_PRICE": return { type, field, value: "" };
    case "INVERSE_MARGIN": return { type, field, costBasis: "NET", marginPercent: "" };
    case "DECIMAL_ENDING": return { type, field, cents: 95 };
    case "PRICE_USE_MODE": return { type, value: "NORMAL" };
    case "OFFER": return { type, active: true, discountPercent: null, from: null, until: null };
  }
}

function validNumber(value: string | number | null, minimum: number, maximum = Number.POSITIVE_INFINITY) {
  if (value === null || String(value).trim() === "") return false;
  const parsed = Number(String(value).replace(",", "."));
  return Number.isFinite(parsed) && parsed >= minimum && parsed <= maximum;
}

export function validateStockBulkPriceActions(actions: readonly StockBulkPriceAction[]) {
  for (const action of actions) {
    if (action.type === "FIXED_PRICE" && !validNumber(action.value, 0)) {
      return "stock.bulkEdit.rules.validation.price";
    }
    if (action.type === "INVERSE_MARGIN" && !validNumber(action.marginPercent, 0, 99.999999)) {
      return "stock.bulkEdit.rules.validation.margin";
    }
    if (action.type === "DECIMAL_ENDING"
        && (action.cents === "" || !Number.isInteger(action.cents) || action.cents < 0 || action.cents > 99)) {
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
  return null;
}

const previewFieldToDraftField: Record<string, string> = {
  SALE_PRICE: "salePrice",
  MEMBER_PRICE: "memberPrice",
  WHOLESALE_PRICE: "wholesalePrice",
  OFFER_PRICE: "offerPrice",
  PRICE_USE_MODE: "discountType",
  DISCOUNT_TYPE: "backendDiscountType",
  OFFER_DISCOUNT_PERCENT: "offerDiscountPercent",
  OFFER_ACTIVE: "offerActive",
  OFFER_FROM: "offerFrom",
  OFFER_UNTIL: "offerUntil"
};

function stockBulkPriceActionDraftValue(field: string, value: unknown) {
  if (field === "OFFER_ACTIVE") {
    return value === true ? "common.yes" : "common.no";
  }
  return value === null || value === undefined ? "" : String(value);
}

export function applyStockBulkPriceActionChanges<Row extends StockBulkPriceActionEditableRow>(
  rows: Row[],
  preview: StockBulkPriceActionChanges
): Row[] {
  const changesByProduct = new Map(preview.products.map((product) => [product.productId, product.changes]));
  return rows.map((row) => {
    if (!row.selected || !row.product) return row;
    const changes = changesByProduct.get(row.product.productId);
    if (!changes || changes.length === 0) return row;
    const patch = Object.fromEntries(changes.flatMap((change) => {
      const field = previewFieldToDraftField[change.field];
      return field ? [[field, stockBulkPriceActionDraftValue(change.field, change.after)]] : [];
    }));
    return Object.keys(patch).length === 0
      ? row
      : { ...row, draft: { ...row.draft, ...patch } };
  });
}
