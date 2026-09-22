import { useRefreshVersion } from "../../app/RefreshContext";
import { useEffect, useMemo, useRef, useState } from "react";
import { api } from "../../lib/api";

import type { CustomerHealth, Credentials, InstallationSummary, LicenseSummary } from "../../lib/types";
import { Notice } from "../../shared/types";
import { useI18n } from "../../i18n/index";
import { errorMessage, riskLabel, formatDate, hoursSince } from "../../shared/lib";
import { Metric, SectionHeader, EmptyState, StatusPill } from "../../shared/ui";

export function CustomerHealthView({
  credentials,
  licenses,
  onNotice
}: {
  credentials: Credentials;
  licenses: LicenseSummary[];
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const refreshVersion = useRefreshVersion();
  const visibleCompanyIds = useMemo(() => new Set(licenses.map((license) => license.companyId)), [licenses]);
  const requestId = useRef(0);
  const [health, setHealth] = useState<CustomerHealth[]>([]);
  const [selectedCompanyId, setSelectedCompanyId] = useState("");
  const visibleHealth = health.filter((item) => visibleCompanyIds.has(item.companyId));
  const selected = visibleHealth.find((item) => item.companyId === selectedCompanyId) ?? visibleHealth[0] ?? null;
  const riskCount = visibleHealth.filter((item) => item.riskLevel === "DANGER").length;
  const warningCount = visibleHealth.filter((item) => item.riskLevel === "WARNING").length;
  const inactiveCount = visibleHealth.filter((item) => item.eventsLast7Days === 0).length;

  useEffect(() => {
    void loadHealth();
  }, [credentials.accessToken, refreshVersion]);

  useEffect(() => {
    if (visibleHealth.length > 0 && !visibleHealth.some((item) => item.companyId === selectedCompanyId)) {
      setSelectedCompanyId(visibleHealth[0].companyId);
    }
  }, [visibleHealth, selectedCompanyId]);

  async function loadHealth() {
    const id = ++requestId.current;
    try {
      const rows = await api.customerHealth(credentials);
      if (id !== requestId.current) return;
      setHealth(rows);
      onNotice(null);
    } catch (error) {
      if (id !== requestId.current) return;
      setHealth([]);
      onNotice({ type: "error", text: errorMessage(error) });
    }
  }

  return (
    <div className="view-grid">
      <section className="metric-grid">
        <Metric label={t("customersInRisk")} value={riskCount} tone={riskCount > 0 ? "warning" : undefined} />
        <Metric label={t("riskWarning")} value={warningCount} tone={warningCount > 0 ? "warning" : undefined} />
        <Metric label={t("inactiveCustomers")} value={inactiveCount} tone={inactiveCount > 0 ? "warning" : undefined} />
        <Metric label={t("company")} value={visibleHealth.length} />
      </section>

      <section className="content-section health-board">
        <SectionHeader title={t("customerHealth")} subtitle={t("healthSubtitle")} />
        {visibleHealth.length === 0 ? (
          <EmptyState text={t("noHealthData")} />
        ) : (
          <div className="health-layout">
            <div className="health-list">
              {visibleHealth
                .slice()
                .sort((left, right) => left.score - right.score)
                .map((item) => (
                  <button
                    className={`health-card ${item.riskLevel.toLowerCase()} ${selected?.companyId === item.companyId ? "active" : ""}`}
                    type="button"
                    key={item.companyId}
                    onClick={() => setSelectedCompanyId(item.companyId)}
                  >
                    <span>{item.companyName}</span>
                    <strong>{item.score}</strong>
                    <small>{riskLabel(item.riskLevel, t)} - {item.billingStatus}</small>
                  </button>
                ))}
            </div>

            {selected && (
              <article className={`health-detail ${selected.riskLevel.toLowerCase()}`}>
                <div className="health-detail-header">
                  <div>
                    <span>{selected.taxId}</span>
                    <h2>{selected.companyName}</h2>
                  </div>
                  <StatusPill status={riskLabel(selected.riskLevel, t)} tone={selected.riskLevel === "OK" ? "ok" : "warning"} />
                </div>

                <div className="health-score">
                  <strong>{selected.score}</strong>
                  <span>{t("healthScore")}</span>
                </div>

                <div className="health-facts">
                  <Metric label={t("plan")} value={selected.planName} detail={selected.billingStatus} />
                  <Metric label={t("license")} value={selected.licenseStatus} detail={selected.validUntil ? formatDate(selected.validUntil) : t("pending")} />
                  <Metric label={t("eventsLast7Days")} value={selected.eventsLast7Days} detail={selected.lastEventAt ? `${t("lastEventAt")}: ${formatDate(selected.lastEventAt)}` : t("noEvents")} />
                  <Metric label={t("installations")} value={selected.installations} detail={`${t("staleInstallations")}: ${selected.staleInstallations}`} />
                  <Metric label={t("openTickets")} value={selected.openTickets} detail={`${t("urgentTickets")}: ${selected.urgentTickets}`} />
                  <Metric label={t("lastValidationAt")} value={selected.lastValidationAt ? formatDate(selected.lastValidationAt) : t("pending")} />
                </div>

                <div className="health-signals">
                  <strong>{t("healthSignals")}</strong>
                  <div>
                    {selected.signals.map((signal) => (
                      <span key={signal}>{signal}</span>
                    ))}
                  </div>
                </div>
              </article>
            )}
          </div>
        )}
      </section>
    </div>
  );
}

export function InstallationHealth({ installation }: { installation: InstallationSummary }) {
  const { t } = useI18n();
  if (!installation.active) {
    return null;
  }
  if (!installation.lastValidatedAt) {
    return <StatusPill status={t("withoutValidation")} tone="muted" />;
  }
  if (hoursSince(installation.lastValidatedAt) > 48) {
    return <StatusPill status={t("stale")} tone="warning" />;
  }
  return null;
}
