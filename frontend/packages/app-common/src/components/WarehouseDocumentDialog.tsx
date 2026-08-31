import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import { enterNavigationIntent, focusRelativeEnterTarget } from "./keyboardNavigation";
import { ErpSelect } from "./ErpSelect";
import {
  SharedExcelImportDialog,
  type SharedExcelImportAcceptedRow,
  type SharedExcelImportMetadata
} from "./SharedExcelImportDialog";
import { useOutsidePointerDown } from "./useOutsidePointerDown";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { visibleTableColumns } from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";
import {
  applyProductRequiredDefaults,
  buildCreateProductRequest,
  createDefaultProductForm,
  ProductCreateDialog,
  type ProductCreateFormState,
  type ProductCreateResponse
} from "./ProductCreateDialog";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import type { ExcelImportClassifiedRow, ExcelImportProductDraft, ExcelImportProductIdentity } from "./excelImport";
import { SaleProductSearchDialog, type SaleProductSearchOption } from "./SaleProductSearchDialog";
import { WarehouseSupplierDialog } from "./WarehouseSupplierDialog";
import {
  type WarehouseDocumentLineDraft,
  type WarehouseImportProduct
} from "./warehouseDocumentImport";
import {
  buildWarehouseA4Document,
  hasDesktopHardwareBridge,
  openWarehouseDocumentPreview,
  printWarehouseA4Document
} from "../warehouse/warehouseDocumentPrinting";

export type WarehouseDocumentMode = "input" | "output";
export type WarehouseInputDocumentType = "ENTRADA_ALMACEN" | "ALBARAN_ENTRADA" | "FACTURA_ENTRADA";
export type WarehouseInputPriceSource = "PURCHASE" | "SALE" | "MEMBER" | "WHOLESALE" | "OFFER";

export type WarehouseOption = {
  id: string;
  name?: string | null;
  nombre?: string | null;
  active?: boolean;
};

export type WarehouseCustomerOption = {
  id: string;
  fiscalName?: string | null;
  nombreFiscal?: string | null;
  documentNumber?: string | null;
  numeroDocumento?: string | null;
};

export type WarehouseSupplierOption = {
  id: string;
  supplierId?: string | null;
  legalName?: string | null;
  razonSocial?: string | null;
  tradeName?: string | null;
  documentType?: string | null;
  documentNumber?: string | null;
  numeroDocumento?: string | null;
  phone?: string | null;
  email?: string | null;
  active?: boolean | null;
};

export type WarehouseDocumentView = {
  id: string;
  number?: string | null;
  warehouseId: string;
  supplierId?: string | null;
  documentType?: WarehouseInputDocumentType;
  date: string;
  externalNumber?: string | null;
  origin?: string | null;
  destination?: string | null;
  concept?: string | null;
  priceSource?: WarehouseInputPriceSource;
  globalDiscount?: number | string | null;
  subtotal?: number | string | null;
  total?: number | string | null;
  sourceDeliveryNoteIds?: string[];
  status: string;
  lines: Array<{
    productId: string;
    productName?: string | null;
    quantity: number;
    purchaseUnitPrice?: number | string | null;
    discount?: number | string | null;
    priceOverridden?: boolean;
  }>;
};

type WarehouseDocumentDialogProps = {
  mode: WarehouseDocumentMode;
  open: boolean;
  app?: AppKind;
  username?: string;
  accessToken?: string;
  session?: UserSession;
  title?: string;
  documentType?: WarehouseInputDocumentType;
  canConfirm?: boolean;
  locale?: LocaleCode;
  token?: string;
  products: WarehouseImportProduct[];
  warehouses: WarehouseOption[];
  customers: WarehouseCustomerOption[];
  suppliers: WarehouseSupplierOption[];
  document?: WarehouseDocumentView | null;
  defaultWarehouseId?: string;
  terminalContext?: TerminalContext;
  onClose: () => void;
  onSaved?: (document: WarehouseDocumentView) => void;
  onConfirmed: (document?: WarehouseDocumentView) => void;
};

export type WarehouseDocumentDraft = {
  warehouseId: string;
  partnerId: string;
  partnerText: string;
  date: string;
  externalNumber?: string;
  concept: string;
  documentType?: WarehouseInputDocumentType;
  priceSource?: WarehouseInputPriceSource;
  globalDiscount?: string;
  sourceDeliveryNoteIds?: string[];
  lines: WarehouseDocumentLineDraft[];
  excelImport?: SharedExcelImportMetadata | null;
};

const warehouseDocumentColumns = [
  { key: "code", labelKey: "warehouseDocument.column.code", defaultWidth: 180 },
  { key: "barcode", labelKey: "warehouseDocument.column.barcode", defaultWidth: 200 },
  { key: "name", labelKey: "warehouseDocument.column.name", defaultWidth: 260 },
  { key: "discount", labelKey: "warehouseDocument.column.discount", defaultWidth: 150 },
  { key: "price", labelKey: "warehouseDocument.column.price", defaultWidth: 120 },
  { key: "quantity", labelKey: "warehouseDocument.quantity", defaultWidth: 170 },
  { key: "total", labelKey: "warehouseDocument.column.total", defaultWidth: 160 }
] as const;

type WarehouseDocumentColumnKey = typeof warehouseDocumentColumns[number]["key"];
const printStartupGuardMs = 350;

export function warehouseDocumentPath(mode: WarehouseDocumentMode) {
  return mode === "input" ? "/warehouse-inputs" : "/warehouse-outputs";
}

export function warehouseDocumentRequestErrorMessage(
  error: unknown,
  fallback: string,
  messages: { integrityConflict: string; stateConflict: string }
) {
  if (error instanceof TypeError || (error instanceof Error && error.message === "Failed to write request")) {
    return fallback;
  }
  if (error instanceof ApiError) {
    const code = typeof error.problem?.code === "string" ? error.problem.code : "";
    if (error.status === 409 && code === "DATA_INTEGRITY_CONFLICT") {
      return messages.integrityConflict;
    }
    if (error.status === 409 && code === "STATE_CONFLICT") {
      return messages.stateConflict;
    }
  }
  return fallback;
}

export function canConfirmWarehouseDocument(draft: Pick<WarehouseDocumentDraft, "warehouseId" | "partnerId" | "partnerText" | "documentType" | "lines">) {
  return Boolean(draft.warehouseId)
    && (draft.documentType !== "FACTURA_ENTRADA" || Boolean(draft.partnerId))
    && draft.lines.length > 0
    && draft.lines.every((line) => line.valid
      && Number.isFinite(line.unitPrice ?? 0)
      && (line.unitPrice ?? 0) >= 0
      && parseDocumentDiscountPercent(line.discountPercent) >= 0);
}

export function buildWarehouseDocumentCommand(mode: WarehouseDocumentMode, draft: WarehouseDocumentDraft) {
  const inputLines = draft.lines
    .filter((line) => line.valid)
    .map((line) => ({
      productId: line.productId,
      quantity: line.quantity,
      unitPrice: line.unitPrice ?? 0,
      discount: parseDocumentDiscountPercent(line.discountPercent),
      priceOverridden: Boolean(line.priceOverridden),
      productName: line.productName ?? line.productLabel
    }));
  const lines = mode === "input"
    ? inputLines
    : inputLines.map(({ productId, quantity }) => ({ productId, quantity }));
  if (mode === "input") {
    return {
      warehouseId: draft.warehouseId,
      date: draft.date,
      supplierId: draft.partnerId || undefined,
      origin: draft.partnerText,
      externalNumber: draft.externalNumber?.trim() || undefined,
      concept: draft.concept,
      documentType: draft.documentType ?? "ENTRADA_ALMACEN",
      priceSource: draft.priceSource ?? "PURCHASE",
      globalDiscount: parseDocumentDiscountPercent(draft.globalDiscount),
      sourceDeliveryNoteIds: draft.sourceDeliveryNoteIds ?? [],
      lines,
      ...(draft.excelImport ? { excelImport: draft.excelImport } : {})
    };
  }
  return {
    warehouseId: draft.warehouseId,
    date: draft.date,
    destination: draft.partnerText,
    concept: draft.concept,
    lines,
    ...(draft.excelImport ? { excelImport: draft.excelImport } : {})
  };
}

export function createManualWarehouseDocumentLine(
  productId: string,
  quantity: number,
  products: WarehouseImportProduct[],
  rowNumber: number
): WarehouseDocumentLineDraft {
  const product = products.find((candidate) => candidate.id === productId);
  return createWarehouseDocumentLine(product, product?.code ?? product?.barcode ?? product?.name ?? "", quantity, rowNumber);
}

function createManualWarehouseDocumentLineByCode(
  code: string,
  quantity: number,
  products: WarehouseImportProduct[],
  rowNumber: number
): WarehouseDocumentLineDraft {
  const normalized = normalizeExcelOption(code);
  const product = products.find((candidate) => (
    normalizeExcelOption(candidate.code ?? "") === normalized
    || normalizeExcelOption(candidate.barcode ?? "") === normalized
    || normalizeExcelOption(candidate.reference ?? "") === normalized
  ));
  return createWarehouseDocumentLine(product, code, quantity, rowNumber);
}

function createWarehouseDocumentLine(
  product: WarehouseImportProduct | undefined,
  importedProduct: string,
  quantity: number,
  rowNumber: number,
  discountPercent = "0"
): WarehouseDocumentLineDraft {
  const valid = Boolean(product) && Number.isFinite(quantity) && quantity > 0;
  return {
    rowNumber,
    productId: product?.id ?? "",
    productLabel: product ? productLabel(product) : "",
    productName: product?.name ?? "",
    importedProduct: product?.code ?? product?.barcode ?? importedProduct,
    quantity,
    discountPercent,
    valid,
    errorKey: !product ? "warehouseDocument.error.productNotFound" : quantity <= 0 ? "warehouseDocument.error.invalidQuantity" : ""
  };
}

