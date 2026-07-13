import { useEffect, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import { enterNavigationIntent, focusRelativeEnterTarget } from "./keyboardNavigation";
import { ErpSelect } from "./ErpSelect";
import type { LocaleCode } from "../types";
import {
  buildWarehouseDocumentLines,
  readWarehouseDocumentFile,
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
  canConfirm?: boolean;
  locale?: LocaleCode;
  token?: string;
  products: WarehouseImportProduct[];
  warehouses: WarehouseOption[];
  customers: WarehouseCustomerOption[];
  suppliers: WarehouseSupplierOption[];
  document?: WarehouseDocumentView | null;
  defaultWarehouseId?: string;
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

export function warehouseDocumentPath(mode: WarehouseDocumentMode) {
  return mode === "input" ? "/warehouse-inputs" : "/warehouse-outputs";
}

export function canConfirmWarehouseDocument(draft: Pick<WarehouseDocumentDraft, "warehouseId" | "partnerId" | "partnerText" | "lines">) {
  return Boolean(draft.warehouseId)
    && Boolean(draft.partnerId || draft.partnerText.trim())
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
  const valid = Boolean(product) && Number.isFinite(quantity) && quantity > 0;
  return {
    rowNumber,
    productId: product?.id ?? "",
    productLabel: product ? productLabel(product) : "",
    importedProduct: product?.code ?? product?.barcode ?? product?.name ?? "",
    quantity,
    valid,
    errorKey: !product ? "warehouseDocument.error.productNotFound" : quantity <= 0 ? "warehouseDocument.error.invalidQuantity" : ""
  };
}

export function WarehouseDocumentDialog({
  mode,
  open,
  canConfirm = false,
  locale = "es",
  token,
  products,
  warehouses,
  customers,
  suppliers,
  document,
  defaultWarehouseId,
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
  const [concept, setConcept] = useState("");
  const [lines, setLines] = useState<WarehouseDocumentLineDraft[]>([]);
  const [manualProductId, setManualProductId] = useState("");
  const [manualQuantity, setManualQuantity] = useState("1");
  const [status, setStatus] = useState("");
  const [submitting, setSubmitting] = useState(false);

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
    setConcept(document?.concept ?? "");
    setLines((document?.lines ?? []).map((line, index) => (
      createManualWarehouseDocumentLine(line.productId, Number(line.quantity), products, index + 1)
    )));
    setManualProductId("");
    setManualQuantity("1");
    setStatus("");
  }, [defaultWarehouseId, document, mode, open]);

  if (!open) {
    return null;
  }

  const title = t(mode === "input" ? "stock.nav.inputWarehouse" : "stock.nav.outputWarehouse");
  const partnerLabel = t(mode === "input" ? "warehouseDocument.supplier" : "warehouseDocument.customer");
  const partnerOptions = mode === "input" ? suppliers : customers;
  const draft = { warehouseId, partnerId, partnerText, date, concept, lines };
  const canSaveDraft = canConfirmWarehouseDocument(draft) && !submitting && Boolean(token);
  const canSubmitConfirmation = canConfirm && canSaveDraft;
  const readOnly = documentStatus !== "BORRADOR";
  const isEditing = Boolean(documentId);

  async function importFile(file: File | undefined) {
    if (!file || readOnly) {
      return;
    }
    setStatus(t("warehouseDocument.importing"));
    try {
      setLines(await readWarehouseDocumentFile(file, products));
      setStatus(t("warehouseDocument.imported"));
    } catch (error) {
      setLines(buildWarehouseDocumentLines([], products));
      setStatus(error instanceof Error ? error.message : t("warehouseDocument.importError"));
    }
  }

  function addManualLine() {
    const quantity = Number(manualQuantity.replace(",", "."));
    const next = createManualWarehouseDocumentLine(manualProductId, quantity, products, lines.length + 1);
    if (!next.valid) {
      setStatus(t(next.errorKey));
      return;
    }
    setLines((current) => [...current, next]);
    setManualProductId("");
    setManualQuantity("1");
    setStatus(t("warehouseDocument.lineAdded"));
  }

  function updateLine(index: number, productId: string, quantity: number) {
    setLines((current) => current.map((line, lineIndex) => (
      lineIndex === index
        ? createManualWarehouseDocumentLine(productId, quantity, products, line.rowNumber)
        : line
    )));
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
      setStatus(error instanceof Error ? error.message : t("warehouseDocument.saveError"));
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
      setStatus(error instanceof Error ? error.message : t("warehouseDocument.confirmError"));
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
            addManualLine();
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
      <section className="warehouse-document-dialog">
        <header className="filter-header">
          <div>
            <h2 id="warehouse-document-title">
              {t(readOnly ? "warehouseDocument.view" : isEditing ? "warehouseDocument.edit" : "warehouseDocument.create")}
            </h2>
            <span>{title}{document?.number ? ` / ${document.number}` : ""}</span>
          </div>
          <button type="button" onClick={onClose}>{t("common.close")}</button>
        </header>

        <div className="warehouse-document-grid">
          <div className="warehouse-document-field">
            <span>{partnerLabel}</span>
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
          <label>
            <span>{t("warehouseDocument.partnerFallback").replace("{partner}", partnerLabel)}</span>
            <input value={partnerText} disabled={readOnly} onChange={(event) => setPartnerText(event.target.value)} />
          </label>
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
          <label>
            <span>{t("salesReport.filter.date")}</span>
            <input type="date" value={date} disabled={readOnly || isEditing} onChange={(event) => setDate(event.target.value)} />
          </label>
          <label className="warehouse-document-concept">
            <span>{t("warehouseDocument.concept")}</span>
            <input value={concept} disabled={readOnly} onChange={(event) => setConcept(event.target.value)} />
          </label>
          <label className="warehouse-document-file">
            <span>{t("warehouseDocument.importExcel")}</span>
            <input
              type="file"
              accept=".xlsx,.xls,.csv"
              disabled={readOnly}
              onChange={(event) => void importFile(event.currentTarget.files?.[0])}
            />
          </label>
          <div className="warehouse-document-state">
            <span>{t("salesReport.column.status")}</span>
            <strong>{documentStatus}</strong>
          </div>
        </div>

        {!readOnly && (
          <div className="warehouse-document-line-entry">
            <div className="warehouse-document-field">
              <span>{t("warehouseDocument.product")}</span>
              <ErpSelect
                id="warehouse-manual-product"
                aria-label={t("warehouseDocument.product")}
                value={manualProductId}
                options={[
                  { value: "", label: t("common.select") },
                  ...products.map((product) => ({ value: product.id, label: productLabel(product) }))
                ]}
                onChange={setManualProductId}
                onCommit={() => moveFromActiveControl("next")}
                onNavigatePrevious={() => moveFromActiveControl("previous")}
              />
            </div>
            <label>
              <span>{t("warehouseDocument.quantity")}</span>
              <input data-warehouse-add-line type="number" min="1" step="1" value={manualQuantity} onChange={(event) => setManualQuantity(event.target.value)} />
            </label>
            <button type="button" onClick={addManualLine}>{t("warehouseDocument.addLine")}</button>
          </div>
        )}

        <div className="warehouse-document-table-scroll">
          <table className="report-table warehouse-document-table">
            <thead>
              <tr>
                <th>{t("warehouseDocument.row")}</th>
                <th>{t("warehouseDocument.product")}</th>
                <th>{t("warehouseDocument.quantity")}</th>
                <th>{t("salesReport.column.status")}</th>
                {!readOnly && <th>{t("common.actions")}</th>}
              </tr>
            </thead>
            <tbody>
              {lines.map((line, index) => (
                <tr className={line.valid ? "" : "warehouse-document-line-error"} key={`${line.rowNumber}-${index}`}>
                  <td>{index + 1}</td>
                  <td>
                    {readOnly ? line.productLabel : (
                      <ErpSelect
                        aria-label={`${t("warehouseDocument.product")} ${index + 1}`}
                        value={line.productId}
                        options={[
                          { value: "", label: t("common.select") },
                          ...products.map((product) => ({ value: product.id, label: productLabel(product) }))
                        ]}
                        onChange={(next) => updateLine(index, next, line.quantity)}
                        onCommit={() => moveFromActiveControl("next")}
                        onNavigatePrevious={() => moveFromActiveControl("previous")}
                      />
                    )}
                  </td>
                  <td>
                    {readOnly ? line.quantity : (
                      <input
                        type="number"
                        min="1"
                        step="1"
                        value={line.quantity}
                        onChange={(event) => updateLine(index, line.productId, Number(event.target.value))}
                      />
                    )}
                  </td>
                  <td>{line.valid ? t("stock.status.ok") : t(line.errorKey)}</td>
                  {!readOnly && (
                    <td>
                      <button type="button" onClick={() => setLines((current) => current.filter((_, lineIndex) => lineIndex !== index))}>
                        {t("common.delete")}
                      </button>
                    </td>
                  )}
                </tr>
              ))}
              {lines.length === 0 && (
                <tr>
                  <td colSpan={readOnly ? 4 : 5}>{t("warehouseDocument.emptyLines")}</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        {status && <p className="warehouse-document-status" aria-live="polite">{status}</p>}

        <footer className="filter-actions warehouse-document-actions">
          <button type="button" onClick={onClose}>{t(readOnly ? "common.close" : "common.cancel")}</button>
          {!readOnly && (
            <>
              <button type="button" disabled={!canSaveDraft} onClick={() => void saveDraft()}>
                {submitting ? t("warehouseDocument.saving") : t("warehouseDocument.saveDraft")}
              </button>
              {canConfirm && (
                <button type="button" disabled={!canSubmitConfirmation} onClick={() => void confirmDocument()}>
                  {submitting ? t("warehouseDocument.confirming") : t("warehouseDocument.confirm")}
                </button>
              )}
            </>
          )}
        </footer>
      </section>
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
