import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createVerifactuCorrection,
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
      size: 25
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
      size: 25
    }, "token");
    await loadVerifactuAdminAttempts("record/unsafe", 2, 10, "token");
    await loadVerifactuAdminDiagnostics("token");

    const defectiveUrl = String(fetchMock.mock.calls[0][0]);
    expect(defectiveUrl).toContain("/verifactu/admin/defective-records?");
    expect(defectiveUrl).toContain("status=DEFECTUOSO");
    expect(defectiveUrl).toContain("documentNumber=T-200");
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
