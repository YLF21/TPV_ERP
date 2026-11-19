import { addDays } from "../../lib/utc-date-time.mjs";

export function readLicenseDirectoryFilters(hash = window.location.hash) {
  const [, query] = hash.split("?", 2);
  const params = new URLSearchParams(query);
  const status = params.get("status");
  return {
    companyId: params.get("companyId") || "", storeId: params.get("storeId") || "", q: "",
    status: status === "" || status === "CADUCADA" || status === "BLOQUEADA_MANUAL" ? status : "VALIDA",
    expiresBefore: "", upcoming: null, hasConnections: "", billingStatus: "",
    sort: { key: "validUntil", direction: "asc" },
  };
}

export function licenseExpiryShortcut(days, now = new Date()) {
  return { days, before: addDays(now, days).toISOString() };
}
