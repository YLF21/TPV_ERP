import { readSheet } from "read-excel-file/browser";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import type { StockInventoryRow } from "./StockScreen";

export type StockBulkEditRowData = {
  id: string;
  selected: boolean;
  query: string;
  product?: StockInventoryRow;
  draft: Partial<StockInventoryRow>;
  suppliers?: StockBulkSupplierInfo[];
  pendingSupplier?: StockBulkSupplierInfo;
};

export type StockBulkSupplierInfo = {
  id: string;
  supplierCode: string;
  legalName: string;
  tradeName?: string | null;
  documentNumber: string;
  active: boolean;
  supplierReference?: string | null;
  principal?: boolean;
  lastSupplier?: boolean;
  grossPurchasePrice?: number | string | null;
  purchaseDiscount?: number | string | null;
  netPurchasePrice?: number | string | null;
  lastEntryAt?: string | null;
};

export type StockBulkStoreSupplierLink = Omit<StockBulkSupplierInfo, "id"> & {
  productId: string;
  supplierId: string;
};

export type StockBulkSupplierProductLink = {
  productId: string;
  supplierReference?: string | null;
  principal?: boolean;
  lastSupplier: boolean;
  grossPurchasePrice?: number | string | null;
  purchaseDiscount?: number | string | null;
  netPurchasePrice?: number | string | null;
  lastEntryAt?: string | null;
};

export function hydrateStockBulkSupplierData(
  rows: StockBulkEditRowData[],
  links: StockBulkStoreSupplierLink[]
): StockBulkEditRowData[] {
  const suppliersByProduct = new Map<string, StockBulkSupplierInfo[]>();
  links.forEach((link) => {
    const suppliers = suppliersByProduct.get(link.productId) ?? [];
    suppliers.push({
      id: link.supplierId,
      supplierCode: link.supplierCode,
      legalName: link.legalName,
      tradeName: link.tradeName,
      documentNumber: link.documentNumber,
      active: link.active,
      supplierReference: link.supplierReference,
      principal: link.principal,
      lastSupplier: link.lastSupplier,
      grossPurchasePrice: link.grossPurchasePrice,
      purchaseDiscount: link.purchaseDiscount,
      netPurchasePrice: link.netPurchasePrice,
      lastEntryAt: link.lastEntryAt
    });
    suppliersByProduct.set(link.productId, suppliers);
  });
  suppliersByProduct.forEach((suppliers) => suppliers.sort((left, right) => {
    const lastDifference = Number(Boolean(right.lastSupplier)) - Number(Boolean(left.lastSupplier));
    if (lastDifference !== 0) return lastDifference;
    const principalDifference = Number(Boolean(right.principal)) - Number(Boolean(left.principal));
    if (principalDifference !== 0) return principalDifference;
    return left.legalName.localeCompare(right.legalName, undefined, { sensitivity: "base" });
  }));

  return rows.map((row) => row.product
    ? { ...row, suppliers: suppliersByProduct.get(row.product.productId) ?? [] }
    : row);
}

export type StockBulkSupplierAssignment = {
  supplierId: string;
  productIds: string[];
};

export type StockBulkPurchaseDocumentLine = {
  productId: string;
  grossPurchasePrice: number | string;
  purchaseDiscount: number | string;
};

export type StockBulkValidationField = keyof StockInventoryRow | "productId";

export type StockBulkValidationError = {
  rowId: string;
  field: StockBulkValidationField;
  code:
    | "duplicate"
    | "required"
    | "invalidNumber"
    | "invalidPercentage"
    | "invalidDate"
    | "offerPriceRequired"
    | "offerDiscountRequired"
    | "offerFromRequired";
};

export type StockBulkComment = {
  id: string;
  text: string;
  username: string;
  createdAt: string;
};

export type StockBulkDraftView = {
  id: string;
  code: string;
  seriesId: string;
  versionNumber: number;
  previousVersionId?: string | null;
  name: string;
  status: "PENDING" | "APPLIED";
  content: StockBulkEditRowData[];
  version: number;
  createdById: string;
  createdBy: string;
  createdAt: string;
  updatedById: string;
  updatedBy: string;
  updatedAt: string;
  appliedById?: string | null;
  appliedBy?: string | null;
  appliedAt?: string | null;
  comments: Array<{
    id: string;
    userId: string;
    username: string;
    text: string;
    createdAt: string;
  }>;
};

