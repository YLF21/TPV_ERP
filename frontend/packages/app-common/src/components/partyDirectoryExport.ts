import { strToU8, zipSync } from "fflate";
import type { LocaleCode } from "../types";
import { previewExport } from "../export/previewExport";
import { previewPreparedPdf } from "../export/preparedPdfExport";

export type PartyDirectoryExportReport = {
  title: string;
  companyName?: string;
  storeName?: string;
  printedAt?: string;
  columns: Array<{ key: string; label: string }>;
  rows: string[][];
  filters: Array<{ label: string; value: string }>;
  totals: Array<{ label: string; value: string }>;
};

const NAVY = "0A2F56";
const PALE = "F2F6FA";

function reportLandscape(report: PartyDirectoryExportReport): boolean {
  return report.columns.length > 6;
}

function reportMetadata(report: PartyDirectoryExportReport, locale: LocaleCode): Array<{ label: string; value: string }> {
  const labels = locale === "zh" ? ["公司", "门店", "打印日期"] : locale === "en" ? ["Company", "Store", "Print date"] : ["Empresa", "Tienda", "Fecha de impresión"];
  return [
    ...(report.companyName?.trim() ? [{ label: labels[0], value: report.companyName.trim() }] : []),
    ...(report.storeName?.trim() ? [{ label: labels[1], value: report.storeName.trim() }] : []),
    ...(report.printedAt?.trim() ? [{ label: labels[2], value: report.printedAt.trim() }] : [])
  ];
}

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

