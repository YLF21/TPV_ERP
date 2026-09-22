import { FormEvent, useMemo, useRef, useState } from "react";
import { api, request } from "../../lib/api";

import type {
  Credentials,
  IntegrationEndpoint,
  LicenseSummary,
} from "../../lib/types";
import { Notice } from "../../shared/types";
import { useI18n } from "../../i18n/index";
import {
  uniqueCompanies,
  normalizeSearch,
  errorMessage,
  isValidUrl,
  formatMoney,
  formatDate,
} from "../../shared/lib";
import {
  Metric,
  SectionHeader,
  Input,
  Select,
  EmptyState,
  StatusPill,
} from "../../shared/ui";

import { useRemote } from "../../app/RefreshContext";
import { LoadState } from "../../shared/workspace-ui";
import { useWorkspaceLabels } from "../../i18n/workspace";

export function ReportsView({
  mode = "reports",
  credentials,
  licenses,
  permissions,
  onNotice,
}: {
  mode?: "reports" | "integrations";
  credentials: Credentials;
  licenses: LicenseSummary[];
  permissions: Set<string>;
  onNotice: (notice: Notice) => void;
}) {
  const { t } = useI18n();
  const l = useWorkspaceLabels();
  const companies = useMemo(() => uniqueCompanies(licenses), [licenses]);
  const reportState = useRemote(
    () =>
      mode === "reports"
        ? api.advancedReports(credentials)
        : Promise.resolve(null),
    [credentials.accessToken, mode],
  );
  const integrationsState = useRemote(
    () =>
      mode === "integrations"
        ? api.integrations(credentials)
        : Promise.resolve([]),
    [credentials.accessToken, mode],
  );
  const report = reportState.data;
  const integrations = integrationsState.data ?? [];
  const [runIntegration, setRunIntegration] = useState("");
  type Run = {
    id: string;
    attempt: number;
    status: string;
    deliveryMode: string;
    errorCode: string | null;
    errorMessage: string | null;
    startedAt: string;
    completedAt: string | null;
  };
  const runs = useRemote(
    () =>
      runIntegration
        ? request<Run[]>(
            credentials,
            `/api/v1/admin/integrations/${runIntegration}/runs`,
          )
        : Promise.resolve([]),
    [credentials.accessToken, runIntegration],
  );
  const [integrationFilter, setIntegrationFilter] = useState("");
  const [form, setForm] = useState({
    companyId: "",
    name: "",
    integrationType: "WEBHOOK",
    status: "ACTIVA",
    targetUrl: "",
    apiKey: "",
  });
  const [busy, setBusy] = useState(false);
  const [runningId, setRunningId] = useState<string | null>(null);
  const runningRef = useRef(false);
  const operationKeys = useRef(new Map<string, string>());
  const canManage = permissions.has("MANAGE_INTEGRATIONS");
  const filteredIntegrations = integrations.filter((item) =>
    [
      item.name,
      item.companyName ?? "",
      item.integrationType,
      item.status,
      item.targetUrl ?? "",
    ].some((value) =>
      normalizeSearch(value).includes(normalizeSearch(integrationFilter)),
    ),
  );

  async function createIntegration(event: FormEvent) {
    event.preventDefault();
    if (!canManage) {
      onNotice({ type: "error", text: t("noPermissionAction") });
      return;
    }
    if (form.targetUrl && !isValidUrl(form.targetUrl)) {
      onNotice({ type: "error", text: t("invalidUrl") });
      return;
    }
    setBusy(true);
    try {
      await api.createIntegration(credentials, {
        ...form,
        companyId: form.companyId || null,
      });
      setForm({
        companyId: "",
        name: "",
        integrationType: "WEBHOOK",
        status: "ACTIVA",
        targetUrl: "",
        apiKey: "",
      });
      integrationsState.reload();
      runs.reload();
      onNotice({ type: "success", text: t("itemCreated") });
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  }

  async function markSynced(id: string) {
    if (runningRef.current) return;
    runningRef.current = true;
    setRunningId(id);
    const key = operationKeys.current.get(id) ?? crypto.randomUUID();
    operationKeys.current.set(id, key);
    try {
      await api.markIntegrationSynced(credentials, id, key);
      operationKeys.current.delete(id);
      integrationsState.reload();
      runs.reload();
    } catch (error) {
      onNotice({ type: "error", text: errorMessage(error) });
    } finally {
      runningRef.current = false;
      setRunningId(null);
    }
  }

  return (
    <div className="view-grid">
      {mode === "reports" && (
        <>
          <LoadState {...reportState} />
          {report && (
            <>
              <section className="metric-grid">
                <Metric
                  label={t("invoicedTotal")}
                  value={formatMoney(report?.invoicedTotal ?? "0")}
                />
                <Metric
                  label={t("paidTotal")}
                  value={formatMoney(report?.paidTotal ?? "0")}
                />
                <Metric
                  label={t("salesTotal")}
                  value={formatMoney(report?.salesTotal ?? "0")}
                />
                <Metric
                  label={t("inventoryMovements")}
                  value={report?.inventoryMovements ?? "-"}
                />
                <Metric
                  label={t("activeIntegrations")}
                  value={report?.activeIntegrations ?? "-"}
                />
              </section>
              <section className="content-section">
                <SectionHeader
                  title={t("advancedReports")}
                  subtitle={t("advancedReportsSubtitle")}
                />
                <div className="table-wrap">
                  <table>
                    <tbody>
                      <tr>
                        <td>{t("company")}</td>
                        <td>{report?.companies ?? 0}</td>
                      </tr>
                      <tr>
                        <td>{t("invoices")}</td>
                        <td>{report?.invoices ?? 0}</td>
                      </tr>
                      <tr>
                        <td>{t("salesDocuments")}</td>
                        <td>{report?.salesDocuments ?? 0}</td>
                      </tr>
                      <tr>
                        <td>{t("integrations")}</td>
                        <td>{report?.integrations ?? 0}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </section>
            </>
          )}
        </>
      )}
      {mode === "integrations" && (
        <section className="content-section">
          <LoadState {...integrationsState} />
          <SectionHeader
            title={t("integrations")}
            subtitle={t("integrationsSubtitle")}
          />
          <div className="toolbar">
            <Input
              label={t("globalSearch")}
              value={integrationFilter}
              onChange={setIntegrationFilter}
            />
          </div>
          {canManage && (
            <form className="compact-form-grid" onSubmit={createIntegration}>
              <label>
                {t("company")}
                <select
                  className="control-input"
                  value={form.companyId}
                  onChange={(event) =>
                    setForm({ ...form, companyId: event.target.value })
                  }
                >
                  <option value="">{t("allCompanies")}</option>
                  {companies.map((company) => (
                    <option key={company.companyId} value={company.companyId}>
                      {company.companyName}
                    </option>
                  ))}
                </select>
              </label>
              <Select
                label={t("status")}
                value={form.status}
                options={["ACTIVA", "PAUSADA"]}
                onChange={(status) => setForm({ ...form, status })}
              />
              <Input
                label={t("name")}
                value={form.name}
                onChange={(name) => setForm({ ...form, name })}
                required
              />
              <Input
                label={t("integrationType")}
                value={form.integrationType}
                onChange={(integrationType) =>
                  setForm({ ...form, integrationType })
                }
                required
              />
              <Input
                label={t("targetUrl")}
                value={form.targetUrl}
                onChange={(targetUrl) => setForm({ ...form, targetUrl })}
              />
              <Input
                label={t("apiKey")}
                type="password"
                value={form.apiKey}
                onChange={(apiKey) => setForm({ ...form, apiKey })}
              />
              <button className="primary-button" type="submit" disabled={busy}>
                {t("createIntegration")}
              </button>
            </form>
          )}
          <IntegrationsTable
            onRuns={setRunIntegration}
            busy={runningId !== null}
            integrations={filteredIntegrations}
            canManage={canManage}
            onSync={(id) => void markSynced(id)}
          />
        </section>
      )}
      {mode === "integrations" && runIntegration && (
        <section className="content-section">
          <h3>{l("runs")}</h3>
          <LoadState {...runs} />
          {runs.data && (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{l("status")}</th>
                    <th>{l("occurrences")}</th>
                    <th>{l("from")}</th>
                    <th>{l("to")}</th>
                    <th>{l("detail")}</th>
                  </tr>
                </thead>
                <tbody>
                  {runs.data.map((r) => (
                    <tr key={r.id}>
                      <td>{r.status}</td>
                      <td>{r.attempt}</td>
                      <td>{formatDate(r.startedAt)}</td>
                      <td>{r.completedAt ? formatDate(r.completedAt) : "—"}</td>
                      <td>
                        {[r.errorCode, r.errorMessage]
                          .filter(Boolean)
                          .join(" · ") || r.deliveryMode}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {runs.data.length === 0 && <p>{l("empty")}</p>}
            </div>
          )}
        </section>
      )}
    </div>
  );
}

export function IntegrationsTable({
  integrations,
  canManage,
  onSync,
  onRuns,
  busy = false,
}: {
  integrations: IntegrationEndpoint[];
  canManage: boolean;
  onSync: (id: string) => void;
  busy?: boolean;
  onRuns: (id: string) => void;
}) {
  const { t } = useI18n();
  const l = useWorkspaceLabels();
  if (integrations.length === 0)
    return <EmptyState text={t("noEventsForFilter")} />;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t("name")}</th>
            <th>{t("company")}</th>
            <th>{t("integrationType")}</th>
            <th>{t("status")}</th>
            <th>{t("apiKeyPreview")}</th>
            <th>{t("lastSyncAt")}</th>
            <th>{l("runs")}</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {integrations.map((item) => (
            <tr key={item.id}>
              <td>
                <strong>{item.name}</strong>
                <small>{item.targetUrl || "-"}</small>
              </td>
              <td>{item.companyName || t("allCompanies")}</td>
              <td>{item.integrationType}</td>
              <td>
                <StatusPill
                  status={item.status}
                  tone={item.status === "ACTIVA" ? "ok" : "muted"}
                />
              </td>
              <td>{item.apiKeyPreview || "-"}</td>
              <td>{item.lastSyncAt ? formatDate(item.lastSyncAt) : "-"}</td>
              <td>
                <button type="button" onClick={() => onRuns(item.id)}>
                  {l("runs")}
                </button>
              </td>
              {canManage && (
                <td className="table-actions">
                  <button
                    className="secondary-button"
                    type="button"
                    disabled={busy || item.status !== "ACTIVA"}
                    onClick={() => onSync(item.id)}
                  >
                    {l("execute")}
                  </button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
