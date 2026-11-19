import { useState } from "react";
import { useRemote } from "../../app/RefreshContext";
import { localeFor, useI18n } from "../../i18n";
import { api } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import { formatDate, licenseStatusPresentation } from "../../shared/lib";
import { EmptyState, Metric, PaginationControls, StatusPill, usePagination } from "../../shared/ui";
import { LoadState } from "../../shared/workspace-ui";
import { analyticsHref, readAnalyticsFilters, useAnalyticsContext, useAnalyticsNavigationPosition } from "../reports/analytics-context";
import { useSummaryCategoryLabel, useSummaryLabels } from "./labels";
import type { AdminSummary, SummaryCount } from "./types";
import { SummaryActivity, SummaryAnalyticsDetails, SummaryFilterPanel, SummaryMetric, SummarySalesChart, restoreSummaryFilters, summaryQuery, type SummaryReportLinks } from "./SummaryAnalytics";
import { formatDocumentAmount } from "./format";
import { SPANISH_PROVINCES } from "../../shared/provinces/spanish-provinces.mjs";

type DashboardProps = {
  credentials: Credentials;
  permissions: ReadonlySet<string>;
};

export function Dashboard(props: DashboardProps) {
  const { t } = useI18n();
  if (!props.permissions.has("VIEW_ADMIN_DATA")) return <EmptyState text={t("noPermissionAction")} />;
  return <DashboardContent key={props.credentials.accessToken} {...props} />;
}

function DashboardContent({ credentials, permissions }: DashboardProps) {
  const [saved] = useState(() => readAnalyticsFilters(credentials, "summary"));
  const [filters, setFilters] = useState(() => restoreSummaryFilters(saved));
  const [detailsOpen, setDetailsOpen] = useState(saved.details === "1");
  const [syncTypesExpanded, setSyncTypesExpanded] = useState(saved.syncTypesExpanded === "1");
  useAnalyticsContext(credentials, "summary", {
    ...filters, details: detailsOpen ? "1" : "0", syncTypesExpanded: syncTypesExpanded ? "1" : "0",
  });
  useAnalyticsNavigationPosition(credentials);
  const query = summaryQuery(filters);
  const state = useRemote(() => api.summary(credentials, query), [credentials.accessToken, query]);
  const { companyId, storeId, province } = filters;
  const links = permissions.has("VIEW_REPORTS") ? {
    sales: analyticsHref("sales", filters),
    averages: analyticsHref("averages", filters),
    sync: analyticsHref("sync", filters),
    inventory: analyticsHref("inventory", { companyId, storeId, province }),
  } : undefined;

  return (
    <div className={state.data ? "saas-summary-view saas-dashboard view-grid" : "saas-summary-view view-grid"}>
      <SummaryFilterPanel credentials={credentials} filters={filters} onApply={next => { if (summaryQuery(next) === query) state.reload(); setFilters(next); }} />
      <LoadState {...state} />
      {state.data && <SummaryContent
        summary={state.data}
        detailsOpen={detailsOpen}
        onToggleDetails={setDetailsOpen}
        syncTypesExpanded={syncTypesExpanded}
        onToggleSyncTypes={() => setSyncTypesExpanded(open => !open)}
        links={links}
      />}
    </div>
  );
}