export type StockBulkXlsxDownload = {
  blob: Blob;
  fileName: string;
};

export type StockBulkImportCatalogs = {
  locale?: LocaleCode;
  families?: Array<{ id: string; name: string }>;
  subfamilies?: Array<{ id: string; familyId: string; name: string }>;
  taxes?: Array<{ id: string; name: string }>;
};

export type StockBulkProductUpdate = {
  productId: string;
  expectedVersion: number;
  product: {
    familyId: string;
    subfamilyId: string | null;
    taxId: string;
    productType: string;
    discountType: string;
    priceUseMode: string;
    name: string;
    description: string | null;
    comments: string | null;
    purchasePrice: number;
    taxesIncluded: boolean;
    code: string | null;
    barcode: string | null;
    barcode2: string | null;
    salePrice: number;
    memberPrice: number | null;
    wholesalePrice: number | null;
    offerPrice: number | null;
    offerDiscountPercent: number | null;
    purchaseDiscountPercent: number | null;
    offerActive: boolean;
    offerFrom: string | null;
    offerUntil: string | null;
  };
};

const excelFields: Array<{ keys: string[]; field: keyof StockInventoryRow }> = [
  { keys: ["nombre", "name"], field: "name" },
  { keys: ["descripcion", "description"], field: "description" },
  { keys: ["precio compra", "purchase price"], field: "purchasePrice" },
  { keys: ["descuento compra", "purchase discount"], field: "purchaseDiscountPercent" },
  { keys: ["precio venta", "sale price"], field: "salePrice" },
  { keys: ["precio socio", "member price"], field: "memberPrice" },
  { keys: ["precio mayor", "wholesale price"], field: "wholesalePrice" },
  { keys: ["precio oferta", "offer price"], field: "offerPrice" },
  { keys: ["usar precio", "price use"], field: "discountType" },
  { keys: ["descuento oferta", "offer discount"], field: "offerDiscountPercent" },
  { keys: ["oferta desde", "offer from"], field: "offerFrom" },
  { keys: ["oferta hasta", "offer until"], field: "offerUntil" },
  { keys: ["comentarios", "comments"], field: "comments" }
];

const excelReferenceFields = {
  family: {
    id: ["familia id", "family id"],
    name: ["familia", "family"]
  },
  subfamily: {
    id: ["subfamilia id", "subfamily id"],
    name: ["subfamilia", "subfamily"]
  },
  tax: {
    id: ["impuesto id", "tax id"],
    name: ["impuesto", "tax"]
  }
} as const;

type StockBulkNamedReference = {
  id: string;
  name: string;
  parentId?: string;
  normalizedNames: Set<string>;
};

export function stockBulkRowsChanged(rows: StockBulkEditRowData[]) {
  return rows.some((row) => row.pendingSupplier || (row.product && Object.keys(row.draft).some((key) => {
    const field = key as keyof StockInventoryRow;
    return text(row.draft[field]) !== text(row.product?.[field]);
  })));
}

export function stockOfferPriceFromDiscount(salePrice: unknown, discountPercent: unknown) {
  const sale = parsedNumber(salePrice);
  const discount = parsedNumber(discountPercent);
  if (sale === null || discount === null || sale < 0 || discount < 0 || discount > 100) {
    return null;
  }
  return Math.max(0, sale - sale * discount / 100).toFixed(2);
}

