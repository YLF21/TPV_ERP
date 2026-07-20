import { useEffect, useMemo, useState, type CSSProperties, type FormEvent, type ReactNode } from "react";
import {
  TableLayoutHeaderCell,
  tableLayoutGridTemplate,
  useTableLayoutPreference,
  visibleTableColumns,
  type TableColumnDefinition,
  type UserSession
} from "@tpverp/app-common";
import {
  createSecurityRole,
  createSecurityUser,
  deleteSecurityRole,
  loadPermissionCatalog,
  loadRoleOptions,
  loadSecurityRoles,
  loadSecurityUsers,
  resetSecurityUserPassword,
  renameSecurityRole,
  saveSecurityRolePermissions,
  updateSecurityUserActive,
  updateSecurityUserIdentity,
  updateSecurityUserRole,
  type PermissionCatalogItem,
  type RoleOption,
  type SecurityRole,
  type SecurityUser
} from "./securityAdministrationApi";

type Translator = (key: string) => string;
export type SecurityAdministrationMode = "users" | "roles";
type UserDialogKind = "create" | "identity" | "role" | "password";
type UserColumnKey = "userId" | "name" | "userName" | "role" | "status";

export const securityUserTableKey = "gestion.security.users";
export const securityUserColumnDefinitions = [
  { key: "userId", defaultWidth: 92 },
  { key: "name", defaultWidth: 190 },
  { key: "userName", defaultWidth: 190 },
  { key: "role", defaultWidth: 160 },
  { key: "status", defaultWidth: 105 }
] as const satisfies readonly TableColumnDefinition<UserColumnKey>[];

export function canManageUsers(session: Pick<UserSession, "permissions">) {
  return session.permissions.includes("ADMIN") || session.permissions.includes("GESTION_USUARIO");
}

export function canManageRoles(session: Pick<UserSession, "permissions">) {
  return session.permissions.includes("ADMIN") || session.permissions.includes("ROLES_MANAGE");
}

export function SecurityAdministrationScreen({ mode, session, t }: {
  mode: SecurityAdministrationMode;
  session: UserSession;
  t: Translator;
}) {
  if (mode === "roles") {
    return <RoleAdministration session={session} t={t} />;
  }
  return <UserAdministration session={session} t={t} />;
}

