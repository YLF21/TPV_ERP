import { useState } from "react";
import { useRemote } from "../../app/RefreshContext";
import { useWorkspaceLabels } from "../../i18n/workspace";
import { api, request } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import { COMPANY_PRIVILEGES, workspaceApi, type AccessCompany, type CompanyPrivilege } from "../../lib/workspace-api";
import type { Notice } from "../../shared/types";
import { LoadState } from "../../shared/workspace-ui";
import { useClientUserLabels } from "./client-user-labels";
import { StoreAccessPicker } from "./StoreAccessPicker";

type UpdateAccess = { roleName: string; companyPrivileges: CompanyPrivilege[]; storeIds: string[] };

export function TenantAccessEditor({ credentials, username, disabled, onNotice, onChanged, runMutation }: {
  credentials: Credentials;
  username: string;
  disabled: boolean;
  onNotice: (notice: Notice) => void;
  onChanged: () => void;
  runMutation: (operation: () => Promise<void>) => Promise<void>;
}) {
  const l = useWorkspaceLabels();
  const labels = useClientUserLabels();
  const [selectedCompany, setSelectedCompany] = useState<string | null>(null);
  const companies = useRemote(() => api.companies(credentials), [credentials.accessToken]);
  const access = useRemote(() => workspaceApi.userAccess(credentials, username), [credentials.accessToken, username]);
  const companyId = selectedCompany ?? access.data?.companies[0]?.companyId ?? "";
  const membership = access.data?.companies.find(company => company.companyId === companyId);
  const companyReady = companies.data?.some(company => company.companyId === companyId) ?? false;

  function updateAccess(body?: UpdateAccess) {
    if (disabled || !companyReady || !access.data) return;
    void runMutation(async () => {
      await request(credentials,
        `/api/v1/admin/tenant-users/${encodeURIComponent(username)}/access/companies/${encodeURIComponent(companyId)}`,
        body ? { method: "PUT", body } : { method: "DELETE" });
      setSelectedCompany(companyId);
      access.reload();
      onChanged();
      onNotice({ type: "success", text: l("saved") });
    });
  }

  return <section className="client-user-panel">
    <h3>{l("access")}</h3>
    <LoadState {...access} />
    <LoadState {...companies} />
    {access.data && <>
      <p className="module-help">{access.data.companies.map(company => `${company.companyName} (${company.stores.length})`).join(" · ") || labels("noCompanies")}</p>
      <div className="toolbar">
        <label>{l("company")}
          <select className="control-input" aria-label={l("company")} value={companyReady ? companyId : ""} onChange={event => setSelectedCompany(event.target.value)}
            disabled={disabled || !companies.data}>
            <option value="">{l("selectCompany")}</option>
            {companies.data?.map(company => <option key={company.companyId} value={company.companyId}>{company.companyName}</option>)}
          </select>
        </label>
      </div>
      {companyReady && <CompanyAccessEditor key={companyId} credentials={credentials} companyId={companyId}
        membership={membership} disabled={disabled} onSave={updateAccess} onRevoke={() => updateAccess()} />}
    </>}
  </section>;
}

function CompanyAccessEditor({ credentials, companyId, membership, disabled, onSave, onRevoke }: {
  credentials: Credentials;
  companyId: string;
  membership?: AccessCompany;
  disabled: boolean;
  onSave: (update: UpdateAccess) => void;
  onRevoke: () => void;
}) {
  const l = useWorkspaceLabels();
  const labels = useClientUserLabels();
  const [roleName, setRoleName] = useState(membership?.roleName ?? "VIEWER");
  const [privileges, setPrivileges] = useState(membership?.companyPrivileges ?? []);
  const [stores, setStores] = useState<AccessCompany["stores"]>(membership?.stores ?? []);
  const canWriteMasters = ["OWNER", "MANAGER"].includes(roleName);

  function togglePrivilege(privilege: CompanyPrivilege, included: boolean) {
    setPrivileges(current => {
      const next = new Set(current);
      if (included) next.add(privilege);
      else next.delete(privilege);
      if (privilege === "WRITE_MASTERS" && included) next.add("READ_MASTERS");
      if (privilege === "READ_MASTERS" && !included) next.delete("WRITE_MASTERS");
      return [...next];
    });
  }

  function removeStore(id: string) {
    setStores(current => current.filter(store => store.storeId !== id));
  }

  return <>
    <div className="toolbar">
      <label>{l("role")}
        <select className="control-input" disabled={disabled} value={roleName} onChange={event => {
          const role = event.target.value;
          setRoleName(role);
          if (!["OWNER", "MANAGER"].includes(role)) setPrivileges(current => current.filter(privilege => privilege !== "WRITE_MASTERS"));
        }}>
          {(membership?.roleName === "OWNER" ? ["OWNER", "MANAGER", "VIEWER", "BILLING"] : ["MANAGER", "VIEWER", "BILLING"])
            .map(role => <option key={role}>{role}</option>)}
        </select>
      </label>
    </div>
    <fieldset disabled={disabled}>
      <legend>{l("privileges")}</legend>
      {COMPANY_PRIVILEGES.map(privilege => <label className="access-option" key={privilege}>
        <input type="checkbox" checked={privileges.includes(privilege)} disabled={privilege === "WRITE_MASTERS" && !canWriteMasters}
          onChange={event => togglePrivilege(privilege, event.target.checked)} />
        {l(privilege)}
      </label>)}
    </fieldset>
    <h4>{labels("selectedStores")} ({stores.length})</h4>
    {stores.length > 0 && <ul className="client-user-grants">
      {stores.map(store => <li key={store.storeId}>{store.code} · {store.name}
        <button type="button" className="small-button" aria-label={`${labels("removeStore")} ${store.code} · ${store.name}`}
          disabled={disabled} onClick={() => removeStore(store.storeId)}>{labels("removeStore")}</button>
      </li>)}
    </ul>}
    {stores.length > 0 && <button type="button" className="small-button" disabled={disabled} onClick={() => setStores([])}>{labels("clearStores")}</button>}
    <StoreAccessPicker credentials={credentials} companyId={companyId} disabled={disabled}
      selectedIds={new Set(stores.map(store => store.storeId))} onToggle={(store, included) => {
        if (included) setStores(current => [...current, { storeId: store.id, code: store.code, name: store.name }]);
        else removeStore(store.id);
      }} />
    <div className="form-actions">
      <button className="primary-button" type="button" disabled={disabled}
        onClick={() => onSave({ roleName, companyPrivileges: privileges, storeIds: stores.map(store => store.storeId) })}>{l("save")}</button>
      {membership && <button className="small-button danger" type="button" disabled={disabled}
        onClick={() => { if (window.confirm(l("confirmRevoke"))) onRevoke(); }}>{l("revoke")}</button>}
    </div>
  </>;
}
