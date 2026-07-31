import { useEffect, useMemo, useRef, useState } from "react";
import type { PointerEvent, ReactNode } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, TerminalContext } from "../types";
import {
  classifyExcelProductRows,
  excelCellText,
  excelColumnIndexToLetter,
  excelColumnLetterToIndex,
  excelImportAccept,
  findExcelColumn,
  readExcelSheet,
  type ExcelCell,
  type ExcelColumnMapping,
  type ExcelImportClassifiedRow,
  type ExcelImportProductIdentity,
  type ExcelSheet
} from "./excelImport";
import {
  extractExcelFormulaMetadata,
  isExcelFormulaCell,
  recalculateExcelFormulas,
  type ExcelFormulaMetadata
} from "./excelFormula";

type ProductMappingKey = keyof ExcelColumnMapping;

export type SharedExcelImportUpdateField = ProductMappingKey;

export type SharedExcelImportAcceptedRow = ExcelImportClassifiedRow & {
  quantity: number;
  updateFields: Partial<Record<SharedExcelImportUpdateField, boolean>>;
};

export type SharedExcelImportMetadata = {
  fileName?: string;
  formulas: ExcelFormulaMetadata[];
};

type SharedExcelImportPanel = "mapping" | "summary" | "missing" | "priceChanged" | "accepted" | "errors";
type SharedExcelImportPriceSource = "purchasePrice" | "salePrice" | "memberPrice" | "wholesalePrice" | "offerPrice";

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
  onAddMissingAuto?: (rows: ExcelImportClassifiedRow[]) => Promise<ExcelImportProductIdentity[] | void> | ExcelImportProductIdentity[] | void;
  onAddMissingManual?: (rows: ExcelImportClassifiedRow[]) => void;
  onReviewMissing?: (row: ExcelImportClassifiedRow) => void;
  initialPanel?: SharedExcelImportPanel;
  terminalContext?: Pick<TerminalContext, "terminalCode" | "terminalId">;
};

type MappingField = {
  key: ProductMappingKey | "quantity";
  label: string;
  translatedLabels?: Record<LocaleCode, string>;
  aliases: string[];
  updateKey?: SharedExcelImportUpdateField;
};

const productMappingFields: MappingField[] = [
  { key: "code", label: "Codigo", aliases: ["codigo", "code"] },
  { key: "barcode", label: "Codigo de barras", aliases: ["codigo de barras", "codigo barras", "barcode", "ean"] },
  { key: "barcode2", label: "Codigo de barras 2", aliases: ["codigo de barras 2", "barcode 2", "barcode2", "ean 2"], updateKey: "barcode2" },
  { key: "name", label: "Nombre", aliases: ["nombre", "producto", "product", "name"], updateKey: "name" },
  { key: "description", label: "Descripcion", aliases: ["descripcion", "description"], updateKey: "description" },
  { key: "comments", label: "Comentarios", aliases: ["comentarios", "comments"], updateKey: "comments" },
  { key: "familyId", label: "Familia", aliases: ["familia", "family", "family id", "familyid"], updateKey: "familyId" },
  { key: "subfamilyId", label: "Subfamilia", aliases: ["subfamilia", "subfamily", "subfamily id", "subfamilyid"], updateKey: "subfamilyId" },
  { key: "taxId", label: "Impuesto", aliases: ["impuesto", "tax", "tax id", "taxid", "iva"], updateKey: "taxId" },
  { key: "productType", label: "Tipo", aliases: ["tipo", "product type", "producttype"], updateKey: "productType" },
  { key: "priceUseMode", label: "Usar precio", aliases: ["usar precio", "price use", "price use mode", "priceusemode"], updateKey: "priceUseMode" },
  { key: "discountType", label: "Tipo descuento", aliases: ["tipo descuento", "discount type", "discounttype"], updateKey: "discountType" },
  { key: "purchasePrice", label: "Precio compra", aliases: ["precio compra", "compra", "purchase price", "cost"], updateKey: "purchasePrice" },
  { key: "taxesIncluded", label: "IVA incluido", aliases: ["iva incluido", "impuestos incluidos", "taxes included", "taxesincluded"], updateKey: "taxesIncluded" },
  { key: "salePrice", label: "Precio venta", aliases: ["precio venta", "venta", "sale price", "price"], updateKey: "salePrice" },
  { key: "memberPrice", label: "Precio socio", aliases: ["precio socio", "member price", "memberprice"], updateKey: "memberPrice" },
  { key: "wholesalePrice", label: "Precio mayor", aliases: ["precio mayor", "wholesale price", "wholesaleprice"], updateKey: "wholesalePrice" },
  { key: "offerPrice", label: "Precio oferta", aliases: ["precio oferta", "offer price", "offerprice"], updateKey: "offerPrice" },
  { key: "offerDiscountPercent", label: "Descuento oferta %", aliases: ["descuento oferta", "descuento oferta %", "offer discount", "offer discount percent"], updateKey: "offerDiscountPercent" },
  { key: "offerActive", label: "Oferta activa", aliases: ["oferta activa", "offer active", "offeractive"], updateKey: "offerActive" },
  { key: "offerFrom", label: "Oferta desde", aliases: ["oferta desde", "offer from", "offerfrom"], updateKey: "offerFrom" },
  { key: "offerUntil", label: "Oferta hasta", aliases: ["oferta hasta", "offer until", "offeruntil"], updateKey: "offerUntil" },
  { key: "stockMin", label: "Stock min", aliases: ["stock min", "stock minimo", "stock mínimo", "minimum stock"], updateKey: "stockMin" },
  { key: "stockMax", label: "Stock max", aliases: ["stock max", "stock maximo", "stock máximo", "maximum stock"], updateKey: "stockMax" },
  { key: "quantity", label: "Cantidad", aliases: ["cantidad", "quantity", "unidades", "uds"] }
];