function UserAdministration({ session, t }: { session: UserSession; t: Translator }) {
  const token = session.accessToken;
  const [users, setUsers] = useState<SecurityUser[]>([]);
  const [roles, setRoles] = useState<RoleOption[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [dialog, setDialog] = useState<UserDialogKind | null>(null);
  const [confirmActive, setConfirmActive] = useState<boolean | null>(null);
  const tableLayout = useTableLayoutPreference({
    app: "gestion",
    username: session.username,
    accessToken: token,
    tableKey: securityUserTableKey,
    definitions: securityUserColumnDefinitions
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const tableStyle = { gridTemplateColumns: tableLayoutGridTemplate(tableLayout.layout) } as CSSProperties;

  async function refresh(preferredId = selectedId) {
    setLoading(true);
    setError(false);
    try {
      const [loadedUsers, loadedRoles] = await Promise.all([
        loadSecurityUsers(token),
        loadRoleOptions(token)
      ]);
      setUsers(loadedUsers);
      setRoles(loadedRoles);
      setSelectedId(loadedUsers.some((user) => user.id === preferredId)
        ? preferredId
        : loadedUsers[0]?.id ?? null);
    } catch {
      setUsers([]);
      setRoles([]);
      setSelectedId(null);
      setError(true);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void refresh(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  const filteredUsers = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase();
    if (!normalized) return users;
    return users.filter((user) => [user.userId, user.name, user.userName, user.role]
      .some((value) => value.toLocaleLowerCase().includes(normalized)));
  }, [query, users]);
  const selected = users.find((user) => user.id === selectedId) ?? null;

  function replaceUser(updated: SecurityUser) {
    setUsers((current) => current.map((user) => user.id === updated.id ? updated : user));
    setSelectedId(updated.id);
  }

  async function changeActive() {
    if (!selected || confirmActive === null) return;
    await updateSecurityUserActive(selected.id, confirmActive, token);
    replaceUser({ ...selected, active: confirmActive });
    setConfirmActive(null);
  }

  return (
    <section className="gestion-workspace gestion-security-workspace">
      <SecurityHeader
        eyebrow={t("gestion.security.eyebrow")}
        title={t("gestion.users.title")}
        subtitle={t("gestion.users.subtitle")}
      >
        <button type="button" onClick={() => void refresh()}>{t("common.refresh")}</button>
        <button type="button" className="primary" onClick={() => setDialog("create")}>{t("gestion.users.new")}</button>
      </SecurityHeader>

      <div className="gestion-security-toolbar">
        <label>
          <span>{t("gestion.security.search")}</span>
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t("gestion.users.searchPlaceholder")} />
        </label>
        <span>{t("gestion.security.results").replace("{count}", String(filteredUsers.length))}</span>
      </div>

      <div className="gestion-security-grid">
        <section className="gestion-security-list" aria-label={t("gestion.users.title")}>
          <div className="gestion-security-table" role="table" aria-rowcount={filteredUsers.length}>
            <div className="gestion-security-row head" role="row" style={tableStyle}>
              {visibleColumns.map((column) => (
                <TableLayoutHeaderCell
                  as="span"
                  column={column}
                  key={column.key}
                  resizeLabel={`${t("gestion.security.resize")} ${t(`gestion.users.column.${column.key}`)}`}
                  onReorder={tableLayout.reorderColumns}
                  onMove={tableLayout.moveColumn}
                  onResize={tableLayout.resizeColumn}
                >
                  {t(`gestion.users.column.${column.key}`)}
                </TableLayoutHeaderCell>
              ))}
            </div>
            {filteredUsers.map((user) => (
              <button
                type="button"
                role="row"
                aria-selected={selectedId === user.id}
                className={`gestion-security-row ${selectedId === user.id ? "selected" : ""}`}
                style={tableStyle}
                key={user.id}
                onClick={() => setSelectedId(user.id)}
              >
                {visibleColumns.map((column) => (
                  <span role="cell" data-column-key={column.key} key={column.key}>
                    {renderUserCell(user, column.key, t)}
                  </span>
                ))}
              </button>
            ))}
            {loading && <SecurityState>{t("common.loading")}</SecurityState>}
            {!loading && error && <SecurityState error>{t("gestion.security.loadError")}</SecurityState>}
            {!loading && !error && filteredUsers.length === 0 && <SecurityState>{t("gestion.users.empty")}</SecurityState>}
          </div>
        </section>

        <aside className="gestion-security-detail">
          {!selected && <SecurityState>{t("gestion.users.select")}</SecurityState>}
          {selected && (
            <>
              <header>
                <div><span>{selected.userId}</span><h3>{selected.name}</h3></div>
                <StatusBadge active={selected.active} t={t} />
              </header>
              <dl>
                <div><dt>{t("gestion.users.column.userName")}</dt><dd>{selected.userName}</dd></div>
                <div><dt>{t("gestion.users.column.role")}</dt><dd>{selected.role}</dd></div>
                <div><dt>{t("gestion.users.protected")}</dt><dd>{selected.protectedUser ? t("common.yes") : t("common.no")}</dd></div>
              </dl>
              {selected.protectedUser ? (
                <p className="gestion-security-notice">{t("gestion.users.adminProtected")}</p>
              ) : (
                <div className="gestion-security-actions">
                  <button type="button" onClick={() => setDialog("identity")}>{t("gestion.users.editIdentity")}</button>
                  <button type="button" onClick={() => setDialog("role")}>{t("gestion.users.changeRole")}</button>
                  <button type="button" onClick={() => setDialog("password")}>{t("gestion.users.resetPassword")}</button>
                  <button type="button" className={selected.active ? "danger" : ""} onClick={() => setConfirmActive(!selected.active)}>
                    {t(selected.active ? "gestion.users.deactivate" : "gestion.users.activate")}
                  </button>
                </div>
              )}
            </>
          )}
        </aside>
      </div>

      {dialog && (
        <UserActionDialog
          kind={dialog}
          user={dialog === "create" ? null : selected}
          roles={roles}
          token={token}
          t={t}
          onClose={() => setDialog(null)}
          onSaved={(saved) => {
            if (dialog === "create") setUsers((current) => [...current, saved].sort((a, b) => a.name.localeCompare(b.name)));
            else replaceUser(saved);
            setSelectedId(saved.id);
            setDialog(null);
          }}
        />
      )}
      {selected && confirmActive !== null && (
        <ConfirmDialog
          title={t(confirmActive ? "gestion.users.confirmActivateTitle" : "gestion.users.confirmDeactivateTitle")}
          text={t(confirmActive ? "gestion.users.confirmActivate" : "gestion.users.confirmDeactivate").replace("{name}", selected.name)}
          t={t}
          onCancel={() => setConfirmActive(null)}
          onConfirm={() => void changeActive()}
        />
      )}
    </section>
  );
}

function UserActionDialog({ kind, user, roles, token, t, onClose, onSaved }: {
  kind: UserDialogKind;
  user: SecurityUser | null;
  roles: RoleOption[];
  token?: string;
  t: Translator;
  onClose: () => void;
  onSaved: (user: SecurityUser) => void;
}) {
  const initialRole = roles.find((role) => role.name === user?.role)?.id ?? roles[0]?.id ?? "";
  const [name, setName] = useState(user?.name ?? "");
  const [userName, setUserName] = useState(user?.userName ?? "");
  const [roleId, setRoleId] = useState(initialRole);
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError("");
    if ((kind === "create" || kind === "password") && (!/^\d{4,12}$/.test(password) || password !== confirmPassword)) {
      setError(t("gestion.users.passwordInvalid"));
      return;
    }
    setSaving(true);
    try {
      let saved: SecurityUser;
      if (kind === "create") {
        saved = await createSecurityUser({ name, userName, password, roleId }, token);
      } else if (kind === "identity" && user) {
        saved = await updateSecurityUserIdentity(user.id, { name, userName }, token);
      } else if (kind === "role" && user) {
        saved = await updateSecurityUserRole(user.id, roleId, token);
      } else if (kind === "password" && user) {
        await resetSecurityUserPassword(user.id, password, token);
        saved = user;
      } else {
        return;
      }
      onSaved(saved);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("gestion.security.saveError"));
    } finally {
      setSaving(false);
    }
  }

  const title = t(`gestion.users.dialog.${kind}`);
  return (
    <Modal title={title} onClose={onClose}>
      <form className="gestion-security-form" onSubmit={submit}>
        {(kind === "create" || kind === "identity") && (
          <>
            <label><span>{t("gestion.users.field.name")}</span><input autoFocus required value={name} onChange={(event) => setName(event.target.value)} /></label>
            <label><span>{t("gestion.users.field.userName")}</span><input required value={userName} onChange={(event) => setUserName(event.target.value)} /></label>
          </>
        )}
        {(kind === "create" || kind === "role") && (
          <label><span>{t("gestion.users.field.role")}</span><select required value={roleId} onChange={(event) => setRoleId(event.target.value)}>{roles.map((role) => <option value={role.id} key={role.id}>{role.name}</option>)}</select></label>
        )}
        {(kind === "create" || kind === "password") && (
          <>
            <label><span>{t("gestion.users.field.password")}</span><input autoFocus={kind === "password"} required inputMode="numeric" type="password" minLength={4} maxLength={12} value={password} onChange={(event) => setPassword(event.target.value)} /></label>
            <label><span>{t("gestion.users.field.confirmPassword")}</span><input required inputMode="numeric" type="password" minLength={4} maxLength={12} value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} /></label>
          </>
        )}
        {error && <p className="gestion-inline-error" role="alert">{error}</p>}
        <footer><button type="button" onClick={onClose}>{t("common.cancel")}</button><button type="submit" className="primary" disabled={saving || ((kind === "create" || kind === "role") && !roleId)}>{saving ? t("common.saving") : t("common.save")}</button></footer>
      </form>
    </Modal>
  );
}