function SummaryContent({ summary, detailsOpen, onToggleDetails, syncTypesExpanded, onToggleSyncTypes, links }: {
  summary: AdminSummary;
  detailsOpen: boolean;
  onToggleDetails: (open: boolean) => void;
  syncTypesExpanded: boolean;
  onToggleSyncTypes: () => void;
  links?: SummaryReportLinks;
}) {
  const { language, t } = useI18n();
  const l = useSummaryLabels();
  const locale = localeFor(language);
  const count = new Intl.NumberFormat(locale).format;
  const { stores, licenses, events, catalogue } = summary;
  const licenseDetail = l("licenseDetail")
    .replace("{valid}", count(licenses.valid))
    .replace("{expired}", count(licenses.expired))
    .replace("{blocked}", count(licenses.blocked));
  const catalogueCoverage = catalogue && l("catalogueCoverage")
    .replace("{covered}", count(catalogue.coveredStores))
    .replace("{total}", count(catalogue.stores));
  const catalogueDetail = [catalogue?.products == null ? l(catalogue ? "noCatalogueCapture" : "noData") : l("latestSnapshot"), catalogueCoverage]
    .filter(Boolean).join(" · ");

  return (
    <>
      <section className="saas-summary-panel" aria-labelledby="summary-portfolio-heading">
        <header className="saas-summary-heading">
          <h2 id="summary-portfolio-heading">{l("currentPortfolio")}</h2>
        </header>
        <div className="saas-summary-metrics">
          <SummaryMetric href="#/companies" label={t("companies")} value={count(summary.companies)} detail={l("registeredTotals")} />
          <SummaryMetric href="#/stores" label={t("stores")} value={count(stores.total)} detail={l("activeStores").replace("{count}", count(stores.active))} />
          <SummaryMetric href="#/licenses" label={t("licenses")} value={count(licenses.total)} detail={licenseDetail} />
          <SummaryMetric href={links?.inventory} label={l("catalogueProducts")} value={catalogue?.products == null ? "—" : count(catalogue.products)} detail={catalogueDetail} />
        </div>
      </section>
      <SummaryActivity summary={summary} links={links} />
      <div className="saas-summary-overview">
        <SummarySalesChart summary={summary} links={links} />
        <section className="saas-summary-panel saas-summary-sync" aria-labelledby="summary-sync-types-heading">
          <header className="saas-summary-heading">
            <h2 id="summary-sync-types-heading">{l("syncByType")}</h2>
            <div className="saas-summary-heading-actions">
              <p className="saas-summary-timestamp">{l("accumulated")}</p>
              {links && <a className="small-button" href={links.sync}>{l("viewSync")}</a>}
            </div>
          </header>
          <div className="saas-summary-panel-body">
            <CountTable caption={l("eventsByType")} category={t("type")} rows={events.byType} empty={l("noEventCoverage")} total={events.total}
              pageSize={4} expansion={{ open: syncTypesExpanded, toggle: onToggleSyncTypes }} />
            <p className="saas-summary-sync-help">{l("syncFailureHelp")}</p>
          </div>
        </section>
      </div>

      <RecentLicenseTable licenses={summary.recentLicenses} />

      <footer className="saas-summary-footer">
        <p className="saas-summary-timestamp">{l("asOf")} <DateTime value={summary.asOf} /></p>
      </footer>

      <details className="saas-summary-panel saas-summary-details" open={detailsOpen} onToggle={event => onToggleDetails(event.currentTarget.open)}>
        <summary className="saas-summary-heading">{l("summaryDetails")}</summary>
        <div className="saas-summary-panel-body">
          <SummaryAnalyticsDetails summary={summary} links={links} />
          <SummaryAccumulatedDetails summary={summary} />
        </div>
      </details>
    </>
  );
}

