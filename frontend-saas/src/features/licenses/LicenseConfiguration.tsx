import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { api, request } from "../../lib/api";
import type { LicenseRow } from "../../lib/workspace-api";
import type { Credentials, InstallationSummary } from "../../lib/types";
import type { Notice } from "../../shared/types";
import { DateTimePicker, Input } from "../../shared/ui";
import { InstallationsTable } from "../../shared/license-tables";
import { LoadState } from "../../shared/workspace-ui";
import { errorMessage, formatDate, formatCurrency, toLocalInput, licenseStatusPresentation, billingStatusLabel } from "../../shared/lib";
import { useI18n } from "../../i18n";
import { useWorkspaceLabels } from "../../i18n/workspace";
import { LicenseDialog } from "./LicenseDialog";
import { useLicenseLabels } from "./license-labels";

export function LicenseConfiguration({ credentials, license, installations, permissions, onChanged, onClose, restoreFocus }: {
  credentials: Credentials; license: LicenseRow; installations: InstallationSummary[]; permissions: Set<string>;
  onChanged: () => void; onClose: () => void; restoreFocus: () => HTMLElement | null;
}) {
  const { t } = useI18n(); const l = useWorkspaceLabels(); const own = useLicenseLabels();
  const [detail, setDetail] = useState<LicenseRow | null>(null);
  const [renewal, setRenewal] = useState({ validUntil: "", maxWindows: 1, maxPda: 0 });
  const [loading, setLoading] = useState(true); const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false); const [notice, setNotice] = useState<Notice | null>(null);
  const [pairing, setPairing] = useState<string | null>(null);
  const [revoked, setRevoked] = useState<Record<string, InstallationSummary>>({});
  const mounted = useRef(false); const writing = useRef(false); const generation = useRef(0); const syncDraft = useRef(true);
  const activeContext = useRef({ token: credentials.accessToken, id: license.id });
  activeContext.current = { token: credentials.accessToken, id: license.id };

  // The editor has its own resource identity: a directory refresh must never
  // discard a draft or close a license that no longer matches the list filter.
  const loadDetail = useCallback(async (resetDraft = false) => {
    const sequence = ++generation.current;
    if (resetDraft) syncDraft.current = true;
    setLoading(true); setError(null);
    const current = () => mounted.current && sequence === generation.current
      && activeContext.current.token === credentials.accessToken && activeContext.current.id === license.id;
    try {
      const fresh = await request<LicenseRow>(credentials, "/api/v1/admin/license-workspace/" + encodeURIComponent(license.id));
      if (!current()) return;
      setDetail(fresh);
      if (syncDraft.current) {
        setRenewal({ validUntil: toLocalInput(new Date(fresh.validUntil)), maxWindows: fresh.maxWindows, maxPda: fresh.maxPda });
        syncDraft.current = false;
      }
    } catch (failure) { if (current()) setError(errorMessage(failure)); }
    finally { if (current()) setLoading(false); }
  }, [credentials.accessToken, license.id]);
  useEffect(() => {
    mounted.current = true; void loadDetail(true);
    return () => { mounted.current = false; generation.current++; };
  }, [loadDetail]);
  const unavailable = busy || loading || error !== null || detail === null;

  async function mutate(permission: string, operation: () => Promise<unknown>, resetDraft = false) {
    if (unavailable || writing.current || !permissions.has(permission)) return;
    const context = activeContext.current;
    const current = () => mounted.current && activeContext.current.token === context.token && activeContext.current.id === context.id;
    writing.current = true; setBusy(true); setNotice(null);
    try {
      await operation();
      if (!current()) return;
      setNotice({ type: "success", text: l("saved") }); onChanged();
      await loadDetail(resetDraft);
    } catch (failure) { if (current()) setNotice({ type: "error", text: errorMessage(failure) }); }
    finally { if (current()) { writing.current = false; setBusy(false); } }
  }
  function renew(event: FormEvent) {
    event.preventDefault();
    if (unavailable || !detail || !permissions.has("RENEW_LICENSE")) return;
    const parsed = new Date(renewal.validUntil);
    if (!renewal.validUntil || Number.isNaN(parsed.getTime())) { setNotice({ type: "error", text: own("dateRequired") }); return; }
    const validUntil = renewal.validUntil === toLocalInput(new Date(detail.validUntil)) ? detail.validUntil : parsed.toISOString();
    void mutate("RENEW_LICENSE", () => api.renewLicense(credentials, detail.reference, { ...renewal, validUntil }), true);
  }
  async function action(kind: "block" | "unblock" | "pairing") {
    const permission = { block: "BLOCK_LICENSE", unblock: "UNBLOCK_LICENSE", pairing: "REGENERATE_PAIRING_CODE" }[kind];
    if (unavailable || !detail || !permissions.has(permission)) return;
    if (kind !== "pairing" && !window.confirm(t("confirmDestructive"))) return;
    const context = activeContext.current;
    await mutate(permission, async () => {
      if (kind === "pairing") {
        const result = await api.regeneratePairingCode(credentials, detail.reference);
        if (mounted.current && activeContext.current.token === context.token && activeContext.current.id === context.id) setPairing(result.pairingCode);
      } else await request(credentials, `/api/v1/admin/licenses/${encodeURIComponent(detail.reference)}/${kind}`, { method: "POST" });
    });
  }
  function revoke(installation: InstallationSummary) {
    if (unavailable || !detail || installation.licenseReference !== detail.reference || !installation.active || !permissions.has("REVOKE_INSTALLATION")) return;
    const reason = window.prompt(t("revocationReasonPrompt"))?.trim(); if (!reason) return;
    if (reason.length < 5) { setNotice({ type: "error", text: t("revocationReasonRequired") }); return; }
    const context = activeContext.current;
    void mutate("REVOKE_INSTALLATION", async () => {
      const saved = await api.revokeInstallation(credentials, installation.installationId, reason);
      if (mounted.current && activeContext.current.token === context.token && activeContext.current.id === context.id) {
        setRevoked(current => ({ ...current, [saved.installationId]: saved }));
      }
    });
  }
  return <LicenseDialog reference={detail?.reference ?? license.reference} busy={busy} onClose={onClose} restoreFocus={restoreFocus}>
    {notice && <div className={"notice " + notice.type} role={notice.type === "error" ? "alert" : "status"}>{notice.text}</div>}
    <LoadState loading={loading} error={error} reload={() => void loadDetail()} />
    {detail && <>
      <p>{detail.companyName} · {detail.stores.map(store => `${store.internalCode ?? store.code} · ${store.name}`).join(" / ")}</p>
      <dl className="saas-license-detail-facts">
        <div><dt>{l("status")}</dt><dd>{licenseStatusPresentation(detail.status, t).label}</dd></div>
        <div><dt>{l("validation")}</dt><dd>{detail.lastValidatedAt ? formatDate(detail.lastValidatedAt) : "—"}</dd></div>
        <div><dt>{l("sync")}</dt><dd>{detail.lastSyncAt ? formatDate(detail.lastSyncAt) : "—"}</dd></div>
        <div><dt>{l("billing")}</dt><dd>{detail.companyBillingStatus ? billingStatusLabel(detail.companyBillingStatus, t) : "—"}</dd></div>
        <div><dt>{l("debt")}</dt><dd>{detail.companyDebt.map(debt => formatCurrency(debt.outstanding, debt.currency)).join(" / ") || l("paid")}</dd></div>
      </dl>
      <div className="toolbar">
        {permissions.has("BLOCK_LICENSE") && detail.status !== "BLOQUEADA_MANUAL" && <button disabled={unavailable} onClick={() => void action("block")}>{t("block")}</button>}
        {permissions.has("UNBLOCK_LICENSE") && detail.status === "BLOQUEADA_MANUAL" && <button disabled={unavailable} onClick={() => void action("unblock")}>{t("unblock")}</button>}
        {permissions.has("REGENERATE_PAIRING_CODE") && <button disabled={unavailable} onClick={() => void action("pairing")}>{t("generateCode")}</button>}
      </div>
      {permissions.has("RENEW_LICENSE") ? <form onSubmit={renew} className="compact-form-grid">
        <DateTimePicker label={l("expiry")} value={renewal.validUntil} onChange={validUntil => setRenewal({ ...renewal, validUntil })} required disabled={unavailable} />
        <Input label={own("windows")} type="number" min={1} value={String(renewal.maxWindows)} onChange={value => setRenewal({ ...renewal, maxWindows: Number(value) })} required disabled={unavailable} />
        <Input label={own("pda")} type="number" min={0} value={String(renewal.maxPda)} onChange={value => setRenewal({ ...renewal, maxPda: Number(value) })} required disabled={unavailable} />
        <button type="submit" disabled={unavailable}>{l("save")}</button>
      </form> : <p>{l("expiry")}: {formatDate(detail.validUntil)} · Windows: {detail.maxWindows} · PDA: {detail.maxPda}</p>}
      <InstallationsTable canRevoke={permissions.has("REVOKE_INSTALLATION")} busy={unavailable ? "pending" : null} onRevoke={revoke}
        installations={installations.filter(installation => installation.licenseReference === detail.reference)
          .map(installation => {
            const saved = revoked[installation.installationId];
            // Revocation does not project synchronization timestamps; keep the
            // last known value from the installation directory.
            return saved ? { ...installation, ...saved, lastSyncAt: saved.lastSyncAt ?? installation.lastSyncAt } : installation;
          })} />
    </>}
    {pairing && <div className="notice success" role="status">{t("activePairingCode")}: <strong>{pairing}</strong></div>}
  </LicenseDialog>;
}
