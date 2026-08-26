import { apiBaseUrl, apiRequest } from "@tpverp/app-common";

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
  sortBy?: string;
  sortDirection?: "asc" | "desc";
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

export type FiscalMode = "PRE_SIF" | "NO_VERIFACTU" | "VERIFACTU";
export type FiscalModeTransitionStatus = "APLICADA" | "PROGRAMADA" | "FALLIDA";
export type FiscalScheduledTransition = {
  previousMode: FiscalMode;
  newMode: FiscalMode;
  status: FiscalModeTransitionStatus;
  requestedAt: string;
  effectiveAt: string;
  verifactuEndDate?: string | null;
  aeatAckReference?: string | null;
  lastErrorCode?: string | null;
};
export type FiscalStatus = {
  companyId: string;
  mode: FiscalMode;
  modeVersion: number;
  modeSince?: string | null;
  /** IANA timezone of the active store, used for fiscal wall-clock inputs. */
  timezone?: string | null;
  runtimeClass: "SANDBOX" | "REAL";
  endpointEnvironment: "TEST" | "PRODUCTION";
  transportMode: "SIMULATED" | "AEAT";
  productionEnabled: boolean;
  verifactuBlockedUntil?: string | null;
  scheduledTransition?: FiscalScheduledTransition | null;
};
export type FiscalSandboxStatus = {
  sandboxEnabled: boolean;
  runtimeClass: "SANDBOX" | "REAL";
  endpointEnvironment: "TEST" | "PRODUCTION";
  transportMode: "SIMULATED" | "AEAT";
  nextOutcome: "ACCEPTED" | "ACCEPTED_WITH_ERRORS" | "REJECTED" | "DUPLICATE" | "TIMEOUT" | "HTTP_ERROR" | "INVALID_RESPONSE";
};

export type FiscalEvent = {
  id: string;
  installationId: string;
  systemVersionId: string;
  sequence: number;
  type: "START_NO_VERIFACTU" | "END_NO_VERIFACTU" | "BILLING_ANOMALY_SCAN_STARTED"
    | "BILLING_ANOMALY_DETECTED" | "EVENT_ANOMALY_SCAN_STARTED" | "EVENT_ANOMALY_DETECTED"
    | "BACKUP_RESTORED" | "BILLING_EXPORT" | "EVENT_EXPORT" | "SUMMARY" | "OTHER" | string;
  fiscalMode: FiscalMode;
  generatedAt: string;
  previousHash?: string | null;
  hash: string;
  xmlHash: string;
  signed: boolean;
};

/** A bounded keyset page. Cursors are opaque and must only be sent back to the same scope. */
export type FiscalHistoryCursorPage<T> = {
  items: T[];
  size: number;
  nextCursor?: string | null;
  previousCursor?: string | null;
  hasNext: boolean;
  hasPrevious: boolean;
};

export type FiscalEventCursorPage = FiscalHistoryCursorPage<FiscalEvent> & {
  snapshotSequence: number;
};

export type FiscalIntegrityCheck = {
  checkedAt: string;
  mode: FiscalMode;
  ok: boolean;
  anomalies: string[];
  billingRecordsChecked: number;
  eventRecordsChecked: number;
  anomaliesTotal?: number;
  billingAnomalies?: number;
  eventAnomalies?: number;
};

export type FiscalIntegrityJobStatus = "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";
export type FiscalIntegrityJob = {
  id: string;
  mode: FiscalMode;
  status: FiscalIntegrityJobStatus;
  billingSnapshotSequence: number;
  eventSnapshotSequence: number;
  billingChecked: number;
  eventsChecked: number;
  anomaliesTotal: number;
  billingAnomalies: number;
  eventAnomalies: number;
  evidenceCodes: string[];
  error?: string | null;
  createdAt: string;
  startedAt?: string | null;
  updatedAt: string;
  completedAt?: string | null;
};

export type FiscalExportKind = "BILLING" | "EVENTS";

export type FiscalExportJobStatus = "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | "EXPIRED";
export type FiscalExportJobScope = "PERIOD" | "FILTERED" | "SELECTED" | "CURRENT";

