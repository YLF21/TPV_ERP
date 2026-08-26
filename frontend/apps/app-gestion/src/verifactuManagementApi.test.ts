import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createVerifactuCorrection,
  createFiscalExport,
  createFiscalExportJob,
  createFiscalRequiredSubmissionExportJob,
  downloadFiscalExportZip,
  downloadFiscalExportJob,
  loadFiscalExportJobStatus,
  loadFiscalExportJobs,
  loadFiscalEventsCursor,
  loadFiscalExportHistoryCursor,
  loadFiscalRequiredSubmissionsCursor,
  retryFiscalExportJob,
  deleteVerifactuCertificate,
  importVerifactuCertificate,
  loadVerifactuAdminAttempts,
  loadVerifactuAdminDefectiveRecords,
  loadVerifactuAdminDiagnostics,
  loadVerifactuAdminSubmissions,
  loadVerifactuAdminSummary,
  loadVerifactuCertificates,
  loadVerifactuResolution,
  retryVerifactuSubmission
} from "./verifactuManagementApi";

afterEach(() => vi.unstubAllGlobals());

describe("VeriFactu management API", () => {
  it("crea trabajos con payload exacto para filtros y conserva el prefijo", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: "job-1", status: "QUEUED" }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const signal = new AbortController().signal;
    await createFiscalExportJob({ kind: "BILLING", scope: "FILTERED", periodStart: null, periodEnd: null, recordIds: [], dateFrom: "2026-08-01", dateTo: "2026-08-26", documentNumberPrefix: " T-1 ", operation: "ALTA" }, "token", signal);
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({ kind: "BILLING", scope: "FILTERED", periodStart: null, periodEnd: null, recordIds: [], dateFrom: "2026-08-01", dateTo: "2026-08-26", documentNumberPrefix: "T-1", operation: "ALTA" });
    expect(fetchMock.mock.calls[0][1]?.signal).toBe(signal);
  });

  it("usa paginas cursor acotadas para eventos e historiales", async () => {
    const fetchMock = vi.fn().mockImplementation(() => new Response(JSON.stringify({
      items: [], size: 50, nextCursor: null, previousCursor: null,
      hasNext: false, hasPrevious: false, snapshotSequence: 8
    }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const signal = new AbortController().signal;
    await loadFiscalEventsCursor("token", 500, "event-cursor", signal);
    await loadFiscalExportHistoryCursor("token", 50, null, signal);
    await loadFiscalRequiredSubmissionsCursor("token", 25, "history-cursor", signal);
    expect(String(fetchMock.mock.calls[0][0])).toContain("/fiscal/events/cursor?size=100&cursor=event-cursor");
    expect(String(fetchMock.mock.calls[1][0])).toContain("/fiscal/exports/cursor?size=50");
    expect(String(fetchMock.mock.calls[2][0])).toContain("/fiscal/required-submissions/cursor?size=25&cursor=history-cursor");
    expect(fetchMock.mock.calls.every((call) => call[1]?.signal === signal)).toBe(true);
  });

  it("crea la atencion de requerimiento como trabajo durable", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: "job-req", status: "QUEUED" }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const signal = new AbortController().signal;
    await createFiscalRequiredSubmissionExportJob("req/1", "2026-08-01T00:00:00Z", "2026-08-31T23:59:59Z", "token", signal);
    expect(String(fetchMock.mock.calls[0][0])).toContain("/fiscal/required-submissions/req%2F1/export-jobs");
    expect(fetchMock.mock.calls[0][1]?.method).toBe("POST");
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({ periodStart: "2026-08-01T00:00:00Z", periodEnd: "2026-08-31T23:59:59Z" });
    expect(fetchMock.mock.calls[0][1]?.signal).toBe(signal);
  });

  it("lista, consulta y descarga trabajos con AbortSignal", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: "job-1", status: "RUNNING", processed: 2 }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: "job-2", status: "QUEUED", processed: 0 }), { status: 200 }))
      .mockResolvedValueOnce(new Response(new Blob(["zip"]), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const signal = new AbortController().signal;
    await loadFiscalExportJobs("token", 0, 20, signal);
    await loadFiscalExportJobStatus("job-1", "token", signal);
    await retryFiscalExportJob("job-1", "token", signal);
    await downloadFiscalExportJob("job-1", "token", signal);
    expect(String(fetchMock.mock.calls[0][0])).toContain("/fiscal/export-jobs?page=0&size=20");
    expect(String(fetchMock.mock.calls[1][0])).toContain("/fiscal/export-jobs/job-1");
    expect(String(fetchMock.mock.calls[2][0])).toContain("/fiscal/export-jobs/job-1/retry");
    expect(fetchMock.mock.calls[2][1]?.method).toBe("POST");
    expect(String(fetchMock.mock.calls[3][0])).toContain("/fiscal/export-jobs/job-1/download");
    expect(fetchMock.mock.calls.every((call) => call[1]?.signal === signal)).toBe(true);
  });

  it("sends validated export selection and filters to the backend contract", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ xml: [], recordCount: 1 }), {
      status: 200, headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetchMock);
    await createFiscalExport("BILLING", null, null, "token", {
      recordIds: ["record-1"], documentNumber: " T-1 ", operation: "ALTA"
    });
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({
      kind: "BILLING", periodStart: null, periodEnd: null,
      recordIds: ["record-1"], documentNumber: "T-1", operation: "ALTA"
    });
  });

  it("descarga el ZIP reglamentario desde el endpoint binario", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(new Blob(["zip"]), {
      status: 200, headers: { "Content-Type": "application/zip" }
    }));
    vi.stubGlobal("fetch", fetchMock);
    const result = await downloadFiscalExportZip("BILLING", "2026-01-01T00:00:00.000Z", "2026-12-31T23:59:59.000Z", "token", {});
    expect(result).toBeInstanceOf(Blob);
    expect(String(fetchMock.mock.calls[0][0])).toContain("/fiscal/exports/download");
    expect(fetchMock.mock.calls[0][1]?.headers).toMatchObject({ Authorization: "Bearer token" });
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toMatchObject({ kind: "BILLING", periodStart: "2026-01-01T00:00:00.000Z" });
  });

  it("loads the sanitized summary contract", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ active: false }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetchMock);

    await loadVerifactuAdminSummary("token");

    expect(String(fetchMock.mock.calls[0][0])).toContain("/verifactu/admin/summary");
    expect(fetchMock.mock.calls[0][1]?.method).toBe("GET");
  });

  it("loads public certificate metadata through the admin-only contract", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("[]", {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetchMock);

    await loadVerifactuCertificates("admin-token");

    expect(String(fetchMock.mock.calls[0][0])).toContain("/verifactu/admin/certificates");
    expect(fetchMock.mock.calls[0][1]?.method).toBe("GET");
    expect(fetchMock.mock.calls[0][1]?.headers).toMatchObject({ Authorization: "Bearer admin-token" });
  });

  it("imports or replaces a certificate using multipart without forcing a JSON content type", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: "certificate-2" }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    }));
    vi.stubGlobal("fetch", fetchMock);
    const file = new File(["pkcs12"], "renewed.p12", { type: "application/x-pkcs12" });

    await importVerifactuCertificate(file, "temporary-password", {
      expectedActiveCertificateId: "certificate-1",
      confirmation: "SUSTITUIR CERTIFICADO"
    }, "admin-token");

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.method).toBe("POST");
    expect(init.body).toBeInstanceOf(FormData);
    const body = init.body as FormData;
    expect(body.get("file")).toBe(file);
    expect(body.get("password")).toBe("temporary-password");
    expect(body.get("expectedActiveCertificateId")).toBe("certificate-1");
    expect(body.get("confirmation")).toBe("SUSTITUIR CERTIFICADO");
    expect(Object.keys(init.headers as Record<string, string>)).not.toContain("Content-Type");
  });

  it("deletes a certificate with the exact administrative confirmation", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await deleteVerifactuCertificate("ELIMINAR CERTIFICADO", "admin-token");

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.method).toBe("DELETE");
    expect(JSON.parse(String(init.body))).toEqual({ confirmation: "ELIMINAR CERTIFICADO" });
  });

  it("uses backend pagination and every approved queue filter", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      items: [], page: 2, size: 25, totalElements: 0, totalPages: 0
    }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await loadVerifactuAdminSubmissions({
      dateFrom: "2026-07-01",
      dateTo: "2026-07-21",
      status: "RECHAZADO",
      documentType: "F2",
      operation: "ANULACION",
      documentNumber: " T-100 ",
      page: 2,
      size: 25,
      sortBy: "updatedAt",
      sortDirection: "asc"
    }, "token");

    const url = String(fetchMock.mock.calls[0][0]);
    expect(url).toContain("/verifactu/admin/submissions?");
    expect(url).toContain("dateFrom=2026-07-01");
    expect(url).toContain("dateTo=2026-07-21");
    expect(url).toContain("status=RECHAZADO");
    expect(url).toContain("documentType=F2");
    expect(url).toContain("operation=ANULACION");
    expect(url).toContain("documentNumber=T-100");
    expect(url).toContain("page=2");
    expect(url).toContain("sortBy=updatedAt");
    expect(url).toContain("sortDirection=asc");
    expect(fetchMock.mock.calls[0][1]?.method).toBe("GET");
  });

  it("uses additive sanitized review endpoints without mutation methods", async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
      items: [], page: 0, size: 25, totalElements: 0, totalPages: 0
    }), { status: 200, headers: { "Content-Type": "application/json" } })));
    vi.stubGlobal("fetch", fetchMock);

    await loadVerifactuAdminDefectiveRecords({
      dateFrom: "2026-07-01",
      dateTo: "2026-07-21",
      status: "DEFECTUOSO",
      documentType: "F2",
      operation: "ALTA",
      documentNumber: " T-200 ",
      page: 1,
      size: 25,
      sortBy: "sequence",
      sortDirection: "desc"
    }, "token");
    await loadVerifactuAdminAttempts("record/unsafe", 2, 10, "token");
    await loadVerifactuAdminDiagnostics("token");

    const defectiveUrl = String(fetchMock.mock.calls[0][0]);
    expect(defectiveUrl).toContain("/verifactu/admin/defective-records?");
    expect(defectiveUrl).toContain("status=DEFECTUOSO");
    expect(defectiveUrl).toContain("documentNumber=T-200");
    expect(defectiveUrl).toContain("sortBy=sequence");
    expect(defectiveUrl).toContain("sortDirection=desc");
    expect(String(fetchMock.mock.calls[1][0]))
      .toContain("/verifactu/admin/submissions/record%2Funsafe/attempts?page=2&size=10");
    expect(String(fetchMock.mock.calls[2][0])).toContain("/verifactu/admin/diagnostics");
    expect(fetchMock.mock.calls.every((call) => call[1]?.method === "GET")).toBe(true);
  });

  it("uses scoped resolution, versioned retry and administrative correction contracts", async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
      recordId: "record-1", status: "ENVIADO"
    }), { status: 200, headers: { "Content-Type": "application/json" } })));
    vi.stubGlobal("fetch", fetchMock);

    await loadVerifactuResolution("record/1", "token");
    await retryVerifactuSubmission("record/1", 7, " Revisión de comunicación ", "token");
    await createVerifactuCorrection("record/1", {
      reason: " NIF transmitido incorrectamente ",
      recipientTaxId: " B12345674 ",
      recipientName: " Cliente SL ",
      operationDescription: " "
    }, "token");

    expect(String(fetchMock.mock.calls[0][0]))
      .toContain("/verifactu/admin/submissions/record%2F1/resolution");
    expect(fetchMock.mock.calls[0][1]?.method).toBe("GET");
    expect(String(fetchMock.mock.calls[1][0]))
      .toContain("/verifactu/admin/submissions/record%2F1/retry");
    expect(fetchMock.mock.calls[1][1]?.method).toBe("POST");
    expect(JSON.parse(String(fetchMock.mock.calls[1][1]?.body))).toEqual({
      expectedVersion: 7,
      reason: "Revisión de comunicación"
    });
    expect(String(fetchMock.mock.calls[2][0]))
      .toContain("/verifactu/defective-records/record%2F1/corrections");
    expect(fetchMock.mock.calls[2][1]?.method).toBe("POST");
    expect(JSON.parse(String(fetchMock.mock.calls[2][1]?.body))).toEqual({
      reason: "NIF transmitido incorrectamente",
      recipientTaxId: "B12345674",
      recipientName: "Cliente SL",
      operationDescription: null
    });
  });
});
