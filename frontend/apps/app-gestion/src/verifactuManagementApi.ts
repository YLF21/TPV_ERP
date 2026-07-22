import { apiRequest } from "@tpverp/app-common";

export const verifactuSubmissionStatuses = [
  "PENDIENTE",
  "ENVIANDO",
  "ENVIADO",
  "ACEPTADO",
  "ACEPTADO_CON_ERRORES",
  "RECHAZADO",
  "DEFECTUOSO",
  "SUBSANADO"
] as const;

export const verifactuDocumentTypes = ["F1", "F2", "F3", "R1", "R2", "R3", "R4", "R5"] as const;
export const verifactuOperations = ["ALTA", "ANULACION"] as const;

export type VerifactuSubmissionStatus = typeof verifactuSubmissionStatuses[number];
export type VerifactuDocumentType = typeof verifactuDocumentTypes[number];
export type VerifactuOperation = typeof verifactuOperations[number];

export type VerifactuResolutionAction =
  | "WAIT"
  | "RETRY"
  | "CREATE_CORRECTION"
  | "CREATE_RECTIFYING_INVOICE"
  | "TECHNICAL_REVIEW"
  | "NONE";

export type VerifactuResolutionCategory =
  | "WAITING"
  | "COMMUNICATION_PENDING"
  | "LOCAL_TECHNICAL_ERROR"
  | "ADMINISTRATIVE_DATA_ERROR"
  | "AEAT_REJECTED"
  | "AEAT_ACCEPTED_WITH_ERRORS"
  | "ACCEPTED_FINAL"
  | "CORRECTED_FINAL"
  | "TECHNICAL_REVIEW";

export type VerifactuCertificateSummary = {
  configured: boolean;
  valid: boolean;
  warningCode?: string | null;
  validUntil?: string | null;
};

export type VerifactuManagedCertificate = {
  id: string;
  status: "ACTIVO" | "ANTERIOR" | string;
  subject: string;
  issuer: string;
  serialNumber: string;
  taxId: string;
  fingerprint: string;
  validFrom: string;
  validUntil: string;
  validityStatus: "VALIDO" | "PROXIMO_A_CADUCAR" | "CADUCADO" | "TODAVIA_NO_VALIDO" | string;
  daysRemaining: number;
  canDelete: boolean;
  deleteBlockReason?: string | null;
};

export type VerifactuClockSummary = {
  available: boolean;
  warning: boolean;
  warningCode?: string | null;
  driftSeconds?: number | null;
  thresholdSeconds?: number | null;
  checkedAt?: string | null;
};

export type VerifactuAdminSummary = {
  active: boolean;
  activationMode: "VOLUNTARY" | "LEGAL" | "INACTIVE" | "UNAVAILABLE" | string;
  effectiveActivationAt?: string | null;
  firstSubmissionAt?: string | null;
  endpointMode?: "PRODUCTION" | "PRODUCTION_SEAL" | "TEST" | "TEST_SEAL" | string | null;
  workerEnabled: boolean;
  countsByStatus: Partial<Record<VerifactuSubmissionStatus, number>> & Record<string, number>;
  oldestPendingAt?: string | null;
  certificate: VerifactuCertificateSummary;
  clock: VerifactuClockSummary;
};

export type VerifactuAdminSubmission = {
  recordId: string;
  sequence: number;
  documentNumber: string;
  documentType: VerifactuDocumentType | string;
  operation: VerifactuOperation | string;
  status: VerifactuSubmissionStatus | string;
  updatedAt: string;
  errorCode?: string | null;
};

