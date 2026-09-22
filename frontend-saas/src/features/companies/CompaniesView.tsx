import { FormEvent, useEffect, useRef, useState } from "react";
import { api } from "../../lib/api";
import { useRemote } from "../../app/RefreshContext";
import type { CompanySummary, CreateCompanyRequest, Credentials, InstallationSummary, TaxpayerType } from "../../lib/types";
import type { Notice } from "../../shared/types";
import { useI18n } from "../../i18n";
import { emptyFiscalAddress, errorMessage, formatDate } from "../../shared/lib";
import { SectionHeader, Input, Select, AddressFields, EmptyState } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";
import { WorkspaceDialog } from "../../shared/WorkspaceDialog";
import { SaasDataTable, type DataColumn } from "../../shared/table/SaasDataTable";
import { CompanyDetail } from "./CompanyDetail";
import { CompanyContactFields, emptyCompanyContact } from "./CompanyContactFields";
import { useCompanyLabels } from "./labels";
import { CompanyFilters } from "./CompanyFilters";
import { emptyCompanyFilters, matchesCompanyFilters } from "./company-filters.mjs";
import "./companies.css";

function newCompany(): CreateCompanyRequest {
  return { name: "", taxId: "", taxpayerType: "SOCIEDAD", companyAddress: emptyFiscalAddress(), ...emptyCompanyContact() };
}
type CompanyRow = CompanySummary & { id: string };
type CompanyColumn = "companyName" | "taxId" | "contactName" | "contactPhone" | "contactEmail" | "owners" | "taxpayerType" | "city" | "province" | "createdAt";

