import { useRefreshVersion } from "../../app/RefreshContext";
import { useEffect, useMemo, useRef, useState } from "react";
import { api } from "../../lib/api";

import type { Credentials, LicenseSummary, TaxpayerType, VerifactuActivationPolicy, FiscalStatusAdmin, FiscalCompanyStatusAdmin } from "../../lib/types";
import { Notice } from "../../shared/types";
import { useI18n } from "../../i18n/index";
import { errorMessage, taxpayerLabel, formatDate, uniqueCompanies, fiscalModeLabel, fiscalStateLabel } from "../../shared/lib";
import { SectionHeader, EmptyState, StatusPill, Input } from "../../shared/ui";

export function VerifactuPolicySection({
  credentials,
  canManage,
  onChanged,
  onNotice
}: {
  credentials: Credentials;
  canManage: boolean;
  onChanged: () => void;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const refreshVersion = useRefreshVersion();
  const [policies, setPolicies] = useState<VerifactuActivationPolicy[]>([]);
  const [dates, setDates] = useState<Partial<Record<TaxpayerType, string>>>({});
  const [reasons, setReasons] = useState<Partial<Record<TaxpayerType, string>>>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<TaxpayerType | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api.verifactuActivationPolicies(credentials)
      .then((values) => {
        if (cancelled) return;
        setPolicies(values);
        setDates(Object.fromEntries(values.map((policy) => [policy.taxpayerType, policy.activationDate])));
      })
      .catch((error) => {
        if (!cancelled) onNotice({ type: "error", text: errorMessage(error) });
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [credentials, onNotice, refreshVersion]);

  async function updatePolicy(policy: VerifactuActivationPolicy) {
    const activationDate = dates[policy.taxpayerType] ?? "";
    const reason = reasons[policy.taxpayerType]?.trim() ?? "";
    if (!activationDate || reason.length < 3) {
      onNotice({ type: "error", text: t("policyReasonRequired") });
      return;
    }
    if (!window.confirm(`${t("policyConfirm")}\n\n${taxpayerLabel(policy.taxpayerType, t)}: ${activationDate}`)) return;

    setBusy(policy.taxpayerType);
    try {
      const updated = await api.updateVerifactuActivationPolicy(credentials, policy.taxpayerType, { activationDate, reason });
      setPolicies((current) => current.map((item) => item.taxpayerType === updated.taxpayerType ? updated : item));
      setDates((current) => ({ ...current, [updated.taxpayerType]: updated.activationDate }));
      setReasons((current) => ({ ...current, [updated.taxpayerType]: "" }));
      onNotice({ type: "success", text: t("policyUpdated") });
      onChanged();
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="content-section verifactu-policy-section">
      <SectionHeader title={t("verifactuPolicy")} subtitle={t("verifactuPolicySubtitle")} />
      {!canManage && <div className="permission-hint">{t("policyReadOnly")}</div>}
      {loading ? (
        <EmptyState text={t("verifactuPolicyLoading")} />
      ) : policies.length === 0 ? (
        <EmptyState text={t("verifactuPolicyEmpty")} />
      ) : (
        <div className="verifactu-policy-grid">
          {policies.map((policy) => (
            <article className="verifactu-policy-card" key={policy.taxpayerType}>
              <header>
                <div>
                  <span>{t("type")}</span>
                  <h3>{taxpayerLabel(policy.taxpayerType, t)}</h3>
                </div>
                <StatusPill status={`${t("policyVersion")} ${policy.version}`} tone="muted" />
              </header>

              <div className="verifactu-policy-impact">
                <div><span>{t("affectedLicenses")}</span><strong>{policy.activeLicenses}</strong></div>
                <div><span>{t("affectedInstallations")}</span><strong>{policy.linkedInstallations}</strong></div>
              </div>

              <div className="verifactu-policy-form">
                <Input
                  label={t("activationDate")}
                  type="date"
                  value={dates[policy.taxpayerType] ?? policy.activationDate}
                  onChange={(value) => setDates((current) => ({ ...current, [policy.taxpayerType]: value }))}
                  required
                  disabled={!canManage || busy === policy.taxpayerType}
                />
                <label>
                  {t("changeReason")}
                  <input
                    className="control-input"
                    value={reasons[policy.taxpayerType] ?? ""}
                    maxLength={500}
                    placeholder={t("changeReasonPlaceholder")}
                    onChange={(event) => setReasons((current) => ({ ...current, [policy.taxpayerType]: event.target.value }))}
                    disabled={!canManage || busy === policy.taxpayerType}
                  />
                </label>
              </div>

              <dl className="verifactu-policy-meta">
                <div><dt>{t("updatedBy")}</dt><dd>{policy.updatedBy} · {formatDate(policy.updatedAt)}</dd></div>
                <div><dt>{t("currentReason")}</dt><dd>{policy.reason}</dd></div>
              </dl>

              {canManage && (
                <button
                  className="primary-button"
                  type="button"
                  disabled={busy !== null}
                  onClick={() => void updatePolicy(policy)}
                >
                  {busy === policy.taxpayerType ? t("saving") : t("updatePolicy")}
                </button>
              )}
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

export function FiscalStatusView({ credentials, licenses, onNotice }: {
  credentials: Credentials;
  licenses: LicenseSummary[];
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const refreshVersion = useRefreshVersion();
  const [rows, setRows] = useState<FiscalStatusAdmin[]>([]);
  const [companyRows, setCompanyRows] = useState<FiscalCompanyStatusAdmin[]>([]);
  const [companyId, setCompanyId] = useState("");
  const [loading, setLoading] = useState(false);
  const fiscalRequestId = useRef(0);
  const companies = useMemo(() => uniqueCompanies(licenses), [licenses]);
  const visibleRows = rows.filter((row) => !companyId || row.companyId === companyId);

  useEffect(() => { void load(); }, [credentials.accessToken, companyId, refreshVersion]);

  async function load() {
    const requestId = ++fiscalRequestId.current;
    const requestedCompanyId = companyId;
    setLoading(true);
    try {
      const [nextRows, nextCompanyRows] = await Promise.all([
        api.fiscalStatus(credentials, companyId || undefined),
        companyId ? Promise.resolve([] as FiscalCompanyStatusAdmin[]) : api.fiscalCompanyStatus(credentials)
      ]);
      if (requestId !== fiscalRequestId.current || requestedCompanyId !== companyId) return;
      setRows(nextRows);
      setCompanyRows(nextCompanyRows);
      onNotice(null);
    } catch (error) {
      if (requestId === fiscalRequestId.current && requestedCompanyId === companyId) onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      if (requestId === fiscalRequestId.current) setLoading(false);
    }
  }

  return (
    <section className="content-section">
      <SectionHeader title={t("fiscal")} subtitle={loading ? t("refreshing") : t("fiscalStatusSubtitle")} />
      <div className="permission-hint">{t("fiscalReadOnly")}</div>
      <div className="toolbar">
        <select className="control-input" value={companyId} onChange={(event) => setCompanyId(event.target.value)}>
          <option value="">{t("allCompanies")}</option>
          {companies.map((company) => <option key={company.companyId} value={company.companyId}>{company.companyName}</option>)}
        </select>
      </div>
      {!companyId && companyRows.length > 0 && <div className="table-wrap">
        <table>
          <thead><tr><th>{t("fiscalCompany")}</th><th>{t("fiscalMode")}</th><th>{t("fiscalActivationState")}</th><th>{t("installations")}</th><th>{t("fiscalLastReport")}</th></tr></thead>
          <tbody>{companyRows.map((row) => <tr key={row.companyId}>
            <td><strong>{row.companyName}</strong><small>{row.taxId}</small></td>
            <td>{fiscalModeLabel(row.effectiveMode, t)}</td>
            <td>{fiscalStateLabel(row.activationState, t)}</td>
            <td>
              {row.installations} / {row.stores}
              {row.unlinkedStores > 0 ? ` (${row.unlinkedStores} ${t("notLinked")})` : ""}
              {row.staleInstallations > 0 ? ` · ${row.staleInstallations} ${t("staleInstallations")}` : ""}
            </td>
            <td>{row.lastReportedAt ? formatDate(row.lastReportedAt) : "-"}</td>
          </tr>)}</tbody>
        </table>
      </div>}
      {visibleRows.length === 0 ? <EmptyState text={t("fiscalNoData")} /> : (
        <div className="table-wrap">
          <table>
            <thead><tr>
              <th>{t("fiscalCompany")}</th><th>{t("fiscalStore")}</th><th>{t("fiscalInstallation")}</th>
              <th>{t("fiscalMode")}</th><th>{t("fiscalActivationState")}</th><th>{t("fiscalActivationDate")}</th>
              <th>{t("fiscalEnvironment")}</th><th>{t("fiscalTransport")}</th><th>{t("fiscalLastReport")}</th>
            </tr></thead>
            <tbody>{visibleRows.map((row) => (
              <tr key={`${row.storeId}:${row.installationId ?? "unlinked"}`}>
                <td><strong>{row.companyName}</strong><small>{row.taxId}</small></td>
                <td>{row.storeName}</td>
                <td><strong>{row.installationReference || t("notLinked")}</strong><small>{row.installationId || "—"}</small></td>
                <td><StatusPill status={fiscalModeLabel(row.effectiveMode, t)} tone={row.effectiveMode === "VERIFACTU" ? "ok" : row.effectiveMode === "NO_VERIFACTU" ? "warning" : "muted"} /></td>
                <td><StatusPill status={row.stale ? t("fiscalStale") : fiscalStateLabel(row.activationState, t)} tone={row.stale || row.activationState === "DUE_REVIEW" ? "warning" : row.activationState === "ACTIVE" ? "ok" : "muted"} /></td>
                <td>{row.activationDate ? formatDate(row.activationDate) : "-"}</td>
                <td>{row.runtimeClass && row.endpointEnvironment ? `${row.runtimeClass} / ${row.endpointEnvironment}` : "-"}</td>
                <td>{row.transportMode || "-"}</td>
                <td>{row.reportedAt ? formatDate(row.reportedAt) : "-"}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </section>
  );
}