export function WarehouseDocumentDialog({
  mode,
  open,
  app = "venta",
  username = "",
  accessToken,
  session,
  title: titleOverride,
  documentType = "ENTRADA_ALMACEN",
  canConfirm = false,
  locale = "es",
  token,
  products,
  warehouses,
  customers,
  suppliers,
  document,
  defaultWarehouseId,
  terminalContext,
  onClose,
  onSaved,
  onConfirmed
}: WarehouseDocumentDialogProps) {
  const t = createTranslator(locale);
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const printingRef = useRef(false);
  const printGuardTimerRef = useRef<number | null>(null);
  const printActionMountedRef = useRef(true);
  const [documentId, setDocumentId] = useState("");
  const [documentNumber, setDocumentNumber] = useState("");
  const [documentStatus, setDocumentStatus] = useState("BORRADOR");
  const [warehouseId, setWarehouseId] = useState("");
  const [partnerId, setPartnerId] = useState("");
  const [partnerText, setPartnerText] = useState("");
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [externalNumber, setExternalNumber] = useState("");
  const [documentDiscountPercent, setDocumentDiscountPercent] = useState("0");
  const [concept, setConcept] = useState("");
  const [lines, setLines] = useState<WarehouseDocumentLineDraft[]>([]);
  const [selectedLineIndex, setSelectedLineIndex] = useState<number | null>(null);
  const [lineEditorIndex, setLineEditorIndex] = useState<number | null>(null);
  const [lineEditQuantity, setLineEditQuantity] = useState("1");
  const [lineEditName, setLineEditName] = useState("");
  const [lineEditPrice, setLineEditPrice] = useState("0");
  const [lineEditDiscount, setLineEditDiscount] = useState("0");
  const [lineEditorInitialField, setLineEditorInitialField] = useState<"quantity" | "name" | "price">("quantity");
  const [quickLineEditMode, setQuickLineEditMode] = useState<"name" | "price" | null>(null);
  const [quickLineEditIndex, setQuickLineEditIndex] = useState<number | null>(null);
  const [quickLineEditValue, setQuickLineEditValue] = useState("");
  const [pendingZeroPriceProduct, setPendingZeroPriceProduct] = useState<{
    product: WarehouseImportProduct;
    quantity: number;
  } | null>(null);
  const [productSearchOpen, setProductSearchOpen] = useState(false);
  const [productSearchQuery, setProductSearchQuery] = useState("");
  const [nextScanQuantity, setNextScanQuantity] = useState(1);
  const [nextScanMode, setNextScanMode] = useState<"UNIT" | "PACKAGE">("UNIT");
  const [supplierDialogOpen, setSupplierDialogOpen] = useState(false);
  const [localSuppliers, setLocalSuppliers] = useState<WarehouseSupplierOption[]>(suppliers);
  const [manualProductId, setManualProductId] = useState("");
  const [manualProductCode, setManualProductCode] = useState("");
  const [manualDiscountPercent, setManualDiscountPercent] = useState("0");
  const [manualQuantity, setManualQuantity] = useState("1");
  const [manualUnitPrice, setManualUnitPrice] = useState("0");
  const [manualPriceOverridden, setManualPriceOverridden] = useState(false);
  const [status, setStatus] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [printing, setPrinting] = useState(false);
  const [excelImportOpen, setExcelImportOpen] = useState(false);
  const [excelCreatedProducts, setExcelCreatedProducts] = useState<WarehouseImportProduct[]>([]);
  const [excelImportMetadata, setExcelImportMetadata] = useState<SharedExcelImportMetadata | null>(null);
  const [manualMissingRows, setManualMissingRows] = useState<ExcelImportClassifiedRow[]>([]);
  const [manualMissingIndex, setManualMissingIndex] = useState(0);
  const [fileMenuOpen, setFileMenuOpen] = useState(false);
  const [priceMenuOpen, setPriceMenuOpen] = useState(false);
  const [documentPriceMode, setDocumentPriceMode] = useState<WarehouseInputPriceSource>("PURCHASE");
  const [sourceDeliveryNoteIds, setSourceDeliveryNoteIds] = useState<string[]>([]);
  const [availableSourceDeliveryNotes, setAvailableSourceDeliveryNotes] = useState<WarehouseDocumentView[]>([]);
  const tableLayout = useTableLayoutPreference({
    app,
    username,
    accessToken,
    tableKey: mode === "input" ? "warehouse.inputs.lines" : "warehouse.outputs.lines",
    definitions: warehouseDocumentColumns
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const newLineCodeRef = useRef<HTMLInputElement | null>(null);
  const newLineDiscountRef = useRef<HTMLInputElement | null>(null);
  const newLineQuantityRef = useRef<HTMLInputElement | null>(null);
  const rowCodeRefs = useRef<Array<HTMLInputElement | null>>([]);
  const rowDiscountRefs = useRef<Array<HTMLInputElement | null>>([]);
  const rowQuantityRefs = useRef<Array<HTMLInputElement | null>>([]);
  const productSearchRef = useRef<HTMLInputElement | null>(null);
  const lineEditNameRef = useRef<HTMLInputElement | null>(null);
  const lineEditPriceRef = useRef<HTMLInputElement | null>(null);
  const quickLineEditInputRef = useRef<HTMLInputElement | null>(null);
  const fileMenuRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    printActionMountedRef.current = true;
    return () => {
      printActionMountedRef.current = false;
      if (printGuardTimerRef.current !== null) {
        window.clearTimeout(printGuardTimerRef.current);
        printGuardTimerRef.current = null;
      }
      printingRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (!open) {
      return;
    }
    const initialWarehouse = document?.warehouseId
      || defaultWarehouseId
      || warehouses.find((warehouse) => warehouse.active !== false)?.id
      || "";
    setDocumentId(document?.id ?? "");
    setDocumentNumber(document?.number ?? "");
    setDocumentStatus(document?.status ?? "BORRADOR");
    setWarehouseId(initialWarehouse);
    setPartnerId(document?.supplierId ?? "");
    setPartnerText(mode === "input" ? document?.origin ?? "" : document?.destination ?? "");
    setDate(document?.date ?? new Date().toISOString().slice(0, 10));
    setExternalNumber(document?.externalNumber ?? "");
    setDocumentDiscountPercent(String(document?.globalDiscount ?? "0"));
    setConcept(document?.concept ?? "");
    setLines((document?.lines ?? []).map((line, index) => {
      const draftLine = createManualWarehouseDocumentLine(line.productId, Number(line.quantity), products, index + 1);
      return {
        ...draftLine,
        productName: line.productName ?? draftLine.productName,
        unitPrice: line.purchaseUnitPrice == null ? undefined : Number(line.purchaseUnitPrice),
        discountPercent: String(line.discount ?? 0),
        priceOverridden: Boolean(line.priceOverridden)
      };
    }));
    setSelectedLineIndex(null);
    setLineEditorIndex(null);
    setQuickLineEditMode(null);
    setQuickLineEditIndex(null);
    setQuickLineEditValue("");
    setPendingZeroPriceProduct(null);
    setProductSearchOpen(false);
    setProductSearchQuery("");
    setNextScanQuantity(1);
    setNextScanMode("UNIT");
    setManualProductId("");
    setManualProductCode("");
    setManualDiscountPercent("0");
    setManualQuantity("1");
    setManualUnitPrice("0");
    setManualPriceOverridden(false);
    setStatus("");
    if (printGuardTimerRef.current !== null) {
      window.clearTimeout(printGuardTimerRef.current);
      printGuardTimerRef.current = null;
    }
    printingRef.current = false;
    setPrinting(false);
    setExcelImportOpen(false);
    setExcelCreatedProducts([]);
    setExcelImportMetadata(null);
    setManualMissingRows([]);
    setManualMissingIndex(0);
    setFileMenuOpen(false);
    setPriceMenuOpen(false);
    setDocumentPriceMode(document?.priceSource ?? (mode === "output" ? "SALE" : "PURCHASE"));
    setSourceDeliveryNoteIds(document?.sourceDeliveryNoteIds ?? []);
  }, [defaultWarehouseId, document, mode, open]);

  useEffect(() => {
    setLocalSuppliers(suppliers);
  }, [suppliers]);

  useEffect(() => {
    if (!open || documentType !== "FACTURA_ENTRADA" || !token) {
      setAvailableSourceDeliveryNotes([]);
      return;
    }
    let cancelled = false;
    void apiRequest<{ items?: WarehouseDocumentView[] }>("/warehouse-inputs?limit=500&type=ALBARAN_ENTRADA", { token })
      .then((page) => {
        if (!cancelled) setAvailableSourceDeliveryNotes((page.items ?? []).filter((item) => item.status === "CONFIRMADA"));
      })
      .catch(() => {
        if (!cancelled) setAvailableSourceDeliveryNotes([]);
      });
    return () => { cancelled = true; };
  }, [documentType, open, token]);

  useOutsidePointerDown(fileMenuOpen, fileMenuRef, () => {
    setFileMenuOpen(false);
    setPriceMenuOpen(false);
  });

  useEffect(() => {
    if (!open) {
      return;
    }
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || event.defaultPrevented) {
        return;
      }
      if (excelImportOpen || manualMissingRows.length > 0) {
        return;
      }
      event.preventDefault();
      onClose();
    };
    window.addEventListener("keydown", handleEscape);
    return () => window.removeEventListener("keydown", handleEscape);
  }, [excelImportOpen, manualMissingRows.length, onClose, open]);

  useEffect(() => {
    const linkedLines = documentType === "FACTURA_ENTRADA" && sourceDeliveryNoteIds.length > 0;
    if (!open || documentStatus !== "BORRADOR" || linkedLines) return;
    const frame = window.requestAnimationFrame(() => productSearchRef.current?.focus());
    return () => window.cancelAnimationFrame(frame);
  }, [documentStatus, documentType, open, sourceDeliveryNoteIds.length]);

  useEffect(() => {
    if (lineEditorIndex === null) return;
    const frame = window.requestAnimationFrame(() => {
      const target = lineEditorInitialField === "name"
        ? lineEditNameRef.current
        : lineEditorInitialField === "price"
          ? lineEditPriceRef.current
          : null;
      target?.focus();
      target?.select();
    });
    return () => window.cancelAnimationFrame(frame);
  }, [lineEditorIndex, lineEditorInitialField]);

  useEffect(() => {
    if (quickLineEditMode === null) return;
    const frame = window.requestAnimationFrame(() => {
      quickLineEditInputRef.current?.focus();
      quickLineEditInputRef.current?.select();
    });
    return () => window.cancelAnimationFrame(frame);
  }, [quickLineEditMode, quickLineEditIndex, pendingZeroPriceProduct]);

  if (!open) {
    return null;
  }

  const title = titleOverride ?? t(mode === "input" ? "stock.nav.inputWarehouse" : "stock.nav.outputWarehouse");
  const partnerLabel = t(mode === "input" ? "warehouseDocument.supplier" : "warehouseDocument.customer");
  const partnerOptions = mode === "input" ? localSuppliers : customers;
  const draft = {
    warehouseId, partnerId, partnerText, date, externalNumber, concept, lines,
    documentType, priceSource: documentPriceMode, globalDiscount: documentDiscountPercent,
    sourceDeliveryNoteIds, excelImport: excelImportMetadata
  };
  const readOnly = documentStatus !== "BORRADOR";
  const canSaveDraft = canConfirmWarehouseDocument(draft) && !submitting && Boolean(token) && !readOnly;
  const canSubmitConfirmation = canConfirm && canSaveDraft;
  const isEditing = Boolean(documentId);
  const linkedLinesLocked = documentType === "FACTURA_ENTRADA" && sourceDeliveryNoteIds.length > 0;
  const importProducts = [...products, ...excelCreatedProducts];
  const manualProduct = importProducts.find((product) => product.id === manualProductId);
  const manualProductPrice = decimalDocumentNumber(manualUnitPrice || documentProductPrice(manualProduct, documentPriceMode));
  const documentTypeLabel = title;
  const totalUnits = lines.reduce((total, line) => total + (Number.isFinite(line.quantity) ? line.quantity : 0), 0);
  const selectedPartner = partnerId ? partnerOptions.find((option) => option.id === partnerId) : null;
  const productSearchProducts: Array<SaleProductSearchOption> = importProducts.map((product) => ({
    id: product.id,
    imageId: product.imageId,
    code: product.code,
    barcode: product.barcode,
    barcode2: product.barcode2,
    name: product.name,
    productType: product.productType,
    salePrice: documentProductPrice(product, documentPriceMode)
  }));
  const documentSubtotal = lines.reduce((total, line) => {
    const product = importProducts.find((candidate) => candidate.id === line.productId);
    return total + documentLineTotal(line.unitPrice ?? documentProductPrice(product, documentPriceMode), line.quantity, line.discountPercent ?? "0");
  }, 0);
  const documentTotal = documentTotalAfterDiscount(documentSubtotal, documentDiscountPercent);

  function importAcceptedExcelRows(rows: SharedExcelImportAcceptedRow[], metadata: SharedExcelImportMetadata) {
    const sourceKey = priceSourceDraftKey(documentPriceMode);
    const nextLines = rows.map((row, index) => {
      const line = createManualWarehouseDocumentLine(row.product?.id ?? "", row.quantity, importProducts, index + 1);
      const importedPrice = decimalDocumentNumber(row.draft[sourceKey]);
      const hasImportedPrice = String(row.draft[sourceKey] ?? "").trim() !== "";
      const product = importProducts.find((candidate) => candidate.id === row.product?.id);
      line.unitPrice = hasImportedPrice ? importedPrice : documentProductPrice(product, documentPriceMode);
      line.priceOverridden = hasImportedPrice;
      line.discountPercent = String(row.draft.purchaseDiscountPercent ?? "0");
      return line;
    });
    setLines(nextLines);
              setExcelImportMetadata(metadata);
    setStatus(t("warehouseDocument.imported"));
    setExcelImportOpen(false);
  }

  async function addMissingProductsAuto(rows: ExcelImportClassifiedRow[]): Promise<ExcelImportProductIdentity[]> {
    if (!token) {
      setStatus(t("product.create.saveError"));
      return [];
    }
    setStatus(interpolateMessage(t("warehouseDocument.status.creatingProducts"), { count: rows.length }));
    const defaults = await loadProductCreateDefaults(token);
    const created: ExcelImportProductIdentity[] = [];
    for (const row of rows) {
      const form = applyProductRequiredDefaults(productFormFromExcelDraft(row.draft), defaults.families, defaults.taxes);
      const product = await apiRequest<ProductCreateResponse>("/products/management", {
        token,
        method: "POST",
        body: buildCreateProductRequest(form, { purchaseDiscountPercent: row.draft.purchaseDiscountPercent })
      });
      const createdProduct = {
        id: product.id,
        code: product.code ?? row.draft.code,
        barcode: row.draft.barcode,
        reference: row.draft.code,
        name: product.name ?? row.draft.name
      };
      setExcelCreatedProducts((current) => [...current, createdProduct]);
      created.push({ id: product.id, code: product.code ?? row.draft.code, barcode: row.draft.barcode });
    }
    setStatus(interpolateMessage(t("warehouseDocument.status.productsCreated"), { count: created.length }));
    return created;
  }

  function addMissingProductsManual(rows: ExcelImportClassifiedRow[]) {
    setManualMissingRows(rows);
    setManualMissingIndex(0);
    setStatus(interpolateMessage(t("warehouseDocument.status.productsPendingReview"), { count: rows.length }));
  }

  function closeManualMissingProduct() {
    setManualMissingRows([]);
    setManualMissingIndex(0);
  }

  function manualMissingProductCreated(product: ProductCreateResponse) {
    const row = manualMissingRows[manualMissingIndex];
    if (row) {
      setExcelCreatedProducts((current) => [...current, {
        id: product.id,
        code: product.code ?? row.draft.code,
        barcode: row.draft.barcode,
        reference: row.draft.code,
        name: product.name ?? row.draft.name
      }]);
    }
    const nextIndex = manualMissingIndex + 1;
    if (nextIndex >= manualMissingRows.length) {
      closeManualMissingProduct();
      setStatus(t("warehouseDocument.status.missingReviewed"));
      return;
    }
    setManualMissingIndex(nextIndex);
  }

  function addManualLine() {
    const quantity = Number(manualQuantity.replace(",", "."));
    const next = createManualWarehouseDocumentLine(manualProductId, quantity, importProducts, lines.length + 1);
    next.discountPercent = manualDiscountPercent;
    next.unitPrice = decimalDocumentNumber(manualUnitPrice);
    next.priceOverridden = manualPriceOverridden;
    if (!next.valid) {
      setStatus(t(next.errorKey));
      return;
    }
    setLines((current) => [...current, next]);
    setManualProductId("");
    setManualProductCode("");
    setManualDiscountPercent("0");
    setManualQuantity("1");
    setManualUnitPrice("0");
    setManualPriceOverridden(false);
    setStatus(t("warehouseDocument.lineAdded"));
  }

  function addManualLineFromTable() {
    const quantity = Number(manualQuantity.replace(",", "."));
    const next = manualProductId
      ? createManualWarehouseDocumentLine(manualProductId, quantity, importProducts, lines.length + 1)
      : createManualWarehouseDocumentLineByCode(manualProductCode, quantity, importProducts, lines.length + 1);
    next.discountPercent = manualDiscountPercent;
    next.unitPrice = decimalDocumentNumber(manualUnitPrice);
    next.priceOverridden = manualPriceOverridden;
    if (!next.valid) {
      setStatus(t(next.errorKey));
      return;
    }
    setLines((current) => [...current, next]);
    setManualProductId("");
    setManualProductCode("");
    setManualDiscountPercent("0");
    setManualQuantity("1");
    setManualUnitPrice("0");
    setManualPriceOverridden(false);
    setStatus(t("warehouseDocument.lineAdded"));
    window.requestAnimationFrame(() => newLineCodeRef.current?.focus());
  }

  function selectProductByCode(code: string) {
    setManualProductCode(code);
    const normalized = normalizeExcelOption(code);
    const product = importProducts.find((candidate) => (
      normalizeExcelOption(candidate.code ?? "") === normalized
      || normalizeExcelOption(candidate.barcode ?? "") === normalized
      || normalizeExcelOption(candidate.reference ?? "") === normalized
    ));
    setManualProductId(product?.id ?? "");
    setManualUnitPrice(String(documentProductPrice(product, documentPriceMode)));
    setManualPriceOverridden(false);
    return product;
  }

  function selectDocumentPriceSource(source: WarehouseInputPriceSource) {
    setDocumentPriceMode(source);
    setLines((current) => current.map((line) => {
      if (line.priceOverridden) return line;
      const product = importProducts.find((candidate) => candidate.id === line.productId);
      return { ...line, unitPrice: documentProductPrice(product, source) };
    }));
    const nextManualPrice = documentProductPrice(manualProduct, source);
    setManualUnitPrice(String(nextManualPrice));
    setManualPriceOverridden(false);
    setPriceMenuOpen(false);
    setFileMenuOpen(false);
  }

  function selectSourceDeliveryNotes(ids: string[]) {
    setSourceDeliveryNoteIds(ids);
    if (ids.length === 0) return;
    const selected = availableSourceDeliveryNotes.filter((candidate) => ids.includes(candidate.id));
    if (selected.length === 0) return;
    setWarehouseId(selected[0].warehouseId);
    const supplier = selected.map((candidate) => candidate.supplierId).find(Boolean);
    if (supplier) setPartnerId(supplier);
    const quantities = new Map<string, number>();
    selected.forEach((candidate) => candidate.lines.forEach((line) => {
      quantities.set(line.productId, (quantities.get(line.productId) ?? 0) + Number(line.quantity));
    }));
    setLines(Array.from(quantities.entries()).map(([productId, quantity], index) => {
      const line = createManualWarehouseDocumentLine(productId, quantity, importProducts, index + 1);
      const product = importProducts.find((candidate) => candidate.id === productId);
      line.unitPrice = documentProductPrice(product, documentPriceMode);
      line.priceOverridden = false;
      return line;
    }));
  }

  function openPartnerList() {
    setFileMenuOpen(false);
    if (mode === "input") {
      setSupplierDialogOpen(true);
      return;
    }
    const trigger = dialogRef.current?.querySelector<HTMLElement>("[data-warehouse-partner-trigger]");
    trigger?.focus();
    setStatus(t("warehouseDocument.status.customerList"));
  }

  function clearProductSearch() {
    setProductSearchQuery("");
    window.requestAnimationFrame(() => productSearchRef.current?.focus());
  }

  function focusProductSearch() {
    if (!open || readOnly || linkedLinesLocked || productSearchOpen || supplierDialogOpen
        || lineEditorIndex !== null || quickLineEditMode !== null || excelImportOpen
        || manualMissingRows.length > 0) return;
    window.requestAnimationFrame(() => productSearchRef.current?.focus());
  }

  function productByIdentifier(identifier: string) {
    const normalized = normalizeExcelOption(identifier);
    if (!normalized) return undefined;
    return importProducts.find((product) => [
      product.code, product.barcode, product.barcode2, product.reference
    ].some((value) => normalizeExcelOption(value ?? "") === normalized));
  }

  function commitProductToDocument(
    product: WarehouseImportProduct,
    quantity: number,
    unitPrice: number,
    priceOverridden: boolean
  ) {
    const existingIndex = lines.findIndex((line) => (
      line.productId === product.id
      && decimalDocumentNumber(line.unitPrice ?? unitPrice) === unitPrice
      && parseDocumentDiscountPercent(line.discountPercent) === 0
    ));
    if (existingIndex >= 0) {
      setLines((current) => current.map((line, index) => (
        index === existingIndex ? { ...line, quantity: line.quantity + quantity } : line
      )));
      setSelectedLineIndex(existingIndex);
    } else {
      const next = createManualWarehouseDocumentLine(product.id, quantity, importProducts, lines.length + 1);
      next.unitPrice = unitPrice;
      next.priceOverridden = priceOverridden;
      next.discountPercent = "0";
      setLines((current) => [...current, next]);
      setSelectedLineIndex(lines.length);
    }
    setNextScanQuantity(1);
    setNextScanMode("UNIT");
    setProductSearchOpen(false);
    setStatus(t("warehouseDocument.lineAdded"));
    clearProductSearch();
  }

  function addProductToDocument(product: WarehouseImportProduct) {
    if (readOnly || linkedLinesLocked) return;
    const packageQuantity = decimalDocumentNumber(product.packageQuantity ?? 1);
    const quantity = nextScanMode === "PACKAGE"
      ? nextScanQuantity * (packageQuantity > 0 ? packageQuantity : 1)
      : nextScanQuantity;
    const sourcePrice = documentProductPrice(product, documentPriceMode);
    const reusableLine = sourcePrice === 0
      ? lines.find((line) => line.productId === product.id
        && decimalDocumentNumber(line.unitPrice ?? 0) > 0
        && parseDocumentDiscountPercent(line.discountPercent) === 0)
      : undefined;
    if (mode === "input" && sourcePrice === 0 && !reusableLine) {
      setPendingZeroPriceProduct({ product, quantity });
      setQuickLineEditIndex(null);
      setQuickLineEditValue("");
      setQuickLineEditMode("price");
      setProductSearchOpen(false);
      return;
    }
    const unitPrice = reusableLine?.unitPrice ?? sourcePrice;
    commitProductToDocument(product, quantity, decimalDocumentNumber(unitPrice), Boolean(reusableLine?.priceOverridden));
  }

  function submitProductSearch() {
    if (linkedLinesLocked || readOnly) return;
    const exact = productByIdentifier(productSearchQuery);
    if (exact) {
      addProductToDocument(exact);
      return;
    }
    setProductSearchOpen(true);
  }

  function prepareNextProduct(modeToPrepare: "UNIT" | "PACKAGE") {
    const operand = decimalDocumentNumber(productSearchQuery);
    if (operand <= 0) {
      setStatus(t("warehouseDocument.error.invalidOperand"));
      return;
    }
    setNextScanQuantity(operand);
    setNextScanMode(modeToPrepare);
    clearProductSearch();
  }

  function selectedLineOrStatus() {
    if (selectedLineIndex == null || !lines[selectedLineIndex]) {
      setStatus(t("warehouseDocument.error.noSelectedLine"));
      return null;
    }
    return lines[selectedLineIndex];
  }

  function applyQuantityShortcut(operation: "set" | "add" | "subtract") {
    const line = selectedLineOrStatus();
    const operand = decimalDocumentNumber(productSearchQuery);
    if (!line || operand < 0 || (operation !== "subtract" && operand <= 0)) {
      if (line) setStatus(t("warehouseDocument.error.invalidOperand"));
      return;
    }
    const nextQuantity = operation === "set"
      ? operand
      : operation === "add" ? line.quantity + operand : line.quantity - operand;
    if (nextQuantity < 0) {
      setStatus(t("warehouseDocument.error.invalidQuantity"));
      return;
    }
    if (nextQuantity === 0) {
      removeLine(selectedLineIndex!);
      setSelectedLineIndex(null);
    } else {
      setLines((current) => current.map((candidate, index) => (
        index === selectedLineIndex ? { ...candidate, quantity: nextQuantity } : candidate
      )));
    }
    clearProductSearch();
  }

  function applyDiscountShortcut(global: boolean) {
    const operand = decimalDocumentNumber(productSearchQuery);
    if (operand < 0 || operand > 100) {
      setStatus(t("warehouseDocument.error.invalidDiscount"));
      return;
    }
    if (global) {
      setDocumentDiscountPercent(String(operand));
    } else {
      const line = selectedLineOrStatus();
      if (!line) return;
      setLines((current) => current.map((candidate, index) => (
        index === selectedLineIndex ? { ...candidate, discountPercent: String(operand) } : candidate
      )));
    }
    clearProductSearch();
  }

  function applyDesiredLinePriceShortcut() {
    const line = selectedLineOrStatus();
    if (!line || selectedLineIndex === null) return;
    const product = importProducts.find((candidate) => candidate.id === line.productId);
    const currentPrice = decimalDocumentNumber(
      line.unitPrice ?? documentProductPrice(product, documentPriceMode)
    );
    const desiredPrice = decimalDocumentNumber(productSearchQuery);
    if (!productSearchQuery.trim() || desiredPrice < 0 || desiredPrice > currentPrice || currentPrice <= 0) {
      setStatus(t("warehouseDocument.error.invalidDesiredPrice"));
      return;
    }
    const discount = Math.round((1 - desiredPrice / currentPrice) * 10_000) / 100;
    setLines((current) => current.map((candidate, index) => (
      index === selectedLineIndex
        ? { ...candidate, discountPercent: String(discount) }
        : candidate
    )));
    setStatus("");
    clearProductSearch();
  }

  function openLineEditor(index: number, initialField: "quantity" | "name" | "price" = "quantity") {
    if (readOnly) return;
    const line = lines[index];
    if (!line) return;
    const product = importProducts.find((candidate) => candidate.id === line.productId);
    setSelectedLineIndex(index);
    setLineEditorIndex(index);
    setLineEditorInitialField(initialField);
    setLineEditQuantity(String(line.quantity));
    setLineEditName(line.productName ?? product?.name ?? line.productLabel);
    setLineEditPrice(String(line.unitPrice ?? documentProductPrice(product, documentPriceMode)));
    setLineEditDiscount(String(line.discountPercent ?? "0"));
  }

  function closeLineEditor() {
    setLineEditorIndex(null);
    window.requestAnimationFrame(() => productSearchRef.current?.focus());
  }

  function openQuickLineEditor(modeToOpen: "name" | "price") {
    if (readOnly || mode !== "input") return;
    const line = selectedLineOrStatus();
    if (!line || selectedLineIndex === null) return;
    const product = importProducts.find((candidate) => candidate.id === line.productId);
    setQuickLineEditIndex(selectedLineIndex);
    setPendingZeroPriceProduct(null);
    setQuickLineEditValue(modeToOpen === "name"
      ? line.productName ?? product?.name ?? line.productLabel
      : String(line.unitPrice ?? documentProductPrice(product, documentPriceMode)));
    setQuickLineEditMode(modeToOpen);
  }

  function closeQuickLineEditor() {
    setQuickLineEditMode(null);
    setQuickLineEditIndex(null);
    setQuickLineEditValue("");
    setPendingZeroPriceProduct(null);
    setNextScanQuantity(1);
    setNextScanMode("UNIT");
    clearProductSearch();
  }

  function saveQuickLineEditor(event: FormEvent) {
    event.preventDefault();
    if (quickLineEditMode === "name") {
      const productName = quickLineEditValue.trim();
      if (!productName) {
        setStatus(t("warehouseDocument.error.invalidName"));
        return;
      }
      if (quickLineEditIndex === null || !lines[quickLineEditIndex]) return;
      setLines((current) => current.map((line, index) => (
        index === quickLineEditIndex ? { ...line, productName } : line
      )));
      setStatus("");
      closeQuickLineEditor();
      return;
    }
    const price = decimalDocumentNumber(quickLineEditValue);
    if (price < 0 || (pendingZeroPriceProduct && price <= 0)) {
      setStatus(t(pendingZeroPriceProduct
        ? "warehouseDocument.error.priceRequired"
        : "warehouseDocument.error.invalidPrice"));
      return;
    }
    if (pendingZeroPriceProduct) {
      const pending = pendingZeroPriceProduct;
      setQuickLineEditMode(null);
      setQuickLineEditIndex(null);
      setQuickLineEditValue("");
      setPendingZeroPriceProduct(null);
      commitProductToDocument(pending.product, pending.quantity, price, true);
      return;
    }
    if (quickLineEditIndex === null || !lines[quickLineEditIndex]) return;
    setLines((current) => current.map((line, index) => (
      index === quickLineEditIndex
        ? { ...line, unitPrice: price, priceOverridden: true }
        : line
    )));
    setStatus("");
    closeQuickLineEditor();
  }

  function saveLineEditor(event?: FormEvent) {
    event?.preventDefault();
    if (lineEditorIndex == null) return;
    const originalLine = lines[lineEditorIndex];
    if (!originalLine) return;
    const quantity = linkedLinesLocked ? originalLine.quantity : decimalDocumentNumber(lineEditQuantity);
    const productName = lineEditName.trim();
    const price = decimalDocumentNumber(lineEditPrice);
    const discount = decimalDocumentNumber(lineEditDiscount);
    if (quantity <= 0) {
      setStatus(t("warehouseDocument.error.invalidQuantity"));
      return;
    }
    if (mode === "input" && !productName) {
      setStatus(t("warehouseDocument.error.invalidName"));
      return;
    }
    if (price < 0) {
      setStatus(t("warehouseDocument.error.invalidPrice"));
      return;
    }
    if (discount < 0 || discount > 100) {
      setStatus(t("warehouseDocument.error.invalidDiscount"));
      return;
    }
    setLines((current) => current.map((line, index) => (
      index === lineEditorIndex
        ? {
          ...line,
          quantity,
          productName: mode === "input" ? productName : line.productName,
          unitPrice: price,
          priceOverridden: true,
          discountPercent: String(discount)
        }
        : line
    )));
    closeLineEditor();
    setStatus("");
  }

  function clearAllLines() {
    setLines([]);
    setFileMenuOpen(false);
    setStatus(t("warehouseDocument.status.linesCleared"));
  }

  function clearAllDiscounts() {
    setDocumentDiscountPercent("0");
    setLines((current) => current.map((line) => ({ ...line, discountPercent: "0" })));
    setFileMenuOpen(false);
    setStatus(t("warehouseDocument.status.noDiscounts"));
  }

  function buildPrintRequest() {
    const invalidLine = lines.find((line) => (
      !line.valid || !importProducts.some((candidate) => candidate.id === line.productId)
    ));
    if (invalidLine) {
      setStatus(t(invalidLine.errorKey || "warehouseDocument.error.productNotFound"));
      return null;
    }

    const warehouse = warehouses.find((option) => option.id === warehouseId);
    const warehouseLabel = warehouse?.name ?? warehouse?.nombre ?? warehouseId;
    const discount = parseDocumentDiscountPercent(documentDiscountPercent);
    const printLines = lines.map((line) => {
      const product = importProducts.find((candidate) => candidate.id === line.productId)!;
      const unitPrice = line.unitPrice ?? documentProductPrice(product, documentPriceMode);
      return {
        code: product.code ?? product.barcode ?? product.reference ?? line.importedProduct ?? "",
        name: line.productName ?? product.name ?? line.productLabel,
        quantity: line.quantity,
        unitPrice,
        total: documentLineTotal(unitPrice, line.quantity, line.discountPercent ?? "0")
      };
    });

    return buildWarehouseA4Document({
      title: documentTypeLabel,
      locale,
      storeName: terminalContext?.storeName ?? "",
      terminalCode: terminalContext?.terminalCode ?? "",
      documentNumber,
      issuedAt: date,
      warehouse: warehouseLabel,
      partnerLabel,
      partner: selectedPartner ? partnerName(selectedPartner) : partnerText,
      discountPercent: discount,
      lines: printLines,
      subtotal: documentSubtotal,
      total: documentTotal,
      notes: concept.trim() ? [concept.trim()] : [],
      labels: {
        documentNumber: t("warehouseDocument.print.documentNumber"),
        warehouse: t("warehouseDocument.print.warehouse"),
        discount: t("warehouseDocument.print.discount"),
        partner: t("warehouseDocument.print.partner"),
        terminal: t("warehouseDocument.print.terminal"),
        description: t("warehouseDocument.print.description"),
        quantity: t("warehouseDocument.print.quantity"),
        unitPrice: t("warehouseDocument.print.unitPrice"),
        base: t("warehouseDocument.print.base"),
        tax: t("warehouseDocument.print.tax"),
        taxIncluded: t("warehouseDocument.print.taxIncluded"),
        yes: t("warehouseDocument.print.yes"),
        no: t("warehouseDocument.print.no"),
        mixed: t("warehouseDocument.print.mixed"),
        total: t("warehouseDocument.print.total"),
        print: t("warehouseDocument.print.print"),
        close: t("warehouseDocument.print.close"),
        notes: t("warehouseDocument.print.notes")
      }
    });
  }

  function beginPrintAction() {
    if (printingRef.current) {
      return false;
    }
    if (printGuardTimerRef.current !== null) {
      window.clearTimeout(printGuardTimerRef.current);
      printGuardTimerRef.current = null;
    }
    printingRef.current = true;
    setPrinting(true);
    return true;
  }

  function releasePrintAction() {
    if (printGuardTimerRef.current !== null) {
      window.clearTimeout(printGuardTimerRef.current);
      printGuardTimerRef.current = null;
    }
    printingRef.current = false;
    if (printActionMountedRef.current) {
      setPrinting(false);
    }
  }

  function releasePrintActionAfterStartup() {
    if (printGuardTimerRef.current !== null) {
      window.clearTimeout(printGuardTimerRef.current);
    }
    printGuardTimerRef.current = window.setTimeout(() => {
      printGuardTimerRef.current = null;
      printingRef.current = false;
      if (printActionMountedRef.current) {
        setPrinting(false);
      }
    }, printStartupGuardMs);
  }

  async function printDocument() {
    if (printingRef.current) {
      return;
    }
    setFileMenuOpen(false);
    const request = buildPrintRequest();
    if (!request) {
      return;
    }

    if (!beginPrintAction()) {
      return;
    }
    let deferReleaseForStartup = false;
    try {
      if (!hasDesktopHardwareBridge()) {
        const opened = openWarehouseDocumentPreview(request, { autoPrint: true });
        deferReleaseForStartup = true;
        if (printActionMountedRef.current) {
          setStatus(t(opened
            ? "warehouseDocument.status.printPreview"
            : "warehouseDocument.status.previewBlocked"));
        }
        return;
      }

      const result = await printWarehouseA4Document(request);
      if (printActionMountedRef.current) {
        setStatus(result.ok
          ? t("warehouseDocument.status.printed")
          : t("warehouseDocument.status.printError"));
      }
    } catch {
      if (printActionMountedRef.current) {
        setStatus(t("warehouseDocument.status.printError"));
      }
    } finally {
      if (deferReleaseForStartup) {
        releasePrintActionAfterStartup();
      } else {
        releasePrintAction();
      }
    }
  }

  function previewDocument() {
    if (printingRef.current) {
      return;
    }
    setFileMenuOpen(false);
    const request = buildPrintRequest();
    if (!request) {
      return;
    }
    if (!beginPrintAction()) {
      return;
    }
    try {
      const opened = openWarehouseDocumentPreview(request);
      if (printActionMountedRef.current) {
        setStatus(t(opened
          ? "warehouseDocument.status.printPreview"
          : "warehouseDocument.status.previewBlocked"));
      }
    } finally {
      releasePrintActionAfterStartup();
    }
  }

  function exportDocumentExcel() {
    setFileMenuOpen(false);
    const csv = [
      [
        t("sharedExcel.column.row"),
        t("sharedExcel.column.code"),
        t("warehouseDocument.column.name"),
        t("warehouseDocument.quantity"),
        t("sharedExcel.column.status")
      ].join(";"),
      ...lines.map((line, index) => [
        index + 1,
        csvCell(importProducts.find((product) => product.id === line.productId)?.code ?? line.importedProduct),
        csvCell(line.productName ?? line.productLabel),
        line.quantity,
        line.valid ? t("warehouseDocument.import.correct") : t(line.errorKey)
      ].join(";"))
    ].join("\n");
    const link = globalThis.document.createElement("a");
    link.href = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    link.download = `${documentTypeLabel.toLowerCase().replace(/\s+/g, "-")}.csv`;
    link.click();
    URL.revokeObjectURL(link.href);
    setStatus(t("warehouseDocument.status.exported"));
  }

  function updateLine(index: number, productId: string, quantity: number) {
    setLines((current) => current.map((line, lineIndex) => (
      lineIndex === index
        ? {
          ...createManualWarehouseDocumentLine(productId, quantity, importProducts, line.rowNumber),
          discountPercent: line.discountPercent ?? "0",
          unitPrice: line.unitPrice,
          priceOverridden: line.priceOverridden
        }
        : line
    )));
  }

  function updateLineCode(index: number, code: string) {
    setLines((current) => current.map((line, lineIndex) => (
      lineIndex === index
        ? {
          ...createManualWarehouseDocumentLineByCode(code, line.quantity, importProducts, line.rowNumber),
          discountPercent: line.discountPercent ?? "0",
          unitPrice: line.unitPrice,
          priceOverridden: line.priceOverridden
        }
        : line
    )));
  }

  function updateLineDiscount(index: number, discountPercent: string) {
    setLines((current) => current.map((line, lineIndex) => (
      lineIndex === index ? { ...line, discountPercent } : line
    )));
  }

  function updateLinePrice(index: number, unitPrice: string) {
    setLines((current) => current.map((line, lineIndex) => (
      lineIndex === index
        ? { ...line, unitPrice: decimalDocumentNumber(unitPrice), priceOverridden: true }
        : line
    )));
  }

  function removeLine(index: number) {
    setLines((current) => current.filter((_, lineIndex) => lineIndex !== index));
  }

  function focusNextDocumentRow(index: number) {
    const nextCode = rowCodeRefs.current[index + 1];
    if (nextCode) {
      nextCode.focus();
      nextCode.select();
      return;
    }
    newLineCodeRef.current?.focus();
    newLineCodeRef.current?.select();
  }

  function confirmLineQuantity(index: number, quantity: number) {
    if (quantity === 0) {
      removeLine(index);
      window.requestAnimationFrame(() => focusNextDocumentRow(index - 1));
      return;
    }
    focusNextDocumentRow(index);
  }

  function renderDocumentLineCell(
    columnKey: WarehouseDocumentColumnKey,
    line: WarehouseDocumentLineDraft,
    index: number
  ) {
    const lineProduct = importProducts.find((product) => product.id === line.productId);
    const price = line.unitPrice ?? documentProductPrice(lineProduct, documentPriceMode);
    const discountPercent = line.discountPercent ?? "0";

    switch (columnKey) {
      case "code":
        return <span className="product-name-text">{lineProduct?.code ?? line.importedProduct}</span>;
      case "barcode":
        return lineProduct?.barcode ?? "-";
      case "name":
        return <span className="product-name-text">{line.productName ?? lineProduct?.name ?? line.productLabel}</span>;
      case "discount":
        return formatDocumentDiscount(discountPercent);
      case "price":
        return formatDocumentAmount(price);
      case "quantity":
        return line.quantity;
      case "total":
        return formatDocumentAmount(documentLineTotal(price, line.quantity, discountPercent));
    }
  }

  function renderNewDocumentLineCell(columnKey: WarehouseDocumentColumnKey) {
    switch (columnKey) {
      case "code":
        return (
          <input
            ref={newLineCodeRef}
            value={manualProductCode}
            onChange={(event) => selectProductByCode(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.stopPropagation();
                event.preventDefault();
                newLineDiscountRef.current?.focus();
                newLineDiscountRef.current?.select();
              }
            }}
          />
        );
      case "barcode":
        return manualProduct?.barcode ?? "-";
      case "name":
        return <span className="product-name-text">{manualProduct?.name ?? ""}</span>;
      case "discount":
        return (
          <input
            ref={newLineDiscountRef}
            type="number"
            min="0"
            max="100"
            step="0.01"
            value={manualDiscountPercent}
            onChange={(event) => setManualDiscountPercent(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.stopPropagation();
                event.preventDefault();
                newLineQuantityRef.current?.focus();
                newLineQuantityRef.current?.select();
              }
            }}
          />
        );
      case "price":
        return (
          <input
            type="number"
            min="0"
            step="0.01"
            value={manualUnitPrice || manualProductPrice}
            onChange={(event) => {
              setManualUnitPrice(event.target.value);
              setManualPriceOverridden(true);
            }}
          />
        );
      case "quantity":
        return (
          <input
            ref={newLineQuantityRef}
            data-warehouse-add-line
            type="number"
            min="0"
            step="1"
            value={manualQuantity}
            onChange={(event) => setManualQuantity(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.stopPropagation();
                event.preventDefault();
                addManualLineFromTable();
              }
            }}
          />
        );
      case "total":
        return formatDocumentAmount(documentLineTotal(
          manualProductPrice,
          Number(manualQuantity || 0),
          manualDiscountPercent
        ));
    }
  }

  async function persistDraft() {
    if (!canSaveDraft || !token || readOnly) {
      return null;
    }
    const basePath = warehouseDocumentPath(mode);
    const saved = await apiRequest<WarehouseDocumentView>(documentId ? `${basePath}/${documentId}` : basePath, {
      token,
      method: documentId ? "PUT" : "POST",
      body: buildWarehouseDocumentCommand(mode, draft)
    });
    setDocumentId(saved.id);
    setDocumentNumber(saved.number ?? documentNumber);
    setDocumentStatus(saved.status ?? "BORRADOR");
    onSaved?.(saved);
    return saved;
  }

  async function saveDraft() {
    if (submitting) {
      return;
    }
    setSubmitting(true);
    setStatus(t("warehouseDocument.saving"));
    try {
      await persistDraft();
      setStatus(t("warehouseDocument.saved"));
    } catch (error) {
      setStatus(warehouseDocumentRequestErrorMessage(error, t("warehouseDocument.saveError"), {
        integrityConflict: t("warehouseDocument.error.integrityConflict"),
        stateConflict: t("warehouseDocument.error.stateConflict")
      }));
    } finally {
      setSubmitting(false);
    }
  }

  async function confirmDocument() {
    if (!canSubmitConfirmation) {
      return;
    }
    setSubmitting(true);
    setStatus(t("warehouseDocument.confirming"));
    try {
      const saved = await persistDraft();
      const id = saved?.id ?? documentId;
      if (!id || !token) {
        return;
      }
      const confirmed = await apiRequest<WarehouseDocumentView>(`${warehouseDocumentPath(mode)}/${id}/confirm`, { token, method: "POST" });
      setDocumentNumber(confirmed.number ?? saved?.number ?? documentNumber);
      setDocumentStatus(confirmed.status ?? "CONFIRMADA");
      setStatus(t("warehouseDocument.confirmed"));
      onConfirmed(confirmed);
    } catch (error) {
      setStatus(warehouseDocumentRequestErrorMessage(error, t("warehouseDocument.confirmError"), {
        integrityConflict: t("warehouseDocument.error.integrityConflict"),
        stateConflict: t("warehouseDocument.error.stateConflict")
      }));
    } finally {
      setSubmitting(false);
    }
  }

  const warehouseControlSelector = ".warehouse-document-dialog input:not([type='file']):not(:disabled), .warehouse-document-dialog .erp-select__trigger:not(:disabled)";
  function moveFromActiveControl(intent: "next" | "previous") {
    const current = globalThis.document.activeElement;
    if (!(current instanceof HTMLElement)) return false;
    return focusRelativeEnterTarget(dialogRef.current, current, intent, warehouseControlSelector);
  }

  return (
    <div
      ref={dialogRef}
      className="warehouse-document-overlay"
      role="dialog"
      aria-modal="true"
      aria-labelledby="warehouse-document-title"
      onKeyDown={(event) => {
        const target = event.target as HTMLElement;
        const isProductSearch = target === productSearchRef.current;
        const isTextControl = target.matches("input, textarea, select");
        if (event.key === "Escape") {
          event.preventDefault();
          onClose();
        } else if (event.key === "End" && mode === "input" && !readOnly) {
          event.preventDefault();
          event.stopPropagation();
          openPartnerList();
        } else if (event.key === "Delete" && !readOnly && !isTextControl) {
          event.preventDefault();
          setProductSearchOpen(true);
        } else if (!readOnly && mode === "input" && (isProductSearch || !isTextControl) && event.key === "Home") {
          event.preventDefault();
          openQuickLineEditor("name");
        } else if (!readOnly && mode === "input" && (isProductSearch || !isTextControl)
            && event.ctrlKey && event.key === "PageUp") {
          event.preventDefault();
          openQuickLineEditor("price");
        } else if (!readOnly && mode === "input" && (isProductSearch || !isTextControl)
            && !event.ctrlKey && event.key === "PageUp") {
          event.preventDefault();
          applyDesiredLinePriceShortcut();
        } else if (!readOnly && (isProductSearch || !isTextControl) && event.key === "Pause") {
          event.preventDefault();
          applyQuantityShortcut("set");
        } else if (!readOnly && (isProductSearch || !isTextControl) && event.ctrlKey && event.key === "+") {
          event.preventDefault();
          applyQuantityShortcut("add");
        } else if (!readOnly && (isProductSearch || !isTextControl) && event.ctrlKey && event.key === "-") {
          event.preventDefault();
          applyQuantityShortcut("subtract");
        } else if (!readOnly && (isProductSearch || !isTextControl) && event.ctrlKey && event.shiftKey && event.key.toLocaleLowerCase() === "d") {
          event.preventDefault();
          clearAllDiscounts();
        } else if (!readOnly && (isProductSearch || !isTextControl) && event.ctrlKey && event.key === "/") {
          event.preventDefault();
          applyDiscountShortcut(true);
        } else if (!readOnly && (isProductSearch || !isTextControl) && !event.ctrlKey && event.key === "/") {
          event.preventDefault();
          applyDiscountShortcut(false);
        } else if (!readOnly && (isProductSearch || !isTextControl) && !event.ctrlKey && event.key === "+") {
          event.preventDefault();
          prepareNextProduct("UNIT");
        } else if (!readOnly && (isProductSearch || !isTextControl) && !event.ctrlKey && event.key === "*") {
          event.preventDefault();
          prepareNextProduct("PACKAGE");
        } else if (event.key === "F9" && canSaveDraft) {
          event.preventDefault();
          void saveDraft();
        } else if (event.ctrlKey && event.key.toLocaleLowerCase() === "s") {
          event.preventDefault();
          void saveDraft();
        } else if (event.key === "F10" && canSubmitConfirmation) {
          event.preventDefault();
          void confirmDocument();
        } else {
          const intent = enterNavigationIntent(event.key, {
            shiftKey: event.shiftKey,
            ctrlKey: event.ctrlKey,
            altKey: event.altKey,
            metaKey: event.metaKey,
            isComposing: event.nativeEvent.isComposing
          });
          const target = event.target as HTMLElement;
          if (!intent || !target.matches("input:not([type='file'])")) return;
          event.preventDefault();
          if (intent === "next" && target.matches("[data-warehouse-add-line]")) {
            addManualLineFromTable();
            window.requestAnimationFrame(() => event.currentTarget
              .querySelector<HTMLElement>("#warehouse-manual-product")?.focus());
            return;
          }
          focusRelativeEnterTarget(
            event.currentTarget,
            target,
            intent,
            warehouseControlSelector
          );
        }
      }}
      onBlurCapture={(event) => {
        const nextTarget = event.relatedTarget;
        if (nextTarget instanceof Node && event.currentTarget.contains(nextTarget)) return;
        window.setTimeout(() => {
          const active = globalThis.document.activeElement;
          if (active instanceof Node && dialogRef.current?.contains(active)) return;
          focusProductSearch();
        }, 0);
      }}
    >
      <section className="warehouse-document-dialog warehouse-document-dialog-v2">
        <header className="warehouse-document-topbar">
          <div className="warehouse-document-file-menu" ref={fileMenuRef}>
            <button type="button" onClick={() => setFileMenuOpen((current) => !current)}>{t("warehouseDocument.menu.file")}</button>
            {fileMenuOpen && (
              <div className="warehouse-document-menu" role="menu">
                <button type="button" onClick={openPartnerList}>{partnerLabel}</button>
                <button type="button" disabled={!canSaveDraft} onClick={() => { setFileMenuOpen(false); void saveDraft(); }}>{t("common.save")}</button>
                <button type="button" disabled={!canSubmitConfirmation} onClick={() => { setFileMenuOpen(false); void confirmDocument(); }}>{t("common.confirm")}</button>
                <button type="button" disabled={printing} onClick={previewDocument}>{t("warehouseDocument.menu.previewShortcut")}</button>
                <button type="button" disabled={printing} onClick={printDocument}>{t("salesReport.print")}</button>
                <button type="button" disabled={readOnly} onClick={clearAllLines}>{t("warehouseDocument.menu.clearLines")}</button>
                <button type="button" disabled={readOnly} onClick={clearAllDiscounts}>{t("warehouseDocument.menu.clearDiscounts")}</button>
                <button type="button" disabled={readOnly} onClick={() => { setFileMenuOpen(false); setExcelImportOpen(true); }}>{t("warehouseDocument.importExcel")}</button>
                <button type="button" onClick={exportDocumentExcel}>{t("warehouseDocument.menu.exportExcel")}</button>
                <div className="warehouse-document-submenu">
                  <button type="button" aria-expanded={priceMenuOpen} onClick={() => setPriceMenuOpen((current) => !current)}>{t("warehouseDocument.menu.usePrice")}</button>
                  {priceMenuOpen && (
                    <div className="warehouse-document-submenu-panel" role="menu">
                      <button type="button" onClick={() => selectDocumentPriceSource("PURCHASE")}>{t("sharedExcel.price.purchase")}</button>
                      <button type="button" onClick={() => selectDocumentPriceSource("SALE")}>{t("sharedExcel.price.sale")}</button>
                      <button type="button" onClick={() => selectDocumentPriceSource("MEMBER")}>{t("sharedExcel.price.member")}</button>
                      <button type="button" onClick={() => selectDocumentPriceSource("WHOLESALE")}>{t("sharedExcel.price.wholesale")}</button>
                      <button type="button" onClick={() => selectDocumentPriceSource("OFFER")}>{t("sharedExcel.price.offer")}</button>
                    </div>
                  )}
                </div>
                <button type="button" onClick={onClose}>{t("warehouseDocument.menu.exit")}</button>
              </div>
            )}
          </div>
          <button type="button" disabled={printing} onClick={printDocument}>{t("salesReport.print")}</button>
          <button type="button" disabled={!canSaveDraft} onClick={() => void saveDraft()}>{t("warehouseDocument.menu.saveShortcut")}</button>
          <button type="button" disabled={!canSubmitConfirmation} onClick={() => void confirmDocument()}>{t("common.confirm")}</button>
          <button type="button" onClick={onClose}>{t("warehouseDocument.menu.exitShortcut")}</button>
        </header>

        <div className="warehouse-document-workspace">
          <aside className="warehouse-document-sidebar">
            <div className="warehouse-document-total">
              <span>{documentTypeLabel}{documentNumber ? ` / ${documentNumber}` : ""}</span>
              <strong>{formatDocumentAmount(documentTotal)}</strong>
              <em>{interpolateMessage(t("warehouseDocument.totalUnits"), { quantity: totalUnits.toLocaleString("es-ES") })}</em>
              <small>{t("warehouseDocument.subtotal")}: {formatDocumentAmount(documentSubtotal)}</small>
              <small>{t("warehouseDocument.documentDiscount")}: {formatDocumentDiscount(documentDiscountPercent)}</small>
            </div>

            <div className="warehouse-document-field">
              <span>{t("stock.column.warehouse")}</span>
              <ErpSelect
                aria-label={t("stock.column.warehouse")}
                value={warehouseId}
                disabled={readOnly || isEditing || linkedLinesLocked}
                options={[
                  { value: "", label: t("common.select") },
                  ...warehouses
                    .filter((warehouse) => warehouse.active !== false)
                    .map((warehouse) => ({
                      value: warehouse.id,
                      label: warehouse.name ?? warehouse.nombre ?? warehouse.id
                    }))
                ]}
                onChange={setWarehouseId}
                onCommit={() => moveFromActiveControl("next")}
                onNavigatePrevious={() => moveFromActiveControl("previous")}
              />
            </div>

            {mode === "input" && (
              <label className="warehouse-document-field">
                <span>{t("purchaseDocument.externalNumber")}</span>
                <input
                  value={externalNumber}
                  disabled={readOnly}
                  maxLength={128}
                  onChange={(event) => setExternalNumber(event.target.value)}
                />
              </label>
            )}

            {documentType === "FACTURA_ENTRADA" && !readOnly && (
              <label className="warehouse-document-field">
                <span>{t("warehouseDocument.sourceDeliveryNotes")}</span>
                <select
                  multiple
                  size={Math.min(5, Math.max(2, availableSourceDeliveryNotes.length))}
                  value={sourceDeliveryNoteIds}
                  onChange={(event) => selectSourceDeliveryNotes(
                    Array.from(event.currentTarget.selectedOptions, (option) => option.value)
                  )}
                >
                  {availableSourceDeliveryNotes.map((deliveryNote) => (
                    <option key={deliveryNote.id} value={deliveryNote.id}>
                      {deliveryNote.number || deliveryNote.externalNumber || deliveryNote.id}
                    </option>
                  ))}
                </select>
              </label>
            )}

            <div className={`warehouse-document-partner-panel${mode === "input" ? " warehouse-document-supplier-panel" : ""}`}>
              {mode === "input" ? (
                <button
                  type="button"
                  className="warehouse-document-partner-card"
                  disabled={readOnly}
                  data-warehouse-partner-trigger
                  onClick={openPartnerList}
    >
      <span className="warehouse-document-partner-copy">
        <small>{t("warehouseDocument.partner.supplierCard")}</small>
        <strong>{selectedPartner ? partnerName(selectedPartner) : partnerText || t("warehouseDocument.noSupplier")}</strong>
      </span>
      <kbd>{t("warehouseDocument.shortcut.end")}</kbd>
    </button>
              ) : (
                <>
                  <span>{t("warehouseDocument.partner.output")}</span>
                  <div className="warehouse-document-field">
                    <ErpSelect
                      aria-label={partnerLabel}
                      value={partnerId}
                      disabled={readOnly}
                      options={[
                        { value: "", label: t("common.select") },
                        ...partnerOptions.map((option) => ({ value: option.id, label: partnerName(option) }))
                      ]}
                      onChange={(next) => {
                        const selected = partnerOptions.find((option) => option.id === next);
                        setPartnerId(next);
                        setPartnerText(selected ? partnerName(selected) : "");
                      }}
                      onCommit={() => moveFromActiveControl("next")}
                      onNavigatePrevious={() => moveFromActiveControl("previous")}
                    />
                  </div>
                  <button type="button" disabled={readOnly} data-warehouse-partner-trigger onClick={openPartnerList}>
                    {interpolateMessage(t("warehouseDocument.partnerList"), { partner: partnerLabel.toLocaleLowerCase(locale) })}
                  </button>
                  <p>{selectedPartner ? partnerName(selectedPartner) : partnerText || t("warehouseDocument.noPartner")}</p>
                </>
              )}
            </div>

            <label className="warehouse-document-discount">
              <span>{t("warehouseDocument.totalDiscount")}</span>
              <input
                type="number"
                min="0"
                max="100"
                step="0.01"
                value={documentDiscountPercent}
                disabled={readOnly}
                onChange={(event) => setDocumentDiscountPercent(event.target.value)}
              />
            </label>

            <label className="warehouse-document-comments">
              <span>{t("warehouseDocument.comments")}</span>
              <textarea value={concept} disabled={readOnly} onChange={(event) => setConcept(event.target.value)} />
            </label>

            {status && <p className="warehouse-document-status" aria-live="polite">{status}</p>}
          </aside>

          <main className="warehouse-document-lines-panel">
            <div className="warehouse-document-context-bar">
              {!readOnly && !linkedLinesLocked && (
                <div className="warehouse-document-product-search">
                  <label htmlFor="warehouse-document-product-search">{t("warehouseDocument.searchProduct")}</label>
                  <div>
                    <input
                      id="warehouse-document-product-search"
                      ref={productSearchRef}
                      autoComplete="off"
                      value={productSearchQuery}
                      placeholder={t("warehouseDocument.searchProductPlaceholder")}
                      onChange={(event) => setProductSearchQuery(event.target.value)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter") {
                          event.preventDefault();
                          submitProductSearch();
                        } else if (event.key === "Delete") {
                          event.preventDefault();
                          setProductSearchOpen(true);
                        }
                      }}
                    />
                    <button type="button" onClick={() => setProductSearchOpen(true)}>{t("warehouseDocument.searchProductButton")}</button>
                  </div>
                  {nextScanQuantity !== 1 && (
                    <small>{nextScanMode === "PACKAGE" ? "*" : "+"} {nextScanQuantity}</small>
                  )}
                </div>
              )}
              <div className="warehouse-document-meta">
                <label>
                  <span>{t("salesReport.filter.date")}</span>
                  <input type="date" value={date} disabled={readOnly || isEditing} onChange={(event) => setDate(event.target.value)} />
                </label>
                <div className="warehouse-document-state">
                  <span>{t("salesReport.column.status")}</span>
                  <strong>{warehouseDocumentStatusLabel(documentStatus, t)}</strong>
                </div>
                <div className="warehouse-document-state">
                  <span>{t("warehouseDocument.menu.usePrice")}</span>
                  <strong>{priceSourceLabel(documentPriceMode, t)}</strong>
                </div>
              </div>
            </div>
            <div className="warehouse-document-table-scroll">
              <table className="report-table warehouse-document-table">
                <colgroup>
                  {visibleColumns.map((column) => (
                    <col key={column.key} style={{ width: `${column.width}px` }} />
                  ))}
                </colgroup>
                <thead>
                  <tr>
                    {visibleColumns.map((column) => {
                      const definition = warehouseDocumentColumns.find((candidate) => candidate.key === column.key);
                      const label = definition ? t(definition.labelKey) : column.key;
                      return (
                        <TableLayoutHeaderCell
                          column={column}
                          key={column.key}
                          resizeLabel={`${t("stock.columns.resize")} ${label}`}
                          onReorder={tableLayout.reorderColumns}
                          onMove={tableLayout.moveColumn}
                          onResize={tableLayout.resizeColumn}
                          onToggleVisibility={tableLayout.toggleColumnVisibility}
                          columnVisibilityOptions={tableLayout.layout.map((candidate) => {
                            const candidateDefinition = warehouseDocumentColumns.find((item) => item.key === candidate.key);
                            return {
                              key: candidate.key,
                              label: candidateDefinition ? t(candidateDefinition.labelKey) : candidate.key,
                              visible: candidate.visible
                            };
                          })}
                        >
                          {label}
                        </TableLayoutHeaderCell>
                      );
                    })}
                  </tr>
                </thead>
                <tbody>
                  {lines.map((line, index) => (
                    <tr
                      className={(line.valid ? "" : "warehouse-document-line-error") + (selectedLineIndex === index ? " warehouse-document-line-selected" : "")}
                      key={line.rowNumber + "-" + index}
                      onClick={() => setSelectedLineIndex(index)}
                      onDoubleClick={() => openLineEditor(index)}
                    >
                      {visibleColumns.map((column) => (
                        <td key={column.key}>{renderDocumentLineCell(column.key, line, index)}</td>
                      ))}
                    </tr>
                  ))}
                  {lines.length === 0 && (
                    <tr>
                      <td colSpan={visibleColumns.length}>{t("warehouseDocument.emptyLines")}</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </main>
        </div>
     </section>
      {quickLineEditMode !== null && (
        <div className="filter-overlay warehouse-line-editor-overlay" role="dialog" aria-modal="true" aria-labelledby="warehouse-quick-line-editor-title">
          <form
            className="filter-dialog warehouse-line-editor warehouse-line-quick-editor"
            onSubmit={saveQuickLineEditor}
            onKeyDown={(event) => {
              event.stopPropagation();
              if (event.key === "Escape") {
                event.preventDefault();
                closeQuickLineEditor();
              }
            }}
          >
            <header className="filter-header">
              <div>
                <h2 id="warehouse-quick-line-editor-title">{t(
                  pendingZeroPriceProduct
                    ? "warehouseDocument.registerPrice"
                    : quickLineEditMode === "name"
                      ? "warehouseDocument.changeName"
                      : "warehouseDocument.changePrice"
                )}</h2>
                <span>{pendingZeroPriceProduct?.product.name
                  ?? (quickLineEditIndex !== null ? lines[quickLineEditIndex]?.productLabel : "")}</span>
              </div>
              <button type="button" onClick={closeQuickLineEditor}>{t("common.close")}</button>
            </header>
            <label>
              <span>{t(quickLineEditMode === "name"
                ? "warehouseDocument.column.name"
                : "warehouseDocument.column.price")}</span>
              <input
                ref={quickLineEditInputRef}
                type={quickLineEditMode === "price" ? "number" : "text"}
                min={quickLineEditMode === "price" ? (pendingZeroPriceProduct ? "0.01" : "0") : undefined}
                step={quickLineEditMode === "price" ? "0.01" : undefined}
                maxLength={quickLineEditMode === "name" ? 255 : undefined}
                value={quickLineEditValue}
                onChange={(event) => setQuickLineEditValue(event.target.value)}
              />
            </label>
            <footer className="filter-actions">
              <button type="button" onClick={closeQuickLineEditor}>{t("common.cancel")}</button>
              <button type="submit" className="primary">{t("common.save")}</button>
            </footer>
          </form>
        </div>
      )}
      {lineEditorIndex !== null && (
        <div className="filter-overlay warehouse-line-editor-overlay" role="dialog" aria-modal="true" aria-labelledby="warehouse-line-editor-title">
          <form
            className="filter-dialog warehouse-line-editor"
            onSubmit={saveLineEditor}
           onKeyDown={(event) => {
              event.stopPropagation();
             if (event.key === "Escape") {
                event.preventDefault();
                event.stopPropagation();
                closeLineEditor();
              }
            }}
          >
            <header className="filter-header">
              <div>
                <h2 id="warehouse-line-editor-title">{t("warehouseDocument.editLine")}</h2>
                <span>{lines[lineEditorIndex]?.productLabel}</span>
              </div>
              <button type="button" onClick={() => setLineEditorIndex(null)}>{t("common.close")}</button>
            </header>
            <label><span>{t("warehouseDocument.quantity")}</span><input autoFocus={lineEditorInitialField === "quantity"} disabled={linkedLinesLocked} type="number" min="0.001" step="0.001" value={lineEditQuantity} onChange={(event) => setLineEditQuantity(event.target.value)} /></label>
            {mode === "input" && <label><span>{t("warehouseDocument.column.name")}</span><input ref={lineEditNameRef} maxLength={255} value={lineEditName} onChange={(event) => setLineEditName(event.target.value)} /></label>}
            <label><span>{t("warehouseDocument.column.price")}</span><input ref={lineEditPriceRef} type="number" min="0" step="0.01" value={lineEditPrice} onChange={(event) => setLineEditPrice(event.target.value)} /></label>
            <label><span>{t("warehouseDocument.column.discount")}</span><input type="number" min="0" max="100" step="0.01" value={lineEditDiscount} onChange={(event) => setLineEditDiscount(event.target.value)} /></label>
            <footer className="filter-actions">
              <button type="button" onClick={closeLineEditor}>{t("common.cancel")}</button>
              <button type="submit" className="primary">{t("common.save")}</button>
            </footer>
          </form>
        </div>
      )}
      {productSearchOpen && (
        <SaleProductSearchDialog
          initialQuery={productSearchQuery}
          interfaceMode="KEYBOARD"
          locale={locale}
          products={productSearchProducts}
          labels={{
            title: t("warehouseDocument.productSearchTitle"),
            query: t("warehouseDocument.searchProduct"),
            image: t("sale.searchDialog.image"),
            code: t("sale.searchDialog.code"),
            barcode: t("sale.searchDialog.barcode"),
            name: t("sale.searchDialog.name"),
            stock: t("sale.searchDialog.stock"),
            price: priceSourceLabel(documentPriceMode, t),
            result: t("sale.searchDialog.result"),
            results: t("sale.searchDialog.results"),
            empty: t("sale.searchDialog.empty"),
            close: t("common.close"),
            add: t("warehouseDocument.addLine"),
            details: t("sale.searchDialog.details"),
            navigate: t("sale.searchDialog.navigate"),
            selected: t("sale.searchDialog.selected"),
            unnamedProduct: t("sale.searchDialog.unnamedProduct"),
            missingCode: t("sale.searchDialog.missingCode")
          }}
          onClose={() => {
            setProductSearchOpen(false);
            window.requestAnimationFrame(() => productSearchRef.current?.focus());
          }}
          onQueryChange={setProductSearchQuery}
          onSelect={(option) => {
            const product = importProducts.find((candidate) => candidate.id === option.id);
            if (product) addProductToDocument(product);
          }}
        />
      )}
      {mode === "input" && (
        <WarehouseSupplierDialog
          open={supplierDialogOpen}
          locale={locale}
          session={session}
          selectedId={partnerId}
          suppliers={localSuppliers.map((supplier) => ({
            id: supplier.id,
            supplierId: supplier.supplierId ?? "",
            legalName: supplier.legalName ?? supplier.razonSocial ?? "",
            tradeName: supplier.tradeName ?? null,
            documentType: supplier.documentType ?? "NIF",
            documentNumber: supplier.documentNumber ?? supplier.numeroDocumento ?? "",
            phone: supplier.phone ?? null,
            email: supplier.email ?? null,
            active: supplier.active !== false
          }))}
          onClose={() => {
            setSupplierDialogOpen(false);
            window.requestAnimationFrame(() => productSearchRef.current?.focus());
          }}
          onSelected={(supplier) => {
            setPartnerId(supplier.id);
            setPartnerText(partnerName({ id: supplier.id, legalName: supplier.legalName, documentNumber: supplier.documentNumber }));
            setSupplierDialogOpen(false);
            window.requestAnimationFrame(() => productSearchRef.current?.focus());
          }}
          onChanged={(supplier) => setLocalSuppliers((current) => [
            ...current.filter((candidate) => candidate.id !== supplier.id),
            {
              id: supplier.id,
              supplierId: supplier.supplierId,
              legalName: supplier.legalName,
              tradeName: supplier.tradeName,
              documentType: supplier.documentType,
              documentNumber: supplier.documentNumber,
              phone: supplier.phone,
              email: supplier.email,
              active: supplier.active
            }
          ])}
        />
      )}
     <SharedExcelImportDialog
        open={excelImportOpen}
        locale={locale}
        products={products.map((product) => ({
          id: product.id,
          code: product.code,
          barcode: product.barcode
        })).concat(excelCreatedProducts.map((product) => ({
          id: product.id,
          code: product.code,
          barcode: product.barcode
        })))}
        title={t("warehouseDocument.importExcel")}
        requireQuantity
        terminalContext={terminalContext}
        onClose={() => {
          setExcelImportOpen(false);
          window.requestAnimationFrame(() => productSearchRef.current?.focus());
        }}
        onImportAccepted={importAcceptedExcelRows}
        onAddMissingAuto={addMissingProductsAuto}
        onAddMissingManual={addMissingProductsManual}
      />
      <ProductCreateDialog
        open={manualMissingRows.length > 0}
        locale={locale}
        token={token}
        initialForm={manualMissingRows[manualMissingIndex] ? productFormFromExcelDraft(manualMissingRows[manualMissingIndex].draft) : undefined}
        onClose={closeManualMissingProduct}
        onCreated={manualMissingProductCreated}
      />
    </div>
  );
}

