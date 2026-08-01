function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function safeImage(value) {
  const source = String(value ?? "");
  return /^data:image\/(png|jpe?g|webp|gif);base64,/i.test(source) ? source : "";
}

function renderTableReportHtml(report = {}) {
  const columns = Array.isArray(report.columns) ? report.columns : [];
  const rows = Array.isArray(report.rows) ? report.rows : [];
  const filters = Array.isArray(report.filters) ? report.filters : [];
  const totals = Array.isArray(report.totals) ? report.totals : [];
  const image = safeImage(report.imageDataUrl);
  const fallback = String(report.imageFallback ?? "").slice(0, 2).toUpperCase();
  const imageBlock = image
    ? `<img class="product-image" src="${image}" alt="">`
    : fallback ? `<div class="product-image product-fallback">${escapeHtml(fallback)}</div>` : "";

  return `<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'">
<title>${escapeHtml(report.title)}</title>
<style>
@page { size: A4 portrait; margin: 8mm; }
* { box-sizing: border-box; }
body { margin: 0; color: #10243f; font-family: "Segoe UI", Tahoma, sans-serif; font-size: 7px; }
.document-title { margin: 0 0 7px; color: #082d59; font-size: 17px; font-weight: 800; line-height: 1.15; }
.title-band { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 9px; align-items: center; padding: 8px 9px; background: #082d59; color: #fff; }
.product-image { width: 48px; height: 48px; object-fit: contain; border: 1px solid #b9c7d6; background: #fff; }
.product-fallback { display: grid; place-items: center; color: #082d59; font-size: 24px; font-weight: 900; }
.subject { font-size: 10px; font-weight: 800; line-height: 1.25; }
.code { margin-top: 4px; color: #d6e5f5; font-weight: 700; }
.filters { display: grid; grid-template-columns: repeat(${Math.max(filters.length, 1)}, minmax(0, 1fr)); border: 1px solid #aebed0; border-top: 0; }
.filter { min-height: 38px; display: grid; gap: 2px; padding: 6px 9px; }
.filter + .filter { border-left: 1px solid #c8d3df; }
.filter span, .total span { color: #536a83; font-size: 7px; font-weight: 800; text-transform: uppercase; }
.filter strong { font-size: 8px; }
table { width: 100%; margin-top: 7px; border-collapse: collapse; table-layout: fixed; }
thead { display: table-header-group; }
th { padding: 4px 3px; border: 1px solid #526f91; background: #123b69; color: #fff; font-size: 6px; line-height: 1.2; overflow-wrap: anywhere; text-align: left; }
td { padding: 3px; border: 1px solid #ccd6e1; font-size: 6.5px; line-height: 1.25; overflow-wrap: anywhere; vertical-align: top; }
tbody tr:nth-child(even) td { background: #f2f6fa; }
tr { break-inside: avoid; }
.totals { display: grid; grid-template-columns: repeat(${Math.max(totals.length, 1)}, minmax(0, 1fr)); margin-top: 7px; border: 1px solid #9fb2c7; background: #e7edf4; break-inside: avoid; }
.total { display: grid; gap: 3px; padding: 7px 9px; }
.total + .total { border-left: 1px solid #9fb2c7; }
.total strong { color: #082d59; font-size: 13px; }
</style>
</head>
<body>
<h1 class="document-title">${escapeHtml(report.title)}</h1>
<header class="title-band">
  ${imageBlock}
  <div><div class="subject">${escapeHtml(report.subject)}</div><div class="code">${escapeHtml(report.code)}</div></div>
</header>
<section class="filters">${filters.map((filter) => `<div class="filter"><span>${escapeHtml(filter.label)}</span><strong>${escapeHtml(filter.value)}</strong></div>`).join("")}</section>
<table>
  <thead><tr>${columns.map((column) => `<th>${escapeHtml(column.label)}</th>`).join("")}</tr></thead>
  <tbody>${rows.map((row) => `<tr>${columns.map((_column, index) => `<td>${escapeHtml(row[index])}</td>`).join("")}</tr>`).join("")}</tbody>
</table>
<section class="totals">${totals.map((total) => `<div class="total"><span>${escapeHtml(total.label)}</span><strong>${escapeHtml(total.value)}</strong></div>`).join("")}</section>
</body>
</html>`;
}

module.exports = { renderTableReportHtml };
