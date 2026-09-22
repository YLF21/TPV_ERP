import { useEffect, useRef, useState } from "react";
import { useRemote } from "../../app/RefreshContext";
import { api } from "../../lib/api";
import { LicenseRow, workspaceApi } from "../../lib/workspace-api";
import { CompanySummary, Credentials, InstallationSummary } from "../../lib/types";
import { useWorkspaceLabels } from "../../i18n/workspace";
import { useI18n } from "../../i18n";
import { EmptyState, Input } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";
import { formatDate, formatCurrency, formatPickerDate, daysUntil, licenseStatusPresentation, billingStatusLabel } from "../../shared/lib";
import { DirectoryFilters } from "../../shared/filters/DirectoryFilters";
import { CompanyPicker } from "../../shared/companies/CompanyPicker";
import { SaasDataTable, type DataColumn } from "../../shared/table/SaasDataTable";
import { useTableLabels } from "../../shared/table/table-labels";
import { LicenseConfiguration } from "./LicenseConfiguration";
import { usePagedDirectory } from "../../shared/usePagedDirectory";
import { useLicenseLabels } from "./license-labels";
import "./license-workspace.css";

type LicenseColumn = "reference" | "status" | "company" | "stores" | "expiry" | "days" | "validation" | "sync" | "windows" | "pda" | "installations" | "billing" | "debt";
type LicenseWorkspaceProps = {
  credentials: Credentials; installations: InstallationSummary[]; permissions: Set<string>;
  onChanged: () => Promise<boolean>;
};
export function LicenseWorkspace(props: LicenseWorkspaceProps) {
  // A new session starts with its own filters, cached company names and directory requests.
  return <LicenseWorkspaceContent key={props.credentials.accessToken} {...props} />;
}

