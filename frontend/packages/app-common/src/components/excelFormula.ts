import { unzip } from "fflate";

export type ExcelFormulaCell = {
  kind: "formula";
  formula: string;
  value: unknown;
  calculationError?: string;
};

export type ExcelFormulaMetadata = {
  cell: string;
  formula: string;
  calculatedValue: string;
};

type FormulaSheet = unknown[][];

type SharedFormula = {
  formula: string;
  originReference: string;
};

export function isExcelFormulaCell(value: unknown): value is ExcelFormulaCell {
  return Boolean(
    value
    && typeof value === "object"
    && (value as Partial<ExcelFormulaCell>).kind === "formula"
    && typeof (value as Partial<ExcelFormulaCell>).formula === "string"
  );
}

export function excelFormulaCellText(value: unknown) {
  return isExcelFormulaCell(value) ? value.value : value;
}

export function excelFormulaEditorText(value: unknown) {
  return isExcelFormulaCell(value) ? `=${value.formula}` : value;
}

export function countExcelFormulaCells(sheet: FormulaSheet) {
  return sheet.reduce(
    (count, row) => count + row.filter(isExcelFormulaCell).length,
    0
  );
}

export function extractExcelFormulaMetadata(sheet: FormulaSheet): ExcelFormulaMetadata[] {
  const formulas: ExcelFormulaMetadata[] = [];
  sheet.forEach((row, rowIndex) => {
    row.forEach((cell, columnIndex) => {
      if (!isExcelFormulaCell(cell)) {
        return;
      }
      formulas.push({
        cell: `${formulaColumnLetter(columnIndex)}${rowIndex + 1}`,
        formula: cell.formula,
        calculatedValue: cell.value == null ? "" : String(cell.value)
      });
    });
  });
  return formulas;
}

function formulaColumnLetter(index: number) {
  let result = "";
  let current = index + 1;
  while (current > 0) {
    const remainder = (current - 1) % 26;
    result = String.fromCharCode(65 + remainder) + result;
    current = Math.floor((current - 1) / 26);
  }
  return result;
}

export async function overlayExcelFormulas(file: File, values: FormulaSheet): Promise<FormulaSheet> {
  if (!file.name.toLowerCase().endsWith(".xlsx")) {
    return captureTextFormulas(values);
  }
  try {
    const archive = await unzipFile(file);
    const sheetPath = firstWorksheetPath(archive);
    const sheetXml = sheetPath ? archive[sheetPath] : undefined;
    if (!sheetXml) {
      return values;
    }
    const formulas = parseWorksheetFormulas(decodeXmlFile(sheetXml));
    if (formulas.size === 0) {
      return values;
    }
    const nextSheet = values.map((row) => [...row]);
    formulas.forEach((formula, reference) => {
      const coordinates = parseCellReference(reference);
      if (!coordinates) {
        return;
      }
      const [rowIndex, columnIndex] = coordinates;
      while (nextSheet.length <= rowIndex) {
        nextSheet.push([]);
      }
      while (nextSheet[rowIndex].length <= columnIndex) {
        nextSheet[rowIndex].push("");
      }
      nextSheet[rowIndex][columnIndex] = {
        kind: "formula",
        formula,
        value: nextSheet[rowIndex][columnIndex]
      } satisfies ExcelFormulaCell;
    });
    return recalculateExcelFormulas(nextSheet);
  } catch {
    // The ordinary value reader remains the safe fallback for malformed,
    // password-protected or legacy Excel files.
    return values;
  }
}

export function captureTextFormulas(sheet: FormulaSheet): FormulaSheet {
  const nextSheet = sheet.map((row) => row.map((cell) => (
    typeof cell === "string" && cell.trim().startsWith("=")
      ? { kind: "formula", formula: cell.trim().slice(1), value: "" } satisfies ExcelFormulaCell
      : cell
  )));
  return recalculateExcelFormulas(nextSheet);
}

