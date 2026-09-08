import { useEffect, useMemo, useRef, useState } from "react";
import "../styles/shared-excel-import.css";
import type { PointerEvent } from "react";
import {
  productExcelReadResultToSheet,
  productExcelImportErrorsFromApi,
  loadProductExcelImportTaxes,
  exportProductExcelImportSummary,
  applyProductExcelImport,
  previewProductExcelImport,
  readProductExcelImport,
  type ProductExcelImportContext,
  type ProductExcelImportOperation,
  type ProductExcelImportExportView,
  type ProductExcelImportError,
  type ProductExcelImportPreviewResult,
  type ProductExcelImportPreviewRow,
  type ProductExcelImportReadResult,
  type ProductExcelImportPreviewConfig
} from "../api/productExcelImports";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, TerminalContext } from "../types";
import { ErpSelect } from "./ErpSelect";
import { ProductCreateDialog, type ProductCreateResponse } from "./ProductCreateDialog";
import { productFormFromExcelDraft } from "./excelImportProductForm";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { ExcelImportReviewTable, type ExcelReviewColumn, type ExcelReviewRow } from "./ExcelImportReviewTable";
import { revealExcelTableRow, useExcelTableHeader } from "./useExcelTableHeader";
import { parseMoneyValue } from "../money";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import {
  classifyExcelProductRows,
  buildExcelImportDraft,
  excelCellText,
  excelColumnIndexToLetter,
  excelColumnLetterToIndex,
  excelImportAccept,
  normalizeExcelText,
  parseExcelDate,
  findExcelColumn,
  type ExcelCell,
  type ExcelColumnMapping,
  type ExcelImportClassifiedRow,
  type ExcelImportProductIdentity,
  type ExcelSheet
} from "./excelImport";
import {
  extractExcelFormulaMetadata,
  isExcelFormulaCell
} from "./excelFormula";

type ProductMappingKey = keyof ExcelColumnMapping;

export type SharedExcelImportUpdateField = ProductMappingKey;

export type SharedExcelImportAcceptedRow = ExcelImportClassifiedRow & {
  quantity?: number;
  updateFields: Partial<Record<SharedExcelImportUpdateField, boolean>>;
};

export type SharedExcelImportMetadata = {
  fileName?: string | null;
  sha256?: string | null;
  sheetName?: string | null;
  lines?: Array<{
    productId: string;
    rowNumbers: number[];
    supplierReference?: string | null;
    grossPurchasePrice?: string | null;
    purchaseDiscountPercent?: string | null;
  }>;
  /** Authoritative document-level flags consumed by WarehouseInput confirmation. */
  updateSupplier?: boolean;
  skipZeroPriceUpdate?: boolean;
  documentPriceSource?: SharedExcelImportPriceSource;
  provenanceToken?: string | null;
  formulas: Array<{ cell: string; formula: string; calculatedValue: string | null }>;
};

type SharedExcelImportPanel = "mapping" | "summary" | "missing" | "priceChanged" | "accepted" | "errors";
const exportViewForPanel: Record<SharedExcelImportPanel, ProductExcelImportExportView> = {
  mapping: "RAW", summary: "SUMMARY", missing: "MISSING", priceChanged: "PURCHASE_CHANGED", accepted: "IMPORTABLE", errors: "ERRORS"
};
export type SharedExcelImportPriceSource = "purchasePrice" | "salePrice" | "memberPrice" | "wholesalePrice" | "offerPrice";
export type SharedExcelImportValueSource = "excel" | "global";
export type SharedExcelImportTaxOption = { id: string; label: string; value?: string | number; defaultTax?: boolean; active?: boolean };
export type SharedExcelImportSupplier = {
  id: string;
  code?: string | null;
  legalName?: string | null;
  tradeName?: string | null;
  documentType?: string | null;
  documentNumber?: string | null;
  active?: boolean | null;
};

type SharedExcelImportDialogProps = {
  open: boolean;
  file?: File | null;
  sheet?: ExcelSheet;
  locale: LocaleCode;
  products: readonly ExcelImportProductIdentity[];
  title?: string;
  requireQuantity?: boolean;
  onClose: () => void;
  onImportAccepted: (rows: SharedExcelImportAcceptedRow[], metadata: SharedExcelImportMetadata) => void;
  currentPurchasePrice?: (product: ExcelImportProductIdentity) => string | number | null | undefined;
  onReviewMissing?: (row: ExcelImportClassifiedRow) => void;
  initialPanel?: SharedExcelImportPanel;
  terminalContext?: Pick<TerminalContext, "terminalCode" | "terminalId">;
  taxOptions?: readonly SharedExcelImportTaxOption[];
  showDocumentPriceSource?: boolean;
  token?: string;
  context?: ProductExcelImportContext;
  supplier?: SharedExcelImportSupplier | null;
  warehouseId?: string;
  documentDate?: string;
};

type MappingField = {
  key: ProductMappingKey | "quantity";
  label: string;
  translatedLabels?: Record<LocaleCode, string>;
  aliases: string[];
  updateKey?: SharedExcelImportUpdateField;
};

// Explicit groups keep Stock's omitted quantity from shifting other attributes.
const sharedExcelFieldGroups: MappingField[][] = [[
  field("code", "Código", "Code", "编码", ["codigo", "codigo producto", "codigo articulo", "code", "sku", "referencia", "ref"]),
  field("barcode", "Código de barras", "Barcode", "条码", ["codigo de barras", "codigo barras", "barcode", "ean", "ean13", "gtin"]),
  field("name", "Nombre", "Name", "名称", ["nombre", "nombre producto", "producto", "product", "name", "denominacion"], "name"),
  field("description", "Descripción", "Description", "描述", ["descripcion", "descripcion larga", "description", "detalle"], "description"),
  field("quantity", "Cantidad", "Quantity", "数量", ["cantidad", "quantity", "unidades", "uds"]),
  field("purchasePrice", "Precio de compra", "Purchase price", "采购价", ["precio", "precio compra", "precio de compra", "precio coste", "precio de coste", "compra", "coste", "purchase price", "cost"], "purchasePrice"),
  field("purchaseDiscountPercent", "Descuento de compra", "Purchase discount", "采购折扣", ["descuento", "descuento compra", "descuento de compra", "descuento lineal", "dto", "dto compra", "purchase discount", "purchase discount percent"], "purchaseDiscountPercent"),
  field("familyId", "Familia / Subfamilia", "Family / Subfamily", "类别 / 子类别", ["familia / subfamilia", "family / subfamily", "类别 / 子类别", "familia", "family", "codigo familia", "código familia", "family code", "subfamilia", "subfamily", "codigo subfamilia", "código subfamilia", "subfamily code", "family id", "familyid"], "familyId"),
  field("barcode2", "Código de barras 2", "Barcode 2", "条码 2", ["codigo de barras 2", "codigo barras 2", "barcode 2", "barcode2", "ean 2", "gtin 2"], "barcode2")
], [
  field("salePrice", "Precio de venta", "Sale price", "售价", ["precio venta", "precio de venta", "venta", "sale price", "price"], "salePrice"),
  field("memberPrice", "Precio de miembro", "Member price", "会员价", ["precio de miembro", "precio miembro", "member price", "memberprice"], "memberPrice"),
  field("wholesalePrice", "Precio mayorista", "Wholesale price", "批发价", ["precio mayor", "precio mayorista", "precio de mayorista", "wholesale price", "wholesaleprice"], "wholesalePrice"),
  field("offerPrice", "Precio de oferta", "Offer price", "促销价", ["precio oferta", "precio de oferta", "offer price", "offerprice"], "offerPrice"),
  field("offerDiscountPercent", "Descuento de oferta %", "Offer discount %", "促销折扣%", ["descuento oferta", "descuento de oferta", "descuento oferta %", "descuento de oferta %", "offer discount", "offer discount percent"], "offerDiscountPercent"),
  field("offerFrom", "Oferta desde", "Offer from", "促销开始", ["oferta desde", "offer from", "offerfrom"], "offerFrom"),
  field("offerUntil", "Oferta hasta", "Offer until", "促销结束", ["oferta hasta", "offer until", "offeruntil"], "offerUntil"),
  field("offerActive", "Oferta activa", "Offer active", "促销启用", ["oferta activa", "offer active", "offeractive"], "offerActive")
], [
  field("productType", "Tipo de producto", "Product type", "商品类型", ["tipo producto", "tipo", "product type", "producttype"], "productType"),
  field("priceUseMode", "Usar precio", "Use price", "使用价格", ["usar precio", "price use", "price use mode", "priceusemode"], "priceUseMode"),
  field("discountType", "Prohibido descuento", "Discount prohibited", "禁止折扣", ["prohibido descuento", "no aplicar descuento", "discount prohibited", "discount type", "discounttype"], "discountType"),
  field("taxId", "Impuestos", "Tax", "税", ["impuestos", "impuesto", "tax", "tax id", "taxid", "iva"], "taxId"),
  field("taxesIncluded", "Impuestos incluidos", "Taxes included", "含税", ["impuestos incluidos", "iva incluido", "taxes included", "taxesincluded"], "taxesIncluded"),
  field("packageQuantity", "Cantidad por paquete", "Package quantity", "每包数量", ["cantidad por paquete", "package quantity", "pack quantity"], "packageQuantity"),
  field("stockMin", "Stock mínimo", "Minimum stock", "最低库存", ["stock min", "stock minimo", "stock mínimo", "minimum stock"], "stockMin"),
  field("stockMax", "Stock máximo", "Maximum stock", "最高库存", ["stock max", "stock maximo", "stock máximo", "maximum stock"], "stockMax")
]];
const sharedExcelFieldOrder = sharedExcelFieldGroups.flat();

type SharedExcelImportStoredSettings = {
  useDefaultTax?: boolean;
  familyMappingNeedsReview?: boolean;
  mapping: ExcelColumnMapping;
  quantityColumn: string;
  startRow: number;
  updateFields: Partial<Record<SharedExcelImportUpdateField, boolean>>;
  options: SharedExcelImportOptions;
};

export type SharedExcelImportOptions = {
  autoAddMissing: boolean;
  generateSummaryDocument: boolean;
  showOnlyImported: boolean;
  skipZeroPriceUpdate: boolean;
  updateSupplier: boolean;
  priceSource: SharedExcelImportPriceSource;
  valueSources: SharedExcelImportValueSources;
};

export type SharedExcelImportValueSources = {
  offerActive: { source: SharedExcelImportValueSource; value: string };
  productType: { source: SharedExcelImportValueSource; value: string };
  priceUseMode: { source: SharedExcelImportValueSource; value: string };
  discountType: { source: SharedExcelImportValueSource; value: string };
  taxId: { source: SharedExcelImportValueSource; value: string };
  taxesIncluded: { source: SharedExcelImportValueSource; value: string };
};

type DragScrollState = {
  element: HTMLDivElement;
  pointerId: number;
  startX: number;
  startY: number;
  scrollLeft: number;
  scrollTop: number;
};

const excelImportStoragePrefix = "tpv.sharedExcelImport.v1";

export function sharedExcelImportKeyAction(key: string) {
  return key === "Escape" ? "close" : null;
}

