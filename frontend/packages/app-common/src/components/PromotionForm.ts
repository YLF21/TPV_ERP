export type PromotionType =
  | "PURCHASE_THRESHOLD_COUPON"
  | "PURCHASE_THRESHOLD_DISCOUNT"
  | "BUY_X_PAY_Y"
  | "SECOND_UNIT_PERCENT"
  | "FIXED_PACK_PRICE"
  | "QUANTITY_DISCOUNT";

export type PromotionScope = "SALE" | "PRODUCT_LIST" | "FAMILY" | "SUBFAMILY";
export type PromotionCustomerSegment = "ALL" | "IDENTIFIED_CUSTOMERS" | "MEMBERS_ONLY" | "MEMBER_CATEGORY";
export type PromotionStatus = "DRAFT" | "ACTIVE" | "INACTIVE";
export type BuyXPayYMode = "SAME_PRODUCT" | "MIXED_TARGETS";
export type PromotionTargetType = "PRODUCT" | "FAMILY" | "SUBFAMILY";

export type PromotionTargetRequest = {
  type: PromotionTargetType;
  targetId: string;
};

export type PromotionView = {
  id: string;
  name: string;
  type: PromotionType;
  status: PromotionStatus;
  startDate: string;
  endDate: string | null;
  scope: PromotionScope | null;
  customerSegment: PromotionCustomerSegment | null;
  memberCategoryId: string | null;
  minimumAmount: string | number | null;
  minimumQuantity: string | number | null;
  buyQuantity: string | number | null;
  payQuantity: string | number | null;
  buyXPayYMode: BuyXPayYMode | null;
  discountAmount: string | number | null;
  discountPercent: string | number | null;
  maximumDiscount: string | number | null;
  packPrice: string | number | null;
  versionOrigenId?: string | null;
  used?: boolean;
  targets: PromotionTargetRequest[];
};

export type PromotionDraft = {
  name: string;
  type: PromotionType;
  startDate: string;
  endDate: string;
  scope: PromotionScope;
  customerSegment: PromotionCustomerSegment;
  memberCategoryId: string;
  minimumAmount: string;
  minimumQuantity: string;
  buyQuantity: string;
  payQuantity: string;
  buyXPayYMode: BuyXPayYMode;
  discountAmount: string;
  discountPercent: string;
  maximumDiscount: string;
  packPrice: string;
  couponAmount: string;
  couponPercent: string;
  couponMaximumDiscount: string;
  couponMinimumAmount: string;
  couponValidFromDate: string;
  couponValidFromDays: string;
  couponValidUntilDate: string;
  couponValidDays: string;
  targetIds: string[];
};

export type PromotionRequest = {
  name: string;
  type: PromotionType;
  startDate: string;
  endDate: string | null;
  scope: PromotionScope;
  customerSegment: PromotionCustomerSegment;
  memberCategoryId: string | null;
  minimumAmount: string | null;
  minimumQuantity: string | null;
  buyQuantity: string | null;
  payQuantity: string | null;
  buyXPayYMode: BuyXPayYMode | null;
  discountAmount: string | null;
  discountPercent: string | null;
  maximumDiscount: string | null;
  packPrice: string | null;
  couponAmount: string | null;
  couponPercent: string | null;
  couponMaximumDiscount: string | null;
  couponMinimumAmount: string | null;
  couponValidFromDate: string | null;
  couponValidFromDays: number | null;
  couponValidUntilDate: string | null;
  couponValidDays: number | null;
  targets: PromotionTargetRequest[];
};

export type PromotionValidationError =
  | "NAME_REQUIRED"
  | "NAME_TOO_LONG"
  | "START_DATE_REQUIRED"
  | "DATE_RANGE_INVALID"
  | "TARGETS_NOT_ALLOWED"
  | "TARGETS_REQUIRED"
  | "TARGETS_INVALID"
  | "MEMBER_CATEGORY_REQUIRED"
  | "MINIMUM_AMOUNT_INVALID"
  | "MINIMUM_QUANTITY_INVALID"
  | "BUY_QUANTITY_INVALID"
  | "PAY_QUANTITY_INVALID"
  | "BUY_PAY_RELATION_INVALID"
  | "BUY_X_PAY_Y_MODE_INVALID"
  | "DISCOUNT_VALUE_INVALID"
  | "MAXIMUM_DISCOUNT_INVALID"
  | "PACK_PRICE_INVALID"
  | "COUPON_VALUE_INVALID"
  | "COUPON_MAXIMUM_DISCOUNT_INVALID"
  | "COUPON_MINIMUM_AMOUNT_INVALID"
  | "COUPON_VALID_FROM_INVALID"
  | "COUPON_VALID_UNTIL_INVALID"
  | "COUPON_DATE_RANGE_INVALID";

