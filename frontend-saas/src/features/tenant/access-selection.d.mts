export function resolveCompanySelection(companies: readonly { companyId: string }[], selectedId: string): string;
export function authorizedStoreSelection(stores: readonly { storeId: string }[], selectedIds: readonly string[]): string[];
export function canWriteTenantMasters(roleName: string, privileges: readonly string[]): boolean;