export function SharedExcelImportDialog({
  open,
  file,
  sheet: providedSheet,
  locale,
  products,
  title,
  requireQuantity = false,
  currentPurchasePrice,
  onClose,
  onImportAccepted,
  onReviewMissing,
  initialPanel = "mapping",
  terminalContext,
  taxOptions = [],
  showDocumentPriceSource: requestedDocumentPriceSource = true,
  token,
  context = "STOCK",
  supplier = null,
  warehouseId,
  documentDate
}: SharedExcelImportDialogProps) {
  const t = createTranslator(locale);
  const showDocumentPriceSource = context === "WAREHOUSE_INPUT" && requestedDocumentPriceSource;
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const dragScrollRef = useRef<DragScrollState | null>(null);
  const dragScrollMovedRef = useRef(false);
  const storedSettings = loadStoredExcelImportSettings(terminalContext);
  const hasInitialSheet = Boolean(providedSheet?.length);
  const [localFile, setLocalFile] = useState<File | null>(null);
  const [sheet, setSheet] = useState<ExcelSheet>(() => providedSheet ?? []);
  const [mapping, setMapping] = useState<ExcelColumnMapping>(hasInitialSheet ? storedSettings.mapping : {});
  const [quantityColumn, setQuantityColumn] = useState(hasInitialSheet && context !== "STOCK" ? storedSettings.quantityColumn : "");
  const [startRow, setStartRow] = useState(hasInitialSheet ? storedSettings.startRow : 2);
  const [updateFields, setUpdateFields] = useState<Partial<Record<SharedExcelImportUpdateField, boolean>>>(
    hasInitialSheet ? storedSettings.updateFields : {}
  );
  const [status, setStatus] = useState("");
  const [activePanel, setActivePanel] = useState<SharedExcelImportPanel>(initialPanel);
  const [resolvedProducts, setResolvedProducts] = useState<Record<string, string>>({});
  const [manualQueue, setManualQueue] = useState<ExcelImportClassifiedRow[]>([]);
  const manualSaveInFlightRef = useRef(false);
  const [pendingOperation, setPendingOperation] = useState<ProductExcelImportOperation | null>(null);
  const [autoAddMissing, setAutoAddMissing] = useState(storedSettings.options.autoAddMissing);
  const [generateSummaryDocument, setGenerateSummaryDocument] = useState(storedSettings.options.generateSummaryDocument);
  const [showOnlyImported, setShowOnlyImported] = useState(storedSettings.options.showOnlyImported);
  const [skipZeroPriceUpdate, setSkipZeroPriceUpdate] = useState(storedSettings.options.skipZeroPriceUpdate);
  const [updateSupplier, setUpdateSupplier] = useState(storedSettings.options.updateSupplier);
  const [priceSource, setPriceSource] = useState<SharedExcelImportPriceSource>(storedSettings.options.priceSource);
  const [valueSources, setValueSources] = useState<SharedExcelImportValueSources>(storedSettings.options.valueSources);
  const taxDefaultPending = useRef(Boolean(storedSettings.useDefaultTax));
  const [editingSheet, setEditingSheet] = useState(false);
  const [columnWidths, setColumnWidths] = useState<Record<number, number>>({});
  const [previewScrollTop, setPreviewScrollTop] = useState(0);
  const [selectedSourceRow, setSelectedSourceRow] = useState<number | null>(null);
  const previewViewport = useRef<HTMLDivElement>(null);
  const previewHeader = useExcelTableHeader(previewViewport, sheet.length);
  useEffect(() => { setSelectedSourceRow(null); }, [file, localFile, providedSheet]);
  useEffect(() => {
    if (selectedSourceRow !== null) {
      setPreviewScrollTop(revealExcelTableRow(previewViewport.current, selectedSourceRow - 1, 28));
    }
  }, [selectedSourceRow]);
  const applyInFlightRef = useRef(false);
  const operationSequenceRef = useRef(0);
  const activeOperationKindRef = useRef<"read" | "preview" | "export" | "apply" | null>(null);
  const deferredPreviewInvalidationRef = useRef(false);
  const [isApplying, setIsApplying] = useState(false);
  const autoDetectedSourceRef = useRef<{ providedSheet: ExcelSheet | null; sha256: string | null } | null>(null);
  const [serverRead, setServerRead] = useState<ProductExcelImportReadResult | null>(null);
  const [serverPreview, setServerPreview] = useState<ProductExcelImportPreviewResult | null>(null);
  const [serverPreviewFingerprint, setServerPreviewFingerprint] = useState<string | null>(null);
  const serverPreviewCatalogFingerprintRef = useRef<string | null>(null);
  const [serverGlobalErrors, setServerGlobalErrors] = useState<ProductExcelImportError[]>([]);
  const cellDiagnostics = useMemo(() => {
    const errors: ProductExcelImportError[] = [];
    let count = 0;
    const include = (error: ProductExcelImportError) => {
      count += 1;
      if (errors.length < 20) errors.push(error);
    };
    if (serverRead) {
      serverRead.rows.forEach((row, rowIndex) => row.forEach((cell, columnIndex) => {
        if (cell.errorCode && excelCellText(sheet[rowIndex]?.[columnIndex]) === (cell.value ?? "")) {
          include({ code: cell.errorCode, row: rowIndex + 1, column: columnIndex + 1,
            receivedValue: cell.value, reason: "" });
        }
      }));
    } else {
      serverGlobalErrors.filter(error => error.row && error.column).forEach(include);
    }
    return { errors, count };
  }, [serverRead, sheet, serverGlobalErrors]);
  const [serverTaxOptions, setServerTaxOptions] = useState<SharedExcelImportTaxOption[]>([]);
  const [masterConfirmationCount, setMasterConfirmationCount] = useState<number | null>(null);
  const confirmationDialogRef = useRef<HTMLDivElement | null>(null);
  const [closeConfirmationOpen, setCloseConfirmationOpen] = useState(false);
  const closeConfirmationRef = useRef<HTMLElement | null>(null);
  const closeReturnFocusRef = useRef<HTMLElement | null>(null);
  const selectedFile = localFile ?? file ?? null;
  const supplierAvailable = context === "WAREHOUSE_INPUT" && Boolean(supplier && supplier.active !== false);
  const effectiveTaxOptions = (taxOptions.length > 0 ? taxOptions : serverTaxOptions).filter((tax) => tax.active !== false);
  const defaultTaxes = effectiveTaxOptions.filter((tax) => tax.defaultTax && tax.active !== false);
  const defaultTax = defaultTaxes.length === 1 ? defaultTaxes[0] : undefined;
  const importProducts = products ?? [];
  const catalogFingerprint = useMemo(() => stableSerialize(importProducts.map((product) => ({
    id: product.id,
    code: product.code,
    barcode: product.barcode
  }))), [importProducts]);
  const previousCatalogFingerprintRef = useRef<string | null>(null);

  function clearServerPreview() {
    setServerPreview(null);
    setServerPreviewFingerprint(null);
    serverPreviewCatalogFingerprintRef.current = null;
    setServerGlobalErrors([]);
    setAppliedRows(null);
  }

  function invalidateServerPreview() {
    // Once the apply request is in flight it may already have committed. Keep
    // the guard and let its response finish exactly once; clear the preview
    // only after the operation has released the guard.
    if (applyInFlightRef.current && activeOperationKindRef.current === "apply") {
      deferredPreviewInvalidationRef.current = true;
      return;
    }
    // A response from read/preview/export belongs to the previous snapshot.
    // Invalidate its sequence before clearing the visible result.
    if (applyInFlightRef.current) {
      operationSequenceRef.current += 1;
      applyInFlightRef.current = false;
      activeOperationKindRef.current = null;
      setIsApplying(false);
    }
    clearServerPreview();
  }

  function handleClose() {
    if (applyInFlightRef.current || manualSaveInFlightRef.current || manualQueue.length || masterConfirmationCount !== null || closeConfirmationOpen) return;
    closeReturnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setCloseConfirmationOpen(true);
  }

  function cancelClose() {
    setCloseConfirmationOpen(false);
    const previousFocus = closeReturnFocusRef.current;
    queueMicrotask(() => { if (previousFocus?.isConnected) previousFocus.focus(); });
  }

  function confirmClose() {
    if (applyInFlightRef.current || manualSaveInFlightRef.current) return;
    setCloseConfirmationOpen(false);
    onClose();
  }

  function beginExclusiveOperation(kind: "read" | "preview" | "export" | "apply") {
    if (applyInFlightRef.current) return null;
    const sequence = ++operationSequenceRef.current;
    applyInFlightRef.current = true;
    activeOperationKindRef.current = kind;
    setIsApplying(true);
    return sequence;
  }

  function endExclusiveOperation(sequence: number) {
    if (operationSequenceRef.current !== sequence) return;
    applyInFlightRef.current = false;
    activeOperationKindRef.current = null;
    setIsApplying(false);
    if (deferredPreviewInvalidationRef.current) {
      deferredPreviewInvalidationRef.current = false;
      clearServerPreview();
    }
  }

  function currentPreviewConfig(): ProductExcelImportPreviewConfig | null {
    if (!serverRead) return null;
    const effectiveMapping = Object.fromEntries(Object.entries({
      ...mapping,
      ...(context !== "STOCK" && quantityColumn ? { quantity: quantityColumn } : {})
    }).filter(([key, column]) => Boolean(column) && !(context === "STOCK" && key === "quantity")));
    return {
      mapping: effectiveMapping,
      edits: changedExcelCells(serverRead, sheet),
      options: {
        globalValues: {},
        valueSources,
        // Resolution and concurrency need the current product regardless of the summary view.
        showOnlyImported: false,
        context,
        skipZeroPriceUpdate,
        requireQuantity: context !== "STOCK" && requireQuantity,
        ...(showDocumentPriceSource ? { documentPriceSource: priceSource } : {})
      },
      expectedSha256: serverRead.sha256,
      startRow,
      quantityColumn: context === "STOCK" ? undefined : quantityColumn || undefined,
      resolvedProducts,
      updateFields: Object.fromEntries(Object.entries(updateFields).map(([key, value]) => [key, Boolean(value)]))
    };
  }

  function currentPreviewFingerprint() {
    const config = currentPreviewConfig();
    return config ? stableSerialize(config) : null;
  }

  const serverPreviewIsCurrent = Boolean(
    serverPreview && serverPreviewFingerprint
      && serverPreviewCatalogFingerprintRef.current === catalogFingerprint
      && currentPreviewFingerprint() === serverPreviewFingerprint
  );

  useEffect(() => {
    if (!open) {
      autoDetectedSourceRef.current = null;
      setSheet([]);
      const nextStoredSettings = loadStoredExcelImportSettings(terminalContext);
      setMapping({});
      setQuantityColumn("");
      setStartRow(2);
      setUpdateFields({});
      setStatus("");
      setResolvedProducts({});
      setManualQueue([]);
      setActivePanel(initialPanel);
      setLocalFile(null);
      applyStoredOptions(nextStoredSettings.options, nextStoredSettings.useDefaultTax);
      setEditingSheet(false);
      setColumnWidths({});
      setPreviewScrollTop(0);
      setServerRead(null);
      setMasterConfirmationCount(null);
      setCloseConfirmationOpen(false);
      invalidateServerPreview();
      return;
    }
    if (providedSheet) {
      autoDetectedSourceRef.current = null;
      setServerRead(null);
      setSheet(providedSheet);
      setEditingSheet(false);
      setColumnWidths({});
      setPreviewScrollTop(0);
      invalidateServerPreview();
      return;
    }
    if (!selectedFile) {
      return;
    }
    autoDetectedSourceRef.current = null;
    setResolvedProducts({});
    setManualQueue([]);
    setSheet([]);
    setServerRead(null);
    setActivePanel("mapping");
    setPreviewScrollTop(0);
    invalidateServerPreview();
    let cancelled = false;
    const controller = new AbortController();
    const operationSequence = beginExclusiveOperation("read");
    if (operationSequence === null) return () => controller.abort();
    setStatus(t("sharedExcel.status.reading"));
    void readProductExcelImport(selectedFile, token, controller.signal)
      .then((result) => {
        if (!cancelled && operationSequenceRef.current === operationSequence) {
          const normalizedSheet = productExcelReadResultToSheet(result);
          setServerRead(result);
          setSheet(normalizedSheet);
          setEditingSheet(false);
          setColumnWidths({});
          setPreviewScrollTop(0);
          invalidateServerPreview();
          setStatus("");
        }
      })
      .catch((error) => {
        if (!cancelled && operationSequenceRef.current === operationSequence) {
          const structured = productExcelImportErrorsFromApi(error);
          setServerGlobalErrors(structured);
          setActivePanel("errors");
          setStatus(structured[0]?.reason ?? t("sharedExcel.status.readError"));
        }
      })
      .finally(() => endExclusiveOperation(operationSequence));
    return () => {
      cancelled = true;
      controller.abort();
      endExclusiveOperation(operationSequence);
    };
  }, [initialPanel, open, providedSheet, selectedFile, terminalContext, token]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const nextStoredSettings = loadStoredExcelImportSettings(terminalContext);
    applyStoredOptions(nextStoredSettings.options, nextStoredSettings.useDefaultTax);
  }, [open, terminalContext]);

  useEffect(() => {
    if (!supplierAvailable) setUpdateSupplier(false);
  }, [supplierAvailable]);

  useEffect(() => {
    if (!open || taxOptions.length > 0 || !token) {
      setServerTaxOptions((current) => current.length > 0 ? [] : current);
      return;
    }
    const controller = new AbortController();
    void loadProductExcelImportTaxes(token, controller.signal)
      .then((taxes) => setServerTaxOptions(taxes.map((tax) => ({
        id: tax.id,
        value: tax.id,
        defaultTax: tax.defaultTax ?? false,
        active: tax.active ?? true,
        label: [tax.name, tax.percentage == null ? null : `${tax.percentage}%`].filter(Boolean).join(" · ") || tax.id
      }))))
      .catch(() => setServerTaxOptions([]));
    return () => controller.abort();
  }, [context, open, taxOptions.length, token]);

  useEffect(() => {
    if (!open || !taxDefaultPending.current || !defaultTax) return;
    taxDefaultPending.current = false;
    setValueSources((current) => ({ ...current, taxId: { source: "global", value: String(defaultTax.value ?? defaultTax.id) } }));
  }, [open, defaultTax?.id, defaultTax?.value]);

  useEffect(() => {
    if (!open) {
      autoDetectedSourceRef.current = null;
      return;
    }
    const source = providedSheet
      ? { providedSheet, sha256: null }
      : { providedSheet: null, sha256: serverRead?.sha256 ?? null };
    if (!providedSheet && (!source.sha256 || sheet.length === 0)) return;
    const previous = autoDetectedSourceRef.current;
    if (previous && previous.providedSheet === source.providedSheet && previous.sha256 === source.sha256) return;
    autoDetectedSourceRef.current = source;
    const nextStoredSettings = loadStoredExcelImportSettings(terminalContext);
    const detected = detectExcelHeaderMapping(providedSheet ?? sheet, context);
    // Fixed defaults/selections are not implicit permission to update a product.
    for (const key of Object.keys(nextStoredSettings.options.valueSources) as Array<keyof SharedExcelImportValueSources>) {
      if (nextStoredSettings.options.valueSources[key].source === "global" || (key === "taxId" && nextStoredSettings.useDefaultTax)) {
        delete detected.mapping[key];
        delete detected.updateFields[key];
      }
    }
    if (nextStoredSettings.familyMappingNeedsReview) {
      delete detected.mapping.familyId;
      delete detected.updateFields.familyId;
      delete detected.updateFields.subfamilyId;
      setStatus(locale === "zh" ? "旧配置分别指定了类别和子类别。请重新指定合并字段的Excel列。"
        : locale === "en" ? "The saved configuration used separate family fields. Reassign the Excel column for the combined field."
          : "La configuración guardada separaba Familia y Subfamilia. Vuelve a asignar la columna Excel del campo unificado.");
    }
    setMapping({ ...detected.mapping, ...nextStoredSettings.mapping });
    setQuantityColumn(context === "STOCK" ? "" : nextStoredSettings.quantityColumn || detected.quantityColumn);
    setStartRow(nextStoredSettings.startRow);
    setUpdateFields({ ...detected.updateFields, ...nextStoredSettings.updateFields });
    invalidateServerPreview();
  }, [context, open, providedSheet, serverRead?.sha256, sheet.length, terminalContext]);

  useEffect(() => {
    if (open) {
      setActivePanel(initialPanel);
    }
  }, [file, initialPanel, open, providedSheet]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const closeOnEscape = (event: KeyboardEvent) => {
      if (manualQueue.length || manualSaveInFlightRef.current) return;
      if (event.target instanceof Element && event.target.closest(".erp-select--open")) return;
      if (sharedExcelImportKeyAction(event.key) !== "close") {
        return;
      }
      event.preventDefault();
      event.stopImmediatePropagation();
      if (event.repeat) return;
      if (closeConfirmationOpen) {
        cancelClose();
        return;
      }
      if (masterConfirmationCount !== null) {
        setMasterConfirmationCount(null);
        return;
      }
      handleClose();
    };
    window.addEventListener("keydown", closeOnEscape, true);
    return () => window.removeEventListener("keydown", closeOnEscape, true);
  }, [manualQueue.length, masterConfirmationCount, closeConfirmationOpen, onClose, open]);

  useEffect(() => {
    if (!closeConfirmationOpen || !closeConfirmationRef.current) return;
    return activateModalFocusTrap(closeConfirmationRef.current as unknown as ModalFocusRoot, document, { restoreFocus: false });
  }, [closeConfirmationOpen]);

  useEffect(() => {
    if (masterConfirmationCount === null || !confirmationDialogRef.current) return;
    return activateModalFocusTrap(
      confirmationDialogRef.current as unknown as ModalFocusRoot,
      document,
      { restoreFocus: false }
    );
  }, [masterConfirmationCount]);

  useEffect(() => {
    const previous = previousCatalogFingerprintRef.current;
    previousCatalogFingerprintRef.current = catalogFingerprint;
    const cancelableOperation = activeOperationKindRef.current === "read"
      || activeOperationKindRef.current === "preview"
      || activeOperationKindRef.current === "export";
    if (previous !== null && previous !== catalogFingerprint && (serverPreview || cancelableOperation)) {
      invalidateServerPreview();
      if (activeOperationKindRef.current !== "apply") {
        setStatus(t("sharedExcel.status.previewRequired"));
      }
    }
  }, [catalogFingerprint, isApplying, serverPreview, t]);
  const sheetColumnCount = useMemo(() => sheet.reduce((maximum, row) => Math.max(maximum, row.length), 0), [sheet]);
  const sheetTableWidth = 56 + Array.from({ length: sheetColumnCount }, (_, index) => columnWidths[index] ?? 140).reduce((a, b) => a + b, 0);
  const previewRowHeight = 28;
  const previewVisibleCount = 45;
  const previewStartRow = Math.max(0, Math.floor(previewScrollTop / previewRowHeight) - 8);
  const previewEndRow = Math.min(sheet.length, previewStartRow + previewVisibleCount + 16);
  const visibleSheetRows = sheet.slice(previewStartRow, previewEndRow);
  const previewRows = useMemo(() => applyPreviewOptions(
    classifyExcelProductRows(sheet, mapping, importProducts, currentPurchasePrice, startRow),
    valueSources
  ), [currentPurchasePrice, importProducts, mapping, sheet, startRow, valueSources]);
  const [appliedRows, setAppliedRows] = useState<ExcelImportClassifiedRow[] | null>(null);
  const resultRows = appliedRows ?? [];
  const previewMissingRows = previewRows.filter((row) => row.status === "missing");
  const previewPriceChangedRows = previewRows.filter((row) => row.status === "purchasePriceChanged");
  const previewAcceptedRows = previewRows.filter((row) => row.status === "accepted" || row.status === "purchasePriceChanged");
  const previewErrorRows = previewRows.filter((row) => row.status === "error");
  const missingRows = resultRows.filter((row) => row.existence === "MISSING" || row.status === "missing");
  const purchaseChangedNumbers = new Set(serverPreview?.rows.filter((row) => row.purchasePriceChanged).map((row) => row.rowNumber));
  const priceChangedRows = resultRows.filter((row) => row.product && (row.status === "purchasePriceChanged" || purchaseChangedNumbers.has(row.rowNumber)));
  const acceptedRows = resultRows.filter((row) => Boolean(row.product));
  const errorRows = resultRows.filter((row) => row.status === "error");
  const existingRows = acceptedRows;
  const summaryRows = generateSummaryDocument ? (serverPreview?.sourceRows?.map((row) => previewRowToClassifiedRow(row, sheet, mapping, quantityColumn, importProducts)) ?? resultRows) : [];

  const sourceReviewRows = serverPreview?.sourceRows ?? serverPreview?.rows;
  const matchesReviewPanel = (row: ProductExcelImportPreviewRow, panel: SharedExcelImportPanel) => {
    const existing = row.existence === "EXISTING" || Boolean(row.databaseData?.id);
    if (panel === "missing") return row.existence === "MISSING" || row.classification === "MISSING";
    if (panel === "priceChanged") return existing && row.purchasePriceChanged;
    if (panel === "accepted") return existing;
    if (panel === "errors") return row.errors.length > 0 || row.classification === "ERROR";
    return panel === "summary" && generateSummaryDocument;
  };
  const errorKey = (error: ProductExcelImportError) => JSON.stringify([error.code, error.row, error.column, error.attribute]);
  const rowErrorKeys = new Set((sourceReviewRows?.flatMap((row) => row.errors)
    ?? errorRows.flatMap((row) => row.structuredErrors ?? [])).map(errorKey));
  const additionalErrors = serverGlobalErrors.filter((error) => !rowErrorKeys.has(errorKey(error)));
  const reviewCount = (panel: SharedExcelImportPanel, fallback: number) =>
    sourceReviewRows ? sourceReviewRows.filter((row) => matchesReviewPanel(row, panel)).length : fallback;
  const reviewRows = sourceReviewRows ? sourceReviewRows.filter((row) => matchesReviewPanel(row, activePanel)).map((row) => {
    const classified = previewRowToClassifiedRow(row, sheet, mapping, quantityColumn, importProducts);
    return { ...classified, errorTexts: classified.structuredErrors?.length === row.errors.length ? row.errorTexts : undefined };
  })
    : activePanel === "summary" ? summaryRows : activePanel === "missing" ? missingRows
      : activePanel === "priceChanged" ? priceChangedRows : activePanel === "accepted" ? acceptedRows : errorRows;
  const exportColumns = excelReviewColumns(activePanel, context, showOnlyImported, locale, t);
  const reviewColumns = populatedExcelReviewColumns(exportColumns, reviewRows);
  const reviewTitle = t(activePanel === "summary" ? "sharedExcel.result.summary" : activePanel === "missing" ? (autoAddMissing ? "sharedExcel.result.missingAuto" : "sharedExcel.result.missing")
    : activePanel === "priceChanged" ? "sharedExcel.result.purchaseChanged" : activePanel === "accepted" ? "sharedExcel.result.accepted" : "sharedExcel.result.errors");
  const reviewTableRows: ExcelReviewRow[] = reviewRows.map((row) => ({
    id: row.rowNumber, status: row.status,
    values: Object.fromEntries(reviewColumns.map((column) => [column.key, excelReviewValue(row, column.key, locale, t)])),
    changedColumns: excelReviewChangedColumns(row, reviewColumns),
    details: renderStructuredImportErrors(row.structuredErrors ?? row.errors.map((reason) => ({ code: "ROW_INVALID", reason })), t, locale)
  }));
  if (activePanel === "errors") {
    additionalErrors.forEach((error, index) => reviewTableRows.push({ id: -index - 1, sourceRowNumber: error.row ?? undefined, status: "error",
        values: { rowNumber: error.row == null ? "-" : String(error.row), status: t("sharedExcel.status.error"), errors: reviewErrorText([error], t, locale) },
        details: renderStructuredImportErrors([error], t, locale) }));
  }

  if (!open) {
    return null;
  }

  function acceptedRowsWithQuantity(sourceRows: ExcelImportClassifiedRow[]) {
    return sourceRows.map((row) => ({
      ...row,
      // Backend values already include global selections and resolved references.
      draft: row.excelData ? row.draft : applyGlobalExcelValues(row.draft, valueSources),
      ...(context === "STOCK" ? {} : { quantity: row.excelData?.quantity != null
        ? Number(row.excelData.quantity) : quantityFromRow(row.source, quantityColumn, requireQuantity) }),
      updateFields: combinedFamilyUpdateFields(updateFields, row.draft.familyId)
    }));
  }

  function updateSpecialAssignment(field: keyof SharedExcelImportValueSources, source: SharedExcelImportValueSource, value: string) {
    if (field === "taxId") taxDefaultPending.current = false;
    setMapping((current) => ({ ...current, [field]: source === "excel" ? sanitizeExcelColumnLetter(value) : "" }));
    setValueSources((current) => ({ ...current, [field]: { source, value: source === "global" ? value : "" } }));
    invalidateServerPreview();
  }
  function updateUpdateFields(
    updater: (current: Partial<Record<SharedExcelImportUpdateField, boolean>>) => Partial<Record<SharedExcelImportUpdateField, boolean>>
  ) {
    setUpdateFields(updater);
    invalidateServerPreview();
  }

  function currentImportMetadata(sourceRows: ExcelImportClassifiedRow[] = [...acceptedRows, ...priceChangedRows]): SharedExcelImportMetadata {
    const lines = sourceRows.flatMap((row) => {
      if (!row.product?.id) return [];
      const excelValue = (key: string, fallback: string) => (
        row.excelData && Object.hasOwn(row.excelData, key)
          ? row.excelData[key]
          : fallback
      );
      const supplierReference = String(
        row.draft.code.trim() || row.draft.barcode.trim() || ""
      );
      const importedGross = metadataDecimal(excelValue("purchasePrice", ""));
      const currentGross = metadataDecimal(currentPurchasePrice?.(row.product));
      const gross = importedGross ?? currentGross ?? metadataDecimal(row.draft.purchasePrice);
      const discount = metadataDecimal(excelValue("purchaseDiscountPercent", ""))
        ?? metadataDecimal(row.draft.purchaseDiscountPercent)
        ?? 0;
      return [{
        productId: row.product.id,
        rowNumbers: row.rowNumbers?.length ? row.rowNumbers : [row.rowNumber],
        ...(supplierReference ? { supplierReference } : {}),
        ...(gross === undefined ? {} : { grossPurchasePrice: String(gross) }),
        ...(discount === undefined ? {} : { purchaseDiscountPercent: String(discount) })
      }];
    });
    return {
      fileName: selectedFile?.name,
      sha256: serverRead?.sha256,
      sheetName: serverRead?.sheetName,
      lines,
      updateSupplier: supplierAvailable && updateSupplier,
      skipZeroPriceUpdate,
      ...(showDocumentPriceSource ? { documentPriceSource: priceSource } : {}),
      formulas: extractExcelFormulaMetadata(sheet),
    };
  }

  function currentStoredSettings(): SharedExcelImportStoredSettings {
    return {
      useDefaultTax: taxDefaultPending.current,
      mapping,
      quantityColumn,
      startRow,
      updateFields,
      options: {
        autoAddMissing,
        generateSummaryDocument,
        showOnlyImported,
        skipZeroPriceUpdate,
        updateSupplier,
        priceSource,
        valueSources
      }
    };
  }

  function applyStoredOptions(options: SharedExcelImportOptions, useDefaultTax = false) {
    setAutoAddMissing(options.autoAddMissing);
    setGenerateSummaryDocument(options.generateSummaryDocument);
    setShowOnlyImported(options.showOnlyImported);
    setSkipZeroPriceUpdate(options.skipZeroPriceUpdate);
    setUpdateSupplier(options.updateSupplier);
    setPriceSource(options.priceSource);
    taxDefaultPending.current = useDefaultTax && !defaultTax;
    setValueSources(useDefaultTax && defaultTax
      ? { ...options.valueSources, taxId: { source: "global", value: String(defaultTax.value ?? defaultTax.id) } }
      : options.valueSources);
  }

  function operationLabel(operation: ProductExcelImportOperation) {
    if (operation === "CREATE_MISSING") return t("sharedExcel.addProducts");
    if (operation === "UPDATE_PURCHASE_PRICE") return t("sharedExcel.updatePurchase");
    if (operation === "UPDATE_SELECTED_FIELDS") return t("sharedExcel.updateProducts");
    return context === "STOCK" ? t("sharedExcel.addToDraft") : t("sharedExcel.importDocument");
  }

  async function refreshReview(config: ProductExcelImportPreviewConfig) {
    if (!selectedFile) return [];
    const result = await previewProductExcelImport(selectedFile, config, token);
    const rows = result.rows.map((row) => previewRowToClassifiedRow(row, sheet, mapping, quantityColumn, importProducts));
    setServerPreview(result);
    setServerPreviewFingerprint(stableSerialize(config));
    serverPreviewCatalogFingerprintRef.current = catalogFingerprint;
    setServerGlobalErrors(result.errors);
    setAppliedRows(rows);
    return rows;
  }

  async function runOperation(operation: ProductExcelImportOperation, confirmed = false) {
    const config = currentPreviewConfig();
    if (!selectedFile || !config || !serverPreviewIsCurrent || !serverPreview?.previewFingerprint) {
      setStatus(t("sharedExcel.status.previewRequired"));
      return;
    }
    const targets = operation === "CREATE_MISSING" ? missingRows
      : operation === "UPDATE_PURCHASE_PRICE" ? priceChangedRows : existingRows;
    if (!targets.length) return;
    if (operation !== "PREPARE_DESTINATION" && !confirmed) {
      setPendingOperation(operation);
      setMasterConfirmationCount(targets.length);
      return;
    }
    const sequence = beginExclusiveOperation("apply");
    if (sequence === null) return;
    let committed = false;
    setStatus(operationLabel(operation));
    try {
      const result = await applyProductExcelImport(selectedFile, {
        preview: config,
        operation,
        expectedPreviewFingerprint: serverPreview.previewFingerprint,
        expectedConcurrencyTokens: {},
        autoAddMissing: operation === "CREATE_MISSING",
        confirmMasterChanges: operation !== "PREPARE_DESTINATION",
        warehouseId,
        documentDate,
        supplierId: supplier?.id ?? null,
        updateSupplier: supplierAvailable && updateSupplier
      }, token);
      if (result.errors.length) {
        setServerGlobalErrors(result.errors);
        setActivePanel("errors");
        setStatus(t("sharedExcel.status.blockedErrors"));
        return;
      }
      if (operation === "PREPARE_DESTINATION") {
        if (context === "WAREHOUSE_INPUT" && (!result.warehouseMetadata || !result.warehouseProvenanceToken)) {
          setStatus(t("sharedExcel.status.applyError"));
          return;
        }
        const byRow = new Map(result.rows.map((row) => [row.rowNumber, row]));
        const rows = serverPreview.rows.flatMap((row) => {
          const applied = byRow.get(row.rowNumber);
          if (!applied?.productId) return [];
          const classified = previewRowToClassifiedRow(row, sheet, mapping, quantityColumn, importProducts);
          return [{ ...classified, product: { ...classified.product, id: applied.productId } }];
        });
        const metadata: SharedExcelImportMetadata = context === "WAREHOUSE_INPUT"
          ? { ...result.warehouseMetadata!, provenanceToken: result.warehouseProvenanceToken!,
              documentPriceSource: priceSource,
              formulas: result.warehouseMetadata!.formulas.map((formula) => ({ ...formula, calculatedValue: formula.calculatedValue ?? null })) }
          : currentImportMetadata(rows);
        onImportAccepted(acceptedRowsWithQuantity(rows), metadata);
        onClose();
      } else {
        committed = true;
        const bindings = { ...resolvedProducts };
        if (operation === "CREATE_MISSING") {
          for (const row of result.rows) if (row.productId) {
            for (const number of row.rowNumbers) bindings[number] = row.productId;
          }
          setResolvedProducts(bindings);
        }
        await refreshReview({ ...config, resolvedProducts: bindings });
        setStatus(interpolateMessage(t("sharedExcel.status.savedProducts"), { count: result.appliedCount }));
      }
    } catch (error) {
      if (committed) {
        clearServerPreview();
        setStatus(t("sharedExcel.status.savedRefreshFailed"));
      } else {
        setServerGlobalErrors(productExcelImportErrorsFromApi(error));
        setActivePanel("errors");
        setStatus(t("sharedExcel.status.applyError"));
      }
    } finally {
      endExclusiveOperation(sequence);
    }
  }

  function addProducts() {
    if (!serverPreviewIsCurrent || !selectedFile) {
      setStatus(t("sharedExcel.status.previewRequired"));
      return;
    }
    if (autoAddMissing) void runOperation("CREATE_MISSING");
    else setManualQueue(missingRows);
  }

  async function manualProductCreated(product: ProductCreateResponse) {
    const current = manualQueue[0];
    const config = currentPreviewConfig();
    if (!current || !config) return;
    const remaining = manualQueue.slice(1);
    manualSaveInFlightRef.current = true;
    setManualQueue([]);
    const bindings = { ...resolvedProducts };
    for (const number of current.rowNumbers ?? [current.rowNumber]) bindings[number] = product.id;
    setResolvedProducts(bindings);
    const sequence = beginExclusiveOperation("apply");
    try {
      const refreshed = await refreshReview({ ...config, resolvedProducts: bindings });
      const pending = new Set(refreshed.filter((row) => row.existence === "MISSING" || row.status === "missing").map((row) => row.rowNumber));
      setManualQueue(remaining.filter((row) => pending.has(row.rowNumber)));
      setStatus(t("sharedExcel.status.manualSaved"));
    } catch {
      clearServerPreview();
      setStatus(t("sharedExcel.status.savedRefreshFailed"));
    } finally {
      manualSaveInFlightRef.current = false;
      if (sequence !== null) endExclusiveOperation(sequence);
    }
  }

  function clearMapping() {
    if (applyInFlightRef.current) return;
    const defaults = defaultExcelImportOptions();
    setMapping({});
    setQuantityColumn("");
    setStartRow(2);
    setUpdateFields({});
    applyStoredOptions(defaults, true);
    clearStoredExcelImportSettings(terminalContext);
    invalidateServerPreview();
    setStatus(t("sharedExcel.status.cleared"));
  }

  function clearFile() {
    if (applyInFlightRef.current) return;
    setSheet([]);
    setLocalFile(null);
    setMapping({});
    setQuantityColumn("");
    setStartRow(2);
    setUpdateFields({});
    if (fileInputRef.current) {
      fileInputRef.current.value = "";
    }
    invalidateServerPreview();
    setActivePanel("mapping");
    setEditingSheet(false);
    setColumnWidths({});
    setServerRead(null);
    setServerPreview(null);
    setServerGlobalErrors([]);
    setStatus(t("sharedExcel.status.fileCleared"));
  }

  function toggleSheetEditing() {
    if (applyInFlightRef.current) return;
    if (editingSheet) {
      setEditingSheet(false);
      setStatus(t("sharedExcel.status.editsSaved"));
      return;
    }
    setEditingSheet(true);
    setStatus("");
  }

  async function applyMapping() {
    const operationSequence = beginExclusiveOperation("preview");
    if (operationSequence === null) return;
    try {
      saveStoredExcelImportSettings(terminalContext, currentStoredSettings());
      if (selectedFile && serverRead) {
        const operationFile = selectedFile;
        const operationSheet = sheet;
        const operationMapping = mapping;
        const operationQuantityColumn = quantityColumn;
        const operationProducts = importProducts;
        const operationCatalogFingerprint = catalogFingerprint;
        const operationConfig = currentPreviewConfig();
        setStatus(t("sharedExcel.status.previewing"));
        try {
          const config = operationConfig;
          if (!config) return;
          const result = await previewProductExcelImport(operationFile, config, token);
          if (operationSequenceRef.current !== operationSequence) return;
          const nextRows = result.rows.map((row) => previewRowToClassifiedRow(
            row, operationSheet, operationMapping, operationQuantityColumn, operationProducts
          ));
          const nextMissingRows = nextRows.filter((row) => row.status === "missing");
          const nextPriceChangedRows = nextRows.filter((row) => row.status === "purchasePriceChanged");
          const nextAcceptedRows = nextRows.filter((row) => row.status === "accepted" || row.status === "purchasePriceChanged");
          const nextErrorRows = nextRows.filter((row) => row.status === "error");
          const globalErrors = result.errors;
          setServerPreview(result);
          setServerPreviewFingerprint(stableSerialize(config));
          serverPreviewCatalogFingerprintRef.current = operationCatalogFingerprint;
          setServerGlobalErrors(globalErrors);
          setAppliedRows(nextRows);
          setActivePanel(nextErrorRows.length > 0 || globalErrors.length > 0
            ? "errors"
            : nextMissingRows.length > 0
              ? "missing"
              : nextPriceChangedRows.length > 0
                ? "priceChanged"
                : nextAcceptedRows.length > 0
                  ? "accepted"
                  : "errors");
          setStatus(nextErrorRows.length > 0 || globalErrors.length > 0
            ? t("sharedExcel.status.blockedErrors")
            : interpolateMessage(t("sharedExcel.status.applied"), {
                accepted: originalRowCount(nextAcceptedRows),
                missing: originalRowCount(nextMissingRows),
                changed: originalRowCount(nextPriceChangedRows),
                errors: originalRowCount(nextErrorRows) + globalErrors.length
              }));
        } catch (error) {
          if (operationSequenceRef.current !== operationSequence) return;
          const structured = productExcelImportErrorsFromApi(error);
          setServerPreview(null);
          setServerPreviewFingerprint(null);
          setServerGlobalErrors(structured);
          setAppliedRows(null);
          setActivePanel("errors");
          setStatus(structured[0]?.reason ?? t("sharedExcel.status.readError"));
        }
        return;
      }
      if (previewErrorRows.length > 0) {
        setAppliedRows(previewRows);
        setActivePanel("errors");
        setStatus(t("sharedExcel.status.blockedErrors"));
        return;
      }
      const nextRows = previewRows;
      if (operationSequenceRef.current !== operationSequence) return;
      const nextMissingRows = nextRows.filter((row) => row.status === "missing");
      const nextPriceChangedRows = nextRows.filter((row) => row.status === "purchasePriceChanged");
      const nextAcceptedRows = nextRows.filter((row) => row.status === "accepted" || row.status === "purchasePriceChanged");
      const nextErrorRows = nextRows.filter((row) => row.status === "error");
      setAppliedRows(nextRows);
      setActivePanel(nextMissingRows.length > 0
        ? "missing"
        : nextPriceChangedRows.length > 0
          ? "priceChanged"
          : nextAcceptedRows.length > 0
            ? "accepted"
            : "errors");
      setStatus(interpolateMessage(t("sharedExcel.status.applied"), {
        accepted: originalRowCount(nextAcceptedRows),
        missing: originalRowCount(nextMissingRows),
        changed: originalRowCount(nextPriceChangedRows),
        errors: originalRowCount(nextErrorRows)
      }));
    } finally {
      endExclusiveOperation(operationSequence);
    }
  }

  async function exportReview(panel: SharedExcelImportPanel = activePanel) {
    const raw = panel === "mapping";
    const errorReport = panel === "errors";
    const config: ProductExcelImportPreviewConfig | null = currentPreviewConfig() ?? (errorReport ? {
      mapping: {}, edits: [], expectedSha256: "", startRow, updateFields: {},
      options: { context, globalValues: {}, valueSources: {}, showOnlyImported: false,
        skipZeroPriceUpdate, requireQuantity: context !== "STOCK" && requireQuantity }
    } : null);
    if (!selectedFile || !config || (!raw && !errorReport && !serverPreviewIsCurrent)) {
      setStatus(t("sharedExcel.status.previewRequired"));
      return;
    }
    const fingerprint = exportablePreviewFingerprint(serverPreview);
    if (!raw && !errorReport && !fingerprint) { setStatus(t("sharedExcel.status.previewRequired")); return; }
    const sequence = beginExclusiveOperation("export");
    if (sequence === null) return;
    setStatus(t("sharedExcel.status.exporting"));
    try {
      // Error reports contain only the visible review snapshot; never upload the rejected workbook again.
      const exportFile = errorReport ? new File([], selectedFile.name) : selectedFile;
      const download = await exportProductExcelImportSummary(exportFile,
        { ...config, options: { ...config.options, showOnlyImported: panel === "summary" && showOnlyImported } },
        fingerprint ?? "", locale, token, undefined,
        { view: exportViewForPanel[panel], columns: raw ? [] : exportColumns.map((column) => column.key),
          ...(errorReport ? { errorRows: reviewTableRows.map((row) => row.values) } : {}) });
      if (operationSequenceRef.current !== sequence) return;
      downloadSummaryBlob(download.blob, download.fileName);
      setStatus(t("sharedExcel.status.exported"));
    } catch (error) {
      if (operationSequenceRef.current !== sequence) return;
      const structured = productExcelImportErrorsFromApi(error);
      setServerGlobalErrors(structured);
      setActivePanel("errors");
      setStatus(t("sharedExcel.status.exportError"));
    } finally {
      endExclusiveOperation(sequence);
    }
  }
  function startDragScroll(event: PointerEvent<HTMLDivElement>) {
    dragScrollMovedRef.current = false;
    if (event.button !== 0) {
      return;
    }
    const target = event.target;
    if (target instanceof Element && target.closest("button,input,select,textarea,a")) {
      return;
    }
    const element = event.currentTarget;
    dragScrollRef.current = {
      element,
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      scrollLeft: element.scrollLeft,
      scrollTop: element.scrollTop
    };
    element.dataset.dragging = "true";
  }

  function moveDragScroll(event: PointerEvent<HTMLDivElement>) {
    const drag = dragScrollRef.current;
    if (!drag || drag.pointerId !== event.pointerId) {
      return;
    }
    const deltaX = event.clientX - drag.startX;
    const deltaY = event.clientY - drag.startY;
    if (Math.abs(deltaX) > 2 || Math.abs(deltaY) > 2) {
      dragScrollMovedRef.current = true;
      drag.element.setPointerCapture?.(event.pointerId);
      event.preventDefault();
    }
    drag.element.scrollLeft = drag.scrollLeft - deltaX;
    drag.element.scrollTop = drag.scrollTop - deltaY;
  }

  function endDragScroll(event: PointerEvent<HTMLDivElement>) {
    const drag = dragScrollRef.current;
    if (!drag || drag.pointerId !== event.pointerId) {
      return;
    }
    delete drag.element.dataset.dragging;
    if (drag.element.hasPointerCapture?.(event.pointerId)) {
      drag.element.releasePointerCapture(event.pointerId);
    }
    dragScrollRef.current = null;
  }

  return (
    <>
    <div className="filter-overlay shared-excel-overlay" role="dialog" aria-modal="true" aria-busy={isApplying} aria-labelledby="shared-excel-title"
      aria-hidden={closeConfirmationOpen || undefined} inert={closeConfirmationOpen || undefined}>
      <section className="filter-dialog shared-excel-dialog">
        <header className="shared-excel-toolbar">
          <input
            ref={fileInputRef}
            type="file"
            accept={excelImportAccept}
            className="shared-excel-file-input"
            disabled={isApplying}
            onChange={(event) => {
              if (applyInFlightRef.current) return;
              setLocalFile(event.currentTarget.files?.[0] ?? null);
              setSheet([]);
              setServerRead(null);
              setServerGlobalErrors([]);
              setPreviewScrollTop(0);
              autoDetectedSourceRef.current = null;
              setEditingSheet(false);
              invalidateServerPreview();
              setActivePanel("mapping");
            }}
          />
          <div className="shared-excel-title">
            <h2 id="shared-excel-title">{title ?? t("stock.bulkEdit.importExcel")}</h2>
            <span>{selectedFile?.name ?? excelImportAccept}</span>
          </div>
          <div className="shared-excel-toolbar-actions">
            <button type="button" disabled={isApplying} onClick={() => {
              if (fileInputRef.current) {
                fileInputRef.current.value = "";
                fileInputRef.current.click();
              }
            }}>{t("sharedExcel.open")}</button>
            <button
              type="button"
              disabled={isApplying || sheet.length === 0}
              aria-pressed={editingSheet}
              onClick={toggleSheetEditing}
            >
              {t(editingSheet ? "sharedExcel.finishEdit" : "sharedExcel.openEdit")}
            </button>
            <button type="button" disabled={isApplying} onClick={clearFile}>{t("sharedExcel.clearFile")}</button>
            {activePanel === "mapping" && <button type="button" disabled={isApplying || !serverRead || !selectedFile} onClick={() => void exportReview("mapping")}>{t("sharedExcel.export")}</button>}
            <button type="button" disabled={isApplying} onClick={handleClose}>{t("sharedExcel.back")}</button>
          </div>
        </header>

        <div className="shared-excel-top-pane">
          {cellDiagnostics.count > 0 && <div className="shared-excel-cell-alert" role="alert">
            <strong>{interpolateMessage(t("sharedExcel.cellErrors.title"), { count: cellDiagnostics.count })}</strong>
            <ul tabIndex={0} aria-label={t("sharedExcel.cellErrors.cells")}>
              {cellDiagnostics.errors.map((error, index) => <li key={index}>
                <strong>{excelColumnIndexToLetter(error.column! - 1)}{error.row}</strong>
                {" — "}{error.receivedValue}{" · "}{localizedImportErrorText(error, "reason", t, locale)}
              </li>)}
            </ul>
            {cellDiagnostics.count > cellDiagnostics.errors.length && <span>{interpolateMessage(t("sharedExcel.cellErrors.more"), {
              shown: cellDiagnostics.errors.length, count: cellDiagnostics.count
            })}</span>}
            <p>{t("sharedExcel.cellErrors.fix")}</p>
          </div>}
          {sheet.length === 0 ? (
            <div className="shared-excel-empty-preview">
              <h3>{t("sharedExcel.summary.title")}</h3>
              <p>{t("sharedExcel.flowHelp")}</p>
            </div>
          ) : (
            <div className="shared-excel-frozen-table shared-excel-raw-table">
            <div className="shared-excel-fixed-header" ref={previewHeader.header} onScroll={previewHeader.syncBody}>
              <table role="presentation" style={{ width: sheetTableWidth }}>
                <colgroup>
                  <col style={{ width: 56 }} />
                  {Array.from({ length: sheetColumnCount }).map((_, index) => (
                    <col key={index} style={{ width: columnWidths[index] ?? 140 }} />
                  ))}
                </colgroup>
                <thead>
                  <tr>
                    <th>#</th>
                    {Array.from({ length: sheetColumnCount }).map((_, index) => {
                      const key = excelColumnIndexToLetter(index);
                      return (
                        <TableLayoutHeaderCell
                          key={key}
                          column={{ key, width: columnWidths[index] ?? 140, visible: true }}
                          movable={false}
                          resizable
                          showColumnMenu={false}
                          resizeLabel={interpolateMessage(t("sharedExcel.resizeColumn"), { column: key })}
                          onReorder={() => undefined}
                          onMove={() => undefined}
                          onResize={(_, width) => setColumnWidths((current) => ({
                            ...current,
                            [index]: Math.min(640, Math.max(72, width))
                          }))}
                        >
                          {key}
                        </TableLayoutHeaderCell>
                      );
                    })}
                  </tr>
                </thead>
              </table>
            </div>
            <div
              ref={previewViewport}
              className={`shared-excel-preview${editingSheet ? " shared-excel-preview--editing" : ""}`}
              tabIndex={0} aria-label={t("sharedExcel.tab.configuration")}
              onPointerCancel={endDragScroll}
              onPointerDown={startDragScroll}
              onPointerMove={moveDragScroll}
              onPointerUp={endDragScroll}
              onScroll={(event) => { setPreviewScrollTop(event.currentTarget.scrollTop); previewHeader.syncHeader(); }}
              onKeyDown={(event) => {
                if ((event.target instanceof Element && event.target.closest("input")) || !["ArrowDown", "ArrowUp"].includes(event.key)) return;
                event.preventDefault();
                setSelectedSourceRow((current) => Math.max(1, Math.min(sheet.length, (current ?? 1) + (event.key === "ArrowDown" ? 1 : -1))));
              }}
            >
              <table aria-label={t("sharedExcel.tab.configuration")} aria-rowcount={sheet.length + 1} style={{ width: sheetTableWidth }}>
                <colgroup><col style={{ width: 56 }} />{Array.from({ length: sheetColumnCount }, (_, index) =>
                  <col key={index} style={{ width: columnWidths[index] ?? 140 }} />)}</colgroup>
                <thead className="shared-excel-body-labels"><tr><th scope="col">#</th>
                  {Array.from({ length: sheetColumnCount }, (_, index) => <th scope="col" key={index}>{excelColumnIndexToLetter(index)}</th>)}
                </tr></thead>
                <tbody>
                  {previewStartRow > 0 && <tr aria-hidden="true"><td colSpan={sheetColumnCount + 1} style={{ height: previewStartRow * previewRowHeight, padding: 0 }} /></tr>}
                  {visibleSheetRows.map((row, visibleIndex) => {
                    const rowIndex = previewStartRow + visibleIndex;
                    return <tr key={rowIndex} aria-selected={selectedSourceRow === rowIndex + 1}
                      data-source-row={rowIndex + 1} onClick={() => { if (!dragScrollMovedRef.current) setSelectedSourceRow(rowIndex + 1); }}>
                      <th scope="row"><button type="button" className="shared-excel-source-row-button"
                        aria-label={`${t("sharedExcel.column.row")} Excel ${rowIndex + 1}`}
                        onClick={() => setSelectedSourceRow(rowIndex + 1)}>{rowIndex + 1}</button></th>
                      {Array.from({ length: sheetColumnCount }).map((_, cellIndex) => (
                        <td key={cellIndex} className={serverRead?.rows[rowIndex]?.[cellIndex]?.errorCode
                          && excelCellText(row[cellIndex]) === serverRead.rows[rowIndex][cellIndex].value ? "shared-excel-cell-error" : undefined}>
                          {editingSheet ? (
                            <input
                              className="shared-excel-cell-input"
                              aria-label={`${excelColumnIndexToLetter(cellIndex)}${rowIndex + 1}`}
                              size={Math.min(50, Math.max(8, excelCellText(row[cellIndex]).length))}
                              value={excelCellText(row[cellIndex])}
                              onChange={(event) => {
                                if (applyInFlightRef.current) return;
                                setSheet((current) => updateExcelSheetCell(
                                  current,
                                  rowIndex,
                                  cellIndex,
                                  event.target.value
                                ));
                                invalidateServerPreview();
                              }}
                            />
                          ) : excelCellText(row[cellIndex])}
                        </td>
                      ))}
                    </tr>
                  })}
                  {previewEndRow < sheet.length && <tr aria-hidden="true"><td colSpan={sheetColumnCount + 1} style={{ height: (sheet.length - previewEndRow) * previewRowHeight, padding: 0 }} /></tr>}
                </tbody>
              </table>
            </div>
            </div>
          )}
        </div>

        <div className="shared-excel-body">
          {activePanel === "mapping" ? (
            <div className="shared-excel-config">
              <div className="shared-excel-config-bar">
                <label>
                  <span>{t("sharedExcel.startRow")}</span>
                    <input
                      type="number"
                      min={2}
                      disabled={isApplying}
                    value={startRow}
                    onChange={(event) => {
                      setStartRow(Math.max(2, Number(event.target.value) || 2));
                      invalidateServerPreview();
                    }}
                  />
                </label>
                <span>{interpolateMessage(t("sharedExcel.detectedRows"), {
                  count: serverPreviewIsCurrent && serverPreview ? serverPreview.detectedRows : previewRows.length
                })}</span>
                <span>{interpolateMessage(t("sharedExcel.acceptedRows"), {
                  count: serverPreviewIsCurrent ? originalRowCount(resultRows.filter(row => row.status === "accepted" || row.status === "purchasePriceChanged")) : previewAcceptedRows.length
                })}</span>
                <span>{interpolateMessage(t("sharedExcel.missingRows"), {
                  count: serverPreviewIsCurrent ? originalRowCount(missingRows) : previewMissingRows.length
                })}</span>
              </div>
              <div className="shared-excel-options">
                {<label><input type="checkbox" disabled={isApplying} checked={autoAddMissing} onChange={(event) => setAutoAddMissing(event.target.checked)} /> {t("sharedExcel.option.autoAdd")}</label>}
                <label><input type="checkbox" disabled={isApplying} checked={generateSummaryDocument} onChange={(event) => setGenerateSummaryDocument(event.target.checked)} /> {t("sharedExcel.option.summaryDocument")}</label>
                <label>
                  <input
                    type="checkbox"
                    disabled={isApplying}
                    checked={showOnlyImported}
                    onChange={(event) => setShowOnlyImported(event.target.checked)}
                  /> {t("sharedExcel.option.onlyImported")}
                </label>
                {<label><input type="checkbox" disabled={isApplying} checked={skipZeroPriceUpdate} onChange={(event) => { setSkipZeroPriceUpdate(event.target.checked); invalidateServerPreview(); }} /> {t("sharedExcel.option.skipZeroPrice")}</label>}
                {context === "WAREHOUSE_INPUT" && <>
                  <label className={!supplierAvailable ? "shared-excel-option-disabled" : undefined}>
                    <input
                      type="checkbox"
                      checked={supplierAvailable && updateSupplier}
                      disabled={isApplying || !supplierAvailable}
                    onChange={(event) => setUpdateSupplier(event.target.checked)}
                    /> {t("sharedExcel.option.updateSupplier")}
                  </label>
                  <div className="shared-excel-supplier-context" role="note">
                    {supplierAvailable && supplier
                      ? [supplier.code, supplier.legalName || supplier.tradeName, [supplier.documentType, supplier.documentNumber].filter(Boolean).join(" ")]
                          .filter(Boolean).join(" · ")
                      : t("sharedExcel.option.updateSupplierUnavailable")}
                  </div>
                </>}
                {showDocumentPriceSource && <label>
                  <span>{t("sharedExcel.priceSource")}</span>
                  <ErpSelect disabled={isApplying} aria-label={t("sharedExcel.priceSource")} value={priceSource} options={[
                    { value: "purchasePrice", label: t("sharedExcel.price.purchase") },
                    { value: "salePrice", label: t("sharedExcel.price.sale") },
                    { value: "memberPrice", label: t("sharedExcel.price.member") },
                    { value: "wholesalePrice", label: t("sharedExcel.price.wholesale") },
                    { value: "offerPrice", label: t("sharedExcel.price.offer") }
                  ]} onChange={(value) => {
                    setPriceSource(value as SharedExcelImportPriceSource);
                    invalidateServerPreview();
                  }} />
                </label>}
              </div>
              <p className="shared-excel-mapping-hint">{t("sharedExcel.mappingOrderHint")}</p>
                <div className="shared-excel-mapping" role="group" aria-label={t("sharedExcel.mappingOrderHint")}>
                {sharedExcelFieldGroups.flatMap((group, groupIndex) => group.filter((field) => context !== "STOCK" || field.key !== "quantity").map((field, rowIndex) => (
                  <div key={field.key} className="shared-excel-mapping-row" role="group" aria-label={fieldLabel(field, locale)}
                    style={{ gridColumn: groupIndex + 1, gridRow: rowIndex + 1 }}>
                    <span className="shared-excel-mapping-update">
                      {field.updateKey ? renderUpdateCheckbox(field.updateKey, fieldLabel(field, locale), updateFields, updateUpdateFields, t, isApplying) : null}
                    </span>
                    {specialValueField(field.key) ? (() => {
                      const key = field.key;
                      const setting = valueSources[key];
                      const options = specialValueOptions(key, effectiveTaxOptions, t);
                      const option = setting.source === "global" ? options.find((item) => item.value === setting.value) : undefined;
                      return <div className="shared-excel-value-control">
                        <ErpSelect
                          disabled={isApplying}
                          value={setting.source === "global" ? setting.value : ""}
                          options={options}
                          aria-label={fieldLabel(field, locale)}
                          editable={{
                            text: setting.source === "excel" ? mapping[key] ?? "" : option?.shortLabel ?? setting.value,
                            label: `${fieldLabel(field, locale)} ${t("sharedExcel.columnOrValue")}`,
                            onChange: (text) => updateSpecialAssignment(key, "excel", text)
                          }}
                          onChange={(value) => updateSpecialAssignment(key, "global", value)}
                        />
                      </div>;
                    })() : (
                      <input
                        disabled={isApplying}
                        type="text"
                        inputMode="text"
                        maxLength={2}
                        pattern="[A-Za-z]+"
                        aria-label={`${fieldLabel(field, locale)} ${t("sharedExcel.columnLetter")}`}
                        value={field.key === "quantity" ? quantityColumn : mapping[field.key] ?? ""}
                        onChange={(event) => {
                          const value = sanitizeExcelColumnLetter(event.target.value);
                          if (field.key === "quantity") setQuantityColumn(value);
                          else setMapping((current) => ({ ...current, [field.key]: value }));
                          invalidateServerPreview();
                        }}
                      />
                    )}
                    <span className="shared-excel-mapping-name">
                      {fieldLabel(field, locale)}
                      {field.key === "taxId" && !effectiveTaxOptions.length && <small className="shared-excel-date-hint">{t("sharedExcel.noTaxesAvailable")}</small>}
                      {field.key === "familyId" && (
                        <small className="shared-excel-date-hint">{locale === "zh"
                          ? "3位：类别（清除子类别）；6位：类别和子类别"
                          : locale === "en" ? "3 digits: family (clears subfamily); 6: family and subfamily"
                            : "3 dígitos: familia (quita subfamilia); 6: familia y subfamilia"}</small>
                      )}
                      {(field.key === "offerFrom" || field.key === "offerUntil") && (
                        <small className="shared-excel-date-hint">{t("sharedExcel.dateFormatHint")}</small>
                      )}
                    </span>
                  </div>
                )))}
              </div>
              <div className="shared-excel-config-actions">
                <button type="button" disabled={isApplying} onClick={clearMapping}>{t("sharedExcel.clearConfiguration")}</button>
                <button type="button" disabled={isApplying} onClick={() => void applyMapping()}>{t("common.apply")}</button>
              </div>
            </div>
          ) : (
            <div className="shared-excel-results">
              <ExcelImportReviewTable
                key={activePanel + ":" + (serverRead?.sha256 ?? "local")}
                title={reviewTitle}
                columns={reviewColumns}
                rows={reviewTableRows}
                selectedRowId={reviewTableRows.find((row) => (row.sourceRowNumber ?? row.id) === selectedSourceRow)?.id ?? null}
                onSelectRow={(id) => {
                  const sourceRow = reviewTableRows.find((row) => row.id === id)?.sourceRowNumber ?? id;
                  if (sourceRow > 0 && sourceRow <= sheet.length) setSelectedSourceRow(sourceRow);
                }}
                exportDisabled={isApplying || !selectedFile || (activePanel !== "errors" && !serverPreviewIsCurrent) || (activePanel === "summary" && !generateSummaryDocument)}
                onExport={() => void exportReview()}
                labels={{ export: t("sharedExcel.export"), empty: t("sharedExcel.emptyRows"), review: t("sharedExcel.reviewRow"),
                  ...((activePanel === "summary" || activePanel === "accepted") ? { comparison: {
                    current: t("sharedExcel.column.databaseData"), excel: t("sharedExcel.column.excelData"),
                    changed: t("sharedExcel.review.changed"), hint: t("sharedExcel.review.changeHint")
                  } } : {}),
                  resize: (column) => interpolateMessage(t("sharedExcel.resizeColumn"), { column }) }}
                actions={activePanel === "missing"
                  ? <button type="button" disabled={isApplying || !serverPreviewIsCurrent || !missingRows.length} onClick={addProducts}>{t("sharedExcel.addProducts")}</button>
                  : activePanel === "priceChanged"
                    ? <button type="button" disabled={isApplying || !serverPreviewIsCurrent || !updateFields.purchasePrice || !priceChangedRows.length} onClick={() => void runOperation("UPDATE_PURCHASE_PRICE")}>{t("sharedExcel.updatePurchase")}</button>
                    : activePanel === "accepted" ? <>
                      <button type="button" disabled={isApplying || !serverPreviewIsCurrent || !existingRows.length || !Object.values(updateFields).some(Boolean)} onClick={() => void runOperation("UPDATE_SELECTED_FIELDS")}>{t("sharedExcel.updateProducts")}</button>
                      <button type="button" disabled={isApplying || !serverPreviewIsCurrent || !existingRows.length} onClick={() => void runOperation("PREPARE_DESTINATION")}>{operationLabel("PREPARE_DESTINATION")}</button>
                    </> : null}
              />
            </div>
          )}
        </div>

        {status && <p className="shared-excel-status" role="status">{status}</p>}

        <nav className="shared-excel-bottom-tabs" aria-label={t("sharedExcel.sections")}>
          {renderPanelTab("mapping", t("sharedExcel.tab.configuration"), activePanel, setActivePanel)}
          {renderPanelTab("summary", interpolateMessage(t("sharedExcel.tab.summary"), { count: summaryRows.length }), activePanel, setActivePanel)}
          {renderPanelTab("missing", interpolateMessage(t("sharedExcel.tab.missing"), { count: reviewCount("missing", missingRows.length) }), activePanel, setActivePanel)}
          {renderPanelTab("priceChanged", interpolateMessage(t("sharedExcel.tab.purchaseChanged"), { count: reviewCount("priceChanged", priceChangedRows.length) }), activePanel, setActivePanel)}
          {renderPanelTab("accepted", interpolateMessage(t("sharedExcel.tab.accepted"), { count: reviewCount("accepted", acceptedRows.length) }), activePanel, setActivePanel)}
          {renderPanelTab("errors", interpolateMessage(t("sharedExcel.tab.errors"), { count: reviewCount("errors", errorRows.length) + additionalErrors.length }), activePanel, setActivePanel)}
        </nav>
        {masterConfirmationCount !== null && (
          <div className="shared-excel-confirm-overlay" role="presentation" onPointerDown={(event) => event.stopPropagation()}>
            <div ref={confirmationDialogRef} className="shared-excel-confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby="shared-excel-confirm-title">
              <h2 id="shared-excel-confirm-title">{pendingOperation ? operationLabel(pendingOperation) : t("sharedExcel.updateProducts")}</h2>
              <p>{interpolateMessage(t("sharedExcel.confirmMasterChanges"), { count: masterConfirmationCount })}</p>
              <div className="shared-excel-confirm-actions">
                <button type="button" autoFocus onClick={() => setMasterConfirmationCount(null)}>{t("common.cancel")}</button>
                <button type="button" onClick={() => {
                  setMasterConfirmationCount(null);
                  if (pendingOperation) void runOperation(pendingOperation, true);
                }}>{pendingOperation ? operationLabel(pendingOperation) : t("sharedExcel.updateProducts")}</button>
              </div>
            </div>
          </div>
        )}
      </section>
      {manualQueue[0] && <ProductCreateDialog
        key={manualQueue[0].rowNumber}
        open
        locale={locale}
        token={token}
        initialForm={productFormFromExcelDraft(manualQueue[0].draft)}
        onCreated={(product) => { void manualProductCreated(product); }}
        onClose={() => { if (!manualSaveInFlightRef.current) setManualQueue([]); }}
      />}
    </div>
    {closeConfirmationOpen && <div className="app-venta-home-confirm-overlay" role="presentation">
      <section ref={closeConfirmationRef} className="app-venta-home-confirm-dialog" role="alertdialog" aria-modal="true"
        aria-labelledby="shared-excel-close-title" aria-describedby="shared-excel-close-message"
        onKeyDown={(event) => event.stopPropagation()}>
        <header><h2 id="shared-excel-close-title">{t("sharedExcel.close.title")}</h2></header>
        <p id="shared-excel-close-message">{t("sharedExcel.close.message")}</p>
        <footer>
          <button type="button" onClick={cancelClose}>{t("common.cancel")}</button>
          <button type="button" className="primary" onClick={confirmClose}>{t("sharedExcel.close.confirm")}</button>
        </footer>
      </section>
    </div>}
    </>
  );
}

