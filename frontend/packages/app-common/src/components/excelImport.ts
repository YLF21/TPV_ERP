import { readSheet } from "read-excel-file/browser";

export type ExcelCell = unknown;
export type ExcelSheet = ExcelCell[][];
export type ExcelTableRow = Record<string, ExcelCell>;

export type ExcelTable = {
  headers: string[];
  normalizedHeaders: string[];
  rows: ExcelTableRow[];
};

export type ExcelColumnMapping = {
  familyId?: string;
  subfamilyId?: string;
  taxId?: string;
  productType?: string;
  priceUseMode?: string;
  discountType?: string;
  code?: string;
  barcode?: string;
  barcode2?: string;
  name?: string;
  description?: string;
  comments?: string;
  purchasePrice?: string;
  purchaseDiscountPercent?: string;
  taxesIncluded?: string;
  salePrice?: string;
  memberPrice?: string;
  wholesalePrice?: string;
  offerPrice?: string;
  offerDiscountPercent?: string;
  offerActive?: string;
  offerFrom?: string;
  offerUntil?: string;
  packageQuantity?: string;
  stockMin?: string;
  stockMax?: string;
};

export type ExcelImportProductIdentity = {
  id: string;
  code?: string | null;
  barcode?: string | null;
};

export type ExcelImportProductDraft = {
  familyId: string;
  subfamilyId: string;
  taxId: string;
  productType: string;
  priceUseMode: string;
  discountType: string;
  name: string;
  description: string;
  comments: string;
  code: string;
  barcode: string;
  barcode2: string;
  purchasePrice: string;
  purchaseDiscountPercent: string;
  taxesIncluded: string;
  salePrice: string;
  memberPrice: string;
  wholesalePrice: string;
  offerPrice: string;
  offerDiscountPercent: string;
  offerActive: string;
  offerFrom: string;
  offerUntil: string;
  packageQuantity: string;
  stockMin: string;
  stockMax: string;
};

export type ExcelImportClassifiedRow = {
  rowNumber: number;
  source: ExcelCell[];
  draft: ExcelImportProductDraft;
  product?: ExcelImportProductIdentity;
  status: "missing" | "purchasePriceChanged" | "accepted" | "error";
  errors: string[];
};

export const excelImportAccept = ".xlsx,.xls,.csv";

export async function readExcelSheet(file: File): Promise<ExcelSheet> {
  return await readSheet(file);
}

export async function readExcelTable(file: File): Promise<ExcelTable> {
  return excelSheetToTable(await readExcelSheet(file));
}

export function excelSheetToTable(sheet: ExcelSheet): ExcelTable {
  if (sheet.length === 0) {
    return { headers: [], normalizedHeaders: [], rows: [] };
  }
  const headers = sheet[0].map((header) => excelCellText(header));
  return {
    headers,
    normalizedHeaders: headers.map(normalizeExcelHeader),
    rows: excelRowsToObjects(headers, sheet.slice(1))
  };
}

export function excelRowsToObjects(headers: string[], rows: ExcelCell[][]): ExcelTableRow[] {
  return rows.map((row) => Object.fromEntries(
    headers.map((header, index) => [header, row[index] ?? ""])
  ));
}

export function findExcelColumn(headers: readonly string[], aliases: readonly string[]) {
  const normalizedAliases = new Set(aliases.map(normalizeExcelHeader));
  return headers.findIndex((header) => normalizedAliases.has(normalizeExcelHeader(header)));
}

export function findExcelColumns(headers: readonly string[], aliases: readonly string[]) {
  const normalizedAliases = new Set(aliases.map(normalizeExcelHeader));
  return headers.flatMap((header, index) => normalizedAliases.has(normalizeExcelHeader(header)) ? [index] : []);
}

export function excelColumnLetterToIndex(letter: string | undefined) {
  const value = (letter ?? "").trim().toUpperCase();
  if (!/^[A-Z]+$/.test(value)) {
    return -1;
  }
  return [...value].reduce((index, char) => index * 26 + char.charCodeAt(0) - 64, 0) - 1;
}

export function excelColumnIndexToLetter(index: number) {
  if (!Number.isInteger(index) || index < 0) {
    return "";
  }
  let value = index + 1;
  let letter = "";
  while (value > 0) {
    const remainder = (value - 1) % 26;
    letter = String.fromCharCode(65 + remainder) + letter;
    value = Math.floor((value - 1) / 26);
  }
  return letter;
}

export function excelCellByColumnLetter(row: readonly ExcelCell[], letter: string | undefined) {
  const index = excelColumnLetterToIndex(letter);
  return index < 0 ? undefined : row[index];
}

