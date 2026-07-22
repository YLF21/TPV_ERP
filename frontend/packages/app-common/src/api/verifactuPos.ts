import { apiRequest } from "./client";

export type VerifactuPosPresentationStatus =
  | "INACTIVO"
  | "OPERATIVO"
  | "PENDIENTES"
  | "ENVIANDO"
  | "REQUIERE_REVISION"
  | "DESCONOCIDO";

export type VerifactuSubmissionStatus =
  | "PENDIENTE"
  | "ENVIANDO"
  | "ENVIADO"
  | "RECHAZADO"
  | "DEFECTUOSO"
  | "ACEPTADO_CON_ERRORES";

export type VerifactuPosStatus = {
  active: boolean;
  presentationStatus: VerifactuPosPresentationStatus;
  pendingCount: number;
  sendingCount: number;
  reviewRequiredCount: number;
};

export type VerifactuPosQueueItem = {
  documentNumber: string;
  documentType: string;
  submissionStatus: VerifactuSubmissionStatus;
  updatedAt: string;
  operationalMessageCode?: string | null;
};

export const VERIFACTU_POS_QUEUE_LIMIT = 50;

export function getVerifactuPosStatus(token: string) {
  return apiRequest<VerifactuPosStatus>("/verifactu/pos/status", { token });
}

export function getVerifactuPosQueue(
  token: string,
  limit = VERIFACTU_POS_QUEUE_LIMIT
) {
  const safeLimit = Math.max(1, Math.min(Math.trunc(limit), VERIFACTU_POS_QUEUE_LIMIT));
  return apiRequest<VerifactuPosQueueItem[]>(
    `/verifactu/pos/queue?limit=${safeLimit}`,
    { token }
  );
}
