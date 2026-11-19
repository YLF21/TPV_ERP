import { useEffect, useRef } from "react";
import { useRemote } from "../../app/RefreshContext";
import { api } from "../../lib/api";
import type { StoreRow } from "../../lib/workspace-api";
import type { CompanySummary, Credentials } from "../../lib/types";
import { useWorkspaceLabels } from "../../i18n/workspace";
import { useI18n } from "../../i18n";
import { EmptyState, Input } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";
import { formatCurrency, formatDate } from "../../shared/lib";
import type { Notice } from "../../shared/types";
import { SaasDataTable, type DataColumn } from "../../shared/table/SaasDataTable";
import { DirectoryExcelExport } from "../../shared/table/DirectoryExcelExport";
import { DirectoryLink, DirectoryReturn } from "../../shared/DirectoryActions";
import { useDirectoryPosition, useDirectoryState } from "../../shared/directory-navigation";
import { DirectoryFilters, type DirectoryFilterChip } from "../../shared/filters/DirectoryFilters";
import { CompanyPicker } from "../../shared/companies/CompanyPicker";
import { useStoreLabels } from "./labels";
import { useStoreDirectory } from "./useStoreDirectory";
import { StoreDialog } from "./StoreDialog";
import "./stores.css";

type StoresViewProps = {
  credentials: Credentials; permissions: Set<string>; onNotice: (notice: Notice) => void;
};
export function StoresView(props: StoresViewProps) {
  return <StoresDirectory key={props.credentials.accessToken} {...props} />;
}

