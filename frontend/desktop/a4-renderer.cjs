function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;").replaceAll("'", "&#39;");
}

function money(value) { return Number(value || 0).toFixed(2); }
function quantity(value) { return Number(value || 0).toLocaleString("es-ES", { maximumFractionDigits: 3 }); }

function addressLines(value = {}) {
  return [value.line1, [value.postalCode, value.city].filter(Boolean).join(" "), value.province, value.country]
    .filter((item, index, values) => item && values.indexOf(item) === index);
}

function party(value, labels, kind) {
  if (!value) return "";
  const logo = kind === "issuer" && value.logo
    ? `<img class="company-logo" src="${escapeHtml(value.logo)}" alt="">`
    : "";
  return `<section class="party ${kind}">${logo}<div class="party-data">
    <strong>${escapeHtml(value.name)}</strong>
    <div>${escapeHtml(labels.taxId)}: ${escapeHtml(value.taxId)}</div>
    ${addressLines(value.address).map((line) => `<div>${escapeHtml(line)}</div>`).join("")}
    ${value.phone ? `<div>${escapeHtml(labels.phone || "Teléfono")}: ${escapeHtml(value.phone)}</div>` : ""}
  </div></section>`;
}

function renderA4DocumentHtml(document) {
  const labels = {
    description: "Nombre del artículo", quantity: "Cantidad", unitPrice: "Precio",
    base: "Base", discount: "Descuento", tax: "Impuesto", total: "Total", issuer: "Emisor", customer: "Cliente",
    taxId: "NIF", phone: "Teléfono", code: "Código", barcode: "Código de barras",
    paymentMethod: "Forma de pago", bankDetails: "Datos bancarios", bankName: "Entidad bancaria",
    iban: "IBAN", notes: "Observaciones", ...(document.labels || {})
  };
  const retail = document.fiscalProfile === "IGIC_MINORISTA";
  const taxRateLabel = document.fiscalProfile === "IVA" ? "IVA %" : "IGIC %";
  const rows = (document.lines || []).map((line) => `<tr>
    <td>${escapeHtml(line.code)}</td>
    <td>${escapeHtml(line.barcode)}</td>
    <td>${escapeHtml(line.name)}</td>
    <td class="right">${quantity(line.quantity)}</td>
    <td class="right">${money(line.price)}</td>
    <td class="right">${retail ? "0" : escapeHtml(line.taxPercentage ?? "")}</td>
    <td class="right">${money(line.total)}</td>
  </tr>`).join("");
  const payments = (document.payments || []).map((payment) =>
    `<div><strong>${escapeHtml(payment.method)}</strong><span>${money(payment.amount)}</span>${payment.reference ? `<small>${escapeHtml(payment.reference)}</small>` : ""}</div>`
  ).join("");
  const accounts = (document.bankAccounts || []).map((account) =>
    `<div class="bank-row"><span>${escapeHtml(account.bankName)}</span><strong>${escapeHtml(account.iban)}</strong></div>`
  ).join("");
  const notes = (document.notes || []).filter(Boolean).map((note) => `<p>${escapeHtml(note)}</p>`).join("");
  const qr = document.qrImage ? `<figure class="fiscal-qr"><img src="${escapeHtml(document.qrImage)}" alt="QR AEAT"><figcaption>Factura verificable en la sede electrónica de la AEAT</figcaption></figure>` : "";

  return `<!doctype html><html><head><meta charset="utf-8"><style>
    @page{size:A4 portrait;margin:12mm}*{box-sizing:border-box}body{margin:0;color:#000;font-family:Arial,"Segoe UI",sans-serif;font-size:10.5px;line-height:1.35}
    .top{display:grid;grid-template-columns:minmax(0,1fr) 210px;gap:22px;min-height:150px}.issuer{display:flex;align-items:flex-start;gap:12px;padding-top:4px}.company-logo{width:100px;max-height:70px;object-fit:contain;object-position:left top}.party-data>strong{display:block;font-size:13px;margin-bottom:4px}.right-head{display:flex;flex-direction:column;align-items:stretch}.fiscal-qr{margin:0 0 8px auto;width:112px;text-align:center}.fiscal-qr img{width:92px;height:92px;display:block;margin:0 auto}.fiscal-qr figcaption{font-size:7px;line-height:1.15;margin-top:2px}.customer{border-top:1px solid #000;padding-top:7px;margin-top:auto}
    .document-data{margin:12px 0 10px}.document-data h1{font-size:25px;letter-spacing:.5px;margin:0 0 6px;font-weight:800}.document-data-grid{display:grid;grid-template-columns:120px 1fr;max-width:360px}.document-data-grid span{font-weight:700}
    table{width:100%;border-collapse:collapse;table-layout:fixed}th,td{border:1px solid #000;padding:5px 4px;vertical-align:top}th{font-weight:800;text-align:left;background:transparent}.right{text-align:right}th:nth-child(1){width:10%}th:nth-child(2){width:15%}th:nth-child(3){width:31%}th:nth-child(4){width:9%}th:nth-child(5){width:12%}th:nth-child(6){width:9%}th:nth-child(7){width:14%}
    .summary{display:grid;grid-template-columns:minmax(0,1fr) 240px;gap:22px;margin-top:14px}.payment-block h2,.bank-block h2,.notes h2{font-size:11px;margin:0 0 6px}.payments>div,.bank-row{display:grid;grid-template-columns:1fr auto;gap:4px 10px;padding:3px 0}.payments small{grid-column:1/-1}.totals{border-top:1px solid #000}.total-row{display:flex;justify-content:space-between;padding:5px 0;border-bottom:1px solid #000}.total-row.final{font-size:16px;font-weight:800;padding-top:8px}.bank-block,.notes{margin-top:12px;border-top:1px solid #000;padding-top:8px}.bank-row strong{font-family:"Cascadia Mono",Consolas,monospace}.notes p{white-space:pre-wrap;margin:0}.retail-legend{margin-top:8px;font-size:9px}
  </style></head><body>
    <header class="top">${party(document.issuer, labels, "issuer")}<div class="right-head">${qr}${party(document.customer, labels, "customer")}</div></header>
    <section class="document-data"><h1>${escapeHtml((document.title || "FACTURA").split(" ")[0])}</h1><div class="document-data-grid"><span>Número</span><strong>${escapeHtml(document.documentNumber || document.title || "")}</strong><span>Fecha</span><strong>${escapeHtml(document.issuedAt)}</strong></div></section>
    <table><thead><tr><th>${escapeHtml(labels.code)}</th><th>${escapeHtml(labels.barcode)}</th><th>${escapeHtml(labels.description)}</th><th class="right">${escapeHtml(labels.quantity)}</th><th class="right">${escapeHtml(labels.unitPrice)}</th><th class="right">${escapeHtml(labels.taxRate || taxRateLabel)}</th><th class="right">${escapeHtml(labels.total)}</th></tr></thead><tbody>${rows}</tbody></table>
    <section class="summary"><div><section class="payment-block"><h2>${escapeHtml(labels.paymentMethod)}</h2><div class="payments">${payments || "—"}</div></section>${accounts ? `<section class="bank-block"><h2>${escapeHtml(labels.bankDetails)}</h2>${accounts}</section>` : ""}</div><div class="totals">${Number(document.discount || 0) === 0 ? "" : `<div class="total-row"><span>${escapeHtml(labels.discount)}</span><strong>-${money(Math.abs(document.discount))}</strong></div>`}<div class="total-row"><span>${escapeHtml(labels.base)}</span><strong>${money(document.subtotal)}</strong></div><div class="total-row"><span>${escapeHtml(labels.tax)}</span><strong>${money(document.tax)}</strong></div><div class="total-row final"><span>${escapeHtml(labels.total)}</span><strong>${money(document.total)}</strong></div></div></section>
    ${retail ? '<p class="retail-legend">Comerciante minorista</p>' : ""}${notes ? `<section class="notes"><h2>${escapeHtml(labels.notes)}</h2>${notes}</section>` : ""}
  </body></html>`;
}

module.exports = { renderA4DocumentHtml };