function originalRowCount(rows: ExcelImportClassifiedRow[]): number {
  return rows.reduce((count, row) => count + (row.rowNumbers?.length || 1), 0);
}

function field(
  key: MappingField["key"],
  es: string,
  en: string,
  zh: string,
  aliases: string[],
  updateKey?: SharedExcelImportUpdateField
): MappingField {
  return {
    key,
    label: es,
    translatedLabels: { es, en, zh },
    aliases,
    updateKey
  };
}

function fieldLabel(field: MappingField, locale: LocaleCode) {
  return field.translatedLabels?.[locale] ?? field.label;
}

export function detectExcelHeaderMapping(sheet: ExcelSheet, context?: ProductExcelImportContext): {
  mapping: ExcelColumnMapping;
  quantityColumn: string;
  updateFields: Partial<Record<SharedExcelImportUpdateField, boolean>>;
} {
  const headers = (sheet[0] ?? []).map(excelCellText);
  const mapping: ExcelColumnMapping = {};
  const updateFields: Partial<Record<SharedExcelImportUpdateField, boolean>> = {};
  let quantityColumn = "";

  sharedExcelFieldOrder.forEach((mappingField) => {
    if (context === "STOCK" && mappingField.key === "quantity") return;
    const columnIndex = findExcelColumn(headers, mappingField.aliases);
    if (columnIndex < 0) {
      return;
    }
    const column = excelColumnIndexToLetter(columnIndex);
    if (mappingField.key === "quantity") {
      quantityColumn = column;
    } else {
      mapping[mappingField.key] = column;
    }
    if (mappingField.updateKey) {
      updateFields[mappingField.updateKey] = true;
      if (mappingField.updateKey === "familyId") updateFields.subfamilyId = true;
    }
  });

  return { mapping, quantityColumn, updateFields };
}

