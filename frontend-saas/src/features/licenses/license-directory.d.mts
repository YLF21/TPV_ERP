export type LicenseExpiryShortcut = { days: 7 | 30; before: string };
export type LicenseDirectoryFilters = {
  companyId: string;
  storeId: string;
  q: string;
  status: "" | "VALIDA" | "CADUCADA" | "BLOQUEADA_MANUAL";
  expiresBefore: string;
  upcoming: LicenseExpiryShortcut | null;
  hasConnections: string;
  billingStatus: string;
  sort: { key: string; direction: "asc" | "desc" };
};
export function readLicenseDirectoryFilters(hash?: string): LicenseDirectoryFilters;
export function licenseExpiryShortcut(days: 7 | 30, now?: Date): LicenseExpiryShortcut;
