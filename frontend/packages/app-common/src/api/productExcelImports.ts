import { ApiError, apiRequest } from "./client";
import type { ExcelFormulaCell } from "../components/excelFormula";
import type { ExcelSheet } from "../components/excelImport";

export type ProductExcelImportCell = {
  value: string | null;
  formula?: string | null;
  errorCode?: string | null;
};

export type ProductExcelImportFormula = {
  cell: string;
  formula: string;
  calculatedValue: string | null;
};

export type ProductExcelImportReadResult = {
  fileName: string;
  sha256: string;
  sheetName: string;
  rows: ProductExcelImportCell[][];
  formulas: ProductExcelImportFormula[];
  nonEmptyRows: number;
  columns: number;
  nonEmptyCells: number;
};

export type ProductExcelImportContext = "STOCK" | "WAREHOUSE_INPUT" | "WAREHOUSE_OUTPUT";

export type ProductExcelImportCellEdit = {
  row: number;
  column: string;
  value: string;
};

export type ProductExcelImportValueSource = {
  source: "excel" | "global";
  value: string;
};

export type ProductExcelImportPreviewConfig = {
  mapping: Record<string, string>;
  edits: ProductExcelImportCellEdit[];
  options: {
    globalValues: Record<string, string>;
    valueSources: Record<string, ProductExcelImportValueSource>;
    showOnlyImported: boolean;
    context: ProductExcelImportContext;
    skipZeroPriceUpdate: boolean;
    requireQuantity: boolean;
    documentPriceSource?: "purchasePrice" | "salePrice" | "memberPrice" | "wholesalePrice" | "offerPrice";
  };
  expectedSha256: string;
  startRow: number;
  quantityColumn?: string;
  updateFields: Record<string, boolean>;
  resolvedProducts?: Record<string, string>;
};

export type ProductExcelImportError = {
  code: string;
  row?: number | null;
  column?: number | null;
  attribute?: string | null;
  receivedValue?: string | null;
  reason: string;
  acceptedValues?: string | null;
  recommendedFix?: string | null;
};

export type ProductExcelImportPreviewRow = {
  rowNumber: number;
  rowNumbers: number[];
  classification: "EXISTING" | "MISSING" | "ERROR";
  existence?: "EXISTING" | "MISSING" | "AMBIGUOUS" | "UNRESOLVED";
  excelData: Record<string, unknown>;
  databaseData?: Record<string, unknown> | null;
  version?: number | null;
  changes: Record<string, unknown>;
  errors: ProductExcelImportError[];
  purchasePriceChanged: boolean;
  errorTexts?: Partial<Record<"es" | "en" | "zh", string>>;
  masterDataChanged?: boolean;
  concurrencyToken?: string | null;
};

export type ProductExcelImportPreviewResult = {
  fileName: string;
  sha256: string;
  sheetName: string;
  rows: ProductExcelImportPreviewRow[];
  sourceRows?: ProductExcelImportPreviewRow[];
  detectedRows: number;
  existingRows: number;
  missingRows: number;
  errors: ProductExcelImportError[];
  errorTexts?: Partial<Record<"es" | "en" | "zh", string>>;
  previewFingerprint?: string | null;
};

export type ProductExcelImportSummaryDownload = {
  blob: Blob;
  fileName: string;
};

export type ProductExcelImportExportView = "RAW" | "SUMMARY" | "MISSING" | "PURCHASE_CHANGED" | "IMPORTABLE" | "ERRORS";
export type ProductExcelImportErrorExportRow = Record<string, string>;

export type ProductExcelImportOperation = "CREATE_MISSING" | "UPDATE_PURCHASE_PRICE" | "UPDATE_SELECTED_FIELDS" | "PREPARE_DESTINATION";

export type ProductExcelImportApplyRequest = {
  operation?: ProductExcelImportOperation;
  expectedPreviewFingerprint?: string;
  preview: ProductExcelImportPreviewConfig;
  expectedConcurrencyTokens: Record<string, string>;
  autoAddMissing: boolean;
  confirmMasterChanges: boolean;
  warehouseId?: string;
  documentDate?: string;
  supplierId?: string | null;
  updateSupplier?: boolean;
};

export type ProductExcelImportAppliedRow = {
  rowNumber: number;
  rowNumbers: number[];
  classification: "EXISTING" | "MISSING";
  productId: string | null;
  errors: ProductExcelImportError[];
};

export type ProductExcelImportApplyResult = {
  fileName: string;
  sha256: string | null;
  rows: ProductExcelImportAppliedRow[];
  errors: ProductExcelImportError[];
  appliedCount: number;
  warehouseMetadata?: {
    fileName?: string | null;
    sha256?: string | null;
    sheetName?: string | null;
    updateSupplier: boolean;
    skipZeroPriceUpdate: boolean;
    formulas: Array<{ cell: string; formula: string; calculatedValue?: string | null }>;
    lines: Array<{
      productId: string;
      rowNumbers: number[];
      supplierReference?: string | null;
      grossPurchasePrice?: string | null;
      purchaseDiscountPercent?: string | null;
    }>;
  } | null;
  warehouseProvenanceToken?: string | null;
};

export type ProductExcelImportSelectableTax = {
  id: string;
  defaultTax?: boolean | null;
  active?: boolean | null;
  name?: string | null;
  percentage?: string | number | null;
};