export type FiscalExportJob = {
  id: string;
  kind?: FiscalExportKind;
  scope?: FiscalExportJobScope;
  requiredSubmissionId?: string | null;
  status: FiscalExportJobStatus;
  processed: number;
  hasMore: boolean;
  error?: string | null;
  fileSize: number;
  downloadAvailable: boolean;
  createdAt: string;
  startedAt?: string | null;
  completedAt?: string | null;
  expiresAt?: string | null;
  /** Present once the server exposes the original request for server-side retry. */
  request?: FiscalExportJobRequest;
};

export type FiscalExportJobRequest = {
  kind: FiscalExportKind;
  scope: FiscalExportJobScope;
  periodStart?: string | null;
  periodEnd?: string | null;
  recordIds?: string[];
  dateFrom?: string | null;
  dateTo?: string | null;
  documentNumber?: string | null;
  documentNumberPrefix?: string | null;
  operation?: string | null;
  documentType?: string | null;
  fiscalMode?: string | null;
};

export type FiscalExport = {
  exportId: string;
  kind: FiscalExportKind;
  exportedAt: string;
  periodStart?: string | null;
  periodEnd?: string | null;
  recordCount: number;
  eventId?: string | null;
  xml: string[];
  batchXml?: string | null;
  contentHash: string;
  companyId?: string | null;
  storeId?: string | null;
  installationId?: string | null;
  records?: Array<{
    recordId: string;
    sequence: number;
    number: string;
    generatedAt: string;
    hash: string;
  }>;
};

export type FiscalExportScope = {
  recordIds?: string[];
  dateFrom?: string | null;
  dateTo?: string | null;
  documentNumber?: string | null;
  documentNumberPrefix?: string | null;
  operation?: string | null;
  documentType?: string | null;
  fiscalMode?: string | null;
};

export type FiscalRequiredSubmission = {
  id: string;
  reference: string;
  status: "PENDIENTE" | "EXPORTADO" | "ERROR" | string;
  requestedAt: string;
  attendedAt?: string | null;
  exportId?: string | null;
};

export type FiscalRequiredSubmissionExport = {
  requirement: FiscalRequiredSubmission;
  export: FiscalExport;
};

export type FiscalExportHistory = {
  exportId: string;
  companyId: string;
  installationId: string;
  kind: FiscalExportKind;
  exportedAt: string;
  periodStart?: string | null;
  periodEnd?: string | null;
  recordCount: number;
  eventId?: string | null;
  contentHash: string;
};

export type FiscalRequiredSubmissionHistory = FiscalRequiredSubmission & {
  companyId: string;
  installationId: string;
};

export function loadFiscalStatus(token?: string) {
  return apiRequest<FiscalStatus>("/fiscal/status", { token });
}

export function transitionFiscalMode(
  request: {
    targetMode: FiscalMode;
    expectedVersion: number;
    reason: string;
    confirmation: boolean;
    fechaFinVeriFactu?: string | null;
    aeatAckReference?: string | null;
  },
  token?: string
) {
  return apiRequest<FiscalStatus>("/fiscal/mode-transitions", {
    method: "POST",
    token,
    body: request
  });
}

export function loadFiscalEvents(token?: string) {
  return apiRequest<FiscalEvent[]>("/fiscal/events", { token });
}

export function loadFiscalEventsCursor(
  token?: string,
  size = 50,
  cursor?: string | null,
  signal?: AbortSignal
) {
  const params = new URLSearchParams({ size: String(Math.min(100, Math.max(1, size))) });
  if (cursor) params.set("cursor", cursor);
  return apiRequest<FiscalEventCursorPage>(`/fiscal/events/cursor?${params.toString()}`, { token, signal });
}

export function runFiscalIntegrityCheck(token?: string) {
  return apiRequest<FiscalIntegrityCheck>("/fiscal/integrity-checks", {
    method: "POST",
    token
  });
}

export function createFiscalIntegrityJob(token?: string, signal?: AbortSignal) {
  return apiRequest<FiscalIntegrityJob>("/fiscal/integrity-jobs", {
    method: "POST", token, signal
  });
}

export function loadFiscalIntegrityJobs(token?: string, page = 0, size = 20, signal?: AbortSignal) {
  const params = new URLSearchParams({ page: String(Math.max(0, page)), size: String(Math.min(50, Math.max(1, size))) });
  return apiRequest<{ content: FiscalIntegrityJob[]; totalElements: number; totalPages: number; number: number; size: number }>(
    `/fiscal/integrity-jobs?${params.toString()}`, { token, signal });
}

