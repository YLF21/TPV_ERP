import {
  getHardwareBridge,
  type A4DocumentPrintRequest,
  type HardwareBridge,
  type HardwareResult
} from "../hardware/hardware";

export type WarehouseA4PrintInput = {
  title: string;
  locale?: "es" | "en" | "zh";
  documentNumber?: string;
  storeName: string;
  terminalCode: string;
  issuedAt: string;
  warehouse?: string;
  partnerLabel?: string;
  partner?: string;
  discountPercent?: number;
  notes?: string[];
  lines: Array<{ code?: string; name: string; quantity: number; unitPrice: number; total: number }>;
  subtotal: number;
  total: number;
  labels: {
    terminal: string;
    description: string;
    quantity: string;
    unitPrice: string;
    base: string;
    tax: string;
    taxIncluded: string;
    yes: string;
    no: string;
    mixed: string;
    total: string;
    documentNumber: string;
    warehouse: string;
    partner: string;
    discount: string;
    print: string;
    close: string;
    notes?: string;
  };
};

export function buildWarehouseA4Document(input: WarehouseA4PrintInput): A4DocumentPrintRequest {
  const metadata = [
    metadataEntry(input.labels.documentNumber, input.documentNumber),
    metadataEntry(input.labels.warehouse, input.warehouse),
    metadataEntry(input.partnerLabel || input.labels.partner, input.partner),
    metadataEntry(input.labels.discount, input.discountPercent && input.discountPercent > 0 ? `${input.discountPercent}%` : undefined)
  ].filter((entry): entry is { label: string; value: string } => Boolean(entry));

  return {
    documentType: "REPORT",
    locale: input.locale,
    title: input.title,
    storeName: input.storeName,
    terminalCode: input.terminalCode,
    issuedAt: input.issuedAt,
    lines: input.lines.map((line) => ({
      name: [line.code, line.name].filter(Boolean).join(" - "),
      quantity: line.quantity,
      price: line.unitPrice,
      total: line.total
    })),
    subtotal: input.subtotal,
    tax: 0,
    taxIncluded: true,
    total: input.total,
    metadata,
    notes: input.notes?.map((note) => note.trim()).filter(Boolean),
    labels: {
      terminal: input.labels.terminal,
      description: input.labels.description,
      quantity: input.labels.quantity,
      unitPrice: input.labels.unitPrice,
      base: input.labels.base,
      tax: input.labels.tax,
      taxIncluded: input.labels.taxIncluded,
      yes: input.labels.yes,
      no: input.labels.no,
      mixed: input.labels.mixed,
      total: input.labels.total,
      notes: input.labels.notes,
      print: input.labels.print,
      close: input.labels.close
    }
  };
}

