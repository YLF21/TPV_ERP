import { readSheet } from "read-excel-file/browser";
import {
  excelFormulaCellText,
  isExcelFormulaCell,
  type ExcelFormulaCell
} from "./excelFormula";

export type ExcelCell = unknown | ExcelFormulaCell;
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
  supplierReference?: string;
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
  supplierReference?: string;
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
  existence?: "EXISTING" | "MISSING" | "AMBIGUOUS" | "UNRESOLVED";
  rowNumber: number;
  rowNumbers?: number[];
  source: ExcelCell[];
  draft: ExcelImportProductDraft;
  product?: ExcelImportProductIdentity;
  version?: number | null;
  excelData?: Record<string, unknown>;
  databaseData?: Record<string, unknown> | null;
  changes?: Record<string, unknown>;
  structuredErrors?: Array<{
    code: string;
    row?: number | null;
    column?: number | null;
    attribute?: string | null;
    receivedValue?: string | null;
    reason: string;
    acceptedValues?: string | null;
    recommendedFix?: string | null;
  }>;
  status: "missing" | "purchasePriceChanged" | "accepted" | "error";
  errors: string[];
  masterDataChanged?: boolean;
};

export const excelImportAccept = ".xlsx,.xls";

/** Safety limits shared by the browser preview and the server contract. */
export const excelImportLimits = {
  maxFileBytes: 10 * 1024 * 1024,
  maxDetectedRows: 5_000,
  maxColumns: 256,
  maxNonEmptyCells: 250_000
} as const;

export async function readExcelSheet(file: File): Promise<ExcelSheet> {
  if (file.size > excelImportLimits.maxFileBytes) {
    throw new Error("excel.fileTooLarge");
  }
  const values = await readSheet(file);
  // Legacy helpers may still read an already-normalized XLSX value grid, but
  // they never parse or execute workbook formulas. Product imports use the
  // backend /read endpoint for both XLS and XLSX.
  validateExcelSheetLimits(values);
  return values;
}

export function validateExcelSheetLimits(sheet: ExcelSheet) {
  const width = sheet.reduce((maximum, row) => Math.max(maximum, row.length), 0);
  if (width > excelImportLimits.maxColumns) {
    throw new Error("excel.tooManyColumns");
  }
  const nonEmptyCells = sheet.reduce(
    (count, row) => count + row.filter((cell) => excelCellText(cell) !== "").length,
    0
  );
  if (nonEmptyCells > excelImportLimits.maxNonEmptyCells) {
    throw new Error("excel.tooManyCells");
  }
  return sheet;
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
    supplierReference: excelCellText(excelCellByColumnLetter(row, mapping.supplierReference)),
    purchasePrice: excelPriceText(excelCellByColumnLetter(row, mapping.purchasePrice)),
    purchaseDiscountPercent: excelPriceText(excelCellByColumnLetter(row, mapping.purchaseDiscountPercent)),
    taxesIncluded: excelCellText(excelCellByColumnLetter(row, mapping.taxesIncluded)),
    salePrice: excelPriceText(excelCellByColumnLetter(row, mapping.salePrice)),
    memberPrice: excelPriceText(excelCellByColumnLetter(row, mapping.memberPrice)),
    wholesalePrice: excelPriceText(excelCellByColumnLetter(row, mapping.wholesalePrice)),
    offerPrice: excelPriceText(excelCellByColumnLetter(row, mapping.offerPrice)),
    offerDiscountPercent: excelPriceText(excelCellByColumnLetter(row, mapping.offerDiscountPercent)),
    offerActive: excelCellText(excelCellByColumnLetter(row, mapping.offerActive)),
    offerFrom: excelDateText(excelCellByColumnLetter(row, mapping.offerFrom)),
    offerUntil: excelDateText(excelCellByColumnLetter(row, mapping.offerUntil)),
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
  const classified = sheet.slice(firstDataRow - 1).flatMap((row, rowIndex) => {
    const draft = buildExcelImportDraft(row, mapping);
    // A row is a product candidate only when one of the configured identity
    // fields has content. Price/stock-only helper rows must not inflate the
    // detected count or reach the import workflow.
    if (!draft.code && !draft.barcode && !draft.name) {
      return [];
    }
    const errors: string[] = [];
    if (!draft.code && !draft.barcode) errors.push("identifierRequired");
    const product = productIndex.get(normalizeExcelText(draft.code))
      ?? productIndex.get(normalizeExcelText(draft.barcode));
    if (!product && !draft.name) errors.push("nameRequired");
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
  if (classified.length <= excelImportLimits.maxDetectedRows) return classified;
  return [
    ...classified.slice(0, excelImportLimits.maxDetectedRows),
    {
      rowNumber: -1,
      source: [],
      draft: buildExcelImportDraft([], {}),
      status: "error" as const,
      errors: ["tooManyDetectedRows"]
    }
  ];
}

export function excelCellText(value: ExcelCell) {
  const cellValue = excelFormulaCellText(value);
  if (cellValue instanceof Date && Number.isFinite(cellValue.getTime())) {
    const year = cellValue.getFullYear();
    const month = String(cellValue.getMonth() + 1).padStart(2, "0");
    const day = String(cellValue.getDate()).padStart(2, "0");
    return `${year}-${month}-${day}`;
  }
  return cellValue === null || cellValue === undefined ? "" : String(cellValue).trim();
}

/**
 * Accepts the formats users commonly enter in Spanish Excel sheets. The
 * canonical value remains ISO because the API and existing document metadata
 * already use yyyy-MM-dd.
 */
export function parseExcelDate(value: ExcelCell): string | null {
  if (isExcelFormulaCell(value)) {
    return parseExcelDate(value.value);
  }
  if (value instanceof Date) {
    return Number.isFinite(value.getTime()) ? isoDate(value.getFullYear(), value.getMonth() + 1, value.getDate()) : null;
  }
  const text = String(value ?? "").trim();
  if (!text) return null;
  const match = /^(\d{2})-(\d{2})-(\d{2}|\d{4})$/.exec(text);
  if (match) {
    const year = match[3].length === 2 ? 2000 + Number(match[3]) : Number(match[3]);
    return validIsoDate(year, Number(match[2]), Number(match[1]));
  }
  const iso = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
  return iso ? validIsoDate(Number(iso[1]), Number(iso[2]), Number(iso[3])) : null;
}

export function excelDateText(value: ExcelCell) {
  return parseExcelDate(value) ?? excelCellText(value);
}

function isoDate(year: number, month: number, day: number) {
  return `${String(year).padStart(4, "0")}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

function validIsoDate(year: number, month: number, day: number) {
  if (!Number.isInteger(year) || year < 1 || month < 1 || month > 12 || day < 1 || day > 31) return null;
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
    ? isoDate(year, month, day)
    : null;
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