function partnerName(option: WarehouseCustomerOption | WarehouseSupplierOption) {
  if ("legalName" in option || "razonSocial" in option) {
    const supplier = option as WarehouseSupplierOption;
    return [supplier.legalName ?? supplier.razonSocial, supplier.documentNumber ?? supplier.numeroDocumento].filter(Boolean).join(" - ");
  }
  const customer = option as WarehouseCustomerOption;
  return [customer.fiscalName ?? customer.nombreFiscal, customer.documentNumber ?? customer.numeroDocumento].filter(Boolean).join(" - ");
}

function productLabel(product: WarehouseImportProduct) {
  return [product.code ?? product.barcode ?? product.reference, product.name].filter(Boolean).join(" - ") || product.id;
}

function documentProductPrice(product: WarehouseImportProduct | undefined, mode: WarehouseInputPriceSource) {
  if (!product) return 0;
  const value = mode === "PURCHASE" ? product.purchasePrice
    : mode === "SALE" ? product.salePrice
      : mode === "MEMBER" ? product.memberPrice
        : mode === "WHOLESALE" ? product.wholesalePrice
          : product.offerPrice;
  return decimalDocumentNumber(value ?? product.purchasePrice ?? product.salePrice ?? product.wholesalePrice);
}

function priceSourceDraftKey(source: WarehouseInputPriceSource): "purchasePrice" | "salePrice" | "memberPrice" | "wholesalePrice" | "offerPrice" {
  if (source === "SALE") return "salePrice";
  if (source === "MEMBER") return "memberPrice";
  if (source === "WHOLESALE") return "wholesalePrice";
  if (source === "OFFER") return "offerPrice";
  return "purchasePrice";
}

