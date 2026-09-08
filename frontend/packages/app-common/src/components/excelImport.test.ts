import { describe, expect, it, vi } from "vitest";
import { readSheet } from "read-excel-file/browser";
import {
  excelCellText,
  buildExcelImportDraft,
  classifyExcelProductRows,
  excelColumnIndexToLetter,
  excelColumnLetterToIndex,
  excelImportAccept,
  excelPriceText,
  excelSheetToTable,
  findExcelColumn,
  findExcelColumns,
  normalizeExcelHeader,
  parseExcelDate,
  readExcelTable,
  excelImportLimits
} from "./excelImport";

vi.mock("read-excel-file/browser", () => ({
  readSheet: vi.fn()
}));

const readSheetMock = vi.mocked(readSheet);

describe("excelImport", () => {
  it("defines a shared accept list for Excel import controls", () => {
    expect(excelImportAccept).toBe(".xlsx,.xls");
  });

  it("normalizes headers and exposes rows as objects", () => {
    const table = excelSheetToTable([
      [" Código ", "Fecha", "Importe"],
      ["A001", new Date(2026, 6, 14), 12.5]
    ]);

    expect(table.headers).toEqual(["Código", "Fecha", "Importe"]);
    expect(table.normalizedHeaders).toEqual(["codigo", "fecha", "importe"]);
    expect(table.rows).toEqual([{ "Código": "A001", Fecha: new Date(2026, 6, 14), Importe: 12.5 }]);
  });

  it("finds columns by localized aliases", () => {
    const headers = ["Código", "Codigo de barras", "Product ID"];

    expect(findExcelColumn(headers, ["codigo", "code"])).toBe(0);
    expect(findExcelColumns(headers, ["codigo de barras", "barcode"])).toEqual([1]);
    expect(findExcelColumn(headers, ["missing"])).toBe(-1);
  });

  it("formats date cells with ISO date text", () => {
    expect(excelCellText(new Date(2026, 6, 14))).toBe("2026-07-14");
    expect(excelCellText(null)).toBe("");
    expect(normalizeExcelHeader(" Código  de   barras ")).toBe("codigo de barras");
  });

  it("normalizes Spanish Excel date formats and rejects impossible dates", () => {
    expect(parseExcelDate("05-09-26")).toBe("2026-09-05");
    expect(parseExcelDate("05-09-2026")).toBe("2026-09-05");
    expect(parseExcelDate("2026-09-05")).toBe("2026-09-05");
    expect(parseExcelDate("29-02-2028")).toBe("2028-02-29");
    expect(parseExcelDate("31-04-2026")).toBeNull();
    expect(parseExcelDate("1-1-24")).toBeNull();
    expect(parseExcelDate("2026-9-5")).toBeNull();
  });

  it("converts between Excel column letters and indexes", () => {
    expect(excelColumnLetterToIndex("A")).toBe(0);
    expect(excelColumnLetterToIndex("Z")).toBe(25);
    expect(excelColumnLetterToIndex("AA")).toBe(26);
    expect(excelColumnLetterToIndex("")).toBe(-1);
    expect(excelColumnIndexToLetter(0)).toBe("A");
    expect(excelColumnIndexToLetter(26)).toBe("AA");
  });

  it("builds product drafts from column letters with price defaults", () => {
    expect(buildExcelImportDraft(
      ["A001", "843", "Articulo", "", "2.50"],
      { code: "A", barcode: "B", name: "C", purchasePrice: "D", salePrice: "E" }
    )).toEqual(expect.objectContaining({
      code: "A001",
      barcode: "843",
      name: "Articulo",
      purchasePrice: "0",
      salePrice: "2.50"
    }));
    expect(excelPriceText("")).toBe("0");
  });

  it("classifies rows only by code or barcode", () => {
    const products = [
      { id: "product-1", code: "A001", barcode: "843" },
      { id: "product-2", code: "B002", barcode: "844" }
    ];
    const rows = classifyExcelProductRows(
      [
        ["Codigo", "Barcode", "Nombre", "Compra", "Venta"],
        ["A001", "", "Nombre distinto", "1.00", "2.00"],
        ["", "844", "Otro nombre", "3.50", "4.00"],
        ["", "", "Sin codigo", "1", "2"],
        ["NOPE", "", "Nuevo", "", ""]
      ],
      { code: "A", barcode: "B", name: "C", purchasePrice: "D", salePrice: "E" },
      products,
      (product) => product.id === "product-2" ? "3.00" : "1.00"
    );

    expect(rows).toEqual([
      expect.objectContaining({ rowNumber: 2, product: products[0], status: "accepted" }),
      expect.objectContaining({ rowNumber: 3, product: products[1], status: "purchasePriceChanged" }),
      expect.objectContaining({ rowNumber: 4, product: undefined, status: "error", errors: ["identifierRequired"] }),
      expect.objectContaining({
        rowNumber: 5,
        product: undefined,
        status: "missing",
        draft: expect.objectContaining({ purchasePrice: "0", salePrice: "0" })
      })
    ]);
  });

  it("detects only identity rows, accepts existing products without a name, and requires name for new products", () => {
    const rows = classifyExcelProductRows(
      [["Código", "Nombre", "Precio"], ["", "", "5"], ["A001", "", "5"], ["NEW", "", "5"], ["", "Solo nombre", "5"]],
      { code: "A", name: "B", salePrice: "C" },
      [{ id: "p1", code: "A001" }]
    );
    expect(rows).toHaveLength(3);
    expect(rows[0]?.rowNumber).toBe(3);
    expect(rows[0]?.status).toBe("accepted");
    expect(rows[1]?.errors).toContain("nameRequired");
    expect(rows[2]?.errors).toContain("identifierRequired");
  });

  it("reports a visible blocking row when detected-row limit is exceeded", () => {
    const rows = classifyExcelProductRows(
      [["Código"], ...Array.from({ length: excelImportLimits.maxDetectedRows + 1 }, (_, i) => [`P${i}`])],
      { code: "A" },
      []
    );
    expect(rows).toHaveLength(excelImportLimits.maxDetectedRows + 1);
    expect(rows.at(-1)).toEqual(expect.objectContaining({ status: "error", errors: ["tooManyDetectedRows"] }));
  });

  it("reads a file through the common table adapter", async () => {
    readSheetMock.mockResolvedValueOnce([
      ["Codigo", "Cantidad"],
      ["A001", 2]
    ] as never);

    await expect(readExcelTable(new File(["xlsx"], "import.xlsx"))).resolves.toMatchObject({
      headers: ["Codigo", "Cantidad"],
      normalizedHeaders: ["codigo", "cantidad"],
      rows: [{ Codigo: "A001", Cantidad: 2 }]
    });
  });
});
