




export type View = "dashboard" | "companies" | "stores" | "create-license" | "failures" | "access" | "integrations" | "licenses" | "sync" | "fiscal" | "fiscal-policy" | "users" | "audit" | "support" | "health" | "billing" | "outbox" | "reports";

export type Notice = { type: "success" | "error"; text: string } | null;

export type LicenseAction = "block" | "unblock" | "pairing";

export type LicenseWorkspaceSection = "companies" | "licenses" | "verifactu";

export type GlobalSearchCriterion = "company" | "store" | "taxId";

export type GlobalSearchSuggestion = { key: string; value: string; label: string; detail: string };

export type SaasAdminRoleName = "ADMIN" | "VIEWER" | "SUPPORT" | "BILLING" | "AUDITOR";

export type TenantAssignableRoleName = "MANAGER" | "VIEWER" | "BILLING";

export type AuthMode = "admin" | "tenant";

export type MasterMode = "customers" | "products" | "suppliers" | "warehouses";