export function validateStockBulkRows(rows: StockBulkEditRowData[]): StockBulkValidationError[] {
  const errors: StockBulkValidationError[] = [];
  const productRows = rows.filter((row) => row.product);
  const firstRowByProduct = new Map<string, string>();

  const add = (rowId: string, field: StockBulkValidationField, code: StockBulkValidationError["code"]) => {
    if (!errors.some((error) => error.rowId === rowId && error.field === field && error.code === code)) {
      errors.push({ rowId, field, code });
    }
  };

  productRows.forEach((row) => {
    const product = row.product!;
    const duplicateRowId = firstRowByProduct.get(product.productId);
    if (duplicateRowId) {
      add(duplicateRowId, "productId", "duplicate");
      add(row.id, "productId", "duplicate");
    } else {
      firstRowByProduct.set(product.productId, row.id);
    }

    const value = (field: keyof StockInventoryRow) => row.draft[field] ?? product[field];
    (["familyId", "taxId", "productType", "name"] as const).forEach((field) => {
      if (nullableText(value(field)) === null) {
        add(row.id, field, "required");
      }
    });
    if (nullableText(value("code")) === null && nullableText(value("barcode")) === null) {
      add(row.id, "productId", "required");
    }

    (["purchasePrice", "salePrice"] as const).forEach((field) => {
      const number = parsedNumber(value(field));
      if (number === null || number < 0) {
        add(row.id, field, "invalidNumber");
      }
    });
    (["memberPrice", "wholesalePrice", "offerPrice"] as const).forEach((field) => {
      const raw = nullableText(value(field));
      if (raw !== null) {
        const number = parsedNumber(raw);
        if (number === null || number < 0) {
          add(row.id, field, "invalidNumber");
        }
      }
    });
    (["purchaseDiscountPercent", "offerDiscountPercent"] as const).forEach((field) => {
      const raw = nullableText(value(field));
      if (raw !== null) {
        const number = parsedNumber(raw);
        if (number === null || number < 0 || number > 100) {
          add(row.id, field, "invalidPercentage");
        }
      }
    });

    const priceUseMode = normalizedPriceUse(value("discountType"));
    const offerFrom = nullableText(value("offerFrom"));
    const offerUntil = nullableText(value("offerUntil"));
    if (priceUseMode === "OFFER_PRICE" && nullableText(value("offerPrice")) === null) {
      add(row.id, "offerPrice", "offerPriceRequired");
    }
    if (priceUseMode === "OFFER_DISCOUNT" && nullableText(value("offerDiscountPercent")) === null) {
      add(row.id, "offerDiscountPercent", "offerDiscountRequired");
    }
    if ((priceUseMode === "OFFER_PRICE" || priceUseMode === "OFFER_DISCOUNT") && offerFrom === null) {
      add(row.id, "offerFrom", "offerFromRequired");
    }
    if (offerFrom !== null && !isIsoDate(offerFrom)) {
      add(row.id, "offerFrom", "invalidDate");
    }
    if (offerUntil !== null && (!isIsoDate(offerUntil) || offerFrom === null || offerUntil < offerFrom)) {
      add(row.id, "offerUntil", "invalidDate");
    }
  });

  return errors;
}

export function buildStockBulkSupplierAssignments(
  rows: StockBulkEditRowData[]
): StockBulkSupplierAssignment[] {
  const assignments = new Map<string, Set<string>>();
  rows.forEach((row) => {
    if (!row.product || !row.pendingSupplier) {
      return;
    }
    const productIds = assignments.get(row.pendingSupplier.id) ?? new Set<string>();
    productIds.add(row.product.productId);
    assignments.set(row.pendingSupplier.id, productIds);
  });
  return Array.from(assignments, ([supplierId, productIds]) => ({
    supplierId,
    productIds: Array.from(productIds)
  }));
}

export function finalizeStockBulkSupplierAssignments(
  rows: StockBulkEditRowData[]
): StockBulkEditRowData[] {
  return rows.map((row) => {
    if (!row.pendingSupplier) {
      return row;
    }
    const suppliers = [
      ...(row.suppliers ?? []).filter((supplier) => supplier.id !== row.pendingSupplier?.id),
      row.pendingSupplier
    ];
    const { pendingSupplier: _pendingSupplier, ...finalized } = row;
    return { ...finalized, suppliers };
  });
}