export function changedExcelCells(
  original: ProductExcelImportReadResult,
  current: ExcelSheet
) {
  const edits: Array<{ row: number; column: string; value: string }> = [];
  const rowCount = Math.max(original.rows.length, current.length);
  for (let rowIndex = 0; rowIndex < rowCount; rowIndex += 1) {
    const columnCount = Math.max(original.rows[rowIndex]?.length ?? 0, current[rowIndex]?.length ?? 0);
    for (let columnIndex = 0; columnIndex < columnCount; columnIndex += 1) {
      const before = original.rows[rowIndex]?.[columnIndex]?.value ?? "";
      const after = excelCellText(current[rowIndex]?.[columnIndex]);
      if (before !== after) {
        edits.push({
          row: rowIndex + 1,
          column: excelColumnIndexToLetter(columnIndex),
          value: after
        });
      }
    }
  }
  return edits;
}

export function previewRowToClassifiedRow(
  row: ProductExcelImportPreviewRow,
  sheet: ExcelSheet,
  mapping: ExcelColumnMapping,
  quantityColumn: string,
  products: readonly ExcelImportProductIdentity[] = []
): ExcelImportClassifiedRow {
  const source = [...(sheet[row.rowNumber - 1] ?? [])];
  const quantityIndex = excelColumnLetterToIndex(quantityColumn);
  if (quantityIndex >= 0 && row.excelData.quantity != null) {
    while (source.length <= quantityIndex) source.push("");
    source[quantityIndex] = String(row.excelData.quantity);
  }
  const draft = buildExcelImportDraft(source, mapping);
  (Object.keys(draft) as Array<keyof typeof draft>).forEach((key) => {
    if (row.excelData[key] != null) {
      draft[key] = String(row.excelData[key]);
    }
  });
  const databaseId = row.databaseData?.id;
  const localProduct = databaseId == null && row.classification === "EXISTING"
    ? resolvePreviewProduct(row.excelData, products)
    : undefined;
  const product = databaseId == null ? undefined : {
    id: String(databaseId),
    code: row.databaseData?.code == null ? null : String(row.databaseData.code),
    barcode: row.databaseData?.barcode == null ? null : String(row.databaseData.barcode)
  };
  const resolvedProduct = product ?? localProduct;
  const unresolvedExisting = row.classification === "EXISTING" && !resolvedProduct;
  const structuredErrors = unresolvedExisting
    ? [...row.errors, {
        code: "PRODUCT_LOCAL_RESOLUTION_FAILED",
        row: row.rowNumber,
        column: null,
        attribute: "code/barcode",
        receivedValue: String(row.excelData.code ?? row.excelData.barcode ?? ""),
        reason: "El producto existente no se puede resolver en el catálogo local",
        acceptedValues: "Un único producto del catálogo local",
        recommendedFix: "Recarga el catálogo y vuelve a leer el fichero"
      }]
    : row.errors;
  return {
    rowNumber: row.rowNumber,
    rowNumbers: row.rowNumbers,
    existence: row.existence,
    source,
    draft,
    product: resolvedProduct,
    version: row.version,
    excelData: row.excelData,
    databaseData: row.databaseData,
    changes: row.changes,
    structuredErrors,
    status: row.classification === "ERROR" || unresolvedExisting
      ? "error"
      : row.classification === "MISSING"
        ? "missing"
        : row.purchasePriceChanged || Object.hasOwn(row.changes, "purchasePrice")
          ? "purchasePriceChanged"
          : "accepted",
    errors: unresolvedExisting
      ? [...row.errors.map((error) => error.reason), "Producto existente no resuelto en el catálogo local"]
      : row.errors.map((error) => error.reason),
    masterDataChanged: row.masterDataChanged
  };
}