export function loadFiscalIntegrityJobStatus(id: string, token?: string, signal?: AbortSignal) {
  return apiRequest<FiscalIntegrityJob>(`/fiscal/integrity-jobs/${encodeURIComponent(id)}`, { token, signal });
}

export function retryFiscalIntegrityJob(id: string, token?: string, signal?: AbortSignal) {
  return apiRequest<FiscalIntegrityJob>(`/fiscal/integrity-jobs/${encodeURIComponent(id)}/retry`, {
    method: "POST", token, signal
  });
}

export function createFiscalExport(
  kind: FiscalExportKind,
  periodStart: string | null,
  periodEnd: string | null,
  token?: string,
  scope?: FiscalExportScope
) {
  return apiRequest<FiscalExport>("/fiscal/exports", {
    method: "POST",
    token,
    body: fiscalExportBody(kind, periodStart, periodEnd, scope)
  });
}

export function createFiscalExportJob(
  request: FiscalExportJobRequest,
  token?: string,
  signal?: AbortSignal
) {
  return apiRequest<FiscalExportJob>("/fiscal/export-jobs", {
    method: "POST",
    token,
    signal,
    body: fiscalExportJobBody(request)
  });
}

export function loadFiscalExportJobs(
  token?: string,
  page = 0,
  size = 20,
  signal?: AbortSignal
) {
  const params = new URLSearchParams({ page: String(Math.max(0, page)), size: String(Math.min(100, Math.max(1, size))) });
  return apiRequest<{ content: FiscalExportJob[]; totalElements: number; totalPages: number; number: number; size: number }>(
    `/fiscal/export-jobs?${params.toString()}`,
    { token, signal }
  );
}

export function loadFiscalExportJobStatus(id: string, token?: string, signal?: AbortSignal) {
  return apiRequest<FiscalExportJob>(`/fiscal/export-jobs/${encodeURIComponent(id)}`, { token, signal });
}

export function retryFiscalExportJob(id: string, token?: string, signal?: AbortSignal) {
  return apiRequest<FiscalExportJob>(`/fiscal/export-jobs/${encodeURIComponent(id)}/retry`, {
    method: "POST",
    token,
    signal
  });
}

export async function downloadFiscalExportJob(id: string, token?: string, signal?: AbortSignal) {
  const response = await fetch(`${apiBaseUrl}/fiscal/export-jobs/${encodeURIComponent(id)}/download`, {
    method: "GET",
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    signal
  });
  if (!response.ok) throw new Error(`fiscal_export_job_${response.status}`);
  return response.blob();
}

export async function downloadFiscalExportZip(
  kind: FiscalExportKind,
  periodStart: string | null,
  periodEnd: string | null,
  token?: string,
  scope?: FiscalExportScope
) {
  const response = await fetch(`${apiBaseUrl}/fiscal/exports/download`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(fiscalExportBody(kind, periodStart, periodEnd, scope))
  });
  if (!response.ok) throw new Error(`fiscal_export_${response.status}`);
  return response.blob();
}

function fiscalExportBody(
  kind: FiscalExportKind,
  periodStart: string | null,
  periodEnd: string | null,
  scope?: FiscalExportScope
) {
  return {
    kind, periodStart, periodEnd,
    ...(scope?.recordIds?.length ? { recordIds: scope.recordIds } : {}),
    ...(scope?.dateFrom ? { dateFrom: scope.dateFrom } : {}),
    ...(scope?.dateTo ? { dateTo: scope.dateTo } : {}),
    ...(scope?.documentNumber?.trim() ? { documentNumber: scope.documentNumber.trim() } : {}),
    ...(scope?.documentNumberPrefix?.trim() ? { documentNumberPrefix: scope.documentNumberPrefix.trim() } : {}),
    ...(scope?.operation ? { operation: scope.operation } : {}),
    ...(scope?.documentType ? { documentType: scope.documentType } : {}),
    ...(scope?.fiscalMode ? { fiscalMode: scope.fiscalMode } : {})
  };
}