export function renderWarehouseA4PreviewHtml(document: A4DocumentPrintRequest): string {
  const metadata = (document.metadata ?? []).map((entry) =>
    `<div class="meta"><span>${escapeHtml(entry.label)}</span><strong>${escapeHtml(entry.value)}</strong></div>`
  ).join("");
  const rows = document.lines.map((line) => `<tr>
    <td>${escapeHtml(line.name)}</td><td class="number">${formatNumber(line.quantity)}</td>
    <td class="number">${formatMoney(line.price)}</td><td class="number">${formatMoney(line.total)}</td>
  </tr>`).join("");
  const notes = (document.notes ?? []).map((note) => `<li>${escapeHtml(note)}</li>`).join("");

  return `<!doctype html><html lang="${escapeHtml(document.locale ?? "es")}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
  <title>${escapeHtml(document.title)}</title><style>
  @page{size:A4;margin:14mm}*{box-sizing:border-box}body{margin:0;background:#eef3f8;color:#10213a;font:13px Arial,sans-serif}
  .toolbar{position:sticky;top:0;display:flex;justify-content:flex-end;gap:8px;padding:10px 14px;background:#fff;border-bottom:1px solid #d4deea}
  button{border:1px solid #1f5b9b;border-radius:5px;padding:8px 18px;background:#2f78bd;color:#fff;font-weight:700;cursor:pointer}
  button.secondary{background:#fff;color:#10213a;border-color:#b9c6d6}main{width:210mm;min-height:297mm;margin:18px auto;padding:14mm;background:#fff;box-shadow:0 8px 28px rgba(24,48,78,.15)}
  header{display:flex;justify-content:space-between;align-items:flex-start;gap:24px;padding-bottom:14px;border-bottom:3px solid #173f70}h1{margin:0;font-size:24px}.store{text-align:right;line-height:1.5}
  .metadata{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px 20px;margin:18px 0;padding:12px;border:1px solid #d4deea;border-radius:6px}.meta{display:flex;justify-content:space-between;gap:12px}.meta span{color:#5a6c82}
  table{width:100%;border-collapse:collapse}th{background:#173f70;color:#fff;text-align:left}th,td{padding:8px 10px;border-bottom:1px solid #dce4ed}.number{text-align:right;white-space:nowrap}
  .totals{width:42%;margin:18px 0 0 auto}.totals div{display:flex;justify-content:space-between;padding:7px 0;border-bottom:1px solid #dce4ed}.totals .total{font-size:17px;border-top:2px solid #173f70;border-bottom:0}
  .notes{margin-top:18px;padding:12px 16px;border:1px solid #d4deea;border-radius:6px}.notes h2{margin:0 0 8px;font-size:14px}.notes ul{margin:0;padding-left:20px}
  @media print{body{background:#fff}.toolbar{display:none}main{width:auto;min-height:auto;margin:0;padding:0;box-shadow:none}}</style></head><body>
  <div class="toolbar"><button class="secondary" type="button" onclick="window.close()">${escapeHtml(document.labels.close ?? "Cerrar")}</button><button type="button" onclick="window.print()">${escapeHtml(document.labels.print ?? "Imprimir")}</button></div>
  <main><header><h1>${escapeHtml(document.title)}</h1><div class="store"><strong>${escapeHtml(document.storeName)}</strong><br>${escapeHtml(document.labels.terminal)}: ${escapeHtml(document.terminalCode)}<br>${escapeHtml(document.issuedAt)}</div></header>
  ${metadata ? `<section class="metadata">${metadata}</section>` : ""}
  <table><thead><tr><th>${escapeHtml(document.labels.description)}</th><th class="number">${escapeHtml(document.labels.quantity)}</th><th class="number">${escapeHtml(document.labels.unitPrice)}</th><th class="number">${escapeHtml(document.labels.total)}</th></tr></thead><tbody>${rows}</tbody></table>
  <section class="totals"><div><span>${escapeHtml(document.labels.base)}</span><strong>${formatMoney(document.subtotal)}</strong></div><div><span>${escapeHtml(document.labels.taxIncluded)}</span><strong>${document.taxIncluded ? escapeHtml(document.labels.yes) : escapeHtml(document.labels.no)}</strong></div><div class="total"><span>${escapeHtml(document.labels.total)}</span><strong>${formatMoney(document.total)}</strong></div></section>
  ${notes ? `<section class="notes"><h2>${escapeHtml(document.labels.notes ?? "Notas")}</h2><ul>${notes}</ul></section>` : ""}</main></body></html>`;
}

export function openWarehouseDocumentPreview(request: A4DocumentPrintRequest, options: { autoPrint?: boolean } = {}): boolean {
  const preview = window.open("", "_blank", "popup=yes,width=1040,height=820");
  if (!preview) return false;
  writeWarehouseDocumentPreview(preview, request, options);
  return true;
}

export function writeWarehouseDocumentPreview(
  preview: Window,
  request: A4DocumentPrintRequest,
  options: { autoPrint?: boolean } = {}
): void {
  preview.opener = null;
  preview.document.open();
  preview.document.write(renderWarehouseA4PreviewHtml(request));
  preview.document.close();
  if (options.autoPrint) preview.setTimeout(() => { preview.focus(); preview.print(); }, 250);
}

export function hasDesktopHardwareBridge(): boolean {
  return typeof window !== "undefined" && Boolean(window.tpvDesktop?.hardware);
}

export async function printWarehouseA4Document(
  request: A4DocumentPrintRequest,
  bridge: HardwareBridge = getHardwareBridge()
): Promise<HardwareResult> {
  const config = await bridge.getHardwareConfig();
  return bridge.printA4Document(request, config);
}

function metadataEntry(label: string, value?: string): { label: string; value: string } | null {
  const normalized = value?.trim();
  return normalized ? { label, value: normalized } : null;
}

function escapeHtml(value: unknown): string {
  return String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#039;");
}

function formatMoney(value: number): string {
  const amount = Number(value || 0).toLocaleString("es-ES", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
  return `${amount} €`;
}

function formatNumber(value: number): string {
  return Number(value || 0).toLocaleString("es-ES", { maximumFractionDigits: 3 });
}