const sharedExcelFieldOrder: MappingField[] = [
  field("code", "Código", "Code", "编码", ["codigo", "codigo producto", "codigo articulo", "code", "sku", "referencia", "ref"]),
  field("barcode", "Código de barras", "Barcode", "条码", ["codigo de barras", "codigo barras", "barcode", "ean", "ean13", "gtin"]),
  field("name", "Nombre", "Name", "名称", ["nombre", "nombre producto", "producto", "product", "name", "denominacion"], "name"),
  field("description", "Descripción", "Description", "描述", ["descripcion", "descripcion larga", "description", "detalle"], "description"),
  field("quantity", "Cantidad", "Quantity", "数量", ["cantidad", "quantity", "unidades", "uds"]),
  field("purchaseDiscountPercent", "Descuento de compra", "Purchase discount", "采购折扣", ["descuento", "descuento compra", "descuento de compra", "descuento lineal", "dto", "dto compra", "purchase discount", "purchase discount percent"], "purchaseDiscountPercent"),
  field("productType", "Tipo de producto", "Product type", "商品类型", ["tipo producto", "tipo", "product type", "producttype"], "productType"),
  field("familyId", "Familia", "Family", "类别", ["familia", "family", "family id", "familyid"], "familyId"),
  field("subfamilyId", "Subfamilia", "Subfamily", "子类别", ["subfamilia", "subfamily", "subfamily id", "subfamilyid"], "subfamilyId"),
  field("purchasePrice", "Precio de compra", "Purchase price", "采购价", ["precio", "precio compra", "precio de compra", "precio coste", "precio de coste", "compra", "coste", "purchase price", "cost"], "purchasePrice"),
  field("salePrice", "Precio de venta", "Sale price", "售价", ["precio venta", "precio de venta", "venta", "sale price", "price"], "salePrice"),
  field("memberPrice", "Precio de socio", "Member price", "会员价", ["precio socio", "precio de socio", "member price", "memberprice"], "memberPrice"),
  field("wholesalePrice", "Precio mayorista", "Wholesale price", "批发价", ["precio mayor", "precio mayorista", "precio de mayorista", "wholesale price", "wholesaleprice"], "wholesalePrice"),
  field("offerPrice", "Precio de oferta", "Offer price", "促销价", ["precio oferta", "precio de oferta", "offer price", "offerprice"], "offerPrice"),
  field("offerDiscountPercent", "Descuento de oferta %", "Offer discount %", "促销折扣%", ["descuento oferta", "descuento de oferta", "descuento oferta %", "descuento de oferta %", "offer discount", "offer discount percent"], "offerDiscountPercent"),
  field("offerActive", "Oferta activa", "Offer active", "促销启用", ["oferta activa", "offer active", "offeractive"], "offerActive"),
  field("offerFrom", "Oferta desde", "Offer from", "促销开始", ["oferta desde", "offer from", "offerfrom"], "offerFrom"),
  field("offerUntil", "Oferta hasta", "Offer until", "促销结束", ["oferta hasta", "offer until", "offeruntil"], "offerUntil"),
  field("priceUseMode", "Usar precio", "Use price", "使用价格", ["usar precio", "price use", "price use mode", "priceusemode"], "priceUseMode"),
  field("discountType", "Prohibido descuento", "Discount prohibited", "禁止折扣", ["prohibido descuento", "no aplicar descuento", "discount prohibited", "discount type", "discounttype"], "discountType"),
  field("taxId", "Impuestos", "Tax", "税", ["impuestos", "impuesto", "tax", "tax id", "taxid", "iva"], "taxId"),
  field("taxesIncluded", "Impuestos incluidos", "Taxes included", "含税", ["impuestos incluidos", "iva incluido", "taxes included", "taxesincluded"], "taxesIncluded"),
  field("packageQuantity", "Cantidad por paquete", "Package quantity", "每包数量", ["cantidad por paquete", "package quantity", "pack quantity"], "packageQuantity"),
  field("stockMin", "Stock mínimo", "Minimum stock", "最低库存", ["stock min", "stock minimo", "stock mínimo", "minimum stock"], "stockMin"),
  field("stockMax", "Stock máximo", "Maximum stock", "最高库存", ["stock max", "stock maximo", "stock máximo", "maximum stock"], "stockMax")
];