export const promotionTypes: PromotionType[] = [
  "PURCHASE_THRESHOLD_COUPON",
  "PURCHASE_THRESHOLD_DISCOUNT",
  "BUY_X_PAY_Y",
  "SECOND_UNIT_PERCENT",
  "FIXED_PACK_PRICE",
  "QUANTITY_DISCOUNT"
];

export const promotionScopes: PromotionScope[] = ["SALE", "PRODUCT_LIST", "FAMILY", "SUBFAMILY"];

export const promotionCustomerSegments: PromotionCustomerSegment[] = [
  "ALL",
  "IDENTIFIED_CUSTOMERS",
  "MEMBERS_ONLY",
  "MEMBER_CATEGORY"
];

export const buyXPayYModes: BuyXPayYMode[] = ["SAME_PRODUCT", "MIXED_TARGETS"];

export function createDefaultPromotionDraft(startDate = new Date().toISOString().slice(0, 10)): PromotionDraft {
  return {
    name: "",
    type: "PURCHASE_THRESHOLD_DISCOUNT",
    startDate,
    endDate: "",
    scope: "SALE",
    customerSegment: "ALL",
    memberCategoryId: "",
    minimumAmount: "",
    minimumQuantity: "",
    buyQuantity: "",
    payQuantity: "",
    buyXPayYMode: "SAME_PRODUCT",
    discountAmount: "",
    discountPercent: "",
    maximumDiscount: "",
    packPrice: "",
    couponAmount: "",
    couponPercent: "",
    couponMaximumDiscount: "",
    couponMinimumAmount: "",
    couponValidFromDate: "",
    couponValidFromDays: "",
    couponValidUntilDate: "",
    couponValidDays: "",
    targetIds: []
  };
}

export function promotionTargetTypeForScope(scope: Exclude<PromotionScope, "SALE">): PromotionTargetType {
  if (scope === "PRODUCT_LIST") {
    return "PRODUCT";
  }
  return scope;
}

export function buildPromotionRequest(draft: PromotionDraft): PromotionRequest {
  const purchaseCoupon = draft.type === "PURCHASE_THRESHOLD_COUPON";
  const purchaseDiscount = draft.type === "PURCHASE_THRESHOLD_DISCOUNT";
  const buyXPayY = draft.type === "BUY_X_PAY_Y";
  const secondUnit = draft.type === "SECOND_UNIT_PERCENT";
  const fixedPack = draft.type === "FIXED_PACK_PRICE";
  const quantityDiscount = draft.type === "QUANTITY_DISCOUNT";
  const discountPromotion = purchaseDiscount || quantityDiscount;
  const scope = draft.scope;
  const targets = scope === "SALE"
    ? []
    : draft.targetIds.map((targetId) => ({
        type: promotionTargetTypeForScope(scope),
        targetId: targetId.trim()
      }));

  return {
    name: draft.name.trim(),
    type: draft.type,
    startDate: draft.startDate,
    endDate: blankToNull(draft.endDate),
    scope: draft.scope,
    customerSegment: draft.customerSegment,
    memberCategoryId: draft.customerSegment === "MEMBER_CATEGORY" ? blankToNull(draft.memberCategoryId) : null,
    minimumAmount: purchaseCoupon || purchaseDiscount ? blankToNull(draft.minimumAmount) : null,
    minimumQuantity: quantityDiscount ? blankToNull(draft.minimumQuantity) : null,
    buyQuantity: buyXPayY || fixedPack ? blankToNull(draft.buyQuantity) : null,
    payQuantity: buyXPayY ? blankToNull(draft.payQuantity) : null,
    buyXPayYMode: buyXPayY ? draft.buyXPayYMode : null,
    discountAmount: discountPromotion ? blankToNull(draft.discountAmount) : null,
    discountPercent: discountPromotion || secondUnit ? blankToNull(draft.discountPercent) : null,
    maximumDiscount: discountPromotion ? blankToNull(draft.maximumDiscount) : null,
    packPrice: fixedPack ? blankToNull(draft.packPrice) : null,
    couponAmount: purchaseCoupon ? blankToNull(draft.couponAmount) : null,
    couponPercent: purchaseCoupon ? blankToNull(draft.couponPercent) : null,
    couponMaximumDiscount: purchaseCoupon ? blankToNull(draft.couponMaximumDiscount) : null,
    couponMinimumAmount: purchaseCoupon ? blankToNull(draft.couponMinimumAmount) : null,
    couponValidFromDate: purchaseCoupon ? blankToNull(draft.couponValidFromDate) : null,
    couponValidFromDays: purchaseCoupon ? integerOrNull(draft.couponValidFromDays) : null,
    couponValidUntilDate: purchaseCoupon ? blankToNull(draft.couponValidUntilDate) : null,
    couponValidDays: purchaseCoupon ? integerOrNull(draft.couponValidDays) : null,
    targets
  };
}