export function mergeStockBulkSupplierProducts(
  rows: StockBulkEditRowData[],
  products: StockInventoryRow[],
  supplier: StockBulkSupplierInfo,
  links: StockBulkSupplierProductLink[]
): StockBulkEditRowData[] {
  const productsById = new Map(products.map((product) => [product.productId, product]));
  const linksByProductId = new Map(links.map((link) => [link.productId, link]));
  const importedSupplier = (productId: string): StockBulkSupplierInfo => ({
    ...supplier,
    ...linksByProductId.get(productId)
  });
  const existingProductIds = new Set(rows.flatMap((row) => row.product ? [row.product.productId] : []));
  const merged = rows
    .filter((row) => row.product || row.query.trim())
    .map((row) => {
      if (!row.product || !linksByProductId.has(row.product.productId)) {
        return row;
      }
      const linkedSupplier = importedSupplier(row.product.productId);
      return {
        ...row,
        suppliers: [
          ...(row.suppliers ?? []).filter((value) => value.id !== supplier.id),
          linkedSupplier
        ]
      };
    });
  links.forEach((link, index) => {
    const product = productsById.get(link.productId);
    if (!product || existingProductIds.has(link.productId)) {
      return;
    }
    existingProductIds.add(link.productId);
    merged.push({
      id: `bulk-supplier-${supplier.id}-${link.productId}-${index}`,
      selected: false,
      query: product.code || product.barcode || product.name,
      product,
      draft: { ...product },
      suppliers: [importedSupplier(link.productId)]
    });
  });
  merged.push({
    id: `bulk-supplier-empty-${supplier.id}-${Date.now()}`,
    selected: false,
    query: "",
    draft: {}
  });
  return merged;
}

export function mergeStockBulkPurchaseDocumentProducts(
  rows: StockBulkEditRowData[],
  products: StockInventoryRow[],
  documentLines: StockBulkPurchaseDocumentLine[]
): StockBulkEditRowData[] {
  const productsById = new Map(products.map((product) => [product.productId, product]));
  const lastLineByProduct = new Map<string, StockBulkPurchaseDocumentLine>();
  documentLines.forEach((line) => lastLineByProduct.set(line.productId, line));
  const existingProductIds = new Set(rows.flatMap((row) => row.product ? [row.product.productId] : []));
  const merged = rows
    .filter((row) => row.product || row.query.trim())
    .map((row) => {
      if (!row.product) {
        return row;
      }
      const line = lastLineByProduct.get(row.product.productId);
      if (!line) {
        return row;
      }
      return {
        ...row,
        draft: {
          ...row.draft,
          purchasePrice: String(line.grossPurchasePrice),
          purchaseDiscountPercent: String(line.purchaseDiscount)
        }
      };
    });
  lastLineByProduct.forEach((line, productId) => {
    const product = productsById.get(productId);
    if (!product || existingProductIds.has(productId)) {
      return;
    }
    existingProductIds.add(productId);
    merged.push({
      id: `bulk-purchase-document-${productId}-${merged.length}`,
      selected: false,
      query: product.code || product.barcode || product.name,
      product,
      draft: {
        ...product,
        purchasePrice: String(line.grossPurchasePrice),
        purchaseDiscountPercent: String(line.purchaseDiscount)
      }
    });
  });
  merged.push({
    id: `bulk-purchase-document-empty-${Date.now()}`,
    selected: false,
    query: "",
    draft: {}
  });
  return merged;
}

export function buildStockBulkUpdates(rows: StockBulkEditRowData[]): StockBulkProductUpdate[] {
  return rows.filter((row) => row.product && Object.keys(row.draft).some((key) => {
    const field = key as keyof StockInventoryRow;
    return text(row.draft[field]) !== text(row.product?.[field]);
  })).map((row) => {
    const value = <K extends keyof StockInventoryRow>(field: K) => row.draft[field] ?? row.product?.[field];
    const priceUseMode = normalizedPriceUse(value("discountType"));
    const originalDiscountType = String(row.product?.backendDiscountType ?? "NORMAL");
    const discountType = priceUseMode === "NORMAL" && originalDiscountType === "NONE"
      ? "NONE"
      : priceUseMode === "MEMBER_PRICE"
        ? "MEMBER_PRICE"
        : priceUseMode === "OFFER_PRICE" || priceUseMode === "OFFER_DISCOUNT"
          ? "DISCOUNT_PRICE"
          : "NORMAL";
    const salePrice = numberValue(value("salePrice"));
    const offerDiscountPercent = nullableNumber(value("offerDiscountPercent"));
    const calculatedOfferPrice = priceUseMode === "OFFER_DISCOUNT"
      ? stockOfferPriceFromDiscount(salePrice, offerDiscountPercent)
      : null;
    return {
      productId: row.product!.productId,
      expectedVersion: Number(row.product!.version ?? 0),
      product: {
        familyId: requiredText(value("familyId"), "familyId"),
        subfamilyId: nullableText(value("subfamilyId")),
        taxId: requiredText(value("taxId"), "taxId"),
        productType: requiredText(value("productType"), "productType"),
        discountType,
        priceUseMode,
        name: requiredText(value("name"), "name"),
        description: nullableText(value("description")),
        comments: nullableText(value("comments")),
        purchasePrice: numberValue(value("purchasePrice")),
        taxesIncluded: booleanValue(value("taxesIncluded")),
        code: nullableText(value("code")),
        barcode: nullableText(value("barcode")),
        barcode2: nullableText(value("barcode2")),
        salePrice,
        memberPrice: nullableNumber(value("memberPrice")),
        wholesalePrice: nullableNumber(value("wholesalePrice")),
        offerPrice: calculatedOfferPrice === null
          ? nullableNumber(value("offerPrice"))
          : Number(calculatedOfferPrice),
        offerDiscountPercent,
        purchaseDiscountPercent: nullableNumber(value("purchaseDiscountPercent")),
        offerActive: priceUseMode === "OFFER_PRICE" || priceUseMode === "OFFER_DISCOUNT",
        offerFrom: nullableText(value("offerFrom")),
        offerUntil: nullableText(value("offerUntil"))
      }
    };
  });
}

