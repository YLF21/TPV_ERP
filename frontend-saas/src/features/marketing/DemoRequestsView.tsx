import { useEffect, useRef, useState, type FormEvent } from "react";
import { api, ApiError } from "../../lib/api";
import type { Credentials, DemoRequest, DemoRequestStatus } from "../../lib/types";
import { useI18n } from "../../i18n";
import { useRefreshVersion } from "../../app/RefreshContext";
import { EmptyState, RetryError, SectionHeader, StatusPill } from "../../shared/ui";
import { formatDate } from "../../shared/lib";
import { WorkspaceDialog } from "../../shared/WorkspaceDialog";
import { demoRequestStatuses } from "./demo-request-query.mjs";
import "./demo-requests.css";

const PAGE_SIZE = 20;

export function DemoRequestsView({ credentials, permissions }: { credentials: Credentials; permissions: Set<string> }) {
  const { t } = useI18n();
  const refreshVersion = useRefreshVersion();
  const canRead = permissions.has("VIEW_ADMIN_DATA");
  const canManage = permissions.has("MANAGE_OPERATIONS");
  const [status, setStatus] = useState<DemoRequestStatus | "">("NEW");
  const [searchDraft, setSearchDraft] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [reload, setReload] = useState(0);
  const [items, setItems] = useState<DemoRequest[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selected, setSelected] = useState<DemoRequest | null>(null);
  const [busy, setBusy] = useState(false);
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const [result, setResult] = useState<{ error: boolean; text: string } | null>(null);
  const opener = useRef<HTMLElement | null>(null);
  const searchInput = useRef<HTMLInputElement | null>(null);
  const mounted = useRef(false);
  const activeToken = useRef(credentials.accessToken);
  const activePermissions = useRef(permissions);
  activeToken.current = credentials.accessToken;
  activePermissions.current = permissions;

  function failureText(error: unknown) {
    if (error instanceof ApiError && error.status === 403) return t("demoForbidden");
    if (error instanceof ApiError && error.status === 400) return t("demoInvalidRequest");
    if (error instanceof ApiError && error.status === 404) return t("demoNotFound");
    if (error instanceof ApiError && error.status === 409) return t("demoStatusConflict");
    return t("demoRequestFailed");
  }

  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    setSelected(null); setResult(null); setBusy(false); setNeedsRefresh(false); setPage(0);
  }, [credentials.accessToken, canRead]);
  useEffect(() => {
    let current = true;
    setItems([]); setTotal(0); setLoadError(null);
    if (!canRead) { setLoading(false); return; }
    setLoading(true);
    api.demoRequests(credentials, { page, size: PAGE_SIZE, status: status || undefined, query })
      .then(response => {
        if (!current) return;
        const lastPage = Math.max(0, Math.ceil(response.total / PAGE_SIZE) - 1);
        if (page > lastPage) { setPage(lastPage); return; }
        setItems(response.items); setTotal(response.total);
      })
      .catch(error => { if (current) setLoadError(failureText(error)); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [credentials.accessToken, canRead, refreshVersion, page, status, query, reload]);

  function search(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setPage(0); setQuery(searchDraft.trim()); setReload(value => value + 1);
  }
  function open(request: DemoRequest) {
    opener.current = document.activeElement as HTMLElement;
    setSelected(request); setResult(null); setNeedsRefresh(false);
  }
  async function refreshSelected() {
    if (!selected || busy) return;
    const token = credentials.accessToken;
    const id = selected.id;
    const stillCurrent = () => mounted.current && activeToken.current === token && activePermissions.current.has("VIEW_ADMIN_DATA");
    setBusy(true);
    try {
      const response = await api.demoRequests(credentials, { page: 0, size: 1, query: id });
      if (!stillCurrent()) return;
      const current = response.items.find(request => request.id === id);
      if (!current) { setSelected(null); setLoadError(t("demoNotFound")); return; }
      setSelected(current); setNeedsRefresh(false);
      setResult({ error: false, text: t("demoDetailRefreshed") });
      setReload(value => value + 1);
    } catch (error) { if (stillCurrent()) setResult({ error: true, text: failureText(error) }); }
    finally { if (stillCurrent()) setBusy(false); }
  }
  async function updateStatus(nextStatus: DemoRequestStatus) {
    if (!selected || busy || needsRefresh || !canManage || selected.status === nextStatus) return;
    const token = credentials.accessToken;
    const requestId = selected.id;
    const stillCurrent = () => mounted.current && activeToken.current === token && activePermissions.current.has("VIEW_ADMIN_DATA");
    setBusy(true); setResult(null);
    try {
      const updated = await api.updateDemoRequestStatus(credentials, requestId, nextStatus, selected.status);
      if (!stillCurrent()) return;
      // Only show the returned state after the server has persisted the operation.
      setSelected(updated); setResult({ error: false, text: t("demoStatusSaved") });
      setReload(value => value + 1);
    } catch (error) {
      if (stillCurrent()) {
        if (error instanceof ApiError && error.status === 409) {
          setNeedsRefresh(true); setReload(value => value + 1);
        }
        setResult({ error: true, text: failureText(error) });
      }
    } finally { if (stillCurrent()) setBusy(false); }
  }
  function statusLabel(value: DemoRequestStatus) { return t(`demoStatus${value}`); }
  function products(request: DemoRequest) {
    return (request.products?.length ? request.products : [request.product]).map(value => t(`demoProduct${value}`)).join(" · ");
  }
  function date(value: string) { return value && Number.isFinite(Date.parse(value)) ? formatDate(value) : t("notAvailable"); }
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  if (!canRead) return <section className="content-section"><EmptyState text={t("demoForbidden")} /></section>;
  return <section className="content-section demo-inbox" aria-busy={loading}>
    <SectionHeader title={t("demoRequests")} subtitle={t("demoRequestsHelp")} />
    <form className="toolbar demo-inbox-filters" role="search" aria-label={t("demoSearch")} onSubmit={search}>
      <label className="toolbar-field">{t("demoStatus")}
        <select className="control-input" value={status} disabled={loading || busy} onChange={event => { setStatus(event.target.value as DemoRequestStatus | ""); setPage(0); }}>
          <option value="">{t("demoAllStatuses")}</option>
          {demoRequestStatuses.map(value => <option value={value} key={value}>{statusLabel(value)}</option>)}
        </select>
      </label>
      <label className="toolbar-field demo-inbox-search">{t("demoSearch")}
        <input ref={searchInput} className="control-input" type="search" value={searchDraft} maxLength={160} placeholder={t("demoSearchHint")} disabled={busy} onChange={event => setSearchDraft(event.target.value)} />
      </label>
      <button className="secondary-button" type="submit" disabled={loading || busy}>{t("demoApplySearch")}</button>
      {(query || searchDraft) && <button className="small-button" type="button" disabled={loading || busy} onClick={() => { setSearchDraft(""); setQuery(""); setPage(0); }}>{t("demoClearSearch")}</button>}
    </form>
    {loadError ? <RetryError message={loadError} onRetry={() => setReload(value => value + 1)} /> : loading ? <div role="status"><EmptyState text={t("loadingSaas")} /></div> : items.length === 0 ? <EmptyState text={t("demoEmpty")} /> : <div className="table-wrap">
      <table>
        <caption className="demo-inbox-caption">{t("demoCount").replace("{total}", String(total))}</caption>
        <thead><tr><th scope="col">{t("demoCreatedAt")}</th><th scope="col">{t("demoContact")}</th><th scope="col">{t("demoCompany")}</th><th scope="col">{t("demoProducts")}</th><th scope="col">{t("demoStatus")}</th><th scope="col">{t("demoDetails")}</th></tr></thead>
        <tbody>{items.map(request => <tr key={request.id}>
          <td>{date(request.createdAt)}</td><td><strong>{request.name}</strong><br /><span>{request.email}</span></td><td>{request.company}</td><td>{products(request)}</td>
          <td><StatusPill status={statusLabel(request.status)} tone={request.status === "NEW" ? "warning" : request.status === "CONTACTED" ? "ok" : "muted"} /></td>
          <td><button className="small-button" type="button" disabled={busy} aria-label={`${t("demoDetails")}: ${request.name}`} onClick={() => open(request)}>{t("demoDetails")}</button></td>
        </tr>)}</tbody>
      </table>
    </div>}
    {!loadError && <nav className="pagination-controls" aria-label={t("pageLabel")}>
      <button className="small-button" type="button" disabled={loading || busy || page === 0} onClick={() => setPage(value => value - 1)}>{t("previousPage")}</button>
      <span role="status">{t("pageLabel")} {page + 1} / {pages} · {t("demoCount").replace("{total}", String(total))}</span>
      <button className="small-button" type="button" disabled={loading || busy || page + 1 >= pages} onClick={() => setPage(value => value + 1)}>{t("nextPage")}</button>
    </nav>}
    {selected && <WorkspaceDialog title={selected.company} subtitle={t("demoDetails")} closeLabel={t("demoCloseDetail")} busy={busy} onClose={() => { setSelected(null); setResult(null); }} restoreFocus={() => opener.current?.isConnected ? opener.current : searchInput.current} className="demo-request-dialog">
      {result && <div className={`notice ${result.error ? "error" : "success"}`} role={result.error ? "alert" : "status"}>{result.text}</div>}
      <dl className="demo-request-details">
        <div><dt>{t("demoContact")}</dt><dd>{selected.name}</dd></div><div><dt>{t("demoCompany")}</dt><dd>{selected.company}</dd></div>
        <div><dt>{t("demoEmail")}</dt><dd>{selected.email}</dd></div><div><dt>{t("demoPhone")}</dt><dd>{selected.phone || t("notAvailable")}</dd></div>
        <div><dt>{t("demoProducts")}</dt><dd>{products(selected)}</dd></div><div><dt>{t("demoStatus")}</dt><dd>{statusLabel(selected.status)}</dd></div>
        <div><dt>{t("demoCreatedAt")}</dt><dd>{date(selected.createdAt)}</dd></div><div><dt>{t("demoLocale")}</dt><dd>{selected.locale}</dd></div>
        <div className="demo-request-message"><dt>{t("demoMessage")}</dt><dd>{selected.message || t("notAvailable")}</dd></div>
        <div><dt>{t("demoPrivacyAcceptedAt")}</dt><dd>{date(selected.privacyAcceptedAt)}</dd></div><div><dt>{t("demoReference")}</dt><dd>{selected.id}</dd></div>
      </dl>
      <details className="demo-request-attribution"><summary>{t("demoAttribution")}</summary><dl className="demo-request-details">
        <div><dt>{t("demoLandingPath")}</dt><dd>{selected.landingPath || t("notAvailable")}</dd></div><div><dt>{t("demoReferrer")}</dt><dd>{selected.referrer || t("notAvailable")}</dd></div>
        <div><dt>{t("demoUtmSource")}</dt><dd>{selected.utmSource || "—"}</dd></div>
        <div><dt>{t("demoUtmMedium")}</dt><dd>{selected.utmMedium || "—"}</dd></div>
        <div><dt>{t("demoUtmCampaign")}</dt><dd>{selected.utmCampaign || "—"}</dd></div>
      </dl></details>
      {canManage ? <div className="demo-request-actions" aria-label={t("demoUpdateStatus")} aria-busy={busy}>
        {selected.status !== "CONTACTED" && <button className="primary-button" type="button" disabled={busy || needsRefresh} onClick={() => void updateStatus("CONTACTED")}>{t("demoMarkContacted")}</button>}
        {selected.status !== "CLOSED" && <button className="secondary-button" type="button" disabled={busy || needsRefresh} onClick={() => void updateStatus("CLOSED")}>{t("demoMarkClosed")}</button>}
        {selected.status !== "NEW" && <button className="small-button" type="button" disabled={busy || needsRefresh} onClick={() => void updateStatus("NEW")}>{t("demoReopen")}</button>}
        {needsRefresh && <button className="secondary-button" type="button" disabled={busy} onClick={() => void refreshSelected()}>{t("demoRefreshDetail")}</button>}
        {busy && <span role="status">{t("demoSaving")}</span>}
      </div> : <p className="module-help">{t("demoReadOnly")}</p>}
      <p className="module-help">{t("demoManualFollowup")}</p>
    </WorkspaceDialog>}
  </section>;
}
