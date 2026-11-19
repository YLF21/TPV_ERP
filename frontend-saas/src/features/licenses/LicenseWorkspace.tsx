import { useEffect, useRef, useState } from "react";
import { useRemote } from "../../app/RefreshContext";
import { api, request } from "../../lib/api";
import { LicenseRow, workspaceApi, type StoreRow } from "../../lib/workspace-api";
import { CompanySummary, Credentials, InstallationSummary } from "../../lib/types";
import { useWorkspaceLabels } from "../../i18n/workspace";
import { useI18n } from "../../i18n";
import { EmptyState, Input } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";
import { formatDate, formatCurrency, formatPickerDate, daysUntil, licenseStatusPresentation, billingStatusLabel, utcInputToInstant } from "../../shared/lib";
import { DirectoryFilters } from "../../shared/filters/DirectoryFilters";
import { CompanyPicker } from "../../shared/companies/CompanyPicker";
import { SaasDataTable, type DataColumn } from "../../shared/table/SaasDataTable";
import { DirectoryExcelExport } from "../../shared/table/DirectoryExcelExport";
import { useDirectoryPosition, useDirectoryState } from "../../shared/directory-navigation";
import { DirectoryReturn } from "../../shared/DirectoryActions";
import { useTableLabels } from "../../shared/table/table-labels";
import { LicenseConfiguration } from "./LicenseConfiguration";
import { usePagedDirectory } from "../../shared/usePagedDirectory";
import { useLicenseLabels } from "./license-labels";
import { licenseExpiryShortcut, readLicenseDirectoryFilters, type LicenseDirectoryFilters } from "./license-directory.mjs";
import "./license-workspace.css";

type LicenseColumn = "reference" | "status" | "actions" | "company" | "stores" | "expiry" | "days" | "validation" | "sync" | "windows" | "pda" | "installations" | "billing" | "debt";
const expiryShortcuts = [{ days: 7, label: "expiresIn7" }, { days: 30, label: "expiresIn30" }] as const;
type LicenseWorkspaceProps = {
  credentials: Credentials; installations: InstallationSummary[]; permissions: Set<string>;
  onChanged: () => Promise<boolean>;
  onTitleChange?: (title: string) => void;
};
export function LicenseWorkspace(props: LicenseWorkspaceProps) {
  // A new session starts with its own filters, cached company names and directory requests.
  return <LicenseWorkspaceContent key={props.credentials.accessToken} {...props} />;
}

function readLinkedLicenseId() {
  const [path, query] = window.location.hash.split("?");
  return path === "#/licenses/detail" ? new URLSearchParams(query).get("licenseId") || null : null;
}

