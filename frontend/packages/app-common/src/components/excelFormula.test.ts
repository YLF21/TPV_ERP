import { zipSync, strToU8 } from "fflate";
import { describe, expect, it } from "vitest";
import {
  countExcelFormulaCells,
  evaluateExcelFormula,
  extractExcelFormulaMetadata,
  isExcelFormulaCell,
  overlayExcelFormulas,
  recalculateExcelFormulas
} from "./excelFormula";

describe("excelFormula", () => {
  it("calculates arithmetic, cell references, ranges and common functions", () => {
    const values: Record<string, number> = { A1: 4.1, A2: 2, A3: 3 };
    const references = {
      resolveReference: (reference: string) => values[reference.replaceAll("$", "").toUpperCase()] ?? 0,
      resolveRange: (start: string, end: string) => start === "A2" && end === "A3" ? [2, 3] : []
    };

    expect(evaluateExcelFormula("A1*2.5", references)).toBeCloseTo(10.25);
    expect(evaluateExcelFormula("ROUND(A1*2.5, 2)", references)).toBeCloseTo(10.25);
    expect(evaluateExcelFormula("SUM(A2:A3)+A1", references)).toBeCloseTo(9.1);
  });

  it("recalculates dependent formulas and retains the cached value for unsupported formulas", () => {
    const sheet = recalculateExcelFormulas([
      [4.1, { kind: "formula", formula: "A1*2.5", value: 0 }],
      [2, { kind: "formula", formula: "UNSUPPORTED(A2)", value: 99 }]
    ]);

    expect(sheet[0][1]).toEqual(expect.objectContaining({ value: 10.25 }));
    expect(sheet[1][1]).toEqual(expect.objectContaining({
      value: 99,
      calculationError: expect.stringContaining("Función no compatible")
    }));
  });

  it("extracts normal and shared formulas from the first XLSX worksheet", async () => {
    const archive = zipSync({
      "xl/workbook.xml": strToU8(
        '<workbook><sheets><sheet name="Hoja1" sheetId="1" r:id="rId1"/></sheets></workbook>'
      ),
      "xl/_rels/workbook.xml.rels": strToU8(
        '<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>'
      ),
      "xl/worksheets/sheet1.xml": strToU8(
        '<worksheet><sheetData>'
        + '<row r="1"><c r="E1"><v>4.1</v></c><c r="I1"><f t="shared" si="0" ref="I1:I2">E1*2.5</f><v>10.25</v></c></row>'
        + '<row r="2"><c r="E2"><v>2.85</v></c><c r="I2"><f t="shared" si="0"/><v>7.125</v></c></row>'
        + '</sheetData></worksheet>'
      )
    });
    const file = new File([Uint8Array.from(archive).buffer], "formulas.xlsx");

    const sheet = await overlayExcelFormulas(file, [
      ["", "", "", "", 4.1, "", "", "", 10.25],
      ["", "", "", "", 2.85, "", "", "", 7.125]
    ]);

    expect(countExcelFormulaCells(sheet)).toBe(2);
    expect(isExcelFormulaCell(sheet[0][8]) && sheet[0][8].formula).toBe("E1*2.5");
    expect(isExcelFormulaCell(sheet[1][8]) && sheet[1][8].formula).toBe("E2*2.5");
    expect(isExcelFormulaCell(sheet[1][8]) && sheet[1][8].value).toBeCloseTo(7.125);
    expect(extractExcelFormulaMetadata(sheet)).toEqual([
      { cell: "I1", formula: "E1*2.5", calculatedValue: "10.25" },
      { cell: "I2", formula: "E2*2.5", calculatedValue: "7.125" }
    ]);
  });
});
