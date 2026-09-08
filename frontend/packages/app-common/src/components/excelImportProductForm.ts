import { createDefaultProductForm, type ProductCreateFormState } from "./ProductCreateDialog";
import type { ExcelImportProductDraft } from "./excelImport";

export function productFormFromExcelDraft(draft: ExcelImportProductDraft): ProductCreateFormState {
  return {
    ...createDefaultProductForm(),
    familyId: draft.familyId,
    subfamilyId: draft.subfamilyId,
    taxId: draft.taxId,
    productType: productTypeFromExcel(draft.productType),
    priceUseMode: priceUseModeFromExcel(draft.priceUseMode),
    discountType: discountTypeFromExcel(draft.discountType),
    name: draft.name,
    description: draft.description,
    comments: draft.comments,
    purchasePrice: draft.purchasePrice || "0",
    taxesIncluded: booleanFromExcel(draft.taxesIncluded, true),
    code: draft.code,
    barcode: draft.barcode,
    barcode2: draft.barcode2,
    salePrice: draft.salePrice || "0",
    memberPrice: optionalPositiveExcelValue(draft.memberPrice),
    wholesalePrice: optionalPositiveExcelValue(draft.wholesalePrice),
    offerPrice: optionalPositiveExcelValue(draft.offerPrice),
    offerDiscountPercent: optionalPositiveExcelValue(draft.offerDiscountPercent),
    offerActive: booleanFromExcel(draft.offerActive, false),
    offerFrom: draft.offerFrom,
    offerUntil: draft.offerUntil,
    purchaseDiscountPercent: draft.purchaseDiscountPercent,
    packageQuantity: draft.packageQuantity,
    stockMin: draft.stockMin,
    stockMax: draft.stockMax
  };
}

function productTypeFromExcel(value: string): ProductCreateFormState["productType"] {
  const normalized = normalizeExcelOption(value);
  if (["SERVICE", "SERVICIO"].includes(normalized)) return "SERVICE";
  if (["WEIGHT", "PESO", "PESABLE"].includes(normalized)) return "WEIGHT";
  return "UNIT";
}

function priceUseModeFromExcel(value: string): ProductCreateFormState["priceUseMode"] {
  const normalized = normalizeExcelOption(value);
  if (["MEMBER_PRICE", "MEMBER", "MIEMBRO", "PRECIO_MIEMBRO", "PRECIO_DE_MIEMBRO"].includes(normalized)) return "MEMBER_PRICE";
  if (["OFFER_PRICE", "OFERTA", "PRECIO_OFERTA"].includes(normalized)) return "OFFER_PRICE";
  if (["OFFER_DISCOUNT", "DESCUENTO_OFERTA"].includes(normalized)) return "OFFER_DISCOUNT";
  return "NORMAL";
}

function discountTypeFromExcel(value: string): ProductCreateFormState["discountType"] {
  const normalized = normalizeExcelOption(value);
  if (["1", "TRUE", "SI", "YES", "NONE", "NO_APLICAR", "PROHIBIDO"].includes(normalized)) return "NONE";
  if (["MEMBER_PRICE", "MIEMBRO", "PRECIO_MIEMBRO", "PRECIO_DE_MIEMBRO"].includes(normalized)) return "MEMBER_PRICE";
  if (["DISCOUNT_PRICE", "OFERTA", "DESCUENTO"].includes(normalized)) return "DISCOUNT_PRICE";
  return "NORMAL";
}

function booleanFromExcel(value: string, fallback: boolean) {
  const normalized = normalizeExcelOption(value);
  if (!normalized) return fallback;
  if (["1", "TRUE", "SI", "YES", "S"].includes(normalized)) return true;
  if (["0", "FALSE", "NO", "N"].includes(normalized)) return false;
  return fallback;
}

function optionalPositiveExcelValue(value: string) {
  const normalized = value.trim().replace(",", ".");
  if (!normalized) {
    return "";
  }
  const number = Number(normalized);
  return Number.isFinite(number) && number <= 0 ? "" : value;
}

function normalizeExcelOption(value: string) {
  return value.trim().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/\s+/g, "_").toUpperCase();
}