export function recalculateExcelFormulas(sheet: FormulaSheet): FormulaSheet {
  const nextSheet = sheet.map((row) => row.map((cell) => (
    isExcelFormulaCell(cell) ? { ...cell, calculationError: undefined } : cell
  )));
  const cache = new Map<string, number>();
  const visiting = new Set<string>();

  const resolveReference = (reference: string): number => {
    const normalizedReference = normalizeReference(reference);
    const cached = cache.get(normalizedReference);
    if (cached !== undefined) {
      return cached;
    }
    if (visiting.has(normalizedReference)) {
      throw new Error("Referencia circular");
    }
    const coordinates = parseCellReference(normalizedReference);
    if (!coordinates) {
      throw new Error(`Referencia no válida: ${reference}`);
    }
    const [rowIndex, columnIndex] = coordinates;
    const cell = nextSheet[rowIndex]?.[columnIndex];
    visiting.add(normalizedReference);
    let numericValue: number;
    if (isExcelFormulaCell(cell)) {
      numericValue = evaluateExcelFormula(cell.formula, {
        resolveReference,
        resolveRange
      });
      cell.value = numericValue;
    } else {
      numericValue = numericCellValue(cell);
    }
    visiting.delete(normalizedReference);
    cache.set(normalizedReference, numericValue);
    return numericValue;
  };

  const resolveRange = (start: string, end: string): number[] => {
    const startCoordinates = parseCellReference(start);
    const endCoordinates = parseCellReference(end);
    if (!startCoordinates || !endCoordinates) {
      throw new Error("Rango no válido");
    }
    const values: number[] = [];
    for (
      let rowIndex = Math.min(startCoordinates[0], endCoordinates[0]);
      rowIndex <= Math.max(startCoordinates[0], endCoordinates[0]);
      rowIndex += 1
    ) {
      for (
        let columnIndex = Math.min(startCoordinates[1], endCoordinates[1]);
        columnIndex <= Math.max(startCoordinates[1], endCoordinates[1]);
        columnIndex += 1
      ) {
        values.push(resolveReference(`${columnIndexToLetter(columnIndex)}${rowIndex + 1}`));
      }
    }
    return values;
  };

  nextSheet.forEach((row, rowIndex) => {
    row.forEach((cell, columnIndex) => {
      if (!isExcelFormulaCell(cell)) {
        return;
      }
      const reference = `${columnIndexToLetter(columnIndex)}${rowIndex + 1}`;
      try {
        resolveReference(reference);
      } catch (error) {
        cell.calculationError = error instanceof Error ? error.message : "Fórmula no compatible";
      }
    });
  });
  return nextSheet;
}

export function evaluateExcelFormula(
  formula: string,
  references: {
    resolveReference: (reference: string) => number;
    resolveRange: (start: string, end: string) => number[];
  }
) {
  const parser = new FormulaParser(formula, references);
  return parser.parse();
}

class FormulaParser {
  private readonly tokens: string[];
  private index = 0;

  constructor(
    formula: string,
    private readonly references: {
      resolveReference: (reference: string) => number;
      resolveRange: (start: string, end: string) => number[];
    }
  ) {
    this.tokens = tokenizeFormula(formula.replace(/^=/, ""));
  }

  parse() {
    const value = this.parseAdditive();
    if (this.peek()) {
      throw new Error(`Elemento no compatible: ${this.peek()}`);
    }
    return finiteNumber(value);
  }

  private parseAdditive(): number {
    let value = this.parseMultiplicative();
    while (this.peek() === "+" || this.peek() === "-") {
      const operator = this.take();
      const right = this.parseMultiplicative();
      value = operator === "+" ? value + right : value - right;
    }
    return value;
  }

  private parseMultiplicative(): number {
    let value = this.parsePower();
    while (this.peek() === "*" || this.peek() === "/") {
      const operator = this.take();
      const right = this.parsePower();
      value = operator === "*" ? value * right : value / right;
    }
    return finiteNumber(value);
  }

