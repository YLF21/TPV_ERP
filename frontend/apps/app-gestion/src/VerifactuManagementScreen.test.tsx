// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { UserSession } from "@tpverp/app-common";
import { VerifactuManagementScreen } from "./VerifactuManagementScreen";
import * as api from "./verifactuManagementApi";

vi.mock("./verifactuManagementApi", async (importOriginal) => {
  const original = await importOriginal<typeof import("./verifactuManagementApi")>();
  return {
    ...original,
    loadVerifactuAdminSummary: vi.fn(),
    loadVerifactuCertificates: vi.fn(),
    importVerifactuCertificate: vi.fn(),
    deleteVerifactuCertificate: vi.fn(),
    loadVerifactuAdminSubmissions: vi.fn(),
    loadVerifactuAdminDefectiveRecords: vi.fn(),
    loadVerifactuAdminAttempts: vi.fn(),
    loadVerifactuAdminDiagnostics: vi.fn(),
    loadVerifactuResolution: vi.fn(),
    retryVerifactuSubmission: vi.fn(),
    createVerifactuCorrection: vi.fn()
  };
});

const summary: api.VerifactuAdminSummary = {
  active: true,
  activationMode: "VOLUNTARY",
  effectiveActivationAt: "2026-07-01T10:00:00Z",
  firstSubmissionAt: "2026-07-01T10:30:00Z",
  endpointMode: "TEST",
  workerEnabled: true,
  countsByStatus: {
    PENDIENTE: 2,
    ENVIANDO: 1,
    ENVIADO: 0,
    ACEPTADO: 8,
    ACEPTADO_CON_ERRORES: 1,
    RECHAZADO: 1,
    DEFECTUOSO: 0,
    SUBSANADO: 1
  },
  oldestPendingAt: "2026-07-21T10:00:00Z",
  certificate: { configured: true, valid: true, validUntil: "2027-07-01T00:00:00Z" },
  clock: { available: true, warning: false, driftSeconds: 1, thresholdSeconds: 30, checkedAt: "2026-07-21T09:00:00Z" }
};

const queuePage: api.VerifactuAdminSubmissionPage = {
  items: [{
    recordId: "record-1",
    sequence: 42,
    documentNumber: "T-2026-0042",
    documentType: "F2",
    operation: "ALTA",
    status: "RECHAZADO",
    updatedAt: "2026-07-21T12:00:00Z",
    errorCode: "AEAT-1100"
  }],
  page: 0,
  size: 25,
  totalElements: 1,
  totalPages: 1
};

const defectivePage: api.VerifactuAdminDefectiveRecordPage = {
  items: [{
    recordId: "record-1",
    sequence: 42,
    documentNumber: "T-2026-0042",
    documentType: "F2",
    operation: "ALTA",
    issueDate: "2026-07-21",
    status: "RECHAZADO",
    updatedAt: "2026-07-21T12:00:00Z",
    errorCode: "AEAT-1100"
  }],
  page: 0,
  size: 25,
  totalElements: 1,
  totalPages: 1
};

const attemptPage: api.VerifactuAdminAttemptPage = {
  items: [{
    attemptId: "attempt-1",
    attemptedAt: "2026-07-21T12:00:00Z",
    status: "RECHAZADO",
    errorCode: "AEAT-1100",
    hasTechnicalDetail: true
  }],
  page: 0,
  size: 10,
  totalElements: 1,
  totalPages: 1
};

const diagnostics: api.VerifactuAdminDiagnostics = {
  endpointConfigured: true,
  endpointMode: "TEST",
  workerEnabled: true,
  clock: { available: true, warning: false, driftSeconds: 1, thresholdSeconds: 300, checkedAt: "2026-07-21T12:00:00Z" },
  lastAttempt: { occurredAt: "2026-07-21T12:00:00Z", status: "RECHAZADO" },
  observedAt: "2026-07-21T12:01:00Z"
};

const resolution: api.VerifactuResolution = {
  recordId: "record-1",
  operation: "ALTA",
  status: "RECHAZADO",
  version: 3,
  errorCode: "AEAT-1100",
  category: "AEAT_REJECTED",
  recommendedAction: "CREATE_CORRECTION",
  permittedActions: []
};