function RoleAdministration({ session, t }: { session: UserSession; t: Translator }) {
  const token = session.accessToken;
  const [roles, setRoles] = useState<SecurityRole[]>([]);
  const [catalog, setCatalog] = useState<PermissionCatalogItem[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [draft, setDraft] = useState<Set<string>>(new Set());
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState(false);
  const [creating, setCreating] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const [deleting, setDeleting] = useState(false);

  async function refresh(preferredId = selectedId) {
    setLoading(true);
    setError(false);
    try {
      const [loadedRoles, loadedCatalog] = await Promise.all([
        loadSecurityRoles(token),
        loadPermissionCatalog(token)
      ]);
      setRoles(loadedRoles);
      setCatalog(loadedCatalog);
      setSelectedId(loadedRoles.some((role) => role.id === preferredId)
        ? preferredId
        : loadedRoles[0]?.id ?? null);
    } catch {
      setRoles([]);
      setCatalog([]);
      setSelectedId(null);
      setError(true);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void refresh(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  const filteredRoles = roles.filter((role) => role.name.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const selected = roles.find((role) => role.id === selectedId) ?? null;
  useEffect(() => {
    setDraft(new Set(selected?.protectedRole ? [] : selected?.permissions ?? []));
    setSaveError(false);
  }, [selectedId, selected?.permissions, selected?.protectedRole]);

  const groups = useMemo(() => {
    const values = new Map<string, PermissionCatalogItem[]>();
    catalog.forEach((permission) => values.set(permission.group, [...(values.get(permission.group) ?? []), permission]));
    return [...values.entries()];
  }, [catalog]);

  async function savePermissions() {
    if (!selected || selected.protectedRole) return;
    setSaving(true);
    setSaveError(false);
    try {
      const saved = await saveSecurityRolePermissions(selected.id, [...draft].sort(), token);
      setRoles((current) => current.map((role) => role.id === saved.id ? saved : role));
    } catch {
      setSaveError(true);
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="gestion-workspace gestion-security-workspace gestion-role-workspace">
      <SecurityHeader eyebrow={t("gestion.security.eyebrow")} title={t("gestion.roles.title")} subtitle={t("gestion.roles.subtitle")}>
        <button type="button" onClick={() => void refresh()}>{t("common.refresh")}</button>
        <button type="button" className="primary" onClick={() => setCreating(true)}>{t("gestion.roles.new")}</button>
      </SecurityHeader>

      <div className="gestion-role-layout">
        <aside className="gestion-role-list">
          <label><span>{t("gestion.security.search")}</span><input value={query} onChange={(event) => setQuery(event.target.value)} /></label>
          <div>
            {filteredRoles.map((role) => (
              <button type="button" className={selectedId === role.id ? "selected" : ""} key={role.id} onClick={() => setSelectedId(role.id)}>
                <span><strong>{role.name}</strong><small>{role.protectedRole ? t("gestion.roles.system") : t("gestion.roles.custom")}</small></span>
                <b>{role.protectedRole ? "∞" : role.permissions.length}</b>
              </button>
            ))}
            {loading && <SecurityState>{t("common.loading")}</SecurityState>}
            {!loading && error && <SecurityState error>{t("gestion.security.loadError")}</SecurityState>}
            {!loading && !error && filteredRoles.length === 0 && <SecurityState>{t("gestion.roles.empty")}</SecurityState>}
          </div>
        </aside>

        <section className="gestion-permission-editor">
          {!selected && <SecurityState>{t("gestion.roles.select")}</SecurityState>}
          {selected && (
            <>
              <header>
                <div><span>{t("gestion.roles.detail")}</span><h3>{selected.name}</h3></div>
                {!selected.protectedRole && (
                  <div className="gestion-role-header-actions">
                    <strong>{t("gestion.roles.selectedCount").replace("{count}", String(draft.size))}</strong>
                    <button type="button" onClick={() => setRenaming(true)}>{t("gestion.roles.rename")}</button>
                    <button type="button" className="danger" onClick={() => setDeleting(true)}>{t("gestion.roles.delete")}</button>
                  </div>
                )}
              </header>
              {selected.protectedRole ? (
                <div className="gestion-security-notice wide"><strong>{t("gestion.roles.adminProtected")}</strong><p>{t("gestion.roles.adminProtectedHint")}</p></div>
              ) : (
                <>
                  <div className="gestion-permission-groups">
                    {groups.map(([group, permissions]) => (
                      <section key={group}>
                        <header><strong>{t(`gestion.permissions.group.${group}`)}</strong><button type="button" onClick={() => {
                          const next = new Set(draft);
                          const allSelected = permissions.every((permission) => next.has(permission.code));
                          permissions.forEach((permission) => allSelected ? next.delete(permission.code) : next.add(permission.code));
                          setDraft(next);
                        }}>{t("gestion.roles.toggleGroup")}</button></header>
                        <div>{permissions.map((permission) => (
                          <label key={permission.code}>
                            <input type="checkbox" checked={draft.has(permission.code)} onChange={(event) => {
                              const next = new Set(draft);
                              if (event.target.checked) next.add(permission.code); else next.delete(permission.code);
                              setDraft(next);
                            }} />
                            <span><strong>{permissionLabel(permission, t)}</strong><small>{permission.code}</small></span>
                          </label>
                        ))}</div>
                      </section>
                    ))}
                  </div>
                  <footer>{saveError && <span className="gestion-inline-error">{t("gestion.security.saveError")}</span>}<button type="button" className="primary" disabled={saving} onClick={() => void savePermissions()}>{saving ? t("common.saving") : t("gestion.roles.savePermissions")}</button></footer>
                </>
              )}
            </>
          )}
        </section>
      </div>
      {creating && <CreateRoleDialog token={token} t={t} onClose={() => setCreating(false)} onCreated={(role) => { setRoles((current) => [...current, role].sort((a, b) => a.name.localeCompare(b.name))); setSelectedId(role.id); setCreating(false); }} />}
      {renaming && selected && !selected.protectedRole && (
        <RenameRoleDialog
          role={selected}
          token={token}
          t={t}
          onClose={() => setRenaming(false)}
          onRenamed={(renamed) => {
            setRoles((current) => current
              .map((role) => role.id === renamed.id ? renamed : role)
              .sort((a, b) => a.name.localeCompare(b.name)));
            setSelectedId(renamed.id);
            setRenaming(false);
          }}
        />
      )}
      {deleting && selected && !selected.protectedRole && (
        <DeleteRoleDialog
          role={selected}
          token={token}
          t={t}
          onClose={() => setDeleting(false)}
          onDeleted={() => {
            const remaining = roles.filter((role) => role.id !== selected.id);
            setRoles(remaining);
            setSelectedId(remaining[0]?.id ?? null);
            setDeleting(false);
          }}
        />
      )}
    </section>
  );
}

function CreateRoleDialog({ token, t, onClose, onCreated }: { token?: string; t: Translator; onClose: () => void; onCreated: (role: SecurityRole) => void }) {
  const [name, setName] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError("");
    try { onCreated(await createSecurityRole(name, token)); }
    catch (reason) { setError(reason instanceof Error ? reason.message : t("gestion.security.saveError")); }
    finally { setSaving(false); }
  }
  return <Modal title={t("gestion.roles.dialog.create")} onClose={onClose}><form className="gestion-security-form" onSubmit={submit}><label><span>{t("gestion.roles.field.name")}</span><input autoFocus required value={name} onChange={(event) => setName(event.target.value)} /></label>{error && <p className="gestion-inline-error" role="alert">{error}</p>}<footer><button type="button" onClick={onClose}>{t("common.cancel")}</button><button type="submit" className="primary" disabled={saving}>{saving ? t("common.saving") : t("common.save")}</button></footer></form></Modal>;
}

function RenameRoleDialog({ role, token, t, onClose, onRenamed }: {
  role: SecurityRole;
  token?: string;
  t: Translator;
  onClose: () => void;
  onRenamed: (role: SecurityRole) => void;
}) {
  const [name, setName] = useState(role.name);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError("");
    try { onRenamed(await renameSecurityRole(role.id, name, token)); }
    catch (reason) { setError(reason instanceof Error ? reason.message : t("gestion.security.saveError")); }
    finally { setSaving(false); }
  }
  return (
    <Modal title={t("gestion.roles.dialog.rename")} onClose={onClose}>
      <form className="gestion-security-form" onSubmit={submit}>
        <label><span>{t("gestion.roles.field.name")}</span><input autoFocus required value={name} onChange={(event) => setName(event.target.value)} /></label>
        {error && <p className="gestion-inline-error" role="alert">{error}</p>}
        <footer><button type="button" onClick={onClose}>{t("common.cancel")}</button><button type="submit" className="primary" disabled={saving}>{saving ? t("common.saving") : t("common.save")}</button></footer>
      </form>
    </Modal>
  );
}

function DeleteRoleDialog({ role, token, t, onClose, onDeleted }: {
  role: SecurityRole;
  token?: string;
  t: Translator;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState("");
  async function confirm() {
    setDeleting(true);
    setError("");
    try {
      await deleteSecurityRole(role.id, token);
      onDeleted();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("gestion.security.saveError"));
    } finally {
      setDeleting(false);
    }
  }
  return (
    <Modal title={t("gestion.roles.dialog.delete")} onClose={onClose}>
      <div className="gestion-confirm-content">
        <p>{t("gestion.roles.deleteConfirm").replace("{name}", role.name)}</p>
        <p className="gestion-confirm-warning">{t("gestion.roles.deleteRequirement")}</p>
        {error && <p className="gestion-inline-error" role="alert">{error}</p>}
        <footer>
          <button type="button" onClick={onClose}>{t("common.cancel")}</button>
          <button type="button" className="danger" disabled={deleting} onClick={() => void confirm()}>{deleting ? t("common.saving") : t("gestion.roles.delete")}</button>
        </footer>
      </div>
    </Modal>
  );
}

function SecurityHeader({ eyebrow, title, subtitle, children }: { eyebrow: string; title: string; subtitle: string; children: ReactNode }) {
  return <header className="gestion-dashboard-toolbar gestion-security-header"><div><span className="gestion-eyebrow">{eyebrow}</span><h2>{title}</h2><p>{subtitle}</p></div><div className="gestion-dashboard-actions">{children}</div></header>;
}

function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return <div className="gestion-modal-backdrop"><section className="gestion-security-dialog" role="dialog" aria-modal="true" aria-label={title}><header><h2>{title}</h2><button type="button" aria-label="Cerrar" onClick={onClose}>×</button></header>{children}</section></div>;
}

function ConfirmDialog({ title, text, t, onCancel, onConfirm }: { title: string; text: string; t: Translator; onCancel: () => void; onConfirm: () => void }) {
  return <Modal title={title} onClose={onCancel}><div className="gestion-confirm-content"><p>{text}</p><footer><button type="button" onClick={onCancel}>{t("common.cancel")}</button><button type="button" className="primary" onClick={onConfirm}>{t("common.confirm")}</button></footer></div></Modal>;
}

function SecurityState({ error = false, children }: { error?: boolean; children: ReactNode }) {
  return <div className={`gestion-security-state ${error ? "error" : ""}`}>{children}</div>;
}

function StatusBadge({ active, t }: { active: boolean; t: Translator }) {
  return <span className={`gestion-security-status ${active ? "active" : "inactive"}`}>{t(active ? "gestion.users.active" : "gestion.users.inactive")}</span>;
}

function renderUserCell(user: SecurityUser, column: UserColumnKey, t: Translator) {
  if (column === "status") return <StatusBadge active={user.active} t={t} />;
  return user[column];
}

function permissionLabel(permission: PermissionCatalogItem, t: Translator) {
  const translated = t(permission.translationKey);
  return translated === permission.translationKey ? permission.code.replaceAll("_", " ") : translated;
}
