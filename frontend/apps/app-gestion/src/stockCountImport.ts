import {
  excelCellText,
  normalizeExcelHeader,
  normalizeExcelText,
  readExcelTable,
  type ExcelTableRow
} from "../../../packages/app-common/src/components/excelImport";
import type { LocaleCode } from "../../../packages/app-common/src/types";

export type StockCountImportProduct = {
  id: string;
  code?: string | null;
  barcode?: string | null;
  name?: string | null;
  productType?: string | null;
  active?: boolean | null;
};

export type StockCountImportLine = {
  productId: string;
  countedQuantity: number | null;
};

export type StockCountImportResult = {
  lines: StockCountImportLine[];
  errors: string[];
};

const columns = {
  code: ["code", "codigo", "código", "编码", "商品编码"],
  barcode: ["barcode", "codigo de barras", "código de barras", "条码"],
  name: ["name", "nombre", "名称", "商品名称"],
  quantity: ["cantidad contada", "cantidad", "countedQuantity", "counted quantity", "quantity", "盘点数量", "数量"]
} as const;

type Column = keyof typeof columns;

const messages = {
  es: {
    duplicateColumn: (name: string) => `Hay varias columnas para ${name}.`,
    missingIdentityColumn: "El Excel debe incluir Código, Código de barras o Nombre.",
    missingQuantityColumn: "El Excel debe incluir Cantidad contada.",
    missingIdentity: "falta Código, Código de barras o Nombre.",
    missingValue: (label: string, value: string) => `no se encuentra el ${label} "${value}".`,
    ambiguousValue: (label: string, value: string) => `el ${label} "${value}" es ambiguo.`,
    conflictingIdentifiers: "Código y Código de barras identifican productos distintos.",
    inactive: "el producto está inactivo.",
    service: "un servicio no tiene existencias.",
    duplicateProduct: "el producto ya aparece en otra fila.",
    invalidQuantity: (isUnit: boolean) => `Cantidad contada inválida${isUnit ? " (las unidades deben ser enteras)" : ""}; usa un número no negativo con hasta 3 decimales.`,
    noRows: "El Excel no contiene filas de productos."
  },
  en: {
    duplicateColumn: (name: string) => `Multiple columns found for ${name}.`,
    missingIdentityColumn: "The Excel file must include Code, Barcode or Name.",
    missingQuantityColumn: "The Excel file must include Counted quantity.",
    missingIdentity: "Code, Barcode or Name is missing.",
    missingValue: (label: string, value: string) => `${label} "${value}" was not found.`,
    ambiguousValue: (label: string, value: string) => `${label} "${value}" is ambiguous.`,
    conflictingIdentifiers: "Code and Barcode identify different products.",
    inactive: "the product is inactive.",
    service: "a service has no stock.",
    duplicateProduct: "the product already appears in another row.",
    invalidQuantity: (isUnit: boolean) => `Invalid counted quantity${isUnit ? " (unit products require whole numbers)" : ""}; use a non-negative number with up to 3 decimal places.`,
    noRows: "The Excel file contains no product rows."
  },
  zh: {
    duplicateColumn: (name: string) => `${name}有多个列。`,
    missingIdentityColumn: "Excel 必须包含编码、条码或名称列。",
    missingQuantityColumn: "Excel 必须包含盘点数量列。",
    missingIdentity: "缺少编码、条码或名称。",
    missingValue: (label: string, value: string) => `找不到${label}“${value}”。`,
    ambiguousValue: (label: string, value: string) => `${label}“${value}”对应多个商品。`,
    conflictingIdentifiers: "编码和条码对应不同商品。",
    inactive: "商品已停用。",
    service: "服务商品没有库存。",
    duplicateProduct: "此商品已在其他行出现。",
    invalidQuantity: (isUnit: boolean) => `盘点数量无效${isUnit ? "（计件商品必须是整数）" : ""}；请输入非负数，最多保留 3 位小数。`,
    noRows: "Excel 中没有商品行。"
  }
} satisfies Record<LocaleCode, object>;

const labels = {
  es: { code: "Código", barcode: "Código de barras", name: "Nombre", quantity: "Cantidad contada" },
  en: { code: "Code", barcode: "Barcode", name: "Name", quantity: "Counted quantity" },
  zh: { code: "编码", barcode: "条码", name: "名称", quantity: "盘点数量" }
} satisfies Record<LocaleCode, Record<Column, string>>;

