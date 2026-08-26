function escapeHtml(value) { return String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#39;"); }
const money = (value) => Number(value || 0).toFixed(2);
function renderTicketHtml(ticket) {
  if (typeof ticket?.documentRaster === "string" && ticket.documentRaster.startsWith("data:image/")) {
    return `<!doctype html><html><head><meta charset="utf-8"><style>@page{margin:0;size:80mm auto}html,body{width:80mm;margin:0;padding:0;background:#fff}img{display:block;width:80mm;height:auto;margin:0}</style></head><body><img src="${escapeHtml(ticket.documentRaster)}" alt=""></body></html>`;
  }
  const l = { terminal: "Terminal", item: "Item", quantity: "Qty.", price: "Price", discount: "Descuento", base: "Base", tax: "Impuesto", total: "Total", ...(ticket.labels || {}) };
  const giftReceipt = ticket.layout === "GIFT_RECEIPT";
  const cancellationReceipt = ticket.layout === "CANCELLATION_RECEIPT";
  const lines = (ticket.lines || []).map((x) => {
    const serials = (x.serialNumbers || []).map((serial) => `<div class="serial">S/N: ${escapeHtml(serial)}</div>`).join("");
    const identity = `${x.code ? `<div class="code">${escapeHtml(x.code)}</div>` : ""}${escapeHtml(x.name)}${serials}`;
    return giftReceipt
      ? `<tr><td>${identity}</td><td class="right">${escapeHtml(x.quantity)}</td></tr>`
      : `<tr><td>${identity}</td><td class="right">${escapeHtml(x.quantity)}</td><td class="right">${money(x.price)}</td><td class="right">${money(x.total)}</td></tr>`;
  }).join("");
  const payments = (ticket.payments || []).map((x) => `<div class="row"><span>${escapeHtml(x.method)}${x.reference ? `<small>${escapeHtml(x.reference)}</small>` : ""}</span><strong>${money(x.amount)}</strong></div>`).join("");
  const details = (ticket.details || []).map((x) => `<div class="detail"><span>${escapeHtml(x.label)}</span><strong>${escapeHtml(x.value)}</strong></div>`).join("");
  const heading = ticket.title ? escapeHtml(ticket.title) : giftReceipt ? "TICKET REGALO" : escapeHtml(ticket.storeName || "APP");
  const header = giftReceipt
    ? `<tr><th>${escapeHtml(l.item)}</th><th class="right">${escapeHtml(l.quantity)}</th></tr>`
    : `<tr><th>${escapeHtml(l.item)}</th><th class="right">${escapeHtml(l.quantity)}</th><th class="right">${escapeHtml(l.price)}</th><th class="right">${escapeHtml(l.total)}</th></tr>`;
  const discount = Number(ticket.discount || 0) === 0
    ? ""
    : `<div class="row discount"><span>${escapeHtml(l.discount)}</span><strong>-${money(Math.abs(ticket.discount))}</strong></div>`;
  const fiscal = ticket.subtotal === undefined && ticket.tax === undefined
    ? ""
    : `<div class="row"><span>${escapeHtml(l.base)}</span><strong>${money(ticket.subtotal)}</strong></div><div class="row"><span>${escapeHtml(l.tax)}</span><strong>${money(ticket.tax)}</strong></div>`;
  const settlement = giftReceipt ? "" : `${payments}${discount}${fiscal}<div class="row total"><span>${escapeHtml(l.total)}</span><strong>${money(ticket.total)}</strong></div>`;
  const fiscalSnapshot = ticket.fiscal && typeof ticket.fiscal === "object" ? ticket.fiscal : null;
  const fiscalPrefix = fiscalSnapshot?.prefix || "QR tributario:";
  const fiscalLegend = fiscalSnapshot?.legend
    ? `<div class="fiscal-legend">${escapeHtml(fiscalSnapshot.legend)}</div>` : "";
  const fiscalTestNotice = fiscalSnapshot?.testNotice
    ? `<div class="fiscal-test-notice">${escapeHtml(fiscalSnapshot.testNotice)}</div>` : "";
  const fiscalQrUrl = fiscalSnapshot?.qrUrl || ticket.qrUrl || "";
  const fiscalQr = ticket.qrImage
    ? `<section class="fiscal-qr"><div>${escapeHtml(fiscalPrefix)}</div><img src="${escapeHtml(ticket.qrImage)}" alt="QR tributario"><div class="qr-url">${escapeHtml(fiscalQrUrl)}</div>${fiscalLegend}${fiscalTestNotice}</section>`
    : "";
  const issuer = ticket.issuer
    ? `<section class="issuer"><strong>${escapeHtml(ticket.issuer.name)}</strong><div>NIF: ${escapeHtml(ticket.issuer.taxId)}</div>${ticket.issuer.address ? `<div>${escapeHtml(ticket.issuer.address)}</div>` : ""}</section>`
    : "";
  const store = giftReceipt || cancellationReceipt ? `<div class="store">${escapeHtml(ticket.storeName || "APP")}</div>` : "";
  const notice = ticket.notice ? `<div class="notice">${escapeHtml(ticket.notice)}</div>` : "";
  const logo = ticket.logo ? `<img class="logo" src="${escapeHtml(ticket.logo)}" alt="">` : "";
  const notes = (ticket.notes || []).filter(Boolean)
    .map((note) => `<p>${escapeHtml(note)}</p>`).join("");
  const table = cancellationReceipt ? "" : `<table><thead>${header}</thead><tbody>${lines}</tbody></table>`;
  return `<!doctype html><html><head><meta charset="utf-8"><style>@page{margin:4mm;size:80mm auto}body{width:72mm;margin:0;color:#000;font-family:Arial,sans-serif;font-size:11px}.logo{display:block;max-width:42mm;max-height:22mm;object-fit:contain;margin:0 auto 3mm}h1{text-align:center;font-size:16px;margin-bottom:2px}.store{text-align:center;font-size:12px;font-weight:700}.issuer{text-align:center;font-size:10px;margin:3px 0}.issuer strong{display:block;font-size:11px}.meta{text-align:center;margin:5px 0 8px}.code{font-size:10px;font-weight:800;margin-bottom:1px}table{width:100%;border-collapse:collapse}th{border-bottom:1px solid #000;text-align:left}.right{text-align:right}.serial{font-size:10px;margin-top:1px}.row,.detail{display:flex;justify-content:space-between;gap:8px}.row small{display:block;font-weight:400}.detail{padding:2px 0;border-bottom:1px dotted #999}.detail strong{text-align:right}.total{font-size:16px;font-weight:800}.notice{text-align:center;font-weight:800;border:1px solid #000;margin-top:8px;padding:5px}.notes{border-top:1px dashed #000;margin-top:8px;padding-top:6px;white-space:pre-wrap}.notes p{margin:0 0 4px}.fiscal-qr{text-align:center;border-top:1px dashed #000;margin-top:8px;padding-top:6px;font-weight:700}.fiscal-qr img{display:block;width:35mm;height:35mm;margin:3mm auto 1mm;image-rendering:auto}.qr-url{font-size:7px;line-height:1.1;word-break:break-all;font-weight:400}.fiscal-legend{font-size:9px;margin-top:2mm}.fiscal-test-notice{font-size:9px;margin-top:1mm;font-weight:800}</style></head><body>${logo}<h1>${heading}</h1>${store}${issuer}<div class="meta"><div>${escapeHtml(ticket.documentNumber)}</div><div>${escapeHtml(l.terminal)} ${escapeHtml(ticket.terminalCode)}</div><div>${escapeHtml(ticket.issuedAt)}</div></div>${details}${table}${settlement}${notice}${notes ? `<section class="notes">${notes}</section>` : ""}${fiscalQr}</body></html>`;
}
module.exports = { renderTicketHtml };
