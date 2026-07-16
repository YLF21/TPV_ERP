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
  readExcelSheet,
  type ExcelCell,
  type ExcelColumnMapping,
  type ExcelImportClassifiedRow,
  type ExcelImportProductIdentity,
  type ExcelSheet
} from "./excelImport";

type ProductMappingKey = keyof ExcelColumnMapping;

export type SharedExcelImportUpdateField = ProductMappingKey;

export type SharedExcelImportAcceptedRow = ExcelImportClassifiedRow & {
  quantity: number;
  updateFields: Partial<Record<SharedExcelImportUpdateField, boolean>>;
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
  onImportAccepted: (rows: SharedExcelImportAcceptedRow[]) => void;
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
  field("code", "Codigo", "Code", "编码", ["codigo", "code"]),
  field("barcode", "Codigo de barras", "Barcode", "条码", ["codigo de barras", "codigo barras", "barcode", "ean"]),
  field("name", "Nombre", "Name", "名称", ["nombre", "producto", "product", "name"], "name"),
  field("description", "Descripcion", "Description", "描述", ["descripcion", "description"], "description"),
  field("quantity", "Cantidad", "Quantity", "数量", ["cantidad", "quantity", "unidades", "uds"]),
  field("purchaseDiscountPercent", "Descuento compra", "Purchase discount", "采购折扣", ["descuento compra", "purchase discount", "purchase discount percent"], "purchaseDiscountPercent"),
  field("productType", "Tipo producto", "Product type", "商品类型", ["tipo producto", "tipo", "product type", "producttype"], "productType"),
  field("familyId", "Familia", "Family", "类别", ["familia", "family", "family id", "familyid"], "familyId"),
  field("subfamilyId", "Subfamilia", "Subfamily", "子类别", ["subfamilia", "subfamily", "subfamily id", "subfamilyid"], "subfamilyId"),
  field("purchasePrice", "Precio compra", "Purchase price", "采购价", ["precio compra", "compra", "purchase price", "cost"], "purchasePrice"),
  field("salePrice", "Precio venta", "Sale price", "售价", ["precio venta", "venta", "sale price", "price"], "salePrice"),
  field("memberPrice", "Precio socio", "Member price", "会员价", ["precio socio", "member price", "memberprice"], "memberPrice"),
  field("wholesalePrice", "Precio mayor", "Wholesale price", "批发价", ["precio mayor", "wholesale price", "wholesaleprice"], "wholesalePrice"),
  field("offerPrice", "Precio oferta", "Offer price", "促销价", ["precio oferta", "offer price", "offerprice"], "offerPrice"),
  field("offerDiscountPercent", "Descuento oferta %", "Offer discount %", "促销折扣%", ["descuento oferta", "descuento oferta %", "offer discount", "offer discount percent"], "offerDiscountPercent"),
  field("offerActive", "Oferta activa", "Offer active", "促销启用", ["oferta activa", "offer active", "offeractive"], "offerActive"),
  field("offerFrom", "Oferta desde", "Offer from", "促销开始", ["oferta desde", "offer from", "offerfrom"], "offerFrom"),
  field("offerUntil", "Oferta hasta", "Offer until", "促销结束", ["oferta hasta", "offer until", "offeruntil"], "offerUntil"),
  field("priceUseMode", "Usar precio", "Use price", "使用价格", ["usar precio", "price use", "price use mode", "priceusemode"], "priceUseMode"),
  field("discountType", "Prohibido descuento", "Discount prohibited", "禁止折扣", ["prohibido descuento", "no aplicar descuento", "discount prohibited", "discount type", "discounttype"], "discountType"),
  field("taxId", "Impuestos", "Tax", "税", ["impuestos", "impuesto", "tax", "tax id", "taxid", "iva"], "taxId"),
  field("taxesIncluded", "Impuestos incluidos", "Taxes included", "含税", ["impuestos incluidos", "iva incluido", "taxes included", "taxesincluded"], "taxesIncluded"),
  field("packageQuantity", "Cantidad por paquete", "Package quantity", "每包数量", ["cantidad por paquete", "package quantity", "pack quantity"], "packageQuantity"),
  field("stockMin", "Stock min", "Stock min", "最低库存", ["stock min", "stock minimo", "stock mínimo", "minimum stock"], "stockMin"),
  field("stockMax", "Stock max", "Stock max", "最高库存", ["stock max", "stock maximo", "stock máximo", "maximum stock"], "stockMax")
];