function SummaryAccumulatedDetails({ summary }: { summary: AdminSummary }) {
  const { language, t } = useI18n();
  const l = useSummaryLabels();
  const locale = localeFor(language);
  const count = new Intl.NumberFormat(locale).format;
  const { events, documents } = summary;

  return (
    <>
      <section className="saas-summary-panel" aria-labelledby="summary-other-totals-heading">
        <header className="saas-summary-heading"><h2 id="summary-other-totals-heading">{l("accumulated")}</h2></header>
        <div className="saas-summary-metrics">
          <Metric label={t("installations")} value={count(summary.installations)} />
          <Metric label={l("receivedDocuments")} value={count(documents.total)} />
          <Metric label={l("failedEvents")} value={count(events.byStatus.find(row => row.key === "ERROR")?.count ?? 0)} />
          <Metric label={l("productReferences")} value={count(documents.observedProductReferences)} />
        </div>
      </section>

      <div className="saas-summary-tables">
        <section className="saas-summary-panel" aria-labelledby="summary-sync-heading">
          <header className="saas-summary-heading"><h2 id="summary-sync-heading">{t("sync")}</h2></header>
          <div className="saas-summary-panel-body">
            <p className="saas-summary-coverage">
              {events.firstReceivedAt && events.lastReceivedAt ? (
                <><strong>{l("eventCoverage")}</strong> <DateTime value={events.firstReceivedAt} /> – <DateTime value={events.lastReceivedAt} /></>
              ) : l("noEventCoverage")}
            </p>
            <CountTable caption={l("eventsByStatus")} category={t("status")} rows={events.total ? events.byStatus : []} empty={l("noEventCoverage")} />
          </div>
        </section>

        <section className="saas-summary-panel" aria-labelledby="summary-documents-heading">
          <header className="saas-summary-heading"><h2 id="summary-documents-heading">{l("receivedDocuments")}</h2></header>
          <div className="saas-summary-panel-body">
            <div className="saas-summary-coverage">
              <h3>{l("coverage")}</h3>
              <p>{l("documentScope")}</p>
              <dl>
                <div><dt>{l("storesWithDocuments")}</dt><dd>{count(documents.storesWithDocuments)}</dd></div>
                <div><dt>{l("incompleteDocuments")}</dt><dd>{count(documents.incompleteDocuments)}</dd></div>
                <div><dt>{l("unknownRelationships")}</dt><dd>{count(documents.unknownRelationships)}</dd></div>
              </dl>
              <p>{l("productScope")}</p>
            </div>
            <div className="saas-summary-breakdowns">
              <CountTable caption={l("documentsByType")} category={t("type")} rows={documents.byType} empty={l("noDocuments")} />
              <CountTable caption={l("documentsByStatus")} category={t("status")} rows={documents.byStatus} empty={l("noDocuments")} />
            </div>
            <DocumentAmounts documents={documents} locale={locale} count={count} />
          </div>
        </section>
      </div>
    </>
  );
}

function CountTable({ caption, category, rows, empty, total, pageSize = 8, expansion }: {
  caption: string; category: string; rows: SummaryCount[]; empty: string; total?: number; pageSize?: number;
  expansion?: { open: boolean; toggle: () => void };
}) {
  const { language, t } = useI18n();
  const l = useSummaryLabels();
  const categoryLabel = useSummaryCategoryLabel();
  const count = new Intl.NumberFormat(localeFor(language)).format;
  const pagination = usePagination(rows, pageSize);
  const visibleRows = expansion ? expansion.open ? rows : rows.slice(0, pageSize) : pagination.rows;
  const bodyId = expansion ? "summary-sync-type-rows" : undefined;

  return (
    <div className="table-wrap saas-summary-table">
      <table>
        <caption>{caption}</caption>
        <thead><tr><th scope="col">{category}</th><th scope="col" className="saas-summary-number">{l("count")}</th></tr></thead>
        <tbody id={bodyId}>
          {visibleRows.map((row) => (
            <tr key={row.key}><th scope="row">{categoryLabel(row.key)}</th><td className="saas-summary-number">{count(row.count)}</td></tr>
          ))}
          {rows.length === 0 && <EmptyTableRow columns={2} text={empty} />}
        </tbody>
        {total !== undefined && <tfoot><tr><th scope="row">{t("total")}</th><td className="saas-summary-number">{count(total)}</td></tr></tfoot>}
      </table>
      {expansion ? rows.length > pageSize && <div className="saas-summary-sync-toggle">
        <button type="button" className="small-button" aria-expanded={expansion.open} aria-controls={bodyId} onClick={expansion.toggle}>
          {l(expansion.open ? "showFewerSyncTypes" : "showAllSyncTypes")}
        </button>
      </div> : <PaginationControls {...pagination} />}
    </div>
  );
}

