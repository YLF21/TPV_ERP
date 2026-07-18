import { useEffect, useMemo, useState } from "react";
import { apiRequest } from "../api/client";
import { ErpSelect } from "./ErpSelect";

export type PurchaseDocumentMode = "invoice" | "deliveryNote";

export type PurchaseDocumentProduct = {
  id: string;
  code?: string | null;
  barcode?: string | null;
  name?: string | null;
  purchasePrice?: string | number | null;
  taxId?: string | null;
  taxesIncluded?: boolean;
};

export type PurchaseDocumentTax = {
  id: string;
  percentage?: string | number | null;
};

export type PurchaseDocumentLineDraft = {
  productId: string;
  code: string;
  name: string;
  quantity: number;
  unitPrice: number;
  discount: number;
  taxRegime: "IVA" | "IGIC";
  taxPercentage: number;
  taxesIncluded: boolean;
};

type PurchaseDocumentDialogProps = {
  open: boolean;
  mode: PurchaseDocumentMode;
  token?: string;
  products: PurchaseDocumentProduct[];
  warehouses: Array<{ id: string; name?: string | null; nombre?: string | null; active?: boolean }>;
  suppliers: Array<{ id: string; legalName?: string | null; razonSocial?: string | null; active?: boolean }>;
  taxes: PurchaseDocumentTax[];
  t: (key: string) => string;
  onClose: () => void;
  onConfirmed: () => void;
};

export function purchaseDocumentPath(mode: PurchaseDocumentMode) {
  return mode === "invoice" ? "/invoices" : "/delivery-notes";
}

export function confirmedPurchaseDocumentPath(mode: PurchaseDocumentMode) {
  return `${purchaseDocumentPath(mode)}/confirmed`;
}

export function buildPurchaseDocumentRequest(input: {
  mode: PurchaseDocumentMode;
  warehouseId: string;
  supplierId: string;
  date: string;
  externalNumber: string;
  directStock: boolean;
  lines: PurchaseDocumentLineDraft[];
}) {
  return {
    almacenId: input.warehouseId,
    tipo: input.mode === "invoice" ? "FACTURA_COMPRA" : "ALBARAN_COMPRA",
    fecha: input.date,
    proveedorId: input.supplierId,
    numeroExterno: input.externalNumber.trim() || undefined,
    descuentoGlobal: 0,
    directo: input.mode === "invoice" && input.directStock,
    lineas: input.lines.map((line) => ({
      productoId: line.productId,
      cantidad: line.quantity,
      codigo: line.code,
      nombre: line.name,
      tarifa: "COMPRA",
      precioUnitario: line.unitPrice,
      descuento: line.discount,
      impuestosIncluidos: line.taxesIncluded,
      regimenImpuesto: line.taxRegime,
      porcentajeImpuesto: line.taxPercentage,
      lineType: "PRODUCT"
    }))
  };
}

function numericValue(value: string) {
  const parsed = Number(value.replace(",", "."));
  return Number.isFinite(parsed) ? parsed : Number.NaN;
}

export function purchaseDocumentLineTotal(line: PurchaseDocumentLineDraft) {
  const discounted = line.quantity * line.unitPrice * (1 - line.discount / 100);
  return line.taxesIncluded ? discounted : discounted * (1 + line.taxPercentage / 100);
}

function supplierLabel(supplier: PurchaseDocumentDialogProps["suppliers"][number]) {
  return supplier.legalName ?? supplier.razonSocial ?? supplier.id;
}

function productLabel(product: PurchaseDocumentProduct) {
  return [product.code ?? product.barcode, product.name].filter(Boolean).join(" - ") || product.id;
}

