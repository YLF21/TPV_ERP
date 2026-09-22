import { FormEvent, useEffect, useState } from "react";
import { useRemote } from "../../app/RefreshContext";
import { api, request } from "../../lib/api";
import {
  COMPANY_PRIVILEGES,
  CompanyPrivilege,
  workspaceApi,
} from "../../lib/workspace-api";
import { Credentials } from "../../lib/types";
import { useWorkspaceLabels } from "../../i18n/workspace";
import { useI18n } from "../../i18n";
import { Input } from "../../shared/ui";
import { LoadState, PageButtons } from "../../shared/workspace-ui";
import { errorMessage } from "../../shared/lib";
import { Notice } from "../../shared/types";

export function TenantAccessEditor({
  credentials,
  onNotice,
}: {
  credentials: Credentials;
  onNotice: (n: Notice) => void;
}) {
  const l = useWorkspaceLabels();
  const { t } = useI18n();
  const companies = useRemote(() => api.companies(credentials), [credentials.accessToken]);
  const [username, setUsername] = useState("");
  const [account, setAccount] = useState("");
  const [companyId, setCompanyId] = useState("");
  const [roleName, setRoleName] = useState("VIEWER");
  const [privileges, setPrivileges] = useState<CompanyPrivilege[]>([]);
  const [storeIds, setStoreIds] = useState<string[]>([]);
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [busy, setBusy] = useState(false);
  const access = useRemote(
    () =>
      account
        ? workspaceApi.userAccess(credentials, account)
        : Promise.resolve(null),
    [credentials.accessToken, account],
  );
  const stores = useRemote(
    () =>
      companyId
        ? workspaceApi.stores(credentials, { companyId, q, page, size: 25 })
        : Promise.resolve(null),
    [credentials.accessToken, companyId, q, page],
  );
  const membership = access.data?.companies.find(
    (c) => c.companyId === companyId,
  );
  const companyReady = !companies.loading && !companies.error && Boolean(companies.data?.some(company => company.companyId === companyId));
  const selectionUnavailable = busy || !companyReady || access.loading || Boolean(access.error);
  useEffect(() => {
    if (companies.data && companyId && !companies.data.some(company => company.companyId === companyId)) setCompanyId("");
  }, [companies.data, companyId]);
  useEffect(() => {
    setRoleName(membership?.roleName ?? "VIEWER");
    setPrivileges(membership?.companyPrivileges ?? []);
    setStoreIds(membership?.stores.map((s) => s.storeId) ?? []);
  }, [membership, companyId, account]);
  async function save(event: FormEvent) {
    event.preventDefault();
    if (selectionUnavailable || stores.loading || stores.error || !access.data) return;
    setBusy(true);
    try {
      await request(
        credentials,
        `/api/v1/admin/tenant-users/${encodeURIComponent(account)}/access/companies/${companyId}`,
        {
          method: "PUT",
          body: { roleName, companyPrivileges: privileges, storeIds },
        },
      );
      access.reload();
      onNotice({ type: "success", text: l("saved") });
    } catch (e) {
      onNotice({ type: "error", text: errorMessage(e) });
    } finally {
      setBusy(false);
    }
  }
  async function revoke() {
    if (!membership || selectionUnavailable || !window.confirm(l("confirmRevoke"))) return;
    setBusy(true);
    try {
      await request(
        credentials,
        `/api/v1/admin/tenant-users/${encodeURIComponent(account)}/access/companies/${companyId}`,
        { method: "DELETE" },
      );
      access.reload();
      onNotice({ type: "success", text: l("saved") });
    } catch (e) {
      onNotice({ type: "error", text: errorMessage(e) });
    } finally {
      setBusy(false);
    }
  }
  function togglePrivilege(p: CompanyPrivilege, checked: boolean) {
    setPrivileges((current) => {
      const next = new Set(current);
      if (checked) next.add(p);
      else next.delete(p);
      if (p === "WRITE_MASTERS" && checked) next.add("READ_MASTERS");
      if (p === "READ_MASTERS" && !checked) next.delete("WRITE_MASTERS");
      return [...next];
    });
  }
  return (
    <section className="content-section">
      <h2>{l("access")}</h2>
      <form
        className="toolbar"
        onSubmit={(e) => {
          e.preventDefault();
          setAccount(username.trim());
          setCompanyId("");
        }}
      >
        <Input
          label={l("account")}
          value={username}
          onChange={setUsername}
          required
          disabled={busy}
        />
        <button disabled={busy}>{l("detail")}</button>
      </form>
      <LoadState {...access} />
      <LoadState {...companies} />
      {access.data && (
        <form onSubmit={save}>
          <p>
            <strong>{access.data.username}</strong> ·{" "}
            {access.data.companies.map((c) => c.companyName).join(" / ") ||
              l("empty")}
          </p>
          <div className="toolbar">
            <label>
              {l("company")}
              <select
                required
                aria-label={l("company")}
                disabled={busy || companies.loading || Boolean(companies.error)}
                value={companyId}
                onChange={(e) => {
                  setCompanyId(e.target.value);
                  setPage(0);
                  setQ("");
                }}
              >
                <option value="">{l("selectCompany")}</option>
                {companies.data?.map((c) => (
                  <option key={c.companyId} value={c.companyId}>
                    {c.companyName}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {l("role")}
              <select
                disabled={selectionUnavailable}
                value={roleName}
                onChange={(e) => {
                  setRoleName(e.target.value);
                  if (!["OWNER", "MANAGER"].includes(e.target.value))
                    setPrivileges((p) =>
                      p.filter((v) => v !== "WRITE_MASTERS"),
                    );
                }}
              >
                {(membership?.roleName === "OWNER"
                  ? ["OWNER", "MANAGER", "VIEWER", "BILLING"]
                  : ["MANAGER", "VIEWER", "BILLING"]
                ).map((r) => (
                  <option key={r}>{r}</option>
                ))}
              </select>
            </label>
          </div>
          {companyId && (
            <>
              <fieldset disabled={selectionUnavailable}>
                <legend>{l("privileges")}</legend>
                {COMPANY_PRIVILEGES.map((p) => (
                  <label className="access-option" key={p}>
                    <input
                      type="checkbox"
                      checked={privileges.includes(p)}
                      disabled={
                        p === "WRITE_MASTERS" &&
                        !["OWNER", "MANAGER"].includes(roleName)
                      }
                      onChange={(e) => togglePrivilege(p, e.target.checked)}
                    />
                    {l(p)}
                  </label>
                ))}
              </fieldset>
              <h3>
                {l("stores")} ({storeIds.length})
              </h3>
              <Input
                label={l("search")}
                value={q}
                onChange={(v) => {
                  setQ(v);
                  setPage(0);
                }}
              />
              <LoadState {...stores} />
              {stores.data && (
                <>
                  <div className="table-wrap">
                    <table>
                      <thead>
                        <tr>
                          <th>{l("access")}</th>
                          <th>{l("internalCode")}</th>
                          <th>{l("store")}</th>
                          <th>{l("status")}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {stores.data.items.map((s) => (
                          <tr key={s.id}>
                            <td>
                              <input
                                type="checkbox"
                                aria-label={`${l("access")} ${s.name}`}
                                disabled={selectionUnavailable}
                                checked={storeIds.includes(s.id)}
                                onChange={(e) =>
                                  setStoreIds((v) =>
                                    e.target.checked
                                      ? [...v, s.id]
                                      : v.filter((id) => id !== s.id),
                                  )
                                }
                              />
                            </td>
                            <td>{s.internalCode ?? l("codePending")}</td>
                            <td>
                              {s.code} · {s.name}
                            </td>
                            <td>{s.active ? l("active") : l("inactive")}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <PageButtons
                    page={page}
                    totalPages={stores.data.totalPages}
                    onPage={setPage}
                  />
                </>
              )}
              <div className="form-actions">
                <button
                  type="submit"
                  disabled={selectionUnavailable || stores.loading || Boolean(stores.error)}
                >
                  {l("save")}
                </button>
                {membership && (
                  <button
                    type="button"
                    disabled={selectionUnavailable}
                    onClick={() => void revoke()}
                  >
                    {l("revoke")}
                  </button>
                )}
              </div>
            </>
          )}
        </form>
      )}
      <p>{t("tenantAccess")}</p>
    </section>
  );
}
