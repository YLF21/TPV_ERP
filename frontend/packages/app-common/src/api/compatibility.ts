import { apiRequest } from "./client";
import { frontendVersion } from "./runtime";

export const REQUIRED_BACKEND_CAPABILITIES = [
  "PAYMENT_IDEMPOTENCY",
  "PAYMENT_RECOVERY",
  "PAYMENT_STATUS_QUERY",
  "PAYMENT_VOID",
  "PAYMENT_REFUND",
  "PAYMENT_RECONCILIATION",
  "CORRELATION_ID"
] as const;

export type BackendCompatibility = {
  backendVersion: string;
  apiVersion: string;
  minimumFrontendVersion: string;
  capabilities: string[];
  paymentStates: Record<string, string>;
};

export type CompatibilityEvaluation = {
  compatible: boolean;
  frontendVersion: string;
  missingCapabilities: string[];
  reason?: "API_VERSION" | "FRONTEND_TOO_OLD" | "MISSING_CAPABILITIES";
};

export class InvalidCompatibilityContractError extends Error {
  constructor() { super("invalid_backend_compatibility_contract"); }
}

export async function loadBackendCompatibility(token?: string): Promise<BackendCompatibility> {
  const value = await apiRequest<unknown>("/system/compatibility", { token });
  if (!isBackendCompatibility(value)) throw new InvalidCompatibilityContractError();
  return value;
}

function isBackendCompatibility(value: unknown): value is BackendCompatibility {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<BackendCompatibility>;
  return typeof candidate.backendVersion === "string"
    && typeof candidate.apiVersion === "string"
    && typeof candidate.minimumFrontendVersion === "string"
    && Array.isArray(candidate.capabilities)
    && candidate.capabilities.every(capability => typeof capability === "string")
    && candidate.paymentStates !== null
    && typeof candidate.paymentStates === "object";
}

export function evaluateCompatibility(
  backend: BackendCompatibility,
  currentFrontendVersion = frontendVersion
): CompatibilityEvaluation {
  const missingCapabilities = REQUIRED_BACKEND_CAPABILITIES.filter(
    capability => !backend.capabilities.includes(capability)
  );
  const reason = backend.apiVersion !== "1"
    ? "API_VERSION"
    : compareVersions(currentFrontendVersion, backend.minimumFrontendVersion) < 0
      ? "FRONTEND_TOO_OLD"
      : missingCapabilities.length > 0
        ? "MISSING_CAPABILITIES"
        : undefined;
  return { compatible: reason === undefined, frontendVersion: currentFrontendVersion, missingCapabilities, reason };
}

function compareVersions(left: string, right: string): number {
  const parts = (value: string) => value.split("-")[0].split(".").map(part => Number.parseInt(part, 10) || 0);
  const a = parts(left);
  const b = parts(right);
  for (let index = 0; index < Math.max(a.length, b.length); index += 1) {
    const difference = (a[index] ?? 0) - (b[index] ?? 0);
    if (difference !== 0) return Math.sign(difference);
  }
  return 0;
}
