import { useEffect, useRef, useState } from "react";
import { ApiError, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import { enterNavigationIntent, focusRelativeEnterTarget } from "./keyboardNavigation";
import { ErpSelect } from "./ErpSelect";
import { SharedExcelImportDialog, type SharedExcelImportAcceptedRow } from "./SharedExcelImportDialog";
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
import type { AppKind, LocaleCode, TerminalContext } from "../types";
import type { ExcelImportClassifiedRow, ExcelImportProductDraft, ExcelImportProductIdentity } from "./excelImport";
import {
  type WarehouseDocumentLineDraft,
  type WarehouseImportProduct
} from "./warehouseDocumentImport";

export type WarehouseDocumentMode = "input" | "output";

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
  legalName?: string | null;
  razonSocial?: string | null;
  documentNumber?: string | null;
  numeroDocumento?: string | null;
};

export type WarehouseDocumentView = {
  id: string;
  number?: string | null;
  warehouseId: string;
  supplierId?: string | null;
  date: string;
  origin?: string | null;
  destination?: string | null;
  concept?: string | null;
  status: string;
  lines: Array<{
    productId: string;
    quantity: number;
  }>;
};

type WarehouseDocumentDialogProps = {
  mode: WarehouseDocumentMode;
  open: boolean;
  app?: AppKind;
  username?: string;
  accessToken?: string;
  title?: string;
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
  concept: string;
  lines: WarehouseDocumentLineDraft[];
};

const warehouseDocumentColumns = [
  { key: "code", label: "Codigo", defaultWidth: 180 },
  { key: "barcode", label: "Codigo de barra", defaultWidth: 200 },
  { key: "name", label: "Nombre", defaultWidth: 260 },
  { key: "discount", label: "Descuento", defaultWidth: 150 },
  { key: "price", label: "Precio", defaultWidth: 120 },
  { key: "quantity", labelKey: "warehouseDocument.quantity", defaultWidth: 170 },
  { key: "total", label: "Importe total", defaultWidth: 160 }
] as const;

type WarehouseDocumentColumnKey = typeof warehouseDocumentColumns[number]["key"];

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
      return error.message || messages.integrityConflict;
    }
    if (error.status === 409 && code === "STATE_CONFLICT") {
      return error.message || messages.stateConflict;
    }
  }
  return error instanceof Error ? error.message : fallback;
}

export function canConfirmWarehouseDocument(draft: Pick<WarehouseDocumentDraft, "warehouseId" | "partnerId" | "partnerText" | "lines">) {
  return Boolean(draft.warehouseId)
    && draft.lines.length > 0
    && draft.lines.every((line) => line.valid);
}