  private parsePower(): number {
    const value = this.parseUnary();
    if (this.peek() === "^") {
      this.take();
      return finiteNumber(value ** this.parsePower());
    }
    return value;
  }

  private parseUnary(): number {
    if (this.peek() === "+") {
      this.take();
      return this.parseUnary();
    }
    if (this.peek() === "-") {
      this.take();
      return -this.parseUnary();
    }
    let value = this.parsePrimaryNumber();
    while (this.peek() === "%") {
      this.take();
      value /= 100;
    }
    return value;
  }

  private parsePrimaryNumber(): number {
    const value = this.parsePrimary();
    if (Array.isArray(value)) {
      throw new Error("El rango debe utilizarse dentro de una función");
    }
    return finiteNumber(value);
  }

  private parsePrimary(): number | number[] {
    const token = this.take();
    if (!token) {
      throw new Error("Fórmula incompleta");
    }
    if (token === "(") {
      const value = this.parseAdditive();
      this.expect(")");
      return value;
    }
    if (isNumericToken(token)) {
      return Number(token);
    }
    if (isCellReference(token)) {
      if (this.peek() === ":") {
        this.take();
        const end = this.take();
        if (!end || !isCellReference(end)) {
          throw new Error("Rango no válido");
        }
        return this.references.resolveRange(token, end);
      }
      return this.references.resolveReference(token);
    }
    if (isIdentifier(token) && this.peek() === "(") {
      this.take();
      const args: Array<number | number[]> = [];
      if (this.peek() !== ")") {
        do {
          args.push(this.parsePrimaryArgument());
          if (this.peek() !== "," && this.peek() !== ";") {
            break;
          }
          this.take();
        } while (this.peek() !== ")");
      }
      this.expect(")");
      return calculateFunction(token, args);
    }
    throw new Error(`Elemento no compatible: ${token}`);
  }

  private parsePrimaryArgument(): number | number[] {
    const next = this.peek();
    if (next && (isCellReference(next) || isIdentifier(next))) {
      const following = this.tokens[this.index + 1];
      if (following === ":" || following === "(") {
        return this.parsePrimary();
      }
    }
    return this.parseAdditive();
  }

  private peek() {
    return this.tokens[this.index];
  }

  private take() {
    const token = this.tokens[this.index];
    this.index += 1;
    return token;
  }

  private expect(expected: string) {
    const actual = this.take();
    if (actual !== expected) {
      throw new Error(`Se esperaba ${expected}`);
    }
  }
}

function calculateFunction(name: string, args: Array<number | number[]>) {
  const normalizedName = name.toUpperCase().replace(/^_XLFN\./, "");
  const flattened = args.flatMap((value) => Array.isArray(value) ? value : [value]);
  switch (normalizedName) {
    case "SUM":
      return flattened.reduce((total, value) => total + value, 0);
    case "AVERAGE":
    case "PROMEDIO":
      return flattened.length === 0
        ? 0
        : flattened.reduce((total, value) => total + value, 0) / flattened.length;
    case "MIN":
      return flattened.length === 0 ? 0 : Math.min(...flattened);
    case "MAX":
      return flattened.length === 0 ? 0 : Math.max(...flattened);
    case "ABS":
      return Math.abs(finiteNumber(args[0]));
    case "ROUND":
    case "REDONDEAR": {
      const value = finiteNumber(args[0]);
      const decimals = finiteNumber(args[1] ?? 0);
      const factor = 10 ** decimals;
      return Math.round((value + Number.EPSILON) * factor) / factor;
    }
    case "ROUNDDOWN":
      return roundDirectional(args, Math.trunc);
    case "ROUNDUP":
      return roundDirectional(args, (value) => value < 0 ? Math.floor(value) : Math.ceil(value));
    default:
      throw new Error(`Función no compatible: ${name}`);
  }
}