function xlsxBytes(report: PartyDirectoryExportReport, locale: LocaleCode): Uint8Array {
  const metadata = reportMetadata(report, locale);
  type Row = { values: string[]; kind: "title" | "detail" | "blank" | "header" | "data" | "total"; height?: number; alternate?: boolean };
  const rows: Row[] = [
    { values: [report.title], kind: "title", height: 34 },
    ...metadata.map((item): Row => ({ values: [item.label, item.value], kind: "detail" })),
    ...report.filters.map((filter): Row => ({ values: [filter.label, filter.value], kind: "detail" })),
    { values: [], kind: "blank" },
    { values: report.columns.map((column) => column.label), kind: "header", height: 29 },
    ...report.rows.map((values, index): Row => ({ values, kind: "data", alternate: index % 2 === 1 })),
    { values: [], kind: "blank" },
    ...report.totals.map((total): Row => ({ values: [total.label, total.value], kind: "total", height: 30 }))
  ];
  const headerRow = metadata.length + report.filters.length + 3;
  const lastDataRow = headerRow + report.rows.length;
  const rawSheetName = report.title.replace(/[\\/?*\[\]:]/g, " ").replace(/^'+|'+$/g, "").slice(0, 31) || "Sheet1";
  const sheetName = xmlText(rawSheetName);
  const printTitleName = xmlText(rawSheetName.replace(/'/g, "''"));
  const columnCount = report.rows.reduce((count, row) => Math.max(count, row.length), Math.max(2, report.columns.length));
  const textUnits = (value: string) => Array.from(value).reduce((count, character) =>
    count + (/[\u1100-\u11ff\u2e80-\u9fff\uac00-\ud7af\uf900-\ufaff\uff01-\uff60]/u.test(character) ? 2 : 1), 0);
  const columnWidths = Array.from({ length: columnCount }, (_, index) => {
    let width = 12;
    for (const row of rows) {
      if (row.kind !== "header" && row.kind !== "data") continue;
      for (const line of (row.values[index] ?? "").split(/\r?\n/)) width = Math.max(width, textUnits(line) + 2);
    }
    return Math.min(48, width);
  });
  const widths = columnWidths.map((width, index) => `<col min="${index + 1}" max="${index + 1}" width="${width}" customWidth="1"/>`).join("");
  const lineCount = (value: string, width: number) => value.split(/\r?\n/)
    .reduce((count, line) => count + Math.max(1, Math.ceil(textUnits(line) / Math.max(1, width - 2))), 0);
  const merges: string[] = [];
  const lastColumn = columnName(columnCount - 1);
  if (columnCount > 1) merges.push(`A1:${lastColumn}1`);
  const sheetRows = rows.map((row, rowIndex) => {
    const number = rowIndex + 1;
    let values = row.values;
    let styles: number[];
    if (row.kind === "title") {
      values = [values[0], ...Array.from({ length: columnCount - 1 }, () => "")];
      styles = values.map(() => 1);
    } else if (row.kind === "detail") {
      values = [values[0], values[1], ...Array.from({ length: columnCount - 2 }, () => "")];
      styles = values.map((_value, index) => index === 0 ? 6 : 7);
      if (columnCount > 2) merges.push(`B${number}:${lastColumn}${number}`);
      row.height = Math.min(409, Math.max(24, 9 + 15 * Math.max(lineCount(values[0], columnWidths[0]),
        lineCount(values[1], columnWidths.slice(1).reduce((sum, width) => sum + width, 0)))));
    } else if (row.kind === "total") {
      values = [values[0], ...Array.from({ length: columnCount - 2 }, () => ""), values[1]];
      styles = values.map(() => 4);
      if (columnCount > 2) merges.push(`A${number}:${columnName(columnCount - 2)}${number}`);
    } else {
      styles = values.map(() => row.kind === "header" ? 5 : row.alternate ? 3 : 2);
    }
    if (row.kind === "data") row.height = Math.min(409, Math.max(26, 9 + 15 * Math.max(1,
      ...values.map((value, index) => lineCount(value, columnWidths[index] ?? 12)))));
    return `<row r="${number}"${row.height ? ` ht="${row.height}" customHeight="1"` : ""}>${values.map((value, index) =>
      `<c r="${columnName(index)}${number}" t="inlineStr" s="${styles[index]}"><is><t xml:space="preserve">${xmlText(value)}</t></is></c>`
    ).join("")}</row>`;
  }).join("");
  const mergeCells = `<mergeCells count="${merges.length}">${merges.map((ref) => `<mergeCell ref="${ref}"/>`).join("")}</mergeCells>`;
  const sheet = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetPr><pageSetUpPr fitToPage="1"/></sheetPr><sheetViews><sheetView workbookViewId="0"><pane ySplit="${headerRow}" topLeftCell="A${headerRow + 1}" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight="20"/><cols>${widths}</cols><sheetData>${sheetRows}</sheetData>${mergeCells}<autoFilter ref="A${headerRow}:${lastColumn}${lastDataRow}"/><printOptions horizontalCentered="1"/><pageMargins left="0.3" right="0.3" top="0.5" bottom="0.5" header="0.2" footer="0.2"/><pageSetup paperSize="9" orientation="${reportLandscape(report) ? "landscape" : "portrait"}" fitToWidth="1" fitToHeight="0"/></worksheet>`;
  const entries = {
    "[Content_Types].xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>',
    "_rels/.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
    "xl/workbook.xml": `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${sheetName}" sheetId="1" r:id="rId1"/></sheets><definedNames><definedName name="_xlnm.Print_Titles" localSheetId="0">'${printTitleName}'!$${headerRow}:$${headerRow}</definedName></definedNames></workbook>`,
    "xl/_rels/workbook.xml.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>',
    "xl/styles.xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="5"><font><sz val="11"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="18"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="14"/><name val="Calibri"/></font></fonts><fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF0A2F56"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF2F6FA"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="8"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="0" fillId="3" borderId="0" xfId="0" applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="4" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="2" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="3" fillId="3" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="0" fillId="3" borderId="0" xfId="0" applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf></cellXfs></styleSheet>',
    "xl/worksheets/sheet1.xml": sheet
  };
  return zipSync(Object.fromEntries(Object.entries(entries).map(([name, value]) => [name, strToU8(value)])));
}

function reportHtml(report: PartyDirectoryExportReport, locale: LocaleCode): string {
  const metadata = reportMetadata(report, locale).map((item) => `<div><strong>${xmlText(item.label)}:</strong> ${xmlText(item.value)}</div>`).join("");
  const filters = report.filters.map((filter) => `<div><strong>${xmlText(filter.label)}:</strong> ${xmlText(filter.value)}</div>`).join("");
  const headings = report.columns.map((column) => `<th scope="col">${xmlText(column.label)}</th>`).join("");
  const rows = report.rows.map((row) => `<tr>${report.columns.map((_, index) => `<td>${xmlText(row[index] ?? "")}</td>`).join("")}</tr>`).join("");
  const totals = report.totals.map((total) => `<div><strong>${xmlText(total.label)}:</strong> ${xmlText(total.value)}</div>`).join("");
  const page = locale === "zh" ? "第" : locale === "en" ? "Page " : "Página ";
  const of = locale === "zh" ? " 页，共 " : locale === "en" ? " of " : " de ";
  return `<!doctype html><html lang="${locale}"><head><meta charset="utf-8"><title>${xmlText(report.title)}</title><style>@page{size:A4 ${reportLandscape(report) ? "landscape" : "portrait"};margin:14mm 10mm 18mm}@page{@bottom-right{content:"${page}" counter(page) "${of}" counter(pages);font:10px Calibri,Arial,sans-serif;color:#536a83}}*{box-sizing:border-box}body{font:11px Calibri,Arial,sans-serif;color:#172334;margin:0}h1{font-size:18px;background:#${NAVY};color:#fff;padding:12px 14px;margin:0 0 10px}section{margin:10px 0}.metadata{font-size:10px}.metadata div{margin:2px 0}table{width:100%;border-collapse:collapse;margin-top:12px;table-layout:fixed}thead{display:table-header-group}th,td{padding:8px;border:1px solid #ccd3dd;text-align:left;vertical-align:top;overflow-wrap:anywhere}th{background:#${NAVY};color:#fff}tbody tr:nth-child(even){background:#${PALE}}tr{break-inside:avoid}.totals{font-size:14px;font-weight:bold;break-inside:avoid}</style></head><body><h1>${xmlText(report.title)}</h1><section class="metadata">${metadata}</section><section class="filters">${filters}</section><table><thead><tr>${headings}</tr></thead><tbody>${rows}</tbody></table><section class="totals">${totals}</section></body></html>`;
}

function printReport(html: string): void {
  const iframe = document.createElement("iframe");
  iframe.setAttribute("aria-hidden", "true");
  iframe.style.cssText = "position:fixed;left:-10000px;top:0;width:1px;height:1px;border:0";
  document.body.appendChild(iframe);
  try {
    const page = iframe.contentDocument;
    const printer = iframe.contentWindow;
    if (!page || !printer) throw new Error("report_print_unavailable");
    page.open();
    page.write(html);
    page.close();
    printer.focus();
    printer.print();
  } finally {
    // Give the browser's print dialog time to capture this isolated document.
    window.setTimeout(() => iframe.remove(), 60_000);
  }
}

export async function savePartyDirectoryExport(
  format: "xlsx" | "pdf", report: PartyDirectoryExportReport, fileName: string, locale: LocaleCode = "es",
): Promise<void> {
  if (format === "pdf") {
    if (window.tpvDesktop?.reports) {
      await previewPreparedPdf("table", { ...report, subject: report.title, reportStyle: "partyDirectory", locale }, fileName, locale);
    } else {
      const html = reportHtml(report, locale);
      await previewExport({ blob: new Blob([html], { type: "text/html" }), html, fileName, locale,
        onDownload: async () => { printReport(html); return {}; } });
    }
    return;
  }
  const bytes = xlsxBytes(report, locale);
  await previewExport({ blob: new Blob([Uint8Array.from(bytes).buffer],
    { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" }), fileName, locale, formattedWorkbook: true });
}