export async function readProductExcelImport(
  file: File,
  token?: string,
  signal?: AbortSignal
): Promise<ProductExcelImportReadResult> {
  const body = new FormData();
  body.append("file", file);
  return apiRequest<ProductExcelImportReadResult>("/product-excel-imports/read", {
    method: "POST",
    token,
    body,
    signal
  });
}

export function productExcelReadResultToSheet(result: ProductExcelImportReadResult): ExcelSheet {
  return result.rows.map((row) => row.map((cell) => {
    if (!cell.formula) {
      return cell.value;
    }
    return {
      kind: "formula",
      formula: cell.formula,
      value: cell.value
    } satisfies ExcelFormulaCell;
  }));
}

export async function previewProductExcelImport(
  file: File,
  config: ProductExcelImportPreviewConfig,
  token?: string,
  signal?: AbortSignal
): Promise<ProductExcelImportPreviewResult> {
  const body = new FormData();
  body.append("file", file);
  body.append("config", new Blob([JSON.stringify(config)], { type: "application/json" }));
  return apiRequest<ProductExcelImportPreviewResult>("/product-excel-imports/preview", {
    method: "POST",
    token,
    body,
    signal
  });
}

export async function exportProductExcelImportSummary(
  file: File,
  config: ProductExcelImportPreviewConfig,
  expectedPreviewFingerprint: string,
  locale: "es" | "en" | "zh" = "es",
  token?: string,
  signal?: AbortSignal,
  projection?: { view: ProductExcelImportExportView; columns: string[]; errorRows?: ProductExcelImportErrorExportRow[] }
): Promise<ProductExcelImportSummaryDownload> {
  const body = new FormData();
  body.append("file", file);
  body.append("config", new Blob([JSON.stringify({
    preview: config,
    expectedPreviewFingerprint,
    ...projection
  })], { type: "application/json" }));
  body.append("locale", locale);
  const response = await fetchProductExcelSummary(body, token, signal);
  return {
    blob: response.blob,
    fileName: contentDispositionFileName(response.contentDisposition) ?? `${safeSummaryStem(file.name)}-resumen.xlsx`
  };
}

export async function applyProductExcelImport(
  file: File,
  request: ProductExcelImportApplyRequest,
  token?: string,
  signal?: AbortSignal
): Promise<ProductExcelImportApplyResult> {
  const body = new FormData();
  body.append("file", file);
  body.append("config", new Blob([JSON.stringify(request)], { type: "application/json" }));
  return apiRequest<ProductExcelImportApplyResult>("/product-excel-imports/apply", {
    method: "POST",
    token,
    body,
    signal
  });
}

async function fetchProductExcelSummary(body: FormData, token?: string, signal?: AbortSignal) {
  let contentDisposition: string | null = null;
  const response = await apiRequest<Blob>("/product-excel-imports/summary.xlsx", {
    method: "POST",
    token,
    body,
    signal,
    responseType: "blob",
    onResponseHeaders: (headers) => { contentDisposition = headers.get("Content-Disposition"); }
  });
  return { blob: response, contentDisposition };
}

function safeSummaryStem(fileName: string) {
  const stem = fileName.replace(/\.[^.]*$/, "").replace(/[^a-zA-Z0-9._-]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 120);
  return stem || "importacion-excel";
}

function contentDispositionFileName(value: string | null) {
  if (!value) return null;
  const encoded = value.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  if (encoded) {
    try { return decodeURIComponent(encoded.replace(/^"|"$/g, "")); } catch { return null; }
  }
  const plain = value.match(/filename="?([^";]+)"?/i)?.[1];
  return plain ?? null;
}

export function loadProductExcelImportTaxes(token?: string, signal?: AbortSignal) {
  return apiRequest<ProductExcelImportSelectableTax[]>("/taxes/selectable", { token, signal });
}

function productExcelImportErrorFromProblem(
  problem: Record<string, unknown>,
  fallbackReason: string
): ProductExcelImportError {
  const text = (key: string) => typeof problem[key] === "string" ? String(problem[key]) : null;
  const number = (key: string) => typeof problem[key] === "number" ? Number(problem[key]) : null;
  return {
    code: text("code") ?? "IMPORT_FAILED",
    row: number("row"),
    column: number("column"),
    attribute: text("attribute"),
    receivedValue: text("receivedValue"),
    reason: text("reason") ?? text("detail") ?? fallbackReason,
    acceptedValues: text("acceptedValues"),
    recommendedFix: text("recommendedFix")
  };
}

/**
 * Extract every structured import error returned by a failed endpoint.
 * Apply/preview responses may be an RFC 7807-like object with an `errors`
 * array, while older endpoints return one problem object directly. Keep the
 * complete row/column/value guidance instead of silently dropping all but the
 * first error.
 */
export function productExcelImportErrorsFromApi(error: unknown): ProductExcelImportError[] {
  if (!(error instanceof ApiError) || !error.problem) return [];
  const problem = error.problem;
  const errors = Array.isArray(problem.errors)
    ? problem.errors
      .filter((item): item is Record<string, unknown> => Boolean(item && typeof item === "object"))
      .map((item) => productExcelImportErrorFromProblem(item, error.message))
    : [];
  if (errors.length > 0) return errors;
  return [productExcelImportErrorFromProblem(problem, error.message)];
}

/** Backward-compatible single-error view for callers that only need a status. */
export function productExcelImportErrorFromApi(error: unknown): ProductExcelImportError | null {
  return productExcelImportErrorsFromApi(error)[0] ?? null;
}
