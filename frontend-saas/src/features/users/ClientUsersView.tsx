import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { useRemote } from "../../app/RefreshContext";
import { useI18n } from "../../i18n";
import { useWorkspaceLabels } from "../../i18n/workspace";
import { api } from "../../lib/api";
import { clientAccessApi, type ClientAccessRequestStatus, type ClientAccessRequestSummary, type ClientUserSummary } from "../../lib/client-access-api";
import type { Credentials } from "../../lib/types";
import { formatDate, userManagementErrorMessage } from "../../shared/lib";
import { SaasDataTable, type DataColumn } from "../../shared/table/SaasDataTable";
import type { Notice } from "../../shared/types";
import { Input, SectionHeader, StatusPill } from "../../shared/ui";
import { LoadState, PageButtons } from "../../shared/workspace-ui";
import { WorkspaceDialog } from "../../shared/WorkspaceDialog";
import { ClientRequestReview, ClientRequestStatus } from "./ClientRequestReview";
import { useClientUserLabels } from "./client-user-labels";
import { TenantAccessEditor } from "./TenantAccessEditor";
import "./client-users.css";

const PAGE_SIZE = 25;
const STATUSES: ClientAccessRequestStatus[] = ["PENDING", "APPROVED", "REJECTED"];
type ClientUsersProps = { credentials: Credentials; onNotice: (notice: Notice) => void };
type Section = "requests" | "users";
type Sort = { key: string; direction: "asc" | "desc" };

function readSection(): Section {
  const path = window.location.hash.split("?")[0].replace(/^#\/?/, "");
  return path === "access" || path === "client-users/users" ? "users" : "requests";
}

export function ClientUsersView({ credentials, onNotice }: ClientUsersProps) {
  const { t } = useI18n();
  const labels = useClientUserLabels();
  const [section, setSection] = useState<Section>(readSection);
  const id = useId();
  const requestsTab = useRef<HTMLButtonElement>(null);
  const usersTab = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const sync = () => setSection(readSection());
    window.addEventListener("hashchange", sync);
    window.addEventListener("popstate", sync);
    return () => {
      window.removeEventListener("hashchange", sync);
      window.removeEventListener("popstate", sync);
    };
  }, []);

  function changeSection(next: Section) {
    if (next === section) return;
    window.history.pushState({ view: "client-users" }, "", `#/client-users/${next}`);
    setSection(next);
  }

  function navigateTabs(event: KeyboardEvent<HTMLButtonElement>) {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const next = event.key === "Home" ? "requests" : event.key === "End" ? "users" : section === "requests" ? "users" : "requests";
    changeSection(next);
    (next === "requests" ? requestsTab : usersTab).current?.focus();
  }

  return <section className="content-section table-workspace client-users-section">
    <SectionHeader title={t("tenantUsers")} subtitle={labels(section === "requests" ? "requestHint" : "directoryHint")} />
    <div className="segmented" role="tablist" aria-label={t("tenantUsers")}>
      <button type="button" role="tab" ref={requestsTab} id={`${id}-requests`} aria-selected={section === "requests"}
        aria-controls={`${id}-panel`} tabIndex={section === "requests" ? 0 : -1} className={section === "requests" ? "active" : undefined}
        onKeyDown={navigateTabs} onClick={() => changeSection("requests")}>{labels("requests")}</button>
      <button type="button" role="tab" ref={usersTab} id={`${id}-users`} aria-selected={section === "users"}
        aria-controls={`${id}-panel`} tabIndex={section === "users" ? 0 : -1} className={section === "users" ? "active" : undefined}
        onKeyDown={navigateTabs} onClick={() => changeSection("users")}>{t("tenantUsers")}</button>
    </div>
    <div role="tabpanel" id={`${id}-panel`} aria-labelledby={`${id}-${section}`}>
      {section === "requests"
        ? <RequestsDirectory credentials={credentials} onNotice={onNotice} />
        : <UsersDirectory credentials={credentials} onNotice={onNotice} />}
    </div>
  </section>;
}

