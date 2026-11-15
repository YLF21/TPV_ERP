export type PreviewCell = string | number | boolean | Date | null;
export type PreviewCellFormat = {
  fontSize?: number; bold?: boolean; color?: string; background?: string;
  align?: "left" | "center" | "right"; numberFormat?: "euro" | "date" | "percent" | "number";
  minimumFractionDigits?: number; maximumFractionDigits?: number;
};
export type PreviewSheetFormat = {
  cells: Record<string, PreviewCellFormat>;
  merges: Record<string, number>;
  columnWidths: Record<number, number>;
  rowHeights: Record<number, number>;
  orientation?: "portrait" | "landscape";
  paperSize?: "A4" | "A3" | "A2";
  repeatingHeaderRow?: number;
};
export type PreviewSheet = { name: string; rows: PreviewCell[][]; format?: PreviewSheetFormat };

const MAX_BYTES = 32 * 1024 * 1024;
const MAX_CELLS = 250_000;

export function escapeHtml(value: unknown): string {
  return String(value ?? "").replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  })[character]!);
}

export function cellText(value: PreviewCell): string {
  return value instanceof Date ? value.toLocaleString() : String(value ?? "");
}

export function parseCsv(source: string): PreviewCell[][] {
  const sample = source.split(/\r?\n/, 1)[0];
  const delimiters = [",", ";", "\t"];
  const delimiter = delimiters.sort((left, right) => sample.split(right).length - sample.split(left).length)[0];
  const rows: PreviewCell[][] = [];
  let row: PreviewCell[] = [];
  let field = "";
  let quoted = false;
  for (let index = 0; index < source.length; index++) {
    const character = source[index];
    if (quoted) {
      if (character === '"' && source[index + 1] === '"') { field += '"'; index++; }
      else if (character === '"') quoted = false;
      else field += character;
    } else if (character === '"' && field === "") quoted = true;
    else if (character === delimiter) {
      row.push(field); field = "";
    } else if (character === "\n" || character === "\r") {
      row.push(field); rows.push(row); row = []; field = "";
      if (character === "\r" && source[index + 1] === "\n") index++;
    } else field += character;
  }
  if (quoted) throw new Error("invalid_csv");
  if (row.length || field !== "" || !rows.length) { row.push(field); rows.push(row); }
  return rows;
}

export async function readWorkbookPreview(blob: Blob, fileName: string, formattedWorkbook = false): Promise<PreviewSheet[]> {
  if (blob.size > MAX_BYTES) throw new Error("preview_too_large");
  let sheets: PreviewSheet[];
  if (/\.csv$/i.test(fileName)) {
    const text = (await blob.text()).replace(/^\uFEFF/, "");
    sheets = [{ name: fileName.replace(/\.csv$/i, ""), rows: parseCsv(text) }];
  } else {
    const { default: readXlsxFile } = await import("read-excel-file/browser");
    const source = await readXlsxFile(blob);
    sheets = source.map(({ sheet, data }) => ({ name: sheet, rows: data as PreviewCell[][] }));
    if (formattedWorkbook) {
      try { await addWorkbookFormatting(blob, sheets); } catch { /* Malformed formatting must not hide valid cell values. */ }
    }
  }
  if (!sheets.length || sheets.reduce((total, sheet) => total + sheet.rows.reduce((count, row) => count + row.length, 0), 0) > MAX_CELLS) {
    throw new Error("preview_too_large");
  }
  return sheets;
}