function LicenseWorkspaceContent({ credentials, installations, permissions, onChanged }: LicenseWorkspaceProps) {
  const l = useWorkspaceLabels(); const own = useLicenseLabels(); const tableLabels = useTableLabels(); const { t } = useI18n();
  const companies = useRemote(() => api.companies(credentials), [credentials.accessToken]);
  const previousCompanies = useRef<CompanySummary[]>([]);
  useEffect(() => { if (companies.data) previousCompanies.current = companies.data; }, [companies.data]);
  const companyOptions = companies.data ?? previousCompanies.current;
  const [selectedCompany, setSelectedCompany] = useState<{ id: string; name: string } | null>(null);
  const [companyId, setCompanyId] = useState(""); const [q, setQ] = useState(""); const [status, setStatus] = useState("VALIDA");
  const [expiresBefore, setExpiresBefore] = useState(""); const [hasConnections, setHasConnections] = useState("");
  const [billingStatus, setBillingStatus] = useState("");
  const currentCompany = companyOptions.find(company => company.companyId === companyId);
  const companyName = currentCompany?.companyName || (selectedCompany?.id === companyId ? selectedCompany.name : "") || l("company");
  useEffect(() => {
    if (currentCompany) setSelectedCompany({ id: currentCompany.companyId, name: currentCompany.companyName });
  }, [currentCompany?.companyId, currentCompany?.companyName]);
  const [sort, setSort] = useState({ key: "validUntil", direction: "asc" as "asc" | "desc" });
  const [detail, setDetail] = useState<LicenseRow | null>(null);
  const tableScroll = useRef<HTMLDivElement>(null);
  const mounted = useRef(false);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const state = usePagedDirectory<LicenseRow>(page => workspaceApi.licenses(credentials, {
    companyId, q, status, expiresBefore: expiresBefore ? new Date(expiresBefore + "T00:00:00").toISOString() : "",
    hasConnections, billingStatus, page, size: 25, sortBy: sort.key, sortDirection: sort.direction.toUpperCase(),
  }), [credentials.accessToken, companyId, q, status, expiresBefore, hasConnections, billingStatus, sort.key, sort.direction]);
  useEffect(() => { setDetail(null); }, [credentials.accessToken, companyId, q, status, expiresBefore, hasConnections, billingStatus, sort.key, sort.direction]);
  useEffect(() => { if (state.rows.length === 0) tableScroll.current?.scrollTo({ top: 0 }); }, [state.rows.length]);
  useEffect(() => {
    const viewport = tableScroll.current;
    if (!viewport || detail || state.loading || state.error || !state.hasMore) return;
    const loadNearEnd = () => { if (viewport.scrollHeight - viewport.clientHeight - viewport.scrollTop <= 160) state.loadMore(); };
    const observer = new ResizeObserver(loadNearEnd);
    observer.observe(viewport); viewport.addEventListener("scroll", loadNearEnd, { passive: true }); loadNearEnd();
    return () => { observer.disconnect(); viewport.removeEventListener("scroll", loadNearEnd); };
  }, [detail, state.loading, state.error, state.hasMore, state.rows.length, state.loadMore]);
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
  function restoreFocus() {
    const row = detail ? tableScroll.current?.querySelector<HTMLTableRowElement>('tr[data-row-id="' + CSS.escape(detail.id) + '"]') : null;
    return row ?? tableScroll.current?.querySelector<HTMLElement>("tbody tr, button") ?? null;
  }
  async function refreshAfterChange() {
    // A successful global refresh already invalidates the paged directory.
    // Still refresh this list if an unrelated dashboard request fails.
    if (!await onChanged() && mounted.current) state.reload();
  }
  function clearCompany() { setCompanyId(""); setSelectedCompany(null); }
  function clearFilters() {
    clearCompany(); setQ(""); setStatus(""); setExpiresBefore(""); setHasConnections(""); setBillingStatus("");
  }
  const chips = [
    { key: "q", label: l("search"), value: q.trim(), onRemove: () => setQ("") },
    { key: "companyId", label: l("company"), value: companyId ? companyName : "", onRemove: clearCompany },
    { key: "status", label: l("status"), value: status === "VALIDA" ? l("activeLicenses") : status === "CADUCADA" ? t("expiredStatus") : status === "BLOQUEADA_MANUAL" ? t("blocked") : "", onRemove: () => setStatus("") },
    { key: "expiresBefore", label: l("before"), value: expiresBefore ? formatPickerDate(expiresBefore, true) : "", onRemove: () => setExpiresBefore("") },
    { key: "hasConnections", label: t("installations"), value: hasConnections === "true" ? l("connections") : hasConnections === "false" ? l("noConnections") : "", onRemove: () => setHasConnections("") },
    { key: "billingStatus", label: l("billing"), value: billingStatus ? billingStatusLabel(billingStatus, t) : "", onRemove: () => setBillingStatus("") },
  ].filter(chip => chip.value !== "");
  return <section className="content-section table-workspace saas-license-workspace">
    <h2>{l("activeLicenses")}</h2>
      <div className="saas-license-filters">
        <DirectoryFilters label={l("activeLicenses")} chips={chips} onClear={clearFilters} advanced={<>
          <Input label={l("before")} type="date" value={expiresBefore} onChange={setExpiresBefore} />
          <label>{l("connections")}<select className="control-input" aria-label={l("connections")} value={hasConnections} onChange={event => setHasConnections(event.target.value)}>
            <option value="">{l("all")}</option><option value="true">{l("connections")}</option><option value="false">{l("noConnections")}</option>
          </select></label>
          <label>{l("billing")}<select className="control-input" aria-label={l("billing")} value={billingStatus} onChange={event => setBillingStatus(event.target.value)}>
            <option value="">{l("all")}</option>{["PAGADO", "PENDIENTE", "VENCIDO", "IMPAGADO"].map(value => <option key={value} value={value}>{billingStatusLabel(value, t)}</option>)}
          </select></label>
        </>}>
          <Input label={l("search")} value={q} onChange={setQ} />
          <CompanyPicker companies={companyOptions} value={companyId} required={false} selectedLabel={companyName} onChange={id => {
            setCompanyId(id);
            setSelectedCompany(id ? { id, name: companyOptions.find(company => company.companyId === id)?.companyName || l("company") } : null);
          }} />
          <label>{l("status")}<select className="control-input" aria-label={l("status")} value={status} onChange={event => setStatus(event.target.value)}>
            <option value="VALIDA">{l("activeLicenses")}</option><option value="">{l("allLicenses")}</option><option value="CADUCADA">{t("expiredStatus")}</option><option value="BLOQUEADA_MANUAL">{t("blocked")}</option>
          </select></label>
        </DirectoryFilters>
      </div>
      {companies.error && <LoadState {...companies} />}
      <p className="saas-license-table-hint">{tableLabels("openHint")}</p>
      <div className="saas-license-table-region" aria-busy={state.loading}>
        <SaasDataTable username={credentials.username} tableKey="licenses" label={l("activeLicenses")} columns={columns} rows={state.rows} scrollRef={tableScroll} sort={sort}
          onSort={key => { setSort(current => ({ key, direction: current.key === key && current.direction === "asc" ? "desc" : "asc" })); }} onOpen={setDetail} disabled={detail !== null} />
        <LoadState loading={state.loading} error={state.error} reload={state.retry} />
        {!state.loading && !state.error && state.rows.length === 0 && <EmptyState text={l("empty")} />}
      </div>
    {detail && <LicenseConfiguration key={detail.id} credentials={credentials} license={detail} installations={installations} permissions={permissions}
      onChanged={() => void refreshAfterChange()} onClose={() => setDetail(null)} restoreFocus={restoreFocus} />}
  </section>;
}
