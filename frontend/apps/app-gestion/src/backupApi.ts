import { apiRequest } from "@tpverp/app-common";

export type BackupManifest = {
  capturedAt?: string;
  installationId?: string;
  applicationVersion?: string;
  portableSecretsVersion?: number;
  portableSecretsComplete?: boolean;
  portableSecretsWarnings?: string[];
  tableCounts?: Record<string, number>;
  categories?: Record<string, number>;
  lastDocuments?: Array<{ type: string; storeId?: string; storeCode?: string; series?: string;
    period?: string; documentId?: string; number?: string; date?: string; confirmedAt?: string }>;
  documentsByType?: Record<string, number>;
  documentCounters?: Array<{ storeId: string; type: string; period: string; lastSequence: number }>;
};

export type BackupItem = {
  id: string;
  sequenceNumber: number | null;
  startedAt: string;
  finishedAt?: string | null;
  result: "EN_CURSO" | "EXITO" | "FALLO";
  metadata?: { manifest?: BackupManifest; verifiedAt?: string } | null;
  errorReason?: string | null;
  sizeBytes?: number | null;
  available: boolean;
  origin: "MANUAL" | "AUTOMATIC" | "TRANSFER" | "LEGACY";
  phase?: string | null;
  cloud?: { state: "PENDING" | "UPLOADING" | "SENT" | "RETIRED" | "FAILED";
    attempts: number; uploadedAt?: string | null; nextAttemptAt?: string | null; errorReason?: string | null } | null;
};

export type BackupHistory = {
  items: BackupItem[];
  total: number;
  page: number;
  size: number;
  lastSuccessfulAt?: string | null;
  nextScheduledAt?: string | null;
  overdue: boolean;
  configured: boolean;
};

export type BackupConfiguration = {
  time: string;
  dailyRetention: number;
  monthlyRetention: number;
  active: boolean;
  directory: string;
  configured: boolean;
};

export const loadBackupHistory = (page: number, token?: string) =>
  apiRequest<BackupHistory>(`/backups?page=${page}&size=50`, { token });
export const loadBackupDetail = (id: string, token?: string) =>
  apiRequest<BackupItem>(`/backups/${encodeURIComponent(id)}`, { token });
export const loadBackupConfiguration = (token?: string) =>
  apiRequest<BackupConfiguration>("/backups/configuration", { token });
export const runBackup = (token?: string) =>
  apiRequest<{ id: string }>("/backups/run", { method: "POST", token });
export const retryBackupCloudUpload = (id: string, token?: string) =>
  apiRequest<void>(`/backups/${encodeURIComponent(id)}/cloud/retry`, { method: "POST", token });
export const saveBackupConfiguration = (value: Omit<BackupConfiguration, "configured"> & {
  adminPassword: string; recoverySecret: string;
}, token?: string) =>
  apiRequest<BackupConfiguration>("/backups/configuration", { method: "PUT", body: value, token });