export function stockBulkEffectiveProduct(row: StockBulkEditRowData): StockInventoryRow | undefined {
  if (!row.product) {
    return undefined;
  }
  const changes = Object.fromEntries(
    Object.entries(row.draft).filter(([, value]) => value !== null && value !== undefined)
  ) as Partial<StockInventoryRow>;
  return { ...row.product, ...changes };
}

export function stockBulkVersionedDeletePath(id: string, version: number) {
  return `/product-bulk-edits/${encodeURIComponent(id)}?version=${encodeURIComponent(String(version))}`;
}

export function stockBulkExportFileName(contentDisposition: string | null, fallback: string) {
  if (!contentDisposition) {
    return fallback;
  }
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(contentDisposition)?.[1];
  if (encoded) {
    try {
      return decodeURIComponent(encoded.replace(/^"|"$/g, ""));
    } catch {
      return encoded.replace(/^"|"$/g, "");
    }
  }
  return /filename="([^"]+)"/i.exec(contentDisposition)?.[1]
    ?? /filename=([^;]+)/i.exec(contentDisposition)?.[1]?.trim()
    ?? fallback;
}

export async function requestStockBulkXlsx(
  apiRoot: string,
  token: string,
  content: StockBulkEditRowData[],
  request: typeof fetch = fetch
): Promise<StockBulkXlsxDownload> {
  const response = await request(`${apiRoot}/product-bulk-edits/export`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ content })
  });
  if (!response.ok) {
    let message = response.statusText || "xlsx_export_error";
    try {
      const problem = await response.json() as { detail?: string; code?: string };
      message = problem.detail || problem.code || message;
    } catch {
      // Keep the HTTP status text when the backend does not return a problem body.
    }
    throw new Error(message);
  }
  return {
    blob: await response.blob(),
    fileName: stockBulkExportFileName(
      response.headers.get("Content-Disposition"),
      "productos-edicion-masiva.xlsx"
    )
  };
}

function stockBulkImportMessage(
  t: ReturnType<typeof createTranslator>,
  key: string,
  values: Record<string, string | number> = {}
) {
  return Object.entries(values).reduce(
    (message, [name, value]) => message.replaceAll(`{${name}}`, String(value)),
    t(key)
  );
}

