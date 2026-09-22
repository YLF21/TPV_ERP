/** A single granted company can be selected implicitly; multiple companies require a choice. */
export function resolveCompanySelection(companies, selectedId) {
  if (companies.some(company => company.companyId === selectedId)) return selectedId;
  return companies.length === 1 ? companies[0].companyId : "";
}

/** Never keep a store selection after its grant disappears, including on a refresh. */
export function authorizedStoreSelection(stores, selectedIds) {
  const allowed = new Set(stores.map(store => store.storeId));
  return [...new Set(selectedIds)].filter(id => allowed.has(id));
}

export function canWriteTenantMasters(roleName, privileges) {
  return ["OWNER", "MANAGER"].includes(roleName)
    && privileges.includes("READ_MASTERS") && privileges.includes("WRITE_MASTERS");
}
