import { useWorkspaceLabels } from "../../i18n/workspace";
import { useRefreshVersion, useRemote } from "../../app/RefreshContext";
import { FormEvent, useEffect, useRef, useState } from "react";
import { api } from "../../lib/api";

import type { AdminUser, Credentials, TenantUser } from "../../lib/types";
import { Notice, SaasAdminRoleName, TenantAssignableRoleName } from "../../shared/types";
import { useI18n } from "../../i18n/index";
import { userManagementErrorMessage, errorMessage, SAAS_ADMIN_ROLES, formatDate } from "../../shared/lib";
import { SectionHeader, Input, StatusPill, EmptyState } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";

export function UsersView({
  credentials,
  users,
  permissions,
  onChanged,
  onNotice
}: {
  credentials: Credentials;
  users: AdminUser[];
  permissions: Set<string>;
  onChanged: () => void;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const l = useWorkspaceLabels();
  const refreshVersion = useRefreshVersion();
  const companies = useRemote(() => api.companies(credentials), [credentials.accessToken]);
  const companyOptions = companies.data ?? [];
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [roleName, setRoleName] = useState<SaasAdminRoleName>("ADMIN");
  const [tenantCompanyId, setTenantCompanyId] = useState("");
  const [tenantUsers, setTenantUsers] = useState<TenantUser[]>([]);
  const [tenantUsersCompanyId, setTenantUsersCompanyId] = useState("");
  const [tenantUsername, setTenantUsername] = useState("");
  const [tenantPassword, setTenantPassword] = useState("");
  const [tenantRoleName, setTenantRoleName] = useState<TenantAssignableRoleName>("MANAGER");
  const [adminPasswordByUser, setAdminPasswordByUser] = useState<Record<string, string>>({});
  const [tenantPasswordByUser, setTenantPasswordByUser] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const tenantUsersRequestId = useRef(0);
  const tenantUsersMutationId = useRef(0);
  const selectedTenantCompanyIdRef = useRef(tenantCompanyId);
  selectedTenantCompanyIdRef.current = tenantCompanyId;
  const canManageUsers = permissions.has("MANAGE_ADMIN_USERS");
  const canManageTenantUsers = permissions.has("MANAGE_TENANT_USERS");
  const companiesReady = !companies.loading && !companies.error && companyOptions.some(company => company.companyId === tenantCompanyId);

  useEffect(() => {
    if (companies.data && !companies.data.some(c => c.companyId === tenantCompanyId)) {
      setTenantCompanyId(companies.data[0]?.companyId ?? "");
    }
  }, [companies.data, tenantCompanyId]);

  useEffect(() => {
    tenantUsersRequestId.current += 1;
    tenantUsersMutationId.current += 1;
    setTenantPasswordByUser({});
    setBusy((current) => current?.includes("tenant") ? null : current);
    if (!tenantCompanyId) {
      setTenantUsers([]);
      setTenantUsersCompanyId("");
      return;
    }
    void loadTenantUsers(tenantCompanyId);
  }, [tenantCompanyId, credentials.accessToken, refreshVersion]);

  async function create(event: FormEvent) {
    event.preventDefault();
    setBusy("create-user");
    try {
      await api.createUser(credentials, { username, password, roleName });
      setUsername("");
      setPassword("");
      onNotice({ type: "success", text: `Usuario ${username} creado.` });
      onChanged();
    } catch (error) {
      onNotice({ type: "error", text: userManagementErrorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function deactivate(user: string) {
    if (!window.confirm(t("confirmDestructive"))) return;
    setBusy(user);
    try {
      await api.deactivateUser(credentials, user);
      onNotice({ type: "success", text: `Usuario ${user} desactivado.` });
      onChanged();
    } catch (error) {
      onNotice({ type: "error", text: userManagementErrorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function changeAdminPassword(user: string) {
    const nextPassword = adminPasswordByUser[user]?.trim() ?? "";
    if (nextPassword.length < 4) {
      onNotice({ type: "error", text: t("adminPasswordTooShort") });
      return;
    }
    setBusy(`admin-password-${user}`);
    try {
      await api.changePassword(credentials, user, nextPassword);
      setAdminPasswordByUser((current) => ({ ...current, [user]: "" }));
      onNotice({
        type: "success",
        text: t("adminUserPasswordUpdated").replace("{username}", user)
      });
    } catch (error) {
      onNotice({ type: "error", text: userManagementErrorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function activateAdminUser(user: string) {
    const nextPassword = adminPasswordByUser[user]?.trim() ?? "";
    if (nextPassword.length < 4) {
      onNotice({ type: "error", text: t("adminPasswordTooShort") });
      return;
    }
    setBusy(`admin-activate-${user}`);
    try {
      await api.activateUser(credentials, user, nextPassword);
      setAdminPasswordByUser((current) => ({ ...current, [user]: "" }));
      onNotice({
        type: "success",
        text: t("adminUserActivated").replace("{username}", user)
      });
      onChanged();
    } catch (error) {
      onNotice({ type: "error", text: userManagementErrorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  async function loadTenantUsers(companyId: string) {
    const requestId = ++tenantUsersRequestId.current;
    try {
      const response = await api.tenantUsers(credentials, companyId);
      if (requestId !== tenantUsersRequestId.current
          || selectedTenantCompanyIdRef.current !== companyId) return;
      setTenantUsers(response);
      setTenantUsersCompanyId(companyId);
    } catch (error) {
      if (requestId !== tenantUsersRequestId.current
          || selectedTenantCompanyIdRef.current !== companyId) return;
      setTenantUsers([]);
      setTenantUsersCompanyId(companyId);
      onNotice({ type: "error", text: errorMessage(error) });
    }
  }

  async function createTenantUser(event: FormEvent) {
    event.preventDefault();
    if (!companiesReady || busy || !canManageTenantUsers) return;
    const companyId = tenantCompanyId;
    const mutationId = ++tenantUsersMutationId.current;
    setBusy("create-tenant-user");
    try {
      const created = await api.createTenantUser(credentials, companyId, {
        username: tenantUsername,
        password: tenantPassword,
        roleName: tenantRoleName
      });
      if (!isCurrentTenantMutation(companyId, mutationId) || created.companyId !== companyId) return;
      setTenantUsername("");
      setTenantPassword("");
      onNotice({ type: "success", text: t("tenantUserCreated") });
      if (selectedTenantCompanyIdRef.current === companyId) {
        await loadTenantUsers(companyId);
      }
    } catch (error) {
      if (!isCurrentTenantMutation(companyId, mutationId)) return;
      onNotice({ type: "error", text: userManagementErrorMessage(error) });
    } finally {
      if (isCurrentTenantMutation(companyId, mutationId)) setBusy(null);
    }
  }

  async function changeTenantPassword(user: string) {
    const nextPassword = tenantPasswordByUser[user]?.trim();
    if (!nextPassword) return;
    const companyId = tenantCompanyId;
    if (!ownsSelectedTenantUser(companyId, user)) {
      onNotice({ type: "error", text: t("companySelectionChanged") });
      return;
    }
    const mutationId = ++tenantUsersMutationId.current;
    setBusy(`tenant-password-${user}`);
    try {
      await api.changeTenantPassword(credentials, user, nextPassword);
      if (!isCurrentTenantMutation(companyId, mutationId)) return;
      setTenantPasswordByUser((current) => ({ ...current, [user]: "" }));
      onNotice({ type: "success", text: t("tenantUserUpdated") });
    } catch (error) {
      if (!isCurrentTenantMutation(companyId, mutationId)) return;
      onNotice({ type: "error", text: userManagementErrorMessage(error) });
    } finally {
      if (isCurrentTenantMutation(companyId, mutationId)) setBusy(null);
    }
  }

  async function deactivateTenantUser(user: string) {
    if (!window.confirm(t("confirmDestructive"))) return;
    const companyId = tenantCompanyId;
    if (!ownsSelectedTenantUser(companyId, user)) {
      onNotice({ type: "error", text: t("companySelectionChanged") });
      return;
    }
    const mutationId = ++tenantUsersMutationId.current;
    setBusy(`tenant-disable-${user}`);
    try {
      await api.deactivateTenantUser(credentials, user);
      if (!isCurrentTenantMutation(companyId, mutationId)) return;
      onNotice({ type: "success", text: t("tenantUserDisabled") });
      if (selectedTenantCompanyIdRef.current === companyId) {
        await loadTenantUsers(companyId);
      }
    } catch (error) {
      if (!isCurrentTenantMutation(companyId, mutationId)) return;
      onNotice({ type: "error", text: userManagementErrorMessage(error) });
    } finally {
      if (isCurrentTenantMutation(companyId, mutationId)) setBusy(null);
    }
  }

  function ownsSelectedTenantUser(companyId: string, username: string) {
    return companiesReady && companyId.length > 0
      && tenantUsersCompanyId === companyId
      && tenantUsers.some((user) => user.companyId === companyId && user.username === username);
  }

  function isCurrentTenantMutation(companyId: string, mutationId: number) {
    return mutationId === tenantUsersMutationId.current
      && selectedTenantCompanyIdRef.current === companyId;
  }

  const visibleTenantUsers = tenantUsersCompanyId === tenantCompanyId ? tenantUsers : [];

  return (
    <div className="view-grid">
      <section className="content-section">
        <SectionHeader title={t("newUser")} subtitle={t("availableRoles")} />
        {!canManageUsers && (
          <div className="permission-hint">
            {t("viewerPermissionHint")}
          </div>
        )}
        {canManageUsers && (
          <form className="form-grid three" onSubmit={create}>
            <Input label={t("username")} value={username} onChange={setUsername} required />
            <Input label={t("password")} type="password" value={password} onChange={setPassword} required minLength={4} maxLength={120} />
            <label>
              {t("role")}
              <select
                className="control-input"
                value={roleName}
                onChange={(event) => setRoleName(event.target.value as SaasAdminRoleName)}
              >
                {SAAS_ADMIN_ROLES.map((role) => (
                  <option key={role.value} value={role.value}>
                    {role.label}
                  </option>
                ))}
              </select>
            </label>
            <div className="form-actions">
              <button className="primary-button" type="submit" disabled={busy === "create-user"}>
                {t("createUser")}
              </button>
            </div>
          </form>
        )}
      </section>
      <section className="content-section">
        <SectionHeader title={t("adminUsers")} subtitle={`${users.length} ${t("accounts")}`} />
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{t("username")}</th>
                <th>{t("status")}</th>
                <th>{t("created")}</th>
                {canManageUsers && <th></th>}
              </tr>
            </thead>
            <tbody>
              {users.map((user) => (
                <tr key={user.username}>
                  <td>{user.username}</td>
                  <td>
                    <StatusPill status={user.active ? t("active") : t("inactive")} tone={user.active ? "ok" : "muted"} />
                  </td>
                  <td>{formatDate(user.createdAt)}</td>
                  {canManageUsers && (
                    <td className="row-actions admin-user-actions">
                      <input
                        className="control-input inline-password"
                        type="password"
                        value={adminPasswordByUser[user.username] ?? ""}
                        placeholder={t("newPassword")}
                        aria-label={`${t("newPassword")} ${user.username}`}
                        autoComplete="new-password"
                        minLength={4}
                        maxLength={120}
                        onChange={(event) => setAdminPasswordByUser((current) => ({
                          ...current,
                          [user.username]: event.target.value
                        }))}
                      />
                      <button
                        className="small-button"
                        type="button"
                        disabled={(adminPasswordByUser[user.username]?.trim().length ?? 0) < 4
                          || busy === (user.active
                            ? `admin-password-${user.username}`
                            : `admin-activate-${user.username}`)}
                        onClick={() => void (user.active
                          ? changeAdminPassword(user.username)
                          : activateAdminUser(user.username))}
                      >
                        {user.active ? t("changePassword") : t("activate")}
                      </button>
                      {user.active && (
                        <button
                          className="small-button"
                          type="button"
                          disabled={busy === user.username}
                          onClick={() => void deactivate(user.username)}
                        >
                          {t("deactivate")}
                        </button>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <section className="content-section">
        <SectionHeader title={t("tenantUsers")} subtitle={l("accessHint")} />
        <LoadState {...companies} />
        <div className="toolbar">
          <label className="toolbar-field">
            {t("company")}
            <select className="control-input" aria-label={t("company")} disabled={companies.loading || Boolean(companies.error)} value={tenantCompanyId} onChange={(event) => setTenantCompanyId(event.target.value)}>
              {companyOptions.map((company) => (
                <option key={company.companyId} value={company.companyId}>
                  {company.companyName}
                </option>
              ))}
            </select>
          </label>
        </div>
        {canManageTenantUsers && (
          <form className="form-grid four compact-form" onSubmit={createTenantUser} aria-label={t("createTenantUser")}>
            <Input label={t("username")} value={tenantUsername} onChange={setTenantUsername} required disabled={!companiesReady || busy === "create-tenant-user"} />
            <Input label={t("password")} type="password" value={tenantPassword} onChange={setTenantPassword} required minLength={4} maxLength={120} disabled={!companiesReady || busy === "create-tenant-user"} />
            <label>
              {t("role")}
              <select
                className="control-input"
                value={tenantRoleName}
                onChange={(event) => setTenantRoleName(event.target.value as TenantAssignableRoleName)}
                disabled={!companiesReady || busy === "create-tenant-user"}
              >
                <option value="MANAGER">MANAGER</option>
                <option value="VIEWER">VIEWER</option>
                <option value="BILLING">BILLING</option>
              </select>
            </label>
            <div className="form-actions">
              <button className="primary-button" type="submit" disabled={!companiesReady || busy === "create-tenant-user"}>
                {t("createTenantUser")}
              </button>
            </div>
          </form>
        )}
        {companiesReady && (visibleTenantUsers.length === 0 ? (
          <EmptyState text={t("noTenantUsers")} />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t("username")}</th>
                  <th>{t("role")}</th>
                  <th>{t("status")}</th>
                  <th>{t("created")}</th>
                  {canManageTenantUsers && <th></th>}
                </tr>
              </thead>
              <tbody>
                {visibleTenantUsers.map((user) => (
                  <tr key={user.username}>
                    <td>{user.username}</td>
                    <td>{user.roleName}</td>
                    <td>
                      <StatusPill status={user.active ? t("active") : t("inactive")} tone={user.active ? "ok" : "muted"} />
                    </td>
                    <td>{formatDate(user.createdAt)}</td>
                    {canManageTenantUsers && (
                      <td className="row-actions tenant-user-actions">
                        <input
                          className="control-input inline-password"
                          type="password"
                          value={tenantPasswordByUser[user.username] ?? ""}
                          placeholder={t("newPassword")}
                          disabled={!user.active}
                          onChange={(event) => setTenantPasswordByUser((current) => ({ ...current, [user.username]: event.target.value }))}
                        />
                        <button
                          className="small-button"
                          type="button"
                          disabled={!user.active || !tenantPasswordByUser[user.username]?.trim() || busy === `tenant-password-${user.username}`}
                          onClick={() => void changeTenantPassword(user.username)}
                        >
                          {t("changePassword")}
                        </button>
                        <button
                          className="small-button danger"
                          type="button"
                          disabled={!user.active || busy === `tenant-disable-${user.username}`}
                          onClick={() => void deactivateTenantUser(user.username)}
                        >
                          {t("deactivate")}
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
      </section>
    </div>
  );
}