export async function importStockBulkFile(
  file: File,
  products: StockInventoryRow[],
  catalogs: StockBulkImportCatalogs = {}
) {
  const t = createTranslator(catalogs.locale ?? "es");
  const sheet = await readSheet(file);
  if (sheet.length < 2) {
    return [];
  }
  const headers = sheet[0].map((header) => normalize(String(header ?? "")));
  const productIndex = buildStockBulkProductIndex(products);
  const familyReferences = buildStockBulkReferences(
    products,
    "familyId",
    "familyName",
    undefined,
    catalogs.families
  );
  const subfamilyReferences = buildStockBulkReferences(
    products,
    "subfamilyId",
    "subfamilyName",
    "familyId",
    catalogs.subfamilies?.map((subfamily) => ({
      id: subfamily.id,
      name: subfamily.name,
      parentId: subfamily.familyId
    }))
  );
  const taxReferences = buildStockBulkReferences(
    products,
    "taxId",
    "taxName",
    undefined,
    catalogs.taxes
  );
  const productIdColumn = findExcelColumn(headers, ["producto id", "product id"]);
  const identifierColumns = findExcelColumns(headers, [
    "codigo", "code", "codigo de barra", "barcode", "codigo barra 2", "barcode 2"
  ]);
  if (productIdColumn < 0 && identifierColumns.length === 0) {
    throw new Error(t("stock.bulkEdit.import.error.requiredIdentifier"));
  }

  const errors: string[] = [];
  const imported = sheet.slice(1).flatMap((cells, rowIndex) => {
    const excelRow = rowIndex + 2;
    if (cells.every((cell) => excelCellText(cell) === "")) {
      return [];
    }
    const product = resolveStockBulkProduct(
      cells,
      excelRow,
      productIdColumn,
      identifierColumns,
      products,
      productIndex,
      errors,
      t
    );
    if (!product) return [];

    const draft: Partial<StockInventoryRow> = { ...product };
    excelFields.forEach(({ keys, field }) => {
      const column = findExcelColumn(headers, keys);
      const value = column < 0 ? "" : excelCellText(cells[column]);
      if (value) {
        (draft as Record<string, unknown>)[field] = value;
      }
    });

    resolveStockBulkReference(
      "familia",
      excelRow,
      headers,
      cells,
      excelReferenceFields.family,
      familyReferences,
      errors,
      t
    )?.forEach(([field, value]) => {
      (draft as Record<string, unknown>)[field] = value;
    });

    const effectiveFamilyId = String(draft.familyId ?? product.familyId);
    resolveStockBulkReference(
      "subfamilia",
      excelRow,
      headers,
      cells,
      excelReferenceFields.subfamily,
      subfamilyReferences,
      errors,
      t,
      effectiveFamilyId
    )?.forEach(([field, value]) => {
      (draft as Record<string, unknown>)[field] = value;
    });

    resolveStockBulkReference(
      "impuesto",
      excelRow,
      headers,
      cells,
      excelReferenceFields.tax,
      taxReferences,
      errors,
      t
    )?.forEach(([field, value]) => {
      (draft as Record<string, unknown>)[field] = value;
    });

    importStockBulkBoolean(headers, cells, ["impuestos incluidos", "taxes included"], "taxesIncluded", draft, excelRow, errors, t);
    importStockBulkBoolean(headers, cells, ["oferta activa", "offer active"], "offerActive", draft, excelRow, errors, t);

    return [{
      id: `bulk-import-${Date.now()}-${rowIndex}`,
      selected: false,
      query: product.code || product.barcode || product.name,
      product,
      draft
    } satisfies StockBulkEditRowData];
  });

  if (errors.length > 0) {
    const visibleErrors = errors.slice(0, 10);
    const remainder = errors.length - visibleErrors.length;
    throw new Error([
      t("stock.bulkEdit.import.error.prefix"),
      ...visibleErrors,
      ...(remainder > 0 ? [stockBulkImportMessage(t, "stock.bulkEdit.import.error.remaining", { count: remainder })] : [])
    ].join("\n"));
  }
  return imported;
}

function buildStockBulkProductIndex(products: StockInventoryRow[]) {
  const index = new Map<string, StockInventoryRow[]>();
  products.forEach((product) => [product.code, product.barcode, product.barcode2]
    .map((value) => normalize(String(value ?? "")))
    .filter(Boolean)
    .forEach((identifier) => {
      const matches = index.get(identifier) ?? [];
      if (!matches.some((match) => match.productId === product.productId)) {
        matches.push(product);
      }
      index.set(identifier, matches);
    }));
  return index;
}