export function buildWarehouseDocumentCommand(mode: WarehouseDocumentMode, draft: WarehouseDocumentDraft) {
  const lines = draft.lines
    .filter((line) => line.valid)
    .map((line) => ({ productId: line.productId, quantity: line.quantity }));
  if (mode === "input") {
    return {
      warehouseId: draft.warehouseId,
      date: draft.date,
      supplierId: draft.partnerId || undefined,
      origin: draft.partnerText,
      concept: draft.concept,
      lines
    };
  }
  return {
    warehouseId: draft.warehouseId,
    date: draft.date,
    destination: draft.partnerText,
    concept: draft.concept,
    lines
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
  title: titleOverride,
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
  const [documentId, setDocumentId] = useState("");
  const [documentStatus, setDocumentStatus] = useState("BORRADOR");
  const [warehouseId, setWarehouseId] = useState("");
  const [partnerId, setPartnerId] = useState("");
  const [partnerText, setPartnerText] = useState("");
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [documentDiscountPercent, setDocumentDiscountPercent] = useState("0");
  const [concept, setConcept] = useState("");
  const [lines, setLines] = useState<WarehouseDocumentLineDraft[]>([]);
  const [manualProductId, setManualProductId] = useState("");
  const [manualProductCode, setManualProductCode] = useState("");
  const [manualDiscountPercent, setManualDiscountPercent] = useState("0");
  const [manualQuantity, setManualQuantity] = useState("1");
  const [status, setStatus] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [excelImportOpen, setExcelImportOpen] = useState(false);
  const [excelCreatedProducts, setExcelCreatedProducts] = useState<WarehouseImportProduct[]>([]);
  const [manualMissingRows, setManualMissingRows] = useState<ExcelImportClassifiedRow[]>([]);
  const [manualMissingIndex, setManualMissingIndex] = useState(0);
  const [fileMenuOpen, setFileMenuOpen] = useState(false);
  const [priceMenuOpen, setPriceMenuOpen] = useState(false);
  const [documentPriceMode, setDocumentPriceMode] = useState<"sale" | "minor">("sale");
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
  const fileMenuRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    const initialWarehouse = document?.warehouseId
      || defaultWarehouseId
      || warehouses.find((warehouse) => warehouse.active !== false)?.id
      || "";
    setDocumentId(document?.id ?? "");
    setDocumentStatus(document?.status ?? "BORRADOR");
    setWarehouseId(initialWarehouse);
    setPartnerId(document?.supplierId ?? "");
    setPartnerText(mode === "input" ? document?.origin ?? "" : document?.destination ?? "");
    setDate(document?.date ?? new Date().toISOString().slice(0, 10));
    setDocumentDiscountPercent("0");
    setConcept(document?.concept ?? "");
    setLines((document?.lines ?? []).map((line, index) => (
      createManualWarehouseDocumentLine(line.productId, Number(line.quantity), products, index + 1)
    )));
    setManualProductId("");
    setManualProductCode("");
    setManualDiscountPercent("0");
    setManualQuantity("1");
    setStatus("");
    setExcelImportOpen(false);
    setExcelCreatedProducts([]);
    setManualMissingRows([]);
    setManualMissingIndex(0);
    setFileMenuOpen(false);
    setPriceMenuOpen(false);
  }, [defaultWarehouseId, document, mode, open]);

  useOutsidePointerDown(fileMenuOpen, fileMenuRef, () => {
    setFileMenuOpen(false);
    setPriceMenuOpen(false);
  });

  if (!open) {
    return null;
  }

  const title = titleOverride ?? t(mode === "input" ? "stock.nav.inputWarehouse" : "stock.nav.outputWarehouse");
  const partnerLabel = t(mode === "input" ? "warehouseDocument.supplier" : "warehouseDocument.customer");
  const partnerOptions = mode === "input" ? suppliers : customers;
  const draft = { warehouseId, partnerId, partnerText, date, concept, lines };
  const canSaveDraft = canConfirmWarehouseDocument(draft) && !submitting && Boolean(token);
  const canSubmitConfirmation = canConfirm && canSaveDraft;
  const readOnly = documentStatus !== "BORRADOR";
  const isEditing = Boolean(documentId);
  const importProducts = [...products, ...excelCreatedProducts];
  const manualProduct = importProducts.find((product) => product.id === manualProductId);
  const manualProductPrice = documentProductPrice(manualProduct, documentPriceMode);
  const documentTypeLabel = title;
  const totalUnits = lines.reduce((total, line) => total + (Number.isFinite(line.quantity) ? line.quantity : 0), 0);
  const selectedPartner = partnerId ? partnerOptions.find((option) => option.id === partnerId) : null;
  const documentSubtotal = lines.reduce((total, line) => {
    const product = importProducts.find((candidate) => candidate.id === line.productId);
    return total + documentLineTotal(documentProductPrice(product, documentPriceMode), line.quantity, line.discountPercent ?? "0");
  }, 0);
  const documentTotal = documentTotalAfterDiscount(documentSubtotal, documentDiscountPercent);

  function importAcceptedExcelRows(rows: SharedExcelImportAcceptedRow[]) {
    const nextLines = rows.map((row, index) => (
      createManualWarehouseDocumentLine(row.product?.id ?? "", row.quantity, importProducts, index + 1)
    ));
    setLines(nextLines);
    setStatus(t("warehouseDocument.imported"));
    setExcelImportOpen(false);
  }

  async function addMissingProductsAuto(rows: ExcelImportClassifiedRow[]): Promise<ExcelImportProductIdentity[]> {
    if (!token) {
      setStatus(t("product.create.saveError"));
      return [];
    }
    setStatus(`Creando ${rows.length} productos...`);
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
    setStatus(`${created.length} productos creados`);
    return created;
  }

  function addMissingProductsManual(rows: ExcelImportClassifiedRow[]) {
    setManualMissingRows(rows);
    setManualMissingIndex(0);
    setStatus(`${rows.length} productos pendientes de revisar`);
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
      setStatus("Productos no existentes revisados");
      return;
    }
    setManualMissingIndex(nextIndex);
  }

  function addManualLine() {
    const quantity = Number(manualQuantity.replace(",", "."));
    const next = createManualWarehouseDocumentLine(manualProductId, quantity, importProducts, lines.length + 1);
    next.discountPercent = manualDiscountPercent;
    if (!next.valid) {
      setStatus(t(next.errorKey));
      return;
    }
    setLines((current) => [...current, next]);
    setManualProductId("");
    setManualProductCode("");
    setManualDiscountPercent("0");
    setManualQuantity("1");
    setStatus(t("warehouseDocument.lineAdded"));
  }

  function addManualLineFromTable() {
    const quantity = Number(manualQuantity.replace(",", "."));
    const next = manualProductId
      ? createManualWarehouseDocumentLine(manualProductId, quantity, importProducts, lines.length + 1)
      : createManualWarehouseDocumentLineByCode(manualProductCode, quantity, importProducts, lines.length + 1);
    next.discountPercent = manualDiscountPercent;
    if (!next.valid) {
      setStatus(t(next.errorKey));
      return;
    }
    setLines((current) => [...current, next]);
    setManualProductId("");
    setManualProductCode("");
    setManualDiscountPercent("0");
    setManualQuantity("1");
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
    return product;
  }

  function openPartnerList() {
    setFileMenuOpen(false);
    const trigger = dialogRef.current?.querySelector<HTMLElement>("[data-warehouse-partner-trigger]");
    trigger?.focus();
    setStatus(mode === "input" ? "Listado de proveedores" : "Listado de clientes");
  }

  function clearAllLines() {
    setLines([]);
    setFileMenuOpen(false);
    setStatus("Articulos eliminados");
  }

  function clearAllDiscounts() {
    setFileMenuOpen(false);
    setStatus("Este documento no tiene descuentos aplicados");
  }

  function printDocument() {
    setFileMenuOpen(false);
    window.print();
  }

  function previewDocument() {
    setFileMenuOpen(false);
    setStatus("Vista previa de impresion");
    window.print();
  }

  function exportDocumentExcel() {
    setFileMenuOpen(false);
    const csv = [
      ["Fila", "Codigo", "Articulo", "Cantidad", "Estado"].join(";"),
      ...lines.map((line, index) => [
        index + 1,
        csvCell(importProducts.find((product) => product.id === line.productId)?.code ?? line.importedProduct),
        csvCell(line.productLabel),
        line.quantity,
        line.valid ? "Correcto" : t(line.errorKey)
      ].join(";"))
    ].join("\n");
    const link = globalThis.document.createElement("a");
    link.href = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    link.download = `${documentTypeLabel.toLowerCase().replace(/\s+/g, "-")}.csv`;
    link.click();
    URL.revokeObjectURL(link.href);
    setStatus("Excel exportado");
  }

  function updateLine(index: number, productId: string, quantity: number) {
    setLines((current) => current.map((line, lineIndex) => (
      lineIndex === index
        ? {
          ...createManualWarehouseDocumentLine(productId, quantity, importProducts, line.rowNumber),
          discountPercent: line.discountPercent ?? "0"
        }
        : line
    )));
  }

  function updateLineCode(index: number, code: string) {
    setLines((current) => current.map((line, lineIndex) => (
      lineIndex === index
        ? {
          ...createManualWarehouseDocumentLineByCode(code, line.quantity, importProducts, line.rowNumber),
          discountPercent: line.discountPercent ?? "0"
        }
        : line
    )));
  }

  function updateLineDiscount(index: number, discountPercent: string) {
    setLines((current) => current.map((line, lineIndex) => (
      lineIndex === index ? { ...line, discountPercent } : line
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
    const price = documentProductPrice(lineProduct, documentPriceMode);
    const discountPercent = line.discountPercent ?? "0";

    switch (columnKey) {
      case "code":
        return readOnly ? <span className="product-name-text">{line.productLabel}</span> : (
          <input
            ref={(element) => { rowCodeRefs.current[index] = element; }}
            value={lineProduct?.code ?? line.importedProduct}
            onChange={(event) => updateLineCode(index, event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.stopPropagation();
                event.preventDefault();
                rowDiscountRefs.current[index]?.focus();
                rowDiscountRefs.current[index]?.select();
              }
            }}
          />
        );
      case "barcode":
        return lineProduct?.barcode ?? "-";
      case "name":
        return <span className="product-name-text">{lineProduct?.name ?? line.productLabel}</span>;
      case "discount":
        return readOnly ? formatDocumentDiscount(discountPercent) : (
          <input
            ref={(element) => { rowDiscountRefs.current[index] = element; }}
            type="number"
            min="0"
            max="100"
            step="0.01"
            value={discountPercent}
            onChange={(event) => updateLineDiscount(index, event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.stopPropagation();
                event.preventDefault();
                rowQuantityRefs.current[index]?.focus();
                rowQuantityRefs.current[index]?.select();
              }
            }}
          />
        );
      case "price":
        return formatDocumentAmount(price);
      case "quantity":
        return readOnly ? line.quantity : (
          <input
            ref={(element) => { rowQuantityRefs.current[index] = element; }}
            type="number"
            min="0"
            step="1"
            value={line.quantity}
            onChange={(event) => updateLine(index, line.productId, Number(event.target.value))}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.stopPropagation();
                event.preventDefault();
                confirmLineQuantity(index, Number(event.currentTarget.value));
              }
            }}
          />
        );
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
        return formatDocumentAmount(manualProductPrice);
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
    if (!canConfirm || submitting || readOnly) {
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
        if (event.key === "Escape") {
          event.preventDefault();
          onClose();
        } else if (event.ctrlKey && event.key.toLocaleLowerCase() === "s") {
          event.preventDefault();
          void saveDraft();
        } else if (event.key === "F10" && canConfirm) {
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
    >
      <section className="warehouse-document-dialog warehouse-document-dialog-v2">
        <header className="warehouse-document-topbar">
          <div className="warehouse-document-file-menu" ref={fileMenuRef}>
            <button type="button" onClick={() => setFileMenuOpen((current) => !current)}>Archivo</button>
            {fileMenuOpen && (
              <div className="warehouse-document-menu" role="menu">
                <button type="button" onClick={openPartnerList}>{partnerLabel}</button>
                <button type="button" disabled={!canSaveDraft} onClick={() => { setFileMenuOpen(false); void saveDraft(); }}>Guardar</button>
                <button type="button" disabled={!canSubmitConfirmation} onClick={() => { setFileMenuOpen(false); void confirmDocument(); }}>Confirmar</button>
                <button type="button" onClick={previewDocument}>Vista previa Ctrl+P</button>
                <button type="button" onClick={printDocument}>Imprimir</button>
                <button type="button" disabled={readOnly} onClick={clearAllLines}>Eliminar todos los articulos</button>
                <button type="button" disabled={readOnly} onClick={clearAllDiscounts}>Eliminar todos los descuentos</button>
                <button type="button" disabled={readOnly} onClick={() => { setFileMenuOpen(false); setExcelImportOpen(true); }}>Importar Excel</button>
                <button type="button" onClick={exportDocumentExcel}>Exportar Excel</button>
                <div className="warehouse-document-submenu">
                  <button type="button" aria-expanded={priceMenuOpen} onClick={() => setPriceMenuOpen((current) => !current)}>Usar precio</button>
                  {priceMenuOpen && (
                    <div className="warehouse-document-submenu-panel" role="menu">
                      <button type="button" onClick={() => { setDocumentPriceMode("sale"); setPriceMenuOpen(false); setFileMenuOpen(false); }}>Precio venta</button>
                      <button type="button" onClick={() => { setDocumentPriceMode("minor"); setPriceMenuOpen(false); setFileMenuOpen(false); }}>Precio mayor</button>
                    </div>
                  )}
                </div>
                <button type="button" onClick={onClose}>Salir</button>
              </div>
            )}
          </div>
          <button type="button" onClick={printDocument}>Imprimir</button>
          <button type="button" disabled={!canSaveDraft} onClick={() => void saveDraft()}>Guardar F9</button>
          <button type="button" disabled={!canSubmitConfirmation} onClick={() => void confirmDocument()}>Confirmar</button>
          <button type="button" onClick={onClose}>Salir Esc</button>
        </header>

        <div className="warehouse-document-workspace">
          <aside className="warehouse-document-sidebar">
            <div className="warehouse-document-total">
              <span>{documentTypeLabel}{document?.number ? ` / ${document.number}` : ""}</span>
              <strong>{formatDocumentAmount(documentTotal)}</strong>
              <em>{totalUnits.toLocaleString(locale === "zh" ? "zh-CN" : "es-ES")} {t("warehouseDocument.quantity")} total</em>
              <small>Subtotal: {formatDocumentAmount(documentSubtotal)}</small>
              <small>Descuento documento: {formatDocumentDiscount(documentDiscountPercent)}</small>
            </div>

            <div className="warehouse-document-field">
              <span>{t("stock.column.warehouse")}</span>
              <ErpSelect
                aria-label={t("stock.column.warehouse")}
                value={warehouseId}
                disabled={readOnly || isEditing}
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

            <div className="warehouse-document-partner-panel">
              <span>{mode === "input" ? "Proveedor/Origen" : "Cliente/Destino"}</span>
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
              <button type="button" data-warehouse-partner-trigger onClick={openPartnerList}>Listado {partnerLabel.toLowerCase()}</button>
              <p>{selectedPartner ? partnerName(selectedPartner) : partnerText || "Sin tercero asignado para documento de almacen"}</p>
            </div>

            <label className="warehouse-document-discount">
              <span>Descuento total documento %</span>
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
              <span>Comentarios</span>
              <textarea value={concept} disabled={readOnly} onChange={(event) => setConcept(event.target.value)} />
            </label>

            <div className="warehouse-document-meta">
              <label>
                <span>{t("salesReport.filter.date")}</span>
                <input type="date" value={date} disabled={readOnly || isEditing} onChange={(event) => setDate(event.target.value)} />
              </label>
              <div className="warehouse-document-state">
                <span>{t("salesReport.column.status")}</span>
                <strong>{documentStatus}</strong>
              </div>
              <div className="warehouse-document-state">
                <span>Usar precio</span>
                <strong>{documentPriceMode === "sale" ? "Precio venta" : "Precio menor"}</strong>
              </div>
            </div>
            {status && <p className="warehouse-document-status" aria-live="polite">{status}</p>}
          </aside>

          <main className="warehouse-document-lines-panel">
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
                      const label = definition && "labelKey" in definition ? t(definition.labelKey) : definition?.label ?? column.key;
                      return (
                        <TableLayoutHeaderCell
                          column={column}
                          key={column.key}
                          resizeLabel={`${t("stock.columns.resize")} ${label}`}
                          onReorder={tableLayout.reorderColumns}
                          onMove={tableLayout.moveColumn}
                          onResize={tableLayout.resizeColumn}
                        >
                          {label}
                        </TableLayoutHeaderCell>
                      );
                    })}
                  </tr>
                </thead>
                <tbody>
                  {lines.map((line, index) => (
                    <tr className={line.valid ? "" : "warehouse-document-line-error"} key={`${line.rowNumber}-${index}`}>
                      {visibleColumns.map((column) => (
                        <td key={column.key}>{renderDocumentLineCell(column.key, line, index)}</td>
                      ))}
                    </tr>
                  ))}
                  {!readOnly && (
                    <tr className="warehouse-document-new-row">
                      {visibleColumns.map((column) => (
                        <td key={column.key}>{renderNewDocumentLineCell(column.key)}</td>
                      ))}
                    </tr>
                  )}
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
        onClose={() => setExcelImportOpen(false)}
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