export function PurchaseDocumentDialog({
  open,
  mode,
  token,
  products,
  warehouses,
  suppliers,
  taxes,
  t,
  onClose,
  onConfirmed
}: PurchaseDocumentDialogProps) {
  const [warehouseId, setWarehouseId] = useState("");
  const [supplierId, setSupplierId] = useState("");
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [externalNumber, setExternalNumber] = useState("");
  const [directStock, setDirectStock] = useState(false);
  const [productId, setProductId] = useState("");
  const [quantity, setQuantity] = useState("1");
  const [unitPrice, setUnitPrice] = useState("");
  const [discount, setDiscount] = useState("0");
  const [taxId, setTaxId] = useState("");
  const [taxRegime, setTaxRegime] = useState<"IVA" | "IGIC">("IVA");
  const [taxesIncluded, setTaxesIncluded] = useState(true);
  const [lines, setLines] = useState<PurchaseDocumentLineDraft[]>([]);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const selectedProduct = products.find((product) => product.id === productId);
  const selectedTax = taxes.find((tax) => tax.id === taxId);
  const total = useMemo(() => lines.reduce((sum, line) => sum + purchaseDocumentLineTotal(line), 0), [lines]);

  useEffect(() => {
    if (!open) return;
    setWarehouseId(warehouses.find((warehouse) => warehouse.active !== false)?.id ?? "");
    setSupplierId("");
    setDate(new Date().toISOString().slice(0, 10));
    setExternalNumber("");
    setDirectStock(false);
    setProductId("");
    setQuantity("1");
    setUnitPrice("");
    setDiscount("0");
    setTaxId("");
    setTaxRegime("IVA");
    setTaxesIncluded(true);
    setLines([]);
    setBusy(false);
    setStatus("");
  }, [mode, open]);

  if (!open) return null;

  function selectProduct(nextProductId: string) {
    const product = products.find((candidate) => candidate.id === nextProductId);
    setProductId(nextProductId);
    setUnitPrice(product?.purchasePrice == null ? "" : String(product.purchasePrice));
    setTaxId(product?.taxId ?? taxes[0]?.id ?? "");
    setTaxesIncluded(product?.taxesIncluded ?? true);
  }

  function addLine() {
    if (!selectedProduct) return;
    const normalizedQuantity = numericValue(quantity);
    const normalizedPrice = numericValue(unitPrice);
    const normalizedDiscount = numericValue(discount);
    const normalizedTax = Number(selectedTax?.percentage ?? Number.NaN);
    if (normalizedQuantity <= 0 || normalizedPrice < 0
        || normalizedDiscount < 0 || normalizedDiscount > 100 || normalizedTax < 0 || normalizedTax > 100
        || (quantity.replace(",", ".").split(".")[1]?.length ?? 0) > 3
        || ![normalizedQuantity, normalizedPrice, normalizedDiscount, normalizedTax].every(Number.isFinite)) {
      setStatus(t("purchaseDocument.invalidLine"));
      return;
    }
    setLines((current) => [...current, {
      productId: selectedProduct.id,
      code: selectedProduct.code ?? selectedProduct.barcode ?? selectedProduct.id,
      name: selectedProduct.name ?? selectedProduct.code ?? selectedProduct.id,
      quantity: normalizedQuantity,
      unitPrice: normalizedPrice,
      discount: normalizedDiscount,
      taxRegime,
      taxPercentage: normalizedTax,
      taxesIncluded
    }]);
    setProductId("");
    setQuantity("1");
    setUnitPrice("");
    setDiscount("0");
    setTaxId("");
    setStatus("");
  }

  async function createAndConfirm() {
    if (!token || !warehouseId || !supplierId || lines.length === 0 || busy) {
      setStatus(t("purchaseDocument.required"));
      return;
    }
    setBusy(true);
    setStatus("");
    try {
      await apiRequest(confirmedPurchaseDocumentPath(mode), {
        token,
        method: "POST",
        body: buildPurchaseDocumentRequest({
          mode, warehouseId, supplierId, date, externalNumber, directStock, lines
        })
      });
      setStatus(t("purchaseDocument.confirmed"));
      onConfirmed();
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("purchaseDocument.error"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="purchase-document-title">
      <section className="filter-dialog purchase-document-dialog">
        <header className="filter-header">
          <div>
            <h2 id="purchase-document-title">{t(mode === "invoice" ? "purchaseDocument.invoice" : "purchaseDocument.deliveryNote")}</h2>
            <span>{t("purchaseDocument.subtitle")}</span>
          </div>
          <button type="button" onClick={onClose}>{t("common.close")}</button>
        </header>

        <div className="purchase-document-fields">
          <label><span>{t("stock.column.warehouse")}</span><ErpSelect value={warehouseId} options={warehouses.filter((item) => item.active !== false).map((item) => ({ value: item.id, label: item.name ?? item.nombre ?? item.id }))} onChange={setWarehouseId} /></label>
          <label><span>{t("warehouseDocument.supplier")}</span><ErpSelect value={supplierId} options={suppliers.filter((item) => item.active !== false).map((item) => ({ value: item.id, label: supplierLabel(item) }))} onChange={setSupplierId} /></label>
          <label><span>{t("salesReport.filter.date")}</span><input type="date" value={date} onChange={(event) => setDate(event.target.value)} /></label>
          <label><span>{t("purchaseDocument.externalNumber")}</span><input value={externalNumber} onChange={(event) => setExternalNumber(event.target.value)} /></label>
          {mode === "invoice" && <label className="purchase-document-check"><input type="checkbox" checked={directStock} onChange={(event) => setDirectStock(event.target.checked)} /><span>{t("purchaseDocument.directStock")}</span></label>}
        </div>

        <div className="purchase-document-line-editor">
          <label><span>{t("goodsCheck.column.product")}</span><ErpSelect value={productId} options={products.map((item) => ({ value: item.id, label: productLabel(item) }))} onChange={selectProduct} /></label>
          <label><span>{t("goodsCheck.quantity")}</span><input inputMode="decimal" value={quantity} onChange={(event) => setQuantity(event.target.value)} /></label>
          <label><span>{t("purchaseDocument.unitPrice")}</span><input inputMode="decimal" value={unitPrice} onChange={(event) => setUnitPrice(event.target.value)} /></label>
          <label><span>{t("purchaseDocument.discount")}</span><input inputMode="decimal" value={discount} onChange={(event) => setDiscount(event.target.value)} /></label>
          <label><span>{t("purchaseDocument.tax")}</span><ErpSelect value={taxId} options={taxes.map((item) => ({ value: item.id, label: `${item.percentage ?? 0}%` }))} onChange={setTaxId} /></label>
          <label><span>{t("purchaseDocument.taxRegime")}</span><ErpSelect value={taxRegime} options={[{ value: "IVA", label: "IVA" }, { value: "IGIC", label: "IGIC" }]} onChange={(value) => setTaxRegime(value as "IVA" | "IGIC")} /></label>
          <label className="purchase-document-check"><input type="checkbox" checked={taxesIncluded} onChange={(event) => setTaxesIncluded(event.target.checked)} /><span>{t("purchaseDocument.taxIncluded")}</span></label>
          <button type="button" onClick={addLine}>{t("purchaseDocument.addLine")}</button>
        </div>

        <div className="purchase-document-lines">
          <table className="report-table">
            <thead><tr><th>{t("goodsCheck.column.code")}</th><th>{t("goodsCheck.column.product")}</th><th>{t("goodsCheck.quantity")}</th><th>{t("purchaseDocument.unitPrice")}</th><th>{t("purchaseDocument.discount")}</th><th>{t("purchaseDocument.taxRegime")}</th><th>{t("purchaseDocument.tax")}</th><th>{t("salesReport.column.total")}</th><th /></tr></thead>
            <tbody>
              {lines.map((line, index) => <tr key={`${line.productId}-${index}`}><td>{line.code}</td><td>{line.name}</td><td>{line.quantity}</td><td>{line.unitPrice.toFixed(2)}</td><td>{line.discount.toFixed(2)}%</td><td>{line.taxRegime}</td><td>{line.taxPercentage.toFixed(2)}%</td><td>{purchaseDocumentLineTotal(line).toFixed(2)}</td><td><button type="button" onClick={() => setLines((current) => current.filter((_, candidate) => candidate !== index))}>{t("common.delete")}</button></td></tr>)}
              {lines.length === 0 && <tr><td colSpan={9}>{t("warehouseDocument.emptyLines")}</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="purchase-document-total">{t("salesReport.total")}: <strong>{total.toFixed(2)}</strong></div>
        {status && <p className="stock-operation-status" aria-live="polite">{status}</p>}
        <footer className="filter-actions"><button type="button" onClick={onClose}>{t("common.cancel")}</button><button type="button" disabled={busy || !warehouseId || !supplierId || lines.length === 0} onClick={() => void createAndConfirm()}>{busy ? t("common.loading") : t("purchaseDocument.createAndConfirm")}</button></footer>
      </section>
    </div>
  );
}

export default PurchaseDocumentDialog;
