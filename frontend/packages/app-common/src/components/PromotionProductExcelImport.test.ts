import { describe, expect, it } from "vitest";
import readXlsxFile from "read-excel-file/node";
import { fileURLToPath } from "node:url";
import type { ProductExcelImportReadResult } from "../api/productExcelImports";
import { classifyPromotionExcelRows, detectPromotionExcelMapping } from "./PromotionProductExcelImport";

function read(rows: Array<Array<string | null>>): ProductExcelImportReadResult {
  return {
    fileName: "promotion.xlsx",
    sha256: "sha",
    sheetName: "Sheet1",
    formulas: [],
    rows: rows.map((row) => row.map((value) => ({ value }))),
    nonEmptyRows: rows.length,
    columns: Math.max(...rows.map((row) => row.length)),
    nonEmptyCells: rows.flat().filter(Boolean).length
  };
}

describe("promotion product Excel selection", () => {
  it("detects a header and preserves text identifiers with leading zeroes", () => {
    const workbook = read([
      ["Código", "EAN", "Precio"],
      ["00042", null, "99"],
      [null, "0012345678901", null]
    ]);
    const mapping = detectPromotionExcelMapping(workbook);
    expect(mapping).toEqual({ code: 0, barcode: 1, startRow: 2 });
    expect(
      classifyPromotionExcelRows(workbook, mapping, [
        { id: "a", code: "00042" },
        { id: "b", barcode: "0012345678901" }
      ])
    ).toMatchObject([
      { status: "ready", product: { id: "a" } },
      { status: "ready", product: { id: "b" } }
    ]);
  });

  it("reports missing and ambiguous identifiers, and skips duplicates and selected products", () => {
    const workbook = read([
      ["Code", "Barcode"],
      ["A", null],
      ["A", null],
      ["B", null],
      ["Z", null],
      ["A", "B"],
      ["S", null]
    ]);
    const rows = classifyPromotionExcelRows(
      workbook,
      detectPromotionExcelMapping(workbook),
      [
        { id: "a", code: "A" },
        { id: "b", code: "B" },
        { id: "b2", barcode: "B" },
        { id: "s", code: "S" }
      ],
      ["s"]
    );
    expect(rows.map((row) => row.status)).toEqual([
      "ready",
      "duplicate",
      "ambiguous",
      "missing",
      "ambiguous",
      "selected"
    ]);
    expect(rows[0].product?.id).toBe("a");
  });

  it("does not treat a numeric cell as a zero-padded text identifier", () => {
    const workbook = read([["123"]]);
    expect(
      classifyPromotionExcelRows(workbook, detectPromotionExcelMapping(workbook), [{ id: "a", code: "00123" }])[0]
        .status
    ).toBe("missing");
  });

  it("keeps formulas with cell errors out of the selection", () => {
    const workbook = read([["Code"], [null]]);
    workbook.rows[1][0].errorCode = "FORMULA_ERROR";
    expect(classifyPromotionExcelRows(workbook, detectPromotionExcelMapping(workbook), [])[0].status).toBe(
      "invalidCell"
    );
  });

  it("reports rows with other data but no identifier, mismatched identifiers and inactive products", () => {
    const workbook = read([
      ["Code", "Barcode", "Name"],
      [null, null, "Unnamed"],
      ["A", "other", "Conflict"],
      ["I", null, "Inactive"]
    ]);
    const rows = classifyPromotionExcelRows(workbook, detectPromotionExcelMapping(workbook), [
      { id: "a", code: "A" },
      { id: "i", code: "I", active: false }
    ]);
    expect(rows.map((row) => row.status)).toEqual(["identifierRequired", "identifierMismatch", "inactive"]);
  });

  it("maps the repository's real XLSX sample without requiring its price columns", async () => {
    const path = fileURLToPath(
      new URL("../../../../../frontend-product/public/guides/espos-product-import-sample.xlsx", import.meta.url)
    );
    const sheets = (await readXlsxFile(path)) as unknown as Array<{ sheet: string; data: unknown[][] }>;
    const data = sheets.find((sheet) => sheet.sheet === "Datos")?.data;
    expect(data).toBeDefined();
    const workbook = read(data!.map((row) => row.map((value) => (value == null ? null : String(value)))));
    const products = [{ id: "sample", code: "DEMO-IMPORT-001" }];
    expect(
      classifyPromotionExcelRows(workbook, detectPromotionExcelMapping(workbook), products).map((row) => row.status)
    ).toEqual(["ready", "missing", "missing"]);
  });
});
