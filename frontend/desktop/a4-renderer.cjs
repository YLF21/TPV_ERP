function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;").replaceAll("'", "&#39;");
}

function money(value) { return Number(value || 0).toFixed(2); }

function address(value = {}) {
  return [value.line1, [value.postalCode, value.city].filter(Boolean).join(" "), value.province, value.country]
    .filter((item, index, values) => item && values.indexOf(item) === index).join(", ");
}

function party(label, value, labels) {
  if (!value) return "";
  return `<section class="party"><strong>${escapeHtml(label)}</strong><div>${escapeHtml(value.name)}</div><div>${escapeHtml(labels.taxId)}: ${escapeHtml(value.taxId)}</div><div>${escapeHtml(address(value.address))}</div></section>`;
}

function renderA4DocumentHtml(document) {
  const labels = { terminal: "Terminal", description: "Description", quantity: "Quantity",
    unitPrice: "Unit price", base: "Base", tax: "Tax", taxIncluded: "Tax included",
    yes: "Yes", no: "No", mixed: "Mixed", total: "Total", issuer: "Issuer", customer: "Customer", taxId: "Tax ID", ...(document.labels || {}) };
  const rows = (document.lines || []).map((line) => `<tr>
    <td>${escapeHtml(line.name)}</td><td class="right">${escapeHtml(line.quantity)}</td>
    <td class="right">${money(line.price)}</td><td class="right">${money(line.total)}</td>
  </tr>`).join("");
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    @page { size: A4 portrait; margin: 14mm; } body { margin:0;color:#111827;font-family:Arial,"Segoe UI",sans-serif;font-size:12px }
    header{display:flex;justify-content:space-between;gap:20px;border-bottom:2px solid #111827;padding-bottom:12px;margin-bottom:20px} h1{margin:0;font-size:26px}
    .meta{text-align:right;line-height:1.6} table{width:100%;border-collapse:collapse;margin-top:18px} th{background:#e8eef7;text-align:left}
    th,td{border:1px solid #c8d2e0;padding:8px}.right{text-align:right}.totals{width:260px;margin-left:auto;margin-top:20px}
    .row{display:flex;justify-content:space-between;padding:7px 0;border-bottom:1px solid #d5dce8}.total{font-size:18px;font-weight:800;border-bottom:0}
    .parties{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin:12px 0 20px}.party{border:1px solid #c8d2e0;padding:10px;line-height:1.5}.party strong{display:block;margin-bottom:4px}
  </style></head><body><header><div><h1>${escapeHtml(document.title || "Document")}</h1><div>${escapeHtml(document.storeName)}</div></div>
  <div class="meta"><div>${escapeHtml(labels.terminal)} ${escapeHtml(document.terminalCode)}</div><div>${escapeHtml(document.issuedAt)}</div></div></header>
  <div class="parties">${party(labels.issuer, document.issuer, labels)}${party(labels.customer, document.customer, labels)}</div>
  <table><thead><tr><th>${escapeHtml(labels.description)}</th><th class="right">${escapeHtml(labels.quantity)}</th><th class="right">${escapeHtml(labels.unitPrice)}</th><th class="right">${escapeHtml(labels.total)}</th></tr></thead><tbody>${rows}</tbody></table>
  <section class="totals"><div class="row"><span>${escapeHtml(labels.base)}</span><strong>${money(document.subtotal)}</strong></div>
  <div class="row"><span>${escapeHtml(labels.tax)}</span><strong>${money(document.tax)}</strong></div>
  <div class="row"><span>${escapeHtml(labels.taxIncluded)}</span><strong>${escapeHtml(document.taxIncluded === "MIXED" ? labels.mixed : document.taxIncluded ? labels.yes : labels.no)}</strong></div>
  <div class="row total"><span>${escapeHtml(labels.total)}</span><strong>${money(document.total)}</strong></div></section></body></html>`;
}

module.exports = { renderA4DocumentHtml };
