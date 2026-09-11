import { afterEach, describe, expect, it, vi } from "vitest";
import {
  productExcelReadResultToSheet,
  productExcelImportErrorFromApi,
  productExcelImportErrorsFromApi,
  previewProductExcelImport,
  exportProductExcelImportSummary,
  readProductExcelImport,
  type ProductExcelImportReadResult
} from "./productExcelImports";
import { ApiError } from "./client";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("product Excel import API", () => {
  it("uploads the original workbook as multipart data", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify(readResult()),
      headers: { get: () => null }
    });
    vi.stubGlobal("fetch", fetchMock);
    const file = new File(["workbook"], "productos.xls", { type: "application/vnd.ms-excel" });

    await readProductExcelImport(file, "token");

    expect(fetchMock).toHaveBeenCalledOnce();
    const [, request] = fetchMock.mock.calls[0];
    expect(request.method).toBe("POST");
    expect(request.headers.Authorization).toBe("Bearer token");
    expect(request.headers["Content-Type"]).toBeUndefined();
    expect(request.body).toBeInstanceOf(FormData);
    expect(request.body.get("file")).toBe(file);
  });

  it("keeps only the cached backend value for formula cells", () => {
    const sheet = productExcelReadResultToSheet(readResult());

    expect(sheet).toEqual([
      ["CODIGO", "PRECIO"],
      ["A-1", { kind: "formula", formula: "C2-D2", value: "1.25" }]
    ]);
  });

  it("keeps empty backend cells as null instead of inventing text", () => {
    const sheet = productExcelReadResultToSheet({
      ...readResult(),
      rows: [[{ value: "CODIGO" }, { value: null }]]
    });
    expect(sheet).toEqual([["CODIGO", null]]);
  });

  it("sends the authoritative preview configuration as JSON multipart data", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ ...readResult(), detectedRows: 0, existingRows: 0, missingRows: 0, errors: [] }),
      headers: { get: () => null }
    });
    vi.stubGlobal("fetch", fetchMock);
    const file = new File(["workbook"], "productos.xlsx");
    const config = {
      mapping: { code: "A", quantity: "AA" },
      edits: [{ row: 2, column: "AA", value: "3" }],
      options: {
        globalValues: {},
        valueSources: { priceUseMode: { source: "global" as const, value: "1" } },
        showOnlyImported: false,
        context: "STOCK" as const,
        skipZeroPriceUpdate: true,
        requireQuantity: false
      },
      expectedSha256: "abc",
      startRow: 2,
      quantityColumn: "AA",
      updateFields: { salePrice: true }
    };

    await previewProductExcelImport(file, config, "token");

    const body = fetchMock.mock.calls[0][1].body as FormData;
    const configPart = body.get("config") as Blob;
    expect(configPart.type).toBe("application/json");
    expect(JSON.parse(await configPart.text())).toEqual(config);
  });

  it("keeps the structured correction returned by the backend", () => {
    expect(productExcelImportErrorFromApi(new ApiError("invalid", 400, {
      code: "FILE_SIGNATURE_INVALID",
      row: 2,
      column: 3,
      receivedValue: "fake.xls",
      reason: "La firma no corresponde al formato",
      acceptedValues: "XLS o XLSX real",
      recommendedFix: "Selecciona el fichero original"
    }))).toEqual(expect.objectContaining({
      code: "FILE_SIGNATURE_INVALID",
      row: 2,
      column: 3,
      reason: "La firma no corresponde al formato",
      recommendedFix: "Selecciona el fichero original"
    }));
  });

  it.each([400, 409])("keeps every structured error when Apply returns an error list (%s)", (status) => {
    const errors = productExcelImportErrorsFromApi(new ApiError("apply failed", status, {
      errors: [
        {
          code: "VERSION_STALE", row: 2, column: 27, attribute: "purchasePrice",
          receivedValue: "4,20", reason: "La ficha cambió", acceptedValues: "Vista previa vigente",
          recommendedFix: "Vuelve a generar la vista previa"
        },
        {
          code: "IDENTIFIER_DUPLICATE", row: 8, column: 1, attribute: "code",
          receivedValue: "A-8", reason: "Identificador duplicado", acceptedValues: "Código único",
          recommendedFix: "Corrige el código"
        }
      ]
    }));

    expect(errors).toHaveLength(2);
    expect(errors[0]).toEqual(expect.objectContaining({
      code: "VERSION_STALE", row: 2, column: 27, receivedValue: "4,20",
      acceptedValues: "Vista previa vigente", recommendedFix: "Vuelve a generar la vista previa"
    }));
    expect(errors[1]).toEqual(expect.objectContaining({ code: "IDENTIFIER_DUPLICATE", row: 8 }));
    expect(productExcelImportErrorFromApi(new ApiError("apply failed", status, { errors }))).toEqual(errors[0]);
  });

  it("requests the server XLSX summary as a blob and keeps its safe filename", async () => {
    const blob = new Blob(["xlsx"], { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" });
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      blob: async () => blob,
      headers: { get: (name: string) => name === "Content-Disposition" ? 'attachment; filename="productos-resumen.xlsx"' : null }
    });
    vi.stubGlobal("fetch", fetchMock);
    const file = new File(["workbook"], "productos.xlsx");
    const config = { ...previewConfig() };

    const fingerprint = "f".repeat(64);
    const result = await exportProductExcelImportSummary(file, config, fingerprint, "en", "token");

    expect(result.blob).toBe(blob);
    expect(result.fileName).toBe("productos-resumen.xlsx");
    const body = fetchMock.mock.calls[0][1].body as FormData;
    expect(body.get("file")).toBe(file);
    expect(body.get("locale")).toBe("en");
    expect(JSON.parse(await (body.get("config") as Blob).text())).toEqual({
      preview: config,
      expectedPreviewFingerprint: fingerprint
    });
  });
});

function previewConfig() {
  return {
    mapping: { code: "A" },
    edits: [],
    options: {
      globalValues: {},
      valueSources: {},
      showOnlyImported: false,
      context: "STOCK" as const,
      skipZeroPriceUpdate: false,
      requireQuantity: false
    },
    expectedSha256: "abc",
    startRow: 2,
    updateFields: {}
  };
}

function readResult(): ProductExcelImportReadResult {
  return {
    fileName: "productos.xls",
    sha256: "abc",
    sheetName: "Hoja1",
    rows: [
      [{ value: "CODIGO" }, { value: "PRECIO" }],
      [{ value: "A-1" }, { value: "1.25", formula: "C2-D2" }]
    ],
    formulas: [{ cell: "B2", formula: "C2-D2", calculatedValue: "1.25" }],
    nonEmptyRows: 2,
    columns: 2,
    nonEmptyCells: 4
  };
}