const session: UserSession = {
  username: "fiscal-reader",
  displayName: "Fiscal reader",
  accessToken: "fiscal-token",
  permissions: ["APP_GESTION_ACCESS", "VERIFACTU_READ"]
};

const adminSession: UserSession = {
  username: "ADMIN",
  displayName: "ADMIN",
  accessToken: "admin-token",
  permissions: ["ADMIN"]
};

const t = (key: string) => key;

beforeEach(() => {
  vi.mocked(api.loadVerifactuAdminSummary).mockResolvedValue(summary);
  vi.mocked(api.loadVerifactuCertificates).mockResolvedValue([]);
  vi.mocked(api.loadVerifactuAdminSubmissions).mockResolvedValue(queuePage);
  vi.mocked(api.loadVerifactuAdminDefectiveRecords).mockResolvedValue(defectivePage);
  vi.mocked(api.loadVerifactuAdminAttempts).mockResolvedValue(attemptPage);
  vi.mocked(api.loadVerifactuAdminDiagnostics).mockResolvedValue(diagnostics);
  vi.mocked(api.loadVerifactuResolution).mockResolvedValue(resolution);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("VerifactuManagementScreen", () => {
  it("starts in the operational summary without premature administrative actions", async () => {
    render(<VerifactuManagementScreen locale="es" session={session} t={t} />);

    expect(await screen.findByText("Voluntary")).toBeInTheDocument();
    expect(api.loadVerifactuAdminSummary).toHaveBeenCalledWith("fiscal-token");
    expect(screen.getByText("8")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /retry|reintentar|subsanar|activar|certificado/i })).not.toBeInTheDocument();
    expect(api.loadVerifactuAdminSubmissions).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "verifactu.management.certificate" })).not.toBeInTheDocument();
  });

  it("shows the real certificate view only to ADMIN and loads it on demand", async () => {
    render(<VerifactuManagementScreen locale="es" session={adminSession} t={t} />);
    await screen.findByText("Voluntary");

    const tab = screen.getByRole("button", { name: "verifactu.management.certificate" });
    expect(api.loadVerifactuCertificates).not.toHaveBeenCalled();
    fireEvent.click(tab);

    expect(await screen.findByText("verifactu.certificate.emptyTitle")).toBeInTheDocument();
    expect(api.loadVerifactuCertificates).toHaveBeenCalledWith("admin-token");
  });

  it("opens the backend resolution for a reader without exposing mutation controls", async () => {
    render(<VerifactuManagementScreen locale="es" session={session} t={t} />);
    fireEvent.click(screen.getByRole("button", { name: "verifactu.management.queue" }));
    await screen.findByText("T-2026-0042");

    fireEvent.click(screen.getByRole("button", {
      name: "verifactu.resolution.review T-2026-0042"
    }));

    expect(await screen.findByRole("complementary", {
      name: "verifactu.resolution.title"
    })).toBeInTheDocument();
    expect(api.loadVerifactuResolution).toHaveBeenCalledWith("record-1", "fiscal-token");
    expect(screen.getByText("verifactu.resolution.permissionRequired")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "verifactu.resolution.prepareCorrection" }))
      .not.toBeInTheDocument();
  });

  it("opens the paginated queue and renders only sanitized fields", async () => {
    render(<VerifactuManagementScreen locale="es" session={session} t={t} />);
    await screen.findByText("Voluntary");

    fireEvent.click(screen.getByRole("button", { name: "verifactu.management.queue" }));

    expect(await screen.findByText("T-2026-0042")).toBeInTheDocument();
    expect(screen.getByRole("button", {
      name: "verifactu.management.status: verifactu.management.allStatuses"
    })).toBeInTheDocument();
    expect(screen.getByRole("button", {
      name: "verifactu.management.documentType: verifactu.management.allTypes"
    })).toBeInTheDocument();
    expect(screen.getByRole("button", {
      name: "verifactu.management.fiscalOperation: verifactu.management.allOperations"
    })).toBeInTheDocument();
    expect(screen.getByText("AEAT-1100")).toBeInTheDocument();
    expect(api.loadVerifactuAdminSubmissions).toHaveBeenCalledWith(expect.objectContaining({
      page: 0,
      size: 25
    }), "fiscal-token");
    expect(document.body.textContent).not.toContain("responsePayload");
    expect(document.body.textContent).not.toContain("requestXml");
  });

  it("validates dates locally and sends document filters through backend pagination", async () => {
    render(<VerifactuManagementScreen locale="es" session={session} t={t} />);
    fireEvent.click(screen.getByRole("button", { name: "verifactu.management.queue" }));
    await screen.findByText("T-2026-0042");
    vi.mocked(api.loadVerifactuAdminSubmissions).mockClear();

    fireEvent.change(screen.getByLabelText("verifactu.management.dateFrom"), { target: { value: "2026-07-21" } });
    fireEvent.change(screen.getByLabelText("verifactu.management.dateTo"), { target: { value: "2026-07-01" } });
    fireEvent.click(screen.getByRole("button", { name: "verifactu.management.applyFilters" }));

    expect(screen.getByRole("alert")).toHaveTextContent("verifactu.management.invalidDateRange");
    expect(api.loadVerifactuAdminSubmissions).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText("verifactu.management.dateTo"), { target: { value: "2026-07-31" } });
    fireEvent.change(screen.getByLabelText("verifactu.management.documentNumber"), { target: { value: " T-42 " } });
    fireEvent.click(screen.getByRole("button", { name: "verifactu.management.applyFilters" }));

    await waitFor(() => expect(api.loadVerifactuAdminSubmissions).toHaveBeenCalledWith(expect.objectContaining({
      dateFrom: "2026-07-21",
      dateTo: "2026-07-31",
      documentNumber: " T-42 ",
      page: 0
    }), "fiscal-token"));
  });

  it("shows a safe error state when the summary cannot be loaded", async () => {
    vi.mocked(api.loadVerifactuAdminSummary).mockRejectedValueOnce(new Error("internal secret"));

    render(<VerifactuManagementScreen locale="es" session={session} t={t} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("verifactu.management.summaryError");
    expect(document.body.textContent).not.toContain("internal secret");
  });

  it("loads scoped defective records and opens sanitized attempt history", async () => {
    render(<VerifactuManagementScreen locale="es" session={session} t={t} />);

    fireEvent.click(screen.getByRole("button", { name: "verifactu.management.defective" }));
    expect(await screen.findByText("T-2026-0042")).toBeInTheDocument();
    expect(api.loadVerifactuAdminDefectiveRecords).toHaveBeenCalledWith(expect.objectContaining({
      page: 0,
      size: 25
    }), "fiscal-token");

    fireEvent.click(screen.getByRole("button", {
      name: "verifactu.management.viewAttempts T-2026-0042"
    }));

    expect(await screen.findByRole("complementary", {
      name: "verifactu.management.attemptHistory"
    })).toBeInTheDocument();
    expect(api.loadVerifactuAdminAttempts).toHaveBeenCalledWith(
      "record-1", 0, 10, "fiscal-token"
    );
    expect(screen.getByText("verifactu.management.protected")).toBeInTheDocument();
    expect(document.body.textContent).not.toContain("responsePayload");
    expect(document.body.textContent).not.toContain("requestXml");
    expect(document.body.textContent).not.toContain("<script>");
  });

  it("shows passive diagnostics without connection or mutation actions", async () => {
    render(<VerifactuManagementScreen locale="es" session={session} t={t} />);

    fireEvent.click(screen.getByRole("button", { name: "verifactu.management.diagnostics" }));

    expect(await screen.findByText("verifactu.management.passiveDiagnostic")).toBeInTheDocument();
    expect(api.loadVerifactuAdminDiagnostics).toHaveBeenCalledWith("fiscal-token");
    expect(screen.getByText("verifactu.management.workerConfigurationOnly")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /test|probar|retry|reintentar|correct|subsan/i })).not.toBeInTheDocument();
  });
});
