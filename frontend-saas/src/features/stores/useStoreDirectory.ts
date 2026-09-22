import type { Credentials } from "../../lib/types";
import { workspaceApi, type StoreRow } from "../../lib/workspace-api";
import { usePagedDirectory } from "../../shared/usePagedDirectory";

type StoreFilters = { companyId: string; q: string; active: string; sortBy: string; sortDirection: "ASC" | "DESC" };

export function useStoreDirectory(credentials: Credentials, filters: StoreFilters) {
  const scopedCredentials = { ...credentials };
  const scopedFilters = { ...filters };
  return usePagedDirectory<StoreRow>(
    page => workspaceApi.stores(scopedCredentials, { ...scopedFilters, page, size: 25 }),
    [
      credentials.accessToken, credentials.companyId, credentials.username, credentials.mode,
      filters.companyId, filters.q, filters.active, filters.sortBy, filters.sortDirection,
    ],
  );
}
