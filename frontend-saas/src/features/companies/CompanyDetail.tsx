import { FormEvent, useEffect, useRef, useState } from "react";
import { api } from "../../lib/api";
import { useRemote } from "../../app/RefreshContext";
import type { CompanyProfileRequest, CompanySummary, Credentials, InstallationSummary } from "../../lib/types";
import type { Notice } from "../../shared/types";
import { useI18n } from "../../i18n";
import { emptyFiscalAddress, errorMessage } from "../../shared/lib";
import { Input, Select, AddressFields, SectionHeader } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";
import { InstallationsTable } from "../../shared/license-tables";
import { CompanyContactFields, emptyOwner } from "./CompanyContactFields";
import { useCompanyLabels } from "./labels";

function profileDraft(company: CompanySummary): CompanyProfileRequest {
  return {
    name: company.companyName, companyAddress: company.companyAddress ?? emptyFiscalAddress(),
    contactName: company.contactName ?? "", contactPhone: company.contactPhone ?? "", contactEmail: company.contactEmail ?? "",
    supportStatus: company.supportStatus ?? "NORMAL", notes: company.notes ?? "",
    owners: company.owners?.length ? company.owners.map(owner => ({ ...owner })) : [emptyOwner()],
  };
}

export function CompanyDetail({ credentials, company, installations, canEditCompany, canRevokeInstallation,
  installationBusy, onRevokeInstallation, onChanged, onNotice, onBusyChange, onCompanySaved }: {
  credentials: Credentials; company: CompanySummary; installations: InstallationSummary[];
  canEditCompany: boolean; canRevokeInstallation: boolean; installationBusy: string | null;
  onRevokeInstallation: (installation: InstallationSummary) => void;
  onChanged: () => void; onNotice: (notice: Notice) => void;
  onBusyChange?: (busy: boolean) => void; onCompanySaved?: (company: CompanySummary) => void;
}) {
  const { t } = useI18n(); const l = useCompanyLabels();
  const profile = useRemote(() => api.companyProfile(credentials, company.companyId), [credentials.accessToken, company.companyId]);
  const [form, setForm] = useState<CompanyProfileRequest | null>(null);
  const [contactRevision, setContactRevision] = useState(0);
  const [busy, setBusy] = useState(false);
  const dirty = useRef(false);
  const mounted = useRef(false);
  const mutation = useRef(0);
  const activeContext = useRef({ companyId: company.companyId, token: credentials.accessToken });
  const onBusyChangeRef = useRef(onBusyChange);
  activeContext.current = { companyId: company.companyId, token: credentials.accessToken };
  onBusyChangeRef.current = onBusyChange;
  const isBusy = busy || installationBusy !== null;
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; mutation.current++; onBusyChangeRef.current?.(false); };
  }, []);
  useEffect(() => { mutation.current++; setBusy(false); dirty.current = false; setForm(null); }, [company.companyId, credentials.accessToken]);
  useEffect(() => { onBusyChange?.(isBusy); }, [isBusy, onBusyChange]);
  useEffect(() => {
    if (!dirty.current && profile.data?.companyId === company.companyId) {
      setForm(profileDraft(profile.data)); setContactRevision(current => current + 1);
    }
  }, [profile.data, company.companyId]);
  function change(next: CompanyProfileRequest) { dirty.current = true; setForm(next); }
  function isCurrentMutation(id: number, companyId: string, token: string) {
    return mounted.current && id === mutation.current
      && companyId === activeContext.current.companyId && token === activeContext.current.token;
  }
  async function saveProfile(event: FormEvent) {
    event.preventDefault();
    if (!canEditCompany || isBusy || !form || profile.loading || profile.error || profile.data?.companyId !== company.companyId) return;
    if (!form.owners.length || form.owners.some(owner => !owner.name.trim() || !owner.taxId?.trim())) { onNotice({ type: "error", text: l("ownersRequired") }); return; }
    const id = ++mutation.current; const companyId = company.companyId; const token = credentials.accessToken;
    setBusy(true);
    try {
      const saved = await api.saveCompanyProfile(credentials, companyId, form);
      if (!isCurrentMutation(id, companyId, token) || saved.companyId !== companyId) return;
      dirty.current = false; setForm(profileDraft(saved)); setContactRevision(current => current + 1); onCompanySaved?.(saved);
      onNotice({ type: "success", text: l("saved") }); onChanged();
    } catch (error) { if (isCurrentMutation(id, companyId, token)) onNotice({ type: "error", text: errorMessage(error) }); }
    finally { if (isCurrentMutation(id, companyId, token)) setBusy(false); }
  }
  const disabled = !canEditCompany || isBusy || profile.loading || Boolean(profile.error);
  const companyInstallations = installations.filter(installation => installation.companyId === company.companyId);
  return <section className="company-detail">
    <LoadState {...profile} />
    {form && <form className="company-profile-form" onSubmit={saveProfile} aria-label={t("companyDetail")}>
      <fieldset className="company-fieldset"><legend>{l("companyOnly")}</legend>
        <div className="company-identity-grid">
          <Input label={t("company")} value={form.name} onChange={name => change({ ...form, name })} maxLength={200} required disabled={disabled} />
          <Input label={t("taxId")} value={company.taxId} onChange={() => undefined} disabled />
          <Select label={t("type")} value={company.taxpayerType} options={["SOCIEDAD", "AUTONOMO"]} onChange={() => undefined} disabled />
        </div>
        <AddressFields title={t("companyAddress")} value={form.companyAddress} onChange={companyAddress => change({ ...form, companyAddress })} disabled={disabled} countryOptions="europe" />
        <p className="company-field-hint">{l("identityLocked")}</p>
      </fieldset>
      <CompanyContactFields key={contactRevision} value={form} onChange={contact => change({ ...form, ...contact })} disabled={disabled} readOnly={!canEditCompany} />
      {canEditCompany && <div className="company-save-bar"><button className="primary-button" type="submit" disabled={disabled}>{busy ? t("saving") : l("saveProfile")}</button></div>}
    </form>}
    <section className="company-installations"><SectionHeader title={t("linkedInstallations")} subtitle={`${companyInstallations.length} ${t("installations").toLowerCase()}`} />
      <InstallationsTable installations={companyInstallations} canRevoke={canRevokeInstallation} busy={busy ? "profile" : installationBusy} onRevoke={onRevokeInstallation} />
    </section>
  </section>;
}
