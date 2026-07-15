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
  readExcelTable
} from "./excelImport";

vi.mock("read-excel-file/browser", () => ({
  readSheet: vi.fn()
}));

const readSheetMock = vi.mocked(readSheet);

describe("excelImport", () => {
  it("defines a shared accept list for Excel import controls", () => {
    expect(excelImportAccept).toBe(".xlsx,.xls,.csv");
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
