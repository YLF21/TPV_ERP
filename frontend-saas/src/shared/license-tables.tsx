import { useState } from "react";


import type { InstallationSummary, LicenseSummary } from "../lib/types";
import { LicenseAction } from "./types";
import { useI18n } from "../i18n/index";
import { normalizeSearch, licenseStatusPresentation, formatDate } from "./lib";
import { usePagination, EmptyState, Input, StatusPill, PaginationControls } from "./ui";
import { InstallationHealth } from "../features/health/CustomerHealthView";

export function LicenseTable({
  licenses,
  compact = false,
  onAction,
  busy,
  showPairingAction = true,
  showBlockAction = true,
  showUnblockAction = true,
  selectedCompanyId,
  onSelectCompany
}: {
  licenses: LicenseSummary[];
  compact?: boolean;
  onAction?: (reference: string, action: LicenseAction) => void;
  busy?: string | null;
  showPairingAction?: boolean;
  showBlockAction?: boolean;
  showUnblockAction?: boolean;
  selectedCompanyId?: string;
  onSelectCompany?: (companyId: string) => void;
}) {
  const { t } = useI18n();
  const [query, setQuery] = useState("");
  const filtered = compact ? licenses : licenses.filter((license) => [license.licenseReference, license.companyName, license.taxId].some((value) => normalizeSearch(value).includes(normalizeSearch(query))));
  const paging = usePagination(filtered);
  const showActionColumn = Boolean(onAction
    && (showPairingAction || showBlockAction || showUnblockAction));
  if (licenses.length === 0) return <EmptyState text={t("noLicenses")} />;
  return (
    <>
      {!compact && <div className="toolbar table-filter"><Input label={t("filterRecords")} value={query} onChange={setQuery} /></div>}
      <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t("license")}</th>
            <th>{t("company")}</th>
            <th>{t("status")}</th>
            <th>{t("validity")}</th>
            {!compact && <th>{t("quotas")}</th>}
            {!compact && showActionColumn && <th></th>}
          </tr>
        </thead>
        <tbody>
          {paging.rows.map((license) => (
            <tr
              key={license.licenseReference}
              className={[
                busy?.endsWith(license.licenseReference) ? "is-busy" : "",
                selectedCompanyId === license.companyId ? "is-selected" : ""
              ].filter(Boolean).join(" ")}
            >
              <td>
                <strong>{license.licenseReference}</strong>
                <small>{license.taxId}</small>
              </td>
              <td>
                <button className="link-button" type="button" onClick={() => onSelectCompany?.(license.companyId)}>
                  {license.companyName}
                </button>
                {!compact && selectedCompanyId === license.companyId && <small>{t("selected")}</small>}
              </td>
              <td>
                <StatusPill
                  status={licenseStatusPresentation(license.status, t).label}
                  tone={licenseStatusPresentation(license.status, t).tone}
                />
              </td>
              <td>{formatDate(license.validUntil)}</td>
              {!compact && <td>{license.maxWindows} Windows · {license.maxPda} PDA</td>}
              {!compact && showActionColumn && (
                <td className="row-actions">
                  {showPairingAction && (
                    <button
                      className="small-button code"
                      type="button"
                      onClick={() => onAction?.(license.licenseReference, "pairing")}
                      disabled={busy === `pairing:${license.licenseReference}`}
                      aria-label={`${t("generateCode")} ${license.licenseReference}`}
                    >
                      {busy === `pairing:${license.licenseReference}` ? t("generating") : t("generateCode")}
                    </button>
                  )}
                  {license.status === "BLOQUEADA_MANUAL" ? (
                    showUnblockAction && (
                      <button className="small-button" type="button" onClick={() => onAction?.(license.licenseReference, "unblock")} disabled={busy === `unblock:${license.licenseReference}`}>
                        {t("unblock")}
                      </button>
                    )
                  ) : (
                    showBlockAction && (
                      <button className="small-button danger" type="button" onClick={() => onAction?.(license.licenseReference, "block")} disabled={busy === `block:${license.licenseReference}`}>
                        {t("block")}
                      </button>
                    )
                  )}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
      </div>
      {!compact && <PaginationControls {...paging} />}
    </>
  );
}

export function InstallationsTable({
  installations,
  canRevoke = false,
  busy = null,
  onRevoke
}: {
  installations: InstallationSummary[];
  canRevoke?: boolean;
  busy?: string | null;
  onRevoke?: (installation: InstallationSummary) => void;
}) {
  const { t } = useI18n();
  if (installations.length === 0) return <EmptyState text={t("noLinkedInstallations")} />;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>{t("installations")}</th>
            <th>{t("status")}</th>
            <th>{t("license")}</th>
            <th>{t("linkedAt")}</th>
            <th>{t("lastValidation")}</th><th>{t("lastSyncAt")}</th>
            <th>{t("deviceDetails")}</th>
            {canRevoke && <th>{t("actions")}</th>}
          </tr>
        </thead>
        <tbody>
          {installations.map((installation) => (
            <tr key={installation.installationId}>
              <td>
                <strong>{installation.installationReference}</strong>
                <small>{installation.installationId}</small>
              </td>
              <td>
                <StatusPill
                  status={installation.active ? t("active") : t("revoked")}
                  tone={installation.active ? "ok" : "warning"}
                />
                {!installation.active && installation.revokedAt && (
                  <small>{t("revokedAt")}: {formatDate(installation.revokedAt)}</small>
                )}
                {!installation.active && installation.revokedBy && (
                  <small>{t("revokedBy")}: {installation.revokedBy}</small>
                )}
                {!installation.active && installation.revocationReason && (
                  <small>{installation.revocationReason}</small>
                )}
              </td>
              <td>{installation.licenseReference}</td>
              <td>{formatDate(installation.linkedAt)}</td>
              <td>
                {installation.lastValidatedAt ? formatDate(installation.lastValidatedAt) : t("pending")}
                <InstallationHealth installation={installation} />
              </td>
              <td>{installation.lastSyncAt ? formatDate(installation.lastSyncAt) : "—"}</td>
              <td>
                <strong>{installation.terminalName || t("notAvailable")}</strong>
                <small>{t("appVersion")}: {installation.appVersion || t("notAvailable")}</small>
                <small>{t("operatingSystem")}: {installation.operatingSystem || t("notAvailable")}</small>
                <small>{t("lastIp")}: {installation.lastIp || t("notAvailable")}</small>
              </td>
              {canRevoke && (
                <td>
                  {installation.active && (
                    <button
                      className="danger-button subtle"
                      type="button"
                      disabled={busy !== null}
                      onClick={() => onRevoke?.(installation)}
                    >
                      {t("revokeInstallation")}
                    </button>
                  )}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