function resolveStockBulkProduct(
  cells: unknown[],
  row: number,
  productIdColumn: number,
  identifierColumns: number[],
  products: StockInventoryRow[],
  productIndex: Map<string, StockInventoryRow[]>,
  errors: string[],
  t: ReturnType<typeof createTranslator>
) {
  const productId = productIdColumn < 0 ? "" : excelCellText(cells[productIdColumn]);
  const productById = productId
    ? products.find((product) => normalize(product.productId) === normalize(productId))
    : undefined;
  if (productId && !productById) {
    errors.push(stockBulkImportMessage(t, "stock.bulkEdit.import.error.productIdMissing", { row, value: productId }));
    return undefined;
  }

  const identifiers = identifierColumns
    .map((column) => excelCellText(cells[column]))
    .filter(Boolean);
  const matchedProducts = new Map<string, StockInventoryRow>();
  identifiers.forEach((identifier) => {
    (productIndex.get(normalize(identifier)) ?? []).forEach((product) => {
      matchedProducts.set(product.productId, product);
    });
  });

  if (matchedProducts.size > 1) {
    errors.push(stockBulkImportMessage(t, "stock.bulkEdit.import.error.ambiguousIdentifiers", { row }));
    return undefined;
  }
  const productByIdentifier = matchedProducts.values().next().value as StockInventoryRow | undefined;
  if (productById && productByIdentifier && productById.productId !== productByIdentifier.productId) {
    errors.push(stockBulkImportMessage(t, "stock.bulkEdit.import.error.identifierMismatch", { row }));
    return undefined;
  }
  const product = productById ?? productByIdentifier;
  if (!product) {
    const reference = identifiers.join(" / ") || productId || t("stock.bulkEdit.import.error.withoutIdentifier");
    errors.push(stockBulkImportMessage(t, "stock.bulkEdit.import.error.productMissing", { row, value: reference }));
  }
  return product;
}

function buildStockBulkReferences(
  products: StockInventoryRow[],
  idField: "familyId" | "subfamilyId" | "taxId",
  nameField: "familyName" | "subfamilyName" | "taxName",
  parentField?: "familyId",
  catalog: Array<{ id: string; name: string; parentId?: string }> = []
) {
  const references = new Map<string, StockBulkNamedReference>();
  const addReference = (
    id: string | null | undefined,
    name: string | null | undefined,
    parentId?: string
  ) => {
    if (!id || !name) return;
    const key = `${normalize(parentId ?? "")}\u0000${normalize(id)}`;
    const existing = references.get(key);
    if (existing) {
      existing.normalizedNames.add(normalize(name));
      return;
    }
    references.set(key, {
      id,
      name,
      parentId,
      normalizedNames: new Set([normalize(name)])
    });
  };
  catalog.forEach((reference) => addReference(reference.id, reference.name, reference.parentId));
  products.forEach((product) => {
    const id = nullableText(product[idField]);
    const name = nullableText(product[nameField]);
    const parentId = parentField ? nullableText(product[parentField]) ?? undefined : undefined;
    addReference(id, name, parentId);
  });
  return Array.from(references.values());
}

function resolveStockBulkReference(
  label: "familia" | "subfamilia" | "impuesto",
  row: number,
  headers: string[],
  cells: unknown[],
  columns: { readonly id: readonly string[]; readonly name: readonly string[] },
  references: StockBulkNamedReference[],
  errors: string[],
  t: ReturnType<typeof createTranslator>,
  parentId?: string
): Array<[keyof StockInventoryRow, string]> | undefined {
  const idColumn = findExcelColumn(headers, columns.id);
  const nameColumn = findExcelColumn(headers, columns.name);
  const id = idColumn < 0 ? "" : excelCellText(cells[idColumn]);
  const name = nameColumn < 0 ? "" : excelCellText(cells[nameColumn]);
  if (!id && !name) return undefined;

  const parentReferences = parentId
    ? references.filter((reference) => normalize(reference.parentId ?? "") === normalize(parentId))
    : references;
  const matches = id
    ? parentReferences.filter((reference) => normalize(reference.id) === normalize(id))
    : parentReferences.filter((reference) => reference.normalizedNames.has(normalize(name)));
  if (matches.length === 0) {
    const value = id || name;
    const key = parentId && label === "subfamilia"
      ? "stock.bulkEdit.import.error.subfamilyMissingInFamily"
      : `stock.bulkEdit.import.error.referenceMissing.${label}`;
    errors.push(stockBulkImportMessage(t, key, { row, value }));
    return undefined;
  }
  if (matches.length > 1) {
    errors.push(stockBulkImportMessage(t, "stock.bulkEdit.import.error.referenceAmbiguous", {
      row,
      reference: t(`stock.bulkEdit.import.reference.${label}`),
      value: id || name
    }));
    return undefined;
  }
  const match = matches[0];
  if (name && !match.normalizedNames.has(normalize(name))) {
    errors.push(stockBulkImportMessage(t, "stock.bulkEdit.import.error.referenceMismatch", {
      row,
      reference: t(`stock.bulkEdit.import.reference.${label}`)
    }));
    return undefined;
  }
  const fields = label === "familia"
    ? (["familyId", "familyName"] as const)
    : label === "subfamilia"
      ? (["subfamilyId", "subfamilyName"] as const)
      : (["taxId", "taxName"] as const);
  return [[fields[0], match.id], [fields[1], match.name]];
}