function roundDirectional(args: Array<number | number[]>, operation: (value: number) => number) {
  const value = finiteNumber(args[0]);
  const decimals = finiteNumber(args[1] ?? 0);
  const factor = 10 ** decimals;
  return operation(value * factor) / factor;
}

function tokenizeFormula(formula: string) {
  const tokens: string[] = [];
  let remaining = formula.trim();
  while (remaining) {
    const whitespace = remaining.match(/^\s+/);
    if (whitespace) {
      remaining = remaining.slice(whitespace[0].length);
      continue;
    }
    const match = remaining.match(
      /^(?:\$?[A-Za-z]{1,3}\$?\d+|(?:\d+(?:\.\d*)?|\.\d+)|_?[A-Za-z][A-Za-z0-9_.]*|[+\-*/^(),;:%])/
    );
    if (!match) {
      throw new Error(`Sintaxis no compatible: ${remaining.slice(0, 12)}`);
    }
    tokens.push(match[0]);
    remaining = remaining.slice(match[0].length);
  }
  return tokens;
}

function finiteNumber(value: number | number[] | undefined): number {
  if (Array.isArray(value) || value === undefined || !Number.isFinite(value)) {
    throw new Error("Resultado numérico no válido");
  }
  return value;
}

function numericCellValue(value: unknown) {
  const unwrapped = excelFormulaCellText(value);
  if (unwrapped === null || unwrapped === undefined || unwrapped === "") {
    return 0;
  }
  const numericValue = Number(String(unwrapped).replace(",", "."));
  if (!Number.isFinite(numericValue)) {
    throw new Error("La referencia no contiene un número");
  }
  return numericValue;
}

function isNumericToken(token: string) {
  return /^(?:\d+(?:\.\d*)?|\.\d+)$/.test(token);
}

function isIdentifier(token: string) {
  return /^_?[A-Za-z][A-Za-z0-9_.]*$/.test(token);
}

function isCellReference(reference: string) {
  return /^\$?[A-Za-z]{1,3}\$?\d+$/.test(reference);
}

function normalizeReference(reference: string) {
  return reference.replaceAll("$", "").toUpperCase();
}

function parseCellReference(reference: string): [number, number] | null {
  const match = normalizeReference(reference).match(/^([A-Z]{1,3})(\d+)$/);
  if (!match) {
    return null;
  }
  let columnIndex = 0;
  for (const character of match[1]) {
    columnIndex = columnIndex * 26 + character.charCodeAt(0) - 64;
  }
  return [Number(match[2]) - 1, columnIndex - 1];
}

function columnIndexToLetter(index: number) {
  let value = index + 1;
  let letter = "";
  while (value > 0) {
    const remainder = (value - 1) % 26;
    letter = String.fromCharCode(65 + remainder) + letter;
    value = Math.floor((value - 1) / 26);
  }
  return letter;
}

function unzipFile(file: File): Promise<Record<string, Uint8Array>> {
  return file.arrayBuffer().then((buffer) => new Promise((resolve, reject) => {
    unzip(new Uint8Array(buffer), (error, archive) => {
      if (error) {
        reject(error);
      } else {
        resolve(archive);
      }
    });
  }));
}

function firstWorksheetPath(archive: Record<string, Uint8Array>) {
  const workbook = archive["xl/workbook.xml"];
  const relationships = archive["xl/_rels/workbook.xml.rels"];
  if (!workbook || !relationships) {
    return archive["xl/worksheets/sheet1.xml"] ? "xl/worksheets/sheet1.xml" : undefined;
  }
  const workbookXml = decodeXmlFile(workbook);
  const relationshipXml = decodeXmlFile(relationships);
  const firstSheetTag = workbookXml.match(/<sheet\b[^>]*\/?>/i)?.[0];
  const relationshipId = firstSheetTag
    ? readXmlAttribute(firstSheetTag, "r:id") ?? readXmlAttribute(firstSheetTag, "id")
    : undefined;
  if (!relationshipId) {
    return "xl/worksheets/sheet1.xml";
  }
  const relationshipTags = relationshipXml.match(/<Relationship\b[^>]*\/?>/gi) ?? [];
  const relationshipTag = relationshipTags.find(
    (tag) => readXmlAttribute(tag, "Id") === relationshipId
  );
  const target = relationshipTag ? readXmlAttribute(relationshipTag, "Target") : undefined;
  if (!target) {
    return "xl/worksheets/sheet1.xml";
  }
  return normalizeArchivePath(target.startsWith("/") ? target.slice(1) : `xl/${target}`);
}

