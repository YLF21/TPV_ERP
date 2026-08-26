import { apiRequest } from "@tpverp/app-common";

export type FiscalRecordMode = "PRE_SIF" | "NO_VERIFACTU" | "VERIFACTU" | string;
export type FiscalRecordOperation = "ALTA" | "ANULACION" | string;
export type FiscalRecordDocumentType = "F1" | "F2" | "F3" | "R1" | "R2" | "R3" | "R4" | "R5" | string;
export type FiscalRecordSubmissionStatus = "PENDIENTE" | "ENVIANDO" | "ENVIADO" | "ACEPTADO" | "ACEPTADO_CON_ERRORES" | "RECHAZADO" | "DEFECTUOSO" | "SUBSANADO" | string;

export type FiscalRecordFilters = {
  dateFrom: string;
  dateTo: string;
  number: string;
  numberMatch?: "EXACT" | "PREFIX";
  operation: "" | FiscalRecordOperation;
  documentType: "" | FiscalRecordDocumentType;
  fiscalMode: "" | FiscalRecordMode;
  page: number;
  size: number;
};

/** Cursor-based filters for the append-only fiscal-record catalogue. */
export type FiscalRecordCursorFilters = {
  dateFrom: string;
  dateTo: string;
  number: string;
  numberMatch?: "EXACT" | "PREFIX";
  operation: "" | FiscalRecordOperation;
  documentType: "" | FiscalRecordDocumentType;
  fiscalMode: "" | FiscalRecordMode;
  size: number;
  cursor?: string | null;
};

export type FiscalRecordListItem = {
  recordId: string;
  installationId: string;
  storeId: string;
  documentId?: string | null;
  sequence: number;
  operation: FiscalRecordOperation;
  documentType: FiscalRecordDocumentType;
  number: string;
  issueDate: string;
  generatedAt: string;
  fiscalMode: FiscalRecordMode;
  totalTax?: number | string | null;
  totalAmount?: number | string | null;
  previousHash?: string | null;
  hash: string;
  submissionStatus?: FiscalRecordSubmissionStatus | null;
  submissionUpdatedAt?: string | null;
};

export type FiscalRecordPage = {
  items: FiscalRecordListItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type FiscalRecordCursorPage = {
  items: FiscalRecordListItem[];
  size: number;
  nextCursor?: string | null;
  previousCursor?: string | null;
  hasNext: boolean;
  hasPrevious: boolean;
  snapshotSequence: number;
};

export type FiscalRecordDocument = {
  id: string;
  storeId: string;
  type: string;
  status: string;
  number: string;
  issueDate: string;
  createdAt?: string | null;
  confirmedAt?: string | null;
  cancelledAt?: string | null;
};

export type FiscalRecordArtifact = {
  fiscalMode: FiscalRecordMode;
  environment: "TEST" | "PRODUCTION" | string;
  sandbox: boolean;
  systemVersionId?: string | null;
  issuerName?: string | null;
  issuerTaxId?: string | null;
  xmlHash?: string | null;
  qrUrl?: string | null;
  qrHash?: string | null;
  createdAt?: string | null;
};

export type FiscalRecordRelation = {
  relatedRecordId: string;
  type: string;
};

export type FiscalRecordSubmission = {
  status: FiscalRecordSubmissionStatus;
  updatedAt?: string | null;
  errorCode?: string | null;
};

export type FiscalRecordDetail = FiscalRecordListItem & {
  chainId: string;
  companyId: string;
  timezone: string;
  issuerTaxId: string;
  snapshotHash: string;
  formatVersion: string;
  algorithmVersion: string;
  applicationVersion: string;
  previousRecordId?: string | null;
  nextRecordId?: string | null;
  document?: FiscalRecordDocument | null;
  artifact?: FiscalRecordArtifact | null;
  relations: FiscalRecordRelation[];
  submission?: FiscalRecordSubmission | null;
  adjacentChainStatus?: "ADJACENT_VALID" | "ADJACENT_ANOMALOUS" | "ADJACENT_UNAVAILABLE" | string | null;
};

const endpoint = "/verifactu/admin/records";

function recordFilterParams(filters: FiscalRecordCursorFilters | FiscalRecordFilters) {
  const params = new URLSearchParams({ size: String(filters.size) });
  if (filters.dateFrom) params.set("dateFrom", filters.dateFrom);
  if (filters.dateTo) params.set("dateTo", filters.dateTo);
  if (filters.number.trim()) params.set("number", filters.number.trim());
  if (filters.number.trim()) params.set("numberMatch", filters.numberMatch ?? "PREFIX");
  if (filters.operation) params.set("operation", filters.operation);
  if (filters.documentType) params.set("documentType", filters.documentType);
  if (filters.fiscalMode) params.set("fiscalMode", filters.fiscalMode);
  return params;
}

export function loadFiscalRecords(filters: FiscalRecordFilters, token?: string, signal?: AbortSignal) {
  const params = recordFilterParams(filters);
  params.set("page", String(filters.page));
  return apiRequest<FiscalRecordPage>(`${endpoint}?${params.toString()}`, { token, signal });
}

export function loadFiscalRecordsCursor(
  filters: FiscalRecordCursorFilters,
  token?: string,
  signal?: AbortSignal
) {
  const params = recordFilterParams(filters);
  if (filters.cursor) params.set("cursor", filters.cursor);
  return apiRequest<FiscalRecordCursorPage>(`${endpoint}/cursor?${params.toString()}`, { token, signal });
}

export function loadFiscalRecord(recordId: string, token?: string, signal?: AbortSignal) {
  return apiRequest<FiscalRecordDetail>(`${endpoint}/${encodeURIComponent(recordId)}`, { token, signal });
}
