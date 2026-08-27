import { apiRequest } from "@tpverp/app-common";

export type LicenseSaasStatus =
  | "VALIDA"
  | "BLOQUEADA_MANUAL"
  | "CADUCADA"
  | "REQUIERE_ACTUALIZACION";

export type LicenseHistoryItem = {
  reference: string;
  validFrom: string;
  validUntil: string;
  maxWindows: number;
  maxPda: number;
  taxId: string;
  taxpayerType: string;
  impuestos: string;
  commercialProfile: string;
  active: boolean;
  saasStatus?: LicenseSaasStatus;
  lastSaasValidationAt?: string | null;
  verifactuActivationDate?: string | null;
  verifactuPolicyVersion?: number | null;
  verifactuPolicyUpdatedAt?: string | null;
  licenseVersion?: number | null;
};

export type LicenseSaasLinkResult = {
  licenseReference: string;
  companyId: string;
  storeId: string;
  serverTerminalId: string;
  validUntil: string;
  status: LicenseSaasStatus;
  maxWindows: number;
  maxPda: number;
};

export type LicenseSaasValidationResult = {
  status: LicenseSaasStatus;
  validUntil: string;
  verifactuActivationDate?: string | null;
  verifactuPolicyVersion?: number | null;
  verifactuPolicyUpdatedAt?: string | null;
  commercialProfile?: string | null;
  maxWindows: number;
  maxPda: number;
  licenseVersion: number;
};

export function loadLicenseHistory(
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  return request<LicenseHistoryItem[]>("/licenses", { token });
}

export function linkSaasLicense(
  pairingCode: string,
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  return request<LicenseSaasLinkResult>("/licenses/link-saas", {
    method: "POST",
    token,
    body: { pairingCode: pairingCode.trim() },
  });
}

export function validateSaasLicense(
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  return request<LicenseSaasValidationResult>("/licenses/validate-saas", {
    method: "POST",
    token,
  });
}
