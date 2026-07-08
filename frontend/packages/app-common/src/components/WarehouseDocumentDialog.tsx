import { useState } from "react";
import { apiRequest } from "../api/client";
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

type WarehouseDocumentDialogProps = {
  mode: WarehouseDocumentMode;
  open: boolean;
  token?: string;
  products: WarehouseImportProduct[];
  warehouses: WarehouseOption[];
  customers: WarehouseCustomerOption[];
  suppliers: WarehouseSupplierOption[];
  onClose: () => void;
  onConfirmed: () => void;
};

type WarehouseDocumentDraft = {
  warehouseId: string;
  partnerId: string;
  partnerText: string;
  date: string;
  concept: string;
  lines: WarehouseDocumentLineDraft[];
};

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

export function WarehouseDocumentDialog({
  mode,
  open,
  token,
  products,
  warehouses,
  customers,
  suppliers,
  onClose,
  onConfirmed
}: WarehouseDocumentDialogProps) {
  const [warehouseId, setWarehouseId] = useState(warehouses[0]?.id ?? "");
  const [partnerId, setPartnerId] = useState("");
  const [partnerText, setPartnerText] = useState("");
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [concept, setConcept] = useState("");
  const [lines, setLines] = useState<WarehouseDocumentLineDraft[]>([]);
  const [status, setStatus] = useState("");
  const [submitting, setSubmitting] = useState(false);

  if (!open) {
    return null;
  }

  const title = mode === "input" ? "Entrada almacen" : "Salida almacen";
  const partnerLabel = mode === "input" ? "Proveedor" : "Cliente";
  const partnerOptions = mode === "input" ? suppliers : customers;
  const draft = { warehouseId, partnerId, partnerText, date, concept, lines };
  const canConfirm = canConfirmWarehouseDocument(draft) && !submitting;

  async function importFile(file: File | undefined) {
    if (!file) {
      return;
    }
    setStatus("Importando Excel...");
    try {
      setLines(await readWarehouseDocumentFile(file, products));
      setStatus("Excel importado");
    } catch (error) {
      setLines(buildWarehouseDocumentLines([], products));
      setStatus(error instanceof Error ? error.message : "No se pudo importar Excel");
    }
  }

  async function confirmDocument() {
    if (!canConfirm || !token) {
      return;
    }
    setSubmitting(true);
    setStatus("Confirmando documento...");
    try {
      const path = mode === "input" ? "/warehouse-inputs" : "/warehouse-outputs";
      const created = await apiRequest<{ id: string }>(path, {
        token,
        body: buildWarehouseDocumentCommand(mode, draft)
      });
      await apiRequest(`${path}/${created.id}/confirm`, { token, method: "POST" });
      setStatus("Documento confirmado");
      onConfirmed();
    } catch (error) {
      setStatus(error instanceof Error ? error.message : "No se pudo confirmar");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="warehouse-document-overlay" role="dialog" aria-modal="true" aria-labelledby="warehouse-document-title">
      <section className="warehouse-document-dialog">
        <header className="filter-header">
          <div>
            <h2 id="warehouse-document-title">Crear documento</h2>
            <span>{title}</span>
          </div>
          <button type="button" onClick={onClose}>Cerrar</button>
        </header>

        <div className="warehouse-document-grid">
          <label>
            <span>{partnerLabel}</span>
            <select
              value={partnerId}
              onChange={(event) => {
                const selected = partnerOptions.find((option) => option.id === event.target.value);
                setPartnerId(event.target.value);
                setPartnerText(selected ? partnerName(selected) : "");
              }}
            >
              <option value="">Seleccionar</option>
              {partnerOptions.map((option) => (
                <option value={option.id} key={option.id}>{partnerName(option)}</option>
              ))}
            </select>
          </label>
          <label>
            <span>{`${partnerLabel} manual`}</span>
            <input value={partnerText} onChange={(event) => setPartnerText(event.target.value)} />
          </label>
          <label>
            <span>Almacen</span>
            <select value={warehouseId} onChange={(event) => setWarehouseId(event.target.value)}>
              <option value="">Seleccionar</option>
              {warehouses.map((warehouse) => (
                <option value={warehouse.id} key={warehouse.id}>{warehouse.name ?? warehouse.nombre ?? warehouse.id}</option>
              ))}
            </select>
          </label>
          <label>
            <span>Fecha</span>
            <input type="date" value={date} onChange={(event) => setDate(event.target.value)} />
          </label>
          <label className="warehouse-document-concept">
            <span>Concepto</span>
            <input value={concept} onChange={(event) => setConcept(event.target.value)} />
          </label>
          <label className="warehouse-document-file">
            <span>Importar Excel</span>
            <input
              type="file"
              accept=".xlsx,.xls,.csv"
              onChange={(event) => void importFile(event.currentTarget.files?.[0])}
            />
          </label>
        </div>

        <div className="warehouse-document-table-scroll">
          <table className="report-table warehouse-document-table">
            <thead>
              <tr>
                <th>Fila</th>
                <th>Articulo</th>
                <th>Cantidad</th>
                <th>Estado</th>
              </tr>
            </thead>
            <tbody>
              {lines.map((line) => (
                <tr className={line.valid ? "" : "warehouse-document-line-error"} key={`${line.rowNumber}-${line.importedProduct}`}>
                  <td>{line.rowNumber}</td>
                  <td>{line.productLabel || line.importedProduct}</td>
                  <td>{line.quantity}</td>
                  <td>{line.valid ? "Correcto" : line.errorKey}</td>
                </tr>
              ))}
              {lines.length === 0 && (
                <tr>
                  <td colSpan={4}>Importa un Excel para cargar lineas</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        {status && <p className="warehouse-document-status" aria-live="polite">{status}</p>}

        <footer className="filter-actions warehouse-document-actions">
          <button type="button" onClick={onClose}>Cancelar</button>
          <button type="button" disabled={!canConfirm} onClick={() => void confirmDocument()}>
            {submitting ? "Confirmando..." : "Confirmar"}
          </button>
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