function DocumentAmounts({ documents, locale, count }: { documents: AdminSummary["documents"]; locale: string; count: (value: number) => string }) {
  const { t } = useI18n();
  const l = useSummaryLabels();
  const amounts = documents.unknownRelationships > 0 ? [] : documents.amounts;
  const pagination = usePagination(amounts, 8);

  return (
    <div className="table-wrap saas-summary-table">
      <table aria-describedby={amounts.length ? "summary-amount-scope" : undefined}>
        <caption>{l("documentAmounts")}</caption>
        <thead><tr><th scope="col">{t("currency")}</th><th scope="col" className="saas-summary-number">{l("amountDocuments")}</th><th scope="col" className="saas-summary-number">{t("amount")}</th></tr></thead>
        <tbody>
          {pagination.rows.map((row) => (
            <tr key={row.currency}>
              <th scope="row">{row.currency}</th>
              <td className="saas-summary-number">{count(row.documents)}</td>
              <td className="saas-summary-number">{formatDocumentAmount(row.amount, locale)}</td>
            </tr>
          ))}
          {amounts.length === 0 && <EmptyTableRow columns={3} text={l(documents.unknownRelationships ? "amountsUnavailable" : "noAmounts")} />}
        </tbody>
      </table>
      {amounts.length > 0 && <p id="summary-amount-scope" className="saas-summary-note">{l("amountScope")}</p>}
      <PaginationControls {...pagination} />
    </div>
  );
}

function RecentLicenseTable({ licenses }: { licenses: AdminSummary["recentLicenses"] }) {
  const { t } = useI18n();
  const l = useSummaryLabels();

  return (
    <section className="saas-summary-panel saas-summary-recent" aria-labelledby="summary-licenses-heading">
      <header className="saas-summary-heading">
        <h2 id="summary-licenses-heading">{l("recentLicenses")}</h2>
        <a className="small-button" href="#/licenses">{l("viewLicenses")}</a>
      </header>
      <div className="table-wrap saas-summary-table" tabIndex={0}>
        <table aria-labelledby="summary-licenses-heading">
          <thead>
            <tr>
              <th scope="col">{t("license")}</th><th scope="col">{t("company")}</th><th scope="col">{t("store")}</th>
              <th scope="col">{l("province")}</th>
              <th scope="col">{t("status")}</th><th scope="col">{l("createdAt")}</th><th scope="col">{t("validUntil")}</th>
            </tr>
          </thead>
          <tbody>
            {licenses.map((license) => {
              const { label, tone } = licenseStatusPresentation(license.status, t);
              return (
                <tr key={license.id}>
                  <th scope="row"><a className="saas-summary-table-link" href={`#/licenses/detail?licenseId=${encodeURIComponent(license.id)}`}>{license.reference}</a></th>
                  <td>{license.companyName}</td>
                  <td>{license.storeCount > 1 ? `${l("multipleStores")} (${license.storeCount})` : license.storeName ?? l("storeUnassigned")}</td>
                  <td>{SPANISH_PROVINCES.find(item => item.code === license.province)?.name ?? "—"}</td>
                  <td><StatusPill status={label} tone={tone} /></td>
                  <td><DateTime value={license.createdAt} /></td>
                  <td><DateTime value={license.validUntil} /></td>
                </tr>
              );
            })}
            {licenses.length === 0 && <EmptyTableRow columns={7} text={t("noLicenses")} />}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function EmptyTableRow({ columns, text }: { columns: number; text: string }) {
  return <tr><td className="saas-summary-empty" colSpan={columns}>{text}</td></tr>;
}

function DateTime({ value }: { value: string }) {
  return <time dateTime={value}>{formatDate(value)}</time>;
}