function documentProductPrice(product: WarehouseImportProduct | undefined, mode: "sale" | "minor") {
  if (!product) return 0;
  const value = mode === "minor"
    ? product.wholesalePrice ?? product.salePrice ?? product.purchasePrice
    : product.salePrice ?? product.wholesalePrice ?? product.purchasePrice;
  return decimalDocumentNumber(value);
}

function decimalDocumentNumber(value: string | number | null | undefined) {
  const number = Number(String(value ?? "0").replace(",", "."));
  return Number.isFinite(number) ? number : 0;
}

function documentDiscountPercent(value: string | number | null | undefined) {
  const number = decimalDocumentNumber(value);
  if (number < 0) return 0;
  if (number > 100) return 100;
  return number;
}

export function documentLineTotal(price: number, quantity: number, discountPercent: string | number | null | undefined) {
  const discount = documentDiscountPercent(discountPercent);
  const total = price * quantity * (1 - discount / 100);
  return Number.isFinite(total) ? total : 0;
}

export function documentTotalAfterDiscount(subtotal: number, discountPercent: string | number | null | undefined) {
  const discount = documentDiscountPercent(discountPercent);
  const total = subtotal * (1 - discount / 100);
  return Number.isFinite(total) ? total : 0;
}

function formatDocumentDiscount(value: string | number | null | undefined) {
  return `${formatDocumentAmount(documentDiscountPercent(value))}%`;
}

function formatDocumentAmount(value: number) {
  return new Intl.NumberFormat("es-ES", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(Number.isFinite(value) ? value : 0);
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
  if (["MEMBER_PRICE", "MEMBER", "SOCIO", "PRECIO_SOCIO"].includes(normalized)) return "MEMBER_PRICE";
  if (["OFFER_PRICE", "OFERTA", "PRECIO_OFERTA"].includes(normalized)) return "OFFER_PRICE";
  if (["OFFER_DISCOUNT", "DESCUENTO_OFERTA"].includes(normalized)) return "OFFER_DISCOUNT";
  return "NORMAL";
}

function discountTypeFromExcel(value: string): ProductCreateFormState["discountType"] {
  const normalized = normalizeExcelOption(value);
  if (["1", "TRUE", "SI", "YES", "NONE", "NO_APLICAR", "PROHIBIDO"].includes(normalized)) return "NONE";
  if (["MEMBER_PRICE", "SOCIO"].includes(normalized)) return "MEMBER_PRICE";
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
