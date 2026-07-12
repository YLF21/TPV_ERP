import type { StockInventoryRow } from "./StockScreen";
import type { StockBulkEditRowData } from "./stockBulkEdit";

export type StockBulkFilterCriteria = {
  productType?: string | null;
  familyId?: string | null;
  subfamilyId?: string | null;
  supplierId?: string | null;
  taxId?: string | null;
  priceUseMode?: string | null;
  offerActive?: boolean | null;
  offerFrom?: string | null;
  offerUntil?: string | null;
  minimumPrice?: number | null;
  maximumPrice?: number | null;
};

export type StockBulkImageCandidate = {
  name: string;
  file: File;
};

export type StockBulkImageMatch = {
  productId: string;
  file: File;
  fileName: string;
};

export type StockBulkImageMatchResult = {
  matches: StockBulkImageMatch[];
  unresolved: Array<{
    file: File;
    fileName: string;
    reason: "noMatch" | "multipleMatches" | "productAlreadyMatched";
    productIds: string[];
  }>;
};

export const emptyStockBulkFilterCriteria: StockBulkFilterCriteria = {
  productType: null,
  familyId: null,
  subfamilyId: null,
  supplierId: null,
  taxId: null,
  priceUseMode: null,
  offerActive: null,
  offerFrom: null,
  offerUntil: null,
  minimumPrice: null,
  maximumPrice: null
};

export function countActiveStockBulkFilters(criteria: StockBulkFilterCriteria) {
  return Object.values(criteria).filter(
    (value) => value !== null && value !== undefined && value !== ""
  ).length;
}

export function filterStockBulkRows(
  rows: StockBulkEditRowData[],
  criteria: StockBulkFilterCriteria
) {
  return rows.filter((row) => {
    if (!row.product) {
      return !hasActiveCriteria(criteria);
    }
    const value = <K extends keyof StockInventoryRow>(field: K) => row.draft[field] ?? row.product?.[field];
    if (criteria.productType && value("productType") !== criteria.productType) return false;
    if (criteria.familyId && value("familyId") !== criteria.familyId) return false;
    if (criteria.subfamilyId && value("subfamilyId") !== criteria.subfamilyId) return false;
    if (criteria.taxId && value("taxId") !== criteria.taxId) return false;
    if (criteria.priceUseMode && value("discountType") !== criteria.priceUseMode) return false;
    if (criteria.supplierId && !row.suppliers?.some((supplier) => supplier.id === criteria.supplierId)) return false;

    const offerActiveValue = booleanFlag(value("offerActive"));
    const offerActive = offerActiveValue ?? ["OFFER_PRICE", "OFFER_DISCOUNT"]
      .includes(String(value("discountType") ?? "NORMAL"));
    if (criteria.offerActive !== null && criteria.offerActive !== undefined && offerActive !== criteria.offerActive) {
      return false;
    }
    const offerFrom = nullableText(value("offerFrom"));
    const offerUntil = nullableText(value("offerUntil"));
    if (criteria.offerFrom && offerUntil && offerUntil < criteria.offerFrom) return false;
    if (criteria.offerUntil && (!offerFrom || offerFrom > criteria.offerUntil)) return false;

    const salePrice = numberValue(value("salePrice"));
    if (criteria.minimumPrice !== null && criteria.minimumPrice !== undefined && salePrice < criteria.minimumPrice) {
      return false;
    }
    if (criteria.maximumPrice !== null && criteria.maximumPrice !== undefined && salePrice > criteria.maximumPrice) {
      return false;
    }
    return true;
  });
}