export function workbookPrintHtml(sheets: PreviewSheet[], title: string): string {
  const styled = sheets.some((sheet) => sheet.format);
  const orientation = sheets.find((sheet) => sheet.format?.orientation)?.format?.orientation ?? (styled ? "portrait" : "landscape");
  const paperSize = sheets.find((sheet) => sheet.format?.paperSize)?.format?.paperSize ?? "A4";
  const pageStyles = sheets.map((sheet, index) => sheet.format
    ? `@page workbook-sheet-${index}{size:${sheet.format.paperSize ?? "A4"} ${sheet.format.orientation ?? "portrait"};margin:12mm}` : "").join("");
  const sections = sheets.map((sheet, sheetIndex) => {
    const columnCount = sheet.format ? workbookColumnCount(sheet) : undefined;
    const rowHtml = (row: PreviewCell[], rowIndex: number) =>
      `<tr${sheet.format?.rowHeights[rowIndex] ? ` style="height:${sheet.format.rowHeights[rowIndex]}px"` : ""}>${workbookRowCells(sheet, row, rowIndex, columnCount).map(({ value, colSpan, style }) => `<td${colSpan > 1 ? ` colspan="${colSpan}"` : ""}${style ? ` style="${escapeHtml(cellStyleCss(style))}"` : ""}>${escapeHtml(value)}</td>`).join("")}</tr>`;
    const columns = sheet.format ? printColumns(sheet) : "";
    const headerRow = sheet.format?.repeatingHeaderRow;
    const table = headerRow !== undefined && sheet.rows[headerRow]
      ? `${headerRow > 0 ? `<table>${columns}<tbody>${sheet.rows.slice(0, headerRow).map(rowHtml).join("")}</tbody></table>` : ""}<table>${columns}<thead>${rowHtml(sheet.rows[headerRow], headerRow)}</thead><tbody>${sheet.rows.slice(headerRow + 1).map((row, index) => rowHtml(row, headerRow + 1 + index)).join("")}</tbody></table>`
      : `<table>${columns}${sheet.rows.map(rowHtml).join("")}</table>`;
    return `<section${sheet.format ? ` style="page:workbook-sheet-${sheetIndex}"` : ""}><h2>${escapeHtml(sheet.name)}</h2>${table}</section>`;
  }).join("");
  return `<!doctype html><html><head><meta charset="utf-8"><title>${escapeHtml(title)}</title><style>body{font:11px Calibri,Arial,sans-serif;color:#17212f;${styled ? "print-color-adjust:exact;-webkit-print-color-adjust:exact;" : ""}}section{break-after:page}section:last-child{break-after:auto}thead{display:table-header-group}h2{font-size:15px}table{border-collapse:collapse;width:100%;${styled ? "table-layout:fixed;" : ""}}td{border:1px solid #aeb9c6;padding:4px;vertical-align:top;overflow-wrap:anywhere;white-space:pre-wrap}${styled ? "tr{break-inside:avoid}" : ""}@page{size:${styled ? `${paperSize} ${orientation}` : "landscape"};margin:12mm}${pageStyles}</style></head><body>${sections}</body></html>`;
}

export type PreviewDisplayCell = { value: string; colSpan: number; style?: PreviewCellFormat; width?: number };

export function workbookRowCells(sheet: PreviewSheet, row: PreviewCell[], rowIndex: number, columnCount?: number): PreviewDisplayCell[] {
  const format = sheet.format;
  if (!format) return row.map((cell) => ({ value: cellText(cell), colSpan: 1 }));
  const last = Math.max(columnCount ?? workbookColumnCount(sheet),
    ...Object.keys(format.merges).filter((key) => key.startsWith(`${rowIndex}:`)).map((key) => Number(key.split(":")[1]) + format.merges[key]));
  const cells: PreviewDisplayCell[] = [];
  for (let column = 0; column < last; column++) {
    const key = `${rowIndex}:${column}`;
    const colSpan = Math.min(format.merges[key] ?? 1, last - column);
    const value = row[column];
    const style = format.cells[key];
    cells.push({ value: formatValue(value, style), colSpan, style, width: format.columnWidths[column] });
    column += colSpan - 1;
  }
  return cells;
}