function resolvePreviewProduct(
  data: Record<string, unknown>,
  products: readonly ExcelImportProductIdentity[]
): ExcelImportProductIdentity | undefined {
  const identities = [data.code, data.barcode]
    .map((value) => normalizeExcelText(String(value ?? "")))
    .filter(Boolean);
  if (identities.length === 0) return undefined;
  const matches = products.filter((product) => {
    const productIdentities = [product.code, product.barcode]
      .map((value) => normalizeExcelText(String(value ?? "")))
      .filter(Boolean);
    return identities.some((identity) => productIdentities.includes(identity));
  });
  return matches.length === 1 ? matches[0] : undefined;
}

export function updateExcelSheetCell(
  sheet: ExcelSheet,
  rowIndex: number,
  columnIndex: number,
  value: ExcelCell
): ExcelSheet {
  const nextValue = value;
  const updatedSheet = sheet.map((row, currentRowIndex) => currentRowIndex === rowIndex
    ? Array.from(
        { length: Math.max(row.length, columnIndex + 1) },
        (_, currentColumnIndex) => currentColumnIndex === columnIndex ? nextValue : row[currentColumnIndex] ?? ""
      )
    : row
  );
  // Formula cells keep their cached value. Import previews must never execute
  // workbook formulas; the backend remains the authority for validation.
  return updatedSheet;
}

function exportablePreviewFingerprint(result: ProductExcelImportPreviewResult | null) {
  const fingerprint = result?.previewFingerprint?.trim();
  return fingerprint && /^[0-9a-f]{64}$/i.test(fingerprint) ? fingerprint : null;
}

export function normalizeExcelDecimalValue(value: ExcelCell): ExcelCell {
  if (isExcelFormulaCell(value)) {
    return {
      ...value,
      value: normalizeExcelDecimalValue(value.value)
    };
  }
  if (typeof value !== "number" && typeof value !== "string") {
    return value;
  }
  const text = String(value).trim();
  if (!/^-?\d+[.,]\d{3,}$/.test(text)) {
    return value;
  }
  const decimalSeparator = text.includes(",") ? "," : ".";
  const numericValue = Number(text.replace(",", "."));
  if (!Number.isFinite(numericValue)) {
    return value;
  }
  const rounded = (Math.round((numericValue + Number.EPSILON) * 100) / 100).toFixed(2);
  return decimalSeparator === "," ? rounded.replace(".", ",") : rounded;
}

export function normalizeExcelDecimalCells(sheet: ExcelSheet): ExcelSheet {
  return sheet.map((row) => row.map(normalizeExcelDecimalValue));
}

function renderUpdateCheckbox(
  field: SharedExcelImportUpdateField,
  label: string,
  updateFields: Partial<Record<SharedExcelImportUpdateField, boolean>>,
  setUpdateFields: (updater: (current: Partial<Record<SharedExcelImportUpdateField, boolean>>) => Partial<Record<SharedExcelImportUpdateField, boolean>>) => void,
  t: (key: string) => string,
  disabled = false
) {
  return (
    <input
      type="checkbox"
      disabled={disabled}
      checked={Boolean(updateFields[field])}
      onChange={(event) => setUpdateFields((current) => ({
        ...current,
        [field]: event.target.checked,
        ...(field === "familyId" ? { subfamilyId: event.target.checked } : {})
      }))}
      aria-label={interpolateMessage(t("sharedExcel.updateField"), { field: label })}
    />
  );
}

function specialValueField(key: MappingField["key"]): key is keyof SharedExcelImportValueSources {
  return key === "offerActive" || key === "productType" || key === "priceUseMode" || key === "discountType" || key === "taxId" || key === "taxesIncluded";
}

export function sanitizeExcelColumnLetter(value: string) {
  return value.replace(/[^a-z]/gi, "").toUpperCase().slice(0, 2);
}

export function combinedFamilyUpdateFields(
  fields: Partial<Record<SharedExcelImportUpdateField, boolean>>,
  familyReference: string
) {
  const selected = Boolean(fields.familyId && familyReference.trim());
  return { ...fields, familyId: selected, subfamilyId: selected };
}

function specialValueOptions(
  field: keyof SharedExcelImportValueSources,
  taxes: readonly SharedExcelImportTaxOption[],
  t: (key: string) => string
) {
  if (field === "productType") return [
    { value: "1", label: "1 - " + t("product.type.unit"), shortLabel: "1" },
    { value: "2", label: "2 - " + t("product.type.weight"), shortLabel: "2" },
    { value: "3", label: "3 - " + t("product.type.service"), shortLabel: "3" }
  ];
  if (field === "taxId") return taxes.length
    ? taxes.map((tax) => ({ value: String(tax.value ?? tax.id), label: tax.label, shortLabel: tax.label.match(/[\d.,]+\s*%/)?.[0] ?? tax.label }))
    : [{ value: "", label: t("sharedExcel.noTaxesAvailable"), shortLabel: "", disabled: true }];
  if (field === "priceUseMode") return [
    { value: "NORMAL", label: "1 - " + t("sharedExcel.priceMode.normal"), shortLabel: "1" },
    { value: "MEMBER_PRICE", label: "2 - " + t("sharedExcel.priceMode.member"), shortLabel: "2" },
    { value: "OFFER_PRICE", label: "3 - " + t("sharedExcel.priceMode.offerPrice"), shortLabel: "3" },
    { value: "OFFER_DISCOUNT", label: "4 - " + t("sharedExcel.priceMode.offerDiscount"), shortLabel: "4" }
  ];
  return [
    { value: "0", label: "0 - " + t("sharedExcel.no"), shortLabel: "0" },
    { value: "1", label: "1 - " + t("sharedExcel.yes"), shortLabel: "1" }
  ];
}
export function applyPreviewOptions(
  rows: ExcelImportClassifiedRow[],
  values: SharedExcelImportValueSources
) {
  return rows.map((row) => {
    const draft = applyGlobalExcelValues(row.draft, values);
    const errors = [...row.errors];
    if (values.taxId.source === "global" && !values.taxId.value) errors.push("taxRequired");
    if (values.priceUseMode.source === "global" && !["NORMAL", "MEMBER_PRICE", "OFFER_PRICE", "OFFER_DISCOUNT", "1", "2", "3", "4"].includes(values.priceUseMode.value)) errors.push("invalidPriceUseMode");
    if (values.discountType.source === "global" && !["0", "1"].includes(values.discountType.value)) errors.push("invalidDiscountType");
    if (values.taxesIncluded.source === "global" && !["0", "1"].includes(values.taxesIncluded.value)) errors.push("invalidTaxesIncluded");
    if (draft.priceUseMode && !["NORMAL", "MEMBER_PRICE", "OFFER_PRICE", "OFFER_DISCOUNT"].includes(draft.priceUseMode)) errors.push("invalidPriceUseMode");
    if (draft.discountType && !["0", "1"].includes(draft.discountType)) errors.push("invalidDiscountType");
    if (draft.taxesIncluded && !["0", "1"].includes(draft.taxesIncluded)) errors.push("invalidTaxesIncluded");
    if (draft.discountType === "1") {
      if (draft.priceUseMode !== "NORMAL") errors.push("discountProhibitedPriceMode");
      if ([draft.memberPrice, draft.offerPrice, draft.offerDiscountPercent]
        .some((value) => typeof value === "string" && value.trim() !== "")) {
        errors.push("discountProhibitedPriceMode");
      }
    }
    if (draft.offerFrom && !parseExcelDate(draft.offerFrom)) errors.push("invalidOfferFrom");
    if (draft.offerUntil && !parseExcelDate(draft.offerUntil)) errors.push("invalidOfferUntil");
    if (draft.offerFrom && draft.offerUntil && parseExcelDate(draft.offerFrom) && parseExcelDate(draft.offerUntil)
      && parseExcelDate(draft.offerUntil)! < parseExcelDate(draft.offerFrom)!) errors.push("offerDateRange");
    return errors.length === 0 ? { ...row, draft } : { ...row, draft, status: "error" as const, errors: [...new Set(errors)] };
  });
}

function quantityFromRow(row: readonly ExcelCell[], column: string, required: boolean) {
  const index = excelColumnLetterToIndex(column);
  const quantity = index < 0 ? (required ? 0 : 1) : Number(excelCellText(row[index]).replace(",", "."));
  return Number.isFinite(quantity) && quantity > 0 ? quantity : required ? 0 : 1;
}

function defaultStoredExcelImportSettings(): SharedExcelImportStoredSettings {
  return {
    useDefaultTax: true,
    mapping: {},
    quantityColumn: "",
    startRow: 2,
    updateFields: {},
    options: defaultExcelImportOptions()
  };
}

function defaultExcelImportOptions(): SharedExcelImportOptions {
  return {
    autoAddMissing: true,
    generateSummaryDocument: false,
    showOnlyImported: false,
    skipZeroPriceUpdate: true,
    updateSupplier: false,
    priceSource: "purchasePrice",
    valueSources: defaultExcelImportValueSources()
  };
}

function defaultExcelImportValueSources(): SharedExcelImportValueSources {
  return {
    offerActive: { source: "excel", value: "" },
    productType: { source: "global", value: "1" },
    priceUseMode: { source: "excel", value: "NORMAL" },
    discountType: { source: "global", value: "0" },
    taxId: { source: "excel", value: "" },
    taxesIncluded: { source: "global", value: "1" }
  };
}

function applyGlobalExcelValues(
  draft: ExcelImportClassifiedRow["draft"],
  values: SharedExcelImportValueSources
) {
  const effective = {
    ...draft,
    productType: values.productType?.source === "global" ? values.productType.value : draft.productType,
    offerActive: values.offerActive?.source === "global" ? values.offerActive.value : draft.offerActive,
    priceUseMode: values.priceUseMode.source === "global" ? values.priceUseMode.value : draft.priceUseMode,
    discountType: values.discountType.source === "global" ? values.discountType.value : draft.discountType,
    taxId: values.taxId.source === "global" ? values.taxId.value : draft.taxId,
    taxesIncluded: values.taxesIncluded.source === "global" ? values.taxesIncluded.value : draft.taxesIncluded
  };
  return {
    ...effective,
    productType: normalizeImportProductType(effective.productType),
    offerActive: normalizeBooleanSetting(effective.offerActive),
    priceUseMode: normalizePriceUseMode(effective.priceUseMode),
    discountType: normalizeBooleanSetting(effective.discountType),
    taxesIncluded: normalizeBooleanSetting(effective.taxesIncluded)
  };
}

function normalizePriceUseMode(value: string) {
  return ({ "1": "NORMAL", "2": "MEMBER_PRICE", "3": "OFFER_PRICE", "4": "OFFER_DISCOUNT" } as Record<string, string>)[value.trim().toUpperCase()]
    ?? value.trim().toUpperCase();
}

function normalizeImportProductType(value: string) {
  const normalized = value.trim().toUpperCase();
  return ({ "1": "UNIT", "2": "WEIGHT", "3": "SERVICE" } as Record<string, string>)[normalized] ?? normalized;
}

function normalizeBooleanSetting(value: string) {
  const normalized = value.trim().toUpperCase();
  if (["SI", "SÍ", "TRUE", "YES"].includes(normalized)) return "1";
  if (["NO", "FALSE"].includes(normalized)) return "0";
  return value.trim();
}

function excelImportStorageKey(terminalContext?: Pick<TerminalContext, "terminalCode" | "terminalId">) {
  const terminalKey = terminalContext?.terminalId || terminalContext?.terminalCode || "terminal-default";
  return `${excelImportStoragePrefix}.${terminalKey}`;
}

function loadStoredExcelImportSettings(terminalContext?: Pick<TerminalContext, "terminalCode" | "terminalId">): SharedExcelImportStoredSettings {
  try {
    const raw = globalThis.localStorage.getItem(excelImportStorageKey(terminalContext));
    if (!raw) {
      return defaultStoredExcelImportSettings();
    }
    const parsed = JSON.parse(raw) as Partial<SharedExcelImportStoredSettings>;
    const defaultOptions = defaultExcelImportOptions();
    const parsedOptions: Partial<SharedExcelImportOptions> =
      parsed.options && typeof parsed.options === "object" ? parsed.options : {};
    const mapping = parsed.mapping && typeof parsed.mapping === "object" ? { ...parsed.mapping } : {};
    const updateFields = parsed.updateFields && typeof parsed.updateFields === "object" ? { ...parsed.updateFields } : {};
    // The importer now derives supplier references from code/barcode. Do not
    // revive hidden legacy assignments; persisted documents remain untouched.
    delete mapping.supplierReference;
    delete updateFields.supplierReference;
    delete mapping.comments;
    delete updateFields.comments;
    const familyMappingNeedsReview = Boolean(mapping.subfamilyId);
    if (familyMappingNeedsReview) {
      // Never silently turn a former two-column assignment into a destructive clear.
      delete mapping.familyId;
      delete mapping.subfamilyId;
      updateFields.familyId = false;
    }
    updateFields.subfamilyId = Boolean(updateFields.familyId);
    return {
      mapping,
      useDefaultTax: !mapping.taxId && (parsed.useDefaultTax === true || !Object.hasOwn(parsedOptions.valueSources ?? {}, "taxId")),
      familyMappingNeedsReview,
      quantityColumn: typeof parsed.quantityColumn === "string" ? parsed.quantityColumn : "",
      startRow: typeof parsed.startRow === "number" && Number.isFinite(parsed.startRow) ? Math.max(2, parsed.startRow) : 2,
      updateFields,
      options: {
        autoAddMissing: booleanSetting(parsedOptions.autoAddMissing, defaultOptions.autoAddMissing),
        generateSummaryDocument: booleanSetting(parsedOptions.generateSummaryDocument, defaultOptions.generateSummaryDocument),
        showOnlyImported: booleanSetting(parsedOptions.showOnlyImported, defaultOptions.showOnlyImported),
        skipZeroPriceUpdate: booleanSetting(parsedOptions.skipZeroPriceUpdate, defaultOptions.skipZeroPriceUpdate),
        updateSupplier: booleanSetting(parsedOptions.updateSupplier, defaultOptions.updateSupplier),
        priceSource: isExcelImportPriceSource(parsedOptions.priceSource) ? parsedOptions.priceSource : defaultOptions.priceSource,
        valueSources: readStoredValueSources(parsedOptions.valueSources, mapping)
      }
    };
  } catch {
    return defaultStoredExcelImportSettings();
  }
}