export function validatePromotionDraft(draft: PromotionDraft): PromotionValidationError[] {
  const errors: PromotionValidationError[] = [];
  const name = draft.name.trim();
  if (name === "") {
    errors.push("NAME_REQUIRED");
  } else if (name.length > 160) {
    errors.push("NAME_TOO_LONG");
  }
  if (!validIsoDate(draft.startDate)) {
    errors.push("START_DATE_REQUIRED");
  }
  if (draft.endDate !== "" && (!validIsoDate(draft.endDate) || draft.endDate < draft.startDate)) {
    errors.push("DATE_RANGE_INVALID");
  }

  validateTargets(draft, errors);
  if (draft.customerSegment === "MEMBER_CATEGORY" && draft.memberCategoryId.trim() === "") {
    errors.push("MEMBER_CATEGORY_REQUIRED");
  }

  switch (draft.type) {
    case "PURCHASE_THRESHOLD_COUPON":
      validateMinimumAmount(draft.minimumAmount, errors);
      validateExactlyOnePositive(draft.couponAmount, draft.couponPercent, "COUPON_VALUE_INVALID", errors);
      validateOptionalPositive(draft.couponMaximumDiscount, "COUPON_MAXIMUM_DISCOUNT_INVALID", errors);
      validateOptionalNonNegative(draft.couponMinimumAmount, "COUPON_MINIMUM_AMOUNT_INVALID", errors);
      validateCouponValidity(draft, errors);
      break;
    case "PURCHASE_THRESHOLD_DISCOUNT":
      validateMinimumAmount(draft.minimumAmount, errors);
      validateDiscount(draft, errors);
      break;
    case "BUY_X_PAY_Y":
      validateBuyXPayY(draft, errors);
      break;
    case "SECOND_UNIT_PERCENT":
      if (!validPositivePercentage(draft.discountPercent)) {
        errors.push("DISCOUNT_VALUE_INVALID");
      }
      break;
    case "FIXED_PACK_PRICE":
      if (!validWholePositive(draft.buyQuantity)) {
        errors.push("BUY_QUANTITY_INVALID");
      }
      if (!validPositiveDecimal(draft.packPrice)) {
        errors.push("PACK_PRICE_INVALID");
      }
      break;
    case "QUANTITY_DISCOUNT":
      if (!validPositiveQuantity(draft.minimumQuantity)) {
        errors.push("MINIMUM_QUANTITY_INVALID");
      }
      validateDiscount(draft, errors);
      break;
  }

  return [...new Set(errors)];
}

export class PromotionDraftValidationError extends Error {
  readonly validationErrors: PromotionValidationError[];

  constructor(validationErrors: PromotionValidationError[]) {
    super("promotion draft is invalid");
    this.name = "PromotionDraftValidationError";
    this.validationErrors = validationErrors;
  }
}

export function assertValidPromotionDraft(draft: PromotionDraft) {
  const errors = validatePromotionDraft(draft);
  if (errors.length > 0) {
    throw new PromotionDraftValidationError(errors);
  }
}

function validateTargets(draft: PromotionDraft, errors: PromotionValidationError[]) {
  if (draft.scope === "SALE") {
    if (draft.targetIds.length > 0) {
      errors.push("TARGETS_NOT_ALLOWED");
    }
    return;
  }
  if (draft.targetIds.length === 0) {
    errors.push("TARGETS_REQUIRED");
    return;
  }
  const normalized = draft.targetIds.map((targetId) => targetId.trim());
  if (normalized.some((targetId) => targetId === "") || new Set(normalized).size !== normalized.length) {
    errors.push("TARGETS_INVALID");
  }
}

function validateMinimumAmount(value: string, errors: PromotionValidationError[]) {
  if (!validNonNegativeDecimal(value)) {
    errors.push("MINIMUM_AMOUNT_INVALID");
  }
}

function validateDiscount(draft: PromotionDraft, errors: PromotionValidationError[]) {
  validateExactlyOnePositive(draft.discountAmount, draft.discountPercent, "DISCOUNT_VALUE_INVALID", errors);
  validateOptionalPositive(draft.maximumDiscount, "MAXIMUM_DISCOUNT_INVALID", errors);
}

