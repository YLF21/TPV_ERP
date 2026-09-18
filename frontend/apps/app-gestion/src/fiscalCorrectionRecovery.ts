import type { VerifactuCorrectionRequest } from "./verifactuManagementApi";

type StoredAttempt = { version: 1; idempotencyKey: string; payloadFingerprint: string };
export type FiscalCorrectionAttempt = StoredAttempt & { storageKey: string; newAttempt: boolean };

async function fingerprint(value: string) {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function storageKey(scope: string, recordId: string) {
  if (!scope || !recordId) throw new Error("fiscal_correction_recovery_unavailable");
  return `tpv.fiscal-correction.v1.${await fingerprint(JSON.stringify([scope, recordId]))}`;
}

function read(key: string): StoredAttempt | null {
  const raw = sessionStorage.getItem(key);
  if (raw === null) return null;
  const value = JSON.parse(raw) as Partial<StoredAttempt>;
  if (value?.version !== 1 || !/^[0-9a-f-]{36}$/i.test(value.idempotencyKey ?? "")
      || !/^[0-9a-f]{64}$/.test(value.payloadFingerprint ?? "")) {
    // Never overwrite a recovery record whose identity cannot be established.
    throw new Error("fiscal_correction_recovery_unavailable");
  }
  return value as StoredAttempt;
}

export async function loadFiscalCorrectionAttempt(scope: string, recordId: string) {
  const key = await storageKey(scope, recordId);
  const stored = read(key);
  return stored ? { ...stored, storageKey: key, newAttempt: false } : null;
}

export async function reserveFiscalCorrectionAttempt(scope: string, recordId: string, draft: VerifactuCorrectionRequest): Promise<FiscalCorrectionAttempt> {
  const [key, payloadFingerprint] = await Promise.all([
    storageKey(scope, recordId),
    fingerprint(JSON.stringify([
      draft.reason.trim(), draft.recipientTaxId?.trim() || null,
      draft.recipientName?.trim() || null, draft.operationDescription?.trim() || null
    ]))
  ]);
  const stored = read(key);
  if (stored) {
    if (stored.payloadFingerprint !== payloadFingerprint) throw new Error("fiscal_correction_pending_payload");
    return { ...stored, storageKey: key, newAttempt: false };
  }
  const attempt: StoredAttempt = { version: 1, idempotencyKey: crypto.randomUUID(), payloadFingerprint };
  // Persist before sending. Only hashes and a random key are stored, never the
  // recipient, reason, document number, auth token or operator's identity.
  sessionStorage.setItem(key, JSON.stringify(attempt));
  return { ...attempt, storageKey: key, newAttempt: true };
}

export function clearFiscalCorrectionAttempt(attempt: FiscalCorrectionAttempt) {
  if (read(attempt.storageKey)?.idempotencyKey === attempt.idempotencyKey) {
    sessionStorage.removeItem(attempt.storageKey);
  }
}
