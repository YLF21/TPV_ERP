function escapeHtml(value) { return String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#39;"); }
const money = (value) => Number(value || 0).toFixed(2);
function renderTicketHtml(ticket) {
  const l = { terminal: "Terminal", item: "Item", quantity: "Qty.", price: "Price", total: "Total", ...(ticket.labels || {}) };
  const giftReceipt = ticket.layout === "GIFT_RECEIPT";
  const lines = (ticket.lines || []).map((x) => {
    const serials = (x.serialNumbers || []).map((serial) => `<div class="serial">S/N: ${escapeHtml(serial)}</div>`).join("");
    const identity = `${x.code ? `<div class="code">${escapeHtml(x.code)}</div>` : ""}${escapeHtml(x.name)}${serials}`;
    return giftReceipt
      ? `<tr><td>${identity}</td><td class="right">${escapeHtml(x.quantity)}</td></tr>`
      : `<tr><td>${identity}</td><td class="right">${escapeHtml(x.quantity)}</td><td class="right">${money(x.price)}</td><td class="right">${money(x.total)}</td></tr>`;
  }).join("");
  const payments = (ticket.payments || []).map((x) => `<div class="row"><span>${escapeHtml(x.method)}</span><strong>${money(x.amount)}</strong></div>`).join("");
  const heading = giftReceipt ? "TICKET REGALO" : escapeHtml(ticket.storeName || "APP");
  const header = giftReceipt
    ? `<tr><th>${escapeHtml(l.item)}</th><th class="right">${escapeHtml(l.quantity)}</th></tr>`
    : `<tr><th>${escapeHtml(l.item)}</th><th class="right">${escapeHtml(l.quantity)}</th><th class="right">${escapeHtml(l.price)}</th><th class="right">${escapeHtml(l.total)}</th></tr>`;
  const settlement = giftReceipt ? "" : `${payments}<div class="row total"><span>${escapeHtml(l.total)}</span><strong>${money(ticket.total)}</strong></div>`;
  return `<!doctype html><html><head><meta charset="utf-8"><style>@page{margin:4mm;size:80mm auto}body{width:72mm;margin:0;color:#000;font-family:Arial,sans-serif;font-size:11px}h1{text-align:center;font-size:16px;margin-bottom:2px}.store{text-align:center;font-size:12px;font-weight:700}.meta{text-align:center;margin:5px 0 8px}.code{font-size:10px;font-weight:800;margin-bottom:1px}table{width:100%;border-collapse:collapse}th{border-bottom:1px solid #000;text-align:left}.right{text-align:right}.serial{font-size:10px;margin-top:1px}.row{display:flex;justify-content:space-between}.total{font-size:16px;font-weight:800}</style></head><body><h1>${heading}</h1>${giftReceipt ? `<div class="store">${escapeHtml(ticket.storeName || "APP")}</div>` : ""}<div class="meta"><div>${escapeHtml(ticket.documentNumber)}</div><div>${escapeHtml(l.terminal)} ${escapeHtml(ticket.terminalCode)}</div><div>${escapeHtml(ticket.issuedAt)}</div></div><table><thead>${header}</thead><tbody>${lines}</tbody></table>${settlement}</body></html>`;
}
module.exports = { renderTicketHtml };
