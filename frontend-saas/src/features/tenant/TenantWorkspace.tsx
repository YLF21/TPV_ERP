import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { loadTenantPortal, tenantApi, type TenantAccessResponse } from "../../lib/tenant-api";
import type { Credentials, TenantPortalData } from "../../lib/types";
import type { Notice } from "../../shared/types";
import { errorMessage } from "../../shared/lib";
import { EmptyState, LanguageSelector, RetryError } from "../../shared/ui";
import { AccountPassword } from "../../shared/AccountPassword";
import { resolveCompanySelection } from "./access-selection.mjs";
import { useTenantLabels } from "./labels";
import { TenantPortal } from "./TenantPortal";
import { TenantSupervision } from "./TenantSupervision";
import { TenantSupportConversation } from "./TenantSupportConversation";
import "./tenant-workspace.css";

export function TenantWorkspace({ credentials, onLogout, onNotice }: {
  credentials: Credentials; onLogout: () => void; onNotice: (notice: Notice) => void;
}) {
  const l = useTenantLabels();
  const [access, setAccess] = useState<{ token: string; revision: number; data: TenantAccessResponse } | null>(null);
  const [selected, setSelected] = useState("");
  const [revision, setRevision] = useState(0);
  const [accessLoading, setAccessLoading] = useState(true);
  const [accessError, setAccessError] = useState<string | null>(null);
  const [portal, setPortal] = useState<{ context: string; data: TenantPortalData } | null>(null);
  const [portalLoading, setPortalLoading] = useState(false);
  const [portalError, setPortalError] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const token = credentials.accessToken;
  const companies = access?.token === token ? access.data.companies : [];
  const companyId = resolveCompanySelection(companies, selected);
  const company = companies.find(item => item.companyId === companyId);
  const scopedCredentials = useMemo(() => ({ ...credentials, companyId }), [credentials, companyId]);
  const context = company ? JSON.stringify([token, companyId, company.roleName, company.companyPrivileges, company.stores]) : "";
  const currentContext = useRef(context);
  currentContext.current = context;
  const mounted = useRef(false);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  useEffect(() => {
    let cancelled = false;
    setAccessLoading(true); setAccessError(null);
    tenantApi.access(credentials).then(data => {
      if (!cancelled) {
        setAccess({ token, revision, data });
        setSelected(previous => resolveCompanySelection(data.companies, previous));
      }
    }).catch(error => { if (!cancelled) { setAccess(null); setAccessError(errorMessage(error)); } })
      .finally(() => { if (!cancelled) setAccessLoading(false); });
    return () => { cancelled = true; };
  }, [credentials, token, revision]);

  useEffect(() => {
    let cancelled = false;
    setNotice(null); setPortalError(null);
    if (!company) { setPortal(null); setPortalLoading(false); return; }
    setPortalLoading(true);
    loadTenantPortal(scopedCredentials, company).then(data => {
      if (!cancelled) setPortal({ context, data });
    }).catch(error => { if (!cancelled) { setPortal(null); setPortalError(errorMessage(error)); } })
      .finally(() => { if (!cancelled) setPortalLoading(false); });
    return () => { cancelled = true; };
  }, [scopedCredentials, company, context]);

  const refresh = useCallback(() => {
    if (mounted.current && currentContext.current === context) setRevision(value => value + 1);
  }, [context]);
  const showNotice = useCallback((next: Notice) => {
    if (mounted.current && currentContext.current === context) { setNotice(next); onNotice(next); }
  }, [context, onNotice]);
  const data = portal?.context === context ? portal.data : null;
  const selector = <div className="tenant-context-controls">
    <label>{l("company")}<select aria-label={l("company")} className="control-input" value={companyId} onChange={event => { setSelected(event.target.value); setNotice(null); }}>
      <option value="" disabled={companies.length === 1}>{l("chooseCompany")}</option>
      {companies.map(item => <option key={item.companyId} value={item.companyId}>{item.companyName}</option>)}
    </select></label>
    <AccountPassword credentials={credentials} onLogout={onLogout} />
  </div>;

  if (!company) return <div className="app-shell tenant-shell">
    <header className="app-header"><div className="brand"><strong>ERP SaaS</strong></div>
      <div className="app-actions"><LanguageSelector /><button type="button" className="secondary-button" onClick={onLogout}>{l("logout")}</button></div>
    </header>
    <main className="main-panel tenant-main"><section className="content-section">
      {selector}
      {accessError ? <RetryError message={accessError} onRetry={refresh} /> : <EmptyState text={accessLoading ? l("loading") : companies.length ? l("chooseCompany") : l("noAccess")} />}
      <button type="button" className="secondary-button" disabled={accessLoading} onClick={refresh}>{l("refresh")}</button>
    </section></main>
  </div>;

  return <TenantPortal key={context} credentials={scopedCredentials} data={data} loading={accessLoading || portalLoading}
    notice={notice ?? (portalError ? { type: "error", text: portalError } : data?.loadErrors.length ? { type: "error", text: l("partialError") } : null)}
    onRefresh={refresh} onLogout={onLogout} onNotice={showNotice} contextSelector={selector}
    companyPrivileges={company.companyPrivileges}
    supervision={<TenantSupervision credentials={scopedCredentials} stores={company.stores} revision={access?.revision ?? 0} />}
    supportConversation={<TenantSupportConversation credentials={scopedCredentials} tickets={data?.tickets ?? []} onNotice={showNotice} revision={access?.revision ?? 0} />} />;
}