function StoresDirectory({ credentials, permissions, onNotice }: StoresViewProps) {
  const l = useWorkspaceLabels();
  const s = useStoreLabels();
  const { t, language } = useI18n();
  const companiesState = useRemote(() => api.companies(credentials), [credentials.accessToken]);
  const previousCompanies = useRef<{ token: string; rows: CompanySummary[] } | null>(null);
  if (companiesState.data) previousCompanies.current = { token: credentials.accessToken, rows: companiesState.data };
  const companies = companiesState.data ?? (previousCompanies.current?.token === credentials.accessToken ? previousCompanies.current.rows : []);
  const identity = credentials.accessToken + ":stores:";
  useDirectoryPosition(credentials.accessToken);
  const [companyFilter, setCompanyFilter] = useDirectoryState(identity + "company", () => ({ id: new URLSearchParams(window.location.hash.split("?")[1]).get("companyId") ?? "", name: "" }));
  const companyId = companyFilter.id;
  const [q, setQ] = useDirectoryState(identity + "query", "");
  const [active, setActive] = useDirectoryState(identity + "active", "");
  const [commercialConfiguration, setCommercialConfiguration] = useDirectoryState(identity + "commercial", "");
  const [sort, setSort] = useDirectoryState(identity + "sort", { key: "code", direction: "asc" as "asc" | "desc" });
  const [editing, setEditing] = useDirectoryState<StoreRow | "new" | null>(identity + "editing", null);
  const state = useStoreDirectory(credentials, { companyId, q, active, commercialConfiguration, sortBy: sort.key, sortDirection: sort.direction === "asc" ? "ASC" : "DESC" });
  const commercialLabel = language === "zh" ? "商业配置" : language === "en" ? "Commercial configuration" : "Configuración comercial";
  const pendingLabel = language === "zh" ? "待完善" : language === "en" ? "Pending" : "Pendiente";
  const completeLabel = language === "zh" ? "完整" : language === "en" ? "Complete" : "Completa";
  const licensesLabel = language === "zh" ? "查看许可证" : language === "en" ? "View licenses" : "Ver licencias";
  const tableScroll = useRef<HTMLDivElement>(null);
  const createButton = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (state.rows.length === 0) tableScroll.current?.scrollTo({ top: 0 });
  }, [state.rows.length]);
  useEffect(() => {
    const viewport = tableScroll.current;
    if (!viewport || editing || state.loading || state.error || !state.hasMore) return;
    const loadNearEnd = () => {
      if (viewport.scrollHeight - viewport.clientHeight - viewport.scrollTop <= 160) state.loadMore();
    };
    const observer = new ResizeObserver(loadNearEnd);
    observer.observe(viewport);
    viewport.addEventListener("scroll", loadNearEnd, { passive: true });
    loadNearEnd();
    return () => { observer.disconnect(); viewport.removeEventListener("scroll", loadNearEnd); };
  }, [editing, state.loading, state.error, state.hasMore, state.rows.length, state.loadMore]);
  function restoreFocus() {
    const row = editing && editing !== "new" ? tableScroll.current?.querySelector<HTMLTableRowElement>('tr[data-row-id="' + CSS.escape(editing.id) + '"]') : null;
    return row ?? createButton.current ?? tableScroll.current?.querySelector<HTMLElement>("tbody tr, button") ?? null;
  }
  const columns: DataColumn<StoreRow, string>[] = [
    { key: "code", label: s("localCodeColumn"), defaultWidth: 145, sortKey: "code", render: row => <strong>{row.code}</strong> },
    { key: "name", label: l("store"), defaultWidth: 230, sortKey: "name", render: row => <strong>{row.name}</strong> },
    { key: "internalCode", label: l("internalCode"), defaultWidth: 175, sortKey: "internalCode", render: row => <strong>{row.internalCode ?? l("codePending")}</strong> },
    { key: "companyName", label: l("company"), defaultWidth: 250, sortKey: "companyName", render: row => <DirectoryLink view="companies" parameters={{ companyId: row.companyId }}>{row.companyName}</DirectoryLink> },
    { key: "licenses", label: language === "zh" ? "许可证" : language === "en" ? "Licenses" : "Licencias", defaultWidth: 130, render: row => <DirectoryLink view="licenses" parameters={{ companyId: row.companyId, storeId: row.id, status: "" }} label={`${licensesLabel}: ${row.name}`}>{licensesLabel}</DirectoryLink> },
    { key: "active", label: l("status"), defaultWidth: 105, sortKey: "active", render: row => row.active ? l("active") : l("inactive") },
    { key: "taxRegime", label: t("taxes"), defaultWidth: 120, sortKey: "taxRegime", render: row => row.taxRegime },
    { key: "commercialProfile", label: t("commercialProfile"), defaultWidth: 145, sortKey: "commercialProfile", render: row => row.commercialProfile === "MAYORISTA" ? s("wholesale") : row.commercialProfile === "MINORISTA" ? s("retail") : s("unconfigured") },
    { key: "servicePrice", label: s("servicePrice"), defaultWidth: 150, sortKey: "servicePrice", align: "right", render: row => row.servicePrice == null ? s("unconfigured") : formatCurrency(row.servicePrice, "EUR") },
    { key: "billingPeriod", label: s("billingPeriod"), defaultWidth: 120, sortKey: "billingPeriod", render: row => row.billingPeriod ? s(row.billingPeriod === "MONTHLY" ? "monthly" : "annual") : s("unconfigured") },
    { key: "validUntil", label: t("validUntil"), defaultWidth: 185, sortKey: "validUntil", render: row => row.validUntil ? formatDate(row.validUntil) : s("unconfigured") },
    { key: "maxWindows", label: "Windows", defaultWidth: 115, sortKey: "maxWindows", align: "right", render: row => row.maxWindows },
    { key: "maxPda", label: "PDA", defaultWidth: 80, sortKey: "maxPda", align: "right", render: row => row.maxPda },
    { key: "activeInstallations", label: l("installations"), defaultWidth: 200, sortKey: "activeInstallations", align: "right", render: row => row.activeInstallations },
    { key: "lastSyncAt", label: l("sync"), defaultWidth: 210, sortKey: "lastSyncAt", render: row => row.lastSyncAt ? formatDate(row.lastSyncAt) : "—" },
  ];
  const companyName = companies.find(company => company.companyId === companyId)?.companyName ?? companyFilter.name;
  const chips: DirectoryFilterChip[] = [
    ...(q.trim() ? [{ key: "q", label: l("search"), value: q.trim(), onRemove: () => setQ("") }] : []),
    ...(companyId ? [{ key: "companyId", label: l("company"), value: companyName, onRemove: () => setCompanyFilter({ id: "", name: "" }) }] : []),
    ...(active ? [{ key: "active", label: l("status"), value: active === "true" ? l("active") : l("inactive"), onRemove: () => setActive("") }] : []),
    ...(commercialConfiguration ? [{ key: "commercialConfiguration", label: commercialLabel, value: commercialConfiguration === "pending" ? pendingLabel : completeLabel, onRemove: () => setCommercialConfiguration("") }] : []),
  ];
  return <section className="content-section table-workspace stores-workspace">
    <div className="stores-list-heading">
      <div><h2>{l("stores")}</h2><p className="stores-table-hint">{s("tableHint")}</p></div>
      <div className="directory-table-actions">
        <DirectoryReturn />
        <DirectoryExcelExport credentials={credentials} resource="stores" filters={{ companyId, q, active, commercialConfiguration }} sort={sort}
          disabled={state.loading || Boolean(state.error)} />
        {permissions.has("ADD_COMPANY") && <button className="primary-button" ref={createButton} type="button" disabled={companiesState.loading || Boolean(companiesState.error) || companies.length === 0} onClick={() => setEditing("new")}>{l("create")}</button>}
      </div>
    </div>
    <DirectoryFilters label={l("stores")} chips={chips} onClear={() => { setQ(""); setCompanyFilter({ id: "", name: "" }); setActive(""); setCommercialConfiguration(""); }}
      advanced={<><label>{l("status")}<select className="control-input" aria-label={l("status")} value={active} onChange={event => setActive(event.target.value)}>
        <option value="">{l("all")}</option><option value="true">{l("active")}</option><option value="false">{l("inactive")}</option>
      </select></label></>}>
      <Input label={l("search")} value={q} onChange={setQ} />
      <CompanyPicker companies={companies} value={companyId} required={false} selectedLabel={companyName}
        onChange={id => setCompanyFilter({ id, name: companies.find(company => company.companyId === id)?.companyName ?? "" })} />
      <label>{commercialLabel}<select className="control-input" aria-label={commercialLabel} value={commercialConfiguration} onChange={event => setCommercialConfiguration(event.target.value)}>
        <option value="">{l("all")}</option><option value="pending">{pendingLabel}</option><option value="complete">{completeLabel}</option>
      </select></label>
    </DirectoryFilters>
    <LoadState {...companiesState} />
    <div className="stores-table-region" aria-busy={state.loading}>
      <SaasDataTable username={credentials.username} tableKey="stores" label={l("stores")} columns={columns} rows={state.rows}
        legacyColumnOrder={["internalCode", "companyName", "code", "name", "active", "taxRegime", "commercialProfile", "servicePrice", "billingPeriod", "validUntil", "maxWindows", "maxPda", "activeInstallations", "lastSyncAt"]}
        scrollRef={tableScroll} scrollClassName="stores-table-scroll" sort={sort}
        onSort={key => setSort(current => ({ key, direction: current.key === key && current.direction === "asc" ? "desc" : "asc" }))}
        onOpen={setEditing} disabled={editing !== null} />
      <LoadState loading={state.loading} error={state.error} reload={state.retry} />
      {!state.loading && !state.error && state.rows.length === 0 && <EmptyState text={l("empty")} />}
    </div>
    {editing && <StoreDialog key={editing === "new" ? "new" : editing.id} store={editing} credentials={credentials}
      companies={companies} defaultCompanyId={companyId} permissions={permissions} restoreFocus={restoreFocus}
      onClose={() => setEditing(null)} onSaved={() => { setEditing(null); state.reload(); }} onNotice={onNotice} />}
  </section>;
}
