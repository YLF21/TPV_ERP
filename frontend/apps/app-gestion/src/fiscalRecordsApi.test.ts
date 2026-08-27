import { afterEach, describe, expect, it, vi } from "vitest";
import { loadFiscalRecord, loadFiscalRecords, loadFiscalRecordsCursor } from "./fiscalRecordsApi";

afterEach(() => vi.unstubAllGlobals());

describe("fiscal records API", () => {
  it("serializa todos los filtros, número y paginación", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [], page: 2, size: 25, totalElements: 0, totalPages: 0 }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    await loadFiscalRecords({ dateFrom: "2026-08-01", dateTo: "2026-08-26", number: " F-10 ", numberMatch: "EXACT", operation: "ANULACION", documentType: "R5", fiscalMode: "NO_VERIFACTU", page: 2, size: 25 }, "token");
    const url = String(fetchMock.mock.calls[0][0]);
    expect(url).toContain("/verifactu/admin/records?");
    expect(url).toContain("number=F-10");
    expect(url).toContain("numberMatch=EXACT");
    expect(url).toContain("operation=ANULACION");
    expect(url).toContain("documentType=R5");
    expect(url).toContain("fiscalMode=NO_VERIFACTU");
    expect(url).toContain("page=2");
  });

  it("codifica el identificador de detalle y mantiene GET", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ recordId: "record-1", relations: [] }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    await loadFiscalRecord("record/1", "token");
    expect(String(fetchMock.mock.calls[0][0])).toContain("/verifactu/admin/records/record%2F1");
    expect(fetchMock.mock.calls[0][1]?.method).toBe("GET");
    expect(fetchMock.mock.calls[0][1]?.headers).toMatchObject({ Authorization: "Bearer token" });
  });

  it("serializa el cursor y propaga la señal de cancelación", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      items: [], size: 25, nextCursor: "next", previousCursor: null,
      hasNext: true, hasPrevious: false, snapshotSequence: 12
    }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const signal = new AbortController().signal;
    await loadFiscalRecordsCursor({
      dateFrom: "", dateTo: "", number: " F-10 ", operation: "",
      documentType: "", fiscalMode: "NO_VERIFACTU", size: 25,
      cursor: "cursor/2", numberMatch: "PREFIX"
    }, "token", signal);
    const url = String(fetchMock.mock.calls[0][0]);
    expect(url).toContain("/verifactu/admin/records/cursor?");
    expect(url).toContain("cursor=cursor%2F2");
    expect(url).toContain("number=F-10");
    expect(url).toContain("numberMatch=PREFIX");
    expect(fetchMock.mock.calls[0][1]?.signal).toBe(signal);
  });

  it("no añade un cursor vacío a la primera página", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      items: [], size: 25, nextCursor: null, previousCursor: null,
      hasNext: false, hasPrevious: false, snapshotSequence: 0
    }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    await loadFiscalRecordsCursor({
      dateFrom: "", dateTo: "", number: "", operation: "",
      documentType: "", fiscalMode: "", size: 25, cursor: null
    });
    expect(String(fetchMock.mock.calls[0][0])).not.toContain("cursor=");
  });
});