export function CompaniesView({ credentials, installations, permissions, onChanged, onNotice }: {
  credentials: Credentials; installations: InstallationSummary[]; permissions: Set<string>;
  onChanged: () => void; onNotice: (notice: Notice) => void;
}) {
  const { t, language } = useI18n();
  const l = useCompanyLabels();
  const companies = useRemote(() => api.companies(credentials), [credentials.accessToken]);
  const [companyForm, setCompanyForm] = useState(newCompany);
  const [creating, setCreating] = useState(false);
  const [selectedCompany, setSelectedCompany] = useState<CompanySummary | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [detailBusy, setDetailBusy] = useState(false);
  const [dialogNotice, setDialogNotice] = useState<Notice | null>(null);
  const [filters, setFilters] = useState(emptyCompanyFilters);
  const [sort, setSort] = useState({ key: "companyName", direction: "asc" as "asc" | "desc" });
  const opener = useRef<HTMLElement | null>(null);
  const mounted = useRef(false);
  const activeToken = useRef(credentials.accessToken);
  activeToken.current = credentials.accessToken;
  const previous = useRef<{ token: string; rows: CompanySummary[] } | null>(null);
  if (companies.data) previous.current = { token: credentials.accessToken, rows: companies.data };
  const directory = companies.data ?? (previous.current?.token === credentials.accessToken ? previous.current.rows : []);
  const canCreateCompany = permissions.has("ADD_COMPANY");
  const canEditCompany = permissions.has("EDIT_COMPANY_DATA");
  const canRevokeInstallation = permissions.has("REVOKE_INSTALLATION");
  const isBusy = busy !== null || detailBusy;
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => { setCreating(false); setSelectedCompany(null); setBusy(null); setDetailBusy(false); setDialogNotice(null); setFilters(emptyCompanyFilters()); }, [credentials.accessToken]);

  function refresh() { companies.reload(); onChanged(); }
  function closeDialog() { if (!isBusy) { setCreating(false); setSelectedCompany(null); setDialogNotice(null); } }
  function openCreate() {
    opener.current = document.activeElement as HTMLElement;
    setCompanyForm(newCompany()); setDialogNotice(null); setCreating(true);
  }
  function openCompany(company: CompanySummary) {
    opener.current = document.activeElement as HTMLElement;
    setDialogNotice(null); setDetailBusy(false); setSelectedCompany(company);
  }
  async function createCompany(event: FormEvent) {
    event.preventDefault(); if (isBusy || !canCreateCompany) return;
    if (!companyForm.owners.length || companyForm.owners.some(owner => !owner.name.trim() || !owner.taxId.trim())) { setDialogNotice({ type: "error", text: l("ownersRequired") }); return; }
    const token = credentials.accessToken;
    setBusy("create"); setDialogNotice(null);
    try {
      await api.createCompany(credentials, companyForm);
      if (!mounted.current || activeToken.current !== token) return;
      setCreating(false); setCompanyForm(newCompany());
      onNotice({ type: "success", text: l("created") }); refresh();
    } catch (error) {
      if (mounted.current && activeToken.current === token) setDialogNotice({ type: "error", text: errorMessage(error) });
    } finally { if (mounted.current && activeToken.current === token) setBusy(null); }
  }
  async function revokeInstallation(installation: InstallationSummary) {
    if (!canRevokeInstallation || !installation.active || isBusy || installation.companyId !== selectedCompany?.companyId) return;
    const reason = window.prompt(t("revocationReasonPrompt"))?.trim();
    if (reason == null) return;
    if (reason.length < 5) { setDialogNotice({ type: "error", text: t("revocationReasonRequired") }); return; }
    const token = credentials.accessToken;
    setBusy("revoke:" + installation.installationId);
    try {
      await api.revokeInstallation(credentials, installation.installationId, reason);
      if (mounted.current && activeToken.current === token) { setDialogNotice({ type: "success", text: t("installationRevoked") }); refresh(); }
    } catch (error) {
      if (mounted.current && activeToken.current === token) setDialogNotice({ type: "error", text: errorMessage(error) });
    } finally { if (mounted.current && activeToken.current === token) setBusy(null); }
  }

  function sortValue(company: CompanySummary, key: string) {
    if (key === "city") return company.companyAddress?.ciudad ?? "";
    if (key === "province") return company.companyAddress?.provincia ?? "";
    if (key === "owners") return company.owners?.[0]?.name ?? "";
    return company[key as Exclude<CompanyColumn, "city" | "province" | "owners">] ?? "";
  }
  const rows: CompanyRow[] = directory.filter(company => matchesCompanyFilters(company, filters))
    .map(company => ({ ...company, id: company.companyId }))
    .sort((a, b) => (sortValue(a, sort.key).localeCompare(sortValue(b, sort.key), language, { numeric: true, sensitivity: "base" })
      || a.companyId.localeCompare(b.companyId)) * (sort.direction === "asc" ? 1 : -1));
  const columns: DataColumn<CompanyRow, CompanyColumn>[] = [
    { key: "companyName", label: t("company"), defaultWidth: 280, sortKey: "companyName", render: row => <strong>{row.companyName}</strong> },
    { key: "taxId", label: t("taxId"), defaultWidth: 145, sortKey: "taxId", render: row => row.taxId },
    { key: "contactName", label: t("contactName"), defaultWidth: 185, sortKey: "contactName", render: row => row.contactName || "—" },
    { key: "contactPhone", label: l("phone"), defaultWidth: 145, sortKey: "contactPhone", render: row => row.contactPhone || "—" },
    { key: "contactEmail", label: t("contactEmail"), defaultWidth: 220, sortKey: "contactEmail", render: row => row.contactEmail || "—" },
    { key: "owners", label: l("firstOwner"), defaultWidth: 230, sortKey: "owners", render: row => row.owners?.[0]?.name || "—" },
    { key: "taxpayerType", label: t("type"), defaultWidth: 130, sortKey: "taxpayerType", render: row => row.taxpayerType === "SOCIEDAD" ? l("society") : l("selfEmployed") },
    { key: "city", label: t("city"), defaultWidth: 180, sortKey: "city", render: row => row.companyAddress?.ciudad ?? "—" },
    { key: "province", label: t("province"), defaultWidth: 155, sortKey: "province", render: row => row.companyAddress?.provincia ?? "—" },
    { key: "createdAt", label: l("createdAt"), defaultWidth: 170, sortKey: "createdAt", render: row => formatDate(row.createdAt) },
  ];
  const notice = dialogNotice && <div className={`notice ${dialogNotice.type}`} role={dialogNotice.type === "error" ? "alert" : "status"}>{dialogNotice.text}</div>;
  return <div className="view-grid table-workspace company-workspace">
    <section className="content-section company-list-section">
      <div className="company-list-heading">
        <SectionHeader title={t("companies")} subtitle={l("tableHint")} />
        {canCreateCompany && <button className="primary-button" type="button" onClick={openCreate}>{l("newCompany")}</button>}
      </div>
      <CompanyFilters filters={filters} onChange={setFilters} companies={directory} count={rows.length} />
      <LoadState {...companies} />
      <div className="company-table-region" aria-busy={companies.loading}>
        <SaasDataTable username={credentials.username} tableKey="companies" label={t("companies")} columns={columns} rows={rows}
          sort={sort} onSort={key => setSort(current => ({ key, direction: current.key === key && current.direction === "asc" ? "desc" : "asc" }))}
          onOpen={openCompany} disabled={isBusy || companies.loading || Boolean(companies.error)} />
        {!companies.loading && !companies.error && rows.length === 0 && <EmptyState text={l("empty")} />}
      </div>
    </section>
    {creating && <WorkspaceDialog key="create" className="saas-company-dialog" title={l("newCompany")} subtitle={l("companyOnly")}
      closeLabel={t("close")} busy={isBusy} onClose={closeDialog} restoreFocus={opener.current} focusFirstInput>
      {notice}
      <form className="company-profile-form" onSubmit={createCompany} aria-label={t("createCompany")}>
        <fieldset className="company-fieldset"><legend>{l("companyOnly")}</legend><div className="company-identity-grid">
        <Input label={t("company")} value={companyForm.name} onChange={name => setCompanyForm({ ...companyForm, name })} required maxLength={200} disabled={isBusy} />
        <Input label={t("taxId")} value={companyForm.taxId} onChange={taxId => setCompanyForm({ ...companyForm, taxId })} required disabled={isBusy} />
        <Select label={t("type")} value={companyForm.taxpayerType} options={["SOCIEDAD", "AUTONOMO"]} onChange={taxpayerType => setCompanyForm({ ...companyForm, taxpayerType: taxpayerType as TaxpayerType })} disabled={isBusy} />
        </div>
        <AddressFields title={t("companyAddress")} value={companyForm.companyAddress} onChange={companyAddress => setCompanyForm({ ...companyForm, companyAddress })} disabled={isBusy} countryOptions="europe" />
        </fieldset>
        <CompanyContactFields value={companyForm} onChange={contact => setCompanyForm({ ...companyForm, ...contact })} disabled={isBusy} />
        <div className="company-save-bar"><button className="primary-button" type="submit" disabled={isBusy}>{busy === "create" ? t("creating") : t("createCompany")}</button></div>
      </form>
    </WorkspaceDialog>}
    {selectedCompany && <WorkspaceDialog key={selectedCompany.companyId} className="saas-company-dialog" title={`${t("companyDetail")}: ${selectedCompany.companyName}`}
      subtitle={l("companyOnly")} closeLabel={t("close")} busy={isBusy} onClose={closeDialog} restoreFocus={opener.current}>
      {notice}
      <CompanyDetail key={selectedCompany.companyId} credentials={credentials} company={selectedCompany}
        installations={installations} canEditCompany={canEditCompany} canRevokeInstallation={canRevokeInstallation}
        installationBusy={busy} onRevokeInstallation={installation => void revokeInstallation(installation)}
        onChanged={refresh} onNotice={setDialogNotice} onBusyChange={setDetailBusy} onCompanySaved={setSelectedCompany} />
    </WorkspaceDialog>}
  </div>;
}