function priceSourceLabel(source: WarehouseInputPriceSource, t: (key: string) => string) {
  if (source === "SALE") return t("sharedExcel.price.sale");
  if (source === "MEMBER") return t("sharedExcel.price.member");
  if (source === "WHOLESALE") return t("sharedExcel.price.wholesale");
  if (source === "OFFER") return t("sharedExcel.price.offer");
  return t("sharedExcel.price.purchase");
}

function decimalDocumentNumber(value: string | number | null | undefined) {
  const number = Number(String(value ?? "0").replace(",", "."));
  return Number.isFinite(number) ? number : 0;
}

function parseDocumentDiscountPercent(value: string | number | null | undefined) {
  const number = decimalDocumentNumber(value);
  if (number < 0) return 0;
  if (number > 100) return 100;
  return number;
}

export function documentLineTotal(price: number, quantity: number, discountPercent: string | number | null | undefined) {
  const discount = parseDocumentDiscountPercent(discountPercent);
  const total = price * quantity * (1 - discount / 100);
  return Number.isFinite(total) ? total : 0;
}

export function documentTotalAfterDiscount(subtotal: number, discountPercent: string | number | null | undefined) {
  const discount = parseDocumentDiscountPercent(discountPercent);
  const total = subtotal * (1 - discount / 100);
  return Number.isFinite(total) ? total : 0;
}