function LicenseWorkspaceContent({ credentials, installations, permissions, onChanged, onTitleChange }: LicenseWorkspaceProps) {
  const l = useWorkspaceLabels(); const own = useLicenseLabels(); const tableLabels = useTableLabels(); const { t } = useI18n();
  const canRenew = permissions.has("RENEW_LICENSE");
  useDirectoryPosition(credentials.accessToken);
  const [filters, setFilters] = useDirectoryState<LicenseDirectoryFilters>(`${credentials.accessToken}:licenses`, readLicenseDirectoryFilters);
  const { companyId, storeId, q, status, expiresBefore, upcoming, hasConnections, billingStatus, sort } = filters;
  const expiryLimit = upcoming?.before ?? (expiresBefore ? utcInputToInstant(expiresBefore + "T00:00") : "");
  const queryFilters = { companyId, storeId, q, status, expiresBefore: expiryLimit, hasConnections, billingStatus };
  const heading = status === "VALIDA" ? l("activeLicenses") : status === "CADUCADA" ? own("expiredLicenses")
    : status === "BLOQUEADA_MANUAL" ? own("blockedLicenses") : own("allLicenses");
  useEffect(() => { onTitleChange?.(heading); }, [heading, onTitleChange]);
  const companies = useRemote(() => api.companies(credentials), [credentials.accessToken]);
  const scopedStore = useRemote(() => storeId ? request<StoreRow>(credentials, `/api/v1/admin/stores/${encodeURIComponent(storeId)}`) : Promise.resolve(null),
    [credentials.accessToken, storeId]);
  const previousCompanies = useRef<CompanySummary[]>([]);
  useEffect(() => { if (companies.data) previousCompanies.current = companies.data; }, [companies.data]);
  const companyOptions = companies.data ?? previousCompanies.current;
  const [selectedCompany, setSelectedCompany] = useState<{ id: string; name: string } | null>(null);
  const currentCompany = companyOptions.find(company => company.companyId === companyId);
  const companyName = currentCompany?.companyName || (selectedCompany?.id === companyId ? selectedCompany.name : "") || l("company");
  useEffect(() => {
    if (currentCompany) setSelectedCompany({ id: currentCompany.companyId, name: currentCompany.companyName });
  }, [currentCompany?.companyId, currentCompany?.companyName]);
  const [detail, setDetail] = useState<{ row: LicenseRow; renew: boolean } | null>(null);
  const [routeLicenseId, setRouteLicenseId] = useState(readLinkedLicenseId);
  const linkedLicenseId = permissions.has("VIEW_ADMIN_DATA") ? routeLicenseId : null;
  const detailId = linkedLicenseId ?? detail?.row.id;
  const tableScroll = useRef<HTMLDivElement>(null);
  const mounted = useRef(false);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const state = usePagedDirectory<LicenseRow>(page => workspaceApi.licenses(credentials, {
    ...queryFilters, page, size: 25, sortBy: sort.key, sortDirection: sort.direction.toUpperCase(),
  }), [credentials.accessToken, companyId, storeId, q, status, expiryLimit, hasConnections, billingStatus, sort.key, sort.direction]);
  const store = scopedStore.data?.id === storeId ? scopedStore.data
    : storeId ? state.rows.flatMap(row => row.stores).find(store => store.id === storeId) : null;
  const storeLabel = store ? `${store.internalCode ?? store.code} · ${store.name}` : storeId;
  useEffect(() => {
    function syncRoute() {
      setRouteLicenseId(readLinkedLicenseId());
      setDetail(null);
    }
    window.addEventListener("hashchange", syncRoute);
    window.addEventListener("popstate", syncRoute);
    return () => {
      window.removeEventListener("hashchange", syncRoute);
      window.removeEventListener("popstate", syncRoute);
    };
  }, []);
  useEffect(() => { setDetail(null); }, [credentials.accessToken, companyId, storeId, q, status, expiryLimit, hasConnections, billingStatus, sort.key, sort.direction]);
  useEffect(() => {
    const viewport = tableScroll.current;
    if (!viewport || detailId || state.loading || state.error || !state.hasMore) return;
    const loadNearEnd = () => { if (viewport.scrollHeight - viewport.clientHeight - viewport.scrollTop <= 160) state.loadMore(); };
    const observer = new ResizeObserver(loadNearEnd);
    observer.observe(viewport); viewport.addEventListener("scroll", loadNearEnd, { passive: true }); loadNearEnd();
    return () => { observer.disconnect(); viewport.removeEventListener("scroll", loadNearEnd); };
  }, [detailId, state.loading, state.error, state.hasMore, state.rows.length, state.loadMore]);
  const columns: DataColumn<LicenseRow, LicenseColumn>[] = [
    { key: "reference", label: l("reference"), defaultWidth: 180, sortKey: "reference", render: row => row.reference },
    { key: "status", label: l("status"), defaultWidth: 145, sortKey: "status", render: row => licenseStatusPresentation(row.status, t).label },
    { key: "company", label: l("company"), defaultWidth: 235, sortKey: "companyName", render: row => <span title={`${row.companyName} · ${row.taxId}`}>{row.companyName} <small>{row.taxId}</small></span> },
    { key: "stores", label: l("stores"), defaultWidth: 250, sortKey: "storeCode", render: row => { const text = row.stores.map(store => `${store.internalCode ?? store.code} · ${store.name}`).join(" / "); return <span title={text}>{text || "—"}</span>; } },
    { key: "expiry", label: l("expiry"), defaultWidth: 170, sortKey: "validUntil", render: row => formatDate(row.validUntil) },
    { key: "days", label: l("days"), defaultWidth: 145, align: "right", render: row => daysUntil(row.validUntil) },
    { key: "validation", label: l("validation"), defaultWidth: 205, sortKey: "lastValidatedAt", render: row => row.lastValidatedAt ? formatDate(row.lastValidatedAt) : "—" },
    { key: "sync", label: l("sync"), defaultWidth: 215, sortKey: "lastSyncAt", render: row => row.lastSyncAt ? formatDate(row.lastSyncAt) : "—" },
    { key: "windows", label: own("windows"), defaultWidth: 165, sortKey: "maxWindows", align: "right", render: row => row.maxWindows },
    { key: "pda", label: own("pda"), defaultWidth: 145, sortKey: "maxPda", align: "right", render: row => row.maxPda },
    { key: "installations", label: l("installations"), defaultWidth: 205, sortKey: "activeInstallations", align: "right", render: row => row.activeInstallations },
    { key: "billing", label: l("billing"), defaultWidth: 250, sortKey: "billingStatus", render: row => row.companyBillingStatus ? billingStatusLabel(row.companyBillingStatus, t) : "—" },
    { key: "debt", label: l("debt"), defaultWidth: 180, align: "right", render: row => row.companyDebt.map(debt => formatCurrency(debt.outstanding, debt.currency)).join(" / ") || l("paid") },
  ];
  if (canRenew) columns.splice(2, 0, {
    key: "actions", label: t("actions"), defaultWidth: 128,
    render: row => <button type="button" className="small-button saas-license-renew" disabled={Boolean(detailId)}
      title={`${own("renew")} ${row.reference}`} onClick={() => setDetail({ row, renew: true })}
      onDoubleClick={event => event.stopPropagation()}>{own("renew")}</button>,
  });
  function restoreFocus() {
    const row = detailId ? tableScroll.current?.querySelector<HTMLTableRowElement>('tr[data-row-id="' + CSS.escape(detailId) + '"]') : null;
    if (detail?.renew && row) return row.querySelector<HTMLButtonElement>(".saas-license-renew") ?? row;
    return row ?? tableScroll.current?.querySelector<HTMLElement>("tbody tr, button") ?? null;
  }
  function closeDetail() {
    if (linkedLicenseId) {
      const params = new URLSearchParams(window.location.hash.split("?", 2)[1]);
      params.delete("licenseId");
      window.history.replaceState(window.history.state, "", `#/licenses${params.size ? `?${params}` : ""}`);
      setRouteLicenseId(null);
    }
    setDetail(null);
  }
  async function refreshAfterChange() {
    // A successful global refresh already invalidates the paged directory.
    // Still refresh this list if an unrelated dashboard request fails.
    if (!await onChanged() && mounted.current) state.reload();
  }
  function setFilter<K extends keyof LicenseDirectoryFilters>(key: K, value: LicenseDirectoryFilters[K]) {
    setFilters(current => ({ ...current, [key]: value }));
  }
  function changeCompany(id: string) {
    setFilters(current => ({ ...current, companyId: id, storeId: current.companyId === id ? current.storeId : "" }));
    setSelectedCompany(id ? { id, name: companyOptions.find(company => company.companyId === id)?.companyName || l("company") } : null);
  }
  function clearFilters() {
    setSelectedCompany(null);
    setFilters({ ...readLicenseDirectoryFilters("#/licenses?status="), sort });
  }
  function selectExpiryShortcut(days: 7 | 30) {
    const shortcut = licenseExpiryShortcut(days);
    setFilters(current => ({ ...current, status: "VALIDA", expiresBefore: "",
      upcoming: current.upcoming?.days === days ? null : shortcut }));
  }
  function changeStatus(nextStatus: LicenseDirectoryFilters["status"]) {
    setFilters(current => ({ ...current, status: nextStatus, upcoming: nextStatus === "VALIDA" ? current.upcoming : null }));
  }
  const chips = [
    { key: "q", label: l("search"), value: q.trim(), onRemove: () => setFilter("q", "") },
    { key: "companyId", label: l("company"), value: companyId ? companyName : "", onRemove: () => changeCompany("") },
    { key: "storeId", label: l("store"), value: storeLabel, onRemove: () => setFilter("storeId", "") },
    { key: "status", label: l("status"), value: status === "VALIDA" ? l("activeLicenses") : status === "CADUCADA" ? t("expiredStatus") : status === "BLOQUEADA_MANUAL" ? t("blocked") : "", onRemove: () => changeStatus("") },
    { key: "expiresBefore", label: upcoming ? l("before") : `${l("before")} (UTC)`, value: upcoming ? formatDate(upcoming.before) : expiresBefore ? formatPickerDate(expiresBefore, true) : "",
      onRemove: () => setFilters(current => ({ ...current, upcoming: null, expiresBefore: "" })) },
    { key: "hasConnections", label: t("installations"), value: hasConnections === "true" ? l("connections") : hasConnections === "false" ? l("noConnections") : "", onRemove: () => setFilter("hasConnections", "") },
    { key: "billingStatus", label: l("billing"), value: billingStatus ? billingStatusLabel(billingStatus, t) : "", onRemove: () => setFilter("billingStatus", "") },
  ].filter(chip => chip.value !== "");
  return <section className="content-section table-workspace saas-license-workspace">
    <div className="directory-license-heading">
      <h2>{heading}</h2>
      <div className="saas-license-heading-actions">
        <DirectoryReturn />
        <div className="saas-license-shortcuts" role="group" aria-label={own("expiryShortcuts")}>
          {expiryShortcuts.map(({ days, label }) => <button key={days} type="button" className="secondary-button"
            aria-pressed={upcoming?.days === days} onClick={() => selectExpiryShortcut(days)}>{own(label)}</button>)}
        </div>
        <DirectoryExcelExport credentials={credentials} resource="licenses" sort={sort} filters={queryFilters}
          disabled={state.loading || Boolean(state.error)} />
      </div>
    </div>
      <div className="saas-license-filters">
        <DirectoryFilters label={heading} chips={chips} onClear={clearFilters} advanced={<>
          <Input label={`${l("before")} (UTC)`} type="date" value={expiresBefore}
            onChange={value => setFilters(current => ({ ...current, expiresBefore: value, upcoming: null }))} />
          <label>{l("connections")}<select className="control-input" aria-label={l("connections")} value={hasConnections} onChange={event => setFilter("hasConnections", event.target.value)}>
            <option value="">{l("all")}</option><option value="true">{l("connections")}</option><option value="false">{l("noConnections")}</option>
          </select></label>
          <label>{l("billing")}<select className="control-input" aria-label={l("billing")} value={billingStatus} onChange={event => setFilter("billingStatus", event.target.value)}>
            <option value="">{l("all")}</option>{["PAGADO", "PENDIENTE", "VENCIDO", "IMPAGADO"].map(value => <option key={value} value={value}>{billingStatusLabel(value, t)}</option>)}
          </select></label>
        </>}>
          <Input label={l("search")} value={q} onChange={value => setFilter("q", value)} />
          <CompanyPicker companies={companyOptions} value={companyId} required={false} selectedLabel={companyName} onChange={changeCompany} />
          <label>{l("status")}<select className="control-input" aria-label={l("status")} value={status} onChange={event => changeStatus(event.target.value as LicenseDirectoryFilters["status"])}>
            <option value="VALIDA">{l("activeLicenses")}</option><option value="">{l("allLicenses")}</option><option value="CADUCADA">{t("expiredStatus")}</option><option value="BLOQUEADA_MANUAL">{t("blocked")}</option>
          </select></label>
        </DirectoryFilters>
      </div>
      {companies.error && <LoadState {...companies} />}
      <p className="saas-license-table-hint">{tableLabels("openHint")}</p>
      <div className="saas-license-table-region" aria-busy={state.loading}>
        <SaasDataTable key={String(canRenew)} username={credentials.username} tableKey="licenses" label={heading} columns={columns} rows={state.rows} scrollRef={tableScroll} sort={sort}
          legacyColumnOrder={["reference", "status", "company", "stores", "expiry", "days", "validation", "sync", "windows", "pda", "installations", "billing", "debt"]}
          onSort={key => setFilters(current => ({ ...current, sort: { key, direction: current.sort.key === key && current.sort.direction === "asc" ? "desc" : "asc" } }))}
          onOpen={row => setDetail({ row, renew: false })} disabled={Boolean(detailId)} />
        <LoadState loading={state.loading} error={state.error} reload={state.retry} />
        {!state.loading && !state.error && state.rows.length === 0 && <EmptyState text={l("empty")} />}
      </div>
    {detailId && <LicenseConfiguration key={detailId} credentials={credentials} licenseId={detailId} reference={detail?.row.reference} installations={installations} permissions={permissions}
      focusRenewal={Boolean(detail?.renew)}
      onChanged={() => void refreshAfterChange()} onClose={closeDetail} restoreFocus={restoreFocus} />}
  </section>;
}