function useDirectoryControls(initialSort: Sort) {
  const [q, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState(initialSort);
  const table = useRef<HTMLDivElement>(null);
  function setQ(value: string) { setQuery(value); setPage(0); }
  function onSort(key: string) {
    setSort(current => ({ key, direction: current.key === key && current.direction === "asc" ? "desc" : "asc" }));
    setPage(0);
  }
  useEffect(() => { table.current?.scrollTo({ top: 0 }); }, [q.trim(), page, sort.key, sort.direction]);
  function restoreFocus(rowId: string) {
    return table.current?.querySelector<HTMLElement>(`tr[data-row-id="${CSS.escape(rowId)}"]`)
      ?? table.current?.querySelector<HTMLElement>('tr[aria-selected="true"]')
      ?? table.current?.querySelector<HTMLElement>("thead button") ?? null;
  }
  return { q, setQ, page, setPage, sort, onSort, table, restoreFocus,
    query: { q: q.trim(), page, size: PAGE_SIZE, sort: sort.key, direction: sort.direction },
    deps: [q.trim(), page, sort.key, sort.direction] };
}

function RequestsDirectory({ credentials, onNotice }: ClientUsersProps) {
  const { t } = useI18n();
  const labels = useClientUserLabels();
  const l = useWorkspaceLabels();
  const [status, setStatus] = useState<ClientAccessRequestStatus | "">("PENDING");
  const [opened, setOpened] = useState<string | null>(null);
  const controls = useDirectoryControls({ key: "createdAt", direction: "desc" });
  const search = useRemote(() => clientAccessApi.requests(credentials, { ...controls.query, status: status || undefined }),
    [credentials.accessToken, status, ...controls.deps]);
  const columns: DataColumn<ClientAccessRequestSummary, string>[] = [
    { key: "createdAt", label: labels("submittedAt"), defaultWidth: 180, sortKey: "createdAt",
      render: row => <time dateTime={row.createdAt}>{formatDate(row.createdAt)}</time> },
    { key: "fullName", label: labels("name"), defaultWidth: 205, sortKey: "fullName", render: row => <span title={row.fullName}>{row.fullName}</span> },
    { key: "username", label: t("username"), defaultWidth: 155, sortKey: "username", render: row => <span title={row.username}>{row.username}</span> },
    { key: "email", label: t("email"), defaultWidth: 225, sortKey: "email", render: row => <span title={row.email}>{row.email}</span> },
    { key: "status", label: t("status"), defaultWidth: 140, sortKey: "status", render: row => <ClientRequestStatus status={row.status} /> },
    { key: "open", label: l("detail"), defaultWidth: 110, render: row => <button type="button" className="small-button"
      aria-label={`${l("detail")} ${row.username}`} onClick={() => setOpened(row.id)}>{l("detail")}</button> },
  ];
  const pages = Math.ceil((search.data?.total ?? 0) / PAGE_SIZE);
  useEffect(() => {
    if (search.data && controls.page >= Math.max(1, pages)) controls.setPage(Math.max(0, pages - 1));
  }, [search.data, pages, controls.page]);

  return <>
    <div className="toolbar client-users-filters">
      <Input label={labels("searchPeople")} type="search" value={controls.q} onChange={controls.setQ} maxLength={160} />
      <label>{t("status")}
        <select className="control-input" value={status} onChange={event => {
          setStatus(event.target.value as ClientAccessRequestStatus | ""); controls.setPage(0);
        }}>
          <option value="">{labels("allStatuses")}</option>
          {STATUSES.map(state => <option key={state} value={state}>{labels(state)}</option>)}
        </select>
      </label>
    </div>
    <div className="client-users-records" aria-busy={search.loading}>
      <SaasDataTable username={credentials.username} tableKey="client-access-requests" label={labels("requests")}
        columns={columns} rows={search.data?.items ?? []} sort={controls.sort} onSort={controls.onSort}
        onOpen={row => setOpened(row.id)} disabled={opened !== null || search.loading} scrollRef={controls.table} />
      <LoadState {...search} />
      {search.data?.items.length === 0 && <p role="status">{labels("noRequests")}</p>}
      {search.data && pages > 1 && <PageButtons page={controls.page} totalPages={pages} onPage={controls.setPage} />}
    </div>
    {opened && <ClientRequestReview key={opened} requestId={opened} credentials={credentials} onNotice={onNotice}
      onChanged={search.reload} onClose={() => setOpened(null)} restoreFocus={() => controls.restoreFocus(opened)} />}
  </>;
}

function UsersDirectory({ credentials, onNotice }: ClientUsersProps) {
  const { t } = useI18n();
  const l = useWorkspaceLabels();
  const labels = useClientUserLabels();
  const [opened, setOpened] = useState<ClientUserSummary | null>(null);
  const controls = useDirectoryControls({ key: "username", direction: "asc" });
  const search = useRemote(() => clientAccessApi.users(credentials, controls.query), [credentials.accessToken, ...controls.deps]);
  const columns: DataColumn<ClientUserSummary, string>[] = [
    { key: "username", label: t("username"), defaultWidth: 135, sortKey: "username", render: row => <span title={row.username}>{row.username}</span> },
    { key: "fullName", label: labels("name"), defaultWidth: 170, sortKey: "fullName", render: row => <span title={row.fullName || undefined}>{row.fullName || "—"}</span> },
    { key: "email", label: t("email"), defaultWidth: 190, sortKey: "email", render: row => <span title={row.email || undefined}>{row.email || "—"}</span> },
    { key: "active", label: t("status"), defaultWidth: 100, sortKey: "active", render: row => <UserStatus active={row.active} /> },
    { key: "companyCount", label: l("companies"), defaultWidth: 135, sortKey: "companyCount", render: row => row.companyCount, align: "right" },
    { key: "storeCount", label: l("stores"), defaultWidth: 125, sortKey: "storeCount", render: row => row.storeCount, align: "right" },
    { key: "createdAt", label: t("created"), defaultWidth: 160, sortKey: "createdAt",
      render: row => <time dateTime={row.createdAt}>{formatDate(row.createdAt)}</time> },
    { key: "open", label: labels("openAccess"), defaultWidth: 90, render: row => <button type="button" className="small-button"
      aria-label={`${labels("openAccess")} ${row.username}`} onClick={() => setOpened(row)}>{labels("openAccess")}</button> },
  ];
  const pages = Math.ceil((search.data?.total ?? 0) / PAGE_SIZE);

  return <>
    <div className="toolbar client-users-filters">
      <Input label={labels("searchPeople")} type="search" value={controls.q} onChange={controls.setQ} maxLength={160} />
    </div>
    <div className="client-users-records" aria-busy={search.loading}>
      <SaasDataTable username={credentials.username} tableKey="client-user-directory" label={t("tenantUsers")}
        columns={columns} rows={search.data?.items ?? []} sort={controls.sort} onSort={controls.onSort}
        onOpen={setOpened} disabled={opened !== null || search.loading} scrollRef={controls.table} />
      <LoadState {...search} />
      {search.data?.items.length === 0 && <p role="status">{labels("noUsers")}</p>}
      {search.data && pages > 1 && <PageButtons page={controls.page} totalPages={pages} onPage={controls.setPage} />}
    </div>
    {opened && <AccountDialog key={opened.id} user={opened} credentials={credentials} onNotice={onNotice} onChanged={search.reload}
      onClose={() => setOpened(null)} restoreFocus={() => controls.restoreFocus(opened.id)} />}
  </>;
}

function UserStatus({ active }: { active: boolean }) {
  const { t } = useI18n();
  return <StatusPill status={t(active ? "active" : "inactive")} tone={active ? "ok" : "muted"} />;
}

function AccountDialog({ credentials, user, onNotice, onChanged, onClose, restoreFocus }: ClientUsersProps & {
  user: ClientUserSummary;
  onChanged: () => void;
  onClose: () => void;
  restoreFocus: () => HTMLElement | null;
}) {
  const { t } = useI18n();
  const labels = useClientUserLabels();
  const pending = useRef(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<Notice | null>(null);
  const [password, setPassword] = useState("");
  const [active, setActive] = useState(user.active);

  function report(notice: Notice) {
    setStatus(notice);
    onNotice(notice);
  }

  async function runMutation(operation: () => Promise<void>) {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setStatus(null);
    try {
      await operation();
    } catch (error) {
      report({ type: "error", text: userManagementErrorMessage(error) });
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }

  function changePassword(event: FormEvent) {
    event.preventDefault();
    if (!active || password.trim().length < 4) return;
    void runMutation(async () => {
      await api.changeTenantPassword(credentials, user.username, password.trim());
      setPassword("");
      report({ type: "success", text: t("tenantUserUpdated") });
    });
  }

  function deactivate() {
    if (!active || pending.current || !window.confirm(t("confirmDestructive"))) return;
    void runMutation(async () => {
      await api.deactivateTenantUser(credentials, user.username);
      setPassword("");
      setActive(false);
      report({ type: "success", text: t("tenantUserDisabled") });
      onChanged();
    });
  }

  return <WorkspaceDialog title={user.username} subtitle={labels("userDetail")} busy={busy} closeLabel={t("close")}
    onClose={onClose} restoreFocus={restoreFocus} className="client-user-dialog">
    <dl className="client-user-detail">
      <div><dt>{labels("name")}</dt><dd>{user.fullName || "—"}</dd></div>
      <div><dt>{t("email")}</dt><dd>{user.email || "—"}</dd></div>
      <div><dt>{t("status")}</dt><dd><UserStatus active={active} /></dd></div>
    </dl>
    {status && <p className={`notice ${status.type}`} role={status.type === "error" ? "alert" : "status"}>{status.text}</p>}
    {active && <form className="toolbar client-user-panel" onSubmit={changePassword} aria-label={t("changePassword")}>
      <Input label={t("newPassword")} type="password" value={password} onChange={setPassword} autoComplete="new-password"
        minLength={4} maxLength={120} disabled={busy} />
      <button className="small-button" type="submit" disabled={busy || password.trim().length < 4}>{t("changePassword")}</button>
      <button className="small-button danger" type="button" disabled={busy} onClick={deactivate}>{t("deactivate")}</button>
    </form>}
    <TenantAccessEditor credentials={credentials} username={user.username} disabled={busy}
      onChanged={onChanged} onNotice={report} runMutation={runMutation} />
  </WorkspaceDialog>;
}