function readStoredValueSources(value: unknown, mapping: ExcelColumnMapping = {}): SharedExcelImportValueSources {
  const defaults = defaultExcelImportValueSources();
  for (const key of Object.keys(defaults) as Array<keyof SharedExcelImportValueSources>) {
    if (mapping[key]) defaults[key] = { source: "excel", value: "" };
  }
  if (!value || typeof value !== "object") return defaults;
  const candidate = value as Partial<Record<keyof SharedExcelImportValueSources, unknown>>;
  return (Object.keys(defaults) as Array<keyof SharedExcelImportValueSources>).reduce((result, key) => {
    const entry = candidate[key];
    if (!entry || typeof entry !== "object") return result;
    const parsed = entry as Partial<{ source: unknown; value: unknown }>;
    result[key] = {
      source: parsed.source === "global" ? "global" : "excel",
      value: typeof parsed.value === "string" ? parsed.value : defaults[key].value
    };
    if (key === "productType" && result[key].source === "global") {
      result[key].value = ({ UNIT: "1", WEIGHT: "2", SERVICE: "3" } as Record<string, string>)[result[key].value] ?? result[key].value;
    }
    return result;
  }, { ...defaults });
}

function saveStoredExcelImportSettings(
  terminalContext: Pick<TerminalContext, "terminalCode" | "terminalId"> | undefined,
  settings: SharedExcelImportStoredSettings
) {
  try {
    globalThis.localStorage.setItem(excelImportStorageKey(terminalContext), JSON.stringify(settings));
  } catch {
    // Local storage is only used to remember the terminal template.
  }
}

function clearStoredExcelImportSettings(terminalContext?: Pick<TerminalContext, "terminalCode" | "terminalId">) {
  try {
    globalThis.localStorage.removeItem(excelImportStorageKey(terminalContext));
  } catch {
    // Local storage may be unavailable outside the browser.
  }
}

function booleanSetting(value: unknown, fallback: boolean) {
  return typeof value === "boolean" ? value : fallback;
}

function isExcelImportPriceSource(value: unknown): value is SharedExcelImportPriceSource {
  return value === "purchasePrice"
    || value === "salePrice"
    || value === "memberPrice"
    || value === "wholesalePrice"
    || value === "offerPrice";
}

function renderPanelTab(
  panel: SharedExcelImportPanel,
  label: string,
  activePanel: SharedExcelImportPanel,
  setActivePanel: (panel: SharedExcelImportPanel) => void
) {
  return (
    <button
      type="button"
      className={activePanel === panel ? "selected" : ""}
      onClick={() => setActivePanel(panel)}
      aria-current={activePanel === panel ? "page" : undefined}
    >
      {label}
    </button>
  );
}

export function excelReviewColumns(
  panel: SharedExcelImportPanel, context: ProductExcelImportContext, importedOnly: boolean,
  locale: LocaleCode, t: (key: string) => string
): ExcelReviewColumn[] {
  const fields = [...sharedExcelFieldOrder.filter((field) => field.key !== "barcode2" && (context !== "STOCK" || field.key !== "quantity")),
    ...sharedExcelFieldOrder.filter((field) => field.key === "barcode2")];
  const current = panel !== "missing" && !(panel === "summary" && importedOnly);
  const comparison = panel === "summary" || panel === "accepted";
  return [
    { key: "rowNumber", label: t("sharedExcel.column.row") },
    { key: "status", label: t("sharedExcel.column.status") },
    ...fields.flatMap(({ key: field, updateKey }) => !updateKey
      ? [{ key: "excel." + field, label: excelFieldDisplayName(field, locale) }]
      : [
          ...(current ? [{ key: "current." + field, label: excelFieldDisplayName(field, locale) + " · " + t("sharedExcel.column.databaseData"),
            ...(comparison ? { source: "current" as const } : {}) }] : []),
          { key: "excel." + field, label: excelFieldDisplayName(field, locale) + " · " + t("sharedExcel.column.excelData"),
            ...(comparison ? { source: "excel" as const } : {}) }
        ]),
    { key: "errors", label: t("sharedExcel.column.errorDetail") }
  ];
}

/** Hide only wholly empty attribute pairs. Zero/false and either side's values remain visible. */
export function populatedExcelReviewColumns(columns: ExcelReviewColumn[], rows: readonly ExcelImportClassifiedRow[]) {
  const populated = new Set<string>();
  for (const column of columns) {
    const [source, field] = column.key.split(".");
    if (!field) continue;
    if (rows.some((row) => {
      const values = source === "current" ? row.databaseData : row.excelData ?? row.draft;
      return String(values?.[field as keyof typeof values] ?? "").trim() !== "";
    })) populated.add(field);
  }
  return columns.filter((column) => !column.key.includes(".") || populated.has(column.key.split(".")[1]));
}

function excelReviewValue(
  row: ExcelImportClassifiedRow & { errorTexts?: Partial<Record<LocaleCode, string>> },
  key: string, locale: LocaleCode, t: (key: string) => string
) {
  if (key === "rowNumber") return String(row.rowNumber);
  if (key === "status") return resultStatusLabel(row.status, t);
  if (key === "errors") return row.errorTexts?.[locale] ?? reviewErrorText(row.structuredErrors ?? row.errors.map((reason) => ({ code: "ROW_INVALID", reason })), t, locale);
  const [source, field] = key.split(".");
  const values = source === "current" ? row.databaseData : row.excelData ?? row.draft;
  return values?.[field as keyof typeof values] == null ? "" : String(values[field as keyof typeof values]);
}

/** Presentation-only differences, independent of update flags and zero-price write protection. */
export function excelReviewChangedColumns(
  row: Pick<ExcelImportClassifiedRow, "excelData" | "databaseData">, columns: readonly ExcelReviewColumn[]
): string[] {
  const { databaseData, excelData } = row;
  if (!databaseData || !excelData) return [];
  const changed = new Set(columns.filter((column) => column.source === "excel").flatMap((column) => {
    const field = column.key.slice("excel.".length);
    // Unassigned attributes and document-only values have no comparable pair.
    if (!Object.hasOwn(excelData, field) || !Object.hasOwn(databaseData, field)) return [];
    return excelReviewComparableValue(field, excelData[field]) === excelReviewComparableValue(field, databaseData[field])
      ? [] : [field];
  }));
  return columns.filter((column) => column.source && changed.has(column.key.slice(column.key.indexOf(".") + 1)))
    .map((column) => column.key);
}

function excelReviewComparableValue(field: string, value: unknown): string {
  const text = String(value ?? "").trim();
  if (!text) return ""; // Empty and zero must remain different.
  if (field === "offerFrom" || field === "offerUntil") return parseExcelDate(text) ?? text;
  if (field.endsWith("Price")) {
    // Compare plain decimals exactly, even on invalid rows exceeding JS safe precision.
    if (/^[+-]?\d+(?:[.,]\d+)?$/.test(text)) return excelReviewDecimal(text);
    const price = parseMoneyValue(text, 3);
    return price === null ? text : String(price);
  }
  if (field === "priceUseMode") {
    const modes: Record<string, string> = { NORMAL: "1", MEMBER_PRICE: "2", OFFER_PRICE: "3", OFFER_DISCOUNT: "4" };
    return modes[text] ?? text;
  }
  if (field === "productType") return normalizeImportProductType(text);
  if (["purchaseDiscountPercent", "offerDiscountPercent", "packageQuantity", "stockMin", "stockMax", "taxId"].includes(field)) {
    const number = text.replace(/\s*%$/, "").replace(",", ".");
    if (/^[+-]?\d+(?:\.\d+)?$/.test(number)) return excelReviewDecimal(number);
  }
  return text; // Codes, barcodes and family codes retain leading zeros and case.
}

function excelReviewDecimal(text: string): string {
  const [whole, fraction = ""] = text.replace(/^[+-]/, "").replace(",", ".").split(".");
  const number = whole.replace(/^0+(?=\d)/, "") + (fraction.replace(/0+$/, "") ? "." + fraction.replace(/0+$/, "") : "");
  return text.startsWith("-") && number !== "0" ? "-" + number : number;
}

function reviewErrorText(errors: readonly ProductExcelImportError[], t: (key: string) => string, locale: LocaleCode) {
  return errors.map((error) => [
    "[" + error.code + "]",
    t("sharedExcel.error.row") + ": " + (error.row ?? "-"),
    t("sharedExcel.error.column") + ": " + (error.column ? excelColumnIndexToLetter(error.column - 1) : "-"),
    t("sharedExcel.error.attribute") + ": " + (error.attribute ? excelFieldDisplayName(error.attribute, locale) : "-"),
    t("sharedExcel.error.received") + ": " + (error.receivedValue ?? "-"),
    t("sharedExcel.error.reason") + ": " + localizedImportErrorText(error, "reason", t, locale),
    t("sharedExcel.error.accepted") + ": " + localizedImportErrorText(error, "accepted", t, locale),
    t("sharedExcel.error.fix") + ": " + localizedImportErrorText(error, "fix", t, locale)
  ].join(" | ")).join("\n");
}
function resultStatusLabel(status: ExcelImportClassifiedRow["status"], t: (key: string) => string) {
  return t(status === "purchasePriceChanged"
    ? "sharedExcel.status.purchaseChanged"
    : status === "missing"
      ? "sharedExcel.status.missing"
      : status === "error"
        ? "sharedExcel.status.error"
        : "sharedExcel.status.existing");
}


function excelFieldDisplayName(key: string, locale: LocaleCode) {
  const field = sharedExcelFieldOrder.find((candidate) => candidate.key === key);
  if (field) return fieldLabel(field, locale);
  const technical = localizedImportAttributeLabels[key]?.[locale];
  return technical ?? localizedImportAttributeLabels.__unknown[locale];
}

const localizedImportAttributeLabels: Record<string, Record<LocaleCode, string>> = {
  comments: { es: "Comentarios", en: "Comments", zh: "备注" },
  subfamilyId: { es: "Subfamilia", en: "Subfamily", zh: "子类别" },
  familyBusinessCode: { es: "Código Familia / Subfamilia", en: "Family / Subfamily code", zh: "类别 / 子类别编码" },
  file: { es: "Fichero", en: "File", zh: "文件" },
  context: { es: "Contexto", en: "Context", zh: "上下文" },
  storeId: { es: "Tienda", en: "Store", zh: "门店" },
  companyId: { es: "Empresa", en: "Company", zh: "企业" },
  edits: { es: "Ediciones", en: "Edits", zh: "编辑" },
  cell: { es: "Celda", en: "Cell", zh: "单元格" },
  version: { es: "Versión", en: "Version", zh: "版本" },
  expectedConcurrencyTokens: { es: "Tokens de concurrencia", en: "Concurrency tokens", zh: "并发令牌" },
  confirmMasterChanges: { es: "Confirmación de cambios", en: "Master change confirmation", zh: "主数据更改确认" },
  autoAddMissing: { es: "Altas automáticas", en: "Automatic additions", zh: "自动新增" },
  __unknown: { es: "Campo de importación", en: "Import field", zh: "导入字段" }
};

type LocalizedImportErrorText = { reason: string; accepted: string; fix: string };
type ImportErrorCatalog = Record<LocaleCode, LocalizedImportErrorText>;

export const PRODUCT_EXCEL_IMPORT_ERROR_CODES = [
  "FILE_EMPTY", "FILE_TOO_LARGE", "FILE_READ_FAILED", "FILE_EXTENSION_INVALID", "FILE_SIGNATURE_INVALID",
  "WORKBOOK_ENCRYPTED", "WORKBOOK_CORRUPT", "WORKBOOK_UNREADABLE", "WORKBOOK_MACRO_UNSUPPORTED", "WORKBOOK_LIMIT", "SHEET_MISSING",
  "GRID_ROW_LIMIT", "GRID_CELL_LIMIT", "CELL_LIMIT", "CELL_TEXT_LIMIT", "TEXT_LIMIT", "FORMULA_LIMIT", "FORMULA_NO_CACHE",
  "CELL_ERROR_VALUE", "FORMULA_RESULT_ERROR",
  "MAPPING_REQUIRED", "IDENTITY_MAPPING_REQUIRED", "MAPPING_FIELD_UNKNOWN", "VIEW_INVALID", "COLUMN_INVALID", "COLUMN_NOT_FOUND", "COLUMN_LIMIT", "CONTRACT_LIMIT",
  "CONTEXT_REQUIRED", "CONTEXT_INVALID", "STORE_CONTEXT_MISMATCH", "COMPANY_CONTEXT_MISMATCH", "START_ROW_INVALID",
  "ROW_LIMIT", "NO_ROWS_DETECTED", "CELL_EDIT_INVALID", "EDIT_LIMIT", "EDIT_VALUE_LIMIT", "FIELD_LENGTH_INVALID",
  "IDENTIFIER_REQUIRED", "IDENTIFIER_DUPLICATE", "PRODUCT_AMBIGUOUS", "DUPLICATE_CONFLICT", "INVALID_PRICE_MODE",
  "INVALID_BOOLEAN", "PRODUCT_TYPE_INVALID", "DISCOUNT_PROHIBITED_PRICE_MODE", "ZERO_PRICE_INVALID", "NUMBER_INVALID",
  "NUMBER_FORMAT_AMBIGUOUS", "NUMBER_SCALE_INVALID", "NUMBER_PRECISION_INVALID", "DATE_INVALID", "DATE_RANGE_INVALID",
  "OFFER_REQUIRED", "TAX_REQUIRED", "TAX_UNKNOWN", "TAX_AMBIGUOUS", "FAMILY_REQUIRED", "FAMILY_UNKNOWN", "FAMILY_AMBIGUOUS",
  "SUBFAMILY_UNKNOWN", "SUBFAMILY_AMBIGUOUS", "SUBFAMILY_FAMILY_MISMATCH", "NAME_REQUIRED", "QUANTITY_REQUIRED", "STOCK_RANGE_INVALID",
  "GLOBAL_VALUE_UNKNOWN", "VALUE_SOURCE_UNKNOWN", "VALUE_SOURCE_INVALID", "UPDATE_FIELD_UNKNOWN", "FILE_CHANGED", "HASH_REQUIRED",
  "TOKEN_LIMIT", "TOKEN_UNEXPECTED", "TOKEN_INVALID", "CONCURRENCY_TOKEN_REQUIRED", "VERSION_REQUIRED", "VERSION_STALE",
  "MISSING_REVIEW_REQUIRED", "CONFIRMATION_REQUIRED", "APPLY_CONTEXT_UNSUPPORTED", "APPLY_CONTEXT_INVALID", "APPLY_PROVENANCE_REQUIRED",
  "APPLY_REQUIRED_VALUE", "APPLY_OFFER_REQUIRED",
  "APPLY_TRANSACTION_FAILED", "SUMMARY_PREVIEW_INVALID", "PRODUCT_LOCAL_RESOLUTION_FAILED", "ROW_INVALID", "ERROR_LIMIT",
  "TRANSPORT_REQUEST_TOO_LARGE", "NUMBER_FORMAT_UNSUPPORTED", "PERCENTAGE_FORMAT_NOT_ALLOWED", "IDENTIFIER_NUMERIC_PRECISION",
  "PERMISSION_DENIED", "PREVIEW_READ_FAILED", "LOCALE_INVALID"
] as const;