export function cellStyleCss(style: PreviewCellFormat): string {
  const rules: string[] = [];
  if (style.fontSize) rules.push(`font-size:${style.fontSize}px`);
  if (style.bold) rules.push("font-weight:700");
  if (style.color) rules.push(`color:${style.color}`);
  if (style.background) rules.push(`background-color:${style.background}`);
  if (style.align) rules.push(`text-align:${style.align}`);
  return rules.join(";");
}

function formatValue(value: PreviewCell, style?: PreviewCellFormat): string {
  if (style?.numberFormat === "date" && value instanceof Date && !Number.isNaN(value.getTime())) {
    return new Intl.DateTimeFormat("es-ES", { day: "2-digit", month: "2-digit", year: "numeric", timeZone: "UTC" }).format(value);
  }
  if ((style?.numberFormat === "euro" || style?.numberFormat === "number") && typeof value === "number" && Number.isFinite(value)) {
    const formatted = new Intl.NumberFormat("es-ES", {
      useGrouping: true, minimumFractionDigits: style.minimumFractionDigits ?? 2,
      maximumFractionDigits: style.maximumFractionDigits ?? 2
    }).format(value);
    return style.numberFormat === "euro" ? `${formatted} €` : formatted;
  }
  if (style?.numberFormat === "percent" && typeof value === "number" && Number.isFinite(value)) {
    return `${new Intl.NumberFormat("es-ES", {
      minimumFractionDigits: style.minimumFractionDigits ?? 0,
      maximumFractionDigits: style.maximumFractionDigits ?? 2
    }).format(value * 100)}%`;
  }
  return cellText(value);
}

export function workbookColumnCount(sheet: PreviewSheet): number {
  const widths = sheet.format!.columnWidths;
  return Math.max(Object.keys(widths).reduce((max, key) => Math.max(max, Number(key)), -1),
    sheet.rows.reduce((max, row) => Math.max(max, row.length - 1), -1)) + 1;
}

function printColumns(sheet: PreviewSheet): string {
  const widths = sheet.format!.columnWidths;
  const count = workbookColumnCount(sheet);
  const total = Array.from({ length: count }, (_, index) => widths[index] ?? 80).reduce((sum, width) => sum + width, 0);
  return `<colgroup>${Array.from({ length: count }, (_, index) => `<col style="width:${((widths[index] ?? 80) / total * 100).toFixed(3)}%">`).join("")}</colgroup>`;
}

function children(element: Element, name: string): Element[] {
  return Array.from(element.children).filter((child) => child.localName === name);
}

function first(element: Element | undefined, name: string): Element | undefined { return element ? children(element, name)[0] : undefined; }

function xml(bytes?: Uint8Array): Document | undefined {
  if (!bytes || bytes.length > 16 * 1024 * 1024) return;
  const source = new TextDecoder().decode(bytes);
  if (/<!DOCTYPE|<!ENTITY/i.test(source)) return;
  const document = new DOMParser().parseFromString(source, "application/xml");
  return document.querySelector("parsererror") ? undefined : document;
}

function rgb(element?: Element): string | undefined {
  const value = element?.getAttribute("rgb");
  return value && /^(?:FF)?[0-9a-f]{6}$/i.test(value) ? `#${value.slice(-6).toUpperCase()}` : undefined;
}

function positiveNumber(value: string | null, min: number, max: number): number | undefined {
  const parsed = Number(value);
  return value !== null && Number.isFinite(parsed) && parsed >= min && parsed <= max ? parsed : undefined;
}

function cellIndex(reference: string): { row: number; column: number } | undefined {
  const match = /^([A-Z]{1,3})([1-9]\d{0,6})$/.exec(reference);
  if (!match) return;
  let column = 0;
  for (const letter of match[1]) column = column * 26 + letter.charCodeAt(0) - 64;
  return { row: Number(match[2]) - 1, column: column - 1 };
}