type SharedExcelImportStoredSettings = {
  mapping: ExcelColumnMapping;
  quantityColumn: string;
  startRow: number;
  updateFields: Partial<Record<SharedExcelImportUpdateField, boolean>>;
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
  const [localFile, setLocalFile] = useState<File | null>(null);
  const [sheet, setSheet] = useState<ExcelSheet>(providedSheet ?? []);
  const [mapping, setMapping] = useState<ExcelColumnMapping>(storedSettings.mapping);
  const [quantityColumn, setQuantityColumn] = useState(storedSettings.quantityColumn);
  const [startRow, setStartRow] = useState(storedSettings.startRow);
  const [updateFields, setUpdateFields] = useState<Partial<Record<SharedExcelImportUpdateField, boolean>>>(storedSettings.updateFields);
  const [status, setStatus] = useState("");
  const [activePanel, setActivePanel] = useState<SharedExcelImportPanel>(initialPanel);
  const [refreshToken, setRefreshToken] = useState(0);
  const [createdProducts, setCreatedProducts] = useState<ExcelImportProductIdentity[]>([]);
  const [autoAddMissing, setAutoAddMissing] = useState(true);
  const [generateSummaryDocument, setGenerateSummaryDocument] = useState(false);
  const [showOnlyImported, setShowOnlyImported] = useState(false);
  const [skipZeroPriceUpdate, setSkipZeroPriceUpdate] = useState(true);
  const [updateSupplier, setUpdateSupplier] = useState(false);
  const [priceSource, setPriceSource] = useState<SharedExcelImportPriceSource>("purchasePrice");
  const selectedFile = localFile ?? file ?? null;

  useEffect(() => {
    if (!open) {
      setSheet([]);
      const nextStoredSettings = loadStoredExcelImportSettings(terminalContext);
      setMapping(nextStoredSettings.mapping);
      setQuantityColumn(nextStoredSettings.quantityColumn);
      setStartRow(nextStoredSettings.startRow);
      setUpdateFields(nextStoredSettings.updateFields);
      setStatus("");
      setCreatedProducts([]);
      setActivePanel(initialPanel);
      setLocalFile(null);
      setAutoAddMissing(true);
      setGenerateSummaryDocument(false);
      setShowOnlyImported(false);
      setSkipZeroPriceUpdate(true);
      setUpdateSupplier(false);
      setPriceSource("purchasePrice");
      setAppliedRows(null);
      return;
    }
    if (providedSheet) {
      setSheet(providedSheet);
      return;
    }
    if (!selectedFile) {
      return;
    }
    let cancelled = false;
    setStatus("Leyendo Excel...");
    void readExcelSheet(selectedFile)
      .then((nextSheet) => {
        if (!cancelled) {
          setSheet(nextSheet);
          setAppliedRows(null);
          setStatus("");
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setStatus(error instanceof Error ? error.message : "No se pudo leer el Excel");
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
    setMapping(nextStoredSettings.mapping);
    setQuantityColumn(nextStoredSettings.quantityColumn);
    setStartRow(nextStoredSettings.startRow);
    setUpdateFields(nextStoredSettings.updateFields);
  }, [open, terminalContext]);

  useEffect(() => {
    if (open) {
      setActivePanel(initialPanel);
    }
  }, [file, initialPanel, open, providedSheet]);

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

  function importAccepted() {
    saveStoredExcelImportSettings(terminalContext, { mapping, quantityColumn, startRow, updateFields });
    onImportAccepted(acceptedRowsWithQuantity([...acceptedRows, ...priceChangedRows]));
    onClose();
  }

  function clearMapping() {
    setMapping({});
    setQuantityColumn("");
    setStartRow(2);
    setUpdateFields({});
    setAppliedRows(null);
    setStatus("Relleno limpiado");
  }

  async function applyMapping() {
    saveStoredExcelImportSettings(terminalContext, { mapping, quantityColumn, startRow, updateFields });
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
    setStatus(`Aplicado: ${nextAcceptedRows.length} aceptadas, ${nextMissingRows.length} no existentes, ${nextPriceChangedRows.length} con precio compra distinto, ${nextErrorRows.length} errores`);
  }

  function openExcelFile(fileToOpen: File | null) {
    if (!fileToOpen) {
      fileInputRef.current?.click();
      return;
    }
    const url = URL.createObjectURL(fileToOpen);
    window.open(url, "_blank", "noopener,noreferrer");
    window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
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
              setAppliedRows(null);
              setActivePanel("mapping");
            }}
          />
          <div className="shared-excel-title">
            <h2 id="shared-excel-title">{title ?? t("stock.bulkEdit.importExcel")}</h2>
            <span>{selectedFile?.name ?? excelImportAccept}</span>
          </div>
          <div className="shared-excel-toolbar-actions">
            <button type="button" onClick={() => fileInputRef.current?.click()}>Abrir Excel</button>
            <button type="button" onClick={() => openExcelFile(selectedFile)}>Abrir edicion</button>
            <button type="button" onClick={() => {
              setRefreshToken((value) => value + 1);
              setStatus("Excel refrescado y clasificado");
            }}>Refrescar</button>
            <button type="button" onClick={clearMapping}>Limpiar</button>
            <button type="button" onClick={onClose}>Regreso [Esc]</button>
          </div>
        </header>

        <div className="shared-excel-top-pane">
          {sheet.length === 0 ? (
            <div className="shared-excel-empty-preview">
              <h3>{summaryTitle(locale)}</h3>
              <ol>
                {summaryItems(locale).map((item) => <li key={item}>{item}</li>)}
              </ol>
            </div>
          ) : (
            <div
              className="shared-excel-preview"
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
                  {sheet.slice(0, 12).map((row, rowIndex) => (
                    <tr key={rowIndex}>
                      <th>{rowIndex + 1}</th>
                      {Array.from({ length: sheet[0]?.length ?? row.length }).map((_, cellIndex) => (
                        <td key={cellIndex}>{excelCellText(row[cellIndex])}</td>
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
                  <span>Producto empieza en fila</span>
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
                <span>{previewRows.length} filas detectadas</span>
                <span>{previewAcceptedRows.length} aceptadas</span>
                <span>{previewMissingRows.length} no existentes</span>
              </div>
              <div className="shared-excel-options">
                <label><input type="checkbox" checked={autoAddMissing} onChange={(event) => setAutoAddMissing(event.target.checked)} /> Auto añadir productos no existentes</label>
                <label><input type="checkbox" checked={generateSummaryDocument} onChange={(event) => setGenerateSummaryDocument(event.target.checked)} /> Generar documento resumen</label>
                <label><input type="checkbox" checked={showOnlyImported} onChange={(event) => setShowOnlyImported(event.target.checked)} /> Mostrar solo importados</label>
                <label><input type="checkbox" checked={skipZeroPriceUpdate} onChange={(event) => setSkipZeroPriceUpdate(event.target.checked)} /> Si precio nuevo es 0, no actualizar</label>
                <label><input type="checkbox" checked={updateSupplier} onChange={(event) => setUpdateSupplier(event.target.checked)} /> Actualizar proveedor del producto</label>
                <label>
                  <span>Precio documento desde</span>
                  <select value={priceSource} onChange={(event) => setPriceSource(event.target.value as SharedExcelImportPriceSource)}>
                    <option value="purchasePrice">Precio compra</option>
                    <option value="salePrice">Precio venta</option>
                    <option value="memberPrice">Precio socio</option>
                    <option value="wholesalePrice">Precio mayor</option>
                    <option value="offerPrice">Precio oferta</option>
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
                    {field.updateKey ? renderUpdateCheckbox(field.updateKey, fieldLabel(field, locale), updateFields, setUpdateFields) : <span />}
                  </label>
                ))}
              </div>
              <div className="shared-excel-config-actions">
                <button type="button" onClick={clearMapping}>Limpiar</button>
                <button type="button" onClick={() => void applyMapping()}>Aplicar</button>
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
                title: "Documento resumen",
                rows: existingRows,
                actions: null,
                summaryMode: true
              })}
              {activePanel === "missing" && renderResultTable({
                title: autoAddMissing ? "Productos no existentes para auto añadir" : "Productos no importables",
                rows: missingRows,
                actions: (
                  <>
                    {onAddMissingAuto && <button type="button" onClick={() => onAddMissingAuto(missingRows)}>Anadir automatico</button>}
                  </>
                ),
                reviewRow: onReviewMissing
              })}
              {activePanel === "priceChanged" && renderResultTable({
                title: "Productos con precio compra distinto",
                rows: priceChangedRows,
                actions: <button type="button" onClick={() => onImportAccepted(acceptedRowsWithQuantity(priceChangedRows))}>Actualizar</button>,
                currentPurchasePrice,
                showPurchasePriceDiff: true
              })}
              {activePanel === "accepted" && renderResultTable({
                title: "Productos aceptados",
                rows: acceptedRows,
                actions: <button type="button" onClick={importAccepted}>Importar Excel al documento</button>
              })}
              {activePanel === "errors" && renderResultTable({
                title: "Filas con errores",
                rows: errorRows,
                actions: null
              })}
            </div>
          )}
        </div>

        {status && <p className="shared-excel-status" role="status">{status}</p>}

        <nav className="shared-excel-bottom-tabs" aria-label="Apartados de importacion Excel">
          {renderPanelTab("mapping", "Configuracion archivo", activePanel, setActivePanel)}
          {renderPanelTab("summary", `Documento resumen (${existingRows.length})`, activePanel, setActivePanel)}
          {renderPanelTab("missing", `Productos no importables (${missingRows.length})`, activePanel, setActivePanel)}
          {renderPanelTab("priceChanged", `Precio compra cambiado (${priceChangedRows.length})`, activePanel, setActivePanel)}
          {renderPanelTab("accepted", `Productos importables (${acceptedRows.length})`, activePanel, setActivePanel)}
          {renderPanelTab("errors", `Errores (${errorRows.length})`, activePanel, setActivePanel)}
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

function summaryTitle(locale: LocaleCode) {
  if (locale === "zh") return "导入说明";
  if (locale === "en") return "Import summary";
  return "Resumen de importacion";
}

function summaryItems(locale: LocaleCode) {
  if (locale === "zh") {
    return [
      "点击 Abrir Excel 在此窗口选择文件。",
      "只处理 Excel 的第一个工作表，并允许 Z 之后的列。",
      "刷新分类前请输入商品开始的行号。",
      "商品通过编码或条码识别。",
      "勾选属性表示可以更新商品主档。",
      "Impuestos incluidos 使用 1 表示是，0 表示否。",
      "Usar precio 决定销售使用的价格：NORMAL=售价，MEMBER_PRICE=会员价，OFFER_PRICE=促销价，OFFER_DISCOUNT=按促销折扣计算。",
      "Prohibido descuento 使用 1 表示禁止再打折，0 表示允许正常折扣。",
      "Oferta activa 使用 1 启用促销，0 关闭促销；启用时会使用促销价或促销折扣以及日期范围。"
    ];
  }
  if (locale === "en") {
    return [
      "Click Abrir Excel to select the file from this window.",
      "Only the first Excel sheet is processed, and columns after Z are allowed.",
      "Set the first product row before refreshing the classification.",
      "Products are identified by code or barcode.",
      "Check an attribute when it is allowed to update the product master.",
      "Taxes included uses 1 for true and 0 for false.",
      "Use price defines the selling price mode: NORMAL=sale price, MEMBER_PRICE=member price, OFFER_PRICE=offer price, OFFER_DISCOUNT=calculated from offer discount.",
      "Discount prohibited uses 1 to block further discounts and 0 to allow normal discounts.",
      "Offer active uses 1 to enable the offer and 0 to disable it; enabled offers use offer price or discount plus date range."
    ];
  }
  return [
    "Pulsa Abrir Excel para seleccionar el archivo desde esta ventana.",
    "Se procesa la primera hoja del Excel y se admiten columnas posteriores a Z.",
    "Indica la fila donde empiezan los productos antes de refrescar la clasificacion.",
    "El producto se identifica por codigo o codigo de barras.",
    "Marca el check de cada atributo si puede actualizar el producto maestro.",
    "Impuestos incluidos usa 1 para verdadero y 0 para falso.",
    "Usar precio define la tarifa activa: NORMAL=precio venta, MEMBER_PRICE=precio socio, OFFER_PRICE=precio oferta y OFFER_DISCOUNT=calculo por descuento oferta.",
    "Prohibido descuento usa 1 para bloquear descuentos adicionales y 0 para permitir descuento normal.",
    "Oferta activa usa 1 para activar la oferta y 0 para desactivarla; si esta activa usa precio oferta o descuento oferta con su rango de fechas."
  ];
}

function renderUpdateCheckbox(
  field: SharedExcelImportUpdateField,
  label: string,
  updateFields: Partial<Record<SharedExcelImportUpdateField, boolean>>,
  setUpdateFields: (updater: (current: Partial<Record<SharedExcelImportUpdateField, boolean>>) => Partial<Record<SharedExcelImportUpdateField, boolean>>) => void
) {
  return (
    <input
      type="checkbox"
      checked={Boolean(updateFields[field])}
      onChange={(event) => setUpdateFields((current) => ({
        ...current,
        [field]: event.target.checked
      }))}
      aria-label={`Actualizar ${label}`}
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
    updateFields: {}
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
    return {
      mapping: parsed.mapping && typeof parsed.mapping === "object" ? parsed.mapping : {},
      quantityColumn: typeof parsed.quantityColumn === "string" ? parsed.quantityColumn : "",
      startRow: typeof parsed.startRow === "number" && Number.isFinite(parsed.startRow) ? Math.max(2, parsed.startRow) : 2,
      updateFields: parsed.updateFields && typeof parsed.updateFields === "object" ? parsed.updateFields : {}
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
  summaryMode = false
}: {
  title: string;
  rows: ExcelImportClassifiedRow[];
  actions: ReactNode;
  reviewRow?: (row: ExcelImportClassifiedRow) => void;
  currentPurchasePrice?: (product: ExcelImportProductIdentity) => string | number | null | undefined;
  showPurchasePriceDiff?: boolean;
  summaryMode?: boolean;
}) {
  const colSpan = (reviewRow ? 7 : 6) + (showPurchasePriceDiff ? 1 : 0) + (summaryMode ? 1 : 0);
  return (
    <section>
      <header>
        <h3>{title}</h3>
        <span>{rows.length}</span>
        <button type="button" onClick={() => exportRows(title, rows)}>Exportar</button>
        {actions}
      </header>
      <table>
        <thead>
          <tr>
            <th>Fila</th>
            <th>Codigo</th>
            <th>Codigo barras</th>
            <th>Nombre</th>
            {showPurchasePriceDiff ? (
              <>
                <th>Precio compra actual</th>
                <th>Precio compra nuevo</th>
              </>
            ) : (
              <th>Precio compra</th>
            )}
            <th>Precio venta</th>
            {summaryMode && <th>Estado</th>}
            {reviewRow && <th>Revisar</th>}
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
              {summaryMode && <td>{row.status === "purchasePriceChanged" ? "Precio compra cambiado" : "Existente"}</td>}
              {reviewRow && (
                <td><button type="button" onClick={() => reviewRow(row)}>Manual</button></td>
              )}
            </tr>
          ))}
          {rows.length === 0 && (
            <tr>
              <td colSpan={colSpan}>Sin filas</td>
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
