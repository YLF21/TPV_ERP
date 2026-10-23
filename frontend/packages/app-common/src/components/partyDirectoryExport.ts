import { strToU8, zipSync } from "fflate";

export type PartyDirectoryExportReport = {
  title: string;
  columns: Array<{ key: string; label: string }>;
  rows: string[][];
  filters: Array<{ label: string; value: string }>;
  totals: Array<{ label: string; value: string }>;
};

function xmlText(value: string): string {
  // XML 1.0 rejects control characters and unpaired UTF-16 surrogates.
  const valid = Array.from(value).filter((character) => {
    const point = character.codePointAt(0)!;
    return point === 9 || point === 10 || point === 13 || (point >= 32 && point <= 0xd7ff)
      || (point >= 0xe000 && point <= 0xfffd) || (point >= 0x10000 && point <= 0x10ffff);
  }).join("");
  return valid.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&apos;");
}

function columnName(index: number): string {
  let name = "";
  for (let current = index + 1; current > 0; current = Math.floor((current - 1) / 26)) {
    name = String.fromCharCode(65 + (current - 1) % 26) + name;
  }
  return name;
}

function xlsxBytes(report: PartyDirectoryExportReport): Uint8Array {
  const rows: Array<{ values: string[]; header?: boolean }> = [
    { values: [report.title] },
    ...report.filters.map((filter) => ({ values: [filter.label, filter.value] })),
    { values: [] },
    { values: report.columns.map((column) => column.label), header: true },
    ...report.rows.map((values) => ({ values })),
    { values: [] },
    ...report.totals.map((total) => ({ values: [total.label, total.value] }))
  ];
  const headerRow = report.filters.length + 3;
  const sheetName = xmlText(report.title.replace(/[\\/?*\[\]:]/g, " ").replace(/^'+|'+$/g, "").slice(0, 31) || "Sheet1");
  const columnCount = rows.reduce((count, row) => Math.max(count, row.values.length), Math.max(1, report.columns.length));
  const widths = Array.from({ length: columnCount }, (_, index) => {
    const longest = rows.reduce((length, row) => Math.max(length, Array.from(row.values[index] ?? "").length), 10);
    return `<col min="${index + 1}" max="${index + 1}" width="${Math.min(48, longest + 2)}" customWidth="1"/>`;
  }).join("");
  const sheetRows = rows.map((row, rowIndex) => `<row r="${rowIndex + 1}">${row.values.map((value, index) =>
    `<c r="${columnName(index)}${rowIndex + 1}" t="inlineStr"${row.header ? ' s="1"' : ""}><is><t xml:space="preserve">${xmlText(value)}</t></is></c>`
  ).join("")}</row>`).join("");
  const sheet = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="${headerRow}" topLeftCell="A${headerRow + 1}" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>${widths}</cols><sheetData>${sheetRows}</sheetData></worksheet>`;
  const entries = {
    "[Content_Types].xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>',
    "_rels/.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
    "xl/workbook.xml": `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${sheetName}" sheetId="1" r:id="rId1"/></sheets></workbook>`,
    "xl/_rels/workbook.xml.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>',
    "xl/styles.xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs></styleSheet>',
    "xl/worksheets/sheet1.xml": sheet
  };
  return zipSync(Object.fromEntries(Object.entries(entries).map(([name, value]) => [name, strToU8(value)])));
}

function printReport(report: PartyDirectoryExportReport): void {
  const iframe = document.createElement("iframe");
  iframe.setAttribute("aria-hidden", "true");
  iframe.style.cssText = "position:fixed;left:-10000px;top:0;width:1px;height:1px;border:0";
  document.body.appendChild(iframe);
  try {
    const page = iframe.contentDocument;
    const printer = iframe.contentWindow;
    if (!page || !printer) throw new Error("report_print_unavailable");
    const filters = report.filters.map((filter) => `<div><strong>${xmlText(filter.label)}:</strong> ${xmlText(filter.value)}</div>`).join("");
    const headings = report.columns.map((column) => `<th scope="col">${xmlText(column.label)}</th>`).join("");
    const rows = report.rows.map((row) => `<tr>${report.columns.map((_, index) => `<td>${xmlText(row[index] ?? "")}</td>`).join("")}</tr>`).join("");
    const totals = report.totals.map((total) => `<div><strong>${xmlText(total.label)}:</strong> ${xmlText(total.value)}</div>`).join("");
    page.open();
    page.write(`<!doctype html><html><head><meta charset="utf-8"><title>${xmlText(report.title)}</title><style>body{font:12px Arial,sans-serif;color:#172334;margin:24px}h1{font-size:18px}section{margin:12px 0}table{width:100%;border-collapse:collapse;margin-top:14px}th,td{padding:6px 8px;border-bottom:1px solid #ccd3dd;text-align:left;vertical-align:top}th{background:#e9eef5}@page{size:landscape;margin:12mm}</style></head><body><h1>${xmlText(report.title)}</h1><section>${filters}</section><table><thead><tr>${headings}</tr></thead><tbody>${rows}</tbody></table><section>${totals}</section></body></html>`);
    page.close();
    printer.focus();
    printer.print();
  } finally {
    // Give the browser's print dialog time to capture this isolated document.
    window.setTimeout(() => iframe.remove(), 60_000);
  }
}

export async function savePartyDirectoryExport(
  format: "xlsx" | "pdf", report: PartyDirectoryExportReport, fileName: string
): Promise<void> {
  const reports = window.tpvDesktop?.reports;
  if (format === "pdf") {
    if (!reports) return printReport(report);
    const result = await reports.exportTablePdf({ ...report, subject: report.title }, fileName);
    if (!result.ok) throw new Error(result.message || result.code);
    return;
  }

  const bytes = xlsxBytes(report);
  if (reports) {
    const result = await reports.saveFile({ defaultFileName: fileName, filters: [{ name: "Excel", extensions: ["xlsx"] }], bytes });
    if (!result.ok) throw new Error(result.message || result.code);
    return;
  }
  const url = URL.createObjectURL(new Blob([Uint8Array.from(bytes).buffer], { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" }));
  try {
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = fileName;
    document.body.appendChild(anchor);
    try { anchor.click(); } finally { anchor.remove(); }
  } finally {
    URL.revokeObjectURL(url);
  }
}