async function addWorkbookFormatting(blob: Blob, sheets: PreviewSheet[]): Promise<void> {
  const { unzipSync } = await import("fflate");
  let extractedBytes = 0;
  const files = unzipSync(new Uint8Array(await blob.arrayBuffer()), {
    filter: (file) => {
      if (!/^xl\/(?:workbook\.xml|_rels\/workbook\.xml\.rels|styles\.xml|worksheets\/[^/]+\.xml)$/.test(file.name)
        || file.originalSize > 16 * 1024 * 1024 || extractedBytes + file.originalSize > 48 * 1024 * 1024) return false;
      extractedBytes += file.originalSize;
      return true;
    }
  });
  const workbook = xml(files["xl/workbook.xml"]);
  const relationships = xml(files["xl/_rels/workbook.xml.rels"]);
  const styles = xml(files["xl/styles.xml"]);
  if (!workbook || !relationships || !styles) return;

  const relationshipPaths = new Map<string, string>();
  for (const relation of Array.from(relationships.getElementsByTagName("Relationship"))) {
    const target = relation.getAttribute("Target")?.replace(/^\//, "");
    if (!target) continue;
    const path = target.startsWith("xl/") ? target : `xl/${target}`;
    if (/^xl\/worksheets\/[^/.]+\.xml$/.test(path)) relationshipPaths.set(relation.getAttribute("Id") ?? "", path);
  }
  const bookSheets = Array.from(workbook.getElementsByTagName("sheet"));
  const fonts = Array.from(styles.getElementsByTagName("fonts")).flatMap((element) => children(element, "font"));
  const fills = Array.from(styles.getElementsByTagName("fills")).flatMap((element) => children(element, "fill"));
  const xfs = Array.from(styles.getElementsByTagName("cellXfs")).flatMap((element) => children(element, "xf"));
  const numFormats = new Map(Array.from(styles.getElementsByTagName("numFmt")).map((element) => [Number(element.getAttribute("numFmtId")), element.getAttribute("formatCode") ?? ""]));
  const builtinFormats: Record<number, string> = { 1: "0", 2: "0.00", 3: "#,##0", 4: "#,##0.00", 9: "0%", 10: "0.00%" };
  const parsedStyles = xfs.map((xf): PreviewCellFormat => {
    const font = fonts[Number(xf.getAttribute("fontId"))];
    const fill = fills[Number(xf.getAttribute("fillId"))];
    const formatId = Number(xf.getAttribute("numFmtId"));
    const formatCode = numFormats.get(formatId) ?? builtinFormats[formatId] ?? "";
    const alignment = first(xf, "alignment")?.getAttribute("horizontal");
    const fontPoints = positiveNumber(first(font, "sz")?.getAttribute("val") ?? null, 6, 30);
    const result: PreviewCellFormat = {};
    if (fontPoints) result.fontSize = Math.round(fontPoints * 4 / 3);
    if (font && first(font, "b")) result.bold = true;
    const textColor = font && rgb(first(font, "color"));
    if (textColor) result.color = textColor;
    const fillColor = fill && first(fill, "patternFill")?.getAttribute("patternType") === "solid" ? rgb(first(first(fill, "patternFill")!, "fgColor")) : undefined;
    if (fillColor) result.background = fillColor;
    if (alignment === "left" || alignment === "center" || alignment === "right") result.align = alignment;
    if (/[€]|\\20ac/i.test(formatCode)) {
      result.numberFormat = "euro";
      const numeric = /0(?:\.([0#]+))?/.exec(formatCode);
      const decimals = numeric ? numeric[1] ?? "" : "00";
      result.minimumFractionDigits = Math.min(20, decimals.replace(/#/g, "").length);
      result.maximumFractionDigits = Math.min(20, decimals.length);
    }
    else if (/(?:dd|d)[/.-](?:mm|m)[/.-](?:yyyy|yy)/i.test(formatCode)) result.numberFormat = "date";
    else {
      const percentage = /^0(?:\.([0#]+))?%$/.exec(formatCode);
      if (percentage) {
        const decimals = percentage[1] ?? "";
        result.numberFormat = "percent";
        result.minimumFractionDigits = Math.min(20, decimals.replace(/#/g, "").length);
        result.maximumFractionDigits = Math.min(20, decimals.length);
      } else {
        const numeric = /^(?:#,##)?0(?:\.([0#]+))?$/.exec(formatCode);
        if (numeric) {
          const decimals = numeric[1] ?? "";
          result.numberFormat = "number";
          result.minimumFractionDigits = Math.min(20, decimals.replace(/#/g, "").length);
          result.maximumFractionDigits = Math.min(20, decimals.length);
        }
      }
    }
    return result;
  });

  for (const sheet of sheets) {
    const bookSheet = bookSheets.find((entry) => entry.getAttribute("name") === sheet.name);
    const id = bookSheet?.getAttribute("r:id") ?? bookSheet?.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
    const path = id && relationshipPaths.get(id);
    const document = path && xml(files[path]);
    if (!document) continue;
    const format: PreviewSheetFormat = { cells: {}, merges: {}, columnWidths: {}, rowHeights: {} };
    const sheetIndex = bookSheets.indexOf(bookSheet!);
    const printTitles = Array.from(workbook.getElementsByTagName("definedName"))
      .find((entry) => entry.getAttribute("name") === "_xlnm.Print_Titles" && entry.getAttribute("localSheetId") === String(sheetIndex));
    const repeatedRows = /!\$(\d+):\$(\d+)(?:,|$)/.exec(printTitles?.textContent ?? "");
    if (repeatedRows && repeatedRows[1] === repeatedRows[2]) {
      const row = Number(repeatedRows[1]) - 1;
      if (Number.isInteger(row) && row >= 0 && row < sheet.rows.length) format.repeatingHeaderRow = row;
    }
    const pageSetup = document.getElementsByTagName("pageSetup")[0];
    const orientation = pageSetup?.getAttribute("orientation");
    if (orientation === "portrait" || orientation === "landscape") format.orientation = orientation;
    const paperSizes: Record<string, PreviewSheetFormat["paperSize"]> = { "9": "A4", "8": "A3", "66": "A2" };
    format.paperSize = paperSizes[pageSetup?.getAttribute("paperSize") ?? ""];
    for (const row of Array.from(document.getElementsByTagName("row"))) {
      const rowNumber = positiveNumber(row.getAttribute("r"), 1, sheet.rows.length);
      const height = positiveNumber(row.getAttribute("ht"), 8, 100);
      if (rowNumber && height) format.rowHeights[rowNumber - 1] = Math.round(height * 4 / 3);
    }
    for (const col of Array.from(document.getElementsByTagName("col"))) {
      const min = positiveNumber(col.getAttribute("min"), 1, 256);
      const max = positiveNumber(col.getAttribute("max"), 1, 256);
      const width = positiveNumber(col.getAttribute("width"), 3, 80);
      if (min && max && width && max >= min) for (let index = min - 1; index < max; index++) format.columnWidths[index] = Math.round(width * 7 + 5);
    }
    for (const cell of Array.from(document.getElementsByTagName("c"))) {
      const coordinate = cellIndex(cell.getAttribute("r") ?? "");
      const style = parsedStyles[Number(cell.getAttribute("s"))];
      if (coordinate && style && coordinate.row < sheet.rows.length && coordinate.column < 256 && Object.keys(style).length) {
        format.cells[`${coordinate.row}:${coordinate.column}`] = style;
      }
    }
    for (const merge of Array.from(document.getElementsByTagName("mergeCell"))) {
      const [from, to] = (merge.getAttribute("ref") ?? "").split(":").map(cellIndex);
      if (from && to && from.row === to.row && from.row < sheet.rows.length && to.column > from.column && to.column < 256
        && sheet.rows[from.row].slice(from.column + 1, to.column + 1).every((value) => value === null || value === "" || value === undefined)) {
        format.merges[`${from.row}:${from.column}`] = to.column - from.column + 1;
      }
    }
    sheet.format = format;
  }
}