export const localizedImportErrorCatalog: Record<string, ImportErrorCatalog> = {
  FILE_SIGNATURE_INVALID: {
    es: { reason: "La firma del fichero no coincide con su extensión", accepted: "Un XLS o XLSX real", fix: "Selecciona el fichero original sin renombrarlo" },
    en: { reason: "The file signature does not match its extension", accepted: "A real XLS or XLSX workbook", fix: "Select the original file without renaming it" },
    zh: { reason: "文件签名与扩展名不匹配", accepted: "真实的 XLS 或 XLSX 工作簿", fix: "选择未重命名的原始文件" }
  },
  WORKBOOK_CORRUPT: {
    es: { reason: "El libro está dañado o no se puede leer", accepted: "Un libro XLS/XLSX válido", fix: "Abre y guarda de nuevo el libro y vuelve a intentarlo" },
    en: { reason: "The workbook is corrupt or unreadable", accepted: "A valid XLS/XLSX workbook", fix: "Open and save the workbook again, then retry" },
    zh: { reason: "工作簿已损坏或无法读取", accepted: "有效的 XLS/XLSX 工作簿", fix: "重新打开并保存工作簿后重试" }
  },
  WORKBOOK_ENCRYPTED: {
    es: { reason: "El libro está cifrado", accepted: "Un libro sin contraseña", fix: "Quita la protección y vuelve a seleccionar el fichero" },
    en: { reason: "The workbook is encrypted", accepted: "A workbook without a password", fix: "Remove protection and select the file again" },
    zh: { reason: "工作簿已加密", accepted: "无密码的工作簿", fix: "取消保护后重新选择文件" }
  },
  WORKBOOK_MACRO_UNSUPPORTED: {
    es: { reason: "Los libros con macros no son compatibles", accepted: "XLS o XLSX sin macros", fix: "Guarda una copia sin macros y vuelve a importarla" },
    en: { reason: "Macro-enabled workbooks are not supported", accepted: "An XLS or XLSX without macros", fix: "Save a macro-free copy and import it again" },
    zh: { reason: "不支持带宏的工作簿", accepted: "不含宏的 XLS 或 XLSX", fix: "保存无宏副本后重新导入" }
  },
  ROW_LIMIT: {
    es: { reason: "Se ha superado el límite de filas detectadas", accepted: "Hasta 5.000 filas", fix: "Divide el libro en varios ficheros" },
    en: { reason: "The detected row limit was exceeded", accepted: "Up to 5,000 rows", fix: "Split the workbook into multiple files" },
    zh: { reason: "超过检测行数限制", accepted: "最多 5,000 行", fix: "将工作簿拆分为多个文件" }
  },
  COLUMN_LIMIT: {
    es: { reason: "Se ha superado el límite de columnas", accepted: "Hasta 256 columnas (A-IV)", fix: "Reduce las columnas utilizadas" },
    en: { reason: "The column limit was exceeded", accepted: "Up to 256 columns (A-IV)", fix: "Reduce the used columns" },
    zh: { reason: "超过列数限制", accepted: "最多 256 列 (A-IV)", fix: "减少使用的列" }
  },
  FORMULA_NO_CACHE: {
    es: { reason: "La fórmula no tiene un resultado guardado", accepted: "Una fórmula con resultado almacenado", fix: "Calcula y guarda el libro antes de importarlo" },
    en: { reason: "The formula has no stored result", accepted: "A formula with a cached result", fix: "Calculate and save the workbook before importing" },
    zh: { reason: "公式没有保存的结果", accepted: "带缓存结果的公式", fix: "导入前计算并保存工作簿" }
  },
  IDENTIFIER_REQUIRED: {
    es: { reason: "Falta un identificador del producto", accepted: "Código, código de barras o nombre", fix: "Completa el identificador de la fila" },
    en: { reason: "A product identifier is missing", accepted: "Code, barcode, or name", fix: "Complete the row identifier" },
    zh: { reason: "缺少商品标识", accepted: "编码、条码或名称", fix: "补全该行标识" }
  },
  ROW_INVALID: {
    es: { reason: "La fila contiene un error de importación", accepted: "Valores válidos para el atributo", fix: "Corrige el valor indicado" },
    en: { reason: "The row contains an import error", accepted: "Valid values for the attribute", fix: "Correct the indicated value" },
    zh: { reason: "该行包含导入错误", accepted: "该属性的有效值", fix: "更正指出的数值" }
  },
  IDENTIFIER_DUPLICATE: {
    es: { reason: "El identificador está duplicado", accepted: "Identificadores únicos", fix: "Corrige las filas repetidas" },
    en: { reason: "The identifier is duplicated", accepted: "Unique identifiers", fix: "Correct the repeated rows" },
    zh: { reason: "标识重复", accepted: "唯一标识", fix: "更正重复行" }
  },
  PRODUCT_AMBIGUOUS: {
    es: { reason: "El identificador coincide con varios productos", accepted: "Un único producto", fix: "Corrige el código o el código de barras" },
    en: { reason: "The identifier matches several products", accepted: "A single product", fix: "Correct the code or barcode" },
    zh: { reason: "标识匹配多个商品", accepted: "唯一商品", fix: "更正编码或条码" }
  },
  DATE_INVALID: {
    es: { reason: "La fecha no es válida", accepted: "Fecha nativa de Excel válida, DD-MM-AA, DD-MM-AAAA o ISO", fix: "Corrige la fecha y usa un día de calendario real" },
    en: { reason: "The date is invalid", accepted: "A valid native Excel date, DD-MM-YY, DD-MM-YYYY, or ISO", fix: "Correct the date using a real calendar day" },
    zh: { reason: "日期无效", accepted: "有效的 Excel 原生日期、DD-MM-AA、DD-MM-AAAA 或 ISO", fix: "使用真实日历日期更正日期" }
  },
  DATE_RANGE_INVALID: {
    es: { reason: "La fecha final es anterior a la inicial", accepted: "Oferta hasta igual o posterior a Oferta desde", fix: "Corrige el intervalo de oferta" },
    en: { reason: "The end date is before the start date", accepted: "Offer until on or after Offer from", fix: "Correct the offer interval" },
    zh: { reason: "结束日期早于开始日期", accepted: "结束日期不早于开始日期", fix: "更正促销日期范围" }
  },
  TAX_UNKNOWN: {
    es: { reason: "El impuesto no existe o no está activo en la tienda", accepted: "Un impuesto activo de esta tienda", fix: "Selecciona un impuesto válido" },
    en: { reason: "The tax does not exist or is inactive in this store", accepted: "An active tax from this store", fix: "Select a valid tax" },
    zh: { reason: "税率不存在或在此门店未启用", accepted: "此门店的启用税率", fix: "选择有效税率" }
  },
  TAX_AMBIGUOUS: {
    es: { reason: "El porcentaje coincide con varios impuestos activos", accepted: "Un único impuesto activo", fix: "Selecciona el impuesto por su referencia" },
    en: { reason: "The percentage matches several active taxes", accepted: "A single active tax", fix: "Select the tax by its reference" },
    zh: { reason: "百分比匹配多个启用税率", accepted: "唯一启用税率", fix: "按税率引用选择税率" }
  },
  INVALID_BOOLEAN: {
    es: { reason: "El valor booleano no es válido", accepted: "0 = No o 1 = Sí", fix: "Introduce 0 o 1" },
    en: { reason: "The boolean value is invalid", accepted: "0 = No or 1 = Yes", fix: "Enter 0 or 1" },
    zh: { reason: "布尔值无效", accepted: "0 = 否或 1 = 是", fix: "输入 0 或 1" }
  },
  INVALID_PRICE_MODE: {
    es: { reason: "El modo de precio no es válido", accepted: "1 = Venta, 2 = Miembro, 3 = Oferta, 4 = Descuento oferta", fix: "Introduce un modo del 1 al 4" },
    en: { reason: "The price mode is invalid", accepted: "1 = Sale, 2 = Member, 3 = Offer, 4 = Offer discount", fix: "Enter a mode from 1 to 4" },
    zh: { reason: "价格模式无效", accepted: "1 = 销售、2 = 会员、3 = 促销、4 = 促销折扣", fix: "输入 1 到 4 的模式" }
  },
  DISCOUNT_PROHIBITED_PRICE_MODE: {
    es: { reason: "No se permiten precios especiales con descuento prohibido", accepted: "Modo 1 = Precio de venta/NORMAL", fix: "Cambia el modo de precio o permite descuentos" },
    en: { reason: "Special prices are not allowed when discounts are prohibited", accepted: "Mode 1 = Sale/NORMAL", fix: "Change the price mode or allow discounts" },
    zh: { reason: "禁止折扣时不允许使用特殊价格", accepted: "模式 1 = 销售/NORMAL", fix: "更改价格模式或允许折扣" }
  },
  VERSION_STALE: {
    es: { reason: "El producto cambió desde la vista previa", accepted: "Una vista previa vigente", fix: "Vuelve a generar la vista previa" },
    en: { reason: "The product changed since the preview", accepted: "A current preview", fix: "Generate the preview again" },
    zh: { reason: "商品在预览后已发生变化", accepted: "最新预览", fix: "重新生成预览" }
  },
  CONFIRMATION_REQUIRED: {
    es: { reason: "Se requiere confirmar los cambios del maestro", accepted: "Confirmación explícita", fix: "Confirma la actualización de productos" },
    en: { reason: "Master data changes require confirmation", accepted: "Explicit confirmation", fix: "Confirm the product update" },
    zh: { reason: "主数据更改需要确认", accepted: "明确确认", fix: "确认商品更新" }
  },
  PRODUCT_LOCAL_RESOLUTION_FAILED: {
    es: { reason: "El producto existente no se puede resolver en el catálogo local", accepted: "Un único producto del catálogo local", fix: "Recarga el catálogo y vuelve a leer el fichero" },
    en: { reason: "The existing product cannot be resolved in the local catalogue", accepted: "A single product from the local catalogue", fix: "Reload the catalogue and read the file again" },
    zh: { reason: "无法在本地商品目录中解析现有商品", accepted: "本地目录中的唯一商品", fix: "重新加载商品目录后再次读取文件" }
  }
};