export function mergeStockBulkFamilyProducts(
  rows: StockBulkEditRowData[],
  products: StockInventoryRow[],
  familyIds: string[],
  subfamilyIds: string[] = []
) {
  const selectedFamilies = new Set(familyIds);
  const selectedSubfamilies = new Set(subfamilyIds);
  const existing = new Set(rows.flatMap((row) => row.product ? [row.product.productId] : []));
  const result = rows.filter((row) => row.product || row.query.trim());

  products.forEach((product) => {
    const matchesSubfamily = selectedSubfamilies.size > 0 && product.subfamilyId
      ? selectedSubfamilies.has(product.subfamilyId)
      : false;
    const matchesFamily = selectedFamilies.has(product.familyId);
    if ((!matchesFamily && !matchesSubfamily) || existing.has(product.productId)) {
      return;
    }
    existing.add(product.productId);
    result.push({
      id: `bulk-family-${product.productId}`,
      selected: false,
      query: product.code || product.barcode || product.name,
      product,
      draft: { ...product }
    });
  });

  result.push({
    id: `bulk-family-empty-${Date.now()}`,
    selected: false,
    query: "",
    draft: {}
  });
  return result;
}

export function applyStockBulkDecimalEnding(
  rows: StockBulkEditRowData[],
  rowIds: Set<string>,
  field: "salePrice" | "memberPrice" | "wholesalePrice" | "offerPrice",
  ending: number
) {
  if (!Number.isInteger(ending) || ending < 0 || ending > 99) {
    throw new Error("decimalEnding");
  }
  return rows.map((row) => {
    if (!rowIds.has(row.id) || !row.product) {
      return row;
    }
    const current = numberValue(row.draft[field] ?? row.product[field]);
    const adjusted = Math.trunc(current) + ending / 100;
    return {
      ...row,
      draft: {
        ...row.draft,
        [field]: adjusted.toFixed(2)
      }
    };
  });
}

export function matchStockBulkImageFiles(
  candidates: StockBulkImageCandidate[],
  products: StockInventoryRow[],
  mode: "code" | "name"
): StockBulkImageMatchResult {
  const productsByKey = new Map<string, StockInventoryRow[]>();
  products.forEach((product) => {
    const keys = mode === "name"
      ? [product.name]
      : [product.code, product.barcode, product.barcode2];
    keys.map((key) => normalizeImageKey(String(key ?? "")))
      .filter(Boolean)
      .forEach((key) => productsByKey.set(key, [...(productsByKey.get(key) ?? []), product]));
  });

  const matches: StockBulkImageMatch[] = [];
  const unresolved: StockBulkImageMatchResult["unresolved"] = [];
  const matchedProducts = new Set<string>();
  candidates.forEach(({ name, file }) => {
    const key = normalizeImageKey(stripExtension(name));
    const candidatesForFile = uniqueProducts(productsByKey.get(key) ?? []);
    if (candidatesForFile.length === 0) {
      unresolved.push({ file, fileName: name, reason: "noMatch", productIds: [] });
      return;
    }
    if (candidatesForFile.length > 1) {
      unresolved.push({
        file,
        fileName: name,
        reason: "multipleMatches",
        productIds: candidatesForFile.map((product) => product.productId)
      });
      return;
    }
    const product = candidatesForFile[0];
    if (matchedProducts.has(product.productId)) {
      unresolved.push({ file, fileName: name, reason: "productAlreadyMatched", productIds: [product.productId] });
      return;
    }
    matchedProducts.add(product.productId);
    matches.push({ productId: product.productId, file, fileName: name });
  });
  return { matches, unresolved };
}

function hasActiveCriteria(criteria: StockBulkFilterCriteria) {
  return countActiveStockBulkFilters(criteria) > 0;
}

function nullableText(value: unknown) {
  const text = String(value ?? "").trim();
  return text && text !== "-" ? text : null;
}

function numberValue(value: unknown) {
  const parsed = Number(String(value ?? 0).replace(",", "."));
  return Number.isFinite(parsed) ? parsed : 0;
}

function booleanFlag(value: unknown) {
  const normalized = String(value ?? "").trim().toLowerCase();
  if (["true", "yes", "si", "sí", "common.yes"].includes(normalized)) return true;
  if (["false", "no", "common.no"].includes(normalized)) return false;
  return null;
}

function stripExtension(value: string) {
  return value.replace(/\.[^.]+$/, "");
}

function normalizeImageKey(value: string) {
  return value.normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-zA-Z0-9]+/g, "")
    .toLowerCase();
}

function uniqueProducts(products: StockInventoryRow[]) {
  return Array.from(new Map(products.map((product) => [product.productId, product])).values());
}