function importStockBulkBoolean(
  headers: string[],
  cells: unknown[],
  keys: string[],
  field: "taxesIncluded" | "offerActive",
  draft: Partial<StockInventoryRow>,
  row: number,
  errors: string[],
  t: ReturnType<typeof createTranslator>
) {
  const column = findExcelColumn(headers, keys);
  if (column < 0 || cells[column] === null || cells[column] === undefined || excelCellText(cells[column]) === "") {
    return;
  }
  const normalized = normalize(excelCellText(cells[column]));
  if (["true", "yes", "si", "1", "common.yes"].includes(normalized)) {
    draft[field] = "common.yes";
  } else if (["false", "no", "0", "common.no"].includes(normalized)) {
    draft[field] = "common.no";
  } else {
    errors.push(stockBulkImportMessage(t, "stock.bulkEdit.import.error.boolean", {
      row,
      field: t(`stock.bulkEdit.import.field.${field}`)
    }));
  }
}

function findExcelColumns(headers: string[], keys: readonly string[]) {
  return headers.flatMap((header, index) => keys.includes(header) ? [index] : []);
}

function findExcelColumn(headers: string[], keys: readonly string[]) {
  return headers.findIndex((header) => keys.includes(header));
}

function excelCellText(value: unknown) {
  if (value instanceof Date && Number.isFinite(value.getTime())) {
    const year = value.getFullYear();
    const month = String(value.getMonth() + 1).padStart(2, "0");
    const day = String(value.getDate()).padStart(2, "0");
    return `${year}-${month}-${day}`;
  }
  return text(value).trim();
}

function normalizedPriceUse(value: unknown) {
  const mode = String(value ?? "NORMAL");
  return ["NORMAL", "MEMBER_PRICE", "OFFER_PRICE", "OFFER_DISCOUNT"].includes(mode) ? mode : "NORMAL";
}

function requiredText(value: unknown, field: string) {
  const normalized = nullableText(value);
  if (!normalized) {
    throw new Error(`Campo obligatorio: ${field}`);
  }
  return normalized;
}

function nullableText(value: unknown) {
  const normalized = text(value).trim();
  return !normalized || normalized === "-" ? null : normalized;
}

function numberValue(value: unknown) {
  const number = Number(text(value).replace(",", "."));
  return Number.isFinite(number) ? number : 0;
}

function parsedNumber(value: unknown) {
  const normalized = nullableText(value);
  if (normalized === null) {
    return null;
  }
  const number = Number(normalized.replace(",", "."));
  return Number.isFinite(number) ? number : null;
}

function nullableNumber(value: unknown) {
  const normalized = nullableText(value);
  if (normalized === null) {
    return null;
  }
  const number = Number(normalized.replace(",", "."));
  return Number.isFinite(number) ? number : null;
}

function booleanValue(value: unknown) {
  return value === true || ["true", "yes", "si", "common.yes"].includes(normalize(text(value)));
}

function isIsoDate(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) {
    return false;
  }
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return date.getFullYear() === Number(match[1])
    && date.getMonth() === Number(match[2]) - 1
    && date.getDate() === Number(match[3]);
}

function text(value: unknown) {
  return value === null || value === undefined ? "" : String(value);
}

function normalize(value: string) {
  return value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").trim().toLowerCase().replace(/\s+/g, " ");
}