type SharedExcelImportStoredSettings = {
  mapping: ExcelColumnMapping;
  quantityColumn: string;
  startRow: number;
  updateFields: Partial<Record<SharedExcelImportUpdateField, boolean>>;
  options: SharedExcelImportOptions;
};

type SharedExcelImportOptions = {
  autoAddMissing: boolean;
  generateSummaryDocument: boolean;
  showOnlyImported: boolean;
  skipZeroPriceUpdate: boolean;
  updateSupplier: boolean;
  priceSource: SharedExcelImportPriceSource;
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
  onAddMissingAuto,
  onAddMissingManual,
  onReviewMissing,
  initialPanel = "mapping",
  terminalContext
}: SharedExcelImportDialogProps) {
  const t = createTranslator(locale);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const dragScrollRef = useRef<DragScrollState | null>(null);
  const storedSettings = loadStoredExcelImportSettings(terminalContext);
  const hasInitialSheet = Boolean(providedSheet?.length);
  const [localFile, setLocalFile] = useState<File | null>(null);
  const [sheet, setSheet] = useState<ExcelSheet>(() => normalizeExcelDecimalCells(providedSheet ?? []));
  const [mapping, setMapping] = useState<ExcelColumnMapping>(hasInitialSheet ? storedSettings.mapping : {});
  const [quantityColumn, setQuantityColumn] = useState(hasInitialSheet ? storedSettings.quantityColumn : "");
  const [startRow, setStartRow] = useState(hasInitialSheet ? storedSettings.startRow : 2);
  const [updateFields, setUpdateFields] = useState<Partial<Record<SharedExcelImportUpdateField, boolean>>>(
    hasInitialSheet ? storedSettings.updateFields : {}
  );
  const [status, setStatus] = useState("");
  const [activePanel, setActivePanel] = useState<SharedExcelImportPanel>(initialPanel);
  const [refreshToken, setRefreshToken] = useState(0);
  const [createdProducts, setCreatedProducts] = useState<ExcelImportProductIdentity[]>([]);
  const [autoAddMissing, setAutoAddMissing] = useState(storedSettings.options.autoAddMissing);
  const [generateSummaryDocument, setGenerateSummaryDocument] = useState(storedSettings.options.generateSummaryDocument);
  const [showOnlyImported, setShowOnlyImported] = useState(storedSettings.options.showOnlyImported);
  const [skipZeroPriceUpdate, setSkipZeroPriceUpdate] = useState(storedSettings.options.skipZeroPriceUpdate);
  const [updateSupplier, setUpdateSupplier] = useState(storedSettings.options.updateSupplier);
  const [priceSource, setPriceSource] = useState<SharedExcelImportPriceSource>(storedSettings.options.priceSource);
  const [editingSheet, setEditingSheet] = useState(false);
  const selectedFile = localFile ?? file ?? null;

  useEffect(() => {
    if (!open) {
      setSheet([]);
      const nextStoredSettings = loadStoredExcelImportSettings(terminalContext);
      setMapping({});
      setQuantityColumn("");
      setStartRow(2);
      setUpdateFields({});
      setStatus("");
      setCreatedProducts([]);
      setActivePanel(initialPanel);
      setLocalFile(null);
      applyStoredOptions(nextStoredSettings.options);
      setEditingSheet(false);
      setAppliedRows(null);
      return;
    }
    if (providedSheet) {
      setSheet(normalizeExcelDecimalCells(providedSheet));
      setEditingSheet(false);
      return;
    }
    if (!selectedFile) {
      return;
    }
    let cancelled = false;
    setStatus(t("sharedExcel.status.reading"));
    void readExcelSheet(selectedFile)
      .then((nextSheet) => {
        if (!cancelled) {
          const normalizedSheet = normalizeExcelDecimalCells(nextSheet);
          setSheet(normalizedSheet);
          setEditingSheet(false);
          setAppliedRows(null);
          setStatus("");
        }
      })
      .catch(() => {
        if (!cancelled) {
          setStatus(t("sharedExcel.status.readError"));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [initialPanel, open, providedSheet, refreshToken, selectedFile, terminalContext]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const nextStoredSettings = loadStoredExcelImportSettings(terminalContext);
    applyStoredOptions(nextStoredSettings.options);
  }, [open, terminalContext]);

  useEffect(() => {
    if (!open || sheet.length === 0) {
      return;
    }
    const nextStoredSettings = loadStoredExcelImportSettings(terminalContext);
    const detected = detectExcelHeaderMapping(sheet);
    setMapping({ ...nextStoredSettings.mapping, ...detected.mapping });
    setQuantityColumn(detected.quantityColumn || nextStoredSettings.quantityColumn);
    setStartRow(nextStoredSettings.startRow);
    setUpdateFields({ ...nextStoredSettings.updateFields, ...detected.updateFields });
    setAppliedRows(null);
  }, [open, sheet, terminalContext]);

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
      if (sharedExcelImportKeyAction(event.key) !== "close") {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      onClose();
    };
    window.addEventListener("keydown", closeOnEscape, true);
    return () => window.removeEventListener("keydown", closeOnEscape, true);
  }, [onClose, open]);

  const importProducts = useMemo(() => [...products, ...createdProducts], [createdProducts, products]);
  const previewRows = useMemo(() => classifyExcelProductRows(
    sheet,
    mapping,
    importProducts,
    currentPurchasePrice,
    startRow
  ), [currentPurchasePrice, importProducts, mapping, sheet, startRow]);
  const [appliedRows, setAppliedRows] = useState<ExcelImportClassifiedRow[] | null>(null);
  const resultRows = appliedRows ?? [];
  const previewMissingRows = previewRows.filter((row) => row.status === "missing");
  const previewPriceChangedRows = previewRows.filter((row) => row.status === "purchasePriceChanged");
  const previewAcceptedRows = previewRows.filter((row) => row.status === "accepted");
  const previewErrorRows = previewRows.filter((row) => row.status === "error");
  const missingRows = resultRows.filter((row) => row.status === "missing");
  const priceChangedRows = resultRows.filter((row) => row.status === "purchasePriceChanged");
  const acceptedRows = resultRows.filter((row) => row.status === "accepted");
  const errorRows = resultRows.filter((row) => row.status === "error");
  const existingRows = [...acceptedRows, ...priceChangedRows];

  if (!open) {
    return null;
  }

  function acceptedRowsWithQuantity(sourceRows: ExcelImportClassifiedRow[]) {
    return sourceRows.map((row) => ({
      ...row,
      quantity: quantityFromRow(row.source, quantityColumn, requireQuantity),
      updateFields
    }));
  }

  function currentImportMetadata(): SharedExcelImportMetadata {
    return {
      fileName: selectedFile?.name,
      formulas: extractExcelFormulaMetadata(sheet)
    };
  }

  function currentStoredSettings(): SharedExcelImportStoredSettings {
    return {
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
        priceSource
      }
    };
  }

  function applyStoredOptions(options: SharedExcelImportOptions) {
    setAutoAddMissing(options.autoAddMissing);
    setGenerateSummaryDocument(options.generateSummaryDocument);
    setShowOnlyImported(options.showOnlyImported);
    setSkipZeroPriceUpdate(options.skipZeroPriceUpdate);
    setUpdateSupplier(options.updateSupplier);
    setPriceSource(options.priceSource);
  }

  function importAccepted() {
    saveStoredExcelImportSettings(terminalContext, currentStoredSettings());
    onImportAccepted(
      acceptedRowsWithQuantity([...acceptedRows, ...priceChangedRows]),
      currentImportMetadata()
    );
    onClose();
  }

  function clearMapping() {
    const defaults = defaultExcelImportOptions();
    setMapping({});
    setQuantityColumn("");
    setStartRow(2);
    setUpdateFields({});
    applyStoredOptions(defaults);
    clearStoredExcelImportSettings(terminalContext);
    setAppliedRows(null);
    setStatus(t("sharedExcel.status.cleared"));
  }

  function clearFile() {
    setSheet([]);
    setLocalFile(null);
    setMapping({});
    setQuantityColumn("");
    setStartRow(2);
    setUpdateFields({});
    if (fileInputRef.current) {
      fileInputRef.current.value = "";
    }
    setAppliedRows(null);
    setActivePanel("mapping");
    setEditingSheet(false);
    setStatus(t("sharedExcel.status.fileCleared"));
  }

  function toggleSheetEditing() {
    if (editingSheet) {
      setSheet((current) => normalizeExcelDecimalCells(current));
      setEditingSheet(false);
      setStatus(t("sharedExcel.status.editsSaved"));
      return;
    }
    setEditingSheet(true);
    setStatus("");
  }

  async function applyMapping() {
    saveStoredExcelImportSettings(terminalContext, currentStoredSettings());
    let nextRows = previewRows;
    if (previewMissingRows.length > 0) {
      if (autoAddMissing && onAddMissingAuto) {
        const created = await onAddMissingAuto(previewMissingRows);
        if (Array.isArray(created) && created.length > 0) {
          const nextProducts = [...importProducts, ...created];
          setCreatedProducts((current) => [...current, ...created]);
          nextRows = classifyExcelProductRows(sheet, mapping, nextProducts, currentPurchasePrice, startRow);
        }
      } else if (!autoAddMissing && onAddMissingManual) {
        onAddMissingManual(previewMissingRows);
      }
    }
    const nextMissingRows = nextRows.filter((row) => row.status === "missing");
    const nextPriceChangedRows = nextRows.filter((row) => row.status === "purchasePriceChanged");
    const nextAcceptedRows = nextRows.filter((row) => row.status === "accepted");
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
      accepted: nextAcceptedRows.length,
      missing: nextMissingRows.length,
      changed: nextPriceChangedRows.length,
      errors: nextErrorRows.length
    }));
  }

  function startDragScroll(event: PointerEvent<HTMLDivElement>) {
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
    element.setPointerCapture(event.pointerId);
  }

  function moveDragScroll(event: PointerEvent<HTMLDivElement>) {
    const drag = dragScrollRef.current;
    if (!drag || drag.pointerId !== event.pointerId) {
      return;
    }
    const deltaX = event.clientX - drag.startX;
    const deltaY = event.clientY - drag.startY;
    if (Math.abs(deltaX) > 2 || Math.abs(deltaY) > 2) {
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
    if (drag.element.hasPointerCapture(event.pointerId)) {
      drag.element.releasePointerCapture(event.pointerId);
    }
    dragScrollRef.current = null;
  }

  return (
    <div className="filter-overlay shared-excel-overlay" role="dialog" aria-modal="true" aria-labelledby="shared-excel-title">
      <section className="filter-dialog shared-excel-dialog">
        <header className="shared-excel-toolbar">
          <input
            ref={fileInputRef}
            type="file"
            accept={excelImportAccept}
            className="shared-excel-file-input"
            onChange={(event) => {
              setLocalFile(event.currentTarget.files?.[0] ?? null);
              setEditingSheet(false);
              setAppliedRows(null);
              setActivePanel("mapping");
            }}
          />
          <div className="shared-excel-title">
            <h2 id="shared-excel-title">{title ?? t("stock.bulkEdit.importExcel")}</h2>
            <span>{selectedFile?.name ?? excelImportAccept}</span>
          </div>
          <div className="shared-excel-toolbar-actions">
            <button type="button" onClick={() => fileInputRef.current?.click()}>{t("sharedExcel.open")}</button>
            <button
              type="button"
              disabled={sheet.length === 0}
              aria-pressed={editingSheet}
              onClick={toggleSheetEditing}
            >
              {t(editingSheet ? "sharedExcel.finishEdit" : "sharedExcel.openEdit")}
            </button>
            <button type="button" onClick={() => {
              setRefreshToken((value) => value + 1);
              setStatus(t("sharedExcel.status.refreshed"));
            }}>{t("sharedExcel.refresh")}</button>
            <button type="button" onClick={clearFile}>{t("sharedExcel.clearFile")}</button>
            <button type="button" onClick={onClose}>{t("sharedExcel.back")}</button>
          </div>
        </header>

        <div className="shared-excel-top-pane">
          {sheet.length === 0 ? (
            <div className="shared-excel-empty-preview">
              <h3>{t("sharedExcel.summary.title")}</h3>
              <ol>
                {summaryItems(t).map((item) => <li key={item}>{item}</li>)}
              </ol>
            </div>
          ) : (
            <div
              className={`shared-excel-preview${editingSheet ? " shared-excel-preview--editing" : ""}`}
              onPointerCancel={endDragScroll}
              onPointerDown={startDragScroll}
              onPointerMove={moveDragScroll}
              onPointerUp={endDragScroll}
            >
              <table>
                <thead>
                  <tr>
                    <th>#</th>
                    {(sheet[0] ?? []).map((_, index) => <th key={index}>{excelColumnIndexToLetter(index)}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {sheet.map((row, rowIndex) => (
                    <tr key={rowIndex}>
                      <th>{rowIndex + 1}</th>
                      {Array.from({ length: sheet[0]?.length ?? row.length }).map((_, cellIndex) => (
                        <td key={cellIndex}>
                          {editingSheet ? (
                            <input
                              className="shared-excel-cell-input"
                              aria-label={`${excelColumnIndexToLetter(cellIndex)}${rowIndex + 1}`}
                              size={Math.min(50, Math.max(8, excelCellText(row[cellIndex]).length))}
                              value={excelCellText(row[cellIndex])}
                              onChange={(event) => {
                                setSheet((current) => updateExcelSheetCell(
                                  current,
                                  rowIndex,
                                  cellIndex,
                                  event.target.value
                                ));
                                setAppliedRows(null);
                              }}
                              onBlur={(event) => {
                                const normalized = normalizeExcelDecimalValue(event.currentTarget.value);
                                if (normalized !== event.currentTarget.value) {
                                  setSheet((current) => updateExcelSheetCell(
                                    current,
                                    rowIndex,
                                    cellIndex,
                                    normalized
                                  ));
                                }
                              }}
                            />
                          ) : excelCellText(row[cellIndex])}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
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
                    value={startRow}
                    onChange={(event) => {
                      setStartRow(Math.max(2, Number(event.target.value) || 2));
                      setAppliedRows(null);
                    }}
                  />
                </label>
                <span>{interpolateMessage(t("sharedExcel.detectedRows"), { count: previewRows.length })}</span>
                <span>{interpolateMessage(t("sharedExcel.acceptedRows"), { count: previewAcceptedRows.length })}</span>
                <span>{interpolateMessage(t("sharedExcel.missingRows"), { count: previewMissingRows.length })}</span>
              </div>
              <div className="shared-excel-options">
                <label><input type="checkbox" checked={autoAddMissing} onChange={(event) => setAutoAddMissing(event.target.checked)} /> {t("sharedExcel.option.autoAdd")}</label>
                <label><input type="checkbox" checked={generateSummaryDocument} onChange={(event) => setGenerateSummaryDocument(event.target.checked)} /> {t("sharedExcel.option.summaryDocument")}</label>
                <label><input type="checkbox" checked={showOnlyImported} onChange={(event) => setShowOnlyImported(event.target.checked)} /> {t("sharedExcel.option.onlyImported")}</label>
                <label><input type="checkbox" checked={skipZeroPriceUpdate} onChange={(event) => setSkipZeroPriceUpdate(event.target.checked)} /> {t("sharedExcel.option.skipZeroPrice")}</label>
                <label><input type="checkbox" checked={updateSupplier} onChange={(event) => setUpdateSupplier(event.target.checked)} /> {t("sharedExcel.option.updateSupplier")}</label>
                <label>
                  <span>{t("sharedExcel.priceSource")}</span>
                  <select value={priceSource} onChange={(event) => setPriceSource(event.target.value as SharedExcelImportPriceSource)}>
                    <option value="purchasePrice">{t("sharedExcel.price.purchase")}</option>
                    <option value="salePrice">{t("sharedExcel.price.sale")}</option>
                    <option value="memberPrice">{t("sharedExcel.price.member")}</option>
                    <option value="wholesalePrice">{t("sharedExcel.price.wholesale")}</option>
                    <option value="offerPrice">{t("sharedExcel.price.offer")}</option>
                  </select>
                </label>
              </div>
              <div className="shared-excel-mapping">
                {sharedExcelFieldOrder.map((field) => (
                  <label key={field.key}>
                    <span>{fieldLabel(field, locale)}</span>
                    <input
                      type="text"
                      value={field.key === "quantity" ? quantityColumn : mapping[field.key] ?? ""}
                      onChange={(event) => {
                        const value = event.target.value.toUpperCase();
                        if (field.key === "quantity") {
                          setQuantityColumn(value);
                        } else {
                          setMapping((current) => ({ ...current, [field.key]: value }));
                        }
                        setAppliedRows(null);
                      }}
                    />
                    {field.updateKey ? renderUpdateCheckbox(field.updateKey, fieldLabel(field, locale), updateFields, setUpdateFields, t) : <span />}
                  </label>
                ))}
              </div>
              <div className="shared-excel-config-actions">
                <button type="button" onClick={clearMapping}>{t("sharedExcel.clearConfiguration")}</button>
                <button type="button" onClick={() => void applyMapping()}>{t("common.apply")}</button>
              </div>
            </div>
          ) : (
            <div
              className="shared-excel-results"
              onPointerCancel={endDragScroll}
              onPointerDown={startDragScroll}
              onPointerMove={moveDragScroll}
              onPointerUp={endDragScroll}
            >
              {activePanel === "summary" && renderResultTable({
                title: t("sharedExcel.result.summary"),
                rows: existingRows,
                actions: null,
                summaryMode: true,
                t
              })}
              {activePanel === "missing" && renderResultTable({
                title: autoAddMissing ? t("sharedExcel.result.missingAuto") : t("sharedExcel.result.missing"),
                rows: missingRows,
                actions: (
                  <>
                    {onAddMissingAuto && <button type="button" onClick={() => onAddMissingAuto(missingRows)}>{t("sharedExcel.autoAdd")}</button>}
                  </>
                ),
                reviewRow: onReviewMissing,
                t
              })}
              {activePanel === "priceChanged" && renderResultTable({
                title: t("sharedExcel.result.purchaseChanged"),
                rows: priceChangedRows,
                actions: <button type="button" onClick={() => onImportAccepted(
                  acceptedRowsWithQuantity(priceChangedRows),
                  currentImportMetadata()
                )}>{t("sharedExcel.update")}</button>,
                currentPurchasePrice,
                showPurchasePriceDiff: true,
                t
              })}
              {activePanel === "accepted" && renderResultTable({
                title: t("sharedExcel.result.accepted"),
                rows: acceptedRows,
                actions: <button type="button" onClick={importAccepted}>{t("sharedExcel.importDocument")}</button>,
                t
              })}
              {activePanel === "errors" && renderResultTable({
                title: t("sharedExcel.result.errors"),
                rows: errorRows,
                actions: null,
                t
              })}
            </div>
          )}
        </div>

        {status && <p className="shared-excel-status" role="status">{status}</p>}

        <nav className="shared-excel-bottom-tabs" aria-label={t("sharedExcel.sections")}>
          {renderPanelTab("mapping", t("sharedExcel.tab.configuration"), activePanel, setActivePanel)}
          {renderPanelTab("summary", interpolateMessage(t("sharedExcel.tab.summary"), { count: existingRows.length }), activePanel, setActivePanel)}
          {renderPanelTab("missing", interpolateMessage(t("sharedExcel.tab.missing"), { count: missingRows.length }), activePanel, setActivePanel)}
          {renderPanelTab("priceChanged", interpolateMessage(t("sharedExcel.tab.purchaseChanged"), { count: priceChangedRows.length }), activePanel, setActivePanel)}
          {renderPanelTab("accepted", interpolateMessage(t("sharedExcel.tab.accepted"), { count: acceptedRows.length }), activePanel, setActivePanel)}
          {renderPanelTab("errors", interpolateMessage(t("sharedExcel.tab.errors"), { count: errorRows.length }), activePanel, setActivePanel)}
        </nav>
      </section>
    </div>
  );
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

export function detectExcelHeaderMapping(sheet: ExcelSheet): {
  mapping: ExcelColumnMapping;
  quantityColumn: string;
  updateFields: Partial<Record<SharedExcelImportUpdateField, boolean>>;
} {
  const headers = (sheet[0] ?? []).map(excelCellText);
  const mapping: ExcelColumnMapping = {};
  const updateFields: Partial<Record<SharedExcelImportUpdateField, boolean>> = {};
  let quantityColumn = "";

  sharedExcelFieldOrder.forEach((mappingField) => {
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
    }
  });

  return { mapping, quantityColumn, updateFields };
}

export function updateExcelSheetCell(
  sheet: ExcelSheet,
  rowIndex: number,
  columnIndex: number,
  value: ExcelCell
): ExcelSheet {
  const currentCell = sheet[rowIndex]?.[columnIndex];
  const nextValue = typeof value === "string" && value.trim().startsWith("=")
    ? {
        kind: "formula" as const,
        formula: value.trim().slice(1),
        value: isExcelFormulaCell(currentCell) ? currentCell.value : excelCellText(currentCell)
      }
    : value;
  const updatedSheet = sheet.map((row, currentRowIndex) => currentRowIndex === rowIndex
    ? Array.from(
        { length: Math.max(row.length, columnIndex + 1) },
        (_, currentColumnIndex) => currentColumnIndex === columnIndex ? nextValue : row[currentColumnIndex] ?? ""
      )
    : row
  );
  return normalizeExcelDecimalCells(recalculateExcelFormulas(updatedSheet));
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

function summaryItems(t: (key: string) => string) {
  return Array.from({ length: 9 }, (_, index) => t(`sharedExcel.summary.${index + 1}`));
}

function renderUpdateCheckbox(
  field: SharedExcelImportUpdateField,
  label: string,
  updateFields: Partial<Record<SharedExcelImportUpdateField, boolean>>,
  setUpdateFields: (updater: (current: Partial<Record<SharedExcelImportUpdateField, boolean>>) => Partial<Record<SharedExcelImportUpdateField, boolean>>) => void,
  t: (key: string) => string
) {
  return (
    <input
      type="checkbox"
      checked={Boolean(updateFields[field])}
      onChange={(event) => setUpdateFields((current) => ({
        ...current,
        [field]: event.target.checked
      }))}
      aria-label={interpolateMessage(t("sharedExcel.updateField"), { field: label })}
    />
  );
}

function quantityFromRow(row: readonly ExcelCell[], column: string, required: boolean) {
  const index = excelColumnLetterToIndex(column);
  const quantity = index < 0 ? (required ? 0 : 1) : Number(excelCellText(row[index]).replace(",", "."));
  return Number.isFinite(quantity) && quantity > 0 ? quantity : required ? 0 : 1;
}

function defaultStoredExcelImportSettings(): SharedExcelImportStoredSettings {
  return {
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
    priceSource: "purchasePrice"
  };
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
    return {
      mapping: parsed.mapping && typeof parsed.mapping === "object" ? parsed.mapping : {},
      quantityColumn: typeof parsed.quantityColumn === "string" ? parsed.quantityColumn : "",
      startRow: typeof parsed.startRow === "number" && Number.isFinite(parsed.startRow) ? Math.max(2, parsed.startRow) : 2,
      updateFields: parsed.updateFields && typeof parsed.updateFields === "object" ? parsed.updateFields : {},
      options: {
        autoAddMissing: booleanSetting(parsedOptions.autoAddMissing, defaultOptions.autoAddMissing),
        generateSummaryDocument: booleanSetting(parsedOptions.generateSummaryDocument, defaultOptions.generateSummaryDocument),
        showOnlyImported: booleanSetting(parsedOptions.showOnlyImported, defaultOptions.showOnlyImported),
        skipZeroPriceUpdate: booleanSetting(parsedOptions.skipZeroPriceUpdate, defaultOptions.skipZeroPriceUpdate),
        updateSupplier: booleanSetting(parsedOptions.updateSupplier, defaultOptions.updateSupplier),
        priceSource: isExcelImportPriceSource(parsedOptions.priceSource) ? parsedOptions.priceSource : defaultOptions.priceSource
      }
    };
  } catch {
    return defaultStoredExcelImportSettings();
  }
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

function renderResultTable({
  title,
  rows,
  actions,
  reviewRow,
  currentPurchasePrice,
  showPurchasePriceDiff = false,
  summaryMode = false,
  t
}: {
  title: string;
  rows: ExcelImportClassifiedRow[];
  actions: ReactNode;
  reviewRow?: (row: ExcelImportClassifiedRow) => void;
  currentPurchasePrice?: (product: ExcelImportProductIdentity) => string | number | null | undefined;
  showPurchasePriceDiff?: boolean;
  summaryMode?: boolean;
  t: (key: string) => string;
}) {
  const colSpan = (reviewRow ? 7 : 6) + (showPurchasePriceDiff ? 1 : 0) + (summaryMode ? 1 : 0);
  return (
    <section>
      <header>
        <h3>{title}</h3>
        <span>{rows.length}</span>
        <button type="button" onClick={() => exportRows(title, rows)}>{t("sharedExcel.export")}</button>
        {actions}
      </header>
      <table>
        <thead>
          <tr>
            <th>{t("sharedExcel.column.row")}</th>
            <th>{t("sharedExcel.column.code")}</th>
            <th>{t("sharedExcel.column.barcode")}</th>
            <th>{t("sharedExcel.column.name")}</th>
            {showPurchasePriceDiff ? (
              <>
                <th>{t("sharedExcel.column.currentPurchasePrice")}</th>
                <th>{t("sharedExcel.column.newPurchasePrice")}</th>
              </>
            ) : (
              <th>{t("sharedExcel.column.purchasePrice")}</th>
            )}
            <th>{t("sharedExcel.column.salePrice")}</th>
            {summaryMode && <th>{t("sharedExcel.column.status")}</th>}
            {reviewRow && <th>{t("sharedExcel.column.review")}</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.rowNumber}>
              <td>{row.rowNumber}</td>
              <td>{row.draft.code || "-"}</td>
              <td>{row.draft.barcode || "-"}</td>
              <td><span className="product-name-text">{row.draft.name || "-"}</span></td>
              {showPurchasePriceDiff ? (
                <>
                  <td>{currentPurchasePriceText(row, currentPurchasePrice)}</td>
                  <td>{row.draft.purchasePrice}</td>
                </>
              ) : (
                <td>{row.draft.purchasePrice}</td>
              )}
              <td>{row.draft.salePrice}</td>
              {summaryMode && <td>{t(row.status === "purchasePriceChanged" ? "sharedExcel.status.purchaseChanged" : "sharedExcel.status.existing")}</td>}
              {reviewRow && (
                <td><button type="button" onClick={() => reviewRow(row)}>{t("sharedExcel.manual")}</button></td>
              )}
            </tr>
          ))}
          {rows.length === 0 && (
            <tr>
              <td colSpan={colSpan}>{t("sharedExcel.emptyRows")}</td>
            </tr>
          )}
        </tbody>
      </table>
    </section>
  );
}

function currentPurchasePriceText(
  row: ExcelImportClassifiedRow,
  currentPurchasePrice?: (product: ExcelImportProductIdentity) => string | number | null | undefined
) {
  if (!row.product || !currentPurchasePrice) {
    return "-";
  }
  const value = currentPurchasePrice(row.product);
  return value === null || value === undefined || value === "" ? "-" : String(value);
}

function exportRows(title: string, rows: ExcelImportClassifiedRow[]) {
  const csv = [
    ["fila", "codigo", "codigo_barras", "nombre", "precio_compra", "precio_venta"],
    ...rows.map((row) => [
      row.rowNumber,
      row.draft.code,
      row.draft.barcode,
      row.draft.name,
      row.draft.purchasePrice,
      row.draft.salePrice
    ])
  ].map((line) => line.map((value) => `"${String(value).replaceAll("\"", "\"\"")}"`).join(";")).join("\n");
  const link = document.createElement("a");
  link.href = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
  link.download = `${title.toLowerCase().replace(/\s+/g, "-")}.csv`;
  link.click();
  URL.revokeObjectURL(link.href);
}

function interpolateMessage(template: string, values: Record<string, string | number>) {
  return Object.entries(values).reduce(
    (result, [key, value]) => result.replaceAll(`{${key}}`, String(value)),
    template
  );
}
