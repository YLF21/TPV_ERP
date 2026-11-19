import { useEffect, useState, type ReactNode } from "react";
import { useI18n } from "../../i18n";
import type { Credentials } from "../../lib/types";
import { StatisticsView } from "./StatisticsView";
import { InventoryReportsView } from "./InventoryReportsView";
import { ArticleReportsView } from "./ArticleReportsView";
import { MovementReportsView } from "./MovementReportsView";
import { DocumentReportsView } from "./DocumentReportsView";
import { useStatisticsLabels } from "./statistics-labels";
import { EmptyState } from "../../shared/ui";
import { analyticsHref, readAnalyticsFilters, useAnalyticsNavigationPosition } from "./analytics-context";

const sections = ["sales", "articles", "movements", "stock", "averages", "provinces", "sync"] as const;
type Section = typeof sections[number];

function selectedSection(hash: string): Section | "documents" {
  const value = hash.split("/")[2]?.split("?")[0];
  if (value === "documents") return value;
  return sections.find((section) => section === value) ?? (value === "inventory" ? "stock" : "sales");
}

export function ReportsWorkspace({ credentials, permissions }: {
  credentials: Credentials;
  permissions: ReadonlySet<string>;
}) {
  const { t } = useI18n();
  const l = useStatisticsLabels();
  const [location, setLocation] = useState(() => window.location.hash);
  const section = selectedSection(location);

  useEffect(() => {
    const sync = () => setLocation(window.location.hash);
    window.addEventListener("popstate", sync);
    window.addEventListener("hashchange", sync);
    return () => {
      window.removeEventListener("popstate", sync);
      window.removeEventListener("hashchange", sync);
    };
  }, []);

  function navigate(next: Section) {
    if (next === section) return;
    const destination = next === "stock" ? "inventory" : next;
    const hash = analyticsHref(destination, readAnalyticsFilters(credentials, destination));
    if (window.location.hash !== hash) window.history.pushState({ view: "reports" }, "", hash);
    setLocation(hash);
  }

  if (!permissions.has("VIEW_REPORTS")) return <EmptyState text={t("noPermissionAction")} />;

  return (
    <div className="saas-summary-view reports-workspace view-grid">
      <div className="segmented reports-sections" role="group" aria-label={t("reports")}>
        {sections.map((value) => (
          <button key={value} type="button" className={section === value ? "active" : ""}
            aria-pressed={section === value} onClick={() => navigate(value)}>{l(value)}</button>
        ))}
      </div>
      <ReportViewport key={location} credentials={credentials}>{section === "stock" ? <InventoryReportsView credentials={credentials} permissions={permissions} />
        : section === "articles" ? <ArticleReportsView key={location} credentials={credentials} />
        : section === "movements" ? <MovementReportsView key={location} credentials={credentials} />
        : section === "documents" ? <DocumentReportsView key={location} credentials={credentials} />
        : <StatisticsView key={location} mode={section} credentials={credentials} permissions={permissions} />}</ReportViewport>
    </div>
  );
}

function ReportViewport({ credentials, children }: { credentials: Credentials; children: ReactNode }) {
  useAnalyticsNavigationPosition(credentials);
  return <div className="reports-content">{children}</div>;
}
