import { useRef, useState, type FormEvent } from "react";
import { useRemote } from "../../app/RefreshContext";
import { useI18n } from "../../i18n";
import { api, ApiError } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import { formatDate } from "../../shared/lib";
import type { Notice } from "../../shared/types";
import { Input, SectionHeader, StatusPill } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";

export function UsersView({
  credentials,
  permissions,
  onNotice
}: {
  credentials: Credentials;
  permissions: Set<string>;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const users = useRemote(() => api.users(credentials), [credentials.accessToken]);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [creatingStaff, setCreatingStaff] = useState(false);
  const staffCreationPending = useRef(false);
  const [adminPasswordByUser, setAdminPasswordByUser] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const canManageUsers = permissions.has("MANAGE_ADMIN_USERS");
  const ownUsername = credentials.username.trim().toLowerCase();
  const isOwnAccount = (user: string) => user.trim().toLowerCase() === ownUsername;

  async function create(event: FormEvent) {
    event.preventDefault();
    const normalizedUsername = username.trim();
    if (!canManageUsers || busy || staffCreationPending.current || !normalizedUsername || !password.trim()) return;
    staffCreationPending.current = true;
    setCreatingStaff(true);
    try {
      const user = await api.createUser(credentials, { username: normalizedUsername, password });
      setUsername("");
      setPassword("");
      onNotice({ type: "success", text: t("staffUserCreated").replace("{username}", () => user.username) });
      users.reload();
    } catch (error) {
      onNotice({ type: "error", text: adminUserError(error, "staffCreateFailed") });
    } finally {
      staffCreationPending.current = false;
      setCreatingStaff(false);
    }
  }

  function adminUserError(error: unknown, fallback: string) {
    if (error instanceof ApiError) {
      if (error.status === 403) return t("staffPermissions");
      if (error.status === 409 && fallback === "staffCreateFailed") return t("staffUsernameConflict");
    }
    return t(fallback);
  }

  async function deactivate(user: string) {
    if (!canManageUsers || busy || staffCreationPending.current || isOwnAccount(user)) return;
    if (!window.confirm(t("confirmDestructive"))) return;
    setBusy(user);
    try {
      await api.deactivateUser(credentials, user);
      onNotice({ type: "success", text: t("staffUserDeactivated").replace("{username}", () => user) });
      users.reload();
    } catch (error) {
      onNotice({ type: "error", text: adminUserError(error, "staffUpdateFailed") });
    } finally {
      setBusy(null);
    }
  }

  async function changeAdminPassword(user: string) {
    if (!canManageUsers || busy || staffCreationPending.current) return;
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
      onNotice({ type: "error", text: adminUserError(error, "staffUpdateFailed") });
    } finally {
      setBusy(null);
    }
  }

  async function activateAdminUser(user: string) {
    if (!canManageUsers || busy || staffCreationPending.current) return;
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
      users.reload();
    } catch (error) {
      onNotice({ type: "error", text: adminUserError(error, "staffUpdateFailed") });
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="view-grid">
      <section className="content-section">
        <SectionHeader title={t("newUser")} subtitle={t("staffAccountHelp")} />
        {!canManageUsers && (
          <div className="permission-hint">
            {t("staffPermissions")}
          </div>
        )}
        {canManageUsers && (
          <form className="form-grid" onSubmit={create} aria-label={t("createUser")} aria-busy={creatingStaff}>
            <Input label={t("username")} value={username} onChange={setUsername} required maxLength={80} autoComplete="off" disabled={creatingStaff} />
            <Input label={t("password")} type="password" value={password} onChange={setPassword} required minLength={4} maxLength={120} autoComplete="new-password" disabled={creatingStaff} />
            <div className="form-actions">
              <button className="primary-button" type="submit" disabled={creatingStaff || busy !== null || !username.trim() || !password.trim()}>
                {t("createUser")}
              </button>
            </div>
          </form>
        )}
      </section>
      <section className="content-section">
        <SectionHeader title={t("adminUsers")} subtitle={users.data ? `${users.data.length} ${t("accounts")}` : ""} />
        <LoadState {...users} />
        <div className="table-wrap">
          <table aria-label={t("adminUsers")}>
            <thead>
              <tr>
                <th>{t("username")}</th>
                <th>{t("status")}</th>
                <th>{t("created")}</th>
                {canManageUsers && <th></th>}
              </tr>
            </thead>
            <tbody>
              {users.data?.map((user) => (
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
                        disabled={(adminPasswordByUser[user.username]?.trim().length ?? 0) < 4 || busy !== null || creatingStaff}
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
                          disabled={busy !== null || creatingStaff || isOwnAccount(user.username)}
                          title={isOwnAccount(user.username) ? t("staffCannotDeactivateOwnAccount") : undefined}
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
    </div>
  );
}