export type VerifactuAdminSubmissionPage = {
  items: VerifactuAdminSubmission[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type VerifactuAdminSubmissionFilters = {
  dateFrom: string;
  dateTo: string;
  status: "" | VerifactuSubmissionStatus;
  documentType: "" | VerifactuDocumentType;
  operation: "" | VerifactuOperation;
  documentNumber: string;
  page: number;
  size: number;
};

export type VerifactuAdminDefectiveRecord = {
  recordId: string;
  sequence: number;
  documentNumber: string;
  documentType: VerifactuDocumentType | string;
  operation: VerifactuOperation | string;
  issueDate: string;
  status: VerifactuSubmissionStatus | string;
  updatedAt: string;
  errorCode?: string | null;
};

export type VerifactuAdminDefectiveRecordPage = {
  items: VerifactuAdminDefectiveRecord[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type VerifactuAdminDefectiveFilters = Omit<VerifactuAdminSubmissionFilters, "status"> & {
  status: "" | "RECHAZADO" | "DEFECTUOSO" | "ACEPTADO_CON_ERRORES";
};

export type VerifactuAdminAttempt = {
  attemptId: string;
  attemptedAt: string;
  status: VerifactuSubmissionStatus | string;
  errorCode?: string | null;
  hasTechnicalDetail: boolean;
};

export type VerifactuAdminAttemptPage = {
  items: VerifactuAdminAttempt[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type VerifactuAdminDiagnosticEvent = {
  occurredAt: string;
  status: VerifactuSubmissionStatus | string;
};

export type VerifactuAdminDiagnostics = {
  endpointConfigured: boolean;
  endpointMode?: VerifactuAdminSummary["endpointMode"];
  workerEnabled: boolean;
  clock: VerifactuClockSummary;
  lastAttempt?: VerifactuAdminDiagnosticEvent | null;
  observedAt: string;
};

export type VerifactuResolution = {
  recordId: string;
  operation: VerifactuOperation | string;
  status: VerifactuSubmissionStatus | string;
  version: number;
  errorCode?: string | null;
  category: VerifactuResolutionCategory | string;
  recommendedAction: VerifactuResolutionAction | string;
  permittedActions: (VerifactuResolutionAction | string)[];
};

export type VerifactuManualRetryResult = {
  recordId: string;
  status: VerifactuSubmissionStatus | string;
  errorCode?: string | null;
};

export type VerifactuCorrectionRequest = {
  reason: string;
  recipientTaxId?: string | null;
  recipientName?: string | null;
  operationDescription?: string | null;
};

export type VerifactuCorrectionResult = {
  id: string;
  originalRecordId: string;
  number: string;
  generatedAt: string;
  status: VerifactuSubmissionStatus | string;
};

export function loadVerifactuAdminSummary(token?: string) {
  return apiRequest<VerifactuAdminSummary>("/verifactu/admin/summary", { token });
}

export function loadVerifactuCertificates(token?: string) {
  return apiRequest<VerifactuManagedCertificate[]>("/verifactu/admin/certificates", { token });
}

export function importVerifactuCertificate(
  file: File,
  password: string,
  replacement: { expectedActiveCertificateId: string; confirmation: string } | null,
  token?: string
) {
  const body = new FormData();
  body.append("file", file);
  body.append("password", password);
  if (replacement) {
    body.append("expectedActiveCertificateId", replacement.expectedActiveCertificateId);
    body.append("confirmation", replacement.confirmation);
  }
  return apiRequest<VerifactuManagedCertificate>("/verifactu/admin/certificates", {
    method: "POST",
    token,
    body
  });
}

export function deleteVerifactuCertificate(confirmation: string, token?: string) {
  return apiRequest<void>("/verifactu/admin/certificates", {
    method: "DELETE",
    token,
    body: { confirmation }
  });
}

export function loadVerifactuAdminSubmissions(
  filters: VerifactuAdminSubmissionFilters,
  token?: string
) {
  const params = submissionParams(filters);
  return apiRequest<VerifactuAdminSubmissionPage>(`/verifactu/admin/submissions?${params}`, { token });
}

export function loadVerifactuAdminDefectiveRecords(
  filters: VerifactuAdminDefectiveFilters,
  token?: string
) {
  const params = submissionParams(filters);
  return apiRequest<VerifactuAdminDefectiveRecordPage>(
    `/verifactu/admin/defective-records?${params}`,
    { token }
  );
}

export function loadVerifactuAdminAttempts(recordId: string, page: number, size: number, token?: string) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  return apiRequest<VerifactuAdminAttemptPage>(
    `/verifactu/admin/submissions/${encodeURIComponent(recordId)}/attempts?${params}`,
    { token }
  );
}

export function loadVerifactuAdminDiagnostics(token?: string) {
  return apiRequest<VerifactuAdminDiagnostics>("/verifactu/admin/diagnostics", { token });
}

export function loadVerifactuResolution(recordId: string, token?: string) {
  return apiRequest<VerifactuResolution>(
    `/verifactu/admin/submissions/${encodeURIComponent(recordId)}/resolution`,
    { token }
  );
}

export function retryVerifactuSubmission(
  recordId: string,
  expectedVersion: number,
  reason: string,
  token?: string
) {
  return apiRequest<VerifactuManualRetryResult>(
    `/verifactu/admin/submissions/${encodeURIComponent(recordId)}/retry`,
    { method: "POST", token, body: { expectedVersion, reason: reason.trim() } }
  );
}

export function createVerifactuCorrection(
  recordId: string,
  request: VerifactuCorrectionRequest,
  token?: string
) {
  return apiRequest<VerifactuCorrectionResult>(
    `/verifactu/defective-records/${encodeURIComponent(recordId)}/corrections`,
    {
      method: "POST",
      token,
      body: {
        reason: request.reason.trim(),
        recipientTaxId: optionalText(request.recipientTaxId),
        recipientName: optionalText(request.recipientName),
        operationDescription: optionalText(request.operationDescription)
      }
    }
  );
}

function submissionParams(filters: VerifactuAdminSubmissionFilters | VerifactuAdminDefectiveFilters) {
  const params = new URLSearchParams({
    page: String(filters.page),
    size: String(filters.size)
  });
  if (filters.dateFrom) params.set("dateFrom", filters.dateFrom);
  if (filters.dateTo) params.set("dateTo", filters.dateTo);
  if (filters.status) params.set("status", filters.status);
  if (filters.documentType) params.set("documentType", filters.documentType);
  if (filters.operation) params.set("operation", filters.operation);
  if (filters.documentNumber.trim()) params.set("documentNumber", filters.documentNumber.trim());
  return params;
}

function optionalText(value: string | null | undefined) {
  const normalized = value?.trim();
  return normalized ? normalized : null;
}