export function buildExcelImportDraft(row: readonly ExcelCell[], mapping: ExcelColumnMapping): ExcelImportProductDraft {
  return {
    familyId: excelCellText(excelCellByColumnLetter(row, mapping.familyId)),
    subfamilyId: excelCellText(excelCellByColumnLetter(row, mapping.subfamilyId)),
    taxId: excelCellText(excelCellByColumnLetter(row, mapping.taxId)),
    productType: excelCellText(excelCellByColumnLetter(row, mapping.productType)),
    priceUseMode: excelCellText(excelCellByColumnLetter(row, mapping.priceUseMode)),
    discountType: excelCellText(excelCellByColumnLetter(row, mapping.discountType)),
    name: excelCellText(excelCellByColumnLetter(row, mapping.name)),
    description: excelCellText(excelCellByColumnLetter(row, mapping.description)),
    comments: excelCellText(excelCellByColumnLetter(row, mapping.comments)),
    code: excelCellText(excelCellByColumnLetter(row, mapping.code)),
    barcode: excelCellText(excelCellByColumnLetter(row, mapping.barcode)),
    barcode2: excelCellText(excelCellByColumnLetter(row, mapping.barcode2)),
    purchasePrice: excelPriceText(excelCellByColumnLetter(row, mapping.purchasePrice)),
    purchaseDiscountPercent: excelPriceText(excelCellByColumnLetter(row, mapping.purchaseDiscountPercent)),
    taxesIncluded: excelCellText(excelCellByColumnLetter(row, mapping.taxesIncluded)),
    salePrice: excelPriceText(excelCellByColumnLetter(row, mapping.salePrice)),
    memberPrice: excelPriceText(excelCellByColumnLetter(row, mapping.memberPrice)),
    wholesalePrice: excelPriceText(excelCellByColumnLetter(row, mapping.wholesalePrice)),
    offerPrice: excelPriceText(excelCellByColumnLetter(row, mapping.offerPrice)),
    offerDiscountPercent: excelPriceText(excelCellByColumnLetter(row, mapping.offerDiscountPercent)),
    offerActive: excelCellText(excelCellByColumnLetter(row, mapping.offerActive)),
    offerFrom: excelCellText(excelCellByColumnLetter(row, mapping.offerFrom)),
    offerUntil: excelCellText(excelCellByColumnLetter(row, mapping.offerUntil)),
    packageQuantity: excelPriceText(excelCellByColumnLetter(row, mapping.packageQuantity)),
    stockMin: excelPriceText(excelCellByColumnLetter(row, mapping.stockMin)),
    stockMax: excelPriceText(excelCellByColumnLetter(row, mapping.stockMax))
  };
}

export function classifyExcelProductRows(
  sheet: ExcelSheet,
  mapping: ExcelColumnMapping,
  products: readonly ExcelImportProductIdentity[],
  currentPurchasePrice: (product: ExcelImportProductIdentity) => string | number | null | undefined = () => undefined,
  startRow = 2
): ExcelImportClassifiedRow[] {
  const productIndex = buildExcelProductIdentityIndex(products);
  const firstDataRow = Math.max(2, Math.floor(startRow));
  return sheet.slice(firstDataRow - 1).flatMap((row, rowIndex) => {
    if (row.every((cell) => excelCellText(cell) === "")) {
      return [];
    }
    const draft = buildExcelImportDraft(row, mapping);
    const errors: string[] = [];
    if (!draft.name) errors.push("nameRequired");
    if (!draft.code && !draft.barcode) errors.push("identifierRequired");
    const product = productIndex.get(normalizeExcelText(draft.code))
      ?? productIndex.get(normalizeExcelText(draft.barcode));
    const status: ExcelImportClassifiedRow["status"] = errors.length > 0
      ? "error"
      : !product
        ? "missing"
        : purchasePriceChanged(draft.purchasePrice, currentPurchasePrice(product))
          ? "purchasePriceChanged"
          : "accepted";
    return [{
      rowNumber: rowIndex + firstDataRow,
      source: row,
      draft,
      product,
      status,
      errors
    }];
  });
}

export function excelCellText(value: ExcelCell) {
  if (value instanceof Date && Number.isFinite(value.getTime())) {
    const year = value.getFullYear();
    const month = String(value.getMonth() + 1).padStart(2, "0");
    const day = String(value.getDate()).padStart(2, "0");
    return `${year}-${month}-${day}`;
  }
  return value === null || value === undefined ? "" : String(value).trim();
}

export function excelPriceText(value: ExcelCell) {
  const text = excelCellText(value).replace(",", ".");
  if (!text) {
    return "0";
  }
  const number = Number(text);
  return Number.isFinite(number) ? text : "0";
}

export function normalizeExcelHeader(value: string) {
  return normalizeExcelText(value).replace(/\s+/g, " ");
}

export function normalizeExcelText(value: string) {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .trim()
    .toLowerCase();
}

function buildExcelProductIdentityIndex(products: readonly ExcelImportProductIdentity[]) {
  const index = new Map<string, ExcelImportProductIdentity>();
  products.forEach((product) => {
    [product.code, product.barcode]
      .map((value) => normalizeExcelText(String(value ?? "")))
      .filter(Boolean)
      .forEach((key) => {
        if (!index.has(key)) {
          index.set(key, product);
        }
      });
  });
  return index;
}

function purchasePriceChanged(importedPrice: string, currentPrice: string | number | null | undefined) {
  if (currentPrice === null || currentPrice === undefined || currentPrice === "") {
    return false;
  }
  return Number(importedPrice) !== Number(String(currentPrice).replace(",", "."));
}