function validateBuyXPayY(draft: PromotionDraft, errors: PromotionValidationError[]) {
  if (!validWholePositive(draft.buyQuantity)) {
    errors.push("BUY_QUANTITY_INVALID");
  }
  if (!validWholeNonNegative(draft.payQuantity)) {
    errors.push("PAY_QUANTITY_INVALID");
  }
  const buyQuantity = decimalValue(draft.buyQuantity);
  const payQuantity = decimalValue(draft.payQuantity);
  if (buyQuantity != null && payQuantity != null && payQuantity >= buyQuantity) {
    errors.push("BUY_PAY_RELATION_INVALID");
  }
  if (!buyXPayYModes.includes(draft.buyXPayYMode)) {
    errors.push("BUY_X_PAY_Y_MODE_INVALID");
  }
}

function validateExactlyOnePositive(
  amount: string,
  percent: string,
  error: "DISCOUNT_VALUE_INVALID" | "COUPON_VALUE_INVALID",
  errors: PromotionValidationError[]
) {
  const hasAmount = amount.trim() !== "";
  const hasPercent = percent.trim() !== "";
  if (hasAmount === hasPercent
      || (hasAmount && !validPositiveDecimal(amount))
      || (hasPercent && !validPositivePercentage(percent))) {
    errors.push(error);
  }
}

function validateOptionalPositive(
  value: string,
  error: "MAXIMUM_DISCOUNT_INVALID" | "COUPON_MAXIMUM_DISCOUNT_INVALID",
  errors: PromotionValidationError[]
) {
  if (value.trim() !== "" && !validPositiveDecimal(value)) {
    errors.push(error);
  }
}

function validateOptionalNonNegative(
  value: string,
  error: "COUPON_MINIMUM_AMOUNT_INVALID",
  errors: PromotionValidationError[]
) {
  if (value.trim() !== "" && !validNonNegativeDecimal(value)) {
    errors.push(error);
  }
}

function validateCouponValidity(draft: PromotionDraft, errors: PromotionValidationError[]) {
  const hasFromDate = draft.couponValidFromDate.trim() !== "";
  const hasFromDays = draft.couponValidFromDays.trim() !== "";
  if ((hasFromDate && hasFromDays)
      || (hasFromDate && !validIsoDate(draft.couponValidFromDate))
      || (hasFromDays && !validInteger(draft.couponValidFromDays, true))) {
    errors.push("COUPON_VALID_FROM_INVALID");
  }

  const hasUntilDate = draft.couponValidUntilDate.trim() !== "";
  const hasValidDays = draft.couponValidDays.trim() !== "";
  if (hasUntilDate === hasValidDays
      || (hasUntilDate && !validIsoDate(draft.couponValidUntilDate))
      || (hasValidDays && !validInteger(draft.couponValidDays, false))) {
    errors.push("COUPON_VALID_UNTIL_INVALID");
  }
  if (hasFromDate && hasUntilDate
      && validIsoDate(draft.couponValidFromDate)
      && validIsoDate(draft.couponValidUntilDate)
      && draft.couponValidUntilDate < draft.couponValidFromDate) {
    errors.push("COUPON_DATE_RANGE_INVALID");
  }
}

function validIsoDate(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value.trim());
  if (!match) {
    return false;
  }
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}

function validPositivePercentage(value: string) {
  const parsed = decimalValue(value);
  return parsed != null && parsed > 0 && parsed <= 100 && decimalPlaces(value) <= 2;
}

function validPositiveQuantity(value: string) {
  const parsed = decimalValue(value);
  return parsed != null && parsed > 0 && decimalPlaces(value) <= 3;
}

function validWholePositive(value: string) {
  const parsed = decimalValue(value);
  return parsed != null && parsed > 0 && Number.isInteger(parsed);
}

function validWholeNonNegative(value: string) {
  const parsed = decimalValue(value);
  return parsed != null && parsed >= 0 && Number.isInteger(parsed);
}

function validPositiveDecimal(value: string) {
  const parsed = decimalValue(value);
  return parsed != null && parsed > 0;
}

function validNonNegativeDecimal(value: string) {
  const parsed = decimalValue(value);
  return parsed != null && parsed >= 0;
}

function validInteger(value: string, allowZero: boolean) {
  const parsed = decimalValue(value);
  return parsed != null && Number.isInteger(parsed) && (allowZero ? parsed >= 0 : parsed > 0);
}

function decimalValue(value: string) {
  const trimmed = value.trim();
  if (!/^(?:\d+|\d*\.\d+)$/.test(trimmed)) {
    return null;
  }
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}

function decimalPlaces(value: string) {
  return value.trim().split(".")[1]?.length ?? 0;
}

function integerOrNull(value: string) {
  const trimmed = value.trim();
  return trimmed === "" ? null : Number.parseInt(trimmed, 10);
}

function blankToNull(value: string) {
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}