function parseWorksheetFormulas(xml: string) {
  const formulas = new Map<string, string>();
  const sharedFormulas = new Map<string, SharedFormula>();
  const cellPattern = /<c\b([^>]*)>([\s\S]*?)<\/c>/gi;
  for (const match of xml.matchAll(cellPattern)) {
    const reference = readXmlAttribute(match[1], "r");
    if (!reference) {
      continue;
    }
    const formulaMatch = match[2].match(/<f\b([^>]*)>([\s\S]*?)<\/f>|<f\b([^>]*)\/>/i);
    if (!formulaMatch) {
      continue;
    }
    const attributes = formulaMatch[1] ?? formulaMatch[3] ?? "";
    const sharedIndex = readXmlAttribute(attributes, "si");
    const formulaText = decodeXmlEntities(formulaMatch[2] ?? "").trim();
    if (formulaText) {
      formulas.set(reference, formulaText);
      if (sharedIndex) {
        sharedFormulas.set(sharedIndex, { formula: formulaText, originReference: reference });
      }
    } else if (sharedIndex) {
      const shared = sharedFormulas.get(sharedIndex);
      if (shared) {
        formulas.set(
          reference,
          translateSharedFormula(shared.formula, shared.originReference, reference)
        );
      }
    }
  }
  return formulas;
}

function translateSharedFormula(formula: string, originReference: string, targetReference: string) {
  const origin = parseCellReference(originReference);
  const target = parseCellReference(targetReference);
  if (!origin || !target) {
    return formula;
  }
  const rowDelta = target[0] - origin[0];
  const columnDelta = target[1] - origin[1];
  return formula.replace(
    /(\$?)([A-Z]{1,3})(\$?)(\d+)/gi,
    (_, fixedColumn: string, column: string, fixedRow: string, row: string) => {
      const coordinates = parseCellReference(`${column}${row}`);
      if (!coordinates) {
        return `${fixedColumn}${column}${fixedRow}${row}`;
      }
      const nextRow = fixedRow ? coordinates[0] : coordinates[0] + rowDelta;
      const nextColumn = fixedColumn ? coordinates[1] : coordinates[1] + columnDelta;
      return `${fixedColumn}${columnIndexToLetter(nextColumn)}${fixedRow}${nextRow + 1}`;
    }
  );
}

function normalizeArchivePath(path: string) {
  const parts: string[] = [];
  path.replaceAll("\\", "/").split("/").forEach((part) => {
    if (!part || part === ".") {
      return;
    }
    if (part === "..") {
      parts.pop();
    } else {
      parts.push(part);
    }
  });
  return parts.join("/");
}

function readXmlAttribute(source: string, name: string) {
  const escapedName = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return source.match(new RegExp(`\\b${escapedName}\\s*=\\s*["']([^"']*)["']`, "i"))?.[1];
}

function decodeXmlFile(value: Uint8Array) {
  return new TextDecoder("utf-8").decode(value);
}

function decodeXmlEntities(value: string) {
  return value
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&quot;", "\"")
    .replaceAll("&apos;", "'")
    .replaceAll("&amp;", "&")
    .replace(/&#(\d+);/g, (_, code: string) => String.fromCodePoint(Number(code)))
    .replace(/&#x([0-9a-f]+);/gi, (_, code: string) => String.fromCodePoint(Number.parseInt(code, 16)));
}