function importErrorCopy(code: string): ImportErrorCatalog {
  if (["NUMBER_INVALID", "NUMBER_FORMAT_AMBIGUOUS", "NUMBER_SCALE_INVALID", "NUMBER_PRECISION_INVALID"].includes(code)) return copies("El número no tiene un formato o precisión válidos", "The number format or precision is invalid", "数字格式或精度无效", "Número con separadores y escala permitidos", "A number with allowed separators and scale", "使用允许分隔符和小数位的数字", "Corrige separadores, escala y precisión", "Correct separators, scale, and precision", "更正分隔符、小数位和精度");
  if (["GLOBAL_VALUE_UNKNOWN", "VALUE_SOURCE_UNKNOWN", "VALUE_SOURCE_INVALID"].includes(code)) return copies("El valor global u origen no está permitido", "The global value or source is not allowed", "全局值或来源不允许", "Un campo especial y origen excel/global", "A supported special field and excel/global source", "受支持字段及 excel/global 来源", "Corrige la configuración de valores", "Correct the value configuration", "更正值配置");
  if (["TOKEN_LIMIT", "TOKEN_UNEXPECTED", "TOKEN_INVALID", "CONCURRENCY_TOKEN_REQUIRED"].includes(code)) return copies("El contrato de concurrencia no es válido", "The concurrency contract is invalid", "并发契约无效", "Un token SHA-256 por fila aplicable", "One SHA-256 token per applicable row", "每个适用行一个 SHA-256 令牌", "Vuelve a previsualizar y conserva los tokens", "Preview again and keep the tokens", "重新预览并保留令牌");
  if (["VERSION_REQUIRED", "VERSION_STALE"].includes(code)) return copies("La versión del producto ya no es vigente", "The product version is no longer current", "商品版本已不是最新", "Versión y token de la vista previa", "The preview version and token", "预览版本和令牌", "Vuelve a generar la vista previa", "Generate the preview again", "重新生成预览");
  if (["MISSING_REVIEW_REQUIRED", "CONFIRMATION_REQUIRED"].includes(code)) return copies("Se requiere revisión o confirmación explícita", "Review or explicit confirmation is required", "需要审核或明确确认", "Una revisión confirmada por el usuario", "A user-confirmed review", "用户确认的审核", "Revisa el lote y confirma la acción", "Review the batch and confirm the action", "审核批次并确认操作");
  if (["APPLY_REQUIRED_VALUE", "APPLY_OFFER_REQUIRED"].includes(code)) return copies("Falta un valor obligatorio para guardar", "A required value for saving is missing", "缺少保存所需值", "Los campos obligatorios del producto", "The product required fields", "商品必填字段", "Completa los campos indicados", "Complete the indicated fields", "填写指定字段");
  if (["FILE_READ_FAILED"].includes(code)) return copies("No se pudo leer el fichero", "The file could not be read", "无法读取文件", "Un fichero accesible", "An accessible file", "可访问的文件", "Vuelve a seleccionar el fichero", "Select the file again", "重新选择文件");
  if (["SHEET_MISSING"].includes(code)) return copies("El libro no contiene hojas", "The workbook contains no sheets", "工作簿不包含工作表", "Al menos una hoja", "At least one sheet", "至少一个工作表", "Añade una hoja de cálculo", "Add a worksheet", "添加工作表");
  if (["GRID_ROW_LIMIT", "GRID_CELL_LIMIT", "CELL_LIMIT"].includes(code)) return copies("La cuadrícula supera sus límites de seguridad", "The grid exceeds its safety limits", "网格超过安全限制", "Hasta 100.000 filas y 250.000 celdas", "Up to 100,000 rows and 250,000 cells", "最多 100,000 行和 250,000 个单元格", "Reduce filas o celdas dispersas", "Reduce sparse rows or cells", "减少稀疏行或单元格");
  if (["CELL_TEXT_LIMIT", "TEXT_LIMIT"].includes(code)) return copies("El texto supera el límite de seguridad", "The text exceeds the safety limit", "文本超过安全限制", "Hasta 32.767 por celda y 5.000.000 total", "Up to 32,767 per cell and 5,000,000 total", "每个单元格最多 32,767、总计 5,000,000", "Acorta el texto del libro", "Shorten the workbook text", "缩短工作簿文本");
  if (["FORMULA_LIMIT"].includes(code)) return copies("El libro supera el límite de fórmulas", "The workbook exceeds the formula limit", "工作簿超过公式限制", "Hasta 20.000 fórmulas", "Up to 20,000 formulas", "最多 20,000 个公式", "Reduce fórmulas", "Reduce formulas", "减少公式");
  if (["FORMULA_NO_CACHE"].includes(code)) return copies("La fórmula no tiene resultado almacenado", "The formula has no cached result", "公式没有缓存结果", "Una fórmula con resultado guardado", "A formula with a stored result", "带保存结果的公式", "Calcula y guarda el libro", "Calculate and save the workbook", "计算并保存工作簿");
  if (["CELL_ERROR_VALUE", "FORMULA_RESULT_ERROR"].includes(code)) return copies("La celda o fórmula contiene un error de Excel", "The cell or formula contains an Excel error", "单元格或公式包含 Excel 错误", "Un valor calculado válido, sin errores como #DIV/0!", "A valid calculated value without errors such as #DIV/0!", "有效计算值，不含 #DIV/0! 等错误", "Corrige el error, recalcula y guarda el libro", "Fix the error, recalculate, and save the workbook", "修正错误、重新计算并保存工作簿");
  if (["COLUMN_LIMIT"].includes(code)) return copies("El libro supera 256 columnas", "The workbook exceeds 256 columns", "工作簿超过 256 列", "Columnas A-IV", "Columns A-IV", "A-IV 列", "Reduce las columnas", "Reduce the columns", "减少列数");
  if (["DISCOUNT_PROHIBITED_PRICE_MODE"].includes(code)) return copies("El modo de precio contradice el descuento prohibido", "The price mode conflicts with discount prohibition", "价格模式与禁止折扣冲突", "Modo normal cuando se prohíben descuentos", "Normal mode when discounts are prohibited", "禁止折扣时使用普通模式", "Cambia el modo o permite descuentos", "Change the mode or allow discounts", "更改模式或允许折扣");
  if (["SUMMARY_PREVIEW_INVALID"].includes(code)) return copies("La vista previa no permite el resumen", "The preview cannot produce the summary", "预览无法生成摘要", "Una vista previa sin errores globales", "A preview without global errors", "无全局错误的预览", "Corrige los errores globales", "Correct the global errors", "更正全局错误");
  if (["PRODUCT_LOCAL_RESOLUTION_FAILED"].includes(code)) return copies("No se puede resolver el producto local", "The local product cannot be resolved", "无法解析本地商品", "Un único producto del catálogo local", "A single local catalogue product", "本地目录中的唯一商品", "Recarga el catálogo", "Reload the catalogue", "重新加载目录");
  if (["ROW_INVALID"].includes(code)) return copies("La fila contiene un error", "The row contains an error", "该行包含错误", "Valores válidos para la fila", "Valid row values", "有效行值", "Corrige el valor indicado", "Correct the indicated value", "更正指定值");
  if (["OFFER_REQUIRED", "TAX_REQUIRED", "FAMILY_REQUIRED", "FAMILY_UNKNOWN", "FAMILY_AMBIGUOUS", "SUBFAMILY_UNKNOWN", "SUBFAMILY_AMBIGUOUS", "SUBFAMILY_FAMILY_MISMATCH"].includes(code)) return copies("Falta o no es válida una referencia obligatoria", "A required reference is missing or invalid", "缺少或无效的必填引用", "Una referencia activa y única de la tienda", "A unique active store reference", "门店唯一启用的引用", "Corrige la referencia del producto", "Correct the product reference", "更正商品引用");
  if (["NAME_REQUIRED", "QUANTITY_REQUIRED", "STOCK_RANGE_INVALID"].includes(code)) return copies("Falta un valor obligatorio o coherente", "A required or consistent value is missing", "缺少必填或一致的值", "Valores que cumplen las reglas del producto", "Values satisfying product rules", "符合商品规则的值", "Completa y corrige la fila", "Complete and correct the row", "填写并更正该行");
  switch (code) {
    case "FILE_EMPTY": return copies("Selecciona un fichero con contenido", "Select a non-empty workbook", "选择非空工作簿", "Un XLS/XLSX con datos", "An XLS/XLSX containing data", "包含数据的 XLS/XLSX", "Selecciona un fichero válido", "Select a valid workbook", "选择有效工作簿");
    case "FILE_TOO_LARGE": return copies("El fichero supera 10 MB", "The file exceeds 10 MB", "文件超过 10 MB", "Hasta 10 MB", "Up to 10 MB", "最大 10 MB", "Reduce el tamaño del fichero", "Reduce the file size", "减小文件大小");
    case "FILE_READ_FAILED": return copies("No se pudo leer el fichero", "The file could not be read", "无法读取文件", "Un fichero accesible", "An accessible file", "可访问的文件", "Vuelve a seleccionar el fichero", "Select the file again", "重新选择文件");
    case "WORKBOOK_LIMIT": return copies("El libro supera los límites de seguridad de descompresión", "The workbook exceeds decompression safety limits", "工作簿超过解压安全限制", "Entradas y expansión dentro de los límites", "Entries and expansion within the configured limits", "条目和解压大小在限制内", "Reduce el tamaño comprimido o divide el libro", "Reduce compressed size or split the workbook", "减小压缩大小或拆分工作簿");
    case "FILE_EXTENSION_INVALID": return copies("La extensión no está admitida", "The file extension is not supported", "不支持该文件扩展名", ".xls o .xlsx", ".xls or .xlsx", ".xls 或 .xlsx", "Usa una extensión XLS/XLSX", "Use an XLS/XLSX extension", "使用 XLS/XLSX 扩展名");
    case "WORKBOOK_UNREADABLE": return copies("No se puede leer el libro", "The workbook cannot be read", "无法读取工作簿", "Un libro XLS/XLSX completo", "A complete XLS/XLSX workbook", "完整的 XLS/XLSX 工作簿", "Abre y guarda de nuevo el libro", "Open and save the workbook again", "重新打开并保存工作簿");
    case "SHEET_MISSING": return copies("El libro no contiene hojas", "The workbook contains no sheets", "工作簿不包含工作表", "Al menos una hoja", "At least one sheet", "至少一个工作表", "Añade una hoja de cálculo", "Add a worksheet", "添加工作表");
    case "GRID_ROW_LIMIT": return copies("La cuadrícula supera 100.000 filas", "The grid exceeds 100,000 rows", "网格超过 100,000 行", "Hasta 100.000 filas", "Up to 100,000 rows", "最多 100,000 行", "Elimina filas dispersas", "Remove sparse rows", "删除稀疏行");
    case "GRID_CELL_LIMIT": return copies("La cuadrícula supera 250.000 celdas", "The grid exceeds 250,000 cells", "网格超过 250,000 个单元格", "Hasta 250.000 celdas", "Up to 250,000 cells", "最多 250,000 个单元格", "Reduce celdas materializadas", "Reduce materialized cells", "减少已物化单元格");
    case "CELL_LIMIT": return copies("Hay demasiadas celdas no vacías", "There are too many non-empty cells", "非空单元格过多", "Hasta 250.000 celdas no vacías", "Up to 250,000 non-empty cells", "最多 250,000 个非空单元格", "Divide el libro", "Split the workbook", "拆分工作簿");
    case "CELL_TEXT_LIMIT": return copies("Una celda supera 32.767 caracteres", "A cell exceeds 32,767 characters", "单元格超过 32,767 个字符", "Hasta 32.767 caracteres", "Up to 32,767 characters", "最多 32,767 个字符", "Acorta el contenido de la celda", "Shorten the cell content", "缩短单元格内容");
    case "TEXT_LIMIT": return copies("El texto materializado supera el límite", "Materialized text exceeds the limit", "已物化文本超过限制", "Hasta 5.000.000 caracteres", "Up to 5,000,000 characters", "最多 5,000,000 个字符", "Reduce textos y fórmulas", "Reduce text and formulas", "减少文本和公式");
    case "FORMULA_LIMIT": return copies("El libro supera 20.000 fórmulas", "The workbook exceeds 20,000 formulas", "工作簿超过 20,000 个公式", "Hasta 20.000 fórmulas", "Up to 20,000 formulas", "最多 20,000 个公式", "Reduce las fórmulas", "Reduce the formulas", "减少公式");
    case "MAPPING_REQUIRED":
    case "IDENTITY_MAPPING_REQUIRED": return copies("Falta configurar el mapeo de identidad", "The identity mapping is missing", "缺少标识映射", "Código, código de barras o nombre mapeado", "Mapped code, barcode, or name", "已映射编码、条码或名称", "Configura el mapeo antes de continuar", "Configure the mapping before continuing", "继续前配置映射");
    case "MAPPING_FIELD_UNKNOWN":
    case "UPDATE_FIELD_UNKNOWN": return copies("El campo de configuración no existe", "The configuration field does not exist", "配置字段不存在", "Un campo del contrato de importación", "A field from the import contract", "导入契约中的字段", "Elimina el campo desconocido", "Remove the unknown field", "删除未知字段");
    case "CONTRACT_LIMIT": return copies("La configuración supera el número de claves permitido", "The configuration contains too many keys", "配置包含过多键", "Mapas dentro de los límites del contrato", "Maps within the contract limits", "符合契约限制的映射", "Elimina las claves que no utilices", "Remove unused configuration keys", "删除未使用的配置键");
    case "VIEW_INVALID": return copies("La vista de exportación no es válida", "The export view is invalid", "导出视图无效", "Una pestaña del importador", "An importer tab", "导入器标签页", "Selecciona la pestaña y exporta de nuevo", "Select the tab and export again", "选择标签页后重新导出");
    case "COLUMN_INVALID":
    case "COLUMN_NOT_FOUND": return copies("La columna indicada no es válida", "The selected column is invalid", "指定列无效", "Una columna A-IV existente", "An existing A-IV column", "存在的 A-IV 列", "Corrige la letra de columna", "Correct the column letter", "更正列字母");
    case "CONTEXT_REQUIRED":
    case "CONTEXT_INVALID": return copies("El contexto operativo no es válido", "The operational context is invalid", "操作上下文无效", "STOCK, WAREHOUSE_INPUT o WAREHOUSE_OUTPUT", "STOCK, WAREHOUSE_INPUT, or WAREHOUSE_OUTPUT", "STOCK、WAREHOUSE_INPUT 或 WAREHOUSE_OUTPUT", "Selecciona un contexto permitido", "Select an allowed context", "选择允许的上下文");
    case "STORE_CONTEXT_MISMATCH":
    case "COMPANY_CONTEXT_MISMATCH": return copies("La organización no coincide con la sesión", "The organization does not match the session", "组织与会话不匹配", "La tienda y empresa activas", "The active store and company", "当前门店和企业", "Usa la organización activa", "Use the active organization", "使用当前组织");
    case "START_ROW_INVALID": return copies("La fila inicial no está dentro del contenido", "The start row is outside the content", "起始行不在内容范围内", "Una fila desde 2 dentro de la hoja", "A row from 2 within the sheet", "工作表中从第 2 行开始的行", "Corrige la fila inicial", "Correct the start row", "更正起始行");
    case "NO_ROWS_DETECTED": return copies("No hay filas con identidad importable", "No rows with an importable identity were found", "未找到可导入标识的行", "Código, código de barras o nombre", "Code, barcode, or name", "编码、条码或名称", "Mapea una identidad y revisa la fila inicial", "Map an identity and check the start row", "映射标识并检查起始行");
    case "CELL_EDIT_INVALID": return copies("La edición de celda no es válida", "The cell edit is invalid", "单元格编辑无效", "Fila/columna y valor dentro de los límites", "A row/column and value within limits", "范围内的行、列和值", "Corrige o elimina la edición", "Correct or remove the edit", "更正或删除编辑");
    case "EDIT_LIMIT": return copies("Hay demasiadas ediciones", "There are too many edits", "编辑过多", "Hasta 250.000 ediciones", "Up to 250,000 edits", "最多 250,000 次编辑", "Reduce las ediciones", "Reduce the edits", "减少编辑");
    case "EDIT_VALUE_LIMIT": return copies("El valor editado es demasiado largo", "The edited value is too long", "编辑值过长", "Hasta 32.767 caracteres", "Up to 32,767 characters", "最多 32,767 个字符", "Acorta el valor editado", "Shorten the edited value", "缩短编辑值");
    case "FIELD_LENGTH_INVALID": return copies("Un atributo supera su longitud permitida", "An attribute exceeds its allowed length", "属性超过允许长度", "La longitud máxima del atributo", "The attribute maximum length", "属性最大长度", "Acorta el valor indicado", "Shorten the indicated value", "缩短指定值");
    case "DUPLICATE_CONFLICT": return copies("Las filas duplicadas tienen datos incompatibles", "Duplicate rows contain incompatible data", "重复行包含不兼容数据", "Datos iguales o fusionables", "Equal or mergeable data", "相同或可合并的数据", "Revisa las identidades repetidas", "Review repeated identities", "检查重复标识");
    case "PRODUCT_TYPE_INVALID": return copies("El tipo de producto no es válido", "The product type is invalid", "商品类型无效", "1 = Unidad (UNIT), 2 = Peso (WEIGHT), 3 = Servicio (SERVICE)", "1 = Unit (UNIT), 2 = Weight (WEIGHT), 3 = Service (SERVICE)", "1 = 计件 (UNIT)，2 = 称重 (WEIGHT)，3 = 服务 (SERVICE)", "Selecciona un tipo válido", "Select a valid type", "选择有效类型");
    case "ZERO_PRICE_INVALID": return copies("El precio opcional cero no está permitido", "An optional zero price is not allowed", "不允许可选零价格", "Un precio vacío o positivo", "An empty or positive price", "空值或正价格", "Corrige el precio o activa la opción de ceros", "Correct the price or enable the zero-price option", "更正价格或启用零价格选项");
    case "NUMBER_INVALID": case "NUMBER_FORMAT_AMBIGUOUS": case "NUMBER_SCALE_INVALID": case "NUMBER_PRECISION_INVALID": return copies("El número no tiene un formato o precisión válidos", "The number format or precision is invalid", "数字格式或精度无效", "Número con separadores y escala permitidos", "A number with allowed separators and scale", "使用允许分隔符和小数位的数字", "Corrige separadores, escala y precisión", "Correct separators, scale, and precision", "更正分隔符、小数位和精度");
    case "GLOBAL_VALUE_UNKNOWN": case "VALUE_SOURCE_UNKNOWN": case "VALUE_SOURCE_INVALID": return copies("El valor global u origen no está permitido", "The global value or source is not allowed", "全局值或来源不允许", "Un campo especial y origen excel/global", "A supported special field and excel/global source", "受支持字段及 excel/global 来源", "Corrige la configuración de valores", "Correct the value configuration", "更正值配置");
    case "FILE_CHANGED": return copies("El fichero no coincide con la vista previa", "The file does not match the preview", "文件与预览不一致", "El SHA-256 de la vista previa", "The preview SHA-256", "预览中的 SHA-256", "Vuelve a leer el fichero", "Read the file again", "重新读取文件");
    case "HASH_REQUIRED": return copies("Falta el hash esperado", "The expected hash is missing", "缺少预期哈希", "SHA-256 obligatorio", "A required SHA-256", "必需的 SHA-256", "Genera una vista previa nueva", "Generate a new preview", "生成新预览");
    case "TOKEN_LIMIT": case "TOKEN_UNEXPECTED": case "TOKEN_INVALID": case "CONCURRENCY_TOKEN_REQUIRED": return copies("El contrato de concurrencia no es válido", "The concurrency contract is invalid", "并发契约无效", "Un token SHA-256 por fila aplicable", "One SHA-256 token per applicable row", "每个适用行一个 SHA-256 令牌", "Vuelve a previsualizar y conserva los tokens", "Preview again and keep the tokens", "重新预览并保留令牌");
    case "VERSION_REQUIRED": case "VERSION_STALE": return copies("La versión del producto ya no es vigente", "The product version is no longer current", "商品版本已不是最新", "Versión y token de la vista previa", "The preview version and token", "预览版本和令牌", "Vuelve a generar la vista previa", "Generate the preview again", "重新生成预览");
    case "MISSING_REVIEW_REQUIRED": case "CONFIRMATION_REQUIRED": return copies("Se requiere revisión o confirmación explícita", "Review or explicit confirmation is required", "需要审核或明确确认", "Una revisión confirmada por el usuario", "A user-confirmed review", "用户确认的审核", "Revisa el lote y confirma la acción", "Review the batch and confirm the action", "审核批次并确认操作");
    case "APPLY_CONTEXT_UNSUPPORTED": return copies("Este contexto no escribe el maestro", "This context cannot write master data", "此上下文不能写入主数据", "WAREHOUSE_INPUT para aplicar", "WAREHOUSE_INPUT for applying", "使用 WAREHOUSE_INPUT 应用", "Usa el flujo de edición correspondiente", "Use the corresponding edit flow", "使用相应编辑流程");
    case "APPLY_CONTEXT_INVALID": return copies("El almacén o proveedor ya no pertenece al contexto activo", "The warehouse or supplier no longer belongs to the active context", "仓库或供应商已不属于当前上下文", "Un almacén activo de la tienda y un proveedor activo de la empresa", "An active store warehouse and active company supplier", "当前门店的启用仓库及当前企业的启用供应商", "Revisa el documento y genera una vista previa nueva", "Review the document and generate a new preview", "检查单据并重新生成预览");
    case "APPLY_PROVENANCE_REQUIRED": return copies("Falta la procedencia autoritativa de la importación de Almacén", "The authoritative Warehouse import provenance is missing", "缺少仓库导入的权威来源凭证", "Metadatos firmados procedentes de Aplicar", "Signed metadata returned by Apply", "由应用操作返回的已签名元数据", "Vuelve a aplicar el fichero desde la entrada de almacén", "Apply the workbook again from the warehouse input", "从仓库入库单重新应用工作簿");
    case "APPLY_REQUIRED_VALUE": case "APPLY_OFFER_REQUIRED": return copies("Falta un valor obligatorio para guardar", "A required value for saving is missing", "缺少保存所需值", "Los campos obligatorios del producto", "The product required fields", "商品必填字段", "Completa los campos indicados", "Complete the indicated fields", "填写指定字段");
    case "APPLY_TRANSACTION_FAILED": return copies("La transacción no pudo completarse", "The transaction could not complete", "事务无法完成", "Un lote íntegro y válido", "A complete valid batch", "完整有效的批次", "Corrige el lote y vuelve a previsualizar", "Correct the batch and preview again", "更正批次并重新预览");
    case "SUMMARY_PREVIEW_INVALID": return copies("La vista previa no permite generar el resumen", "The preview cannot generate the summary", "预览无法生成摘要", "Una vista previa íntegra", "An integrity-valid preview", "完整有效的预览", "Resuelve los errores globales y vuelve a previsualizar", "Resolve global errors and preview again", "解决全局错误并重新预览");
    case "PERMISSION_DENIED": return copies("No tienes permiso para esta operación", "You do not have permission for this operation", "没有执行此操作的权限", "El permiso requerido por el contexto", "The permission required by the context", "上下文所需权限", "Solicita el permiso adecuado", "Request the required permission", "申请所需权限");
    case "PREVIEW_READ_FAILED": return copies("No se pudo leer la vista previa", "The preview could not be read", "无法读取预览", "Un fichero legible", "A readable workbook", "可读取的工作簿", "Vuelve a cargar el fichero", "Load the file again", "重新加载文件");
    case "LOCALE_INVALID": return copies("El idioma solicitado no es válido", "The requested language is invalid", "请求的语言无效", "es, en o zh", "es, en, or zh", "es、en 或 zh", "Selecciona uno de los idiomas disponibles", "Select one of the available languages", "选择可用语言之一");
    case "ERROR_LIMIT": return copies("Se han omitido detalles de errores por superar el límite de respuesta", "Some error details were omitted because the response detail limit was exceeded", "由于超过响应详情限制，部分错误详情已省略", "Hasta 5.000 detalles de fila y la clasificación ERROR conservada", "Up to 5,000 row details while preserving ERROR classification", "最多 5,000 条行详情，同时保留 ERROR 分类", "Corrige las filas indicadas y vuelve a previsualizar", "Correct the indicated rows and preview again", "更正指出的行后重新预览");
    case "TRANSPORT_REQUEST_TOO_LARGE": return copies("La petición del importador supera el límite de transporte", "The importer request exceeds the transport limit", "导入请求超过传输限制", "Una petición de importación de hasta 64 MiB", "An import request up to 64 MiB", "不超过 64 MiB 的导入请求", "Reduce el fichero o divide la configuración en varios lotes", "Reduce the file or split the configuration into smaller batches", "减小文件或将配置拆分为多个批次");

    case "NUMBER_FORMAT_UNSUPPORTED": return copies("El formato numérico no es compatible", "The numeric format is not supported", "数字格式不受支持", "Formato de hasta 1.024 caracteres, sin condiciones y con cero, uno o dos operadores %", "A format up to 1,024 characters, without conditions and with zero, one, or two % operators", "最多 1,024 个字符、无条件且包含零个、一个或两个 % 运算符的格式", "Aplica un formato numérico o porcentaje estándar", "Apply a standard numeric or percentage format", "使用标准数字或百分比格式");
    case "PERCENTAGE_FORMAT_NOT_ALLOWED": return copies("El porcentaje no está permitido para este atributo", "A percentage format is not allowed for this field", "此属性不允许使用百分比格式", "Formato numérico sin porcentaje para este atributo", "A numeric format without percentage for this field", "此属性使用不带百分号的数字格式", "Cambia la celda a formato numérico sin porcentaje", "Change the cell to a numeric format without percentage", "将单元格改为不带百分号的数字格式");
    case "IDENTIFIER_NUMERIC_PRECISION": return copies("El identificador numérico no conserva una precisión segura", "The numeric identifier does not preserve safe precision", "数字标识无法保留安全精度", "Un entero no negativo de hasta 15 dígitos o texto", "A non-negative integer of up to 15 digits or text", "最多 15 位的非负整数或文本", "Formatea la columna como Texto y vuelve a leer el fichero", "Format the column as Text and read the file again", "将列设置为文本后重新读取文件");
    default: throw new Error(`Missing localized import error catalog entry: ${code}`);
  }
}

function copies(esReason: string, enReason: string, zhReason: string, esAccepted: string, enAccepted: string, zhAccepted: string, esFix: string, enFix: string, zhFix: string): ImportErrorCatalog {
  return { es: { reason: esReason, accepted: esAccepted, fix: esFix }, en: { reason: enReason, accepted: enAccepted, fix: enFix }, zh: { reason: zhReason, accepted: zhAccepted, fix: zhFix } };
}

for (const code of PRODUCT_EXCEL_IMPORT_ERROR_CODES) {
  if (localizedImportErrorCatalog[code]) continue;
  localizedImportErrorCatalog[code] = importErrorCopy(code);
}

function localizedImportErrorText(
  error: ProductExcelImportError,
  part: "reason" | "accepted" | "fix",
  t: (key: string) => string,
  locale: LocaleCode
) {
  const catalogEntry = localizedImportErrorCatalog[error.code]?.[locale];
  if (catalogEntry) return catalogEntry[part];
  const key = `sharedExcel.error.code.${error.code}.${part}`;
  const translated = t(key);
  if (translated !== key) return translated;
  // Backend messages are currently Spanish. Do not leak them into the
  // English/Chinese screens when a future backend code has no catalog entry.
  if (locale !== "es") return t(`sharedExcel.error.generic${part[0].toUpperCase()}${part.slice(1)}`);
  return error[part === "accepted" ? "acceptedValues" : part === "fix" ? "recommendedFix" : "reason"]
    ?? t(`sharedExcel.error.generic${part[0].toUpperCase()}${part.slice(1)}`);
}


function renderStructuredImportErrors(
  errors: readonly ProductExcelImportError[],
  t: (key: string) => string,
  locale: LocaleCode
) {
  if (errors.length === 0) return null;
  return (
    <ul className="shared-excel-error-list">
      {errors.map((error, index) => (
        <li key={`${error.code}-${error.row ?? "global"}-${error.column ?? "none"}-${index}`}>
          <strong>{error.code}</strong>
          <span>{localizedImportErrorText(error, "reason", t, locale)}</span>
          {(error.row != null || error.column != null) && <span>
            {error.row != null ? `${t("sharedExcel.error.row")}: ${error.row}` : ""}
            {error.row != null && error.column != null ? " · " : ""}
            {error.column != null ? `${t("sharedExcel.error.column")}: ${excelColumnIndexToLetter(error.column - 1)}` : ""}
          </span>}
          {error.attribute != null && <span>{t("sharedExcel.error.attribute")}: {excelFieldDisplayName(error.attribute, locale)}</span>}
          {error.receivedValue != null && error.receivedValue !== "" && <span>{t("sharedExcel.error.received")}: {error.receivedValue}</span>}
          {(error.acceptedValues || error.code === "PRODUCT_LOCAL_RESOLUTION_FAILED") && <span>{t("sharedExcel.error.accepted")}: {error.code === "PRODUCT_LOCAL_RESOLUTION_FAILED"
            ? t("sharedExcel.error.localResolution.accepted")
            : localizedImportErrorText(error, "accepted", t, locale)}</span>}
          {(error.recommendedFix || error.code === "PRODUCT_LOCAL_RESOLUTION_FAILED") && <span>{t("sharedExcel.error.fix")}: {error.code === "PRODUCT_LOCAL_RESOLUTION_FAILED"
            ? t("sharedExcel.error.localResolution.fix")
            : localizedImportErrorText(error, "fix", t, locale)}</span>}
        </li>
      ))}
    </ul>
  );
}


function interpolateMessage(template: string, values: Record<string, string | number>) {
  return Object.entries(values).reduce(
    (result, [key, value]) => result.replaceAll(`{${key}}`, String(value)),
    template
  );
}

function stableSerialize(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableSerialize).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value as Record<string, unknown>).sort().map((key) => (
      `${JSON.stringify(key)}:${stableSerialize((value as Record<string, unknown>)[key])}`
    )).join(",")}}`;
  }
  return JSON.stringify(value) ?? "null";
}

function metadataDecimal(value: unknown): number | undefined {
  if (value === null || value === undefined || String(value).trim() === "") return undefined;
  const parsed = Number(String(value).trim().replace(",", "."));
  return Number.isFinite(parsed) ? parsed : undefined;
}

function downloadSummaryBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = fileName;
    link.click();
  } finally {
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
  }
}
