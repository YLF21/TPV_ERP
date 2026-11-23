import type { ProductExcelImportReadResult } from "../api/productExcelImports";
import { excelCellText, excelColumnIndexToLetter, findExcelColumn, normalizeExcelText } from "./excelImport";

export type PromotionExcelCatalogProduct = {
  id: string;
  code?: string | null;
  barcode?: string | null;
  barcode2?: string | null;
  name?: string | null;
  active?: boolean | null;
};

export type PromotionExcelMapping = { code: number; barcode: number; startRow: number };
export type PromotionExcelRowStatus =
  | "ready"
  | "missing"
  | "ambiguous"
  | "identifierMismatch"
  | "inactive"
  | "duplicate"
  | "selected"
  | "identifierRequired"
  | "invalidCell";
export type PromotionExcelRow = {
  rowNumber: number;
  code: string;
  barcode: string;
  product?: PromotionExcelCatalogProduct;
  status: PromotionExcelRowStatus;
};

const codeAliases = ["codigo", "código", "codigo producto", "codigo articulo", "code", "sku", "referencia", "ref"];
const barcodeAliases = ["codigo de barras", "código de barras", "codigo barras", "barcode", "ean", "ean13", "gtin"];

export function detectPromotionExcelMapping(read: ProductExcelImportReadResult): PromotionExcelMapping {
  const firstRow = read.rows[0] ?? [];
  const headings = firstRow.map((cell) => excelCellText(cell.value));
  const code = findExcelColumn(headings, codeAliases);
  const barcode = findExcelColumn(headings, barcodeAliases);
  return { code: code >= 0 ? code : barcode >= 0 ? -1 : 0, barcode, startRow: code >= 0 || barcode >= 0 ? 2 : 1 };
}

/** Product identifiers are text. Never parse them as numbers or strip leading zeroes. */
export function classifyPromotionExcelRows(
  read: ProductExcelImportReadResult,
  mapping: PromotionExcelMapping,
  products: readonly PromotionExcelCatalogProduct[],
  selectedProductIds: readonly string[] = []
): PromotionExcelRow[] {
  const index = new Map<string, Set<string>>();
  const productById = new Map(products.map((product) => [product.id, product]));
  for (const product of products) {
    for (const value of [product.code, product.barcode, product.barcode2]) {
      const key = normalizeExcelText(String(value ?? ""));
      if (!key) continue;
      const ids = index.get(key) ?? new Set<string>();
      ids.add(product.id);
      index.set(key, ids);
    }
  }
  const selected = new Set(selectedProductIds);
  const seen = new Set<string>();
  const result: PromotionExcelRow[] = [];
  for (let rowIndex = Math.max(0, mapping.startRow - 1); rowIndex < read.rows.length; rowIndex++) {
    const cells = read.rows[rowIndex] ?? [];
    const codeCell = mapping.code >= 0 ? cells[mapping.code] : undefined;
    const barcodeCell = mapping.barcode >= 0 ? cells[mapping.barcode] : undefined;
    const code = excelCellText(codeCell?.value);
    const barcode = excelCellText(barcodeCell?.value);
    if (!cells.some((cell) => excelCellText(cell.value) || cell.errorCode)) continue;
    let status: PromotionExcelRowStatus;
    let product: PromotionExcelCatalogProduct | undefined;
    if (codeCell?.errorCode || barcodeCell?.errorCode) {
      status = "invalidCell";
    } else if (!code && !barcode) {
      status = "identifierRequired";
    } else {
      const codeIds = code ? index.get(normalizeExcelText(code)) : undefined;
      const barcodeIds = barcode ? index.get(normalizeExcelText(barcode)) : undefined;
      const ids = new Set([...(codeIds ?? []), ...(barcodeIds ?? [])]);
      if (ids.size > 1) status = "ambiguous";
      else if (code && barcode && ((codeIds?.size ?? 0) === 0) !== ((barcodeIds?.size ?? 0) === 0))
        status = "identifierMismatch";
      else if (ids.size === 0) status = "missing";
      else {
        const id = [...ids][0];
        product = productById.get(id);
        if (selected.has(id)) status = "selected";
        else if (product?.active === false) status = "inactive";
        else if (seen.has(id)) status = "duplicate";
        else status = "ready";
        seen.add(id);
      }
    }
    result.push({ rowNumber: rowIndex + 1, code, barcode, product, status });
  }
  return result;
}

export function promotionExcelColumns(read: ProductExcelImportReadResult) {
  return Array.from({ length: read.columns }, (_, index) => ({
    index,
    label: `${excelColumnIndexToLetter(index)} · ${excelCellText(read.rows[0]?.[index]?.value) || "—"}`
  }));
}
