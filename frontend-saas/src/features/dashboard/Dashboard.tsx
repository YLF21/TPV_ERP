


import type { DashboardData } from "../../lib/types";
import { View } from "../../shared/types";
import { useI18n } from "../../i18n/index";
import { operationalAlerts } from "../../shared/lib";
import { LicenseTable } from "../../shared/license-tables";
import { EventLine } from "../sync/SyncView";
import { EmptyState } from "../../shared/ui";
import { AuditList } from "../audit/AuditView";

export function Dashboard({ data, onNavigate }: { data: DashboardData; onNavigate: (view: View) => void }) {
  const { t } = useI18n();
  const activeLicenses = data.licenses.filter((license) => license.status === "VALIDA").length;
  const blockedLicenses = data.licenses.filter((license) => license.status === "BLOQUEADA_MANUAL").length;
  const activeUsers = data.users.filter((user) => user.active).length;
  const lastEvent = data.events[0];
  const alerts = operationalAlerts(data, t);
  const report = data.advancedReport;

  return (
    <div className="view-grid saas-dashboard">
      <section className="saas-dashboard-summary-strip" aria-label={t("dashboard")}>
        <article className="saas-dashboard-panel license-summary">
          <header><strong>{t("licensesCompanies")}</strong></header>
          <div className="saas-dashboard-panel-body saas-license-summary-body">
            <div className="saas-main-metric">
              <span>{t("validLicenses")}</span>
              <strong>{activeLicenses}</strong>
              <small>{`${data.licenses.length} ${t("total")}`}</small>
            </div>
            <dl>
              <div><dt>{t("blocked")}</dt><dd>{blockedLicenses}</dd></div>
              <div><dt>{t("installations")}</dt><dd>{data.installations.length}</dd></div>
            </dl>
            <footer><button type="button" onClick={() => onNavigate("licenses")}>{t("licensesCompanies")}</button></footer>
          </div>
        </article>

        <article className="saas-dashboard-panel sync-summary">
          <header><strong>{t("sync")}</strong></header>
          <div className="saas-dashboard-panel-body saas-summary-content">
            <div className="saas-promotion-count"><strong>{data.events.length}</strong><span>{t("events")}</span></div>
            <div className="saas-summary-facts">
              <span>{t("syncedSales")}</span>
              <strong>{data.salesSummary.documentCount}</strong>
            </div>
            <footer><button type="button" onClick={() => onNavigate("sync")}>{t("sync")}</button></footer>
          </div>
        </article>

        <article className="saas-dashboard-panel alert-summary">
          <header><strong>{t("alerts")}</strong></header>
          <div className="saas-dashboard-panel-body saas-summary-content">
            <div className="saas-control-alert-counts">
              <div><strong>{alerts.length}</strong><span>{t("alerts")}</span></div>
              <div><strong>{blockedLicenses}</strong><span>{t("blocked")}</span></div>
            </div>
            <div className="saas-summary-facts">
              <span>{t("activeUsers")}</span>
              <strong>{activeUsers}</strong>
            </div>
            <footer><button type="button" onClick={() => onNavigate("health")}>{t("viewDetail")}</button></footer>
          </div>
        </article>
      </section>

      <div className="saas-dashboard-main-grid">
        <section className="saas-dashboard-panel recent-licenses-panel">
          <header><strong>{t("recentLicenses")}</strong></header>
          <div className="saas-dashboard-panel-body">
            <LicenseTable licenses={data.licenses.slice(0, 8)} compact />
          </div>
        </section>

        <section className="saas-dashboard-activity" aria-labelledby="saas-dashboard-activity-title">
          <h2 id="saas-dashboard-activity-title">{t("recentActivity")}</h2>
          <div className="saas-dashboard-activity-body">
            <article className="saas-dashboard-panel">
              <header><strong>{t("lastEvent")}</strong></header>
              <div className="saas-dashboard-panel-body activity-panel-body">
                {lastEvent ? <EventLine event={lastEvent} /> : <EmptyState text={t("noSyncedEventsYet")} />}
                <footer><button type="button" onClick={() => onNavigate("sync")}>{t("sync")}</button></footer>
              </div>
            </article>
            <article className="saas-dashboard-panel">
              <header><strong>{t("audit")}</strong></header>
              <div className="saas-dashboard-panel-body activity-panel-body">
                <AuditList audit={data.audit.slice(0, 3)} />
                <footer><button type="button" onClick={() => onNavigate("audit")}>{t("audit")}</button></footer>
              </div>
            </article>
          </div>
        </section>
      </div>
    </div>
  );
}
