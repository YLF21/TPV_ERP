import { useState } from "react";
import { useRemote } from "../../app/RefreshContext";
import { useWorkspaceLabels } from "../../i18n/workspace";
import type { Credentials } from "../../lib/types";
import { workspaceApi, type StoreRow } from "../../lib/workspace-api";
import { Input } from "../../shared/ui";
import { LoadState, PageButtons } from "../../shared/workspace-ui";
import { useClientUserLabels } from "./client-user-labels";

export function StoreAccessPicker({ credentials, companyId, selectedIds, onToggle, disabled, activeOnly = false }: {
  credentials: Credentials;
  companyId: string;
  selectedIds: ReadonlySet<string>;
  onToggle: (store: StoreRow, included: boolean) => void;
  disabled: boolean;
  activeOnly?: boolean;
}) {
  const l = useWorkspaceLabels();
  const labels = useClientUserLabels();
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const stores = useRemote(() => workspaceApi.stores(credentials, {
    companyId, q: q.trim(), page, size: 25, sortBy: "name", ...(activeOnly ? { active: true } : {}),
  }), [credentials.accessToken, companyId, q.trim(), page, activeOnly]);

  return <>
    <div className="toolbar">
      <Input label={labels("searchStores")} type="search" value={q} maxLength={160} disabled={disabled}
        onChange={value => { setQ(value); setPage(0); }} />
    </div>
    <LoadState {...stores} />
    <div className="table-wrap">
      <table aria-label={l("stores")}>
        <thead><tr><th>{labels("openAccess")}</th><th>{l("internalCode")}</th><th>{l("store")}</th><th>{l("status")}</th></tr></thead>
        <tbody>
          {stores.data?.items.map(store => <tr key={store.id}>
            <td><input type="checkbox" aria-label={`${labels("openAccess")} ${store.code} · ${store.name}`}
              disabled={disabled} checked={selectedIds.has(store.id)}
              onChange={event => onToggle(store, event.target.checked)} /></td>
            <td>{store.internalCode ?? l("codePending")}</td>
            <td>{store.code} · {store.name}</td>
            <td>{store.active ? l("active") : l("inactive")}</td>
          </tr>)}
          {stores.data?.items.length === 0 && <tr><td colSpan={4}>{labels("noStores")}</td></tr>}
        </tbody>
      </table>
    </div>
    {stores.data && stores.data.totalPages > 1 && <PageButtons page={page} totalPages={stores.data.totalPages} onPage={setPage} />}
  </>;
}
