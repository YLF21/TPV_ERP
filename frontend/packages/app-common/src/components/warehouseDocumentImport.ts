import { normalizeExcelHeader, normalizeExcelText, readExcelTable } from "./excelImport";

export type WarehouseImportProduct = {
  id: string;
  code?: string | null;
  barcode?: string | null;
  reference?: string | null;
  name?: string | null;
  discountType?: string | null;
  salePrice?: string | number | null;
  wholesalePrice?: string | number | null;
  purchasePrice?: string | number | null;
};

export type WarehouseDocumentImportRow = Record<string, unknown>;

export type WarehouseDocumentLineDraft = {
  rowNumber: number;
  productId: string;
  productLabel: string;
  importedProduct: string;
  quantity: number;
  discountPercent?: string;
  valid: boolean;
  errorKey: string;
};

const productKeys = ["producto", "product", "codigo", "code", "referencia", "reference", "barcode", "codigo de barras"];
const quantityKeys = ["cantidad", "quantity", "unidades", "uds"];

export function buildWarehouseDocumentLines(
  rows: WarehouseDocumentImportRow[],
  products: WarehouseImportProduct[]
): WarehouseDocumentLineDraft[] {
  const index = buildProductIndex(products);
  return rows
    .filter((row) => Object.values(row).some((value) => String(value ?? "").trim()))
    .map((row, indexNumber) => {
      const importedProduct = firstValue(row, productKeys);
      const product = index.get(normalize(importedProduct));
      const quantity = parseQuantity(firstValue(row, quantityKeys));
      const productLabel = product ? productLabelText(product) : importedProduct;
      const errorKey = !product
        ? "warehouseDocument.error.productNotFound"
        : quantity <= 0
          ? "warehouseDocument.error.invalidQuantity"
          : "";
      return {
        rowNumber: indexNumber + 2,
        productId: product?.id ?? "",
        productLabel,
        importedProduct,
        quantity,
        discountPercent: "0",
        valid: !errorKey,
        errorKey
      };
    });
}

export async function readWarehouseDocumentFile(file: File, products: WarehouseImportProduct[]) {
  const table = await readExcelTable(file);
  return table.headers.length === 0 ? [] : buildWarehouseDocumentLines(table.rows, products);
}

function buildProductIndex(products: WarehouseImportProduct[]) {
  const index = new Map<string, WarehouseImportProduct>();
  products.forEach((product) => {
    [product.code, product.barcode, product.reference, product.name]
      .map((value) => normalize(value ?? ""))
      .filter(Boolean)
      .forEach((key) => {
        if (!index.has(key)) {
          index.set(key, product);
        }
      });
  });
  return index;
}

function firstValue(row: WarehouseDocumentImportRow, keys: string[]) {
  const entries = Object.entries(row);
  for (const key of keys) {
    const match = entries.find(([candidate]) => normalizeHeader(candidate) === normalizeHeader(key));
    if (match) {
      return String(match[1] ?? "").trim();
    }
  }
  return "";
}

function parseQuantity(value: string) {
  const quantity = Number(value.replace(",", "."));
  return Number.isFinite(quantity) && quantity > 0 ? quantity : 0;
}

function productLabelText(product: WarehouseImportProduct) {
  return [product.code, product.name].filter(Boolean).join(" - ") || product.id;
}

function normalizeHeader(value: string) {
  return normalizeExcelHeader(value);
}

function normalize(value: string) {
  return normalizeExcelText(value);
}