function formatDocumentDiscount(value: string | number | null | undefined) {
  return `${formatDocumentAmount(parseDocumentDiscountPercent(value))}%`;
}

function formatDocumentAmount(value: number) {
  return new Intl.NumberFormat("es-ES", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(Number.isFinite(value) ? value : 0);
}

function interpolateMessage(template: string, values: Record<string, string | number>) {
  return Object.entries(values).reduce(
    (result, [key, value]) => result.replaceAll(`{${key}}`, String(value)),
    template
  );
}

function warehouseDocumentStatusLabel(status: string, t: (key: string) => string) {
  const key = `warehouseDocument.status.${status.trim().toLocaleUpperCase()}`;
  const translated = t(key);
  return translated === key ? status : translated;
}

function csvCell(value: unknown) {
  const text = String(value ?? "");
  return /[;"\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

async function loadProductCreateDefaults(token: string) {
  const [families, taxes] = await Promise.all([
    apiRequest<Array<{ id: string; defaultFamily?: boolean | null }>>("/families", { token }),
    apiRequest<Array<{ id: string; defaultTax?: boolean | null }>>("/taxes/selectable", { token })
  ]);
  return { families, taxes };
}

function productFormFromExcelDraft(draft: ExcelImportProductDraft): ProductCreateFormState {
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
    offerUntil: draft.offerUntil
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