function fiscalExportJobBody(request: FiscalExportJobRequest) {
  return {
    kind: request.kind,
    scope: request.scope,
    periodStart: request.periodStart ?? null,
    periodEnd: request.periodEnd ?? null,
    recordIds: request.recordIds ?? [],
    ...(request.dateFrom ? { dateFrom: request.dateFrom } : {}),
    ...(request.dateTo ? { dateTo: request.dateTo } : {}),
    ...(request.documentNumber?.trim() ? { documentNumber: request.documentNumber.trim() } : {}),
    ...(request.documentNumberPrefix?.trim() ? { documentNumberPrefix: request.documentNumberPrefix.trim() } : {}),
    ...(request.operation ? { operation: request.operation } : {}),
    ...(request.documentType ? { documentType: request.documentType } : {}),
    ...(request.fiscalMode ? { fiscalMode: request.fiscalMode } : {})
  };
}

export function loadFiscalExportHistory(token?: string, limit = 100) {
  return apiRequest<FiscalExportHistory[]>(`/fiscal/exports?limit=${limit}`, { token });
}

export function loadFiscalExportHistoryCursor(
  token?: string,
  size = 50,
  cursor?: string | null,
  signal?: AbortSignal
) {
  const params = new URLSearchParams({ size: String(Math.min(100, Math.max(1, size))) });
  if (cursor) params.set("cursor", cursor);
  return apiRequest<FiscalHistoryCursorPage<FiscalExportHistory>>(
    `/fiscal/exports/cursor?${params.toString()}`,
    { token, signal }
  );
}

export function registerFiscalRequiredSubmission(reference: string, token?: string) {
  return apiRequest<FiscalRequiredSubmission>("/fiscal/required-submissions", {
    method: "POST",
    token,
    body: { reference: reference.trim() }
  });
}

export function loadFiscalRequiredSubmissions(token?: string, limit = 100) {
  return apiRequest<FiscalRequiredSubmissionHistory[]>(
    `/fiscal/required-submissions?limit=${limit}`,
    { token }
  );
}

export function loadFiscalRequiredSubmissionsCursor(
  token?: string,
  size = 50,
  cursor?: string | null,
  signal?: AbortSignal
) {
  const params = new URLSearchParams({ size: String(Math.min(100, Math.max(1, size))) });
  if (cursor) params.set("cursor", cursor);
  return apiRequest<FiscalHistoryCursorPage<FiscalRequiredSubmissionHistory>>(
    `/fiscal/required-submissions/cursor?${params.toString()}`,
    { token, signal }
  );
}

export function createFiscalRequiredSubmissionExportJob(
  id: string,
  periodStart: string,
  periodEnd: string,
  token?: string,
  signal?: AbortSignal
) {
  return apiRequest<FiscalExportJob>(
    `/fiscal/required-submissions/${encodeURIComponent(id)}/export-jobs`,
    {
      method: "POST",
      token,
      signal,
      body: { periodStart, periodEnd }
    }
  );
}

export function exportFiscalRequiredSubmission(
  id: string,
  kind: FiscalExportKind,
  periodStart: string,
  periodEnd: string,
  token?: string
) {
  return apiRequest<FiscalRequiredSubmissionExport>(
    `/fiscal/required-submissions/${encodeURIComponent(id)}/exports`,
    {
      method: "POST",
      token,
      body: { kind, periodStart, periodEnd }
    }
  );
}

export function loadFiscalSandboxStatus(token?: string) {
  return apiRequest<FiscalSandboxStatus>("/dev/fiscal-sandbox/status", { token });
}

export function setFiscalSandboxScenario(outcome: FiscalSandboxStatus["nextOutcome"], token?: string) {
  return apiRequest<FiscalSandboxStatus>("/dev/fiscal-sandbox/scenario", {
    method: "PUT", token, body: { outcome }
  });
}

export function dispatchFiscalSandboxNext(token?: string) {
  return apiRequest<unknown>("/dev/fiscal-sandbox/dispatch-next", { method: "POST", token });
}

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
  if (filters.sortBy) params.set("sortBy", filters.sortBy);
  if (filters.sortDirection) params.set("sortDirection", filters.sortDirection);
  return params;
}

function optionalText(value: string | null | undefined) {
  const normalized = value?.trim();
  return normalized ? normalized : null;
}
