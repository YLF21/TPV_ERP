import { beforeEach, describe, expect, it, vi } from "vitest";
import { excelSheetToTable, readExcelTable, type ExcelSheet } from "../../../packages/app-common/src/components/excelImport";
import { importStockCount, type StockCountImportProduct } from "./stockCountImport";

vi.mock("../../../packages/app-common/src/components/excelImport", async (importOriginal) => ({
  ...await importOriginal<typeof import("../../../packages/app-common/src/components/excelImport")>(),
  readExcelTable: vi.fn()
}));

const file = new File(["xlsx"], "recuento.xlsx");
const products: StockCountImportProduct[] = [
  { id: "zero", code: "0", barcode: "8400", name: "Producto cero", productType: "UNIT", active: true },
  { id: "weight", code: "PESO", barcode: "8411", name: "Café en grano", productType: "WEIGHT", active: true },
  { id: "service", code: "SERV", name: "Servicio", productType: "SERVICE", active: true },
  { id: "inactive", code: "BAJA", name: "Inactivo", productType: "UNIT", active: false }
];

function sheet(rows: ExcelSheet) {
  vi.mocked(readExcelTable).mockResolvedValue(excelSheetToTable(rows));
}

describe("importStockCount", () => {
  beforeEach(() => vi.resetAllMocks());

  it("matches exact code, barcode and unique name and preserves blank versus zero quantities", async () => {
    sheet([
      ["Código", "Código de barras", "Nombre", "Cantidad contada"],
      ["0", "", "", 0],
      ["", "8411", "", "1,125"],
      ["", "", "PRODUCTO CERO", ""],
      ["", "", "", ""]
    ]);
    const result = await importStockCount(file, products);
    expect(result).toEqual({
      lines: [],
      errors: ["Fila 4: el producto ya aparece en otra fila."]
    });

    sheet([
      ["编码", "条码", "名称", "盘点数量"],
      ["0", "", "", 0],
      ["", "8411", "", "1,125"]
    ]);
    expect(await importStockCount(file, products)).toEqual({
      lines: [
        { productId: "zero", countedQuantity: 0 },
        { productId: "weight", countedQuantity: 1.125 }
      ],
      errors: []
    });

    sheet([["Nombre", "Cantidad"], ["café en grano", ""]]);
    expect(await importStockCount(file, products)).toEqual({
      lines: [{ productId: "weight", countedQuantity: null }], errors: []
    });
  });

  it("rejects invalid precision, negative and fractional unit counts without partial lines", async () => {
    sheet([
      ["code", "countedQuantity"],
      ["0", "1.5"],
      ["PESO", "-1"],
      ["PESO", "1.0001"]
    ]);
    const result = await importStockCount(file, products);
    expect(result.lines).toEqual([]);
    expect(result.errors).toHaveLength(3);
    expect(result.errors.join(" ")).toMatch(/Fila 2.*Fila 3.*Fila 4/);
  });

  it("rejects unknown, conflicting, ambiguous, inactive and service products", async () => {
    sheet([
      ["Código", "Código de barras", "Cantidad"],
      ["NOPE", "", 2],
      ["0", "8411", 2],
      ["SERV", "", 2],
      ["BAJA", "", 2]
    ]);
    const result = await importStockCount(file, products);
    expect(result.lines).toEqual([]);
    expect(result.errors).toHaveLength(4);
    expect(result.errors.join(" ")).toMatch(/no se encuentra el código.*productos distintos.*servicio.*inactivo/);

    sheet([["code", "quantity"], ["0", 1]]);
    const ambiguous = await importStockCount(file, [...products, { id: "other", code: "0" }]);
    expect(ambiguous.lines).toEqual([]);
    expect(ambiguous.errors[0]).toContain("ambiguo");
  });

  it("requires unambiguous columns and at least one product row", async () => {
    sheet([["Código", "Code", "Nombre", "Cantidad"]]);
    expect((await importStockCount(file, products)).errors).toContain("Hay varias columnas para Código.");

    sheet([["Nombre", "Cantidad"]]);
    expect(await importStockCount(file, products)).toEqual({
      lines: [], errors: ["El Excel no contiene filas de productos."]
    });
  });

  it("localizes row and header errors in English and Chinese", async () => {
    sheet([["code", "quantity"], ["MISSING", 1]]);
    expect((await importStockCount(file, products, "en")).errors).toEqual([
      'Row 2: Code "MISSING" was not found.'
    ]);
    expect((await importStockCount(file, products, "zh")).errors).toEqual([
      '第 2 行：找不到编码“MISSING”。'
    ]);

    sheet([["Name"]]);
    expect((await importStockCount(file, products, "en")).errors).toEqual([
      "The Excel file must include Counted quantity."
    ]);
  });
});