export async function importStockCount(
  file: File,
  products: StockCountImportProduct[],
  locale: LocaleCode = "es"
): Promise<StockCountImportResult> {
  const message = messages[locale];
  const label = labels[locale];
  const rowError = (row: number, detail: string) => locale === "zh" ? `第 ${row} 行：${detail}` : `${locale === "en" ? "Row" : "Fila"} ${row}: ${detail}`;
  const table = await readExcelTable(file);
  const errors: string[] = [];
  const selected = {} as Partial<Record<Column, string>>;

  for (const column of Object.keys(columns) as Column[]) {
    const aliases = new Set<string>(columns[column].map(normalizeExcelHeader));
    const matches = table.headers.filter((header) => aliases.has(normalizeExcelHeader(header)));
    if (matches.length > 1) {
      errors.push(message.duplicateColumn(label[column]));
    } else if (matches.length === 1) {
      selected[column] = matches[0];
    }
  }
  if (!selected.code && !selected.barcode && !selected.name) {
    errors.push(message.missingIdentityColumn);
  }
  if (!selected.quantity) {
    errors.push(message.missingQuantityColumn);
  }
  if (errors.length) return { lines: [], errors };

  const codeIndex = indexProducts(products, (product) => product.code);
  const barcodeIndex = indexProducts(products, (product) => product.barcode);
  const nameIndex = indexProducts(products, (product) => product.name, normalizeExcelText);
  const seen = new Set<string>();
  const lines: StockCountImportLine[] = [];

  table.rows.forEach((row, index) => {
    if (!Object.values(row).some((cell) => excelCellText(cell) !== "")) return;
    const rowNumber = index + 2;
    const code = cell(row, selected.code);
    const barcode = cell(row, selected.barcode);
    const name = cell(row, selected.name);
    const quantityText = cell(row, selected.quantity);
    const matches: StockCountImportProduct[] = [];
    let invalidIdentity = false;

    for (const [label, value, productIndex, normalize] of [
      [locale === "es" ? "código" : labels[locale].code, code, codeIndex, identity],
      [locale === "es" ? "código de barras" : labels[locale].barcode, barcode, barcodeIndex, identity]
    ] as const) {
      if (value === "") continue;
      const candidates = productIndex.get(normalize(value)) ?? [];
      if (candidates.length !== 1) {
        errors.push(rowError(rowNumber, candidates.length ? message.ambiguousValue(label, value) : message.missingValue(label, value)));
        invalidIdentity = true;
      } else {
        matches.push(candidates[0]);
      }
    }
    if (code === "" && barcode === "") {
      if (name === "") {
        errors.push(rowError(rowNumber, message.missingIdentity));
        invalidIdentity = true;
      } else {
        const candidates = nameIndex.get(normalizeExcelText(name)) ?? [];
        if (candidates.length !== 1) {
          errors.push(rowError(rowNumber, candidates.length ? message.ambiguousValue(locale === "es" ? "nombre" : label.name, name) : message.missingValue(locale === "es" ? "nombre" : label.name, name)));
          invalidIdentity = true;
        } else {
          matches.push(candidates[0]);
        }
      }
    }
    if (invalidIdentity) return;
    const product = matches[0];
    if (matches.some((match) => match.id !== product.id)) {
      errors.push(rowError(rowNumber, message.conflictingIdentifiers));
      return;
    }
    if (product.active === false) {
      errors.push(rowError(rowNumber, message.inactive));
      return;
    }
    if (product.productType === "SERVICE") {
      errors.push(rowError(rowNumber, message.service));
      return;
    }
    if (seen.has(product.id)) {
      errors.push(rowError(rowNumber, message.duplicateProduct));
      return;
    }
    const countedQuantity = parseQuantity(quantityText, product.productType);
    if (countedQuantity === undefined) {
      errors.push(rowError(rowNumber, message.invalidQuantity(product.productType === "UNIT")));
      return;
    }
    seen.add(product.id);
    lines.push({ productId: product.id, countedQuantity });
  });

  if (!lines.length && !errors.length) errors.push(message.noRows);
  // Callers must never be able to apply a subset after a row fails validation.
  return { lines: errors.length ? [] : lines, errors };
}

function identity(value: string) {
  return value;
}

function indexProducts(
  products: StockCountImportProduct[],
  value: (product: StockCountImportProduct) => string | null | undefined,
  normalize: (value: string) => string = identity
) {
  const index = new Map<string, StockCountImportProduct[]>();
  products.forEach((product) => {
    const key = normalize(String(value(product) ?? "").trim());
    if (key !== "") index.set(key, [...(index.get(key) ?? []), product]);
  });
  return index;
}

function cell(row: ExcelTableRow, header?: string) {
  return header === undefined ? "" : excelCellText(row[header]);
}

function parseQuantity(value: string, productType?: string | null): number | null | undefined {
  if (value === "") return null;
  if (!/^\d+(?:[.,]\d{1,3})?$/.test(value)) return undefined;
  const quantity = Number(value.replace(",", "."));
  if (!Number.isFinite(quantity) || quantity > Number.MAX_SAFE_INTEGER / 1000) return undefined;
  if (productType === "UNIT" && !Number.isInteger(quantity)) return undefined;
  return quantity;
}
